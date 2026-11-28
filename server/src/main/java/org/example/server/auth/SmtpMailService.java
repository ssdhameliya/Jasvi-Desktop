package org.example.server.auth;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.example.server.persistence.JpaNativeRepository;
import org.example.shared.SecretValueCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.List;

/**
 * One SMTP authority for login OTP and business email.
 * Local desktop-backed servers continue to read the workspace config file;
 * dedicated/company servers use application_setting, with environment values
 * retained as a safe deployment fallback.
 */
@Service
public class SmtpMailService {
    private static final Logger LOG = LoggerFactory.getLogger(SmtpMailService.class);
    private final String host;
    private final int port;
    private final String email;
    private final String password;
    private final String configFile;
    private final JpaNativeRepository db;

    SmtpMailService(@Value("${dse.smtp.host:}") String host,
                    @Value("${dse.smtp.port:587}") int port,
                    @Value("${dse.smtp.email:}") String email,
                    @Value("${dse.smtp.password:}") String password,
                    @Value("${dse.smtp.config-file:}") String configFile,
                    JpaNativeRepository db) {
        this.host = host == null ? "" : host.trim();
        this.port = port;
        this.email = email == null ? "" : email.trim();
        this.password = password == null ? "" : password;
        this.configFile = configFile == null ? "" : configFile.trim();
        this.db = db;
    }

    public void requireConfigured() {
        Settings settings = settings();
        if (settings.host().isBlank() || settings.email().isBlank() || settings.password().isBlank())
            throw new IllegalStateException("Email/OTP settings are not configured. Configure SMTP settings first.");
    }

    public boolean isConfigured() {
        try {
            Settings settings = settings();
            return !settings.host().isBlank() && !settings.email().isBlank() && !settings.password().isBlank();
        } catch (Exception ignored) {
            return false;
        }
    }

    public Settings currentSettings() { return settings(); }

    /** Metadata for the Admin Settings UI; never decrypts or exposes the stored App Password. */
    public SettingsSummary currentSettingsSummary() {
        Settings fallback = new Settings(host, port, email, password);
        if (!configFile.isBlank()) {
            Path path = Path.of(configFile).toAbsolutePath().normalize();
            if (Files.isRegularFile(path)) {
                Properties values = new Properties();
                try (InputStream input = Files.newInputStream(path)) {
                    values.load(input);
                    String currentEmail = values.getProperty("smtp.email", fallback.email()).trim();
                    String storedPassword = values.getProperty("smtp.appPassword", fallback.password());
                    String currentHost = values.getProperty("smtp.host", fallback.host()).trim();
                    int currentPort;
                    try { currentPort = Integer.parseInt(values.getProperty("smtp.port", Integer.toString(fallback.port())).trim()); }
                    catch (Exception ignored) { currentPort = 587; }
                    if (currentHost.isBlank()) currentHost = inferHost(currentEmail);
                    return new SettingsSummary(currentHost,currentPort,currentEmail,storedPassword!=null&&!storedPassword.isBlank());
                } catch (Exception exception) {
                    throw new IllegalStateException("Email/OTP settings metadata could not be read", exception);
                }
            }
        }
        String currentEmail=setting("smtp.email",fallback.email()).trim();
        String storedPassword=setting("smtp.appPassword",fallback.password());
        String currentHost=setting("smtp.host",fallback.host()).trim();
        int currentPort;
        try { currentPort=Integer.parseInt(setting("smtp.port",Integer.toString(fallback.port())).trim()); }
        catch(Exception ignored){ currentPort=587; }
        if(currentHost.isBlank())currentHost=inferHost(currentEmail);
        return new SettingsSummary(currentHost,currentPort,currentEmail,storedPassword!=null&&!storedPassword.isBlank());
    }

    /** Customer-facing company identity used by server-generated email surfaces. */
    public String companyName() {
        if (!configFile.isBlank()) {
            try {
                Path path = Path.of(configFile).toAbsolutePath().normalize();
                if (Files.isRegularFile(path)) {
                    Properties values = new Properties();
                    try (InputStream input = Files.newInputStream(path)) { values.load(input); }
                    String configured = values.getProperty("company.name", "").trim();
                    if (!configured.isBlank()) return configured;
                }
            } catch (Exception ignored) { java.lang.System.getLogger("org.example").log(java.lang.System.Logger.Level.DEBUG, "Suppressed exception: " + ignored.getMessage(), ignored); }
        }
        return org.example.server.communication.ServerNotificationTemplateService.forJdbc(db).companyName();
    }

    @Transactional
    public Settings saveSettings(String email, String password, String host, Integer port) {
        String cleanedEmail = email == null ? "" : email.trim();
        String requestedPassword = password == null ? "" : password.replaceAll("\\s+", "");
        String cleanedHost = host == null ? "" : host.trim();
        int cleanedPort = port == null ? 587 : port;
        if (cleanedEmail.isBlank()) throw new IllegalArgumentException("Sending email address is required.");
        if (cleanedPort < 1 || cleanedPort > 65535) throw new IllegalArgumentException("SMTP port must be between 1 and 65535.");

        /*
         * A newly entered App Password is authoritative. Do not decrypt or migrate
         * the previously stored secret first: after a server move/restore the old
         * value may have been protected by a different installation key, and that
         * must not block an Administrator from replacing it with a fresh secret.
         */
        String cleanedPassword;
        if (!requestedPassword.isBlank()) {
            cleanedPassword = requestedPassword;
        } else {
            try {
                cleanedPassword = settings().password();
            } catch (IllegalStateException failure) {
                throw new IllegalStateException(
                        "The existing email App Password cannot be read. Enter the App Password again and save the settings.",
                        failure);
            }
        }
        if (cleanedPassword.isBlank()) throw new IllegalArgumentException("Email app password is required the first time email is configured.");

        String encryptedPassword;
        try {
            encryptedPassword = SecretValueCodec.encrypt(cleanedPassword);
        } catch (IllegalStateException failure) {
            LOG.error("SMTP App Password encryption failed. Verify the server DSE_SECRET_KEY configuration.", failure);
            throw failure;
        }

        put("smtp.email", cleanedEmail);
        put("smtp.appPassword", encryptedPassword);
        put("smtp.host", cleanedHost);
        put("smtp.port", Integer.toString(cleanedPort));

        String effectiveHost = cleanedHost.isBlank() ? inferHost(cleanedEmail) : cleanedHost;
        return new Settings(effectiveHost, cleanedPort, cleanedEmail, cleanedPassword);
    }

    public void sendOtp(String recipient, String purpose, String code) {
        Settings settings = settings();
        if (settings.host().isBlank() || settings.email().isBlank() || settings.password().isBlank())
            throw new IllegalStateException("Email/OTP settings are not configured. Configure SMTP settings first.");
        try {
            InternetAddress destination = new InternetAddress(recipient, true);
            Session session = mailSession(settings, 10_000, 10_000);
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(settings.email()));
            message.setRecipient(Message.RecipientType.TO, destination);
            var templates = org.example.server.communication.ServerNotificationTemplateService.forJdbc(db);
            String brand = companyName();
            String safePurpose = purpose == null || purpose.isBlank() ? "verification" : purpose;
            String safeCode = code == null ? "" : code;
            String defaultSubject = brand + " " + safePurpose + " code";
            var otpCallout = org.example.shared.communication.TokenTemplateEngine.calloutBoxHtml(
                    "One-Time Verification Code",
                    safeCode,
                    "Expires in 10 minutes • Keep this code confidential",
                    "#2563eb",
                    "#eff6ff",
                    "#1e3a8a"
            );
            var otpDetails = org.example.shared.communication.TokenTemplateEngine.keyValueSectionHtml(
                    "Verification Request Details",
                    "#1e3a8a",
                    List.of(
                            new org.example.shared.communication.TokenTemplateEngine.KeyValueRow("Verification Purpose", safePurpose),
                            new org.example.shared.communication.TokenTemplateEngine.KeyValueRow("Recipient Email", recipient == null ? "" : recipient),
                            new org.example.shared.communication.TokenTemplateEngine.KeyValueRow("Validity Window", "10 Minutes", false, "#b45309", "#fef3c7")
                    )
            );
            var rendered = templates.renderRichEmail(
                    "auth_otp",
                    java.util.Map.of(
                            "CompanyName", brand,
                            "AppName", brand + " ERP",
                            "Purpose", safePurpose,
                            "OtpCode", safeCode,
                            "ExpiryMinutes", "10"
                    ),
                    new org.example.server.communication.ServerNotificationTemplateService.RichCardStyle(
                            "#0f172a", "#1e3a8a",
                            "IDENTITY & ACCOUNT SECURITY",
                            "OTP CODE",
                            "#1d4ed8",
                            brand + " Verification Code",
                            "Single-use security challenge",
                            "Your " + brand + " verification code is " + safeCode + " (valid for 10 minutes).",
                            List.of(),
                            List.of(otpCallout, otpDetails),
                            List.of(),
                            "Automated Security Verification • " + brand,
                            "If you did not initiate this request, you can safely ignore this message."
                    )
            );
            String finalSubject = rendered.subject() != null && !rendered.subject().isBlank() ? rendered.subject() : defaultSubject;
            message.setSubject(finalSubject);
            if (rendered.html()) {
                message.setContent(buildAlternativeBody(rendered.body()));
            } else {
                message.setText(rendered.body());
            }
            Transport.send(message);
        } catch (Exception exception) {
            EmailDeliveryException failure = EmailDeliveryException.verification(exception);
            LOG.warn("Verification email delivery failed via {}:{} for sender {}: {}", settings.host(), settings.port(), masked(settings.email()), failure.adminMessage());
            throw failure;
        }
    }

    public void sendBusiness(String recipient, String subject, String body) {
        sendBusiness(recipient, subject, body, (String) null, null, null, null);
    }

    public void sendBusiness(String recipient, String subject, String body, String attachmentName, byte[] attachment) {
        sendBusiness(recipient, subject, body, attachmentName, attachment, null, null);
    }

    public void sendBusiness(String recipient, String subject, String body, String attachmentName, byte[] attachment, String signatureImageName, byte[] signatureImage) {
        sendBusiness(recipient, subject, body, attachmentName, attachment, signatureImageName, signatureImage, null, null);
    }

    public void sendBusiness(String recipient, String subject, String body, String attachmentName, byte[] attachment, String signatureImageName, byte[] signatureImage, String customFromEmail, String customFromName) {
        List<Attachment> attachments = attachment == null || attachment.length == 0
                ? List.of()
                : List.of(new Attachment(attachmentName == null ? "document.pdf" : attachmentName,
                "application/octet-stream", attachment));
        Attachment sigAttachment = signatureImage == null || signatureImage.length == 0
                ? null
                : new Attachment(signatureImageName == null ? "signature.png" : signatureImageName,
                "image/png", signatureImage);
        sendBusiness(recipient, subject, body, attachments, sigAttachment, customFromEmail, customFromName);
    }

    public void sendBusiness(String recipient, String subject, String body, List<Attachment> attachments) {
        sendBusiness(recipient, subject, body, attachments, null, null, null);
    }

    public void sendBusiness(String recipient, String subject, String body, List<Attachment> attachments, Attachment signatureImage) {
        sendBusiness(recipient, subject, body, attachments, signatureImage, null, null);
    }

    public void sendBusiness(String recipient, String subject, String body, List<Attachment> attachments, Attachment signatureImage, String customFromEmail, String customFromName) {
        sendBusinessWithCopies(recipient, null, null, subject, body, attachments, signatureImage, customFromEmail, customFromName);
    }

    public void sendBusinessWithInlineImages(String recipient, String subject, String body, List<Attachment> attachments, List<InlineAttachment> inlineImages) {
        sendBusinessWithCopies(recipient, null, null, subject, body, attachments, inlineImages, null, null);
    }

    public void sendBusinessWithCopies(String recipient, String recipientCc, String recipientBcc, String subject, String body,
                                       List<Attachment> attachments, Attachment signatureImage, String customFromEmail, String customFromName) {
        List<InlineAttachment> inlineImages = (signatureImage != null && signatureImage.data() != null && signatureImage.data().length > 0)
                ? List.of(new InlineAttachment(
                        "signatureImage",
                        signatureImage.name() == null || signatureImage.name().isBlank() ? "signature.png" : signatureImage.name(),
                        signatureImage.contentType() == null || signatureImage.contentType().isBlank() ? "image/png" : signatureImage.contentType(),
                        signatureImage.data()))
                : List.of();
        sendBusinessWithCopies(recipient, recipientCc, recipientBcc, subject, body, attachments, inlineImages, customFromEmail, customFromName);
    }

    public void sendBusinessWithCopies(String recipient, String recipientCc, String recipientBcc, String subject, String body,
                                       List<Attachment> attachments, List<InlineAttachment> inlineImages, String customFromEmail, String customFromName) {
        Settings settings = settings();
        if (settings.host().isBlank() || settings.email().isBlank() || settings.password().isBlank())
            throw new IllegalStateException("Server business email is not configured");
        try {
            Session session = mailSession(settings, 15_000, 30_000);
            MimeMessage message = new MimeMessage(session);
            String fromEmail = (customFromEmail != null && !customFromEmail.isBlank()) ? customFromEmail.trim() : settings.email();
            String fromName = (customFromName != null && !customFromName.isBlank()) ? customFromName.trim() : companyName();
            try {
                message.setFrom(new InternetAddress(fromEmail, fromName));
            } catch (Exception ignored) {
                message.setFrom(new InternetAddress(fromEmail));
            }
            if (customFromEmail != null && !customFromEmail.isBlank() && !customFromEmail.equalsIgnoreCase(settings.email())) {
                try {
                    message.setReplyTo(new InternetAddress[]{ new InternetAddress(customFromEmail, fromName) });
                } catch (Exception ignored) {}
            }
            message.setRecipients(Message.RecipientType.TO, parseRecipientAddresses(recipient));
            if (recipientCc != null && !recipientCc.isBlank()) {
                message.setRecipients(Message.RecipientType.CC, parseRecipientAddresses(recipientCc));
            }
            if (recipientBcc != null && !recipientBcc.isBlank()) {
                message.setRecipients(Message.RecipientType.BCC, parseRecipientAddresses(recipientBcc));
            }
            message.setSubject(subject == null ? companyName() + " document" : subject);
            List<Attachment> files = attachments == null ? List.of() : attachments.stream()
                    .filter(a -> a != null && a.data() != null && a.data().length > 0).toList();
            List<InlineAttachment> cids = inlineImages == null ? List.of() : inlineImages.stream()
                    .filter(i -> i != null && i.data() != null && i.data().length > 0 && i.contentId() != null && !i.contentId().isBlank())
                    .toList();
            boolean hasSignatureCid = cids.stream().anyMatch(c -> "signatureImage".equals(c.contentId()));
            boolean isHtml = isHtmlContent(body);
            if (files.isEmpty() && cids.isEmpty()) {
                if (isHtml) {
                    message.setContent(buildAlternativeBody(body));
                } else {
                    message.setText(body == null ? "" : body);
                }
            } else {
                var rootMultipart = new jakarta.mail.internet.MimeMultipart("mixed");

                if (!cids.isEmpty()) {
                    var relatedMultipart = new jakarta.mail.internet.MimeMultipart("related");

                    var htmlWrapper = new jakarta.mail.internet.MimeBodyPart();
                    String htmlContent = formatHtmlEmailBody(body, hasSignatureCid);
                    htmlWrapper.setContent(buildAlternativeBody(htmlContent));
                    relatedMultipart.addBodyPart(htmlWrapper);

                    for (InlineAttachment inline : cids) {
                        var imgPart = new jakarta.mail.internet.MimeBodyPart();
                        String mime = inline.contentType() == null || inline.contentType().isBlank() ? "image/png" : inline.contentType();
                        String cid = inline.contentId().trim();
                        String name = inline.name() == null || inline.name().isBlank() ? cid + ".png" : inline.name();
                        imgPart.setDataHandler(new jakarta.activation.DataHandler(new jakarta.mail.util.ByteArrayDataSource(inline.data(), mime)));
                        imgPart.setContentID("<" + cid + ">");
                        imgPart.setHeader("Content-ID", "<" + cid + ">");
                        imgPart.setDisposition(jakarta.mail.Part.INLINE);
                        imgPart.setHeader("Content-Type", mime + "; name=\"" + name + "\"");
                        relatedMultipart.addBodyPart(imgPart);
                    }

                    var bodyWrapper = new jakarta.mail.internet.MimeBodyPart();
                    bodyWrapper.setContent(relatedMultipart);
                    rootMultipart.addBodyPart(bodyWrapper);
                } else {
                    var textWrapper = new jakarta.mail.internet.MimeBodyPart();
                    if (isHtml) {
                        textWrapper.setContent(buildAlternativeBody(body));
                    } else {
                        textWrapper.setText(body == null ? "" : body);
                    }
                    rootMultipart.addBodyPart(textWrapper);
                }

                for (Attachment attachment : files) {
                    var file = new jakarta.mail.internet.MimeBodyPart();
                    file.setFileName(attachment.name() == null || attachment.name().isBlank() ? "attachment" : attachment.name());
                    String mime = attachment.contentType() == null || attachment.contentType().isBlank() ? "application/octet-stream" : attachment.contentType();
                    file.setDataHandler(new jakarta.activation.DataHandler(new jakarta.mail.util.ByteArrayDataSource(attachment.data(), mime)));
                    file.setDisposition(jakarta.mail.Part.ATTACHMENT);
                    rootMultipart.addBodyPart(file);
                }
                message.setContent(rootMultipart);
            }
            Transport.send(message);
        } catch (Exception e) {
            EmailDeliveryException failure = EmailDeliveryException.business(e);
            LOG.warn("Business email delivery failed via {}:{} for sender {}: {}", settings.host(), settings.port(), masked(settings.email()), failure.adminMessage());
            throw failure;
        }
    }

    public static boolean isHtmlContent(String content) {
        if (content == null || content.isBlank()) return false;
        String trimmed = content.trim().toLowerCase(java.util.Locale.ROOT);
        return trimmed.startsWith("<") || trimmed.contains("<html") || trimmed.contains("<div") || trimmed.contains("<table") || trimmed.contains("<p>");
    }

    private static jakarta.mail.internet.MimeMultipart buildAlternativeBody(String htmlBody) throws jakarta.mail.MessagingException {
        var alternative = new jakarta.mail.internet.MimeMultipart("alternative");
        var plainPart = new jakarta.mail.internet.MimeBodyPart();
        plainPart.setText(stripHtmlToPlainText(htmlBody), "UTF-8");
        alternative.addBodyPart(plainPart);
        var htmlPart = new jakarta.mail.internet.MimeBodyPart();
        htmlPart.setContent(htmlBody == null ? "" : htmlBody, "text/html; charset=UTF-8");
        alternative.addBodyPart(htmlPart);
        return alternative;
    }

    private static String stripHtmlToPlainText(String html) {
        if (html == null || html.isBlank()) return "";
        String text = html
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
        return text.isBlank() ? "Please view this message in an HTML-compatible email client." : text;
    }

    private static String formatHtmlEmailBody(String body, boolean includeInlineSignatureImage) {
        if (body == null) body = "";
        boolean isHtml = isHtmlContent(body);
        String contentHtml;
        if (isHtml) {
            contentHtml = body;
        } else {
            String escaped = body
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\r\n", "\n")
                    .replace("\n", "<br/>\n");
            contentHtml = "<div>" + escaped + "</div>";
        }

        StringBuilder sb = new StringBuilder();
        if (!isHtml) {
            sb.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"></head><body style=\"margin:0; padding:12px; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; font-size: 14px; line-height: 1.6; color: #1e293b;\">");
            sb.append(contentHtml);
            if (includeInlineSignatureImage) {
                sb.append("<div style=\"margin-top: 16px; margin-bottom: 8px;\">");
                sb.append("<img src=\"cid:signatureImage\" alt=\"Signature / Stamp\" style=\"width: auto; max-width: 100%; max-height: 115px; height: auto; border: none; display: block;\" />");
                sb.append("</div>");
            }
            sb.append("</body></html>");
        } else {
            if (includeInlineSignatureImage && !contentHtml.contains("cid:signatureImage")) {
                String sigTag = "<div style=\"margin-top: 16px; margin-bottom: 8px;\"><img src=\"cid:signatureImage\" alt=\"Signature / Stamp\" style=\"width: auto; max-width: 100%; max-height: 115px; height: auto; border: none; display: block;\" /></div>";
                if (contentHtml.contains("</body>")) {
                    contentHtml = contentHtml.replace("</body>", sigTag + "</body>");
                } else {
                    contentHtml = contentHtml + sigTag;
                }
            }
            sb.append(contentHtml);
        }
        return sb.toString();
    }

    public void sendBusinessFiles(String recipient, String subject, String body, List<PathAttachment> attachments) {
        Settings settings = settings();
        if (settings.host().isBlank() || settings.email().isBlank() || settings.password().isBlank())
            throw new IllegalStateException("Server business email is not configured");
        try {
            Session session = mailSession(settings, 15_000, 30_000);
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(settings.email()));
            message.setRecipients(Message.RecipientType.TO, parseRecipientAddresses(recipient));
            message.setSubject(subject == null ? companyName() + " document" : subject);
            List<PathAttachment> files = attachments == null ? List.of() : attachments.stream()
                    .filter(a -> a != null && a.path() != null && Files.isRegularFile(a.path())).toList();
            boolean isHtml = isHtmlContent(body);
            if (files.isEmpty()) {
                if (isHtml) {
                    message.setContent(body == null ? "" : body, "text/html; charset=UTF-8");
                } else {
                    message.setText(body == null ? "" : body);
                }
            } else {
                var multipart = new jakarta.mail.internet.MimeMultipart();
                var text = new jakarta.mail.internet.MimeBodyPart();
                if (isHtml) {
                    text.setContent(body == null ? "" : body, "text/html; charset=UTF-8");
                } else {
                    text.setText(body == null ? "" : body);
                }
                multipart.addBodyPart(text);
                for (PathAttachment attachment : files) {
                    var file = new jakarta.mail.internet.MimeBodyPart();
                    file.attachFile(attachment.path().toFile());
                    file.setFileName(attachment.name() == null || attachment.name().isBlank()
                            ? attachment.path().getFileName().toString() : attachment.name());
                    if (attachment.contentType() != null && !attachment.contentType().isBlank())
                        file.setHeader("Content-Type", attachment.contentType());
                    multipart.addBodyPart(file);
                }
                message.setContent(multipart);
            }
            Transport.send(message);
        } catch (Exception e) {
            EmailDeliveryException failure = EmailDeliveryException.business(e);
            LOG.warn("Business email delivery failed via {}:{} for sender {}: {}", settings.host(), settings.port(), masked(settings.email()), failure.adminMessage());
            throw failure;
        }
    }

    private Session mailSession(Settings settings, int connectTimeout, int timeout) {
        Properties properties = new Properties();
        properties.put("mail.smtp.auth", "true");
        properties.put("mail.smtp.starttls.enable", "true");
        properties.put("mail.smtp.starttls.required", "true");
        properties.put("mail.smtp.host", settings.host());
        properties.put("mail.smtp.port", Integer.toString(settings.port()));
        properties.put("mail.smtp.connectiontimeout", Integer.toString(connectTimeout));
        properties.put("mail.smtp.timeout", Integer.toString(timeout));
        return Session.getInstance(properties, new Authenticator() {
            @Override protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(settings.email(), settings.password());
            }
        });
    }

    private Settings settings() {
        Settings fallback = new Settings(host, port, email, password);
        // LOCAL desktop servers explicitly provide the workspace config file.
        // Keeping this path authoritative preserves the current single-PC flow.
        if (!configFile.isBlank()) {
            Path path = Path.of(configFile).toAbsolutePath().normalize();
            if (Files.isRegularFile(path)) return settingsFromFile(path, fallback);
        }
        // Dedicated/company servers have no desktop config file: settings are
        // owned by the server database and can be changed by an Admin in-app.
        return settingsFromDatabase(fallback);
    }

    private Settings settingsFromFile(Path path, Settings fallback) {
        Properties values = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            values.load(input);
            String currentEmail = values.getProperty("smtp.email", fallback.email()).trim();
            String storedPassword = values.getProperty("smtp.appPassword", fallback.password());
            String currentPassword = SecretValueCodec.decrypt(storedPassword).replaceAll("\\s+", "");
            String currentHost = values.getProperty("smtp.host", fallback.host()).trim();
            String configuredPort = values.getProperty("smtp.port", Integer.toString(fallback.port())).trim();
            int currentPort = configuredPort.isBlank() ? 587 : Integer.parseInt(configuredPort);
            if (currentHost.isBlank()) currentHost = inferHost(currentEmail);
            return new Settings(currentHost, currentPort, currentEmail, currentPassword);
        } catch (Exception exception) {
            throw new IllegalStateException("Email/OTP settings could not be read", exception);
        }
    }

    private Settings settingsFromDatabase(Settings fallback) {
        String currentEmail = setting("smtp.email", fallback.email()).trim();
        String storedPassword = setting("smtp.appPassword", fallback.password());
        String currentPassword = SecretValueCodec.decrypt(storedPassword).replaceAll("\\s+", "");
        if(!storedPassword.isBlank()&&!SecretValueCodec.isEncrypted(storedPassword)) put("smtp.appPassword",SecretValueCodec.encrypt(currentPassword));
        String currentHost = setting("smtp.host", fallback.host()).trim();
        int currentPort;
        try { currentPort = Integer.parseInt(setting("smtp.port", Integer.toString(fallback.port())).trim()); }
        catch (Exception ignored) { currentPort = 587; }
        if (currentHost.isBlank()) currentHost = inferHost(currentEmail);
        return new Settings(currentHost, currentPort, currentEmail, currentPassword);
    }

    private String setting(String key, String fallback) {
        try {
            String value = db.queryForObject("SELECT setting_value FROM application_setting WHERE setting_key=?", String.class, key);
            return value == null ? (fallback == null ? "" : fallback) : value;
        } catch (Exception ignored) {
            return fallback == null ? "" : fallback;
        }
    }

    private void put(String key, String value) {
        db.update("INSERT INTO application_setting(setting_key,setting_value,updated_at) VALUES(?,?,CURRENT_TIMESTAMP) "
                + "ON CONFLICT(setting_key) DO UPDATE SET setting_value=excluded.setting_value,updated_at=CURRENT_TIMESTAMP", key, value);
    }

    private String inferHost(String value) {
        String address = value == null ? "" : value.toLowerCase();
        if (address.endsWith("@gmail.com") || address.endsWith("@googlemail.com")) return "smtp.gmail.com";
        if (address.endsWith("@outlook.com") || address.endsWith("@hotmail.com") || address.endsWith("@live.com")) return "smtp.office365.com";
        if (address.endsWith("@yahoo.com") || address.endsWith("@yahoo.in")) return "smtp.mail.yahoo.com";
        return "";
    }

    static InternetAddress[] parseRecipientAddresses(String raw) throws jakarta.mail.internet.AddressException {
        if (raw == null || raw.isBlank()) {
            throw new jakarta.mail.internet.AddressException("Recipient email address is required");
        }
        java.util.LinkedHashMap<String, InternetAddress> unique = new java.util.LinkedHashMap<>();
        for (String token : raw.split("[;,]")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) continue;
            InternetAddress address = new InternetAddress(trimmed, true);
            address.validate();
            unique.putIfAbsent(address.getAddress().toLowerCase(java.util.Locale.ROOT), address);
        }
        if (unique.isEmpty()) {
            throw new jakarta.mail.internet.AddressException("Recipient email address is required");
        }
        return unique.values().toArray(InternetAddress[]::new);
    }

    private static String masked(String address) {
        if (address == null || address.isBlank()) return "(not configured)";
        int at = address.indexOf('@');
        if (at <= 1) return "***" + (at >= 0 ? address.substring(at) : "");
        return address.substring(0, 1) + "***" + address.substring(at);
    }

    public record InlineAttachment(String contentId, String name, String contentType, byte[] data) {}
    public record Attachment(String name, String contentType, byte[] data) {}
    public record PathAttachment(String name, String contentType, Path path) {}
    public record Settings(String host, int port, String email, String password) {}
    public record SettingsSummary(String host, int port, String email, boolean passwordConfigured) {}
}
