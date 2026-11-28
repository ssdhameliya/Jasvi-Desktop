package org.example.shared.communication;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class NotificationTemplateCatalogTest {

    private static final Pattern FORBIDDEN_KEY_WORDS = Pattern.compile(".*(password|secret|token|credential).*");

    @Test
    void allCatalogKeysAreUniqueAndShareableWithoutForbiddenTerms() {
        List<NotificationTemplateCatalog.TemplateSpec> specs = NotificationTemplateCatalog.all();
        assertEquals(25, specs.size());

        Set<String> seen = new HashSet<>();
        for (var spec : specs) {
            assertTrue(seen.add(spec.key()), "Duplicate key: " + spec.key());
            assertTrue(spec.supportsEmail() || spec.supportsWhatsapp(), "Must support at least one channel: " + spec.key());
            assertFalse(FORBIDDEN_KEY_WORDS.matcher(spec.emailSubjectSettingKey().toLowerCase(Locale.ROOT)).matches(),
                    "Forbidden substring in subject setting key: " + spec.emailSubjectSettingKey());
            assertFalse(FORBIDDEN_KEY_WORDS.matcher(spec.emailBodySettingKey().toLowerCase(Locale.ROOT)).matches(),
                    "Forbidden substring in body setting key: " + spec.emailBodySettingKey());
            assertFalse(FORBIDDEN_KEY_WORDS.matcher(spec.emailFormatSettingKey().toLowerCase(Locale.ROOT)).matches(),
                    "Forbidden substring in format setting key: " + spec.emailFormatSettingKey());
            assertFalse(FORBIDDEN_KEY_WORDS.matcher(spec.whatsappBodySettingKey().toLowerCase(Locale.ROOT)).matches(),
                    "Forbidden substring in whatsapp setting key: " + spec.whatsappBodySettingKey());
            if (spec.supportsEmail()) {
                assertNotNull(spec.defaultEmailSubject());
                assertFalse(spec.defaultEmailSubject().isBlank());
                assertNotNull(spec.defaultEmailBody());
                assertFalse(spec.defaultEmailBody().isBlank());
            }
            if (spec.supportsWhatsapp()) {
                assertNotNull(spec.defaultWhatsappBody());
                assertFalse(spec.defaultWhatsappBody().isBlank());
            }
        }
    }

    @Test
    void roleAccessFilteringRestrictsAdminAndSuperAdminTemplates() {
        var staffEmail = NotificationTemplateCatalog.forChannelAndAccess(false, false, false);
        assertTrue(staffEmail.stream().noneMatch(NotificationTemplateCatalog.TemplateSpec::adminOnly));
        assertTrue(staffEmail.stream().noneMatch(NotificationTemplateCatalog.TemplateSpec::superAdminOnly));
        assertTrue(staffEmail.stream().anyMatch(s -> "aging_collection".equals(s.key())));
        assertTrue(staffEmail.stream().anyMatch(s -> "gst_rule36_notice".equals(s.key())));

        var adminEmail = NotificationTemplateCatalog.forChannelAndAccess(false, true, false);
        assertTrue(adminEmail.stream().anyMatch(s -> "auth_otp".equals(s.key())));
        assertTrue(adminEmail.stream().anyMatch(s -> "briefing_dispatch".equals(s.key())));
        assertTrue(adminEmail.stream().noneMatch(s -> "rogue_alert".equals(s.key())));

        var superEmail = NotificationTemplateCatalog.forChannelAndAccess(false, true, true);
        assertTrue(superEmail.stream().anyMatch(s -> "rogue_alert".equals(s.key())));
        assertTrue(superEmail.stream().anyMatch(s -> "license_activation".equals(s.key())));

        assertTrue(NotificationTemplateCatalog.requiresAdminRole("email.template.auth_otp.body"));
        assertFalse(NotificationTemplateCatalog.requiresAdminRole("email.template.sale.body"));
        assertTrue(NotificationTemplateCatalog.requiresSuperAdminRole("whatsapp.template.rogue_alert.body"));
        assertFalse(NotificationTemplateCatalog.requiresSuperAdminRole("whatsapp.template.security_lock_admin.body"));
    }

    @Test
    void tokenEngineFormatsSinglePassAndBuildsSafeHtmlCards() {
        String formatted = TokenTemplateEngine.format(
                "User={CustomerName}, Note={Note}, Unmatched={Unknown}",
                Map.of("CustomerName", "{Note}", "Note", "Actual")
        );
        assertEquals("User={Note}, Note=Actual, Unmatched={Unknown}", formatted);

        String escaped = TokenTemplateEngine.formatHtmlEscaped(
                "<p>{Name}</p><div>{Raw}</div>",
                Map.of("Name", "<script>alert(1)</script>", "Raw", "<b>OK</b>"),
                Set.of("Raw")
        );
        assertEquals("<p>&lt;script&gt;alert(1)&lt;/script&gt;</p><div><b>OK</b></div>", escaped);

        String card = TokenTemplateEngine.wrapInCardHtml(
                "#0284c7", "#0369a1", "OTP", "Verification Code",
                "• Code: *482910*\n• Valid: 10 mins\n<script>bad</script>",
                List.of(new TokenTemplateEngine.ActionButton("Open", "https://example.com?a=1&b=2", "#2563eb")),
                "Test Footer"
        );
        assertTrue(card.contains("<!DOCTYPE html>"));
        assertTrue(card.contains("482910"));
        assertFalse(card.contains("<script>bad</script>"));
        assertTrue(card.contains("&lt;script&gt;bad&lt;/script&gt;"));
        assertTrue(card.contains("https://example.com?a=1&amp;b=2"));
    }

    @Test
    void emailFormatModeParsesDisplayAndEnumValues() {
        assertEquals(NotificationTemplateCatalog.EmailFormatMode.STANDARD,
                NotificationTemplateCatalog.EmailFormatMode.parse("standard", NotificationTemplateCatalog.EmailFormatMode.HTML));
        assertEquals(NotificationTemplateCatalog.EmailFormatMode.STANDARD,
                NotificationTemplateCatalog.EmailFormatMode.parse("Standard Plain Text", NotificationTemplateCatalog.EmailFormatMode.HTML));
        assertEquals(NotificationTemplateCatalog.EmailFormatMode.HTML,
                NotificationTemplateCatalog.EmailFormatMode.parse("HTML Card View", NotificationTemplateCatalog.EmailFormatMode.STANDARD));
        assertEquals(NotificationTemplateCatalog.EmailFormatMode.INHERIT,
                NotificationTemplateCatalog.EmailFormatMode.parse("INHERIT", NotificationTemplateCatalog.EmailFormatMode.HTML));
    }

    @Test
    void signatureBlockHtmlMatchesManualExecutiveOrderAndEscapesUnsafeMarkup() {
        String sig = "Regards\nBipin Patel\n+91 72280 99500\nE - Mail   :   jasviindustries1989@gmail.com | marketing@jasviindustries.in";
        String addr = "52, Darshanvilla Park, Ahmedabad - 382345";
        String html = TokenTemplateEngine.signatureBlockHtml(sig, "cid:signatureImage", addr);

        assertTrue(html.contains("href=\"tel:+917228099500\""));
        assertTrue(html.contains("href=\"mailto:jasviindustries1989@gmail.com\""));
        assertTrue(html.contains("href=\"mailto:marketing@jasviindustries.in\""));
        assertFalse(html.contains("href=\"tel:382345\""), "6-digit PIN code must not be linkified as tel:");
        assertTrue(html.contains("max-width:100%"));
        assertFalse(html.contains("max-width:240px"));
        assertTrue(html.contains("<strong style=\"color:#0f2942;\">Office Address:</strong> - 52, Darshanvilla Park, Ahmedabad - 382345"));

        int textPos = html.indexOf("Bipin Patel");
        int imgPos = html.indexOf("cid:signatureImage");
        int addrPos = html.indexOf("Office Address:");
        assertTrue(textPos >= 0 && imgPos > textPos && addrPos > imgPos,
                "Signature order must be: signature text -> banner image -> Office Address");

        String duplicateAddrHtml = TokenTemplateEngine.signatureBlockHtml(
                "Regards\n" + addr + "\n<script>x</script>", "javascript:alert(1)", addr);
        assertFalse(duplicateAddrHtml.contains("<script>"));
        assertFalse(duplicateAddrHtml.contains("javascript:alert(1)"));
        assertEquals(duplicateAddrHtml.indexOf(addr), duplicateAddrHtml.lastIndexOf(addr));
    }
}
