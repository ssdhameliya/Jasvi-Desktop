package org.example.util;

import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.example.api.accounting.GeneralLedgerApiClient;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Universal Posting Flow Inspector Dialog for Jasvi-Desktop.
 * Provides transparent, 360-degree audit visibility across:
 *   1. Double-Entry General Ledger Journal Voucher (Debits & Credits)
 *   2. Inventory & Stock Movement (Opening Stock, Movement Qty, Closing Stock, Average Unit Cost, Selling Rate, Line Revenue, Item Profit, Margin %)
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
        double maxW = Math.max(1200, screen.getWidth() - 40);
        double maxH = Math.max(680, screen.getHeight() - 40);
        double targetW = Math.min(maxW, Math.max(1480, screen.getWidth() * 0.94));
        double targetH = Math.min(maxH, Math.max(760, screen.getHeight() * 0.88));
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

        // 5-Phase End-to-End Deal Progression Timeline
        HBox dealStepper = new HBox(8);
        dealStepper.setAlignment(Pos.CENTER_LEFT);
        dealStepper.setPadding(new Insets(2, 0, 4, 0));

        Label valStep1 = new Label("Detecting…");
        Label badgeStep1 = new Label("Checking Origin");
        valStep1.getStyleClass().addAll("kpi-value", "erp-table-value-colour-blue");
        badgeStep1.getStyleClass().addAll("status-badge", "erp-status-summary");

        Label valStep2 = new Label("Loading…");
        Label badgeStep2 = new Label("Document Verification");
        valStep2.getStyleClass().addAll("kpi-value", "erp-table-value-colour-blue");
        badgeStep2.getStyleClass().addAll("status-badge", "erp-status-summary");

        Label valStep3 = new Label("Calculating…");
        Label badgeStep3 = new Label("Inventory Movement");
        valStep3.getStyleClass().addAll("kpi-value", "erp-table-value-colour-orange");
        badgeStep3.getStyleClass().addAll("status-badge", "erp-status-summary");

        Label valStep4 = new Label("Checking…");
        Label badgeStep4 = new Label("Payment Status");
        valStep4.getStyleClass().addAll("kpi-value", "erp-table-value-colour-green");
        badgeStep4.getStyleClass().addAll("status-badge", "erp-status-summary");

        Label valStep5 = new Label("Validating…");
        Label badgeStep5 = new Label("Ledger & Statutory");
        valStep5.getStyleClass().addAll("kpi-value", "erp-table-value-colour-purple");
        badgeStep5.getStyleClass().addAll("status-badge", "erp-status-summary");

        dealStepper.getChildren().addAll(
                stepperCard("1", "Deal Origin", valStep1, badgeStep1, "document"),
                stepperCard("2", "Document Execution", valStep2, badgeStep2, "status"),
                stepperCard("3", "Inventory Movement", valStep3, badgeStep3, "item"),
                stepperCard("4", "Settlement / Payment", valStep4, badgeStep4, "payment"),
                stepperCard("5", "Statutory & Ledger", valStep5, badgeStep5, "complete")
        );

        // 7-Card Semantic Metrics Banner
        GridPane metrics = new GridPane();
        metrics.setHgap(10);
        metrics.setPadding(new Insets(6, 0, 8, 0));

        Label valTotal = new Label("Loading…");
        valTotal.getStyleClass().addAll("kpi-value", "erp-table-value-colour-blue");
        Label valRevenue = new Label("Loading…");
        valRevenue.getStyleClass().addAll("kpi-value", "erp-table-value-colour-purple");
        Label valCost = new Label("Loading…");
        valCost.getStyleClass().addAll("kpi-value", "erp-table-value-colour-orange");
        Label valDiscount = new Label("Calculating…");
        valDiscount.getStyleClass().addAll("kpi-value", "erp-table-value-colour-orange");
        Label valProfit = new Label("Calculating…");
        valProfit.getStyleClass().addAll("kpi-value", "erp-table-value-colour-green");
        Label valMargin = new Label("Calculating…");
        valMargin.getStyleClass().addAll("kpi-value", "erp-table-value-colour-cyan");
        Label valBalanced = new Label("Checking…");
        valBalanced.getStyleClass().addAll("kpi-value", "erp-table-value-colour-green");

        metrics.add(metricCard("Document Total", valTotal, "currency"), 0, 0);
        metrics.add(metricCard("Taxable Revenue", valRevenue, "currency"), 1, 0);
        metrics.add(metricCard("Total Cost (COGS)", valCost, "item"), 2, 0);
        metrics.add(metricCard("Order Discount", valDiscount, "status"), 3, 0);
        metrics.add(metricCard("Total Order Profit", valProfit, "currency"), 4, 0);
        metrics.add(metricCard("Gross Margin", valMargin, "status"), 5, 0);
        metrics.add(metricCard("Ledger Integrity", valBalanced, "complete"), 6, 0);

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
        Label lblPartyBalance = new Label("Party Settlement Status: Checking…");
        lblPartyBalance.getStyleClass().addAll("status-badge", "erp-status-summary");
        HBox journalFooter = new HBox(12, lblVoucherSummary, lblPartyBalance);
        journalFooter.setAlignment(Pos.CENTER_LEFT);
        VBox tabJournalContent = new VBox(8, journalTable, journalFooter);
        VBox.setVgrow(journalTable, Priority.ALWAYS);
        tabJournalContent.setPadding(new Insets(10));

        // Tab 2: Inventory & Stock Movement (Multi-Item Detailed Profit & Valuation Analysis)
        TableView<GeneralLedgerApiClient.PostingFlowInventoryLine> invTable = new TableView<>();
        invTable.getStyleClass().addAll("approved-table", "erp-table-profile-dialog");
        invTable.setPlaceholder(new Label("No inventory movements associated with this transaction."));

        TableColumn<GeneralLedgerApiClient.PostingFlowInventoryLine, String> colItemCode = new TableColumn<>("Item Code");
        colItemCode.setCellValueFactory(v -> new ReadOnlyStringWrapper(v.getValue().itemCode()));
        colItemCode.setCellFactory(col -> styledCell("erp-table-value-colour-purple", Pos.CENTER_LEFT));
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

        TableColumn<GeneralLedgerApiClient.PostingFlowInventoryLine, String> colStockBefore = new TableColumn<>("Stock Before Order");
        colStockBefore.setCellValueFactory(v -> new ReadOnlyStringWrapper(formatStock(v.getValue().stockBefore(), v.getValue().unit())));
        colStockBefore.setCellFactory(col -> styledCell("erp-table-value-colour-blue", Pos.CENTER_RIGHT));
        IconFactory.applyTableHeaderIcon(colStockBefore, "inventory");

        TableColumn<GeneralLedgerApiClient.PostingFlowInventoryLine, String> colQty = new TableColumn<>("Order Qty");
        colQty.setCellValueFactory(v -> new ReadOnlyStringWrapper(formatQty(v.getValue().quantity(), v.getValue().unit())));
        colQty.setCellFactory(col -> styledCell("erp-table-value-colour-orange", Pos.CENTER_RIGHT));
        IconFactory.applyTableHeaderIcon(colQty, "quantity");

        TableColumn<GeneralLedgerApiClient.PostingFlowInventoryLine, String> colStockAfter = new TableColumn<>("Stock After Order");
        colStockAfter.setCellValueFactory(v -> new ReadOnlyStringWrapper(formatStock(v.getValue().stockAfter(), v.getValue().unit())));
        colStockAfter.setCellFactory(col -> styledCell("erp-table-value-colour-green", Pos.CENTER_RIGHT));
        IconFactory.applyTableHeaderIcon(colStockAfter, "inventory");

        TableColumn<GeneralLedgerApiClient.PostingFlowInventoryLine, String> colUnitCost = new TableColumn<>("Average Unit Cost");
        colUnitCost.setCellValueFactory(v -> new ReadOnlyStringWrapper(formatMoney(v.getValue().unitCost())));
        colUnitCost.setCellFactory(col -> styledCell("erp-table-value-colour-orange", Pos.CENTER_RIGHT));
        IconFactory.applyTableHeaderIcon(colUnitCost, "currency");

        TableColumn<GeneralLedgerApiClient.PostingFlowInventoryLine, String> colGrossRate = new TableColumn<>("Gross List Rate");
        colGrossRate.setCellValueFactory(v -> new ReadOnlyStringWrapper(formatMoney(v.getValue().grossRate())));
        colGrossRate.setCellFactory(col -> styledCell("erp-table-value-colour-purple", Pos.CENTER_RIGHT));
        IconFactory.applyTableHeaderIcon(colGrossRate, "currency");

        TableColumn<GeneralLedgerApiClient.PostingFlowInventoryLine, String> colDiscount = new TableColumn<>("Discount (% / ₹)");
        colDiscount.setCellValueFactory(v -> new ReadOnlyStringWrapper(formatDiscount(v.getValue().discountPercent(), v.getValue().discountAmount())));
        colDiscount.setCellFactory(col -> styledCell("erp-table-value-colour-orange", Pos.CENTER_RIGHT));
        IconFactory.applyTableHeaderIcon(colDiscount, "status");

        TableColumn<GeneralLedgerApiClient.PostingFlowInventoryLine, String> colRate = new TableColumn<>("Net Selling Rate");
        colRate.setCellValueFactory(v -> new ReadOnlyStringWrapper(formatMoney(v.getValue().sellingRate())));
        colRate.setCellFactory(col -> styledCell("erp-table-value-colour-blue", Pos.CENTER_RIGHT));
        IconFactory.applyTableHeaderIcon(colRate, "currency");

        TableColumn<GeneralLedgerApiClient.PostingFlowInventoryLine, String> colValuation = new TableColumn<>("Total Valuation Delta");
        colValuation.setCellValueFactory(v -> new ReadOnlyStringWrapper(formatMoney(v.getValue().totalValuation())));
        colValuation.setCellFactory(col -> styledCell("erp-table-value-colour-orange", Pos.CENTER_RIGHT));
        IconFactory.applyTableHeaderIcon(colValuation, "currency");

        TableColumn<GeneralLedgerApiClient.PostingFlowInventoryLine, String> colRevenue = new TableColumn<>("Line Revenue");
        colRevenue.setCellValueFactory(v -> new ReadOnlyStringWrapper(formatMoney(v.getValue().lineRevenue())));
        colRevenue.setCellFactory(col -> styledCell("erp-table-value-colour-purple", Pos.CENTER_RIGHT));
        IconFactory.applyTableHeaderIcon(colRevenue, "currency");

        TableColumn<GeneralLedgerApiClient.PostingFlowInventoryLine, String> colProfit = new TableColumn<>("Item Profit (₹)");
        colProfit.setCellValueFactory(v -> new ReadOnlyStringWrapper(formatProfit(v.getValue().lineProfit())));
        colProfit.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(item);
                    setAlignment(Pos.CENTER_RIGHT);
                    getStyleClass().removeAll("erp-table-value-colour-green", "erp-table-value-colour-red", "erp-table-value-colour-blue");
                    if (item.startsWith("-") || item.contains("(-)")) {
                        getStyleClass().add("erp-table-value-colour-red");
                    } else if (item.equals("—") || item.equals("₹ 0.00")) {
                        getStyleClass().add("erp-table-value-colour-blue");
                    } else {
                        getStyleClass().add("erp-table-value-colour-green");
                    }
                }
            }
        });
        IconFactory.applyTableHeaderIcon(colProfit, "currency");

        TableColumn<GeneralLedgerApiClient.PostingFlowInventoryLine, String> colMargin = new TableColumn<>("Margin (%)");
        colMargin.setCellValueFactory(v -> new ReadOnlyStringWrapper(formatMargin(v.getValue().profitMarginPercent())));
        colMargin.setCellFactory(col -> styledCell("erp-table-value-colour-cyan", Pos.CENTER_RIGHT));
        IconFactory.applyTableHeaderIcon(colMargin, "status");

        invTable.getColumns().addAll(
                colItemCode, colItemDesc, colMovementType,
                colStockBefore, colQty, colStockAfter,
                colUnitCost, colGrossRate, colDiscount, colRate, colValuation,
                colRevenue, colProfit, colMargin
        );
        DynamicTableLayoutManager.install(invTable);

        Label lblInventoryNote = new Label("Perpetual Valuation: Average Unit Cost calculated from weighted moving purchase batches; Stock Before / After reflects opening inventory before order execution and closing inventory post-posting.");
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

        Tab tabInventory = new Tab("Inventory Movement & Margin Drill-Down", tabInvContent);
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

        VBox layout = new VBox(10, hero, dealStepper, metrics, tabPane, bottomBanner);
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
            String partyGstin = (resp.partyGstin() != null && !resp.partyGstin().isBlank()) ? resp.partyGstin() : "Unregistered";
            String jvDisplay = resp.journalVoucherNo() != null ? resp.journalVoucherNo() : "PROSPECTIVE";
            String profileDisplay = formatProfile(resp.postingProfile());
            String auditStamp = "";
            if (resp.postedAt() != null) {
                String dtFormatted = resp.postedAt().format(DateTimeFormatter.ofPattern(BusinessClock.datePattern() + " " + BusinessClock.timePattern(), Locale.getDefault()));
                auditStamp = "  •  🕒 Posted: " + dtFormatted + " by " + (resp.postedBy() != null ? resp.postedBy() : "admin");
            }

            subtitle.setText(documentNo + "  •  " + resp.partyName() + " (" + partyGstin + ")  •  " + resp.documentDate() + "  •  " + profileDisplay + "  •  JV: " + jvDisplay + auditStamp);

            valTotal.setText(formatMoney(resp.totalAmount()));
            valRevenue.setText(formatMoney(resp.totalTaxableRevenue()));
            valCost.setText(formatMoney(resp.totalInventoryCost()));

            if (resp.totalDiscount() != null && resp.totalDiscount().compareTo(BigDecimal.ZERO) > 0) {
                valDiscount.setText("- " + formatMoney(resp.totalDiscount()));
                valDiscount.getStyleClass().removeAll("erp-table-value-colour-orange", "erp-table-value-colour-blue");
                valDiscount.getStyleClass().add("erp-table-value-colour-orange");
            } else {
                valDiscount.setText("₹ 0.00");
                valDiscount.getStyleClass().removeAll("erp-table-value-colour-orange", "erp-table-value-colour-blue");
                valDiscount.getStyleClass().add("erp-table-value-colour-blue");
            }

            // Total Order Profit styling
            BigDecimal profit = resp.totalOrderProfit();
            valProfit.setText(formatProfit(profit));
            valProfit.getStyleClass().removeAll("erp-table-value-colour-green", "erp-table-value-colour-red", "erp-table-value-colour-blue");
            if (profit != null && profit.compareTo(BigDecimal.ZERO) < 0) {
                valProfit.getStyleClass().add("erp-table-value-colour-red");
            } else if (profit != null && profit.compareTo(BigDecimal.ZERO) == 0 && "PURCHASE_BILL".equalsIgnoreCase(resp.documentType())) {
                valProfit.setText("— (Inward Cost)");
                valProfit.getStyleClass().add("erp-table-value-colour-blue");
            } else {
                valProfit.getStyleClass().add("erp-table-value-colour-green");
            }

            // Gross Margin %
            BigDecimal margin = resp.orderProfitMarginPercent();
            if ("PURCHASE_BILL".equalsIgnoreCase(resp.documentType())) {
                valMargin.setText("— (Inward)");
            } else {
                valMargin.setText(formatMargin(margin));
            }

            valBalanced.setText(resp.isBalanced() ? "✓ BALANCED" : "⚠️ UNBALANCED");

            // 5-Phase End-to-End Stepper Dynamic Population
            String refNo = (resp.referenceNo() != null && !resp.referenceNo().isBlank()) ? resp.referenceNo() : "";
            boolean isSale = "SALES_INVOICE".equalsIgnoreCase(resp.documentType()) || "SALE".equalsIgnoreCase(resp.documentType());
            boolean isPurchase = "PURCHASE_BILL".equalsIgnoreCase(resp.documentType()) || "PURCHASE".equalsIgnoreCase(resp.documentType());
            boolean isReturn = resp.documentType() != null && resp.documentType().contains("RETURN");

            // 1. Step 1: Deal Origin
            if (isSale) {
                if (!refNo.isBlank()) {
                    valStep1.setText(refNo);
                    badgeStep1.setText("✓ Quotation Approved");
                    setStepColour(badgeStep1, "erp-table-value-colour-purple");
                } else {
                    valStep1.setText("Direct Sale (Spot/B2B)");
                    badgeStep1.setText("✓ Direct Deal");
                    setStepColour(badgeStep1, "erp-table-value-colour-blue");
                }
            } else if (isPurchase) {
                if (!refNo.isBlank()) {
                    valStep1.setText(refNo);
                    badgeStep1.setText("✓ Purchase Order (PO)");
                    setStepColour(badgeStep1, "erp-table-value-colour-purple");
                } else {
                    valStep1.setText("Direct Procurement");
                    badgeStep1.setText("✓ Direct Inward");
                    setStepColour(badgeStep1, "erp-table-value-colour-blue");
                }
            } else if (isReturn) {
                valStep1.setText(!refNo.isBlank() ? "Original: " + refNo : "Source Invoice");
                badgeStep1.setText("✓ Linked Document");
                setStepColour(badgeStep1, "erp-table-value-colour-orange");
            } else {
                valStep1.setText(!refNo.isBlank() ? refNo : "System Journal");
                badgeStep1.setText("✓ Financial Entry");
                setStepColour(badgeStep1, "erp-table-value-colour-blue");
            }

            // 2. Step 2: Document Execution
            valStep2.setText(documentNo);
            if (isSale) {
                badgeStep2.setText("✓ Tax Invoice (" + formatMoney(resp.totalAmount()) + ")");
                setStepColour(badgeStep2, "erp-table-value-colour-blue");
            } else if (isPurchase) {
                badgeStep2.setText("✓ Purchase Bill (" + formatMoney(resp.totalAmount()) + ")");
                setStepColour(badgeStep2, "erp-table-value-colour-blue");
            } else if (isReturn) {
                badgeStep2.setText("✓ Note Issued (" + formatMoney(resp.totalAmount()) + ")");
                setStepColour(badgeStep2, "erp-table-value-colour-purple");
            } else {
                badgeStep2.setText("✓ Voucher Posted (" + formatMoney(resp.totalAmount()) + ")");
                setStepColour(badgeStep2, "erp-table-value-colour-blue");
            }

            // 3. Step 3: Inventory Movement
            if (resp.inventoryMovements() != null && !resp.inventoryMovements().isEmpty()) {
                int totalItems = resp.inventoryMovements().size();
                String itemSummary = totalItems == 1 ? resp.inventoryMovements().getFirst().itemCode() : totalItems + " Warehouse Items";
                valStep3.setText(itemSummary);
                if (isSale) {
                    badgeStep3.setText("✓ Dispatched (-Stock)");
                    setStepColour(badgeStep3, "erp-table-value-colour-orange");
                } else if (isPurchase) {
                    badgeStep3.setText("✓ Received (+Stock)");
                    setStepColour(badgeStep3, "erp-table-value-colour-green");
                } else {
                    badgeStep3.setText("✓ Restocked / Adjusted");
                    setStepColour(badgeStep3, "erp-table-value-colour-cyan");
                }
            } else {
                valStep3.setText("No Inventory Impact");
                badgeStep3.setText("✓ Financial / Service");
                setStepColour(badgeStep3, "erp-table-value-colour-blue");
            }

            // 4. Step 4: Settlement / Payment
            String payStatus = (resp.paymentStatus() != null && !resp.paymentStatus().isBlank()) ? resp.paymentStatus() : "SETTLED";
            BigDecimal paid = resp.paidAmount() != null ? resp.paidAmount() : BigDecimal.ZERO;
            if ("PAID".equalsIgnoreCase(payStatus) || "SETTLED".equalsIgnoreCase(payStatus)) {
                valStep4.setText(paid.compareTo(BigDecimal.ZERO) > 0 ? formatMoney(paid) : formatMoney(resp.totalAmount()));
                badgeStep4.setText("✓ Settled (Balance ₹0.00)");
                setStepColour(badgeStep4, "erp-table-value-colour-green");
                lblPartyBalance.setText("Customer Net Outstanding on Document: ₹ 0.00 (Fully Settled & Reconciled)");
                setStepColour(lblPartyBalance, "erp-table-value-colour-green");
            } else if ("PARTIAL".equalsIgnoreCase(payStatus)) {
                BigDecimal bal = resp.totalAmount().subtract(paid).max(BigDecimal.ZERO);
                valStep4.setText("Paid: " + formatMoney(paid));
                badgeStep4.setText("⚠️ Bal Due: " + formatMoney(bal));
                setStepColour(badgeStep4, "erp-table-value-colour-orange");
                lblPartyBalance.setText("Customer Net Outstanding on Document: " + formatMoney(bal) + " (Partial Settlement Recorded)");
                setStepColour(lblPartyBalance, "erp-table-value-colour-orange");
            } else {
                valStep4.setText("Unpaid (Credit Term)");
                badgeStep4.setText("⏳ AR Outstanding");
                setStepColour(badgeStep4, "erp-table-value-colour-orange");
                lblPartyBalance.setText("Customer Net Outstanding on Document: " + formatMoney(resp.totalAmount()) + " (Awaiting Settlement)");
                setStepColour(lblPartyBalance, "erp-table-value-colour-orange");
            }

            // 5. Step 5: Statutory & Ledger Postings
            valStep5.setText(jvDisplay);
            if (resp.isBalanced()) {
                badgeStep5.setText("✓ Ledger Balanced (Dr=Cr)");
                setStepColour(badgeStep5, "erp-table-value-colour-green");
            } else {
                badgeStep5.setText("⚠️ Discrepancy Detected");
                setStepColour(badgeStep5, "erp-table-value-colour-red");
            }

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
                lblGstRule.setText("Statutory Provision: " + g.statutoryRule());
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

    private static VBox stepperCard(String stepNum, String title, Label valNode, Label badgeNode, String iconSemantic) {
        Label lblTitle = new Label(stepNum + ". " + title);
        lblTitle.getStyleClass().add("metric-title");
        lblTitle.setGraphic(IconFactory.compactIcon(iconSemantic, 14));
        lblTitle.setGraphicTextGap(6);

        VBox card = new VBox(4, lblTitle, valNode, badgeNode);
        card.getStyleClass().addAll("approved-card", "metric-card");
        card.setPadding(new Insets(8, 12, 8, 12));
        card.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(card, Priority.ALWAYS);
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    private static void setStepColour(Label lbl, String colourClass) {
        lbl.getStyleClass().removeAll("erp-table-value-colour-blue", "erp-table-value-colour-green",
                "erp-table-value-colour-orange", "erp-table-value-colour-purple", "erp-table-value-colour-cyan", "erp-table-value-colour-red");
        lbl.getStyleClass().add(colourClass);
    }

    private static <T> TableCell<T, String> styledCell(String styleClass, Pos alignment) {
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

    private static String formatProfit(BigDecimal amount) {
        if (amount == null) return "—";
        if (amount.compareTo(BigDecimal.ZERO) == 0) return "₹ 0.00";
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            return "- " + formatMoney(amount.abs());
        }
        return "+ " + formatMoney(amount);
    }

    private static String formatMargin(BigDecimal margin) {
        if (margin == null || margin.compareTo(BigDecimal.ZERO) == 0) return "0.00 %";
        return String.format(Locale.of("en", "IN"), "%.2f %%", margin.doubleValue());
    }

    private static String formatDiscount(BigDecimal pct, BigDecimal amt) {
        boolean hasPct = pct != null && pct.compareTo(BigDecimal.ZERO) > 0;
        boolean hasAmt = amt != null && amt.compareTo(BigDecimal.ZERO) > 0;
        if (!hasPct && !hasAmt) return "0.00 %";
        if (hasPct && hasAmt) {
            return String.format(Locale.of("en", "IN"), "%.2f %% (-%s)", pct.doubleValue(), formatMoney(amt));
        }
        if (hasPct) {
            return String.format(Locale.of("en", "IN"), "%.2f %%", pct.doubleValue());
        }
        return "- " + formatMoney(amt);
    }

    private static String formatStock(BigDecimal stock, String unit) {
        if (stock == null) return "—";
        String u = (unit != null && !unit.isBlank()) ? " " + unit : "";
        return String.format(Locale.of("en", "IN"), "%,.2f%s", stock.doubleValue(), u);
    }

    private static String formatQty(BigDecimal qty, String unit) {
        if (qty == null) return "0.00";
        String u = (unit != null && !unit.isBlank()) ? " " + unit : "";
        return String.format(Locale.of("en", "IN"), "%+,.2f%s", qty.doubleValue(), u);
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
