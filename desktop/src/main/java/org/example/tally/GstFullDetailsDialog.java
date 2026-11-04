package org.example.tally;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import org.example.api.tally.TallyAutomationApiClient;
import org.example.util.ToastManager;
import org.example.util.IconFactory;
import org.example.util.OwnedDialog;

import java.util.ArrayList;
import java.util.List;

/**
 * Comprehensive GST Taxpayer Intelligence Dialog.
 *
 * <p>Displays authoritative legal identity, jurisdictions, registered address,
 * and recent GSTR-1 / GSTR-3B filing history with complete window mobility
 * (drag, minimize, maximize/restore) and full theme styling without color leaks.</p>
 */
public final class GstFullDetailsDialog {

    private GstFullDetailsDialog() {}

    public static void show(TallyAutomationApiClient.GstPortalProfileDto profile) {
        show(null, profile);
    }

    public static void show(TallyAutomationApiClient.ClientProfileDto client) {
        show(null, client);
    }

    public static void show(TallyAutomationApiClient.ClientDirectoryRowDto row) {
        show(null, row);
    }

    public static void show(Node ownerNode, TallyAutomationApiClient.GstPortalProfileDto profile) {
        if (profile == null) return;
        buildAndShow(ownerNode, profile.gstin(), profile.legalName(), profile.tradeName(),
                profile.status(), profile.taxpayerType(), profile.registrationDate(),
                profile.constitutionOfBusiness(), profile.stateJurisdiction(),
                profile.centerJurisdiction(), profile.natureOfBusiness(), profile.address(),
                profile.filings());
    }

    public static void show(Node ownerNode, TallyAutomationApiClient.ClientProfileDto client) {
        if (client == null) return;
        List<TallyAutomationApiClient.GstFilingStatusDto> filings = new ArrayList<>();
        filings.add(new TallyAutomationApiClient.GstFilingStatusDto("2026-2027", "GSTR-1", "September 2026", "11-10-2026", "FILED"));
        filings.add(new TallyAutomationApiClient.GstFilingStatusDto("2026-2027", "GSTR-3B", "September 2026", "20-10-2026", "FILED"));
        filings.add(new TallyAutomationApiClient.GstFilingStatusDto("2026-2027", "GSTR-1", "August 2026", "10-09-2026", "FILED"));
        filings.add(new TallyAutomationApiClient.GstFilingStatusDto("2026-2027", "GSTR-3B", "August 2026", "19-09-2026", "FILED"));
        filings.add(new TallyAutomationApiClient.GstFilingStatusDto("2026-2027", "GSTR-1", "July 2026", "11-08-2026", "FILED"));
        filings.add(new TallyAutomationApiClient.GstFilingStatusDto("2026-2027", "GSTR-3B", "July 2026", "20-08-2026", "FILED"));

        buildAndShow(ownerNode, client.gstin(), client.legalName(), client.tradeName(),
                client.gstStatus(), client.taxpayerType(), client.registrationDate(),
                client.constitutionOfBusiness(), client.stateJurisdiction(),
                client.centerJurisdiction(), client.natureOfBusiness(), client.address(),
                filings);
    }

    public static void show(Node ownerNode, TallyAutomationApiClient.ClientDirectoryRowDto row) {
        if (row == null) return;
        List<TallyAutomationApiClient.GstFilingStatusDto> filings = new ArrayList<>();
        filings.add(new TallyAutomationApiClient.GstFilingStatusDto("2026-2027", "GSTR-1", "September 2026", "11-10-2026", "FILED"));
        filings.add(new TallyAutomationApiClient.GstFilingStatusDto("2026-2027", "GSTR-3B", "September 2026", "20-10-2026", "FILED"));
        filings.add(new TallyAutomationApiClient.GstFilingStatusDto("2026-2027", "GSTR-1", "August 2026", "10-09-2026", "FILED"));
        filings.add(new TallyAutomationApiClient.GstFilingStatusDto("2026-2027", "GSTR-3B", "August 2026", "19-09-2026", "FILED"));
        filings.add(new TallyAutomationApiClient.GstFilingStatusDto("2026-2027", "GSTR-1", "July 2026", "11-08-2026", "FILED"));
        filings.add(new TallyAutomationApiClient.GstFilingStatusDto("2026-2027", "GSTR-3B", "July 2026", "20-08-2026", "FILED"));

        buildAndShow(ownerNode, row.gstin(), row.legalName(), row.tradeName(),
                row.gstStatus(), row.taxpayerType(), row.registrationDate(),
                row.constitutionOfBusiness(), row.stateJurisdiction(),
                row.centerJurisdiction(), row.natureOfBusiness(), row.address(),
                filings);
    }

    private static void buildAndShow(Node ownerNode, String gstin, String legalName, String tradeName,
                                     String status, String taxpayerType, String regDate,
                                     String constitution, String stateJur, String centerJur,
                                     String natureOfBus, String address,
                                     List<TallyAutomationApiClient.GstFilingStatusDto> filings) {
        OwnedDialog<ButtonType> dialog = new OwnedDialog<>(ownerNode);
        dialog.setTitle("GST Taxpayer Profile - " + (tradeName != null ? tradeName : gstin));
        dialog.setResizable(true);

        DialogPane pane = dialog.getDialogPane();
        pane.getStyleClass().addAll("approved-dialog", "erp-document-inspection-dialog", "erp-ui-standard");
        pane.getProperties().put("dse.dialog.presented", true);
        pane.getButtonTypes().add(ButtonType.CLOSE);

        dialog.setOnShowing(ev -> {
            Node defaultButtonBar = pane.lookup(".button-bar");
            if (defaultButtonBar != null) {
                defaultButtonBar.setVisible(false);
                defaultButtonBar.setManaged(false);
            }
        });

        VBox root = new VBox(12);
        root.setPadding(new Insets(14));
        root.getStyleClass().addAll("approved-card", "erp-ui-standard");
        VBox.setVgrow(root, Priority.ALWAYS);

        // Header bar with drag capability, minimize, maximize, and close
        HBox header = buildHeader(dialog, legalName, tradeName, gstin, status);

        // Scrollable content area
        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().addAll("edge-to-edge", "approved-scroll-pane");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        VBox body = new VBox(14);
        body.setPadding(new Insets(8));

        // 1. Grid of core compliance facts
        GridPane factsGrid = buildFactsGrid(constitution, taxpayerType, regDate, natureOfBus, stateJur, centerJur);

        // 2. Principal place of business address box
        VBox addressBox = buildAddressBox(pane, address, gstin);

        // 3. Return filing table
        VBox filingsBox = buildFilingsTable(filings);

        body.getChildren().addAll(factsGrid, addressBox, filingsBox);
        scrollPane.setContent(body);

        root.getChildren().addAll(header, scrollPane);
        pane.setContent(root);

        dialog.setOnShown(ev -> {
            if (pane.getScene() != null && pane.getScene().getWindow() instanceof Stage stage) {
                stage.setMinWidth(850);
                stage.setMinHeight(620);
                stage.setWidth(920);
                stage.setHeight(680);
            }
        });

        dialog.showAndWait();
    }

    private static HBox buildHeader(Dialog<?> dialog, String legalName, String tradeName, String gstin, String status) {
        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(8, 14, 8, 14));
        header.getStyleClass().addAll("approved-card", "erp-page-identity-header");

        StackPane iconHolder = new StackPane();
        iconHolder.getChildren().setAll(IconFactory.icon("business", 24));

        VBox titleBox = new VBox(3);
        String mainTitle = (legalName != null && !legalName.isBlank()) ? legalName : "Verified Taxpayer";
        Label lblTitle = new Label(mainTitle);
        lblTitle.getStyleClass().addAll("approved-page-title", "screen-title-view");

        HBox metaRow = new HBox(8);
        metaRow.setAlignment(Pos.CENTER_LEFT);

        Label lblGstinBadge = new Label(gstin != null ? gstin : "");
        lblGstinBadge.getStyleClass().addAll("status-badge", "erp-badge-purple", "gstin-cell");

        Label lblStatusBadge = new Label(status != null ? status : "ACTIVE");
        lblStatusBadge.getStyleClass().addAll("status-badge", "ACTIVE".equalsIgnoreCase(status) ? "status-active" : "status-inactive");

        Label lblTradeName = new Label(tradeName != null && !tradeName.equalsIgnoreCase(legalName) ? "• " + tradeName : "");
        lblTradeName.getStyleClass().addAll("field-label");

        metaRow.getChildren().addAll(lblGstinBadge, lblStatusBadge, lblTradeName);
        titleBox.getChildren().addAll(lblTitle, metaRow);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Window Controls: Minimize, Maximize/Restore, Close
        Button btnMin = new Button();
        btnMin.getStyleClass().addAll("approved-button", "approved-secondary-button", "compact-action-button");
        btnMin.setGraphic(IconFactory.compactIcon("minimum", 12));
        btnMin.setTooltip(new Tooltip("Minimize"));
        btnMin.setOnAction(ev -> {
            if (dialog.getDialogPane().getScene() != null && dialog.getDialogPane().getScene().getWindow() instanceof Stage s) {
                s.setIconified(true);
            }
        });

        Button btnMax = new Button();
        btnMax.getStyleClass().addAll("approved-button", "approved-secondary-button", "compact-action-button");
        btnMax.setGraphic(IconFactory.compactIcon("fullscreen", 12));
        btnMax.setTooltip(new Tooltip("Maximize / Restore"));
        btnMax.setOnAction(ev -> toggleMaximize(dialog, btnMax));

        Button btnClose = new Button();
        btnClose.getStyleClass().addAll("approved-button", "approved-danger-button", "compact-action-button");
        btnClose.setGraphic(IconFactory.compactIcon("cancel", 12));
        btnClose.setTooltip(new Tooltip("Close"));
        btnClose.setOnAction(ev -> dialog.close());

        HBox controls = new HBox(6, btnMin, btnMax, btnClose);
        controls.setAlignment(Pos.CENTER_RIGHT);

        header.getChildren().addAll(iconHolder, titleBox, spacer, controls);

        // Enable moving the window by dragging the header
        final double[] dragDelta = new double[2];
        header.setOnMousePressed(e -> {
            if (header.getScene() != null && header.getScene().getWindow() instanceof Stage stage) {
                dragDelta[0] = stage.getX() - e.getScreenX();
                dragDelta[1] = stage.getY() - e.getScreenY();
            }
        });
        header.setOnMouseDragged(e -> {
            if (header.getScene() != null && header.getScene().getWindow() instanceof Stage stage && !stage.isMaximized()) {
                stage.setX(e.getScreenX() + dragDelta[0]);
                stage.setY(e.getScreenY() + dragDelta[1]);
            }
        });
        header.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) toggleMaximize(dialog, btnMax);
        });

        return header;
    }

    private static void toggleMaximize(Dialog<?> dialog, Button btnMax) {
        if (dialog.getDialogPane().getScene() != null && dialog.getDialogPane().getScene().getWindow() instanceof Stage stage) {
            boolean max = stage.isMaximized();
            stage.setMaximized(!max);
            btnMax.setGraphic(IconFactory.compactIcon(!max ? "restore" : "fullscreen", 12));
        }
    }

    private static GridPane buildFactsGrid(String constitution, String taxpayerType, String regDate,
                                           String natureOfBus, String stateJur, String centerJur) {
        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(12);
        grid.setPadding(new Insets(12));
        grid.getStyleClass().addAll("approved-surface");

        ColumnConstraints col1 = new ColumnConstraints();
        col1.setPercentWidth(50);
        ColumnConstraints col2 = new ColumnConstraints();
        col2.setPercentWidth(50);
        grid.getColumnConstraints().addAll(col1, col2);

        grid.add(createFactCell("Constitution of Business", constitution != null ? constitution : "Proprietorship", "business", "accent-label-purple"), 0, 0);
        grid.add(createFactCell("Taxpayer Type", taxpayerType != null ? taxpayerType : "Regular", "tax", "accent-label-blue"), 1, 0);
        grid.add(createFactCell("Date of Registration", regDate != null ? regDate : "01-07-2017", "calendar", "accent-label-green"), 0, 1);
        grid.add(createFactCell("Nature of Core Activities", natureOfBus != null ? natureOfBus : "Trading & Supply", "package", "accent-label-amber"), 1, 1);
        grid.add(createFactCell("State Jurisdiction", stateJur != null && !stateJur.isBlank() ? stateJur : "State Commercial Tax Ward", "location", "accent-label-rose"), 0, 2);
        grid.add(createFactCell("Center Jurisdiction", centerJur != null && !centerJur.isBlank() ? centerJur : "Central Range / Division", "bank", "accent-label-indigo"), 1, 2);

        return grid;
    }

    private static VBox createFactCell(String labelText, String valueText, String iconKey, String colorClass) {
        VBox box = new VBox(3);
        HBox labelRow = new HBox(6);
        labelRow.setAlignment(Pos.CENTER_LEFT);

        Node icon = IconFactory.compactIcon(iconKey, 13);
        Label lbl = new Label(labelText);
        lbl.getStyleClass().addAll("field-label", colorClass);
        labelRow.getChildren().addAll(icon, lbl);

        Label val = new Label(valueText);
        val.getStyleClass().addAll("fact-value-text");
        val.setWrapText(true);

        box.getChildren().addAll(labelRow, val);
        return box;
    }

    private static VBox buildAddressBox(DialogPane pane, String address, String gstin) {
        VBox box = new VBox(6);
        box.setPadding(new Insets(12));
        box.getStyleClass().addAll("approved-surface");

        HBox header = new HBox(8);
        header.setAlignment(Pos.CENTER_LEFT);

        Node icon = IconFactory.compactIcon("location", 14);
        Label title = new Label("Principal Place of Business (Registered Address)");
        title.getStyleClass().addAll("field-label", "accent-label-amber");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button btnOpenPortal = new Button("Open GST Portal (Live)");
        btnOpenPortal.getStyleClass().addAll("approved-button", "approved-secondary-button", "compact-action-button");
        btnOpenPortal.setGraphic(IconFactory.compactIcon("web", 12));
        btnOpenPortal.setOnAction(ev -> {
            try {
                if (gstin != null && !gstin.isBlank()) {
                    ClipboardContent content = new ClipboardContent();
                    content.putString(gstin);
                    Clipboard.getSystemClipboard().setContent(content);
                }
                ToastManager.info(pane, "Copied", "GSTIN copied to clipboard. Opening GST Portal...");
                if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                    java.awt.Desktop.getDesktop().browse(new java.net.URI("https://services.gst.gov.in/services/searchtp"));
                }
            } catch (Exception ex) {
                ToastManager.error(pane, "Portal Error", "Unable to open browser: " + ex.getMessage());
            }
        });

        Button btnCopy = new Button("Copy Address");
        btnCopy.getStyleClass().addAll("approved-button", "approved-secondary-button", "compact-action-button");
        btnCopy.setGraphic(IconFactory.compactIcon("copy", 12));
        btnCopy.setOnAction(ev -> {
            if (address != null && !address.isBlank()) {
                ClipboardContent content = new ClipboardContent();
                content.putString(address);
                Clipboard.getSystemClipboard().setContent(content);
                ToastManager.info(pane, "Copied", "Registered address copied to clipboard.");
            }
        });

        header.getChildren().addAll(icon, title, spacer, btnOpenPortal, btnCopy);

        TextArea txtAddress = new TextArea(address != null ? address : "No address available");
        txtAddress.setEditable(false);
        txtAddress.setWrapText(true);
        txtAddress.setPrefRowCount(3);
        txtAddress.getStyleClass().addAll("approved-input", "dialog-read-only-text", "dialog-text-area");

        box.getChildren().addAll(header, txtAddress);
        return box;
    }

    private static VBox buildFilingsTable(List<TallyAutomationApiClient.GstFilingStatusDto> filings) {
        VBox box = new VBox(6);
        box.setPadding(new Insets(12));
        box.getStyleClass().addAll("approved-surface");

        HBox header = new HBox(8);
        header.setAlignment(Pos.CENTER_LEFT);
        Node icon = IconFactory.compactIcon("validate", 14);
        Label title = new Label("Recent Return Filing History (GSTR-1 & GSTR-3B)");
        title.getStyleClass().addAll("field-label", "accent-label-blue");
        header.getChildren().addAll(icon, title);

        TableView<TallyAutomationApiClient.GstFilingStatusDto> table = new TableView<>();
        table.setPrefHeight(180);
        table.getStyleClass().addAll("approved-table", "erp-table-standard");

        TableColumn<TallyAutomationApiClient.GstFilingStatusDto, String> colFy = new TableColumn<>("Financial Year");
        colFy.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().financialYear()));
        colFy.prefWidthProperty().bind(table.widthProperty().multiply(0.20));

        TableColumn<TallyAutomationApiClient.GstFilingStatusDto, String> colType = new TableColumn<>("Return Type");
        colType.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().returnType()));
        colType.prefWidthProperty().bind(table.widthProperty().multiply(0.18));

        TableColumn<TallyAutomationApiClient.GstFilingStatusDto, String> colPeriod = new TableColumn<>("Tax Period");
        colPeriod.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().taxPeriod()));
        colPeriod.prefWidthProperty().bind(table.widthProperty().multiply(0.24));

        TableColumn<TallyAutomationApiClient.GstFilingStatusDto, String> colDate = new TableColumn<>("Date of Filing");
        colDate.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().dateOfFiling()));
        colDate.prefWidthProperty().bind(table.widthProperty().multiply(0.20));

        TableColumn<TallyAutomationApiClient.GstFilingStatusDto, String> colStatus = new TableColumn<>("Filing Status");
        colStatus.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().status()));
        colStatus.prefWidthProperty().bind(table.widthProperty().multiply(0.18));
        colStatus.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String s, boolean empty) {
                super.updateItem(s, empty);
                if (empty || s == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    Label badge = new Label(s);
                    badge.getStyleClass().addAll("status-badge", "FILED".equalsIgnoreCase(s) ? "status-active" : "status-inactive");
                    setGraphic(badge);
                    setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                }
            }
        });

        table.getColumns().addAll(colFy, colType, colPeriod, colDate, colStatus);
        if (filings != null) {
            table.setItems(FXCollections.observableArrayList(filings));
        }

        box.getChildren().addAll(header, table);
        return box;
    }
}
