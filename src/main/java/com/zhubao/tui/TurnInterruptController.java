package com.zhubao.tui;

import com.zhubao.llm.LlmStream;
import com.zhubao.tool.builtin.BashTool;
import org.jline.utils.Signals;

/**
 * 本轮中断控制（M4 spec F5 + review 加固 2026-08-11）。
 *
 * <p><b>方案</b>：用 JVM 级 SIGINT handler（JLine 公共 API {@link Signals#register}，内部
 * 反射 sun.misc.Signal）在本轮内临时接管 Ctrl+C——把默认「终止进程（exit 130）」重定向为
 * 「取消本轮」：生成中取消当前 LLM 流，工具执行中销毁 Bash 进程树并中断主线程。
 * 信号回调运行在 JVM 信号分发线程，因此这里只触碰线程安全状态（volatile phase、
 * LlmStream.cancel / BashTool.cancel / Thread.interrupt）。本轮结束（endTurn）恢复前一个
 * handler，提示符的 Ctrl+C 退出语义不受影响（真机由 JLine readLine 处理，哑终端保持原默认）。
 *
 * <p><b>二次 Ctrl+C 逃生门</b>：本轮内 1.5s 内连续第二次 Ctrl+C → 恢复默认 SIGINT 并置
 * 退出请求（同时取消在途工作），主循环经 {@link #consumeExitRequest()} 收到后优雅退出（落盘
 * 会话），避免一轮卡死时用户被困（对齐 Codex「再按一次退出」）。退出请求**粘性**：跨 runTurn
 * 不清零，直到被消费（避免被压缩/计划循环里的后续 beginTurn 吞掉）。逃生门触发后 handler 已
 * 恢复，之后 Ctrl+C 恢复系统默认行为。
 *
 * <p><b>状态收敛</b>：phase / chatThread / activeStream / 信号 token 全部集中在本类，
 * ChatApp 只通过 beginTurn / endTurn / 槽位（onStep / onToolCall / onStreamCreated）/
 * exitRequested / shutdown 交互。
 */
final class TurnInterruptController {

    /** 本轮阶段：GENERATING = LLM 流生成；TOOL_EXEC = 工具执行（含权限确认） */
    enum Phase { IDLE, GENERATING, TOOL_EXEC }

    /** 二次 Ctrl+C 逃生门判定窗口（毫秒）：本轮内连续两次 Ctrl+C 视为「退出」 */
    private static final long DOUBLE_INTERRUPT_WINDOW_MS = 1_500;

    private final BashTool bashTool;
    private volatile Phase phase = Phase.IDLE;
    // M4 review-P2：以下字段跨「主线程 ↔ JVM 信号线程」读写，必须 volatile / 原子，
    // 否则在弱内存模型（Apple Silicon）下可能读到陈旧值 → 逃生门/取消失效。
    private volatile Thread chatThread;          // 本轮执行线程（信号回调里 interrupt 它）
    private volatile LlmStream activeStream;     // 本轮当前 LLM 流（取消 / 退出 join）
    private volatile long lastInterruptAt;       // 上次 Ctrl+C 时间戳（逃生门判定）
    private Object intSignalToken;               // Signals.register 返回的前一个 handler（恢复用）
    private final java.util.concurrent.atomic.AtomicBoolean exitRequested =
            new java.util.concurrent.atomic.AtomicBoolean(false); // 逃生门粘性标志（P3：跨 runTurn 不清零）

    TurnInterruptController(BashTool bashTool) {
        this.bashTool = bashTool;
    }

    /** 本轮开始：安装 JVM 级 SIGINT handler 并重置逃生门状态 */
    void beginTurn() {
        chatThread = Thread.currentThread();
        activeStream = null;
        phase = Phase.GENERATING;
        lastInterruptAt = 0;
        // 注意：exitRequested 不在 beginTurn 清零（P3 粘性），由主循环 consumeExitRequest() 消费后清除
        // JVM 级注册：真机（PosixSysTerminal）与哑/受限 PTY（DumbTerminal）都能拦截 SIGINT；
        // 返回前一个 handler 作恢复 token（真机场景恢复 JLine 自己的注册）。
        intSignalToken = Signals.register("INT", this::onCtrlC);
    }

    /** 本轮结束：恢复前一个 SIGINT handler、join 在途流、清中断标志 */
    void endTurn() {
        phase = Phase.IDLE;
        if (intSignalToken != null) {
            Signals.unregister("INT", intSignalToken);
            intSignalToken = null;
        }
        LlmStream s = activeStream;
        if (s != null) {
            s.join();
            activeStream = null;
        }
        Thread.interrupted(); // 清掉中断标志，避免影响后续 readLine
    }

    /** 退出时兜底：取消并 join 在途流（正常退出路径 activeStream 已为 null） */
    void shutdown() {
        LlmStream s = activeStream;
        if (s != null) {
            s.cancel();
            s.join();
            activeStream = null;
        }
    }

    /**
     * 消费逃生门请求（一次性）：主循环检查后应保存会话并退出。
     * 粘性：逃生门触发后跨 runTurn 不清零，直到这里被消费（review-P3 修复，避免被
     * 后续 runTurn 的 beginTurn 吞掉）；AtomicBoolean.getAndSet 保证主线程读到最新值。
     */
    boolean consumeExitRequest() {
        return exitRequested.getAndSet(false);
    }

    void onStreamCreated(LlmStream stream) {
        activeStream = stream;
    }

    void onStep() {
        phase = Phase.GENERATING;
    }

    void onToolCall() {
        phase = Phase.TOOL_EXEC;
    }

    /** Ctrl+C 处理（运行于 JVM 信号分发线程；只碰线程安全状态；包内可见供测试模拟） */
    void onCtrlC() {
        long now = System.currentTimeMillis();
        if (now - lastInterruptAt < DOUBLE_INTERRUPT_WINDOW_MS) {
            // 二次 Ctrl+C 逃生门：恢复默认 SIGINT（之后的 Ctrl+C 恢复系统行为）+ 请求优雅退出
            exitRequested.set(true);
            if (intSignalToken != null) {
                Signals.unregister("INT", intSignalToken);
                intSignalToken = null;
            }
            cancelCurrent();
            return;
        }
        lastInterruptAt = now;
        cancelCurrent();
    }

    private void cancelCurrent() {
        if (phase == Phase.GENERATING) {
            LlmStream s = activeStream;
            if (s != null) {
                s.cancel();
            }
        } else if (phase == Phase.TOOL_EXEC) {
            if (bashTool != null) {
                bashTool.cancel();
            }
        }
        if (chatThread != null) {
            chatThread.interrupt();
        }
    }
}
