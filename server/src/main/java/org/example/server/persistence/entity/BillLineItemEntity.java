package org.example.server.persistence.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "bill_line_items")
public class BillLineItemEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bill_record_id", nullable = false)
    private Long billRecordId;

    @Column(name = "line_number", nullable = false)
    private Integer lineNumber = 1;

    @Column(name = "extracted_item_name", nullable = false)
    private String extractedItemName;

    @Column(name = "mapped_tally_stock_item")
    private String mappedTallyStockItem;

    @Column(name = "hsn_sac")
    private String hsnSac;

    @Column(name = "quantity", nullable = false)
    private BigDecimal quantity = BigDecimal.ONE;

    @Column(name = "uom")
    private String uom = "NOS";

    @Column(name = "rate", nullable = false)
    private BigDecimal rate = BigDecimal.ZERO;

    @Column(name = "discount_amount", nullable = false)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "tax_rate", nullable = false)
    private BigDecimal taxRate = BigDecimal.ZERO;

    @Column(name = "taxable_amount", nullable = false)
    private BigDecimal taxableAmount = BigDecimal.ZERO;

    @Column(name = "line_accuracy_percentage", nullable = false)
    private BigDecimal lineAccuracyPercentage = BigDecimal.valueOf(100.00);

    @Column(name = "line_error_notes")
    private String lineErrorNotes;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getBillRecordId() { return billRecordId; }
    public void setBillRecordId(Long billRecordId) { this.billRecordId = billRecordId; }
    public Integer getLineNumber() { return lineNumber; }
    public void setLineNumber(Integer lineNumber) { this.lineNumber = lineNumber; }
    public String getExtractedItemName() { return extractedItemName; }
    public void setExtractedItemName(String extractedItemName) { this.extractedItemName = extractedItemName; }
    public String getMappedTallyStockItem() { return mappedTallyStockItem; }
    public void setMappedTallyStockItem(String mappedTallyStockItem) { this.mappedTallyStockItem = mappedTallyStockItem; }
    public String getHsnSac() { return hsnSac; }
    public void setHsnSac(String hsnSac) { this.hsnSac = hsnSac; }
    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity == null ? BigDecimal.ONE : quantity; }
    public String getUom() { return uom; }
    public void setUom(String uom) { this.uom = uom; }
    public BigDecimal getRate() { return rate; }
    public void setRate(BigDecimal rate) { this.rate = rate == null ? BigDecimal.ZERO : rate; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount == null ? BigDecimal.ZERO : discountAmount; }
    public BigDecimal getTaxRate() { return taxRate; }
    public void setTaxRate(BigDecimal taxRate) { this.taxRate = taxRate == null ? BigDecimal.ZERO : taxRate; }
    public BigDecimal getTaxableAmount() { return taxableAmount; }
    public void setTaxableAmount(BigDecimal taxableAmount) { this.taxableAmount = taxableAmount == null ? BigDecimal.ZERO : taxableAmount; }
    public BigDecimal getLineAccuracyPercentage() { return lineAccuracyPercentage; }
    public void setLineAccuracyPercentage(BigDecimal lineAccuracyPercentage) { this.lineAccuracyPercentage = lineAccuracyPercentage == null ? BigDecimal.valueOf(100.0) : lineAccuracyPercentage; }
    public String getLineErrorNotes() { return lineErrorNotes; }
    public void setLineErrorNotes(String lineErrorNotes) { this.lineErrorNotes = lineErrorNotes; }
}
