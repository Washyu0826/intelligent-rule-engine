package com.ruleengine.rules.service.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Condition;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Result;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.Rule;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.RuleRow;
import com.ruleengine.rules.persistence.execution.DecisionTraceRepository;
import com.ruleengine.rules.persistence.rulestore.RuleStatus;
import com.ruleengine.rules.persistence.rulestore.RuleVersionEntity;
import com.ruleengine.rules.persistence.rulestore.RuleVersionRepository;
import com.ruleengine.rules.service.RuleLookupService;
import com.ruleengine.rules.service.rulestore.RuleStoreService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 引擎執行服務測試（P2-S3，H2 切片）：執行落庫 → 回放閉環。
 *
 * <p>DB 級不可變（trigger）由真 PG 測試驗證（PgDecisionTraceImmutabilityTest）。</p>
 */
@DataJpaTest
@Import({RuleStoreService.class, RuleLookupService.class, RuleExecutionEngine.class,
        EngineExecutionService.class, EngineExecutionServiceTest.TestBeans.class})
@DisplayName("EngineExecutionService - 執行落庫與回放")
class EngineExecutionServiceTest {

    @TestConfiguration
    static class TestBeans {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    }

    @Autowired RuleStoreService store;
    @Autowired EngineExecutionService service;
    @Autowired DecisionTraceRepository traceRepository;
    @Autowired RuleVersionRepository versionRepository;

    // ── fixture ──

    private RuleEnvelope creditEnvelope() {
        RuleEnvelope e = new RuleEnvelope();
        e.setRuleType("DecisionTable");
        Rule rule = new Rule();
        rule.setHitPolicy("FIRST");
        RuleRow approve = new RuleRow();
        approve.setRuleId("R01"); approve.setPriority(1);
        Condition c1 = new Condition();
        c1.setField("score"); c1.setOperator("greaterThanOrEqual"); c1.setValue(700);
        Result r1 = new Result(); r1.setField("decision"); r1.setValue("approve");
        approve.setConditions(List.of(c1)); approve.setResults(List.of(r1));
        RuleRow reject = new RuleRow();
        reject.setRuleId("R02"); reject.setPriority(2);
        Condition c2 = new Condition();
        c2.setField("score"); c2.setOperator("lessThan"); c2.setValue(700);
        Result r2 = new Result(); r2.setField("decision"); r2.setValue("reject");
        reject.setConditions(List.of(c2)); reject.setResults(List.of(r2));
        rule.setRules(List.of(approve, reject));
        e.setRule(rule);
        return e;
    }

    /** 建一版並直接轉 ACTIVE（正規審核流是 P2-S4 的事）。 */
    private RuleVersionEntity activeVersion(String key) {
        RuleVersionEntity v = store.createDraft(key, creditEnvelope(), "maker");
        v.setStatus(RuleStatus.ACTIVE);
        return versionRepository.saveAndFlush(v);
    }

    // ================================================================

    @Nested
    @DisplayName("執行落庫")
    class ExecutePersists {

        @Test
        @DisplayName("執行成功且回放三要素全部落庫（含 executedBy 歸因）")
        void persistsReplayTriad() {
            activeVersion("credit.exec");
            var outcome = service.executeActive("credit.exec",
                    Map.of("score", 750), TraceLevel.FULL, "checker");

            assertTrue(outcome.result().isMatched());
            assertEquals("approve", outcome.result().getOutputs().get("decision"));

            var saved = traceRepository.findById(outcome.traceId()).orElseThrow();
            assertEquals(outcome.ruleVersionId(), saved.getRuleVersionId(), "要素①規則版本");
            assertTrue(saved.getInputSnapshot().contains("750"), "要素②輸入快照");
            assertEquals(RuleExecutionEngine.ENGINE_VERSION, saved.getEngineVersion(), "要素③引擎版本");
            assertEquals("checker", saved.getExecutedBy(), "JWT 身分歸因");
            assertEquals("credit.exec", saved.getRuleKey());
            assertTrue(saved.isMatched());
            assertNotNull(saved.getTraceDetail(), "FULL 級應有明細");
        }

        @Test
        @DisplayName("NONE 級也留痕：三要素照存，只有明細為空")
        void noneLevelStillPersistsTriad() {
            activeVersion("credit.none");
            var outcome = service.executeActive("credit.none",
                    Map.of("score", 500), TraceLevel.NONE, null);
            var saved = traceRepository.findById(outcome.traceId()).orElseThrow();
            assertNotNull(saved.getInputSnapshot());
            assertNull(saved.getTraceDetail(), "NONE 級無明細");
            assertEquals("anonymous", saved.getExecutedBy(), "無身分時記 anonymous");
        }

        @Test
        @DisplayName("無 ACTIVE 版本 → 可行動的錯誤（提示先走審核流程）")
        void noActiveVersion() {
            store.createDraft("credit.draft-only", creditEnvelope(), "maker"); // 停在 DRAFT
            var ex = assertThrows(EngineExecutionService.EngineServiceException.class,
                    () -> service.executeActive("credit.draft-only", Map.of("score", 700),
                            TraceLevel.NONE, null));
            assertTrue(ex.getMessage().contains("審核"));
        }
    }

    @Nested
    @DisplayName("回放閉環")
    class Replay {

        @Test
        @DisplayName("同版本同輸入回放 → consistent=true、零差異")
        void replayConsistent() {
            activeVersion("credit.replay");
            var outcome = service.executeActive("credit.replay",
                    Map.of("score", 750), TraceLevel.SUMMARY, "maker");

            var report = service.replay(outcome.traceId());
            assertTrue(report.consistent(), "差異：" + report.differences());
            assertTrue(report.differences().isEmpty());
            assertFalse(report.nonDeterministicWarning());
            assertEquals(report.originalEngineVersion(), report.currentEngineVersion());
            // 回放一律 FULL —— 逐步明細俱在
            assertNotNull(report.replayed().getTrace());
            assertEquals(TraceLevel.FULL, report.replayed().getTrace().getLevel());
        }

        @Test
        @DisplayName("關鍵語意：版本已 RETIRED 仍可回放（append-only 版本鏈的回報）")
        void replayWorksOnRetiredVersion() {
            var v = activeVersion("credit.retired-replay");
            var outcome = service.executeActive("credit.retired-replay",
                    Map.of("score", 650), TraceLevel.SUMMARY, "maker");

            v.setStatus(RuleStatus.RETIRED);   // 之後規則退役了
            versionRepository.saveAndFlush(v);

            var report = service.replay(outcome.traceId());
            assertTrue(report.consistent(), "退役不影響歷史決策的可回放性");
            assertEquals("reject", report.replayedOutputs().get("decision"));
        }

        @Test
        @DisplayName("規則含 $today → nonDeterministicWarning 如實標記")
        void nonDeterministicFlagged() {
            RuleEnvelope e = creditEnvelope();
            // 加一條用 $today 的規則
            Condition dateCond = new Condition();
            dateCond.setField("applyDate"); dateCond.setOperator("lessThanOrEqual");
            dateCond.setValueRef("$today");
            RuleRow dated = new RuleRow();
            dated.setRuleId("R00"); dated.setPriority(0);
            dated.setConditions(List.of(dateCond));
            Result r = new Result(); r.setField("decision"); r.setValue("dated");
            dated.setResults(List.of(r));
            var rules = new java.util.ArrayList<>(e.getRule().getRules());
            rules.add(0, dated);
            e.getRule().setRules(rules);

            var v = store.createDraft("credit.dated", e, "maker");
            v.setStatus(RuleStatus.ACTIVE);
            versionRepository.saveAndFlush(v);

            var outcome = service.executeVersion(v, Map.of("score", 750, "applyDate", "2026-01-01"),
                    TraceLevel.SUMMARY, "maker");
            var report = service.replay(outcome.traceId());
            assertTrue(report.nonDeterministicWarning(), "$today 規則必須標記非決定性");
        }

        @Test
        @DisplayName("trace 不存在 → 404 級錯誤訊息")
        void missingTrace() {
            var ex = assertThrows(EngineExecutionService.EngineServiceException.class,
                    () -> service.replay(999_999L));
            assertTrue(ex.getMessage().contains("不存在"));
        }
    }

    @Nested
    @DisplayName("trace 查詢")
    class TraceQueries {

        @Test
        @DisplayName("recentTraces 依 ruleKey 過濾、新的在前、limit 有上限")
        void recentFiltered() {
            activeVersion("credit.qa");
            activeVersion("credit.qb");
            service.executeActive("credit.qa", Map.of("score", 700), TraceLevel.NONE, null);
            service.executeActive("credit.qb", Map.of("score", 700), TraceLevel.NONE, null);
            service.executeActive("credit.qa", Map.of("score", 500), TraceLevel.NONE, null);

            var qa = service.recentTraces("credit.qa", 10);
            assertEquals(2, qa.size());
            // 錯誤#4 教訓：score=500 會命中 R02（<700→reject），matched 仍是 true —— 
            // 「最新在前」用輸出內容區分（最新那筆是 reject，較早那筆是 approve）
            assertTrue(qa.get(0).getOutputs().contains("reject"), "最新一筆（score=500→reject）在前");
            assertTrue(qa.get(1).getOutputs().contains("approve"), "較早一筆（score=700→approve）在後");

            var all = service.recentTraces(null, 10);
            assertEquals(3, all.size());

            assertEquals(1, service.recentTraces(null, -5).size(), "limit 下限鉗到 1");
        }
    }
}
