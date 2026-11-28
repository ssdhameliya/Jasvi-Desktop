package org.example.service;

import org.example.config.ConfigManager;
import org.example.config.WorkspaceManager;
import org.example.shared.communication.TokenTemplateEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RichEmailTemplateHtmlTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void initConfig() throws Exception {
        WorkspaceManager.configure(tempDir);
        ConfigManager.load();
    }

    @AfterEach
    void resetInMemoryConfig() throws Exception {
        WorkspaceManager.configure(tempDir);
        ConfigManager.load();
    }

    @Test
    void formatOtpEmailHtmlUsesTableLayoutAndOtpCalloutBox() {
        ConfigManager.setWithoutSaving("company.name", "Jasvi Industries");
        String html = MessageTemplateService.formatOtpEmailHtml("finance@jasvi.in", "Two-Factor Check", "739201");

        assertTrue(html.contains("role=\"presentation\""));
        assertTrue(html.contains("ONE-TIME PASSWORD"));
        assertTrue(html.contains("739201"));
        assertTrue(html.contains("finance@jasvi.in"));
        assertTrue(html.contains("10 Minutes"));
    }

    @Test
    void formatExecutiveHtmlAdaptsSectionsPerDocumentTypeAndPreventsDuplicates() {
        ConfigManager.setWithoutSaving("company.name", "Jasvi Industries");
        ConfigManager.setWithoutSaving("payment.bankName", "ICICI Bank");
        ConfigManager.setWithoutSaving("payment.accountNumber", "123456789012");
        ConfigManager.setWithoutSaving("payment.ifsc", "ICIC0000421");
        ConfigManager.setWithoutSaving("payment.upiId", "jasvi@icici");

        var items = List.of(
                new MessageTemplateService.LineItemPreview(
                        "CNC Spindle Drive <script>bad()</script>", "8504", "2 Nos", "₹ 25,000.00", "18.0% (₹ 9,000.00)", "₹ 59,000.00")
        );

        // 1. Quotation: QUOTED FOR, VALID UNTIL, Estimated Tax, Grand Quote Total, Line Schedule, Bank/UPI
        var quoteCtx = new MessageTemplateService.RichEmailContext(
                "Zenith Manufacturing", "27 Nov 2026", "27 Dec 2026", "24AAACZ1111A1Z2",
                null, null, null, 50000.0, 9000.0, "CGST 9% + SGST 9%", items);
        String quoteHtml = MessageTemplateService.formatExecutiveHtmlEmail(
                "Quotation", "QUOTATION", "QTN-2026-019", "27 Nov 2026", "Zenith Manufacturing",
                59000.0, 0.0, 59000.0, "Please review our commercial quotation below.",
                false, "ICICI Bank", "123456789012", "ICIC0000421", "Zenith Manufacturing", "-",
                null, true, "cid:signatureImage", quoteCtx
        );
        assertTrue(quoteHtml.contains("role=\"presentation\""));
        assertTrue(quoteHtml.contains("QUOTED FOR"));
        assertTrue(quoteHtml.contains("VALID UNTIL"));
        assertTrue(quoteHtml.contains("Estimated Tax (GST)"));
        assertTrue(quoteHtml.contains("Grand Quote Total"));
        assertTrue(quoteHtml.contains("Itemized Line Schedule"));
        assertTrue(quoteHtml.contains("8504"));
        assertTrue(quoteHtml.contains("jasvi@icici"));
        assertFalse(quoteHtml.contains("<script>bad()</script>"));
        assertEquals(quoteHtml.indexOf("cid:signatureImage"), quoteHtml.lastIndexOf("cid:signatureImage"),
                "Signature CID image must appear only once");

        // 2. Unpaid Sales Invoice: BILLED TO, DUE DATE, Amount Received, Balance Due, Payment & Remittance Card, Safe Portal CTA
        String invoiceHtml = MessageTemplateService.formatExecutiveHtmlEmail(
                "Tax Invoice", "PARTIAL", "INV-2026-042", "27 Nov 2026", "Zenith Manufacturing",
                59000.0, 20000.0, 39000.0, "Please settle remaining invoice balance.",
                false, "ICICI Bank", "123456789012", "ICIC0000421", "Zenith Manufacturing", "-",
                "https://erp.jasviindustries.in/payment-status?inv=INV-2026-042&token=abc", true, "cid:signatureImage", quoteCtx
        );
        assertTrue(invoiceHtml.contains("BILLED TO"));
        assertTrue(invoiceHtml.contains("DUE DATE"));
        assertTrue(invoiceHtml.contains("Amount Received"));
        assertTrue(invoiceHtml.contains("Balance Due"));
        assertTrue(invoiceHtml.contains("Payment &amp; Remittance Instructions")
                || invoiceHtml.contains("Payment & Remittance Instructions"));
        assertTrue(invoiceHtml.contains("VIEW LIVE PAYMENT &amp; INVOICE STATUS"));

        // 3. Sales Return: ORIGINAL DOC, Return / Credit Value, safe URL blocking
        var returnCtx = MessageTemplateService.RichEmailContext.forSettlement(
                "Zenith Manufacturing", "27 Nov 2026", null, "Defective Bearing", null, "INV-2026-042", null, null, null, items);
        String returnHtml = MessageTemplateService.formatExecutiveHtmlEmail(
                "Sales Return", "APPROVED", "SR-2026-004", "27 Nov 2026", "Zenith Manufacturing",
                12000.0, 12000.0, 0.0, "Credit note approved.",
                false, "", "", "", "Zenith Manufacturing", "-",
                "javascript:alert(1)", false, null, returnCtx
        );
        assertTrue(returnHtml.contains("ORIGINAL DOC"));
        assertTrue(returnHtml.contains("INV-2026-042"));
        assertTrue(returnHtml.contains("Return / Credit Value"));
        assertFalse(returnHtml.contains("javascript:alert(1)"));
    }

    @Test
    void renderRichEmailByKeyHonorsHtmlAndStandardModes() {
        ConfigManager.setWithoutSaving("email.format.mode", "HTML");
        ConfigManager.setWithoutSaving("email.template.gst_rule36_notice.format", "HTML");

        String split = TokenTemplateEngine.infoSplitPanelHtml("Comparison", "Books", "#0369a1",
                List.of(new TokenTemplateEngine.KeyValueRow("Invoice No", "PUR-88", true)),
                "GSTR-2B", "#dc2626",
                List.of(new TokenTemplateEngine.KeyValueRow("Status", "MISSING IN GSTR-2B", false)));

        var tokens = Map.of(
                "SupplierName", "Apex Steel",
                "SupplierGstin", "24AAAAA0000A1Z5",
                "InvoiceNo", "PUR-88",
                "InvoiceDate", "27 Nov 2026",
                "TaxableAmount", "₹ 1,00,000.00",
                "TaxAmount", "₹ 18,000.00",
                "GstBreakup", "CGST 9% | SGST 9%",
                "RegisterStatus", "MISSING IN GSTR-2B",
                "CompanyGstin", "24ABCDE1234F1Z5"
        );
        var spec = new TokenTemplateEngine.RichCardSpec(
                "#b91c1c", "#7f1d1d", "GST COMPLIANCE", "MISSING IN GSTR-2B", "#dc2626",
                "GST Rule 36(4) Notice", null, "Rule 36(4) Discrepancy",
                List.of(new TokenTemplateEngine.MetaColumn("INVOICE #", "PUR-88")),
                null, List.of(split), List.of(), "Jasvi Tax Compliance", null
        );
        var htmlResult = MessageTemplateService.renderRichEmailByKey("gst_rule36_notice", tokens, spec);
        assertTrue(htmlResult.body().contains("role=\"presentation\""));
        assertTrue(htmlResult.body().contains("MISSING IN GSTR-2B"));

        ConfigManager.setWithoutSaving("email.template.gst_rule36_notice.format", "STANDARD");
        var plainResult = MessageTemplateService.renderRichEmailByKey("gst_rule36_notice", tokens, spec);
        assertFalse(plainResult.body().contains("<table"));
        assertTrue(plainResult.body().contains("PUR-88"));
    }

    @Test
    void executiveAndCardEmailsRenderSettingsSignatureTextBannerImageAndOfficeAddressOnce() {
        ConfigManager.setWithoutSaving("company.name", "Jasvi Creation");
        ConfigManager.setWithoutSaving("company.address", "52, Darshanvilla Park, Near Gopal Chowk, Ahmedabad - 382345");
        ConfigManager.setWithoutSaving("company.phone", "9099939193");
        ConfigManager.setWithoutSaving("company.email", "ssdhameliya@gmail.com");
        ConfigManager.setWithoutSaving("email.signature",
                "Regards\nBipin Patel\n+91 72280 99500\nE - Mail   :   jasviindustries1989@gmail.com | marketing@jasviindustries.in");
        ConfigManager.setWithoutSaving("email.signature.attachToEmail", "true");

        var receipt = MessageTemplateService.formatPaymentReceiptEmail(
                "Shailesh", "RCP-101", java.time.LocalDate.of(2026, 11, 28),
                50000.0, 50000.0, 0.0, "UTR12345", "28 Nov 2026", "NEFT Settlement");

        String html = MessageTemplateService.formatExecutiveHtmlEmail(
                "Payment Receipt", "FULLY PAID", "RCP-101", "28 Nov 2026", "Shailesh",
                50000.0, 50000.0, 0.0, receipt.body(),
                true, "State Bank of India", "20104492473", "SBIN0000001", "Shailesh", "UTR12345",
                "https://erp.jasviindustries.in/status/RCP-101", true, "cid:signatureImage",
                MessageTemplateService.RichEmailContext.empty()
        );

        assertEquals(html.indexOf("Bipin Patel"), html.lastIndexOf("Bipin Patel"),
                "Expanded {Signature} text must be moved cleanly to bottom signature block without duplicating above KPIs");
        assertEquals(html.indexOf("52, Darshanvilla Park"), html.lastIndexOf("52, Darshanvilla Park"),
                "Office Address must appear once under the signature image instead of duplicating in ERP footer");
        assertTrue(html.contains("max-width:100%"));
        assertFalse(html.contains("max-width:240px"));

        int ctaPos = html.indexOf("VIEW LIVE PAYMENT");
        int namePos = html.indexOf("Bipin Patel");
        int mailtoPos = html.indexOf("mailto:jasviindustries1989@gmail.com");
        int imgPos = html.indexOf("cid:signatureImage");
        int addrPos = html.indexOf("Office Address:");
        int footerPos = html.indexOf("Generated automatically by Jasvi Creation ERP");
        assertTrue(ctaPos > 0 && namePos > ctaPos && mailtoPos > namePos && imgPos > mailtoPos && addrPos > imgPos && footerPos > addrPos,
                "Signature text -> links -> banner image -> Office Address must appear in exact manual order above ERP footer");

        String cardHtml = MessageTemplateService.wrapPlainBodyInCardHtml(
                "DISPATCH", "General Letter",
                "Please find the attached note.\n\nRegards\nBipin Patel\n+91 72280 99500",
                true, "data:image/png;base64,iVBORw0KGgo=");
        assertEquals(cardHtml.indexOf("Bipin Patel"), cardHtml.lastIndexOf("Bipin Patel"));
        assertTrue(cardHtml.indexOf("Bipin Patel") < cardHtml.indexOf("data:image/png;base64,"));
        assertTrue(cardHtml.indexOf("data:image/png;base64,") < cardHtml.indexOf("Office Address:"));
    }
}
