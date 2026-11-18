package org.example.util;

import javafx.animation.Interpolator;
import javafx.animation.RotateTransition;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.Duration;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.example.api.operations.OperationsApiClient;
import org.example.api.operations.OperationsApiClient.StockHistoryEntry;
import org.example.api.returns.ReturnApiClient;
import org.example.api.support.SupportApiClient;
import org.example.controller.ItemTransactionContext;
import org.example.model.Item;
import org.example.model.Purchase;
import org.example.model.Sales;
import org.example.navigation.NavigationManager;

import java.io.File;
import java.io.FileOutputStream;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Consumer;

/**
 * Enterprise Stock Movement Ledger Dialog (1320px x 820px, Content-Adaptive & Resizable).
 * Features:
 * - 5 Semantic KPI Cards (On-Hand, Inward, Outward, Net Realized Profit, Valuation).
 * - Activity-based colored badges (Sale, Purchase, Return, Adjustment).
 * - Interactive reference linkages (click opens Sales/Purchase/Return Document Inspection).
 * - Realized P&L margin per transaction (Selling Rate vs. Unit Cost).
 * - Chronological Running Balance calculation.
 * - 3D Animated Refresh button with rotation transition & disabled feedback state.
 * - Amber Reset button with instant filter wiping.
 * - Excel & PDF export engines.
 * - Quick Action Buttons: [+ Create Sale] & [+ Create Purchase] with item auto-population.
 */
public final class ItemStockHistoryDialog {

    private static final NumberFormat MONEY = NumberFormat.getCurrencyInstance(Locale.of("en", "IN"));

    private ItemStockHistoryDialog() {}

    public record LedgerRow(
            int index,
            String date,
            String timestamp,
            String type,
            double quantity,
            String reason,
            String reference,
            String user,
            String partyName,
            Double rate,
            Double unitCost,
            double inwardQty,
            double outwardQty,
            double runningBalance,
            Double profitAmount,
            Double profitMarginPercent
    ) {}

    public record ItemActions(
            Consumer<Item> onViewItem,
            Consumer<Item> onEditItem,
            Consumer<Item> onAdjustStock,
            java.util.function.BiConsumer<Item, Runnable> onDeleteItem,
            Runnable onRefreshInventory
    ) {}

    public static void show(Node ownerNode, Item item, OperationsApiClient operationsApi, Consumer<Item> onAdjustStock, Runnable onRefreshInventory) {
        show(ownerNode, item, operationsApi, new ItemActions(null, null, onAdjustStock, null, onRefreshInventory));
    }

    public static void show(Node ownerNode, Item item, OperationsApiClient operationsApi, ItemActions actions) {
        if (item == null) return;
        final ItemActions itemActions = actions != null ? actions : new ItemActions(null, null, null, null, null);

        Dialog<ButtonType> dialog = new OwnedDialog<>(ownerNode);
        dialog.setTitle("Stock Movement Ledger — " + item.getItemCode());
        dialog.setResizable(true);

        DialogPane pane = dialog.getDialogPane();
        pane.getStyleClass().addAll("stock-ledger-dialog", "erp-responsive-dialog", "dse-dialog-workspace", "approved-ui");
        PopupTableWorkspace.applyInspectionSizing(dialog, ownerNode);

        final OperationsApiClient api = (operationsApi != null) ? operationsApi : new OperationsApiClient();
        final SupportApiClient supportClient = new SupportApiClient();
        final ReturnApiClient returnClient = new ReturnApiClient();

        // Data State
        final List<StockHistoryEntry> rawEntries = new ArrayList<>();
        final ObservableList<LedgerRow> tableRows = FXCollections.observableArrayList();

        // 1. Top Header Hero
        HBox headerHero = buildHeaderHero(item);

        // 2. 5 KPI Cards Strip
        Label lblOnHandVal = new Label("—");
        Label lblInwardVal = new Label("—");
        Label lblOutwardVal = new Label("—");
        Label lblProfitVal = new Label("—");
        Label lblValuationVal = new Label("—");

        HBox kpiStrip = buildKpiStrip(item, lblOnHandVal, lblInwardVal, lblOutwardVal, lblProfitVal, lblValuationVal);

        // 3. Filter & 3D Control Bar
        TextField txtSearch = new TextField();
        txtSearch.setPromptText("Search reference #, party name, reason, or user...");
        txtSearch.getStyleClass().addAll("stock-filter-field");
        HBox.setHgrow(txtSearch, Priority.ALWAYS);

        ComboBox<String> cmbType = new ComboBox<>(FXCollections.observableArrayList(
                "All Types", "SALE", "PURCHASE", "SALES RETURN", "PURCHASE RETURN", "ADJUSTMENT"
        ));
        cmbType.setValue("All Types");
        cmbType.getStyleClass().addAll("stock-filter-combo");

        DatePicker dpFrom = new DatePicker();
        dpFrom.setPromptText("From date");
        dpFrom.getStyleClass().addAll("stock-filter-date");
        DatePickerFormatter.attach(dpFrom);

        DatePicker dpTo = new DatePicker();
        dpTo.setPromptText("To date");
        dpTo.getStyleClass().addAll("stock-filter-date");
        DatePickerFormatter.attach(dpTo);

        Button btnRefresh = new Button("Refresh");
        btnRefresh.getStyleClass().addAll("stock-btn-refresh");
        Node refreshIcon = IconFactory.compactIcon("refresh", 16);
        btnRefresh.setGraphic(refreshIcon);

        Button btnReset = new Button("Reset");
        btnReset.getStyleClass().addAll("stock-btn-reset");
        btnReset.setGraphic(IconFactory.compactIcon("cancel", 16));

        HBox filterBar = new HBox(10,
                txtSearch,
                new Label("Type:"), cmbType,
                new Label("From:"), dpFrom,
                new Label("To:"), dpTo,
                btnRefresh,
                btnReset
        );
        filterBar.setAlignment(Pos.CENTER_LEFT);
        filterBar.getStyleClass().add("stock-filter-bar");

        // 4. Ledger Table View + Universal Pagination Bar (default 25 rows/page)
        TableView<LedgerRow> table = new TableView<>();
        table.getStyleClass().addAll("professional-table", "entity-table", "stock-ledger-table");
        VBox.setVgrow(table, Priority.ALWAYS);

        setupTableColumns(dialog, table, api, supportClient, returnClient);

        TablePaginationBar<LedgerRow> paginationBar = new TablePaginationBar<>(table, 25);

        // 5. Status Banner + Pagination Footer
        Label lblRecordCount = new Label("Loading movement history…");
        lblRecordCount.getStyleClass().add("stock-ledger-subtitle");

        // 6. Data Fetching & Reload Trigger (declared before action dock so Adjust/Edit can refresh live)
        Runnable recomputeAndFilter = () -> {
            String q = safe(txtSearch.getText()).toLowerCase(Locale.ROOT).trim();
            String typeFilter = cmbType.getValue();
            LocalDate from = dpFrom.getValue();
            LocalDate to = dpTo.getValue();

            List<LedgerRow> computed = computeLedgerRows(item, rawEntries);

            List<LedgerRow> filtered = computed.stream()
                    .filter(r -> {
                        if (q.isBlank()) return true;
                        String haystack = (r.reference() + " " + r.partyName() + " " + r.reason() + " " + r.user() + " " + r.type()).toLowerCase(Locale.ROOT);
                        return haystack.contains(q);
                    })
                    .filter(r -> {
                        if (typeFilter == null || typeFilter.startsWith("All")) return true;
                        if ("ADJUSTMENT".equalsIgnoreCase(typeFilter)) {
                            return !"SALE".equalsIgnoreCase(r.type()) && !"PURCHASE".equalsIgnoreCase(r.type())
                                    && !r.type().toUpperCase().contains("RETURN");
                        }
                        return r.type().toUpperCase().contains(typeFilter.toUpperCase());
                    })
                    .filter(r -> {
                        if (from == null && to == null) return true;
                        LocalDate rowDate = BusinessClock.parseDate(r.date());
                        if (rowDate == null) return true;
                        if (from != null && rowDate.isBefore(from)) return false;
                        if (to != null && rowDate.isAfter(to)) return false;
                        return true;
                    })
                    .toList();

            tableRows.setAll(filtered);
            paginationBar.setAllItems(filtered);
            lblRecordCount.setText(String.format(Locale.ENGLISH, "Filtered %,d of %,d total movement record%s", filtered.size(), computed.size(), computed.size() == 1 ? "" : "s"));

            // Refresh KPI Totals based on all loaded rows for this item
            updateKpiMetrics(item, computed, lblOnHandVal, lblInwardVal, lblOutwardVal, lblProfitVal, lblValuationVal);
        };

        // 3D Animated Refresh Action
        RotateTransition rotate = new RotateTransition(Duration.millis(750), refreshIcon);
        rotate.setByAngle(360);
        rotate.setInterpolator(Interpolator.LINEAR);
        rotate.setCycleCount(RotateTransition.INDEFINITE);

        Runnable loadData = () -> {
            btnRefresh.setDisable(true);
            rotate.play();
            lblRecordCount.setText("Synchronizing stock movement records from database…");

            UiTaskExecutor.submitLatest("stock-history-" + item.getItemCode(),
                    () -> api.stockHistory(item.getItemCode()),
                    entries -> {
                        rotate.stop();
                        refreshIcon.setRotate(0);
                        btnRefresh.setDisable(false);
                        rawEntries.clear();
                        if (entries != null) rawEntries.addAll(entries);
                        recomputeAndFilter.run();
                    },
                    error -> {
                        rotate.stop();
                        refreshIcon.setRotate(0);
                        btnRefresh.setDisable(false);
                        lblRecordCount.setText("Error loading stock movements: " + error.getMessage());
                        AppDialogService.error(pane, "Failed to load stock history", error.getMessage());
                    }
            );
        };

        // 7. Bottom Zero-Truncation Action Dock (Full Inventory Actions — matching Sales/Purchase Inspection)
        HBox actionDock = buildActionDock(dialog, pane, item, tableRows, itemActions, loadData);

        // Assemble Content
        VBox root = new VBox(10, headerHero, kpiStrip, filterBar, table, paginationBar, actionDock);
        root.setPadding(new Insets(16, 20, 14, 20));
        pane.setContent(root);
        ProfessionalUiEnhancer.enhance(root);
        pane.getButtonTypes().add(ButtonType.CLOSE);
        dialog.setOnShowing(ev -> {
            Node defaultButtonBar = pane.lookup(".button-bar");
            if (defaultButtonBar != null) {
                defaultButtonBar.setVisible(false);
                defaultButtonBar.setManaged(false);
            }
        });

        // Event Bindings
        btnRefresh.setOnAction(e -> loadData.run());
        btnReset.setOnAction(e -> {
            txtSearch.clear();
            cmbType.setValue("All Types");
            dpFrom.setValue(null);
            dpTo.setValue(null);
            recomputeAndFilter.run();
        });

        txtSearch.textProperty().addListener((obs, old, val) -> recomputeAndFilter.run());
        cmbType.valueProperty().addListener((obs, old, val) -> recomputeAndFilter.run());
        dpFrom.valueProperty().addListener((obs, old, val) -> recomputeAndFilter.run());
        dpTo.valueProperty().addListener((obs, old, val) -> recomputeAndFilter.run());

        // Initial Data Load
        loadData.run();

        dialog.showAndWait();
    }

    private static HBox buildHeaderHero(Item item) {
        // Title Block
        Label lblTitle = new Label(item.getDescription());
        lblTitle.getStyleClass().add("stock-ledger-title");

        String unit = safe(item.getUnit()).isBlank() ? "" : " " + item.getUnit();
        Label lblSubtitle = new Label("Code: " + item.getItemCode() + " • HSN: " + safe(item.getHsn())
                + " • Category: " + safe(item.getCategory()) + " • Unit: " + (unit.isBlank() ? "Nos" : unit.trim()));
        lblSubtitle.getStyleClass().add("stock-ledger-subtitle");

        VBox titleBox = new VBox(4, lblTitle, lblSubtitle);
        HBox.setHgrow(titleBox, Priority.ALWAYS);

        String stockStatus = item.getAvailableStock() <= 0 ? "OUT OF STOCK"
                : item.getAvailableStock() <= item.getMinimumStock() ? "LOW STOCK" : "IN STOCK";
        String pillClass = item.getAvailableStock() <= 0 ? "pill-danger"
                : item.getAvailableStock() <= item.getMinimumStock() ? "pill-warning" : "pill-success";
        String statusIcon = item.getAvailableStock() <= 0 ? "error"
                : item.getAvailableStock() <= item.getMinimumStock() ? "reminder" : "complete";
        Label statusPill = DocumentInspectionScaffold.createPill(stockStatus, pillClass, statusIcon);

        HBox header = new HBox(16, titleBox, statusPill);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("stock-ledger-header");
        return header;
    }

    private static HBox buildActionDock(Dialog<?> dialog, Node ownerNode, Item item, ObservableList<LedgerRow> tableRows, ItemActions actions, Runnable reloadLedger) {
        HBox dock = new HBox(8);
        dock.setAlignment(Pos.CENTER_LEFT);
        dock.getStyleClass().add("inspection-action-dock");

        HBox actionsContainer = new HBox(8);
        actionsContainer.setAlignment(Pos.CENTER_LEFT);

        if (actions.onViewItem() != null) {
            Button btnView = new Button("View Item");
            btnView.setGraphic(IconFactory.compactIcon("view", 14));
            btnView.getStyleClass().addAll("approved-button", "approved-sky-button");
            btnView.setTooltip(new Tooltip("Inspect full Item Master details for " + item.getItemCode()));
            btnView.setOnAction(e -> actions.onViewItem().accept(item));
            actionsContainer.getChildren().add(btnView);
        }

        if (actions.onEditItem() != null) {
            Button btnEdit = new Button("Edit Item");
            btnEdit.setGraphic(IconFactory.compactIcon("edit", 14));
            btnEdit.getStyleClass().addAll("approved-button", "approved-amber-button");
            btnEdit.setTooltip(new Tooltip("Edit Item Master properties for " + item.getItemCode()));
            btnEdit.setOnAction(e -> {
                actions.onEditItem().accept(item);
                if (reloadLedger != null) reloadLedger.run();
            });
            actionsContainer.getChildren().add(btnEdit);
        }

        Button btnCreateSale = new Button("Create Sale");
        btnCreateSale.setGraphic(IconFactory.compactIcon("sale", 14));
        btnCreateSale.getStyleClass().addAll("approved-button", "approved-emerald-button");
        btnCreateSale.setTooltip(new Tooltip("Create a new Sales Invoice pre-filled with " + item.getItemCode()));
        btnCreateSale.setOnAction(e -> {
            dialog.close();
            ItemTransactionContext.sale(item.getItemCode());
            NavigationManager.navigateOrReport("/fxml/pages/Sale.fxml");
        });
        actionsContainer.getChildren().add(btnCreateSale);

        Button btnCreatePurchase = new Button("Create Purchase");
        btnCreatePurchase.setGraphic(IconFactory.compactIcon("purchase", 14));
        btnCreatePurchase.getStyleClass().addAll("approved-button", "approved-primary-button");
        btnCreatePurchase.setTooltip(new Tooltip("Create a new Purchase Bill pre-filled with " + item.getItemCode()));
        btnCreatePurchase.setOnAction(e -> {
            dialog.close();
            ItemTransactionContext.purchase(item.getItemCode());
            NavigationManager.navigateOrReport("/fxml/pages/Purchase.fxml");
        });
        actionsContainer.getChildren().add(btnCreatePurchase);

        if (actions.onAdjustStock() != null) {
            Button btnAdjust = new Button("Adjust Stock");
            btnAdjust.setGraphic(IconFactory.compactIcon("adjust", 14));
            btnAdjust.getStyleClass().addAll("approved-button", "approved-secondary-button");
            btnAdjust.setTooltip(new Tooltip("Record manual inward/outward stock adjustment for " + item.getItemCode()));
            btnAdjust.setOnAction(e -> {
                actions.onAdjustStock().accept(item);
                if (reloadLedger != null) reloadLedger.run();
            });
            actionsContainer.getChildren().add(btnAdjust);
        }

        Button btnExportExcel = new Button("Export Excel");
        btnExportExcel.setGraphic(IconFactory.compactIcon("excel", 14));
        btnExportExcel.getStyleClass().addAll("approved-button", "approved-emerald-button");
        btnExportExcel.setTooltip(new Tooltip("Export filtered Stock Movement Ledger to Excel"));
        btnExportExcel.setOnAction(e -> exportLedgerExcel(ownerNode, item, tableRows));
        actionsContainer.getChildren().add(btnExportExcel);

        Button btnExportPdf = new Button("Export PDF");
        btnExportPdf.setGraphic(IconFactory.compactIcon("pdf", 14));
        btnExportPdf.getStyleClass().addAll("approved-button", "approved-danger-button");
        btnExportPdf.setTooltip(new Tooltip("Export branded Stock Movement Ledger to PDF"));
        btnExportPdf.setOnAction(e -> exportLedgerPdf(ownerNode, item, tableRows));
        actionsContainer.getChildren().add(btnExportPdf);

        if (actions.onDeleteItem() != null) {
            Button btnDelete = new Button("Delete Item");
            btnDelete.setGraphic(IconFactory.compactIcon("delete", 14));
            btnDelete.getStyleClass().addAll("approved-button", "approved-danger-button");
            btnDelete.setTooltip(new Tooltip("Delete item " + item.getItemCode()));
            btnDelete.setOnAction(e -> actions.onDeleteItem().accept(item, dialog::close));
            actionsContainer.getChildren().add(btnDelete);
        }

        for (Node node : actionsContainer.getChildren()) {
            if (node instanceof Region r) r.setMinWidth(Region.USE_PREF_SIZE);
        }

        ScrollPane scroll = new ScrollPane(actionsContainer);
        scroll.setFitToHeight(true);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.getStyleClass().addAll("erp-dock-scroll", "erp-clean-scroll");
        HBox.setHgrow(scroll, Priority.ALWAYS);

        Button btnClose = new Button("Close");
        btnClose.setMinWidth(Region.USE_PREF_SIZE);
        btnClose.setGraphic(IconFactory.compactIcon("cancel", 14));
        btnClose.getStyleClass().addAll("approved-button", "approved-danger-ghost-button");
        btnClose.setOnAction(e -> dialog.close());

        dock.getChildren().addAll(scroll, btnClose);
        return dock;
    }

    private static void exportLedgerPdf(Node ownerNode, Item item, List<LedgerRow> rows) {
        if (rows == null || rows.isEmpty()) {
            AppDialogService.warning(ownerNode, "No Movement Data", "There are no stock movement records to export.");
            return;
        }
        String safeCode = item.getItemCode().replaceAll("[^A-Za-z0-9_-]", "_");
        File out = ExportFeedbackManager.resolveWorkspaceExportFile("Inventory", "Stock_Ledger_" + safeCode, "pdf");
        if (!ExportFeedbackManager.checkFileWriteable(ownerNode, out)) return;

        try {
            String[] headings = {"Date", "Activity", "Reference", "Party / Entity", "Rate", "Inward (+)", "Outward (-)", "Balance", "Realized P&L"};
            List<String[]> pdfRows = rows.stream().map(r -> new String[]{
                    r.date(),
                    r.type(),
                    safe(r.reference()),
                    safe(r.partyName()),
                    r.rate() != null && r.rate() > 0 ? MONEY.format(r.rate()).replace("₹", "Rs. ") : "—",
                    r.inwardQty() > 0 ? String.format("+%,.2f", r.inwardQty()) : "—",
                    r.outwardQty() > 0 ? String.format("-%,.2f", r.outwardQty()) : "—",
                    String.format("%,.2f", r.runningBalance()),
                    r.profitAmount() != null
                            ? String.format("%s%,.2f (%+.1f%%)", r.profitAmount() >= 0 ? "+" : "", r.profitAmount(), r.profitMarginPercent() != null ? r.profitMarginPercent() : 0.0)
                            : "—"
            }).toList();
            org.example.service.BrandedRegisterPdfService.export(
                    out.toPath(),
                    "Stock Movement Ledger — " + item.getItemCode() + " (" + safe(item.getDescription()) + ")",
                    headings,
                    pdfRows,
                    new float[]{1.2f, 1.3f, 1.7f, 1.8f, 1.1f, 1.0f, 1.0f, 1.2f, 1.5f}
            );
            ExportFeedbackManager.notifyExportSuccess(ownerNode, out, "Stock Movement Ledger PDF — " + item.getItemCode(), rows.size());
        } catch (Exception ex) {
            AppDialogService.error(ownerNode, "PDF Export Failed", ex.getMessage());
        }
    }

    private static void exportLedgerExcel(Node ownerNode, Item item, List<LedgerRow> rows) {
        if (rows == null || rows.isEmpty()) {
            AppDialogService.warning(ownerNode, "No Movement Data", "There are no stock movement records to export.");
            return;
        }
        String safeCode = item.getItemCode().replaceAll("[^A-Za-z0-9_-]", "_");
        File out = ExportFeedbackManager.resolveWorkspaceExportFile("Inventory", "Stock_Ledger_" + safeCode, "xlsx");
        if (!ExportFeedbackManager.checkFileWriteable(ownerNode, out)) return;

        try (Workbook wb = new XSSFWorkbook(); FileOutputStream fos = new FileOutputStream(out)) {
            Sheet sh = wb.createSheet("Stock Movement Ledger");
            String[] headers = {"#", "Date", "Timestamp", "Activity", "Reference", "Party / Entity", "Rate", "Inward (+)", "Outward (-)", "Running Balance", "Realized P&L", "Margin %", "Reason", "User"};
            Row headerRow = sh.createRow(0);
            for (int i = 0; i < headers.length; i++) headerRow.createCell(i).setCellValue(headers[i]);

            int rIdx = 1;
            for (LedgerRow r : rows) {
                Row row = sh.createRow(rIdx++);
                row.createCell(0).setCellValue(r.index());
                row.createCell(1).setCellValue(r.date());
                row.createCell(2).setCellValue(r.timestamp());
                row.createCell(3).setCellValue(r.type());
                row.createCell(4).setCellValue(r.reference());
                row.createCell(5).setCellValue(r.partyName());
                row.createCell(6).setCellValue(r.rate() != null ? r.rate() : 0.0);
                row.createCell(7).setCellValue(r.inwardQty());
                row.createCell(8).setCellValue(r.outwardQty());
                row.createCell(9).setCellValue(r.runningBalance());
                row.createCell(10).setCellValue(r.profitAmount() != null ? r.profitAmount() : 0.0);
                row.createCell(11).setCellValue(r.profitMarginPercent() != null ? r.profitMarginPercent() : 0.0);
                row.createCell(12).setCellValue(r.reason());
                row.createCell(13).setCellValue(r.user());
            }
            wb.write(fos);
            ExportFeedbackManager.notifyExportSuccess(ownerNode, out, "Stock Movement Ledger — " + item.getItemCode(), rows.size());
        } catch (Exception ex) {
            AppDialogService.error(ownerNode, "Export Failed", ex.getMessage());
        }
    }

    private static HBox buildKpiStrip(Item item, Label lblOnHandVal, Label lblInwardVal, Label lblOutwardVal, Label lblProfitVal, Label lblValuationVal) {
        VBox cardOnHand = buildKpiCard("Current On-Hand", lblOnHandVal, "Physical stock available", "stock-kpi-primary", "inventory");
        VBox cardInward = buildKpiCard("Total Inward (+)", lblInwardVal, "Purchases & inward audits", "stock-kpi-success", "complete");
        VBox cardOutward = buildKpiCard("Total Outward (-)", lblOutwardVal, "Sales & dispatches", "stock-kpi-danger", "error");
        VBox cardProfit = buildKpiCard("Net Realized Profit", lblProfitVal, "Sales margin above unit cost", "stock-kpi-profit", "currency");
        VBox cardValuation = buildKpiCard("Stock Valuation", lblValuationVal, "Purchase valuation", "stock-kpi-valuation", "balance");

        HBox.setHgrow(cardOnHand, Priority.ALWAYS);
        HBox.setHgrow(cardInward, Priority.ALWAYS);
        HBox.setHgrow(cardOutward, Priority.ALWAYS);
        HBox.setHgrow(cardProfit, Priority.ALWAYS);
        HBox.setHgrow(cardValuation, Priority.ALWAYS);

        HBox strip = new HBox(12, cardOnHand, cardInward, cardOutward, cardProfit, cardValuation);
        strip.setAlignment(Pos.CENTER);
        return strip;
    }

    private static VBox buildKpiCard(String labelText, Label valLabel, String subText, String styleVariant, String iconSemantic) {
        Label lblHeader = new Label(labelText);
        lblHeader.getStyleClass().add("stock-kpi-label");
        lblHeader.setGraphic(IconFactory.compactIcon(iconSemantic, 16));

        valLabel.getStyleClass().add("stock-kpi-value");

        Label lblSub = new Label(subText);
        lblSub.getStyleClass().add("stock-kpi-sub");

        VBox card = new VBox(4, lblHeader, valLabel, lblSub);
        card.getStyleClass().addAll("stock-kpi-card", styleVariant);
        return card;
    }

    private static void setupTableColumns(Dialog<?> dialog, TableView<LedgerRow> table, OperationsApiClient api, SupportApiClient support, ReturnApiClient returnApi) {
        // 1. #
        TableColumn<LedgerRow, String> colIndex = new TableColumn<>("#");
        colIndex.setCellValueFactory(v -> new SimpleStringProperty(String.valueOf(v.getValue().index())));
        colIndex.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                setAlignment(Pos.CENTER);
            }
        });
        colIndex.setMinWidth(42);
        colIndex.prefWidthProperty().bind(table.widthProperty().multiply(0.03));

        // 2. Date & Time (Single-line: Date + Space + Time per user standard)
        TableColumn<LedgerRow, LedgerRow> colDate = new TableColumn<>("Date & Time");
        colDate.setCellValueFactory(v -> new SimpleObjectProperty<>(v.getValue()));
        colDate.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(LedgerRow r, boolean empty) {
                super.updateItem(r, empty);
                if (empty || r == null) {
                    setGraphic(null);
                    setText(null);
                } else {
                    Label lblDate = new Label(r.date());
                    lblDate.getStyleClass().add("stock-date-main");
                    lblDate.setMinWidth(Region.USE_PREF_SIZE);
                    HBox box;
                    if (r.timestamp() != null && !r.timestamp().isBlank() && !"—".equals(r.timestamp())) {
                        Label lblTime = new Label(r.timestamp());
                        lblTime.getStyleClass().add("stock-date-time");
                        lblTime.setMinWidth(Region.USE_PREF_SIZE);
                        box = new HBox(6, lblDate, lblTime);
                    } else {
                        box = new HBox(lblDate);
                    }
                    box.setAlignment(Pos.CENTER_LEFT);
                    setGraphic(box);
                    setText(null);
                }
            }
        });
        colDate.setMinWidth(185);
        colDate.prefWidthProperty().bind(table.widthProperty().multiply(0.14));
        IconFactory.applyTableHeaderIcon(colDate, "calendar");

        // 3. Activity Type
        TableColumn<LedgerRow, String> colType = new TableColumn<>("Activity");
        colType.setCellValueFactory(v -> new SimpleStringProperty(v.getValue().type()));
        colType.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String type, boolean empty) {
                super.updateItem(type, empty);
                if (empty || type == null) {
                    setGraphic(null);
                    setText(null);
                } else {
                    Label badge = new Label(type);
                    badge.setMinWidth(Region.USE_PREF_SIZE);
                    badge.getStyleClass().add("stock-badge");
                    String up = type.toUpperCase(Locale.ROOT);
                    if (up.contains("SALE RETURN")) badge.getStyleClass().add("stock-badge-sreturn");
                    else if (up.contains("PURCHASE RETURN")) badge.getStyleClass().add("stock-badge-preturn");
                    else if (up.equals("SALE")) badge.getStyleClass().add("stock-badge-sale");
                    else if (up.equals("PURCHASE")) badge.getStyleClass().add("stock-badge-purchase");
                    else badge.getStyleClass().add("stock-badge-adjust");
                    setGraphic(badge);
                    setAlignment(Pos.CENTER);
                }
            }
        });
        colType.setMinWidth(155);
        colType.prefWidthProperty().bind(table.widthProperty().multiply(0.12));
        IconFactory.applyTableHeaderIcon(colType, "category");

        // 4. Linked Reference (Clean borderless text link — no blue button fill)
        TableColumn<LedgerRow, LedgerRow> colRef = new TableColumn<>("Linked Reference");
        colRef.setCellValueFactory(v -> new SimpleObjectProperty<>(v.getValue()));
        colRef.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(LedgerRow r, boolean empty) {
                super.updateItem(r, empty);
                if (empty || r == null || r.reference() == null || r.reference().isBlank()) {
                    setGraphic(null);
                    setText(null);
                } else {
                    Hyperlink link = new Hyperlink(r.reference());
                    link.setMinWidth(Region.USE_PREF_SIZE);
                    link.getStyleClass().add("erp-inline-doc-link");
                    link.setTooltip(IconFactory.createTooltip("Inspect linked document " + r.reference()));
                    link.setOnAction(e -> {
                        link.setVisited(false);
                        handleReferenceClick(dialog, r, api, support, returnApi);
                    });
                    setGraphic(link);
                    setText(null);
                    setAlignment(Pos.CENTER_LEFT);
                }
            }
        });
        colRef.setMinWidth(185);
        colRef.prefWidthProperty().bind(table.widthProperty().multiply(0.15));
        IconFactory.applyTableHeaderIcon(colRef, "reference");

        // 5. Party / Entity
        TableColumn<LedgerRow, String> colParty = new TableColumn<>("Party / Entity");
        colParty.setCellValueFactory(v -> new SimpleStringProperty(v.getValue().partyName()));
        colParty.setMinWidth(150);
        colParty.prefWidthProperty().bind(table.widthProperty().multiply(0.14));
        IconFactory.applyTableHeaderIcon(colParty, "user");

        // 6. Unit Rate
        TableColumn<LedgerRow, Double> colRate = new TableColumn<>("Rate");
        colRate.setCellValueFactory(v -> new SimpleObjectProperty<>(v.getValue().rate()));
        colRate.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Double val, boolean empty) {
                super.updateItem(val, empty);
                if (empty || val == null || val <= 0) {
                    setText(empty ? null : "—");
                } else {
                    setText(MONEY.format(val));
                }
                setAlignment(Pos.CENTER_RIGHT);
            }
        });
        colRate.setMinWidth(95);
        colRate.prefWidthProperty().bind(table.widthProperty().multiply(0.08));
        IconFactory.applyTableHeaderIcon(colRate, "currency");

        // 7. Inward (+)
        TableColumn<LedgerRow, Double> colInward = new TableColumn<>("Inward (+)");
        colInward.setCellValueFactory(v -> new SimpleObjectProperty<>(v.getValue().inwardQty()));
        colInward.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Double qty, boolean empty) {
                super.updateItem(qty, empty);
                if (empty || qty == null || qty <= 0) {
                    setText(empty ? null : "—");
                    getStyleClass().remove("stock-qty-inward");
                } else {
                    setText(String.format("+%,.2f", qty));
                    if (!getStyleClass().contains("stock-qty-inward")) getStyleClass().add("stock-qty-inward");
                }
                setAlignment(Pos.CENTER_RIGHT);
            }
        });
        colInward.setMinWidth(90);
        colInward.prefWidthProperty().bind(table.widthProperty().multiply(0.07));
        IconFactory.applyTableHeaderIcon(colInward, "complete");

        // 8. Outward (-)
        TableColumn<LedgerRow, Double> colOutward = new TableColumn<>("Outward (-)");
        colOutward.setCellValueFactory(v -> new SimpleObjectProperty<>(v.getValue().outwardQty()));
        colOutward.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Double qty, boolean empty) {
                super.updateItem(qty, empty);
                if (empty || qty == null || qty <= 0) {
                    setText(empty ? null : "—");
                    getStyleClass().remove("stock-qty-outward");
                } else {
                    setText(String.format("-%,.2f", qty));
                    if (!getStyleClass().contains("stock-qty-outward")) getStyleClass().add("stock-qty-outward");
                }
                setAlignment(Pos.CENTER_RIGHT);
            }
        });
        colOutward.setMinWidth(90);
        colOutward.prefWidthProperty().bind(table.widthProperty().multiply(0.07));
        IconFactory.applyTableHeaderIcon(colOutward, "error");

        // 9. Running Balance
        TableColumn<LedgerRow, Double> colBalance = new TableColumn<>("Running Balance");
        colBalance.setCellValueFactory(v -> new SimpleObjectProperty<>(v.getValue().runningBalance()));
        colBalance.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Double bal, boolean empty) {
                super.updateItem(bal, empty);
                if (empty || bal == null) {
                    setText(null);
                    getStyleClass().remove("stock-qty-balance");
                } else {
                    setText(String.format("%,.2f", bal));
                    if (!getStyleClass().contains("stock-qty-balance")) getStyleClass().add("stock-qty-balance");
                }
                setAlignment(Pos.CENTER_RIGHT);
            }
        });
        colBalance.setMinWidth(115);
        colBalance.prefWidthProperty().bind(table.widthProperty().multiply(0.09));
        IconFactory.applyTableHeaderIcon(colBalance, "balance");

        // 10. Realized P&L
        TableColumn<LedgerRow, LedgerRow> colPnl = new TableColumn<>("Realized P&L");
        colPnl.setCellValueFactory(v -> new SimpleObjectProperty<>(v.getValue()));
        colPnl.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(LedgerRow r, boolean empty) {
                super.updateItem(r, empty);
                if (empty || r == null || r.profitAmount() == null) {
                    setText(empty ? null : "—");
                    setGraphic(null);
                    setAlignment(Pos.CENTER);
                } else {
                    double profit = r.profitAmount();
                    double margin = r.profitMarginPercent() != null ? r.profitMarginPercent() : 0.0;
                    Label badge = new Label(String.format("%s%,.2f (%+.1f%%)", profit >= 0 ? "+" : "", profit, margin));
                    badge.setMinWidth(Region.USE_PREF_SIZE);
                    badge.getStyleClass().add(profit >= 0 ? "stock-pnl-profit" : "stock-pnl-loss");
                    setGraphic(badge);
                    setText(null);
                    setAlignment(Pos.CENTER);
                }
            }
        });
        colPnl.setMinWidth(185);
        colPnl.prefWidthProperty().bind(table.widthProperty().multiply(0.14));
        IconFactory.applyTableHeaderIcon(colPnl, "currency");

        table.getColumns().setAll(List.of(
                colIndex, colDate, colType, colRef, colParty, colRate, colInward, colOutward, colBalance, colPnl
        ));
    }

    private static void handleReferenceClick(Dialog<?> dialog, LedgerRow r, OperationsApiClient api, SupportApiClient support, ReturnApiClient returnApi) {
        String ref = r.reference();
        if (ref == null || ref.isBlank()) return;

        String type = r.type().toUpperCase(Locale.ROOT);
        Node owner = dialog.getDialogPane();

        if (type.equals("SALE") || ref.startsWith("IN/") || ref.startsWith("INV")) {
            UiTaskExecutor.submitLatest("inspect-sale-" + ref,
                    () -> api.sale(ref),
                    sale -> {
                        if (sale != null) {
                            SalesDocumentInspectionDialog.show(owner, sale, support, null);
                        } else {
                            AppDialogService.warning(owner, "Document Not Found", "Sales invoice " + ref + " could not be resolved.");
                        }
                    },
                    err -> AppDialogService.error(owner, "Cannot Load Sale", err.getMessage())
            );
        } else if (type.equals("PURCHASE") || ref.startsWith("PUR/")) {
            UiTaskExecutor.submitLatest("inspect-purchase-" + ref,
                    () -> api.purchase(ref),
                    purchase -> {
                        if (purchase != null) {
                            PurchaseDocumentInspectionDialog.show(owner, purchase, support, null);
                        } else {
                            AppDialogService.warning(owner, "Document Not Found", "Purchase invoice " + ref + " could not be resolved.");
                        }
                    },
                    err -> AppDialogService.error(owner, "Cannot Load Purchase", err.getMessage())
            );
        } else if (type.contains("RETURN") || ref.startsWith("SRT/") || ref.startsWith("PRT/")) {
            boolean isSalesReturn = type.contains("SALE") || ref.startsWith("SRT/");
            ReturnDocumentInspectionDialog.show(owner, ref, isSalesReturn, returnApi, support, null);
        } else {
            AppDialogService.info(owner, "Stock Movement Detail", "Reference: " + ref + "\nType: " + r.type() + "\nReason: " + r.reason() + "\nUser: " + r.user());
        }
    }

    private static List<LedgerRow> computeLedgerRows(Item item, List<StockHistoryEntry> raw) {
        if (raw == null || raw.isEmpty()) return List.of();

        // The query returns records newest first (DESC).
        // To compute running balance mathematically:
        // We start with current openingStock of the item as the final balance.
        // As we step backward from the newest transaction, balance_before = balance_after - movement_qty.
        double currentStock = item.getAvailableStock() > 0 ? item.getAvailableStock() : item.getOpeningStock();

        // Create chronological copy (oldest first) to accumulate forward
        List<StockHistoryEntry> chronological = new ArrayList<>(raw);
        Collections.reverse(chronological);

        // Sum of all deltas
        double totalDelta = chronological.stream().mapToDouble(StockHistoryEntry::quantity).sum();
        double running = currentStock - totalDelta; // Opening stock before these movements

        List<LedgerRow> resultChronological = new ArrayList<>();
        int idx = 1;
        for (StockHistoryEntry e : chronological) {
            double qty = e.quantity();
            running += qty;

            double inward = qty > 0 ? qty : 0.0;
            double outward = qty < 0 ? -qty : 0.0;

            Double profit = null;
            Double margin = null;
            if ("SALE".equalsIgnoreCase(e.type()) && e.rate() != null && e.unitCost() != null) {
                double saleRate = e.rate();
                double cost = e.unitCost();
                double saleQty = Math.abs(qty);
                profit = saleQty * (saleRate - cost);
                margin = cost > 0 ? ((saleRate - cost) / cost) * 100.0 : 0.0;
            }

            String dateFormatted = formatDisplayDate(e.date());
            String timeFormatted = formatDisplayTime(e.timestamp());

            resultChronological.add(new LedgerRow(
                    idx++,
                    dateFormatted,
                    timeFormatted,
                    safe(e.type()),
                    qty,
                    safe(e.reason()),
                    safe(e.reference()),
                    safe(e.user()),
                    safe(e.partyName()),
                    e.rate(),
                    e.unitCost(),
                    inward,
                    outward,
                    running,
                    profit,
                    margin
            ));
        }

        // Return newest first for standard enterprise ledger presentation
        Collections.reverse(resultChronological);
        // Re-index so 1 is at the top
        List<LedgerRow> finalRows = new ArrayList<>();
        int displayIdx = 1;
        for (LedgerRow r : resultChronological) {
            finalRows.add(new LedgerRow(
                    displayIdx++,
                    r.date(),
                    r.timestamp(),
                    r.type(),
                    r.quantity(),
                    r.reason(),
                    r.reference(),
                    r.user(),
                    r.partyName(),
                    r.rate(),
                    r.unitCost(),
                    r.inwardQty(),
                    r.outwardQty(),
                    r.runningBalance(),
                    r.profitAmount(),
                    r.profitMarginPercent()
            ));
        }
        return finalRows;
    }

    private static void updateKpiMetrics(Item item, List<LedgerRow> rows, Label lblOnHand, Label lblInward, Label lblOutward, Label lblProfit, Label lblValuation) {
        String unit = safe(item.getUnit()).isBlank() ? "" : " " + item.getUnit();

        double onHand = item.getAvailableStock() > 0 ? item.getAvailableStock() : item.getOpeningStock();
        lblOnHand.setText(String.format(Locale.ENGLISH, "%,.2f%s", onHand, unit));

        double totalInward = rows.stream().mapToDouble(LedgerRow::inwardQty).sum();
        lblInward.setText(String.format(Locale.ENGLISH, "+%,.2f%s", totalInward, unit));

        double totalOutward = rows.stream().mapToDouble(LedgerRow::outwardQty).sum();
        lblOutward.setText(String.format(Locale.ENGLISH, "-%,.2f%s", totalOutward, unit));

        double netProfit = rows.stream()
                .filter(r -> r.profitAmount() != null)
                .mapToDouble(LedgerRow::profitAmount)
                .sum();
        lblProfit.setText(MONEY.format(netProfit));

        double valuation = Math.max(0, onHand) * item.getPurchasePrice();
        lblValuation.setText(MONEY.format(valuation));
    }

    private static String formatDisplayDate(String rawDate) {
        if (rawDate == null || rawDate.isBlank()) return "—";
        try {
            LocalDate d = BusinessClock.parseDate(rawDate);
            if (d != null) return BusinessClock.formatDate(d);
        } catch (Exception ignored) {}
        return rawDate;
    }

    private static String formatDisplayTime(String rawTimestamp) {
        if (rawTimestamp == null || rawTimestamp.isBlank()) return "—";
        try {
            String time = BusinessClock.formatTime(BusinessClock.parseTimestamp(rawTimestamp));
            String zone = BusinessClock.zoneAbbreviation();
            return time + (zone.isBlank() ? "" : " " + zone);
        } catch (Exception ignored) {
            return rawTimestamp.length() > 10 ? rawTimestamp.substring(11) : rawTimestamp;
        }
    }

    private static String safe(String v) {
        return v == null ? "" : v.trim();
    }
}
