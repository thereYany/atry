package com.wherelee.base.bench;

import com.wherelee.base.integration.KernelIntegrationTestSupport;
import com.wherelee.base.modules.contracts.llm.ChatMessage;
import com.wherelee.base.modules.contracts.llm.LlmOperations;
import com.wherelee.base.modules.contracts.llm.LlmRequest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * mock provider 并发/吞吐基线（非默认测试；用 -Dtest=LlmConcurrencyBenchTest 手动运行）。
 * 目的：给出框架自身的并发开销/记录一致性证据，不代表真实 provider 延迟。
 */
@Tag("bench")
@TestPropertySource(properties = {"atry.llm.outbox.enabled=false"})
class LlmConcurrencyBenchTest extends KernelIntegrationTestSupport {

    @Autowired
    private LlmOperations llm;

    @Test
    void runsConcurrentCallsAndReportsLatency() throws Exception {
        int total = 200;
        int concurrency = 16;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    long begin = System.nanoTime();
                    llm.invoke(new LlmRequest("bench-" + index, index, 0, 1, "mock-small",
                            List.of(new ChatMessage("user", "bench")), 32, false,
                            Instant.now().plusSeconds(30), null));
                    latencies.add(TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - begin));
                    return null;
                }));
            }
            long begin = System.currentTimeMillis();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(120, TimeUnit.SECONDS);
            }
            long wallMs = System.currentTimeMillis() - begin;

            List<Long> sorted = latencies.stream().sorted().toList();
            long p50 = sorted.get((int) (sorted.size() * 0.50));
            long p95 = sorted.get((int) (sorted.size() * 0.95));
            long p99 = sorted.get((int) (sorted.size() * 0.99));
            double rps = total * 1000.0 / Math.max(1, wallMs);
            System.out.printf("BENCH mock total=%d concurrency=%d wallMs=%d rps=%.1f "
                            + "p50=%dus p95=%dus p99=%dus%n",
                    total, concurrency, wallMs, rps, p50, p95, p99);

            assertThat(latencies).hasSize(total);
            assertThat(count("t_llm_call_record", "request_id LIKE 'bench-%'")).isEqualTo(total);
            assertThat(count("t_llm_outbox", "aggregate_id LIKE 'bench-%'")).isEqualTo(total);
        } finally {
            pool.shutdownNow();
        }
    }
}
