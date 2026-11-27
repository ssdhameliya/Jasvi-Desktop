package org.example.server.customer360;

import java.math.BigDecimal;
import java.util.List;

public final class Customer360Dtos {
    private Customer360Dtos() {}

    public record Customer(int id,String code,String name,String contactPerson,String phone,String email,
                           String gstin,String address,BigDecimal openingBalance,boolean active,long rowVersion) {}
    public record Summary(Customer customer,BigDecimal outstandingReceivable,BigDecimal openQuotationValue,long openQuotationCount,
                          BigDecimal totalSales,BigDecimal lastPaymentAmount,String lastPaymentDate,
                          List<QuotationRow> recentQuotations,List<InvoiceRow> recentInvoices) {}
    public record QuotationRow(int id,String no,String date,String valid,String salesperson,BigDecimal amount,String status,String followUp) {}
    public record InvoiceRow(int id,String invoiceNo,String invoiceDate,BigDecimal totalAmount,
                             BigDecimal paidAmount,BigDecimal outstanding,String paymentStatus,String documentStatus) {}
    public record PaymentRow(int id,String paymentDate,String referenceNo,String paymentMode,BigDecimal amount,String invoiceNo,String notes) {}
    public record ContactRow(long id,int partyId,String name,String designation,String department,String mobile,String email,
                             boolean primary,boolean emailRecipient,boolean whatsappRecipient,String notes,long rowVersion,String createdBy,String createdAt,String updatedBy,String updatedAt) {}
    public record ContactSave(Long id,String name,String designation,String department,String mobile,String email,boolean primary,boolean emailRecipient,boolean whatsappRecipient,String notes,long rowVersion) {}
    public record NoteRow(long id,int partyId,String note,String createdBy,String createdAt,String updatedBy,String updatedAt,long rowVersion) {}
    public record NoteSave(Long id,String note,long rowVersion) {}
    public record CreditHealth(int customerId,BigDecimal openingBalance,BigDecimal unpaidInvoicesTotal,BigDecimal netReceivable,
                               long overdueCount,BigDecimal overdueAmount,BigDecimal creditLimit,double creditUsedPercent,
                               BigDecimal lastPaymentAmount,String lastPaymentDate) {}
    public record PriceRuleRow(long id,int partyId,String itemCode,String itemDescription,double minQty,double specialRate,
                               double discountPercent,String validFrom,String validTo,String notes,boolean active,long rowVersion) {}
    public record PriceRuleSave(Long id,String itemCode,double minQty,double specialRate,double discountPercent,
                                String validFrom,String validTo,String notes,boolean active,long rowVersion) {}
    public record ItemPricingInsight(int customerId,String itemCode,Double lastSoldRate,Double lastSoldDiscountPercent,
                                     String lastSoldDate,String lastSoldInvoiceNo,double purchaseCost,double masterSellingPrice,
                                     PriceRuleRow matchedRule,List<PriceRuleRow> allSlabs) {}
    public record Ok(boolean success,String message) {}
}
