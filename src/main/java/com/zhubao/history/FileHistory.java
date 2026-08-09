package com.zhubao.history;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zhubao.tool.PathGuard;
import com.zhubao.tool.ToolException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 文件历史：检查点落盘 + 快照 + undo/rewind（M3，spec F3，统一检查点机制）。
 * <ul>
 *   <li>存储：~/.zhu-code-agent/snapshots/&lt;会话ID&gt;/checkpoints.json（原子写，仿 SessionStore；损坏跳过）</li>
 *   <li>快照：write_file/edit_file 写前调用 snapshotBefore；超 10MB 跳过；失败 discardLast</li>
 *   <li>回滚：undo = 回退最近检查点；rewindTo(k) = 倒序恢复 k..last 并丢弃其后；恢复前再过 PathGuard</li>
 *   <li>bash 引起的工作区改动不追踪（spec F3 声明限制）</li>
 * </ul>
 */
public final class FileHistory {

    /** 单文件快照大小上限（spec F3/N6）：超过不参与快照/回滚 */
    public static final long MAX_SNAPSHOT_BYTES = 10L * 1024 * 1024;

    private static final String INDEX_FILE = "checkpoints.json";

    private final Path snapshotsRoot;
    private final PathGuard guard;
    private final ObjectMapper mapper;

    public FileHistory(Path snapshotsRoot, PathGuard guard) {
        this.snapshotsRoot = snapshotsRoot;
        this.guard = guard;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    /** 快照结果 */
    public record SnapshotOutcome(boolean taken, boolean skippedOversize) {
        static SnapshotOutcome captured() {
            return new SnapshotOutcome(true, false);
        }

        static SnapshotOutcome skipped(boolean oversize) {
            return new SnapshotOutcome(false, oversize);
        }
    }

    /**
     * 写操作前调用：解析路径并读取当前内容，生成检查点落盘。
     *
     * @param sessionId 会话 id（快照按会话隔离）
     * @param rawPath   工具参数里的原始路径（相对/绝对均可）
     * @param summary   变更摘要（如 "edit_file: src/Foo.java"）
     */
    public SnapshotOutcome snapshotBefore(String sessionId, String rawPath, String summary) {
        try {
            Path target = guard.resolveInWorkspace(rawPath);
            guard.assertNotForbidden(target);
            FileCheckpoint cp;
            if (!Files.exists(target)) {
                cp = new FileCheckpoint(null, Instant.now(), target.toString(), false, null, summary);
            } else if (Files.size(target) > MAX_SNAPSHOT_BYTES) {
                return SnapshotOutcome.skipped(true);
            } else {
                String content = Files.readString(target, StandardCharsets.UTF_8);
                cp = new FileCheckpoint(null, Instant.now(), target.toString(), true, content, summary);
            }
            append(sessionId, cp); // 一次 load 内计算 id（review P3：避免 nextId+append 双重全量读取）
            return SnapshotOutcome.captured();
        } catch (ToolException | IOException e) {
            // 越界/禁写/读失败：跳过快照，不阻塞写（工具自身会校验）
            return SnapshotOutcome.skipped(false);
        }
    }

    /** 写操作失败时调用：丢弃刚追加的检查点（避免垃圾检查点） */
    public void discardLast(String sessionId) {
        List<FileCheckpoint> list = list(sessionId);
        if (list.isEmpty()) {
            return;
        }
        list.remove(list.size() - 1);
        save(sessionId, list);
    }

    /** 检查点列表（插入序：旧 → 新；/rewind 展示可倒序） */
    public List<FileCheckpoint> list(String sessionId) {
        return load(sessionId);
    }

    /** /undo：回退最近一个检查点（快捷） */
    public RollbackResult undo(String sessionId) {
        List<FileCheckpoint> list = list(sessionId);
        if (list.isEmpty()) {
            return RollbackResult.none();
        }
        return rewindTo(sessionId, list.size() - 1);
    }

    /**
     * /rewind：回退到第 index 个检查点——倒序恢复 index..last，其后检查点丢弃。
     * 任一恢复失败则不截断（保留检查点供重试），返回 ok=false 与可读信息。
     */
    public RollbackResult rewindTo(String sessionId, int index) {
        List<FileCheckpoint> list = list(sessionId);
        if (list.isEmpty()) {
            return RollbackResult.none();
        }
        if (index < 0 || index >= list.size()) {
            return new RollbackResult(false, "检查点序号越界（0.." + (list.size() - 1) + "）", List.of());
        }
        List<String> actions = new ArrayList<>();
        boolean allOk = true;
        for (int i = list.size() - 1; i >= index; i--) {
            FileCheckpoint cp = list.get(i);
            try {
                Path target = guard.resolveInWorkspace(cp.path());
                guard.assertNotForbidden(target);
                if (cp.existed()) {
                    Files.writeString(target, cp.beforeContent(), StandardCharsets.UTF_8);
                    actions.add("已恢复 " + target.getFileName() + "（" + label(cp) + "）");
                } else {
                    Files.deleteIfExists(target);
                    actions.add("已删除新建文件 " + target.getFileName() + "（" + label(cp) + "）");
                }
            } catch (ToolException | IOException e) {
                allOk = false;
                actions.add("✗ " + cp.path() + "：恢复失败（" + e.getMessage() + "）");
            }
        }
        if (!allOk) {
            return new RollbackResult(false, "回滚部分失败，检查点已保留（可重试）", actions);
        }
        // 全部成功：丢弃 index 及之后
        String target = "初始状态";
        if (!list.isEmpty()) {
            target = "检查点 #" + list.get(index).id() + " 之前";
        }
        list.subList(index, list.size()).clear();
        save(sessionId, list);
        return new RollbackResult(true, "已回滚到 " + target, actions);
    }

    private static String label(FileCheckpoint cp) {
        String op = cp.summary() == null ? cp.path() : cp.summary();
        return op + " 前的状态";
    }

    // ── 持久化 ────────────────────────────────────────────────

    private Path dirFor(String sessionId) {
        return snapshotsRoot.resolve(sessionId);
    }

    private Path fileFor(String sessionId) {
        return dirFor(sessionId).resolve(INDEX_FILE);
    }

    private List<FileCheckpoint> load(String sessionId) {
        Path file = fileFor(sessionId);
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            FileCheckpoint[] arr = mapper.readValue(file.toFile(), FileCheckpoint[].class);
            List<FileCheckpoint> out = new ArrayList<>();
            if (arr != null) {
                for (FileCheckpoint cp : arr) {
                    if (cp != null) {
                        out.add(cp);
                    }
                }
            }
            return out;
        } catch (IOException e) {
            // 损坏：可读警告后跳过，进程不崩溃
            System.err.println("[快照] 检查点文件损坏已跳过: " + file.getFileName() + "（" + e.getMessage() + "）");
            return new ArrayList<>();
        }
    }

    /** 追加检查点：一次 load 计算递增 id 后落盘（review P3，消除重复全量读取） */
    private void append(String sessionId, FileCheckpoint cp) {
        List<FileCheckpoint> list = load(sessionId);
        String id = String.format(Locale.ROOT, "%04d", list.size() + 1);
        FileCheckpoint withId = new FileCheckpoint(id, cp.timestamp(), cp.path(),
                cp.existed(), cp.beforeContent(), cp.summary());
        list.add(withId);
        save(sessionId, list);
    }

    private void save(String sessionId, List<FileCheckpoint> list) {
        try {
            Path dir = dirFor(sessionId);
            Files.createDirectories(dir);
            Path target = fileFor(sessionId);
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            mapper.writeValue(tmp.toFile(), list);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // 快照落盘失败不阻塞工具执行；记录可读警告
            System.err.println("[快照] 检查点写入失败: " + e.getMessage());
        }
    }

}
