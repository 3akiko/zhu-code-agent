package com.zhubao.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhubao.tool.DangerGuard;
import com.zhubao.tool.PathGuard;
import com.zhubao.tool.Tool;
import com.zhubao.tool.ToolCall;
import com.zhubao.tool.ToolException;
import com.zhubao.tool.ToolResult;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * bash：在工作区执行命令（spec F2/F7 安全约束）。
 * <ul>
 *   <li>无 stdin（/dev/null），交互式命令无法挂住</li>
 *   <li>30s 超时，超时 kill 整个进程树</li>
 *   <li>输出上限 200KB（截断并标注）</li>
 *   <li>危险命令（rm -rf 等）：rm -rf 目标必须解析后在 cwd 内，否则直接拒绝（spec F4 安全红线）</li>
 * </ul>
 */
public final class BashTool implements Tool {

    static final long TIMEOUT_SECONDS = 30;
    static final int OUTPUT_CAP_CHARS = 200_000;

    private final PathGuard guard;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public BashTool(PathGuard guard) {
        this.guard = guard;
    }

    @Override
    public String name() {
        return "bash";
    }

    @Override
    public String description() {
        return "在工作区执行 shell 命令（无 stdin、30s 超时、输出上限 200KB）；破坏性命令（如 rm -rf）受安全校验。";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties")
                .putObject("command").put("type", "string").put("description", "要执行的 shell 命令");
        schema.putArray("required").add("command");
        return schema;
    }

    @Override
    public ToolResult execute(ToolCall call) {
        Map<String, Object> args = call.arguments();
        String command = args.get("command") == null ? null : String.valueOf(args.get("command"));
        if (command == null || command.isBlank()) {
            return ToolResult.error(call, "bash 缺少参数 command");
        }
        // 安全红线：rm -rf 目标必须位于工作区内，否则直接拒绝（不执行、无副作用）
        try {
            DangerGuard.assertFileMutationsInWorkspace(command, guard);
        } catch (ToolException e) {
            return ToolResult.error(call, "危险命令已拒绝（" + e.getMessage() + "）");
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", command);
            pb.directory(guard.root().toFile());
            pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // 先起线程读输出：避免输出超过管道缓冲区（~64KB）时子进程写满阻塞、waitFor 假超时
            Future<String> outputFuture = OUTPUT_READER.submit(() -> readOutput(process));
            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                killTree(process);
            }
            String output = awaitOutput(outputFuture);
            if (!finished) {
                return ToolResult.error(call, "命令超时（" + TIMEOUT_SECONDS + "s），已终止"
                        + (output == null || output.isBlank() ? "" : "\n部分输出:\n" + output));
            }
            int exit = process.exitValue();
            String text = output;
            if (exit != 0) {
                text = (text == null ? "" : text) + "\n（退出码 " + exit + "）";
                return ToolResult.error(call, text);
            }
            return ToolResult.ok(call, text == null ? "" : text);
        } catch (IOException e) {
            return ToolResult.error(call, "执行命令失败: " + ReadFileTool.safe(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error(call, "命令执行被中断");
        }
    }

    /** 读取子进程输出，保留前 OUTPUT_CAP_CHARS 字符，其余丢弃但仍消费（防止管道阻塞） */
    private static String readOutput(Process process) {
        StringBuilder kept = new StringBuilder();
        int total = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                total += line.length() + 1;
                if (kept.length() < OUTPUT_CAP_CHARS) {
                    int room = OUTPUT_CAP_CHARS - kept.length();
                    kept.append(line, 0, Math.min(line.length(), room)).append('\n');
                }
            }
        } catch (IOException ignored) {
            // 进程被 kill 后流可能已关闭：返回已读部分即可
        }
        String out = kept.toString();
        if (total > OUTPUT_CAP_CHARS) {
            out += "\n…（输出过大，已截断，共 " + total + " 字符）";
        }
        return out;
    }

    /** 共享 daemon 线程池：读取子进程输出（防止大输出阻塞 waitFor） */
    private static final ExecutorService OUTPUT_READER = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "bash-output-reader");
        t.setDaemon(true);
        return t;
    });

    /** 等待读取完成（进程已结束/被 kill 后流会 EOF）；异常时返回已读部分 */
    private static String awaitOutput(Future<String> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "";
        }
    }

    private static void killTree(Process process) {
        try {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
        } catch (Exception ignored) {
            // 尽力而为
        }
        process.destroyForcibly();
    }
}
