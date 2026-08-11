package com.zhubao.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import com.zhubao.conversation.ContentBlock;
import com.zhubao.conversation.Message;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 会话存储（spec F9 / M4 spec F6）：
 * <ul>
 *   <li>目录：~/.zhu-code-agent/sessions/（可注入路径便于测试）</li>
 *   <li>新格式：{id}.jsonl 追加写——每次 save 追加一行 meta（last-wins）+ 自内存 cursor 起的新消息行（O(1)）；
 *       单进程单写者，无需文件锁；损坏行跳过并警告</li>
 *   <li>旧格式：{id}.json 仍可读取（迁移逻辑沿用）；加载旧 .json 后首次 save 转存 .jsonl 并删除旧文件</li>
 *   <li>安全：会话 JSON 只含 provider 快照，不含 api_key（N4）</li>
 * </ul>
 */
public class SessionStore {

    private final Path sessionsDir;
    private final ObjectMapper mapper;
    /** 已写入的会话消息行数（进程内 cursor，防重复追加；load/迁移时初始化） */
    private final Map<String, Integer> writtenCount = new HashMap<>();

    public SessionStore(Path sessionsDir) {
        this.sessionsDir = sessionsDir;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    public Path getSessionsDir() {
        return sessionsDir;
    }

    /** 按最后更新时间倒序列出所有会话元数据；损坏文件跳过 */
    public List<SessionMeta> list() {
        ensureDir();
        List<SessionMeta> metas = new ArrayList<>();
        try (Stream<Path> files = Files.list(sessionsDir)) {
            List<Path> paths = files.filter(p -> {
                String name = p.getFileName().toString();
                return name.endsWith(".json") || name.endsWith(".jsonl");
            }).toList();
            // M4 review-P3：.jsonl 优先、按 id 去重（迁移崩溃窗口可能 .json/.jsonl 并存）
            java.util.Map<String, SessionMeta> byId = new java.util.LinkedHashMap<>();
            for (Path p : paths) {
                if (p.getFileName().toString().endsWith(".jsonl")) {
                    loadByPath(p).ifPresent(s -> byId.putIfAbsent(s.getMeta().id(), s.getMeta()));
                }
            }
            for (Path p : paths) {
                if (p.getFileName().toString().endsWith(".json")) {
                    loadByPath(p).ifPresent(s -> byId.putIfAbsent(s.getMeta().id(), s.getMeta()));
                }
            }
            metas.addAll(byId.values());
        } catch (IOException e) {
            return List.of();
        }
        metas.sort(Comparator.comparing(SessionMeta::updatedAt).reversed());
        return List.copyOf(metas);
    }

    /** 按 id 加载会话（优先 .jsonl，其次旧 .json） */
    public Optional<Session> load(String id) {
        Path jsonl = sessionsDir.resolve(id + ".jsonl");
        if (Files.exists(jsonl)) {
            return loadJsonl(jsonl);
        }
        return load(sessionsDir.resolve(id + ".json"));
    }

    private Optional<Session> loadByPath(Path file) {
        if (file.getFileName().toString().endsWith(".jsonl")) {
            return loadJsonl(file);
        }
        return load(file);
    }

    /** 旧 .json 读取（含 M1 字符串 content → TextBlock 迁移，由 Message.setContentNode 处理） */
    private Optional<Session> load(Path file) {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            Session session = mapper.readValue(file.toFile(), Session.class);
            if (session == null || session.getMeta() == null) {
                return Optional.empty();
            }
            return Optional.of(session);
        } catch (Exception e) {
            // 损坏文件：可读警告后跳过，进程不崩溃
            System.err.println("[会话] 文件损坏已跳过: " + file.getFileName() + "（" + e.getMessage() + "）");
            return Optional.empty();
        }
    }

    /** .jsonl 读取：最后一条 meta 生效，损坏行跳过 */
    private Optional<Session> loadJsonl(Path file) {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        SessionMeta lastMeta = null;
        List<Message> messages = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonNode node = mapper.readTree(line);
                    if (node.has("role")) {
                        Message msg = mapper.treeToValue(node, Message.class);
                        if (msg != null) {
                            messages.add(msg);
                        }
                    } else {
                        SessionMeta meta = mapper.treeToValue(node, SessionMeta.class);
                        if (meta != null) {
                            lastMeta = meta;
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[会话] 损坏行已跳过: " + file.getFileName() + "（" + e.getMessage() + "）");
                }
            }
        } catch (IOException e) {
            return Optional.empty();
        }
        if (lastMeta == null) {
            return Optional.empty();
        }
        writtenCount.put(lastMeta.id(), messages.size());
        return Optional.of(new Session(lastMeta, messages));
    }

    /** 单条 tool_result 落盘上限（spec F9）：超出截断并标注；内存仍保留完整结果 */
    public static final int TOOL_RESULT_PERSIST_CAP = 64 * 1024;

    /** 保存会话（M4 spec F6）：追加写 .jsonl；旧 .json 首次保存迁移为 .jsonl */
    public void save(Session session) {
        ensureDir();
        String id = session.getMeta().id();
        Path jsonl = sessionsDir.resolve(id + ".jsonl");
        Path legacy = sessionsDir.resolve(id + ".json");
        try {
            if (Files.exists(legacy)) {
                // 旧 .json 存在 → 全量转存 .jsonl（tmp + rename），成功后删除旧文件
                writeFullJsonl(jsonl, session);
                Files.deleteIfExists(legacy);
                writtenCount.put(id, session.getMessages().size());
                return;
            }
            int already = writtenCount.getOrDefault(id, -1);
            if (already < 0) {
                // 本进程首次接触该会话：统计文件里已有的消息行数（跨进程恢复场景）
                already = countMessageLines(jsonl);
                writtenCount.put(id, already);
            }
            if (session.getMessages().size() < already) {
                // M4 review-P1：历史收缩（压缩/snip/截断）时追加写会残留陈旧消息，
                // 重载会复活压缩前历史 → 整文件重写
                writeFullJsonl(jsonl, session);
                writtenCount.put(id, session.getMessages().size());
            } else {
                appendMessages(jsonl, session, already);
            }
        } catch (IOException e) {
            throw new SessionException("保存会话失败: " + e.getMessage(), e);
        }
    }

    /** 追加：meta 行（last-wins）+ 自 cursor 起的新消息行（均经 64KB 截断） */
    private void appendMessages(Path jsonl, Session session, int already) throws IOException {
        List<Message> messages = truncateToolResults(session).getMessages();
        try (BufferedWriter writer = Files.newBufferedWriter(jsonl, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            writer.write(mapper.writeValueAsString(session.getMeta()) + "\n");
            for (int i = already; i < messages.size(); i++) {
                writer.write(mapper.writeValueAsString(messages.get(i)) + "\n");
            }
        }
        writtenCount.put(session.getMeta().id(), messages.size());
    }

    /** 全量写入 .jsonl（迁移/首次），tmp + rename 原子替换 */
    private void writeFullJsonl(Path jsonl, Session session) throws IOException {
        String id = session.getMeta().id();
        Path tmp = sessionsDir.resolve(id + ".jsonl.tmp");
        List<Message> messages = truncateToolResults(session).getMessages();
        try (BufferedWriter writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            writer.write(mapper.writeValueAsString(session.getMeta()) + "\n");
            for (Message m : messages) {
                writer.write(mapper.writeValueAsString(m) + "\n");
            }
        }
        Files.move(tmp, jsonl, StandardCopyOption.REPLACE_EXISTING);
    }

    /** 统计 .jsonl 中已有的消息行数（meta 行不计） */
    private int countMessageLines(Path jsonl) {
        if (!Files.exists(jsonl)) {
            return 0;
        }
        int count = 0;
        try (BufferedReader reader = Files.newBufferedReader(jsonl, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    if (mapper.readTree(line).has("role")) {
                        count++;
                    }
                } catch (Exception ignored) {
                    // 损坏行不计
                }
            }
        } catch (IOException ignored) {
            return 0;
        }
        return count;
    }

    /** 深拷贝会话并把超限的 tool_result 输出截断（仅落盘视图，不改内存对象） */
    static Session truncateToolResults(Session session) {
        List<Message> messages = session.getMessages();
        List<Message> out = new ArrayList<>(messages.size());
        boolean changed = false;
        for (Message m : messages) {
            List<ContentBlock> blocks = m.getBlocks();
            List<ContentBlock> newBlocks = null;
            for (int i = 0; i < blocks.size(); i++) {
                ContentBlock b = blocks.get(i);
                if (b instanceof ContentBlock.ToolResultBlock tr
                        && tr.output() != null && tr.output().length() > TOOL_RESULT_PERSIST_CAP) {
                    if (newBlocks == null) {
                        newBlocks = new ArrayList<>(blocks);
                    }
                    String truncated = tr.output().substring(0, TOOL_RESULT_PERSIST_CAP)
                            + "\n…（已截断，共 " + tr.output().length() + " 字节）";
                    newBlocks.set(i, new ContentBlock.ToolResultBlock(tr.id(), tr.name(), tr.isError(), truncated));
                    changed = true;
                }
            }
            if (newBlocks != null) {
                out.add(new Message(m.getRole(), newBlocks, m.getThinking(), m.getThinkingSignature()));
            } else {
                out.add(m);
            }
        }
        return changed ? new Session(session.getMeta(), out) : session;
    }

    private void ensureDir() {
        try {
            Files.createDirectories(sessionsDir);
        } catch (IOException e) {
            throw new SessionException("创建会话目录失败: " + sessionsDir + "（" + e.getMessage() + "）", e);
        }
    }
}
