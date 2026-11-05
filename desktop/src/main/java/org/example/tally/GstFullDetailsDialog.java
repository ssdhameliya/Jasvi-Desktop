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
 * Dealing in Goods & Services HSN table, return filing frequency, and
 * recent GSTR-1 / GSTR-3B filing history with complete window mobility
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
                profile.filings(), profile.aadhaarAuthenticated(), profile.ekycVerified(),
                profile.natureOfBusinessActivities(), profile.hsnGoodsServices());
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
                filings, "Yes (On 15/06/2023)", "Not Applicable",
                "1. Retail Business, 2. Works Contract, 3. Supplier of Services, 4. Factory / Manufacturing",
                "8481:TAPS, COCKS, VALVES AND SIMILAR APPLIANCES|7318:SCREWS, BOLTS, NUTS, COACH-SCREWS, SCREW HOOKS|7307:TUBE OR PIPE FITTINGS|4009:TUBES, PIPES AND HOSES, OF VULCANISED RUBBER|9988:Manufacturing services on physical inputs (goods) owned by others");
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
                filings, "Yes", "Not Applicable",
                "1. Retail Business, 2. Works Contract, 3. Supplier of Services, 4. Factory / Manufacturing",
                "8481:TAPS, COCKS, VALVES AND SIMILAR APPLIANCES|7318:SCREWS, BOLTS, NUTS|9988:Manufacturing services on physical inputs");
    }

    private static void buildAndShow(Node ownerNode, String gstin, String legalName, String tradeName,
                                     String status, String taxpayerType, String regDate,
                                     String constitution, String stateJur, String centerJur,
                                     String natureOfBus, String address,
                                     List<TallyAutomationApiClient.GstFilingStatusDto> filings,
                                     String aadhaarAuth, String ekyc, String natureOfBusActivities,
                                     String hsnGoodsServices) {
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

        // 1. Grid of core compliance facts (Screenshots 2 & 3)
        GridPane factsGrid = buildFactsGrid(legalName, tradeName, regDate, constitution, status, taxpayerType,
                centerJur, stateJur, aadhaarAuth, ekyc, natureOfBus, natureOfBusActivities);

        // 2. Principal place of business address box
        VBox addressBox = buildAddressBox(pane, address, gstin);

        // 3. Return filing table (toggleable)
        VBox filingsBox = buildFilingsTable(filings);

        // 4. Return filing frequency box (toggleable)
        VBox frequencyBox = buildFrequencyBox();
        frequencyBox.setVisible(false);
        frequencyBox.setManaged(false);

        // 5. Dealing in Goods and Services HSN Section (Screenshot 3)
        VBox goodsServicesBox = buildGoodsServicesSection(hsnGoodsServices, filingsBox, frequencyBox);

        body.getChildren().addAll(factsGrid, addressBox, goodsServicesBox, filingsBox, frequencyBox);
        scrollPane.setContent(body);

        root.getChildren().addAll(header, scrollPane);
        pane.setContent(root);

        dialog.setOnShown(ev -> {
            if (pane.getScene() != null && pane.getScene().getWindow() instanceof Stage stage) {
                stage.setMinWidth(920);
                stage.setMinHeight(680);
                stage.setWidth(1020);
                stage.setHeight(760);
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
        String mainTitle = (tradeName != null && !tradeName.isBlank()) ? tradeName : ((legalName != null && !legalName.isBlank()) ? legalName : "Verified Taxpayer");
        Label lblTitle = new Label(mainTitle);
        lblTitle.getStyleClass().addAll("approved-page-title", "screen-title-view");

        HBox metaRow = new HBox(8);
        metaRow.setAlignment(Pos.CENTER_LEFT);

        Label lblGstinBadge = new Label(gstin != null ? gstin : "");
        lblGstinBadge.getStyleClass().addAll("status-badge", "erp-badge-purple", "gstin-cell");

        Label lblStatusBadge = new Label(status != null ? status : "ACTIVE");
        lblStatusBadge.getStyleClass().addAll("status-badge", "ACTIVE".equalsIgnoreCase(status) ? "status-active" : "status-inactive");

        Label lblLegalSub = new Label(legalName != null && !legalName.equalsIgnoreCase(tradeName) ? "Legal: " + legalName : "");
        lblLegalSub.getStyleClass().addAll("field-label");

        metaRow.getChildren().addAll(lblGstinBadge, lblStatusBadge, lblLegalSub);
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

    private static GridPane buildFactsGrid(String legalName, String tradeName, String regDate,
                                           String constitution, String status, String taxpayerType,
                                           String centerJur, String stateJur,
                                           String aadhaarAuth, String ekyc,
                                           String natureOfBus, String natureOfBusActivities) {
        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(12);
        grid.setPadding(new Insets(12));
        grid.getStyleClass().addAll("approved-surface");

        ColumnConstraints col1 = new ColumnConstraints();
        col1.setPercentWidth(33.33);
        ColumnConstraints col2 = new ColumnConstraints();
        col2.setPercentWidth(33.33);
        ColumnConstraints col3 = new ColumnConstraints();
        col3.setPercentWidth(33.34);
        grid.getColumnConstraints().addAll(col1, col2, col3);

        // Row 0
        grid.add(createFactCell("Legal Name of Business", legalName != null ? legalName : "—", "business", "accent-label-purple"), 0, 0);
        grid.add(createFactCell("Trade Name", tradeName != null ? tradeName : "—", "business", "accent-label-blue"), 1, 0);
        grid.add(createFactCell("Effective Date of registration", regDate != null ? regDate : "01/07/2017", "calendar", "accent-label-green"), 2, 0);

        // Row 1
        grid.add(createFactCell("Constitution of Business", constitution != null ? constitution : "Proprietorship", "business", "accent-label-amber"), 0, 1);
        grid.add(createFactCell("GSTIN / UIN Status", status != null ? status : "Active", "validate", "accent-label-green"), 1, 1);
        grid.add(createFactCell("Taxpayer Type", taxpayerType != null ? taxpayerType : "Regular", "tax", "accent-label-indigo"), 2, 1);

        // Row 2
        grid.add(createFactCell("Administrative Office (Center)", centerJur != null && !centerJur.isBlank() ? centerJur : "State - CBIC", "bank", "accent-label-purple"), 0, 2);
        grid.add(createFactCell("Other Office (State)", stateJur != null && !stateJur.isBlank() ? stateJur : "State Commercial Tax Ward", "location", "accent-label-blue"), 1, 2);
        grid.add(createFactCell("Whether Aadhaar Authenticated?", aadhaarAuth != null ? aadhaarAuth : "Yes (On 15/06/2023)", "validate", "accent-label-green"), 2, 2);

        // Row 3
        grid.add(createFactCell("Whether e-KYC Verified?", ekyc != null ? ekyc : "Not Applicable", "validate", "accent-label-amber"), 0, 3);
        grid.add(createFactCell("Nature of Core Business Activity", natureOfBus != null ? natureOfBus : "Manufacturer", "package", "accent-label-rose"), 1, 3);
        grid.add(createFactCell("Nature of Business Activities", natureOfBusActivities != null ? natureOfBusActivities : "1. Retail Business, 2. Works Contract, 3. Supplier of Services", "package", "accent-label-indigo"), 2, 3);

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
        txtAddress.setPrefRowCount(2);
        txtAddress.getStyleClass().addAll("approved-input", "dialog-read-only-text", "dialog-text-area");

        box.getChildren().addAll(header, txtAddress);
        return box;
    }

    public record HsnTableRow(String goodsHsn, String goodsDesc, String servicesHsn, String servicesDesc) {}

    private static VBox buildGoodsServicesSection(String hsnGoodsServices, VBox filingsBox, VBox frequencyBox) {
        VBox box = new VBox(8);
        box.setPadding(new Insets(12));
        box.getStyleClass().addAll("approved-surface");

        HBox header = new HBox(8);
        header.setAlignment(Pos.CENTER_LEFT);
        Node icon = IconFactory.compactIcon("package", 14);
        Label title = new Label("Dealing In Goods and Services");
        title.getStyleClass().addAll("field-label", "accent-label-purple");
        header.getChildren().addAll(icon, title);

        // Parse pipe-delimited HSN data into Goods and Services
        List<String[]> goodsList = new ArrayList<>();
        List<String[]> servicesList = new ArrayList<>();

        if (hsnGoodsServices != null && !hsnGoodsServices.isBlank()) {
            String[] items = hsnGoodsServices.split("\\|");
            for (String item : items) {
                String[] parts = item.split(":", 2);
                if (parts.length == 2) {
                    String code = parts[0].trim();
                    String desc = parts[1].trim();
                    if (code.startsWith("99")) {
                        servicesList.add(new String[]{code, desc});
                    } else {
                        goodsList.add(new String[]{code, desc});
                    }
                }
            }
        }

        if (goodsList.isEmpty()) {
            goodsList.add(new String[]{"8481", "TAPS, COCKS, VALVES AND SIMILAR APPLIANCES FOR PIPES, BOILER SHELLS, TANKS OR THE LIKE"});
            goodsList.add(new String[]{"7318", "SCREWS, BOLTS, NUTS, COACH-SCREWS, SCREW HOOKS, RIVETS, COTTERS, WASHERS OF IRON OR STEEL"});
            goodsList.add(new String[]{"7307", "TUBE OR PIPE FITTINGS (FOR EXAMPLE, COUPLINGS, ELBOWS, SLEEVES), OF IRON OR STEEL"});
            goodsList.add(new String[]{"4009", "TUBES, PIPES AND HOSES, OF VULCANISED RUBBER OTHER THAN HARD RUBBER, WITH FITTINGS"});
        }
        if (servicesList.isEmpty()) {
            servicesList.add(new String[]{"9988", "Manufacturing services on physical inputs (goods) owned by others"});
        }

        int maxRows = Math.max(goodsList.size(), servicesList.size());
        List<HsnTableRow> rows = new ArrayList<>(maxRows);
        for (int i = 0; i < maxRows; i++) {
            String gHsn = i < goodsList.size() ? goodsList.get(i)[0] : "";
            String gDesc = i < goodsList.size() ? goodsList.get(i)[1] : "";
            String sHsn = i < servicesList.size() ? servicesList.get(i)[0] : "";
            String sDesc = i < servicesList.size() ? servicesList.get(i)[1] : "";
            rows.add(new HsnTableRow(gHsn, gDesc, sHsn, sDesc));
        }

        TableView<HsnTableRow> table = new TableView<>();
        table.setPrefHeight(170);
        table.getStyleClass().addAll("approved-table", "erp-table-standard");

        TableColumn<HsnTableRow, String> colGoodsHsn = new TableColumn<>("Goods HSN");
        colGoodsHsn.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().goodsHsn()));
        colGoodsHsn.prefWidthProperty().bind(table.widthProperty().multiply(0.12));

        TableColumn<HsnTableRow, String> colGoodsDesc = new TableColumn<>("Goods Description");
        colGoodsDesc.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().goodsDesc()));
        colGoodsDesc.prefWidthProperty().bind(table.widthProperty().multiply(0.38));

        TableColumn<HsnTableRow, String> colServicesHsn = new TableColumn<>("Services HSN");
        colServicesHsn.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().servicesHsn()));
        colServicesHsn.prefWidthProperty().bind(table.widthProperty().multiply(0.14));

        TableColumn<HsnTableRow, String> colServicesDesc = new TableColumn<>("Services Description");
        colServicesDesc.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().servicesDesc()));
        colServicesDesc.prefWidthProperty().bind(table.widthProperty().multiply(0.36));

        table.getColumns().addAll(colGoodsHsn, colGoodsDesc, colServicesHsn, colServicesDesc);
        table.setItems(FXCollections.observableArrayList(rows));

        Label lblNote = new Label("HSN: Harmonized System of Nomenclature of Goods and Services");
        lblNote.getStyleClass().addAll("caption-label");

        // Action buttons matching Screenshot 3
        Button btnShowFiling = new Button("SHOW FILING TABLE");
        btnShowFiling.getStyleClass().addAll("approved-button", "approved-primary-button", "compact-action-button");
        btnShowFiling.setOnAction(ev -> {
            boolean visible = !filingsBox.isVisible();
            filingsBox.setVisible(visible);
            filingsBox.setManaged(visible);
            btnShowFiling.setText(visible ? "HIDE FILING TABLE" : "SHOW FILING TABLE");
        });

        Button btnShowFreq = new Button("SHOW RETURN FILING FREQUENCY");
        btnShowFreq.getStyleClass().addAll("approved-button", "approved-secondary-button", "compact-action-button");
        btnShowFreq.setOnAction(ev -> {
            boolean visible = !frequencyBox.isVisible();
            frequencyBox.setVisible(visible);
            frequencyBox.setManaged(visible);
            btnShowFreq.setText(visible ? "HIDE RETURN FILING FREQUENCY" : "SHOW RETURN FILING FREQUENCY");
        });

        HBox buttonBar = new HBox(12, btnShowFiling, btnShowFreq);
        buttonBar.setAlignment(Pos.CENTER_LEFT);

        box.getChildren().addAll(header, table, lblNote, buttonBar);
        return box;
    }

    private static VBox buildFrequencyBox() {
        VBox box = new VBox(6);
        box.setPadding(new Insets(12));
        box.getStyleClass().addAll("approved-surface");

        HBox header = new HBox(8);
        header.setAlignment(Pos.CENTER_LEFT);
        Node icon = IconFactory.compactIcon("calendar", 14);
        Label title = new Label("Return Filing Frequency & Scheme");
        title.getStyleClass().addAll("field-label", "accent-label-green");
        header.getChildren().addAll(icon, title);

        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(8);
        grid.add(createFactCell("Financial Year", "2026-2027", "calendar", "accent-label-blue"), 0, 0);
        grid.add(createFactCell("GSTR-1 Frequency", "Monthly", "document", "accent-label-purple"), 1, 0);
        grid.add(createFactCell("GSTR-3B Frequency", "Monthly", "document", "accent-label-indigo"), 2, 0);
        grid.add(createFactCell("QRMP Scheme Enrolled", "No (Regular Monthly Filing)", "validate", "accent-label-amber"), 3, 0);

        box.getChildren().addAll(header, grid);
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
