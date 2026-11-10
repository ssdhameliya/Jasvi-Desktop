package org.example.controller;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import org.example.api.admin.AdminApiClient;
import org.example.service.PermissionService;
import org.example.service.SessionService;
import org.example.shared.PermissionCatalog;
import org.example.util.IconFactory;
import org.example.util.AppDialogService;
import org.example.util.UiTaskExecutor;
import org.example.navigation.ScreenLifecycle;

import java.util.*;

/**
 * Full-width, iOS toggle-driven permission matrix.
 * Super Admin governs Company Administrator (ADMIN) access;
 * Company Administrator governs operational staff roles within their permitted ceiling.
 */
public class PermissionMatrixController implements ScreenLifecycle {
    private static String requestedRole;
    public static void requestRole(String role) { requestedRole = role; }

    @FXML private VBox root, boxCopyRole;
    @FXML private ComboBox<String> cmbRole, cmbCopyRole;
    @FXML private ComboBox<PermissionCatalog.Template> cmbTemplate;
    @FXML private TextField txtSearch;
    @FXML private CheckBox chkGrantedOnly;
    @FXML private Label lblGrantedSummary, lblRoleUsers, lblHint, lblLegend;
    @FXML private TableView<MatrixRow> table;
    @FXML private TableColumn<MatrixRow, MatrixRow> colModule, colAll, colView, colCreate, colEdit, colDelete, colExport, colApprove, colSpecial;
    @FXML private Button btnBack, btnApplyTemplate, btnCopyRole, btnReset, btnSave;

    private final AdminApiClient api = new AdminApiClient();
    private final ObservableList<MatrixRow> tableRows = FXCollections.observableArrayList();
    private final List<ModuleRow> modules = new ArrayList<>();
    private final Map<String, Long> roleUserCounts = new HashMap<>();
    private final Map<String, String> roleDisplayNames = new HashMap<>();
    private final Map<String, CheckBox> headerToggles = new HashMap<>();
    private boolean loading;
    private boolean permissionsDirty;
    private long permissionRowVersion;

    @FXML private void initialize() {
        configureIcons();
        configureTable();
        configureFilters();
        StringConverter<String> roleConverter = new StringConverter<>() {
            @Override public String toString(String code) { return code == null ? "" : roleDisplayNames.getOrDefault(code.toUpperCase(Locale.ROOT), code); }
            @Override public String fromString(String value) { return value; }
        };
        cmbRole.setConverter(roleConverter);
        cmbCopyRole.setConverter(roleConverter);
        cmbTemplate.getItems().setAll(PermissionCatalog.TEMPLATES);
        cmbRole.valueProperty().addListener((obs, oldRole, newRole) -> {
            if (loading) return;
            if (permissionsDirty && oldRole != null && newRole != null && !Objects.equals(oldRole, newRole)) {
                String displayRole = roleDisplayNames.getOrDefault(oldRole.toUpperCase(Locale.ROOT), oldRole);
                boolean leave = AppDialogService.unsaved(root, "Permission Matrix",
                        "Permission changes for " + displayRole, "role " + roleDisplayNames.getOrDefault(newRole.toUpperCase(Locale.ROOT), newRole));
                if (!leave) {
                    loading = true;
                    try { cmbRole.setValue(oldRole); }
                    finally { loading = false; }
                    return;
                }
                permissionsDirty = false;
            }
            loadRole(newRole);
        });
        loadRoles();
    }

    @Override
    public void onScreenShown(boolean reusedFromCache) {
        if (reusedFromCache) loadRoles();
    }

    private void configureIcons() {
        if (btnBack != null) btnBack.setGraphic(IconFactory.compactIcon("previous", 16));
        if (btnApplyTemplate != null) btnApplyTemplate.setGraphic(IconFactory.compactIcon("permission", 16));
        if (btnCopyRole != null) btnCopyRole.setGraphic(IconFactory.compactIcon("copy", 16));
        if (btnReset != null) btnReset.setGraphic(IconFactory.compactIcon("reset", 16));
        if (btnSave != null) btnSave.setGraphic(IconFactory.compactIcon("save", 16));
    }

    private void configureFilters() {
        if (chkGrantedOnly != null && !chkGrantedOnly.getStyleClass().contains("ios-toggle-switch")) {
            chkGrantedOnly.getStyleClass().add("ios-toggle-switch");
        }
        txtSearch.textProperty().addListener((obs, oldValue, newValue) -> rebuildVisibleRows());
        chkGrantedOnly.selectedProperty().addListener((obs, oldValue, newValue) -> rebuildVisibleRows());
    }

    private void configureTable() {
        table.setItems(tableRows);
        table.setEditable(true);
        table.setRowFactory(tv -> new TableRow<>() {
            @Override protected void updateItem(MatrixRow item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("permission-group-row", "permission-module-row");
                if (!empty && item != null) getStyleClass().add(item.group() ? "permission-group-row" : "permission-module-row");
            }
        });

        colModule.setCellValueFactory(v -> new ReadOnlyObjectWrapper<>(v.getValue()));
        colModule.setCellFactory(c -> new ModuleCell());
        configureAllColumn();
        configureActionColumn(colView, "VIEW", "View");
        configureActionColumn(colCreate, "CREATE", "Create");
        configureActionColumn(colEdit, "EDIT", "Edit");
        configureActionColumn(colDelete, "DELETE", "Delete");
        configureActionColumn(colExport, "EXPORT", "Export");
        configureActionColumn(colApprove, "APPROVE", "Approve");
        configureSpecialColumn();
    }

    private void configureAllColumn() {
        colAll.setCellValueFactory(v -> new ReadOnlyObjectWrapper<>(v.getValue()));
        colAll.setCellFactory(c -> new MatrixCheckCell(row -> row.permissions(), "All"));
        installHeaderToggle(colAll, null, "All");
    }

    private void configureActionColumn(TableColumn<MatrixRow, MatrixRow> column, String action, String label) {
        column.setCellValueFactory(v -> new ReadOnlyObjectWrapper<>(v.getValue()));
        column.setCellFactory(c -> new MatrixCheckCell(row -> {
            PermissionEntry entry = row.permission(action);
            return entry == null ? List.of() : List.of(entry);
        }, label));
        installHeaderToggle(column, action, label);
    }

    private void installHeaderToggle(TableColumn<MatrixRow, MatrixRow> column, String action, String label) {
        Label text = new Label(label);
        text.getStyleClass().add("permission-column-title");
        CheckBox toggle = new CheckBox();
        toggle.setAllowIndeterminate(false);
        toggle.getStyleClass().addAll("permission-header-check", "ios-toggle-switch");
        toggle.setTooltip(new Tooltip(action == null ? "Turn ON or OFF every visible capability" : "Turn ON or OFF " + label + " for visible modules"));
        toggle.setOnAction(event -> {
            if (!canEdit()) return;
            boolean selected = toggle.isSelected();
            toggle.setIndeterminate(false);
            for (ModuleRow row : visibleModules()) {
                if (!row.isSubscribed()) continue;
                Collection<PermissionEntry> targets = action == null ? row.permissions() : entryList(row.permission(action));
                targets.forEach(p -> p.allowed().set(selected));
            }
            permissionsChanged();
        });
        HBox header = new HBox(6, text, toggle);
        header.setAlignment(Pos.CENTER);
        column.setText(null);
        column.setGraphic(header);
        headerToggles.put(action == null ? "*" : action, toggle);
    }

    private void configureSpecialColumn() {
        colSpecial.setCellValueFactory(v -> new ReadOnlyObjectWrapper<>(v.getValue()));
        colSpecial.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(MatrixRow row, boolean empty) {
                super.updateItem(row, empty);
                if (empty || row == null || row.group()) { setGraphic(null); return; }
                ModuleRow moduleRow = (ModuleRow) row;
                List<PermissionEntry> special = moduleRow.permissions().stream()
                        .filter(p -> !PermissionCatalog.isCommonAction(p.action()))
                        .sorted(Comparator.comparing(PermissionEntry::action))
                        .toList();
                if (special.isEmpty()) {
                    Label dash = new Label("—");
                    dash.getStyleClass().add("permission-not-supported");
                    setGraphic(dash);
                    setAlignment(Pos.CENTER);
                    return;
                }
                if (special.size() == 1) {
                    PermissionEntry entry = special.getFirst();
                    CheckBox toggle = new CheckBox();
                    toggle.getStyleClass().addAll("permission-special-check", "ios-toggle-switch");
                    toggle.setSelected(moduleRow.isSubscribed() && entry.allowed().get());
                    toggle.setDisable(!canEdit() || !moduleRow.isSubscribed());
                    toggle.setTooltip(new Tooltip(PermissionCatalog.actionLabel(entry.action())
                        + (entry.description() == null || entry.description().isBlank() ? "" : "\n" + entry.description())));
                    toggle.setOnAction(event -> {
                        if (!canEdit() || !moduleRow.isSubscribed()) return;
                        entry.allowed().set(toggle.isSelected());
                        permissionsChanged();
                    });
                    setGraphic(toggle);
                    setAlignment(Pos.CENTER);
                    return;
                }
                MenuButton menu = new MenuButton();
                menu.getStyleClass().add("permission-special-menu");
                // This cell is a compact permission selector, not a row action.
                // Prevent the global action-icon decorator from turning the
                // useful "n of n" value into an unexplained icon-only button.
                menu.getProperties().put("erp.icon.skip", true);
                menu.getProperties().put("erp-icon-preserve", true);
                menu.setGraphic(null);
                menu.setContentDisplay(ContentDisplay.TEXT_ONLY);
                menu.setDisable(!canEdit() || !moduleRow.isSubscribed());
                int granted = 0;
                for (PermissionEntry entry : special) {
                    if (entry.allowed().get()) granted++;
                    CheckMenuItem item = new CheckMenuItem(PermissionCatalog.actionLabel(entry.action()));
                    item.setSelected(entry.allowed().get());
                    item.setDisable(!canEdit() || !moduleRow.isSubscribed());
                    if (entry.description() != null && !entry.description().isBlank()) item.setGraphic(IconFactory.compactIcon("permission", 13));
                    item.setOnAction(event -> {
                        entry.allowed().set(item.isSelected());
                        permissionsChanged();
                    });
                    menu.getItems().add(item);
                }
                menu.setText(granted + " / " + special.size());
                String labels = special.stream().map(p -> PermissionCatalog.actionLabel(p.action())).reduce((a,b) -> a + ", " + b).orElse("Special permissions");
                menu.setTooltip(new Tooltip("Special permissions: " + labels + "\nClick to review or change these permissions."));
                menu.setAccessibleText(granted + " of " + special.size() + " special permissions granted");
                setGraphic(menu);
                setAlignment(Pos.CENTER);
            }
        });
        Label label = new Label("Special Capabilities");
        label.getStyleClass().add("permission-column-title");
        colSpecial.setText(null);
        colSpecial.setGraphic(label);
    }

    private void loadRoles() {
        boolean superAdmin = SessionService.isSuperAdmin();
        UiTaskExecutor.submitLatest(
                "permission-matrix-roles",
                () -> api.roles().stream()
                        .filter(AdminApiClient.RoleDto::active)
                        .filter(r -> !"SUPER_ADMIN".equalsIgnoreCase(r.code()))
                        .filter(r -> superAdmin == "ADMIN".equalsIgnoreCase(r.code()))
                        .toList(),
                this::applyRoles,
                failure -> { loading = false; showError("Roles could not be loaded", asException(failure)); }
        );
    }

    private void applyRoles(List<AdminApiClient.RoleDto> roles) {
        loading = true;
        boolean superAdmin = SessionService.isSuperAdmin();
        try {
            roleDisplayNames.clear();
            roleUserCounts.clear();
            for (var role : roles == null ? List.<AdminApiClient.RoleDto>of() : roles) {
                String code = role.code() == null ? "" : role.code().trim().toUpperCase(Locale.ROOT);
                if (code.isBlank()) continue;
                String display = "ADMIN".equals(code) ? "Company Administrator (ADMIN)"
                        : (role.displayName() == null || role.displayName().isBlank() ? code : role.displayName().trim());
                roleDisplayNames.put(code, display);
                roleUserCounts.put(code, role.userCount());
            }
            if (superAdmin && !roleDisplayNames.containsKey("ADMIN")) {
                roleDisplayNames.put("ADMIN", "Company Administrator (ADMIN)");
                roleUserCounts.put("ADMIN", 1L);
            }
            cmbRole.getItems().setAll(roleDisplayNames.keySet().stream().toList());
            if (boxCopyRole != null) {
                boxCopyRole.setVisible(!superAdmin);
                boxCopyRole.setManaged(!superAdmin);
            }
            if (btnCopyRole != null) {
                btnCopyRole.setVisible(!superAdmin);
                btnCopyRole.setManaged(!superAdmin);
            }
            String requested = requestedRole == null ? null : requestedRole.trim().toUpperCase(Locale.ROOT);
            String target = requested != null && cmbRole.getItems().contains(requested)
                    ? requested : (cmbRole.getItems().isEmpty() ? null : cmbRole.getItems().getFirst());
            if (target != null) cmbRole.setValue(target);
            requestedRole = null;
        } finally {
            loading = false;
        }
        loadRole(cmbRole.getValue());
    }

    private void loadRole(String role) {
        modules.clear();
        tableRows.clear();
        if (role == null || role.isBlank()) return;
        UiTaskExecutor.submitLatest(
                "permission-matrix-role",
                () -> api.permissionSet(role),
                set -> { permissionRowVersion = set.rowVersion(); applyRolePermissions(role, set.permissions()); },
                failure -> showError("Permissions could not be loaded", asException(failure))
        );
    }

    private void applyRolePermissions(String role, List<AdminApiClient.PermissionDto> permissions) {
        if (!Objects.equals(cmbRole.getValue(), role)) return;
        Map<String, ModuleRow> byModule = new LinkedHashMap<>();
        for (var dto : permissions == null ? List.<AdminApiClient.PermissionDto>of() : permissions) {
            String moduleKey = PermissionCatalog.normalize(dto.module());
            PermissionCatalog.ModuleMeta meta = PermissionCatalog.module(moduleKey);
            ModuleRow row = byModule.computeIfAbsent(moduleKey, key -> new ModuleRow(meta));
            PermissionEntry entry = new PermissionEntry(dto.id(), moduleKey, PermissionCatalog.normalize(dto.action()), dto.description(), new SimpleBooleanProperty(dto.allowed()));
            entry.allowed().addListener((obs, oldValue, newValue) -> permissionsChanged());
            row.add(entry);
        }
        modules.clear();
        modules.addAll(byModule.values().stream()
                .sorted(Comparator.comparingInt((ModuleRow row) -> row.meta().order()).thenComparing(row -> row.meta().label()))
                .toList());
        boolean editable = canEditRole(role);
        setEditingEnabled(editable);
        long users = roleUserCounts.getOrDefault(role.toUpperCase(Locale.ROOT), 0L);
        lblRoleUsers.setText(users + (users == 1 ? " user" : " users") + " use this role");
        String displayRole = roleDisplayNames.getOrDefault(role.toUpperCase(Locale.ROOT), role);
        if (SessionService.isSuperAdmin()) {
            lblHint.setText("Super Admin Scope: Governing " + displayRole + ". Any module or capability turned OFF here is immediately hidden from Company Admin and all company staff.");
        } else {
            lblHint.setText("Company Admin Scope: Governing " + displayRole + ". Generic personal screens (such as My Profile) remain available by default to all signed-in users.");
        }
        rebuildCopyRoles();
        cmbTemplate.getSelectionModel().clearSelection();
        rebuildVisibleRows();
        permissionsDirty = false;
        org.example.navigation.UnsavedChangesManager.markClean(root);
    }

    private void rebuildCopyRoles() {
        String current = cmbRole.getValue();
        List<String> choices = cmbRole.getItems().stream()
                .filter(role -> !role.equalsIgnoreCase(current) && !"ADMIN".equalsIgnoreCase(role))
                .toList();
        cmbCopyRole.getItems().setAll(choices);
        if (!choices.isEmpty()) cmbCopyRole.getSelectionModel().selectFirst();
    }

    private void setEditingEnabled(boolean enabled) {
        btnApplyTemplate.setDisable(!enabled);
        if (btnCopyRole != null) btnCopyRole.setDisable(!enabled || cmbCopyRole.getItems().isEmpty());
        btnReset.setDisable(!enabled);
        btnSave.setDisable(!enabled);
        cmbTemplate.setDisable(!enabled);
        if (cmbCopyRole != null) cmbCopyRole.setDisable(!enabled || cmbCopyRole.getItems().isEmpty());
        headerToggles.values().forEach(cb -> cb.setDisable(!enabled));
        table.refresh();
    }

    private boolean canEdit() {
        return canEditRole(cmbRole.getValue());
    }

    private boolean canEditRole(String role) {
        if (role == null || role.isBlank()) return false;
        if ("SUPER_ADMIN".equalsIgnoreCase(role)) return false;
        if ("ADMIN".equalsIgnoreCase(role)) return SessionService.isSuperAdmin();
        return !SessionService.isSuperAdmin() && SessionService.isAdmin();
    }

    private void rebuildVisibleRows() {
        String search = txtSearch == null || txtSearch.getText() == null ? "" : txtSearch.getText().trim().toLowerCase(Locale.ROOT);
        boolean grantedOnly = chkGrantedOnly != null && chkGrantedOnly.isSelected();
        tableRows.clear();
        String currentCategory = null;
        for (ModuleRow row : modules) {
            if (!matches(row, search)) continue;
            if (grantedOnly && row.permissions().stream().noneMatch(p -> p.allowed().get())) continue;
            if (!Objects.equals(currentCategory, row.meta().category())) {
                currentCategory = row.meta().category();
                tableRows.add(MatrixRow.category(currentCategory));
            }
            tableRows.add(row);
        }
        updateSummaryAndPreview();
        table.refresh();
    }

    private boolean matches(ModuleRow row, String search) {
        if (search.isBlank()) return true;
        if (row.meta().label().toLowerCase(Locale.ROOT).contains(search) || row.meta().category().toLowerCase(Locale.ROOT).contains(search)) return true;
        return row.permissions().stream().anyMatch(p -> PermissionCatalog.actionLabel(p.action()).toLowerCase(Locale.ROOT).contains(search)
                || (p.description() != null && p.description().toLowerCase(Locale.ROOT).contains(search)));
    }

    private List<ModuleRow> visibleModules() {
        return tableRows.stream().filter(row -> row instanceof ModuleRow).map(row -> (ModuleRow) row).toList();
    }

    private void permissionsChanged() {
        if (loading) return;
        permissionsDirty = true;
        org.example.navigation.UnsavedChangesManager.touch(root);
        updateSummaryAndPreview();
        updateHeaderStates();
        table.refresh();
    }

    private void updateSummaryAndPreview() {
        long total = modules.stream().mapToLong(row -> row.permissions().size()).sum();
        long granted = modules.stream().flatMap(row -> row.permissions().stream()).filter(p -> p.allowed().get()).count();
        lblGrantedSummary.setText(granted + " of " + total + " granted");
        lblLegend.setText(visibleModules().size() + " modules shown   •   iOS Switch ON = Granted   •   OFF = Revoked   •   — = Not Supported");
        updateHeaderStates();
    }

    private void updateHeaderStates() {
        updateHeader("*", visibleModules().stream().filter(ModuleRow::isSubscribed).flatMap(row -> row.permissions().stream()).toList());
        for (String action : PermissionCatalog.COMMON_ACTIONS) {
            updateHeader(action, visibleModules().stream().filter(ModuleRow::isSubscribed).map(row -> row.permission(action)).filter(Objects::nonNull).toList());
        }
    }

    private void updateHeader(String key, List<PermissionEntry> entries) {
        CheckBox cb = headerToggles.get(key);
        if (cb == null) return;
        if (entries.isEmpty()) {
            cb.setSelected(false); cb.setIndeterminate(false); cb.setDisable(true); return;
        }
        if (canEdit()) cb.setDisable(false);
        long allowed = entries.stream().filter(p -> p.allowed().get()).count();
        cb.setIndeterminate(allowed > 0 && allowed < entries.size());
        cb.setSelected(allowed == entries.size());
    }

    @FXML private void applyTemplate() {
        if (!canEdit()) return;
        PermissionCatalog.Template template = cmbTemplate.getValue();
        if (template == null) {
            AppDialogService.info(root, "Choose a template", "Permission Matrix", "Select a permission template first.");
            return;
        }
        loading = true;
        try {
            for (ModuleRow row : modules) {
                if (!row.isSubscribed()) continue;
                for (PermissionEntry permission : row.permissions()) {
                    permission.allowed().set(PermissionCatalog.templateAllows(template, row.meta().key(), permission.action()));
                }
            }
        } finally {
            loading = false;
        }
        lblHint.setText(template.label() + " applied as unsaved changes. Review the toggles, then click Save Permissions.");
        permissionsChanged();
    }

    @FXML private void copyFromRole() {
        if (!canEdit()) return;
        String sourceRole = cmbCopyRole.getValue();
        if (sourceRole == null || sourceRole.isBlank()) return;
        UiTaskExecutor.submitLatest(
                "permission-matrix-copy",
                () -> api.permissions(sourceRole),
                permissions -> {
                    Map<String, Boolean> source = new HashMap<>();
                    for (var p : permissions) source.put(permissionKey(p.module(), p.action()), p.allowed());
                    loading = true;
                    try {
                        for (ModuleRow row : modules) {
                            if (!row.isSubscribed()) continue;
                            for (PermissionEntry p : row.permissions())
                                p.allowed().set(source.getOrDefault(permissionKey(row.meta().key(), p.action()), false));
                        }
                    } finally { loading = false; }
                    lblHint.setText("Copied " + sourceRole + " permissions as unsaved changes. Review before saving.");
                    permissionsChanged();
                },
                failure -> showError("Permissions could not be copied", asException(failure))
        );
    }

    @FXML private void save() {
        String role = cmbRole.getValue();
        if (!canEdit() || role == null) return;
        long users = roleUserCounts.getOrDefault(role.toUpperCase(Locale.ROOT), 0L);
        String displayRole = roleDisplayNames.getOrDefault(role.toUpperCase(Locale.ROOT), role);
        if (!AppDialogService.confirm(root, "Save Permissions", "Apply permission changes to " + displayRole + "?",
                "This updates access for " + users + (users == 1 ? " user" : " users") + " assigned to this role across both LOCAL and company-server deployments.")) return;
        List<AdminApiClient.PermissionSave> changes = modules.stream().flatMap(row -> row.permissions().stream())
                .map(p -> new AdminApiClient.PermissionSave(p.id(), p.allowed().get())).toList();
        btnSave.setDisable(true);
        UiTaskExecutor.submitAction(
                "permission-matrix-save-" + role,
                () -> { api.savePermissions(role, changes, permissionRowVersion); PermissionService.refresh(); return api.permissionSet(role); },
                savedSet -> {
                    permissionRowVersion = savedSet.rowVersion();
                    btnSave.setDisable(!canEdit());
                    permissionsDirty = false;
                    org.example.navigation.UnsavedChangesManager.markClean(root);
                    AppDialogService.success(root, "Permissions saved", displayRole + " access has been updated.");
                    lblHint.setText("Permissions saved for " + displayRole + ".");
                },
                failure -> { btnSave.setDisable(!canEdit()); showError("Permissions could not be saved", asException(failure)); }
        );
    }

    @FXML private void reset() {
        if (permissionsDirty && !AppDialogService.destructive(root, "Reset Permission Changes",
                "Discard unsaved permission changes?", "The selected role will be reloaded from the server and your unsaved permission changes will be lost.", "Discard Changes")) return;
        permissionsDirty = false;
        loadRole(cmbRole.getValue());
    }
    @Override public void onScreenHidden() { UiTaskExecutor.cancelPrefix("permission-matrix-"); }
    @FXML private void back() { DashboardController.navigateFromChildPage("Role Management", "/fxml/pages/RoleManagement.fxml"); }

    private static Exception asException(Throwable failure) {
        if (failure instanceof Exception exception) return exception;
        return new RuntimeException(failure);
    }

    private void showError(String title, Exception e) {
        Throwable rootCause = e;
        while (rootCause.getCause() != null) rootCause = rootCause.getCause();
        String message = rootCause.getMessage();
        AppDialogService.error(root, title, "Permission Matrix", message == null || message.isBlank() ? rootCause.getClass().getSimpleName() : message);
    }

    private static String permissionKey(String module, String action) {
        return PermissionCatalog.normalize(module) + "." + PermissionCatalog.normalize(action);
    }

    private static List<PermissionEntry> entryList(PermissionEntry entry) {
        return entry == null ? List.of() : List.of(entry);
    }

    private final class MatrixCheckCell extends TableCell<MatrixRow, MatrixRow> {
        private final CheckBox check = new CheckBox();
        private final ConsumerState targets;
        private final String tooltipPrefix;

        MatrixCheckCell(java.util.function.Function<ModuleRow, Collection<PermissionEntry>> resolver, String tooltipPrefix) {
            this.targets = resolver::apply;
            this.tooltipPrefix = tooltipPrefix;
            check.setAllowIndeterminate(false);
            check.getStyleClass().addAll("permission-matrix-check", "ios-toggle-switch");
            setAlignment(Pos.CENTER);
            check.setOnAction(event -> {
                MatrixRow item = getItem();
                if (!(item instanceof ModuleRow row) || !canEdit() || !row.isSubscribed()) return;
                Collection<PermissionEntry> entries = targets.resolve(row);
                if (entries.isEmpty()) return;
                boolean selected = check.isSelected();
                check.setIndeterminate(false);
                loading = true;
                entries.forEach(p -> p.allowed().set(selected));
                loading = false;
                permissionsChanged();
            });
        }

        @Override protected void updateItem(MatrixRow item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null || item.group()) { setGraphic(null); return; }
            ModuleRow row = (ModuleRow) item;
            if (!row.isSubscribed()) {
                check.setSelected(false);
                check.setIndeterminate(false);
                check.setDisable(true);
                check.setTooltip(new Tooltip("Not Subscribed in Agreement: " + row.meta().label()));
                setGraphic(check);
                return;
            }
            Collection<PermissionEntry> entries = targets.resolve(row);
            if (entries.isEmpty()) {
                Label dash = new Label("—");
                dash.getStyleClass().add("permission-not-supported");
                setGraphic(dash);
                return;
            }
            long allowed = entries.stream().filter(p -> p.allowed().get()).count();
            check.setSelected(allowed == entries.size());
            check.setIndeterminate(allowed > 0 && allowed < entries.size());
            check.setDisable(!canEdit());
            check.setTooltip(new Tooltip(tooltipPrefix + " • " + row.meta().label()));
            setGraphic(check);
        }
    }

    private final class ModuleCell extends TableCell<MatrixRow, MatrixRow> {
        @Override protected void updateItem(MatrixRow item, boolean empty) {
            super.updateItem(item, empty);
            setGraphic(null); setText(null);
            if (empty || item == null) return;
            if (item.group()) {
                Label label = new Label(item.label());
                label.getStyleClass().add("permission-group-label");
                setGraphic(label);
                return;
            }
            ModuleRow row = (ModuleRow) item;
            Label label = new Label(row.meta().label());
            label.getStyleClass().add("permission-module-label");
            Node icon = IconFactory.compactIcon(row.meta().iconKey(), 15);
            HBox box = new HBox(8, icon, label);
            box.setAlignment(Pos.CENTER_LEFT);
            if (!row.isSubscribed()) {
                Label lockBadge = new Label("🔒 Not Subscribed in Agreement");
                lockBadge.getStyleClass().addAll("badge-warning", "badge-compact");
                box.getChildren().add(lockBadge);
            }
            setGraphic(box);
        }
    }

    @FunctionalInterface private interface ConsumerState { Collection<PermissionEntry> resolve(ModuleRow row); }

    public sealed interface MatrixRow permits CategoryRow, ModuleRow {
        boolean group();
        String label();
        static MatrixRow category(String label) { return new CategoryRow(label); }
    }

    public record CategoryRow(String label) implements MatrixRow {
        @Override public boolean group() { return true; }
    }

    public static final class ModuleRow implements MatrixRow {
        private final PermissionCatalog.ModuleMeta meta;
        private final Map<String, PermissionEntry> permissions = new LinkedHashMap<>();
        ModuleRow(PermissionCatalog.ModuleMeta meta) { this.meta = meta; }
        void add(PermissionEntry entry) { permissions.put(entry.action(), entry); }
        PermissionEntry permission(String action) { return permissions.get(PermissionCatalog.normalize(action)); }
        Collection<PermissionEntry> permissions() { return permissions.values(); }
        PermissionCatalog.ModuleMeta meta() { return meta; }
        public boolean isSubscribed() { return PermissionService.isModuleSubscribed(meta.key()); }
        @Override public boolean group() { return true == false; }
        @Override public String label() { return meta.label(); }
    }

    public record PermissionEntry(long id, String module, String action, String description, BooleanProperty allowed) {}
}
