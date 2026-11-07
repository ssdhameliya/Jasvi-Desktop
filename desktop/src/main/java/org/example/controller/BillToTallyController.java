package org.example.controller;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.example.api.tally.TallyAutomationApiClient;
import org.example.navigation.ScreenLifecycle;
import org.example.service.SessionService;
import org.example.tally.TallyDocumentInspectionDialog;
import org.example.tally.TallyDocumentParser;
import org.example.tally.TallyExportPreviewDialog;
import org.example.util.AppDialogService;
import org.example.util.DynamicTableLayoutManager;
import org.example.util.IconFactory;
import org.example.util.ToastManager;
import org.example.util.UiActionIcons;
import org.example.util.UiTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Callable;

public class BillToTallyController implements ScreenLifecycle {
    private static final Logger log = LoggerFactory.getLogger(BillToTallyController.class);

    @FXML private StackPane headerIconHolder;
    @FXML private ComboBox<String> cmbClientProfile;
    @FXML private ComboBox<String> cmbTemplateVersion;
    @FXML private Label lblTemplateVersion;
    @FXML private Button btnUploadBills;

    @FXML private TableView<TallyAutomationApiClient.BillRecordDto> tblBills;
    @FXML private TableColumn<TallyAutomationApiClient.BillRecordDto, String> colStatus;
    @FXML private TableColumn<TallyAutomationApiClient.BillRecordDto, String> colAccuracy;
    @FXML private TableColumn<TallyAutomationApiClient.BillRecordDto, String> colInvoiceNo;
    @FXML private TableColumn<TallyAutomationApiClient.BillRecordDto, String> colInvoiceDate;
    @FXML private TableColumn<TallyAutomationApiClient.BillRecordDto, String> colVendorName;
    @FXML private TableColumn<TallyAutomationApiClient.BillRecordDto, String> colVendorGstin;
    @FXML private TableColumn<TallyAutomationApiClient.BillRecordDto, String> colPartyLedger;
    @FXML private TableColumn<TallyAutomationApiClient.BillRecordDto, String> colTaxable;
    @FXML private TableColumn<TallyAutomationApiClient.BillRecordDto, String> colTotalGst;
    @FXML private TableColumn<TallyAutomationApiClient.BillRecordDto, String> colIgst;
    @FXML private TableColumn<TallyAutomationApiClient.BillRecordDto, String> colGrandTotal;
    @FXML private TableColumn<TallyAutomationApiClient.BillRecordDto, Void> colActions;

    @FXML private Button btnDeleteBill;
    @FXML private Button btnRecalculate;

    @FXML private VBox lineItemsSection;
    @FXML private Label lblLineItemBillRef;
    @FXML private TableView<TallyAutomationApiClient.BillLineItemDto> tblLineItems;
    @FXML private TableColumn<TallyAutomationApiClient.BillLineItemDto, String> colLineNo;
    @FXML private TableColumn<TallyAutomationApiClient.BillLineItemDto, String> colItemName;
    @FXML private TableColumn<TallyAutomationApiClient.BillLineItemDto, String> colTallyItem;
    @FXML private TableColumn<TallyAutomationApiClient.BillLineItemDto, String> colHsn;
    @FXML private TableColumn<TallyAutomationApiClient.BillLineItemDto, String> colQty;
    @FXML private TableColumn<TallyAutomationApiClient.BillLineItemDto, String> colUom;
    @FXML private TableColumn<TallyAutomationApiClient.BillLineItemDto, String> colRate;
    @FXML private TableColumn<TallyAutomationApiClient.BillLineItemDto, String> colLineTaxable;
    @FXML private TableColumn<TallyAutomationApiClient.BillLineItemDto, String> colLineAccuracy;

    @FXML private TextField txtTallyUrl;
    @FXML private Button btnExportJson;
    @FXML private Button btnExportGstPortal;
    @FXML private Button btnExportExcel;
    @FXML private Button btnPushTally;

    private final TallyAutomationApiClient api = new TallyAutomationApiClient();
    private final ObservableList<TallyAutomationApiClient.BillRecordDto> billsList = FXCollections.observableArrayList();
    private final ObservableList<TallyAutomationApiClient.BillLineItemDto> lineItemsList = FXCollections.observableArrayList();
    private final Map<String, TallyAutomationApiClient.ClientProfileDto> profilesByName = new LinkedHashMap<>();
    private final Map<String, TallyAutomationApiClient.TemplateVersionDto> templatesByLabel = new LinkedHashMap<>();

    private Long activeBatchId = null;

    @FXML
    public void initialize() {
        if (headerIconHolder != null) {
            headerIconHolder.getChildren().setAll(IconFactory.icon("excel", 24));
        }

        if (btnUploadBills != null) btnUploadBills.setGraphic(IconFactory.compactIcon("add", 14));
        if (btnDeleteBill != null) btnDeleteBill.setGraphic(IconFactory.compactIcon("delete", 14));
        if (btnRecalculate != null) btnRecalculate.setGraphic(IconFactory.compactIcon("refresh", 14));
        if (btnExportJson != null) btnExportJson.setGraphic(IconFactory.compactIcon("file", 14));
        if (btnExportGstPortal != null) btnExportGstPortal.setGraphic(IconFactory.compactIcon("file", 14));
        if (btnExportExcel != null) btnExportExcel.setGraphic(IconFactory.compactIcon("excel", 14));
        if (btnPushTally != null) btnPushTally.setGraphic(IconFactory.compactIcon("send", 14));

        if (cmbTemplateVersion != null) {
            cmbTemplateVersion.getSelectionModel().selectedItemProperty().addListener((obs, oldV, label) -> {
                if (label != null && templatesByLabel.containsKey(label)) {
                    rebuildTablesFromTemplate(templatesByLabel.get(label));
                }
            });
        }

        if (cmbClientProfile != null) {
            cmbClientProfile.getSelectionModel().selectedItemProperty().addListener((obs, oldV, name) -> {
                if (name != null && profilesByName.containsKey(name)) {
                    applyTemplateForClient(profilesByName.get(name));
                }
            });
        }

        configureBillsTable();
        configureLineItemsTable();
        loadClientProfiles();
    }

    @Override
    public void onScreenShown(boolean reusedFromCache) {
        loadClientProfiles();
        if (reusedFromCache && activeBatchId != null) {
            reloadActiveBatch();
        }
    }

    @Override
    public void onScreenHidden() {
        UiTaskExecutor.cancel("tally-bills-load");
        UiTaskExecutor.cancel("tally-export");
    }

    private void configureBillsTable() {
        colStatus.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().status()));
        colAccuracy.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().accuracyPercentage() != null ? c.getValue().accuracyPercentage().toPlainString() + "%" : "0%"));
        colInvoiceNo.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().invoiceNumber()));
        colInvoiceDate.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().invoiceDate() != null ? c.getValue().invoiceDate().format(DateTimeFormatter.ofPattern("dd-MM-yyyy")) : ""));
        colVendorName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().vendorNamePrinted()));
        colVendorGstin.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().vendorGstin() != null ? c.getValue().vendorGstin() : ""));
        colPartyLedger.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().tallyPartyLedger()));
        colTaxable.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().taxableAmount() != null ? "₹" + c.getValue().taxableAmount().toPlainString() : "₹0.00"));
        colTotalGst.setCellValueFactory(c -> {
            BigDecimal gst = (c.getValue().cgstAmount() != null ? c.getValue().cgstAmount() : BigDecimal.ZERO)
                    .add(c.getValue().sgstAmount() != null ? c.getValue().sgstAmount() : BigDecimal.ZERO);
            return new SimpleStringProperty("₹" + gst.toPlainString());
        });
        colIgst.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().igstAmount() != null ? "₹" + c.getValue().igstAmount().toPlainString() : "₹0.00"));
        colGrandTotal.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().grandTotal() != null ? "₹" + c.getValue().grandTotal().toPlainString() : "₹0.00"));

        // Accuracy badge styling
        colAccuracy.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    getStyleClass().removeAll("approved-emerald-badge", "approved-amber-badge", "approved-danger-badge");
                } else {
                    setText(item);
                    getStyleClass().removeAll("approved-emerald-badge", "approved-amber-badge", "approved-danger-badge");
                    try {
                        double val = Double.parseDouble(item.replace("%", "").trim());
                        if (val >= 95.0) {
                            getStyleClass().add("approved-emerald-badge");
                        } else if (val >= 75.0) {
                            getStyleClass().add("approved-amber-badge");
                        } else {
                            getStyleClass().add("approved-danger-badge");
                        }
                    } catch (Exception ignored) {}
                }
            }
        });

        // Actions column with interactive Inspect Document popup
        if (colActions != null) {
            colActions.setCellFactory(c -> new TableCell<>() {
                private final Button inspectBtn = new Button("Inspect");
                {
                    inspectBtn.getStyleClass().addAll("approved-button", "approved-secondary-button");
                    UiActionIcons.apply(inspectBtn, "view", "Inspect Original Document");
                    inspectBtn.setOnAction(e -> {
                        var item = getTableRow() != null ? getTableRow().getItem() : null;
                        if (item != null) {
                            TallyDocumentInspectionDialog.show(tblBills, item);
                        }
                    });
                }

                @Override
                protected void updateItem(Void item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                        setGraphic(null);
                    } else {
                        setGraphic(inspectBtn);
                    }
                    setAlignment(Pos.CENTER);
                }
            });
        }

        // Enable table cell editing for corrections
        tblBills.setEditable(true);

        colInvoiceNo.setCellFactory(javafx.scene.control.cell.TextFieldTableCell.forTableColumn());
        colInvoiceNo.setOnEditCommit(event -> {
            var bill = event.getRowValue();
            if (bill != null && event.getNewValue() != null && !event.getNewValue().isBlank()) {
                var updated = new TallyAutomationApiClient.BillRecordDto(
                        bill.id(), bill.batchId(), bill.clientProfileId(), bill.voucherMode(),
                        event.getNewValue().trim(), bill.invoiceDate(), bill.vendorNamePrinted(), bill.vendorGstin(),
                        bill.tallyPartyLedger(), bill.tallyPurchaseLedger(), bill.costCenter(), bill.poReference(),
                        bill.taxableAmount(), bill.cgstAmount(), bill.sgstAmount(), bill.igstAmount(),
                        bill.roundOff(), bill.grandTotal(), bill.accuracyPercentage(), bill.validationNotes(),
                        bill.attachedFilePaths(), bill.status(), bill.tallyVoucherGuid(), bill.lineItems()
                );
                saveUpdatedBill(updated);
            }
        });

        colPartyLedger.setCellFactory(javafx.scene.control.cell.TextFieldTableCell.forTableColumn());
        colPartyLedger.setOnEditCommit(event -> {
            var bill = event.getRowValue();
            if (bill != null && event.getNewValue() != null && !event.getNewValue().isBlank()) {
                var updated = new TallyAutomationApiClient.BillRecordDto(
                        bill.id(), bill.batchId(), bill.clientProfileId(), bill.voucherMode(),
                        bill.invoiceNumber(), bill.invoiceDate(), bill.vendorNamePrinted(), bill.vendorGstin(),
                        event.getNewValue().trim(), bill.tallyPurchaseLedger(), bill.costCenter(), bill.poReference(),
                        bill.taxableAmount(), bill.cgstAmount(), bill.sgstAmount(), bill.igstAmount(),
                        bill.roundOff(), bill.grandTotal(), bill.accuracyPercentage(), bill.validationNotes(),
                        bill.attachedFilePaths(), bill.status(), bill.tallyVoucherGuid(), bill.lineItems()
                );
                saveUpdatedBill(updated);
            }
        });

        tblBills.setItems(billsList);
        // Dynamic responsive column widths per Rule 7
        DynamicTableLayoutManager.install(tblBills);

        tblBills.getSelectionModel().selectedItemProperty().addListener((obs, oldV, bill) -> {
            if (bill != null) {
                showBillDetails(bill);
            }
        });
    }

    private void saveUpdatedBill(TallyAutomationApiClient.BillRecordDto bill) {
        if (bill == null || bill.id() == null) return;
        UiTaskExecutor.submitAction(
                "update-bill-" + bill.id(),
                () -> api.saveBill(bill),
                saved -> {
                    ToastManager.success(tblBills, "Saved", "Updated voucher " + saved.invoiceNumber());
                    reloadActiveBatch();
                },
                failure -> ToastManager.error(tblBills, "Update Failed", failure.getMessage())
        );
    }

    private void configureLineItemsTable() {
        tblLineItems.setEditable(true);

        colLineNo.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().lineNumber())));
        colItemName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().extractedItemName()));
        colTallyItem.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().mappedTallyStockItem()));
        colHsn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().hsnSac()));
        colQty.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().quantity() != null ? c.getValue().quantity().toPlainString() : "1"));
        colUom.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().uom()));
        colRate.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().rate() != null ? "₹" + c.getValue().rate().toPlainString() : "₹0.00"));
        colLineTaxable.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().taxableAmount() != null ? "₹" + c.getValue().taxableAmount().toPlainString() : "₹0.00"));
        colLineAccuracy.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().lineAccuracyPercentage() != null ? c.getValue().lineAccuracyPercentage().toPlainString() + "%" : "100%"));

        // Allow inline correction of Mapped Tally Stock Item
        colTallyItem.setCellFactory(javafx.scene.control.cell.TextFieldTableCell.forTableColumn());
        colTallyItem.setOnEditCommit(event -> {
            var item = event.getRowValue();
            var bill = tblBills.getSelectionModel().getSelectedItem();
            if (item != null && bill != null && event.getNewValue() != null) {
                List<TallyAutomationApiClient.BillLineItemDto> updatedItems = new ArrayList<>();
                for (var li : bill.lineItems()) {
                    if (Objects.equals(li.lineNumber(), item.lineNumber())) {
                        updatedItems.add(new TallyAutomationApiClient.BillLineItemDto(
                                li.id(), li.billRecordId(), li.lineNumber(), li.extractedItemName(),
                                event.getNewValue().trim(), li.hsnSac(), li.quantity(), li.uom(),
                                li.rate(), li.discountAmount(), li.taxRate(), li.taxableAmount(),
                                li.lineAccuracyPercentage(), li.lineErrorNotes()
                        ));
                    } else {
                        updatedItems.add(li);
                    }
                }
                var updatedBill = new TallyAutomationApiClient.BillRecordDto(
                        bill.id(), bill.batchId(), bill.clientProfileId(), bill.voucherMode(),
                        bill.invoiceNumber(), bill.invoiceDate(), bill.vendorNamePrinted(), bill.vendorGstin(),
                        bill.tallyPartyLedger(), bill.tallyPurchaseLedger(), bill.costCenter(), bill.poReference(),
                        bill.taxableAmount(), bill.cgstAmount(), bill.sgstAmount(), bill.igstAmount(),
                        bill.roundOff(), bill.grandTotal(), bill.accuracyPercentage(), bill.validationNotes(),
                        bill.attachedFilePaths(), bill.status(), bill.tallyVoucherGuid(), updatedItems
                );
                saveUpdatedBill(updatedBill);
            }
        });

        tblLineItems.setItems(lineItemsList);
        // Dynamic responsive column widths per Rule 7
        DynamicTableLayoutManager.install(tblLineItems);
    }

    private void loadClientProfiles() {
        UiTaskExecutor.submitLatest(
                "load-client-profiles",
                api::listProfiles,
                profiles -> {
                    profilesByName.clear();
                    ObservableList<String> names = FXCollections.observableArrayList();
                    for (var p : profiles) {
                        String label = p.clientName() + (p.gstin() != null && !p.gstin().isBlank() ? " (" + p.gstin() + ")" : "");
                        profilesByName.put(label, p);
                        names.add(label);
                    }
                    if (cmbClientProfile != null) {
                        String current = cmbClientProfile.getValue();
                        cmbClientProfile.setItems(names);
                        if (current != null && names.contains(current)) {
                            cmbClientProfile.getSelectionModel().select(current);
                            applyTemplateForClient(profilesByName.get(current));
                        } else if (!names.isEmpty()) {
                            cmbClientProfile.getSelectionModel().selectFirst();
                            applyTemplateForClient(profiles.get(0));
                        }
                    }
                },
                failure -> ToastManager.error(cmbClientProfile, "Profiles Failed", "Could not load client profiles: " + failure.getMessage())
        );
    }

    private void applyTemplateForClient(TallyAutomationApiClient.ClientProfileDto client) {
        if (client == null) return;

        Callable<List<TallyAutomationApiClient.TemplateVersionDto>> loadTask = () -> {
            List<TallyAutomationApiClient.TemplateVersionDto> list = api.listTemplates(client.id());
            if ((list == null || list.isEmpty()) && client.gstin() != null && !client.gstin().isBlank()) {
                list = api.listTemplatesByGstin(client.gstin());
            }
            return list != null ? list : Collections.<TallyAutomationApiClient.TemplateVersionDto>emptyList();
        };

        UiTaskExecutor.submitLatest(
                "load-client-template-" + client.id(),
                loadTask,
                (List<TallyAutomationApiClient.TemplateVersionDto> templates) -> {
                    templatesByLabel.clear();
                    if (cmbTemplateVersion != null) cmbTemplateVersion.getItems().clear();
                    if (templates == null || templates.isEmpty()) {
                        if (lblTemplateVersion != null) {
                            lblTemplateVersion.setText("Template: Standard (Default)");
                            lblTemplateVersion.getStyleClass().setAll("status-badge", "erp-badge-slate");
                        }
                        if (cmbTemplateVersion != null) {
                            ObservableList<String> defaultList = FXCollections.observableArrayList("Standard (Default)");
                            cmbTemplateVersion.setItems(defaultList);
                            cmbTemplateVersion.getSelectionModel().selectFirst();
                        }
                        restoreDefaultBillsColumns();
                        restoreDefaultLineItemColumns();
                        return;
                    }

                    ObservableList<String> labels = FXCollections.observableArrayList();
                    String selectedLabel = null;
                    for (var t : templates) {
                        String lbl = t.versionCode() + (t.isLocked() ? " (Locked)" : " (Draft)");
                        templatesByLabel.put(lbl, t);
                        labels.add(lbl);
                        if (t.isLocked() && selectedLabel == null) {
                            selectedLabel = lbl;
                        }
                    }
                    if (selectedLabel == null && !labels.isEmpty()) {
                        selectedLabel = labels.get(0);
                    }

                    if (cmbTemplateVersion != null) {
                        cmbTemplateVersion.setItems(labels);
                        if (selectedLabel != null) {
                            cmbTemplateVersion.getSelectionModel().select(selectedLabel);
                        }
                    }

                    var template = selectedLabel != null ? templatesByLabel.get(selectedLabel) : templates.get(0);
                    if (lblTemplateVersion != null) {
                        lblTemplateVersion.setText("Template: " + template.versionCode() + (template.isLocked() ? " (Locked)" : " (Draft)"));
                        lblTemplateVersion.getStyleClass().setAll("status-badge", template.isLocked() ? "status-active" : "erp-badge-amber");
                    }

                    rebuildTablesFromTemplate(template);
                },
                err -> log.debug("Template lookup failed: {}", err.getMessage())
        );
    }

    private void applyCleanHeader(TableColumn<?, ?> col, String label, String semantic) {
        if (col == null) return;
        col.setText("");
        col.setGraphic(IconFactory.tableHeader(label, semantic));
        col.getProperties().put("erp-header-preserve", true);
        col.getProperties().put("erp-header-label", label);
        col.getProperties().put("erp-header-semantic", semantic);
    }

    private TableColumn<TallyAutomationApiClient.BillRecordDto, String> createBillColumn(String headerLabel, String canonical) {
        String lower = (headerLabel + " " + (canonical != null ? canonical : "")).toLowerCase(Locale.ROOT);
        TableColumn<TallyAutomationApiClient.BillRecordDto, String> col = new TableColumn<>();

        String semantic = "generic";
        if (lower.contains("invoice no") || lower.contains("inv no") || lower.contains("bill no") || lower.contains("invoice number")) {
            semantic = "invoice";
            col.setCellValueFactory(r -> new SimpleStringProperty(r.getValue().invoiceNumber() != null ? r.getValue().invoiceNumber() : ""));
        } else if (lower.contains("invoice date") || lower.contains("bill date") || (lower.contains("date") && !lower.contains("due"))) {
            semantic = "date";
            col.setCellValueFactory(r -> new SimpleStringProperty(
                    r.getValue().invoiceDate() != null ? r.getValue().invoiceDate().format(DateTimeFormatter.ofPattern("dd-MM-yyyy")) : ""));
        } else if (lower.contains("gstin") || lower.contains("vendor gstin") || lower.contains("gstin/uin") || lower.contains("party gst")) {
            semantic = "gstin";
            col.setCellValueFactory(r -> new SimpleStringProperty(r.getValue().vendorGstin() != null ? r.getValue().vendorGstin() : ""));
        } else if (lower.contains("vendor") || lower.contains("supplier") || lower.contains("party name") || lower.contains("billed to") || lower.contains("billed by")) {
            semantic = "user";
            col.setCellValueFactory(r -> new SimpleStringProperty(r.getValue().vendorNamePrinted() != null ? r.getValue().vendorNamePrinted() : ""));
        } else if (lower.contains("party ledger") || lower.contains("tally party")) {
            semantic = "user";
            col.setCellValueFactory(r -> new SimpleStringProperty(r.getValue().tallyPartyLedger() != null ? r.getValue().tallyPartyLedger() : ""));
        } else if (lower.contains("taxable") || lower.contains("subtotal")) {
            semantic = "currency";
            col.setCellValueFactory(r -> new SimpleStringProperty(r.getValue().taxableAmount() != null ? "₹" + r.getValue().taxableAmount().toPlainString() : "₹0.00"));
        } else if (lower.contains("cgst")) {
            semantic = "percent";
            col.setCellValueFactory(r -> new SimpleStringProperty(r.getValue().cgstAmount() != null ? "₹" + r.getValue().cgstAmount().toPlainString() : "₹0.00"));
        } else if (lower.contains("sgst")) {
            semantic = "percent";
            col.setCellValueFactory(r -> new SimpleStringProperty(r.getValue().sgstAmount() != null ? "₹" + r.getValue().sgstAmount().toPlainString() : "₹0.00"));
        } else if (lower.contains("igst")) {
            semantic = "percent";
            col.setCellValueFactory(r -> new SimpleStringProperty(r.getValue().igstAmount() != null ? "₹" + r.getValue().igstAmount().toPlainString() : "₹0.00"));
        } else if (lower.contains("total") || lower.contains("grand total") || lower.contains("net payable")) {
            semantic = "currency";
            col.setCellValueFactory(r -> new SimpleStringProperty(r.getValue().grandTotal() != null ? "₹" + r.getValue().grandTotal().toPlainString() : "₹0.00"));
        } else if (lower.contains("po") || lower.contains("reference") || lower.contains("remarks")) {
            semantic = "tag";
            col.setCellValueFactory(r -> new SimpleStringProperty(r.getValue().poReference() != null ? r.getValue().poReference() : ""));
        } else {
            semantic = "generic";
            col.setCellValueFactory(r -> new SimpleStringProperty(""));
        }

        applyCleanHeader(col, headerLabel, semantic);
        return col;
    }

    private TableColumn<TallyAutomationApiClient.BillLineItemDto, String> createItemColumn(String headerLabel, String canonical, String dataType) {
        String lower = (headerLabel + " " + (canonical != null ? canonical : "")).toLowerCase(Locale.ROOT);
        TableColumn<TallyAutomationApiClient.BillLineItemDto, String> col = new TableColumn<>();

        String semantic = "generic";
        if (lower.contains("particular") || lower.contains("item name") || lower.contains("item desc") || lower.contains("description")) {
            semantic = "inventory";
            col.setCellValueFactory(r -> new SimpleStringProperty(r.getValue().extractedItemName() != null ? r.getValue().extractedItemName() : ""));
        } else if (lower.contains("mapped tally") || lower.contains("tally item")) {
            semantic = "inventory";
            col.setCellValueFactory(r -> new SimpleStringProperty(r.getValue().mappedTallyStockItem() != null ? r.getValue().mappedTallyStockItem() : ""));
        } else if (lower.contains("hsn") || lower.contains("sac")) {
            semantic = "tag";
            col.setCellValueFactory(r -> new SimpleStringProperty(r.getValue().hsnSac() != null ? r.getValue().hsnSac() : ""));
        } else if (lower.contains("qty") || lower.contains("quantity")) {
            semantic = "quantity";
            col.setCellValueFactory(r -> new SimpleStringProperty(r.getValue().quantity() != null ? r.getValue().quantity().toPlainString() : "1"));
        } else if (lower.contains("unit") || lower.contains("uom")) {
            semantic = "unit";
            col.setCellValueFactory(r -> new SimpleStringProperty(r.getValue().uom() != null ? r.getValue().uom() : ""));
        } else if (lower.contains("rate") || lower.contains("price")) {
            semantic = "currency";
            col.setCellValueFactory(r -> new SimpleStringProperty(r.getValue().rate() != null ? "₹" + r.getValue().rate().toPlainString() : "₹0.00"));
        } else if (lower.contains("gst") || lower.contains("tax%") || lower.contains("tax percentage")) {
            semantic = "percent";
            col.setCellValueFactory(r -> new SimpleStringProperty(r.getValue().taxRate() != null ? r.getValue().taxRate().toPlainString() + "%" : "0%"));
        } else if (lower.contains("taxable") || lower.contains("net amt") || lower.contains("amount") || lower.contains("total")) {
            semantic = "currency";
            col.setCellValueFactory(r -> new SimpleStringProperty(r.getValue().taxableAmount() != null ? "₹" + r.getValue().taxableAmount().toPlainString() : "₹0.00"));
        } else if (lower.contains("discount") || lower.contains("disc")) {
            semantic = "currency";
            col.setCellValueFactory(r -> new SimpleStringProperty(r.getValue().discountAmount() != null ? "₹" + r.getValue().discountAmount().toPlainString() : "₹0.00"));
        } else {
            semantic = "generic";
            col.setCellValueFactory(r -> new SimpleStringProperty(""));
        }

        applyCleanHeader(col, headerLabel, semantic);
        return col;
    }

    private Node createLoadingPlaceholder(String message) {
        VBox box = new VBox(10);
        box.setAlignment(Pos.CENTER);
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(38, 38);
        Label title = new Label(message);
        title.getStyleClass().add("section-title");
        Label sub = new Label("Running native on-device text recognition and math cross-validation...");
        sub.getStyleClass().add("muted-label");
        box.getChildren().addAll(spinner, title, sub);
        return box;
    }

    private void restoreDefaultBillsColumns() {
        applyCleanHeader(colStatus, "Status", "status");
        applyCleanHeader(colAccuracy, "Accuracy", "percent");
        applyCleanHeader(colInvoiceNo, "Invoice No", "invoice");
        applyCleanHeader(colInvoiceDate, "Date", "date");
        applyCleanHeader(colVendorName, "Printed Vendor", "user");
        applyCleanHeader(colVendorGstin, "GSTIN", "gstin");
        applyCleanHeader(colPartyLedger, "Tally Party Ledger", "user");
        applyCleanHeader(colTaxable, "Taxable (₹)", "currency");
        applyCleanHeader(colTotalGst, "GST (₹)", "percent");
        applyCleanHeader(colIgst, "IGST (₹)", "percent");
        applyCleanHeader(colGrandTotal, "Grand Total (₹)", "currency");
        if (colActions != null) {
            applyCleanHeader(colActions, "Actions", "action");
        }

        List<TableColumn<TallyAutomationApiClient.BillRecordDto, ?>> defaultCols = new ArrayList<>(List.of(
                colStatus, colAccuracy, colInvoiceNo, colInvoiceDate, colVendorName, colVendorGstin,
                colPartyLedger, colTaxable, colTotalGst, colIgst, colGrandTotal
        ));
        if (colActions != null && !defaultCols.contains(colActions)) {
            defaultCols.add(colActions);
        }
        tblBills.getColumns().setAll(defaultCols);
        tblBills.setItems(billsList);
        DynamicTableLayoutManager.forceLayout(tblBills);
        tblBills.refresh();
    }

    private void restoreDefaultLineItemColumns() {
        applyCleanHeader(colLineNo, "#", "tag");
        applyCleanHeader(colItemName, "Extracted Item", "inventory");
        applyCleanHeader(colTallyItem, "Mapped Tally Item", "inventory");
        applyCleanHeader(colHsn, "HSN/SAC", "tag");
        applyCleanHeader(colQty, "Qty", "quantity");
        applyCleanHeader(colUom, "Unit", "unit");
        applyCleanHeader(colRate, "Rate (₹)", "currency");
        applyCleanHeader(colLineTaxable, "Taxable (₹)", "currency");
        applyCleanHeader(colLineAccuracy, "Accuracy", "percent");

        List<TableColumn<TallyAutomationApiClient.BillLineItemDto, ?>> defaultCols = new ArrayList<>(List.of(
                colLineNo, colItemName, colTallyItem, colHsn, colQty, colUom, colRate, colLineTaxable, colLineAccuracy
        ));
        tblLineItems.getColumns().setAll(defaultCols);
        tblLineItems.setItems(lineItemsList);
        DynamicTableLayoutManager.forceLayout(tblLineItems);
        tblLineItems.refresh();
    }

    private void rebuildTablesFromTemplate(TallyAutomationApiClient.TemplateVersionDto t) {
        if (t == null) {
            restoreDefaultBillsColumns();
            restoreDefaultLineItemColumns();
            return;
        }

        // 1. Rebuild tblBills columns strictly from headerFieldsJson
        try {
            if (t.headerFieldsJson() != null && !t.headerFieldsJson().isBlank()) {
                com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(t.headerFieldsJson());
                List<com.fasterxml.jackson.databind.JsonNode> nodes = new ArrayList<>();
                if (root.isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode n : root) {
                        nodes.add(n);
                    }
                }

                if (!nodes.isEmpty()) {
                    List<TableColumn<TallyAutomationApiClient.BillRecordDto, ?>> newCols = new ArrayList<>();

                    // Pinned on Left: Status & Accuracy
                    applyCleanHeader(colStatus, "Status", "status");
                    applyCleanHeader(colAccuracy, "Accuracy", "percent");
                    newCols.add(colStatus);
                    newCols.add(colAccuracy);

                    Set<String> seenHeaders = new HashSet<>();
                    for (com.fasterxml.jackson.databind.JsonNode n : nodes) {
                        String h = n.isObject() && n.has("headerLabel") ? n.get("headerLabel").asText()
                                : (n.isObject() && n.has("canonicalField") ? n.get("canonicalField").asText() : n.asText());
                        String canon = n.isObject() && n.has("canonicalField") ? n.get("canonicalField").asText() : "";
                        if (h == null || h.isBlank()) continue;

                        String lower = (h + " " + canon).toLowerCase(Locale.ROOT);
                        if (lower.contains("status") || lower.contains("accuracy") || lower.contains("action")) {
                            continue; // Already pinned
                        }
                        if (!seenHeaders.add(h.toLowerCase(Locale.ROOT))) {
                            continue; // Skip duplicate headers in template
                        }

                        TableColumn<TallyAutomationApiClient.BillRecordDto, String> col = createBillColumn(h, canon);
                        newCols.add(col);
                    }

                    // Pinned on Right: Actions
                    if (colActions != null) {
                        applyCleanHeader(colActions, "Actions", "action");
                        newCols.add(colActions);
                    }

                    tblBills.getColumns().setAll(newCols);
                    tblBills.setItems(billsList);
                    DynamicTableLayoutManager.forceLayout(tblBills);
                    tblBills.refresh();
                } else {
                    restoreDefaultBillsColumns();
                }
            } else {
                restoreDefaultBillsColumns();
            }
        } catch (Exception ex) {
            log.warn("Dynamic bills table reconstruction failed: {}", ex.getMessage(), ex);
            restoreDefaultBillsColumns();
        }

        // 2. Rebuild tblLineItems columns strictly from itemColumnsJson
        try {
            if (t.itemColumnsJson() != null && !t.itemColumnsJson().isBlank()) {
                com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(t.itemColumnsJson());
                List<com.fasterxml.jackson.databind.JsonNode> nodes = new ArrayList<>();
                if (root.isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode n : root) {
                        nodes.add(n);
                    }
                }

                if (!nodes.isEmpty()) {
                    List<TableColumn<TallyAutomationApiClient.BillLineItemDto, ?>> itemCols = new ArrayList<>();

                    applyCleanHeader(colLineNo, "#", "tag");
                    itemCols.add(colLineNo);

                    Set<String> seenCols = new HashSet<>();
                    for (com.fasterxml.jackson.databind.JsonNode node : nodes) {
                        String header = node.has("headerLabel") ? node.get("headerLabel").asText() : (node.has("colHeader") ? node.get("colHeader").asText() : "");
                        String canon = node.path("canonicalField").asText("");
                        String dataType = node.path("dataType").asText("");
                        if (header.isBlank()) continue;

                        String lower = (header + " " + canon).toLowerCase(Locale.ROOT);
                        if (lower.contains("accuracy") || lower.equals("#")) {
                            continue; // Pinned or row number
                        }
                        if (!seenCols.add(header.toLowerCase(Locale.ROOT))) {
                            continue; // Skip duplicate column headers
                        }

                        TableColumn<TallyAutomationApiClient.BillLineItemDto, String> col = createItemColumn(header, canon, dataType);
                        itemCols.add(col);
                    }

                    // Pinned on Right: Line Accuracy
                    applyCleanHeader(colLineAccuracy, "Accuracy", "percent");
                    itemCols.add(colLineAccuracy);

                    tblLineItems.getColumns().setAll(itemCols);
                    tblLineItems.setItems(lineItemsList);
                    DynamicTableLayoutManager.forceLayout(tblLineItems);
                    tblLineItems.refresh();
                } else {
                    restoreDefaultLineItemColumns();
                }
            } else {
                restoreDefaultLineItemColumns();
            }
        } catch (Exception ex) {
            log.warn("Dynamic line items table reconstruction failed: {}", ex.getMessage(), ex);
            restoreDefaultLineItemColumns();
        }
    }

    private void reloadActiveBatch() {
        if (activeBatchId == null) return;
        UiTaskExecutor.submitLatest(
                "tally-bills-load",
                () -> api.listBills(activeBatchId),
                bills -> {
                    tblBills.setItems(billsList);
                    billsList.setAll(bills);
                    if (!bills.isEmpty()) {
                        tblBills.getSelectionModel().selectFirst();
                        var selected = tblBills.getSelectionModel().getSelectedItem();
                        if (selected != null) {
                            showBillDetails(selected);
                        }
                    } else {
                        lineItemsList.clear();
                        lblLineItemBillRef.setText("");
                    }
                    DynamicTableLayoutManager.forceLayout(tblBills);
                    DynamicTableLayoutManager.forceLayout(tblLineItems);
                    tblBills.refresh();
                    tblLineItems.refresh();
                },
                failure -> ToastManager.error(tblBills, "Load Failed", "Could not load batch bills: " + failure.getMessage())
        );
    }

    private void showBillDetails(TallyAutomationApiClient.BillRecordDto bill) {
        if (bill == null) {
            lineItemsList.clear();
            lblLineItemBillRef.setText("");
            return;
        }
        if (bill.lineItems() != null && !bill.lineItems().isEmpty()) {
            lineItemsList.setAll(bill.lineItems());
            lblLineItemBillRef.setText("(" + bill.invoiceNumber() + " - " + bill.lineItems().size() + " items)");
        } else {
            lineItemsList.clear();
            lblLineItemBillRef.setText("(Summary Mode - No line items)");
        }
        tblLineItems.setItems(lineItemsList);
        DynamicTableLayoutManager.forceLayout(tblLineItems);
        tblLineItems.refresh();
    }

    /* =========================================================================
       USER ACTIONS & HANDLERS
       ========================================================================= */

    @FXML
    public void handleUploadBills() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select Purchase Bills & Vouchers");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Supported Invoices", "*.pdf", "*.xlsx", "*.xls", "*.csv", "*.png", "*.jpg", "*.jpeg"),
                new FileChooser.ExtensionFilter("PDF Documents", "*.pdf"),
                new FileChooser.ExtensionFilter("Excel Spreadsheets", "*.xlsx", "*.xls", "*.csv"),
                new FileChooser.ExtensionFilter("Image Receipts", "*.png", "*.jpg", "*.jpeg")
        );

        List<File> files = chooser.showOpenMultipleDialog(tblBills.getScene().getWindow());
        if (files != null && !files.isEmpty()) {
            processUploadedFiles(files);
        }
    }

    private void processUploadedFiles(List<File> files) {
        String profileName = cmbClientProfile != null ? cmbClientProfile.getValue() : null;
        TallyAutomationApiClient.ClientProfileDto profile = (profileName != null) ? profilesByName.get(profileName) : null;
        if (profile == null && profileName != null) {
            for (var p : profilesByName.values()) {
                if (profileName.startsWith(p.clientName()) || p.clientName().startsWith(profileName)) {
                    profile = p;
                    break;
                }
            }
        }
        Long profileId = (profile != null) ? profile.id() : null;
        String defaultLedger = (profile != null) ? profile.clientName() : null;

        String selectedVersionLabel = cmbTemplateVersion != null ? cmbTemplateVersion.getValue() : null;
        TallyAutomationApiClient.TemplateVersionDto activeTemplate = (selectedVersionLabel != null) ? templatesByLabel.get(selectedVersionLabel) : null;

        tblBills.getItems().clear();
        lineItemsList.clear();
        tblBills.setPlaceholder(createLoadingPlaceholder("Extracting " + files.size() + " voucher(s) with native OCR..."));
        Runnable finishUpload = org.example.util.OperationalUiSupport.beginRefresh(btnUploadBills);

        UiTaskExecutor.submitAction(
                "parse-and-ingest-bills",
                () -> {
                    Long batchId = activeBatchId;
                    String username = SessionService.current() != null ? SessionService.current().getUsername() : "System";
                    if (batchId == null) {
                        var batch = api.createBatch(
                                profileId,
                                "Batch " + DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm").format(java.time.LocalDateTime.now()),
                                files.size(),
                                username
                        );
                        batchId = batch.id();
                    }

                    List<TallyAutomationApiClient.BillRecordDto> ingested = new ArrayList<>();
                    List<String> errors = new ArrayList<>();
                    for (File f : files) {
                        try {
                            var record = TallyDocumentParser.parseDocument(f, batchId, profileId, defaultLedger, activeTemplate);
                            var saved = api.saveBill(record);
                            ingested.add(saved);
                        } catch (Exception ex) {
                            log.error("Failed to parse or save voucher {}: {}", f.getName(), ex.getMessage(), ex);
                            errors.add(f.getName() + " (" + ex.getMessage() + ")");
                        }
                    }
                    if (ingested.isEmpty() && !errors.isEmpty()) {
                        throw new RuntimeException("Could not extract vouchers:\n" + String.join("\n", errors));
                    }
                    return ingested;
                },
                ingested -> {
                    finishUpload.run();
                    if (!ingested.isEmpty()) {
                        activeBatchId = ingested.getFirst().batchId();
                    }
                    if (activeTemplate != null) {
                        rebuildTablesFromTemplate(activeTemplate);
                    }
                    tblBills.setItems(billsList);
                    billsList.setAll(ingested);
                    if (!ingested.isEmpty()) {
                        tblBills.getSelectionModel().selectFirst();
                        var selected = tblBills.getSelectionModel().getSelectedItem();
                        if (selected != null) {
                            showBillDetails(selected);
                        }
                    } else {
                        lineItemsList.clear();
                        lblLineItemBillRef.setText("");
                    }
                    DynamicTableLayoutManager.forceLayout(tblBills);
                    DynamicTableLayoutManager.forceLayout(tblLineItems);
                    tblBills.refresh();
                    tblLineItems.refresh();
                },
                failure -> {
                    finishUpload.run();
                    Label emptyLbl = new Label("Ingestion failed: " + failure.getMessage());
                    emptyLbl.getStyleClass().add("muted-label");
                    tblBills.setPlaceholder(emptyLbl);
                    ToastManager.error(tblBills, "Ingestion Failed", failure.getMessage());
                }
        );
    }

    @FXML
    public void handleDeleteBill() {
        var bill = tblBills.getSelectionModel().getSelectedItem();
        if (bill == null || bill.id() == null) {
            ToastManager.info(tblBills, "Select Voucher", "Please select a bill from the table to delete.");
            return;
        }

        boolean confirm = AppDialogService.confirm(
                tblBills,
                "Delete Voucher",
                "Delete voucher " + bill.invoiceNumber() + "?",
                "Are you sure you want to remove this extracted bill from the current batch?"
        );
        if (!confirm) return;

        UiTaskExecutor.submitAction(
                "delete-bill-" + bill.id(),
                () -> {
                    api.deleteBill(bill.id());
                    return true;
                },
                ok -> {
                    billsList.remove(bill);
                    lineItemsList.clear();
                    ToastManager.success(tblBills, "Deleted", "Removed voucher " + bill.invoiceNumber());
                },
                failure -> ToastManager.error(tblBills, "Delete Failed", failure.getMessage())
        );
    }

    @FXML
    public void handleRecalculateAccuracy() {
        if (activeBatchId == null) {
            ToastManager.info(tblBills, "No Batch", "Please upload or select bills before re-calculating math.");
            return;
        }

        UiTaskExecutor.submitAction(
                "recalculate-math",
                () -> {
                    List<TallyAutomationApiClient.BillRecordDto> updated = new ArrayList<>();
                    for (var b : billsList) {
                        updated.add(api.saveBill(b));
                    }
                    return updated;
                },
                recalculated -> {
                    billsList.setAll(recalculated);
                    ToastManager.success(tblBills, "Math Re-Validated", "Cross-validation re-calculated for " + recalculated.size() + " vouchers.");
                },
                failure -> ToastManager.error(tblBills, "Calculation Error", failure.getMessage())
        );
    }

    @FXML
    public void handleExportJson() {
        if (activeBatchId == null || billsList.isEmpty()) {
            ToastManager.info(tblBills, "No Batch", "Please upload bills before exporting.");
            return;
        }

        UiTaskExecutor.submitAction(
                "prepare-tally-json",
                () -> api.exportToTally(new TallyAutomationApiClient.TallyExportRequest(activeBatchId, null, "JSON", null)),
                resp -> {
                    String json = resp.payloadContent() != null ? resp.payloadContent() : "{}";
                    TallyExportPreviewDialog.show(
                            tblBills,
                            TallyExportPreviewDialog.ExportType.TALLY_JSON,
                            activeBatchId,
                            billsList,
                            json,
                            null,
                            null
                    );
                },
                failure -> ToastManager.error(tblBills, "Export Error", failure.getMessage())
        );
    }

    @FXML
    public void handleExportGstPortal() {
        if (activeBatchId == null || billsList.isEmpty()) {
            ToastManager.info(tblBills, "No Batch", "Please upload bills before exporting.");
            return;
        }

        long invalidCount = billsList.stream().filter(b -> !"EXTRACTED".equalsIgnoreCase(b.status())
                || (b.accuracyPercentage() != null && b.accuracyPercentage().compareTo(new BigDecimal("90.00")) < 0)).count();
        if (invalidCount > 0) {
            ToastManager.error(tblBills, "Accuracy Verification Failed",
                    invalidCount + " bill(s) require review or have math discrepancies. Resolve before exporting to GST Portal.");
            return;
        }

        UiTaskExecutor.submitAction(
                "prepare-gst-portal-json",
                () -> api.exportToTally(new TallyAutomationApiClient.TallyExportRequest(activeBatchId, null, "GST_PORTAL", null)),
                resp -> {
                    String json = resp.payloadContent() != null ? resp.payloadContent() : "{}";
                    TallyExportPreviewDialog.show(
                            tblBills,
                            TallyExportPreviewDialog.ExportType.GST_PORTAL_JSON,
                            activeBatchId,
                            billsList,
                            json,
                            null,
                            null
                    );
                },
                failure -> ToastManager.error(tblBills, "Export Error", failure.getMessage())
        );
    }

    @FXML
    public void handleExportExcel() {
        if (activeBatchId == null || billsList.isEmpty()) {
            ToastManager.info(tblBills, "No Batch", "Please upload bills before exporting.");
            return;
        }

        UiTaskExecutor.submitAction(
                "prepare-tally-excel",
                () -> generateExcelBytes(billsList),
                excelBytes -> {
                    TallyExportPreviewDialog.show(
                            tblBills,
                            TallyExportPreviewDialog.ExportType.TALLY_EXCEL,
                            activeBatchId,
                            billsList,
                            null,
                            excelBytes,
                            null
                    );
                },
                failure -> ToastManager.error(tblBills, "Excel Error", failure.getMessage())
        );
    }

    @FXML
    public void handlePushToTally() {
        if (activeBatchId == null || billsList.isEmpty()) {
            ToastManager.info(tblBills, "Empty Batch", "No bills available to push to TallyPrime.");
            return;
        }

        long invalidCount = billsList.stream().filter(b -> !"EXTRACTED".equalsIgnoreCase(b.status())
                || (b.accuracyPercentage() != null && b.accuracyPercentage().compareTo(new BigDecimal("90.00")) < 0)).count();
        if (invalidCount > 0) {
            ToastManager.error(tblBills, "Accuracy Verification Failed",
                    invalidCount + " bill(s) require review or have math discrepancies. Resolve before pushing to TallyPrime.");
            return;
        }

        String targetUrl = txtTallyUrl.getText().trim();
        if (targetUrl.isBlank()) {
            ToastManager.error(tblBills, "URL Required", "Please specify TallyPrime HTTP port (e.g. http://localhost:9000).");
            return;
        }

        UiTaskExecutor.submitAction(
                "prepare-tally-push-preview",
                () -> api.exportToTally(new TallyAutomationApiClient.TallyExportRequest(activeBatchId, null, "XML", targetUrl)),
                resp -> {
                    String xml = resp.payloadContent() != null ? resp.payloadContent() : "<ENVELOPE/>";
                    TallyExportPreviewDialog.show(
                            tblBills,
                            TallyExportPreviewDialog.ExportType.TALLY_PUSH,
                            activeBatchId,
                            billsList,
                            xml,
                            null,
                            confirmed -> executePushToTally(targetUrl)
                    );
                },
                failure -> ToastManager.error(tblBills, "Push Preparation Error", failure.getMessage())
        );
    }

    private void executePushToTally(String targetUrl) {
        UiTaskExecutor.submitAction(
                "push-to-tally",
                () -> api.exportToTally(new TallyAutomationApiClient.TallyExportRequest(activeBatchId, null, "HTTP", targetUrl)),
                resp -> {
                    if (resp.success()) {
                        ToastManager.success(tblBills, "Pushed to Tally", "Vouchers created successfully in TallyPrime!");
                        reloadActiveBatch();
                    } else {
                        ToastManager.error(tblBills, "Tally Response", resp.message());
                    }
                },
                failure -> {
                    if (failure.getCause() instanceof ConnectException || failure instanceof ConnectException) {
                        ToastManager.error(tblBills, "Tally Connection Error",
                                "Could not connect to TallyPrime at " + targetUrl + ". Ensure TallyPrime is running with ODBC/HTTP Server enabled on port 9000.");
                    } else {
                        ToastManager.error(tblBills, "Tally Push Error", failure.getMessage());
                    }
                }
        );
    }

    private byte[] generateExcelBytes(List<TallyAutomationApiClient.BillRecordDto> bills) {
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("Purchase Vouchers");
            org.apache.poi.ss.usermodel.Row header = sheet.createRow(0);
            String[] cols = {"Invoice No", "Date", "Vendor Name", "Vendor GSTIN", "Party Ledger", "Purchase Ledger", "Taxable Amount", "CGST", "SGST", "IGST", "Grand Total"};
            for (int i = 0; i < cols.length; i++) {
                header.createCell(i).setCellValue(cols[i]);
            }
            int rowIdx = 1;
            for (var b : bills) {
                org.apache.poi.ss.usermodel.Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(b.invoiceNumber() != null ? b.invoiceNumber() : "");
                row.createCell(1).setCellValue(b.invoiceDate() != null ? b.invoiceDate().toString() : "");
                row.createCell(2).setCellValue(b.vendorNamePrinted() != null ? b.vendorNamePrinted() : "");
                row.createCell(3).setCellValue(b.vendorGstin() != null ? b.vendorGstin() : "");
                row.createCell(4).setCellValue(b.tallyPartyLedger() != null ? b.tallyPartyLedger() : "");
                row.createCell(5).setCellValue(b.tallyPurchaseLedger() != null ? b.tallyPurchaseLedger() : "");
                row.createCell(6).setCellValue(b.taxableAmount() != null ? b.taxableAmount().doubleValue() : 0.0);
                row.createCell(7).setCellValue(b.cgstAmount() != null ? b.cgstAmount().doubleValue() : 0.0);
                row.createCell(8).setCellValue(b.sgstAmount() != null ? b.sgstAmount().doubleValue() : 0.0);
                row.createCell(9).setCellValue(b.igstAmount() != null ? b.igstAmount().doubleValue() : 0.0);
                row.createCell(10).setCellValue(b.grandTotal() != null ? b.grandTotal().doubleValue() : 0.0);
            }
            for (int i = 0; i < cols.length; i++) sheet.autoSizeColumn(i);
            workbook.write(bos);
            return bos.toByteArray();
        } catch (Exception ex) {
            log.error("Failed generating Excel bytes: {}", ex.getMessage());
            return new byte[0];
        }
    }
}
