package org.example.bank;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import org.example.api.bank.BankStatementApiClient;
import org.example.util.*;

import java.util.*;

/**
 * Bank Statement Smart Narration Auto-Rules Manager Modal (Option 2).
 * 95% responsive, Light & Dark theme parity, zero inline color leaks,
 * iPhone-style ios-toggle-switch controls, live dry-run preview, and one-click rule execution.
 */
public final class BankReconciliationRulesDialog {

    private BankReconciliationRulesDialog() {}

    public static void show(
            Node owner,
            BankStatementApiClient api,
            Long activeBatchId,
            String defaultBankAccount,
            List<String> expenseCategories,
            List<String> paymentModes,
            String performedBy,
            Runnable onBatchChanged) {
        show(owner, api, activeBatchId, defaultBankAccount, expenseCategories, paymentModes, List.of(), performedBy, onBatchChanged);
    }

    public static void show(
            Node owner,
            BankStatementApiClient api,
            Long activeBatchId,
            String defaultBankAccount,
            List<String> expenseCategories,
            List<String> paymentModes,
            List<String> bankAccounts,
            String performedBy,
            Runnable onBatchChanged) {
        OwnedDialog<ButtonType> dialog = new OwnedDialog<>(owner);
        dialog.setTitle("Bank Statement Smart Reconciliation Rules");
        dialog.setResizable(true);

        DialogPane pane = dialog.getDialogPane();
        pane.getStyleClass().addAll("approved-dialog", "erp-document-inspection-dialog", "erp-table-workspace-dialog", "erp-wide-workspace-90", "erp-ui-standard");
        pane.getButtonTypes().add(ButtonType.CLOSE);
        pane.getProperties().put("dse.dialog.wide90", Boolean.TRUE);
        PopupTableWorkspace.applyWideWorkspace90(dialog, owner);
        dialog.setOnShowing(ev -> {
            Node bar = pane.lookup(".button-bar");
            if (bar != null) {
                bar.setVisible(false);
                bar.setManaged(false);
            }
        });

        VBox root = new VBox(12);
        root.setPadding(new Insets(16));
        root.getStyleClass().addAll("approved-card", "erp-ui-standard");

        // Header
        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);
        StackPane icon = new StackPane(IconFactory.icon("reconcile", 22));
        icon.getStyleClass().add("erp-page-header-icon");
        VBox titleBox = new VBox(2);
        Label lblTitle = new Label("Bank Statement Smart Narration Rules");
        lblTitle.getStyleClass().add("screen-title");
        Label lblSub = new Label("Automate recurring narrations (rent, UPI handles, payment gateways, bank charges) with Suggest or Auto-Apply rules.");
        lblSub.getStyleClass().add("screen-subtitle");
        titleBox.getChildren().addAll(lblTitle, lblSub);
        header.getChildren().addAll(icon, titleBox);

        // Colorized KPI Row (Rule 6)
        Label lblTotalVal = new Label("0");
        lblTotalVal.getStyleClass().add("metric-value");
        Label lblActiveVal = new Label("0");
        lblActiveVal.getStyleClass().add("metric-value");
        Label lblHitsVal = new Label("0");
        lblHitsVal.getStyleClass().add("metric-value");
        HBox kpiRow = new HBox(12,
                buildKpi("Total Rules", "Configured patterns", lblTotalVal, "settings", "kpi-blue"),
                buildKpi("Active Rules", "Enabled on import & run", lblActiveVal, "complete", "kpi-green"),
                buildKpi("Transactions Matched", "Cumulative rows automated", lblHitsVal, "reconcile", "kpi-purple")
        );
        kpiRow.getStyleClass().add("standard-kpi-row");

        // Search & Action Toolbar
        TextField txtSearch = new TextField();
        txtSearch.setPromptText("Search rule name, narration pattern, category...");
        txtSearch.getStyleClass().add("approved-input");
        HBox.setHgrow(txtSearch, Priority.ALWAYS);

        Button btnNew = new Button("New Rule");
        btnNew.setMinWidth(Region.USE_PREF_SIZE);
        btnNew.getStyleClass().addAll("approved-button", "approved-primary-button");
        btnNew.setGraphic(IconFactory.compactIcon("add", 14));

        Button btnDiscover = new Button("Discover from History");
        btnDiscover.setMinWidth(Region.USE_PREF_SIZE);
        btnDiscover.getStyleClass().addAll("approved-button", "approved-secondary-button");
        btnDiscover.setGraphic(IconFactory.compactIcon("search", 14));

        Button btnTestAll = new Button("Preview on Statement");
        btnTestAll.setMinWidth(Region.USE_PREF_SIZE);
        btnTestAll.getStyleClass().addAll("approved-button", "approved-secondary-button");
        btnTestAll.setGraphic(IconFactory.compactIcon("view", 14));
        btnTestAll.setDisable(activeBatchId == null || activeBatchId <= 0);

        Button btnRunNow = new Button("Run Active Rules Now");
        btnRunNow.setMinWidth(Region.USE_PREF_SIZE);
        btnRunNow.getStyleClass().addAll("approved-button", "approved-primary-button");
        btnRunNow.setGraphic(IconFactory.compactIcon("refresh", 14));
        btnRunNow.setDisable(activeBatchId == null || activeBatchId <= 0);

        HBox toolbar = new HBox(8, txtSearch, btnNew, btnDiscover, btnTestAll, btnRunNow);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        // Rules Table
        TableView<BankStatementApiClient.RuleDto> table = new TableView<>();
        table.getStyleClass().addAll("approved-table", "erp-table-profile-responsive");
        VBox.setVgrow(table, Priority.ALWAYS);

        TableColumn<BankStatementApiClient.RuleDto, String> colPriority = new TableColumn<>("Priority");
        colPriority.setCellValueFactory(d -> new SimpleStringProperty(String.valueOf(d.getValue().priority())));
        colPriority.setMinWidth(75);
        colPriority.prefWidthProperty().bind(table.widthProperty().multiply(0.06));

        TableColumn<BankStatementApiClient.RuleDto, String> colName = new TableColumn<>("Rule Name");
        colName.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().ruleName()));
        colName.setMinWidth(140);
        colName.prefWidthProperty().bind(table.widthProperty().multiply(0.14));

        TableColumn<BankStatementApiClient.RuleDto, String> colPattern = new TableColumn<>("Match Condition");
        colPattern.setCellValueFactory(d -> new SimpleStringProperty(describeCondition(d.getValue())));
        colPattern.setMinWidth(260);
        colPattern.prefWidthProperty().bind(table.widthProperty().multiply(0.28));

        TableColumn<BankStatementApiClient.RuleDto, String> colAction = new TableColumn<>("Target Action");
        colAction.setCellValueFactory(d -> new SimpleStringProperty(describeTarget(d.getValue())));
        colAction.setMinWidth(200);
        colAction.prefWidthProperty().bind(table.widthProperty().multiply(0.22));

        TableColumn<BankStatementApiClient.RuleDto, String> colMode = new TableColumn<>("Mode");
        colMode.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().executionMode()));
        colMode.setMinWidth(110);
        colMode.prefWidthProperty().bind(table.widthProperty().multiply(0.10));

        TableColumn<BankStatementApiClient.RuleDto, String> colHits = new TableColumn<>("Hits");
        colHits.setCellValueFactory(d -> new SimpleStringProperty(String.valueOf(d.getValue().hitCount())));
        colHits.setMinWidth(60);
        colHits.prefWidthProperty().bind(table.widthProperty().multiply(0.05));

        TableColumn<BankStatementApiClient.RuleDto, String> colStatus = new TableColumn<>("Status");
        colStatus.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().active() ? "ACTIVE" : "INACTIVE"));
        colStatus.setCellFactory(c -> SemanticTableCells.status("status"));
        colStatus.setMinWidth(85);
        colStatus.prefWidthProperty().bind(table.widthProperty().multiply(0.07));

        TableColumn<BankStatementApiClient.RuleDto, Void> colOps = new TableColumn<>("Actions");
        colOps.setSortable(false);
        colOps.setMinWidth(110);
        colOps.prefWidthProperty().bind(table.widthProperty().multiply(0.08));

        table.getColumns().setAll(colPriority, colName, colPattern, colAction, colMode, colHits, colStatus, colOps);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        DynamicTableLayoutManager.install(table);
        TablePaginationBar<BankStatementApiClient.RuleDto> pagination = new TablePaginationBar<>(table, 25);

        List<BankStatementApiClient.RuleDto> masterRules = new ArrayList<>();
        Runnable applyFilter = () -> {
            String q = txtSearch.getText() == null ? "" : txtSearch.getText().trim().toLowerCase(Locale.ROOT);
            List<BankStatementApiClient.RuleDto> filtered = masterRules.stream().filter(r ->
                    q.isBlank()
                            || safe(r.ruleName()).toLowerCase(Locale.ROOT).contains(q)
                            || safe(r.matchPattern()).toLowerCase(Locale.ROOT).contains(q)
                            || safe(r.targetCategory()).toLowerCase(Locale.ROOT).contains(q)
                            || safe(r.actionType()).toLowerCase(Locale.ROOT).contains(q)
            ).toList();
            pagination.setAllItems(filtered);
        };
        txtSearch.textProperty().addListener((obs, o, n) -> applyFilter.run());

        Runnable[] reloadRef = new Runnable[1];
        reloadRef[0] = () -> UiTaskExecutor.submitLatest(
                "bank-rules-load",
                api::listRules,
                list -> {
                    masterRules.clear();
                    if (list != null) masterRules.addAll(list);
                    lblTotalVal.setText(String.valueOf(masterRules.size()));
                    lblActiveVal.setText(String.valueOf(masterRules.stream().filter(BankStatementApiClient.RuleDto::active).count()));
                    lblHitsVal.setText(String.valueOf(masterRules.stream().mapToInt(BankStatementApiClient.RuleDto::hitCount).sum()));
                    applyFilter.run();
                },
                err -> AppDialogService.error(pane, "Load Rules Failed", err.getMessage())
        );

        colOps.setCellFactory(col -> new TableCell<>() {
            final MenuButton btnActions = new MenuButton("Actions");
            {
                btnActions.getStyleClass().addAll("approved-button", "action-menu-button");
                IconFactory.decorateActionMenu(btnActions);
                btnActions.setMinWidth(Region.USE_PREF_SIZE);
            }

            @Override
            protected void updateItem(Void v, boolean empty) {
                super.updateItem(v, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                    return;
                }
                BankStatementApiClient.RuleDto r = getTableRow().getItem();
                btnActions.getItems().clear();

                MenuItem itemEdit = new MenuItem("Edit Rule");
                itemEdit.setGraphic(IconFactory.compactIcon("edit", 14));
                itemEdit.setOnAction(ev -> openRuleEditor(pane, api, activeBatchId, r, defaultBankAccount, expenseCategories, paymentModes, bankAccounts, reloadRef[0]));

                MenuItem itemPreview = new MenuItem("Preview on Statement");
                itemPreview.setGraphic(IconFactory.compactIcon("view", 14));
                itemPreview.setDisable(activeBatchId == null || activeBatchId <= 0);
                itemPreview.setOnAction(ev -> previewRulesOnBatch(pane, api, activeBatchId, toSaveRequest(r, r.active())));

                MenuItem itemRun = new MenuItem("Run This Rule Now");
                itemRun.setGraphic(IconFactory.compactIcon("refresh", 14));
                itemRun.setDisable(activeBatchId == null || activeBatchId <= 0);
                itemRun.setOnAction(ev -> runSingleRule(pane, api, activeBatchId, r, performedBy, () -> {
                    reloadRef[0].run();
                    if (onBatchChanged != null) onBatchChanged.run();
                }));

                String toggleLabel = r.active() ? "Deactivate Rule" : "Activate Rule";
                String toggleIcon = r.active() ? "cancel" : "complete";
                MenuItem itemToggle = new MenuItem(toggleLabel);
                itemToggle.setGraphic(IconFactory.compactIcon(toggleIcon, 14));
                itemToggle.setOnAction(ev -> toggleRuleActive(pane, api, r, reloadRef[0]));

                MenuItem itemDel = new MenuItem("Delete Rule");
                itemDel.setGraphic(IconFactory.compactIcon("delete", 14));
                itemDel.getStyleClass().add("menu-item-danger");
                itemDel.setOnAction(ev -> {
                    if (!AppDialogService.destructive(pane, "Delete Rule", "Delete rule '" + r.ruleName() + "'?",
                            "Future bank statements will no longer match this pattern. Past entries remain unaffected.", "Delete Rule")) {
                        return;
                    }
                    UiTaskExecutor.submitAction("bank-rule-delete-" + r.id(),
                            () -> api.deleteRule(r.id()),
                            ok -> reloadRef[0].run(),
                            err -> AppDialogService.error(pane, "Delete Failed", err.getMessage()));
                });

                btnActions.getItems().addAll(itemEdit, itemPreview, itemRun, new SeparatorMenuItem(), itemToggle, new SeparatorMenuItem(), itemDel);
                IconFactory.decorateActionMenu(btnActions);
                setGraphic(btnActions);
            }
        });

        table.setRowFactory(tv -> {
            TableRow<BankStatementApiClient.RuleDto> row = new TableRow<>();
            ContextMenu rowMenu = new ContextMenu();
            row.setOnMouseClicked(ev -> {
                if (ev.getClickCount() == 2 && !row.isEmpty()) {
                    BankStatementApiClient.RuleDto item = row.getItem();
                    if (item != null) {
                        openRuleEditor(pane, api, activeBatchId, item, defaultBankAccount, expenseCategories, paymentModes, bankAccounts, reloadRef[0]);
                    }
                }
            });

            row.itemProperty().addListener((obs, oldVal, item) -> {
                if (item == null) {
                    row.setContextMenu(null);
                    return;
                }
                rowMenu.getItems().clear();

                MenuItem itemEdit = new MenuItem("Edit Rule");
                itemEdit.setGraphic(IconFactory.compactIcon("edit", 14));
                itemEdit.setOnAction(ev -> openRuleEditor(pane, api, activeBatchId, item, defaultBankAccount, expenseCategories, paymentModes, bankAccounts, reloadRef[0]));

                MenuItem itemPreview = new MenuItem("Preview on Statement");
                itemPreview.setGraphic(IconFactory.compactIcon("view", 14));
                itemPreview.setDisable(activeBatchId == null || activeBatchId <= 0);
                itemPreview.setOnAction(ev -> previewRulesOnBatch(pane, api, activeBatchId, toSaveRequest(item, item.active())));

                MenuItem itemRun = new MenuItem("Run This Rule Now");
                itemRun.setGraphic(IconFactory.compactIcon("refresh", 14));
                itemRun.setDisable(activeBatchId == null || activeBatchId <= 0);
                itemRun.setOnAction(ev -> runSingleRule(pane, api, activeBatchId, item, performedBy, () -> {
                    reloadRef[0].run();
                    if (onBatchChanged != null) onBatchChanged.run();
                }));

                String toggleLabel = item.active() ? "Deactivate Rule" : "Activate Rule";
                String toggleIcon = item.active() ? "cancel" : "complete";
                MenuItem itemToggle = new MenuItem(toggleLabel);
                itemToggle.setGraphic(IconFactory.compactIcon(toggleIcon, 14));
                itemToggle.setOnAction(ev -> toggleRuleActive(pane, api, item, reloadRef[0]));

                MenuItem itemDel = new MenuItem("Delete Rule");
                itemDel.setGraphic(IconFactory.compactIcon("delete", 14));
                itemDel.getStyleClass().add("menu-item-danger");
                itemDel.setOnAction(ev -> {
                    if (!AppDialogService.destructive(pane, "Delete Rule", "Delete rule '" + item.ruleName() + "'?",
                            "Future bank statements will no longer match this pattern. Past entries remain unaffected.", "Delete Rule")) {
                        return;
                    }
                    UiTaskExecutor.submitAction("bank-rule-delete-" + item.id(),
                            () -> api.deleteRule(item.id()),
                            ok -> reloadRef[0].run(),
                            err -> AppDialogService.error(pane, "Delete Failed", err.getMessage()));
                });

                rowMenu.getItems().addAll(itemEdit, itemPreview, itemRun, new SeparatorMenuItem(), itemToggle, new SeparatorMenuItem(), itemDel);
                IconFactory.decorateActionMenu(rowMenu);
                row.setContextMenu(rowMenu);
            });
            return row;
        });

        btnNew.setOnAction(ev -> openRuleEditor(pane, api, activeBatchId, null, defaultBankAccount, expenseCategories, paymentModes, bankAccounts, reloadRef[0]));
        btnDiscover.setOnAction(ev -> openDiscoveryDialog(pane, api, defaultBankAccount, paymentModes, reloadRef[0]));
        btnTestAll.setOnAction(ev -> previewRulesOnBatch(pane, api, activeBatchId, null));
        btnRunNow.setOnAction(ev -> runRulesWithConfirmation(pane, api, activeBatchId, performedBy, () -> {
            reloadRef[0].run();
            if (onBatchChanged != null) onBatchChanged.run();
        }));

        HBox footer = new HBox(10);
        footer.setAlignment(Pos.CENTER_RIGHT);
        Button btnClose = new Button("Close");
        btnClose.setMinWidth(Region.USE_PREF_SIZE);
        btnClose.getStyleClass().addAll("approved-button", "approved-secondary-button");
        btnClose.setGraphic(IconFactory.compactIcon("cancel", 14));
        btnClose.setOnAction(ev -> dialog.close());
        footer.getChildren().add(btnClose);

        root.getChildren().addAll(header, kpiRow, toolbar, table, pagination, footer);
        pane.setContent(root);
        ProfessionalUiEnhancer.enhance(root);

        dialog.setOnShown(ev -> {
            if (pane.getScene() != null && pane.getScene().getWindow() instanceof Stage stage) {
                stage.getProperties().put("dse.dialog.wide90", Boolean.TRUE);
                WindowUtilsFx.fitDialogToOwnerScreen(stage, owner);
            }
            reloadRef[0].run();
        });

        dialog.showAndWait();
    }

    public static void runRulesWithConfirmation(
            Node owner,
            BankStatementApiClient api,
            Long batchId,
            String performedBy,
            Runnable onDone) {
        if (batchId == null || batchId <= 0) {
            AppDialogService.info(owner, "No Statement", "Select or import a bank statement first.");
            return;
        }
        UiTaskExecutor.submitActionWithOverlay(
                owner,
                "Evaluating Smart Bank Rules...",
                "bank-rules-preview-" + batchId,
                () -> api.previewRules(batchId, null),
                preview -> {
                    if (!preview.enabled()) {
                        AppDialogService.warning(owner, "Smart Bank Rules OFF",
                                "Bank Statement Smart Auto-Rules are turned OFF in Settings -> Module Activation.");
                        return;
                    }
                    if (preview.matchedRows() == 0) {
                        AppDialogService.info(owner, "No Rule Matches",
                                "Checked " + preview.totalOpenRows() + " open transaction(s) — no active rule matched.");
                        return;
                    }
                    String msg = "Matched " + preview.matchedRows() + " of " + preview.totalOpenRows()
                            + " open transaction(s) (Total: ₹ " + String.format(Locale.ENGLISH, "%,.2f", preview.matchedTotalAmount()) + ").\n\n"
                            + "Proceed to apply active rules (Suggest / Auto-Apply)?";
                    if (!AppDialogService.confirm(owner, "Run Smart Bank Rules", "Apply Smart Rules?", msg)) {
                        return;
                    }
                    UiTaskExecutor.submitActionWithOverlay(
                            owner,
                            "Applying Smart Bank Rules...",
                            "bank-rules-apply-" + batchId,
                            () -> api.applyRules(batchId, performedBy),
                            res -> {
                                AppDialogService.success(owner, "Smart Rules Applied", res.message());
                                if (onDone != null) onDone.run();
                            },
                            err -> AppDialogService.error(owner, "Apply Rules Failed", err.getMessage())
                    );
                },
                err -> AppDialogService.error(owner, "Rule Evaluation Failed", err.getMessage())
        );
    }

    private static void previewRulesOnBatch(Node owner, BankStatementApiClient api, Long batchId, BankStatementApiClient.RuleSaveRequest candidate) {
        if (batchId == null || batchId <= 0) return;
        UiTaskExecutor.submitActionWithOverlay(
                owner,
                "Testing Rule Against Current Statement...",
                "bank-rules-test-" + batchId,
                () -> api.previewRules(batchId, candidate),
                preview -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append("Open Rows Checked: ").append(preview.totalOpenRows()).append("\n");
                    sb.append("Matched Rows: ").append(preview.matchedRows()).append("\n");
                    sb.append("Matched Total: ₹ ").append(String.format(Locale.ENGLISH, "%,.2f", preview.matchedTotalAmount())).append("\n\n");
                    int shown = 0;
                    for (var m : preview.matches()) {
                        if (++shown > 8) {
                            sb.append("... and ").append(preview.matchedRows() - 8).append(" more row(s).");
                            break;
                        }
                        sb.append("• ").append(m.transactionDate()).append(" | ₹ ")
                                .append(String.format(Locale.ENGLISH, "%,.2f", m.amount()))
                                .append(" | ").append(m.matchedRuleName())
                                .append(" → ").append(m.targetSummary()).append("\n");
                    }
                    AppDialogService.info(owner, "Dry-Run Rule Preview", sb.toString());
                },
                err -> AppDialogService.error(owner, "Preview Failed", err.getMessage())
        );
    }

    private static void openRuleEditor(
            Node owner,
            BankStatementApiClient api,
            Long activeBatchId,
            BankStatementApiClient.RuleDto existing,
            String defaultBankAccount,
            List<String> categories,
            List<String> modes,
            List<String> bankAccounts,
            Runnable onSaved) {
        OwnedDialog<ButtonType> dlg = new OwnedDialog<>(owner);
        dlg.setTitle(existing == null ? "Create Bank Reconciliation Rule" : "Edit Rule — " + existing.ruleName());
        dlg.setHeaderText("Match recurring bank statement narrations automatically.");
        dlg.getDialogPane().getStyleClass().addAll("approved-dialog", "erp-ui-standard");

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new Insets(14));
        ColumnConstraints c0 = new ColumnConstraints(155);
        ColumnConstraints c1 = new ColumnConstraints(340, 400, Double.MAX_VALUE);
        c1.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(c0, c1);

        TextField txtName = new TextField(existing != null ? safe(existing.ruleName()) : "");
        txtName.setPromptText("e.g. Office Rent, Razorpay Payout, Bank Charges");
        txtName.getStyleClass().add("approved-input");

        ComboBox<String> cmbField = new ComboBox<>(FXCollections.observableArrayList("NARRATION", "REFERENCE"));
        cmbField.setValue(existing != null ? existing.matchField() : "NARRATION");
        cmbField.setMaxWidth(Double.MAX_VALUE);

        ComboBox<String> cmbOp = new ComboBox<>(FXCollections.observableArrayList("CONTAINS", "STARTS_WITH", "EXACT", "REGEX"));
        cmbOp.setValue(existing != null ? existing.matchOperator() : "CONTAINS");
        cmbOp.setMaxWidth(Double.MAX_VALUE);

        TextField txtPattern = new TextField(existing != null ? safe(existing.matchPattern()) : "");
        txtPattern.setPromptText("e.g. SHIVANAND, RAZORPAY, ^ACH.*POWER$");
        txtPattern.getStyleClass().add("approved-input");

        TextField txtMinAmount = new TextField(existing != null && existing.minAmount() != null ? String.valueOf(existing.minAmount()) : "");
        txtMinAmount.setPromptText("Min ₹ (optional)");
        txtMinAmount.getStyleClass().add("approved-input");
        TextField txtMaxAmount = new TextField(existing != null && existing.maxAmount() != null ? String.valueOf(existing.maxAmount()) : "");
        txtMaxAmount.setPromptText("Max ₹ (optional)");
        txtMaxAmount.getStyleClass().add("approved-input");
        HBox amountRangeBox = new HBox(8, txtMinAmount, txtMaxAmount);
        HBox.setHgrow(txtMinAmount, Priority.ALWAYS);
        HBox.setHgrow(txtMaxAmount, Priority.ALWAYS);

        ComboBox<String> cmbChannelFilter = new ComboBox<>(FXCollections.observableArrayList("ALL", "UPI", "NEFT", "IMPS", "RTGS", "CHQ", "ACH", "POS", "CHARGES"));
        cmbChannelFilter.setValue(existing != null && !safe(existing.channelFilter()).isBlank() ? existing.channelFilter() : "ALL");
        cmbChannelFilter.setMaxWidth(Double.MAX_VALUE);

        List<String> scopeAccounts = new ArrayList<>();
        scopeAccounts.add("ALL ACCOUNTS");
        if (bankAccounts != null) scopeAccounts.addAll(bankAccounts);
        ComboBox<String> cmbScopeAccount = new ComboBox<>(FXCollections.observableArrayList(scopeAccounts));
        cmbScopeAccount.setEditable(true);
        cmbScopeAccount.setValue(existing != null && !safe(existing.bankAccountFilter()).isBlank() ? existing.bankAccountFilter() : "ALL ACCOUNTS");
        cmbScopeAccount.setMaxWidth(Double.MAX_VALUE);

        ComboBox<String> cmbDir = new ComboBox<>(FXCollections.observableArrayList("DEBIT", "CREDIT", "BOTH"));
        cmbDir.setValue(existing != null ? existing.direction() : "DEBIT");
        cmbDir.setMaxWidth(Double.MAX_VALUE);

        ComboBox<String> cmbAction = new ComboBox<>(FXCollections.observableArrayList("EXPENSE", "BANK_ENTRY", "MATCH_PARTY_FIFO", "CONTRA", "IGNORE"));
        cmbAction.setValue(existing != null ? existing.actionType() : "EXPENSE");
        cmbAction.setMaxWidth(Double.MAX_VALUE);

        ComboBox<BankStatementApiClient.PartyOptionDto> cmbParty = new ComboBox<>();
        cmbParty.setPromptText("Select Customer / Supplier for FIFO settlement");
        cmbParty.setMaxWidth(Double.MAX_VALUE);

        ComboBox<String> cmbContraAccount = new ComboBox<>(FXCollections.observableArrayList(bankAccounts == null ? List.of() : bankAccounts));
        cmbContraAccount.setEditable(true);
        cmbContraAccount.setPromptText("Destination bank account for Contra");
        cmbContraAccount.setMaxWidth(Double.MAX_VALUE);
        if (existing != null && !safe(existing.targetContraAccount()).isBlank()) {
            cmbContraAccount.setValue(existing.targetContraAccount());
        }

        ComboBox<String> cmbCategory = new ComboBox<>(FXCollections.observableArrayList(categories == null ? List.of() : categories));
        cmbCategory.setEditable(true);
        cmbCategory.setMaxWidth(Double.MAX_VALUE);
        if (existing != null && existing.targetCategory() != null) cmbCategory.setValue(existing.targetCategory());

        ComboBox<String> cmbAccount = new ComboBox<>(FXCollections.observableArrayList(bankAccounts == null ? List.of() : bankAccounts));
        cmbAccount.setEditable(true);
        cmbAccount.setMaxWidth(Double.MAX_VALUE);
        if (existing != null && existing.targetAccountName() != null && !existing.targetAccountName().isBlank()) {
            cmbAccount.setValue(existing.targetAccountName());
        } else if (defaultBankAccount != null && !defaultBankAccount.isBlank()) {
            cmbAccount.setValue(defaultBankAccount);
        } else if (!cmbAccount.getItems().isEmpty()) {
            cmbAccount.getSelectionModel().selectFirst();
        }

        ComboBox<String> cmbPayMode = new ComboBox<>(FXCollections.observableArrayList(modes == null || modes.isEmpty() ? List.of("Bank Transfer", "UPI", "NEFT", "IMPS") : modes));
        cmbPayMode.setEditable(true);
        cmbPayMode.setMaxWidth(Double.MAX_VALUE);
        if (existing != null && existing.targetPaymentMode() != null) cmbPayMode.setValue(existing.targetPaymentMode());
        else if (!cmbPayMode.getItems().isEmpty()) cmbPayMode.getSelectionModel().selectFirst();

        ComboBox<String> cmbExecMode = new ComboBox<>(FXCollections.observableArrayList("SUGGEST", "AUTO_APPLY"));
        cmbExecMode.setValue(existing != null ? existing.executionMode() : "SUGGEST");
        cmbExecMode.setMaxWidth(Double.MAX_VALUE);

        TextField txtPriority = new TextField(String.valueOf(existing != null ? existing.priority() : 100));
        txtPriority.getStyleClass().add("approved-input");

        TextField txtNotes = new TextField(existing != null ? safe(existing.targetNotes()) : "");
        txtNotes.setPromptText("Optional audit note / remark");
        txtNotes.getStyleClass().add("approved-input");

        CheckBox chkActive = new CheckBox("Rule Active");
        chkActive.getStyleClass().addAll("approved-checkbox", "ios-toggle-switch");
        chkActive.setSelected(existing == null || existing.active());

        cmbCategory.disableProperty().bind(cmbAction.valueProperty().isNotEqualTo("EXPENSE"));
        cmbParty.disableProperty().bind(cmbAction.valueProperty().isNotEqualTo("MATCH_PARTY_FIFO"));
        cmbContraAccount.disableProperty().bind(cmbAction.valueProperty().isNotEqualTo("CONTRA"));

        Integer preselectedPartyId = existing != null ? existing.targetPartyId() : null;
        UiTaskExecutor.submitLatest("bank-rules-parties", api::listRuleParties, partyList -> {
            if (partyList != null) {
                cmbParty.getItems().setAll(partyList);
                if (preselectedPartyId != null) {
                    partyList.stream().filter(p -> p.id() == preselectedPartyId).findFirst().ifPresent(cmbParty::setValue);
                }
            }
        }, err -> { });

        int r = 0;
        grid.addRow(r++, new Label("Rule Name *"), txtName);
        grid.addRow(r++, new Label("Match Field"), cmbField);
        grid.addRow(r++, new Label("Match Operator"), cmbOp);
        grid.addRow(r++, new Label("Match Pattern *"), txtPattern);
        grid.addRow(r++, new Label("Amount Range (Min / Max)"), amountRangeBox);
        grid.addRow(r++, new Label("Channel Filter"), cmbChannelFilter);
        grid.addRow(r++, new Label("Bank Account Scope"), cmbScopeAccount);
        grid.addRow(r++, new Label("Direction"), cmbDir);
        grid.addRow(r++, new Label("Target Action"), cmbAction);
        grid.addRow(r++, new Label("Expense Category"), cmbCategory);
        grid.addRow(r++, new Label("Party (FIFO Action)"), cmbParty);
        grid.addRow(r++, new Label("Contra Destination Account"), cmbContraAccount);
        grid.addRow(r++, new Label("Posting Bank Account"), cmbAccount);
        grid.addRow(r++, new Label("Payment Mode"), cmbPayMode);
        grid.addRow(r++, new Label("Execution Mode"), cmbExecMode);
        grid.addRow(r++, new Label("Priority (Low=First)"), txtPriority);
        grid.addRow(r++, new Label("Default Note"), txtNotes);
        grid.addRow(r, new Label("Status"), chkActive);

        dlg.getDialogPane().setContent(grid);
        ButtonType btTest = new ButtonType("Test Pattern", ButtonBar.ButtonData.LEFT);
        ButtonType btSave = new ButtonType("Save Rule", ButtonBar.ButtonData.OK_DONE);
        dlg.getDialogPane().getButtonTypes().addAll(btTest, ButtonType.CANCEL, btSave);

        Node testNode = dlg.getDialogPane().lookupButton(btTest);
        if (testNode != null) {
            testNode.setDisable(activeBatchId == null || activeBatchId <= 0);
            testNode.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
                ev.consume();
                var req = buildRequest(txtName, cmbField, cmbOp, txtPattern, cmbDir, cmbAction, cmbCategory,
                        cmbAccount, cmbPayMode, txtNotes, cmbExecMode, txtPriority, chkActive,
                        txtMinAmount, txtMaxAmount, cmbChannelFilter, cmbScopeAccount, cmbParty, cmbContraAccount);
                previewRulesOnBatch(dlg.getDialogPane(), api, activeBatchId, req);
            });
        }

        Node saveNode = dlg.getDialogPane().lookupButton(btSave);
        if (saveNode != null) {
            saveNode.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
                if (safe(txtName.getText()).isBlank() || safe(txtPattern.getText()).isBlank()) {
                    ev.consume();
                    AppDialogService.warning(dlg.getDialogPane(), "Missing Fields", "Rule Name and Match Pattern are required.");
                } else if ("EXPENSE".equalsIgnoreCase(cmbAction.getValue()) && safe(cmbCategory.getEditor().getText()).isBlank() && safe(cmbCategory.getValue()).isBlank()) {
                    ev.consume();
                    AppDialogService.warning(dlg.getDialogPane(), "Category Required", "Select an Expense Category for EXPENSE rules.");
                } else if ("MATCH_PARTY_FIFO".equalsIgnoreCase(cmbAction.getValue()) && cmbParty.getValue() == null) {
                    ev.consume();
                    AppDialogService.warning(dlg.getDialogPane(), "Party Required", "Select a Customer / Supplier for Party FIFO Settlement.");
                } else if ("CONTRA".equalsIgnoreCase(cmbAction.getValue()) && safe(cmbContraAccount.getValue()).isBlank() && safe(cmbContraAccount.getEditor().getText()).isBlank()) {
                    ev.consume();
                    AppDialogService.warning(dlg.getDialogPane(), "Contra Account Required", "Select the destination Contra Bank Account.");
                }
            });
        }

        PopupTableWorkspace.prepareDialog(dlg, 760);
        dlg.showAndWait().filter(btSave::equals).ifPresent(ok -> {
            var req = buildRequest(txtName, cmbField, cmbOp, txtPattern, cmbDir, cmbAction, cmbCategory,
                    cmbAccount, cmbPayMode, txtNotes, cmbExecMode, txtPriority, chkActive,
                    txtMinAmount, txtMaxAmount, cmbChannelFilter, cmbScopeAccount, cmbParty, cmbContraAccount);
            UiTaskExecutor.submitAction("bank-rule-save",
                    () -> existing == null ? api.createRule(req) : api.updateRule(existing.id(), req),
                    saved -> {
                        if (onSaved != null) onSaved.run();
                    },
                    err -> AppDialogService.error(owner, "Save Rule Failed", err.getMessage()));
        });
    }

    private static void toggleRuleActive(Node owner, BankStatementApiClient api, BankStatementApiClient.RuleDto rule, Runnable onReload) {
        if (rule == null) return;
        boolean newActive = !rule.active();
        var req = toSaveRequest(rule, newActive);
        UiTaskExecutor.submitAction(
                "bank-rule-toggle-" + rule.id(),
                () -> api.updateRule(rule.id(), req),
                ok -> onReload.run(),
                err -> AppDialogService.error(owner, "Update Status Failed", err.getMessage())
        );
    }

    private static void runSingleRule(Node owner, BankStatementApiClient api, Long batchId, BankStatementApiClient.RuleDto rule, String performedBy, Runnable onDone) {
        if (batchId == null || batchId <= 0 || rule == null) return;
        var req = toSaveRequest(rule, true);
        UiTaskExecutor.submitActionWithOverlay(
                owner,
                "Previewing Rule '" + rule.ruleName() + "'...",
                "bank-rule-run-preview-" + rule.id(),
                () -> api.previewRules(batchId, req),
                preview -> {
                    if (preview.matchedRows() == 0) {
                        AppDialogService.info(owner, "No Matches", "Rule '" + rule.ruleName() + "' did not match any open transactions on this statement.");
                        return;
                    }
                    String msg = "Rule '" + rule.ruleName() + "' matched " + preview.matchedRows() + " open transaction(s) (Total: ₹ "
                            + String.format(Locale.ENGLISH, "%,.2f", preview.matchedTotalAmount()) + ").\n\nApply only this rule now?";
                    if (!AppDialogService.confirm(owner, "Run Rule", "Apply Rule '" + rule.ruleName() + "'?", msg)) {
                        return;
                    }
                    UiTaskExecutor.submitActionWithOverlay(
                            owner,
                            "Applying Rule '" + rule.ruleName() + "'...",
                            "bank-rule-single-apply-" + rule.id(),
                            () -> api.applySingleRule(batchId, rule.id(), performedBy),
                            res -> {
                                AppDialogService.success(owner, "Rule Applied", res.message());
                                if (onDone != null) onDone.run();
                            },
                            err -> AppDialogService.error(owner, "Apply Rule Failed", err.getMessage())
                    );
                },
                err -> AppDialogService.error(owner, "Preview Failed", err.getMessage())
        );
    }

    private static void openDiscoveryDialog(
            Node owner,
            BankStatementApiClient api,
            String defaultBankAccount,
            List<String> paymentModes,
            Runnable onRulesCreated) {
        UiTaskExecutor.submitActionWithOverlay(
                owner,
                "Analyzing Bank History for Recurring Patterns...",
                "bank-rules-discover",
                api::discoverRuleSuggestions,
                candidates -> {
                    if (candidates == null || candidates.isEmpty()) {
                        AppDialogService.info(owner, "No New Rule Suggestions",
                                "No recurring un-ruled bank narrations (>= 2 hits) were found in history.");
                        return;
                    }
                    OwnedDialog<ButtonType> dlg = new OwnedDialog<>(owner);
                    dlg.setTitle("Auto-Discovered Smart Rules");
                    dlg.setHeaderText("Found " + candidates.size() + " recurring narration pattern(s) from your past reconciliations.");
                    dlg.getDialogPane().getStyleClass().addAll("approved-dialog", "erp-wide-workspace-90", "erp-ui-standard");

                    record DiscoverRow(javafx.beans.property.BooleanProperty selected, BankStatementApiClient.RuleSuggestionCandidateDto dto) {}
                    List<DiscoverRow> rows = candidates.stream()
                            .map(c -> new DiscoverRow(new javafx.beans.property.SimpleBooleanProperty(true), c))
                            .toList();

                    TableView<DiscoverRow> tbl = new TableView<>(FXCollections.observableArrayList(rows));
                    tbl.getStyleClass().addAll("approved-table", "erp-table-profile-dialog");
                    tbl.setEditable(true);
                    tbl.setPrefHeight(360);

                    TableColumn<DiscoverRow, Boolean> cSel = new TableColumn<>("Select");
                    cSel.setCellValueFactory(d -> d.getValue().selected());
                    cSel.setCellFactory(javafx.scene.control.cell.CheckBoxTableCell.forTableColumn(cSel));
                    cSel.setEditable(true);
                    cSel.prefWidthProperty().bind(tbl.widthProperty().multiply(0.08));

                    TableColumn<DiscoverRow, String> cPat = new TableColumn<>("Narration Pattern");
                    cPat.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().dto().matchPattern()));
                    cPat.prefWidthProperty().bind(tbl.widthProperty().multiply(0.28));

                    TableColumn<DiscoverRow, String> cAct = new TableColumn<>("Action / Target");
                    cAct.setCellValueFactory(d -> new SimpleStringProperty(
                            d.getValue().dto().actionType() + " → "
                                    + (safe(d.getValue().dto().targetCategory()).isBlank()
                                    ? safe(d.getValue().dto().targetAccountName())
                                    : d.getValue().dto().targetCategory())));
                    cAct.prefWidthProperty().bind(tbl.widthProperty().multiply(0.32));

                    TableColumn<DiscoverRow, String> cHits = new TableColumn<>("History Hits");
                    cHits.setCellValueFactory(d -> new SimpleStringProperty(String.valueOf(d.getValue().dto().historyCount())));
                    cHits.prefWidthProperty().bind(tbl.widthProperty().multiply(0.14));

                    TableColumn<DiscoverRow, String> cAmt = new TableColumn<>("Historical Total");
                    cAmt.setCellValueFactory(d -> new SimpleStringProperty("₹ " + String.format(Locale.ENGLISH, "%,.2f", d.getValue().dto().totalAmount())));
                    cAmt.setCellFactory(c -> SemanticTableCells.moneyString("blue"));
                    cAmt.prefWidthProperty().bind(tbl.widthProperty().multiply(0.18));

                    tbl.getColumns().setAll(cSel, cPat, cAct, cHits, cAmt);
                    tbl.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
                    DynamicTableLayoutManager.install(tbl);

                    VBox box = new VBox(10, tbl);
                    box.setPadding(new Insets(10));
                    dlg.getDialogPane().setContent(box);
                    ButtonType btCreate = new ButtonType("Create Selected Rules", ButtonBar.ButtonData.OK_DONE);
                    dlg.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, btCreate);
                    PopupTableWorkspace.prepareDialog(dlg, 860);

                    dlg.showAndWait().filter(btCreate::equals).ifPresent(ok -> {
                        List<BankStatementApiClient.RuleSuggestionCandidateDto> picked = rows.stream()
                                .filter(r -> r.selected().get()).map(DiscoverRow::dto).toList();
                        if (picked.isEmpty()) return;
                        String defaultMode = (paymentModes != null && !paymentModes.isEmpty()) ? paymentModes.getFirst() : "Bank Transfer";
                        UiTaskExecutor.submitActionWithOverlay(
                                owner,
                                "Creating " + picked.size() + " Discovered Rule(s)...",
                                "bank-rules-discover-create",
                                () -> {
                                    int created = 0;
                                    for (var p : picked) {
                                        var req = new BankStatementApiClient.RuleSaveRequest(
                                                p.suggestedName(), "NARRATION", "CONTAINS", p.matchPattern(),
                                                p.direction(), p.actionType(), p.targetCategory(),
                                                safe(p.targetAccountName()).isBlank() ? defaultBankAccount : p.targetAccountName(),
                                                defaultMode, "Auto-discovered from bank history", "SUGGEST", 100, true);
                                        api.createRule(req);
                                        created++;
                                    }
                                    return created;
                                },
                                count -> {
                                    AppDialogService.success(owner, "Rules Created", "Created " + count + " smart bank rule(s).");
                                    if (onRulesCreated != null) onRulesCreated.run();
                                },
                                err -> AppDialogService.error(owner, "Rule Creation Failed", err.getMessage())
                        );
                    });
                },
                err -> AppDialogService.error(owner, "Discovery Failed", err.getMessage())
        );
    }

    private static BankStatementApiClient.RuleSaveRequest toSaveRequest(BankStatementApiClient.RuleDto r, boolean active) {
        return new BankStatementApiClient.RuleSaveRequest(
                r.ruleName(), r.matchField(), r.matchOperator(), r.matchPattern(), r.direction(),
                r.actionType(), r.targetCategory(), r.targetAccountName(), r.targetPaymentMode(),
                r.targetNotes(), r.executionMode(), r.priority(), active,
                r.minAmount(), r.maxAmount(), r.channelFilter(), r.bankAccountFilter(),
                r.targetPartyId(), r.targetContraAccount()
        );
    }

    private static BankStatementApiClient.RuleSaveRequest buildRequest(
            TextField txtName, ComboBox<String> cmbField, ComboBox<String> cmbOp, TextField txtPattern,
            ComboBox<String> cmbDir, ComboBox<String> cmbAction, ComboBox<String> cmbCategory,
            ComboBox<String> cmbAccount, ComboBox<String> cmbPayMode, TextField txtNotes,
            ComboBox<String> cmbExecMode, TextField txtPriority, CheckBox chkActive,
            TextField txtMinAmount, TextField txtMaxAmount, ComboBox<String> cmbChannelFilter,
            ComboBox<String> cmbScopeAccount, ComboBox<BankStatementApiClient.PartyOptionDto> cmbParty,
            ComboBox<String> cmbContraAccount) {
        int prio = 100;
        try { prio = Integer.parseInt(txtPriority.getText().trim()); } catch (Exception ignored) {}
        Double minAmt = parseOptionalDouble(txtMinAmount.getText());
        Double maxAmt = parseOptionalDouble(txtMaxAmount.getText());
        String cat = comboValue(cmbCategory);
        String mode = comboValue(cmbPayMode);
        String acct = comboValue(cmbAccount);
        String scopeAcct = comboValue(cmbScopeAccount);
        if ("ALL ACCOUNTS".equalsIgnoreCase(scopeAcct)) scopeAcct = null;
        String channelFilter = cmbChannelFilter != null ? cmbChannelFilter.getValue() : "ALL";
        if ("ALL".equalsIgnoreCase(channelFilter)) channelFilter = null;
        Integer partyId = cmbParty != null && cmbParty.getValue() != null ? cmbParty.getValue().id() : null;
        String contraAcct = comboValue(cmbContraAccount);
        return new BankStatementApiClient.RuleSaveRequest(
                txtName.getText().trim(), cmbField.getValue(), cmbOp.getValue(), txtPattern.getText().trim(),
                cmbDir.getValue(), cmbAction.getValue(), cat, safe(acct), mode,
                txtNotes.getText().trim(), cmbExecMode.getValue(), prio, chkActive.isSelected(),
                minAmt, maxAmt, channelFilter, scopeAcct, partyId, contraAcct
        );
    }

    private static String comboValue(ComboBox<String> cmb) {
        if (cmb == null) return "";
        String v = cmb.getValue();
        if (v != null && !v.isBlank()) return v.trim();
        return cmb.getEditor() != null && cmb.getEditor().getText() != null ? cmb.getEditor().getText().trim() : "";
    }

    private static Double parseOptionalDouble(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            double v = Double.parseDouble(raw.replace(",", "").replace("₹", "").trim());
            return v > 0 ? v : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String describeCondition(BankStatementApiClient.RuleDto r) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.matchField()).append(" ").append(r.matchOperator()).append(" \"").append(r.matchPattern()).append("\" (").append(r.direction()).append(")");
        if (r.minAmount() != null || r.maxAmount() != null) {
            sb.append(" • ₹").append(r.minAmount() != null ? String.format(Locale.ENGLISH, "%,.0f", r.minAmount()) : "0")
                    .append("–").append(r.maxAmount() != null ? "₹" + String.format(Locale.ENGLISH, "%,.0f", r.maxAmount()) : "∞");
        }
        if (!safe(r.channelFilter()).isBlank()) sb.append(" • ").append(r.channelFilter());
        if (!safe(r.bankAccountFilter()).isBlank()) sb.append(" • ").append(r.bankAccountFilter());
        return sb.toString();
    }

    /** Extracts a reusable narration keyword by stripping unique digits/prefixes like IMPS/UPI transaction IDs. */
    public static String suggestNarrationPattern(String rawDescription) {
        String s = safe(rawDescription).replaceAll("\\s+", " ").trim();
        if (s.isBlank()) return "";
        String[] parts = s.split("[-/|]+");
        String best = "";
        for (String p : parts) {
            String cleaned = p.trim();
            if (cleaned.length() < 4) continue;
            if (cleaned.matches("^[0-9X]+$")) continue;
            String upper = cleaned.toUpperCase(Locale.ROOT);
            if (Set.of("UPI", "IMPS", "NEFT", "RTGS", "ACH", "REV", "NACH", "POS").contains(upper)) continue;
            if (cleaned.length() > best.length()) best = cleaned;
        }
        String candidate = best.isBlank() ? s : best;
        return candidate.length() > 48 ? candidate.substring(0, 48).trim() : candidate;
    }

    private static HBox buildKpi(String title, String note, Label valLabel, String iconKey, String accentClass) {
        StackPane ic = new StackPane(IconFactory.compactIcon(iconKey, 18));
        ic.getStyleClass().add("standard-kpi-icon");
        Label lblT = new Label(title);
        lblT.getStyleClass().add("metric-label");
        Label lblN = new Label(note);
        lblN.getStyleClass().add("metric-note");
        VBox vb = new VBox(2, lblT, valLabel, lblN);
        HBox.setHgrow(vb, Priority.ALWAYS);
        HBox card = new HBox(10, ic, vb);
        card.setAlignment(Pos.CENTER_LEFT);
        card.getStyleClass().addAll("standard-kpi-card", accentClass);
        HBox.setHgrow(card, Priority.ALWAYS);
        return card;
    }

    private static String describeTarget(BankStatementApiClient.RuleDto r) {
        return switch (safe(r.actionType()).toUpperCase(Locale.ROOT)) {
            case "EXPENSE" -> "Expense → " + (safe(r.targetCategory()).isBlank() ? "Category" : r.targetCategory());
            case "BANK_ENTRY" -> "Bank Entry → " + (safe(r.targetAccountName()).isBlank() ? "Statement Account" : r.targetAccountName());
            case "CONTRA" -> "Contra → " + (safe(r.targetContraAccount()).isBlank() ? "Destination Account" : r.targetContraAccount());
            case "MATCH_PARTY_FIFO" -> "Auto-Settle Party FIFO" + (r.targetPartyId() != null ? " (#" + r.targetPartyId() + ")" : "");
            case "IGNORE" -> "Ignore";
            default -> safe(r.actionType());
        };
    }

    private static String safe(String s) { return s == null ? "" : s.trim(); }
}
