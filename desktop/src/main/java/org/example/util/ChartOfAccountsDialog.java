package org.example.util;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.example.api.accounting.GeneralLedgerApiClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * Branded Create / Edit Chart of Accounts dialog with automatic account-code suggestion
 * and system-account protection.
 */
public final class ChartOfAccountsDialog {

    private ChartOfAccountsDialog() {}

    public static void show(Node ownerNode,
                            GeneralLedgerApiClient api,
                            GeneralLedgerApiClient.AccountDto existing,
                            List<GeneralLedgerApiClient.AccountDto> allAccounts,
                            Runnable onSaved) {
        boolean isEdit = existing != null && existing.id() != null;
        Dialog<ButtonType> dialog = new OwnedDialog<>(ownerNode);
        dialog.setTitle(isEdit ? "Edit Ledger Account — " + existing.accountCode() : "New Chart of Accounts Ledger");
        dialog.setResizable(true);

        DialogPane pane = dialog.getDialogPane();
        pane.getStyleClass().addAll("dse-dialog-pane", "approved-dialog", "comm-inspection-dialog", "erp-responsive-dialog");
        pane.getProperties().put("dse.dialog.presented", true);
        pane.setPrefSize(660, 440);
        pane.setMinWidth(600);
        pane.setMinHeight(400);

        Region topAccent = new Region();
        topAccent.setMinHeight(5);
        topAccent.setPrefHeight(5);
        topAccent.setMaxHeight(5);
        topAccent.getStyleClass().addAll("dse-dialog-accent", "comm-top-accent", "comm-accent-whatsapp");

        StackPane heroIcon = new StackPane(IconFactory.icon("ledger", 22));
        heroIcon.getStyleClass().addAll("erp-page-header-icon", "dse-dialog-icon-box");

        Label lblKicker = new Label("CHART OF ACCOUNTS  •  LEDGER MASTER");
        lblKicker.getStyleClass().addAll("comm-hero-kicker", "comm-kicker-whatsapp");
        Label lblTitle = new Label(isEdit ? "Edit Account: " + existing.accountCode() + " — " + existing.accountName() : "Create New Ledger Account");
        lblTitle.getStyleClass().add("stock-ledger-title");

        VBox heroText = new VBox(2, lblKicker, lblTitle);
        HBox.setHgrow(heroText, Priority.ALWAYS);

        Button btnTopClose = new Button();
        btnTopClose.setGraphic(IconFactory.compactIcon("cancel", 14));
        btnTopClose.getStyleClass().addAll("dse-dialog-close", "icon-button");
        btnTopClose.getProperties().put("erp-icon-preserve", true);
        btnTopClose.setOnAction(e -> dialog.close());

        HBox headerHero = new HBox(12, heroIcon, heroText, btnTopClose);
        headerHero.setAlignment(Pos.CENTER_LEFT);
        headerHero.getStyleClass().addAll("comm-hero-banner", "comm-hero-whatsapp");
        DocumentInspectionScaffold.installDragPhysics(headerHero, dialog);

        VBox headerWrap = new VBox(0, topAccent, headerHero);
        headerWrap.getStyleClass().addAll("dse-stage-header-wrap", "comm-header-wrap");

        ComboBox<String> cmbType = new ComboBox<>(FXCollections.observableArrayList(
                "ASSET", "LIABILITY", "EQUITY", "REVENUE", "EXPENSE"));
        cmbType.setValue(isEdit ? existing.accountType() : "EXPENSE");
        cmbType.setMaxWidth(Double.MAX_VALUE);
        cmbType.getStyleClass().add("approved-combo");
        if (isEdit && Boolean.TRUE.equals(existing.isSystem())) {
            cmbType.setDisable(true);
        }

        TextField txtCode = new TextField(isEdit ? existing.accountCode() : suggestCode("EXPENSE", allAccounts));
        txtCode.setPromptText("e.g. 5110");
        txtCode.getStyleClass().add("approved-input");
        if (isEdit) txtCode.setDisable(true);

        if (!isEdit) {
            cmbType.valueProperty().addListener((o, a, b) -> {
                if (b != null) txtCode.setText(suggestCode(b, allAccounts));
            });
        }

        TextField txtName = new TextField(isEdit ? existing.accountName() : "");
        txtName.setPromptText("e.g. HDFC Current Account, Factory Rent, Salary Expense");
        txtName.getStyleClass().add("approved-input");

        ComboBox<String> cmbSubtype = new ComboBox<>(FXCollections.observableArrayList(
                "CASH_BANK", "RECEIVABLE", "INVENTORY", "FIXED_ASSET",
                "PAYABLE", "GST_TAX", "LOAN", "CAPITAL",
                "SALES_REVENUE", "OTHER_INCOME", "COGS_DIRECT", "INDIRECT_EXPENSE", "GENERAL"));
        cmbSubtype.setEditable(true);
        cmbSubtype.setValue(isEdit && existing.accountSubtype() != null ? existing.accountSubtype() : "INDIRECT_EXPENSE");
        cmbSubtype.setMaxWidth(Double.MAX_VALUE);
        cmbSubtype.getStyleClass().add("approved-combo");

        TextField txtOpening = new TextField(isEdit && existing.openingBalance() != null
                ? existing.openingBalance().toPlainString() : "0.00");
        txtOpening.setPromptText("0.00");
        txtOpening.getStyleClass().add("approved-input");

        CheckBox chkActive = new CheckBox("Account Active (available for posting)");
        chkActive.setSelected(!isEdit || !Boolean.FALSE.equals(existing.isActive()));
        chkActive.getStyleClass().add("approved-checkbox");
        if (isEdit && Boolean.TRUE.equals(existing.isSystem())) {
            chkActive.setDisable(true);
            chkActive.setTooltip(IconFactory.createTooltip("Core statutory system accounts remain active to protect automatic ERP posting."));
        }

        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(12);
        ColumnConstraints c0 = new ColumnConstraints(); c0.setPercentWidth(50);
        ColumnConstraints c1 = new ColumnConstraints(); c1.setPercentWidth(50);
        grid.getColumnConstraints().addAll(c0, c1);

        grid.add(labeled("Account Category *", cmbType), 0, 0);
        grid.add(labeled("Account Code *", txtCode), 1, 0);
        grid.add(labeled("Account Name *", txtName), 0, 1, 2, 1);
        grid.add(labeled("Account Subtype", cmbSubtype), 0, 2);
        grid.add(labeled("Opening Balance (₹)", txtOpening), 1, 2);
        grid.add(chkActive, 0, 3, 2, 1);

        VBox formCard = new VBox(10, grid);
        formCard.getStyleClass().add("comm-detail-card");
        VBox.setVgrow(formCard, Priority.ALWAYS);

        Button btnSave = new Button(isEdit ? "Save Changes" : "Create Account");
        btnSave.setMinWidth(Region.USE_PREF_SIZE);
        btnSave.setGraphic(IconFactory.compactIcon("save", 14));
        btnSave.getStyleClass().addAll("approved-button", "approved-primary-button");

        Button btnCancel = new Button("Cancel");
        btnCancel.setMinWidth(Region.USE_PREF_SIZE);
        btnCancel.setGraphic(IconFactory.compactIcon("cancel", 14));
        btnCancel.getStyleClass().addAll("approved-button", "approved-danger-ghost-button");
        btnCancel.setOnAction(e -> dialog.close());

        btnSave.setOnAction(e -> {
            String code = txtCode.getText() == null ? "" : txtCode.getText().trim();
            String name = txtName.getText() == null ? "" : txtName.getText().trim();
            String type = cmbType.getValue() == null ? "EXPENSE" : cmbType.getValue().trim().toUpperCase(Locale.ROOT);
            String subtype = cmbSubtype.getEditor() != null && !cmbSubtype.getEditor().getText().isBlank()
                    ? cmbSubtype.getEditor().getText().trim().toUpperCase(Locale.ROOT)
                    : (cmbSubtype.getValue() != null ? cmbSubtype.getValue() : "GENERAL");
            if (code.isBlank() || name.isBlank()) {
                AppDialogService.warning(pane, "Validation", "Account Code and Account Name are required.");
                return;
            }
            BigDecimal opening;
            try {
                opening = new BigDecimal(txtOpening.getText().replace(",", "").replace("₹", "").trim());
            } catch (Exception ex) {
                AppDialogService.warning(pane, "Invalid Balance", "Enter a valid numeric Opening Balance (e.g., 0.00).");
                return;
            }

            btnSave.setDisable(true);
            UiTaskExecutor.submitAction("gl-save-account", () -> {
                if (isEdit) {
                    return api.updateAccount(existing.id(), new GeneralLedgerApiClient.UpdateAccountRequest(
                            name, type, subtype, opening, chkActive.isSelected()));
                }
                return api.createAccount(new GeneralLedgerApiClient.AccountDto(
                        null, code, name, type, subtype, null, "INR", opening, opening, chkActive.isSelected(), false, 0L));
            }, saved -> {
                dialog.close();
                AppDialogService.success(ownerNode,
                        isEdit ? "Account Updated" : "Account Created",
                        saved.accountCode() + " — " + saved.accountName() + " saved to Chart of Accounts.");
                if (onSaved != null) onSaved.run();
            }, err -> {
                btnSave.setDisable(false);
                AppDialogService.error(pane, "Account Save Failed", err.getMessage());
            });
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

    private static String suggestCode(String type, List<GeneralLedgerApiClient.AccountDto> existing) {
        int base = switch (type == null ? "" : type.toUpperCase(Locale.ROOT)) {
            case "ASSET" -> 1100;
            case "LIABILITY" -> 2100;
            case "EQUITY" -> 3100;
            case "REVENUE" -> 4100;
            default -> 5100;
        };
        int max = base;
        if (existing != null) {
            for (var a : existing) {
                if (a.accountCode() == null) continue;
                try {
                    int n = Integer.parseInt(a.accountCode().trim());
                    if (n >= base - 100 && n < base + 900 && n >= max) {
                        max = n + 10;
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        return Integer.toString(max);
    }
}
