package org.example.server.reconciliation;

import org.example.server.persistence.JpaNativeRepository;
import org.example.server.persistence.entity.BankReconciliationAuditEntity;
import org.example.server.persistence.entity.BankReconciliationRuleEntity;
import org.example.server.persistence.entity.BankStatementTransactionEntity;
import org.example.server.persistence.repository.BankReconciliationAuditRepository;
import org.example.server.persistence.repository.BankReconciliationRuleRepository;
import org.example.server.persistence.repository.BankStatementTransactionRepository;
import org.example.server.security.CurrentUser;
import org.example.server.util.BusinessClock;
import org.example.shared.BankNarrationParser;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Pattern;

@Service
public class BankReconciliationRuleService {

    private static final Set<String> OPEN_STATUSES = Set.of("UNMATCHED", "SUGGESTED", "REVIEW");

    private final BankReconciliationRuleRepository rules;
    private final BankStatementTransactionRepository txs;
    private final BankReconciliationAuditRepository audits;
    private final JpaNativeRepository jdbc;
    private final BankReconciliationService reconciliationService;

    public BankReconciliationRuleService(
            BankReconciliationRuleRepository rules,
            BankStatementTransactionRepository txs,
            BankReconciliationAuditRepository audits,
            JpaNativeRepository jdbc,
            @Lazy BankReconciliationService reconciliationService) {
        this.rules = rules;
        this.txs = txs;
        this.audits = audits;
        this.jdbc = jdbc;
        this.reconciliationService = reconciliationService;
    }

    public boolean isFeatureEnabled() {
        return readBooleanSetting("module.bankRules.enabled", true);
    }

    public boolean isSuggestOnImportEnabled() {
        return isFeatureEnabled() && readBooleanSetting("module.bankRules.suggestOnImport", true);
    }

    public boolean isAutoApplyOnImportEnabled() {
        return isFeatureEnabled() && readBooleanSetting("module.bankRules.autoApplyOnImport", false);
    }

    public List<BankReconciliationDtos.RuleDto> listRules() {
        return rules.findAllByOrderByPriorityAscIdAsc().stream().map(this::toDto).toList();
    }

    public List<BankReconciliationDtos.RuleDto> listActiveRules() {
        return rules.findByActiveTrueOrderByPriorityAscIdAsc().stream().map(this::toDto).toList();
    }

    public List<BankReconciliationDtos.PartyOptionDto> listActiveParties() {
        return jdbc.query(
                "SELECT id, name, COALESCE(party_type, 'CUSTOMER') FROM party_master WHERE COALESCE(is_active, 1) <> 0 ORDER BY name",
                (r, i) -> new BankReconciliationDtos.PartyOptionDto(r.getInt(1), safe(r.getString(2)), safe(r.getString(3)))
        );
    }

    @Transactional
    public BankReconciliationDtos.RuleDto saveRule(Long id, BankReconciliationDtos.RuleSaveRequest req) {
        if (req == null) throw new IllegalArgumentException("Rule details are required.");
        String name = safe(req.ruleName()).trim();
        String pattern = safe(req.matchPattern()).trim();
        if (name.isBlank()) throw new IllegalArgumentException("Rule name is required.");
        if (pattern.isBlank()) throw new IllegalArgumentException("Match pattern is required.");

        String op = normalizeOperator(req.matchOperator());
        if ("REGEX".equals(op)) {
            try {
                Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
            } catch (Exception ex) {
                throw new IllegalArgumentException("Invalid regular expression in Match Pattern: " + ex.getMessage());
            }
        }
        if (req.minAmount() != null && req.maxAmount() != null && req.minAmount() > req.maxAmount()) {
            throw new IllegalArgumentException("Minimum amount cannot exceed maximum amount.");
        }

        String actionType = normalizeAction(req.actionType());
        if ("EXPENSE".equals(actionType) && safe(req.targetCategory()).trim().isBlank()) {
            throw new IllegalArgumentException("Expense category is required for EXPENSE rules.");
        }
        if ("MATCH_PARTY_FIFO".equals(actionType) && (req.targetPartyId() == null || req.targetPartyId() <= 0)) {
            throw new IllegalArgumentException("Select a Customer / Supplier for Party FIFO Settlement rules.");
        }
        if ("CONTRA".equals(actionType) && safe(req.targetContraAccount()).trim().isBlank()) {
            throw new IllegalArgumentException("Select a destination Contra Bank Account for CONTRA rules.");
        }

        BankReconciliationRuleEntity entity = (id != null && id > 0)
                ? rules.findById(id).orElseThrow(() -> new IllegalArgumentException("Reconciliation rule not found."))
                : new BankReconciliationRuleEntity();

        entity.setRuleName(name);
        entity.setMatchField(normalizeField(req.matchField()));
        entity.setMatchOperator(op);
        entity.setMatchPattern(pattern);
        entity.setDirection(normalizeDirection(req.direction()));
        entity.setActionType(actionType);
        entity.setTargetCategory(trimOrNull(req.targetCategory()));
        entity.setTargetAccountName(trimOrNull(req.targetAccountName()));
        entity.setTargetPaymentMode(trimOrNull(req.targetPaymentMode()));
        entity.setTargetNotes(trimOrNull(req.targetNotes()));
        entity.setMinAmount(req.minAmount() != null && req.minAmount() > 0 ? req.minAmount() : null);
        entity.setMaxAmount(req.maxAmount() != null && req.maxAmount() > 0 ? req.maxAmount() : null);
        entity.setChannelFilter(normalizeOptionalUpper(req.channelFilter()));
        entity.setBankAccountFilter(trimOrNull(req.bankAccountFilter()));
        entity.setTargetPartyId(req.targetPartyId() != null && req.targetPartyId() > 0 ? req.targetPartyId() : null);
        entity.setTargetContraAccount(trimOrNull(req.targetContraAccount()));
        entity.setExecutionMode(normalizeMode(req.executionMode()));
        entity.setPriority(req.priority() == null ? 100 : Math.max(1, Math.min(req.priority(), 9999)));
        entity.setActive(req.active() == null || req.active());
        if (entity.getCreatedBy() == null || entity.getCreatedBy().isBlank()) {
            entity.setCreatedBy(currentActor());
        }

        return toDto(rules.save(entity));
    }

    @Transactional
    public void deleteRule(Long id) {
        if (id == null || id <= 0) return;
        rules.deleteById(id);
    }

    @Transactional(readOnly = true)
    public BankReconciliationDtos.RulePreviewResult previewBatch(Long batchId, BankReconciliationDtos.RuleSaveRequest candidate) {
        boolean enabled = isFeatureEnabled();
        List<BankStatementTransactionEntity> openRows = loadOpenTransactions(batchId);
        List<BankReconciliationRuleEntity> evalRules;
        if (candidate != null && !safe(candidate.matchPattern()).isBlank()) {
            evalRules = List.of(toEphemeralRule(candidate));
        } else {
            evalRules = rules.findByActiveTrueOrderByPriorityAscIdAsc();
        }

        List<BankReconciliationDtos.RulePreviewMatchDto> matches = new ArrayList<>();
        double totalAmt = 0d;
        for (BankStatementTransactionEntity tx : openRows) {
            BankReconciliationRuleEntity hit = findFirstMatch(tx, evalRules);
            if (hit == null) continue;
            double amt = txAmount(tx);
            totalAmt += amt;
            matches.add(new BankReconciliationDtos.RulePreviewMatchDto(
                    tx.getId(),
                    safe(tx.getTransactionDate()),
                    safe(tx.getOriginalDescription()),
                    safe(tx.getOriginalReference()),
                    amt,
                    isCredit(tx) ? "CREDIT" : "DEBIT",
                    hit.getRuleName(),
                    hit.getActionType(),
                    describeTarget(hit),
                    hit.getExecutionMode()
            ));
        }

        return new BankReconciliationDtos.RulePreviewResult(enabled, openRows.size(), matches.size(), totalAmt, matches);
    }

    @Transactional
    public BankReconciliationDtos.RuleApplyResult applyRulesToBatch(Long batchId, boolean onImport, String actor) {
        return applyRulesInternal(batchId, null, onImport, actor);
    }

    @Transactional
    public BankReconciliationDtos.RuleApplyResult applySingleRuleToBatch(Long batchId, Long ruleId, String actor) {
        if (ruleId == null || ruleId <= 0) throw new IllegalArgumentException("Rule ID is required.");
        return applyRulesInternal(batchId, ruleId, false, actor);
    }

    @Transactional
    public BankReconciliationDtos.OperationResult executeRuleForTransaction(Long txId, Long ruleId, String actor) {
        if (!isFeatureEnabled()) {
            return new BankReconciliationDtos.OperationResult(false, "Smart Bank Rules are turned OFF in Settings.", "UNMATCHED", null);
        }
        BankStatementTransactionEntity tx = txs.findByIdForUpdate(txId)
                .orElseThrow(() -> new IllegalArgumentException("Bank statement transaction not found."));
        BankReconciliationRuleEntity rule = rules.findById(ruleId)
                .orElseThrow(() -> new IllegalArgumentException("Reconciliation rule not found."));
        String performedBy = (actor == null || actor.isBlank()) ? currentActor() : actor;
        boolean ok = executeRuleOnTransaction(tx, rule, performedBy);
        if (!ok) {
            return new BankReconciliationDtos.OperationResult(false, "Rule '" + rule.getRuleName() + "' is not applicable to this transaction.", tx.getStatus(), null);
        }
        rule.setHitCount(rule.getHitCount() + 1);
        rule.setLastMatchedAt(BusinessClock.nowUtcText());
        rules.save(rule);
        return new BankReconciliationDtos.OperationResult(true, "Applied rule '" + rule.getRuleName() + "'.", tx.getStatus(), null);
    }

    private BankReconciliationDtos.RuleApplyResult applyRulesInternal(Long batchId, Long onlyRuleId, boolean onImport, String actor) {
        if (!isFeatureEnabled()) {
            return new BankReconciliationDtos.RuleApplyResult(false, 0, 0, 0,
                    "Smart Bank Rules are turned OFF in Settings -> Module Activation.");
        }
        if (onImport && !isSuggestOnImportEnabled() && !isAutoApplyOnImportEnabled()) {
            return new BankReconciliationDtos.RuleApplyResult(true, 0, 0, 0,
                    "Import rule evaluation skipped by Settings.");
        }

        List<BankReconciliationRuleEntity> activeRules;
        if (onlyRuleId != null) {
            BankReconciliationRuleEntity one = rules.findById(onlyRuleId)
                    .orElseThrow(() -> new IllegalArgumentException("Reconciliation rule not found."));
            activeRules = List.of(one);
        } else {
            activeRules = rules.findByActiveTrueOrderByPriorityAscIdAsc();
        }
        if (activeRules.isEmpty()) {
            return new BankReconciliationDtos.RuleApplyResult(true, 0, 0, 0, "No active bank reconciliation rules configured.");
        }

        String performedBy = (actor == null || actor.isBlank()) ? currentActor() : actor;
        List<BankStatementTransactionEntity> openRows = loadOpenTransactions(batchId);
        int suggested = 0;
        int autoApplied = 0;
        int skipped = 0;
        Map<Long, Integer> hitIncrements = new HashMap<>();

        for (BankStatementTransactionEntity tx : openRows) {
            BankReconciliationRuleEntity rule = findFirstMatch(tx, activeRules, onlyRuleId != null);
            if (rule == null) continue;

            boolean canAutoApply = "AUTO_APPLY".equalsIgnoreCase(rule.getExecutionMode())
                    && (!onImport || isAutoApplyOnImportEnabled());

            if (canAutoApply) {
                try {
                    boolean applied = executeRuleOnTransaction(tx, rule, performedBy);
                    if (applied) {
                        autoApplied++;
                        if (rule.getId() != null) hitIncrements.merge(rule.getId(), 1, Integer::sum);
                    } else {
                        skipped++;
                    }
                } catch (Exception ex) {
                    skipped++;
                }
            } else if (!onImport || isSuggestOnImportEnabled()) {
                String prev = tx.getStatus();
                tx.setStatus("SUGGESTED");
                tx.setSuggestedMatchType(formatSuggestedRuleTag(rule));
                tx.setSuggestedConfidence(95.0);
                txs.save(tx);
                recordAudit(tx, "RULE_SUGGESTED",
                        "Matched smart rule '" + rule.getRuleName() + "' (" + describeTarget(rule) + ")",
                        prev, "SUGGESTED", performedBy);
                suggested++;
                if (rule.getId() != null) hitIncrements.merge(rule.getId(), 1, Integer::sum);
            }
        }

        if (!hitIncrements.isEmpty()) {
            String now = BusinessClock.nowUtcText();
            for (BankReconciliationRuleEntity r : activeRules) {
                Integer inc = hitIncrements.get(r.getId());
                if (inc != null && inc > 0) {
                    r.setHitCount(r.getHitCount() + inc);
                    r.setLastMatchedAt(now);
                    rules.save(r);
                }
            }
        }

        String summary = (onlyRuleId != null ? "Rule applied: " : "Rules processed: ")
                + autoApplied + " auto-applied, " + suggested + " suggested"
                + (skipped > 0 ? (", " + skipped + " skipped") : "") + ".";
        return new BankReconciliationDtos.RuleApplyResult(true, suggested, autoApplied, skipped, summary);
    }

    /** Evaluates the highest-priority active rule matching a single open transaction (used by Auto Match Preview). */
    public BankReconciliationDtos.RuleDto findMatchingRuleDto(BankStatementTransactionEntity tx) {
        if (!isFeatureEnabled() || tx == null) return null;
        List<BankReconciliationRuleEntity> activeRules = rules.findByActiveTrueOrderByPriorityAscIdAsc();
        BankReconciliationRuleEntity match = findFirstMatch(tx, activeRules, false);
        return match == null ? null : toDto(match);
    }

    @Transactional
    public boolean executeRuleOnTransaction(BankStatementTransactionEntity tx, BankReconciliationRuleEntity rule, String actor) {
        String action = normalizeAction(rule.getActionType());
        String note = "Auto-rule '" + rule.getRuleName() + "'"
                + (safe(rule.getTargetNotes()).isBlank() ? "" : " — " + rule.getTargetNotes().trim());
        String account = safe(rule.getTargetAccountName()).isBlank()
                ? (tx.getImportBatch().getBankName() + " - " + tx.getImportBatch().getBankAccount())
                : rule.getTargetAccountName().trim();
        String mode = safe(rule.getTargetPaymentMode()).isBlank() ? "Bank Transfer" : rule.getTargetPaymentMode().trim();

        return switch (action) {
            case "EXPENSE" -> {
                if (n(tx.getDebitAmount()) <= 0) yield false;
                String cat = safe(rule.getTargetCategory()).trim();
                if (cat.isBlank()) yield false;
                reconciliationService.expense(tx.getId(), new BankReconciliationDtos.ExpenseRequest(cat, account, mode, note, null, actor));
                yield true;
            }
            case "BANK_ENTRY" -> {
                reconciliationService.bankEntry(tx.getId(), new BankReconciliationDtos.BankEntryRequest(account, mode, note, actor));
                yield true;
            }
            case "CONTRA" -> {
                String contraAcct = safe(rule.getTargetContraAccount()).trim();
                if (contraAcct.isBlank()) yield false;
                reconciliationService.bankEntry(tx.getId(),
                        new BankReconciliationDtos.BankEntryRequest(account, mode, note, actor, "CONTRA TRANSFER", null, contraAcct));
                yield true;
            }
            case "MATCH_PARTY_FIFO" -> executePartyFifoMatch(tx, rule, account, mode, note, actor);
            case "IGNORE" -> {
                reconciliationService.ignore(tx.getId(), new BankReconciliationDtos.IgnoreRequest(note, actor));
                yield true;
            }
            default -> false;
        };
    }

    private boolean executePartyFifoMatch(
            BankStatementTransactionEntity tx,
            BankReconciliationRuleEntity rule,
            String account,
            String mode,
            String note,
            String actor) {
        Integer partyId = rule.getTargetPartyId();
        if (partyId == null || partyId <= 0) return false;
        boolean credit = isCredit(tx);
        double amount = txAmount(tx);
        if (amount <= 0) return false;

        List<Map<String, Object>> openDocs = credit
                ? jdbc.queryForList("""
                        SELECT id, (COALESCE(total_amount, 0) - COALESCE(paid_amount, 0)) AS outstanding
                        FROM sales_header
                        WHERE customer_id = ?
                          AND (COALESCE(total_amount, 0) - COALESCE(paid_amount, 0)) > 0.009
                          AND UPPER(COALESCE(document_status, '')) NOT IN ('CANCELLED', 'DELETED', 'REJECTED', 'PENDING APPROVAL')
                        ORDER BY invoice_date ASC NULLS LAST, id ASC
                        """, partyId)
                : jdbc.queryForList("""
                        SELECT id, (COALESCE(total_amount, 0) - COALESCE(paid_amount, 0)) AS outstanding
                        FROM purchase_header
                        WHERE supplier_id = ?
                          AND (COALESCE(total_amount, 0) - COALESCE(paid_amount, 0)) > 0.009
                          AND COALESCE(inventory_posted, false) = true
                          AND UPPER(COALESCE(document_status, '')) NOT IN ('CANCELLED', 'DELETED', 'DRAFT', 'RETURNED', 'PARTIALLY RETURNED', 'REJECTED', 'PENDING APPROVAL')
                        ORDER BY invoice_date ASC NULLS LAST, id ASC
                        """, partyId);

        String targetType = credit ? "SALE" : "PURCHASE";
        List<BankReconciliationDtos.AllocationRequest> allocations = new ArrayList<>();
        double remaining = Math.round(amount * 100d) / 100d;
        for (Map<String, Object> doc : openDocs) {
            if (remaining <= 0.009) break;
            int docId = ((Number) doc.get("id")).intValue();
            double out = ((Number) doc.get("outstanding")).doubleValue();
            if (out <= 0.009) continue;
            double alloc = Math.min(out, remaining);
            alloc = Math.round(alloc * 100d) / 100d;
            if (alloc > 0.009) {
                allocations.add(new BankReconciliationDtos.AllocationRequest(targetType, docId, alloc));
                remaining = Math.round((remaining - alloc) * 100d) / 100d;
            }
        }

        if (!allocations.isEmpty() && remaining <= 0.01) {
            reconciliationService.match(tx.getId(), new BankReconciliationDtos.MatchRequest(actor, allocations));
            return true;
        }
        // Fallback when no open invoice covers the full payment: record Party On-Account / Advance Bank Entry
        String advCategory = credit ? "Customer Advance" : "Supplier Advance";
        reconciliationService.bankEntry(tx.getId(),
                new BankReconciliationDtos.BankEntryRequest(account, mode, note, actor, advCategory, partyId, null));
        return true;
    }

    /** Discovers recurring manual categorizations (>= 2 occurrences) not yet covered by any active rule. */
    @Transactional(readOnly = true)
    public List<BankReconciliationDtos.RuleSuggestionCandidateDto> discoverRuleSuggestions() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT t.original_description,
                       COALESCE(t.debit_amount, 0) AS debit_amt,
                       COALESCE(t.credit_amount, 0) AS credit_amt,
                       t.status,
                       COALESCE(f.category, '') AS category,
                       COALESCE(f.account_name, '') AS account_name
                FROM bank_statement_transaction t
                JOIN bank_reconciliation_allocation a ON a.statement_transaction_id = t.id AND a.reversed_at IS NULL
                LEFT JOIN finance_register f ON f.id = a.finance_entry_id
                WHERE UPPER(COALESCE(t.status, '')) IN ('EXPENSE', 'MATCHED')
                  AND a.target_type IN ('EXPENSE', 'BANK_ENTRY')
                ORDER BY t.id DESC
                LIMIT 1000
                """);

        List<BankReconciliationRuleEntity> existingRules = rules.findByActiveTrueOrderByPriorityAscIdAsc();
        record GroupKey(String pattern, String direction, String actionType, String category, String accountName) {}
        Map<GroupKey, List<Double>> grouped = new LinkedHashMap<>();

        for (Map<String, Object> r : rows) {
            String desc = Objects.toString(r.get("original_description"), "");
            double debit = ((Number) r.get("debit_amt")).doubleValue();
            double credit = ((Number) r.get("credit_amt")).doubleValue();
            String status = Objects.toString(r.get("status"), "");
            String cat = Objects.toString(r.get("category"), "").trim();
            String acct = Objects.toString(r.get("account_name"), "").trim();

            String pattern = extractDiscoveryPattern(desc);
            if (pattern.isBlank() || pattern.length() < 4) continue;
            String dir = credit > 0 && debit <= 0 ? "CREDIT" : "DEBIT";
            String action = "EXPENSE".equalsIgnoreCase(status) ? "EXPENSE" : "BANK_ENTRY";
            if ("EXPENSE".equals(action) && cat.isBlank()) continue;

            // Skip if an existing active rule already covers this pattern
            String patLower = pattern.toLowerCase(Locale.ROOT);
            boolean covered = existingRules.stream().anyMatch(rule ->
                    safe(rule.getMatchPattern()).trim().equalsIgnoreCase(pattern)
                    || patLower.contains(safe(rule.getMatchPattern()).trim().toLowerCase(Locale.ROOT)));
            if (covered) continue;

            double amt = debit > 0 ? debit : credit;
            grouped.computeIfAbsent(new GroupKey(pattern, dir, action, cat, acct), k -> new ArrayList<>()).add(amt);
        }

        return grouped.entrySet().stream()
                .filter(e -> e.getValue().size() >= 2)
                .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
                .limit(25)
                .map(e -> {
                    GroupKey k = e.getKey();
                    double total = e.getValue().stream().mapToDouble(Double::doubleValue).sum();
                    String name = "Auto: " + k.pattern() + ("EXPENSE".equals(k.actionType()) ? " (" + k.category() + ")" : "");
                    return new BankReconciliationDtos.RuleSuggestionCandidateDto(
                            name, k.pattern(), k.direction(), k.actionType(), k.category(), k.accountName(), e.getValue().size(), total);
                })
                .toList();
    }

    private static String extractDiscoveryPattern(String desc) {
        var parsed = BankNarrationParser.parse(desc, "");
        if (parsed.hasCounterparty() && parsed.counterparty().trim().length() >= 4) {
            return parsed.counterparty().trim();
        }
        String s = safe(desc).replaceAll("\\s+", " ").trim();
        for (String p : s.split("[-/|]+")) {
            String cleaned = p.trim();
            if (cleaned.length() >= 4 && !cleaned.matches("^[0-9X]+$")
                    && !Set.of("UPI", "IMPS", "NEFT", "RTGS", "ACH", "REV", "NACH", "POS").contains(cleaned.toUpperCase(Locale.ROOT))) {
                return cleaned.length() > 48 ? cleaned.substring(0, 48).trim() : cleaned;
            }
        }
        return "";
    }

    private BankReconciliationRuleEntity toEphemeralRule(BankReconciliationDtos.RuleSaveRequest candidate) {
        BankReconciliationRuleEntity temp = new BankReconciliationRuleEntity();
        temp.setRuleName(safe(candidate.ruleName()).isBlank() ? "Test Rule" : candidate.ruleName().trim());
        temp.setMatchField(normalizeField(candidate.matchField()));
        temp.setMatchOperator(normalizeOperator(candidate.matchOperator()));
        temp.setMatchPattern(candidate.matchPattern().trim());
        temp.setDirection(normalizeDirection(candidate.direction()));
        temp.setActionType(normalizeAction(candidate.actionType()));
        temp.setTargetCategory(trimOrNull(candidate.targetCategory()));
        temp.setTargetAccountName(trimOrNull(candidate.targetAccountName()));
        temp.setTargetPaymentMode(trimOrNull(candidate.targetPaymentMode()));
        temp.setTargetNotes(trimOrNull(candidate.targetNotes()));
        temp.setMinAmount(candidate.minAmount() != null && candidate.minAmount() > 0 ? candidate.minAmount() : null);
        temp.setMaxAmount(candidate.maxAmount() != null && candidate.maxAmount() > 0 ? candidate.maxAmount() : null);
        temp.setChannelFilter(normalizeOptionalUpper(candidate.channelFilter()));
        temp.setBankAccountFilter(trimOrNull(candidate.bankAccountFilter()));
        temp.setTargetPartyId(candidate.targetPartyId() != null && candidate.targetPartyId() > 0 ? candidate.targetPartyId() : null);
        temp.setTargetContraAccount(trimOrNull(candidate.targetContraAccount()));
        temp.setExecutionMode(normalizeMode(candidate.executionMode()));
        temp.setActive(true);
        return temp;
    }

    private List<BankStatementTransactionEntity> loadOpenTransactions(Long batchId) {
        if (batchId == null || batchId <= 0) return List.of();
        return txs.findByImportBatchIdOrderByTransactionTimestampAscIdAsc(batchId).stream()
                .filter(t -> OPEN_STATUSES.contains(safe(t.getStatus()).trim().toUpperCase(Locale.ROOT)))
                .toList();
    }

    private BankReconciliationRuleEntity findFirstMatch(BankStatementTransactionEntity tx, List<BankReconciliationRuleEntity> list) {
        return findFirstMatch(tx, list, false);
    }

    private BankReconciliationRuleEntity findFirstMatch(BankStatementTransactionEntity tx, List<BankReconciliationRuleEntity> list, boolean allowInactiveSingleRun) {
        boolean credit = isCredit(tx);
        double amt = txAmount(tx);
        String txAccount = tx.getImportBatch() != null
                ? (safe(tx.getImportBatch().getBankName()) + " " + safe(tx.getImportBatch().getBankAccount())).toLowerCase(Locale.ROOT)
                : "";
        String detectedChannel = BankNarrationParser.detectChannel(tx.getOriginalDescription(), tx.getOriginalReference());

        for (BankReconciliationRuleEntity rule : list) {
            if (!allowInactiveSingleRun && !rule.isActive()) continue;
            String dir = normalizeDirection(rule.getDirection());
            if ("DEBIT".equals(dir) && credit) continue;
            if ("CREDIT".equals(dir) && !credit) continue;
            if ("EXPENSE".equalsIgnoreCase(rule.getActionType()) && credit) continue;

            if (rule.getMinAmount() != null && amt + 0.001 < rule.getMinAmount()) continue;
            if (rule.getMaxAmount() != null && amt - 0.001 > rule.getMaxAmount()) continue;
            if (rule.getChannelFilter() != null && !rule.getChannelFilter().isBlank()
                    && !"ALL".equalsIgnoreCase(rule.getChannelFilter())
                    && !rule.getChannelFilter().equalsIgnoreCase(detectedChannel)) continue;
            if (rule.getBankAccountFilter() != null && !rule.getBankAccountFilter().isBlank()
                    && !txAccount.contains(rule.getBankAccountFilter().trim().toLowerCase(Locale.ROOT))) continue;

            String hay = "REFERENCE".equalsIgnoreCase(rule.getMatchField())
                    ? safe(tx.getOriginalReference())
                    : (safe(tx.getOriginalDescription()) + " " + safe(tx.getOriginalReference()));
            String hayLower = hay.trim().toLowerCase(Locale.ROOT);
            String pat = safe(rule.getMatchPattern()).trim();
            if (pat.isBlank()) continue;
            String patLower = pat.toLowerCase(Locale.ROOT);

            boolean matched = switch (normalizeOperator(rule.getMatchOperator())) {
                case "EXACT" -> hayLower.equals(patLower)
                        || safe(tx.getOriginalDescription()).trim().toLowerCase(Locale.ROOT).equals(patLower)
                        || safe(tx.getOriginalReference()).trim().toLowerCase(Locale.ROOT).equals(patLower);
                case "STARTS_WITH" -> hayLower.startsWith(patLower)
                        || safe(tx.getOriginalDescription()).trim().toLowerCase(Locale.ROOT).startsWith(patLower);
                case "REGEX" -> matchesRegexSafe(pat, hay) || matchesRegexSafe(pat, safe(tx.getOriginalDescription()));
                default -> hayLower.contains(patLower);
            };
            if (matched) return rule;
        }
        return null;
    }

    private static boolean matchesRegexSafe(String regex, String target) {
        if (target == null) return false;
        try {
            return Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(target).find();
        } catch (Exception ignored) {
            return false;
        }
    }

    private void recordAudit(BankStatementTransactionEntity tx, String event, String detail, String oldStatus, String newStatus, String actor) {
        BankReconciliationAuditEntity a = new BankReconciliationAuditEntity();
        a.setStatementTransactionId(tx.getId());
        a.setEventType(event);
        a.setEventDetail(detail);
        a.setPreviousStatus(oldStatus);
        a.setNewStatus(newStatus);
        a.setPerformedBy(actor);
        audits.save(a);
    }

    private boolean readBooleanSetting(String key, boolean defaultValue) {
        try {
            List<String> values = jdbc.query(
                    "SELECT setting_value FROM application_setting WHERE setting_key = ?",
                    (rs, i) -> rs.getString(1),
                    key
            );
            if (values.isEmpty() || values.getFirst() == null || values.getFirst().isBlank()) {
                return defaultValue;
            }
            return Boolean.parseBoolean(values.getFirst().trim());
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private BankReconciliationDtos.RuleDto toDto(BankReconciliationRuleEntity e) {
        return new BankReconciliationDtos.RuleDto(
                e.getId(), e.getRuleName(), e.getMatchField(), e.getMatchOperator(), e.getMatchPattern(),
                e.getDirection(), e.getActionType(), e.getTargetCategory(), e.getTargetAccountName(),
                e.getTargetPaymentMode(), e.getTargetNotes(), e.getExecutionMode(),
                e.getPriority(), e.isActive(), e.getHitCount(), e.getLastMatchedAt(),
                e.getCreatedBy(), e.getCreatedAt(), e.getUpdatedAt(),
                e.getMinAmount(), e.getMaxAmount(), e.getChannelFilter(),
                e.getBankAccountFilter(), e.getTargetPartyId(), e.getTargetContraAccount()
        );
    }

    private static String describeTarget(BankReconciliationRuleEntity r) {
        return switch (normalizeAction(r.getActionType())) {
            case "EXPENSE" -> "Expense → " + (safe(r.getTargetCategory()).isBlank() ? "Uncategorized" : r.getTargetCategory());
            case "BANK_ENTRY" -> "Bank Entry → " + (safe(r.getTargetAccountName()).isBlank() ? "Statement Account" : r.getTargetAccountName());
            case "CONTRA" -> "Contra → " + (safe(r.getTargetContraAccount()).isBlank() ? "Counter Account" : r.getTargetContraAccount());
            case "MATCH_PARTY_FIFO" -> "Auto-Settle Party Invoices (FIFO)";
            case "IGNORE" -> "Ignore (" + (safe(r.getTargetNotes()).isBlank() ? "Auto-ignored" : r.getTargetNotes()) + ")";
            default -> r.getActionType();
        };
    }

    private static boolean isCredit(BankStatementTransactionEntity tx) {
        return n(tx.getCreditAmount()) > 0 && n(tx.getDebitAmount()) <= 0;
    }

    private static double txAmount(BankStatementTransactionEntity tx) {
        return n(tx.getDebitAmount()) > 0 ? n(tx.getDebitAmount()) : n(tx.getCreditAmount());
    }

    private static double n(Double v) { return v == null ? 0d : v; }
    private static String safe(String s) { return s == null ? "" : s; }
    private static String trimOrNull(String s) { return (s == null || s.trim().isBlank()) ? null : s.trim(); }
    private static String normalizeOptionalUpper(String s) { String t = trimOrNull(s); return t == null ? null : t.toUpperCase(Locale.ROOT); }
    private static String normalizeField(String s) { return "REFERENCE".equalsIgnoreCase(safe(s).trim()) ? "REFERENCE" : "NARRATION"; }
    private static String normalizeOperator(String s) {
        String u = safe(s).trim().toUpperCase(Locale.ROOT);
        return Set.of("EXACT", "STARTS_WITH", "CONTAINS", "REGEX").contains(u) ? u : "CONTAINS";
    }
    private static String normalizeDirection(String s) {
        String u = safe(s).trim().toUpperCase(Locale.ROOT);
        return Set.of("DEBIT", "CREDIT", "BOTH").contains(u) ? u : "BOTH";
    }
    private static String normalizeAction(String s) {
        String u = safe(s).trim().toUpperCase(Locale.ROOT);
        return Set.of("EXPENSE", "BANK_ENTRY", "CONTRA", "MATCH_PARTY_FIFO", "IGNORE").contains(u) ? u : "EXPENSE";
    }
    private static String normalizeMode(String s) {
        return "AUTO_APPLY".equalsIgnoreCase(safe(s).trim()) ? "AUTO_APPLY" : "SUGGEST";
    }
    private static String formatSuggestedRuleTag(BankReconciliationRuleEntity rule) {
        String tag = "RULE:" + normalizeAction(rule.getActionType()) + ":" + safe(rule.getRuleName()).trim();
        return tag.length() > 255 ? tag.substring(0, 255) : tag;
    }
    private static String currentActor() {
        try {
            return CurrentUser.require().username();
        } catch (Exception ignored) {
            return "SYSTEM";
        }
    }
}
