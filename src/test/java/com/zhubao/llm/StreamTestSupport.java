package com.zhubao.llm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/** 测试工具：从事件队列取事件直到 StreamEnd/Error 或超时 */
final class StreamTestSupport {

    private StreamTestSupport() {
    }

    static List<StreamEvent> drain(BlockingQueue<StreamEvent> queue, Duration timeout) throws InterruptedException {
        List<StreamEvent> events = new ArrayList<>();
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (true) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                break;
            }
            StreamEvent ev = queue.poll(remaining, TimeUnit.MILLISECONDS);
            if (ev == null) {
                break;
            }
            events.add(ev);
            if (ev instanceof StreamEvent.StreamEnd || ev instanceof StreamEvent.Error) {
                break;
            }
        }
        return events;
    }
}
