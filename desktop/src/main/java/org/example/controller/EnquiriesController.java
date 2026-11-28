package org.example.controller;

import javafx.beans.property.SimpleStringProperty;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.example.api.enquiry.EnquiryApiClient;
import org.example.navigation.NavigationManager;
import org.example.navigation.ScreenLifecycle;
import org.example.service.PermissionService;
import org.example.util.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Website Enquiries & Leads register with detail drawer, status updates, and 1-click Quotation conversion. */
public final class EnquiriesController implements ScreenLifecycle {
    @FXML private StackPane pageIcon, searchIconBox, kpiTotalIcon, kpiNewIcon, kpiReviewIcon, kpiQuotedIcon, kpiWonIcon;
    @FXML private TextField txtSearch;
    @FXML private ComboBox<String> cmbType, cmbStatus, cmbEditStatus, cmbEditPriority;
    @FXML private Button btnRefresh, btnReset, btnPrevPage, btnNextPage, btnSaveStatus, btnCreateQuotation;
    @FXML private Label lblKpiTotal, lblKpiBreakdown, lblKpiNew, lblKpiReview, lblKpiQuoted, lblKpiWon, lblKpiRating, lblFilteredSummary;
    @FXML private SplitPane mainSplit;
    @FXML private TableView<EnquiryTableRow> table;
    @FXML private TableColumn<EnquiryTableRow, String> colNo, colDate, colType, colClient, colPhone, colDetails, colSource, colStatus, colQuotationNo;
    @FXML private TableColumn<EnquiryTableRow, Void> colActions;
    @FXML private VBox detailDrawer;
    @FXML private Label lblDetailNo, lblDetailDate, lblDetailType, lblDetailClient, lblDetailContact, lblDetailSpecs, lblDetailQuotation;
    @FXML private TextArea txtDetailMessage, txtInternalNotes;

    private final EnquiryApiClient api = new EnquiryApiClient();
    private final FxDebouncer searchDebouncer = new FxDebouncer(java.time.Duration.ofMillis(280));
    private final RegisterPageState pageState = new RegisterPageState();
    private static final int PAGE_SIZE = 25;

    private EnquiryApiClient.EnquiryRow selected;

    @FXML
    public void initialize() {
        applyIcons();
        RegisterUiSupport.configureHeaderSearch(txtSearch, searchIconBox, "Search ref no, client, company, phone or product...");
        cmbType.getItems().setAll("All", "CONTACT_RFQ", "PRODUCT_RFQ", "FEEDBACK");
        cmbType.setValue("All");
        cmbStatus.getItems().setAll("All", "NEW", "IN_REVIEW", "QUOTED", "WON", "LOST", "SPAM");
        cmbStatus.setValue("All");
        cmbEditStatus.getItems().setAll("NEW", "IN_REVIEW", "QUOTED", "WON", "LOST", "SPAM");
        cmbEditPriority.getItems().setAll("LOW", "NORMAL", "HIGH", "URGENT");

        cmbType.valueProperty().addListener((o, a, b) -> { pageState.reset(); loadPage(); });
        cmbStatus.valueProperty().addListener((o, a, b) -> { pageState.reset(); loadPage(); });
        txtSearch.textProperty().addListener((o, a, b) -> { pageState.reset(); searchDebouncer.submit(this::loadPage); });

        configureTable();
        if (detailDrawer != null) {
            detailDrawer.setMinWidth(400);
            detailDrawer.setPrefWidth(430);
            detailDrawer.setMaxWidth(470);
            RegisterUiSupport.hideDrawer(detailDrawer, mainSplit, table);
        }
        OperationalUiSupport.installEscapeClose(mainSplit, () -> detailDrawer != null && detailDrawer.isVisible(), this::closeDetails);

        boolean canCreateQuote = PermissionService.allowed("QUOTATION.CREATE");
        if (btnCreateQuotation != null) {
            btnCreateQuotation.setDisable(!canCreateQuote);
        }
        loadPage();
        consumeDeepLinkIfPresent();
    }

    @Override
    public void onScreenShown(boolean reused) {
        if (reused && ScreenRefreshPolicy.shouldRefresh("enquiries", ScreenRefreshPolicy.Mode.WHEN_STALE, java.time.Duration.ofSeconds(30))) {
            loadPage();
        }
        consumeDeepLinkIfPresent();
    }

    private void consumeDeepLinkIfPresent() {
        LinkedRecordContext.Target target = LinkedRecordContext.consume("ENQUIRY");
        if (target == null) return;
        Long id = target.recordId() == null ? null : target.recordId().longValue();
        String ref = target.documentNo() == null ? "" : target.documentNo().trim();
        UiTaskExecutor.submitLatest("enquiry-deeplink-open", () -> {
            if (id != null && id > 0) return api.detail(id);
            if (!ref.isBlank()) return api.byNumber(ref);
            return null;
        }, detail -> {
            if (detail != null && detail.enquiry() != null) {
                showDetails(detail.enquiry());
            }
        }, err -> System.err.println("Enquiry deep link failed: " + err.getMessage()));
    }

    private void applyIcons() {
        if (pageIcon != null) pageIcon.getChildren().setAll(IconFactory.icon("communication", 24));
        if (kpiTotalIcon != null) kpiTotalIcon.getChildren().setAll(IconFactory.icon("inbox", 20));
        if (kpiNewIcon != null) kpiNewIcon.getChildren().setAll(IconFactory.icon("notification", 20));
        if (kpiReviewIcon != null) kpiReviewIcon.getChildren().setAll(IconFactory.icon("view", 20));
        if (kpiQuotedIcon != null) kpiQuotedIcon.getChildren().setAll(IconFactory.icon("quotation", 20));
        if (kpiWonIcon != null) kpiWonIcon.getChildren().setAll(IconFactory.icon("complete", 20));
        if (btnRefresh != null) UiActionIcons.apply(btnRefresh, "refresh", "Refresh enquiries");
        if (btnReset != null) UiActionIcons.apply(btnReset, "reset", "Reset filters");
        if (btnSaveStatus != null) UiActionIcons.apply(btnSaveStatus, "save", "Save status and notes");
        if (btnCreateQuotation != null) UiActionIcons.apply(btnCreateQuotation, "quotation", "Create quotation from enquiry");
    }

    private void configureTable() {
        colNo.setCellValueFactory(v -> v.getValue().enquiryNo);
        colDate.setCellValueFactory(v -> v.getValue().dateDisplay);
        colType.setCellValueFactory(v -> v.getValue().type);
        colClient.setCellValueFactory(v -> v.getValue().client);
        colPhone.setCellValueFactory(v -> v.getValue().phone);
        colDetails.setCellValueFactory(v -> v.getValue().details);
        colSource.setCellValueFactory(v -> v.getValue().source);
        colStatus.setCellValueFactory(v -> v.getValue().status);
        colQuotationNo.setCellValueFactory(v -> v.getValue().quotationNo);

        colNo.setCellFactory(c -> styledCell("erp-table-value-colour-blue"));
        colClient.setCellFactory(c -> styledCell("erp-table-value-colour-indigo"));
        colStatus.setCellFactory(c -> statusCell());
        colType.setCellFactory(c -> typeCell());

        colActions.setCellFactory(col -> new TableCell<>() {
            private final Button btnView = new Button();
            private final HBox box = new HBox(6, btnView);
            {
                box.setAlignment(Pos.CENTER);
                UiActionIcons.applyLabeledTableAction(btnView, "View", "view", "Inspect enquiry details and convert to quotation");
                btnView.setOnAction(e -> {
                    EnquiryTableRow row = getTableRow() == null ? null : getTableRow().getItem();
                    if (row != null) showDetails(row.dto);
                });
            }
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : box);
            }
        });

        table.setRowFactory(tv -> {
            TableRow<EnquiryTableRow> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY && !row.isEmpty()
                        && !RegisterUiSupport.isInteractiveTableTarget(e.getPickResult().getIntersectedNode(), row)) {
                    table.getSelectionModel().select(row.getItem());
                    showDetails(row.getItem().dto);
                }
            });
            return row;
        });

        IconFactory.applyTableHeaderIcon(colNo, "reference");
        IconFactory.applyTableHeaderIcon(colDate, "calendar");
        IconFactory.applyTableHeaderIcon(colType, "category");
        IconFactory.applyTableHeaderIcon(colClient, "customer");
        IconFactory.applyTableHeaderIcon(colPhone, "phone");
        IconFactory.applyTableHeaderIcon(colDetails, "notes");
        IconFactory.applyTableHeaderIcon(colSource, "source");
        IconFactory.applyTableHeaderIcon(colStatus, "document");
        IconFactory.applyTableHeaderIcon(colQuotationNo, "quotation");
        IconFactory.applyTableHeaderIcon(colActions, "actions");

        DynamicTableLayoutManager.install(table);
        ProfessionalUiEnhancer.refreshTableDecorations(table);
    }

    private TableCell<EnquiryTableRow, String> styledCell(String styleClass) {
        return new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null || item.isBlank()) {
                    setText(null);
                    getStyleClass().remove(styleClass);
                } else {
                    setText(item);
                    if (!getStyleClass().contains(styleClass)) getStyleClass().add(styleClass);
                }
            }
        };
    }

    private TableCell<EnquiryTableRow, String> statusCell() {
        return new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("pill-success", "pill-warning", "pill-danger", "pill-neutral");
                if (empty || item == null || item.isBlank()) {
                    setText(null);
                    return;
                }
                setText(item);
                String up = item.toUpperCase(Locale.ROOT);
                if ("QUOTED".equals(up) || "WON".equals(up)) getStyleClass().add("pill-success");
                else if ("NEW".equals(up) || "IN_REVIEW".equals(up)) getStyleClass().add("pill-warning");
                else if ("SPAM".equals(up) || "LOST".equals(up)) getStyleClass().add("pill-danger");
                else getStyleClass().add("pill-neutral");
            }
        };
    }

    private TableCell<EnquiryTableRow, String> typeCell() {
        return new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("pill-success", "pill-warning", "pill-danger", "pill-neutral");
                if (empty || item == null || item.isBlank()) {
                    setText(null);
                    return;
                }
                setText(item.replace('_', ' '));
                getStyleClass().add("FEEDBACK".equalsIgnoreCase(item) ? "pill-neutral" : "pill-warning");
            }
        };
    }

    @FXML
    public void refresh() {
        loadPage();
    }

    @FXML
    private void resetFilters() {
        txtSearch.clear();
        cmbType.setValue("All");
        cmbStatus.setValue("All");
        pageState.reset();
        loadPage();
    }

    @FXML
    private void previousPage() {
        if (pageState.previous()) loadPage();
    }

    @FXML
    private void nextPage() {
        if (pageState.next()) loadPage();
    }

    private void loadPage() {
        String q = safe(txtSearch.getText()).trim();
        String type = safe(cmbType.getValue());
        String status = safe(cmbStatus.getValue());
        int pageIdx = pageState.currentPage();

        UiTaskExecutor.submitLatest("enquiries-page-load", () -> api.page(pageIdx, PAGE_SIZE, q, type, status), res -> {
            ScreenRefreshPolicy.markRefreshed("enquiries");
            List<EnquiryTableRow> rows = res.rows() == null ? List.of() : res.rows().stream().map(EnquiryTableRow::new).toList();
            table.getItems().setAll(rows);
            pageState.apply(res.page(), res.totalPages(), res.totalRows());
            updateMetrics(res.metrics(), res.totalRows());
            RegisterUiSupport.updatePageNavigation(pageState, btnPrevPage, btnNextPage);
            if (lblFilteredSummary != null) {
                lblFilteredSummary.setText(pageState.rangeWithPageText(PAGE_SIZE, rows.size(), "enquiries"));
            }
        }, err -> AppDialogService.error(table, "Load Failed", "Could not load website enquiries", safe(err.getMessage())));
    }

    private void updateMetrics(EnquiryApiClient.Metrics m, long totalFiltered) {
        if (m == null) return;
        lblKpiTotal.setText(String.valueOf(m.totalCount()));
        lblKpiBreakdown.setText(m.rfqCount() + " RFQ / " + m.feedbackCount() + " Feedback");
        lblKpiNew.setText(String.valueOf(m.newCount()));
        lblKpiReview.setText(String.valueOf(m.inReviewCount()));
        lblKpiQuoted.setText(String.valueOf(m.quotedCount()));
        lblKpiWon.setText(String.valueOf(m.wonCount()));
        lblKpiRating.setText(m.averageRating() > 0 ? String.format(Locale.ENGLISH, "Avg Rating: %.1f / 5", m.averageRating()) : "Avg Rating: -");
    }

    private void showDetails(EnquiryApiClient.EnquiryRow e) {
        if (e == null) return;
        selected = e;
        lblDetailNo.setText(safe(e.enquiryNo()));
        lblDetailDate.setText(formatTimestamp(e.createdAt()));
        lblDetailType.setText(safe(e.enquiryType()).replace('_', ' '));
        String title = safe(e.companyName()).isBlank() ? safe(e.fullName()) : e.companyName() + " • " + e.fullName();
        lblDetailClient.setText(title);

        String contact = String.join("  |  ",
                List.of(safe(e.phone()), safe(e.email()), safe(e.cityState())).stream().filter(s -> !s.isBlank()).toList());
        lblDetailContact.setText(contact.isBlank() ? "No contact details provided" : contact);

        String specs = String.join("  |  ",
                List.of(safe(e.productSeries()), safe(e.npsSize()), safe(e.pressureClass()), safe(e.bodyMaterial()), safe(e.quantityText()))
                        .stream().filter(s -> !s.isBlank()).toList());
        lblDetailSpecs.setText(specs.isBlank() ? (e.rating() != null ? "Rating: " + e.rating() + " / 5" : "General Inquiry") : specs);

        txtDetailMessage.setText(safe(e.message()).isBlank() ? "(No detailed message)" : e.message());
        cmbEditStatus.setValue(safe(e.status()).isBlank() ? "NEW" : e.status());
        cmbEditPriority.setValue(safe(e.priority()).isBlank() ? "NORMAL" : e.priority());
        lblDetailQuotation.setText(safe(e.convertedQuotationNo()).isBlank() ? "Not converted yet" : e.convertedQuotationNo());
        txtInternalNotes.setText(safe(e.internalNotes()));

        RegisterUiSupport.showDrawer(detailDrawer, mainSplit, 0.64);
    }

    @FXML
    private void closeDetails() {
        RegisterUiSupport.hideDrawer(detailDrawer, mainSplit, table);
    }

    @FXML
    private void saveStatusUpdate() {
        if (selected == null) return;
        EnquiryApiClient.EnquiryRow current = selected;
        var req = new EnquiryApiClient.StatusUpdateRequest(
                cmbEditStatus.getValue(),
                cmbEditPriority.getValue(),
                txtInternalNotes.getText(),
                "Updated in Desktop Enquiries",
                current.assignedUserId(),
                current.rowVersion());
        UiTaskExecutor.submitAction("enquiry-status-save", () -> api.updateStatus(current.id(), req), updated -> {
            showDetails(updated);
            loadPage();
            AppDialogService.success(table, "Enquiry Updated", updated.enquiryNo() + " saved.");
        }, err -> AppDialogService.error(table, "Update Failed", "Could not update enquiry", safe(err.getMessage())));
    }

    @FXML
    private void convertToQuotation() {
        if (selected == null) return;
        EnquiryApiClient.EnquiryRow current = selected;
        if (!safe(current.convertedQuotationNo()).isBlank()) {
            ButtonType choice = new OwnedAlert(
                    Alert.AlertType.CONFIRMATION,
                    "This enquiry is already linked to Quotation " + current.convertedQuotationNo() + ". Open existing Quotation?",
                    ButtonType.YES, ButtonType.NO).showAndWait().orElse(ButtonType.NO);
            if (choice == ButtonType.YES && current.convertedQuotationId() != null) {
                QuotationEditorContext.open(current.convertedQuotationId());
                NavigationManager.getInstance().loadPage("/fxml/pages/QuotationEditor.fxml");
            }
            return;
        }
        UiTaskExecutor.submitAction("enquiry-prepare-quotation", () -> api.prepareQuotation(current.id()), prep -> {
            QuotationEditorContext.openFromEnquiry(
                    prep.enquiryId(), prep.customerId(), prep.customerName(), prep.suggestedSource(), prep.suggestedRemarks());
            NavigationManager.getInstance().loadPage("/fxml/pages/QuotationEditor.fxml");
        }, err -> AppDialogService.error(table, "Conversion Failed", "Could not prepare Quotation", safe(err.getMessage())));
    }

    private static String formatTimestamp(String raw) {
        if (raw == null || raw.isBlank()) return "";
        try {
            Instant instant = Instant.parse(raw.trim());
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern(BusinessClock.datePattern() + " hh:mm a").withZone(BusinessClock.zone());
            return fmt.format(instant);
        } catch (Exception ex) {
            try {
                LocalDate d = LocalDate.parse(raw.substring(0, Math.min(10, raw.length())));
                return BusinessClock.formatDate(d);
            } catch (Exception ignored) {
                return raw;
            }
        }
    }

    private static String safe(String v) { return v == null ? "" : v; }

    public static final class EnquiryTableRow {
        final EnquiryApiClient.EnquiryRow dto;
        final SimpleStringProperty enquiryNo, dateDisplay, type, client, phone, details, source, status, quotationNo;

        EnquiryTableRow(EnquiryApiClient.EnquiryRow d) {
            this.dto = d;
            this.enquiryNo = new SimpleStringProperty(safe(d.enquiryNo()));
            this.dateDisplay = new SimpleStringProperty(formatTimestamp(d.createdAt()));
            this.type = new SimpleStringProperty(safe(d.enquiryType()));
            String who = safe(d.companyName()).isBlank() ? safe(d.fullName()) : d.companyName() + " (" + d.fullName() + ")";
            this.client = new SimpleStringProperty(who);
            String contact = safe(d.phone()).isBlank() ? safe(d.email()) : d.phone();
            this.phone = new SimpleStringProperty(contact);
            String detailSummary = !safe(d.productSeries()).isBlank()
                    ? d.productSeries() + (safe(d.npsSize()).isBlank() ? "" : " • " + d.npsSize())
                    : (d.rating() != null ? d.rating() + "/5 Rating: " + safe(d.message()) : safe(d.message()));
            this.details = new SimpleStringProperty(detailSummary);
            this.source = new SimpleStringProperty(safe(d.sourcePage()));
            this.status = new SimpleStringProperty(safe(d.status()));
            this.quotationNo = new SimpleStringProperty(safe(d.convertedQuotationNo()));
        }
    }
}
