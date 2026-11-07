package org.example.util;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;
import org.example.config.WorkspaceManager;
import org.example.service.SessionService;
import org.example.theme.ThemeManager;
import org.example.ai.ErpAgentService;
import org.example.service.DiagnosticAutoScanner;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enterprise System Logs & Application Diagnostics Viewer.
 *
 * <p>Centralizes desktop exceptions, background task failures, and server platform
 * events into an authoritative, searchable diagnostics console. Converts raw technical
 * stack traces into plain-English, actionable guidance via {@link UserFacingErrorMapper}.</p>
 */
public final class SystemDiagnosticsDialog {

    private static final DateTimeFormatter DISPLAY_FORMATTER = DateTimeFormatter.ofPattern("dd MMM yyyy hh:mm:ss a", Locale.ROOT);
    private static final Pattern SPRING_BOOT_LOG_PATTERN = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?)\\s+(\\w+)\\s+(?:\\d+\\s+---\\s+)?(?:\\[(.*?)\\]\\s+)?\\[(.*?)\\]\\s+([^:]+?)\\s+:\\s+(.*)$"
    );
    private static final Pattern LEGACY_SERVER_LOG_PATTERN = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?)\\s+\\[(.*?)\\]\\s+(\\w+)\\s+([\\w\\.]+)\\s+-\\s+(.*)$"
    );

    public record DiagnosticRow(
            long id,
            String timeRaw,
            String formattedTime,
            String level,
            String source,
            String component,
            String event,
            String user,
            String friendlyTitle,
            String friendlyExplanation,
            String actionableGuidance,
            String technicalDetail,
            String rawFailure,
            String searchIndex
    ) {
        public DiagnosticRow(
                long id,
                String timeRaw,
                String formattedTime,
                String level,
                String source,
                String component,
                String event,
                String user,
                String friendlyTitle,
                String friendlyExplanation,
                String actionableGuidance,
                String technicalDetail,
                String rawFailure
        ) {
            this(
                    id,
                    timeRaw,
                    formattedTime,
                    level,
                    source,
                    component,
                    event,
                    user,
                    friendlyTitle,
                    friendlyExplanation,
                    actionableGuidance,
                    technicalDetail,
                    rawFailure,
                    (safe(friendlyTitle) + " " + safe(component) + " " + safe(level) + " " +
                     safe(user) + " " + safe(friendlyExplanation) + " " + safe(actionableGuidance) + " " +
                     safe(technicalDetail) + " " + safe(rawFailure)).toLowerCase(Locale.ROOT)
            );
        }
    }

    private SystemDiagnosticsDialog() {}

    public static int getUninspectedErrorCount() {
        return DiagnosticAutoScanner.getUninspectedCount();
    }

    public static void markAsInspected() {
        DiagnosticAutoScanner.markAllInspected();
    }

    public static void show(Node owner) {
        if (owner == null) return;
        markAsInspected();
        if (owner == null) return;

        String windowTitle = "System Logs & Diagnostics — DSE Enterprise";
        DocumentInspectionScaffold.InspectionShell shell =
                DocumentInspectionScaffold.createShell(owner, windowTitle, "diagnostics-dialog");
        Dialog<ButtonType> dialog = shell.dialog();
        DialogPane pane = shell.pane();
        StackPane host = shell.host();

        // ================= HEADER HERO =================
        HBox headerHero = buildHeaderHero(dialog);

        // ================= KPI METRIC STRIP =================
        Label lblTotalVal = new Label("0");
        Label lblErrorsVal = new Label("0");
        Label lblWarningsVal = new Label("0");
        Label lblDesktopVal = new Label("0");
        Label lblServerVal = new Label("0");

        HBox cardTotal = createStandardKpiCard("Total Diagnostic Events", lblTotalVal, "Loaded from disk & server", "kpi-blue", "diagnostic");
        HBox cardErrors = createStandardKpiCard("Critical & Errors", lblErrorsVal, "Require action / inspection", "kpi-red", "error");
        HBox cardWarnings = createStandardKpiCard("System Warnings", lblWarningsVal, "Non-fatal alerts & retries", "kpi-orange", "warning");
        HBox cardDesktop = createStandardKpiCard("Desktop Client", lblDesktopVal, "UI thread & task executor", "kpi-teal", "application");
        HBox cardServer = createStandardKpiCard("Server Platform", lblServerVal, "Spring REST & database engine", "kpi-purple", "database");

        HBox kpiStrip = new HBox(10, cardTotal, cardErrors, cardWarnings, cardDesktop, cardServer);
        kpiStrip.getStyleClass().addAll("erp-popup-metric-strip", ResponsiveKpiLayoutManager.KPI_SECTION_STYLE, "erp-kpi-single-row");
        HBox.setHgrow(cardTotal, Priority.ALWAYS);
        HBox.setHgrow(cardErrors, Priority.ALWAYS);
        HBox.setHgrow(cardWarnings, Priority.ALWAYS);
        HBox.setHgrow(cardDesktop, Priority.ALWAYS);
        HBox.setHgrow(cardServer, Priority.ALWAYS);
        ResponsiveKpiLayoutManager.install(kpiStrip);

        // ================= TOOLBAR & SEARCH =================
        TextField txtSearch = new TextField();
        txtSearch.setPromptText("Search error messages, components, users, or SQL state…");
        txtSearch.getStyleClass().addAll("search-field", "table-search-input");
        txtSearch.setPrefWidth(300);
        HBox.setHgrow(txtSearch, Priority.ALWAYS);

        ComboBox<String> cmbLevel = new ComboBox<>();
        cmbLevel.getItems().addAll("All Levels", "ERROR Only", "WARN Only", "INFO Only");
        cmbLevel.setValue("All Levels");
        cmbLevel.getStyleClass().addAll("approved-combo-box", "audit-category-combo");
        cmbLevel.setPrefWidth(140);

        ComboBox<String> cmbSource = new ComboBox<>();
        cmbSource.getItems().addAll("All Sources", "DESKTOP", "SERVER");
        cmbSource.setValue("All Sources");
        cmbSource.getStyleClass().addAll("approved-combo-box", "audit-category-combo");
        cmbSource.setPrefWidth(130);

        Label lblEntriesCount = new Label("Loading logs…");
        lblEntriesCount.getStyleClass().addAll("status-pill", "pill-neutral");

        Button btnExportCsv = new Button("Export CSV");
        btnExportCsv.setGraphic(IconFactory.compactIcon("export", 13));
        btnExportCsv.getStyleClass().addAll("approved-button", "approved-secondary-button");
        btnExportCsv.setTooltip(new Tooltip("Export visible diagnostic log entries to CSV"));

        Button btnOpenFolder = new Button("Open Logs Folder");
        btnOpenFolder.setGraphic(IconFactory.compactIcon("storage", 13));
        btnOpenFolder.getStyleClass().addAll("approved-button", "approved-secondary-button");
        btnOpenFolder.setTooltip(new Tooltip("Open local logs directory in Windows Explorer"));
        btnOpenFolder.setOnAction(e -> {
            try {
                Path folder = WorkspaceManager.isConfigured() ? WorkspaceManager.getLogsFolder() : Path.of(System.getProperty("user.home", "."), ".dse-erp", "Logs");
                if (Files.exists(folder)) {
                    java.awt.Desktop.getDesktop().open(folder.toFile());
                } else {
                    ToastManager.warning(pane, "Logs Folder", "Logs directory does not exist yet.");
                }
            } catch (Exception ex) {
                ToastManager.error(pane, "Open Folder Failed", ex.getMessage());
            }
        });

        Button btnRefresh = new Button("Refresh");
        btnRefresh.setGraphic(IconFactory.compactIcon("refresh", 13));
        btnRefresh.getStyleClass().addAll("approved-button", "approved-secondary-button");
        btnRefresh.setTooltip(new Tooltip("Reload latest events from active log files"));

        HBox toolbar = new HBox(8, txtSearch, cmbLevel, cmbSource, lblEntriesCount, btnExportCsv, btnOpenFolder, btnRefresh);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(4, 2, 6, 2));

        // ================= TABLEVIEW & PAGINATION SETUP =================
        final int PAGE_SIZE = 25;
        final int[] currentPage = new int[]{0};

        TableView<DiagnosticRow> table = new TableView<>();
        table.getStyleClass().addAll("approved-table", "erp-table-standard", "diagnostics-table");
        table.setPlaceholder(Classy3DLoadingView.createTableSkeleton("Loading System Diagnostic Ledger…", "Fetching real-time error logs, stack traces, and background events…"));
        table.setFixedCellSize(38.0);
        table.getProperties().put("erp-keep-selection", true);

        // Bind dynamic glassmorphic defect styling via Antigravity Agent Service
        ErpAgentService.installGlassDefectRowFactory(table, r -> {
            if (r == null) return ErpAgentService.DefectSeverity.NONE;
            if ("ERROR".equalsIgnoreCase(r.level()) || "FATAL".equalsIgnoreCase(r.level())) {
                return ErpAgentService.DefectSeverity.CRITICAL;
            } else if ("WARN".equalsIgnoreCase(r.level())) {
                return ErpAgentService.DefectSeverity.WARNING;
            } else {
                return ErpAgentService.DefectSeverity.INFO;
            }
        });

        // Col 1: # (Index)
        TableColumn<DiagnosticRow, Number> colIndex = new TableColumn<>("#");
        colIndex.setPrefWidth(46);
        colIndex.setMinWidth(40);
        colIndex.setMaxWidth(60);
        colIndex.setSortable(false);
        colIndex.getProperties().put("erp-row-number", true);
        colIndex.getProperties().put("erp-header-explicit", true);
        colIndex.setCellValueFactory(v -> new ReadOnlyObjectWrapper<>(v.getValue().id()));
        colIndex.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(Number item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getIndex() < 0 || getTableView() == null || getTableView().getItems() == null || getIndex() >= getTableView().getItems().size()) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                int rowNumber = (currentPage[0] * PAGE_SIZE) + getIndex() + 1;
                setText(String.valueOf(rowNumber));
                setGraphic(null);
                setAlignment(Pos.CENTER);
            }
        });
        IconFactory.applyTableHeaderIcon(colIndex, "index");

        // Col 2: Timestamp
        TableColumn<DiagnosticRow, String> colDate = new TableColumn<>("Timestamp");
        colDate.setPrefWidth(165);
        colDate.setMinWidth(140);
        colDate.setCellValueFactory(v -> new ReadOnlyStringWrapper(v.getValue().formattedTime()));
        colDate.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                setText(item);
                setAlignment(Pos.CENTER_LEFT);
            }
        });
        IconFactory.applyTableHeaderIcon(colDate, "calendar");

        // Col 3: Level (Badge)
        TableColumn<DiagnosticRow, String> colLevel = new TableColumn<>("Level");
        colLevel.setPrefWidth(90);
        colLevel.setMinWidth(75);
        colLevel.setCellValueFactory(v -> new ReadOnlyStringWrapper(v.getValue().level()));
        colLevel.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label badge = new Label(item);
                badge.getStyleClass().addAll("status-pill",
                        "ERROR".equalsIgnoreCase(item) ? "pill-danger" : "WARN".equalsIgnoreCase(item) ? "pill-warning" : "pill-success");
                badge.setGraphic(IconFactory.compactIcon("ERROR".equalsIgnoreCase(item) ? "error" : "WARN".equalsIgnoreCase(item) ? "reminder" : "complete", 12));
                badge.setGraphicTextGap(4);
                setGraphic(badge);
                setAlignment(Pos.CENTER);
                setText(null);
            }
        });
        IconFactory.applyTableHeaderIcon(colLevel, "action");

        // Col 4: Source (Badge)
        TableColumn<DiagnosticRow, String> colSource = new TableColumn<>("Source");
        colSource.setPrefWidth(100);
        colSource.setMinWidth(85);
        colSource.setCellValueFactory(v -> new ReadOnlyStringWrapper(v.getValue().source()));
        colSource.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label badge = new Label(item);
                badge.getStyleClass().addAll("status-pill", "SERVER".equalsIgnoreCase(item) ? "pill-primary" : "pill-neutral");
                badge.setGraphic(IconFactory.compactIcon("SERVER".equalsIgnoreCase(item) ? "database" : "storage", 12));
                badge.setGraphicTextGap(4);
                setGraphic(badge);
                setAlignment(Pos.CENTER);
                setText(null);
            }
        });
        IconFactory.applyTableHeaderIcon(colSource, "database");

        // Col 5: Component / Module
        TableColumn<DiagnosticRow, String> colComp = new TableColumn<>("Component / Scope");
        colComp.setPrefWidth(150);
        colComp.setMinWidth(120);
        colComp.setCellValueFactory(v -> new ReadOnlyStringWrapper(v.getValue().component()));
        colComp.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                setText(item);
                setAlignment(Pos.CENTER_LEFT);
                setStyle("-fx-font-weight: bold;");
            }
        });
        IconFactory.applyTableHeaderIcon(colComp, "category");

        // Col 6: User-Friendly Summary
        TableColumn<DiagnosticRow, String> colTitle = new TableColumn<>("User-Friendly Issue");
        colTitle.setPrefWidth(220);
        colTitle.setMinWidth(180);
        colTitle.setCellValueFactory(v -> new ReadOnlyStringWrapper(v.getValue().friendlyTitle()));
        colTitle.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                setText(item);
                setAlignment(Pos.CENTER_LEFT);
                getStyleClass().removeAll("diagnostic-level-error", "diagnostic-level-warn", "diagnostic-level-info");
                if (getTableRow() != null && getTableRow().getItem() != null) {
                    String lvl = getTableRow().getItem().level();
                    if ("ERROR".equalsIgnoreCase(lvl)) {
                        getStyleClass().add("diagnostic-level-error");
                    } else if ("WARN".equalsIgnoreCase(lvl)) {
                        getStyleClass().add("diagnostic-level-warn");
                    } else {
                        getStyleClass().add("diagnostic-level-info");
                    }
                }
            }
        });
        IconFactory.applyTableHeaderIcon(colTitle, "document");

        // Col 7: Plain-English Explanation & Action
        TableColumn<DiagnosticRow, String> colExplain = new TableColumn<>("Explanation & Guidance");
        colExplain.setPrefWidth(300);
        colExplain.setMinWidth(220);
        colExplain.setCellValueFactory(v -> new ReadOnlyStringWrapper(v.getValue().friendlyExplanation() + " • " + v.getValue().actionableGuidance()));
        colExplain.setCellFactory(col -> new TableCell<>() {
            private final Tooltip explainTooltip = new Tooltip();
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setTooltip(null);
                    return;
                }
                setText(item);
                setAlignment(Pos.CENTER_LEFT);
                explainTooltip.setText(item);
                setTooltip(explainTooltip);
            }
        });
        IconFactory.applyTableHeaderIcon(colExplain, "notes");

        // Col 8: User
        TableColumn<DiagnosticRow, String> colUser = new TableColumn<>("User");
        colUser.setPrefWidth(110);
        colUser.setMinWidth(85);
        colUser.setCellValueFactory(v -> new ReadOnlyStringWrapper(v.getValue().user()));
        colUser.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label uLbl = new Label(item.isBlank() ? "SYSTEM" : item, IconFactory.compactIcon("user", 12));
                uLbl.getStyleClass().add("audit-user-label");
                setGraphic(uLbl);
                setAlignment(Pos.CENTER_LEFT);
                setText(null);
            }
        });
        IconFactory.applyTableHeaderIcon(colUser, "user");

        // Col 9: Technical Inspect Action
        TableColumn<DiagnosticRow, Void> colAction = new TableColumn<>("Diagnostic");
        colAction.setPrefWidth(110);
        colAction.setMinWidth(95);
        colAction.setCellFactory(col -> new TableCell<>() {
            private final Button btnInspect = new Button("Inspect");
            {
                btnInspect.getStyleClass().addAll("approved-button", "approved-secondary-button");
                btnInspect.setGraphic(IconFactory.compactIcon("view", 12));
                btnInspect.setOnAction(e -> {
                    DiagnosticRow row = getTableRow() == null ? null : getTableRow().getItem();
                    if (row != null) {
                        showTechnicalInspectionModal(pane, row);
                    }
                });
            }
            @Override protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                } else {
                    setGraphic(btnInspect);
                    setAlignment(Pos.CENTER);
                }
            }
        });
        IconFactory.applyTableHeaderIcon(colAction, "tools");

        table.getColumns().addAll(List.of(colIndex, colDate, colLevel, colSource, colComp, colTitle, colExplain, colUser, colAction));
        DynamicTableLayoutManager.install(table);
        VBox.setVgrow(table, Priority.ALWAYS);

        // ================= DATA PIPELINE & PAGINATION =================
        ObservableList<DiagnosticRow> masterList = FXCollections.observableArrayList();
        FilteredList<DiagnosticRow> filteredList = new FilteredList<>(masterList, p -> true);
        ObservableList<DiagnosticRow> pagedList = FXCollections.observableArrayList();
        table.setItems(pagedList);

        Label lblPageInfo = new Label("Showing 0 to 0 of 0 events");
        lblPageInfo.getStyleClass().add("register-record-count");

        Region pageSpacer = new Region();
        HBox.setHgrow(pageSpacer, Priority.ALWAYS);

        Button btnPrev = new Button("◀ Previous");
        btnPrev.getStyleClass().addAll("approved-button", "approved-secondary-button");
        Button btnNext = new Button("Next ▶");
        btnNext.getStyleClass().addAll("approved-button", "approved-secondary-button");

        HBox paginationBar = new HBox(10, lblPageInfo, pageSpacer, btnPrev, btnNext);
        paginationBar.setAlignment(Pos.CENTER_LEFT);
        paginationBar.setPadding(new Insets(6, 4, 2, 4));

        Runnable renderPage = () -> {
            int total = filteredList.size();
            int totalPages = Math.max(1, (int) Math.ceil((double) total / PAGE_SIZE));
            if (currentPage[0] >= totalPages) currentPage[0] = Math.max(0, totalPages - 1);
            if (currentPage[0] < 0) currentPage[0] = 0;

            int from = total == 0 ? 0 : currentPage[0] * PAGE_SIZE;
            int to = Math.min(from + PAGE_SIZE, total);

            if (total == 0) {
                pagedList.clear();
                lblPageInfo.setText("No diagnostic events found");
            } else {
                pagedList.setAll(filteredList.subList(from, to));
                lblPageInfo.setText(String.format(Locale.ROOT, "Showing %d to %d of %d events   •   Page %d of %d",
                        from + 1, to, total, currentPage[0] + 1, totalPages));
            }
            btnPrev.setDisable(currentPage[0] <= 0);
            btnNext.setDisable(currentPage[0] >= totalPages - 1);
            lblEntriesCount.setText(total + " of " + masterList.size() + " events");
        };

        btnPrev.setOnAction(e -> {
            if (currentPage[0] > 0) {
                currentPage[0]--;
                renderPage.run();
            }
        });

        btnNext.setOnAction(e -> {
            int totalPages = Math.max(1, (int) Math.ceil((double) filteredList.size() / PAGE_SIZE));
            if (currentPage[0] < totalPages - 1) {
                currentPage[0]++;
                renderPage.run();
            }
        });

        Runnable applyFilter = () -> {
            String q = safe(txtSearch.getText()).trim().toLowerCase(Locale.ROOT);
            String lvl = cmbLevel.getValue();
            String src = cmbSource.getValue();

            filteredList.setPredicate(row -> {
                if (row == null) return false;
                if ("ERROR Only".equalsIgnoreCase(lvl) && !"ERROR".equalsIgnoreCase(row.level())) return false;
                if ("WARN Only".equalsIgnoreCase(lvl) && !"WARN".equalsIgnoreCase(row.level())) return false;
                if ("INFO Only".equalsIgnoreCase(lvl) && !"INFO".equalsIgnoreCase(row.level())) return false;

                if ("DESKTOP".equalsIgnoreCase(src) && !"DESKTOP".equalsIgnoreCase(row.source())) return false;
                if ("SERVER".equalsIgnoreCase(src) && !"SERVER".equalsIgnoreCase(row.source())) return false;

                if (q.isBlank()) return true;
                return row.searchIndex().contains(q);
            });

            currentPage[0] = 0;
            renderPage.run();
        };

        PauseTransition filterDebounce = new PauseTransition(Duration.millis(250));
        filterDebounce.setOnFinished(e -> applyFilter.run());

        txtSearch.textProperty().addListener((obs, o, n) -> filterDebounce.playFromStart());
        cmbLevel.valueProperty().addListener((obs, o, n) -> filterDebounce.playFromStart());
        cmbSource.valueProperty().addListener((obs, o, n) -> filterDebounce.playFromStart());

        Runnable loadData = () -> {
            table.setPlaceholder(Classy3DLoadingView.createTableSkeleton("Loading System Diagnostic Ledger…", "Fetching real-time error logs, stack traces, and background events…"));
            UiTaskExecutor.submitAction("load-system-diagnostics", () -> fetchCombinedLogs(500), rows -> {
                masterList.setAll(rows);
                long total = rows.size();
                long errors = rows.stream().filter(r -> "ERROR".equalsIgnoreCase(r.level())).count();
                long warnings = rows.stream().filter(r -> "WARN".equalsIgnoreCase(r.level())).count();
                long desktop = rows.stream().filter(r -> "DESKTOP".equalsIgnoreCase(r.source())).count();
                long server = rows.stream().filter(r -> "SERVER".equalsIgnoreCase(r.source())).count();

                lblTotalVal.setText(String.valueOf(total));
                lblErrorsVal.setText(String.valueOf(errors));
                lblWarningsVal.setText(String.valueOf(warnings));
                lblDesktopVal.setText(String.valueOf(desktop));
                lblServerVal.setText(String.valueOf(server));

                applyFilter.run();
                if (rows.isEmpty()) {
                    table.setPlaceholder(new Label("No system logs or diagnostic events found."));
                }
            }, ex -> {
                table.setPlaceholder(new Label("Diagnostic logs could not be loaded: " + ex.getMessage()));
                ToastManager.error(pane, "Failed to load logs", ex.getMessage());
            });
        };

        btnRefresh.setOnAction(e -> loadData.run());

        btnExportCsv.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Save System Diagnostics CSV");
            chooser.setInitialFileName("System_Diagnostics_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".csv");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files (*.csv)", "*.csv"));
            File file = chooser.showSaveDialog(dialog.getDialogPane().getScene().getWindow());
            if (file != null) {
                exportToCsv(filteredList, file, pane);
            }
        });

        // ================= ROOT CONTAINER =================
        VBox root = new VBox(12, headerHero, kpiStrip, toolbar, table, paginationBar);
        root.setPadding(new Insets(16, 20, 16, 20));
        root.getStyleClass().addAll("document-inspection-content", "erp-ui-standard");
        VBox.setVgrow(root, Priority.ALWAYS);

        host.getChildren().setAll(root);

        // Trigger load
        loadData.run();
        dialog.showAndWait();
    }

    private static HBox buildHeaderHero(Dialog<?> dialog) {
        Label iconLabel = new Label();
        iconLabel.setGraphic(IconFactory.icon("settings", 32));
        iconLabel.getStyleClass().add("inspection-hero-icon");

        Label kicker = new Label("ENTERPRISE DIAGNOSTIC & LOG LEDGER");
        kicker.getStyleClass().add("inspection-kicker");

        Label title = new Label("System & Application Diagnostics");
        title.getStyleClass().addAll("inspection-hero-title");

        Label meta = new Label("Real-time diagnostic stream of desktop client exceptions, server background tasks, and database events.");
        meta.getStyleClass().add("inspection-meta-label");

        VBox titleBox = new VBox(2, kicker, title, meta);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label livePill = new Label("LIVE STREAM");
        livePill.getStyleClass().addAll("status-pill", "pill-success");
        livePill.setGraphic(IconFactory.compactIcon("complete", 12));
        livePill.setGraphicTextGap(5);

        Label rotationPill = new Label("LOG ROTATION ACTIVE");
        rotationPill.getStyleClass().addAll("status-pill", "pill-neutral");
        rotationPill.setGraphic(IconFactory.compactIcon("storage", 12));
        rotationPill.setGraphicTextGap(5);

        HBox badges = new HBox(8, livePill, rotationPill);
        badges.setAlignment(Pos.CENTER_RIGHT);

        HBox hero = new HBox(12, iconLabel, titleBox, spacer, badges);
        hero.setAlignment(Pos.CENTER_LEFT);
        hero.getStyleClass().add("inspection-hero-bar");
        DocumentInspectionScaffold.installDragPhysics(hero, dialog);
        return hero;
    }

    private static HBox createStandardKpiCard(String title, Label valueLabel, String subtitle, String kpiColorClass, String iconSemantic) {
        StackPane iconHolder = new StackPane();
        iconHolder.getStyleClass().add("standard-kpi-icon");
        iconHolder.getChildren().setAll(IconFactory.compactIcon(iconSemantic, 18));

        Label lblTitle = new Label(title);
        lblTitle.getStyleClass().add("metric-label");

        valueLabel.getStyleClass().addAll("metric-value", "kpi-value-" + kpiColorClass.replace("kpi-", ""), "diagnostic-kpi-value");

        Label lblSub = new Label(subtitle);
        lblSub.getStyleClass().add("metric-note");

        VBox textBox = new VBox(3, lblTitle, valueLabel, lblSub);
        HBox.setHgrow(textBox, Priority.ALWAYS);

        HBox card = new HBox(12, iconHolder, textBox);
        card.setAlignment(Pos.CENTER_LEFT);
        card.getStyleClass().addAll("standard-kpi-card", kpiColorClass);
        card.setPadding(new Insets(10, 14, 10, 14));
        HBox.setHgrow(card, Priority.ALWAYS);
        return card;
    }

    private static void showTechnicalInspectionModal(Node owner, DiagnosticRow row) {
        DocumentInspectionScaffold.InspectionShell shell = DocumentInspectionScaffold.createAdaptiveShell(
                owner,
                "Diagnostic Incident — " + row.friendlyTitle(),
                "diagnostic-incident-modal",
                8
        );
        OwnedDialog<ButtonType> modal = shell.dialog();
        DialogPane pane = shell.pane();
        StackPane host = shell.host();

        Label lblSub = new Label(row.component() + " • " + row.level());
        lblSub.getStyleClass().add("diagnostic-incident-subtitle");

        Label lblReason = new Label("What Happened: " + row.friendlyExplanation());
        lblReason.setWrapText(true);
        lblReason.getStyleClass().add("diagnostic-incident-reason");

        Label lblAction = new Label("Recommended Action: " + row.actionableGuidance());
        lblAction.setWrapText(true);
        lblAction.getStyleClass().add("diagnostic-incident-action");

        TextArea txtTrace = new TextArea();
        txtTrace.setEditable(false);
        txtTrace.setWrapText(true);
        txtTrace.getStyleClass().add("diagnostic-trace-area");
        txtTrace.setText("--- EVENT DETAILS ---\n"
                + "Time: " + row.formattedTime() + " (" + row.timeRaw() + ")\n"
                + "Source: " + row.source() + "\n"
                + "Level: " + row.level() + "\n"
                + "Component: " + row.component() + "\n"
                + "Event: " + row.event() + "\n"
                + "Operator: " + (row.user().isBlank() ? "SYSTEM" : row.user()) + "\n\n"
                + "--- TECHNICAL DETAIL ---\n"
                + row.technicalDetail() + "\n\n"
                + (row.rawFailure().isBlank() ? "" : "--- STACK TRACE / EXCEPTION ---\n" + row.rawFailure() + "\n"));
        VBox.setVgrow(txtTrace, Priority.ALWAYS);

        // Glassmorphic AI Diagnostic Card
        VBox aiCard = new VBox(6);
        aiCard.getStyleClass().addAll("glass-card", "ai-diagnostic-card", "diagnostic-ai-card");
        aiCard.setVisible(false);
        aiCard.setManaged(false);

        Label lblAiTitle = new Label("AI Copilot Diagnosis & Recommended Fix:");
        lblAiTitle.getStyleClass().add("diagnostic-ai-title");
        Label lblAiBody = new Label();
        lblAiBody.setWrapText(true);
        lblAiBody.getStyleClass().add("diagnostic-ai-body");
        aiCard.getChildren().addAll(lblAiTitle, lblAiBody);

        Button btnAiDiagnose = new Button("AI Defect Analysis");
        btnAiDiagnose.setGraphic(IconFactory.compactIcon("action", 13));
        btnAiDiagnose.getStyleClass().addAll("approved-button", "approved-primary-button");
        btnAiDiagnose.setOnAction(e -> {
            btnAiDiagnose.setDisable(true);
            btnAiDiagnose.setText("Analyzing with AI Agent…");
            aiCard.setVisible(true);
            aiCard.setManaged(true);
            aiCard.getChildren().clear();

            Label lblAiStatus = new Label("⚡ Step 1/3: Parsing server stack trace & exception context…");
            Node scannerCard = Classy3DLoadingView.createAiScannerCard(lblAiStatus);
            aiCard.getChildren().add(scannerCard);

            javafx.animation.Timeline progressTimeline = new javafx.animation.Timeline(
                    new javafx.animation.KeyFrame(javafx.util.Duration.millis(400), ev1 -> lblAiStatus.setText("🧠 Step 2/3: Google Antigravity Agent evaluating defect root cause…")),
                    new javafx.animation.KeyFrame(javafx.util.Duration.millis(900), ev2 -> lblAiStatus.setText("💡 Step 3/3: Synthesizing actionable enterprise resolution…"))
            );
            progressTimeline.play();

            ErpAgentService.getInstance().diagnoseDiagnosticRow(row).thenAccept(diag -> {
                Platform.runLater(() -> {
                    progressTimeline.stop();
                    btnAiDiagnose.setDisable(false);
                    btnAiDiagnose.setText("Re-analyze with AI");

                    VBox resultCard = new VBox(8);
                    resultCard.getStyleClass().add("diagnostic-ai-result-card");

                    String badge = diag.isAiGenerated() ? " [Google Antigravity Agent]" : " [Deterministic Engine]";
                    Label title = new Label("AI Diagnosis" + badge);
                    title.getStyleClass().add("diagnostic-ai-result-title");

                    Label cause = new Label("🔍 Root Cause:\n" + diag.rootCause());
                    cause.setWrapText(true);
                    cause.getStyleClass().add("diagnostic-ai-result-cause");

                    Label fix = new Label("💡 Recommended Resolution:\n" + diag.recommendedFix());
                    fix.setWrapText(true);
                    fix.getStyleClass().add("diagnostic-ai-result-fix");

                    resultCard.getChildren().addAll(title, cause, fix);
                    aiCard.getChildren().setAll(resultCard);
                });
            }).exceptionally(ex -> {
                Platform.runLater(() -> {
                    progressTimeline.stop();
                    btnAiDiagnose.setDisable(false);
                    btnAiDiagnose.setText("AI Defect Analysis");
                    Label err = new Label("Analysis could not complete: " + ex.getMessage());
                    err.getStyleClass().add("diagnostic-ai-error");
                    aiCard.getChildren().setAll(err);
                });
                return null;
            });
        });

        Button btnCopy = new Button("Copy Technical Details");
        btnCopy.setGraphic(IconFactory.compactIcon("copy", 13));
        btnCopy.getStyleClass().addAll("approved-button", "approved-sky-button", "erp-button-sky");
        btnCopy.setOnAction(e -> {
            Clipboard clipboard = Clipboard.getSystemClipboard();
            ClipboardContent content = new ClipboardContent();
            content.putString(txtTrace.getText());
            clipboard.setContent(content);
            ToastManager.info(pane, "Copied", "Technical diagnostic trace copied to clipboard.");
        });

        Button btnClose = new Button("Close");
        btnClose.setGraphic(IconFactory.compactIcon("close", 13));
        btnClose.getStyleClass().addAll("approved-button", "approved-danger-button", "erp-button-danger");
        btnClose.setOnAction(e -> modal.close());

        HBox actions = new HBox(10, btnAiDiagnose, btnCopy, btnClose);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.getStyleClass().add("inspection-actions-dock");

        VBox content = new VBox(12, lblSub, lblReason, lblAction, aiCard, txtTrace, actions);
        content.getStyleClass().add("diagnostic-incident-body");
        content.setPadding(new Insets(16));
        VBox.setVgrow(content, Priority.ALWAYS);

        host.getChildren().setAll(content);
        modal.showAndWait();
    }

    private static List<DiagnosticRow> fetchCombinedLogs(int maxLimit) {
        List<DiagnosticRow> rows = new ArrayList<>();
        long counter = 1;

        // 1. Fetch Desktop Logs
        try {
            Path desktopLogFile = WorkspaceManager.isConfigured()
                    ? WorkspaceManager.getDesktopLogsFolder().resolve("desktop.log")
                    : Path.of(System.getProperty("user.home", "."), ".dse-erp", "Logs", "Desktop", "desktop.log");

            if (Files.exists(desktopLogFile)) {
                List<String> lines = Files.readAllLines(desktopLogFile, StandardCharsets.UTF_8);
                int start = Math.max(0, lines.size() - maxLimit);
                for (int i = lines.size() - 1; i >= start; i--) {
                    String line = lines.get(i).trim();
                    if (line.isBlank() || !line.startsWith("{")) continue;
                    try {
                        String time = extractJson(line, "time");
                        String level = extractJson(line, "level");
                        String comp = extractJson(line, "component");
                        String event = extractJson(line, "event");
                        String user = extractJson(line, "user");
                        String detail = extractJson(line, "detail");
                        String failure = extractJson(line, "failure");

                        DiagnosticsInterpretation interp = interpretDiagnostic("DESKTOP", level, comp, event, user, detail, failure);
                        String fTitle = interp.title();
                        String fMsg = interp.explanation();
                        String fAction = interp.action();

                        rows.add(new DiagnosticRow(
                                counter++,
                                time,
                                formatTimestamp(time),
                                level.isBlank() ? "INFO" : level.toUpperCase(Locale.ROOT),
                                "DESKTOP",
                                comp.isBlank() ? "General" : comp,
                                event.isBlank() ? "EVENT" : event,
                                user,
                                fTitle,
                                fMsg,
                                fAction,
                                detail,
                                failure
                        ));
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ex) {
            System.err.println("Failed reading desktop.log: " + ex.getMessage());
        }

        // 2. Fetch Server Logs (Remote REST API first for Cloud UAT/PROD/Local, fallback to disk)
        boolean serverLogsLoaded = false;
        try {
            org.example.api.admin.AdminApiClient adminApi = new org.example.api.admin.AdminApiClient();
            List<org.example.api.admin.AdminApiClient.ServerLogEntry> remoteEntries = adminApi.serverDiagnostics(maxLimit);
            if (remoteEntries != null && !remoteEntries.isEmpty()) {
                serverLogsLoaded = true;
                for (org.example.api.admin.AdminApiClient.ServerLogEntry entry : remoteEntries) {
                    String sTime = entry.time();
                    String sLevel = entry.level() != null ? entry.level().toUpperCase(Locale.ROOT) : "INFO";
                    String sThread = entry.thread() != null ? entry.thread() : "";
                    String sLogger = entry.logger() != null ? entry.logger() : "Server";
                    String sMsg = entry.message() != null ? entry.message() : "";
                    String rawFailure = entry.stackTrace() != null ? entry.stackTrace() : "";

                    String simpleLogger = sLogger;
                    if (simpleLogger.contains(".")) {
                        simpleLogger = simpleLogger.substring(simpleLogger.lastIndexOf('.') + 1);
                    }
                    if (simpleLogger.endsWith("]")) {
                        int open = simpleLogger.indexOf('[');
                        if (open >= 0) simpleLogger = simpleLogger.substring(open + 1, simpleLogger.length() - 1);
                    }

                    DiagnosticsInterpretation interp = interpretDiagnostic("SERVER", sLevel, simpleLogger, "SERVER_EVENT", "SYSTEM", sMsg, rawFailure);
                    String fTitle = interp.title();
                    String fMsg = interp.explanation();
                    String fAction = interp.action();

                    rows.add(new DiagnosticRow(
                            counter++,
                            sTime,
                            formatTimestamp(sTime),
                            sLevel,
                            "SERVER",
                            simpleLogger.isBlank() ? "Server" : simpleLogger,
                            "SERVER_EVENT",
                            "SYSTEM",
                            fTitle,
                            fMsg,
                            fAction,
                            sMsg + (!sThread.isBlank() ? " (thread: " + sThread + ")" : ""),
                            !rawFailure.isBlank() ? rawFailure : (sLevel.equalsIgnoreCase("ERROR") ? sMsg : "")
                    ));
                }
            }
        } catch (Exception ex) {
            // Unauthenticated or offline; fallback to local log file on disk
        }

        // Fallback to reading server log file on disk if remote API was unavailable or returned no rows
        if (!serverLogsLoaded) {
            try {
                Path serverLogFile = WorkspaceManager.isConfigured()
                        ? WorkspaceManager.getServerLogsFolder().resolve("dse-erp-server.log")
                        : Path.of("D:", "DSE Production", "Logs", "Server", "dse-erp-server.log");

                if (Files.exists(serverLogFile)) {
                    List<String> sLines = Files.readAllLines(serverLogFile, StandardCharsets.UTF_8);
                    int sStart = Math.max(0, sLines.size() - Math.max(maxLimit * 4, 3000));
                    List<String> traceBuffer = new ArrayList<>();
                    for (int i = sLines.size() - 1; i >= sStart; i--) {
                        String sLine = sLines.get(i).trim();
                        if (sLine.isBlank()) continue;

                        Matcher mSpring = SPRING_BOOT_LOG_PATTERN.matcher(sLine);
                        Matcher mLegacy = !mSpring.matches() ? LEGACY_SERVER_LOG_PATTERN.matcher(sLine) : null;

                        if (mSpring.matches() || (mLegacy != null && mLegacy.matches())) {
                            String sTime;
                            String sLevel;
                            String sThread;
                            String sLogger;
                            String sMsg;

                            if (mSpring.matches()) {
                                sTime = mSpring.group(1);
                                sLevel = mSpring.group(2).toUpperCase(Locale.ROOT);
                                sThread = mSpring.group(4) != null ? mSpring.group(4).trim() : "";
                                sLogger = mSpring.group(5).trim();
                                sMsg = mSpring.group(6).trim();
                            } else {
                                sTime = mLegacy.group(1);
                                sThread = mLegacy.group(2) != null ? mLegacy.group(2).trim() : "";
                                sLevel = mLegacy.group(3).toUpperCase(Locale.ROOT);
                                sLogger = mLegacy.group(4).trim();
                                sMsg = mLegacy.group(5).trim();
                            }

                            StringBuilder traceBuilder = new StringBuilder();
                            for (int t = traceBuffer.size() - 1; t >= 0; t--) {
                                traceBuilder.append(traceBuffer.get(t)).append("\n");
                            }
                            String rawFailure = traceBuilder.toString().trim();
                            traceBuffer.clear();

                            String simpleLogger = sLogger;
                            if (simpleLogger.contains(".")) {
                                simpleLogger = simpleLogger.substring(simpleLogger.lastIndexOf('.') + 1);
                            }
                            if (simpleLogger.endsWith("]")) {
                                int open = simpleLogger.indexOf('[');
                                if (open >= 0) simpleLogger = simpleLogger.substring(open + 1, simpleLogger.length() - 1);
                            }

                            DiagnosticsInterpretation interp = interpretDiagnostic("SERVER", sLevel, simpleLogger, "SERVER_EVENT", "SYSTEM", sMsg, rawFailure);
                            String fTitle = interp.title();
                            String fMsg = interp.explanation();
                            String fAction = interp.action();

                            rows.add(new DiagnosticRow(
                                    counter++,
                                    sTime,
                                    formatTimestamp(sTime),
                                    sLevel,
                                    "SERVER",
                                    simpleLogger.isBlank() ? "Server" : simpleLogger,
                                    "SERVER_EVENT",
                                    "SYSTEM",
                                    fTitle,
                                    fMsg,
                                    fAction,
                                    sMsg + (!sThread.isBlank() ? " (thread: " + sThread + ")" : ""),
                                    !rawFailure.isBlank() ? rawFailure : (sLevel.equalsIgnoreCase("ERROR") ? sMsg : "")
                            ));
                        } else {
                            if (traceBuffer.size() < 250) {
                                traceBuffer.add(sLine);
                            }
                        }
                    }
                }
            } catch (Exception ex) {
                System.err.println("Failed reading server.log: " + ex.getMessage());
            }
        }

        // Sort newest first by timeRaw
        rows.sort((a, b) -> safe(b.timeRaw()).compareTo(safe(a.timeRaw())));
        return rows;
    }

    private static String extractJson(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) return "";
        start += marker.length();
        StringBuilder sb = new StringBuilder();
        boolean escape = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escape) {
                if (c == 'n') sb.append('\n');
                else if (c == 'r') sb.append('\r');
                else if (c == 't') sb.append('\t');
                else sb.append(c);
                escape = false;
            } else if (c == '\\') {
                escape = true;
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String formatTimestamp(String raw) {
        if (raw == null || raw.isBlank()) return "—";
        try {
            Instant instant = Instant.parse(raw);
            return DISPLAY_FORMATTER.format(instant.atZone(ZoneId.systemDefault()));
        } catch (Exception ignored) {
            return raw;
        }
    }

    private static void exportToCsv(List<DiagnosticRow> list, File file, Node owner) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("ID,Timestamp,Level,Source,Component,Event,User,Friendly_Title,Explanation,Action,Technical_Detail\n");
            for (DiagnosticRow r : list) {
                sb.append(r.id()).append(",")
                        .append(csv(r.formattedTime())).append(",")
                        .append(csv(r.level())).append(",")
                        .append(csv(r.source())).append(",")
                        .append(csv(r.component())).append(",")
                        .append(csv(r.event())).append(",")
                        .append(csv(r.user())).append(",")
                        .append(csv(r.friendlyTitle())).append(",")
                        .append(csv(r.friendlyExplanation())).append(",")
                        .append(csv(r.actionableGuidance())).append(",")
                        .append(csv(r.technicalDetail()))
                        .append("\n");
            }
            Files.writeString(file.toPath(), sb.toString(), StandardCharsets.UTF_8);
            ToastManager.success(owner, "Export Complete", "Exported " + list.size() + " diagnostic entries to " + file.getName());
        } catch (IOException e) {
            ToastManager.error(owner, "Export Failed", e.getMessage());
        }
    }

    private static String csv(String v) {
        if (v == null) return "\"\"";
        return "\"" + v.replace("\"", "\"\"").replace("\n", " ") + "\"";
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private record DiagnosticsInterpretation(String title, String explanation, String action) {}

    private static DiagnosticsInterpretation interpretDiagnostic(String source, String level, String component, String event, String user, String detail, String failure) {
        String cleanDetail = detail != null ? detail.trim() : "";
        String cleanFailure = failure != null ? failure.trim() : "";
        String combined = (cleanDetail + " " + cleanFailure).toLowerCase(Locale.ROOT);

        if (combined.contains("duplicate key") || combined.contains("23505") || combined.contains("unique constraint")) {
            return new DiagnosticsInterpretation("Duplicate Identifier Conflict", "A record with this reference code or identifier already exists in the database.", "Review reference sequences or enter an alternative unique code.");
        }
        if (combined.contains("foreign key") || combined.contains("23503") || combined.contains("referenced by")) {
            return new DiagnosticsInterpretation("Linked Record Dependency", "This record cannot be deleted or modified because dependent vouchers or lines exist.", "Remove linked transactions or dependent line items first.");
        }
        if (combined.contains("concurrentedit") || combined.contains("optimistic locking") || combined.contains("row_version")) {
            return new DiagnosticsInterpretation("Simultaneous Edit Conflict", "Another operator updated this record simultaneously.", "Click Refresh to load latest values and re-apply changes.");
        }
        if (combined.contains("lock timeout") || combined.contains("could not obtain lock") || combined.contains("deadlock")) {
            return new DiagnosticsInterpretation("Database Lock Wait Timeout", "The database is currently synchronizing another transaction on this record.", "Wait a moment for the database lock to release, then retry.");
        }
        if (combined.contains("server resource not found") || combined.contains("illegalargumentexception: server resource")) {
            return new DiagnosticsInterpretation("Server Resource Missing", "Requested backend route or endpoint could not be found on the server engine.", "Verify server deployment version or route mapping in Server Administration.");
        }
        if (combined.contains("does not exist") && (combined.contains("column") || combined.contains("relation"))) {
            String colName = extractColumnName(cleanDetail, cleanFailure);
            return new DiagnosticsInterpretation("Database Schema Mismatch" + (colName.isBlank() ? "" : " (" + colName + ")"), "PostgreSQL schema is missing a required table or column: " + (colName.isBlank() ? cleanDetail : colName), "Run pending Flyway database migrations or apply the database upgrade package.");
        }
        if (combined.contains("no proof is attached") || combined.contains("attachmentunavailableexception")) {
            return new DiagnosticsInterpretation("Payment Proof Attachment Required", "Payment voucher requires an uploaded receipt or proof document before submission.", "Attach a receipt or payment document file before approving the voucher.");
        }
        if (combined.contains("closedchannelexception")) {
            return new DiagnosticsInterpretation("Backend Channel Interrupted", "Communication channel to the Spring backend was closed unexpectedly.", "Verify server port 58080 is active. The client will retry automatically.");
        }
        if (combined.contains("connectexception") || combined.contains("connection refused")) {
            return new DiagnosticsInterpretation("Server Connection Unavailable", "Desktop client could not establish connection to the backend server.", "Check network connection or ensure the Spring REST server is running on port 58080.");
        }
        if (combined.contains("compatibility policy is invalid") || combined.contains("minimum supported desktop")) {
            return new DiagnosticsInterpretation("Version Policy Incompatible", "The company server compatibility policy does not match this desktop version.", "Update the server deployment or adjust compatibility policy in Settings.");
        }
        if (combined.contains("hikaripool") || combined.contains("08003") || combined.contains("timed out after")) {
            return new DiagnosticsInterpretation("Database Pool Timeout", "PostgreSQL database connection pool timed out under heavy load.", "Check PostgreSQL service status on port 55432.");
        }

        String title = "Application Diagnostic Event";
        String explanation = cleanDetail;
        String action = "Review the failure details above or retry the operation.";

        if ("SERVER_START_FAILED".equalsIgnoreCase(event)) {
            title = "Backend Server Startup Failed";
            action = "Check server log in DSE Production\\Logs\\Server\\dse-erp-server.log.";
        } else if ("POSTGRES_START_FAILED".equalsIgnoreCase(event)) {
            title = "PostgreSQL Startup Failed";
            action = "Verify PostgreSQL data folder and port 55432.";
        } else if ("RESTORE_FAILED".equalsIgnoreCase(event)) {
            title = "Database Restore Failed";
            action = "Ensure valid backup file format and adequate disk permissions.";
        } else if (cleanDetail.contains(" | ")) {
            int pipe = cleanDetail.indexOf(" | ");
            String key = cleanDetail.substring(0, pipe).trim();
            explanation = cleanDetail.substring(pipe + 3).trim();
            title = formatActionKey(key);
        } else if ("ERROR".equalsIgnoreCase(level)) {
            if (!component.equalsIgnoreCase("Server") && !component.equalsIgnoreCase("General")) {
                title = component + " Operation Failed";
            } else {
                title = "Internal Application Error";
            }
        } else if ("WARN".equalsIgnoreCase(level)) {
            title = component + " System Warning";
        } else {
            title = event.replace("_", " ");
        }

        if (explanation.isBlank() && !cleanFailure.isBlank()) {
            explanation = cleanFailure;
        }
        if (explanation.isBlank()) {
            explanation = "Event occurred in component " + component + ".";
        }

        if (explanation.contains("threw exception [Request processing failed:")) {
            int start = explanation.indexOf("Request processing failed:");
            int end = explanation.lastIndexOf("]");
            if (start != -1 && end > start) {
                explanation = explanation.substring(start + 26, end).trim();
            }
        }

        return new DiagnosticsInterpretation(title, explanation, action);
    }

    private static String formatActionKey(String key) {
        if (key == null || key.isBlank()) return "Operation Failed";
        String[] parts = key.split("-");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.length() > 4 && p.matches(".*\\d.*")) continue;
            if (!p.isBlank()) {
                sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1).toLowerCase(Locale.ROOT)).append(" ");
            }
        }
        String res = sb.toString().trim();
        return res.isBlank() ? key : res + " Failed";
    }

    private static String extractColumnName(String detail, String failure) {
        String comb = detail + " " + failure;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("column\\s+([A-Za-z0-9_.]+)\\s+does not exist", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher m = p.matcher(comb);
        if (m.find()) return m.group(1);
        return "";
    }
}
