package com.ruleengine.rules.service.adapter.group;

import com.ruleengine.rules.domain.glossary.GlossaryEntry;
import com.ruleengine.rules.service.glossary.GlossaryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Resolves an English camelCase field name (e.g. {@code insuredIdFormatIsRocId})
 * into a Chinese display label.
 *
 * <p><b>Resolution priority (curated → fuzzy):</b></p>
 * <ol>
 *   <li><b>Whole-name SHIM</b> — a hand-curated dictionary covering every
 *       field name used in sample-A / sample-B / 12 built-in BU examples.
 *       This is the primary path; it produces clean labels like
 *       {@code 被保人 ID 為 ROC 格式} for {@code insuredIdFormatIsRocId}.</li>
 *   <li><b>Whole-name glossary direct hit</b> — {@code glossaryService.search(name, null)}
 *       FIRST entry's {@code zh_TW}. Used for rare LLM-generated long names that
 *       happen to match a glossary entry's {@code en} substring.</li>
 *   <li><b>Token-by-token SHIM only</b> — split camelCase, look each up in SHIM.
 *       Tokens never miss-substringed via glossary fuzzy here because
 *       {@code glossaryService.search} matches "any en containing the query",
 *       which produces gibberish for 2-3-char tokens like {@code Id} / {@code Is}
 *       (they accidentally match any zh_TW belonging to an entry whose en
 *       happens to contain "id" / "is" e.g. {@code Disability}).</li>
 *   <li><b>Fallback</b> — original English name with {@code （待補）} suffix
 *       so the UI clearly surfaces missing labels.</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GroupLabelResolver {

    private static final Pattern CAMEL_BOUNDARY = Pattern.compile("(?<!^)(?=[A-Z])");

    /**
     * Curated Chinese labels for every field this demo's fixtures touch.
     * Keys are lower-cased; lookup is case-insensitive.
     *
     * <p>Categories:</p>
     * <ul>
     *   <li><b>sample-A</b> — ID / 國籍別 cross-check (case 1)</li>
     *   <li><b>sample-B</b> — 通路 / 授權書 / 繳費 / 投保始期 (case 2)</li>
     *   <li><b>common</b> — output fields and shared domain tokens</li>
     * </ul>
     */
    private static final Map<String, String> SHIM = new HashMap<>();
    static {
        // ─── Sample-A whole-name (case 1: ID / 國籍別 cross-check) ───
        SHIM.put("insuredidformatisrocid", "被保人 ID 為 ROC 格式");
        SHIM.put("insurednationality", "被保人國籍");
        SHIM.put("policyholderidformatisrocid", "要保人 ID 為 ROC 格式");
        SHIM.put("policyholdernationality", "要保人國籍");

        // ─── Sample-B whole-name (case 2: 通路 / 授權書 / 繳費 / 投保始期) ───
        SHIM.put("channel", "通路");
        SHIM.put("receivingchannel", "受理通路");
        SHIM.put("authnumber", "授權書編號");
        SHIM.put("acceptancenumber", "受理編號");
        SHIM.put("authmatchesacceptanceformat", "授權書符合有效授權書編號格式");
        SHIM.put("authmatchesacceptancenumber", "授權書編號等於受理編號");
        SHIM.put("auth", "授權書");
        SHIM.put("matches", "符合");
        SHIM.put("acceptance", "受理");
        SHIM.put("newcontractpaymentchannel", "新契約繳費管道");
        SHIM.put("renewalpaymentchannel", "續期繳費管道");
        SHIM.put("paymentchannel", "繳費管道");
        SHIM.put("policystartdate", "投保始期");
        SHIM.put("policystartdateinsured", "投保始期（被保人）");
        SHIM.put("policystartdateproposer", "投保始期（要保人）");
        SHIM.put("insuredbirthday", "被保人生日");
        SHIM.put("proposerbirthday", "要保人生日");

        // ─── Common output fields ───
        SHIM.put("errormessage", "錯誤訊息");
        SHIM.put("decision", "核保決議");
        SHIM.put("applicable", "適用");
        SHIM.put("premiumrate", "保費係數");
        SHIM.put("remark", "備註說明");
        SHIM.put("premium", "保費");
        SHIM.put("status", "狀態");

        // ─── Built-in 12 BU examples — health / insurance / scoring tokens ───
        SHIM.put("age", "年齡");
        SHIM.put("insuredage", "投保年齡");
        SHIM.put("gender", "性別");
        SHIM.put("bmi", "BMI");
        SHIM.put("bmirange", "BMI 區間");
        SHIM.put("hashypertension", "是否有高血壓");
        SHIM.put("hasdiabetes", "是否有糖尿病");
        SHIM.put("hasheartdisease", "是否有心臟病");
        SHIM.put("smokingstatus", "吸菸狀態");
        SHIM.put("occupationrisklevel", "職業風險等級");
        SHIM.put("claimcount", "理賠次數");
        SHIM.put("creditscore", "信用評分");

        // ─── Generic short tokens (for camelCase split fallback) ───
        SHIM.put("insured", "被保人");
        SHIM.put("policyholder", "要保人");
        SHIM.put("proposer", "要保人");
        SHIM.put("beneficiary", "受益人");
        SHIM.put("id", "ID");
        SHIM.put("rocid", "ROC 身分證");
        SHIM.put("format", "格式");
        SHIM.put("number", "編號");
        SHIM.put("nationality", "國籍別");
        SHIM.put("birthday", "生日");
        SHIM.put("date", "日期");
        SHIM.put("type", "類型");
        SHIM.put("category", "類別");
        SHIM.put("amount", "金額");
        SHIM.put("score", "分數");
        SHIM.put("level", "等級");
        SHIM.put("hypertension", "高血壓");
        SHIM.put("diabetes", "糖尿病");
        SHIM.put("heartdisease", "心臟病");
        SHIM.put("smoking", "吸菸");
        SHIM.put("occupation", "職業");
        SHIM.put("risk", "風險");
        SHIM.put("count", "次數");
        SHIM.put("credit", "信用");
    }

    /** Minimum token length to attempt glossary fuzzy match (avoid Id / Is matching). */
    private static final int MIN_GLOSSARY_FUZZY_LEN = 4;

    private final GlossaryService glossaryService;

    /** Resolve a field name to a Chinese display label. */
    public String resolveFieldChinese(String fieldName) {
        if (fieldName == null || fieldName.isBlank()) return "(空)";

        // 1. Whole-name SHIM (curated — primary path)
        String lower = fieldName.toLowerCase();
        if (SHIM.containsKey(lower)) {
            return SHIM.get(lower);
        }

        // 2. Whole-name glossary direct hit (only for names long enough to avoid garbage)
        if (fieldName.length() >= MIN_GLOSSARY_FUZZY_LEN) {
            Optional<GlossaryEntry> direct = firstNonBlankZh(glossaryService.search(fieldName, null));
            if (direct.isPresent()) {
                return direct.get().getZh_TW();
            }
        }

        // 3. Token-by-token SHIM only (no glossary fuzzy on tokens)
        String[] tokens = CAMEL_BOUNDARY.split(fieldName);
        List<String> parts = new ArrayList<>();
        boolean anyHit = false;
        for (String token : tokens) {
            String t = token.trim();
            if (t.isEmpty()) continue;
            String resolved = SHIM.get(t.toLowerCase());
            if (resolved != null) {
                parts.add(resolved);
                anyHit = true;
            } else {
                parts.add(t);
            }
        }

        if (parts.isEmpty()) return fieldName;
        String joined = String.join(" ", parts);
        return anyHit ? joined : (fieldName + "（待補）");
    }

    /** Resolve a field name to a Chinese definition (best-effort, glossary direct only). */
    public String resolveFieldDescription(String fieldName) {
        if (fieldName == null || fieldName.isBlank()) return "";
        if (fieldName.length() < MIN_GLOSSARY_FUZZY_LEN) return "";

        Optional<GlossaryEntry> hit = glossaryService.search(fieldName, null).stream()
                .filter(e -> e.getDefinition() != null && !e.getDefinition().isBlank())
                .findFirst();
        return hit.map(GlossaryEntry::getDefinition).orElse("");
    }

    /**
     * True when the field name was resolved via SHIM or glossary
     * (used to decide whether to emit a glossary-miss warning).
     */
    public boolean hasResolution(String fieldName) {
        if (fieldName == null || fieldName.isBlank()) return false;
        if (SHIM.containsKey(fieldName.toLowerCase())) return true;
        if (fieldName.length() >= MIN_GLOSSARY_FUZZY_LEN
                && !glossaryService.search(fieldName, null).isEmpty()) {
            return true;
        }
        // any camelCase token resolved via SHIM
        for (String token : CAMEL_BOUNDARY.split(fieldName)) {
            if (SHIM.containsKey(token.trim().toLowerCase())) return true;
        }
        return false;
    }

    private static Optional<GlossaryEntry> firstNonBlankZh(List<GlossaryEntry> candidates) {
        if (candidates == null) return Optional.empty();
        return candidates.stream()
                .filter(e -> e.getZh_TW() != null && !e.getZh_TW().isBlank())
                .findFirst();
    }
}
