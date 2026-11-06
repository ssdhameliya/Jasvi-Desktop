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
    @FXML private Button btnDeleteVersion;
    @FXML private TabPane tabPaneBlueprint;
    @FXML private Tab tabDocumentHeaders;
    @FXML private Tab tabItemColumns;

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
        if (btnDeleteVersion != null) btnDeleteVersion.setGraphic(IconFactory.compactIcon("delete", 12));
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



        DynamicTableLayoutManager.install(tableDocumentHeaders);



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
                new DocumentHeaderRow(1, "Invoice Number", "Invoice Number", "—", true),
                new DocumentHeaderRow(2, "Invoice Date", "Invoice Date", "—", true),
                new DocumentHeaderRow(3, "Due Date", "Due Date", "—", false),
                new DocumentHeaderRow(4, "Customer PO No", "Customer PO Reference", "—", false),
                new DocumentHeaderRow(5, "E-Way Bill No", "E-Way Bill Number", "—", false),
                new DocumentHeaderRow(6, "Taxable Amount", "Taxable Value", "—", true),
                new DocumentHeaderRow(7, "CGST Amount", "CGST", "—", false),
                new DocumentHeaderRow(8, "SGST Amount", "SGST", "—", false),
                new DocumentHeaderRow(9, "IGST Amount", "IGST", "—", false),
                new DocumentHeaderRow(10, "Round Off", "Round Off", "—", false),
                new DocumentHeaderRow(11, "Grand Total", "Grand Total Amount", "—", true)
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
        DynamicTableLayoutManager.install(tableColumnsBlueprint);

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
        UiTaskExecutor.submitLatest(
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

        UiTaskExecutor.submitLatest(
                "analyze-sample-template",
                () -> extractSampleLines(f),
                lines -> {
                    if (!lines.isEmpty()) {
                        detectHeadersFromOcrLines(lines, f);
                    } else {
                        ToastManager.info(tableColumnsBlueprint, "Sample Attached", "Attached " + f.getName() + " to template (no OCR text detected).");
                    }
                },
                err -> ToastManager.error(tableColumnsBlueprint, "Analysis Failed", err.getMessage())
        );
    }

    private void detectHeadersFromOcrLines(List<String> lines, File sourceFile) {
        // =========================================================================
        // 1. DYNAMIC HIGH-LEVEL DOCUMENT HEADERS EXTRACTION
        // =========================================================================
        Map<String, DocumentHeaderRow> extractedHeaders = new LinkedHashMap<>();

        // Canonical header matching regexes with value extractors
        java.util.regex.Pattern pInvNo = java.util.regex.Pattern.compile("(?i)(?:inv(?:oice)?|bill|tax\\s*invoice)(?:\\s*(?:no|num|number|#))?[:\\s\\-]*([A-Za-z0-9\\/\\-_]{3,30})");
        java.util.regex.Pattern pInvDate = java.util.regex.Pattern.compile("(?i)(?:inv(?:oice)?\\s*date|dated|bill\\s*date|date)[:\\s\\-]*([0-9]{1,2}[\\/\\-\\.][0-9]{1,2}[\\/\\-\\.][0-9]{2,4}|[0-9]{1,2}\\s+[A-Za-z]{3}\\s+[0-9]{2,4})");
        java.util.regex.Pattern pDueDate = java.util.regex.Pattern.compile("(?i)(?:due\\s*date|payment\\s*due)[:\\s\\-]*([0-9]{1,2}[\\/\\-\\.][0-9]{1,2}[\\/\\-\\.][0-9]{2,4})");
        java.util.regex.Pattern pPo = java.util.regex.Pattern.compile("(?i)(?:po|order|buyer\\s*order)(?:\\s*(?:no|num|number|ref))?[:\\s\\-]*([A-Za-z0-9\\/\\-_]{3,30})");
        java.util.regex.Pattern pEwb = java.util.regex.Pattern.compile("(?i)(?:e-?way\\s*bill|ewb)(?:\\s*(?:no|num|number))?[:\\s\\-]*([0-9]{12})");
        java.util.regex.Pattern pChallan = java.util.regex.Pattern.compile("(?i)(?:delivery\\s*challan|challan)(?:\\s*(?:no|num|number))?[:\\s\\-]*([A-Za-z0-9\\/\\-_]+)");
        java.util.regex.Pattern pVehicle = java.util.regex.Pattern.compile("(?i)(?:vehicle|lorry|truck)(?:\\s*(?:no|num|number))?[:\\s\\-]*([A-Za-z]{2}\\s*[0-9]{1,2}\\s*[A-Za-z]{1,3}\\s*[0-9]{4})");
        java.util.regex.Pattern pGstin = java.util.regex.Pattern.compile("(?i)(?:gstin|gst\\s*no)?[:\\s\\-]*([0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1})");
        java.util.regex.Pattern pState = java.util.regex.Pattern.compile("(?i)(?:place\\s*of\\s*supply|state)[:\\s\\-]*([A-Za-z\\s]+(?:\\([0-9]{2}\\))?)");
        java.util.regex.Pattern pReverseCharge = java.util.regex.Pattern.compile("(?i)(?:reverse\\s*charge)[:\\s\\-]*([A-Za-z]+)");
        java.util.regex.Pattern pTaxableAmt = java.util.regex.Pattern.compile("(?i)(?:taxable\\s*(?:amount|value|amt)?|sub\\s*total)[:\\s₹rs\\.]*([0-9,]+\\.[0-9]{2})");
        java.util.regex.Pattern pCgstAmt = java.util.regex.Pattern.compile("(?i)(?:cgst\\s*(?:amt|amount)?|central\\s*tax)[:\\s₹rs\\.]*([0-9,]+\\.[0-9]{2})");
        java.util.regex.Pattern pSgstAmt = java.util.regex.Pattern.compile("(?i)(?:sgst\\s*(?:amt|amount)?|state\\s*tax|utgst)[:\\s₹rs\\.]*([0-9,]+\\.[0-9]{2})");
        java.util.regex.Pattern pIgstAmt = java.util.regex.Pattern.compile("(?i)(?:igst\\s*(?:amt|amount)?|integrated\\s*tax)[:\\s₹rs\\.]*([0-9,]+\\.[0-9]{2})");
        java.util.regex.Pattern pRoundOff = java.util.regex.Pattern.compile("(?i)(?:round(?:ing)?\\s*(?:off)?)[:\\s₹rs\\.\\+\\-]*([0-9,]+\\.[0-9]{2})");
        java.util.regex.Pattern pTotalAmt = java.util.regex.Pattern.compile("(?i)(?:grand\\s*total|invoice\\s*total|net\\s*amount|total\\s*amount)[:\\s₹rs\\.]*([0-9,]+\\.[0-9]{2})");

        int docOrder = 1;

        // Scan all OCR lines for recognized and generic labeled headers
        for (String l : lines) {
            String trimmedLine = l.trim();
            if (trimmedLine.length() < 3) continue;

            java.util.regex.Matcher m = pInvNo.matcher(trimmedLine);
            if (m.find() && !extractedHeaders.containsKey("Invoice Number")) {
                extractedHeaders.put("Invoice Number", new DocumentHeaderRow(docOrder++, "Invoice Number", "Invoice Number", m.group(1), true));
            }

            m = pInvDate.matcher(trimmedLine);
            if (m.find() && !extractedHeaders.containsKey("Invoice Date")) {
                extractedHeaders.put("Invoice Date", new DocumentHeaderRow(docOrder++, "Invoice Date", "Invoice Date", m.group(1), true));
            }

            m = pDueDate.matcher(trimmedLine);
            if (m.find() && !extractedHeaders.containsKey("Due Date")) {
                extractedHeaders.put("Due Date", new DocumentHeaderRow(docOrder++, "Due Date", "Due Date", m.group(1), false));
            }

            m = pPo.matcher(trimmedLine);
            if (m.find() && !extractedHeaders.containsKey("Customer PO Reference")) {
                extractedHeaders.put("Customer PO Reference", new DocumentHeaderRow(docOrder++, "Customer PO No", "Customer PO Reference", m.group(1), false));
            }

            m = pEwb.matcher(trimmedLine);
            if (m.find() && !extractedHeaders.containsKey("E-Way Bill Number")) {
                extractedHeaders.put("E-Way Bill Number", new DocumentHeaderRow(docOrder++, "E-Way Bill No", "E-Way Bill Number", m.group(1), false));
            }

            m = pChallan.matcher(trimmedLine);
            if (m.find() && !extractedHeaders.containsKey("Delivery Challan")) {
                extractedHeaders.put("Delivery Challan", new DocumentHeaderRow(docOrder++, "Delivery Challan No", "Delivery Challan", m.group(1), false));
            }

            m = pVehicle.matcher(trimmedLine);
            if (m.find() && !extractedHeaders.containsKey("Vehicle Number")) {
                extractedHeaders.put("Vehicle Number", new DocumentHeaderRow(docOrder++, "Vehicle No", "Vehicle Number", m.group(1), false));
            }

            m = pGstin.matcher(trimmedLine);
            if (m.find() && !extractedHeaders.containsKey("Vendor GSTIN")) {
                extractedHeaders.put("Vendor GSTIN", new DocumentHeaderRow(docOrder++, "Vendor GSTIN", "Vendor GSTIN", m.group(1), true));
            }

            m = pState.matcher(trimmedLine);
            if (m.find() && !extractedHeaders.containsKey("Place of Supply")) {
                extractedHeaders.put("Place of Supply", new DocumentHeaderRow(docOrder++, "Place of Supply", "Place of Supply", m.group(1), false));
            }

            m = pReverseCharge.matcher(trimmedLine);
            if (m.find() && !extractedHeaders.containsKey("Reverse Charge")) {
                extractedHeaders.put("Reverse Charge", new DocumentHeaderRow(docOrder++, "Reverse Charge", "Reverse Charge", m.group(1), false));
            }

            m = pTaxableAmt.matcher(trimmedLine);
            if (m.find() && !extractedHeaders.containsKey("Taxable Value")) {
                extractedHeaders.put("Taxable Value", new DocumentHeaderRow(docOrder++, "Taxable Amount", "Taxable Value", "₹ " + m.group(1), true));
            }

            m = pCgstAmt.matcher(trimmedLine);
            if (m.find() && !extractedHeaders.containsKey("CGST")) {
                extractedHeaders.put("CGST", new DocumentHeaderRow(docOrder++, "CGST Amount", "CGST", "₹ " + m.group(1), false));
            }

            m = pSgstAmt.matcher(trimmedLine);
            if (m.find() && !extractedHeaders.containsKey("SGST")) {
                extractedHeaders.put("SGST", new DocumentHeaderRow(docOrder++, "SGST Amount", "SGST", "₹ " + m.group(1), false));
            }

            m = pIgstAmt.matcher(trimmedLine);
            if (m.find() && !extractedHeaders.containsKey("IGST")) {
                extractedHeaders.put("IGST", new DocumentHeaderRow(docOrder++, "IGST Amount", "IGST", "₹ " + m.group(1), false));
            }

            m = pRoundOff.matcher(trimmedLine);
            if (m.find() && !extractedHeaders.containsKey("Round Off")) {
                extractedHeaders.put("Round Off", new DocumentHeaderRow(docOrder++, "Round Off", "Round Off", "₹ " + m.group(1), false));
            }

            m = pTotalAmt.matcher(trimmedLine);
            if (m.find() && !extractedHeaders.containsKey("Grand Total Amount")) {
                extractedHeaders.put("Grand Total Amount", new DocumentHeaderRow(docOrder++, "Grand Total", "Grand Total Amount", "₹ " + m.group(1), true));
            }

            // Generic key-value pair detection for any other custom document headers
            if (trimmedLine.contains(":") && !trimmedLine.toLowerCase().contains("http") && !trimmedLine.toLowerCase().contains("address")) {
                String[] parts = trimmedLine.split(":", 2);
                if (parts.length == 2) {
                    String lbl = parts[0].trim().replaceAll("[^A-Za-z0-9\\s/\\-_]", "");
                    String val = parts[1].trim();
                    if (lbl.length() >= 3 && lbl.length() <= 25 && val.length() >= 1 && val.length() <= 50 && !extractedHeaders.containsKey(lbl)) {
                        extractedHeaders.put(lbl, new DocumentHeaderRow(docOrder++, lbl, lbl, val, false));
                    }
                }
            }
        }

        // Ensure foundational ERP document headers are present even if not scanned in sample
        if (!extractedHeaders.containsKey("Invoice Number")) {
            extractedHeaders.put("Invoice Number", new DocumentHeaderRow(docOrder++, "Invoice Number", "Invoice Number", "—", true));
        }
        if (!extractedHeaders.containsKey("Invoice Date")) {
            extractedHeaders.put("Invoice Date", new DocumentHeaderRow(docOrder++, "Invoice Date", "Invoice Date", "—", true));
        }
        if (!extractedHeaders.containsKey("Taxable Value")) {
            extractedHeaders.put("Taxable Value", new DocumentHeaderRow(docOrder++, "Taxable Amount", "Taxable Value", "—", true));
        }
        if (!extractedHeaders.containsKey("Grand Total Amount")) {
            extractedHeaders.put("Grand Total Amount", new DocumentHeaderRow(docOrder++, "Grand Total", "Grand Total Amount", "—", true));
        }

        documentHeadersList.setAll(extractedHeaders.values());
        reorderHeaderRows();

        // =========================================================================
        // 2. DYNAMIC ITEM-LEVEL TABLE COLUMNS EXTRACTION
        // =========================================================================
        List<ColumnRow> detectedColumns = new ArrayList<>();
        int colOrderIdx = 1;
        Set<String> matchedCanonical = new HashSet<>();

        // Find candidate table header line: line containing multiple table keywords
        String bestHeaderLine = null;
        int maxHeaderKeywords = 0;
        java.util.regex.Pattern pColKeyword = java.util.regex.Pattern.compile("(?i)\\b(item|desc|particular|product|goods|hsn|sac|qty|quantity|unit|rate|price|amount|tax|gst|total|disc|s\\.?no|sr\\.?|nos|pcs)\\b");

        for (String l : lines) {
            java.util.regex.Matcher km = pColKeyword.matcher(l);
            int count = 0;
            while (km.find()) count++;
            if (count > maxHeaderKeywords && count >= 2) {
                maxHeaderKeywords = count;
                bestHeaderLine = l;
            }
        }

        if (bestHeaderLine != null) {
            String[] tokens = bestHeaderLine.split("[|,\t]+|\\s{2,}");
            for (String tok : tokens) {
                String trimmed = tok.trim().replaceAll("^[^A-Za-z0-9%#]+|[^A-Za-z0-9%#]+$", "");
                if (trimmed.length() < 2) continue;

                String lower = trimmed.toLowerCase(Locale.ROOT);
                String canonical;
                String dataType;
                boolean req = true;

                if (lower.matches(".*(item|desc|particular|product|goods|article).*")) {
                    canonical = "Item Description";
                    dataType = "Text";
                } else if (lower.matches(".*(hsn|sac|tariff|commodity).*")) {
                    canonical = "HSN/SAC";
                    dataType = "Text";
                } else if (lower.matches(".*(qty|quantity|billing\\s*qty|billed\\s*qty|nos|pcs).*")) {
                    canonical = "Quantity";
                    dataType = "Decimal";
                } else if (lower.matches(".*(unit|uom|pkg|measure|per).*")) {
                    canonical = "Unit of Measure";
                    dataType = "Text";
                } else if (lower.matches(".*(rate|price|unit\\s*price|mrp).*")) {
                    canonical = "Unit Rate";
                    dataType = "Currency";
                } else if (lower.matches(".*(gst\\s*%|tax\\s*%|rate\\s*%|cgst\\s*%|sgst\\s*%|igst\\s*%).*")) {
                    canonical = "Tax Percentage";
                    dataType = "Percentage";
                } else if (lower.matches(".*(disc|discount|trade\\s*disc).*")) {
                    canonical = "Line Discount";
                    dataType = "Currency";
                    req = false;
                } else if (lower.matches(".*(taxable\\s*amt|taxable\\s*value|taxable).*")) {
                    canonical = "Line Taxable Amount";
                    dataType = "Currency";
                } else if (lower.matches(".*(cgst\\s*(?:amt|amount)?).*")) {
                    canonical = "CGST Amount";
                    dataType = "Currency";
                    req = false;
                } else if (lower.matches(".*(sgst\\s*(?:amt|amount)?|utgst).*")) {
                    canonical = "SGST Amount";
                    dataType = "Currency";
                    req = false;
                } else if (lower.matches(".*(igst\\s*(?:amt|amount)?).*")) {
                    canonical = "IGST Amount";
                    dataType = "Currency";
                    req = false;
                } else if (lower.matches(".*(net\\s*amt|amount|total).*")) {
                    canonical = "Line Total Amount";
                    dataType = "Currency";
                } else if (lower.matches(".*(s\\.?no|sr\\.?|sl\\.?|line|no).*")) {
                    canonical = "Line Number";
                    dataType = "Integer";
                } else {
                    canonical = trimmed;
                    dataType = "Text";
                    req = false;
                }

                if (!matchedCanonical.contains(canonical + "_" + trimmed)) {
                    detectedColumns.add(new ColumnRow(colOrderIdx++, trimmed, canonical, dataType, req));
                    matchedCanonical.add(canonical + "_" + trimmed);
                }
            }
        }

        // Fallback token extraction across lines if single line wasn't sufficiently tokenized
        if (detectedColumns.size() < 3) {
            java.util.regex.Pattern pItem = java.util.regex.Pattern.compile("(?i).*(item\\s*name|item\\s*desc|description|particular|product|goods|article).*");
            java.util.regex.Pattern pHsn = java.util.regex.Pattern.compile("(?i).*(hsn|sac|tariff|commodity).*");
            java.util.regex.Pattern pQty = java.util.regex.Pattern.compile("(?i).*(qty|quantity|billing\\s*qty|billed\\s*qty|nos|pcs).*");
            java.util.regex.Pattern pUom = java.util.regex.Pattern.compile("(?i).*(unit|uom|pkg|measure).*");
            java.util.regex.Pattern pRate = java.util.regex.Pattern.compile("(?i).*(unit\\s*rate|rate|price|unit\\s*price|mrp).*");
            java.util.regex.Pattern pTax = java.util.regex.Pattern.compile("(?i).*(gst\\s*%|tax\\s*%|rate\\s*%).*");
            java.util.regex.Pattern pDisc = java.util.regex.Pattern.compile("(?i).*(disc|discount|trade\\s*disc).*");
            java.util.regex.Pattern pTotal = java.util.regex.Pattern.compile("(?i).*(taxable\\s*amt|taxable\\s*value|net\\s*amt|amount|total).*");

            for (String l : lines) {
                String[] tokens = l.split("[|,\t]+|\\s{2,}");
                for (String tok : tokens) {
                    String trimmed = tok.trim();
                    if (trimmed.length() < 2) continue;

                    if (pItem.matcher(trimmed).matches() && !matchedCanonical.contains("Item Description")) {
                        detectedColumns.add(new ColumnRow(colOrderIdx++, trimmed, "Item Description", "Text", true));
                        matchedCanonical.add("Item Description");
                    } else if (pHsn.matcher(trimmed).matches() && !matchedCanonical.contains("HSN/SAC")) {
                        detectedColumns.add(new ColumnRow(colOrderIdx++, trimmed, "HSN/SAC", "Text", true));
                        matchedCanonical.add("HSN/SAC");
                    } else if (pQty.matcher(trimmed).matches() && !matchedCanonical.contains("Quantity")) {
                        detectedColumns.add(new ColumnRow(colOrderIdx++, trimmed, "Quantity", "Decimal", true));
                        matchedCanonical.add("Quantity");
                    } else if (pUom.matcher(trimmed).matches() && !matchedCanonical.contains("Unit of Measure")) {
                        detectedColumns.add(new ColumnRow(colOrderIdx++, trimmed, "Unit of Measure", "Text", true));
                        matchedCanonical.add("Unit of Measure");
                    } else if (pRate.matcher(trimmed).matches() && !matchedCanonical.contains("Unit Rate")) {
                        detectedColumns.add(new ColumnRow(colOrderIdx++, trimmed, "Unit Rate", "Currency", true));
                        matchedCanonical.add("Unit Rate");
                    } else if (pTax.matcher(trimmed).matches() && !matchedCanonical.contains("Tax Percentage")) {
                        detectedColumns.add(new ColumnRow(colOrderIdx++, trimmed, "Tax Percentage", "Percentage", true));
                        matchedCanonical.add("Tax Percentage");
                    } else if (pDisc.matcher(trimmed).matches() && !matchedCanonical.contains("Line Discount")) {
                        detectedColumns.add(new ColumnRow(colOrderIdx++, trimmed, "Line Discount", "Currency", false));
                        matchedCanonical.add("Line Discount");
                    } else if (pTotal.matcher(trimmed).matches() && !matchedCanonical.contains("Line Total Amount")) {
                        detectedColumns.add(new ColumnRow(colOrderIdx++, trimmed, "Line Total Amount", "Currency", true));
                        matchedCanonical.add("Line Total Amount");
                    }
                }
            }
        }

        if (!detectedColumns.isEmpty()) {
            columnsList.setAll(detectedColumns);
            reorderRows();
        }

        // =========================================================================
        // 3. EXPLICIT OCR COMPLETION CONFIRMATION MODAL
        // =========================================================================
        showOcrCompletionDialog(sourceFile, documentHeadersList.size(), columnsList.size());
    }

    private void showOcrCompletionDialog(File sourceFile, int docHeaderCount, int columnCount) {
        Dialog<ButtonType> dialog = new org.example.util.OwnedDialog<>(tableColumnsBlueprint);
        dialog.setTitle("OCR Blueprint Extraction Completed");
        dialog.setHeaderText("Dynamic Blueprint Successfully Extracted");
        dialog.getDialogPane().getStyleClass().addAll("approved-dialog", "erp-document-inspection-dialog");

        VBox content = new VBox(14);
        content.setPadding(new Insets(16));

        HBox banner = new HBox(12);
        banner.setAlignment(Pos.CENTER_LEFT);
        banner.getStyleClass().addAll("approved-card", "erp-page-identity-header");
        banner.setPadding(new Insets(10, 14, 10, 14));

        StackPane iconHolder = new StackPane();
        iconHolder.getChildren().setAll(IconFactory.icon("validate", 24));
        VBox titleBox = new VBox(2);
        Label lblTitle = new Label("OCR Extraction Complete — " + sourceFile.getName());
        lblTitle.getStyleClass().addAll("title-label", "accent-label-emerald");
        Label lblSubtitle = new Label("Native Windows OCR analyzed the document and generated dynamic schema blueprints.");
        lblSubtitle.getStyleClass().add("muted-label");
        titleBox.getChildren().addAll(lblTitle, lblSubtitle);
        banner.getChildren().addAll(iconHolder, titleBox);

        GridPane statsGrid = new GridPane();
        statsGrid.setHgap(16);
        statsGrid.setVgap(8);
        statsGrid.setPadding(new Insets(10));
        statsGrid.getStyleClass().add("approved-card");

        List<String> docHeaderNames = documentHeadersList.stream().map(DocumentHeaderRow::getHeaderLabel).collect(Collectors.toList());
        List<String> columnNames = columnsList.stream().map(c -> c.headerLabel.get()).collect(Collectors.toList());

        Label lblDocHdrTitle = new Label("High-Level Document Headers:");
        lblDocHdrTitle.getStyleClass().addAll("field-label", "accent-label-blue");
        String docPreview = String.join(", ", docHeaderNames.subList(0, Math.min(docHeaderNames.size(), 6))) + (docHeaderNames.size() > 6 ? ", ..." : "");
        Label lblDocHdrVal = new Label(docHeaderCount + " headers detected (" + docPreview + ")");
        lblDocHdrVal.getStyleClass().addAll("field-value", "bold-value-label");

        Label lblColTitle = new Label("Item-Level Table Columns:");
        lblColTitle.getStyleClass().addAll("field-label", "accent-label-amber");
        String colPreview = String.join(", ", columnNames.subList(0, Math.min(columnNames.size(), 6))) + (columnNames.size() > 6 ? ", ..." : "");
        Label lblColVal = new Label(columnCount + " table columns detected (" + colPreview + ")");
        lblColVal.getStyleClass().addAll("field-value", "bold-value-label");

        statsGrid.addRow(0, lblDocHdrTitle, lblDocHdrVal);
        statsGrid.addRow(1, lblColTitle, lblColVal);

        Label guidance = new Label("Both blueprint tabs have been populated dynamically with detected fields and extracted sample values. You can review, reorder, or add custom fields, then click 'Lock & Save Template Version' to make this version active for Bill to Tally.");
        guidance.setWrapText(true);
        guidance.getStyleClass().add("page-subtitle");

        content.getChildren().addAll(banner, statsGrid, guidance);
        dialog.getDialogPane().setContent(content);

        ButtonType btnReview = new ButtonType("Review Blueprint & Version", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(btnReview);

        dialog.showAndWait();
    }

    private void setHeaderSampleValue(String canonicalField, String val) {
        if (val == null || val.isBlank()) return;
        for (DocumentHeaderRow r : documentHeadersList) {
            if (canonicalField.equalsIgnoreCase(r.canonicalField.get())) {
                r.sampleValue.set(val.trim());
                break;
            }
        }
    }

    @FXML
    public void handleDeleteVersion() {
        if (activeTemplate == null || activeTemplate.id() == null || activeTemplate.id() <= 0) {
            ToastManager.warning(tableColumnsBlueprint, "Cannot Delete", "No saved template version selected to delete.");
            return;
        }

        String vCode = activeTemplate.versionCode() != null ? activeTemplate.versionCode() : "this";
        Alert alert = new OwnedAlert(Alert.AlertType.CONFIRMATION,
                "Are you sure you want to permanently delete template version " + vCode + "?",
                ButtonType.YES, ButtonType.NO);
        alert.setTitle("Confirm Delete Version");
        alert.setHeaderText("Delete Template Version " + vCode);
        alert.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.YES) {
                Long templateId = activeTemplate.id();
                UiTaskExecutor.submitAction(
                        "delete-template-version",
                        () -> {
                            api.deleteTemplate(templateId);
                            return true;
                        },
                        ok -> {
                            ToastManager.success(tableColumnsBlueprint, "Version Deleted", "Template version " + vCode + " was deleted.");
                            currentVersions.removeIf(v -> Objects.equals(v.id(), templateId));
                            if (!currentVersions.isEmpty()) {
                                selectTemplateVersion(currentVersions.get(0));
                            } else {
                                initDefaultVersion("v1");
                            }
                            renderVersionPills();
                        },
                        err -> AppDialogService.error(tableColumnsBlueprint, "Delete Error", "Could not delete template version", err.getMessage())
                );
            }
        });
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
        if (activeClient == null) {
            ToastManager.info(tableColumnsBlueprint, "Select Client", "Please select a client from the dropdown first to view full GST details.");
            return;
        }

        String gstin = activeClient.gstin();
        if (gstin != null && !gstin.isBlank()) {
            ToastManager.info(tableColumnsBlueprint, "Fetching GST Profile", "Querying verified live taxpayer profile for " + gstin + "...");
            UiTaskExecutor.submitLatest(
                    "view-full-gst-profile",
                    () -> api.verifyGstin(gstin),
                    profile -> {
                        if (profile != null) {
                            GstFullDetailsDialog.show(tableColumnsBlueprint, profile);
                        } else {
                            GstFullDetailsDialog.show(tableColumnsBlueprint, activeClient);
                        }
                    },
                    err -> GstFullDetailsDialog.show(tableColumnsBlueprint, activeClient)
            );
        } else {
            GstFullDetailsDialog.show(tableColumnsBlueprint, activeClient);
        }
    }

    @FXML
    public void handleBackToDirectory() {
        DashboardController.navigateFromChildPage("Clients", "/fxml/pages/ClientsDirectory.fxml");
    }

    @Override
    public void onScreenShown(boolean reusedFromCache) {
        UiTaskExecutor.submitLatest(
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
