package org.example.util;

import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.example.api.bank.BankStatementApiClient;
import org.example.api.support.SupportApiClient;

import java.text.NumberFormat;
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
        dialog.getDialogPane().setPrefSize(920, 590);

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

    public static Node buildDocumentLinkagesNode(javafx.scene.control.Dialog<?> parentDialog,
                                                 String documentType, long documentId, String invoiceNo) {
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

        Label countLabel = new Label("Loading linked records…");
        countLabel.getStyleClass().add("muted-label");
        countLabel.setWrapText(true);
        HBox footer = new HBox(10, countLabel);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.setPadding(new javafx.geometry.Insets(4, 0, 8, 0));

        VBox box = new VBox(8, table, footer);
        VBox.setVgrow(box, Priority.ALWAYS);

        Integer docIdInt = documentId > 0 ? (int) documentId : null;
        UiTaskExecutor.submitLatest("doc-linkages-" + documentType + "-" + documentId + "-" + invoiceNo,
                () -> new BankStatementApiClient().linkage(documentType, docIdInt, invoiceNo, null, null),
                view -> {
                    List<BankStatementApiClient.LinkageItemDto> items = view != null && view.linkages() != null ? view.linkages() : List.of();
                    table.getItems().setAll(items);
                    if (items.isEmpty()) {
                        table.setPlaceholder(new Label("No linked Bank Statement, Bank Entry, Expense Entry, or Payment records found for " + safe(invoiceNo) + "."));
                    }
                    countLabel.setText(items.size() + " linked record(s) found • Click Payment Ref, Statement Ref, or Finance Voucher to navigate");
                },
                err -> {
                    table.setPlaceholder(new Label("Unable to load linked records: " + err.getMessage()));
                    countLabel.setText("Error loading linked records");
                });
        return box;
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
