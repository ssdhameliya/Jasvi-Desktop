package org.example.shortcut;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.TableView;
import javafx.scene.control.ListView;
import javafx.scene.control.TreeView;
import org.example.navigation.NavigationManager;
import org.example.shortcut.ShortcutRegistry.Action;
import org.example.util.AppDialogService;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Universal centralized shortcut command dispatcher.
 * Executes page-level actions contextually across all ERP screens by combining
 * direct controller reflection, intelligent UI hierarchy resolution, and contextual routing.
 */
public final class ApplicationCommandDispatcher {
    private ApplicationCommandDispatcher() {}

    public static boolean execute(Action action) {
        Spec spec = spec(action);
        if (spec == null) return false;
        Node root = NavigationManager.currentNode();
        Object controller = NavigationManager.currentController();

        // 1. Direct Controller Method Invocation (highest fidelity execution)
        if (controller != null && invoke(controller, spec.methods)) {
            return true;
        }

        // 2. Intelligent Button Discovery (exact text, prefix, fx:id prefix, or semantic contains)
        if (root != null) {
            ButtonBase button = findButton(root, spec.labels, spec.idPrefixes);
            if (button != null) {
                button.fire();
                return true;
            }
        }

        // 3. Central Context-Aware Delegation (e.g. Ctrl+N on Sales/Purchase/Quotation pages)
        if (executeContextualFallback(action, controller, NavigationManager.currentPage())) {
            return true;
        }

        if (root != null) {
            AppDialogService.info(root, "Shortcut unavailable", spec.display + " is not available on this screen.");
        }
        return false;
    }

    private static boolean executeContextualFallback(Action action, Object controller, String currentPage) {
        if (action == Action.NEW_CURRENT) {
            String cName = controller != null ? controller.getClass().getSimpleName().toLowerCase(Locale.ROOT) : "";
            String page = currentPage != null ? currentPage.toLowerCase(Locale.ROOT) : "";

            if (cName.contains("sale") || page.contains("sale")) {
                org.example.controller.DashboardController.createSaleFromWorkflow();
                return true;
            }
            if (cName.contains("purchase") || page.contains("purchase")) {
                org.example.controller.DashboardController.createPurchaseFromWorkflow();
                return true;
            }
            if (cName.contains("quotation") || page.contains("quotation")) {
                org.example.controller.DashboardController.createQuotationFromWorkflow();
                return true;
            }
        }
        return false;
    }

    /** Best-effort generic selection check used by user-configurable advanced shortcut rules. */
    public static boolean hasSelection() {
        Node root = NavigationManager.currentNode();
        return root != null && hasSelection(root);
    }

    private static boolean hasSelection(Node node) {
        if (node instanceof TableView<?> table && table.getSelectionModel() != null && table.getSelectionModel().getSelectedIndex() >= 0) return true;
        if (node instanceof ListView<?> list && list.getSelectionModel() != null && list.getSelectionModel().getSelectedIndex() >= 0) return true;
        if (node instanceof TreeView<?> tree && tree.getSelectionModel() != null && tree.getSelectionModel().getSelectedIndex() >= 0) return true;
        if (node instanceof Parent parent) for (Node child : parent.getChildrenUnmodifiable()) if (hasSelection(child)) return true;
        return false;
    }

    private static ButtonBase findButton(Node root, List<String> labels, List<String> idPrefixes) {
        List<ButtonBase> buttons = new ArrayList<>();
        collect(root, buttons);

        // 1. Exact or prefix match on button text
        for (String wanted : labels) {
            String w = norm(wanted);
            for (ButtonBase b : buttons) {
                if (!b.isVisible() || b.isDisable()) continue;
                String t = norm(b.getText());
                if (t.equals(w) || t.startsWith(w + " ") || t.startsWith(w + " (") || t.startsWith("+ " + w) || t.startsWith("+" + w)) {
                    return b;
                }
            }
        }

        // 2. Button fx:id prefix match (e.g. btnNewSale, btnAdd, btnCreate, btnRefresh)
        if (idPrefixes != null && !idPrefixes.isEmpty()) {
            for (String prefix : idPrefixes) {
                String p = norm(prefix);
                for (ButtonBase b : buttons) {
                    if (!b.isVisible() || b.isDisable()) continue;
                    String id = norm(b.getId());
                    if (id.equals(p) || id.startsWith(p)) {
                        return b;
                    }
                }
            }
        }

        // 3. Contains match for distinct keywords (length >= 4)
        for (String wanted : labels) {
            String w = norm(wanted);
            if (w.length() < 4) continue;
            for (ButtonBase b : buttons) {
                if (!b.isVisible() || b.isDisable()) continue;
                String t = norm(b.getText());
                if (t.contains(w)) return b;
            }
        }

        return null;
    }

    private static void collect(Node n, List<ButtonBase> out) {
        if (n instanceof ButtonBase b) out.add(b);
        if (n instanceof Parent p) for (Node c : p.getChildrenUnmodifiable()) collect(c, out);
    }

    private static boolean invoke(Object controller, List<String> names) {
        for (String name : names) {
            for (Class<?> c = controller.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    Method m = c.getDeclaredMethod(name);
                    if (m.getParameterCount() != 0) continue;
                    m.setAccessible(true);
                    m.invoke(controller);
                    return true;
                } catch (NoSuchMethodException ignored) {
                } catch (Exception ex) {
                    throw new IllegalStateException("Shortcut action failed: " + name, ex);
                }
            }
        }
        return false;
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static Spec spec(Action a) {
        return switch (a) {
            case SAVE_CURRENT -> new Spec(
                    "Save",
                    List.of("Save", "Save Changes", "Save Settings", "Update", "Submit", "Save Record"),
                    List.of("btnSave", "btnUpdate", "btnSubmit"),
                    List.of("save", "saveChanges", "saveCurrent", "saveSettings", "saveRecord", "submit", "onSave", "handleSave")
            );
            case EDIT_CURRENT -> new Spec(
                    "Edit",
                    List.of("Edit", "Edit Sale", "Edit Purchase", "Modify", "Edit Selected"),
                    List.of("btnEdit", "btnModify"),
                    List.of("editSelectedSale", "editSelectedPurchase", "editSelected", "editCurrent", "edit", "openSelected", "editItem", "editParty", "modifySelected", "handleEdit")
            );
            case REFRESH_CURRENT -> new Spec(
                    "Refresh",
                    List.of("Refresh", "Reload", "Sync", "Load Data"),
                    List.of("btnRefresh", "btnReload", "btnSync"),
                    List.of("refresh", "reload", "loadData", "load", "loadItems", "loadVouchers", "loadCategories", "loadTable", "calculateStatements", "fetchData", "syncData", "loadTransactions", "loadAll", "initData", "search", "applyFilters", "reloadPage")
            );
            case NEW_CURRENT -> new Spec(
                    "New",
                    List.of("New", "Add New", "Create New", "New Sale", "New Purchase", "New Quotation", "New Item", "Add Customer", "Add Supplier", "Add Item", "Record Payment", "Create", "Add"),
                    List.of("btnNew", "btnAdd", "btnCreate", "btnRecord"),
                    List.of("newEntry", "newParty", "newItem", "newQuotation", "newSale", "newPurchase", "newUser", "newMaster", "addLookup", "createNew", "addNew", "createSale", "createPurchase", "createQuotation", "createInvoice", "add", "onNew", "handleNew", "newCustomer", "newSupplier", "newVoucher", "openCreateDialog", "showCreatePopup", "addEntry")
            );
            case OPEN_SELECTED -> new Spec(
                    "Open",
                    List.of("View", "Open", "View Details", "Details", "Inspect"),
                    List.of("btnView", "btnOpen", "btnDetails", "btnInspect"),
                    List.of("openSelected", "viewSelected", "showSelected", "openCurrent", "showDetails", "viewDetails", "open", "inspectSelected")
            );
            case DELETE_SELECTED -> new Spec(
                    "Delete",
                    List.of("Delete", "Remove"),
                    List.of("btnDelete", "btnRemove"),
                    List.of("deleteSelected", "removeSelected", "delete", "remove", "deleteCurrent", "handleDelete")
            );
            case PRINT_CURRENT -> new Spec(
                    "Print",
                    List.of("Print", "Print / Preview", "Preview", "Print Register"),
                    List.of("btnPrint", "btnPreview"),
                    List.of("print", "printSelected", "preview", "printRegister", "printDocument", "handlePrint")
            );
            case EXPORT_CURRENT -> new Spec(
                    "Export",
                    List.of("Export", "Export Excel", "Excel", "View / Download Excel", "Export PDF"),
                    List.of("btnExport", "btnExportExcel", "btnExcel", "btnExportPdf"),
                    List.of("export", "exportExcel", "excelSelected", "exportPdf", "exportData", "exportRegister", "handleExport")
            );
            case CLOSE_BACK -> new Spec(
                    "Close / Back",
                    List.of("Close", "Cancel", "Back"),
                    List.of("btnClose", "btnCancel", "btnBack", "btnCloseDetails"),
                    List.of("closeDetails", "cancel", "back", "close", "onClose", "goBack", "handleClose")
            );
            case MASTER_DELETE -> new Spec(
                    "Delete Master",
                    List.of("Delete", "Remove"),
                    List.of("btnDelete", "btnRemove"),
                    List.of("deleteLookup", "deleteSelected", "removeSelected")
            );
            case MASTER_EDIT -> new Spec(
                    "Edit Master",
                    List.of("Edit", "Edit Master"),
                    List.of("btnEdit"),
                    List.of("editLookup", "editSelected", "editCurrent")
            );
            case MASTER_REFRESH -> new Spec(
                    "Refresh Master",
                    List.of("Refresh", "Reload"),
                    List.of("btnRefresh", "btnReload"),
                    List.of("loadTable", "loadCategories", "refresh", "loadData")
            );
            case MASTER_NEW -> new Spec(
                    "New Master",
                    List.of("Add Master", "New Master", "New"),
                    List.of("btnAdd", "btnNew"),
                    List.of("addLookup", "newEntry", "addNew")
            );
            default -> null;
        };
    }

    private record Spec(String display, List<String> labels, List<String> idPrefixes, List<String> methods) {}
}
