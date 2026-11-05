package org.example.controller;

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
import org.example.util.AppDialogService;
import org.example.util.BusinessClock;
import org.example.util.IconFactory;
import org.example.util.OwnedAlert;
import org.example.util.OwnedDialog;
import org.example.util.OwnedTextInputDialog;
import org.example.util.ToastManager;
import org.example.util.UiTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.DecimalFormat;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

public class ClientRegistryController implements ScreenLifecycle {
    private static final Logger log = LoggerFactory.getLogger(ClientRegistryController.class);
    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DecimalFormat CURRENCY_FMT = new DecimalFormat("#,##0.00");

    @FXML private StackPane pageIcon;
    @FXML private Button btnRegisterClient;
    @FXML private Button btnRefresh;
    @FXML private Button btnResetFilter;

    @FXML private StackPane kpiTotalIcon;
    @FXML private StackPane kpiActiveIcon;
    @FXML private StackPane kpiExpiringIcon;
    @FXML private StackPane kpiArrIcon;
    @FXML private Label lblKpiTotal;
    @FXML private Label lblKpiActive;
    @FXML private Label lblKpiExpiring;
    @FXML private Label lblKpiArr;

    @FXML private TextField txtSearch;
    @FXML private ComboBox<String> cmbFilterStatus;

    @FXML private TableView<Map<String, Object>> tableClients;
    @FXML private TableColumn<Map<String, Object>, String> colStatus;
    @FXML private TableColumn<Map<String, Object>, String> colCompany;
    @FXML private TableColumn<Map<String, Object>, String> colMachineId;
    @FXML private TableColumn<Map<String, Object>, String> colContact;
    @FXML private TableColumn<Map<String, Object>, String> colTier;
    @FXML private TableColumn<Map<String, Object>, String> colAnnualFee;
    @FXML private TableColumn<Map<String, Object>, String> colValidUntil;
    @FXML private TableColumn<Map<String, Object>, Void> colActions;

    private final ClientRegistryApiClient api = new ClientRegistryApiClient();
    private final ObservableList<Map<String, Object>> masterList = FXCollections.observableArrayList();
    private FilteredList<Map<String, Object>> filteredList;

    @FXML
    public void initialize() {
        if (pageIcon != null) pageIcon.getChildren().setAll(IconFactory.icon("shield", 22));
        if (kpiTotalIcon != null) kpiTotalIcon.getChildren().setAll(IconFactory.icon("customer", 18));
        if (kpiActiveIcon != null) kpiActiveIcon.getChildren().setAll(IconFactory.icon("active", 18));
        if (kpiExpiringIcon != null) kpiExpiringIcon.getChildren().setAll(IconFactory.icon("clock", 18));
        if (kpiArrIcon != null) kpiArrIcon.getChildren().setAll(IconFactory.icon("dollar", 18));

        if (btnRegisterClient != null) btnRegisterClient.setGraphic(IconFactory.compactIcon("add", 14));
        if (btnRefresh != null) btnRefresh.setGraphic(IconFactory.compactIcon("refresh", 14));
        if (btnResetFilter != null) btnResetFilter.setGraphic(IconFactory.compactIcon("reset", 14));

        if (cmbFilterStatus != null) {
            cmbFilterStatus.setItems(FXCollections.observableArrayList("All Statuses", "ACTIVE", "EXPIRING", "EXPIRED", "REVOKED"));
            cmbFilterStatus.setValue("All Statuses");
            cmbFilterStatus.valueProperty().addListener((obs, o, n) -> applyFilters());
        }

        setupTableColumns();
        setupFiltering();
    }

    private void setupTableColumns() {
        if (tableClients == null) return;

        // Dynamic responsive column widths per Rule 7 (sum = 1.00)
        colStatus.prefWidthProperty().bind(tableClients.widthProperty().multiply(0.08));
        colCompany.prefWidthProperty().bind(tableClients.widthProperty().multiply(0.20));
        colMachineId.prefWidthProperty().bind(tableClients.widthProperty().multiply(0.20));
        colContact.prefWidthProperty().bind(tableClients.widthProperty().multiply(0.14));
        colTier.prefWidthProperty().bind(tableClients.widthProperty().multiply(0.10));
        colAnnualFee.prefWidthProperty().bind(tableClients.widthProperty().multiply(0.09));
        colValidUntil.prefWidthProperty().bind(tableClients.widthProperty().multiply(0.09));
        colActions.prefWidthProperty().bind(tableClients.widthProperty().multiply(0.10));

        // 1. Status Column with Semantic Badges (Rule 11)
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
                    badge.getStyleClass().add("badge-active");
                } else if ("REVOKED".equals(upper)) {
                    badge.getStyleClass().add("badge-cancelled");
                } else {
                    badge.getStyleClass().add("badge-warning");
                }
                setGraphic(badge);
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                setAlignment(Pos.CENTER);
            }
        });

        // 2. Company Name
        colCompany.setCellValueFactory(cell -> new SimpleStringProperty(String.valueOf(cell.getValue().getOrDefault("company_name", "-"))));

        // 3. Machine Hardware ID formatted
        colMachineId.setCellValueFactory(cell -> {
            String m = String.valueOf(cell.getValue().getOrDefault("machine_id", ""));
            if (m.length() == 16) {
                m = String.format("%s-%s-%s-%s", m.substring(0, 4), m.substring(4, 8), m.substring(8, 12), m.substring(12, 16));
            }
            return new SimpleStringProperty(m);
        });

        // 4. Contact & Phone
        colContact.setCellValueFactory(cell -> {
            String person = String.valueOf(cell.getValue().getOrDefault("contact_person", ""));
            String phone = String.valueOf(cell.getValue().getOrDefault("phone", ""));
            String combined = person.isBlank() ? phone : (phone.isBlank() ? person : person + " (" + phone + ")");
            return new SimpleStringProperty(combined.isBlank() ? "-" : combined);
        });

        // 5. Tier & Users
        colTier.setCellValueFactory(cell -> {
            String tier = String.valueOf(cell.getValue().getOrDefault("tier", "GOLD"));
            Object maxUsers = cell.getValue().get("max_users");
            return new SimpleStringProperty(tier + " (" + (maxUsers != null ? maxUsers : 5) + " Users)");
        });

        // 6. Annual Fee
        colAnnualFee.setCellValueFactory(cell -> {
            Object fee = cell.getValue().get("annual_fee");
            if (fee instanceof Number n) {
                return new SimpleStringProperty("₹" + CURRENCY_FMT.format(n.doubleValue()));
            }
            return new SimpleStringProperty("₹3,500.00");
        });

        // 7. Valid Until (Rule 13: settings-driven date formatting)
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

        // 8. Colorized Actions (Rule 8 & 9)
        colActions.setCellFactory(col -> new TableCell<>() {
            private final MenuButton actionsBtn = new MenuButton("Actions");

            {
                actionsBtn.getStyleClass().addAll("table-action-menu", "approved-row-action");
                actionsBtn.setGraphic(IconFactory.compactIcon("actions", 14));
                actionsBtn.setContentDisplay(ContentDisplay.LEFT);
                actionsBtn.setGraphicTextGap(6);

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

                MenuItem miWhatsApp = new MenuItem("Send via WhatsApp");
                miWhatsApp.setGraphic(IconFactory.compactIcon("share", 13));
                miWhatsApp.setOnAction(e -> {
                    Map<String, Object> item = getTableView().getItems().get(getIndex());
                    if (item != null) sendWhatsApp(item);
                });

                MenuItem miRevoke = new MenuItem("Revoke License");
                miRevoke.setGraphic(IconFactory.compactIcon("delete", 13));
                miRevoke.setOnAction(e -> {
                    Map<String, Object> item = getTableView().getItems().get(getIndex());
                    if (item != null) handleRevokeClient(item);
                });

                actionsBtn.getItems().addAll(miRenew, miRenewCustom, miCopyToken, miExport, miWhatsApp, new SeparatorMenuItem(), miRevoke);
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

    private void loadDataAsync() {
        if (UiTaskExecutor.actionInProgress("load-client-registry")) {
            return;
        }
        UiTaskExecutor.submitAction(
                "load-client-registry",
                () -> {
                    var list = api.getClients(null, null);
                    var stats = api.getStats();
                    return Map.of("list", list, "stats", stats);
                },
                res -> {
                    @SuppressWarnings("unchecked")
                    var list = (java.util.List<Map<String, Object>>) res.get("list");
                    @SuppressWarnings("unchecked")
                    var stats = (Map<String, Object>) res.get("stats");

                    masterList.setAll(list);
                    updateKpis(stats);
                },
                err -> AppDialogService.error(tableClients, "Registry Load Error", "Could not load client registry", err.getMessage())
        );
    }

    private void updateKpis(Map<String, Object> stats) {
        if (lblKpiTotal != null) lblKpiTotal.setText(String.valueOf(stats.getOrDefault("totalClients", masterList.size())));
        if (lblKpiActive != null) lblKpiActive.setText(String.valueOf(stats.getOrDefault("activeClients", 0)));
        if (lblKpiExpiring != null) lblKpiExpiring.setText(String.valueOf(stats.getOrDefault("expiringClients", 0)));
        if (lblKpiArr != null) {
            Object arrObj = stats.get("arr");
            if (arrObj instanceof Number n) {
                lblKpiArr.setText("₹" + CURRENCY_FMT.format(n.doubleValue()));
            } else {
                lblKpiArr.setText("₹0.00");
            }
        }
    }

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
    private void handleRegisterClient() {
        Dialog<Map<String, Object>> dialog = new OwnedDialog<>(tableClients);
        dialog.setTitle("Register New Client Company");
        dialog.setHeaderText("Issue cryptographically signed license bound to client computer");

        ButtonType btnSave = new ButtonType("Issue License", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(btnSave, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 24, 20, 24));

        TextField txtComp = new TextField();
        txtComp.setPromptText("e.g. Surat Silk Mills Pvt Ltd");

        TextField txtMach = new TextField();
        txtMach.setPromptText("e.g. F12D-C2C0-AADE-15EB");

        Button btnMyId = new Button("Use This PC");
        btnMyId.setOnAction(e -> txtMach.setText(HardwareFingerprintUtil.getDisplayMachineId()));
        HBox machBox = new HBox(6, txtMach, btnMyId);
        HBox.setHgrow(txtMach, Priority.ALWAYS);

        TextField txtContact = new TextField();
        TextField txtPhone = new TextField();
        TextField txtEmail = new TextField();
        ComboBox<String> cmbTier = new ComboBox<>(FXCollections.observableArrayList("GOLD", "SILVER", "PLATINUM"));
        cmbTier.setValue("GOLD");

        TextField txtUsers = new TextField("5");
        TextField txtFee = new TextField("3500.00");
        ComboBox<String> cmbDays = new ComboBox<>(FXCollections.observableArrayList("365 Days (1 Year)", "730 Days (2 Years)", "30 Days (Trial)"));
        cmbDays.setValue("365 Days (1 Year)");

        grid.add(new Label("Company Name *:"), 0, 0);
        grid.add(txtComp, 1, 0);
        grid.add(new Label("Machine Hardware ID *:"), 0, 1);
        grid.add(machBox, 1, 1);
        grid.add(new Label("Contact Person:"), 0, 2);
        grid.add(txtContact, 1, 2);
        grid.add(new Label("Phone (WhatsApp):"), 0, 3);
        grid.add(txtPhone, 1, 3);
        grid.add(new Label("Email:"), 0, 4);
        grid.add(txtEmail, 1, 4);
        grid.add(new Label("Plan Tier:"), 0, 5);
        grid.add(cmbTier, 1, 5);
        grid.add(new Label("Max Concurrent Users:"), 0, 6);
        grid.add(txtUsers, 1, 6);
        grid.add(new Label("Annual Fee (₹):"), 0, 7);
        grid.add(txtFee, 1, 7);
        grid.add(new Label("Validity Duration:"), 0, 8);
        grid.add(cmbDays, 1, 8);

        dialog.getDialogPane().setContent(grid);
        dialog.setResultConverter(btn -> {
            if (btn == btnSave) {
                int days = cmbDays.getValue().contains("730") ? 730 : (cmbDays.getValue().contains("30") ? 30 : 365);
                int u = 5;
                try { u = Integer.parseInt(txtUsers.getText().trim()); } catch (Exception ignored) {}
                double f = 3500.00;
                try { f = Double.parseDouble(txtFee.getText().trim()); } catch (Exception ignored) {}

                return Map.of(
                        "companyName", txtComp.getText().trim(),
                        "machineId", txtMach.getText().trim(),
                        "contactPerson", txtContact.getText().trim(),
                        "phone", txtPhone.getText().trim(),
                        "email", txtEmail.getText().trim(),
                        "tier", cmbTier.getValue(),
                        "maxUsers", u,
                        "annualFee", f,
                        "days", days
                );
            }
            return null;
        });

        dialog.showAndWait().ifPresent(req -> {
            if (String.valueOf(req.get("companyName")).isBlank()) {
                ToastManager.warning(tableClients, "Validation", "Company Name is required.");
                return;
            }
            UiTaskExecutor.submitAction(
                    "register-client",
                    () -> api.registerClient(req),
                    saved -> {
                        ToastManager.success(tableClients, "Client Registered", "License issued successfully for " + req.get("companyName"));
                        loadDataAsync();
                    },
                    err -> AppDialogService.error(tableClients, "Registration Failed", "Could not register client", err.getMessage())
            );
        });
    }

    private void handleRenewClient(Map<String, Object> item, int days) {
        Long id = ((Number) item.get("id")).longValue();
        String company = String.valueOf(item.get("company_name"));

        UiTaskExecutor.submitAction(
                "renew-client",
                () -> api.renewClient(id, days),
                res -> {
                    ToastManager.success(tableClients, "Renewal Successful", "License renewed +" + days + " days for " + company);
                    loadDataAsync();
                },
                err -> AppDialogService.error(tableClients, "Renewal Failed", "Could not renew license", err.getMessage())
        );
    }

    private void promptCustomRenewal(Map<String, Object> item) {
        TextInputDialog dialog = new OwnedTextInputDialog("365");
        dialog.setTitle("Custom Renewal Duration");
        dialog.setHeaderText("Enter number of days to extend validity for " + item.get("company_name"));
        dialog.setContentText("Additional Days:");

        dialog.showAndWait().ifPresent(str -> {
            try {
                int days = Integer.parseInt(str.trim());
                handleRenewClient(item, days);
            } catch (Exception ex) {
                ToastManager.warning(tableClients, "Invalid Input", "Please enter a valid positive integer.");
            }
        });
    }

    private void copyLicenseToken(Map<String, Object> item) {
        String enc = String.valueOf(item.get("license_payload_enc"));
        if (enc != null && !enc.isBlank()) {
            ClipboardContent cb = new ClipboardContent();
            cb.putString(enc);
            Clipboard.getSystemClipboard().setContent(cb);
            ToastManager.info(tableClients, "Token Copied", "License JSON token copied to clipboard!");
        }
    }

    private void exportLicenseFile(Map<String, Object> item) {
        Long id = ((Number) item.get("id")).longValue();
        String company = String.valueOf(item.getOrDefault("company_name", "client")).replaceAll("[^a-zA-Z0-9_-]", "_");

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save Jasvi License File");
        chooser.setInitialFileName(company + "-license.jlic");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Jasvi Signed License (*.jlic)", "*.jlic"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("All Files (*.*)", "*.*"));

        File target = chooser.showSaveDialog(tableClients.getScene().getWindow());
        if (target != null) {
            UiTaskExecutor.submitAction(
                    "download-license",
                    () -> api.downloadLicenseFile(id),
                    bytes -> {
                        try {
                            Files.write(target.toPath(), bytes);
                            ToastManager.success(tableClients, "Exported", "Saved to " + target.getName());
                        } catch (Exception e) {
                            AppDialogService.error(tableClients, "Save Error", "Could not save file", e.getMessage());
                        }
                    },
                    err -> AppDialogService.error(tableClients, "Export Error", "Could not download license file", err.getMessage())
            );
        }
    }

    private void sendWhatsApp(Map<String, Object> item) {
        String phone = String.valueOf(item.getOrDefault("phone", "")).replaceAll("[^0-9]", "");
        String company = String.valueOf(item.getOrDefault("company_name", "Valued Client"));
        String payload = String.valueOf(item.getOrDefault("license_payload_enc", ""));

        String text = "Dear " + company + ",\n\nYour Jasvi ERP License is active.\nMachine ID: " +
                item.get("machine_id") + "\nValid Until: " + item.get("valid_until") +
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

    private void handleRevokeClient(Map<String, Object> item) {
        Long id = ((Number) item.get("id")).longValue();
        String company = String.valueOf(item.get("company_name"));

        Alert confirm = new OwnedAlert(Alert.AlertType.CONFIRMATION, "Are you sure you want to REVOKE the license for " + company + "?", ButtonType.YES, ButtonType.NO);
        confirm.setHeaderText("Confirm Revocation");
        confirm.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.YES) {
                UiTaskExecutor.submitAction(
                        "revoke-client",
                        () -> api.revokeClient(id),
                        res -> {
                            ToastManager.info(tableClients, "Revoked", "Client " + company + " revoked.");
                            loadDataAsync();
                        },
                        err -> AppDialogService.error(tableClients, "Revoke Error", "Could not revoke client", err.getMessage())
                );
            }
        });
    }

    @Override
    public void onScreenShown(boolean reusedFromCache) {
        loadDataAsync();
    }
}
