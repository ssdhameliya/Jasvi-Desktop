package org.example.util;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.example.api.accounting.GeneralLedgerApiClient;
import org.example.service.SessionService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Multi-line double-entry manual Journal / Expense / Contra / Adjustment Voucher dialog.
 * Enforces real-time Debit == Credit balance verification before posting or saving as draft.
 */
public final class ManualJournalDialog {

    private ManualJournalDialog() {}

    private record AccountChoice(GeneralLedgerApiClient.AccountDto dto) {
        @Override
        public String toString() {
            return dto == null ? "" : dto.accountCode() + " — " + dto.accountName() + " (" + dto.accountType() + ")";
        }
    }

    private static final class LineRow {
        final ComboBox<AccountChoice> cmbAccount = new ComboBox<>();
        final TextField txtDebit = new TextField();
        final TextField txtCredit = new TextField();
        final TextField txtNarration = new TextField();
        final Button btnRemove = new Button();
        final HBox container;

        LineRow(List<AccountChoice> choices, Runnable onRecalc, Runnable onRemove) {
            cmbAccount.setItems(FXCollections.observableArrayList(choices));
            cmbAccount.setPromptText("Select ledger account...");
            cmbAccount.setMaxWidth(Double.MAX_VALUE);
            cmbAccount.getStyleClass().add("approved-combo");
            HBox.setHgrow(cmbAccount, Priority.ALWAYS);

            txtDebit.setPromptText("0.00");
            txtDebit.setPrefWidth(120);
            txtDebit.setMinWidth(105);
            txtDebit.getStyleClass().add("approved-input");

            txtCredit.setPromptText("0.00");
            txtCredit.setPrefWidth(120);
            txtCredit.setMinWidth(105);
            txtCredit.getStyleClass().add("approved-input");

            txtNarration.setPromptText("Line remark (optional)");
            txtNarration.setPrefWidth(210);
            txtNarration.setMinWidth(150);
            txtNarration.getStyleClass().add("approved-input");
            HBox.setHgrow(txtNarration, Priority.SOMETIMES);

            btnRemove.setGraphic(IconFactory.compactIcon("delete", 13));
            btnRemove.getStyleClass().addAll("icon-button", "approved-danger-ghost-button");
            btnRemove.setTooltip(IconFactory.createTooltip("Remove line"));
            btnRemove.setOnAction(e -> onRemove.run());

            txtDebit.textProperty().addListener((o, a, b) -> {
                if (parseMoney(b).compareTo(BigDecimal.ZERO) > 0 && !txtCredit.getText().isBlank()) {
                    txtCredit.clear();
                }
                onRecalc.run();
            });
            txtCredit.textProperty().addListener((o, a, b) -> {
                if (parseMoney(b).compareTo(BigDecimal.ZERO) > 0 && !txtDebit.getText().isBlank()) {
                    txtDebit.clear();
                }
                onRecalc.run();
            });

            container = new HBox(8, cmbAccount, txtDebit, txtCredit, txtNarration, btnRemove);
            container.setAlignment(Pos.CENTER_LEFT);
            container.getStyleClass().add("gl-journal-line-row");
        }
    }

    public static void show(Node ownerNode,
                            GeneralLedgerApiClient api,
                            List<GeneralLedgerApiClient.AccountDto> accounts,
                            Runnable onPosted) {
        if (accounts == null || accounts.isEmpty()) {
            AppDialogService.warning(ownerNode, "No Active Accounts", "Create or load Chart of Accounts before posting a manual journal voucher.");
            return;
        }

        Dialog<ButtonType> dialog = new OwnedDialog<>(ownerNode);
        dialog.setTitle("New Manual Journal Voucher");
        dialog.setResizable(true);

        DialogPane pane = dialog.getDialogPane();
        pane.getStyleClass().addAll("dse-dialog-pane", "approved-dialog", "comm-inspection-dialog", "erp-responsive-dialog");
        pane.getProperties().put("dse.dialog.presented", true);
        PopupTableWorkspace.applyInspectionSizing(dialog, ownerNode);

        // Branded blue top accent + tinted header
        Region topAccent = new Region();
        topAccent.setMinHeight(5);
        topAccent.setPrefHeight(5);
        topAccent.setMaxHeight(5);
        topAccent.getStyleClass().addAll("dse-dialog-accent", "comm-top-accent", "comm-accent-email");

        StackPane heroIcon = new StackPane(IconFactory.icon("ledger", 24));
        heroIcon.getStyleClass().addAll("erp-page-header-icon", "dse-dialog-icon-box");

        Label lblKicker = new Label("DOUBLE-ENTRY ACCOUNTING  •  MANUAL VOUCHER COMPOSER");
        lblKicker.getStyleClass().addAll("comm-hero-kicker", "comm-kicker-email");
        Label lblTitle = new Label("New Journal / Expense / Contra Voucher");
        lblTitle.getStyleClass().add("stock-ledger-title");
        Label lblSub = new Label("Post balanced multi-line entries directly into PostgreSQL General Ledger with real-time Dr = Cr validation");
        lblSub.getStyleClass().add("stock-ledger-subtitle");

        VBox heroText = new VBox(3, lblKicker, lblTitle, lblSub);
        HBox.setHgrow(heroText, Priority.ALWAYS);

        Button btnTopClose = new Button();
        btnTopClose.setGraphic(IconFactory.compactIcon("cancel", 14));
        btnTopClose.getStyleClass().addAll("dse-dialog-close", "icon-button");
        btnTopClose.getProperties().put("erp-icon-preserve", true);
        btnTopClose.setOnAction(e -> dialog.close());

        HBox headerHero = new HBox(14, heroIcon, heroText, btnTopClose);
        headerHero.setAlignment(Pos.CENTER_LEFT);
        headerHero.getStyleClass().addAll("comm-hero-banner", "comm-hero-email");
        DocumentInspectionScaffold.installDragPhysics(headerHero, dialog);

        VBox headerWrap = new VBox(0, topAccent, headerHero);
        headerWrap.getStyleClass().addAll("dse-stage-header-wrap", "comm-header-wrap");

        // Header metadata grid
        DatePicker dpDate = new DatePicker(BusinessClock.today());
        DatePickerFormatter.attach(dpDate);
        dpDate.setMaxWidth(Double.MAX_VALUE);

        ComboBox<String> cmbType = new ComboBox<>(FXCollections.observableArrayList(
                "GENERAL", "EXPENSE", "CONTRA", "ADJUSTMENT", "OPENING_BALANCE"));
        cmbType.setValue("GENERAL");
        cmbType.setMaxWidth(Double.MAX_VALUE);
        cmbType.getStyleClass().add("approved-combo");

        TextField txtRefNo = new TextField();
        txtRefNo.setPromptText("Optional ref / bill / cheque no.");
        txtRefNo.getStyleClass().add("approved-input");

        TextField txtNarration = new TextField();
        txtNarration.setPromptText("Enter voucher narration / audit explanation (required)");
        txtNarration.getStyleClass().add("approved-input");

        GridPane metaGrid = new GridPane();
        metaGrid.setHgap(12);
        metaGrid.setVgap(8);
        ColumnConstraints c0 = new ColumnConstraints(); c0.setPercentWidth(22);
        ColumnConstraints c1 = new ColumnConstraints(); c1.setPercentWidth(22);
        ColumnConstraints c2 = new ColumnConstraints(); c2.setPercentWidth(22);
        ColumnConstraints c3 = new ColumnConstraints(); c3.setPercentWidth(34);
        metaGrid.getColumnConstraints().addAll(c0, c1, c2, c3);

        metaGrid.add(labeled("Voucher Date *", dpDate), 0, 0);
        metaGrid.add(labeled("Voucher Type *", cmbType), 1, 0);
        metaGrid.add(labeled("Reference No.", txtRefNo), 2, 0);
        metaGrid.add(labeled("Narration / Description *", txtNarration), 3, 0);

        VBox metaCard = new VBox(8, metaGrid);
        metaCard.getStyleClass().add("comm-detail-card");

        // Multi-line double-entry section
        List<AccountChoice> choices = accounts.stream()
                .filter(a -> !Boolean.FALSE.equals(a.isActive()))
                .map(AccountChoice::new)
                .toList();

        VBox linesContainer = new VBox(8);
        List<LineRow> lineRows = new ArrayList<>();

        Label lblTotDr = new Label("Total Debit: ₹ 0.00");
        lblTotDr.getStyleClass().addAll("comm-section-heading", "comm-heading-blue");
        Label lblTotCr = new Label("Total Credit: ₹ 0.00");
        lblTotCr.getStyleClass().addAll("comm-section-heading", "comm-heading-success");
        Label lblDiffPill = new Label("ENTER LINES (Dr = Cr)");
        lblDiffPill.getStyleClass().addAll("comm-status-badge", "comm-status-failed");

        Button btnSaveDraft = new Button("Save as Draft");
        btnSaveDraft.setMinWidth(Region.USE_PREF_SIZE);
        btnSaveDraft.setGraphic(IconFactory.compactIcon("save", 14));
        btnSaveDraft.getStyleClass().addAll("approved-button", "approved-secondary-button");

        Button btnPostNow = new Button("Post Journal Voucher");
        btnPostNow.setMinWidth(Region.USE_PREF_SIZE);
        btnPostNow.setGraphic(IconFactory.compactIcon("complete", 14));
        btnPostNow.getStyleClass().addAll("approved-button", "approved-primary-button");

        Runnable recalc = () -> {
            BigDecimal sumDr = BigDecimal.ZERO;
            BigDecimal sumCr = BigDecimal.ZERO;
            int validAccounts = 0;
            for (LineRow row : lineRows) {
                BigDecimal d = parseMoney(row.txtDebit.getText());
                BigDecimal c = parseMoney(row.txtCredit.getText());
                sumDr = sumDr.add(d);
                sumCr = sumCr.add(c);
                if (row.cmbAccount.getValue() != null && (d.compareTo(BigDecimal.ZERO) > 0 || c.compareTo(BigDecimal.ZERO) > 0)) {
                    validAccounts++;
                }
            }
            BigDecimal diff = sumDr.subtract(sumCr).abs().setScale(2, RoundingMode.HALF_UP);
            lblTotDr.setText("Total Debit: " + money(sumDr));
            lblTotCr.setText("Total Credit: " + money(sumCr));
            boolean balanced = sumDr.compareTo(BigDecimal.ZERO) > 0 && diff.compareTo(BigDecimal.ZERO) == 0 && validAccounts >= 2;
            lblDiffPill.getStyleClass().removeAll("comm-status-sent", "comm-status-failed");
            if (balanced) {
                lblDiffPill.setText("BALANCED (Dr = Cr)");
                lblDiffPill.getStyleClass().add("comm-status-sent");
            } else if (sumDr.compareTo(BigDecimal.ZERO) == 0 && sumCr.compareTo(BigDecimal.ZERO) == 0) {
                lblDiffPill.setText("ENTER AMOUNTS");
                lblDiffPill.getStyleClass().add("comm-status-failed");
            } else {
                lblDiffPill.setText("OUT OF BALANCE: " + money(diff));
                lblDiffPill.getStyleClass().add("comm-status-failed");
            }
            btnSaveDraft.setDisable(!balanced);
            btnPostNow.setDisable(!balanced);
        };

        Runnable addLineAction = () -> {
            final LineRow[] holder = new LineRow[1];
            holder[0] = new LineRow(choices, recalc, () -> {
                if (lineRows.size() <= 2) {
                    AppDialogService.warning(pane, "Minimum Lines", "A double-entry journal voucher requires at least 2 lines.");
                    return;
                }
                lineRows.remove(holder[0]);
                linesContainer.getChildren().remove(holder[0].container);
                recalc.run();
            });
            lineRows.add(holder[0]);
            linesContainer.getChildren().add(holder[0].container);
            recalc.run();
        };

        // Start with 2 blank rows (Debit row + Credit row)
        addLineAction.run();
        addLineAction.run();

        // Column header strip
        Label hAcc = new Label("LEDGER ACCOUNT");
        hAcc.getStyleClass().addAll("comm-section-heading", "comm-heading-indigo");
        hAcc.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(hAcc, Priority.ALWAYS);
        Label hDr = new Label("DEBIT (₹)");
        hDr.getStyleClass().addAll("comm-section-heading", "comm-heading-blue");
        hDr.setPrefWidth(120);
        Label hCr = new Label("CREDIT (₹)");
        hCr.getStyleClass().addAll("comm-section-heading", "comm-heading-success");
        hCr.setPrefWidth(120);
        Label hRem = new Label("LINE REMARK");
        hRem.getStyleClass().addAll("comm-section-heading", "comm-heading-amber");
        hRem.setPrefWidth(210);
        HBox.setHgrow(hRem, Priority.SOMETIMES);
        Region hAct = new Region();
        hAct.setPrefWidth(32);
        HBox colHeader = new HBox(8, hAcc, hDr, hCr, hRem, hAct);
        colHeader.setAlignment(Pos.CENTER_LEFT);

        Button btnAddLine = new Button("+ Add Line");
        btnAddLine.setMinWidth(Region.USE_PREF_SIZE);
        btnAddLine.setGraphic(IconFactory.compactIcon("add", 13));
        btnAddLine.getStyleClass().addAll("approved-button", "approved-secondary-button");
        btnAddLine.setOnAction(e -> addLineAction.run());

        Region lineTopSpacer = new Region();
        HBox.setHgrow(lineTopSpacer, Priority.ALWAYS);
        Label lblLinesTitle = new Label("DOUBLE-ENTRY JOURNAL LINES");
        lblLinesTitle.getStyleClass().addAll("comm-section-heading", "comm-heading-indigo");
        HBox linesHeaderRow = new HBox(8, lblLinesTitle, lineTopSpacer, btnAddLine);
        linesHeaderRow.setAlignment(Pos.CENTER_LEFT);

        ScrollPane linesScroll = new ScrollPane(linesContainer);
        linesScroll.setFitToWidth(true);
        linesScroll.getStyleClass().add("erp-clean-scroll");
        VBox.setVgrow(linesScroll, Priority.ALWAYS);

        Region sumSpacer = new Region();
        HBox.setHgrow(sumSpacer, Priority.ALWAYS);
        HBox summaryStrip = new HBox(18, lblTotDr, lblTotCr, sumSpacer, lblDiffPill);
        summaryStrip.setAlignment(Pos.CENTER_LEFT);
        summaryStrip.getStyleClass().add("gl-totals-strip");

        VBox linesCard = new VBox(10, linesHeaderRow, colHeader, linesScroll, summaryStrip);
        linesCard.getStyleClass().add("comm-detail-card");
        VBox.setVgrow(linesCard, Priority.ALWAYS);

        java.util.function.Consumer<String> submit = targetStatus -> {
            LocalDate entryDate = dpDate.getValue() != null ? dpDate.getValue() : BusinessClock.today();
            String narration = txtNarration.getText() == null ? "" : txtNarration.getText().trim();
            if (narration.isBlank()) {
                AppDialogService.warning(pane, "Narration Required", "Please enter a clear narration explaining this journal voucher.");
                txtNarration.requestFocus();
                return;
            }
            List<GeneralLedgerApiClient.CreateLineRequest> reqLines = new ArrayList<>();
            for (LineRow row : lineRows) {
                AccountChoice chosen = row.cmbAccount.getValue();
                BigDecimal d = parseMoney(row.txtDebit.getText());
                BigDecimal c = parseMoney(row.txtCredit.getText());
                if (d.compareTo(BigDecimal.ZERO) == 0 && c.compareTo(BigDecimal.ZERO) == 0) continue;
                if (chosen == null || chosen.dto() == null) {
                    AppDialogService.warning(pane, "Account Required", "Select a ledger account for every line that has an amount.");
                    return;
                }
                reqLines.add(new GeneralLedgerApiClient.CreateLineRequest(
                        chosen.dto().id(),
                        chosen.dto().accountCode(),
                        d,
                        c,
                        null,
                        row.txtNarration.getText() == null || row.txtNarration.getText().isBlank() ? narration : row.txtNarration.getText().trim(),
                        null
                ));
            }
            if (reqLines.size() < 2) {
                AppDialogService.warning(pane, "Incomplete Entry", "Enter at least one Debit line and one Credit line.");
                return;
            }
            String actor = SessionService.current() != null ? SessionService.current().getUsername() : "admin";
            String refNo = txtRefNo.getText() == null ? "" : txtRefNo.getText().trim();
            GeneralLedgerApiClient.CreateJournalRequest req = new GeneralLedgerApiClient.CreateJournalRequest(
                    entryDate,
                    cmbType.getValue(),
                    "MANUAL",
                    null,
                    refNo.isBlank() ? null : refNo,
                    narration,
                    actor,
                    targetStatus,
                    reqLines
            );

            btnSaveDraft.setDisable(true);
            btnPostNow.setDisable(true);
            UiTaskExecutor.submitAction("gl-post-manual-journal",
                    () -> api.postJournalEntry(req),
                    saved -> {
                        dialog.close();
                        AppDialogService.success(ownerNode,
                                "DRAFT".equals(targetStatus) ? "Journal Draft Saved" : "Journal Voucher Posted",
                                "Voucher " + saved.entryNumber() + " (" + saved.status() + ") recorded cleanly.");
                        if (onPosted != null) onPosted.run();
                    },
                    err -> {
                        recalc.run();
                        AppDialogService.error(pane, "Posting Failed", err.getMessage());
                    });
        };

        btnSaveDraft.setOnAction(e -> submit.accept("DRAFT"));
        btnPostNow.setOnAction(e -> submit.accept("POSTED"));

        Button btnClose = new Button("Cancel");
        btnClose.setMinWidth(Region.USE_PREF_SIZE);
        btnClose.setGraphic(IconFactory.compactIcon("cancel", 14));
        btnClose.getStyleClass().addAll("approved-button", "approved-danger-ghost-button");
        btnClose.setOnAction(e -> dialog.close());

        Region dockSpacer = new Region();
        HBox.setHgrow(dockSpacer, Priority.ALWAYS);
        HBox actionDock = new HBox(10, btnSaveDraft, btnPostNow, dockSpacer, btnClose);
        actionDock.setAlignment(Pos.CENTER_LEFT);
        actionDock.getStyleClass().add("erp-inspection-action-dock");

        VBox contentBody = new VBox(12, metaCard, linesCard, actionDock);
        contentBody.setPadding(new Insets(14, 20, 14, 20));
        VBox.setVgrow(contentBody, Priority.ALWAYS);

        VBox root = new VBox(0, headerWrap, contentBody);
        root.getStyleClass().addAll("dse-dialog-shell", "dse-dialog-shell-workspace", "comm-inspection-shell");
        pane.setContent(root);
        pane.getButtonTypes().add(ButtonType.CLOSE);
        dialog.setOnShowing(ev -> {
            Node defaultBar = pane.lookup(".button-bar");
            if (defaultBar != null) {
                defaultBar.setVisible(false);
                defaultBar.setManaged(false);
            }
        });

        dialog.showAndWait();
    }

    private static VBox labeled(String caption, Node control) {
        Label l = new Label(caption);
        l.getStyleClass().addAll("field-label", "comm-section-heading");
        return new VBox(4, l, control);
    }

    private static BigDecimal parseMoney(String raw) {
        if (raw == null || raw.isBlank()) return BigDecimal.ZERO;
        try {
            String cleaned = raw.replace("₹", "").replace(",", "").trim();
            BigDecimal val = new BigDecimal(cleaned);
            return val.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : val;
        } catch (Exception ignored) {
            return BigDecimal.ZERO;
        }
    }

    private static String money(BigDecimal v) {
        return String.format(Locale.of("en", "IN"), "₹ %,.2f", v == null ? BigDecimal.ZERO : v);
    }
}
