package org.example.util;

import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.example.api.authority.BusinessEmailClient;
import org.example.api.authority.BusinessEmailClient.OutboxItemDto;
import org.example.api.authority.BusinessEmailClient.OutboxPage;

import java.util.List;

/**
 * Interactive Email Outbox & Auto-Retry Inspector for Administrators.
 * Shows SENT / RETRYING / PENDING / FAILED deliveries, backoff schedule, and 1-click Retry.
 */
public final class EmailOutboxDialog {

    private EmailOutboxDialog() {}

    public static void show(Node owner) {
        BusinessEmailClient client = new BusinessEmailClient();
        OwnedDialog<Void> dialog = new OwnedDialog<>(owner);
        dialog.setTitle("Email Outbox & Auto-Retry Queue");
        dialog.setResizable(true);
        AppDialogRenderer.configureWorkspace(
                dialog,
                "email",
                "Email Outbox & Auto-Retry Queue",
                "Persistent SMTP delivery log, automatic backoff retry status, and manual resend",
                "Failed transient emails automatically retry in the background (2m -> 10m -> 30m).",
                ""
        );

        Label lblTotal = new Label("0");
        Label lblSent = new Label("0");
        Label lblRetrying = new Label("0");
        Label lblFailed = new Label("0");
        HBox metrics = PopupTableWorkspace.metricStrip(
                metricCard("Total Queued", lblTotal, "email"),
                metricCard("Delivered", lblSent, "complete"),
                metricCard("Auto-Retrying", lblRetrying, "clock"),
                metricCard("Failed / Action Needed", lblFailed, "warning")
        );

        ComboBox<String> cmbStatus = new ComboBox<>(FXCollections.observableArrayList("ALL", "FAILED", "RETRYING", "PENDING", "SENT"));
        cmbStatus.setValue("ALL");
        cmbStatus.getStyleClass().add("approved-combo");

        TextField txtSearch = new TextField();
        txtSearch.setPromptText("Search recipient, CC, subject, reference, or error...");
        txtSearch.getStyleClass().addAll("approved-input", "search-field");
        HBox.setHgrow(txtSearch, Priority.ALWAYS);

        Button btnRefresh = new Button("Refresh");
        UiActionIcons.apply(btnRefresh, "refresh", "Refresh outbox");

        HBox filterBar = new HBox(10, cmbStatus, txtSearch, btnRefresh);
        filterBar.setAlignment(Pos.CENTER_LEFT);

        TableView<OutboxItemDto> table = new TableView<>();
        table.getStyleClass().add("approved-table");
        table.setPlaceholder(new Label("No email outbox entries recorded yet."));
        VBox.setVgrow(table, Priority.ALWAYS);

        TableColumn<OutboxItemDto, String> colStatus = new TableColumn<>("Status");
        colStatus.setCellValueFactory(c -> new SimpleStringProperty(safe(c.getValue().status())));
        colStatus.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                Label badge = new Label(item);
                badge.getStyleClass().add("status-badge");
                if ("SENT".equalsIgnoreCase(item)) badge.getStyleClass().add("status-positive");
                else if ("FAILED".equalsIgnoreCase(item)) badge.getStyleClass().add("status-negative");
                else badge.getStyleClass().add("status-warning");
                setGraphic(badge);
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                setAlignment(Pos.CENTER);
            }
        });

        TableColumn<OutboxItemDto, String> colCreated = new TableColumn<>("Created");
        colCreated.setCellValueFactory(c -> new SimpleStringProperty(formatTs(c.getValue().createdAt())));

        TableColumn<OutboxItemDto, String> colRecipient = new TableColumn<>("Recipient / Copies");
        colRecipient.setCellValueFactory(c -> {
            var it = c.getValue();
            String copy = (it.recipientCc() != null && !it.recipientCc().isBlank() ? " | CC: " + it.recipientCc() : "")
                    + (it.recipientBcc() != null && !it.recipientBcc().isBlank() ? " | BCC: " + it.recipientBcc() : "");
            return new SimpleStringProperty(safe(it.recipientTo()) + copy);
        });

        TableColumn<OutboxItemDto, String> colSubject = new TableColumn<>("Subject");
        colSubject.setCellValueFactory(c -> new SimpleStringProperty(safe(c.getValue().subject())));

        TableColumn<OutboxItemDto, String> colRef = new TableColumn<>("Reference");
        colRef.setCellValueFactory(c -> {
            String ref = safe(c.getValue().referenceNo());
            return new SimpleStringProperty(ref.isBlank() ? safe(c.getValue().entityType()) : ref);
        });

        TableColumn<OutboxItemDto, Number> colAttempts = new TableColumn<>("Attempts");
        colAttempts.setCellValueFactory(c -> new SimpleIntegerProperty(c.getValue().attemptCount()));

        TableColumn<OutboxItemDto, String> colError = new TableColumn<>("Error / Next Retry");
        colError.setCellValueFactory(c -> {
            var it = c.getValue();
            if ("SENT".equalsIgnoreCase(it.status())) {
                return new SimpleStringProperty("Delivered " + formatTs(it.sentAt()));
            }
            String err = safe(it.lastError());
            String next = it.nextRetryAt() != null && !it.nextRetryAt().isBlank() ? " • Next: " + formatTs(it.nextRetryAt()) : "";
            return new SimpleStringProperty((err.isBlank() ? "Pending" : err) + next);
        });

        TableColumn<OutboxItemDto, Void> colAction = new TableColumn<>("Action");
        final Runnable[] reloadRef = new Runnable[1];
        colAction.setCellFactory(c -> new TableCell<>() {
            private final Button btnRetry = new Button("Retry");
            {
                UiActionIcons.apply(btnRetry, "refresh", "Retry sending email now");
                btnRetry.setOnAction(e -> {
                    OutboxItemDto row = getTableRow() == null ? null : getTableRow().getItem();
                    if (row == null) return;
                    btnRetry.setDisable(true);
                    UiTaskExecutor.submitActionWithOverlay(
                            table,
                            "Retrying Email Delivery...",
                            "retry-email-outbox-" + row.id(),
                            () -> client.retryOutbox(row.id()),
                            res -> {
                                if (res.success()) {
                                    AppDialogService.success(table, "Email Delivered", res.message());
                                } else {
                                    AppDialogService.warning(table, "Retry Failed", "Delivery attempt failed", res.message());
                                }
                                if (reloadRef[0] != null) reloadRef[0].run();
                            },
                            err -> {
                                AppDialogService.error(table, "Retry Error", err.getMessage());
                                if (reloadRef[0] != null) reloadRef[0].run();
                            }
                    );
                });
            }

            @Override
            protected void updateItem(Void v, boolean empty) {
                super.updateItem(v, empty);
                OutboxItemDto row = empty || getTableRow() == null ? null : getTableRow().getItem();
                if (row == null || "SENT".equalsIgnoreCase(row.status())) {
                    setGraphic(null);
                    return;
                }
                btnRetry.setDisable(false);
                setGraphic(btnRetry);
                setAlignment(Pos.CENTER);
            }
        });

        table.getColumns().setAll(List.of(colStatus, colCreated, colRecipient, colSubject, colRef, colAttempts, colError, colAction));
        DynamicTableLayoutManager.install(table);

        reloadRef[0] = () -> UiTaskExecutor.submitLatest(
                "load-email-outbox",
                () -> client.outbox(cmbStatus.getValue(), txtSearch.getText(), 0, 100),
                page -> {
                    if (page != null && page.summary() != null) {
                        lblTotal.setText(Long.toString(page.summary().total()));
                        lblSent.setText(Long.toString(page.summary().sent()));
                        lblRetrying.setText(Long.toString(page.summary().retrying() + page.summary().pending()));
                        lblFailed.setText(Long.toString(page.summary().failed()));
                    }
                    table.setItems(FXCollections.observableArrayList(page != null && page.rows() != null ? page.rows() : List.of()));
                },
                err -> AppDialogService.error(table, "Outbox Unavailable", err.getMessage())
        );

        cmbStatus.setOnAction(e -> reloadRef[0].run());
        btnRefresh.setOnAction(e -> reloadRef[0].run());
        RealtimeSearchSupport.installRemote(txtSearch, () -> reloadRef[0].run());

        VBox content = new VBox(12, metrics, filterBar, table);
        content.setPadding(new Insets(12));
        content.setPrefSize(1060, 620);

        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE);
        PopupTableWorkspace.prepareDialog(dialog, 1080);
        reloadRef[0].run();
        dialog.showAndWait();
    }

    private static VBox metricCard(String caption, Label valueLabel, String semantic) {
        HBox head = new HBox(6, IconFactory.compactIcon(semantic, 15), new Label(caption));
        head.setAlignment(Pos.CENTER_LEFT);
        valueLabel.getStyleClass().addAll("approved-kpi-value", "metric-value");
        VBox box = new VBox(4, head, valueLabel);
        box.getStyleClass().addAll("approved-kpi", "standard-kpi-card");
        box.setPadding(new Insets(10, 14, 10, 14));
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    private static String formatTs(String raw) {
        if (raw == null || raw.isBlank()) return "—";
        try {
            return BusinessClock.formatTimestamp(raw.trim());
        } catch (Exception ignored) {
            return raw.trim();
        }
    }

    private static String safe(String v) {
        return v == null ? "" : v.trim();
    }
}
