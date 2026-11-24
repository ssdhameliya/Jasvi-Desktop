package org.example.service;

import org.example.importing.ImportModuleRegistry;
import org.example.shared.statutory.GstinValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ImportServicePartyGstinTest {

    @Test
    void testPartyRegistryFieldsAndGuidance() {
        List<String> customerFields = ImportModuleRegistry.fields("Customers/CRM");
        assertEquals("party_code", customerFields.get(0));
        assertEquals("gstin", customerFields.get(1));
        assertEquals("name", customerFields.get(2));

        List<String> supplierFields = ImportModuleRegistry.fields("Suppliers/HRM");
        assertEquals("party_code", supplierFields.get(0));
        assertEquals("gstin", supplierFields.get(1));
        assertEquals("name", supplierFields.get(2));

        assertTrue(ImportModuleRegistry.requiredFields("Customers/CRM").containsAll(List.of("party_code", "name")));
        assertTrue(ImportModuleRegistry.requiredFields("Suppliers/HRM").containsAll(List.of("party_code", "name", "email")));

        String guidance = ImportModuleRegistry.identifierGuidance("Customers/CRM");
        assertTrue(guidance.contains("GSTIN"));
        assertTrue(guidance.contains("mock data"));
    }

    @Test
    void testStatutoryGstinValidationOnValidAndInvalidStrings() {
        // Valid authentic GSTIN
        var valid = GstinValidator.validate("24ANXPD3352N1ZK");
        assertTrue(valid.valid());
        assertTrue(valid.checksumValid());
        assertEquals("24", valid.stateCode());
        assertEquals("Gujarat", valid.stateName());

        // Invalid checksum typo
        var badChecksum = GstinValidator.validate("24ANXPD3352N1Z9");
        assertFalse(badChecksum.valid());
        assertFalse(badChecksum.checksumValid());
        assertTrue(badChecksum.message().contains("Luhn Checksum mismatch"));

        // Invalid length
        var shortGstin = GstinValidator.validate("24ANXPD3352");
        assertFalse(shortGstin.valid());
        assertTrue(shortGstin.message().contains("Incomplete GSTIN"));
    }

    @Test
    void testPartyImportRejectsBlankNameWhenPortalUnavailable(@TempDir Path tempDir) throws Exception {
        // CSV with GSTIN provided but Name blank, and unverified GSTIN (so portal cannot retrieve profile)
        Path csv = tempDir.resolve("customers_test.csv");
        String content = """
            party_code,gstin,name,contact_person,phone,email,address,opening_balance,is_active
            CUS-9901,24ANXPD3352N1ZK,,Mr. X,9876543210,test@example.com,,0,true
            """;
        Files.writeString(csv, content);

        Map<String, String> mapping = Map.of(
            "party_code", "party_code",
            "gstin", "gstin",
            "name", "name",
            "contact_person", "contact_person",
            "phone", "phone",
            "email", "email",
            "address", "address",
            "opening_balance", "opening_balance",
            "is_active", "is_active"
        );

        ImportService importService = new ImportService();
        // In dryRun, this row must fail pre-validation because Name is blank and live portal data is unavailable
        ImportService.ImportResult result = importService.importCustomers(
            csv, mapping, true, ImportService.ImportMode.CREATE_ONLY, (ImportService.ImportProgressCallback) null
        );

        assertNotNull(result);
        assertEquals(1, result.details.size());
        ImportService.ImportRowResult rowResult = result.details.get(0);
        assertEquals("FAILED", rowResult.status);
        assertTrue(rowResult.message.contains("Party Name is blank and Live GST Portal lookup is unavailable")
            || rowResult.message.contains("Zero Mock Data"));
    }

    @Test
    void testPartyImportRejectsInvalidChecksumGstin(@TempDir Path tempDir) throws Exception {
        // CSV with GSTIN having a Luhn checksum typo
        Path csv = tempDir.resolve("bad_gstin.csv");
        String content = """
            party_code,gstin,name,contact_person,phone,email,address,opening_balance,is_active
            CUS-9902,24ANXPD3352N1Z9,Some Customer,Mr. X,9876543210,test@example.com,Address,0,true
            """;
        Files.writeString(csv, content);

        Map<String, String> mapping = Map.of(
            "party_code", "party_code",
            "gstin", "gstin",
            "name", "name",
            "contact_person", "contact_person",
            "phone", "phone",
            "email", "email",
            "address", "address",
            "opening_balance", "opening_balance",
            "is_active", "is_active"
        );

        ImportService importService = new ImportService();
        ImportService.ImportResult result = importService.importCustomers(
            csv, mapping, true, ImportService.ImportMode.CREATE_ONLY, (ImportService.ImportProgressCallback) null
        );

        assertNotNull(result);
        assertEquals(1, result.details.size());
        ImportService.ImportRowResult rowResult = result.details.get(0);
        assertEquals("FAILED", rowResult.status);
        assertTrue(rowResult.message.contains("Invalid GSTIN") && rowResult.message.contains("Luhn Checksum mismatch"));
    }

    @Test
    void testPartyImportPassesWhenNameIsProvidedWithoutGstin(@TempDir Path tempDir) throws Exception {
        // Non-GST customer with authentic user-supplied name
        Path csv = tempDir.resolve("nongst_customer.csv");
        String content = """
            party_code,gstin,name,contact_person,phone,email,address,opening_balance,is_active
            CUS-9903,,Shree Ganesh Store,Ramesh Bhai,9876543210,ramesh@example.com,Nikol Ahmedabad,0,true
            """;
        Files.writeString(csv, content);

        Map<String, String> mapping = Map.of(
            "party_code", "party_code",
            "gstin", "gstin",
            "name", "name",
            "contact_person", "contact_person",
            "phone", "phone",
            "email", "email",
            "address", "address",
            "opening_balance", "opening_balance",
            "is_active", "is_active"
        );

        ImportService importService = new ImportService();
        ImportService.ImportResult result = importService.importCustomers(
            csv, mapping, true, ImportService.ImportMode.CREATE_ONLY, (ImportService.ImportProgressCallback) null
        );

        assertNotNull(result);
        assertEquals(1, result.details.size());
        ImportService.ImportRowResult rowResult = result.details.get(0);
        assertEquals("PASSED", rowResult.status);
        assertEquals("WOULD CREATE", rowResult.action);
    }
}
