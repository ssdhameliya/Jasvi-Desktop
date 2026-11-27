package org.example.server.financial;

import org.example.server.persistence.JpaNativeRepository;
import org.example.server.persistence.entity.BankReconciliationRuleEntity;
import org.example.server.persistence.entity.BankStatementImportEntity;
import org.example.server.persistence.entity.BankStatementTransactionEntity;
import org.example.server.persistence.repository.BankReconciliationAuditRepository;
import org.example.server.persistence.repository.BankReconciliationRuleRepository;
import org.example.server.persistence.repository.BankStatementTransactionRepository;
import org.example.server.reconciliation.BankReconciliationDtos.*;
import org.example.server.reconciliation.BankReconciliationRuleService;
import org.example.server.reconciliation.BankReconciliationService;
import org.example.server.security.AuthenticatedUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
}
