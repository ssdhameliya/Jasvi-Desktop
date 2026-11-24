package org.example.controller;

import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import org.example.service.CompanyTemplateService;
import org.example.service.CompanyTemplateService.TemplateOption;
import org.example.util.AppDialogService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CompanyTemplateLinkDialogController {

    @FXML private Label lblCompanyTitle;
    @FXML private Label lblKpiPdf;
    @FXML private Label lblKpiExcel;

    // 1. Sales Invoice
    @FXML private ComboBox<TemplateOption> cmbSalesPdf;
    @FXML private ComboBox<TemplateOption> cmbSalesExcel;
    @FXML private Label lblSalesPdfDesc;
    @FXML private Label lblSalesExcelDesc;

    // 2. Purchase Order
    @FXML private ComboBox<TemplateOption> cmbPurchasePdf;
    @FXML private ComboBox<TemplateOption> cmbPurchaseExcel;
    @FXML private Label lblPurchasePdfDesc;
    @FXML private Label lblPurchaseExcelDesc;

    // 3. Commercial Quotation
    @FXML private ComboBox<TemplateOption> cmbQuotationPdf;
    @FXML private ComboBox<TemplateOption> cmbQuotationExcel;
    @FXML private Label lblQuotationPdfDesc;
    @FXML private Label lblQuotationExcelDesc;

    // 4. Returns (Credit & Debit Notes)
    @FXML private ComboBox<TemplateOption> cmbReturnsPdf;
    @FXML private ComboBox<TemplateOption> cmbReturnsExcel;
    @FXML private Label lblReturnsPdfDesc;
    @FXML private Label lblReturnsExcelDesc;

    @FXML private Button btnSave;
    @FXML private Button btnCancel;
    @FXML private Button btnResetDefaults;
    @FXML private Button btnOpenExcelStudio;

    private Long companyId = 1L;
    private String companyName = "Jasvi Industries";
    private Runnable onSavedCallback;

    public void init(Long companyId, String companyName, Runnable onSaved) {
        this.companyId = (companyId == null || companyId <= 0) ? 1L : companyId;
        this.companyName = (companyName == null || companyName.isBlank()) ? "Company #" + this.companyId : companyName;
        this.onSavedCallback = onSaved;

        lblCompanyTitle.setText("Link Document Templates: " + this.companyName);

        // Populate PDF catalogs
        setupComboBox(cmbSalesPdf, CompanyTemplateService.getPdfCatalog("SALES"), lblSalesPdfDesc);
        setupComboBox(cmbPurchasePdf, CompanyTemplateService.getPdfCatalog("PURCHASE"), lblPurchasePdfDesc);
        setupComboBox(cmbQuotationPdf, CompanyTemplateService.getPdfCatalog("QUOTATION"), lblQuotationPdfDesc);
        setupComboBox(cmbReturnsPdf, CompanyTemplateService.getPdfCatalog("RETURNS"), lblReturnsPdfDesc);

        // Populate Excel catalogs (dynamically sourced from Excel Studio)
        List<TemplateOption> salesExcel = CompanyTemplateService.getExcelCatalog("SALES");
        List<TemplateOption> purchaseExcel = CompanyTemplateService.getExcelCatalog("PURCHASE");
        List<TemplateOption> quotationExcel = CompanyTemplateService.getExcelCatalog("QUOTATION");
        List<TemplateOption> returnsExcel = CompanyTemplateService.getExcelCatalog("RETURNS");

        setupComboBox(cmbSalesExcel, salesExcel, lblSalesExcelDesc);
        setupComboBox(cmbPurchaseExcel, purchaseExcel, lblPurchaseExcelDesc);
        setupComboBox(cmbQuotationExcel, quotationExcel, lblQuotationExcelDesc);
        setupComboBox(cmbReturnsExcel, returnsExcel, lblReturnsExcelDesc);

        if (lblKpiExcel != null) {
            int activeWorkbooks = (int) salesExcel.stream().filter(e -> "Excel Studio".equalsIgnoreCase(e.badge())).count()
                    + (int) returnsExcel.stream().filter(e -> "Excel Studio".equalsIgnoreCase(e.badge())).count();
            lblKpiExcel.setText(Math.max(2, activeWorkbooks) + " Active Workbooks");
        }

        loadCurrentMappings();
    }

    private void setupComboBox(ComboBox<TemplateOption> combo, List<TemplateOption> catalog, Label descLabel) {
        combo.setItems(FXCollections.observableArrayList(catalog));

        combo.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(TemplateOption item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item.displayName());
                }
            }
        });

        combo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(TemplateOption item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item.displayName());
                }
            }
        });

        combo.setConverter(new StringConverter<>() {
            @Override
            public String toString(TemplateOption item) {
                return item == null ? "" : item.displayName();
            }

            @Override
            public TemplateOption fromString(String string) {
                if (string == null || string.isBlank()) return combo.getValue();
                for (var opt : combo.getItems()) {
                    if (opt.code().equalsIgnoreCase(string) || opt.name().equalsIgnoreCase(string) || opt.displayName().equalsIgnoreCase(string)) {
                        return opt;
                    }
                }
                return combo.getValue();
            }
        });

        combo.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && descLabel != null) {
                descLabel.setText(newVal.description());
            }
        });

        if (!catalog.isEmpty()) {
            combo.getSelectionModel().selectFirst();
        }
    }

    private void loadCurrentMappings() {
        Map<String, String> current = CompanyTemplateService.getCompanyMappings(this.companyId);

        // PDF selections
        selectByCode(cmbSalesPdf, current.getOrDefault("SALES_PDF", current.getOrDefault("SALES", "Jasvi_Sale")));
        selectByCode(cmbPurchasePdf, current.getOrDefault("PURCHASE_PDF", current.getOrDefault("PURCHASE", "Jasvi_Purchase")));
        selectByCode(cmbQuotationPdf, current.getOrDefault("QUOTATION_PDF", current.getOrDefault("QUOTATION", "Jasvi_Quotation")));
        selectByCode(cmbReturnsPdf, current.getOrDefault("RETURNS_PDF", current.getOrDefault("RETURNS", "Jasvi_Return")));

        // Excel selections
        selectByCode(cmbSalesExcel, current.getOrDefault("SALES_EXCEL", "Sale Invoice 1"));
        selectByCode(cmbPurchaseExcel, current.getOrDefault("PURCHASE_EXCEL", "BuiltIn_Purchase_Excel"));
        selectByCode(cmbQuotationExcel, current.getOrDefault("QUOTATION_EXCEL", "BuiltIn_Quotation_Excel"));
        selectByCode(cmbReturnsExcel, current.getOrDefault("RETURNS_EXCEL", "New Purchase Return Excel"));

        if (lblKpiPdf != null && cmbSalesPdf.getValue() != null) {
            lblKpiPdf.setText(cmbSalesPdf.getValue().name());
        }
    }

    private void selectByCode(ComboBox<TemplateOption> combo, String code) {
        if (code == null || code.isBlank() || combo == null) return;
        String query = code.trim();
        for (var item : combo.getItems()) {
            if (item.code().equalsIgnoreCase(query) || item.name().equalsIgnoreCase(query)) {
                combo.getSelectionModel().select(item);
                return;
            }
        }
    }

    @FXML
    private void handleResetDefaults() {
        // Reset PDF selections
        selectByCode(cmbSalesPdf, "Jasvi_Sale");
        selectByCode(cmbPurchasePdf, "Jasvi_Purchase");
        selectByCode(cmbQuotationPdf, "Jasvi_Quotation");
        selectByCode(cmbReturnsPdf, "Jasvi_Return");

        // Reset Excel selections
        selectByCode(cmbSalesExcel, "Sale Invoice 1");
        selectByCode(cmbPurchaseExcel, "BuiltIn_Purchase_Excel");
        selectByCode(cmbQuotationExcel, "BuiltIn_Quotation_Excel");
        selectByCode(cmbReturnsExcel, "New Purchase Return Excel");
    }

    @FXML
    private void handleOpenExcelStudio() {
        closeDialog();
        DashboardController.navigateFromChildPage("Excel Studio", "/fxml/pages/DocumentStudio.fxml");
    }

    @FXML
    private void handleCancel() {
        closeDialog();
    }

    @FXML
    private void handleSave() {
        Map<String, String> mappings = new LinkedHashMap<>();

        // Save PDF Mappings
        if (cmbSalesPdf.getValue() != null) mappings.put("SALES_PDF", cmbSalesPdf.getValue().code());
        if (cmbPurchasePdf.getValue() != null) mappings.put("PURCHASE_PDF", cmbPurchasePdf.getValue().code());
        if (cmbQuotationPdf.getValue() != null) mappings.put("QUOTATION_PDF", cmbQuotationPdf.getValue().code());
        if (cmbReturnsPdf.getValue() != null) mappings.put("RETURNS_PDF", cmbReturnsPdf.getValue().code());

        // Save Excel Mappings
        if (cmbSalesExcel.getValue() != null) mappings.put("SALES_EXCEL", cmbSalesExcel.getValue().code());
        if (cmbPurchaseExcel.getValue() != null) mappings.put("PURCHASE_EXCEL", cmbPurchaseExcel.getValue().code());
        if (cmbQuotationExcel.getValue() != null) mappings.put("QUOTATION_EXCEL", cmbQuotationExcel.getValue().code());
        if (cmbReturnsExcel.getValue() != null) mappings.put("RETURNS_EXCEL", cmbReturnsExcel.getValue().code());

        btnSave.setDisable(true);
        try {
            boolean ok = CompanyTemplateService.saveCompanyMappings(this.companyId, mappings);
            if (ok) {
                AppDialogService.info(btnSave, "Templates Linked",
                        "Document templates successfully linked and isolated for " + this.companyName + ".");
                if (onSavedCallback != null) {
                    onSavedCallback.run();
                }
                closeDialog();
            } else {
                AppDialogService.error(btnSave, "Save Failed", "Could not persist company template mappings.");
            }
        } catch (Exception ex) {
            AppDialogService.error(btnSave, "Error", "Failed to save: " + ex.getMessage());
        } finally {
            btnSave.setDisable(false);
        }
    }

    private void closeDialog() {
        Stage stage = (Stage) btnCancel.getScene().getWindow();
        if (stage != null) {
            stage.close();
        }
    }
}
