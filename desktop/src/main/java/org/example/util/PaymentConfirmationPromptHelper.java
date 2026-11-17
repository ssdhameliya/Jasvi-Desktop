package org.example.util;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import org.example.api.support.SupportApiClient;
import org.example.api.whatsapp.BusinessWhatsappApiClient;
import org.example.config.ConfigManager;
import org.example.controller.PaymentTransmissionDialog;
import org.example.model.Purchase;
import org.example.model.Sales;
import org.example.service.EmailService;
import org.example.service.ManagedInvoicePdfService;
import org.example.service.MessageTemplateService;
import org.example.service.SessionService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Universal payment confirmation, remittance advice, and refund multi-channel prompt helper.
 * Provides consistent user prompt and simultaneous/selective transmission flow across:
 * 1. Sales Invoices (Customer Payment Receipts) - Email & WhatsApp
 * 2. Purchase Bills (Supplier Remittance Advice) - Email & WhatsApp
 * 3. Sales & Purchase Returns (Refund Settlement Confirmations) - Email & WhatsApp
 * 4. Bank Statement Reconciled Transactions - Email & WhatsApp
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
        promptAndTransmit(ownerNode, sale, (Integer) null, paymentAmount, paymentMode, referenceNo, paymentDate, notes, null);
    }

    public static void promptAndTransmit(
            Node ownerNode,
            Sales sale,
            Integer paymentId,
            double paymentAmount,
            String paymentMode,
            String referenceNo,
            LocalDate paymentDate,
            String notes,
            Runnable onSentCallback) {
        promptAndTransmit(ownerNode, sale, paymentId != null ? List.of(paymentId) : List.of(), paymentAmount, paymentMode, referenceNo, paymentDate, notes, onSentCallback);
    }

    public static void promptAndTransmit(
            Node ownerNode,
            Sales sale,
            List<Integer> paymentIds,
            double paymentAmount,
            String paymentMode,
            String referenceNo,
            LocalDate paymentDate,
            String notes,
            Runnable onSentCallback) {

        boolean emailEnabled = ConfigManager.getBoolean("bank.paymentConfirmationEmail.enabled", true);
        boolean whatsappEnabled = ConfigManager.getBoolean("whatsapp.paymentConfirmation.enabled", true);
        if (!emailEnabled && !whatsappEnabled) {
            return;
        }
        if (sale == null || sale.getCustomer() == null) {
            return;
        }

        final String customerName = sale.getCustomer().getName() == null ? "Valued Customer" : sale.getCustomer().getName().trim();
        final String customerEmail = sale.getCustomer().getEmail() != null ? sale.getCustomer().getEmail().trim() : "";
        final String customerPhone = sale.getCustomer().getPhone() != null ? sale.getCustomer().getPhone().trim() : "";

        boolean canSendEmail = !customerEmail.isBlank() && emailEnabled;
        boolean canSendWhatsapp = !customerPhone.isBlank() && whatsappEnabled;
        if (!canSendEmail && !canSendWhatsapp) {
            return;
        }

        final String invoiceNo = sale.getInvoiceNo();
        final double amount = paymentAmount;
        final String safeMode = (paymentMode == null || paymentMode.isBlank()) ? "Payment" : paymentMode.trim();
        final String safeRef = (referenceNo == null || referenceNo.isBlank()) ? "-" : referenceNo.trim();
        final LocalDate safeDate = paymentDate == null ? BusinessClock.today() : paymentDate;
        final String safeNotes = (notes == null || notes.isBlank()) ? ("Payment received for " + invoiceNo) : notes.trim();

        Platform.runLater(() -> {
            StringBuilder dest = new StringBuilder();
            if (canSendEmail && canSendWhatsapp) {
                dest.append("Email (").append(customerEmail).append(") & WhatsApp (").append(customerPhone).append(")");
            } else if (canSendEmail) {
                dest.append("Email (").append(customerEmail).append(")");
            } else {
                dest.append("WhatsApp (").append(customerPhone).append(")");
            }

            Alert alert = new OwnedAlert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Payment Confirmation Dispatch");
            alert.setHeaderText("Payment Receipt & Confirmation Advice");
            alert.setContentText("Payment of " + formatMoney(amount) + " was successfully recorded for Invoice "
                    + invoiceNo + " (" + customerName + ").\n\nWould you like to transmit an official Payment Confirmation Advice to " + dest + "?");

            ButtonType btnSend = new ButtonType("Transmit Advice", ButtonBar.ButtonData.OK_DONE);
            alert.getButtonTypes().setAll(btnSend, ButtonType.CANCEL);

            var choice = alert.showAndWait();
            if (choice.isPresent() && choice.get() == btnSend) {
                openSaleTransmissionDialog(ownerNode, sale, paymentIds, amount, safeMode, safeRef, safeDate, safeNotes, onSentCallback);
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
        promptAndTransmitPurchase(ownerNode, purchase, (Integer) null, paymentAmount, paymentMode, referenceNo, paymentDate, notes, null);
    }

    public static void promptAndTransmitPurchase(
            Node ownerNode,
            Purchase purchase,
            Integer paymentId,
            double paymentAmount,
            String paymentMode,
            String referenceNo,
            LocalDate paymentDate,
            String notes,
            Runnable onSentCallback) {
        promptAndTransmitPurchase(ownerNode, purchase, paymentId != null ? List.of(paymentId) : List.of(), paymentAmount, paymentMode, referenceNo, paymentDate, notes, onSentCallback);
    }

    public static void promptAndTransmitPurchase(
            Node ownerNode,
            Purchase purchase,
            List<Integer> paymentIds,
            double paymentAmount,
            String paymentMode,
            String referenceNo,
            LocalDate paymentDate,
            String notes,
            Runnable onSentCallback) {

        boolean emailEnabled = ConfigManager.getBoolean("bank.paymentConfirmationEmail.enabled", true);
        boolean whatsappEnabled = ConfigManager.getBoolean("whatsapp.paymentConfirmation.enabled", true);
        if (!emailEnabled && !whatsappEnabled) {
            return;
        }
        if (purchase == null || purchase.getSupplier() == null) {
            return;
        }

        final String supplierName = purchase.getSupplier().getName() == null ? "Valued Supplier" : purchase.getSupplier().getName().trim();
        final String supplierEmail = purchase.getSupplier().getEmail() != null ? purchase.getSupplier().getEmail().trim() : "";
        final String supplierPhone = purchase.getSupplier().getPhone() != null ? purchase.getSupplier().getPhone().trim() : "";

        boolean canSendEmail = !supplierEmail.isBlank() && emailEnabled;
        boolean canSendWhatsapp = !supplierPhone.isBlank() && whatsappEnabled;
        if (!canSendEmail && !canSendWhatsapp) {
            return;
        }

        final String invoiceNo = purchase.getInvoiceNo();
        final double amount = paymentAmount;
        final String safeMode = (paymentMode == null || paymentMode.isBlank()) ? "Bank Transfer" : paymentMode.trim();
        final String safeRef = (referenceNo == null || referenceNo.isBlank()) ? "-" : referenceNo.trim();
        final LocalDate safeDate = paymentDate == null ? BusinessClock.today() : paymentDate;
        final String safeNotes = (notes == null || notes.isBlank()) ? ("Payment disbursed for Bill #" + invoiceNo) : notes.trim();

        Platform.runLater(() -> {
            StringBuilder dest = new StringBuilder();
            if (canSendEmail && canSendWhatsapp) {
                dest.append("Email (").append(supplierEmail).append(") & WhatsApp (").append(supplierPhone).append(")");
            } else if (canSendEmail) {
                dest.append("Email (").append(supplierEmail).append(")");
            } else {
                dest.append("WhatsApp (").append(supplierPhone).append(")");
            }

            Alert alert = new OwnedAlert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Supplier Remittance Advice Dispatch");
            alert.setHeaderText("Payment Remittance Advice");
            alert.setContentText("Disbursement of " + formatMoney(amount) + " was recorded for Purchase Bill "
                    + invoiceNo + " (" + supplierName + ").\n\nWould you like to transmit an official Remittance Advice to " + dest + "?");

            ButtonType btnSend = new ButtonType("Transmit Remittance", ButtonBar.ButtonData.OK_DONE);
            alert.getButtonTypes().setAll(btnSend, ButtonType.CANCEL);

            var choice = alert.showAndWait();
            if (choice.isPresent() && choice.get() == btnSend) {
                openPurchaseTransmissionDialog(ownerNode, purchase, paymentIds, amount, safeMode, safeRef, safeDate, safeNotes, onSentCallback);
            }
        });
    }

    public static void promptAndTransmitReturn(
            Node ownerNode,
            String returnNo,
            String partyName,
            String partyEmail,
            String partyPhone,
            String originalDocNo,
            String returnType,
            double refundAmount,
            double remainingBalance,
            String mode,
            String ref,
            LocalDate date,
            String notes) {
        promptAndTransmitReturn(ownerNode, null, returnNo, partyName, partyEmail, partyPhone, originalDocNo, returnType, refundAmount, remainingBalance, mode, ref, date, notes, null);
    }

    public static void promptAndTransmitReturn(
            Node ownerNode,
            Integer refundId,
            String returnNo,
            String partyName,
            String partyEmail,
            String partyPhone,
            String originalDocNo,
            String returnType,
            double refundAmount,
            double remainingBalance,
            String mode,
            String ref,
            LocalDate date,
            String notes,
            Runnable onSentCallback) {

        boolean emailEnabled = ConfigManager.getBoolean("bank.paymentConfirmationEmail.enabled", true);
        boolean whatsappEnabled = ConfigManager.getBoolean("whatsapp.paymentConfirmation.enabled", true);
        if (!emailEnabled && !whatsappEnabled) {
            return;
        }
        final String pEmail = partyEmail != null ? partyEmail.trim() : "";
        final String pPhone = partyPhone != null ? partyPhone.trim() : "";

        boolean canSendEmail = !pEmail.isBlank() && emailEnabled;
        boolean canSendWhatsapp = !pPhone.isBlank() && whatsappEnabled;
        if (!canSendEmail && !canSendWhatsapp) {
            return;
        }

        final String pName = partyName == null || partyName.isBlank() ? "Valued Partner" : partyName.trim();
        final String rNo = returnNo == null ? "" : returnNo.trim();
        final String rType = returnType == null ? "Return" : returnType.trim();
        final double amount = refundAmount;
        final String safeMode = (mode == null || mode.isBlank()) ? "Bank Transfer" : mode.trim();
        final String safeRef = (ref == null || ref.isBlank()) ? "-" : ref.trim();
        final LocalDate safeDate = date == null ? BusinessClock.today() : date;
        final String safeNotes = notes == null ? "" : notes.trim();

        Platform.runLater(() -> {
            StringBuilder dest = new StringBuilder();
            if (!pEmail.isBlank() && !pPhone.isBlank()) {
                dest.append("Email (").append(pEmail).append(") & WhatsApp (").append(pPhone).append(")");
            } else if (!pEmail.isBlank()) {
                dest.append("Email (").append(pEmail).append(")");
            } else {
                dest.append("WhatsApp (").append(pPhone).append(")");
            }

            Alert alert = new OwnedAlert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Refund Settlement Confirmation");
            alert.setHeaderText("Refund Settlement Receipt");
            alert.setContentText("Refund of " + formatMoney(amount) + " was processed for "
                    + rType + " " + rNo + " (" + pName + ").\n\nWould you like to transmit an official Settlement Confirmation Advice to " + dest + "?");

            ButtonType btnSend = new ButtonType("Transmit Settlement", ButtonBar.ButtonData.OK_DONE);
            alert.getButtonTypes().setAll(btnSend, ButtonType.CANCEL);

            var choice = alert.showAndWait();
            if (choice.isPresent() && choice.get() == btnSend) {
                openReturnTransmissionDialog(ownerNode, refundId, rNo, pName, pEmail, pPhone, originalDocNo, rType, amount, remainingBalance, safeMode, safeRef, safeDate, safeNotes, onSentCallback);
            }
        });
    }

    public static void openSaleTransmissionDialog(
            Node ownerNode,
            Sales sale,
            Integer paymentId,
            double amountPaid,
            String paymentMode,
            String referenceNo,
            LocalDate paymentDate,
            String notes,
            Runnable onSentCallback) {
        openSaleTransmissionDialog(ownerNode, sale, paymentId != null ? List.of(paymentId) : List.of(), amountPaid, paymentMode, referenceNo, paymentDate, notes, PaymentTransmissionDialog.ChannelPreference.BOTH, onSentCallback);
    }

    public static void openSaleTransmissionDialog(
            Node ownerNode,
            Sales sale,
            Integer paymentId,
            double amountPaid,
            String paymentMode,
            String referenceNo,
            LocalDate paymentDate,
            String notes,
            PaymentTransmissionDialog.ChannelPreference preferredChannel,
            Runnable onSentCallback) {
        openSaleTransmissionDialog(ownerNode, sale, paymentId != null ? List.of(paymentId) : List.of(), amountPaid, paymentMode, referenceNo, paymentDate, notes, preferredChannel, onSentCallback);
    }

    public static void openSaleTransmissionDialog(
            Node ownerNode,
            Sales sale,
            List<Integer> paymentIds,
            double amountPaid,
            String paymentMode,
            String referenceNo,
            LocalDate paymentDate,
            String notes,
            Runnable onSentCallback) {
        openSaleTransmissionDialog(ownerNode, sale, paymentIds, amountPaid, paymentMode, referenceNo, paymentDate, notes, PaymentTransmissionDialog.ChannelPreference.BOTH, onSentCallback);
    }

    public static void openSaleTransmissionDialog(
            Node ownerNode,
            Sales sale,
            List<Integer> paymentIds,
            double amountPaid,
            String paymentMode,
            String referenceNo,
            LocalDate paymentDate,
            String notes,
            PaymentTransmissionDialog.ChannelPreference preferredChannel,
            Runnable onSentCallback) {

        try {
            double total = sale.getTotalAmount();
            double previouslyPaid = sale.getPaidAmount();
            double balance = Math.max(0.0, total - (previouslyPaid + amountPaid));
            String dateText = BusinessClock.formatDate(paymentDate);
            String narration = paymentMode + (referenceNo.equals("-") ? "" : " • Ref: " + referenceNo) + " • " + notes;

            var emailMsg = MessageTemplateService.formatPaymentReceiptEmail(
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

            String whatsappMsg = MessageTemplateService.formatPaymentReceiptWhatsapp(
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
            if (statusUrl != null && !statusUrl.isBlank() && !whatsappMsg.contains(statusUrl)) {
                whatsappMsg = whatsappMsg.stripTrailing() + "\n\n🔗 *Live Status Tracking:*\n" + statusUrl;
            }

            String sigPathStr = MessageTemplateService.isAttachSignatureToEmail() ? MessageTemplateService.getSignatureImagePath() : null;
            Path sigPath = (sigPathStr != null && !sigPathStr.isBlank()) ? Path.of(sigPathStr) : null;

            Path pdf = null;
            try {
                pdf = ManagedInvoicePdfService.sales(sale);
            } catch (Exception ignored) {}

            String statusBadge = balance <= 0.009 ? "PAID" : "PARTIAL";

            String customerEmail = sale.getCustomer() != null && sale.getCustomer().getEmail() != null ? sale.getCustomer().getEmail().trim() : "";
            String customerPhone = sale.getCustomer() != null && sale.getCustomer().getPhone() != null ? sale.getCustomer().getPhone().trim() : "";

            Integer primaryPaymentId = (paymentIds != null && !paymentIds.isEmpty()) ? paymentIds.getFirst() : null;

            var draft = new PaymentTransmissionDialog.PaymentDraft(
                    "Transmit Payment Confirmation Advice",
                    "Direct delivery of payment receipt & confirmation to customer",
                    "Payment Receipt",
                    sale.getInvoiceNo(),
                    customerEmail,
                    customerPhone,
                    emailMsg.subject(),
                    emailMsg.body(),
                    whatsappMsg,
                    pdf,
                    sigPath,
                    "accounts",
                    statusUrl,
                    total,
                    amountPaid,
                    balance,
                    statusBadge,
                    "SALE",
                    sale.getId(),
                    primaryPaymentId,
                    null,
                    preferredChannel != null ? preferredChannel : PaymentTransmissionDialog.ChannelPreference.BOTH
            );

            showAndTransmit(ownerNode, draft, "Payment Confirmation", primaryPaymentId, paymentIds, null, onSentCallback);
        } catch (Exception ex) {
            AppDialogService.error(ownerNode, "Error", ex.getMessage() == null ? "Failed to prepare payment confirmation advice." : ex.getMessage());
        }
    }

    public static void openPurchaseTransmissionDialog(
            Node ownerNode,
            Purchase purchase,
            Integer paymentId,
            double amountPaid,
            String paymentMode,
            String referenceNo,
            LocalDate paymentDate,
            String notes,
            Runnable onSentCallback) {
        openPurchaseTransmissionDialog(ownerNode, purchase, paymentId != null ? List.of(paymentId) : List.of(), amountPaid, paymentMode, referenceNo, paymentDate, notes, PaymentTransmissionDialog.ChannelPreference.BOTH, onSentCallback);
    }

    public static void openPurchaseTransmissionDialog(
            Node ownerNode,
            Purchase purchase,
            Integer paymentId,
            double amountPaid,
            String paymentMode,
            String referenceNo,
            LocalDate paymentDate,
            String notes,
            PaymentTransmissionDialog.ChannelPreference preferredChannel,
            Runnable onSentCallback) {
        openPurchaseTransmissionDialog(ownerNode, purchase, paymentId != null ? List.of(paymentId) : List.of(), amountPaid, paymentMode, referenceNo, paymentDate, notes, preferredChannel, onSentCallback);
    }

    public static void openPurchaseTransmissionDialog(
            Node ownerNode,
            Purchase purchase,
            List<Integer> paymentIds,
            double amountPaid,
            String paymentMode,
            String referenceNo,
            LocalDate paymentDate,
            String notes,
            Runnable onSentCallback) {
        openPurchaseTransmissionDialog(ownerNode, purchase, paymentIds, amountPaid, paymentMode, referenceNo, paymentDate, notes, PaymentTransmissionDialog.ChannelPreference.BOTH, onSentCallback);
    }

    public static void openPurchaseTransmissionDialog(
            Node ownerNode,
            Purchase purchase,
            List<Integer> paymentIds,
            double amountPaid,
            String paymentMode,
            String referenceNo,
            LocalDate paymentDate,
            String notes,
            PaymentTransmissionDialog.ChannelPreference preferredChannel,
            Runnable onSentCallback) {

        try {
            double total = purchase.getTotalAmount();
            double previouslyPaid = purchase.getPaidAmount();
            double balance = Math.max(0.0, total - (previouslyPaid + amountPaid));
            String dateText = BusinessClock.formatDate(paymentDate);

            var emailMsg = MessageTemplateService.formatPurchaseRemittanceAdviceEmail(
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

            String statusUrl = MessageTemplateService.buildPublicPaymentStatusUrl(purchase.getInvoiceNo(), total);

            String whatsappMsg = MessageTemplateService.formatPurchaseRemittanceWhatsapp(
                    purchase.getSupplier().getName(),
                    purchase.getInvoiceNo(),
                    purchase.getInvoiceDate() == null ? BusinessClock.today() : purchase.getInvoiceDate(),
                    total,
                    amountPaid,
                    balance,
                    referenceNo,
                    dateText,
                    paymentMode + " - " + notes
            );
            if (statusUrl != null && !statusUrl.isBlank() && !whatsappMsg.contains(statusUrl)) {
                whatsappMsg = whatsappMsg.stripTrailing() + "\n\n🔗 *Live Status Tracking:*\n" + statusUrl;
            }

            String sigPathStr = MessageTemplateService.isAttachSignatureToEmail() ? MessageTemplateService.getSignatureImagePath() : null;
            Path sigPath = (sigPathStr != null && !sigPathStr.isBlank()) ? Path.of(sigPathStr) : MessageTemplateService.getOrCreateSignatureImagePath();

            Path pdf = null;
            try {
                pdf = ManagedInvoicePdfService.purchase(purchase);
            } catch (Exception ignored) {}

            String statusBadge = balance <= 0.009 ? "SETTLED" : "PARTIAL";

            String supplierEmail = purchase.getSupplier() != null && purchase.getSupplier().getEmail() != null ? purchase.getSupplier().getEmail().trim() : "";
            String supplierPhone = purchase.getSupplier() != null && purchase.getSupplier().getPhone() != null ? purchase.getSupplier().getPhone().trim() : "";

            Integer primaryPaymentId = (paymentIds != null && !paymentIds.isEmpty()) ? paymentIds.getFirst() : null;

            var draft = new PaymentTransmissionDialog.PaymentDraft(
                    "Transmit Supplier Remittance Advice",
                    "Direct delivery of payment remittance advice to supplier",
                    "Remittance Advice",
                    purchase.getInvoiceNo(),
                    supplierEmail,
                    supplierPhone,
                    emailMsg.subject(),
                    emailMsg.body(),
                    whatsappMsg,
                    pdf,
                    sigPath,
                    "purchases",
                    statusUrl,
                    total,
                    amountPaid,
                    balance,
                    statusBadge,
                    "PURCHASE",
                    purchase.getId(),
                    primaryPaymentId,
                    null,
                    preferredChannel != null ? preferredChannel : PaymentTransmissionDialog.ChannelPreference.BOTH
            );

            showAndTransmit(ownerNode, draft, "Supplier Remittance Advice", primaryPaymentId, paymentIds, null, onSentCallback);
        } catch (Exception ex) {
            AppDialogService.error(ownerNode, "Error", ex.getMessage() == null ? "Failed to prepare remittance advice." : ex.getMessage());
        }
    }

    public static void openReturnTransmissionDialog(
            Node ownerNode,
            Integer refundId,
            String returnNo,
            String partyName,
            String partyEmail,
            String partyPhone,
            String originalDocNo,
            String returnType,
            double refundAmount,
            double remainingBalance,
            String paymentMode,
            String referenceNo,
            LocalDate paymentDate,
            String notes,
            Runnable onSentCallback) {
        openReturnTransmissionDialog(ownerNode, refundId, returnNo, partyName, partyEmail, partyPhone, originalDocNo, returnType, refundAmount, remainingBalance, paymentMode, referenceNo, paymentDate, notes, PaymentTransmissionDialog.ChannelPreference.BOTH, onSentCallback);
    }

    public static void openReturnTransmissionDialog(
            Node ownerNode,
            Integer refundId,
            String returnNo,
            String partyName,
            String partyEmail,
            String partyPhone,
            String originalDocNo,
            String returnType,
            double refundAmount,
            double remainingBalance,
            String paymentMode,
            String referenceNo,
            LocalDate paymentDate,
            String notes,
            PaymentTransmissionDialog.ChannelPreference preferredChannel,
            Runnable onSentCallback) {

        try {
            boolean isSalesReturn = returnType == null || !returnType.toUpperCase().contains("PURCHASE");
            String dateText = BusinessClock.formatDate(paymentDate);

            var emailMsg = MessageTemplateService.formatReturnRefundEmail(
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

            String statusUrl = MessageTemplateService.buildPublicPaymentStatusUrl(returnNo, refundAmount + remainingBalance);

            String whatsappMsg = "Hello *" + partyName + "*,\n\n"
                    + "Refund settlement of *₹ " + String.format(Locale.of("en", "IN"), "%,.2f", refundAmount) + "* "
                    + "has been processed against document *" + returnNo + "*.\n\n"
                    + "Payment Mode: *" + paymentMode + "*\n"
                    + "Reference / UTR: *" + referenceNo + "*\n"
                    + "Settlement Date: *" + dateText + "*\n"
                    + "Remaining Balance: *₹ " + String.format(Locale.of("en", "IN"), "%,.2f", remainingBalance) + "*\n\n"
                    + (statusUrl != null && !statusUrl.isBlank() ? "🔗 *Live Status Tracking:*\n" + statusUrl + "\n\n" : "")
                    + "Regards,\n*" + ConfigManager.get("company.name", "JASVI INDUSTRIES") + "*";

            String sigPathStr = MessageTemplateService.isAttachSignatureToEmail() ? MessageTemplateService.getSignatureImagePath() : null;
            Path sigPath = (sigPathStr != null && !sigPathStr.isBlank()) ? Path.of(sigPathStr) : MessageTemplateService.getOrCreateSignatureImagePath();

            Path pdf = null;
            Integer returnId = null;
            try {
                pdf = ManagedInvoicePdfService.refund(returnNo, isSalesReturn);
            } catch (Exception ignored) {}

            String statusBadge = remainingBalance <= 0.009 ? "SETTLED" : "PARTIAL";

            var draft = new PaymentTransmissionDialog.PaymentDraft(
                    "Transmit Refund Settlement Confirmation",
                    "Direct delivery of refund settlement advice to commercial party",
                    "Refund Advice",
                    returnNo,
                    partyEmail != null ? partyEmail.trim() : "",
                    partyPhone != null ? partyPhone.trim() : "",
                    emailMsg.subject(),
                    emailMsg.body(),
                    whatsappMsg,
                    pdf,
                    sigPath,
                    "returns",
                    statusUrl,
                    refundAmount + remainingBalance,
                    refundAmount,
                    remainingBalance,
                    statusBadge,
                    isSalesReturn ? "SALES_RETURN" : "PURCHASE_RETURN",
                    returnId,
                    null,
                    refundId,
                    preferredChannel != null ? preferredChannel : PaymentTransmissionDialog.ChannelPreference.BOTH
            );

            showAndTransmit(ownerNode, draft, "Refund Settlement Confirmation", null, refundId, onSentCallback);
        } catch (Exception ex) {
            AppDialogService.error(ownerNode, "Error", ex.getMessage() == null ? "Failed to prepare refund settlement advice." : ex.getMessage());
        }
    }

    private static void showAndTransmit(
            Node ownerNode,
            PaymentTransmissionDialog.PaymentDraft draft,
            String operationTitle,
            Integer paymentId,
            Integer refundId,
            Runnable onSent) {
        showAndTransmit(ownerNode, draft, operationTitle, paymentId, null, refundId, onSent);
    }

    private static void showAndTransmit(
            Node ownerNode,
            PaymentTransmissionDialog.PaymentDraft draft,
            String operationTitle,
            Integer paymentId,
            List<Integer> paymentIds,
            Integer refundId,
            Runnable onSent) {

        var opt = PaymentTransmissionDialog.show(ownerNode, draft);
        if (opt.isPresent()) {
            var res = opt.get();
            if (!res.sendEmail() && !res.sendWhatsapp()) {
                return;
            }

            LiveOperationDialog.run(ownerNode, "Transmitting " + operationTitle, "process",
                    "Connecting to communication channels...",
                    reporter -> {
                        int totalStages = (res.sendEmail() ? 1 : 0) + (res.sendWhatsapp() ? 1 : 0) + 1;
                        int currentStage = 1;
                        String actor = SessionService.current() != null ? SessionService.current().getUsername() : "System";
                        String eType = (draft != null && draft.entityType() != null) ? draft.entityType() : "COMMUNICATION";
                        int eId = (draft != null && draft.entityId() != null) ? draft.entityId() : 0;

                        // 1. Send Email if selected
                        if (res.sendEmail()) {
                            reporter.stage(currentStage++, totalStages, "Transmitting Email via SMTP...");
                            try {
                                List<String> emails = EmailService.parseRecipientList(res.recipientEmail());
                                for (String email : emails) {
                                    EmailService.send(email, res.emailSubject(), res.emailBody(), res.attachmentPdf(), res.signatureImage(), res.senderProfile());
                                    supportApi.communication(new SupportApiClient.CommunicationRequest(
                                            eType, eId, "EMAIL", email, res.emailSubject(), "SENT", null, actor
                                    ));
                                }
                                if (paymentIds != null && !paymentIds.isEmpty()) {
                                    for (Integer pid : paymentIds) {
                                        if (pid != null && pid > 0) {
                                            supportApi.updatePaymentEmailStatus(pid, "SENT");
                                        }
                                    }
                                } else if (paymentId != null && paymentId > 0) {
                                    supportApi.updatePaymentEmailStatus(paymentId, "SENT");
                                }
                                if (refundId != null && refundId > 0) {
                                    new org.example.api.returns.ReturnApiClient().updateRefundEmailStatus(refundId, "SENT");
                                }
                            } catch (Exception ex) {
                                supportApi.communication(new SupportApiClient.CommunicationRequest(
                                        eType, eId, "EMAIL", res.recipientEmail(), res.emailSubject(), "FAILED", ex.getMessage(), actor
                                ));
                                if (paymentIds != null && !paymentIds.isEmpty()) {
                                    for (Integer pid : paymentIds) {
                                        if (pid != null && pid > 0) {
                                            supportApi.updatePaymentEmailStatus(pid, "FAILED");
                                        }
                                    }
                                } else if (paymentId != null && paymentId > 0) {
                                    supportApi.updatePaymentEmailStatus(paymentId, "FAILED");
                                }
                                if (refundId != null && refundId > 0) {
                                    new org.example.api.returns.ReturnApiClient().updateRefundEmailStatus(refundId, "FAILED");
                                }
                                throw new IllegalStateException("Email transmission failed: " + ex.getMessage(), ex);
                            }
                        }

                        // 2. Send WhatsApp if selected
                        if (res.sendWhatsapp()) {
                            reporter.stage(currentStage++, totalStages, "Transmitting WhatsApp via WAHA Engine...");
                            try {
                                var wahaApi = new BusinessWhatsappApiClient();
                                List<String> phones = Arrays.stream(res.recipientPhone().split("[;,]"))
                                        .map(String::trim)
                                        .filter(s -> !s.isBlank())
                                        .toList();
                                for (String phone : phones) {
                                    BusinessWhatsappApiClient.SendResponse sendResp = null;
                                    if (res.attachmentPdf() != null && Files.isRegularFile(res.attachmentPdf())) {
                                        sendResp = wahaApi.sendDocument(new BusinessWhatsappApiClient.DocumentSendRequest(
                                                eType, draft != null && draft.documentNo() != null ? draft.documentNo() : "PAY", eId, null, phone, res.whatsappMessage(), 0L
                                        ));
                                    } else {
                                        sendResp = wahaApi.sendText(new BusinessWhatsappApiClient.TextSendRequest(
                                                phone, res.whatsappMessage(), null
                                        ));
                                    }
                                    if (sendResp != null && !sendResp.success()) {
                                        throw new IllegalStateException(sendResp.message());
                                    }
                                }
                                // Note: BusinessWhatsappService on the server automatically records the WHATSAPP communication log entry.
                                if (paymentIds != null && !paymentIds.isEmpty()) {
                                    for (Integer pid : paymentIds) {
                                        if (pid != null && pid > 0) {
                                            supportApi.updatePaymentWhatsappStatus(pid, "SENT");
                                        }
                                    }
                                } else if (paymentId != null && paymentId > 0) {
                                    supportApi.updatePaymentWhatsappStatus(paymentId, "SENT");
                                }
                                if (refundId != null && refundId > 0) {
                                    new org.example.api.returns.ReturnApiClient().updateRefundWhatsappStatus(refundId, "SENT");
                                }
                            } catch (Exception ex) {
                                if (paymentIds != null && !paymentIds.isEmpty()) {
                                    for (Integer pid : paymentIds) {
                                        if (pid != null && pid > 0) {
                                            supportApi.updatePaymentWhatsappStatus(pid, "FAILED");
                                        }
                                    }
                                } else if (paymentId != null && paymentId > 0) {
                                    supportApi.updatePaymentWhatsappStatus(paymentId, "FAILED");
                                }
                                if (refundId != null && refundId > 0) {
                                    new org.example.api.returns.ReturnApiClient().updateRefundWhatsappStatus(refundId, "FAILED");
                                }
                                throw new IllegalStateException("WhatsApp transmission failed: " + ex.getMessage(), ex);
                            }
                        }

                        reporter.stage(currentStage, totalStages, "Transmission completed successfully.");
                    },
                    () -> {
                        if (onSent != null) {
                            try { onSent.run(); } catch (Exception ignored) {}
                        }
                        String channels = (res.sendEmail() && res.sendWhatsapp()) ? "Email & WhatsApp" : (res.sendEmail() ? "Email" : "WhatsApp");
                        AppDialogService.success(ownerNode, "Transmission Sent", operationTitle + " delivered successfully via " + channels + ".");
                    },
                    err -> {
                        AppDialogService.error(ownerNode, "Transmission Failed", err.getMessage() == null ? "Delivery failed." : err.getMessage());
                    }
            );
        }
    }

    private static String formatMoney(double value) {
        return "₹ " + String.format(Locale.of("en", "IN"), "%,.2f", value);
    }
}
