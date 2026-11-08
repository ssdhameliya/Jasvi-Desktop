package org.example.controller;

import org.example.util.OwnedAlert;
import org.example.util.AppDialogService;

import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.example.model.Item;
import org.example.api.master.MasterApiClient;
import org.example.service.ItemService;
import org.example.service.ItemSpreadsheetService;
import org.example.service.NotificationService;
import org.example.theme.ThemeManager;
import org.example.util.PlatformUiSupport;
import org.example.util.IconFactory;
import org.example.util.RegisterDetailDrawer;
import org.example.util.RegisterUiSupport;
import org.example.util.DynamicTableLayoutManager;
import org.example.util.ExportFeedbackManager;
import org.example.util.OperationalUiSupport;
import org.example.navigation.NavigationManager;
import org.example.navigation.ScreenLifecycle;
import org.example.util.UiTaskExecutor;

import java.io.File;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public class ItemMasterController implements ScreenLifecycle {

    @FXML private TextField txtSearch;
    @FXML private TableView<Item> tableItems;
    @FXML private Label lblRecordCount,lblKpiTotal,lblKpiCategories,lblKpiLowStock,lblKpiValue,lblSelectedCount;
    @FXML private Button btnReset,btnRefresh,btnDeleteSelected,btnImportExcel,btnExportExcel;
    @FXML private StackPane itemPageIcon,itemTotalIcon,itemCategoryIcon,itemLowIcon,itemValueIcon;


    @FXML private TableColumn<Item, Boolean> colSelect;
    @FXML private TableColumn<Item, String> colCode;
    @FXML private TableColumn<Item, String> colDescription;
    @FXML private TableColumn<Item, String> colCategory;
    @FXML private TableColumn<Item, String> colBrand;
    @FXML private TableColumn<Item, String> colMaterial;
    @FXML private TableColumn<Item, String> colSize;
    @FXML private TableColumn<Item, String> colUnit;
    @FXML private TableColumn<Item, String> colHsn;
    @FXML private TableColumn<Item, Double> colGst;
    @FXML private TableColumn<Item, Double> colDiscount;
    @FXML private TableColumn<Item, Double> colPurchasePrice;
    @FXML private TableColumn<Item, Double> colSellingPrice;
    @FXML private TableColumn<Item, Double> colOpeningStock;
    @FXML private TableColumn<Item, Double> colMinimumStock;
    @FXML private TableColumn<Item, String> colLocation;
    @FXML private TableColumn<Item, String> colRemarks;
    @FXML private TableColumn<Item, Void> colAction;

    private final ObservableList<Item> items = FXCollections.observableArrayList();
    private final ObservableList<Item> allItems = FXCollections.observableArrayList();
    private final ItemService service = new ItemService();
    private final ItemSpreadsheetService spreadsheetService = new ItemSpreadsheetService();
    private final Set<String> selectedItemCodes = new LinkedHashSet<>();
    private final CheckBox selectAllVisible = new CheckBox();
    private RegisterDetailDrawer detailDrawer;
    private Item detailItem;

    private void installToolbarIcons() {
        if (btnReset != null) {
            btnReset.setGraphic(IconFactory.compactIcon("reset", 15));
            btnReset.setGraphicTextGap(7);
            btnReset.getProperties().put("erp-icon-preserve", true);
        }
        if (btnRefresh != null) {
            btnRefresh.setGraphic(IconFactory.compactIcon("refresh", 15));
            btnRefresh.setGraphicTextGap(7);
            btnRefresh.getProperties().put("erp-icon-preserve", true);
        }
        if (btnImportExcel != null) {
            btnImportExcel.setGraphic(IconFactory.compactIcon("import", 15));
            btnImportExcel.setGraphicTextGap(7);
            btnImportExcel.getProperties().put("erp-icon-preserve", true);
        }
        if (btnExportExcel != null) {
            btnExportExcel.setGraphic(IconFactory.compactIcon("export", 15));
            btnExportExcel.setGraphicTextGap(7);
            btnExportExcel.getProperties().put("erp-icon-preserve", true);
        }
    }

    @FXML
    public void initialize() {
        installKpiIcons();
        installToolbarIcons();// Item Master owns its checkbox model. The global table enhancer must
        // not replace colSelect with a TableView-selection-backed checkbox.
        tableItems.getProperties().put("erp-keep-selection", true);
        configureBulkSelection();
        // Column bindings

        colCode.setCellValueFactory(new PropertyValueFactory<>("itemCode"));
        colDescription.setCellValueFactory(new PropertyValueFactory<>("description"));
        colCategory.setCellValueFactory(new PropertyValueFactory<>("category"));
        colBrand.setCellValueFactory(new PropertyValueFactory<>("brand"));
        colMaterial.setCellValueFactory(new PropertyValueFactory<>("material"));
        colSize.setCellValueFactory(new PropertyValueFactory<>("size"));
        colUnit.setCellValueFactory(new PropertyValueFactory<>("unit"));
        colHsn.setCellValueFactory(new PropertyValueFactory<>("hsn"));
        colGst.setCellValueFactory(new PropertyValueFactory<>("gst"));
        colGst.setCellFactory(tc -> new TableCell<>() {
            @Override protected void updateItem(Double v, boolean empty) {
                super.updateItem(v, empty);
                setText(empty || v == null ? null : String.format("%.2f", v));
            }
        });

        colDiscount.setCellValueFactory(new PropertyValueFactory<>("discountPercent"));
        colDiscount.setCellFactory(tc -> new TableCell<>() {
            @Override protected void updateItem(Double v, boolean empty) {
                super.updateItem(v, empty);
                setText(empty || v == null ? null : String.format("%.2f%%", v));
            }
        });

        colPurchasePrice.setCellValueFactory(new PropertyValueFactory<>("purchasePrice"));
        colPurchasePrice.setCellFactory(tc -> new TableCell<>() {
            @Override protected void updateItem(Double v, boolean empty) {
                super.updateItem(v, empty);
                setText(empty || v == null ? null : "₹" + String.format("%,.2f", v));
            }
        });

        colSellingPrice.setCellValueFactory(new PropertyValueFactory<>("sellingPrice"));
        colSellingPrice.setCellFactory(tc -> new TableCell<>() {
            @Override protected void updateItem(Double v, boolean empty) {
                super.updateItem(v, empty);
                setText(empty || v == null ? null : "₹" + String.format("%,.2f", v));
            }
        });

        colOpeningStock.setCellValueFactory(new PropertyValueFactory<>("openingStock"));
        colOpeningStock.setCellFactory(tc -> new TableCell<>() {
            @Override protected void updateItem(Double v, boolean empty) {
                super.updateItem(v, empty);
                setText(empty || v == null ? null : String.format("%,.3f", v));
            }
        });

        colMinimumStock.setCellValueFactory(new PropertyValueFactory<>("minimumStock"));
        colMinimumStock.setCellFactory(tc -> new TableCell<>() {
            @Override protected void updateItem(Double v, boolean empty) {
                super.updateItem(v, empty);
                setText(empty || v == null ? null : String.format("%,.3f", v));
            }
        });

        colLocation.setCellValueFactory(new PropertyValueFactory<>("location"));

        colRemarks.setCellValueFactory(new PropertyValueFactory<>("remarks"));
        colRemarks.setCellFactory(tc -> {
            TableCell<Item, String> cell = new TableCell<>() {
                @Override protected void updateItem(String v, boolean empty) {
                    super.updateItem(v, empty);
                    setText(empty || v == null ? null : v);
                    setWrapText(true);
                }
            };
            return cell;
        });

        // Compact icon + text Actions menu. Full row operations remain inside the menu.
        colAction.setCellFactory(tc -> new TableCell<>() {
            private final MenuButton actions = new MenuButton();
            {
                actions.getStyleClass().add("table-action-menu");
                actions.setGraphic(IconFactory.compactIcon("actions", 16));
                actions.setText("Actions");
                actions.setContentDisplay(ContentDisplay.LEFT);
                actions.setGraphicTextGap(6);

                actions.setTooltip(new Tooltip("Item actions"));

                MenuItem create = new MenuItem("Create Item", IconFactory.compactIcon("add", 16));
                create.setOnAction(e -> openItemDialog(null));
                MenuItem view = new MenuItem("View Item", IconFactory.compactIcon("view", 16));
                view.setOnAction(e -> showDetails(currentItem()));
                MenuItem edit = new MenuItem("Edit Item", IconFactory.compactIcon("edit", 16));
                edit.setOnAction(e -> openItemDialog(currentItem()));
                MenuItem audit = new MenuItem("Audit Trail", IconFactory.compactIcon("history", 16));
                audit.setOnAction(e -> { Item item=currentItem(); if(item!=null) org.example.util.ActivityTimelineDialog.show(tableItems,"ITEM",item.getId(),item.getItemCode()); });
                MenuItem sale = new MenuItem("Create Sale", IconFactory.compactIcon("sale", 16));
                sale.setOnAction(e -> openTransaction(currentItem(), true));
                MenuItem purchase = new MenuItem("Create Purchase", IconFactory.compactIcon("purchase", 16));
                purchase.setOnAction(e -> openTransaction(currentItem(), false));
                MenuItem delete = new MenuItem("Delete Item", IconFactory.compactIcon("delete", 16));
                delete.setOnAction(e -> deleteItem(currentItem()));
                actions.getItems().addAll(create, view, edit, audit, new SeparatorMenuItem(), sale, purchase, new SeparatorMenuItem(), delete);
                IconFactory.decorateActionMenu(actions);
            }

            private Item currentItem() {
                int index = getIndex();
                return index >= 0 && index < getTableView().getItems().size()
                    ? getTableView().getItems().get(index) : null;
            }

            @Override protected void updateItem(Void value, boolean empty) {
                super.updateItem(value, empty);
                setGraphic(empty ? null : actions);
            }
        });

        tableItems.setItems(items);
        colBrand.setVisible(false); colMaterial.setVisible(false); colSize.setVisible(false); colLocation.setVisible(false); colRemarks.setVisible(true);

        // Search listener
        txtSearch.textProperty().addListener((obs, oldValue, newValue) -> applyLocalFilter());

        tableItems.setRowFactory(view -> {
            TableRow<Item> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (row.isEmpty() || event.getButton() != javafx.scene.input.MouseButton.PRIMARY || event.getClickCount() != 1
                        || RegisterUiSupport.isInteractiveTableTarget(event.getPickResult().getIntersectedNode(), row)) return;
                Item clicked = row.getItem();
                tableItems.getSelectionModel().select(clicked);
                event.consume();
            });
            MenuItem add = new MenuItem("Add Item", IconFactory.icon("add"));
            add.setOnAction(event -> openItemDialog(null));
            MenuItem viewItem = new MenuItem("View Item", IconFactory.icon("view"));
            viewItem.setOnAction(event -> { if (!row.isEmpty()) { tableItems.getSelectionModel().select(row.getItem()); showDetails(row.getItem()); } });
            MenuItem edit = new MenuItem("Edit Item", IconFactory.icon("edit"));
            edit.setOnAction(event -> { if (!row.isEmpty()) openItemDialog(row.getItem()); });
            MenuItem delete = new MenuItem("Delete Item", IconFactory.icon("delete"));
            delete.setOnAction(event -> { if (!row.isEmpty()) deleteItem(row.getItem()); });
            MenuItem clear = new MenuItem("Clear Selection", IconFactory.icon("cancel"));
            clear.setOnAction(event -> { tableItems.getSelectionModel().clearSelection(); closeDetails(); });
            ContextMenu context = new ContextMenu(add, viewItem, edit, delete, new SeparatorMenuItem(), clear);
            IconFactory.decorateActionMenu(context);
            row.contextMenuProperty().bind(javafx.beans.binding.Bindings.when(row.emptyProperty())
                .then((ContextMenu) null).otherwise(context));
            return row;
        });
        installDetailDrawer();

        // initial load
        org.example.util.OperationalUiSupport.focusWorkArea(tableItems);
        loadItems();
    }


    private void openTransaction(Item item, boolean sale) {
        if (item == null || item.getItemCode() == null || item.getItemCode().isBlank()) return;
        if (sale) { ItemTransactionContext.sale(item.getItemCode()); NavigationManager.navigateOrReport("/fxml/pages/Sale.fxml"); }
        else { ItemTransactionContext.purchase(item.getItemCode()); NavigationManager.navigateOrReport("/fxml/pages/Purchase.fxml"); }
    }

    private void installDetailDrawer() {
        detailDrawer = new RegisterDetailDrawer();
        detailDrawer.setCloseAction(this::closeDetails);
        detailDrawer.attachBesideTable(tableItems);
        OperationalUiSupport.installEscapeClose(tableItems, detailDrawer::isOpen, this::closeDetails);
    }

    private void showDetails(Item item) {
        if (item == null) return;
        try {
            Optional<Item> result = ItemDialog.showView(tableItems, item);
            loadItems();
            if (result.isPresent()) {
                Item saved = result.get();
                org.example.util.AppDialogService.success(tableItems,
                        "Item Updated",
                        saved.getItemCode() + " • " + saved.getDescription() + " was updated successfully.");
                javafx.application.Platform.runLater(() -> selectSavedItem(saved.getItemCode()));
            }
        } catch (Exception e) {
            showError("Could not open the item dialog: " + e.getMessage());
        }
    }

    private void closeDetails() {
        detailItem = null;
        if (detailDrawer != null) detailDrawer.hideDrawer();
        tableItems.getSelectionModel().clearSelection();
    }

    private void openItemDialog(Item item) {
        try {
            Optional<Item> result = ItemDialog.show(tableItems, item);
            loadItems();
            if (result.isPresent()) {
                Item saved = result.get();
                org.example.util.AppDialogService.success(tableItems,
                        item == null ? "Item Created" : "Item Updated",
                        saved.getItemCode() + " • " + saved.getDescription() + (item == null ? " was added successfully." : " was updated successfully."));
                javafx.application.Platform.runLater(() -> selectSavedItem(saved.getItemCode()));
            }
        } catch (Exception e) {
            showError("Could not open the item dialog: " + e.getMessage());
        }
    }

    private void selectSavedItem(String code){
        if(code==null||code.isBlank())return;
        Item match=items.stream().filter(i->code.equalsIgnoreCase(i.getItemCode())).findFirst().orElse(null);
        if(match!=null){tableItems.getSelectionModel().select(match);tableItems.scrollTo(match);}
    }

    @FXML
    private void newItem() { openItemDialog(null); }

    @FXML
    private void editItem() {
        Item selected = tableItems.getSelectionModel().getSelectedItem();
        if (selected == null) { showWarning("Select an item to edit."); return; }
        openItemDialog(selected);
    }

    private void deleteItem(Item selected) {
        if (selected == null) { showWarning("Select an item to delete."); return; }
        boolean confirmed = AppDialogService.destructive(
                tableItems,
                "Delete Item",
                "Delete item '" + selected.getDescription() + "'?",
                "This action cannot be undone. Inventory stock history for this item will be archived.",
                "Delete Item"
        );
        if (!confirmed) return;
        String code = selected.getItemCode();
        String description = selected.getDescription();
        if (tableItems != null) tableItems.setDisable(true);
        UiTaskExecutor.submitAction(
                "item-master-delete-" + code,
                () -> { service.delete(selected); NotificationService.add("Item '" + description + "' was deleted."); return null; },
                ignored -> {
                    if (tableItems != null) tableItems.setDisable(false);
                    org.example.util.AppDialogService.success(tableItems, "Item Deleted", "Item deleted successfully.");
                    loadItems();
                },
                failure -> {
                    if (tableItems != null) tableItems.setDisable(false);
                    showError("Could not delete item: " + message(failure));
                }
        );
    }

    @FXML
    private void deleteItem() { // keep compatibility with FXML onAction
        Item selected = tableItems.getSelectionModel().getSelectedItem();
        deleteItem(selected);
    }

    @FXML
    private void resetFilters() {
        org.example.util.OperationalUiSupport.animateReset(btnReset, txtSearch, () -> {
            if (txtSearch != null) txtSearch.clear();
            tableItems.getSelectionModel().clearSelection();
            loadItems();
        });
    }

    @FXML
    private void refresh() { loadItems(); }

    @FXML
    private void importItems() {
        ImportScreenContext.select("Item Master");
        NavigationManager.getInstance().loadPage("/fxml/pages/Import.fxml");
    }

    @FXML
    private void exportItems() {
        org.example.service.PermissionService.require("INVENTORY.EXPORT", "Export Items");
        File file = ExportFeedbackManager.resolveWorkspaceExportFile("Items", "Item_Master", "xlsx");
        if (!ExportFeedbackManager.checkFileWriteable(tableItems, file)) return;
        Path exportPath = file.toPath();
        List<Item> snapshot = List.copyOf(allItems);
        UiTaskExecutor.submitAction(
                "item-master-export",
                () -> { spreadsheetService.exportItems(snapshot, exportPath); return exportPath; },
                saved -> ExportFeedbackManager.notifyExportSuccess(tableItems, saved.toFile(), "Item Master Export", snapshot.size()),
                failure -> showError("Could not export the workbook: " + message(failure))
        );
    }

    private void loadItems() {
        Runnable finishRefresh = org.example.util.OperationalUiSupport.beginRefresh(btnRefresh);
        org.example.util.OperationalUiSupport.showLoading(tableItems,"Loading item master…");
        UiTaskExecutor.submitLatest(
                "item-master-load",
                service::getAll,
                list -> {
                    finishRefresh.run();
                    allItems.setAll(list == null ? List.of() : list);
                    clearBulkSelection();
                    applyLocalFilter();
                    updateKpis(allItems);
                },
                failure -> {
                    finishRefresh.run();
                    org.example.util.OperationalUiSupport.showError(tableItems,"Item Master could not load",failure);
                    showError("Could not load items: " + message(failure));
                }
        );
    }

    @Override public void onScreenHidden() { UiTaskExecutor.cancelPrefix("item-master-"); }

    /** Filters the already loaded master list without an API call per keystroke. */
    private void applyLocalFilter() {
        String query = txtSearch.getText() == null ? "" : txtSearch.getText().trim().toLowerCase(Locale.ROOT);
        items.setAll(allItems.stream()
            .filter(item -> query.isEmpty()
                || (item.getItemCode() != null && item.getItemCode().toLowerCase(Locale.ROOT).contains(query))
                || (item.getDescription() != null && item.getDescription().toLowerCase(Locale.ROOT).contains(query))
                || (item.getCategory() != null && item.getCategory().toLowerCase(Locale.ROOT).contains(query))
                || (item.getBrand() != null && item.getBrand().toLowerCase(Locale.ROOT).contains(query)))
            .toList());
        clearBulkSelection();
        if(items.isEmpty())org.example.util.OperationalUiSupport.showEmpty(tableItems,"No items found","Create a new item or adjust the current search.");
        lblRecordCount.setText("Showing " + items.size() + " Record" + (items.size() == 1 ? "" : "s"));
    }

    private void configureBulkSelection() {
        // Keep JavaFX row/keyboard selection and the controller-owned checkbox
        // model as one selection state. MULTIPLE restores the native
        // Shift+Up/Down (and Shift+click) range-selection behavior, while the
        // listener below keeps selectedItemCodes authoritative for bulk actions.
        tableItems.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        tableItems.getSelectionModel().getSelectedItems().addListener((javafx.collections.ListChangeListener<Item>) change -> {
            selectedItemCodes.clear();
            for (Item item : tableItems.getSelectionModel().getSelectedItems()) {
                if (item != null && item.getItemCode() != null && !item.getItemCode().isBlank()) {
                    selectedItemCodes.add(item.getItemCode());
                }
            }
            updateBulkSelectionUi();
            tableItems.refresh();
        });

        selectAllVisible.setTooltip(new Tooltip("Select all currently visible Item Master records"));
        selectAllVisible.setOnAction(event -> {
            if (selectAllVisible.isSelected()) tableItems.getSelectionModel().selectAll();
            else tableItems.getSelectionModel().clearSelection();
        });
        Label selectAllLabel = new Label("Select");
        HBox selectAllHeader = new HBox(6, selectAllVisible, selectAllLabel);
        selectAllHeader.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        colSelect.setText("");
        colSelect.setGraphic(selectAllHeader);
        colSelect.getProperties().put("erp-header-preserve", true);
        // A real observable value is required or JavaFX marks every body cell empty
        // and the checkbox graphic never renders. Selection state itself remains
        // authoritative in selectedItemCodes so refresh/filter behavior stays safe.
        colSelect.setCellValueFactory(cell -> new ReadOnlyBooleanWrapper(
            cell != null && cell.getValue() != null && cell.getValue().getItemCode() != null));
        colSelect.setSortable(false);
        colSelect.setReorderable(false);
        colSelect.setResizable(false);
        colSelect.setCellFactory(column -> new TableCell<>() {
            private final CheckBox box = new CheckBox();
            {
                box.setOnAction(event -> {
                    Item row = getTableRow() == null ? null : getTableRow().getItem();
                    int index = getIndex();
                    if (row == null || row.getItemCode() == null || row.getItemCode().isBlank()
                        || index < 0 || index >= tableItems.getItems().size()) return;
                    if (box.isSelected()) tableItems.getSelectionModel().select(index);
                    else tableItems.getSelectionModel().clearSelection(index);
                });
            }
            @Override protected void updateItem(Boolean value, boolean empty) {
                super.updateItem(value, empty);
                Item row = getTableRow() == null ? null : getTableRow().getItem();
                if (empty || row == null || row.getItemCode() == null) { setGraphic(null); return; }
                box.setSelected(selectedItemCodes.contains(row.getItemCode()));
                setGraphic(box);
            }
        });
        updateBulkSelectionUi();
    }

    private void clearBulkSelection() {
        if (tableItems != null) tableItems.getSelectionModel().clearSelection();
        selectedItemCodes.clear();
        selectAllVisible.setSelected(false);
        updateBulkSelectionUi();
    }

    private void updateBulkSelectionUi() {
        int count = selectedItemCodes.size();
        if (lblSelectedCount != null) lblSelectedCount.setText(count + " selected");
        if (btnDeleteSelected != null) {
            btnDeleteSelected.setText("Delete Selected (" + count + ")");
            btnDeleteSelected.setDisable(count == 0);
        }
        int visible = tableItems == null || tableItems.getItems() == null ? 0 : tableItems.getItems().size();
        selectAllVisible.setSelected(visible > 0 && count == visible);
        selectAllVisible.setIndeterminate(count > 0 && count < visible);
    }

    @FXML
    private void deleteSelectedItems() {
        List<String> codes = new ArrayList<>(selectedItemCodes);
        if (codes.isEmpty()) { showWarning("Select one or more visible items to delete."); return; }

        boolean first = AppDialogService.destructive(
                tableItems,
                "Confirm Bulk Deletion",
                "Delete " + codes.size() + " selected Item Master record" + (codes.size() == 1 ? "" : "s") + "?",
                "The server will first verify that none of the selected items are used by Sales, Purchases, Returns, Quotations or stock history.",
                "Verify & Delete"
        );
        if (!first) return;

        UiTaskExecutor.submitLatest(
                "item-master-bulk-validation",
                () -> service.validateBulkDelete(codes),
                validation -> {
                    if (validation == null || !validation.valid()) { showBulkDeleteBlocked(validation); return; }
                    boolean finalConfirmation = AppDialogService.doubleConfirmation(
                            tableItems,
                            "DELETE",
                            "Final Confirmation Required",
                            "Permanently delete " + codes.size() + " validated items?",
                            "Dependency validation passed. This action cannot be undone."
                    );
                    if (!finalConfirmation) return;
                    UiTaskExecutor.submitAction(
                            "item-master-bulk-delete",
                            () -> service.bulkDelete(codes),
                            result -> {
                                clearBulkSelection(); loadItems();
                                String detail = result == null || result.message() == null || result.message().isBlank()
                                        ? codes.size() + " item" + (codes.size() == 1 ? "" : "s") + " deleted successfully."
                                        : result.message() + ".";
                                org.example.util.AppDialogService.success(tableItems, "Items Deleted", detail);
                            },
                            failure -> showError("Could not delete the selected items: " + message(failure))
                    );
                },
                failure -> showError("Could not validate the selected items: " + message(failure))
        );
    }

    private void showBulkDeleteBlocked(MasterApiClient.ItemBulkDeleteValidation validation) {
        List<MasterApiClient.ItemDeleteIssue> issues = validation == null || validation.issues() == null ? List.of() : validation.issues();
        StringBuilder details = new StringBuilder("Nothing was deleted. The following selected items are protected because they are already referenced by ERP data:\n\n");
        int shown = Math.min(issues.size(), 20);
        for (int i = 0; i < shown; i++) {
            MasterApiClient.ItemDeleteIssue issue = issues.get(i);
            details.append("• ").append(issue.itemCode());
            if (issue.itemName() != null && !issue.itemName().isBlank() && !issue.itemName().equalsIgnoreCase(issue.itemCode())) details.append(" — ").append(issue.itemName());
            details.append("\n  ").append(issue.usages() == null || issue.usages().isEmpty() ? "Referenced by ERP data" : String.join(", ", issue.usages())).append("\n");
        }
        if (issues.size() > shown) details.append("\n…and ").append(issues.size() - shown).append(" more protected item(s).");
        Alert alert = new OwnedAlert(Alert.AlertType.WARNING, details.toString(), ButtonType.OK);
        alert.setHeaderText("Bulk deletion blocked");
        alert.getDialogPane().setMinWidth(620);
        alert.showAndWait();
    }

    private void installKpiIcons() {
        if(itemPageIcon!=null)itemPageIcon.getChildren().setAll(IconFactory.icon("item",24));
        setKpiIcon(itemTotalIcon, "item"); setKpiIcon(itemCategoryIcon, "category");
        setKpiIcon(itemLowIcon, "reminder"); setKpiIcon(itemValueIcon, "currency");
    }

    private void setKpiIcon(StackPane pane, String semantic) {
        if (pane != null) pane.getChildren().setAll(IconFactory.compactIcon(semantic, 22));
    }

    private void updateKpis(List<Item> source) {
        if (source == null) source = List.of();
        long categories = source.stream().map(Item::getCategory).filter(v -> v != null && !v.isBlank()).distinct().count();
        long lowStock = source.stream().filter(i -> i.getOpeningStock() <= i.getMinimumStock()).count();
        double value = source.stream().mapToDouble(i -> i.getOpeningStock() * i.getPurchasePrice()).sum();
        lblKpiTotal.setText(String.valueOf(source.size()));
        lblKpiCategories.setText(String.valueOf(categories));
        lblKpiLowStock.setText(String.valueOf(lowStock));
        lblKpiValue.setText("₹ " + String.format(Locale.of("en", "IN"), "%,.2f", value));
    }

    private void showWarning(String message) {
        Alert alert = new OwnedAlert(Alert.AlertType.WARNING, message);
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    private static String message(Throwable failure) {
        Throwable current = failure;
        while (current != null && (current.getMessage() == null || current.getMessage().isBlank()) && current.getCause() != current) current = current.getCause();
        return current == null || current.getMessage() == null ? "Unexpected error." : current.getMessage();
    }

    @Override
    public void onScreenShown(boolean reusedFromCache) {
        LinkedRecordContext.Target target = LinkedRecordContext.consume("ITEM");
        if (target != null && target.documentNo() != null && !target.documentNo().isBlank()) {
            txtSearch.setText(target.documentNo().trim());
            applyLocalFilter();
        }
    }

    private void showError(String message) {
        Alert alert = new OwnedAlert(Alert.AlertType.ERROR, message);
        alert.setHeaderText(null);
        alert.showAndWait();
    }
}
