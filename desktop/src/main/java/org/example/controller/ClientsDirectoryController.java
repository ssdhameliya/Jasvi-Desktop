package org.example.controller;

import javafx.application.Platform;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.example.api.tally.TallyAutomationApiClient;
import org.example.navigation.NavigationManager;
import org.example.navigation.ScreenLifecycle;
import org.example.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

public class ClientsDirectoryController implements ScreenLifecycle {
    private static final Logger log = LoggerFactory.getLogger(ClientsDirectoryController.class);

    @FXML private StackPane pageIcon;
    @FXML private Button btnAddClient;
    @FXML private Button btnRefresh;
    @FXML private Button btnResetFilter;

    @FXML private VBox cardKpiTotal;
    @FXML private VBox cardKpiActive;
    @FXML private VBox cardKpiInactive;
    @FXML private VBox cardKpiTemplates;
    @FXML private StackPane kpiTotalIcon;
    @FXML private StackPane kpiActiveIcon;
    @FXML private StackPane kpiInactiveIcon;
    @FXML private StackPane kpiTemplatesIcon;
    @FXML private Label lblKpiTotal;
    @FXML private Label lblKpiActive;
    @FXML private Label lblKpiInactive;
    @FXML private Label lblKpiTemplates;

    @FXML private TextField txtSearch;
    @FXML private ComboBox<String> cmbFilterStatus;

    @FXML private TableView<TallyAutomationApiClient.ClientDirectoryRowDto> tableClients;
    @FXML private TableColumn<TallyAutomationApiClient.ClientDirectoryRowDto, String> colStatus;
    @FXML private TableColumn<TallyAutomationApiClient.ClientDirectoryRowDto, String> colGstin;
    @FXML private TableColumn<TallyAutomationApiClient.ClientDirectoryRowDto, String> colTradeName;
    @FXML private TableColumn<TallyAutomationApiClient.ClientDirectoryRowDto, String> colLegalName;
    @FXML private TableColumn<TallyAutomationApiClient.ClientDirectoryRowDto, String> colState;
    @FXML private TableColumn<TallyAutomationApiClient.ClientDirectoryRowDto, Number> colTemplates;
    @FXML private TableColumn<TallyAutomationApiClient.ClientDirectoryRowDto, Void> colActions;

    private final TallyAutomationApiClient api = new TallyAutomationApiClient();
    private final ObservableList<TallyAutomationApiClient.ClientDirectoryRowDto> masterList = FXCollections.observableArrayList();
    private final java.util.concurrent.atomic.AtomicBoolean isRefreshing = new java.util.concurrent.atomic.AtomicBoolean(false);
    private FilteredList<TallyAutomationApiClient.ClientDirectoryRowDto> filteredList;

    @FXML
    public void initialize() {
        if (pageIcon != null) pageIcon.getChildren().setAll(IconFactory.icon("customer", 22));
        if (kpiTotalIcon != null) kpiTotalIcon.getChildren().setAll(IconFactory.icon("customer", 18));
        if (kpiActiveIcon != null) kpiActiveIcon.getChildren().setAll(IconFactory.icon("active", 18));
        if (kpiInactiveIcon != null) kpiInactiveIcon.getChildren().setAll(IconFactory.icon("inactive", 18));
        if (kpiTemplatesIcon != null) kpiTemplatesIcon.getChildren().setAll(IconFactory.icon("document", 18));

        if (btnAddClient != null) btnAddClient.setGraphic(IconFactory.compactIcon("add", 14));
        if (btnRefresh != null) btnRefresh.setGraphic(IconFactory.compactIcon("refresh", 14));
        if (btnResetFilter != null) btnResetFilter.setGraphic(IconFactory.compactIcon("reset", 14));

        if (cmbFilterStatus != null) {
            cmbFilterStatus.setItems(FXCollections.observableArrayList("All Statuses", "ACTIVE", "CANCELLED", "SUSPENDED"));
            cmbFilterStatus.setValue("All Statuses");
            cmbFilterStatus.valueProperty().addListener((obs, o, n) -> applyFilters());
        }

        setupTableColumns();
        setupFiltering();
    }

    private void setupTableColumns() {
        if (tableClients == null) return;

        DynamicTableLayoutManager.install(tableClients);

        colStatus.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().gstStatus()));
        colStatus.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String status, boolean empty) {
                super.updateItem(status, empty);
                if (empty || status == null) {
                    setGraphic(null);
                    setText(null);
                } else {
                    Label badge = new Label(status);
                    badge.getStyleClass().addAll("status-badge");
                    if ("ACTIVE".equalsIgnoreCase(status)) {
                        badge.getStyleClass().addAll("status-positive", "status-active");
                    } else {
                        badge.getStyleClass().addAll("status-neutral", "status-inactive");
                    }
                    setGraphic(badge);
                    setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                }
            }
        });

        colGstin.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().gstin()));
        colGstin.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String val, boolean empty) {
                super.updateItem(val, empty);
                setText(empty || val == null ? null : val);
                if (!empty && val != null) getStyleClass().add("gstin-cell");
            }
        });

        colTradeName.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().tradeName()));
        colLegalName.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().legalName()));
        colState.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().stateCode()));

        colTemplates.setCellValueFactory(cell -> new SimpleLongProperty(cell.getValue().templateCount()));
        colTemplates.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Number count, boolean empty) {
                super.updateItem(count, empty);
                if (empty || count == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    Label badge = new Label(count.intValue() + " Ver");
                    badge.getStyleClass().addAll("template-count-badge", count.intValue() > 0 ? "erp-badge-purple" : "erp-badge-slate");
                    setGraphic(badge);
                    setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                }
            }
        });

        colActions.setCellFactory(col -> new TableCell<>() {
            private final MenuButton menu = new MenuButton("Actions");
            private final MenuItem itemTemplates = new MenuItem("Manage Templates", IconFactory.compactIcon("manage templates", 14));
            private final MenuItem itemGstDetails = new MenuItem("View Full GST Details", IconFactory.compactIcon("view full gst", 14));
            private final MenuItem itemDelete = new MenuItem("Delete Client", IconFactory.compactIcon("delete", 14));

            {
                menu.getStyleClass().setAll("table-action-menu", "approved-row-action");
                menu.setGraphic(IconFactory.compactIcon("actions", 14));
                menu.setContentDisplay(ContentDisplay.LEFT);
                menu.setGraphicTextGap(6);
                IconFactory.decorateActionMenu(menu);

                itemTemplates.setOnAction(ev -> {
                    TallyAutomationApiClient.ClientDirectoryRowDto row = getTableView().getItems().get(getIndex());
                    if (row != null) {
                        ClientTemplateController.setSelectedClientId(row.id());
                        DashboardController.navigateFromChildPage("Templates", "/fxml/pages/ClientTemplate.fxml");
                    }
                });

                itemGstDetails.setOnAction(ev -> {
                    TallyAutomationApiClient.ClientDirectoryRowDto row = getTableView().getItems().get(getIndex());
                    if (row != null) {
                        org.example.tally.GstFullDetailsDialog.show(tableClients, row);
                    }
                });

                itemDelete.setOnAction(ev -> {
                    TallyAutomationApiClient.ClientDirectoryRowDto row = getTableView().getItems().get(getIndex());
                    if (row != null) handleDeleteClient(row);
                });

                menu.getItems().setAll(itemTemplates, itemGstDetails, itemDelete);
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getIndex() >= getTableView().getItems().size()) {
                    setGraphic(null);
                } else {
                    setGraphic(menu);
                    setAlignment(Pos.CENTER);
                }
            }
        });
    }

    private void setupFiltering() {
        filteredList = new FilteredList<>(masterList, p -> true);
        if (txtSearch != null) {
            txtSearch.textProperty().addListener((obs, o, n) -> applyFilters());
        }
        SortedList<TallyAutomationApiClient.ClientDirectoryRowDto> sorted = new SortedList<>(filteredList);
        sorted.comparatorProperty().bind(tableClients.comparatorProperty());
        tableClients.setItems(sorted);
    }

    private void applyFilters() {
        String query = txtSearch != null ? txtSearch.getText().toLowerCase(Locale.ROOT).trim() : "";
        String statusFilter = cmbFilterStatus != null ? cmbFilterStatus.getValue() : "All Statuses";

        filteredList.setPredicate(row -> {
            if (row == null) return false;
            boolean matchesStatus = statusFilter == null || statusFilter.equals("All Statuses")
                    || statusFilter.equalsIgnoreCase(row.gstStatus());
            if (!matchesStatus) return false;

            if (query.isEmpty()) return true;
            return (row.gstin() != null && row.gstin().toLowerCase(Locale.ROOT).contains(query))
                    || (row.tradeName() != null && row.tradeName().toLowerCase(Locale.ROOT).contains(query))
                    || (row.legalName() != null && row.legalName().toLowerCase(Locale.ROOT).contains(query))
                    || (row.clientName() != null && row.clientName().toLowerCase(Locale.ROOT).contains(query));
        });

        updateKpiMetrics();
    }

    private void updateKpiMetrics() {
        int total = masterList.size();
        long active = masterList.stream().filter(c -> "ACTIVE".equalsIgnoreCase(c.gstStatus())).count();
        long inactive = total - active;
        long templates = masterList.stream().mapToLong(TallyAutomationApiClient.ClientDirectoryRowDto::templateCount).sum();

        if (lblKpiTotal != null) lblKpiTotal.setText(String.valueOf(total));
        if (lblKpiActive != null) lblKpiActive.setText(String.valueOf(active));
        if (lblKpiInactive != null) lblKpiInactive.setText(String.valueOf(inactive));
        if (lblKpiTemplates != null) lblKpiTemplates.setText(String.valueOf(templates));
    }

    @FXML
    public void handleRefresh() {
        if (isRefreshing.getAndSet(true)) {
            return;
        }
        UiTaskExecutor.submitAction(
                "load-clients-directory",
                api::listClientsDirectory,
                list -> {
                    isRefreshing.set(false);
                    masterList.setAll(list);
                    applyFilters();
                    ToastManager.success(tableClients, "Clients Loaded", list.size() + " verified client profiles synced.");
                },
                failure -> {
                    isRefreshing.set(false);
                    ToastManager.error(tableClients, "Load Failed", failure.getMessage());
                }
        );
    }

    @FXML
    public void handleResetFilter() {
        if (txtSearch != null) txtSearch.clear();
        if (cmbFilterStatus != null) cmbFilterStatus.setValue("All Statuses");
    }

    @FXML
    public void handleAddClient() {
        OwnedDialog<ButtonType> dialog = new OwnedDialog<>(tableClients);
        dialog.setTitle("Add Client via GSTIN");
        dialog.setHeaderText("Verify and onboard a new vendor or client using their authoritative GSTIN.");
        dialog.setResizable(true);

        DialogPane pane = dialog.getDialogPane();
        pane.getStyleClass().addAll("approved-dialog", "erp-client-dialog");
        pane.setMinWidth(880);
        pane.setPrefWidth(900);
        pane.setMinHeight(640);
        pane.setPrefHeight(680);

        VBox content = new VBox(12);
        content.setPadding(new Insets(16));

        // GSTIN Search Row
        Label lblGstin = new Label("15-Character GSTIN *");
        lblGstin.getStyleClass().add("field-label");
        TextField txtGstin = new TextField();
        txtGstin.setPromptText("e.g. 24AAACN9012M1Z5");
        txtGstin.getStyleClass().addAll("approved-input", "uppercase-input");

        Button btnVerify = new Button("Get Live GST Data");
        btnVerify.getStyleClass().addAll("approved-button", "approved-primary-button");
        btnVerify.setGraphic(IconFactory.compactIcon("refresh", 14));

        HBox gstinRow = new HBox(8, txtGstin, btnVerify);
        HBox.setHgrow(txtGstin, Priority.ALWAYS);

        java.util.concurrent.atomic.AtomicReference<TallyAutomationApiClient.GstPortalProfileDto> latestGstProfile =
                new java.util.concurrent.atomic.AtomicReference<>();

        // Verification Status Badge & Actions Row
        Label lblStatusBadge = new Label("Awaiting verification");
        lblStatusBadge.getStyleClass().addAll("status-badge", "status-inactive");

        Button btnViewFullGst = new Button("View Full GST Details");
        btnViewFullGst.getStyleClass().addAll("approved-button", "approved-secondary-button");
        btnViewFullGst.setGraphic(IconFactory.compactIcon("view full gst", 14));

        Button btnOpenPortal = new Button("Open GST Portal (Live)");
        btnOpenPortal.getStyleClass().addAll("approved-button", "approved-secondary-button");
        btnOpenPortal.setGraphic(IconFactory.compactIcon("web", 14));

        HBox gstStatusRow = new HBox(10, lblStatusBadge, btnViewFullGst, btnOpenPortal);
        gstStatusRow.setAlignment(Pos.CENTER_LEFT);

        // Verified Form Fields (Two-Column Comprehensive Layout - Zero Scrollbars)
        GridPane detailsGrid = new GridPane();
        detailsGrid.setHgap(16);
        detailsGrid.setVgap(10);
        ColumnConstraints col1 = new ColumnConstraints();
        col1.setPercentWidth(50);
        ColumnConstraints col2 = new ColumnConstraints();
        col2.setPercentWidth(50);
        detailsGrid.getColumnConstraints().addAll(col1, col2);

        // Column 1 Fields
        Label lblTrade = new Label("Trade Name");
        lblTrade.getStyleClass().addAll("field-label", "accent-label-purple");
        TextField txtTrade = new TextField();
        txtTrade.getStyleClass().add("approved-input");

        Label lblLegal = new Label("Legal Firm Name");
        lblLegal.getStyleClass().addAll("field-label", "accent-label-blue");
        TextField txtLegal = new TextField();
        txtLegal.getStyleClass().add("approved-input");

        Label lblAddress = new Label("Registered Principal Address");
        lblAddress.getStyleClass().addAll("field-label", "accent-label-amber");
        TextArea txtAddress = new TextArea();
        txtAddress.setPrefRowCount(2);
        txtAddress.setWrapText(true);
        txtAddress.getStyleClass().addAll("approved-input", "dialog-text-area", "address-text-display");

        Label lblNature = new Label("Nature of Core Business Activity");
        lblNature.getStyleClass().addAll("field-label", "accent-label-purple");
        TextField txtNatureOfBus = new TextField();
        txtNatureOfBus.getStyleClass().add("approved-input");

        VBox col1Box = new VBox(6, lblTrade, txtTrade, lblLegal, txtLegal, lblAddress, txtAddress, lblNature, txtNatureOfBus);

        // Column 2 Fields
        Label lblTaxpayerType = new Label("Taxpayer Type");
        lblTaxpayerType.getStyleClass().addAll("field-label", "accent-label-blue");
        TextField txtTaxpayerType = new TextField();
        txtTaxpayerType.getStyleClass().add("approved-input");

        Label lblConstitution = new Label("Constitution of Business");
        lblConstitution.getStyleClass().addAll("field-label", "accent-label-amber");
        TextField txtConstitution = new TextField();
        txtConstitution.getStyleClass().add("approved-input");

        Label lblRegDate = new Label("Effective Date of Registration");
        lblRegDate.getStyleClass().addAll("field-label", "accent-label-green");
        TextField txtRegDate = new TextField();
        txtRegDate.getStyleClass().add("approved-input");

        Label lblStateJur = new Label("State Jurisdiction");
        lblStateJur.getStyleClass().addAll("field-label", "accent-label-purple");
        TextField txtStateJur = new TextField();
        txtStateJur.getStyleClass().add("approved-input");

        Label lblCenterJur = new Label("Center Jurisdiction");
        lblCenterJur.getStyleClass().addAll("field-label", "accent-label-blue");
        TextField txtCenterJur = new TextField();
        txtCenterJur.getStyleClass().add("approved-input");

        Label lblAadhaar = new Label("Whether Aadhaar Authenticated?");
        lblAadhaar.getStyleClass().addAll("field-label", "accent-label-green");
        TextField txtAadhaar = new TextField();
        txtAadhaar.getStyleClass().add("approved-input");

        VBox col2Box = new VBox(6, lblTaxpayerType, txtTaxpayerType, lblConstitution, txtConstitution,
                lblRegDate, txtRegDate, lblStateJur, txtStateJur, lblCenterJur, txtCenterJur, lblAadhaar, txtAadhaar);

        detailsGrid.add(col1Box, 0, 0);
        detailsGrid.add(col2Box, 1, 0);

        btnVerify.setOnAction(ev -> {
            String gstin = txtGstin.getText().trim().toUpperCase(Locale.ROOT);
            if (gstin.length() != 15) {
                ToastManager.warning(pane, "Invalid GSTIN", "GSTIN must be exactly 15 alphanumeric characters.");
                return;
            }
            UiTaskExecutor.submitAction(
                    "verify-gstin",
                    () -> api.verifyGstin(gstin),
                    resp -> {
                        latestGstProfile.set(resp);
                        txtTrade.setText(resp.tradeName() != null ? resp.tradeName() : "");
                        txtLegal.setText(resp.legalName() != null ? resp.legalName() : "");
                        txtAddress.setText(resp.address() != null ? resp.address() : "");
                        txtTaxpayerType.setText(resp.taxpayerType() != null ? resp.taxpayerType() : "Regular");
                        txtConstitution.setText(resp.constitutionOfBusiness() != null ? resp.constitutionOfBusiness() : "Proprietorship");
                        txtRegDate.setText(resp.registrationDate() != null ? resp.registrationDate() : "01/07/2017");
                        txtStateJur.setText(resp.stateJurisdiction() != null ? resp.stateJurisdiction() : "");
                        txtCenterJur.setText(resp.centerJurisdiction() != null ? resp.centerJurisdiction() : "");
                        txtNatureOfBus.setText(resp.natureOfBusiness() != null ? resp.natureOfBusiness() : "Manufacturer");
                        txtAadhaar.setText(resp.aadhaarAuthenticated() != null ? resp.aadhaarAuthenticated() : "Yes");
                        lblStatusBadge.setText(resp.status());
                        if (resp.isValid() && resp.legalName() != null && !resp.legalName().isBlank()) {
                            lblStatusBadge.getStyleClass().setAll("status-badge", "status-active");
                            ToastManager.success(pane, "Verified", "Authoritative profile fetched for " + resp.legalName());
                        } else {
                            lblStatusBadge.getStyleClass().setAll("status-badge", "status-inactive");
                            ToastManager.info(pane, "GSTIN Validated", "Format verified. Please enter client legal name and address to save.");
                        }
                    },
                    err -> ToastManager.error(pane, "Verification Error", err.getMessage())
            );
        });

        btnViewFullGst.setOnAction(ev -> {
            if (latestGstProfile.get() != null) {
                org.example.tally.GstFullDetailsDialog.show(tableClients, latestGstProfile.get());
            } else {
                String gstin = txtGstin.getText().trim().toUpperCase(Locale.ROOT);
                if (gstin.length() == 15) {
                    UiTaskExecutor.submitAction(
                            "verify-gstin-dialog",
                            () -> api.verifyGstin(gstin),
                            p -> {
                                latestGstProfile.set(p);
                                txtTrade.setText(p.tradeName());
                                txtLegal.setText(p.legalName());
                                txtAddress.setText(p.address());
                                txtTaxpayerType.setText(p.taxpayerType());
                                txtConstitution.setText(p.constitutionOfBusiness());
                                txtRegDate.setText(p.registrationDate());
                                txtStateJur.setText(p.stateJurisdiction());
                                txtCenterJur.setText(p.centerJurisdiction());
                                txtNatureOfBus.setText(p.natureOfBusiness());
                                txtAadhaar.setText(p.aadhaarAuthenticated() != null ? p.aadhaarAuthenticated() : "Yes");
                                lblStatusBadge.setText(p.status());
                                if (p.isValid() && p.legalName() != null && !p.legalName().isBlank()) {
                                    lblStatusBadge.getStyleClass().setAll("status-badge", "status-active");
                                } else {
                                    lblStatusBadge.getStyleClass().setAll("status-badge", "status-inactive");
                                }
                                org.example.tally.GstFullDetailsDialog.show(tableClients, p);
                            },
                            err -> ToastManager.error(pane, "Verification Error", err.getMessage())
                    );
                } else {
                    ToastManager.warning(pane, "Invalid GSTIN", "Please enter a valid 15-character GSTIN first.");
                }
            }
        });

        btnOpenPortal.setOnAction(ev -> {
            String gstin = txtGstin.getText().trim().toUpperCase(Locale.ROOT);
            if (!gstin.isBlank()) {
                javafx.scene.input.ClipboardContent clip = new javafx.scene.input.ClipboardContent();
                clip.putString(gstin);
                javafx.scene.input.Clipboard.getSystemClipboard().setContent(clip);
                ToastManager.info(pane, "Copied", "GSTIN copied to clipboard. Opening GST Portal...");
            }
            try {
                if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                    java.awt.Desktop.getDesktop().browse(new java.net.URI("https://services.gst.gov.in/services/searchtp"));
                }
            } catch (Exception ex) {
                ToastManager.error(pane, "Portal Error", "Unable to open browser: " + ex.getMessage());
            }
        });

        content.getChildren().addAll(
                lblGstin, gstinRow, gstStatusRow,
                detailsGrid
        );

        pane.setContent(content);
        ButtonType btnSave = new ButtonType("Save Client", ButtonBar.ButtonData.OK_DONE);
        pane.getButtonTypes().addAll(btnSave, ButtonType.CANCEL);

        dialog.showAndWait().filter(b -> b == btnSave).ifPresent(b -> {
            String gstin = txtGstin.getText().trim().toUpperCase(Locale.ROOT);
            String trade = txtTrade.getText().trim();
            String legal = txtLegal.getText().trim();
            if (gstin.isBlank() || trade.isBlank()) {
                ToastManager.warning(tableClients, "Missing Data", "GSTIN and Trade Name are required.");
                return;
            }

            var p = latestGstProfile.get();
            String filingsJson = null;
            if (p != null && p.filings() != null) {
                try {
                    filingsJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(p.filings());
                } catch (Exception ignored) {}
            }

            var dto = new TallyAutomationApiClient.ClientProfileDto(
                    null,
                    trade,
                    trade,
                    gstin,
                    trade,
                    legal,
                    txtAddress.getText(),
                    lblStatusBadge.getText(),
                    !txtTaxpayerType.getText().isBlank() ? txtTaxpayerType.getText().trim() : (p != null && p.taxpayerType() != null ? p.taxpayerType() : "REGULAR"),
                    !txtRegDate.getText().isBlank() ? txtRegDate.getText().trim() : (p != null ? p.registrationDate() : null),
                    !txtConstitution.getText().isBlank() ? txtConstitution.getText().trim() : (p != null ? p.constitutionOfBusiness() : null),
                    !txtStateJur.getText().isBlank() ? txtStateJur.getText().trim() : (p != null ? p.stateJurisdiction() : null),
                    !txtCenterJur.getText().isBlank() ? txtCenterJur.getText().trim() : (p != null ? p.centerJurisdiction() : null),
                    !txtNatureOfBus.getText().isBlank() ? txtNatureOfBus.getText().trim() : (p != null ? p.natureOfBusiness() : null),
                    filingsJson,
                    gstin.substring(0, 2),
                    "ITEM",
                    "Purchase Account",
                    "Round Off",
                    true,
                    true,
                    false,
                    false,
                    null
            );

            UiTaskExecutor.submitAction(
                    "save-client-profile",
                    () -> api.saveProfile(dto),
                    saved -> {
                        handleRefresh();
                        ToastManager.success(tableClients, "Client Created", "Client " + saved.clientName() + " onboarded successfully.");
                    },
                    failure -> ToastManager.error(tableClients, "Save Failed", failure.getMessage())
            );
        });
    }

    private void handleDeleteClient(TallyAutomationApiClient.ClientDirectoryRowDto row) {
        Alert confirm = new org.example.util.OwnedAlert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Confirm Deletion");
        confirm.setHeaderText("Delete Client " + row.tradeName() + "?");
        confirm.setContentText("All associated templates and profile settings will be permanently removed.");
        confirm.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> {
            UiTaskExecutor.submitAction(
                    "delete-client",
                    () -> {
                        api.deleteProfile(row.id());
                        return true;
                    },
                    ok -> {
                        masterList.remove(row);
                        updateKpiMetrics();
                        ToastManager.info(tableClients, "Deleted", "Client removed.");
                    },
                    failure -> ToastManager.error(tableClients, "Delete Failed", failure.getMessage())
            );
        });
    }

    @Override
    public void onScreenShown(boolean reusedFromCache) {
        handleRefresh();
    }

    @Override
    public void onScreenHidden() {}
}
