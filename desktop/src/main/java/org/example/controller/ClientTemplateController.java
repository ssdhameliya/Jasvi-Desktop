package org.example.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import org.example.api.tally.TallyAutomationApiClient;
import org.example.navigation.NavigationManager;
import org.example.navigation.ScreenLifecycle;
import org.example.tally.TallyDocumentInspectionDialog;
import org.example.tally.WindowsOcrEngine;
import org.example.util.*;
import org.example.tally.GstFullDetailsDialog;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;

public class ClientTemplateController implements ScreenLifecycle {
    private static final Logger log = LoggerFactory.getLogger(ClientTemplateController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Long preselectedClientId = null;

    public static void setSelectedClientId(Long id) {
        preselectedClientId = id;
    }

    @FXML private StackPane pageIcon;
    @FXML private Button btnLockTemplate;
    @FXML private Button btnBackToDirectory;
    @FXML private ComboBox<TallyAutomationApiClient.ClientProfileDto> cmbClientProfile;
    @FXML private Button btnUploadSample;
    @FXML private Button btnViewTemplateDoc;

    @FXML private Label lblFirmName;
    @FXML private Label lblGstin;
    @FXML private Label lblAddress;
    @FXML private Label lblGstStatus;
    @FXML private Label lblLockStatus;
    @FXML private HBox boxVersionPills;

    @FXML private TableView<DocumentHeaderRow> tableDocumentHeaders;
    @FXML private TableColumn<DocumentHeaderRow, Number> colHdrOrder;
    @FXML private TableColumn<DocumentHeaderRow, String> colHdrLabel;
    @FXML private TableColumn<DocumentHeaderRow, String> colHdrCanonical;
    @FXML private TableColumn<DocumentHeaderRow, String> colHdrSample;
    @FXML private TableColumn<DocumentHeaderRow, Boolean> colHdrRequired;
    @FXML private TableColumn<DocumentHeaderRow, Void> colHdrActions;
    @FXML private Button btnAddHeaderField;
    @FXML private Button btnResetHeaders;

    @FXML private TableView<ColumnRow> tableColumnsBlueprint;
    @FXML private TableColumn<ColumnRow, Number> colOrder;
    @FXML private TableColumn<ColumnRow, String> colHeaderLabel;
    @FXML private TableColumn<ColumnRow, String> colCanonicalField;
    @FXML private TableColumn<ColumnRow, String> colDataType;
    @FXML private TableColumn<ColumnRow, Boolean> colRequired;
    @FXML private TableColumn<ColumnRow, Void> colActions;
    @FXML private Button btnAddColumn;
    @FXML private Button btnReDetect;

    private final TallyAutomationApiClient api = new TallyAutomationApiClient();
    private final ObservableList<ColumnRow> columnsList = FXCollections.observableArrayList();
    private final ObservableList<DocumentHeaderRow> documentHeadersList = FXCollections.observableArrayList();



    public static final class DocumentHeaderRow {

        final SimpleIntegerProperty order = new SimpleIntegerProperty();

        final SimpleStringProperty headerLabel = new SimpleStringProperty();

        final SimpleStringProperty canonicalField = new SimpleStringProperty();

        final SimpleStringProperty sampleValue = new SimpleStringProperty();

        final SimpleBooleanProperty isRequired = new SimpleBooleanProperty();



        public DocumentHeaderRow(int order, String headerLabel, String canonicalField, String sampleValue, boolean isRequired) {

            this.order.set(order);

            this.headerLabel.set(headerLabel);

            this.canonicalField.set(canonicalField);

            this.sampleValue.set(sampleValue != null ? sampleValue : "—");

            this.isRequired.set(isRequired);

        }



        public int getOrder() { return order.get(); }

        public String getHeaderLabel() { return headerLabel.get(); }

        public String getCanonicalField() { return canonicalField.get(); }

        public String getSampleValue() { return sampleValue.get(); }

        public boolean isRequired() { return isRequired.get(); }

    }
    private final List<TallyAutomationApiClient.TemplateVersionDto> currentVersions = new ArrayList<>();

    private TallyAutomationApiClient.ClientProfileDto activeClient = null;
    private TallyAutomationApiClient.TemplateVersionDto activeTemplate = null;
    private File activeSampleFile = null;

    public static final class ColumnRow {
        final SimpleIntegerProperty order = new SimpleIntegerProperty();
        final SimpleStringProperty headerLabel = new SimpleStringProperty();
        final SimpleStringProperty canonicalField = new SimpleStringProperty();
        final SimpleStringProperty dataType = new SimpleStringProperty();
        final SimpleBooleanProperty isRequired = new SimpleBooleanProperty();

        public ColumnRow(int o, String h, String c, String d, boolean r) {
            order.set(o);
            headerLabel.set(h);
            canonicalField.set(c);
            dataType.set(d);
            isRequired.set(r);
        }
    }

    @FXML
    public void initialize() {
        if (pageIcon != null) pageIcon.getChildren().setAll(IconFactory.icon("document", 22));
        if (btnLockTemplate != null) btnLockTemplate.setGraphic(IconFactory.compactIcon("lock", 14));
        if (btnBackToDirectory != null) btnBackToDirectory.setGraphic(IconFactory.compactIcon("previous", 14));
        if (btnUploadSample != null) btnUploadSample.setGraphic(IconFactory.compactIcon("upload", 14));
        if (btnViewTemplateDoc != null) btnViewTemplateDoc.setGraphic(IconFactory.compactIcon("view", 14));
        if (btnAddHeaderField != null) btnAddHeaderField.setGraphic(IconFactory.compactIcon("add", 12));
        if (btnAddColumn != null) btnAddColumn.setGraphic(IconFactory.compactIcon("add", 12));
        if (btnReDetect != null) btnReDetect.setGraphic(IconFactory.compactIcon("refresh", 12));

        if (btnResetHeaders != null) btnResetHeaders.setGraphic(IconFactory.compactIcon("refresh", 12));
        setupClientProfileSelector();
        setupDocumentHeadersTable();
        setupColumnsBlueprintTable();
    }

    private void setupClientProfileSelector() {
        if (cmbClientProfile == null) return;
        cmbClientProfile.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(TallyAutomationApiClient.ClientProfileDto item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : (item.clientName() + " (" + (item.gstin() != null ? item.gstin() : "No GSTIN") + ")"));
            }
        });
        cmbClientProfile.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(TallyAutomationApiClient.ClientProfileDto item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : (item.clientName() + " (" + (item.gstin() != null ? item.gstin() : "No GSTIN") + ")"));
            }
        });
        cmbClientProfile.valueProperty().addListener((obs, o, n) -> {
            if (n != null) onClientSelected(n);
        });
    }

    private void setupDocumentHeadersTable() {

        if (tableDocumentHeaders == null) return;



        // Dynamic responsive column widths per Rule 7

        colHdrOrder.prefWidthProperty().bind(tableDocumentHeaders.widthProperty().multiply(0.08));

        colHdrLabel.prefWidthProperty().bind(tableDocumentHeaders.widthProperty().multiply(0.24));

        colHdrCanonical.prefWidthProperty().bind(tableDocumentHeaders.widthProperty().multiply(0.24));

        colHdrSample.prefWidthProperty().bind(tableDocumentHeaders.widthProperty().multiply(0.20));

        colHdrRequired.prefWidthProperty().bind(tableDocumentHeaders.widthProperty().multiply(0.10));

        colHdrActions.prefWidthProperty().bind(tableDocumentHeaders.widthProperty().multiply(0.14));



        colHdrOrder.setCellValueFactory(cell -> cell.getValue().order);

        colHdrLabel.setCellValueFactory(cell -> cell.getValue().headerLabel);

        colHdrCanonical.setCellValueFactory(cell -> cell.getValue().canonicalField);

        colHdrSample.setCellValueFactory(cell -> cell.getValue().sampleValue);

        colHdrRequired.setCellValueFactory(cell -> cell.getValue().isRequired);



        colHdrActions.setCellFactory(col -> new TableCell<>() {

            private final Button btnUp = new Button("↑");

            private final Button btnDown = new Button("↓");

            private final Button btnDel = new Button("✕");

            private final HBox box = new HBox(4, btnUp, btnDown, btnDel);



            {

                btnUp.getStyleClass().addAll("approved-button", "approved-secondary-button", "compact-action-button");

                btnUp.setTooltip(new Tooltip("Move Header Up"));

                btnUp.setOnAction(ev -> moveHeaderRow(getIndex(), -1));



                btnDown.getStyleClass().addAll("approved-button", "approved-secondary-button", "compact-action-button");

                btnDown.setTooltip(new Tooltip("Move Header Down"));

                btnDown.setOnAction(ev -> moveHeaderRow(getIndex(), 1));



                btnDel.getStyleClass().addAll("approved-button", "approved-danger-button", "compact-action-button");

                btnDel.setTooltip(new Tooltip("Delete Header"));

                btnDel.setOnAction(ev -> {

                    int idx = getIndex();

                    if (idx >= 0 && idx < documentHeadersList.size()) {

                        documentHeadersList.remove(idx);

                        reorderHeaderRows();

                    }

                });

            }



            @Override

            protected void updateItem(Void item, boolean empty) {

                super.updateItem(item, empty);

                setGraphic(empty ? null : box);

                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);

            }

        });



        tableDocumentHeaders.setItems(documentHeadersList);

        populateDefaultDocumentHeaders();

    }



    private void moveHeaderRow(int idx, int dir) {

        int target = idx + dir;

        if (target >= 0 && target < documentHeadersList.size()) {

            DocumentHeaderRow r = documentHeadersList.remove(idx);

            documentHeadersList.add(target, r);

            reorderHeaderRows();

        }

    }



    private void reorderHeaderRows() {

        for (int i = 0; i < documentHeadersList.size(); i++) {

            documentHeadersList.get(i).order.set(i + 1);

        }

    }



    private void populateDefaultDocumentHeaders() {

        documentHeadersList.setAll(

                new DocumentHeaderRow(1, "Invoice Number", "Invoice Number", "INV-2026-089", true),

                new DocumentHeaderRow(2, "Invoice Date", "Invoice Date", "24/10/2026", true),

                new DocumentHeaderRow(3, "Due Date", "Due Date", "08/11/2026", false),

                new DocumentHeaderRow(4, "Customer PO No", "Customer PO Reference", "PO-9921", false),

                new DocumentHeaderRow(5, "E-Way Bill No", "E-Way Bill Number", "241289381928", false),

                new DocumentHeaderRow(6, "Taxable Amount", "Taxable Value", "₹ 1,25,000.00", true),

                new DocumentHeaderRow(7, "CGST Amount", "CGST", "₹ 11,250.00", false),

                new DocumentHeaderRow(8, "SGST Amount", "SGST", "₹ 11,250.00", false),

                new DocumentHeaderRow(9, "IGST Amount", "IGST", "₹ 0.00", false),

                new DocumentHeaderRow(10, "Round Off", "Round Off", "₹ 0.40", false),

                new DocumentHeaderRow(11, "Grand Total", "Grand Total Amount", "₹ 1,47,500.40", true)

        );

    }



    @FXML

    public void handleResetHeaders() {

        populateDefaultDocumentHeaders();

        ToastManager.info(tableColumnsBlueprint, "Headers Reset", "Document headers reset to standard ERP extraction blueprint.");

    }



    private void setupColumnsBlueprintTable() {
        if (tableColumnsBlueprint == null) return;

        // Dynamic responsive column widths per Rule 7
        colOrder.prefWidthProperty().bind(tableColumnsBlueprint.widthProperty().multiply(0.08));
        colHeaderLabel.prefWidthProperty().bind(tableColumnsBlueprint.widthProperty().multiply(0.28));
        colCanonicalField.prefWidthProperty().bind(tableColumnsBlueprint.widthProperty().multiply(0.24));
        colDataType.prefWidthProperty().bind(tableColumnsBlueprint.widthProperty().multiply(0.14));
        colRequired.prefWidthProperty().bind(tableColumnsBlueprint.widthProperty().multiply(0.10));
        colActions.prefWidthProperty().bind(tableColumnsBlueprint.widthProperty().multiply(0.16));

        colOrder.setCellValueFactory(cell -> cell.getValue().order);
        colHeaderLabel.setCellValueFactory(cell -> cell.getValue().headerLabel);
        colCanonicalField.setCellValueFactory(cell -> cell.getValue().canonicalField);
        colDataType.setCellValueFactory(cell -> cell.getValue().dataType);
        colRequired.setCellValueFactory(cell -> cell.getValue().isRequired);

        colActions.setCellFactory(col -> new TableCell<>() {
            private final Button btnUp = new Button("↑");
            private final Button btnDown = new Button("↓");
            private final Button btnDel = new Button("✕");
            private final HBox box = new HBox(4, btnUp, btnDown, btnDel);

            {
                btnUp.getStyleClass().addAll("approved-button", "approved-secondary-button", "compact-action-button");
                btnUp.setTooltip(new Tooltip("Move Column Up"));
                btnUp.setOnAction(ev -> moveRow(getIndex(), -1));

                btnDown.getStyleClass().addAll("approved-button", "approved-secondary-button", "compact-action-button");
                btnDown.setTooltip(new Tooltip("Move Column Down"));
                btnDown.setOnAction(ev -> moveRow(getIndex(), 1));

                btnDel.getStyleClass().addAll("approved-button", "approved-danger-button", "compact-action-button");
                btnDel.setTooltip(new Tooltip("Delete Column"));
                btnDel.setOnAction(ev -> removeRow(getIndex()));
                box.setAlignment(Pos.CENTER);
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : box);
            }
        });

        tableColumnsBlueprint.setItems(columnsList);
    }

    private void moveRow(int idx, int delta) {
        int target = idx + delta;
        if (target < 0 || target >= columnsList.size()) return;
        ColumnRow item = columnsList.remove(idx);
        columnsList.add(target, item);
        reorderRows();
    }

    private void removeRow(int idx) {
        if (idx >= 0 && idx < columnsList.size()) {
            columnsList.remove(idx);
            reorderRows();
        }
    }

    private void reorderRows() {
        for (int i = 0; i < columnsList.size(); i++) {
            columnsList.get(i).order.set(i + 1);
        }
    }

    private void onClientSelected(TallyAutomationApiClient.ClientProfileDto client) {
        this.activeClient = client;
        lblFirmName.setText(client.legalName() != null ? client.legalName() : client.clientName());
        lblGstin.setText(client.gstin() != null ? client.gstin() : "No GSTIN");
        lblAddress.setText(client.address() != null ? client.address() : "Registered Address");
        lblGstStatus.setText(client.gstStatus() != null ? client.gstStatus() : "ACTIVE");
        lblGstStatus.getStyleClass().setAll("status-badge", "ACTIVE".equalsIgnoreCase(client.gstStatus()) ? "status-active" : "status-inactive");

        loadTemplatesForClient(client.id());
    }

    private void loadTemplatesForClient(Long clientId) {
        UiTaskExecutor.submitAction(
                "load-client-templates",
                () -> api.listTemplates(clientId),
                templates -> {
                    currentVersions.clear();
                    currentVersions.addAll(templates);
                    renderVersionPills();
                    if (!templates.isEmpty()) {
                        selectTemplateVersion(templates.get(0));
                    } else {
                        // Create default v1 schema
                        initDefaultVersion("v1");
                    }
                },
                failure -> ToastManager.error(tableColumnsBlueprint, "Load Templates Error", failure.getMessage())
        );
    }

    private void renderVersionPills() {
        if (boxVersionPills == null) return;
        boxVersionPills.getChildren().clear();

        if (currentVersions.isEmpty()) {
            Button pill = new Button("v1 (Draft)");
            pill.getStyleClass().addAll("approved-button", "version-pill", "version-pill-active");
            boxVersionPills.getChildren().add(pill);
            return;
        }

        for (var t : currentVersions) {
            String label = t.versionCode() + (t.isLocked() ? " (Locked)" : " (Draft)");
            Button pill = new Button(label);
            pill.getStyleClass().addAll("approved-button", "version-pill");
            if (activeTemplate != null && t.id().equals(activeTemplate.id())) {
                pill.getStyleClass().add("version-pill-active");
            }
            pill.setOnAction(ev -> selectTemplateVersion(t));
            boxVersionPills.getChildren().add(pill);
        }
    }

    private void selectTemplateVersion(TallyAutomationApiClient.TemplateVersionDto t) {
        this.activeTemplate = t;
        renderVersionPills();

        if (lblLockStatus != null) {
            lblLockStatus.setText(t.isLocked() ? "Status: LOCKED (Ready for Bill to Tally)" : "Status: DRAFT (Editable)");
            lblLockStatus.getStyleClass().setAll("lock-status-badge", t.isLocked() ? "erp-badge-emerald" : "erp-badge-amber");
        }

        if (t.sampleFilePath() != null && !t.sampleFilePath().isBlank()) {
            activeSampleFile = new File(t.sampleFilePath());
        }

        // Populate Document Headers
        documentHeadersList.clear();
        try {
            if (t.headerFieldsJson() != null && !t.headerFieldsJson().isBlank()) {
                JsonNode root = MAPPER.readTree(t.headerFieldsJson());
                if (root.isArray()) {
                    int orderIdx = 1;
                    for (JsonNode n : root) {
                        if (n.isObject()) {
                            int ord = n.has("order") ? n.get("order").asInt(orderIdx) : orderIdx;
                            String lbl = n.has("headerLabel") ? n.get("headerLabel").asText() : "";
                            String can = n.has("canonicalField") ? n.get("canonicalField").asText() : lbl;
                            String smp = n.has("sampleValue") ? n.get("sampleValue").asText() : "—";
                            boolean req = !n.has("isRequired") || n.get("isRequired").asBoolean(true);
                            documentHeadersList.add(new DocumentHeaderRow(ord, lbl, can, smp, req));
                        } else if (n.isTextual()) {
                            String s = n.asText();
                            documentHeadersList.add(new DocumentHeaderRow(orderIdx, s, s, "—", true));
                        }
                        orderIdx++;
                    }
                }
            }
        } catch (Exception ignored) {}

        if (documentHeadersList.isEmpty()) {
            populateDefaultDocumentHeaders();
        } else {
            reorderHeaderRows();
        }

        // Populate Item Columns Blueprint
        columnsList.clear();
        try {
            if (t.itemColumnsJson() != null && !t.itemColumnsJson().isBlank()) {
                List<TallyAutomationApiClient.TemplateColumnDef> cols = MAPPER.readValue(t.itemColumnsJson(), new TypeReference<>() {});
                for (var c : cols) {
                    columnsList.add(new ColumnRow(c.colOrder(), c.colHeader(), c.canonicalField(), c.dataType(), c.isRequired()));
                }
            }
        } catch (Exception ignored) {}

        if (columnsList.isEmpty()) {
            populateDefaultColumnsBlueprint();
        }
    }

    private void initDefaultVersion(String code) {
        activeTemplate = new TallyAutomationApiClient.TemplateVersionDto(
                null,
                activeClient != null ? activeClient.id() : null,
                "Standard Invoice Template",
                code,
                true,
                false,
                activeClient != null ? activeClient.gstin() : null,
                "HSN/SAC,Qty,Rate,Net Amt",
                "[\"Invoice No\",\"Invoice Date\",\"Vendor Name\",\"GSTIN\",\"Taxable Amount\",\"CGST\",\"SGST\",\"IGST\",\"Grand Total\"]",
                "[]",
                null,
                null
        );
        selectTemplateVersion(activeTemplate);
    }

    private void populateDefaultColumnsBlueprint() {
        columnsList.setAll(
                new ColumnRow(1, "S.No", "Line Number", "Integer", true),
                new ColumnRow(2, "Particulars / Item Name", "Item Description", "Text", true),
                new ColumnRow(3, "HSN/SAC", "HSN/SAC", "Text", true),
                new ColumnRow(4, "Qty", "Quantity", "Decimal", true),
                new ColumnRow(5, "Unit", "Unit of Measure", "Text", true),
                new ColumnRow(6, "Rate", "Unit Rate", "Currency", true),
                new ColumnRow(7, "GST %", "Tax Percentage", "Percentage", true),
                new ColumnRow(8, "Discount", "Line Discount", "Currency", false),
                new ColumnRow(9, "Net Amt", "Line Total Amount", "Currency", true)
        );
    }

    @FXML
    public void handleAddHeaderField() {
        Dialog<DocumentHeaderRow> dialog = new org.example.util.OwnedDialog<>();
        dialog.setTitle("Add High-Level Document Header");
        dialog.setHeaderText("Define a document-level header extracted from the bill top.");
        dialog.getDialogPane().getStyleClass().addAll("approved-dialog");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));

        TextField txtLabel = new TextField();
        txtLabel.setPromptText("e.g. PO Number, Vehicle No, TCS");
        txtLabel.getStyleClass().add("approved-input");

        ComboBox<String> cmbCanonical = new ComboBox<>(FXCollections.observableArrayList(
                "Invoice Number", "Invoice Date", "Due Date", "Customer PO Reference",
                "E-Way Bill Number", "Taxable Value", "CGST", "SGST", "IGST",
                "Round Off", "Grand Total Amount", "Custom Header"
        ));
        cmbCanonical.setValue("Invoice Number");
        cmbCanonical.getStyleClass().add("approved-input");

        TextField txtSample = new TextField();
        txtSample.setPromptText("e.g. PO-88192");
        txtSample.getStyleClass().add("approved-input");

        CheckBox chkReq = new CheckBox("Required Document Header");
        chkReq.setSelected(true);

        grid.addRow(0, new Label("Header Label *"), txtLabel);
        grid.addRow(1, new Label("Mapped Field"), cmbCanonical);
        grid.addRow(2, new Label("Sample Value"), txtSample);
        grid.addRow(3, new Label(""), chkReq);

        dialog.getDialogPane().setContent(grid);
        ButtonType btnAdd = new ButtonType("Add Header", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(btnAdd, ButtonType.CANCEL);

        dialog.setResultConverter(b -> {
            if (b == btnAdd && !txtLabel.getText().trim().isEmpty()) {
                return new DocumentHeaderRow(
                        documentHeadersList.size() + 1,
                        txtLabel.getText().trim(),
                        cmbCanonical.getValue(),
                        txtSample.getText().trim(),
                        chkReq.isSelected()
                );
            }
            return null;
        });

        dialog.showAndWait().ifPresent(row -> {
            documentHeadersList.add(row);
            reorderHeaderRows();
        });
    }

    @FXML
    public void handleAddColumn() {
        Dialog<ColumnRow> dialog = new org.example.util.OwnedDialog<>();
        dialog.setTitle("Add Column Header");
        dialog.setHeaderText("Define a new column in the item-level table blueprint.");
        dialog.getDialogPane().getStyleClass().addAll("approved-dialog");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));

        TextField txtLabel = new TextField();
        txtLabel.setPromptText("e.g. Discount %, Batch No");
        txtLabel.getStyleClass().add("approved-input");

        ComboBox<String> cmbCanonical = new ComboBox<>(FXCollections.observableArrayList(
                "Item Description", "HSN/SAC", "Quantity", "Unit of Measure", "Unit Rate",
                "Tax Percentage", "Line Discount", "Line Total Amount", "Custom"
        ));
        cmbCanonical.setValue("Item Description");
        cmbCanonical.getStyleClass().add("approved-input");

        ComboBox<String> cmbType = new ComboBox<>(FXCollections.observableArrayList("Text", "Decimal", "Integer", "Currency", "Percentage"));
        cmbType.setValue("Text");
        cmbType.getStyleClass().add("approved-input");

        CheckBox chkReq = new CheckBox("Required Column");
        chkReq.setSelected(true);

        grid.addRow(0, new Label("Header Label *"), txtLabel);
        grid.addRow(1, new Label("Mapped Field"), cmbCanonical);
        grid.addRow(2, new Label("Data Type"), cmbType);
        grid.addRow(3, new Label(""), chkReq);

        dialog.getDialogPane().setContent(grid);
        ButtonType btnAdd = new ButtonType("Add Column", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(btnAdd, ButtonType.CANCEL);

        dialog.setResultConverter(b -> {
            if (b == btnAdd && !txtLabel.getText().trim().isEmpty()) {
                return new ColumnRow(
                        columnsList.size() + 1,
                        txtLabel.getText().trim(),
                        cmbCanonical.getValue(),
                        cmbType.getValue(),
                        chkReq.isSelected()
                );
            }
            return null;
        });

        dialog.showAndWait().ifPresent(row -> {
            columnsList.add(row);
            reorderRows();
        });
    }

    @FXML
    public void handleUploadSample() {
        if (activeClient == null) {
            ToastManager.warning(tableColumnsBlueprint, "No Client", "Please select a client before uploading a sample bill.");
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select Sample Bill Format");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Invoices & Spreadsheets", "*.png", "*.jpg", "*.jpeg", "*.pdf", "*.xlsx", "*.xls", "*.csv"));
        File f = chooser.showOpenDialog(tableColumnsBlueprint.getScene().getWindow());
        if (f == null) return;

        this.activeSampleFile = f;
        ToastManager.info(tableColumnsBlueprint, "Analyzing Sample", "Scanning document headers with native OCR...");

        UiTaskExecutor.submitAction(
                "analyze-sample-template",
                () -> extractSampleLines(f),
                lines -> {
                    if (!lines.isEmpty()) {
                        detectHeadersFromOcrLines(lines);
                        ToastManager.success(tableColumnsBlueprint, "Headers Detected", "Extracted table headers from sample file: " + f.getName());
                    } else {
                        ToastManager.info(tableColumnsBlueprint, "Sample Attached", "Attached " + f.getName() + " to template.");
                    }
                },
                err -> ToastManager.error(tableColumnsBlueprint, "Analysis Failed", err.getMessage())
        );
    }

    private void detectHeadersFromOcrLines(List<String> lines) {
        List<ColumnRow> detected = new ArrayList<>();
        int colOrderIdx = 1;
        for (String l : lines) {
            String trimmed = l.trim();
            if (trimmed.equalsIgnoreCase("Particulars / Item Name") || trimmed.equalsIgnoreCase("Description") || trimmed.equalsIgnoreCase("Item Description")) {
                detected.add(new ColumnRow(colOrderIdx++, trimmed, "Item Description", "Text", true));
            } else if (trimmed.equalsIgnoreCase("HSN/SAC") || trimmed.equalsIgnoreCase("HSN")) {
                detected.add(new ColumnRow(colOrderIdx++, trimmed, "HSN/SAC", "Text", true));
            } else if (trimmed.equalsIgnoreCase("Qty") || trimmed.equalsIgnoreCase("Quantity")) {
                detected.add(new ColumnRow(colOrderIdx++, trimmed, "Quantity", "Decimal", true));
            } else if (trimmed.equalsIgnoreCase("Unit") || trimmed.equalsIgnoreCase("UOM")) {
                detected.add(new ColumnRow(colOrderIdx++, trimmed, "Unit of Measure", "Text", true));
            } else if (trimmed.equalsIgnoreCase("Rate") || trimmed.equalsIgnoreCase("Price")) {
                detected.add(new ColumnRow(colOrderIdx++, trimmed, "Unit Rate", "Currency", true));
            } else if (trimmed.equalsIgnoreCase("GST%") || trimmed.equalsIgnoreCase("Tax%")) {
                detected.add(new ColumnRow(colOrderIdx++, trimmed, "Tax Percentage", "Percentage", true));
            } else if (trimmed.equalsIgnoreCase("Discount") || trimmed.equalsIgnoreCase("Disc")) {
                detected.add(new ColumnRow(colOrderIdx++, trimmed, "Line Discount", "Currency", false));
            } else if (trimmed.equalsIgnoreCase("Net Amt") || trimmed.equalsIgnoreCase("Amount") || trimmed.equalsIgnoreCase("Total")) {
                detected.add(new ColumnRow(colOrderIdx++, trimmed, "Line Total Amount", "Currency", true));
            }
        }
        if (!detected.isEmpty()) {
            columnsList.setAll(detected);
            reorderRows();
        }
    }

    @FXML
    public void handleReDetect() {
        if (activeSampleFile != null && activeSampleFile.exists()) {
            handleUploadSample();
        } else {
            ToastManager.warning(tableColumnsBlueprint, "No Sample", "Please upload a sample bill file first.");
        }
    }

    @FXML
    public void handleViewTemplateDoc() {
        if (activeSampleFile == null || !activeSampleFile.exists()) {
            ToastManager.warning(tableColumnsBlueprint, "No Document", "No sample bill document has been attached to this template yet.");
            return;
        }
        String title = "Template Source Document — " + (activeClient != null ? activeClient.clientName() : "Client");
        String meta = "Version: " + (activeTemplate != null ? activeTemplate.versionCode() : "v1") + " | File: " + activeSampleFile.getName();
        TallyDocumentInspectionDialog.show(tableColumnsBlueprint, activeSampleFile, title, meta, "");
    }

    @FXML
    public void handleNewVersion() {
        if (activeClient == null) {
            ToastManager.warning(tableColumnsBlueprint, "No Client", "Select a client first.");
            return;
        }
        int nextNum = currentVersions.size() + 1;
        initDefaultVersion("v" + nextNum);
        renderVersionPills();
        ToastManager.info(tableColumnsBlueprint, "New Version", "Created Draft Version v" + nextNum + ". Adjust headers and click 'Lock & Save'.");
    }

    @FXML
    public void handleLockTemplate() {
        if (activeClient == null) {
            ToastManager.warning(tableColumnsBlueprint, "Cannot Lock", "Select a client first.");
            return;
        }

        try {
            List<Map<String, Object>> hdrDefs = new ArrayList<>();

            for (DocumentHeaderRow r : documentHeadersList) {

                hdrDefs.add(Map.of(

                        "order", r.order.get(),

                        "headerLabel", r.headerLabel.get(),

                        "canonicalField", r.canonicalField.get(),

                        "sampleValue", r.sampleValue.get(),

                        "isRequired", r.isRequired.get()

                ));

            }

            String headersJson = MAPPER.writeValueAsString(hdrDefs);
            List<TallyAutomationApiClient.TemplateColumnDef> colDefs = new ArrayList<>();
            for (ColumnRow r : columnsList) {
                colDefs.add(new TallyAutomationApiClient.TemplateColumnDef(
                        r.order.get(),
                        r.headerLabel.get().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "_"),
                        r.headerLabel.get(),
                        r.canonicalField.get(),
                        r.dataType.get(),
                        r.isRequired.get()
                ));
            }
            String columnsJson = MAPPER.writeValueAsString(colDefs);

            String versionCode = (activeTemplate != null && activeTemplate.versionCode() != null)
                    ? activeTemplate.versionCode()
                    : "v1";

            var dto = new TallyAutomationApiClient.TemplateVersionDto(
                    activeTemplate != null ? activeTemplate.id() : null,
                    activeClient.id(),
                    activeClient.clientName() + " " + versionCode.toUpperCase(Locale.ROOT) + " Format",
                    versionCode,
                    true,
                    true, // Locked
                    activeClient.gstin(),
                    documentHeadersList.stream().map(DocumentHeaderRow::getHeaderLabel).collect(Collectors.joining(",")),
                    headersJson,
                    columnsJson,
                    activeSampleFile != null ? activeSampleFile.getAbsolutePath() : null,
                    null
            );

            UiTaskExecutor.submitAction(
                    "lock-template-version",
                    () -> api.saveTemplate(dto),
                    saved -> {
                        this.activeTemplate = saved;
                        loadTemplatesForClient(activeClient.id());
                        ToastManager.success(tableColumnsBlueprint, "Template Locked", "Version " + saved.versionCode() + " locked and saved! Ready for Bill to Tally.");
                    },
                    failure -> ToastManager.error(tableColumnsBlueprint, "Lock Failed", failure.getMessage())
            );
        } catch (Exception ex) {
            ToastManager.error(tableColumnsBlueprint, "Serialization Error", ex.getMessage());
        }
    }

    @FXML
    public void handleViewFullGst() {
        if (activeClient != null) {
            GstFullDetailsDialog.show(tableColumnsBlueprint, activeClient);
        } else {
            ToastManager.info(tableColumnsBlueprint, "Select Client", "Please select a client from the dropdown first to view full GST details.");
        }
    }

    @FXML
    public void handleBackToDirectory() {
        DashboardController.navigateFromChildPage("Clients", "/fxml/pages/ClientsDirectory.fxml");
    }

    @Override
    public void onScreenShown(boolean reusedFromCache) {
        UiTaskExecutor.submitAction(
                "load-profiles-combo",
                api::listProfiles,
                list -> {
                    cmbClientProfile.setItems(FXCollections.observableArrayList(list));
                    if (preselectedClientId != null) {
                        list.stream().filter(c -> preselectedClientId.equals(c.id())).findFirst().ifPresent(cmbClientProfile::setValue);
                        preselectedClientId = null;
                    } else if (!list.isEmpty()) {
                        cmbClientProfile.setValue(list.get(0));
                    }
                },
                failure -> ToastManager.error(tableColumnsBlueprint, "Load Clients Error", failure.getMessage())
        );
    }

    @Override
    public void onScreenHidden() {}

    private List<String> extractSampleLines(File f) {
        String ext = f.getName().toLowerCase(Locale.ROOT);
        if (ext.endsWith(".png") || ext.endsWith(".jpg") || ext.endsWith(".jpeg")) {
            return WindowsOcrEngine.extractTextLines(f);
        } else if (ext.endsWith(".pdf")) {
            try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.Loader.loadPDF(f)) {
                org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
                String text = stripper.getText(doc);
                return Arrays.asList(text.split("\\r?\\n"));
            } catch (Exception ex) {
                return Collections.emptyList();
            }
        }
        return Collections.emptyList();
    }

}
