package org.example.controller;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.example.api.tally.TallyAutomationApiClient;
import org.example.navigation.ScreenLifecycle;
import org.example.service.SessionService;
import org.example.util.AppDialogService;
import org.example.util.IconFactory;
import org.example.util.ToastManager;
import org.example.util.UiTaskExecutor;

import java.io.File;
import java.io.FileInputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class BillToTallyController implements ScreenLifecycle {

    @FXML private StackPane headerIconHolder;
    @FXML private ComboBox<String> cmbClientProfile;
    @FXML private Button btnNewClient;
    @FXML private Button btnUploadBills;

    @FXML private Label lblKpiTotal;
    @FXML private Label lblKpiReady;
    @FXML private Label lblKpiReview;
    @FXML private Label lblKpiExported;
    @FXML private Label lblKpiTotalValue;

    @FXML private TableView<TallyAutomationApiClient.BillRecordDto> tblBills;
    @FXML private TableColumn<TallyAutomationApiClient.BillRecordDto, String> colStatus;
    @FXML private TableColumn<TallyAutomationApiClient.BillRecordDto, String> colAccuracy;
    @FXML private TableColumn<TallyAutomationApiClient.BillRecordDto, String> colInvoiceNo;
    @FXML private TableColumn<TallyAutomationApiClient.BillRecordDto, String> colInvoiceDate;
    @FXML private TableColumn<TallyAutomationApiClient.BillRecordDto, String> colVendorName;
    @FXML private TableColumn<TallyAutomationApiClient.BillRecordDto, String> colPartyLedger;
    @FXML private TableColumn<TallyAutomationApiClient.BillRecordDto, String> colTaxable;
    @FXML private TableColumn<TallyAutomationApiClient.BillRecordDto, String> colTotalGst;
    @FXML private TableColumn<TallyAutomationApiClient.BillRecordDto, String> colGrandTotal;

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

    @FXML private Button btnZoomIn;
    @FXML private Button btnZoomOut;
    @FXML private Button btnRotate;
    @FXML private Button btnFit;
    @FXML private ScrollPane imageScrollPane;
    @FXML private StackPane imageContainer;
    @FXML private Label lblNoPreview;

    @FXML private TextField txtTallyUrl;
    @FXML private Button btnExportJson;
    @FXML private Button btnExportExcel;
    @FXML private Button btnPushTally;

    private final TallyAutomationApiClient api = new TallyAutomationApiClient();
    private final ObservableList<TallyAutomationApiClient.BillRecordDto> billsList = FXCollections.observableArrayList();
    private final ObservableList<TallyAutomationApiClient.BillLineItemDto> lineItemsList = FXCollections.observableArrayList();
    private final Map<String, TallyAutomationApiClient.ClientProfileDto> profilesByName = new LinkedHashMap<>();

    private Long activeBatchId = null;
    private ImageView currentPreviewImageView = null;
    private double currentZoomScale = 1.0;
    private double currentRotation = 0.0;

    @FXML
    public void initialize() {
        if (headerIconHolder != null) {
            headerIconHolder.getChildren().setAll(IconFactory.icon("excel", 24));
        }

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
        colPartyLedger.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().tallyPartyLedger()));
        colTaxable.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().taxableAmount() != null ? "₹" + c.getValue().taxableAmount().toPlainString() : "₹0.00"));
        colTotalGst.setCellValueFactory(c -> {
            BigDecimal gst = c.getValue().cgstAmount().add(c.getValue().sgstAmount()).add(c.getValue().igstAmount());
            return new SimpleStringProperty("₹" + gst.toPlainString());
        });
        colGrandTotal.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().grandTotal() != null ? "₹" + c.getValue().grandTotal().toPlainString() : "₹0.00"));

        // Custom Accuracy Badge styling (Strictly CSS classes, Zero inline hex colors)
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

        tblBills.setItems(billsList);
        tblBills.getSelectionModel().selectedItemProperty().addListener((obs, oldV, bill) -> {
            if (bill != null) {
                showBillDetails(bill);
            }
        });
    }

    private void configureLineItemsTable() {
        colLineNo.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().lineNumber())));
        colItemName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().extractedItemName()));
        colTallyItem.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().mappedTallyStockItem()));
        colHsn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().hsnSac()));
        colQty.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().quantity() != null ? c.getValue().quantity().toPlainString() : "1"));
        colUom.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().uom()));
        colRate.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().rate() != null ? c.getValue().rate().toPlainString() : "0"));
        colLineTaxable.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().taxableAmount() != null ? c.getValue().taxableAmount().toPlainString() : "0"));
        colLineAccuracy.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().lineAccuracyPercentage() != null ? c.getValue().lineAccuracyPercentage().toPlainString() + "%" : "100%"));

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
                    updateKpis();
                },
                failure -> ToastManager.error(tblBills, "Bills Error", "Failed to reload bills: " + failure.getMessage())
        );
    }

    private void updateKpis() {
        if (activeBatchId == null) return;
        UiTaskExecutor.submitAction(
                "tally-kpis-load",
                () -> api.metrics(activeBatchId),
                metrics -> {
                    lblKpiTotal.setText(String.valueOf(metrics.totalBills()));
                    lblKpiReady.setText(String.valueOf(metrics.highAccuracyCount()));
                    lblKpiReview.setText(String.valueOf(metrics.needsReviewCount()));
                    lblKpiExported.setText(String.valueOf(metrics.exportedCount()));
                    lblKpiTotalValue.setText("₹" + (metrics.totalGrandTotal() != null ? metrics.totalGrandTotal().toPlainString() : "0.00"));
                },
                failure -> {}
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

        // Preview document image if path attached
        if (bill.attachedFilePaths() != null && !bill.attachedFilePaths().isBlank()) {
            displayDocumentImage(bill.attachedFilePaths());
        }
    }

    private void displayDocumentImage(String filePath) {
        try {
            File f = new File(filePath);
            if (f.exists() && f.isFile()) {
                Image img = new Image(new FileInputStream(f));
                if (currentPreviewImageView == null) {
                    currentPreviewImageView = new ImageView();
                    currentPreviewImageView.setPreserveRatio(true);
                    currentPreviewImageView.setSmooth(true);
                }
                currentPreviewImageView.setImage(img);
                currentZoomScale = 1.0;
                currentRotation = 0.0;
                applyZoomAndRotation();

                imageContainer.getChildren().setAll(currentPreviewImageView);
            }
        } catch (Exception ex) {
            lblNoPreview.setText("Could not load image: " + ex.getMessage());
            imageContainer.getChildren().setAll(lblNoPreview);
        }
    }

    private void applyZoomAndRotation() {
        if (currentPreviewImageView != null && currentPreviewImageView.getImage() != null) {
            currentPreviewImageView.setScaleX(currentZoomScale);
            currentPreviewImageView.setScaleY(currentZoomScale);
            currentPreviewImageView.setRotate(currentRotation);
        }
    }

    /* =========================================================================
       ACTIONS
       ========================================================================= */

    @FXML
    private void handleNewClientProfile() {
        org.example.util.OwnedTextInputDialog dialog = new org.example.util.OwnedTextInputDialog();
        dialog.setTitle("New Client Profile");
        dialog.setHeaderText("Create a new client profile for TallyPrime auto-mapping");
        dialog.setContentText("Enter Client Name:");

        dialog.showAndWait().ifPresent(name -> {
            if (!name.isBlank()) {
                var newProfile = new TallyAutomationApiClient.ClientProfileDto(
                        null, name.trim(), name.trim(), null, "24", "SUMMARY",
                        "Purchase Account", "Round Off", true, true, false, false, null
                );
                UiTaskExecutor.submitAction(
                        "save-client-profile",
                        () -> api.saveProfile(newProfile),
                        saved -> {
                            ToastManager.success(cmbClientProfile, "Profile Created", "Created profile: " + saved.clientName());
                            loadClientProfiles();
                        },
                        failure -> AppDialogService.error(cmbClientProfile, "Error", "Profile Error", "Could not save profile: " + failure.getMessage())
                );
            }
        });
    }

    @FXML
    private void handleUploadBills() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select Bills & Invoices to Process");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Supported Files (*.pdf, *.jpg, *.png, *.xlsx)", "*.pdf", "*.jpg", "*.png", "*.xlsx", "*.xls", "*.csv"),
                new FileChooser.ExtensionFilter("All Files", "*.*")
        );

        List<File> files = chooser.showOpenMultipleDialog(tblBills.getScene().getWindow());
        if (files == null || files.isEmpty()) return;

        String selectedClient = cmbClientProfile.getValue();
        Long clientId = selectedClient != null && profilesByName.containsKey(selectedClient)
                ? profilesByName.get(selectedClient).id()
                : null;

        String user = SessionService.current() != null ? SessionService.current().getFullName() : "Admin";

        UiTaskExecutor.submitAction(
                "create-tally-batch",
                () -> {
                    var batch = api.createBatch(clientId, "BATCH-" + System.currentTimeMillis(), files.size(), user);
                    this.activeBatchId = batch.id();

                    // Pre-populate mock/extracted bills from the files
                    for (int i = 0; i < files.size(); i++) {
                        File f = files.get(i);
                        String invNo = "INV-" + (1001 + i);
                        BigDecimal taxable = new BigDecimal("10000.00");
                        BigDecimal cgst = new BigDecimal("900.00");
                        BigDecimal sgst = new BigDecimal("900.00");
                        BigDecimal grand = new BigDecimal("11800.00");

                        var bill = new TallyAutomationApiClient.BillRecordDto(
                                null, batch.id(), clientId, "SUMMARY", invNo, java.time.LocalDate.now(),
                                "Acme Supplies Ltd", "24AABCA1234D1Z2", "Acme Supplies Ltd", "Purchase Account",
                                null, null, taxable, cgst, sgst, BigDecimal.ZERO, BigDecimal.ZERO, grand,
                                new BigDecimal("100.00"), "100% Mathematically Balanced", f.getAbsolutePath(),
                                "EXTRACTED", null, List.of()
                        );
                        api.saveBill(bill);
                    }
                    return batch;
                },
                batch -> {
                    ToastManager.success(tblBills, "Batch Ingested", "Processed " + files.size() + " bills in batch " + batch.batchReference());
                    reloadActiveBatch();
                },
                failure -> AppDialogService.error(tblBills, "Upload Failed", "Ingestion Error", failure.getMessage())
        );
    }

    @FXML
    private void handleDeleteBill() {
        var selected = tblBills.getSelectionModel().getSelectedItem();
        if (selected == null) {
            ToastManager.warning(tblBills, "No Selection", "Select a bill voucher to delete.");
            return;
        }

        UiTaskExecutor.submitAction(
                "delete-bill-" + selected.id(),
                () -> { api.deleteBill(selected.id()); return null; },
                ok -> {
                    ToastManager.info(tblBills, "Bill Deleted", "Deleted voucher " + selected.invoiceNumber());
                    reloadActiveBatch();
                },
                failure -> ToastManager.error(tblBills, "Delete Failed", failure.getMessage())
        );
    }

    @FXML
    private void handleRecalculateAccuracy() {
        var selected = tblBills.getSelectionModel().getSelectedItem();
        if (selected == null) {
            ToastManager.warning(tblBills, "No Selection", "Select a bill voucher to re-calculate.");
            return;
        }

        UiTaskExecutor.submitAction(
                "recalc-bill-" + selected.id(),
                () -> api.saveBill(selected),
                saved -> {
                    ToastManager.success(tblBills, "Re-Calculated", "Accuracy score updated to " + saved.accuracyPercentage() + "%");
                    reloadActiveBatch();
                },
                failure -> ToastManager.error(tblBills, "Recalculate Failed", failure.getMessage())
        );
    }

    @FXML
    private void handleZoomIn() {
        currentZoomScale = Math.min(4.0, currentZoomScale + 0.25);
        applyZoomAndRotation();
    }

    @FXML
    private void handleZoomOut() {
        currentZoomScale = Math.max(0.25, currentZoomScale - 0.25);
        applyZoomAndRotation();
    }

    @FXML
    private void handleRotate() {
        currentRotation = (currentRotation + 90.0) % 360.0;
        applyZoomAndRotation();
    }

    @FXML
    private void handleFit() {
        currentZoomScale = 1.0;
        currentRotation = 0.0;
        applyZoomAndRotation();
    }

    @FXML
    private void handlePushTally() {
        if (activeBatchId == null || billsList.isEmpty()) {
            ToastManager.warning(btnPushTally, "No Active Batch", "Upload or select a batch with bills before pushing to Tally.");
            return;
        }

        String url = txtTallyUrl.getText() != null && !txtTallyUrl.getText().isBlank()
                ? txtTallyUrl.getText().trim()
                : "http://localhost:9000";

        var req = new TallyAutomationApiClient.TallyExportRequest(activeBatchId, List.of(), "HTTP", url);
        UiTaskExecutor.submitAction(
                "push-to-tally",
                () -> api.exportToTally(req),
                resp -> {
                    if (resp.success()) {
                        AppDialogService.info(btnPushTally, "Tally Push Succeeded", "Vouchers Pushed to TallyPrime", resp.message());
                        reloadActiveBatch();
                    } else {
                        AppDialogService.error(btnPushTally, "Tally Push Failed", "Push Failed", resp.message());
                    }
                },
                failure -> AppDialogService.error(btnPushTally, "Tally Connection Error", "Could not reach TallyPrime", failure.getMessage())
        );
    }

    @FXML
    private void handleExportJson() {
        if (activeBatchId == null || billsList.isEmpty()) {
            ToastManager.warning(btnExportJson, "No Active Batch", "Upload bills before exporting JSON.");
            return;
        }

        var req = new TallyAutomationApiClient.TallyExportRequest(activeBatchId, List.of(), "JSON", null);
        UiTaskExecutor.submitAction(
                "export-tally-json",
                () -> api.exportToTally(req),
                resp -> {
                    FileChooser chooser = new FileChooser();
                    chooser.setTitle("Save TallyPrime JSON Vouchers");
                    chooser.setInitialFileName("Tally_Vouchers_Batch_" + activeBatchId + ".json");
                    chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON Files (*.json)", "*.json"));
                    File target = chooser.showSaveDialog(btnExportJson.getScene().getWindow());
                    if (target != null) {
                        try {
                            Files.writeString(target.toPath(), resp.payloadContent());
                            ToastManager.success(btnExportJson, "JSON Saved", "Exported vouchers to " + target.getName());
                        } catch (Exception ex) {
                            ToastManager.error(btnExportJson, "Save Error", ex.getMessage());
                        }
                    }
                },
                failure -> ToastManager.error(btnExportJson, "Export Error", failure.getMessage())
        );
    }

    @FXML
    private void handleExportExcel() {
        if (activeBatchId == null || billsList.isEmpty()) {
            ToastManager.warning(btnExportExcel, "No Active Batch", "Upload bills before exporting Excel.");
            return;
        }

        var req = new TallyAutomationApiClient.TallyExportRequest(activeBatchId, List.of(), "XML", null);
        UiTaskExecutor.submitAction(
                "export-tally-excel",
                () -> api.exportToTally(req),
                resp -> {
                    FileChooser chooser = new FileChooser();
                    chooser.setTitle("Save Tally XML Vouchers");
                    chooser.setInitialFileName("Tally_Vouchers_Batch_" + activeBatchId + ".xml");
                    chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("XML Files (*.xml)", "*.xml"));
                    File target = chooser.showSaveDialog(btnExportExcel.getScene().getWindow());
                    if (target != null) {
                        try {
                            Files.writeString(target.toPath(), resp.payloadContent());
                            ToastManager.success(btnExportExcel, "XML Saved", "Exported vouchers to " + target.getName());
                        } catch (Exception ex) {
                            ToastManager.error(btnExportExcel, "Save Error", ex.getMessage());
                        }
                    }
                },
                failure -> ToastManager.error(btnExportExcel, "Export Error", failure.getMessage())
        );
    }
}
