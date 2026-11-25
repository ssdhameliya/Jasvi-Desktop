package org.example.server.financial;

import org.example.server.accounting.GeneralLedgerDtos;
import org.example.server.accounting.GeneralLedgerService;
import org.example.server.aging.AgingAnalysisService;
import org.example.server.audit.AuditService;
import org.example.server.persistence.JpaNativeRepository;
import org.example.server.persistence.entity.ChartOfAccountsEntity;
import org.example.server.persistence.entity.JournalEntryEntity;
import org.example.server.persistence.entity.JournalLineEntity;
import org.example.server.persistence.repository.ChartOfAccountsRepository;
import org.example.server.persistence.repository.JournalEntryRepository;
import org.example.server.persistence.repository.JournalLineRepository;
import org.example.shared.PermissionCatalog;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive verification suite for all 29 GL/Financial/Accounting/Aging/GST/Security fixes
 * and the 6 enterprise enhancements.
 */
class GlModuleRemediationContractTest {

    @Test
    void migrationV11_0_88IsPackagedWithoutDollarBlocksAndCoversAllGlHardening() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V11_0_88__gl_integrity_reconciliation_and_fy_close.sql")) {
            assertNotNull(in, "V11_0_88__gl_integrity_reconciliation_and_fy_close.sql must exist");
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(sql.contains("$$"), "Runtime migration runner forbids $$ blocks");
            assertTrue(sql.contains("ADD COLUMN IF NOT EXISTS gl_entry_id"));
            assertTrue(sql.contains("fiscal_year_close_log"));
            assertTrue(sql.contains("gst_gl_settlement_log"));
            assertTrue(sql.contains("'1510'") && sql.contains("'2510'") && sql.contains("'4100'") && sql.contains("'5100'"));
            assertTrue(sql.contains("GENERAL_LEDGER.CLOSE_YEAR"));
            assertTrue(sql.contains("GST_COMPLIANCE.POST_GL"));
        }

        String runner = Files.readString(Path.of("src/main/java/org/example/server/runtime/SecurityFinancialMigrationRunner.java"));
        assertTrue(runner.contains("V11_0_88__gl_integrity_reconciliation_and_fy_close.sql"));
    }

    @Test
    void permissionCatalogAndSecurityConfigProtectAllFinancialEndpoints() throws Exception {
        assertEquals("Close Fiscal Year", PermissionCatalog.actionLabel("CLOSE_YEAR"));
        assertEquals("Post Tax Offset to GL", PermissionCatalog.actionLabel("POST_GL"));
        assertTrue(PermissionCatalog.templateAllows(PermissionCatalog.MANAGER_STANDARD, "GST_COMPLIANCE", "POST_GL"));
        assertFalse(PermissionCatalog.templateAllows(PermissionCatalog.MANAGER_STANDARD, "GENERAL_LEDGER", "CLOSE_YEAR"));
        assertTrue(PermissionCatalog.templateAllows(PermissionCatalog.BUSINESS_FULL, "GENERAL_LEDGER", "CLOSE_YEAR"));

        String sec = Files.readString(Path.of("src/main/java/org/example/server/security/SecurityConfig.java"));
        assertTrue(sec.contains("/api/accounting/**"));
        assertTrue(sec.contains("/api/financial/**"));
        assertTrue(sec.contains("/api/financial-statements/**"));
        assertTrue(sec.contains("/api/aging/**"));
        assertTrue(sec.contains("/api/gst/gstr3b/*/post-utilization"));
    }

    @Test
    void financialStatementsServiceContainsZeroMockConstantsAndSupportsCanonicalGlAndExtendedDrillDown() throws Exception {
        String fs = Files.readString(Path.of("src/main/java/org/example/server/financialstatements/FinancialStatementsService.java"));
        assertFalse(fs.contains("150000"), "Hardcoded Cash/Machinery/Capital mock 150000 must be removed");
        assertFalse(fs.contains("50000"), "Hardcoded Bank/Loan mock 50000 must be removed");
        assertTrue(fs.contains("CANONICAL_GL"));
        assertTrue(fs.contains("CASH_BANK"));
        assertTrue(fs.contains("FIXED_ASSETS"));
        assertTrue(fs.contains("BORROWINGS"));
        assertTrue(fs.contains("CAPITAL"));
        assertTrue(fs.contains("OPENING_STOCK"));
        assertTrue(fs.contains("CLOSING_STOCK"));
        assertTrue(fs.contains("buildCashFlow("));
        assertTrue(fs.contains("buildPriorComparison"));
    }

    @Test
    void openingBalanceUpdateAutoBalancesAgainstSuspenseAccount3999() {
        ChartOfAccountsRepository coaRepo = mock(ChartOfAccountsRepository.class);
        JournalEntryRepository jeRepo = mock(JournalEntryRepository.class);
        JournalLineRepository jlRepo = mock(JournalLineRepository.class);
        JpaNativeRepository jdbc = mock(JpaNativeRepository.class);

        ChartOfAccountsEntity suspense = new ChartOfAccountsEntity();
        suspense.setId(99L);
        suspense.setAccountCode("3999");
        suspense.setAccountName("Opening Balance Suspense");
        suspense.setAccountType("EQUITY");
        suspense.setOpeningBalance(BigDecimal.ZERO);
        suspense.setCurrentBalance(BigDecimal.ZERO);

        when(coaRepo.findByAccountCode("1055")).thenReturn(Optional.empty());
        when(coaRepo.findByAccountCode("3999")).thenReturn(Optional.of(suspense));
        when(jdbc.queryForObject(contains("chart_of_accounts WHERE account_code"), eq(Long.class), eq("3999"))).thenReturn(99L);
        when(coaRepo.findById(99L)).thenReturn(Optional.of(suspense));
        when(coaRepo.save(any())).thenAnswer(inv -> {
            ChartOfAccountsEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId(1055L);
            return e;
        });

        GeneralLedgerService gl = new GeneralLedgerService(coaRepo, jeRepo, jlRepo, jdbc);
        var created = gl.createAccount(new GeneralLedgerDtos.AccountDto(
                null, "1055", "Petty Cash", "ASSET", "CURRENT_ASSET",
                null, "INR", new BigDecimal("5000.00"), null, true, false, 0L));

        assertEquals(0, new BigDecimal("5000.00").compareTo(created.openingBalance()));
        assertEquals(0, new BigDecimal("5000.00").compareTo(suspense.getCurrentBalance()),
                "Opening balance on ASSET account must auto-offset EQUITY Suspense 3999 by +5000 to keep Trial Balance balanced");
    }

    @Test
    void postJournalEntryAttachesSavedLinesAndRejectsLockedPeriods() {
        ChartOfAccountsRepository coaRepo = mock(ChartOfAccountsRepository.class);
        JournalEntryRepository jeRepo = mock(JournalEntryRepository.class);
        JournalLineRepository jlRepo = mock(JournalLineRepository.class);
        JpaNativeRepository jdbc = mock(JpaNativeRepository.class);

        ChartOfAccountsEntity bank = new ChartOfAccountsEntity();
        bank.setId(2L);
        bank.setAccountCode("1020");
        bank.setAccountName("Bank Account");
        bank.setAccountType("ASSET");
        bank.setIsActive(true);
        bank.setCurrentBalance(BigDecimal.ZERO);

        ChartOfAccountsEntity rev = new ChartOfAccountsEntity();
        rev.setId(4L);
        rev.setAccountCode("4010");
        rev.setAccountName("Domestic Sales");
        rev.setAccountType("REVENUE");
        rev.setIsActive(true);
        rev.setCurrentBalance(BigDecimal.ZERO);

        when(coaRepo.findById(2L)).thenReturn(Optional.of(bank));
        when(coaRepo.findById(4L)).thenReturn(Optional.of(rev));
        when(coaRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(jeRepo.save(any())).thenAnswer(inv -> {
            JournalEntryEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId(500L);
            return e;
        });
        when(jlRepo.save(any())).thenAnswer(inv -> {
            JournalLineEntity l = inv.getArgument(0);
            if (l.getId() == null) l.setId(1L);
            return l;
        });
        // Books locked through 2026-10-31
        when(jdbc.queryForObject(contains("setting_value"), eq(String.class), eq("accounting.period.lock.until")))
                .thenReturn("2026-10-31");

        GeneralLedgerService gl = new GeneralLedgerService(coaRepo, jeRepo, jlRepo, jdbc);
        var reqLines = List.of(
                new GeneralLedgerDtos.CreateLineRequest(2L, "1020", new BigDecimal("1000.00"), BigDecimal.ZERO, null, "Cash sale", null),
                new GeneralLedgerDtos.CreateLineRequest(4L, "4010", BigDecimal.ZERO, new BigDecimal("1000.00"), null, "Sales revenue", null)
        );

        assertThrows(IllegalStateException.class, () -> gl.postJournalEntry(
                new GeneralLedgerDtos.CreateJournalRequest(
                        LocalDate.of(2026, 10, 15), "GENERAL", "MANUAL", 1L, "JV-LOCKED", "Locked test", "admin", "POSTED", reqLines)),
                "Posting into a locked period must be rejected");

        var posted = gl.postJournalEntry(new GeneralLedgerDtos.CreateJournalRequest(
                LocalDate.of(2026, 11, 15), "GENERAL", "MANUAL", 1L, "JV-OPEN", "Open test", "admin", "POSTED", reqLines));

        assertEquals(2, posted.lines().size(), "postJournalEntry must return saved lines immediately");
        assertEquals(0, new BigDecimal("1000.00").compareTo(bank.getCurrentBalance()));
        assertEquals(0, new BigDecimal("1000.00").compareTo(rev.getCurrentBalance()));
    }

    @Test
    void cancelJournalEntriesForReferenceSkipsAlreadyReversedEntriesWithoutDoubleReversing() {
        ChartOfAccountsRepository coaRepo = mock(ChartOfAccountsRepository.class);
        JournalEntryRepository jeRepo = mock(JournalEntryRepository.class);
        JournalLineRepository jlRepo = mock(JournalLineRepository.class);
        JpaNativeRepository jdbc = mock(JpaNativeRepository.class);

        ChartOfAccountsEntity bank = new ChartOfAccountsEntity();
        bank.setId(2L);
        bank.setAccountCode("1020");
        bank.setAccountType("ASSET");
        bank.setCurrentBalance(BigDecimal.ZERO);

        JournalEntryEntity alreadyReversed = new JournalEntryEntity();
        alreadyReversed.setId(77L);
        alreadyReversed.setEntryNumber("JV-77");
        alreadyReversed.setEntryDate(LocalDate.of(2026, 11, 20));
        alreadyReversed.setStatus("REVERSED");
        JournalLineEntity jl = new JournalLineEntity();
        jl.setAccountId(2L);
        jl.setDebitAmount(new BigDecimal("5000.00"));
        jl.setCreditAmount(BigDecimal.ZERO);
        alreadyReversed.getLines().add(jl);

        when(jeRepo.findByReferenceTypeAndReferenceId("SALE", 99L)).thenReturn(List.of(alreadyReversed));
        when(coaRepo.findById(2L)).thenReturn(Optional.of(bank));

        GeneralLedgerService gl = new GeneralLedgerService(coaRepo, jeRepo, jlRepo, jdbc);
        gl.cancelJournalEntriesForReference("SALE", 99L, "Cancelled in test");

        assertEquals("REVERSED", alreadyReversed.getStatus());
        assertEquals(0, BigDecimal.ZERO.compareTo(bank.getCurrentBalance()),
                "Cancelled hook must NOT subtract balance a second time on already REVERSED entries");
        verify(jeRepo, never()).save(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void agingAnalysisHonorsHistoricalCutoffAndNetsUnappliedCreditNotesFifo() {
        JpaNativeRepository jdbc = mock(JpaNativeRepository.class);
        AuditService audit = mock(AuditService.class);
        LocalDate asOf = LocalDate.of(2026, 11, 20);

        List<Map<String, Object>> simulatedInvoices = new ArrayList<>();
        // Invoice 1: 100 days old -> 90+ bucket, ₹10,000
        simulatedInvoices.add(Map.of("partyId", 10L, "partyName", "Acme Steel", "gstin", "24ABCDE1234F1Z5",
                "phone", "9876543210", "invoiceDate", "2026-08-12", "balance", new BigDecimal("10000.00")));
        // Invoice 2: 10 days old -> 0-30 bucket, ₹5,000
        simulatedInvoices.add(Map.of("partyId", 10L, "partyName", "Acme Steel", "gstin", "24ABCDE1234F1Z5",
                "phone", "9876543210", "invoiceDate", "2026-11-10", "balance", new BigDecimal("5000.00")));
        // Invoice 3: future-dated (2026-11-25) -> must be excluded as of 2026-11-20!
        simulatedInvoices.add(Map.of("partyId", 10L, "partyName", "Acme Steel", "gstin", "24ABCDE1234F1Z5",
                "phone", "9876543210", "invoiceDate", "2026-11-25", "balance", new BigDecimal("99999.00")));

        when(jdbc.query(contains("FROM sales_header"), any(BiFunction.class), eq(asOf), eq(asOf), eq(asOf)))
                .thenReturn(simulatedInvoices);
        // Also handle LocalDate.now() non-historical branch cleanly if asOf is compared against today
        when(jdbc.query(contains("FROM sales_header"), any(BiFunction.class), eq(asOf)))
                .thenReturn(simulatedInvoices);

        // Unapplied Credit Note of ₹6,000 for party 10
        when(jdbc.queryForList(contains("FROM credit_note_header"), eq(asOf)))
                .thenReturn(List.of(Map.of(
                        "party_id", 10L,
                        "note_no", "CN/26-27/0001",
                        "note_total", new BigDecimal("6000.00"),
                        "note_notes", "Standalone credit note"
                )));
        when(jdbc.query(contains("FROM return_refund"), any(BiFunction.class), eq(asOf))).thenReturn(List.of());
        when(jdbc.query(contains("FROM payment_record"), any(BiFunction.class), any(), eq(asOf))).thenReturn(List.of());

        AgingAnalysisService aging = new AgingAnalysisService(jdbc, audit);
        var summary = aging.getReceivablesAging(asOf);

        assertEquals(1, summary.parties().size());
        var p = summary.parties().getFirst();
        // FIFO credit note of 6,000 reduces 90+ bucket from 10,000 -> 4,000 while keeping 0-30 bucket at 5,000 (total 9,000; future 99,999 excluded)
        assertEquals(0, new BigDecimal("4000.00").compareTo(p.bucket90Plus()), "90+ bucket must absorb FIFO unapplied credit note first");
        assertEquals(0, new BigDecimal("5000.00").compareTo(p.bucket0To30()), "0-30 bucket must remain 5000");
        assertEquals(0, new BigDecimal("9000.00").compareTo(p.totalDue()), "Net receivable must exclude future invoice and net unapplied CN");
        assertEquals(0, new BigDecimal("9000.00").compareTo(summary.totalOutstanding()));
    }
}
