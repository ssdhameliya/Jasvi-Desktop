package org.example.server.enquiry;

import org.example.server.audit.AuditService;
import org.example.server.operations.BusinessOperationsService;
import org.example.server.persistence.JpaNativeRepository;
import org.example.server.security.CurrentUser;
import org.example.server.util.BusinessClock;
import org.example.server.web.ConcurrentEditException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class EnquiryService {
    private static final String REF_ENQUIRY_LOOKUP = "REF_ENQUIRY";
    private static final String REF_ENQUIRY_FALLBACK = "ENQ/DD-MM-YYYY/XXXX";
    private static final Set<String> ALLOWED_STATUSES = Set.of("NEW", "IN_REVIEW", "QUOTED", "WON", "LOST", "SPAM");
    private static final Set<String> ALLOWED_PRIORITIES = Set.of("LOW", "NORMAL", "HIGH", "URGENT");
    private static final long RATE_WINDOW_MS = 10 * 60 * 1000L;
    private static final int MAX_SUBMISSIONS_PER_WINDOW = 12;

    private final JpaNativeRepository jdbc;
    private final BusinessOperationsService operations;
    private final AuditService audit;
    private final EnquiryTokenService tokenService;
    private final ApplicationEventPublisher eventPublisher;
    private final Map<String, Deque<Long>> rateBuckets = new ConcurrentHashMap<>();

    public EnquiryService(
            JpaNativeRepository jdbc,
            BusinessOperationsService operations,
            AuditService audit,
            EnquiryTokenService tokenService,
            ApplicationEventPublisher eventPublisher) {
        this.jdbc = jdbc;
        this.operations = operations;
        this.audit = audit;
        this.tokenService = tokenService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public EnquiryDtos.PublicSubmitResponse submitPublicRfq(
            EnquiryDtos.PublicRfqRequest req, String clientIp, String userAgent) {
        if (req == null) throw new IllegalArgumentException("Enquiry details are required.");
        if (!trim(req.websiteUrl()).isBlank()) {
            return new EnquiryDtos.PublicSubmitResponse(true, "", "Thank you! Your submission has been received.");
        }
        String name = trim(req.name());
        if (name.isBlank()) throw new IllegalArgumentException("Full name is required.");
        String email = trim(req.email());
        String phone = trim(req.phone());
        if (email.isBlank() && phone.isBlank()) {
            throw new IllegalArgumentException("Please provide either a phone number or an email address.");
        }
        enforceRateLimit(clientIp);

        String product = trim(req.productSeries());
        String enquiryType = (!product.isBlank() && !"General Inquiry".equalsIgnoreCase(product)) ? "PRODUCT_RFQ" : "CONTACT_RFQ";
        String sourcePage = firstNonBlank(trim(req.sourcePage()), "contact");
        String subject = firstNonBlank(trim(req.subject()),
                product.isBlank() ? "Website Contact / Quote Request" : "RFQ: " + product);

        SavedPublicEnquiry saved = persistPublicEnquiry(
                enquiryType,
                "NORMAL",
                name,
                trim(req.company()),
                email,
                phone,
                trim(req.cityState()),
                product,
                trim(req.npsSize()),
                trim(req.pressureClass()),
                trim(req.bodyMaterial()),
                trim(req.quantity()),
                subject,
                trim(req.message()),
                null,
                sourcePage,
                Boolean.TRUE.equals(req.whatsappInitiated()),
                clientIp,
                userAgent
        );

        String msg = "Thank you, " + name + "! Your enquiry " + saved.enquiryNo()
                + " has been received and routed directly to our engineering team.";
        return new EnquiryDtos.PublicSubmitResponse(true, saved.enquiryNo(), msg);
    }

    @Transactional
    public EnquiryDtos.PublicSubmitResponse submitPublicFeedback(
            EnquiryDtos.PublicFeedbackRequest req, String clientIp, String userAgent) {
        if (req == null) throw new IllegalArgumentException("Feedback details are required.");
        if (!trim(req.websiteUrl()).isBlank()) {
            return new EnquiryDtos.PublicSubmitResponse(true, "", "Thank you for your feedback!");
        }
        String name = firstNonBlank(trim(req.name()), "Valued Client");
        enforceRateLimit(clientIp);
        int rating = req.rating() == null ? 5 : Math.max(1, Math.min(5, req.rating()));
        String sourcePage = firstNonBlank(trim(req.sourcePage()), "feedback");

        SavedPublicEnquiry saved = persistPublicEnquiry(
                "FEEDBACK",
                "LOW",
                name,
                trim(req.company()),
                trim(req.email()),
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                rating + "/5 Client Rating",
                trim(req.message()),
                rating,
                sourcePage,
                false,
                clientIp,
                userAgent
        );

        return new EnquiryDtos.PublicSubmitResponse(true, saved.enquiryNo(),
                "Thank you, " + name + "! Your feedback has been recorded under " + saved.enquiryNo() + ".");
    }

    private SavedPublicEnquiry persistPublicEnquiry(
            String enquiryType,
            String priority,
            String fullName,
            String companyName,
            String email,
            String phone,
            String cityState,
            String productSeries,
            String npsSize,
            String pressureClass,
            String bodyMaterial,
            String quantityText,
            String subject,
            String message,
            Integer rating,
            String sourcePage,
            boolean whatsappInitiated,
            String clientIp,
            String userAgent) {

        // Dynamic Admin-managed Master Data REF_ENQUIRY lookup numbering (zero hardcoding)
        String enquiryNo = operations.nextConfiguredReference(
                REF_ENQUIRY_LOOKUP,
                REF_ENQUIRY_FALLBACK,
                () -> jdbc.query("SELECT enquiry_no FROM public_enquiries WHERE enquiry_no IS NOT NULL", (r, i) -> r.getString(1)));

        String now = BusinessClock.nowUtcText();
        Long id = jdbc.queryForObject("""
                INSERT INTO public_enquiries(
                    enquiry_no, enquiry_type, status, priority,
                    full_name, company_name, email, phone, city_state,
                    product_series, nps_size, pressure_class, body_material, quantity_text,
                    subject, message, rating, source_page, whatsapp_initiated,
                    client_ip, user_agent, created_at, updated_at, row_version
                ) VALUES (?,?,?,?, ?,?,?,?,?, ?,?,?,?,?, ?,?,?,?,?, ?,?,?,?,0)
                RETURNING id
                """, Long.class,
                enquiryNo, enquiryType, "NEW", priority,
                trunc(fullName, 180), trunc(companyName, 180), trunc(email, 180), trunc(phone, 60), trunc(cityState, 180),
                trunc(productSeries, 200), trunc(npsSize, 80), trunc(pressureClass, 80), trunc(bodyMaterial, 120), trunc(quantityText, 80),
                trunc(subject, 255), message, rating, trunc(sourcePage, 80), whatsappInitiated,
                trunc(trim(clientIp), 64), trunc(trim(userAgent), 255), now, now);

        long enquiryId = id == null ? 0L : id;
        recordActivity(enquiryId, "SUBMITTED",
                enquiryType + " received from " + fullName + (companyName.isBlank() ? "" : " (" + companyName + ")"),
                "PUBLIC_WEB");

        insertAdminNotifications(enquiryId, enquiryNo, enquiryType, fullName, companyName, productSeries, message, rating);

        String token = tokenService.generateToken(enquiryId, enquiryNo);
        eventPublisher.publishEvent(new EnquiryDtos.EnquirySubmittedEvent(
                enquiryId, enquiryNo, enquiryType, fullName, companyName, email, phone, cityState,
                productSeries, npsSize, pressureClass, bodyMaterial, quantityText, message, rating,
                sourcePage, whatsappInitiated, token));

        return new SavedPublicEnquiry(enquiryId, enquiryNo, token);
    }

    private void insertAdminNotifications(
            long enquiryId, String enquiryNo, String enquiryType,
            String fullName, String companyName, String productSeries, String message, Integer rating) {
        boolean isFeedback = "FEEDBACK".equalsIgnoreCase(enquiryType);
        String category = isFeedback ? "COMMUNICATION" : "QUOTATIONS";
        String severity = isFeedback ? "INFO" : "WARN";
        String title = (isFeedback ? "Website Feedback " : "New Website Enquiry ") + enquiryNo;
        StringBuilder summary = new StringBuilder();
        summary.append(fullName);
        if (!companyName.isBlank()) summary.append(" (").append(companyName).append(")");
        if (isFeedback && rating != null) {
            summary.append(" submitted a ").append(rating).append("/5 rating");
        } else if (!productSeries.isBlank()) {
            summary.append(" requested details for ").append(productSeries);
        } else {
            summary.append(" submitted a website contact request");
        }
        if (!message.isBlank()) {
            String snippet = message.length() > 140 ? message.substring(0, 137) + "..." : message;
            summary.append(": ").append(snippet);
        }
        long epochMs = System.currentTimeMillis();
        List<Integer> adminIds = jdbc.query(
                "SELECT id FROM users WHERE active = 1 AND UPPER(COALESCE(role, '')) IN ('ADMIN', 'SUPER_ADMIN', 'SUPERADMIN') ORDER BY id ASC",
                (r, i) -> r.getInt(1));

        for (Integer adminId : adminIds) {
            jdbc.update("""
                    INSERT INTO notifications(
                        title, message, severity, category, is_read,
                        target_fxml, reference_no, module_key, record_id, action_code, created_at, recipient_user_id
                    ) VALUES (?,?,?,?,0,?,?,?,?,?,?,?)
                    """,
                    title, summary.toString(), severity, category,
                    "/fxml/pages/EnquiriesList.fxml", enquiryNo, "ENQUIRY", enquiryId, "VIEW", epochMs, adminId);
        }
    }

    @Transactional(readOnly = true)
    public EnquiryDtos.EnquiryPage page(int page, int size, String q, String type, String status) {
        requireEnquiryView();
        int safeSize = Math.max(10, Math.min(size, 200));
        int safePage = Math.max(0, page);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1=1");

        String query = trim(q);
        if (!query.isBlank()) {
            where.append(" AND LOWER(CONCAT_WS(' ', e.enquiry_no, e.full_name, e.company_name, e.email, e.phone, e.city_state, e.product_series, e.message)) LIKE ?");
            args.add("%" + query.toLowerCase(Locale.ROOT) + "%");
        }
        String t = trim(type).toUpperCase(Locale.ROOT);
        if (!t.isBlank() && !t.startsWith("ALL")) {
            where.append(" AND UPPER(e.enquiry_type) = ?");
            args.add(t);
        }
        String st = trim(status).toUpperCase(Locale.ROOT);
        if (!st.isBlank() && !st.startsWith("ALL")) {
            where.append(" AND UPPER(e.status) = ?");
            args.add(st);
        }

        String joins = baseFrom();
        Object[] baseArgs = args.toArray();
        Long totalObj = jdbc.queryForObject("SELECT COUNT(*) " + joins + where, Long.class, baseArgs);
        long total = totalObj == null ? 0L : totalObj;
        int totalPages = total == 0 ? 0 : (int) Math.ceil(total / (double) safeSize);
        if (totalPages > 0 && safePage >= totalPages) safePage = totalPages - 1;

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add((long) safePage * safeSize);

        List<EnquiryDtos.EnquiryRow> rows = jdbc.query(
                baseSelect() + joins + where + " ORDER BY e.id DESC LIMIT ? OFFSET ?",
                this::mapRow,
                pageArgs.toArray());

        EnquiryDtos.Metrics metrics = computeMetrics();
        return new EnquiryDtos.EnquiryPage(rows, safePage, safeSize, total, totalPages, metrics);
    }

    @Transactional(readOnly = true)
    public EnquiryDtos.EnquiryDetailResponse detail(long id) {
        requireEnquiryView();
        EnquiryDtos.EnquiryRow row = requireRowById(id);
        return new EnquiryDtos.EnquiryDetailResponse(row, loadActivities(id));
    }

    @Transactional(readOnly = true)
    public EnquiryDtos.EnquiryDetailResponse detailByNumber(String enquiryNo) {
        requireEnquiryView();
        String no = trim(enquiryNo);
        if (no.isBlank()) throw new IllegalArgumentException("Enquiry number is required.");
        List<EnquiryDtos.EnquiryRow> rows = jdbc.query(
                baseSelect() + baseFrom() + " WHERE UPPER(e.enquiry_no) = UPPER(?)",
                this::mapRow, no);
        if (rows.isEmpty()) throw new IllegalArgumentException("Enquiry not found: " + no);
        EnquiryDtos.EnquiryRow row = rows.getFirst();
        return new EnquiryDtos.EnquiryDetailResponse(row, loadActivities(row.id()));
    }

    @Transactional
    public EnquiryDtos.EnquiryRow updateStatus(long id, EnquiryDtos.StatusUpdateRequest req) {
        requireEnquiryEdit();
        if (req == null) throw new IllegalArgumentException("Update payload is required.");
        EnquiryDtos.EnquiryRow current = requireRowById(id);

        String nextStatus = firstNonBlank(trim(req.status()).toUpperCase(Locale.ROOT), current.status());
        if (!ALLOWED_STATUSES.contains(nextStatus)) {
            throw new IllegalArgumentException("Invalid enquiry status: " + nextStatus);
        }
        String nextPriority = firstNonBlank(trim(req.priority()).toUpperCase(Locale.ROOT), current.priority());
        if (!ALLOWED_PRIORITIES.contains(nextPriority)) {
            throw new IllegalArgumentException("Invalid priority: " + nextPriority);
        }
        String nextNotes = req.internalNotes() != null ? req.internalNotes().trim() : current.internalNotes();
        Integer assignee = req.assignedUserId() != null ? (req.assignedUserId() > 0 ? req.assignedUserId() : null) : current.assignedUserId();
        String now = BusinessClock.nowUtcText();

        int changed = jdbc.update("""
                UPDATE public_enquiries
                SET status = ?, priority = ?, internal_notes = ?, assigned_user_id = ?, updated_at = ?, row_version = row_version + 1
                WHERE id = ? AND row_version = ?
                """,
                nextStatus, nextPriority, nextNotes, assignee, now, id, req.rowVersion());
        if (changed != 1) throw new ConcurrentEditException("Enquiry " + current.enquiryNo());

        String actor = CurrentUser.isSet() ? CurrentUser.require().username() : "SYSTEM";
        StringBuilder detail = new StringBuilder();
        if (!nextStatus.equals(current.status())) {
            detail.append("Status: ").append(current.status()).append(" -> ").append(nextStatus);
        }
        String note = trim(req.note());
        if (!note.isBlank()) {
            if (!detail.isEmpty()) detail.append(" • ");
            detail.append(note);
        }
        if (detail.isEmpty()) detail.append("Updated enquiry details");
        recordActivity(id, "UPDATED", detail.toString(), actor);
        audit.log("ENQUIRY", (int) Math.min(Integer.MAX_VALUE, id), "UPDATED", current.enquiryNo() + " • " + detail);
        return requireRowById(id);
    }

    @Transactional
    public EnquiryDtos.PrepareQuotationResponse prepareQuotation(long id) {
        requireEnquiryEdit();
        CurrentUser.requirePermission("QUOTATION.CREATE", "Create quotation from enquiry");
        EnquiryDtos.EnquiryRow e = requireRowById(id);
        Integer partyId = e.convertedPartyId();
        if (partyId == null || partyId <= 0) {
            partyId = findMatchingCustomerId(e.phone(), e.email(), firstNonBlank(e.companyName(), e.fullName()));
        }
        if (partyId == null || partyId <= 0) {
            partyId = createCustomerPartyFromEnquiry(e);
        }

        Map<String, Object> partyRow = jdbc.queryForMap(
                "SELECT id, COALESCE(party_code,'') AS code, name FROM party_master WHERE id = ?", partyId);
        int resolvedPartyId = ((Number) partyRow.get("id")).intValue();
        String partyCode = Objects.toString(partyRow.get("code"), "");
        String partyName = Objects.toString(partyRow.get("name"), "");

        String nextStatus = "NEW".equalsIgnoreCase(e.status()) ? "IN_REVIEW" : e.status();
        jdbc.update("""
                UPDATE public_enquiries
                SET converted_party_id = ?, status = ?, updated_at = ?, row_version = row_version + 1
                WHERE id = ?
                """,
                resolvedPartyId, nextStatus, BusinessClock.nowUtcText(), id);

        String actor = CurrentUser.isSet() ? CurrentUser.require().username() : "SYSTEM";
        recordActivity(id, "QUOTATION_PREPARED",
                "Linked Customer " + partyName + (partyCode.isBlank() ? "" : " (" + partyCode + ")") + " for Quotation",
                actor);

        StringBuilder remarks = new StringBuilder();
        remarks.append("Website Enquiry Ref: ").append(e.enquiryNo());
        if (!safe(e.productSeries()).isBlank()) remarks.append("\nProduct: ").append(safe(e.productSeries()));
        List<String> specs = new ArrayList<>();
        if (!safe(e.npsSize()).isBlank()) specs.add("Size: " + safe(e.npsSize()));
        if (!safe(e.pressureClass()).isBlank()) specs.add("Class: " + safe(e.pressureClass()));
        if (!safe(e.bodyMaterial()).isBlank()) specs.add("Material: " + safe(e.bodyMaterial()));
        if (!safe(e.quantityText()).isBlank()) specs.add("Qty: " + safe(e.quantityText()));
        if (!specs.isEmpty()) remarks.append("\nSpecs: ").append(String.join(" | ", specs));
        if (!safe(e.message()).isBlank()) remarks.append("\nRequirement: ").append(safe(e.message()));

        return new EnquiryDtos.PrepareQuotationResponse(
                id, e.enquiryNo(), resolvedPartyId, partyCode, partyName,
                resolveWebsiteQuotationSource(), remarks.toString());
    }

    @Transactional
    public EnquiryDtos.EnquiryRow linkQuotation(long id, int quotationId) {
        requireEnquiryEdit();
        EnquiryDtos.EnquiryRow current = requireRowById(id);
        Map<String, Object> q = jdbc.queryForMap(
                "SELECT id, quotation_no, customer_id FROM quotation_header WHERE id = ?", quotationId);
        String quoteNo = Objects.toString(q.get("quotation_no"), "");
        int custId = ((Number) q.get("customer_id")).intValue();

        jdbc.update("""
                UPDATE public_enquiries
                SET converted_party_id = COALESCE(converted_party_id, ?),
                    converted_quotation_id = ?,
                    status = 'QUOTED',
                    updated_at = ?,
                    row_version = row_version + 1
                WHERE id = ?
                """,
                custId, quotationId, BusinessClock.nowUtcText(), id);

        String actor = CurrentUser.isSet() ? CurrentUser.require().username() : "SYSTEM";
        recordActivity(id, "QUOTATION_LINKED", "Converted to Quotation " + quoteNo, actor);
        audit.log("ENQUIRY", (int) Math.min(Integer.MAX_VALUE, id), "QUOTATION_LINKED",
                current.enquiryNo() + " -> " + quoteNo);
        return requireRowById(id);
    }

    // -------------------------------------------------------------------------
    // Mobile Quick-View Token Access (Zero Mobile App)
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Optional<EnquiryDtos.EnquiryDetailResponse> loadForMobileView(long id, String token) {
        if (id <= 0 || token == null || token.isBlank()) return Optional.empty();
        List<EnquiryDtos.EnquiryRow> rows = jdbc.query(
                baseSelect() + baseFrom() + " WHERE e.id = ?", this::mapRow, id);
        if (rows.isEmpty()) return Optional.empty();
        EnquiryDtos.EnquiryRow row = rows.getFirst();
        if (!tokenService.verifyToken(id, row.enquiryNo(), token)) return Optional.empty();
        return Optional.of(new EnquiryDtos.EnquiryDetailResponse(row, loadActivities(id)));
    }

    @Transactional
    public Optional<EnquiryDtos.EnquiryDetailResponse> updateFromMobileView(
            long id, String token, String status, String note) {
        Optional<EnquiryDtos.EnquiryDetailResponse> verified = loadForMobileView(id, token);
        if (verified.isEmpty()) return Optional.empty();
        EnquiryDtos.EnquiryRow current = verified.get().enquiry();

        String cleanStatus = trim(status).toUpperCase(Locale.ROOT);
        String nextStatus = ALLOWED_STATUSES.contains(cleanStatus) ? cleanStatus : current.status();
        String cleanNote = trim(note);
        String nextNotes = current.internalNotes();
        if (!cleanNote.isBlank()) {
            String stamped = "[" + BusinessClock.nowUtcText() + " Mobile] " + cleanNote;
            nextNotes = (nextNotes == null || nextNotes.isBlank()) ? stamped : nextNotes + "\n" + stamped;
        }
        jdbc.update("""
                UPDATE public_enquiries
                SET status = ?, internal_notes = ?, updated_at = ?, row_version = row_version + 1
                WHERE id = ?
                """,
                nextStatus, nextNotes, BusinessClock.nowUtcText(), id);

        String actionDetail = !nextStatus.equals(current.status())
                ? "Status changed to " + nextStatus + (cleanNote.isBlank() ? "" : " • " + cleanNote)
                : (cleanNote.isBlank() ? "Viewed on Mobile" : "Mobile Note: " + cleanNote);
        recordActivity(id, "MOBILE_UPDATE", actionDetail, "ADMIN_MOBILE_LINK");
        return loadForMobileView(id, token);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Integer findMatchingCustomerId(String phone, String email, String name) {
        if (!safe(email).isBlank()) {
            List<Integer> ids = jdbc.query(
                    "SELECT id FROM party_master WHERE party_type='CUSTOMER' AND COALESCE(is_active,1)=1 AND LOWER(TRIM(COALESCE(email,'')))=LOWER(?) ORDER BY id ASC LIMIT 1",
                    (r, i) -> r.getInt(1), email.trim());
            if (!ids.isEmpty()) return ids.getFirst();
        }
        String digits = safe(phone).replaceAll("[^0-9]", "");
        if (digits.length() >= 7) {
            List<Integer> ids = jdbc.query(
                    "SELECT id FROM party_master WHERE party_type='CUSTOMER' AND COALESCE(is_active,1)=1 AND REGEXP_REPLACE(COALESCE(phone,''), '[^0-9]', '', 'g') LIKE ? ORDER BY id ASC LIMIT 1",
                    (r, i) -> r.getInt(1), "%" + (digits.length() > 10 ? digits.substring(digits.length() - 10) : digits));
            if (!ids.isEmpty()) return ids.getFirst();
        }
        if (!safe(name).isBlank()) {
            List<Integer> ids = jdbc.query(
                    "SELECT id FROM party_master WHERE party_type='CUSTOMER' AND COALESCE(is_active,1)=1 AND LOWER(TRIM(name))=LOWER(?) ORDER BY id ASC LIMIT 1",
                    (r, i) -> r.getInt(1), name.trim());
            if (!ids.isEmpty()) return ids.getFirst();
        }
        return null;
    }

    private int createCustomerPartyFromEnquiry(EnquiryDtos.EnquiryRow e) {
        String code = operations.nextConfiguredReference(
                "REF_CUSTOMER",
                "CUS/DD-MM-YYYY/XXXX",
                () -> jdbc.query("SELECT party_code FROM party_master WHERE party_code IS NOT NULL", (r, i) -> r.getString(1)));
        String partyName = firstNonBlank(safe(e.companyName()), safe(e.fullName()));
        String contact = safe(e.companyName()).isBlank() ? "" : safe(e.fullName());
        Integer id = jdbc.queryForObject("""
                INSERT INTO party_master(party_type, party_code, name, contact_person, phone, email, gstin, address, opening_balance, is_active, row_version)
                VALUES ('CUSTOMER', ?, ?, ?, ?, ?, '', ?, 0, 1, 0)
                RETURNING id
                """, Integer.class,
                code, partyName, contact, safe(e.phone()), safe(e.email()), safe(e.cityState()));
        return id == null ? 0 : id;
    }

    private String resolveWebsiteQuotationSource() {
        List<String> rows = jdbc.query(
                "SELECT lm.lookup_value FROM lookup_master lm " +
                "JOIN master_category mc ON UPPER(TRIM(mc.category_name)) = UPPER(TRIM(lm.lookup_type)) " +
                "WHERE mc.category_code = 'QUOTATION_SOURCE' AND COALESCE(lm.is_active,1) = 1 " +
                "ORDER BY CASE WHEN LOWER(lm.lookup_value) LIKE '%website%' THEN 0 ELSE 1 END, lm.display_order ASC LIMIT 1",
                (r, i) -> r.getString(1));
        return rows.isEmpty() ? "Website RFQ" : rows.getFirst();
    }

    private EnquiryDtos.Metrics computeMetrics() {
        return jdbc.query("""
                SELECT
                    COUNT(*),
                    COUNT(*) FILTER (WHERE UPPER(status) = 'NEW'),
                    COUNT(*) FILTER (WHERE UPPER(status) = 'IN_REVIEW'),
                    COUNT(*) FILTER (WHERE UPPER(status) = 'QUOTED'),
                    COUNT(*) FILTER (WHERE UPPER(status) = 'WON'),
                    COUNT(*) FILTER (WHERE UPPER(enquiry_type) IN ('CONTACT_RFQ', 'PRODUCT_RFQ')),
                    COUNT(*) FILTER (WHERE UPPER(enquiry_type) = 'FEEDBACK'),
                    COALESCE(AVG(rating) FILTER (WHERE rating IS NOT NULL), 0)
                FROM public_enquiries
                """,
                (r, i) -> new EnquiryDtos.Metrics(
                        r.getLong(1), r.getLong(2), r.getLong(3), r.getLong(4),
                        r.getLong(5), r.getLong(6), r.getLong(7), r.getDouble(8)))
                .getFirst();
    }

    private EnquiryDtos.EnquiryRow requireRowById(long id) {
        List<EnquiryDtos.EnquiryRow> rows = jdbc.query(
                baseSelect() + baseFrom() + " WHERE e.id = ?", this::mapRow, id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Enquiry not found: " + id);
        return rows.getFirst();
    }

    private List<EnquiryDtos.ActivityItem> loadActivities(long enquiryId) {
        return jdbc.query(
                "SELECT id, action_type, COALESCE(detail,''), COALESCE(actor_username,''), created_at " +
                "FROM enquiry_activity WHERE enquiry_id = ? ORDER BY id DESC LIMIT 100",
                (r, i) -> new EnquiryDtos.ActivityItem(r.getLong(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5)),
                enquiryId);
    }

    private void recordActivity(long enquiryId, String action, String detail, String actor) {
        jdbc.update(
                "INSERT INTO enquiry_activity(enquiry_id, action_type, detail, actor_username, created_at) VALUES (?,?,?,?,?)",
                enquiryId, action, detail, actor, BusinessClock.nowUtcText());
    }

    private String baseSelect() {
        return """
                SELECT
                    e.id, e.enquiry_no, e.enquiry_type, e.status, e.priority,
                    e.full_name, COALESCE(e.company_name,''), COALESCE(e.email,''), COALESCE(e.phone,''), COALESCE(e.city_state,''),
                    COALESCE(e.product_series,''), COALESCE(e.nps_size,''), COALESCE(e.pressure_class,''), COALESCE(e.body_material,''), COALESCE(e.quantity_text,''),
                    COALESCE(e.subject,''), COALESCE(e.message,''), e.rating, COALESCE(e.source_page,''), COALESCE(e.whatsapp_initiated, FALSE),
                    e.assigned_user_id, COALESCE(u.username,''), e.converted_party_id, COALESCE(p.name,''),
                    e.converted_quotation_id, COALESCE(q.quotation_no,''), COALESCE(e.internal_notes,''),
                    e.created_at, e.updated_at, COALESCE(e.row_version,0)
                """;
    }

    private String baseFrom() {
        return " FROM public_enquiries e LEFT JOIN users u ON u.id = e.assigned_user_id LEFT JOIN party_master p ON p.id = e.converted_party_id LEFT JOIN quotation_header q ON q.id = e.converted_quotation_id";
    }

    private EnquiryDtos.EnquiryRow mapRow(JpaNativeRepository.NativeRow r, Integer i) {
        Number ratingNum = (Number) r.getObject(18);
        Number assignedNum = (Number) r.getObject(21);
        Number partyNum = (Number) r.getObject(23);
        Number quoteNum = (Number) r.getObject(25);
        return new EnquiryDtos.EnquiryRow(
                r.getLong(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5),
                r.getString(6), r.getString(7), r.getString(8), r.getString(9), r.getString(10),
                r.getString(11), r.getString(12), r.getString(13), r.getString(14), r.getString(15),
                r.getString(16), r.getString(17), ratingNum == null ? null : ratingNum.intValue(),
                r.getString(19), r.getBoolean(20),
                assignedNum == null ? null : assignedNum.intValue(), r.getString(22),
                partyNum == null ? null : partyNum.intValue(), r.getString(24),
                quoteNum == null ? null : quoteNum.intValue(), r.getString(26),
                r.getString(27), r.getString(28), r.getString(29), r.getLong(30));
    }

    private void enforceRateLimit(String ip) {
        String key = safe(ip).isBlank() ? "unknown" : ip.trim();
        long now = Instant.now().toEpochMilli();
        Deque<Long> bucket = rateBuckets.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (bucket) {
            while (!bucket.isEmpty() && (now - bucket.peekFirst()) > RATE_WINDOW_MS) {
                bucket.pollFirst();
            }
            if (bucket.size() >= MAX_SUBMISSIONS_PER_WINDOW) {
                throw new IllegalStateException("Too many enquiries submitted briefly. Please wait a few minutes or message us on WhatsApp.");
            }
            bucket.addLast(now);
        }
    }

    private static void requireEnquiryView() {
        if (!(CurrentUser.hasPermission("QUOTATION.VIEW")
                || CurrentUser.hasPermission("SALES.VIEW")
                || CurrentUser.hasPermission("COMMUNICATION.VIEW"))) {
            throw new SecurityException("Permission denied: Enquiries require Quotation, Sales, Communication, or Admin access.");
        }
    }

    private static void requireEnquiryEdit() {
        if (!(CurrentUser.hasPermission("QUOTATION.EDIT")
                || CurrentUser.hasPermission("QUOTATION.CREATE")
                || CurrentUser.hasPermission("SALES.EDIT"))) {
            throw new SecurityException("Permission denied: Updating enquiries requires Quotation/Sales edit or Admin access.");
        }
    }

    private static String trim(String v) { return v == null ? "" : v.trim(); }
    private static String safe(String v) { return trim(v); }
    private static String firstNonBlank(String a, String b) { return !trim(a).isBlank() ? trim(a) : trim(b); }
    private static String trunc(String v, int max) { return v.length() <= max ? v : v.substring(0, max); }

    private record SavedPublicEnquiry(long id, String enquiryNo, String mobileToken) {}
}
