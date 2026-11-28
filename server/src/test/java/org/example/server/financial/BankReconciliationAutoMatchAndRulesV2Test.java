package org.example.server.financial;

import org.example.server.audit.AuditService;
import org.example.server.operations.BusinessOperationsService;
import org.example.server.persistence.JpaNativeRepository;
import org.example.server.persistence.entity.*;
import org.example.server.persistence.repository.*;
import org.example.server.reconciliation.BankReconciliationDtos.*;
import org.example.server.reconciliation.BankReconciliationRuleService;
import org.example.server.reconciliation.BankReconciliationService;
import org.example.server.returns.ReturnService;
import org.example.server.security.AuthenticatedUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BankReconciliationAutoMatchAndRulesV2Test {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void migrationV11_0_91IsValidSqlWithoutDollarBlocks() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V11_0_91__bank_rules_v2_and_party_aliases.sql")) {
            assertNotNull(in, "V11_0_91__bank_rules_v2_and_party_aliases.sql must be packaged");
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains("party_bank_alias"));
            assertTrue(sql.contains("min_amount"));
            assertTrue(sql.contains("target_party_id"));
            assertTrue(sql.contains("target_contra_account"));
            assertFalse(sql.contains("$$"), "Runtime migration runner forbids procedural $$ blocks");
        }
    }

    @Test
    void singleRuleRunOnlyAppliesRequestedRuleAndRespectsAmountRangeAndRegex() {
        var principal = new AuthenticatedUser(1, "admin", "ADMIN");
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, "", List.of()));

        BankReconciliationRuleRepository ruleRepo = mock(BankReconciliationRuleRepository.class);
        BankStatementTransactionRepository txRepo = mock(BankStatementTransactionRepository.class);
        BankReconciliationAuditRepository auditRepo = mock(BankReconciliationAuditRepository.class);
        JpaNativeRepository jdbc = mock(JpaNativeRepository.class);
        BankReconciliationService reconService = mock(BankReconciliationService.class);

        when(jdbc.query(contains("application_setting"), any(BiFunction.class), eq("module.bankRules.enabled")))
                .thenReturn(List.of("true"));

        BankReconciliationRuleEntity targetedRule = new BankReconciliationRuleEntity();
        targetedRule.setRuleName("Small Bank SMS Charge");
        targetedRule.setMatchField("NARRATION");
        targetedRule.setMatchOperator("REGEX");
        targetedRule.setMatchPattern("^(SMS|MIN\\s+BAL).*CHRG");
        targetedRule.setDirection("DEBIT");
        targetedRule.setActionType("EXPENSE");
        targetedRule.setTargetCategory("Bank Charges");
        targetedRule.setMinAmount(10.0);
        targetedRule.setMaxAmount(500.0);
        targetedRule.setExecutionMode("AUTO_APPLY");
        targetedRule.setActive(true);
        when(ruleRepo.findById(42L)).thenReturn(Optional.of(targetedRule));

        BankStatementImportEntity batch = new BankStatementImportEntity();
        batch.setBankName("HDFC");
        batch.setBankAccount("502000123456");

        BankStatementTransactionEntity smallCharge = new BankStatementTransactionEntity();
        smallCharge.setImportBatch(batch);
        smallCharge.setOriginalDescription("SMS ALERT CHRG Q4");
        smallCharge.setDebitAmount(177.0);
        smallCharge.setCreditAmount(0.0);
        smallCharge.setStatus("UNMATCHED");

        BankStatementTransactionEntity largeCharge = new BankStatementTransactionEntity();
        largeCharge.setImportBatch(batch);
        largeCharge.setOriginalDescription("SMS GATEWAY CHRG VENDOR");
        largeCharge.setDebitAmount(4500.0);
        largeCharge.setCreditAmount(0.0);
        largeCharge.setStatus("UNMATCHED");

        when(txRepo.findByImportBatchIdOrderByTransactionTimestampAscIdAsc(99L))
                .thenReturn(List.of(smallCharge, largeCharge));

        BankReconciliationRuleService svc = new BankReconciliationRuleService(ruleRepo, txRepo, auditRepo, jdbc, reconService);
        RuleApplyResult res = svc.applySingleRuleToBatch(99L, 42L, "admin");

        assertTrue(res.enabled());
        assertEquals(1, res.autoAppliedCount());
        assertEquals(0, res.suggestedCount());
        verify(reconService, times(1)).expense(any(), argThat(req -> "Bank Charges".equals(req.category())));
        verify(ruleRepo, never()).findByActiveTrueOrderByPriorityAscIdAsc();
    }

    @Test
    void previewAutoMatchPreventsFalseTieAutoSelectAndResolvesContestedDocumentsOptimally() {
        var principal = new AuthenticatedUser(1, "admin", "ADMIN");
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, "", List.of()));

        BankStatementImportRepository importRepo = mock(BankStatementImportRepository.class);
        BankStatementTransactionRepository txRepo = mock(BankStatementTransactionRepository.class);
        BankReconciliationAllocationRepository allocRepo = mock(BankReconciliationAllocationRepository.class);
        BankReconciliationAuditRepository auditRepo = mock(BankReconciliationAuditRepository.class);
        PaymentRecordRepository payRepo = mock(PaymentRecordRepository.class);
        SalesHeaderRepository salesRepo = mock(SalesHeaderRepository.class);
        PurchaseHeaderRepository purchaseRepo = mock(PurchaseHeaderRepository.class);
        PurchaseReconRepository reconRepo = mock(PurchaseReconRepository.class);
        FinanceRegisterRepository finRepo = mock(FinanceRegisterRepository.class);
        JpaNativeRepository jdbc = mock(JpaNativeRepository.class);
        BankReconciliationRuleService ruleService = mock(BankReconciliationRuleService.class);

        when(allocRepo.findByStatementTransactionIdInAndReversedAtIsNull(any())).thenReturn(List.of());
        when(jdbc.queryForList(contains("party_bank_alias"))).thenReturn(List.of());
        when(jdbc.queryForList(contains("FROM return_register"), any())).thenReturn(List.of());
        when(jdbc.queryForList(contains("FROM payment_record"), any())).thenReturn(List.of());
        when(jdbc.queryForList(contains("FROM finance_register"), any(), any())).thenReturn(List.of());
        when(ruleService.isFeatureEnabled()).thenReturn(false);

        BankStatementImportEntity batch = new BankStatementImportEntity();
        batch.setId(10L);
        batch.setStatementReference("BST/27-11-2026/0001");
        batch.setBankName("Kotak Mahindra");
        batch.setBankAccount("9988776655");

        PartyEntity partyA = new PartyEntity();
        partyA.setId(11);
        partyA.setName("Zenith Plastics");
        partyA.setGstin("24ABCDE1234F1Z5");

        PartyEntity partyB = new PartyEntity();
        partyB.setId(12);
        partyB.setName("Mehta Engineering");
        partyB.setGstin("24PQRST9988K1Z1");

        // Ambiguous: two different parties with identical ₹10,000 invoice & generic NEFT narration
        SalesHeaderEntity invTie1 = saleDoc(101, "IN/26-27/0101", "2026-11-25", 10000.0, partyA);
        SalesHeaderEntity invTie2 = saleDoc(102, "IN/26-27/0102", "2026-11-25", 10000.0, partyB);

        // Contested pair: txOnly1 only fits invShared (different party? No, txFlexible has exact Zenith + invAlt too!)
        SalesHeaderEntity invShared = saleDoc(103, "IN/26-27/0103", "2026-11-24", 15000.0, partyA);
        SalesHeaderEntity invAlt = saleDoc(104, "IN/26-27/0104", "2026-11-24", 15000.0, partyA);

        BankStatementTransactionEntity txTie = bankTx(1L, batch, "2026-11-26", "NEFT/UNKNOWN/TRANSFER", 10000.0, 0.0);
        BankStatementTransactionEntity txConstrained = bankTx(2L, batch, "2026-11-25", "UPI/IN26270103/ZENITH", 15000.0, 0.0);
        BankStatementTransactionEntity txFlexible = bankTx(3L, batch, "2026-11-25", "NEFT/ZENITH PLASTICS/ABCDE1234F", 15000.0, 0.0);

        // Same-account bounce pair
        BankStatementTransactionEntity txOrigReceipt = bankTx(4L, batch, "2026-11-26", "CHQ 445511 CLEARING", 8500.0, 0.0);
        BankStatementTransactionEntity txBounceDebit = bankTx(5L, batch, "2026-11-26", "REV-CHQ BOUNCE 445511", 0.0, 8500.0);

        when(txRepo.findByImportBatchIdOrderByTransactionTimestampAscIdAsc(10L))
                .thenReturn(List.of(txTie, txConstrained, txFlexible, txOrigReceipt, txBounceDebit));
        when(salesRepo.findOpenForBankMatching(eq(0d), any())).thenReturn(List.of(invTie1, invTie2, invShared, invAlt));
        when(purchaseRepo.findOpenForBankMatching(eq(0d), any())).thenReturn(List.of());

        BankReconciliationService service = new BankReconciliationService(
                importRepo, txRepo, allocRepo, auditRepo, payRepo,
                salesRepo, purchaseRepo, reconRepo, finRepo, jdbc,
                mock(ReturnService.class), mock(AuditService.class), mock(BusinessOperationsService.class), ruleService);

        AutoMatchPreviewDto preview = service.previewAutoMatch(10L, "", "", "");

        AutoMatchSuggestionDto tieSuggestion = preview.suggestions().stream()
                .filter(s -> s.tx().id().equals(1L)).findFirst().orElseThrow();
        assertFalse(tieSuggestion.autoSelectable(), "Ambiguous equal-amount candidates with no party proof must NOT be auto-selected");
        assertTrue(tieSuggestion.reasonSummary().contains("Ambiguous") || tieSuggestion.reasonSummary().contains("Verify Party"));

        AutoMatchSuggestionDto constrainedMatch = preview.suggestions().stream()
                .filter(s -> s.tx().id().equals(2L)).findFirst().orElseThrow();
        AutoMatchSuggestionDto flexibleMatch = preview.suggestions().stream()
                .filter(s -> s.tx().id().equals(3L)).findFirst().orElseThrow();
        assertEquals(103, constrainedMatch.candidate().id());
        assertEquals(104, flexibleMatch.candidate().id(), "Second tx should fall back cleanly to unclaimed alternate invoice");
        assertTrue(constrainedMatch.autoSelectable());
        assertTrue(flexibleMatch.autoSelectable());
        assertTrue(flexibleMatch.reasonSummary().contains("PAN matched") || flexibleMatch.reasonSummary().contains("Party 'ZENITH'"));

        List<AutoMatchSuggestionDto> bounceRows = preview.suggestions().stream()
                .filter(s -> "BOUNCE".equals(s.kind())).toList();
        assertEquals(2, bounceRows.size(), "Both sides of same-account bounce pair must be isolated for review");
        assertTrue(bounceRows.stream().noneMatch(AutoMatchSuggestionDto::autoSelectable));
    }

    @Test
    void detectsExternalSupplierBillRefPrefixTokensAndSixInvoiceFifoMultiSplit() {
        var principal = new AuthenticatedUser(1, "admin", "ADMIN");
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, "", List.of()));

        BankStatementImportRepository importRepo = mock(BankStatementImportRepository.class);
        BankStatementTransactionRepository txRepo = mock(BankStatementTransactionRepository.class);
        BankReconciliationAllocationRepository allocRepo = mock(BankReconciliationAllocationRepository.class);
        BankReconciliationAuditRepository auditRepo = mock(BankReconciliationAuditRepository.class);
        PaymentRecordRepository payRepo = mock(PaymentRecordRepository.class);
        SalesHeaderRepository salesRepo = mock(SalesHeaderRepository.class);
        PurchaseHeaderRepository purchaseRepo = mock(PurchaseHeaderRepository.class);
        PurchaseReconRepository reconRepo = mock(PurchaseReconRepository.class);
        FinanceRegisterRepository finRepo = mock(FinanceRegisterRepository.class);
        JpaNativeRepository jdbc = mock(JpaNativeRepository.class);
        BankReconciliationRuleService ruleService = mock(BankReconciliationRuleService.class);

        when(allocRepo.findByStatementTransactionIdInAndReversedAtIsNull(any())).thenReturn(List.of());
        when(jdbc.queryForList(contains("party_bank_alias"))).thenReturn(List.of());
        when(jdbc.queryForList(contains("FROM return_register"), any())).thenReturn(List.of());
        when(jdbc.queryForList(contains("FROM payment_record"), any())).thenReturn(List.of());
        when(jdbc.queryForList(contains("FROM finance_register"), any(), any())).thenReturn(List.of());
        when(ruleService.isFeatureEnabled()).thenReturn(false);

        BankStatementImportEntity batch = new BankStatementImportEntity();
        batch.setId(20L);
        batch.setStatementReference("BST/27-11-2026/0002");
        batch.setBankName("HDFC Bank");
        batch.setBankAccount("502000998877");

        PartyEntity vendor = new PartyEntity();
        vendor.setId(25);
        vendor.setName("Saurashtra Precision Fasteners");

        PurchaseHeaderEntity bill = new PurchaseHeaderEntity();
        bill.setId(501);
        bill.setInvoiceNo("PUR/26-27/0055");
        bill.setReferenceNo("SPF-88412");
        bill.setInvoiceDate("2026-11-20");
        bill.setTotalAmount(12400.0);
        bill.setSubtotal(12400.0);
        bill.setPaidAmount(0.0);
        bill.setSupplier(vendor);

        // Customer paying 5 FIFO invoices together in one RTGS
        PartyEntity customer = new PartyEntity();
        customer.setId(30);
        customer.setName("Navrang Engineering Works");
        List<SalesHeaderEntity> fiveSales = List.of(
                saleDoc(201, "IN/26-27/0201", "2026-11-01", 1000.0, customer),
                saleDoc(202, "IN/26-27/0202", "2026-11-03", 2000.0, customer),
                saleDoc(203, "IN/26-27/0203", "2026-11-05", 3000.0, customer),
                saleDoc(204, "IN/26-27/0204", "2026-11-07", 4000.0, customer),
                saleDoc(205, "IN/26-27/0205", "2026-11-09", 5000.0, customer)
        );

        BankStatementTransactionEntity txVendor = bankTx(10L, batch, "2026-11-22", "NEFT/SAURA/FASTEN/SPF88412", 0.0, 12400.0);
        BankStatementTransactionEntity txSplit5 = bankTx(11L, batch, "2026-11-12", "RTGS/NAVRANG FAST SETTLEMENT", 15000.0, 0.0);

        when(txRepo.findByImportBatchIdOrderByTransactionTimestampAscIdAsc(20L))
                .thenReturn(List.of(txVendor, txSplit5));
        when(salesRepo.findOpenForBankMatching(eq(0d), any())).thenReturn(fiveSales);
        when(purchaseRepo.findOpenForBankMatching(eq(0d), any())).thenReturn(List.of(bill));

        BankReconciliationService service = new BankReconciliationService(
                importRepo, txRepo, allocRepo, auditRepo, payRepo,
                salesRepo, purchaseRepo, reconRepo, finRepo, jdbc,
                mock(ReturnService.class), mock(AuditService.class), mock(BusinessOperationsService.class), ruleService);

        AutoMatchPreviewDto preview = service.previewAutoMatch(20L, "", "", "");

        AutoMatchSuggestionDto vendorSuggestion = preview.suggestions().stream()
                .filter(s -> s.tx().id().equals(10L)).findFirst().orElseThrow();
        assertEquals(501, vendorSuggestion.candidate().id());
        assertTrue(vendorSuggestion.autoSelectable());
        assertTrue(vendorSuggestion.reasonSummary().contains("Ref #SPF-88412 matched"));

        AutoMatchSuggestionDto splitSuggestion = preview.suggestions().stream()
                .filter(s -> s.tx().id().equals(11L)).findFirst().orElseThrow();
        assertEquals("MULTI_SPLIT", splitSuggestion.kind());
        assertEquals(5, splitSuggestion.candidate().splitAllocations().size());
        assertTrue(splitSuggestion.autoSelectable());
    }

    private static SalesHeaderEntity saleDoc(int id, String no, String date, double total, PartyEntity cust) {
        SalesHeaderEntity s = new SalesHeaderEntity();
        s.setId(id);
        s.setInvoiceNo(no);
        s.setInvoiceDate(date);
        s.setTotalAmount(total);
        s.setSubtotal(total);
        s.setPaidAmount(0.0);
        s.setCustomer(cust);
        return s;
    }

    private static BankStatementTransactionEntity bankTx(long id, BankStatementImportEntity batch, String date, String desc, double credit, double debit) {
        BankStatementTransactionEntity t = new BankStatementTransactionEntity();
        org.springframework.test.util.ReflectionTestUtils.setField(t, "id", id);
        t.setImportBatch(batch);
        t.setTransactionDate(date);
        t.setOriginalDescription(desc);
        t.setOriginalReference("");
        t.setCreditAmount(credit);
        t.setDebitAmount(debit);
        t.setStatus("UNMATCHED");
        return t;
    }
}
