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
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

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

    @Transactional
    public BankReconciliationDtos.RuleDto saveRule(Long id, BankReconciliationDtos.RuleSaveRequest req) {
        if (req == null) throw new IllegalArgumentException("Rule details are required.");
        String name = safe(req.ruleName()).trim();
        String pattern = safe(req.matchPattern()).trim();
        if (name.isBlank()) throw new IllegalArgumentException("Rule name is required.");
        if (pattern.isBlank()) throw new IllegalArgumentException("Match pattern is required.");

        String actionType = normalizeAction(req.actionType());
        if ("EXPENSE".equals(actionType) && safe(req.targetCategory()).trim().isBlank()) {
            throw new IllegalArgumentException("Expense category is required for EXPENSE rules.");
        }

        BankReconciliationRuleEntity entity = (id != null && id > 0)
                ? rules.findById(id).orElseThrow(() -> new IllegalArgumentException("Reconciliation rule not found."))
                : new BankReconciliationRuleEntity();

        entity.setRuleName(name);
        entity.setMatchField(normalizeField(req.matchField()));
        entity.setMatchOperator(normalizeOperator(req.matchOperator()));
        entity.setMatchPattern(pattern);
        entity.setDirection(normalizeDirection(req.direction()));
        entity.setActionType(actionType);
        entity.setTargetCategory(trimOrNull(req.targetCategory()));
        entity.setTargetAccountName(trimOrNull(req.targetAccountName()));
        entity.setTargetPaymentMode(trimOrNull(req.targetPaymentMode()));
        entity.setTargetNotes(trimOrNull(req.targetNotes()));
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
            temp.setExecutionMode(normalizeMode(candidate.executionMode()));
            temp.setActive(true);
            evalRules = List.of(temp);
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
        if (!isFeatureEnabled()) {
            return new BankReconciliationDtos.RuleApplyResult(false, 0, 0, 0,
                    "Smart Bank Rules are turned OFF in Settings -> Module Activation.");
        }
        if (onImport && !isSuggestOnImportEnabled() && !isAutoApplyOnImportEnabled()) {
            return new BankReconciliationDtos.RuleApplyResult(true, 0, 0, 0,
                    "Import rule evaluation skipped by Settings.");
        }

        List<BankReconciliationRuleEntity> activeRules = rules.findByActiveTrueOrderByPriorityAscIdAsc();
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
            BankReconciliationRuleEntity rule = findFirstMatch(tx, activeRules);
            if (rule == null) continue;

            boolean canAutoApply = "AUTO_APPLY".equalsIgnoreCase(rule.getExecutionMode())
                    && (!onImport || isAutoApplyOnImportEnabled());

            if (canAutoApply) {
                try {
                    boolean applied = executeAutoAction(tx, rule, performedBy);
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

        String summary = "Rules processed: " + autoApplied + " auto-applied, " + suggested + " suggested"
                + (skipped > 0 ? (", " + skipped + " skipped") : "") + ".";
        return new BankReconciliationDtos.RuleApplyResult(true, suggested, autoApplied, skipped, summary);
    }

    private boolean executeAutoAction(BankStatementTransactionEntity tx, BankReconciliationRuleEntity rule, String actor) {
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
            case "IGNORE" -> {
                reconciliationService.ignore(tx.getId(), new BankReconciliationDtos.IgnoreRequest(note, actor));
                yield true;
            }
            default -> false;
        };
    }

    private List<BankStatementTransactionEntity> loadOpenTransactions(Long batchId) {
        if (batchId == null || batchId <= 0) return List.of();
        return txs.findByImportBatchIdOrderByTransactionTimestampAscIdAsc(batchId).stream()
                .filter(t -> OPEN_STATUSES.contains(safe(t.getStatus()).trim().toUpperCase(Locale.ROOT)))
                .toList();
    }

    private BankReconciliationRuleEntity findFirstMatch(BankStatementTransactionEntity tx, List<BankReconciliationRuleEntity> list) {
        boolean credit = isCredit(tx);
        for (BankReconciliationRuleEntity rule : list) {
            if (!rule.isActive()) continue;
            String dir = normalizeDirection(rule.getDirection());
            if ("DEBIT".equals(dir) && credit) continue;
            if ("CREDIT".equals(dir) && !credit) continue;
            if ("EXPENSE".equalsIgnoreCase(rule.getActionType()) && credit) continue;

            String hay = "REFERENCE".equalsIgnoreCase(rule.getMatchField())
                    ? safe(tx.getOriginalReference())
                    : (safe(tx.getOriginalDescription()) + " " + safe(tx.getOriginalReference()));
            String hayLower = hay.trim().toLowerCase(Locale.ROOT);
            String patLower = safe(rule.getMatchPattern()).trim().toLowerCase(Locale.ROOT);
            if (patLower.isBlank()) continue;

            boolean matched = switch (normalizeOperator(rule.getMatchOperator())) {
                case "EXACT" -> hayLower.equals(patLower)
                        || safe(tx.getOriginalDescription()).trim().toLowerCase(Locale.ROOT).equals(patLower)
                        || safe(tx.getOriginalReference()).trim().toLowerCase(Locale.ROOT).equals(patLower);
                case "STARTS_WITH" -> hayLower.startsWith(patLower)
                        || safe(tx.getOriginalDescription()).trim().toLowerCase(Locale.ROOT).startsWith(patLower);
                default -> hayLower.contains(patLower);
            };
            if (matched) return rule;
        }
        return null;
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
                e.getCreatedBy(), e.getCreatedAt(), e.getUpdatedAt()
        );
    }

    private static String describeTarget(BankReconciliationRuleEntity r) {
        return switch (normalizeAction(r.getActionType())) {
            case "EXPENSE" -> "Expense → " + (safe(r.getTargetCategory()).isBlank() ? "Uncategorized" : r.getTargetCategory());
            case "BANK_ENTRY" -> "Bank Entry → " + (safe(r.getTargetAccountName()).isBlank() ? "Statement Account" : r.getTargetAccountName());
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
    private static String normalizeField(String s) { return "REFERENCE".equalsIgnoreCase(safe(s).trim()) ? "REFERENCE" : "NARRATION"; }
    private static String normalizeOperator(String s) {
        String u = safe(s).trim().toUpperCase(Locale.ROOT);
        return Set.of("EXACT", "STARTS_WITH", "CONTAINS").contains(u) ? u : "CONTAINS";
    }
    private static String normalizeDirection(String s) {
        String u = safe(s).trim().toUpperCase(Locale.ROOT);
        return Set.of("DEBIT", "CREDIT", "BOTH").contains(u) ? u : "BOTH";
    }
    private static String normalizeAction(String s) {
        String u = safe(s).trim().toUpperCase(Locale.ROOT);
        return Set.of("EXPENSE", "BANK_ENTRY", "IGNORE").contains(u) ? u : "EXPENSE";
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
