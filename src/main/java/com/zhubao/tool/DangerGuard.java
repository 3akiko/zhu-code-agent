package com.zhubao.tool;

import java.util.List;
import java.util.Set;

/**
 * 危险命令守卫（spec F4 + 用户安全红线）：
 * <ul>
 *   <li>内置破坏性命令清单：命中即视为危险（即使曾「总是允许」也强制确认）</li>
 *   <li>文件系统修改命令（rm / rmdir / mv / cp）的目标路径必须解析后位于工作区内，
 *       否则直接拒绝不执行——对标 Claude Code / Codex 的「工作区边界适用于所有
 *       文件系统修改操作（删除/移动/复制），与 flags 无关」</li>
 * </ul>
 */
public final class DangerGuard {

    private static final List<String> DANGEROUS_PREFIXES = List.of(
            "sudo rm", "mkfs", "mkfs.", "dd if=", "shutdown", "reboot", "poweroff",
            "chmod -r 777 /", "chown -r", ":(){", "> /dev/sd", "mv /", "rm -rf /", "rm -fr /"
    );

    /** 文件系统修改命令：删除 / 移动 / 复制（目标必须位于工作区内） */
    private static final Set<String> MUTATING_COMMANDS = Set.of("rm", "rmdir", "mv", "cp");

    private DangerGuard() {
    }

    /** 是否为危险命令（需强制确认；rm -rf 类 + 破坏性前缀） */
    public static boolean isDangerous(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        String c = command.trim();
        String lower = c.toLowerCase();
        for (String p : DANGEROUS_PREFIXES) {
            if (lower.startsWith(p)) {
                return true;
            }
        }
        return isRmRecursiveForce(c);
    }

    /** 识别 rm -rf / rm -fr / rm -r -f 等递归强制删除 */
    private static boolean isRmRecursiveForce(String c) {
        String[] tokens = c.trim().split("\\s+");
        if (tokens.length < 2 || !"rm".equals(tokens[0])) {
            return false;
        }
        boolean sawRecursive = false;
        boolean sawForce = false;
        for (int i = 1; i < tokens.length; i++) {
            String t = tokens[i];
            if (t.startsWith("-") && !t.equals("--")) {
                if (t.contains("r")) {
                    sawRecursive = true;
                }
                if (t.contains("f")) {
                    sawForce = true;
                }
                if (sawRecursive && sawForce) {
                    return true;
                }
            } else {
                break;
            }
        }
        return sawRecursive && sawForce;
    }

    /**
     * 校验文件系统修改命令的目标全部位于工作区内（用户安全红线）：
     * <ul>
     *   <li>rm / rmdir：所有参数目标逐个校验</li>
     *   <li>mv / cp：目标（最后一个参数，或 -t/--target-directory 的值）校验</li>
     *   <li>目标含 shell 展开/元字符（~ $ 反引号 $() ; & | &lt; &gt; 等）→ 直接拒绝（无法静态校验）</li>
     *   <li>越界 → 抛 {@link ToolException}（调用方必须拒绝执行，不产生副作用）</li>
     * </ul>
     *
     * @param command 完整 bash 命令
     * @param guard   工作区 PathGuard
     */
    public static void assertFileMutationsInWorkspace(String command, PathGuard guard) {
        if (command == null || command.isBlank()) {
            return;
        }
        String[] tokens = command.trim().split("\\s+");
        String cmd = tokens[0];
        if (!MUTATING_COMMANDS.contains(cmd)) {
            return; // 非删除/移动/复制命令不在此校验（读取类命令由权限流程把关）
        }
        switch (cmd) {
            case "rm", "rmdir" -> {
                for (int i = 1; i < tokens.length; i++) {
                    String t = tokens[i];
                    if (t.startsWith("-")) {
                        continue; // 跳过 flags（含 -- 选项终止符）
                    }
                    assertTargetInWorkspace(t, guard);
                }
            }
            case "mv", "cp" -> {
                String dest = destinationOf(tokens);
                if (dest != null) {
                    assertTargetInWorkspace(dest, guard);
                }
            }
            default -> { /* 不可达 */ }
        }
    }

    /** mv/cp 的目标：-t/--target-directory 后的值，否则最后一个非 flag 参数 */
    private static String destinationOf(String[] tokens) {
        String last = null;
        for (int i = 1; i < tokens.length; i++) {
            String t = tokens[i];
            if (t.equals("-t") || t.equals("--target-directory")) {
                if (i + 1 < tokens.length) {
                    return tokens[i + 1];
                }
            } else if (t.startsWith("--target-directory=")) {
                return t.substring("--target-directory=".length());
            } else if (!t.startsWith("-")) {
                last = t;
            }
        }
        return last;
    }

    /** 校验单个目标：展开字符拒绝 + 通配符前缀/整路径在 cwd 内 */
    private static void assertTargetInWorkspace(String target, PathGuard guard) {
        String t = stripQuotes(target);
        if (t.isBlank()) {
            return;
        }
        // 安全红线：含 shell 展开/元字符的目标无法静态校验（~ $ 反引号 $() ; & | < > 等），
        // 运行时会展开到 cwd 外，直接拒绝（如 rm ~/x、rm $HOME/x、mv a /tmp/x）。
        if (!isSafeTarget(t)) {
            throw new ToolException("目标含无法静态校验的 shell 展开字符，已拒绝: " + t);
        }
        int wildcard = firstWildcard(t);
        String base = wildcard >= 0 ? t.substring(0, wildcard) : t;
        if (base.isBlank()) {
            base = ".";
        }
        // 通配符只静态校验前缀；前缀本身必须落在工作区内
        guard.resolveInWorkspace(base);
    }

    /** 目标允许的字符：字母数字 + 路径/通配符；其余（~ $ 反引号 ; & | < > 引号括号等）视为不可静态校验 */
    private static boolean isSafeTarget(String target) {
        for (int i = 0; i < target.length(); i++) {
            char c = target.charAt(i);
            boolean ok = Character.isLetterOrDigit(c)
                    || c == '/' || c == '.' || c == '_' || c == '-'
                    || c == '*' || c == '?' || c == '[' || c == ']';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static String stripQuotes(String t) {
        String s = t.trim();
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static int firstWildcard(String s) {
        int star = s.indexOf('*');
        int q = s.indexOf('?');
        int open = s.indexOf('[');
        int min = Integer.MAX_VALUE;
        for (int idx : new int[]{star, q, open}) {
            if (idx >= 0 && idx < min) {
                min = idx;
            }
        }
        return min == Integer.MAX_VALUE ? -1 : min;
    }
}
