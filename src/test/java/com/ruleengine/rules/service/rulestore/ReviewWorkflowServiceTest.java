package com.ruleengine.rules.service.rulestore;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.persistence.rulestore.RuleStatus;
import com.ruleengine.rules.persistence.rulestore.RuleVersionEntity;
import com.ruleengine.rules.service.audit.AuditService;
import com.ruleengine.rules.service.audit.InMemoryAuditRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 審核狀態機服務測試（P2-S4，H2 切片）。
 *
 * <p>驗四層：轉移合法性（查表）、身分規則（四眼原則）、
 * activate 原子性讓位、稽核事件落地。</p>
 */
@DataJpaTest
@Import({RuleStoreService.class, ReviewWorkflowService.class,
        AuditService.class, ReviewWorkflowServiceTest.TestBeans.class})
@DisplayName("ReviewWorkflowService - maker-checker 狀態機")
class ReviewWorkflowServiceTest {

    @TestConfiguration
    static class TestBeans {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean InMemoryAuditRepository auditRepository() { return new InMemoryAuditRepository(1000); }
    }

    @Autowired RuleStoreService store;
    @Autowired ReviewWorkflowService workflow;
    @Autowired AuditService auditService;

    private RuleEnvelope envelope() {
        RuleEnvelope e = new RuleEnvelope();
        e.setRuleType("DecisionTable");
        e.setReason("workflow test");
        return e;
    }

    private RuleVersionEntity draft(String key) {
        return store.createDraft(key, envelope(), "alice-maker");
    }

    // ================================================================

    @Nested
    @DisplayName("正向旅程")
    class HappyPath {

        @Test
        @DisplayName("DRAFT→submit→approve→activate→（新版）→supersede 全鏈通")
        void fullJourney() {
            var v1 = draft("wf.journey");

            var afterSubmit = workflow.submit(v1.getId(), "alice-maker");
            assertEquals(RuleStatus.REVIEW, afterSubmit.getStatus());
            assertEquals("alice-maker", afterSubmit.getSubmittedBy());
            assertNotNull(afterSubmit.getSubmittedAt());

            var afterApprove = workflow.approve(v1.getId(), "bob-checker", "規則邏輯正確");
            assertEquals(RuleStatus.APPROVED, afterApprove.getStatus());
            assertEquals("bob-checker", afterApprove.getReviewedBy());
            assertEquals("規則邏輯正確", afterApprove.getReviewComment());

            var active = workflow.activate(v1.getId(), "bob-checker");
            assertEquals(RuleStatus.ACTIVE, active.getStatus());
            assertEquals(v1.getId(), store.findActive("wf.journey").orElseThrow().getId());

            // 第二版走完流程生效 → 第一版自動退役（原子性讓位）
            var v2 = draft("wf.journey");
            workflow.submit(v2.getId(), "alice-maker");
            workflow.approve(v2.getId(), "bob-checker", "v2 ok");
            workflow.activate(v2.getId(), "bob-checker");

            assertEquals(v2.getId(), store.findActive("wf.journey").orElseThrow().getId());
            assertEquals(RuleStatus.RETIRED, store.findById(v1.getId()).orElseThrow().getStatus(),
                    "舊 ACTIVE 應被 supersede 為 RETIRED");
        }

        @Test
        @DisplayName("可逆路徑：withdraw 回草稿、reject 後 revise 重來")
        void reversiblePaths() {
            var v = draft("wf.reversible");
            workflow.submit(v.getId(), "alice-maker");
            workflow.withdraw(v.getId(), "alice-maker");
            assertEquals(RuleStatus.DRAFT, store.findById(v.getId()).orElseThrow().getStatus());

            workflow.submit(v.getId(), "alice-maker");
            workflow.reject(v.getId(), "bob-checker", "欄位定義缺 ENUM 值");
            assertEquals(RuleStatus.REJECTED, store.findById(v.getId()).orElseThrow().getStatus());

            workflow.revise(v.getId(), "alice-maker");
            assertEquals(RuleStatus.DRAFT, store.findById(v.getId()).orElseThrow().getStatus());
        }
    }

    @Nested
    @DisplayName("四眼原則（文獻調查的硬檢查）")
    class FourEyes {

        @Test
        @DisplayName("送審人不可核准自己的件 —— 即使他同時有 CHECKER 角色")
        void selfApproveBlocked() {
            var v = draft("wf.self");
            workflow.submit(v.getId(), "carol-both-roles");
            var ex = assertThrows(ReviewWorkflowService.WorkflowViolationException.class,
                    () -> workflow.approve(v.getId(), "carol-both-roles", "自己審自己"));
            assertTrue(ex.getMessage().contains("四眼原則"));
            // 狀態未變
            assertEquals(RuleStatus.REVIEW, store.findById(v.getId()).orElseThrow().getStatus());
        }

        @Test
        @DisplayName("送審人不可退回自己的件（防「自導自演」的審核紀錄）")
        void selfRejectBlocked() {
            var v = draft("wf.selfreject");
            workflow.submit(v.getId(), "carol");
            assertThrows(ReviewWorkflowService.WorkflowViolationException.class,
                    () -> workflow.reject(v.getId(), "carol", "reason"));
        }

        @Test
        @DisplayName("撤回只限原送審人")
        void withdrawOnlyBySubmitter() {
            var v = draft("wf.withdraw");
            workflow.submit(v.getId(), "alice-maker");
            var ex = assertThrows(ReviewWorkflowService.WorkflowViolationException.class,
                    () -> workflow.withdraw(v.getId(), "someone-else"));
            assertTrue(ex.getMessage().contains("原送審人"));
        }
    }

    @Nested
    @DisplayName("狀態機防線")
    class StateMachineGuards {

        @Test
        @DisplayName("非法轉移被擋且訊息含可行動資訊（現態與允許目標）")
        void illegalTransitionsBlocked() {
            var v = draft("wf.illegal");
            // DRAFT 直接 approve（跳過送審）
            var ex = assertThrows(ReviewWorkflowService.WorkflowViolationException.class,
                    () -> workflow.approve(v.getId(), "bob-checker", "x"));
            assertTrue(ex.getMessage().contains("DRAFT"));
            assertTrue(ex.getMessage().contains("REVIEW"), "訊息應告知允許的目標狀態");

            // DRAFT 直接 activate
            assertThrows(ReviewWorkflowService.WorkflowViolationException.class,
                    () -> workflow.activate(v.getId(), "bob-checker"));
        }

        @Test
        @DisplayName("退回必須附意見（maker 要據此修改）")
        void rejectRequiresComment() {
            var v = draft("wf.comment");
            workflow.submit(v.getId(), "alice-maker");
            assertThrows(ReviewWorkflowService.WorkflowViolationException.class,
                    () -> workflow.reject(v.getId(), "bob-checker", "  "));
        }

        @Test
        @DisplayName("不存在的版本 → VersionNotFoundException")
        void missingVersion() {
            assertThrows(ReviewWorkflowService.VersionNotFoundException.class,
                    () -> workflow.submit(999_999L, "alice-maker"));
        }
    }

    @Nested
    @DisplayName("稽核與佇列")
    class AuditAndQueue {

        @Test
        @DisplayName("每個轉移都留稽核事件（操作、歸因、from→to）")
        void everyTransitionAudited() {
            // 錯誤#8 教訓：in-memory 稽核 repo 是共享單例、不隨 @DataJpaTest 交易回滾 ——
            // 其他測試的事件會累積，斷言必須用「增量」而非絕對數
            int submitBefore = auditService.getLogsByOperation("SUBMIT", 1000).size();
            int approveBefore = auditService.getLogsByOperation("APPROVE", 1000).size();
            int activateBefore = auditService.getLogsByOperation("ACTIVATE", 1000).size();

            var v = draft("wf.audit");
            workflow.submit(v.getId(), "alice-maker");
            workflow.approve(v.getId(), "bob-checker", "ok");
            workflow.activate(v.getId(), "bob-checker");

            assertEquals(submitBefore + 1, auditService.getLogsByOperation("SUBMIT", 1000).size());
            assertEquals(approveBefore + 1, auditService.getLogsByOperation("APPROVE", 1000).size());
            assertEquals(activateBefore + 1, auditService.getLogsByOperation("ACTIVATE", 1000).size());
            // 最新一筆（recent 序在前）是本測試的
            var approve = auditService.getLogsByOperation("APPROVE", 1000).get(0);
            assertEquals("bob-checker", approve.getUserId());
            assertTrue(approve.getReason().contains("REVIEW→APPROVED"));
        }

        @Test
        @DisplayName("待審佇列只含 REVIEW 狀態")
        void queueOnlyReview() {
            var a = draft("wf.q1");
            var b = draft("wf.q2");
            workflow.submit(a.getId(), "alice-maker");
            // b 停在 DRAFT
            var queue = workflow.reviewQueue();
            assertEquals(1, queue.size());
            assertEquals(a.getId(), queue.get(0).getId());
        }
    }
}
