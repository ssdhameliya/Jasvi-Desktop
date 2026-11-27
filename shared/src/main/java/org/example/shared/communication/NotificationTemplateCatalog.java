package org.example.shared.communication;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Canonical registry of all user-customizable Email & WhatsApp notification templates,
 * safe application_setting keys, supported tokens, and Email rendering mode settings.
 *
 * <p>Setting key naming rule: keys avoid the words {@code password}, {@code secret},
 * {@code token}, and {@code credential} so {@code SupportService} permits sharing them
 * safely across all clients in PostgreSQL {@code application_setting}.</p>
 */
public final class NotificationTemplateCatalog {
    public static final String EMAIL_FORMAT_MODE_KEY = "email.format.mode";
    public static final String EMAIL_TEMPLATE_PREFIX = "email.template.";
    public static final String WHATSAPP_TEMPLATE_PREFIX = "whatsapp.template.";

    public enum Category {
        BUSINESS_DOCUMENTS("Business Documents & Settlements"),
        COLLECTIONS_COMPLIANCE("Collections & Statutory GST"),
        AUTH_SECURITY("Authentication, Lockout & Recovery (Admin)"),
        SYSTEM_AUTOMATION("Scheduled Reports, Briefings & System (Admin)"),
        PLATFORM_LICENSING("Platform Licensing & Security Alerts (Super Admin)");

        private final String displayName;

        Category(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    public enum EmailFormatMode {
        INHERIT("Inherit Company Default"),
        HTML("HTML Card View"),
        STANDARD("Standard Plain Text");

        private final String displayName;

        EmailFormatMode(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }

        public static EmailFormatMode parse(String raw, EmailFormatMode fallback) {
            if (raw == null || raw.isBlank()) return fallback;
            return switch (raw.trim().toUpperCase(Locale.ROOT)) {
                case "HTML", "HTML CARD VIEW", "HTML_CARD" -> HTML;
                case "STANDARD", "PLAIN", "TEXT", "STANDARD PLAIN TEXT" -> STANDARD;
                case "INHERIT", "INHERIT COMPANY DEFAULT", "DEFAULT" -> INHERIT;
                default -> fallback;
            };
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    public record TemplateSpec(
            String key,
            String displayName,
            Category category,
            boolean supportsEmail,
            boolean supportsWhatsapp,
            boolean adminOnly,
            boolean superAdminOnly,
            List<String> tokens,
            String defaultEmailSubject,
            String defaultEmailBody,
            String defaultWhatsappBody
    ) {
        public String emailSubjectSettingKey() {
            return EMAIL_TEMPLATE_PREFIX + key + ".subject";
        }

        public String emailBodySettingKey() {
            return EMAIL_TEMPLATE_PREFIX + key + ".body";
        }

        public String emailFormatSettingKey() {
            return EMAIL_TEMPLATE_PREFIX + key + ".format";
        }

        public String whatsappBodySettingKey() {
            return WHATSAPP_TEMPLATE_PREFIX + key + ".body";
        }
    }

    private static final List<TemplateSpec> ALL_SPECS = List.of(
            // 1. Commercial Documents (preserving existing 8 keys & placeholder contracts)
            new TemplateSpec(
                    "sale", "Sales Invoice", Category.BUSINESS_DOCUMENTS, true, true, false, false,
                    List.of("{CustomerName}", "{DocumentNo}", "{DocumentDate}", "{TotalAmount}", "{PaidAmount}", "{BalanceAmount}", "{CompanyName}", "{Signature}"),
                    "Sales Invoice {DocumentNo} from {CompanyName}",
                    """
                            Dear {CustomerName},

                            Please find attached your sales invoice {DocumentNo} dated {DocumentDate}.

                            Invoice Summary:
                            • Invoice Number: {DocumentNo}
                            • Invoice Date: {DocumentDate}
                            • Total Amount: {TotalAmount}
                            • Balance Due: {BalanceAmount}

                            Thank you for your business.

                            {Signature}""",
                    "Hello *{CustomerName}*,\n\nPlease find attached sales invoice *{DocumentNo}* dated *{DocumentDate}* for total amount *{TotalAmount}* (Balance: *{BalanceAmount}*).\n\nThank you for your business!\n\nRegards,\n*{CompanyName}*"
            ),
            new TemplateSpec(
                    "quote", "Quotation", Category.BUSINESS_DOCUMENTS, true, true, false, false,
                    List.of("{CustomerName}", "{DocumentNo}", "{DocumentDate}", "{TotalAmount}", "{CompanyName}", "{Signature}"),
                    "Quotation {DocumentNo} from {CompanyName}",
                    """
                            Dear {CustomerName},

                            Thank you for your enquiry. Please find attached our quotation {DocumentNo} dated {DocumentDate} for total amount {TotalAmount}.

                            We look forward to serving you. Please let us know if you need any adjustments or further information.

                            {Signature}""",
                    "Hello *{CustomerName}*,\n\nPlease find attached quotation *{DocumentNo}* dated *{DocumentDate}* for total amount *{TotalAmount}*.\n\nLooking forward to your feedback!\n\nRegards,\n*{CompanyName}*"
            ),
            new TemplateSpec(
                    "purchase", "Purchase Order / Bill", Category.BUSINESS_DOCUMENTS, true, true, false, false,
                    List.of("{CustomerName}", "{DocumentNo}", "{DocumentDate}", "{TotalAmount}", "{BalanceAmount}", "{CompanyName}", "{Signature}"),
                    "Purchase Order {DocumentNo} from {CompanyName}",
                    """
                            Dear {CustomerName},

                            Please find attached our purchase order {DocumentNo} dated {DocumentDate}.

                            Kindly confirm receipt and proceed with fulfillment per agreed terms.

                            {Signature}""",
                    "Hello *{CustomerName}*,\n\nPlease find attached purchase order *{DocumentNo}* dated *{DocumentDate}*.\n\nRegards,\n*{CompanyName}*"
            ),
            new TemplateSpec(
                    "payment", "Payment Receipt", Category.BUSINESS_DOCUMENTS, true, true, false, false,
                    List.of("{CustomerName}", "{DocumentNo}", "{DocumentDate}", "{PaidAmount}", "{BalanceAmount}", "{PaymentMode}", "{PaymentReference}", "{CompanyName}", "{Signature}"),
                    "Payment Receipt for {DocumentNo} from {CompanyName}",
                    """
                            Dear {CustomerName},

                            Thank you for your payment. We have successfully received payment against document {DocumentNo}.

                            Receipt Summary:
                            • Document Number: {DocumentNo}
                            • Receipt Date: {DocumentDate}
                            • Amount Paid: {PaidAmount}
                            • Remaining Balance: {BalanceAmount}

                            Thank you for your business.

                            {Signature}""",
                    "Hello *{CustomerName}*,\n\nWe have received payment of *{PaidAmount}* against *{DocumentNo}* (Balance: *{BalanceAmount}*).\n\nThank you!\n\n*{CompanyName}*"
            ),
            new TemplateSpec(
                    "purchase_remittance", "Supplier Remittance Advice", Category.BUSINESS_DOCUMENTS, true, true, false, false,
                    List.of("{CustomerName}", "{DocumentNo}", "{TotalAmount}", "{PaidAmount}", "{BalanceAmount}", "{PaymentMode}", "{PaymentReference}", "{PaymentDate}", "{Narration}", "{CompanyName}", "{Signature}"),
                    "Payment Remittance Advice: Bill #{DocumentNo} - {CompanyName}",
                    """
                            Dear {CustomerName},

                            We have disbursed a payment settlement against Purchase Bill #{DocumentNo}.

                            Remittance Breakdown:
                            • Bill Total: {TotalAmount}
                            • Amount Disbursed: {PaidAmount}
                            • Balance Remaining: {BalanceAmount}
                            • Payment Mode: {PaymentMode}
                            • Reference / UTR: {PaymentReference}
                            • Settlement Date: {PaymentDate}
                            • Remarks / Narration: {Narration}

                            {Signature}""",
                    "Hello *{CustomerName}*,\n\nWe have disbursed payment of *{PaidAmount}* against Purchase Bill *{DocumentNo}* (Balance: *{BalanceAmount}*).\n\nRegards,\n*{CompanyName}*"
            ),
            new TemplateSpec(
                    "refund_confirmation", "Refund Settlement Confirmation", Category.BUSINESS_DOCUMENTS, true, true, false, false,
                    List.of("{CustomerName}", "{DocumentNo}", "{PaidAmount}", "{BalanceAmount}", "{PaymentMode}", "{PaymentReference}", "{PaymentDate}", "{Narration}", "{CompanyName}", "{Signature}"),
                    "Refund Settlement: {DocumentNo} - {CompanyName}",
                    """
                            Dear {CustomerName},

                            This is an official confirmation that a refund settlement has been issued for document #{DocumentNo}.

                            Settlement Breakdown:
                            • Refund Amount: {PaidAmount}
                            • Remaining Balance: {BalanceAmount}
                            • Refund Mode: {PaymentMode}
                            • Reference / UTR: {PaymentReference}
                            • Settlement Date: {PaymentDate}
                            • Remarks / Notes: {Narration}

                            {Signature}""",
                    "Hello *{CustomerName}*,\n\nRefund settlement of *{PaidAmount}* has been issued for document *{DocumentNo}*.\n\nRegards,\n*{CompanyName}*"
            ),
            new TemplateSpec(
                    "sales_return", "Sales Return / Credit Note", Category.BUSINESS_DOCUMENTS, true, true, false, false,
                    List.of("{CustomerName}", "{DocumentNo}", "{DocumentDate}", "{TotalAmount}", "{CompanyName}", "{Signature}"),
                    "Sales Return Note {DocumentNo} from {CompanyName}",
                    """
                            Dear {CustomerName},

                            Please find attached sales return / credit note {DocumentNo} dated {DocumentDate}.

                            • Total Credit: {TotalAmount}

                            {Signature}""",
                    "Hello *{CustomerName}*,\n\nPlease find attached sales return note *{DocumentNo}* for amount *{TotalAmount}*.\n\n*{CompanyName}*"
            ),
            new TemplateSpec(
                    "purchase_return", "Purchase Return / Debit Note", Category.BUSINESS_DOCUMENTS, true, true, false, false,
                    List.of("{CustomerName}", "{DocumentNo}", "{DocumentDate}", "{TotalAmount}", "{CompanyName}", "{Signature}"),
                    "Purchase Return Note {DocumentNo} from {CompanyName}",
                    """
                            Dear {CustomerName},

                            Please find attached purchase return / debit note {DocumentNo} dated {DocumentDate}.

                            • Total Amount: {TotalAmount}

                            {Signature}""",
                    "Hello *{CustomerName}*,\n\nPlease find attached purchase return note *{DocumentNo}* for amount *{TotalAmount}*.\n\n*{CompanyName}*"
            ),

            // 2. Collections & Statutory GST
            new TemplateSpec(
                    "aging_collection", "Overdue Collection Reminder", Category.COLLECTIONS_COMPLIANCE, true, true, false, false,
                    List.of("{PartyName}", "{CompanyName}", "{AsOfDate}", "{TotalOutstanding}", "{OverdueAmount}", "{OpenInvoiceCount}", "{UrgencySubjectPrefix}", "{UrgencyIntro}", "{PaymentDetails}", "{BankName}", "{BankAccount}", "{Ifsc}", "{UpiId}"),
                    "{UrgencySubjectPrefix} ({TotalOutstanding}) | {CompanyName}",
                    """
                            Dear {PartyName},

                            {UrgencyIntro}

                            Account Summary (As of {AsOfDate}):
                            • Total Outstanding: {TotalOutstanding}
                            • Overdue (>30 Days): {OverdueAmount}
                            • Open Invoices: {OpenInvoiceCount}

                            {PaymentDetails}

                            Kindly arrange settlement at your earliest convenience and share the payment reference. If already paid, please disregard this notice.

                            Regards,
                            {CompanyName}""",
                    """
                            Dear *{PartyName}*,

                            {UrgencyIntro}

                            *Account Summary (As of {AsOfDate}):*
                            • Total Outstanding: *{TotalOutstanding}*
                            • Overdue (>30 Days): *{OverdueAmount}*
                            • Open Invoices: *{OpenInvoiceCount}*

                            {PaymentDetails}

                            Kindly arrange settlement at your earliest convenience and share the payment reference. If already paid, please ignore this notice.

                            Regards,
                            *{CompanyName}*"""
            ),
            new TemplateSpec(
                    "gst_rule36_notice", "GST Rule 36(4) Discrepancy Notice", Category.COLLECTIONS_COMPLIANCE, true, true, false, false,
                    List.of("{SupplierName}", "{InvoiceNo}", "{InvoiceDate}", "{TaxableAmount}", "{TaxAmount}", "{CompanyName}", "{CompanyGstin}"),
                    "Urgent: GST Rule 36(4) Notice for Invoice {InvoiceNo}",
                    """
                            Dear {SupplierName},

                            Sub: Discrepancy Notice under CGST Rule 36(4) / Section 16(2)(aa) for Invoice #{InvoiceNo}

                            We have recorded Purchase Bill #{InvoiceNo} dated {InvoiceDate} for Taxable Value {TaxableAmount} and GST {TaxAmount} in our accounting system.
                            However, our automated GSTR-2B statutory reconciliation indicates this invoice is NOT reflected in our GSTR-2B return on the GST Portal.

                            Under statutory GST Rule 36(4), we are prohibited from claiming Input Tax Credit (ITC) for unreflected supplies. Kindly ensure this invoice is uploaded/amended in your GSTR-1 return immediately to prevent payment withholding or interest under Section 50.

                            Buyer Details:
                            • Name: {CompanyName}
                            • GSTIN: {CompanyGstin}

                            Thank you,
                            Finance & Taxation Department
                            {CompanyName}""",
                    """
                            Dear *{SupplierName}*,

                            *Sub: Discrepancy Notice under CGST Rule 36(4) for Invoice #{InvoiceNo}*

                            Purchase Bill *#{InvoiceNo}* dated *{InvoiceDate}* (Taxable: {TaxableAmount}, GST: {TaxAmount}) is not reflected in our GSTR-2B return on the GST Portal.

                            Kindly upload/amend this invoice in your GSTR-1 immediately so we can avoid withholding payment or Section 50 interest.

                            • Buyer: {CompanyName}
                            • GSTIN: {CompanyGstin}"""
            ),

            // 3. Authentication, Lockout & Recovery (Admin only)
            new TemplateSpec(
                    "auth_otp", "Verification / OTP Email Code", Category.AUTH_SECURITY, true, false, true, false,
                    List.of("{CompanyName}", "{Purpose}", "{OtpCode}", "{ExpiryMinutes}"),
                    "{CompanyName} {Purpose} code",
                    "Your {CompanyName} verification code is {OtpCode}. It expires in {ExpiryMinutes} minutes. If you did not request this, ignore this email.",
                    null
            ),
            new TemplateSpec(
                    "mfa_enrollment", "Authenticator App Enrollment", Category.AUTH_SECURITY, true, false, true, false,
                    List.of("{CompanyName}", "{Username}", "{ManualSecret}", "{ProvisioningUri}"),
                    "{CompanyName} authenticator enrollment",
                    """
                            Authenticator enrollment for {CompanyName}

                            • Username: {Username}
                            • Manual setup key: {ManualSecret}
                            • Provisioning URI: {ProvisioningUri}

                            Add this account to Google Authenticator, Microsoft Authenticator, or another RFC-6238 compatible app before signing in.""",
                    null
            ),
            new TemplateSpec(
                    "security_lock_superadmin", "Admin Lockout Escalation (To Super Admin)", Category.AUTH_SECURITY, true, true, true, false,
                    List.of("{AppName}", "{CompanyName}", "{TicketId}", "{AdminName}", "{Username}", "{AdminEmail}", "{AdminPhone}", "{Branch}", "{Department}", "{LockReason}", "{ClientIp}", "{Timestamp}", "{UnlockUrl}", "{ResetUrl}"),
                    "🚨 URGENT: {AppName} Administrator Account Locked [Ticket #{TicketId}]",
                    """
                            An Administrator account has been locked in {AppName} following repeated failed verification attempts.

                            • Incident Ticket: #{TicketId}
                            • Administrator: {AdminName} (User: {Username})
                            • Registered Email: {AdminEmail}
                            • Registered Phone: {AdminPhone}
                            • Branch & Dept: {Branch} | {Department}
                            • Lock Reason: {LockReason}
                            • Workstation / IP: {ClientIp}
                            • Incident Time: {Timestamp}

                            Option 1 — Unlock Account (Preserve Password): {UnlockUrl}
                            Option 2 — Unlock & Reset to Temporary Password: {ResetUrl}""",
                    """
                            🚨 *URGENT: {AppName} Administrator Account Locked*
                            *Ticket ID:* #{TicketId}
                            *Priority:* Critical

                            An Administrator account has been locked:
                            • *Admin:* {AdminName} (User: {Username})
                            • *Email:* {AdminEmail}
                            • *Mobile:* {AdminPhone}
                            • *Branch / Dept:* {Branch} | {Department}
                            • *Reason:* {LockReason}
                            • *Time:* {Timestamp}
                            • *Workstation:* {ClientIp}

                            *Choose an immediate action below (Single-use, 24h validity):*

                            🔓 *Option 1: Unlock Account (Preserve Password)*
                            {UnlockUrl}

                            🔑 *Option 2: Unlock & Reset to Temporary Password*
                            {ResetUrl}

                            _{AppName} Autonomous Security Incident Engine_"""
            ),
            new TemplateSpec(
                    "security_lock_admin", "Admin Lockout Advisory (To Locked Admin)", Category.AUTH_SECURITY, true, true, true, false,
                    List.of("{AppName}", "{CompanyName}", "{TicketId}", "{AdminName}", "{Username}", "{SuperAdminEmail}", "{SuperAdminPhone}", "{Timestamp}", "{StatusUrl}"),
                    "⚠️ Security Notice: Your Administrator Account is Locked [Ticket #{TicketId}]",
                    """
                            Dear {AdminName} ({Username}),

                            Your {AppName} Administrator account has been locked following failed verification attempts on {Timestamp}.

                            • Incident Ticket: #{TicketId}
                            • Escalated To: {SuperAdminEmail} / {SuperAdminPhone}
                            • Live Status URL: {StatusUrl}

                            Please allow up to 30 minutes for Super Admin review. Keep your Desktop login screen open for live unlock detection.""",
                    """
                            ⚠️ *Security Notice: Administrator Account Locked*
                            *Ticket ID:* #{TicketId}

                            Dear {AdminName} ({Username}),
                            Your {AppName} Administrator account has been locked following consecutive failed verification attempts.

                            An emergency report has been dispatched to Super Admin ({SuperAdminEmail} / {SuperAdminPhone}).

                            🔍 *Track Live Status Online:*
                            {StatusUrl}

                            *Advisory:* Keep your Desktop login screen open for live resolution tracking.

                            _{AppName} Autonomous Security Incident Engine_"""
            ),
            new TemplateSpec(
                    "security_unlock", "Admin Account Unlocked Confirmation", Category.AUTH_SECURITY, true, true, true, false,
                    List.of("{AppName}", "{CompanyName}", "{TicketId}", "{FullName}", "{Username}"),
                    "🎉 Account Unlocked: {AppName} [Ticket #{TicketId}]",
                    """
                            Dear {FullName} (User: {Username}),

                            Your {AppName} Administrator account has been unlocked by Super Admin.

                            • Ticket ID: #{TicketId}
                            • Resolution Status: ACTIVE & UNLOCKED
                            • Password Status: Existing password preserved

                            You can now sign in immediately on your Desktop workstation using your existing password.""",
                    """
                            🎉 *{AppName} Account Unlocked*
                            *Ticket ID:* #{TicketId}

                            Dear {FullName},
                            Your Administrator account has been unlocked by Super Admin. You may now sign in on your Desktop workstation using your existing password."""
            ),
            new TemplateSpec(
                    "security_recovery_reset", "Admin Temporary Access Reset Dispatch", Category.AUTH_SECURITY, true, true, true, false,
                    List.of("{AppName}", "{CompanyName}", "{TicketId}", "{FullName}", "{Username}", "{TempPassword}", "{UserEmail}"),
                    "🔑 Temporary Password Dispatched: {AppName} [Ticket #{TicketId}]",
                    """
                            Dear {FullName} (User: {Username}),

                            Your {AppName} Administrator account has been unlocked and assigned a secure temporary sign-in password by Super Admin.

                            • Ticket ID: #{TicketId}
                            • Temporary Sign-In Password: {TempPassword}
                            • Security Policy: You must set a new permanent password immediately upon sign-in (cannot reuse the temporary password or your last 3 passwords).

                            Please sign in on the Desktop workstation and complete your password update.""",
                    """
                            🔑 *{AppName} Password Reset*
                            *Ticket ID:* #{TicketId}

                            Dear {FullName},
                            Your Administrator account has been unlocked by Super Admin.
                            A secure temporary password has been dispatched to your email ({UserEmail}). Please sign in on the Desktop workstation and set your permanent password."""
            ),

            // 4. Scheduled Reports, Briefings & System (Admin only)
            new TemplateSpec(
                    "briefing_dispatch", "Executive Briefing Summary", Category.SYSTEM_AUTOMATION, true, true, true, false,
                    List.of("{CompanyName}", "{CadenceLabel}", "{PeriodLabel}", "{Environment}", "{PortalUrl}", "{MetricsSummary}"),
                    "{CompanyName} — {CadenceLabel} ({PeriodLabel})",
                    """
                            {CompanyName} — {CadenceLabel}
                            {PeriodLabel}

                            {MetricsSummary}

                            Portal: {PortalUrl}
                            Dispatched automatically by {CompanyName} ERP Scheduled Automation Suite.""",
                    """
                            📊 *{CompanyName} — {CadenceLabel}*
                            📅 *Period:* {PeriodLabel}
                            ━━━━━━━━━━━━━━━━━━━━━━━━━
                            {MetricsSummary}
                            ━━━━━━━━━━━━━━━━━━━━━━━━━
                            _Automated briefing generated by {CompanyName} ERP Engine._"""
            ),
            new TemplateSpec(
                    "storage_alert", "Cloud Storage Health Warning", Category.SYSTEM_AUTOMATION, true, true, true, false,
                    List.of("{Level}", "{Environment}", "{ServerHost}", "{UsagePercent}", "{TotalGb}", "{UsedGb}", "{FreeGb}", "{Timestamp}"),
                    "[{Level}] Cloud Storage Alert: {Environment} Server ({ServerHost})",
                    """
                            Storage utilization on {Environment} server ({ServerHost}) has reached {UsagePercent}%.

                            • Alert Level: {Level}
                            • Total Disk Space: {TotalGb} GB
                            • Used Disk Space: {UsedGb} GB ({UsagePercent}%)
                            • Available Space: {FreeGb} GB
                            • Timestamp: {Timestamp}

                            Action Required: Review disk usage, clear old archives, or allocate additional volume space.""",
                    """
                            ⚠️ *[{Level}] Cloud Storage Alert: {Environment} Server ({ServerHost})*

                            Storage on {Environment} server ({ServerHost}) has reached *{UsagePercent}%* utilization.
                            • Total Disk Space: {TotalGb} GB
                            • Used Disk Space: {UsedGb} GB ({UsagePercent}%)
                            • Available Space: {FreeGb} GB

                            Action Required: Please review disk usage, clear old archives, or allocate additional volume space.
                            Timestamp: {Timestamp}"""
            ),
            new TemplateSpec(
                    "scheduled_report", "Scheduled Saved Report Email", Category.SYSTEM_AUTOMATION, true, false, true, false,
                    List.of("{CompanyName}", "{ScheduleName}", "{ReportTitle}", "{PeriodFrom}", "{PeriodTo}", "{GeneratedAt}", "{RowCount}"),
                    "{CompanyName} Scheduled Report - {ReportTitle}",
                    """
                            Scheduled Report Delivery ({CompanyName})

                            • Schedule Name: {ScheduleName}
                            • Report: {ReportTitle}
                            • Period: {PeriodFrom} to {PeriodTo}
                            • Generated: {GeneratedAt}
                            • Rows: {RowCount}""",
                    null
            ),
            new TemplateSpec(
                    "smtp_test", "SMTP Test Connection Email", Category.SYSTEM_AUTOMATION, true, false, true, false,
                    List.of("{CompanyName}"),
                    "{CompanyName} email test",
                    "Your {CompanyName} company-server email configuration is working correctly.",
                    null
            ),

            // 5. Platform Licensing (Super Admin only)
            new TemplateSpec(
                    "rogue_alert", "Unlicensed Instance Security Alert", Category.PLATFORM_LICENSING, true, true, true, true,
                    List.of("{CompanyName}", "{MachineId}", "{ClientIp}", "{Users}", "{ClientVersion}", "{KickUrl}", "{SupportBrand}"),
                    "🚨 Security Alert: Unlicensed Local Instance Detected - {CompanyName}",
                    """
                            An unauthorized or unlicensed local workstation has connected to the {SupportBrand} ecosystem without an active plan.

                            • Organization / Company: {CompanyName}
                            • Terminal / Machine ID: {MachineId}
                            • Public IP Address: {ClientIp}
                            • Reported Active Users: {Users}
                            • Client Version: {ClientVersion}
                            • Plan Status: UNLICENSED / NO PLAN

                            Instant Kick & Block URL: {KickUrl}""",
                    """
                            🚨 *SECURITY ALERT: UNLICENSED INSTANCE*

                            An unauthorized local workstation has connected without an active plan:

                            🏢 *Company:* {CompanyName}
                            💻 *Machine ID:* {MachineId}
                            🌐 *IP Address:* {ClientIp}
                            👤 *Reported Users:* {Users}
                            📦 *Version:* {ClientVersion}
                            ⚠️ *Plan Status:* NO PLAN / UNLICENSED

                            🛑 *INSTANT 1-CLICK KICK & BLOCK:*
                            Tap below to terminate sessions and lock this workstation:
                            {KickUrl}"""
            ),
            new TemplateSpec(
                    "license_activation", "Enterprise License Activation Delivery", Category.PLATFORM_LICENSING, true, true, true, true,
                    List.of("{CompanyName}", "{Tier}", "{MachineId}", "{ValidUntil}", "{JlicFilename}", "{JsonFilename}", "{IssuerBrand}", "{SupportEmail}"),
                    "Official {IssuerBrand} Enterprise License Activation - {CompanyName}",
                    """
                            Dear {CompanyName},

                            Your {IssuerBrand} production license has been issued and approved. Both your digital license file ({JlicFilename}) and readable JSON certificate ({JsonFilename}) are attached.

                            • Registered Client: {CompanyName}
                            • Subscription Tier: {Tier}
                            • Hardware Terminal ID: {MachineId}
                            • Validity Period: Until {ValidUntil}
                            • Support: {SupportEmail}

                            How to Activate:
                            1. Download the attached {JlicFilename} (or {JsonFilename}) file.
                            2. Open the ERP Desktop application.
                            3. Open Settings > License and click Import .jlic File to activate immediately.""",
                    """
                            Dear {CompanyName},

                            🎉 Your {IssuerBrand} Production License is ACTIVE!

                            🏢 Company: {CompanyName}
                            💻 Machine ID: {MachineId}
                            💎 Plan Tier: {Tier}
                            ⏳ Valid Until: {ValidUntil}

                            📄 Attached: Official License ({JlicFilename}) & JSON certificate ({JsonFilename}).
                            To activate: Open Desktop > Settings > License > Import License File.

                            Thank you for choosing {IssuerBrand}!"""
            )
    );

    private static final Map<String, TemplateSpec> BY_KEY;
    static {
        Map<String, TemplateSpec> map = new LinkedHashMap<>();
        for (TemplateSpec spec : ALL_SPECS) {
            map.put(spec.key(), spec);
        }
        BY_KEY = Map.copyOf(map);
    }

    private NotificationTemplateCatalog() {}

    public static List<TemplateSpec> all() {
        return ALL_SPECS;
    }

    public static Optional<TemplateSpec> byKey(String key) {
        if (key == null || key.isBlank()) return Optional.empty();
        return Optional.ofNullable(BY_KEY.get(key.trim().toLowerCase(Locale.ROOT)));
    }

    public static List<TemplateSpec> forChannelAndAccess(boolean whatsapp, boolean isAdmin, boolean isSuperAdmin) {
        return ALL_SPECS.stream()
                .filter(spec -> whatsapp ? spec.supportsWhatsapp() : spec.supportsEmail())
                .filter(spec -> !spec.adminOnly() || isAdmin || isSuperAdmin)
                .filter(spec -> !spec.superAdminOnly() || isSuperAdmin)
                .toList();
    }

    public static boolean requiresAdminRole(String settingKey) {
        String key = extractTemplateKey(settingKey);
        return key != null && byKey(key).map(TemplateSpec::adminOnly).orElse(false);
    }

    public static boolean requiresSuperAdminRole(String settingKey) {
        String key = extractTemplateKey(settingKey);
        return key != null && byKey(key).map(TemplateSpec::superAdminOnly).orElse(false);
    }

    public static String extractTemplateKey(String settingKey) {
        if (settingKey == null) return null;
        String trimmed = settingKey.trim().toLowerCase(Locale.ROOT);
        String remainder;
        if (trimmed.startsWith(EMAIL_TEMPLATE_PREFIX)) {
            remainder = trimmed.substring(EMAIL_TEMPLATE_PREFIX.length());
        } else if (trimmed.startsWith(WHATSAPP_TEMPLATE_PREFIX)) {
            remainder = trimmed.substring(WHATSAPP_TEMPLATE_PREFIX.length());
        } else {
            return null;
        }
        int dot = remainder.lastIndexOf('.');
        return dot > 0 ? remainder.substring(0, dot) : null;
    }

    public static Map<String, String> sampleTokens(String companyName, String formattedDate, String signature) {
        String safeCompany = companyName == null || companyName.isBlank() ? "Sample Enterprise Ltd" : companyName.trim();
        String safeDate = formattedDate == null || formattedDate.isBlank() ? "26 Nov 2026" : formattedDate.trim();
        Map<String, String> tokens = new LinkedHashMap<>();
        // Business documents
        tokens.put("CustomerName", "Shree Hari Traders");
        tokens.put("PartyName", "Shree Hari Traders");
        tokens.put("SupplierName", "Apex Precision Components");
        tokens.put("DocumentNo", "INV/26-11-2026/0042");
        tokens.put("DocumentDate", safeDate);
        tokens.put("TotalAmount", "₹ 1,25,000.00");
        tokens.put("PaidAmount", "₹ 75,000.00");
        tokens.put("BalanceAmount", "₹ 50,000.00");
        tokens.put("PaymentMode", "NEFT / RTGS");
        tokens.put("PaymentReference", "UTR20261126009182");
        tokens.put("PaymentDate", safeDate);
        tokens.put("Narration", "Partial settlement against running invoice");
        tokens.put("CompanyName", safeCompany);
        tokens.put("AppName", safeCompany + " ERP");
        tokens.put("CompanyGstin", "24ABCDE1234F1Z5");
        tokens.put("Signature", signature == null || signature.isBlank() ? "Regards,\n" + safeCompany : signature);

        // Collections & GST
        tokens.put("AsOfDate", safeDate);
        tokens.put("TotalOutstanding", "₹ 1,85,400.00");
        tokens.put("OverdueAmount", "₹ 64,200.00");
        tokens.put("OpenInvoiceCount", "4");
        tokens.put("UrgencySubjectPrefix", "Payment Reminder & Outstanding Statement");
        tokens.put("UrgencyIntro", "This is a polite reminder regarding your overdue balance with " + safeCompany + ".");
        tokens.put("BankName", "State Bank of India");
        tokens.put("BankAccount", "XXXX4819");
        tokens.put("Ifsc", "SBIN0001234");
        tokens.put("UpiId", "accounts@example.upi");
        tokens.put("PaymentDetails", "Payment Details:\n• Bank: State Bank of India | A/C: XXXX4819 | IFSC: SBIN0001234\n• UPI: accounts@example.upi");
        tokens.put("InvoiceNo", "PUR-2026-881");
        tokens.put("InvoiceDate", safeDate);
        tokens.put("TaxableAmount", "₹ 50,000.00");
        tokens.put("TaxAmount", "₹ 9,000.00");

        // Auth & Security
        tokens.put("Purpose", "password reset");
        tokens.put("OtpCode", "482910");
        tokens.put("ExpiryMinutes", "10");
        tokens.put("Username", "officeadmin");
        tokens.put("ManualSecret", "JBSWY3DPEHPK3PXP");
        tokens.put("ProvisioningUri", "otpauth://totp/ERP:officeadmin?secret=JBSWY3DPEHPK3PXP");
        tokens.put("TicketId", "SEC-482190");
        tokens.put("AdminName", "Office Administrator");
        tokens.put("FullName", "Office Administrator");
        tokens.put("AdminEmail", "admin@example.com");
        tokens.put("AdminPhone", "+91 9876543210");
        tokens.put("Branch", "Main Branch");
        tokens.put("Department", "Administration");
        tokens.put("LockReason", "PERMANENT_LOCK");
        tokens.put("ClientIp", "192.168.1.45");
        tokens.put("Timestamp", safeDate + " 10:15 AM IST");
        tokens.put("UnlockUrl", "https://erp.example.com/api/security/emergency/action?token=sample-unlock");
        tokens.put("ResetUrl", "https://erp.example.com/api/security/emergency/action?token=sample-reset");
        tokens.put("StatusUrl", "https://erp.example.com/api/security/emergency/status?ticket=SEC-482190");
        tokens.put("SuperAdminEmail", "security@example.com");
        tokens.put("SuperAdminPhone", "+91 9000011111");
        tokens.put("TempPassword", "Xk9!mP4#qR7z");
        tokens.put("UserEmail", "admin@example.com");

        // System Automation & Briefings
        tokens.put("CadenceLabel", "Executive Daily Briefing");
        tokens.put("PeriodLabel", "Evening Performance Summary • " + safeDate);
        tokens.put("Environment", "PROD");
        tokens.put("PortalUrl", "https://erp.example.com");
        tokens.put("MetricsSummary", "• Invoiced Sales: ₹ 2,40,000.00 (6 Invoices)\n• Customer Collections: ₹ 1,95,000.00 (4 Receipts)\n• Net Cash Flow: +₹ 1,10,000.00 (Surplus)");
        tokens.put("Level", "WARNING");
        tokens.put("ServerHost", "erp-app-01");
        tokens.put("UsagePercent", "82.4");
        tokens.put("TotalGb", "100");
        tokens.put("UsedGb", "82");
        tokens.put("FreeGb", "18");
        tokens.put("ScheduleName", "Daily Sales Register");
        tokens.put("ReportTitle", "Sales Summary Report");
        tokens.put("PeriodFrom", safeDate);
        tokens.put("PeriodTo", safeDate);
        tokens.put("GeneratedAt", safeDate + " 08:00 PM IST");
        tokens.put("RowCount", "48");

        // Licensing
        tokens.put("MachineId", "WIN-77A1-99C4");
        tokens.put("Users", "officeadmin, sales01");
        tokens.put("ClientVersion", org.example.shared.RuntimeContract.appVersion());
        tokens.put("KickUrl", "https://erp.example.com/api/license/super/kick-client?token=sample-kick");
        tokens.put("SupportBrand", safeCompany);
        tokens.put("Tier", "ENTERPRISE");
        tokens.put("ValidUntil", "31-Dec-2027");
        tokens.put("JlicFilename", "Sample_Enterprise-license.jlic");
        tokens.put("JsonFilename", "Sample_Enterprise-license.json");
        tokens.put("IssuerBrand", safeCompany + " ERP");
        tokens.put("SupportEmail", "support@example.com");
        return tokens;
    }
}
