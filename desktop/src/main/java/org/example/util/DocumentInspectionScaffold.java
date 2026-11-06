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
    public record InspectionShell(OwnedDialog<ButtonType> dialog, DialogPane pane, StackPane host) {}

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
        pane.getStyleClass().addAll("erp-document-inspection-dialog", specificDialogClass, "approved-ui");
        pane.getProperties().put("dse.dialog.presented", true);
        PopupTableWorkspace.applyInspectionSizing(dialog, ownerNode);

        StackPane host = new StackPane();
        host.getStyleClass().addAll("document-inspection-host", "erp-ui-standard");
        pane.setContent(host);
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

        Button btnHeroClose = new Button();
        btnHeroClose.setGraphic(SemanticIconManager.compact("close", 14));
        btnHeroClose.getStyleClass().addAll("dse-dialog-close", "icon-button");
        btnHeroClose.setTooltip(new Tooltip("Close"));
        btnHeroClose.setOnAction(e -> dialog.close());
        statusStrip.getChildren().add(btnHeroClose);

        HBox hero = new HBox(12, iconLabel, titleBox, spacer, statusStrip);
        hero.setAlignment(Pos.CENTER_LEFT);
        hero.getStyleClass().add("inspection-hero-bar");

        installDragPhysics(hero, dialog);
        return hero;
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
