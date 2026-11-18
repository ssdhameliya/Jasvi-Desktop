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
        OwnedDialog<ButtonType> dialog = new OwnedDialog<>(owner);
        dialog.setTitle("Bank Statement Smart Reconciliation Rules");
        dialog.setResizable(true);

        DialogPane pane = dialog.getDialogPane();
        pane.getStyleClass().addAll("approved-dialog", "erp-document-inspection-dialog", "erp-ui-standard");
        pane.getButtonTypes().add(ButtonType.CLOSE);
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

        HBox toolbar = new HBox(8, txtSearch, btnNew, btnTestAll, btnRunNow);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        // Rules Table
        TableView<BankStatementApiClient.RuleDto> table = new TableView<>();
        table.getStyleClass().addAll("approved-table", "erp-table-profile-responsive");
        VBox.setVgrow(table, Priority.ALWAYS);

        TableColumn<BankStatementApiClient.RuleDto, String> colPriority = new TableColumn<>("Priority");
        colPriority.setCellValueFactory(d -> new SimpleStringProperty(String.valueOf(d.getValue().priority())));

        TableColumn<BankStatementApiClient.RuleDto, String> colName = new TableColumn<>("Rule Name");
        colName.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().ruleName()));

        TableColumn<BankStatementApiClient.RuleDto, String> colPattern = new TableColumn<>("Match Condition");
        colPattern.setCellValueFactory(d -> new SimpleStringProperty(
                d.getValue().matchField() + " " + d.getValue().matchOperator() + " \"" + d.getValue().matchPattern() + "\" (" + d.getValue().direction() + ")"));

        TableColumn<BankStatementApiClient.RuleDto, String> colAction = new TableColumn<>("Target Action");
        colAction.setCellValueFactory(d -> new SimpleStringProperty(describeTarget(d.getValue())));

        TableColumn<BankStatementApiClient.RuleDto, String> colMode = new TableColumn<>("Mode");
        colMode.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().executionMode()));

        TableColumn<BankStatementApiClient.RuleDto, String> colHits = new TableColumn<>("Hits");
        colHits.setCellValueFactory(d -> new SimpleStringProperty(String.valueOf(d.getValue().hitCount())));

        TableColumn<BankStatementApiClient.RuleDto, String> colStatus = new TableColumn<>("Status");
        colStatus.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().active() ? "ACTIVE" : "INACTIVE"));

        TableColumn<BankStatementApiClient.RuleDto, Void> colOps = new TableColumn<>("Actions");
        colOps.setSortable(false);

        table.getColumns().setAll(colPriority, colName, colPattern, colAction, colMode, colHits, colStatus, colOps);
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
            final Button btnEdit = new Button("Edit");
            final Button btnDel = new Button("Delete");
            final HBox box = new HBox(6, btnEdit, btnDel);
            {
                box.setAlignment(Pos.CENTER);
                btnEdit.setMinWidth(Region.USE_PREF_SIZE);
                btnEdit.getStyleClass().addAll("approved-button", "approved-secondary-button", "compact-action-button");
                btnEdit.setGraphic(IconFactory.compactIcon("edit", 12));
                btnEdit.setOnAction(ev -> {
                    BankStatementApiClient.RuleDto r = getTableRow() == null ? null : getTableRow().getItem();
                    if (r != null) {
                        openRuleEditor(pane, api, activeBatchId, r, defaultBankAccount, expenseCategories, paymentModes, reloadRef[0]);
                    }
                });

                btnDel.setMinWidth(Region.USE_PREF_SIZE);
                btnDel.getStyleClass().addAll("approved-button", "approved-danger-button", "compact-action-button");
                btnDel.setGraphic(IconFactory.compactIcon("delete", 12));
                btnDel.setOnAction(ev -> {
                    BankStatementApiClient.RuleDto r = getTableRow() == null ? null : getTableRow().getItem();
                    if (r == null) return;
                    if (!AppDialogService.destructive(pane, "Delete Rule", "Delete rule '" + r.ruleName() + "'?",
                            "Future bank statements will no longer match this pattern. Past entries remain unaffected.", "Delete Rule")) {
                        return;
                    }
                    UiTaskExecutor.submitAction("bank-rule-delete-" + r.id(),
                            () -> api.deleteRule(r.id()),
                            ok -> reloadRef[0].run(),
                            err -> AppDialogService.error(pane, "Delete Failed", err.getMessage()));
                });
            }

            @Override
            protected void updateItem(Void v, boolean empty) {
                super.updateItem(v, empty);
                setGraphic(empty ? null : box);
            }
        });

        btnNew.setOnAction(ev -> openRuleEditor(pane, api, activeBatchId, null, defaultBankAccount, expenseCategories, paymentModes, reloadRef[0]));
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
                javafx.geometry.Rectangle2D b = WindowUtilsFx.visualBoundsFor(owner);
                double w = Math.min(b.getWidth() * 0.92, 1140);
                double h = Math.min(b.getHeight() * 0.88, 700);
                stage.setMinWidth(Math.min(820, w));
                stage.setMinHeight(Math.min(500, h));
                stage.setWidth(w);
                stage.setHeight(h);
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

        ComboBox<String> cmbOp = new ComboBox<>(FXCollections.observableArrayList("CONTAINS", "STARTS_WITH", "EXACT"));
        cmbOp.setValue(existing != null ? existing.matchOperator() : "CONTAINS");
        cmbOp.setMaxWidth(Double.MAX_VALUE);

        TextField txtPattern = new TextField(existing != null ? safe(existing.matchPattern()) : "");
        txtPattern.setPromptText("e.g. SHIVANAND, RAZORPAY, ELECTRICITY");
        txtPattern.getStyleClass().add("approved-input");

        ComboBox<String> cmbDir = new ComboBox<>(FXCollections.observableArrayList("DEBIT", "CREDIT", "BOTH"));
        cmbDir.setValue(existing != null ? existing.direction() : "DEBIT");
        cmbDir.setMaxWidth(Double.MAX_VALUE);

        ComboBox<String> cmbAction = new ComboBox<>(FXCollections.observableArrayList("EXPENSE", "BANK_ENTRY", "IGNORE"));
        cmbAction.setValue(existing != null ? existing.actionType() : "EXPENSE");
        cmbAction.setMaxWidth(Double.MAX_VALUE);

        ComboBox<String> cmbCategory = new ComboBox<>(FXCollections.observableArrayList(categories == null ? List.of() : categories));
        cmbCategory.setEditable(true);
        cmbCategory.setMaxWidth(Double.MAX_VALUE);
        if (existing != null && existing.targetCategory() != null) cmbCategory.setValue(existing.targetCategory());

        TextField txtAccount = new TextField(existing != null && existing.targetAccountName() != null ? existing.targetAccountName() : safe(defaultBankAccount));
        txtAccount.getStyleClass().add("approved-input");

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

        int r = 0;
        grid.addRow(r++, new Label("Rule Name *"), txtName);
        grid.addRow(r++, new Label("Match Field"), cmbField);
        grid.addRow(r++, new Label("Match Operator"), cmbOp);
        grid.addRow(r++, new Label("Match Pattern *"), txtPattern);
        grid.addRow(r++, new Label("Direction"), cmbDir);
        grid.addRow(r++, new Label("Target Action"), cmbAction);
        grid.addRow(r++, new Label("Expense Category"), cmbCategory);
        grid.addRow(r++, new Label("Bank Account"), txtAccount);
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
                var req = buildRequest(txtName, cmbField, cmbOp, txtPattern, cmbDir, cmbAction, cmbCategory, txtAccount, cmbPayMode, txtNotes, cmbExecMode, txtPriority, chkActive);
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
                }
            });
        }

        PopupTableWorkspace.prepareDialog(dlg, 680);
        dlg.showAndWait().filter(btSave::equals).ifPresent(ok -> {
            var req = buildRequest(txtName, cmbField, cmbOp, txtPattern, cmbDir, cmbAction, cmbCategory, txtAccount, cmbPayMode, txtNotes, cmbExecMode, txtPriority, chkActive);
            UiTaskExecutor.submitAction("bank-rule-save",
                    () -> existing == null ? api.createRule(req) : api.updateRule(existing.id(), req),
                    saved -> {
                        if (onSaved != null) onSaved.run();
                    },
                    err -> AppDialogService.error(owner, "Save Rule Failed", err.getMessage()));
        });
    }

    private static BankStatementApiClient.RuleSaveRequest buildRequest(
            TextField txtName, ComboBox<String> cmbField, ComboBox<String> cmbOp, TextField txtPattern,
            ComboBox<String> cmbDir, ComboBox<String> cmbAction, ComboBox<String> cmbCategory,
            TextField txtAccount, ComboBox<String> cmbPayMode, TextField txtNotes,
            ComboBox<String> cmbExecMode, TextField txtPriority, CheckBox chkActive) {
        int prio = 100;
        try { prio = Integer.parseInt(txtPriority.getText().trim()); } catch (Exception ignored) {}
        String cat = cmbCategory.getValue() != null && !cmbCategory.getValue().isBlank() ? cmbCategory.getValue() : cmbCategory.getEditor().getText();
        String mode = cmbPayMode.getValue() != null && !cmbPayMode.getValue().isBlank() ? cmbPayMode.getValue() : cmbPayMode.getEditor().getText();
        return new BankStatementApiClient.RuleSaveRequest(
                txtName.getText().trim(), cmbField.getValue(), cmbOp.getValue(), txtPattern.getText().trim(),
                cmbDir.getValue(), cmbAction.getValue(), cat, txtAccount.getText().trim(), mode,
                txtNotes.getText().trim(), cmbExecMode.getValue(), prio, chkActive.isSelected()
        );
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
            case "IGNORE" -> "Ignore";
            default -> safe(r.actionType());
        };
    }

    private static String safe(String s) { return s == null ? "" : s.trim(); }
}
