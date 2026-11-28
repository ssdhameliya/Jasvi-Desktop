package org.example.server.accounting;

import org.example.server.persistence.JpaNativeRepository;
import org.example.server.persistence.entity.ChartOfAccountsEntity;
import org.example.server.persistence.entity.JournalEntryEntity;
import org.example.server.persistence.entity.JournalLineEntity;
import org.example.server.persistence.repository.ChartOfAccountsRepository;
import org.example.server.persistence.repository.JournalEntryRepository;
import org.example.server.persistence.repository.JournalLineRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PerBankGlAndGstPostingTest {

    private static ChartOfAccountsEntity coa(long id, String code, String name, String type) {
        ChartOfAccountsEntity e = new ChartOfAccountsEntity();
        e.setId(id);
        e.setAccountCode(code);
        e.setAccountName(name);
        e.setAccountType(type);
        e.setIsActive(true);
        e.setCurrentBalance(BigDecimal.ZERO);
        return e;
    }

    @Test
    void provisionsIsolatedBankGlSubAccountAndPostsGstItcBalancedEntries() {
        Map<String, ChartOfAccountsEntity> byCode = new LinkedHashMap<>();
        Map<Long, ChartOfAccountsEntity> byId = new LinkedHashMap<>();
        long[] seq = {100L};
        for (ChartOfAccountsEntity c : List.of(
                coa(1L, "1020", "Bank Current Account", "ASSET"),
                coa(2L, "1081", "Input CGST Pool", "ASSET"),
                coa(3L, "1082", "Input SGST Pool", "ASSET"),
                coa(4L, "1083", "Input IGST Pool", "ASSET")
        )) {
            byCode.put(c.getAccountCode(), c);
            byId.put(c.getId(), c);
        }

        ChartOfAccountsRepository coaRepo = mock(ChartOfAccountsRepository.class);
        when(coaRepo.findByAccountCode(anyString())).thenAnswer(inv -> {
            String code = inv.getArgument(0);
            ChartOfAccountsEntity found = byCode.get(code);
            if (found != null) return Optional.of(found);
            if (code != null && code.startsWith("6")) {
                ChartOfAccountsEntity exp = coa(++seq[0], code, "Expense " + code, "EXPENSE");
                byCode.put(code, exp);
                byId.put(exp.getId(), exp);
                return Optional.of(exp);
            }
            return Optional.empty();
        });
        when(coaRepo.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(byId.get(inv.getArgument(0))));
        when(coaRepo.save(any())).thenAnswer(inv -> {
            ChartOfAccountsEntity c = inv.getArgument(0);
            if (c.getId() == null) c.setId(++seq[0]);
            byCode.put(c.getAccountCode(), c);
            byId.put(c.getId(), c);
            return c;
        });

        List<JournalEntryEntity> savedEntries = new ArrayList<>();
        JournalEntryRepository jeRepo = mock(JournalEntryRepository.class);
        when(jeRepo.save(any())).thenAnswer(inv -> {
            JournalEntryEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId(500L + savedEntries.size());
            savedEntries.add(e);
            return e;
        });

        JournalLineRepository jlRepo = mock(JournalLineRepository.class);
        when(jlRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, String> bankMap = new LinkedHashMap<>();
        JpaNativeRepository jdbc = new JpaNativeRepository() {
            @Override
            public List<Map<String, Object>> queryForList(String sql, Object... args) {
                if (sql.contains("FROM bank_gl_account_map") && args != null && args.length > 0) {
                    String k = String.valueOf(args[0]);
                    String glCode = bankMap.get(k);
                    if (glCode == null) return List.of();
                    ChartOfAccountsEntity c = byCode.get(glCode);
                    return List.of(Map.of(
                            "bank_account_key", k,
                            "gl_account_code", glCode,
                            "id", c != null ? c.getId() : 0L,
                            "account_name", c != null ? c.getAccountName() : "",
                            "bal", c != null && c.getCurrentBalance() != null ? c.getCurrentBalance() : BigDecimal.ZERO,
                            "gl_account_id", c != null ? c.getId() : 0L
                    ));
                }
                if (sql.contains("SELECT account_code FROM chart_of_accounts")) {
                    List<Map<String, Object>> out = new ArrayList<>();
                    byCode.keySet().forEach(code -> out.add(Map.of("account_code", code)));
                    return out;
                }
                return List.of();
            }

            @Override
            public <T> List<T> query(String sql, java.util.function.BiFunction<NativeRow, Integer, T> mapper, Object... args) {
                return List.of();
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T queryForObject(String sql, Class<T> type, Object... args) {
                if (sql.contains("SELECT id FROM chart_of_accounts WHERE account_code = ?") && args != null && args.length > 0) {
                    ChartOfAccountsEntity existing = byCode.get(String.valueOf(args[0]));
                    if (existing == null && String.valueOf(args[0]).startsWith("6")) {
                        existing = coa(++seq[0], String.valueOf(args[0]), "Expense " + args[0], "EXPENSE");
                        byCode.put(existing.getAccountCode(), existing);
                        byId.put(existing.getId(), existing);
                    }
                    return existing != null ? (T) existing.getId() : null;
                }
                if (sql.contains("INSERT INTO chart_of_accounts") && type == Long.class) {
                    String code = String.valueOf(args[0]);
                    String name = String.valueOf(args[1]);
                    String accType = args.length > 2 ? String.valueOf(args[2]) : "ASSET";
                    ChartOfAccountsEntity existing = byCode.get(code);
                    if (existing != null) return (T) existing.getId();
                    ChartOfAccountsEntity created = coa(++seq[0], code, name, accType);
                    byCode.put(code, created);
                    byId.put(created.getId(), created);
                    return (T) created.getId();
                }
                if (sql.contains("COUNT(") && type == Long.class) return (T) Long.valueOf(0L);
                if (sql.contains("COUNT(") && type == Integer.class) return (T) Integer.valueOf(0);
                return null;
            }

            @Override
            public int update(String sql, Object... args) {
                if (sql.contains("INSERT INTO bank_gl_account_map") && args != null && args.length >= 4) {
                    bankMap.put(String.valueOf(args[0]), String.valueOf(args[3]));
                }
                return 1;
            }
        };

        GeneralLedgerService gl = new GeneralLedgerService(coaRepo, jeRepo, jlRepo, jdbc);

        // 1. Generic BANK falls back to default 1020; named bank provisions isolated 1021
        assertEquals("1020", gl.resolveBankGlAccount("BANK").accountCode());
        GeneralLedgerService.BankGlAccount hdfc = gl.resolveBankGlAccount("HDFC Bank • 50200012345678");
        assertEquals("1021", hdfc.accountCode());
        assertTrue(hdfc.accountName().contains("HDFC"));

        // 2. Intra-state GST bank charge (1000 net + 90 CGST + 90 SGST = 1180 total)
        gl.postFinanceVoucherWithGst(
                77, "EXP-001", LocalDate.of(2026, 11, 27), "EXPENSE", "Bank Charges",
                "HDFC Bank • 50200012345678", "NEFT", new BigDecimal("1180.00"),
                null, "IMPS fee", "admin",
                new BigDecimal("18.00"), new BigDecimal("180.00"),
                new BigDecimal("90.00"), new BigDecimal("90.00"), BigDecimal.ZERO
        );

        JournalEntryEntity intraEntry = savedEntries.getLast();
        assertEquals(0, new BigDecimal("1180.00").compareTo(intraEntry.getTotalDebit()));
        assertEquals(0, new BigDecimal("1180.00").compareTo(intraEntry.getTotalCredit()));
        List<Long> intraAccIds = intraEntry.getLines().stream().map(JournalLineEntity::getAccountId).toList();
        assertTrue(intraAccIds.contains(2L), "Intra-state GST expense must debit 1081 Input CGST");
        assertTrue(intraAccIds.contains(3L), "Intra-state GST expense must debit 1082 Input SGST");
        assertTrue(intraAccIds.contains(hdfc.accountId()),
                "Must credit resolved " + hdfc + " among lines " + intraAccIds + " (bankMap=" + bankMap + ")");

        // 3. Inter-state IGST bank charge (1000 net + 180 IGST = 1180 total)
        gl.postFinanceVoucherWithGst(
                78, "EXP-002", LocalDate.of(2026, 11, 27), "EXPENSE", "Bank Charges",
                "HDFC Bank • 50200012345678", "NEFT", new BigDecimal("1180.00"),
                null, "Wire fee", "admin",
                new BigDecimal("18.00"), new BigDecimal("180.00"),
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("180.00")
        );

        JournalEntryEntity igstEntry = savedEntries.getLast();
        List<Long> igstAccIds = igstEntry.getLines().stream().map(JournalLineEntity::getAccountId).toList();
        assertTrue(igstAccIds.contains(4L), "Inter-state GST expense must debit 1083 Input IGST");
        assertFalse(igstAccIds.contains(2L));
    }
}
