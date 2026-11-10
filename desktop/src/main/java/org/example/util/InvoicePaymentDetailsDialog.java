package org.example.util;

import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.example.api.ApiRuntime;
import org.example.api.ApiSession;
import org.example.api.bank.BankStatementApiClient;
import org.example.api.returns.ReturnApiClient;
import org.example.api.support.SupportApiClient;
import org.example.config.ConfigManager;
import org.example.controller.CreditNoteRegisterController;
import org.example.controller.DebitNoteRegisterController;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.NumberFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shared, read-only payment and linkage drill-down for Sales and Purchase registers. */
public final class InvoicePaymentDetailsDialog {
    private InvoicePaymentDetailsDialog() {}

    public static void show(Node owner, SupportApiClient api, String documentType, int documentId,
                            String invoiceNo, String partyLabel, String partyName,
                            double total, double paid, double balance) {
        OwnedDialog<ButtonType> dialog = new OwnedDialog<>(owner);
        dialog.setTitle("Invoice Payment Details");
        dialog.setHeaderText(null);
        dialog.getDialogPane().getStyleClass().addAll("invoice-payment-dialog");

        javafx.geometry.Rectangle2D screen = WindowUtilsFx.visualBoundsFor(owner);
        double maxW = Math.max(760, screen.getWidth() - 48);
        double maxH = Math.max(500, screen.getHeight() - 48);
        double targetW = Math.min(maxW, Math.max(760, Math.min(920, screen.getWidth() * 0.78)));
        double targetH = Math.min(maxH, Math.max(460, Math.min(590, screen.getHeight() * 0.78)));
        dialog.getDialogPane().setPrefSize(targetW, targetH);
        dialog.getDialogPane().setMaxWidth(maxW);
        dialog.getDialogPane().setMaxHeight(maxH);
        dialog.setOnShown(ev -> {
            if (dialog.getDialogPane().getScene() != null && dialog.getDialogPane().getScene().getWindow() instanceof javafx.stage.Stage stage) {
                WindowUtilsFx.fitDialogToOwnerScreen(stage, owner);
            }
        });

        Label title = new Label("Invoice Payment Details");
        title.getStyleClass().add("bank-dialog-hero-title");
        Label subtitle = new Label(invoiceNo + "  •  " + partyLabel + ": " + safe(partyName));
        subtitle.getStyleClass().add("bank-dialog-subtitle");
        HBox hero = new HBox(12, IconFactory.icon("payment", 32), new VBox(3, title, subtitle));
        hero.setAlignment(Pos.CENTER_LEFT);
        hero.getStyleClass().add("bank-dialog-hero");
        DocumentInspectionScaffold.installDragPhysics(hero, dialog);

        GridPane metrics = new GridPane();
        metrics.setHgap(12);
        metrics.add(metric("Invoice Total", money(total), "payment-metric-total"), 0, 0);
        metrics.add(metric("Paid", money(paid), "payment-metric-paid"), 1, 0);
        metrics.add(metric("Outstanding", money(balance), balance > .009 ? "payment-metric-pending" : "payment-metric-settled"), 2, 0);
        metrics.add(metric("Payment Status", balance <= .009 ? "PAID" : paid > .009 ? "PARTIAL" : "PENDING",
                balance <= .009 ? "payment-metric-settled" : "payment-metric-pending"), 3, 0);
        for (Node child : metrics.getChildren()) {
            GridPane.setHgrow(child, Priority.ALWAYS);
            if (child instanceof Region region) region.setMaxWidth(Double.MAX_VALUE);
        }

        TableView<SupportApiClient.PaymentRow> table = new TableView<>();
        table.getStyleClass().addAll("professional-table", "approved-table", "invoice-payment-table", "erp-table-profile-dialog");
        table.setPlaceholder(new Label("Loading payment records…"));
        TableColumn<SupportApiClient.PaymentRow, String> paymentRefCol = new TableColumn<>("Payment Ref");
        paymentRefCol.setPrefWidth(155);
        paymentRefCol.setCellValueFactory(value -> new ReadOnlyStringWrapper(safe(value.getValue().paymentReference())));
        IconFactory.applyTableHeaderIcon(paymentRefCol, "link");
        paymentRefCol.setCellFactory(ignored -> new javafx.scene.control.TableCell<>() {
            private final javafx.scene.control.Hyperlink link = new javafx.scene.control.Hyperlink();
            {
                link.getStyleClass().add("table-link");
                link.setOnAction(e -> {
                    int idx = getIndex();
                    if (idx >= 0 && idx < getTableView().getItems().size()) {
                        SupportApiClient.PaymentRow row = getTableView().getItems().get(idx);
                        dialog.setResult(ButtonType.CLOSE);
                        dialog.close();
                        if ("PURCHASE".equalsIgnoreCase(documentType)) {
                            if (invoiceNo != null && !invoiceNo.isBlank()) {
                                org.example.controller.PurchaseScreenContext.select(invoiceNo);
                            }
                            org.example.navigation.NavigationManager.getInstance().loadPage("/fxml/pages/PurchasePayment.fxml");
                        } else {
                            if (invoiceNo != null && !invoiceNo.isBlank()) {
                                org.example.controller.SalesScreenContext.select(invoiceNo);
                            }
                            org.example.navigation.NavigationManager.getInstance().loadPage("/fxml/pages/RecordPayment.fxml");
                        }
                    }
                });
            }
            @Override protected void updateItem(String val, boolean empty) {
                super.updateItem(val, empty);
                if (empty || val == null || val.isBlank() || "—".equals(val)) {
                    setGraphic(null);
                    setText(empty ? null : "—");
                } else {
                    link.setText(val);
                    setText(null);
                    setGraphic(link);
                }
            }
        });
        TableColumn<SupportApiClient.PaymentRow, String> date = textColumn("Date", "calendar", SupportApiClient.PaymentRow::date);
        TableColumn<SupportApiClient.PaymentRow, String> reference = textColumn("Reference", "document", SupportApiClient.PaymentRow::reference);
        TableColumn<SupportApiClient.PaymentRow, String> from = textColumn("Received From / Paid To", "customer", SupportApiClient.PaymentRow::receivedFrom);
        TableColumn<SupportApiClient.PaymentRow, String> mode = textColumn("Mode", "payment", SupportApiClient.PaymentRow::mode);
        TableColumn<SupportApiClient.PaymentRow, Number> amount = new TableColumn<>("Amount");
        amount.setCellValueFactory(value -> new ReadOnlyObjectWrapper<>(value.getValue().amount()));
        amount.setCellFactory(ignored -> new javafx.scene.control.TableCell<>() {
            @Override protected void updateItem(Number value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty || value == null ? null : money(value.doubleValue()));
                setAlignment(Pos.CENTER_RIGHT);
                getStyleClass().removeAll("register-total-amount");
                if (!empty) getStyleClass().add("register-total-amount");
            }
        });
        IconFactory.applyTableHeaderIcon(amount, "currency");
        TableColumn<SupportApiClient.PaymentRow, String> notes = textColumn("Notes", "notes", SupportApiClient.PaymentRow::notes);
        table.getColumns().addAll(paymentRefCol, date, reference, from, mode, amount, notes);
        DynamicTableLayoutManager.install(table);
        VBox.setVgrow(table, Priority.ALWAYS);

        Label count = new Label("Loading payment history…");
        count.getStyleClass().add("muted-label");
        Label totalRecorded = new Label("");
        totalRecorded.getStyleClass().add("payment-history-total");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox footer = new HBox(10, count, spacer, totalRecorded);
        footer.setAlignment(Pos.CENTER_LEFT);

        dialog.getDialogPane().setContent(new VBox(12, hero, metrics, table, footer));
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        final SupportApiClient client = api != null ? api : new SupportApiClient();
        UiTaskExecutor.submitLatest("invoice-payment-" + documentType + "-" + documentId,
                () -> client.payments(documentType, documentId),
                payments -> {
                    List<SupportApiClient.PaymentRow> list = payments != null ? payments : List.of();
                    table.getItems().setAll(list);
                    if (list.isEmpty()) {
                        table.setPlaceholder(new Label("No payment records have been recorded for this invoice."));
                    }
                    count.setText(list.size() + (list.size() == 1 ? " payment record" : " payment records"));
                    totalRecorded.setText("Recorded total: " + money(list.stream().mapToDouble(SupportApiClient.PaymentRow::amount).sum()));
                },
                err -> {
                    table.setPlaceholder(new Label("Unable to load payment history: " + err.getMessage()));
                    count.setText("Error loading payments");
                });

        dialog.showAndWait();
    }

    public static void showDocumentLinkagesDialog(Node owner, String documentType, long documentId,
                                                  String invoiceNo, String partyLabel, String partyName) {
        OwnedDialog<ButtonType> dialog = new OwnedDialog<>(owner);
        dialog.setTitle("Linked Records — " + safe(invoiceNo));
        dialog.setHeaderText(null);
        dialog.getDialogPane().getStyleClass().addAll("invoice-payment-dialog");
        PopupTableWorkspace.prepareDialog(dialog, 1040);
        dialog.getDialogPane().setPrefHeight(620);
        dialog.getDialogPane().setMinHeight(520);

        Label title = new Label("Linked Financial & Bank Records");
        title.getStyleClass().add("bank-dialog-hero-title");
        Label subtitle = new Label(safe(invoiceNo) + "  •  " + partyLabel + ": " + safe(partyName));
        subtitle.getStyleClass().add("bank-dialog-subtitle");
        HBox hero = new HBox(12, IconFactory.icon("link", 32), new VBox(3, title, subtitle));
        hero.setAlignment(Pos.CENTER_LEFT);
        hero.getStyleClass().add("bank-dialog-hero");

        Node linkagesNode = buildDocumentLinkagesNode(dialog, documentType, documentId, invoiceNo);
        VBox.setVgrow(linkagesNode, Priority.ALWAYS);

        VBox contentBox = new VBox(12, hero, linkagesNode);
        contentBox.setPadding(new javafx.geometry.Insets(0, 0, 10, 0));
        dialog.getDialogPane().setContent(contentBox);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.showAndWait();
    }

    public record LinkedReturnNoteRow(
            Long id,
            String docType,
            String docNo,
            String date,
            String party,
            String reason,
            double amount,
            String status,
            String refundStatus
    ) {}

    public static Node buildDocumentLinkagesNode(javafx.scene.control.Dialog<?> parentDialog,
                                                 String documentType, long documentId, String invoiceNo) {
        boolean isSales = "SALE".equalsIgnoreCase(documentType) || "SALES".equalsIgnoreCase(documentType);

        // Sub-Tab 1: Returns & Financial Notes
        TableView<LinkedReturnNoteRow> tableReturns = new TableView<>();
        tableReturns.getStyleClass().addAll("professional-table", "approved-table", "invoice-payment-table", "erp-table-standard");
        tableReturns.setPlaceholder(new Label("Loading linked Returns and Financial Notes for " + safe(invoiceNo) + "…"));
        tableReturns.setFixedCellSize(36.0);

        TableColumn<LinkedReturnNoteRow, String> colRetDocType = new TableColumn<>("Document Type");
        colRetDocType.setPrefWidth(140);
        colRetDocType.setCellValueFactory(v -> new ReadOnlyStringWrapper(safe(v.getValue().docType())));
        IconFactory.applyTableHeaderIcon(colRetDocType, "document");

        TableColumn<LinkedReturnNoteRow, String> colRetDocNo = new TableColumn<>("Document No.");
        colRetDocNo.setPrefWidth(160);
        colRetDocNo.setCellValueFactory(v -> new ReadOnlyStringWrapper(safe(v.getValue().docNo())));
        IconFactory.applyTableHeaderIcon(colRetDocNo, "link");
        colRetDocNo.setCellFactory(ignored -> new javafx.scene.control.TableCell<>() {
            private final javafx.scene.control.Hyperlink link = new javafx.scene.control.Hyperlink();
            {
                link.getStyleClass().add("table-link");
                link.setOnAction(e -> {
                    int idx = getIndex();
                    if (idx >= 0 && idx < getTableView().getItems().size()) {
                        openLinkedReturnNote(parentDialog, getTableView().getItems().get(idx), documentType);
                    }
                });
            }
            @Override protected void updateItem(String val, boolean empty) {
                super.updateItem(val, empty);
                if (empty || val == null || val.isBlank()) {
                    setGraphic(null); setText(null);
                } else {
                    link.setText(val); setText(null); setGraphic(link);
                }
            }
        });

        TableColumn<LinkedReturnNoteRow, String> colRetDate = new TableColumn<>("Date");
        colRetDate.setPrefWidth(110);
        colRetDate.setCellValueFactory(v -> new ReadOnlyStringWrapper(safe(v.getValue().date())));
        IconFactory.applyTableHeaderIcon(colRetDate, "calendar");

        TableColumn<LinkedReturnNoteRow, String> colRetParty = new TableColumn<>(isSales ? "Customer" : "Supplier");
        colRetParty.setPrefWidth(180);
        colRetParty.setCellValueFactory(v -> new ReadOnlyStringWrapper(safe(v.getValue().party())));
        IconFactory.applyTableHeaderIcon(colRetParty, isSales ? "customer" : "supplier");

        TableColumn<LinkedReturnNoteRow, String> colRetReason = new TableColumn<>("Reason / Notes");
        colRetReason.setPrefWidth(180);
        colRetReason.setCellValueFactory(v -> new ReadOnlyStringWrapper(safe(v.getValue().reason())));
        IconFactory.applyTableHeaderIcon(colRetReason, "notes");

        TableColumn<LinkedReturnNoteRow, String> colRetAmount = new TableColumn<>("Amount");
        colRetAmount.setPrefWidth(130);
        colRetAmount.setCellValueFactory(v -> new ReadOnlyStringWrapper(money(v.getValue().amount())));
        colRetAmount.setCellFactory(c -> SemanticTableCells.moneyString("green"));
        IconFactory.applyTableHeaderIcon(colRetAmount, "currency");

        TableColumn<LinkedReturnNoteRow, String> colRetStatus = new TableColumn<>("Status");
        colRetStatus.setPrefWidth(130);
        colRetStatus.setCellValueFactory(v -> new ReadOnlyStringWrapper(safe(v.getValue().status())));
        colRetStatus.setCellFactory(x -> SemanticTableCells.status("return"));
        IconFactory.applyTableHeaderIcon(colRetStatus, "status");

        TableColumn<LinkedReturnNoteRow, String> colRetRefund = new TableColumn<>("Refund / Settlement");
        colRetRefund.setPrefWidth(135);
        colRetRefund.setCellValueFactory(v -> new ReadOnlyStringWrapper(safe(v.getValue().refundStatus())));
        colRetRefund.setCellFactory(x -> SemanticTableCells.status("refund"));
        IconFactory.applyTableHeaderIcon(colRetRefund, "payment");

        TableColumn<LinkedReturnNoteRow, Void> colRetAction = new TableColumn<>("Action");
        colRetAction.setPrefWidth(85);
        IconFactory.applyTableHeaderIcon(colRetAction, "actions");
        colRetAction.setCellFactory(ignored -> new javafx.scene.control.TableCell<>() {
            private final Button btnOpen = new Button("Open");
            {
                btnOpen.getStyleClass().addAll("approved-button", "approved-secondary-button");
                btnOpen.setGraphic(IconFactory.compactIcon("view", 12));
                btnOpen.setOnAction(e -> {
                    int idx = getIndex();
                    if (idx >= 0 && idx < getTableView().getItems().size()) {
                        openLinkedReturnNote(parentDialog, getTableView().getItems().get(idx), documentType);
                    }
                });
            }
            @Override protected void updateItem(Void val, boolean empty) {
                super.updateItem(val, empty);
                setGraphic(empty ? null : btnOpen);
                setAlignment(Pos.CENTER);
            }
        });

        tableReturns.getColumns().addAll(colRetDocType, colRetDocNo, colRetDate, colRetParty, colRetReason, colRetAmount, colRetStatus, colRetRefund, colRetAction);
        DynamicTableLayoutManager.install(tableReturns);
        VBox.setVgrow(tableReturns, Priority.ALWAYS);

        // Sub-Tab 2: Bank & Statement Reconciliations
        TableView<BankStatementApiClient.LinkageItemDto> table = new TableView<>();
        table.getStyleClass().addAll("professional-table", "approved-table", "invoice-payment-table", "erp-table-standard");
        table.setPlaceholder(new Label("Loading linked Bank Statement, Payment Ref (PRF), Bank Entry & Expense records…"));
        table.setFixedCellSize(36.0);

        TableColumn<BankStatementApiClient.LinkageItemDto, String> colStmtRef = new TableColumn<>("Statement Ref");
        colStmtRef.setPrefWidth(155);
        colStmtRef.setCellValueFactory(v -> new ReadOnlyStringWrapper(safe(v.getValue().statementReference())));
        IconFactory.applyTableHeaderIcon(colStmtRef, "bank");
        colStmtRef.setCellFactory(ignored -> new javafx.scene.control.TableCell<>() {
            private final javafx.scene.control.Hyperlink link = new javafx.scene.control.Hyperlink();
            {
                link.getStyleClass().add("table-link");
                link.setOnAction(e -> {
                    int idx = getIndex();
                    if (idx >= 0 && idx < getTableView().getItems().size()) {
                        navigateToStatementFromLinkage(parentDialog, invoiceNo, getTableView().getItems().get(idx));
                    }
                });
            }
            @Override protected void updateItem(String val, boolean empty) {
                super.updateItem(val, empty);
                if (empty || val == null || val.isBlank() || "—".equals(val)) {
                    setGraphic(null);
                    setText(empty ? null : "—");
                } else {
                    link.setText(val);
                    setText(null);
                    setGraphic(link);
                }
            }
        });

        TableColumn<BankStatementApiClient.LinkageItemDto, String> colPmtRef = new TableColumn<>("Payment Ref");
        colPmtRef.setPrefWidth(155);
        colPmtRef.setCellValueFactory(v -> new ReadOnlyStringWrapper(safe(v.getValue().paymentReference())));
        IconFactory.applyTableHeaderIcon(colPmtRef, "payment");
        colPmtRef.setCellFactory(ignored -> new javafx.scene.control.TableCell<>() {
            private final javafx.scene.control.Hyperlink link = new javafx.scene.control.Hyperlink();
            {
                link.getStyleClass().add("table-link");
                link.setOnAction(e -> {
                    int idx = getIndex();
                    if (idx >= 0 && idx < getTableView().getItems().size()) {
                        navigateToPaymentFromLinkage(parentDialog, invoiceNo, documentType, getTableView().getItems().get(idx));
                    }
                });
            }
            @Override protected void updateItem(String val, boolean empty) {
                super.updateItem(val, empty);
                if (empty || val == null || val.isBlank() || "—".equals(val)) {
                    setGraphic(null);
                    setText(empty ? null : "—");
                } else {
                    link.setText(val);
                    setText(null);
                    setGraphic(link);
                }
            }
        });

        TableColumn<BankStatementApiClient.LinkageItemDto, String> colFinRef = new TableColumn<>("Finance Voucher");
        colFinRef.setPrefWidth(155);
        colFinRef.setCellValueFactory(v -> new ReadOnlyStringWrapper(safe(v.getValue().financeVoucherNo())));
        IconFactory.applyTableHeaderIcon(colFinRef, "link");
        colFinRef.setCellFactory(ignored -> new javafx.scene.control.TableCell<>() {
            private final javafx.scene.control.Hyperlink link = new javafx.scene.control.Hyperlink();
            {
                link.getStyleClass().add("table-link");
                link.setOnAction(e -> {
                    int idx = getIndex();
                    if (idx >= 0 && idx < getTableView().getItems().size()) {
                        BankStatementApiClient.LinkageItemDto item = getTableView().getItems().get(idx);
                        if (item.financeEntryId() != null) {
                            closeDialogHierarchy(parentDialog);
                            String vType = item.financeVoucherType() == null ? "" : item.financeVoucherType().toUpperCase(Locale.ROOT);
                            org.example.controller.BankExpenseController.requestLinkedEntry(
                                    vType.contains("EXPENSE") ? org.example.controller.BankExpenseController.Mode.EXPENSE : org.example.controller.BankExpenseController.Mode.BANK,
                                    item.financeEntryId()
                            );
                            org.example.navigation.NavigationManager.getInstance().loadPage("/fxml/pages/BankExpense.fxml");
                        }
                    }
                });
            }
            @Override protected void updateItem(String val, boolean empty) {
                super.updateItem(val, empty);
                if (empty || val == null || val.isBlank() || "—".equals(val)) {
                    setGraphic(null);
                    setText(empty ? null : "—");
                } else {
                    link.setText(val);
                    setText(null);
                    setGraphic(link);
                }
            }
        });

        TableColumn<BankStatementApiClient.LinkageItemDto, String> colDate = new TableColumn<>("Date");
        colDate.setPrefWidth(105);
        colDate.setCellValueFactory(v -> new ReadOnlyStringWrapper(safe(v.getValue().transactionDate())));
        IconFactory.applyTableHeaderIcon(colDate, "calendar");

        TableColumn<BankStatementApiClient.LinkageItemDto, String> colBankRef = new TableColumn<>("Bank / Mode / UTR");
        colBankRef.setPrefWidth(180);
        colBankRef.setCellValueFactory(v -> {
            var item = v.getValue();
            String b = item.bankName() == null ? "" : item.bankName();
            String r = item.originalReference() == null ? "" : item.originalReference();
            return new ReadOnlyStringWrapper(safe(b.isBlank() ? r : (r.isBlank() ? b : b + " • " + r)));
        });
        IconFactory.applyTableHeaderIcon(colBankRef, "document");

        TableColumn<BankStatementApiClient.LinkageItemDto, String> colAmount = new TableColumn<>("Allocated Amount");
        colAmount.setPrefWidth(130);
        colAmount.setCellValueFactory(v -> new ReadOnlyStringWrapper(money(v.getValue().allocatedAmount())));
        IconFactory.applyTableHeaderIcon(colAmount, "currency");

        TableColumn<BankStatementApiClient.LinkageItemDto, String> colStatus = new TableColumn<>("Status / Settled By");
        colStatus.setPrefWidth(175);
        colStatus.setCellValueFactory(v -> {
            var item = v.getValue();
            String st = item.status() == null ? "" : item.status();
            String by = item.settledBy() == null || item.settledBy().isBlank() ? "" : (" (User: " + item.settledBy() + ")");
            return new ReadOnlyStringWrapper(safe(st + by));
        });
        IconFactory.applyTableHeaderIcon(colStatus, "status");

        table.getColumns().addAll(colPmtRef, colStmtRef, colFinRef, colDate, colBankRef, colAmount, colStatus);
        DynamicTableLayoutManager.install(table);
        VBox.setVgrow(table, Priority.ALWAYS);

        Tab tabReturns = new Tab("Returns & Financial Notes");
        tabReturns.setGraphic(IconFactory.compactIcon("return", 14));
        tabReturns.setContent(tableReturns);

        Tab tabBank = new Tab("Bank & Statement Reconciliations");
        tabBank.setGraphic(IconFactory.compactIcon("bank", 14));
        tabBank.setContent(table);

        TabPane linkagesTabPane = new TabPane(tabReturns, tabBank);
        linkagesTabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        linkagesTabPane.getStyleClass().addAll("erp-inspection-tabs", "approved-tab-pane");
        VBox.setVgrow(linkagesTabPane, Priority.ALWAYS);

        // Async Load: Returns and Notes
        UiTaskExecutor.submitLatest("doc-linkages-returns-" + documentType + "-" + invoiceNo,
                () -> {
                    String returnType = isSales ? "SALES RETURN" : "PURCHASE RETURN";
                    List<LinkedReturnNoteRow> linkedRows = new ArrayList<>();
                    try {
                        List<ReturnApiClient.Summary> returns = new ReturnApiClient().allFiltered(returnType, invoiceNo, "", "", "", "");
                        if (returns != null) {
                            for (var r : returns) {
                                if (r.invoice() != null && r.invoice().equalsIgnoreCase(invoiceNo)) {
                                    linkedRows.add(new LinkedReturnNoteRow(
                                            null,
                                            isSales ? "Sales Return" : "Purchase Return",
                                            r.no(),
                                            safe(r.date()),
                                            r.party(),
                                            r.reason(),
                                            r.total(),
                                            r.status(),
                                            r.refundStatus()
                                    ));
                                }
                            }
                        }
                    } catch (Exception ignored) {}

                    try {
                        String notesEndpoint = isSales ? "/api/credit-notes" : "/api/debit-notes";
                        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(ConfigManager.getDataApiBaseUrl() + notesEndpoint))
                                .timeout(Duration.ofSeconds(10))
                                .header("Accept", "application/json")
                                .GET();
                        ApiSession.authorize(req);
                        HttpResponse<String> resp = ApiRuntime.HTTP.send(req.build(), HttpResponse.BodyHandlers.ofString());
                        if (resp.statusCode() == 200) {
                            if (isSales) {
                                List<CreditNoteRegisterController.CreditNoteRow> notes = ApiRuntime.JSON.readValue(resp.body(), new com.fasterxml.jackson.core.type.TypeReference<List<CreditNoteRegisterController.CreditNoteRow>>() {});
                                if (notes != null) {
                                    for (var n : notes) {
                                        if (n.originalInvoiceNo != null && n.originalInvoiceNo.equalsIgnoreCase(invoiceNo)) {
                                            linkedRows.add(new LinkedReturnNoteRow(
                                                    n.id,
                                                    "Credit Note",
                                                    n.creditNoteNo,
                                                    safe(n.noteDate),
                                                    n.partyName,
                                                    n.reasonCode,
                                                    n.totalAmount != null ? n.totalAmount.doubleValue() : 0.0,
                                                    n.status,
                                                    "—"
                                            ));
                                        }
                                    }
                                }
                            } else {
                                List<DebitNoteRegisterController.DebitNoteRow> notes = ApiRuntime.JSON.readValue(resp.body(), new com.fasterxml.jackson.core.type.TypeReference<List<DebitNoteRegisterController.DebitNoteRow>>() {});
                                if (notes != null) {
                                    for (var n : notes) {
                                        if (n.originalPurchaseNo != null && n.originalPurchaseNo.equalsIgnoreCase(invoiceNo)) {
                                            linkedRows.add(new LinkedReturnNoteRow(
                                                    n.id,
                                                    "Debit Note",
                                                    n.debitNoteNo,
                                                    safe(n.noteDate),
                                                    n.partyName,
                                                    n.reasonCode,
                                                    n.totalAmount != null ? n.totalAmount.doubleValue() : 0.0,
                                                    n.status,
                                                    "—"
                                            ));
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                    return linkedRows;
                },
                rows -> {
                    tableReturns.getItems().setAll(rows);
                    tabReturns.setText("Returns & Financial Notes (" + rows.size() + ")");
                    if (rows.isEmpty()) {
                        tableReturns.setPlaceholder(new Label("No Returns or Financial Notes issued against " + safe(invoiceNo) + "."));
                    } else {
                        linkagesTabPane.getSelectionModel().select(tabReturns);
                    }
                },
                err -> tableReturns.setPlaceholder(new Label("Failed to load linked returns: " + err.getMessage()))
        );

        // Async Load: Bank Statement Linkages
        Integer docIdInt = documentId > 0 ? (int) documentId : null;
        UiTaskExecutor.submitLatest("doc-linkages-bank-" + documentType + "-" + documentId + "-" + invoiceNo,
                () -> new BankStatementApiClient().linkage(documentType, docIdInt, invoiceNo, null, null),
                view -> {
                    List<BankStatementApiClient.LinkageItemDto> items = view != null && view.linkages() != null ? view.linkages() : List.of();
                    table.getItems().setAll(items);
                    tabBank.setText("Bank & Statement Reconciliations (" + items.size() + ")");
                    if (items.isEmpty()) {
                        table.setPlaceholder(new Label("No linked Bank Statement, Bank Entry, Expense Entry, or Payment records found for " + safe(invoiceNo) + "."));
                    }
                },
                err -> table.setPlaceholder(new Label("Unable to load linked records: " + err.getMessage()))
        );

        return linkagesTabPane;
    }

    private static void openLinkedReturnNote(javafx.scene.control.Dialog<?> parentDialog, LinkedReturnNoteRow item, String documentType) {
        if (item == null) return;
        boolean isSales = "SALE".equalsIgnoreCase(documentType) || "SALES".equalsIgnoreCase(documentType);
        Node ownerNode = parentDialog != null ? parentDialog.getDialogPane() : null;
        if ("Debit Note".equalsIgnoreCase(item.docType()) || "Credit Note".equalsIgnoreCase(item.docType())) {
            CreditDebitNoteInspectionDialog.show(ownerNode, item.id(), item.docNo(), isSales, null, null, null);
        } else {
            ReturnDocumentInspectionDialog.show(ownerNode, item.docNo(), isSales, new ReturnApiClient(), new SupportApiClient(), null);
        }
    }

    private static void closeDialogHierarchy(javafx.scene.control.Dialog<?> dialog) {
        if (dialog == null) return;
        try {
            dialog.setResult(null);
            dialog.close();
            if (dialog.getDialogPane() != null && dialog.getDialogPane().getScene() != null) {
                var window = dialog.getDialogPane().getScene().getWindow();
                if (window instanceof javafx.stage.Stage stage) {
                    stage.close();
                }
            }
        } catch (Exception ignored) {}
    }

    private static void navigateToStatementFromLinkage(javafx.scene.control.Dialog<?> parentDialog,
                                                       String invoiceNo,
                                                       BankStatementApiClient.LinkageItemDto item) {
        if (item == null) return;
        if (item.statementTransactionId() != null && item.statementTransactionId() > 0) {
            closeDialogHierarchy(parentDialog);
            org.example.controller.LinkedRecordContext.open(
                    "BANK_STATEMENT",
                    item.statementTransactionId().intValue(),
                    item.statementReference() == null || item.statementReference().isBlank() ? item.originalReference() : item.statementReference(),
                    "VIEW",
                    safe(invoiceNo)
            );
            org.example.navigation.NavigationManager.getInstance().loadPage("/fxml/pages/BankStatement.fxml");
        } else {
            String user = item.settledBy() == null || item.settledBy().isBlank() ? "ADMIN" : item.settledBy();
            String pmtRef = item.paymentReference() == null || item.paymentReference().isBlank() ? safe(item.originalReference()) : item.paymentReference();
            new OwnedAlert(javafx.scene.control.Alert.AlertType.WARNING,
                    "No Bank Statement entry exists for Payment Ref " + pmtRef + " as it was settled manually by USER: " + user).showAndWait();
        }
    }

    private static void navigateToPaymentFromLinkage(javafx.scene.control.Dialog<?> parentDialog,
                                                     String invoiceNo,
                                                     String fallbackDocumentType,
                                                     BankStatementApiClient.LinkageItemDto item) {
        if (item == null) return;
        String type = item.targetType() != null && !item.targetType().isBlank() ? item.targetType() : fallbackDocumentType;
        if (type == null) type = "SALE";
        String docNo = item.documentNo() != null && !item.documentNo().isBlank() ? item.documentNo() : invoiceNo;

        closeDialogHierarchy(parentDialog);

        if ("PURCHASE".equalsIgnoreCase(type)) {
            if (docNo != null && !docNo.isBlank()) {
                org.example.controller.PurchaseScreenContext.select(docNo);
            }
            org.example.navigation.NavigationManager.getInstance().loadPage("/fxml/pages/PurchasePayment.fxml");
        } else {
            if (docNo != null && !docNo.isBlank()) {
                org.example.controller.SalesScreenContext.select(docNo);
            }
            org.example.navigation.NavigationManager.getInstance().loadPage("/fxml/pages/RecordPayment.fxml");
        }
    }

    private static VBox metric(String label, String value, String style) {
        Label caption = new Label(label);
        caption.getStyleClass().add("bank-dialog-label");
        Label amount = new Label(value);
        amount.getStyleClass().addAll("bank-dialog-metric-value", style);
        VBox box = new VBox(4, caption, amount);
        box.getStyleClass().add("bank-dialog-metric-card");
        return box;
    }

    private static TableColumn<SupportApiClient.PaymentRow, String> textColumn(
            String title, String semantic, java.util.function.Function<SupportApiClient.PaymentRow, String> getter) {
        TableColumn<SupportApiClient.PaymentRow, String> column = new TableColumn<>(title);
        column.setCellValueFactory(value -> new ReadOnlyStringWrapper(safe(getter.apply(value.getValue()))));
        IconFactory.applyTableHeaderIcon(column, semantic);
        return column;
    }

    private static String money(double value) {
        return NumberFormat.getCurrencyInstance(Locale.of("en", "IN")).format(value).replace("₹", "₹ ");
    }

    private static String safe(String value) { return value == null || value.isBlank() ? "—" : value; }
}
