package org.example.tally;

import org.example.api.tally.TallyAutomationApiClient;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;

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
}
