package org.example.server.financial;

import org.example.server.reconciliation.BankReconciliationDtos.*;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BankStatementEvolutionContractTest {

    @Test
    void lineReferenceMigrationExistsWithoutProceduralDollarBlocks() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V11_0_87__bank_statement_line_reference.sql")) {
            assertNotNull(in, "V11_0_87__bank_statement_line_reference.sql must be packaged");
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains("transaction_reference"));
            assertTrue(sql.contains("ROW_NUMBER()"));
            assertTrue(sql.contains("uq_bank_statement_transaction_ref"));
            assertFalse(sql.contains("$$"), "Runtime migration runner forbids procedural $$ blocks");
        }
    }

    @Test
    void metricsBankEntryRequestAndBrsDtoContractsWorkCorrectly() {
        var backwardMetrics = new Metrics(10, 2, 1, 5, 1, 0, 1, 50000.0, 12000.0, 7, 70.0, "PARTIAL");
        assertEquals(38000.0, backwardMetrics.calculatedClosingBalance(), 0.001);
        assertFalse(backwardMetrics.balanceVerified());

        var fullMetrics = new Metrics(10, 0, 0, 10, 0, 0, 0, 50000.0, 12000.0, 10, 100.0,
                "RECONCILED", 10000.0, 48000.0, 48000.0, 0.0, true);
        assertEquals(10000.0, fullMetrics.openingBalance(), 0.001);
        assertEquals(48000.0, fullMetrics.calculatedClosingBalance(), 0.001);
        assertTrue(fullMetrics.balanceVerified());

        var legacyEntry = new BankEntryRequest("HDFC - 1234", "NEFT", "Bank Receipt", "admin");
        assertNull(legacyEntry.contraAccountName());
        assertNull(legacyEntry.partyId());

        var contraEntry = new BankEntryRequest("HDFC - 1234", "RTGS", "Contra sweep", "admin",
                "CONTRA TRANSFER", null, "SBI - 5678");
        assertEquals("SBI - 5678", contraEntry.contraAccountName());

        var brs = new BrsSummaryDto("Single Statement", "HDFC", "1234", "2026-11-01", "2026-11-30", "2026-11-30",
                10000.0, 48000.0, 50000.0, 12000.0, 48000.0, 0.0,
                50000.0, 12000.0, 0.0, 0.0, 0.0, 0.0, 0.0, 48000.0, 0.0,
                10, 10, 0, List.of(), List.of());
        assertEquals(48000.0, brs.impliedBooksBalance(), 0.001);
    }

    @Test
    void controllerExposesConsolidatedAndBrsEndpointsUnderBankStatementsSecurityPath() throws Exception {
        String controller = Files.readString(Path.of(
                "src/main/java/org/example/server/reconciliation/BankReconciliationController.java"));
        assertTrue(controller.contains("@RequestMapping(\"/api/bank-statements\")"));
        assertTrue(controller.contains("@GetMapping(\"/accounts/page\")"));
        assertTrue(controller.contains("@GetMapping(\"/accounts/metrics\")"));
        assertTrue(controller.contains("@GetMapping(\"/imports/{id}/brs\")"));
        assertTrue(controller.contains("@GetMapping(\"/accounts/brs\")"));
    }

    @Test
    void suggestedMatchTypeMigrationWidensVarchar20AndRuleServiceCapsLength() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V11_0_89__bank_statement_suggested_match_type.sql")) {
            assertNotNull(in, "V11_0_89__bank_statement_suggested_match_type.sql must be packaged");
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains("ALTER TABLE bank_statement_transaction"));
            assertTrue(sql.contains("suggested_match_type TYPE VARCHAR(255)"));
            assertFalse(sql.contains("$$"), "Runtime migration runner forbids procedural $$ blocks");
        }
        String ruleService = Files.readString(Path.of(
                "src/main/java/org/example/server/reconciliation/BankReconciliationRuleService.java"));
        assertTrue(ruleService.contains("formatSuggestedRuleTag(rule)"));
        assertTrue(ruleService.contains("tag.length() > 255"));
    }
}
