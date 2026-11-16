package org.example.util;

import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.example.api.accounting.GeneralLedgerApiClient;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * Universal Posting Flow Inspector Dialog for Jasvi-Desktop.
 * Provides transparent, 360-degree audit visibility across:
 *   1. Double-Entry General Ledger Journal Voucher (Debits & Credits)
 *   2. Inventory & Stock Movement (Restock / Issue / Zero movement for rate diffs)
 *   3. Statutory GST Impact & Table Mapping (GSTR-1, GSTR-3B, GSTR-2B, Sec 15(3)(b))
 */
public final class PostingFlowDialog {

    private static final NumberFormat CURRENCY = NumberFormat.getCurrencyInstance(Locale.of("en", "IN"));

    private PostingFlowDialog() {}

    public static void show(Node owner, String documentType, String documentNo) {
        if (documentNo == null || documentNo.isBlank()) {
            AppDialogService.warning(owner, "Posting Flow", "No document selected to inspect posting flow.");
            return;
        }

        OwnedDialog<ButtonType> dialog = new OwnedDialog<>(owner);
        dialog.setTitle("Posting Flow Inspector — " + documentNo);
        dialog.setHeaderText(null);
        dialog.getDialogPane().getStyleClass().addAll("approved-card", "invoice-payment-dialog");

        javafx.geometry.Rectangle2D screen = WindowUtilsFx.visualBoundsFor(owner);
        double maxW = Math.max(840, screen.getWidth() - 60);
        double maxH = Math.max(540, screen.getHeight() - 60);
        double targetW = Math.min(maxW, Math.max(860, screen.getWidth() * 0.82));
        double targetH = Math.min(maxH, Math.max(520, screen.getHeight() * 0.80));
        dialog.getDialogPane().setPrefSize(targetW, targetH);
        dialog.getDialogPane().setMaxWidth(maxW);
        dialog.getDialogPane().setMaxHeight(maxH);

        dialog.setOnShown(ev -> {
            if (dialog.getDialogPane().getScene() != null && dialog.getDialogPane().getScene().getWindow() instanceof javafx.stage.Stage stage) {
                WindowUtilsFx.fitDialogToOwnerScreen(stage, owner);
            }
        });

        // Hero Header
        Label title = new Label("Posting Flow & Accounting Drill-Down");
        title.getStyleClass().add("bank-dialog-hero-title");
        Label subtitle = new Label("Document: " + documentNo + " (" + documentType + ")  •  Loading real-time posting state…");
        subtitle.getStyleClass().add("bank-dialog-subtitle");

        HBox hero = new HBox(12, IconFactory.icon("status", 32), new VBox(3, title, subtitle));
        hero.setAlignment(Pos.CENTER_LEFT);
        hero.getStyleClass().add("bank-dialog-hero");
        DocumentInspectionScaffold.installDragPhysics(hero, dialog);

        // Metrics Banner
        GridPane metrics = new GridPane();
        metrics.setHgap(12);
        metrics.setPadding(new Insets(6, 0, 8, 0));
        Label valTotal = new Label("Loading…");
        valTotal.getStyleClass().addAll("kpi-value", "erp-table-value-colour-blue");
        Label valProfile = new Label("Analyzing…");
        valProfile.getStyleClass().addAll("kpi-value", "erp-table-value-colour-orange");
        Label valVoucher = new Label("Pending…");
        valVoucher.getStyleClass().addAll("kpi-value", "erp-table-value-colour-purple");
        Label valBalanced = new Label("Checking…");
        valBalanced.getStyleClass().addAll("kpi-value", "erp-table-value-colour-green");

        metrics.add(metricCard("Document Total", valTotal, "currency"), 0, 0);
        metrics.add(metricCard("Posting Profile", valProfile, "status"), 1, 0);
        metrics.add(metricCard("Journal Voucher", valVoucher, "document"), 2, 0);
        metrics.add(metricCard("Ledger Integrity", valBalanced, "complete"), 3, 0);
        for (Node child : metrics.getChildren()) {
            GridPane.setHgrow(child, Priority.ALWAYS);
            if (child instanceof Region r) r.setMaxWidth(Double.MAX_VALUE);
        }

        // Tab 1: Double-Entry Journal
        TableView<GeneralLedgerApiClient.PostingFlowVoucherLine> journalTable = new TableView<>();
        journalTable.getStyleClass().addAll("approved-table", "erp-table-profile-dialog");
        journalTable.setPlaceholder(new Label("Loading General Ledger journal lines…"));

        TableColumn<GeneralLedgerApiClient.PostingFlowVoucherLine, String> colAccCode = new TableColumn<>("Account Code");
        colAccCode.setCellValueFactory(v -> new ReadOnlyStringWrapper(v.getValue().accountCode()));
        IconFactory.applyTableHeaderIcon(colAccCode, "identity");

        TableColumn<GeneralLedgerApiClient.PostingFlowVoucherLine, String> colAccName = new TableColumn<>("Account Name");
        colAccName.setCellValueFactory(v -> new ReadOnlyStringWrapper(v.getValue().accountName()));
        IconFactory.applyTableHeaderIcon(colAccName, "ledger");

        TableColumn<GeneralLedgerApiClient.PostingFlowVoucherLine, String> colDebit = new TableColumn<>("Debit (Dr)");
        colDebit.setCellValueFactory(v -> new ReadOnlyStringWrapper(formatDebit(v.getValue().debitAmount())));
        colDebit.setCellFactory(col -> styledCell("erp-table-value-colour-green", Pos.CENTER_RIGHT));
        IconFactory.applyTableHeaderIcon(colDebit, "currency");

        TableColumn<GeneralLedgerApiClient.PostingFlowVoucherLine, String> colCredit = new TableColumn<>("Credit (Cr)");
        colCredit.setCellValueFactory(v -> new ReadOnlyStringWrapper(formatCredit(v.getValue().creditAmount())));
        colCredit.setCellFactory(col -> styledCell("erp-table-value-colour-orange", Pos.CENTER_RIGHT));
        IconFactory.applyTableHeaderIcon(colCredit, "currency");

        TableColumn<GeneralLedgerApiClient.PostingFlowVoucherLine, String> colNarration = new TableColumn<>("Line Narration / Remarks");
        colNarration.setCellValueFactory(v -> new ReadOnlyStringWrapper(v.getValue().narration()));
        IconFactory.applyTableHeaderIcon(colNarration, "notes");

        journalTable.getColumns().addAll(colAccCode, colAccName, colDebit, colCredit, colNarration);
        DynamicTableLayoutManager.install(journalTable);

        Label lblVoucherSummary = new Label("Total Debit: ₹ 0.00  |  Total Credit: ₹ 0.00");
        lblVoucherSummary.getStyleClass().addAll("status-badge", "erp-status-summary");
        VBox tabJournalContent = new VBox(8, journalTable, lblVoucherSummary);
        VBox.setVgrow(journalTable, Priority.ALWAYS);
        tabJournalContent.setPadding(new Insets(10));

        // Tab 2: Inventory & Stock Movement
        TableView<GeneralLedgerApiClient.PostingFlowInventoryLine> invTable = new TableView<>();
        invTable.getStyleClass().addAll("approved-table", "erp-table-profile-dialog");
        invTable.setPlaceholder(new Label("No inventory movements associated with this transaction."));

        TableColumn<GeneralLedgerApiClient.PostingFlowInventoryLine, String> colItemCode = new TableColumn<>("Item Code");
        colItemCode.setCellValueFactory(v -> new ReadOnlyStringWrapper(v.getValue().itemCode()));
        IconFactory.applyTableHeaderIcon(colItemCode, "identity");

        TableColumn<GeneralLedgerApiClient.PostingFlowInventoryLine, String> colItemDesc = new TableColumn<>("Description");
        colItemDesc.setCellValueFactory(v -> new ReadOnlyStringWrapper(v.getValue().description()));
        IconFactory.applyTableHeaderIcon(colItemDesc, "item");

        TableColumn<GeneralLedgerApiClient.PostingFlowInventoryLine, String> colMovementType = new TableColumn<>("Movement Direction");
        colMovementType.setCellValueFactory(v -> new ReadOnlyStringWrapper(v.getValue().movementType()));
        colMovementType.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(item);
                    if (item.contains("INWARD")) {
                        setStyleClass("erp-table-value-colour-green");
                        setGraphic(IconFactory.compactIcon("item", 14));
                    } else if (item.contains("OUTWARD")) {
                        setStyleClass("erp-table-value-colour-orange");
                        setGraphic(IconFactory.compactIcon("item", 14));
                    } else {
                        setStyleClass("erp-table-value-colour-purple");
                        setGraphic(IconFactory.compactIcon("notes", 14));
                    }
                }
            }
            private void setStyleClass(String cls) {
                getStyleClass().removeAll("erp-table-value-colour-green", "erp-table-value-colour-orange", "erp-table-value-colour-purple");
                getStyleClass().add(cls);
            }
        });
        IconFactory.applyTableHeaderIcon(colMovementType, "return");

        TableColumn<GeneralLedgerApiClient.PostingFlowInventoryLine, String> colQty = new TableColumn<>("Quantity");
        colQty.setCellValueFactory(v -> new ReadOnlyStringWrapper(v.getValue().quantity() + " " + v.getValue().unit()));
        IconFactory.applyTableHeaderIcon(colQty, "quantity");

        TableColumn<GeneralLedgerApiClient.PostingFlowInventoryLine, String> colUnitCost = new TableColumn<>("Unit Valuation");
        colUnitCost.setCellValueFactory(v -> new ReadOnlyStringWrapper(formatMoney(v.getValue().unitCost())));
        IconFactory.applyTableHeaderIcon(colUnitCost, "currency");

        TableColumn<GeneralLedgerApiClient.PostingFlowInventoryLine, String> colValuation = new TableColumn<>("Total Valuation Delta");
        colValuation.setCellValueFactory(v -> new ReadOnlyStringWrapper(formatMoney(v.getValue().totalValuation())));
        IconFactory.applyTableHeaderIcon(colValuation, "currency");

        invTable.getColumns().addAll(colItemCode, colItemDesc, colMovementType, colQty, colUnitCost, colValuation);
        DynamicTableLayoutManager.install(invTable);

        Label lblInventoryNote = new Label("Inventory Policy: Physical returns reinstate FIFO valuation; Rate differences and Commercial rebates enforce ZERO stock movement.");
        lblInventoryNote.getStyleClass().add("filter-chip");
        VBox tabInvContent = new VBox(8, invTable, lblInventoryNote);
        VBox.setVgrow(invTable, Priority.ALWAYS);
        tabInvContent.setPadding(new Insets(10));

        // Tab 3: Statutory GST Impact
        VBox tabGstContent = new VBox(12);
        tabGstContent.setPadding(new Insets(16));
        tabGstContent.getStyleClass().add("approved-surface");

        Label lblGstReturnForm = new Label("Target Statutory Return: —");
        lblGstReturnForm.getStyleClass().addAll("kpi-value", "erp-table-value-colour-blue");
        Label lblGstTable = new Label("Reporting Table / Schedule: —");
        lblGstTable.getStyleClass().addAll("kpi-value", "erp-table-value-colour-purple");
        Label lblGstRule = new Label("Statutory Provision: —");
        lblGstRule.setWrapText(true);
        lblGstRule.getStyleClass().add("approved-lead");

        GridPane gstBreakdown = new GridPane();
        gstBreakdown.setHgap(16);
        gstBreakdown.setVgap(10);
        Label valTaxable = new Label("₹ 0.00");
        valTaxable.getStyleClass().addAll("kpi-value", "erp-table-value-colour-blue");
        Label valCgst = new Label("₹ 0.00");
        valCgst.getStyleClass().addAll("kpi-value", "erp-table-value-colour-green");
        Label valSgst = new Label("₹ 0.00");
        valSgst.getStyleClass().addAll("kpi-value", "erp-table-value-colour-green");
        Label valIgst = new Label("₹ 0.00");
        valIgst.getStyleClass().addAll("kpi-value", "erp-table-value-colour-orange");
        Label valTotalTax = new Label("₹ 0.00");
        valTotalTax.getStyleClass().addAll("kpi-value", "erp-table-value-colour-purple");

        gstBreakdown.add(metricCard("Taxable Amount", valTaxable, "currency"), 0, 0);
        gstBreakdown.add(metricCard("CGST", valCgst, "tax"), 1, 0);
        gstBreakdown.add(metricCard("SGST", valSgst, "tax"), 2, 0);
        gstBreakdown.add(metricCard("IGST", valIgst, "tax"), 3, 0);
        gstBreakdown.add(metricCard("Total GST Liability / Credit", valTotalTax, "currency"), 4, 0);

        tabGstContent.getChildren().addAll(
                new HBox(8, IconFactory.icon("tax", 20), lblGstReturnForm),
                new HBox(8, IconFactory.icon("document", 20), lblGstTable),
                gstBreakdown,
                new Separator(),
                new HBox(8, IconFactory.icon("notes", 20), lblGstRule)
        );

        // TabPane Assembly
        TabPane tabPane = new TabPane();
        tabPane.getStyleClass().add("approved-tab-pane");

        Tab tabJournal = new Tab("Double-Entry Journal (GL)", tabJournalContent);
        tabJournal.setGraphic(IconFactory.compactIcon("payment", 16));
        tabJournal.setClosable(false);

        Tab tabInventory = new Tab("Inventory Movement", tabInvContent);
        tabInventory.setGraphic(IconFactory.compactIcon("item", 16));
        tabInventory.setClosable(false);

        Tab tabGst = new Tab("Statutory GST Impact", tabGstContent);
        tabGst.setGraphic(IconFactory.compactIcon("tax", 16));
        tabGst.setClosable(false);

        tabPane.getTabs().addAll(tabJournal, tabInventory, tabGst);

        // Explanatory Bottom Strip
        Label summaryText = new Label("Analyzing transaction flow…");
        summaryText.setWrapText(true);
        summaryText.getStyleClass().add("approved-lead");
        HBox bottomBanner = new HBox(8, IconFactory.compactIcon("notes", 16), summaryText);
        bottomBanner.setAlignment(Pos.CENTER_LEFT);
        bottomBanner.setPadding(new Insets(8, 12, 8, 12));
        bottomBanner.getStyleClass().add("approved-filter-bar");

        VBox layout = new VBox(10, hero, metrics, tabPane, bottomBanner);
        layout.setPadding(new Insets(14));
        VBox.setVgrow(tabPane, Priority.ALWAYS);
        dialog.getDialogPane().setContent(layout);

        ButtonType closeBtn = new ButtonType("Close", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().add(closeBtn);

        // Fetch Live Posting Flow Data from Spring Boot backend
        UiTaskExecutor.submitLatest("posting-flow-" + documentNo, () -> {
            GeneralLedgerApiClient client = new GeneralLedgerApiClient();
            return client.postingFlow(documentType, documentNo);
        }, resp -> {
            if (resp == null) {
                subtitle.setText("Document: " + documentNo + "  •  No posting data returned.");
                return;
            }
            subtitle.setText(documentNo + "  •  " + resp.partyName() + " (" + (resp.partyGstin() != null && !resp.partyGstin().isBlank() ? resp.partyGstin() : "Unregistered") + ")  •  " + resp.documentDate());
            valTotal.setText(formatMoney(resp.totalAmount()));
            valProfile.setText(formatProfile(resp.postingProfile()));
            valVoucher.setText(resp.journalVoucherNo() != null ? resp.journalVoucherNo() : "PROSPECTIVE");
            valBalanced.setText(resp.isBalanced() ? "✓ BALANCED" : "⚠️ UNBALANCED");

            summaryText.setText(resp.summaryDescription() != null ? resp.summaryDescription() : "Posting flow processed successfully.");

            // Populate Journal Table
            if (resp.journalLines() != null) {
                journalTable.getItems().setAll(resp.journalLines());
            }
            lblVoucherSummary.setText("Total Debits: " + formatMoney(resp.totalDebit()) + "  |  Total Credits: " + formatMoney(resp.totalCredit()) + (resp.isBalanced() ? "  [BALANCED]" : "  [DISCREPANCY]"));

            // Populate Inventory Table
            if (resp.inventoryMovements() != null) {
                invTable.getItems().setAll(resp.inventoryMovements());
            }

            // Populate GST Tab
            if (resp.gstImpact() != null) {
                GeneralLedgerApiClient.PostingFlowGstImpact g = resp.gstImpact();
                lblGstReturnForm.setText("Target Statutory Return: " + g.returnForm());
                lblGstTable.setText("Reporting Table / Schedule: " + g.tableSection());
                lblGstRule.setText("Statutory Rule: " + g.statutoryRule());
                valTaxable.setText(formatMoney(g.taxableAmount()));
                valCgst.setText(formatMoney(g.cgstAmount()));
                valSgst.setText(formatMoney(g.sgstAmount()));
                valIgst.setText(formatMoney(g.igstAmount()));
                valTotalTax.setText(formatMoney(g.totalTax()));
            }
        }, err -> {
            subtitle.setText("Could not load posting flow: " + err.getMessage());
            AppDialogService.error(owner, "Posting Flow Failed", "Unable to load posting flow details", err.getMessage());
        });

        dialog.showAndWait();
    }

    private static VBox metricCard(String title, Label valNode, String iconSemantic) {
        Label lblTitle = new Label(title);
        lblTitle.getStyleClass().add("metric-title");
        lblTitle.setGraphic(IconFactory.compactIcon(iconSemantic, 14));
        lblTitle.setGraphicTextGap(6);

        VBox card = new VBox(4, lblTitle, valNode);
        card.getStyleClass().addAll("approved-card", "metric-card");
        card.setPadding(new Insets(8, 12, 8, 12));
        card.setAlignment(Pos.CENTER_LEFT);
        return card;
    }

    private static TableCell<GeneralLedgerApiClient.PostingFlowVoucherLine, String> styledCell(String styleClass, Pos alignment) {
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

    private static String formatMoney(BigDecimal amount) {
        if (amount == null) return "₹ 0.00";
        return CURRENCY.format(amount.doubleValue()).replace("₹", "₹ ");
    }

    private static String formatDebit(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) == 0) return "—";
        return formatMoney(amount);
    }

    private static String formatCredit(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) == 0) return "—";
        return formatMoney(amount);
    }

    private static String formatProfile(String raw) {
        if (raw == null) return "STANDARD";
        return switch (raw.toUpperCase(Locale.ROOT)) {
            case "PHYSICAL_STOCK_RETURN" -> "📦 PHYSICAL RETURN";
            case "FINANCIAL_RATE_DIFFERENCE" -> "🏷️ RATE DIFFERENCE";
            case "COMMERCIAL_REBATE" -> "🎉 COMMERCIAL REBATE";
            case "STANDARD_SALES_INVOICE" -> "📄 SALES INVOICE";
            case "STANDARD_PURCHASE_BILL" -> "📑 PURCHASE BILL";
            case "PAYMENT_SETTLEMENT" -> "💳 PAYMENT SETTLEMENT";
            default -> raw.replace('_', ' ');
        };
    }
}
