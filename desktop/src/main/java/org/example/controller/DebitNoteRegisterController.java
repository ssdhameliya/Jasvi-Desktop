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

public class DebitNoteRegisterController implements ScreenLifecycle {

    @FXML private StackPane pageIcon;
    @FXML private StackPane kpiCountIcon, kpiAmountIcon, kpiTaxIcon, kpiStockIcon;
    @FXML private Label kpiTotalCount, kpiTotalAmount, kpiTaxReversed, kpiReturnedCount;

    @FXML private Button btnNewDebitNote, btnRefresh, btnScreenGuidance;
    @FXML private TextField txtSearch;
    @FXML private ComboBox<String> cmbReasonFilter;

    @FXML private TabPane tabPaneRegister;
    @FXML private Tab tabDebitNotes, tabReturnApprovals;
    @FXML private TextField txtReturnSearch;
    @FXML private ComboBox<String> cmbReturnStatusFilter;
    @FXML private TableView<ReturnApiClient.Summary> tblReturns;
    @FXML private TableColumn<ReturnApiClient.Summary, String> colRetNo, colRetDate, colRetInvoice, colRetSupplier;
    @FXML private TableColumn<ReturnApiClient.Summary, String> colRetTotal, colRetRefund, colRetStatus, colRetRefundStatus, colRetActions;

    @FXML private SplitPane mainSplit;
    @FXML private TableView<DebitNoteRow> tblDebitNotes;
    @FXML private TableColumn<DebitNoteRow, String> colNoteNo, colDate, colParty, colInvoiceRef, colReason, colStock;
    @FXML private TableColumn<DebitNoteRow, String> colTaxable, colTax, colTotal, colStatus, colActions;

    @FXML private VBox detailDrawer;
    @FXML private Label lblDrawerTitle, lblDrawerSubtitle;
    @FXML private Label lblDetailNoteNo, lblDetailDate, lblDetailParty, lblDetailInvoice, lblDetailReason;
    @FXML private Label lblDetailTaxable, lblDetailCgst, lblDetailSgst, lblDetailIgst, lblDetailTotal;
    @FXML private Label lblDetailStock, lblDetailStatus;
    @FXML private Button btnDrawerAudit, btnCloseDrawer;

    private final ObservableList<DebitNoteRow> masterList = FXCollections.observableArrayList();
    private final ObservableList<DebitNoteRow> filteredList = FXCollections.observableArrayList();
    private final ObservableList<ReturnApiClient.Summary> masterReturnsList = FXCollections.observableArrayList();
    private final ObservableList<ReturnApiClient.Summary> filteredReturnsList = FXCollections.observableArrayList();
    private final ReturnApiClient returnApi = new ReturnApiClient();
    private final NumberFormat currency = NumberFormat.getCurrencyInstance(Locale.of("en", "IN"));

    private DebitNoteRow selectedRow;

    public void initialize() {
        if (pageIcon != null) pageIcon.getChildren().setAll(IconFactory.icon("debit_note", 22));
        if (kpiCountIcon != null) kpiCountIcon.getChildren().setAll(IconFactory.compactIcon("document", 16));
        if (kpiAmountIcon != null) kpiAmountIcon.getChildren().setAll(IconFactory.compactIcon("wallet", 16));
        if (kpiTaxIcon != null) kpiTaxIcon.getChildren().setAll(IconFactory.compactIcon("tax", 16));
        if (kpiStockIcon != null) kpiStockIcon.getChildren().setAll(IconFactory.compactIcon("inventory", 16));
        if (btnScreenGuidance != null) btnScreenGuidance.setGraphic(IconFactory.compactIcon("help", 14));

        configureTableColumns();
        configureReturnTableColumns();
        IconFactory.applyTableHeaderIcon(colActions, "actions");
        DynamicTableLayoutManager.install(tblDebitNotes);
        Platform.runLater(() -> {
            if (tblDebitNotes.getScene() != null && tblDebitNotes.getScene().getRoot() != null) {
                ProfessionalUiEnhancer.enhance(tblDebitNotes.getScene().getRoot());
            } else if (tblDebitNotes.getParent() != null) {
                ProfessionalUiEnhancer.enhance(tblDebitNotes.getParent());
            }
        });

        cmbReasonFilter.getItems().setAll("All Reasons", "Purchase Return", "Rate Difference", "Supplier Discount", "Correction");
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

        tblDebitNotes.setRowFactory(tv -> {
            TableRow<DebitNoteRow> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY && !row.isEmpty()) {
                    tblDebitNotes.getSelectionModel().select(row.getItem());
                }
            });
            return row;
        });

        RegisterUiSupport.hideDrawer(detailDrawer, mainSplit, tblDebitNotes);
        OperationalUiSupport.installEscapeClose(mainSplit, () -> detailDrawer != null && detailDrawer.isVisible(), this::closeDetails);
        refreshData();
    }

    @Override
    public void onScreenShown(boolean reusedFromCache) {
        refreshData();
        LinkedRecordContext.Target target = LinkedRecordContext.consume("DEBIT_NOTE");
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
        colNoteNo.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().debitNoteNo));
        colDate.setCellValueFactory(d -> new SimpleStringProperty(BusinessClock.formatDate(d.getValue().noteDate)));
        colParty.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().partyName));
        colInvoiceRef.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().originalPurchaseNo != null ? d.getValue().originalPurchaseNo : "-"));
        colReason.setCellValueFactory(d -> new SimpleStringProperty(formatReason(d.getValue().reasonCode)));
        if (colStock != null) {
            colStock.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().adjustInventory ? "Deducted" : "Financial Only"));
            colStock.setCellFactory(col -> new TableCell<>() {
                @Override protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setGraphic(null); setText(null);
                    } else {
                        Label badge = new Label();
                        if ("Deducted".equalsIgnoreCase(item)) {
                            badge.setText("📦 Deducted");
                            badge.getStyleClass().addAll("badge-warning", "badge-compact");
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
                    DebitNoteRow row = getTableRow().getItem();
                    if (row != null) showDetails(row);
                });
                itemEdit.setOnAction(e -> {
                    DebitNoteRow row = getTableRow().getItem();
                    if (row != null) editNote(row);
                });
                itemBarcode.setOnAction(e -> {
                    DebitNoteRow row = getTableRow().getItem();
                    if (row != null) printBarcode(row);
                });
                itemAudit.setOnAction(e -> {
                    DebitNoteRow row = getTableRow().getItem();
                    if (row != null) {
                        ActivityTimelineDialog.show(tblDebitNotes, "DEBIT_NOTE", row.id != null ? row.id.intValue() : 0, row.debitNoteNo);
                    }
                });
                itemCancel.setOnAction(e -> {
                    DebitNoteRow row = getTableRow().getItem();
                    if (row != null) cancelNote(row);
                });
                itemDelete.setOnAction(e -> {
                    DebitNoteRow row = getTableRow().getItem();
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
                    DebitNoteRow row = getTableRow().getItem();
                    boolean cancelled = row.status != null && ("CANCELLED".equalsIgnoreCase(row.status) || "DELETED".equalsIgnoreCase(row.status));
                    itemEdit.setDisable(cancelled);
                    itemCancel.setDisable(cancelled);
                    itemDelete.setDisable("DELETED".equalsIgnoreCase(row.status));
                    setGraphic(actions);
                    setAlignment(Pos.CENTER);
                }
            }
        });

        tblDebitNotes.setItems(filteredList);
    }

    private void configureReturnTableColumns() {
        if (tblReturns == null) return;
        colRetNo.setCellValueFactory(v -> new SimpleStringProperty(v.getValue() == null ? "" : v.getValue().no()));
        colRetDate.setCellValueFactory(v -> new SimpleStringProperty(v.getValue() == null ? "" : BusinessClock.formatDate(v.getValue().date())));
        colRetInvoice.setCellValueFactory(v -> new SimpleStringProperty(v.getValue() == null ? "" : v.getValue().invoice()));
        colRetSupplier.setCellValueFactory(v -> new SimpleStringProperty(v.getValue() == null ? "" : v.getValue().party()));
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
            private final MenuItem miRecordRefund = new MenuItem("Record Refund Payment", IconFactory.compactIcon("payment", 14));
            private final MenuItem miQuickApply = new MenuItem("Quick Apply (Offset Bills)", IconFactory.compactIcon("apply", 14));
            private final MenuItem miViewDebitNote = new MenuItem("View Linked Debit Note", IconFactory.compactIcon("debit_note", 14));
            private final MenuItem miViewOriginal = new MenuItem("View Original Purchase", IconFactory.compactIcon("purchase", 14));
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
                    if (row != null) approvePurchaseReturn(row);
                });
                miReject.setOnAction(e -> {
                    ReturnApiClient.Summary row = getTableRow().getItem();
                    if (row != null) rejectPurchaseReturn(row);
                });
                miInspect.setOnAction(e -> {
                    ReturnApiClient.Summary row = getTableRow().getItem();
                    if (row != null) inspectPurchaseReturn(row);
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
                    if (row != null) quickApplyPurchaseReturn(row);
                });
                miViewDebitNote.setOnAction(e -> {
                    ReturnApiClient.Summary row = getTableRow().getItem();
                    if (row != null) {
                        if (tabPaneRegister != null && tabDebitNotes != null) {
                            tabPaneRegister.getSelectionModel().select(tabDebitNotes);
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
                        LinkedRecordContext.open("PURCHASE", null, row.invoice(), "VIEW", "Return " + row.no());
                        NavigationManager.getInstance().loadPage("/fxml/pages/PurchaseList.fxml");
                    }
                });
                miViewPayments.setOnAction(e -> {
                    ReturnApiClient.Summary row = getTableRow().getItem();
                    if (row != null) {
                        ReturnRefundContext.select(row.no());
                        NavigationManager.getInstance().loadPage("/fxml/pages/ReturnRefund.fxml");
                    }
                });

                actions.getItems().setAll(miApprove, miReject, miRecordRefund, miQuickApply, miViewDebitNote, miViewOriginal, miViewPayments, miInspect);
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

                    miViewDebitNote.setVisible(isApproved);
                    miViewDebitNote.setDisable(!isApproved);

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

    private void quickApplyPurchaseReturn(ReturnApiClient.Summary row) {
        if (!AppDialogService.confirm(tblReturns, "Quick Apply Debit Note",
                "Apply Debit Note / Return " + row.no() + " to outstanding unpaid bills for " + row.party() + "?",
                "This will automatically allocate available return credit to open purchase bills in FIFO order.")) return;
        UiTaskExecutor.submitSerial("purchase-return-quick-apply-" + row.no(), () -> {
            return returnApi.quickApply(row.no());
        }, msg -> {
            NotificationService.add(msg);
            AppDialogService.success(tblReturns, "Applied Successfully", msg);
            refreshData();
        }, ex -> AppDialogService.error(tblReturns, "Quick Apply Failed", ex.getMessage()));
    }

    private void approvePurchaseReturn(ReturnApiClient.Summary row) {
        if (!AppDialogService.confirm(tblReturns, "Approve Purchase Return", "Approve Purchase Return " + row.no() + " for " + currency.format(row.total()) + "?", "Approving this return confirms physical goods return, posts stock reversal, and authorises supplier settlement.")) return;
        UiTaskExecutor.submitSerial("purchase-return-approve-" + row.no(), () -> {
            ReturnWorkflowService.approve(row.no());
            return true;
        }, ok -> {
            NotificationService.add(row.no() + " approved.");
            AppDialogService.success(tblReturns, "Return Approved", row.no() + " was approved successfully.");
            refreshData();
        }, ex -> AppDialogService.error(tblReturns, "Approval Failed", ex.getMessage()));
    }

    private void rejectPurchaseReturn(ReturnApiClient.Summary row) {
        var opt = AppDialogService.confirm(tblReturns, "Reject Purchase Return", "Reject Purchase Return " + row.no() + "?", "Rejecting this return marks it as cancelled and stops supplier settlement.");
        if (!opt) return;
        var input = new org.example.util.OwnedTextInputDialog("");
        input.initOwner(tblReturns.getScene() == null ? null : tblReturns.getScene().getWindow());
        input.setTitle("Reject Purchase Return");
        input.setHeaderText("Reason for rejecting " + row.no());
        input.setContentText("Rejection Reason:");
        input.showAndWait().map(String::trim).filter(r -> !r.isBlank()).ifPresent(reason -> {
            UiTaskExecutor.submitSerial("purchase-return-reject-" + row.no(), () -> {
                ReturnWorkflowService.reject(row.no(), reason);
                return true;
            }, ok -> {
                NotificationService.add(row.no() + " rejected.");
                AppDialogService.success(tblReturns, "Return Rejected", row.no() + " was rejected.");
                refreshData();
            }, ex -> AppDialogService.error(tblReturns, "Rejection Failed", ex.getMessage()));
        });
    }

    private void inspectPurchaseReturn(ReturnApiClient.Summary row) {
        if (row == null) return;
        ReturnDocumentInspectionDialog.show(tblReturns, row.no(), false, returnApi, new org.example.api.support.SupportApiClient(), this::refreshData);
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
            "debit-notes-fetch",
            () -> {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(apiUrl("/api/debit-notes")))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json")
                    .GET();
                ApiSession.authorize(b);
                HttpResponse<String> resp = ApiRuntime.HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    return ApiRuntime.JSON.readValue(resp.body(), new TypeReference<List<DebitNoteRow>>() {});
                }
                return Collections.<DebitNoteRow>emptyList();
            },
            data -> {
                masterList.setAll(data != null ? data : Collections.emptyList());
                applyFilters();
                updateKpis();
            },
            err -> AppDialogService.error(tblDebitNotes, "Debit Notes", "Failed to Load Debit Notes", err.getMessage())
        );

        UiTaskExecutor.submitLatest(
            "debit-note-returns-fetch",
            () -> returnApi.list("PURCHASE RETURN"),
            returns -> {
                masterReturnsList.setAll(returns != null ? returns : Collections.emptyList());
                applyReturnFilters();
                updateReturnTabBadge();
            },
            err -> PerformanceMonitor.event("debit-note-returns-error", err.getMessage())
        );
    }

    private static String formatReason(String raw) {
        if (raw == null || raw.isBlank()) return "-";
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "PURCHASE_RETURN", "PURCHASE RETURN" -> "Purchase Return";
            case "RATE_DIFFERENCE", "RATE DIFFERENCE" -> "Rate Difference";
            case "SUPPLIER_DISCOUNT", "SUPPLIER DISCOUNT" -> "Supplier Discount";
            case "CORRECTION" -> "Correction";
            default -> raw.replace('_', ' ');
        };
    }

    private void applyFilters() {
        String search = txtSearch.getText() != null ? txtSearch.getText().trim().toLowerCase(Locale.ROOT) : "";
        String reason = cmbReasonFilter.getValue();

        List<DebitNoteRow> matched = masterList.stream().filter(row -> {
            boolean matchesSearch = search.isEmpty()
                || (row.debitNoteNo != null && row.debitNoteNo.toLowerCase(Locale.ROOT).contains(search))
                || (row.partyName != null && row.partyName.toLowerCase(Locale.ROOT).contains(search))
                || (row.originalPurchaseNo != null && row.originalPurchaseNo.toLowerCase(Locale.ROOT).contains(search));
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
        int returned = 0;

        for (DebitNoteRow r : masterList) {
            if (r.status != null && !"CANCELLED".equalsIgnoreCase(r.status) && !"DELETED".equalsIgnoreCase(r.status)) {
                totalAmount = totalAmount.add(r.totalAmount != null ? r.totalAmount : BigDecimal.ZERO);
                taxReversed = taxReversed.add(r.cgstAmount != null ? r.cgstAmount : BigDecimal.ZERO)
                    .add(r.sgstAmount != null ? r.sgstAmount : BigDecimal.ZERO)
                    .add(r.igstAmount != null ? r.igstAmount : BigDecimal.ZERO);
                if (r.adjustInventory) {
                    returned++;
                }
            }
        }

        kpiTotalCount.setText(String.valueOf(count));
        kpiTotalAmount.setText(currency.format(totalAmount));
        kpiTaxReversed.setText(currency.format(taxReversed));
        kpiReturnedCount.setText(String.valueOf(returned));
    }

    private void showDetails(DebitNoteRow row) {
        if (row == null) return;
        this.selectedRow = row;
        CreditDebitNoteInspectionDialog.showDebitNote(tblDebitNotes, row, this::refreshData, this::createDebitNote);
    }

    @FXML
    public void closeDetails() {
        selectedRow = null;
        RegisterUiSupport.hideDrawer(detailDrawer, mainSplit, tblDebitNotes);
    }

    @FXML
    private void showHowItWorks() {
        ScreenGuidanceManager.showGuidance(tblDebitNotes, "DEBIT_NOTE");
    }

    @FXML
    public void createDebitNote() {
        Dialog<Map<String, Object>> dialog = new OwnedDialog<>(tblDebitNotes);
        dialog.setTitle("New Purchase Return / Debit Note");
        dialog.setHeaderText("Issue a Purchase Return or Debit Note with automatic GST ITC reversal and stock adjustment");

        DialogPane dp = dialog.getDialogPane();
        dp.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dp.getStyleClass().addAll("approved-dialog", "erp-dialog");

        RadioButton rbGoodsReturn = new RadioButton("Physical Goods Return (Deduct items from Inventory)");
        RadioButton rbFinancialAdjustment = new RadioButton("Price / Rate Adjustment (Financial Only — No Stock Movement)");
        ToggleGroup tgType = new ToggleGroup();
        rbGoodsReturn.setToggleGroup(tgType);
        rbFinancialAdjustment.setToggleGroup(tgType);
        rbGoodsReturn.setSelected(true);
        rbGoodsReturn.getStyleClass().add("approved-radio-button");
        rbFinancialAdjustment.getStyleClass().add("approved-radio-button");
        VBox typeBox = new VBox(6, new Label("Return / Debit Note Category:"), rbGoodsReturn, rbFinancialAdjustment);
        typeBox.getStyleClass().addAll("approved-card", "compact-section");

        ComboBox<String> cmbSupplier = new ComboBox<>();
        cmbSupplier.setPromptText("Search & select supplier...");
        cmbSupplier.setMaxWidth(Double.MAX_VALUE);
        PartySearchUi.install(cmbSupplier, "SUPPLIER", "Select Supplier", "debit-note-party-search");

        ComboBox<String> cmbBillNo = new ComboBox<>();
        cmbBillNo.setPromptText("Select Original Purchase Bill (optional)");
        cmbBillNo.setMaxWidth(Double.MAX_VALUE);
        cmbBillNo.setEditable(true);

        ComboBox<String> cmbReason = new ComboBox<>();
        org.example.service.LookupService lookupService = new org.example.service.LookupService();
        List<String> reasons = lookupService.getValuesByCategoryCode("DEBIT_NOTE_REASON");
        if (reasons == null || reasons.isEmpty()) {
            reasons = List.of("Purchase Return", "Rate Difference", "Supplier Discount", "Correction");
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

        CheckBox chkAdjustInventory = new CheckBox("Adjust Inventory (Deduct returned stock)");
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
            if (r == null) r = "Purchase Return";
            boolean isGoods = rbGoodsReturn.isSelected() || chkAdjustInventory.isSelected();

            if (r.contains("Return")) {
                lblReasonHelp.setText("📦 Physical Vendor Return: Rejected/damaged materials returned to vendor. Warehouse inventory is deducted and Input Tax Credit (ITC) is reversed under GSTR-3B Table 4B(2).");
            } else if (r.contains("Rate")) {
                lblReasonHelp.setText("📉 Rate Difference: Supplier overcharged on unit rates. Financial debit adjustment against supplier ledger; reverses excess claimed ITC without stock movement.");
            } else if (r.contains("Discount")) {
                lblReasonHelp.setText("🏷️ Supplier Discount: Post-purchase commercial rebate from supplier. Reduces Accounts Payable balance and offsets claimed ITC proportionately.");
            } else if (r.contains("Correction")) {
                lblReasonHelp.setText("✏️ Bill Correction: Corrects typographical, invoice amount, or tax rate discrepancies. Financial adjustment only.");
            } else {
                lblReasonHelp.setText("ℹ️ Supplier Debit Note: Debits supplier ledger balance with statutory GST Input Tax Credit (ITC) reversal.");
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
                lblStockImpactBadge.setText("📦 Stock: Deducts returned units from warehouse inventory (-1 qty)");
            } else {
                lblStockImpactBadge.setText("🚫 Stock: Zero movement (Financial debit adjustment only)");
            }
            lblLedgerImpactBadge.setText(String.format(Locale.ROOT, "⚖️ Ledger: Decreases Supplier Payable by ₹ %,.2f (Debit)", total));
            lblGstImpactBadge.setText(String.format(Locale.ROOT, "🏛️ GST: Reverses Input Tax Credit (ITC) ₹ %,.2f in GSTR-3B Table 4B(2)", tax));
        };

        rbGoodsReturn.selectedProperty().addListener((obs, oldVal, isGoods) -> {
            chkAdjustInventory.setSelected(isGoods);
            if (isGoods) {
                if (cmbReason.getItems().contains("Purchase Return")) {
                    cmbReason.setValue("Purchase Return");
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

        // Cascading: when supplier is selected, load supplier purchase bills
        cmbSupplier.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null || newVal.isBlank() || "Select Supplier".equalsIgnoreCase(newVal.trim())) {
                cmbBillNo.getItems().clear();
                return;
            }
            UiTaskExecutor.submitAction("debit-note-load-bills", () -> {
                org.example.service.PurchaseService purchaseService = new org.example.service.PurchaseService();
                return purchaseService.allFiltered(null, newVal.trim(), null, null, null, null, null, null);
            }, purchases -> {
                List<String> billNumbers = new ArrayList<>();
                for (org.example.model.Purchase p : purchases) {
                    if (p != null && p.getInvoiceNo() != null && !p.getInvoiceNo().isBlank()) {
                        billNumbers.add(p.getInvoiceNo());
                    }
                }
                cmbBillNo.getItems().setAll(billNumbers);
            }, err -> {});
        });

        // When bill is chosen, auto-fill taxable amount
        cmbBillNo.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && !newVal.isBlank()) {
                UiTaskExecutor.submitAction("debit-note-load-bill-details", () -> {
                    org.example.service.PurchaseService purchaseService = new org.example.service.PurchaseService();
                    return purchaseService.getByInvoice(newVal.trim());
                }, purchase -> {
                    if (purchase != null && purchase.getTotalAmount() > 0) {
                        txtTaxable.setText(String.format(Locale.ROOT, "%.2f", purchase.getTotalAmount()));
                        updateImpact.run();
                    }
                }, err -> {});
            }
        });

        VBox content = new VBox(10,
            typeBox,
            new Label("Supplier:"), cmbSupplier,
            new Label("Original Bill No:"), cmbBillNo,
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
                String party = cmbSupplier.getValue();
                if (party == null || "Select Supplier".equalsIgnoreCase(party.trim())) {
                    party = cmbSupplier.getEditor() != null ? cmbSupplier.getEditor().getText().trim() : "";
                }
                String bill = cmbBillNo.getValue();
                if (bill == null && cmbBillNo.getEditor() != null) {
                    bill = cmbBillNo.getEditor().getText().trim();
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
                BigDecimal totalDebit = taxableAmt.add(taxTotal);

                // Error Prevention Confirmation Modal
                String confirmTitle = "Confirm Purchase Return / Debit Note Issuance";
                String confirmHeader = "Please review the operational and statutory tax impact:";
                String confirmContent = String.format(
                    Locale.ROOT,
                    "• Supplier: %s\n• Original Bill: %s\n• Reason: %s\n• Taxable Amount: ₹ %,.2f\n• GST Rate: %s%% (Tax: ₹ %,.2f)\n• Total Debit Amount: ₹ %,.2f\n• Inventory Adjustment: %s\n• Statutory ITC Reversal: Reverses ₹ %,.2f Input Tax Credit in GSTR-3B Table 4B(2)\n\nConfirm issuance and post to general ledger?",
                    (party != null && !party.isBlank()) ? party : "Not specified",
                    (bill != null && !bill.isBlank()) ? bill : "Unlinked",
                    cmbReason.getValue(),
                    taxableAmt,
                    rate,
                    taxTotal,
                    totalDebit,
                    chkAdjustInventory.isSelected() ? "YES (Deduct physical items from inventory)" : "NO (Financial adjustment only — Zero stock movement)",
                    taxTotal
                );

                if (!AppDialogService.confirm(tblDebitNotes, confirmTitle, confirmHeader, confirmContent)) {
                    return null;
                }

                Map<String, Object> req = new HashMap<>();
                req.put("partyName", party != null ? party.trim() : "");
                req.put("supplierName", party != null ? party.trim() : "");
                req.put("originalBillNo", bill != null ? bill.trim() : "");
                req.put("originalPurchaseNo", bill != null ? bill.trim() : "");
                req.put("reasonCode", cmbReason.getValue());
                req.put("taxableAmount", taxableAmt);
                req.put("gstRate", rate);
                req.put("adjustInventory", chkAdjustInventory.isSelected());

                // Synthesize 1 line item for accurate tax split and GL journal calculation
                BigDecimal halfTax = taxTotal.divide(BigDecimal.valueOf(2), 2, java.math.RoundingMode.HALF_UP);
                Map<String, Object> line = new HashMap<>();
                line.put("itemDescription", "Debit Note Adjustment: " + cmbReason.getValue());
                line.put("quantity", BigDecimal.ONE);
                line.put("unitPrice", taxableAmt);
                line.put("gstRate", rate);
                line.put("taxableValue", taxableAmt);
                line.put("cgstAmount", halfTax);
                line.put("sgstAmount", taxTotal.subtract(halfTax));
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
                "debit-note-create",
                () -> {
                    String json = ApiRuntime.JSON.writeValueAsString(req);
                    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(apiUrl("/api/debit-notes")))
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
                    AppDialogService.success(tblDebitNotes, "Debit Note Created", "Debit note created and posted to general ledger successfully.");
                    org.example.service.NotificationService.createNotification(
                            org.example.service.NotificationService.Category.PURCHASES,
                            "Debit Note Created",
                            "Debit note created and posted to general ledger successfully.",
                            "INFO",
                            "/fxml/pages/DebitNoteRegister.fxml",
                            null
                    );
                    refreshData();
                },
                err -> AppDialogService.error(tblDebitNotes, "Debit Notes", "Creation Failed", err.getMessage())
            );
        });
    }

    private void editNote(DebitNoteRow row) {
        if (row == null) return;
        showDetails(row);
    }

    private void deleteNote(DebitNoteRow row) {
        if (row == null) return;
        if (!AppDialogService.confirm(tblDebitNotes, "Delete Debit Note",
                "Delete Debit Note " + row.debitNoteNo + "?",
                "This will void the debit note, reverse GL postings, and restore inventory cleanly.")) {
            return;
        }

        UiTaskExecutor.submitAction(
            "debit-note-delete-" + row.id,
            () -> {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(apiUrl("/api/debit-notes/" + row.id)))
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
                AppDialogService.success(tblDebitNotes, "Deleted", "Debit Note deleted safely.");
                refreshData();
            },
            err -> AppDialogService.error(tblDebitNotes, "Debit Notes", "Deletion Failed", err.getMessage())
        );
    }

    private void cancelNote(DebitNoteRow row) {
        if (!AppDialogService.confirm(tblDebitNotes, "Cancel Debit Note", "Cancel Debit Note " + row.debitNoteNo + "?", "This will reverse GL postings and restock items.")) {
            return;
        }

        UiTaskExecutor.submitAction(
            "debit-note-cancel-" + row.id,
            () -> {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(apiUrl("/api/debit-notes/" + row.id + "/cancel")))
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
                AppDialogService.success(tblDebitNotes, "Cancelled", "Debit Note cancelled successfully.");
                refreshData();
            },
            err -> AppDialogService.error(tblDebitNotes, "Debit Notes", "Cancellation Failed", err.getMessage())
        );
    }

    @FXML
    public void auditSelected() {
        if (selectedRow != null) {
            ActivityTimelineDialog.show(tblDebitNotes, "DEBIT_NOTE", selectedRow.id != null ? selectedRow.id.intValue() : 0, selectedRow.debitNoteNo);
        }
    }

    @FXML
    public void printBarcodeSelected() {
        if (selectedRow != null) printBarcode(selectedRow);
    }

    private void printBarcode(DebitNoteRow row) {
        if (row == null) return;
        OrderDispatchBarcodeDialog.showForDebitNote(
            tblDebitNotes,
            row.debitNoteNo,
            row.noteDate,
            row.partyName,
            "",
            row.originalPurchaseNo,
            row.reasonCode,
            row.totalAmount != null ? row.totalAmount.doubleValue() : 0.0
        );
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public static class DebitNoteRow {
        public Long id;
        public String debitNoteNo;
        public String noteDate;
        @com.fasterxml.jackson.annotation.JsonAlias({"supplierName", "partyName"})
        public String partyName;
        @com.fasterxml.jackson.annotation.JsonAlias({"supplierGstin", "partyGstin"})
        public String partyGstin;
        @com.fasterxml.jackson.annotation.JsonAlias({"originalBillNo", "originalPurchaseNo"})
        public String originalPurchaseNo;
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
