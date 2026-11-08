package org.example.util;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.stage.Window;
import org.example.api.ApiRuntime;
import org.example.api.ApiSession;
import org.example.config.ConfigManager;
import org.example.controller.CreditNoteRegisterController.CreditNoteRow;
import org.example.controller.DebitNoteRegisterController.DebitNoteRow;
import org.example.controller.LinkedRecordContext;
import org.example.navigation.NavigationManager;
import org.example.theme.ThemeManager;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.NumberFormat;
import java.time.Duration;
import java.util.*;

/**
 * Centered, spacious Document Inspection Window Pop-up for Sales Credit Notes and Purchase Debit Notes.
 * Completely replaces inline side drawers with an enterprise multi-tab inspection interface.
 */
public final class CreditDebitNoteInspectionDialog {

    private static final NumberFormat CURRENCY = NumberFormat.getCurrencyInstance(Locale.of("en", "IN"));

    private CreditDebitNoteInspectionDialog() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NoteLineItem(
            Long id,
            Long itemId,
            String itemCode,
            String itemDescription,
            String hsnSac,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal gstRate,
            BigDecimal taxableValue,
            BigDecimal cgstAmount,
            BigDecimal sgstAmount,
            BigDecimal igstAmount,
            BigDecimal totalLineAmount
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NoteFullDetails(
            Long id,
            @JsonAlias({"creditNoteNo", "debitNoteNo"})
            String noteNo,
            String noteDate,
            @JsonAlias({"originalInvoiceNo", "originalBillNo"})
            String originalDocNo,
            Long partyId,
            @JsonAlias({"partyName", "supplierName"})
            String partyName,
            @JsonAlias({"partyGstin", "supplierGstin"})
            String partyGstin,
            String reasonCode,
            BigDecimal taxableAmount,
            BigDecimal cgstAmount,
            BigDecimal sgstAmount,
            BigDecimal igstAmount,
            BigDecimal roundoffAmount,
            BigDecimal totalAmount,
            @JsonAlias({"stockReturned", "adjustInventory"})
            Boolean stockReturned,
            String status,
            Long glEntryId,
            String notes,
            String createdBy,
            String createdAt,
            Long rowVersion,
            List<NoteLineItem> lines
    ) {}

    public static void showCreditNote(Node ownerNode, CreditNoteRow row, Runnable onUpdated, Runnable onReissue) {
        if (row == null) return;
        NoteFullDetails fallback = new NoteFullDetails(
                row.id, row.creditNoteNo, row.noteDate, row.originalInvoiceNo,
                null, row.partyName, row.partyGstin, row.reasonCode,
                row.taxableAmount, row.cgstAmount, row.sgstAmount, row.igstAmount,
                row.roundoffAmount, row.totalAmount, row.adjustInventory, row.status,
                row.glEntryId, row.notes, row.createdBy, row.createdAt, row.rowVersion, List.of()
        );
        show(ownerNode, row.id, row.creditNoteNo, true, onUpdated, onReissue, fallback);
    }

    public static void showDebitNote(Node ownerNode, DebitNoteRow row, Runnable onUpdated, Runnable onReissue) {
        if (row == null) return;
        NoteFullDetails fallback = new NoteFullDetails(
                row.id, row.debitNoteNo, row.noteDate, row.originalPurchaseNo,
                null, row.partyName, row.partyGstin, row.reasonCode,
                row.taxableAmount, row.cgstAmount, row.sgstAmount, row.igstAmount,
                row.roundoffAmount, row.totalAmount, row.adjustInventory, row.status,
                row.glEntryId, row.notes, row.createdBy, row.createdAt, row.rowVersion, List.of()
        );
        show(ownerNode, row.id, row.debitNoteNo, false, onUpdated, onReissue, fallback);
    }

    public static void show(Node ownerNode, Long noteId, String noteNo, boolean isCreditNote, Runnable onUpdated, Runnable onReissue) {
        show(ownerNode, noteId, noteNo, isCreditNote, onUpdated, onReissue, null);
    }

    public static void show(Node ownerNode, Long noteId, String noteNo, boolean isCreditNote, Runnable onUpdated, Runnable onReissue, NoteFullDetails fallback) {
        if (noteId == null && (noteNo == null || noteNo.isBlank())) return;

        if (fallback != null) {
            var shell = DocumentInspectionScaffold.createShell(ownerNode,
                    (isCreditNote ? "Sales Credit Note Inspection — " : "Purchase Debit Note Inspection — ") + safe(fallback.noteNo()),
                    "credit-debit-inspection-dialog");

            renderDialogContent(shell.dialog(), shell.host(), fallback, isCreditNote, onUpdated, onReissue);

            if (noteId != null && (fallback.lines() == null || fallback.lines().isEmpty())) {
                String endpoint = isCreditNote ? ("/api/credit-notes/" + noteId) : ("/api/debit-notes/" + noteId);
                UiTaskExecutor.submitAction("load-note-lines-" + noteId, () -> {
                    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(apiUrl(endpoint)))
                            .timeout(Duration.ofSeconds(6))
                            .header("Accept", "application/json")
                            .GET();
                    ApiSession.authorize(b);
                    HttpResponse<String> resp = ApiRuntime.HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() == 200) {
                        return ApiRuntime.JSON.readValue(resp.body(), NoteFullDetails.class);
                    }
                    return null;
                }, loaded -> {
                    if (loaded != null && loaded.lines() != null && !loaded.lines().isEmpty()) {
                        renderDialogContent(shell.dialog(), shell.host(), loaded, isCreditNote, onUpdated, onReissue);
                    }
                }, err -> {});
            }

            shell.dialog().showAndWait();
            return;
        }

        var shell = DocumentInspectionScaffold.createShell(ownerNode,
                (isCreditNote ? "Sales Credit Note Inspection — " : "Purchase Debit Note Inspection — ") + safe(noteNo),
                "credit-debit-inspection-dialog");

        Classy3DLoadingView loadingSkeleton = Classy3DLoadingView.createTableSkeleton(
                (isCreditNote ? "Loading Sales Credit Note Details — " : "Loading Purchase Debit Note Details — ") + safe(noteNo),
                "Synchronizing line items, GST credit reversal, and financial allocations…");
        shell.host().getChildren().setAll(loadingSkeleton);

        if (noteId != null) {
            String endpoint = isCreditNote ? ("/api/credit-notes/" + noteId) : ("/api/debit-notes/" + noteId);
            UiTaskExecutor.submitAction("load-note-details-" + noteId, () -> {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(apiUrl(endpoint)))
                        .timeout(Duration.ofSeconds(6))
                        .header("Accept", "application/json")
                        .GET();
                ApiSession.authorize(b);
                HttpResponse<String> resp = ApiRuntime.HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    return ApiRuntime.JSON.readValue(resp.body(), NoteFullDetails.class);
                }
                return null;
            }, loaded -> {
                if (loaded != null) {
                    renderDialogContent(shell.dialog(), shell.host(), loaded, isCreditNote, onUpdated, onReissue);
                } else {
                    AppDialogService.error(shell.pane(), "Not Found", "Note could not be loaded.");
                }
            }, err -> AppDialogService.error(shell.pane(), "Failed", err.getMessage()));
        }

        shell.dialog().showAndWait();
    }

    private static void renderDialogContent(Dialog<?> dialog, StackPane host, NoteFullDetails details, boolean isCreditNote, Runnable onUpdated, Runnable onReissue) {
        // Header Hero
        HBox headerHero = buildHeaderHero(details, isCreditNote, dialog);

        // Metric Strip
        HBox metricStrip = buildOverviewCards(details, isCreditNote);

        // TabPane
        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabPane.getStyleClass().addAll("erp-inspection-tabs", "approved-tab-pane");
        VBox.setVgrow(tabPane, Priority.ALWAYS);

        // Tab 1: Line Items Breakdown
        Tab itemsTab = new Tab("Line Items (" + (details.lines() != null ? details.lines().size() : 0) + ")");
        itemsTab.setGraphic(IconFactory.compactIcon("item", 14));
        itemsTab.setContent(buildItemsTabContent(details, isCreditNote));

        // Tab 2: Statutory Tax & GL Impact
        Tab statutoryTab = new Tab("GST & General Ledger Impact");
        statutoryTab.setGraphic(IconFactory.compactIcon("tax", 14));
        statutoryTab.setContent(buildStatutoryTabContent(details, isCreditNote, dialog));

        // Tab 3: Notes & Origin
        Tab notesTab = new Tab("Notes & Audit");
        notesTab.setGraphic(IconFactory.compactIcon("notes", 14));
        notesTab.setContent(buildNotesTabContent(details));

        tabPane.getTabs().addAll(itemsTab, statutoryTab, notesTab);

        // Action Dock
        HBox actionDock = buildActionDock(details, isCreditNote, dialog, onUpdated, onReissue);

        VBox contentBox = DocumentInspectionScaffold.assembleContent(headerHero, metricStrip, tabPane, actionDock);
        host.getChildren().setAll(contentBox);
    }

    private static HBox buildHeaderHero(NoteFullDetails details, boolean isCreditNote, Dialog<?> dialog) {
        Label iconLabel = new Label();
        iconLabel.setGraphic(IconFactory.icon(isCreditNote ? "credit_note" : "debit_note", 32));
        iconLabel.getStyleClass().add("inspection-hero-icon");

        Label docTypeLabel = new Label(isCreditNote ? "SALES CREDIT NOTE (OUTPUT GST REVERSAL)" : "PURCHASE DEBIT NOTE (INPUT TAX REVERSAL)");
        docTypeLabel.getStyleClass().add("inspection-kicker");

        Label docNoLabel = new Label(details.noteNo());
        docNoLabel.getStyleClass().addAll("inspection-hero-title", "sales-detail-invoice");

        Button btnCopy = new Button();
        btnCopy.setGraphic(IconFactory.compactIcon("copy", 13));
        btnCopy.setTooltip(new Tooltip("Copy note number"));
        btnCopy.getStyleClass().addAll("approved-button", "approved-secondary-button", "btn-icon-only");
        btnCopy.setOnAction(e -> {
            Clipboard clipboard = Clipboard.getSystemClipboard();
            ClipboardContent content = new ClipboardContent();
            content.putString(details.noteNo());
            clipboard.setContent(content);
            AppDialogService.info(btnCopy, "Copied", details.noteNo() + " copied to clipboard.");
        });

        HBox docTitleLine = new HBox(8, docNoLabel, btnCopy);
        docTitleLine.setAlignment(Pos.CENTER_LEFT);

        Label partyLabel = new Label((isCreditNote ? "Customer: " : "Supplier: ") + safe(details.partyName()) +
                (!safe(details.partyGstin()).isBlank() ? "  •  GSTIN: " + details.partyGstin() : "") +
                (!safe(details.noteDate()).isBlank() ? "  •  Date: " + BusinessClock.formatDate(details.noteDate()) : ""));
        partyLabel.getStyleClass().add("inspection-subtitle");

        VBox metaBox = new VBox(2, docTypeLabel, docTitleLine, partyLabel);
        HBox.setHgrow(metaBox, Priority.ALWAYS);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        String status = safe(details.status()).toUpperCase(Locale.ROOT);
        Label statusPill = new Label(status);
        if (status.contains("POSTED") || status.contains("ACTIVE")) {
            statusPill.getStyleClass().addAll("status-pill", "pill-success");
        } else if (status.contains("CANCEL") || status.contains("DELET")) {
            statusPill.getStyleClass().addAll("status-pill", "pill-danger");
        } else {
            statusPill.getStyleClass().addAll("status-pill", "pill-warning");
        }

        Button btnHeroClose = new Button();
        btnHeroClose.setGraphic(SemanticIconManager.compact("close", 14));
        btnHeroClose.getStyleClass().addAll("dse-dialog-close", "icon-button");
        btnHeroClose.setTooltip(new Tooltip("Close"));
        btnHeroClose.setOnAction(e -> dialog.close());

        HBox statusBox = new HBox(8, statusPill, btnHeroClose);
        statusBox.setAlignment(Pos.CENTER_RIGHT);

        HBox hero = new HBox(16, iconLabel, metaBox, spacer, statusBox);
        hero.setAlignment(Pos.CENTER_LEFT);
        hero.setPadding(new Insets(4, 8, 8, 8));
        hero.getStyleClass().add("inspection-hero-container");

        DocumentInspectionScaffold.installDragPhysics(hero, dialog);
        return hero;
    }

    private static HBox buildOverviewCards(NoteFullDetails details, boolean isCreditNote) {
        BigDecimal total = details.totalAmount() != null ? details.totalAmount() : BigDecimal.ZERO;
        BigDecimal taxable = details.taxableAmount() != null ? details.taxableAmount() : BigDecimal.ZERO;
        BigDecimal tax = (details.cgstAmount() != null ? details.cgstAmount() : BigDecimal.ZERO)
                .add(details.sgstAmount() != null ? details.sgstAmount() : BigDecimal.ZERO)
                .add(details.igstAmount() != null ? details.igstAmount() : BigDecimal.ZERO);
        boolean stock = Boolean.TRUE.equals(details.stockReturned());

        VBox cardTotal = makeKpiCard("TOTAL NOTE AMOUNT", money(total), "wallet", "inspection-card-primary");
        VBox cardTaxable = makeKpiCard("TAXABLE VALUE", money(taxable), "currency", "inspection-card-neutral");
        VBox cardTax = makeKpiCard(isCreditNote ? "OUTPUT GST REVERSED" : "INPUT TAX REVERSED", money(tax), "tax", "inspection-card-neutral");
        VBox cardStock = makeKpiCard("STOCK IMPACT", stock ? (isCreditNote ? "RESTOCKED (+)" : "RETURNED (-)") : "NO STOCK CHANGE",
                "inventory", stock ? "inspection-card-success" : "inspection-card-neutral");

        HBox strip = new HBox(12, cardTotal, cardTaxable, cardTax, cardStock);
        strip.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(cardTotal, Priority.ALWAYS);
        HBox.setHgrow(cardTaxable, Priority.ALWAYS);
        HBox.setHgrow(cardTax, Priority.ALWAYS);
        HBox.setHgrow(cardStock, Priority.ALWAYS);
        return strip;
    }

    private static VBox makeKpiCard(String title, String value, String iconSemantic, String styleClass) {
        Label titleLbl = new Label(title);
        titleLbl.getStyleClass().add("inspection-kpi-title");
        titleLbl.setGraphic(IconFactory.compactIcon(iconSemantic, 13));

        Label valLbl = new Label(value);
        valLbl.getStyleClass().add("inspection-kpi-value");

        VBox card = new VBox(4, titleLbl, valLbl);
        card.getStyleClass().addAll("inspection-kpi-card", styleClass);
        card.setPadding(new Insets(10, 14, 10, 14));
        return card;
    }

    private static Node buildItemsTabContent(NoteFullDetails details, boolean isCreditNote) {
        TableView<NoteLineItem> table = new TableView<>();
        PopupTableWorkspace.prepareTable(table, "erp-table-profile-detail");

        TableColumn<NoteLineItem, String> colCode = new TableColumn<>("Item Code");
        colCode.setCellValueFactory(d -> new ReadOnlyStringWrapper(safe(d.getValue().itemCode())));

        TableColumn<NoteLineItem, String> colDesc = new TableColumn<>("Description");
        colDesc.setCellValueFactory(d -> new ReadOnlyStringWrapper(safe(d.getValue().itemDescription())));

        TableColumn<NoteLineItem, String> colHsn = new TableColumn<>("HSN/SAC");
        colHsn.setCellValueFactory(d -> new ReadOnlyStringWrapper(safe(d.getValue().hsnSac())));

        TableColumn<NoteLineItem, String> colQty = new TableColumn<>("Qty");
        colQty.setCellValueFactory(d -> new ReadOnlyStringWrapper(d.getValue().quantity() != null ? d.getValue().quantity().stripTrailingZeros().toPlainString() : "0"));
        colQty.setCellFactory(c -> SemanticTableCells.amountString("quantity"));

        TableColumn<NoteLineItem, String> colRate = new TableColumn<>("Unit Rate");
        colRate.setCellValueFactory(d -> new ReadOnlyStringWrapper(money(d.getValue().unitPrice())));
        colRate.setCellFactory(c -> SemanticTableCells.amountString("rate"));

        TableColumn<NoteLineItem, String> colTaxRate = new TableColumn<>("GST %");
        colTaxRate.setCellValueFactory(d -> new ReadOnlyStringWrapper(d.getValue().gstRate() != null ? d.getValue().gstRate() + "%" : "0%"));

        TableColumn<NoteLineItem, String> colTaxable = new TableColumn<>("Taxable");
        colTaxable.setCellValueFactory(d -> new ReadOnlyStringWrapper(money(d.getValue().taxableValue())));
        colTaxable.setCellFactory(c -> SemanticTableCells.amountString("tax"));

        TableColumn<NoteLineItem, String> colTaxAmount = new TableColumn<>("GST Amount");
        colTaxAmount.setCellValueFactory(d -> {
            BigDecimal tax = (d.getValue().cgstAmount() != null ? d.getValue().cgstAmount() : BigDecimal.ZERO)
                    .add(d.getValue().sgstAmount() != null ? d.getValue().sgstAmount() : BigDecimal.ZERO)
                    .add(d.getValue().igstAmount() != null ? d.getValue().igstAmount() : BigDecimal.ZERO);
            return new ReadOnlyStringWrapper(money(tax));
        });
        colTaxAmount.setCellFactory(c -> SemanticTableCells.amountString("tax"));

        TableColumn<NoteLineItem, String> colTotal = new TableColumn<>("Line Total");
        colTotal.setCellValueFactory(d -> new ReadOnlyStringWrapper(money(d.getValue().totalLineAmount())));
        colTotal.setCellFactory(c -> SemanticTableCells.amountString(isCreditNote ? "credit" : "debit"));

        table.getColumns().addAll(List.of(colCode, colDesc, colHsn, colQty, colRate, colTaxRate, colTaxable, colTaxAmount, colTotal));

        if (details.lines() != null && !details.lines().isEmpty()) {
            table.setItems(FXCollections.observableArrayList(details.lines()));
        } else {
            table.setPlaceholder(new Label("Summary Note issued without line-level item breakdown. Tax and GL balances are recorded in total."));
        }

        VBox.setVgrow(table, Priority.ALWAYS);
        return table;
    }

    private static Node buildStatutoryTabContent(NoteFullDetails details, boolean isCreditNote, Dialog<?> dialog) {
        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(12);
        grid.setPadding(new Insets(14, 16, 14, 16));
        grid.getStyleClass().addAll("inspection-detail-grid", "erp-detail-drawer-card");

        int r = 0;
        addDetailRow(grid, r++, isCreditNote ? "Original Sales Invoice" : "Original Purchase Bill",
                !safe(details.originalDocNo()).isBlank() ? details.originalDocNo() : "Not Linked", "document");
        addDetailRow(grid, r++, isCreditNote ? "Customer / Recipient" : "Supplier / Creditor", safe(details.partyName()), isCreditNote ? "customer" : "supplier");
        addDetailRow(grid, r++, "GST Identification Number", !safe(details.partyGstin()).isBlank() ? details.partyGstin() : "Unregistered / Consumer", "tax");
        addDetailRow(grid, r++, "Reason for Note", !safe(details.reasonCode()).isBlank() ? details.reasonCode() : "Standard Adjustment", "notes");
        addDetailRow(grid, r++, "General Ledger Journal Ref", details.glEntryId() != null ? ("Voucher #" + details.glEntryId()) : "Auto-Posted", "audit");
        addDetailRow(grid, r++, "Inventory Adjustment", Boolean.TRUE.equals(details.stockReturned()) ?
                (isCreditNote ? "Returned to stock (item opening balances updated)" : "Deducted from stock (goods returned to vendor)") : "No inventory change (Price/Discount correction only)", "inventory");
        addDetailRow(grid, r++, "Statutory Note Status", safe(details.status()), "status");

        HBox actionRow = new HBox(10);
        actionRow.setPadding(new Insets(12, 0, 0, 0));

        if (!safe(details.originalDocNo()).isBlank()) {
            Button btnViewOriginal = new Button(isCreditNote ? "Open Original Sales Invoice (" + details.originalDocNo() + ")" : "Open Original Purchase Bill (" + details.originalDocNo() + ")");
            btnViewOriginal.setGraphic(IconFactory.compactIcon(isCreditNote ? "sales" : "purchase", 14));
            btnViewOriginal.getStyleClass().addAll("approved-button", "approved-secondary-button");
            btnViewOriginal.setOnAction(e -> {
                dialog.close();
                if (isCreditNote) {
                    LinkedRecordContext.open("SALE", null, details.originalDocNo(), "VIEW", "Credit Note " + details.noteNo());
                    NavigationManager.getInstance().loadPage("/fxml/pages/SalesList.fxml");
                } else {
                    LinkedRecordContext.open("PURCHASE", null, details.originalDocNo(), "VIEW", "Debit Note " + details.noteNo());
                    NavigationManager.getInstance().loadPage("/fxml/pages/PurchaseList.fxml");
                }
            });
            actionRow.getChildren().add(btnViewOriginal);
        }

        VBox container = new VBox(10, grid, actionRow);
        ScrollPane scroll = new ScrollPane(container);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().addAll("erp-inner-scroll", "inspection-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return scroll;
    }

    private static Node buildNotesTabContent(NoteFullDetails details) {
        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(12);
        grid.setPadding(new Insets(14, 16, 14, 16));
        grid.getStyleClass().addAll("inspection-detail-grid", "erp-detail-drawer-card");

        int r = 0;
        addDetailRow(grid, r++, "Created By User", !safe(details.createdBy()).isBlank() ? details.createdBy() : "System", "user");
        addDetailRow(grid, r++, "Created Timestamp", !safe(details.createdAt()).isBlank() ? BusinessClock.formatTimestamp(details.createdAt()) : "—", "calendar");
        addDetailRow(grid, r++, "Internal Notes / Remarks", !safe(details.notes()).isBlank() ? details.notes() : "No additional remarks recorded.", "notes");

        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().addAll("erp-inner-scroll", "inspection-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return scroll;
    }

    private static void addDetailRow(GridPane grid, int row, String caption, String value, String semantic) {
        DocumentInspectionScaffold.addDetailRow(grid, row, caption, value, semantic);
    }

    private static HBox buildActionDock(NoteFullDetails details, boolean isCreditNote, Dialog<?> dialog, Runnable onUpdated, Runnable onReissue) {
        HBox dock = new HBox(8);
        dock.setAlignment(Pos.CENTER_LEFT);
        dock.getStyleClass().add("inspection-action-dock");
        dock.setPadding(new Insets(10, 0, 0, 0));

        boolean isCancelled = "CANCELLED".equalsIgnoreCase(safe(details.status())) || "DELETED".equalsIgnoreCase(safe(details.status()));

        // 1. Edit Note (Safe Accounting Edit)
        Button btnEdit = new Button("Edit Note");
        btnEdit.setGraphic(IconFactory.compactIcon("edit", 14));
        btnEdit.getStyleClass().addAll("approved-button", "approved-secondary-button");
        btnEdit.setDisable(isCancelled);
        btnEdit.setOnAction(e -> {
            promptSafeEdit(details, isCreditNote, dialog, onUpdated, onReissue);
        });

        // 2. Barcode
        Button btnBarcode = new Button("Barcode");
        btnBarcode.setGraphic(IconFactory.compactIcon("barcode", 14));
        btnBarcode.getStyleClass().addAll("approved-button", "approved-secondary-button");
        btnBarcode.setOnAction(e -> {
            if (isCreditNote) {
                OrderDispatchBarcodeDialog.showForCreditNote(
                        dialog.getDialogPane(),
                        details.noteNo(),
                        details.noteDate(),
                        details.partyName(),
                        details.partyGstin(),
                        details.originalDocNo(),
                        details.reasonCode(),
                        details.totalAmount() != null ? details.totalAmount().doubleValue() : 0.0
                );
            } else {
                OrderDispatchBarcodeDialog.showForDebitNote(
                        dialog.getDialogPane(),
                        details.noteNo(),
                        details.noteDate(),
                        details.partyName(),
                        details.partyGstin(),
                        details.originalDocNo(),
                        details.reasonCode(),
                        details.totalAmount() != null ? details.totalAmount().doubleValue() : 0.0
                );
            }
        });

        // 3. Audit Trail
        Button btnAudit = new Button("Audit Trail");
        btnAudit.setGraphic(IconFactory.compactIcon("history", 14));
        btnAudit.getStyleClass().addAll("approved-button", "approved-secondary-button");
        btnAudit.setOnAction(e -> {
            ActivityTimelineDialog.show(
                    dialog.getDialogPane(),
                    isCreditNote ? "CREDIT_NOTE" : "DEBIT_NOTE",
                    details.id() != null ? details.id().intValue() : 0,
                    details.noteNo()
            );
        });

        dock.getChildren().addAll(btnEdit, btnBarcode, btnAudit);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button btnClose = new Button("Close");
        btnClose.setGraphic(IconFactory.compactIcon("cancel", 14));
        btnClose.getStyleClass().addAll("approved-button", "approved-secondary-button");
        btnClose.setOnAction(e -> dialog.close());

        dock.getChildren().addAll(spacer, btnClose);
        return dock;
    }

    private static void promptSafeEdit(NoteFullDetails details, boolean isCreditNote, Dialog<?> parentDialog, Runnable onUpdated, Runnable onReissue) {
        Dialog<ButtonType> editChoice = new OwnedDialog<>(parentDialog.getDialogPane());
        editChoice.setTitle("Edit Note — " + details.noteNo());
        editChoice.setHeaderText("Accounting & Tax Compliance Mode");

        VBox content = new VBox(12);
        content.setPadding(new Insets(14));

        Label infoLbl = new Label("This document is POSTED with active General Ledger & GST entries.\n" +
                "To maintain strict accounting compliance and audit integrity:\n\n" +
                "• Option A (Edit Remarks): Update internal remarks or reason description without altering financial balances.\n" +
                "• Option B (Cancel & Reissue): Automatically void this note (reversing GL & stock) and open a new note pre-filled with these lines for corrections.");
        infoLbl.setWrapText(true);

        TextField txtRemarks = new TextField(safe(details.notes()));
        txtRemarks.setPromptText("Enter updated internal remarks/notes...");

        content.getChildren().addAll(infoLbl, new Label("Update Remarks / Notes:"), txtRemarks);

        ButtonType btnSaveRemarks = new ButtonType("Save Remarks", ButtonBar.ButtonData.OK_DONE);
        ButtonType btnReissue = new ButtonType("Cancel & Reissue...", ButtonBar.ButtonData.OTHER);
        ButtonType btnCancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);

        editChoice.getDialogPane().setContent(content);
        editChoice.getDialogPane().getButtonTypes().addAll(btnSaveRemarks, btnReissue, btnCancel);

        var result = editChoice.showAndWait();
        if (result.isPresent()) {
            if (result.get() == btnSaveRemarks) {
                String updatedNotes = txtRemarks.getText().trim();
                String endpoint = (isCreditNote ? "/api/credit-notes/" : "/api/debit-notes/") + details.id() +
                        "?notes=" + urlEncode(updatedNotes);
                UiTaskExecutor.submitAction("note-update-" + details.id(), () -> {
                    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(apiUrl(endpoint)))
                            .timeout(Duration.ofSeconds(15))
                            .header("Accept", "application/json")
                            .PUT(HttpRequest.BodyPublishers.noBody());
                    ApiSession.authorize(b);
                    HttpResponse<String> resp = ApiRuntime.HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
                    return resp.statusCode() == 200;
                }, ok -> {
                    AppDialogService.success(parentDialog.getDialogPane(), "Saved", "Remarks updated successfully.");
                    if (onUpdated != null) onUpdated.run();
                    parentDialog.close();
                }, err -> AppDialogService.error(parentDialog.getDialogPane(), "Save Failed", err.getMessage()));
            } else if (result.get() == btnReissue) {
                if (AppDialogService.doubleConfirmation(parentDialog.getDialogPane(), "REVERSE", "Confirm Statutory Reversal",
                        "Cancel Note " + details.noteNo() + " and Reissue?",
                        "This will post an irreversible reversal journal in the General Ledger and reverse stock movements.")) {
                    String cancelEndpoint = (isCreditNote ? "/api/credit-notes/" : "/api/debit-notes/") + details.id() + "/cancel";
                    UiTaskExecutor.submitAction("note-cancel-reissue-" + details.id(), () -> {
                        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(apiUrl(cancelEndpoint)))
                                .timeout(Duration.ofSeconds(15))
                                .header("Accept", "application/json")
                                .POST(HttpRequest.BodyPublishers.noBody());
                        ApiSession.authorize(b);
                        HttpResponse<String> resp = ApiRuntime.HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
                        return resp.statusCode() == 200;
                    }, ok -> {
                        AppDialogService.success(parentDialog.getDialogPane(), "Cancelled", "Original note voided cleanly. Opening creation dialog...");
                        if (onUpdated != null) onUpdated.run();
                        parentDialog.close();
                        if (onReissue != null) onReissue.run();
                    }, err -> AppDialogService.error(parentDialog.getDialogPane(), "Cancellation Failed", err.getMessage()));
                }
            }
        }
    }

    private static String apiUrl(String path) {
        String base = ConfigManager.getDataApiBaseUrl();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + path;
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s == null ? "" : s, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String money(BigDecimal value) {
        return DocumentInspectionScaffold.money(value);
    }

    private static String safe(String s) {
        return DocumentInspectionScaffold.safe(s);
    }
}
