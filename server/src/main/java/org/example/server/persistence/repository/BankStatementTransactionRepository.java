package org.example.server.persistence.repository;

import jakarta.persistence.LockModeType;
import org.example.server.persistence.entity.BankStatementTransactionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BankStatementTransactionRepository extends JpaRepository<BankStatementTransactionEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select transaction from BankStatementTransactionEntity transaction where transaction.id=:id")
    Optional<BankStatementTransactionEntity> findByIdForUpdate(@Param("id") Long id);
    boolean existsByTransactionFingerprint(String fingerprint);
    List<BankStatementTransactionEntity> findByImportBatchIdOrderByTransactionTimestampAscIdAsc(Long importId);
    long countByImportBatchIdAndStatusIn(Long importId, Collection<String> statuses);

    @Query(value = """
        select * from bank_statement_transaction t
        where t.import_id = :importId
          and (:status = '' or upper(coalesce(t.status,'UNMATCHED')) = :status)
          and (:direction = 'ALL'
               or (:direction = 'CREDIT' and coalesce(t.credit_amount,0) > 0)
               or (:direction = 'DEBIT' and coalesce(t.debit_amount,0) > 0))
          and (:fromDate = '' or coalesce(t.transaction_date,'') >= :fromDate)
          and (:toDate = '' or coalesce(t.transaction_date,'') <= :toDate)
          and (:queryText = ''
               or lower(coalesce(t.transaction_reference,'')) like concat('%', lower(:queryText), '%')
               or lower(coalesce(t.original_description,'')) like concat('%', lower(:queryText), '%')
               or lower(coalesce(t.original_reference,'')) like concat('%', lower(:queryText), '%')
               or lower(coalesce(t.status,'')) like concat('%', lower(:queryText), '%')
               or cast(coalesce(t.debit_amount,0) as text) like concat('%', replace(replace(:queryText, ',', ''), '₹', ''), '%')
               or cast(coalesce(t.credit_amount,0) as text) like concat('%', replace(replace(:queryText, ',', ''), '₹', ''), '%')
               or cast(coalesce(t.balance,0) as text) like concat('%', replace(replace(:queryText, ',', ''), '₹', ''), '%')
               or exists (
                   select 1 from bank_reconciliation_allocation a
                   where a.statement_transaction_id = t.id
                     and a.reversed_at is null
                     and (
                         (a.target_type = 'SALE' and exists (select 1 from sales_header s where s.id = a.target_id and lower(s.invoice_no) like concat('%', lower(:queryText), '%')))
                         or (a.target_type = 'PURCHASE' and exists (select 1 from purchase_header p where p.id = a.target_id and lower(p.invoice_no) like concat('%', lower(:queryText), '%')))
                         or (a.target_type = 'PURCHASE_RECON' and exists (select 1 from purchase_recon pr where pr.id = a.target_id and (lower(pr.recon_ref) like concat('%', lower(:queryText), '%') or lower(pr.supplier_invoice_no) like concat('%', lower(:queryText), '%'))))
                         or exists (select 1 from finance_register f where f.id = a.finance_entry_id and lower(f.voucher_no) like concat('%', lower(:queryText), '%'))
                     )
               ))
        order by t.transaction_timestamp asc nulls last, t.id asc
        """, countQuery = """
        select count(*) from bank_statement_transaction t
        where t.import_id = :importId
          and (:status = '' or upper(coalesce(t.status,'UNMATCHED')) = :status)
          and (:direction = 'ALL'
               or (:direction = 'CREDIT' and coalesce(t.credit_amount,0) > 0)
               or (:direction = 'DEBIT' and coalesce(t.debit_amount,0) > 0))
          and (:fromDate = '' or coalesce(t.transaction_date,'') >= :fromDate)
          and (:toDate = '' or coalesce(t.transaction_date,'') <= :toDate)
          and (:queryText = ''
               or lower(coalesce(t.transaction_reference,'')) like concat('%', lower(:queryText), '%')
               or lower(coalesce(t.original_description,'')) like concat('%', lower(:queryText), '%')
               or lower(coalesce(t.original_reference,'')) like concat('%', lower(:queryText), '%')
               or lower(coalesce(t.status,'')) like concat('%', lower(:queryText), '%')
               or cast(coalesce(t.debit_amount,0) as text) like concat('%', :queryText, '%')
               or cast(coalesce(t.credit_amount,0) as text) like concat('%', :queryText, '%')
               or exists (
                   select 1 from bank_reconciliation_allocation a
                   where a.statement_transaction_id = t.id
                     and a.reversed_at is null
                     and (
                         (a.target_type = 'SALE' and exists (select 1 from sales_header s where s.id = a.target_id and lower(s.invoice_no) like concat('%', lower(:queryText), '%')))
                         or (a.target_type = 'PURCHASE' and exists (select 1 from purchase_header p where p.id = a.target_id and lower(p.invoice_no) like concat('%', lower(:queryText), '%')))
                         or (a.target_type = 'PURCHASE_RECON' and exists (select 1 from purchase_recon pr where pr.id = a.target_id and (lower(pr.recon_ref) like concat('%', lower(:queryText), '%') or lower(pr.supplier_invoice_no) like concat('%', lower(:queryText), '%'))))
                         or exists (select 1 from finance_register f where f.id = a.finance_entry_id and lower(f.voucher_no) like concat('%', lower(:queryText), '%'))
                     )
               ))
        """, nativeQuery = true)
    Page<BankStatementTransactionEntity> searchPage(@Param("importId") Long importId,
            @Param("status") String status, @Param("direction") String direction,
            @Param("fromDate") String fromDate, @Param("toDate") String toDate,
            @Param("queryText") String queryText, Pageable pageable);

    @Query(value = """
        select t.* from bank_statement_transaction t
        join bank_statement_import b on b.id = t.import_id
        where (:importId <= 0 or t.import_id = :importId)
          and (:bankAccount = '' or lower(regexp_replace(coalesce(b.bank_account,''), '[^0-9A-Za-z]', '', 'g')) = lower(regexp_replace(:bankAccount, '[^0-9A-Za-z]', '', 'g'))
               or lower(concat(coalesce(b.bank_name,''), ' - ', coalesce(b.bank_account,''))) like concat('%', lower(:bankAccount), '%'))
          and (:status = '' or upper(coalesce(t.status,'UNMATCHED')) = :status)
          and (:direction = 'ALL'
               or (:direction = 'CREDIT' and coalesce(t.credit_amount,0) > 0)
               or (:direction = 'DEBIT' and coalesce(t.debit_amount,0) > 0))
          and (:channel = 'ALL'
               or (:channel = 'UPI' and (upper(coalesce(t.original_description,'')) like '%UPI%' or upper(coalesce(t.original_reference,'')) like '%UPI%' or upper(coalesce(t.original_description,'')) like '%@OK%' or upper(coalesce(t.original_description,'')) like '%@YBL%' or upper(coalesce(t.original_description,'')) like '%@AXL%'))
               or (:channel = 'NEFT' and (upper(coalesce(t.original_description,'')) like '%NEFT%' or upper(coalesce(t.original_reference,'')) like '%NEFT%'))
               or (:channel = 'IMPS' and (upper(coalesce(t.original_description,'')) like '%IMPS%' or upper(coalesce(t.original_reference,'')) like '%IMPS%'))
               or (:channel = 'RTGS' and (upper(coalesce(t.original_description,'')) like '%RTGS%' or upper(coalesce(t.original_reference,'')) like '%RTGS%'))
               or (:channel = 'CHQ' and (upper(coalesce(t.original_description,'')) like '%CHQ%' or upper(coalesce(t.original_description,'')) like '%CHEQUE%' or upper(coalesce(t.original_description,'')) like '%CLG%'))
               or (:channel = 'ACH' and (upper(coalesce(t.original_description,'')) like '%ACH%' or upper(coalesce(t.original_description,'')) like '%NACH%' or upper(coalesce(t.original_description,'')) like '%ECS%'))
               or (:channel = 'POS' and (upper(coalesce(t.original_description,'')) like '%POS%' or upper(coalesce(t.original_description,'')) like '%ATM%' or upper(coalesce(t.original_description,'')) like '%CARD%'))
               or (:channel = 'CHARGES' and (upper(coalesce(t.original_description,'')) like '%CHG%' or upper(coalesce(t.original_description,'')) like '%CHRG%' or upper(coalesce(t.original_description,'')) like '%CHARGE%' or upper(coalesce(t.original_description,'')) like '%FEE%' or upper(coalesce(t.original_description,'')) like '%INTEREST%')))
          and (:fromDate = '' or coalesce(t.transaction_date,'') >= :fromDate)
          and (:toDate = '' or coalesce(t.transaction_date,'') <= :toDate)
          and (:queryText = ''
               or lower(coalesce(t.transaction_reference,'')) like concat('%', lower(:queryText), '%')
               or lower(coalesce(t.original_description,'')) like concat('%', lower(:queryText), '%')
               or lower(coalesce(t.original_reference,'')) like concat('%', lower(:queryText), '%')
               or lower(coalesce(t.status,'')) like concat('%', lower(:queryText), '%')
               or cast(coalesce(t.debit_amount,0) as text) like concat('%', replace(replace(:queryText, ',', ''), '₹', ''), '%')
               or cast(coalesce(t.credit_amount,0) as text) like concat('%', replace(replace(:queryText, ',', ''), '₹', ''), '%')
               or cast(coalesce(t.balance,0) as text) like concat('%', replace(replace(:queryText, ',', ''), '₹', ''), '%')
               or exists (
                   select 1 from bank_reconciliation_allocation a
                   where a.statement_transaction_id = t.id
                     and a.reversed_at is null
                     and (
                         (a.target_type = 'SALE' and exists (select 1 from sales_header s where s.id = a.target_id and lower(s.invoice_no) like concat('%', lower(:queryText), '%')))
                         or (a.target_type = 'PURCHASE' and exists (select 1 from purchase_header p where p.id = a.target_id and lower(p.invoice_no) like concat('%', lower(:queryText), '%')))
                         or (a.target_type = 'PURCHASE_RECON' and exists (select 1 from purchase_recon pr where pr.id = a.target_id and (lower(pr.recon_ref) like concat('%', lower(:queryText), '%') or lower(pr.supplier_invoice_no) like concat('%', lower(:queryText), '%'))))
                         or exists (select 1 from finance_register f where f.id = a.finance_entry_id and lower(f.voucher_no) like concat('%', lower(:queryText), '%'))
                     )
               ))
        order by t.transaction_timestamp asc nulls last, t.id asc
        """, countQuery = """
        select count(*) from bank_statement_transaction t
        join bank_statement_import b on b.id = t.import_id
        where (:importId <= 0 or t.import_id = :importId)
          and (:bankAccount = '' or lower(regexp_replace(coalesce(b.bank_account,''), '[^0-9A-Za-z]', '', 'g')) = lower(regexp_replace(:bankAccount, '[^0-9A-Za-z]', '', 'g'))
               or lower(concat(coalesce(b.bank_name,''), ' - ', coalesce(b.bank_account,''))) like concat('%', lower(:bankAccount), '%'))
          and (:status = '' or upper(coalesce(t.status,'UNMATCHED')) = :status)
          and (:direction = 'ALL'
               or (:direction = 'CREDIT' and coalesce(t.credit_amount,0) > 0)
               or (:direction = 'DEBIT' and coalesce(t.debit_amount,0) > 0))
          and (:channel = 'ALL'
               or (:channel = 'UPI' and (upper(coalesce(t.original_description,'')) like '%UPI%' or upper(coalesce(t.original_reference,'')) like '%UPI%' or upper(coalesce(t.original_description,'')) like '%@OK%' or upper(coalesce(t.original_description,'')) like '%@YBL%' or upper(coalesce(t.original_description,'')) like '%@AXL%'))
               or (:channel = 'NEFT' and (upper(coalesce(t.original_description,'')) like '%NEFT%' or upper(coalesce(t.original_reference,'')) like '%NEFT%'))
               or (:channel = 'IMPS' and (upper(coalesce(t.original_description,'')) like '%IMPS%' or upper(coalesce(t.original_reference,'')) like '%IMPS%'))
               or (:channel = 'RTGS' and (upper(coalesce(t.original_description,'')) like '%RTGS%' or upper(coalesce(t.original_reference,'')) like '%RTGS%'))
               or (:channel = 'CHQ' and (upper(coalesce(t.original_description,'')) like '%CHQ%' or upper(coalesce(t.original_description,'')) like '%CHEQUE%' or upper(coalesce(t.original_description,'')) like '%CLG%'))
               or (:channel = 'ACH' and (upper(coalesce(t.original_description,'')) like '%ACH%' or upper(coalesce(t.original_description,'')) like '%NACH%' or upper(coalesce(t.original_description,'')) like '%ECS%'))
               or (:channel = 'POS' and (upper(coalesce(t.original_description,'')) like '%POS%' or upper(coalesce(t.original_description,'')) like '%ATM%' or upper(coalesce(t.original_description,'')) like '%CARD%'))
               or (:channel = 'CHARGES' and (upper(coalesce(t.original_description,'')) like '%CHG%' or upper(coalesce(t.original_description,'')) like '%CHRG%' or upper(coalesce(t.original_description,'')) like '%CHARGE%' or upper(coalesce(t.original_description,'')) like '%FEE%' or upper(coalesce(t.original_description,'')) like '%INTEREST%')))
          and (:fromDate = '' or coalesce(t.transaction_date,'') >= :fromDate)
          and (:toDate = '' or coalesce(t.transaction_date,'') <= :toDate)
          and (:queryText = ''
               or lower(coalesce(t.transaction_reference,'')) like concat('%', lower(:queryText), '%')
               or lower(coalesce(t.original_description,'')) like concat('%', lower(:queryText), '%')
               or lower(coalesce(t.original_reference,'')) like concat('%', lower(:queryText), '%')
               or lower(coalesce(t.status,'')) like concat('%', lower(:queryText), '%')
               or cast(coalesce(t.debit_amount,0) as text) like concat('%', replace(replace(:queryText, ',', ''), '₹', ''), '%')
               or cast(coalesce(t.credit_amount,0) as text) like concat('%', replace(replace(:queryText, ',', ''), '₹', ''), '%')
               or cast(coalesce(t.balance,0) as text) like concat('%', replace(replace(:queryText, ',', ''), '₹', ''), '%')
               or exists (
                   select 1 from bank_reconciliation_allocation a
                   where a.statement_transaction_id = t.id
                     and a.reversed_at is null
                     and (
                         (a.target_type = 'SALE' and exists (select 1 from sales_header s where s.id = a.target_id and lower(s.invoice_no) like concat('%', lower(:queryText), '%')))
                         or (a.target_type = 'PURCHASE' and exists (select 1 from purchase_header p where p.id = a.target_id and lower(p.invoice_no) like concat('%', lower(:queryText), '%')))
                         or (a.target_type = 'PURCHASE_RECON' and exists (select 1 from purchase_recon pr where pr.id = a.target_id and (lower(pr.recon_ref) like concat('%', lower(:queryText), '%') or lower(pr.supplier_invoice_no) like concat('%', lower(:queryText), '%'))))
                         or exists (select 1 from finance_register f where f.id = a.finance_entry_id and lower(f.voucher_no) like concat('%', lower(:queryText), '%'))
                     )
               ))
        """, nativeQuery = true)
    Page<BankStatementTransactionEntity> searchFiltered(@Param("importId") long importId,
            @Param("bankAccount") String bankAccount, @Param("status") String status,
            @Param("direction") String direction, @Param("channel") String channel,
            @Param("fromDate") String fromDate, @Param("toDate") String toDate,
            @Param("queryText") String queryText, Pageable pageable);

    @Query(value = """
        select count(*) as total,
          coalesce(sum(case when upper(coalesce(t.status,'UNMATCHED'))='UNMATCHED' then 1 else 0 end),0) as unmatched,
          coalesce(sum(case when upper(coalesce(t.status,''))='SUGGESTED' then 1 else 0 end),0) as suggested,
          coalesce(sum(case when upper(coalesce(t.status,''))='MATCHED' then 1 else 0 end),0) as matched,
          coalesce(sum(case when upper(coalesce(t.status,''))='EXPENSE' then 1 else 0 end),0) as expenses,
          coalesce(sum(case when upper(coalesce(t.status,''))='IGNORED' then 1 else 0 end),0) as ignored,
          coalesce(sum(case when upper(coalesce(t.status,''))='REVIEW' then 1 else 0 end),0) as review,
          coalesce(sum(t.credit_amount),0) as "totalCredits",
          coalesce(sum(t.debit_amount),0) as "totalDebits",
          coalesce(sum(case when upper(coalesce(t.status,'')) in ('MATCHED','EXPENSE','IGNORED') then 1 else 0 end),0) as reconciled
        from bank_statement_transaction t
        join bank_statement_import b on b.id = t.import_id
        where (:importId <= 0 or t.import_id = :importId)
          and (:bankAccount = '' or lower(regexp_replace(coalesce(b.bank_account,''), '[^0-9A-Za-z]', '', 'g')) = lower(regexp_replace(:bankAccount, '[^0-9A-Za-z]', '', 'g'))
               or lower(concat(coalesce(b.bank_name,''), ' - ', coalesce(b.bank_account,''))) like concat('%', lower(:bankAccount), '%'))
          and (:fromDate = '' or coalesce(t.transaction_date,'') >= :fromDate)
          and (:toDate = '' or coalesce(t.transaction_date,'') <= :toDate)
        """, nativeQuery = true)
    MetricsProjection aggregateFilteredMetrics(@Param("importId") long importId,
            @Param("bankAccount") String bankAccount,
            @Param("fromDate") String fromDate,
            @Param("toDate") String toDate);

    @Query(value = """
        select count(*) as total,
          coalesce(sum(case when upper(coalesce(status,'UNMATCHED'))='UNMATCHED' then 1 else 0 end),0) as unmatched,
          coalesce(sum(case when upper(coalesce(status,''))='SUGGESTED' then 1 else 0 end),0) as suggested,
          coalesce(sum(case when upper(coalesce(status,''))='MATCHED' then 1 else 0 end),0) as matched,
          coalesce(sum(case when upper(coalesce(status,''))='EXPENSE' then 1 else 0 end),0) as expenses,
          coalesce(sum(case when upper(coalesce(status,''))='IGNORED' then 1 else 0 end),0) as ignored,
          coalesce(sum(case when upper(coalesce(status,''))='REVIEW' then 1 else 0 end),0) as review,
          coalesce(sum(credit_amount),0) as "totalCredits",
          coalesce(sum(debit_amount),0) as "totalDebits",
          coalesce(sum(case when upper(coalesce(status,'')) in ('MATCHED','EXPENSE','IGNORED') then 1 else 0 end),0) as reconciled
        from bank_statement_transaction where import_id=:importId
        """, nativeQuery = true)
    MetricsProjection aggregateMetrics(@Param("importId") Long importId);

    interface MetricsProjection {
        long getTotal(); long getUnmatched(); long getSuggested(); long getMatched();
        long getExpenses(); long getIgnored(); long getReview();
        double getTotalCredits(); double getTotalDebits(); long getReconciled();
    }
}
