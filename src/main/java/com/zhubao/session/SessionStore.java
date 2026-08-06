package com.zhubao.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 会话存储（spec F9）：
 * <ul>
 *   <li>目录：~/.zhu-code-agent/sessions/（可注入路径便于测试）</li>
 *   <li>文件：{id}.json，原子写（先写 .tmp 再 rename），避免半截文件</li>
 *   <li>损坏文件：list/load 时跳过并打印警告，不崩溃（N3）</li>
 *   <li>安全：会话 JSON 只含 provider 快照，不含 api_key（N4）</li>
 * </ul>
 */
public class SessionStore {

    private final Path sessionsDir;
    private final ObjectMapper mapper;

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
            files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .forEach(p -> load(p).ifPresent(s -> metas.add(s.getMeta())));
        } catch (IOException e) {
            return List.of();
        }
        metas.sort(Comparator.comparing(SessionMeta::updatedAt).reversed());
        return List.copyOf(metas);
    }

    /** 按 id 加载会话（id 即文件名前缀） */
    public Optional<Session> load(String id) {
        return load(sessionsDir.resolve(id + ".json"));
    }

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

    /** 保存会话：原子写（tmp + rename） */
    public void save(Session session) {
        ensureDir();
        String id = session.getMeta().id();
        Path target = sessionsDir.resolve(id + ".json");
        Path tmp = sessionsDir.resolve(id + ".json.tmp");
        try {
            mapper.writeValue(tmp.toFile(), session);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new SessionException("保存会话失败: " + e.getMessage(), e);
        }
    }

    private void ensureDir() {
        try {
            Files.createDirectories(sessionsDir);
        } catch (IOException e) {
            throw new SessionException("创建会话目录失败: " + sessionsDir + "（" + e.getMessage() + "）", e);
        }
    }
}
