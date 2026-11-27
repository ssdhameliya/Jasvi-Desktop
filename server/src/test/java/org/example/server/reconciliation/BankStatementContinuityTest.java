package org.example.server.reconciliation;

import org.example.server.audit.AuditService;
import org.example.server.operations.BusinessOperationsService;
import org.example.server.persistence.JpaNativeRepository;
import org.example.server.persistence.entity.BankStatementImportEntity;
import org.example.server.persistence.repository.*;
import org.example.server.returns.ReturnService;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BankStatementContinuityTest {

    private static BankReconciliationService serviceWithBatches(List<BankStatementImportEntity> existing) {
        BankStatementImportRepository imports = mock(BankStatementImportRepository.class);
        when(imports.findAll()).thenReturn(existing);
        return new BankReconciliationService(
                imports,
                mock(BankStatementTransactionRepository.class),
                mock(BankReconciliationAllocationRepository.class),
                mock(BankReconciliationAuditRepository.class),
                mock(PaymentRecordRepository.class),
                mock(SalesHeaderRepository.class),
                mock(PurchaseHeaderRepository.class),
                mock(PurchaseReconRepository.class),
                mock(FinanceRegisterRepository.class),
                mock(JpaNativeRepository.class),
                mock(ReturnService.class),
                mock(AuditService.class),
                mock(BusinessOperationsService.class),
                mock(BankReconciliationRuleService.class)
        );
    }

    private static BankStatementImportEntity batch(long id, String ref, String acct, String from, String to, Double closing) {
        BankStatementImportEntity b = new BankStatementImportEntity();
        b.setId(id);
        b.setStatementReference(ref);
        b.setBankName("HDFC Bank");
        b.setBankAccount(acct);
        b.setStatementFrom(from);
        b.setStatementTo(to);
        b.setClosingBalance(closing);
        return b;
    }

    @Test
    void detectsDateGapAndBalanceMismatchAcrossSequentialImports() {
        var prior = batch(1L, "BST/18-11-2026/0001", "50200012345678", "2026-11-01", "2026-11-18", 125000.00);
        var svc = serviceWithBatches(List.of(prior));

        // 1) Gap of 2 days (19-20 Nov missing) + opening mismatch (120,000 vs prior closing 125,000)
        var res = svc.checkContinuity(new BankReconciliationDtos.ContinuityCheckRequest(
                "XXXX5678", "2026-11-21", "2026-11-30", 120000.00, 145000.00));

        assertTrue(res.hasPriorBatch());
        assertEquals("BST/18-11-2026/0001", res.priorBatchRef());
        assertEquals(2, res.gapDays());
        assertFalse(res.overlapDetected());
        assertTrue(res.balanceMismatch());
        assertEquals(-5000.00, res.balanceDifference(), 0.01);
        assertEquals("ERROR", res.severity());
        assertEquals(2, res.warnings().size());
    }

    @Test
    void confirmsCleanContinuityWhenDatesFollowAndOpeningMatchesClosing() {
        var prior = batch(1L, "BST/18-11-2026/0001", "50200012345678", "2026-11-01", "2026-11-18", 125000.00);
        var svc = serviceWithBatches(List.of(prior));

        var res = svc.checkContinuity(new BankReconciliationDtos.ContinuityCheckRequest(
                "50200012345678", "2026-11-19", "2026-11-30", 125000.00, 150000.00));

        assertTrue(res.hasPriorBatch());
        assertEquals(0, res.gapDays());
        assertFalse(res.overlapDetected());
        assertFalse(res.balanceMismatch());
        assertEquals("OK", res.severity());
        assertTrue(res.warnings().isEmpty());
    }

    @Test
    void migrationV11_0_92IsPackagedCleanlyWithoutDollarBlocks() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V11_0_92__sales_customer_health_and_pricing.sql")) {
            assertNotNull(in);
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains("credit_limit"));
            assertTrue(sql.contains("customer_price_rule"));
            assertFalse(sql.contains("$$"));
        }
    }
}
