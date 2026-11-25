package org.example;

import org.example.bank.UniversalBankStatementParser;
import org.example.shared.BankNarrationParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BankStatementEnhancementTest {

    @Test
    void narrationParserDetectsIndianChannelsExtractsUtrAndCleansParty() {
        var upi = BankNarrationParser.parse("UPI/CR/431209871234/RAMESH TRADERS/SBIN/ramesh@oksbi/Invoice 104", "");
        assertEquals("UPI", upi.channel());
        assertEquals("431209871234", upi.utrOrRef());
        assertTrue(upi.hasUtrOrRef());
        assertTrue(upi.hasCounterparty());

        var neft = BankNarrationParser.parse("NEFT-HDFC0001234-N241122000123456-SUNRISE ENTERPRISE", "");
        assertEquals("NEFT", neft.channel());
        assertTrue(neft.hasUtrOrRef());
        assertFalse(neft.utrOrRef().isBlank());

        var imps = BankNarrationParser.parse("IMPS/P2A/432198765432/ACME CORP", "-");
        assertEquals("IMPS", imps.channel());
        assertEquals("432198765432", imps.utrOrRef());

        var rtgs = BankNarrationParser.parse("RTGS/UTIBR52024112200112233/DELTA STEEL", "");
        assertEquals("RTGS", rtgs.channel());
        assertTrue(rtgs.hasUtrOrRef());

        var chq = BankNarrationParser.parse("CHQ NO 452189 CLEARING STAR TRADERS", "");
        assertEquals("CHQ", chq.channel());
        assertEquals("452189", chq.utrOrRef());

        var charges = BankNarrationParser.parse("SMS ALERT CHARGES Q3", "");
        assertEquals("CHARGES", charges.channel());

        assertTrue(BankNarrationParser.isSyntheticRef("REF-1"));
        assertTrue(BankNarrationParser.isSyntheticRef("-"));
        assertTrue(BankNarrationParser.isSyntheticRef("N/A"));
        assertFalse(BankNarrationParser.isSyntheticRef("431209871234"));

        String rawUpi = "UPI-PRAJWAL KUMAR J-Q270110332@YBL-YESB0YBLUPI-609392003477-UPI";
        var parsedUpi = BankNarrationParser.parse(rawUpi, "0000609392003477");
        String compactUpi = BankNarrationParser.compactSummary(rawUpi, "0000609392003477", parsedUpi);
        assertFalse(compactUpi.contains("YESB0YBLUPI"));
        assertFalse(compactUpi.contains("609392003477"));
        assertTrue(compactUpi.contains("Q270110332@YBL"));

        String rawRent = "IMPS-609343722035-SHIVANAND K HANDYAL-CNRB-XXXXXXX2555-MARCH RENT 2026";
        var parsedRent = BankNarrationParser.parse(rawRent, "0000609343722035");
        String compactRent = BankNarrationParser.compactSummary(rawRent, "0000609343722035", parsedRent);
        assertTrue(compactRent.contains("MARCH RENT 2026"));
        assertFalse(compactRent.contains("XXXXXXX2555"));
    }

    @Test
    void universalParserExtractsRealUtrWhenReferenceColumnIsBlank(@TempDir Path tempDir) throws Exception {
        Path csv = tempDir.resolve("hdfc-nov.csv");
        Files.writeString(csv, """
                Account Statement
                JASVI INDUSTRIES
                Account No.,50200012345678
                Period,From 01/11/2026 To 30/11/2026
                Opening Balance,100000.00
                Closing Balance,109850.00
                Sl. No.,Transaction Date,Value Date,Description,Chq / Ref No.,Debit,Credit,Balance
                1,01-11-2026,01-11-2026,UPI/CR/431209871234/APEX ENGINEERING/HDFC/apex@okhdfcbank,,0.00,15000.00,115000.00
                2,02-11-2026,02-11-2026,CHQ NO 452189 CLEARING VENDOR,-,5000.00,0.00,110000.00
                3,03-11-2026,03-11-2026,MONTHLY FOLIO CHARGE,,150.00,0.00,109850.00
                """);

        var parsed = new UniversalBankStatementParser().parse(csv);
        assertEquals(3, parsed.rows().size());
        assertEquals("431209871234", parsed.rows().get(0).reference());
        assertEquals("452189", parsed.rows().get(1).reference());
        assertEquals("REF-3", parsed.rows().get(2).reference());
        assertEquals(100000.00, parsed.openingBalance(), 0.01);
        assertEquals(109850.00, parsed.closingBalance(), 0.01);
    }

    @Test
    void bankStatementFxmlAndControllerSatisfyUiArchitectureContracts() throws Exception {
        String fxml = Files.readString(Path.of("src/main/resources/fxml/pages/BankStatement.fxml"));
        assertTrue(fxml.contains("balanceVerificationBar"));
        assertTrue(fxml.contains("cmbChannel"));
        assertTrue(fxml.contains("btnExport"));
        assertTrue(fxml.contains("miExportBrsPdf"));
        assertTrue(fxml.contains("miExportBrsExcel"));
        assertFalse(fxml.contains("-fx-"), "BankStatement.fxml must stay free of inline styling");
        assertFalse(fxml.contains("btnSearch"), "Redundant Search button must be removed");
        assertFalse(fxml.contains("chkSelectAll"), "Redundant Select all visible bar must be removed");
        assertFalse(fxml.contains("lblSelected"), "Redundant selected count label must be removed");

        int balanceBarPos = fxml.indexOf("balanceVerificationBar");
        int batchStatusPos = fxml.indexOf("lblBatchStatus");
        int balanceStatusPos = fxml.indexOf("lblBalanceStatus");
        assertTrue(balanceBarPos > 0 && batchStatusPos > balanceBarPos && balanceStatusPos > batchStatusPos,
                "lblBatchStatus must sit on the balance verification strip immediately before lblBalanceStatus");

        int filterPos = fxml.indexOf("bank-recon-filter-panel");
        int progressPos = fxml.indexOf("reconciliationProgress");
        int rightDockSpacerPos = fxml.indexOf("<Region minWidth=\"8\" HBox.hgrow=\"ALWAYS\"/>", progressPos);
        int resetPos = fxml.indexOf("btnReset");
        int refreshPos = fxml.indexOf("btnRefresh");
        int tablePos = fxml.indexOf("bank-recon-table-panel");
        assertTrue(filterPos > 0 && progressPos > filterPos && rightDockSpacerPos > progressPos
                        && resetPos > rightDockSpacerPos && refreshPos > resetPos && tablePos > refreshPos,
                "Reset and Refresh must sit right-docked inside the filter panel after reconciliationProgress and before the table");

        int footerPos = fxml.indexOf("bank-recon-footer");
        int bulkIgnorePos = fxml.indexOf("btnBulkIgnore");
        int autoMatchPos = fxml.indexOf("btnAutoMatch");
        int manageRulesPos = fxml.indexOf("btnManageRules");
        int runRulesPos = fxml.indexOf("btnApplyRules");
        assertTrue(footerPos > 0 && bulkIgnorePos > footerPos && autoMatchPos > bulkIgnorePos
                        && manageRulesPos > autoMatchPos && runRulesPos > manageRulesPos,
                "Auto Match Suggestions, Rules, and Run Rules must sit in the bottom footer after bulk selection actions");

        String controller = Files.readString(Path.of("src/main/java/org/example/controller/BankStatementController.java"));
        assertTrue(controller.contains("exportFilteredExcel"));
        assertTrue(controller.contains("exportFilteredPdf"));
        assertTrue(controller.contains("exportBrsPdf"));
        assertTrue(controller.contains("exportBrsExcel"));
        assertTrue(controller.contains("BankStatementQuickEntryDialog"));
        assertTrue(controller.contains("bank-link-more-pill"));
        assertTrue(controller.contains("compactSummary"));
        assertTrue(controller.contains("setFixedCellSize(38.0)"));
        assertTrue(controller.contains("colDescription.setMinWidth(260)"));
    }
}
