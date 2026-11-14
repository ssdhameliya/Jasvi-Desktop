package org.example.server.runtime;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public live payment and invoice verification portal endpoint.
 * Allows customers to securely view the real-time settlement status
 * of their Tax Invoices via cryptographic HMAC-SHA256 link tokens.
 * Delegates all business logic and persistence retrieval to PublicPaymentStatusService.
 */
@RestController
@RequestMapping({"/public/payment-status", "/api/public/payment-status"})
public class PublicPaymentStatusController {

    private final PublicPaymentStatusService service;

    public PublicPaymentStatusController(PublicPaymentStatusService service) {
        this.service = service;
    }

    @GetMapping(produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> renderStatus(
            @RequestParam(name = "inv", required = false) String invoiceNo,
            @RequestParam(name = "token", required = false) String token,
            @RequestParam(name = "verifyInvoiceNo", required = false) String verifyInvoiceNo,
            @RequestParam(name = "verifyDate", required = false) String verifyDate,
            @RequestParam(name = "verifyAmount", required = false) String verifyAmount) {
        return service.renderPublicPaymentStatus(invoiceNo, token, verifyInvoiceNo, verifyDate, verifyAmount);
    }

    @org.springframework.web.bind.annotation.PostMapping(produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> verifyAndRenderStatus(
            @RequestParam(name = "inv", required = false) String invoiceNo,
            @RequestParam(name = "token", required = false) String token,
            @RequestParam(name = "verifyInvoiceNo", required = false) String verifyInvoiceNo,
            @RequestParam(name = "verifyDate", required = false) String verifyDate,
            @RequestParam(name = "verifyAmount", required = false) String verifyAmount) {
        return service.renderPublicPaymentStatus(invoiceNo, token, verifyInvoiceNo, verifyDate, verifyAmount);
    }
}
