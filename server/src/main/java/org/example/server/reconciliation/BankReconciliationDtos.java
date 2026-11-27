package org.example.server.reconciliation;
import java.util.List;

public final class BankReconciliationDtos {
 private BankReconciliationDtos(){}
 public record ImportRow(Integer sourceRowNumber,String transactionTimestamp,String transactionDate,String valueDate,String description,String reference,double debit,double credit,double balance,String transactionFingerprint){}
 public record ImportRequest(String bankName,String bankAccount,String accountHolder,String statementFrom,String statementTo,String currency,Double openingBalance,Double closingBalance,String sourceFingerprint,String sourceFileName,String sourceCsv,String importedBy,boolean dryRun,List<ImportRow> rows){}
 public record ImportResult(BatchDto batch,int importedRows,int duplicateRows,boolean alreadyImported){}
 public record ContinuityCheckRequest(String bankAccount,String statementFrom,String statementTo,Double openingBalance,Double closingBalance){}
 public record ContinuityCheckResult(boolean hasPriorBatch,Long priorBatchId,String priorBatchRef,String priorFrom,String priorTo,Double priorClosingBalance,int gapDays,boolean overlapDetected,boolean balanceMismatch,double balanceDifference,String severity,List<String> warnings){}
 public record BatchDto(Long id,String statementReference,String bankName,String bankAccount,String accountHolder,String statementFrom,String statementTo,String currency,int transactionCount,double totalDebit,double totalCredit,int reconciledCount,double reconciliationPercent,String status,String sourceFileName,String importedAt){
  public BatchDto(Long id,String bankName,String bankAccount,String accountHolder,String statementFrom,String statementTo,String currency,int transactionCount,double totalDebit,double totalCredit,int reconciledCount,double reconciliationPercent,String status,String sourceFileName,String importedAt){
   this(id,null,bankName,bankAccount,accountHolder,statementFrom,statementTo,currency,transactionCount,totalDebit,totalCredit,reconciledCount,reconciliationPercent,status,sourceFileName,importedAt);
  }
 }
 public record BatchPage(List<BatchDto> rows,int page,int size,long totalRows,int totalPages){}
 public record SourceDto(String fileName,String fingerprint,String csvContent){}
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
 public record TransactionPage(List<TransactionDto> rows,Metrics metrics,int page,int size,long totalRows,int totalPages){}
 public record CandidateDto(String type,Integer id,String documentNo,String partyName,String documentDate,double totalAmount,double paidAmount,double outstanding,double confidence,String matchReason,double taxableAmount,double suggestedTdsAmount,String suggestedTdsLabel,double suggestedRounding,List<AllocationRequest> splitAllocations){
  public CandidateDto(String type,Integer id,String documentNo,String partyName,String documentDate,double totalAmount,double paidAmount,double outstanding,double confidence,String matchReason){
   this(type,id,documentNo,partyName,documentDate,totalAmount,paidAmount,outstanding,confidence,matchReason,totalAmount,0d,"",0d,List.of());
  }
  public CandidateDto(String type,Integer id,String documentNo,String partyName,String documentDate,double totalAmount,double paidAmount,double outstanding,double confidence){
   this(type,id,documentNo,partyName,documentDate,totalAmount,paidAmount,outstanding,confidence,"");
  }
 }
 public record AllocationRequest(String targetType,Integer targetId,double amount){}
 public record MatchRequest(String user,List<AllocationRequest> allocations){}
 public record ExpenseRequest(String category,String accountName,String paymentMode,String notes,String billPath,String user){}
 public record BankEntryRequest(String accountName,String paymentMode,String notes,String user,String category,Integer partyId,String contraAccountName){
  public BankEntryRequest(String accountName,String paymentMode,String notes,String user){
   this(accountName,paymentMode,notes,user,null,null,null);
  }
 }
 public record BulkExpenseRequest(List<Long> transactionIds,String category,String accountName,String paymentMode,String notes,String billPath,String user){}
 public record BulkBankEntryRequest(List<Long> transactionIds,String accountName,String paymentMode,String notes,String user){}
 public record BulkOperationResult(boolean success,String message,String status,int processed,List<Integer> financeEntryIds){}
 public record IgnoreRequest(String note,String user){}
 public record NoteRequest(String note,String user,long rowVersion){}
 public record OperationResult(boolean success,String message,String status,Integer financeEntryId){}
 public record BatchDeleteResult(boolean success,String message,long deletedBatchId,int deletedTransactions,int reversedTransactions){}
 public record AuditDto(Long id,String eventType,String detail,String previousStatus,String newStatus,String performedBy,String createdAt){}
 public record Metrics(int total,int unmatched,int suggested,int matched,int expenses,int ignored,int review,double totalCredits,double totalDebits,int reconciled,double reconciledPercent,String batchStatus,double openingBalance,double bankClosingBalance,double calculatedClosingBalance,double balanceVariance,boolean balanceVerified){
  public Metrics(int total,int unmatched,int suggested,int matched,int expenses,int ignored,int review,double totalCredits,double totalDebits,int reconciled,double reconciledPercent,String batchStatus){
   this(total,unmatched,suggested,matched,expenses,ignored,review,totalCredits,totalDebits,reconciled,reconciledPercent,batchStatus,0d,0d,totalCredits-totalDebits,0d,false);
  }
 }
 public record BrsLineItemDto(Long id,String statementReference,String transactionDate,String reference,String description,String status,double debit,double credit){}
 public record BrsSummaryDto(String scopeLabel,String bankName,String bankAccount,String periodFrom,String periodTo,String asOfDate,double openingBalance,double statementClosingBalance,double totalCredits,double totalDebits,double calculatedClosingBalance,double balanceVariance,double matchedCredits,double matchedDebits,double expenseDebits,double ignoredCredits,double ignoredDebits,double pendingCredits,double pendingDebits,double impliedBooksBalance,double netReconciliationDiff,int totalCount,int reconciledCount,int pendingCount,List<BrsLineItemDto> unclearedCredits,List<BrsLineItemDto> unclearedDebits){}
 public record LinkageItemDto(Long statementTransactionId,String statementReference,String bankName,String bankAccount,String transactionDate,String originalReference,double debitAmount,double creditAmount,Integer financeEntryId,String financeVoucherNo,String financeVoucherType,String targetType,Integer targetId,String documentNo,Integer paymentRecordId,String paymentReference,String settledBy,double allocatedAmount,String status){
  public LinkageItemDto(Long statementTransactionId,String statementReference,String bankName,String bankAccount,String transactionDate,String originalReference,double debitAmount,double creditAmount,Integer financeEntryId,String financeVoucherNo,String financeVoucherType,String targetType,Integer targetId,String documentNo,double allocatedAmount,String status){
   this(statementTransactionId,statementReference,bankName,bankAccount,transactionDate,originalReference,debitAmount,creditAmount,financeEntryId,financeVoucherNo,financeVoucherType,targetType,targetId,documentNo,null,null,null,allocatedAmount,status);
  }
 }
 public record DocumentLinkageDto(boolean linked,String queryType,String queryRef,List<LinkageItemDto> linkages){}
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
 public record PartyOptionDto(int id,String name,String partyType){}
}
