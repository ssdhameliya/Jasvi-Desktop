package org.example.tally;

import org.example.api.tally.TallyAutomationApiClient;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TallyDocumentParserTest {

    @Test
    void testWindowsOcrEngineAvailability() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            assertTrue(WindowsOcrEngine.isAvailable(), "Windows OCR must be reported as available on Windows OS");
        }
    }

    @Test
    void testImageDocumentParsingWhenSampleExists() {
        Path samplePath = Path.of(System.getProperty("user.home"),
                "Downloads", "All_30_Dummy_Bills_Complete_Bundle", "01_Images_PNG_JPG", "T08_MultiPage_Loose_Page2of2.png");
        File sampleFile = samplePath.toFile();
        if (!sampleFile.exists()) {
            return; // Skip if test bundle not in Downloads on this runner
        }

        TallyAutomationApiClient.BillRecordDto record =
                TallyDocumentParser.parseDocument(sampleFile, 1L, 1L, "GST");

        assertNotNull(record);
        assertEquals("NIF/2026/9901", record.invoiceNumber(), "Invoice number must be extracted from image voucher");
        assertEquals("24AAACN9012M1Z5", record.vendorGstin(), "Vendor GSTIN must be extracted and sanitized");
        assertNotNull(record.grandTotal());
        assertTrue(record.grandTotal().doubleValue() > 0, "Grand total must be non-zero");
        assertFalse(record.lineItems().isEmpty(), "Line items must be extracted from columnar table");
    }

    @Test
    void testFallbackParsingWhenFileCorruptOrEmpty() throws Exception {
        File temp = File.createTempFile("empty-bill-", ".png");
        try {
            TallyAutomationApiClient.BillRecordDto record =
                    TallyDocumentParser.parseDocument(temp, 10L, 1L, "Default Ledger");
            assertNotNull(record);
            assertNotNull(record.invoiceNumber());
            assertEquals("NEEDS_REVIEW", record.status());
        } finally {
            temp.delete();
        }
    }

    @Test
    void testMultiRateGstSlabsColumnarParsing() {
        List<String> ocrLines = List.of(
                "GST TAX INVOICE",
                "Supplies",
                "Annapurna Horeca",
                "GSTIN/UIN: 24AAACA3322PIZ6",
                "800.",
                "520.",
                "600.",
                "Bill NO : AHS/1108",
                "Bill Date : 01-11-2026",
                "Billed To : Shailesh Enterprise",
                "party GST : 24ABCDE1234FIZ5",
                "No",
                "Particulars / Item Name",
                "Roast Arabica Coffee Beans 1Kg",
                "Amul Unsalted Butter Bulk pack",
                "Comercial Espresso Cleaning Descale",
                "HSN/SAC",
                "0901",
                "€405",
                "3402",
                "Qty",
                "Unit",
                "KGS",
                "KGS",
                "LTR",
                "Taxable Total:",
                "Rate",
                "GST%",
                "Net Amt",
                "10400 .",
                "3000 .",
                "21400.00",
                "Remarks: Multi-Rate Slab -rest (5%, 12%, 18%)",
                "Total CGST (2.5% + + 9%): 1094.00",
                "Total SGST (2.5% + + 9%): 1094.00",
                "Net Payable (INR): 23588.00"
        );

        TallyAutomationApiClient.BillRecordDto record =
                TallyDocumentParser.parseFromTextLines(ocrLines, new File("T10_MultiRate_GST_Slabs.png"), 1L, 1L, "Annapurna Horeca");

        assertNotNull(record);
        assertEquals("AHS/1108", record.invoiceNumber(), "Invoice number must match");
        assertEquals("24AAACA3322P1Z6", record.vendorGstin(), "Vendor GSTIN must match and be sanitized");
        assertEquals(new BigDecimal("21400.00"), record.taxableAmount(), "Taxable amount must be 21400.00");
        assertEquals(new BigDecimal("1094.00"), record.cgstAmount(), "CGST must be 1094.00");
        assertEquals(new BigDecimal("1094.00"), record.sgstAmount(), "SGST must be 1094.00");
        assertEquals(new BigDecimal("23588.00"), record.grandTotal(), "Grand total must be 23588.00");
        assertEquals(new BigDecimal("100.00"), record.accuracyPercentage(), "Math cross-validation must be 100%");
        assertEquals("EXTRACTED", record.status(), "Status must be EXTRACTED");

        assertEquals(3, record.lineItems().size(), "Must extract exactly 3 line items");

        var item1 = record.lineItems().get(0);
        assertEquals("Roast Arabica Coffee Beans 1Kg", item1.extractedItemName());
        assertEquals("0901", item1.hsnSac());
        assertEquals(new BigDecimal("10"), item1.quantity());
        assertEquals("KGS", item1.uom());
        assertEquals(new BigDecimal("800.00"), item1.rate());
        assertEquals(new BigDecimal("8000.00"), item1.taxableAmount());
        assertEquals(new BigDecimal("5.00"), item1.taxRate());

        var item2 = record.lineItems().get(1);
        assertEquals("Amul Unsalted Butter Bulk pack", item2.extractedItemName());
        assertEquals("0405", item2.hsnSac());
        assertEquals(new BigDecimal("20"), item2.quantity());
        assertEquals("KGS", item2.uom());
        assertEquals(new BigDecimal("520.00"), item2.rate());
        assertEquals(new BigDecimal("10400.00"), item2.taxableAmount());
        assertEquals(new BigDecimal("12.00"), item2.taxRate());

        var item3 = record.lineItems().get(2);
        assertEquals("Comercial Espresso Cleaning Descale", item3.extractedItemName());
        assertEquals("3402", item3.hsnSac());
        assertEquals(new BigDecimal("5"), item3.quantity());
        assertEquals("LTR", item3.uom());
        assertEquals(new BigDecimal("600.00"), item3.rate());
        assertEquals(new BigDecimal("3000.00"), item3.taxableAmount());
        assertEquals(new BigDecimal("18.00"), item3.taxRate());
    }
}
