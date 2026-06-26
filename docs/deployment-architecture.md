# Rules MCP Server — Cloud Deployment Architecture

**版本**：v1.0（對應應用版本 v3.11+）
**作者**：Washyu0826
**日期**：2026-04-24
**狀態**：提案（Proposal）

> 本文件為 Rules MCP Server 的金融業生產級雲端部署藍圖，涵蓋架構、觀測性、成本、合規（金管會 AI 指引）與分階段上線路徑。
> 設計前提：服務最終目標是**內部業務使用者（保險業務／精算師／規則維運團隊）**透過 Web UI 與 Claude Desktop（MCP 模式）操作，輸出 engine-neutral RuleEnvelope，經 exporter / adapter 轉換後交付下游集團規則引擎執行。

---

## 目錄

1. [現狀與差距](#1-現狀與差距)
2. [目標架構（High-Level）](#2-目標架構high-level)
3. [LLM-Native 部署考量](#3-llm-native-部署考量)
4. [分階段成熟度路徑](#4-分階段成熟度路徑)
5. [Kubernetes 部署設計](#5-kubernetes-部署設計)
6. [觀測性（Observability）](#6-觀測性observability)
7. [安全與合規（金管會對應）](#7-安全與合規金管會對應)
8. [成本優化](#8-成本優化)
9. [CI/CD 與 GitOps](#9-cicd-與-gitops)
10. [災難復原（DR）](#10-災難復原dr)
11. [關鍵決策（Decision Records）](#11-關鍵決策decision-records)

---

## 1. 現狀與差距

目前倉儲內的 `docker-compose.yml` + `Dockerfile` 屬 MVP 水準，可在單機或開發環境運行，但離金融業生產部署仍有顯著落差：

| 面向 | 現狀 | 生產需求 | 差距 |
|---|---|---|---|
| Compute | 單一容器 | HA、跨 AZ、autoscale | 🔴 |
| LLM 連線 | 直連 Anthropic/Google | 有 gateway、failover、成本歸因 | 🔴 |
| Cache | in-memory Caffeine | 跨 pod 共享、semantic | 🟡 |
| Secret | `.env` 檔 | KMS / Secrets Manager、輪替 | 🔴 |
| 觀測 | Actuator + Prometheus | LLM-specific metrics、trace、log | 🟡 |
| Audit | 本機檔案 | 不可變、長期保存、可查 | 🟡 |
| CI/CD | GitHub Actions build image | GitOps、canary、自動 rollback | 🔴 |
| DR | 無 | RTO/RPO 明確、異地備援 | 🔴 |
| 合規 | 無對應文件 | 金管會 AI 六原則 mapping | 🔴 |

---

## 2. 目標架構（High-Level）

```
                    ┌──────────────────────────────────────────┐
                    │   使用者（業務／精算師／稽核／開發者）       │
                    └───────────────┬──────────────────────────┘
                                    │ HTTPS (TLS 1.3)
                    ┌───────────────▼──────────────────────────┐
                    │  CDN (CloudFront / Cloud CDN)             │
                    │  + WAF（OWASP rules、rate limit、geo）     │
                    └───────────────┬──────────────────────────┘
                                    │
                    ┌───────────────▼──────────────────────────┐
                    │  API Gateway                              │
                    │  - OAuth2/OIDC（集團 AD/SSO integration） │
                    │  - rate limit per tenant/user             │
                    │  - audit logging                          │
                    └───────────────┬──────────────────────────┘
                                    │
    ┌───────────────────────────────┴───────────────────────────────┐
    │                  Kubernetes Cluster                             │
    │              (EKS / AKS / GKE / OpenShift)                      │
    │                                                                  │
    │  ┌──────────────────────────────────────────────────────────┐ │
    │  │  Ingress (Istio Gateway) + cert-manager (自動 TLS)        │ │
    │  └──────┬────────────────────────────────────┬──────────────┘ │
    │         │                                    │                   │
    │  ┌──────▼──────┐                    ┌───────▼─────────┐         │
    │  │ Frontend    │                    │ Backend         │         │
    │  │ (React SPA) │                    │ (Spring Boot)   │         │
    │  │ nginx + br  │                    │ HPA by RPS      │         │
    │  │ 2-5 pods    │                    │ 3-20 pods       │         │
    │  └─────────────┘                    └────┬────────────┘         │
    │                                          │                       │
    │                              ┌───────────┴──────────┐            │
    │                              ▼                      ▼            │
    │                    ┌───────────────────┐   ┌─────────────────┐  │
    │                    │   LLM Gateway     │   │  Data Layer     │  │
    │                    │   (LiteLLM /      │   │                 │  │
    │                    │    Portkey)       │   │  Redis          │  │
    │                    │                   │   │  - session       │  │
    │                    │ - 多 provider     │   │  - exact cache   │  │
    │                    │   failover        │   │  - semantic cache│  │
    │                    │ - cost tracking   │   │                 │  │
    │                    │ - semantic cache  │   │  PostgreSQL      │  │
    │                    │ - prompt guard    │   │  + pgvector      │  │
    │                    └────┬──────────────┘   │  - rules repo    │  │
    │                         │                  │  - audit log     │  │
    │                         ▼                  │  - rule history  │  │
    │                ┌─────────────────┐         │  - embeddings    │  │
    │                │  Ollama         │         └─────────────────┘  │
    │                │  (GPU pool)     │                              │
    │                │  敏感資料專用   │                              │
    │                └─────────────────┘                              │
    │                                                                  │
    │  ┌───────────────────────────────────────────────────────────┐  │
    │  │  Observability                                              │  │
    │  │  Prometheus + Thanos │ Loki │ Tempo │ Langfuse │ Grafana   │  │
    │  └───────────────────────────────────────────────────────────┘  │
    └──────────────────────────────────────────────────────────────────┘
                                    │ egress via NAT + Private Link
                    ┌───────────────▼──────────────────────────┐
                    │   外部 LLM APIs                            │
                    │   Anthropic / Google / OpenAI              │
                    └──────────────────────────────────────────┘
```

### 2.1 元件職責分工

| 元件 | 職責 | 技術選項 |
|---|---|---|
| CDN | 靜態資源、WAF、DDoS | CloudFront / Cloud CDN / Azure Front Door |
| API Gateway | 認證、限流、稽核 | Kong / AWS API Gateway / Azure APIM |
| Ingress | 叢集入口、TLS termination | Istio / NGINX Ingress + cert-manager |
| Backend | Spring Boot `rules-mcp-server` | OCI image 由 Dockerfile 建置 |
| LLM Gateway | **不直連外部 LLM**，所有 LLM 流量必經此層 | LiteLLM（OSS）／ Portkey（商業）／ Kong AI Gateway |
| Redis | 語意快取、session、rate-limit counter | ElastiCache / Azure Cache / Memorystore |
| PostgreSQL + pgvector | 規則庫、稽核、版本歷史、embedding 儲存 | RDS / Azure DB / Cloud SQL |
| Ollama（可選） | 敏感資料不出雲的 self-hosted LLM | 單獨 GPU node pool（L4/A10G） |
| Observability | Metrics、logs、traces、LLM trace | Prometheus + Grafana + Langfuse |

---

## 3. LLM-Native 部署考量

這是本服務與一般 Java 微服務**最重要的差別**。傳統 12-factor app 的教科書設計對 LLM 應用常常誤導。

### 3.1 LLM Gateway 必要性（不能直連）

**禁止**：`ClaudeService` → `https://api.anthropic.com`
**正確**：`ClaudeService` → `http://llm-gateway.internal:4000` → 外部 LLM

**為什麼**：

| 能力 | 沒 Gateway | 有 Gateway |
|---|---|---|
| Token 成本歸因（per user / feature / tenant） | ❌ 難 | ✅ 內建 |
| Cross-provider failover | 🟡 需自寫 | ✅ 設定即可 |
| Rate limit（單一使用者燒光 quota） | ❌ 無防護 | ✅ 可多層限流 |
| Audit trail | 🟡 散在各 service | ✅ 集中 |
| Semantic cache | 🟡 需自寫 | ✅ 內建 |
| Prompt injection 防護 | ❌ 無 | ✅ Guard 可選 |

**推薦**：**LiteLLM**（OSS，可自建，有 Helm chart）或 **Portkey**（託管）。Kong AI Gateway 若集團已用 Kong，整合最低。

### 3.2 Semantic Cache：最具 ROI 的單一優化

2025 實測顯示 semantic cache 可減少 **61–68% LLM 呼叫**。

**兩層設計**：

```
Layer 1 — Exact match（Redis KV，延遲 <5ms）
    key = sha256(description || provider || promptVersion)
    value = { ruleEnvelopeJson, timestamp, ttl }

Layer 2 — Semantic（pgvector 或 Redis Vector，延遲 <50ms）
    vector = embedding(description)
    query: SELECT ... WHERE cosine_similarity > 0.95
    命中時：回傳現成結果，或作為 few-shot 範例餵給 LLM
```

**適用判斷**：保險業務員常反覆問「20-60 歲男性核保」、「20 到 60 歲女性核保」這類語意近似的 query，命中率可觀。

**實作位置**：建議放在 LLM Gateway 層（LiteLLM 內建）；若自建，在 Spring Boot 的 `LlmProvider` decorator 加入，依賴 Spring AI 的 `VectorStore` 抽象。

### 3.3 Autoscaling：CPU HPA 無效

LLM 應用大部分時間在等 LLM API 回應，是 **I/O-bound** workload。CPU 利用率長期在 10-20%，`CPU HPA` 永不觸發。

**建議 HPA metrics**：

```yaml
# k8s/hpa-backend.yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: rules-mcp-backend
spec:
  scaleTargetRef: { kind: Deployment, name: rules-mcp-backend }
  minReplicas: 3
  maxReplicas: 20
  metrics:
    - type: Pods
      pods:
        metric: { name: http_requests_in_flight }
        target: { type: AverageValue, averageValue: "10" }
    - type: External
      external:
        metric:
          name: llm_queue_depth
          selector: { matchLabels: { service: rules-mcp } }
        target: { type: Value, value: "20" }
  behavior:
    scaleDown:
      stabilizationWindowSeconds: 300   # 避免抖動
    scaleUp:
      stabilizationWindowSeconds: 30    # 快速反應
```

搭配 **KEDA** 能以 Prometheus metrics 直接驅動，比原生 HPA 更彈性。

### 3.4 Self-hosted Ollama（處理敏感資料）

若規則描述包含個資／保單內容，不宜送外部 LLM：

- 獨立 GPU node pool（`node-role=gpu`，`taint gpu=true:NoSchedule`）
- 建議機型：L4（24GB，$0.5/hr on spot）或 A10G
- 模型：Qwen2.5-14B-instruct 或 Llama-3.1-8B
- 推論引擎：**vLLM** 或 CNCF **llm-d**（多租戶最佳化）
- `LlmProviderRegistry` 依 request sensitivity 決定路由外部 vs 內部

---

## 4. 分階段成熟度路徑

> Demo 時**先給 需求方 看這張表**，展示你有長期思維。

| 階段 | 目標情境 | 架構 | SLO | 時程 |
|---|---|---|---|---|
| **MVP** | Demo / 開發 | 單 VM + Docker Compose | 盡力 | 現狀 |
| **Pilot** | 真實使用者有限測試（≤ 10 人） | 託管 container（AWS App Runner / Azure Container Apps / Cloud Run）+ Managed Redis/DB | 99.0% | 1-2 月內 |
| **Beta** | 單一業務單位正式用（≤ 100 人） | EKS/AKS/GKE 單 cluster、LLM Gateway、Langfuse、GitOps | 99.5% | 3-4 月內 |
| **Prod** | 全行多業務單位使用（≥ 1000 人） | 多 AZ + 災備 region、ArgoCD、多租戶、immutable audit | 99.95% | 6 月+ |

### 4.1 每階段關鍵 Gate

**Pilot → Beta**：
- [ ] LLM Gateway 上線，能看到 per-user token 用量
- [ ] Semantic cache 命中率 > 30%
- [ ] P95 latency < 8s（含 LLM 呼叫）
- [ ] 稽核 log 寫入 immutable storage（WORM / S3 Object Lock）

**Beta → Prod**：
- [ ] 金管會 AI 六原則 mapping 文件通過內部稽核
- [ ] DR 演練通過（RTO < 1hr, RPO < 15min）
- [ ] 外部 LLM provider 可動態切換（Claude 掛掉 < 30s 切 Gemini）
- [ ] Canary rollout 可自動 rollback（error rate > 1% 觸發）

---

## 5. Kubernetes 部署設計

### 5.1 Namespace 規劃

```
rules-mcp-prod/          # Production
rules-mcp-staging/       # UAT
rules-mcp-dev/           # Dev
rules-mcp-obs/           # Observability stack（跨環境共用）
```

### 5.2 Deployment 關鍵設定

```yaml
# k8s/backend-deployment.yaml（摘要）
apiVersion: apps/v1
kind: Deployment
metadata:
  name: rules-mcp-backend
spec:
  replicas: 3
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 0    # 金融業不允許降容
  template:
    spec:
      affinity:
        podAntiAffinity:
          preferredDuringSchedulingIgnoredDuringExecution:
          - weight: 100
            podAffinityTerm:
              topologyKey: topology.kubernetes.io/zone
              labelSelector:
                matchLabels: { app: rules-mcp-backend }
      containers:
      - name: backend
        image: <registry>/rules-mcp-server:3.11.0
        resources:
          requests: { cpu: 500m, memory: 1Gi }
          limits:   { cpu: 2000m, memory: 2Gi }
        readinessProbe:
          httpGet: { path: /actuator/health/readiness, port: 8080 }
          initialDelaySeconds: 20
          periodSeconds: 5
        livenessProbe:
          httpGet: { path: /actuator/health/liveness, port: 8080 }
          periodSeconds: 10
        env:
        - name: RULES_LLM_PROVIDER
          valueFrom: { configMapKeyRef: { name: rules-mcp-config, key: llmProvider } }
        - name: CLAUDE_API_KEY
          valueFrom: { secretKeyRef: { name: llm-secrets, key: claude-api-key } }
```

### 5.3 PodDisruptionBudget（避免滾動升級時全掛）

```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata: { name: rules-mcp-backend-pdb }
spec:
  minAvailable: 2
  selector:
    matchLabels: { app: rules-mcp-backend }
```

### 5.4 NetworkPolicy（零信任）

```yaml
# 後端只能被 ingress 和 frontend 訪問，僅能對外訪問 Redis/PG/LLM Gateway
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: rules-mcp-backend-netpol }
spec:
  podSelector: { matchLabels: { app: rules-mcp-backend } }
  policyTypes: [Ingress, Egress]
  ingress:
    - from:
      - podSelector: { matchLabels: { app: rules-mcp-frontend } }
      - namespaceSelector: { matchLabels: { name: istio-system } }
  egress:
    - to:
      - podSelector: { matchLabels: { app: redis } }
      - podSelector: { matchLabels: { app: postgres } }
      - podSelector: { matchLabels: { app: llm-gateway } }
```

### 5.5 Secrets（非 `.env`）

推薦 **External Secrets Operator + AWS Secrets Manager / HashiCorp Vault**：

```yaml
apiVersion: external-secrets.io/v1beta1
kind: ExternalSecret
metadata: { name: llm-secrets }
spec:
  refreshInterval: 1h          # 自動重載（支援輪替）
  secretStoreRef: { name: aws-secrets-manager, kind: ClusterSecretStore }
  target: { name: llm-secrets }
  data:
    - secretKey: claude-api-key
      remoteRef: { key: rules-mcp/prod/claude-api-key }
    - secretKey: gemini-api-key
      remoteRef: { key: rules-mcp/prod/gemini-api-key }
```

---

## 6. 觀測性（Observability）

### 6.1 三柱合一 + LLM 專用層

| 層 | 工具 | 資料量 (Prod) |
|---|---|---|
| Metrics | Prometheus + Thanos | 30 天熱、13 月冷 |
| Logs | Loki（結構化 JSON） | 90 天 |
| Traces | Tempo（OpenTelemetry） | 30 天，head-based sampling 10% |
| **LLM Traces** | **Langfuse**（自建） or Helicone | 完整，含 prompt / response |
| Immutable Audit | S3 Object Lock / Azure Immutable Blob | 7 年（符合金融保存） |

### 6.2 LLM-specific 必須 emit 的 metrics

在 `RuleServiceMetricsAspect.java` 補足以下（目前只有基本的 traceId/duration）：

```java
// 建議新增的 Micrometer metrics
Counter.builder("llm.request.total")
    .tag("provider", providerName)
    .tag("model", modelName)
    .tag("tenant", tenantId)
    .tag("feature", "generate|explain|evaluate|narrate")
    .register(registry);

Timer.builder("llm.request.duration")
    .tag("provider", providerName)
    .publishPercentiles(0.5, 0.95, 0.99)
    .register(registry);

Counter.builder("llm.tokens.input.total").tag(...)
Counter.builder("llm.tokens.output.total").tag(...)
Counter.builder("llm.cost.usd.total").tag(...)   // 乘 pricing 算
Counter.builder("llm.cache.hit.total").tag("layer", "exact|semantic")
Gauge.builder("rule.confidence.score.current", () -> lastScore)
Counter.builder("rule.hallucination.detected.total").tag("type", ...)
```

### 6.3 Grafana 必備儀表板

建議至少三個：

1. **Service Health**：RPS、error rate、P50/P95/P99 latency、pod 狀態
2. **LLM Ops**：per-provider 成功率、token/min、成本累計、cache 命中率
3. **Rule Quality**：confidence 分布、grounding ratio 分布、hallucination count、前 N 個失敗 description pattern

### 6.4 Alerting（PagerDuty / OpsGenie）

| Severity | 條件 | 反應 |
|---|---|---|
| P1 | Error rate > 5% 持續 5min | 立即 page oncall |
| P1 | 所有 LLM provider down | 立即 page + 啟動 offline fallback |
| P2 | P95 latency > 15s 持續 10min | 15min 內處理 |
| P2 | 單日 LLM 成本 > 預算 150% | 15min 內處理（可能受攻擊） |
| P3 | Confidence score 平均 < 60 持續 1hr | 次日處理（LLM 品質退化） |

---

## 7. 安全與合規（金管會對應）

### 7.1 金管會 AI 六大原則 → 技術落點

依 FSC《金融業 AI 應用核心原則與政策》（2023）與《人工智慧基本法》（2025.12）：

| 原則 | 技術實作 | 相對應模組 |
|---|---|---|
| **永續發展** | GPU scale-to-zero、semantic cache 減碳 | KEDA + LLM Gateway cache |
| **人類自主** | 規則生成後**強制人類確認**才入庫 | 前端 approval workflow + `RuleService.commit()` 分離 |
| **隱私保護** | PII 自動遮罩後再送外部 LLM；敏感 case 走 Ollama | 新增 `PiiRedactionService`；`LlmProviderRegistry` 路由策略 |
| **資訊安全** | Secrets Manager + Private Link + 金鑰輪替 | External Secrets Operator + VPC Endpoint |
| **透明性** | v3.9 rationale、v3.10 confidence、v3.8 grounding；audit log immutable | 已部分實作 |
| **公平性** | v3.8 grounding + `CausalSanityCheck`（偵測性別等敏感代理變數） | 已部分實作（grounding），待補 causal |

### 7.2 Data Residency

**建議部署 region**：

| 雲 | 台灣 region | 特性 |
|---|---|---|
| AWS | ap-east-2（台北） | 2023 新開，金融業可用 |
| Azure | Taiwan North（北） | 2024 上線 |
| GCP | asia-east1（彰化） | 延遲最低、最成熟 |

若是重大消費金融系統（直接參與核保決策），**必須在台灣 region**；若僅是內部輔助工具，可考慮境外但需自評報告。

### 7.3 Audit Log 不可變

目前 `AuditService` 寫本機；生產應改為：

```
AuditService.write()
  → Kafka / Kinesis stream（fan-out）
  → S3 Object Lock (WORM) 7 年保存
  → Loki（90 天熱查）
  → SIEM（若有）
```

保存內容：`timestamp, traceId, userId, tenantId, endpoint, requestHash, responseHash, confidence, provider, durationMs`。

### 7.4 Prompt Injection / Data Exfiltration

- LLM Gateway 啟用 prompt guard（LiteLLM 有插件）
- 對 `description` 做長度與 pattern 過濾（`.env.example` 已設 `SERVER_RATE_LIMIT_*`）
- 輸出含金融敏感字（身分證、卡號格式）時警示並遮罩

---

## 8. 成本優化

### 8.1 省 LLM 成本（最大槓桿）

| 策略 | 預估節省 | 實施位置 |
|---|---|---|
| Semantic cache | **60%+ 呼叫** | LLM Gateway |
| Prompt cache（Claude） | **30% token 成本** | 已實作於 `ClaudeService` |
| Provider arbitrage（簡單問題走 Ollama） | **40% 成本** | `LlmProviderRegistry` 加路由策略 |
| 批次打包 rationale（v3.9 一次呼叫 N 條） | **N 倍減少呼叫** | 已實作 |
| Prompt 精簡（少 token 輸入） | 10-20% | Prompt engineering |

### 8.2 省 K8s 成本

| 策略 | 預估節省 | 條件 |
|---|---|---|
| Spot / Preemptible nodes | 70% | 非關鍵 workload（observability、batch） |
| Reserved Instances 1yr | 30% | 關鍵 workload（backend、DB） |
| Scale-to-zero（非工作時間） | 50% | KEDA + 依時段縮容 |
| 右 sizing（VPA 建議） | 20-30% | 定期檢視 requests/limits |

### 8.3 成本預估（Prod, 估算）

假設每日 10,000 次 generate（含 cache miss 4,000 真 LLM call）：

| 項目 | 月成本（USD） | 備註 |
|---|---|---|
| Claude API（4000 × 30 × $0.015） | ~$1,800 | 假設 90% cache hit 後 |
| EKS control plane | $73 | 固定 |
| 3× m6i.large（backend）| ~$200 | 含 reserved discount |
| 2× t3.medium（frontend） | ~$60 | |
| RDS PG（db.t4g.medium） | ~$60 | |
| ElastiCache Redis | ~$50 | |
| ALB + CloudFront | ~$40 | |
| 觀測（Grafana Cloud free tier）| $0 | 或 Langfuse self-host |
| **總計** | **~$2,300/月** | 10k req/day @ $0.23/req |

**不做 semantic cache 的話**：Claude 成本會飆到 ~$5,400/月，差距 3x。

---

## 9. CI/CD 與 GitOps

```
Developer
   │ git push feature/*
   ▼
GitHub（source repo）
   │
   ▼
GitHub Actions
   ├─ mvn test (357 tests)
   ├─ npm test (38 tests)
   ├─ TypeScript strict check
   ├─ SAST（Semgrep / CodeQL）
   ├─ Image build（multi-stage Dockerfile）
   ├─ Trivy CVE scan（fail if HIGH+）
   ├─ Cosign sign image
   └─ Push to ECR/ACR/GAR
        │
        ▼
config repo update（image tag bump via PR）
   │
   ▼
ArgoCD watches config repo
   │
   ├─ Dev: auto-sync
   ├─ Staging: auto-sync
   └─ Prod: manual approval + auto-sync
         │
         ▼
Argo Rollouts（progressive delivery）
   Canary: 5% → 25% → 50% → 100%
   Success gate: error rate < 1% AND p95 < 10s
   Auto-rollback on failure
```

**為什麼 GitOps / ArgoCD**：金融業稽核要求 git 是 single source of truth，任何部署異動可 `git blame` 可 revert，比 push-based CD 更符合稽核需求。

---

## 10. 災難復原（DR）

### 10.1 RTO / RPO 目標

| Tier | RTO | RPO | 策略 |
|---|---|---|---|
| Prod | 1 hr | 15 min | 異地 region warm standby |
| Beta | 4 hr | 1 hr | 同 region 跨 AZ |
| Dev | 24 hr | 24 hr | 備份即可 |

### 10.2 資料備份

| 資料 | 策略 | 保留 |
|---|---|---|
| PostgreSQL | Continuous WAL + 每日 snapshot | 30 天近期 + 月度 1 年 |
| Redis | 僅 cache，不備份（可重建） | — |
| Audit Log | S3 Object Lock | 7 年 |
| Rule Repository（規則版本庫） | DB 備份 + S3 cold storage | 永久 |

### 10.3 故障情境 Runbook

| 情境 | 偵測 | 處置 |
|---|---|---|
| Claude API down | `/actuator/health` LLM 指示器 DOWN | 自動切 Gemini；若全掛則 offline fallback |
| Ollama GPU node 故障 | Prometheus alert | K8s auto-reschedule；GPU 池 drain |
| Primary region outage | Route53 health check 失敗 | 手動 failover 至 DR region（1hr RTO） |
| PG primary 故障 | RDS Multi-AZ 自動 failover | < 60s，應用重連即可 |

---

## 11. 關鍵決策（Decision Records）

### ADR-001：選擇 LLM Gateway（LiteLLM）而非直連

- **決策**：所有 LLM 流量透過 LiteLLM（K8s 內自部署）
- **理由**：成本歸因、failover、semantic cache、prompt guard 一次解決
- **代替方案**：直連（無這些能力）、Portkey（託管但資料離境有疑慮）
- **日期**：2026-04-24

### ADR-002：選擇 pgvector 而非獨立 vector DB

- **決策**：embedding 儲存於 PostgreSQL 的 pgvector 擴充
- **理由**：已有 PG、規模下 pgvector 足夠（< 10M 向量）、不用多維護一個 DB
- **代替方案**：Pinecone（託管，離境）、Weaviate（多一個系統）
- **日期**：2026-04-24

### ADR-003：部署 region 以台灣為主（GCP asia-east1）

- **決策**：Prod 部署在 GCP asia-east1（彰化）為主、asia-southeast1（新加坡）DR
- **理由**：
  - 金控集團**已主用 GCP**（Group Holdings GCP case study 公開資料證實），且為**金管會首家獲核准數據上雲的金控**
  - GKE Autopilot + Binary Authorization + Cosign **原生整合**，符合 v3.11 quality signals 與保險業自律規範對 AI 系統「執行軌跡留存」要求
  - 彰化 region 2013 GA，成熟度最高；對比 AWS ap-east-2（2025 GA）、Azure Taiwan North（2026 GA）無新區風險
  - 延遲、合規（若升級為消費金融核心系統）
- **次選 / 雙雲補強**：AWS ap-east-2 用於 AI workload（Bedrock 多模型），透過 Cloud Interconnect 互連
- **日期**：2026-04-24（更新 2026-05-06：以三雲調研證實集團主用 GCP 後確認）

### ADR-004：Observability 用 Langfuse 補強 LLM trace

- **決策**：標準 Prometheus/Loki/Tempo 之外，LLM trace 另用 Langfuse（self-hosted）
- **理由**：Tempo 不熟 prompt/response 結構；Langfuse 原生支援 LLM eval
- **日期**：2026-04-24

---

## 附錄 A：與現有程式碼對應的改動清單

| 改動項目 | 影響檔案 | 優先級 |
|---|---|---|
| Spring profile `cloud` 新增 | `application-cloud.yml`（新增） | P0 |
| LLM Gateway client | `LlmProviderRegistry.java`：base URL 可注入 | P0 |
| Redis `CacheConfig` 改為分散式 | `config/CacheConfig.java` | P1 |
| Actuator `/readiness`、`/liveness` 拆分 | `application.yml` + health indicators | P0 |
| JSON structured logging | `logback-spring.xml`（新增） | P0 |
| Metrics 新增 LLM 專用 | `config/RuleServiceMetricsAspect.java` 擴充 | P1 |
| PII redaction | `service/security/PiiRedactionService.java`（新增） | P1 |
| Audit 改寫至 Kafka/S3 | `service/audit/AuditService.java` | P2 |

## 附錄 B：給 Demo 的 3 分鐘重點

1. **現況**：能跑的 docker-compose，單點、無觀測、無合規對應
2. **差距矩陣**（§1 那張表）：9 項中 6 項紅燈
3. **目標架構**（§2 那張圖）：LLM Gateway + semantic cache 是兩個關鍵
4. **成熟度路徑**（§4 那張表）：MVP → Pilot → Beta → Prod 分四階段
5. **合規對應**（§7.1）：金管會六原則逐項 map 到技術模組
6. **成本**（§8.3）：不做 cache ≈ 月 $5400；做 cache ≈ 月 $2300，差 3x
7. **下一步具體動作**：先做 P0 改動（附錄 A）

---

*本文件持續演進；任何異動走 PR 流程，由 需求方 審核。*
