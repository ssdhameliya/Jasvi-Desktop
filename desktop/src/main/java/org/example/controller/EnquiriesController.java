package org.example.controller;

import javafx.beans.property.SimpleStringProperty;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import org.example.api.enquiry.EnquiryApiClient;
import org.example.navigation.NavigationManager;
import org.example.navigation.ScreenLifecycle;
import org.example.service.PermissionService;
import org.example.util.*;

import java.awt.Desktop;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Website Enquiries & Client Feedback 2-tab register with modal inspect popup and standardized actions menu. */
public final class EnquiriesController implements ScreenLifecycle {
    @FXML private StackPane pageIcon, searchIconBox, kpiTotalIcon, kpiNewIcon, kpiReviewIcon, kpiQuotedIcon, kpiWonIcon;
    @FXML private TextField txtSearch;
    @FXML private TabPane tabPaneEnquiries;
    @FXML private Tab tabEnquiries, tabFeedback;

    // Tab 1: Enquiries & RFQs
    @FXML private ComboBox<String> cmbType, cmbStatus;
    @FXML private Button btnRefresh, btnReset, btnPrevPage, btnNextPage;
    @FXML private Label lblKpiTotal, lblKpiBreakdown, lblKpiNew, lblKpiReview, lblKpiQuoted, lblKpiWon, lblKpiRating, lblFilteredSummary;
    @FXML private TableView<EnquiryTableRow> table;
    @FXML private TableColumn<EnquiryTableRow, String> colNo, colDate, colType, colClient, colPhone, colDetails, colSource, colStatus, colQuotationNo;
    @FXML private TableColumn<EnquiryTableRow, Void> colActions;

    // Tab 2: Client Feedback & Ratings
    @FXML private ComboBox<String> cmbFeedbackStatus;
    @FXML private Button btnFeedbackReset, btnFeedbackPrevPage, btnFeedbackNextPage;
    @FXML private Label lblFeedbackSummary;
    @FXML private TableView<EnquiryTableRow> feedbackTable;
    @FXML private TableColumn<EnquiryTableRow, String> colFbNo, colFbDate, colFbClient, colFbEmail, colFbRating, colFbRemarks, colFbSource, colFbStatus;
    @FXML private TableColumn<EnquiryTableRow, Void> colFbActions;

    private final EnquiryApiClient api = new EnquiryApiClient();
    private final FxDebouncer searchDebouncer = new FxDebouncer(java.time.Duration.ofMillis(280));
    private final RegisterPageState enquiryPageState = new RegisterPageState();
    private final RegisterPageState feedbackPageState = new RegisterPageState();
    private static final int PAGE_SIZE = 25;

    @FXML
    public void initialize() {
        applyIcons();
        RegisterUiSupport.configureHeaderSearch(txtSearch, searchIconBox, "Search ref no, client, company, phone or product...");
        cmbType.getItems().setAll("All RFQs", "CONTACT_RFQ", "PRODUCT_RFQ", "CATALOG_REQUEST");
        cmbType.setValue("All RFQs");
        cmbStatus.getItems().setAll("All", "NEW", "IN_REVIEW", "QUOTED", "WON", "LOST", "SPAM");
        cmbStatus.setValue("All");

        cmbFeedbackStatus.getItems().setAll("All", "NEW", "IN_REVIEW", "WON", "SPAM");
        cmbFeedbackStatus.setValue("All");

        cmbType.valueProperty().addListener((o, a, b) -> { enquiryPageState.reset(); loadEnquiryPage(); });
        cmbStatus.valueProperty().addListener((o, a, b) -> { enquiryPageState.reset(); loadEnquiryPage(); });
        cmbFeedbackStatus.valueProperty().addListener((o, a, b) -> { feedbackPageState.reset(); loadFeedbackPage(); });
        txtSearch.textProperty().addListener((o, a, b) -> {
            enquiryPageState.reset();
            feedbackPageState.reset();
            searchDebouncer.submit(this::loadAllTabs);
        });

        configureEnquiryTable();
        configureFeedbackTable();
        loadAllTabs();
        consumeDeepLinkIfPresent();
    }

    @Override
    public void onScreenShown(boolean reused) {
        if (reused && ScreenRefreshPolicy.shouldRefresh("enquiries", ScreenRefreshPolicy.Mode.WHEN_STALE, java.time.Duration.ofSeconds(30))) {
            loadAllTabs();
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
                EnquiryApiClient.EnquiryRow row = detail.enquiry();
                if (tabPaneEnquiries != null) {
                    tabPaneEnquiries.getSelectionModel().select("FEEDBACK".equalsIgnoreCase(row.enquiryType()) ? tabFeedback : tabEnquiries);
                }
                showEnquiryDetailsDialog(row);
            }
        }, err -> System.err.println("Enquiry deep link failed: " + err.getMessage()));
    }

    private void applyIcons() {
        if (pageIcon != null) pageIcon.getChildren().setAll(IconFactory.icon("communication", 22));
        if (kpiTotalIcon != null) kpiTotalIcon.getChildren().setAll(IconFactory.icon("document", 20));
        if (kpiNewIcon != null) kpiNewIcon.getChildren().setAll(IconFactory.icon("notification", 20));
        if (kpiReviewIcon != null) kpiReviewIcon.getChildren().setAll(IconFactory.icon("view", 20));
        if (kpiQuotedIcon != null) kpiQuotedIcon.getChildren().setAll(IconFactory.icon("quotation", 20));
        if (kpiWonIcon != null) kpiWonIcon.getChildren().setAll(IconFactory.icon("complete", 20));
        if (btnRefresh != null) UiActionIcons.apply(btnRefresh, "refresh", "Refresh enquiries and feedback");
        if (btnReset != null) UiActionIcons.apply(btnReset, "reset", "Reset enquiry filters");
        if (btnFeedbackReset != null) UiActionIcons.apply(btnFeedbackReset, "reset", "Reset feedback filters");
        if (tabEnquiries != null) tabEnquiries.setGraphic(IconFactory.compactIcon("quotation", 14));
        if (tabFeedback != null) tabFeedback.setGraphic(IconFactory.compactIcon("favorite", 14));
    }

    private void configureEnquiryTable() {
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

        colActions.setCellFactory(col -> buildActionCell(true));

        table.setRowFactory(tv -> {
            TableRow<EnquiryTableRow> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY && !row.isEmpty()
                        && !RegisterUiSupport.isInteractiveTableTarget(e.getPickResult().getIntersectedNode(), row)) {
                    table.getSelectionModel().select(row.getItem());
                    showEnquiryDetailsDialog(row.getItem().dto);
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

    private void configureFeedbackTable() {
        colFbNo.setCellValueFactory(v -> v.getValue().enquiryNo);
        colFbDate.setCellValueFactory(v -> v.getValue().dateDisplay);
        colFbClient.setCellValueFactory(v -> v.getValue().client);
        colFbEmail.setCellValueFactory(v -> v.getValue().email);
        colFbRating.setCellValueFactory(v -> v.getValue().ratingDisplay);
        colFbRemarks.setCellValueFactory(v -> v.getValue().feedbackNotes);
        colFbSource.setCellValueFactory(v -> v.getValue().source);
        colFbStatus.setCellValueFactory(v -> v.getValue().status);

        colFbNo.setCellFactory(c -> styledCell("erp-table-value-colour-blue"));
        colFbClient.setCellFactory(c -> styledCell("erp-table-value-colour-indigo"));
        colFbRating.setCellFactory(c -> ratingCell());
        colFbStatus.setCellFactory(c -> statusCell());

        colFbActions.setCellFactory(col -> buildActionCell(false));

        feedbackTable.setRowFactory(tv -> {
            TableRow<EnquiryTableRow> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY && !row.isEmpty()
                        && !RegisterUiSupport.isInteractiveTableTarget(e.getPickResult().getIntersectedNode(), row)) {
                    feedbackTable.getSelectionModel().select(row.getItem());
                    showEnquiryDetailsDialog(row.getItem().dto);
                }
            });
            return row;
        });

        IconFactory.applyTableHeaderIcon(colFbNo, "reference");
        IconFactory.applyTableHeaderIcon(colFbDate, "calendar");
        IconFactory.applyTableHeaderIcon(colFbClient, "customer");
        IconFactory.applyTableHeaderIcon(colFbEmail, "email");
        IconFactory.applyTableHeaderIcon(colFbRating, "favorite");
        IconFactory.applyTableHeaderIcon(colFbRemarks, "notes");
        IconFactory.applyTableHeaderIcon(colFbSource, "source");
        IconFactory.applyTableHeaderIcon(colFbStatus, "status");
        IconFactory.applyTableHeaderIcon(colFbActions, "actions");

        DynamicTableLayoutManager.install(feedbackTable);
        ProfessionalUiEnhancer.refreshTableDecorations(feedbackTable);
    }

    private TableCell<EnquiryTableRow, Void> buildActionCell(boolean includeQuotation) {
        return new TableCell<>() {
            private final MenuButton menu = createActionMenu();
            {
                menu.getItems().add(menuItem("View Details", "view", () -> {
                    EnquiryTableRow row = getTableRow() == null ? null : getTableRow().getItem();
                    if (row != null) showEnquiryDetailsDialog(row.dto);
                }));
                if (includeQuotation) {
                    menu.getItems().add(menuItem("Convert to Quotation", "quotation", () -> {
                        EnquiryTableRow row = getTableRow() == null ? null : getTableRow().getItem();
                        if (row != null) convertToQuotation(row.dto);
                    }));
                }
                menu.getItems().addAll(
                        menuItem("Update Status", "edit", () -> {
                            EnquiryTableRow row = getTableRow() == null ? null : getTableRow().getItem();
                            if (row != null) openQuickStatusDialog(row.dto);
                        }),
                        new SeparatorMenuItem(),
                        menuItem("Chat on WhatsApp", "whatsapp", () -> {
                            EnquiryTableRow row = getTableRow() == null ? null : getTableRow().getItem();
                            if (row != null) openWhatsApp(row.dto);
                        }),
                        menuItem("Send Email", "email", () -> {
                            EnquiryTableRow row = getTableRow() == null ? null : getTableRow().getItem();
                            if (row != null) openEmail(row.dto);
                        }),
                        menuItem("Copy Reference", "copy", () -> {
                            EnquiryTableRow row = getTableRow() == null ? null : getTableRow().getItem();
                            if (row != null) copyReference(row.dto);
                        })
                );
            }
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : menu);
            }
        };
    }

    private MenuButton createActionMenu() {
        MenuButton m = new MenuButton("Actions");
        m.setGraphic(IconFactory.compactIcon("actions", 14));
        m.setContentDisplay(ContentDisplay.LEFT);
        m.setGraphicTextGap(6);
        m.getStyleClass().setAll("table-action-menu", "approved-row-action");
        IconFactory.decorateActionMenu(m);
        return m;
    }

    private MenuItem menuItem(String text, String icon, Runnable action) {
        MenuItem item = new MenuItem(text, IconFactory.compactIcon(icon, 14));
        item.setOnAction(e -> action.run());
        return item;
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

    private TableCell<EnquiryTableRow, String> ratingCell() {
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
                if (item.startsWith("5") || item.startsWith("4")) getStyleClass().add("pill-success");
                else if (item.startsWith("3")) getStyleClass().add("pill-warning");
                else getStyleClass().add("pill-danger");
            }
        };
    }

    @FXML
    public void refresh() {
        loadAllTabs();
    }

    @FXML
    private void resetFilters() {
        txtSearch.clear();
        cmbType.setValue("All RFQs");
        cmbStatus.setValue("All");
        enquiryPageState.reset();
        loadEnquiryPage();
    }

    @FXML
    private void resetFeedbackFilters() {
        txtSearch.clear();
        cmbFeedbackStatus.setValue("All");
        feedbackPageState.reset();
        loadFeedbackPage();
    }

    @FXML
    private void previousPage() {
        if (enquiryPageState.previous()) loadEnquiryPage();
    }

    @FXML
    private void nextPage() {
        if (enquiryPageState.next()) loadEnquiryPage();
    }

    @FXML
    private void previousFeedbackPage() {
        if (feedbackPageState.previous()) loadFeedbackPage();
    }

    @FXML
    private void nextFeedbackPage() {
        if (feedbackPageState.next()) loadFeedbackPage();
    }

    private void loadAllTabs() {
        loadEnquiryPage();
        loadFeedbackPage();
    }

    private void loadEnquiryPage() {
        String q = safe(txtSearch.getText()).trim();
        String rawType = safe(cmbType.getValue());
        String type = (rawType.isBlank() || "All RFQs".equalsIgnoreCase(rawType)) ? "RFQ_ALL" : rawType;
        String status = safe(cmbStatus.getValue());
        int pageIdx = enquiryPageState.currentPage();

        UiTaskExecutor.submitLatest("enquiries-page-load", () -> api.page(pageIdx, PAGE_SIZE, q, type, status), res -> {
            ScreenRefreshPolicy.markRefreshed("enquiries");
            List<EnquiryTableRow> rows = res.rows() == null ? List.of() : res.rows().stream().map(EnquiryTableRow::new).toList();
            table.getItems().setAll(rows);
            enquiryPageState.apply(res.page(), res.totalPages(), res.totalRows());
            updateMetrics(res.metrics());
            RegisterUiSupport.updatePageNavigation(enquiryPageState, btnPrevPage, btnNextPage);
            if (lblFilteredSummary != null) {
                lblFilteredSummary.setText(enquiryPageState.rangeWithPageText(PAGE_SIZE, rows.size(), "enquiries"));
            }
        }, err -> AppDialogService.error(table, "Load Failed", "Could not load website enquiries", safe(err.getMessage())));
    }

    private void loadFeedbackPage() {
        String q = safe(txtSearch.getText()).trim();
        String status = safe(cmbFeedbackStatus.getValue());
        int pageIdx = feedbackPageState.currentPage();

        UiTaskExecutor.submitLatest("enquiries-feedback-load", () -> api.page(pageIdx, PAGE_SIZE, q, "FEEDBACK", status), res -> {
            List<EnquiryTableRow> rows = res.rows() == null ? List.of() : res.rows().stream().map(EnquiryTableRow::new).toList();
            feedbackTable.getItems().setAll(rows);
            feedbackPageState.apply(res.page(), res.totalPages(), res.totalRows());
            RegisterUiSupport.updatePageNavigation(feedbackPageState, btnFeedbackPrevPage, btnFeedbackNextPage);
            if (lblFeedbackSummary != null) {
                lblFeedbackSummary.setText(feedbackPageState.rangeWithPageText(PAGE_SIZE, rows.size(), "feedback entries"));
            }
        }, err -> AppDialogService.error(feedbackTable, "Load Failed", "Could not load client feedback", safe(err.getMessage())));
    }

    private void updateMetrics(EnquiryApiClient.Metrics m) {
        if (m == null) return;
        lblKpiTotal.setText(String.valueOf(m.totalCount()));
        lblKpiBreakdown.setText(m.rfqCount() + " RFQ / " + m.feedbackCount() + " Feedback");
        lblKpiNew.setText(String.valueOf(m.newCount()));
        lblKpiReview.setText(String.valueOf(m.inReviewCount()));
        lblKpiQuoted.setText(String.valueOf(m.quotedCount()));
        lblKpiWon.setText(String.valueOf(m.wonCount()));
        lblKpiRating.setText(m.averageRating() > 0 ? String.format(Locale.ENGLISH, "Avg Rating: %.1f / 5", m.averageRating()) : "Avg Rating: -");
    }

    /** Rule 12 Content-Adaptive Draggable Modal Dialog for Inspecting & Managing Enquiry or Feedback. */
    public void showEnquiryDetailsDialog(EnquiryApiClient.EnquiryRow e) {
        if (e == null) return;
        boolean isFeedback = "FEEDBACK".equalsIgnoreCase(e.enquiryType());

        VBox content = new VBox(14);
        content.setPadding(new Insets(14));
        content.setMinWidth(640);
        content.setPrefWidth(700);

        VBox custCard = new VBox(8);
        custCard.getStyleClass().setAll("card-surface", "p-4", "rounded-xl");
        Label lblCustHead = new Label("Client Profile & Contact Information");
        lblCustHead.getStyleClass().setAll("font-bold", "text-sm", "erp-label-title");
        lblCustHead.setGraphic(IconFactory.compactIcon("customer", 16));

        GridPane custGrid = new GridPane();
        custGrid.setHgap(16);
        custGrid.setVgap(8);
        custGrid.add(createFieldBox("Client Name", firstNonBlank(e.fullName(), "-"), "customer"), 0, 0);
        custGrid.add(createFieldBox("Company / Industry", firstNonBlank(e.companyName(), "-"), "supplier"), 1, 0);
        custGrid.add(createFieldBox("Mobile / Phone", firstNonBlank(e.phone(), "-"), "phone"), 0, 1);
        custGrid.add(createFieldBox("Email Address", firstNonBlank(e.email(), "-"), "email"), 1, 1);
        custGrid.add(createFieldBox("Project Location", firstNonBlank(e.cityState(), "-"), "location"), 0, 2);
        custGrid.add(createFieldBox("Submission Type", safe(e.enquiryType()).replace('_', ' '), "category"), 1, 2);
        custCard.getChildren().addAll(lblCustHead, custGrid);

        VBox specCard = new VBox(8);
        specCard.getStyleClass().setAll("card-surface", "p-4", "rounded-xl");
        Label lblSpecHead = new Label(isFeedback ? "Feedback Rating & Reference" : "Engineering & Equipment Specifications");
        lblSpecHead.getStyleClass().setAll("font-bold", "text-sm", "erp-label-title");
        lblSpecHead.setGraphic(IconFactory.compactIcon(isFeedback ? "favorite" : "settings", 16));

        GridPane specGrid = new GridPane();
        specGrid.setHgap(16);
        specGrid.setVgap(8);
        if (isFeedback) {
            String stars = e.rating() == null ? "-" : e.rating() + " / 5 Stars (" + "★".repeat(Math.max(0, Math.min(5, e.rating()))) + ")";
            specGrid.add(createFieldBox("Client Feedback Rating", stars, "favorite"), 0, 0);
            specGrid.add(createFieldBox("Source Page", firstNonBlank(e.sourcePage(), "contact-feedback"), "source"), 1, 0);
        } else {
            specGrid.add(createFieldBox("Product / Series", firstNonBlank(e.productSeries(), "General Inquiry"), "document"), 0, 0);
            specGrid.add(createFieldBox("Size / NPS", firstNonBlank(e.npsSize(), "-"), "notes"), 1, 0);
            specGrid.add(createFieldBox("Pressure Class", firstNonBlank(e.pressureClass(), "-"), "filter"), 0, 1);
            specGrid.add(createFieldBox("Body Material", firstNonBlank(e.bodyMaterial(), "-"), "master"), 1, 1);
            specGrid.add(createFieldBox("Quantity", firstNonBlank(e.quantityText(), "-"), "number"), 0, 2);
            specGrid.add(createFieldBox("Linked Quotation", firstNonBlank(e.convertedQuotationNo(), "Not converted yet"), "quotation"), 1, 2);
        }
        specCard.getChildren().addAll(lblSpecHead, specGrid);

        VBox reqCard = new VBox(6);
        reqCard.getStyleClass().setAll("card-surface", "p-4", "rounded-xl");
        Label lblReqHead = new Label(isFeedback ? "Client Feedback Message" : "Client Requirements & Notes");
        lblReqHead.getStyleClass().setAll("font-bold", "text-sm", "erp-label-title");
        lblReqHead.setGraphic(IconFactory.compactIcon("notes", 16));
        TextArea txtMsg = new TextArea(safe(e.message()).isBlank() ? "(No detailed message)" : e.message());
        txtMsg.setEditable(false);
        txtMsg.setWrapText(true);
        txtMsg.setPrefRowCount(3);
        txtMsg.getStyleClass().add("approved-input");
        reqCard.getChildren().addAll(lblReqHead, txtMsg);

        VBox statusCard = new VBox(8);
        statusCard.getStyleClass().setAll("card-surface", "p-4", "rounded-xl");
        Label lblStatusHead = new Label("Internal Follow-Up & Workflow Status");
        lblStatusHead.getStyleClass().setAll("font-bold", "text-sm", "erp-label-title");
        lblStatusHead.setGraphic(IconFactory.compactIcon("edit", 16));

        HBox statusRow = new HBox(14);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        ComboBox<String> cmbDlgStatus = new ComboBox<>();
        cmbDlgStatus.getItems().setAll("NEW", "IN_REVIEW", "QUOTED", "WON", "LOST", "SPAM");
        cmbDlgStatus.setValue(safe(e.status()).isBlank() ? "NEW" : e.status());
        cmbDlgStatus.getStyleClass().add("approved-input");

        ComboBox<String> cmbDlgPriority = new ComboBox<>();
        cmbDlgPriority.getItems().setAll("LOW", "NORMAL", "HIGH", "URGENT");
        cmbDlgPriority.setValue(safe(e.priority()).isBlank() ? "NORMAL" : e.priority());
        cmbDlgPriority.getStyleClass().add("approved-input");

        statusRow.getChildren().addAll(new Label("Status:"), cmbDlgStatus, new Label("Priority:"), cmbDlgPriority);

        TextArea txtDlgNotes = new TextArea(safe(e.internalNotes()));
        txtDlgNotes.setWrapText(true);
        txtDlgNotes.setPrefRowCount(2);
        txtDlgNotes.setPromptText("Add internal notes, follow-up remarks, or sizing details...");
        txtDlgNotes.getStyleClass().add("approved-input");

        statusCard.getChildren().addAll(lblStatusHead, statusRow, txtDlgNotes);

        content.getChildren().addAll(custCard, specCard, reqCard, statusCard);

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setMaxHeight(560);
        scroll.getStyleClass().add("erp-transparent-scroll");

        ButtonType btnQuote = new ButtonType("Convert to Quotation", ButtonBar.ButtonData.YES);
        ButtonType btnSave = new ButtonType("Save Status & Notes", ButtonBar.ButtonData.OK_DONE);
        ButtonType btnClose = new ButtonType("Close", ButtonBar.ButtonData.CANCEL_CLOSE);

        String heading = "Reference: " + e.enquiryNo() + "  •  " + formatTimestamp(e.createdAt());
        var res = isFeedback
                ? AppDialogService.workspace(feedbackTable, "information", "Feedback Inspection & Response", heading,
                        "Review customer satisfaction rating, comments, and workflow status.", scroll,
                        720, 600, btnSave, btnClose)
                : AppDialogService.workspace(table, "information", "Enquiry Inspection & Action", heading,
                        "Track website contact, RFQ sizing, and convert to quotation.", scroll,
                        720, 600, btnQuote, btnSave, btnClose);

        if (res.isPresent()) {
            if (!isFeedback && res.get() == btnQuote) {
                saveAndConvert(e, cmbDlgStatus.getValue(), cmbDlgPriority.getValue(), txtDlgNotes.getText());
            } else if (res.get() == btnSave) {
                saveStatusUpdate(e, cmbDlgStatus.getValue(), cmbDlgPriority.getValue(), txtDlgNotes.getText());
            }
        }
    }

    private VBox createFieldBox(String label, String value, String icon) {
        VBox box = new VBox(2);
        HBox lblBox = new HBox(4);
        lblBox.setAlignment(Pos.CENTER_LEFT);
        Label iconLbl = new Label("", IconFactory.compactIcon(icon, 14));
        Label l = new Label(label);
        l.getStyleClass().setAll("text-xs", "font-semibold", "text-muted");
        lblBox.getChildren().addAll(iconLbl, l);

        Label v = new Label(value);
        v.setWrapText(true);
        v.getStyleClass().setAll("text-sm", "font-bold", "erp-table-value-colour-indigo");
        box.getChildren().addAll(lblBox, v);
        return box;
    }

    private void openQuickStatusDialog(EnquiryApiClient.EnquiryRow e) {
        if (e == null) return;
        ComboBox<String> cmb = new ComboBox<>();
        cmb.getItems().setAll("NEW", "IN_REVIEW", "QUOTED", "WON", "LOST", "SPAM");
        cmb.setValue(safe(e.status()).isBlank() ? "NEW" : e.status());
        cmb.getStyleClass().add("approved-input");

        VBox box = new VBox(10, new Label("Select New Status for " + e.enquiryNo() + ":"), cmb);
        box.setPadding(new Insets(10));
        ButtonType btnOk = new ButtonType("Update Status", ButtonBar.ButtonData.OK_DONE);
        ButtonType btnCancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);

        var choice = AppDialogService.workspace(table, "confirmation", "Update Status", e.enquiryNo(), "", box, 400, 200, btnOk, btnCancel);
        if (choice.isPresent() && choice.get() == btnOk && cmb.getValue() != null) {
            saveStatusUpdate(e, cmb.getValue(), e.priority(), e.internalNotes());
        }
    }

    private void saveStatusUpdate(EnquiryApiClient.EnquiryRow current, String status, String priority, String notes) {
        var req = new EnquiryApiClient.StatusUpdateRequest(
                status, priority, notes, "Updated from Desktop Enquiries", current.assignedUserId(), current.rowVersion());
        UiTaskExecutor.submitAction("enquiry-status-save", () -> api.updateStatus(current.id(), req), updated -> {
            loadAllTabs();
            AppDialogService.success(table, "Status Updated", updated.enquiryNo() + " status is now " + updated.status());
        }, err -> AppDialogService.error(table, "Update Failed", "Could not update record status", safe(err.getMessage())));
    }

    private void saveAndConvert(EnquiryApiClient.EnquiryRow current, String status, String priority, String notes) {
        var req = new EnquiryApiClient.StatusUpdateRequest(
                status, priority, notes, "Saved before Quotation conversion", current.assignedUserId(), current.rowVersion());
        UiTaskExecutor.submitAction("enquiry-status-save-pre-convert", () -> api.updateStatus(current.id(), req),
                this::convertToQuotation, err -> convertToQuotation(current));
    }

    private void convertToQuotation(EnquiryApiClient.EnquiryRow current) {
        if (current == null) return;
        if (!PermissionService.allowed("QUOTATION.CREATE")) {
            AppDialogService.error(table, "Permission Denied", "Action Not Allowed", "You do not have permission to create Quotations.");
            return;
        }
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

    private void openWhatsApp(EnquiryApiClient.EnquiryRow e) {
        if (e == null) return;
        String rawPhone = safe(e.phone()).replaceAll("[^0-9]", "");
        if (rawPhone.isBlank()) {
            AppDialogService.warning(table, "No Phone Number", "Missing Phone", "No valid phone number is available for " + e.fullName());
            return;
        }
        if (rawPhone.length() == 10) rawPhone = "91" + rawPhone;
        String text = "Hello " + safe(e.fullName()) + ", thank you for contacting DS Industries regarding " + firstNonBlank(e.productSeries(), "industrial valves") + " (Ref: " + e.enquiryNo() + ").";
        try {
            String uriStr = "https://wa.me/" + rawPhone + "?text=" + URLEncoder.encode(text, StandardCharsets.UTF_8);
            Desktop.getDesktop().browse(new URI(uriStr));
        } catch (Exception ex) {
            AppDialogService.error(table, "Cannot Open WhatsApp", "Browser Launch Failed", ex.getMessage());
        }
    }

    private void openEmail(EnquiryApiClient.EnquiryRow e) {
        if (e == null) return;
        String email = safe(e.email()).trim();
        if (email.isBlank() || !email.contains("@")) {
            AppDialogService.warning(table, "No Email Address", "Missing Email", "No valid email address is available for " + e.fullName());
            return;
        }
        String subject = "FEEDBACK".equalsIgnoreCase(e.enquiryType())
                ? "Thank you for your feedback • DS Industries (" + e.enquiryNo() + ")"
                : "DS Industries Quotation & Technical Reference: " + e.enquiryNo();
        String body = "Dear " + safe(e.fullName()) + ",\n\nThank you for reaching out to DS Industries (Ref: " + e.enquiryNo() + ").\n\n";
        try {
            String mailto = "mailto:" + email + "?subject=" + URLEncoder.encode(subject, StandardCharsets.UTF_8).replace("+", "%20")
                    + "&body=" + URLEncoder.encode(body, StandardCharsets.UTF_8).replace("+", "%20");
            Desktop.getDesktop().browse(new URI(mailto));
        } catch (Exception ex) {
            AppDialogService.error(table, "Cannot Open Email Client", "Mailto Launch Failed", ex.getMessage());
        }
    }

    private void copyReference(EnquiryApiClient.EnquiryRow e) {
        if (e == null || safe(e.enquiryNo()).isBlank()) return;
        ClipboardContent cc = new ClipboardContent();
        cc.putString(e.enquiryNo());
        Clipboard.getSystemClipboard().setContent(cc);
        AppDialogService.success(table, "Copied to Clipboard", "Reference " + e.enquiryNo() + " copied.");
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
    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return "";
    }

    public static final class EnquiryTableRow {
        public final EnquiryApiClient.EnquiryRow dto;
        public final SimpleStringProperty enquiryNo = new SimpleStringProperty();
        public final SimpleStringProperty dateDisplay = new SimpleStringProperty();
        public final SimpleStringProperty type = new SimpleStringProperty();
        public final SimpleStringProperty client = new SimpleStringProperty();
        public final SimpleStringProperty phone = new SimpleStringProperty();
        public final SimpleStringProperty email = new SimpleStringProperty();
        public final SimpleStringProperty details = new SimpleStringProperty();
        public final SimpleStringProperty ratingDisplay = new SimpleStringProperty();
        public final SimpleStringProperty feedbackNotes = new SimpleStringProperty();
        public final SimpleStringProperty source = new SimpleStringProperty();
        public final SimpleStringProperty status = new SimpleStringProperty();
        public final SimpleStringProperty quotationNo = new SimpleStringProperty();

        public EnquiryTableRow(EnquiryApiClient.EnquiryRow dto) {
            this.dto = dto;
            this.enquiryNo.set(safe(dto.enquiryNo()));
            this.dateDisplay.set(formatTimestamp(dto.createdAt()));
            this.type.set(safe(dto.enquiryType()));
            String c = safe(dto.companyName()).isBlank() ? safe(dto.fullName()) : dto.companyName() + " (" + safe(dto.fullName()) + ")";
            this.client.set(c);
            this.phone.set(safe(dto.phone()));
            this.email.set(safe(dto.email()));
            String d = safe(dto.productSeries());
            String specs = String.join(" ", List.of(safe(dto.npsSize()), safe(dto.pressureClass()), safe(dto.bodyMaterial())).stream().filter(s -> !s.isBlank()).toList());
            if (!specs.isBlank()) d = d.isBlank() ? specs : d + " (" + specs + ")";
            this.details.set(d.isBlank() ? safe(dto.message()) : d);
            int stars = dto.rating() == null ? 0 : Math.max(0, Math.min(5, dto.rating()));
            this.ratingDisplay.set(stars > 0 ? stars + "/5 " + "★".repeat(stars) : "-");
            this.feedbackNotes.set(safe(dto.message()));
            this.source.set(safe(dto.sourcePage()));
            this.status.set(safe(dto.status()));
            this.quotationNo.set(safe(dto.convertedQuotationNo()).isBlank() ? "-" : dto.convertedQuotationNo());
        }
    }
}
