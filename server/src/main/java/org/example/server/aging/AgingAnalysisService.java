package org.example.server.aging;

import org.example.server.audit.AuditService;
import org.example.server.persistence.JpaNativeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@Transactional(readOnly = true)
public class AgingAnalysisService {

    private final JpaNativeRepository jdbc;
    private final AuditService auditService;

    public AgingAnalysisService(JpaNativeRepository jdbc, AuditService auditService) {
        this.jdbc = jdbc;
        this.auditService = auditService;
    }

    public AgingAnalysisDtos.AgingSummaryDto getReceivablesAging(LocalDate asOfDate) {
        LocalDate asOf = asOfDate != null ? asOfDate : LocalDate.now();

        List<Map<String, Object>> rows = jdbc.query("""
            SELECT s.customer_id, COALESCE(p.name, 'Client'), p.gstin, p.phone,
                   s.invoice_date, (s.total_amount - COALESCE(s.paid_amount, 0)) as balance
            FROM sales_header s
            LEFT JOIN party_master p ON p.id = s.customer_id
            WHERE (s.total_amount - COALESCE(s.paid_amount, 0)) > 0
              AND UPPER(COALESCE(s.document_status, '')) NOT IN ('DELETED', 'CANCELLED', 'REJECTED', 'VOID')
            """, (rs, idx) -> {
                Map<String, Object> map = new HashMap<>();
                map.put("partyId", rs.getLong(1));
                map.put("partyName", rs.getString(2));
                map.put("gstin", rs.getString(3));
                map.put("phone", rs.getString(4));
                map.put("invoiceDate", rs.getString(5));
                map.put("balance", rs.getBigDecimal(6));
                return map;
            });

        return buildAgingSummary(asOf, "RECEIVABLES", rows);
    }

    public AgingAnalysisDtos.AgingSummaryDto getPayablesAging(LocalDate asOfDate) {
        LocalDate asOf = asOfDate != null ? asOfDate : LocalDate.now();

        List<Map<String, Object>> rows = jdbc.query("""
            SELECT p.supplier_id, COALESCE(party.name, 'Supplier'), party.gstin, party.phone,
                   p.invoice_date, (p.total_amount - COALESCE(p.paid_amount, 0)) as balance
            FROM purchase_header p
            LEFT JOIN party_master party ON party.id = p.supplier_id
            WHERE (p.total_amount - COALESCE(p.paid_amount, 0)) > 0
              AND UPPER(COALESCE(p.document_status, '')) NOT IN ('DELETED', 'CANCELLED', 'REJECTED', 'VOID')
            """, (rs, idx) -> {
                Map<String, Object> map = new HashMap<>();
                map.put("partyId", rs.getLong(1));
                map.put("partyName", rs.getString(2));
                map.put("gstin", rs.getString(3));
                map.put("phone", rs.getString(4));
                map.put("invoiceDate", rs.getString(5));
                map.put("balance", rs.getBigDecimal(6));
                return map;
            });

        return buildAgingSummary(asOf, "PAYABLES", rows);
    }

    public AgingAnalysisDtos.DesktopAgingReport getDesktopAgingReport(String type, LocalDate asOfDate) {
        LocalDate asOf = asOfDate != null ? asOfDate : LocalDate.now();
        boolean isDebtor = !"CREDITOR".equalsIgnoreCase(type) && !"PAYABLES".equalsIgnoreCase(type);
        AgingAnalysisDtos.AgingSummaryDto summary = isDebtor ? getReceivablesAging(asOf) : getPayablesAging(asOf);

        Map<Long, String> emailsByParty = loadPartyEmails();
        Map<Long, Integer> countsByParty = loadUnpaidCounts(isDebtor);
        Map<Long, String[]> lastReminderByParty = loadLastRemindersByParty();

        BigDecimal b0_30 = BigDecimal.ZERO;
        BigDecimal b31_60 = BigDecimal.ZERO;
        BigDecimal b61_90 = BigDecimal.ZERO;
        BigDecimal b90Plus = BigDecimal.ZERO;
        List<AgingAnalysisDtos.DesktopAgingRow> rows = new ArrayList<>();

        if (summary.parties() != null) {
            for (AgingAnalysisDtos.PartyAgingDto p : summary.parties()) {
                b0_30 = b0_30.add(p.bucket0To30());
                b31_60 = b31_60.add(p.bucket31To60());
                b61_90 = b61_90.add(p.bucket61To90());
                b90Plus = b90Plus.add(p.bucket90Plus());
                String[] lastRem = lastReminderByParty.get(p.partyId());
                rows.add(new AgingAnalysisDtos.DesktopAgingRow(
                        p.partyId(),
                        p.partyName(),
                        p.phone(),
                        emailsByParty.getOrDefault(p.partyId(), ""),
                        countsByParty.getOrDefault(p.partyId(), 0),
                        p.totalDue(),
                        p.bucket0To30(), p.bucket31To60(), p.bucket61To90(), p.bucket90Plus(),
                        p.riskLevel(),
                        lastRem != null ? lastRem[0] : null,
                        lastRem != null ? lastRem[1] : null
                ));
            }
        }

        return new AgingAnalysisDtos.DesktopAgingReport(
                asOf.toString(),
                isDebtor ? "DEBTOR" : "CREDITOR",
                rows.size(),
                summary.totalOutstanding(),
                b0_30, b31_60, b61_90, b90Plus,
                rows
        );
    }

    public AgingAnalysisDtos.PartyStatementDto getPartyStatement(Long partyId, String type, LocalDate asOfDate) {
        LocalDate asOf = asOfDate != null ? asOfDate : LocalDate.now();
        boolean isDebtor = !"CREDITOR".equalsIgnoreCase(type) && !"PAYABLES".equalsIgnoreCase(type);

        List<Map<String, Object>> partyRows = jdbc.query(
                "SELECT id, COALESCE(name, 'Party'), COALESCE(party_type, ''), COALESCE(gstin, ''), COALESCE(phone, ''), COALESCE(email, ''), COALESCE(address, '') FROM party_master WHERE id = ?",
                (rs, i) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("name", rs.getString(2));
                    m.put("partyType", rs.getString(3));
                    m.put("gstin", rs.getString(4));
                    m.put("phone", rs.getString(5));
                    m.put("email", rs.getString(6));
                    m.put("address", rs.getString(7));
                    return m;
                },
                partyId
        );
        Map<String, Object> pm = partyRows.isEmpty() ? Map.of() : partyRows.getFirst();

        String sql = isDebtor
                ? """
                  SELECT COALESCE(s.invoice_no, CAST(s.id AS VARCHAR)), s.invoice_date,
                         COALESCE(s.total_amount, 0), COALESCE(s.paid_amount, 0),
                         (COALESCE(s.total_amount, 0) - COALESCE(s.paid_amount, 0)) AS balance
                  FROM sales_header s
                  WHERE s.customer_id = ?
                    AND (COALESCE(s.total_amount, 0) - COALESCE(s.paid_amount, 0)) > 0
                    AND UPPER(COALESCE(s.document_status, '')) NOT IN ('DELETED', 'CANCELLED', 'REJECTED', 'VOID')
                  ORDER BY s.invoice_date ASC, s.id ASC
                  """
                : """
                  SELECT COALESCE(p.invoice_no, CAST(p.id AS VARCHAR)), p.invoice_date,
                         COALESCE(p.total_amount, 0), COALESCE(p.paid_amount, 0),
                         (COALESCE(p.total_amount, 0) - COALESCE(p.paid_amount, 0)) AS balance
                  FROM purchase_header p
                  WHERE p.supplier_id = ?
                    AND (COALESCE(p.total_amount, 0) - COALESCE(p.paid_amount, 0)) > 0
                    AND UPPER(COALESCE(p.document_status, '')) NOT IN ('DELETED', 'CANCELLED', 'REJECTED', 'VOID')
                  ORDER BY p.invoice_date ASC, p.id ASC
                  """;

        BigDecimal b0_30 = BigDecimal.ZERO;
        BigDecimal b31_60 = BigDecimal.ZERO;
        BigDecimal b61_90 = BigDecimal.ZERO;
        BigDecimal b90Plus = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        List<AgingAnalysisDtos.PartyStatementInvoiceDto> invoices = new ArrayList<>();

        List<Map<String, Object>> invRows = jdbc.query(sql, (rs, idx) -> {
            Map<String, Object> m = new HashMap<>();
            m.put("invoiceNo", rs.getString(1));
            m.put("invoiceDate", rs.getString(2));
            m.put("totalAmount", rs.getBigDecimal(3));
            m.put("paidAmount", rs.getBigDecimal(4));
            m.put("balance", rs.getBigDecimal(5));
            return m;
        }, partyId);

        for (Map<String, Object> r : invRows) {
            BigDecimal bal = (BigDecimal) r.get("balance");
            if (bal == null || bal.compareTo(BigDecimal.ZERO) <= 0) continue;
            String dateStr = Objects.toString(r.get("invoiceDate"), "");
            LocalDate invDate = parseDate(dateStr);
            long days = invDate != null ? Math.max(0L, ChronoUnit.DAYS.between(invDate, asOf)) : 0L;
            String bucket;
            if (days <= 30) {
                bucket = "0-30 Days";
                b0_30 = b0_30.add(bal);
            } else if (days <= 60) {
                bucket = "31-60 Days";
                b31_60 = b31_60.add(bal);
            } else if (days <= 90) {
                bucket = "61-90 Days";
                b61_90 = b61_90.add(bal);
            } else {
                bucket = "90+ Days";
                b90Plus = b90Plus.add(bal);
            }
            total = total.add(bal);
            invoices.add(new AgingAnalysisDtos.PartyStatementInvoiceDto(
                    Objects.toString(r.get("invoiceNo"), ""),
                    dateStr,
                    days,
                    bucket,
                    r.get("totalAmount") != null ? (BigDecimal) r.get("totalAmount") : BigDecimal.ZERO,
                    r.get("paidAmount") != null ? (BigDecimal) r.get("paidAmount") : BigDecimal.ZERO,
                    bal
            ));
        }

        return new AgingAnalysisDtos.PartyStatementDto(
                partyId,
                Objects.toString(pm.get("name"), "Party"),
                isDebtor ? "DEBTOR" : "CREDITOR",
                Objects.toString(pm.get("gstin"), ""),
                Objects.toString(pm.get("phone"), ""),
                Objects.toString(pm.get("email"), ""),
                Objects.toString(pm.get("address"), ""),
                asOf.toString(),
                total, b0_30, b31_60, b61_90, b90Plus,
                invoices
        );
    }

    private Map<Long, String> loadPartyEmails() {
        Map<Long, String> out = new HashMap<>();
        try {
            jdbc.query("SELECT id, COALESCE(email, '') FROM party_master", (rs, i) -> {
                out.put(rs.getLong(1), rs.getString(2));
                return null;
            });
        } catch (Exception ignored) {}
        return out;
    }

    private Map<Long, Integer> loadUnpaidCounts(boolean isDebtor) {
        Map<Long, Integer> out = new HashMap<>();
        String sql = isDebtor
                ? "SELECT customer_id, COUNT(*) FROM sales_header WHERE (COALESCE(total_amount, 0) - COALESCE(paid_amount, 0)) > 0 AND UPPER(COALESCE(document_status, '')) NOT IN ('DELETED', 'CANCELLED', 'REJECTED', 'VOID') GROUP BY customer_id"
                : "SELECT supplier_id, COUNT(*) FROM purchase_header WHERE (COALESCE(total_amount, 0) - COALESCE(paid_amount, 0)) > 0 AND UPPER(COALESCE(document_status, '')) NOT IN ('DELETED', 'CANCELLED', 'REJECTED', 'VOID') GROUP BY supplier_id";
        try {
            jdbc.query(sql, (rs, i) -> {
                out.put(rs.getLong(1), rs.getInt(2));
                return null;
            });
        } catch (Exception ignored) {}
        return out;
    }

    private Map<Long, String[]> loadLastRemindersByParty() {
        Map<Long, String[]> out = new HashMap<>();
        try {
            jdbc.query("""
                SELECT entity_id, created_at, channel
                FROM communication_log
                WHERE UPPER(COALESCE(entity_type, '')) IN ('PARTY', 'AGING_COLLECTION')
                  AND UPPER(COALESCE(status, '')) = 'SENT'
                  AND UPPER(COALESCE(channel, '')) IN ('EMAIL', 'WHATSAPP')
                  AND entity_id IS NOT NULL AND entity_id > 0
                ORDER BY id DESC
                """, (rs, i) -> {
                long pid = rs.getLong(1);
                out.putIfAbsent(pid, new String[]{rs.getString(2), rs.getString(3)});
                return null;
            });
        } catch (Exception ignored) {}
        return out;
    }

    @Transactional
    public boolean sendPaymentReminder(Long partyId, String user) {
        try {
            String name = jdbc.queryForObject("SELECT name FROM party_master WHERE id = ?", String.class, partyId);
            auditService.log("AGING_ANALYSIS", partyId, "REMINDER_SENT",
                    "Sent payment reminder to " + name + " by " + (user != null ? user : "SYSTEM"));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private AgingAnalysisDtos.AgingSummaryDto buildAgingSummary(LocalDate asOf, String type, List<Map<String, Object>> rows) {
        Map<Long, PartyBuckets> bucketsByParty = new LinkedHashMap<>();

        BigDecimal totalOutstanding = BigDecimal.ZERO;
        BigDecimal totalOverdue = BigDecimal.ZERO;

        for (Map<String, Object> row : rows) {
            Long partyId = (Long) row.get("partyId");
            String partyName = (String) row.get("partyName");
            String gstin = (String) row.get("gstin");
            String phone = (String) row.get("phone");
            String dateStr = (String) row.get("invoiceDate");
            BigDecimal balance = (BigDecimal) row.get("balance");

            if (balance == null || balance.compareTo(BigDecimal.ZERO) <= 0) continue;

            LocalDate invDate = parseDate(dateStr);
            long days = invDate != null ? ChronoUnit.DAYS.between(invDate, asOf) : 0;
            if (days < 0) days = 0;

            PartyBuckets b = bucketsByParty.computeIfAbsent(partyId, k -> new PartyBuckets(partyId, partyName, type, gstin, phone));
            b.addInvoice(days, balance);

            totalOutstanding = totalOutstanding.add(balance);
            if (days > 30) {
                totalOverdue = totalOverdue.add(balance);
            }
        }

        List<AgingAnalysisDtos.PartyAgingDto> partyList = new ArrayList<>();
        for (PartyBuckets pb : bucketsByParty.values()) {
            partyList.add(pb.toDto());
        }

        partyList.sort((a, b) -> b.totalDue().compareTo(a.totalDue()));

        return new AgingAnalysisDtos.AgingSummaryDto(
                asOf, type, totalOutstanding, totalOverdue, partyList.size(), partyList
        );
    }

    private LocalDate parseDate(String str) {
        if (str == null || str.isBlank()) return null;
        try {
            if (str.length() >= 10) {
                return LocalDate.parse(str.substring(0, 10));
            }
        } catch (Exception ignored) { java.lang.System.getLogger("org.example").log(java.lang.System.Logger.Level.DEBUG, "Suppressed exception: " + ignored.getMessage(), ignored); }
        try {
            return LocalDate.parse(str, DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        } catch (Exception ignored) { java.lang.System.getLogger("org.example").log(java.lang.System.Logger.Level.DEBUG, "Suppressed exception: " + ignored.getMessage(), ignored); }
        return null;
    }

    private static class PartyBuckets {
        final Long partyId;
        final String partyName;
        final String partyType;
        final String gstin;
        final String phone;
        BigDecimal b0_30 = BigDecimal.ZERO;
        BigDecimal b31_60 = BigDecimal.ZERO;
        BigDecimal b61_90 = BigDecimal.ZERO;
        BigDecimal b90Plus = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;

        PartyBuckets(Long partyId, String partyName, String partyType, String gstin, String phone) {
            this.partyId = partyId;
            this.partyName = partyName;
            this.partyType = partyType;
            this.gstin = gstin;
            this.phone = phone;
        }

        void addInvoice(long days, BigDecimal amount) {
            total = total.add(amount);
            if (days <= 30) {
                b0_30 = b0_30.add(amount);
            } else if (days <= 60) {
                b31_60 = b31_60.add(amount);
            } else if (days <= 90) {
                b61_90 = b61_90.add(amount);
            } else {
                b90Plus = b90Plus.add(amount);
            }
        }

        AgingAnalysisDtos.PartyAgingDto toDto() {
            String risk = "LOW";
            if (b90Plus.compareTo(BigDecimal.ZERO) > 0) {
                risk = "HIGH";
            } else if (b61_90.compareTo(BigDecimal.ZERO) > 0) {
                risk = "MEDIUM";
            }
            return new AgingAnalysisDtos.PartyAgingDto(
                    partyId, partyName, partyType, gstin, phone,
                    b0_30, b31_60, b61_90, b90Plus, total, risk
            );
        }
    }
}
