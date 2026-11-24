package org.example.architecture;

import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.example.config.SettingsFieldSupport;
import org.example.document.DocumentLookupPolicy;
import org.example.document.DocumentChargeDialog;
import org.example.documentstudio.service.ExcelSelectionPolicy;
import org.example.documentstudio.service.ExcelDimensionPolicy;
import org.example.documentstudio.service.ExcelWorkbookHistory;
import org.example.importing.ImportMappingSupport;
import org.example.importing.ImportDocumentPolicy;
import org.example.importing.ImportMergePolicy;
import org.example.importing.ImportModuleRegistry;
import org.example.importing.ImportValueParser;
import org.example.model.Item;
import org.example.model.Party;
import org.example.model.SalesCharge;
import org.example.util.ProfessionalDocumentFormatSupport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ArchitectureRefactorPolicyTest {
    @Test void importRegistryKeepsRequiredBusinessIdentifiers() {
        assertTrue(ImportModuleRegistry.requiredFields("Customers/CRM").containsAll(List.of("party_code", "name")));
        assertTrue(ImportModuleRegistry.requiredFields("Sales").containsAll(List.of("invoice_no", "party_code", "item_code")));
        assertEquals("/fxml/pages/BankStatement.fxml", ImportModuleRegistry.target("Bank Statement"));
    }

    @Test void importMappingPreservesKnownAliases() {
        Map<String,String> mapped = ImportMappingSupport.autoMap(
            List.of("party_code", "gst", "invoice_no"),
            List.of("Customer Code", "GST Rate", "Bill No"));
        assertEquals("Customer Code", mapped.get("party_code"));
        assertEquals("GST Rate", mapped.get("gst"));
        assertEquals("Bill No", mapped.get("invoice_no"));
    }

    @Test void importValueParsingIsStrictAndDeterministic() {
        assertEquals(1234.5, ImportValueParser.number("1,234.50"), 0.0001);
        assertEquals(15, ImportValueParser.termDays("15 Days"));
        assertThrows(IllegalArgumentException.class, () -> ImportValueParser.positive("0", "quantity"));
    }

    @Test void itemMergePreservesInventoryBaselineAndIdentity() {
        Item existing = new Item(); existing.setId(7); existing.setRowVersion(4); existing.setOpeningStock(55); existing.setReservedStock(8); existing.setDescription("Existing");
        Item incoming = new Item(); incoming.setOpeningStock(999); incoming.setDescription("");
        ImportMergePolicy.mergeItemNonBlank(incoming, existing);
        assertEquals(7, incoming.getId()); assertEquals(4, incoming.getRowVersion());
        assertEquals(55, incoming.getOpeningStock(), 0.001); assertEquals(0, incoming.getReservedStock(), 0.001);
        assertEquals("Existing", incoming.getDescription());
    }

    @Test void partyMergeDoesNotEraseNonBlankExistingFields() {
        Party existing = new Party(); existing.setId(9); existing.setRowVersion(2); existing.setName("ABC"); existing.setGstin("24ABCDE1234F1Z5");
        Party incoming = new Party(); incoming.setName(""); incoming.setGstin(null);
        ImportMergePolicy.mergePartyNonBlank(incoming, existing);
        assertEquals("ABC", incoming.getName()); assertEquals("24ABCDE1234F1Z5", incoming.getGstin());
        assertEquals(9, incoming.getId()); assertEquals(2, incoming.getRowVersion());
    }

    @Test void sharedDocumentLookupPreservesDisplayAndGstSuggestion() {
        Item item = new Item(); item.setItemCode("IT001"); item.setDescription("Pipe"); item.setGst(18);
        assertEquals("IT001 - Pipe", DocumentLookupPolicy.itemDisplay(item));
        assertEquals("IGST", DocumentLookupPolicy.suggestedGstType("24AAAAA0000A1Z5", "27BBBBB0000B1Z5", List.of("GST", "IGST")).orElseThrow());
    }

    @Test void settingsFormattingRemainsNullSafe() {
        assertEquals("", SettingsFieldSupport.text(null));
        assertEquals("ABC", SettingsFieldSupport.upper(" abc "));
        assertNull(SettingsFieldSupport.parseDate("not-a-date"));
    }

    @Test void excelSelectionGeometryIsPureAndNormalized() {
        assertEquals(0, ExcelSelectionPolicy.clamp(-5, 26));
        assertEquals(25, ExcelSelectionPolicy.clamp(100, 26));
        CellRangeAddress range = ExcelSelectionPolicy.range(5, 4, 2, 1, 0, 0);
        assertEquals(2, range.getFirstRow()); assertEquals(5, range.getLastRow());
        assertEquals(1, range.getFirstColumn()); assertEquals(4, range.getLastColumn());
    }

    @Test void excelHistoryProvidesUndoRedoSnapshots() throws Exception {
        try (XSSFWorkbook book = new XSSFWorkbook()) {
            var sheet = book.createSheet("Sheet1"); sheet.createRow(0).createCell(0).setCellValue("before");
            ExcelWorkbookHistory history = new ExcelWorkbookHistory(3); history.checkpoint(book);
            sheet.getRow(0).getCell(0).setCellValue("after");
            assertNotNull(history.undo(book)); assertTrue(history.canRedo());
        }
    }

    @Test void professionalDocumentFormattingIsStable() {
        assertEquals("1,234.50", ProfessionalDocumentFormatSupport.money(1234.5));
        assertEquals("2", ProfessionalDocumentFormatSupport.quantity(2));
        assertEquals("2.125", ProfessionalDocumentFormatSupport.quantity(2.125));
        assertEquals("NA", ProfessionalDocumentFormatSupport.pdfValue(""));
    }

    @Test void sharedChargeValidationRejectsDuplicateAndInvalidRows() {
        SalesCharge first = new SalesCharge(); first.setChargeType("Freight"); first.setAmount(100);
        SalesCharge duplicate = new SalesCharge(); duplicate.setChargeType("Freight"); duplicate.setAmount(50);
        assertNull(DocumentChargeDialog.validateSales(List.of(first)));
        assertTrue(DocumentChargeDialog.validateSales(List.of(first, duplicate)).toLowerCase().contains("same charge"));
        SalesCharge invalid = new SalesCharge(); invalid.setChargeType("Packing"); invalid.setAmount(0);
        assertTrue(DocumentChargeDialog.validateSales(List.of(invalid)).toLowerCase().contains("greater than zero"));
    }

    @Test void excelDimensionConversionsRemainBounded() throws Exception {
        try (XSSFWorkbook book = new XSSFWorkbook()) {
            var sheet = book.createSheet("Sheet1");
            sheet.createRow(0).createCell(0).setCellValue("wrapped text");
            assertTrue(ExcelDimensionPolicy.columnWidthPixels(sheet, 0) >= 24);
            assertTrue(ExcelDimensionPolicy.pixelsToColumnWidth(120) >= 256);
            assertTrue(ExcelDimensionPolicy.pixelsToRowPoints(30) > 0);
            assertTrue(ExcelDimensionPolicy.estimateAutoRowHeightPoints(sheet, 0, cell -> cell.getStringCellValue()) >= 15);
        }
    }

    @Test void importDocumentTaxDescriptionRemainsStable() {
        assertEquals("IGST 18.00% calculated from line values", ImportDocumentPolicy.taxDescription("IGST", 18));
        assertEquals("GST 18.00% calculated as CGST 9.00% + SGST 9.00%", ImportDocumentPolicy.taxDescription("GST", 18));
    }
}
