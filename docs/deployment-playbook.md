# Rules MCP Server — Cloud Deployment Playbook

**版本**：v1.0（對應應用 v3.13+）
**作者**：Washyu0826
**日期**：2026-05-06
**狀態**：操作層補充文件（與 `deployment-architecture.md` 互補）

> 本檔聚焦「**怎麼做**」：實際部署步驟、可貼上的設定片段、cloud provider 選型證據、合規條文 mapping。
> 設計層的「為什麼」（架構圖、ADR、成本論證）請看 `deployment-architecture.md`。

---

## 目錄

1. [Day-1 雲端選型結論](#1-day-1-雲端選型結論)
2. [部署成熟度路徑（操作版）](#2-部署成熟度路徑操作版)
3. [LiteLLM Gateway 整合](#3-litellm-gateway-整合)
4. [Spring Boot 應用層生產設定](#4-spring-boot-應用層生產設定)
5. [合規對應（2025.12 AI 基本法 + 保險業自律規範）](#5-合規對應)
6. [觀測性實作（PromQL + Alert）](#6-觀測性實作)
7. [GitOps 流程細節](#7-gitops-流程細節)
8. [常見 Gotchas](#8-常見-gotchas)

---

## 1. Day-1 雲端選型結論

**結論：GCP `asia-east1`（彰化）為主，AWS `ap-east-2`（台北）為次**。

| 評估面向 | GCP asia-east1 | AWS ap-east-2 | Azure Taiwan North |
|---|---|---|---|
| GA 時間 | 2013（最成熟） | 2025 GA | 2026 GA（新區，部分服務分階段） |
| 集團現況 | **已主用**（Group Holdings GCP case study） | 部分使用 | 較少 |
| 金管會核准 | **首家數據上雲是集團 + GCP** | 已核准 | 已核准 |
| 服務完整度 | ✅ 全服務 | ✅ 全服務、低延遲 2-5ms | 🟡 新區，部分服務尚未上線 |
| Managed K8s 金融特化 | GKE Autopilot + Binary Authorization + Cosign **原生整合** | EKS（audit log 預設關閉，需手開） | AKS（audit log 預設關閉） |
| Managed AI | Vertex AI（Gemini 2.x，asia-east1 可用，batch 折 50%） | Bedrock（Claude/Nova/OpenAI 多模型） | Azure OpenAI（GPT-5 可用，Taiwan North 2026 起逐步開放） |
| 私網連線（10G） | Interconnect ~$1,700/月 | Direct Connect ~$1,620/月 | ExpressRoute ~$5,000/月 |
| Secret 輪替 | Secret Manager + Cloud Scheduler（需自建） | Secrets Manager + 原生 Lambda rotation（最自動） | Key Vault + Function（event-driven） |

**Day-1 GCP 三個關鍵理由**：

1. **合規路徑最短** — 集團是金管會首家獲核准數據上雲的金控（GCP 為主要夥伴），既有上雲案例可直接複用。
2. **供應鏈安全最完整** — GKE Autopilot + Binary Authorization + Cosign 原生整合，符合 v3.11 quality signals 與保險業自律規範對 AI 系統「執行軌跡留存」的要求。
3. **彰化 region 成熟度最高** — 沒有新 region 服務空窗風險。

**雙雲補強**：AI workload 可用 AWS Bedrock（Claude/Nova/OpenAI 多模型一站式），透過 Cloud Interconnect 互連。Azure Day-1 暫不建議（新 region 服務分階段上線）。

---

## 2. 部署成熟度路徑（操作版）

```
MVP (現狀)              Pilot (1-2 月)         Beta (3-4 月)            Prod (6 月+)
───────────             ──────────────         ────────────             ────────────
docker-compose          Cloud Run             GKE Autopilot           GKE Standard + ArgoCD
+ Caffeine cache        + Memorystore Redis   + LiteLLM Gateway       + 多 AZ + DR region
+ .env file             + Cloud SQL PG        + Langfuse              + Multi-tenant
+ 單機                  + Secret Manager      + Argo Rollouts canary  + immutable audit
                        + Cloud Logging       + ServiceMonitor          + 99.95% SLO
                                              + cosign signed image
```

### 2.1 Pilot 階段（≤ 10 人試用，1-2 月內可上線）

**為什麼用 Cloud Run 而非 GKE**：Pilot 階段優先驗證商業價值，K8s overhead 不值得。Cloud Run 提供 scale-to-zero、自動 TLS、IAM 整合，每月成本 < $100。

```bash
# 1. 建 Artifact Registry
gcloud artifacts repositories create rules-mcp \
  --repository-format=docker --location=asia-east1

# 2. Build + push（用既有 Dockerfile）
gcloud builds submit --tag asia-east1-docker.pkg.dev/$PROJECT/rules-mcp/server:v3.13.0

# 3. 部署 Cloud Run（含 secret 注入）
gcloud run deploy rules-mcp-server \
  --image asia-east1-docker.pkg.dev/$PROJECT/rules-mcp/server:v3.13.0 \
  --region asia-east1 \
  --memory 1Gi --cpu 2 \
  --min-instances 1 --max-instances 5 \
  --concurrency 50 \
  --set-secrets=CLAUDE_API_KEY=claude-api-key:latest \
  --set-secrets=GEMINI_API_KEY=gemini-api-key:latest \
  --set-env-vars=SPRING_PROFILES_ACTIVE=cloud,prod \
  --no-allow-unauthenticated

# 4. Memorystore Redis（快取）
gcloud redis instances create rules-mcp-cache \
  --size=1 --region=asia-east1 --redis-version=redis_7_0 \
  --tier=BASIC

# 5. Cloud SQL（規則庫）
gcloud sql instances create rules-mcp-db \
  --database-version=POSTGRES_16 --tier=db-g1-small \
  --region=asia-east1 --availability-type=ZONAL
```

**Pilot 階段 SLO**：99.0%。允許週末維護視窗。

### 2.2 Beta 階段（單一業務單位，≤ 100 人）

**升級到 GKE Autopilot**（Pilot 累積使用量、需要更精細控制）：

```bash
# 1. 建 GKE Autopilot cluster（管理 node 你不用煩）
gcloud container clusters create-auto rules-mcp-beta \
  --region=asia-east1 \
  --release-channel=regular \
  --enable-master-authorized-networks \
  --master-authorized-networks=$VPN_CIDR

# 2. 啟用 Workload Identity（取代 SA key）
gcloud container clusters update rules-mcp-beta \
  --workload-pool=$PROJECT.svc.id.goog \
  --region=asia-east1

# 3. 取得 credentials
gcloud container clusters get-credentials rules-mcp-beta --region=asia-east1

# 4. 套用 base manifest（已 commit 的 k8s/）
kubectl apply -k k8s/

# 5. 部署 LiteLLM Gateway（見 §3）
helm install litellm oci://ghcr.io/berriai/litellm-helm \
  -n llm-gateway --create-namespace \
  -f infra/litellm-values.yaml

# 6. 部署 observability
helm install kube-prometheus-stack prometheus-community/kube-prometheus-stack \
  -n observability --create-namespace
helm install langfuse langfuse/langfuse \
  -n observability -f infra/langfuse-values.yaml

# 7. Image 簽章（Binary Authorization）
gcloud container binauthz policy import infra/binauthz-policy.yaml
```

**Beta 階段 SLO**：99.5%。LLM cache 命中率 > 30%，P95 < 8s。

### 2.3 Prod 階段（全行多單位）

關鍵升級點：

| 升級 | 工具 | 何時做 |
|---|---|---|
| GKE Standard（cluster autoscaler 可控） | gcloud + Terraform | Beta 流量 > Autopilot 經濟臨界 |
| 多 region 災備（asia-southeast1 新加坡） | GKE Multi-Cluster + Multi Cluster Ingress | 業務 critical 升級時 |
| ArgoCD GitOps | argo-cd Helm | Beta 開始 |
| Argo Rollouts canary | argoproj/argo-rollouts | Beta → Prod 過渡 |
| Multi-tenant 隔離 | Namespace per tenant + ResourceQuota | 多單位上線前 |
| Immutable audit | Cloud Storage Object Retention Lock + Pub/Sub | Prod 上線前 |

**Prod 階段 SLO**：99.95%。RTO ≤ 1hr，RPO ≤ 15min。

---

## 3. LiteLLM Gateway 整合

### 3.1 為什麼必須有

`ClaudeService` → `https://api.anthropic.com` 直連的後果（**保險業 fatal**）：
- 無法做 token 用量歸因（per user / tenant / cost center）
- LLM 出問題沒 failover（Claude 掛了整個服務跟著掛）
- 無 semantic cache（同樣的 query 重複燒 token）
- audit log 散在各 service，金管會查 7 年資料找不到
- 違反保險業自律規範「AI 執行軌跡留存」要求（罰 NT$5-20 萬）

正確路徑：`ClaudeService` → `http://litellm-gateway.llm-gateway.svc:4000` → 外部 LLM。

### 3.2 Helm Chart 部署

**注意 image 名稱**：必須用 `litellm-database`（含 Prisma migrations 寫 PostgreSQL 計費表），**不是** plain `litellm`。

```bash
# 拉 chart
helm pull oci://ghcr.io/berriai/litellm-helm --version 0.1.x

# 安裝
kubectl create namespace llm-gateway
kubectl create secret generic litellm-masterkey \
  -n llm-gateway --from-literal=masterkey="sk-$(openssl rand -hex 32)"

helm install litellm oci://ghcr.io/berriai/litellm-helm \
  -n llm-gateway -f infra/litellm-values.yaml
```

`infra/litellm-values.yaml` 關鍵設定：

```yaml
image:
  repository: ghcr.io/berriai/litellm-database
  tag: main-stable

masterkeySecretName: litellm-masterkey

db:
  deployStandalone: false               # 用外部 Cloud SQL
  url: postgresql://litellm:$DB_PASS@cloudsql-proxy:5432/litellm

redis:
  enabled: false                         # 用外部 Memorystore
  external:
    host: rules-mcp-cache.asia-east1.gcp.internal
    port: 6379

envVars:
  STORE_MODEL_IN_DB: "True"
  DISABLE_SCHEMA_UPDATE: "False"

# === 直接內嵌 litellm proxy config ===
proxy_config:
  model_list:
    # 主要 — Claude Opus 4.7（高品質生成）
    - model_name: insurance-primary
      litellm_params:
        model: anthropic/claude-opus-4-7
        api_key: os.environ/ANTHROPIC_API_KEY

    # 同名第二條 = load balance group（自動分流 + failover）
    - model_name: insurance-primary
      litellm_params:
        model: vertex_ai/gemini-2.5-pro
        vertex_project: group-rules-prod
        vertex_location: asia-east1

    # 敏感資料專用 — Ollama 自託管，不出 VPC
    - model_name: insurance-local
      litellm_params:
        model: ollama/llama3.1:70b
        api_base: http://ollama.llm-gateway.svc:11434

  router_settings:
    routing_strategy: latency-based-routing
    num_retries: 2
    cooldown_time: 30
    allowed_fails: 3

  litellm_settings:
    # === Failover 鏈 ===
    fallbacks:
      - {"insurance-primary": ["insurance-local"]}
    context_window_fallbacks:
      - {"insurance-primary": ["gemini-2.5-pro"]}

    # === Semantic cache（保險業務員用閾值）===
    cache: True
    cache_params:
      type: redis-semantic
      similarity_threshold: 0.90        # 保險業 0.88-0.92，不能太鬆
      ttl: 600
      redis_semantic_cache_embedding_model: text-embedding-3-small

  # === Guardrails ===
  guardrails:
    - guardrail_name: "insurance-input-guard"
      litellm_params:
        guardrail: lakera_v2             # 內建輕量；可選 Lasso/Pangea 強化
        mode: pre_call
        api_key: os.environ/LAKERA_API_KEY
        default_on: true
```

**關鍵注意**：semantic cache 閾值對**保險業必須是 0.88-0.92**，不可用 0.95 預設或 0.85 寬鬆值。理由：不同保單條款（例如「終身壽險」vs「定期壽險」）描述高度相似但結果完全不同，閾值太鬆會誤命中、閾值太嚴失去 cache 意義。

### 3.3 Spring 端的改動（最小）

只要改 `application-cloud.yml` 把 base URL 指向 LiteLLM，**Java 程式碼不用改**：

```yaml
# application-cloud.yml
spring:
  ai:
    anthropic:
      api-key: ${LITELLM_MASTERKEY}      # 用 LiteLLM master key 而非真 Claude key
      base-url: http://litellm-gateway.llm-gateway.svc.cluster.local:4000
      chat:
        options:
          model: insurance-primary       # 對應 LiteLLM model_list 的 alias
    openai:                              # 即便 v3.11 的 OpenAI provider，也走同一個 gateway
      api-key: ${LITELLM_MASTERKEY}
      base-url: http://litellm-gateway.llm-gateway.svc.cluster.local:4000
      chat:
        options:
          model: insurance-primary
```

`LlmProviderRegistry` 不用改。Cost tracking 自動走 LiteLLM 那層。

### 3.4 Cost Tracking 4 層歸因

LiteLLM `LiteLLM_SpendLogs` 表自動寫入：Organization → Team → User → Virtual Key。

```bash
# 為精算師團隊建 virtual key（每月 $50 budget）
curl -X POST $PROXY/key/generate \
  -H "Authorization: Bearer $MASTER_KEY" \
  -d '{
    "team_id": "underwriting",
    "user_id": "actuary-001",
    "max_budget": 50,
    "budget_duration": "30d",
    "metadata": {
      "cost_center": "UW-2026",
      "tenant": "life-insurance"
    }
  }'
```

業務端 header 加 tag：`x-litellm-tags: ["product:annuity","channel:agent"]` 做產品線報表。

### 3.5 LiteLLM vs Portkey 選型

**保險業內網建議：LiteLLM self-host**（理由）：
- MIT 授權、完全資料主權，符合金管會委外規範
- 自架在 GCP VPC，不存在資料離境問題
- 配 Lakera + Langfuse + 外部 PostgreSQL/Redis Stack 等於 Portkey commercial 9 成功能

Portkey 適合「想直接拿 SOC2/HIPAA dashboard、不想自運維」的團隊。Portkey gateway 已 Apache 2.0 開源（2026/03），但 Observability/Prompt mgmt/Governance 仍商用 $499/mo+。

---

## 4. Spring Boot 應用層生產設定

### 4.1 JVM 容器化（Java 17 + G1GC）

**結論：Java 17 + G1GC + heap < 8GB 是 2025 最穩**。ZGC 要 JDK 21+ 才成熟，且需 15-25% extra headroom，small pod 會 OOMKill。Native image 對 LLM I/O bound 應用**不建議切**（瓶頸在 LLM API latency 不在啟動時間）。

```yaml
# Deployment env
env:
  - name: JAVA_TOOL_OPTIONS
    value: >-
      -XX:+UseContainerSupport
      -XX:MaxRAMPercentage=70.0
      -XX:InitialRAMPercentage=50.0
      -XX:+UseG1GC
      -XX:MaxGCPauseMillis=200
      -XX:+ExitOnOutOfMemoryError
      -XX:+HeapDumpOnOutOfMemoryError
      -XX:HeapDumpPath=/tmp/heapdump.hprof

resources:
  requests: { memory: "768Mi", cpu: "500m" }
  limits:   { memory: "1Gi",   cpu: "1000m" }
  # 關鍵：memory request = limit，避免 eviction
```

`MaxRAMPercentage=70` 而不是 75（heap 以外要留 metaspace、direct buffer、thread stacks，不留會 native OOM）。

### 4.2 Health Probes 三段

```yaml
# application.yml
management:
  endpoint:
    health:
      probes.enabled: true
      show-details: always
  health:
    livenessstate.enabled: true
    readinessstate.enabled: true
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
```

```yaml
# Deployment
startupProbe:
  httpGet: { path: /actuator/health/liveness, port: 8080 }
  periodSeconds: 5
  failureThreshold: 30                  # 容忍 150s 啟動

readinessProbe:
  httpGet: { path: /actuator/health/readiness, port: 8080 }
  periodSeconds: 5
  failureThreshold: 3

livenessProbe:
  httpGet: { path: /actuator/health/liveness, port: 8080 }
  periodSeconds: 10
  failureThreshold: 3
  timeoutSeconds: 3
```

**關鍵設計**：
- `startupProbe` 護住慢啟動（Spring Boot 啟動久時 liveness 不會誤殺）
- **`livenessProbe` 只檢查死鎖，不檢查 downstream**（否則 LLM API 掛會把所有 pod 全 kill；用 readiness 移流量即可）

### 4.3 Graceful Shutdown（LLM streaming 必須調寬）

```yaml
# application.yml
server.shutdown: graceful
spring.lifecycle.timeout-per-shutdown-phase: 45s    # LLM streaming 可能跑數十秒
```

```yaml
# Deployment
terminationGracePeriodSeconds: 75      # 必須 > timeout-per-shutdown-phase
lifecycle:
  preStop:
    exec:
      command: ["sh","-c","sleep 10"]   # 等 Service endpoint 從 iptables 移除
```

時序：`preStop sleep 10s` → `timeout-per-shutdown-phase 45s` → `terminationGracePeriodSeconds 75s`（含 buffer）。

### 4.4 Spring AI 1.0 Timeout & Retry

```yaml
spring:
  ai:
    anthropic:
      api-key: ${LITELLM_MASTERKEY}
      base-url: http://litellm-gateway.llm-gateway.svc:4000
  http:
    client:
      connect-timeout: 10s
      read-timeout: 120s                # streaming 要寬，預設不夠
```

**Retry 規則**：用 Spring 7 `@Retryable` + exponential backoff。**429/529 才 retry**（rate limit），**5xx with body 不要盲 retry**（會浪費 token + 二次計費）。

```java
@Retryable(
    retryFor = { LlmRateLimitException.class },
    maxAttempts = 3,
    backoff = @Backoff(delay = 1000, multiplier = 2)
)
```

### 4.5 Profile 慣例：`kubernetes,prod`（不是 `cloud`）

`cloud` profile 是 Cloud Foundry 留下來的 legacy，K8s 部署不建議用。建議：

```bash
SPRING_PROFILES_ACTIVE=kubernetes,prod
```

對應 layout：
```
src/main/resources/
├── application.yml                    # 通用設定
├── application-kubernetes.yml         # K8s 環境通用（service discovery、actuator probes）
└── application-prod.yml               # production 機密外的設定
```

機密走 K8s `Secret` mount 為 env var，不要塞 yaml。

---

## 5. 合規對應

### 5.1 規範速覽（2024-2026 重點變動）

| 規範 | 日期 | 性質 | 重點 |
|---|---|---|---|
| 金管會「金融業運用 AI 之核心原則」 | 2023.10.17 | 原則 | 治理 / 公平 / 隱私 / 安全 / 透明 / 永續 |
| 金管會「金融業運用 AI 指引」 | 2024.06.20 | **行政指導**（無法律拘束力但實務上必遵） | 16 條，AI 系統執行軌跡須留存 |
| **保險業運用 AI 系統自律規範** | **2025.04.24** | **產險公會發布、金管會備查**（**有罰則 NT$5-20 萬**） | 可解釋性報告、AI 互動揭露、上線前評估 |
| **人工智慧基本法** | **2025.12.24** | **三讀通過**（不再是草案） | 七大原則；國科會主管；金管會需訂金融業子規範 |

**對 Rules MCP Server 直接影響**：
- v3.9 per-rule rationale + v3.12 BusinessNarrative + v3.13 sparsity → **直接對應「可解釋性報告」要求**，是 commercial pitch 賣點
- UI 必須有 AI 揭露 banner（前端 `RuleNarrative` 已有「由 AI 產生」標示，符合）
- LLM provider 必須可切換（v3.4 Multi-LLM 已支援，但要文件化「為什麼這樣設計」對應自律規範）

### 5.2 七大要求技術 mapping

| 規範要求 | 系統落點 | 狀態 |
|---|---|---|
| 可解釋性報告 | RuleNarrative + decision-tree-v2 | ✅ |
| AI 互動揭露 | 前端 banner + audit log 標記 | 🟡 部分 |
| 上線前評估 | LLM-as-Judge（v3.6）+ Confidence Score（v3.10）+ Grounding（v3.8） | ✅ |
| 執行軌跡留存 7 年 | Audit log → Pub/Sub → Cloud Storage **Object Retention Lock 7y** | 🟡 設計完成，未上線 |
| 個資去識別化 | `service/security/PiiRedactionService`（待實作） | ❌ P1 待補 |
| LLM provider 可切換 | LlmProviderRegistry + LiteLLM gateway failover | ✅ |
| AI 風險分級 | 預留 `RuleEnvelope.riskTier` 欄位（待實作） | ❌ P2 |

### 5.3 LLM 資料離境處理（**保險業核心問題**）

呼叫境外 LLM API（Anthropic / OpenAI）= 客戶個資境外傳輸 = 落入「金融機構作業委託他人處理應注意事項」。三個合法路徑：

**路徑 A：去識別化後送外部**（推薦給非敏感生成）
```
description（含「王先生 45 歲，年收入 100 萬」）
   ↓ PiiRedactionService
description'（「客戶 A 45 歲，年收入區間 100-150 萬」）
   ↓ LLM API
RuleEnvelope JSON
```

**路徑 B：境內 LLM**
- **Azure OpenAI Taiwan North**（2026 起 GPT-5 可用，$1.25/$10 per M tokens）
- **FinLLM 聯盟**（2024.04 啟動，16 家金融業聯合建置，**最符合金管會精神**）
- 自託管 Ollama（Llama 3.1 70B / Qwen 2.5 14B）

**路徑 C：境外但符合三條件**
- 金融機構保有指定處理地權利
- 當地法規不低於我國（美國 CCPA / 歐盟 GDPR 通常通過）
- 重要資料須在台留存備份
- **重大或境外委外須事先申請金管會核准**

**Rules MCP Server 建議路由策略**：
```
sensitivity = LOW    → Claude / Gemini 外部（路徑 A 去識別化）
sensitivity = MEDIUM → Azure OpenAI Taiwan North
sensitivity = HIGH   → 自託管 Ollama 或 FinLLM
```

由 `LlmProviderRegistry` 依 `RuleEnvelope` 內容分類自動決策。

### 5.4 Audit Log 7 年保存

**雖然 AI 指引未明訂年限**，但參照《金融業務委託他人處理作業要點》與保險業內控規定，**5 年起跳**。建議直接做 **7 年不可變**（WORM / append-only）。

```
AuditService.write()
  ↓ Java Pub/Sub publisher
Cloud Pub/Sub topic: rules-mcp.audit
  ↓ subscriber
Cloud Storage bucket（rules-mcp-audit-prod）
  ├── Object Retention Lock: 7 years
  ├── Versioning: enabled
  └── Lifecycle: 30d Standard → 90d Nearline → 永久 Coldline
  ↓ 同步
Cloud Logging (90 天熱查)
  ↓ 同步
SIEM（若有）
```

每筆 audit 必含：`timestamp, traceId, userId, tenantId, endpoint, requestHash, responseHash, confidence, provider, durationMs, llmTokensIn, llmTokensOut, costUsd, sensitivity`。

---

## 6. 觀測性實作

### 6.1 ServiceMonitor（自動讓 Prometheus 抓 Spring Actuator）

```yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: rules-mcp-backend
  namespace: rules-mcp
spec:
  selector:
    matchLabels: { app: rules-mcp, component: backend }
  endpoints:
    - port: http
      path: /actuator/prometheus
      interval: 30s
      scrapeTimeout: 10s
```

### 6.2 LLM-specific Metrics（補在 RuleServiceMetricsAspect）

目前 v3.13 只有基礎 traceId/duration。建議補：

```java
Counter.builder("llm.request.total")
    .tag("provider", providerName)
    .tag("model", modelName)
    .tag("tenant", tenantId)
    .tag("feature", "generate|explain|evaluate|narrate|optimize_v2")
    .register(registry);

Timer.builder("llm.request.duration")
    .publishPercentiles(0.5, 0.95, 0.99)
    .register(registry);

Counter.builder("llm.tokens.input.total").tag(...);
Counter.builder("llm.tokens.output.total").tag(...);
Counter.builder("llm.cost.usd.total").tag(...);
Counter.builder("llm.cache.hit.total").tag("layer", "exact|semantic");
Gauge.builder("rule.confidence.score.current", () -> lastScore);
Counter.builder("rule.hallucination.detected.total").tag("type", ...);
```

### 6.3 PromQL Alert 範例

```yaml
groups:
  - name: rules-mcp-prod
    rules:
      - alert: HighErrorRate
        expr: |
          sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
          / sum(rate(http_server_requests_seconds_count[5m])) > 0.05
        for: 5m
        labels: { severity: P1 }

      - alert: AllLlmProvidersDown
        expr: sum(up{job="litellm-gateway"}) == 0
        for: 1m
        labels: { severity: P1 }

      - alert: P95LatencyHigh
        expr: histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[5m])) > 15
        for: 10m
        labels: { severity: P2 }

      - alert: LlmCostBudgetExceeded
        expr: increase(llm_cost_usd_total[1d]) > 200      # 假設預算 $130/day
        for: 5m
        labels: { severity: P2 }

      - alert: ConfidenceScoreDegraded
        expr: avg_over_time(rule_confidence_score_current[1h]) < 60
        for: 1h
        labels: { severity: P3 }                          # LLM 品質退化
```

### 6.4 Langfuse（LLM-trace 專用）

Prometheus / Tempo 對 prompt/response 結構不熟，LLM trace 另用 Langfuse self-hosted。整合方式：在 `LlmProvider` decorator 注入 Langfuse Java SDK，每次呼叫前後 log。

---

## 7. GitOps 流程細節

```
Developer (feature branch)
    │ git push
    ▼
GitHub Actions
    ├─ mvn test (407 tests)
    ├─ npm test (38 tests)
    ├─ TypeScript strict check
    ├─ SAST: Semgrep + Snyk
    ├─ Image build (multi-stage Dockerfile)
    ├─ Trivy CVE scan (fail if HIGH+)
    ├─ Cosign sign image
    └─ Push to Artifact Registry
         │
         ▼
config repo (rules-mcp-config) — image tag bump via PR
    │ PR merged
    ▼
ArgoCD (watches config repo)
    ├─ rules-mcp-dev:     auto-sync
    ├─ rules-mcp-staging: auto-sync
    └─ rules-mcp-prod:    manual approval + auto-sync
         │
         ▼
Argo Rollouts (progressive delivery)
    canary: 5% → 25% → 50% → 100%
    success gate: error rate < 1% AND p95 < 10s
    auto-rollback on failure
```

**為什麼 GitOps**：金管會稽核要求 git 是 single source of truth；任何部署異動可 `git blame` 可 revert；比 push-based CD 更符合稽核需求。

**ArgoCD Application 範例**（每個環境一個）：
```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: rules-mcp-prod
  namespace: argocd
spec:
  project: default
  source:
    repoURL: https://github.com/ruleengine/rules-mcp-config
    targetRevision: HEAD
    path: overlays/prod
  destination:
    server: https://kubernetes.default.svc
    namespace: rules-mcp
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
      - ServerSideApply=true
```

---

## 8. 常見 Gotchas

| 雷區 | 症狀 | 解法 |
|---|---|---|
| LiteLLM 用 plain image（不含 -database） | proxy 啟動正常但 spend log 全空 | 必用 `litellm-database:main-stable` |
| Semantic cache 閾值用 0.95 預設 | 命中率超低、看似沒效 | 保險業降到 0.88-0.92 |
| Semantic cache 用 0.85 寬鬆值 | 不同保單條款被誤命中（重大事故） | 不可低於 0.85 |
| `livenessProbe` 檢查 LLM downstream | LLM API 掛 → 所有 pod 被 kill → 雪崩 | liveness 只查死鎖；downstream 用 readiness |
| `terminationGracePeriodSeconds` < `timeout-per-shutdown-phase` | streaming response 被切斷、token 浪費 | 必須 grace > timeout，差 30s |
| 用 `cloud` profile | 部分 Spring Cloud 自動配置誤觸發 | 改用 `kubernetes,prod` |
| `MaxRAMPercentage=85` 太貪 | native OOM（heap 以外不夠） | 70-75 是甜蜜點 |
| 切 GraalVM native image | 啟動快但 long-running 反而略慢、reflection hint 維護 | LLM I/O bound 不切 |
| 直連 Anthropic 不走 gateway | 金管會稽核時 7 年資料找不齊 | 全部 LLM 流量必經 LiteLLM |
| Audit log 寫本機檔案 | pod 重啟 / scale 就掉資料 | Pub/Sub → Cloud Storage Object Lock |
| EKS audit log 預設關閉 | 上線後才發現查不到 | EKS / AKS 都要手動開（GKE 是預設開） |
| 用境外 LLM 沒去識別化 | 違反「金融機構作業委託他人處理應注意事項」 | PiiRedactionService 必補 |

---

## 附錄：與現有程式碼對應的改動清單（v3.14+ 待補）

| 改動項目 | 影響檔案 | 優先級 | 對應規範 |
|---|---|---|---|
| `PiiRedactionService` 新增 | `service/security/PiiRedactionService.java` | **P0** | 個資法 + 委外規範 |
| Spring profile `kubernetes,prod` 拆分 | `application-kubernetes.yml` + `application-prod.yml` | **P0** | 部署慣例 |
| Spring AI base-url 可注入 LiteLLM | `application-kubernetes.yml` | **P0** | LLM Gateway 整合 |
| Audit 改寫至 Pub/Sub + Cloud Storage | `service/audit/AuditService.java` | **P0** | 自律規範 7 年留存 |
| LLM-specific Metrics 補齊 | `config/RuleServiceMetricsAspect.java` | P1 | 觀測性 |
| `RuleEnvelope.riskTier` 欄位 | `domain/envelope/RuleEnvelope.java` | P1 | AI 基本法風險分級 |
| AI 揭露 banner | `frontend-src/.../components/common/AiDisclosure.tsx` | P1 | 自律規範 AI 互動揭露 |
| Health probes 三段拆分 | `application.yml` | P0 | K8s 部署 |
| Graceful shutdown timeout 調寬 | `application.yml` | P0 | LLM streaming |

---

*本文件持續演進；任何異動走 PR，由 需求方 審核。*
