package org.example.server.persistence.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "bill_records")
public class BillRecordEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id")
    private Long batchId;

    @Column(name = "client_profile_id")
    private Long clientProfileId;

    @Column(name = "voucher_mode", nullable = false)
    private String voucherMode = "SUMMARY"; // SUMMARY, ITEM

    @Column(name = "invoice_number")
    private String invoiceNumber;

    @Column(name = "invoice_date")
    private LocalDate invoiceDate;

    @Column(name = "vendor_name_printed")
    private String vendorNamePrinted;

    @Column(name = "vendor_gstin")
    private String vendorGstin;

    @Column(name = "tally_party_ledger")
    private String tallyPartyLedger;

    @Column(name = "tally_purchase_ledger")
    private String tallyPurchaseLedger;

    @Column(name = "cost_center")
    private String costCenter;

    @Column(name = "po_reference")
    private String poReference;

    @Column(name = "taxable_amount", nullable = false)
    private BigDecimal taxableAmount = BigDecimal.ZERO;

    @Column(name = "cgst_amount", nullable = false)
    private BigDecimal cgstAmount = BigDecimal.ZERO;

    @Column(name = "sgst_amount", nullable = false)
    private BigDecimal sgstAmount = BigDecimal.ZERO;

    @Column(name = "igst_amount", nullable = false)
    private BigDecimal igstAmount = BigDecimal.ZERO;

    @Column(name = "round_off", nullable = false)
    private BigDecimal roundOff = BigDecimal.ZERO;

    @Column(name = "grand_total", nullable = false)
    private BigDecimal grandTotal = BigDecimal.ZERO;

    @Column(name = "accuracy_percentage", nullable = false)
    private BigDecimal accuracyPercentage = BigDecimal.ZERO;

    @Column(name = "validation_notes", columnDefinition = "TEXT")
    private String validationNotes;

    @Column(name = "attached_file_paths", columnDefinition = "TEXT")
    private String attachedFilePaths;

    @Column(name = "status", nullable = false)
    private String status = "EXTRACTED"; // EXTRACTED, VERIFIED, EXPORTED_TALLY, FAILED

    @Column(name = "tally_voucher_guid")
    private String tallyVoucherGuid;

    @Column(name = "created_at")
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public Long getClientProfileId() { return clientProfileId; }
    public void setClientProfileId(Long clientProfileId) { this.clientProfileId = clientProfileId; }
    public String getVoucherMode() { return voucherMode; }
    public void setVoucherMode(String voucherMode) { this.voucherMode = voucherMode; }
    public String getInvoiceNumber() { return invoiceNumber; }
    public void setInvoiceNumber(String invoiceNumber) { this.invoiceNumber = invoiceNumber; }
    public LocalDate getInvoiceDate() { return invoiceDate; }
    public void setInvoiceDate(LocalDate invoiceDate) { this.invoiceDate = invoiceDate; }
    public String getVendorNamePrinted() { return vendorNamePrinted; }
    public void setVendorNamePrinted(String vendorNamePrinted) { this.vendorNamePrinted = vendorNamePrinted; }
    public String getVendorGstin() { return vendorGstin; }
    public void setVendorGstin(String vendorGstin) { this.vendorGstin = vendorGstin; }
    public String getTallyPartyLedger() { return tallyPartyLedger; }
    public void setTallyPartyLedger(String tallyPartyLedger) { this.tallyPartyLedger = tallyPartyLedger; }
    public String getTallyPurchaseLedger() { return tallyPurchaseLedger; }
    public void setTallyPurchaseLedger(String tallyPurchaseLedger) { this.tallyPurchaseLedger = tallyPurchaseLedger; }
    public String getCostCenter() { return costCenter; }
    public void setCostCenter(String costCenter) { this.costCenter = costCenter; }
    public String getPoReference() { return poReference; }
    public void setPoReference(String poReference) { this.poReference = poReference; }
    public BigDecimal getTaxableAmount() { return taxableAmount; }
    public void setTaxableAmount(BigDecimal taxableAmount) { this.taxableAmount = taxableAmount == null ? BigDecimal.ZERO : taxableAmount; }
    public BigDecimal getCgstAmount() { return cgstAmount; }
    public void setCgstAmount(BigDecimal cgstAmount) { this.cgstAmount = cgstAmount == null ? BigDecimal.ZERO : cgstAmount; }
    public BigDecimal getSgstAmount() { return sgstAmount; }
    public void setSgstAmount(BigDecimal sgstAmount) { this.sgstAmount = sgstAmount == null ? BigDecimal.ZERO : sgstAmount; }
    public BigDecimal getIgstAmount() { return igstAmount; }
    public void setIgstAmount(BigDecimal igstAmount) { this.igstAmount = igstAmount == null ? BigDecimal.ZERO : igstAmount; }
    public BigDecimal getRoundOff() { return roundOff; }
    public void setRoundOff(BigDecimal roundOff) { this.roundOff = roundOff == null ? BigDecimal.ZERO : roundOff; }
    public BigDecimal getGrandTotal() { return grandTotal; }
    public void setGrandTotal(BigDecimal grandTotal) { this.grandTotal = grandTotal == null ? BigDecimal.ZERO : grandTotal; }
    public BigDecimal getAccuracyPercentage() { return accuracyPercentage; }
    public void setAccuracyPercentage(BigDecimal accuracyPercentage) { this.accuracyPercentage = accuracyPercentage == null ? BigDecimal.ZERO : accuracyPercentage; }
    public String getValidationNotes() { return validationNotes; }
    public void setValidationNotes(String validationNotes) { this.validationNotes = validationNotes; }
    public String getAttachedFilePaths() { return attachedFilePaths; }
    public void setAttachedFilePaths(String attachedFilePaths) { this.attachedFilePaths = attachedFilePaths; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getTallyVoucherGuid() { return tallyVoucherGuid; }
    public void setTallyVoucherGuid(String tallyVoucherGuid) { this.tallyVoucherGuid = tallyVoucherGuid; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
