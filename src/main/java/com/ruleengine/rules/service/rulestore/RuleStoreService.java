package com.ruleengine.rules.service.rulestore;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.persistence.rulestore.RuleStatus;
import com.ruleengine.rules.persistence.rulestore.RuleVersionEntity;
import com.ruleengine.rules.persistence.rulestore.RuleVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 規則庫服務（P2-S1）：草稿建立、版本鏈、查詢。
 *
 * <p>狀態<b>轉移</b>（送審/核准/生效…）不在這裡 —— 那是 P2-S4 審核狀態機服務的職責，
 * 本類別只管「版本的誕生與讀取」。職責邊界：RuleStore = 資料的家，
 * ReviewWorkflow = 狀態的裁判。</p>
 *
 * <p>
 * <b>version_no 由服務層算（max+1）而非 DB sequence：</b>版本號是「同一 rule_key 內」
 * 的序號，PG sequence 是全域遞增、做不出 per-key 序號。併發同 key 建版會撞
 * {@code uq_rule_version(rule_key, version_no)} 唯一約束 —— 我們<b>依賴</b>這個約束
 * 當最後防線：撞到就拋 {@link ConcurrentVersionException} 讓呼叫端重試，
 * 而不是靜默錯號。規則建版是低頻人工操作，樂觀策略正確；
 * 若未來高併發，再改 SELECT ... FOR UPDATE 悲觀鎖。
 * </p>
 *
 * <p><b>envelope 序列化邊界收在此類</b>：entity 存 String（不綁 Jackson），
 * domain（RuleEnvelope）↔ String 的轉換只發生在這裡。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RuleStoreService {

    private final RuleVersionRepository repository;
    private final ObjectMapper objectMapper;

    /** 併發建版撞唯一約束時拋出；呼叫端（controller/UI）應提示重試。 */
    public static class ConcurrentVersionException extends RuntimeException {
        public ConcurrentVersionException(String ruleKey, Throwable cause) {
            super("規則 " + ruleKey + " 正被同時修改，請重新載入後再試", cause);
        }
    }

    public static class RuleStoreException extends RuntimeException {
        public RuleStoreException(String message) { super(message); }
        public RuleStoreException(String message, Throwable cause) { super(message, cause); }
    }

    // ================================================================
    // 建立
    // ================================================================

    /**
     * 建立草稿版本。首版 version_no=1；已有版本則接鏈
     * （version_no=max+1、previousVersionId 指向該 key 最新版）。
     */
    @Transactional
    public RuleVersionEntity createDraft(String ruleKey, RuleEnvelope envelope, String createdBy) {
        if (ruleKey == null || ruleKey.isBlank()) {
            throw new RuleStoreException("ruleKey 不可為空");
        }
        if (envelope == null || envelope.getRuleType() == null) {
            throw new RuleStoreException("envelope 與其 ruleType 不可為空");
        }

        int nextNo = repository.maxVersionNo(ruleKey) + 1;
        Long previousId = repository.findByRuleKeyOrderByVersionNoDesc(ruleKey).stream()
                .findFirst().map(RuleVersionEntity::getId).orElse(null);

        OffsetDateTime now = OffsetDateTime.now();
        RuleVersionEntity entity = RuleVersionEntity.builder()
                .ruleKey(ruleKey)
                .versionNo(nextNo)
                .ruleType(envelope.getRuleType())
                .envelope(serialize(envelope))
                .status(RuleStatus.DRAFT)
                .previousVersionId(previousId)
                .createdBy(createdBy != null ? createdBy : "system")
                .createdAt(now)
                .updatedAt(now)
                .build();
        try {
            RuleVersionEntity saved = repository.saveAndFlush(entity);
            log.info("RULE-STORE 建立草稿 | key={} | v{} | by={} | prev={}",
                    ruleKey, nextNo, entity.getCreatedBy(), previousId);
            return saved;
        } catch (DataIntegrityViolationException e) {
            // 兩個請求同時算出同一個 max+1 → 唯一約束擋下後到者（樂觀併發的最後防線）
            throw new ConcurrentVersionException(ruleKey, e);
        }
    }

    // ================================================================
    // 查詢
    // ================================================================

    @Transactional(readOnly = true)
    public Optional<RuleVersionEntity> findActive(String ruleKey) {
        return repository.findByRuleKeyAndStatus(ruleKey, RuleStatus.ACTIVE);
    }

    @Transactional(readOnly = true)
    public Optional<RuleVersionEntity> findById(Long id) {
        return repository.findById(id);
    }

    @Transactional(readOnly = true)
    public Optional<RuleVersionEntity> findVersion(String ruleKey, int versionNo) {
        return repository.findByRuleKeyAndVersionNo(ruleKey, versionNo);
    }

    /** 版本鏈（新在前）。 */
    @Transactional(readOnly = true)
    public List<RuleVersionEntity> history(String ruleKey) {
        return repository.findByRuleKeyOrderByVersionNoDesc(ruleKey);
    }

    /** 依狀態列版本（REVIEW = 審核工作台的待審清單，舊的先審）。 */
    @Transactional(readOnly = true)
    public List<RuleVersionEntity> listByStatus(RuleStatus status) {
        return repository.findByStatusOrderByUpdatedAtAsc(status);
    }

    @Transactional(readOnly = true)
    public List<String> allRuleKeys() {
        return repository.distinctRuleKeys();
    }

    // ================================================================
    // envelope 序列化邊界（全專案唯一轉換點）
    // ================================================================

    public String serialize(RuleEnvelope envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new RuleStoreException("envelope 序列化失敗", e);
        }
    }

    public RuleEnvelope deserialize(RuleVersionEntity entity) {
        try {
            return objectMapper.readValue(entity.getEnvelope(), RuleEnvelope.class);
        } catch (Exception e) {
            // 落庫時是我們序列化的 —— 讀不回來代表資料損毀或 schema 不相容升級，屬系統錯誤
            throw new RuleStoreException(
                    "envelope 反序列化失敗 | key=" + entity.getRuleKey() + " v" + entity.getVersionNo(), e);
        }
    }
}
