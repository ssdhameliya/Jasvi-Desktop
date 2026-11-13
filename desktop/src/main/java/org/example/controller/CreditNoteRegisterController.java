package org.example.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.example.api.ApiRuntime;
import org.example.api.ApiSession;
import org.example.api.returns.ReturnApiClient;
import org.example.config.ConfigManager;
import org.example.navigation.NavigationManager;
import org.example.navigation.ScreenLifecycle;
import org.example.service.NotificationService;
import org.example.service.ReturnWorkflowService;
import org.example.util.*;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;

public class CreditNoteRegisterController implements ScreenLifecycle {

    @FXML private StackPane pageIcon;
    @FXML private StackPane kpiCountIcon, kpiAmountIcon, kpiTaxIcon, kpiStockIcon;
    @FXML private Label kpiTotalCount, kpiTotalAmount, kpiTaxReversed, kpiRestockedCount;

    @FXML private Button btnNewCreditNote, btnRefresh, btnScreenGuidance;
    @FXML private TextField txtSearch;
    @FXML private ComboBox<String> cmbReasonFilter;

    @FXML private TabPane tabPaneRegister;
    @FXML private Tab tabCreditNotes, tabReturnApprovals;
    @FXML private TextField txtReturnSearch;
    @FXML private ComboBox<String> cmbReturnStatusFilter;
    @FXML private TableView<ReturnApiClient.Summary> tblReturns;
    @FXML private TableColumn<ReturnApiClient.Summary, String> colRetNo, colRetDate, colRetInvoice, colRetParty;
    @FXML private TableColumn<ReturnApiClient.Summary, String> colRetTotal, colRetRefund, colRetStatus, colRetRefundStatus, colRetActions;

    @FXML private SplitPane mainSplit;
    @FXML private TableView<CreditNoteRow> tblCreditNotes;
    @FXML private TableColumn<CreditNoteRow, String> colNoteNo, colDate, colParty, colInvoiceRef, colReason, colStock;
    @FXML private TableColumn<CreditNoteRow, String> colTaxable, colTax, colTotal, colStatus, colActions;

    @FXML private VBox detailDrawer;
    @FXML private Label lblDrawerTitle, lblDrawerSubtitle;
    @FXML private Label lblDetailNoteNo, lblDetailDate, lblDetailParty, lblDetailInvoice, lblDetailReason;
    @FXML private Label lblDetailTaxable, lblDetailCgst, lblDetailSgst, lblDetailIgst, lblDetailTotal;
    @FXML private Label lblDetailStock, lblDetailStatus;
    @FXML private Button btnDrawerAudit, btnCloseDrawer;

    private final ObservableList<CreditNoteRow> masterList = FXCollections.observableArrayList();
    private final ObservableList<CreditNoteRow> filteredList = FXCollections.observableArrayList();
    private final ObservableList<ReturnApiClient.Summary> masterReturnsList = FXCollections.observableArrayList();
    private final ObservableList<ReturnApiClient.Summary> filteredReturnsList = FXCollections.observableArrayList();
    private final ReturnApiClient returnApi = new ReturnApiClient();
    private final NumberFormat currency = NumberFormat.getCurrencyInstance(Locale.of("en", "IN"));

    private CreditNoteRow selectedRow;

    public void initialize() {
        if (pageIcon != null) pageIcon.getChildren().setAll(IconFactory.icon("credit_note", 22));
        if (kpiCountIcon != null) kpiCountIcon.getChildren().setAll(IconFactory.compactIcon("document", 16));
        if (kpiAmountIcon != null) kpiAmountIcon.getChildren().setAll(IconFactory.compactIcon("wallet", 16));
        if (kpiTaxIcon != null) kpiTaxIcon.getChildren().setAll(IconFactory.compactIcon("tax", 16));
        if (kpiStockIcon != null) kpiStockIcon.getChildren().setAll(IconFactory.compactIcon("inventory", 16));
        if (btnScreenGuidance != null) {
            btnScreenGuidance.setText("");
            btnScreenGuidance.setContentDisplay(javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
            btnScreenGuidance.setGraphic(IconFactory.compactIcon("info", 16));
            btnScreenGuidance.setTooltip(new javafx.scene.control.Tooltip("Credit Notes & Sales Returns Guide"));
            btnScreenGuidance.setAccessibleText("Credit Notes Guide");
            if (!btnScreenGuidance.getStyleClass().contains("erp-screen-guide-button")) {
                btnScreenGuidance.getStyleClass().add("erp-screen-guide-button");
            }
        }

        configureTableColumns();
        configureReturnTableColumns();
        IconFactory.applyTableHeaderIcon(colActions, "actions");
        DynamicTableLayoutManager.install(tblCreditNotes);
        Platform.runLater(() -> {
            if (tblCreditNotes.getScene() != null && tblCreditNotes.getScene().getRoot() != null) {
                ProfessionalUiEnhancer.enhance(tblCreditNotes.getScene().getRoot());
            } else if (tblCreditNotes.getParent() != null) {
                ProfessionalUiEnhancer.enhance(tblCreditNotes.getParent());
            }
        });

        cmbReasonFilter.getItems().setAll("All Reasons", "Sales Return", "Rate Difference", "Post-Sale Discount", "Correction");
        cmbReasonFilter.setValue("All Reasons");
        cmbReasonFilter.valueProperty().addListener((obs, o, n) -> applyFilters());

        RealtimeSearchSupport.installLocal(txtSearch, this::applyFilters);

        if (cmbReturnStatusFilter != null) {
            cmbReturnStatusFilter.getItems().setAll("All Status", "PENDING APPROVAL", "APPROVED", "REJECTED", "CANCELLED");
            cmbReturnStatusFilter.setValue("All Status");
            cmbReturnStatusFilter.valueProperty().addListener((obs, o, n) -> applyReturnFilters());
        }
        if (txtReturnSearch != null) {
            RealtimeSearchSupport.installLocal(txtReturnSearch, this::applyReturnFilters);
        }

        tblCreditNotes.setRowFactory(tv -> {
            TableRow<CreditNoteRow> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY && !row.isEmpty()) {
                    tblCreditNotes.getSelectionModel().select(row.getItem());
                }
            });
            return row;
        });

        RegisterUiSupport.hideDrawer(detailDrawer, mainSplit, tblCreditNotes);
        OperationalUiSupport.installEscapeClose(mainSplit, () -> detailDrawer != null && detailDrawer.isVisible(), this::closeDetails);
        refreshData();
    }

    @Override
    public void onScreenShown(boolean reusedFromCache) {
        refreshData();
        LinkedRecordContext.Target target = LinkedRecordContext.consume("CREDIT_NOTE");
        if (target != null) {
            if (tabPaneRegister != null && tabReturnApprovals != null) {
                tabPaneRegister.getSelectionModel().select(tabReturnApprovals);
            }
            if (txtReturnSearch != null && !target.documentNo().isBlank()) {
                txtReturnSearch.setText(target.documentNo());
            }
        }
    }

    private static String apiUrl(String path) {
        String base = ConfigManager.getDataApiBaseUrl();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + path;
    }

    private void configureTableColumns() {
        colNoteNo.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().creditNoteNo));
        colDate.setCellValueFactory(d -> new SimpleStringProperty(BusinessClock.formatDate(d.getValue().noteDate)));
        colParty.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().partyName));
        colInvoiceRef.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().originalInvoiceNo != null ? d.getValue().originalInvoiceNo : "-"));
        colReason.setCellValueFactory(d -> new SimpleStringProperty(formatReason(d.getValue().reasonCode)));
        if (colStock != null) {
            colStock.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().adjustInventory ? "Restocked" : "Financial Only"));
            colStock.setCellFactory(col -> new TableCell<>() {
                @Override protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setGraphic(null); setText(null);
                    } else {
                        Label badge = new Label();
                        if ("Restocked".equalsIgnoreCase(item)) {
                            badge.setText("📦 Restocked");
                            badge.getStyleClass().addAll("badge-success", "badge-compact");
                        } else {
                            badge.setText("🚫 Financial");
                            badge.getStyleClass().addAll("badge-neutral", "badge-compact");
                        }
                        setGraphic(badge);
                        setAlignment(Pos.CENTER);
                    }
                }
            });
        }
        colTaxable.setCellValueFactory(d -> new SimpleStringProperty(currency.format(d.getValue().taxableAmount)));
        colTax.setCellValueFactory(d -> new SimpleStringProperty(currency.format(d.getValue().cgstAmount.add(d.getValue().sgstAmount).add(d.getValue().igstAmount))));
        colTotal.setCellValueFactory(d -> new SimpleStringProperty(currency.format(d.getValue().totalAmount)));
        colStatus.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().status));

        colActions.setCellFactory(col -> new TableCell<>() {
            private final MenuButton actions = new MenuButton("Actions");
            private final MenuItem itemView = new MenuItem("View Note", IconFactory.compactIcon("view", 14));
            private final MenuItem itemEdit = new MenuItem("Edit Note", IconFactory.compactIcon("edit", 14));
            private final MenuItem itemBarcode = new MenuItem("Print Barcode", IconFactory.compactIcon("barcode", 14));
            private final MenuItem itemAudit = new MenuItem("Audit Trail", IconFactory.compactIcon("history", 14));
            private final MenuItem itemCancel = new MenuItem("Cancel Note", IconFactory.compactIcon("cancel", 14));
            private final MenuItem itemDelete = new MenuItem("Delete Note", IconFactory.compactIcon("delete", 14));
            {
                actions.getStyleClass().addAll("table-action-menu", "row-actions");
                actions.setGraphic(IconFactory.compactIcon("actions", 14));
                actions.setContentDisplay(ContentDisplay.LEFT);
                actions.setGraphicTextGap(6);
                actions.setTooltip(new Tooltip("Actions"));
                IconFactory.decorateActionMenu(actions);

                itemView.setOnAction(e -> {
                    CreditNoteRow row = getTableRow().getItem();
                    if (row != null) showDetails(row);
                });
                itemEdit.setOnAction(e -> {
                    CreditNoteRow row = getTableRow().getItem();
                    if (row != null) editNote(row);
                });
                itemBarcode.setOnAction(e -> {
                    CreditNoteRow row = getTableRow().getItem();
                    if (row != null) printBarcode(row);
                });
                itemAudit.setOnAction(e -> {
                    CreditNoteRow row = getTableRow().getItem();
                    if (row != null) {
                        ActivityTimelineDialog.show(tblCreditNotes, "CREDIT_NOTE", row.id != null ? row.id.intValue() : 0, row.creditNoteNo);
                    }
                });
                itemCancel.setOnAction(e -> {
                    CreditNoteRow row = getTableRow().getItem();
                    if (row != null) cancelNote(row);
                });
                itemDelete.setOnAction(e -> {
                    CreditNoteRow row = getTableRow().getItem();
                    if (row != null) deleteNote(row);
                });

                actions.getItems().addAll(itemView, itemEdit, itemBarcode, itemAudit, new SeparatorMenuItem(), itemCancel, itemDelete);
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                } else {
                    CreditNoteRow row = getTableRow().getItem();
                    boolean cancelled = row.status != null && ("CANCELLED".equalsIgnoreCase(row.status) || "DELETED".equalsIgnoreCase(row.status));
                    itemEdit.setDisable(cancelled);
                    itemCancel.setDisable(cancelled);
                    itemDelete.setDisable("DELETED".equalsIgnoreCase(row.status));
                    setGraphic(actions);
                    setAlignment(Pos.CENTER);
                }
            }
        });

        tblCreditNotes.setItems(filteredList);
    }

    private void configureReturnTableColumns() {
        if (tblReturns == null) return;
        colRetNo.setCellValueFactory(v -> new SimpleStringProperty(v.getValue() == null ? "" : v.getValue().no()));
        colRetDate.setCellValueFactory(v -> new SimpleStringProperty(v.getValue() == null ? "" : BusinessClock.formatDate(v.getValue().date())));
        colRetInvoice.setCellValueFactory(v -> new SimpleStringProperty(v.getValue() == null ? "" : v.getValue().invoice()));
        colRetParty.setCellValueFactory(v -> new SimpleStringProperty(v.getValue() == null ? "" : v.getValue().party()));
        colRetTotal.setCellValueFactory(v -> new SimpleStringProperty(v.getValue() == null ? "₹ 0.00" : currency.format(v.getValue().total())));
        colRetRefund.setCellValueFactory(v -> new SimpleStringProperty(v.getValue() == null ? "₹ 0.00" : currency.format(v.getValue().refund())));
        colRetStatus.setCellValueFactory(v -> new SimpleStringProperty(v.getValue() == null ? "N/A" : v.getValue().status()));
        colRetRefundStatus.setCellValueFactory(v -> new SimpleStringProperty(v.getValue() == null ? "N/A" : v.getValue().refundStatus()));

        colRetTotal.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item);
                setAlignment(Pos.CENTER_RIGHT);
            }
        });
        colRetRefund.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item);
                setAlignment(Pos.CENTER_RIGHT);
            }
        });

        colRetStatus.setCellFactory(x -> SemanticTableCells.status("return"));
        colRetRefundStatus.setCellFactory(x -> SemanticTableCells.status("refund"));

        IconFactory.applyTableHeaderIcon(colRetActions, "actions");
        DynamicTableLayoutManager.install(tblReturns);

        colRetActions.setCellFactory(col -> new TableCell<>() {
            private final MenuButton actions = new MenuButton("Actions");
            private final MenuItem miApprove = new MenuItem("Approve Return", IconFactory.compactIcon("approve", 14));
            private final MenuItem miReject = new MenuItem("Reject Return", IconFactory.compactIcon("reject", 14));
            private final MenuItem miInspect = new MenuItem("Inspect Details", IconFactory.compactIcon("view", 14));
            private final MenuItem miRecordRefund = new MenuItem("Record Customer Refund", IconFactory.compactIcon("payment", 14));
            private final MenuItem miQuickApply = new MenuItem("Quick Apply (Offset Invoices)", IconFactory.compactIcon("apply", 14));
            private final MenuItem miViewCreditNote = new MenuItem("View Linked Credit Note", IconFactory.compactIcon("credit_note", 14));
            private final MenuItem miViewOriginal = new MenuItem("View Original Sale", IconFactory.compactIcon("sale", 14));
            private final MenuItem miViewPayments = new MenuItem("View Payments & Refunds", IconFactory.compactIcon("payment", 14));
            {
                actions.getStyleClass().addAll("table-action-menu", "row-actions");
                actions.setGraphic(IconFactory.compactIcon("actions", 14));
                actions.setContentDisplay(ContentDisplay.LEFT);
                actions.setGraphicTextGap(6);
                actions.setTooltip(new Tooltip("Actions"));
                IconFactory.decorateActionMenu(actions);
                miReject.getStyleClass().add("danger-menu-item");

                miApprove.setOnAction(e -> {
                    ReturnApiClient.Summary row = getTableRow().getItem();
                    if (row != null) approveSalesReturn(row);
                });
                miReject.setOnAction(e -> {
                    ReturnApiClient.Summary row = getTableRow().getItem();
                    if (row != null) rejectSalesReturn(row);
                });
                miInspect.setOnAction(e -> {
                    ReturnApiClient.Summary row = getTableRow().getItem();
                    if (row != null) inspectSalesReturn(row);
                });
                miRecordRefund.setOnAction(e -> {
                    ReturnApiClient.Summary row = getTableRow().getItem();
                    if (row != null) {
                        ReturnRefundContext.select(row.no());
                        NavigationManager.getInstance().loadPage("/fxml/pages/ReturnRefund.fxml");
                    }
                });
                miQuickApply.setOnAction(e -> {
                    ReturnApiClient.Summary row = getTableRow().getItem();
                    if (row != null) quickApplySalesReturn(row);
                });
                miViewCreditNote.setOnAction(e -> {
                    ReturnApiClient.Summary row = getTableRow().getItem();
                    if (row != null) {
                        if (tabPaneRegister != null && tabCreditNotes != null) {
                            tabPaneRegister.getSelectionModel().select(tabCreditNotes);
                        }
                        if (txtSearch != null && row.invoice() != null) {
                            txtSearch.setText(row.invoice());
                            applyFilters();
                        }
                    }
                });
                miViewOriginal.setOnAction(e -> {
                    ReturnApiClient.Summary row = getTableRow().getItem();
                    if (row != null && row.invoice() != null && !row.invoice().isBlank()) {
                        LinkedRecordContext.open("SALE", null, row.invoice(), "VIEW", "Return " + row.no());
                        NavigationManager.getInstance().loadPage("/fxml/pages/SalesList.fxml");
                    }
                });
                miViewPayments.setOnAction(e -> {
                    ReturnApiClient.Summary row = getTableRow().getItem();
                    if (row != null) {
                        ReturnRefundContext.select(row.no());
                        NavigationManager.getInstance().loadPage("/fxml/pages/ReturnRefund.fxml");
                    }
                });

                actions.getItems().setAll(miApprove, miReject, miRecordRefund, miQuickApply, miViewCreditNote, miViewOriginal, miViewPayments, miInspect);
            }
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                } else {
                    ReturnApiClient.Summary row = getTableRow().getItem();
                    String st = safe(row.status()).trim().toUpperCase(Locale.ROOT);
                    String rst = safe(row.refundStatus()).trim().toUpperCase(Locale.ROOT);
                    boolean isWaiting = "PENDING APPROVAL".equals(st) || "PENDING".equals(st);
                    boolean isApproved = "APPROVED".equals(st);
                    boolean isRefundPending = !"REFUNDED".equals(rst) && !"SETTLED".equals(rst);

                    miApprove.setVisible(isWaiting);
                    miApprove.setDisable(!isWaiting);
                    miReject.setVisible(isWaiting);
                    miReject.setDisable(!isWaiting);

                    miRecordRefund.setVisible(isApproved && isRefundPending);
                    miRecordRefund.setDisable(!isApproved || !isRefundPending);
                    miQuickApply.setVisible(isApproved && isRefundPending);
                    miQuickApply.setDisable(!isApproved || !isRefundPending);

                    miViewCreditNote.setVisible(isApproved);
                    miViewCreditNote.setDisable(!isApproved);

                    miViewOriginal.setVisible(true);
                    miViewOriginal.setDisable(false);

                    boolean hasRefundActivity = isApproved || (row.refund() > 0.009);
                    miViewPayments.setVisible(hasRefundActivity);
                    miViewPayments.setDisable(!hasRefundActivity);

                    miInspect.setVisible(true);
                    miInspect.setDisable(false);

                    setGraphic(actions);
                    setAlignment(Pos.CENTER);
                }
            }
        });

        tblReturns.setItems(filteredReturnsList);
    }

    private void quickApplySalesReturn(ReturnApiClient.Summary row) {
        if (!AppDialogService.confirm(tblReturns, "Quick Apply Credit Note",
                "Apply Credit Note / Return " + row.no() + " to outstanding unpaid invoices for " + row.party() + "?",
                "This will automatically allocate available return credit to open sales invoices in FIFO order.")) return;
        UiTaskExecutor.submitSerial("sales-return-quick-apply-" + row.no(), () -> {
            return returnApi.quickApply(row.no());
        }, msg -> {
            NotificationService.add(msg);
            AppDialogService.success(tblReturns, "Applied Successfully", msg);
            refreshData();
        }, ex -> AppDialogService.error(tblReturns, "Quick Apply Failed", ex.getMessage()));
    }

    private void approveSalesReturn(ReturnApiClient.Summary row) {
        if (!AppDialogService.confirm(tblReturns, "Approve Sales Return", "Approve Sales Return " + row.no() + " for " + currency.format(row.total()) + "?", "Approving this return confirms goods return into inventory and authorizes customer credit / refund.")) return;
        UiTaskExecutor.submitSerial("sales-return-approve-" + row.no(), () -> {
            ReturnWorkflowService.approve(row.no());
            return true;
        }, ok -> {
            NotificationService.add(row.no() + " approved.");
            AppDialogService.success(tblReturns, "Return Approved", row.no() + " was approved successfully.");
            refreshData();
        }, ex -> AppDialogService.error(tblReturns, "Approval Failed", ex.getMessage()));
    }

    private void rejectSalesReturn(ReturnApiClient.Summary row) {
        var opt = AppDialogService.confirm(tblReturns, "Reject Sales Return", "Reject Sales Return " + row.no() + "?", "Rejecting this return marks it as cancelled and stops refund / credit authorization.");
        if (!opt) return;
        var input = new org.example.util.OwnedTextInputDialog("");
        input.initOwner(tblReturns.getScene() == null ? null : tblReturns.getScene().getWindow());
        input.setTitle("Reject Sales Return");
        input.setHeaderText("Reason for rejecting " + row.no());
        input.setContentText("Rejection Reason:");
        input.showAndWait().map(String::trim).filter(r -> !r.isBlank()).ifPresent(reason -> {
            UiTaskExecutor.submitSerial("sales-return-reject-" + row.no(), () -> {
                ReturnWorkflowService.reject(row.no(), reason);
                return true;
            }, ok -> {
                NotificationService.add(row.no() + " rejected.");
                AppDialogService.success(tblReturns, "Return Rejected", row.no() + " was rejected.");
                refreshData();
            }, ex -> AppDialogService.error(tblReturns, "Rejection Failed", ex.getMessage()));
        });
    }

    private void inspectSalesReturn(ReturnApiClient.Summary row) {
        if (row == null) return;
        ReturnDocumentInspectionDialog.show(tblReturns, row.no(), true, returnApi, new org.example.api.support.SupportApiClient(), this::refreshData);
    }

    private void applyReturnFilters() {
        String search = txtReturnSearch != null && txtReturnSearch.getText() != null ? txtReturnSearch.getText().trim().toLowerCase(Locale.ROOT) : "";
        String status = cmbReturnStatusFilter != null ? cmbReturnStatusFilter.getValue() : null;

        List<ReturnApiClient.Summary> matched = masterReturnsList.stream().filter(row -> {
            boolean matchesSearch = search.isEmpty()
                || (row.no() != null && row.no().toLowerCase(Locale.ROOT).contains(search))
                || (row.party() != null && row.party().toLowerCase(Locale.ROOT).contains(search))
                || (row.invoice() != null && row.invoice().toLowerCase(Locale.ROOT).contains(search))
                || (row.reason() != null && row.reason().toLowerCase(Locale.ROOT).contains(search));
            boolean matchesStatus = status == null || "All Status".equals(status)
                || (row.status() != null && row.status().equalsIgnoreCase(status));
            return matchesSearch && matchesStatus;
        }).toList();

        filteredReturnsList.setAll(matched);
    }

    private void updateReturnTabBadge() {
        if (tabReturnApprovals == null) return;
        long pending = masterReturnsList.stream()
            .filter(r -> "PENDING APPROVAL".equalsIgnoreCase(safe(r.status()).trim()))
            .count();
        if (pending > 0) {
            tabReturnApprovals.setText("Return Approvals (" + pending + ")");
        } else {
            tabReturnApprovals.setText("Return Approvals");
        }
    }

    private String safe(String v) { return v == null ? "" : v; }

    @FXML
    public void refreshData() {
        UiTaskExecutor.submitLatest(
            "credit-notes-fetch",
            () -> {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(apiUrl("/api/credit-notes")))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json")
                    .GET();
                ApiSession.authorize(b);
                HttpResponse<String> resp = ApiRuntime.HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    return ApiRuntime.JSON.readValue(resp.body(), new TypeReference<List<CreditNoteRow>>() {});
                }
                return Collections.<CreditNoteRow>emptyList();
            },
            data -> {
                masterList.setAll(data != null ? data : Collections.emptyList());
                applyFilters();
                updateKpis();
            },
            err -> AppDialogService.error(tblCreditNotes, "Credit Notes", "Failed to Load Credit Notes", err.getMessage())
        );

        UiTaskExecutor.submitLatest(
            "credit-note-returns-fetch",
            () -> returnApi.list("SALES RETURN"),
            returns -> {
                masterReturnsList.setAll(returns != null ? returns : Collections.emptyList());
                applyReturnFilters();
                updateReturnTabBadge();
            },
            err -> PerformanceMonitor.event("credit-note-returns-error", err.getMessage())
        );
    }

    private static String formatReason(String raw) {
        if (raw == null || raw.isBlank()) return "-";
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "SALES_RETURN", "SALES RETURN", "SALE RETURN" -> "Sales Return";
            case "RATE_DIFFERENCE", "RATE DIFFERENCE" -> "Rate Difference";
            case "CUSTOMER_DISCOUNT", "CUSTOMER DISCOUNT" -> "Customer Discount";
            case "CORRECTION" -> "Correction";
            default -> raw.replace('_', ' ');
        };
    }

    private void applyFilters() {
        String search = txtSearch.getText() != null ? txtSearch.getText().trim().toLowerCase(Locale.ROOT) : "";
        String reason = cmbReasonFilter.getValue();

        List<CreditNoteRow> matched = masterList.stream().filter(row -> {
            boolean matchesSearch = search.isEmpty()
                || (row.creditNoteNo != null && row.creditNoteNo.toLowerCase(Locale.ROOT).contains(search))
                || (row.partyName != null && row.partyName.toLowerCase(Locale.ROOT).contains(search))
                || (row.originalInvoiceNo != null && row.originalInvoiceNo.toLowerCase(Locale.ROOT).contains(search));
            boolean matchesReason = reason == null || "All Reasons".equals(reason)
                || formatReason(row.reasonCode).equalsIgnoreCase(reason)
                || (row.reasonCode != null && row.reasonCode.equalsIgnoreCase(reason));
            return matchesSearch && matchesReason;
        }).toList();

        filteredList.setAll(matched);
    }

    private void updateKpis() {
        int count = masterList.size();
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal taxReversed = BigDecimal.ZERO;
        int restocked = 0;

        for (CreditNoteRow r : masterList) {
            if (r.status != null && !"CANCELLED".equalsIgnoreCase(r.status) && !"DELETED".equalsIgnoreCase(r.status)) {
                totalAmount = totalAmount.add(r.totalAmount != null ? r.totalAmount : BigDecimal.ZERO);
                taxReversed = taxReversed.add(r.cgstAmount != null ? r.cgstAmount : BigDecimal.ZERO)
                    .add(r.sgstAmount != null ? r.sgstAmount : BigDecimal.ZERO)
                    .add(r.igstAmount != null ? r.igstAmount : BigDecimal.ZERO);
                if (r.adjustInventory) {
                    restocked++;
                }
            }
        }

        kpiTotalCount.setText(String.valueOf(count));
        kpiTotalAmount.setText(currency.format(totalAmount));
        kpiTaxReversed.setText(currency.format(taxReversed));
        kpiRestockedCount.setText(String.valueOf(restocked));
    }

    private void showDetails(CreditNoteRow row) {
        if (row == null) return;
        this.selectedRow = row;
        CreditDebitNoteInspectionDialog.showCreditNote(tblCreditNotes, row, this::refreshData, this::createCreditNote);
    }

    @FXML
    public void closeDetails() {
        selectedRow = null;
        RegisterUiSupport.hideDrawer(detailDrawer, mainSplit, tblCreditNotes);
    }

    @FXML
    private void showHowItWorks() {
        ScreenGuidanceManager.showGuidance(tblCreditNotes, "CREDIT_NOTE");
    }

    @FXML
    public void createCreditNote() {
        Dialog<Map<String, Object>> dialog = new OwnedDialog<>(tblCreditNotes);
        dialog.setTitle("New Sales Return / Credit Note");
        dialog.setHeaderText("Issue a Sales Return or Credit Note with automatic GST tax reversal and stock adjustment");

        DialogPane dp = dialog.getDialogPane();
        dp.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dp.getStyleClass().addAll("approved-dialog", "erp-dialog");

        RadioButton rbGoodsReturn = new RadioButton("Physical Goods Return (Restock items into Inventory)");
        RadioButton rbFinancialAdjustment = new RadioButton("Price / Discount Adjustment (Financial Only — No Stock Movement)");
        ToggleGroup tgType = new ToggleGroup();
        rbGoodsReturn.setToggleGroup(tgType);
        rbFinancialAdjustment.setToggleGroup(tgType);
        rbGoodsReturn.setSelected(true);
        rbGoodsReturn.getStyleClass().add("approved-radio-button");
        rbFinancialAdjustment.getStyleClass().add("approved-radio-button");
        VBox typeBox = new VBox(6, new Label("Return / Credit Note Category:"), rbGoodsReturn, rbFinancialAdjustment);
        typeBox.getStyleClass().addAll("approved-card", "compact-section");

        ComboBox<String> cmbCustomer = new ComboBox<>();
        cmbCustomer.setPromptText("Search & select customer...");
        cmbCustomer.setMaxWidth(Double.MAX_VALUE);
        PartySearchUi.install(cmbCustomer, "CUSTOMER", "Select Customer", "credit-note-party-search");

        ComboBox<String> cmbInvoiceNo = new ComboBox<>();
        cmbInvoiceNo.setPromptText("Select Original Invoice (optional)");
        cmbInvoiceNo.setMaxWidth(Double.MAX_VALUE);
        cmbInvoiceNo.setEditable(true);

        ComboBox<String> cmbReason = new ComboBox<>();
        org.example.service.LookupService lookupService = new org.example.service.LookupService();
        List<String> reasons = lookupService.getValuesByCategoryCode("CREDIT_NOTE_REASON");
        if (reasons == null || reasons.isEmpty()) {
            reasons = List.of("Sales Return", "Rate Difference", "Post-Sale Discount", "Correction");
        }
        cmbReason.getItems().setAll(reasons);
        cmbReason.setValue(reasons.get(0));
        cmbReason.setMaxWidth(Double.MAX_VALUE);

        // Dynamic Reason-Specific Guidance Callout Box
        Label lblReasonHelp = new Label();
        lblReasonHelp.setWrapText(true);
        lblReasonHelp.getStyleClass().addAll("approved-callout", "reason-guidance-callout");
        lblReasonHelp.setMinHeight(42);

        TextField txtTaxable = new TextField();
        txtTaxable.setPromptText("Taxable Amount (₹)");

        ComboBox<String> cmbGstRate = new ComboBox<>();
        List<String> gstRates = lookupService.getValuesByCategoryCode("GST");
        if (gstRates == null || gstRates.isEmpty()) {
            gstRates = List.of("0", "5", "12", "18", "28");
        }
        cmbGstRate.getItems().setAll(gstRates);
        cmbGstRate.setValue(gstRates.contains("18") ? "18" : gstRates.get(0));
        cmbGstRate.setMaxWidth(Double.MAX_VALUE);

        CheckBox chkAdjustInventory = new CheckBox("Adjust Inventory (Restock returned items)");
        chkAdjustInventory.setSelected(true);

        // Live Statutory & Ledger Impact Summary Card
        Label lblStockImpactBadge = new Label();
        lblStockImpactBadge.getStyleClass().addAll("metric-trend-pill", "trend-positive");

        Label lblLedgerImpactBadge = new Label();
        lblLedgerImpactBadge.getStyleClass().addAll("metric-trend-pill", "trend-warning");

        Label lblGstImpactBadge = new Label();
        lblGstImpactBadge.getStyleClass().addAll("metric-trend-pill", "trend-info");

        VBox impactCard = new VBox(6,
            new Label("Statutory & Operational Impact Preview:"),
            lblStockImpactBadge,
            lblLedgerImpactBadge,
            lblGstImpactBadge
        );
        impactCard.getStyleClass().addAll("approved-card", "compact-section");

        Runnable updateImpact = () -> {
            String r = cmbReason.getValue();
            if (r == null) r = "Sales Return";
            boolean isGoods = rbGoodsReturn.isSelected() || chkAdjustInventory.isSelected();

            if (r.contains("Return")) {
                lblReasonHelp.setText("📦 Physical Return: Customer returned items. Warehouse inventory is restocked and Output GST is reversed in GSTR-1 Table 9B (CDNR).");
            } else if (r.contains("Rate")) {
                lblReasonHelp.setText("📉 Rate Difference: Billed rate was higher than agreed. Financial-only credit note; reverses excess Output GST without moving stock.");
            } else if (r.contains("Discount")) {
                lblReasonHelp.setText("🏷️ Post-Sale Discount: Commercial concession or turnover rebate granted post-sale. Offsets GST liability proportionately.");
            } else if (r.contains("Correction")) {
                lblReasonHelp.setText("✏️ Invoice Correction: Corrects clerical, ledger, or tax rate discrepancies. Financial adjustment only.");
            } else {
                lblReasonHelp.setText("ℹ️ Customer Credit Note: Offsets customer ledger balance with statutory GST tax liability reversal.");
            }

            BigDecimal taxable = BigDecimal.ZERO;
            try {
                taxable = new BigDecimal(txtTaxable.getText().trim());
            } catch (Exception ignored) {}
            BigDecimal rate = new BigDecimal("18");
            try {
                if (cmbGstRate.getValue() != null) rate = new BigDecimal(cmbGstRate.getValue().trim());
            } catch (Exception ignored) {}
            BigDecimal tax = taxable.multiply(rate).divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
            BigDecimal total = taxable.add(tax);

            if (isGoods) {
                lblStockImpactBadge.setText("📦 Stock: Restocks returned units into inventory (+1 qty)");
            } else {
                lblStockImpactBadge.setText("🚫 Stock: Zero movement (Financial credit adjustment only)");
            }
            lblLedgerImpactBadge.setText(String.format(Locale.ROOT, "⚖️ Ledger: Decreases Customer Balance by ₹ %,.2f (Credit)", total));
            lblGstImpactBadge.setText(String.format(Locale.ROOT, "🏛️ GST: Reverses Output Tax ₹ %,.2f in GSTR-1 Table 9B & GSTR-3B Table 3.1", tax));
        };

        rbGoodsReturn.selectedProperty().addListener((obs, oldVal, isGoods) -> {
            chkAdjustInventory.setSelected(isGoods);
            if (isGoods) {
                if (cmbReason.getItems().contains("Sales Return")) {
                    cmbReason.setValue("Sales Return");
                }
            } else {
                if (cmbReason.getItems().contains("Rate Difference")) {
                    cmbReason.setValue("Rate Difference");
                }
            }
            updateImpact.run();
        });

        cmbReason.valueProperty().addListener((obs, o, n) -> updateImpact.run());
        txtTaxable.textProperty().addListener((obs, o, n) -> updateImpact.run());
        cmbGstRate.valueProperty().addListener((obs, o, n) -> updateImpact.run());
        chkAdjustInventory.selectedProperty().addListener((obs, o, n) -> updateImpact.run());
        updateImpact.run();

        // Cascading: when customer is selected, load customer invoices
        cmbCustomer.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null || newVal.isBlank() || "Select Customer".equalsIgnoreCase(newVal.trim())) {
                cmbInvoiceNo.getItems().clear();
                return;
            }
            UiTaskExecutor.submitAction("credit-note-load-invoices", () -> {
                org.example.service.SalesService salesService = new org.example.service.SalesService();
                return salesService.allFiltered(null, null, newVal.trim(), null, null, null, null, null, null, null, null, null, null);
            }, invoices -> {
                List<String> invNumbers = new ArrayList<>();
                for (org.example.model.Sales s : invoices) {
                    if (s != null && s.getInvoiceNo() != null && !s.getInvoiceNo().isBlank()) {
                        invNumbers.add(s.getInvoiceNo());
                    }
                }
                cmbInvoiceNo.getItems().setAll(invNumbers);
            }, err -> {});
        });

        // When invoice is chosen, auto-fill taxable amount
        cmbInvoiceNo.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && !newVal.isBlank()) {
                UiTaskExecutor.submitAction("credit-note-load-invoice-details", () -> {
                    org.example.service.SalesService salesService = new org.example.service.SalesService();
                    return salesService.getByInvoice(newVal.trim());
                }, sale -> {
                    if (sale != null && (sale.getSubtotal() > 0 || sale.getTotalAmount() > 0)) {
                        double baseTaxable = sale.getSubtotal() > 0 ? sale.getSubtotal() : sale.getTotalAmount();
                        txtTaxable.setText(String.format(Locale.ROOT, "%.2f", baseTaxable));
                        updateImpact.run();
                    }
                }, err -> {});
            }
        });

        VBox content = new VBox(10,
            typeBox,
            new Label("Customer:"), cmbCustomer,
            new Label("Original Invoice No:"), cmbInvoiceNo,
            new Label("Reason:"), cmbReason,
            lblReasonHelp,
            new Label("Taxable Amount (₹):"), txtTaxable,
            new Label("GST Rate (%):"), cmbGstRate,
            chkAdjustInventory,
            impactCard
        );
        content.setPrefWidth(540);
        dp.setContent(content);
        org.example.util.SearchableComboBoxHelper.makeAllSearchable(content);

        dialog.setResultConverter(btn -> {
            if (btn == ButtonType.OK) {
                String party = cmbCustomer.getValue();
                if (party == null || "Select Customer".equalsIgnoreCase(party.trim())) {
                    party = cmbCustomer.getEditor() != null ? cmbCustomer.getEditor().getText().trim() : "";
                }
                String inv = cmbInvoiceNo.getValue();
                if (inv == null && cmbInvoiceNo.getEditor() != null) {
                    inv = cmbInvoiceNo.getEditor().getText().trim();
                }

                BigDecimal taxableAmt = BigDecimal.ZERO;
                try {
                    taxableAmt = new BigDecimal(txtTaxable.getText().trim());
                } catch (Exception ignored) {}

                BigDecimal rate = new BigDecimal("18");
                try {
                    rate = new BigDecimal(cmbGstRate.getValue().trim());
                } catch (Exception ignored) {}

                BigDecimal taxTotal = taxableAmt.multiply(rate).divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
                BigDecimal totalCredit = taxableAmt.add(taxTotal);

                // Error Prevention Confirmation Modal
                String confirmTitle = "Confirm Sales Return / Credit Note Issuance";
                String confirmHeader = "Please review the operational and statutory tax impact:";
                String confirmContent = String.format(
                    Locale.ROOT,
                    "• Customer: %s\n• Original Invoice: %s\n• Reason: %s\n• Taxable Amount: ₹ %,.2f\n• GST Rate: %s%% (Tax: ₹ %,.2f)\n• Total Credit Amount: ₹ %,.2f\n• Inventory Adjustment: %s\n• Statutory GST Reversal: Reverses ₹ %,.2f Output Tax in GSTR-1 Table 9B\n\nConfirm issuance and post to general ledger?",
                    (party != null && !party.isBlank()) ? party : "Not specified",
                    (inv != null && !inv.isBlank()) ? inv : "Unlinked",
                    cmbReason.getValue(),
                    taxableAmt,
                    rate,
                    taxTotal,
                    totalCredit,
                    chkAdjustInventory.isSelected() ? "YES (Restock physical items into inventory)" : "NO (Financial adjustment only — Zero stock movement)",
                    taxTotal
                );

                if (!AppDialogService.confirm(tblCreditNotes, confirmTitle, confirmHeader, confirmContent)) {
                    return null;
                }

                Map<String, Object> req = new HashMap<>();
                req.put("partyName", party != null ? party.trim() : "");
                req.put("originalInvoiceNo", inv != null ? inv.trim() : "");
                req.put("reasonCode", cmbReason.getValue());
                req.put("taxableAmount", taxableAmt);
                req.put("gstRate", rate);
                req.put("adjustInventory", chkAdjustInventory.isSelected());

                // Synthesize 1 line item; server automatically computes accurate tax split (IGST vs CGST/SGST)
                Map<String, Object> line = new HashMap<>();
                line.put("itemDescription", "Credit Note Adjustment: " + cmbReason.getValue());
                line.put("quantity", BigDecimal.ONE);
                line.put("unitPrice", taxableAmt);
                line.put("gstRate", rate);
                line.put("taxableValue", taxableAmt);
                line.put("cgstAmount", BigDecimal.ZERO);
                line.put("sgstAmount", BigDecimal.ZERO);
                line.put("igstAmount", BigDecimal.ZERO);
                line.put("totalLineAmount", taxableAmt.add(taxTotal));
                req.put("lines", List.of(line));

                return req;
            }
            return null;
        });

        Optional<Map<String, Object>> result = dialog.showAndWait();
        result.ifPresent(req -> {
            UiTaskExecutor.submitAction(
                "credit-note-create",
                () -> {
                    String json = ApiRuntime.JSON.writeValueAsString(req);
                    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(apiUrl("/api/credit-notes")))
                        .timeout(Duration.ofSeconds(20))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json));
                    ApiSession.authorize(b);
                    HttpResponse<String> resp = ApiRuntime.HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() == 200 || resp.statusCode() == 201) {
                        return "SUCCESS";
                    }
                    throw new RuntimeException("Server error: " + resp.body());
                },
                res -> {
                    AppDialogService.success(tblCreditNotes, "Credit Note Created", "Credit note created and posted to general ledger successfully.");
                    org.example.service.NotificationService.createNotification(
                            org.example.service.NotificationService.Category.SALES,
                            "Credit Note Created",
                            "Credit note created and posted to general ledger successfully.",
                            "INFO",
                            "/fxml/pages/CreditNoteRegister.fxml",
                            null
                    );
                    refreshData();
                },
                err -> AppDialogService.error(tblCreditNotes, "Credit Notes", "Creation Failed", err.getMessage())
            );
        });
    }

    private void editNote(CreditNoteRow row) {
        if (row == null) return;
        showDetails(row);
    }

    private void deleteNote(CreditNoteRow row) {
        if (row == null) return;
        if (!AppDialogService.confirm(tblCreditNotes, "Delete Credit Note",
                "Delete Credit Note " + row.creditNoteNo + "?",
                "This will void the credit note, reverse GL postings, and restore inventory cleanly.")) {
            return;
        }

        UiTaskExecutor.submitAction(
            "credit-note-delete-" + row.id,
            () -> {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(apiUrl("/api/credit-notes/" + row.id)))
                    .timeout(Duration.ofSeconds(20))
                    .DELETE();
                ApiSession.authorize(b);
                HttpResponse<String> resp = ApiRuntime.HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    return "SUCCESS";
                }
                throw new RuntimeException("Failed to delete: " + resp.body());
            },
            res -> {
                AppDialogService.success(tblCreditNotes, "Deleted", "Credit Note deleted safely.");
                refreshData();
            },
            err -> AppDialogService.error(tblCreditNotes, "Credit Notes", "Deletion Failed", err.getMessage())
        );
    }

    private void cancelNote(CreditNoteRow row) {
        if (!AppDialogService.confirm(tblCreditNotes, "Cancel Credit Note", "Cancel Credit Note " + row.creditNoteNo + "?", "This will reverse GL postings and adjust inventory.")) {
            return;
        }

        UiTaskExecutor.submitAction(
            "credit-note-cancel-" + row.id,
            () -> {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(apiUrl("/api/credit-notes/" + row.id + "/cancel")))
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.noBody());
                ApiSession.authorize(b);
                HttpResponse<String> resp = ApiRuntime.HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    return "SUCCESS";
                }
                throw new RuntimeException("Failed to cancel: " + resp.body());
            },
            res -> {
                AppDialogService.success(tblCreditNotes, "Cancelled", "Credit Note cancelled successfully.");
                refreshData();
            },
            err -> AppDialogService.error(tblCreditNotes, "Credit Notes", "Cancellation Failed", err.getMessage())
        );
    }

    @FXML
    public void auditSelected() {
        if (selectedRow != null) {
            ActivityTimelineDialog.show(tblCreditNotes, "CREDIT_NOTE", selectedRow.id != null ? selectedRow.id.intValue() : 0, selectedRow.creditNoteNo);
        }
    }

    @FXML
    public void printBarcodeSelected() {
        if (selectedRow != null) printBarcode(selectedRow);
    }

    private void printBarcode(CreditNoteRow row) {
        if (row == null) return;
        OrderDispatchBarcodeDialog.showForCreditNote(
            tblCreditNotes,
            row.creditNoteNo,
            row.noteDate,
            row.partyName,
            "",
            row.originalInvoiceNo,
            row.reasonCode,
            row.totalAmount != null ? row.totalAmount.doubleValue() : 0.0
        );
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public static class CreditNoteRow {
        public Long id;
        public String creditNoteNo;
        public String noteDate;
        public String partyName;
        public String partyGstin;
        public String originalInvoiceNo;
        public String reasonCode;
        public BigDecimal taxableAmount = BigDecimal.ZERO;
        public BigDecimal cgstAmount = BigDecimal.ZERO;
        public BigDecimal sgstAmount = BigDecimal.ZERO;
        public BigDecimal igstAmount = BigDecimal.ZERO;
        public BigDecimal roundoffAmount = BigDecimal.ZERO;
        public BigDecimal totalAmount = BigDecimal.ZERO;
        @com.fasterxml.jackson.annotation.JsonAlias({"stockReturned", "adjustInventory"})
        public boolean adjustInventory;
        public String status;
        public Long glEntryId;
        public String notes;
        public String createdBy;
        public String createdAt;
        public Long rowVersion;
    }
}
