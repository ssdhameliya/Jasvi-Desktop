package org.example.util;

import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogEvent;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Window;

import java.util.Locale;

/**
 * Shared visual structure for compact data/table dialogs.
 *
 * Business controllers keep ownership of data, selection, validation and actions;
 * this class only standardizes presentation so current behavior is not changed.
 */
public final class PopupTableWorkspace {
    private PopupTableWorkspace() {}

    public static Label metricValue(String text, String semantic) {
        Label value = new Label(text == null ? "" : text);
        value.getStyleClass().addAll("erp-popup-metric-value", semanticClass(semantic));
        return value;
    }

    public static VBox metricCard(String caption, String value, String semantic) {
        return metricCard(caption, metricValue(value, semantic), semantic);
    }

    public static VBox metricCard(String caption, Label value, String semantic) {
        if (value != null && !value.getStyleClass().contains("erp-popup-metric-value")) {
            value.getStyleClass().add("erp-popup-metric-value");
        }
        if (value != null) {
            String semanticClass = semanticClass(semantic);
            if (!value.getStyleClass().contains(semanticClass)) value.getStyleClass().add(semanticClass);
        }
        return controlCard(caption, value == null ? new Label("") : value, semantic);
    }

    public static VBox controlCard(String caption, Node value, String semantic) {
        Label label = new Label(caption == null ? "" : caption);
        label.getStyleClass().add("erp-popup-metric-caption");
        HBox heading = new HBox(7, IconFactory.compactIcon(normalizeSemantic(semantic), 15), label);
        heading.setAlignment(Pos.CENTER_LEFT);
        VBox card = new VBox(6, heading, value == null ? new Label("") : value);
        card.getStyleClass().add("erp-popup-metric-card");
        HBox.setHgrow(card, Priority.ALWAYS);
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    public static HBox metricStrip(Node... cards) {
        HBox strip = new HBox(12);
        strip.setMinWidth(0);
        strip.setMaxWidth(Double.MAX_VALUE);
        strip.getStyleClass().addAll("erp-popup-metric-strip", ResponsiveKpiLayoutManager.KPI_SECTION_STYLE, "erp-kpi-single-row");
        if (cards != null) {
            for (Node card : cards) {
                if (card == null) continue;
                strip.getChildren().add(card);
                HBox.setHgrow(card, Priority.ALWAYS);
            }
        }
        ResponsiveKpiLayoutManager.install(strip);
        return strip;
    }

    public static Label footerText(String text) {
        Label label = new Label(text == null ? "" : text);
        label.getStyleClass().add("erp-popup-footer-text");
        label.setWrapText(true);
        label.setMinWidth(0);
        label.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(label, Priority.ALWAYS);
        return label;
    }

    public static HBox footer(Node... nodes) {
        HBox footer = new HBox(10);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.getStyleClass().add("erp-popup-footer");
        if (nodes != null) {
            for (Node node : nodes) if (node != null) footer.getChildren().add(node);
        }
        return footer;
    }

    public static HBox footerWithRight(Node left, Node right) {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return footer(left, spacer, right);
    }

    public static VBox content(Node metrics, Node tableOrContent, Node footer) {
        VBox box = new VBox(14);
        box.getStyleClass().addAll("erp-popup-workspace", "erp-ui-standard");
        if (metrics != null) box.getChildren().add(metrics);
        if (tableOrContent != null) {
            box.getChildren().add(tableOrContent);
            VBox.setVgrow(tableOrContent, Priority.ALWAYS);
        }
        if (footer != null) box.getChildren().add(footer);
        return box;
    }

    public static void prepareDialog(Dialog<?> dialog, double prefWidth) {
        if (dialog == null) return;
        if (!dialog.getDialogPane().getStyleClass().contains("erp-table-workspace-dialog")) {
            dialog.getDialogPane().getStyleClass().add("erp-table-workspace-dialog");
        }
        if (!dialog.getDialogPane().getStyleClass().contains("erp-ui-standard")) {
            dialog.getDialogPane().getStyleClass().add("erp-ui-standard");
        }
        if (prefWidth > 0) dialog.getDialogPane().setPrefWidth(prefWidth);
        if (!Boolean.TRUE.equals(dialog.getDialogPane().getProperties().get("erp-popup-responsive-width"))) {
            dialog.getDialogPane().getProperties().put("erp-popup-responsive-width", true);
            dialog.addEventHandler(DialogEvent.DIALOG_SHOWING, event -> {
                Window owner = dialog.getOwner();
                Screen screen = owner == null
                        ? Screen.getPrimary()
                        : Screen.getScreensForRectangle(owner.getX(), owner.getY(), Math.max(1, owner.getWidth()), Math.max(1, owner.getHeight()))
                                .stream().findFirst().orElse(Screen.getPrimary());
                double usableW = Math.max(640, screen.getVisualBounds().getWidth() - 64);
                double usableH = Math.max(480, screen.getVisualBounds().getHeight() - 64);
                double target = prefWidth > 0 ? Math.min(prefWidth, usableW) : usableW;
                dialog.getDialogPane().setPrefWidth(target);
                dialog.getDialogPane().setMaxWidth(usableW);
                dialog.getDialogPane().setMinWidth(Math.min(720, target));
                dialog.getDialogPane().setMaxHeight(usableH);
            });
            dialog.addEventHandler(DialogEvent.DIALOG_SHOWN, event -> {
                if (dialog.getDialogPane().getScene() != null && dialog.getDialogPane().getScene().getWindow() instanceof javafx.stage.Stage stage) {
                    WindowUtilsFx.fitDialogToOwnerScreen(stage, dialog.getOwner());
                }
            });
        }
    }

    /**
     * Sizes core document inspection dialogs (Sale, Purchase, Returns, Quotation)
     * dynamically based on monitor usable bounds and content, ensuring zero clipping or margin waste.
     */
    public static void applyInspectionSizing(Dialog<?> dialog, Node ownerNode) {
        if (dialog == null) return;
        Screen screen = Screen.getPrimary();
        if (ownerNode != null && ownerNode.getScene() != null && ownerNode.getScene().getWindow() != null) {
            Window w = ownerNode.getScene().getWindow();
            var screens = Screen.getScreensForRectangle(w.getX(), w.getY(), Math.max(1, w.getWidth()), Math.max(1, w.getHeight()));
            if (!screens.isEmpty()) screen = screens.get(0);
        }
        Rectangle2D bounds = screen.getVisualBounds();
        double maxAvailableW = Math.max(760, bounds.getWidth() - 32);
        double maxAvailableH = Math.max(520, bounds.getHeight() - 48);
        double targetW = Math.min(maxAvailableW, Math.max(1040, Math.min(1600, bounds.getWidth() * 0.82)));
        double targetH = Math.min(maxAvailableH, Math.max(560, Math.min(880, bounds.getHeight() * 0.82)));

        DialogPane pane = dialog.getDialogPane();
        pane.setPrefSize(targetW, targetH);
        pane.setMaxWidth(maxAvailableW);
        pane.setMaxHeight(maxAvailableH);
        pane.setMinWidth(Math.min(920, targetW));
        pane.setMinHeight(Math.min(480, targetH));
        if (!pane.getStyleClass().contains("erp-table-workspace-dialog")) {
            pane.getStyleClass().add("erp-table-workspace-dialog");
        }
        if (!pane.getStyleClass().contains("erp-ui-standard")) {
            pane.getStyleClass().add("erp-ui-standard");
        }
        dialog.setResizable(true);
        dialog.addEventHandler(DialogEvent.DIALOG_SHOWN, event -> {
            if (pane.getScene() != null && pane.getScene().getWindow() instanceof javafx.stage.Stage stage) {
                WindowUtilsFx.fitDialogToOwnerScreen(stage, stage.getOwner());
            }
        });
    }

    /**
     * Content-adaptive sizing for inspection dialogs with variable row counts (Audit Trail, Diagnostics).
     * Eliminates empty dark voids when only a few rows exist while allowing dynamic expansion.
     */
    public static void applyAdaptiveInspectionSizing(Dialog<?> dialog, Node ownerNode, int expectedRows) {
        if (dialog == null) return;
        Screen screen = Screen.getPrimary();
        if (ownerNode != null && ownerNode.getScene() != null && ownerNode.getScene().getWindow() != null) {
            Window w = ownerNode.getScene().getWindow();
            var screens = Screen.getScreensForRectangle(w.getX(), w.getY(), Math.max(1, w.getWidth()), Math.max(1, w.getHeight()));
            if (!screens.isEmpty()) screen = screens.get(0);
        }
        Rectangle2D bounds = screen.getVisualBounds();
        double maxAvailableW = Math.max(760, bounds.getWidth() - 32);
        double maxAvailableH = Math.max(520, bounds.getHeight() - 48);
        double targetW = Math.min(maxAvailableW, Math.max(900, Math.min(1480, bounds.getWidth() * 0.78)));

        // Base chrome: window title (38px) + header (64px) + hero (72px) + toolbar (52px) + table header (38px) + footer (54px) + margins ~ 320px
        double contentH = 340.0 + Math.max(2, expectedRows) * 40.0;
        double targetH = Math.min(maxAvailableH, Math.max(500.0, Math.min(820.0, contentH)));

        DialogPane pane = dialog.getDialogPane();
        pane.setPrefSize(targetW, targetH);
        pane.setMaxWidth(maxAvailableW);
        pane.setMaxHeight(maxAvailableH);
        pane.setMinWidth(Math.min(840, targetW));
        pane.setMinHeight(Math.min(480, targetH));
        if (!pane.getStyleClass().contains("erp-table-workspace-dialog")) {
            pane.getStyleClass().add("erp-table-workspace-dialog");
        }
        if (!pane.getStyleClass().contains("erp-ui-standard")) {
            pane.getStyleClass().add("erp-ui-standard");
        }
        dialog.setResizable(true);
        dialog.addEventHandler(DialogEvent.DIALOG_SHOWN, event -> {
            if (pane.getScene() != null && pane.getScene().getWindow() instanceof javafx.stage.Stage stage) {
                WindowUtilsFx.fitDialogToOwnerScreen(stage, stage.getOwner());
            }
        });
    }

    public static void prepareTable(TableView<?> table, String profileClass) {
        if (table == null) return;
        if (!table.getStyleClass().contains("approved-table")) table.getStyleClass().add("approved-table");
        if (!table.getStyleClass().contains("erp-table-standard")) table.getStyleClass().add("erp-table-standard");
        if (profileClass != null && !profileClass.isBlank() && !table.getStyleClass().contains(profileClass)) {
            table.getStyleClass().add(profileClass);
        }
        DynamicTableLayoutManager.install(table);
    }

    private static String semanticClass(String semantic) {
        String normalized = normalizeSemantic(semantic);
        return switch (normalized) {
            case "complete", "success", "credit" -> "erp-popup-metric-success";
            case "warning", "reminder", "balance" -> "erp-popup-metric-warning";
            case "error", "danger", "debit", "delete" -> "erp-popup-metric-danger";
            case "communication", "version", "tax" -> "erp-popup-metric-accent";
            default -> "erp-popup-metric-primary";
        };
    }

    private static String normalizeSemantic(String semantic) {
        String value = semantic == null ? "info" : semantic.trim().toLowerCase(Locale.ROOT);
        return value.isBlank() ? "info" : value;
    }
}
