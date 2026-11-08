package org.example.util;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.example.theme.ThemeManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Universal centered modal inspection dialog for register and master screens.
 *
 * <p>Replaces all legacy docked side drawers across Bank Entry, Expense Entry,
 * Purchase Recon, Recon Supplier, User Access, Items Master, Inventory,
 * Customers, Suppliers and Master Data with a consistent, rich floating modal popup.</p>
 */
public final class RegisterDetailDrawer extends VBox {
    public record Field(String label, String value, String semantic) {
        public Field(String label, String value) {
            this(label, value, IconFactory.semanticForLabel(label));
        }
    }

    private final Label title = new Label("Details");
    private final Label subtitle = new Label("Review the selected record.");
    private final GridPane fieldsGrid = new GridPane();
    private final VBox extra = new VBox(10);
    private final HBox actions = new HBox(10);

    private Node ownerNode;
    private OwnedDialog<ButtonType> activeDialog;
    private Runnable closeAction = this::hideDrawer;

    public RegisterDetailDrawer() {
        // Keep VBox non-managed and invisible so embedding in legacy layouts has zero visual footprint
        setManaged(false);
        setVisible(false);
        setMinSize(0, 0);
        setPrefSize(0, 0);
        setMaxSize(0, 0);

        actions.setSpacing(10);
        actions.setAlignment(Pos.CENTER_RIGHT);

        fieldsGrid.setHgap(14);
        fieldsGrid.setVgap(10);
        ColumnConstraints c0 = new ColumnConstraints();
        c0.setPercentWidth(50);
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setPercentWidth(50);
        fieldsGrid.getColumnConstraints().addAll(c0, c1);
    }

    public void setCloseAction(Runnable closeAction) {
        this.closeAction = closeAction == null ? this::hideDrawer : closeAction;
    }

    public void showRecord(String heading, String caption, Field... recordFields) {
        showRecord(heading, caption, recordFields == null ? List.of() : List.of(recordFields));
    }

    public void showRecord(String heading, String caption, List<Field> recordFields) {
        fieldsGrid.getChildren().clear();
        fieldsGrid.getRowConstraints().clear();
        if (recordFields != null) {
            int row = 0;
            int col = 0;
            for (Field field : recordFields) {
                if (field == null) continue;
                Node fieldNode = fieldRow(field);
                boolean spanFull = isWideField(field);

                if (spanFull) {
                    if (col != 0) {
                        row++;
                        col = 0;
                    }
                    fieldsGrid.add(fieldNode, 0, row, 2, 1);
                    row++;
                    col = 0;
                } else {
                    fieldsGrid.add(fieldNode, col, row);
                    if (col == 0) {
                        col = 1;
                    } else {
                        col = 0;
                        row++;
                    }
                }
            }
        }

        buildAndShowDialog(heading, caption);
    }

    private void buildAndShowDialog(String heading, String caption) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> buildAndShowDialog(heading, caption));
            return;
        }

        if (activeDialog != null && activeDialog.isShowing()) {
            activeDialog.close();
            activeDialog = null;
        }

        Node anchor = ownerNode != null ? ownerNode : this;
        OwnedDialog<ButtonType> dialog = new OwnedDialog<>(anchor);
        this.activeDialog = dialog;

        String safeHeading = safe(heading, "Record Details");
        dialog.setTitle(safeHeading);
        dialog.setHeaderText(null);
        dialog.setResizable(true);

        DialogPane pane = dialog.getDialogPane();
        pane.getStyleClass().addAll("approved-dialog", "erp-document-inspection-dialog", "erp-detail-drawer-dialog", "erp-global-detail-drawer");
        pane.getButtonTypes().add(ButtonType.CLOSE);

        Node defaultButtonBar = pane.lookup(".button-bar");
        if (defaultButtonBar != null) {
            defaultButtonBar.setVisible(false);
            defaultButtonBar.setManaged(false);
        }

        // Header
        String semantic = resolveHeadingSemantic(safeHeading);
        pane.getStyleClass().add("dse-dialog-semantic-" + semantic);

        Region accent = new Region();
        accent.getStyleClass().add("dse-dialog-accent");
        accent.setMinHeight(5);
        accent.setPrefHeight(5);
        accent.setMaxHeight(5);

        StackPane iconBox = new StackPane(IconFactory.compactIcon(semantic, 22));
        iconBox.getStyleClass().add("dse-dialog-icon-box");

        subtitle.setText(safe(caption, "Review selected record."));
        subtitle.getStyleClass().setAll("dse-dialog-kicker");
        subtitle.setWrapText(true);

        title.setText(safeHeading);
        title.getStyleClass().setAll("dse-dialog-title");

        VBox titleBox = new VBox(2, subtitle, title);
        HBox.setHgrow(titleBox, Priority.ALWAYS);

        Button btnCloseTop = new Button();
        btnCloseTop.setGraphic(SemanticIconManager.compact("close", 14));
        btnCloseTop.getStyleClass().addAll("dse-dialog-close", "icon-button");
        btnCloseTop.getProperties().put("erp-icon-preserve", true);
        btnCloseTop.setTooltip(new Tooltip("Close"));
        btnCloseTop.setOnAction(e -> hideDrawer());

        HBox header = new HBox(12, iconBox, titleBox, btnCloseTop);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("dse-dialog-header");
        header.setPadding(new Insets(14, 20, 12, 20));

        // Body Content
        VBox body = new VBox(14, fieldsGrid, extra);
        body.setPadding(new Insets(16, 20, 16, 20));
        body.getStyleClass().add("erp-detail-drawer-body");

        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().addAll("erp-detail-drawer-scroll", "import-inner-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        // Footer / Action Bar
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button btnClose = new Button("Close");
        btnClose.getStyleClass().addAll("approved-button", "approved-secondary-button", "approved-cancel-button");
        btnClose.setGraphic(IconFactory.compactIcon("cancel", 14));
        btnClose.setMinWidth(Region.USE_PREF_SIZE);
        btnClose.setOnAction(e -> hideDrawer());

        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.setMinWidth(Region.USE_PREF_SIZE);

        HBox footer = new HBox(12, spacer, actions, btnClose);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setFillHeight(false);
        footer.setPadding(new Insets(14, 20, 14, 20));
        footer.getStyleClass().addAll("dse-dialog-footer", "erp-popup-footer");

        VBox dialogRoot = new VBox(accent, header, scroll, footer);
        dialogRoot.getStyleClass().addAll("dse-dialog-shell", "dse-dialog-shell-workspace", "erp-detail-drawer-dialog", "dse-dialog-semantic-" + semantic);

        int fieldRows = Math.max(1, (fieldsGrid.getChildren().size() + 1) / 2);
        boolean hasExtra = extra != null && extra.isManaged() && !extra.getChildren().isEmpty();
        double extraH = hasExtra ? 230.0 : 0.0;
        double calculatedH = 150.0 + (fieldRows * 44.0) + extraH;

        Rectangle2D screen = WindowUtilsFx.visualBoundsFor(anchor != null && anchor.getScene() != null ? anchor.getScene().getWindow() : null);
        double maxAvailW = Math.max(760, screen.getWidth() - 36);
        double maxAvailH = Math.max(480, screen.getHeight() - 48);

        double targetW = Math.min(maxAvailW, 940.0);
        double targetH = Math.min(maxAvailH, Math.max(340.0, Math.min(760.0, calculatedH)));

        dialogRoot.setPrefSize(targetW, targetH);
        dialogRoot.setMinSize(Math.min(760, maxAvailW), Math.min(300, maxAvailH));
        dialogRoot.setMaxSize(maxAvailW, targetH);
        pane.setPrefSize(targetW, targetH);
        pane.setMaxSize(maxAvailW, targetH);

        dialog.setOnShowing(ev -> {
            Node bar = pane.lookup(".button-bar");
            if (bar != null) {
                bar.setVisible(false);
                bar.setManaged(false);
            }
        });
        dialog.setOnShown(ev -> {
            if (pane.getScene() != null && pane.getScene().getWindow() instanceof javafx.stage.Stage stage) {
                stage.setWidth(targetW);
                stage.setHeight(targetH);
                WindowUtilsFx.fitDialogToOwnerScreen(stage, anchor);
            }
        });

        pane.getProperties().put("dse.dialog.presented", true);
        pane.getProperties().put("dse.dialog.workspace", true);
        pane.getStyleClass().add("erp-document-inspection-dialog");
        pane.setContent(dialogRoot);
        pane.setStyle("-fx-background-color: transparent; -fx-padding: 0;");

        dialog.setOnHidden(e -> {
            this.activeDialog = null;
            if (closeAction != null) {
                closeAction.run();
            }
        });

        dialog.show();
        ProfessionalUiEnhancer.enhance(dialogRoot);
    }

    public void setExtra(Node... nodes) {
        extra.getChildren().clear();
        if (nodes != null) {
            for (Node node : nodes) if (node != null) extra.getChildren().add(node);
        }
        extra.setManaged(!extra.getChildren().isEmpty());
        extra.setVisible(!extra.getChildren().isEmpty());
        ProfessionalUiEnhancer.enhance(extra);
    }

    public void setActions(Node... nodes) {
        actions.getChildren().clear();
        if (nodes != null) {
            for (Node node : nodes) {
                if (node != null) {
                    if (node instanceof Region r) {
                        r.setMinWidth(Region.USE_PREF_SIZE);
                    }
                    actions.getChildren().add(node);
                }
            }
        }
        actions.setManaged(!actions.getChildren().isEmpty());
        actions.setVisible(!actions.getChildren().isEmpty());
        ProfessionalUiEnhancer.enhance(actions);
    }

    public void hideDrawer() {
        if (activeDialog != null && activeDialog.isShowing()) {
            activeDialog.close();
        }
        activeDialog = null;
        setVisible(false);
        setManaged(false);
    }

    public boolean isOpen() {
        return activeDialog != null && activeDialog.isShowing();
    }

    public void attachBesideTable(TableView<?> table) {
        if (table != null) {
            this.ownerNode = table;
        }
    }

    public static Field field(String label, Object value, String semantic) {
        return new Field(label, display(value), semantic);
    }

    public static Field field(String label, Object value) {
        return new Field(label, display(value), IconFactory.semanticForLabel(label));
    }

    public static String statusSemantic(String status) {
        String value = status == null ? "" : status.toLowerCase(Locale.ROOT);
        if (value.contains("reconciled") || value.contains("active") || value.contains("paid") || value.contains("complete") || value.contains("deposit") || value.contains("credit") || value.contains("in stock")) return "complete";
        if (value.contains("partial")) return "partial";
        if (value.contains("review") || value.contains("pending") || value.contains("low") || value.contains("open")) return "warning";
        if (value.contains("inactive") || value.contains("locked") || value.contains("withdraw") || value.contains("debit") || value.contains("out of stock") || value.contains("overdue")) return "error";
        return "status";
    }

    private static String resolveHeadingSemantic(String heading) {
        if (heading == null) return "inspect";
        String lower = heading.toLowerCase(Locale.ROOT);
        if (lower.contains("bank")) return "bank";
        if (lower.contains("expense")) return "bank";
        if (lower.contains("recon")) return "inspect";
        if (lower.contains("user")) return "notification";
        if (lower.contains("item") || lower.contains("stock")) return "inspect";
        if (lower.contains("customer") || lower.contains("supplier") || lower.contains("party")) return "inspect";
        if (lower.contains("lookup") || lower.contains("master")) return "mapping";
        return "inspect";
    }

    private static boolean isWideField(Field field) {
        if (field == null) return false;
        String lbl = field.label() == null ? "" : field.label().toLowerCase(Locale.ROOT);
        if (lbl.contains("description") || lbl.contains("narration") || lbl.contains("notes")
                || lbl.contains("remarks") || lbl.contains("address") || lbl.contains("tax review")) {
            return true;
        }
        return field.value() != null && field.value().length() > 42;
    }

    private Node fieldRow(Field field) {
        String semantic = field.semantic() == null || field.semantic().isBlank()
            ? IconFactory.semanticForLabel(field.label()) : field.semantic();
        if (semantic == null || semantic.isBlank()) semantic = "identity";

        Label caption = new Label(safe(field.label(), "Field"));
        caption.getStyleClass().addAll("erp-drawer-caption", "detail-label", "field-label");
        caption.setGraphic(IconFactory.compactIcon(semantic, 13));
        caption.setGraphicTextGap(6);
        IconFactory.applySemanticLabelColour(caption, semantic);
        caption.getProperties().put("erp.label.icon.skip", true);

        Label value = new Label(safe(field.value(), "—"));
        value.setWrapText(true);
        value.getStyleClass().addAll("erp-drawer-value", "erp-drawer-value-" + colourClass(semantic));
        VBox.setVgrow(value, Priority.NEVER);

        VBox row = new VBox(4, caption, value);
        row.getStyleClass().addAll("erp-detail-drawer-field", "approved-card");
        row.setPadding(new Insets(8, 12, 8, 12));
        return row;
    }

    private static String colourClass(String semantic) {
        if (semantic == null) return "indigo";
        return switch (semantic.toLowerCase(Locale.ROOT)) {
            case "complete", "credit", "sale", "save" -> "green";
            case "error", "delete", "debit" -> "red";
            case "warning", "partial", "tax", "category", "reference" -> "orange";
            case "supplier", "attachment", "email", "phone", "inventory", "location" -> "teal";
            case "bank", "status", "reconcile", "notes", "calendar", "role", "security" -> "purple";
            default -> "blue";
        };
    }

    private static String display(Object value) {
        if (value == null) return "—";
        String text = String.valueOf(value).trim();
        return text.isBlank() ? "—" : text;
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
