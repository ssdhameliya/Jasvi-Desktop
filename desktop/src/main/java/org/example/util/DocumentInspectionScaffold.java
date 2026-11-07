package org.example.util;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.stage.Window;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

/**
 * Unified modal scaffold and visual engine for full-width transactional document inspection workspaces.
 *
 * <p>Centralizes window shell creation, 88% responsive screen bounds, window dragging physics,
 * hero header banner with reference copying, status pill generation, overview summary KPI cards,
 * and key-value metadata rows. Specific document inspection dialogs (Sales, Purchase, Quotation,
 * Returns, Credit/Debit Notes) delegate shell and presentation logic here while retaining domain
 * business tabs, line items, and action handlers.</p>
 */
public final class DocumentInspectionScaffold {

    private DocumentInspectionScaffold() {}

    /**
     * Immutable key-value field descriptor for KPI summary cards.
     */
    public record FieldPair(String label, String value, String semantic, boolean bold) {
        public FieldPair(String label, String value, String semantic) {
            this(label, value, semantic, false);
        }
    }

    /**
     * Complete initialized inspection dialog shell bundle.
     */
    public record InspectionShell(OwnedDialog<ButtonType> dialog, DialogPane pane, StackPane host) {
        public OwnedDialog<ButtonType> getDialog() { return dialog; }
        public DialogPane getDialogPane() { return pane; }
        public StackPane getHost() { return host; }
    }

    /**
     * Creates and initializes a standardized full inspection window dialog shell.
     *
     * @param ownerNode           the calling parent UI node
     * @param title               the window stage title
     * @param specificDialogClass specific CSS style class for the dialog (e.g. "sales-inspection-dialog")
     * @return initialized {@link InspectionShell} containing dialog, pane, and host container
     */
    public static InspectionShell createShell(Node ownerNode, String title, String specificDialogClass) {
        OwnedDialog<ButtonType> dialog = new OwnedDialog<>(ownerNode);
        dialog.setTitle(title != null ? title : "Document Inspection");
        dialog.setHeaderText(null);
        dialog.setResizable(true);

        DialogPane pane = dialog.getDialogPane();
        pane.getStyleClass().addAll("dse-dialog-pane", "erp-document-inspection-dialog", "dse-dialog-workspace", "dse-dialog-semantic-inspect", specificDialogClass, "approved-ui");
        pane.getProperties().put("dse.dialog.presented", true);
        PopupTableWorkspace.applyInspectionSizing(dialog, ownerNode);

        // 1. Top 5px Gradient Accent Bar
        Region accent = new Region();
        accent.getStyleClass().add("dse-dialog-accent");
        accent.setMinHeight(5);
        accent.setPrefHeight(5);
        accent.setMaxHeight(5);

        // 2. Circular Semantic Icon Box
        String iconSemantic = resolveIcon(title, specificDialogClass);
        StackPane iconBox = new StackPane(SemanticIconManager.compact(iconSemantic, 22));
        iconBox.getStyleClass().add("dse-dialog-icon-box");

        // 3. Title copy: Kicker + Document Title
        Label kicker = new Label(resolveKicker(title, specificDialogClass));
        kicker.getStyleClass().add("dse-dialog-kicker");

        Label headingLabel = new Label(title != null ? title : "Document Inspection");
        headingLabel.getStyleClass().add("dse-dialog-title");
        headingLabel.setWrapText(true);

        VBox titleCopy = new VBox(3, kicker, headingLabel);
        HBox.setHgrow(titleCopy, Priority.ALWAYS);

        // 4. Window Close Button
        Button close = new Button();
        close.setGraphic(SemanticIconManager.compact("close", 14));
        close.getStyleClass().addAll("dse-dialog-close", "icon-button");
        close.getProperties().put("erp-icon-preserve", true);
        close.setAccessibleText("Close dialog");
        close.setTooltip(new Tooltip("Close"));
        close.setCancelButton(true);
        close.setFocusTraversable(false);
        close.setOnAction(event -> dialog.close());

        // 5. Draggable Window Header Bar
        HBox header = new HBox(14, iconBox, titleCopy, close);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("dse-dialog-header");
        installDragPhysics(header, dialog);

        // 6. Host container for inspection tabs & content
        StackPane host = new StackPane();
        host.getStyleClass().addAll("document-inspection-host", "erp-ui-standard");
        VBox.setVgrow(host, Priority.ALWAYS);

        // 7. Outer Canonical Dialog Shell wrapping Accent, Header, and Host
        VBox shell = new VBox(accent, header, host);
        shell.getStyleClass().addAll("dse-dialog-shell", "dse-dialog-shell-workspace", "erp-ui-standard");
        VBox.setVgrow(shell, Priority.ALWAYS);

        pane.setContent(shell);
        pane.getButtonTypes().add(ButtonType.CLOSE);

        dialog.setOnShowing(ev -> {
            Node defaultButtonBar = pane.lookup(".button-bar");
            if (defaultButtonBar != null) {
                defaultButtonBar.setVisible(false);
                defaultButtonBar.setManaged(false);
            }
        });

        return new InspectionShell(dialog, pane, host);
    }

    /**
     * Content-adaptive inspection shell for variable-length documents (Audit Trail, Event Inspector).
     */
    public static InspectionShell createAdaptiveShell(Node ownerNode, String title, String specificDialogClass, int expectedRows) {
        OwnedDialog<ButtonType> dialog = new OwnedDialog<>(ownerNode);
        dialog.setTitle(title != null ? title : "Document Inspection");
        dialog.setHeaderText(null);
        dialog.setResizable(true);

        DialogPane pane = dialog.getDialogPane();
        pane.getStyleClass().addAll("dse-dialog-pane", "erp-document-inspection-dialog", "dse-dialog-workspace", "dse-dialog-semantic-inspect", specificDialogClass, "approved-ui");
        pane.getProperties().put("dse.dialog.presented", true);
        PopupTableWorkspace.applyAdaptiveInspectionSizing(dialog, ownerNode, expectedRows);

        Region accent = new Region();
        accent.getStyleClass().add("dse-dialog-accent");
        accent.setMinHeight(5);
        accent.setPrefHeight(5);
        accent.setMaxHeight(5);

        String iconSemantic = resolveIcon(title, specificDialogClass);
        StackPane iconBox = new StackPane(SemanticIconManager.compact(iconSemantic, 22));
        iconBox.getStyleClass().add("dse-dialog-icon-box");

        Label kicker = new Label(resolveKicker(title, specificDialogClass));
        kicker.getStyleClass().add("dse-dialog-kicker");

        Label headingLabel = new Label(title != null ? title : "Document Inspection");
        headingLabel.getStyleClass().add("dse-dialog-title");
        headingLabel.setWrapText(true);

        VBox titleCopy = new VBox(3, kicker, headingLabel);
        HBox.setHgrow(titleCopy, Priority.ALWAYS);

        Button close = new Button();
        close.setGraphic(SemanticIconManager.compact("close", 14));
        close.getStyleClass().addAll("dse-dialog-close", "icon-button");
        close.getProperties().put("erp-icon-preserve", true);
        close.setAccessibleText("Close dialog");
        close.setTooltip(new Tooltip("Close"));
        close.setCancelButton(true);
        close.setFocusTraversable(false);
        close.setOnAction(event -> dialog.close());

        HBox header = new HBox(14, iconBox, titleCopy, close);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("dse-dialog-header");
        installDragPhysics(header, dialog);

        StackPane host = new StackPane();
        host.getStyleClass().addAll("document-inspection-host", "erp-ui-standard");
        VBox.setVgrow(host, Priority.ALWAYS);

        VBox shell = new VBox(accent, header, host);
        shell.getStyleClass().addAll("dse-dialog-shell", "dse-dialog-shell-workspace", "erp-ui-standard");
        VBox.setVgrow(shell, Priority.ALWAYS);

        pane.setContent(shell);
        pane.getButtonTypes().add(ButtonType.CLOSE);

        dialog.setOnShowing(ev -> {
            Node defaultButtonBar = pane.lookup(".button-bar");
            if (defaultButtonBar != null) {
                defaultButtonBar.setVisible(false);
                defaultButtonBar.setManaged(false);
            }
        });

        return new InspectionShell(dialog, pane, host);
    }

    /**
     * Installs smooth window dragging physics on any interactive handle (e.g. header hero bar).
     */
    public static void installDragPhysics(Node handle, Dialog<?> dialog) {
        if (handle == null || dialog == null) return;
        final double[] dragDelta = new double[2];
        handle.setOnMousePressed(mouseEvent -> {
            if (dialog.getDialogPane().getScene() != null && dialog.getDialogPane().getScene().getWindow() != null) {
                Window w = dialog.getDialogPane().getScene().getWindow();
                dragDelta[0] = w.getX() - mouseEvent.getScreenX();
                dragDelta[1] = w.getY() - mouseEvent.getScreenY();
            }
        });
        handle.setOnMouseDragged(mouseEvent -> {
            if (dialog.getDialogPane().getScene() != null && dialog.getDialogPane().getScene().getWindow() != null) {
                Window w = dialog.getDialogPane().getScene().getWindow();
                w.setX(mouseEvent.getScreenX() + dragDelta[0]);
                w.setY(mouseEvent.getScreenY() + dragDelta[1]);
            }
        });
    }

    /**
     * Builds the standardized top header hero bar featuring document badge, kicker,
     * document reference number with copy button, creation/invoice metadata, status pills,
     * top-right close button, and window dragging physics.
     */
    public static HBox buildHeaderHero(Dialog<?> dialog, String iconSemantic, String docTypeKicker,
                                       String docNo, String metaText, List<Node> statusPills) {
        Label iconLabel = new Label();
        iconLabel.setGraphic(IconFactory.icon(iconSemantic != null ? iconSemantic : "document", 32));
        iconLabel.getStyleClass().add("inspection-hero-icon");

        Label kickerLabel = new Label(docTypeKicker != null ? docTypeKicker : "");
        kickerLabel.getStyleClass().add("inspection-kicker");

        Label docNoLabel = new Label(docNo != null ? docNo : "");
        docNoLabel.getStyleClass().addAll("inspection-hero-title", "sales-detail-invoice");

        Button btnCopy = new Button();
        btnCopy.setGraphic(IconFactory.compactIcon("copy", 13));
        btnCopy.setTooltip(new Tooltip("Copy reference number"));
        btnCopy.getStyleClass().addAll("icon-button", "copy-ref-button", "approved-button");
        btnCopy.setOnAction(e -> copyToClipboard(dialog, docNo));

        HBox docNoBox = new HBox(8, docNoLabel, btnCopy);
        docNoBox.setAlignment(Pos.CENTER_LEFT);

        Label metaLabel = new Label(metaText != null ? metaText : "");
        metaLabel.getStyleClass().add("inspection-meta-label");

        VBox titleBox = new VBox(2, kickerLabel, docNoBox, metaLabel);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox statusStrip = new HBox(8);
        statusStrip.setAlignment(Pos.CENTER_RIGHT);
        if (statusPills != null) {
            for (Node pill : statusPills) {
                if (pill != null) statusStrip.getChildren().add(pill);
            }
        }

        HBox hero = new HBox(12, iconLabel, titleBox, spacer, statusStrip);
        hero.setAlignment(Pos.CENTER_LEFT);
        hero.getStyleClass().add("inspection-hero-bar");

        installDragPhysics(hero, dialog);
        return hero;
    }

    private static String resolveKicker(String title, String specificDialogClass) {
        String lowerClass = specificDialogClass != null ? specificDialogClass.toLowerCase(Locale.ROOT) : "";
        String lowerTitle = title != null ? title.toLowerCase(Locale.ROOT) : "";
        if (lowerClass.contains("sales") || lowerTitle.contains("sale") || lowerTitle.contains("invoice")) return "Tax Invoice • Commercial Record";
        if (lowerClass.contains("purchase") || lowerTitle.contains("purchase") || lowerTitle.contains("bill")) return "Purchase Bill • Commercial Record";
        if (lowerClass.contains("quotation") || lowerTitle.contains("quotation") || lowerTitle.contains("quote")) return "Quotation Specification • Commercial Record";
        if (lowerClass.contains("return") || lowerTitle.contains("return")) return "Return Voucher • Commercial Record";
        if (lowerClass.contains("credit") || lowerClass.contains("debit") || lowerTitle.contains("note")) return "Credit / Debit Note • Commercial Record";
        if (lowerClass.contains("statement") || lowerTitle.contains("statement") || lowerTitle.contains("bank")) return "Bank Statement • Financial Reconciliation";
        if (lowerClass.contains("gst") || lowerTitle.contains("gst")) return "GST Compliance • Statutory Record";
        if (lowerClass.contains("diagnostics") || lowerTitle.contains("diagnostic")) return "Enterprise Log Ledger • Diagnostics";
        if (lowerClass.contains("user") || lowerTitle.contains("user")) return "Identity & Access • Security Privilege";
        if (lowerClass.contains("timeline") || lowerTitle.contains("timeline") || lowerTitle.contains("audit")) return "Enterprise Audit Trail • Immutable Record";
        return "Document Inspection • Commercial Record";
    }

    private static String resolveIcon(String title, String specificDialogClass) {
        String lowerClass = specificDialogClass != null ? specificDialogClass.toLowerCase(Locale.ROOT) : "";
        String lowerTitle = title != null ? title.toLowerCase(Locale.ROOT) : "";
        if (lowerClass.contains("sales") || lowerTitle.contains("sale")) return "sale";
        if (lowerClass.contains("purchase") || lowerTitle.contains("purchase")) return "purchase";
        if (lowerClass.contains("quotation") || lowerTitle.contains("quote")) return "quotation";
        if (lowerClass.contains("return") || lowerTitle.contains("return")) return "return";
        if (lowerClass.contains("credit") || lowerClass.contains("debit")) return "wallet";
        if (lowerClass.contains("bank") || lowerTitle.contains("bank")) return "bank";
        if (lowerClass.contains("diagnostics") || lowerTitle.contains("diagnostic")) return "diagnostics";
        if (lowerClass.contains("user") || lowerTitle.contains("user")) return "users";
        if (lowerClass.contains("timeline") || lowerTitle.contains("audit")) return "audit";
        return "document";
    }

    /**
     * Builds a standardized colorful status badge pill.
     */
    public static Label createPill(String text, String pillClass, String iconSemantic) {
        Label pill = new Label(text != null ? text : "");
        pill.getStyleClass().addAll("status-pill", pillClass != null ? pillClass : "pill-info");
        pill.setGraphic(IconFactory.compactIcon(iconSemantic != null ? iconSemantic : "info", 13));
        pill.setGraphicTextGap(5);
        return pill;
    }

    /**
     * Builds a standardized overview KPI summary card with grid key-value rows.
     */
    public static VBox createSummaryCard(String title, String iconSemantic, List<FieldPair> fields) {
        Label titleLabel = new Label(title != null ? title : "");
        titleLabel.setGraphic(IconFactory.compactIcon(iconSemantic != null ? iconSemantic : "info", 14));
        titleLabel.setGraphicTextGap(6);
        titleLabel.getStyleClass().add("inspection-card-title");

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(4);

        int row = 0;
        if (fields != null) {
            for (FieldPair fp : fields) {
                if (fp == null) continue;
                Label lbl = new Label(fp.label() + ":");
                lbl.getStyleClass().add("inspection-card-caption");

                Label val = new Label(fp.value() != null ? fp.value() : "");
                val.getStyleClass().add("inspection-card-value");
                if (fp.bold()) val.getStyleClass().add("inspection-value-bold");
                if ("complete".equals(fp.semantic())) val.getStyleClass().add("summary-paid");
                else if ("balance".equals(fp.semantic())) val.getStyleClass().add("summary-balance");

                grid.add(lbl, 0, row);
                grid.add(val, 1, row);
                row++;
            }
        }

        VBox card = new VBox(8, titleLabel, grid);
        card.getStyleClass().addAll("inspection-card", "erp-detail-drawer-card");
        card.setPadding(new Insets(10, 12, 10, 12));
        return card;
    }

    /**
     * Builds a responsive horizontal summary cards strip where each card shares equal available width.
     */
    public static HBox createSummaryStrip(VBox... cards) {
        HBox strip = new HBox(12);
        strip.getStyleClass().add("inspection-overview-strip");
        if (cards != null) {
            for (VBox card : cards) {
                if (card != null) {
                    strip.getChildren().add(card);
                    HBox.setHgrow(card, Priority.ALWAYS);
                }
            }
        }
        return strip;
    }

    /**
     * Adds a standardized key-value detail row to a detail metadata grid.
     */
    public static void addDetailRow(GridPane grid, int row, String caption, String value, String semantic) {
        if (grid == null) return;
        Label capLbl = new Label(caption != null ? caption : "");
        capLbl.setGraphic(IconFactory.compactIcon(semantic != null ? semantic : "info", 14));
        capLbl.setGraphicTextGap(6);
        capLbl.getStyleClass().addAll("inspection-card-caption", "erp-drawer-caption");
        capLbl.setMinWidth(180);

        Label valLbl = new Label(value != null ? value : "");
        valLbl.getStyleClass().addAll("inspection-card-value", "erp-drawer-value");
        valLbl.setWrapText(true);

        grid.add(capLbl, 0, row);
        grid.add(valLbl, 1, row);
    }

    /**
     * Assembles the full inspection content hierarchy (Header Hero, Overview Strip, Tab Pane, Action Dock).
     */
    public static VBox assembleContent(HBox headerHero, HBox metricStrip, TabPane tabPane, HBox actionDock) {
        VBox contentBox = new VBox(12, headerHero, metricStrip, tabPane, actionDock);
        contentBox.setPadding(new Insets(16, 20, 16, 20));
        contentBox.getStyleClass().addAll("document-inspection-content", "erp-ui-standard");
        VBox.setVgrow(contentBox, Priority.ALWAYS);
        return contentBox;
    }

    /**
     * Creates a standardized bottom action dock container.
     */
    public static HBox createActionDock(Node... actions) {
        HBox dock = new HBox(8);
        dock.setAlignment(Pos.CENTER_LEFT);
        dock.getStyleClass().add("inspection-action-dock");
        if (actions != null) {
            for (Node action : actions) {
                if (action != null) dock.getChildren().add(action);
            }
        }
        return dock;
    }

    /**
     * Copies text to system clipboard and renders a non-intrusive feedback toast.
     */
    public static void copyToClipboard(Dialog<?> dialog, String text) {
        if (text == null || text.isBlank()) return;
        Clipboard clipboard = Clipboard.getSystemClipboard();
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        clipboard.setContent(content);
        if (dialog != null && dialog.getDialogPane() != null) {
            ToastManager.info(dialog.getDialogPane(), "Copied", text + " copied to clipboard.");
        }
    }

    /**
     * Standard Indian Rupee currency formatter for double numbers.
     */
    public static String money(double value) {
        return NumberFormat.getCurrencyInstance(Locale.of("en", "IN")).format(value).replace("₹", "₹ ");
    }

    /**
     * Standard Indian Rupee currency formatter for BigDecimal values.
     */
    public static String money(BigDecimal value) {
        return NumberFormat.getCurrencyInstance(Locale.of("en", "IN")).format(value != null ? value : BigDecimal.ZERO).replace("₹", "₹ ");
    }

    /**
     * Null-safe string trimmer.
     */
    public static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    /**
     * Format human-readable file sizes.
     */
    public static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format(Locale.ROOT, "%.1f %cB", bytes / Math.pow(1024, exp), pre);
    }
}
