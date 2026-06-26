# 三研究方向的深度技術研究

**Author:** senior engineer (research)
**Date:** 2026-06-03
**對象方向:** ① 輸入端事前偵測 ② DecisionTree 條件視覺化 ③ 與舊資料規則比對
**定位:** 把每個方向對應到已建立的學術/工程技術，標出本專案「已有 / 可複用 / 該建」與成本權衡。延續本專案論文背書的傳統（Calvanese 2016、GOSDT、IEEE 2024 SMT）。

---

## 方向 ① 輸入端事前偵測 — 「在花 token 前抓出不完整與邏輯錯誤」

### 1.1 技術全景：三個層次

事前偵測在學術與工程上分三層，成本/能力遞增：

| 層 | 方法家族 | 代表技術 | 成本 | 抓得到什麼 |
|---|---|---|---|---|
| L1 符號自洽 | 幾何 / 區間代數 | 我們的 `SpecLintService`（已建）；DMN 重疊偵測 | 0 token, <5ms | 區間矛盾、互斥值、空值域 |
| L2 NLP 需求異味 | Requirement Smell Detection + CNL | **Paska / Rimay (IEEE TSE 2024)** | 0 token（純 NLP） | 模糊、不完整、不可原子化、可追溯性 |
| L3 形式化驗證 | SMT solver | **THEANO (IEEE TSE 2024 / FSE 2025)** | 0 token（但需 solver） | 完整性、一致性的數學證明 + 反例 |

關鍵洞見：**L1/L2/L3 全部不需要呼叫 LLM**。主管要的「省 token」最強解其實是「事前根本不打 LLM 的符號/NLP 檢查」，而非「用便宜 LLM 預檢」。後者（設計文件 §5.2 的 Layer B LLM lint）應降為最後手段。

### 1.2 L2 — Requirement Smell Detection（最該補的一層）

**Paska**（arxiv 2305.07097, IEEE TSE 2024）是目前最契合本專案的研究：

- 針對**自然語言需求**用 NLP（tokenization / lemmatization / POS tagging / constituency parsing / glossary search / Tregex）偵測「異味」，對應 completeness / clarity / atomicity / correctness 等品質屬性，**並給修正建議**。
- 在**金融領域** 13 個系統、2,725 條已標註需求上評估：smell 偵測 **89% precision/recall**，修正建議 **96%/94%**。
- 搭配 **Rimay**——一套寫功能性需求的**受控自然語言（CNL）**，限制詞彙/文法/語意，逼使用者寫出完整、無歧義的需求。

**對我們的意義**：
1. 我們現在的 `DescriptionDimensionParser` 是手刻 regex（脆弱、假陰性高）。Paska 的方法論說明「用更結構化的 NLP pipeline + glossary」能大幅拉高偵測率——我們**已經有 `GlossaryService`（v3.14）**可當 glossary search 的後盾。
2. **Rimay 式 CNL = 我們的「撰寫小撇步」可以升級成半強制模板**。`NaturalLanguageInput.tsx` 已有「推薦寫法 / 建議避免」對照，這正是 CNL 的雛形。把它形式化成可被 parser 可靠解析的句型（「若 <欄位> <運算子> <值>，則 <輸出>=<值>」），等於同時提升解析率與生成品質。
3. 異味分類（不完整/歧義/不可原子化）正好補上我們 `SpecLintService` 純符號層抓不到的「語意」異味。

### 1.3 L3 — SMT-based 形式化驗證（THEANO）

**THEANO**（Menghi et al., IEEE TSE 2024「Completeness and Consistency of Tabular Requirements: An SMT-Based Verification Approach」+ FSE 2025 tool paper）：

- 把 if-then 需求表編碼成 SMT 公式，用 solver 判定**完整性**（是否每個輸入組合都有規則）與**一致性**（是否有矛盾），不一致時回**具體反例**。
- 基準：160 張需求表、2 小時 timeout，能完整檢查完整性、偵測不一致；對汽車領域 14 版需求表抓出 2 個不一致 + 5 個不完整。
- 支援 stateless 與 stateful（比 Simulink R2022a 內建只支援 stateless 更廣）。

**對我們的意義**：我們的 `ConsistencyValidator` 已經在做「窮舉幾何交集 + structured witness」——這在語意上等價於 tabular fragment 的 SMT 解（v3.7 CHANGELOG 自己也這麼寫）。**升級路徑**：對複雜布林組合（目前幾何法處理不了的跨欄位耦合），可接 **Z3 / JavaSMT** 做真正的 SMT 判定。但這是**生成後**的驗證；要搬到**生成前**，得先有結構化的描述（呼應 1.2 的 CNL）。

### 1.4 LLM Guardrails 工程實務（補強而非取代）

2024-2026 業界 guardrails 的共識（Guardrails-AI RAIL、NeMo Guardrails、Llama Guard；2025-02「Guardrails Index」基準）：

- **Input guardrails**：在進模型前用模板/嚴格格式驗證、拒絕 malformed 輸入——正是本方向。
- **Schema-constrained decoding**：用 typed schema 約束輸出（OpenAI Structured Outputs / Anthropic tool use）。我們的 `OllamaService` 已用 **JSON Schema → GBNF grammar**，這是同一思路的硬約束版，比 prompt 約束強。可考慮把 Claude/OpenAI 也升級到 tool-use schema 強制。
- **Re-ask 迴圈**：驗證失敗自動回餵——我們的 Level A retry + Level B self-repair 已實作。

### 1.5 方向①建議路線

1. ✅ L1 `SpecLintService`（已建，0 token）。
2. **L2 Requirement-Smell pass**：把 `DescriptionDimensionParser` 升級成 Paska 式 pipeline，借 `GlossaryService` 當 glossary，新增 4-5 個語意異味（模糊量詞、缺輸出、不可原子）。
3. **CNL 模板**：把「撰寫小撇步」形式化成可解析句型，提升解析率（同時利生成）。
4. L3 Z3/JavaSMT 留給生成後 validator 的進階升級，非事前必需。

---

## 方向 ② DecisionTree 條件視覺化 — 「讓判斷條件更清楚」

### 2.1 樹佈局演算法演進（這是視覺清晰度的數學基礎）

| 年 | 演算法 | 貢獻 | 複雜度 | 與我們的關係 |
|---|---|---|---|---|
| 1981 | **Reingold–Tilford**「Tidier Drawings of Trees」 | 整齊、對稱、緊湊的二元樹佈局 | O(n) for binary | d3.tree 的根 |
| 1990 | **Walker** | 推廣到 n-ary 樹 | 原為 O(n²) | — |
| 2002/06 | **Buchheim, Jünger, Leipert**「Drawing rooted trees in linear time」 | 修正 Walker 成真 O(n) | **O(n)** | **d3-hierarchy `d3.tree` 採用的就是這個** |
| 2014 | **van der Ploeg**「Drawing non-layered tidy trees in linear time」(SP&E) | 第一個**變高度節點**的 O(n) 非分層 tidy 佈局 | O(n) | **正中我們痛點** |

**關鍵技術判斷**：中文欄位名長度不一（「年齡」vs「新契約繳費管道」），節點寬高不固定。**分層（layered）佈局會浪費垂直空間且對齊難看**；**non-layered tidy（van der Ploeg 2014）才是變尺寸節點的正解**。可用實作：
- `d3-flextree`（Klortho）——d3.tree 的變節點尺寸版。
- `tidy`（zxch3n, Rust→WASM）——高效能 non-layered tidy。
- 我們現有的 `TreeLayoutEngine.ts` 是**手刻**——值得評估是否用 d3-flextree 取代，省維護成本並拿到學界驗證過的緊湊度。

### 2.2 視覺編碼：讓「條件」成為主角

研究與工程慣例（d3 tidy tree、決策樹分類器視覺化專利 US6278464）對「條件判斷清晰」的共識：

- **node = 問題，edge = 答案**：分支節點放欄位（「產地別?」），邊放運算子+值（`= 進口`、`> 60`）。我們的 `group-tree-rendering-spec.md` §4 已採此設計（operator 縮寫表 `=`/`>`/`[]`），但 `DecisionTreeView.tsx` 目前把條件壓在節點內、edge label 只有 8px——**方向反了**，該把條件移到邊、放大。
- **運算子符號化**：`between`→`[]`、`in`→`∈`，比中文「介於」更省空間且國際慣例。spec §4.1 已定義。
- **路徑敘事（path-to-rule）**：每條 root→leaf 轉一句白話——我們 `collectLeafPath` 已實作，價值最高，應從「點擊才出現」升級為「決策路徑表」常駐檢視。

### 2.3 兩套渲染器的整併

`DecisionTreeView.tsx`（Dashboard）與 `GroupTreeView.tsx`（Engine tab）並存。建議抽共用層：佈局（d3-flextree）+ operator/label 對照（`fieldLabels.ts` 已是單一來源）+ hit-path 高亮（spec §7）。避免兩套分歧。

### 2.4 方向②建議路線

1. 評估以 **d3-flextree / van der Ploeg non-layered tidy** 取代手刻 `TreeLayoutEngine`，解決中文變寬節點的緊湊度與對齊。
2. **條件主角化**：node 問句、edge 答案、運算子符號化（落實 spec §4）。
3. **決策路徑表**常駐檢視（複用 `collectLeafPath`）。
4. 整併兩套渲染器的共用層；補完 spec §7 hit-path。

---

## 方向 ③ 與舊資料規則比對 — 「文字 diff → 語意 diff → 行為 diff」

規則治理的比對有三個嚴格遞進的層次，我們現在只在最弱的第 0 層：

| 層 | 比什麼 | 技術 | 抓得到 | 我們現況 |
|---|---|---|---|---|
| L0 文字 | JSON 字串 | LCS 行 diff | 字面改動 | ✅ `DiffView.tsx`（**會被重排/改名誤導**） |
| L1 結構 | 樹/表結構 | **Zhang-Shasha 樹編輯距離** | 哪個節點增刪改 | ❌ |
| L2 語意 | 規範化後規則 | **DMN exclusive-rule 正規化 (Calvanese 2016)** | 規則集是否等價 | 部分（有 HyperRectangle） |
| L3 行為 | 決策輸出 | 情境展開 + 執行比對 / SMT 等價 | 同輸入新舊決策差異（回歸） | ❌（但機器齊全） |

### 3.1 L1 — 結構 diff：Zhang-Shasha 樹編輯距離

**Zhang & Shasha (1989)**「Simple Fast Algorithms for the Editing Distance Between Trees」：

- 計算把一棵樹轉成另一棵的最小**節點插入/刪除/重標記**次數，是字串編輯距離的樹版推廣。
- 複雜度 O(m²n²) time / O(mn) space——對我們 <30 節點的決策樹**綽綽有餘**。
- **現成 Java 實作**：**JGraphT 的 `ZhangShashaTreeEditDistance`**（免費圖庫，可直接加依賴）；另有 `zss`（Python）參考。
- 廣泛用於 AST 結構比對（程式碼 diff）、生物學、智慧教學。

**對我們**：`DecisionTree` 的 `RuleEnvelope.TreeNode` 直接餵進 Zhang-Shasha，就能回「新增 N03、刪除 N05、N02 條件改了」的**結構化 diff**，取代 `DiffView` 的文字 diff。`DecisionTable` 可先轉樹（我們有 `TableToTreeConverter`）或用規則對齊（按 ruleId / 條件簽章）。

### 3.2 L2 — 語意等價：Calvanese exclusive-rule 正規化

**Calvanese, Dumas et al.「Semantics and Analysis of DMN Decision Tables」(BPM 2016)**（我們 `DmnAnalyzer` 的理論基礎）有一個關鍵演算法：

> 把任何 S-FEEL DMN 決策表轉成**行為等價、規則互斥**的標準表，維持相同 input-output 行為。

**對我們**：要判斷「新舊規則集是否語意等價」，先把兩者各自正規化成 exclusive-rule canonical form，再比對覆蓋的超矩形集合是否相同。我們**已經有 `HyperRectangle` 幾何模型**——兩個規則集的 N 維超矩形集合做**集合差**，就能算出：
- 舊版覆蓋、新版沒覆蓋的區域（**回歸風險**）。
- 新版多覆蓋的區域。
- 同一區域但**決策結果不同**的（最危險）。

這是把既有 `DmnAnalyzer` 從「單表分析」擴成「雙表比對」，**複用度極高**。

### 3.3 L3 — 行為 diff：情境展開 + 執行比對

最有業務意義的比對：**同一組輸入，新舊規則決策是否一致？**

- 我們**已經有全套機器**：`ScenarioExpansionService`（展開情境空間）+ `RuleLookupService` / `MockGroupEngine`（執行）。
- 作法：對兩規則集的聯合輸入空間（或抽樣）跑執行，列出決策不一致的具體輸入——文字/結構 diff 都抓不到的回歸。
- 進階：用 **THEANO 式 SMT 等價判定**（方向①的 L3）做數學證明而非抽樣，對連續/大值域更可靠。

### 3.4 「舊資料」的來源與持久化

- 「舊資料」本質是**外部基準**，不是 localStorage 歷史。應接 **M4 `GroupJsonImporter`**：舊的 Group JSON/xlsx → `RuleEnvelope` → 丟進 L1/L2/L3 比對。
- 持久化骨幹用既有 `versionId`/`previousVersionId` + `AuditService` 版本鏈；把 `InMemoryAuditRepository` 升級成 JPA/Redis（介面已留）。
- 相關工具參考：**`dmn-check`（red6, GitHub）**——對 DMN 檔做靜態分析抓 bug，可借鑑其 diff/檢查項設計。

### 3.5 方向③建議路線

1. **L1 結構 diff**：加 JGraphT 依賴，用 `ZhangShashaTreeEditDistance` 做樹結構 diff（最快見效）。
2. **L2 語意 diff**：擴 `DmnAnalyzer` 成雙表超矩形集合差（回歸/新增/衝突三類）。
3. **L3 行為 diff**：複用 `ScenarioExpansionService` + `MockGroupEngine` 跑新舊決策對照。
4. 接 M4 importer 支援「上傳舊檔比對」；版本鏈持久化。

---

## 跨方向總結（給主管）

| 方向 | 最該補的技術 | 關鍵文獻 | 本專案可複用 | 工程量 |
|---|---|---|---|---|
| ① 事前偵測 | Requirement Smell (Paska/Rimay) + CNL 模板 | IEEE TSE 2024 (2305.07097); THEANO 2024 | `GlossaryService`、`SpecLintService`(已建)、GBNF | 中 |
| ② 樹視覺化 | non-layered tidy 佈局 + 條件主角化 | van der Ploeg 2014; Buchheim 2006; RT 1981 | `collectLeafPath`、`fieldLabels`、rendering spec | 中 |
| ③ 規則比對 | 樹編輯距離 + 超矩形集合差 + 行為比對 | Zhang-Shasha 1989; Calvanese 2016 | `HyperRectangle`、`ScenarioExpansion`、`MockGroupEngine`、版本鏈 | 中-高（價值最高） |

**核心判斷**：三個方向都**不需要新的 LLM 能力**，重點全在**符號/幾何/NLP/圖論**的既有成熟技術，而且本專案的 `DmnAnalyzer`/`HyperRectangle`/`ScenarioExpansion`/`GlossaryService` 已經把一半的地基打好了。這對「省成本」與「可驗證、可審計」（金融合規）兩個訴求都對齊。

---

## 參考文獻

**方向①**
- Menghi, Balai, Valovcin, Sticksel, Rajhans. *Completeness and Consistency of Tabular Requirements: An SMT-Based Verification Approach.* IEEE TSE, 2024. https://ieeexplore.ieee.org/document/10844918/
- *Theano: A Tool for Verifying the Consistency and Completeness in Tabular Requirements.* FSE 2025. https://dl.acm.org/doi/10.1145/3696630.3728599
- *Automated Smell Detection and Recommendation in Natural Language Requirements (Paska/Rimay).* IEEE TSE, 2024. https://arxiv.org/abs/2305.07097
- *Building Guardrails for Large Language Models.* arxiv 2402.01822. https://arxiv.org/pdf/2402.01822
- Guardrails-AI. https://github.com/guardrails-ai/guardrails

**方向②**
- Reingold, Tilford. *Tidier Drawings of Trees.* IEEE TSE, 1981.
- Buchheim, Jünger, Leipert. *Drawing rooted trees in linear time.* Software: Practice and Experience, 36(6), 2006.
- van der Ploeg. *Drawing non-layered tidy trees in linear time.* SP&E, 2014. https://onlinelibrary.wiley.com/doi/10.1002/spe.2213
- d3-hierarchy tree (Buchheim-based). https://d3js.org/d3-hierarchy/tree ; d3-flextree https://github.com/Klortho/d3-flextree

**方向③**
- Zhang, Shasha. *Simple Fast Algorithms for the Editing Distance Between Trees and Related Problems.* SIAM J. Comput., 1989.
- JGraphT `ZhangShashaTreeEditDistance`. https://jgrapht.org/javadoc/org.jgrapht.core/org/jgrapht/alg/similarity/ZhangShashaTreeEditDistance.html
- Calvanese, Dumas, et al. *Semantics and Analysis of DMN Decision Tables.* BPM 2016. https://arxiv.org/abs/1603.07466
- red6. *dmn-check* (DMN static analysis). https://github.com/red6/dmn-check

*End of research.*
