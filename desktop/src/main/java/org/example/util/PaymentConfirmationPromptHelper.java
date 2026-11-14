package org.example.util;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import org.example.api.support.SupportApiClient;
import org.example.config.ConfigManager;
import org.example.controller.EmailTransmissionDialog;
import org.example.model.Purchase;
import org.example.model.Sales;
import org.example.service.EmailService;
import org.example.service.ManagedInvoicePdfService;
import org.example.service.MessageTemplateService;
import org.example.service.SessionService;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Locale;

/**
 * Universal payment confirmation, remittance advice, and refund email prompt helper.
 * Provides consistent user prompt and transmission flow across:
 * 1. Sales Invoices (Customer Payment Receipts)
 * 2. Purchase Bills (Supplier Remittance Advice)
 * 3. Sales & Purchase Returns (Refund Settlement Confirmations)
 */
public final class PaymentConfirmationPromptHelper {

    private static final SupportApiClient supportApi = new SupportApiClient();

    private PaymentConfirmationPromptHelper() {}

    public static void promptAndTransmit(
            Node ownerNode,
            Sales sale,
            double paymentAmount,
            String paymentMode,
            String referenceNo,
            LocalDate paymentDate,
            String notes) {

        if (!ConfigManager.getBoolean("bank.paymentConfirmationEmail.enabled", true)) {
            return;
        }
        if (sale == null || sale.getCustomer() == null || sale.getCustomer().getEmail() == null || sale.getCustomer().getEmail().isBlank()) {
            return;
        }

        final String customerName = sale.getCustomer().getName() == null ? "Valued Customer" : sale.getCustomer().getName().trim();
        final String customerEmail = sale.getCustomer().getEmail().trim();
        final String invoiceNo = sale.getInvoiceNo();
        final double amount = paymentAmount;
        final String safeMode = (paymentMode == null || paymentMode.isBlank()) ? "Payment" : paymentMode.trim();
        final String safeRef = (referenceNo == null || referenceNo.isBlank()) ? "-" : referenceNo.trim();
        final LocalDate safeDate = paymentDate == null ? BusinessClock.today() : paymentDate;
        final String safeNotes = (notes == null || notes.isBlank()) ? ("Payment received for " + invoiceNo) : notes.trim();

        Platform.runLater(() -> {
            Alert alert = new OwnedAlert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Payment Confirmation Email");
            alert.setHeaderText("Payment Receipt & Confirmation");
            alert.setContentText("Payment of " + formatMoney(amount) + " was successfully recorded for Invoice "
                    + invoiceNo + " (" + customerName + ").\n\nWould you like to transmit an official Payment Confirmation Email to " + customerEmail + "?");

            ButtonType btnSend = new ButtonType("Transmit Receipt", ButtonBar.ButtonData.OK_DONE);
            alert.getButtonTypes().setAll(btnSend, ButtonType.CANCEL);

            var choice = alert.showAndWait();
            if (choice.isPresent() && choice.get() == btnSend) {
                openSaleTransmissionDialog(ownerNode, sale, amount, safeMode, safeRef, safeDate, safeNotes);
            }
        });
    }

    public static void promptAndTransmitPurchase(
            Node ownerNode,
            Purchase purchase,
            double paymentAmount,
            String paymentMode,
            String referenceNo,
            LocalDate paymentDate,
            String notes) {

        if (!ConfigManager.getBoolean("bank.paymentConfirmationEmail.enabled", true)) {
            return;
        }
        if (purchase == null || purchase.getSupplier() == null || purchase.getSupplier().getEmail() == null || purchase.getSupplier().getEmail().isBlank()) {
            return;
        }

        final String supplierName = purchase.getSupplier().getName() == null ? "Valued Supplier" : purchase.getSupplier().getName().trim();
        final String supplierEmail = purchase.getSupplier().getEmail().trim();
        final String invoiceNo = purchase.getInvoiceNo();
        final double amount = paymentAmount;
        final String safeMode = (paymentMode == null || paymentMode.isBlank()) ? "Bank Transfer" : paymentMode.trim();
        final String safeRef = (referenceNo == null || referenceNo.isBlank()) ? "-" : referenceNo.trim();
        final LocalDate safeDate = paymentDate == null ? BusinessClock.today() : paymentDate;
        final String safeNotes = (notes == null || notes.isBlank()) ? ("Payment disbursed for Bill #" + invoiceNo) : notes.trim();

        Platform.runLater(() -> {
            Alert alert = new OwnedAlert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Supplier Remittance Advice");
            alert.setHeaderText("Payment Remittance Advice");
            alert.setContentText("Disbursement of " + formatMoney(amount) + " was recorded for Purchase Bill "
                    + invoiceNo + " (" + supplierName + ").\n\nWould you like to transmit an official Remittance Advice Email to " + supplierEmail + "?");

            ButtonType btnSend = new ButtonType("Transmit Remittance", ButtonBar.ButtonData.OK_DONE);
            alert.getButtonTypes().setAll(btnSend, ButtonType.CANCEL);

            var choice = alert.showAndWait();
            if (choice.isPresent() && choice.get() == btnSend) {
                openPurchaseTransmissionDialog(ownerNode, purchase, amount, safeMode, safeRef, safeDate, safeNotes);
            }
        });
    }

    public static void promptAndTransmitReturn(
            Node ownerNode,
            String returnNo,
            String partyName,
            String partyEmail,
            String originalDocNo,
            String returnType,
            double refundAmount,
            double remainingBalance,
            String mode,
            String ref,
            LocalDate date,
            String notes) {

        if (!ConfigManager.getBoolean("bank.paymentConfirmationEmail.enabled", true)) {
            return;
        }
        if (partyEmail == null || partyEmail.isBlank()) {
            return;
        }

        final String pName = partyName == null || partyName.isBlank() ? "Valued Partner" : partyName.trim();
        final String pEmail = partyEmail.trim();
        final String rNo = returnNo == null ? "" : returnNo.trim();
        final String rType = returnType == null ? "Return" : returnType.trim();
        final double amount = refundAmount;
        final String safeMode = (mode == null || mode.isBlank()) ? "Bank Transfer" : mode.trim();
        final String safeRef = (ref == null || ref.isBlank()) ? "-" : ref.trim();
        final LocalDate safeDate = date == null ? BusinessClock.today() : date;
        final String safeNotes = notes == null ? "" : notes.trim();

        Platform.runLater(() -> {
            Alert alert = new OwnedAlert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Refund Settlement Confirmation");
            alert.setHeaderText("Refund Settlement Receipt");
            alert.setContentText("Refund of " + formatMoney(amount) + " was processed for "
                    + rType + " " + rNo + " (" + pName + ").\n\nWould you like to transmit an official Settlement Confirmation Email to " + pEmail + "?");

            ButtonType btnSend = new ButtonType("Transmit Settlement", ButtonBar.ButtonData.OK_DONE);
            alert.getButtonTypes().setAll(btnSend, ButtonType.CANCEL);

            var choice = alert.showAndWait();
            if (choice.isPresent() && choice.get() == btnSend) {
                openReturnTransmissionDialog(ownerNode, rNo, pName, pEmail, originalDocNo, rType, amount, remainingBalance, safeMode, safeRef, safeDate, safeNotes);
            }
        });
    }

    private static void openSaleTransmissionDialog(
            Node ownerNode,
            Sales sale,
            double amountPaid,
            String paymentMode,
            String referenceNo,
            LocalDate paymentDate,
            String notes) {

        try {
            double total = sale.getTotalAmount();
            double previouslyPaid = sale.getPaidAmount();
            double balance = Math.max(0.0, total - (previouslyPaid + amountPaid));
            String dateText = BusinessClock.formatDate(paymentDate);
            String narration = paymentMode + (referenceNo.equals("-") ? "" : " • Ref: " + referenceNo) + " • " + notes;

            var msg = MessageTemplateService.formatPaymentReceiptEmail(
                    sale.getCustomer().getName(),
                    sale.getInvoiceNo(),
                    sale.getInvoiceDate() == null ? BusinessClock.today() : sale.getInvoiceDate(),
                    total,
                    amountPaid,
                    balance,
                    referenceNo,
                    dateText,
                    narration
            );

            String statusUrl = MessageTemplateService.buildPublicPaymentStatusUrl(sale.getInvoiceNo(), total);
            String sigPathStr = MessageTemplateService.isAttachSignatureToEmail() ? MessageTemplateService.getSignatureImagePath() : null;
            Path sigPath = (sigPathStr != null && !sigPathStr.isBlank()) ? Path.of(sigPathStr) : null;

            Path pdf = null;
            try {
                pdf = ManagedInvoicePdfService.sales(sale);
            } catch (Exception ignored) {}

            String statusBadge = balance <= 0.009 ? "PAID" : "PARTIAL";

            var draft = new EmailTransmissionDialog.EmailDraft(
                    "Transmit Payment Confirmation Email",
                    "Direct delivery of payment receipt & confirmation to customer",
                    "Payment Receipt",
                    sale.getInvoiceNo(),
                    sale.getCustomer().getEmail().trim(),
                    msg.subject(),
                    msg.body(),
                    pdf,
                    sigPath,
                    "accounts",
                    statusUrl,
                    total,
                    amountPaid,
                    balance,
                    statusBadge,
                    "SALE",
                    sale.getId()
            );

            showAndTransmit(ownerNode, draft, "Payment Confirmation");
        } catch (Exception ex) {
            AppDialogService.error(ownerNode, "Error", ex.getMessage() == null ? "Failed to prepare payment confirmation email." : ex.getMessage());
        }
    }

    private static void openPurchaseTransmissionDialog(
            Node ownerNode,
            Purchase purchase,
            double amountPaid,
            String paymentMode,
            String referenceNo,
            LocalDate paymentDate,
            String notes) {

        try {
            double total = purchase.getTotalAmount();
            double previouslyPaid = purchase.getPaidAmount();
            double balance = Math.max(0.0, total - (previouslyPaid + amountPaid));
            String dateText = BusinessClock.formatDate(paymentDate);

            var msg = MessageTemplateService.formatPurchaseRemittanceAdviceEmail(
                    purchase.getSupplier().getName(),
                    purchase.getInvoiceNo(),
                    purchase.getInvoiceDate() == null ? BusinessClock.today() : purchase.getInvoiceDate(),
                    total,
                    amountPaid,
                    balance,
                    referenceNo,
                    dateText,
                    paymentMode,
                    notes
            );

            String sigPathStr = MessageTemplateService.isAttachSignatureToEmail() ? MessageTemplateService.getSignatureImagePath() : null;
            Path sigPath = (sigPathStr != null && !sigPathStr.isBlank()) ? Path.of(sigPathStr) : MessageTemplateService.getOrCreateSignatureImagePath();

            Path pdf = null;
            try {
                pdf = ManagedInvoicePdfService.purchase(purchase);
            } catch (Exception ignored) {}

            String statusBadge = balance <= 0.009 ? "SETTLED" : "PARTIAL";
            String publicStatusUrl = MessageTemplateService.buildPublicPaymentStatusUrl(purchase.getInvoiceNo(), total);

            var draft = new EmailTransmissionDialog.EmailDraft(
                    "Transmit Supplier Remittance Advice",
                    "Direct delivery of payment remittance advice to supplier",
                    "Remittance Advice",
                    purchase.getInvoiceNo(),
                    purchase.getSupplier().getEmail().trim(),
                    msg.subject(),
                    msg.body(),
                    pdf,
                    sigPath,
                    "purchases",
                    publicStatusUrl,
                    total,
                    amountPaid,
                    balance,
                    statusBadge,
                    "PURCHASE",
                    purchase.getId()
            );

            showAndTransmit(ownerNode, draft, "Supplier Remittance Advice");
        } catch (Exception ex) {
            AppDialogService.error(ownerNode, "Error", ex.getMessage() == null ? "Failed to prepare remittance advice email." : ex.getMessage());
        }
    }

    private static void openReturnTransmissionDialog(
            Node ownerNode,
            String returnNo,
            String partyName,
            String partyEmail,
            String originalDocNo,
            String returnType,
            double refundAmount,
            double remainingBalance,
            String paymentMode,
            String referenceNo,
            LocalDate paymentDate,
            String notes) {

        try {
            String dateText = BusinessClock.formatDate(paymentDate);
            var msg = MessageTemplateService.formatReturnRefundEmail(
                    partyName,
                    returnNo,
                    originalDocNo,
                    returnType,
                    refundAmount,
                    remainingBalance,
                    referenceNo,
                    dateText,
                    paymentMode,
                    notes
            );

            String sigPathStr = MessageTemplateService.isAttachSignatureToEmail() ? MessageTemplateService.getSignatureImagePath() : null;
            Path sigPath = (sigPathStr != null && !sigPathStr.isBlank()) ? Path.of(sigPathStr) : MessageTemplateService.getOrCreateSignatureImagePath();

            boolean isSalesReturn = returnType.toUpperCase(Locale.ROOT).contains("SALE");
            Path pdf = null;
            try {
                pdf = ManagedInvoicePdfService.refund(returnNo, isSalesReturn);
            } catch (Exception ignored) {}

            String statusBadge = remainingBalance <= 0.009 ? "REFUNDED" : "PARTIAL";
            String publicStatusUrl = MessageTemplateService.buildPublicPaymentStatusUrl(returnNo, refundAmount + remainingBalance);

            Integer returnId = 0;
            try {
                var resolved = supportApi.resolveRecord(isSalesReturn ? "SALES_RETURN" : "PURCHASE_RETURN", returnNo);
                if (resolved != null && resolved.found() && resolved.recordId() != null) {
                    returnId = resolved.recordId().intValue();
                }
            } catch (Exception ignored) {}

            var draft = new EmailTransmissionDialog.EmailDraft(
                    "Transmit Refund Settlement Confirmation",
                    "Direct delivery of refund settlement advice to " + (isSalesReturn ? "customer" : "supplier"),
                    "Refund Advice",
                    returnNo,
                    partyEmail,
                    msg.subject(),
                    msg.body(),
                    pdf,
                    sigPath,
                    "returns",
                    publicStatusUrl,
                    refundAmount + remainingBalance,
                    refundAmount,
                    remainingBalance,
                    statusBadge,
                    isSalesReturn ? "SALES_RETURN" : "PURCHASE_RETURN",
                    returnId
            );

            showAndTransmit(ownerNode, draft, "Refund Settlement Confirmation");
        } catch (Exception ex) {
            AppDialogService.error(ownerNode, "Error", ex.getMessage() == null ? "Failed to prepare refund settlement email." : ex.getMessage());
        }
    }

    private static void showAndTransmit(Node ownerNode, EmailTransmissionDialog.EmailDraft draft, String operationTitle) {
        var opt = EmailTransmissionDialog.show(ownerNode, draft);
        if (opt.isPresent()) {
            var res = opt.get();
            LiveOperationDialog.run(ownerNode, "Transmitting " + operationTitle, "email",
                    "Connecting to SMTP server and transmitting document...",
                    reporter -> {
                        reporter.stage(1, 3, "Transmitting message to " + res.recipientEmail() + "...");
                        EmailService.send(res.recipientEmail(), res.subject(), res.body(), res.attachmentPdf(), res.signatureImage(), res.senderProfile());
                        reporter.stage(2, 3, "Recording communication status in audit register...");
                        try {
                            String actor = SessionService.current() != null ? SessionService.current().getUsername() : "System";
                            String eType = (draft != null && draft.entityType() != null) ? draft.entityType() : "COMMUNICATION";
                            int eId = (draft != null && draft.entityId() != null) ? draft.entityId() : 0;
                            supportApi.communication(new SupportApiClient.CommunicationRequest(
                                    eType, eId, "EMAIL", res.recipientEmail(), res.subject(), "SENT", null, actor
                            ));
                        } catch (Exception logEx) {
                            System.err.println("[PaymentConfirmation] Error logging communication: " + logEx.getMessage());
                        }
                        reporter.stage(3, 3, "Message delivered successfully.");
                    },
                    () -> AppDialogService.success(ownerNode, "Email Sent", operationTitle + " delivered successfully to " + res.recipientEmail() + "."),
                    err -> {
                        try {
                            String actor = SessionService.current() != null ? SessionService.current().getUsername() : "System";
                            String eType = (draft != null && draft.entityType() != null) ? draft.entityType() : "COMMUNICATION";
                            int eId = (draft != null && draft.entityId() != null) ? draft.entityId() : 0;
                            supportApi.communication(new SupportApiClient.CommunicationRequest(
                                    eType, eId, "EMAIL", res.recipientEmail(), res.subject(), "FAILED", err.getMessage(), actor
                            ));
                        } catch (Exception logEx) {
                            System.err.println("[PaymentConfirmation] Error logging failed communication: " + logEx.getMessage());
                        }
                        AppDialogService.error(ownerNode, "Delivery Failed", err.getMessage() == null ? "Email transmission failed." : err.getMessage());
                    }
            );
        }
    }

    private static String formatMoney(double value) {
        return "₹ " + String.format(Locale.of("en", "IN"), "%,.2f", value);
    }
}
