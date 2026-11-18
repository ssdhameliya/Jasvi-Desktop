package org.example.util;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.example.api.accounting.GeneralLedgerApiClient;
import org.example.service.BrandingService;

import java.io.File;
import java.io.FileOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * Tally-style Account Ledger Statement modal with brought-forward Opening Balance,
 * chronological Debit/Credit running balance, date range filter, double-click drill-down
 * to PostingFlowDialog, and 1-click Excel & PDF statement exports.
 */
public final class AccountLedgerDialog {

    private AccountLedgerDialog() {}

    public static void show(Node ownerNode,
                            GeneralLedgerApiClient api,
                            String accountCode,
                            String accountName,
                            LocalDate initialFrom,
                            LocalDate initialTo) {
        Dialog<ButtonType> dialog = new OwnedDialog<>(ownerNode);
        dialog.setTitle("Account Ledger — " + accountCode + " " + safe(accountName));
        dialog.setResizable(true);

        DialogPane pane = dialog.getDialogPane();
        pane.getStyleClass().addAll("dse-dialog-pane", "approved-dialog", "comm-inspection-dialog", "erp-responsive-dialog");
        pane.getProperties().put("dse.dialog.presented", true);
        PopupTableWorkspace.applyInspectionSizing(dialog, ownerNode);

        // Top branded blue accent bar & hero header
        Region topAccent = new Region();
        topAccent.setMinHeight(5);
        topAccent.setPrefHeight(5);
        topAccent.setMaxHeight(5);
        topAccent.getStyleClass().addAll("dse-dialog-accent", "comm-top-accent", "comm-accent-email");

        StackPane heroIcon = new StackPane(IconFactory.icon("ledger", 24));
        heroIcon.getStyleClass().addAll("erp-page-header-icon", "dse-dialog-icon-box");

        Label lblKicker = new Label("ACCOUNT LEDGER STATEMENT  •  RUNNING BALANCE DRILL-DOWN");
        lblKicker.getStyleClass().addAll("comm-hero-kicker", "comm-kicker-email");

        Label lblDocTitle = new Label(accountCode + " — " + safe(accountName));
        lblDocTitle.getStyleClass().add("stock-ledger-title");

        Label lblSub = new Label("Chronological double-entry statement with brought-forward opening balance and running Dr/Cr balance");
        lblSub.getStyleClass().add("stock-ledger-subtitle");

        VBox heroText = new VBox(3, lblKicker, lblDocTitle, lblSub);
        HBox.setHgrow(heroText, Priority.ALWAYS);

        Button btnTopClose = new Button();
        btnTopClose.setGraphic(IconFactory.compactIcon("cancel", 14));
        btnTopClose.getStyleClass().addAll("dse-dialog-close", "icon-button");
        btnTopClose.getProperties().put("erp-icon-preserve", true);
        btnTopClose.setOnAction(e -> dialog.close());

        HBox headerHero = new HBox(14, heroIcon, heroText, btnTopClose);
        headerHero.setAlignment(Pos.CENTER_LEFT);
        headerHero.getStyleClass().addAll("comm-hero-banner", "comm-hero-email");
        DocumentInspectionScaffold.installDragPhysics(headerHero, dialog);

        VBox headerWrap = new VBox(0, topAccent, headerHero);
        headerWrap.getStyleClass().addAll("dse-stage-header-wrap", "comm-header-wrap");

        // KPI strip
        Label lblOpen = new Label("₹ 0.00");
        lblOpen.getStyleClass().add("metric-value");
        Label lblDr = new Label("₹ 0.00");
        lblDr.getStyleClass().add("metric-value");
        Label lblCr = new Label("₹ 0.00");
        lblCr.getStyleClass().add("metric-value");
        Label lblClose = new Label("₹ 0.00");
        lblClose.getStyleClass().add("metric-value");

        HBox kpiRow = new HBox(10,
                kpiCard("Opening Balance", lblOpen, "kpi-blue"),
                kpiCard("Period Debit", lblDr, "kpi-orange"),
                kpiCard("Period Credit", lblCr, "kpi-green"),
                kpiCard("Closing Balance", lblClose, "kpi-teal")
        );

        // Filter toolbar
        DatePicker dpFrom = new DatePicker(initialFrom);
        DatePickerFormatter.attach(dpFrom);
        dpFrom.setPromptText("From Date");
        dpFrom.setPrefWidth(145);

        DatePicker dpTo = new DatePicker(initialTo);
        DatePickerFormatter.attach(dpTo);
        dpTo.setPromptText("To Date");
        dpTo.setPrefWidth(145);

        TextField txtSearch = new TextField();
        txtSearch.setPromptText("Search voucher no, ref document, narration...");
        HBox.setHgrow(txtSearch, Priority.ALWAYS);

        Button btnApply = new Button("Apply Period");
        btnApply.setMinWidth(Region.USE_PREF_SIZE);
        btnApply.setGraphic(IconFactory.compactIcon("filter", 13));
        btnApply.getStyleClass().addAll("approved-button", "approved-primary-button");

        Button btnClearPeriod = new Button("All Time");
        btnClearPeriod.setMinWidth(Region.USE_PREF_SIZE);
        btnClearPeriod.setGraphic(IconFactory.compactIcon("refresh", 13));
        btnClearPeriod.getStyleClass().addAll("approved-button", "approved-secondary-button");

        HBox filterBar = new HBox(8, txtSearch, dpFrom, dpTo, btnApply, btnClearPeriod);
        filterBar.setAlignment(Pos.CENTER_LEFT);

        // Running-balance TableView
        ObservableList<GeneralLedgerApiClient.AccountLedgerLineDto> masterLines = FXCollections.observableArrayList();
        ObservableList<GeneralLedgerApiClient.AccountLedgerLineDto> filteredLines = FXCollections.observableArrayList();
        TableView<GeneralLedgerApiClient.AccountLedgerLineDto> table = new TableView<>(filteredLines);
        table.getStyleClass().addAll("approved-table", "erp-table-profile-responsive", "erp-purple-selection-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        VBox.setVgrow(table, Priority.ALWAYS);

        TableColumn<GeneralLedgerApiClient.AccountLedgerLineDto, String> cDate = new TableColumn<>("Date");
        cDate.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().entryDate() != null ? BusinessClock.formatDate(d.getValue().entryDate()) : "-"));
        TableColumn<GeneralLedgerApiClient.AccountLedgerLineDto, String> cVoucher = new TableColumn<>("Voucher No.");
        cVoucher.setCellValueFactory(d -> new SimpleStringProperty(safe(d.getValue().entryNumber())));
        TableColumn<GeneralLedgerApiClient.AccountLedgerLineDto, String> cType = new TableColumn<>("Type");
        cType.setCellValueFactory(d -> new SimpleStringProperty(safe(d.getValue().entryType())));
        TableColumn<GeneralLedgerApiClient.AccountLedgerLineDto, String> cRef = new TableColumn<>("Ref Document");
        cRef.setCellValueFactory(d -> new SimpleStringProperty(safe(d.getValue().referenceNo()).isBlank() ? "-" : d.getValue().referenceNo()));
        TableColumn<GeneralLedgerApiClient.AccountLedgerLineDto, String> cNarr = new TableColumn<>("Narration");
        cNarr.setCellValueFactory(d -> {
            String ln = safe(d.getValue().lineNarration());
            return new SimpleStringProperty(ln.isBlank() ? safe(d.getValue().narration()) : ln);
        });
        TableColumn<GeneralLedgerApiClient.AccountLedgerLineDto, String> cDr = new TableColumn<>("Debit (₹)");
        cDr.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().debitAmount() != null && d.getValue().debitAmount().compareTo(BigDecimal.ZERO) > 0 ? money(d.getValue().debitAmount()) : ""));
        TableColumn<GeneralLedgerApiClient.AccountLedgerLineDto, String> cCr = new TableColumn<>("Credit (₹)");
        cCr.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().creditAmount() != null && d.getValue().creditAmount().compareTo(BigDecimal.ZERO) > 0 ? money(d.getValue().creditAmount()) : ""));
        TableColumn<GeneralLedgerApiClient.AccountLedgerLineDto, String> cRun = new TableColumn<>("Running Balance");
        cRun.setCellValueFactory(d -> new SimpleStringProperty(money(d.getValue().runningBalance()) + " " + safe(d.getValue().balanceType())));

        table.getColumns().addAll(List.of(cDate, cVoucher, cType, cRef, cNarr, cDr, cCr, cRun));
        table.setPlaceholder(new Label("No posted ledger movements found for this period."));

        // Double-click any row -> jump straight to Posting Flow Inspector
        table.setRowFactory(tv -> {
            TableRow<GeneralLedgerApiClient.AccountLedgerLineDto> row = new TableRow<>();
            row.setOnMouseClicked(ev -> {
                if (ev.getClickCount() == 2 && !row.isEmpty()) {
                    var item = row.getItem();
                    if (item.referenceNo() != null && !item.referenceNo().isBlank() && item.referenceType() != null && !item.referenceType().isBlank()) {
                        PostingFlowDialog.show(pane, item.referenceType(), item.referenceNo());
                    }
                }
            });
            return row;
        });

        Runnable applySearch = () -> {
            String q = txtSearch.getText() == null ? "" : txtSearch.getText().trim().toLowerCase(Locale.ROOT);
            filteredLines.setAll(masterLines.stream().filter(l ->
                    q.isBlank()
                            || safe(l.entryNumber()).toLowerCase(Locale.ROOT).contains(q)
                            || safe(l.referenceNo()).toLowerCase(Locale.ROOT).contains(q)
                            || safe(l.narration()).toLowerCase(Locale.ROOT).contains(q)
                            || safe(l.lineNarration()).toLowerCase(Locale.ROOT).contains(q)
                            || safe(l.entryType()).toLowerCase(Locale.ROOT).contains(q)
            ).toList());
        };
        txtSearch.textProperty().addListener((o, a, b) -> applySearch.run());

        final GeneralLedgerApiClient.AccountLedgerResponse[] latestResp = new GeneralLedgerApiClient.AccountLedgerResponse[1];

        Runnable reload = () -> UiTaskExecutor.submitLatest("gl-account-ledger-" + accountCode,
                () -> api.accountLedger(accountCode, dpFrom.getValue(), dpTo.getValue()),
                resp -> {
                    latestResp[0] = resp;
                    if (resp.account() != null) {
                        lblDocTitle.setText(resp.account().accountCode() + " — " + resp.account().accountName() + " (" + resp.account().accountType() + ")");
                    }
                    lblOpen.setText(money(resp.openingBalance()));
                    lblDr.setText(money(resp.totalDebit()));
                    lblCr.setText(money(resp.totalCredit()));
                    lblClose.setText(money(resp.closingBalance()) + " " + safe(resp.closingBalanceType()));
                    masterLines.setAll(resp.entries() != null ? resp.entries() : List.of());
                    applySearch.run();
                },
                err -> AppDialogService.error(pane, "Ledger Load Error", err.getMessage()));

        btnApply.setOnAction(e -> reload.run());
        btnClearPeriod.setOnAction(e -> {
            dpFrom.setValue(null);
            dpTo.setValue(null);
            reload.run();
        });

        // Bottom actions: Export Excel, Export PDF, Close
        Button btnExportExcel = new Button("Export Ledger Excel");
        btnExportExcel.setMinWidth(Region.USE_PREF_SIZE);
        btnExportExcel.setGraphic(IconFactory.compactIcon("excel", 14));
        btnExportExcel.getStyleClass().addAll("approved-button", "approved-success-button");
        btnExportExcel.setOnAction(e -> exportLedgerExcel(pane, latestResp[0], filteredLines));

        Button btnExportPdf = new Button("Export Ledger PDF");
        btnExportPdf.setMinWidth(Region.USE_PREF_SIZE);
        btnExportPdf.setGraphic(IconFactory.compactIcon("pdf", 14));
        btnExportPdf.getStyleClass().addAll("approved-button", "approved-primary-button");
        btnExportPdf.setOnAction(e -> exportLedgerPdf(pane, latestResp[0], filteredLines));

        Button btnClose = new Button("Close");
        btnClose.setMinWidth(Region.USE_PREF_SIZE);
        btnClose.setGraphic(IconFactory.compactIcon("cancel", 14));
        btnClose.getStyleClass().addAll("approved-button", "approved-danger-ghost-button");
        btnClose.setOnAction(e -> dialog.close());

        Region dockSpacer = new Region();
        HBox.setHgrow(dockSpacer, Priority.ALWAYS);
        HBox actionDock = new HBox(10, btnExportExcel, btnExportPdf, dockSpacer, btnClose);
        actionDock.setAlignment(Pos.CENTER_LEFT);
        actionDock.getStyleClass().add("erp-inspection-action-dock");

        VBox tableCard = new VBox(10, filterBar, table);
        tableCard.getStyleClass().add("comm-detail-card");
        VBox.setVgrow(tableCard, Priority.ALWAYS);

        VBox contentBody = new VBox(12, kpiRow, tableCard, actionDock);
        contentBody.setPadding(new Insets(14, 20, 14, 20));
        VBox.setVgrow(contentBody, Priority.ALWAYS);

        VBox root = new VBox(0, headerWrap, contentBody);
        root.getStyleClass().addAll("dse-dialog-shell", "dse-dialog-shell-workspace", "comm-inspection-shell");
        pane.setContent(root);
        pane.getButtonTypes().add(ButtonType.CLOSE);
        dialog.setOnShowing(ev -> {
            Node defaultBar = pane.lookup(".button-bar");
            if (defaultBar != null) {
                defaultBar.setVisible(false);
                defaultBar.setManaged(false);
            }
        });

        reload.run();
        dialog.showAndWait();
    }

    private static HBox kpiCard(String title, Label valueLabel, String kpiClass) {
        Label lbl = new Label(title);
        lbl.getStyleClass().add("metric-label");
        VBox box = new VBox(3, lbl, valueLabel);
        HBox.setHgrow(box, Priority.ALWAYS);
        HBox card = new HBox(8, box);
        card.setAlignment(Pos.CENTER_LEFT);
        card.getStyleClass().addAll("standard-kpi-card", kpiClass);
        HBox.setHgrow(card, Priority.ALWAYS);
        return card;
    }

    private static void exportLedgerExcel(Node owner,
                                          GeneralLedgerApiClient.AccountLedgerResponse resp,
                                          List<GeneralLedgerApiClient.AccountLedgerLineDto> lines) {
        if (resp == null || resp.account() == null) return;
        String code = resp.account().accountCode().replaceAll("[^A-Za-z0-9_-]", "_");
        File file = ExportFeedbackManager.resolveWorkspaceExportFile("GeneralLedger", "Account_Ledger_" + code, "xlsx");
        if (!ExportFeedbackManager.checkFileWriteable(owner, file)) return;
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sh = wb.createSheet("Account Ledger");
            Row title = sh.createRow(0);
            title.createCell(0).setCellValue(BrandingService.companyName() + " — Account Ledger: " + resp.account().accountCode() + " " + resp.account().accountName());
            Row summary = sh.createRow(1);
            summary.createCell(0).setCellValue("Opening: " + money(resp.openingBalance())
                    + " | Debit: " + money(resp.totalDebit())
                    + " | Credit: " + money(resp.totalCredit())
                    + " | Closing: " + money(resp.closingBalance()) + " " + safe(resp.closingBalanceType()));

            Row hdr = sh.createRow(3);
            String[] cols = {"Date", "Voucher No.", "Type", "Ref Document", "Narration", "Debit (INR)", "Credit (INR)", "Running Balance"};
            for (int i = 0; i < cols.length; i++) hdr.createCell(i).setCellValue(cols[i]);

            int r = 4;
            for (var line : lines) {
                Row row = sh.createRow(r++);
                row.createCell(0).setCellValue(line.entryDate() != null ? BusinessClock.formatDate(line.entryDate()) : "-");
                row.createCell(1).setCellValue(safe(line.entryNumber()));
                row.createCell(2).setCellValue(safe(line.entryType()));
                row.createCell(3).setCellValue(safe(line.referenceNo()));
                row.createCell(4).setCellValue(safe(line.lineNarration()).isBlank() ? safe(line.narration()) : line.lineNarration());
                row.createCell(5).setCellValue(line.debitAmount() != null ? line.debitAmount().doubleValue() : 0.0);
                row.createCell(6).setCellValue(line.creditAmount() != null ? line.creditAmount().doubleValue() : 0.0);
                row.createCell(7).setCellValue(money(line.runningBalance()) + " " + safe(line.balanceType()));
            }
            for (int i = 0; i < cols.length; i++) sh.autoSizeColumn(i);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                wb.write(fos);
            }
            ExportFeedbackManager.notifyExportSuccess(owner, file, "Account Ledger Excel Export");
        } catch (Exception ex) {
            AppDialogService.error(owner, "Export Failed", ex.getMessage());
        }
    }

    private static void exportLedgerPdf(Node owner,
                                        GeneralLedgerApiClient.AccountLedgerResponse resp,
                                        List<GeneralLedgerApiClient.AccountLedgerLineDto> lines) {
        if (resp == null || resp.account() == null) return;
        String code = resp.account().accountCode().replaceAll("[^A-Za-z0-9_-]", "_");
        File file = ExportFeedbackManager.resolveWorkspaceExportFile("GeneralLedger", "Account_Ledger_" + code, "pdf");
        if (!ExportFeedbackManager.checkFileWriteable(owner, file)) return;
        try (PDDocument doc = new PDDocument()) {
            PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDPageContentStream cs = new PDPageContentStream(doc, page);
            float y = 800f;

            cs.beginText();
            cs.setFont(bold, 14);
            cs.newLineAtOffset(40, y);
            cs.showText(ascii(BrandingService.companyName() + " — Account Ledger Statement"));
            cs.endText();
            y -= 18;

            cs.beginText();
            cs.setFont(regular, 10);
            cs.newLineAtOffset(40, y);
            cs.showText(ascii("Account: " + resp.account().accountCode() + " - " + resp.account().accountName() + " (" + resp.account().accountType() + ")"));
            cs.endText();
            y -= 14;

            cs.beginText();
            cs.setFont(bold, 9.5f);
            cs.newLineAtOffset(40, y);
            cs.showText(ascii("Opening: Rs " + plain(resp.openingBalance())
                    + "   |   Debit: Rs " + plain(resp.totalDebit())
                    + "   |   Credit: Rs " + plain(resp.totalCredit())
                    + "   |   Closing: Rs " + plain(resp.closingBalance()) + " " + safe(resp.closingBalanceType())));
            cs.endText();
            y -= 20;

            cs.beginText();
            cs.setFont(bold, 8.5f);
            cs.newLineAtOffset(40, y);
            cs.showText(String.format("%-12s %-18s %-14s %-28s %12s %12s %14s", "Date", "Voucher", "Ref", "Narration", "Debit", "Credit", "Balance"));
            cs.endText();
            y -= 14;

            for (var line : lines) {
                if (y < 60) {
                    cs.close();
                    page = new PDPage(PDRectangle.A4);
                    doc.addPage(page);
                    cs = new PDPageContentStream(doc, page);
                    y = 800f;
                }
                String dt = line.entryDate() != null ? BusinessClock.formatDate(line.entryDate()) : "-";
                String narr = safe(line.lineNarration()).isBlank() ? safe(line.narration()) : line.lineNarration();
                if (narr.length() > 26) narr = narr.substring(0, 23) + "...";
                String ref = safe(line.referenceNo());
                if (ref.length() > 13) ref = ref.substring(0, 10) + "...";
                String rowStr = String.format("%-12s %-18s %-14s %-28s %12s %12s %14s",
                        dt, truncate(safe(line.entryNumber()), 17), ref, ascii(narr),
                        plain(line.debitAmount()), plain(line.creditAmount()),
                        plain(line.runningBalance()) + " " + safe(line.balanceType()));
                cs.beginText();
                cs.setFont(regular, 8f);
                cs.newLineAtOffset(40, y);
                cs.showText(ascii(rowStr));
                cs.endText();
                y -= 12;
            }
            cs.close();
            doc.save(file);
            ExportFeedbackManager.notifyExportSuccess(owner, file, "Account Ledger PDF Export");
        } catch (Exception ex) {
            AppDialogService.error(owner, "PDF Export Failed", ex.getMessage());
        }
    }

    private static String money(BigDecimal v) {
        return String.format(Locale.of("en", "IN"), "₹ %,.2f", v == null ? BigDecimal.ZERO : v);
    }

    private static String plain(BigDecimal v) {
        return String.format(Locale.ROOT, "%.2f", v == null ? BigDecimal.ZERO : v);
    }

    private static String safe(String v) {
        return v == null ? "" : v.trim();
    }

    private static String truncate(String v, int max) {
        return v.length() <= max ? v : v.substring(0, max - 2) + "..";
    }

    private static String ascii(String s) {
        if (s == null) return "";
        return s.replace("₹", "Rs ").replace("—", "-").replace("•", "|").replaceAll("[^\\x20-\\x7E]", " ");
    }
}
