package com.zhubao.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 路径边界守卫（spec F6）：所有文件类工具的操作路径必须解析后位于工作区（cwd）内。
 *
 * <ul>
 *   <li>相对路径基于 root 解析，归一化后必须在 root 内（拒绝 {@code ..} 逃逸）</li>
 *   <li>存在时做 realpath（解析符号链接），符号链接指向 root 外 → 拒绝</li>
 *   <li>不存在时对最近存在的祖先做 realpath，再拼接剩余部分校验</li>
 *   <li>root 与候选路径统一经 realpath 比较（避免 /var ↔ /private/var 类平台符号链接误判）</li>
 *   <li>禁止写 .git/ 与程序自身目录 ~/.zhu-code-agent/</li>
 * </ul>
 */
public final class PathGuard {

    private final Path root;
    private final Path rootReal;

    public PathGuard(Path root) {
        this.root = root.toAbsolutePath().normalize();
        this.rootReal = realpathOf(this.root);
    }

    public Path root() {
        return root;
    }

    /**
     * 把原始路径解析为工作区内的绝对路径；越界抛 {@link ToolException}。
     *
     * @param raw 用户/模型给的路径（相对或绝对）
     */
    public Path resolveInWorkspace(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ToolException("路径为空");
        }
        Path candidate = Path.of(raw.trim());
        if (!candidate.isAbsolute()) {
            candidate = root.resolve(candidate);
        }
        candidate = candidate.normalize();
        // 统一经 realpath 比较：root 与候选都解析符号链接后比较，规避平台符号链接差异
        Path resolved = resolveReal(candidate);
        Path base = resolved != null ? resolved : candidate;
        if (!base.startsWith(rootReal) && !base.startsWith(root)) {
            throw new ToolException("路径越界（必须在工作区内）: " + raw);
        }
        return base;
    }

    /** 对路径做 realpath（对最近存在的祖先解析符号链接再拼回）；解析失败返回 null */
    private static Path resolveReal(Path candidate) {
        try {
            Path cur = candidate;
            List<Path> tails = new ArrayList<>();
            while (cur != null && !Files.exists(cur)) {
                tails.add(0, cur.getFileName());
                cur = cur.getParent();
            }
            if (cur == null) {
                return null;
            }
            Path real = cur.toRealPath();
            for (Path t : tails) {
                real = real.resolve(t);
            }
            return real.normalize();
        } catch (IOException e) {
            return null;
        }
    }

    private static Path realpathOf(Path p) {
        try {
            return p.toRealPath().normalize();
        } catch (IOException e) {
            return p;
        }
    }

    /** 禁止操作 .git/ 与 ~/.zhu-code-agent/（spec F6 边界规则） */
    public void assertNotForbidden(Path p) {
        Path abs = p.toAbsolutePath().normalize();
        for (Path part : abs) {
            if (".git".equals(part.toString())) {
                throw new ToolException("禁止操作 .git 目录");
            }
        }
        Path homeAgent = Path.of(System.getProperty("user.home"), ".zhu-code-agent").toAbsolutePath().normalize();
        Path homeAgentReal = realpathOf(homeAgent);
        if (abs.startsWith(homeAgent) || abs.startsWith(homeAgentReal)) {
            throw new ToolException("禁止操作程序自身目录 ~/.zhu-code-agent");
        }
    }
}
