# DecisionTree v2 — Sparsity-Aware Optimization Pipeline

**版本**：v1.0（對應應用版本 v3.13）
**作者**：Washyu0826
**日期**：2026-04-25
**狀態**：設計 + 實作中
**對應 TaskList**：#6–#13

> 本文件是 Rules MCP Server DecisionTree 子系統 v2 重構的設計藍圖與文獻綜述。v1（既有）已有 3-pass TreeOptimizer；v2 在不破壞向後相容的前提下，引入 2025 文獻中的 sparsity-aware pruning 與等價子樹偵測，讓最終生成的樹更小、更易讀，同時保留覆蓋率。

---

## 目錄
1. [動機與目標](#1-動機與目標)
2. [2024–2025 文獻綜述](#2-20242025-文獻綜述)
3. [v1 既有實作檢視](#3-v1-既有實作檢視)
4. [v2 設計原則](#4-v2-設計原則)
5. [Sparsity Objective（目標函數）](#5-sparsity-objective目標函數)
6. [Canonicalizer（子樹規範化）](#6-canonicalizer子樹規範化)
7. [6-Pass Pipeline](#7-6-pass-pipeline)
8. [API 與相容性](#8-api-與相容性)
9. [評估方法](#9-評估方法)
10. [不在本版本的事（明確 out-of-scope）](#10-不在本版本的事明確-out-of-scope)

---

## 1. 動機與目標

### 1.1 為何需要 v2

目前 `TreeOptimizer`（v1）有三個 pass：

1. **合併語意相同的兄弟葉節點**
2. **移除死碼分支**（aggressive 模式）
3. **摺疊只有一子節點的分支**

但實務生成的樹（尤其 LLM 生成）常有以下**現有優化抓不到的退化**：

| 現象 | v1 行為 | 理想行為 |
|---|---|---|
| 兩個位置不同但結構相同的子樹 | 各自保留 | 識別為同構、可抽出共用 |
| 某條件被上層已決定 | 保留，造成冗餘判斷 | 消除冗餘 |
| 本地重排能降一層深度但 v1 不試 | 保留原順序 | 小範圍重構 |
| 一棵樹整體偏大但沒明顯錯誤 | 無 signal | 提供 sparsity score 告訴使用者「還能更小嗎？」 |

對保險業務/精算師受眾而言，**樹越小越好讀**。v2 的總體目標：**在不犧牲正確性的前提下最大化稀疏性（sparsity）**。

### 1.2 具體目標

- **正確性保持**：coverage、leaf results 不得退化（unit-tested 不變式）
- **平均葉節點數 ↓ 15–30%**（在 benchmark 上）
- **平均深度 ↓ 1–2 層**
- **可解釋性度量**：提供 `sparsityScore`、`balanceIndex`、`avgPathLength` 給 UI 顯示
- **向後相容**：不改動現有 `TreeOptimizer` 行為，v2 做為 opt-in 管線
- **決策可追溯**：每個 pass 記錄實際動作，可審計（金融業需要）

---

## 2. 2024–2025 文獻綜述

### 2.1 GOSDT（Generalized Optimal Sparse Decision Trees）

- **原始論文**：Lin et al., ICML 2020；2022 AAAI 擴展
- **核心思想**：以 branch-and-bound + DP 找到**全域最小的**樹，目標 `min loss + λ · #leaves`
- **限制**：計算代價高，僅適合 depth ≤ 4–5 的小樹
- **對 v2 的啟發**：
  - 採用 `loss + λ · leaves + μ · depth` 作為接受 pruning 變動的判準
  - 對小範圍（Pass 6：local reconstruct）可暴力試全部排列
  - 對整棵樹用貪心 + 目標函數驗證

### 2.2 LHT（Learning Hyperplane Tree, arxiv 2505.04139, 2025.05）

- **核心思想**：Oblique decision tree，每個分支條件是**特徵線性組合**（如 `0.6·age + 0.4·income > 50`）
- **優點**：對「整體風險」類複合判斷大幅縮減深度
- **對 v2 的限制**：schema 擴展過大（operator 要新增 `obliqueExpression`），影響整條管線（validator、converter、前端視覺化）
- **本版本決策**：**不納入 v2**，列入 v3（見 §10 out-of-scope），但 v2 會保留 schema 擴展點（`Condition.type` 預留欄位）

### 2.3 FIGS（Fast Interpretable Greedy-Tree Sums, PNAS 2024）

- **核心思想**：同時成長多棵小樹（加總），用 variance reduction 決定「繼續長」或「開新樹」
- **與本專案的關係**：DecisionTable 已經是多規則加總（`hitPolicy=MULTI`），此概念更相關於 converter（Table→Trees）
- **對 v2 的啟發**：衡量「何時應該拆成多個小樹」— 目前不納入，但 sparsityScore 會暴露「葉節點過多」的訊號

### 2.4 CausalDT（ScienceDirect 2024）

- **核心思想**：在分支時，不僅考慮 information gain，還檢驗**因果關係**
- **對保險應用的意義**：偵測「性別」這類可能是「風險行為」的代理變數（fairness concern）
- **本版本決策**：列入 v3（配合金管會公平性原則）；v2 先專注 sparsity

### 2.5 Subtree Isomorphism 與 Canonical Form

- **AHU 算法**（Aho/Hopcroft/Ullman）：O(n) 計算無序標籤樹的 canonical form
- **Merkle tree hashing**：DFS bottom-up 把每個子樹摘要為 hash
- **對 v2 的應用**：Pass 4（subtree deduplication）需要判斷兩子樹是否等價 — 用 canonical hash 實作

**參考資料來源**：
- [GOSDT — PyPI](https://pypi.org/project/gosdt/)
- [LHT — arxiv 2505.04139](https://arxiv.org/abs/2505.04139)
- [FIGS — PNAS 2024](https://www.pnas.org/doi/10.1073/pnas.2310151122)
- [Subtree Isomorphism — Abboud 2017](https://people.csail.mit.edu/virgi/treeiso.pdf)

---

## 3. v1 既有實作檢視

### 3.1 既有 Pass 1-3（保留不動）

**Pass 1：MergeIdenticalSiblings**（`TreeOptimizer.java:111–151`）
- 適用：所有子節點都是葉且 result 相同 → 合成單一葉
- 缺點：只看**直接子節點**，不看深層同構

**Pass 2：RemoveDeadBranches**（`TreeOptimizer.java:154–196`，aggressive 模式）
- 適用：路徑上 ancestor 條件與當前條件矛盾
- 實作：`ConditionBound` 累積路徑限制，偵測衝突
- 缺點：只偵測**完全矛盾**，不偵測**蘊含冗餘**（ancestor 已決定時，child 可簡化）

**Pass 3：CollapseSingleChildBranches**（`TreeOptimizer.java:222–254`）
- 適用：一個分支節點只剩一子節點
- 效果：減深度 1

### 3.2 既有的 OptimizeResult

```java
OptimizeResult {
    RuleEnvelope optimized;
    int nodesRemoved;
    int depthReduction;
    List<String> appliedOptimizations;
}
```

v2 會**擴充**此結構（而非替換），加入 metrics：

```java
OptimizeResultV2 extends OptimizeResult {
    TreeQualityMetrics metricsBefore;
    TreeQualityMetrics metricsAfter;
    double sparsityScoreBefore;
    double sparsityScoreAfter;
    Map<String, Integer> passContributions;   // 每個 pass 移除幾個節點
    List<SubtreeMergeRecord> subtreeMerges;   // 哪些 hash 被合併
}
```

---

## 4. v2 設計原則

### 4.1 Opt-in，非破壞性

- v1 `TreeOptimizer` 完全不動
- v2 新增 `TreeOptimizerV2` 類別，獨立 Spring bean
- v2 呼叫 v1 完成 Pass 1-3，再執行 Pass 4-6
- REST：新端點 `/tools/optimize/v2`（舊 `/tools/optimize` 不變）
- MCP：新 tool `optimize_rule_tree_v2`

### 4.2 變動可接受性由 objective 決定

所有 pass 動完後，**比較 before/after objective**：
- `objectiveAfter <= objectiveBefore` → 接受
- `objectiveAfter > objectiveBefore` → rollback 該 pass

保證**永不退化**（monotonic improvement）。

### 4.3 審計友善

每個 pass 都產出：
- 實際執行的動作清單（人類可讀中文）
- 涉及的節點 ID
- 影響的 objective 變化

寫入 `AuditService`（既有）。

### 4.4 Schema 向前相容

`Condition` 類別保留一個可選欄位 `type` 給未來 oblique 擴展用，v2 僅處理 axis-aligned。

---

## 5. Sparsity Objective（目標函數）

### 5.1 公式

```
Objective(T) = CoverageLoss(T) + λ · Leaves(T) + μ · Depth(T)
```

- **CoverageLoss(T)** = `1 − coverageRate`（既有 `TreeAnalyzer` 計算）
- **Leaves(T)** = 葉節點總數
- **Depth(T)** = max depth
- **λ（leafWeight）**：預設 `0.01`（每多 1 個葉，objective +0.01）
- **μ（depthWeight）**：預設 `0.005`（每多 1 層深度，objective +0.005）

### 5.2 預設值的推導

對一棵**典型保險規則樹**（12 葉、深度 5、coverage 1.0）：
- Objective = 0 + 0.01·12 + 0.005·5 = 0.145

若 pruning 後少 2 葉、少 1 層（但 coverage 降至 0.99）：
- Objective = 0.01 + 0.01·10 + 0.005·4 = 0.130 → 接受（降了 0.015）

若 pruning 讓 coverage 降至 0.85：
- Objective = 0.15 + 0.10 + 0.02 = 0.27 → 拒絕

### 5.3 參數可調

`OptimizeConfigV2`：
```java
double leafWeight;         // λ, 預設 0.01
double depthWeight;        // μ, 預設 0.005
double coverageFloor;      // 絕對下限 0.95，coverage 掉到此以下無論 obj 都拒絕
int maxReconstructDepth;   // Pass 6 局部重構的最大深度，預設 4
boolean enablePass4;       // 子樹去重
boolean enablePass5;       // 冗餘條件消除
boolean enablePass6;       // 局部重構
```

### 5.4 為什麼不直接用 GOSDT 全域最優

- **計算成本**：GOSDT 對 depth ≥ 6 會超時
- **LLM 已做了好的初始化**：把 LLM 生成當起點，用 local search 做 sparsification 就夠
- **審計性**：GOSDT 黑箱式找最優，不如「逐 pass 記錄」友善

---

## 6. Canonicalizer（子樹規範化）

### 6.1 目的

判斷兩棵子樹「語意上等價」。簡單地做 JSON 比對會失敗，因為：
- 兄弟分支順序可能不同（但邏輯等價）
- ENUM 值的 list 順序可能不同
- `between [20,30]` 等價於同義寫法

### 6.2 演算法：Merkle-like DFS 雜湊

```
canonicalHash(node):
    if leaf:
        return H("leaf|" + canonicalResults(results))
    else:
        childHashes = sorted([(canonicalBranch(b), b.label) for b in branches])
        return H("branch|" + canonicalCondition(condition) + "|" + childHashes)
```

### 6.3 條件正規化

- operator 正規化：`lessThanOrEqual X` ≡ `negated greaterThan X`（選一個作正規形）
- between 值排序
- in / notIn 值排序
- BOOLEAN 值統一 true/false（非字串）

### 6.4 結果正規化

- results 依 `field` 排序
- value 型別正規化（integer string "30" → 30）

### 6.5 使用場景

- Pass 4 subtree dedup：兩子樹 hash 相同 → 可共用
- Pass 1 升級版：原本只看直接子節點，現在可看深層同構
- 測試輔助：不同順序的 envelope 可驗證是否 semantic-equal

---

## 7. 6-Pass Pipeline

### 7.1 總覽

```
Pass 1: MergeIdenticalSiblings      (v1 既有，葉層合併)
Pass 2: RemoveDeadBranches          (v1 既有，路徑矛盾偵測)
Pass 3: CollapseSingleChildBranches (v1 既有，摺疊單子節點)
───── v2 新增 ─────
Pass 4: DeduplicateIsomorphicSubtrees  (跨位置同構子樹合併)
Pass 5: EliminateRedundantConditions   (祖先蘊含的條件消除)
Pass 6: LocalReconstruction            (小子樹內局部重排)
```

### 7.2 Pass 4：DeduplicateIsomorphicSubtrees

**輸入**：整棵樹
**步驟**：
1. DFS 計算每個子樹的 canonical hash
2. 建 `Map<hash, List<TreeNode>>`
3. 對 `size >= 2` 的 group：
   - 若合併後 objective 改善 → 將後出現者替換為對第一個的「reference」（在最終 envelope 裡用 inline 複製，因 RuleEnvelope 無 ref 機制）
   - v2 實作上「合併」= 兩子樹都複製自 canonical 版本，省掉重複推理工
4. 記錄 `SubtreeMergeRecord`

**複雜度**：O(n · h) 其中 n=節點數, h=雜湊常數時間
**保證**：不改變 coverage、leaves、depth（純粹是語意保持的正規化）

### 7.3 Pass 5：EliminateRedundantConditions

**輸入**：整棵樹 + 路徑狀態 `PathContext`
**核心**：對每個分支節點，檢查 `condition` 是否被 `pathContext` **蘊含**：
- 祖先有 `age between [20,65]`，當前有 `age greaterThan 18` → 冗餘
- 祖先有 `gender equals M`，當前有 `gender in [M,F]` → 冗餘

**Implication Logic** (15 cases)：
```
A: age >= 20      implies  age > 10          (equal-or-weaker lower bound)
A: age in [M,F]   implies  age equals M      (for this path)
A: x anything     implies  any condition on x
A: gender = M     implies  gender notEquals F
...
```

**步驟**：
1. DFS 維護 `PathBounds`（類似 v1 Pass 2 的 `ConditionBound`）
2. 對每個分支的 condition，若被蘊含 → 將該分支**摺疊**（只保留對應 child）
3. objective 比較，接受或 rollback

**與 Pass 2 的差異**：Pass 2 只偵測**矛盾**（刪除不可能的分支），Pass 5 偵測**冗餘**（刪除已被決定的分支）

### 7.4 Pass 6：LocalReconstruction

**輸入**：整棵樹
**步驟**：
1. 找出**葉節點數 ≤ K** 的子樹（預設 K=8，對應 depth ≤ 3）
2. 對該子樹：
   - 枚舉所有路徑得到 RuleRow list
   - 對這些 rules 重新用 `TableToTreeConverter`（既有）跑 ID3
   - 若產出的樹 objective 更優 → 替換
3. 對整棵樹重複（top-down），避免同一區域多次重構

**複雜度**：每個小子樹 O(2^K · k!)，K=8 時約 5 秒內
**保證**：objective monotonically improves

### 7.5 Pass 順序的理由

- **1–3 先執行**：去除明顯退化（葉合併、死碼、單子節點）
- **4 比 5 前**：去重後的樹再偵測冗餘更有效
- **6 最後**：前面的 pass 已將樹「規範化」，重構效率最高

---

## 8. API 與相容性

### 8.1 新 DTO

```java
// ToolDtos.java 新增
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public static class OptimizeRequestV2 {
    @NotNull JsonNode ruleJson;
    OptimizeConfigV2 config;   // 可 null 用預設
}

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(NON_NULL)
public static class OptimizeResponseV2 {
    RuleEnvelope optimized;
    TreeQualityMetrics metricsBefore;
    TreeQualityMetrics metricsAfter;
    double sparsityScoreBefore;
    double sparsityScoreAfter;
    List<String> appliedOptimizations;
    Map<String, Integer> passContributions;
    long durationMs;
}
```

### 8.2 新端點

```
POST /tools/optimize/v2
Content-Type: application/json
Body: OptimizeRequestV2
Response: OptimizeResponseV2
```

### 8.3 MCP Tool

```
Tool 11: optimize_rule_tree_v2
Params: ruleJsonStr, configJsonStr?
```

### 8.4 舊端點

`POST /tools/optimize`（v1）**保留不變**。前端遷移由 feature flag 控制。

---

## 9. 評估方法

### 9.1 Unit Tests

見 [Task #12]，預計 40+ 測試。核心不變式：

- **Invariant A**：v2 不得降低 coverage（在 `coverageFloor` 之上）
- **Invariant B**：v2 不得增加 leaves / depth（每 pass 後驗證）
- **Invariant C**：對 `aggressive=false` 的輸入，不得改變 leaf results（只能結構重排）
- **Invariant D**：canonicalHash(before) 的**語意集合**與 canonicalHash(after) 等價

### 9.2 Integration Tests

- 10 個真實業務 envelope（`TestCasesV1`），跑 v1 vs v2，比較 metrics
- 記錄：平均葉節點減少 %、平均深度減少

### 9.3 回歸保證

CI 跑完整 v1 測試集 + v2 新增，確認 368 + N 全綠。

---

## 10. 不在本版本的事（明確 out-of-scope）

以下項目列入 v3（下次迭代）：

1. **Oblique trees（LHT）**：需大改 schema 與前端視覺化
2. **Causal sanity check**：公平性驗證，需設計敏感屬性清單
3. **FIGS-style multi-tree ensemble**：改為 DecisionTable + multiple small trees
4. **GOSDT 全域最優替代**：對小樹（leaves ≤ 16）可直接叫 GOSDT（Java port 或 JNI）
5. **前端「稀疏度滑桿」UI**：讓使用者調 λ/μ 看即時效果
6. **差分編輯（diff view）**：對比 v1 vs v2 輸出的節點對映

---

## 附錄 A：TreeQualityMetrics 欄位

```java
record TreeQualityMetrics(
    int leafCount,
    int branchNodeCount,
    int maxDepth,
    double avgPathLength,     // 根到葉的平均深度
    double balanceIndex,      // 0..1，越高越平衡
    double sparsityScore,     // objective 值
    int uniqueSubtrees,       // canonical hash distinct count
    double duplicationRatio   // (totalSubtrees - uniqueSubtrees) / totalSubtrees
) {}
```

## 附錄 B：實作順序

1. `TreeCanonicalizer` — 基礎工具，其他都用它
2. `TreeQualityMetrics` — 度量，先能算才能比
3. `SparsityObjective` — 比較器
4. `TreeOptimizerV2` — 整合 + 6 passes
5. REST/MCP 端點
6. 測試
7. CHANGELOG
