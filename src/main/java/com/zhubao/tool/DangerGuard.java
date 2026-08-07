package com.zhubao.tool;

import java.util.List;

/**
 * 危险命令守卫（spec F4 + 用户安全红线）：
 * <ul>
 *   <li>内置破坏性命令清单：命中即视为危险（即使曾「总是允许」也强制确认）</li>
 *   <li>{@code rm -rf} 类命令：目标路径必须解析后位于工作区内，否则直接拒绝不执行</li>
 * </ul>
 */
public final class DangerGuard {

    private static final List<String> DANGEROUS_PREFIXES = List.of(
            "sudo rm", "mkfs", "mkfs.", "dd if=", "shutdown", "reboot", "poweroff",
            "chmod -r 777 /", "chown -r", ":(){", "> /dev/sd", "mv /", "rm -rf /", "rm -fr /"
    );

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
     * 校验 rm -rf 类命令的目标全部位于工作区内（用户安全红线）。
     * 越界 → 抛 {@link ToolException}（调用方必须拒绝执行，不产生副作用）。
     *
     * @param command 完整 bash 命令
     * @param guard   工作区 PathGuard
     */
    public static void assertRmTargetsInWorkspace(String command, PathGuard guard) {
        if (!isRmRecursiveForce(command)) {
            return;
        }
        String[] tokens = command.trim().split("\\s+");
        boolean targetsStarted = false;
        for (String t : tokens) {
            if ("rm".equals(t)) {
                continue;
            }
            if (!targetsStarted && t.startsWith("-")) {
                continue;
            }
            targetsStarted = true;
            String target = stripQuotes(t);
            if (target.isBlank()) {
                continue;
            }
            int wildcard = firstWildcard(target);
            String base = wildcard >= 0 ? target.substring(0, wildcard) : target;
            if (base.isBlank()) {
                base = ".";
            }
            // 通配符只静态校验前缀；前缀本身必须落在工作区内
            guard.resolveInWorkspace(base);
        }
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
