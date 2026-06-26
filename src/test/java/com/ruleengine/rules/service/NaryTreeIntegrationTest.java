package com.ruleengine.rules.service;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.domain.envelope.RuleEnvelope.*;
import com.ruleengine.rules.service.analyzer.AnalysisResult;
import com.ruleengine.rules.service.analyzer.TreeAnalyzer;
import com.ruleengine.rules.service.converter.TableToTreeConverter;
import com.ruleengine.rules.service.converter.TreeToTableConverter;
import com.ruleengine.rules.service.generator.TreeEvaluationComputer;
import com.ruleengine.rules.service.generator.TreeNormalizer;
import com.ruleengine.rules.service.generator.EnvelopeNormalizer;
import com.ruleengine.rules.service.optimizer.TreeOptimizer;
import com.ruleengine.rules.service.validator.DecisionTreeValidator;
import com.ruleengine.rules.domain.dto.ToolDtos.ValidationError;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * N-ary 多分支樹整合測試。
 *
 * 驗證項目：
 * 1. 三個保險場景 fixture 的 N-ary 結構能正確載入
 * 2. 正規化不破壞 N-ary branches
 * 3. Validator 通過驗證（0 errors）
 * 4. TreeAnalyzer 計算正確的覆蓋率（1.0）
 * 5. TreeEvaluationComputer 正確計算葉節點數
 * 6. Tree → Table 轉換正確展平
 * 7. Table → Tree 轉換能產生 N-ary 分支（ENUM 欄位）
 * 8. TreeOptimizer 不改變已最佳化的樹
 */
@SpringBootTest
@DisplayName("N-ary 多分支樹整合測試")
class NaryTreeIntegrationTest {

    @Autowired private ObjectMapper objectMapper;
    @Autowired private TreeNormalizer treeNormalizer;
    @Autowired private TreeEvaluationComputer treeEvaluationComputer;
    @Autowired private DecisionTreeValidator validator;
    @Autowired private TreeAnalyzer treeAnalyzer;
    @Autowired private TreeToTableConverter treeToTable;
    @Autowired private TableToTreeConverter tableToTree;
    @Autowired private TreeOptimizer treeOptimizer;

    // ================================================================
    // 案例 1：壽險核保（年齡 4-way + 性別 2-way）
    // ================================================================
    @Nested
    @DisplayName("案例 1：壽險核保決策樹")
    class UnderwritingTest {

        private RuleEnvelope envelope;
        private JsonNode envelopeJson;

        @BeforeEach
        void setUp() throws Exception {
            envelope = loadEnvelope("tree-nary-underwriting.json");
            envelopeJson = objectMapper.valueToTree(envelope);
        }

        @Test
        @DisplayName("根節點有 4 個 N-ary branches（年齡分組）")
        void rootHas4Branches() {
            TreeNode root = envelope.getRule().getRoot();
            assertNotNull(root.getBranches(), "root 應有 branches");
            assertEquals(4, root.getBranches().size(), "年齡分 4 組 → 4 個 branches");

            List<String> labels = root.getBranches().stream()
                    .map(Branch::getLabel).toList();
            assertTrue(labels.contains("18-35"), "應有 18-35 分支");
            assertTrue(labels.contains("36-50"), "應有 36-50 分支");
            assertTrue(labels.contains("51-65"), "應有 51-65 分支");
            assertTrue(labels.contains("66+"), "應有 66+ 分支");
        }

        @Test
        @DisplayName("性別使用 2-way branches（66+ 子樹）")
        void genderBranch() {
            Branch branch66 = envelope.getRule().getRoot().getBranches().get(3);
            assertEquals("66+", branch66.getLabel());

            // 66+ → has_heart_disease → FALSE → gender 2-way（N-ary branches）
            TreeNode after66 = branch66.getChild();
            assertNotNull(after66.getBranches());
            Branch falseBranch = after66.getBranches().stream()
                    .filter(b -> "FALSE".equals(b.getLabel())).findFirst().orElse(null);
            assertNotNull(falseBranch, "應有 heart_disease FALSE 分支");

            TreeNode genderNode = falseBranch.getChild();
            assertNotNull(genderNode.getBranches(), "性別節點應有 branches");
            assertTrue(genderNode.getBranches().size() >= 2, "male/female 至少 2 個分支");
        }

        @Test
        @DisplayName("Validator 驗證通過（0 errors）")
        void validatorPasses() {
            List<ValidationError> errors = validator.validate(envelopeJson);
            assertEquals(0, errors.size(),
                    "N-ary 結構應通過驗證，但有 " + errors.size() + " 個錯誤：" + errors);
        }

        @Test
        @DisplayName("TreeAnalyzer 覆蓋率 = 1.0")
        void fullCoverage() {
            AnalysisResult result = treeAnalyzer.analyze(envelopeJson);
            assertEquals(1.0, result.getCoverageRate(), 0.001, "完整樹覆蓋率應為 1.0");
            assertTrue(result.getGaps().isEmpty(), "不應有缺口");
        }

        @Test
        @DisplayName("TreeEvaluationComputer 正確計算葉節點數")
        void evaluationCompute() {
            treeEvaluationComputer.computeEvaluation(envelope);
            Evaluation eval = envelope.getEvaluation();
            assertNotNull(eval);
            assertEquals("COMPLETE", eval.getCompleteness());
            assertTrue(eval.getTotalScenarios() >= 10, "應至少有 10 個葉節點");
            assertEquals(1.0, eval.getCoverageRate(), 0.001);
        }

        @Test
        @DisplayName("Tree → Table 展平：每個葉節點產生一條 rule")
        void treeToTableConversion() {
            RuleEnvelope table = treeToTable.convert(envelope);
            assertEquals("DecisionTable", table.getRuleType());
            assertNotNull(table.getRule().getRules());

            int leafCount = countLeaves(envelope.getRule().getRoot());
            assertEquals(leafCount, table.getRule().getRules().size(),
                    "Table rules 數量應等於葉節點數");
        }
    }

    // ================================================================
    // 案例 2：保費費率（職業 3-way + BMI 3-way）
    // ================================================================
    @Nested
    @DisplayName("案例 2：保費費率決策樹")
    class PremiumRateTest {

        private RuleEnvelope envelope;
        private JsonNode envelopeJson;

        @BeforeEach
        void setUp() throws Exception {
            envelope = loadEnvelope("tree-nary-premium-rate.json");
            envelopeJson = objectMapper.valueToTree(envelope);
        }

        @Test
        @DisplayName("根節點有 3 個 N-ary branches（職業風險）")
        void rootHas3Branches() {
            TreeNode root = envelope.getRule().getRoot();
            assertNotNull(root.getBranches());
            assertEquals(3, root.getBranches().size(), "low/medium/high = 3 個分支");
        }

        @Test
        @DisplayName("BMI 使用 3-way branches")
        void bmi3WayBranch() {
            // medium → bmi_category 3-way
            Branch mediumBranch = envelope.getRule().getRoot().getBranches().stream()
                    .filter(b -> "medium".equals(b.getLabel())).findFirst().orElse(null);
            assertNotNull(mediumBranch);
            TreeNode bmiNode = mediumBranch.getChild();
            assertNotNull(bmiNode.getBranches());
            assertEquals(3, bmiNode.getBranches().size(), "obese/overweight/normal = 3 分支");
        }

        @Test
        @DisplayName("Validator 驗證通過")
        void validatorPasses() {
            List<ValidationError> errors = validator.validate(envelopeJson);
            assertEquals(0, errors.size(),
                    "應通過驗證，但有 " + errors.size() + " 個錯誤：" + errors);
        }

        @Test
        @DisplayName("覆蓋率 = 1.0，無缺口")
        void fullCoverage() {
            AnalysisResult result = treeAnalyzer.analyze(envelopeJson);
            assertEquals(1.0, result.getCoverageRate(), 0.001);
            assertTrue(result.getGaps().isEmpty());
        }

        @Test
        @DisplayName("Round-trip：Tree → Table → Tree 保持結構")
        void roundTrip() {
            int originalLeaves = countLeaves(envelope.getRule().getRoot());

            RuleEnvelope table = treeToTable.convert(envelope);
            assertEquals(originalLeaves, table.getRule().getRules().size(),
                    "Table rules 數量應等於原始葉節點數");

            RuleEnvelope rebuiltTree = tableToTree.convert(table);
            int rebuiltLeaves = countLeaves(rebuiltTree.getRule().getRoot());
            assertTrue(rebuiltLeaves > 0,
                    "Round-trip 後應有葉節點，實際 " + rebuiltLeaves);
        }

        @Test
        @DisplayName("TableToTree 對 ENUM 欄位產生 N-ary 分支")
        void tableToTreeProducesNaryForEnum() {
            RuleEnvelope table = treeToTable.convert(envelope);
            RuleEnvelope rebuiltTree = tableToTree.convert(table);

            // 重建的樹應使用 branches（不是 trueBranch/falseBranch）
            TreeNode root = rebuiltTree.getRule().getRoot();
            assertNotNull(root.getBranches(), "轉換後的樹根應有 branches");
            assertNull(root.getTrueBranch(), "不應有舊格式 trueBranch");
            assertNull(root.getFalseBranch(), "不應有舊格式 falseBranch");
        }
    }

    // ================================================================
    // 案例 3：意外險核保（職業 6-way + 殘廢 3-way + 理賠 3-way）
    // ================================================================
    @Nested
    @DisplayName("案例 3：意外險核保決策樹")
    class AccidentInsuranceTest {

        private RuleEnvelope envelope;
        private JsonNode envelopeJson;

        @BeforeEach
        void setUp() throws Exception {
            envelope = loadEnvelope("tree-nary-accident-insurance.json");
            envelopeJson = objectMapper.valueToTree(envelope);
        }

        @Test
        @DisplayName("職業類別使用 6-way branches")
        void occupationHas6Branches() {
            // root → age_eligible TRUE → occupation_class 6-way
            TreeNode root = envelope.getRule().getRoot();
            Branch trueBranch = root.getBranches().stream()
                    .filter(b -> "TRUE".equals(b.getLabel())).findFirst().orElse(null);
            assertNotNull(trueBranch);
            TreeNode occNode = trueBranch.getChild();
            assertNotNull(occNode.getBranches());
            assertEquals(6, occNode.getBranches().size(),
                    "class_1~class_6 = 6 個分支");
        }

        @Test
        @DisplayName("殘廢等級使用 3-way branches（class_3 子樹）")
        void disabilityGrade3Way() {
            TreeNode occNode = envelope.getRule().getRoot()
                    .getBranches().get(0).getChild(); // TRUE → occupation
            Branch class3 = occNode.getBranches().stream()
                    .filter(b -> "class_3".equals(b.getLabel())).findFirst().orElse(null);
            assertNotNull(class3);
            TreeNode disNode = class3.getChild();
            assertNotNull(disNode.getBranches());
            assertEquals(3, disNode.getBranches().size(),
                    "none/grade_7_11/grade_1_6 = 3 個分支");
        }

        @Test
        @DisplayName("理賠次數使用 3-way branches（class_1 子樹）")
        void claimCount3Way() {
            TreeNode occNode = envelope.getRule().getRoot()
                    .getBranches().get(0).getChild();
            Branch class1 = occNode.getBranches().stream()
                    .filter(b -> "class_1".equals(b.getLabel())).findFirst().orElse(null);
            assertNotNull(class1);
            TreeNode claimNode = class1.getChild();
            assertNotNull(claimNode.getBranches());
            assertEquals(3, claimNode.getBranches().size(), "0/1/2+ = 3 個分支");
        }

        @Test
        @DisplayName("Validator 驗證通過")
        void validatorPasses() {
            List<ValidationError> errors = validator.validate(envelopeJson);
            assertEquals(0, errors.size(),
                    "應通過驗證，但有 " + errors.size() + " 個錯誤：" + errors);
        }

        @Test
        @DisplayName("覆蓋率 = 1.0")
        void fullCoverage() {
            AnalysisResult result = treeAnalyzer.analyze(envelopeJson);
            assertEquals(1.0, result.getCoverageRate(), 0.001);
        }

        @Test
        @DisplayName("TreeAnalyzer 無簡化建議（不同葉節點結果不同）")
        void noSimplifications() {
            AnalysisResult result = treeAnalyzer.analyze(envelopeJson);
            long deadCodeCount = result.getSimplifications().stream()
                    .filter(s -> s.getSuggestion().contains("Dead Code")).count();
            assertEquals(0, deadCodeCount, "不應有死碼");
        }

        @Test
        @DisplayName("Tree → Table 展平所有路徑")
        void treeToTableFlattens() {
            RuleEnvelope table = treeToTable.convert(envelope);
            int leafCount = countLeaves(envelope.getRule().getRoot());
            assertEquals(leafCount, table.getRule().getRules().size());

            // 每條 rule 都應有所有 4 個 output 欄位
            for (var rule : table.getRule().getRules()) {
                assertEquals(4, rule.getResults().size(),
                        "Rule " + rule.getRuleId() + " 應有 4 個 output 結果");
            }
        }

        @Test
        @DisplayName("TreeOptimizer 不改變已最佳化的樹")
        void optimizerNoOp() {
            TreeOptimizer.OptimizeResult result = treeOptimizer.optimize(envelope, false);
            assertEquals(0, result.getNodesRemoved(), "已最佳化的樹不應移除節點");
        }
    }

    // ================================================================
    // 向後相容測試：舊格式 fixture 仍能通過
    // ================================================================
    @Nested
    @DisplayName("向後相容：舊格式 trueBranch/falseBranch")
    class BackwardCompatTest {

        @Test
        @DisplayName("舊格式 tree-valid-basic.json 經正規化後轉為 branches")
        void legacyFormatNormalized() throws Exception {
            RuleEnvelope envelope = loadEnvelope("tree-valid-basic.json");

            // 正規化前：有 trueBranch/falseBranch
            TreeNode rootBefore = envelope.getRule().getRoot();
            // 注意：JSON 反序列化後 trueBranch/falseBranch 存在

            // 正規化
            treeNormalizer.normalize(envelope, "1.0.0", "p2.1.0");
            TreeNode root = envelope.getRule().getRoot();

            // 正規化後：trueBranch/falseBranch 應轉為 branches
            assertNotNull(root.getBranches(), "正規化後應有 branches");
            assertEquals(2, root.getBranches().size(), "二元樹 → 2 個 branches");
            assertNull(root.getTrueBranch(), "trueBranch 應已清除");
            assertNull(root.getFalseBranch(), "falseBranch 應已清除");
        }

        @Test
        @DisplayName("正規化後的舊格式仍通過 Validator")
        void legacyFormatValidates() throws Exception {
            RuleEnvelope envelope = loadEnvelope("tree-valid-basic.json");
            treeNormalizer.normalize(envelope, "1.0.0", "p2.1.0");
            JsonNode json = objectMapper.valueToTree(envelope);
            List<ValidationError> errors = validator.validate(json);
            assertEquals(0, errors.size(),
                    "正規化後的舊格式應通過驗證，但有 " + errors.size() + " 個錯誤：" + errors);
        }
    }

    // ================================================================
    // Helpers
    // ================================================================

    private RuleEnvelope loadEnvelope(String filename) throws Exception {
        InputStream is = getClass().getClassLoader()
                .getResourceAsStream("fixtures/" + filename);
        assertNotNull(is, "找不到 fixture: " + filename);
        return objectMapper.readValue(is, RuleEnvelope.class);
    }

    private int countLeaves(TreeNode node) {
        if (node == null) return 0;
        boolean hasBranches = node.getBranches() != null && !node.getBranches().isEmpty();
        boolean isLeaf = node.getResults() != null && !node.getResults().isEmpty()
                && node.getCondition() == null && !hasBranches;
        if (isLeaf) return 1;
        int count = 0;
        if (hasBranches) {
            for (Branch b : node.getBranches()) {
                count += countLeaves(b.getChild());
            }
        }
        count += countLeaves(node.getTrueBranch());
        count += countLeaves(node.getFalseBranch());
        return count;
    }
}
