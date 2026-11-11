package org.example;

import org.example.bank.UniversalBankStatementParser;
import java.nio.file.*;

/** Simple parser smoke for the universal reconciliation import path. */
public class BankStatementCsvParserSmoke {
    public static void main(String[] args) throws Exception {
        Path file = Files.createTempFile("dse-bank-statement-", ".csv");
        Files.writeString(file, """
            "",,Account Statement
            TEST COMPANY
            "",,,,Account No.,0123456789
            "",,,,Period,From 01/08/2026 To 31/08/2026
            "",,,,Currency,INR
            Sl. No.,Transaction Date,Value Date,Description,Chq / Ref No.,Amount,Dr / Cr,Balance,Dr / Cr
            1,01-08-2026 10:00:00,01-08-2026,Customer Receipt,UTR001,"10,000.00",CR,"25,000.00",CR
            2,02-08-2026 11:00:00,02-08-2026,Bank Charges,CHG001,100.00,DR,"24,900.00",CR
            """);
        var parsed = new UniversalBankStatementParser().parse(file);
        if (parsed.rows().size() != 2) throw new AssertionError("Expected 2 rows");
        if (parsed.rows().getFirst().credit() != 10000d) throw new AssertionError("Credit normalization failed");
        if (parsed.rows().getLast().debit() != 100d) throw new AssertionError("Debit normalization failed");
        if (!"0123456789".equals(parsed.accountNumber())) throw new AssertionError("Account parsing failed");
        if (!UniversalBankStatementParser.accountsMatch("0123456789", "0123456789")) throw new AssertionError("Exact match failed");
        if (!UniversalBankStatementParser.accountsMatch("XXXX6789", "0123456789")) throw new AssertionError("Masked suffix match failed");
        if (UniversalBankStatementParser.accountsMatch("9999999999", "0123456789")) throw new AssertionError("Mismatch failed");
        Files.deleteIfExists(file);
        System.out.println("BankStatementCsvParserSmoke OK");
    }
}
