package org.example.server.creditdebit;

import org.example.server.accounting.GeneralLedgerDtos;
import org.example.server.accounting.GeneralLedgerService;
import org.example.server.audit.AuditService;
import org.example.server.persistence.JpaNativeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

@Service
@Transactional
public class CreditDebitNoteService {

    private final JpaNativeRepository jdbc;
    private final GeneralLedgerService glService;
    private final AuditService auditService;

    public CreditDebitNoteService(JpaNativeRepository jdbc,
                                  GeneralLedgerService glService,
                                  AuditService auditService) {
        this.jdbc = jdbc;
        this.glService = glService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<CreditDebitNoteDtos.CreditNoteDto> listCreditNotes() {
        return jdbc.query("""
            SELECT id, credit_note_no, note_date, original_invoice_no, party_id, party_name, party_gstin,
                   reason_code, taxable_amount, cgst_amount, sgst_amount, igst_amount, roundoff_amount,
                   total_amount, stock_returned, status, gl_entry_id, notes, created_by,
                   created_at::text, row_version
            FROM credit_note_header
            ORDER BY note_date DESC, id DESC
            """, (row, idx) -> new CreditDebitNoteDtos.CreditNoteDto(
                row.getLong(1), row.getString(2),
                parseDate(row.getObject(3)), row.getString(4),
                row.getLong(5), row.getString(6), row.getString(7),
                row.getString(8), row.getBigDecimal(9), row.getBigDecimal(10),
                row.getBigDecimal(11), row.getBigDecimal(12), row.getBigDecimal(13),
                row.getBigDecimal(14), row.getBoolean(15), row.getString(16),
                toLongOrNull(row.getObject(17)), row.getString(18), row.getString(19),
                row.getString(20), row.getLong(21),
                List.of()
        ));
    }

    @Transactional(readOnly = true)
    public Optional<CreditDebitNoteDtos.CreditNoteDto> getCreditNote(Long id) {
        List<CreditDebitNoteDtos.CreditNoteDto> headers = jdbc.query("""
            SELECT id, credit_note_no, note_date, original_invoice_no, party_id, party_name, party_gstin,
                   reason_code, taxable_amount, cgst_amount, sgst_amount, igst_amount, roundoff_amount,
                   total_amount, stock_returned, status, gl_entry_id, notes, created_by,
                   created_at::text, row_version
            FROM credit_note_header WHERE id = ?
            """, (row, idx) -> new CreditDebitNoteDtos.CreditNoteDto(
                row.getLong(1), row.getString(2),
                parseDate(row.getObject(3)), row.getString(4),
                row.getLong(5), row.getString(6), row.getString(7),
                row.getString(8), row.getBigDecimal(9), row.getBigDecimal(10),
                row.getBigDecimal(11), row.getBigDecimal(12), row.getBigDecimal(13),
                row.getBigDecimal(14), row.getBoolean(15), row.getString(16),
                toLongOrNull(row.getObject(17)), row.getString(18), row.getString(19),
                row.getString(20), row.getLong(21),
                new ArrayList<>()
        ), id);

        if (headers.isEmpty()) return Optional.empty();
        CreditDebitNoteDtos.CreditNoteDto header = headers.get(0);

        List<CreditDebitNoteDtos.NoteLineDto> lines = jdbc.query("""
            SELECT id, item_id, item_code, item_description, hsn_sac, quantity, unit_price,
                   gst_rate, taxable_value, cgst_amount, sgst_amount, igst_amount, total_line_amount
            FROM credit_note_line WHERE credit_note_id = ? ORDER BY id ASC
            """, (row, idx) -> new CreditDebitNoteDtos.NoteLineDto(
                row.getLong(1), toLongOrNull(row.getObject(2)), row.getString(3), row.getString(4),
                row.getString(5), row.getBigDecimal(6), row.getBigDecimal(7),
                row.getBigDecimal(8), row.getBigDecimal(9), row.getBigDecimal(10),
                row.getBigDecimal(11), row.getBigDecimal(12), row.getBigDecimal(13)
        ), id);

        return Optional.of(new CreditDebitNoteDtos.CreditNoteDto(
                header.id(), header.creditNoteNo(), header.noteDate(), header.originalInvoiceNo(),
                header.partyId(), header.partyName(), header.partyGstin(), header.reasonCode(),
                header.taxableAmount(), header.cgstAmount(), header.sgstAmount(), header.igstAmount(),
                header.roundoffAmount(), header.totalAmount(), header.stockReturned(), header.status(),
                header.glEntryId(), header.notes(), header.createdBy(), header.createdAt(), header.rowVersion(),
                lines
        ));
    }

    public CreditDebitNoteDtos.CreditNoteDto createCreditNote(CreditDebitNoteDtos.CreditNoteDto dto, String user) {
        String noteNo = generateNoteNumber("CN");
        LocalDate date = dto.noteDate() != null ? dto.noteDate() : LocalDate.now();

        BigDecimal taxable = BigDecimal.ZERO;
        BigDecimal cgst = BigDecimal.ZERO;
        BigDecimal sgst = BigDecimal.ZERO;
        BigDecimal igst = BigDecimal.ZERO;

        List<CreditDebitNoteDtos.NoteLineDto> processedLines = new ArrayList<>();
        if (dto.lines() != null) {
            for (CreditDebitNoteDtos.NoteLineDto line : dto.lines()) {
                BigDecimal qty = line.quantity() != null ? line.quantity() : BigDecimal.ONE;
                BigDecimal price = line.unitPrice() != null ? line.unitPrice() : BigDecimal.ZERO;
                BigDecimal lineTaxable = qty.multiply(price).setScale(2, RoundingMode.HALF_UP);
                BigDecimal rate = line.gstRate() != null ? line.gstRate() : BigDecimal.ZERO;

                BigDecimal lineCgst = line.cgstAmount() != null ? line.cgstAmount() : BigDecimal.ZERO;
                BigDecimal lineSgst = line.sgstAmount() != null ? line.sgstAmount() : BigDecimal.ZERO;
                BigDecimal lineIgst = line.igstAmount() != null ? line.igstAmount() : BigDecimal.ZERO;

                if (lineCgst.compareTo(BigDecimal.ZERO) == 0 && lineSgst.compareTo(BigDecimal.ZERO) == 0 && lineIgst.compareTo(BigDecimal.ZERO) == 0 && rate.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal taxTotal = lineTaxable.multiply(rate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                    if (isInterState(dto.partyGstin())) {
                        lineIgst = taxTotal;
                        lineCgst = BigDecimal.ZERO;
                        lineSgst = BigDecimal.ZERO;
                    } else {
                        lineCgst = taxTotal.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
                        lineSgst = taxTotal.subtract(lineCgst);
                        lineIgst = BigDecimal.ZERO;
                    }
                }

                taxable = taxable.add(lineTaxable);
                cgst = cgst.add(lineCgst);
                sgst = sgst.add(lineSgst);
                igst = igst.add(lineIgst);

                BigDecimal lineTotal = lineTaxable.add(lineCgst).add(lineSgst).add(lineIgst);
                processedLines.add(new CreditDebitNoteDtos.NoteLineDto(
                        null, line.itemId(), line.itemCode(), line.itemDescription(), line.hsnSac(),
                        qty, price, rate, lineTaxable, lineCgst, lineSgst, lineIgst, lineTotal
                ));
            }
        }

        BigDecimal grossTotal = taxable.add(cgst).add(sgst).add(igst);
        BigDecimal roundedTotal = grossTotal.setScale(0, RoundingMode.HALF_UP).setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal roundoff = roundedTotal.subtract(grossTotal);

        Long partyId = dto.partyId();
        String partyName = dto.partyName();
        String partyGstin = dto.partyGstin();

        if (partyId == null || partyId <= 0) {
            if (dto.originalInvoiceNo() != null && !dto.originalInvoiceNo().isBlank()) {
                try {
                    Long foundId = jdbc.queryForObject("SELECT customer_id FROM sales_header WHERE invoice_no = ? LIMIT 1", Long.class, dto.originalInvoiceNo().trim());
                    if (foundId != null) partyId = foundId;
                } catch (Exception ignored) { java.lang.System.getLogger("org.example").log(java.lang.System.Logger.Level.DEBUG, "Suppressed exception: " + ignored.getMessage(), ignored); }
            }
        }
        if (partyId == null || partyId <= 0) {
            if (partyName != null && !partyName.isBlank()) {
                try {
                    Long foundId = jdbc.queryForObject("SELECT id FROM party_master WHERE UPPER(name) = UPPER(?) LIMIT 1", Long.class, partyName.trim());
                    if (foundId != null) partyId = foundId;
                } catch (Exception ignored) { java.lang.System.getLogger("org.example").log(java.lang.System.Logger.Level.DEBUG, "Suppressed exception: " + ignored.getMessage(), ignored); }
            }
        }
        if (partyId == null || partyId <= 0) {
            if (partyGstin != null && !partyGstin.isBlank()) {
                try {
                    Long foundId = jdbc.queryForObject("SELECT id FROM party_master WHERE UPPER(gstin) = UPPER(?) LIMIT 1", Long.class, partyGstin.trim());
                    if (foundId != null) partyId = foundId;
                } catch (Exception ignored) { java.lang.System.getLogger("org.example").log(java.lang.System.Logger.Level.DEBUG, "Suppressed exception: " + ignored.getMessage(), ignored); }
            }
        }
        if (partyId == null || partyId <= 0) {
            try {
                partyId = jdbc.queryForObject("SELECT id FROM party_master WHERE party_type = 'CUSTOMER' ORDER BY id ASC LIMIT 1", Long.class);
            } catch (Exception ignored) { java.lang.System.getLogger("org.example").log(java.lang.System.Logger.Level.DEBUG, "Suppressed exception: " + ignored.getMessage(), ignored); }
            if (partyId == null) {
                try {
                    partyId = jdbc.queryForObject("SELECT id FROM party_master ORDER BY id ASC LIMIT 1", Long.class);
                } catch (Exception ignored) { java.lang.System.getLogger("org.example").log(java.lang.System.Logger.Level.DEBUG, "Suppressed exception: " + ignored.getMessage(), ignored); }
            }
        }
        if (partyId == null || partyId <= 0) {
            throw new IllegalArgumentException("A valid customer party is required for creating a Credit Note");
        }
        if (partyName == null || partyName.isBlank()) {
            try {
                partyName = jdbc.queryForObject("SELECT name FROM party_master WHERE id = ?", String.class, partyId);
            } catch (Exception ignored) { java.lang.System.getLogger("org.example").log(java.lang.System.Logger.Level.DEBUG, "Suppressed exception: " + ignored.getMessage(), ignored); }
            if (partyName == null) partyName = "Customer";
        }

        Long headerId = jdbc.queryForObject("""
            INSERT INTO credit_note_header(
                credit_note_no, note_date, original_invoice_no, party_id, party_name, party_gstin,
                reason_code, taxable_amount, cgst_amount, sgst_amount, igst_amount, roundoff_amount,
                total_amount, stock_returned, status, notes, created_by
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING id
            """, Long.class,
                noteNo, date, dto.originalInvoiceNo(), partyId, partyName, partyGstin,
                dto.reasonCode() != null ? dto.reasonCode() : "SALES_RETURN",
                taxable, cgst, sgst, igst, roundoff, roundedTotal,
                dto.stockReturned() != null ? dto.stockReturned() : true,
                "POSTED", dto.notes(), user != null ? user : "SYSTEM"
        );

        for (CreditDebitNoteDtos.NoteLineDto line : processedLines) {
            jdbc.update("""
                INSERT INTO credit_note_line(
                    credit_note_id, item_id, item_code, item_description, hsn_sac,
                    quantity, unit_price, gst_rate, taxable_value, cgst_amount, sgst_amount, igst_amount,
                    total_line_amount
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                    headerId, line.itemId(), line.itemCode(), line.itemDescription(), line.hsnSac(),
                    line.quantity(), line.unitPrice(), line.gstRate(), line.taxableValue(),
                    line.cgstAmount(), line.sgstAmount(), line.igstAmount(), line.totalLineAmount()
            );

            if (Boolean.TRUE.equals(dto.stockReturned())) {
                try {
                    Long targetItemId = line.itemId();
                    if (targetItemId == null && line.itemCode() != null && !line.itemCode().isBlank()) {
                        targetItemId = jdbc.queryForObject("SELECT id FROM item_master WHERE item_code = ? LIMIT 1", Long.class, line.itemCode().trim());
                    }
                    if (targetItemId != null && targetItemId > 0) {
                        jdbc.update("UPDATE item_master SET opening_stock = COALESCE(opening_stock, 0) + ? WHERE id = ?",
                                line.quantity(), targetItemId);
                        String itmCode = line.itemCode();
                        if (itmCode == null || itmCode.isBlank()) {
                            itmCode = jdbc.queryForObject("SELECT item_code FROM item_master WHERE id = ?", String.class, targetItemId);
                        }
                        if (itmCode != null && !itmCode.isBlank()) {
                            jdbc.update("UPDATE inventory_cost_state SET quantity = COALESCE(quantity, 0) + ?, last_updated_at = NOW() WHERE item_code = ?",
                                    line.quantity(), itmCode);
                        }
                    }
                } catch (Exception ignored) { java.lang.System.getLogger("org.example").log(java.lang.System.Logger.Level.DEBUG, "Suppressed exception: " + ignored.getMessage(), ignored); }
            }
        }

        // Post balancing Journal Voucher via General Ledger
        String cnReason = dto.reasonCode() != null ? dto.reasonCode() : "SALES_RETURN";
        Long glId = postCreditNoteJournal(headerId, noteNo, date, partyId.intValue(), partyName, cnReason,
                taxable, cgst, sgst, igst, roundoff, roundedTotal, user);
        if (glId != null) {
            jdbc.update("UPDATE credit_note_header SET gl_entry_id = ? WHERE id = ?", glId, headerId);
        }

        auditService.log("CREDIT_NOTE", headerId, "CREATED",
                "Created Sales Credit Note " + noteNo + " for party " + partyName + " (Total: " + roundedTotal + ")");

        return getCreditNote(headerId).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<CreditDebitNoteDtos.DebitNoteDto> listDebitNotes() {
        return jdbc.query("""
            SELECT id, debit_note_no, note_date, original_bill_no, supplier_id, supplier_name, supplier_gstin,
                   reason_code, taxable_amount, cgst_amount, sgst_amount, igst_amount, roundoff_amount,
                   total_amount, stock_returned, status, gl_entry_id, notes, created_by,
                   created_at::text, row_version
            FROM debit_note_header
            ORDER BY note_date DESC, id DESC
            """, (row, idx) -> new CreditDebitNoteDtos.DebitNoteDto(
                row.getLong(1), row.getString(2),
                parseDate(row.getObject(3)), row.getString(4),
                row.getLong(5), row.getString(6), row.getString(7),
                row.getString(8), row.getBigDecimal(9), row.getBigDecimal(10),
                row.getBigDecimal(11), row.getBigDecimal(12), row.getBigDecimal(13),
                row.getBigDecimal(14), row.getBoolean(15), row.getString(16),
                toLongOrNull(row.getObject(17)), row.getString(18), row.getString(19),
                row.getString(20), row.getLong(21),
                List.of()
        ));
    }

    @Transactional(readOnly = true)
    public Optional<CreditDebitNoteDtos.DebitNoteDto> getDebitNote(Long id) {
        List<CreditDebitNoteDtos.DebitNoteDto> headers = jdbc.query("""
            SELECT id, debit_note_no, note_date, original_bill_no, supplier_id, supplier_name, supplier_gstin,
                   reason_code, taxable_amount, cgst_amount, sgst_amount, igst_amount, roundoff_amount,
                   total_amount, stock_returned, status, gl_entry_id, notes, created_by,
                   created_at::text, row_version
            FROM debit_note_header WHERE id = ?
            """, (row, idx) -> new CreditDebitNoteDtos.DebitNoteDto(
                row.getLong(1), row.getString(2),
                parseDate(row.getObject(3)), row.getString(4),
                row.getLong(5), row.getString(6), row.getString(7),
                row.getString(8), row.getBigDecimal(9), row.getBigDecimal(10),
                row.getBigDecimal(11), row.getBigDecimal(12), row.getBigDecimal(13),
                row.getBigDecimal(14), row.getBoolean(15), row.getString(16),
                toLongOrNull(row.getObject(17)), row.getString(18), row.getString(19),
                row.getString(20), row.getLong(21),
                new ArrayList<>()
        ), id);

        if (headers.isEmpty()) return Optional.empty();
        CreditDebitNoteDtos.DebitNoteDto header = headers.get(0);

        List<CreditDebitNoteDtos.NoteLineDto> lines = jdbc.query("""
            SELECT id, item_id, item_code, item_description, hsn_sac, quantity, unit_cost,
                   gst_rate, taxable_value, cgst_amount, sgst_amount, igst_amount, total_line_amount
            FROM debit_note_line WHERE debit_note_id = ? ORDER BY id ASC
            """, (row, idx) -> new CreditDebitNoteDtos.NoteLineDto(
                row.getLong(1), toLongOrNull(row.getObject(2)), row.getString(3), row.getString(4),
                row.getString(5), row.getBigDecimal(6), row.getBigDecimal(7),
                row.getBigDecimal(8), row.getBigDecimal(9), row.getBigDecimal(10),
                row.getBigDecimal(11), row.getBigDecimal(12), row.getBigDecimal(13)
        ), id);

        return Optional.of(new CreditDebitNoteDtos.DebitNoteDto(
                header.id(), header.debitNoteNo(), header.noteDate(), header.originalBillNo(),
                header.supplierId(), header.supplierName(), header.supplierGstin(), header.reasonCode(),
                header.taxableAmount(), header.cgstAmount(), header.sgstAmount(), header.igstAmount(),
                header.roundoffAmount(), header.totalAmount(), header.stockReturned(), header.status(),
                header.glEntryId(), header.notes(), header.createdBy(), header.createdAt(), header.rowVersion(),
                lines
        ));
    }

    public CreditDebitNoteDtos.DebitNoteDto createDebitNote(CreditDebitNoteDtos.DebitNoteDto dto, String user) {
        String noteNo = generateNoteNumber("DN");
        LocalDate date = dto.noteDate() != null ? dto.noteDate() : LocalDate.now();

        BigDecimal taxable = BigDecimal.ZERO;
        BigDecimal cgst = BigDecimal.ZERO;
        BigDecimal sgst = BigDecimal.ZERO;
        BigDecimal igst = BigDecimal.ZERO;

        List<CreditDebitNoteDtos.NoteLineDto> processedLines = new ArrayList<>();
        if (dto.lines() != null) {
            for (CreditDebitNoteDtos.NoteLineDto line : dto.lines()) {
                BigDecimal qty = line.quantity() != null ? line.quantity() : BigDecimal.ONE;
                BigDecimal cost = line.unitPrice() != null ? line.unitPrice() : BigDecimal.ZERO;
                BigDecimal lineTaxable = qty.multiply(cost).setScale(2, RoundingMode.HALF_UP);
                BigDecimal rate = line.gstRate() != null ? line.gstRate() : BigDecimal.ZERO;

                BigDecimal lineCgst = line.cgstAmount() != null ? line.cgstAmount() : BigDecimal.ZERO;
                BigDecimal lineSgst = line.sgstAmount() != null ? line.sgstAmount() : BigDecimal.ZERO;
                BigDecimal lineIgst = line.igstAmount() != null ? line.igstAmount() : BigDecimal.ZERO;

                if (lineCgst.compareTo(BigDecimal.ZERO) == 0 && lineSgst.compareTo(BigDecimal.ZERO) == 0 && lineIgst.compareTo(BigDecimal.ZERO) == 0 && rate.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal taxTotal = lineTaxable.multiply(rate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                    if (isInterState(dto.supplierGstin())) {
                        lineIgst = taxTotal;
                        lineCgst = BigDecimal.ZERO;
                        lineSgst = BigDecimal.ZERO;
                    } else {
                        lineCgst = taxTotal.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
                        lineSgst = taxTotal.subtract(lineCgst);
                        lineIgst = BigDecimal.ZERO;
                    }
                }

                taxable = taxable.add(lineTaxable);
                cgst = cgst.add(lineCgst);
                sgst = sgst.add(lineSgst);
                igst = igst.add(lineIgst);

                BigDecimal lineTotal = lineTaxable.add(lineCgst).add(lineSgst).add(lineIgst);
                processedLines.add(new CreditDebitNoteDtos.NoteLineDto(
                        null, line.itemId(), line.itemCode(), line.itemDescription(), line.hsnSac(),
                        qty, cost, rate, lineTaxable, lineCgst, lineSgst, lineIgst, lineTotal
                ));
            }
        }

        BigDecimal grossTotal = taxable.add(cgst).add(sgst).add(igst);
        BigDecimal roundedTotal = grossTotal.setScale(0, RoundingMode.HALF_UP).setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal roundoff = roundedTotal.subtract(grossTotal);

        Long supplierId = dto.supplierId();
        String supplierName = dto.supplierName();
        String supplierGstin = dto.supplierGstin();

        if (supplierId == null || supplierId <= 0) {
            if (dto.originalBillNo() != null && !dto.originalBillNo().isBlank()) {
                try {
                    Long foundId = jdbc.queryForObject("SELECT supplier_id FROM purchase_header WHERE invoice_no = ? LIMIT 1", Long.class, dto.originalBillNo().trim());
                    if (foundId != null) supplierId = foundId;
                } catch (Exception ignored) { java.lang.System.getLogger("org.example").log(java.lang.System.Logger.Level.DEBUG, "Suppressed exception: " + ignored.getMessage(), ignored); }
            }
        }
        if (supplierId == null || supplierId <= 0) {
            if (supplierName != null && !supplierName.isBlank()) {
                try {
                    Long foundId = jdbc.queryForObject("SELECT id FROM party_master WHERE UPPER(name) = UPPER(?) LIMIT 1", Long.class, supplierName.trim());
                    if (foundId != null) supplierId = foundId;
                } catch (Exception ignored) { java.lang.System.getLogger("org.example").log(java.lang.System.Logger.Level.DEBUG, "Suppressed exception: " + ignored.getMessage(), ignored); }
            }
        }
        if (supplierId == null || supplierId <= 0) {
            if (supplierGstin != null && !supplierGstin.isBlank()) {
                try {
                    Long foundId = jdbc.queryForObject("SELECT id FROM party_master WHERE UPPER(gstin) = UPPER(?) LIMIT 1", Long.class, supplierGstin.trim());
                    if (foundId != null) supplierId = foundId;
                } catch (Exception ignored) { java.lang.System.getLogger("org.example").log(java.lang.System.Logger.Level.DEBUG, "Suppressed exception: " + ignored.getMessage(), ignored); }
            }
        }
        if (supplierId == null || supplierId <= 0) {
            try {
                supplierId = jdbc.queryForObject("SELECT id FROM party_master WHERE party_type = 'SUPPLIER' ORDER BY id ASC LIMIT 1", Long.class);
            } catch (Exception ignored) { java.lang.System.getLogger("org.example").log(java.lang.System.Logger.Level.DEBUG, "Suppressed exception: " + ignored.getMessage(), ignored); }
            if (supplierId == null) {
                try {
                    supplierId = jdbc.queryForObject("SELECT id FROM party_master ORDER BY id ASC LIMIT 1", Long.class);
                } catch (Exception ignored) { java.lang.System.getLogger("org.example").log(java.lang.System.Logger.Level.DEBUG, "Suppressed exception: " + ignored.getMessage(), ignored); }
            }
        }
        if (supplierId == null || supplierId <= 0) {
            throw new IllegalArgumentException("A valid supplier party is required for creating a Debit Note");
        }
        if (supplierName == null || supplierName.isBlank()) {
            try {
                supplierName = jdbc.queryForObject("SELECT name FROM party_master WHERE id = ?", String.class, supplierId);
            } catch (Exception ignored) { java.lang.System.getLogger("org.example").log(java.lang.System.Logger.Level.DEBUG, "Suppressed exception: " + ignored.getMessage(), ignored); }
            if (supplierName == null) supplierName = "Supplier";
        }

        Long headerId = jdbc.queryForObject("""
            INSERT INTO debit_note_header(
                debit_note_no, note_date, original_bill_no, supplier_id, supplier_name, supplier_gstin,
                reason_code, taxable_amount, cgst_amount, sgst_amount, igst_amount, roundoff_amount,
                total_amount, stock_returned, status, notes, created_by
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING id
            """, Long.class,
                noteNo, date, dto.originalBillNo(), supplierId, supplierName, supplierGstin,
                dto.reasonCode() != null ? dto.reasonCode() : "PURCHASE_RETURN",
                taxable, cgst, sgst, igst, roundoff, roundedTotal,
                dto.stockReturned() != null ? dto.stockReturned() : true,
                "POSTED", dto.notes(), user != null ? user : "SYSTEM"
        );

        for (CreditDebitNoteDtos.NoteLineDto line : processedLines) {
            jdbc.update("""
                INSERT INTO debit_note_line(
                    debit_note_id, item_id, item_code, item_description, hsn_sac,
                    quantity, unit_cost, gst_rate, taxable_value, cgst_amount, sgst_amount, igst_amount,
                    total_line_amount
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                    headerId, line.itemId(), line.itemCode(), line.itemDescription(), line.hsnSac(),
                    line.quantity(), line.unitPrice(), line.gstRate(), line.taxableValue(),
                    line.cgstAmount(), line.sgstAmount(), line.igstAmount(), line.totalLineAmount()
            );

            if (Boolean.TRUE.equals(dto.stockReturned())) {
                try {
                    Long targetItemId = line.itemId();
                    if (targetItemId == null && line.itemCode() != null && !line.itemCode().isBlank()) {
                        targetItemId = jdbc.queryForObject("SELECT id FROM item_master WHERE item_code = ? LIMIT 1", Long.class, line.itemCode().trim());
                    }
                    if (targetItemId != null && targetItemId > 0) {
                        jdbc.update("UPDATE item_master SET opening_stock = GREATEST(0, COALESCE(opening_stock, 0) - ?) WHERE id = ?",
                                line.quantity(), targetItemId);
                        String itmCode = line.itemCode();
                        if (itmCode == null || itmCode.isBlank()) {
                            itmCode = jdbc.queryForObject("SELECT item_code FROM item_master WHERE id = ?", String.class, targetItemId);
                        }
                        if (itmCode != null && !itmCode.isBlank()) {
                            jdbc.update("UPDATE inventory_cost_state SET quantity = GREATEST(0, COALESCE(quantity, 0) - ?), last_updated_at = NOW() WHERE item_code = ?",
                                    line.quantity(), itmCode);
                        }
                    }
                } catch (Exception ignored) { java.lang.System.getLogger("org.example").log(java.lang.System.Logger.Level.DEBUG, "Suppressed exception: " + ignored.getMessage(), ignored); }
            }
        }

        // Post balancing Journal Voucher via General Ledger
        String dnReason = dto.reasonCode() != null ? dto.reasonCode() : "PURCHASE_RETURN";
        Long glId = postDebitNoteJournal(headerId, noteNo, date, supplierId.intValue(), supplierName, dnReason,
                taxable, cgst, sgst, igst, roundoff, roundedTotal, user);
        if (glId != null) {
            jdbc.update("UPDATE debit_note_header SET gl_entry_id = ? WHERE id = ?", glId, headerId);
        }

        auditService.log("DEBIT_NOTE", headerId, "CREATED",
                "Created Purchase Debit Note " + noteNo + " for supplier " + supplierName + " (Total: " + roundedTotal + ")");

        return getDebitNote(headerId).orElse(null);
    }

    private String generateNoteNumber(String prefix) {
        String datePart = LocalDate.now().toString().replace("-", "");
        String counterKey = prefix + "|" + datePart;
        Long allocatedValue = jdbc.queryForObject(
                "INSERT INTO reference_counter(counter_key, next_value, updated_at) VALUES (?, 1, ?) " +
                "ON CONFLICT(counter_key) DO UPDATE SET next_value = reference_counter.next_value + 1, updated_at = EXCLUDED.updated_at " +
                "RETURNING next_value",
                Long.class, counterKey, org.example.server.util.BusinessClock.nowUtcText()
        );
        long val = allocatedValue != null ? allocatedValue : 1L;
        String lookupCode = "DN".equalsIgnoreCase(prefix) ? "REF_DEBIT_NOTE" : "REF_CREDIT_NOTE";
        String fallback = prefix.toUpperCase(Locale.ROOT) + "/DD-MM-YYYY/XXXX";
        List<String> fmtRows = jdbc.query(
            "SELECT lookup_value FROM lookup_master WHERE UPPER(TRIM(lookup_type))='REFERENCE FORMAT' AND UPPER(TRIM(lookup_code))=? AND COALESCE(is_active,1)<>0 LIMIT 1",
            (r, i) -> r.getString(1), lookupCode);
        String fmt = (!fmtRows.isEmpty() && fmtRows.getFirst() != null && !fmtRows.getFirst().isBlank()) ? fmtRows.getFirst().trim() : fallback;
        return org.example.shared.ReferenceFormatRules.format(fmt, (int) val, org.example.server.util.BusinessClock.today());
    }

    private boolean isInterState(String partyGstin) {
        if (partyGstin == null || partyGstin.trim().length() < 2) return false;
        try {
            String companyGstin = jdbc.queryForObject(
                    "SELECT setting_value FROM application_setting WHERE setting_key = 'company.gstin' LIMIT 1",
                    String.class
            );
            if (companyGstin != null && companyGstin.trim().length() >= 2) {
                return !companyGstin.trim().substring(0, 2).equalsIgnoreCase(partyGstin.trim().substring(0, 2));
            }
        } catch (Exception ignored) {}
        return false;
    }

    private Long postCreditNoteJournal(Long noteId, String noteNo, LocalDate date, Integer partyId, String party,
                                       String reasonCode, BigDecimal taxable, BigDecimal cgst, BigDecimal sgst, BigDecimal igst,
                                       BigDecimal roundoff, BigDecimal total, String user) {
        try {
            glService.ensurePeriodOpen(date);
            boolean isRebate = reasonCode != null && reasonCode.toUpperCase(Locale.ROOT).contains("REBATE");
            Long arAcc = glService.getAccountIdOrDefault("1040", "Sundry Debtors (Trade Receivables)", "ASSET");
            Long roundoffAcc = glService.getAccountIdOrDefault("5050", "Discounts & Rounding Adjustments", "EXPENSE");
            List<GeneralLedgerDtos.CreateLineRequest> lines = new ArrayList<>();

            if (isRebate) {
                lines.add(new GeneralLedgerDtos.CreateLineRequest(roundoffAcc, "5050", total, BigDecimal.ZERO, null, "Commercial Rebate Credit Note: " + noteNo, null));
                lines.add(new GeneralLedgerDtos.CreateLineRequest(arAcc, "1040", BigDecimal.ZERO, total, partyId, "Party adjustment: " + party, null));
            } else {
                Long salesReturnAcc = glService.getAccountIdOrDefault("4100", "Sales Returns & Allowances", "REVENUE");
                Long cgstAcc = glService.getAccountIdOrDefault("2081", "Output CGST Payable", "LIABILITY");
                Long sgstAcc = glService.getAccountIdOrDefault("2082", "Output SGST Payable", "LIABILITY");
                Long igstAcc = glService.getAccountIdOrDefault("2083", "Output IGST Payable", "LIABILITY");

                lines.add(new GeneralLedgerDtos.CreateLineRequest(salesReturnAcc, "4100", taxable, BigDecimal.ZERO, null, "Credit Note: " + noteNo, null));
                if (igst != null && igst.compareTo(BigDecimal.ZERO) > 0) {
                    lines.add(new GeneralLedgerDtos.CreateLineRequest(igstAcc, "2083", igst, BigDecimal.ZERO, null, "IGST reversal: " + noteNo, null));
                }
                if (cgst != null && cgst.compareTo(BigDecimal.ZERO) > 0) {
                    lines.add(new GeneralLedgerDtos.CreateLineRequest(cgstAcc, "2081", cgst, BigDecimal.ZERO, null, "CGST reversal: " + noteNo, null));
                }
                if (sgst != null && sgst.compareTo(BigDecimal.ZERO) > 0) {
                    lines.add(new GeneralLedgerDtos.CreateLineRequest(sgstAcc, "2082", sgst, BigDecimal.ZERO, null, "SGST reversal: " + noteNo, null));
                }
                lines.add(new GeneralLedgerDtos.CreateLineRequest(arAcc, "1040", BigDecimal.ZERO, total, partyId, "Party adjustment: " + party, null));

                BigDecimal tax = (igst != null ? igst : BigDecimal.ZERO)
                        .add(cgst != null ? cgst : BigDecimal.ZERO)
                        .add(sgst != null ? sgst : BigDecimal.ZERO);
                BigDecimal debits = taxable.add(tax).setScale(2, RoundingMode.HALF_UP);
                BigDecimal credits = total.setScale(2, RoundingMode.HALF_UP);
                BigDecimal diff = debits.subtract(credits);
                if (diff.compareTo(BigDecimal.ZERO) > 0) {
                    lines.add(new GeneralLedgerDtos.CreateLineRequest(roundoffAcc, "5050", BigDecimal.ZERO, diff, null, "Round off: " + noteNo, null));
                } else if (diff.compareTo(BigDecimal.ZERO) < 0) {
                    lines.add(new GeneralLedgerDtos.CreateLineRequest(roundoffAcc, "5050", diff.abs(), BigDecimal.ZERO, null, "Round off: " + noteNo, null));
                }
            }

            GeneralLedgerDtos.CreateJournalRequest req = new GeneralLedgerDtos.CreateJournalRequest(
                    date, "CREDIT_NOTE", "CREDIT_NOTE", noteId, noteNo,
                    "Sales Credit Note for " + party, user, lines
            );
            GeneralLedgerDtos.JournalEntryDto created = glService.postJournalEntry(req);
            return created != null ? created.id() : null;
        } catch (IllegalStateException locked) {
            throw locked;
        } catch (Exception e) {
            java.lang.System.getLogger("org.example").log(java.lang.System.Logger.Level.WARNING, "Failed to post credit note journal for " + noteNo + ": " + e.getMessage(), e);
            return null;
        }
    }

    private Long postDebitNoteJournal(Long noteId, String noteNo, LocalDate date, Integer supplierId, String supplier,
                                      String reasonCode, BigDecimal taxable, BigDecimal cgst, BigDecimal sgst, BigDecimal igst,
                                      BigDecimal roundoff, BigDecimal total, String user) {
        try {
            glService.ensurePeriodOpen(date);
            boolean isRebate = reasonCode != null && reasonCode.toUpperCase(Locale.ROOT).contains("REBATE");
            Long apAcc = glService.getAccountIdOrDefault("2010", "Sundry Creditors (Trade Payables)", "LIABILITY");
            Long roundoffAcc = glService.getAccountIdOrDefault("4090", "Discounts & Rounding Adjustments", "REVENUE");
            List<GeneralLedgerDtos.CreateLineRequest> lines = new ArrayList<>();

            if (isRebate) {
                lines.add(new GeneralLedgerDtos.CreateLineRequest(apAcc, "2010", total, BigDecimal.ZERO, supplierId, "Supplier Rebate adjustment: " + supplier, null));
                lines.add(new GeneralLedgerDtos.CreateLineRequest(roundoffAcc, "4090", BigDecimal.ZERO, total, null, "Supplier Rebate Debit Note: " + noteNo, null));
            } else {
                Long purchaseReturnAcc = glService.getAccountIdOrDefault("5100", "Purchase Returns & Outward", "EXPENSE");
                Long cgstAcc = glService.getAccountIdOrDefault("1081", "Input CGST Pool", "ASSET");
                Long sgstAcc = glService.getAccountIdOrDefault("1082", "Input SGST Pool", "ASSET");
                Long igstAcc = glService.getAccountIdOrDefault("1083", "Input IGST Pool", "ASSET");

                lines.add(new GeneralLedgerDtos.CreateLineRequest(apAcc, "2010", total, BigDecimal.ZERO, supplierId, "Supplier adjustment: " + supplier, null));
                lines.add(new GeneralLedgerDtos.CreateLineRequest(purchaseReturnAcc, "5100", BigDecimal.ZERO, taxable, null, "Debit Note: " + noteNo, null));
                if (igst != null && igst.compareTo(BigDecimal.ZERO) > 0) {
                    lines.add(new GeneralLedgerDtos.CreateLineRequest(igstAcc, "1083", BigDecimal.ZERO, igst, null, "Input IGST reversal: " + noteNo, null));
                }
                if (cgst != null && cgst.compareTo(BigDecimal.ZERO) > 0) {
                    lines.add(new GeneralLedgerDtos.CreateLineRequest(cgstAcc, "1081", BigDecimal.ZERO, cgst, null, "Input CGST reversal: " + noteNo, null));
                }
                if (sgst != null && sgst.compareTo(BigDecimal.ZERO) > 0) {
                    lines.add(new GeneralLedgerDtos.CreateLineRequest(sgstAcc, "1082", BigDecimal.ZERO, sgst, null, "Input SGST reversal: " + noteNo, null));
                }

                BigDecimal tax = (igst != null ? igst : BigDecimal.ZERO)
                        .add(cgst != null ? cgst : BigDecimal.ZERO)
                        .add(sgst != null ? sgst : BigDecimal.ZERO);
                BigDecimal debits = total.setScale(2, RoundingMode.HALF_UP);
                BigDecimal credits = taxable.add(tax).setScale(2, RoundingMode.HALF_UP);
                BigDecimal diff = debits.subtract(credits);
                if (diff.compareTo(BigDecimal.ZERO) > 0) {
                    lines.add(new GeneralLedgerDtos.CreateLineRequest(roundoffAcc, "4090", BigDecimal.ZERO, diff, null, "Round off: " + noteNo, null));
                } else if (diff.compareTo(BigDecimal.ZERO) < 0) {
                    lines.add(new GeneralLedgerDtos.CreateLineRequest(roundoffAcc, "4090", diff.abs(), BigDecimal.ZERO, null, "Round off: " + noteNo, null));
                }
            }

            GeneralLedgerDtos.CreateJournalRequest req = new GeneralLedgerDtos.CreateJournalRequest(
                    date, "DEBIT_NOTE", "DEBIT_NOTE", noteId, noteNo,
                    "Purchase Debit Note adjustment for " + supplier, user, lines
            );
            GeneralLedgerDtos.JournalEntryDto created = glService.postJournalEntry(req);
            return created != null ? created.id() : null;
        } catch (IllegalStateException locked) {
            throw locked;
        } catch (Exception e) {
            java.lang.System.getLogger("org.example").log(java.lang.System.Logger.Level.WARNING, "Failed to post debit note journal for " + noteNo + ": " + e.getMessage(), e);
            return null;
        }
    }

    private Long getAccountIdOrDefault(String code, String name, String type) {
        if (glService != null) {
            return glService.getAccountIdOrDefault(code, name, type);
        }
        try {
            Long id = jdbc.queryForObject("SELECT id FROM chart_of_accounts WHERE account_code = ? LIMIT 1", Long.class, code);
            if (id != null) return id;
        } catch (org.springframework.dao.EmptyResultDataAccessException | jakarta.persistence.NoResultException ignored) {
            // Auto-provision missing COA account below when query returns no rows.
        }
        return jdbc.queryForObject("""
            INSERT INTO chart_of_accounts(account_code, account_name, account_type, account_subtype, is_active, is_system)
            VALUES (?, ?, ?, 'ADJUSTMENT', TRUE, TRUE)
            ON CONFLICT (account_code) DO UPDATE SET account_name = EXCLUDED.account_name
            RETURNING id
            """, Long.class, code, name, type);
    }

    private LocalDate parseDate(Object obj) {
        if (obj == null) return null;
        if (obj instanceof LocalDate ld) return ld;
        String str = obj.toString().trim();
        if (str.length() >= 10) {
            try { return LocalDate.parse(str.substring(0, 10)); } catch (Exception ignored) { java.lang.System.getLogger("org.example").log(java.lang.System.Logger.Level.DEBUG, "Suppressed exception: " + ignored.getMessage(), ignored); }
        }
        return null;
    }

    private Long toLongOrNull(Object val) {
        if (val == null) return null;
        if (val instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(val).trim());
        } catch (Exception e) {
            return null;
        }
    }

    public boolean cancelCreditNote(Long id, String user) {
        var noteOpt = getCreditNote(id);
        if (noteOpt.isEmpty()) return false;
        var note = noteOpt.get();
        if ("CANCELLED".equalsIgnoreCase(note.status())) return false;

        glService.ensurePeriodOpen(note.noteDate());
        glService.ensurePeriodOpen(LocalDate.now());

        int rows = jdbc.update("UPDATE credit_note_header SET status = 'CANCELLED' WHERE id = ?", id);
        if (rows > 0) {
            if (Boolean.TRUE.equals(note.stockReturned()) && note.lines() != null) {
                for (var line : note.lines()) {
                    if (line.itemId() != null && line.itemId() > 0) {
                        try {
                            jdbc.update("UPDATE item_master SET opening_stock = GREATEST(0, COALESCE(opening_stock, 0) - ?) WHERE id = ?",
                                    line.quantity(), line.itemId());
                        } catch (Exception e) {
                            auditService.log("CREDIT_NOTE", id, "WARN", "Could not reverse stock for item " + line.itemId() + ": " + e.getMessage());
                        }
                    }
                }
            }
            if (note.glEntryId() != null) {
                glService.reverseJournalEntry(note.glEntryId(), new GeneralLedgerDtos.ReverseJournalRequest(
                        LocalDate.now(), "Credit Note Cancellation: " + note.creditNoteNo(), user));
            } else {
                Long arAcc = glService.getAccountIdOrDefault("1040", "Sundry Debtors (Trade Receivables)", "ASSET");
                Long salesReturnAcc = glService.getAccountIdOrDefault("4100", "Sales Returns & Allowances", "REVENUE");
                Long cgstAcc = glService.getAccountIdOrDefault("2081", "Output CGST Payable", "LIABILITY");
                Long sgstAcc = glService.getAccountIdOrDefault("2082", "Output SGST Payable", "LIABILITY");
                Long igstAcc = glService.getAccountIdOrDefault("2083", "Output IGST Payable", "LIABILITY");
                Long roundAcc = glService.getAccountIdOrDefault("5050", "Discounts & Rounding Adjustments", "EXPENSE");
                Integer pId = note.partyId() != null ? note.partyId().intValue() : null;

                List<GeneralLedgerDtos.CreateLineRequest> lines = new ArrayList<>();
                lines.add(new GeneralLedgerDtos.CreateLineRequest(arAcc, "1040", note.totalAmount(), BigDecimal.ZERO, pId, "Credit Note Cancellation: " + note.creditNoteNo(), null));
                lines.add(new GeneralLedgerDtos.CreateLineRequest(salesReturnAcc, "4100", BigDecimal.ZERO, note.taxableAmount(), null, "Reversal of Sales Return: " + note.creditNoteNo(), null));
                if (note.igstAmount().compareTo(BigDecimal.ZERO) > 0) lines.add(new GeneralLedgerDtos.CreateLineRequest(igstAcc, "2083", BigDecimal.ZERO, note.igstAmount(), null, "IGST reversal cancel", null));
                if (note.cgstAmount().compareTo(BigDecimal.ZERO) > 0) lines.add(new GeneralLedgerDtos.CreateLineRequest(cgstAcc, "2081", BigDecimal.ZERO, note.cgstAmount(), null, "CGST reversal cancel", null));
                if (note.sgstAmount().compareTo(BigDecimal.ZERO) > 0) lines.add(new GeneralLedgerDtos.CreateLineRequest(sgstAcc, "2082", BigDecimal.ZERO, note.sgstAmount(), null, "SGST reversal cancel", null));
                BigDecimal tax = note.cgstAmount().add(note.sgstAmount()).add(note.igstAmount());
                BigDecimal diff = note.totalAmount().subtract(note.taxableAmount().add(tax)).setScale(2, RoundingMode.HALF_UP);
                if (diff.compareTo(BigDecimal.ZERO) > 0) {
                    lines.add(new GeneralLedgerDtos.CreateLineRequest(roundAcc, "5050", BigDecimal.ZERO, diff, null, "Round off cancel: " + note.creditNoteNo(), null));
                } else if (diff.compareTo(BigDecimal.ZERO) < 0) {
                    lines.add(new GeneralLedgerDtos.CreateLineRequest(roundAcc, "5050", diff.abs(), BigDecimal.ZERO, null, "Round off cancel: " + note.creditNoteNo(), null));
                }
                glService.postJournalEntry(new GeneralLedgerDtos.CreateJournalRequest(
                        LocalDate.now(), "CREDIT_NOTE_REV", "CREDIT_NOTE", id, "REV-" + note.creditNoteNo(),
                        "Cancellation reversal for Credit Note " + note.creditNoteNo(), user, lines));
            }

            auditService.log("CREDIT_NOTE", id, "CANCELLED",
                    "Cancelled Credit Note " + id + " (" + note.creditNoteNo() + ") by " + (user != null ? user : "SYSTEM"));
            return true;
        }
        return false;
    }

    public boolean cancelDebitNote(Long id, String user) {
        var noteOpt = getDebitNote(id);
        if (noteOpt.isEmpty()) return false;
        var note = noteOpt.get();
        if ("CANCELLED".equalsIgnoreCase(note.status())) return false;

        glService.ensurePeriodOpen(note.noteDate());
        glService.ensurePeriodOpen(LocalDate.now());

        int rows = jdbc.update("UPDATE debit_note_header SET status = 'CANCELLED' WHERE id = ?", id);
        if (rows > 0) {
            if (Boolean.TRUE.equals(note.stockReturned()) && note.lines() != null) {
                for (var line : note.lines()) {
                    if (line.itemId() != null && line.itemId() > 0) {
                        try {
                            jdbc.update("UPDATE item_master SET opening_stock = COALESCE(opening_stock, 0) + ? WHERE id = ?",
                                    line.quantity(), line.itemId());
                        } catch (Exception e) {
                            auditService.log("DEBIT_NOTE", id, "WARN", "Could not restore stock for item " + line.itemId() + ": " + e.getMessage());
                        }
                    }
                }
            }
            if (note.glEntryId() != null) {
                glService.reverseJournalEntry(note.glEntryId(), new GeneralLedgerDtos.ReverseJournalRequest(
                        LocalDate.now(), "Debit Note Cancellation: " + note.debitNoteNo(), user));
            } else {
                Long apAcc = glService.getAccountIdOrDefault("2010", "Sundry Creditors (Trade Payables)", "LIABILITY");
                Long purchaseReturnAcc = glService.getAccountIdOrDefault("5100", "Purchase Returns & Outward", "EXPENSE");
                Long cgstAcc = glService.getAccountIdOrDefault("1081", "Input CGST Pool", "ASSET");
                Long sgstAcc = glService.getAccountIdOrDefault("1082", "Input SGST Pool", "ASSET");
                Long igstAcc = glService.getAccountIdOrDefault("1083", "Input IGST Pool", "ASSET");
                Long roundAcc = glService.getAccountIdOrDefault("4090", "Discounts & Rounding Adjustments", "REVENUE");
                Integer supId = note.supplierId() != null ? note.supplierId().intValue() : null;

                List<GeneralLedgerDtos.CreateLineRequest> lines = new ArrayList<>();
                lines.add(new GeneralLedgerDtos.CreateLineRequest(purchaseReturnAcc, "5100", note.taxableAmount(), BigDecimal.ZERO, null, "Debit Note Cancellation: " + note.debitNoteNo(), null));
                if (note.igstAmount().compareTo(BigDecimal.ZERO) > 0) lines.add(new GeneralLedgerDtos.CreateLineRequest(igstAcc, "1083", note.igstAmount(), BigDecimal.ZERO, null, "Input IGST cancel", null));
                if (note.cgstAmount().compareTo(BigDecimal.ZERO) > 0) lines.add(new GeneralLedgerDtos.CreateLineRequest(cgstAcc, "1081", note.cgstAmount(), BigDecimal.ZERO, null, "Input CGST cancel", null));
                if (note.sgstAmount().compareTo(BigDecimal.ZERO) > 0) lines.add(new GeneralLedgerDtos.CreateLineRequest(sgstAcc, "1082", note.sgstAmount(), BigDecimal.ZERO, null, "Input SGST cancel", null));
                lines.add(new GeneralLedgerDtos.CreateLineRequest(apAcc, "2010", BigDecimal.ZERO, note.totalAmount(), supId, "Reversal of Supplier Adjustment: " + note.supplierName(), null));
                BigDecimal tax = note.cgstAmount().add(note.sgstAmount()).add(note.igstAmount());
                BigDecimal diff = note.totalAmount().subtract(note.taxableAmount().add(tax)).setScale(2, RoundingMode.HALF_UP);
                if (diff.compareTo(BigDecimal.ZERO) > 0) {
                    lines.add(new GeneralLedgerDtos.CreateLineRequest(roundAcc, "4090", diff, BigDecimal.ZERO, null, "Round off cancel: " + note.debitNoteNo(), null));
                } else if (diff.compareTo(BigDecimal.ZERO) < 0) {
                    lines.add(new GeneralLedgerDtos.CreateLineRequest(roundAcc, "4090", BigDecimal.ZERO, diff.abs(), null, "Round off cancel: " + note.debitNoteNo(), null));
                }
                glService.postJournalEntry(new GeneralLedgerDtos.CreateJournalRequest(
                        LocalDate.now(), "DEBIT_NOTE_REV", "DEBIT_NOTE", id, "REV-" + note.debitNoteNo(),
                        "Cancellation reversal for Debit Note " + note.debitNoteNo(), user, lines));
            }

            auditService.log("DEBIT_NOTE", id, "CANCELLED",
                    "Cancelled Debit Note " + id + " (" + note.debitNoteNo() + ") by " + (user != null ? user : "SYSTEM"));
            return true;
        }
        return false;
    }

    public boolean updateCreditNote(Long id, String notes, String reasonCode, String user) {
        var noteOpt = getCreditNote(id);
        if (noteOpt.isEmpty()) return false;
        var note = noteOpt.get();
        if ("CANCELLED".equalsIgnoreCase(note.status()) || "DELETED".equalsIgnoreCase(note.status())) return false;
        jdbc.update("UPDATE credit_note_header SET notes = ?, reason_code = COALESCE(?, reason_code) WHERE id = ?",
                notes, reasonCode, id);
        auditService.log("CREDIT_NOTE", id, "UPDATED", "Updated notes/reason for Credit Note " + note.creditNoteNo() + " by " + (user != null ? user : "SYSTEM"));
        return true;
    }

    public boolean updateDebitNote(Long id, String notes, String reasonCode, String user) {
        var noteOpt = getDebitNote(id);
        if (noteOpt.isEmpty()) return false;
        var note = noteOpt.get();
        if ("CANCELLED".equalsIgnoreCase(note.status()) || "DELETED".equalsIgnoreCase(note.status())) return false;
        jdbc.update("UPDATE debit_note_header SET notes = ?, reason_code = COALESCE(?, reason_code) WHERE id = ?",
                notes, reasonCode, id);
        auditService.log("DEBIT_NOTE", id, "UPDATED", "Updated notes/reason for Debit Note " + note.debitNoteNo() + " by " + (user != null ? user : "SYSTEM"));
        return true;
    }

    public boolean deleteCreditNote(Long id, String user) {
        var noteOpt = getCreditNote(id);
        if (noteOpt.isEmpty()) return false;
        var note = noteOpt.get();
        if (!"CANCELLED".equalsIgnoreCase(note.status()) && !"DELETED".equalsIgnoreCase(note.status())) {
            cancelCreditNote(id, user);
        }
        jdbc.update("UPDATE credit_note_header SET status = 'DELETED' WHERE id = ?", id);
        auditService.log("CREDIT_NOTE", id, "DELETED", "Deleted Credit Note " + note.creditNoteNo() + " by " + (user != null ? user : "SYSTEM"));
        return true;
    }

    public boolean deleteDebitNote(Long id, String user) {
        var noteOpt = getDebitNote(id);
        if (noteOpt.isEmpty()) return false;
        var note = noteOpt.get();
        if (!"CANCELLED".equalsIgnoreCase(note.status()) && !"DELETED".equalsIgnoreCase(note.status())) {
            cancelDebitNote(id, user);
        }
        jdbc.update("UPDATE debit_note_header SET status = 'DELETED' WHERE id = ?", id);
        auditService.log("DEBIT_NOTE", id, "DELETED", "Deleted Debit Note " + note.debitNoteNo() + " by " + (user != null ? user : "SYSTEM"));
        return true;
    }
}
