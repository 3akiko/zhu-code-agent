package com.zhubao.tool;

import com.zhubao.agent.AgentDepth;
import com.zhubao.agent.AgentUi;
import com.zhubao.history.FileHistory;
import com.zhubao.permission.PermissionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 并行工具执行器（M5，spec F2）：顺序分段调度——
 * 连续「可并行」调用（只读工具 ∪ task）组段、段内用 Java 21 虚拟线程并行；
 * write/edit/bash 单独串行且保持原顺序；段与段之间按原调用顺序执行；
 * 结果严格按原调用顺序回填（与完成先后无关）。
 *
 * <p>权限/快照/结果语义与 {@link SerialToolExecutor} 完全一致（共享
 * {@link AbstractToolExecutor#executeOne}）：只读自动放行、写/bash 确认（危险强制）、
 * 写类快照顺序与串行一致。UI 回调（onToolCall/onToolResult）由调度线程串行调用，
 * 避免并行虚拟线程并发写终端（spec N4 稳定性）。
 *
 * <p>中断（spec F2.7）：段间检查中断标志停止后续调度；段内 {@link Future#get()} 被
 * interrupt 时中断标志恢复并丢弃未完成结果，本轮由 AgentRunner 判中断终止。
 */
public final class ParallelToolExecutor extends AbstractToolExecutor {

    public ParallelToolExecutor(ToolRegistry registry, PermissionManager permissions, AgentUi ui, int previewLines) {
        super(registry, permissions, ui, previewLines);
    }

    public ParallelToolExecutor(ToolRegistry registry, PermissionManager permissions, AgentUi ui, int previewLines,
                                FileHistory history, String sessionId) {
        super(registry, permissions, ui, previewLines, history, sessionId);
    }

    @Override
    public List<ToolResult> execute(List<ToolCall> calls) {
        List<ToolResult> results = new ArrayList<>(calls.size());
        int i = 0;
        while (i < calls.size()) {
            // spec F2.7：Ctrl+C 中断后停止后续调度
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            ToolCall call = calls.get(i);
            if (isParallelCall(call)) {
                int j = i;
                while (j < calls.size() && isParallelCall(calls.get(j))) {
                    j++;
                }
                results.addAll(runParallelSegment(calls.subList(i, j)));
                i = j;
            } else {
                results.add(executeOne(call));
                i++;
            }
        }
        return results;
    }

    /** 可并行调用：只读工具（自动放行、无用户交互、无快照）∪ task（F3 并行子任务入口） */
    private boolean isParallelCall(ToolCall call) {
        if ("task".equals(call.name())) {
            return true;
        }
        return registry.byName(call.name()).map(Tool::readOnly).orElse(false);
    }

    /**
     * 并行执行一个读段：UI 提交前串行 onToolCall → 虚拟线程提交 executeOne(call, false) →
     * 按段内原序 get 收集 → 串行 onToolResult。单调用失败以 isError 结果返回、不拖垮段。
     */
    private List<ToolResult> runParallelSegment(List<ToolCall> segment) {
        // 提交前串行 UI 回调（避免并发写终端）
        if (ui != null) {
            for (ToolCall c : segment) {
                ui.onToolCall(c);
            }
        }
        List<ToolResult> results = new ArrayList<>(segment.size());
        // review 修复（2026-08-13）：虚拟线程不继承 ThreadLocal——提交前捕获父线程的子任务深度，
        // 线程内恢复，使嵌套子任务（子任务内再调 task）读到正确的当前深度（spec F3.5 护栏）。
        int parentDepth = AgentDepth.get();
        ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor();
        boolean interrupted = false;
        try {
            List<Future<ToolResult>> futures = new ArrayList<>(segment.size());
            for (ToolCall c : segment) {
                futures.add(ex.submit(() -> {
                    AgentDepth.set(parentDepth);
                    try {
                        return executeOne(c, false);
                    } finally {
                        AgentDepth.clear();
                    }
                }));
            }
            for (int idx = 0; idx < futures.size(); idx++) {
                try {
                    results.add(futures.get(idx).get());
                } catch (InterruptedException ie) {
                    // 主线程被 Ctrl+C 中断：恢复标志、丢弃未完成结果（spec F2.7）
                    Thread.currentThread().interrupt();
                    interrupted = true;
                    break;
                } catch (ExecutionException ee) {
                    // 防御：executeOne 不抛，但保证单调用异常不拖垮段
                    results.add(ToolResult.error(segment.get(idx), "执行异常: " + safeMessage(ee.getCause())));
                }
            }
        } finally {
            if (interrupted) {
                // review-P3：中断时不再等待在途虚拟线程（close() 默认 awaitTermination 无超时）
                ex.shutdownNow();
            }
            ex.close();
        }
        // 收集后串行 UI 回调（按原序，保证终端输出有序）
        if (ui != null) {
            for (ToolResult r : results) {
                ui.onToolResult(r, previewLines);
            }
        }
        return results;
    }

    private static String safeMessage(Throwable t) {
        String msg = t == null ? null : t.getMessage();
        return msg == null || msg.isBlank() ? (t == null ? "未知" : t.getClass().getSimpleName()) : msg;
    }
}
