package org.example.bank;

import org.example.util.AppDialogService;

import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.example.api.bank.BankStatementApiClient;
import org.example.api.master.MasterApiClient;
import org.example.importing.ImportTemplateService;
import org.example.model.Lookup;
import org.example.service.NotificationService;
import org.example.util.*;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/**
 * Universal Bank Statement Import & Data Shipping Wizard for Jasvi ERP Desktop.
 * Implements full 5-stage workflow with multi-bank normalization, template downloads (.xlsx, .xls, .csv),
 * color-coded preflight validation table, Light/Dark mode parity, and generous non-clipping workspace sizing.
 */
public final class UniversalBankStatementImportDialog extends OwnedDialog<Boolean> {

    private final BankStatementApiClient api;
    private final MasterApiClient masterClient;
    private final Runnable onSuccessCallback;
    private final Consumer<Long> onBatchSuccessCallback;
    private final UniversalBankStatementParser parser = new UniversalBankStatementParser();
    private final boolean lightMode;

    private final ComboBox<BankAccountChoice> cmbBankAccount = new ComboBox<>();
    private final Label lblSelectedFile = new Label("No statement file selected (drag & drop or browse below)");
    private final TableView<UniversalBankStatementParser.AuditRow> table = new TableView<>();
    private final ObservableList<UniversalBankStatementParser.AuditRow> auditRows = FXCollections.observableArrayList();
    private final Button btnStartShipping = new Button("Start Data Shipping");
    private final Label lblTotalRows = new Label("Total: 0");
    private final Label lblValidRows = new Label("Valid: 0");
    private final Label lblErrorRows = new Label("Errors: 0");

    private File chosenFile = null;
    private UniversalBankStatementParser.AuditParseResult lastAuditResult = null;

    public record BankAccountChoice(String lookupCode, String bankName, String accountNumber, String holderName) {
        @Override
        public String toString() {
            String acc = (accountNumber == null || accountNumber.isBlank()) ? "General" : accountNumber;
            return bankName + " - " + acc + (holderName != null && !holderName.isBlank() ? " (" + holderName + ")" : "");
        }
    }

    public static void show(Window owner, BankStatementApiClient api, MasterApiClient masterClient, Consumer<Long> onBatchSuccess) {
        UniversalBankStatementImportDialog dialog = new UniversalBankStatementImportDialog(owner, api, masterClient, onBatchSuccess);
        dialog.showAndWait();
    }

    public static void show(Window owner, BankStatementApiClient api, MasterApiClient masterClient, Runnable onSuccess) {
        UniversalBankStatementImportDialog dialog = new UniversalBankStatementImportDialog(owner, api, masterClient, onSuccess != null ? bId -> onSuccess.run() : null);
        dialog.showAndWait();
    }

    public UniversalBankStatementImportDialog(Window owner, BankStatementApiClient api, MasterApiClient masterClient, Consumer<Long> onBatchSuccess) {
        super(owner != null && owner.getScene() != null ? owner.getScene().getRoot() : null);
        this.api = api != null ? api : new BankStatementApiClient();
        this.masterClient = masterClient != null ? masterClient : new MasterApiClient();
        this.onBatchSuccessCallback = onBatchSuccess;
        this.onSuccessCallback = onBatchSuccess != null ? () -> onBatchSuccess.accept(null) : null;
        this.lightMode = detectLightMode(owner);

        setTitle("Data Shipping Wizard - Bank Statement");
        setResizable(true);

        DialogPane pane = getDialogPane();
        pane.getStyleClass().addAll("approved-dialog", "erp-screen-root", "universal-import-dialog");
        if (lightMode && !pane.getStyleClass().contains("light-theme")) {
            pane.getStyleClass().add("light-theme");
        }
        pane.getButtonTypes().add(ButtonType.CLOSE);

        // Dynamic adaptive sizing: responsive bounds based on monitor usable area (Rule 12)
        PopupTableWorkspace.applyInspectionSizing(this, owner != null && owner.getScene() != null ? owner.getScene().getRoot() : null);
        setOnShown(ev -> {
            if (pane.getScene() != null && pane.getScene().getWindow() instanceof javafx.stage.Stage stage) {
                WindowUtilsFx.fitDialogToOwnerScreen(stage, owner);
            }
        });

        VBox content = new VBox(10);
        content.setPadding(new Insets(10, 16, 10, 16));
        content.setFillWidth(true);
        VBox.setVgrow(content, Priority.ALWAYS);

        // Header Title Card
        content.getChildren().add(buildHeaderCard());

        // 1 & 2. Compact Target Bank Account & File Ingestion Toolbar
        content.getChildren().add(buildCompactConfigurationCard());

        // 3. Preflight Color-Coded Validation Table (Expanded Workspace)
        VBox tableSection = buildValidationTableSection();
        VBox.setVgrow(tableSection, Priority.ALWAYS);
        content.getChildren().add(tableSection);

        // 4. Action Footer
        content.getChildren().add(buildActionFooter());

        pane.setContent(content);
        org.example.util.SearchableComboBoxHelper.makeSearchable(cmbBankAccount, b -> b == null ? "" : b.toString());
        org.example.util.SearchableComboBoxHelper.makeAllSearchable(content);

        // Load Bank Accounts from Master Data
        loadBankAccounts();
    }

    private static boolean detectLightMode(Window owner) {
        if (owner != null && owner.getScene() != null && owner.getScene().getRoot() != null) {
            List<String> classes = owner.getScene().getRoot().getStyleClass();
            if (classes.contains("dse-theme-light") || classes.contains("light-theme") || classes.contains("theme-light")) {
                return true;
            }
            if (classes.contains("dse-theme-dark")) {
                return false;
            }
        }
        return !org.example.theme.ThemeManager.isDark();
    }

    private Node buildHeaderCard() {
        HBox header = new HBox(14);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().addAll("erp-page-identity-header", "approved-card");
        header.setPadding(new Insets(10, 16, 10, 16));

        StackPane icon = new StackPane();
        icon.getStyleClass().add("erp-page-header-icon");
        icon.getChildren().setAll(IconFactory.icon("bank", 24));

        VBox titleBox = new VBox(2);
        Label title = new Label("DATA LOADER / DATA SHIPPING WIZARD - MODULE: BANK STATEMENT");
        title.getStyleClass().addAll("section-title", "erp-page-title", "approved-strong-label");

        Label sub = new Label("Universal statement ingestion supporting .xlsx, .xls, and .csv across all commercial banking templates with preflight parity.");
        sub.getStyleClass().addAll("muted-label", "erp-page-subtitle");
        titleBox.getChildren().addAll(title, sub);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button btnGuide = org.example.util.ScreenGuidanceManager.createGuideButton(this, "BANK_STATEMENT");

        header.getChildren().addAll(icon, titleBox, spacer, btnGuide);
        org.example.util.DocumentInspectionScaffold.installDragPhysics(header, this);
        return header;
    }

    private Node buildCompactConfigurationCard() {
        VBox box = new VBox(8);
        box.getStyleClass().addAll("approved-card");
        box.setPadding(new Insets(8, 12, 8, 12));

        // Row 1: Target Bank Account + Template Download + Browse Button
        HBox row1 = new HBox(12);
        row1.setAlignment(Pos.CENTER_LEFT);

        HBox bankBox = new HBox(8);
        bankBox.setAlignment(Pos.CENTER_LEFT);
        Label lblBank = new Label("Target Bank:");
        lblBank.setGraphic(IconFactory.compactIcon("bank", 15));
        lblBank.getStyleClass().addAll("approved-strong-label", "erp-page-subtitle");

        cmbBankAccount.setPrefWidth(380);
        cmbBankAccount.setPrefHeight(34);
        cmbBankAccount.setPromptText("Select Target Bank Account from Master...");
        HBox.setHgrow(cmbBankAccount, Priority.ALWAYS);
        bankBox.getChildren().addAll(lblBank, cmbBankAccount);
        HBox.setHgrow(bankBox, Priority.ALWAYS);

        MenuButton btnDownloadTemplate = new MenuButton("Download Bank Template (.xlsx / .xls / .csv)");
        btnDownloadTemplate.setGraphic(IconFactory.compactIcon("excel", 15));
        btnDownloadTemplate.getStyleClass().addAll("approved-button", "approved-secondary-button");

        MenuItem itemAllThree = new MenuItem("Download All 3 Formats (.xlsx + .xls + .csv)");
        itemAllThree.setGraphic(IconFactory.compactIcon("package", 15));
        itemAllThree.getStyleClass().add("erp-menu-semantic-orange");
        itemAllThree.setOnAction(e -> onDownloadTemplate("ALL"));

        MenuItem itemXlsx = new MenuItem("Excel Workbook (.xlsx) — Multi-Color Headers");
        itemXlsx.setGraphic(IconFactory.compactIcon("excel", 15));
        itemXlsx.getStyleClass().add("erp-menu-semantic-green");
        itemXlsx.setOnAction(e -> onDownloadTemplate("xlsx"));

        MenuItem itemXls = new MenuItem("Excel 97-2003 Workbook (.xls) — Multi-Color Headers");
        itemXls.setGraphic(IconFactory.compactIcon("inventory", 15));
        itemXls.getStyleClass().add("erp-menu-semantic-teal");
        itemXls.setOnAction(e -> onDownloadTemplate("xls"));

        MenuItem itemCsv = new MenuItem("CSV (Comma Delimited) (.csv) — Mandatory (*) Headers");
        itemCsv.setGraphic(IconFactory.compactIcon("document", 15));
        itemCsv.getStyleClass().add("erp-menu-semantic-blue");
        itemCsv.setOnAction(e -> onDownloadTemplate("csv"));
        btnDownloadTemplate.getItems().addAll(itemAllThree, new SeparatorMenuItem(), itemXlsx, itemXls, itemCsv);

        Button btnBrowse = new Button("Select File (.xlsx, .xls, .csv)");
        btnBrowse.setGraphic(IconFactory.compactIcon("document", 15));
        btnBrowse.getStyleClass().addAll("approved-button", "primary-button");
        btnBrowse.setOnAction(e -> onBrowseFile());

        row1.getChildren().addAll(bankBox, btnDownloadTemplate, btnBrowse);

        // Row 2: Compact Drag & Drop Target Strip + Colorized File Status Badge
        HBox row2 = new HBox(12);
        row2.setAlignment(Pos.CENTER_LEFT);

        HBox dropStrip = new HBox(8);
        dropStrip.setAlignment(Pos.CENTER_LEFT);
        dropStrip.setPadding(new Insets(5, 12, 5, 12));
        dropStrip.getStyleClass().addAll("universal-dropzone");
        Label dropText = new Label("Drag & Drop statement file here or on table (.xlsx, .xls, .csv)");
        dropText.getStyleClass().add("universal-dropzone-text");
        dropStrip.getChildren().addAll(IconFactory.compactIcon("import", 15), dropText);

        dropStrip.setOnDragOver(event -> {
            if (event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });
        dropStrip.setOnDragDropped(event -> {
            Dragboard db = event.getDragboard();
            boolean success = false;
            if (db.hasFiles() && !db.getFiles().isEmpty()) {
                File f = db.getFiles().getFirst();
                processSelectedFile(f);
                success = true;
            }
            event.setDropCompleted(success);
            event.consume();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        lblSelectedFile.getStyleClass().setAll("badge", "badge-pill", "status-warning");
        lblSelectedFile.setGraphic(IconFactory.compactIcon("alert-circle", 14));
        lblSelectedFile.setContentDisplay(ContentDisplay.LEFT);
        lblSelectedFile.setGraphicTextGap(6);

        row2.getChildren().addAll(dropStrip, spacer, lblSelectedFile);

        box.getChildren().addAll(row1, row2);
        return box;
    }

    private VBox buildValidationTableSection() {
        VBox box = new VBox(8);
        VBox.setVgrow(box, Priority.ALWAYS);

        HBox top = new HBox(12);
        top.setAlignment(Pos.CENTER_LEFT);

        Label lbl = new Label("3. PREFLIGHT COLOR-CODED VALIDATION TABLE");
        lbl.getStyleClass().addAll("form-section-title", "section-title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        lblTotalRows.getStyleClass().addAll("approved-status", "erp-table-value-colour-blue");
        lblValidRows.getStyleClass().addAll("approved-status", "erp-table-value-colour-green");
        lblErrorRows.getStyleClass().addAll("approved-status", "erp-table-value-colour-red");

        top.getChildren().addAll(lbl, spacer, lblTotalRows, lblValidRows, lblErrorRows);

        configureTable();
        VBox.setVgrow(table, Priority.ALWAYS);

        box.getChildren().addAll(top, table);
        return box;
    }

    private void configureTable() {
        table.getProperties().put("erp.noRowSelection", Boolean.TRUE);
        if (!table.getStyleClass().contains("erp-no-row-selection")) {
            table.getStyleClass().add("erp-no-row-selection");
        }
        PopupTableWorkspace.prepareTable(table, "erp-table-profile-responsive");
        table.setItems(auditRows);

        // 1. Line / Source Index (#)
        TableColumn<UniversalBankStatementParser.AuditRow, Number> colIdx = new TableColumn<>("#");
        colIdx.setCellValueFactory(v -> new SimpleIntegerProperty(v.getValue().sourceIndex()));

        // 2. Status Badge
        TableColumn<UniversalBankStatementParser.AuditRow, String> colStatus = new TableColumn<>("Status");
        colStatus.setCellValueFactory(v -> new SimpleStringProperty(v.getValue().status()));
        colStatus.setCellFactory(c -> SemanticTableCells.status("validation"));

        // 3. Date
        TableColumn<UniversalBankStatementParser.AuditRow, String> colDate = new TableColumn<>("Date");
        colDate.setCellValueFactory(v -> new SimpleStringProperty(v.getValue().date()));

        // 4. Reference
        TableColumn<UniversalBankStatementParser.AuditRow, String> colRef = new TableColumn<>("Reference / UTR");
        colRef.setCellValueFactory(v -> new SimpleStringProperty(v.getValue().reference()));

        // 5. Description / Narration
        TableColumn<UniversalBankStatementParser.AuditRow, String> colDesc = new TableColumn<>("Narration / Description");
        colDesc.setCellValueFactory(v -> new SimpleStringProperty(v.getValue().description()));

        // 6. Debit
        TableColumn<UniversalBankStatementParser.AuditRow, Number> colDr = new TableColumn<>("Debit (Dr)");
        colDr.setCellValueFactory(v -> new SimpleDoubleProperty(v.getValue().debit()));
        colDr.setCellFactory(x -> SemanticTableCells.amount("debit"));

        // 7. Credit
        TableColumn<UniversalBankStatementParser.AuditRow, Number> colCr = new TableColumn<>("Credit (Cr)");
        colCr.setCellValueFactory(v -> new SimpleDoubleProperty(v.getValue().credit()));
        colCr.setCellFactory(x -> SemanticTableCells.amount("credit"));

        // 8. Balance
        TableColumn<UniversalBankStatementParser.AuditRow, Number> colBal = new TableColumn<>("Balance");
        colBal.setCellValueFactory(v -> new SimpleDoubleProperty(v.getValue().balance()));
        colBal.setCellFactory(x -> SemanticTableCells.amount("balance"));

        // 9. Error Message / Validation Detail
        TableColumn<UniversalBankStatementParser.AuditRow, String> colError = new TableColumn<>("Validation Message");
        colError.setCellValueFactory(v -> new SimpleStringProperty(v.getValue().error() != null ? v.getValue().error() : "Passed all statutory checks"));
        colError.setCellFactory(tc -> new TableCell<>() {
            @Override
            protected void updateItem(String val, boolean empty) {
                super.updateItem(val, empty);
                getStyleClass().removeAll("muted-label", "status-negative");
                if (empty || val == null) {
                    setText(null);
                    setTooltip(null);
                } else {
                    setText(val);
                    setTooltip(new Tooltip(val));
                    if (val.contains("Passed")) {
                        getStyleClass().add("muted-label");
                    } else {
                        getStyleClass().add("status-negative");
                    }
                }
            }
        });

        table.getColumns().setAll(colIdx, colStatus, colDate, colRef, colDesc, colDr, colCr, colBal, colError);
        DynamicTableLayoutManager.install(table);

        // Allow direct drag & drop onto the entire table area
        table.setOnDragOver(event -> {
            if (event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });
        table.setOnDragDropped(event -> {
            Dragboard db = event.getDragboard();
            boolean success = false;
            if (db.hasFiles() && !db.getFiles().isEmpty()) {
                File f = db.getFiles().getFirst();
                processSelectedFile(f);
                success = true;
            }
            event.setDropCompleted(success);
            event.consume();
        });
    }

    private Node buildActionFooter() {
        HBox footer = new HBox(14);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(10, 0, 0, 0));

        Button btnBack = new Button("Back");
        btnBack.getStyleClass().addAll("approved-button", "approved-secondary-button");
        btnBack.setPrefWidth(120);
        btnBack.setPrefHeight(38);
        btnBack.setOnAction(e -> close());

        btnStartShipping.getStyleClass().addAll("approved-button", "primary-button");
        btnStartShipping.setPrefWidth(200);
        btnStartShipping.setPrefHeight(38);
        btnStartShipping.setDisable(true);
        btnStartShipping.setOnAction(e -> onStartDataShipping());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        footer.getChildren().addAll(btnBack, spacer, btnStartShipping);
        return footer;
    }

    private void loadBankAccounts() {
        try {
            List<Lookup> bankLookups = masterClient.lookupsByCategoryCode("BANK_ACCOUNT");
            ObservableList<BankAccountChoice> choices = FXCollections.observableArrayList();
            if (bankLookups != null) {
                for (var b : bankLookups) {
                    choices.add(new BankAccountChoice(b.getLookupCode(), b.getDescription(), b.getLookupValue(), "Primary A/c"));
                }
            }
            if (choices.isEmpty()) {
                choices.add(new BankAccountChoice("BNK001", "Primary Bank Account", "0000000000", "Current A/c"));
            }
            cmbBankAccount.setItems(choices);
            cmbBankAccount.getSelectionModel().selectFirst();
        } catch (Exception ex) {
            cmbBankAccount.setItems(FXCollections.observableArrayList(
                new BankAccountChoice("BNK001", "Kotak Mahindra Bank", "1234567890", "Current A/c"),
                new BankAccountChoice("BNK002", "HDFC Bank", "9876543210", "Current A/c")
            ));
            cmbBankAccount.getSelectionModel().selectFirst();
        }
    }

    private void onDownloadTemplate(String formatMode) {
        String ext = "ALL".equalsIgnoreCase(formatMode) ? "xlsx" : formatMode.toLowerCase(Locale.ROOT);
        File target = ExportFeedbackManager.resolveWorkspaceExportFile("Templates", "Bank_Statement_Template", ext);
        if (target == null) return;
        if (!ExportFeedbackManager.checkFileWriteable(table, target)) return;

        try {
            ImportTemplateService service = new ImportTemplateService();
            String ver = org.example.update.BuildInfo.version();
            if ("ALL".equalsIgnoreCase(formatMode)) {
                List<Path> written = service.writeAllThreeFormats(target.toPath(), "Bank Statement", ver);
                ExportFeedbackManager.notifyExportSuccess(table, written.getFirst().toFile(), "All 3 Bank Templates Downloaded", 3);
            } else {
                service.write(target.toPath(), "Bank Statement", ver);
                ExportFeedbackManager.notifyExportSuccess(table, target, "Bank Statement Template Downloaded");
            }
        } catch (Exception ex) {
            AppDialogService.error(table, "Template Error", "Template Export Failed", "Could not write template: " + ex.getMessage());
        }
    }

    private void onBrowseFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select Bank Statement File");
        chooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("All Supported Formats (*.xlsx, *.xls, *.csv)", "*.xlsx", "*.xls", "*.csv", "*.txt"),
            new FileChooser.ExtensionFilter("Excel Workbook (*.xlsx)", "*.xlsx"),
            new FileChooser.ExtensionFilter("Excel 97-2003 Workbook (*.xls)", "*.xls"),
            new FileChooser.ExtensionFilter("CSV (Comma Delimited) (*.csv)", "*.csv")
        );
        File file = chooser.showOpenDialog(getDialogPane().getScene().getWindow());
        if (file != null) {
            processSelectedFile(file);
        }
    }

    private void processSelectedFile(File file) {
        this.chosenFile = file;
        lblSelectedFile.getStyleClass().setAll("badge", "badge-pill", "status-positive");
        lblSelectedFile.setGraphic(IconFactory.compactIcon("check", 14));
        lblSelectedFile.setText(file.getName() + " (" + Math.max(1, file.length() / 1024) + " KB)");

        try {
            lastAuditResult = parser.parseWithAudit(file.toPath());
            auditRows.setAll(lastAuditResult.auditRows());

            int total = lastAuditResult.totalRows();
            int errors = lastAuditResult.errorCount();
            int valid = total - errors;

            lblTotalRows.setText("Total: " + total);
            lblValidRows.setText("Valid: " + valid);
            lblErrorRows.setText("Errors: " + errors);

            btnStartShipping.setDisable(errors > 0 || valid == 0);
            if (errors > 0) {
                AppDialogService.error(table, "Preflight Errors Detected", "File contains " + errors + " error rows. Please correct highlighted rows in red.");
            } else {
                AppDialogService.success(table, "Validation Passed", "All " + valid + " rows passed verification and are ready for data shipping.");
            }
        } catch (Exception ex) {
            auditRows.clear();
            lblTotalRows.setText("Total: 0");
            lblValidRows.setText("Valid: 0");
            lblErrorRows.setText("Errors: 1");
            btnStartShipping.setDisable(true);
            lblSelectedFile.getStyleClass().setAll("badge", "badge-pill", "status-negative");
            lblSelectedFile.setGraphic(IconFactory.compactIcon("alert-triangle", 14));
            lblSelectedFile.setText("Parse Error: " + file.getName());
            AppDialogService.error(table, "Parse Error", "Bank Statement Analysis Failed", "Failed to analyze bank statement: " + ex.getMessage());
        }
    }

    private void onStartDataShipping() {
        if (chosenFile == null || lastAuditResult == null || lastAuditResult.statement() == null) {
            AppDialogService.error(table, "No Valid Statement", "Validation Error", "Please select a valid bank statement file first.");
            return;
        }

        BankAccountChoice targetAccount = cmbBankAccount.getValue();
        String bankName = targetAccount != null ? targetAccount.bankName() : "Bank";
        String bankAcc = targetAccount != null ? targetAccount.accountNumber() : "0000000000";
        String accHolder = targetAccount != null ? targetAccount.holderName() : "Jasvi Industries";

        var statement = lastAuditResult.statement();

        var request = new BankStatementApiClient.ImportRequest(
            bankName,
            bankAcc,
            accHolder,
            statement.statementFrom(),
            statement.statementTo(),
            statement.currency(),
            statement.openingBalance(),
            statement.closingBalance(),
            statement.sourceFingerprint(),
            chosenFile.getName(),
            statement.sourceCsv(),
            org.example.service.SessionService.current() != null ? org.example.service.SessionService.current().getFullName() : "Admin",
            false,
            statement.rows()
        );

        btnStartShipping.setDisable(true);
        btnStartShipping.setText("Shipping Data to Server…");

        LiveOperationDialog.execute(
            table,
            "Importing Bank Statement: " + chosenFile.getName(),
            "banking",
            "Validating Statement Transactions…",
            reporter -> {
                reporter.stage(1, 3, "Validating " + statement.rows().size() + " statement transactions…");
                reporter.log("Parsed " + statement.rows().size() + " transaction records from " + chosenFile.getName());
                reporter.progress(0.33);

                reporter.stage(2, 3, "Shipping batch to Spring Boot backend…");
                reporter.log("Encoding payload and transmitting to server API…");
                var result = api.importStatement(request);
                reporter.progress(0.75);

                reporter.stage(3, 3, "Finalizing ledger reconciliation…");
                reporter.log("Server processed " + result.importedRows() + " transactions. Auto-reconciliation active.");
                reporter.progress(1.0);
                return result;
            },
            result -> {
                Long batchId = result.batch() != null ? result.batch().id() : null;
                Window ownerWindow = getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null;
                Node targetHost = ownerWindow != null && ownerWindow.getScene() != null ? ownerWindow.getScene().getRoot() : null;
                close();
                if (onBatchSuccessCallback != null) onBatchSuccessCallback.accept(batchId);
                else if (onSuccessCallback != null) onSuccessCallback.run();

                javafx.application.Platform.runLater(() -> {
                    if (result.alreadyImported()) {
                        AppDialogService.warning(targetHost, "Already Imported", "Duplicate Statement Detected",
                                "This exact bank statement was already imported previously.\nBatch Reference: "
                                        + (result.batch() != null ? result.batch().statementReference() : "N/A"));
                    } else {
                        NotificationService.createNotification(
                                NotificationService.Category.BANKING,
                                "Bank Statement Imported",
                                "Imported " + result.importedRows() + " transactions (Ref: " + (result.batch() != null ? result.batch().statementReference() : "") + ")",
                                "INFO",
                                "/fxml/pages/BankStatement.fxml",
                                result.batch() != null ? result.batch().statementReference() : null
                        );
                        AppDialogService.success(targetHost, "Statement Import Complete", "Transactions Ingested Successfully",
                                "Successfully imported " + result.importedRows() + " bank transactions into the ledger.\nStatement Batch: "
                                        + (result.batch() != null ? result.batch().statementReference() : ""));
                    }
                });
            },
            ex -> {
                btnStartShipping.setDisable(false);
                btnStartShipping.setText("Start Data Shipping");
                AppDialogService.error(table, "Data Shipping Failed", "Server Import Rejected", "Server import rejected: " + ex.getMessage());
            }
        );
    }
}
