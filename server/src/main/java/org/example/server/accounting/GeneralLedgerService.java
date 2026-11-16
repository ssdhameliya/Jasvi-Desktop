package org.example.server.accounting;

import org.example.server.persistence.entity.ChartOfAccountsEntity;
import org.example.server.persistence.entity.JournalEntryEntity;
import org.example.server.persistence.entity.JournalLineEntity;
import org.example.server.persistence.repository.ChartOfAccountsRepository;
import org.example.server.persistence.repository.JournalEntryRepository;
import org.example.server.persistence.repository.JournalLineRepository;
import org.example.server.persistence.JpaNativeRepository;
import org.example.shared.ReferenceFormatRules;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Transactional
public class GeneralLedgerService {

    private final ChartOfAccountsRepository coaRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final JournalLineRepository journalLineRepository;
    private final JpaNativeRepository jdbc;
    private final AtomicLong sequenceGen = new AtomicLong(System.currentTimeMillis() % 100000);

    public GeneralLedgerService(ChartOfAccountsRepository coaRepository,
                                JournalEntryRepository journalEntryRepository,
                                JournalLineRepository journalLineRepository,
                                JpaNativeRepository jdbc) {
        this.coaRepository = coaRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.journalLineRepository = journalLineRepository;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<GeneralLedgerDtos.AccountDto> listAccounts() {
        return coaRepository.findAllByIsActiveTrueOrderByAccountCodeAsc().stream()
                .map(this::toAccountDto)
                .toList();
    }

    public GeneralLedgerDtos.AccountDto createAccount(GeneralLedgerDtos.AccountDto dto) {
        ChartOfAccountsEntity entity = new ChartOfAccountsEntity();
        entity.setAccountCode(dto.accountCode());
        entity.setAccountName(dto.accountName());
        entity.setAccountType(dto.accountType());
        entity.setAccountSubtype(dto.accountSubtype());
        entity.setParentId(dto.parentId());
        entity.setCurrency(dto.currency() != null ? dto.currency() : "INR");
        entity.setOpeningBalance(dto.openingBalance() != null ? dto.openingBalance() : BigDecimal.ZERO);
        entity.setCurrentBalance(entity.getOpeningBalance());
        entity.setIsActive(dto.isActive() != null ? dto.isActive() : true);
        entity.setIsSystem(false);
        ChartOfAccountsEntity saved = coaRepository.save(entity);
        return toAccountDto(saved);
    }

    public GeneralLedgerDtos.JournalEntryDto postJournalEntry(GeneralLedgerDtos.CreateJournalRequest req) {
        if (req.lines() == null || req.lines().isEmpty()) {
            throw new IllegalArgumentException("Journal entry must contain at least two lines");
        }

        BigDecimal sumDebit = BigDecimal.ZERO;
        BigDecimal sumCredit = BigDecimal.ZERO;

        for (GeneralLedgerDtos.CreateLineRequest line : req.lines()) {
            BigDecimal d = line.debitAmount() != null ? line.debitAmount() : BigDecimal.ZERO;
            BigDecimal c = line.creditAmount() != null ? line.creditAmount() : BigDecimal.ZERO;
            sumDebit = sumDebit.add(d);
            sumCredit = sumCredit.add(c);
        }

        if (sumDebit.setScale(2, RoundingMode.HALF_UP).compareTo(sumCredit.setScale(2, RoundingMode.HALF_UP)) != 0) {
            throw new IllegalArgumentException("Unbalanced Journal Entry: Debits (" + sumDebit +
                    ") must equal Credits (" + sumCredit + ")");
        }

        String status = (req.status() != null && !req.status().isBlank()) ? req.status().toUpperCase(Locale.ROOT) : "POSTED";
        JournalEntryEntity entry = new JournalEntryEntity();
        entry.setEntryNumber(nextJournalVoucherNumber(req.entryDate()));
        entry.setEntryDate(req.entryDate() != null ? req.entryDate() : LocalDate.now());
        entry.setEntryType(req.entryType() != null ? req.entryType() : "GENERAL");
        entry.setReferenceType(req.referenceType());
        entry.setReferenceId(req.referenceId());
        entry.setReferenceNo(req.referenceNo());
        entry.setNarration(req.narration());
        entry.setTotalDebit(sumDebit);
        entry.setTotalCredit(sumCredit);
        entry.setStatus(status);
        if ("POSTED".equals(status)) {
            entry.setPostedBy(req.postedBy() != null ? req.postedBy() : "SYSTEM");
            entry.setPostedAt(LocalDateTime.now());
        }

        JournalEntryEntity savedEntry = journalEntryRepository.save(entry);

        int lineNum = 1;
        for (GeneralLedgerDtos.CreateLineRequest lineReq : req.lines()) {
            ChartOfAccountsEntity account;
            if (lineReq.accountId() != null) {
                account = coaRepository.findById(lineReq.accountId())
                        .orElseThrow(() -> new IllegalArgumentException("Account not found: " + lineReq.accountId()));
            } else if (lineReq.accountCode() != null) {
                account = coaRepository.findByAccountCode(lineReq.accountCode())
                        .orElseThrow(() -> new IllegalArgumentException("Account not found for code: " + lineReq.accountCode()));
            } else {
                throw new IllegalArgumentException("Line " + lineNum + " missing account specification");
            }

            BigDecimal debit = lineReq.debitAmount() != null ? lineReq.debitAmount() : BigDecimal.ZERO;
            BigDecimal credit = lineReq.creditAmount() != null ? lineReq.creditAmount() : BigDecimal.ZERO;

            JournalLineEntity line = new JournalLineEntity();
            line.setJournalEntry(savedEntry);
            line.setAccountId(account.getId());
            line.setLineNumber(lineNum++);
            line.setDebitAmount(debit);
            line.setCreditAmount(credit);
            line.setPartyId(lineReq.partyId());
            line.setLineNarration(lineReq.lineNarration());
            line.setCostCenter(lineReq.costCenter());
            journalLineRepository.save(line);

            // Update Account Balance if POSTED
            if ("POSTED".equals(status)) {
                BigDecimal current = account.getCurrentBalance() != null ? account.getCurrentBalance() : BigDecimal.ZERO;
                if ("ASSET".equalsIgnoreCase(account.getAccountType()) || "EXPENSE".equalsIgnoreCase(account.getAccountType())) {
                    account.setCurrentBalance(current.add(debit).subtract(credit));
                } else {
                    account.setCurrentBalance(current.add(credit).subtract(debit));
                }
                coaRepository.save(account);
            }
        }

        return toJournalEntryDto(savedEntry);
    }

    public GeneralLedgerDtos.JournalEntryDto postDraft(Long journalEntryId, String postedBy) {
        JournalEntryEntity entry = journalEntryRepository.findById(journalEntryId)
                .orElseThrow(() -> new IllegalArgumentException("Journal Entry not found: " + journalEntryId));
        if ("POSTED".equals(entry.getStatus())) {
            return toJournalEntryDto(entry);
        }
        entry.setStatus("POSTED");
        entry.setPostedBy(postedBy != null ? postedBy : "SYSTEM");
        entry.setPostedAt(LocalDateTime.now());
        for (JournalLineEntity line : entry.getLines()) {
            ChartOfAccountsEntity account = coaRepository.findById(line.getAccountId())
                    .orElseThrow(() -> new IllegalArgumentException("Account not found: " + line.getAccountId()));
            BigDecimal current = account.getCurrentBalance() != null ? account.getCurrentBalance() : BigDecimal.ZERO;
            if ("ASSET".equalsIgnoreCase(account.getAccountType()) || "EXPENSE".equalsIgnoreCase(account.getAccountType())) {
                account.setCurrentBalance(current.add(line.getDebitAmount()).subtract(line.getCreditAmount()));
            } else {
                account.setCurrentBalance(current.add(line.getCreditAmount()).subtract(line.getDebitAmount()));
            }
            coaRepository.save(account);
        }
        return toJournalEntryDto(journalEntryRepository.save(entry));
    }

    @Transactional(readOnly = true)
    public List<GeneralLedgerDtos.JournalEntryDto> listRecentEntries() {
        return journalEntryRepository.findTop100ByOrderByEntryDateDescIdDesc().stream()
                .map(this::toJournalEntryDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<GeneralLedgerDtos.TrialBalanceItemDto> getTrialBalance() {
        List<ChartOfAccountsEntity> accounts = coaRepository.findAllByIsActiveTrueOrderByAccountCodeAsc();
        List<GeneralLedgerDtos.TrialBalanceItemDto> tb = new ArrayList<>();

        for (ChartOfAccountsEntity acc : accounts) {
            BigDecimal current = acc.getCurrentBalance() != null ? acc.getCurrentBalance() : BigDecimal.ZERO;
            BigDecimal debit = BigDecimal.ZERO;
            BigDecimal credit = BigDecimal.ZERO;

            if ("ASSET".equalsIgnoreCase(acc.getAccountType()) || "EXPENSE".equalsIgnoreCase(acc.getAccountType())) {
                if (current.compareTo(BigDecimal.ZERO) >= 0) {
                    debit = current;
                } else {
                    credit = current.abs();
                }
            } else {
                if (current.compareTo(BigDecimal.ZERO) >= 0) {
                    credit = current;
                } else {
                    debit = current.abs();
                }
            }

            tb.add(new GeneralLedgerDtos.TrialBalanceItemDto(
                    acc.getAccountCode(),
                    acc.getAccountName(),
                    acc.getAccountType(),
                    acc.getOpeningBalance(),
                    debit,
                    credit,
                    current
            ));
        }

        return tb;
    }

    @Transactional(readOnly = true)
    public GeneralLedgerDtos.FinancialStatementDto getFinancialStatements() {
        List<ChartOfAccountsEntity> accounts = coaRepository.findAllByIsActiveTrueOrderByAccountCodeAsc();
        BigDecimal totalRevenue = BigDecimal.ZERO;
        BigDecimal totalExpense = BigDecimal.ZERO;
        BigDecimal totalAssets = BigDecimal.ZERO;
        BigDecimal totalLiabilities = BigDecimal.ZERO;
        BigDecimal totalEquity = BigDecimal.ZERO;

        for (ChartOfAccountsEntity acc : accounts) {
            BigDecimal bal = acc.getCurrentBalance() != null ? acc.getCurrentBalance() : BigDecimal.ZERO;
            switch (acc.getAccountType().toUpperCase()) {
                case "REVENUE" -> totalRevenue = totalRevenue.add(bal);
                case "EXPENSE" -> totalExpense = totalExpense.add(bal);
                case "ASSET" -> totalAssets = totalAssets.add(bal);
                case "LIABILITY" -> totalLiabilities = totalLiabilities.add(bal);
                case "EQUITY" -> totalEquity = totalEquity.add(bal);
            }
        }

        BigDecimal netProfit = totalRevenue.subtract(totalExpense);
        return new GeneralLedgerDtos.FinancialStatementDto(
                totalRevenue, totalExpense, netProfit, totalAssets, totalLiabilities, totalEquity
        );
    }

    public Long postSalesInvoice(Long saleId, String invoiceNo, LocalDate invoiceDate, BigDecimal totalAmount,
                                 BigDecimal taxable, BigDecimal cgst, BigDecimal sgst, BigDecimal igst,
                                 Integer partyId, String customerName, String user) {
        if (saleId == null || totalAmount == null || totalAmount.compareTo(BigDecimal.ZERO) <= 0) return null;
        try {
            cancelJournalEntriesForReference("SALE", saleId, user);
            LocalDate date = invoiceDate != null ? invoiceDate : LocalDate.now();
            Long arAcc = getAccountIdOrDefault("1040", "Sundry Debtors (Trade Receivables)", "ASSET");
            boolean isInter = igst != null && igst.compareTo(BigDecimal.ZERO) > 0;
            Long salesAcc = getAccountIdOrDefault(isInter ? "4020" : "4010",
                    isInter ? "Inter-State Sales Revenue" : "Domestic Sales Revenue", "REVENUE");
            Long cgstAcc = getAccountIdOrDefault("2081", "Output CGST Payable", "LIABILITY");
            Long sgstAcc = getAccountIdOrDefault("2082", "Output SGST Payable", "LIABILITY");
            Long igstAcc = getAccountIdOrDefault("2083", "Output IGST Payable", "LIABILITY");
            Long roundoffAcc = getAccountIdOrDefault("5050", "Discounts & Rounding Adjustments", "EXPENSE");

            BigDecimal tx = taxable != null ? taxable.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            BigDecimal c = cgst != null ? cgst.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            BigDecimal s = sgst != null ? sgst.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            BigDecimal i = igst != null ? igst.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            BigDecimal tot = totalAmount.setScale(2, RoundingMode.HALF_UP);

            List<GeneralLedgerDtos.CreateLineRequest> lines = new ArrayList<>();
            lines.add(new GeneralLedgerDtos.CreateLineRequest(arAcc, "1040", tot, BigDecimal.ZERO, partyId, "Sales Invoice: " + invoiceNo + " - " + (customerName != null ? customerName : "Debtor"), null));
            lines.add(new GeneralLedgerDtos.CreateLineRequest(salesAcc, isInter ? "4020" : "4010", BigDecimal.ZERO, tx, null, "Taxable sales: " + invoiceNo, null));
            if (c.compareTo(BigDecimal.ZERO) > 0) lines.add(new GeneralLedgerDtos.CreateLineRequest(cgstAcc, "2081", BigDecimal.ZERO, c, null, "CGST Output: " + invoiceNo, null));
            if (s.compareTo(BigDecimal.ZERO) > 0) lines.add(new GeneralLedgerDtos.CreateLineRequest(sgstAcc, "2082", BigDecimal.ZERO, s, null, "SGST Output: " + invoiceNo, null));
            if (i.compareTo(BigDecimal.ZERO) > 0) lines.add(new GeneralLedgerDtos.CreateLineRequest(igstAcc, "2083", BigDecimal.ZERO, i, null, "IGST Output: " + invoiceNo, null));

            BigDecimal debits = tot;
            BigDecimal credits = tx.add(c).add(s).add(i).setScale(2, RoundingMode.HALF_UP);
            BigDecimal diff = debits.subtract(credits);
            if (diff.compareTo(BigDecimal.ZERO) > 0) {
                lines.add(new GeneralLedgerDtos.CreateLineRequest(roundoffAcc, "5050", BigDecimal.ZERO, diff, null, "Rounding adjustment: " + invoiceNo, null));
            } else if (diff.compareTo(BigDecimal.ZERO) < 0) {
                lines.add(new GeneralLedgerDtos.CreateLineRequest(roundoffAcc, "5050", diff.abs(), BigDecimal.ZERO, null, "Rounding adjustment: " + invoiceNo, null));
            }

            GeneralLedgerDtos.CreateJournalRequest req = new GeneralLedgerDtos.CreateJournalRequest(
                    date, "SALES", "SALE", saleId, invoiceNo,
                    "Sales Invoice " + invoiceNo + " for " + (customerName != null ? customerName : "Customer"),
                    user != null ? user : "SYSTEM", lines
            );
            GeneralLedgerDtos.JournalEntryDto entry = postJournalEntry(req);
            return entry != null ? entry.id() : null;
        } catch (Exception e) {
            java.lang.System.getLogger("org.example").log(java.lang.System.Logger.Level.WARNING, "Failed to auto-post sales journal: " + e.getMessage(), e);
            return null;
        }
    }

    public Long postPurchaseBill(Long purchaseId, String billNo, LocalDate billDate, BigDecimal totalAmount,
                                 BigDecimal taxable, BigDecimal cgst, BigDecimal sgst, BigDecimal igst,
                                 Integer partyId, String supplierName, String user) {
        if (purchaseId == null || totalAmount == null || totalAmount.compareTo(BigDecimal.ZERO) <= 0) return null;
        try {
            cancelJournalEntriesForReference("PURCHASE", purchaseId, user);
            LocalDate date = billDate != null ? billDate : LocalDate.now();
            Long apAcc = getAccountIdOrDefault("2010", "Sundry Creditors (Trade Payables)", "LIABILITY");
            Long purAcc = getAccountIdOrDefault("5020", "Raw Material Purchases", "EXPENSE");
            Long cgstAcc = getAccountIdOrDefault("1081", "Input CGST Pool", "ASSET");
            Long sgstAcc = getAccountIdOrDefault("1082", "Input SGST Pool", "ASSET");
            Long igstAcc = getAccountIdOrDefault("1083", "Input IGST Pool", "ASSET");
            Long roundoffAcc = getAccountIdOrDefault("4090", "Discounts & Rounding Adjustments", "REVENUE");

            BigDecimal tx = taxable != null ? taxable.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            BigDecimal c = cgst != null ? cgst.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            BigDecimal s = sgst != null ? sgst.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            BigDecimal i = igst != null ? igst.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            BigDecimal tot = totalAmount.setScale(2, RoundingMode.HALF_UP);

            List<GeneralLedgerDtos.CreateLineRequest> lines = new ArrayList<>();
            lines.add(new GeneralLedgerDtos.CreateLineRequest(purAcc, "5020", tx, BigDecimal.ZERO, null, "Taxable purchase: " + billNo, null));
            if (c.compareTo(BigDecimal.ZERO) > 0) lines.add(new GeneralLedgerDtos.CreateLineRequest(cgstAcc, "1081", c, BigDecimal.ZERO, null, "CGST Input: " + billNo, null));
            if (s.compareTo(BigDecimal.ZERO) > 0) lines.add(new GeneralLedgerDtos.CreateLineRequest(sgstAcc, "1082", s, BigDecimal.ZERO, null, "SGST Input: " + billNo, null));
            if (i.compareTo(BigDecimal.ZERO) > 0) lines.add(new GeneralLedgerDtos.CreateLineRequest(igstAcc, "1083", i, BigDecimal.ZERO, null, "IGST Input: " + billNo, null));
            lines.add(new GeneralLedgerDtos.CreateLineRequest(apAcc, "2010", BigDecimal.ZERO, tot, partyId, "Purchase Bill: " + billNo + " - " + (supplierName != null ? supplierName : "Creditor"), null));

            BigDecimal debits = tx.add(c).add(s).add(i).setScale(2, RoundingMode.HALF_UP);
            BigDecimal credits = tot;
            BigDecimal diff = debits.subtract(credits);
            if (diff.compareTo(BigDecimal.ZERO) > 0) {
                lines.add(new GeneralLedgerDtos.CreateLineRequest(roundoffAcc, "4090", BigDecimal.ZERO, diff, null, "Rounding adjustment: " + billNo, null));
            } else if (diff.compareTo(BigDecimal.ZERO) < 0) {
                lines.add(new GeneralLedgerDtos.CreateLineRequest(roundoffAcc, "4090", diff.abs(), BigDecimal.ZERO, null, "Rounding adjustment: " + billNo, null));
            }

            GeneralLedgerDtos.CreateJournalRequest req = new GeneralLedgerDtos.CreateJournalRequest(
                    date, "PURCHASE", "PURCHASE", purchaseId, billNo,
                    "Purchase Bill " + billNo + " from " + (supplierName != null ? supplierName : "Supplier"),
                    user != null ? user : "SYSTEM", lines
            );
            GeneralLedgerDtos.JournalEntryDto entry = postJournalEntry(req);
            return entry != null ? entry.id() : null;
        } catch (Exception e) {
            java.lang.System.getLogger("org.example").log(java.lang.System.Logger.Level.WARNING, "Failed to auto-post purchase journal: " + e.getMessage(), e);
            return null;
        }
    }

    public Long postPaymentReceipt(Long paymentId, String paymentRef, LocalDate paymentDate, BigDecimal amount,
                                   String mode, Integer partyId, String customerName, String referenceNo, String user) {
        if (paymentId == null || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) return null;
        try {
            cancelJournalEntriesForReference("PAYMENT_RECEIPT", paymentId, user);
            LocalDate date = paymentDate != null ? paymentDate : LocalDate.now();
            boolean isCash = mode != null && mode.toUpperCase(Locale.ROOT).contains("CASH");
            Long fundAcc = getAccountIdOrDefault(isCash ? "1010" : "1020",
                    isCash ? "Cash on Hand" : "Bank Current Account", "ASSET");
            Long arAcc = getAccountIdOrDefault("1040", "Sundry Debtors (Trade Receivables)", "ASSET");

            BigDecimal amt = amount.setScale(2, RoundingMode.HALF_UP);
            List<GeneralLedgerDtos.CreateLineRequest> lines = new ArrayList<>();
            lines.add(new GeneralLedgerDtos.CreateLineRequest(fundAcc, isCash ? "1010" : "1020", amt, BigDecimal.ZERO, null, "Payment receipt via " + (mode != null ? mode : "BANK") + " (" + paymentRef + ")", null));
            lines.add(new GeneralLedgerDtos.CreateLineRequest(arAcc, "1040", BigDecimal.ZERO, amt, partyId, "Debtor settlement: " + (customerName != null ? customerName : "Customer") + " (" + referenceNo + ")", null));

            GeneralLedgerDtos.CreateJournalRequest req = new GeneralLedgerDtos.CreateJournalRequest(
                    date, "RECEIPT", "PAYMENT_RECEIPT", paymentId, paymentRef,
                    "Payment Receipt " + paymentRef + " for " + (customerName != null ? customerName : "Customer") + " [" + referenceNo + "]",
                    user != null ? user : "SYSTEM", lines
            );
            GeneralLedgerDtos.JournalEntryDto entry = postJournalEntry(req);
            return entry != null ? entry.id() : null;
        } catch (Exception e) {
            java.lang.System.getLogger("org.example").log(java.lang.System.Logger.Level.WARNING, "Failed to auto-post payment receipt journal: " + e.getMessage(), e);
            return null;
        }
    }

    public Long postPurchasePayment(Long paymentId, String paymentRef, LocalDate paymentDate, BigDecimal amount,
                                    String mode, Integer partyId, String supplierName, String referenceNo, String user) {
        if (paymentId == null || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) return null;
        try {
            cancelJournalEntriesForReference("PURCHASE_PAYMENT", paymentId, user);
            LocalDate date = paymentDate != null ? paymentDate : LocalDate.now();
            boolean isCash = mode != null && mode.toUpperCase(Locale.ROOT).contains("CASH");
            Long fundAcc = getAccountIdOrDefault(isCash ? "1010" : "1020",
                    isCash ? "Cash on Hand" : "Bank Current Account", "ASSET");
            Long apAcc = getAccountIdOrDefault("2010", "Sundry Creditors (Trade Payables)", "LIABILITY");

            BigDecimal amt = amount.setScale(2, RoundingMode.HALF_UP);
            List<GeneralLedgerDtos.CreateLineRequest> lines = new ArrayList<>();
            lines.add(new GeneralLedgerDtos.CreateLineRequest(apAcc, "2010", amt, BigDecimal.ZERO, partyId, "Creditor payment: " + (supplierName != null ? supplierName : "Supplier") + " (" + referenceNo + ")", null));
            lines.add(new GeneralLedgerDtos.CreateLineRequest(fundAcc, isCash ? "1010" : "1020", BigDecimal.ZERO, amt, null, "Payment payout via " + (mode != null ? mode : "BANK") + " (" + paymentRef + ")", null));

            GeneralLedgerDtos.CreateJournalRequest req = new GeneralLedgerDtos.CreateJournalRequest(
                    date, "PAYMENT", "PURCHASE_PAYMENT", paymentId, paymentRef,
                    "Supplier Payment " + paymentRef + " to " + (supplierName != null ? supplierName : "Supplier") + " [" + referenceNo + "]",
                    user != null ? user : "SYSTEM", lines
            );
            GeneralLedgerDtos.JournalEntryDto entry = postJournalEntry(req);
            return entry != null ? entry.id() : null;
        } catch (Exception e) {
            java.lang.System.getLogger("org.example").log(java.lang.System.Logger.Level.WARNING, "Failed to auto-post purchase payment journal: " + e.getMessage(), e);
            return null;
        }
    }

    public void cancelJournalEntriesForReference(String referenceType, Long referenceId, String user) {
        if (referenceType == null || referenceId == null) return;
        try {
            List<Long> entryIds = jdbc.query(
                    "SELECT id FROM journal_entry WHERE reference_type = ? AND reference_id = ? AND status <> 'CANCELLED'",
                    (rs, idx) -> rs.getLong(1), referenceType, referenceId
            );
            for (Long eId : entryIds) {
                List<Map<String, Object>> lines = jdbc.queryForList(
                        "SELECT account_id, debit_amount, credit_amount FROM journal_line WHERE journal_entry_id = ?", eId
                );
                for (Map<String, Object> l : lines) {
                    Long accId = ((Number) l.get("account_id")).longValue();
                    BigDecimal deb = toBigDecimal(l.get("debit_amount"));
                    BigDecimal cred = toBigDecimal(l.get("credit_amount"));
                    Optional<ChartOfAccountsEntity> accOpt = coaRepository.findById(accId);
                    if (accOpt.isPresent()) {
                        ChartOfAccountsEntity acc = accOpt.get();
                        BigDecimal cur = acc.getCurrentBalance() != null ? acc.getCurrentBalance() : BigDecimal.ZERO;
                        if ("ASSET".equalsIgnoreCase(acc.getAccountType()) || "EXPENSE".equalsIgnoreCase(acc.getAccountType())) {
                            acc.setCurrentBalance(cur.subtract(deb).add(cred));
                        } else {
                            acc.setCurrentBalance(cur.subtract(cred).add(deb));
                        }
                        coaRepository.save(acc);
                    }
                }
                jdbc.update("UPDATE journal_entry SET status = 'CANCELLED', updated_by = ?, updated_at = NOW() WHERE id = ?",
                        user != null ? user : "SYSTEM", eId);
            }
        } catch (Exception e) {
            java.lang.System.getLogger("org.example").log(java.lang.System.Logger.Level.WARNING, "Error cancelling journal entry: " + e.getMessage(), e);
        }
    }

    public Long getAccountIdOrDefault(String code, String name, String type) {
        Long id = jdbc.queryForObject("SELECT id FROM chart_of_accounts WHERE account_code = ? LIMIT 1", Long.class, code);
        if (id != null) return id;
        return jdbc.queryForObject("""
            INSERT INTO chart_of_accounts(account_code, account_name, account_type, account_subtype, is_active, is_system)
            VALUES (?, ?, ?, 'STATUTORY', TRUE, TRUE)
            ON CONFLICT (account_code) DO UPDATE SET account_name = EXCLUDED.account_name
            RETURNING id
            """, Long.class, code, name, type);
    }

    private static BigDecimal toBigDecimal(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal b) return b;
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(o.toString()); } catch (Exception ignored) { return BigDecimal.ZERO; }
    }

    private GeneralLedgerDtos.AccountDto toAccountDto(ChartOfAccountsEntity e) {
        return new GeneralLedgerDtos.AccountDto(
                e.getId(), e.getAccountCode(), e.getAccountName(), e.getAccountType(),
                e.getAccountSubtype(), e.getParentId(), e.getCurrency(), e.getOpeningBalance(),
                e.getCurrentBalance(), e.getIsActive(), e.getIsSystem(), e.getRowVersion()
        );
    }

    private GeneralLedgerDtos.JournalEntryDto toJournalEntryDto(JournalEntryEntity e) {
        List<GeneralLedgerDtos.JournalLineDto> lines = e.getLines().stream().map(l -> {
            Optional<ChartOfAccountsEntity> acc = coaRepository.findById(l.getAccountId());
            String code = acc.map(ChartOfAccountsEntity::getAccountCode).orElse("");
            String name = acc.map(ChartOfAccountsEntity::getAccountName).orElse("");
            return new GeneralLedgerDtos.JournalLineDto(
                    l.getId(), l.getAccountId(), code, name, l.getLineNumber(),
                    l.getDebitAmount(), l.getCreditAmount(), l.getPartyId(),
                    l.getLineNarration(), l.getCostCenter()
            );
        }).toList();

        return new GeneralLedgerDtos.JournalEntryDto(
                e.getId(), e.getEntryNumber(), e.getEntryDate(), e.getEntryType(),
                e.getReferenceType(), e.getReferenceId(), e.getReferenceNo(),
                e.getNarration(), e.getTotalDebit(), e.getTotalCredit(),
                e.getStatus(), e.getPostedBy(), e.getPostedAt(), e.getRowVersion(),
                lines
        );
    }

    private String nextJournalVoucherNumber(LocalDate date) {
        LocalDate d = date != null ? date : LocalDate.now();
        String format = "JV-YYYYMMDD-XXXX";
        try {
            String val = jdbc.queryForObject(
                    "SELECT lookup_value FROM lookup_master WHERE UPPER(TRIM(REPLACE(lookup_type, ' ', '_')))='REFERENCE_FORMAT' AND UPPER(lookup_code) IN ('REF_JOURNAL','JOURNAL_VOUCHER','JOURNAL') LIMIT 1",
                    String.class
            );
            if (val != null && !val.isBlank()) format = val.trim();
        } catch (Exception ignored) { java.lang.System.getLogger("org.example").log(java.lang.System.Logger.Level.DEBUG, "Suppressed exception: " + ignored.getMessage(), ignored); }

        try {
            String dated = format
                    .replaceAll("(?i)\\{?YYYY-YYYY\\}?", String.format(Locale.ROOT, "%04d-%04d", ((d.getMonthValue() >= 4) ? d.getYear() : (d.getYear() - 1)), ((d.getMonthValue() >= 4) ? d.getYear() : (d.getYear() - 1)) + 1))
                    .replaceAll("(?i)\\{?YY-YYYY\\}?", String.format(Locale.ROOT, "%02d-%04d", ((d.getMonthValue() >= 4) ? d.getYear() : (d.getYear() - 1)) % 100, ((d.getMonthValue() >= 4) ? d.getYear() : (d.getYear() - 1)) + 1))
                    .replaceAll("(?i)\\{?YYYY-YY\\}?", String.format(Locale.ROOT, "%04d-%02d", ((d.getMonthValue() >= 4) ? d.getYear() : (d.getYear() - 1)), (((d.getMonthValue() >= 4) ? d.getYear() : (d.getYear() - 1)) + 1) % 100))
                    .replaceAll("(?i)\\{?YY-YY\\}?", String.format(Locale.ROOT, "%02d-%02d", ((d.getMonthValue() >= 4) ? d.getYear() : (d.getYear() - 1)) % 100, (((d.getMonthValue() >= 4) ? d.getYear() : (d.getYear() - 1)) + 1) % 100))
                    .replaceAll("(?i)\\{?DD-MM-YYYY\\}?", String.format(Locale.ROOT, "%02d-%02d-%04d", d.getDayOfMonth(), d.getMonthValue(), d.getYear()))
                    .replaceAll("(?i)\\{?DD/MM/YYYY\\}?", String.format(Locale.ROOT, "%02d/%02d/%04d", d.getDayOfMonth(), d.getMonthValue(), d.getYear()))
                    .replaceAll("(?i)\\{?YYYY-MM-DD\\}?", String.format(Locale.ROOT, "%04d-%02d-%02d", d.getYear(), d.getMonthValue(), d.getDayOfMonth()))
                    .replace("YYYY", String.format(Locale.ROOT, "%04d", d.getYear()))
                    .replace("YY", String.format(Locale.ROOT, "%02d", d.getYear() % 100))
                    .replace("MM", String.format(Locale.ROOT, "%02d", d.getMonthValue()))
                    .replace("DD", String.format(Locale.ROOT, "%02d", d.getDayOfMonth()));
            java.util.regex.Matcher seqMatcher = ReferenceFormatRules.sequenceMatcher(dated);
            int width = seqMatcher.end() - seqMatcher.start();
            String prefix = dated.substring(0, seqMatcher.start());
            String suffix = dated.substring(seqMatcher.end());
            String scope = prefix + "\u0000" + suffix;
            String counterKey = "REF_JOURNAL|" + UUID.nameUUIDFromBytes(scope.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String regex = "^" + pgRegex(prefix) + "([0-9]+)" + pgRegex(suffix) + "$";

            Long observedValue = jdbc.queryForObject(
                    "SELECT COALESCE(MAX(((regexp_match(entry_number, ?))[1])::bigint),0)+1 FROM journal_entry WHERE entry_number ~ ?",
                    Long.class, regex, regex
            );
            long observed = observedValue == null ? 1L : Math.max(1L, observedValue);

            Long allocatedValue = jdbc.queryForObject(
                    "INSERT INTO reference_counter(counter_key,next_value,updated_at) VALUES(?,?,NOW()::text) ON CONFLICT(counter_key) DO UPDATE SET next_value=GREATEST(reference_counter.next_value+1,EXCLUDED.next_value),updated_at=EXCLUDED.updated_at RETURNING next_value",
                    Long.class, counterKey, observed
            );
            long allocated = allocatedValue == null ? observed : allocatedValue;
            return prefix + String.format(Locale.ROOT, "%0" + width + "d", allocated) + suffix;
        } catch (Exception e) {
            String datePrefix = d.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            return "JV-" + datePrefix + "-" + String.format("%04d", sequenceGen.incrementAndGet());
        }
    }

    private static String pgRegex(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length() * 2);
        String meta = "\\.^$|?*+()[]{}";
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (meta.indexOf(c) >= 0) out.append('\\');
            out.append(c);
        }
        return out.toString();
    }

    public static boolean isPhysicalStockReturnReason(String reason) {
        if (reason == null || reason.isBlank()) return true;
        String r = reason.trim().toUpperCase(Locale.ROOT);
        if (r.contains("RATE DIFF") || r.contains("PRICE") || r.contains("OVER BILL") || r.contains("TAX RATE")) return false;
        if (r.contains("DISCOUNT") || r.contains("REBATE") || r.contains("INCENTIVE") || r.contains("GOODWILL")) return false;
        if (r.contains("COMMERCIAL")) return false;
        return true;
    }

    public static boolean isCommercialRebateReason(String reason) {
        if (reason == null || reason.isBlank()) return false;
        String r = reason.trim().toUpperCase(Locale.ROOT);
        return r.contains("COMMERCIAL") || r.contains("DISCOUNT") || r.contains("REBATE") || r.contains("INCENTIVE") || r.contains("GOODWILL");
    }

    @Transactional(readOnly = true)
    public GeneralLedgerDtos.PostingFlowResponse getPostingFlow(String docType, String docNo) {
        if (docNo == null || docNo.isBlank()) {
            throw new IllegalArgumentException("Document number or ID is required for Posting Flow lookup.");
        }
        String type = docType == null ? "SALE" : docType.trim().toUpperCase(Locale.ROOT);
        String no = docNo.trim();

        // 1. Resolve SALE / SALES_INVOICE
        if (type.contains("SALE") && !type.contains("RETURN")) {
            return buildSalesPostingFlow(no);
        }
        // 2. Resolve PURCHASE / BILL
        if (type.contains("PURCHASE") && !type.contains("RETURN")) {
            return buildPurchasePostingFlow(no);
        }
        // 3. Resolve SALES_RETURN / CREDIT_NOTE
        if (type.contains("SALE") && type.contains("RETURN") || type.contains("CREDIT_NOTE")) {
            return buildSalesReturnPostingFlow(no);
        }
        // 4. Resolve PURCHASE_RETURN / DEBIT_NOTE
        if (type.contains("PURCHASE") && type.contains("RETURN") || type.contains("DEBIT_NOTE")) {
            return buildPurchaseReturnPostingFlow(no);
        }
        // 5. Resolve PAYMENT / RECEIPT
        if (type.contains("PAYMENT") || type.contains("RECEIPT")) {
            return buildPaymentPostingFlow(no);
        }

        // Fallback: search by document number across all types
        try { return buildSalesPostingFlow(no); } catch (Exception ignored) {}
        try { return buildSalesReturnPostingFlow(no); } catch (Exception ignored) {}
        try { return buildPurchasePostingFlow(no); } catch (Exception ignored) {}
        try { return buildPurchaseReturnPostingFlow(no); } catch (Exception ignored) {}
        return buildPaymentPostingFlow(no);
    }

    private GeneralLedgerDtos.PostingFlowResponse buildSalesPostingFlow(String no) {
        List<Map<String, Object>> headers = jdbc.queryForList("""
            SELECT sh.id, sh.invoice_no, sh.invoice_date,
                   COALESCE(sh.total_amount, 0) AS total_amount,
                   COALESCE(sh.subtotal, 0) AS subtotal,
                   COALESCE(sh.gst_amount, 0) AS gst_amount,
                   COALESCE(sh.discount_amount, 0) AS discount_amount,
                   COALESCE(sh.gst_type, '') AS gst_type,
                   COALESCE(NULLIF(sh.customer_name_snapshot, ''), pm.name, 'Customer') AS party_name,
                   COALESCE(NULLIF(sh.customer_gstin_snapshot, ''), NULLIF(sh.billing_gstin, ''), NULLIF(sh.gstin, ''), pm.gstin, '') AS party_gstin
            FROM sales_header sh
            LEFT JOIN party_master pm ON pm.id = sh.customer_id
            WHERE sh.invoice_no = ? OR sh.id::text = ?
            LIMIT 1
            """, no, no);
        if (headers.isEmpty()) {
            throw new IllegalArgumentException("Sales invoice " + no + " not found.");
        }
        Map<String, Object> h = headers.getFirst();
        Long salesId = ((Number) h.get("id")).longValue();
        String invoiceNo = Objects.toString(h.get("invoice_no"), no);
        LocalDate date = parseDate(h.get("invoice_date"));
        String partyName = Objects.toString(h.get("party_name"), "Customer");
        String gstin = Objects.toString(h.get("party_gstin"), "");
        BigDecimal total = toBigDecimal(h.get("total_amount"));
        BigDecimal taxable = toBigDecimal(h.get("subtotal"));
        BigDecimal totalTax = toBigDecimal(h.get("gst_amount"));
        BigDecimal discount = toBigDecimal(h.get("discount_amount"));
        String gstType = Objects.toString(h.get("gst_type"), "").toUpperCase(Locale.ROOT);
        boolean isInterstate = gstType.contains("IGST") || gstType.contains("INTER");
        BigDecimal cgst = isInterstate ? BigDecimal.ZERO : totalTax.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        BigDecimal sgst = isInterstate ? BigDecimal.ZERO : totalTax.subtract(cgst);
        BigDecimal igst = isInterstate ? totalTax : BigDecimal.ZERO;

        // Voucher Lines
        List<GeneralLedgerDtos.PostingFlowVoucherLine> vLines = fetchVoucherLines("SALE", salesId, invoiceNo);
        String jvNo = fetchVoucherNumber("SALE", salesId, invoiceNo);
        if (vLines.isEmpty()) {
            jvNo = "PROSPECTIVE (ON APPROVAL)";
            vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("1100", "Accounts Receivable", total, BigDecimal.ZERO, "Debit Customer: " + partyName));
            if (discount.compareTo(BigDecimal.ZERO) > 0) {
                vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("5200", "Discount Allowed", discount, BigDecimal.ZERO, "Debit Discount Allowed"));
            }
            vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("4000", "Sales Revenue", BigDecimal.ZERO, taxable, "Credit Revenue: " + invoiceNo));
            if (totalTax.compareTo(BigDecimal.ZERO) > 0) {
                if (igst.compareTo(BigDecimal.ZERO) > 0) {
                    vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("2200", "Output IGST Payable", BigDecimal.ZERO, igst, "Credit Output IGST"));
                } else {
                    vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("2210", "Output CGST Payable", BigDecimal.ZERO, cgst, "Credit Output CGST"));
                    vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("2220", "Output SGST Payable", BigDecimal.ZERO, sgst, "Credit Output SGST"));
                }
            }
            BigDecimal diff = total.add(discount).subtract(taxable.add(totalTax));
            if (diff.compareTo(BigDecimal.ZERO) > 0) {
                vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("8100", "Round Off Adjustment", BigDecimal.ZERO, diff, "Round off credit"));
            } else if (diff.compareTo(BigDecimal.ZERO) < 0) {
                vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("8100", "Round Off Adjustment", diff.abs(), BigDecimal.ZERO, "Round off debit"));
            }
        }

        BigDecimal totDeb = vLines.stream().map(GeneralLedgerDtos.PostingFlowVoucherLine::debitAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totCred = vLines.stream().map(GeneralLedgerDtos.PostingFlowVoucherLine::creditAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

        // Inventory movements
        List<GeneralLedgerDtos.PostingFlowInventoryLine> invLines = new ArrayList<>();
        List<Map<String, Object>> lines = jdbc.queryForList("""
            SELECT sl.item_code, COALESCE(sl.item_description_snapshot, im.description, sl.item_code) AS descr,
                   sl.quantity, COALESCE(im.unit, 'Nos') AS unit, COALESCE(sl.unit_cost_snapshot, 0) AS unit_cost
            FROM sales_line sl
            LEFT JOIN item_master im ON im.item_code = sl.item_code
            WHERE sl.sales_id = ? ORDER BY sl.id ASC
            """, salesId);
        for (Map<String, Object> l : lines) {
            BigDecimal q = toBigDecimal(l.get("quantity"));
            BigDecimal uc = toBigDecimal(l.get("unit_cost"));
            invLines.add(new GeneralLedgerDtos.PostingFlowInventoryLine(
                    Objects.toString(l.get("item_code"), ""),
                    Objects.toString(l.get("descr"), ""),
                    "OUTWARD_SALE (STOCK DECREASED)",
                    q.negate(),
                    Objects.toString(l.get("unit"), "Nos"),
                    uc,
                    q.multiply(uc).setScale(2, RoundingMode.HALF_UP).negate()
            ));
        }

        // GST Impact
        boolean b2b = gstin != null && gstin.trim().length() == 15;
        String gstr1Table = b2b ? "Table 4A - B2B Taxable Invoices" : "Table 7 - B2C Others";
        GeneralLedgerDtos.PostingFlowGstImpact gst = new GeneralLedgerDtos.PostingFlowGstImpact(
                "GSTR-1 & GSTR-3B",
                gstr1Table + " & GSTR-3B Table 3.1(a) (Outward Taxable Supplies)",
                taxable, cgst, sgst, igst, totalTax,
                "CGST Act Sec 9 / IGST Act Sec 5: Statutory output tax levied on outward forward-charge supplies"
        );

        return new GeneralLedgerDtos.PostingFlowResponse(
                "SALES_INVOICE", invoiceNo, date, partyName, gstin, total,
                "STANDARD_SALES_INVOICE",
                "Standard Sales Invoice: Debits Customer AR, Credits Revenue and Output GST liability. Reduces warehouse inventory.",
                jvNo, vLines, totDeb, totCred, totDeb.compareTo(totCred) == 0,
                invLines, gst
        );
    }

    private GeneralLedgerDtos.PostingFlowResponse buildPurchasePostingFlow(String no) {
        List<Map<String, Object>> headers = jdbc.queryForList("""
            SELECT ph.id, ph.invoice_no, ph.invoice_date,
                   COALESCE(ph.total_amount, 0) AS total_amount,
                   COALESCE(ph.subtotal, 0) AS subtotal,
                   COALESCE(ph.gst_amount, 0) AS gst_amount,
                   COALESCE(ph.discount_amount, 0) AS discount_amount,
                   COALESCE(ph.gst_treatment, ph.gst_type, '') AS gst_type,
                   COALESCE(NULLIF(ph.supplier_name_snapshot, ''), pm.name, 'Supplier') AS party_name,
                   COALESCE(NULLIF(ph.supplier_gstin_snapshot, ''), NULLIF(ph.billing_gstin, ''), pm.gstin, '') AS party_gstin
            FROM purchase_header ph
            LEFT JOIN party_master pm ON pm.id = ph.supplier_id
            WHERE ph.invoice_no = ? OR ph.id::text = ?
            LIMIT 1
            """, no, no);
        if (headers.isEmpty()) {
            throw new IllegalArgumentException("Purchase bill " + no + " not found.");
        }
        Map<String, Object> h = headers.getFirst();
        Long purchaseId = ((Number) h.get("id")).longValue();
        String invoiceNo = Objects.toString(h.get("invoice_no"), no);
        LocalDate date = parseDate(h.get("invoice_date"));
        String partyName = Objects.toString(h.get("party_name"), "Supplier");
        String gstin = Objects.toString(h.get("party_gstin"), "");
        BigDecimal total = toBigDecimal(h.get("total_amount"));
        BigDecimal taxable = toBigDecimal(h.get("subtotal"));
        BigDecimal totalTax = toBigDecimal(h.get("gst_amount"));
        BigDecimal discount = toBigDecimal(h.get("discount_amount"));
        String gstType = Objects.toString(h.get("gst_type"), "").toUpperCase(Locale.ROOT);
        boolean isInterstate = gstType.contains("IGST") || gstType.contains("INTER");
        BigDecimal cgst = isInterstate ? BigDecimal.ZERO : totalTax.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        BigDecimal sgst = isInterstate ? BigDecimal.ZERO : totalTax.subtract(cgst);
        BigDecimal igst = isInterstate ? totalTax : BigDecimal.ZERO;

        // Voucher Lines
        List<GeneralLedgerDtos.PostingFlowVoucherLine> vLines = fetchVoucherLines("PURCHASE", purchaseId, invoiceNo);
        String jvNo = fetchVoucherNumber("PURCHASE", purchaseId, invoiceNo);
        if (vLines.isEmpty()) {
            jvNo = "PROSPECTIVE (ON APPROVAL)";
            vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("5000", "Purchases / Cost of Goods", taxable, BigDecimal.ZERO, "Debit Purchases: " + invoiceNo));
            if (totalTax.compareTo(BigDecimal.ZERO) > 0) {
                if (igst.compareTo(BigDecimal.ZERO) > 0) {
                    vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("1200", "Input IGST Credit", igst, BigDecimal.ZERO, "Debit Input IGST"));
                } else {
                    vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("1210", "Input CGST Credit", cgst, BigDecimal.ZERO, "Debit Input CGST"));
                    vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("1220", "Input SGST Credit", sgst, BigDecimal.ZERO, "Debit Input SGST"));
                }
            }
            vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("2100", "Accounts Payable", BigDecimal.ZERO, total, "Credit Supplier: " + partyName));
            if (discount.compareTo(BigDecimal.ZERO) > 0) {
                vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("5100", "Discount Received", BigDecimal.ZERO, discount, "Credit Discount Received"));
            }
            BigDecimal diff = taxable.add(totalTax).subtract(total.add(discount));
            if (diff.compareTo(BigDecimal.ZERO) > 0) {
                vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("8100", "Round Off Adjustment", BigDecimal.ZERO, diff, "Round off credit"));
            } else if (diff.compareTo(BigDecimal.ZERO) < 0) {
                vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("8100", "Round Off Adjustment", diff.abs(), BigDecimal.ZERO, "Round off debit"));
            }
        }

        BigDecimal totDeb = vLines.stream().map(GeneralLedgerDtos.PostingFlowVoucherLine::debitAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totCred = vLines.stream().map(GeneralLedgerDtos.PostingFlowVoucherLine::creditAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

        // Inventory movements
        List<GeneralLedgerDtos.PostingFlowInventoryLine> invLines = new ArrayList<>();
        List<Map<String, Object>> lines = jdbc.queryForList("""
            SELECT pl.item_code, COALESCE(pl.item_description_snapshot, im.description, pl.item_code) AS descr,
                   pl.quantity, COALESCE(im.unit, 'Nos') AS unit, COALESCE(pl.rate, 0) AS unit_cost
            FROM purchase_line pl
            LEFT JOIN item_master im ON im.item_code = pl.item_code
            WHERE pl.purchase_id = ? ORDER BY pl.id ASC
            """, purchaseId);
        for (Map<String, Object> l : lines) {
            BigDecimal q = toBigDecimal(l.get("quantity"));
            BigDecimal uc = toBigDecimal(l.get("unit_cost"));
            invLines.add(new GeneralLedgerDtos.PostingFlowInventoryLine(
                    Objects.toString(l.get("item_code"), ""),
                    Objects.toString(l.get("descr"), ""),
                    "INWARD_PURCHASE (STOCK INCREASED)",
                    q,
                    Objects.toString(l.get("unit"), "Nos"),
                    uc,
                    q.multiply(uc).setScale(2, RoundingMode.HALF_UP)
            ));
        }

        // GST Impact
        GeneralLedgerDtos.PostingFlowGstImpact gst = new GeneralLedgerDtos.PostingFlowGstImpact(
                "GSTR-3B & GSTR-2B",
                "Table 4(A)(5) - All Other ITC (Auto-drafted from GSTR-2B)",
                taxable, cgst, sgst, igst, totalTax,
                "CGST Act Sec 16: Statutory ITC claim eligible upon receipt of tax invoice and goods/services"
        );

        return new GeneralLedgerDtos.PostingFlowResponse(
                "PURCHASE_BILL", invoiceNo, date, partyName, gstin, total,
                "STANDARD_PURCHASE_BILL",
                "Standard Purchase Bill: Debits Purchase Expenses and Input GST Asset, Credits Supplier AP. Increases warehouse inventory.",
                jvNo, vLines, totDeb, totCred, totDeb.compareTo(totCred) == 0,
                invLines, gst
        );
    }

    private GeneralLedgerDtos.PostingFlowResponse buildSalesReturnPostingFlow(String no) {
        // Query return_register first
        List<Map<String, Object>> retRows = jdbc.queryForList("""
            SELECT r.id, r.return_no, r.return_date, r.invoice_no, r.party_id, r.item_code,
                   r.quantity, r.amount, r.reason, r.status, pm.name AS party_name, COALESCE(pm.gstin, '') AS party_gstin
            FROM return_register r
            LEFT JOIN party_master pm ON pm.id = r.party_id
            WHERE r.return_no = ?
            ORDER BY r.id ASC
            """, no);

        String retNo = no;
        LocalDate date = LocalDate.now();
        String partyName = "Customer";
        String gstin = "";
        BigDecimal total = BigDecimal.ZERO;
        String reason = "";
        List<GeneralLedgerDtos.PostingFlowInventoryLine> invLines = new ArrayList<>();

        if (!retRows.isEmpty()) {
            Map<String, Object> first = retRows.getFirst();
            retNo = Objects.toString(first.get("return_no"), no);
            date = parseDate(first.get("return_date"));
            partyName = Objects.toString(first.get("party_name"), "Customer");
            gstin = Objects.toString(first.get("party_gstin"), "");
            reason = Objects.toString(first.get("reason"), "");

            for (Map<String, Object> r : retRows) {
                BigDecimal q = toBigDecimal(r.get("quantity"));
                BigDecimal a = toBigDecimal(r.get("amount"));
                total = total.add(a);
                String lineReason = Objects.toString(r.get("reason"), reason);
                boolean physical = isPhysicalStockReturnReason(lineReason);

                invLines.add(new GeneralLedgerDtos.PostingFlowInventoryLine(
                        Objects.toString(r.get("item_code"), ""),
                        "Return item (" + lineReason + ")",
                        physical ? "INWARD_RESTOCK (STOCK REINSTATED)" : "ZERO_MOVEMENT (PRICE / REBATE ADJUSTMENT)",
                        physical ? q : BigDecimal.ZERO,
                        "Nos",
                        q.compareTo(BigDecimal.ZERO) > 0 ? a.divide(q, 2, RoundingMode.HALF_UP) : BigDecimal.ZERO,
                        physical ? a : BigDecimal.ZERO
                ));
            }
        } else {
            // Check credit_note_header
            List<Map<String, Object>> cnRows = jdbc.queryForList("""
                SELECT id, credit_note_no, note_date, original_invoice_no, party_name, party_gstin,
                       taxable_amount, cgst_amount, sgst_amount, igst_amount, total_amount, reason_code, notes, stock_returned
                FROM credit_note_header WHERE credit_note_no = ? OR id::text = ?
                LIMIT 1
                """, no, no);
            if (cnRows.isEmpty()) {
                throw new IllegalArgumentException("Sales return / Credit Note " + no + " not found.");
            }
            Map<String, Object> cn = cnRows.getFirst();
            retNo = Objects.toString(cn.get("credit_note_no"), no);
            date = parseDate(cn.get("note_date"));
            partyName = Objects.toString(cn.get("party_name"), "Customer");
            gstin = Objects.toString(cn.get("party_gstin"), "");
            total = toBigDecimal(cn.get("total_amount"));
            reason = Objects.toString(cn.get("reason_code"), "") + " " + Objects.toString(cn.get("notes"), "");
            boolean stockMoved = Boolean.TRUE.equals(cn.get("stock_returned"));

            invLines.add(new GeneralLedgerDtos.PostingFlowInventoryLine(
                    "CREDIT_NOTE_ITEMS",
                    "Credit Note Items (" + reason + ")",
                    stockMoved ? "INWARD_RESTOCK (STOCK REINSTATED)" : "ZERO_MOVEMENT (FINANCIAL / COMMERCIAL ADJUSTMENT)",
                    stockMoved ? BigDecimal.ONE : BigDecimal.ZERO,
                    "Lot",
                    toBigDecimal(cn.get("taxable_amount")),
                    stockMoved ? toBigDecimal(cn.get("taxable_amount")) : BigDecimal.ZERO
            ));
        }

        boolean isRebate = isCommercialRebateReason(reason);
        boolean isPhysical = isPhysicalStockReturnReason(reason);

        // Fetch voucher
        List<GeneralLedgerDtos.PostingFlowVoucherLine> vLines = fetchVoucherLines("CREDIT_NOTE", null, retNo);
        String jvNo = fetchVoucherNumber("CREDIT_NOTE", null, retNo);
        if (vLines.isEmpty()) {
            vLines = fetchVoucherLines("SALES_RETURN", null, retNo);
            jvNo = fetchVoucherNumber("SALES_RETURN", null, retNo);
        }

        if (vLines.isEmpty()) {
            jvNo = "PROSPECTIVE (ON APPROVAL)";
            if (isRebate) {
                // Commercial discount - Section 15(3)(b)
                vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("5200", "Discount Allowed / Commercial Rebate", total, BigDecimal.ZERO, "Debit Rebate: " + retNo));
                vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("1100", "Accounts Receivable", BigDecimal.ZERO, total, "Credit Customer: " + partyName));
            } else {
                // Statutory Credit note (tax reversed)
                BigDecimal taxable = total.divide(BigDecimal.valueOf(1.18), 2, RoundingMode.HALF_UP);
                BigDecimal tax = total.subtract(taxable);
                vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("4100", "Sales Returns & Allowances", taxable, BigDecimal.ZERO, "Debit Sales Return: " + retNo));
                vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("2200", "Output GST Reversal", tax, BigDecimal.ZERO, "Debit Output GST Reversal"));
                vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("1100", "Accounts Receivable", BigDecimal.ZERO, total, "Credit Customer: " + partyName));
            }
        }

        BigDecimal totDeb = vLines.stream().map(GeneralLedgerDtos.PostingFlowVoucherLine::debitAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totCred = vLines.stream().map(GeneralLedgerDtos.PostingFlowVoucherLine::creditAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

        // GST Impact
        GeneralLedgerDtos.PostingFlowGstImpact gst;
        String profile;
        String summary;
        if (isRebate) {
            profile = "COMMERCIAL_REBATE";
            summary = "Commercial Rebate / Volume Discount (CGST Sec 15(3)(b)): Debits Discount Allowed, Credits Customer AR. Zero stock impact, Zero GST reversal.";
            gst = new GeneralLedgerDtos.PostingFlowGstImpact(
                    "NONE",
                    "Non-GST Commercial Rebate (Section 15(3)(b))",
                    total, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    "CGST Act Sec 15(3)(b): Post-supply discount not agreed in terms of pre-existing agreement. Tax liability cannot be reduced; issued as commercial credit note."
            );
        } else if (!isPhysical) {
            profile = "FINANCIAL_RATE_DIFFERENCE";
            summary = "Financial Credit Note for Rate Difference / Price Adjustment: Reverses Sales Revenue & Output GST, Credits Customer AR. Zero stock impact.";
            BigDecimal tax = totDeb.subtract(total.divide(BigDecimal.valueOf(1.18), 2, RoundingMode.HALF_UP));
            BigDecimal taxable = totDeb.subtract(tax);
            gst = new GeneralLedgerDtos.PostingFlowGstImpact(
                    "GSTR-1 & GSTR-3B",
                    "GSTR-1 Table 9B (Credit/Debit Notes Registered) & GSTR-3B Table 3.1(a) (Net of Credit Notes)",
                    taxable, tax.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP), tax.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP), BigDecimal.ZERO, tax,
                    "CGST Act Sec 34(1): Credit note issued for taxable value / rate reduction. Output tax liability is reduced in the month of issue."
            );
        } else {
            profile = "PHYSICAL_STOCK_RETURN";
            summary = "Physical Goods Sales Return: Reinstates stock to inventory at FIFO valuation, Reverses Sales Revenue & Output GST, Credits Customer AR.";
            BigDecimal tax = totDeb.subtract(total.divide(BigDecimal.valueOf(1.18), 2, RoundingMode.HALF_UP));
            BigDecimal taxable = totDeb.subtract(tax);
            gst = new GeneralLedgerDtos.PostingFlowGstImpact(
                    "GSTR-1 & GSTR-3B",
                    "GSTR-1 Table 9B (Credit/Debit Notes) & GSTR-3B Table 3.1(a) (Net of Credit Notes)",
                    taxable, tax.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP), tax.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP), BigDecimal.ZERO, tax,
                    "CGST Act Sec 34(1): Credit note issued for goods returned by recipient. Reverses output tax and reinstates stock."
            );
        }

        return new GeneralLedgerDtos.PostingFlowResponse(
                "SALES_RETURN", retNo, date, partyName, gstin, total,
                profile, summary, jvNo, vLines, totDeb, totCred, totDeb.compareTo(totCred) == 0,
                invLines, gst
        );
    }

    private GeneralLedgerDtos.PostingFlowResponse buildPurchaseReturnPostingFlow(String no) {
        List<Map<String, Object>> dnRows = jdbc.queryForList("""
            SELECT id, debit_note_no, note_date, original_bill_no, supplier_name, supplier_gstin,
                   taxable_amount, cgst_amount, sgst_amount, igst_amount, total_amount, reason_code, notes, stock_returned
            FROM debit_note_header WHERE debit_note_no = ? OR id::text = ?
            LIMIT 1
            """, no, no);

        String dnNo = no;
        LocalDate date = LocalDate.now();
        String supplier = "Supplier";
        String gstin = "";
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal taxable = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        String reason = "";
        boolean stockMoved = true;

        if (!dnRows.isEmpty()) {
            Map<String, Object> dn = dnRows.getFirst();
            dnNo = Objects.toString(dn.get("debit_note_no"), no);
            date = parseDate(dn.get("note_date"));
            supplier = Objects.toString(dn.get("supplier_name"), "Supplier");
            gstin = Objects.toString(dn.get("supplier_gstin"), "");
            total = toBigDecimal(dn.get("total_amount"));
            taxable = toBigDecimal(dn.get("taxable_amount"));
            BigDecimal cgst = toBigDecimal(dn.get("cgst_amount"));
            BigDecimal sgst = toBigDecimal(dn.get("sgst_amount"));
            BigDecimal igst = toBigDecimal(dn.get("igst_amount"));
            tax = cgst.add(sgst).add(igst);
            reason = Objects.toString(dn.get("reason_code"), "") + " " + Objects.toString(dn.get("notes"), "");
            stockMoved = Boolean.TRUE.equals(dn.get("stock_returned"));
        } else {
            // Query return_register for purchase return
            List<Map<String, Object>> retRows = jdbc.queryForList("""
                SELECT r.return_no, r.return_date, r.amount, r.reason, pm.name AS party_name, COALESCE(pm.gstin, '') AS party_gstin
                FROM return_register r
                LEFT JOIN party_master pm ON pm.id = r.party_id
                WHERE r.return_no = ?
                """, no);
            if (retRows.isEmpty()) {
                throw new IllegalArgumentException("Purchase return / Debit Note " + no + " not found.");
            }
            Map<String, Object> r = retRows.getFirst();
            dnNo = Objects.toString(r.get("return_no"), no);
            date = parseDate(r.get("return_date"));
            supplier = Objects.toString(r.get("party_name"), "Supplier");
            gstin = Objects.toString(r.get("party_gstin"), "");
            total = toBigDecimal(r.get("amount"));
            reason = Objects.toString(r.get("reason"), "");
            taxable = total.divide(BigDecimal.valueOf(1.18), 2, RoundingMode.HALF_UP);
            tax = total.subtract(taxable);
            stockMoved = isPhysicalStockReturnReason(reason);
        }

        boolean isRebate = isCommercialRebateReason(reason);
        boolean isPhysical = isPhysicalStockReturnReason(reason);

        // Fetch voucher
        List<GeneralLedgerDtos.PostingFlowVoucherLine> vLines = fetchVoucherLines("DEBIT_NOTE", null, dnNo);
        String jvNo = fetchVoucherNumber("DEBIT_NOTE", null, dnNo);
        if (vLines.isEmpty()) {
            vLines = fetchVoucherLines("PURCHASE_RETURN", null, dnNo);
            jvNo = fetchVoucherNumber("PURCHASE_RETURN", null, dnNo);
        }

        if (vLines.isEmpty()) {
            jvNo = "PROSPECTIVE (ON APPROVAL)";
            if (isRebate) {
                vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("2100", "Accounts Payable", total, BigDecimal.ZERO, "Debit Supplier: " + supplier));
                vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("5210", "Discount Received / Supplier Rebate", BigDecimal.ZERO, total, "Credit Rebate: " + dnNo));
            } else {
                vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("2100", "Accounts Payable", total, BigDecimal.ZERO, "Debit Supplier: " + supplier));
                vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("5100", "Purchase Returns & Outward", BigDecimal.ZERO, taxable, "Credit Purchase Return"));
                vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("1200", "Input GST Reversal", BigDecimal.ZERO, tax, "Credit Input GST Reversal"));
            }
        }

        BigDecimal totDeb = vLines.stream().map(GeneralLedgerDtos.PostingFlowVoucherLine::debitAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totCred = vLines.stream().map(GeneralLedgerDtos.PostingFlowVoucherLine::creditAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

        List<GeneralLedgerDtos.PostingFlowInventoryLine> invLines = new ArrayList<>();
        invLines.add(new GeneralLedgerDtos.PostingFlowInventoryLine(
                "DEBIT_NOTE_ITEMS",
                "Debit Note Items (" + reason + ")",
                isPhysical ? "OUTWARD_RETURN (STOCK DEDUCTED)" : "ZERO_MOVEMENT (FINANCIAL / REBATE ADJUSTMENT)",
                isPhysical ? BigDecimal.ONE.negate() : BigDecimal.ZERO,
                "Lot",
                taxable,
                isPhysical ? taxable.negate() : BigDecimal.ZERO
        ));

        GeneralLedgerDtos.PostingFlowGstImpact gst;
        String profile;
        String summary;
        if (isRebate) {
            profile = "COMMERCIAL_REBATE";
            summary = "Supplier Volume Discount / Commercial Rebate: Debits Supplier AP, Credits Discount Received. Zero stock impact, Zero ITC reversal.";
            gst = new GeneralLedgerDtos.PostingFlowGstImpact(
                    "NONE",
                    "Non-GST Commercial Debit Note",
                    total, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    "Commercial rebate without tax adjustment."
            );
        } else if (!isPhysical) {
            profile = "FINANCIAL_RATE_DIFFERENCE";
            summary = "Financial Debit Note for Rate Difference / Over-billing: Debits Supplier AP, Reverses Purchase Cost and Input GST. Zero stock impact.";
            gst = new GeneralLedgerDtos.PostingFlowGstImpact(
                    "GSTR-3B",
                    "Table 4(B)(2) - Others (ITC Reversal)",
                    taxable, tax.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP), tax.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP), BigDecimal.ZERO, tax,
                    "CGST Act Sec 34(2): Mandatory Input Tax Credit reversal upon supplier rate reduction or credit note."
            );
        } else {
            profile = "PHYSICAL_STOCK_RETURN";
            summary = "Physical Purchase Return: Deducts inventory from warehouse, Debits Supplier AP, Reverses Input GST ITC.";
            gst = new GeneralLedgerDtos.PostingFlowGstImpact(
                    "GSTR-3B",
                    "Table 4(B)(2) - Others (ITC Reversal)",
                    taxable, tax.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP), tax.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP), BigDecimal.ZERO, tax,
                    "CGST Act Sec 34(2): Mandatory Input Tax Credit reversal upon returning goods to supplier."
            );
        }

        return new GeneralLedgerDtos.PostingFlowResponse(
                "PURCHASE_RETURN", dnNo, date, supplier, gstin, total,
                profile, summary, jvNo, vLines, totDeb, totCred, totDeb.compareTo(totCred) == 0,
                invLines, gst
        );
    }

    private GeneralLedgerDtos.PostingFlowResponse buildPaymentPostingFlow(String no) {
        List<Map<String, Object>> payments = jdbc.queryForList("""
            SELECT id, document_type, document_id, payment_date, amount, payment_mode, reference_no, received_from, payment_type
            FROM payment_record
            WHERE reference_no = ? OR id::text = ?
            LIMIT 1
            """, no, no);
        if (payments.isEmpty()) {
            throw new IllegalArgumentException("Payment record " + no + " not found.");
        }
        Map<String, Object> p = payments.getFirst();
        Long paymentId = ((Number) p.get("id")).longValue();
        String docType = Objects.toString(p.get("document_type"), "SALE");
        LocalDate date = parseDate(p.get("payment_date"));
        BigDecimal amount = toBigDecimal(p.get("amount"));
        String mode = Objects.toString(p.get("payment_mode"), "BANK");
        String refNo = Objects.toString(p.get("reference_no"), no);
        boolean isCustomer = "SALE".equalsIgnoreCase(docType) || "CUSTOMER".equalsIgnoreCase(Objects.toString(p.get("received_from"), ""));

        List<GeneralLedgerDtos.PostingFlowVoucherLine> vLines = fetchVoucherLines(isCustomer ? "SALES_PAYMENT" : "PURCHASE_PAYMENT", paymentId, refNo);
        String jvNo = fetchVoucherNumber(isCustomer ? "SALES_PAYMENT" : "PURCHASE_PAYMENT", paymentId, refNo);

        if (vLines.isEmpty()) {
            jvNo = "PROSPECTIVE (ON SETTLEMENT)";
            boolean isCash = mode.toUpperCase(Locale.ROOT).contains("CASH");
            String fundAccCode = isCash ? "1010" : "1020";
            String fundAccName = isCash ? "Cash in Hand" : "Bank Account";
            if (isCustomer) {
                vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine(fundAccCode, fundAccName, amount, BigDecimal.ZERO, "Debit Received via " + mode));
                vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("1100", "Accounts Receivable", BigDecimal.ZERO, amount, "Credit Customer AR (" + refNo + ")"));
            } else {
                vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("2100", "Accounts Payable", amount, BigDecimal.ZERO, "Debit Supplier AP (" + refNo + ")"));
                vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine(fundAccCode, fundAccName, BigDecimal.ZERO, amount, "Credit Paid via " + mode));
            }
        }

        BigDecimal totDeb = vLines.stream().map(GeneralLedgerDtos.PostingFlowVoucherLine::debitAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totCred = vLines.stream().map(GeneralLedgerDtos.PostingFlowVoucherLine::creditAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

        GeneralLedgerDtos.PostingFlowGstImpact gst = new GeneralLedgerDtos.PostingFlowGstImpact(
                "NONE", "No Direct GST Table Movement (Financial Cash/Bank Settlement)",
                amount, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                "CGST Act Sec 12/13: GST is triggered at invoice issuance / advance receipt; standard invoice settlement does not generate duplicate tax liability."
        );

        return new GeneralLedgerDtos.PostingFlowResponse(
                isCustomer ? "PAYMENT_RECEIPT" : "PURCHASE_PAYMENT", refNo, date,
                isCustomer ? "Customer" : "Supplier", "", amount,
                "PAYMENT_SETTLEMENT",
                isCustomer ? "Customer Payment Receipt: Debits Cash/Bank and Credits Accounts Receivable. Zero inventory, Zero tax liability impact."
                           : "Supplier Payout: Debits Accounts Payable and Credits Cash/Bank. Zero inventory, Zero tax liability impact.",
                jvNo, vLines, totDeb, totCred, totDeb.compareTo(totCred) == 0,
                List.of(), gst
        );
    }

    private List<GeneralLedgerDtos.PostingFlowVoucherLine> fetchVoucherLines(String refType, Long refId, String refNo) {
        List<GeneralLedgerDtos.PostingFlowVoucherLine> lines = new ArrayList<>();
        try {
            List<Map<String, Object>> entries = jdbc.queryForList("""
                SELECT id FROM journal_entry
                WHERE (reference_no = ? OR (reference_type = ? AND reference_id = ?))
                  AND status <> 'CANCELLED'
                ORDER BY id DESC LIMIT 1
                """, refNo, refType, refId != null ? refId : -1L);
            if (!entries.isEmpty()) {
                Long jvId = ((Number) entries.getFirst().get("id")).longValue();
                List<Map<String, Object>> rawLines = jdbc.queryForList("""
                    SELECT jl.debit_amount, jl.credit_amount, jl.line_narration, ca.account_code, ca.account_name
                    FROM journal_line jl
                    JOIN chart_of_accounts ca ON ca.id = jl.account_id
                    WHERE jl.journal_entry_id = ?
                    ORDER BY jl.line_number ASC, jl.id ASC
                    """, jvId);
                for (Map<String, Object> l : rawLines) {
                    lines.add(new GeneralLedgerDtos.PostingFlowVoucherLine(
                            Objects.toString(l.get("account_code"), ""),
                            Objects.toString(l.get("account_name"), ""),
                            toBigDecimal(l.get("debit_amount")),
                            toBigDecimal(l.get("credit_amount")),
                            Objects.toString(l.get("line_narration"), "")
                    ));
                }
            }
        } catch (Exception ignored) {}
        return lines;
    }

    private String fetchVoucherNumber(String refType, Long refId, String refNo) {
        try {
            return jdbc.queryForObject("""
                SELECT entry_number FROM journal_entry
                WHERE (reference_no = ? OR (reference_type = ? AND reference_id = ?))
                  AND status <> 'CANCELLED'
                ORDER BY id DESC LIMIT 1
                """, String.class, refNo, refType, refId != null ? refId : -1L);
        } catch (Exception ignored) {
            return "PROSPECTIVE";
        }
    }

    private static LocalDate parseDate(Object o) {
        if (o == null) return LocalDate.now();
        if (o instanceof LocalDate ld) return ld;
        try { return LocalDate.parse(o.toString().substring(0, 10)); } catch (Exception ignored) { return LocalDate.now(); }
    }
}
