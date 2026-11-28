package org.example.server.reconciliation;

import org.example.server.accounting.GeneralLedgerDtos;
import org.example.server.accounting.GeneralLedgerService;
import org.example.server.util.BusinessClock;

import org.example.server.persistence.entity.*;
import org.example.server.persistence.JpaNativeRepository;
import org.example.shared.DocumentCalculationEngine;
import org.example.server.returns.ReturnService;
import org.example.server.security.CurrentUser;
import org.example.server.web.ConcurrentEditException;
import org.example.server.persistence.repository.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.example.server.insights.InsightsService;
import org.example.server.audit.AuditService;
import org.example.server.operations.BusinessOperationsService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;
import org.example.shared.BankNarrationParser;

@Service
public class BankReconciliationService {
 private static final Set<String> RESOLVED=Set.of("MATCHED","EXPENSE","IGNORED");
 private final BankStatementImportRepository imports; private final BankStatementTransactionRepository txs; private final BankReconciliationAllocationRepository allocations; private final BankReconciliationAuditRepository audits; private final PaymentRecordRepository payments; private final SalesHeaderRepository sales; private final PurchaseHeaderRepository purchases; private final PurchaseReconRepository purchaseRecons; private final FinanceRegisterRepository finance; private final JpaNativeRepository jdbc; private final ReturnService returnService; private final AuditService audit; private final BusinessOperationsService operations; private final BankReconciliationRuleService ruleService; private final ObjectProvider<GeneralLedgerService> glProvider;
 public BankReconciliationService(BankStatementImportRepository imports,BankStatementTransactionRepository txs,BankReconciliationAllocationRepository allocations,BankReconciliationAuditRepository audits,PaymentRecordRepository payments,SalesHeaderRepository sales,PurchaseHeaderRepository purchases,PurchaseReconRepository purchaseRecons,FinanceRegisterRepository finance,JpaNativeRepository jdbc,ReturnService returnService,AuditService audit,BusinessOperationsService operations,BankReconciliationRuleService ruleService){this(imports,txs,allocations,audits,payments,sales,purchases,purchaseRecons,finance,jdbc,returnService,audit,operations,ruleService,null);}
 @Autowired
 public BankReconciliationService(BankStatementImportRepository imports,BankStatementTransactionRepository txs,BankReconciliationAllocationRepository allocations,BankReconciliationAuditRepository audits,PaymentRecordRepository payments,SalesHeaderRepository sales,PurchaseHeaderRepository purchases,PurchaseReconRepository purchaseRecons,FinanceRegisterRepository finance,JpaNativeRepository jdbc,ReturnService returnService,AuditService audit,BusinessOperationsService operations,BankReconciliationRuleService ruleService,ObjectProvider<GeneralLedgerService> glProvider){this.imports=imports;this.txs=txs;this.allocations=allocations;this.audits=audits;this.payments=payments;this.sales=sales;this.purchases=purchases;this.purchaseRecons=purchaseRecons;this.finance=finance;this.jdbc=jdbc;this.returnService=returnService;this.audit=audit;this.operations=operations;this.ruleService=ruleService;this.glProvider=glProvider;}

 @Transactional
 public BankReconciliationDtos.ImportResult importStatement(BankReconciliationDtos.ImportRequest r){
  if(r==null||blank(r.bankAccount())||blank(r.sourceFingerprint()))throw new IllegalArgumentException("Bank account and source fingerprint are required.");
  var prior=imports.findBySourceFingerprint(r.sourceFingerprint());
  if(prior.isPresent()){BankStatementImportEntity existing=prior.get();return new BankReconciliationDtos.ImportResult(batch(existing),0,nv(existing.getTransactionCount()),true);}
  if(!blank(r.bankAccount())&&!blank(r.statementFrom())&&!blank(r.statementTo())){
   var periodDupes=imports.findByBankAccountAndPeriod(r.bankAccount().trim(),r.statementFrom().trim(),r.statementTo().trim());
   if(!periodDupes.isEmpty()){BankStatementImportEntity existing=periodDupes.getFirst();return new BankReconciliationDtos.ImportResult(batch(existing),0,nv(existing.getTransactionCount()),true);}
  }
  if(r.dryRun()){
   int ready=0,dupes=0;Set<String> seen=new HashSet<>();
   if(r.rows()!=null)for(var row:r.rows()){validateStatementRow(row);String fp=safe(row.transactionFingerprint()).trim();if(fp.isBlank()||!seen.add(fp)||txs.existsByTransactionFingerprint(fp)){dupes++;continue;}ready++;}
   return new BankReconciliationDtos.ImportResult(null,ready,dupes,false);
  }
  String actor=CurrentUser.require().username();
  String statementRef = operations.nextConfiguredReference("REF_BANK_STATEMENT", "BST/DD-MM-YYYY/XXXX", () -> imports.findAll().stream().map(BankStatementImportEntity::getStatementReference).filter(Objects::nonNull).toList());
   BankStatementImportEntity b=new BankStatementImportEntity(); b.setStatementReference(statementRef); b.setBankName(blank(r.bankName())?"Bank":r.bankName().trim());b.setBankAccount(r.bankAccount().trim());b.setAccountHolder(r.accountHolder());b.setStatementFrom(r.statementFrom());b.setStatementTo(r.statementTo());b.setCurrency(blank(r.currency())?"INR":r.currency());b.setOpeningBalance(r.openingBalance());b.setClosingBalance(r.closingBalance());b.setSourceFingerprint(r.sourceFingerprint());b.setSourceFileName(r.sourceFileName());b.setSourceCsv(r.sourceCsv());b.setImportedBy(actor);b.setTransactionCount(0);b.setTotalDebit(0d);b.setTotalCredit(0d);b.setReconciledCount(0);b.setReconciliationPercent(0d);
   if (glProvider != null && glProvider.getIfAvailable() != null) {
    b.setGlAccountCode(glProvider.getIfAvailable().resolveBankGlAccount(b.getBankName() + " - " + b.getBankAccount()).accountCode());
   }
   b=imports.save(b);
  int added=0,dupes=0; int lineSeq=1; double debit=0,credit=0;
  if(r.rows()!=null)for(var row:r.rows()){
   validateStatementRow(row);
   if(blank(row.transactionFingerprint())||txs.existsByTransactionFingerprint(row.transactionFingerprint())){dupes++;continue;}
   double rowDebit=money(row.debit()),rowCredit=money(row.credit()),rowBalance=money(row.balance());
   BankStatementTransactionEntity t=new BankStatementTransactionEntity();t.setImportBatch(b);t.setSourceRowNumber(row.sourceRowNumber());t.setTransactionReference(statementRef+"-"+String.format(Locale.ROOT,"%03d",lineSeq++));t.setTransactionTimestamp(row.transactionTimestamp());t.setTransactionDate(row.transactionDate());t.setValueDate(blank(row.valueDate())?row.transactionDate():row.valueDate());t.setOriginalDescription(row.description());t.setOriginalReference(row.reference());t.setDebitAmount(rowDebit);t.setCreditAmount(rowCredit);t.setBalance(rowBalance);t.setTransactionFingerprint(row.transactionFingerprint());t=txs.save(t);applySuggestion(t);added++;debit=money(debit+rowDebit);credit=money(credit+rowCredit);audit(t,"IMPORTED","Imported from "+r.sourceFileName()+" ["+t.getTransactionReference()+"]",null,t.getStatus(),actor);
  }
  if(added==0){imports.delete(b);return new BankReconciliationDtos.ImportResult(null,0,dupes,false);}
  b.setTransactionCount(added);b.setTotalDebit(debit);b.setTotalCredit(credit);imports.save(b);if(ruleService!=null){try{ruleService.applyRulesToBatch(b.getId(),true,actor);}catch(Exception ignored){}}refreshBatch(b.getId());return new BankReconciliationDtos.ImportResult(batch(b),added,dupes,false);
 }

 @Transactional(readOnly=true)
 public BankReconciliationDtos.ContinuityCheckResult checkContinuity(BankReconciliationDtos.ContinuityCheckRequest r){
  if(r==null||blank(r.bankAccount())){
   return new BankReconciliationDtos.ContinuityCheckResult(false,null,"","","",null,0,false,false,0d,"FIRST_BATCH",List.of());
  }
  LocalDate newFrom=date(r.statementFrom()),newTo=date(r.statementTo());
  List<BankStatementImportEntity> accountBatches=imports.findAll().stream()
   .filter(b->accountsMatch(b.getBankAccount(),r.bankAccount()))
   .sorted(Comparator.comparing((BankStatementImportEntity b)->Optional.ofNullable(date(b.getStatementTo())).orElse(LocalDate.MIN))
    .thenComparing(BankStatementImportEntity::getId))
   .toList();
  if(accountBatches.isEmpty()){
   return new BankReconciliationDtos.ContinuityCheckResult(false,null,"","","",null,0,false,false,0d,"FIRST_BATCH",List.of());
  }
  List<String> warnings=new ArrayList<>();
  boolean overlap=false;
  if(newFrom!=null&&newTo!=null){
   for(var existing:accountBatches){
    LocalDate exFrom=date(existing.getStatementFrom()),exTo=date(existing.getStatementTo());
    if(exFrom!=null&&exTo!=null&&!exFrom.isAfter(newTo)&&!newFrom.isAfter(exTo)){
     overlap=true;
     warnings.add("Period overlaps existing batch ["+safe(existing.getStatementReference())+"] ("+safe(existing.getStatementFrom())+" to "+safe(existing.getStatementTo())+").");
     break;
    }
   }
  }
  BankStatementImportEntity prior=null;
  if(newFrom!=null){
   for(int i=accountBatches.size()-1;i>=0;i--){
    var cand=accountBatches.get(i);
    LocalDate candEnd=date(cand.getStatementTo());
    if(candEnd!=null&&!candEnd.isAfter(newFrom)){prior=cand;break;}
   }
  }
  if(prior==null)prior=accountBatches.getLast();
  LocalDate priorEnd=date(prior.getStatementTo());
  int gapDays=0;
  if(priorEnd!=null&&newFrom!=null){
   long missing=ChronoUnit.DAYS.between(priorEnd,newFrom)-1;
   if(missing>0){
    gapDays=(int)Math.min(Integer.MAX_VALUE,missing);
    LocalDate missStart=priorEnd.plusDays(1),missEnd=newFrom.minusDays(1);
    warnings.add("Date gap of "+gapDays+" missing day(s) between batch ["+safe(prior.getStatementReference())+"] ending "+priorEnd+" and new start "+newFrom+" (missing "+missStart+(missEnd.equals(missStart)?"":" to "+missEnd)+").");
   }
  }
  boolean balanceMismatch=false;
  double balanceDiff=0d;
  if(prior.getClosingBalance()!=null&&r.openingBalance()!=null){
   balanceDiff=money(r.openingBalance()-prior.getClosingBalance());
   if(Math.abs(balanceDiff)>0.01){
    balanceMismatch=true;
    warnings.add("Opening/Closing balance mismatch vs ["+safe(prior.getStatementReference())+"]: prior closed at ₹"+fmt(prior.getClosingBalance())+", new file opens at ₹"+fmt(r.openingBalance())+" (Diff: "+signedMoney(balanceDiff)+").");
   }
  }
  String severity=balanceMismatch?"ERROR":((overlap||gapDays>0)?"WARNING":"OK");
  return new BankReconciliationDtos.ContinuityCheckResult(true,prior.getId(),safe(prior.getStatementReference()),safe(prior.getStatementFrom()),safe(prior.getStatementTo()),prior.getClosingBalance(),gapDays,overlap,balanceMismatch,balanceDiff,severity,List.copyOf(warnings));
 }

 private static boolean accountsMatch(String a,String b){
  String c1=compact(a),c2=compact(b);
  if(c1.isEmpty()||c2.isEmpty())return false;
  if(c1.equalsIgnoreCase(c2))return true;
  String d1=c1.replaceAll("[^0-9]",""),d2=c2.replaceAll("[^0-9]","");
  if(!d1.isEmpty()&&d1.equals(d2))return true;
  if(d1.length()>=4&&d2.length()>=4){
   return d1.substring(d1.length()-4).equals(d2.substring(d2.length()-4));
  }
  return false;
 }

 public List<BankReconciliationDtos.BatchDto> batches(){return batchPage(0,20,"","","","","").rows();}
 public BankReconciliationDtos.BatchDto batch(Long importId){return batch(imports.findById(importId).orElseThrow(()->new IllegalArgumentException("Statement batch not found.")));}
 public BankReconciliationDtos.BatchPage batchPage(int page,int size,String account,String status,String fromDate,String toDate,String q){
  int safePage=Math.max(0,page),safeSize=Math.max(10,Math.min(size,100));String state=safe(status).trim();if(state.toUpperCase(Locale.ROOT).startsWith("ALL"))state="";
  var result=imports.search(safe(account).trim(),state,safe(fromDate).trim(),safe(toDate).trim(),safe(q).trim(),PageRequest.of(safePage,safeSize,org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Order.desc("importedAt"),org.springframework.data.domain.Sort.Order.desc("id"))));
  if(result.getTotalPages()>0&&safePage>=result.getTotalPages()){safePage=result.getTotalPages()-1;result=imports.search(safe(account).trim(),state,safe(fromDate).trim(),safe(toDate).trim(),safe(q).trim(),PageRequest.of(safePage,safeSize,org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Order.desc("importedAt"),org.springframework.data.domain.Sort.Order.desc("id"))));}
  return new BankReconciliationDtos.BatchPage(result.getContent().stream().map(this::batch).toList(),safePage,safeSize,result.getTotalElements(),result.getTotalPages());
 }
 public BankReconciliationDtos.SourceDto source(Long importId){var b=imports.findById(importId).orElseThrow(()->new IllegalArgumentException("Statement batch not found."));return new BankReconciliationDtos.SourceDto(b.getSourceFileName(),b.getSourceFingerprint(),b.getSourceCsv());}
 public List<BankReconciliationDtos.TransactionDto> transactions(Long importId){
  return mapTransactions(txs.findByImportBatchIdOrderByTransactionTimestampAscIdAsc(importId));
 }
 public BankReconciliationDtos.TransactionPage transactionPage(Long importId,int page,int size,String status,String direction,String fromDate,String toDate,String queryText){
  return transactionPage(importId,"",page,size,status,direction,"ALL",fromDate,toDate,queryText);
 }
 public BankReconciliationDtos.TransactionPage transactionPage(Long importId,String bankAccount,int page,int size,String status,String direction,String channel,String fromDate,String toDate,String queryText){
  int safePage=Math.max(0,page),safeSize=Math.max(10,Math.min(size,200));
  String safeStatus=up(status),safeDirection=up(direction),safeChannel=up(channel);
  if(safeDirection.isBlank())safeDirection="ALL";
  if(safeChannel.isBlank())safeChannel="ALL";
  long resolvedImportId=importId==null||importId<=0?0L:importId;
  String resolvedAccount=resolvedImportId>0?"":safe(bankAccount).trim();
  var result=txs.searchFiltered(resolvedImportId,resolvedAccount,safeStatus,safeDirection,safeChannel,safe(fromDate).trim(),safe(toDate).trim(),safe(queryText).trim(),PageRequest.of(safePage,safeSize));
  var m=resolvedImportId>0?metrics(resolvedImportId):accountMetrics(resolvedAccount,fromDate,toDate);
  return new BankReconciliationDtos.TransactionPage(mapTransactions(result.getContent()),m,result.getNumber(),result.getSize(),result.getTotalElements(),result.getTotalPages());
 }
 public BankReconciliationDtos.Metrics metrics(Long importId){
  var m=txs.aggregateMetrics(importId);
  var batchOpt=imports.findById(importId);
  Double opObj=batchOpt.map(BankStatementImportEntity::getOpeningBalance).orElse(null);
  Double clObj=batchOpt.map(BankStatementImportEntity::getClosingBalance).orElse(null);
  double opening=n(opObj),bankClosing=n(clObj);
  boolean hasBalances=opObj!=null&&clObj!=null;
  return buildMetrics(m,opening,bankClosing,hasBalances);
 }
 public BankReconciliationDtos.Metrics accountMetrics(String bankAccount,String fromDate,String toDate){
  String acct=safe(bankAccount).trim(),from=safe(fromDate).trim(),to=safe(toDate).trim();
  var m=txs.aggregateFilteredMetrics(0L,acct,from,to);
  var allRows=txs.searchFiltered(0L,acct,"","ALL","ALL",from,to,"",PageRequest.of(0,1)).getContent();
  double opening=0d,bankClosing=0d; boolean hasBalances=false;
  if(!allRows.isEmpty()){
   var first=allRows.getFirst();
   opening=money(n(first.getBalance())-n(first.getCreditAmount())+n(first.getDebitAmount()));
   long totalCount=m==null?0:m.getTotal();
   if(totalCount>0){
    var lastPage=txs.searchFiltered(0L,acct,"","ALL","ALL",from,to,"",PageRequest.of((int)Math.max(0,(totalCount-1)/200),200)).getContent();
    if(!lastPage.isEmpty()){bankClosing=n(lastPage.getLast().getBalance());hasBalances=true;}
   }
  }
  return buildMetrics(m,opening,bankClosing,hasBalances);
 }
 private BankReconciliationDtos.Metrics buildMetrics(BankStatementTransactionRepository.MetricsProjection m,double opening,double bankClosing,boolean hasBalances){
  long total=m==null?0:m.getTotal(),resolved=m==null?0:m.getReconciled();
  double pct=total==0?0:resolved*100d/total;
  String status=total==0?"IMPORTED":resolved==total?"FULLY RECONCILED":resolved==0?"IMPORTED":"PARTIALLY RECONCILED";
  double credits=money(m==null?0:m.getTotalCredits()),debits=money(m==null?0:m.getTotalDebits());
  double calculatedClosing=money(opening+credits-debits);
  double variance=hasBalances?money(bankClosing-calculatedClosing):0d;
  boolean verified=hasBalances&&total>0&&Math.abs(variance)<=0.01;
  return new BankReconciliationDtos.Metrics((int)total,(int)(m==null?0:m.getUnmatched()),(int)(m==null?0:m.getSuggested()),(int)(m==null?0:m.getMatched()),(int)(m==null?0:m.getExpenses()),(int)(m==null?0:m.getIgnored()),(int)(m==null?0:m.getReview()),credits,debits,(int)resolved,pct,status,money(opening),money(bankClosing),calculatedClosing,variance,verified);
 }
 @Transactional(readOnly=true)
 public BankReconciliationDtos.BrsSummaryDto getBrsSummary(Long importId,String bankAccount,String fromDate,String toDate){
  boolean batchMode=importId!=null&&importId>0;
  BankStatementImportEntity batchEntity=batchMode?imports.findById(importId).orElseThrow(()->new IllegalArgumentException("Statement batch not found.")):null;
  String acctFilter=batchMode?"":safe(bankAccount).trim();
  String from=batchMode?safe(batchEntity.getStatementFrom()):safe(fromDate).trim();
  String to=batchMode?safe(batchEntity.getStatementTo()):safe(toDate).trim();
  List<BankStatementTransactionEntity> rows=batchMode
   ?txs.findByImportBatchIdOrderByTransactionTimestampAscIdAsc(importId)
   :txs.searchFiltered(0L,acctFilter,"","ALL","ALL",from,to,"",PageRequest.of(0,10000)).getContent();
  BankReconciliationDtos.Metrics m=batchMode?metrics(importId):accountMetrics(acctFilter,from,to);
  double matchedCredits=0d,matchedDebits=0d,expenseDebits=0d,ignoredCredits=0d,ignoredDebits=0d,pendingCredits=0d,pendingDebits=0d;
  List<BankReconciliationDtos.BrsLineItemDto> unclearedCredits=new ArrayList<>();
  List<BankReconciliationDtos.BrsLineItemDto> unclearedDebits=new ArrayList<>();
  for(var t:rows){
   String st=up(t.getStatus());double cr=money(n(t.getCreditAmount())),dr=money(n(t.getDebitAmount()));
   String lineRef=!blank(t.getTransactionReference())?t.getTransactionReference():(t.getImportBatch()!=null?safe(t.getImportBatch().getStatementReference())+"-"+String.format(Locale.ROOT,"%03d",nv(t.getSourceRowNumber())):"");
   if("MATCHED".equals(st)){matchedCredits=money(matchedCredits+cr);matchedDebits=money(matchedDebits+dr);}
   else if("EXPENSE".equals(st)){expenseDebits=money(expenseDebits+dr);}
   else if("IGNORED".equals(st)){ignoredCredits=money(ignoredCredits+cr);ignoredDebits=money(ignoredDebits+dr);}
   else{
    pendingCredits=money(pendingCredits+cr);pendingDebits=money(pendingDebits+dr);
    var item=new BankReconciliationDtos.BrsLineItemDto(t.getId(),lineRef,safe(t.getTransactionDate()),safe(t.getOriginalReference()),safe(t.getOriginalDescription()),st,dr,cr);
    if(cr>0)unclearedCredits.add(item);else if(dr>0)unclearedDebits.add(item);
   }
  }
  double impliedBooksBalance=money(m.bankClosingBalance()-pendingCredits+pendingDebits);
  double netReconDiff=money(m.bankClosingBalance()-(impliedBooksBalance+pendingCredits-pendingDebits));
  String bankName=batchEntity!=null?safe(batchEntity.getBankName()):(rows.isEmpty()||rows.getFirst().getImportBatch()==null?"Consolidated Account":safe(rows.getFirst().getImportBatch().getBankName()));
  String bAcct=batchEntity!=null?safe(batchEntity.getBankAccount()):acctFilter;
  String scopeLabel=batchEntity!=null?(safe(batchEntity.getStatementReference())+" • "+bankName+" - "+bAcct):("Consolidated • "+bankName+(bAcct.isBlank()?"":" - "+bAcct));
  String glAccountCode="1020";
  double glLedgerBalance=impliedBooksBalance;
  double glVsStatementDiff=money(m.bankClosingBalance()-glLedgerBalance);
  GeneralLedgerService gl = glProvider != null ? glProvider.getIfAvailable() : null;
  if(gl!=null){
   String hint=(bankName+" - "+bAcct).trim();
   var bankGl=gl.resolveBankGlAccount(hint);
   glAccountCode=bankGl.accountCode();
   glLedgerBalance=bankGl.currentBalance()!=null?money(bankGl.currentBalance().doubleValue()):0d;
   glVsStatementDiff=money(m.bankClosingBalance()-glLedgerBalance);
  }
  return new BankReconciliationDtos.BrsSummaryDto(scopeLabel,bankName,bAcct,from,to,BusinessClock.today().toString(),m.openingBalance(),m.bankClosingBalance(),m.totalCredits(),m.totalDebits(),m.calculatedClosingBalance(),m.balanceVariance(),matchedCredits,matchedDebits,expenseDebits,ignoredCredits,ignoredDebits,pendingCredits,pendingDebits,impliedBooksBalance,netReconDiff,m.total(),m.reconciled(),m.total()-m.reconciled(),List.copyOf(unclearedCredits),List.copyOf(unclearedDebits),glAccountCode,glLedgerBalance,glVsStatementDiff);
 }

 @Transactional public List<BankReconciliationDtos.CandidateDto> suggest(Long id){var t=reqTxForUpdate(id);applySuggestion(t);txs.save(t);refreshBatch(t.getImportBatch().getId());return candidates(id);}
 public List<BankReconciliationDtos.CandidateDto> candidates(Long id){var t=reqTx(id);var out=candidatesFor(t);out.sort(Comparator.comparingDouble(BankReconciliationDtos.CandidateDto::confidence).reversed().thenComparingDouble(x->Math.abs(x.outstanding()-bankAmount(t))));return out.stream().limit(30).toList();}

 @Transactional public BankReconciliationDtos.OperationResult match(Long id,BankReconciliationDtos.MatchRequest r){
  var t=reqTxForUpdate(id);ensureProcessable(t);if(r==null||r.allocations()==null||r.allocations().isEmpty())throw new IllegalArgumentException("Select at least one eligible Sale, Purchase, Purchase Recon or Return refund transaction.");
  double bank=money(bankAmount(t)),sum=0d;for(var a:r.allocations()){if(a==null||!Double.isFinite(a.amount())||a.amount()<=0)throw new IllegalArgumentException("Allocation amount must be a finite number greater than zero.");sum=money(sum+money(a.amount()));}if(Math.abs(sum-bank)>.001)throw new IllegalArgumentException("Allocated amount must equal the bank transaction amount after monetary rounding ("+fmt(bank)+").");
  double tolerance=roundingTolerance();
  boolean credit=n(t.getCreditAmount())>0;Set<String> allowed=credit?Set.of("SALE","PURCHASE_RETURN","MANUAL_PAYMENT","RECORDED_PAYMENT"):Set.of("PURCHASE","PURCHASE_RECON","SALES_RETURN","CUSTOMER_REFUND","MANUAL_PAYMENT","RECORDED_PAYMENT");for(var a:r.allocations())if(!allowed.contains(up(a.targetType())))throw new IllegalArgumentException("Bank direction can only match "+String.join(" or ",allowed)+" transactions.");
  boolean purchaseReconOnly=r.allocations().stream().allMatch(a->"PURCHASE_RECON".equals(up(a.targetType())));
  boolean skipFinanceRegister=r.allocations().stream().allMatch(a->"RECORDED_PAYMENT".equals(up(a.targetType())));
  LinkedHashSet<String> purchaseReconTraders=new LinkedHashSet<>();
  String voucherNo=skipFinanceRegister?null:operations.nextBankEntryVoucher();
  FinanceRegisterEntity f=null;
  if(!skipFinanceRegister){
   f=new FinanceRegisterEntity();f.setVoucherNo(voucherNo);f.setVoucherType(credit?"BANK DEPOSIT":"BANK WITHDRAWAL");f.setVoucherDate(t.getTransactionDate());f.setCategory(credit?"Deposit":"Withdrawal");f.setReferenceNo(t.getOriginalReference());f.setAmount(bank);f.setPaymentMode("Bank Statement");f.setNotes("Matched from imported bank statement: "+safe(t.getOriginalDescription()));f.setAccountName(t.getImportBatch().getBankName()+" - "+t.getImportBatch().getBankAccount());f.setReconciled(1);f.setCreatedAt(BusinessClock.nowUtcText());f=finance.save(f);
  }
  double totalRounding=0d;
  Set<Integer> matchedPartyIds = new LinkedHashSet<>();
  GeneralLedgerService gl = glProvider != null ? glProvider.getIfAvailable() : null;
  LocalDate reconDate = date(t.getTransactionDate());
  if (gl != null && reconDate != null) gl.ensurePeriodOpen(reconDate);
  List<GeneralLedgerDtos.CreateLineRequest> reconGlLines = new ArrayList<>();
  boolean reconGlHasCash = false;
  for(var a:r.allocations()){
   double amount=money(a.amount());if(!Double.isFinite(amount)||amount<=0)throw new IllegalArgumentException("Allocation amount must be a finite number greater than zero.");String target=up(a.targetType());Integer partyId=null,paymentId=null;String docNo="",partyName="";double roundOff=0d;double allocationTds=0d;
   if("SALE".equals(target)){var sale=sales.findByIdForUpdate(a.targetId()).orElseThrow(()->new IllegalArgumentException("Sales invoice not found."));if(cancelled(sale.getDocumentStatus())||approvalLocked(sale.getDocumentStatus()))throw new IllegalStateException("The selected Sales invoice is not eligible for reconciliation until Admin approval is complete.");double outstanding=Math.max(0,n(sale.getTotalAmount())-n(sale.getPaidAmount()));roundOff=roundingAdjustment(outstanding,amount,tolerance);double tdsAmt=0d;double taxable=n(sale.getSubtotal())>0?n(sale.getSubtotal()):n(sale.getTotalAmount());if(roundOff==0d&&outstanding>amount){double diff=money(outstanding-amount);if(isStatutoryTds(n(sale.getTotalAmount()),taxable,diff))tdsAmt=diff;}validateAllocation(amount,outstanding,roundOff+tdsAmt,sale.getInvoiceNo());double settled=money(amount+roundOff+tdsAmt),paid=n(sale.getPaidAmount())+settled;sale.setPaidAmount(paid);sale.setPaymentStatus(paid+.009>=n(sale.getTotalAmount())?"PAID":"PARTIAL");sales.save(sale);partyId=sale.getCustomer()==null?null:sale.getCustomer().getId();partyName=sale.getCustomer()==null?"":safe(sale.getCustomer().getName());docNo=sale.getInvoiceNo();allocationTds=tdsAmt;PaymentRecordEntity pr=new PaymentRecordEntity();pr.setDocumentType("SALE");pr.setDocumentId(a.targetId());pr.setPaymentDate(t.getTransactionDate());pr.setAmount(amount);pr.setPaymentMode("Bank Statement");pr.setReferenceNo(t.getOriginalReference());pr.setPaymentReference(operations.nextPaymentReference());pr.setNotes(reconciliationNote(t,roundOff)+(tdsAmt>0?" • Statutory TDS "+statutoryTdsLabel(n(sale.getTotalAmount()),taxable,tdsAmt)+" ₹"+fmt(tdsAmt):""));pr.setCreatedBy(org.example.server.security.CurrentUser.require().username());pr.setReceivedFrom("Bank Statement");pr.setPaymentType("BANK_RECONCILIATION");paymentId=payments.save(pr).getId();}
   else if("PURCHASE".equals(target)){var purchase=purchases.findByIdForUpdate(a.targetId()).orElseThrow(()->new IllegalArgumentException("Purchase invoice not found."));if(cancelled(purchase.getDocumentStatus())||approvalLocked(purchase.getDocumentStatus())||purchaseLifecycleLocked(purchase.getDocumentStatus()))throw new IllegalStateException("The selected Purchase invoice is not eligible for reconciliation until it is approved and posted.");double outstanding=Math.max(0,n(purchase.getTotalAmount())-n(purchase.getPaidAmount()));roundOff=roundingAdjustment(outstanding,amount,tolerance);double tdsAmt=0d;double taxable=n(purchase.getSubtotal())>0?n(purchase.getSubtotal()):n(purchase.getTotalAmount());if(roundOff==0d&&outstanding>amount){double diff=money(outstanding-amount);if(isStatutoryTds(n(purchase.getTotalAmount()),taxable,diff))tdsAmt=diff;}validateAllocation(amount,outstanding,roundOff+tdsAmt,purchase.getInvoiceNo());double settled=money(amount+roundOff+tdsAmt),paid=n(purchase.getPaidAmount())+settled;purchase.setPaidAmount(paid);purchase.setPaymentStatus(paid+.009>=n(purchase.getTotalAmount())?"PAID":"PARTIAL");purchases.save(purchase);partyId=purchase.getSupplier()==null?null:purchase.getSupplier().getId();partyName=purchase.getSupplier()==null?"":safe(purchase.getSupplier().getName());docNo=purchase.getInvoiceNo();allocationTds=tdsAmt;PaymentRecordEntity pr=new PaymentRecordEntity();pr.setDocumentType("PURCHASE");pr.setDocumentId(a.targetId());pr.setPaymentDate(t.getTransactionDate());pr.setAmount(amount);pr.setPaymentMode("Bank Statement");pr.setReferenceNo(t.getOriginalReference());pr.setPaymentReference(operations.nextPaymentReference());pr.setNotes(reconciliationNote(t,roundOff)+(tdsAmt>0?" • Statutory TDS "+statutoryTdsLabel(n(purchase.getTotalAmount()),taxable,tdsAmt)+" ₹"+fmt(tdsAmt):""));pr.setCreatedBy(org.example.server.security.CurrentUser.require().username());pr.setReceivedFrom(partyName);pr.setPaymentType("BANK_RECONCILIATION");paymentId=payments.save(pr).getId();}
   else if("PURCHASE_RECON".equals(target)){org.example.server.security.CurrentUser.requirePermission("BANK_EXPENSE.RECONCILE","Bank reconciliation");org.example.server.security.CurrentUser.requirePermission("PURCHASE_RECON.MATCH","Purchase Recon bank matching");var recon=purchaseRecons.findByIdForUpdate(a.targetId()).orElseThrow(()->new IllegalArgumentException("Purchase Recon record not found."));double outstanding=Math.max(0,n(recon.getInvoiceValue())-n(recon.getLinkedAmount()));roundOff=roundingAdjustment(outstanding,amount,tolerance);validateAllocation(amount,outstanding,roundOff,recon.getReconRef());double linked=n(recon.getLinkedAmount())+money(amount+roundOff);recon.setLinkedAmount(linked);recon.setStatus(linked+.009>=n(recon.getInvoiceValue())?"RECONCILED":"PARTIAL");purchaseRecons.save(recon);partyId=recon.getReconSupplier()==null?null:recon.getReconSupplier().getId();partyName=recon.getSupplierNameSnapshot();if(!blank(partyName))purchaseReconTraders.add(partyName.trim());docNo=recon.getReconRef();}
   else if("CUSTOMER_REFUND".equals(target)){partyId=a.targetId();var partyRow=jdbc.queryForList("SELECT name FROM party_master WHERE id=?",partyId);partyName=partyRow.isEmpty()?"Customer #"+partyId:Objects.toString(partyRow.getFirst().get("name"),"");docNo="REFUND-"+partyName;if(f!=null){f.setPartyId(partyId);f.setNotes("Customer direct refund: "+partyName+" • "+safe(t.getOriginalDescription()));}}
   else if("MANUAL_PAYMENT".equals(target)){var fRow=finance.findById(a.targetId()).orElseThrow(()->new IllegalArgumentException("Manual finance entry not found."));fRow.setReconciled(1);finance.save(fRow);docNo=fRow.getVoucherNo();partyId=fRow.getPartyId();partyName=safe(fRow.getNotes());if(f!=null)f.setNotes("Reconciled manual entry #"+docNo+" • "+safe(t.getOriginalDescription()));}
   else if("RECORDED_PAYMENT".equals(target)){var existingPr=payments.findById(a.targetId()).orElseThrow(()->new IllegalArgumentException("Recorded payment voucher not found."));List<Map<String,Object>> activeLinks=jdbc.queryForList("SELECT id FROM bank_reconciliation_allocation WHERE payment_record_id = ? AND reversed_at IS NULL LIMIT 1",existingPr.getId());if(!activeLinks.isEmpty())throw new IllegalStateException("This recorded payment is already linked to an active bank transaction.");paymentId=existingPr.getId();docNo=!blank(existingPr.getPaymentReference())?existingPr.getPaymentReference():("PAY #"+existingPr.getId());partyName=safe(existingPr.getReceivedFrom());if("SALE".equalsIgnoreCase(existingPr.getDocumentType())&&existingPr.getDocumentId()!=null){var sOpt=sales.findById(existingPr.getDocumentId());if(sOpt.isPresent()){var s=sOpt.get();if(s.getCustomer()!=null){partyId=s.getCustomer().getId();partyName=safe(s.getCustomer().getName());}}}else if("PURCHASE".equalsIgnoreCase(existingPr.getDocumentType())&&existingPr.getDocumentId()!=null){var pOpt=purchases.findById(existingPr.getDocumentId());if(pOpt.isPresent()){var p=pOpt.get();if(p.getSupplier()!=null){partyId=p.getSupplier().getId();partyName=safe(p.getSupplier().getName());}}}}
   else {Map<String,Object> rr=returnTarget(a.targetId(),target);docNo=Objects.toString(rr.get("no"),"");partyId=((Number)rr.get("party_id")).intValue();partyName=Objects.toString(rr.get("party"),"");double outstanding=Math.max(0,((Number)rr.get("total")).doubleValue()-((Number)rr.get("refunded")).doubleValue());roundOff=roundingAdjustment(outstanding,amount,tolerance);validateAllocation(amount,outstanding,roundOff,docNo);LocalDate refundDate=date(t.getTransactionDate());if(refundDate==null)throw new IllegalStateException("Bank transaction date is invalid for return refund posting.");paymentId=jdbc.queryForObject("INSERT INTO return_refund(return_no,refund_date,amount,rounding_adjustment,payment_mode,reference_no,bank_account,refunded_party,notes,refund_type,created_by,created_at,bank_statement_transaction_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",Integer.class,docNo,refundDate,amount,roundOff,"Bank Statement",safe(t.getOriginalReference()),t.getImportBatch().getBankName()+" - "+t.getImportBatch().getBankAccount(),partyName,reconciliationNote(t,roundOff),"BANK_RECONCILIATION",org.example.server.security.CurrentUser.require().username(),BusinessClock.nowUtcText(),t.getId());returnService.syncRefundState(docNo);}
   if (gl != null && !"MANUAL_PAYMENT".equals(target) && !"RECORDED_PAYMENT".equals(target)) {
    appendBankReconGlLines(gl, reconGlLines, credit, target, docNo, amount, roundOff, allocationTds, partyId, partyName);
    reconGlHasCash = true;
   }
   if (partyId != null && partyId > 0) matchedPartyIds.add(partyId);
   totalRounding=money(totalRounding+roundOff);if(f!=null&&r.allocations().size()==1){f.setPartyId(partyId);if("PURCHASE_RECON".equals(target)&&!blank(partyName))f.setNotes(partyName.trim());}BankReconciliationAllocationEntity al=new BankReconciliationAllocationEntity();al.setStatementTransactionId(t.getId());al.setTargetType(target);al.setTargetId(a.targetId());al.setAllocatedAmount(amount);al.setRoundingAdjustment(roundOff);al.setPaymentRecordId(paymentId);al.setFinanceEntryId(f!=null?f.getId():null);al.setCreatedBy(org.example.server.security.CurrentUser.require().username());allocations.save(al);audit(t,"MATCHED","Allocated "+fmt(amount)+roundingDetail(roundOff)+" to "+docNo,t.getStatus(),"MATCHED",r.user());
  }
  if(matchedPartyIds.size()==1)learnPartyAliasFromMatch(t, matchedPartyIds.iterator().next());
  if(f!=null){
   if(purchaseReconOnly&&!purchaseReconTraders.isEmpty())f.setNotes(String.join(", ",purchaseReconTraders));finance.save(f);
   if (gl != null && reconGlHasCash && !reconGlLines.isEmpty()) {
    String bankLabel = t.getImportBatch().getBankName() + " - " + t.getImportBatch().getBankAccount();
    var bankGl = gl.resolveBankGlAccount(bankLabel);
    Long bankAcc = bankGl.accountId();
    String bankCode = bankGl.accountCode();
    BigDecimal bAmt = BigDecimal.valueOf(bank).setScale(2, RoundingMode.HALF_UP);
    if (credit) {
     reconGlLines.add(0, new GeneralLedgerDtos.CreateLineRequest(bankAcc, bankCode, bAmt, BigDecimal.ZERO, null, "Bank statement receipt: " + voucherNo, null));
    } else {
     reconGlLines.add(new GeneralLedgerDtos.CreateLineRequest(bankAcc, bankCode, BigDecimal.ZERO, bAmt, null, "Bank statement payout: " + voucherNo, null));
    }
    String actor = org.example.server.security.CurrentUser.isSet() ? org.example.server.security.CurrentUser.require().username() : safe(r.user());
    GeneralLedgerDtos.JournalEntryDto glEntry = gl.postJournalEntry(new GeneralLedgerDtos.CreateJournalRequest(
            reconDate != null ? reconDate : LocalDate.now(), "BANK_RECON", "FINANCE", Long.valueOf(f.getId()), voucherNo,
            "Bank Statement Match " + voucherNo + " • " + safe(t.getOriginalDescription()), actor, reconGlLines));
    if (glEntry != null && glEntry.id() != null) {
     jdbc.update("UPDATE finance_register SET gl_entry_id = ? WHERE id = ?", glEntry.id(), f.getId());
    }
   }
  }
  String old=t.getStatus();t.setStatus("MATCHED");t.setSuggestedConfidence(null);txs.save(t);if(!"MATCHED".equals(old))audit(t,"STATUS_CHANGED","Match confirmed"+roundingDetail(totalRounding),old,"MATCHED",r.user());refreshBatch(t.getImportBatch().getId());InsightsService.invalidateDashboardCache();String vLabel=voucherNo!=null?voucherNo:"Recorded Payment Link";audit.log("BANK_STATEMENT",t.getId().intValue(),"MATCHED","Bank transaction #"+t.getId()+" matched • "+vLabel);if(f!=null)audit.log("FINANCE",f.getId(),"CREATED",f.getVoucherNo()+" • Matched from Bank Statement #"+t.getId());String msg="Bank transaction matched successfully."+(Math.abs(totalRounding)>.0001?" Round-off adjustment: "+signedMoney(totalRounding)+".":"");return new BankReconciliationDtos.OperationResult(true,msg,"MATCHED",f!=null?f.getId():null);
 }
 @Transactional public BankReconciliationDtos.OperationResult expense(Long id,BankReconciliationDtos.ExpenseRequest r){
  if(r==null||blank(r.category()))throw new IllegalArgumentException("Expense category is required.");
  var t=reqTxForUpdate(id);ensureProcessable(t);if(n(t.getDebitAmount())<=0)throw new IllegalArgumentException("Only debit transactions can be moved to Expense Entry.");
  BankReconciliationDtos.OperationResult result=createExpense(t,r);refreshBatch(t.getImportBatch().getId());return result;
 }
 @Transactional public BankReconciliationDtos.OperationResult bankEntry(Long id,BankReconciliationDtos.BankEntryRequest r){
  var t=reqTxForUpdate(id);ensureProcessable(t);if(bankAmount(t)<=0)throw new IllegalArgumentException("Bank transaction amount must be greater than zero.");
  BankReconciliationDtos.OperationResult result=createBankEntry(t,r);refreshBatch(t.getImportBatch().getId());return result;
 }
 @Transactional public BankReconciliationDtos.BulkOperationResult bulkExpense(BankReconciliationDtos.BulkExpenseRequest r){
  if(r==null||r.transactionIds()==null||r.transactionIds().isEmpty())throw new IllegalArgumentException("Select at least one bank transaction.");
  if(blank(r.category()))throw new IllegalArgumentException("Expense category is required for the selected transactions.");
  List<Long> ids=r.transactionIds().stream().filter(Objects::nonNull).distinct().toList();if(ids.isEmpty())throw new IllegalArgumentException("Select at least one bank transaction.");
  List<BankStatementTransactionEntity> selected=new ArrayList<>();Set<Long> batches=new HashSet<>();
  for(Long id:ids){var t=reqTxForUpdate(id);ensureProcessable(t);if(n(t.getDebitAmount())<=0)throw new IllegalArgumentException("Transaction #"+id+" is not a debit transaction and cannot be moved to Expense.");selected.add(t);batches.add(t.getImportBatch().getId());}
  List<Integer> financeIds=new ArrayList<>();var one=new BankReconciliationDtos.ExpenseRequest(r.category(),r.accountName(),r.paymentMode(),r.notes(),r.billPath(),r.user());
  for(var t:selected){var result=createExpense(t,one);if(result.financeEntryId()!=null)financeIds.add(result.financeEntryId());}
  for(Long batchId:batches)refreshBatch(batchId);
  return new BankReconciliationDtos.BulkOperationResult(true,selected.size()+" bank transactions moved to Expense using category "+r.category()+".","EXPENSE",selected.size(),List.copyOf(financeIds));
 }
 @Transactional public BankReconciliationDtos.BulkOperationResult bulkBankEntry(BankReconciliationDtos.BulkBankEntryRequest r){
  if(r==null||r.transactionIds()==null||r.transactionIds().isEmpty())throw new IllegalArgumentException("Select at least one bank transaction.");
  List<Long> ids=r.transactionIds().stream().filter(Objects::nonNull).distinct().toList();if(ids.isEmpty())throw new IllegalArgumentException("Select at least one bank transaction.");
  List<BankStatementTransactionEntity> selected=new ArrayList<>();Set<Long> batches=new HashSet<>();
  for(Long id:ids){var t=reqTxForUpdate(id);ensureProcessable(t);if(bankAmount(t)<=0)throw new IllegalArgumentException("Transaction #"+id+" has no usable amount.");selected.add(t);batches.add(t.getImportBatch().getId());}
  List<Integer> financeIds=new ArrayList<>();var one=new BankReconciliationDtos.BankEntryRequest(r.accountName(),r.paymentMode(),r.notes(),r.user());
  for(var t:selected){var result=createBankEntry(t,one);if(result.financeEntryId()!=null)financeIds.add(result.financeEntryId());}
  for(Long batchId:batches)refreshBatch(batchId);
  return new BankReconciliationDtos.BulkOperationResult(true,selected.size()+" bank transactions moved to Bank Entry.","MATCHED",selected.size(),List.copyOf(financeIds));
 }
 private BankReconciliationDtos.OperationResult createExpense(BankStatementTransactionEntity t,BankReconciliationDtos.ExpenseRequest r){
  String vNo=operations.nextExpenseVoucher();
  String resolvedAccName=blank(r.accountName())?t.getImportBatch().getBankName()+" - "+t.getImportBatch().getBankAccount():r.accountName().trim();
  double totalDr=money(n(t.getDebitAmount()));
  double rate=r.gstRate()!=null&&Double.isFinite(r.gstRate())&&r.gstRate()>0?Math.min(28d,r.gstRate()):0d;
  double gstTotal=0d,cgst=0d,sgst=0d,igst=0d;
  if(rate>0&&totalDr>0){
   double taxable=money(totalDr/(1.0+rate/100.0));
   gstTotal=money(totalDr-taxable);
   if(Boolean.TRUE.equals(r.interState())){igst=gstTotal;}
   else{cgst=money(gstTotal/2.0);sgst=money(gstTotal-cgst);}
  }
  FinanceRegisterEntity f=new FinanceRegisterEntity();f.setVoucherNo(vNo);f.setVoucherType("EXPENSE");f.setVoucherDate(t.getTransactionDate());f.setCategory(r.category().trim());f.setReferenceNo(t.getOriginalReference());f.setAmount(totalDr);f.setPaymentMode(blank(r.paymentMode())?"Bank":r.paymentMode());f.setNotes(blank(r.notes())?t.getOriginalDescription():r.notes());f.setAccountName(resolvedAccName);f.setBillPath(r.billPath());f.setReconciled(1);f.setCreatedAt(BusinessClock.nowUtcText());f.setGstRate(rate);f.setGstAmount(gstTotal);f.setCgstAmount(cgst);f.setSgstAmount(sgst);f.setIgstAmount(igst);f.setVendorGstin(blank(r.vendorGstin())?null:r.vendorGstin().trim().toUpperCase(Locale.ROOT));f=finance.save(f);
  if (glProvider != null && glProvider.getIfAvailable() != null) {
   String actor = CurrentUser.isSet() ? CurrentUser.require().username() : safe(r.user());
   glProvider.getIfAvailable().postFinanceVoucherWithGst(f.getId(), vNo, date(t.getTransactionDate()), "EXPENSE", f.getCategory(), resolvedAccName, f.getPaymentMode(), BigDecimal.valueOf(totalDr), null, f.getNotes(), actor, BigDecimal.valueOf(rate), BigDecimal.valueOf(gstTotal), BigDecimal.valueOf(cgst), BigDecimal.valueOf(sgst), BigDecimal.valueOf(igst));
  }
  String old=t.getStatus();t.setStatus("EXPENSE");txs.save(t);BankReconciliationAllocationEntity al=new BankReconciliationAllocationEntity();al.setStatementTransactionId(t.getId());al.setTargetType("EXPENSE");al.setTargetId(f.getId());al.setAllocatedAmount(t.getDebitAmount());al.setFinanceEntryId(f.getId());al.setCreatedBy(org.example.server.security.CurrentUser.require().username());allocations.save(al);audit(t,"EXPENSE_CREATED","Expense voucher "+f.getVoucherNo()+" created",old,"EXPENSE",r.user());audit.log("FINANCE",f.getId(),"CREATED",vNo+" • Expense from Bank Statement #"+t.getId());audit.log("BANK_STATEMENT",t.getId().intValue(),"EXPENSE_CREATED","Expense voucher "+vNo+" created from statement #"+t.getId());InsightsService.invalidateDashboardCache();return new BankReconciliationDtos.OperationResult(true,"Expense created from bank statement.","EXPENSE",f.getId());
 }
 private BankReconciliationDtos.OperationResult createBankEntry(BankStatementTransactionEntity t,BankReconciliationDtos.BankEntryRequest r){
  double amount=bankAmount(t);boolean credit=n(t.getCreditAmount())>0;
  String vNo=operations.nextBankEntryVoucher();
  String defaultCategory=credit?"Deposit":"Withdrawal";
  String resolvedCategory=(r==null||blank(r.category()))?defaultCategory:r.category().trim();
  String baseNotes=(r==null||blank(r.notes()))?t.getOriginalDescription():r.notes().trim();
  if(r!=null&&!blank(r.contraAccountName())&&!baseNotes.toLowerCase(Locale.ROOT).contains(r.contraAccountName().trim().toLowerCase(Locale.ROOT))){
   baseNotes=baseNotes+" [Contra: "+r.contraAccountName().trim()+"]";
  }
  FinanceRegisterEntity f=new FinanceRegisterEntity();f.setVoucherNo(vNo);f.setVoucherType(credit?"BANK DEPOSIT":"BANK WITHDRAWAL");f.setVoucherDate(t.getTransactionDate());f.setCategory(resolvedCategory);if(r!=null&&r.partyId()!=null&&r.partyId()>0)f.setPartyId(r.partyId());f.setReferenceNo(t.getOriginalReference());f.setAmount(amount);f.setPaymentMode(blank(r==null?null:r.paymentMode())?"Bank Statement":r.paymentMode());f.setNotes(baseNotes);f.setAccountName(r==null||blank(r.accountName())?t.getImportBatch().getBankName()+" - "+t.getImportBatch().getBankAccount():r.accountName());f.setReconciled(1);f.setCreatedAt(BusinessClock.nowUtcText());f=finance.save(f);
  if (glProvider != null && glProvider.getIfAvailable() != null) {
   String actor = CurrentUser.isSet() ? CurrentUser.require().username() : safe(r == null ? null : r.user());
   String catForGl = resolvedCategory + (r != null && !blank(r.contraAccountName()) ? " " + r.contraAccountName().trim() : "");
   glProvider.getIfAvailable().postFinanceVoucher(f.getId(), vNo, date(t.getTransactionDate()), f.getVoucherType(), catForGl, f.getAccountName(), f.getPaymentMode(), BigDecimal.valueOf(amount), f.getPartyId(), f.getNotes(), actor);
  }
  String old=t.getStatus();t.setStatus("MATCHED");txs.save(t);BankReconciliationAllocationEntity al=new BankReconciliationAllocationEntity();al.setStatementTransactionId(t.getId());al.setTargetType("BANK_ENTRY");al.setTargetId(f.getId());al.setAllocatedAmount(amount);al.setFinanceEntryId(f.getId());al.setCreatedBy(org.example.server.security.CurrentUser.require().username());allocations.save(al);audit(t,"BANK_ENTRY_CREATED","Bank entry "+f.getVoucherNo()+" ("+resolvedCategory+") created",old,"MATCHED",r==null?null:r.user());audit.log("FINANCE",f.getId(),"CREATED",vNo+" • "+resolvedCategory+" from Bank Statement #"+t.getId());audit.log("BANK_STATEMENT",t.getId().intValue(),"BANK_ENTRY_CREATED",resolvedCategory+" voucher "+vNo+" created from statement #"+t.getId());InsightsService.invalidateDashboardCache();return new BankReconciliationDtos.OperationResult(true,resolvedCategory+" created from bank statement.","MATCHED",f.getId());
 }
 @Transactional public BankReconciliationDtos.OperationResult updateNote(Long id,BankReconciliationDtos.NoteRequest r){var t=reqTxForUpdate(id);long expected=r==null?-1:r.rowVersion();if(t.getRowVersion()!=expected)throw new ConcurrentEditException("Bank statement transaction");t.setNotes(r.note());txs.saveAndFlush(t);audit(t,"NOTE_UPDATED",r.note(),t.getStatus(),t.getStatus(),CurrentUser.require().username());return new BankReconciliationDtos.OperationResult(true,"Transaction note updated.",t.getStatus(),null);}
 @Transactional public BankReconciliationDtos.OperationResult ignore(Long id,BankReconciliationDtos.IgnoreRequest r){var t=reqTxForUpdate(id);ensureProcessable(t);String old=t.getStatus();t.setStatus("IGNORED");t.setNotes(r==null?null:r.note());txs.save(t);audit(t,"IGNORED",r==null?"Marked intentionally ignored":r.note(),old,"IGNORED",r==null?null:r.user());refreshBatch(t.getImportBatch().getId());return new BankReconciliationDtos.OperationResult(true,"Transaction marked as ignored.","IGNORED",null);}
 @Transactional public BankReconciliationDtos.OperationResult review(Long id,BankReconciliationDtos.NoteRequest r){var t=reqTxForUpdate(id);ensureProcessable(t);String old=t.getStatus();t.setStatus("REVIEW");t.setNotes(r==null?"Reviewed by user":r.note());txs.save(t);audit(t,"MARKED_REVIEW","Transaction marked for review",old,"REVIEW",r==null?null:r.user());refreshBatch(t.getImportBatch().getId());return new BankReconciliationDtos.OperationResult(true,"Transaction marked for review.","REVIEW",null);}

 @Transactional public BankReconciliationDtos.OperationResult reverse(Long id,String user){return reverseInternal(id,user,true);}
private BankReconciliationDtos.OperationResult reverseInternal(Long id,String user,boolean enforceTargetPermissions){var t=reqTxForUpdate(id);var active=allocations.findByStatementTransactionIdAndReversedAtIsNull(id);GeneralLedgerService gl = glProvider != null ? glProvider.getIfAvailable() : null;if (gl != null && date(t.getTransactionDate()) != null) gl.ensurePeriodOpen(date(t.getTransactionDate()));if(active.isEmpty()&&"IGNORED".equals(up(t.getStatus()))){String old=t.getStatus();t.setStatus("UNMATCHED");txs.save(t);audit(t,"REOPENED","Ignored transaction returned to reconciliation",old,"UNMATCHED",user);refreshBatch(t.getImportBatch().getId());InsightsService.invalidateDashboardCache();return new BankReconciliationDtos.OperationResult(true,"Transaction returned to UNMATCHED.","UNMATCHED",null);}if(active.isEmpty())throw new IllegalStateException("No active reconciliation exists for this transaction.");Set<Integer> financeIds=new HashSet<>();for(var a:active){String target=up(a.getTargetType());double settled=money(n(a.getAllocatedAmount())+n(a.getRoundingAdjustment()));if(a.getPaymentRecordId()!=null){if("SALE".equals(target)){sales.findByIdForUpdate(a.getTargetId()).ifPresent(x->{double paid=Math.max(0,n(x.getPaidAmount())-settled);x.setPaidAmount(paid);x.setPaymentStatus(paid<=.009?"PENDING":paid+.009>=n(x.getTotalAmount())?"PAID":"PARTIAL");sales.save(x);});payments.deleteById(a.getPaymentRecordId());}else if("PURCHASE".equals(target)){purchases.findByIdForUpdate(a.getTargetId()).ifPresent(x->{double paid=Math.max(0,n(x.getPaidAmount())-settled);x.setPaidAmount(paid);x.setPaymentStatus(paid<=.009?"PENDING":paid+.009>=n(x.getTotalAmount())?"PAID":"PARTIAL");purchases.save(x);});payments.deleteById(a.getPaymentRecordId());}else if(Set.of("SALES_RETURN","PURCHASE_RETURN").contains(target)){String no=jdbc.queryForObject("SELECT return_no FROM return_refund WHERE id=?",String.class,a.getPaymentRecordId());jdbc.update("DELETE FROM return_refund WHERE id=?",a.getPaymentRecordId());if(no!=null&&!no.isBlank())returnService.syncRefundState(no);}}if("PURCHASE_RECON".equals(target)){if(enforceTargetPermissions){org.example.server.security.CurrentUser.requirePermission("BANK_EXPENSE.RECONCILE","Bank reconciliation reversal");org.example.server.security.CurrentUser.requirePermission("PURCHASE_RECON.MATCH","Purchase Recon bank reversal");}purchaseRecons.findByIdForUpdate(a.getTargetId()).ifPresent(x->{double linked=Math.max(0,n(x.getLinkedAmount())-settled);x.setLinkedAmount(linked);if(linked<=.009)x.setStatus(x.getTaxReviewRequired()!=null&&x.getTaxReviewRequired()!=0?"NEEDS REVIEW":"OPEN");else if(linked+.009>=n(x.getInvoiceValue()))x.setStatus("RECONCILED");else x.setStatus("PARTIAL");purchaseRecons.save(x);});}if(a.getFinanceEntryId()!=null){financeIds.add(a.getFinanceEntryId());a.setFinanceEntryId(null);}a.setReversedAt(BusinessClock.nowUtcText());allocations.save(a);}for(Integer fid:financeIds){if (gl != null) gl.cancelJournalEntriesForReference("FINANCE", Long.valueOf(fid), user);if(finance.existsById(fid)){finance.deleteById(fid);audit.log("FINANCE",fid,"DELETED","Reversed via Bank Statement #"+t.getId());}}String old=t.getStatus();t.setStatus("UNMATCHED");t.setSuggestedMatchId(null);t.setSuggestedMatchType(null);t.setSuggestedConfidence(null);txs.save(t);audit(t,"REVERSED","Reconciliation reversed, including any round-off adjustment",old,"UNMATCHED",user);audit.log("BANK_STATEMENT",t.getId().intValue(),"REVERSED","Reconciliation reversed for bank transaction #"+t.getId());refreshBatch(t.getImportBatch().getId());InsightsService.invalidateDashboardCache();return new BankReconciliationDtos.OperationResult(true,"Reconciliation reversed.","UNMATCHED",null);}

 private void appendBankReconGlLines(GeneralLedgerService gl, List<GeneralLedgerDtos.CreateLineRequest> lines,
                                     boolean credit, String target, String docNo,
                                     double amount, double roundOff, double tdsAmt,
                                     Integer partyId, String partyName) {
  BigDecimal amt = BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP);
  BigDecimal ro = BigDecimal.valueOf(roundOff).setScale(2, RoundingMode.HALF_UP);
  BigDecimal tds = BigDecimal.valueOf(tdsAmt).setScale(2, RoundingMode.HALF_UP);
  BigDecimal total = amt.add(ro).add(tds);
  Long roundAcc = gl.getAccountIdOrDefault("5050", "Discounts & Rounding Adjustments", "EXPENSE");

  if (credit) {
   if (tds.compareTo(BigDecimal.ZERO) > 0) {
    Long tdsRec = gl.getAccountIdOrDefault("1090", "TDS Receivable (Sec 194Q/194C)", "ASSET");
    lines.add(new GeneralLedgerDtos.CreateLineRequest(tdsRec, "1090", tds, BigDecimal.ZERO, partyId, "TDS receivable: " + docNo, null));
   }
   if (ro.compareTo(BigDecimal.ZERO) > 0) {
    lines.add(new GeneralLedgerDtos.CreateLineRequest(roundAcc, "5050", ro, BigDecimal.ZERO, null, "Bank match rounding: " + docNo, null));
   } else if (ro.compareTo(BigDecimal.ZERO) < 0) {
    lines.add(new GeneralLedgerDtos.CreateLineRequest(roundAcc, "5050", BigDecimal.ZERO, ro.abs(), null, "Bank match rounding: " + docNo, null));
   }
   if ("PURCHASE_RETURN".equals(target)) {
    Long apAcc = gl.getAccountIdOrDefault("2010", "Sundry Creditors (Trade Payables)", "LIABILITY");
    lines.add(new GeneralLedgerDtos.CreateLineRequest(apAcc, "2010", BigDecimal.ZERO, total, partyId, "Supplier return refund: " + docNo, null));
   } else {
    Long arAcc = gl.getAccountIdOrDefault("1040", "Sundry Debtors (Trade Receivables)", "ASSET");
    lines.add(new GeneralLedgerDtos.CreateLineRequest(arAcc, "1040", BigDecimal.ZERO, total, partyId, "Receipt cleared (" + partyName + "): " + docNo, null));
   }
  } else {
   if ("CUSTOMER_REFUND".equals(target) || "SALES_RETURN".equals(target)) {
    Long arAcc = gl.getAccountIdOrDefault("1040", "Sundry Debtors (Trade Receivables)", "ASSET");
    lines.add(new GeneralLedgerDtos.CreateLineRequest(arAcc, "1040", total, BigDecimal.ZERO, partyId, "Customer refund (" + partyName + "): " + docNo, null));
   } else {
    Long apAcc = gl.getAccountIdOrDefault("2010", "Sundry Creditors (Trade Payables)", "LIABILITY");
    lines.add(new GeneralLedgerDtos.CreateLineRequest(apAcc, "2010", total, BigDecimal.ZERO, partyId, "Payout cleared (" + partyName + "): " + docNo, null));
   }
   if (tds.compareTo(BigDecimal.ZERO) > 0) {
    Long tdsPay = gl.getAccountIdOrDefault("2090", "TDS / TCS Payable", "LIABILITY");
    lines.add(new GeneralLedgerDtos.CreateLineRequest(tdsPay, "2090", BigDecimal.ZERO, tds, partyId, "TDS payable: " + docNo, null));
   }
   if (ro.compareTo(BigDecimal.ZERO) > 0) {
    lines.add(new GeneralLedgerDtos.CreateLineRequest(roundAcc, "5050", BigDecimal.ZERO, ro, null, "Payout rounding discount: " + docNo, null));
   } else if (ro.compareTo(BigDecimal.ZERO) < 0) {
    lines.add(new GeneralLedgerDtos.CreateLineRequest(roundAcc, "5050", ro.abs(), BigDecimal.ZERO, null, "Payout rounding excess: " + docNo, null));
   }
  }
 }

 @Transactional public BankReconciliationDtos.BatchDeleteResult deleteBatch(Long importId,String confirmation,String user){
  org.example.server.security.CurrentUser.requirePermission("BANK_EXPENSE.DELETE","Delete Bank Statement");
  String conf=confirmation==null?"":confirmation.trim().toUpperCase(Locale.ROOT);
  if(!"DELETE".equals(conf)&&!"FORCE DELETE".equals(conf))throw new IllegalArgumentException("Type DELETE or FORCE DELETE to confirm permanent statement deletion.");
  var batch=imports.findByIdForUpdate(importId).orElseThrow(()->new IllegalArgumentException("Statement batch not found."));List<BankStatementTransactionEntity> rows=txs.findByImportBatchIdOrderByTransactionTimestampAscIdAsc(importId);int reversed=0;
  long matchedCount = rows.stream().filter(tx -> !allocations.findByStatementTransactionIdAndReversedAtIsNull(tx.getId()).isEmpty() || Set.of("MATCHED", "PARTIAL").contains(up(tx.getStatus()))).count();
  if (matchedCount > 0 && !"FORCE DELETE".equals(conf)) throw new IllegalStateException("Statement cannot be deleted because " + matchedCount + " transaction" + (matchedCount == 1 ? " is" : "s are") + " already matched and reconciled with ERP records. Please unmatch/reverse them individually before deleting, or confirm with FORCE DELETE to unmatch all automatically.");
  for(var tx:rows){var active=allocations.findByStatementTransactionIdAndReversedAtIsNull(tx.getId());if(!active.isEmpty()||"IGNORED".equals(up(tx.getStatus()))){reverseInternal(tx.getId(),user,false);reversed++;}else if(RESOLVED.contains(up(tx.getStatus())))throw new IllegalStateException("Statement transaction #"+tx.getId()+" is resolved but has no reversible reconciliation record. Deletion was cancelled to protect accounting data.");}
  List<Long> ids=rows.stream().map(BankStatementTransactionEntity::getId).toList();if(!ids.isEmpty()){var allAudits=audits.findByStatementTransactionIdIn(ids);if(!allAudits.isEmpty())audits.deleteAll(allAudits);var allAllocations=allocations.findByStatementTransactionIdIn(ids);if(!allAllocations.isEmpty())allocations.deleteAll(allAllocations);txs.deleteAll(rows);txs.flush();}
  imports.delete(batch);imports.flush();InsightsService.invalidateDashboardCache();return new BankReconciliationDtos.BatchDeleteResult(true,"Bank Statement permanently deleted after safe reconciliation rollback.",importId,rows.size(),reversed);
 }
 public List<BankReconciliationDtos.AuditDto> audit(Long id){return audits.findByStatementTransactionIdOrderByIdDesc(id).stream().map(a->new BankReconciliationDtos.AuditDto(a.getId(),a.getEventType(),a.getEventDetail(),a.getPreviousStatus(),a.getNewStatus(),a.getPerformedBy(),a.getCreatedAt())).toList();}

 public List<BankReconciliationDtos.BankGlMappingDto> listBankGlMappings() {
  GeneralLedgerService gl = glProvider != null ? glProvider.getIfAvailable() : null;
  if (gl == null) return List.of();
  return gl.listBankGlMappings().stream().map(m -> new BankReconciliationDtos.BankGlMappingDto(
          m.get("id") instanceof Number n ? n.longValue() : null,
          Objects.toString(m.get("bank_account_key"), ""),
          Objects.toString(m.get("bank_name"), ""),
          Objects.toString(m.get("account_number"), ""),
          Objects.toString(m.get("gl_account_code"), "1020"),
          Objects.toString(m.get("gl_account_name"), "Bank Current Account"),
          m.get("current_balance") instanceof Number n ? n.doubleValue() : 0d
  )).toList();
 }

 public void updateBankGlMapping(BankReconciliationDtos.BankGlMappingUpdateRequest req) {
  if (req == null) throw new IllegalArgumentException("Mapping request is required.");
  GeneralLedgerService gl = glProvider != null ? glProvider.getIfAvailable() : null;
  if (gl == null) throw new IllegalStateException("General Ledger service is not available.");
  String actor = CurrentUser.isSet() ? CurrentUser.require().username() : "ADMIN";
  gl.updateBankGlMapping(req.bankAccountKey(), req.glAccountCode(), actor);
 }

 private static final double[] TDS_RATES = {0.001, 0.01, 0.02, 0.05, 0.10};
 private static boolean isStatutoryTds(double total,double taxable,double diff){
  if(diff<=0||(total<=0&&taxable<=0))return false;
  for(double rate:TDS_RATES){
   if(taxable>0&&Math.abs(diff-DocumentCalculationEngine.money(taxable*rate))<=1.0)return true;
   if(total>0&&Math.abs(diff-DocumentCalculationEngine.money(total*rate))<=1.0)return true;
  }
  return false;
 }
 private static String statutoryTdsLabel(double total,double taxable,double diff){
  if(diff<=0||(total<=0&&taxable<=0))return "";
  double base=taxable>0?taxable:total;
  if(matchesTdsRate(total,taxable,diff,0.001))return "0.1% TDS (Sec 194Q)"+(taxable>0&&Math.abs(diff-DocumentCalculationEngine.money(taxable*0.001))<=1.0?" on Taxable":"");
  if(matchesTdsRate(total,taxable,diff,0.01))return "1% TDS (Sec 194C)"+(taxable>0&&Math.abs(diff-DocumentCalculationEngine.money(taxable*0.01))<=1.0?" on Taxable":"");
  if(matchesTdsRate(total,taxable,diff,0.02))return "2% TDS (Sec 194C/J)"+(taxable>0&&Math.abs(diff-DocumentCalculationEngine.money(taxable*0.02))<=1.0?" on Taxable":"");
  if(matchesTdsRate(total,taxable,diff,0.05))return "5% TDS (Sec 194J)"+(taxable>0&&Math.abs(diff-DocumentCalculationEngine.money(taxable*0.05))<=1.0?" on Taxable":"");
  if(matchesTdsRate(total,taxable,diff,0.10))return "10% TDS (Sec 194J)"+(taxable>0&&Math.abs(diff-DocumentCalculationEngine.money(taxable*0.10))<=1.0?" on Taxable":"");
  return base>0?"Statutory TDS":"";
 }
 private static boolean matchesTdsRate(double total,double taxable,double diff,double rate){
  return (taxable>0&&Math.abs(diff-DocumentCalculationEngine.money(taxable*rate))<=1.0)
      || (total>0&&Math.abs(diff-DocumentCalculationEngine.money(total*rate))<=1.0);
 }
 private void applySuggestion(BankStatementTransactionEntity t){
  String desc=up(t.getOriginalDescription());
  String holder=compact(t.getImportBatch().getAccountHolder());
  if(desc.contains("REV-")||desc.startsWith("REV")){t.setStatus("REVIEW");t.setNotes("Possible bank reversal; review before posting.");return;}
  if(!holder.isBlank()&&compact(desc).contains(holder)){t.setStatus("REVIEW");t.setNotes("Possible transfer involving the statement account holder; verify before posting.");return;}
  if(desc.contains("CHRG")||desc.contains("CHARGE")){t.setStatus("REVIEW");t.setNotes("Likely bank charge; review and use Move to Expense if appropriate.");return;}
  var cs=candidatesFor(t);
  if(!cs.isEmpty()&&cs.get(0).confidence()>=75&&bankAmount(t)<=cs.get(0).outstanding()+roundingTolerance()+0.01){
   var c=cs.get(0);
   t.setStatus("SUGGESTED");
   t.setSuggestedMatchType(c.type());
   t.setSuggestedMatchId(c.id());
   t.setSuggestedConfidence(c.confidence());
   if(c.matchReason()!=null&&!c.matchReason().isBlank())t.setNotes(c.matchReason());
  }else t.setStatus("UNMATCHED");
 }
 private Map<Integer,List<String>> loadPartyBankAliases(){
  Map<Integer,List<String>> map=new HashMap<>();
  try{
   for(Map<String,Object> r:jdbc.queryForList("SELECT party_id, alias_pattern FROM party_bank_alias WHERE party_id IS NOT NULL AND alias_pattern IS NOT NULL")){
    int pid=((Number)r.get("party_id")).intValue();
    String pat=Objects.toString(r.get("alias_pattern"),"").trim().toLowerCase(Locale.ROOT);
    if(!pat.isBlank())map.computeIfAbsent(pid,ignored->new ArrayList<>()).add(pat);
   }
  }catch(Exception ignored){}
  return map;
 }
 private void learnPartyAliasFromMatch(BankStatementTransactionEntity t, Integer partyId){
  if(t==null||partyId==null||partyId<=0)return;
  String desc=safe(t.getOriginalDescription());
  if(desc.isBlank())return;
  Set<String> candidates=new LinkedHashSet<>();
  var vpaMatcher=Pattern.compile("([A-Za-z0-9._\\-]{3,}@[A-Za-z0-9]{2,})").matcher(desc);
  while(vpaMatcher.find())candidates.add(vpaMatcher.group(1).trim());
  var parsed=BankNarrationParser.parse(desc,t.getOriginalReference());
  if(parsed.hasCounterparty()&&parsed.counterparty().trim().length()>=4)candidates.add(parsed.counterparty().trim());
  String actor=CurrentUser.isSet()?CurrentUser.require().username():"SYSTEM";
  String now=BusinessClock.nowUtcText();
  for(String pat:candidates){
   String upPat=up(pat);
   if(upPat.length()>150||GENERIC_PARTY_TOKENS.contains(upPat)||STOP_WORDS.contains(upPat))continue;
   String type=pat.contains("@")?"UPI_HANDLE":"COUNTERPARTY";
   try{
    jdbc.update("""
      INSERT INTO party_bank_alias(party_id,alias_pattern,alias_type,hit_count,last_seen_at,created_by,created_at)
      VALUES(?,?,?,1,?,?,?)
      ON CONFLICT (LOWER(alias_pattern)) DO UPDATE SET
        party_id = EXCLUDED.party_id,
        hit_count = party_bank_alias.hit_count + 1,
        last_seen_at = EXCLUDED.last_seen_at
      """,partyId,pat,type,now,actor,now);
   }catch(Exception ignored){}
  }
 }
 private List<BankReconciliationDtos.CandidateDto> candidatesFor(BankStatementTransactionEntity t){
  double tolerance=roundingTolerance();
  Map<Integer,List<String>> aliasMap=loadPartyBankAliases();
  return candidatesFor(t,tolerance,aliasMap,null,null,null,null,null,null);
 }
 private List<BankReconciliationDtos.CandidateDto> candidatesFor(
   BankStatementTransactionEntity t,
   double tolerance,
   Map<Integer,List<String>> aliasMap,
   List<SalesHeaderEntity> preloadedSales,
   List<PurchaseHeaderEntity> preloadedPurchases,
   List<PurchaseReconEntity> preloadedRecons,
   List<Map<String,Object>> preloadedReturnsSale,
   List<Map<String,Object>> preloadedReturnsPur,
   List<Map<String,Object>> preloadedRecordedPayments){
  List<BankReconciliationDtos.CandidateDto> out=new ArrayList<>();double amount=bankAmount(t);
  if(n(t.getCreditAmount())>0){
   List<SalesHeaderEntity> openSales=preloadedSales!=null?preloadedSales:sales.findOpenForBankMatching(amount,PageRequest.of(0,500));
   for(var sale:openSales){
    double o=Math.max(0,n(sale.getTotalAmount())-n(sale.getPaidAmount()));
    if(o<=0.009)continue;
    var cust=sale.getCustomer();
    double taxable=n(sale.getSubtotal())>0?n(sale.getSubtotal()):n(sale.getTotalAmount());
    String extraRef=(safe(sale.getOrderNo())+" "+safe(sale.getReferenceNo())).trim();
    out.add(candidate(t,"SALE",sale.getId(),sale.getInvoiceNo(),cust==null?"":cust.getName(),cust==null?null:cust.getId(),cust==null?"":cust.getPhone(),cust==null?"":cust.getGstin(),sale.getInvoiceDate(),n(sale.getTotalAmount()),taxable,n(sale.getPaidAmount()),o,tolerance,aliasMap,extraRef));
   }
   addMultiInvoiceSplitCandidates(out,t,"SALE",openSales,null,tolerance,aliasMap);
   if(preloadedReturnsPur!=null) addReturnCandidatesFromRows(out,t,"PURCHASE_RETURN",preloadedReturnsPur,tolerance,aliasMap);
   else addReturnCandidates(out,t,"PURCHASE RETURN","PURCHASE_RETURN",tolerance,aliasMap);
   addRecordedPaymentCandidates(out,t,"SALE",tolerance,aliasMap,preloadedRecordedPayments);
   addManualPaymentCandidates(out,t,"Deposit");
  }else{
   List<PurchaseHeaderEntity> openPurchases=preloadedPurchases!=null?preloadedPurchases:purchases.findOpenForBankMatching(amount,PageRequest.of(0,500));
   for(var purchase:openPurchases){
    double o=Math.max(0,n(purchase.getTotalAmount())-n(purchase.getPaidAmount()));
    if(o<=0.009)continue;
    var sup=purchase.getSupplier();
    double taxable=n(purchase.getSubtotal())>0?n(purchase.getSubtotal()):n(purchase.getTotalAmount());
    out.add(candidate(t,"PURCHASE",purchase.getId(),purchase.getInvoiceNo(),sup==null?"":sup.getName(),sup==null?null:sup.getId(),sup==null?"":sup.getPhone(),sup==null?"":sup.getGstin(),purchase.getInvoiceDate(),n(purchase.getTotalAmount()),taxable,n(purchase.getPaidAmount()),o,tolerance,aliasMap,safe(purchase.getReferenceNo())));
   }
   addMultiInvoiceSplitCandidates(out,t,"PURCHASE",null,openPurchases,tolerance,aliasMap);
   if(org.example.server.security.CurrentUser.hasPermission("BANK_EXPENSE.RECONCILE")&&org.example.server.security.CurrentUser.hasPermission("PURCHASE_RECON.MATCH")){
    List<PurchaseReconEntity> openRecons=preloadedRecons!=null?preloadedRecons:purchaseRecons.findOpenForBankMatching(amount,PageRequest.of(0,500));
    for(var recon:openRecons){
     double o=Math.max(0,n(recon.getInvoiceValue())-n(recon.getLinkedAmount()));
     if(o<=0.009)continue;
     var rSup=recon.getReconSupplier();
     Integer spId=rSup==null?null:rSup.getId();String spPhone="",spGstin="";
     if(rSup!=null){spPhone=safe(rSup.getPhone());spGstin=safe(rSup.getGstin());}
     out.add(candidate(t,"PURCHASE_RECON",recon.getId(),recon.getReconRef(),recon.getSupplierNameSnapshot(),spId,spPhone,spGstin,recon.getInvoiceDate()==null?"":recon.getInvoiceDate().toString(),n(recon.getInvoiceValue()),n(recon.getInvoiceValue()),n(recon.getLinkedAmount()),o,tolerance,aliasMap,safe(recon.getSupplierInvoiceNo())));
    }
   }
   if(preloadedReturnsSale!=null) addReturnCandidatesFromRows(out,t,"SALES_RETURN",preloadedReturnsSale,tolerance,aliasMap);
   else addReturnCandidates(out,t,"SALES RETURN","SALES_RETURN",tolerance,aliasMap);
   addRecordedPaymentCandidates(out,t,"PURCHASE",tolerance,aliasMap,preloadedRecordedPayments);
   addManualPaymentCandidates(out,t,"Withdrawal");
  }
  out.sort(Comparator.comparingDouble(BankReconciliationDtos.CandidateDto::confidence).reversed());
  return out;
 }
 private record SplitDoc(int id,String no,String date,double total,double paid,double outAmt){}
 private void addMultiInvoiceSplitCandidates(
   List<BankReconciliationDtos.CandidateDto> out,
   BankStatementTransactionEntity t,
   String type,
   List<SalesHeaderEntity> openSales,
   List<PurchaseHeaderEntity> openPurchases,
   double tolerance,
   Map<Integer,List<String>> aliasMap){
  double targetAmt=bankAmount(t);
  if(targetAmt<=0)return;
  record PartyBucket(int partyId,String partyName,String phone,String gstin,List<SplitDoc> docs){}
  Map<Integer,PartyBucket> byParty=new LinkedHashMap<>();
  if("SALE".equals(type)&&openSales!=null){
   for(var s:openSales){
    var p=s.getCustomer();if(p==null||p.getId()==null)continue;
    double o=Math.max(0,n(s.getTotalAmount())-n(s.getPaidAmount()));
    if(o<=0.009||o>=targetAmt-0.01)continue;
    byParty.computeIfAbsent(p.getId(),ignored->new PartyBucket(p.getId(),safe(p.getName()),safe(p.getPhone()),safe(p.getGstin()),new ArrayList<>()))
      .docs().add(new SplitDoc(s.getId(),s.getInvoiceNo(),safe(s.getInvoiceDate()),n(s.getTotalAmount()),n(s.getPaidAmount()),o));
   }
  }else if("PURCHASE".equals(type)&&openPurchases!=null){
   for(var pu:openPurchases){
    var p=pu.getSupplier();if(p==null||p.getId()==null)continue;
    double o=Math.max(0,n(pu.getTotalAmount())-n(pu.getPaidAmount()));
    if(o<=0.009||o>=targetAmt-0.01)continue;
    byParty.computeIfAbsent(p.getId(),ignored->new PartyBucket(p.getId(),safe(p.getName()),safe(p.getPhone()),safe(p.getGstin()),new ArrayList<>()))
      .docs().add(new SplitDoc(pu.getId(),pu.getInvoiceNo(),safe(pu.getInvoiceDate()),n(pu.getTotalAmount()),n(pu.getPaidAmount()),o));
   }
  }
  String hay=up(safe(t.getOriginalDescription())+" "+safe(t.getOriginalReference()));
  String normHay=" "+hay.replaceAll("[^A-Z0-9 ]"," ").replaceAll("\\s+"," ").trim()+" ";
  for(PartyBucket bucket:byParty.values()){
   if(bucket.docs().size()<2)continue;
   double sumAll=bucket.docs().stream().mapToDouble(SplitDoc::outAmt).sum();
   if(sumAll+tolerance<targetAmt)continue;
   boolean partySignal=identitySignalScore(normHay,hay,bucket.partyId(),bucket.partyName(),bucket.phone(),bucket.gstin(),aliasMap,new ArrayList<>())>0;
   List<SplitDoc> fifoOrdered=new ArrayList<>(bucket.docs());
   fifoOrdered.sort(Comparator.comparing((SplitDoc d)->Optional.ofNullable(date(d.date())).orElse(LocalDate.MAX))
     .thenComparingInt(SplitDoc::id));
   List<SplitDoc> capped=fifoOrdered.stream().limit(18).toList();
   List<SplitDoc> picked=findSubsetMatch(capped,targetAmt,tolerance);
   if(picked.isEmpty())picked=findContiguousFifoMatch(fifoOrdered,targetAmt,tolerance);
   if(picked.isEmpty())continue;
   if(!partySignal&&picked.size()>2)continue;
   double pickedSum=money(picked.stream().mapToDouble(SplitDoc::outAmt).sum());
   double diff=money(pickedSum-targetAmt);
   double conf=partySignal?92.0:76.0;
   if(Math.abs(diff)<=0.01)conf+=3.0;
   String docLabel=picked.stream().map(SplitDoc::no).collect(java.util.stream.Collectors.joining(", "));
   if(docLabel.length()>42)docLabel=picked.size()+" Docs ("+picked.getFirst().no()+"...)";
   double remToAlloc=targetAmt;
   List<BankReconciliationDtos.AllocationRequest> splitAllocs=new ArrayList<>();
   for(int idx=0;idx<picked.size();idx++){
    SplitDoc d=picked.get(idx);
    double alloc=(idx==picked.size()-1)?money(Math.max(0.01,remToAlloc)):money(Math.min(d.outAmt(),remToAlloc));
    splitAllocs.add(new BankReconciliationDtos.AllocationRequest(type,d.id(),alloc));
    remToAlloc=money(remToAlloc-alloc);
   }
   out.add(new BankReconciliationDtos.CandidateDto(
     "MULTI_SPLIT",picked.getFirst().id(),docLabel,bucket.partyName(),picked.getFirst().date(),
     picked.stream().mapToDouble(SplitDoc::total).sum(),picked.stream().mapToDouble(SplitDoc::paid).sum(),
     pickedSum,Math.min(98.0,conf),
     "Multi-Invoice Split ("+picked.size()+" docs FIFO)"+(partySignal?" • Party Matched":"")+(Math.abs(diff)>0.005?" • Round-off "+signedMoney(diff):""),
     pickedSum,0d,"",diff,List.copyOf(splitAllocs)
   ));
  }
 }
 private static List<SplitDoc> findContiguousFifoMatch(List<SplitDoc> docs,double target,double tolerance){
  if(docs==null||docs.size()<2)return List.of();
  double tol=Math.max(0.01,tolerance),sum=0d;
  List<SplitDoc> picked=new ArrayList<>();
  for(SplitDoc d:docs){
   sum=money(sum+d.outAmt());
   picked.add(d);
   if(picked.size()>=2&&Math.abs(sum-target)<=tol)return List.copyOf(picked);
   if(sum>target+tol)break;
  }
  return List.of();
 }
 private static List<SplitDoc> findSubsetMatch(List<SplitDoc> docs,double target,double tolerance){
  double tol=Math.max(0.01,tolerance);
  List<SplitDoc> current=new ArrayList<>();
  for(int maxSize=2;maxSize<=6;maxSize++){
   if(searchSubset(docs,0,maxSize,0d,target,tol,current))return List.copyOf(current);
   current.clear();
  }
  return List.of();
 }
 private static boolean searchSubset(List<SplitDoc> docs,int idx,int maxSize,double sum,double target,double tol,List<SplitDoc> picked){
  if(picked.size()>=2&&Math.abs(sum-target)<=tol)return true;
  if(picked.size()>=maxSize||sum>target+tol)return false;
  for(int i=idx;i<docs.size();i++){
   SplitDoc d=docs.get(i);
   picked.add(d);
   if(searchSubset(docs,i+1,maxSize,money(sum+d.outAmt()),target,tol,picked))return true;
   picked.removeLast();
  }
  return false;
 }
 @Transactional(readOnly=true)
 public BankReconciliationDtos.AutoMatchPreviewDto previewAutoMatch(Long importId,String bankAccount,String fromDate,String toDate){
  boolean batchMode=importId!=null&&importId>0L;
  List<BankStatementTransactionEntity> scopedRows=batchMode
    ?txs.findByImportBatchIdOrderByTransactionTimestampAscIdAsc(importId)
    :txs.searchFiltered(0L,safe(bankAccount).trim(),"","ALL","ALL",safe(fromDate).trim(),safe(toDate).trim(),"",PageRequest.of(0,1000)).getContent();
  Set<String> openStates=Set.of("UNMATCHED","SUGGESTED","REVIEW");
  List<BankStatementTransactionEntity> openRows=scopedRows.stream()
    .filter(t->openStates.contains(up(t.getStatus())))
    .toList();
  if(openRows.isEmpty()){
   return new BankReconciliationDtos.AutoMatchPreviewDto(0,0,0d,List.of());
  }
  double tolerance=roundingTolerance();
  Map<Integer,List<String>> aliasMap=loadPartyBankAliases();
  List<SalesHeaderEntity> openSales=sales.findOpenForBankMatching(0d,PageRequest.of(0,500));
  List<PurchaseHeaderEntity> openPurchases=purchases.findOpenForBankMatching(0d,PageRequest.of(0,500));
  List<PurchaseReconEntity> openRecons=(org.example.server.security.CurrentUser.hasPermission("BANK_EXPENSE.RECONCILE")
    &&org.example.server.security.CurrentUser.hasPermission("PURCHASE_RECON.MATCH"))
    ?purchaseRecons.findOpenForBankMatching(0d,PageRequest.of(0,500)):List.of();
  List<Map<String,Object>> saleReturns=loadOpenReturnRows("SALES RETURN");
  List<Map<String,Object>> purReturns=loadOpenReturnRows("PURCHASE RETURN");
  List<Map<String,Object>> recordedSalePays=loadUnlinkedRecordedPayments("SALE");
  List<Map<String,Object>> recordedPurchasePays=loadUnlinkedRecordedPayments("PURCHASE");

  List<BankReconciliationDtos.TransactionDto> dtos=mapTransactions(openRows);
  Map<Long,BankReconciliationDtos.TransactionDto> dtoById=new HashMap<>();
  for(int i=0;i<openRows.size();i++)dtoById.put(openRows.get(i).getId(),dtos.get(i));

  Set<String> claimedDocs=new HashSet<>();
  Set<Long> handledTxIds=new HashSet<>();
  List<BankReconciliationDtos.AutoMatchSuggestionDto> suggestions=new ArrayList<>();

  // 1. Detect same-account Reversal / Bounce pairs first so bounced transfers never falsely match invoices
  for(int i=0;i<openRows.size();i++){
   BankStatementTransactionEntity a=openRows.get(i);
   if(handledTxIds.contains(a.getId()))continue;
   double amtA=bankAmount(a);if(amtA<=0)continue;
   boolean creditA=n(a.getCreditAmount())>0;
   String acctA=a.getImportBatch()!=null?safe(a.getImportBatch().getBankAccount()):"";
   LocalDate dateA=date(a.getTransactionDate());
   for(int j=i+1;j<openRows.size();j++){
    BankStatementTransactionEntity b=openRows.get(j);
    if(handledTxIds.contains(b.getId()))continue;
    if(creditA==(n(b.getCreditAmount())>0))continue;
    String acctB=b.getImportBatch()!=null?safe(b.getImportBatch().getBankAccount()):"";
    if(acctA.isBlank()||acctB.isBlank()||!accountsMatch(acctA,acctB))continue;
    if(Math.abs(amtA-bankAmount(b))>0.01)continue;
    LocalDate dateB=date(b.getTransactionDate());
    if(dateA!=null&&dateB!=null&&Math.abs(ChronoUnit.DAYS.between(dateA,dateB))>3)continue;
    if(!hasReversalOrBounceMarker(a)&&!hasReversalOrBounceMarker(b))continue;
    handledTxIds.add(a.getId());
    handledTxIds.add(b.getId());
    String acctLabel=a.getImportBatch()!=null?(safe(a.getImportBatch().getBankName())+" - "+acctA):acctA;
    suggestions.add(new BankReconciliationDtos.AutoMatchSuggestionDto(
      dtoById.get(a.getId()),"BOUNCE",null,null,null,"IGNORE",null,acctLabel,"Bank Transfer",
      "Reversal / Bounce Pair",null,acctLabel,b.getId(),0d,
      "Same-Account Reversal / Bounce Pair (#"+b.getId()+" • ₹"+fmt(amtA)+") — Verify & Ignore",82.0,false
    ));
    suggestions.add(new BankReconciliationDtos.AutoMatchSuggestionDto(
      dtoById.get(b.getId()),"BOUNCE",null,null,null,"IGNORE",null,acctLabel,"Bank Transfer",
      "Reversal / Bounce Pair",null,acctLabel,a.getId(),0d,
      "Same-Account Reversal / Bounce Pair (#"+a.getId()+" • ₹"+fmt(amtA)+") — Verify & Ignore",82.0,false
    ));
    break;
   }
  }

  // 2. Collect all eligible candidates per open transaction, preserving backups for contested resolution
  record TxPool(BankStatementTransactionEntity tx,BankReconciliationDtos.TransactionDto dto,List<BankReconciliationDtos.CandidateDto> eligible){}
  List<TxPool> pools=new ArrayList<>();
  for(BankStatementTransactionEntity tx:openRows){
   if(handledTxIds.contains(tx.getId()))continue;
   double bankAmt=bankAmount(tx);
   if(bankAmt<=0)continue;
   List<BankReconciliationDtos.CandidateDto> cs=candidatesFor(tx,tolerance,aliasMap,openSales,openPurchases,openRecons,saleReturns,purReturns,
     n(tx.getCreditAmount())>0?recordedSalePays:recordedPurchasePays);
   List<BankReconciliationDtos.CandidateDto> elig=new ArrayList<>();
   for(var c:cs){
    boolean validSettlement=bankAmt<=c.outstanding()+tolerance+0.01;
    if(!validSettlement)continue;
    double diff=Math.abs(c.outstanding()-bankAmt);
    boolean isEligible=c.confidence()>=70.0||diff<=tolerance+0.01||(cs.size()==1&&c.confidence()>=45.0&&diff<=0.01);
    if(isEligible)elig.add(c);
   }
   if(!elig.isEmpty())pools.add(new TxPool(tx,dtoById.get(tx.getId()),elig));
  }

  // Prioritize uniquely constrained single-option verified matches first, then highest confidence
  pools.sort((p1,p2)->{
   var c1=p1.eligible().getFirst();
   var c2=p2.eligible().getFirst();
   boolean s1=isStrongVerifiedCandidate(c1,p1.eligible().size()>1?p1.eligible().get(1):null);
   boolean s2=isStrongVerifiedCandidate(c2,p2.eligible().size()>1?p2.eligible().get(1):null);
   if(s1!=s2)return s1?-1:1;
   boolean u1=p1.eligible().size()==1,u2=p2.eligible().size()==1;
   if(u1!=u2)return u1?-1:1;
   return Double.compare(c2.confidence(),c1.confidence());
  });

  for(TxPool pool:pools){
   BankReconciliationDtos.CandidateDto picked=null;
   BankReconciliationDtos.CandidateDto runnerUp=null;
   int closeCompetitors=0;
   for(int idx=0;idx<pool.eligible().size();idx++){
    var c=pool.eligible().get(idx);
    List<String> keys=candidateClaimKeys(c);
    if(keys.stream().anyMatch(claimedDocs::contains))continue;
    if(picked==null){
     picked=c;
     for(int j=idx+1;j<pool.eligible().size();j++){
      var alt=pool.eligible().get(j);
      if(candidateClaimKeys(alt).stream().anyMatch(claimedDocs::contains))continue;
      if(runnerUp==null)runnerUp=alt;
      if((c.confidence()-alt.confidence())<12.0&&!Objects.equals(up(c.partyName()),up(alt.partyName())))closeCompetitors++;
     }
     break;
    }
   }
   if(picked==null)continue;
   boolean hasStrongProof=hasStrongMatchProof(picked);
   boolean ambiguous=closeCompetitors>0&&!hasStrongProof;
   boolean verified=isStrongVerifiedCandidate(picked,ambiguous?runnerUp:null)&&!ambiguous;
   boolean canLockClaim=verified||picked.confidence()>=70.0;
   if(canLockClaim)claimedDocs.addAll(candidateClaimKeys(picked));
   handledTxIds.add(pool.tx().getId());
   double variance=picked.suggestedTdsAmount()>0?picked.suggestedTdsAmount():picked.suggestedRounding();
   String kind="RECORDED_PAYMENT".equals(picked.type())?"RECORDED_PAYMENT":("MULTI_SPLIT".equals(picked.type())?"MULTI_SPLIT":"DOCUMENT");
   String reason=safe(picked.matchReason());
   if(ambiguous){
    reason=(reason.isBlank()?"":reason+" • ")+"Ambiguous ("+(closeCompetitors+1)+" close matches — review required)";
   }else if(!verified&&picked.confidence()<70.0){
    reason=(reason.isBlank()?"":reason+" • ")+"Verify Party (Amount-Only Match)";
   }
   suggestions.add(new BankReconciliationDtos.AutoMatchSuggestionDto(
     pool.dto(),kind,picked,null,null,null,null,null,null,null,null,null,null,
     variance,reason,picked.confidence(),verified
   ));
  }

  // Evaluate Smart Rules on remaining open transactions
  if(ruleService!=null&&ruleService.isFeatureEnabled()){
   for(BankStatementTransactionEntity tx:openRows){
    if(handledTxIds.contains(tx.getId()))continue;
    BankReconciliationDtos.RuleDto rule=ruleService.findMatchingRuleDto(tx);
    if(rule==null)continue;
    handledTxIds.add(tx.getId());
    String targetDesc=switch(up(rule.actionType())){
     case "EXPENSE" -> "Rule Expense: "+safe(rule.targetCategory());
     case "BANK_ENTRY" -> "Rule Bank Entry: "+safe(rule.targetAccountName());
     case "CONTRA" -> "Rule Contra: "+safe(rule.targetContraAccount());
     case "MATCH_PARTY_FIFO" -> "Rule Party FIFO Settlement";
     case "IGNORE" -> "Rule Ignore";
     default -> "Smart Rule";
    };
    suggestions.add(new BankReconciliationDtos.AutoMatchSuggestionDto(
      dtoById.get(tx.getId()),"RULE",null,rule.id(),rule.ruleName(),rule.actionType(),
      rule.targetCategory(),rule.targetAccountName(),rule.targetPaymentMode(),rule.targetNotes(),
      rule.targetPartyId(),rule.targetContraAccount(),null,0d,
      "Smart Rule '"+rule.ruleName()+"' → "+targetDesc,95.0,true
    ));
   }
  }

  // Evaluate Inter-Bank Contra Transfer pairs across open transactions
  for(int i=0;i<openRows.size();i++){
   BankStatementTransactionEntity a=openRows.get(i);
   if(handledTxIds.contains(a.getId()))continue;
   double amtA=bankAmount(a);boolean creditA=n(a.getCreditAmount())>0;
   String acctA=a.getImportBatch()!=null?safe(a.getImportBatch().getBankAccount()):"";
   String acctLabelA=a.getImportBatch()!=null?(safe(a.getImportBatch().getBankName())+" - "+acctA):"";
   LocalDate dateA=date(a.getTransactionDate());
   for(int j=i+1;j<openRows.size();j++){
    BankStatementTransactionEntity b=openRows.get(j);
    if(handledTxIds.contains(b.getId()))continue;
    boolean creditB=n(b.getCreditAmount())>0;
    if(creditA==creditB)continue;
    String acctB=b.getImportBatch()!=null?safe(b.getImportBatch().getBankAccount()):"";
    if(acctA.isBlank()||acctB.isBlank()||acctA.equalsIgnoreCase(acctB))continue;
    if(Math.abs(amtA-bankAmount(b))>0.01)continue;
    LocalDate dateB=date(b.getTransactionDate());
    if(dateA!=null&&dateB!=null&&Math.abs(ChronoUnit.DAYS.between(dateA,dateB))>2)continue;
    String acctLabelB=b.getImportBatch()!=null?(safe(b.getImportBatch().getBankName())+" - "+acctB):"";
    handledTxIds.add(a.getId());
    handledTxIds.add(b.getId());
    suggestions.add(new BankReconciliationDtos.AutoMatchSuggestionDto(
      dtoById.get(a.getId()),"CONTRA",null,null,null,"CONTRA",null,acctLabelA,"Bank Transfer",
      "Contra transfer paired with "+acctLabelB,null,acctLabelB,b.getId(),0d,
      "Inter-Bank Contra Pair ("+acctLabelB+" • ₹"+fmt(amtA)+")",88.0,true
    ));
    suggestions.add(new BankReconciliationDtos.AutoMatchSuggestionDto(
      dtoById.get(b.getId()),"CONTRA",null,null,null,"CONTRA",null,acctLabelB,"Bank Transfer",
      "Contra transfer paired with "+acctLabelA,null,acctLabelA,a.getId(),0d,
      "Inter-Bank Contra Pair ("+acctLabelA+" • ₹"+fmt(amtA)+")",88.0,true
    ));
    break;
   }
  }

  suggestions.sort((a,b)->Double.compare(b.confidence(),a.confidence()));
  double totalEligibleAmt=money(suggestions.stream().mapToDouble(s->Math.max(s.tx().credit(),s.tx().debit())).sum());
  return new BankReconciliationDtos.AutoMatchPreviewDto(openRows.size(),suggestions.size(),totalEligibleAmt,List.copyOf(suggestions));
 }
 private static boolean hasReversalOrBounceMarker(BankStatementTransactionEntity t){
  String s=" "+up(safe(t.getOriginalDescription())+" "+safe(t.getOriginalReference())).replaceAll("[^A-Z0-9 ]"," ")+" ";
  return s.contains(" REV ")||s.contains(" REVERSAL ")||s.contains(" BOUNCE ")||s.contains(" DISHONOUR ")||s.contains(" UNPAID ")||s.contains(" CHQ RET ")||s.contains(" CHEQUE RETURN ")||up(safe(t.getOriginalDescription())).startsWith("REV-");
 }
 private static List<String> candidateClaimKeys(BankReconciliationDtos.CandidateDto c){
  List<String> keys=new ArrayList<>();
  if(c.splitAllocations()!=null&&!c.splitAllocations().isEmpty()){
   for(var sa:c.splitAllocations())keys.add(up(sa.targetType())+"#"+sa.targetId());
  }else if(c.id()!=null){
   keys.add(up(c.type())+"#"+c.id());
  }
  return keys;
 }
 private static boolean hasStrongMatchProof(BankReconciliationDtos.CandidateDto c){
  String r=safe(c.matchReason());
  return r.contains("Invoice #")||r.contains("Ref #")||r.contains("Ref/UTR matched")||r.contains("Learned VPA/Alias")||r.contains("GSTIN matched")||r.contains("PAN matched")||r.contains("Phone matched");
 }
 private static boolean isStrongVerifiedCandidate(BankReconciliationDtos.CandidateDto best,BankReconciliationDtos.CandidateDto runnerUp){
  if(best==null||best.confidence()<70.0)return false;
  if(runnerUp!=null&&(best.confidence()-runnerUp.confidence())<12.0&&!hasStrongMatchProof(best)
    &&!Objects.equals(up(best.partyName()),up(runnerUp.partyName())))return false;
  return true;
 }
 private List<Map<String,Object>> loadUnlinkedRecordedPayments(String docType){
  try{
   return jdbc.queryForList("""
     SELECT pr.id, pr.document_type, pr.document_id, pr.payment_date, pr.amount,
            pr.payment_mode, pr.reference_no, pr.payment_reference, pr.received_from,
            COALESCE(s.invoice_no, p.invoice_no, '') AS invoice_no,
            COALESCE(cs.id, ps.id) AS party_id,
            COALESCE(cs.name, ps.name, pr.received_from, '') AS party_name,
            COALESCE(cs.phone, ps.phone, '') AS party_phone,
            COALESCE(cs.gstin, ps.gstin, '') AS party_gstin
     FROM payment_record pr
     LEFT JOIN sales_header s ON pr.document_type = 'SALE' AND s.id = pr.document_id
     LEFT JOIN party_master cs ON cs.id = s.customer_id
     LEFT JOIN purchase_header p ON pr.document_type = 'PURCHASE' AND p.id = pr.document_id
     LEFT JOIN party_master ps ON ps.id = p.supplier_id
     WHERE UPPER(COALESCE(pr.document_type, '')) = ?
       AND COALESCE(pr.payment_type, '') <> 'BANK_RECONCILIATION'
       AND NOT EXISTS (
           SELECT 1 FROM bank_reconciliation_allocation a
           WHERE a.payment_record_id = pr.id AND a.reversed_at IS NULL
       )
     ORDER BY pr.id DESC
     LIMIT 300
     """, up(docType));
  }catch(Exception ignored){return List.of();}
 }
 private void addRecordedPaymentCandidates(List<BankReconciliationDtos.CandidateDto> out,BankStatementTransactionEntity t,String docType,double tolerance,Map<Integer,List<String>> aliasMap,List<Map<String,Object>> preloaded){
  List<Map<String,Object>> rows=preloaded!=null?preloaded:loadUnlinkedRecordedPayments(docType);
  if(rows==null||rows.isEmpty())return;
  double amount=bankAmount(t);
  String hay=up(safe(t.getOriginalDescription())+" "+safe(t.getOriginalReference()));
  String normHay=" "+hay.replaceAll("[^A-Z0-9 ]"," ").replaceAll("\\s+"," ").trim()+" ";
  String compactHay=hay.replaceAll("[^A-Z0-9]","");
  LocalDate td=date(t.getTransactionDate());
  for(Map<String,Object> r:rows){
   if(r.get("id")==null||!(r.get("amount") instanceof Number amtNum))continue;
   int prId=((Number)r.get("id")).intValue();
   double prAmt=amtNum.doubleValue();
   double diff=money(prAmt-amount);
   boolean exactAmt=Math.abs(diff)<=0.01;
   boolean roundAmt=!exactAmt&&tolerance>0&&Math.abs(diff)<=tolerance+0.0001;
   if(!exactAmt&&!roundAmt)continue;
   String payRef=Objects.toString(r.get("payment_reference"),"").trim();
   String extRef=Objects.toString(r.get("reference_no"),"").trim();
   String invNo=Objects.toString(r.get("invoice_no"),"").trim();
   String party=Objects.toString(r.get("party_name"),"").trim();
   Integer pid=r.get("party_id") instanceof Number n?n.intValue():null;
   String phone=Objects.toString(r.get("party_phone"),"");
   String gstin=Objects.toString(r.get("party_gstin"),"");
   String pDate=Objects.toString(r.get("payment_date"),"");
   double score=exactAmt?40.0:34.0;
   List<String> reasons=new ArrayList<>();
   reasons.add("Pre-Recorded "+("SALE".equals(docType)?"Receipt":"Payment")+" "+(payRef.isBlank()?"#"+prId:payRef));
   if(!invNo.isBlank())reasons.add("For "+invNo);
   if(refMatches(extRef,normHay,compactHay)||refMatches(payRef,normHay,compactHay)){
    score+=38.0;reasons.add("Ref/UTR matched");
   }else if(refMatches(invNo,normHay,compactHay)){
    score+=28.0;reasons.add("Invoice #"+invNo+" matched");
   }
   score+=identitySignalScore(normHay,hay,pid,party,phone,gstin,aliasMap,reasons);
   LocalDate vd=date(pDate);
   if(td!=null&&vd!=null){
    long absDays=Math.abs(ChronoUnit.DAYS.between(vd,td));
    if(absDays<=3)score+=14.0;
    else if(absDays<=7)score+=10.0;
    else if(absDays<=21)score+=5.0;
   }
   double finalScore=Math.max(0.0,Math.min(100.0,Math.round(score)));
   if(finalScore<55.0)continue;
   String docLabel=payRef.isBlank()?("PAY #"+prId):(payRef+(invNo.isBlank()?"":" • "+invNo));
   out.add(new BankReconciliationDtos.CandidateDto("RECORDED_PAYMENT",prId,docLabel,party,pDate,prAmt,0d,prAmt,finalScore,String.join(" • ",reasons),prAmt,0d,"",diff,List.of()));
  }
 }
 private static boolean refMatches(String ref,String normHay,String compactHay){
  if(blank(ref))return false;
  String compactRef=up(ref).replaceAll("[^A-Z0-9]","");
  String normRef=up(ref).replaceAll("[^A-Z0-9 ]"," ").replaceAll("\\s+"," ").trim();
  return (compactRef.length()>=4&&compactHay.contains(compactRef))||(!normRef.isBlank()&&normRef.length()>=4&&normHay.contains(" "+normRef+" "));
 }
 private void addManualPaymentCandidates(List<BankReconciliationDtos.CandidateDto> out,BankStatementTransactionEntity t,String category){
  double amount=bankAmount(t);
  try{
   for(Map<String,Object> r:jdbc.queryForList("SELECT id,voucher_no,voucher_date,notes,amount,category FROM finance_register WHERE COALESCE(reconciled,0)=0 AND ABS(amount-?)<=0.01 AND category=? ORDER BY id DESC LIMIT 10",amount,category)){
    int fid=((Number)r.get("id")).intValue();
    double amt=((Number)r.get("amount")).doubleValue();
    String vNo=Objects.toString(r.get("voucher_no"),"");
    String vDate=Objects.toString(r.get("voucher_date"),"");
    String notes=Objects.toString(r.get("notes"),"Manual Payment");
    LocalDate td=date(t.getTransactionDate()),vd=date(vDate);
    double score=75;
    if(td!=null&&vd!=null&&Math.abs(ChronoUnit.DAYS.between(vd,td))<=7)score+=15;
    out.add(new BankReconciliationDtos.CandidateDto("MANUAL_PAYMENT",fid,vNo,notes,vDate,amt,0d,amt,Math.min(100,score),"Existing un-reconciled "+category+" voucher #"+vNo+" dated "+vDate));
   }
  }catch(Exception ignored){}
 }
 private void addReturnCandidates(List<BankReconciliationDtos.CandidateDto> out,BankStatementTransactionEntity t,String dbType,String candidateType,double tolerance,Map<Integer,List<String>> aliasMap){
  addReturnCandidatesFromRows(out,t,candidateType,loadOpenReturnRows(dbType),tolerance,aliasMap);
 }
 private List<Map<String,Object>> loadOpenReturnRows(String dbType){
  return jdbc.queryForList("SELECT MIN(r.id) id,r.return_no no,MAX(r.return_date) date,MAX(r.party_id) party_id,MAX(COALESCE(pm.name,'')) party,MAX(COALESCE(pm.phone,'')) phone,MAX(COALESCE(pm.gstin,'')) gstin,SUM(r.amount) total,COALESCE((SELECT SUM(rr.amount+COALESCE(rr.rounding_adjustment,0)) FROM return_refund rr WHERE rr.return_no=r.return_no),0) refunded FROM return_register r LEFT JOIN party_master pm ON pm.id=r.party_id WHERE r.return_type=? AND UPPER(COALESCE(r.status,''))='APPROVED' GROUP BY r.return_no HAVING SUM(r.amount)-COALESCE((SELECT SUM(rr.amount+COALESCE(rr.rounding_adjustment,0)) FROM return_refund rr WHERE rr.return_no=r.return_no),0)>.009 ORDER BY MAX(CASE WHEN COALESCE(r.return_date,'') ~ '^\\d{4}-\\d{2}-\\d{2}$' THEN TO_DATE(r.return_date,'YYYY-MM-DD') WHEN COALESCE(r.return_date,'') ~ '^\\d{2}/\\d{2}/\\d{4}$' THEN TO_DATE(r.return_date,'DD/MM/YYYY') WHEN COALESCE(r.return_date,'') ~ '^\\d{2}-\\d{2}-\\d{4}$' THEN TO_DATE(r.return_date,'DD-MM-YYYY') ELSE NULL END) DESC LIMIT 200",dbType);
 }
 private void addReturnCandidatesFromRows(List<BankReconciliationDtos.CandidateDto> out,BankStatementTransactionEntity t,String candidateType,List<Map<String,Object>> rows,double tolerance,Map<Integer,List<String>> aliasMap){
  for(Map<String,Object> r:rows){
   int rid=((Number)r.get("id")).intValue();
   Integer pid=r.get("party_id") instanceof Number n?n.intValue():null;
   double total=((Number)r.get("total")).doubleValue(),paid=((Number)r.get("refunded")).doubleValue();
   out.add(candidate(t,candidateType,rid,Objects.toString(r.get("no"),""),Objects.toString(r.get("party"),""),pid,Objects.toString(r.get("phone"),""),Objects.toString(r.get("gstin"),""),Objects.toString(r.get("date"),""),total,total,paid,Math.max(0,total-paid),tolerance,aliasMap));
  }
 }
 private Map<String,Object> returnTarget(int id,String candidateType){String dbType="SALES_RETURN".equals(candidateType)?"SALES RETURN":"PURCHASE RETURN";String returnNo=jdbc.queryForObject("SELECT return_no FROM return_register WHERE id=?",String.class,id);if(returnNo==null||returnNo.isBlank())throw new IllegalArgumentException("Return refund target was not found.");List<String> states=jdbc.query("SELECT COALESCE(status,'PENDING') FROM return_register WHERE return_no=? AND return_type=? FOR UPDATE",(r,i)->r.getString(1),returnNo,dbType);if(states.isEmpty())throw new IllegalArgumentException("Return refund target was not found.");if(states.stream().anyMatch(x->!"APPROVED".equals(safe(x).trim().toUpperCase(java.util.Locale.ROOT))))throw new IllegalStateException("Return refund reconciliation is available only after Admin approves the Return.");List<Map<String,Object>> rows=jdbc.queryForList("SELECT MIN(r.id) id,r.return_no no,MAX(r.party_id) party_id,MAX(COALESCE(pm.name,'')) party,SUM(r.amount) total,COALESCE((SELECT SUM(rr.amount+COALESCE(rr.rounding_adjustment,0)) FROM return_refund rr WHERE rr.return_no=r.return_no),0) refunded FROM return_register r LEFT JOIN party_master pm ON pm.id=r.party_id WHERE r.return_no=? AND r.return_type=? GROUP BY r.return_no",returnNo,dbType);if(rows.isEmpty())throw new IllegalArgumentException("Return refund target was not found.");return rows.getFirst();}
 private static final Set<String> GENERIC_PARTY_TOKENS=Set.of(
   "PRIVATE","LIMITED","INDUSTRIES","INDUSTRY","ENTERPRISES","ENTERPRISE","COMPANY","CORPORATION","CORP",
   "PVT","LTD","LLP","TRADERS","TRADING","INDIA","GUJARAT","AHMEDABAD","SURAT","MUMBAI","DELHI",
   "SHREE","SHRI","TECH","SYSTEMS","SOLUTIONS","ENGINEERING","ENGINEERS","PLASTICS","HARDWARE",
   "SALES","MARKETING","AGENCY","BROTHERS","SONS","BANK","CASH","HDFC","ICICI","SBI","AXIS","KOTAK",
   "UPI","IMPS","NEFT","RTGS","NACH","POS"
 );
 private static final Set<String> STOP_WORDS=Set.of("TO","BY","IN","ON","OF","AT","MR","MS","DR","NO","A","AN","THE","IS","OR","AND","FOR","CR");
 private BankReconciliationDtos.CandidateDto candidate(
   BankStatementTransactionEntity t,String type,Integer id,String no,String party,
   Integer partyId,String partyPhone,String partyGstin,
   String docDate,double total,double taxable,double paid,double outstanding,
   double tolerance,Map<Integer,List<String>> aliasMap,String externalDocRef){
  double score=0,amount=bankAmount(t);
  double roundAdj=0d,tdsAmt=0d;String tdsLabel="";
  List<String> reasons=new ArrayList<>();
  String hay=up(safe(t.getOriginalDescription())+" "+safe(t.getOriginalReference()));
  String normHay=" "+hay.replaceAll("[^A-Z0-9 ]"," ").replaceAll("\\s+"," ").trim()+" ";
  String compactHay=up(hay).replaceAll("[^A-Z0-9]","");

  boolean hasDocMatch=false;
  if(!blank(no)){
   String normNo=up(no).replaceAll("[^A-Z0-9 ]"," ").replaceAll("\\s+"," ").trim();
   String compactNo=up(no).replaceAll("[^A-Z0-9]","");
   if((!compactNo.isBlank()&&compactNo.length()>=4&&compactHay.contains(compactNo))
       ||(!normNo.isBlank()&&normHay.contains(" "+normNo+" "))){
    score+=40;
    hasDocMatch=true;
    reasons.add("Invoice #"+no+" matched");
   }
  }
  if(!hasDocMatch&&!blank(externalDocRef)){
   for(String ext:externalDocRef.trim().split("\\s+")){
    if(refMatches(ext,normHay,compactHay)){
     score+=40;
     hasDocMatch=true;
     reasons.add("Ref #"+ext+" matched");
     break;
    }
   }
  }

  double idScore=identitySignalScore(normHay,hay,partyId,party,partyPhone,partyGstin,aliasMap,reasons);
  score+=idScore;

  double diff=money(outstanding-amount);
  boolean exactAmt=Math.abs(diff)<=0.01;
  boolean roundOffAmt=!exactAmt&&tolerance>0&&Math.abs(diff)<=tolerance+0.0001;
  boolean excessAmt=amount>outstanding+tolerance+0.01;
  if(exactAmt){
   score+=40;
   reasons.add("Exact Amount Match");
  }else if(roundOffAmt){
   score+=35;
   roundAdj=diff;
   reasons.add("Within Round-off "+signedMoney(diff));
  }else if(!excessAmt&&outstanding>amount&&isStatutoryTds(total,taxable,diff)){
   score+=32;
   tdsAmt=diff;
   tdsLabel=statutoryTdsLabel(total,taxable,diff);
   reasons.add(tdsLabel+" ₹"+fmt(diff));
  }else if(excessAmt){
   reasons.add("Exceeds Outstanding");
  }else if(amount<outstanding&&(idScore>0||hasDocMatch)){
   score+=12;
   reasons.add("Partial Settlement");
  }

  LocalDate td=date(t.getTransactionDate()),dd=date(docDate);
  if(td!=null&&dd!=null){
   long signedDays=ChronoUnit.DAYS.between(dd,td);
   long absDays=Math.abs(signedDays);
   if(signedDays<-2){
    score-=8;
    reasons.add("Doc dated after bank tx");
   }else if(absDays<=7){
    score+=10;
    reasons.add("Date within "+absDays+"d");
   }else if(absDays<=30){
    score+=6;
    reasons.add("Date within "+absDays+"d");
   }else if(absDays<=90){
    score+=2;
   }else if(absDays>180){
    score-=5;
   }
  }
  if(excessAmt){
   score=Math.min(35.0,Math.max(0.0,score));
  }
  double finalScore=Math.max(0.0,Math.min(100.0,Math.round(score)));
  String matchReason=String.join(" • ",reasons);
  return new BankReconciliationDtos.CandidateDto(type,id,no,party,docDate,total,paid,outstanding,finalScore,matchReason,taxable,tdsAmt,tdsLabel,roundAdj,List.of());
 }
 private BankReconciliationDtos.CandidateDto candidate(
   BankStatementTransactionEntity t,String type,Integer id,String no,String party,
   Integer partyId,String partyPhone,String partyGstin,
   String docDate,double total,double taxable,double paid,double outstanding,
   double tolerance,Map<Integer,List<String>> aliasMap){
  return candidate(t,type,id,no,party,partyId,partyPhone,partyGstin,docDate,total,taxable,paid,outstanding,tolerance,aliasMap,"");
 }
 private static double identitySignalScore(
   String normHay,String rawHay,
   Integer partyId,String party,String phone,String gstin,
   Map<Integer,List<String>> aliasMap,List<String> reasons){
  if(partyId!=null&&aliasMap!=null){
   for(String alias:aliasMap.getOrDefault(partyId,List.of())){
    if(alias.length()>=3&&rawHay.contains(alias.toUpperCase(Locale.ROOT))){
     reasons.add("Learned VPA/Alias matched");
     return 30;
    }
   }
  }
  if(!blank(gstin)&&gstin.trim().length()>=10&&rawHay.contains(gstin.trim().toUpperCase(Locale.ROOT))){
   reasons.add("Party GSTIN matched");
   return 28;
  }
  String cleanGstin=safe(gstin).trim().toUpperCase(Locale.ROOT);
  if(cleanGstin.length()==15){
   String pan=cleanGstin.substring(2,12);
   if(rawHay.contains(pan)){
    reasons.add("Party PAN matched");
    return 24;
   }
  }
  String phoneDigits=safe(phone).replaceAll("[^0-9]","");
  if(phoneDigits.length()>=10){
   String last10=phoneDigits.substring(phoneDigits.length()-10);
   if(rawHay.replaceAll("[^0-9]","").contains(last10)){
    reasons.add("Party Phone matched");
    return 28;
   }
  }
  if(blank(party))return 0;
  String[] tokens=up(party).replaceAll("[^A-Z0-9 ]"," ").split("\\s+");
  int distinctCount=0,matchedCount=0;
  String firstMatched="";
  List<String> distinctivePartyTokens=new ArrayList<>();
  for(String tok:tokens){
   if(tok.length()<3||STOP_WORDS.contains(tok)||GENERIC_PARTY_TOKENS.contains(tok))continue;
   distinctCount++;
   distinctivePartyTokens.add(tok);
   if(normHay.contains(" "+tok+" ")){
    matchedCount++;
    if(firstMatched.isBlank())firstMatched=tok;
   }
  }
  if(matchedCount>0){
   if(matchedCount==1&&firstMatched.length()<4&&distinctCount>1)return 0;
   reasons.add("Party '"+firstMatched+"' matched");
   double ratio=distinctCount>0?(double)matchedCount/distinctCount:1.0;
   return matchedCount>=2||ratio>=0.99?25:18;
  }
  String[] hayTokens=normHay.trim().split("\\s+");
  for(String pTok:distinctivePartyTokens){
   if(pTok.length()<5)continue;
   for(String hTok:hayTokens){
    if(hTok.length()<4||STOP_WORDS.contains(hTok)||GENERIC_PARTY_TOKENS.contains(hTok))continue;
    if(pTok.startsWith(hTok)||hTok.startsWith(pTok.substring(0,5))){
     reasons.add("Party prefix '"+hTok+"' matched");
     return 16;
    }
   }
  }
  return 0;
 }
 private void refreshBatch(Long id){var b=imports.findById(id).orElseThrow();var m=metrics(id);b.setReconciledCount(m.reconciled());b.setReconciliationPercent(m.reconciledPercent());b.setStatus(m.batchStatus());imports.save(b);}
 private BankReconciliationDtos.BatchDto batch(BankStatementImportEntity b){return new BankReconciliationDtos.BatchDto(b.getId(),b.getStatementReference(),b.getBankName(),b.getBankAccount(),b.getAccountHolder(),b.getStatementFrom(),b.getStatementTo(),b.getCurrency(),nv(b.getTransactionCount()),n(b.getTotalDebit()),n(b.getTotalCredit()),nv(b.getReconciledCount()),n(b.getReconciliationPercent()),b.getStatus(),b.getSourceFileName(),b.getImportedAt());}
 private List<BankReconciliationDtos.TransactionDto> mapTransactions(List<BankStatementTransactionEntity> rows){
  if(rows==null||rows.isEmpty())return List.of();Set<Long> ids=new HashSet<>();for(var t:rows)ids.add(t.getId());Map<Long,List<BankReconciliationAllocationEntity>> byTx=new HashMap<>();Set<Integer> financeIds=new HashSet<>(),paymentIds=new HashSet<>();for(var a:allocations.findByStatementTransactionIdInAndReversedAtIsNull(ids)){byTx.computeIfAbsent(a.getStatementTransactionId(),ignored->new ArrayList<>()).add(a);if(a.getFinanceEntryId()!=null)financeIds.add(a.getFinanceEntryId());if(a.getPaymentRecordId()!=null&&Set.of("SALE","PURCHASE").contains(up(a.getTargetType())))paymentIds.add(a.getPaymentRecordId());}
  Map<Integer,String> financeVouchers=new HashMap<>();if(!financeIds.isEmpty())for(var f:finance.findAllById(financeIds))if(f.getVoucherNo()!=null)financeVouchers.put(f.getId(),f.getVoucherNo());
  Map<Integer,String> paymentRefs=new HashMap<>();if(!paymentIds.isEmpty())for(var p:payments.findAllById(paymentIds)){String prf=safe(p.getPaymentReference()).trim();if(!prf.isBlank())paymentRefs.put(p.getId(),prf);}
  Set<Integer> saleIds=new HashSet<>(),purchaseIds=new HashSet<>(),purchaseReconIds=new HashSet<>(),returnIds=new HashSet<>();for(var t:rows){for(var a:byTx.getOrDefault(t.getId(),List.of())){String ty=up(a.getTargetType());if("SALE".equals(ty))saleIds.add(a.getTargetId());else if("PURCHASE".equals(ty))purchaseIds.add(a.getTargetId());else if("PURCHASE_RECON".equals(ty))purchaseReconIds.add(a.getTargetId());else if(Set.of("SALES_RETURN","PURCHASE_RETURN").contains(ty))returnIds.add(a.getTargetId());}if(t.getSuggestedMatchId()!=null){String ty=up(t.getSuggestedMatchType());if("SALE".equals(ty))saleIds.add(t.getSuggestedMatchId());else if("PURCHASE".equals(ty))purchaseIds.add(t.getSuggestedMatchId());else if("PURCHASE_RECON".equals(ty))purchaseReconIds.add(t.getSuggestedMatchId());else if(Set.of("SALES_RETURN","PURCHASE_RETURN").contains(ty))returnIds.add(t.getSuggestedMatchId());}}
  Map<Integer,String> saleNos=new HashMap<>(),purchaseNos=new HashMap<>(),purchaseReconNos=new HashMap<>(),returnNos=new HashMap<>();if(!saleIds.isEmpty())for(var x:sales.findAllById(saleIds))saleNos.put(x.getId(),x.getInvoiceNo());if(!purchaseIds.isEmpty())for(var x:purchases.findAllById(purchaseIds))purchaseNos.put(x.getId(),x.getInvoiceNo());if(!purchaseReconIds.isEmpty())for(var x:purchaseRecons.findAllById(purchaseReconIds))purchaseReconNos.put(x.getId(),x.getReconRef());if(!returnIds.isEmpty())for(Map<String,Object> x:jdbc.queryForList("SELECT id,return_no FROM return_register WHERE id IN ("+returnIds.stream().map(x->"?").reduce((a,b)->a+","+b).orElse("0")+")",returnIds.toArray()))returnNos.put(((Number)x.get("id")).intValue(),Objects.toString(x.get("return_no"),""));
  List<BankReconciliationDtos.TransactionDto> out=new ArrayList<>(rows.size());for(var t:rows)out.add(transaction(t,byTx.getOrDefault(t.getId(),List.of()),financeVouchers,paymentRefs,saleNos,purchaseNos,purchaseReconNos,returnNos));return out;
 }
 private BankReconciliationDtos.TransactionDto transaction(BankStatementTransactionEntity t,List<BankReconciliationAllocationEntity> a,Map<Integer,String> financeVouchers,Map<Integer,String> paymentRefs,Map<Integer,String> saleNos,Map<Integer,String> purchaseNos,Map<Integer,String> purchaseReconNos,Map<Integer,String> returnNos){
  String link="";Integer financeId=null,targetId=null;String targetType=null,docNo=null;
  List<BankReconciliationDtos.AllocationDto> linked=new ArrayList<>();
  for(var allocation:a){String type=up(allocation.getTargetType());Integer id=allocation.getTargetId();String no=documentNo(type,id,saleNos,purchaseNos,purchaseReconNos,returnNos);String prf=allocation.getPaymentRecordId()==null?null:paymentRefs.get(allocation.getPaymentRecordId());linked.add(new BankReconciliationDtos.AllocationDto(allocation.getId(),type,id,no,n(allocation.getAllocatedAmount()),n(allocation.getRoundingAdjustment()),allocation.getPaymentRecordId(),prf,allocation.getFinanceEntryId()));}
  if(!a.isEmpty()){
   var first=a.getFirst();financeId=first.getFinanceEntryId();targetType=up(first.getTargetType());targetId=first.getTargetId();docNo=documentNo(targetType,targetId,saleNos,purchaseNos,purchaseReconNos,returnNos);
   String fVoucher=financeId==null?null:financeVouchers.get(financeId);
   String firstPrf=first.getPaymentRecordId()==null?null:paymentRefs.get(first.getPaymentRecordId());
   if("EXPENSE".equals(targetType))link=fVoucher!=null?fVoucher:"View Expense";
   else if("BANK_ENTRY".equals(targetType))link=fVoucher!=null?fVoucher:"View Bank Entry";
   else if(a.size()>1)link=a.size()+" linked transactions";
   else link=(firstPrf!=null&&!firstPrf.isBlank()&&docNo!=null)?(docNo+" • "+firstPrf):docNo;
  }else if(t.getSuggestedMatchId()!=null){targetType=up(t.getSuggestedMatchType());targetId=t.getSuggestedMatchId();docNo=documentNo(targetType,targetId,saleNos,purchaseNos,purchaseReconNos,returnNos);link="Suggested: "+(docNo==null?targetType+" #"+targetId:docNo);}
  String financeVoucherNo=financeId==null?null:financeVouchers.get(financeId);
  String lineRef=!blank(t.getTransactionReference())?t.getTransactionReference():(t.getImportBatch()!=null&&!blank(t.getImportBatch().getStatementReference())?t.getImportBatch().getStatementReference()+"-"+String.format(Locale.ROOT,"%03d",nv(t.getSourceRowNumber())):"");
  return new BankReconciliationDtos.TransactionDto(t.getId(),t.getImportBatch().getId(),lineRef,t.getSourceRowNumber(),t.getTransactionTimestamp(),t.getTransactionDate(),t.getValueDate(),t.getOriginalDescription(),t.getOriginalReference(),n(t.getDebitAmount()),n(t.getCreditAmount()),n(t.getBalance()),t.getStatus(),t.getSuggestedMatchType(),t.getSuggestedMatchId(),t.getSuggestedConfidence(),link,financeId,financeVoucherNo,targetType,targetId,docNo,List.copyOf(linked),t.getRowVersion());
 }
 private String documentNo(String type,Integer id,Map<Integer,String> saleNos,Map<Integer,String> purchaseNos,Map<Integer,String> purchaseReconNos,Map<Integer,String> returnNos){if(id==null)return null;if("SALE".equals(type))return saleNos.getOrDefault(id,"Sale #"+id);if("PURCHASE".equals(type))return purchaseNos.getOrDefault(id,"Purchase #"+id);if("PURCHASE_RECON".equals(type))return purchaseReconNos.getOrDefault(id,"Purchase Recon #"+id);if(Set.of("SALES_RETURN","PURCHASE_RETURN").contains(type))return returnNos.getOrDefault(id,"Return #"+id);if("RECORDED_PAYMENT".equals(type)){var pr=payments.findById(id).orElse(null);return pr!=null&&!blank(pr.getPaymentReference())?pr.getPaymentReference():"Payment #"+id;}if("EXPENSE".equals(type)){var f=finance.findById(id).orElse(null);return f!=null&&f.getVoucherNo()!=null?f.getVoucherNo():"Expense #"+id;}if("BANK_ENTRY".equals(type)){var f=finance.findById(id).orElse(null);return f!=null&&f.getVoucherNo()!=null?f.getVoucherNo():"Bank Entry #"+id;}return type+" #"+id;}

 @Transactional(readOnly=true) public BankReconciliationDtos.TransactionDto transaction(Long id){return transaction(reqTx(id));}
 private BankReconciliationDtos.TransactionDto transaction(BankStatementTransactionEntity t){return mapTransactions(List.of(t)).getFirst();}
 private void audit(BankStatementTransactionEntity t,String event,String detail,String old,String next,String user){BankReconciliationAuditEntity a=new BankReconciliationAuditEntity();a.setStatementTransactionId(t.getId());a.setEventType(event);a.setEventDetail(detail);a.setPreviousStatus(old);a.setNewStatus(next);a.setPerformedBy(org.example.server.security.CurrentUser.require().username());audits.save(a);}
 private BankStatementTransactionEntity reqTx(Long id){return txs.findById(id).orElseThrow(()->new IllegalArgumentException("Bank statement transaction not found."));} private BankStatementTransactionEntity reqTxForUpdate(Long id){return txs.findByIdForUpdate(id).orElseThrow(()->new IllegalArgumentException("Bank statement transaction not found."));} private void ensureProcessable(BankStatementTransactionEntity t){if(RESOLVED.contains(up(t.getStatus())))throw new IllegalStateException("This bank transaction is already resolved. Use Unmatch / Reverse first.");}
 private static void validateStatementRow(BankReconciliationDtos.ImportRow row){
  if(row==null)throw new IllegalArgumentException("Bank statement row is required.");
  double debit=row.debit(),credit=row.credit(),balance=row.balance();
  if(!Double.isFinite(debit)||!Double.isFinite(credit)||!Double.isFinite(balance))throw new IllegalArgumentException("Bank statement monetary values must be finite numbers.");
  if(debit<0||credit<0)throw new IllegalArgumentException("Bank statement debit and credit values cannot be negative.");
  boolean hasDebit=money(debit)>0d,hasCredit=money(credit)>0d;
  if(hasDebit==hasCredit)throw new IllegalArgumentException("Each bank statement row must contain exactly one positive Debit or Credit amount.");
  }

  @Transactional(readOnly=true)
  public BankReconciliationDtos.DocumentLinkageDto getLinkage(String type, Integer id, String docNo, Long transactionId, Integer financeId) {
   List<BankReconciliationAllocationEntity> allocs = new ArrayList<>();
   String activeType = up(type);
   String activeRef = safe(docNo).trim();
   Integer resolvedTargetId = id;

   if (transactionId != null) {
       allocs.addAll(allocations.findByStatementTransactionIdInAndReversedAtIsNull(Set.of(transactionId)));
       activeType = "STATEMENT_TX";
       activeRef = String.valueOf(transactionId);
   } else if (financeId != null) {
       allocs.addAll(allocations.findAll().stream().filter(a -> a.getReversedAt() == null && Objects.equals(a.getFinanceEntryId(), financeId)).toList());
       activeType = "FINANCE";
       activeRef = String.valueOf(financeId);
   } else if (!activeType.isBlank()) {
       Integer targetId = id;
       final String lookupRef = activeRef;
       final String lookupType = activeType;
       if (targetId == null && !lookupRef.isBlank()) {
           if ("SALE".equals(lookupType)) {
               var s = sales.findByInvoiceNo(lookupRef).orElse(null);
               if (s != null) targetId = s.getId();
           } else if ("PURCHASE".equals(lookupType)) {
               var p = purchases.findByInvoiceNo(lookupRef).orElse(null);
               if (p != null) targetId = p.getId();
           } else if ("PURCHASE_RECON".equals(lookupType)) {
               var r = purchaseRecons.findAll().stream().filter(x -> lookupRef.equalsIgnoreCase(x.getReconRef())).findFirst().orElse(null);
               if (r != null) targetId = r.getId();
           } else if ("EXPENSE".equals(lookupType) || "BANK_ENTRY".equals(lookupType)) {
               var f = finance.findAll().stream().filter(x -> lookupRef.equalsIgnoreCase(x.getVoucherNo())).findFirst().orElse(null);
               if (f != null) targetId = f.getId();
           }
       }
       resolvedTargetId = targetId;
       if (targetId != null) {
           final Integer finalTgtId = targetId;
           allocs.addAll(allocations.findAll().stream().filter(a -> a.getReversedAt() == null && lookupType.equalsIgnoreCase(a.getTargetType()) && Objects.equals(a.getTargetId(), finalTgtId)).toList());
       }
   }

   Set<Long> txIds = new HashSet<>();
   Set<Integer> finIds = new HashSet<>();
   Set<Integer> payIds = new HashSet<>();
   for (var a : allocs) {
       if (a.getStatementTransactionId() != null) txIds.add(a.getStatementTransactionId());
       if (a.getFinanceEntryId() != null) finIds.add(a.getFinanceEntryId());
       if (a.getPaymentRecordId() != null && Set.of("SALE", "PURCHASE").contains(up(a.getTargetType()))) payIds.add(a.getPaymentRecordId());
   }

   Map<Long, BankStatementTransactionEntity> txMap = new HashMap<>();
   if (!txIds.isEmpty()) for (var tx : txs.findAllById(txIds)) txMap.put(tx.getId(), tx);

   Map<Integer, FinanceRegisterEntity> finMap = new HashMap<>();
   if (!finIds.isEmpty()) for (var f : finance.findAllById(finIds)) finMap.put(f.getId(), f);

   Map<Integer, PaymentRecordEntity> payMap = new HashMap<>();
   if (!payIds.isEmpty()) for (var p : payments.findAllById(payIds)) payMap.put(p.getId(), p);

   List<BankReconciliationDtos.LinkageItemDto> items = new ArrayList<>();
   Set<Integer> linkedPaymentIds = new HashSet<>();
   for (var a : allocs) {
       var tx = txMap.get(a.getStatementTransactionId());
       var f = a.getFinanceEntryId() != null ? finMap.get(a.getFinanceEntryId()) : null;
       var p = a.getPaymentRecordId() != null ? payMap.get(a.getPaymentRecordId()) : null;
       if (p != null) linkedPaymentIds.add(p.getId());

       String stmtRef = tx != null ? (!blank(tx.getTransactionReference()) ? tx.getTransactionReference() : (tx.getImportBatch() != null ? tx.getImportBatch().getStatementReference() : null)) : null;
       String bName = tx != null && tx.getImportBatch() != null ? tx.getImportBatch().getBankName() : null;
       String bAcct = tx != null && tx.getImportBatch() != null ? tx.getImportBatch().getBankAccount() : null;
       String txDate = tx != null ? tx.getTransactionDate() : null;
       String origRef = tx != null ? tx.getOriginalReference() : null;
       double dr = tx != null ? n(tx.getDebitAmount()) : 0d;
       double cr = tx != null ? n(tx.getCreditAmount()) : 0d;

       String fVoucher = f != null ? f.getVoucherNo() : null;
       String fType = f != null ? f.getVoucherType() : null;
       Integer pId = p != null ? p.getId() : null;
       String pRef = p != null ? p.getPaymentReference() : null;
       String settledBy = p != null && !blank(p.getCreatedBy()) ? p.getCreatedBy() : a.getCreatedBy();

       String docNoResolved = documentNo(a.getTargetType(), a.getTargetId(), Map.of(), Map.of(), Map.of(), Map.of());
       if (docNoResolved != null && docNoResolved.contains("#")) {
           if ("SALE".equalsIgnoreCase(a.getTargetType())) {
               var s = sales.findById(a.getTargetId()).orElse(null);
               if (s != null) docNoResolved = s.getInvoiceNo();
           } else if ("PURCHASE".equalsIgnoreCase(a.getTargetType())) {
               var pur = purchases.findById(a.getTargetId()).orElse(null);
               if (pur != null) docNoResolved = pur.getInvoiceNo();
           } else if ("PURCHASE_RECON".equalsIgnoreCase(a.getTargetType())) {
               var r = purchaseRecons.findById(a.getTargetId()).orElse(null);
               if (r != null) docNoResolved = r.getReconRef();
           }
       }

       items.add(new BankReconciliationDtos.LinkageItemDto(
           a.getStatementTransactionId(),
           stmtRef,
           bName,
           bAcct,
           txDate,
           origRef,
           dr,
           cr,
           a.getFinanceEntryId(),
           fVoucher,
           fType,
           a.getTargetType(),
           a.getTargetId(),
           docNoResolved,
           pId,
           pRef,
           settledBy,
           n(a.getAllocatedAmount()),
           tx != null ? tx.getStatus() : "LINKED"
       ));
   }

   if (resolvedTargetId != null && Set.of("SALE", "PURCHASE").contains(activeType)) {
       String docNoResolved = activeRef;
       if (docNoResolved.isBlank()) {
           if ("SALE".equals(activeType)) {
               var s = sales.findById(resolvedTargetId).orElse(null);
               if (s != null) docNoResolved = safe(s.getInvoiceNo());
           } else {
               var pur = purchases.findById(resolvedTargetId).orElse(null);
               if (pur != null) docNoResolved = safe(pur.getInvoiceNo());
           }
       }
       final String finalDocNo = docNoResolved;
       final Integer finalTargetId = resolvedTargetId; final String finalType = activeType;
       List<PaymentRecordEntity> manualPayments = payments.findAll().stream()
           .filter(p -> finalType.equalsIgnoreCase(p.getDocumentType())
               && Objects.equals(p.getDocumentId(), finalTargetId)
               && !linkedPaymentIds.contains(p.getId()))
           .sorted(Comparator.comparing(PaymentRecordEntity::getId).reversed())
           .toList();
       for (var mp : manualPayments) {
           String glVoucher = null;
           Integer glId = null;
           try {
               String refType = "SALE".equals(activeType) ? "PAYMENT_RECEIPT" : "PURCHASE_PAYMENT";
               var glRows = jdbc.queryForList(
                   "SELECT id, entry_number FROM journal_entry WHERE (reference_no = ? OR (reference_type = ? AND reference_id = ?)) AND status <> 'CANCELLED' ORDER BY id DESC LIMIT 1",
                   mp.getPaymentReference(), refType, mp.getId()
               );
               if (!glRows.isEmpty()) {
                   glVoucher = java.util.Objects.toString(glRows.getFirst().get("entry_number"), null);
                   glId = ((Number) glRows.getFirst().get("id")).intValue();
               }
           } catch (Exception ignored) {}

           items.add(new BankReconciliationDtos.LinkageItemDto(
               null,
               null,
               mp.getPaymentMode(),
               mp.getReceivedFrom(),
               mp.getPaymentDate(),
               mp.getReferenceNo(),
               "PURCHASE".equals(activeType) ? n(mp.getAmount()) : 0d,
               "SALE".equals(activeType) ? n(mp.getAmount()) : 0d,
               glId,
               glVoucher,
               "JOURNAL_ENTRY",
               activeType,
               finalTargetId,
               finalDocNo,
               mp.getId(),
               mp.getPaymentReference(),
               blank(mp.getCreatedBy()) ? "System" : mp.getCreatedBy(),
               n(mp.getAmount()),
               "MANUAL_PAYMENT"
           ));
       }
   }

   return new BankReconciliationDtos.DocumentLinkageDto(!items.isEmpty(), activeType, activeRef, items);
 }

 private double roundingTolerance(){try{String value=jdbc.queryForObject("SELECT setting_value FROM application_setting WHERE setting_key='payment.bankMatchRoundingTolerance'",String.class);double parsed=Double.parseDouble(value==null?"1.00":value.trim());return Math.max(0d,Math.min(5d,money(parsed)));}catch(Exception ignored){return 1d;}}
 private static double roundingAdjustment(double outstanding,double amount,double tolerance){
  double difference=money(outstanding-amount);
  if(Math.abs(difference)<=.0001)return 0d;
  double absDiff=Math.abs(difference);
  return tolerance>0&&absDiff<=tolerance+.0001?difference:0d;
 }
 private static void validateAllocation(double amount,double outstanding,double roundOff,String documentNo){if(amount>outstanding+.01&&roundOff==0d)throw new IllegalArgumentException("Allocation exceeds outstanding balance for "+documentNo);double settled=money(amount+roundOff);if(settled>outstanding+.01)throw new IllegalArgumentException("Settlement exceeds outstanding balance for "+documentNo);}
 private static String reconciliationNote(BankStatementTransactionEntity t,double roundOff){return "Reconciled from bank statement transaction #"+t.getId()+(Math.abs(roundOff)>.0001?" • Round-off "+signedMoney(roundOff):"");}
 private static String roundingDetail(double roundOff){return Math.abs(roundOff)>.0001?" • round-off "+signedMoney(roundOff):"";}
 private static String signedMoney(double v){return (v>=0?"+":"-")+"₹"+fmt(Math.abs(v));}
 private static double money(double v){return DocumentCalculationEngine.money(v);}
 private static String csvCell(String v){if(v==null)return "";String s=v.trim();if(s.contains(",")||s.contains("\"")||s.contains("\n")||s.contains("\r")){return "\""+s.replace("\"","\"\"")+"\"";}return s;}
 private static double bankAmount(BankStatementTransactionEntity t){return n(t.getCreditAmount())>0?n(t.getCreditAmount()):n(t.getDebitAmount());} private static boolean cancelled(String s){String x=up(s);return x.equals("CANCELLED")||x.equals("DELETED");} private static boolean approvalLocked(String s){String x=up(s);return x.equals("PENDING APPROVAL")||x.equals("REJECTED");} private static boolean purchaseLifecycleLocked(String s){String x=up(s);return x.equals("DRAFT");} private static int nv(Integer v){return v==null?0:v;} private static double n(Number v){return v==null?0:v.doubleValue();} private static String up(String s){return s==null?"":s.trim().toUpperCase(Locale.ROOT);} private static boolean blank(String s){return s==null||s.isBlank();} private static String safe(String s){return s==null?"":s;} private static String compact(String s){return up(s).replaceAll("[^A-Z0-9]","");} private static String fmt(double v){return String.format(Locale.ENGLISH,"%,.2f",v);} private static LocalDate date(String s){if(s==null||s.isBlank())return null;String v=s.trim();try{return LocalDate.parse(v.length()>=10?v.substring(0,10):v);}catch (Exception ignored) { java.lang.System.getLogger("org.example").log(java.lang.System.Logger.Level.DEBUG, "Suppressed exception: " + ignored.getMessage(), ignored); }try{return LocalDate.parse(v,java.time.format.DateTimeFormatter.ofPattern("dd/MM/uuuu"));}catch (Exception ignored) { java.lang.System.getLogger("org.example").log(java.lang.System.Logger.Level.DEBUG, "Suppressed exception: " + ignored.getMessage(), ignored); }try{return LocalDate.parse(v,java.time.format.DateTimeFormatter.ofPattern("dd-MM-uuuu"));}catch(Exception ignored){return null;}}
}
