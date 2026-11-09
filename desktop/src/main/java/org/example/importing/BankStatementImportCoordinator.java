package org.example.importing;

import org.example.api.bank.BankStatementApiClient;
import org.example.bank.UniversalBankStatementParser;
import org.example.service.ImportService;
import org.example.service.SessionService;

import java.nio.file.Path;
import java.util.List;

/** Executes Bank Statement imports and normalizes the result for the generic Import wizard. */
public final class BankStatementImportCoordinator {
    private final BankStatementApiClient api;
    private final UniversalBankStatementParser parser;

    public BankStatementImportCoordinator() {
        this(new BankStatementApiClient(), new UniversalBankStatementParser());
    }

    BankStatementImportCoordinator(BankStatementApiClient api, UniversalBankStatementParser parser) {
        this.api = api;
        this.parser = parser;
    }

    public ImportService.ImportResult execute(Path file, boolean dryRun) throws Exception {
        return execute(file, dryRun, null);
    }

    public ImportService.ImportResult execute(Path file, boolean dryRun, ImportService.ImportProgressCallback progress) throws Exception {
        if (progress != null) {
            progress.onProgress("Parsing Statement", "Validating statement transactions from file…", 1, 3);
        }
        var auditResult = parser.parseWithAudit(file);
        if (auditResult.statement() == null) {
            throw new IllegalArgumentException("No valid bank statement transactions found. Errors encountered: " + auditResult.errorCount());
        }
        var parsed = auditResult.statement();
        var current = SessionService.current();
        String user = current == null ? "User" : current.getFullName();

        String bankAccount = parsed.accountNumber();
        String bankName = parsed.bankName();
        String accHolder = parsed.accountHolder();

        if (bankAccount == null || bankAccount.isBlank()) {
            try {
                var lookups = new org.example.api.master.MasterApiClient().lookupsByCategoryCode("BANK_ACCOUNT");
                if (lookups != null && !lookups.isEmpty()) {
                    var first = lookups.getFirst();
                    bankAccount = (first.getLookupValue() != null && !first.getLookupValue().isBlank()) ? first.getLookupValue().trim() : "0000000000";
                    if (bankName == null || bankName.isBlank()) {
                        bankName = (first.getDescription() != null && !first.getDescription().isBlank()) ? first.getDescription().trim() : "Bank";
                    }
                }
            } catch (Exception ignored) {}
            if (bankAccount == null || bankAccount.isBlank()) {
                bankAccount = "0000000000";
            }
        }
        if (bankName == null || bankName.isBlank()) {
            bankName = "Bank Account";
        }
        if (accHolder == null || accHolder.isBlank()) {
            accHolder = org.example.service.BrandingService.companyName();
        }

        String fingerprint = parsed.sourceFingerprint();
        if (fingerprint == null || fingerprint.isBlank()) {
            fingerprint = java.util.UUID.randomUUID().toString();
        }

        var request = new BankStatementApiClient.ImportRequest(
            bankName, bankAccount, accHolder, parsed.statementFrom(), parsed.statementTo(),
            parsed.currency(), parsed.openingBalance(), parsed.closingBalance(), fingerprint,
            parsed.sourceFileName(), parsed.sourceCsv(), user, dryRun, parsed.rows());

        if (progress != null) {
            progress.onProgress("Shipping Statement", "Transmitting " + parsed.rows().size() + " transactions to server…", 2, 3);
        }
        var result = api.importStatement(request);

        if (progress != null) {
            progress.onProgress("Complete", "Bank statement processed: " + result.importedRows() + " transactions.", 3, 3);
        }
        boolean allExisting = !result.alreadyImported() && result.importedRows() == 0 && result.duplicateRows() > 0;
        String action = result.alreadyImported() || allExisting ? "ALREADY CURRENT" : (dryRun ? "VALIDATED" : "IMPORTED");
        String message = result.alreadyImported()
            ? "This exact bank statement was imported previously. Open the existing statement from Bank Statement history."
            : allExisting
                ? "All transactions in this statement were already imported. No bank transactions will be overwritten."
                : (dryRun ? "Server validation passed" : "Bank statement imported");
        var details = List.of(new ImportService.ImportRowResult(
            "1-" + parsed.rows().size(), parsed.sourceFileName(), "PASSED", action, message, "", 0));
        return new ImportService.ImportResult(
            parsed.rows().size(), dryRun ? 0 : result.importedRows(), 0, result.duplicateRows(), List.of(), details);
    }
}
