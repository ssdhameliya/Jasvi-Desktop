package org.example.service;

import org.example.config.ConfigManager;
import org.example.config.WorkspaceManager;
import org.example.model.Party;
import org.example.model.Purchase;
import org.example.model.Sales;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MessageTemplateServiceTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setup() throws Exception {
        WorkspaceManager.configure(tempDir);
        ConfigManager.load();
    }

    @Test
    void tokenSubstitutionReplacesAllPlaceholders() {
        String template = "Hello {CustomerName}, invoice {DocumentNo} is dated {DocumentDate}. Total: {TotalAmount}. Balance: {BalanceAmount}.";
        Map<String, String> tokens = Map.of(
            "CustomerName", "Acme Corp",
            "DocumentNo", "INV-101",
            "DocumentDate", "04/10/2026",
            "TotalAmount", "₹ 5,000.00",
            "BalanceAmount", "₹ 2,000.00"
        );
        String formatted = MessageTemplateService.format(template, tokens);
        assertEquals("Hello Acme Corp, invoice INV-101 is dated 04/10/2026. Total: ₹ 5,000.00. Balance: ₹ 2,000.00.", formatted);
    }

    @Test
    void defaultTemplatesContainStandardPlaceholders() {
        for (MessageTemplateService.DocumentType type : MessageTemplateService.DocumentType.values()) {
            String subject = MessageTemplateService.defaultEmailSubject(type);
            String body = MessageTemplateService.defaultEmailBody(type);
            assertNotNull(subject);
            assertNotNull(body);
            assertTrue(subject.contains("{DocumentNo}") || subject.contains("{CompanyName}"));
            assertTrue(body.contains("{CustomerName}") || body.contains("{DocumentNo}"));
            assertTrue(body.contains("{Signature}"));
        }
    }

    @Test
    void formatSalesEmailUsesTokensAndSignature() {
        Sales sale = new Sales();
        sale.setInvoiceNo("INV-2026-999");
        sale.setInvoiceDate(LocalDate.of(2026, 10, 4));
        sale.setTotalAmount(15000.0);
        sale.setPaidAmount(5000.0);
        Party customer = new Party();
        customer.setName("Alpha Traders");
        sale.setCustomer(customer);

        MessageTemplateService.FormattedMessage msg = MessageTemplateService.formatSalesEmail(sale);
        assertNotNull(msg);
        assertNotNull(msg.subject());
        assertNotNull(msg.body());
        assertTrue(msg.subject().contains("INV-2026-999"));
        assertTrue(msg.body().contains("Alpha Traders"));
        assertTrue(msg.body().contains("INV-2026-999"));
    }

    @Test
    void formatPurchaseEmailUsesTokens() {
        Purchase purchase = new Purchase();
        purchase.setInvoiceNo("PO-2026-555");
        purchase.setInvoiceDate(LocalDate.of(2026, 10, 4));
        purchase.setTotalAmount(45000.0);
        purchase.setPaidAmount(0.0);
        Party supplier = new Party();
        supplier.setName("Mega Supplies Ltd");
        purchase.setSupplier(supplier);

        MessageTemplateService.FormattedMessage msg = MessageTemplateService.formatPurchaseEmail(purchase);
        assertNotNull(msg);
        assertTrue(msg.subject().contains("PO-2026-555"));
        assertTrue(msg.body().contains("Mega Supplies Ltd"));
    }

    @Test
    void customTemplateOverridePersistsAndFormats() {
        MessageTemplateService.setEmailTemplate(
            MessageTemplateService.DocumentType.SALES_INVOICE,
            "Custom Invoice Subject: {DocumentNo}",
            "Hi {CustomerName}, you owe {BalanceAmount} on {DocumentNo}."
        );
        ConfigManager.save();

        Sales sale = new Sales();
        sale.setInvoiceNo("INV-CUSTOM-1");
        sale.setTotalAmount(1200.0);
        sale.setPaidAmount(0.0);
        Party customer = new Party();
        customer.setName("Beta Co");
        sale.setCustomer(customer);

        MessageTemplateService.FormattedMessage msg = MessageTemplateService.formatSalesEmail(sale);
        assertEquals("Custom Invoice Subject: INV-CUSTOM-1", msg.subject());
        assertTrue(msg.body().contains("Hi Beta Co"));
        assertTrue(msg.body().contains("INV-CUSTOM-1"));
    }

    @Test
    void signatureBlockAppendedWhenNotExplicitInTemplate() {
        MessageTemplateService.setEmailTemplate(
            MessageTemplateService.DocumentType.SALES_INVOICE,
            "Invoice {DocumentNo}",
            "Dear {CustomerName},\nPlease find attached invoice."
        );
        ConfigManager.setWithoutSaving("email.signature", "Regards,\nBipin Patel\n+91 9999999999");
        ConfigManager.save();

        Sales sale = new Sales();
        sale.setInvoiceNo("INV-SIG-TEST");
        sale.setTotalAmount(500.0);
        Party customer = new Party();
        customer.setName("Gamma Traders");
        sale.setCustomer(customer);

        MessageTemplateService.FormattedMessage msg = MessageTemplateService.formatSalesEmail(sale);
        assertTrue(msg.body().contains("Regards,"));
        assertTrue(msg.body().contains("Bipin Patel"));
    }

    @Test
    void signatureImagePathRetrieval() {
        ConfigManager.setWithoutSaving("email.signature.image", "assets/email-signature.png");
        ConfigManager.save();
        assertEquals("assets/email-signature.png", MessageTemplateService.getSignatureImagePath());
    }

    @Test
    void formatExecutiveHtmlReflectsDynamicBrandingAndCompanySettings() {
        ConfigManager.setWithoutSaving("company.name", "Dynamic Enterprises LLP");
        ConfigManager.setWithoutSaving("company.tagline", "PRECISION ENGINEERING & AUTOMATION");
        ConfigManager.setWithoutSaving("company.phone", "+91 91234 56789");
        ConfigManager.setWithoutSaving("company.email", "billing@dynamic.in");
        ConfigManager.setWithoutSaving("company.website", "www.dynamic.in");
        ConfigManager.setWithoutSaving("company.address", "GIDC Vatva, Ahmedabad");
        ConfigManager.setWithoutSaving("branding.primaryColor", "#0f766e");
        BrandingService.invalidateSharedIdentity();

        String html = MessageTemplateService.formatExecutiveHtmlEmail(
                "Payment Receipt",
                "PAID",
                "INV-2026-9999",
                "15/11/2026",
                "Shree Hari Traders",
                50000.0,
                50000.0,
                0.0,
                "Payment Received: ₹50,000",
                true,
                "Bank of India",
                "1234567890",
                "BKID0001234",
                "Shree Hari Traders",
                "UTR987654321",
                "http://127.0.0.1:58080/payment-status?inv=INV-2026-9999&token=abc",
                false,
                null
        );

        assertNotNull(html);
        assertTrue(html.contains("Dynamic Enterprises LLP"));
        assertTrue(html.contains("PRECISION ENGINEERING &amp; AUTOMATION"));
        assertTrue(html.contains("#0f766e")); // Primary color
        assertTrue(html.contains("Beneficiary Bank (Dynamic Enterprises LLP)"));
        assertTrue(html.contains("GIDC Vatva, Ahmedabad"));
        assertTrue(html.contains("+91 91234 56789"));
        assertTrue(html.contains("billing@dynamic.in"));
        assertTrue(html.contains("www.dynamic.in"));
        assertFalse(html.contains("INDUSTRIAL MACHINERY &amp; ENGINEERING SOLUTIONS &bull; AHMEDABAD"));
        assertFalse(html.contains("Beneficiary Bank (Jasvi Industries)"));
    }

    @Test
    void sanitizePublicPortalUrlAutoHealsTypoAndProtocol() {
        assertEquals("https://api-uat.jasviindustries.in",
                MessageTemplateService.sanitizePublicPortalUrl("https:/api-uat.jasviinduestries.in"));
        assertEquals("https://api-uat.jasviindustries.in",
                MessageTemplateService.sanitizePublicPortalUrl("api-uat.jasviinduestries.in"));
        assertEquals("http://127.0.0.1:58080",
                MessageTemplateService.sanitizePublicPortalUrl("http:/127.0.0.1:58080/"));
    }

    @Test
    void htmlAndStandardFormatModesToggleRenderingPerGlobalAndTemplateOverride() {
        ConfigManager.setWithoutSaving("company.name", "Apex Castings Pvt Ltd");
        ConfigManager.setWithoutSaving("email.format.mode", "STANDARD");
        assertFalse(MessageTemplateService.shouldUseHtml("aging_collection"));
        assertFalse(MessageTemplateService.shouldUseHtmlForEntity("SALES_INVOICE"));

        var standardRendered = MessageTemplateService.renderEmailByKey(
                "gst_rule36_notice",
                Map.of(
                        "SupplierName", "Bharat Steels",
                        "InvoiceNo", "PUR-99",
                        "InvoiceDate", "26/11/2026",
                        "TaxableAmount", "10000",
                        "TaxAmount", "1800",
                        "CompanyGstin", "24AAAAA0000A1Z5"
                ),
                "GST Notice",
                "ITC",
                "#dc2626",
                "#991b1b"
        );
        assertFalse(standardRendered.body().contains("<!DOCTYPE html>"));
        assertTrue(standardRendered.body().contains("Bharat Steels"));
        assertTrue(standardRendered.body().contains("PUR-99"));

        // Per-template HTML override even while company default is STANDARD
        ConfigManager.setWithoutSaving("email.template.gst_rule36_notice.format", "HTML");
        assertTrue(MessageTemplateService.shouldUseHtml("gst_rule36_notice"));
        var overrideHtmlRendered = MessageTemplateService.renderEmailByKey(
                "gst_rule36_notice",
                Map.of(
                        "SupplierName", "Bharat Steels",
                        "InvoiceNo", "PUR-99",
                        "InvoiceDate", "26/11/2026",
                        "TaxableAmount", "10000",
                        "TaxAmount", "1800",
                        "CompanyGstin", "24AAAAA0000A1Z5"
                ),
                "GST Notice",
                "ITC",
                "#dc2626",
                "#991b1b"
        );
        assertTrue(overrideHtmlRendered.body().contains("<!DOCTYPE html>"));
        assertTrue(overrideHtmlRendered.body().contains("Bharat Steels"));
    }
}
