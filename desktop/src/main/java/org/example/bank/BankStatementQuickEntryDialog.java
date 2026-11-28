package org.example.bank;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.example.api.bank.BankStatementApiClient;
import org.example.model.Party;
import org.example.service.PartyService;
import org.example.shared.BankNarrationParser;
import org.example.util.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * In-screen quick posting modals for Bank Statement rows:
 * 1. Quick Expense (with optional Smart Auto-Rule saving)
 * 2. Quick Bank Entry & Internal Contra Transfer
 * 3. Quick Customer Advance Receipt / Supplier Advance Payment
 * Keeps accountants directly inside Bank Statement without losing scroll or filter context.
 */
public final class BankStatementQuickEntryDialog {

    public enum QuickKind {
        EXPENSE,
        BANK_ENTRY,
        CONTRA,
        ADVANCE
    }

    public enum NoMatchChoice {
        ADVANCE,
        EXPENSE,
        CONTRA_OR_BANK,
        CANCEL
    }

    private BankStatementQuickEntryDialog() {
    }

    public static NoMatchChoice showNoMatchFallbackDialog(
            Node owner,
            BankStatementApiClient.TransactionDto tx,
            boolean canReconcile,
            boolean canExpense,
            boolean canBankEntry) {
        boolean credit = tx.credit() > 0 && tx.debit() <= 0;
        double amt = credit ? tx.credit() : tx.debit();
        var parsed = BankNarrationParser.parse(tx.description(), tx.reference());

        OwnedDialog<NoMatchChoice> dialog = new OwnedDialog<>(owner);
        dialog.setTitle("No Matching Invoice Found");
        dialog.setResizable(true);
        AppDialogRenderer.configureWorkspace(
                dialog,
                "warning",
                "No Matching Invoice Found",
                (credit ? "Credit Deposit" : "Debit Payment") + " • ₹ " + money(amt),
                safe(tx.transactionDate()) + "  •  Ref: " + (parsed.hasUtrOrRef() ? parsed.utrOrRef() : safe(tx.reference())),
                ""
        );

        VBox content = new VBox(12);
        content.setPadding(new Insets(12));
        content.setPrefWidth(620);

        HBox metrics = PopupTableWorkspace.metricStrip(
                PopupTableWorkspace.metricCard(credit ? "Money Received" : "Money Paid", "₹ " + money(amt), credit ? "credit" : "debit"),
                PopupTableWorkspace.metricCard("Channel", parsed.hasChannel() ? parsed.channel() : "BANK", "bank"),
                PopupTableWorkspace.metricCard("Line Ref", safe(tx.statementReference()), "reference")
        );

        Label info = new Label("No open Sales Invoice, Purchase Bill, or Return Refund matches this bank row yet.\n\nChoose how to record it directly without leaving this screen:");
        info.setWrapText(true);
        info.getStyleClass().add("bank-dialog-help");

        Label rawNarration = new Label("Narration: " + safe(tx.description()));
        rawNarration.setWrapText(true);
        rawNarration.getStyleClass().add("bank-dialog-value");

        VBox optionsBox = new VBox(8);
        Button btnAdvance = new Button(credit ? "Record as Customer Advance Receipt" : "Record as Supplier Advance Payment");
        btnAdvance.setGraphic(IconFactory.compactIcon("customer", 15));
        btnAdvance.getStyleClass().addAll("approved-button", "approved-primary-button");
        btnAdvance.setMaxWidth(Double.MAX_VALUE);
        btnAdvance.setDisable(!canReconcile && !canBankEntry);

        Button btnContraOrBank = new Button("Record as Internal Transfer (Contra) / Bank Entry");
        btnContraOrBank.setGraphic(IconFactory.compactIcon("bank", 15));
        btnContraOrBank.getStyleClass().addAll("approved-button", "approved-secondary-button");
        btnContraOrBank.setMaxWidth(Double.MAX_VALUE);
        btnContraOrBank.setDisable(!canBankEntry);

        Button btnExpense = new Button("Move to Expense (Bank Charges, Rent, Electricity...)");
        btnExpense.setGraphic(IconFactory.compactIcon("expense", 15));
        btnExpense.getStyleClass().addAll("approved-button", "approved-secondary-button");
        btnExpense.setMaxWidth(Double.MAX_VALUE);
        btnExpense.setVisible(!credit && canExpense);
        btnExpense.setManaged(!credit && canExpense);

        btnAdvance.setOnAction(e -> {
            dialog.setResult(NoMatchChoice.ADVANCE);
            dialog.close();
        });
        btnContraOrBank.setOnAction(e -> {
            dialog.setResult(NoMatchChoice.CONTRA_OR_BANK);
            dialog.close();
        });
        btnExpense.setOnAction(e -> {
            dialog.setResult(NoMatchChoice.EXPENSE);
            dialog.close();
        });

        optionsBox.getChildren().addAll(btnAdvance, btnContraOrBank, btnExpense);
        content.getChildren().addAll(metrics, info, rawNarration, optionsBox);

        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE);
        dialog.setResultConverter(bt -> NoMatchChoice.CANCEL);
        PopupTableWorkspace.prepareDialog(dialog, 660);
        return dialog.showAndWait().orElse(NoMatchChoice.CANCEL);
    }

    public static void showQuickExpense(
            Node owner,
            BankStatementApiClient api,
            BankStatementApiClient.TransactionDto tx,
            String batchAccountName,
            List<String> categories,
            List<String> paymentModes,
            boolean allowRememberRule,
            String performedBy,
            Runnable onOpenFullScreen,
            Runnable onSuccess) {
        if (tx.debit() <= 0) {
            AppDialogService.warning(owner, "Expense Entry", "Debit Transaction Required", "Only debit transactions can be recorded as an Expense.");
            return;
        }
        if (categories == null || categories.isEmpty()) {
            AppDialogService.warning(owner, "Expense Category Required", "Configure Expense Categories", "Add at least one Expense Category in Master Data before moving transactions to Expense.");
            return;
        }

        var parsed = BankNarrationParser.parse(tx.description(), tx.reference());
        OwnedDialog<Boolean> dialog = new OwnedDialog<>(owner);
        dialog.setTitle("Quick Move to Expense");
        dialog.setResizable(true);
        AppDialogRenderer.configureWorkspace(
                dialog,
                "expense",
                "Quick Expense Entry",
                "Post ₹ " + money(tx.debit()) + " from Bank Statement",
                safe(tx.statementReference()) + "  •  " + BusinessClock.formatDate(tx.transactionDate()),
                ""
        );

        HBox metrics = PopupTableWorkspace.metricStrip(
                PopupTableWorkspace.metricCard("Debit Amount", "₹ " + money(tx.debit()), "debit"),
                PopupTableWorkspace.metricCard("Bank Account", batchAccountName.isBlank() ? "Statement Account" : batchAccountName, "bank"),
                PopupTableWorkspace.metricCard("Ref / UTR", parsed.hasUtrOrRef() ? parsed.utrOrRef() : safe(tx.reference()), "reference")
        );

        ComboBox<String> cmbCategory = new ComboBox<>(FXCollections.observableArrayList(categories));
        cmbCategory.setPromptText("Select Expense Category *");
        cmbCategory.setMaxWidth(Double.MAX_VALUE);
        cmbCategory.getItems().stream()
                .filter(c -> c.toLowerCase(Locale.ROOT).contains("bank") || c.toLowerCase(Locale.ROOT).contains("charge"))
                .findFirst()
                .ifPresent(c -> {
                    if ("CHARGES".equals(parsed.channel())) cmbCategory.setValue(c);
                });

        ComboBox<String> cmbMode = new ComboBox<>(FXCollections.observableArrayList(paymentModes == null ? List.of() : paymentModes));
        cmbMode.setEditable(true);
        String defaultMode = parsed.hasChannel() && !"OTHER".equals(parsed.channel()) && !"CHARGES".equals(parsed.channel())
                ? parsed.channel()
                : (paymentModes != null && !paymentModes.isEmpty() ? paymentModes.getFirst() : "Bank Transfer");
        if (!cmbMode.getItems().contains(defaultMode)) cmbMode.getItems().add(0, defaultMode);
        cmbMode.setValue(defaultMode);
        cmbMode.setMaxWidth(Double.MAX_VALUE);

        TextField txtAccount = new TextField(safe(batchAccountName));
        txtAccount.getStyleClass().add("approved-input");

        TextArea txtNotes = new TextArea(safe(tx.description()));
        txtNotes.setPrefRowCount(3);
        txtNotes.setWrapText(true);
        txtNotes.getStyleClass().add("input-field");

        CheckBox chkClaimGst = new CheckBox("Claim GST Input Credit on Bank Fee / Expense");
        chkClaimGst.getStyleClass().addAll("approved-checkbox", "ios-toggle-switch");
        ComboBox<Double> cmbGstRate = new ComboBox<>(FXCollections.observableArrayList(5.0, 12.0, 18.0, 28.0));
        cmbGstRate.setValue(18.0);
        cmbGstRate.disableProperty().bind(chkClaimGst.selectedProperty().not());
        ComboBox<String> cmbSupplyType = new ComboBox<>(FXCollections.observableArrayList("Intra-State (CGST+SGST)", "Inter-State (IGST)"));
        cmbSupplyType.setValue("Intra-State (CGST+SGST)");
        cmbSupplyType.disableProperty().bind(chkClaimGst.selectedProperty().not());
        TextField txtVendorGstin = new TextField();
        txtVendorGstin.setPromptText("Optional Bank / Vendor GSTIN");
        txtVendorGstin.getStyleClass().add("approved-input");
        txtVendorGstin.disableProperty().bind(chkClaimGst.selectedProperty().not());
        Label lblGstPreview = new Label("No GST ITC split (full amount booked to expense)");
        lblGstPreview.getStyleClass().add("bank-dialog-help");

        Runnable updateGstPreview = () -> {
            if (!chkClaimGst.isSelected()) {
                lblGstPreview.setText("No GST ITC split (full ₹ " + money(tx.debit()) + " booked to expense)");
                return;
            }
            double r = cmbGstRate.getValue() == null ? 18.0 : cmbGstRate.getValue();
            double base = Math.round((tx.debit() / (1.0 + r / 100.0)) * 100.0) / 100.0;
            double tax = Math.round((tx.debit() - base) * 100.0) / 100.0;
            boolean inter = cmbSupplyType.getValue() != null && cmbSupplyType.getValue().contains("IGST");
            lblGstPreview.setText("Base Expense: ₹ " + money(base) + " + " + (inter ? "IGST " : "CGST+SGST ") + r + "%: ₹ " + money(tax) + " = Total ₹ " + money(tx.debit()));
        };
        chkClaimGst.selectedProperty().addListener((o, a, b) -> updateGstPreview.run());
        cmbGstRate.valueProperty().addListener((o, a, b) -> updateGstPreview.run());
        cmbSupplyType.valueProperty().addListener((o, a, b) -> updateGstPreview.run());
        updateGstPreview.run();

        CheckBox chkRule = new CheckBox("Remember rule for future statements");
        chkRule.getStyleClass().addAll("approved-checkbox", "ios-toggle-switch");
        String suggestedPattern = BankReconciliationRulesDialog.suggestNarrationPattern(tx.description());
        TextField txtPattern = new TextField(suggestedPattern);
        txtPattern.setPromptText("Narration keyword");
        txtPattern.getStyleClass().add("approved-input");
        txtPattern.disableProperty().bind(chkRule.selectedProperty().not());
        ComboBox<String> cmbExecMode = new ComboBox<>(FXCollections.observableArrayList("SUGGEST", "AUTO_APPLY"));
        cmbExecMode.setValue("SUGGEST");
        cmbExecMode.disableProperty().bind(chkRule.selectedProperty().not());

        GridPane grid = buildFormGrid();
        int row = 0;
        addField(grid, row++, "Expense Category *", "category", cmbCategory);
        addField(grid, row++, "Bank Account", "bank", txtAccount);
        addField(grid, row++, "Payment Mode", "payment", cmbMode);
        addField(grid, row++, "Description / Notes", "notes", txtNotes);
        addField(grid, row++, "GST Input Credit", "tax", chkClaimGst);
        HBox gstConfigRow = new HBox(8, cmbGstRate, cmbSupplyType, txtVendorGstin);
        HBox.setHgrow(txtVendorGstin, Priority.ALWAYS);
        addField(grid, row++, "GST Split / GSTIN", "tax", new VBox(4, gstConfigRow, lblGstPreview));
        if (allowRememberRule) {
            addField(grid, row++, "Smart Auto-Rule", "reconcile", chkRule);
            HBox ruleRow = new HBox(8, txtPattern, cmbExecMode);
            HBox.setHgrow(txtPattern, Priority.ALWAYS);
            addField(grid, row++, "Match Pattern", "search", ruleRow);
        }

        Hyperlink openFull = new Hyperlink("Need to upload a bill attachment? Open full Expense screen →");
        openFull.getStyleClass().add("bank-match-link");
        openFull.setOnAction(e -> {
            dialog.setResult(Boolean.FALSE);
            dialog.close();
            if (onOpenFullScreen != null) onOpenFullScreen.run();
        });

        VBox content = new VBox(12, metrics, grid, openFull);
        content.setPadding(new Insets(12));
        content.setMinWidth(600);
        dialog.getDialogPane().setContent(content);
        SearchableComboBoxHelper.makeAllSearchable(content);
        PopupTableWorkspace.prepareDialog(dialog, 760);

        ButtonType saveBtnType = new ButtonType("Save Expense & Reconcile", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, saveBtnType);
        Node saveNode = dialog.getDialogPane().lookupButton(saveBtnType);
        saveNode.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            if (cmbCategory.getValue() == null || cmbCategory.getValue().isBlank()) {
                ev.consume();
                AppDialogService.warning(dialog, "Category Required", "Select an Expense Category", "Please select an Expense Category before saving.");
            }
        });

        dialog.setResultConverter(bt -> saveBtnType.equals(bt));
        if (dialog.showAndWait().orElse(false)) {
            String cat = cmbCategory.getValue().trim();
            String mode = cmbMode.isEditable() ? safe(cmbMode.getEditor().getText()) : safe(cmbMode.getValue());
            if (mode.isBlank()) mode = "Bank Transfer";
            String account = safe(txtAccount.getText());
            String notes = safe(txtNotes.getText());
            double gstRate = chkClaimGst.isSelected() && cmbGstRate.getValue() != null ? cmbGstRate.getValue() : 0.0;
            boolean interState = chkClaimGst.isSelected() && cmbSupplyType.getValue() != null && cmbSupplyType.getValue().contains("IGST");
            String vendorGstin = chkClaimGst.isSelected() ? safe(txtVendorGstin.getText()) : null;
            boolean saveRule = allowRememberRule && chkRule.isSelected() && !safe(txtPattern.getText()).isBlank();
            String pattern = safe(txtPattern.getText());
            String execMode = cmbExecMode.getValue();
            final String finalMode = mode;

            UiTaskExecutor.submitSerial("bank-statement-quick-expense-" + tx.id(), () -> {
                var res = api.expense(tx.id(), new BankStatementApiClient.ExpenseRequest(
                        cat, account, finalMode, notes, null, performedBy, gstRate, interState, vendorGstin));
                if (saveRule) {
                    try {
                        api.createRule(new BankStatementApiClient.RuleSaveRequest(
                                cat + " (" + pattern + ")", "NARRATION", "CONTAINS", pattern,
                                "DEBIT", "EXPENSE", cat, account, finalMode, notes, execMode, 100, true
                        ));
                    } catch (Exception ignored) {
                    }
                }
                return res;
            }, res -> {
                AppDialogService.success(owner, "Expense Saved", res.message() + (saveRule ? "\n\nSaved Smart Rule for \"" + pattern + "\"" : ""));
                if (onSuccess != null) onSuccess.run();
            }, err -> AppDialogService.error(owner, "Quick Expense Failed", err.getMessage()));
        }
    }

    public static void showQuickBankOrContra(
            Node owner,
            BankStatementApiClient api,
            BankStatementApiClient.TransactionDto tx,
            String batchAccountName,
            List<String> allBankAccounts,
            List<String> paymentModes,
            boolean initialContraMode,
            boolean allowRememberRule,
            String performedBy,
            Runnable onSuccess) {
        boolean credit = tx.credit() > 0 && tx.debit() <= 0;
        double amt = credit ? tx.credit() : tx.debit();
        var parsed = BankNarrationParser.parse(tx.description(), tx.reference());

        OwnedDialog<Boolean> dialog = new OwnedDialog<>(owner);
        dialog.setTitle(initialContraMode ? "Quick Contra Transfer / Bank Entry" : "Quick Move to Bank Entry");
        dialog.setResizable(true);
        AppDialogRenderer.configureWorkspace(
                dialog,
                "bank",
                initialContraMode ? "Internal Transfer (Contra) / Bank Entry" : "Quick Bank Entry",
                (credit ? "Bank Deposit + ₹ " : "Bank Withdrawal - ₹ ") + money(amt),
                safe(tx.statementReference()) + "  •  " + BusinessClock.formatDate(tx.transactionDate()),
                ""
        );

        HBox metrics = PopupTableWorkspace.metricStrip(
                PopupTableWorkspace.metricCard(credit ? "Credit Deposit" : "Debit Withdrawal", "₹ " + money(amt), credit ? "credit" : "debit"),
                PopupTableWorkspace.metricCard("Channel", parsed.hasChannel() ? parsed.channel() : "BANK", "bank"),
                PopupTableWorkspace.metricCard("Ref / UTR", parsed.hasUtrOrRef() ? parsed.utrOrRef() : safe(tx.reference()), "reference")
        );

        ComboBox<String> cmbPurpose = new ComboBox<>(FXCollections.observableArrayList(
                "Internal Transfer (Contra)",
                credit ? "General Bank Deposit" : "General Bank Withdrawal",
                "Owner Capital / Drawings",
                "Loan EMI / Bank Finance"
        ));
        cmbPurpose.setValue(initialContraMode ? "Internal Transfer (Contra)" : (credit ? "General Bank Deposit" : "General Bank Withdrawal"));
        cmbPurpose.setMaxWidth(Double.MAX_VALUE);

        List<String> contraTargets = new ArrayList<>();
        contraTargets.add("Cash in Hand");
        if (allBankAccounts != null) {
            for (String b : allBankAccounts) {
                if (b != null && !b.isBlank() && !b.equalsIgnoreCase(batchAccountName)) {
                    contraTargets.add(b);
                }
            }
        }
        ComboBox<String> cmbContraAccount = new ComboBox<>(FXCollections.observableArrayList(contraTargets));
        cmbContraAccount.setEditable(true);
        cmbContraAccount.setPromptText(credit ? "Transferred FROM account *" : "Transferred TO account *");
        if (!contraTargets.isEmpty()) cmbContraAccount.getSelectionModel().selectFirst();
        cmbContraAccount.setMaxWidth(Double.MAX_VALUE);

        Label lblContraCaption = new Label(credit ? "From Bank / Cash *" : "To Bank / Cash *");
        lblContraCaption.setMinWidth(Region.USE_PREF_SIZE);

        Runnable syncContraVisibility = () -> {
            boolean contra = "Internal Transfer (Contra)".equals(cmbPurpose.getValue());
            lblContraCaption.setVisible(contra);
            lblContraCaption.setManaged(contra);
            cmbContraAccount.setVisible(contra);
            cmbContraAccount.setManaged(contra);
        };
        cmbPurpose.valueProperty().addListener((o, a, b) -> syncContraVisibility.run());
        syncContraVisibility.run();

        ComboBox<String> cmbMode = new ComboBox<>(FXCollections.observableArrayList(paymentModes == null ? List.of() : paymentModes));
        cmbMode.setEditable(true);
        String defaultMode = parsed.hasChannel() && !"OTHER".equals(parsed.channel())
                ? parsed.channel()
                : (paymentModes != null && !paymentModes.isEmpty() ? paymentModes.getFirst() : "Bank Transfer");
        if (!cmbMode.getItems().contains(defaultMode)) cmbMode.getItems().add(0, defaultMode);
        cmbMode.setValue(defaultMode);
        cmbMode.setMaxWidth(Double.MAX_VALUE);

        TextArea txtNotes = new TextArea(safe(tx.description()));
        txtNotes.setPrefRowCount(3);
        txtNotes.setWrapText(true);
        txtNotes.getStyleClass().add("input-field");

        CheckBox chkRule = new CheckBox("Remember rule for future statements");
        chkRule.getStyleClass().addAll("approved-checkbox", "ios-toggle-switch");
        String suggestedPattern = BankReconciliationRulesDialog.suggestNarrationPattern(tx.description());
        TextField txtPattern = new TextField(suggestedPattern);
        txtPattern.setPromptText("Narration keyword");
        txtPattern.getStyleClass().add("approved-input");
        txtPattern.disableProperty().bind(chkRule.selectedProperty().not());
        ComboBox<String> cmbExecMode = new ComboBox<>(FXCollections.observableArrayList("SUGGEST", "AUTO_APPLY"));
        cmbExecMode.setValue("SUGGEST");
        cmbExecMode.disableProperty().bind(chkRule.selectedProperty().not());

        GridPane grid = buildFormGrid();
        int row = 0;
        addField(grid, row++, "Entry Purpose", "category", cmbPurpose);
        grid.addRow(row++, lblContraCaption, cmbContraAccount);
        GridPane.setHgrow(cmbContraAccount, Priority.ALWAYS);
        addField(grid, row++, "Payment Mode", "payment", cmbMode);
        addField(grid, row++, "Notes / Narration", "notes", txtNotes);
        if (allowRememberRule) {
            addField(grid, row++, "Smart Auto-Rule", "reconcile", chkRule);
            HBox ruleRow = new HBox(8, txtPattern, cmbExecMode);
            HBox.setHgrow(txtPattern, Priority.ALWAYS);
            addField(grid, row++, "Match Pattern", "search", ruleRow);
        }

        VBox content = new VBox(12, metrics, grid);
        content.setPadding(new Insets(12));
        content.setMinWidth(600);
        dialog.getDialogPane().setContent(content);
        SearchableComboBoxHelper.makeAllSearchable(content);
        PopupTableWorkspace.prepareDialog(dialog, 720);

        ButtonType saveBtnType = new ButtonType("Save & Reconcile", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, saveBtnType);
        Node saveNode = dialog.getDialogPane().lookupButton(saveBtnType);
        saveNode.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            if ("Internal Transfer (Contra)".equals(cmbPurpose.getValue())) {
                String target = cmbContraAccount.isEditable() ? safe(cmbContraAccount.getEditor().getText()) : safe(cmbContraAccount.getValue());
                if (target.isBlank()) {
                    ev.consume();
                    AppDialogService.warning(dialog, "Account Required", "Select Target Contra Account", "Select or enter the Bank / Cash account this transfer moved between.");
                }
            }
        });

        dialog.setResultConverter(bt -> saveBtnType.equals(bt));
        if (dialog.showAndWait().orElse(false)) {
            String purpose = safe(cmbPurpose.getValue());
            boolean contra = "Internal Transfer (Contra)".equals(purpose);
            String category = switch (purpose) {
                case "Internal Transfer (Contra)" -> "Contra Transfer";
                case "Owner Capital / Drawings" -> credit ? "Owner Capital" : "Owner Drawings";
                case "Loan EMI / Bank Finance" -> "Loan & EMI";
                default -> credit ? "Deposit" : "Withdrawal";
            };
            String contraTarget = contra
                    ? (cmbContraAccount.isEditable() ? safe(cmbContraAccount.getEditor().getText()) : safe(cmbContraAccount.getValue()))
                    : null;
            String mode = cmbMode.isEditable() ? safe(cmbMode.getEditor().getText()) : safe(cmbMode.getValue());
            if (mode.isBlank()) mode = "Bank Transfer";
            String notes = safe(txtNotes.getText());
            boolean saveRule = allowRememberRule && chkRule.isSelected() && !safe(txtPattern.getText()).isBlank();
            String pattern = safe(txtPattern.getText());
            String execMode = cmbExecMode.getValue();
            final String finalMode = mode;

            UiTaskExecutor.submitSerial("bank-statement-quick-bank-" + tx.id(), () -> {
                var res = api.bankEntry(tx.id(), new BankStatementApiClient.BankEntryRequest(
                        batchAccountName, finalMode, notes, performedBy, category, null, contraTarget
                ));
                if (saveRule) {
                    try {
                        api.createRule(new BankStatementApiClient.RuleSaveRequest(
                                category + " (" + pattern + ")", "NARRATION", "CONTAINS", pattern,
                                credit ? "CREDIT" : "DEBIT", "BANK_ENTRY", null, batchAccountName, finalMode, notes, execMode, 100, true
                        ));
                    } catch (Exception ignored) {
                    }
                }
                return res;
            }, res -> {
                AppDialogService.success(owner, "Bank Entry Reconciled", res.message() + (saveRule ? "\n\nSaved Smart Rule for \"" + pattern + "\"" : ""));
                if (onSuccess != null) onSuccess.run();
            }, err -> AppDialogService.error(owner, "Bank Entry Failed", err.getMessage()));
        }
    }

    public static void showQuickPartyAdvance(
            Node owner,
            BankStatementApiClient api,
            BankStatementApiClient.TransactionDto tx,
            String batchAccountName,
            List<String> paymentModes,
            String performedBy,
            Runnable onSuccess) {
        boolean credit = tx.credit() > 0 && tx.debit() <= 0;
        double amt = credit ? tx.credit() : tx.debit();
        String partyType = credit ? "CUSTOMER" : "SUPPLIER";
        String roleLabel = credit ? "Customer" : "Supplier";
        var parsed = BankNarrationParser.parse(tx.description(), tx.reference());

        UiTaskExecutor.submitLatest("bank-statement-party-load-" + partyType, () -> {
            List<Party> parties = new PartyService().getByType(partyType);
            return parties == null ? List.<Party>of() : parties.stream().filter(Party::isActive).toList();
        }, parties -> {
            if (parties.isEmpty()) {
                AppDialogService.warning(owner, roleLabel + " Advance", "No Active " + roleLabel + " Found", "Create at least one active " + roleLabel + " before recording an advance receipt/payment.");
                return;
            }

            OwnedDialog<Boolean> dialog = new OwnedDialog<>(owner);
            dialog.setTitle(credit ? "Record Customer Advance Receipt" : "Record Supplier Advance Payment");
            dialog.setResizable(true);
            AppDialogRenderer.configureWorkspace(
                    dialog,
                    credit ? "credit" : "debit",
                    credit ? "Customer Advance Receipt" : "Supplier Advance Payment",
                    (credit ? "Receipt + ₹ " : "Payment - ₹ ") + money(amt),
                    safe(tx.statementReference()) + "  •  " + BusinessClock.formatDate(tx.transactionDate()),
                    ""
            );

            HBox metrics = PopupTableWorkspace.metricStrip(
                    PopupTableWorkspace.metricCard(credit ? "Advance Received" : "Advance Paid", "₹ " + money(amt), credit ? "credit" : "debit"),
                    PopupTableWorkspace.metricCard("Channel", parsed.hasChannel() ? parsed.channel() : "BANK", "bank"),
                    PopupTableWorkspace.metricCard("Ref / UTR", parsed.hasUtrOrRef() ? parsed.utrOrRef() : safe(tx.reference()), "reference")
            );

            ComboBox<PartyChoice> cmbParty = new ComboBox<>();
            cmbParty.setMaxWidth(Double.MAX_VALUE);
            cmbParty.setPromptText("Search " + roleLabel + " *");
            String descLower = safe(tx.description()).toLowerCase(Locale.ROOT);
            PartyChoice bestMatch = null;
            for (Party p : parties) {
                PartyChoice item = new PartyChoice(p.getId(), p.getPartyCode(), p.getName(), p.getGstin());
                cmbParty.getItems().add(item);
                if (bestMatch == null && p.getName() != null && p.getName().length() >= 4
                        && descLower.contains(p.getName().toLowerCase(Locale.ROOT))) {
                    bestMatch = item;
                }
            }
            if (bestMatch != null) cmbParty.setValue(bestMatch);

            ComboBox<String> cmbMode = new ComboBox<>(FXCollections.observableArrayList(paymentModes == null ? List.of() : paymentModes));
            cmbMode.setEditable(true);
            String defaultMode = parsed.hasChannel() && !"OTHER".equals(parsed.channel())
                    ? parsed.channel()
                    : (paymentModes != null && !paymentModes.isEmpty() ? paymentModes.getFirst() : "Bank Transfer");
            if (!cmbMode.getItems().contains(defaultMode)) cmbMode.getItems().add(0, defaultMode);
            cmbMode.setValue(defaultMode);
            cmbMode.setMaxWidth(Double.MAX_VALUE);

            TextArea txtNotes = new TextArea((credit ? "Advance received from customer: " : "Advance paid to supplier: ") + safe(tx.description()));
            txtNotes.setPrefRowCount(3);
            txtNotes.setWrapText(true);
            txtNotes.getStyleClass().add("input-field");

            GridPane grid = buildFormGrid();
            int row = 0;
            addField(grid, row++, roleLabel + " *", "customer", cmbParty);
            addField(grid, row++, "Payment Mode", "payment", cmbMode);
            addField(grid, row++, "Voucher Notes", "notes", txtNotes);

            VBox content = new VBox(12, metrics, grid);
            content.setPadding(new Insets(12));
            content.setMinWidth(600);
            dialog.getDialogPane().setContent(content);
            SearchableComboBoxHelper.makeSearchable(cmbParty, PartyChoice::toString);
            SearchableComboBoxHelper.makeAllSearchable(content);
            PopupTableWorkspace.prepareDialog(dialog, 720);

            ButtonType saveBtnType = new ButtonType("Save Advance & Match", ButtonBar.ButtonData.OK_DONE);
            dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, saveBtnType);
            Node saveNode = dialog.getDialogPane().lookupButton(saveBtnType);
            saveNode.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
                if (cmbParty.getValue() == null) {
                    ev.consume();
                    AppDialogService.warning(dialog, roleLabel + " Required", "Select a " + roleLabel, "Please choose the " + roleLabel + " linked to this advance.");
                }
            });

            dialog.setResultConverter(bt -> saveBtnType.equals(bt));
            if (dialog.showAndWait().orElse(false)) {
                PartyChoice selectedParty = cmbParty.getValue();
                String mode = cmbMode.isEditable() ? safe(cmbMode.getEditor().getText()) : safe(cmbMode.getValue());
                if (mode.isBlank()) mode = "Bank Transfer";
                String category = credit ? "Customer Advance" : "Supplier Advance";
                String notes = selectedParty.name() + " • " + safe(txtNotes.getText());
                final String finalMode = mode;

                UiTaskExecutor.submitSerial("bank-statement-quick-advance-" + tx.id(), () -> api.bankEntry(
                        tx.id(),
                        new BankStatementApiClient.BankEntryRequest(
                                batchAccountName, finalMode, notes, performedBy, category, selectedParty.id(), null
                        )
                ), res -> {
                    AppDialogService.success(owner, category + " Recorded", res.message() + "\n\nParty: " + selectedParty.name() + "\nAmount: ₹ " + money(amt));
                    if (onSuccess != null) onSuccess.run();
                }, err -> AppDialogService.error(owner, category + " Failed", err.getMessage()));
            }
        }, err -> AppDialogService.error(owner, "Could not load " + roleLabel + "s", err.getMessage()));
    }

    private record PartyChoice(int id, String code, String name, String gstin) {
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            if (code != null && !code.isBlank()) sb.append("[").append(code).append("] ");
            sb.append(name == null ? "" : name);
            if (gstin != null && !gstin.isBlank()) sb.append(" (").append(gstin).append(")");
            return sb.toString();
        }
    }

    private static GridPane buildFormGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(12);
        ColumnConstraints c0 = new ColumnConstraints(170, 185, 210);
        c0.setHgrow(Priority.NEVER);
        ColumnConstraints c1 = new ColumnConstraints(360, 420, Double.MAX_VALUE);
        c1.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().setAll(c0, c1);
        return grid;
    }

    private static void addField(GridPane grid, int row, String caption, String semantic, Node control) {
        Label label = new Label(caption);
        label.setMinWidth(Region.USE_PREF_SIZE);
        label.setGraphic(IconFactory.compactIcon(semantic, 14));
        label.getStyleClass().add("finance-field-label");
        grid.addRow(row, label, control);
        GridPane.setHgrow(control, Priority.ALWAYS);
    }

    private static String safe(String s) { return s == null ? "" : s.trim(); }
    private static String money(double v) { return String.format(Locale.ENGLISH, "%,.2f", v); }
}
