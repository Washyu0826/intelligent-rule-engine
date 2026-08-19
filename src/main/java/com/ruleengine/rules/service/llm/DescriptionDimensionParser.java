package com.ruleengine.rules.service.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 從中文自然語言描述中預解析輸入/輸出維度。
 *
 * 將解析結果注入 LLM prompt，避免小模型無法從複雜中文描述中正確識別維度。
 */
@Component
@Slf4j
public class DescriptionDimensionParser {

    // v3.16.3：移除 static ThreadLocal CURRENT_DIMS。
    //
    // 原本用它在「LLM 生成」與「generator 後處理」之間隱性傳遞解析結果，但：
    //   - 只有 OllamaService 寫入，只有 DecisionTableGenerator 清除；
    //     DecisionTreeGenerator / ScoreCardGenerator 走完後值會殘留在執行緒上。
    //   - v3.16 起 LLM 工作跑在共用的 llmExecutor 池，執行緒高度重用 →
    //     殘留值會被「下一個請求」讀到（別人的欄位進到你的 prompt 與 envelope）。
    //
    // 現行作法：parse() 是純函式，呼叫端各自獨立呼叫；generator 端是否套用維度後處理
    // 由 LlmProvider.usesDimensionPreparse() 顯式宣告。無共享可變狀態。

    /**
     * 解析結果
     */
    public record ParsedDimensions(
            List<DimensionInfo> inputs,
            List<DimensionInfo> outputs,
            int estimatedCartesian
    ) {}

    public record DimensionInfo(
            String chineseName,
            String suggestedEnglishName,
            String suggestedTypeRef,
            List<String> values
    ) {}

    // ── 匹配「X（A／B／C）」或「X（A、B、C）」的模式 ──
    private static final Pattern DIM_WITH_VALUES = Pattern.compile(
            "([\\u4e00-\\u9fff\\w]+?)\\s*[（(]\\s*([^）)]+?)\\s*[）)]"
    );

    // ── 匹配「是否X」的模式 ──
    private static final Pattern BOOLEAN_DIM = Pattern.compile(
            "是否(?:有|為|從事)?\\s*([\\u4e00-\\u9fff\\w]+)"
    );

    // ── 匹配「是否有X、Y、Z」列舉多個 boolean 維度 ──
    private static final Pattern BOOLEAN_ENUM_LIST = Pattern.compile(
            "是否(?:有|為)?\\s*([\\u4e00-\\u9fff\\w]+(?:[、，,][\\u4e00-\\u9fff\\w]+)+)"
    );

    // ── 匹配「年齡是否介於X–Y歲」的範圍 boolean ──
    private static final Pattern AGE_RANGE_BOOLEAN = Pattern.compile(
            "年齡是否介於\\s*(\\d+)\\s*[–\\-~]\\s*(\\d+)\\s*歲?"
    );

    // ── 分隔符：／、/、，、、 ──
    private static final Pattern VALUE_SEPARATOR = Pattern.compile("[／/、，,]");

    // ── 「輸出」標記之前都是 input，之後都是 output ──
    private static final Pattern OUTPUT_MARKER = Pattern.compile("輸出[^：:]*[：:]?");

    // ── 方括號欄位/值（核保前端常見格式）：[被保人ID]、[受理通路]、[TW] ──
    private static final Pattern BRACKETED_TERM = Pattern.compile(
            "[\\[【「]\\s*([^\\[\\]【】「」]{1,30}?)\\s*[\\]】」]"
    );

    // ── 拋訊息 / 拋出訊息 / 錯誤訊息：表示有錯誤碼 + 錯誤訊息兩個輸出 ──
    private static final Pattern ERROR_MESSAGE_MARKER = Pattern.compile(
            "(?:拋訊息|拋出訊息|拋出錯誤訊息|錯誤訊息|errorMessage)"
    );

    // ── 訊息編號（X.Y 或 X.Y.Z）—— 偵測為「錯誤碼」輸出 ──
    private static final Pattern ERROR_CODE = Pattern.compile(
            "(?:拋訊息|錯誤訊息)?\\s*(\\d+\\.\\d+(?:\\.\\d+)?)"
    );

    // ── 編號條目（核保規格典型格式：1.、2.1、2.2、3.1 ...）—— 估規則數用 ──
    private static final Pattern NUMBERED_RULE = Pattern.compile(
            "(?m)^\\s*(?:條件|檢核)?\\s*(\\d+(?:\\.\\d+)?)\\s*[\\.：:]"
    );

    // ── 已知的「值」（不是欄位名）—— 過濾用，避免被誤認為輸入欄位 ──
    private static final Set<String> KNOWN_VALUES = Set.of(
            "TW", "tw", "Y", "N", "是", "否", "true", "false", "空值", "null",
            "中華民國身分證字號格式", "身分證字號格式", "身分證格式", "中華民國",
            "保代", "直效", "特約", "銀保",
            "行動保險", "網路投保", "直效線上成交",
            "指定帳戶轉帳", "A7扣薪", "A8扣薪", "滿期金", "解約金",
            "業務員", "行政人員", "在職員工",
            "受理編號", "受理編號規則", "有效授權書編號規則", "授權書編號規則",
            "隔日", "今日", "明日",
            "非網投件", "網投件", "試算上傳", "保代通路",
            "6"
    );

    // ── 中文到英文名稱映射（常見保險領域） ──
    private static final Map<String, String> CHINESE_TO_ENGLISH = new LinkedHashMap<>();
    static {
        CHINESE_TO_ENGLISH.put("年齡", "age");
        CHINESE_TO_ENGLISH.put("被保人年齡", "age");
        CHINESE_TO_ENGLISH.put("投保年齡", "age");
        CHINESE_TO_ENGLISH.put("性別", "gender");
        CHINESE_TO_ENGLISH.put("高血壓", "hypertension");
        CHINESE_TO_ENGLISH.put("糖尿病", "diabetes");
        CHINESE_TO_ENGLISH.put("心臟病病史", "heartDisease");
        CHINESE_TO_ENGLISH.put("心臟病", "heartDisease");
        CHINESE_TO_ENGLISH.put("吸菸狀態", "smokingStatus");
        CHINESE_TO_ENGLISH.put("吸菸", "smoking");
        CHINESE_TO_ENGLISH.put("BMI區間", "bmiRange");
        CHINESE_TO_ENGLISH.put("BMI", "bmi");
        CHINESE_TO_ENGLISH.put("職業風險等級", "occupationRiskLevel");
        CHINESE_TO_ENGLISH.put("職業類別", "occupationCategory");
        CHINESE_TO_ENGLISH.put("高風險活動", "highRiskActivity");
        CHINESE_TO_ENGLISH.put("理賠次數", "claimCount");
        CHINESE_TO_ENGLISH.put("殘廢等級", "disabilityLevel");
        CHINESE_TO_ENGLISH.put("核保決議", "underwritingDecision");
        CHINESE_TO_ENGLISH.put("保費係數", "premiumCoefficient");
        CHINESE_TO_ENGLISH.put("備註說明", "remark");
        CHINESE_TO_ENGLISH.put("備註", "remark");
        CHINESE_TO_ENGLISH.put("費率等級", "rateLevel");
        CHINESE_TO_ENGLISH.put("加費百分比", "surchargePercent");
        CHINESE_TO_ENGLISH.put("加費原因說明", "surchargeReason");
        CHINESE_TO_ENGLISH.put("投保決議", "insuranceDecision");
        CHINESE_TO_ENGLISH.put("最高承保金額上限", "maxCoverageAmount");
        CHINESE_TO_ENGLISH.put("除外條款說明", "exclusionClause");
        // 核保前端檢核 — ID / 國籍 / 通路 / 繳費管道 / 投保始期
        CHINESE_TO_ENGLISH.put("被保人ID", "insuredId");
        CHINESE_TO_ENGLISH.put("要保人ID", "applicantId");
        CHINESE_TO_ENGLISH.put("被保人國籍別", "insuredNationality");
        CHINESE_TO_ENGLISH.put("要保人國籍別", "applicantNationality");
        CHINESE_TO_ENGLISH.put("國籍別", "nationality");
        CHINESE_TO_ENGLISH.put("受理通路", "receivingChannel");
        CHINESE_TO_ENGLISH.put("通路", "channel");
        CHINESE_TO_ENGLISH.put("授權書編號", "authNumber");
        CHINESE_TO_ENGLISH.put("受理編號", "acceptanceNumber");
        CHINESE_TO_ENGLISH.put("新契約繳費管道", "newPaymentChannel");
        CHINESE_TO_ENGLISH.put("續期繳費管道", "renewalPaymentChannel");
        CHINESE_TO_ENGLISH.put("繳費管道", "paymentChannel");
        CHINESE_TO_ENGLISH.put("投保始期", "policyStartDate");
        CHINESE_TO_ENGLISH.put("被保人生日", "birthday");
        CHINESE_TO_ENGLISH.put("團體種類", "groupType");
        CHINESE_TO_ENGLISH.put("職域代碼", "occupationCode");
        CHINESE_TO_ENGLISH.put("errorCode", "errorCode");
        CHINESE_TO_ENGLISH.put("errorMessage", "errorMessage");
        CHINESE_TO_ENGLISH.put("錯誤碼", "errorCode");
        CHINESE_TO_ENGLISH.put("錯誤訊息", "errorMessage");
    }

    /**
     * v3.8.0：依據內建中文→英文字典，從自由格式描述中推測應該存在的英文欄位名。
     *
     * 供 {@link com.ruleengine.rules.service.evaluator.GroundingGuardService} 使用 —
     * 不需要 parse() 依賴的括號格式，單純針對 {@link #CHINESE_TO_ENGLISH} 做 substring 檢查。
     *
     * @param description 自由格式描述
     * @return 所有在描述中出現過的中文 keyword 對應的英文欄位名（lowercase）
     */
    public Set<String> inferExpectedEnglishFields(String description) {
        if (description == null || description.isBlank()) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : CHINESE_TO_ENGLISH.entrySet()) {
            if (description.contains(e.getKey())) {
                result.add(e.getValue().toLowerCase());
            }
        }
        return result;
    }

    /**
     * 從描述中解析維度。
     */
    public ParsedDimensions parse(String description) {
        if (description == null || description.isBlank()) {
            return new ParsedDimensions(List.of(), List.of(), 0);
        }

        // 找到「輸出」位置，分割 input/output 區域
        Matcher outputMarker = OUTPUT_MARKER.matcher(description);
        String inputSection;
        String outputSection;

        if (outputMarker.find()) {
            inputSection = description.substring(0, outputMarker.start());
            outputSection = description.substring(outputMarker.start());
        } else {
            inputSection = description;
            outputSection = "";
        }

        List<DimensionInfo> inputs = new ArrayList<>();
        List<DimensionInfo> outputs = new ArrayList<>();

        // 解析 input 維度
        extractDimensions(inputSection, inputs);

        // 解析 output 維度
        extractDimensions(outputSection, outputs);

        // 解析「是否X」模式的 boolean 維度（僅限 input 區域）
        extractBooleanDimensions(inputSection, inputs);

        // v3.14：核保前端常見的 [fieldName] 方括號格式 + 拋訊息錯誤輸出
        extractBracketedFields(inputSection, inputs);
        extractErrorOutputs(description, outputs);

        // 去重（基於中文名）
        inputs = dedup(inputs);
        outputs = dedup(outputs);

        // 計算規則數估算：優先用編號條目（1./2.1/2.2 ...），其次走笛卡爾積
        int cartesian = countNumberedRules(description);
        if (cartesian == 0) {
            cartesian = 1;
            for (DimensionInfo dim : inputs) {
                int count = dim.values().size();
                if (count > 0) {
                    cartesian *= count;
                }
            }
            if (cartesian == 1 && inputs.isEmpty()) {
                cartesian = 0;
            }
        }

        log.info("維度解析完成 | inputs={} | outputs={} | estimatedCartesian={}",
                inputs.size(), outputs.size(), cartesian);
        for (DimensionInfo dim : inputs) {
            log.debug("  input: {} ({}) [{}] values={}",
                    dim.chineseName(), dim.suggestedEnglishName(), dim.suggestedTypeRef(), dim.values());
        }
        for (DimensionInfo dim : outputs) {
            log.debug("  output: {} ({}) [{}] values={}",
                    dim.chineseName(), dim.suggestedEnglishName(), dim.suggestedTypeRef(), dim.values());
        }

        return new ParsedDimensions(inputs, outputs, cartesian);
    }

    private void extractDimensions(String section, List<DimensionInfo> dims) {
        Matcher m = DIM_WITH_VALUES.matcher(section);
        while (m.find()) {
            String chName = m.group(1).trim();
            String valuesStr = m.group(2).trim();

            // 跳過太短或看起來不像維度名的
            if (chName.length() < 1) continue;
            // 跳過「是否」開頭但又帶括號的（已經在 boolean 解析處理）
            // 但如果值不是「是／否」就不跳過

            List<String> values = splitValues(valuesStr);

            // 判斷類型
            String typeRef = inferTypeRef(chName, values);
            String engName = inferEnglishName(chName);

            dims.add(new DimensionInfo(chName, engName, typeRef, values));
        }
    }

    private void extractBooleanDimensions(String section, List<DimensionInfo> dims) {
        Set<String> existingNames = new HashSet<>();
        for (DimensionInfo d : dims) {
            existingNames.add(d.chineseName());
        }

        // 1. 匹配「年齡是否介於X–Y歲」
        Matcher ageRange = AGE_RANGE_BOOLEAN.matcher(section);
        while (ageRange.find()) {
            String chName = "年齡是否介於" + ageRange.group(1) + "–" + ageRange.group(2) + "歲";
            if (!existingNames.contains(chName)) {
                dims.add(new DimensionInfo(chName, "ageInRange", "BOOLEAN",
                        List.of("是", "否")));
                existingNames.add(chName);
            }
        }

        // 2. 匹配「是否有X、Y、Z」列舉多個 boolean 維度
        Matcher enumList = BOOLEAN_ENUM_LIST.matcher(section);
        while (enumList.find()) {
            String fullMatch = enumList.group(1).trim();
            String[] items = fullMatch.split("[、，,]");
            for (String item : items) {
                String chName = item.trim();
                if (chName.isEmpty()) continue;
                if (existingNames.stream().anyMatch(e -> e.contains(chName) || chName.contains(e))) continue;

                String engName = inferEnglishName(chName);
                dims.add(new DimensionInfo(chName, engName, "BOOLEAN",
                        List.of("是", "否")));
                existingNames.add(chName);
            }
        }

        // 3. 匹配單獨的「是否X」
        Matcher m = BOOLEAN_DIM.matcher(section);
        while (m.find()) {
            String chName = m.group(1).trim();
            if (existingNames.stream().anyMatch(e -> e.contains(chName) || chName.contains(e))) continue;

            String engName = inferEnglishName(chName);
            dims.add(new DimensionInfo(chName, engName, "BOOLEAN",
                    List.of("是", "否")));
            existingNames.add(chName);
        }
    }

    /**
     * v3.14：抓 `[fieldName]` 方括號格式的欄位（核保前端常見寫法）。
     * 過濾已知的「值」字串（TW / 保代 / A7扣薪 ...）。
     */
    private void extractBracketedFields(String section, List<DimensionInfo> dims) {
        Set<String> existingNames = new HashSet<>();
        for (DimensionInfo d : dims) existingNames.add(d.chineseName());

        Matcher m = BRACKETED_TERM.matcher(section);
        while (m.find()) {
            String term = m.group(1).trim();
            if (term.isEmpty() || term.length() > 25) continue;
            if (KNOWN_VALUES.contains(term)) continue;
            if (term.matches("\\d+(\\.\\d+)?")) continue; // 純數字（例 1.1）跳過
            if (existingNames.contains(term)) continue;
            // 過長 / 含太多標點通常是訊息文字、不是欄位
            if (term.contains("，") || term.contains("。") || term.contains("、")) continue;

            String engName = inferEnglishName(term);
            String typeRef = inferTypeRefForBracketed(term);
            dims.add(new DimensionInfo(term, engName, typeRef, List.of()));
            existingNames.add(term);
        }
    }

    /**
     * 為 bracketed 欄位推斷 typeRef（基於名稱關鍵字）。
     */
    private String inferTypeRefForBracketed(String chName) {
        if (chName.endsWith("日") || chName.endsWith("期") || chName.contains("生日")) return "DATE";
        if (chName.endsWith("ID") || chName.endsWith("編號") || chName.endsWith("代碼")) return "STRING";
        if (chName.endsWith("數") || chName.endsWith("金額") || chName.endsWith("年齡")) return "INTEGER";
        return "ENUM";
    }

    /**
     * v3.14：偵測「拋訊息 / 錯誤訊息」描述。出現任一次就視為有兩個輸出：errorCode + errorMessage。
     */
    private void extractErrorOutputs(String description, List<DimensionInfo> outputs) {
        if (!ERROR_MESSAGE_MARKER.matcher(description).find()
                && !ERROR_CODE.matcher(description).find()) return;

        Set<String> existing = new HashSet<>();
        for (DimensionInfo d : outputs) existing.add(d.suggestedEnglishName());

        if (!existing.contains("errorCode")) {
            outputs.add(new DimensionInfo("錯誤碼", "errorCode", "STRING", List.of()));
        }
        if (!existing.contains("errorMessage")) {
            outputs.add(new DimensionInfo("錯誤訊息", "errorMessage", "STRING", List.of()));
        }
    }

    /**
     * v3.14：統計編號條目（1.、2.1、2.2、3.1 ...）作為規則數估算。
     */
    private int countNumberedRules(String description) {
        Matcher m = NUMBERED_RULE.matcher(description);
        int count = 0;
        while (m.find()) count++;
        return count;
    }

    private List<String> splitValues(String valuesStr) {
        String[] parts = VALUE_SEPARATOR.split(valuesStr);
        List<String> values = new ArrayList<>();
        for (String part : parts) {
            String v = part.trim();
            if (!v.isEmpty()) {
                values.add(v);
            }
        }
        return values;
    }

    private String inferTypeRef(String chName, List<String> values) {
        // 如果值都是數字
        if (values.stream().allMatch(v -> v.matches("-?\\d+(\\.\\d+)?"))) {
            return values.stream().anyMatch(v -> v.contains(".")) ? "DECIMAL" : "INTEGER";
        }
        // 如果值是「是／否」
        if (values.size() == 2 && values.contains("是") && values.contains("否")) {
            return "BOOLEAN";
        }
        // 如果名稱含有「年齡」且值含有 – 或 - 分段
        if (chName.contains("年齡") && values.stream().anyMatch(v -> v.contains("–") || v.contains("-") || v.contains("歲"))) {
            return "INTEGER";
        }
        // 如果值包含「空值」
        if (values.stream().anyMatch(v -> v.contains("空值") || v.contains("null"))) {
            // 過濾掉空值描述
            return "ENUM";
        }
        // 預設為 ENUM
        return "ENUM";
    }

    private String inferEnglishName(String chName) {
        // 先完全匹配
        if (CHINESE_TO_ENGLISH.containsKey(chName)) {
            return CHINESE_TO_ENGLISH.get(chName);
        }
        // 部分匹配（取最長匹配）
        String best = null;
        int bestLen = 0;
        for (Map.Entry<String, String> entry : CHINESE_TO_ENGLISH.entrySet()) {
            if (chName.contains(entry.getKey()) && entry.getKey().length() > bestLen) {
                best = entry.getValue();
                bestLen = entry.getKey().length();
            }
        }
        if (best != null) return best;

        // 用拼音或原文
        return "field_" + chName.hashCode();
    }

    private List<DimensionInfo> dedup(List<DimensionInfo> dims) {
        // 第一輪：以 chineseName 為 key 去重，同名取較多 values 的版本
        LinkedHashMap<String, DimensionInfo> byCh = new LinkedHashMap<>();
        for (DimensionInfo dim : dims) {
            if (!byCh.containsKey(dim.chineseName()) ||
                    dim.values().size() > byCh.get(dim.chineseName()).values().size()) {
                byCh.put(dim.chineseName(), dim);
            }
        }
        // 第二輪：以 englishName 為 key 再去重（避免「授權書編號」+「授權書編號規則」
        // 同樣對映 authNumber 的重複）；fallback 名稱（field_NNN）不參與英文去重，
        // 優先保留有具名 mapping 的版本。
        LinkedHashMap<String, DimensionInfo> byEn = new LinkedHashMap<>();
        for (DimensionInfo dim : byCh.values()) {
            String en = dim.suggestedEnglishName();
            boolean isFallback = en != null && en.startsWith("field_");
            if (isFallback) {
                // fallback 名稱不去重、保留（後面整體再砍）
                byEn.put(dim.chineseName(), dim);
                continue;
            }
            if (!byEn.containsKey(en) ||
                    dim.values().size() > byEn.get(en).values().size()) {
                byEn.put(en, dim);
            }
        }
        // 第三輪：移除 fallback `field_NNN` 結尾的低品質條目（demo 雜訊降低）
        List<DimensionInfo> result = new ArrayList<>();
        for (DimensionInfo dim : byEn.values()) {
            String en = dim.suggestedEnglishName();
            if (en != null && en.startsWith("field_")) continue;
            result.add(dim);
        }
        return result;
    }

    /**
     * 將解析結果格式化為 prompt 注入段落。
     */
    public String formatForPrompt(ParsedDimensions dims) {
        if (dims.inputs().isEmpty() && dims.outputs().isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n# ⚠ 系統已預解析的維度（你必須完全遵守，不可增減欄位）\n\n");

        if (!dims.inputs().isEmpty()) {
            sb.append("## 輸入欄位（共 ").append(dims.inputs().size()).append(" 個，全部必須使用）\n");
            for (int i = 0; i < dims.inputs().size(); i++) {
                DimensionInfo d = dims.inputs().get(i);
                sb.append(String.format("  %d. name=\"%s\" (中文：%s) | typeRef=%s",
                        i + 1, d.suggestedEnglishName(), d.chineseName(), d.suggestedTypeRef()));
                if (!d.values().isEmpty()) {
                    sb.append(" | 取值=").append(d.values());
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        if (!dims.outputs().isEmpty()) {
            sb.append("## 輸出欄位（共 ").append(dims.outputs().size()).append(" 個，全部必須使用）\n");
            for (int i = 0; i < dims.outputs().size(); i++) {
                DimensionInfo d = dims.outputs().get(i);
                sb.append(String.format("  %d. name=\"%s\" (中文：%s) | typeRef=%s",
                        i + 1, d.suggestedEnglishName(), d.chineseName(), d.suggestedTypeRef()));
                if (!d.values().isEmpty()) {
                    sb.append(" | 取值=").append(d.values());
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        if (dims.estimatedCartesian() > 0) {
            sb.append("## 笛卡爾積\n");
            sb.append("理論規則數 = ");
            StringJoiner joiner = new StringJoiner(" × ");
            for (DimensionInfo d : dims.inputs()) {
                if (!d.values().isEmpty()) {
                    joiner.add(String.valueOf(d.values().size()));
                }
            }
            sb.append(joiner).append(" = ").append(dims.estimatedCartesian()).append(" 條\n");
            sb.append("此數字是情境展開分析用的理論組合數，不一定等於正式規則列數。")
                    .append("若業務語意是獨立檢核、錯誤訊息可同時回傳、或使用 MULTI，")
                    .append("請保留精簡原子規則，不要為了笛卡爾積強制展開正式 rules。\n\n");
        }

        return sb.toString();
    }
}
