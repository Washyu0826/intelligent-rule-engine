package com.ruleengine.rules.controller;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Thread starvation 壓測（v3.16 /generate 非同步化的驗證）。
 *
 * 場景：LLM 呼叫最長 300 秒。若 LLM 工作佔住 Tomcat worker，
 * 少量併發即可讓所有端點（含 /health）排隊。
 *
 * 實驗設計（A/B 同一個 app 同一次執行）：
 *   - Tomcat 壓到 4 條 worker（server.tomcat.threads.max=4）放大現象
 *   - WireMock 模擬 Ollama，/api/chat 固定延遲 3 秒
 *   - 對照組：8 併發打 test-only 的「同步阻塞」端點（sleep 3s，= LLM 端點非同步化前的執行模型；
 *     v3.16 後生產端點已全部非同步化，故對照組由測試自備）
 *     → 期望 /health 被佔住的 worker 卡到秒級
 *   - 實驗組：8 併發打「非同步」/tools/generate（DeferredResult + llmExecutor）
 *     → 期望 /health 維持毫秒級
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.tomcat.threads.max=4"})
@org.springframework.context.annotation.Import(GenerateThreadStarvationTest.SyncBlockingControlConfig.class)
@DisplayName("LLM 端點非同步化 — thread starvation A/B 壓測")
class GenerateThreadStarvationTest {

    /** 對照組：佔住 Tomcat worker 3 秒的同步端點（模擬非同步化前的 LLM 端點執行模型）。
     *  巢狀 @RestController 由 @Configuration 成員類處理自動註冊，勿再加顯式 @Bean（會重複映射）。 */
    @org.springframework.boot.test.context.TestConfiguration
    static class SyncBlockingControlConfig {
        @org.springframework.web.bind.annotation.RestController
        static class SyncBlockingController {
            @org.springframework.web.bind.annotation.PostMapping("/test-sync-block")
            public String block() throws InterruptedException {
                Thread.sleep(3000);
                return "ok";
            }
        }
    }

    static WireMockServer wireMock;

    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("rules.llm.provider", () -> "ollama");
        registry.add("rules.llm.model", () -> "test-model");
        registry.add("rules.llm.ollama.base-url", () -> "http://localhost:" + wireMock.port());
    }

    @BeforeEach
    void stubSlowLlm() {
        wireMock.resetAll();
        // 3 秒延遲的「慢 LLM」；內容合法可解析（generate 流程能走完）
        String envelope = """
                {"ruleType":"DecisionTable","reason":"t","rule":{"hitPolicy":"FIRST",
                "inputs":[{"name":"age","typeRef":"INTEGER"}],
                "outputs":[{"name":"decision","typeRef":"STRING"}],
                "rules":[{"ruleId":"R01","priority":1,
                "conditions":[{"field":"age","operator":"greaterThan","value":60}],
                "results":[{"field":"decision","value":"reject"}]}]}}
                """.replace("\n", "");
        String content = envelope.replace("\\", "\\\\").replace("\"", "\\\"");
        wireMock.stubFor(post(urlPathEqualTo("/api/chat"))
                .willReturn(okJson("{\"message\":{\"content\":\"" + content + "\"},\"done\":true}")
                        .withFixedDelay(3000)));
    }

    @Test
    @DisplayName("同步阻塞端點佔滿 worker 時 /health 卡秒級；非同步 /generate 下維持毫秒級")
    void asyncGenerateKeepsHealthResponsive() throws Exception {
        String generateBody = """
                {"description":"年齡大於 60 拒保，否則承保","ruleType":"DecisionTable","mode":"fast"}
                """;

        // 暖身：讓 /health 路徑的類載入/JIT/連線建立先完成，不汙染量測
        probeHealthOnce();

        // 對照組：同步阻塞端點（= LLM 端點非同步化前的執行模型）
        List<Long> starved = healthLatenciesUnderLoad("/test-sync-block", "{}");
        // 實驗組：非同步 /generate
        List<Long> async = healthLatenciesUnderLoad("/tools/generate", generateBody);

        // 用中位數判定：全套件環境下其他 context 的 GC/排程抖動會讓單一樣本飆高，
        // max 對單一離群值太敏感（曾在 full suite 下單發 10s+），median 才反映系統性飢餓
        long starvedMedian = median(starved);
        long asyncMedian = median(async);
        System.out.printf("starvation A/B | sync-block /health median=%dms max=%dms | async(generate) /health median=%dms max=%dms%n",
                starvedMedian, max(starved), asyncMedian, max(async));

        assertTrue(starvedMedian > 800,
                "對照組（同步阻塞端點）應出現系統性 /health 延遲，實測 median=" + starvedMedian + "ms");
        assertTrue(asyncMedian < 800,
                "非同步 /generate 下 /health 應維持快速回應，實測 median=" + asyncMedian + "ms");
        assertTrue(asyncMedian < starvedMedian,
                "非同步版的 /health 延遲應低於同步版");
    }

    private static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        return sorted.get(sorted.size() / 2);
    }

    private static long max(List<Long> values) {
        return values.stream().mapToLong(Long::longValue).max().orElse(0);
    }

    private void probeHealthOnce() throws Exception {
        http.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/health"))
                        .timeout(Duration.ofSeconds(15)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 發出 8 個併發 POST 佔用 LLM 3 秒，期間每 100ms 探測一次 /health，回傳所有延遲樣本。
     */
    private List<Long> healthLatenciesUnderLoad(String path, String body) throws Exception {
        ExecutorService clientPool = Executors.newFixedThreadPool(8);
        try {
            List<CompletableFuture<Void>> loads = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                loads.add(CompletableFuture.runAsync(() -> firePost(path, body), clientPool));
            }

            Thread.sleep(200); // 等負載真正進到 server
            List<Long> latencies = new ArrayList<>();
            long deadline = System.currentTimeMillis() + 2_500;
            while (System.currentTimeMillis() < deadline) {
                long t0 = System.nanoTime();
                HttpResponse<String> res = http.send(HttpRequest.newBuilder()
                                .uri(URI.create("http://localhost:" + port + "/health"))
                                .timeout(Duration.ofSeconds(15)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                long ms = (System.nanoTime() - t0) / 1_000_000;
                assertEquals(200, res.statusCode());
                latencies.add(ms);
                Thread.sleep(100);
            }

            CompletableFuture.allOf(loads.toArray(new CompletableFuture[0]))
                    .get(60, java.util.concurrent.TimeUnit.SECONDS);
            return latencies;
        } finally {
            clientPool.shutdownNow();
        }
    }

    private void firePost(String path, String body) {
        try {
            http.send(HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + port + path))
                            .header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(60))
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception ignored) {
            // 壓測只關心佔用時間，不關心個別回應
        }
    }
}
