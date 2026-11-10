package org.example.controller;

import org.example.util.AppDialogService;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import org.example.api.licensing.ClientRegistryApiClient;
import org.example.navigation.ScreenLifecycle;
import org.example.shared.licensing.HardwareFingerprintUtil;
import org.example.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.DecimalFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ClientRegistryController implements ScreenLifecycle {
    private static final Logger log = LoggerFactory.getLogger(ClientRegistryController.class);
    private static final DecimalFormat CURRENCY_FMT = new DecimalFormat("#,##0.00");

    @FXML private StackPane pageIcon;
    @FXML private Button btnRegisterClient;
    @FXML private Button btnRefresh;

    @FXML private StackPane kpiTotalIcon;
    @FXML private StackPane kpiActiveIcon;
    @FXML private StackPane kpiPendingLeadsIcon;
    @FXML private StackPane kpiArrIcon;
    @FXML private Label lblKpiTotal;
    @FXML private Label lblKpiActive;
    @FXML private Label lblKpiPendingLeads;
    @FXML private Label lblKpiPendingTrend;
    @FXML private Label lblKpiArr;

    @FXML private TabPane registryTabPane;
    @FXML private Tab tabClients;
    @FXML private Tab tabLeads;
    @FXML private Tab tabPlans;

    // Tab 1: Clients Table
    @FXML private TextField txtSearch;
    @FXML private ComboBox<String> cmbFilterStatus;
    @FXML private Button btnResetFilter;
    @FXML private TableView<Map<String, Object>> tableClients;
    @FXML private TableColumn<Map<String, Object>, String> colStatus;
    @FXML private TableColumn<Map<String, Object>, String> colCompany;
    @FXML private TableColumn<Map<String, Object>, String> colMachineId;
    @FXML private TableColumn<Map<String, Object>, String> colRemoteUrl;
    @FXML private TableColumn<Map<String, Object>, String> colLiveStatus;
    @FXML private TableColumn<Map<String, Object>, String> colContact;
    @FXML private TableColumn<Map<String, Object>, String> colTier;
    @FXML private TableColumn<Map<String, Object>, String> colAnnualFee;
    @FXML private TableColumn<Map<String, Object>, String> colValidUntil;
    @FXML private TableColumn<Map<String, Object>, Void> colActions;

    // Tab 2: Leads Table
    @FXML private TextField txtLeadSearch;
    @FXML private ComboBox<String> cmbLeadFilterStatus;
    @FXML private Button btnResetLeadFilter;
    @FXML private TableView<Map<String, Object>> tableLeads;
    @FXML private TableColumn<Map<String, Object>, String> colLeadStatus;
    @FXML private TableColumn<Map<String, Object>, String> colLeadCompany;
    @FXML private TableColumn<Map<String, Object>, String> colLeadContact;
    @FXML private TableColumn<Map<String, Object>, String> colLeadPhone;
    @FXML private TableColumn<Map<String, Object>, String> colLeadMachineId;
    @FXML private TableColumn<Map<String, Object>, String> colLeadTier;
    @FXML private TableColumn<Map<String, Object>, String> colLeadDate;
    @FXML private TableColumn<Map<String, Object>, Void> colLeadActions;

    // Tab 3: Plans Table & Commercial Settings
    @FXML private Button btnAddPlan;
    @FXML private TableView<Map<String, Object>> tablePlans;
    @FXML private TableColumn<Map<String, Object>, String> colPlanCode;
    @FXML private TableColumn<Map<String, Object>, String> colPlanName;
    @FXML private TableColumn<Map<String, Object>, String> colPlanFee;
    @FXML private TableColumn<Map<String, Object>, String> colPlanUsers;
    @FXML private TableColumn<Map<String, Object>, String> colPlanDays;
    @FXML private TableColumn<Map<String, Object>, String> colPlanFeatures;
    @FXML private TableColumn<Map<String, Object>, String> colPlanStatus;
    @FXML private TableColumn<Map<String, Object>, Void> colPlanActions;
    @FXML private Button btnPlanPaidDetails;

    @FXML private TextField txtCommPhone;
    @FXML private TextField txtCommVpa;
    @FXML private TextField txtCommPayee;
    @FXML private TextField txtCommGst;
    @FXML private TextArea txtCommLeadTpl;
    @FXML private TextArea txtCommIssuedTpl;
    @FXML private Button btnSaveCommercialSettings;

    private Map<String, Object> currentCommercialSettings = new java.util.HashMap<>();

    private final ClientRegistryApiClient api = new ClientRegistryApiClient();

    private final ObservableList<Map<String, Object>> masterList = FXCollections.observableArrayList();
    private FilteredList<Map<String, Object>> filteredList;

    private final ObservableList<Map<String, Object>> leadsMasterList = FXCollections.observableArrayList();
    private FilteredList<Map<String, Object>> leadsFilteredList;

    private final ObservableList<Map<String, Object>> plansList = FXCollections.observableArrayList();

    @FXML
    public void initialize() {
        if (pageIcon != null) pageIcon.getChildren().setAll(IconFactory.icon("shield", 22));
        if (kpiTotalIcon != null) kpiTotalIcon.getChildren().setAll(IconFactory.icon("customer", 18));
        if (kpiActiveIcon != null) kpiActiveIcon.getChildren().setAll(IconFactory.icon("active", 18));
        if (kpiPendingLeadsIcon != null) kpiPendingLeadsIcon.getChildren().setAll(IconFactory.icon("clock", 18));
        if (kpiArrIcon != null) kpiArrIcon.getChildren().setAll(IconFactory.icon("dollar", 18));

        if (btnRegisterClient != null) btnRegisterClient.setGraphic(IconFactory.compactIcon("add", 14));
        if (btnRefresh != null) btnRefresh.setGraphic(IconFactory.compactIcon("refresh", 14));
        if (btnResetFilter != null) btnResetFilter.setGraphic(IconFactory.compactIcon("reset", 14));
        if (btnResetLeadFilter != null) btnResetLeadFilter.setGraphic(IconFactory.compactIcon("reset", 14));
        if (btnAddPlan != null) btnAddPlan.setGraphic(IconFactory.compactIcon("add", 14));
        if (btnSaveCommercialSettings != null) btnSaveCommercialSettings.setGraphic(IconFactory.compactIcon("settings", 14));

        if (cmbFilterStatus != null) {
            cmbFilterStatus.setItems(FXCollections.observableArrayList("All Statuses", "ACTIVE", "EXPIRING", "EXPIRED", "REVOKED"));
            cmbFilterStatus.setValue("All Statuses");
            cmbFilterStatus.valueProperty().addListener((obs, o, n) -> applyFilters());
        }

        if (cmbLeadFilterStatus != null) {
            cmbLeadFilterStatus.setItems(FXCollections.observableArrayList("All Statuses", "PENDING", "APPROVED", "REJECTED"));
            cmbLeadFilterStatus.setValue("All Statuses");
            cmbLeadFilterStatus.valueProperty().addListener((obs, o, n) -> applyLeadFilters());
        }

        setupTableColumns();
        setupFiltering();

        setupLeadTableColumns();
        setupLeadFiltering();

        setupPlanTableColumns();
    }

    // ==========================================
    // TAB 1: REGISTERED CLIENTS SETUP
    // ==========================================

    private void setupTableColumns() {
        if (tableClients == null) return;
        DynamicTableLayoutManager.install(tableClients);

        colStatus.setCellValueFactory(cell -> new SimpleStringProperty(String.valueOf(cell.getValue().getOrDefault("status", "ACTIVE"))));
        colStatus.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String status, boolean empty) {
                super.updateItem(status, empty);
                if (empty || status == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                Label badge = new Label(status);
                badge.getStyleClass().add("status-badge");
                String upper = status.toUpperCase(Locale.ROOT);
                if ("ACTIVE".equals(upper)) {
                    badge.getStyleClass().addAll("status-positive", "badge-active");
                } else if ("REVOKED".equals(upper)) {
                    badge.getStyleClass().addAll("status-negative", "badge-cancelled");
                } else {
                    badge.getStyleClass().addAll("status-warning", "badge-warning");
                }
                setGraphic(badge);
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                setAlignment(Pos.CENTER);
            }
        });

        colCompany.setCellValueFactory(cell -> new SimpleStringProperty(String.valueOf(cell.getValue().getOrDefault("company_name", "-"))));

        colMachineId.setCellValueFactory(cell -> {
            String m = String.valueOf(cell.getValue().getOrDefault("machine_id", ""));
            if (m.length() == 16) {
                m = String.format("%s-%s-%s-%s", m.substring(0, 4), m.substring(4, 8), m.substring(8, 12), m.substring(12, 16));
            }
            return new SimpleStringProperty(m);
        });

        colRemoteUrl.setCellValueFactory(cell -> new SimpleStringProperty(String.valueOf(cell.getValue().getOrDefault("remote_url", ""))));
        colRemoteUrl.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String url, boolean empty) {
                super.updateItem(url, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                if (url == null || url.isBlank() || "null".equalsIgnoreCase(url)) {
                    setGraphic(null);
                    setText("⚪ No Tunnel");
                    setContentDisplay(ContentDisplay.TEXT_ONLY);
                    setAlignment(Pos.CENTER);
                    return;
                }
                Hyperlink link = new Hyperlink(url);
                link.setGraphic(IconFactory.compactIcon("link", 13));
                link.getStyleClass().add("table-hyperlink");
                link.setTooltip(new Tooltip("Click to test or open remote support endpoint:\n" + url));
                link.setOnAction(e -> {
                    try {
                        if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                            java.awt.Desktop.getDesktop().browse(URI.create(url));
                        }
                    } catch (Exception ex) {
                        AppDialogService.error(tableClients, "Browser Error", "Could not open URL", ex.getMessage());
                    }
                });
                setGraphic(link);
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                setAlignment(Pos.CENTER_LEFT);
            }
        });

        colLiveStatus.setCellValueFactory(cell -> {
            Object hb = cell.getValue().get("last_heartbeat_at");
            Object ver = cell.getValue().get("client_version");
            Object activeUsers = cell.getValue().get("active_users_count");
            Object maxUsers = cell.getValue().get("max_users");
            String hbStr = (hb != null && !"null".equalsIgnoreCase(hb.toString())) ? hb.toString() : "";
            String v = (ver != null && !ver.toString().isBlank() && !"null".equalsIgnoreCase(ver.toString())) ? ver.toString() : "";
            int act = 0;
            if (activeUsers instanceof Number n) {
                act = n.intValue();
            } else if (activeUsers != null) {
                try { act = Integer.parseInt(activeUsers.toString().trim()); } catch (Exception ignored) {}
            }
            int max = 1;
            if (maxUsers instanceof Number n) {
                max = n.intValue();
            } else if (maxUsers != null) {
                try { max = Integer.parseInt(maxUsers.toString().trim()); } catch (Exception ignored) {}
            }
            return new SimpleStringProperty(hbStr + "|" + v + "|" + act + "|" + max);
        });
        colLiveStatus.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String raw, boolean empty) {
                super.updateItem(raw, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                if (raw == null || raw.isBlank()) {
                    Label offline = new Label("⚪ Offline");
                    offline.getStyleClass().addAll("status-badge", "status-neutral");
                    setGraphic(offline);
                    setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                    setAlignment(Pos.CENTER);
                    return;
                }
                String[] parts = raw.split("\\|", 4);
                String hbStr = parts[0];
                String ver = parts.length > 1 ? parts[1] : "";
                int activeCount = 1;
                int maxUsers = 1;
                try {
                    if (parts.length > 2 && !parts[2].isBlank()) activeCount = Integer.parseInt(parts[2]);
                    if (parts.length > 3 && !parts[3].isBlank()) maxUsers = Integer.parseInt(parts[3]);
                } catch (Exception ignored) {}

                Instant hbTime = null;
                try {
                    hbTime = Instant.parse(hbStr);
                } catch (Exception ignored) {
                    try {
                        hbTime = java.time.LocalDateTime.parse(hbStr.replace(" ", "T")).atZone(java.time.ZoneId.systemDefault()).toInstant();
                    } catch (Exception ignored2) {}
                }

                Label badge;
                String userCapacityLabel = activeCount + "/" + maxUsers + " Users";
                if (hbTime != null && Duration.between(hbTime, Instant.now()).toMinutes() <= 15) {
                    if (maxUsers > 0 && activeCount >= maxUsers) {
                        badge = new Label("🟡 At Limit • " + userCapacityLabel);
                        badge.getStyleClass().addAll("status-badge", "status-warning");
                    } else {
                        badge = new Label("🟢 Online • " + userCapacityLabel);
                        badge.getStyleClass().addAll("status-badge", "status-positive", "badge-active");
                    }
                } else if (hbTime != null && Duration.between(hbTime, Instant.now()).toHours() < 24) {
                    long hours = Duration.between(hbTime, Instant.now()).toHours();
                    badge = new Label("🟡 Seen " + (hours == 0 ? "recently" : hours + "h ago") + " • " + userCapacityLabel);
                    badge.getStyleClass().addAll("status-badge", "status-warning");
                } else {
                    badge = new Label("⚪ Offline • " + (hbStr.length() > 10 ? hbStr.substring(0, 10) : hbStr));
                    badge.getStyleClass().addAll("status-badge", "status-neutral");
                }

                String tooltipText = "Active Connected Users: " + activeCount + " of " + maxUsers +
                        (ver.isBlank() ? "" : "\nERP Client Version: " + ver) +
                        (hbStr.isBlank() ? "" : "\nLast Live Heartbeat: " + hbStr);
                Tooltip tooltip = new Tooltip(tooltipText);
                Tooltip.install(badge, tooltip);

                setGraphic(badge);
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                setAlignment(Pos.CENTER);
            }
        });

        colContact.setCellValueFactory(cell -> {
            String person = String.valueOf(cell.getValue().getOrDefault("contact_person", ""));
            String phone = String.valueOf(cell.getValue().getOrDefault("phone", ""));
            String combined = person.isBlank() ? phone : (phone.isBlank() ? person : person + " (" + phone + ")");
            return new SimpleStringProperty(combined.isBlank() ? "-" : combined);
        });

        colTier.setCellValueFactory(cell -> {
            String tier = String.valueOf(cell.getValue().getOrDefault("tier", "GOLD"));
            Object maxUsers = cell.getValue().get("max_users");
            return new SimpleStringProperty(tier + " (" + (maxUsers != null ? maxUsers : 5) + " Users)");
        });

        colAnnualFee.setCellValueFactory(cell -> {
            Object fee = cell.getValue().get("annual_fee");
            if (fee instanceof Number n) {
                return new SimpleStringProperty("₹" + CURRENCY_FMT.format(n.doubleValue()));
            }
            return new SimpleStringProperty("₹3,500.00");
        });

        colValidUntil.setCellValueFactory(cell -> {
            Object vu = cell.getValue().get("valid_until");
            if (vu != null) {
                String str = vu.toString();
                try {
                    return new SimpleStringProperty(BusinessClock.formatDate(str));
                } catch (Exception ignored) {
                    return new SimpleStringProperty(str.length() > 10 ? str.substring(0, 10) : str);
                }
            }
            return new SimpleStringProperty("-");
        });

        colActions.setCellFactory(col -> new TableCell<>() {
            private final MenuButton actionsBtn = new MenuButton("Actions");

            {
                actionsBtn.getStyleClass().addAll("table-action-menu", "approved-row-action");
                actionsBtn.setGraphic(IconFactory.compactIcon("actions", 14));
                actionsBtn.setContentDisplay(ContentDisplay.LEFT);
                actionsBtn.setGraphicTextGap(6);

                MenuItem miEditAccess = new MenuItem("Edit Plan & Module Access");
                miEditAccess.setGraphic(IconFactory.compactIcon("edit", 13));
                miEditAccess.setOnAction(e -> {
                    Map<String, Object> item = getTableView().getItems().get(getIndex());
                    if (item != null) showClientLicenseDialog(item);
                });

                MenuItem miRenew = new MenuItem("Renew (+1 Year)");
                miRenew.setGraphic(IconFactory.compactIcon("refresh", 13));
                miRenew.setOnAction(e -> {
                    Map<String, Object> item = getTableView().getItems().get(getIndex());
                    if (item != null) handleRenewClient(item, 365);
                });

                MenuItem miRenewCustom = new MenuItem("Custom Renewal Days...");
                miRenewCustom.setGraphic(IconFactory.compactIcon("calendar", 13));
                miRenewCustom.setOnAction(e -> {
                    Map<String, Object> item = getTableView().getItems().get(getIndex());
                    if (item != null) promptCustomRenewal(item);
                });

                MenuItem miCopyToken = new MenuItem("Copy License Token");
                miCopyToken.setGraphic(IconFactory.compactIcon("copy", 13));
                miCopyToken.setOnAction(e -> {
                    Map<String, Object> item = getTableView().getItems().get(getIndex());
                    if (item != null) copyLicenseToken(item);
                });

                MenuItem miExport = new MenuItem("Export .jlic File");
                miExport.setGraphic(IconFactory.compactIcon("download", 13));
                miExport.setOnAction(e -> {
                    Map<String, Object> item = getTableView().getItems().get(getIndex());
                    if (item != null) exportLicenseFile(item);
                });

                MenuItem miEmail = new MenuItem("Send via Email");
                miEmail.setGraphic(IconFactory.compactIcon("email", 13));
                miEmail.setOnAction(e -> {
                    Map<String, Object> item = getTableView().getItems().get(getIndex());
                    if (item != null) sendEmail(item);
                });

                MenuItem miWhatsApp = new MenuItem("Send via WhatsApp");
                miWhatsApp.setGraphic(IconFactory.compactIcon("share", 13));
                miWhatsApp.setOnAction(e -> {
                    Map<String, Object> item = getTableView().getItems().get(getIndex());
                    if (item != null) sendWhatsApp(item);
                });

                MenuItem miDispatchAll = new MenuItem("Multi-Channel Dispatch");
                miDispatchAll.setGraphic(IconFactory.compactIcon("dispatch", 13));
                miDispatchAll.setOnAction(e -> {
                    Map<String, Object> item = getTableView().getItems().get(getIndex());
                    if (item != null) dispatchAll(item);
                });

                MenuItem miTestUrl = new MenuItem("Test Remote URL");
                miTestUrl.setGraphic(IconFactory.compactIcon("link", 13));
                miTestUrl.setOnAction(e -> {
                    Map<String, Object> item = getTableView().getItems().get(getIndex());
                    if (item != null) testRemoteUrl(item);
                });

                MenuItem miRevoke = new MenuItem("Revoke License");
                miRevoke.setGraphic(IconFactory.compactIcon("delete", 13));
                miRevoke.setOnAction(e -> {
                    Map<String, Object> item = getTableView().getItems().get(getIndex());
                    if (item != null) handleRevokeClient(item);
                });

                actionsBtn.getItems().addAll(miEditAccess, miRenew, miRenewCustom, miCopyToken, miExport, miEmail, miWhatsApp, miDispatchAll, miTestUrl, new SeparatorMenuItem(), miRevoke);
                IconFactory.decorateActionMenu(actionsBtn);
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : actionsBtn);
            }
        });
    }

    private void setupFiltering() {
        filteredList = new FilteredList<>(masterList, p -> true);
        tableClients.setItems(filteredList);

        if (txtSearch != null) {
            txtSearch.textProperty().addListener((obs, o, n) -> applyFilters());
        }
    }

    private void applyFilters() {
        String query = txtSearch != null ? txtSearch.getText().trim().toLowerCase(Locale.ROOT) : "";
        String status = cmbFilterStatus != null ? cmbFilterStatus.getValue() : "All Statuses";

        filteredList.setPredicate(item -> {
            if (status != null && !"All Statuses".equalsIgnoreCase(status)) {
                String itemStatus = String.valueOf(item.getOrDefault("status", "ACTIVE"));
                if (!status.equalsIgnoreCase(itemStatus)) return false;
            }

            if (!query.isBlank()) {
                String comp = String.valueOf(item.getOrDefault("company_name", "")).toLowerCase(Locale.ROOT);
                String mach = String.valueOf(item.getOrDefault("machine_id", "")).toLowerCase(Locale.ROOT);
                String cont = String.valueOf(item.getOrDefault("contact_person", "")).toLowerCase(Locale.ROOT);
                String ph = String.valueOf(item.getOrDefault("phone", "")).toLowerCase(Locale.ROOT);
                return comp.contains(query) || mach.contains(query) || cont.contains(query) || ph.contains(query);
            }

            return true;
        });
    }

    // ==========================================
    // TAB 2: INBOUND LEADS / APPROVAL REQUESTS
    // ==========================================

    private void setupLeadTableColumns() {
        if (tableLeads == null) return;
        DynamicTableLayoutManager.install(tableLeads);

        colLeadStatus.setCellValueFactory(cell -> new SimpleStringProperty(String.valueOf(cell.getValue().getOrDefault("status", "PENDING"))));
        colLeadStatus.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String status, boolean empty) {
                super.updateItem(status, empty);
                if (empty || status == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                Label badge = new Label(status);
                badge.getStyleClass().add("status-badge");
                String upper = status.toUpperCase(Locale.ROOT);
                if ("APPROVED".equals(upper)) {
                    badge.getStyleClass().addAll("status-positive", "badge-active");
                } else if ("REJECTED".equals(upper)) {
                    badge.getStyleClass().addAll("status-negative", "badge-cancelled");
                } else {
                    badge.getStyleClass().addAll("status-warning", "badge-warning");
                }
                setGraphic(badge);
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                setAlignment(Pos.CENTER);
            }
        });

        colLeadCompany.setCellValueFactory(cell -> new SimpleStringProperty(String.valueOf(cell.getValue().getOrDefault("company_name", "-"))));
        colLeadContact.setCellValueFactory(cell -> new SimpleStringProperty(String.valueOf(cell.getValue().getOrDefault("contact_person", "-"))));
        colLeadPhone.setCellValueFactory(cell -> new SimpleStringProperty(String.valueOf(cell.getValue().getOrDefault("phone", "-"))));

        colLeadMachineId.setCellValueFactory(cell -> {
            String m = String.valueOf(cell.getValue().getOrDefault("machine_id", ""));
            if (m.length() == 16) {
                m = String.format("%s-%s-%s-%s", m.substring(0, 4), m.substring(4, 8), m.substring(8, 12), m.substring(12, 16));
            }
            return new SimpleStringProperty(m);
        });

        colLeadTier.setCellValueFactory(cell -> new SimpleStringProperty(String.valueOf(cell.getValue().getOrDefault("requested_tier", "GOLD"))));

        colLeadDate.setCellValueFactory(cell -> {
            Object dt = cell.getValue().get("created_at");
            if (dt != null) {
                try {
                    return new SimpleStringProperty(BusinessClock.formatDate(dt.toString()));
                } catch (Exception ignored) {
                    return new SimpleStringProperty(dt.toString().substring(0, Math.min(10, dt.toString().length())));
                }
            }
            return new SimpleStringProperty("-");
        });

        colLeadActions.setCellFactory(col -> new TableCell<>() {
            private final MenuButton actionsBtn = new MenuButton("Actions");
            private final MenuItem miView = new MenuItem("View Request Details");
            private final MenuItem miApprove = new MenuItem("Approve & Issue License");
            private final MenuItem miReject = new MenuItem("Reject Request");
            private final MenuItem miCopyMachine = new MenuItem("Copy Machine Hardware ID");
            private final MenuItem miWhatsApp = new MenuItem("Send via WhatsApp");
            private final MenuItem miEmail = new MenuItem("Send via Email");
            private final MenuItem miDelete = new MenuItem("Delete Request Lead");

            {
                actionsBtn.getStyleClass().addAll("table-action-menu", "approved-row-action");
                actionsBtn.setGraphic(IconFactory.compactIcon("actions", 14));
                actionsBtn.setContentDisplay(ContentDisplay.LEFT);
                actionsBtn.setGraphicTextGap(6);

                miView.setGraphic(IconFactory.compactIcon("view", 13));
                miView.setOnAction(e -> {
                    Map<String, Object> lead = getTableView().getItems().get(getIndex());
                    if (lead != null) showLeadDetailsDialog(lead);
                });

                miApprove.setGraphic(IconFactory.compactIcon("check", 13));
                miApprove.setOnAction(e -> {
                    Map<String, Object> lead = getTableView().getItems().get(getIndex());
                    if (lead != null) handleApproveLead(lead);
                });

                miReject.setGraphic(IconFactory.compactIcon("delete", 13));
                miReject.setOnAction(e -> {
                    Map<String, Object> lead = getTableView().getItems().get(getIndex());
                    if (lead != null) handleRejectLead(lead);
                });

                miCopyMachine.setGraphic(IconFactory.compactIcon("copy", 13));
                miCopyMachine.setOnAction(e -> {
                    Map<String, Object> lead = getTableView().getItems().get(getIndex());
                    if (lead != null) copyMachineId(String.valueOf(lead.get("machine_id")));
                });

                miWhatsApp.setGraphic(IconFactory.compactIcon("share", 13));
                miWhatsApp.setOnAction(e -> {
                    Map<String, Object> lead = getTableView().getItems().get(getIndex());
                    if (lead != null) sendWhatsAppLead(lead);
                });

                miEmail.setGraphic(IconFactory.compactIcon("email", 13));
                miEmail.setOnAction(e -> {
                    Map<String, Object> lead = getTableView().getItems().get(getIndex());
                    if (lead != null) sendEmailLead(lead);
                });

                miDelete.setGraphic(IconFactory.compactIcon("delete", 13));
                miDelete.setOnAction(e -> {
                    Map<String, Object> lead = getTableView().getItems().get(getIndex());
                    if (lead != null) handleDeleteLead(lead);
                });

                actionsBtn.getItems().addAll(
                        miView,
                        miApprove,
                        miReject,
                        miCopyMachine,
                        miWhatsApp,
                        miEmail,
                        new SeparatorMenuItem(),
                        miDelete
                );
                IconFactory.decorateActionMenu(actionsBtn);
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getIndex() >= getTableView().getItems().size()) {
                    setGraphic(null);
                    return;
                }
                Map<String, Object> lead = getTableView().getItems().get(getIndex());
                String status = String.valueOf(lead.getOrDefault("status", "PENDING"));
                boolean pending = "PENDING".equalsIgnoreCase(status);
                miApprove.setDisable(!pending);
                miReject.setDisable(!pending);
                setGraphic(actionsBtn);
            }
        });
    }

    private void setupLeadFiltering() {
        leadsFilteredList = new FilteredList<>(leadsMasterList, p -> true);
        tableLeads.setItems(leadsFilteredList);

        if (txtLeadSearch != null) {
            txtLeadSearch.textProperty().addListener((obs, o, n) -> applyLeadFilters());
        }
    }

    private void applyLeadFilters() {
        String query = txtLeadSearch != null ? txtLeadSearch.getText().trim().toLowerCase(Locale.ROOT) : "";
        String status = cmbLeadFilterStatus != null ? cmbLeadFilterStatus.getValue() : "All Statuses";

        leadsFilteredList.setPredicate(item -> {
            if (status != null && !"All Statuses".equalsIgnoreCase(status)) {
                String itemStatus = String.valueOf(item.getOrDefault("status", "PENDING"));
                if (!status.equalsIgnoreCase(itemStatus)) return false;
            }

            if (!query.isBlank()) {
                String comp = String.valueOf(item.getOrDefault("company_name", "")).toLowerCase(Locale.ROOT);
                String mach = String.valueOf(item.getOrDefault("machine_id", "")).toLowerCase(Locale.ROOT);
                String cont = String.valueOf(item.getOrDefault("contact_person", "")).toLowerCase(Locale.ROOT);
                String ph = String.valueOf(item.getOrDefault("phone", "")).toLowerCase(Locale.ROOT);
                return comp.contains(query) || mach.contains(query) || cont.contains(query) || ph.contains(query);
            }

            return true;
        });
    }

    private void handleApproveLead(Map<String, Object> lead) {
        Long id = ((Number) lead.get("id")).longValue();
        String company = String.valueOf(lead.getOrDefault("company_name", "Client"));
        String defaultTier = String.valueOf(lead.getOrDefault("requested_tier", "GOLD"));

        Dialog<Map<String, Object>> dialog = new OwnedDialog<>(tableLeads);
        dialog.setTitle("Approve Lead & Issue License");
        dialog.setHeaderText("Issue cryptographically signed license for " + company);

        ButtonType btnApprove = new ButtonType("Approve & Issue", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(btnApprove, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 24, 20, 24));

        ComboBox<String> cmbTier = new ComboBox<>(activePlanTierCodes());
        cmbTier.setValue(cmbTier.getItems().contains(defaultTier) ? defaultTier : (cmbTier.getItems().isEmpty() ? defaultTier : cmbTier.getItems().getFirst()));
        TextField txtDays = new TextField("365");

        grid.add(new Label("Company:"), 0, 0);
        grid.add(new Label(company), 1, 0);
        grid.add(new Label("Machine ID:"), 0, 1);
        grid.add(new Label(String.valueOf(lead.get("machine_id"))), 1, 1);
        grid.add(new Label("License Tier *:"), 0, 2);
        grid.add(cmbTier, 1, 2);
        grid.add(new Label("Validity (Days) *:"), 0, 3);
        grid.add(txtDays, 1, 3);

        dialog.getDialogPane().setContent(grid);
        PopupTableWorkspace.prepareDialog(dialog, 600);
        dialog.setResultConverter(b -> {
            if (b == btnApprove) {
                int days = 365;
                try { days = Integer.parseInt(txtDays.getText().trim()); } catch (Exception ignored) {}
                return Map.of("tier", cmbTier.getValue(), "days", days);
            }
            return null;
        });

        dialog.showAndWait().ifPresent(req -> {
            UiTaskExecutor.submitActionWithOverlay(
                    tableLeads,
                    "Approving Registration Lead & Generating License...",
                    "approve-lead-" + id,
                    () -> api.approveLead(id, String.valueOf(req.get("tier")), (Integer) req.get("days")),
                    res -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> dispatch = (Map<String, Object>) res.get("dispatch");
                        boolean waSent = dispatch != null && Boolean.TRUE.equals(dispatch.get("whatsappSent"));
                        boolean emSent = dispatch != null && Boolean.TRUE.equals(dispatch.get("emailSent"));

                        if (waSent) {
                            AppDialogService.success(tableLeads, "Lead Approved & Dispatched",
                                    "License issued and dispatched automatically via WhatsApp to " + company + "!" +
                                            (emSent ? " (and official email sent)" : ""));
                        } else {
                            AppDialogService.success(tableLeads, "Lead Approved", "License issued for " + company + ". Opening WhatsApp Web...");
                            openManualWhatsApp(res);
                        }
                        loadDataAsync();
                    },
                    err -> AppDialogService.error(tableLeads, "Approval Error", "Could not approve lead", err.getMessage())
            );
        });
    }

    private void handleRejectLead(Map<String, Object> lead) {
        Long id = ((Number) lead.get("id")).longValue();
        String company = String.valueOf(lead.getOrDefault("company_name", "Client"));

        boolean confirmed = AppDialogService.confirm(
                tableLeads,
                "Reject Lead",
                "Reject registration request for " + company + "?",
                "This inbound lead will be marked as REJECTED."
        );
        if (!confirmed) return;

        UiTaskExecutor.submitActionWithOverlay(
                tableLeads,
                "Rejecting Registration Lead...",
                "reject-lead-" + id,
                () -> api.rejectLead(id),
                res -> {
                    AppDialogService.info(tableLeads, "Lead Rejected", "Registration for " + company + " rejected.");
                    loadDataAsync();
                },
                err -> AppDialogService.error(tableLeads, "Reject Error", "Could not reject lead", err.getMessage())
        );
    }

    private void copyMachineId(String machineId) {
        if (machineId == null || machineId.isBlank()) return;
        String clean = machineId.replace("-", "").toUpperCase(Locale.ROOT);
        String formatted = clean.length() == 16
                ? String.format("%s-%s-%s-%s", clean.substring(0, 4), clean.substring(4, 8), clean.substring(8, 12), clean.substring(12, 16))
                : clean;
        ClipboardContent content = new ClipboardContent();
        content.putString(formatted);
        Clipboard.getSystemClipboard().setContent(content);
        AppDialogService.info(tableLeads, "Copied", "Machine Hardware ID copied to clipboard:\n" + formatted);
    }

    private void showLeadDetailsDialog(Map<String, Object> lead) {
        String company = String.valueOf(lead.getOrDefault("company_name", "Client"));
        String contact = String.valueOf(lead.getOrDefault("contact_person", "-"));
        String phone = String.valueOf(lead.getOrDefault("phone", "-"));
        String email = String.valueOf(lead.getOrDefault("email", "-"));
        String machine = String.valueOf(lead.getOrDefault("machine_id", "-"));
        if (machine.length() == 16) {
            machine = String.format("%s-%s-%s-%s", machine.substring(0, 4), machine.substring(4, 8), machine.substring(8, 12), machine.substring(12, 16));
        }
        String tier = String.valueOf(lead.getOrDefault("requested_tier", "GOLD"));
        String status = String.valueOf(lead.getOrDefault("status", "PENDING"));
        String notes = String.valueOf(lead.getOrDefault("notes", "-"));
        String remoteUrl = String.valueOf(lead.getOrDefault("remote_url", "-"));
        String createdAt = String.valueOf(lead.getOrDefault("created_at", "-"));

        Dialog<Void> dialog = new OwnedDialog<>(tableLeads);
        dialog.setTitle("Registration Lead Dossier - " + company);
        dialog.setHeaderText("Inbound Activation Lead Details for " + company);

        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(12);
        grid.setPadding(new Insets(20, 24, 20, 24));

        Label lblStatusBadge = new Label(status);
        lblStatusBadge.getStyleClass().add("status-badge");
        if ("APPROVED".equalsIgnoreCase(status)) {
            lblStatusBadge.getStyleClass().addAll("status-positive", "badge-active");
        } else if ("REJECTED".equalsIgnoreCase(status)) {
            lblStatusBadge.getStyleClass().addAll("status-negative", "badge-cancelled");
        } else {
            lblStatusBadge.getStyleClass().addAll("status-warning", "badge-warning");
        }

        int r = 0;
        grid.add(new Label("Status:"), 0, r);
        grid.add(lblStatusBadge, 1, r++);
        grid.add(new Label("Company Name:"), 0, r);
        grid.add(new Label(company), 1, r++);
        grid.add(new Label("Contact Person:"), 0, r);
        grid.add(new Label(contact), 1, r++);
        grid.add(new Label("Phone / WhatsApp:"), 0, r);
        grid.add(new Label(phone), 1, r++);
        grid.add(new Label("Email Address:"), 0, r);
        grid.add(new Label(email), 1, r++);
        grid.add(new Label("Machine Hardware ID:"), 0, r);
        grid.add(new Label(machine), 1, r++);
        grid.add(new Label("Requested Tier:"), 0, r);
        grid.add(new Label(tier), 1, r++);
        grid.add(new Label("Remote Cloud URL:"), 0, r);
        grid.add(new Label(remoteUrl), 1, r++);
        grid.add(new Label("Submission Date:"), 0, r);
        grid.add(new Label(createdAt), 1, r++);
        grid.add(new Label("Notes / Telemetry:"), 0, r);
        grid.add(new Label(notes), 1, r++);

        dialog.getDialogPane().setContent(grid);
        PopupTableWorkspace.prepareDialog(dialog, 620);
        dialog.showAndWait();
    }

    private void handleDeleteLead(Map<String, Object> lead) {
        Long id = ((Number) lead.get("id")).longValue();
        String company = String.valueOf(lead.getOrDefault("company_name", "Client"));

        boolean confirmed = AppDialogService.confirm(
                tableLeads,
                "Delete Request Lead",
                "Permanently delete inbound registration lead for " + company + "?",
                "This action cannot be undone."
        );
        if (!confirmed) return;

        UiTaskExecutor.submitActionWithOverlay(
                tableLeads,
                "Deleting Registration Lead...",
                "delete-lead-" + id,
                () -> api.deleteLead(id),
                res -> {
                    AppDialogService.info(tableLeads, "Lead Deleted", "Registration lead for " + company + " deleted.");
                    loadDataAsync();
                },
                err -> AppDialogService.error(tableLeads, "Delete Error", "Could not delete lead", err.getMessage())
        );
    }

    private void sendEmailLead(Map<String, Object> lead) {
        String email = String.valueOf(lead.getOrDefault("email", "")).trim();
        if (email.isBlank()) {
            AppDialogService.warning(tableLeads, "Email Missing", "No email address registered for this lead.");
            return;
        }
        String company = String.valueOf(lead.getOrDefault("company_name", "Client"));
        String machine = String.valueOf(lead.getOrDefault("machine_id", ""));
        String status = String.valueOf(lead.getOrDefault("status", "PENDING"));

        String subject = "Jasvi ERP License Request - " + company;
        String body = "Dear " + company + " Team,\n\n" +
                ("APPROVED".equalsIgnoreCase(status)
                        ? "Your Jasvi ERP activation request for Terminal ID: " + machine + " has been approved! Please restart your Jasvi application to activate."
                        : "We have received your registration for Terminal ID: " + machine + " and our team is currently processing it.") +
                "\n\nBest Regards,\nJasvi ERP Support Team";

        try {
            String mailto = "mailto:" + URLEncoder.encode(email, StandardCharsets.UTF_8) +
                    "?subject=" + URLEncoder.encode(subject, StandardCharsets.UTF_8) +
                    "&body=" + URLEncoder.encode(body, StandardCharsets.UTF_8);
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(URI.create(mailto));
            }
        } catch (Exception ex) {
            AppDialogService.error(tableLeads, "Email Error", "Could not open default mail client", ex.getMessage());
        }
    }

    private void sendWhatsAppLead(Map<String, Object> lead) {
        String phone = String.valueOf(lead.getOrDefault("phone", "")).replaceAll("[^0-9]", "");
        String company = String.valueOf(lead.getOrDefault("company_name", "Client"));
        String machine = String.valueOf(lead.getOrDefault("machine_id", ""));
        String status = String.valueOf(lead.getOrDefault("status", "PENDING"));

        String text;
        if ("APPROVED".equalsIgnoreCase(status)) {
            text = "Dear " + company + ",\n\nYour Jasvi ERP activation request for Terminal ID: " +
                    machine + " has been APPROVED!\nPlease restart your Jasvi ERP or click 'Check Status' to auto-activate.\n\nThank you for choosing Jasvi ERP!";
        } else {
            text = "Dear " + company + ",\n\nWe have received your Jasvi ERP activation request for Terminal ID: " +
                    machine + ".\nOur team is reviewing your registration.\n\nThank you for choosing Jasvi ERP!";
        }

        try {
            String url = "https://wa.me/" + (phone.isBlank() ? "" : (phone.startsWith("91") ? phone : "91" + phone)) +
                    "?text=" + URLEncoder.encode(text, StandardCharsets.UTF_8);
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception ex) {
            AppDialogService.error(tableLeads, "WhatsApp Error", "Could not open browser", ex.getMessage());
        }
    }

    // ==========================================
    // TAB 3: SUBSCRIPTION PLANS & COMMERCIAL SETTINGS
    // ==========================================

    private void setupPlanTableColumns() {
        if (tablePlans == null) return;
        DynamicTableLayoutManager.install(tablePlans);
        tablePlans.setItems(plansList);

        colPlanCode.setCellValueFactory(cell -> new SimpleStringProperty(String.valueOf(cell.getValue().getOrDefault("tier_code", "-"))));
        colPlanName.setCellValueFactory(cell -> new SimpleStringProperty(String.valueOf(cell.getValue().getOrDefault("plan_name", "-"))));

        colPlanFee.setCellValueFactory(cell -> {
            Object fee = cell.getValue().get("annual_fee");
            if (fee instanceof Number n) {
                return new SimpleStringProperty("₹" + CURRENCY_FMT.format(n.doubleValue()));
            }
            return new SimpleStringProperty("₹0.00");
        });

        colPlanUsers.setCellValueFactory(cell -> new SimpleStringProperty(String.valueOf(cell.getValue().getOrDefault("max_users", "5")) + " Users"));
        colPlanDays.setCellValueFactory(cell -> new SimpleStringProperty(String.valueOf(cell.getValue().getOrDefault("validity_days", "365")) + " Days"));
        colPlanFeatures.setCellValueFactory(cell -> {
            String raw = String.valueOf(cell.getValue().getOrDefault("features", ""));
            var allMods = org.example.shared.PermissionCatalog.subscriptionModules();
            java.util.Set<String> expanded = org.example.shared.PermissionCatalog.expandFeatureTokens(List.of(raw));
            long activeCount = allMods.stream().filter(m -> expanded.contains(m.key())).count();
            return new SimpleStringProperty(activeCount + " / " + allMods.size() + " Modules Active");
        });

        colPlanStatus.setCellValueFactory(cell -> {
            Object act = cell.getValue().get("is_active");
            boolean isActive = act == null || Boolean.TRUE.equals(act) || "1".equals(String.valueOf(act));
            return new SimpleStringProperty(isActive ? "Active" : "Inactive");
        });
        colPlanStatus.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String status, boolean empty) {
                super.updateItem(status, empty);
                if (empty || status == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                Label badge = new Label("● " + status);
                badge.getStyleClass().addAll("status-badge", "Active".equalsIgnoreCase(status) ? "status-positive" : "status-negative");
                setGraphic(badge);
                setAlignment(Pos.CENTER);
            }
        });

        colPlanActions.setCellFactory(col -> new TableCell<>() {
            private final MenuButton actionsBtn = new MenuButton("Actions");
            private final MenuItem miView = new MenuItem("View Plan & Modules");
            private final MenuItem miEdit = new MenuItem("Edit Plan & Modules");
            private final MenuItem miDuplicate = new MenuItem("Duplicate / Clone Plan");
            private final MenuItem miAdd = new MenuItem("Add New Plan");
            private final MenuItem miToggleStatus = new MenuItem("Activate / Deactivate Plan");
            private final MenuItem miDelete = new MenuItem("Delete Plan");

            {
                actionsBtn.getStyleClass().addAll("table-action-menu", "approved-row-action");
                actionsBtn.setGraphic(IconFactory.compactIcon("actions", 14));
                actionsBtn.setContentDisplay(ContentDisplay.LEFT);
                actionsBtn.setGraphicTextGap(6);

                miView.setGraphic(IconFactory.compactIcon("view", 13));
                miView.setOnAction(e -> {
                    Map<String, Object> plan = getTableView().getItems().get(getIndex());
                    if (plan != null) showPlanEditDialog(plan, true);
                });

                miEdit.setGraphic(IconFactory.compactIcon("edit", 13));
                miEdit.setOnAction(e -> {
                    Map<String, Object> plan = getTableView().getItems().get(getIndex());
                    if (plan != null) handleEditPlan(plan);
                });

                miDuplicate.setGraphic(IconFactory.compactIcon("copy", 13));
                miDuplicate.setOnAction(e -> {
                    Map<String, Object> plan = getTableView().getItems().get(getIndex());
                    if (plan != null) handleDuplicatePlan(plan);
                });

                miAdd.setGraphic(IconFactory.compactIcon("add", 13));
                miAdd.setOnAction(e -> handleAddPlan());

                miToggleStatus.setGraphic(IconFactory.compactIcon("refresh", 13));
                miToggleStatus.setOnAction(e -> {
                    Map<String, Object> plan = getTableView().getItems().get(getIndex());
                    if (plan != null) handleTogglePlanActive(plan);
                });

                miDelete.setGraphic(IconFactory.compactIcon("delete", 13));
                miDelete.setOnAction(e -> {
                    Map<String, Object> plan = getTableView().getItems().get(getIndex());
                    if (plan != null) handleDeletePlan(plan);
                });

                actionsBtn.getItems().addAll(
                        miView,
                        miEdit,
                        miDuplicate,
                        miAdd,
                        new SeparatorMenuItem(),
                        miToggleStatus,
                        miDelete
                );
            }
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : actionsBtn);
                setAlignment(Pos.CENTER);
            }
        });
    }

    @FXML
    private void handleAddPlan() {
        showPlanEditDialog(null, false);
    }

    private void handleEditPlan(Map<String, Object> plan) {
        showPlanEditDialog(plan, false);
    }

    private void handleDuplicatePlan(Map<String, Object> sourcePlan) {
        if (sourcePlan == null) return;
        Map<String, Object> copy = new java.util.LinkedHashMap<>(sourcePlan);
        copy.remove("id");
        copy.put("tier_code", String.valueOf(sourcePlan.getOrDefault("tier_code", "CUSTOM")) + "_PLUS");
        copy.put("plan_name", String.valueOf(sourcePlan.getOrDefault("plan_name", "Custom Plan")) + " (Copy)");
        showPlanEditDialog(copy, false);
    }

    private void handleTogglePlanActive(Map<String, Object> plan) {
        if (plan == null) return;
        Object act = plan.get("is_active");
        boolean currentlyActive = act == null || Boolean.TRUE.equals(act) || "1".equals(String.valueOf(act));
        boolean nextActive = !currentlyActive;
        Map<String, Object> req = new java.util.HashMap<>();
        if (plan.get("id") != null) req.put("id", plan.get("id"));
        req.put("tierCode", String.valueOf(plan.getOrDefault("tier_code", "")).trim().toUpperCase(Locale.ROOT));
        req.put("planName", String.valueOf(plan.getOrDefault("plan_name", "")).trim());
        req.put("annualFee", plan.getOrDefault("annual_fee", new BigDecimal("3500.00")));
        req.put("maxUsers", plan.getOrDefault("max_users", 5));
        req.put("validityDays", plan.getOrDefault("validity_days", 365));
        req.put("features", String.valueOf(plan.getOrDefault("features", "")));
        req.put("isActive", nextActive);

        UiTaskExecutor.submitActionWithOverlay(
                tablePlans,
                (nextActive ? "Activating " : "Deactivating ") + req.get("tierCode") + " Plan...",
                "toggle-plan-status",
                () -> api.savePlan(req),
                res -> {
                    org.example.service.PermissionService.refresh();
                    AppDialogService.success(tablePlans, "Plan Status Updated",
                            "Plan '" + req.get("tierCode") + "' is now " + (nextActive ? "Active" : "Inactive") + ".");
                    loadDataAsync();
                },
                err -> AppDialogService.error(tablePlans, "Status Error", "Could not update plan status", err.getMessage())
        );
    }

    private void handleDeletePlan(Map<String, Object> plan) {
        if (plan == null || plan.get("id") == null) return;
        Long id = ((Number) plan.get("id")).longValue();
        String tierCode = String.valueOf(plan.getOrDefault("tier_code", "PLAN"));
        String planName = String.valueOf(plan.getOrDefault("plan_name", tierCode));
        boolean confirmed = AppDialogService.destructive(
                tablePlans,
                "Delete Subscription Plan",
                "Delete plan '" + planName + "' (" + tierCode + ")?",
                "This removes the subscription tier from the commercial catalog. Active client licenses cannot be deleted.",
                "Delete Plan"
        );
        if (!confirmed) return;

        UiTaskExecutor.submitActionWithOverlay(
                tablePlans,
                "Deleting Subscription Plan " + tierCode + "...",
                "delete-plan-" + id,
                () -> api.deletePlan(id),
                res -> {
                    org.example.service.PermissionService.refresh();
                    AppDialogService.success(tablePlans, "Plan Deleted", "Deleted subscription plan '" + planName + "' (" + tierCode + ").");
                    loadDataAsync();
                },
                err -> AppDialogService.error(tablePlans, "Delete Blocked", "Could not delete subscription plan", err.getMessage())
        );
    }

    private void showPlanEditDialog(Map<String, Object> plan) {
        showPlanEditDialog(plan, false);
    }

    private void showPlanEditDialog(Map<String, Object> plan, boolean readOnly) {
        boolean isEdit = plan != null && plan.get("id") != null;
        boolean hasSeed = plan != null;
        Dialog<Map<String, Object>> dialog = new OwnedDialog<>(tablePlans);
        dialog.setTitle(readOnly
                ? "View Subscription Plan & Modules (Super Admin)"
                : (isEdit ? "Edit Subscription Plan (Super Admin)" : "Create New Subscription Plan (Super Admin)"));
        dialog.setHeaderText(readOnly
                ? "Inspect commercial pricing, user quota, and included ERP modules for " + plan.getOrDefault("plan_name", "")
                : (isEdit
                        ? "Configure commercial pricing, user quota, and turn ERP modules ON or OFF for " + plan.get("plan_name")
                        : "Create a dynamic subscription tier and turn ERP modules ON or OFF"));

        ButtonType btnSave = new ButtonType("Save Plan", ButtonBar.ButtonData.OK_DONE);
        if (readOnly) {
            dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        } else {
            dialog.getDialogPane().getButtonTypes().addAll(btnSave, ButtonType.CANCEL);
        }

        VBox rootBox = new VBox(8);
        rootBox.setPadding(new Insets(4, 6, 4, 6));
        rootBox.setMinHeight(Region.USE_PREF_SIZE);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);
        grid.setMinHeight(Region.USE_PREF_SIZE);

        TextField txtCode = new TextField(hasSeed ? String.valueOf(plan.getOrDefault("tier_code", "")) : "");
        txtCode.setPromptText("e.g. GOLD, PLATINUM, DIAMOND");
        txtCode.setPrefWidth(220);
        TextField txtName = new TextField(hasSeed ? String.valueOf(plan.getOrDefault("plan_name", "")) : "");
        txtName.setPromptText("e.g. Gold Standard");
        txtName.setPrefWidth(220);
        TextField txtFee = new TextField(hasSeed && plan.get("annual_fee") != null ? String.valueOf(plan.get("annual_fee")) : "3500.00");
        TextField txtUsers = new TextField(hasSeed && plan.get("max_users") != null ? String.valueOf(plan.get("max_users")) : "5");
        TextField txtDays = new TextField(hasSeed && plan.get("validity_days") != null ? String.valueOf(plan.get("validity_days")) : "365");

        Object actObj = hasSeed ? plan.get("is_active") : Boolean.TRUE;
        boolean initialActive = actObj == null || Boolean.TRUE.equals(actObj) || "1".equals(String.valueOf(actObj));
        CheckBox chkActivePlan = new CheckBox("Active Plan");
        chkActivePlan.getStyleClass().add("ios-toggle-switch");
        chkActivePlan.setSelected(initialActive);
        chkActivePlan.setMinWidth(Region.USE_PREF_SIZE);

        Label lblCode = new Label("Tier Code *:");
        lblCode.setMinWidth(Region.USE_PREF_SIZE);
        Label lblName = new Label("Plan Name *:");
        lblName.setMinWidth(Region.USE_PREF_SIZE);
        Label lblStatus = new Label("Plan Status:");
        lblStatus.setMinWidth(Region.USE_PREF_SIZE);
        Label lblFee = new Label("Annual Price (₹) *:");
        lblFee.setMinWidth(Region.USE_PREF_SIZE);
        Label lblUsers = new Label("Max Concurrent Users *:");
        lblUsers.setMinWidth(Region.USE_PREF_SIZE);
        Label lblDays = new Label("Validity (Days) *:");
        lblDays.setMinWidth(Region.USE_PREF_SIZE);

        grid.add(lblCode, 0, 0);
        grid.add(txtCode, 1, 0);
        grid.add(lblName, 2, 0);
        grid.add(txtName, 3, 0);
        grid.add(lblStatus, 4, 0);
        grid.add(chkActivePlan, 5, 0);

        grid.add(lblFee, 0, 1);
        grid.add(txtFee, 1, 1);
        grid.add(lblUsers, 2, 1);
        grid.add(txtUsers, 3, 1);
        grid.add(lblDays, 4, 1);
        grid.add(txtDays, 5, 1);

        GridPane.setHgrow(txtCode, Priority.ALWAYS);
        GridPane.setHgrow(txtName, Priority.ALWAYS);
        GridPane.setHgrow(txtFee, Priority.ALWAYS);
        GridPane.setHgrow(txtUsers, Priority.ALWAYS);
        GridPane.setHgrow(txtDays, Priority.ALWAYS);

        java.util.Map<String, CheckBox> moduleToggles = new java.util.LinkedHashMap<>();
        List<String> initialModules = hasSeed
                ? resolvePlanFeatures(String.valueOf(plan.getOrDefault("tier_code", "GOLD")), plan)
                : org.example.service.PermissionService.defaultAllFeatures();

        VBox moduleSection = buildIosModuleTogglePanel(moduleToggles, initialModules);
        if (readOnly) {
            txtCode.setEditable(false);
            txtName.setEditable(false);
            txtFee.setEditable(false);
            txtUsers.setEditable(false);
            txtDays.setEditable(false);
            chkActivePlan.setDisable(true);
            moduleSection.setDisable(true);
        }
        rootBox.getChildren().addAll(grid, new Separator(), moduleSection);

        dialog.getDialogPane().setContent(rootBox);
        dialog.getDialogPane().getStyleClass().add("dse-dialog-auto-height");
        PopupTableWorkspace.prepareDialog(dialog, 1240);
        dialog.setResultConverter(b -> {
            if (b == btnSave) {
                java.util.Map<String, Object> req = new java.util.HashMap<>();
                if (isEdit && plan.get("id") != null) req.put("id", plan.get("id"));
                req.put("tierCode", txtCode.getText().trim().toUpperCase(Locale.ROOT));
                req.put("planName", txtName.getText().trim());
                try { req.put("annualFee", new BigDecimal(txtFee.getText().trim())); } catch (Exception e) { req.put("annualFee", BigDecimal.ZERO); }
                try { req.put("maxUsers", Integer.parseInt(txtUsers.getText().trim())); } catch (Exception e) { req.put("maxUsers", 5); }
                try { req.put("validityDays", Integer.parseInt(txtDays.getText().trim())); } catch (Exception e) { req.put("validityDays", 365); }
                List<String> selected = new java.util.ArrayList<>();
                moduleToggles.forEach((key, toggle) -> {
                    if (toggle.isSelected()) selected.add(key);
                });
                req.put("features", String.join(",", selected));
                req.put("isActive", chkActivePlan.isSelected());
                return req;
            }
            return null;
        });

        dialog.showAndWait().ifPresent(req -> {
            if (String.valueOf(req.getOrDefault("tierCode", "")).isBlank()) {
                AppDialogService.warning(tablePlans, "Validation", "Tier Code is required (e.g. GOLD, PLATINUM, CUSTOM).");
                return;
            }
            UiTaskExecutor.submitActionWithOverlay(
                    tablePlans,
                    "Saving Commercial Subscription Plan...",
                    "save-plan",
                    () -> api.savePlan(req),
                    res -> {
                        org.example.service.PermissionService.refresh();
                        AppDialogService.success(tablePlans, "Plan Saved", "Subscription plan and module access updated!");
                        loadDataAsync();
                    },
                    err -> AppDialogService.error(tablePlans, "Save Error", "Could not save plan", err.getMessage())
            );
        });
    }

    @FXML
    private void handleOpenPlanPaidDetails() {
        Dialog<Map<String, Object>> dialog = new OwnedDialog<>(tablePlans);
        dialog.setTitle("Plan Paid Details & Commercial Configuration");
        dialog.setHeaderText("Configure UPI payment VPA, support WhatsApp hotline, and automated message templates");
        dialog.setResizable(true);

        DialogPane pane = dialog.getDialogPane();
        pane.setMinWidth(840);
        pane.setPrefWidth(880);

        ButtonType btnSave = new ButtonType("Save Commercial Details", ButtonBar.ButtonData.OK_DONE);
        pane.getButtonTypes().addAll(btnSave, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(12);
        grid.setPadding(new Insets(18, 24, 18, 24));

        ColumnConstraints colLabel = new ColumnConstraints();
        colLabel.setMinWidth(260);
        colLabel.setPrefWidth(280);

        ColumnConstraints colInput = new ColumnConstraints();
        colInput.setHgrow(Priority.ALWAYS);
        colInput.setMinWidth(480);
        colInput.setFillWidth(true);

        grid.getColumnConstraints().addAll(colLabel, colInput);

        Map<String, Object> comm = currentCommercialSettings != null ? currentCommercialSettings : Map.of();

        TextField phoneField = new TextField(String.valueOf(comm.getOrDefault("supportPhone", "9537544008")));
        phoneField.setPromptText("e.g. 9537544008");
        phoneField.getStyleClass().add("approved-input");

        TextField vpaField = new TextField(String.valueOf(comm.getOrDefault("upiVpa", "jasviindustries@icici")));
        vpaField.setPromptText("e.g. jasviindustries@icici");
        vpaField.getStyleClass().add("approved-input");

        TextField payeeField = new TextField(String.valueOf(comm.getOrDefault("payeeName", "Jasvi Industries")));
        payeeField.setPromptText("e.g. Jasvi Industries");
        payeeField.getStyleClass().add("approved-input");

        TextField gstField = new TextField(String.valueOf(comm.getOrDefault("gstRate", "18.00")));
        gstField.setPromptText("e.g. 18.00");
        gstField.getStyleClass().add("approved-input");

        TextArea leadTplArea = new TextArea(String.valueOf(comm.getOrDefault("whatsappTemplateLead", "")));
        leadTplArea.setPromptText("Template for new inbound client lead notification...");
        leadTplArea.setPrefRowCount(3);
        leadTplArea.setMinHeight(84);
        leadTplArea.setWrapText(true);
        leadTplArea.getStyleClass().add("approved-input");

        TextArea issuedTplArea = new TextArea(String.valueOf(comm.getOrDefault("whatsappTemplateIssued", "")));
        issuedTplArea.setPromptText("Template for license issuance message sent to client...");
        issuedTplArea.setPrefRowCount(3);
        issuedTplArea.setMinHeight(84);
        issuedTplArea.setWrapText(true);
        issuedTplArea.getStyleClass().add("approved-input");

        Label lblPhone = new Label("Primary Support WhatsApp *:");
        lblPhone.getStyleClass().add("field-label");

        Label lblVpa = new Label("Merchant UPI VPA (ID) *:");
        lblVpa.getStyleClass().add("field-label");

        Label lblPayee = new Label("Payee Business Name:");
        lblPayee.getStyleClass().add("field-label");

        Label lblGst = new Label("GST Taxation Rate (%):");
        lblGst.getStyleClass().add("field-label");

        Label lblLeadTpl = new Label("WhatsApp Lead Response Template:");
        lblLeadTpl.getStyleClass().add("field-label");

        Label lblIssuedTpl = new Label("WhatsApp License Issued Template:");
        lblIssuedTpl.getStyleClass().add("field-label");

        grid.add(lblPhone, 0, 0);
        grid.add(phoneField, 1, 0);

        grid.add(lblVpa, 0, 1);
        grid.add(vpaField, 1, 1);

        grid.add(lblPayee, 0, 2);
        grid.add(payeeField, 1, 2);

        grid.add(lblGst, 0, 3);
        grid.add(gstField, 1, 3);

        grid.add(lblLeadTpl, 0, 4);
        grid.add(leadTplArea, 1, 4);

        grid.add(lblIssuedTpl, 0, 5);
        grid.add(issuedTplArea, 1, 5);

        pane.setContent(grid);

        dialog.setResultConverter(clicked -> {
            if (clicked == btnSave) {
                return Map.of(
                        "supportPhone", phoneField.getText() != null ? phoneField.getText().trim() : "",
                        "upiVpa", vpaField.getText() != null ? vpaField.getText().trim() : "",
                        "payeeName", payeeField.getText() != null ? payeeField.getText().trim() : "",
                        "gstRate", gstField.getText() != null ? gstField.getText().trim() : "18.00",
                        "whatsappTemplateLead", leadTplArea.getText() != null ? leadTplArea.getText().trim() : "",
                        "whatsappTemplateIssued", issuedTplArea.getText() != null ? issuedTplArea.getText().trim() : ""
                );
            }
            return null;
        });

        dialog.showAndWait().ifPresent(req -> {
            if (String.valueOf(req.get("supportPhone")).isBlank()) {
                AppDialogService.warning(tablePlans, "Validation", "Primary Support WhatsApp hotline is required.");
                return;
            }
            if (String.valueOf(req.get("upiVpa")).isBlank()) {
                AppDialogService.warning(tablePlans, "Validation", "Merchant UPI VPA is required.");
                return;
            }

            UiTaskExecutor.submitActionWithOverlay(
                    registryTabPane != null ? registryTabPane : tablePlans,
                    "Saving Settings & Updating Cloud Catalog...",
                    "save-comm-settings",
                    () -> api.saveCommercialSettings(req),
                    res -> {
                        currentCommercialSettings = new java.util.HashMap<>(req);
                        populateCommercialSettings(req);
                        AppDialogService.success(tablePlans, "Settings Saved", "Commercial settings updated and synced with cloud catalog!");
                    },
                    err -> AppDialogService.error(tablePlans, "Save Error", "Could not save settings", err.getMessage())
            );
        });
    }

    @FXML
    private void handleSaveCommercialSettings() {
        Map<String, Object> req = Map.of(
                "supportPhone", txtCommPhone.getText() != null ? txtCommPhone.getText().trim() : "",
                "upiVpa", txtCommVpa.getText() != null ? txtCommVpa.getText().trim() : "",
                "payeeName", txtCommPayee.getText() != null ? txtCommPayee.getText().trim() : "",
                "gstRate", txtCommGst.getText() != null ? txtCommGst.getText().trim() : "18.00",
                "whatsappTemplateLead", txtCommLeadTpl.getText() != null ? txtCommLeadTpl.getText().trim() : "",
                "whatsappTemplateIssued", txtCommIssuedTpl.getText() != null ? txtCommIssuedTpl.getText().trim() : ""
        );

        UiTaskExecutor.submitActionWithOverlay(
                registryTabPane,
                "Saving Settings & Updating Cloud Catalog...",
                "save-comm-settings",
                () -> api.saveCommercialSettings(req),
                res -> AppDialogService.success(registryTabPane, "Settings Saved", "Commercial settings updated and synced with cloud catalog!"),
                err -> AppDialogService.error(registryTabPane, "Save Error", "Could not save settings", err.getMessage())
        );
    }

    // ==========================================
    // DATA LOADING & KPIS
    // ==========================================

    private void loadDataAsync() {
        if (UiTaskExecutor.actionInProgress("load-client-registry")) {
            return;
        }
        UiTaskExecutor.submitActionWithOverlay(
                tableClients,
                "Loading Enterprise Client Registry...",
                "load-client-registry",
                () -> {
                    var list = api.getClients(null, null);
                    var stats = api.getStats();
                    var leads = api.getLeads();
                    var plans = api.getPlans();
                    var comm = api.getCommercialSettings();
                    return Map.of("list", list, "stats", stats, "leads", leads, "plans", plans, "comm", comm);
                },
                res -> {
                    @SuppressWarnings("unchecked")
                    var list = (List<Map<String, Object>>) res.get("list");
                    @SuppressWarnings("unchecked")
                    var stats = (Map<String, Object>) res.get("stats");
                    @SuppressWarnings("unchecked")
                    var leads = (List<Map<String, Object>>) res.get("leads");
                    @SuppressWarnings("unchecked")
                    var plans = (List<Map<String, Object>>) res.get("plans");
                    @SuppressWarnings("unchecked")
                    var comm = (Map<String, Object>) res.get("comm");

                    if (list != null) masterList.setAll(list);
                    if (leads != null) leadsMasterList.setAll(leads);
                    if (plans != null) plansList.setAll(plans);

                    updateKpis(stats, leads);
                    populateCommercialSettings(comm);
                },
                err -> AppDialogService.error(tableClients != null ? tableClients : registryTabPane, "Registry Load Error", "Could not load client registry", err.getMessage())
        );
    }

    private void updateKpis(Map<String, Object> stats, List<Map<String, Object>> leads) {
        if (lblKpiTotal != null) lblKpiTotal.setText(String.valueOf(stats.getOrDefault("totalClients", masterList.size())));
        if (lblKpiActive != null) lblKpiActive.setText(String.valueOf(stats.getOrDefault("activeClients", 0)));

        long pendingLeads = leads != null ? leads.stream().filter(l -> "PENDING".equalsIgnoreCase(String.valueOf(l.get("status")))).count() : 0;
        if (lblKpiPendingLeads != null) lblKpiPendingLeads.setText(String.valueOf(pendingLeads));
        if (lblKpiPendingTrend != null) lblKpiPendingTrend.setText(pendingLeads > 0 ? pendingLeads + " Pending" : "Up to date");

        if (tabLeads != null) {
            tabLeads.setText(pendingLeads > 0 ? "Approval Requests (" + pendingLeads + ")" : "Approval Requests");
        }

        if (lblKpiArr != null) {
            Object arr = stats.get("arr");
            if (arr instanceof Number n) {
                lblKpiArr.setText("₹" + CURRENCY_FMT.format(n.doubleValue()));
            } else {
                lblKpiArr.setText("₹0.00");
            }
        }
    }

    private void populateCommercialSettings(Map<String, Object> comm) {
        if (comm == null) return;
        this.currentCommercialSettings = new java.util.HashMap<>(comm);
        if (txtCommPhone != null) txtCommPhone.setText(String.valueOf(comm.getOrDefault("supportPhone", "9537544008")));
        if (txtCommVpa != null) txtCommVpa.setText(String.valueOf(comm.getOrDefault("upiVpa", "jasviindustries@icici")));
        if (txtCommPayee != null) txtCommPayee.setText(String.valueOf(comm.getOrDefault("payeeName", "Jasvi Industries")));
        if (txtCommGst != null) txtCommGst.setText(String.valueOf(comm.getOrDefault("gstRate", "18.00")));
        if (txtCommLeadTpl != null) txtCommLeadTpl.setText(String.valueOf(comm.getOrDefault("whatsappTemplateLead", "")));
        if (txtCommIssuedTpl != null) txtCommIssuedTpl.setText(String.valueOf(comm.getOrDefault("whatsappTemplateIssued", "")));
    }

    // ==========================================
    // ACTION HANDLERS
    // ==========================================

    @FXML
    private void handleRefresh() {
        loadDataAsync();
    }

    @FXML
    private void handleResetFilter() {
        if (txtSearch != null) txtSearch.clear();
        if (cmbFilterStatus != null) cmbFilterStatus.setValue("All Statuses");
    }

    @FXML
    private void handleResetLeadFilter() {
        if (txtLeadSearch != null) txtLeadSearch.clear();
        if (cmbLeadFilterStatus != null) cmbLeadFilterStatus.setValue("All Statuses");
    }

    @FXML
    private void handleRegisterClient() {
        showClientLicenseDialog(null);
    }

    private void showClientLicenseDialog(Map<String, Object> existingClient) {
        boolean isEdit = existingClient != null;
        Dialog<Map<String, Object>> dialog = new OwnedDialog<>(tableClients);
        dialog.setTitle(isEdit ? "Edit Client Plan & Module Access" : "Register New Client Company");
        dialog.setHeaderText(isEdit
                ? "Modify subscription tier and turn ERP modules ON or OFF for " + existingClient.getOrDefault("company_name", "Client")
                : "Issue cryptographically signed license bound to client computer");

        ButtonType btnSave = new ButtonType(isEdit ? "Update & Re-Sign License" : "Issue License", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(btnSave, ButtonType.CANCEL);

        VBox rootBox = new VBox(8);
        rootBox.setPadding(new Insets(4, 6, 4, 6));
        rootBox.setMinHeight(Region.USE_PREF_SIZE);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);
        grid.setMinHeight(Region.USE_PREF_SIZE);

        TextField txtComp = new TextField(isEdit ? String.valueOf(existingClient.getOrDefault("company_name", "")) : "");
        txtComp.setPromptText("e.g. Surat Silk Mills Pvt Ltd");

        TextField txtMachine = new TextField(isEdit ? String.valueOf(existingClient.getOrDefault("machine_id", "")) : "");
        txtMachine.setPromptText("e.g. 16-hex Machine ID or click Use Current");

        Button btnUseCurrentMachine = new Button("Use Current Machine ID");
        btnUseCurrentMachine.getStyleClass().addAll("approved-button", "approved-secondary-button");
        btnUseCurrentMachine.setMinWidth(Region.USE_PREF_SIZE);
        btnUseCurrentMachine.setOnAction(e -> txtMachine.setText(HardwareFingerprintUtil.getDisplayMachineId()));

        HBox machineRow = new HBox(8, txtMachine, btnUseCurrentMachine);
        HBox.setHgrow(txtMachine, Priority.ALWAYS);

        TextField txtContact = new TextField(isEdit && existingClient.get("contact_person") != null ? String.valueOf(existingClient.get("contact_person")) : "");
        txtContact.setPromptText("e.g. Ramesh Patel");

        TextField txtPhone = new TextField(isEdit && existingClient.get("phone") != null ? String.valueOf(existingClient.get("phone")) : "");
        txtPhone.setPromptText("e.g. 9825012345");

        TextField txtEmail = new TextField(isEdit && existingClient.get("email") != null ? String.valueOf(existingClient.get("email")) : "");
        txtEmail.setPromptText("e.g. accounts@suratsilk.com");

        ComboBox<String> cmbTier = new ComboBox<>(activePlanTierCodes());
        cmbTier.setMaxWidth(Double.MAX_VALUE);
        String currentTier = isEdit && existingClient.get("tier") != null
                ? String.valueOf(existingClient.get("tier")).trim().toUpperCase(Locale.ROOT)
                : (cmbTier.getItems().contains("GOLD") ? "GOLD" : (cmbTier.getItems().isEmpty() ? "GOLD" : cmbTier.getItems().getFirst()));
        if (!cmbTier.getItems().contains(currentTier)) {
            cmbTier.getItems().add(currentTier);
        }
        cmbTier.setValue(currentTier);

        TextField txtUsers = new TextField(isEdit && existingClient.get("max_users") != null ? String.valueOf(existingClient.get("max_users")) : "5");
        TextField txtFee = new TextField(isEdit && existingClient.get("annual_fee") != null ? String.valueOf(existingClient.get("annual_fee")) : "3500.00");
        TextField txtDays = new TextField("365");

        TextField txtRemoteUrl = new TextField(isEdit && existingClient.get("remote_url") != null ? String.valueOf(existingClient.get("remote_url")) : "");
        txtRemoteUrl.setPromptText("e.g. https://client-a.jasviindustries.in or https://...trycloudflare.com");

        java.util.Map<String, CheckBox> moduleToggles = new java.util.LinkedHashMap<>();
        List<String> initialModules;
        if (isEdit && existingClient.get("features") != null && !String.valueOf(existingClient.get("features")).isBlank()) {
            initialModules = List.copyOf(org.example.shared.PermissionCatalog.expandFeatureTokens(
                    List.of(String.valueOf(existingClient.get("features")))));
        } else {
            initialModules = resolvePlanFeatures(currentTier, findPlanByTierCode(currentTier));
        }

        VBox moduleSection = buildIosModuleTogglePanel(moduleToggles, initialModules);

        java.util.function.Consumer<String> applyTierDefaults = tier -> {
            Map<String, Object> matchedPlan = findPlanByTierCode(tier);
            if (matchedPlan != null) {
                if (matchedPlan.get("max_users") != null) txtUsers.setText(String.valueOf(matchedPlan.get("max_users")));
                if (matchedPlan.get("annual_fee") != null) txtFee.setText(String.valueOf(matchedPlan.get("annual_fee")));
                if (matchedPlan.get("validity_days") != null) txtDays.setText(String.valueOf(matchedPlan.get("validity_days")));
            }
            java.util.Set<String> defaults = new java.util.HashSet<>(resolvePlanFeatures(tier, matchedPlan));
            moduleToggles.forEach((tag, chk) -> chk.setSelected(defaults.contains(tag)));
        };

        if (!isEdit) {
            applyTierDefaults.accept(currentTier);
        }

        cmbTier.valueProperty().addListener((obs, oldV, newV) -> {
            if (newV != null) applyTierDefaults.accept(newV);
        });

        Label lblComp = new Label("Company Name *:");
        lblComp.setMinWidth(Region.USE_PREF_SIZE);
        Label lblMachine = new Label("Machine Hardware ID *:");
        lblMachine.setMinWidth(Region.USE_PREF_SIZE);
        Label lblContact = new Label("Contact Person:");
        lblContact.setMinWidth(Region.USE_PREF_SIZE);
        Label lblPhone = new Label("Phone / WhatsApp:");
        lblPhone.setMinWidth(Region.USE_PREF_SIZE);
        Label lblEmail = new Label("Email:");
        lblEmail.setMinWidth(Region.USE_PREF_SIZE);
        Label lblTier = new Label("Subscription Plan *:");
        lblTier.setMinWidth(Region.USE_PREF_SIZE);
        Label lblUsers = new Label("Max Concurrent Users *:");
        lblUsers.setMinWidth(Region.USE_PREF_SIZE);
        Label lblFee = new Label("Annual Fee (₹) *:");
        lblFee.setMinWidth(Region.USE_PREF_SIZE);
        Label lblDays = new Label("Validity (Days) *:");
        lblDays.setMinWidth(Region.USE_PREF_SIZE);
        Label lblRemote = new Label("Remote Support URL:");
        lblRemote.setMinWidth(Region.USE_PREF_SIZE);

        grid.add(lblComp, 0, 0);
        grid.add(txtComp, 1, 0);
        grid.add(lblMachine, 2, 0);
        grid.add(machineRow, 3, 0, 3, 1);

        grid.add(lblContact, 0, 1);
        grid.add(txtContact, 1, 1);
        grid.add(lblPhone, 2, 1);
        grid.add(txtPhone, 3, 1);
        grid.add(lblEmail, 4, 1);
        grid.add(txtEmail, 5, 1);

        grid.add(lblTier, 0, 2);
        grid.add(cmbTier, 1, 2);
        grid.add(lblUsers, 2, 2);
        grid.add(txtUsers, 3, 2);
        grid.add(lblFee, 4, 2);
        grid.add(txtFee, 5, 2);

        grid.add(lblDays, 0, 3);
        grid.add(txtDays, 1, 3);
        grid.add(lblRemote, 2, 3);
        grid.add(txtRemoteUrl, 3, 3, 3, 1);

        GridPane.setHgrow(txtComp, Priority.ALWAYS);
        GridPane.setHgrow(machineRow, Priority.ALWAYS);
        GridPane.setHgrow(txtContact, Priority.ALWAYS);
        GridPane.setHgrow(txtPhone, Priority.ALWAYS);
        GridPane.setHgrow(txtEmail, Priority.ALWAYS);
        GridPane.setHgrow(cmbTier, Priority.ALWAYS);
        GridPane.setHgrow(txtUsers, Priority.ALWAYS);
        GridPane.setHgrow(txtFee, Priority.ALWAYS);
        GridPane.setHgrow(txtDays, Priority.ALWAYS);
        GridPane.setHgrow(txtRemoteUrl, Priority.ALWAYS);

        rootBox.getChildren().addAll(grid, new Separator(), moduleSection);
        dialog.getDialogPane().setContent(rootBox);
        dialog.getDialogPane().getStyleClass().add("dse-dialog-auto-height");
        PopupTableWorkspace.prepareDialog(dialog, 1240);
        dialog.setResultConverter(btn -> {
            if (btn == btnSave) {
                java.util.Map<String, Object> reqMap = new java.util.HashMap<>();
                reqMap.put("companyName", txtComp.getText().trim());
                reqMap.put("machineId", txtMachine.getText().trim().replace("-", ""));
                reqMap.put("contactPerson", txtContact.getText().trim());
                reqMap.put("phone", txtPhone.getText().trim());
                reqMap.put("email", txtEmail.getText().trim());
                reqMap.put("tier", cmbTier.getValue());
                try { reqMap.put("maxUsers", Integer.parseInt(txtUsers.getText().trim())); } catch (Exception e) { reqMap.put("maxUsers", 5); }
                try { reqMap.put("annualFee", new BigDecimal(txtFee.getText().trim())); } catch (Exception e) { reqMap.put("annualFee", BigDecimal.ZERO); }
                try { reqMap.put("days", Integer.parseInt(txtDays.getText().trim())); } catch (Exception e) { reqMap.put("days", 365); }
                reqMap.put("remoteUrl", txtRemoteUrl.getText().trim());
                reqMap.put("notes", isEdit ? "Updated via SuperAdmin Plan & Access Editor" : "Direct SuperAdmin issuance");

                List<String> selectedFeatures = new java.util.ArrayList<>();
                moduleToggles.forEach((tag, chk) -> {
                    if (chk.isSelected()) selectedFeatures.add(tag);
                });
                reqMap.put("features", selectedFeatures);
                return reqMap;
            }
            return null;
        });

        dialog.showAndWait().ifPresent(req -> {
            if (String.valueOf(req.get("companyName")).isBlank() || String.valueOf(req.get("machineId")).isBlank()) {
                AppDialogService.warning(tableClients, "Validation", "Company Name and Machine ID are required.");
                return;
            }

            UiTaskExecutor.submitActionWithOverlay(
                    tableClients,
                    isEdit ? "Updating Client Plan & Re-Signing License..." : "Issuing License & Registering Client...",
                    "register-client",
                    () -> api.registerClient(req),
                    client -> {
                        org.example.service.PermissionService.refresh();
                        AppDialogService.success(tableClients, isEdit ? "License Updated" : "License Issued",
                                (isEdit ? "Updated plan and module access for " : "Registered ") + req.get("companyName") + "!");
                        loadDataAsync();
                    },
                    err -> AppDialogService.error(tableClients, "Issuance Failed", "Could not save client license", err.getMessage())
            );
        });
    }

    private VBox buildIosModuleTogglePanel(java.util.Map<String, CheckBox> moduleToggles, java.util.Collection<String> initialSelected) {
        var allModules = org.example.shared.PermissionCatalog.subscriptionModules();
        java.util.Set<String> activeInitial = org.example.shared.PermissionCatalog.expandFeatureTokens(initialSelected);

        Label lblSummary = new Label();
        lblSummary.getStyleClass().add("section-title");

        Label lblAll = new Label("Turn All Modules ON / OFF:");
        CheckBox masterToggle = new CheckBox();
        masterToggle.getStyleClass().add("ios-toggle-switch");

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox headerRow = new HBox(10, IconFactory.compactIcon("permission", 16), lblSummary, headerSpacer, lblAll, masterToggle);
        headerRow.setAlignment(Pos.CENTER_LEFT);

        boolean[] syncing = {false};
        Runnable updateCounts = () -> {
            if (syncing[0]) return;
            syncing[0] = true;
            try {
                long onCount = moduleToggles.values().stream().filter(CheckBox::isSelected).count();
                lblSummary.setText("Included ERP Modules & Capabilities (" + onCount + " / " + allModules.size() + " Active)");
                masterToggle.setSelected(onCount == allModules.size() && !allModules.isEmpty());
            } finally {
                syncing[0] = false;
            }
        };

        masterToggle.setOnAction(e -> {
            if (syncing[0]) return;
            boolean target = masterToggle.isSelected();
            syncing[0] = true;
            try {
                moduleToggles.values().forEach(chk -> chk.setSelected(target));
            } finally {
                syncing[0] = false;
            }
            updateCounts.run();
        });

        java.util.Map<String, List<org.example.shared.PermissionCatalog.ModuleMeta>> grouped = new java.util.LinkedHashMap<>();
        grouped.put("Sales, Billing & CRM", new java.util.ArrayList<>());
        grouped.put("Purchase, Inventory & Operations", new java.util.ArrayList<>());
        grouped.put("Finance, GST Compliance & Enterprise Add-Ons", new java.util.ArrayList<>());

        for (var meta : allModules) {
            String cat = meta.category() == null ? "" : meta.category().toLowerCase(Locale.ROOT);
            if (cat.contains("sale") || cat.contains("dashboard")) {
                grouped.get("Sales, Billing & CRM").add(meta);
            } else if (cat.contains("purchase") || cat.contains("inventory") || cat.contains("master")) {
                grouped.get("Purchase, Inventory & Operations").add(meta);
            } else {
                grouped.get("Finance, GST Compliance & Enterprise Add-Ons").add(meta);
            }
        }

        VBox categoriesBox = new VBox(6);
        categoriesBox.setPadding(new Insets(2, 0, 2, 0));
        categoriesBox.setMinHeight(Region.USE_PREF_SIZE);

        final int columns = 4;
        for (var entry : grouped.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            Label groupTitle = new Label(entry.getKey());
            groupTitle.getStyleClass().add("module-category-header");
            groupTitle.setMinHeight(Region.USE_PREF_SIZE);

            GridPane groupGrid = new GridPane();
            groupGrid.setHgap(10);
            groupGrid.setVgap(5);
            groupGrid.setMinHeight(Region.USE_PREF_SIZE);

            int idx = 0;
            for (var meta : entry.getValue()) {
                CheckBox toggle = new CheckBox();
                toggle.getStyleClass().add("ios-toggle-switch");
                toggle.setSelected(activeInitial.contains(meta.key()));
                toggle.setMinWidth(Region.USE_PREF_SIZE);
                toggle.selectedProperty().addListener((obs, o, n) -> updateCounts.run());
                moduleToggles.put(meta.key(), toggle);

                Label nameLbl = new Label(meta.label());
                nameLbl.setMinWidth(Region.USE_PREF_SIZE);
                Region sp = new Region();
                HBox.setHgrow(sp, Priority.ALWAYS);

                HBox cellBox = new HBox(6, IconFactory.compactIcon(meta.iconKey(), 13), nameLbl, sp, toggle);
                cellBox.setAlignment(Pos.CENTER_LEFT);
                cellBox.setMinHeight(Region.USE_PREF_SIZE);
                cellBox.getStyleClass().add("module-toggle-pill");
                GridPane.setHgrow(cellBox, Priority.ALWAYS);

                groupGrid.add(cellBox, idx % columns, idx / columns);
                idx++;
            }

            for (int col = 0; col < columns; col++) {
                ColumnConstraints cc = new ColumnConstraints();
                cc.setPercentWidth(100.0 / columns);
                cc.setHgrow(Priority.ALWAYS);
                groupGrid.getColumnConstraints().add(cc);
            }

            categoriesBox.getChildren().addAll(groupTitle, groupGrid);
        }

        updateCounts.run();

        headerRow.setMinHeight(Region.USE_PREF_SIZE);
        VBox wrapper = new VBox(6, headerRow, categoriesBox);
        wrapper.setMinHeight(Region.USE_PREF_SIZE);
        return wrapper;
    }

    private void handleRenewClient(Map<String, Object> item, int days) {
        Long id = ((Number) item.get("id")).longValue();
        String company = String.valueOf(item.get("company_name"));

        UiTaskExecutor.submitActionWithOverlay(
                tableClients,
                "Renewing Client License & Generating Signature...",
                "renew-client-" + id,
                () -> api.renewClient(id, days),
                res -> {
                    AppDialogService.success(tableClients, "License Renewed", "Added " + days + " days to " + company + ".");
                    loadDataAsync();
                },
                err -> AppDialogService.error(tableClients, "Renewal Error", "Could not renew license", err.getMessage())
        );
    }

    private void promptCustomRenewal(Map<String, Object> item) {
        Long id = ((Number) item.get("id")).longValue();
        String company = String.valueOf(item.get("company_name"));

        TextInputDialog d = new OwnedTextInputDialog("365");
        d.setTitle("Custom Renewal Period");
        d.setHeaderText("Extend License for " + company);
        d.setContentText("Enter number of extension days (e.g. 30, 90, 180, 730):");
        d.showAndWait().ifPresent(val -> {
            try {
                int days = Integer.parseInt(val.trim());
                if (days <= 0) throw new IllegalArgumentException("Days must be > 0");
                handleRenewClient(item, days);
            } catch (Exception ex) {
                AppDialogService.error(tableClients, "Invalid Input", "Invalid number of days", ex.getMessage());
            }
        });
    }

    private void copyLicenseToken(Map<String, Object> item) {
        String payload = String.valueOf(item.getOrDefault("license_payload_enc", ""));
        if (!payload.isBlank()) {
            ClipboardContent content = new ClipboardContent();
            content.putString(payload);
            Clipboard.getSystemClipboard().setContent(content);
            AppDialogService.info(tableClients, "Copied", "Cryptographic license token copied to clipboard!");
        } else {
            AppDialogService.warning(tableClients, "Unavailable", "No license token generated for this client.");
        }
    }

    private void exportLicenseFile(Map<String, Object> item) {
        Long id = ((Number) item.get("id")).longValue();
        String company = String.valueOf(item.getOrDefault("company_name", "client")).replaceAll("[^a-zA-Z0-9_-]", "_");

        File target = ExportFeedbackManager.resolveWorkspaceExportFile("Licenses", company, "jlic");
        if (target != null) {
            if (!ExportFeedbackManager.checkFileWriteable(tableClients, target)) return;
            UiTaskExecutor.submitActionWithOverlay(
                    tableClients,
                    "Exporting Dual License Package (.jlic & .json)...",
                    "download-license",
                    () -> api.downloadLicenseFile(id),
                    bytes -> {
                        try {
                            Files.write(target.toPath(), bytes);
                            // Also save companion readable JSON certificate in the same directory
                            File jsonTarget = new File(target.getParentFile(), company + "-license.json");
                            Files.write(jsonTarget.toPath(), bytes);
                            ExportFeedbackManager.notifyExportSuccess(tableClients, target, "Jasvi License Package (.jlic & .json for " + company + ")");
                        } catch (Exception e) {
                            AppDialogService.error(tableClients, "Save Error", "Could not save file", e.getMessage());
                        }
                    },
                    err -> AppDialogService.error(tableClients, "Export Error", "Could not download license file", err.getMessage())
            );
        }
    }

    private void sendEmail(Map<String, Object> item) {
        Long id = item.get("id") instanceof Number n ? n.longValue() : null;
        String email = String.valueOf(item.getOrDefault("email", "")).trim();
        String company = String.valueOf(item.getOrDefault("company_name", "Client"));
        if (id == null) {
            AppDialogService.warning(tableClients, "Missing Client ID", "Cannot dispatch email without valid client ID.", "");
            return;
        }
        if (email.isBlank() || "null".equalsIgnoreCase(email)) {
            AppDialogService.warning(tableClients, "No Email Address", "No registered email found for " + company, "Please edit the client profile or provide an email address.");
            return;
        }

        UiTaskExecutor.submitActionWithOverlay(
                tableClients,
                "Connecting to SMTP & Dispatching Dual License Package...",
                "dispatch-email-" + id,
                () -> api.dispatchEmail(id),
                res -> {
                    boolean emSent = Boolean.TRUE.equals(res.get("emailSent"));
                    String emErr = String.valueOf(res.getOrDefault("emailError", ""));
                    if (emSent) {
                        AppDialogService.success(tableClients, "License Email Dispatched",
                                "Official license files (.jlic & .json) dispatched to " + email);
                    } else {
                        AppDialogService.error(tableClients, "Email Delivery Notice",
                                "Failed to send license email to " + email,
                                (emErr.isBlank() ? "SMTP server unavailable" : emErr));
                    }
                },
                err -> AppDialogService.error(tableClients, "Email Error", "Could not dispatch email", err.getMessage())
        );
    }

    private void sendWhatsApp(Map<String, Object> item) {
        Long id = item.get("id") instanceof Number n ? n.longValue() : null;
        if (id == null) {
            openManualWhatsApp(item);
            return;
        }

        UiTaskExecutor.submitActionWithOverlay(
                tableClients,
                "Connecting to WhatsApp Gateway & Transmitting License...",
                "dispatch-whatsapp-" + id,
                () -> api.dispatchWhatsApp(id),
                res -> {
                    boolean waSent = Boolean.TRUE.equals(res.get("whatsappSent"));
                    String waErr = String.valueOf(res.getOrDefault("whatsappError", ""));

                    if (waSent) {
                        AppDialogService.success(tableClients, "WhatsApp Dispatched",
                                "Official license files (.jlic & .json) and activation message dispatched via WhatsApp to " + item.getOrDefault("company_name", "client"));
                    } else {
                        AppDialogService.warning(tableClients, "WhatsApp Channel Notice",
                                (waErr.isBlank() ? "WAHA channel unavailable" : waErr) + ". Opening WhatsApp Web in browser...");
                        openManualWhatsApp(item);
                    }
                },
                err -> openManualWhatsApp(item)
        );
    }

    private void dispatchAll(Map<String, Object> item) {
        Long id = item.get("id") instanceof Number n ? n.longValue() : null;
        if (id == null) return;
        UiTaskExecutor.submitActionWithOverlay(
                tableClients,
                "Dispatching Multi-Channel License (Email + WhatsApp)...",
                "dispatch-all-" + id,
                () -> api.dispatchChannels(id),
                res -> {
                    boolean waSent = Boolean.TRUE.equals(res.get("whatsappSent"));
                    boolean emSent = Boolean.TRUE.equals(res.get("emailSent"));
                    String summary = (waSent ? "✓ WhatsApp Sent (.jlic & .json)" : "✗ WhatsApp Failed") + " | " +
                            (emSent ? "✓ Email Sent (.jlic & .json attached)" : "✗ Email Failed");
                    if (waSent || emSent) {
                        AppDialogService.success(tableClients, "Multi-Channel Dispatch", summary);
                    } else {
                        AppDialogService.warning(tableClients, "Dispatch Notice", summary, "Neither channel could complete delivery. Please check WAHA and SMTP configurations.");
                    }
                },
                err -> AppDialogService.error(tableClients, "Dispatch Error", "Could not complete multi-channel dispatch", err.getMessage())
        );
    }

    private void openManualWhatsApp(Map<String, Object> item) {
        String phone = String.valueOf(item.getOrDefault("phone", "")).replaceAll("[^0-9]", "");
        String company = String.valueOf(item.getOrDefault("company_name", "Valued Client"));
        String payload = String.valueOf(item.getOrDefault("license_payload_enc", item.getOrDefault("licensePayload", "")));

        String text = "Dear " + company + ",\n\nYour Jasvi ERP License is active.\nMachine ID: " +
                item.getOrDefault("machine_id", item.getOrDefault("machineHash", "")) +
                "\nValid Until: " + item.getOrDefault("valid_until", item.getOrDefault("validUntil", "")) +
                "\n\nLicense Payload:\n" + payload + "\n\nThank you for choosing Jasvi ERP!";

        try {
            String url = "https://wa.me/" + (phone.isBlank() ? "" : (phone.startsWith("91") ? phone : "91" + phone)) +
                    "?text=" + URLEncoder.encode(text, StandardCharsets.UTF_8);
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception ex) {
            AppDialogService.error(tableClients, "WhatsApp Error", "Could not open browser", ex.getMessage());
        }
    }

    private void testRemoteUrl(Map<String, Object> item) {
        String url = String.valueOf(item.getOrDefault("remote_url", ""));
        if (url == null || url.isBlank() || "null".equalsIgnoreCase(url)) {
            AppDialogService.warning(tableClients, "No Remote URL",
                    "This client has not registered or connected via Cloudflare Tunnel yet.",
                    "Remote URL will appear automatically when the client starts Remote Access.");
            return;
        }
        try {
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception ex) {
            AppDialogService.error(tableClients, "Browser Error", "Could not open URL", ex.getMessage());
        }
    }

    private void handleRevokeClient(Map<String, Object> item) {
        Long id = ((Number) item.get("id")).longValue();
        String company = String.valueOf(item.get("company_name"));

        boolean confirmed = AppDialogService.doubleConfirmation(
                tableClients,
                "REVOKE",
                "Revoke Client License",
                "Revoke enterprise license for " + company + "?",
                "All workstations registered under " + company + " will be immediately deactivated and denied system access."
        );
        if (!confirmed) return;

        UiTaskExecutor.submitActionWithOverlay(
                tableClients,
                "Revoking Client License & Updating Registry...",
                "revoke-client-" + id,
                () -> api.revokeClient(id),
                res -> {
                    AppDialogService.info(tableClients, "Revoked", "Client " + company + " revoked.");
                    loadDataAsync();
                },
                err -> AppDialogService.error(tableClients, "Revoke Error", "Could not revoke client", err.getMessage())
        );
    }

    private ObservableList<String> activePlanTierCodes() {
        List<String> codes = new java.util.ArrayList<>();
        for (Map<String, Object> plan : plansList) {
            if (plan == null) continue;
            Object activeObj = plan.get("is_active");
            boolean active = activeObj == null || "1".equals(String.valueOf(activeObj)) || Boolean.TRUE.equals(activeObj);
            if (!active) continue;
            String code = String.valueOf(plan.getOrDefault("tier_code", "")).trim().toUpperCase(Locale.ROOT);
            if (!code.isEmpty() && !"NULL".equals(code) && !codes.contains(code)) {
                codes.add(code);
            }
        }
        if (codes.isEmpty()) {
            codes.addAll(List.of("SILVER", "GOLD", "PLATINUM"));
        }
        return FXCollections.observableArrayList(codes);
    }

    private Map<String, Object> findPlanByTierCode(String tierCode) {
        if (tierCode == null || tierCode.isBlank()) return null;
        String target = tierCode.trim().toUpperCase(Locale.ROOT);
        for (Map<String, Object> plan : plansList) {
            if (plan == null) continue;
            String code = String.valueOf(plan.getOrDefault("tier_code", "")).trim().toUpperCase(Locale.ROOT);
            if (target.equals(code)) return plan;
        }
        return null;
    }

    private List<String> resolvePlanFeatures(String tier, Map<String, Object> plan) {
        if (plan != null && plan.get("features") != null) {
            String raw = String.valueOf(plan.get("features")).trim();
            if (!raw.isEmpty() && !"null".equalsIgnoreCase(raw)) {
                java.util.Set<String> expanded = org.example.shared.PermissionCatalog.expandFeatureTokens(List.of(raw));
                if (!expanded.isEmpty()) return List.copyOf(expanded);
            }
        }
        return org.example.service.PermissionService.fallbackFeaturesForTier(tier);
    }

    @Override
    public void onScreenShown(boolean reusedFromCache) {
        loadDataAsync();
    }
}