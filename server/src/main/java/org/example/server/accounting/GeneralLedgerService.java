package org.example.server.accounting;

import org.example.server.audit.AuditService;
import org.example.server.insights.CashPositionService;
import org.example.server.persistence.entity.ChartOfAccountsEntity;
import org.example.server.persistence.entity.JournalEntryEntity;
import org.example.server.persistence.entity.JournalLineEntity;
import org.example.server.persistence.repository.ChartOfAccountsRepository;
import org.example.server.persistence.repository.JournalEntryRepository;
import org.example.server.persistence.repository.JournalLineRepository;
import org.example.server.persistence.JpaNativeRepository;
import org.example.server.security.CurrentUser;
import org.example.shared.ReferenceFormatRules;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final ObjectProvider<AuditService> auditProvider;
    private final ObjectProvider<CashPositionService> cashPositionProvider;
    private final AtomicLong sequenceGen = new AtomicLong(System.currentTimeMillis() % 100000);

    public GeneralLedgerService(ChartOfAccountsRepository coaRepository,
                                JournalEntryRepository journalEntryRepository,
                                JournalLineRepository journalLineRepository,
                                JpaNativeRepository jdbc) {
        this(coaRepository, journalEntryRepository, journalLineRepository, jdbc, null, null);
    }

    @Autowired
    public GeneralLedgerService(ChartOfAccountsRepository coaRepository,
                                JournalEntryRepository journalEntryRepository,
                                JournalLineRepository journalLineRepository,
                                JpaNativeRepository jdbc,
                                ObjectProvider<AuditService> auditProvider,
                                ObjectProvider<CashPositionService> cashPositionProvider) {
        this.coaRepository = coaRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.journalLineRepository = journalLineRepository;
        this.jdbc = jdbc;
        this.auditProvider = auditProvider;
        this.cashPositionProvider = cashPositionProvider;
    }

    private void recordAudit(String entityType, Number entityId, String action, String detail, List<AuditService.Change> changes) {
        try {
            AuditService audit = auditProvider != null ? auditProvider.getIfAvailable() : null;
            if (audit == null || !CurrentUser.isSet()) return;
            if (changes == null || changes.isEmpty()) {
                audit.log(entityType, entityId, action, detail);
            } else {
                audit.logChanges(entityType, entityId, action, detail, changes);
            }
        } catch (Exception ignored) {
            // Never let auxiliary audit lookup disrupt GL core persistence when anonymous/system seeded
        }
    }

    private static final String PERIOD_LOCK_UNTIL_KEY = "accounting.period.lock.until";
    private static final String PERIOD_LOCK_BY_KEY = "accounting.period.lock.by";
    private static final String PERIOD_LOCK_AT_KEY = "accounting.period.lock.at";

    @Transactional(readOnly = true)
    public List<GeneralLedgerDtos.AccountDto> listAccounts() {
        return listAccounts(false);
    }

    @Transactional(readOnly = true)
    public List<GeneralLedgerDtos.AccountDto> listAccounts(boolean includeInactive) {
        List<ChartOfAccountsEntity> rows = includeInactive
                ? coaRepository.findAllByOrderByAccountCodeAsc()
                : coaRepository.findAllByIsActiveTrueOrderByAccountCodeAsc();
        return rows.stream().map(this::toAccountDto).toList();
    }

    public GeneralLedgerDtos.AccountDto createAccount(GeneralLedgerDtos.AccountDto dto) {
        String code = dto.accountCode() == null ? "" : dto.accountCode().trim();
        String name = dto.accountName() == null ? "" : dto.accountName().trim();
        String type = dto.accountType() == null ? "" : dto.accountType().trim().toUpperCase(Locale.ROOT);
        if (code.isBlank()) throw new IllegalArgumentException("Account code is required.");
        if (name.isBlank()) throw new IllegalArgumentException("Account name is required.");
        if (!Set.of("ASSET", "LIABILITY", "EQUITY", "REVENUE", "EXPENSE").contains(type)) {
            throw new IllegalArgumentException("Account type must be ASSET, LIABILITY, EQUITY, REVENUE, or EXPENSE.");
        }
        if (coaRepository.findByAccountCode(code).isPresent()) {
            throw new IllegalArgumentException("Account code already exists: " + code);
        }
        ChartOfAccountsEntity entity = new ChartOfAccountsEntity();
        entity.setAccountCode(code);
        entity.setAccountName(name);
        entity.setAccountType(type);
        entity.setAccountSubtype(dto.accountSubtype() == null || dto.accountSubtype().isBlank() ? "GENERAL" : dto.accountSubtype().trim().toUpperCase(Locale.ROOT));
        entity.setParentId(dto.parentId());
        entity.setCurrency(dto.currency() != null && !dto.currency().isBlank() ? dto.currency().trim() : "INR");
        entity.setOpeningBalance(dto.openingBalance() != null ? dto.openingBalance() : BigDecimal.ZERO);
        entity.setCurrentBalance(entity.getOpeningBalance());
        entity.setIsActive(dto.isActive() != null ? dto.isActive() : true);
        entity.setIsSystem(false);
        ChartOfAccountsEntity saved = coaRepository.save(entity);
        reconcileOpeningBalanceSuspense(saved, saved.getOpeningBalance());
        recordAudit("ACCOUNT", saved.getId(), "CREATED",
                saved.getAccountCode() + " - " + saved.getAccountName() + " (" + saved.getAccountType() + ")",
                List.of(new AuditService.Change("Opening Balance", "0.00", saved.getOpeningBalance().toPlainString())));
        return toAccountDto(saved);
    }

    public GeneralLedgerDtos.AccountDto updateAccount(Long id, GeneralLedgerDtos.UpdateAccountRequest req) {
        ChartOfAccountsEntity entity = coaRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + id));
        String oldName = entity.getAccountName();
        String oldType = entity.getAccountType();
        String oldSubtype = entity.getAccountSubtype();
        BigDecimal oldOpening = entity.getOpeningBalance() != null ? entity.getOpeningBalance() : BigDecimal.ZERO;
        Boolean oldActive = entity.getIsActive();
        BigDecimal openingDelta = BigDecimal.ZERO;

        if (req.accountName() != null && !req.accountName().isBlank()) {
            entity.setAccountName(req.accountName().trim());
        }
        if (req.accountType() != null && !req.accountType().isBlank()) {
            String normalized = req.accountType().trim().toUpperCase(Locale.ROOT);
            if (!Set.of("ASSET", "LIABILITY", "EQUITY", "REVENUE", "EXPENSE").contains(normalized)) {
                throw new IllegalArgumentException("Invalid account type: " + normalized);
            }
            if (!Boolean.TRUE.equals(entity.getIsSystem())) {
                entity.setAccountType(normalized);
            }
        }
        if (req.accountSubtype() != null && !req.accountSubtype().isBlank()) {
            entity.setAccountSubtype(req.accountSubtype().trim().toUpperCase(Locale.ROOT));
        }
        if (req.openingBalance() != null) {
            BigDecimal current = entity.getCurrentBalance() != null ? entity.getCurrentBalance() : BigDecimal.ZERO;
            openingDelta = req.openingBalance().subtract(oldOpening);
            entity.setOpeningBalance(req.openingBalance());
            entity.setCurrentBalance(current.add(openingDelta));
        }
        if (req.isActive() != null && !req.isActive()) {
            if (Boolean.TRUE.equals(entity.getIsSystem())) {
                throw new IllegalArgumentException("Core system account '" + entity.getAccountCode() + " - " + entity.getAccountName() + "' cannot be deactivated.");
            }
            BigDecimal cur = entity.getCurrentBalance() != null ? entity.getCurrentBalance() : BigDecimal.ZERO;
            if (cur.abs().compareTo(new BigDecimal("0.01")) >= 0) {
                throw new IllegalArgumentException("Account '" + entity.getAccountCode() + "' has a non-zero balance (" + cur + "). Bring balance to 0.00 before deactivating.");
            }
            entity.setIsActive(false);
        } else if (req.isActive() != null && req.isActive()) {
            entity.setIsActive(true);
        }
        ChartOfAccountsEntity saved = coaRepository.save(entity);
        reconcileOpeningBalanceSuspense(saved, openingDelta);
        recordAudit("ACCOUNT", saved.getId(), "UPDATED",
                saved.getAccountCode() + " - " + saved.getAccountName(),
                List.of(
                        new AuditService.Change("Account Name", oldName, saved.getAccountName()),
                        new AuditService.Change("Account Type", oldType, saved.getAccountType()),
                        new AuditService.Change("Account Subtype", oldSubtype, saved.getAccountSubtype()),
                        new AuditService.Change("Opening Balance", oldOpening.toPlainString(),
                                saved.getOpeningBalance() != null ? saved.getOpeningBalance().toPlainString() : "0.00"),
                        new AuditService.Change("Active", String.valueOf(oldActive), String.valueOf(saved.getIsActive()))
                ));
        return toAccountDto(saved);
    }

    /** Preserves double-entry Trial Balance equilibrium whenever an account opening balance is added or changed. */
    private void reconcileOpeningBalanceSuspense(ChartOfAccountsEntity target, BigDecimal openingDelta) {
        if (target == null || openingDelta == null || openingDelta.compareTo(BigDecimal.ZERO) == 0) return;
        if ("3999".equals(target.getAccountCode())) return;
        Long suspenseId = getAccountIdOrDefault("3999", "Opening Balance Suspense", "EQUITY");
        ChartOfAccountsEntity suspense = coaRepository.findById(suspenseId).orElse(null);
        if (suspense == null) return;
        BigDecimal cur = suspense.getCurrentBalance() != null ? suspense.getCurrentBalance() : BigDecimal.ZERO;
        BigDecimal curOpen = suspense.getOpeningBalance() != null ? suspense.getOpeningBalance() : BigDecimal.ZERO;
        // Debit-normal account opening increases Dr -> offset Credits 3999 (positive EQUITY); Credit-normal decreases Cr -> offset Debits 3999.
        BigDecimal suspenseDelta = isDebitNormal(target.getAccountType()) ? openingDelta : openingDelta.negate();
        suspense.setOpeningBalance(curOpen.add(suspenseDelta));
        suspense.setCurrentBalance(cur.add(suspenseDelta));
        coaRepository.save(suspense);
    }

    @Transactional(readOnly = true)
    public GeneralLedgerDtos.PeriodLockDto getPeriodLock() {
        try {
            String val = jdbc.queryForObject("SELECT setting_value FROM application_setting WHERE setting_key = ? LIMIT 1", String.class, PERIOD_LOCK_UNTIL_KEY);
            if (val == null || val.isBlank()) {
                return new GeneralLedgerDtos.PeriodLockDto(null, null, null, false);
            }
            LocalDate lockedUntil = LocalDate.parse(val.trim().substring(0, 10));
            String by = jdbc.queryForObject("SELECT setting_value FROM application_setting WHERE setting_key = ? LIMIT 1", String.class, PERIOD_LOCK_BY_KEY);
            String at = jdbc.queryForObject("SELECT setting_value FROM application_setting WHERE setting_key = ? LIMIT 1", String.class, PERIOD_LOCK_AT_KEY);
            return new GeneralLedgerDtos.PeriodLockDto(lockedUntil, by, at, true);
        } catch (Exception ignored) {
            return new GeneralLedgerDtos.PeriodLockDto(null, null, null, false);
        }
    }

    public GeneralLedgerDtos.PeriodLockDto setPeriodLock(LocalDate lockedUntil, String actor) {
        GeneralLedgerDtos.PeriodLockDto previous = getPeriodLock();
        String safeActor = (actor == null || actor.isBlank()) ? "ADMIN" : actor.trim();
        String nowStr = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        upsertSetting(PERIOD_LOCK_UNTIL_KEY, lockedUntil == null ? "" : lockedUntil.toString());
        upsertSetting(PERIOD_LOCK_BY_KEY, safeActor);
        upsertSetting(PERIOD_LOCK_AT_KEY, nowStr);
        String oldUntil = previous.lockedUntil() != null ? previous.lockedUntil().toString() : "OPEN";
        String newUntil = lockedUntil != null ? lockedUntil.toString() : "OPEN";
        recordAudit("PERIOD_LOCK", 1L, lockedUntil != null ? "LOCKED" : "UNLOCKED",
                "Period lock changed from " + oldUntil + " to " + newUntil + " by " + safeActor,
                List.of(new AuditService.Change("Locked Until", oldUntil, newUntil)));
        return new GeneralLedgerDtos.PeriodLockDto(lockedUntil, safeActor, nowStr, lockedUntil != null);
    }

    private void upsertSetting(String key, String value) {
        jdbc.update(
                "INSERT INTO application_setting(setting_key, setting_value, updated_at) VALUES (?, ?, CURRENT_TIMESTAMP) " +
                "ON CONFLICT (setting_key) DO UPDATE SET setting_value = EXCLUDED.setting_value, updated_at = CURRENT_TIMESTAMP",
                key, value == null ? "" : value
        );
    }

    public void ensurePeriodOpen(LocalDate entryDate) {
        if (entryDate == null) return;
        GeneralLedgerDtos.PeriodLockDto lock = getPeriodLock();
        if (lock.active() && lock.lockedUntil() != null && !entryDate.isAfter(lock.lockedUntil())) {
            throw new IllegalStateException("Accounting Period Locked: Books are frozen through " + lock.lockedUntil()
                    + " (locked by " + (lock.lockedBy() != null ? lock.lockedBy() : "Admin")
                    + "). Posting or modifying entries dated " + entryDate + " is blocked. Unlock period in General Ledger to proceed.");
        }
    }

    public GeneralLedgerDtos.JournalEntryDto postJournalEntry(GeneralLedgerDtos.CreateJournalRequest req) {
        if (req.lines() == null || req.lines().size() < 2) {
            throw new IllegalArgumentException("Journal entry must contain at least two lines");
        }
        LocalDate effectiveDate = req.entryDate() != null ? req.entryDate() : LocalDate.now();
        ensurePeriodOpen(effectiveDate);

        BigDecimal sumDebit = BigDecimal.ZERO;
        BigDecimal sumCredit = BigDecimal.ZERO;

        for (GeneralLedgerDtos.CreateLineRequest line : req.lines()) {
            BigDecimal d = line.debitAmount() != null ? line.debitAmount() : BigDecimal.ZERO;
            BigDecimal c = line.creditAmount() != null ? line.creditAmount() : BigDecimal.ZERO;
            if (d.compareTo(BigDecimal.ZERO) < 0 || c.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("Debit and Credit amounts cannot be negative.");
            }
            if (d.compareTo(BigDecimal.ZERO) > 0 && c.compareTo(BigDecimal.ZERO) > 0) {
                throw new IllegalArgumentException("A single journal line cannot contain both Debit and Credit amounts.");
            }
            sumDebit = sumDebit.add(d);
            sumCredit = sumCredit.add(c);
        }

        if (sumDebit.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Journal entry total must be greater than zero.");
        }

        if (sumDebit.setScale(2, RoundingMode.HALF_UP).compareTo(sumCredit.setScale(2, RoundingMode.HALF_UP)) != 0) {
            throw new IllegalArgumentException("Unbalanced Journal Entry: Debits (" + sumDebit +
                    ") must equal Credits (" + sumCredit + ")");
        }

        String status = (req.status() != null && !req.status().isBlank()) ? req.status().toUpperCase(Locale.ROOT) : "POSTED";
        JournalEntryEntity entry = new JournalEntryEntity();
        entry.setEntryNumber(nextJournalVoucherNumber(effectiveDate));
        entry.setEntryDate(effectiveDate);
        entry.setEntryType(req.entryType() != null && !req.entryType().isBlank() ? req.entryType().trim().toUpperCase(Locale.ROOT) : "GENERAL");
        entry.setReferenceType(req.referenceType());
        entry.setReferenceId(req.referenceId());
        entry.setReferenceNo(req.referenceNo());
        entry.setNarration(req.narration());
        entry.setTotalDebit(sumDebit);
        entry.setTotalCredit(sumCredit);
        entry.setStatus(status);
        String creator = (req.postedBy() != null && !req.postedBy().isBlank()) ? req.postedBy().trim()
                : (CurrentUser.isSet() ? CurrentUser.require().username() : "SYSTEM");
        entry.setCreatedBy(creator);
        if ("POSTED".equals(status)) {
            entry.setPostedBy(creator);
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
            if (!Boolean.TRUE.equals(account.getIsActive()) && !"REVERSAL".equalsIgnoreCase(entry.getEntryType())) {
                throw new IllegalArgumentException("Account '" + account.getAccountCode() + " - " + account.getAccountName() + "' is inactive and cannot be posted.");
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
            JournalLineEntity savedLine = journalLineRepository.save(line);
            savedEntry.getLines().add(savedLine);

            // Update Account Balance if POSTED
            if ("POSTED".equals(status)) {
                BigDecimal current = account.getCurrentBalance() != null ? account.getCurrentBalance() : BigDecimal.ZERO;
                if (isDebitNormal(account.getAccountType())) {
                    account.setCurrentBalance(current.add(debit).subtract(credit));
                } else {
                    account.setCurrentBalance(current.add(credit).subtract(debit));
                }
                coaRepository.save(account);
            }
        }

        recordAudit("JOURNAL", savedEntry.getId(), "DRAFT".equals(status) ? "CREATED_DRAFT" : "POSTED",
                savedEntry.getEntryNumber() + " (" + savedEntry.getEntryType() + ") • Total: " + sumDebit.toPlainString(),
                List.of(new AuditService.Change("Voucher Status", null, status),
                        new AuditService.Change("Amount", "0.00", sumDebit.toPlainString())));
        return toJournalEntryDto(savedEntry);
    }

    public GeneralLedgerDtos.JournalEntryDto postDraft(Long journalEntryId, String postedBy) {
        JournalEntryEntity entry = journalEntryRepository.findById(journalEntryId)
                .orElseThrow(() -> new IllegalArgumentException("Journal Entry not found: " + journalEntryId));
        if ("POSTED".equalsIgnoreCase(entry.getStatus())) {
            return toJournalEntryDto(entry);
        }
        if ("CANCELLED".equalsIgnoreCase(entry.getStatus()) || "REVERSED".equalsIgnoreCase(entry.getStatus())) {
            throw new IllegalStateException("Cannot post a " + entry.getStatus() + " voucher.");
        }
        ensurePeriodOpen(entry.getEntryDate());
        String actor = postedBy != null && !postedBy.isBlank() ? postedBy.trim()
                : (CurrentUser.isSet() ? CurrentUser.require().username() : "SYSTEM");
        boolean selfPosted = entry.getCreatedBy() != null && entry.getCreatedBy().equalsIgnoreCase(actor);
        entry.setStatus("POSTED");
        entry.setPostedBy(actor);
        entry.setPostedAt(LocalDateTime.now());
        for (JournalLineEntity line : entry.getLines()) {
            ChartOfAccountsEntity account = coaRepository.findById(line.getAccountId())
                    .orElseThrow(() -> new IllegalArgumentException("Account not found: " + line.getAccountId()));
            if (!Boolean.TRUE.equals(account.getIsActive())) {
                throw new IllegalArgumentException("Account '" + account.getAccountCode() + " - " + account.getAccountName() + "' is inactive.");
            }
            BigDecimal current = account.getCurrentBalance() != null ? account.getCurrentBalance() : BigDecimal.ZERO;
            if (isDebitNormal(account.getAccountType())) {
                account.setCurrentBalance(current.add(line.getDebitAmount()).subtract(line.getCreditAmount()));
            } else {
                account.setCurrentBalance(current.add(line.getCreditAmount()).subtract(line.getDebitAmount()));
            }
            coaRepository.save(account);
        }
        JournalEntryEntity saved = journalEntryRepository.save(entry);
        recordAudit("JOURNAL", saved.getId(), "POSTED",
                saved.getEntryNumber() + " posted from DRAFT by " + actor + (selfPosted ? " (Self-Approved)" : " (Maker-Checker)"),
                List.of(new AuditService.Change("Status", "DRAFT", "POSTED"),
                        new AuditService.Change("Posted By", entry.getCreatedBy(), actor)));
        return toJournalEntryDto(saved);
    }

    public GeneralLedgerDtos.JournalEntryDto reverseJournalEntry(Long journalEntryId, GeneralLedgerDtos.ReverseJournalRequest req) {
        JournalEntryEntity original = journalEntryRepository.findById(journalEntryId)
                .orElseThrow(() -> new IllegalArgumentException("Journal Entry not found: " + journalEntryId));
        if (!"POSTED".equalsIgnoreCase(original.getStatus())) {
            throw new IllegalStateException("Only POSTED journal vouchers can be reversed (current status: " + original.getStatus() + ").");
        }
        Long existingReversal = jdbc.queryForObject(
                "SELECT COUNT(*) FROM journal_entry WHERE reference_type = 'JOURNAL_REVERSAL' AND reference_id = ? AND status <> 'CANCELLED'",
                Long.class, journalEntryId);
        if (existingReversal != null && existingReversal > 0) {
            throw new IllegalStateException("Voucher " + original.getEntryNumber() + " has already been reversed.");
        }

        LocalDate revDate = (req != null && req.reversalDate() != null) ? req.reversalDate() : LocalDate.now();
        ensurePeriodOpen(revDate);

        String reason = (req != null && req.reason() != null && !req.reason().isBlank()) ? req.reason().trim() : "Accounting Reversal";
        String actor = (req != null && req.postedBy() != null && !req.postedBy().isBlank()) ? req.postedBy().trim() : "SYSTEM";

        List<GeneralLedgerDtos.CreateLineRequest> mirrorLines = new ArrayList<>();
        for (JournalLineEntity line : original.getLines()) {
            mirrorLines.add(new GeneralLedgerDtos.CreateLineRequest(
                    line.getAccountId(),
                    null,
                    line.getCreditAmount() != null ? line.getCreditAmount() : BigDecimal.ZERO,
                    line.getDebitAmount() != null ? line.getDebitAmount() : BigDecimal.ZERO,
                    line.getPartyId(),
                    "Reversal of " + original.getEntryNumber() + (line.getLineNarration() != null && !line.getLineNarration().isBlank() ? " | " + line.getLineNarration() : ""),
                    line.getCostCenter()
            ));
        }

        GeneralLedgerDtos.CreateJournalRequest revReq = new GeneralLedgerDtos.CreateJournalRequest(
                revDate,
                "REVERSAL",
                "JOURNAL_REVERSAL",
                original.getId(),
                original.getEntryNumber(),
                "Reversal of " + original.getEntryNumber() + " — " + reason,
                actor,
                "POSTED",
                mirrorLines
        );
        GeneralLedgerDtos.JournalEntryDto reversal = postJournalEntry(revReq);
        original.setStatus("REVERSED");
        journalEntryRepository.save(original);
        recordAudit("JOURNAL", original.getId(), "REVERSED",
                "Reversed " + original.getEntryNumber() + " via " + reversal.entryNumber() + " (" + reason + ")",
                List.of(new AuditService.Change("Status", "POSTED", "REVERSED")));
        return reversal;
    }

    @Transactional(readOnly = true)
    public List<GeneralLedgerDtos.JournalEntryDto> listRecentEntries() {
        return listEntries(null, null, null, null);
    }

    @Transactional(readOnly = true)
    public List<GeneralLedgerDtos.JournalEntryDto> listEntries(LocalDate fromDate, LocalDate toDate, String entryType, String status) {
        List<JournalEntryEntity> entities;
        if (fromDate != null || toDate != null) {
            LocalDate start = fromDate != null ? fromDate : LocalDate.of(2000, 1, 1);
            LocalDate end = toDate != null ? toDate : LocalDate.of(2099, 12, 31);
            entities = journalEntryRepository.findByEntryDateBetweenOrderByEntryDateDescIdDesc(start, end);
        } else {
            entities = journalEntryRepository.findByEntryDateBetweenOrderByEntryDateDescIdDesc(
                    LocalDate.of(2000, 1, 1), LocalDate.of(2099, 12, 31));
        }
        String typeFilter = (entryType == null || entryType.isBlank() || "ALL".equalsIgnoreCase(entryType)) ? null : entryType.trim().toUpperCase(Locale.ROOT);
        String statusFilter = (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) ? null : status.trim().toUpperCase(Locale.ROOT);

        return entities.stream()
                .filter(e -> typeFilter == null || (e.getEntryType() != null && e.getEntryType().equalsIgnoreCase(typeFilter)))
                .filter(e -> statusFilter == null || (e.getStatus() != null && e.getStatus().equalsIgnoreCase(statusFilter)))
                .map(this::toJournalEntryDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public GeneralLedgerDtos.PagedJournalResponse listEntriesPage(int page, int size, LocalDate fromDate, LocalDate toDate,
                                                                  String entryType, String status, String q) {
        int safeSize = Math.max(10, Math.min(size, 200));
        int safePage = Math.max(0, page);
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        if (fromDate != null) {
            where.append(" AND je.entry_date >= ? ");
            args.add(fromDate);
        }
        if (toDate != null) {
            where.append(" AND je.entry_date <= ? ");
            args.add(toDate);
        }
        String typeFilter = (entryType == null || entryType.isBlank() || "ALL".equalsIgnoreCase(entryType)) ? null : entryType.trim().toUpperCase(Locale.ROOT);
        if (typeFilter != null) {
            where.append(" AND UPPER(COALESCE(je.entry_type, '')) = ? ");
            args.add(typeFilter);
        }
        String statusFilter = (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) ? null : status.trim().toUpperCase(Locale.ROOT);
        if (statusFilter != null) {
            where.append(" AND UPPER(COALESCE(je.status, '')) = ? ");
            args.add(statusFilter);
        }
        if (q != null && !q.isBlank()) {
            String like = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
            where.append(" AND (LOWER(COALESCE(je.entry_number,'')) LIKE ? OR LOWER(COALESCE(je.reference_no,'')) LIKE ? OR LOWER(COALESCE(je.narration,'')) LIKE ? OR EXISTS (SELECT 1 FROM journal_line jl JOIN chart_of_accounts ca ON ca.id = jl.account_id WHERE jl.journal_entry_id = je.id AND (LOWER(ca.account_code) LIKE ? OR LOWER(ca.account_name) LIKE ? OR LOWER(COALESCE(jl.line_narration,'')) LIKE ?))) ");
            for (int i = 0; i < 6; i++) args.add(like);
        }
        long total = Optional.ofNullable(jdbc.queryForObject("SELECT COUNT(*) FROM journal_entry je " + where, Long.class, args.toArray())).orElse(0L);
        int totalPages = total == 0 ? 0 : (int) Math.ceil(total / (double) safeSize);
        if (totalPages > 0 && safePage >= totalPages) safePage = totalPages - 1;
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add((long) safePage * safeSize);
        List<Long> ids = jdbc.query(
                "SELECT je.id FROM journal_entry je " + where + " ORDER BY je.entry_date DESC, je.id DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> rs.getLong(1), pageArgs.toArray());
        if (ids.isEmpty()) {
            return new GeneralLedgerDtos.PagedJournalResponse(List.of(), safePage, safeSize, total, totalPages);
        }
        Map<Long, JournalEntryEntity> byId = new HashMap<>();
        journalEntryRepository.findAllById(ids).forEach(e -> byId.put(e.getId(), e));
        List<GeneralLedgerDtos.JournalEntryDto> rows = ids.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .map(this::toJournalEntryDto)
                .toList();
        return new GeneralLedgerDtos.PagedJournalResponse(rows, safePage, safeSize, total, totalPages);
    }

    @Transactional(readOnly = true)
    public List<GeneralLedgerDtos.TrialBalanceItemDto> getTrialBalance() {
        return getTrialBalance(null, null);
    }

    @Transactional(readOnly = true)
    public List<GeneralLedgerDtos.TrialBalanceItemDto> getTrialBalance(LocalDate fromDate, LocalDate toDate) {
        List<ChartOfAccountsEntity> accounts = coaRepository.findAllByOrderByAccountCodeAsc();
        if (fromDate == null && toDate == null) {
            List<GeneralLedgerDtos.TrialBalanceItemDto> tb = new ArrayList<>();
            for (ChartOfAccountsEntity acc : accounts) {
                BigDecimal open = acc.getOpeningBalance() != null ? acc.getOpeningBalance() : BigDecimal.ZERO;
                BigDecimal current = acc.getCurrentBalance() != null ? acc.getCurrentBalance() : BigDecimal.ZERO;
                if (!Boolean.TRUE.equals(acc.getIsActive()) && open.compareTo(BigDecimal.ZERO) == 0 && current.compareTo(BigDecimal.ZERO) == 0) {
                    continue;
                }
                BigDecimal debit = BigDecimal.ZERO;
                BigDecimal credit = BigDecimal.ZERO;
                if (isDebitNormal(acc.getAccountType())) {
                    if (current.compareTo(BigDecimal.ZERO) >= 0) debit = current;
                    else credit = current.abs();
                } else {
                    if (current.compareTo(BigDecimal.ZERO) >= 0) credit = current;
                    else debit = current.abs();
                }
                tb.add(new GeneralLedgerDtos.TrialBalanceItemDto(
                        acc.getAccountCode(), acc.getAccountName(), acc.getAccountType(),
                        open, debit, credit, current
                ));
            }
            return tb;
        }

        LocalDate start = fromDate != null ? fromDate : LocalDate.of(2000, 1, 1);
        LocalDate end = toDate != null ? toDate : LocalDate.of(2099, 12, 31);

        Map<Long, BigDecimal[]> priorMap = loadAccountMovementSums(null, start.minusDays(1));
        Map<Long, BigDecimal[]> periodMap = loadAccountMovementSums(start, end);

        List<GeneralLedgerDtos.TrialBalanceItemDto> tb = new ArrayList<>();
        for (ChartOfAccountsEntity acc : accounts) {
            BigDecimal baseOpen = acc.getOpeningBalance() != null ? acc.getOpeningBalance() : BigDecimal.ZERO;
            BigDecimal[] prior = priorMap.getOrDefault(acc.getId(), new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            BigDecimal[] period = periodMap.getOrDefault(acc.getId(), new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            boolean drNormal = isDebitNormal(acc.getAccountType());
            BigDecimal opening = drNormal
                    ? baseOpen.add(prior[0]).subtract(prior[1])
                    : baseOpen.add(prior[1]).subtract(prior[0]);
            BigDecimal periodDr = period[0];
            BigDecimal periodCr = period[1];
            BigDecimal closing = drNormal
                    ? opening.add(periodDr).subtract(periodCr)
                    : opening.add(periodCr).subtract(periodDr);
            if (!Boolean.TRUE.equals(acc.getIsActive())
                    && opening.compareTo(BigDecimal.ZERO) == 0
                    && periodDr.compareTo(BigDecimal.ZERO) == 0
                    && periodCr.compareTo(BigDecimal.ZERO) == 0
                    && closing.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            tb.add(new GeneralLedgerDtos.TrialBalanceItemDto(
                    acc.getAccountCode(), acc.getAccountName(), acc.getAccountType(),
                    opening, periodDr, periodCr, closing
            ));
        }
        return tb;
    }

    private Map<Long, BigDecimal[]> loadAccountMovementSums(LocalDate fromDate, LocalDate toDate) {
        Map<Long, BigDecimal[]> map = new HashMap<>();
        StringBuilder sql = new StringBuilder(
                "SELECT jl.account_id, COALESCE(SUM(jl.debit_amount), 0) AS dr, COALESCE(SUM(jl.credit_amount), 0) AS cr " +
                "FROM journal_line jl JOIN journal_entry je ON je.id = jl.journal_entry_id " +
                "WHERE UPPER(COALESCE(je.status, '')) IN ('POSTED', 'REVERSED') ");
        List<Object> params = new ArrayList<>();
        if (fromDate != null) {
            sql.append("AND je.entry_date >= ? ");
            params.add(fromDate);
        }
        if (toDate != null) {
            sql.append("AND je.entry_date <= ? ");
            params.add(toDate);
        }
        sql.append("GROUP BY jl.account_id");
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), params.toArray());
        for (Map<String, Object> r : rows) {
            if (r.get("account_id") == null) continue;
            Long accId = ((Number) r.get("account_id")).longValue();
            map.put(accId, new BigDecimal[]{toBigDecimal(r.get("dr")), toBigDecimal(r.get("cr"))});
        }
        return map;
    }

    @Transactional(readOnly = true)
    public GeneralLedgerDtos.AccountLedgerResponse getAccountLedger(String accountCode, LocalDate fromDate, LocalDate toDate) {
        ChartOfAccountsEntity account = coaRepository.findByAccountCode(accountCode == null ? "" : accountCode.trim())
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountCode));
        boolean drNormal = isDebitNormal(account.getAccountType());

        BigDecimal baseOpening = account.getOpeningBalance() != null ? account.getOpeningBalance() : BigDecimal.ZERO;
        BigDecimal opening = baseOpening;
        if (fromDate != null) {
            Map<Long, BigDecimal[]> prior = loadAccountMovementSums(null, fromDate.minusDays(1));
            BigDecimal[] p = prior.getOrDefault(account.getId(), new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            opening = drNormal ? baseOpening.add(p[0]).subtract(p[1]) : baseOpening.add(p[1]).subtract(p[0]);
        }

        StringBuilder sql = new StringBuilder(
                "SELECT je.id AS je_id, je.entry_number, je.entry_date, je.entry_type, je.reference_type, je.reference_id, " +
                "je.reference_no, je.narration, jl.line_narration, jl.debit_amount, jl.credit_amount, je.status, je.posted_by " +
                "FROM journal_line jl JOIN journal_entry je ON je.id = jl.journal_entry_id " +
                "WHERE jl.account_id = ? AND UPPER(COALESCE(je.status, '')) IN ('POSTED', 'REVERSED') ");
        List<Object> params = new ArrayList<>();
        params.add(account.getId());
        if (fromDate != null) {
            sql.append("AND je.entry_date >= ? ");
            params.add(fromDate);
        }
        if (toDate != null) {
            sql.append("AND je.entry_date <= ? ");
            params.add(toDate);
        }
        sql.append("ORDER BY je.entry_date ASC, je.id ASC, jl.line_number ASC");

        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), params.toArray());
        BigDecimal running = opening;
        BigDecimal totalDr = BigDecimal.ZERO;
        BigDecimal totalCr = BigDecimal.ZERO;
        List<GeneralLedgerDtos.AccountLedgerLineDto> lines = new ArrayList<>();

        for (Map<String, Object> r : rows) {
            BigDecimal dr = toBigDecimal(r.get("debit_amount"));
            BigDecimal cr = toBigDecimal(r.get("credit_amount"));
            totalDr = totalDr.add(dr);
            totalCr = totalCr.add(cr);
            running = drNormal ? running.add(dr).subtract(cr) : running.add(cr).subtract(dr);
            String balType = resolveBalanceSide(drNormal, running);
            LocalDate eDate = parseLocalDate(r.get("entry_date"));
            Long jeId = r.get("je_id") instanceof Number n ? n.longValue() : null;
            Long refId = r.get("reference_id") instanceof Number n ? n.longValue() : null;
            lines.add(new GeneralLedgerDtos.AccountLedgerLineDto(
                    jeId,
                    str(r.get("entry_number")),
                    eDate,
                    str(r.get("entry_type")),
                    str(r.get("reference_type")),
                    refId,
                    str(r.get("reference_no")),
                    str(r.get("narration")),
                    str(r.get("line_narration")),
                    dr,
                    cr,
                    running.abs(),
                    balType,
                    str(r.get("status")),
                    str(r.get("posted_by"))
            ));
        }

        return new GeneralLedgerDtos.AccountLedgerResponse(
                toAccountDto(account),
                fromDate,
                toDate,
                opening,
                totalDr,
                totalCr,
                running.abs(),
                resolveBalanceSide(drNormal, running),
                lines
        );
    }

    @Transactional(readOnly = true)
    public GeneralLedgerDtos.ProfitAndLossResponse getProfitAndLoss(LocalDate fromDate, LocalDate toDate) {
        List<ChartOfAccountsEntity> accounts = coaRepository.findAllByOrderByAccountCodeAsc();
        Map<Long, BigDecimal[]> periodMap = (fromDate != null || toDate != null)
                ? loadAccountMovementSums(fromDate, toDate)
                : null;

        List<GeneralLedgerDtos.ProfitAndLossLineDto> revenues = new ArrayList<>();
        List<GeneralLedgerDtos.ProfitAndLossLineDto> directExpenses = new ArrayList<>();
        List<GeneralLedgerDtos.ProfitAndLossLineDto> indirectExpenses = new ArrayList<>();
        BigDecimal totalRev = BigDecimal.ZERO;
        BigDecimal totalDirect = BigDecimal.ZERO;
        BigDecimal totalIndirect = BigDecimal.ZERO;

        for (ChartOfAccountsEntity acc : accounts) {
            String type = acc.getAccountType() == null ? "" : acc.getAccountType().toUpperCase(Locale.ROOT);
            if (!"REVENUE".equals(type) && !"EXPENSE".equals(type)) continue;
            BigDecimal amt;
            if (periodMap != null) {
                BigDecimal[] m = periodMap.getOrDefault(acc.getId(), new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                amt = "REVENUE".equals(type) ? m[1].subtract(m[0]) : m[0].subtract(m[1]);
            } else {
                amt = acc.getCurrentBalance() != null ? acc.getCurrentBalance() : BigDecimal.ZERO;
            }
            if (amt.compareTo(BigDecimal.ZERO) == 0) continue;
            GeneralLedgerDtos.ProfitAndLossLineDto line = new GeneralLedgerDtos.ProfitAndLossLineDto(
                    acc.getAccountCode(), acc.getAccountName(),
                    acc.getAccountSubtype() != null ? acc.getAccountSubtype() : "GENERAL", amt);
            if ("REVENUE".equals(type)) {
                revenues.add(line);
                totalRev = totalRev.add(amt);
            } else if (isDirectExpense(acc)) {
                directExpenses.add(line);
                totalDirect = totalDirect.add(amt);
            } else {
                indirectExpenses.add(line);
                totalIndirect = totalIndirect.add(amt);
            }
        }

        BigDecimal grossProfit = totalRev.subtract(totalDirect);
        BigDecimal netProfit = grossProfit.subtract(totalIndirect);
        BigDecimal grossMargin = totalRev.compareTo(BigDecimal.ZERO) > 0
                ? grossProfit.multiply(BigDecimal.valueOf(100)).divide(totalRev, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal netMargin = totalRev.compareTo(BigDecimal.ZERO) > 0
                ? netProfit.multiply(BigDecimal.valueOf(100)).divide(totalRev, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return new GeneralLedgerDtos.ProfitAndLossResponse(
                fromDate, toDate, revenues, directExpenses, indirectExpenses,
                totalRev, totalDirect, grossProfit, grossMargin, totalIndirect, netProfit, netMargin
        );
    }

    @Transactional(readOnly = true)
    public GeneralLedgerDtos.BalanceSheetResponse getBalanceSheet(LocalDate asOfDate) {
        List<ChartOfAccountsEntity> accounts = coaRepository.findAllByOrderByAccountCodeAsc();
        Map<Long, BigDecimal[]> upToMap = asOfDate != null ? loadAccountMovementSums(null, asOfDate) : null;

        List<GeneralLedgerDtos.BalanceSheetLineDto> assets = new ArrayList<>();
        List<GeneralLedgerDtos.BalanceSheetLineDto> liabilities = new ArrayList<>();
        List<GeneralLedgerDtos.BalanceSheetLineDto> equities = new ArrayList<>();
        BigDecimal totalAssets = BigDecimal.ZERO;
        BigDecimal totalLiabilities = BigDecimal.ZERO;
        BigDecimal totalEquity = BigDecimal.ZERO;
        BigDecimal totalRev = BigDecimal.ZERO;
        BigDecimal totalExp = BigDecimal.ZERO;

        for (ChartOfAccountsEntity acc : accounts) {
            String type = acc.getAccountType() == null ? "" : acc.getAccountType().toUpperCase(Locale.ROOT);
            BigDecimal bal;
            if (upToMap != null) {
                BigDecimal baseOpen = acc.getOpeningBalance() != null ? acc.getOpeningBalance() : BigDecimal.ZERO;
                BigDecimal[] m = upToMap.getOrDefault(acc.getId(), new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                bal = isDebitNormal(type) ? baseOpen.add(m[0]).subtract(m[1]) : baseOpen.add(m[1]).subtract(m[0]);
            } else {
                bal = acc.getCurrentBalance() != null ? acc.getCurrentBalance() : BigDecimal.ZERO;
            }
            if (bal.compareTo(BigDecimal.ZERO) == 0 && !Set.of("ASSET", "LIABILITY", "EQUITY").contains(type)) continue;
            switch (type) {
                case "ASSET" -> {
                    if (bal.compareTo(BigDecimal.ZERO) != 0) assets.add(new GeneralLedgerDtos.BalanceSheetLineDto(acc.getAccountCode(), acc.getAccountName(), acc.getAccountSubtype(), bal));
                    totalAssets = totalAssets.add(bal);
                }
                case "LIABILITY" -> {
                    if (bal.compareTo(BigDecimal.ZERO) != 0) liabilities.add(new GeneralLedgerDtos.BalanceSheetLineDto(acc.getAccountCode(), acc.getAccountName(), acc.getAccountSubtype(), bal));
                    totalLiabilities = totalLiabilities.add(bal);
                }
                case "EQUITY" -> {
                    if (bal.compareTo(BigDecimal.ZERO) != 0) equities.add(new GeneralLedgerDtos.BalanceSheetLineDto(acc.getAccountCode(), acc.getAccountName(), acc.getAccountSubtype(), bal));
                    totalEquity = totalEquity.add(bal);
                }
                case "REVENUE" -> totalRev = totalRev.add(bal);
                case "EXPENSE" -> totalExp = totalExp.add(bal);
            }
        }

        BigDecimal currentNetProfit = totalRev.subtract(totalExp);
        BigDecimal totalLiabAndEq = totalLiabilities.add(totalEquity).add(currentNetProfit);
        BigDecimal diff = totalAssets.subtract(totalLiabAndEq).setScale(2, RoundingMode.HALF_UP);
        boolean balanced = diff.abs().compareTo(new BigDecimal("0.05")) <= 0;

        return new GeneralLedgerDtos.BalanceSheetResponse(
                asOfDate, assets, liabilities, equities,
                totalAssets, totalLiabilities, totalEquity, currentNetProfit,
                totalLiabAndEq, balanced, diff
        );
    }

    @Transactional(readOnly = true)
    public GeneralLedgerDtos.FinancialStatementDto getFinancialStatements() {
        return getFinancialStatements(null, null);
    }

    @Transactional(readOnly = true)
    public GeneralLedgerDtos.FinancialStatementDto getFinancialStatements(LocalDate fromDate, LocalDate toDate) {
        if (fromDate == null && toDate == null) {
            List<ChartOfAccountsEntity> accounts = coaRepository.findAllByOrderByAccountCodeAsc();
            BigDecimal totalRevenue = BigDecimal.ZERO;
            BigDecimal totalExpense = BigDecimal.ZERO;
            BigDecimal totalAssets = BigDecimal.ZERO;
            BigDecimal totalLiabilities = BigDecimal.ZERO;
            BigDecimal totalEquity = BigDecimal.ZERO;

            for (ChartOfAccountsEntity acc : accounts) {
                BigDecimal bal = acc.getCurrentBalance() != null ? acc.getCurrentBalance() : BigDecimal.ZERO;
                switch (acc.getAccountType().toUpperCase(Locale.ROOT)) {
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
        GeneralLedgerDtos.ProfitAndLossResponse pl = getProfitAndLoss(fromDate, toDate);
        GeneralLedgerDtos.BalanceSheetResponse bs = getBalanceSheet(toDate);
        BigDecimal totalExpense = pl.totalDirectExpense().add(pl.totalIndirectExpense());
        return new GeneralLedgerDtos.FinancialStatementDto(
                pl.totalRevenue(), totalExpense, pl.netProfit(),
                bs.totalAssets(), bs.totalLiabilities(), bs.totalEquity()
        );
    }

    private static boolean isDebitNormal(String accountType) {
        return "ASSET".equalsIgnoreCase(accountType) || "EXPENSE".equalsIgnoreCase(accountType);
    }

    private static String resolveBalanceSide(boolean drNormal, BigDecimal signedBalance) {
        if (signedBalance == null || signedBalance.compareTo(BigDecimal.ZERO) == 0) return drNormal ? "Dr" : "Cr";
        if (drNormal) return signedBalance.compareTo(BigDecimal.ZERO) >= 0 ? "Dr" : "Cr";
        return signedBalance.compareTo(BigDecimal.ZERO) >= 0 ? "Cr" : "Dr";
    }

    private static boolean isDirectExpense(ChartOfAccountsEntity acc) {
        String sub = acc.getAccountSubtype() == null ? "" : acc.getAccountSubtype().toUpperCase(Locale.ROOT);
        String name = acc.getAccountName() == null ? "" : acc.getAccountName().toUpperCase(Locale.ROOT);
        String code = acc.getAccountCode() == null ? "" : acc.getAccountCode().trim();
        return sub.contains("COGS") || sub.contains("DIRECT") || sub.contains("PURCHASE")
                || name.contains("COST OF GOODS") || name.contains("PURCHASE") || name.contains("FREIGHT IN")
                || code.startsWith("50");
    }

    private static LocalDate parseLocalDate(Object val) {
        if (val == null) return null;
        if (val instanceof LocalDate ld) return ld;
        String s = val.toString().trim();
        if (s.length() >= 10) {
            try { return LocalDate.parse(s.substring(0, 10)); } catch (Exception ignored) {}
        }
        return null;
    }

    private static String str(Object val) {
        return val == null ? "" : val.toString();
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
        } catch (IllegalStateException locked) {
            throw locked;
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
        } catch (IllegalStateException locked) {
            throw locked;
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
        } catch (IllegalStateException locked) {
            throw locked;
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
        } catch (IllegalStateException locked) {
            throw locked;
        } catch (Exception e) {
            java.lang.System.getLogger("org.example").log(java.lang.System.Logger.Level.WARNING, "Failed to auto-post purchase payment journal: " + e.getMessage(), e);
            return null;
        }
    }

    public void cancelJournalEntriesForReference(String referenceType, Long referenceId, String user) {
        if (referenceType == null || referenceId == null) return;
        try {
            List<Map<String, Object>> existingEntries = jdbc.queryForList(
                    "SELECT id, entry_date, status FROM journal_entry WHERE reference_type = ? AND reference_id = ? AND status <> 'CANCELLED'",
                    referenceType, referenceId
            );
            for (Map<String, Object> existing : existingEntries) {
                LocalDate eDate = parseLocalDate(existing.get("entry_date"));
                if (eDate != null) {
                    ensurePeriodOpen(eDate);
                }
            }
            for (Map<String, Object> existing : existingEntries) {
                Long eId = ((Number) existing.get("id")).longValue();
                String status = Objects.toString(existing.get("status"), "").toUpperCase(Locale.ROOT);
                // Only subtract current_balance for live POSTED vouchers (DRAFT never moved balances; REVERSED already has a contra entry).
                if ("POSTED".equals(status)) {
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
                            if (isDebitNormal(acc.getAccountType())) {
                                acc.setCurrentBalance(cur.subtract(deb).add(cred));
                            } else {
                                acc.setCurrentBalance(cur.subtract(cred).add(deb));
                            }
                            coaRepository.save(acc);
                        }
                    }
                } else if ("REVERSED".equals(status)) {
                    // Cancel any linked contra reversal entry atomically as well without distorting net balance
                    cancelJournalEntriesForReference("JOURNAL_REVERSAL", eId, user);
                }
                jdbc.update("UPDATE journal_entry SET status = 'CANCELLED', updated_by = ?, updated_at = NOW() WHERE id = ?",
                        user != null ? user : "SYSTEM", eId);
            }
        } catch (IllegalStateException locked) {
            throw locked;
        } catch (Exception e) {
            java.lang.System.getLogger("org.example").log(java.lang.System.Logger.Level.WARNING, "Error cancelling journal entry: " + e.getMessage(), e);
        }
    }

    public Long getAccountIdOrDefault(String code, String name, String type) {
        try {
            Long id = jdbc.queryForObject("SELECT id FROM chart_of_accounts WHERE account_code = ? LIMIT 1", Long.class, code);
            if (id != null) return id;
        } catch (org.springframework.dao.EmptyResultDataAccessException | jakarta.persistence.NoResultException ignored) {
            // Auto-provision missing statutory or default COA account below when query returns no rows.
        }
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
                   COALESCE(sh.reference_no, '') AS reference_no,
                   COALESCE(sh.payment_status, 'UNPAID') AS payment_status,
                   COALESCE(sh.paid_amount, 0) AS paid_amount,
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
        String refNo = Objects.toString(h.get("reference_no"), "");
        String paymentStatus = Objects.toString(h.get("payment_status"), "UNPAID");
        BigDecimal paidAmount = toBigDecimal(h.get("paid_amount"));
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
        VoucherMeta meta = fetchVoucherMeta("SALE", salesId, invoiceNo);
        String jvNo = meta.entryNumber();
        if (vLines.isEmpty()) {
            jvNo = "PROSPECTIVE (ON APPROVAL)";
            vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("1040", "Sundry Debtors (Trade Receivables)", total, BigDecimal.ZERO, "Debit Customer: " + partyName));
            if (discount.compareTo(BigDecimal.ZERO) > 0) {
                vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("5050", "Discounts & Rounding Adjustments", discount, BigDecimal.ZERO, "Debit Discount Allowed"));
            }
            vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine(isInterstate ? "4020" : "4010", isInterstate ? "Inter-State Sales Revenue" : "Domestic Sales Revenue", BigDecimal.ZERO, taxable, "Credit Revenue: " + invoiceNo));
            if (totalTax.compareTo(BigDecimal.ZERO) > 0) {
                if (igst.compareTo(BigDecimal.ZERO) > 0) {
                    vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("2083", "Output IGST Payable", BigDecimal.ZERO, igst, "Credit Output IGST"));
                } else {
                    vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("2081", "Output CGST Payable", BigDecimal.ZERO, cgst, "Credit Output CGST"));
                    vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("2082", "Output SGST Payable", BigDecimal.ZERO, sgst, "Credit Output SGST"));
                }
            }
            BigDecimal diff = total.add(discount).subtract(taxable.add(totalTax));
            if (diff.compareTo(BigDecimal.ZERO) > 0) {
                vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("5050", "Discounts & Rounding Adjustments", BigDecimal.ZERO, diff, "Round off credit"));
            } else if (diff.compareTo(BigDecimal.ZERO) < 0) {
                vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("5050", "Discounts & Rounding Adjustments", diff.abs(), BigDecimal.ZERO, "Round off debit"));
            }
        }

        BigDecimal totDeb = vLines.stream().map(GeneralLedgerDtos.PostingFlowVoucherLine::debitAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totCred = vLines.stream().map(GeneralLedgerDtos.PostingFlowVoucherLine::creditAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

        // Inventory movements
        List<GeneralLedgerDtos.PostingFlowInventoryLine> invLines = new ArrayList<>();
        List<Map<String, Object>> lines = jdbc.queryForList("""
            SELECT sl.item_code, COALESCE(sl.item_description_snapshot, im.description, sl.item_code) AS descr,
                   sl.quantity, COALESCE(im.unit, 'Nos') AS unit, COALESCE(sl.unit_cost_snapshot, 0) AS unit_cost,
                   COALESCE(sl.rate, 0) AS rate, COALESCE(sl.discount_percent, 0) AS discount_percent,
                   COALESCE(sl.line_total, 0) AS line_total, COALESCE(im.opening_stock, 0) AS current_stock
            FROM sales_line sl
            LEFT JOIN item_master im ON im.item_code = sl.item_code
            WHERE sl.sales_id = ? ORDER BY sl.id ASC
            """, salesId);

        BigDecimal orderTaxableRevenue = BigDecimal.ZERO;
        BigDecimal orderInventoryCost = BigDecimal.ZERO;
        BigDecimal orderTotalProfit = BigDecimal.ZERO;
        BigDecimal orderTotalDiscount = BigDecimal.ZERO;

        for (Map<String, Object> l : lines) {
            BigDecimal q = toBigDecimal(l.get("quantity"));
            BigDecimal uc = toBigDecimal(l.get("unit_cost"));
            BigDecimal grossRate = toBigDecimal(l.get("rate"));
            BigDecimal discPct = toBigDecimal(l.get("discount_percent"));
            BigDecimal lineTot = toBigDecimal(l.get("line_total"));
            BigDecimal currentStock = toBigDecimal(l.get("current_stock"));

            // Stock before and after order
            // Sales decrement warehouse inventory, so before order stock was currentStock + q
            BigDecimal stockAfter = currentStock;
            BigDecimal stockBefore = currentStock.add(q);

            // Effective selling rate per unit (respecting item-level discounts)
            BigDecimal sellingRate = (q.compareTo(BigDecimal.ZERO) > 0 && lineTot.compareTo(BigDecimal.ZERO) > 0)
                    ? lineTot.divide(q, 2, RoundingMode.HALF_UP)
                    : grossRate;
            BigDecimal lineRev = lineTot.compareTo(BigDecimal.ZERO) > 0 ? lineTot : q.multiply(sellingRate).setScale(2, RoundingMode.HALF_UP);
            BigDecimal grossLineTotal = q.multiply(grossRate).setScale(2, RoundingMode.HALF_UP);
            BigDecimal lineDiscount = grossLineTotal.subtract(lineRev).max(BigDecimal.ZERO);
            orderTotalDiscount = orderTotalDiscount.add(lineDiscount);

            BigDecimal lineCost = q.multiply(uc).setScale(2, RoundingMode.HALF_UP);
            BigDecimal lineProfit = lineRev.subtract(lineCost);
            BigDecimal marginPct = (lineRev.compareTo(BigDecimal.ZERO) > 0)
                    ? lineProfit.multiply(BigDecimal.valueOf(100)).divide(lineRev, 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            orderTaxableRevenue = orderTaxableRevenue.add(lineRev);
            orderInventoryCost = orderInventoryCost.add(lineCost);
            orderTotalProfit = orderTotalProfit.add(lineProfit);

            invLines.add(new GeneralLedgerDtos.PostingFlowInventoryLine(
                    Objects.toString(l.get("item_code"), ""),
                    Objects.toString(l.get("descr"), ""),
                    "OUTWARD_SALE (STOCK DECREASED)",
                    q.negate(),
                    Objects.toString(l.get("unit"), "Nos"),
                    uc,
                    lineCost.negate(),
                    stockBefore,
                    stockAfter,
                    grossRate,
                    discPct,
                    lineDiscount,
                    sellingRate,
                    lineRev,
                    lineProfit,
                    marginPct
            ));
        }

        BigDecimal orderMarginPct = (orderTaxableRevenue.compareTo(BigDecimal.ZERO) > 0)
                ? orderTotalProfit.multiply(BigDecimal.valueOf(100)).divide(orderTaxableRevenue, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // GST Impact
        boolean b2b = gstin != null && gstin.trim().length() == 15;
        String gstr1Table = b2b ? "Table 4A - B2B Taxable Invoices" : "Table 7 - B2C Others";
        GeneralLedgerDtos.PostingFlowGstImpact gst = new GeneralLedgerDtos.PostingFlowGstImpact(
                "GSTR-1 & GSTR-3B",
                gstr1Table + " & GSTR-3B Table 3.1(a) (Outward Taxable Supplies)",
                taxable, cgst, sgst, igst, totalTax,
                "CGST Act Sec 9 / IGST Act Sec 5: Statutory output tax levied on outward forward-charge supplies"
        );

        BigDecimal totalDiscountConcession = orderTotalDiscount.max(discount);

        return new GeneralLedgerDtos.PostingFlowResponse(
                "SALES_INVOICE", invoiceNo, date, partyName, gstin, total,
                "STANDARD_SALES_INVOICE",
                "Standard Sales Invoice: Debits Customer AR, Credits Revenue and Output GST liability. Reduces warehouse inventory.",
                jvNo, vLines, totDeb, totCred, totDeb.compareTo(totCred) == 0,
                invLines, gst,
                orderTaxableRevenue, orderInventoryCost, orderTotalProfit, orderMarginPct,
                totalDiscountConcession, meta.postedAt(), meta.postedBy(), meta.triggerEvent(),
                refNo, paymentStatus, paidAmount
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
                   COALESCE(ph.reference_no, '') AS reference_no,
                   COALESCE(ph.payment_status, 'UNPAID') AS payment_status,
                   COALESCE(ph.paid_amount, 0) AS paid_amount,
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
        String refNo = Objects.toString(h.get("reference_no"), "");
        String paymentStatus = Objects.toString(h.get("payment_status"), "UNPAID");
        BigDecimal paidAmount = toBigDecimal(h.get("paid_amount"));
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
        VoucherMeta meta = fetchVoucherMeta("PURCHASE", purchaseId, invoiceNo);
        String jvNo = meta.entryNumber();
        if (vLines.isEmpty()) {
            jvNo = "PROSPECTIVE (ON APPROVAL)";
            vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("5020", "Raw Material Purchases", taxable, BigDecimal.ZERO, "Debit Purchases: " + invoiceNo));
            if (totalTax.compareTo(BigDecimal.ZERO) > 0) {
                if (igst.compareTo(BigDecimal.ZERO) > 0) {
                    vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("1083", "Input IGST Pool", igst, BigDecimal.ZERO, "Debit Input IGST"));
                } else {
                    vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("1081", "Input CGST Pool", cgst, BigDecimal.ZERO, "Debit Input CGST"));
                    vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("1082", "Input SGST Pool", sgst, BigDecimal.ZERO, "Debit Input SGST"));
                }
            }
            vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("2010", "Sundry Creditors (Trade Payables)", BigDecimal.ZERO, total, "Credit Supplier: " + partyName));
            if (discount.compareTo(BigDecimal.ZERO) > 0) {
                vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("4090", "Discounts Received", BigDecimal.ZERO, discount, "Credit Discount Received"));
            }
            BigDecimal diff = taxable.add(totalTax).subtract(total.add(discount));
            if (diff.compareTo(BigDecimal.ZERO) > 0) {
                vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("4090", "Discounts & Rounding Adjustments", BigDecimal.ZERO, diff, "Round off credit"));
            } else if (diff.compareTo(BigDecimal.ZERO) < 0) {
                vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("4090", "Discounts & Rounding Adjustments", diff.abs(), BigDecimal.ZERO, "Round off debit"));
            }
        }

        BigDecimal totDeb = vLines.stream().map(GeneralLedgerDtos.PostingFlowVoucherLine::debitAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totCred = vLines.stream().map(GeneralLedgerDtos.PostingFlowVoucherLine::creditAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

        // Inventory movements
        List<GeneralLedgerDtos.PostingFlowInventoryLine> invLines = new ArrayList<>();
        List<Map<String, Object>> lines = jdbc.queryForList("""
            SELECT pl.item_code, COALESCE(pl.item_description_snapshot, im.description, pl.item_code) AS descr,
                   pl.quantity, COALESCE(im.unit, 'Nos') AS unit, COALESCE(pl.rate, 0) AS rate,
                   COALESCE(pl.discount_percent, 0) AS discount_percent,
                   COALESCE(pl.line_total, 0) AS line_total, COALESCE(im.opening_stock, 0) AS current_stock
            FROM purchase_line pl
            LEFT JOIN item_master im ON im.item_code = pl.item_code
            WHERE pl.purchase_id = ? ORDER BY pl.id ASC
            """, purchaseId);

        BigDecimal orderInwardValuation = BigDecimal.ZERO;
        BigDecimal orderTotalDiscount = BigDecimal.ZERO;

        for (Map<String, Object> l : lines) {
            BigDecimal q = toBigDecimal(l.get("quantity"));
            BigDecimal grossRate = toBigDecimal(l.get("rate"));
            BigDecimal discPct = toBigDecimal(l.get("discount_percent"));
            BigDecimal lineTot = toBigDecimal(l.get("line_total"));
            BigDecimal currentStock = toBigDecimal(l.get("current_stock"));

            // Purchases increment inventory, so before inward order stock was currentStock - q
            BigDecimal stockAfter = currentStock;
            BigDecimal stockBefore = currentStock.subtract(q);

            BigDecimal inwardRate = (q.compareTo(BigDecimal.ZERO) > 0 && lineTot.compareTo(BigDecimal.ZERO) > 0)
                    ? lineTot.divide(q, 2, RoundingMode.HALF_UP)
                    : grossRate;
            BigDecimal valuationDelta = lineTot.compareTo(BigDecimal.ZERO) > 0
                    ? lineTot
                    : q.multiply(inwardRate).setScale(2, RoundingMode.HALF_UP);
            BigDecimal grossLineTotal = q.multiply(grossRate).setScale(2, RoundingMode.HALF_UP);
            BigDecimal lineDiscount = grossLineTotal.subtract(valuationDelta).max(BigDecimal.ZERO);
            orderTotalDiscount = orderTotalDiscount.add(lineDiscount);

            orderInwardValuation = orderInwardValuation.add(valuationDelta);

            invLines.add(new GeneralLedgerDtos.PostingFlowInventoryLine(
                    Objects.toString(l.get("item_code"), ""),
                    Objects.toString(l.get("descr"), ""),
                    "INWARD_PURCHASE (STOCK INCREASED)",
                    q,
                    Objects.toString(l.get("unit"), "Nos"),
                    inwardRate,
                    valuationDelta,
                    stockBefore,
                    stockAfter,
                    grossRate,
                    discPct,
                    lineDiscount,
                    inwardRate,
                    valuationDelta,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO
            ));
        }

        // GST Impact
        GeneralLedgerDtos.PostingFlowGstImpact gst = new GeneralLedgerDtos.PostingFlowGstImpact(
                "GSTR-3B & GSTR-2B",
                "Table 4(A)(5) - All Other ITC (Auto-drafted from GSTR-2B)",
                taxable, cgst, sgst, igst, totalTax,
                "CGST Act Sec 16: Statutory ITC claim eligible upon receipt of tax invoice and goods/services"
        );

        BigDecimal totalDiscountConcession = orderTotalDiscount.max(discount);

        return new GeneralLedgerDtos.PostingFlowResponse(
                "PURCHASE_BILL", invoiceNo, date, partyName, gstin, total,
                "STANDARD_PURCHASE_BILL",
                "Standard Purchase Bill: Debits Purchase Expenses and Input GST Asset, Credits Supplier AP. Increases warehouse inventory.",
                jvNo, vLines, totDeb, totCred, totDeb.compareTo(totCred) == 0,
                invLines, gst,
                taxable, orderInwardValuation, BigDecimal.ZERO, BigDecimal.ZERO,
                totalDiscountConcession, meta.postedAt(), meta.postedBy(), meta.triggerEvent(),
                refNo, paymentStatus, paidAmount
        );
    }

    private GeneralLedgerDtos.PostingFlowResponse buildSalesReturnPostingFlow(String no) {
        // Query return_register first
        List<Map<String, Object>> retRows = jdbc.queryForList("""
            SELECT r.id, r.return_no, r.return_date, r.invoice_no, r.party_id, r.item_code,
                   r.quantity, r.amount, r.reason, r.status, pm.name AS party_name, COALESCE(pm.gstin, '') AS party_gstin,
                   COALESCE(im.opening_stock, 0) AS current_stock, COALESCE(im.unit, 'Nos') AS unit,
                   COALESCE(NULLIF(im.description, ''), r.item_code) AS descr
            FROM return_register r
            LEFT JOIN party_master pm ON pm.id = r.party_id
            LEFT JOIN item_master im ON im.item_code = r.item_code
            WHERE r.return_no = ?
            ORDER BY r.id ASC
            """, no);

        String retNo = no;
        LocalDate date = LocalDate.now();
        String partyName = "Customer";
        String gstin = "";
        BigDecimal total = BigDecimal.ZERO;
        String reason = "";
        String origInv = "";
        List<GeneralLedgerDtos.PostingFlowInventoryLine> invLines = new ArrayList<>();
        BigDecimal orderTotalRevClawback = BigDecimal.ZERO;
        BigDecimal orderTotalCostReinstated = BigDecimal.ZERO;

        if (!retRows.isEmpty()) {
            Map<String, Object> first = retRows.getFirst();
            retNo = Objects.toString(first.get("return_no"), no);
            date = parseDate(first.get("return_date"));
            partyName = Objects.toString(first.get("party_name"), "Customer");
            gstin = Objects.toString(first.get("party_gstin"), "");
            reason = Objects.toString(first.get("reason"), "");
            origInv = Objects.toString(first.get("invoice_no"), "");

            for (Map<String, Object> r : retRows) {
                BigDecimal q = toBigDecimal(r.get("quantity"));
                BigDecimal a = toBigDecimal(r.get("amount"));
                BigDecimal currentStock = toBigDecimal(r.get("current_stock"));
                String u = Objects.toString(r.get("unit"), "Nos");
                String d = Objects.toString(r.get("descr"), "");
                total = total.add(a);
                String lineReason = Objects.toString(r.get("reason"), reason);
                boolean physical = isPhysicalStockReturnReason(lineReason);

                BigDecimal retRate = q.compareTo(BigDecimal.ZERO) > 0 ? a.divide(q, 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
                BigDecimal stockBefore = physical ? currentStock.subtract(q) : currentStock;
                BigDecimal stockAfter = currentStock;

                BigDecimal lineRev = a.negate();
                BigDecimal lineCost = physical ? a : BigDecimal.ZERO;
                BigDecimal lineProfit = physical ? BigDecimal.ZERO : lineRev;

                orderTotalRevClawback = orderTotalRevClawback.add(lineRev);
                orderTotalCostReinstated = orderTotalCostReinstated.add(lineCost);

                invLines.add(new GeneralLedgerDtos.PostingFlowInventoryLine(
                        Objects.toString(r.get("item_code"), ""),
                        d + " (" + lineReason + ")",
                        physical ? "INWARD_RESTOCK (STOCK REINSTATED)" : "ZERO_MOVEMENT (PRICE / REBATE ADJUSTMENT)",
                        physical ? q : BigDecimal.ZERO,
                        u,
                        retRate,
                        physical ? a : BigDecimal.ZERO,
                        stockBefore,
                        stockAfter,
                        retRate,
                        lineRev,
                        lineProfit,
                        BigDecimal.ZERO
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
            Long cnId = ((Number) cn.get("id")).longValue();
            retNo = Objects.toString(cn.get("credit_note_no"), no);
            origInv = Objects.toString(cn.get("original_invoice_no"), "");
            date = parseDate(cn.get("note_date"));
            partyName = Objects.toString(cn.get("party_name"), "Customer");
            gstin = Objects.toString(cn.get("party_gstin"), "");
            total = toBigDecimal(cn.get("total_amount"));
            reason = Objects.toString(cn.get("reason_code"), "") + " " + Objects.toString(cn.get("notes"), "");
            boolean stockMoved = Boolean.TRUE.equals(cn.get("stock_returned"));
            BigDecimal taxAmt = toBigDecimal(cn.get("taxable_amount"));

            List<Map<String, Object>> cnLines = jdbc.queryForList("""
                SELECT cnl.item_code, COALESCE(cnl.item_description, im.description, cnl.item_code) AS descr,
                       cnl.quantity, COALESCE(im.unit, 'Nos') AS unit, cnl.unit_price,
                       cnl.taxable_value, COALESCE(im.opening_stock, 0) AS current_stock
                FROM credit_note_line cnl
                LEFT JOIN item_master im ON im.item_code = cnl.item_code
                WHERE cnl.credit_note_id = ?
                ORDER BY cnl.id ASC
                """, cnId);

            if (!cnLines.isEmpty()) {
                orderTotalRevClawback = BigDecimal.ZERO;
                orderTotalCostReinstated = BigDecimal.ZERO;
                for (Map<String, Object> cl : cnLines) {
                    BigDecimal q = toBigDecimal(cl.get("quantity"));
                    BigDecimal up = toBigDecimal(cl.get("unit_price"));
                    BigDecimal tv = toBigDecimal(cl.get("taxable_value"));
                    BigDecimal curStk = toBigDecimal(cl.get("current_stock"));
                    String u = Objects.toString(cl.get("unit"), "Nos");
                    String d = Objects.toString(cl.get("descr"), "");

                    BigDecimal stockBefore = curStk;
                    BigDecimal stockAfter = stockMoved ? curStk.add(q) : curStk;

                    BigDecimal lineRev = tv.negate();
                    BigDecimal lineCost = stockMoved ? tv : BigDecimal.ZERO;
                    orderTotalRevClawback = orderTotalRevClawback.add(lineRev);
                    orderTotalCostReinstated = orderTotalCostReinstated.add(lineCost);

                    invLines.add(new GeneralLedgerDtos.PostingFlowInventoryLine(
                            Objects.toString(cl.get("item_code"), ""),
                            d + " (" + reason + ")",
                            stockMoved ? "INWARD_RESTOCK (STOCK REINSTATED)" : "ZERO_MOVEMENT (PRICE / REBATE ADJUSTMENT)",
                            stockMoved ? q : BigDecimal.ZERO,
                            u,
                            up,
                            stockMoved ? tv : BigDecimal.ZERO,
                            stockBefore,
                            stockAfter,
                            up,
                            BigDecimal.ZERO,
                            BigDecimal.ZERO,
                            up,
                            lineRev,
                            stockMoved ? BigDecimal.ZERO : lineRev,
                            BigDecimal.ZERO
                    ));
                }
            } else {
                orderTotalRevClawback = taxAmt.negate();
                orderTotalCostReinstated = stockMoved ? taxAmt : BigDecimal.ZERO;

                invLines.add(new GeneralLedgerDtos.PostingFlowInventoryLine(
                        "CREDIT_NOTE_ITEMS",
                        "Credit Note Items (" + reason + ")",
                        stockMoved ? "INWARD_RESTOCK (STOCK REINSTATED)" : "ZERO_MOVEMENT (FINANCIAL / COMMERCIAL ADJUSTMENT)",
                        stockMoved ? BigDecimal.ONE : BigDecimal.ZERO,
                        "Lot",
                        taxAmt,
                        stockMoved ? taxAmt : BigDecimal.ZERO,
                        null,
                        null,
                        taxAmt,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        taxAmt,
                        taxAmt.negate(),
                        stockMoved ? BigDecimal.ZERO : taxAmt.negate(),
                        BigDecimal.ZERO
                ));
            }
        }

        boolean isRebate = isCommercialRebateReason(reason);
        boolean isPhysical = isPhysicalStockReturnReason(reason);

        // Fetch voucher
        List<GeneralLedgerDtos.PostingFlowVoucherLine> vLines = fetchVoucherLines("CREDIT_NOTE", null, retNo);
        VoucherMeta meta = fetchVoucherMeta("CREDIT_NOTE", null, retNo);
        if ("PROSPECTIVE (ON APPROVAL)".equals(meta.entryNumber())) {
            meta = fetchVoucherMeta("SALES_RETURN", null, retNo);
        }
        String jvNo = meta.entryNumber();
        if (vLines.isEmpty()) {
            vLines = fetchVoucherLines("SALES_RETURN", null, retNo);
        }

        if (vLines.isEmpty()) {
            jvNo = "PROSPECTIVE (ON APPROVAL)";
            if (isRebate) {
                // Commercial discount - Section 15(3)(b)
                vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("5050", "Discounts Allowed / Commercial Rebate", total, BigDecimal.ZERO, "Debit Rebate: " + retNo));
                vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("1040", "Sundry Debtors (Trade Receivables)", BigDecimal.ZERO, total, "Credit Customer: " + partyName));
            } else {
                // Statutory Credit note (tax reversed) using source GST split when available
                boolean interState = false;
                BigDecimal taxable = BigDecimal.ZERO;
                BigDecimal cgstAmt = BigDecimal.ZERO;
                BigDecimal sgstAmt = BigDecimal.ZERO;
                BigDecimal igstAmt = BigDecimal.ZERO;
                try {
                    List<Map<String, Object>> cn = jdbc.queryForList(
                            "SELECT taxable_amount, cgst_amount, sgst_amount, igst_amount FROM credit_note_header WHERE credit_note_no = ? LIMIT 1", retNo);
                    if (!cn.isEmpty()) {
                        taxable = toBigDecimal(cn.getFirst().get("taxable_amount"));
                        cgstAmt = toBigDecimal(cn.getFirst().get("cgst_amount"));
                        sgstAmt = toBigDecimal(cn.getFirst().get("sgst_amount"));
                        igstAmt = toBigDecimal(cn.getFirst().get("igst_amount"));
                        interState = igstAmt.compareTo(BigDecimal.ZERO) > 0;
                    }
                } catch (Exception ignored) {}
                if (taxable.compareTo(BigDecimal.ZERO) <= 0) {
                    try {
                        List<Map<String, Object>> orig = origInv == null || origInv.isBlank() ? List.of() : jdbc.queryForList(
                                "SELECT subtotal, gst_amount, total_amount, COALESCE(gst_type,'') AS gst_type FROM sales_header WHERE invoice_no = ? LIMIT 1", origInv);
                        if (!orig.isEmpty()) {
                            BigDecimal sTot = toBigDecimal(orig.getFirst().get("total_amount"));
                            BigDecimal sTax = toBigDecimal(orig.getFirst().get("gst_amount"));
                            BigDecimal sSub = toBigDecimal(orig.getFirst().get("subtotal"));
                            String gt = Objects.toString(orig.getFirst().get("gst_type"), "").toUpperCase(Locale.ROOT);
                            interState = gt.contains("IGST") || gt.contains("INTER");
                            if (sTot.compareTo(BigDecimal.ZERO) > 0 && sSub.compareTo(BigDecimal.ZERO) > 0) {
                                taxable = total.multiply(sSub).divide(sTot, 2, RoundingMode.HALF_UP);
                                BigDecimal taxRem = total.subtract(taxable).max(BigDecimal.ZERO);
                                if (interState) igstAmt = taxRem;
                                else {
                                    cgstAmt = taxRem.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
                                    sgstAmt = taxRem.subtract(cgstAmt);
                                }
                            } else if (sTax.compareTo(BigDecimal.ZERO) == 0 && sTot.compareTo(BigDecimal.ZERO) > 0) {
                                taxable = total;
                            }
                        }
                    } catch (Exception ignored) {}
                }
                if (taxable.compareTo(BigDecimal.ZERO) <= 0) {
                    taxable = total.divide(BigDecimal.valueOf(1.18), 2, RoundingMode.HALF_UP);
                    BigDecimal rem = total.subtract(taxable);
                    cgstAmt = rem.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
                    sgstAmt = rem.subtract(cgstAmt);
                }
                vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("4100", "Sales Returns & Allowances", taxable, BigDecimal.ZERO, "Debit Sales Return: " + retNo));
                if (interState && igstAmt.compareTo(BigDecimal.ZERO) > 0) {
                    vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("2083", "Output IGST Payable", igstAmt, BigDecimal.ZERO, "Debit Output IGST Reversal"));
                } else {
                    if (cgstAmt.compareTo(BigDecimal.ZERO) > 0) vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("2081", "Output CGST Payable", cgstAmt, BigDecimal.ZERO, "Debit Output CGST Reversal"));
                    if (sgstAmt.compareTo(BigDecimal.ZERO) > 0) vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("2082", "Output SGST Payable", sgstAmt, BigDecimal.ZERO, "Debit Output SGST Reversal"));
                }
                vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("1040", "Sundry Debtors (Trade Receivables)", BigDecimal.ZERO, total, "Credit Customer: " + partyName));
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
                invLines, gst,
                orderTotalRevClawback, orderTotalCostReinstated, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, meta.postedAt(), meta.postedBy(), meta.triggerEvent(),
                origInv, "SETTLED", total
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
        String origBill = "";
        boolean stockMoved = true;

        if (!dnRows.isEmpty()) {
            Map<String, Object> dn = dnRows.getFirst();
            dnNo = Objects.toString(dn.get("debit_note_no"), no);
            origBill = Objects.toString(dn.get("original_bill_no"), "");
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

        List<GeneralLedgerDtos.PostingFlowInventoryLine> invLines = new ArrayList<>();
        BigDecimal orderTotalRevClawback = BigDecimal.ZERO;
        BigDecimal orderTotalCostReinstated = BigDecimal.ZERO;

        if (!dnRows.isEmpty()) {
            Map<String, Object> dn = dnRows.getFirst();
            Long dnId = ((Number) dn.get("id")).longValue();
            List<Map<String, Object>> dnLines = jdbc.queryForList("""
                SELECT dnl.item_code, COALESCE(dnl.item_description, im.description, dnl.item_code) AS descr,
                       dnl.quantity, COALESCE(im.unit, 'Nos') AS unit, COALESCE(dnl.unit_cost, 0) AS unit_price,
                       dnl.taxable_value, COALESCE(im.opening_stock, 0) AS current_stock
                FROM debit_note_line dnl
                LEFT JOIN item_master im ON im.item_code = dnl.item_code
                WHERE dnl.debit_note_id = ?
                ORDER BY dnl.id ASC
                """, dnId);

            if (!dnLines.isEmpty()) {
                for (Map<String, Object> dl : dnLines) {
                    BigDecimal q = toBigDecimal(dl.get("quantity"));
                    BigDecimal up = toBigDecimal(dl.get("unit_price"));
                    BigDecimal tv = toBigDecimal(dl.get("taxable_value"));
                    BigDecimal curStk = toBigDecimal(dl.get("current_stock"));
                    String u = Objects.toString(dl.get("unit"), "Nos");
                    String d = Objects.toString(dl.get("descr"), "");

                    BigDecimal stockBefore = curStk;
                    BigDecimal stockAfter = stockMoved ? curStk.subtract(q) : curStk;

                    BigDecimal lineRev = tv.negate();
                    BigDecimal lineCost = stockMoved ? tv.negate() : BigDecimal.ZERO;
                    orderTotalRevClawback = orderTotalRevClawback.add(lineRev);
                    orderTotalCostReinstated = orderTotalCostReinstated.add(lineCost);

                    invLines.add(new GeneralLedgerDtos.PostingFlowInventoryLine(
                            Objects.toString(dl.get("item_code"), ""),
                            d + " (" + reason + ")",
                            stockMoved ? "OUTWARD_RETURN (STOCK DEDUCTED)" : "ZERO_MOVEMENT (PRICE / REBATE ADJUSTMENT)",
                            stockMoved ? q.negate() : BigDecimal.ZERO,
                            u,
                            up,
                            stockMoved ? tv.negate() : BigDecimal.ZERO,
                            stockBefore,
                            stockAfter,
                            up,
                            BigDecimal.ZERO,
                            BigDecimal.ZERO,
                            up,
                            lineRev,
                            BigDecimal.ZERO,
                            BigDecimal.ZERO
                    ));
                }
            } else {
                invLines.add(new GeneralLedgerDtos.PostingFlowInventoryLine(
                        "DEBIT_NOTE_ITEMS",
                        "Debit Note Items (" + reason + ")",
                        isPhysical ? "OUTWARD_RETURN (STOCK DEDUCTED)" : "ZERO_MOVEMENT (FINANCIAL / REBATE ADJUSTMENT)",
                        isPhysical ? BigDecimal.ONE.negate() : BigDecimal.ZERO,
                        "Lot",
                        taxable,
                        isPhysical ? taxable.negate() : BigDecimal.ZERO,
                        null,
                        null,
                        taxable,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        taxable,
                        taxable.negate(),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO
                ));
            }
        } else {
            invLines.add(new GeneralLedgerDtos.PostingFlowInventoryLine(
                    "DEBIT_NOTE_ITEMS",
                    "Debit Note Items (" + reason + ")",
                    isPhysical ? "OUTWARD_RETURN (STOCK DEDUCTED)" : "ZERO_MOVEMENT (FINANCIAL / REBATE ADJUSTMENT)",
                    isPhysical ? BigDecimal.ONE.negate() : BigDecimal.ZERO,
                    "Lot",
                    taxable,
                    isPhysical ? taxable.negate() : BigDecimal.ZERO,
                    null,
                    null,
                    taxable,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    taxable,
                    taxable.negate(),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO
            ));
        }

        // Fetch voucher
        List<GeneralLedgerDtos.PostingFlowVoucherLine> vLines = fetchVoucherLines("DEBIT_NOTE", null, dnNo);
        VoucherMeta meta = fetchVoucherMeta("DEBIT_NOTE", null, dnNo);
        if ("PROSPECTIVE (ON APPROVAL)".equals(meta.entryNumber())) {
            meta = fetchVoucherMeta("PURCHASE_RETURN", null, dnNo);
        }
        String jvNo = meta.entryNumber();
        if (vLines.isEmpty()) {
            vLines = fetchVoucherLines("PURCHASE_RETURN", null, dnNo);
        }

        if (vLines.isEmpty()) {
            jvNo = "PROSPECTIVE (ON APPROVAL)";
            if (isRebate) {
                vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("2010", "Sundry Creditors (Trade Payables)", total, BigDecimal.ZERO, "Debit Supplier: " + supplier));
                vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("4090", "Discounts Received / Supplier Rebate", BigDecimal.ZERO, total, "Credit Rebate: " + dnNo));
            } else {
                vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("2010", "Sundry Creditors (Trade Payables)", total, BigDecimal.ZERO, "Debit Supplier: " + supplier));
                vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("5100", "Purchase Returns & Outward", BigDecimal.ZERO, taxable, "Credit Purchase Return"));
                vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("1083", "Input GST Reversal (CGST/SGST/IGST)", BigDecimal.ZERO, tax, "Credit Input GST Reversal"));
            }
        }

        BigDecimal totDeb = vLines.stream().map(GeneralLedgerDtos.PostingFlowVoucherLine::debitAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totCred = vLines.stream().map(GeneralLedgerDtos.PostingFlowVoucherLine::creditAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

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
                invLines, gst,
                taxable.negate(), isPhysical ? taxable.negate() : BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, meta.postedAt(), meta.postedBy(), meta.triggerEvent(),
                origBill, "SETTLED", total
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

        List<GeneralLedgerDtos.PostingFlowVoucherLine> vLines = fetchVoucherLines(isCustomer ? "PAYMENT_RECEIPT" : "PURCHASE_PAYMENT", paymentId, refNo);
        VoucherMeta meta = fetchVoucherMeta(isCustomer ? "PAYMENT_RECEIPT" : "PURCHASE_PAYMENT", paymentId, refNo);
        String jvNo = meta.entryNumber();

        if (vLines.isEmpty()) {
            jvNo = "PROSPECTIVE (ON SETTLEMENT)";
            boolean isCash = mode.toUpperCase(Locale.ROOT).contains("CASH");
            String fundAccCode = isCash ? "1010" : "1020";
            String fundAccName = isCash ? "Cash on Hand" : "Bank Current Account";
            if (isCustomer) {
                vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine(fundAccCode, fundAccName, amount, BigDecimal.ZERO, "Debit Received via " + mode));
                vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("1040", "Sundry Debtors (Trade Receivables)", BigDecimal.ZERO, amount, "Credit Customer AR (" + refNo + ")"));
            } else {
                vLines.add(new GeneralLedgerDtos.PostingFlowVoucherLine("2010", "Sundry Creditors (Trade Payables)", amount, BigDecimal.ZERO, "Debit Supplier AP (" + refNo + ")"));
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
                List.of(), gst,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, meta.postedAt(), meta.postedBy(), meta.triggerEvent(),
                refNo, "PAID", amount
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

    private record VoucherMeta(String entryNumber, LocalDateTime postedAt, String postedBy, String triggerEvent) {}

    private VoucherMeta fetchVoucherMeta(String refType, Long refId, String refNo) {
        try {
            List<Map<String, Object>> entries = jdbc.queryForList("""
                SELECT entry_number, posted_at, posted_by, reference_type
                FROM journal_entry
                WHERE (reference_no = ? OR (reference_type = ? AND reference_id = ?))
                  AND status <> 'CANCELLED'
                ORDER BY id DESC LIMIT 1
                """, refNo, refType, refId != null ? refId : -1L);
            if (!entries.isEmpty()) {
                Map<String, Object> r = entries.getFirst();
                String en = Objects.toString(r.get("entry_number"), "PROSPECTIVE");
                LocalDateTime pat = parseDateTime(r.get("posted_at"));
                String pb = Objects.toString(r.get("posted_by"), "SYSTEM");
                String tr = Objects.toString(r.get("reference_type"), refType);
                return new VoucherMeta(en, pat, pb, tr);
            }
        } catch (Exception ignored) {}
        return new VoucherMeta("PROSPECTIVE (ON APPROVAL)", null, "SYSTEM", "AUTO_POST");
    }

    private static LocalDateTime parseDateTime(Object o) {
        if (o == null) return null;
        if (o instanceof LocalDateTime ldt) return ldt;
        try {
            String s = o.toString().trim().replace(' ', 'T');
            if (s.length() >= 19) s = s.substring(0, 19);
            return LocalDateTime.parse(s);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static LocalDate parseDate(Object o) {
        if (o == null) return LocalDate.now();
        if (o instanceof LocalDate ld) return ld;
        try { return LocalDate.parse(o.toString().substring(0, 10)); } catch (Exception ignored) { return LocalDate.now(); }
    }

    public Long resolveExpenseAccountId(String category, String accountName) {
        String combined = ((category != null ? category : "") + " " + (accountName != null ? accountName : ""))
                .trim().toUpperCase(Locale.ROOT);
        if (combined.contains("BANK CHARGE") || combined.contains("MDR") || combined.contains("GATEWAY")) {
            return getAccountIdOrDefault("5060", "Bank Charges & Gateway MDR Fees", "EXPENSE");
        }
        if (combined.contains("SALARY") || combined.contains("WAGE") || combined.contains("STAFF") || combined.contains("WELFARE")) {
            return getAccountIdOrDefault("5070", "Salaries & Staff Welfare", "EXPENSE");
        }
        if (combined.contains("FREIGHT") || combined.contains("CARTAGE") || combined.contains("TRANSPORT")) {
            return getAccountIdOrDefault("5030", "Freight Inward (Cartage)", "EXPENSE");
        }
        if (combined.contains("PURCHASE") || combined.contains("RAW")) {
            return getAccountIdOrDefault("5020", "Raw Material Purchases", "EXPENSE");
        }
        if (combined.contains("DISCOUNT")) {
            return getAccountIdOrDefault("5050", "Discounts Allowed", "EXPENSE");
        }
        if (combined.contains("RENT") || combined.contains("UTILITY") || combined.contains("ELECTRIC") || combined.contains("OFFICE")) {
            return getAccountIdOrDefault("5080", "Office Rent & Utilities", "EXPENSE");
        }
        if (category != null && !category.isBlank()) {
            try {
                Long matched = jdbc.queryForObject(
                        "SELECT id FROM chart_of_accounts WHERE UPPER(account_type) = 'EXPENSE' AND is_active = TRUE AND LOWER(account_name) LIKE ? ORDER BY account_code ASC LIMIT 1",
                        Long.class, "%" + category.trim().toLowerCase(Locale.ROOT) + "%");
                if (matched != null) return matched;
            } catch (Exception ignored) {}
        }
        return getAccountIdOrDefault("5080", "Office Rent & Utilities", "EXPENSE");
    }

    /** Posts or replaces the GL Journal Voucher for a manual Bank & Expense Register entry. */
    public Long postFinanceVoucher(Integer financeId, String voucherNo, LocalDate date, String voucherType,
                                   String category, String accountName, String mode, BigDecimal amount,
                                   Integer partyId, String notes, String actor) {
        if (financeId == null || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) return null;
        LocalDate effectiveDate = date != null ? date : LocalDate.now();
        ensurePeriodOpen(effectiveDate);
        cancelJournalEntriesForReference("FINANCE", Long.valueOf(financeId), actor);

        BigDecimal amt = amount.setScale(2, RoundingMode.HALF_UP);
        String vt = voucherType == null ? "EXPENSE" : voucherType.trim().toUpperCase(Locale.ROOT);
        boolean isCashMode = mode != null && mode.toUpperCase(Locale.ROOT).contains("CASH");
        Long fundAcc = getAccountIdOrDefault(isCashMode ? "1010" : "1020",
                isCashMode ? "Cash on Hand" : "Bank Current Account", "ASSET");
        String fundCode = isCashMode ? "1010" : "1020";

        List<GeneralLedgerDtos.CreateLineRequest> lines = new ArrayList<>();
        String entryType;
        if ("EXPENSE".equals(vt)) {
            entryType = "EXPENSE";
            Long expAcc = resolveExpenseAccountId(category, accountName);
            lines.add(new GeneralLedgerDtos.CreateLineRequest(expAcc, null, amt, BigDecimal.ZERO, partyId,
                    (category != null ? category : "Expense") + ": " + (notes != null ? notes : voucherNo), null));
            lines.add(new GeneralLedgerDtos.CreateLineRequest(fundAcc, fundCode, BigDecimal.ZERO, amt, null,
                    "Paid via " + (mode != null ? mode : "Bank") + " (" + voucherNo + ")", null));
        } else if (vt.contains("DEPOSIT")) {
            entryType = "BANK_DEPOSIT";
            lines.add(new GeneralLedgerDtos.CreateLineRequest(fundAcc, fundCode, amt, BigDecimal.ZERO, null,
                    "Deposit " + voucherNo + (notes != null && !notes.isBlank() ? " - " + notes : ""), null));
            Long creditAcc;
            String creditCode;
            if (partyId != null && partyId > 0) {
                creditCode = "1040";
                creditAcc = getAccountIdOrDefault("1040", "Sundry Debtors (Trade Receivables)", "ASSET");
            } else if ((accountName + " " + category + " " + notes).toUpperCase(Locale.ROOT).contains("CAPITAL")) {
                creditCode = "3010";
                creditAcc = getAccountIdOrDefault("3010", "Owner Capital Account", "EQUITY");
            } else if ((mode != null && mode.toUpperCase(Locale.ROOT).contains("CASH")) || (category != null && category.toUpperCase(Locale.ROOT).contains("CONTRA"))) {
                creditCode = "1010";
                creditAcc = getAccountIdOrDefault("1010", "Cash on Hand", "ASSET");
            } else {
                creditCode = "4090";
                creditAcc = getAccountIdOrDefault("4090", "Discounts Received", "REVENUE");
            }
            lines.add(new GeneralLedgerDtos.CreateLineRequest(creditAcc, creditCode, BigDecimal.ZERO, amt, partyId,
                    "Source: " + (category != null ? category : "Deposit") + " (" + voucherNo + ")", null));
        } else {
            entryType = "BANK_WITHDRAWAL";
            Long debitAcc;
            String debitCode;
            if (partyId != null && partyId > 0) {
                debitCode = "2010";
                debitAcc = getAccountIdOrDefault("2010", "Sundry Creditors (Trade Payables)", "LIABILITY");
            } else if ((notes + " " + category).toUpperCase(Locale.ROOT).matches(".*(ATM|CASH|SELF|CONTRA|PETTY).*")) {
                debitCode = "1010";
                debitAcc = getAccountIdOrDefault("1010", "Cash on Hand", "ASSET");
            } else {
                debitCode = "3010";
                debitAcc = getAccountIdOrDefault("3010", "Owner Capital Account", "EQUITY");
            }
            lines.add(new GeneralLedgerDtos.CreateLineRequest(debitAcc, debitCode, amt, BigDecimal.ZERO, partyId,
                    "Withdrawal to: " + (category != null ? category : debitCode) + " (" + voucherNo + ")", null));
            lines.add(new GeneralLedgerDtos.CreateLineRequest(fundAcc, fundCode, BigDecimal.ZERO, amt, null,
                    "Bank withdrawal " + voucherNo, null));
        }

        GeneralLedgerDtos.JournalEntryDto created = postJournalEntry(new GeneralLedgerDtos.CreateJournalRequest(
                effectiveDate, entryType, "FINANCE", Long.valueOf(financeId), voucherNo,
                vt + " " + voucherNo + (notes != null && !notes.isBlank() ? " — " + notes : ""),
                actor != null ? actor : "SYSTEM", lines
        ));
        if (created != null && created.id() != null) {
            jdbc.update("UPDATE finance_register SET gl_entry_id = ? WHERE id = ?", created.id(), financeId);
            return created.id();
        }
        return null;
    }

    /** Posts the GL Journal Voucher for a Bank Statement Reconciliation Match (handles Round-Off and TDS). */
    public Long postBankReconSettlement(Integer financeId, String voucherNo, LocalDate date, boolean isReceipt,
                                        String targetType, String targetNo, BigDecimal bankAmount,
                                        BigDecimal roundOff, BigDecimal tdsAmount, Integer partyId,
                                        String partyName, String actor) {
        if (bankAmount == null || bankAmount.compareTo(BigDecimal.ZERO) <= 0) return null;
        LocalDate effectiveDate = date != null ? date : LocalDate.now();
        ensurePeriodOpen(effectiveDate);
        if (financeId != null) {
            cancelJournalEntriesForReference("FINANCE", Long.valueOf(financeId), actor);
        }
        BigDecimal bankAmt = bankAmount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal roAmt = roundOff != null ? roundOff.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal tdsAmt = tdsAmount != null ? tdsAmount.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal settledTotal = bankAmt.add(roAmt).add(tdsAmt);
        if (settledTotal.compareTo(BigDecimal.ZERO) <= 0) settledTotal = bankAmt;

        Long bankAcc = getAccountIdOrDefault("1020", "Bank Current Account", "ASSET");
        Long roundAcc = getAccountIdOrDefault("5050", "Discounts & Rounding Adjustments", "EXPENSE");
        List<GeneralLedgerDtos.CreateLineRequest> lines = new ArrayList<>();

        if (isReceipt) {
            lines.add(new GeneralLedgerDtos.CreateLineRequest(bankAcc, "1020", bankAmt, BigDecimal.ZERO, null,
                    "Bank receipt reconciled: " + targetNo, null));
            if (tdsAmt.compareTo(BigDecimal.ZERO) > 0) {
                Long tdsRecAcc = getAccountIdOrDefault("1090", "TDS Receivable (Sec 194Q/194C)", "ASSET");
                lines.add(new GeneralLedgerDtos.CreateLineRequest(tdsRecAcc, "1090", tdsAmt, BigDecimal.ZERO, partyId,
                        "TDS deducted on " + targetNo, null));
            }
            if (roAmt.compareTo(BigDecimal.ZERO) > 0) {
                lines.add(new GeneralLedgerDtos.CreateLineRequest(roundAcc, "5050", roAmt, BigDecimal.ZERO, null,
                        "Settlement rounding loss on " + targetNo, null));
            } else if (roAmt.compareTo(BigDecimal.ZERO) < 0) {
                lines.add(new GeneralLedgerDtos.CreateLineRequest(roundAcc, "5050", BigDecimal.ZERO, roAmt.abs(), null,
                        "Settlement rounding gain on " + targetNo, null));
            }
            Long arAcc = getAccountIdOrDefault("1040", "Sundry Debtors (Trade Receivables)", "ASSET");
            lines.add(new GeneralLedgerDtos.CreateLineRequest(arAcc, "1040", BigDecimal.ZERO, settledTotal, partyId,
                    "AR cleared: " + (partyName != null ? partyName : targetNo) + " [" + targetNo + "]", null));
        } else {
            Long apAcc = getAccountIdOrDefault("2010", "Sundry Creditors (Trade Payables)", "LIABILITY");
            lines.add(new GeneralLedgerDtos.CreateLineRequest(apAcc, "2010", settledTotal, BigDecimal.ZERO, partyId,
                    "AP cleared: " + (partyName != null ? partyName : targetNo) + " [" + targetNo + "]", null));
            lines.add(new GeneralLedgerDtos.CreateLineRequest(bankAcc, "1020", BigDecimal.ZERO, bankAmt, null,
                    "Bank payout reconciled: " + targetNo, null));
            if (tdsAmt.compareTo(BigDecimal.ZERO) > 0) {
                Long tdsPayAcc = getAccountIdOrDefault("2090", "TDS / TCS Payable", "LIABILITY");
                lines.add(new GeneralLedgerDtos.CreateLineRequest(tdsPayAcc, "2090", BigDecimal.ZERO, tdsAmt, partyId,
                        "TDS withheld on " + targetNo, null));
            }
            if (roAmt.compareTo(BigDecimal.ZERO) > 0) {
                lines.add(new GeneralLedgerDtos.CreateLineRequest(roundAcc, "5050", BigDecimal.ZERO, roAmt, null,
                        "Supplier settlement discount/roundoff " + targetNo, null));
            } else if (roAmt.compareTo(BigDecimal.ZERO) < 0) {
                lines.add(new GeneralLedgerDtos.CreateLineRequest(roundAcc, "5050", roAmt.abs(), BigDecimal.ZERO, null,
                        "Supplier settlement excess " + targetNo, null));
            }
        }

        GeneralLedgerDtos.JournalEntryDto created = postJournalEntry(new GeneralLedgerDtos.CreateJournalRequest(
                effectiveDate, "BANK_RECON", "FINANCE", financeId != null ? Long.valueOf(financeId) : null, voucherNo,
                "Bank Reconciliation " + targetType + " " + targetNo + (partyName != null && !partyName.isBlank() ? " (" + partyName + ")" : ""),
                actor != null ? actor : "SYSTEM", lines
        ));
        if (created != null && created.id() != null && financeId != null) {
            jdbc.update("UPDATE finance_register SET gl_entry_id = ? WHERE id = ?", created.id(), financeId);
            return created.id();
        }
        return created != null ? created.id() : null;
    }

    /** Posts the GL Journal Voucher when a Sales Return refund is paid or Purchase Return refund is received. */
    public Long postReturnRefundVoucher(Integer refundId, String returnNo, boolean isSalesReturn, LocalDate date,
                                        BigDecimal cashAmount, BigDecimal rounding, String mode,
                                        Integer partyId, String partyName, String actor) {
        if (cashAmount == null || cashAmount.compareTo(BigDecimal.ZERO) <= 0) return null;
        LocalDate effectiveDate = date != null ? date : LocalDate.now();
        ensurePeriodOpen(effectiveDate);
        if (refundId != null) {
            cancelJournalEntriesForReference("RETURN_REFUND", Long.valueOf(refundId), actor);
        }
        BigDecimal amt = cashAmount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal ro = rounding != null ? rounding.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal total = amt.add(ro);
        boolean isCash = mode != null && mode.toUpperCase(Locale.ROOT).contains("CASH");
        Long fundAcc = getAccountIdOrDefault(isCash ? "1010" : "1020",
                isCash ? "Cash on Hand" : "Bank Current Account", "ASSET");
        String fundCode = isCash ? "1010" : "1020";
        Long roundAcc = getAccountIdOrDefault("5050", "Discounts & Rounding Adjustments", "EXPENSE");

        List<GeneralLedgerDtos.CreateLineRequest> lines = new ArrayList<>();
        if (isSalesReturn) {
            Long arAcc = getAccountIdOrDefault("1040", "Sundry Debtors (Trade Receivables)", "ASSET");
            lines.add(new GeneralLedgerDtos.CreateLineRequest(arAcc, "1040", total, BigDecimal.ZERO, partyId,
                    "Customer return refund: " + returnNo + " (" + (partyName != null ? partyName : "Customer") + ")", null));
            lines.add(new GeneralLedgerDtos.CreateLineRequest(fundAcc, fundCode, BigDecimal.ZERO, amt, null,
                    "Refund paid via " + (mode != null ? mode : "Bank"), null));
            if (ro.compareTo(BigDecimal.ZERO) > 0) {
                lines.add(new GeneralLedgerDtos.CreateLineRequest(roundAcc, "5050", BigDecimal.ZERO, ro, null, "Refund rounding: " + returnNo, null));
            } else if (ro.compareTo(BigDecimal.ZERO) < 0) {
                lines.add(new GeneralLedgerDtos.CreateLineRequest(roundAcc, "5050", ro.abs(), BigDecimal.ZERO, null, "Refund rounding: " + returnNo, null));
            }
        } else {
            Long apAcc = getAccountIdOrDefault("2010", "Sundry Creditors (Trade Payables)", "LIABILITY");
            lines.add(new GeneralLedgerDtos.CreateLineRequest(fundAcc, fundCode, amt, BigDecimal.ZERO, null,
                    "Supplier refund received via " + (mode != null ? mode : "Bank"), null));
            if (ro.compareTo(BigDecimal.ZERO) > 0) {
                lines.add(new GeneralLedgerDtos.CreateLineRequest(roundAcc, "5050", ro, BigDecimal.ZERO, null, "Refund rounding: " + returnNo, null));
            } else if (ro.compareTo(BigDecimal.ZERO) < 0) {
                lines.add(new GeneralLedgerDtos.CreateLineRequest(roundAcc, "5050", BigDecimal.ZERO, ro.abs(), null, "Refund rounding: " + returnNo, null));
            }
            lines.add(new GeneralLedgerDtos.CreateLineRequest(apAcc, "2010", BigDecimal.ZERO, total, partyId,
                    "Supplier return refund: " + returnNo + " (" + (partyName != null ? partyName : "Supplier") + ")", null));
        }

        GeneralLedgerDtos.JournalEntryDto created = postJournalEntry(new GeneralLedgerDtos.CreateJournalRequest(
                effectiveDate, "REFUND", "RETURN_REFUND", refundId != null ? Long.valueOf(refundId) : null, returnNo,
                "Return Refund Settlement " + returnNo, actor != null ? actor : "SYSTEM", lines
        ));
        if (created != null && created.id() != null && refundId != null) {
            jdbc.update("UPDATE return_refund SET gl_entry_id = ? WHERE id = ?", created.id(), refundId);
            return created.id();
        }
        return created != null ? created.id() : null;
    }

    @Transactional(readOnly = true)
    public GeneralLedgerDtos.PartyLedgerResponse getPartyLedger(Long partyId, LocalDate fromDate, LocalDate toDate) {
        if (partyId == null || partyId <= 0) {
            throw new IllegalArgumentException("A valid Customer or Supplier partyId is required.");
        }
        List<Map<String, Object>> partyRows = jdbc.queryForList(
                "SELECT id, COALESCE(name, 'Party') AS name, COALESCE(party_type, 'CUSTOMER') AS party_type FROM party_master WHERE id = ?",
                partyId);
        if (partyRows.isEmpty()) {
            throw new IllegalArgumentException("Party not found: " + partyId);
        }
        Map<String, Object> pm = partyRows.getFirst();
        String partyName = Objects.toString(pm.get("name"), "Party");
        String partyType = Objects.toString(pm.get("party_type"), "CUSTOMER").toUpperCase(Locale.ROOT);
        boolean drNormal = !partyType.contains("SUPPLIER") && !partyType.contains("VENDOR");

        BigDecimal opening = BigDecimal.ZERO;
        if (fromDate != null) {
            Map<String, Object> prior = jdbc.queryForMap("""
                SELECT COALESCE(SUM(jl.debit_amount), 0) AS dr, COALESCE(SUM(jl.credit_amount), 0) AS cr
                FROM journal_line jl
                JOIN journal_entry je ON je.id = jl.journal_entry_id
                WHERE jl.party_id = ?
                  AND je.entry_date < ?
                  AND UPPER(COALESCE(je.status, '')) IN ('POSTED', 'REVERSED')
                """, partyId, fromDate);
            BigDecimal pDr = toBigDecimal(prior.get("dr"));
            BigDecimal pCr = toBigDecimal(prior.get("cr"));
            opening = drNormal ? pDr.subtract(pCr) : pCr.subtract(pDr);
        }

        StringBuilder sql = new StringBuilder("""
            SELECT je.id AS je_id, je.entry_number, je.entry_date, je.entry_type, je.reference_type, je.reference_id,
                   je.reference_no, je.narration, jl.line_narration, jl.debit_amount, jl.credit_amount, je.status, je.posted_by
            FROM journal_line jl
            JOIN journal_entry je ON je.id = jl.journal_entry_id
            WHERE jl.party_id = ?
              AND UPPER(COALESCE(je.status, '')) IN ('POSTED', 'REVERSED')
            """);
        List<Object> params = new ArrayList<>();
        params.add(partyId);
        if (fromDate != null) {
            sql.append(" AND je.entry_date >= ? ");
            params.add(fromDate);
        }
        if (toDate != null) {
            sql.append(" AND je.entry_date <= ? ");
            params.add(toDate);
        }
        sql.append(" ORDER BY je.entry_date ASC, je.id ASC, jl.line_number ASC");

        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), params.toArray());
        BigDecimal running = opening;
        BigDecimal totalDr = BigDecimal.ZERO;
        BigDecimal totalCr = BigDecimal.ZERO;
        List<GeneralLedgerDtos.AccountLedgerLineDto> lines = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            BigDecimal dr = toBigDecimal(r.get("debit_amount"));
            BigDecimal cr = toBigDecimal(r.get("credit_amount"));
            totalDr = totalDr.add(dr);
            totalCr = totalCr.add(cr);
            running = drNormal ? running.add(dr).subtract(cr) : running.add(cr).subtract(dr);
            lines.add(new GeneralLedgerDtos.AccountLedgerLineDto(
                    r.get("je_id") instanceof Number n ? n.longValue() : null,
                    str(r.get("entry_number")),
                    parseLocalDate(r.get("entry_date")),
                    str(r.get("entry_type")),
                    str(r.get("reference_type")),
                    r.get("reference_id") instanceof Number n ? n.longValue() : null,
                    str(r.get("reference_no")),
                    str(r.get("narration")),
                    str(r.get("line_narration")),
                    dr, cr, running.abs(), resolveBalanceSide(drNormal, running),
                    str(r.get("status")), str(r.get("posted_by"))
            ));
        }

        return new GeneralLedgerDtos.PartyLedgerResponse(
                partyId, partyName, partyType, fromDate, toDate,
                opening.abs(), totalDr, totalCr, running.abs(), resolveBalanceSide(drNormal, running), lines
        );
    }

    @Transactional(readOnly = true)
    public GeneralLedgerDtos.ReconciliationHealthCheckDto getReconciliationHealth() {
        List<GeneralLedgerDtos.ReconciliationControlRow> controls = new ArrayList<>();

        BigDecimal glAr = sumAccountCurrentBalance(List.of("1040", "1100"));
        BigDecimal subAr = toBigDecimal(jdbc.queryForObject(
                "SELECT COALESCE(SUM(GREATEST(0, COALESCE(total_amount, 0) - COALESCE(paid_amount, 0))), 0) FROM sales_header WHERE UPPER(COALESCE(document_status, '')) NOT IN ('DELETED','CANCELLED','REJECTED','VOID') AND UPPER(COALESCE(approval_status, 'APPROVED')) <> 'PENDING'",
                BigDecimal.class));
        controls.add(buildControlRow("Accounts Receivable", "1040", "Sundry Debtors vs Sales Unpaid", glAr, subAr));

        BigDecimal glAp = sumAccountCurrentBalance(List.of("2010", "2100"));
        BigDecimal subAp = toBigDecimal(jdbc.queryForObject(
                "SELECT COALESCE(SUM(GREATEST(0, COALESCE(total_amount, 0) - COALESCE(paid_amount, 0))), 0) FROM purchase_header WHERE UPPER(COALESCE(document_status, '')) NOT IN ('DELETED','CANCELLED','REJECTED','VOID') AND UPPER(COALESCE(approval_status, 'APPROVED')) <> 'PENDING'",
                BigDecimal.class));
        controls.add(buildControlRow("Accounts Payable", "2010", "Sundry Creditors vs Purchase Unpaid", glAp, subAp));

        BigDecimal glCashBank = sumAccountCurrentBalance(List.of("1010", "1020"));
        CashPositionService cashSvc = cashPositionProvider != null ? cashPositionProvider.getIfAvailable() : null;
        BigDecimal subCashBank = cashSvc != null ? BigDecimal.valueOf(cashSvc.cashPosition()).setScale(2, RoundingMode.HALF_UP) : glCashBank;
        controls.add(buildControlRow("Cash & Bank Position", "1010/1020", "GL Cash+Bank vs Operational Cash Position", glCashBank, subCashBank));

        BigDecimal glStock = sumAccountCurrentBalance(List.of("1060"));
        BigDecimal subStock = toBigDecimal(jdbc.queryForObject(
                "SELECT COALESCE(SUM(COALESCE(opening_stock, 0) * COALESCE(NULLIF(purchase_price, 0), 0)), 0) FROM item_master WHERE COALESCE(is_active, 1) <> 0",
                BigDecimal.class)).setScale(2, RoundingMode.HALF_UP);
        controls.add(buildControlRow("Inventory Valuation", "1060", "Stock-in-Trade vs Item Valuation", glStock, subStock));

        List<GeneralLedgerDtos.UnpostedDocRow> unposted = new ArrayList<>();
        try {
            jdbc.query("""
                SELECT 'SALE' AS doc_type, s.id, COALESCE(s.invoice_no, CAST(s.id AS VARCHAR)) AS doc_no, s.invoice_date::TEXT AS dt, COALESCE(s.total_amount, 0) AS amt, COALESCE(s.document_status, '') AS st
                FROM sales_header s
                WHERE UPPER(COALESCE(s.approval_status, '')) = 'APPROVED'
                  AND UPPER(COALESCE(s.document_status, '')) NOT IN ('DELETED','CANCELLED','REJECTED','VOID')
                  AND COALESCE(s.total_amount, 0) > 0
                  AND NOT EXISTS (SELECT 1 FROM journal_entry je WHERE je.reference_type = 'SALE' AND je.reference_id = s.id AND UPPER(COALESCE(je.status,'')) IN ('POSTED','REVERSED'))
                UNION ALL
                SELECT 'PURCHASE', p.id, COALESCE(p.invoice_no, CAST(p.id AS VARCHAR)), p.invoice_date::TEXT, COALESCE(p.total_amount, 0), COALESCE(p.document_status, '')
                FROM purchase_header p
                WHERE UPPER(COALESCE(p.approval_status, '')) = 'APPROVED'
                  AND UPPER(COALESCE(p.document_status, '')) NOT IN ('DELETED','CANCELLED','REJECTED','VOID')
                  AND COALESCE(p.total_amount, 0) > 0
                  AND NOT EXISTS (SELECT 1 FROM journal_entry je WHERE je.reference_type = 'PURCHASE' AND je.reference_id = p.id AND UPPER(COALESCE(je.status,'')) IN ('POSTED','REVERSED'))
                UNION ALL
                SELECT 'FINANCE', f.id, COALESCE(f.voucher_no, CAST(f.id AS VARCHAR)), COALESCE(f.voucher_date, ''), COALESCE(f.amount, 0), COALESCE(f.voucher_type, '')
                FROM finance_register f
                WHERE COALESCE(f.amount, 0) > 0
                  AND NOT EXISTS (SELECT 1 FROM journal_entry je WHERE je.reference_type = 'FINANCE' AND je.reference_id = f.id AND UPPER(COALESCE(je.status,'')) IN ('POSTED','REVERSED'))
                LIMIT 50
                """, (rs, i) -> {
                unposted.add(new GeneralLedgerDtos.UnpostedDocRow(
                        rs.getString(1), rs.getLong(2), rs.getString(3),
                        rs.getString(4), rs.getBigDecimal(5), rs.getString(6)
                ));
                return null;
            });
        } catch (Exception ignored) {}

        int mismatches = (int) controls.stream().filter(c -> !"MATCHED".equals(c.status())).count();
        boolean healthy = mismatches == 0 && unposted.isEmpty();
        return new GeneralLedgerDtos.ReconciliationHealthCheckDto(controls, unposted, mismatches, unposted.size(), healthy);
    }

    private BigDecimal sumAccountCurrentBalance(List<String> codes) {
        BigDecimal total = BigDecimal.ZERO;
        for (String code : codes) {
            total = total.add(coaRepository.findByAccountCode(code).map(ChartOfAccountsEntity::getCurrentBalance).orElse(BigDecimal.ZERO));
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    private static GeneralLedgerDtos.ReconciliationControlRow buildControlRow(String area, String code, String name, BigDecimal gl, BigDecimal sub) {
        BigDecimal g = gl != null ? gl.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal s = sub != null ? sub.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal diff = g.subtract(s);
        String st = diff.abs().compareTo(new BigDecimal("1.00")) <= 0 ? "MATCHED" : "VARIANCE";
        return new GeneralLedgerDtos.ReconciliationControlRow(area, code, name, g, s, diff, st);
    }

    public GeneralLedgerDtos.FiscalYearCloseResponse closeFiscalYear(GeneralLedgerDtos.FiscalYearCloseRequest req, String actor) {
        if (req == null || req.fromDate() == null || req.toDate() == null || req.toDate().isBefore(req.fromDate())) {
            throw new IllegalArgumentException("Valid Fiscal Year start and end dates are required.");
        }
        String label = (req.fyLabel() == null || req.fyLabel().isBlank())
                ? "FY " + req.fromDate().getYear() + "-" + req.toDate().getYear()
                : req.fyLabel().trim();
        ensurePeriodOpen(req.toDate());

        // Prevent duplicate FY close for exact date range
        Long existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fiscal_year_close_log WHERE from_date = ? AND to_date = ?",
                Long.class, req.fromDate(), req.toDate());
        if (existing != null && existing > 0) {
            throw new IllegalStateException("Fiscal Year " + label + " (" + req.fromDate() + " to " + req.toDate() + ") has already been closed.");
        }

        Map<Long, BigDecimal[]> periodMap = loadAccountMovementSums(req.fromDate(), req.toDate());
        List<ChartOfAccountsEntity> accounts = coaRepository.findAllByOrderByAccountCodeAsc();
        List<GeneralLedgerDtos.CreateLineRequest> closingLines = new ArrayList<>();
        BigDecimal netTransfer = BigDecimal.ZERO;

        for (ChartOfAccountsEntity acc : accounts) {
            String type = acc.getAccountType() == null ? "" : acc.getAccountType().toUpperCase(Locale.ROOT);
            if (!"REVENUE".equals(type) && !"EXPENSE".equals(type)) continue;
            BigDecimal[] mov = periodMap.getOrDefault(acc.getId(), new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            BigDecimal netRaw = "REVENUE".equals(type) ? mov[1].subtract(mov[0]) : mov[0].subtract(mov[1]);
            BigDecimal net = netRaw.setScale(2, RoundingMode.HALF_UP);
            if (net.abs().compareTo(new BigDecimal("0.01")) < 0) continue;

            if ("REVENUE".equals(type)) {
                if (net.compareTo(BigDecimal.ZERO) > 0) {
                    closingLines.add(new GeneralLedgerDtos.CreateLineRequest(acc.getId(), acc.getAccountCode(), net, BigDecimal.ZERO, null, "FY Close: " + acc.getAccountName(), null));
                    netTransfer = netTransfer.add(net);
                } else {
                    BigDecimal absNet = net.abs();
                    closingLines.add(new GeneralLedgerDtos.CreateLineRequest(acc.getId(), acc.getAccountCode(), BigDecimal.ZERO, absNet, null, "FY Close: " + acc.getAccountName(), null));
                    netTransfer = netTransfer.subtract(absNet);
                }
            } else {
                if (net.compareTo(BigDecimal.ZERO) > 0) {
                    closingLines.add(new GeneralLedgerDtos.CreateLineRequest(acc.getId(), acc.getAccountCode(), BigDecimal.ZERO, net, null, "FY Close: " + acc.getAccountName(), null));
                    netTransfer = netTransfer.subtract(net);
                } else {
                    BigDecimal absNet = net.abs();
                    closingLines.add(new GeneralLedgerDtos.CreateLineRequest(acc.getId(), acc.getAccountCode(), absNet, BigDecimal.ZERO, null, "FY Close: " + acc.getAccountName(), null));
                    netTransfer = netTransfer.add(absNet);
                }
            }
        }

        if (closingLines.isEmpty()) {
            throw new IllegalStateException("No Revenue or Expense movements found in " + label + " to close.");
        }

        Long retainedAcc = getAccountIdOrDefault("3030", "Retained Earnings", "EQUITY");
        if (netTransfer.compareTo(BigDecimal.ZERO) > 0) {
            closingLines.add(new GeneralLedgerDtos.CreateLineRequest(retainedAcc, "3030", BigDecimal.ZERO, netTransfer.setScale(2, RoundingMode.HALF_UP), null,
                    "Net Profit transferred to Retained Earnings (" + label + ")", null));
        } else if (netTransfer.compareTo(BigDecimal.ZERO) < 0) {
            closingLines.add(new GeneralLedgerDtos.CreateLineRequest(retainedAcc, "3030", netTransfer.abs().setScale(2, RoundingMode.HALF_UP), BigDecimal.ZERO, null,
                    "Net Loss transferred to Retained Earnings (" + label + ")", null));
        }

        String safeActor = (actor != null && !actor.isBlank()) ? actor : (req.closedBy() != null ? req.closedBy() : "ADMIN");
        GeneralLedgerDtos.JournalEntryDto closingVoucher = postJournalEntry(new GeneralLedgerDtos.CreateJournalRequest(
                req.toDate(), "CLOSING", "FISCAL_YEAR_CLOSE", null, label,
                "Year-End Fiscal Closing & Retained Earnings Transfer for " + label,
                safeActor, closingLines
        ));

        jdbc.update("INSERT INTO fiscal_year_close_log(fy_label, from_date, to_date, net_profit, retained_earnings_entry_id, closed_by) VALUES (?, ?, ?, ?, ?, ?)",
                label, req.fromDate(), req.toDate(), netTransfer.setScale(2, RoundingMode.HALF_UP), closingVoucher.id(), safeActor);

        LocalDate lockedUntil = getPeriodLock().lockedUntil();
        if (req.lockPeriod()) {
            setPeriodLock(req.toDate(), safeActor);
            lockedUntil = req.toDate();
        }
        recordAudit("FISCAL_CLOSE", closingVoucher.id(), "CLOSED",
                "Closed " + label + "; Net Profit " + netTransfer.setScale(2, RoundingMode.HALF_UP) + " transferred to 3030 Retained Earnings",
                List.of(new AuditService.Change("Net Profit Transferred", "0.00", netTransfer.setScale(2, RoundingMode.HALF_UP).toPlainString())));

        return new GeneralLedgerDtos.FiscalYearCloseResponse(
                closingVoucher.id(), closingVoucher.entryNumber(), label,
                netTransfer.setScale(2, RoundingMode.HALF_UP), lockedUntil,
                "Fiscal Year " + label + " closed cleanly via voucher " + closingVoucher.entryNumber() + "."
        );
    }
}
