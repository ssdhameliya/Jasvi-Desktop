package org.example.server.persistence.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "client_profiles")
public class ClientProfileEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_name", nullable = false, unique = true)
    private String clientName;

    @Column(name = "tally_company_name", nullable = false)
    private String tallyCompanyName;

    @Column(name = "gstin")
    private String gstin;

    @Column(name = "trade_name")
    private String tradeName;

    @Column(name = "legal_name")
    private String legalName;

    @Column(name = "address", columnDefinition = "TEXT")
    private String address;

    @Column(name = "gst_status")
    private String gstStatus = "ACTIVE";

    @Column(name = "taxpayer_type")
    private String taxpayerType = "REGULAR";

    @Column(name = "registration_date")
    private String registrationDate;

    @Column(name = "constitution_of_business")
    private String constitutionOfBusiness;

    @Column(name = "state_jurisdiction")
    private String stateJurisdiction;

    @Column(name = "center_jurisdiction")
    private String centerJurisdiction;

    @Column(name = "nature_of_business")
    private String natureOfBusiness;

    @Column(name = "filing_status_json", columnDefinition = "TEXT")
    private String filingStatusJson = "[]";

    @Column(name = "state_code", nullable = false)
    private String stateCode = "24";

    @Column(name = "default_mode", nullable = false)
    private String defaultMode = "SUMMARY";

    @Column(name = "default_purchase_ledger")
    private String defaultPurchaseLedger = "Purchase Account";

    @Column(name = "default_roundoff_ledger")
    private String defaultRoundoffLedger = "Round Off";

    @Column(name = "show_gst_columns", nullable = false)
    private Boolean showGstColumns = true;

    @Column(name = "show_hsn_column", nullable = false)
    private Boolean showHsnColumn = true;

    @Column(name = "show_cost_center", nullable = false)
    private Boolean showCostCenter = false;

    @Column(name = "show_po_reference", nullable = false)
    private Boolean showPoReference = false;

    @Column(name = "excel_column_mapping_json", columnDefinition = "TEXT")
    private String excelColumnMappingJson;

    @Column(name = "created_at")
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getClientName() { return clientName; }
    public void setClientName(String clientName) { this.clientName = clientName; }
    public String getTallyCompanyName() { return tallyCompanyName; }
    public void setTallyCompanyName(String tallyCompanyName) { this.tallyCompanyName = tallyCompanyName; }
    public String getGstin() { return gstin; }
    public void setGstin(String gstin) { this.gstin = gstin; }
    public String getTradeName() { return tradeName; }
    public void setTradeName(String tradeName) { this.tradeName = tradeName; }
    public String getLegalName() { return legalName; }
    public void setLegalName(String legalName) { this.legalName = legalName; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getGstStatus() { return gstStatus; }
    public void setGstStatus(String gstStatus) { this.gstStatus = gstStatus; }
    public String getTaxpayerType() { return taxpayerType; }
    public void setTaxpayerType(String taxpayerType) { this.taxpayerType = taxpayerType; }
    public String getRegistrationDate() { return registrationDate; }
    public void setRegistrationDate(String registrationDate) { this.registrationDate = registrationDate; }
    public String getConstitutionOfBusiness() { return constitutionOfBusiness; }
    public void setConstitutionOfBusiness(String constitutionOfBusiness) { this.constitutionOfBusiness = constitutionOfBusiness; }
    public String getStateJurisdiction() { return stateJurisdiction; }
    public void setStateJurisdiction(String stateJurisdiction) { this.stateJurisdiction = stateJurisdiction; }
    public String getCenterJurisdiction() { return centerJurisdiction; }
    public void setCenterJurisdiction(String centerJurisdiction) { this.centerJurisdiction = centerJurisdiction; }
    public String getNatureOfBusiness() { return natureOfBusiness; }
    public void setNatureOfBusiness(String natureOfBusiness) { this.natureOfBusiness = natureOfBusiness; }
    public String getFilingStatusJson() { return filingStatusJson; }
    public void setFilingStatusJson(String filingStatusJson) { this.filingStatusJson = filingStatusJson; }
    public String getStateCode() { return stateCode; }
    public void setStateCode(String stateCode) { this.stateCode = stateCode; }
    public String getDefaultMode() { return defaultMode; }
    public void setDefaultMode(String defaultMode) { this.defaultMode = defaultMode; }
    public String getDefaultPurchaseLedger() { return defaultPurchaseLedger; }
    public void setDefaultPurchaseLedger(String defaultPurchaseLedger) { this.defaultPurchaseLedger = defaultPurchaseLedger; }
    public String getDefaultRoundoffLedger() { return defaultRoundoffLedger; }
    public void setDefaultRoundoffLedger(String defaultRoundoffLedger) { this.defaultRoundoffLedger = defaultRoundoffLedger; }
    public Boolean getShowGstColumns() { return showGstColumns; }
    public void setShowGstColumns(Boolean showGstColumns) { this.showGstColumns = showGstColumns; }
    public Boolean getShowHsnColumn() { return showHsnColumn; }
    public void setShowHsnColumn(Boolean showHsnColumn) { this.showHsnColumn = showHsnColumn; }
    public Boolean getShowCostCenter() { return showCostCenter; }
    public void setShowCostCenter(Boolean showCostCenter) { this.showCostCenter = showCostCenter; }
    public Boolean getShowPoReference() { return showPoReference; }
    public void setShowPoReference(Boolean showPoReference) { this.showPoReference = showPoReference; }
    public String getExcelColumnMappingJson() { return excelColumnMappingJson; }
    public void setExcelColumnMappingJson(String excelColumnMappingJson) { this.excelColumnMappingJson = excelColumnMappingJson; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
