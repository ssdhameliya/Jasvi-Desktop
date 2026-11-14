package org.example.util;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import org.example.service.SessionService;

import java.util.*;
import java.util.prefs.Preferences;

/** Per-user persistence for register column visibility and order. Width is Phase 5 dynamic. */
public final class RegisterColumnPreferences {
    private RegisterColumnPreferences() { }

    public static void install(TableView<?> table, String screenKey) {
        if (table == null || screenKey == null || screenKey.isBlank()) return;
        if (table.getProperties().putIfAbsent("dse.register.columns.installed", Boolean.TRUE) != null) return;
        table.setTableMenuButtonVisible(true);
        installReadableChooser(table);
        Platform.runLater(() -> restoreAndListen(table, screenKey));
    }

    private static void installReadableChooser(TableView<?> table) {
        Runnable attemptBind = () -> bindChooserButton(table);

        attemptBind.run();
        Platform.runLater(attemptBind);

        table.skinProperty().addListener((obs, oldSkin, newSkin) -> {
            if (newSkin != null) Platform.runLater(attemptBind);
        });

        table.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) Platform.runLater(attemptBind);
        });

        table.widthProperty().addListener((obs, oldW, newW) -> {
            if (newW != null && newW.doubleValue() > 0) attemptBind.run();
        });
    }

    private static void bindChooserButton(TableView<?> table) {
        Node chooser = table.lookup(".show-hide-columns-button");
        if (chooser == null) return;
        if (chooser.getProperties().putIfAbsent("dse.readable-column-menu", Boolean.TRUE) != null) return;

        chooser.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            e.consume();
            showColumnMenu(table, chooser);
        });
        chooser.addEventFilter(MouseEvent.MOUSE_RELEASED, javafx.event.Event::consume);
        chooser.setOnMouseClicked(e -> {
            e.consume();
            showColumnMenu(table, chooser);
        });
    }

    private static void showColumnMenu(TableView<?> table, Node anchor) {
        ContextMenu menu = new ContextMenu();
        menu.getStyleClass().add("register-column-chooser-menu");

        VBox contentBox = new VBox(6);
        contentBox.setPadding(new Insets(10, 12, 10, 12));
        contentBox.getStyleClass().add("register-column-chooser-card");

        // Header Title
        Label headerTitle = new Label("Visible Columns");
        headerTitle.getStyleClass().addAll("header-title", "metric-label");
        headerTitle.setGraphic(IconFactory.compactIcon("view", 15));

        List<TableColumn<?, ?>> targetColumns = new ArrayList<>();
        for (TableColumn<?, ?> col : table.getColumns()) {
            String label = resolveHeaderLabel(col);
            if (!isSelectionColumn(col, label)) {
                targetColumns.add(col);
            }
        }

        long visibleCount = targetColumns.stream().filter(TableColumn::isVisible).count();
        Label countPill = new Label(visibleCount + " / " + targetColumns.size() + " Active");
        countPill.getStyleClass().addAll("status-pill", "pill-primary");

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox headerRow = new HBox(8, headerTitle, headerSpacer, countPill);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        headerRow.setPadding(new Insets(0, 0, 4, 0));

        VBox itemsBox = new VBox(3);
        itemsBox.setPadding(new Insets(4, 0, 4, 0));

        List<CheckBox> checkBoxes = new ArrayList<>();

        for (TableColumn<?, ?> column : targetColumns) {
            String labelText = resolveHeaderLabel(column);
            String iconSem = semanticIcon(labelText);

            CheckBox check = new CheckBox();
            check.setSelected(column.isVisible());
            check.getStyleClass().add("approved-checkbox");
            checkBoxes.add(check);

            Label label = new Label(labelText);
            label.getStyleClass().add("metric-label");
            label.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(label, Priority.ALWAYS);

            HBox row = new HBox(10, check, IconFactory.compactIcon(iconSem, 15), label);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(5, 8, 5, 8));
            row.getStyleClass().add("register-column-item-row");
            row.setCursor(javafx.scene.Cursor.HAND);

            Runnable toggle = () -> {
                if (column.isVisible() && table.getVisibleLeafColumns().size() <= 1) {
                    check.setSelected(true);
                    return;
                }
                boolean nextState = !column.isVisible();
                column.setVisible(nextState);
                check.setSelected(nextState);
                long current = targetColumns.stream().filter(TableColumn::isVisible).count();
                countPill.setText(current + " / " + targetColumns.size() + " Active");
                DynamicTableLayoutManager.requestLayout(table);
            };

            row.setOnMouseClicked(e -> {
                e.consume();
                toggle.run();
            });

            check.setOnAction(e -> {
                e.consume();
                toggle.run();
            });

            itemsBox.getChildren().add(row);
        }

        ScrollPane scrollPane = new ScrollPane(itemsBox);
        scrollPane.setFitToWidth(true);
        scrollPane.setMaxHeight(360);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.getStyleClass().addAll("dse-dialog-scroll", "edge-to-edge");
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent; -fx-padding: 0;");

        // Action Buttons
        Button btnShowAll = new Button("Show All");
        btnShowAll.setGraphic(IconFactory.compactIcon("complete", 12));
        btnShowAll.getStyleClass().addAll("approved-button", "approved-secondary-button");
        btnShowAll.setOnAction(e -> {
            targetColumns.forEach(c -> c.setVisible(true));
            checkBoxes.forEach(cb -> cb.setSelected(true));
            countPill.setText(targetColumns.size() + " / " + targetColumns.size() + " Active");
            DynamicTableLayoutManager.requestLayout(table);
        });

        Button btnReset = new Button("Reset");
        btnReset.setGraphic(IconFactory.compactIcon("reset", 12));
        btnReset.getStyleClass().addAll("approved-button", "approved-amber-button");
        btnReset.setOnAction(e -> {
            targetColumns.forEach(c -> c.setVisible(true));
            checkBoxes.forEach(cb -> cb.setSelected(true));
            countPill.setText(targetColumns.size() + " / " + targetColumns.size() + " Active");
            DynamicTableLayoutManager.requestLayout(table);
        });

        HBox actionsRow = new HBox(8, btnShowAll, btnReset);
        actionsRow.setAlignment(Pos.CENTER_RIGHT);
        actionsRow.setPadding(new Insets(6, 0, 2, 0));

        contentBox.getChildren().addAll(headerRow, new Separator(), scrollPane, new Separator(), actionsRow);

        CustomMenuItem customMenuItem = new CustomMenuItem(contentBox, false);
        customMenuItem.setHideOnClick(false);
        menu.getItems().setAll(customMenuItem);

        double popupWidth = 330;
        menu.show(anchor, Side.BOTTOM, -popupWidth + anchor.getLayoutBounds().getWidth(), 4);
    }

    public static String resolveHeaderLabel(TableColumn<?, ?> column) {
        if (column == null) return "Column";
        Object stored = column.getProperties().get("erp-header-label");
        if (stored instanceof String s && !s.isBlank()) return s.trim();
        String text = column.getText();
        if (text != null && !text.isBlank()) return text.trim();
        Node graphic = column.getGraphic();
        if (graphic instanceof Label lbl && lbl.getText() != null && !lbl.getText().isBlank()) {
            return lbl.getText().trim();
        }
        if (graphic instanceof javafx.scene.Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                if (child instanceof Label l && l.getText() != null && !l.getText().isBlank()) {
                    return l.getText().trim();
                }
            }
        }
        String id = column.getId();
        if (id != null && !id.isBlank()) {
            return humanizeColumnId(id);
        }
        return "Column";
    }

    private static String humanizeColumnId(String id) {
        String s = id.startsWith("col") && id.length() > 3 ? id.substring(3) : id;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isUpperCase(c) && i > 0 && !Character.isUpperCase(s.charAt(i - 1))) {
                sb.append(' ');
            }
            sb.append(c);
        }
        String res = sb.toString().trim();
        if ("Invoice".equalsIgnoreCase(res)) return "Invoice No.";
        if ("Quotation".equalsIgnoreCase(res)) return "Quotation No.";
        if ("Due".equalsIgnoreCase(res)) return "Payment Due";
        if ("Mail".equalsIgnoreCase(res)) return "Email";
        if ("Total".equalsIgnoreCase(res)) return "Total Amount";
        return res;
    }

    private static boolean isSelectionColumn(TableColumn<?, ?> column, String label) {
        if (column == null) return true;
        if (Boolean.TRUE.equals(column.getProperties().get("erp-global-checkbox"))) return true;
        if (column.getGraphic() instanceof CheckBox) return true;
        String id = column.getId() == null ? "" : column.getId().toLowerCase(Locale.ROOT);
        return label.equals("#") || label.equals("✓") || label.equalsIgnoreCase("select") || id.contains("select");
    }

    private static String semanticIcon(String title) {
        String v = title == null ? "" : title.toLowerCase(Locale.ROOT);
        if (v.contains("date")) return "calendar";
        if (v.contains("customer") || v.contains("supplier")) return "user";
        if (v.contains("mobile") || v.contains("phone")) return "phone";
        if (v.contains("mail") || v.contains("email")) return "email";
        if (v.contains("amount") || v.contains("paid") || v.contains("pending") || v.contains("balance") || v.contains("fee") || v.contains("total")) return "currency";
        if (v.contains("due")) return "reminder";
        if (v.contains("status")) return "complete";
        if (v.contains("action")) return "list";
        if (v.contains("gst") || v.contains("tax")) return "tax";
        if (v.contains("invoice") || v.contains("quotation") || v.contains("document") || v.contains("code") || v.contains("number") || v.contains("ref")) return "document";
        if (v.contains("item")) return "item";
        return "view";
    }

    private static void restoreAndListen(TableView<?> table, String screenKey) {
        Preferences prefs = prefs(screenKey);
        boolean[] restoring = {true};
        try {
            restoreOrder(table, prefs.get("order", ""));
            for (TableColumn<?, ?> column : table.getColumns()) {
                String key = key(column);
                column.setVisible(prefs.getBoolean(key + ".visible", column.isVisible()));
                // Remove legacy per-user widths once. Phase 5 sizes from live content.
                prefs.remove(key + ".width");
            }
        } finally {
            restoring[0] = false;
        }

        for (TableColumn<?, ?> column : table.getColumns()) {
            String key = key(column);
            column.visibleProperty().addListener((o, a, b) -> {
                if (!restoring[0]) prefs.putBoolean(key + ".visible", b);
            });
        }
        @SuppressWarnings({"rawtypes", "unchecked"})
        ListChangeListener orderListener = change -> {
            if (!restoring[0]) prefs.put("order", table.getColumns().stream().map(RegisterColumnPreferences::key).reduce((a,b)->a+","+b).orElse(""));
        };
        table.getColumns().addListener(orderListener);
    }

    private static void restoreOrder(TableView<?> table, String saved) {
        if (saved == null || saved.isBlank()) return;
        Map<String, TableColumn<?, ?>> byKey = new LinkedHashMap<>();
        for (TableColumn<?, ?> c : table.getColumns()) byKey.put(key(c), c);
        List<TableColumn<?, ?>> ordered = new ArrayList<>();
        for (String token : saved.split(",")) {
            TableColumn<?, ?> c = byKey.remove(token);
            if (c != null) ordered.add(c);
        }
        ordered.addAll(byKey.values());
        if (ordered.size() == table.getColumns().size()) {
            @SuppressWarnings({"rawtypes", "unchecked"})
            List raw = ordered;
            table.getColumns().setAll(raw);
        }
    }

    private static Preferences prefs(String screenKey) {
        String user = SessionService.current() == null ? "anonymous" : String.valueOf(SessionService.current().getId());
        return Preferences.userRoot().node("org/example/dseerp/register-columns/" + safe(user) + "/" + safe(screenKey));
    }

    private static String key(TableColumn<?, ?> c) {
        String value = c.getId();
        if (value == null || value.isBlank()) value = resolveHeaderLabel(c);
        return safe(value == null || value.isBlank() ? "column" : value);
    }

    private static String safe(String value) { return value.replaceAll("[^A-Za-z0-9._-]", "_"); }
}
