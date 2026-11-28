package org.example.service;

import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.example.config.ConfigManager;

import java.util.Properties;
import java.nio.file.Path;
import java.nio.file.Files;

/** SMTP delivery configured from the application's Settings screen. */
public final class EmailService {
    private EmailService() {
    }

    public record SenderProfile(String key, String displayName, String email, String password, String host, int port) {}

    public static SenderProfile defaultSenderProfile() {
        String email = ConfigManager.get("smtp.email", "").trim();
        String pwd = ConfigManager.getSmtpPassword();
        String host = ConfigManager.get("smtp.host", "").trim();
        if (host.isBlank() && !email.isBlank()) {
            try { host = inferHost(email); } catch (Exception ignored) { host = "smtp.gmail.com"; }
        }
        int port = 587;
        try { port = Integer.parseInt(ConfigManager.get("smtp.port", "587").trim()); } catch (Exception ignored) {}
        return new SenderProfile("default", BrandingService.companyName(), email, pwd, host, port);
    }

    public static SenderProfile resolveSenderProfile(String activityKey) {
        if (!ConfigManager.getBoolean("email.activityRouting.enabled", false) || activityKey == null || activityKey.isBlank()) {
            return defaultSenderProfile();
        }
        String pEmail = ConfigManager.get("email.profile." + activityKey + ".email", "").trim();
        if (pEmail.isBlank()) {
            return defaultSenderProfile();
        }
        String pName = ConfigManager.get("email.profile." + activityKey + ".name", BrandingService.companyName()).trim();
        String pPwd = ConfigManager.get("email.profile." + activityKey + ".password", "").trim();
        if (org.example.shared.SecretValueCodec.isEncrypted(pPwd)) {
            try { pPwd = org.example.shared.SecretValueCodec.decrypt(pPwd); } catch (Exception ignored) {}
        }
        String pHost = ConfigManager.get("email.profile." + activityKey + ".host", "").trim();
        if (pHost.isBlank()) pHost = ConfigManager.get("smtp.host", "").trim();
        if (pHost.isBlank()) {
            try { pHost = inferHost(pEmail); } catch (Exception ignored) { pHost = "smtp.gmail.com"; }
        }
        int pPort = 587;
        try {
            pPort = Integer.parseInt(ConfigManager.get("email.profile." + activityKey + ".port", ConfigManager.get("smtp.port", "587")).trim());
        } catch (Exception ignored) {}
        return new SenderProfile(activityKey, pName, pEmail, pPwd, pHost, pPort);
    }

    public static void sendOtp(String recipient, String otp) {
        String subject = BrandingService.applicationName() + " verification code";
        String body = MessageTemplateService.shouldUseHtml("auth_otp")
                ? MessageTemplateService.formatOtpEmailHtml(recipient, "verification", otp)
                : "Your " + BrandingService.companyName() + " verification code is " + otp + ". It expires in 10 minutes. Do not share this code.";
        sendWithCopies(recipient, null, null, subject, body, null, null, null, "AUTH", null, null, "auth_otp");
    }

    public static void send(String recipient, String subject, String body) {
        send(recipient, subject, body, null);
    }

    public static void resend(String recipient, String subject, String body, Path attachment) {
        resend(recipient, subject, body, attachment, null);
    }

    public static void resend(String recipient, String subject, String body, Path attachment, Path signatureImage) {
        resend(recipient, subject, body, attachment, signatureImage, null);
    }

    public static java.util.List<String> parseRecipientList(String raw) {
        InternetAddress[] parsed = parseRecipientAddresses(raw);
        return java.util.Arrays.stream(parsed).map(InternetAddress::getAddress).toList();
    }

    public static InternetAddress[] parseRecipientAddresses(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Recipient email address is missing. Update the customer or supplier master record and try again.");
        }
        java.util.LinkedHashMap<String, InternetAddress> unique = new java.util.LinkedHashMap<>();
        for (String token : raw.split("[;,]")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) continue;
            try {
                InternetAddress address = new InternetAddress(trimmed, true);
                address.validate();
                unique.putIfAbsent(address.getAddress().toLowerCase(java.util.Locale.ROOT), address);
            } catch (Exception invalidAddress) {
                throw new IllegalArgumentException("Invalid recipient email address: " + trimmed, invalidAddress);
            }
        }
        if (unique.isEmpty()) {
            throw new IllegalArgumentException("Recipient email address is missing. Update the customer or supplier master record and try again.");
        }
        return unique.values().toArray(InternetAddress[]::new);
    }

    public static java.util.List<String> parseOptionalRecipientList(String raw) {
        if (raw == null || raw.isBlank()) return java.util.List.of();
        java.util.LinkedHashMap<String, String> unique = new java.util.LinkedHashMap<>();
        for (String token : raw.split("[;,]")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) continue;
            try {
                InternetAddress address = new InternetAddress(trimmed, true);
                address.validate();
                String normalized = address.getAddress();
                if (normalized != null && normalized.contains("@")) {
                    unique.putIfAbsent(normalized.toLowerCase(java.util.Locale.ROOT), normalized);
                }
            } catch (Exception ignored) {
                // Skip invalid optional recipient tokens while retaining valid deduplicated addresses.
            }
        }
        return java.util.List.copyOf(unique.values());
    }

    private static String mergeDefaultAddresses(String explicitRaw, String configKey) {
        java.util.LinkedHashMap<String, String> unique = new java.util.LinkedHashMap<>();
        for (String addr : parseOptionalRecipientList(explicitRaw)) {
            unique.putIfAbsent(addr.toLowerCase(java.util.Locale.ROOT), addr);
        }
        String configured = ConfigManager.get(configKey, "");
        if (configured != null && !configured.isBlank()) {
            try {
                for (String addr : parseOptionalRecipientList(configured)) {
                    unique.putIfAbsent(addr.toLowerCase(java.util.Locale.ROOT), addr);
                }
            } catch (Exception ignored) {}
        }
        return String.join("; ", unique.values());
    }

    public static void resend(String recipient, String subject, String body, Path attachment, Path signatureImage, SenderProfile profile) {
        org.example.service.PermissionService.require("COMMUNICATION.RESEND", "re-send a communication");
        if (signatureImage == null && MessageTemplateService.isAttachSignatureToEmail()) {
            signatureImage = MessageTemplateService.getOrCreateSignatureImagePath();
        }
        String normalizedRecipients = String.join(", ", parseRecipientList(recipient));
        String mergedCc = mergeDefaultAddresses(null, "email.default.cc");
        String mergedBcc = mergeDefaultAddresses(null, "email.default.bcc");
        if (ConfigManager.isSharedClient() || org.example.api.ApiSession.isEstablished()) {
            SenderProfile effective = profile != null ? profile : defaultSenderProfile();
            new org.example.api.authority.BusinessEmailClient().resend(
                    normalizedRecipients, mergedCc, mergedBcc, subject, body, attachment, signatureImage,
                    effective.email(), effective.displayName(), null, null, null, null);
            return;
        }
        sendWithCopies(normalizedRecipients, mergedCc, mergedBcc, subject, body, attachment, signatureImage, profile, null, null, null, null);
    }

    public static void resend(org.example.controller.EmailTransmissionDialog.EmailDraft draft,
                              org.example.controller.EmailTransmissionDialog.TransmissionResult result) {
        if (result == null) return;
        org.example.service.PermissionService.require("COMMUNICATION.RESEND", "re-send a communication");
        Path signatureImage = result.signatureImage();
        if (signatureImage == null && MessageTemplateService.isAttachSignatureToEmail()) {
            signatureImage = MessageTemplateService.getOrCreateSignatureImagePath();
        }
        String normalizedRecipients = String.join(", ", parseRecipientList(result.recipientEmail()));
        String mergedCc = mergeDefaultAddresses(result.recipientCc(), "email.default.cc");
        String mergedBcc = mergeDefaultAddresses(result.recipientBcc(), "email.default.bcc");
        Long entityId = draft != null && draft.entityId() != null ? Long.valueOf(draft.entityId()) : null;
        String entityType = draft != null ? draft.entityType() : null;
        String docNo = draft != null ? draft.documentNo() : null;
        String tplKey = MessageTemplateService.resolveTemplateKeyForEntity(entityType != null ? entityType : (draft != null ? draft.documentLabel() : null));
        if (ConfigManager.isSharedClient() || org.example.api.ApiSession.isEstablished()) {
            SenderProfile effective = result.senderProfile() != null ? result.senderProfile() : defaultSenderProfile();
            new org.example.api.authority.BusinessEmailClient().resend(
                    normalizedRecipients, mergedCc, mergedBcc, result.subject(), result.body(),
                    result.attachmentPdf(), signatureImage, effective.email(), effective.displayName(),
                    entityType, entityId, docNo, tplKey);
            return;
        }
        sendWithCopies(normalizedRecipients, mergedCc, mergedBcc, result.subject(), result.body(),
                result.attachmentPdf(), signatureImage, result.senderProfile(), entityType, entityId, docNo, tplKey);
    }

    public static void send(String recipient, String subject, String body, Path attachment) {
        send(recipient, subject, body, attachment, null);
    }

    public static void send(String recipient, String subject, String body, Path attachment, Path signatureImage) {
        send(recipient, subject, body, attachment, signatureImage, null);
    }

    public static void send(org.example.controller.EmailTransmissionDialog.EmailDraft draft,
                            org.example.controller.EmailTransmissionDialog.TransmissionResult result) {
        if (result == null) return;
        Long entityId = draft != null && draft.entityId() != null ? Long.valueOf(draft.entityId()) : null;
        String entityType = draft != null ? draft.entityType() : null;
        String docNo = draft != null ? draft.documentNo() : null;
        sendWithCopies(
                result.recipientEmail(),
                result.recipientCc(),
                result.recipientBcc(),
                result.subject(),
                result.body(),
                result.attachmentPdf(),
                result.signatureImage(),
                result.senderProfile(),
                entityType,
                entityId,
                docNo,
                MessageTemplateService.resolveTemplateKeyForEntity(entityType != null ? entityType : (draft != null ? draft.documentLabel() : null))
        );
    }

    public static void send(String recipient, String subject, String body, Path attachment, Path signatureImage, SenderProfile profile) {
        sendWithCopies(recipient, null, null, subject, body, attachment, signatureImage, profile, null, null, null, null);
    }

    public static void sendWithCopies(String recipient, String recipientCc, String recipientBcc, String subject, String body,
                                      Path attachment, Path signatureImage, SenderProfile profile,
                                      String entityType, Long entityId, String referenceNo, String templateKey) {
        if (signatureImage == null && MessageTemplateService.isAttachSignatureToEmail()) {
            signatureImage = MessageTemplateService.getOrCreateSignatureImagePath();
        }
        InternetAddress[] recipients = parseRecipientAddresses(recipient);
        String normalizedRecipients = java.util.Arrays.stream(recipients).map(InternetAddress::getAddress).collect(java.util.stream.Collectors.joining(", "));
        String mergedCc = mergeDefaultAddresses(recipientCc, "email.default.cc");
        String mergedBcc = mergeDefaultAddresses(recipientBcc, "email.default.bcc");
        SenderProfile effectiveProfile = profile != null ? profile : defaultSenderProfile();
        if (ConfigManager.isSharedClient() || org.example.api.ApiSession.isEstablished()) {
            try {
                new org.example.api.authority.BusinessEmailClient().send(
                        normalizedRecipients, mergedCc, mergedBcc, subject, body, attachment, signatureImage,
                        effectiveProfile.email(), effectiveProfile.displayName(), entityType, entityId, referenceNo, templateKey);
                return;
            } catch (Exception serverEx) {
                boolean isConnectFailure = false;
                for (Throwable t = serverEx; t != null; t = t.getCause()) {
                    if (t instanceof java.net.ConnectException) {
                        isConnectFailure = true;
                        break;
                    }
                }
                if (ConfigManager.isSharedClient() || !isConnectFailure) {
                    throw serverEx instanceof RuntimeException re ? re : new IllegalStateException(serverEx.getMessage(), serverEx);
                }
            }
        }
        String sender = effectiveProfile.email().trim();
        String password = effectiveProfile.password().trim();
        if (sender.isBlank() || password.isBlank()) {
            throw new IllegalStateException("Email is not configured. Open Settings and enter the sending email address and app password.");
        }
        try {
            sender = new InternetAddress(sender, true).getAddress();
        } catch (Exception invalidAddress) {
            throw new IllegalArgumentException("The sender email address is not valid.", invalidAddress);
        }
        if (attachment != null && (!Files.isRegularFile(attachment) || !Files.isReadable(attachment))) {
            throw new IllegalStateException("The PDF attachment could not be read: " + attachment);
        }
        String host = effectiveProfile.host().trim();
        if (host.isBlank()) host = inferHost(sender);
        password = password.replaceAll("\\s+", "");
        final String authSender = sender;
        final String authPassword = password;
        int portVal = effectiveProfile.port() > 0 ? effectiveProfile.port() : 587;
        String port = Integer.toString(portVal);
        Properties properties = new Properties();
        properties.put("mail.smtp.host", host);
        properties.put("mail.smtp.port", port.isBlank() ? "587" : port);
        properties.put("mail.smtp.auth", "true");
        properties.put("mail.smtp.starttls.enable", "true");
        properties.put("mail.smtp.starttls.required", "true");
        properties.put("mail.smtp.connectiontimeout", "15000");
        properties.put("mail.smtp.timeout", "30000");
        properties.put("mail.smtp.writetimeout", "30000");
        Session session = Session.getInstance(properties, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(authSender, authPassword);
            }
        });
        try {
            Message message = new MimeMessage(session);
            try {
                message.setFrom(new InternetAddress(sender, effectiveProfile.displayName()));
            } catch (Exception ex) {
                message.setFrom(new InternetAddress(sender));
            }
            message.setRecipients(Message.RecipientType.TO, recipients);
            if (!mergedCc.isBlank()) {
                message.setRecipients(Message.RecipientType.CC, parseRecipientAddresses(mergedCc));
            }
            if (!mergedBcc.isBlank()) {
                message.setRecipients(Message.RecipientType.BCC, parseRecipientAddresses(mergedBcc));
            }
            message.setSubject(subject);

            boolean hasSignatureImage = signatureImage != null && Files.isRegularFile(signatureImage) && Files.isReadable(signatureImage);
            boolean hasAttachment = attachment != null && Files.isRegularFile(attachment) && Files.isReadable(attachment);
            boolean isHtml = body != null && (body.contains("<!DOCTYPE html>") || body.contains("<html") || body.contains("<body"));

            if (!hasSignatureImage && !hasAttachment) {
                if (isHtml) {
                    message.setContent(buildAlternativeBody(body));
                } else {
                    message.setContent(body == null ? "" : body, "text/plain; charset=UTF-8");
                }
            } else {
                var rootMultipart = new jakarta.mail.internet.MimeMultipart("mixed");

                if (hasSignatureImage) {
                    var relatedMultipart = new jakarta.mail.internet.MimeMultipart("related");

                    var altPart = new jakarta.mail.internet.MimeBodyPart();
                    String htmlContent = isHtml
                            ? (body.contains("cid:signatureImage") ? body : injectInlineSignatureBeforeBodyEnd(body))
                            : formatHtmlEmailBody(body, true);
                    altPart.setContent(buildAlternativeBody(htmlContent));
                    relatedMultipart.addBodyPart(altPart);

                    var imgPart = new jakarta.mail.internet.MimeBodyPart();
                    imgPart.attachFile(signatureImage.toFile());
                    imgPart.setContentID("<signatureImage>");
                    imgPart.setHeader("Content-ID", "<signatureImage>");
                    imgPart.setDisposition(jakarta.mail.Part.INLINE);
                    String mimeType = Files.probeContentType(signatureImage);
                    if (mimeType == null || mimeType.isBlank()) mimeType = "image/png";
                    imgPart.setHeader("Content-Type", mimeType + "; name=\"" + signatureImage.getFileName() + "\"");
                    relatedMultipart.addBodyPart(imgPart);

                    var bodyWrapper = new jakarta.mail.internet.MimeBodyPart();
                    bodyWrapper.setContent(relatedMultipart);
                    rootMultipart.addBodyPart(bodyWrapper);
                } else {
                    var textPart = new jakarta.mail.internet.MimeBodyPart();
                    if (isHtml) {
                        textPart.setContent(buildAlternativeBody(body));
                    } else {
                        textPart.setContent(buildAlternativeBody(formatHtmlEmailBody(body, false)));
                    }
                    rootMultipart.addBodyPart(textPart);
                }

                if (hasAttachment) {
                    var filePart = new jakarta.mail.internet.MimeBodyPart();
                    filePart.attachFile(attachment.toFile());
                    filePart.setFileName(attachment.getFileName().toString());
                    filePart.setDisposition(jakarta.mail.Part.ATTACHMENT);
                    rootMultipart.addBodyPart(filePart);
                }

                message.setContent(rootMultipart);
            }
            Transport.send(message);
        } catch (Exception e) {
            throw new RuntimeException(
                "Email could not be sent using " + host + ".\n\n" + EmailFailureMessages.forAdministrator(e), e);
        }
    }

    private static jakarta.mail.internet.MimeMultipart buildAlternativeBody(String htmlBody) throws MessagingException {
        var alternative = new jakarta.mail.internet.MimeMultipart("alternative");
        var plainPart = new jakarta.mail.internet.MimeBodyPart();
        String plain = htmlBody == null ? "" : htmlBody
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|tr|li|h[1-6])>", "\n")
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        plainPart.setText(plain.isBlank() ? "Please view this message in an HTML-compatible email client." : plain, "UTF-8");
        alternative.addBodyPart(plainPart);
        var htmlPart = new jakarta.mail.internet.MimeBodyPart();
        htmlPart.setContent(htmlBody == null ? "" : htmlBody, "text/html; charset=UTF-8");
        alternative.addBodyPart(htmlPart);
        return alternative;
    }

    private static String injectInlineSignatureBeforeBodyEnd(String html) {
        if (html == null || html.contains("cid:signatureImage")) return html;
        String sigTag = "<div style=\"margin-top: 16px; margin-bottom: 8px;\"><img src=\"cid:signatureImage\" alt=\"Signature / Stamp\" style=\"width: auto; max-width: 100%; max-height: 115px; height: auto; border: none; display: block;\" /></div>";
        return html.contains("</body>") ? html.replace("</body>", sigTag + "</body>") : (html + sigTag);
    }

    public static String formatHtmlEmailBody(String plainText, boolean includeInlineSignatureImage) {
        if (plainText == null) plainText = "";
        String escaped = plainText
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\r\n", "\n")
                .replace("\n", "<br/>\n");

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"></head><body style=\"margin:0; padding:12px; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; font-size: 14px; line-height: 1.6; color: #1e293b;\">");
        sb.append("<div>").append(escaped).append("</div>");
        if (includeInlineSignatureImage) {
            sb.append("<div style=\"margin-top: 16px; margin-bottom: 8px;\">");
            sb.append("<img src=\"cid:signatureImage\" alt=\"Signature / Stamp\" style=\"width: auto; max-width: 100%; max-height: 115px; height: auto; border: none; display: block;\" />");
            sb.append("</div>");
        }
        sb.append("</body></html>");
        return sb.toString();
    }

    private static String inferHost(String email) {
        String value = email.toLowerCase();
        if (value.endsWith("@gmail.com") || value.endsWith("@googlemail.com")) return "smtp.gmail.com";
        if (value.endsWith("@outlook.com") || value.endsWith("@hotmail.com") || value.endsWith("@live.com")) return "smtp.office365.com";
        if (value.endsWith("@yahoo.com") || value.endsWith("@yahoo.in")) return "smtp.mail.yahoo.com";
        throw new IllegalStateException("Email provider was not recognized. Enter SMTP Host and Port in Settings.");
    }

}
