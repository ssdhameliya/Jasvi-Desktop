package org.example.bank;

import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.example.api.bank.BankStatementApiClient;
import org.example.api.master.MasterApiClient;
import javafx.scene.Node;
import org.example.model.Lookup;
import org.example.importing.ImportTemplateService;
import org.example.util.*;

import java.io.File;
import java.nio.file.Path;
import java.util.*;

/**
 * Universal Bank Statement Import & Data Shipping Wizard for Jasvi ERP Desktop.
 * Implements full 5-stage workflow with multi-bank normalization, template downloads,
 * color-coded preflight validation table, and generous non-clipping workspace sizing.
 */
public final class UniversalBankStatementImportDialog extends OwnedDialog<Boolean> {

    private final BankStatementApiClient api;
    private final MasterApiClient masterClient;
    private final Runnable onSuccessCallback;
    private final UniversalBankStatementParser parser = new UniversalBankStatementParser();

    private final ComboBox<BankAccountChoice> cmbBankAccount = new ComboBox<>();
    private final Label lblSelectedFile = new Label("No statement file selected (drag & drop or browse below)");
    private final TableView<KotakBankStatementCsvParser.AuditRow> table = new TableView<>();
    private final ObservableList<KotakBankStatementCsvParser.AuditRow> auditRows = FXCollections.observableArrayList();
    private final Button btnStartShipping = new Button("Start Data Shipping");
    private final Label lblTotalRows = new Label("Total: 0");
    private final Label lblValidRows = new Label("Valid: 0");
    private final Label lblErrorRows = new Label("Errors: 0");

    private File chosenFile = null;
    private KotakBankStatementCsvParser.AuditParseResult lastAuditResult = null;

    public record BankAccountChoice(String lookupCode, String bankName, String accountNumber, String holderName) {
        @Override
        public String toString() {
            String acc = (accountNumber == null || accountNumber.isBlank()) ? "General" : accountNumber;
            return bankName + " - " + acc + (holderName != null && !holderName.isBlank() ? " (" + holderName + ")" : "");
        }
    }

    public static void show(Window owner, BankStatementApiClient api, MasterApiClient masterClient, Runnable onSuccess) {
        UniversalBankStatementImportDialog dialog = new UniversalBankStatementImportDialog(owner, api, masterClient, onSuccess);
        dialog.showAndWait();
    }

    public UniversalBankStatementImportDialog(Window owner, BankStatementApiClient api, MasterApiClient masterClient, Runnable onSuccess) {
        super(owner != null ? owner.getScene().getRoot() : null);
        this.api = api != null ? api : new BankStatementApiClient();
        this.masterClient = masterClient != null ? masterClient : new MasterApiClient();
        this.onSuccessCallback = onSuccess;

        setTitle("Data Shipping Wizard - Bank Statement");
        setResizable(true);

        DialogPane pane = getDialogPane();
        pane.getStyleClass().addAll("approved-dialog", "erp-screen-root", "universal-import-dialog");
        pane.getButtonTypes().add(ButtonType.CLOSE);

        // Apply generous sizing so nothing is clipped
        pane.setPrefWidth(1380);
        pane.setMinWidth(1100);
        pane.setPrefHeight(820);
        pane.setMinHeight(640);
        PopupTableWorkspace.applyInspectionSizing(this, owner != null ? owner.getScene().getRoot() : null);

        VBox content = new VBox(14);
        content.setPadding(new Insets(14, 18, 14, 18));
        content.setFillWidth(true);
        VBox.setVgrow(content, Priority.ALWAYS);

        // Header Title Card
        content.getChildren().add(buildHeaderCard());

        // 1. Target Bank Account Section
        content.getChildren().add(buildBankAccountSection());

        // 2. Template Download & File Ingestion Section
        content.getChildren().add(buildFileIngestionSection());

        // 3. Smart Normalization Summary Card
        content.getChildren().add(buildNormalizationSummaryCard());

        // 4. Preflight Color-Coded Validation Table
        VBox tableSection = buildValidationTableSection();
        VBox.setVgrow(tableSection, Priority.ALWAYS);
        content.getChildren().add(tableSection);

        // 5. Action Footer
        content.getChildren().add(buildActionFooter());

        pane.setContent(content);

        // Load Bank Accounts from Master Data
        loadBankAccounts();
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
        title.getStyleClass().add("section-title");
        title.setStyle("-fx-font-weight: 800; -fx-font-size: 15px; -fx-text-fill: #38bdf8;");

        Label sub = new Label("Universal statement ingestion supporting .xlsx, .xls, and .csv across all commercial banking templates with preflight parity.");
        sub.getStyleClass().add("muted-label");
        titleBox.getChildren().addAll(title, sub);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        header.getChildren().addAll(icon, titleBox, spacer);
        return header;
    }

    private Node buildBankAccountSection() {
        VBox box = new VBox(6);
        box.getStyleClass().addAll("approved-card");
        box.setPadding(new Insets(10, 14, 10, 14));

        Label lbl = new Label("1. TARGET BANK ACCOUNT (Populated from Master: BANK_ACCOUNT)");
        lbl.setStyle("-fx-font-weight: 700; -fx-font-size: 12px; -fx-text-fill: #e2e8f0;");

        cmbBankAccount.setMaxWidth(Double.MAX_VALUE);
        cmbBankAccount.setPrefHeight(36);
        cmbBankAccount.setPromptText("Select Target Bank Account from Master...");
        HBox.setHgrow(cmbBankAccount, Priority.ALWAYS);

        box.getChildren().addAll(lbl, cmbBankAccount);
        return box;
    }

    private Node buildFileIngestionSection() {
        VBox box = new VBox(8);
        box.getStyleClass().addAll("approved-card");
        box.setPadding(new Insets(10, 14, 10, 14));

        Label lbl = new Label("2. TEMPLATE DOWNLOAD & FILE INGESTION");
        lbl.setStyle("-fx-font-weight: 700; -fx-font-size: 12px; -fx-text-fill: #e2e8f0;");

        HBox controls = new HBox(12);
        controls.setAlignment(Pos.CENTER_LEFT);

        Button btnDownloadTemplate = new Button("Download Bank Statement Template (.xlsx / .csv)");
        btnDownloadTemplate.setGraphic(IconFactory.compactIcon("excel", 15));
        btnDownloadTemplate.getStyleClass().addAll("approved-button", "approved-secondary-button");
        btnDownloadTemplate.setOnAction(e -> onDownloadTemplate());

        Button btnBrowse = new Button("Select File (.xlsx, .xls, .csv)");
        btnBrowse.setGraphic(IconFactory.compactIcon("document", 15));
        btnBrowse.getStyleClass().addAll("approved-button", "primary-button");
        btnBrowse.setOnAction(e -> onBrowseFile());

        lblSelectedFile.setStyle("-fx-font-family: monospace; -fx-font-size: 12px; -fx-text-fill: #38bdf8;");
        HBox.setHgrow(lblSelectedFile, Priority.ALWAYS);

        controls.getChildren().addAll(btnDownloadTemplate, btnBrowse, lblSelectedFile);

        // Drag & drop dropzone
        VBox dropZone = new VBox(6);
        dropZone.setAlignment(Pos.CENTER);
        dropZone.setPadding(new Insets(12));
        dropZone.setStyle("-fx-border-color: rgba(56, 189, 248, 0.35); -fx-border-style: dashed; -fx-border-width: 2px; -fx-border-radius: 8px; -fx-background-color: rgba(15, 23, 42, 0.45);");

        Label dropText = new Label("Drag & Drop ANY Bank Statement file here (.xlsx, .xls, .csv)");
        dropText.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 12px;");
        dropZone.getChildren().addAll(IconFactory.icon("import", 22), dropText);

        dropZone.setOnDragOver(event -> {
            if (event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });

        dropZone.setOnDragDropped(event -> {
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

        box.getChildren().addAll(lbl, controls, dropZone);
        return box;
    }

    private Node buildNormalizationSummaryCard() {
        HBox box = new HBox(20);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().addAll("approved-card");
        box.setPadding(new Insets(8, 14, 8, 14));
        box.setStyle("-fx-background-color: rgba(30, 41, 59, 0.5); -fx-border-color: rgba(255,255,255,0.06); -fx-border-radius: 6px;");

        Label title = new Label("3. SMART COLUMN NORMALIZATION:");
        title.setStyle("-fx-font-weight: 700; -fx-font-size: 11px; -fx-text-fill: #cbd5e1;");

        Label chip1 = new Label("• System Template ──► 1-to-1 Direct Mapping");
        chip1.setStyle("-fx-font-size: 11px; -fx-text-fill: #34d399;");

        Label chip2 = new Label("• Known Banks (Kotak, HDFC, ICICI, SBI, Axis) ──► Auto-detected & Mapped");
        chip2.setStyle("-fx-font-size: 11px; -fx-text-fill: #38bdf8;");

        Label chip3 = new Label("• Custom File ──► Fuzzy Synonym Matching (Date, Narration, Ref, Dr, Cr)");
        chip3.setStyle("-fx-font-size: 11px; -fx-text-fill: #a78bfa;");

        box.getChildren().addAll(title, chip1, chip2, chip3);
        return box;
    }

    private VBox buildValidationTableSection() {
        VBox box = new VBox(8);
        VBox.setVgrow(box, Priority.ALWAYS);

        HBox top = new HBox(12);
        top.setAlignment(Pos.CENTER_LEFT);

        Label lbl = new Label("4. PREFLIGHT COLOR-CODED VALIDATION TABLE");
        lbl.setStyle("-fx-font-weight: 700; -fx-font-size: 12px; -fx-text-fill: #e2e8f0;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        lblTotalRows.setStyle("-fx-background-color: rgba(255,255,255,0.08); -fx-padding: 3 8 3 8; -fx-background-radius: 4; -fx-font-weight: 600; -fx-text-fill: #f8fafc;");
        lblValidRows.setStyle("-fx-background-color: rgba(52, 211, 153, 0.2); -fx-padding: 3 8 3 8; -fx-background-radius: 4; -fx-font-weight: 600; -fx-text-fill: #34d399;");
        lblErrorRows.setStyle("-fx-background-color: rgba(248, 113, 113, 0.2); -fx-padding: 3 8 3 8; -fx-background-radius: 4; -fx-font-weight: 600; -fx-text-fill: #f87171;");

        top.getChildren().addAll(lbl, spacer, lblTotalRows, lblValidRows, lblErrorRows);

        configureTable();
        VBox.setVgrow(table, Priority.ALWAYS);

        box.getChildren().addAll(top, table);
        return box;
    }

    private void configureTable() {
        PopupTableWorkspace.prepareTable(table, "erp-table-profile-responsive");
        table.setItems(auditRows);
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);

        // 1. Line / Source Index
        TableColumn<KotakBankStatementCsvParser.AuditRow, Number> colIdx = new TableColumn<>("#");
        colIdx.setCellValueFactory(v -> new SimpleIntegerProperty(v.getValue().sourceIndex()));
        colIdx.setMinWidth(45);
        colIdx.setPrefWidth(55);

        // 2. Status Badge
        TableColumn<KotakBankStatementCsvParser.AuditRow, String> colStatus = new TableColumn<>("Status");
        colStatus.setCellValueFactory(v -> new SimpleStringProperty(v.getValue().status()));
        colStatus.setMinWidth(95);
        colStatus.setPrefWidth(110);
        colStatus.setCellFactory(tc -> new TableCell<>() {
            @Override
            protected void updateItem(String val, boolean empty) {
                super.updateItem(val, empty);
                if (empty || val == null) {
                    setGraphic(null);
                    setText(null);
                } else {
                    boolean ok = "PASSED".equalsIgnoreCase(val);
                    Label badge = new Label(ok ? "🟢 VALID" : "🔴 ERROR");
                    badge.setStyle(ok
                        ? "-fx-background-color: rgba(52, 211, 153, 0.18); -fx-text-fill: #34d399; -fx-padding: 2 6 2 6; -fx-background-radius: 4; -fx-font-weight: bold; -fx-font-size: 11px;"
                        : "-fx-background-color: rgba(248, 113, 113, 0.22); -fx-text-fill: #f87171; -fx-padding: 2 6 2 6; -fx-background-radius: 4; -fx-font-weight: bold; -fx-font-size: 11px;");
                    setGraphic(badge);
                    setText(null);
                }
            }
        });

        // 3. Date
        TableColumn<KotakBankStatementCsvParser.AuditRow, String> colDate = new TableColumn<>("Date");
        colDate.setCellValueFactory(v -> new SimpleStringProperty(v.getValue().date()));
        colDate.setMinWidth(110);
        colDate.setPrefWidth(125);

        // 4. Reference
        TableColumn<KotakBankStatementCsvParser.AuditRow, String> colRef = new TableColumn<>("Reference / UTR");
        colRef.setCellValueFactory(v -> new SimpleStringProperty(v.getValue().reference()));
        colRef.setMinWidth(140);
        colRef.setPrefWidth(170);

        // 5. Description / Narration
        TableColumn<KotakBankStatementCsvParser.AuditRow, String> colDesc = new TableColumn<>("Narration / Description");
        colDesc.setCellValueFactory(v -> new SimpleStringProperty(v.getValue().description()));
        colDesc.setMinWidth(220);
        colDesc.setPrefWidth(320);

        // 6. Debit
        TableColumn<KotakBankStatementCsvParser.AuditRow, Number> colDr = new TableColumn<>("Debit (Dr)");
        colDr.setCellValueFactory(v -> new SimpleDoubleProperty(v.getValue().debit()));
        colDr.setCellFactory(x -> currencyCell(false));
        colDr.setMinWidth(115);
        colDr.setPrefWidth(130);

        // 7. Credit
        TableColumn<KotakBankStatementCsvParser.AuditRow, Number> colCr = new TableColumn<>("Credit (Cr)");
        colCr.setCellValueFactory(v -> new SimpleDoubleProperty(v.getValue().credit()));
        colCr.setCellFactory(x -> currencyCell(true));
        colCr.setMinWidth(115);
        colCr.setPrefWidth(130);

        // 8. Balance
        TableColumn<KotakBankStatementCsvParser.AuditRow, Number> colBal = new TableColumn<>("Balance");
        colBal.setCellValueFactory(v -> new SimpleDoubleProperty(v.getValue().balance()));
        colBal.setCellFactory(x -> currencyCell(false));
        colBal.setMinWidth(115);
        colBal.setPrefWidth(130);

        // 9. Error Message / Validation Detail
        TableColumn<KotakBankStatementCsvParser.AuditRow, String> colError = new TableColumn<>("Validation Message");
        colError.setCellValueFactory(v -> new SimpleStringProperty(v.getValue().error() != null ? v.getValue().error() : "Passed all statutory checks"));
        colError.setMinWidth(200);
        colError.setPrefWidth(260);
        colError.setCellFactory(tc -> new TableCell<>() {
            @Override
            protected void updateItem(String val, boolean empty) {
                super.updateItem(val, empty);
                if (empty || val == null) {
                    setText(null);
                } else {
                    setText(val);
                    if (val.contains("Passed")) {
                        setStyle("-fx-text-fill: #94a3b8;");
                    } else {
                        setStyle("-fx-text-fill: #f87171; -fx-font-weight: 700;");
                    }
                }
            }
        });

        table.getColumns().setAll(colIdx, colStatus, colDate, colRef, colDesc, colDr, colCr, colBal, colError);
    }

    private TableCell<KotakBankStatementCsvParser.AuditRow, Number> currencyCell(boolean isCredit) {
        return new TableCell<>() {
            @Override
            protected void updateItem(Number val, boolean empty) {
                super.updateItem(val, empty);
                if (empty || val == null || val.doubleValue() <= 0) {
                    setText("-");
                    setStyle("-fx-alignment: CENTER-RIGHT; -fx-text-fill: #64748b;");
                } else {
                    setText(String.format(Locale.ENGLISH, "₹ %,.2f", val.doubleValue()));
                    setStyle("-fx-alignment: CENTER-RIGHT; -fx-font-weight: 600; -fx-text-fill: " + (isCredit ? "#34d399;" : "#f87171;"));
                }
            }
        };
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

    private void onDownloadTemplate() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save Bank Statement Import Template");
        chooser.setInitialFileName("Bank_Statement_Template.xlsx");
        chooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("Excel Workbook (*.xlsx)", "*.xlsx"),
            new FileChooser.ExtensionFilter("CSV (Comma Delimited) (*.csv)", "*.csv")
        );
        File target = chooser.showSaveDialog(getDialogPane().getScene().getWindow());
        if (target == null) return;

        try {
            new ImportTemplateService().write(target.toPath(), "Bank Statement", org.example.update.BuildInfo.version());
            ToastManager.success(table, "Template Downloaded", "Bank statement template saved to: " + target.getName());
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
        lblSelectedFile.setText(file.getName() + " (" + (file.length() / 1024) + " KB)");

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
                ToastManager.error(table, "Preflight Errors Detected", "File contains " + errors + " error rows. Please correct highlighted rows in red.");
            } else {
                ToastManager.success(table, "Validation Passed", "All " + valid + " rows passed verification and are ready for data shipping.");
            }
        } catch (Exception ex) {
            auditRows.clear();
            lblTotalRows.setText("Total: 0");
            lblValidRows.setText("Valid: 0");
            lblErrorRows.setText("Errors: 1");
            btnStartShipping.setDisable(true);
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

        try {
            var result = api.importStatement(request);
            if (result.alreadyImported()) {
                AppDialogService.info(table, "Already Imported", "Duplicate Statement", "This exact bank statement was imported previously. Statement batch reference: " + (result.batch() != null ? result.batch().statementReference() : ""));
            } else {
                ToastManager.success(table, "Data Shipping Complete", "Imported " + result.importedRows() + " transactions. Statement Ref: " + (result.batch() != null ? result.batch().statementReference() : ""));
            }
            if (onSuccessCallback != null) onSuccessCallback.run();
            close();
        } catch (Exception ex) {
            AppDialogService.error(table, "Data Shipping Failed", "Server Import Rejected", "Server import rejected: " + ex.getMessage());
        }
    }
}
