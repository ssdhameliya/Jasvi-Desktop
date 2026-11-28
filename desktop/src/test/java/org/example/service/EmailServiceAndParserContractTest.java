package org.example.service;

import org.example.bank.UniversalBankStatementParser;
import org.example.shared.communication.NotificationTemplateCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EmailServiceAndParserContractTest {

    @Test
    void parseOptionalRecipientListFiltersInvalidAndDeduplicatesAddresses() {
        List<String> parsed = EmailService.parseOptionalRecipientList(
                "accounts@example.com; invalid_addr , CFO@example.com, accounts@example.com"
        );
        assertEquals(List.of("accounts@example.com", "CFO@example.com"), parsed);
        assertTrue(EmailService.parseOptionalRecipientList("   ").isEmpty());
    }

    @Test
    void bankStatementParserUsesNonHardcodedHolderFallbackWhenMetadataAbsent(@TempDir Path tempDir) throws Exception {
        Path csv = tempDir.resolve("hdfc_stmt.csv");
        Files.writeString(csv, """
                Bank: HDFC Bank
                Account Number: 50200012345678
                Date,Narration,Chq / Ref No,Debit,Credit,Balance
                20-11-2026,NEFT SHREE HARI TRADERS,UTR20261120001,0.00,50000.00,150000.00
                """);

        UniversalBankStatementParser.ParsedStatement parsed = new UniversalBankStatementParser().parse(csv);

        assertNotNull(parsed);
        assertNotEquals("Jasvi Industries Pvt Ltd", parsed.accountHolder(),
                "Bank statement parser must not fall back to a hardcoded commercial tenant name");
        assertFalse(parsed.rows().isEmpty());
    }

    @Test
    void catalogIncludesPhase1AndPhase2EmailAndWhatsappTemplates() {
        assertEquals(25, NotificationTemplateCatalog.all().size());
        assertTrue(NotificationTemplateCatalog.byKey("bank_unreconciled_digest").isPresent());
        assertTrue(NotificationTemplateCatalog.byKey("subscription_expiry_warning").isPresent());
        assertTrue(NotificationTemplateCatalog.byKey("subscription_renewal_receipt").isPresent());
    }
}
