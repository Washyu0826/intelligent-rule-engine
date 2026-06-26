package com.ruleengine.rules.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * LLM 相關長工作的專用執行緒池。
 *
 * 設計動機（v3.16）：/generate 同步打 LLM 最長 300 秒，若佔住 Tomcat worker，
 * 少量併發 generate 即可讓所有端點（含 /health）排隊 — thread starvation。
 * 解法：LLM 工作移到專用有界池，Tomcat 執行緒立即歸還（搭配 DeferredResult）。
 *
 * 池語意：core=max 固定大小 + allowCoreThreadTimeOut（閒置 60s 回收）。
 * 注意 ThreadPoolExecutor 只有在「佇列滿」才會長出 core 以上的執行緒，
 * 所以 core&lt;max + 有界佇列的組合實際上用不到 max — 這裡刻意取 core=max 避免該誤解。
 * 佇列滿載 → AbortPolicy 快速失敗（回 429/忙碌），不讓請求無限堆積。
 */
@Configuration
public class AsyncExecutorConfig {

    @Value("${rules.executor.llm.pool-size:8}")
    private int llmPoolSize;

    @Value("${rules.executor.llm.queue-capacity:32}")
    private int llmQueueCapacity;

    @Value("${rules.executor.sse.pool-size:16}")
    private int ssePoolSize;

    @Value("${rules.executor.sse.queue-capacity:64}")
    private int sseQueueCapacity;

    /** /tools/generate 的 LLM 生成工作池（DeferredResult 搭配使用） */
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService llmExecutor() {
        return fixedBoundedPool(llmPoolSize, llmQueueCapacity, "llm-generate");
    }

    /** /tools/generate/stream 的 SSE 推送池 */
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService sseExecutor() {
        return fixedBoundedPool(ssePoolSize, sseQueueCapacity, "sse-generate");
    }

    private static ExecutorService fixedBoundedPool(int poolSize, int queueCapacity, String threadName) {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                poolSize, poolSize, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                namedDaemonFactory(threadName),
                new ThreadPoolExecutor.AbortPolicy());
        pool.allowCoreThreadTimeOut(true); // 閒置時縮回 0，不常駐
        return pool;
    }

    private static ThreadFactory namedDaemonFactory(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }
}
