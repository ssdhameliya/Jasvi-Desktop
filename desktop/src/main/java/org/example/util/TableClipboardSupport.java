package org.example.util;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.text.Text;
import javafx.util.Callback;
import javafx.util.Duration;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * Universal table clipboard support for Jasvi-Desktop (Option 1).
 * Strictly operates on TableView registers without touching any Labels or form inputs.
 * Provides Ctrl+C cell/row copying and right-click context menu across
 * all registers and popup/modal tables.
 * Single-click inspection dialog opening is 100% preserved.
 */
public final class TableClipboardSupport {

    private static final String TABLE_CLIPBOARD_INSTALLED = "erp.table.clipboard.installed";
    private static final String CLICKED_CELL = "erp.table.clicked.cell";
    private static final String CLICKED_COL = "erp.table.clicked.col";
    private static final String CLICKED_ROW = "erp.table.clicked.row";
    private static final String CLICKED_ITEM = "erp.table.clicked.item";

    private TableClipboardSupport() {}

    /**
     * Copies text cleanly to the Windows OS system clipboard.
     * Writes directly to Win32 CF_UNICODETEXT Global Memory via AWT with owner = null,
     * ensuring that all external applications (Notepad, Excel, Chrome, chat) can paste
     * the text immediately and permanently without lock contention.
     * Also synchronizes to the JavaFX system clipboard for internal JavaFX controls.
     */
    public static boolean copyText(String text) {
        if (text == null || text.isBlank()) return false;
        String clean = text.trim();

        // 1. Primary: Win32 CF_UNICODETEXT Global Memory (owner = null guarantees zero lock contention)
        boolean awtOk = false;
        for (int i = 0; i < 5; i++) {
            try {
                StringSelection selection = new StringSelection(clean);
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
                awtOk = true;
                break;
            } catch (Throwable t) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ignored) {}
            }
        }

        // 2. Also synchronize to JavaFX system clipboard for internal JavaFX controls
        boolean fxOk = false;
        try {
            Clipboard clipboard = Clipboard.getSystemClipboard();
            ClipboardContent content = new ClipboardContent();
            content.putString(clean);
            fxOk = clipboard.setContent(content);
        } catch (Throwable ignored) {}

        // 3. Virtual thread safety net: guarantees OS clipboard acquisition even if native Win32 window is mid-destroy
        Thread.ofVirtual().start(() -> {
            for (int i = 0; i < 10; i++) {
                try {
                    StringSelection selection = new StringSelection(clean);
                    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
                    break;
                } catch (Throwable t) {
                    try {
                        Thread.sleep(40);
                    } catch (InterruptedException ignored) {}
                }
            }
        });

        return awtOk || fxOk;
    }

    /**
     * Installs universal mouse cell tracking, Ctrl+C accelerator, and right-click
     * context menu on any TableView across registers and popup dialogs.
     */
    public static void install(TableView<?> table) {
        if (table == null) return;
        if (Boolean.TRUE.equals(table.getProperties().get(TABLE_CLIPBOARD_INSTALLED))) {
            return;
        }
        table.getProperties().put(TABLE_CLIPBOARD_INSTALLED, true);

        // 1. Mouse Pressed Event Filter: Captures right-clicks directly from event target hierarchy
        table.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() == MouseButton.SECONDARY) {
                captureHitFromEvent(table, event.getTarget(), event.getSceneX(), event.getSceneY(), true);
            }
        });

        // 2. Context Menu Requested Filter: Reinforces cell/row tracking
        table.addEventFilter(ContextMenuEvent.CONTEXT_MENU_REQUESTED, event -> {
            captureHitFromEvent(table, event.getTarget(), event.getSceneX(), event.getSceneY(), true);
        });

        // 3. Keyboard Accelerator: Ctrl+C / Cmd+C
        table.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.isShortcutDown() && event.getCode() == KeyCode.C) {
                if (table.getEditingCell() != null) {
                    return; // allow active text cell editor to handle Ctrl+C
                }
                if (copyTableSelection(table)) {
                    event.consume();
                }
            }
        });

        // 4. Right-Click Context Menu: Robust deferred extraction on click
        if (table.getContextMenu() == null) {
            ContextMenu cm = new ContextMenu();
            MenuItem miCopyCell = new MenuItem("Copy Cell", IconFactory.compactIcon("copy", 14));
            MenuItem miCopyRow = new MenuItem("Copy Row", IconFactory.compactIcon("duplicate", 14));
            cm.getItems().addAll(miCopyCell, miCopyRow);
            IconFactory.decorateActionMenu(cm);

            // Never disable menu items when the table has records
            cm.setOnShowing(e -> {
                boolean hasRows = table.getItems() != null && !table.getItems().isEmpty();
                miCopyCell.setDisable(!hasRows);
                miCopyRow.setDisable(!hasRows);
            });

            // Defer execution so the ContextMenu native Win32 popup completely dismisses
            // and releases its OLE clipboard lock before writing to OS system clipboard
            miCopyCell.setOnAction(ev -> Platform.runLater(() -> copyFocusedCell(table)));
            miCopyRow.setOnAction(ev -> Platform.runLater(() -> copySelectedRows(table)));

            table.setContextMenu(cm);
        }
    }

    /**
     * Resolves the clicked row, item, cell, and column by traversing up the scene graph
     * hierarchy from event target, falling back to geometric scene coordinates.
     */
    private static void captureHitFromEvent(TableView<?> table, Object eventTarget, double sceneX, double sceneY, boolean selectRow) {
        if (table == null || table.getItems() == null || table.getItems().isEmpty()) return;

        TableCell<?, ?> foundCell = null;
        TableRow<?> foundRow = null;

        // Traverse up node hierarchy from clicked target
        if (eventTarget instanceof Node node) {
            Node curr = node;
            while (curr != null && curr != table) {
                if (curr instanceof TableCell<?, ?> tc && foundCell == null) {
                    foundCell = tc;
                }
                if (curr instanceof TableRow<?> tr && foundRow == null) {
                    foundRow = tr;
                    break;
                }
                curr = curr.getParent();
            }
        }

        int rowIndex = -1;
        Object rowItem = null;
        TableColumn<?, ?> col = null;

        if (foundRow != null && !foundRow.isEmpty() && foundRow.getItem() != null) {
            rowIndex = foundRow.getIndex();
            rowItem = foundRow.getItem();
        }

        if (foundCell != null && foundCell.getTableColumn() != null) {
            col = foundCell.getTableColumn();
            table.getProperties().put(CLICKED_CELL, foundCell);
        } else {
            table.getProperties().remove(CLICKED_CELL);
        }

        // Geometric fallback if target hierarchy traversal didn't locate row or column
        if (rowIndex < 0) {
            rowIndex = findRowAtSceneY(table, sceneY);
        }
        if (col == null || isIgnoredColumn(col)) {
            col = findColumnAtSceneX(table, sceneX);
        }

        // Selection model fallback
        if (rowIndex < 0 || rowIndex >= table.getItems().size()) {
            if (table.getSelectionModel() != null && !table.getSelectionModel().isEmpty()) {
                rowIndex = table.getSelectionModel().getSelectedIndex();
            }
        }

        if (rowIndex >= 0 && rowIndex < table.getItems().size()) {
            if (rowItem == null) {
                rowItem = table.getItems().get(rowIndex);
            }
            table.getProperties().put(CLICKED_ROW, rowIndex);
            table.getProperties().put(CLICKED_ITEM, rowItem);

            if (col != null && !isIgnoredColumn(col)) {
                table.getProperties().put(CLICKED_COL, col);
            }
            if (selectRow && table.getSelectionModel() != null) {
                table.getSelectionModel().clearAndSelect(rowIndex);
            }
        }
    }

    public static int findRowAtSceneY(TableView<?> table, double sceneY) {
        if (table == null || table.getItems() == null || table.getItems().isEmpty()) return -1;
        for (Node n : table.lookupAll(".table-row-cell")) {
            if (n instanceof TableRow<?> r && !r.isEmpty() && r.isVisible()) {
                Point2D p = r.localToScene(0, 0);
                if (p != null) {
                    double minY = p.getY();
                    double maxY = minY + r.getHeight();
                    if (sceneY >= minY && sceneY < maxY) {
                        return r.getIndex();
                    }
                }
            }
        }
        return -1;
    }

    public static TableColumn<?, ?> findColumnAtSceneX(TableView<?> table, double sceneX) {
        if (table == null) return null;
        for (Node n : table.lookupAll(".table-cell")) {
            if (n instanceof TableCell<?, ?> c && c.getTableColumn() != null && !isIgnoredColumn(c.getTableColumn()) && c.isVisible()) {
                Point2D p = c.localToScene(0, 0);
                if (p != null) {
                    double minX = p.getX();
                    double maxX = minX + c.getWidth();
                    if (sceneX >= minX && sceneX < maxX) {
                        return c.getTableColumn();
                    }
                }
            }
        }

        List<? extends TableColumn<?, ?>> visibleCols = table.getVisibleLeafColumns().stream()
                .filter(c -> c.isVisible() && !isIgnoredColumn(c))
                .toList();
        if (visibleCols.isEmpty()) return null;

        Point2D tableScene = table.localToScene(0, 0);
        double startX = tableScene != null ? tableScene.getX() : 0;
        double currentX = startX;
        for (TableColumn<?, ?> c : visibleCols) {
            double w = c.getWidth();
            if (sceneX >= currentX && sceneX < currentX + w) {
                return c;
            }
            currentX += w;
        }
        return visibleCols.get(0);
    }

    /**
     * Extracts and copies selected cell or row data from the TableView.
     */
    public static boolean copyTableSelection(TableView<?> table) {
        if (table == null) return false;

        TableColumn<?, ?> clickedCol = (TableColumn<?, ?>) table.getProperties().get(CLICKED_COL);
        Integer clickedRow = (Integer) table.getProperties().get(CLICKED_ROW);
        if (table.getSelectionModel() != null && table.getSelectionModel().getSelectedItems().size() == 1
                && clickedCol != null && clickedRow != null && !isIgnoredColumn(clickedCol)) {
            TableCell<?, ?> clickedCell = (TableCell<?, ?>) table.getProperties().get(CLICKED_CELL);
            String cellVal = extractCellText(clickedCell, clickedCol, clickedRow, table);
            if (cellVal != null && !cellVal.isBlank()) {
                return copyText(cellVal);
            }
        }

        return copySelectedRows(table);
    }

    /**
     * Copies the focused or clicked cell's text to the system clipboard.
     */
    public static boolean copyFocusedCell(TableView<?> table) {
        if (table == null || table.getItems() == null || table.getItems().isEmpty()) return false;

        Integer rowIndex = (Integer) table.getProperties().get(CLICKED_ROW);
        if (rowIndex == null || rowIndex < 0 || rowIndex >= table.getItems().size()) {
            if (table.getSelectionModel() != null && !table.getSelectionModel().isEmpty()) {
                rowIndex = table.getSelectionModel().getSelectedIndex();
            }
        }
        if (rowIndex == null || rowIndex < 0 || rowIndex >= table.getItems().size()) {
            rowIndex = 0;
        }

        TableColumn<?, ?> col = (TableColumn<?, ?>) table.getProperties().get(CLICKED_COL);
        if (col == null || isIgnoredColumn(col)) {
            if (table.getFocusModel() != null && table.getFocusModel().getFocusedCell() != null) {
                TableColumn<?, ?> focCol = table.getFocusModel().getFocusedCell().getTableColumn();
                if (focCol != null && !isIgnoredColumn(focCol)) {
                    col = focCol;
                }
            }
        }
        if (col == null || isIgnoredColumn(col)) {
            col = table.getVisibleLeafColumns().stream()
                    .filter(c -> c.isVisible() && !isIgnoredColumn(c))
                    .findFirst()
                    .orElse(null);
        }
        if (col == null) return false;

        TableCell<?, ?> cell = (TableCell<?, ?>) table.getProperties().get(CLICKED_CELL);
        String text = extractCellText(cell, col, rowIndex, table);
        if (text != null && !text.isBlank()) {
            return copyText(text);
        }

        return copySelectedRows(table);
    }

    /**
     * Extracts a single row as Tab-Separated Values (TSV).
     */
    public static String extractRowTsv(TableView<?> table, int rowIndex) {
        if (table == null || table.getItems() == null || rowIndex < 0 || rowIndex >= table.getItems().size()) {
            return "";
        }
        List<? extends TableColumn<?, ?>> visibleCols = table.getVisibleLeafColumns().stream()
                .filter(c -> c.isVisible() && !isIgnoredColumn(c))
                .toList();
        if (visibleCols.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < visibleCols.size(); c++) {
            if (c > 0) sb.append("\t");
            TableColumn<?, ?> col = visibleCols.get(c);
            String valStr = extractCellText(null, col, rowIndex, table);
            sb.append(valStr != null ? valStr : "");
        }
        return sb.toString();
    }

    /**
     * Copies all selected rows as Tab-Separated Values (TSV).
     */
    public static boolean copySelectedRows(TableView<?> table) {
        if (table == null || table.getItems() == null || table.getItems().isEmpty()) return false;

        if (table.getSelectionModel() != null && table.getSelectionModel().isEmpty()) {
            Integer clickedRow = (Integer) table.getProperties().get(CLICKED_ROW);
            if (clickedRow != null && clickedRow >= 0 && clickedRow < table.getItems().size()) {
                table.getSelectionModel().select(clickedRow);
            }
        }

        List<? extends TableColumn<?, ?>> visibleCols = table.getVisibleLeafColumns().stream()
                .filter(c -> c.isVisible() && !isIgnoredColumn(c))
                .toList();
        if (visibleCols.isEmpty()) return false;

        ObservableList<Integer> selectedIndices = table.getSelectionModel() != null ? table.getSelectionModel().getSelectedIndices() : null;
        if (selectedIndices == null || selectedIndices.isEmpty()) {
            Integer clickedRow = (Integer) table.getProperties().get(CLICKED_ROW);
            if (clickedRow != null && clickedRow >= 0 && clickedRow < table.getItems().size()) {
                selectedIndices = FXCollections.observableArrayList(clickedRow);
            } else {
                selectedIndices = FXCollections.observableArrayList(0);
            }
        }

        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Integer rIdx : selectedIndices) {
            if (rIdx == null || rIdx < 0 || rIdx >= table.getItems().size()) continue;
            if (!sb.isEmpty()) sb.append("\n");
            for (int c = 0; c < visibleCols.size(); c++) {
                if (c > 0) sb.append("\t");
                TableColumn<?, ?> col = visibleCols.get(c);
                String valStr = extractCellText(null, col, rIdx, table);
                sb.append(valStr != null ? valStr : "");
            }
            count++;
        }

        String tsv = sb.toString();
        if (!tsv.isBlank()) {
            return copyText(tsv);
        }
        return false;
    }

    /**
     * Robust extraction of cell text across direct cell text, custom graphics (labels/pills),
     * cell item values, and column cellData factories.
     */
    public static String extractCellText(TableCell<?, ?> cell, TableColumn<?, ?> col, int rowIndex, TableView<?> table) {
        if (cell != null && cell.getIndex() == rowIndex) {
            String txt = cell.getText();
            if (txt != null && !txt.isBlank()) {
                return txt.trim();
            }
            if (cell.getGraphic() != null) {
                String graphicText = extractTextFromNode(cell.getGraphic());
                if (graphicText != null && !graphicText.isBlank()) {
                    return graphicText.trim();
                }
            }
            Object item = cell.getItem();
            if (item != null) {
                String formatted = formatValue(item);
                if (!formatted.isBlank()) {
                    return formatted;
                }
            }
        }

        if (col != null && rowIndex >= 0 && table != null && table.getItems() != null && rowIndex < table.getItems().size()) {
            try {
                Object val = col.getCellData(rowIndex);
                if (val != null) {
                    String formatted = formatValue(val);
                    if (!formatted.isBlank()) {
                        return formatted;
                    }
                }
            } catch (Throwable ignored) {}

            try {
                Object rowItem = table.getItems().get(rowIndex);
                if (rowItem != null) {
                    @SuppressWarnings("unchecked")
                    TableColumn<Object, ?> rawCol = (TableColumn<Object, ?>) col;
                    Object val = rawCol.getCellData(rowItem);
                    if (val != null) {
                        String formatted = formatValue(val);
                        if (!formatted.isBlank()) {
                            return formatted;
                        }
                    }
                }
            } catch (Throwable ignored) {}

            // Layer 2c: Fallback to rendered TableCell in scene graph if cell was null (e.g. during TSV row export)
            try {
                for (Node n : table.lookupAll(".table-row-cell")) {
                    if (n instanceof TableRow<?> r && r.getIndex() == rowIndex && !r.isEmpty()) {
                        for (Node cn : r.lookupAll(".table-cell")) {
                            if (cn instanceof TableCell<?, ?> c && c.getTableColumn() == col) {
                                String txt = c.getText();
                                if (txt != null && !txt.isBlank()) return txt.trim();
                                if (c.getGraphic() != null) {
                                    String gt = extractTextFromNode(c.getGraphic());
                                    if (gt != null && !gt.isBlank()) return gt.trim();
                                }
                                if (c.getItem() != null) {
                                    String fmt = formatValue(c.getItem());
                                    if (!fmt.isBlank()) return fmt;
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        return "";
    }

    private static String extractTextFromNode(Node node) {
        if (node == null) return null;
        if (node instanceof Labeled labeled) {
            String t = labeled.getText();
            if (t != null && !t.isBlank()) return t;
        }
        if (node instanceof Text text) {
            String t = text.getText();
            if (t != null && !t.isBlank()) return t;
        }
        if (node instanceof TextInputControl tic) {
            String t = tic.getText();
            if (t != null && !t.isBlank()) return t;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                String t = extractTextFromNode(child);
                if (t != null && !t.isBlank()) return t;
            }
        }
        return null;
    }

    /**
     * Checks if a column is an action, checkbox, or control column that should not be copied.
     */
    public static boolean isIgnoredColumn(TableColumn<?, ?> col) {
        if (col == null) return true;
        String text = col.getText() == null ? "" : col.getText().trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty() && (col.getGraphic() != null || !col.isSortable())) {
            return true; // Checkbox selection column
        }
        return text.equals("action") || text.equals("actions") || text.equals("select") || text.equals("manage");
    }

    /**
     * Formats an arbitrary table cell object to a clean string.
     */
    public static String formatValue(Object val) {
        if (val == null) return "";
        if (val instanceof BigDecimal bd) {
            return bd.toPlainString();
        }
        if (val instanceof Double d) {
            return String.format(Locale.ROOT, "%.2f", d);
        }
        if (val instanceof Float f) {
            return String.format(Locale.ROOT, "%.2f", f);
        }
        if (val instanceof LocalDate ld) {
            return BusinessClock.formatDate(ld);
        }
        if (val instanceof Instant inst) {
            return BusinessClock.formatTimestamp(inst);
        }
        return String.valueOf(val).trim();
    }

    /**
     * Non-intrusive feedback hook. Avoids native Win32 popup windows during ContextMenu dismissal.
     */
    public static void showFeedback(Node node, String message) {
        // Intentionally no-op to prevent native Glass.dll popup window collisions during ContextMenu hide
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
