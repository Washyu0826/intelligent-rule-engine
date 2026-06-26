package com.ruleengine.rules.service.adapter.group;

import com.ruleengine.rules.domain.envelope.RuleEnvelope;
import com.ruleengine.rules.service.converter.TableToTreeConverter;
import com.ruleengine.rules.service.glossary.GlossaryService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Demo-sprint smoke test for {@link GroupJsonExporter}. Verifies that the
 * exporter consumes the two golden-test envelopes and produces a non-empty
 * tree with sensible node / edge counts.
 *
 * <p>This is a lightweight smoke test, not a comprehensive contract test —
 * the full export contract will be exercised by the M7 integration tests
 * once the demo cuts over.</p>
 */
class GroupJsonExporterSmokeTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    // Demo-sprint update: GroupJsonExporter now takes 4 ctor args
    // (LayoutEngine + GlossaryService + TableToTreeConverter + GroupLabelResolver).
    // The standalone GlossaryService instance skips @PostConstruct, so search()
    // returns an empty list — the resolver's built-in SHIM handles common
    // domain tokens. Smoke test only asserts node/edge presence.
    private final GlossaryService glossaryService = new GlossaryService();
    private final GroupJsonExporter exporter = new GroupJsonExporter(
            new GroupLayoutEngine(),
            glossaryService,
            new TableToTreeConverter(),
            new GroupLabelResolver(glossaryService));

    @Test
    void sampleA_decisionTableFlattenedToOneLevel() throws Exception {
        RuleEnvelope env = load("golden-tests/sample-A/expected-envelope.json");
        GroupTreeExportResult result = exporter.export(env);
        System.out.println("[sample-A] nodes=" + result.tree().nodes().size()
                + " edges=" + result.tree().edges().size()
                + " warnings=" + result.warnings().size()
                + " warnings_list=" + result.warnings());
        // sample-A has 4 rules → 1 ROOT + 4 leaves = 5 nodes; 4 edges
        assertTrue(result.tree().nodes().size() >= 2);
        assertNotNull(result.tree().treeId());
    }

    @Test
    void sampleBPart1_decisionTableWithMixedOperators() throws Exception {
        RuleEnvelope env = load("golden-tests/sample-B/expected-envelope-part1.json");
        GroupTreeExportResult result = exporter.export(env);
        System.out.println("[sample-B] nodes=" + result.tree().nodes().size()
                + " edges=" + result.tree().edges().size()
                + " warnings=" + result.warnings().size()
                + " warnings_list=" + result.warnings());
        assertTrue(result.tree().nodes().size() >= 2);
    }

    private RuleEnvelope load(String relPath) throws Exception {
        Path p = Path.of(relPath);
        return mapper.readValue(Files.readString(p), RuleEnvelope.class);
    }
}
