package org.example.controller;

import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.example.api.operations.OperationsApiClient;
import org.example.api.quotation.QuotationApiClient;
import org.example.api.returns.ReturnApiClient;
import org.example.api.support.SupportApiClient;
import org.example.navigation.NavigationManager;
import org.example.navigation.ScreenLifecycle;
import org.example.service.BrandedRegisterPdfService;
import org.example.service.BrandingService;
import org.example.service.EmailService;
import org.example.service.ManagedInvoicePdfService;
import org.example.service.MessageTemplateService;
import org.example.service.PermissionService;
import org.example.service.PurchaseService;
import org.example.service.SalesService;
import org.example.service.SessionService;
import org.example.util.*;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;

public class CommunicationCenterController implements ScreenLifecycle {

    private final SupportApiClient supportApi = new SupportApiClient();
    private final OperationsApiClient operationsApi = new OperationsApiClient();
    private final QuotationApiClient quotationApi = new QuotationApiClient();
    private final ReturnApiClient returnApi = new ReturnApiClient();
    private final FxDebouncer searchDebouncer = new FxDebouncer(Duration.ofMillis(200));

    @FXML private StackPane communicationPageIcon, communicationTotalIcon, communicationSuccessIcon, communicationFailedIcon, communicationEmailIcon, communicationWhatsappIcon;
    @FXML private HBox cardTotal, cardSuccess, cardFailed, cardEmail, cardWhatsapp, paginationHost;
    @FXML private Label lblTotalHeading, lblTotal, lblSuccess, lblDeliveryRate, lblFailed, lblEmailCount, lblEmailRecipientsNote, lblWhatsappCount;
    @FXML private TextField txtSearch;
    @FXML private ComboBox<String> cmbChannel, cmbStatus;
    @FXML private DatePicker dpFrom, dpTo;
    @FXML private Button btnPresetAll, btnPresetToday, btnPresetYesterday, btnPreset7Days, btnPreset30Days;
    @FXML private Button btnExportExcel, btnExportPdf, btnReset, btnRefresh;
    @FXML private TableView<Row> table;
    @FXML private TableColumn<Row, String> colTime, colEntity, colChannel, colRecipient, colSubject, colStatus, colAttempts, colError, colUser;
    @FXML private TableColumn<Row, Void> colActions;

    private List<Row> all = List.of();
    private List<Row> filteredRows = List.of();
    private TablePaginationBar<Row> paginationBar;
    private Runnable refreshFinish;

    @FXML
    public void initialize() {
        installKpiAndToolbarIcons();
        configureKpiQuickFilters();
        DatePickerFormatter.attachAll(dpFrom, dpTo);

        colTime.setCellValueFactory(v -> v.getValue().time);
        colEntity.setCellValueFactory(v -> v.getValue().entity);
        colChannel.setCellValueFactory(v -> v.getValue().channel);
        colRecipient.setCellValueFactory(v -> v.getValue().recipient);
        colSubject.setCellValueFactory(v -> v.getValue().subject);
        colStatus.setCellValueFactory(v -> v.getValue().status);
        if (colAttempts != null) {
            colAttempts.setCellValueFactory(v -> v.getValue().attempts);
        }
        colError.setCellValueFactory(v -> v.getValue().error);
        colUser.setCellValueFactory(v -> v.getValue().user);

        configureTableColumns();
        configureActions();
        configureTableInteractions();

        if (paginationHost != null) {
            paginationBar = new TablePaginationBar<>(table, 25);
            HBox.setHgrow(paginationBar, Priority.ALWAYS);
            paginationHost.getChildren().setAll(paginationBar);
        }

        IconFactory.applyTableHeaderIcon(colTime, "calendar");
        IconFactory.applyTableHeaderIcon(colEntity, "document");
        IconFactory.applyTableHeaderIcon(colChannel, "communication");
        IconFactory.applyTableHeaderIcon(colRecipient, "email");
        IconFactory.applyTableHeaderIcon(colSubject, "notes");
        IconFactory.applyTableHeaderIcon(colStatus, "status");
        if (colAttempts != null) IconFactory.applyTableHeaderIcon(colAttempts, "refresh");
        IconFactory.applyTableHeaderIcon(colError, "info");
        IconFactory.applyTableHeaderIcon(colUser, "user");
        if (colActions != null) IconFactory.applyTableHeaderIcon(colActions, "actions");

        cmbChannel.getItems().setAll("All Channels", "EMAIL", "WHATSAPP");
        cmbChannel.setValue("All Channels");
        cmbStatus.getItems().setAll("All Statuses", "SENT", "FAILED");
        cmbStatus.setValue("All Statuses");

        txtSearch.textProperty().addListener((o, a, b) -> searchDebouncer.submit(this::filter));
        cmbChannel.valueProperty().addListener((o, a, b) -> filter());
        cmbStatus.valueProperty().addListener((o, a, b) -> filter());
        if (dpFrom != null) dpFrom.valueProperty().addListener((o, a, b) -> filter());
        if (dpTo != null) dpTo.valueProperty().addListener((o, a, b) -> filter());

        DynamicTableLayoutManager.install(table);
        ProfessionalUiEnhancer.refreshTableDecorations(table);
        loadSilently();
    }

    private void installKpiAndToolbarIcons() {
        if (communicationPageIcon != null) communicationPageIcon.getChildren().setAll(IconFactory.icon("communication", 24));
        setKpiIcon(communicationTotalIcon, "communication");
        setKpiIcon(communicationSuccessIcon, "complete");
        setKpiIcon(communicationFailedIcon, "error");
        setKpiIcon(communicationEmailIcon, "email");
        setKpiIcon(communicationWhatsappIcon, "whatsapp");

        // Pin communication icon on Total Activity label so it never auto-infers currency (₹)
        if (lblTotalHeading != null) {
            lblTotalHeading.setGraphic(IconFactory.compactIcon("communication", 14));
            lblTotalHeading.getProperties().put("erp.icon.explicit", Boolean.TRUE);
        }

        boolean canExport = PermissionService.allowed("COMMUNICATION.EXPORT");
        if (btnExportExcel != null) {
            btnExportExcel.setGraphic(IconFactory.compactIcon("excel", 15));
            btnExportExcel.setDisable(!canExport);
        }
        if (btnExportPdf != null) {
            btnExportPdf.setGraphic(IconFactory.compactIcon("pdf", 15));
            btnExportPdf.setDisable(!canExport);
        }
        if (btnReset != null) btnReset.setGraphic(IconFactory.compactIcon("cancel", 15));
        if (btnRefresh != null) btnRefresh.setGraphic(IconFactory.compactIcon("refresh", 15));
    }

    private void configureKpiQuickFilters() {
        if (cardTotal != null) cardTotal.setOnMouseClicked(e -> { cmbChannel.setValue("All Channels"); cmbStatus.setValue("All Statuses"); });
        if (cardSuccess != null) cardSuccess.setOnMouseClicked(e -> cmbStatus.setValue("SENT"));
        if (cardFailed != null) cardFailed.setOnMouseClicked(e -> cmbStatus.setValue("FAILED"));
        if (cardEmail != null) cardEmail.setOnMouseClicked(e -> cmbChannel.setValue("EMAIL"));
        if (cardWhatsapp != null) cardWhatsapp.setOnMouseClicked(e -> cmbChannel.setValue("WHATSAPP"));
    }

    private void configureTableColumns() {
        // Document column: sleek borderless Hyperlink for linked documents
        colEntity.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String val, boolean empty) {
                super.updateItem(val, empty);
                Row row = (empty || getTableRow() == null) ? null : getTableRow().getItem();
                if (empty || row == null || val == null || val.isBlank()) {
                    setGraphic(null);
                    setText(null);
                    setTooltip(null);
                    return;
                }
                if (canOpenLinkedDocument(row)) {
                    Hyperlink link = new Hyperlink(val);
                    link.setMinWidth(Region.USE_PREF_SIZE);
                    link.getStyleClass().add("erp-inline-doc-link");
                    link.setTooltip(IconFactory.createTooltip("Open linked document " + val));
                    link.setOnAction(e -> {
                        link.setVisited(false);
                        openLinkedDocument(row);
                    });
                    setGraphic(link);
                    setText(null);
                } else {
                    setGraphic(null);
                    setText(val);
                    setTooltip(IconFactory.createTooltip(val));
                }
                setAlignment(Pos.CENTER_LEFT);
            }
        });

        // Channel column: semantic pill badge
        colChannel.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String val, boolean empty) {
                super.updateItem(val, empty);
                if (empty || val == null || val.isBlank()) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                boolean isEmail = "EMAIL".equalsIgnoreCase(val);
                Label badge = new Label(val.toUpperCase(Locale.ROOT));
                badge.setMinWidth(Region.USE_PREF_SIZE);
                badge.setGraphic(IconFactory.compactIcon(isEmail ? "email" : "whatsapp", 12));
                badge.getStyleClass().addAll("comm-channel-badge", isEmail ? "comm-channel-email" : "comm-channel-whatsapp");
                setGraphic(badge);
                setText(null);
                setAlignment(Pos.CENTER);
            }
        });

        // Recipient column: zero-clipping primary address + clickable "+N more" pill + full tooltip
        colRecipient.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String val, boolean empty) {
                super.updateItem(val, empty);
                Row row = (empty || getTableRow() == null) ? null : getTableRow().getItem();
                if (empty || row == null) {
                    setGraphic(null);
                    setText(null);
                    setTooltip(null);
                    return;
                }
                List<String> recips = row.recipients;
                if (recips.isEmpty()) {
                    setGraphic(null);
                    setText("—");
                    setTooltip(null);
                    return;
                }
                String fullTooltip = String.join("\n", recips);
                Label lblPrimary = new Label(recips.get(0));
                lblPrimary.getStyleClass().add("comm-recipient-primary");

                if (recips.size() > 1) {
                    int extra = recips.size() - 1;
                    Button morePill = new Button("+" + extra + " more");
                    morePill.setMinWidth(Region.USE_PREF_SIZE);
                    morePill.getStyleClass().add("comm-more-pill");
                    morePill.setTooltip(IconFactory.createTooltip("All " + recips.size() + " recipients:\n" + fullTooltip + "\n\nClick to inspect all recipients"));
                    morePill.setOnAction(e -> inspectRow(row));
                    HBox box = new HBox(6, lblPrimary, morePill);
                    box.setAlignment(Pos.CENTER_LEFT);
                    setGraphic(box);
                } else {
                    setGraphic(lblPrimary);
                }
                setText(null);
                setTooltip(IconFactory.createTooltip(fullTooltip));
                setAlignment(Pos.CENTER_LEFT);
            }
        });

        // Subject column: full hover tooltip so nothing is lost
        colSubject.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String val, boolean empty) {
                super.updateItem(val, empty);
                if (empty || val == null || val.isBlank()) {
                    setText(empty ? null : "—");
                    setTooltip(null);
                } else {
                    setText(val);
                    setTooltip(IconFactory.createTooltip(val));
                }
            }
        });

        // Status column: semantic pill badge
        colStatus.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String val, boolean empty) {
                super.updateItem(val, empty);
                if (empty || val == null || val.isBlank()) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                boolean failed = "FAILED".equalsIgnoreCase(val);
                Label badge = new Label(val.toUpperCase(Locale.ROOT));
                badge.setMinWidth(Region.USE_PREF_SIZE);
                badge.setGraphic(IconFactory.compactIcon(failed ? "error" : "complete", 12));
                badge.getStyleClass().addAll("comm-status-badge", failed ? "comm-status-failed" : "comm-status-sent");
                setGraphic(badge);
                setText(null);
                setAlignment(Pos.CENTER);
            }
        });

        // Error / Result column: full tooltip on hover
        colError.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String val, boolean empty) {
                super.updateItem(val, empty);
                getStyleClass().removeAll("status-negative", "muted-label");
                if (empty || val == null || val.isBlank()) {
                    setText(empty ? null : "—");
                    if (!empty) getStyleClass().add("muted-label");
                    setTooltip(null);
                } else {
                    setText(val);
                    getStyleClass().add("status-negative");
                    setTooltip(IconFactory.createTooltip(val));
                }
            }
        });
    }

    private void configureTableInteractions() {
        table.setRowFactory(tv -> {
            TableRow<Row> row = new TableRow<>();
            MenuItem miInspect = new MenuItem("Inspect Details & Recipients", IconFactory.compactIcon("view", 16));
            MenuItem miOpenDoc = new MenuItem("Open Linked Document", IconFactory.compactIcon("reference", 16));
            MenuItem miResend = new MenuItem("Re-send Communication", IconFactory.compactIcon("refresh", 16));
            MenuItem miCopy = new MenuItem("Copy All Recipients", IconFactory.compactIcon("duplicate", 16));
            ContextMenu menu = new ContextMenu(miInspect, miOpenDoc, new SeparatorMenuItem(), miResend, miCopy);
            IconFactory.decorateActionMenu(menu);

            miInspect.setOnAction(e -> { if (!row.isEmpty()) inspectRow(row.getItem()); });
            miOpenDoc.setOnAction(e -> { if (!row.isEmpty()) openLinkedDocument(row.getItem()); });
            miResend.setOnAction(e -> { if (!row.isEmpty()) resend(row.getItem()); });
            miCopy.setOnAction(e -> { if (!row.isEmpty()) copyRecipients(row.getItem()); });

            row.setOnContextMenuRequested(e -> {
                if (row.isEmpty()) { menu.hide(); return; }
                table.getSelectionModel().select(row.getItem());
                miOpenDoc.setDisable(!canOpenLinkedDocument(row.getItem()));
                miCopy.setDisable(row.getItem().recipients.isEmpty());
                menu.show(row, e.getScreenX(), e.getScreenY());
                e.consume();
            });

            row.setOnMouseClicked(e -> {
                if (row.isEmpty() || e.getButton() != MouseButton.PRIMARY || e.getClickCount() != 1
                        || RegisterUiSupport.isInteractiveTableTarget(e.getPickResult().getIntersectedNode(), row)) {
                    return;
                }
                table.getSelectionModel().select(row.getItem());
                inspectRow(row.getItem());
                e.consume();
            });
            return row;
        });
    }

    private void setKpiIcon(StackPane pane, String semantic) {
        if (pane != null) pane.getChildren().setAll(IconFactory.compactIcon(semantic, 22));
    }

    @FXML
    public void refresh() {
        loadCommunications(true);
    }

    private void loadSilently() {
        loadCommunications(false);
    }

    private void loadCommunications(boolean withFeedback) {
        if (withFeedback && btnRefresh != null) {
            if (refreshFinish != null) return;
            refreshFinish = OperationalUiSupport.beginRefresh(btnRefresh, "Refreshing…");
        }
        UiTaskExecutor.submitLatest(
                "communication-load",
                this::readRows,
                rows -> {
                    if (refreshFinish != null) { refreshFinish.run(); refreshFinish = null; }
                    applyRows(rows);
                },
                error -> {
                    if (refreshFinish != null) { refreshFinish.run(); refreshFinish = null; }
                    new OwnedAlert(Alert.AlertType.ERROR, error.getMessage()).showAndWait();
                }
        );
    }

    private List<Row> readRows() {
        List<Row> x = new ArrayList<>();
        var rawList = supportApi.communications();
        Map<String, Integer> counts = new HashMap<>();
        for (var r : rawList) {
            String key = groupKey(r.entityType(), r.entityId(), r.channel());
            counts.put(key, counts.getOrDefault(key, 0) + 1);
        }

        Map<String, Integer> currentIdx = new HashMap<>();
        for (var r : rawList) {
            String key = groupKey(r.entityType(), r.entityId(), r.channel());
            int idx = currentIdx.getOrDefault(key, 0) + 1;
            currentIdx.put(key, idx);
            int totalForEntity = counts.getOrDefault(key, 1);
            String attemptText = (totalForEntity <= 1) ? "Original"
                    : (idx == 1 ? "Original (1/" + totalForEntity + ")" : "Resend #" + (idx - 1) + " (" + idx + "/" + totalForEntity + ")");
            x.add(new Row(
                    BusinessClock.formatTimestamp(r.createdAt()),
                    BusinessClock.localDateOfTimestamp(r.createdAt()),
                    r.documentLabel(),
                    r.entityType(),
                    r.entityId(),
                    r.channel(),
                    r.recipient(),
                    r.subject(),
                    r.status(),
                    r.errorMessage(),
                    r.createdBy(),
                    attemptText,
                    totalForEntity
            ));
        }
        return x;
    }

    private void applyRows(List<Row> x) {
        long started = System.nanoTime();
        all = x == null ? List.of() : List.copyOf(x);
        int totalCount = all.size();
        long successCount = all.stream().filter(r -> !"FAILED".equalsIgnoreCase(r.status.get())).count();
        long failedCount = totalCount - successCount;
        long emailCount = all.stream().filter(r -> "EMAIL".equalsIgnoreCase(r.channel.get())).count();
        long whatsappCount = all.stream().filter(r -> "WHATSAPP".equalsIgnoreCase(r.channel.get())).count();
        long emailRecipientsTotal = all.stream()
                .filter(r -> "EMAIL".equalsIgnoreCase(r.channel.get()))
                .mapToLong(r -> Math.max(1, r.recipients.size()))
                .sum();

        if (lblTotal != null) lblTotal.setText(String.valueOf(totalCount));
        if (lblSuccess != null) lblSuccess.setText(String.valueOf(successCount));
        if (lblDeliveryRate != null) {
            double rate = totalCount == 0 ? 100.0 : (successCount * 100.0 / totalCount);
            lblDeliveryRate.setText(String.format(Locale.ENGLISH, "%.1f%%", rate));
        }
        if (lblFailed != null) lblFailed.setText(String.valueOf(failedCount));
        if (lblEmailCount != null) lblEmailCount.setText(String.valueOf(emailCount));
        if (lblEmailRecipientsNote != null) {
            lblEmailRecipientsNote.setText(emailRecipientsTotal + (emailRecipientsTotal == 1 ? " recipient reached" : " recipients reached"));
        }
        if (lblWhatsappCount != null) lblWhatsappCount.setText(String.valueOf(whatsappCount));

        filter();
        long ms = (System.nanoTime() - started) / 1_000_000L;
        if (ms >= 20) PerformanceMonitor.event("controller-phase", "communication-apply | " + ms + " ms");
    }

    private void filter() {
        String q = txtSearch == null || txtSearch.getText() == null ? "" : txtSearch.getText().trim().toLowerCase(Locale.ROOT);
        String channel = cmbChannel == null || cmbChannel.getValue() == null ? "All Channels" : cmbChannel.getValue();
        String status = cmbStatus == null || cmbStatus.getValue() == null ? "All Statuses" : cmbStatus.getValue();
        LocalDate from = dpFrom == null ? null : dpFrom.getValue();
        LocalDate to = dpTo == null ? null : dpTo.getValue();

        filteredRows = all.stream()
                .filter(r -> q.isBlank() || (r.entity.get() + " " + r.recipient.get() + " " + r.subject.get() + " " + r.error.get() + " " + r.user.get()).toLowerCase(Locale.ROOT).contains(q))
                .filter(r -> channel.startsWith("All") || r.channel.get().equalsIgnoreCase(channel))
                .filter(r -> status.startsWith("All") || r.status.get().equalsIgnoreCase(status))
                .filter(r -> {
                    if (from == null && to == null) return true;
                    if (r.localDate == null) return true;
                    if (from != null && r.localDate.isBefore(from)) return false;
                    if (to != null && r.localDate.isAfter(to)) return false;
                    return true;
                })
                .toList();

        if (paginationBar != null) {
            paginationBar.setAllItems(filteredRows);
        } else {
            table.getItems().setAll(filteredRows);
        }
    }

    @FXML private void presetAllDates() {
        if (dpFrom != null) dpFrom.setValue(null);
        if (dpTo != null) dpTo.setValue(null);
        markActivePreset(btnPresetAll);
        filter();
    }

    @FXML private void presetToday() {
        LocalDate today = BusinessClock.today();
        if (dpFrom != null) dpFrom.setValue(today);
        if (dpTo != null) dpTo.setValue(today);
        markActivePreset(btnPresetToday);
        filter();
    }

    @FXML private void presetYesterday() {
        LocalDate y = BusinessClock.today().minusDays(1);
        if (dpFrom != null) dpFrom.setValue(y);
        if (dpTo != null) dpTo.setValue(y);
        markActivePreset(btnPresetYesterday);
        filter();
    }

    @FXML private void preset7Days() {
        LocalDate today = BusinessClock.today();
        if (dpFrom != null) dpFrom.setValue(today.minusDays(6));
        if (dpTo != null) dpTo.setValue(today);
        markActivePreset(btnPreset7Days);
        filter();
    }

    @FXML private void preset30Days() {
        LocalDate today = BusinessClock.today();
        if (dpFrom != null) dpFrom.setValue(today.minusDays(29));
        if (dpTo != null) dpTo.setValue(today);
        markActivePreset(btnPreset30Days);
        filter();
    }

    private void markActivePreset(Button active) {
        for (Button b : List.of(btnPresetAll, btnPresetToday, btnPresetYesterday, btnPreset7Days, btnPreset30Days)) {
            if (b == null) continue;
            b.getStyleClass().remove("comm-preset-active");
            if (b == active) b.getStyleClass().add("comm-preset-active");
        }
    }

    @FXML
    private void resetFilters() {
        if (txtSearch != null) txtSearch.clear();
        if (cmbChannel != null) cmbChannel.setValue("All Channels");
        if (cmbStatus != null) cmbStatus.setValue("All Statuses");
        presetAllDates();
    }

    private void configureActions() {
        if (colActions == null) return;
        colActions.setCellFactory(c -> new TableCell<>() {
            final MenuButton menu = new MenuButton("Actions");
            final MenuItem miInspect = new MenuItem("Inspect Details", IconFactory.compactIcon("view", 16));
            final MenuItem miOpenDoc = new MenuItem("Open Linked Document", IconFactory.compactIcon("reference", 16));
            final MenuItem miResend = new MenuItem("Re-send", IconFactory.compactIcon("refresh", 16));
            final MenuItem miCopy = new MenuItem("Copy Recipients", IconFactory.compactIcon("duplicate", 16));

            {
                miInspect.setOnAction(e -> { Row r = currentRow(); if (r != null) inspectRow(r); });
                miOpenDoc.setOnAction(e -> { Row r = currentRow(); if (r != null) openLinkedDocument(r); });
                miResend.setOnAction(e -> { Row r = currentRow(); if (r != null) resend(r); });
                miCopy.setOnAction(e -> { Row r = currentRow(); if (r != null) copyRecipients(r); });
                menu.getItems().addAll(miInspect, miOpenDoc, new SeparatorMenuItem(), miResend, miCopy);
                menu.getStyleClass().add("row-actions");
                menu.setGraphic(IconFactory.compactIcon("actions", 16));
                menu.setContentDisplay(ContentDisplay.LEFT);
                menu.setGraphicTextGap(6);
                menu.setTooltip(IconFactory.createTooltip("Communication actions"));
                menu.setOnShowing(e -> {
                    Row r = currentRow();
                    miOpenDoc.setDisable(r == null || !canOpenLinkedDocument(r));
                    miCopy.setDisable(r == null || r.recipients.isEmpty());
                });
                IconFactory.decorateActionMenu(menu);
            }

            private Row currentRow() {
                return (getTableRow() == null || getTableRow().isEmpty()) ? null : getTableRow().getItem();
            }

            @Override
            protected void updateItem(Void v, boolean empty) {
                super.updateItem(v, empty);
                if (empty) {
                    setGraphic(null);
                    return;
                }
                Row row = currentRow();
                if (row != null) {
                    miOpenDoc.setDisable(!canOpenLinkedDocument(row));
                    miCopy.setDisable(row.recipients.isEmpty());
                }
                setGraphic(menu);
                setAlignment(Pos.CENTER);
            }
        });
    }

    /** Shared approved secondary Re-send control factory (preserves central icon & theme contract). */
    public Button createStandardResendButton(Row row) {
        Button resend = new Button("Re-send");
        resend.getStyleClass().addAll("approved-button","approved-secondary-button","communication-resend-button");
        UiActionIcons.apply(resend,"refresh", "Re-send communication with draft preview & live status");
        resend.setOnAction(e -> { if (row != null) resend(row); });
        return resend;
    }

    private void inspectRow(Row row) {
        if (row == null) return;
        String key = groupKey(row.entityType, row.entityId, row.channel.get());
        List<CommunicationInspectionDialog.CommunicationSnapshot> related = all.stream()
                .filter(r -> groupKey(r.entityType, r.entityId, r.channel.get()).equals(key))
                .map(Row::toSnapshot)
                .toList();
        Runnable openDocAction = canOpenLinkedDocument(row) ? () -> openLinkedDocument(row) : null;
        CommunicationInspectionDialog.show(table, row.toSnapshot(), related, openDocAction, () -> resend(row));
    }

    private boolean canOpenLinkedDocument(Row row) {
        if (row == null || row.entity.get() == null || row.entity.get().isBlank()) return false;
        String type = row.entityType == null ? "" : row.entityType.toUpperCase(Locale.ROOT);
        String doc = row.entity.get().trim();
        return switch (type) {
            case "SALE", "PURCHASE", "QUOTATION", "SALES_RETURN", "PURCHASE_RETURN", "SALES RETURN", "PURCHASE RETURN" -> true;
            default -> doc.startsWith("IN/") || doc.startsWith("PUR/") || doc.startsWith("QTN/") || doc.startsWith("SRT/") || doc.startsWith("PRT/");
        };
    }

    private void openLinkedDocument(Row row) {
        if (!canOpenLinkedDocument(row)) return;
        String doc = row.entity.get().trim();
        String type = row.entityType == null ? "" : row.entityType.toUpperCase(Locale.ROOT);

        if ("SALE".equals(type) || doc.startsWith("IN/")) {
            UiTaskExecutor.submitLatest("comm-open-sale-" + doc,
                    () -> operationsApi.sale(doc),
                    sale -> {
                        if (sale != null) SalesDocumentInspectionDialog.show(table, sale, supportApi, this::refresh);
                        else AppDialogService.warning(table, "Document Not Found", "Sales invoice " + doc + " could not be loaded.");
                    },
                    err -> AppDialogService.error(table, "Cannot Load Sale", err.getMessage()));
        } else if ("PURCHASE".equals(type) || doc.startsWith("PUR/")) {
            UiTaskExecutor.submitLatest("comm-open-purchase-" + doc,
                    () -> operationsApi.purchase(doc),
                    purchase -> {
                        if (purchase != null) PurchaseDocumentInspectionDialog.show(table, purchase, supportApi, this::refresh);
                        else AppDialogService.warning(table, "Document Not Found", "Purchase invoice " + doc + " could not be loaded.");
                    },
                    err -> AppDialogService.error(table, "Cannot Load Purchase", err.getMessage()));
        } else if ("QUOTATION".equals(type) || doc.startsWith("QTN/")) {
            UiTaskExecutor.submitLatest("comm-open-quote-" + doc,
                    () -> quotationApi.quoteByNumber(doc),
                    quote -> {
                        if (quote != null) QuotationDocumentInspectionDialog.show(table, quote, quotationApi, supportApi, this::refresh);
                        else AppDialogService.warning(table, "Document Not Found", "Quotation " + doc + " could not be loaded.");
                    },
                    err -> AppDialogService.error(table, "Cannot Load Quotation", err.getMessage()));
        } else if (type.contains("RETURN") || doc.startsWith("SRT/") || doc.startsWith("PRT/")) {
            boolean isSalesReturn = type.contains("SALE") || doc.startsWith("SRT/");
            ReturnDocumentInspectionDialog.show(table, doc, isSalesReturn, returnApi, supportApi, this::refresh);
        }
    }

    private void copyRecipients(Row row) {
        if (row == null || row.recipients.isEmpty()) return;
        String joined = String.join("; ", row.recipients);
        ClipboardContent cc = new ClipboardContent();
        cc.putString(joined);
        Clipboard.getSystemClipboard().setContent(cc);
        AppDialogService.success(table, "Copied", "Copied " + row.recipients.size() + " recipient address(es) to clipboard.");
    }

    @FXML
    private void exportExcel() {
        PermissionService.require("COMMUNICATION.EXPORT", "export communication logs");
        if (filteredRows.isEmpty()) {
            AppDialogService.warning(table, "No Records", "There are no communication log entries to export.");
            return;
        }
        File out = ExportFeedbackManager.resolveWorkspaceExportFile("Reports", "Communication_Center_Log", "xlsx");
        if (!ExportFeedbackManager.checkFileWriteable(table, out)) return;

        try (Workbook wb = new XSSFWorkbook(); FileOutputStream fos = new FileOutputStream(out)) {
            Sheet sh = wb.createSheet("Communication Log");
            String[] headers = {"Date & Time", "Document", "Channel", "Recipient Count", "All Recipients", "Subject", "Status", "Attempt", "Result / Error", "Sent By"};
            org.apache.poi.ss.usermodel.Row hdr = sh.createRow(0);
            for (int i = 0; i < headers.length; i++) hdr.createCell(i).setCellValue(headers[i]);

            int idx = 1;
            for (Row r : filteredRows) {
                org.apache.poi.ss.usermodel.Row xl = sh.createRow(idx++);
                xl.createCell(0).setCellValue(r.time.get());
                xl.createCell(1).setCellValue(r.entity.get());
                xl.createCell(2).setCellValue(r.channel.get());
                xl.createCell(3).setCellValue(r.recipients.size());
                xl.createCell(4).setCellValue(String.join("; ", r.recipients));
                xl.createCell(5).setCellValue(r.subject.get());
                xl.createCell(6).setCellValue(r.status.get());
                xl.createCell(7).setCellValue(r.attempts.get());
                xl.createCell(8).setCellValue(r.error.get());
                xl.createCell(9).setCellValue(r.user.get());
            }
            wb.write(fos);
            ExportFeedbackManager.notifyExportSuccess(table, out, "Communication Center Log", filteredRows.size());
        } catch (Exception ex) {
            AppDialogService.error(table, "Export Failed", ex.getMessage());
        }
    }

    @FXML
    private void exportPdf() {
        PermissionService.require("COMMUNICATION.EXPORT", "export communication logs");
        if (filteredRows.isEmpty()) {
            AppDialogService.warning(table, "No Records", "There are no communication log entries to export.");
            return;
        }
        File out = ExportFeedbackManager.resolveWorkspaceExportFile("Reports", "Communication_Center_Log", "pdf");
        if (!ExportFeedbackManager.checkFileWriteable(table, out)) return;

        try {
            String[] headings = {"Date & Time", "Document", "Channel", "Recipients", "Subject", "Status", "Attempt", "Sent By"};
            List<String[]> rows = filteredRows.stream().map(r -> new String[]{
                    r.time.get(),
                    r.entity.get(),
                    r.channel.get(),
                    String.join("; ", r.recipients),
                    r.subject.get(),
                    r.status.get(),
                    r.attempts.get(),
                    r.user.get()
            }).toList();
            BrandedRegisterPdfService.export(
                    out.toPath(),
                    "Communication Center Audit Log",
                    headings,
                    rows,
                    new float[]{1.4f, 1.3f, 0.8f, 2.0f, 2.2f, 0.7f, 1.0f, 0.9f}
            );
            ExportFeedbackManager.notifyExportSuccess(table, out, "Communication Center Log PDF", filteredRows.size());
        } catch (Exception ex) {
            AppDialogService.error(table, "PDF Export Failed", ex.getMessage());
        }
    }

    private void resend(Row row) {
        org.example.service.PermissionService.require("COMMUNICATION.RESEND", "re-send a communication");
        String channel = row.channel.get();

        if ("WHATSAPP".equalsIgnoreCase(channel)) {
            resendWhatsapp(row);
        } else {
            resendEmail(row);
        }
    }

    private void resendWhatsapp(Row row) {
        UiTaskExecutor.submitActionWithOverlay(
                table,
                "Preparing WhatsApp transmission…",
                "prep-whatsapp-resend",
                () -> {
                    Path pdf = originalDocumentPdf(row);
                    String phone = row.recipient.get().trim();
                    String message = (row.subject.get() != null && !row.subject.get().isBlank())
                            ? row.subject.get()
                            : ("Document " + row.entity.get() + " from " + BrandingService.applicationName());

                    return new WhatsappTransmissionDialog.WhatsappDraft(
                            "Re-send WhatsApp Document",
                            "Direct delivery via WhatsApp Business Engine to customer mobile",
                            row.entityType != null ? row.entityType : "COMMUNICATION",
                            row.entity.get(),
                            phone,
                            message,
                            pdf,
                            "Connected: Primary Company WhatsApp"
                    );
                },
                draft -> {
                    Optional<WhatsappTransmissionDialog.WhatsappTransmissionResult> opt = WhatsappTransmissionDialog.show(table, draft);
                    if (opt.isEmpty()) return;
                    var result = opt.get();

                    LiveOperationDialog.run(table, "Re-sending WhatsApp Message", "whatsapp", "Connecting to WhatsApp Business Engine...", reporter -> {
                        reporter.stage(1, 3, "Validating recipient " + result.recipientPhone() + "...");
                        var wahaApi = new org.example.api.whatsapp.BusinessWhatsappApiClient();

                        reporter.stage(2, 3, "Transmitting via WhatsApp Business Engine...");
                        org.example.api.whatsapp.BusinessWhatsappApiClient.SendResponse sendResp;

                        if (draft.attachmentPdf() != null && Files.isRegularFile(draft.attachmentPdf())) {
                            sendResp = wahaApi.sendDocument(new org.example.api.whatsapp.BusinessWhatsappApiClient.DocumentSendRequest(
                                    row.entityType, row.entity.get(), row.entityId, null, result.recipientPhone(), result.message(), 0L
                            ));
                        } else {
                            String ph = result.recipientPhone().replaceAll("\\D", "");
                            if (ph.length() == 10) ph = "91" + ph;
                            sendResp = wahaApi.sendText(new org.example.api.whatsapp.BusinessWhatsappApiClient.TextSendRequest(ph, result.message(), null));
                        }

                        if (!sendResp.success()) throw new IllegalStateException(sendResp.message());
                        reporter.stage(3, 3, "Message dispatched via channel " + sendResp.channelCode());

                        supportApi.communication(new SupportApiClient.CommunicationRequest(
                                row.entityType, row.entityId, "WHATSAPP", result.recipientPhone(), row.subject.get(), "SENT", null, (SessionService.current() != null ? SessionService.current().getUsername() : "System")
                        ));
                    }, () -> {
                        AppDialogService.success(table, "WhatsApp Resent", "Document " + row.entity.get() + " was resent successfully via WhatsApp.");
                        refresh();
                    }, error -> new OwnedAlert(Alert.AlertType.ERROR, error.getMessage()).showAndWait());
                },
                failure -> new OwnedAlert(Alert.AlertType.ERROR, "Failed to prepare WhatsApp draft:\n\n" + failure.getMessage()).showAndWait()
        );
    }

    private void resendEmail(Row row) {
        UiTaskExecutor.submitActionWithOverlay(
                table,
                "Preparing email draft…",
                "prep-email-resend",
                () -> {
                    Path pdf = originalDocumentPdf(row);
                    String to = row.recipients.isEmpty() ? row.recipient.get().trim() : String.join("; ", row.recipients);
                    String origSubject = row.subject.get().isBlank() ? row.entity.get() : row.subject.get();
                    int resendNo = Math.max(1, row.totalAttempts);
                    String subject = origSubject.contains("[Resend") ? origSubject : origSubject + " [Resend #" + resendNo + "]";

                    String body = "Dear Valued Customer / Partner,\n\nPlease find attached the official copy of " + row.entity.get()
                            + ".\n\nThis communication is resent from " + BrandingService.applicationName() + " Communication Center.\n\n"
                            + "{Signature}";

                    String docType = row.entityType == null ? "" : row.entityType.toUpperCase(Locale.ROOT);
                    try {
                        if ("SALE".equals(docType)) {
                            var sale = new SalesService().getByInvoice(row.entity.get());
                            if (sale != null) {
                                var formatted = MessageTemplateService.formatSalesEmail(sale);
                                body = formatted.body();
                            }
                        } else if ("PURCHASE".equals(docType)) {
                            var purchase = new PurchaseService().getByInvoice(row.entity.get());
                            if (purchase != null) {
                                var formatted = MessageTemplateService.formatPurchaseEmail(purchase);
                                body = formatted.body();
                            }
                        }
                    } catch (Exception ignored) {}

                    Path sigPath = MessageTemplateService.getOrCreateSignatureImagePath();

                    return new EmailTransmissionDialog.EmailDraft(
                            "Re-send Document Email",
                            "Transmit document " + row.entity.get() + " (supports multiple ; or , separated emails)",
                            row.entityType != null ? row.entityType : "DOCUMENT",
                            row.entity.get(),
                            to,
                            subject,
                            body,
                            pdf,
                            sigPath
                    );
                },
                draft -> {
                    Optional<EmailTransmissionDialog.TransmissionResult> opt = EmailTransmissionDialog.show(table, draft);
                    if (opt.isEmpty()) return;
                    var result = opt.get();

                    LiveOperationDialog.run(table, "Re-sending Email", "email", "Connecting to Mail Transfer Agent...", reporter -> {
                        reporter.stage(1, 3, "Validating mail recipients...");
                        reporter.stage(2, 3, "Dispatching email to " + result.recipientEmail() + "...");

                        EmailService.resend(result.recipientEmail(), result.subject(), result.body(), result.attachmentPdf(), result.signatureImage(), result.senderProfile());
                        reporter.stage(3, 3, "Mail sent successfully.");

                        supportApi.communication(new SupportApiClient.CommunicationRequest(
                                row.entityType, row.entityId, "EMAIL", result.recipientEmail(), result.subject(), "SENT", null, (SessionService.current() != null ? SessionService.current().getUsername() : "System")
                        ));
                    }, () -> {
                        AppDialogService.success(table, "Email Resent", "Document " + row.entity.get() + " was resent successfully to " + result.recipientEmail());
                        refresh();
                    }, error -> new OwnedAlert(Alert.AlertType.ERROR, error.getMessage()).showAndWait());
                },
                failure -> new OwnedAlert(Alert.AlertType.ERROR, "Failed to prepare email draft:\n\n" + failure.getMessage()).showAndWait()
        );
    }

    private Path originalDocumentPdf(Row row) throws Exception {
        String type = row.entityType == null ? "" : row.entityType.toUpperCase(Locale.ROOT);
        String number = row.entity.get();
        return switch (type) {
            case "SALE" -> {
                var sale = new SalesService().getByInvoice(number);
                yield sale == null ? null : ManagedInvoicePdfService.sales(sale);
            }
            case "PURCHASE" -> {
                var purchase = new PurchaseService().getByInvoice(number);
                yield purchase == null ? null : ManagedInvoicePdfService.purchase(purchase);
            }
            case "QUOTATION" -> ManagedInvoicePdfService.quotation(number);
            default -> null;
        };
    }

    @FXML
    private void openEmailSettings() {
        SettingsController.requestSection(SettingsController.Section.EMAIL);
        NavigationManager.getInstance().loadPage("/fxml/pages/Settings.fxml");
    }

    @Override
    public void onScreenShown(boolean reusedFromCache) {
        OperationalUiSupport.focusWorkArea(table);
        if (reusedFromCache) loadSilently();
    }

    @Override
    public void onScreenHidden() {
        UiTaskExecutor.cancelPrefix("communication-");
        searchDebouncer.cancel();
        if (refreshFinish != null) {
            refreshFinish.run();
            refreshFinish = null;
        }
    }

    private static String groupKey(String entityType, int entityId, String channel) {
        return ((entityType == null ? "" : entityType) + "#" + entityId + "#" + (channel == null ? "" : channel)).toUpperCase(Locale.ROOT);
    }

    public static final class Row {
        final SimpleStringProperty time, entity, channel, recipient, subject, status, error, user, attempts;
        final LocalDate localDate;
        final String entityType;
        final int entityId;
        final int totalAttempts;
        final List<String> recipients;

        Row(String timeValue, LocalDate dateValue, String entityValue, String type, int id, String channelValue,
            String recipientValue, String subjectValue, String statusValue, String errorValue, String userValue,
            String attemptsValue, int totalAttemptsValue) {
            time = new SimpleStringProperty(timeValue == null ? "" : timeValue);
            localDate = dateValue;
            entity = new SimpleStringProperty(entityValue == null ? "" : entityValue);
            entityType = type;
            entityId = id;
            channel = new SimpleStringProperty(channelValue == null ? "" : channelValue);
            recipient = new SimpleStringProperty(recipientValue == null ? "" : recipientValue);
            recipients = CommunicationInspectionDialog.parseRecipients(recipientValue);
            subject = new SimpleStringProperty(subjectValue == null ? "" : subjectValue);
            status = new SimpleStringProperty(statusValue == null ? "" : statusValue);
            error = new SimpleStringProperty(errorValue == null ? "" : errorValue);
            user = new SimpleStringProperty(userValue == null ? "" : userValue);
            attempts = new SimpleStringProperty(attemptsValue == null ? "Original" : attemptsValue);
            totalAttempts = totalAttemptsValue;
        }

        Row(String timeValue, String entityValue, String type, int id, String channelValue, String recipientValue, String subjectValue, String statusValue, String errorValue, String userValue) {
            this(timeValue, null, entityValue, type, id, channelValue, recipientValue, subjectValue, statusValue, errorValue, userValue, "Original", 1);
        }

        CommunicationInspectionDialog.CommunicationSnapshot toSnapshot() {
            return new CommunicationInspectionDialog.CommunicationSnapshot(
                    time.get(), entity.get(), entityType, entityId, channel.get(), recipient.get(),
                    subject.get(), status.get(), error.get(), user.get(), attempts.get(), totalAttempts
            );
        }
    }
}
