package org.example.controller;

import org.example.util.AppDialogService;

import java.math.BigDecimal;
import org.example.api.accounting.GeneralLedgerApiClient;
import org.example.config.ConfigManager;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.example.navigation.NavigationManager;
import org.example.navigation.ScreenLifecycle;
import org.example.service.PermissionService;
import org.example.util.*;

import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.*;
import java.io.File;
import java.io.FileOutputStream;
import javafx.stage.FileChooser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.example.service.BrandingService;

public class GeneralLedgerController implements ScreenLifecycle {

    @FXML private StackPane pageIcon;
    @FXML private StackPane kpiAssetsIcon, kpiLiabilitiesIcon, kpiRevenueIcon, kpiExpenseIcon, kpiProfitIcon;
    @FXML private Label kpiTotalAssets, kpiTotalLiabilities, kpiTotalRevenue, kpiTotalExpense, kpiNetProfit;

    @FXML private Label lblPeriodLockBadge, lblActivePeriodInfo;
    @FXML private Button btnHealthCheck, btnCloseYear, btnPeriodLock, btnNewJournal, btnRefreshLedger;
    @FXML private MenuButton btnExport;
    @FXML private MenuItem miExportExcel, miExportCsv, miExportPdf;
    @FXML private ComboBox<String> cmbPeriodPreset;
    @FXML private DatePicker dpFromDate, dpToDate;
    @FXML private Button btnApplyPeriod, btnClearPeriod;
    @FXML private TabPane glTabPane;

    // Tab 1: Journal Vouchers
    @FXML private TextField txtJournalSearch;
    @FXML private ComboBox<String> cmbVoucherFilter, cmbQuickBookFilter;
    @FXML private Button btnJournalRefresh;
    @FXML private SplitPane journalSplit;
    @FXML private TableView<JournalRow> tblJournals;
    @FXML private HBox journalPaginationHost;
    @FXML private TableColumn<JournalRow, String> colEntryNumber, colEntryDate, colEntryType, colReferenceNo, colNarration, colTotalDebit, colTotalCredit, colStatus, colActions;

    // Journal Detail Drawer
    @FXML private VBox journalDetailDrawer, vboxDrawerLines;
    @FXML private Label lblDrawerVoucherNo, lblDrawerDate, lblDrawerType, lblDrawerRef, lblDrawerStatus;
    @FXML private Label lblDrawerNarration, lblDrawerDrHead, lblDrawerDrAmount, lblDrawerCrHead, lblDrawerCrAmount;
    @FXML private Button btnPostDraft, btnReverseVoucher, btnCopyVoucher, btnOpenLinkedDoc, btnDrawerAudit, btnCloseJournalDrawer;

    // Tab 2: Chart of Accounts
    @FXML private TextField txtAccountSearch;
    @FXML private ComboBox<String> cmbAccountFilter;
    @FXML private CheckBox chkShowInactiveAccounts;
    @FXML private Button btnNewAccount, btnEditAccount, btnViewAccountLedger;
    @FXML private TableView<AccountRow> tblAccounts;
    @FXML private TableColumn<AccountRow, String> colAccountCode, colAccountName, colAccountType, colAccountSubtype, colOpeningBalance, colCurrentBalance, colAccountActive;

    // Tab 3: Trial Balance
    @FXML private TextField txtTbSearch;
    @FXML private Button btnTbViewLedger, btnExportTb;
    @FXML private TableView<TbRow> tblTrialBalance;
    @FXML private TableColumn<TbRow, String> colTbCode, colTbName, colTbType, colTbOpening, colTbDebit, colTbCredit, colTbClosing;
    @FXML private Label lblTbTotalOpening, lblTbTotalDebit, lblTbTotalCredit, lblTbTotalClosing, lblTbBalancePill;

    // Tab 4: Profit & Loss
    @FXML private Label lblPlRevenue, lblPlDirectCost, lblPlGrossProfit, lblPlIndirectCost, lblPlNetProfit;
    @FXML private TextField txtPlSearch;
    @FXML private Button btnPlViewLedger;
    @FXML private TableView<PlRow> tblProfitLoss;
    @FXML private TableColumn<PlRow, String> colPlSection, colPlCode, colPlName, colPlSubtype, colPlAmount;

    // Tab 5: Balance Sheet
    @FXML private TableView<BsRow> tblBsAssets;
    @FXML private TableColumn<BsRow, String> colBsAssetCode, colBsAssetName, colBsAssetSubtype, colBsAssetBalance;
    @FXML private TableView<BsRow> tblBsLiabEquity;
    @FXML private TableColumn<BsRow, String> colBsLeCode, colBsLeName, colBsLeType, colBsLeBalance;
    @FXML private Label lblBsTotalAssets, lblBsTotalLiab, lblBsTotalEquity, lblBsTallyPill;

    private final GeneralLedgerApiClient glClient = new GeneralLedgerApiClient();

    private final NumberFormat currency = NumberFormat.getCurrencyInstance(Locale.of("en", "IN"));

    private final ObservableList<JournalRow> allJournalRows = FXCollections.observableArrayList();
    private final ObservableList<JournalRow> filteredJournalRows = FXCollections.observableArrayList();
    private int journalCurrentPage = 0;
    private int journalPageSize = 50;
    private long journalTotalElements = 0L;
    private int journalTotalPages = 1;
    private boolean initializingFilters = true;
    private Label lblJournalPageSummary, lblJournalPageNum;
    private Button btnJournalFirst, btnJournalPrev, btnJournalNext, btnJournalLast;
    private ComboBox<Integer> cmbJournalPageSize;

    private final ObservableList<AccountRow> allAccountRows = FXCollections.observableArrayList();
    private final ObservableList<AccountRow> filteredAccountRows = FXCollections.observableArrayList();
    private List<GeneralLedgerApiClient.AccountDto> cachedAccountDtos = List.of();

    private final ObservableList<TbRow> allTbRows = FXCollections.observableArrayList();
    private final ObservableList<TbRow> filteredTbRows = FXCollections.observableArrayList();

    private final ObservableList<PlRow> allPlRows = FXCollections.observableArrayList();
    private final ObservableList<PlRow> filteredPlRows = FXCollections.observableArrayList();

    private final ObservableList<BsRow> bsAssetRows = FXCollections.observableArrayList();
    private final ObservableList<BsRow> bsLeRows = FXCollections.observableArrayList();

    private GeneralLedgerApiClient.ProfitAndLossResponse latestPl;
    private GeneralLedgerApiClient.BalanceSheetResponse latestBs;
    private GeneralLedgerApiClient.PeriodLockDto latestPeriodLock;
    private JournalRow selectedJournal;

    @FXML
    public void initialize() {
        configureIcons();
        configurePeriodBar();
        configureFilterCombos();
        setupTableColumns();
        installDynamicLayouts();
        configureTableInteractions();

        if (txtJournalSearch != null) {
            txtJournalSearch.textProperty().addListener((obs, oldVal, newVal) -> filterJournals());
        }
        if (txtAccountSearch != null) {
            txtAccountSearch.textProperty().addListener((obs, oldVal, newVal) -> filterAccounts());
        }
        if (txtTbSearch != null) {
            txtTbSearch.textProperty().addListener((obs, oldVal, newVal) -> filterTrialBalance());
        }
        if (txtPlSearch != null) {
            txtPlSearch.textProperty().addListener((obs, oldVal, newVal) -> filterProfitLoss());
        }

        if (btnPeriodLock != null) {
            boolean canLock = PermissionService.allowed("GENERAL_LEDGER.LOCK_PERIOD");
            btnPeriodLock.setDisable(!canLock);
            btnPeriodLock.setVisible(canLock);
            btnPeriodLock.setManaged(canLock);
        }
        if (btnCloseYear != null) {
            boolean canCloseYear = PermissionService.allowed("GENERAL_LEDGER.CLOSE_YEAR");
            btnCloseYear.setDisable(!canCloseYear);
            btnCloseYear.setVisible(canCloseYear);
            btnCloseYear.setManaged(canCloseYear);
        }
        if (btnNewJournal != null) {
            boolean canCreate = PermissionService.allowed("GENERAL_LEDGER.CREATE_JOURNAL");
            btnNewJournal.setDisable(!canCreate);
            btnNewJournal.setVisible(canCreate);
            btnNewJournal.setManaged(canCreate);
        }
        if (btnNewAccount != null) {
            boolean canCreateAcc = PermissionService.allowed("GENERAL_LEDGER.CREATE_ACCOUNT");
            btnNewAccount.setDisable(!canCreateAcc);
            btnNewAccount.setVisible(canCreateAcc);
            btnNewAccount.setManaged(canCreateAcc);
        }
        if (btnEditAccount != null) {
            boolean canEditAcc = PermissionService.allowed("GENERAL_LEDGER.EDIT_ACCOUNT");
            btnEditAccount.setDisable(!canEditAcc);
            btnEditAccount.setVisible(canEditAcc);
            btnEditAccount.setManaged(canEditAcc);
        }
        boolean canExport = PermissionService.allowed("GENERAL_LEDGER.EXPORT");
        if (btnExport != null) { btnExport.setDisable(!canExport); btnExport.setVisible(canExport); btnExport.setManaged(canExport); }
        if (btnExportTb != null) { btnExportTb.setDisable(!canExport); btnExportTb.setVisible(canExport); btnExportTb.setManaged(canExport); }

        RegisterUiSupport.hideDrawer(journalDetailDrawer, journalSplit, tblJournals);
        javafx.application.Platform.runLater(() -> RegisterUiSupport.hideDrawer(journalDetailDrawer, journalSplit, tblJournals));
        OperationalUiSupport.installEscapeClose(journalSplit, () -> journalDetailDrawer != null && journalDetailDrawer.isVisible(), this::closeJournalDetails);
        initializingFilters = false;
        loadLedgerDataAsync();
        javafx.application.Platform.runLater(this::consumeLinkedLedgerRequest);
    }

    @Override
    public void onScreenShown(boolean reusedFromCache) {
        if (reusedFromCache) {
            loadLedgerDataAsync();
        }
        consumeLinkedLedgerRequest();
    }

    private void consumeLinkedLedgerRequest() {
        LinkedRecordContext.Target accTarget = LinkedRecordContext.consume("ACCOUNT_LEDGER");
        if (accTarget != null && !accTarget.documentNo().isBlank()) {
            openAccountLedger(accTarget.documentNo(), accTarget.documentNo());
            return;
        }
        LinkedRecordContext.Target partyTarget = LinkedRecordContext.consume("PARTY_LEDGER");
        if (partyTarget != null && partyTarget.recordId() != null && partyTarget.recordId() > 0) {
            AccountLedgerDialog.showPartyLedger(tblJournals, glClient, partyTarget.recordId(),
                    partyTarget.documentNo(), dpFromDate != null ? dpFromDate.getValue() : null,
                    dpToDate != null ? dpToDate.getValue() : null);
        }
    }

    private void configurePeriodBar() {
        if (dpFromDate != null) DatePickerFormatter.attach(dpFromDate);
        if (dpToDate != null) DatePickerFormatter.attach(dpToDate);
        if (cmbPeriodPreset != null) {
            cmbPeriodPreset.setItems(FXCollections.observableArrayList(
                    "All Time", "Today", "This Month", "This Quarter", "This FY", "Last FY", "Custom Range"));
            cmbPeriodPreset.setValue("All Time");
            cmbPeriodPreset.valueProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal == null || "Custom Range".equals(newVal)) return;
                LocalDate today = BusinessClock.today();
                switch (newVal) {
                    case "All Time" -> { if (dpFromDate != null) dpFromDate.setValue(null); if (dpToDate != null) dpToDate.setValue(null); }
                    case "Today" -> { if (dpFromDate != null) dpFromDate.setValue(today); if (dpToDate != null) dpToDate.setValue(today); }
                    case "This Month" -> { if (dpFromDate != null) dpFromDate.setValue(today.withDayOfMonth(1)); if (dpToDate != null) dpToDate.setValue(today); }
                    case "This Quarter" -> {
                        int qStartMonth = ((today.getMonthValue() - 1) / 3) * 3 + 1;
                        if (dpFromDate != null) dpFromDate.setValue(LocalDate.of(today.getYear(), qStartMonth, 1));
                        if (dpToDate != null) dpToDate.setValue(today);
                    }
                    case "This FY" -> {
                        int fyYear = today.getMonthValue() >= 4 ? today.getYear() : today.getYear() - 1;
                        if (dpFromDate != null) dpFromDate.setValue(LocalDate.of(fyYear, 4, 1));
                        if (dpToDate != null) dpToDate.setValue(LocalDate.of(fyYear + 1, 3, 31));
                    }
                    case "Last FY" -> {
                        int fyYear = (today.getMonthValue() >= 4 ? today.getYear() : today.getYear() - 1) - 1;
                        if (dpFromDate != null) dpFromDate.setValue(LocalDate.of(fyYear, 4, 1));
                        if (dpToDate != null) dpToDate.setValue(LocalDate.of(fyYear + 1, 3, 31));
                    }
                }
                loadLedgerDataAsync();
            });
        }
    }

    private void configureIcons() {
        if (pageIcon != null) pageIcon.getChildren().setAll(IconFactory.icon("ledger", 24));
        if (kpiAssetsIcon != null) kpiAssetsIcon.getChildren().setAll(IconFactory.compactIcon("wallet", 20));
        if (kpiLiabilitiesIcon != null) kpiLiabilitiesIcon.getChildren().setAll(IconFactory.compactIcon("warning", 20));
        if (kpiRevenueIcon != null) kpiRevenueIcon.getChildren().setAll(IconFactory.compactIcon("sales", 20));
        if (kpiExpenseIcon != null) kpiExpenseIcon.getChildren().setAll(IconFactory.compactIcon("purchase", 20));
        if (kpiProfitIcon != null) kpiProfitIcon.getChildren().setAll(IconFactory.compactIcon("currency", 20));

        if (btnPeriodLock != null) UiActionIcons.apply(btnPeriodLock, "lock", "Period Lock");
        if (btnHealthCheck != null) UiActionIcons.apply(btnHealthCheck, "reconciliation", "GL Health Check");
        if (btnCloseYear != null) UiActionIcons.apply(btnCloseYear, "calendar", "Close Fiscal Year");
        UiActionIcons.apply(btnNewJournal, ButtonAction.ADD);
        UiActionIcons.apply(btnExport, ButtonAction.EXPORT);
        if (miExportExcel != null) miExportExcel.setGraphic(IconFactory.compactIcon("excel", 14));
        if (miExportCsv   != null) miExportCsv.setGraphic(IconFactory.compactIcon("csv", 14));
        if (miExportPdf   != null) miExportPdf.setGraphic(IconFactory.compactIcon("pdf", 14));
        UiActionIcons.apply(btnRefreshLedger, ButtonAction.REFRESH);
        if (btnApplyPeriod != null) UiActionIcons.apply(btnApplyPeriod, "filter", "Apply Period");
        if (btnClearPeriod != null) UiActionIcons.apply(btnClearPeriod, ButtonAction.REFRESH);
        UiActionIcons.apply(btnJournalRefresh, ButtonAction.REFRESH);
        if (btnNewAccount != null) UiActionIcons.apply(btnNewAccount, ButtonAction.ADD);
        if (btnEditAccount != null) UiActionIcons.apply(btnEditAccount, "edit", "Edit Account");
        if (btnViewAccountLedger != null) UiActionIcons.apply(btnViewAccountLedger, "ledger", "View Ledger");
        if (btnTbViewLedger != null) UiActionIcons.apply(btnTbViewLedger, "ledger", "View Ledger");
        if (btnPlViewLedger != null) UiActionIcons.apply(btnPlViewLedger, "ledger", "View Ledger");
        UiActionIcons.apply(btnExportTb, ButtonAction.EXPORT);
        UiActionIcons.apply(btnDrawerAudit, "history", "Audit Trail");
        UiActionIcons.apply(btnCloseJournalDrawer, ButtonAction.CLOSE);
        if (btnPostDraft != null) UiActionIcons.apply(btnPostDraft, "complete", "Post Draft");
        if (btnReverseVoucher != null) UiActionIcons.apply(btnReverseVoucher, "rollback", "Reverse");
        UiActionIcons.apply(btnCopyVoucher, "copy", "Copy No");
        UiActionIcons.apply(btnOpenLinkedDoc, "view", "Open Document");
    }

    private void configureFilterCombos() {
        if (cmbVoucherFilter != null) {
            cmbVoucherFilter.setItems(FXCollections.observableArrayList(
                    "All Types", "SALES", "PURCHASE", "RECEIPT", "PAYMENT", "GENERAL", "EXPENSE", "CONTRA", "ADJUSTMENT", "REVERSAL", "CREDIT_NOTE", "DEBIT_NOTE"));
            cmbVoucherFilter.setValue("All Types");
            cmbVoucherFilter.valueProperty().addListener((obs, oldVal, newVal) -> filterJournals());
        }
        if (cmbQuickBookFilter != null) {
            cmbQuickBookFilter.setItems(FXCollections.observableArrayList(
                    "All Vouchers", "Day Book (Today)", "Cash & Bank Book", "GST Ledgers", "Drafts Only", "Manual & Reversals"));
            cmbQuickBookFilter.setValue("All Vouchers");
            cmbQuickBookFilter.valueProperty().addListener((obs, oldVal, newVal) -> filterJournals());
        }
        if (cmbAccountFilter != null) {
            cmbAccountFilter.setItems(FXCollections.observableArrayList("All Categories", "ASSET", "LIABILITY", "EQUITY", "REVENUE", "EXPENSE"));
            cmbAccountFilter.setValue("All Categories");
            cmbAccountFilter.valueProperty().addListener((obs, oldVal, newVal) -> filterAccounts());
        }
    }

    private void installDynamicLayouts() {
        if (tblJournals != null) DynamicTableLayoutManager.install(tblJournals);
        if (tblAccounts != null) DynamicTableLayoutManager.install(tblAccounts);
        if (tblTrialBalance != null) DynamicTableLayoutManager.install(tblTrialBalance);
        if (tblProfitLoss != null) DynamicTableLayoutManager.install(tblProfitLoss);
        if (tblBsAssets != null) DynamicTableLayoutManager.install(tblBsAssets);
        if (tblBsLiabEquity != null) DynamicTableLayoutManager.install(tblBsLiabEquity);
    }

    private void setupTableColumns() {
        if (tblJournals != null) {
            colEntryNumber.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().entryNumber()));
            colEntryDate.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().entryDate()));
            colEntryType.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().entryType()));
            colReferenceNo.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().referenceNo()));
            colNarration.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().narration()));
            colTotalDebit.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().totalDebit()));
            colTotalCredit.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().totalCredit()));
            colStatus.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().status()));

            colActions.setCellFactory(col -> new TableCell<JournalRow, String>() {
                private final MenuButton actionsBtn = new MenuButton("Actions");
                {
                    actionsBtn.getStyleClass().addAll("table-action-menu", "approved-row-action");
                    actionsBtn.setGraphic(IconFactory.compactIcon("actions", 14));
                    actionsBtn.setContentDisplay(ContentDisplay.LEFT);
                    actionsBtn.setGraphicTextGap(6);

                    MenuItem viewItem = new MenuItem("View Details");
                    viewItem.setGraphic(IconFactory.compactIcon("view", 13));
                    viewItem.setOnAction(e -> {
                        JournalRow row = getTableView().getItems().get(getIndex());
                        if (row != null) {
                            tblJournals.getSelectionModel().select(row);
                            showJournalDetails(row);
                        }
                    });

                    MenuItem flowItem = new MenuItem("View Posting Flow (Ctrl+J)");
                    flowItem.setGraphic(IconFactory.compactIcon("status", 13));
                    flowItem.setOnAction(e -> {
                        JournalRow row = getTableView().getItems().get(getIndex());
                        if (row != null) openPostingFlow(row);
                    });

                    MenuItem auditItem = new MenuItem("Audit Trail");
                    auditItem.setGraphic(IconFactory.compactIcon("history", 13));
                    auditItem.setOnAction(e -> {
                        JournalRow row = getTableView().getItems().get(getIndex());
                        if (row != null) {
                            showJournalAudit(row);
                        }
                    });

                    MenuItem openItem = new MenuItem("Open Document");
                    openItem.setGraphic(IconFactory.compactIcon("open", 13));
                    openItem.setOnAction(e -> {
                        JournalRow row = getTableView().getItems().get(getIndex());
                        if (row != null) openDocument(row);
                    });

                    MenuItem reverseItem = new MenuItem("Reverse Voucher");
                    reverseItem.setGraphic(IconFactory.compactIcon("rollback", 13));
                    reverseItem.setOnAction(e -> {
                        JournalRow row = getTableView().getItems().get(getIndex());
                        if (row != null) reverseJournal(row);
                    });

                    MenuItem copyItem = new MenuItem("Copy Voucher No");
                    copyItem.setGraphic(IconFactory.compactIcon("copy", 13));
                    copyItem.setOnAction(e -> {
                        JournalRow row = getTableView().getItems().get(getIndex());
                        if (row != null) copyVoucher(row);
                    });

                    actionsBtn.getItems().addAll(viewItem, flowItem, reverseItem, auditItem, openItem, copyItem);
                    IconFactory.decorateActionMenu(actionsBtn);
                }

                @Override
                protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || getIndex() >= getTableView().getItems().size()) {
                        setGraphic(null);
                        setText(null);
                    } else {
                        setGraphic(actionsBtn);
                        setText(null);
                        setAlignment(Pos.CENTER);
                    }
                }
            });

            if (journalPaginationHost != null) {
                buildServerPaginationBar();
            } else {
                tblJournals.setItems(filteredJournalRows);
            }
        }

        if (tblAccounts != null) {
            colAccountCode.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().code()));
            colAccountName.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().name()));
            colAccountType.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().type()));
            colAccountSubtype.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().subtype()));
            colOpeningBalance.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().opening()));
            colCurrentBalance.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().current()));
            colAccountActive.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().active()));
            tblAccounts.setItems(filteredAccountRows);
        }

        if (tblTrialBalance != null) {
            colTbCode.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().code()));
            colTbName.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().name()));
            colTbType.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().type()));
            colTbOpening.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().opening()));
            colTbDebit.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().debit()));
            colTbCredit.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().credit()));
            colTbClosing.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().closing()));
            tblTrialBalance.setItems(filteredTbRows);
        }

        if (tblProfitLoss != null) {
            colPlSection.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().section()));
            colPlCode.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().code()));
            colPlName.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().name()));
            colPlSubtype.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().subtype()));
            colPlAmount.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().amount()));
            tblProfitLoss.setItems(filteredPlRows);
        }

        if (tblBsAssets != null) {
            colBsAssetCode.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().code()));
            colBsAssetName.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().name()));
            colBsAssetSubtype.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().subtype()));
            colBsAssetBalance.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().balance()));
            tblBsAssets.setItems(bsAssetRows);
        }

        if (tblBsLiabEquity != null) {
            colBsLeCode.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().code()));
            colBsLeName.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().name()));
            colBsLeType.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().subtype()));
            colBsLeBalance.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().balance()));
            tblBsLiabEquity.setItems(bsLeRows);
        }
    }

    private void configureTableInteractions() {
        if (tblJournals != null) {
            tblJournals.setOnKeyPressed(event -> {
                if (new javafx.scene.input.KeyCodeCombination(javafx.scene.input.KeyCode.J, javafx.scene.input.KeyCombination.CONTROL_DOWN).match(event)) {
                    JournalRow sel = tblJournals.getSelectionModel().getSelectedItem();
                    if (sel != null) {
                        openPostingFlow(sel);
                        event.consume();
                    }
                }
            });
            tblJournals.setRowFactory(tv -> {
                TableRow<JournalRow> row = new TableRow<>();
                row.setOnMouseClicked(event -> {
                    if (event.getButton() != MouseButton.PRIMARY || row.isEmpty()
                            || RegisterUiSupport.isInteractiveTableTarget(event.getPickResult().getIntersectedNode(), row)) {
                        return;
                    }
                    JournalRow clicked = row.getItem();
                    tblJournals.getSelectionModel().select(clicked);
                    if (event.getClickCount() == 1) {
                        showJournalDetails(clicked);
                    }
                    event.consume();
                });
                return row;
            });
        }
        if (tblAccounts != null) {
            tblAccounts.setRowFactory(tv -> {
                TableRow<AccountRow> row = new TableRow<>();
                ContextMenu cm = new ContextMenu();
                MenuItem viewLedger = new MenuItem("View Account Ledger");
                viewLedger.setGraphic(IconFactory.compactIcon("ledger", 13));
                viewLedger.setOnAction(e -> {
                    if (!row.isEmpty()) openAccountLedger(row.getItem().code(), row.getItem().name());
                });
                MenuItem editAcc = new MenuItem("Edit Account");
                editAcc.setGraphic(IconFactory.compactIcon("edit", 13));
                editAcc.setOnAction(e -> {
                    if (!row.isEmpty()) {
                        tblAccounts.getSelectionModel().select(row.getItem());
                        editSelectedAccount();
                    }
                });
                cm.getItems().addAll(viewLedger, editAcc);
                row.contextMenuProperty().bind(javafx.beans.binding.Bindings.when(row.emptyProperty()).then((ContextMenu) null).otherwise(cm));
                return row;
            });
        }
        if (tblTrialBalance != null) {
            tblTrialBalance.setRowFactory(tv -> {
                TableRow<TbRow> row = new TableRow<>();
                ContextMenu cm = new ContextMenu();
                MenuItem viewLedger = new MenuItem("View Account Ledger");
                viewLedger.setGraphic(IconFactory.compactIcon("ledger", 13));
                viewLedger.setOnAction(e -> {
                    if (!row.isEmpty()) openAccountLedger(row.getItem().code(), row.getItem().name());
                });
                cm.getItems().add(viewLedger);
                row.contextMenuProperty().bind(javafx.beans.binding.Bindings.when(row.emptyProperty()).then((ContextMenu) null).otherwise(cm));
                return row;
            });
        }
        if (tblProfitLoss != null) {
            tblProfitLoss.setRowFactory(tv -> {
                TableRow<PlRow> row = new TableRow<>();
                ContextMenu cm = new ContextMenu();
                MenuItem viewLedger = new MenuItem("View Account Ledger");
                viewLedger.setGraphic(IconFactory.compactIcon("ledger", 13));
                viewLedger.setOnAction(e -> {
                    if (!row.isEmpty()) openAccountLedger(row.getItem().code(), row.getItem().name());
                });
                cm.getItems().add(viewLedger);
                row.contextMenuProperty().bind(javafx.beans.binding.Bindings.when(row.emptyProperty()).then((ContextMenu) null).otherwise(cm));
                return row;
            });
        }
        if (tblBsAssets != null) {
            tblBsAssets.setRowFactory(tv -> {
                TableRow<BsRow> row = new TableRow<>();
                ContextMenu cm = new ContextMenu();
                MenuItem viewLedger = new MenuItem("View Account Ledger");
                viewLedger.setGraphic(IconFactory.compactIcon("ledger", 13));
                viewLedger.setOnAction(e -> {
                    if (!row.isEmpty() && row.getItem().code() != null && !row.getItem().code().isBlank() && !"-".equals(row.getItem().code())) {
                        openAccountLedger(row.getItem().code(), row.getItem().name());
                    }
                });
                cm.getItems().add(viewLedger);
                row.contextMenuProperty().bind(javafx.beans.binding.Bindings.when(row.emptyProperty()).then((ContextMenu) null).otherwise(cm));
                return row;
            });
        }
        if (tblBsLiabEquity != null) {
            tblBsLiabEquity.setRowFactory(tv -> {
                TableRow<BsRow> row = new TableRow<>();
                ContextMenu cm = new ContextMenu();
                MenuItem viewLedger = new MenuItem("View Account Ledger");
                viewLedger.setGraphic(IconFactory.compactIcon("ledger", 13));
                viewLedger.setOnAction(e -> {
                    if (!row.isEmpty() && row.getItem().code() != null && !row.getItem().code().isBlank() && !"-".equals(row.getItem().code())) {
                        openAccountLedger(row.getItem().code(), row.getItem().name());
                    }
                });
                cm.getItems().add(viewLedger);
                row.contextMenuProperty().bind(javafx.beans.binding.Bindings.when(row.emptyProperty()).then((ContextMenu) null).otherwise(cm));
                return row;
            });
        }
    }

    private void openAccountLedger(String code, String name) {
        if (code == null || code.isBlank()) return;
        AccountLedgerDialog.show(tblJournals, glClient, code, name,
                dpFromDate != null ? dpFromDate.getValue() : null,
                dpToDate != null ? dpToDate.getValue() : null);
    }

    private void openPostingFlow(JournalRow row) {
        if (row == null) return;
        String docNo = row.referenceNo() != null && !row.referenceNo().isBlank() ? row.referenceNo() : row.entryNumber();
        PostingFlowDialog.show(tblJournals, row.entryType(), docNo);
    }

    private void showJournalDetails(JournalRow row) {
        if (row == null) return;
        this.selectedJournal = row;

        if (lblDrawerVoucherNo != null) lblDrawerVoucherNo.setText(row.entryNumber());
        if (lblDrawerDate != null) lblDrawerDate.setText(row.entryDate());
        if (lblDrawerType != null) lblDrawerType.setText(row.entryType());
        if (lblDrawerRef != null) lblDrawerRef.setText(row.referenceNo() != null ? row.referenceNo() : "-");
        if (lblDrawerStatus != null) lblDrawerStatus.setText(row.status());
        if (lblDrawerNarration != null) lblDrawerNarration.setText(row.narration());
        if (lblDrawerDrHead != null) lblDrawerDrHead.setText("Dr. " + row.drHead() + ":");
        if (lblDrawerDrAmount != null) lblDrawerDrAmount.setText(row.drAmount());
        if (lblDrawerCrHead != null) lblDrawerCrHead.setText("Cr. " + row.crHead() + ":");
        if (lblDrawerCrAmount != null) lblDrawerCrAmount.setText(row.crAmount());
        if (btnPostDraft != null) {
            boolean isDraft = "DRAFT".equalsIgnoreCase(row.status());
            btnPostDraft.setVisible(isDraft);
            btnPostDraft.setManaged(isDraft);
        }
        if (btnReverseVoucher != null) {
            boolean canReverse = row.id() != null && "POSTED".equalsIgnoreCase(row.status());
            btnReverseVoucher.setDisable(!canReverse);
        }
        if (vboxDrawerLines != null) {
            vboxDrawerLines.getChildren().clear();
            if (row.lines() != null) {
                for (var l : row.lines()) {
                    boolean isDr = l.debitAmount() != null && l.debitAmount().compareTo(BigDecimal.ZERO) > 0;
                    Label lblAcc = new Label((isDr ? "Dr " : "Cr ") + (l.accountCode() != null ? l.accountCode() + " " : "") + (l.accountName() != null ? l.accountName() : ""));
                    lblAcc.getStyleClass().add("meta-label");
                    lblAcc.setWrapText(true);
                    Region sp = new Region();
                    HBox.setHgrow(sp, Priority.ALWAYS);
                    Label lblAmt = new Label(formatMoneyNullable(isDr ? l.debitAmount() : l.creditAmount()));
                    lblAmt.getStyleClass().addAll("meta-value-bold", isDr ? "text-primary" : "text-success");
                    HBox lineRow = new HBox(6, lblAcc, sp, lblAmt);
                    lineRow.setAlignment(Pos.CENTER_LEFT);
                    vboxDrawerLines.getChildren().add(lineRow);
                }
            }
        }

        // Centered floating modal inspection pop-up
        OwnedDialog<ButtonType> dialog = new OwnedDialog<>(tblJournals);
        dialog.setTitle("Journal Voucher Inspection — " + row.entryNumber());
        dialog.setHeaderText(null);
        dialog.setResizable(true);

        DialogPane pane = dialog.getDialogPane();
        pane.getStyleClass().addAll("erp-document-inspection-dialog", "approved-ui");
        PopupTableWorkspace.applyInspectionSizing(dialog, tblJournals);

        Label iconLabel = new Label();
        iconLabel.setGraphic(IconFactory.icon("finance", 32));
        iconLabel.getStyleClass().add("inspection-hero-icon");

        Label kicker = new Label("DOUBLE-ENTRY GENERAL LEDGER VOUCHER");
        kicker.getStyleClass().add("inspection-kicker");

        Label title = new Label(row.entryNumber() + "  •  " + (row.referenceNo() != null && !row.referenceNo().isBlank() ? "Ref: " + row.referenceNo() : "Journal Voucher"));
        title.getStyleClass().addAll("inspection-hero-title", "sales-detail-invoice");

        Label sub = new Label("Posting Date: " + row.entryDate() + "  •  Type: " + row.entryType());
        sub.getStyleClass().add("inspection-subtitle");

        VBox titleBox = new VBox(2, kicker, title, sub);
        HBox.setHgrow(titleBox, Priority.ALWAYS);

        Region spacerHero = new Region();
        HBox.setHgrow(spacerHero, Priority.ALWAYS);

        Label statusPill = new Label(row.status() != null ? row.status() : "POSTED");
        statusPill.getStyleClass().addAll("status-pill", "POSTED".equalsIgnoreCase(row.status()) ? "pill-success" : "pill-neutral");

        HBox hero = new HBox(14, iconLabel, titleBox, spacerHero, statusPill);
        hero.setAlignment(Pos.CENTER_LEFT);
        hero.setPadding(new Insets(4, 8, 8, 8));
        DocumentInspectionScaffold.installDragPhysics(hero, dialog);

        // Details Grid
        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(10);
        grid.setPadding(new Insets(14));
        grid.getStyleClass().addAll("inspection-detail-grid", "erp-detail-drawer-card");

        int r = 0;
        addJournalDetailRow(grid, r++, "Voucher Number", row.entryNumber(), "document");
        addJournalDetailRow(grid, r++, "Posting Date", row.entryDate(), "calendar");
        addJournalDetailRow(grid, r++, "Voucher Type", row.entryType(), "status");
        addJournalDetailRow(grid, r++, "Reference / Source Doc", row.referenceNo() != null && !row.referenceNo().isBlank() ? row.referenceNo() : "Direct Journal (None)", "open");
        addJournalDetailRow(grid, r++, "Voucher Status", row.status() != null ? row.status() : "POSTED", "complete");
        addJournalDetailRow(grid, r++, "Total Debit", row.totalDebit() != null ? row.totalDebit() : "-", "currency");
        addJournalDetailRow(grid, r++, "Total Credit", row.totalCredit() != null ? row.totalCredit() : "-", "currency");
        addJournalDetailRow(grid, r++, "Narration / Remarks", row.narration() != null && !row.narration().isBlank() ? row.narration() : "—", "document");

        // Multi-leg table showing ALL lines with zero concealment
        Label lblLegHeader = new Label("ALL GENERAL LEDGER POSTING LEGS & ACCOUNTS");
        lblLegHeader.setGraphic(IconFactory.compactIcon("ledger", 14));
        lblLegHeader.getStyleClass().addAll("inspection-kicker", "approved-lead");

        TableView<GeneralLedgerApiClient.JournalLineDto> legTable = new TableView<>();
        legTable.getStyleClass().addAll("approved-table", "erp-table-profile-dialog");
        legTable.setPrefHeight(180);
        legTable.setMinHeight(140);

        TableColumn<GeneralLedgerApiClient.JournalLineDto, String> colLineCode = new TableColumn<>("Account Code");
        colLineCode.setCellValueFactory(v -> new SimpleStringProperty(v.getValue().accountCode()));
        colLineCode.setCellFactory(c -> styledTableCell("erp-table-value-colour-purple", Pos.CENTER_LEFT));
        IconFactory.applyTableHeaderIcon(colLineCode, "identity");

        TableColumn<GeneralLedgerApiClient.JournalLineDto, String> colLineName = new TableColumn<>("Account Name");
        colLineName.setCellValueFactory(v -> new SimpleStringProperty(v.getValue().accountName()));
        IconFactory.applyTableHeaderIcon(colLineName, "ledger");

        TableColumn<GeneralLedgerApiClient.JournalLineDto, String> colLineDr = new TableColumn<>("Debit (Dr)");
        colLineDr.setCellValueFactory(v -> new SimpleStringProperty(formatMoneyNullable(v.getValue().debitAmount())));
        colLineDr.setCellFactory(c -> styledTableCell("erp-table-value-colour-green", Pos.CENTER_RIGHT));
        IconFactory.applyTableHeaderIcon(colLineDr, "currency");

        TableColumn<GeneralLedgerApiClient.JournalLineDto, String> colLineCr = new TableColumn<>("Credit (Cr)");
        colLineCr.setCellValueFactory(v -> new SimpleStringProperty(formatMoneyNullable(v.getValue().creditAmount())));
        colLineCr.setCellFactory(c -> styledTableCell("erp-table-value-colour-orange", Pos.CENTER_RIGHT));
        IconFactory.applyTableHeaderIcon(colLineCr, "currency");

        TableColumn<GeneralLedgerApiClient.JournalLineDto, String> colLineNarr = new TableColumn<>("Line Remarks / Party");
        colLineNarr.setCellValueFactory(v -> new SimpleStringProperty(v.getValue().lineNarration() != null ? v.getValue().lineNarration() : "—"));
        IconFactory.applyTableHeaderIcon(colLineNarr, "notes");

        legTable.getColumns().addAll(colLineCode, colLineName, colLineDr, colLineCr, colLineNarr);
        DynamicTableLayoutManager.install(legTable);
        if (row.lines() != null && !row.lines().isEmpty()) {
            legTable.setItems(FXCollections.observableArrayList(row.lines()));
        } else {
            legTable.setPlaceholder(new Label("Single-entry summary record."));
        }

        VBox detailBox = new VBox(10, grid, lblLegHeader, legTable);
        VBox.setVgrow(legTable, Priority.ALWAYS);

        ScrollPane scroll = new ScrollPane(detailBox);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        // Action Dock
        HBox dock = new HBox(8);
        dock.setAlignment(Pos.CENTER_LEFT);
        dock.setPadding(new Insets(10, 0, 0, 0));

        Button btnFlow = new Button("View Posting Flow (Ctrl+J)");
        btnFlow.setGraphic(IconFactory.compactIcon("status", 14));
        btnFlow.getStyleClass().addAll("approved-button", "approved-primary-button");
        btnFlow.setOnAction(e -> {
            dialog.close();
            openPostingFlow(row);
        });
        dock.getChildren().add(btnFlow);

        if (row.id() != null && "DRAFT".equalsIgnoreCase(row.status())) {
            Button btnPost = new Button("Post to Ledger");
            btnPost.setGraphic(IconFactory.compactIcon("check", 14));
            btnPost.getStyleClass().addAll("approved-button", "approved-primary-button");
            btnPost.setOnAction(e -> {
                dialog.close();
                postDraftJournal(row);
            });
            dock.getChildren().add(btnPost);
        }

        if (row.id() != null && "POSTED".equalsIgnoreCase(row.status())
                && PermissionService.allowed("GENERAL_LEDGER.REVERSE_JOURNAL")) {
            Button btnRevModal = new Button("Reverse Voucher");
            btnRevModal.setGraphic(IconFactory.compactIcon("rollback", 14));
            btnRevModal.getStyleClass().addAll("approved-button", "approved-danger-ghost-button");
            btnRevModal.setOnAction(e -> {
                dialog.close();
                reverseJournal(row);
            });
            dock.getChildren().add(btnRevModal);
        }

        Button btnOpenDoc = new Button("Open Linked Document");
        btnOpenDoc.setGraphic(IconFactory.compactIcon("open", 14));
        btnOpenDoc.getStyleClass().addAll("approved-button", "approved-secondary-button");
        boolean hasRef = row.referenceNo() != null && !row.referenceNo().isBlank();
        btnOpenDoc.setDisable(!hasRef);
        btnOpenDoc.setOnAction(e -> {
            dialog.close();
            openDocument(row);
        });

        Button btnAudit = new Button("Audit Trail");
        btnAudit.setGraphic(IconFactory.compactIcon("history", 14));
        btnAudit.getStyleClass().addAll("approved-button", "approved-secondary-button");
        btnAudit.setOnAction(e -> showJournalAudit(row));

        Button btnCopy = new Button("Copy Voucher #");
        btnCopy.setGraphic(IconFactory.compactIcon("copy", 14));
        btnCopy.getStyleClass().addAll("approved-button", "approved-secondary-button");
        btnCopy.setOnAction(e -> copyVoucher(row));

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);

        Button btnClose = new Button("Close");
        btnClose.setGraphic(IconFactory.compactIcon("cancel", 14));
        btnClose.getStyleClass().addAll("approved-button", "approved-secondary-button");
        btnClose.setOnAction(e -> dialog.close());

        dock.getChildren().addAll(btnOpenDoc, btnAudit, btnCopy, sp, btnClose);

        VBox contentBox = new VBox(12, hero, scroll, dock);
        contentBox.setPadding(new Insets(16, 20, 16, 20));
        pane.setContent(contentBox);
        pane.getButtonTypes().add(ButtonType.CLOSE);
        Node bb = pane.lookup(".button-bar");
        if (bb != null) { bb.setVisible(false); bb.setManaged(false); }

        dialog.showAndWait();
    }

    private static void addJournalDetailRow(GridPane grid, int row, String caption, String value, String semantic) {
        Label capLbl = new Label(caption);
        capLbl.setGraphic(IconFactory.compactIcon(semantic, 14));
        capLbl.setGraphicTextGap(6);
        capLbl.getStyleClass().addAll("inspection-card-caption", "erp-drawer-caption");
        capLbl.setMinWidth(180);

        Label valLbl = new Label(value != null ? value : "-");
        valLbl.getStyleClass().addAll("inspection-card-value", "erp-drawer-value");
        valLbl.setWrapText(true);

        grid.add(capLbl, 0, row);
        grid.add(valLbl, 1, row);
    }

    @FXML
    public void closeJournalDetails() {
        this.selectedJournal = null;
        RegisterUiSupport.hideDrawer(journalDetailDrawer, journalSplit, tblJournals);
    }

    @FXML
    public void auditSelected() {
        if (selectedJournal != null) {
            showJournalAudit(selectedJournal);
        }
    }

    private void showJournalAudit(JournalRow row) {
        if (row == null) return;
        String ref = row.referenceNo();
        if (ref == null || ref.isBlank()) ref = row.entryNumber();
        String type = row.entryType() != null ? row.entryType().toUpperCase(Locale.ROOT) : "JOURNAL";
        String entityType = switch (type) {
            case "SALES", "SALE", "RECEIPT" -> "SALE";
            case "PURCHASE", "PAYMENT" -> "PURCHASE";
            case "CREDIT_NOTE", "SALES_RETURN" -> "CREDIT_NOTE";
            case "DEBIT_NOTE", "PURCHASE_RETURN" -> "DEBIT_NOTE";
            default -> "JOURNAL";
        };
        int entityId = row.id() != null ? row.id().intValue() : 0;
        org.example.util.ActivityTimelineDialog.show(tblJournals, entityType, entityId, ref);
    }

    @FXML
    public void openLinkedDocument() {
        if (selectedJournal != null) openDocument(selectedJournal);
    }

    private void openDocument(JournalRow row) {
        if (row == null) return;
        String ref = row.referenceNo() != null && !row.referenceNo().isBlank() ? row.referenceNo() : row.entryNumber();
        if (ref == null || ref.isBlank()) return;
        String type = row.entryType() != null ? row.entryType().toUpperCase(Locale.ROOT) : "";
        switch (type) {
            case "SALES", "SALE", "RECEIPT" -> {
                LinkedRecordContext.open("SALE", null, ref, "VIEW", "General Ledger");
                NavigationManager.getInstance().loadPage("/fxml/pages/SalesList.fxml");
            }
            case "PURCHASE", "PAYMENT" -> {
                LinkedRecordContext.open("PURCHASE", null, ref, "VIEW", "General Ledger");
                NavigationManager.getInstance().loadPage("/fxml/pages/PurchaseList.fxml");
            }
            case "CREDIT_NOTE", "SALES_RETURN" -> {
                LinkedRecordContext.open("CREDIT_NOTE", null, ref, "VIEW", "General Ledger");
                NavigationManager.getInstance().loadPage("/fxml/pages/CreditNoteRegister.fxml");
            }
            case "DEBIT_NOTE", "PURCHASE_RETURN" -> {
                LinkedRecordContext.open("DEBIT_NOTE", null, ref, "VIEW", "General Ledger");
                NavigationManager.getInstance().loadPage("/fxml/pages/DebitNoteRegister.fxml");
            }
            case "EXPENSE", "FINANCE", "BANK_DEPOSIT", "BANK_WITHDRAWAL", "CONTRA" -> {
                BankExpenseController.requestMode(type.contains("EXPENSE") ? BankExpenseController.Mode.EXPENSE : BankExpenseController.Mode.BANK);
                LinkedRecordContext.open("FINANCE", null, ref, "VIEW", "General Ledger");
                NavigationManager.getInstance().loadPage("/fxml/pages/BankExpense.fxml");
            }
            default -> PostingFlowDialog.show(tblJournals, type.isBlank() ? "JOURNAL" : type, ref);
        }
    }

    @FXML
    public void copyVoucherNumber() {
        if (selectedJournal != null) copyVoucher(selectedJournal);
    }

    private void copyVoucher(JournalRow row) {
        if (row == null || row.entryNumber() == null) return;
        ClipboardContent cc = new ClipboardContent();
        cc.putString(row.entryNumber());
        Clipboard.getSystemClipboard().setContent(cc);
        AppDialogService.info(tblJournals, "Copied", "Voucher number copied to clipboard: " + row.entryNumber());
    }

    private void postDraftJournal(JournalRow row) {
        if (row == null || row.id() == null) return;
        UiTaskExecutor.submitLatest(
                "gl-post-draft-" + row.id(),
                () -> {
                    org.example.model.AppUser u = org.example.service.SessionService.current();
                    String username = u != null && u.getUsername() != null ? u.getUsername() : "admin";
                    glClient.postDraft(row.id(), username);
                    return true;
                },
                success -> {
                    AppDialogService.info(tblJournals, "Voucher Posted", "Journal voucher " + row.entryNumber() + " has been successfully posted to the General Ledger.");
                    loadLedgerDataAsync();
                },
                ex -> AppDialogService.error(tblJournals, "Posting Failed", "Failed to post draft journal: " + ex.getMessage())
        );
    }

    private void loadLedgerDataAsync() {
        final LocalDate fromDate = dpFromDate != null ? dpFromDate.getValue() : null;
        final LocalDate toDate = dpToDate != null ? dpToDate.getValue() : null;
        final boolean includeInactive = chkShowInactiveAccounts != null && chkShowInactiveAccounts.isSelected();
        final String searchQuery = txtJournalSearch != null && txtJournalSearch.getText() != null ? txtJournalSearch.getText().trim() : "";
        final String typeFilter = cmbVoucherFilter != null && cmbVoucherFilter.getValue() != null ? cmbVoucherFilter.getValue() : "All Types";
        final String bookFilter = cmbQuickBookFilter != null && cmbQuickBookFilter.getValue() != null ? cmbQuickBookFilter.getValue() : "All Vouchers";
        final JournalQuerySpec qSpec = resolveJournalQuerySpec(fromDate, toDate, typeFilter, bookFilter, searchQuery);

        if (lblActivePeriodInfo != null) {
            if (fromDate == null && toDate == null) {
                lblActivePeriodInfo.setText("Showing: All Time");
            } else {
                String f = fromDate != null ? BusinessClock.formatDate(fromDate) : "Beginning";
                String t = toDate != null ? BusinessClock.formatDate(toDate) : "Today";
                lblActivePeriodInfo.setText("Period: " + f + " → " + t);
            }
        }

        UiTaskExecutor.submitLatest(
                "general-ledger-load",
                () -> {
                    List<AccountRow> aRows = new ArrayList<>();
                    List<GeneralLedgerApiClient.AccountDto> rawAccounts = new ArrayList<>();
                    List<TbRow> tRows = new ArrayList<>();
                    List<JournalRow> jRows = new ArrayList<>();
                    GeneralLedgerApiClient.ProfitAndLossResponse plResp = null;
                    GeneralLedgerApiClient.BalanceSheetResponse bsResp = null;
                    GeneralLedgerApiClient.PeriodLockDto lockDto = null;
                    String assetsStr = "₹ 0.00";
                    String liabStr = "₹ 0.00";
                    String revStr = "₹ 0.00";
                    String expStr = "₹ 0.00";
                    String profitStr = "₹ 0.00";
                    long totalElements = 0L;
                    int totalPages = 1;

                    boolean glEnabled = ConfigManager.getBoolean("module.gl.enabled", true);
                    if (glEnabled) {
                        List<GeneralLedgerApiClient.AccountDto> accDtos = glClient.accounts(includeInactive);
                        if (accDtos != null) {
                            rawAccounts.addAll(accDtos);
                            for (GeneralLedgerApiClient.AccountDto a : accDtos) {
                                aRows.add(new AccountRow(
                                        a.accountCode(),
                                        a.accountName(),
                                        a.accountType(),
                                        a.accountSubtype() != null ? a.accountSubtype() : "",
                                        currency.format(a.openingBalance() != null ? a.openingBalance() : BigDecimal.ZERO),
                                        currency.format(a.currentBalance() != null ? a.currentBalance() : BigDecimal.ZERO),
                                        Boolean.TRUE.equals(a.isActive()) ? "Active" : "Inactive"
                                ));
                            }
                        }

                        List<GeneralLedgerApiClient.TrialBalanceItemDto> tbDtos = glClient.trialBalance(fromDate, toDate);
                        if (tbDtos != null) {
                            for (GeneralLedgerApiClient.TrialBalanceItemDto tb : tbDtos) {
                                tRows.add(new TbRow(
                                        tb.accountCode(),
                                        tb.accountName(),
                                        tb.accountType(),
                                        currency.format(tb.openingBalance() != null ? tb.openingBalance() : BigDecimal.ZERO),
                                        currency.format(tb.debitAmount() != null ? tb.debitAmount() : BigDecimal.ZERO),
                                        currency.format(tb.creditAmount() != null ? tb.creditAmount() : BigDecimal.ZERO),
                                        currency.format(tb.closingBalance() != null ? tb.closingBalance() : BigDecimal.ZERO)
                                ));
                            }
                        }

                        GeneralLedgerApiClient.FinancialStatementDto fs = glClient.financialStatements(fromDate, toDate);
                        if (fs != null) {
                            if (fs.totalAssets() != null) assetsStr = currency.format(fs.totalAssets());
                            if (fs.totalLiabilities() != null) liabStr = currency.format(fs.totalLiabilities());
                            if (fs.totalRevenue() != null) revStr = currency.format(fs.totalRevenue());
                            if (fs.totalExpense() != null) expStr = currency.format(fs.totalExpense());
                            if (fs.netProfit() != null) profitStr = currency.format(fs.netProfit());
                        }

                        int reqSize = qSpec.specialBook() ? Math.max(200, journalPageSize) : journalPageSize;
                        GeneralLedgerApiClient.PagedJournalResponse pageResp = glClient.journalEntriesPage(
                                journalCurrentPage, reqSize, qSpec.fromDate(), qSpec.toDate(),
                                qSpec.entryType(), qSpec.status(), qSpec.query());
                        if (pageResp != null) {
                            totalElements = pageResp.totalElements();
                            totalPages = Math.max(1, pageResp.totalPages());
                            if (pageResp.rows() != null) {
                                for (GeneralLedgerApiClient.JournalEntryDto je : pageResp.rows()) {
                                    JournalRow mapped = mapJournalDto(je);
                                    if (matchesBookView(mapped, bookFilter)) {
                                        jRows.add(mapped);
                                    }
                                }
                            }
                        }

                        plResp = glClient.profitAndLoss(fromDate, toDate);
                        bsResp = glClient.balanceSheet(toDate);
                        lockDto = glClient.periodLock();
                    }

                    return new LedgerResult(
                            assetsStr, liabStr, revStr, expStr, profitStr,
                            jRows, aRows, tRows, rawAccounts, plResp, bsResp, lockDto,
                            totalElements, totalPages
                    );
                },
                result -> {
                    if (kpiTotalAssets != null) kpiTotalAssets.setText(result.assets());
                    if (kpiTotalLiabilities != null) kpiTotalLiabilities.setText(result.liabilities());
                    if (kpiTotalRevenue != null) kpiTotalRevenue.setText(result.revenue());
                    if (kpiTotalExpense != null) kpiTotalExpense.setText(result.expenses());
                    if (kpiNetProfit != null) kpiNetProfit.setText(result.netProfit());

                    cachedAccountDtos = result.rawAccounts() != null ? result.rawAccounts() : List.of();
                    latestPl = result.plResp();
                    latestBs = result.bsResp();
                    latestPeriodLock = result.lockDto();
                    journalTotalElements = result.journalTotalElements();
                    journalTotalPages = Math.max(1, result.journalTotalPages());

                    updatePeriodLockBadge(latestPeriodLock);

                    allJournalRows.setAll(result.journalRows());
                    filteredJournalRows.setAll(result.journalRows());
                    if (tblJournals != null && journalPaginationHost != null) {
                        tblJournals.setItems(filteredJournalRows);
                    }
                    updateServerPaginationControls();

                    allAccountRows.setAll(result.accountRows());
                    filterAccounts();

                    allTbRows.setAll(result.tbRows());
                    filterTrialBalance();

                    populateProfitAndLoss(latestPl);
                    populateBalanceSheet(latestBs);
                },
                ex -> AppDialogService.error(tblJournals, "General Ledger Error",
                        "Unable to load General Ledger from server: " + ex.getMessage())
        );
    }

    private record JournalQuerySpec(LocalDate fromDate, LocalDate toDate, String entryType, String status, String query, boolean specialBook) {}

    private JournalQuerySpec resolveJournalQuerySpec(LocalDate from, LocalDate to, String typeFilter, String bookFilter, String query) {
        LocalDate effFrom = from;
        LocalDate effTo = to;
        String effType = (!"All Types".equals(typeFilter) && !typeFilter.isBlank()) ? typeFilter : null;
        String effStatus = null;
        boolean special = false;
        if ("Day Book (Today)".equals(bookFilter)) {
            LocalDate today = BusinessClock.today();
            effFrom = today;
            effTo = today;
        } else if ("Drafts Only".equals(bookFilter)) {
            effStatus = "DRAFT";
        } else if ("Cash & Bank Book".equals(bookFilter) || "GST Ledgers".equals(bookFilter) || "Manual & Reversals".equals(bookFilter)) {
            special = true;
        }
        return new JournalQuerySpec(effFrom, effTo, effType, effStatus, query, special);
    }

    private static boolean matchesBookView(JournalRow row, String bookFilter) {
        if (bookFilter == null || "All Vouchers".equals(bookFilter) || "Day Book (Today)".equals(bookFilter) || "Drafts Only".equals(bookFilter)) {
            return true;
        }
        return switch (bookFilter) {
            case "Cash & Bank Book" -> hasAnyAccountPrefix(row, "1010", "1020")
                    || "RECEIPT".equalsIgnoreCase(row.entryType())
                    || "PAYMENT".equalsIgnoreCase(row.entryType())
                    || "CONTRA".equalsIgnoreCase(row.entryType());
            case "GST Ledgers" -> hasAnyAccountPrefix(row, "1081", "1082", "1083", "2081", "2082", "2083", "2085");
            case "Manual & Reversals" -> Set.of("GENERAL", "EXPENSE", "CONTRA", "ADJUSTMENT", "REVERSAL", "CLOSING", "GST_OFFSET")
                    .contains(safeStr(row.entryType()).toUpperCase(Locale.ROOT));
            default -> true;
        };
    }

    private JournalRow mapJournalDto(GeneralLedgerApiClient.JournalEntryDto je) {
        String drHead = "";
        String crHead = "";
        if (je.lines() != null) {
            for (GeneralLedgerApiClient.JournalLineDto l : je.lines()) {
                if (l.debitAmount() != null && l.debitAmount().compareTo(BigDecimal.ZERO) > 0 && drHead.isBlank()) {
                    drHead = l.accountName() != null ? l.accountName() : l.accountCode();
                }
                if (l.creditAmount() != null && l.creditAmount().compareTo(BigDecimal.ZERO) > 0 && crHead.isBlank()) {
                    crHead = l.accountName() != null ? l.accountName() : l.accountCode();
                }
            }
        }
        String dtStr = je.entryDate() != null ? BusinessClock.formatDate(je.entryDate()) : "";
        double raw = je.totalDebit() != null ? je.totalDebit().doubleValue() : 0.0;
        return new JournalRow(
                je.id(),
                je.entryNumber(),
                dtStr,
                je.entryType(),
                je.referenceNo(),
                je.narration(),
                currency.format(je.totalDebit() != null ? je.totalDebit() : BigDecimal.ZERO),
                currency.format(je.totalCredit() != null ? je.totalCredit() : BigDecimal.ZERO),
                je.status() != null ? je.status() : "POSTED",
                drHead,
                crHead,
                currency.format(je.totalDebit() != null ? je.totalDebit() : BigDecimal.ZERO),
                currency.format(je.totalCredit() != null ? je.totalCredit() : BigDecimal.ZERO),
                raw,
                je.lines() != null ? je.lines() : List.of()
        );
    }

    private void buildServerPaginationBar() {
        lblJournalPageSummary = new Label("Showing 0 Vouchers");
        lblJournalPageSummary.getStyleClass().add("erp-pagination-summary");
        lblJournalPageSummary.setMinWidth(Region.USE_PREF_SIZE);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label lblRows = new Label("Rows per page");
        lblRows.getStyleClass().add("erp-pagination-label");
        lblRows.setMinWidth(Region.USE_PREF_SIZE);

        cmbJournalPageSize = new ComboBox<>(FXCollections.observableArrayList(25, 50, 100, 200));
        cmbJournalPageSize.setValue(journalPageSize);
        cmbJournalPageSize.getStyleClass().addAll("approved-combobox", "erp-pagination-size-combo");
        cmbJournalPageSize.setMinWidth(Region.USE_PREF_SIZE);
        cmbJournalPageSize.valueProperty().addListener((o, oldV, newV) -> {
            if (newV != null && newV > 0 && newV != journalPageSize) {
                journalPageSize = newV;
                journalCurrentPage = 0;
                loadJournalsPageAsync();
            }
        });

        btnJournalFirst = createPageNavButton("«", "First page", () -> goToJournalPage(0));
        btnJournalPrev = createPageNavButton("‹", "Previous page", () -> goToJournalPage(journalCurrentPage - 1));
        lblJournalPageNum = new Label("1 / 1");
        lblJournalPageNum.getStyleClass().add("erp-pagination-page-pill");
        lblJournalPageNum.setMinWidth(Region.USE_PREF_SIZE);
        btnJournalNext = createPageNavButton("›", "Next page", () -> goToJournalPage(journalCurrentPage + 1));
        btnJournalLast = createPageNavButton("»", "Last page", () -> goToJournalPage(Math.max(0, journalTotalPages - 1)));

        HBox navGroup = new HBox(4, btnJournalFirst, btnJournalPrev, lblJournalPageNum, btnJournalNext, btnJournalLast);
        navGroup.setAlignment(Pos.CENTER_RIGHT);

        HBox bar = new HBox(10, lblJournalPageSummary, spacer, lblRows, cmbJournalPageSize, navGroup);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6, 4, 2, 4));
        bar.getStyleClass().add("erp-pagination-bar");
        HBox.setHgrow(bar, Priority.ALWAYS);
        journalPaginationHost.getChildren().setAll(bar);
        tblJournals.setItems(filteredJournalRows);
    }

    private Button createPageNavButton(String text, String tip, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().addAll("approved-button", "erp-pagination-nav-btn");
        b.setMinWidth(Region.USE_PREF_SIZE);
        b.setTooltip(new Tooltip(tip));
        b.setOnAction(e -> action.run());
        return b;
    }

    private void goToJournalPage(int targetPage) {
        int clamped = Math.max(0, Math.min(targetPage, Math.max(0, journalTotalPages - 1)));
        if (clamped != journalCurrentPage) {
            journalCurrentPage = clamped;
            loadJournalsPageAsync();
        }
    }

    private void loadJournalsPageAsync() {
        if (initializingFilters) return;
        final LocalDate fromDate = dpFromDate != null ? dpFromDate.getValue() : null;
        final LocalDate toDate = dpToDate != null ? dpToDate.getValue() : null;
        final String searchQuery = txtJournalSearch != null && txtJournalSearch.getText() != null ? txtJournalSearch.getText().trim() : "";
        final String typeFilter = cmbVoucherFilter != null && cmbVoucherFilter.getValue() != null ? cmbVoucherFilter.getValue() : "All Types";
        final String bookFilter = cmbQuickBookFilter != null && cmbQuickBookFilter.getValue() != null ? cmbQuickBookFilter.getValue() : "All Vouchers";
        final JournalQuerySpec qSpec = resolveJournalQuerySpec(fromDate, toDate, typeFilter, bookFilter, searchQuery);

        UiTaskExecutor.submitLatest("gl-journal-page", () -> {
            if (!ConfigManager.getBoolean("module.gl.enabled", true)) {
                return new GeneralLedgerApiClient.PagedJournalResponse(List.of(), 0, journalPageSize, 0L, 1);
            }
            int reqSize = qSpec.specialBook() ? Math.max(200, journalPageSize) : journalPageSize;
            return glClient.journalEntriesPage(journalCurrentPage, reqSize, qSpec.fromDate(), qSpec.toDate(),
                    qSpec.entryType(), qSpec.status(), qSpec.query());
        }, pageResp -> {
            List<JournalRow> rows = new ArrayList<>();
            if (pageResp != null && pageResp.rows() != null) {
                for (GeneralLedgerApiClient.JournalEntryDto je : pageResp.rows()) {
                    JournalRow mapped = mapJournalDto(je);
                    if (matchesBookView(mapped, bookFilter)) {
                        rows.add(mapped);
                    }
                }
            }
            journalTotalElements = pageResp != null ? pageResp.totalElements() : 0L;
            journalTotalPages = pageResp != null ? Math.max(1, pageResp.totalPages()) : 1;
            allJournalRows.setAll(rows);
            filteredJournalRows.setAll(rows);
            updateServerPaginationControls();
        }, ex -> AppDialogService.error(tblJournals, "Journal Load Error", "Failed to load journal vouchers: " + ex.getMessage()));
    }

    private void updateServerPaginationControls() {
        int dispPage = journalCurrentPage + 1;
        int pages = Math.max(1, journalTotalPages);
        int shown = filteredJournalRows.size();
        if (lblJournalPageSummary != null) {
            if (shown == 0) {
                lblJournalPageSummary.setText("Showing 0 Vouchers");
            } else {
                long fromIdx = (long) journalCurrentPage * journalPageSize + 1L;
                long toIdx = fromIdx + shown - 1L;
                lblJournalPageSummary.setText(String.format(Locale.ENGLISH, "Showing %,d–%,d of %,d Vouchers",
                        fromIdx, Math.max(fromIdx, Math.max(toIdx, shown)), Math.max(journalTotalElements, shown)));
            }
        }
        if (lblJournalPageNum != null) lblJournalPageNum.setText(dispPage + " / " + pages);
        if (btnJournalFirst != null) btnJournalFirst.setDisable(journalCurrentPage <= 0);
        if (btnJournalPrev != null) btnJournalPrev.setDisable(journalCurrentPage <= 0);
        if (btnJournalNext != null) btnJournalNext.setDisable(dispPage >= pages);
        if (btnJournalLast != null) btnJournalLast.setDisable(dispPage >= pages);
    }

    private void updatePeriodLockBadge(GeneralLedgerApiClient.PeriodLockDto lock) {
        if (lblPeriodLockBadge == null) return;
        lblPeriodLockBadge.getStyleClass().removeAll("status-positive", "status-warning", "status-negative");
        if (lock != null && lock.lockedUntil() != null) {
            lblPeriodLockBadge.setText("Books Locked <= " + BusinessClock.formatDate(lock.lockedUntil()));
            lblPeriodLockBadge.getStyleClass().add("status-warning");
        } else {
            lblPeriodLockBadge.setText("Books Open");
            lblPeriodLockBadge.getStyleClass().add("status-positive");
        }
    }

    private void populateProfitAndLoss(GeneralLedgerApiClient.ProfitAndLossResponse pl) {
        allPlRows.clear();
        if (pl == null) {
            if (lblPlRevenue != null) lblPlRevenue.setText("Revenue: ₹ 0.00");
            if (lblPlDirectCost != null) lblPlDirectCost.setText("Direct Cost (COGS): ₹ 0.00");
            if (lblPlGrossProfit != null) lblPlGrossProfit.setText("Gross Profit: ₹ 0.00 (0.0%)");
            if (lblPlIndirectCost != null) lblPlIndirectCost.setText("Indirect Expenses: ₹ 0.00");
            if (lblPlNetProfit != null) lblPlNetProfit.setText("Net Profit: ₹ 0.00 (0.0%)");
            filterProfitLoss();
            return;
        }
        BigDecimal rev = nz(pl.totalRevenue());
        BigDecimal dc = nz(pl.totalDirectExpense());
        BigDecimal gp = nz(pl.grossProfit());
        BigDecimal ind = nz(pl.totalIndirectExpense());
        BigDecimal np = nz(pl.netProfit());
        double gpPct = rev.compareTo(BigDecimal.ZERO) > 0 ? gp.doubleValue() * 100.0 / rev.doubleValue() : 0.0;
        double npPct = rev.compareTo(BigDecimal.ZERO) > 0 ? np.doubleValue() * 100.0 / rev.doubleValue() : 0.0;

        if (lblPlRevenue != null) lblPlRevenue.setText("Revenue: " + currency.format(rev));
        if (lblPlDirectCost != null) lblPlDirectCost.setText("Direct Cost (COGS): " + currency.format(dc));
        if (lblPlGrossProfit != null) lblPlGrossProfit.setText(String.format(Locale.ROOT, "Gross Profit: %s (%.1f%%)", currency.format(gp), gpPct));
        if (lblPlIndirectCost != null) lblPlIndirectCost.setText("Indirect Expenses: " + currency.format(ind));
        if (lblPlNetProfit != null) {
            lblPlNetProfit.setText(String.format(Locale.ROOT, "%s: %s (%.1f%%)", np.compareTo(BigDecimal.ZERO) >= 0 ? "Net Profit" : "Net Loss", currency.format(np), npPct));
            lblPlNetProfit.getStyleClass().removeAll("comm-status-sent", "comm-status-failed");
            lblPlNetProfit.getStyleClass().add(np.compareTo(BigDecimal.ZERO) >= 0 ? "comm-status-sent" : "comm-status-failed");
        }

        if (pl.revenueLines() != null) {
            for (var line : pl.revenueLines()) {
                allPlRows.add(new PlRow("REVENUE", line.accountCode(), line.accountName(), safeStr(line.subtype()), currency.format(nz(line.amount()))));
            }
        }
        if (pl.directExpenseLines() != null) {
            for (var line : pl.directExpenseLines()) {
                allPlRows.add(new PlRow("DIRECT COST", line.accountCode(), line.accountName(), safeStr(line.subtype()), currency.format(nz(line.amount()))));
            }
        }
        if (pl.indirectExpenseLines() != null) {
            for (var line : pl.indirectExpenseLines()) {
                allPlRows.add(new PlRow("INDIRECT EXPENSE", line.accountCode(), line.accountName(), safeStr(line.subtype()), currency.format(nz(line.amount()))));
            }
        }
        filterProfitLoss();
    }

    private void populateBalanceSheet(GeneralLedgerApiClient.BalanceSheetResponse bs) {
        bsAssetRows.clear();
        bsLeRows.clear();
        if (bs == null) {
            if (lblBsTotalAssets != null) lblBsTotalAssets.setText("Total Assets: ₹ 0.00");
            if (lblBsTotalLiab != null) lblBsTotalLiab.setText("Liabilities: ₹ 0.00");
            if (lblBsTotalEquity != null) lblBsTotalEquity.setText("Equity + Net Profit: ₹ 0.00");
            return;
        }
        if (bs.assetLines() != null) {
            for (var a : bs.assetLines()) {
                bsAssetRows.add(new BsRow(a.accountCode(), a.accountName(), safeStr(a.subtype()), currency.format(nz(a.balance()))));
            }
        }
        if (bs.liabilityLines() != null) {
            for (var l : bs.liabilityLines()) {
                bsLeRows.add(new BsRow(l.accountCode(), l.accountName(), "LIABILITY · " + safeStr(l.subtype()), currency.format(nz(l.balance()))));
            }
        }
        if (bs.equityLines() != null) {
            for (var eq : bs.equityLines()) {
                bsLeRows.add(new BsRow(eq.accountCode(), eq.accountName(), "EQUITY · " + safeStr(eq.subtype()), currency.format(nz(eq.balance()))));
            }
        }
        BigDecimal currentPl = nz(bs.currentPeriodNetProfit());
        if (currentPl.compareTo(BigDecimal.ZERO) != 0) {
            bsLeRows.add(new BsRow("-", "Current Period Net Profit / (Loss)", "RETAINED EARNINGS", currency.format(currentPl)));
        }
        BigDecimal totalA = nz(bs.totalAssets());
        BigDecimal totalL = nz(bs.totalLiabilities());
        BigDecimal totalE = nz(bs.totalEquity());
        BigDecimal totalLE = nz(bs.totalLiabilitiesAndEquity());
        BigDecimal diff = totalA.subtract(totalLE).abs();

        if (lblBsTotalAssets != null) lblBsTotalAssets.setText("Total Assets: " + currency.format(totalA));
        if (lblBsTotalLiab != null) lblBsTotalLiab.setText("Liabilities: " + currency.format(totalL));
        if (lblBsTotalEquity != null) lblBsTotalEquity.setText("Equity + Net Profit: " + currency.format(totalE.add(currentPl)));
        if (lblBsTallyPill != null) {
            lblBsTallyPill.getStyleClass().removeAll("comm-status-sent", "comm-status-failed");
            if (diff.compareTo(new BigDecimal("0.05")) <= 0) {
                lblBsTallyPill.setText("TALLIED (Assets = L + E)");
                lblBsTallyPill.getStyleClass().add("comm-status-sent");
            } else {
                lblBsTallyPill.setText("DIFFERENCE: " + currency.format(diff));
                lblBsTallyPill.getStyleClass().add("comm-status-failed");
            }
        }
    }

    private void updateTrialBalanceTotals() {
        BigDecimal totOpen = BigDecimal.ZERO;
        BigDecimal totDr = BigDecimal.ZERO;
        BigDecimal totCr = BigDecimal.ZERO;
        BigDecimal totClose = BigDecimal.ZERO;
        for (TbRow row : filteredTbRows) {
            totOpen = totOpen.add(parseMoney(row.opening()));
            totDr = totDr.add(parseMoney(row.debit()));
            totCr = totCr.add(parseMoney(row.credit()));
            totClose = totClose.add(parseMoney(row.closing()));
        }
        if (lblTbTotalOpening != null) lblTbTotalOpening.setText("Total Opening: " + currency.format(totOpen));
        if (lblTbTotalDebit != null) lblTbTotalDebit.setText("Total Debit: " + currency.format(totDr));
        if (lblTbTotalCredit != null) lblTbTotalCredit.setText("Total Credit: " + currency.format(totCr));
        if (lblTbTotalClosing != null) lblTbTotalClosing.setText("Net Closing: " + currency.format(totClose));
        if (lblTbBalancePill != null) {
            BigDecimal diff = totDr.subtract(totCr).abs();
            lblTbBalancePill.getStyleClass().removeAll("comm-status-sent", "comm-status-failed");
            if (diff.compareTo(new BigDecimal("0.05")) <= 0) {
                lblTbBalancePill.setText("BALANCED (Dr = Cr)");
                lblTbBalancePill.getStyleClass().add("comm-status-sent");
            } else {
                lblTbBalancePill.setText("DIFF: " + currency.format(diff));
                lblTbBalancePill.getStyleClass().add("comm-status-failed");
            }
        }
    }

    @FXML
    public void openNewJournalDialog() {
        PermissionService.require("GENERAL_LEDGER.CREATE_JOURNAL", "create journal voucher");
        ManualJournalDialog.show(tblJournals, glClient, cachedAccountDtos, this::loadLedgerDataAsync);
    }

    @FXML
    public void openNewAccountDialog() {
        PermissionService.require("GENERAL_LEDGER.CREATE_ACCOUNT", "create account");
        ChartOfAccountsDialog.show(tblAccounts, glClient, null, cachedAccountDtos, this::loadLedgerDataAsync);
    }

    @FXML
    public void editSelectedAccount() {
        PermissionService.require("GENERAL_LEDGER.EDIT_ACCOUNT", "edit account");
        AccountRow selected = tblAccounts != null ? tblAccounts.getSelectionModel().getSelectedItem() : null;
        if (selected == null) {
            AppDialogService.warning(tblAccounts, "Select Account", "Please select an account row from Chart of Accounts first.");
            return;
        }
        GeneralLedgerApiClient.AccountDto dto = cachedAccountDtos.stream()
                .filter(a -> selected.code().equalsIgnoreCase(a.accountCode()))
                .findFirst().orElse(null);
        if (dto == null) {
            AppDialogService.warning(tblAccounts, "Account Sync", "Please refresh Chart of Accounts from the server before editing.");
            return;
        }
        ChartOfAccountsDialog.show(tblAccounts, glClient, dto, cachedAccountDtos, this::loadLedgerDataAsync);
    }

    @FXML
    public void openSelectedAccountLedger() {
        AccountRow sel = tblAccounts != null ? tblAccounts.getSelectionModel().getSelectedItem() : null;
        if (sel == null) {
            AppDialogService.warning(tblAccounts, "Select Account", "Please select an account to view its Ledger Statement.");
            return;
        }
        openAccountLedger(sel.code(), sel.name());
    }

    @FXML
    public void openSelectedTbLedger() {
        TbRow sel = tblTrialBalance != null ? tblTrialBalance.getSelectionModel().getSelectedItem() : null;
        if (sel == null) {
            AppDialogService.warning(tblTrialBalance, "Select Account", "Please select an account row in Trial Balance first.");
            return;
        }
        openAccountLedger(sel.code(), sel.name());
    }

    @FXML
    public void openSelectedPlLedger() {
        PlRow sel = tblProfitLoss != null ? tblProfitLoss.getSelectionModel().getSelectedItem() : null;
        if (sel == null) {
            AppDialogService.warning(tblProfitLoss, "Select Account", "Please select a revenue or expense account first.");
            return;
        }
        openAccountLedger(sel.code(), sel.name());
    }

    @FXML
    public void postSelectedDraft() {
        PermissionService.require("GENERAL_LEDGER.POST_JOURNAL", "post draft journal voucher");
        if (selectedJournal == null) return;
        postDraftJournal(selectedJournal);
    }

    @FXML
    public void reverseSelectedVoucher() {
        PermissionService.require("GENERAL_LEDGER.REVERSE_JOURNAL", "reverse journal voucher");
        if (selectedJournal == null) return;
        reverseJournal(selectedJournal);
    }

    private void reverseJournal(JournalRow row) {
        if (row == null || row.id() == null) {
            AppDialogService.warning(tblJournals, "Reverse Voucher", "Only server-persisted posted journal entries can be reversed.");
            return;
        }
        if (!"POSTED".equalsIgnoreCase(row.status())) {
            AppDialogService.warning(tblJournals, "Cannot Reverse", "Only POSTED vouchers can be reversed. Current status: " + row.status());
            return;
        }
        Dialog<ButtonType> dlg = new OwnedDialog<>(tblJournals);
        dlg.setTitle("Reverse Journal Voucher — " + row.entryNumber());
        dlg.setResizable(true);

        DialogPane pane = dlg.getDialogPane();
        pane.getStyleClass().addAll("dse-dialog-pane", "approved-dialog", "comm-inspection-dialog", "erp-responsive-dialog");
        pane.getProperties().put("dse.dialog.presented", true);
        pane.setPrefSize(640, 390);
        pane.setMinWidth(580);
        pane.setMinHeight(350);

        Region topAccent = new Region();
        topAccent.setMinHeight(5);
        topAccent.setPrefHeight(5);
        topAccent.setMaxHeight(5);
        topAccent.getStyleClass().addAll("dse-dialog-accent", "comm-top-accent", "comm-accent-email");

        StackPane heroIcon = new StackPane(IconFactory.icon("rollback", 22));
        heroIcon.getStyleClass().addAll("erp-page-header-icon", "dse-dialog-icon-box");

        Label lblKicker = new Label("GENERAL LEDGER  •  CONTRA REVERSAL");
        lblKicker.getStyleClass().addAll("comm-hero-kicker", "comm-kicker-email");
        Label lblTitle = new Label("Reverse Voucher: " + row.entryNumber());
        lblTitle.getStyleClass().add("stock-ledger-title");

        VBox heroText = new VBox(2, lblKicker, lblTitle);
        HBox.setHgrow(heroText, Priority.ALWAYS);

        Button btnTopClose = new Button();
        btnTopClose.setGraphic(IconFactory.compactIcon("cancel", 14));
        btnTopClose.getStyleClass().addAll("dse-dialog-close", "icon-button");
        btnTopClose.getProperties().put("erp-icon-preserve", true);
        btnTopClose.setOnAction(e -> dlg.close());

        HBox headerHero = new HBox(12, heroIcon, heroText, btnTopClose);
        headerHero.setAlignment(Pos.CENTER_LEFT);
        headerHero.setPadding(new Insets(12, 18, 12, 18));
        headerHero.getStyleClass().addAll("comm-hero-banner", "comm-hero-email");

        VBox headerWrap = new VBox(0, topAccent, headerHero);
        headerWrap.getStyleClass().addAll("dse-stage-header-wrap", "comm-header-wrap");

        Label info = new Label("This creates an audited contra REVERSAL voucher swapping all Debit & Credit legs and marks " + row.entryNumber() + " as REVERSED.");
        info.setWrapText(true);
        info.getStyleClass().add("page-subtitle");

        DatePicker dpRevDate = new DatePicker(BusinessClock.today());
        DatePickerFormatter.attach(dpRevDate);
        dpRevDate.setPrefWidth(190);

        TextField txtReason = new TextField();
        txtReason.setPromptText("Audit reason for reversing " + row.entryNumber() + "...");
        txtReason.getStyleClass().add("approved-input");

        Label lblRevDate = new Label("Reversal Date");
        lblRevDate.getStyleClass().addAll("field-label", "comm-section-heading");
        Label lblReason = new Label("Audit Reason");
        lblReason.getStyleClass().addAll("field-label", "comm-section-heading");

        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(12);
        grid.add(lblRevDate, 0, 0);
        grid.add(dpRevDate, 1, 0);
        grid.add(lblReason, 0, 1);
        grid.add(txtReason, 1, 1);
        GridPane.setHgrow(txtReason, Priority.ALWAYS);

        VBox formCard = new VBox(12, info, grid);
        formCard.setPadding(new Insets(16));
        formCard.getStyleClass().add("comm-detail-card");
        VBox.setVgrow(formCard, Priority.ALWAYS);

        Button btnConfirm = new Button("Create Reversal Voucher");
        btnConfirm.setGraphic(IconFactory.compactIcon("rollback", 14));
        btnConfirm.getStyleClass().addAll("approved-button", "approved-primary-button");

        Button btnCancel = new Button("Cancel");
        btnCancel.setGraphic(IconFactory.compactIcon("cancel", 14));
        btnCancel.getStyleClass().addAll("approved-button", "approved-danger-ghost-button");
        btnCancel.setOnAction(e -> dlg.close());

        btnConfirm.setOnAction(e -> {
            LocalDate revDate = dpRevDate.getValue() != null ? dpRevDate.getValue() : BusinessClock.today();
            String reason = txtReason.getText() != null ? txtReason.getText().trim() : "";
            btnConfirm.setDisable(true);
            UiTaskExecutor.submitLatest(
                    "gl-reverse-" + row.id(),
                    () -> {
                        org.example.model.AppUser u = org.example.service.SessionService.current();
                        String username = u != null && u.getUsername() != null ? u.getUsername() : "admin";
                        return glClient.reverseJournalEntry(row.id(), new GeneralLedgerApiClient.ReverseJournalRequest(revDate, reason, username));
                    },
                    created -> {
                        dlg.close();
                        AppDialogService.info(tblJournals, "Voucher Reversed",
                                "Reversal voucher " + (created != null ? created.entryNumber() : "") + " posted successfully.");
                        closeJournalDetails();
                        loadLedgerDataAsync();
                    },
                    ex -> {
                        btnConfirm.setDisable(false);
                        AppDialogService.error(pane, "Reversal Failed", ex.getMessage());
                    }
            );
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actionDock = new HBox(10, btnConfirm, spacer, btnCancel);
        actionDock.setAlignment(Pos.CENTER_LEFT);
        actionDock.getStyleClass().add("erp-inspection-action-dock");

        VBox contentBody = new VBox(12, formCard, actionDock);
        contentBody.setPadding(new Insets(14, 20, 14, 20));
        VBox.setVgrow(contentBody, Priority.ALWAYS);

        VBox root = new VBox(0, headerWrap, contentBody);
        root.getStyleClass().addAll("dse-dialog-shell", "dse-dialog-shell-workspace", "comm-inspection-shell");
        pane.setContent(root);
        pane.getButtonTypes().add(ButtonType.CLOSE);
        dlg.setOnShowing(ev -> {
            Node defaultBar = pane.lookup(".button-bar");
            if (defaultBar != null) {
                defaultBar.setVisible(false);
                defaultBar.setManaged(false);
            }
        });

        dlg.showAndWait();
    }

    @FXML
    public void openPeriodLockDialog() {
        PermissionService.require("GENERAL_LEDGER.LOCK_PERIOD", "manage accounting period lock");
        Dialog<ButtonType> dlg = new OwnedDialog<>(tblJournals);
        dlg.setTitle("Accounting Period Lock / Books Freeze");
        dlg.setResizable(true);

        DialogPane pane = dlg.getDialogPane();
        pane.getStyleClass().addAll("dse-dialog-pane", "approved-dialog", "comm-inspection-dialog", "erp-responsive-dialog");
        pane.getProperties().put("dse.dialog.presented", true);
        pane.setPrefSize(660, 410);
        pane.setMinWidth(600);
        pane.setMinHeight(370);

        Region topAccent = new Region();
        topAccent.setMinHeight(5);
        topAccent.setPrefHeight(5);
        topAccent.setMaxHeight(5);
        topAccent.getStyleClass().addAll("dse-dialog-accent", "comm-top-accent", "comm-accent-email");

        StackPane heroIcon = new StackPane(IconFactory.icon("lock", 22));
        heroIcon.getStyleClass().addAll("erp-page-header-icon", "dse-dialog-icon-box");

        Label lblKicker = new Label("ACCOUNTING CONTROLS  •  BOOKS FREEZE");
        lblKicker.getStyleClass().addAll("comm-hero-kicker", "comm-kicker-email");
        Label lblTitle = new Label("Accounting Period Lock");
        lblTitle.getStyleClass().add("stock-ledger-title");

        VBox heroText = new VBox(2, lblKicker, lblTitle);
        HBox.setHgrow(heroText, Priority.ALWAYS);

        Button btnTopClose = new Button();
        btnTopClose.setGraphic(IconFactory.compactIcon("cancel", 14));
        btnTopClose.getStyleClass().addAll("dse-dialog-close", "icon-button");
        btnTopClose.getProperties().put("erp-icon-preserve", true);
        btnTopClose.setOnAction(e -> dlg.close());

        HBox headerHero = new HBox(12, heroIcon, heroText, btnTopClose);
        headerHero.setAlignment(Pos.CENTER_LEFT);
        headerHero.setPadding(new Insets(12, 18, 12, 18));
        headerHero.getStyleClass().addAll("comm-hero-banner", "comm-hero-email");

        VBox headerWrap = new VBox(0, topAccent, headerHero);
        headerWrap.getStyleClass().addAll("dse-stage-header-wrap", "comm-header-wrap");

        Label info = new Label("Locking books blocks backdated Sales, Purchases, Payments, Returns, and Manual Journals on or before the Lock Date. Only ADMIN / SUPER_ADMIN can modify this setting.");
        info.setWrapText(true);
        info.getStyleClass().add("page-subtitle");

        DatePicker dpLock = new DatePicker(latestPeriodLock != null ? latestPeriodLock.lockedUntil() : null);
        DatePickerFormatter.attach(dpLock);
        dpLock.setPromptText("Leave empty to unlock all periods");
        dpLock.setPrefWidth(210);

        boolean currentlyLocked = latestPeriodLock != null && latestPeriodLock.active() && latestPeriodLock.lockedUntil() != null;
        String lockMetaText = currentlyLocked
                ? "Frozen through " + BusinessClock.formatDate(latestPeriodLock.lockedUntil())
                  + (latestPeriodLock.lockedBy() != null ? " by " + latestPeriodLock.lockedBy() : "")
                : "Books are currently OPEN (no lock date set)";
        Label lblCurrentStatus = new Label(lockMetaText);
        lblCurrentStatus.getStyleClass().addAll("comm-status-badge", currentlyLocked ? "comm-status-failed" : "comm-status-sent");

        Label lblStatusCap = new Label("Current Status");
        lblStatusCap.getStyleClass().addAll("field-label", "comm-section-heading");
        Label lblFreezeCap = new Label("Freeze Books Through");
        lblFreezeCap.getStyleClass().addAll("field-label", "comm-section-heading");

        Button btnUnlock = new Button("Clear Lock Date (Open All)");
        btnUnlock.setGraphic(IconFactory.compactIcon("refresh", 13));
        btnUnlock.getStyleClass().addAll("approved-button", "approved-secondary-button");
        btnUnlock.setOnAction(e -> dpLock.setValue(null));

        HBox dateRow = new HBox(10, dpLock, btnUnlock);
        dateRow.setAlignment(Pos.CENTER_LEFT);

        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(14);
        grid.add(lblStatusCap, 0, 0);
        grid.add(lblCurrentStatus, 1, 0);
        grid.add(lblFreezeCap, 0, 1);
        grid.add(dateRow, 1, 1);

        VBox formCard = new VBox(14, info, grid);
        formCard.setPadding(new Insets(16));
        formCard.getStyleClass().add("comm-detail-card");
        VBox.setVgrow(formCard, Priority.ALWAYS);

        Button btnSave = new Button("Save Period Lock");
        btnSave.setGraphic(IconFactory.compactIcon("lock", 14));
        btnSave.getStyleClass().addAll("approved-button", "approved-primary-button");

        Button btnCancel = new Button("Cancel");
        btnCancel.setGraphic(IconFactory.compactIcon("cancel", 14));
        btnCancel.getStyleClass().addAll("approved-button", "approved-danger-ghost-button");
        btnCancel.setOnAction(e -> dlg.close());

        btnSave.setOnAction(e -> {
            LocalDate lockDate = dpLock.getValue();
            btnSave.setDisable(true);
            UiTaskExecutor.submitLatest(
                    "gl-period-lock-save",
                    () -> glClient.setPeriodLock(lockDate),
                    saved -> {
                        dlg.close();
                        latestPeriodLock = saved;
                        updatePeriodLockBadge(saved);
                        AppDialogService.info(tblJournals, "Period Lock Updated",
                                saved != null && saved.lockedUntil() != null
                                        ? "Books are now frozen for all transactions on or before " + BusinessClock.formatDate(saved.lockedUntil()) + "."
                                        : "Accounting period lock cleared. Books are now open.");
                    },
                    ex -> {
                        btnSave.setDisable(false);
                        AppDialogService.error(pane, "Period Lock Error", ex.getMessage());
                    }
            );
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actionDock = new HBox(10, btnSave, spacer, btnCancel);
        actionDock.setAlignment(Pos.CENTER_LEFT);
        actionDock.getStyleClass().add("erp-inspection-action-dock");

        VBox contentBody = new VBox(12, formCard, actionDock);
        contentBody.setPadding(new Insets(14, 20, 14, 20));
        VBox.setVgrow(contentBody, Priority.ALWAYS);

        VBox root = new VBox(0, headerWrap, contentBody);
        root.getStyleClass().addAll("dse-dialog-shell", "dse-dialog-shell-workspace", "comm-inspection-shell");
        pane.setContent(root);
        pane.getButtonTypes().add(ButtonType.CLOSE);
        dlg.setOnShowing(ev -> {
            Node defaultBar = pane.lookup(".button-bar");
            if (defaultBar != null) {
                defaultBar.setVisible(false);
                defaultBar.setManaged(false);
            }
        });

        dlg.showAndWait();
    }

    @FXML
    public void openHealthCheckDialog() {
        PermissionService.require("GENERAL_LEDGER.VIEW", "view GL reconciliation health check");
        UiTaskExecutor.submitLatest("gl-reconciliation-health", glClient::reconciliationHealth, health -> {
            OwnedDialog<ButtonType> dlg = new OwnedDialog<>(tblJournals);
            dlg.setTitle("GL Control Account & Posting Health Audit");
            dlg.setResizable(true);
            DialogPane pane = dlg.getDialogPane();
            pane.getStyleClass().addAll("dse-dialog-pane", "approved-dialog", "comm-inspection-dialog", "erp-responsive-dialog");
            PopupTableWorkspace.applyWideWorkspace90(dlg, tblJournals);

            Label lblKicker = new Label("AUDIT & RECONCILIATION  •  GL vs SUB-LEDGER INTEGRITY");
            lblKicker.getStyleClass().addAll("comm-hero-kicker", "comm-kicker-email");
            Label lblTitle = new Label(health.healthy() ? "General Ledger Books Healthy & Synchronized" : "Reconciliation Variances or Unposted Documents Found");
            lblTitle.getStyleClass().add("stock-ledger-title");
            Label statusPill = new Label(health.healthy() ? "HEALTHY" : (health.mismatchCount() + " VARIANCES • " + health.unpostedCount() + " UNPOSTED"));
            statusPill.getStyleClass().addAll("status-pill", health.healthy() ? "pill-success" : "pill-warning");

            VBox heroText = new VBox(2, lblKicker, lblTitle);
            HBox.setHgrow(heroText, Priority.ALWAYS);
            HBox hero = new HBox(12, IconFactory.icon("reconciliation", 24), heroText, statusPill);
            hero.setAlignment(Pos.CENTER_LEFT);
            hero.setPadding(new Insets(12, 18, 12, 18));
            hero.getStyleClass().addAll("comm-hero-banner", "comm-hero-email");
            DocumentInspectionScaffold.installDragPhysics(hero, dlg);

            TableView<GeneralLedgerApiClient.ReconciliationControlRow> tblCtrl = new TableView<>();
            tblCtrl.setPrefHeight(260);
            tblCtrl.setMinHeight(180);
            VBox.setVgrow(tblCtrl, Priority.ALWAYS);
            TableColumn<GeneralLedgerApiClient.ReconciliationControlRow, String> cArea = new TableColumn<>("Control Area");
            cArea.setCellValueFactory(v -> new SimpleStringProperty(safeStr(v.getValue().area())));
            TableColumn<GeneralLedgerApiClient.ReconciliationControlRow, String> cAcc = new TableColumn<>("Account");
            cAcc.setCellValueFactory(v -> new SimpleStringProperty(safeStr(v.getValue().accountCode()) + " " + safeStr(v.getValue().accountName())));
            TableColumn<GeneralLedgerApiClient.ReconciliationControlRow, String> cGl = new TableColumn<>("GL Balance (₹)");
            cGl.setCellValueFactory(v -> new SimpleStringProperty(currency.format(nz(v.getValue().glBalance()))));
            TableColumn<GeneralLedgerApiClient.ReconciliationControlRow, String> cSub = new TableColumn<>("Sub-Ledger (₹)");
            cSub.setCellValueFactory(v -> new SimpleStringProperty(currency.format(nz(v.getValue().subledgerBalance()))));
            TableColumn<GeneralLedgerApiClient.ReconciliationControlRow, String> cVar = new TableColumn<>("Variance (₹)");
            cVar.setCellValueFactory(v -> new SimpleStringProperty(currency.format(nz(v.getValue().variance()))));
            TableColumn<GeneralLedgerApiClient.ReconciliationControlRow, String> cSt = new TableColumn<>("Status");
            cSt.setCellValueFactory(v -> new SimpleStringProperty(safeStr(v.getValue().status())));
            tblCtrl.getColumns().addAll(List.of(cArea, cAcc, cGl, cSub, cVar, cSt));
            tblCtrl.setItems(FXCollections.observableArrayList(health.controls() != null ? health.controls() : List.of()));
            DynamicTableLayoutManager.install(tblCtrl);

            TableView<GeneralLedgerApiClient.UnpostedDocRow> tblUnposted = new TableView<>();
            tblUnposted.setPrefHeight(360);
            tblUnposted.setMinHeight(220);
            VBox.setVgrow(tblUnposted, Priority.ALWAYS);
            TableColumn<GeneralLedgerApiClient.UnpostedDocRow, String> uType = new TableColumn<>("Type");
            uType.setCellValueFactory(v -> new SimpleStringProperty(safeStr(v.getValue().docType())));
            TableColumn<GeneralLedgerApiClient.UnpostedDocRow, String> uNo = new TableColumn<>("Document No");
            uNo.setCellValueFactory(v -> new SimpleStringProperty(safeStr(v.getValue().docNo())));
            TableColumn<GeneralLedgerApiClient.UnpostedDocRow, String> uDt = new TableColumn<>("Date");
            uDt.setCellValueFactory(v -> new SimpleStringProperty(BusinessClock.formatDate(v.getValue().date())));
            TableColumn<GeneralLedgerApiClient.UnpostedDocRow, String> uAmt = new TableColumn<>("Amount (₹)");
            uAmt.setCellValueFactory(v -> new SimpleStringProperty(currency.format(nz(v.getValue().amount()))));
            TableColumn<GeneralLedgerApiClient.UnpostedDocRow, String> uSt = new TableColumn<>("Status");
            uSt.setCellValueFactory(v -> new SimpleStringProperty(safeStr(v.getValue().status())));
            tblUnposted.getColumns().addAll(List.of(uType, uNo, uDt, uAmt, uSt));
            tblUnposted.setItems(FXCollections.observableArrayList(health.unpostedDocuments() != null ? health.unpostedDocuments() : List.of()));
            tblUnposted.setPlaceholder(new Label("All posted operational documents have valid GL entries."));
            DynamicTableLayoutManager.install(tblUnposted);

            Label lbl1 = new Label("1. Control Account vs Operational Sub-Ledger Reconciliation");
            lbl1.getStyleClass().add("section-title");
            Label lbl2 = new Label("2. Operational Documents Missing GL Journal Entry");
            lbl2.getStyleClass().add("section-title");

            Button btnClose = new Button("Close");
            btnClose.setGraphic(IconFactory.compactIcon("cancel", 14));
            btnClose.getStyleClass().addAll("approved-button", "approved-secondary-button");
            btnClose.setOnAction(e -> dlg.close());
            HBox dock = new HBox(10, new Region(), btnClose);
            HBox.setHgrow(dock.getChildren().getFirst(), Priority.ALWAYS);
            dock.getStyleClass().add("erp-inspection-action-dock");

            VBox body = new VBox(12, lbl1, tblCtrl, lbl2, tblUnposted, dock);
            body.setPadding(new Insets(14, 20, 14, 20));
            body.setFillWidth(true);
            VBox.setVgrow(body, Priority.ALWAYS);
            VBox root = new VBox(0, hero, body);
            root.setFillWidth(true);
            root.getStyleClass().addAll("dse-dialog-shell", "dse-dialog-shell-workspace", "comm-inspection-shell");
            pane.setContent(root);
            pane.getButtonTypes().add(ButtonType.CLOSE);
            dlg.setOnShowing(ev -> {
                Node bb = pane.lookup(".button-bar");
                if (bb != null) { bb.setVisible(false); bb.setManaged(false); }
            });
            dlg.showAndWait();
        }, ex -> AppDialogService.error(tblJournals, "Health Check Failed", "Could not run GL Health Check: " + ex.getMessage()));
    }

    @FXML
    public void openCloseYearDialog() {
        PermissionService.require("GENERAL_LEDGER.CLOSE_YEAR", "close fiscal year and transfer net profit");
        OwnedDialog<ButtonType> dlg = new OwnedDialog<>(tblJournals);
        dlg.setTitle("Fiscal Year-End Closing Wizard");
        dlg.setResizable(true);
        DialogPane pane = dlg.getDialogPane();
        pane.getStyleClass().addAll("dse-dialog-pane", "approved-dialog", "comm-inspection-dialog", "erp-responsive-dialog");
        PopupTableWorkspace.prepareDialog(dlg, 820);
        pane.setPrefSize(820, 580);
        pane.setMinSize(760, 560);

        LocalDate today = BusinessClock.today();
        int fyStartYear = today.getMonthValue() >= 4 ? today.getYear() - 1 : today.getYear() - 2;
        LocalDate defFrom = LocalDate.of(fyStartYear, 4, 1);
        LocalDate defTo = LocalDate.of(fyStartYear + 1, 3, 31);
        String defLabel = "FY " + fyStartYear + "-" + String.format("%02d", (fyStartYear + 1) % 100);

        TextField txtLabel = new TextField(defLabel);
        txtLabel.getStyleClass().add("approved-input");
        DatePicker dpFyFrom = new DatePicker(defFrom);
        DatePicker dpFyTo = new DatePicker(defTo);
        DatePickerFormatter.attachAll(dpFyFrom, dpFyTo);
        CheckBox chkLock = new CheckBox("Immediately lock accounting period through " + BusinessClock.formatDate(defTo));
        chkLock.setSelected(true);
        chkLock.getStyleClass().add("approved-checkbox");
        dpFyTo.valueProperty().addListener((o, a, nv) -> {
            if (nv != null) chkLock.setText("Immediately lock accounting period through " + BusinessClock.formatDate(nv));
        });

        Label lblKicker = new Label("FISCAL YEAR CLOSE  •  REVENUE & EXPENSE TRANSFER TO RETAINED EARNINGS");
        lblKicker.getStyleClass().addAll("comm-hero-kicker", "comm-kicker-email");
        Label lblTitle = new Label("Close Fiscal Year");
        lblTitle.getStyleClass().add("stock-ledger-title");
        VBox heroText = new VBox(2, lblKicker, lblTitle);
        HBox.setHgrow(heroText, Priority.ALWAYS);
        HBox hero = new HBox(12, IconFactory.icon("calendar", 22), heroText);
        hero.setAlignment(Pos.CENTER_LEFT);
        hero.setPadding(new Insets(12, 18, 12, 18));
        hero.getStyleClass().addAll("comm-hero-banner", "comm-hero-email");
        DocumentInspectionScaffold.installDragPhysics(hero, dlg);

        Label warn = new Label("Executing Fiscal Year Close zeros out net Revenue and Expense balances for the selected period into Retained Earnings (3020) via an audited CLOSING journal voucher.");
        warn.setWrapText(true);
        warn.setMaxWidth(Double.MAX_VALUE);
        warn.setMinHeight(Region.USE_PREF_SIZE);
        warn.getStyleClass().add("page-subtitle");

        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(12);
        grid.add(new Label("Fiscal Year Label"), 0, 0);
        grid.add(txtLabel, 1, 0);
        grid.add(new Label("FY Start Date"), 0, 1);
        grid.add(dpFyFrom, 1, 1);
        grid.add(new Label("FY End Date"), 0, 2);
        grid.add(dpFyTo, 1, 2);
        grid.add(chkLock, 1, 3);

        Button btnExecute = new Button("Post Closing Voucher");
        btnExecute.setGraphic(IconFactory.compactIcon("check", 14));
        btnExecute.getStyleClass().addAll("approved-button", "approved-primary-button");
        Button btnCancel = new Button("Cancel");
        btnCancel.setGraphic(IconFactory.compactIcon("cancel", 14));
        btnCancel.getStyleClass().addAll("approved-button", "approved-secondary-button");
        btnCancel.setOnAction(e -> dlg.close());

        btnExecute.setOnAction(e -> {
            String fyLabel = txtLabel.getText() == null ? "" : txtLabel.getText().trim();
            LocalDate from = dpFyFrom.getValue();
            LocalDate to = dpFyTo.getValue();
            if (fyLabel.isBlank() || from == null || to == null || to.isBefore(from)) {
                AppDialogService.warning(pane, "Invalid Period", "Specify a valid Fiscal Year label and date range.");
                return;
            }
            if (!AppDialogService.confirm(pane, "Confirm Year-End Close",
                    "Close " + fyLabel + " (" + BusinessClock.formatDate(from) + " to " + BusinessClock.formatDate(to) + ")?",
                    "This posts an official CLOSING voucher transferring net profit/loss to Retained Earnings (3020).")) {
                return;
            }
            btnExecute.setDisable(true);
            UiTaskExecutor.submitAction("gl-close-fy", () -> {
                var u = org.example.service.SessionService.current();
                String actor = u != null && u.getUsername() != null ? u.getUsername() : "admin";
                return glClient.closeFiscalYear(new GeneralLedgerApiClient.FiscalYearCloseRequest(
                        fyLabel, from, to, chkLock.isSelected(), actor));
            }, res -> {
                dlg.close();
                AppDialogService.success(tblJournals, "Fiscal Year Closed",
                        "Closing Voucher: " + res.entryNumber() + "\n" + res.message());
                loadLedgerDataAsync();
            }, err -> {
                btnExecute.setDisable(false);
                String msg = err != null && err.getMessage() != null ? err.getMessage() : "Unable to close fiscal year.";
                String cleanMsg = msg.replaceAll("(?s)\\n\\nTip: Reload the record.*", "").trim();
                boolean isBusinessNotice = cleanMsg.contains("No Revenue or Expense movements")
                        || cleanMsg.contains("already closed")
                        || cleanMsg.contains("BUSINESS_CONFLICT")
                        || msg.contains("Tip: Reload the record");
                if (isBusinessNotice) {
                    AppDialogService.warning(pane, "Cannot Close Fiscal Year", cleanMsg);
                } else {
                    AppDialogService.error(pane, "Year-End Close Failed", cleanMsg);
                }
            });
        });

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox dock = new HBox(10, btnExecute, sp, btnCancel);
        dock.getStyleClass().add("erp-inspection-action-dock");

        Region bodySpacer = new Region();
        VBox.setVgrow(bodySpacer, Priority.ALWAYS);
        VBox body = new VBox(14, warn, grid, bodySpacer, dock);
        body.setPadding(new Insets(14, 20, 14, 20));
        body.setFillWidth(true);
        VBox.setVgrow(body, Priority.ALWAYS);
        VBox root = new VBox(0, hero, body);
        root.setFillWidth(true);
        root.getStyleClass().addAll("dse-dialog-shell", "dse-dialog-shell-workspace", "comm-inspection-shell");
        pane.setContent(root);
        pane.getButtonTypes().add(ButtonType.CLOSE);
        dlg.setOnShowing(ev -> {
            Node bb = pane.lookup(".button-bar");
            if (bb != null) { bb.setVisible(false); bb.setManaged(false); }
        });
        dlg.showAndWait();
    }

    @FXML
    public void applyPeriodFilter() {
        if (cmbPeriodPreset != null) {
            cmbPeriodPreset.setValue("Custom Range");
        }
        loadLedgerDataAsync();
    }

    @FXML
    public void clearPeriodFilter() {
        if (dpFromDate != null) dpFromDate.setValue(null);
        if (dpToDate != null) dpToDate.setValue(null);
        if (cmbPeriodPreset != null) cmbPeriodPreset.setValue("All Time");
        loadLedgerDataAsync();
    }

    @FXML
    public void exportCsv() {
        PermissionService.require("GENERAL_LEDGER.EXPORT", "export general ledger CSV");
        File file = ExportFeedbackManager.resolveWorkspaceExportFile("GeneralLedger", "General_Ledger_Journals", "csv");
        if (!ExportFeedbackManager.checkFileWriteable(tblJournals, file)) return;

        try (java.io.PrintWriter out = new java.io.PrintWriter(file, java.nio.charset.StandardCharsets.UTF_8)) {
            out.println("Entry #,Date,Voucher Type,Reference,Narration,Debit,Credit,Status");
            for (JournalRow row : filteredJournalRows) {
                out.printf("%s,%s,%s,%s,%s,%.2f,%.2f,%s%n",
                        csvEscape(row.entryNumber()),
                        csvEscape(row.entryDate()),
                        csvEscape(row.entryType()),
                        csvEscape(row.referenceNo()),
                        csvEscape(row.narration()),
                        parseMoney(row.totalDebit()).doubleValue(),
                        parseMoney(row.totalCredit()).doubleValue(),
                        csvEscape(row.status()));
            }
            ExportFeedbackManager.notifyExportSuccess(tblJournals, file, "General Ledger CSV Export");
        } catch (Exception e) {
            AppDialogService.error(tblJournals, "Export failed", "Could not write CSV file", e.getMessage());
        }
    }

    private static String csvEscape(String value) {
        String s = value == null ? "" : value;
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    @FXML
    public void exportStatement() {
        PermissionService.require("GENERAL_LEDGER.EXPORT", "export financial statements");
        File file = ExportFeedbackManager.resolveWorkspaceExportFile("GeneralLedger", "Financial_Statement_Pack", "xlsx");
        if (!ExportFeedbackManager.checkFileWriteable(tblJournals, file)) return;

        try (Workbook workbook = new XSSFWorkbook()) {
            // Sheet 1: General Ledger
            Sheet s1 = workbook.createSheet("General Ledger");
            Row r0 = s1.createRow(0);
            String[] headers1 = {"Entry #", "Date", "Voucher Type", "Reference", "Narration", "Debit (₹)", "Credit (₹)", "Status"};
            for (int i = 0; i < headers1.length; i++) r0.createCell(i).setCellValue(headers1[i]);
            int rowIdx = 1;
            for (JournalRow row : filteredJournalRows) {
                Row r = s1.createRow(rowIdx++);
                r.createCell(0).setCellValue(safeStr(row.entryNumber()));
                r.createCell(1).setCellValue(safeStr(row.entryDate()));
                r.createCell(2).setCellValue(safeStr(row.entryType()));
                r.createCell(3).setCellValue(safeStr(row.referenceNo()));
                r.createCell(4).setCellValue(safeStr(row.narration()));
                r.createCell(5).setCellValue(parseMoney(row.totalDebit()).doubleValue());
                r.createCell(6).setCellValue(parseMoney(row.totalCredit()).doubleValue());
                r.createCell(7).setCellValue(safeStr(row.status()));
            }

            // Sheet 2: Chart of Accounts
            Sheet s2 = workbook.createSheet("Chart of Accounts");
            Row r02 = s2.createRow(0);
            String[] headers2 = {"Account Code", "Account Name", "Category", "Subtype", "Opening Balance", "Current Balance", "Active"};
            for (int i = 0; i < headers2.length; i++) r02.createCell(i).setCellValue(headers2[i]);
            rowIdx = 1;
            for (AccountRow acc : filteredAccountRows) {
                Row r = s2.createRow(rowIdx++);
                r.createCell(0).setCellValue(safeStr(acc.code()));
                r.createCell(1).setCellValue(safeStr(acc.name()));
                r.createCell(2).setCellValue(safeStr(acc.type()));
                r.createCell(3).setCellValue(safeStr(acc.subtype()));
                r.createCell(4).setCellValue(parseMoney(acc.opening()).doubleValue());
                r.createCell(5).setCellValue(parseMoney(acc.current()).doubleValue());
                r.createCell(6).setCellValue(safeStr(acc.active()));
            }

            // Sheet 3: Trial Balance with Grand Total SUM formulas
            Sheet s3 = workbook.createSheet("Trial Balance");
            Row r03 = s3.createRow(0);
            String[] headers3 = {"Account Code", "Account Name", "Type", "Opening (₹)", "Debit (₹)", "Credit (₹)", "Closing (₹)"};
            for (int i = 0; i < headers3.length; i++) r03.createCell(i).setCellValue(headers3[i]);
            rowIdx = 1;
            for (TbRow tb : filteredTbRows) {
                Row r = s3.createRow(rowIdx++);
                r.createCell(0).setCellValue(safeStr(tb.code()));
                r.createCell(1).setCellValue(safeStr(tb.name()));
                r.createCell(2).setCellValue(safeStr(tb.type()));
                r.createCell(3).setCellValue(parseMoney(tb.opening()).doubleValue());
                r.createCell(4).setCellValue(parseMoney(tb.debit()).doubleValue());
                r.createCell(5).setCellValue(parseMoney(tb.credit()).doubleValue());
                r.createCell(6).setCellValue(parseMoney(tb.closing()).doubleValue());
            }
            if (rowIdx > 1) {
                Row tot = s3.createRow(rowIdx);
                tot.createCell(1).setCellValue("GRAND TOTAL");
                tot.createCell(3).setCellFormula("SUM(D2:D" + rowIdx + ")");
                tot.createCell(4).setCellFormula("SUM(E2:E" + rowIdx + ")");
                tot.createCell(5).setCellFormula("SUM(F2:F" + rowIdx + ")");
                tot.createCell(6).setCellFormula("SUM(G2:G" + rowIdx + ")");
            }

            // Sheet 4: Profit & Loss
            Sheet s4 = workbook.createSheet("Profit & Loss");
            Row r04 = s4.createRow(0);
            String[] headers4 = {"Section", "Account Code", "Account Name", "Subtype", "Amount (₹)"};
            for (int i = 0; i < headers4.length; i++) r04.createCell(i).setCellValue(headers4[i]);
            rowIdx = 1;
            for (PlRow pl : filteredPlRows) {
                Row r = s4.createRow(rowIdx++);
                r.createCell(0).setCellValue(safeStr(pl.section()));
                r.createCell(1).setCellValue(safeStr(pl.code()));
                r.createCell(2).setCellValue(safeStr(pl.name()));
                r.createCell(3).setCellValue(safeStr(pl.subtype()));
                r.createCell(4).setCellValue(parseMoney(pl.amount()).doubleValue());
            }
            if (latestPl != null) {
                rowIdx++;
                Row sr1 = s4.createRow(rowIdx++);
                sr1.createCell(1).setCellValue("Total Revenue");
                sr1.createCell(4).setCellValue(nz(latestPl.totalRevenue()).doubleValue());
                Row sr2 = s4.createRow(rowIdx++);
                sr2.createCell(1).setCellValue("Gross Profit");
                sr2.createCell(4).setCellValue(nz(latestPl.grossProfit()).doubleValue());
                Row sr3 = s4.createRow(rowIdx);
                sr3.createCell(1).setCellValue("Net Profit / (Loss)");
                sr3.createCell(4).setCellValue(nz(latestPl.netProfit()).doubleValue());
            }

            // Sheet 5: Balance Sheet
            Sheet s5 = workbook.createSheet("Balance Sheet");
            Row r05 = s5.createRow(0);
            String[] headers5 = {"Side", "Account Code", "Account Name", "Type / Subtype", "Balance (₹)"};
            for (int i = 0; i < headers5.length; i++) r05.createCell(i).setCellValue(headers5[i]);
            rowIdx = 1;
            for (BsRow a : bsAssetRows) {
                Row r = s5.createRow(rowIdx++);
                r.createCell(0).setCellValue("ASSET");
                r.createCell(1).setCellValue(safeStr(a.code()));
                r.createCell(2).setCellValue(safeStr(a.name()));
                r.createCell(3).setCellValue(safeStr(a.subtype()));
                r.createCell(4).setCellValue(parseMoney(a.balance()).doubleValue());
            }
            for (BsRow le : bsLeRows) {
                Row r = s5.createRow(rowIdx++);
                r.createCell(0).setCellValue("LIAB & EQUITY");
                r.createCell(1).setCellValue(safeStr(le.code()));
                r.createCell(2).setCellValue(safeStr(le.name()));
                r.createCell(3).setCellValue(safeStr(le.subtype()));
                r.createCell(4).setCellValue(parseMoney(le.balance()).doubleValue());
            }

            try (FileOutputStream fos = new FileOutputStream(file)) {
                workbook.write(fos);
            }

            ExportFeedbackManager.notifyExportSuccess(tblJournals, file, "Financial Statement 5-Sheet Workbook Export");
        } catch (Exception e) {
            AppDialogService.error(tblJournals, "Export failed", "Could not write Excel workbook", e.getMessage());
        }
    }

    @FXML
    public void exportPdfFinancialPack() {
        PermissionService.require("GENERAL_LEDGER.EXPORT", "export financial pack PDF");
        File file = ExportFeedbackManager.resolveWorkspaceExportFile("GeneralLedger", "Financial_Statement_Pack", "pdf");
        if (!ExportFeedbackManager.checkFileWriteable(tblJournals, file)) return;

        try (PDDocument doc = new PDDocument()) {
            PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            String company = BrandingService.companyName();
            String periodLabel = lblActivePeriodInfo != null ? lblActivePeriodInfo.getText() : "All Time";

            PdfPackWriter pdf = new PdfPackWriter(doc, bold, regular, company, periodLabel);
            pdf.heading("1. Profit & Loss Statement");
            pdf.line("Operating Revenue: " + (kpiTotalRevenue != null ? kpiTotalRevenue.getText() : "0.00")
                    + "   |   Total Expenses: " + (kpiTotalExpense != null ? kpiTotalExpense.getText() : "0.00")
                    + "   |   Net Profit: " + (kpiNetProfit != null ? kpiNetProfit.getText() : "0.00"), false);
            pdf.gap(6);
            pdf.tableHeader(String.format("%-16s %-8s %-38s %16s", "Section", "Code", "Account Head", "Amount (Rs.)"));
            for (PlRow row : filteredPlRows) {
                pdf.row(String.format("%-16s %-8s %-38s %16s",
                        trimTo(row.section(), 15), trimTo(row.code(), 7), trimTo(row.name(), 36), row.amount()),
                        String.format("%-16s %-8s %-38s %16s", "Section", "Code", "Account Head", "Amount (Rs.)"));
            }

            pdf.heading("2. Balance Sheet — Assets, Liabilities & Equity");
            pdf.line((lblBsTotalAssets != null ? lblBsTotalAssets.getText() : "") + "   |   "
                    + (lblBsTotalLiab != null ? lblBsTotalLiab.getText() : "") + "   |   "
                    + (lblBsTotalEquity != null ? lblBsTotalEquity.getText() : "") + "   |   "
                    + (lblBsTallyPill != null ? lblBsTallyPill.getText() : ""), true);
            pdf.gap(6);
            String bsColHdr = String.format("%-8s %-34s %-22s %16s", "Code", "Asset / Account Head", "Subtype", "Balance (Rs.)");
            pdf.tableHeader(bsColHdr);
            for (BsRow a : bsAssetRows) {
                pdf.row(String.format("%-8s %-34s %-22s %16s",
                        trimTo(a.code(), 7), trimTo(a.name(), 32), trimTo(a.subtype(), 20), a.balance()), bsColHdr);
            }
            pdf.gap(8);
            String leColHdr = String.format("%-8s %-34s %-22s %16s", "Code", "Liability / Equity Head", "Category", "Balance (Rs.)");
            pdf.tableHeader(leColHdr);
            for (BsRow le : bsLeRows) {
                pdf.row(String.format("%-8s %-34s %-22s %16s",
                        trimTo(le.code(), 7), trimTo(le.name(), 32), trimTo(le.subtype(), 20), le.balance()), leColHdr);
            }

            pdf.newPage("3. Trial Balance — " + periodLabel);
            String tbColHdr = String.format("%-8s %-30s %-10s %14s %14s %14s", "Code", "Account Head", "Type", "Debit", "Credit", "Closing");
            pdf.tableHeader(tbColHdr);
            for (TbRow tb : filteredTbRows) {
                pdf.row(String.format("%-8s %-30s %-10s %14s %14s %14s",
                        trimTo(tb.code(), 7), trimTo(tb.name(), 28), trimTo(tb.type(), 9),
                        tb.debit(), tb.credit(), tb.closing()), tbColHdr);
            }
            pdf.gap(8);
            pdf.line((lblTbTotalDebit != null ? lblTbTotalDebit.getText() : "") + "   |   "
                    + (lblTbTotalCredit != null ? lblTbTotalCredit.getText() : "") + "   |   "
                    + (lblTbBalancePill != null ? lblTbBalancePill.getText() : ""), true);
            pdf.close();

            doc.save(file);
            ExportFeedbackManager.notifyExportSuccess(tblJournals, file, "Financial Statement Pack PDF");
        } catch (Exception e) {
            AppDialogService.error(tblJournals, "PDF Export Failed", "Could not generate Financial Statement PDF", e.getMessage());
        }
    }

    private final class PdfPackWriter {
        private final PDDocument doc;
        private final PDType1Font bold;
        private final PDType1Font regular;
        private final String company;
        private final String periodLabel;
        private PDPageContentStream cs;
        private float y;
        private int pageNum = 0;

        PdfPackWriter(PDDocument doc, PDType1Font bold, PDType1Font regular, String company, String periodLabel) throws java.io.IOException {
            this.doc = doc;
            this.bold = bold;
            this.regular = regular;
            this.company = company;
            this.periodLabel = periodLabel;
            startPage(company + " — Financial Statement Pack", true);
        }

        private void startPage(String banner, boolean showPeriodMeta) throws java.io.IOException {
            if (cs != null) cs.close();
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            cs = new PDPageContentStream(doc, page);
            pageNum++;
            y = 800f;
            cs.beginText(); cs.setFont(bold, pageNum == 1 ? 14 : 11); cs.newLineAtOffset(42, y);
            cs.showText(sanitizePdf(banner + (pageNum > 1 ? " (Page " + pageNum + ")" : ""))); cs.endText();
            y -= 17;
            if (showPeriodMeta) {
                cs.beginText(); cs.setFont(regular, 9.5f); cs.newLineAtOffset(42, y);
                cs.showText(sanitizePdf(periodLabel + "   |   Generated: " + BusinessClock.formatDate(BusinessClock.today())));
                cs.endText();
                y -= 20;
            }
        }

        void newPage(String title) throws java.io.IOException {
            startPage(title, false);
        }

        void heading(String title) throws java.io.IOException {
            if (y < 110) startPage(company + " — Financial Statement Pack (Cont.)", false);
            y -= 8;
            cs.beginText(); cs.setFont(bold, 11.5f); cs.newLineAtOffset(42, y);
            cs.showText(sanitizePdf(title)); cs.endText();
            y -= 16;
        }

        void tableHeader(String hdr) throws java.io.IOException {
            if (y < 85) startPage(company + " — Financial Statement Pack (Cont.)", false);
            cs.beginText(); cs.setFont(bold, 8.5f); cs.newLineAtOffset(42, y);
            cs.showText(sanitizePdf(hdr)); cs.endText();
            y -= 13;
        }

        void line(String text, boolean useBold) throws java.io.IOException {
            if (y < 65) startPage(company + " — Financial Statement Pack (Cont.)", false);
            cs.beginText(); cs.setFont(useBold ? bold : regular, 9.2f); cs.newLineAtOffset(42, y);
            cs.showText(sanitizePdf(text)); cs.endText();
            y -= 14;
        }

        void row(String rowText, String repeatColHdr) throws java.io.IOException {
            if (y < 60) {
                startPage(company + " — Financial Statement Pack (Cont.)", false);
                tableHeader(repeatColHdr);
            }
            cs.beginText(); cs.setFont(regular, 8.5f); cs.newLineAtOffset(42, y);
            cs.showText(sanitizePdf(rowText)); cs.endText();
            y -= 12.5f;
        }

        void gap(float pts) {
            y -= pts;
        }

        void close() throws java.io.IOException {
            if (cs != null) cs.close();
        }
    }

    @FXML
    public void filterJournals() {
        journalCurrentPage = 0;
        loadJournalsPageAsync();
    }

    private static boolean hasAnyAccountPrefix(JournalRow row, String... codes) {
        if (row.lines() != null && !row.lines().isEmpty()) {
            for (GeneralLedgerApiClient.JournalLineDto l : row.lines()) {
                String c = safeStr(l.accountCode());
                for (String target : codes) {
                    if (c.startsWith(target)) return true;
                }
            }
        }
        String heads = (safeStr(row.drHead()) + " " + safeStr(row.crHead()));
        for (String target : codes) {
            if (heads.contains(target)) return true;
        }
        return false;
    }

    private static String formatMoneyNullable(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) == 0) return "—";
        return NumberFormat.getCurrencyInstance(Locale.of("en", "IN")).format(amount.doubleValue()).replace("₹", "₹ ");
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private static BigDecimal parseMoney(String formatted) {
        if (formatted == null || formatted.isBlank()) return BigDecimal.ZERO;
        String cleaned = formatted.replaceAll("[^0-9.\\-]", "");
        if (cleaned.isBlank() || "-".equals(cleaned)) return BigDecimal.ZERO;
        try { return new BigDecimal(cleaned); } catch (Exception ignored) { return BigDecimal.ZERO; }
    }

    private static String safeStr(String s) {
        return s != null ? s : "";
    }

    private static String sanitizePdf(String s) {
        if (s == null) return "";
        return s.replace("₹", "Rs.").replace("→", "->").replace("·", "-").replaceAll("[^\\x20-\\x7E]", " ");
    }

    private static String trimTo(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 2)) + "..";
    }

    private static <T> TableCell<T, String> styledTableCell(String styleClass, Pos alignment) {
        return new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(item);
                    setAlignment(alignment);
                    getStyleClass().remove(styleClass);
                    getStyleClass().add(styleClass);
                }
            }
        };
    }

    @FXML
    public void filterAccounts() {
        String q = txtAccountSearch != null && txtAccountSearch.getText() != null ? txtAccountSearch.getText().trim().toLowerCase(Locale.ROOT) : "";
        String catFilter = cmbAccountFilter != null && cmbAccountFilter.getValue() != null ? cmbAccountFilter.getValue() : "All Categories";

        filteredAccountRows.setAll(allAccountRows.stream().filter(row -> {
            if (!catFilter.equals("All Categories") && !row.type().equalsIgnoreCase(catFilter)) return false;
            if (q.isBlank()) return true;
            String hay = (row.code() + " " + row.name() + " " + row.type() + " " + row.subtype()).toLowerCase(Locale.ROOT);
            return hay.contains(q);
        }).toList());
    }

    @FXML
    public void filterTrialBalance() {
        String q = txtTbSearch != null && txtTbSearch.getText() != null ? txtTbSearch.getText().trim().toLowerCase(Locale.ROOT) : "";

        filteredTbRows.setAll(allTbRows.stream().filter(row -> {
            if (q.isBlank()) return true;
            String hay = (row.code() + " " + row.name() + " " + row.type()).toLowerCase(Locale.ROOT);
            return hay.contains(q);
        }).toList());
        updateTrialBalanceTotals();
    }

    @FXML
    public void filterProfitLoss() {
        String q = txtPlSearch != null && txtPlSearch.getText() != null ? txtPlSearch.getText().trim().toLowerCase(Locale.ROOT) : "";
        filteredPlRows.setAll(allPlRows.stream().filter(row -> {
            if (q.isBlank()) return true;
            String hay = (row.section() + " " + row.code() + " " + row.name() + " " + row.subtype()).toLowerCase(Locale.ROOT);
            return hay.contains(q);
        }).toList());
    }

    @FXML
    public void refreshJournals() {
        loadLedgerDataAsync();
    }

    public record JournalRow(
            Long id,
            String entryNumber,
            String entryDate,
            String entryType,
            String referenceNo,
            String narration,
            String totalDebit,
            String totalCredit,
            String status,
            String drHead,
            String crHead,
            String drAmount,
            String crAmount,
            double rawAmount,
            List<GeneralLedgerApiClient.JournalLineDto> lines
    ) {
        public JournalRow(
                Long id,
                String entryNumber,
                String entryDate,
                String entryType,
                String referenceNo,
                String narration,
                String totalDebit,
                String totalCredit,
                String status,
                String drHead,
                String crHead,
                String drAmount,
                String crAmount,
                double rawAmount
        ) {
            this(id, entryNumber, entryDate, entryType, referenceNo, narration, totalDebit, totalCredit, status, drHead, crHead, drAmount, crAmount, rawAmount, List.of());
        }

        public JournalRow(
                String entryNumber,
                String entryDate,
                String entryType,
                String referenceNo,
                String narration,
                String totalDebit,
                String totalCredit,
                String status,
                String drHead,
                String crHead,
                String drAmount,
                String crAmount,
                double rawAmount
        ) {
            this(null, entryNumber, entryDate, entryType, referenceNo, narration, totalDebit, totalCredit, status, drHead, crHead, drAmount, crAmount, rawAmount, List.of());
        }
    }

    public record AccountRow(
            String code,
            String name,
            String type,
            String subtype,
            String opening,
            String current,
            String active
    ) {}

    public record TbRow(
            String code,
            String name,
            String type,
            String opening,
            String debit,
            String credit,
            String closing
    ) {}

    public record PlRow(
            String section,
            String code,
            String name,
            String subtype,
            String amount
    ) {}

    public record BsRow(
            String code,
            String name,
            String subtype,
            String balance
    ) {}

    private record LedgerResult(
            String assets,
            String liabilities,
            String revenue,
            String expenses,
            String netProfit,
            List<JournalRow> journalRows,
            List<AccountRow> accountRows,
            List<TbRow> tbRows,
            List<GeneralLedgerApiClient.AccountDto> rawAccounts,
            GeneralLedgerApiClient.ProfitAndLossResponse plResp,
            GeneralLedgerApiClient.BalanceSheetResponse bsResp,
            GeneralLedgerApiClient.PeriodLockDto lockDto,
            long journalTotalElements,
            int journalTotalPages
    ) {}
}
