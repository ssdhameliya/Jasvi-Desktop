package org.example.controller;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
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

public class BillToTallyController implements ScreenLifecycle {
    private static final Logger log = LoggerFactory.getLogger(BillToTallyController.class);

    @FXML private StackPane headerIconHolder;
    @FXML private ComboBox<String> cmbClientProfile;
    @FXML private Button btnNewClient;
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

    private Long activeBatchId = null;

    @FXML
    public void initialize() {
        if (headerIconHolder != null) {
            headerIconHolder.getChildren().setAll(IconFactory.icon("excel", 24));
        }

        if (btnUploadBills != null) btnUploadBills.setGraphic(IconFactory.compactIcon("add", 14));
        if (btnNewClient != null) btnNewClient.setGraphic(IconFactory.compactIcon("supplier", 14));
        if (btnDeleteBill != null) btnDeleteBill.setGraphic(IconFactory.compactIcon("delete", 14));
        if (btnRecalculate != null) btnRecalculate.setGraphic(IconFactory.compactIcon("refresh", 14));
        if (btnExportJson != null) btnExportJson.setGraphic(IconFactory.compactIcon("file", 14));
        if (btnExportGstPortal != null) btnExportGstPortal.setGraphic(IconFactory.compactIcon("file", 14));
        if (btnExportExcel != null) btnExportExcel.setGraphic(IconFactory.compactIcon("excel", 14));
        if (btnPushTally != null) btnPushTally.setGraphic(IconFactory.compactIcon("send", 14));

        configureBillsTable();
        configureLineItemsTable();
        loadClientProfiles();
    }

    @Override
    public void onScreenShown(boolean reusedFromCache) {
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
    }

    private void loadClientProfiles() {
        UiTaskExecutor.submitAction(
                "load-client-profiles",
                api::listProfiles,
                profiles -> {
                    profilesByName.clear();
                    ObservableList<String> names = FXCollections.observableArrayList();
                    for (var p : profiles) {
                        profilesByName.put(p.clientName(), p);
                        names.add(p.clientName());
                    }
                    cmbClientProfile.setItems(names);
                    if (!names.isEmpty()) {
                        cmbClientProfile.getSelectionModel().selectFirst();
                    }
                },
                failure -> ToastManager.error(cmbClientProfile, "Profiles Failed", "Could not load client profiles: " + failure.getMessage())
        );
    }

    private void reloadActiveBatch() {
        if (activeBatchId == null) return;
        UiTaskExecutor.submitAction(
                "tally-bills-load",
                () -> api.listBills(activeBatchId),
                bills -> {
                    billsList.setAll(bills);
                    if (!bills.isEmpty()) {
                        tblBills.getSelectionModel().selectFirst();
                    } else {
                        lineItemsList.clear();
                    }
                },
                failure -> ToastManager.error(tblBills, "Load Failed", "Could not load batch bills: " + failure.getMessage())
        );
    }

    private void showBillDetails(TallyAutomationApiClient.BillRecordDto bill) {
        if (bill.lineItems() != null && !bill.lineItems().isEmpty()) {
            lineItemsList.setAll(bill.lineItems());
            lblLineItemBillRef.setText("(" + bill.invoiceNumber() + " - " + bill.lineItems().size() + " items)");
        } else {
            lineItemsList.clear();
            lblLineItemBillRef.setText("(Summary Mode - No line items)");
        }
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
        String profileName = cmbClientProfile.getValue();
        TallyAutomationApiClient.ClientProfileDto profile = (profileName != null) ? profilesByName.get(profileName) : null;
        Long profileId = (profile != null) ? profile.id() : null;
        String defaultLedger = (profile != null) ? profile.clientName() : null;

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
                    for (File f : files) {
                        try {
                            var record = TallyDocumentParser.parseDocument(f, batchId, profileId, defaultLedger);
                            var saved = api.saveBill(record);
                            ingested.add(saved);
                        } catch (Exception ex) {
                            log.error("Failed to parse or save voucher {}: {}", f.getName(), ex.getMessage());
                        }
                    }
                    return ingested;
                },
                ingested -> {
                    if (!ingested.isEmpty()) {
                        activeBatchId = ingested.getFirst().batchId();
                    }
                    billsList.setAll(ingested);
                    if (!ingested.isEmpty()) {
                        tblBills.getSelectionModel().selectFirst();
                    }
                    ToastManager.success(tblBills, "Ingestion Complete", "Loaded " + ingested.size() + " vouchers successfully.");
                },
                failure -> ToastManager.error(tblBills, "Ingestion Failed", failure.getMessage())
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

    @FXML
    public void handleNewClientProfile() {
        TextInputDialog d = new org.example.util.OwnedTextInputDialog();
        d.setTitle("New Client Profile");
        d.setHeaderText("Create Client Profile for Bill-to-Tally");
        d.setContentText("Client Company Name:");
        Optional<String> res = d.showAndWait();
        if (res.isPresent() && !res.get().isBlank()) {
            String name = res.get().trim();
            UiTaskExecutor.submitAction(
                    "create-client-profile",
                    () -> api.saveProfile(new TallyAutomationApiClient.ClientProfileDto(
                            null, name, name, "24AABCA0000A1Z1", name, name, "", "ACTIVE", "Regular", "24", "ITEM",
                            "Purchase Account", "Round Off", true, true, false, false, "{}"
                    )),
                    created -> {
                        loadClientProfiles();
                        ToastManager.success(tblBills, "Profile Created", "Profile '" + created.clientName() + "' created.");
                    },
                    failure -> ToastManager.error(tblBills, "Profile Error", failure.getMessage())
            );
        }
    }
}
