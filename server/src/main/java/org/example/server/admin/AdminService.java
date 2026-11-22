package org.example.server.admin;

import org.example.server.master.RoleMasterService;
import org.example.server.persistence.JpaNativeRepository;
import org.example.server.security.CurrentUser;
import org.example.server.security.TokenService;
import org.example.server.util.BusinessClock;
import org.example.server.web.ConcurrentEditException;
import org.example.shared.SecretValueCodec;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AdminService {
    private static final long USER_AUTHORITY_LOCK = 51018047L;
    private final JpaNativeRepository jdbc;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    private final RoleMasterService roleMaster;
    private final String workspacePath;

    private static final java.util.regex.Pattern SPRING_BOOT_LOG_PATTERN = java.util.regex.Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?)\\s+(\\w+)\\s+(?:\\d+\\s+---\\s+)?(?:\\[(.*?)\\]\\s+)?\\[(.*?)\\]\\s+([^:]+?)\\s+:\\s+(.*)$"
    );
    private static final java.util.regex.Pattern LEGACY_SERVER_LOG_PATTERN = java.util.regex.Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?)\\s+\\[(.*?)\\]\\s+(\\w+)\\s+([\\w\\.]+)\\s+-\\s+(.*)$"
    );

    public AdminService(JpaNativeRepository jdbc, PasswordEncoder passwords, TokenService tokens,
                        RoleMasterService roleMaster) {
        this(jdbc, passwords, tokens, roleMaster, "");
    }

    @Autowired
    public AdminService(JpaNativeRepository jdbc, PasswordEncoder passwords, TokenService tokens,
                        RoleMasterService roleMaster,
                        @org.springframework.beans.factory.annotation.Value("${dse.workspace.path:}") String workspacePath) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.tokens = tokens;
        this.roleMaster = roleMaster;
        this.workspacePath = workspacePath != null ? workspacePath.trim() : "";
    }

    @PostConstruct
    public void ensurePermissions() {
        try {
            jdbc.update("""
                INSERT INTO permissions(permission_key, module_name, action_name, description) VALUES
                ('GST_COMPLIANCE.VIEW', 'GST_COMPLIANCE', 'VIEW', 'Open GST Compliance Center and tax filing summaries'),
                ('GST_COMPLIANCE.EXPORT', 'GST_COMPLIANCE', 'EXPORT', 'Export GSTR-1, GSTR-2B and GSTR-3B workbooks and returns'),
                ('GST_COMPLIANCE.RECONCILE', 'GST_COMPLIANCE', 'RECONCILE', 'Execute GSTR-2B automated reconciliation'),
                ('GENERAL_LEDGER.VIEW', 'GENERAL_LEDGER', 'VIEW', 'View Chart of Accounts, Trial Balance, P&L, Balance Sheet, and Journal Vouchers'),
                ('GENERAL_LEDGER.CREATE', 'GENERAL_LEDGER', 'CREATE', 'Create Journal Vouchers and add Accounts'),
                ('GENERAL_LEDGER.POST', 'GENERAL_LEDGER', 'POST', 'Post manual Journal Vouchers to General Ledger'),
                ('GENERAL_LEDGER.EXPORT', 'GENERAL_LEDGER', 'EXPORT', 'Export financial statements and ledger reports'),
                ('AUTOMATION.VIEW', 'AUTOMATION', 'VIEW', 'Open Automation Center and view rule statuses'),
                ('AUTOMATION.EDIT', 'AUTOMATION', 'EDIT', 'Configure automation rules, tolerances, and thresholds'),
                ('AUTOMATION.EXECUTE', 'AUTOMATION', 'EXECUTE', 'Manually trigger 3-way match, stock replenishment, and scheduled rules'),
                ('CREDIT_NOTE.VIEW', 'CREDIT_NOTE', 'VIEW', 'Open Credit Note Register and inspect sales adjustments'),
                ('CREDIT_NOTE.CREATE', 'CREDIT_NOTE', 'CREATE', 'Issue new Sales Credit Notes'),
                ('CREDIT_NOTE.EDIT', 'CREDIT_NOTE', 'EDIT', 'Edit draft or pending Credit Notes'),
                ('CREDIT_NOTE.PRINT', 'CREDIT_NOTE', 'PRINT', 'Print Sales Credit Notes'),
                ('DEBIT_NOTE.VIEW', 'DEBIT_NOTE', 'VIEW', 'Open Debit Note Register and inspect supplier adjustments'),
                ('DEBIT_NOTE.CREATE', 'DEBIT_NOTE', 'CREATE', 'Issue new Purchase Debit Notes'),
                ('DEBIT_NOTE.EDIT', 'DEBIT_NOTE', 'EDIT', 'Edit draft or pending Debit Notes'),
                ('DEBIT_NOTE.PRINT', 'DEBIT_NOTE', 'PRINT', 'Print Purchase Debit Notes'),
                ('FINANCIAL_STATEMENTS.VIEW', 'FINANCIAL_STATEMENTS', 'VIEW', 'View Profit & Loss and Balance Sheet statements'),
                ('FINANCIAL_STATEMENTS.EXPORT', 'FINANCIAL_STATEMENTS', 'EXPORT', 'Export Financial Statements to Excel / PDF'),
                ('AGING_ANALYSIS.VIEW', 'AGING_ANALYSIS', 'VIEW', 'View Accounts Receivable and Accounts Payable Aging'),
                ('AGING_ANALYSIS.REMIND', 'AGING_ANALYSIS', 'REMIND', 'Send overdue payment reminders to customers'),
                ('AGING_ANALYSIS.EXPORT', 'AGING_ANALYSIS', 'EXPORT', 'Export AR/AP Aging reports'),
                ('EWAY_BILL.VIEW', 'EWAY_BILL', 'VIEW', 'View E-Way Bill and transport register'),
                ('EWAY_BILL.GENERATE', 'EWAY_BILL', 'GENERATE', 'Generate and export NIC E-Way Bill JSON'),
                ('EWAY_BILL.UPDATE_VEHICLE', 'EWAY_BILL', 'UPDATE_VEHICLE', 'Update transporter, vehicle number, and distance'),
                ('BARCODE.VIEW', 'BARCODE', 'VIEW', 'Open Barcode Label Studio'),
                ('BARCODE.PRINT', 'BARCODE', 'PRINT', 'Generate and print thermal barcode / QR labels'),
                ('TALLY.VIEW', 'TALLY', 'VIEW', 'Open Bill to Tally Automation workspace and review batches'),
                ('TALLY.IMPORT', 'TALLY', 'IMPORT', 'Upload bills (Images/PDFs/Excel) and process OCR batches'),
                ('TALLY.EDIT', 'TALLY', 'EDIT', 'Edit extracted bill data and Tally ledger/item mappings'),
                ('TALLY.EXPORT', 'TALLY', 'EXPORT', 'Export Tally JSON and Excel voucher files'),
                ('TALLY.PUSH', 'TALLY', 'PUSH', 'Direct sync to TallyPrime (Port 9000)'),
                ('MULTI_BRANCH.VIEW', 'MULTI_BRANCH', 'VIEW', 'View and switch across multiple company branches'),
                ('OFFLINE_SYNC.VIEW', 'OFFLINE_SYNC', 'VIEW', 'View and synchronize offline peer-to-peer workstation data'),
                ('BANK_EXPENSE.STATEMENT', 'BANK_EXPENSE', 'STATEMENT', 'View and inspect bank statements'),
                ('BANK_EXPENSE.ENTRY', 'BANK_EXPENSE', 'ENTRY', 'Record bank voucher entries'),
                ('BANK_EXPENSE.EXPENSE', 'BANK_EXPENSE', 'EXPENSE', 'Record and manage expense entries'),
                ('SALES.DUPLICATE', 'SALES', 'DUPLICATE', 'Duplicate existing sales invoice'),
                ('SALES.EMAIL', 'SALES', 'EMAIL', 'Send sales invoice via email'),
                ('SALES.WHATSAPP', 'SALES', 'WHATSAPP', 'Send sales invoice via WhatsApp'),
                ('SALES.RETURN', 'SALES', 'RETURN', 'Create sales return from invoice'),
                ('SALES.CANCEL', 'SALES', 'CANCEL', 'Cancel sales invoice'),
                ('PURCHASE.DUPLICATE', 'PURCHASE', 'DUPLICATE', 'Duplicate existing purchase bill'),
                ('PURCHASE.RETURN', 'PURCHASE', 'RETURN', 'Create purchase return or debit note'),
                ('PURCHASE.CANCEL', 'PURCHASE', 'CANCEL', 'Cancel purchase bill'),
                ('SETTINGS.TAB_COMPANY', 'SETTINGS', 'TAB_COMPANY', 'View and manage Company Profile'),
                ('SETTINGS.TAB_EMAIL', 'SETTINGS', 'TAB_EMAIL', 'View and manage Email & SMTP setup'),
                ('SETTINGS.TAB_WHATSAPP', 'SETTINGS', 'TAB_WHATSAPP', 'View and manage WhatsApp channels'),
                ('SETTINGS.TAB_PAYMENT', 'SETTINGS', 'TAB_PAYMENT', 'View and manage Payment & Bank accounts'),
                ('SETTINGS.TAB_INVOICE', 'SETTINGS', 'TAB_INVOICE', 'View and manage Invoice layouts & numbering'),
                ('SETTINGS.TAB_SECURITY', 'SETTINGS', 'TAB_SECURITY', 'Manage user security, PIN & passwords'),
                ('SETTINGS.TAB_NOTIFICATIONS', 'SETTINGS', 'TAB_NOTIFICATIONS', 'Manage system notification preferences'),
                ('SETTINGS.TAB_WORKSPACE', 'SETTINGS', 'TAB_WORKSPACE', 'Configure workstation theme and UI layout'),
                ('SETTINGS.TAB_UPDATES', 'SETTINGS', 'TAB_UPDATES', 'Check and install application updates'),
                ('SETTINGS.TAB_SHORTCUTS', 'SETTINGS', 'TAB_SHORTCUTS', 'Configure keyboard shortcut hotkeys'),
                ('SETTINGS.TAB_MODULES', 'SETTINGS', 'TAB_MODULES', 'Manage ERP module feature entitlements'),
                ('QUOTATION.DUPLICATE', 'QUOTATION', 'DUPLICATE', 'Duplicate existing quotation'),
                ('QUOTATION.EMAIL', 'QUOTATION', 'EMAIL', 'Send quotation via email'),
                ('QUOTATION.WHATSAPP', 'QUOTATION', 'WHATSAPP', 'Send quotation via WhatsApp'),
                ('QUOTATION.CONVERT_SALE', 'QUOTATION', 'CONVERT_SALE', 'Convert approved quotation to sales invoice'),
                ('CUSTOMERS.360', 'CUSTOMERS', '360', 'View Customer 360 degree profile insights'),
                ('SUPPLIERS.360', 'SUPPLIERS', '360', 'View Supplier 360 degree profile insights'),
                ('CREDIT_NOTE.CANCEL', 'CREDIT_NOTE', 'CANCEL', 'Cancel credit note'),
                ('DEBIT_NOTE.CANCEL', 'DEBIT_NOTE', 'CANCEL', 'Cancel debit note'),
                ('INVENTORY.ADJUST', 'INVENTORY', 'ADJUST', 'Perform stock quantity and cost adjustments'),
                ('MASTERS.TAB_CATEGORIES', 'MASTERS', 'TAB_CATEGORIES', 'Manage Item Categories in Masters'),
                ('MASTERS.TAB_UNITS', 'MASTERS', 'TAB_UNITS', 'Manage Units of Measure in Masters'),
                ('MASTERS.TAB_MATERIALS', 'MASTERS', 'TAB_MATERIALS', 'Manage Materials in Masters'),
                ('MASTERS.TAB_BRANDS', 'MASTERS', 'TAB_BRANDS', 'Manage Brands in Masters'),
                ('MASTERS.TAB_GST', 'MASTERS', 'TAB_GST', 'Manage GST Tax Rates in Masters'),
                ('MASTERS.TAB_DISCOUNT', 'MASTERS', 'TAB_DISCOUNT', 'Manage Discount Policies in Masters'),
                ('MASTERS.TAB_PAYMENT_MODES', 'MASTERS', 'TAB_PAYMENT_MODES', 'Manage Payment Modes in Masters'),
                ('MASTERS.TAB_ROLES', 'MASTERS', 'TAB_ROLES', 'Manage Role Descriptions in Masters'),
                ('IMPORT.EXECUTE', 'IMPORT', 'EXECUTE', 'Execute and apply data import batch to database'),
                ('IMPORT.ITEMS', 'IMPORT', 'ITEMS', 'Import item master and inventory products'),
                ('IMPORT.CUSTOMERS', 'IMPORT', 'CUSTOMERS', 'Import customer directory and accounts'),
                ('IMPORT.SUPPLIERS', 'IMPORT', 'SUPPLIERS', 'Import supplier directory and accounts'),
                ('IMPORT.SALES', 'IMPORT', 'SALES', 'Import historical sales invoices'),
                ('IMPORT.PURCHASES', 'IMPORT', 'PURCHASES', 'Import historical purchase bills'),
                ('IMPORT.BANK', 'IMPORT', 'BANK', 'Import bank statement transactions'),
                ('IMPORT.MASTERS', 'IMPORT', 'MASTERS', 'Import master categories and lookup values'),
                ('MASTERS.CREATE_CATEGORY', 'MASTERS', 'CREATE_CATEGORY', 'Add new lookup category'),
                ('MASTERS.EDIT_CATEGORY', 'MASTERS', 'EDIT_CATEGORY', 'Rename existing lookup category'),
                ('MASTERS.DELETE_CATEGORY', 'MASTERS', 'DELETE_CATEGORY', 'Delete lookup category'),
                ('MASTERS.CREATE_LOOKUP', 'MASTERS', 'CREATE_LOOKUP', 'Add new lookup value entry'),
                ('MASTERS.EDIT_LOOKUP', 'MASTERS', 'EDIT_LOOKUP', 'Edit lookup value entry'),
                ('MASTERS.DELETE_LOOKUP', 'MASTERS', 'DELETE_LOOKUP', 'Delete or deactivate lookup value entry'),
                ('MASTERS.EXPORT', 'MASTERS', 'EXPORT', 'Export master lookup values to file'),
                ('AUDIT.EXPORT', 'AUDIT', 'EXPORT', 'Export audit logs to Excel or CSV'),
                ('AUDIT.SECURITY', 'AUDIT', 'SECURITY', 'View authentication, password, and security audit logs'),
                ('SAFE_ROLLBACK.EXECUTE', 'SAFE_ROLLBACK', 'EXECUTE', 'Execute live application and database rollback'),
                ('BACKUP.CREATE', 'BACKUP', 'CREATE', 'Create on-demand database backup snapshot'),
                ('BACKUP.RESTORE', 'BACKUP', 'RESTORE', 'Restore database from backup snapshot'),
                ('BACKUP.EXPORT', 'BACKUP', 'EXPORT', 'Export recovery archive to external disk'),
                ('SYSTEM.VIEW', 'SYSTEM', 'VIEW', 'View system specifications and JVM metrics'),
                ('SYSTEM.LOGS', 'SYSTEM', 'LOGS', 'View live server logs and error traces'),
                ('SYSTEM.CACHE_CLEAR', 'SYSTEM', 'CACHE_CLEAR', 'Evict and reload server and desktop cache'),
                ('BARCODE.PRINT', 'BARCODE', 'PRINT', 'Print barcode and QR labels'),
                ('BARCODE.DESIGN', 'BARCODE', 'DESIGN', 'Customize label dimensions and template design'),
                ('DOCUMENT_STUDIO.EDIT', 'DOCUMENT_STUDIO', 'EDIT', 'Edit invoice and document layout templates'),
                ('DOCUMENT_STUDIO.RESET', 'DOCUMENT_STUDIO', 'RESET', 'Restore default factory document templates'),
                ('REPORTS.EXPORT', 'REPORTS', 'EXPORT', 'Export reports to Excel or PDF'),
                ('GENERAL_LEDGER.LOCK_PERIOD', 'GENERAL_LEDGER', 'LOCK_PERIOD', 'Lock or unlock fiscal accounting periods'),
                ('GENERAL_LEDGER.CREATE_JOURNAL', 'GENERAL_LEDGER', 'CREATE_JOURNAL', 'Create manual journal vouchers'),
                ('GENERAL_LEDGER.POST_JOURNAL', 'GENERAL_LEDGER', 'POST_JOURNAL', 'Post draft journal entries to live ledger'),
                ('GENERAL_LEDGER.REVERSE_JOURNAL', 'GENERAL_LEDGER', 'REVERSE_JOURNAL', 'Create accounting reversal vouchers'),
                ('GENERAL_LEDGER.CREATE_ACCOUNT', 'GENERAL_LEDGER', 'CREATE_ACCOUNT', 'Create new Chart of Accounts ledger'),
                ('GENERAL_LEDGER.EDIT_ACCOUNT', 'GENERAL_LEDGER', 'EDIT_ACCOUNT', 'Edit Chart of Accounts ledger properties'),
                ('GST_COMPLIANCE.IMPORT', 'GST_COMPLIANCE', 'IMPORT', 'Import GSTR-2B JSON reconciliation statements'),
                ('GST_COMPLIANCE.NOTIFY', 'GST_COMPLIANCE', 'NOTIFY', 'Send ITC discrepancy notices to suppliers'),
                ('TALLY.UPLOAD', 'TALLY', 'UPLOAD', 'Upload invoices for OCR Bill to Tally conversion'),
                ('TALLY.DELETE', 'TALLY', 'DELETE', 'Delete uploaded Bill to Tally records'),
                ('AUTOMATION.SCAN', 'AUTOMATION', 'SCAN', 'Trigger 3-way matching automated scans'),
                ('AUTOMATION.REORDER_SCAN', 'AUTOMATION', 'REORDER_SCAN', 'Trigger replenishment reorder scans'),
                ('AUTOMATION.CREATE_PO', 'AUTOMATION', 'CREATE_PO', 'Auto-generate Purchase Order from replenishment suggestion'),
                ('REMINDERS.CREATE', 'REMINDERS', 'CREATE', 'Create manual business follow-up reminders'),
                ('REMINDERS.EDIT', 'REMINDERS', 'EDIT', 'Edit, complete, or snooze reminders'),
                ('REMINDERS.DELETE', 'REMINDERS', 'DELETE', 'Delete business reminders'),
                ('COMMUNICATION.EXPORT', 'COMMUNICATION', 'EXPORT', 'Export email and WhatsApp communication logs'),
                ('PURCHASE_RECON.EXPORT', 'PURCHASE_RECON', 'EXPORT', 'Export purchase reconciliation sheets to Excel'),
                ('USERS.CREATE', 'USERS', 'CREATE', 'Create new ERP user accounts'),
                ('USERS.EDIT', 'USERS', 'EDIT', 'Modify user details, department, and branch assignments'),
                ('USERS.RESET_PASSWORD', 'USERS', 'RESET_PASSWORD', 'Reset user passwords'),
                ('USERS.MANAGE_PERMISSIONS', 'USERS', 'MANAGE_PERMISSIONS', 'Configure granular role permission matrices'),
                ('USERS.MANAGE_ROLES', 'USERS', 'MANAGE_ROLES', 'Create, edit, or delete custom ERP roles'),
                ('USERS.APPROVE_REGISTRATION', 'USERS', 'APPROVE_REGISTRATION', 'Approve or reject self-registered user accounts')
                ON CONFLICT (permission_key) DO UPDATE SET description = EXCLUDED.description, active = 1
            """);

            for (var mod : org.example.shared.PermissionCatalog.subscriptionModules()) {
                jdbc.update("""
                    INSERT INTO permissions(permission_key, module_name, action_name, description, active)
                    VALUES (?, ?, 'VIEW', ?, 1)
                    ON CONFLICT (permission_key) DO NOTHING
                """, mod.key() + ".VIEW", mod.key(), "Open and view " + mod.label());
            }

            jdbc.update("""
                INSERT INTO role_permission(role_code, permission_id, allowed)
                SELECT 'ADMIN', p.id, 1
                FROM permissions p
                WHERE p.active = 1
                ON CONFLICT (UPPER(TRIM(role_code)), permission_id) WHERE TRIM(COALESCE(role_code, '')) <> ''
                DO NOTHING
            """);
            jdbc.update("""
                INSERT INTO role_permission(role_code, permission_id, allowed)
                SELECT 'SUPER_ADMIN', p.id, 1
                FROM permissions p
                WHERE p.active = 1
                ON CONFLICT (UPPER(TRIM(role_code)), permission_id) WHERE TRIM(COALESCE(role_code, '')) <> ''
                DO UPDATE SET allowed = 1
            """);
        } catch (Exception ignored) { java.lang.System.getLogger("org.example").log(java.lang.System.Logger.Level.DEBUG, "Suppressed exception: " + ignored.getMessage(), ignored); }
    }

    private boolean isSuperAdminCaller() {
        try {
            var user = org.example.server.security.CurrentUser.require();
            return "SUPER_ADMIN".equalsIgnoreCase(user.role()) || "superadmin".equalsIgnoreCase(user.username());
        } catch (Exception e) {
            return false;
        }
    }

    @Transactional(readOnly = true)
    public List<AdminDtos.UserDto> users() {
        boolean superAdmin = isSuperAdminCaller();
        return jdbc.query("SELECT u.id,u.username,u.full_name,u.email,u.phone,COALESCE(NULLIF(TRIM(u.role),''),'SALES')," +
                        "u.department,u.access_level,u.branch,u.active,u.locked,u.mfa_enabled,COALESCE(NULLIF(u.last_login_utc,''),CAST(u.last_login AS text)),COALESCE(u.row_version,0)," +
                        "CASE WHEN COALESCE(u.mfa_enabled,0) = 0 THEN 'DISABLED' " +
                        "WHEN u.totp_secret_enc IS NULL OR BTRIM(u.totp_secret_enc) = '' " +
                        "OR EXISTS(SELECT 1 FROM auth_totp_enrollment_pending p WHERE p.user_id = u.id) THEN 'PENDING_SETUP' " +
                        "ELSE 'ACTIVE' END AS mfa_status " +
                        "FROM users u ORDER BY u.full_name,u.username",
                (row, index) -> new AdminDtos.UserDto(row.getInt(1), row.getString(2), row.getString(3), row.getString(4),
                        row.getString(5), row.getString(6), row.getString(7), row.getString(8), row.getString(9), flag(row.getObject(10)),
                        flag(row.getObject(11)), flag(row.getObject(12)), BusinessClock.toUtcText(row.getObject(13)), row.getLong(14),
                        row.getString(15)))
                .stream()
                .filter(u -> superAdmin || (!"SUPER_ADMIN".equalsIgnoreCase(u.role()) && !"superadmin".equalsIgnoreCase(u.username())))
                .toList();
    }

    @Transactional(readOnly = true)
    public AdminDtos.UserDto user(int id) {
        AdminDtos.UserDto u = users().stream().filter(value -> value.id() == id).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + id));
        if (!isSuperAdminCaller() && ("SUPER_ADMIN".equalsIgnoreCase(u.role()) || "superadmin".equalsIgnoreCase(u.username()))) {
            throw new SecurityException("Access denied to super admin account");
        }
        return u;
    }

    /** Role options are projected directly from Master Data ROLE lookups. */
    @Transactional(readOnly = true)
    public List<AdminDtos.RoleDto> roles() {
        return roleMaster.activeRoles().stream()
                .filter(role -> !"SUPER_ADMIN".equalsIgnoreCase(role.code()))
                .map(role -> new AdminDtos.RoleDto(role.id(), role.code(), role.displayName(), role.description(),
                        role.active(), role.userCount()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AdminDtos.PermissionDto> permissions(String role) {
        String code = role(role);
        boolean superAdmin = "SUPER_ADMIN".equals(code);
        boolean adminTarget = "ADMIN".equals(code);
        if (superAdmin) {
            return jdbc.query("SELECT p.id,p.module_name,p.action_name,p.description,1 " +
                            "FROM permissions p WHERE p.active=1 ORDER BY p.module_name,p.action_name",
                    (row, index) -> new AdminDtos.PermissionDto(row.getLong(1), row.getString(2), row.getString(3),
                            row.getString(4), true));
        }
        if (adminTarget) {
            return jdbc.query("SELECT p.id,p.module_name,p.action_name,p.description,COALESCE(rp.allowed,1) " +
                            "FROM permissions p LEFT JOIN role_permission rp ON rp.permission_id=p.id " +
                            "AND UPPER(TRIM(COALESCE(rp.role_code,'')))='ADMIN' WHERE p.active=1 ORDER BY p.module_name,p.action_name",
                    (row, index) -> new AdminDtos.PermissionDto(row.getLong(1), row.getString(2), row.getString(3),
                            row.getString(4), flag(row.getObject(5))));
        }
        return jdbc.query("SELECT p.id,p.module_name,p.action_name,p.description,COALESCE(rp.allowed,0) " +
                        "FROM permissions p LEFT JOIN role_permission rp ON rp.permission_id=p.id " +
                        "AND UPPER(TRIM(COALESCE(rp.role_code,'')))=? " +
                        "LEFT JOIN role_permission arp ON arp.permission_id=p.id AND UPPER(TRIM(COALESCE(arp.role_code,'')))='ADMIN' " +
                        "WHERE p.active=1 AND COALESCE(arp.allowed,1)=1 ORDER BY p.module_name,p.action_name",
                (row, index) -> new AdminDtos.PermissionDto(row.getLong(1), row.getString(2), row.getString(3),
                        row.getString(4), flag(row.getObject(5))), code);
    }

    @Transactional
    public AdminDtos.PermissionSetDto permissionSet(String role) {
        String code = role(role);
        jdbc.update("INSERT INTO role_permission_revision(role_code,row_version) VALUES(?,0) ON CONFLICT(role_code) DO NOTHING", code);
        Long revision = jdbc.queryForObject("SELECT row_version FROM role_permission_revision WHERE role_code=?", Long.class, code);
        return new AdminDtos.PermissionSetDto(revision == null ? 0L : revision, permissions(code));
    }

    @Transactional
    public void savePermissions(AdminDtos.PermissionSaveRequest request) {
        String code = role(request == null ? null : request.role());
        if ("SUPER_ADMIN".equals(code)) return;
        if ("ADMIN".equals(code) && !isSuperAdminCaller()) return;
        jdbc.update("INSERT INTO role_permission_revision(role_code,row_version) VALUES(?,0) ON CONFLICT(role_code) DO NOTHING", code);
        Long currentRevision = jdbc.queryForObject("SELECT row_version FROM role_permission_revision WHERE role_code=? FOR UPDATE", Long.class, code);
        long actualRevision = currentRevision == null ? 0L : currentRevision;
        if (request.rowVersion() != actualRevision)
            throw new ConcurrentEditException("Permission Matrix for " + code);
        Map<Long, Boolean> requested = new LinkedHashMap<>();
        if (request.permissions() != null) {
            for (var permission : request.permissions()) {
                if (permission == null || permission.id() <= 0) throw new IllegalArgumentException("A valid permission id is required");
                requested.put(permission.id(), permission.allowed());
            }
        }
        if (!requested.isEmpty()) {
            Long valid = jdbc.queryForObject("SELECT COUNT(*) FROM permissions WHERE active=1 AND id IN (" +
                    requested.keySet().stream().map(x -> "?").collect(java.util.stream.Collectors.joining(",")) + ")",
                    Long.class, requested.keySet().toArray());
            if (valid == null || valid != requested.size()) throw new IllegalArgumentException("One or more permissions are invalid or inactive. Refresh the Permission Matrix and try again.");
        }
        jdbc.update("DELETE FROM role_permission WHERE UPPER(TRIM(COALESCE(role_code,'')))=?", code);
        for (var permission : requested.entrySet()) {
            jdbc.update("INSERT INTO role_permission(role_code,permission_id,allowed) VALUES(?,?,?)",
                    code, permission.getKey(), permission.getValue() ? 1 : 0);
            if ("ADMIN".equals(code) && !Boolean.TRUE.equals(permission.getValue())) {
                jdbc.update("UPDATE role_permission SET allowed=0 WHERE permission_id=? AND UPPER(TRIM(COALESCE(role_code,'')))<>'ADMIN'",
                        permission.getKey());
            }
        }
        jdbc.update("UPDATE role_permission_revision SET row_version=row_version+1,updated_at=CURRENT_TIMESTAMP::text WHERE role_code=?", code);
    }

    @Transactional
    public AdminDtos.UserDto saveUser(AdminDtos.UserSaveRequest request) {
        if (request == null || request.username() == null || request.username().isBlank())
            throw new IllegalArgumentException("Username is required");
        String assignedRole = role(request.role());
        String phone = clean(request.phone());
        if (request.id() == null && (phone == null || phone.isBlank()))
            throw new IllegalArgumentException("Phone number is required");
        validateUniqueIdentity(request);
        boolean enforcedMfa = effectiveMfa(assignedRole, request.mfaEnabled());
        String encoded = null;
        if (request.id() == null || (request.password() != null && !request.password().isBlank())) {
            validatePassword(request.password());
            encoded = passwords.encode(request.password());
        }
        if (request.id() == null) {
            if (!isSuperAdminCaller() && ("SUPER_ADMIN".equalsIgnoreCase(assignedRole) || "superadmin".equalsIgnoreCase(request.username()))) {
                throw new SecurityException("Access denied: Only Super Admin can create superadmin accounts");
            }
            jdbc.update("INSERT INTO users(username,password,full_name,role,role_id,email,phone,active,locked,lock_reason,mfa_enabled,department,branch,access_level) " +
                            "VALUES(?,?,?,?,NULL,?,?,?,?,?,?,?,?,?)",
                    request.username().trim(), encoded, clean(request.fullName()), assignedRole,
                    clean(request.email()), phone, request.active() ? 1 : 0, request.locked() ? 1 : 0,
                    request.locked() ? "ADMIN" : "NONE", enforcedMfa ? 1 : 0,
                    clean(request.department()), clean(request.branch()), clean(request.accessLevel()));
        } else {
            jdbc.query("SELECT pg_advisory_xact_lock(?)", (row,index) -> row.getObject(1), USER_AUTHORITY_LOCK);
            Map<String,Object> lockedUser = jdbc.queryForMap("SELECT COALESCE(row_version,0) row_version FROM users WHERE id=? FOR UPDATE", request.id());
            long actualVersion = ((Number)lockedUser.get("row_version")).longValue();
            if (request.rowVersion() != actualVersion) throw new ConcurrentEditException("User account");
            boolean previousMfa = Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT mfa_enabled FROM users WHERE id=?", Boolean.class, request.id()));
            boolean previousLocked = Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT locked FROM users WHERE id=?", Boolean.class, request.id()));
            String previousRole = jdbc.queryForObject("SELECT COALESCE(role,'') FROM users WHERE id=?", String.class, request.id());
            if (!isSuperAdminCaller() && ("SUPER_ADMIN".equalsIgnoreCase(previousRole) || "superadmin".equalsIgnoreCase(request.username()) || "SUPER_ADMIN".equalsIgnoreCase(assignedRole))) {
                throw new SecurityException("Access denied: Only Super Admin can modify the superadmin account");
            }
            if (request.id() == CurrentUser.require().id() && previousRole != null && !assignedRole.equalsIgnoreCase(previousRole.trim()))
                throw new IllegalArgumentException("You cannot change your own role. Another administrator must perform that change.");
            ensureActiveAdministratorRemains(request.id(), assignedRole, request.active(), request.locked());
            if (request.id() == CurrentUser.require().id() && (request.locked() || !request.active()))
                throw new IllegalArgumentException("You cannot lock or deactivate your own account");
            jdbc.update("UPDATE users SET username=?,full_name=?,email=?,phone=?,role=?,role_id=NULL," +
                            "active=?,locked=?,mfa_enabled=?,department=?,branch=?,access_level=?,row_version=row_version+1 WHERE id=? AND row_version=?",
                    request.username().trim(), clean(request.fullName()), clean(request.email()), phone, assignedRole,
                    request.active() ? 1 : 0, request.locked() ? 1 : 0, enforcedMfa ? 1 : 0,
                    clean(request.department()), clean(request.branch()), clean(request.accessLevel()), request.id(), request.rowVersion());
            if (previousLocked && !request.locked()) {
                jdbc.update("UPDATE users SET failed_attempts=0,mfa_failed_attempts=0,lock_reason='NONE' WHERE id=?", request.id());
                jdbc.update("INSERT INTO activity_log(entity_type,entity_id,action,detail,created_by,created_at) VALUES('USER',?,?,?,?,?)",
                        request.id(), "ACCOUNT_UNLOCKED", "Account unlocked and failed-attempt counters reset",
                        CurrentUser.require().username(), BusinessClock.nowUtcText());
            } else if (!previousLocked && request.locked()) {
                jdbc.update("UPDATE users SET lock_reason='ADMIN' WHERE id=?", request.id());
                tokens.revokeUser(request.id());
                jdbc.update("INSERT INTO activity_log(entity_type,entity_id,action,detail,created_by,created_at) VALUES('USER',?,?,?,?,?)",
                        request.id(), "ACCOUNT_LOCKED_BY_ADMIN", "Account locked by administrator",
                        CurrentUser.require().username(), BusinessClock.nowUtcText());
            }
            if (encoded != null) {
                jdbc.update("UPDATE users SET password=? WHERE id=?", encoded, request.id());
                tokens.revokeUser(request.id());
            }
            if (previousRole == null || !assignedRole.equalsIgnoreCase(previousRole.trim())) {
                tokens.revokeUser(request.id());
                jdbc.update("INSERT INTO activity_log(entity_type,entity_id,action,detail,created_by,created_at) VALUES('USER',?,?,?,?,?)",
                        request.id(), "ROLE_CHANGED", "Role changed from " + (previousRole == null ? "" : previousRole.trim()) + " to " + assignedRole,
                        CurrentUser.require().username(), BusinessClock.nowUtcText());
            }
            if (previousMfa != enforcedMfa) {
                tokens.revokeUser(request.id());
                jdbc.update("UPDATE users SET mfa_failed_attempts=0,totp_secret_enc=NULL WHERE id=?", request.id());
                jdbc.update("DELETE FROM auth_totp_enrollment_pending WHERE user_id=?", request.id());
                jdbc.update("DELETE FROM auth_totp_login_challenge WHERE user_id=?", request.id());
                jdbc.update("INSERT INTO activity_log(entity_type,entity_id,action,detail,created_by,created_at) " +
                                "VALUES('USER',?,?,?,?,?)", request.id(),
                        enforcedMfa ? "MFA_ENROLLMENT_REQUIRED" : "MFA_DISABLED",
                        enforcedMfa ? "Authenticator enrollment required under server policy " + mfaPolicy()
                                : "MFA disabled and authenticator secret invalidated under server policy " + mfaPolicy(),
                        CurrentUser.require().username(), BusinessClock.nowUtcText());
            }
        }
        return request.id() == null
                ? users().stream().filter(value -> value.username().equalsIgnoreCase(request.username().trim())).findFirst().orElseThrow()
                : user(request.id());
    }

    @Transactional(readOnly = true)
    public AdminDtos.MfaState mfaState(int id) {
        Map<String,Object> row=jdbc.queryForMap("SELECT COALESCE(role,'') role,COALESCE(mfa_enabled,0) mfa_enabled,COALESCE(totp_secret_enc,'') totp_secret_enc FROM users WHERE id=?",id);
        String role=String.valueOf(row.get("role"));
        boolean required=effectiveMfa(role,flag(row.get("mfa_enabled")));
        if(!required)return new AdminDtos.MfaState(false,"DISABLED","Authenticator is not required under the current policy.");
        Long pending=jdbc.queryForObject("SELECT COUNT(*) FROM auth_totp_enrollment_pending WHERE user_id=?",Long.class,id);
        String secret=String.valueOf(row.get("totp_secret_enc"));
        boolean enrollmentRequired=secret.isBlank()||(pending!=null&&pending>0);
        return new AdminDtos.MfaState(true,enrollmentRequired?"ENROLLMENT_REQUIRED":"ACTIVE",
                enrollmentRequired?"Authenticator enrollment is required at the next sign-in.":"Authenticator is active.");
    }

    @Transactional
    public AdminDtos.MfaState resetAuthenticator(int id) {
        Map<String,Object> row=jdbc.queryForMap("SELECT COALESCE(role,'') role,COALESCE(mfa_enabled,0) mfa_enabled FROM users WHERE id=? FOR UPDATE",id);
        String role=String.valueOf(row.get("role"));
        if (!isSuperAdminCaller() && "SUPER_ADMIN".equalsIgnoreCase(role)) {
            throw new SecurityException("Access denied: Only Super Admin can modify the superadmin account");
        }
        boolean required=effectiveMfa(role,flag(row.get("mfa_enabled")));
        if(!required)throw new IllegalStateException("Authenticator is not required for this user under the current MFA policy.");
        jdbc.update("UPDATE users SET totp_secret_enc=NULL,mfa_failed_attempts=0,row_version=row_version+1 WHERE id=?",id);
        jdbc.update("DELETE FROM auth_totp_enrollment_pending WHERE user_id=?",id);
        jdbc.update("INSERT INTO auth_totp_enrollment_pending(user_id,created_at) VALUES(?,CURRENT_TIMESTAMP) ON CONFLICT(user_id) DO UPDATE SET created_at=CURRENT_TIMESTAMP",id);
        jdbc.update("DELETE FROM auth_totp_login_challenge WHERE user_id=?",id);
        tokens.revokeUser(id);
        jdbc.update("INSERT INTO activity_log(entity_type,entity_id,action,detail,created_by,created_at) VALUES('USER',?,?,?,?,?)",
                id,"MFA_AUTHENTICATOR_RESET","Existing authenticator invalidated; enrollment required at next sign-in",CurrentUser.require().username(),BusinessClock.nowUtcText());
        return new AdminDtos.MfaState(true,"ENROLLMENT_REQUIRED","Authenticator reset. The user must scan a new QR code at the next sign-in.");
    }

    private void requireSuperAdminIfTargetIsAdmin(String targetRole, String targetUsername, String action) {
        if (!isSuperAdminCaller() && ("ADMIN".equalsIgnoreCase(targetRole) || "SUPER_ADMIN".equalsIgnoreCase(targetRole)
                || "admin".equalsIgnoreCase(targetUsername) || "superadmin".equalsIgnoreCase(targetUsername))) {
            throw new SecurityException("Access denied: Only Super Admin can " + action + " an Administrator account.");
        }
    }

    private void recordPasswordHistory(int userId, String passwordHash) {
        try {
            jdbc.update("INSERT INTO user_password_history(user_id, password_hash, created_at) VALUES(?, ?, CURRENT_TIMESTAMP)", userId, passwordHash);
            jdbc.update("DELETE FROM user_password_history WHERE user_id=? AND id NOT IN (SELECT id FROM user_password_history WHERE user_id=? ORDER BY id DESC LIMIT 5)", userId, userId);
        } catch (Exception ignored) {}
    }

    @Transactional
    public void deleteUser(int id) {
        if (id == CurrentUser.require().id()) throw new IllegalArgumentException("You cannot delete your own account");
        String targetRole = jdbc.queryForObject("SELECT COALESCE(role,'') FROM users WHERE id=?", String.class, id);
        String targetUsername = jdbc.queryForObject("SELECT COALESCE(username,'') FROM users WHERE id=?", String.class, id);
        requireSuperAdminIfTargetIsAdmin(targetRole, targetUsername, "delete");
        if ("SUPER_ADMIN".equalsIgnoreCase(targetRole) || "superadmin".equalsIgnoreCase(targetUsername)) {
            throw new SecurityException("Super Admin account cannot be deleted");
        }
        jdbc.query("SELECT pg_advisory_xact_lock(?)", (row,index) -> row.getObject(1), USER_AUTHORITY_LOCK);
        jdbc.queryForMap("SELECT id FROM users WHERE id=? FOR UPDATE", id);
        ensureActiveAdministratorRemains(id, null, false, true);
        tokens.revokeUser(id);
        if (jdbc.update("DELETE FROM users WHERE id=?", id) != 1) throw new IllegalArgumentException("User not found");
    }

    @Transactional
    public void resetPassword(int id, String password) {
        validatePassword(password);
        String targetRole = jdbc.queryForObject("SELECT COALESCE(role,'') FROM users WHERE id=?", String.class, id);
        String targetUsername = jdbc.queryForObject("SELECT COALESCE(username,'') FROM users WHERE id=?", String.class, id);
        requireSuperAdminIfTargetIsAdmin(targetRole, targetUsername, "reset password for");
        if (!isSuperAdminCaller()) {
            String currentHash = jdbc.queryForObject("SELECT password FROM users WHERE id=?", String.class, id);
            if (currentHash != null && passwords.matches(password, currentHash)) {
                throw new IllegalArgumentException("New password cannot be identical to user's current password.");
            }
            List<String> hist = jdbc.query("SELECT password_hash FROM user_password_history WHERE user_id=? ORDER BY id DESC LIMIT 3", (rs, i) -> rs.getString(1), id);
            for (String h : hist) {
                if (h != null && passwords.matches(password, h)) {
                    throw new IllegalArgumentException("Password security policy: Password cannot be one of the user's last 3 previous passwords.");
                }
            }
        }
        String encoded = passwords.encode(password);
        if (jdbc.update("UPDATE users SET password=?,failed_attempts=0,mfa_failed_attempts=0,locked=0,lock_reason='NONE',last_password_reset_utc=?,row_version=row_version+1 WHERE id=?", encoded, BusinessClock.nowUtcText(), id) != 1)
            throw new IllegalArgumentException("User not found");
        recordPasswordHistory(id, encoded);
        tokens.revokeUser(id);
        jdbc.update("DELETE FROM login_throttle WHERE throttle_key LIKE ?", "%|" + targetUsername.toLowerCase(java.util.Locale.ROOT));
        jdbc.update("INSERT INTO activity_log(entity_type,entity_id,action,detail,created_by,created_at) VALUES('USER',?,?,?,?,?)",
                id, "ACCOUNT_UNLOCKED", "Password reset by administrator and sign-in lock cleared", CurrentUser.require().username(), BusinessClock.nowUtcText());
    }

    @Transactional
    public void setLocked(int id, boolean locked) {
        if (id == CurrentUser.require().id() && locked) throw new IllegalArgumentException("You cannot lock your own account");
        String targetRole = jdbc.queryForObject("SELECT COALESCE(role,'') FROM users WHERE id=?", String.class, id);
        String targetUsername = jdbc.queryForObject("SELECT COALESCE(username,'') FROM users WHERE id=?", String.class, id);
        requireSuperAdminIfTargetIsAdmin(targetRole, targetUsername, locked ? "lock" : "unlock");
        jdbc.query("SELECT pg_advisory_xact_lock(?)", (row,index) -> row.getObject(1), USER_AUTHORITY_LOCK);
        jdbc.queryForMap("SELECT id FROM users WHERE id=? FOR UPDATE", id);
        if (locked) ensureActiveAdministratorRemains(id, null, true, true);
        int updated = locked
                ? jdbc.update("UPDATE users SET locked=1,lock_reason='ADMIN',row_version=row_version+1 WHERE id=?", id)
                : jdbc.update("UPDATE users SET locked=0,lock_reason='NONE',failed_attempts=0,mfa_failed_attempts=0,row_version=row_version+1 WHERE id=?", id);
        if (updated != 1) throw new IllegalArgumentException("User not found");
        if (locked) {
            tokens.revokeUser(id);
        } else {
            jdbc.update("DELETE FROM login_throttle WHERE throttle_key LIKE ?", "%|" + targetUsername.toLowerCase(java.util.Locale.ROOT));
        }
        jdbc.update("INSERT INTO activity_log(entity_type,entity_id,action,detail,created_by,created_at) VALUES('USER',?,?,?,?,?)",
                id, locked ? "ACCOUNT_LOCKED_BY_ADMIN" : "ACCOUNT_UNLOCKED",
                locked ? "Account locked by administrator" : "Account unlocked and failed-attempt counters reset",
                CurrentUser.require().username(), BusinessClock.nowUtcText());
    }

    @Transactional(readOnly = true)
    public AdminDtos.RegistrationRoleDto registrationRole() {
        RoleMasterService.RoleDefinition role = roleMaster.selfRegistrationRole();
        return new AdminDtos.RegistrationRoleDto(role.code(), role.displayName());
    }

    @Transactional
    public AdminDtos.RegistrationRoleDto setRegistrationRole(AdminDtos.RegistrationRoleSaveRequest request) {
        if (request == null || request.role() == null || request.role().isBlank())
            throw new IllegalArgumentException("Select an active non-Admin Role Master entry");
        RoleMasterService.RoleDefinition role = roleMaster.setSelfRegistrationRole(request.role());
        jdbc.update("INSERT INTO activity_log(entity_type,entity_id,action,detail,created_by,created_at) VALUES('SECURITY',NULL,?,?,?,?)",
                "REGISTRATION_ROLE_CHANGED", "Public registration role set to " + role.code(), CurrentUser.require().username(), BusinessClock.nowUtcText());
        return new AdminDtos.RegistrationRoleDto(role.code(), role.displayName());
    }

    /** Role definitions are edited only through Master Data ROLE lookups. */
    public AdminDtos.RoleDto saveRole(AdminDtos.RoleSaveRequest request) {
        throw new IllegalArgumentException("Manage role definitions in Master Data > Role");
    }

    public void deleteRole(int id) {
        throw new IllegalArgumentException("Manage role definitions in Master Data > Role");
    }

    @Transactional
    public void audit(AdminDtos.AuditRequest request) {
        jdbc.update("INSERT INTO activity_log(entity_type,entity_id,action,detail,created_by,created_at) VALUES('USER',?,?,?,?,?)",
                request.userId(), request.action(), request.detail(), CurrentUser.require().username(), BusinessClock.nowUtcText());
    }

    private void ensureActiveAdministratorRemains(int targetId, String nextRole, boolean nextActive, boolean nextLocked) {
        Map<String,Object> current = jdbc.queryForMap("SELECT COALESCE(role,'') role,COALESCE(active,1) active,COALESCE(locked,0) locked FROM users WHERE id=?", targetId);
        String currentRole = String.valueOf(current.get("role")).trim();
        boolean currentActive = flag(current.get("active"));
        boolean currentLocked = flag(current.get("locked"));
        if (!"ADMIN".equalsIgnoreCase(currentRole) || !currentActive || currentLocked) return;
        String effectiveRole = nextRole == null ? currentRole : nextRole;
        if ("ADMIN".equalsIgnoreCase(effectiveRole) && nextActive && !nextLocked) return;
        Long others = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id<>? AND UPPER(TRIM(COALESCE(role,'')))='ADMIN' AND COALESCE(active,1)<>0 AND COALESCE(locked,0)=0", Long.class, targetId);
        if (others == null || others == 0) throw new IllegalStateException("At least one other active, unlocked administrator is required before removing this administrator's authority.");
    }

    private boolean effectiveMfa(String role, boolean requested) {
        String policy=mfaPolicy();
        if ("DISABLED".equals(policy)) return false;
        if ("ADMIN_CONTROLLED".equals(policy)) return requested;
        return !"ADMIN".equalsIgnoreCase(role==null?"":role.trim());
    }

    private String mfaPolicy() {
        try {
            String value=jdbc.queryForObject("SELECT setting_value FROM application_setting WHERE setting_key='security.auth.mfa.policy'",String.class);
            String normalized=value==null?"REQUIRED":value.trim().toUpperCase(java.util.Locale.ROOT).replace(' ','_');
            return switch(normalized){case "ADMIN_CONTROLLED","DISABLED"->normalized;default->"REQUIRED";};
        } catch(RuntimeException ignored){return "REQUIRED";}
    }

    private void validateUniqueIdentity(AdminDtos.UserSaveRequest request) {
        Integer id = request.id();
        String username = request.username() == null ? "" : request.username().trim();
        String email = clean(request.email());
        Long usernameMatches = id == null
                ? jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE LOWER(TRIM(username))=LOWER(TRIM(?))", Long.class, username)
                : jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE LOWER(TRIM(username))=LOWER(TRIM(?)) AND id<>?", Long.class, username, id);
        if (usernameMatches != null && usernameMatches > 0) throw new IllegalArgumentException("This username is already assigned to another user");
        if (email != null && !email.isBlank()) {
            Long emailMatches = id == null
                    ? jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE LOWER(TRIM(COALESCE(email,'')))=LOWER(TRIM(?))", Long.class, email)
                    : jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE LOWER(TRIM(COALESCE(email,'')))=LOWER(TRIM(?)) AND id<>?", Long.class, email, id);
            if (emailMatches != null && emailMatches > 0) throw new IllegalArgumentException("This email address is already assigned to another user");
        }
        String phone = clean(request.phone());
        if (phone != null && !phone.isBlank()) {
            Long phoneMatches = id == null
                    ? jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE BTRIM(COALESCE(phone,''))=BTRIM(?) AND BTRIM(COALESCE(phone,''))<>''", Long.class, phone)
                    : jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE BTRIM(COALESCE(phone,''))=BTRIM(?) AND BTRIM(COALESCE(phone,''))<>'' AND id<>?", Long.class, phone, id);
            if (phoneMatches != null && phoneMatches > 0) throw new IllegalArgumentException("This phone number is already assigned to another user");
        }
    }

    private String role(String value) {
        return roleMaster.requireActive(value).code();
    }

    private static void validatePassword(String value) {
        if (value == null || value.length() < 8 || !value.matches(".*[A-Za-z].*") || !value.matches(".*[0-9].*"))
            throw new IllegalArgumentException("Password must contain at least 8 characters, a letter and a number");
    }

    private static String clean(String value) {
        return value == null ? null : value.trim();
    }

    private static boolean flag(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean bool) return bool;
        String text = String.valueOf(value);
        return "1".equals(text) || "t".equalsIgnoreCase(text) || "true".equalsIgnoreCase(text);
    }
    @Transactional(readOnly = true)
    public List<AdminDtos.RegistrationRequestDto> registrations(String status) {
        String wanted=status==null||status.isBlank()?"PENDING_ADMIN_APPROVAL":status.trim().toUpperCase();
        return jdbc.query("SELECT r.id,r.username,r.full_name,r.email,r.requested_role,r.email_verified,r.mfa_verified,r.status,CAST(r.requested_at AS text),COALESCE(u.username,''),CAST(r.reviewed_at AS text),COALESCE(r.rejection_reason,''),r.row_version FROM registration_request r LEFT JOIN users u ON u.id=r.reviewed_by WHERE r.status=? ORDER BY r.requested_at ASC",
                (row,i)->new AdminDtos.RegistrationRequestDto(row.getLong(1),row.getString(2),row.getString(3),row.getString(4),row.getString(5),flag(row.getObject(6)),flag(row.getObject(7)),row.getString(8),row.getString(9),row.getString(10),row.getString(11),row.getString(12),row.getLong(13)),wanted);
    }

    @Transactional
    public AdminDtos.Ok approveRegistration(long id, AdminDtos.RegistrationDecisionRequest request) {
        var row=jdbc.queryForMap("SELECT id,username,password_hash,full_name,email,COALESCE(phone,'') phone,requested_role,totp_secret_enc,email_verified,mfa_verified,status,row_version FROM registration_request WHERE id=? FOR UPDATE",id);
        long version=((Number)row.get("row_version")).longValue(); if(request==null||request.rowVersion()!=version)throw new ConcurrentEditException("Registration request");
        if(!"PENDING_ADMIN_APPROVAL".equals(String.valueOf(row.get("status"))))throw new IllegalArgumentException("Registration request has already been processed");
        if(!flag(row.get("email_verified"))||!flag(row.get("mfa_verified")))throw new IllegalArgumentException("Registration identity verification is incomplete");
        String assigned=request.role()==null||request.role().isBlank()?String.valueOf(row.get("requested_role")):request.role().trim().toUpperCase();
        if("ADMIN".equals(assigned))throw new SecurityException("Administrator accounts cannot be created from self-registration. Use User Management.");
        var role=roleMaster.requireActive(assigned);
        String username=String.valueOf(row.get("username")),email=String.valueOf(row.get("email")),phone=clean(String.valueOf(row.get("phone")));
        Long duplicates=jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE LOWER(username)=LOWER(?) OR LOWER(COALESCE(email,''))=LOWER(?)",Long.class,username,email);if(duplicates!=null&&duplicates>0)throw new IllegalArgumentException("Username or email is already registered");
        boolean approvedMfa=effectiveMfa(role.code(),true);Integer userId=jdbc.queryForObject("INSERT INTO users(username,password,full_name,role,email,phone,active,locked,mfa_enabled,access_level,totp_secret_enc,approval_status,row_version) VALUES(?,?,?,?,?,?,1,0,?,'STANDARD',?,'APPROVED',0) RETURNING id",Integer.class,username,String.valueOf(row.get("password_hash")),String.valueOf(row.get("full_name")),role.code(),email,phone,approvedMfa?1:0,String.valueOf(row.get("totp_secret_enc")));
        var actor=CurrentUser.require();jdbc.update("UPDATE registration_request SET status='APPROVED',reviewed_by=?,reviewed_at=CURRENT_TIMESTAMP,rejection_reason=NULL,row_version=row_version+1 WHERE id=?",actor.id(),id);
        jdbc.update("INSERT INTO activity_log(entity_type,entity_id,action,detail,created_by,created_at) VALUES('USER',?,?,?,?,?)",userId,"REGISTRATION_APPROVED","Self-registration approved with role "+role.code(),actor.username(),BusinessClock.nowUtcText());
        return new AdminDtos.Ok(true,approvedMfa?"Registration approved. The user can now sign in with password + authenticator.":"Registration approved. Current MFA policy permits password-only sign in.");
    }

    @Transactional
    public AdminDtos.Ok rejectRegistration(long id, AdminDtos.RegistrationDecisionRequest request) {
        var row=jdbc.queryForMap("SELECT username,status,row_version FROM registration_request WHERE id=? FOR UPDATE",id);long version=((Number)row.get("row_version")).longValue();if(request==null||request.rowVersion()!=version)throw new ConcurrentEditException("Registration request");
        if(!"PENDING_ADMIN_APPROVAL".equals(String.valueOf(row.get("status"))))throw new IllegalArgumentException("Registration request has already been processed");
        var actor=CurrentUser.require();String reason=request.reason()==null?"":request.reason().trim();jdbc.update("UPDATE registration_request SET status='REJECTED',reviewed_by=?,reviewed_at=CURRENT_TIMESTAMP,rejection_reason=?,row_version=row_version+1 WHERE id=?",actor.id(),reason,id);
        jdbc.update("INSERT INTO activity_log(entity_type,entity_id,action,detail,created_by,created_at) VALUES('REGISTRATION',?,?,?,?,?)",id,"REGISTRATION_REJECTED",reason.isBlank()?"Registration rejected":reason,actor.username(),BusinessClock.nowUtcText());return new AdminDtos.Ok(true,"Registration rejected");
    }


    @Transactional(readOnly = true)
    public List<AdminDtos.ServerLogEntry> serverDiagnostics(int limit) {
        int maxLines = Math.max(10, Math.min(limit, 1000));
        List<AdminDtos.ServerLogEntry> results = new java.util.ArrayList<>();

        java.nio.file.Path logFile = null;

        String envFile = System.getenv("DSE_LOG_FILE");
        if (envFile == null || envFile.isBlank()) envFile = System.getenv("LOG_FILE");
        if (envFile == null || envFile.isBlank()) envFile = System.getProperty("logging.file.name");
        if (envFile != null && !envFile.isBlank()) {
            java.nio.file.Path candidate = java.nio.file.Path.of(envFile);
            if (java.nio.file.Files.exists(candidate)) logFile = candidate;
        }

        if (logFile == null && workspacePath != null && !workspacePath.isBlank()) {
            for (String sub : List.of("Logs/Server/dse-erp-server.log", "logs/dse-erp-server.log", "logs/server.log")) {
                java.nio.file.Path candidate = java.nio.file.Path.of(workspacePath, sub.split("/"));
                if (java.nio.file.Files.exists(candidate)) {
                    logFile = candidate;
                    break;
                }
            }
        }

        if (logFile == null) {
            List<String> platformCandidates = new java.util.ArrayList<>();
            platformCandidates.add("/srv/dse-erp/Logs/Server/dse-erp-server.log");
            platformCandidates.add("/srv/dse-erp/prod/Logs/Server/dse-erp-server.log");
            platformCandidates.add("/srv/dse-erp/uat/Logs/Server/dse-erp-server.log");
            platformCandidates.add("/srv/dse-erp/logs/dse-erp-server.log");
            platformCandidates.add("/var/log/dse-erp/dse-erp-server.log");
            platformCandidates.add(System.getProperty("user.home", "") + "/.dse-erp/Logs/Server/dse-erp-server.log");
            platformCandidates.add("D:/DSE Production/Logs/Server/dse-erp-server.log");
            platformCandidates.add("logs/dse-erp-server.log");
            platformCandidates.add("logs/server.log");

            for (String candStr : platformCandidates) {
                try {
                    java.nio.file.Path candidate = java.nio.file.Path.of(candStr);
                    if (java.nio.file.Files.exists(candidate)) {
                        logFile = candidate;
                        break;
                    }
                } catch (Exception ignored) {}
            }
        }

        List<String> lines = null;
        if (logFile != null && java.nio.file.Files.exists(logFile)) {
            try {
                lines = java.nio.file.Files.readAllLines(logFile, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception ignored) {}
        }

        if ((lines == null || lines.isEmpty()) && !System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            try {
                Process proc = new ProcessBuilder("journalctl", "-u", "dse-erp-prod", "-u", "dse-erp-uat", "-n", String.valueOf(Math.max(maxLines * 4, 1000)), "--no-pager")
                        .redirectErrorStream(true)
                        .start();
                if (proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                    try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                        lines = reader.lines().toList();
                    }
                }
            } catch (Exception ignored) {}
        }

        if (lines == null || lines.isEmpty()) {
            return List.of();
        }

        try {
            int start = Math.max(0, lines.size() - Math.max(maxLines * 10, 10000));
            List<String> traceBuffer = new java.util.ArrayList<>();

            for (int i = lines.size() - 1; i >= start && results.size() < maxLines; i--) {
                String line = lines.get(i).trim();
                if (line.isBlank()) continue;

                java.util.regex.Matcher mSpring = SPRING_BOOT_LOG_PATTERN.matcher(line);
                java.util.regex.Matcher mLegacy = !mSpring.matches() ? LEGACY_SERVER_LOG_PATTERN.matcher(line) : null;

                if (mSpring.matches() || (mLegacy != null && mLegacy.matches())) {
                    String time;
                    String level;
                    String thread;
                    String logger;
                    String msg;

                    if (mSpring.matches()) {
                        time = mSpring.group(1);
                        level = mSpring.group(2).toUpperCase(java.util.Locale.ROOT);
                        thread = mSpring.group(4) != null ? mSpring.group(4).trim() : "";
                        logger = mSpring.group(5).trim();
                        msg = mSpring.group(6).trim();
                    } else {
                        time = mLegacy.group(1);
                        thread = mLegacy.group(2) != null ? mLegacy.group(2).trim() : "";
                        level = mLegacy.group(3).toUpperCase(java.util.Locale.ROOT);
                        logger = mLegacy.group(4).trim();
                        msg = mLegacy.group(5).trim();
                    }

                    StringBuilder traceBuilder = new StringBuilder();
                    for (int t = traceBuffer.size() - 1; t >= 0; t--) {
                        traceBuilder.append(traceBuffer.get(t)).append("\n");
                    }
                    String rawFailure = traceBuilder.toString().trim();
                    traceBuffer.clear();

                    if (!"ERROR".equalsIgnoreCase(level) && !"WARN".equalsIgnoreCase(level) && !"FATAL".equalsIgnoreCase(level)) {
                        continue;
                    }

                    String simpleLogger = logger;
                    if (simpleLogger.contains(".")) {
                        simpleLogger = simpleLogger.substring(simpleLogger.lastIndexOf('.') + 1);
                    }
                    if (simpleLogger.endsWith("]")) {
                        int open = simpleLogger.indexOf('[');
                        if (open >= 0) simpleLogger = simpleLogger.substring(open + 1, simpleLogger.length() - 1);
                    }

                    results.add(new AdminDtos.ServerLogEntry(
                            time,
                            level,
                            thread,
                            simpleLogger,
                            msg,
                            rawFailure
                    ));
                } else {
                    if (traceBuffer.size() < 250) {
                        traceBuffer.add(line);
                    }
                }
            }
        } catch (Exception ex) {
            java.lang.System.getLogger("org.example").log(java.lang.System.Logger.Level.DEBUG, "Failed reading server log: " + ex.getMessage(), ex);
        }

        return results;
    }

}
