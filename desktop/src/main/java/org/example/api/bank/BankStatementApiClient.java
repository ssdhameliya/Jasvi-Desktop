package org.example.api.bank;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.ConfigManager;

import java.net.*;import java.net.http.*;import java.nio.charset.StandardCharsets;import java.time.Duration;import java.util.*;

public final class BankStatementApiClient {
 private final HttpClient http = org.example.api.ApiRuntime.HTTP;
 private final ObjectMapper json = org.example.api.ApiRuntime.JSON;
 private final String base;
 public BankStatementApiClient(){String b=ConfigManager.getDataApiBaseUrl();while(b.endsWith("/"))b=b.substring(0,b.length()-1);base=b;}
 public ImportResult importStatement(ImportRequest r){return post("/api/bank-statements/imports",r,ImportResult.class);} public ContinuityCheckResult checkContinuity(ContinuityCheckRequest r){return post("/api/bank-statements/imports/continuity-check",r,ContinuityCheckResult.class);} public BatchDeleteResult deleteBatch(long id,String confirmation,String user){return delete("/api/bank-statements/imports/"+id+"?confirmation="+enc(confirmation)+"&user="+enc(user),BatchDeleteResult.class);} public List<BatchDto>batches(){return batchPage(0,20,"","","","","").rows();} public BatchDto batch(long id){return get("/api/bank-statements/imports/"+id,BatchDto.class);} public BatchPage batchPage(int page,int size,String account,String status,String fromDate,String toDate,String q){String path="/api/bank-statements/imports/page?page="+Math.max(0,page)+"&size="+Math.max(10,Math.min(size,100))+"&account="+enc(account)+"&status="+enc(status)+"&fromDate="+enc(fromDate)+"&toDate="+enc(toDate)+"&q="+enc(q);return get(path,BatchPage.class);} public SourceDto source(long id){return get("/api/bank-statements/imports/"+id+"/source",SourceDto.class);} public List<TransactionDto>transactions(long id){return get("/api/bank-statements/imports/"+id+"/transactions",new TypeReference<List<TransactionDto>>(){});} public TransactionDto transaction(long id){return get("/api/bank-statements/transactions/"+id,TransactionDto.class);} public Metrics metrics(long id){return get("/api/bank-statements/imports/"+id+"/metrics",Metrics.class);}
 public TransactionPage page(long id,int page,int size,String status,String direction,String fromDate,String toDate,String q){return page(id,page,size,status,direction,"ALL",fromDate,toDate,q);}
 public TransactionPage page(long id,int page,int size,String status,String direction,String channel,String fromDate,String toDate,String q){String path="/api/bank-statements/imports/"+id+"/page?page="+Math.max(0,page)+"&size="+Math.max(10,Math.min(size,200))+"&status="+enc(status)+"&direction="+enc(direction)+"&channel="+enc(channel)+"&fromDate="+enc(fromDate)+"&toDate="+enc(toDate)+"&q="+enc(q);return get(path,TransactionPage.class);}
 public TransactionPage accountPage(String account,int page,int size,String status,String direction,String channel,String fromDate,String toDate,String q){String path="/api/bank-statements/accounts/page?account="+enc(account)+"&page="+Math.max(0,page)+"&size="+Math.max(10,Math.min(size,200))+"&status="+enc(status)+"&direction="+enc(direction)+"&channel="+enc(channel)+"&fromDate="+enc(fromDate)+"&toDate="+enc(toDate)+"&q="+enc(q);return get(path,TransactionPage.class);}
 public Metrics accountMetrics(String account,String fromDate,String toDate){return get("/api/bank-statements/accounts/metrics?account="+enc(account)+"&fromDate="+enc(fromDate)+"&toDate="+enc(toDate),Metrics.class);}
 public BrsSummaryDto batchBrs(long id){return get("/api/bank-statements/imports/"+id+"/brs",BrsSummaryDto.class);}
 public BrsSummaryDto accountBrs(String account,String fromDate,String toDate){return get("/api/bank-statements/accounts/brs?account="+enc(account)+"&fromDate="+enc(fromDate)+"&toDate="+enc(toDate),BrsSummaryDto.class);}
 public List<BankGlMappingDto> glMappings(){return get("/api/bank-statements/gl-mappings",new TypeReference<List<BankGlMappingDto>>(){});}
 public Map<String,Object> updateGlMapping(String bankAccountKey,String glAccountCode){return put("/api/bank-statements/gl-mappings",new BankGlMappingUpdateRequest(bankAccountKey,glAccountCode),new TypeReference<Map<String,Object>>(){});} public List<CandidateDto>suggest(long id){return post("/api/bank-statements/transactions/"+id+"/suggest",null,new TypeReference<List<CandidateDto>>(){});} public List<CandidateDto>candidates(long id){return get("/api/bank-statements/transactions/"+id+"/candidates",new TypeReference<List<CandidateDto>>(){});} public OperationResult match(long id,MatchRequest r){return post("/api/bank-statements/transactions/"+id+"/match",r,OperationResult.class);} public OperationResult expense(long id,ExpenseRequest r){return post("/api/bank-statements/transactions/"+id+"/expense",r,OperationResult.class);} public OperationResult bankEntry(long id,BankEntryRequest r){return post("/api/bank-statements/transactions/"+id+"/bank-entry",r,OperationResult.class);} public BulkOperationResult bulkExpense(BulkExpenseRequest r){return post("/api/bank-statements/transactions/bulk/expense",r,BulkOperationResult.class);} public BulkOperationResult bulkBankEntry(BulkBankEntryRequest r){return post("/api/bank-statements/transactions/bulk/bank-entry",r,BulkOperationResult.class);} public OperationResult updateNote(long id,NoteRequest r){return post("/api/bank-statements/transactions/"+id+"/note",r,OperationResult.class);} public OperationResult ignore(long id,IgnoreRequest r){return post("/api/bank-statements/transactions/"+id+"/ignore",r,OperationResult.class);} public OperationResult review(long id,NoteRequest r){return post("/api/bank-statements/transactions/"+id+"/review",r,OperationResult.class);} public OperationResult reverse(long id,String user){return post("/api/bank-statements/transactions/"+id+"/reverse?user="+enc(user),null,OperationResult.class);} public List<AuditDto>audit(long id){return get("/api/bank-statements/transactions/"+id+"/audit",new TypeReference<List<AuditDto>>(){});}
 public DocumentLinkageDto linkage(String type, Integer id, String docNo, Long transactionId, Integer financeId){String path="/api/bank-statements/linkage?type="+enc(type)+(id!=null?"&id="+id:"")+"&docNo="+enc(docNo)+(transactionId!=null?"&transactionId="+transactionId:"")+(financeId!=null?"&financeId="+financeId:"");return get(path,DocumentLinkageDto.class);}
 public List<RuleDto> listRules(){return get("/api/bank-statements/rules",new TypeReference<List<RuleDto>>(){});}
 public RuleDto createRule(RuleSaveRequest r){return post("/api/bank-statements/rules",r,RuleDto.class);}
 public RuleDto updateRule(long id,RuleSaveRequest r){return put("/api/bank-statements/rules/"+id,r,RuleDto.class);}
 public Map<String,Object> deleteRule(long id){return delete("/api/bank-statements/rules/"+id,new TypeReference<Map<String,Object>>(){});}
 public RulePreviewResult previewRules(long batchId,RuleSaveRequest candidate){return post("/api/bank-statements/imports/"+batchId+"/rules/preview",candidate,RulePreviewResult.class);}
 public RuleApplyResult applyRules(long batchId,String user){return post("/api/bank-statements/imports/"+batchId+"/rules/apply?user="+enc(user),null,RuleApplyResult.class);}
 public RuleApplyResult applySingleRule(long batchId,long ruleId,String user){return post("/api/bank-statements/imports/"+batchId+"/rules/"+ruleId+"/apply?user="+enc(user),null,RuleApplyResult.class);}
 public OperationResult applyRuleToTransaction(long txId,long ruleId,String user){return post("/api/bank-statements/transactions/"+txId+"/rules/"+ruleId+"/apply?user="+enc(user),null,OperationResult.class);}
 public List<RuleSuggestionCandidateDto> discoverRuleSuggestions(){return get("/api/bank-statements/rules/suggestions",new TypeReference<List<RuleSuggestionCandidateDto>>(){});}
 public List<PartyOptionDto> listRuleParties(){return get("/api/bank-statements/rules/parties",new TypeReference<List<PartyOptionDto>>(){});}
 public AutoMatchPreviewDto previewAutoMatchBatch(long batchId){return get("/api/bank-statements/imports/"+batchId+"/auto-match/preview",AutoMatchPreviewDto.class);}
 public AutoMatchPreviewDto previewAutoMatchAccount(String account,String fromDate,String toDate){return get("/api/bank-statements/accounts/auto-match/preview?account="+enc(account)+"&fromDate="+enc(fromDate)+"&toDate="+enc(toDate),AutoMatchPreviewDto.class);}
 private <T>T get(String p,Class<T>c){return req("GET",p,null,c,null);} private <T>T get(String p,TypeReference<T>t){return req("GET",p,null,null,t);} private <T>T post(String p,Object b,Class<T>c){return req("POST",p,b,c,null);} private <T>T post(String p,Object b,TypeReference<T>t){return req("POST",p,b,null,t);} private <T>T put(String p,Object b,Class<T>c){return req("PUT",p,b,c,null);} private <T>T put(String p,Object b,TypeReference<T>t){return req("PUT",p,b,null,t);} private <T>T delete(String p,Class<T>c){return req("DELETE",p,null,c,null);} private <T>T delete(String p,TypeReference<T>t){return req("DELETE",p,null,null,t);}
 private <T>T req(String method,String path,Object body,Class<T>c,TypeReference<T>t){try{HttpRequest.Builder b=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(30)).header("Accept","application/json");org.example.api.ApiSession.authorize(b);if(body!=null)b.header("Content-Type","application/json");String payload=body==null?"":json.writeValueAsString(body);b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(payload,StandardCharsets.UTF_8));HttpResponse<String>r=http.send(b.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));if(r.statusCode()/100!=2){org.example.api.ApiRuntime.logHttpFailure("Bank Statement request",r.statusCode(),r.body());throw new IllegalStateException(org.example.api.ApiRuntime.userMessage("the Bank Statement request",r.statusCode(),r.body()));}if(c!=null)return json.readValue(r.body(),c);return json.readValue(r.body(),t);}catch(Exception e){if(e instanceof RuntimeException re)throw re;throw new IllegalStateException("Cannot reach Bank Statement API at "+base+": "+e.getMessage(),e);}}
 private static String enc(String s){return URLEncoder.encode(s==null?"":s,StandardCharsets.UTF_8);}
 public record ImportRow(Integer sourceRowNumber,String transactionTimestamp,String transactionDate,String valueDate,String description,String reference,double debit,double credit,double balance,String transactionFingerprint){}
 public record ImportRequest(String bankName,String bankAccount,String accountHolder,String statementFrom,String statementTo,String currency,Double openingBalance,Double closingBalance,String sourceFingerprint,String sourceFileName,String sourceCsv,String importedBy,boolean dryRun,List<ImportRow> rows){}
 public record ImportResult(BatchDto batch,int importedRows,int duplicateRows,boolean alreadyImported){}
 public record ContinuityCheckRequest(String bankAccount,String statementFrom,String statementTo,Double openingBalance,Double closingBalance){}
 public record ContinuityCheckResult(boolean hasPriorBatch,Long priorBatchId,String priorBatchRef,String priorFrom,String priorTo,Double priorClosingBalance,int gapDays,boolean overlapDetected,boolean balanceMismatch,double balanceDifference,String severity,List<String> warnings){}
 public record BatchDto(Long id,String statementReference,String bankName,String bankAccount,String accountHolder,String statementFrom,String statementTo,String currency,int transactionCount,double totalDebit,double totalCredit,int reconciledCount,double reconciliationPercent,String status,String sourceFileName,String importedAt){
  public BatchDto(Long id,String bankName,String bankAccount,String accountHolder,String statementFrom,String statementTo,String currency,int transactionCount,double totalDebit,double totalCredit,int reconciledCount,double reconciliationPercent,String status,String sourceFileName,String importedAt){
   this(id,null,bankName,bankAccount,accountHolder,statementFrom,statementTo,currency,transactionCount,totalDebit,totalCredit,reconciledCount,reconciliationPercent,status,sourceFileName,importedAt);
  }
  @Override public String toString(){
   String account=bankAccount==null?"":bankAccount;
   String tail=account.length()>4?"****"+account.substring(account.length()-4):account;
   if(id!=null&&id<=0L){
    return "[CONSOLIDATED] "+bankName+(tail.isBlank()?"":" • "+tail)+" • "+statementFrom+" to "+statementTo;
   }
   String refBadge=statementReference==null||statementReference.isBlank()?"":"["+statementReference+"] ";
   return refBadge+bankName+" • "+tail+" • "+statementFrom+" to "+statementTo+" • "+status;
  }
 }
 public record BatchPage(List<BatchDto> rows,int page,int size,long totalRows,int totalPages){}
 public record SourceDto(String fileName,String fingerprint,String csvContent){} public record TransactionPage(List<TransactionDto> rows,Metrics metrics,int page,int size,long totalRows,int totalPages){}
 public record AllocationDto(Long allocationId,String targetType,Integer targetId,String documentNo,double allocatedAmount,double roundingAdjustment,Integer paymentRecordId,String paymentReference,Integer financeEntryId){
  public AllocationDto(Long allocationId,String targetType,Integer targetId,String documentNo,double allocatedAmount,double roundingAdjustment,Integer paymentRecordId,Integer financeEntryId){
   this(allocationId,targetType,targetId,documentNo,allocatedAmount,roundingAdjustment,paymentRecordId,null,financeEntryId);
  }
 }
 public record TransactionDto(Long id,Long importId,String statementReference,Integer sourceRowNumber,String transactionTimestamp,String transactionDate,String valueDate,String description,String reference,double debit,double credit,double balance,String status,String suggestedMatchType,Integer suggestedMatchId,Double suggestedConfidence,String matchLink,Integer financeEntryId,String financeVoucherNo,String linkedTargetType,Integer linkedTargetId,String linkedDocumentNo,List<AllocationDto> linkedAllocations,long rowVersion){
  public TransactionDto(Long id,Long importId,Integer sourceRowNumber,String transactionTimestamp,String transactionDate,String valueDate,String description,String reference,double debit,double credit,double balance,String status,String suggestedMatchType,Integer suggestedMatchId,Double suggestedConfidence,String matchLink,Integer financeEntryId,String financeVoucherNo,String linkedTargetType,Integer linkedTargetId,String linkedDocumentNo,List<AllocationDto> linkedAllocations,long rowVersion){
   this(id,importId,null,sourceRowNumber,transactionTimestamp,transactionDate,valueDate,description,reference,debit,credit,balance,status,suggestedMatchType,suggestedMatchId,suggestedConfidence,matchLink,financeEntryId,financeVoucherNo,linkedTargetType,linkedTargetId,linkedDocumentNo,linkedAllocations,rowVersion);
  }
 }
 public record LinkageItemDto(Long statementTransactionId,String statementReference,String bankName,String bankAccount,String transactionDate,String originalReference,double debitAmount,double creditAmount,Integer financeEntryId,String financeVoucherNo,String financeVoucherType,String targetType,Integer targetId,String documentNo,Integer paymentRecordId,String paymentReference,String settledBy,double allocatedAmount,String status){
  public LinkageItemDto(Long statementTransactionId,String statementReference,String bankName,String bankAccount,String transactionDate,String originalReference,double debitAmount,double creditAmount,Integer financeEntryId,String financeVoucherNo,String financeVoucherType,String targetType,Integer targetId,String documentNo,double allocatedAmount,String status){
   this(statementTransactionId,statementReference,bankName,bankAccount,transactionDate,originalReference,debitAmount,creditAmount,financeEntryId,financeVoucherNo,financeVoucherType,targetType,targetId,documentNo,null,null,null,allocatedAmount,status);
  }
 }
 public record DocumentLinkageDto(boolean linked,String queryType,String queryRef,List<LinkageItemDto> linkages){}
 public record CandidateDto(String type,Integer id,String documentNo,String partyName,String documentDate,double totalAmount,double paidAmount,double outstanding,double confidence,String matchReason,double taxableAmount,double suggestedTdsAmount,String suggestedTdsLabel,double suggestedRounding,List<AllocationRequest> splitAllocations){
  public CandidateDto(String type,Integer id,String documentNo,String partyName,String documentDate,double totalAmount,double paidAmount,double outstanding,double confidence,String matchReason){
   this(type,id,documentNo,partyName,documentDate,totalAmount,paidAmount,outstanding,confidence,matchReason,totalAmount,0d,"",0d,List.of());
  }
  public CandidateDto(String type,Integer id,String documentNo,String partyName,String documentDate,double totalAmount,double paidAmount,double outstanding,double confidence){
   this(type,id,documentNo,partyName,documentDate,totalAmount,paidAmount,outstanding,confidence,"");
  }
  @Override public String toString(){return type+" • "+documentNo+" • "+partyName+" • Outstanding "+String.format(Locale.ENGLISH,"%,.2f",outstanding)+" • "+String.format(Locale.ENGLISH,"%.0f%%",confidence)+(matchReason!=null&&!matchReason.isBlank()?" ("+matchReason+")":"");}
 }
 public record AllocationRequest(String targetType,Integer targetId,double amount){} public record MatchRequest(String user,List<AllocationRequest> allocations){} public record BatchDeleteResult(boolean success,String message,long deletedBatchId,int deletedTransactions,int reversedTransactions){} public record ExpenseRequest(String category,String accountName,String paymentMode,String notes,String billPath,String user,Double gstRate,Boolean interState,String vendorGstin){
  public ExpenseRequest(String category,String accountName,String paymentMode,String notes,String billPath,String user){
   this(category,accountName,paymentMode,notes,billPath,user,0d,false,null);
  }
 } public record BankEntryRequest(String accountName,String paymentMode,String notes,String user,String category,Integer partyId,String contraAccountName){
  public BankEntryRequest(String accountName,String paymentMode,String notes,String user){
   this(accountName,paymentMode,notes,user,null,null,null);
  }
 }
 public record BulkExpenseRequest(List<Long> transactionIds,String category,String accountName,String paymentMode,String notes,String billPath,String user){} public record BulkBankEntryRequest(List<Long> transactionIds,String accountName,String paymentMode,String notes,String user){} public record BulkOperationResult(boolean success,String message,String status,int processed,List<Integer> financeEntryIds){} public record IgnoreRequest(String note,String user){} public record NoteRequest(String note,String user,long rowVersion){} public record OperationResult(boolean success,String message,String status,Integer financeEntryId){} public record AuditDto(Long id,String eventType,String detail,String previousStatus,String newStatus,String performedBy,String createdAt){}
 public record Metrics(int total,int unmatched,int suggested,int matched,int expenses,int ignored,int review,double totalCredits,double totalDebits,int reconciled,double reconciledPercent,String batchStatus,double openingBalance,double bankClosingBalance,double calculatedClosingBalance,double balanceVariance,boolean balanceVerified){
  public Metrics(int total,int unmatched,int suggested,int matched,int expenses,int ignored,int review,double totalCredits,double totalDebits,int reconciled,double reconciledPercent,String batchStatus){
   this(total,unmatched,suggested,matched,expenses,ignored,review,totalCredits,totalDebits,reconciled,reconciledPercent,batchStatus,0d,0d,totalCredits-totalDebits,0d,false);
  }
 }
 public record BrsLineItemDto(Long id,String statementReference,String transactionDate,String reference,String description,String status,double debit,double credit){}
 public record BrsSummaryDto(String scopeLabel,String bankName,String bankAccount,String periodFrom,String periodTo,String asOfDate,double openingBalance,double statementClosingBalance,double totalCredits,double totalDebits,double calculatedClosingBalance,double balanceVariance,double matchedCredits,double matchedDebits,double expenseDebits,double ignoredCredits,double ignoredDebits,double pendingCredits,double pendingDebits,double impliedBooksBalance,double netReconciliationDiff,int totalCount,int reconciledCount,int pendingCount,List<BrsLineItemDto> unclearedCredits,List<BrsLineItemDto> unclearedDebits,String glAccountCode,double glLedgerBalance,double glVsStatementDiff){
  public BrsSummaryDto(String scopeLabel,String bankName,String bankAccount,String periodFrom,String periodTo,String asOfDate,double openingBalance,double statementClosingBalance,double totalCredits,double totalDebits,double calculatedClosingBalance,double balanceVariance,double matchedCredits,double matchedDebits,double expenseDebits,double ignoredCredits,double ignoredDebits,double pendingCredits,double pendingDebits,double impliedBooksBalance,double netReconciliationDiff,int totalCount,int reconciledCount,int pendingCount,List<BrsLineItemDto> unclearedCredits,List<BrsLineItemDto> unclearedDebits){
   this(scopeLabel,bankName,bankAccount,periodFrom,periodTo,asOfDate,openingBalance,statementClosingBalance,totalCredits,totalDebits,calculatedClosingBalance,balanceVariance,matchedCredits,matchedDebits,expenseDebits,ignoredCredits,ignoredDebits,pendingCredits,pendingDebits,impliedBooksBalance,netReconciliationDiff,totalCount,reconciledCount,pendingCount,unclearedCredits,unclearedDebits,"1020",impliedBooksBalance,0d);
  }
 }
 public record BankGlMappingDto(Long id,String bankAccountKey,String bankName,String accountNumber,String glAccountCode,String glAccountName,double currentBalance){}
 public record BankGlMappingUpdateRequest(String bankAccountKey,String glAccountCode){}
 public record RuleDto(Long id,String ruleName,String matchField,String matchOperator,String matchPattern,String direction,String actionType,String targetCategory,String targetAccountName,String targetPaymentMode,String targetNotes,String executionMode,int priority,boolean active,int hitCount,String lastMatchedAt,String createdBy,String createdAt,String updatedAt,Double minAmount,Double maxAmount,String channelFilter,String bankAccountFilter,Integer targetPartyId,String targetContraAccount){
  public RuleDto(Long id,String ruleName,String matchField,String matchOperator,String matchPattern,String direction,String actionType,String targetCategory,String targetAccountName,String targetPaymentMode,String targetNotes,String executionMode,int priority,boolean active,int hitCount,String lastMatchedAt,String createdBy,String createdAt,String updatedAt){
   this(id,ruleName,matchField,matchOperator,matchPattern,direction,actionType,targetCategory,targetAccountName,targetPaymentMode,targetNotes,executionMode,priority,active,hitCount,lastMatchedAt,createdBy,createdAt,updatedAt,null,null,null,null,null,null);
  }
 }
 public record RuleSaveRequest(String ruleName,String matchField,String matchOperator,String matchPattern,String direction,String actionType,String targetCategory,String targetAccountName,String targetPaymentMode,String targetNotes,String executionMode,Integer priority,Boolean active,Double minAmount,Double maxAmount,String channelFilter,String bankAccountFilter,Integer targetPartyId,String targetContraAccount){
  public RuleSaveRequest(String ruleName,String matchField,String matchOperator,String matchPattern,String direction,String actionType,String targetCategory,String targetAccountName,String targetPaymentMode,String targetNotes,String executionMode,Integer priority,Boolean active){
   this(ruleName,matchField,matchOperator,matchPattern,direction,actionType,targetCategory,targetAccountName,targetPaymentMode,targetNotes,executionMode,priority,active,null,null,null,null,null,null);
  }
 }
 public record RulePreviewMatchDto(Long transactionId,String transactionDate,String description,String reference,double amount,String direction,String matchedRuleName,String actionType,String targetSummary,String executionMode){}
 public record RulePreviewResult(boolean enabled,int totalOpenRows,int matchedRows,double matchedTotalAmount,List<RulePreviewMatchDto> matches){}
 public record RuleApplyResult(boolean enabled,int suggestedCount,int autoAppliedCount,int skippedCount,String message){}
 public record AutoMatchSuggestionDto(TransactionDto tx,String kind,CandidateDto candidate,Long ruleId,String ruleName,String ruleActionType,String ruleTargetCategory,String ruleTargetAccountName,String ruleTargetPaymentMode,String ruleTargetNotes,Integer ruleTargetPartyId,String contraAccountName,Long contraPairTxId,double varianceAmount,String reasonSummary,double confidence,boolean autoSelectable){}
 public record AutoMatchPreviewDto(int scannedOpenCount,int eligibleCount,double eligibleTotalAmount,List<AutoMatchSuggestionDto> suggestions){}
 public record RuleSuggestionCandidateDto(String suggestedName,String matchPattern,String direction,String actionType,String targetCategory,String targetAccountName,int historyCount,double totalAmount){}
 public record PartyOptionDto(int id,String name,String partyType){
  @Override public String toString(){return name+(partyType==null||partyType.isBlank()?"":" ("+partyType+")");}
 }
}
