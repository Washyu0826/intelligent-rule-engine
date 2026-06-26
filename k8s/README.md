# Kubernetes Manifests

Reference manifests for deploying Rules MCP Server on a managed Kubernetes cluster (EKS / AKS / GKE / OpenShift).

對應設計文件：`docs/deployment-architecture.md` §5。

## 檔案結構

```
k8s/
├── README.md                  # 本檔
├── 00-namespace.yaml          # Namespace + ResourceQuota
├── 01-configmap.yaml          # 非機密設定（rules.llm.provider 等）
├── 02-external-secret.yaml    # 外部祕密管理（AWS Secrets Manager / Vault）
├── 10-backend-deployment.yaml # Spring Boot 後端
├── 11-backend-service.yaml    # ClusterIP 服務
├── 12-backend-hpa.yaml        # 自訂 metrics HPA（RPS-based）
├── 13-backend-pdb.yaml        # PodDisruptionBudget
├── 14-backend-networkpolicy.yaml  # Zero-trust 網路規則
├── 20-frontend-deployment.yaml
├── 21-frontend-service.yaml
├── 30-ingress.yaml            # Istio Gateway 或 NGINX Ingress
└── kustomization.yaml         # Kustomize 入口
```

## 適用情境

- **MVP** 階段不要直接套用，請用 docker-compose；本套件給 Beta 之後使用
- 預設假設 cluster 已具備：
  - Istio 或 NGINX Ingress + cert-manager
  - External Secrets Operator + AWS Secrets Manager（或 Vault）
  - Prometheus Operator（ServiceMonitor 自動發現）
  - KEDA（搭配 HPA 用 Prometheus metric）

## 部署流程

```bash
# 1) 建 Namespace 與基礎資源
kubectl apply -f 00-namespace.yaml

# 2) 設定機密（需先在 Secrets Manager 建好對應 key）
kubectl apply -f 02-external-secret.yaml

# 3) 部署後端
kubectl apply -f 10-backend-deployment.yaml -f 11-backend-service.yaml

# 4) 等就緒
kubectl rollout status deployment/rules-mcp-backend -n rules-mcp

# 5) HPA / PDB / NetworkPolicy
kubectl apply -f 12-backend-hpa.yaml -f 13-backend-pdb.yaml -f 14-backend-networkpolicy.yaml

# 6) 前端與 ingress
kubectl apply -f 20-frontend-deployment.yaml -f 21-frontend-service.yaml -f 30-ingress.yaml
```

或用 Kustomize：

```bash
kubectl apply -k k8s/
```

## 必要的 Secret keys（需事先在外部 Secrets Manager 建立）

```
rules-mcp/prod/claude-api-key
rules-mcp/prod/gemini-api-key
rules-mcp/prod/openai-api-key
rules-mcp/prod/postgres-password
rules-mcp/prod/redis-password
```

`02-external-secret.yaml` 會自動將上述 keys 同步至 K8s Secret `llm-secrets` / `db-secrets` 並由 Pod 注入。

## 注意事項（金管會合規對應）

- 所有容器 `runAsNonRoot: true` + `readOnlyRootFilesystem: true`（資安）
- `NetworkPolicy` 採 zero-trust，預設拒絕（資安）
- `External Secrets` refresh 1 小時，支援金鑰輪替（資安）
- Pod-level `SecurityContext` drop 全部 capabilities（資安）
- Image 需 cosign 簽名才能 pull（在 admission controller 配置，本檔未包含）
