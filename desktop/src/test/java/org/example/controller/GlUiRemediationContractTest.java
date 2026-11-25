package org.example.controller;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies desktop UI, navigation, pagination, export, and table-layout remediation across
 * FinancialStatements, AccountLedgerDialog, AgingAnalysis, GeneralLedger, and GstCompliance.
 */
class GlUiRemediationContractTest {

    @Test
    void financialStatementsFxmlHasZeroHardcodedTreeColumnPrefWidthsAndHasCashFlowAndCompareUi() throws Exception {
        String fxml = Files.readString(Path.of("src/main/resources/fxml/pages/FinancialStatements.fxml"));
        Matcher m = Pattern.compile("<TreeTableColumn\\b[^>]*\\bprefWidth\\s*=").matcher(fxml);
        assertFalse(m.find(), "FinancialStatements.fxml TreeTableColumns must not hardcode prefWidth");
        assertTrue(fxml.contains("fx:id=\"chkComparePrior\""));
        assertTrue(fxml.contains("fx:id=\"tabCashFlow\""));
        assertTrue(fxml.contains("fx:id=\"tblCashFlow\""));
    }

    @Test
    void financialStatementsControllerSupportsCanonicalGlCashFlowComparisonAndHierarchicalFilterPruning() throws Exception {
        String src = Files.readString(Path.of("src/main/java/org/example/controller/FinancialStatementsController.java"));
        assertTrue(src.contains("\"CANONICAL_GL\""), "Canonical GL mode must be wired");
        assertTrue(src.contains("DynamicTableLayoutManager.install(treeTblPnl)"));
        assertTrue(src.contains("DynamicTableLayoutManager.install(treeTblBs)"));
        assertTrue(src.contains("private TreeItem<StatementItemRow> pruneTree("), "filterTree must prune non-matching nodes");
        assertTrue(src.contains("AccountLedgerDialog.show("), "drillDownToLedger must open AccountLedgerDialog directly");
        assertTrue(src.contains("\"FIXED_ASSETS\"") && src.contains("\"BORROWINGS\"") && src.contains("\"CAPITAL\"") && src.contains("\"GST\""));
        assertTrue(src.contains("\"OPENING_STOCK\"") && src.contains("\"CLOSING_STOCK\""));
        assertTrue(src.contains("populateCashFlow(") && src.contains("populateComparison("));
    }

    @Test
    void accountLedgerDialogUsesDynamicLayoutEnforcesExportPermissionSupportsPartyLedgerAndRepeatingPdfHeader() throws Exception {
        String src = Files.readString(Path.of("src/main/java/org/example/util/AccountLedgerDialog.java"));
        assertTrue(src.contains("DynamicTableLayoutManager.install(table)"));
        assertTrue(src.contains("PermissionService.require(\"GENERAL_LEDGER.EXPORT\""));
        assertTrue(src.contains("showPartyLedger("));
        assertTrue(src.contains("writePdfHeader("), "PDF export must repeat column headers on continuation pages");
    }

    @Test
    void agingAnalysisFixesEntityIdPassesRealPartyIdUsesPhoneIconAndOpensPartyLedger() throws Exception {
        String src = Files.readString(Path.of("src/main/java/org/example/controller/AgingAnalysisController.java"));
        assertFalse(src.contains("\"CUSTOMER\" : \"SUPPLIER\", 1,"), "Audit timeline must use real partyId instead of 1");
        assertTrue(src.contains("row.partyId != null ? row.partyId.intValue() : 0"));
        assertTrue(src.contains("\"Contact Phone\", row.phone != null && !row.phone.isBlank() ? row.phone : \"Not recorded\", \"phone\""));
        assertTrue(src.contains("DocumentInspectionScaffold.installDragPhysics(hero, dialog)"));
        assertTrue(src.contains("AccountLedgerDialog.showPartyLedger("));
    }

    @Test
    void generalLedgerControllerRemovesClientFallbackWiresServerPaginationHealthCheckFyCloseAndCompleteDeepLinks() throws Exception {
        String fxml = Files.readString(Path.of("src/main/resources/fxml/pages/GeneralLedger.fxml"));
        assertTrue(fxml.contains("fx:id=\"btnHealthCheck\""));
        assertTrue(fxml.contains("fx:id=\"btnCloseYear\""));

        String src = Files.readString(Path.of("src/main/java/org/example/controller/GeneralLedgerController.java"));
        assertFalse(src.contains("Local transaction compilation fallback"), "Client incomplete local fallback must be removed");
        assertTrue(src.contains("glClient.journalEntriesPage("), "Must use server-backed journal pagination");
        assertTrue(src.contains("openHealthCheckDialog()"));
        assertTrue(src.contains("openCloseYearDialog()"));
        assertTrue(src.contains("LinkedRecordContext.consume(\"ACCOUNT_LEDGER\")"));
        assertTrue(src.contains("LinkedRecordContext.consume(\"PARTY_LEDGER\")"));
        assertTrue(src.contains("CreditNoteRegister.fxml") && src.contains("DebitNoteRegister.fxml") && src.contains("BankExpense.fxml"));
        assertTrue(src.contains("Reverse Voucher") && src.contains("btnRevModal"));
        assertTrue(src.contains("PdfPackWriter"), "PDF export must support multi-page pagination with full Balance Sheet");
        assertFalse(src.contains("ActivityTimelineDialog.show(tblJournals, entityType, 1,"), "Must pass real entry id");
    }

    @Test
    void gstComplianceCenterExposesPostMonthlyItcOffsetControlGatedByPermission() throws Exception {
        String fxml = Files.readString(Path.of("src/main/resources/fxml/pages/GstComplianceCenter.fxml"));
        assertTrue(fxml.contains("fx:id=\"btnPostGstGl\"") && fxml.contains("#postGstUtilizationToGl"));

        String src = Files.readString(Path.of("src/main/java/org/example/controller/GstComplianceController.java"));
        assertTrue(src.contains("PermissionService.allowed(\"GST_COMPLIANCE.POST_GL\")"));
        assertTrue(src.contains("/api/gst/gstr3b/"));
        assertTrue(src.contains("/post-utilization"));
    }
}
