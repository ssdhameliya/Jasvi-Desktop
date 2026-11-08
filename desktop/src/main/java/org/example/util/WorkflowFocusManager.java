package org.example.util;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Central business-order focus sequence and keyboard navigation for ERP screens and dialogs. */
public final class WorkflowFocusManager {
    private static final String FOCUS_INSTALLED_PROP = "erp.workflow.focus.installed";
    private static final String ACTIVE_FOCUS_CLASS = "erp-input-active-focused";

    private WorkflowFocusManager() {}

    /**
     * Installs explicit ordered focus chain for business entry screens (Sales, Purchase, Quotation, Dialogs).
     */
    public static void install(List<? extends Node> ordered) {
        List<Node> nodes = new ArrayList<>();
        if (ordered != null) {
            ordered.stream().filter(Objects::nonNull).forEach(nodes::add);
        }
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            node.getProperties().put(FOCUS_INSTALLED_PROP, true);
            attachInputFocusHighlightAndSelection(node);
            final int index = i;

            @SuppressWarnings("unchecked")
            javafx.event.EventHandler<KeyEvent> oldFilter =
                    (javafx.event.EventHandler<KeyEvent>) node.getProperties().get("erp.workflow.focus.filter");
            if (oldFilter != null) {
                node.removeEventFilter(KeyEvent.KEY_PRESSED, oldFilter);
                if (node instanceof ComboBox<?> cb && cb.getEditor() != null) {
                    cb.getEditor().removeEventFilter(KeyEvent.KEY_PRESSED, oldFilter);
                } else if (node instanceof DatePicker dp && dp.getEditor() != null) {
                    dp.getEditor().removeEventFilter(KeyEvent.KEY_PRESSED, oldFilter);
                }
            }

            javafx.event.EventHandler<KeyEvent> keyFilter = event -> {
                if (event.getCode() == KeyCode.TAB) {
                    focus(nodes, index, event.isShiftDown() ? -1 : 1);
                    event.consume();
                    return;
                }
                if (event.getCode() == KeyCode.ENTER && advanceOnEnter(node)) {
                    if (node instanceof ComboBox<?> cb && cb.isShowing()) {
                        // Dropdown popup is open: allow ComboBox to commit the highlighted item first
                        return;
                    }
                    focus(nodes, index, 1);
                    event.consume();
                }
            };
            node.getProperties().put("erp.workflow.focus.filter", keyFilter);
            node.addEventFilter(KeyEvent.KEY_PRESSED, keyFilter);
            if (node instanceof ComboBox<?> cb) {
                if (cb.getEditor() != null) {
                    cb.getEditor().addEventFilter(KeyEvent.KEY_PRESSED, keyFilter);
                }
                cb.editorProperty().addListener((obs, oldEd, newEd) -> {
                    if (newEd != null) newEd.addEventFilter(KeyEvent.KEY_PRESSED, keyFilter);
                });
            } else if (node instanceof DatePicker dp) {
                if (dp.getEditor() != null) {
                    dp.getEditor().addEventFilter(KeyEvent.KEY_PRESSED, keyFilter);
                }
                dp.editorProperty().addListener((obs, oldEd, newEd) -> {
                    if (newEd != null) newEd.addEventFilter(KeyEvent.KEY_PRESSED, keyFilter);
                });
            }
        }
    }

    public static void initial(Node node) {
        if (node != null) {
            Platform.runLater(() -> focusDirectly(node));
        }
    }

    public static void selectAllOnFocus(TextField field) {
        if (field != null) {
            field.focusedProperty().addListener((o, was, focused) -> {
                if (Boolean.TRUE.equals(focused)) Platform.runLater(field::selectAll);
            });
        }
    }

    /**
     * Excludes auxiliary or secondary action controls (such as + Add Customer, date range pills, export icons)
     * from keyboard tab traversal to maintain high-speed accounting data entry.
     */
    public static void excludeFromTab(Node... nodes) {
        if (nodes == null) return;
        for (Node n : nodes) {
            if (n != null) n.setFocusTraversable(false);
        }
    }

    /**
     * Installs smart loop breakout on an item search field:
     * If the field is blank/empty when Tab or Down Arrow is pressed, smoothly bypasses
     * the line inputs (Qty, Rate, Discount, GST, Add Line) and transfers focus directly to
     * the next target zone (e.g. GST Type, Transport, or Save).
     */
    public static void installSmartLoopBreakout(TextField itemSearchInput, Node nextTargetZone) {
        if (itemSearchInput == null || nextTargetZone == null) return;
        itemSearchInput.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.TAB && !event.isShiftDown()) {
                String text = itemSearchInput.getText();
                if (text == null || text.trim().isEmpty()) {
                    focusDirectly(nextTargetZone);
                    event.consume();
                }
            }
        });
    }

    /**
     * Installs global quick-save accelerators (Ctrl+S, Ctrl+Enter, F10) on a container root.
     */
    public static void installQuickSaveAccelerator(Node root, Button saveButton) {
        if (root == null || saveButton == null) return;
        root.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if ((event.isControlDown() && (event.getCode() == KeyCode.S || event.getCode() == KeyCode.ENTER))
                    || event.getCode() == KeyCode.F10
                    || (event.isAltDown() && event.getCode() == KeyCode.S)) {
                if (!saveButton.isDisabled() && saveButton.isVisible()) {
                    saveButton.fire();
                    event.consume();
                }
            }
        });
    }

    /**
     * Automatically enhances any screen or modal dialog root:
     * 1. Makes all ComboBoxes searchable via SearchableComboBoxHelper.
     * 2. Silences non-data-entry buttons and date-range preset pills from tab traversal.
     * 3. Attaches universal active focus highlighting and selectAll on inputs.
     * 4. Enforces natural left-to-right, row-by-row focus order if no explicit business chain was set.
     */
    public static void autoEnhanceScreen(Node root) {
        if (!(root instanceof Parent parent)) return;
        if (Boolean.TRUE.equals(parent.getProperties().get(FOCUS_INSTALLED_PROP))) return;

        // Step 1: Universal Searchable Dropdowns across all screens & popups
        SearchableComboBoxHelper.makeAllSearchable(parent);

        // Step 2: Silence auxiliary buttons so they don't break high-speed tab flow
        silenceAuxiliaryButtons(parent);

        // Step 3: Collect interactive controls
        List<Node> candidates = new ArrayList<>();
        collectInteractiveControls(parent, candidates);

        TextField primarySearch = null;
        for (Node node : candidates) {
            attachInputFocusHighlightAndSelection(node);
            if (node instanceof TextArea ta) {
                installTextAreaTabForwarding(ta);
            } else if (node instanceof TextField tf && primarySearch == null) {
                String id = tf.getId() != null ? tf.getId().toLowerCase() : "";
                if ((id.contains("search") || tf.getStyleClass().stream().anyMatch(c -> c.contains("search")))
                        && !id.contains("item") && tf.getStyleClass().stream().noneMatch(c -> c.contains("item-search"))) {
                    primarySearch = tf;
                }
            }
        }

        if (primarySearch != null) {
            installSearchToTableNavigation(primarySearch, parent);
        }

        boolean hasExplicitChain = candidates.stream()
                .anyMatch(n -> Boolean.TRUE.equals(n.getProperties().get(FOCUS_INSTALLED_PROP)));

        if (!hasExplicitChain && candidates.size() > 1) {
            // If already attached to scene, sort immediately; otherwise wait for scene attachment
            if (parent.getScene() != null) {
                sortAndInstallCandidates(parent, candidates);
            } else {
                parent.sceneProperty().addListener((obs, oldScene, newScene) -> {
                    if (newScene != null) {
                        Platform.runLater(() -> sortAndInstallCandidates(parent, candidates));
                    }
                });
            }
        }
        parent.getProperties().put(FOCUS_INSTALLED_PROP, true);
    }

    private static void sortAndInstallCandidates(Parent parent, List<Node> candidates) {
        if (candidates.isEmpty()) return;
        candidates.sort(Comparator.comparingDouble((Node n) -> {
            Bounds b = n.localToScene(n.getBoundsInLocal());
            if (b != null && (b.getMinX() != 0 || b.getMinY() != 0)) {
                return Math.round(b.getMinY() / 28.0) * 28.0;
            }
            return n.getLayoutY();
        }).thenComparingDouble(n -> {
            Bounds b = n.localToScene(n.getBoundsInLocal());
            if (b != null && (b.getMinX() != 0 || b.getMinY() != 0)) {
                return b.getMinX();
            }
            return n.getLayoutX();
        }));

        install(candidates);
    }

    private static void silenceAuxiliaryButtons(Parent parent) {
        if (parent == null) return;
        for (Node child : parent.getChildrenUnmodifiable()) {
            if (child instanceof Button btn) {
                String id = btn.getId() != null ? btn.getId().toLowerCase() : "";
                String text = btn.getText() != null ? btn.getText().toLowerCase() : "";
                boolean isPresetOrAux = id.contains("range") || id.contains("preset") || id.contains("export")
                        || id.contains("print") || id.contains("refresh") || id.contains("reset")
                        || id.contains("attachment") || id.contains("managecharges") || id.contains("help")
                        || id.contains("barcode") || id.contains("addcustomer") || id.contains("addsupplier")
                        || id.contains("customrange") || id.contains("removeline") || id.contains("deleteline")
                        || id.contains("filtertoggle") || id.contains("advanced");

                if (!isPresetOrAux) {
                    for (String cls : btn.getStyleClass()) {
                        String c = cls.toLowerCase();
                        if (c.contains("range") || c.contains("pill") || c.contains("icon-button")
                                || c.contains("action-icon") || c.contains("quick-filter") || c.contains("table-tool")
                                || c.contains("filter-pill") || c.contains("secondary-icon") || c.contains("tools-button")) {
                            isPresetOrAux = true;
                            break;
                        }
                    }
                }

                if (!isPresetOrAux) {
                    if (text.contains("today") || text.contains("this month") || text.contains("q1")
                            || text.contains("custom") || text.contains("preset") || text.contains("manage charges")
                            || text.contains("refresh") || text.contains("reset") || text.contains("+ add customer")
                            || text.contains("+ add supplier") || text.contains("barcode") || text.contains("export")
                            || text.contains("close details")) {
                        isPresetOrAux = true;
                    }
                }

                if (isPresetOrAux) {
                    btn.setFocusTraversable(false);
                }
            } else if (child instanceof Parent nested) {
                silenceAuxiliaryButtons(nested);
            }
        }
    }

    private static void installTextAreaTabForwarding(TextArea ta) {
        if (ta == null || Boolean.TRUE.equals(ta.getProperties().get("erp.textarea.tab.installed"))) return;
        ta.getProperties().put("erp.textarea.tab.installed", true);
        ta.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.TAB && !event.isControlDown()) {
                if (Boolean.TRUE.equals(ta.getProperties().get(FOCUS_INSTALLED_PROP))) {
                    return; // Handled by installed focus chain
                }
                event.consume();
                if (ta.getScene() != null && ta.getScene().getRoot() instanceof Parent root) {
                    List<Node> candidates = new ArrayList<>();
                    collectInteractiveControls(root, candidates);
                    int idx = candidates.indexOf(ta);
                    if (idx >= 0 && candidates.size() > 1) {
                        focus(candidates, idx, event.isShiftDown() ? -1 : 1);
                    }
                }
            }
        });
    }

    public static void installSearchToTableNavigation(TextField searchField, Parent parent) {
        if (searchField == null || parent == null) return;
        searchField.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.DOWN) {
                TableView<?> table = findTableView(parent);
                if (table != null && !table.getItems().isEmpty()) {
                    table.requestFocus();
                    if (table.getSelectionModel().getSelectedIndex() < 0) {
                        table.getSelectionModel().selectFirst();
                    }
                    event.consume();
                }
            }
        });
    }

    private static TableView<?> findTableView(Parent parent) {
        if (parent == null) return null;
        for (Node child : parent.getChildrenUnmodifiable()) {
            if (child instanceof TableView<?> tv && tv.isVisible() && !tv.isDisabled()) {
                return tv;
            }
            if (child instanceof Parent p) {
                TableView<?> found = findTableView(p);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void collectInteractiveControls(Parent parent, List<Node> result) {
        if (parent == null) return;
        if (parent instanceof ScrollPane scroll && scroll.getContent() instanceof Parent scrollParent) {
            collectInteractiveControls(scrollParent, result);
        } else if (parent instanceof TabPane tabs) {
            for (Tab tab : tabs.getTabs()) {
                if (tab.getContent() instanceof Parent tabParent) {
                    collectInteractiveControls(tabParent, result);
                }
            }
        } else if (parent instanceof TitledPane titled && titled.getContent() instanceof Parent titledParent) {
            collectInteractiveControls(titledParent, result);
        } else if (parent instanceof Accordion accordion) {
            for (TitledPane pane : accordion.getPanes()) {
                if (pane.getContent() instanceof Parent paneParent) {
                    collectInteractiveControls(paneParent, result);
                }
            }
        } else if (parent instanceof SplitPane split) {
            for (Node item : split.getItems()) {
                if (item instanceof Parent splitParent) {
                    collectInteractiveControls(splitParent, result);
                }
            }
        }
        for (Node child : parent.getChildrenUnmodifiable()) {
            if (!child.isVisible() || !child.isManaged() || child.isDisabled()) continue;
            if (isFocusableInputField(child)) {
                result.add(child);
            } else if (child instanceof Parent nested) {
                collectInteractiveControls(nested, result);
            }
        }
    }

    private static boolean isFocusableInputField(Node node) {
        if (!node.isFocusTraversable()) return false;
        return node instanceof TextField
                || node instanceof TextArea
                || node instanceof PasswordField
                || node instanceof ComboBox<?>
                || node instanceof ChoiceBox<?>
                || node instanceof DatePicker
                || node instanceof Spinner<?>
                || node instanceof CheckBox;
    }

    public static boolean isInternalChildEditor(Node node) {
        if (node == null) return false;
        if (node.getStyleClass().contains("dse-global-search-v3-input")) {
            return true;
        }
        Parent p = node.getParent();
        int depth = 0;
        while (p != null && depth < 4) {
            if (p instanceof ComboBox<?> || p instanceof DatePicker || p instanceof Spinner<?>) {
                return true;
            }
            for (String sc : p.getStyleClass()) {
                if (sc.contains("search-shell") || sc.contains("search-strip") || sc.contains("query-shell")
                        || sc.contains("finance-filter-search") || sc.contains("report-search-shell")
                        || sc.contains("clients-search-strip")) {
                    return true;
                }
            }
            p = p.getParent();
            depth++;
        }
        return false;
    }

    public static void attachInputFocusHighlightAndSelection(Node node) {
        if (node == null) return;
        if (Boolean.TRUE.equals(node.getProperties().get("erp.focus.highlight.attached"))) return;
        node.getProperties().put("erp.focus.highlight.attached", true);

        if (node instanceof TextField tf) {
            selectAllOnFocus(tf);
        }
        node.focusedProperty().addListener((obs, was, isFocused) -> {
            if (!isInternalChildEditor(node)) {
                updateFocusStyleClass(node, isFocused);
            }
            notifyShellParent(node, isFocused);
        });
        if (node instanceof ComboBox<?> cb) {
            wireEditorFocus(cb, cb.getEditor());
            cb.editableProperty().addListener((obs, was, isEditable) -> {
                if (Boolean.TRUE.equals(isEditable)) {
                    wireEditorFocus(cb, cb.getEditor());
                }
            });
            cb.editorProperty().addListener((obs, oldEd, newEd) -> {
                if (newEd != null) wireEditorFocus(cb, newEd);
            });
        } else if (node instanceof DatePicker dp) {
            wireEditorFocus(dp, dp.getEditor());
            dp.editorProperty().addListener((obs, oldEd, newEd) -> {
                if (newEd != null) wireEditorFocus(dp, newEd);
            });
        } else if (node instanceof Spinner<?> sp) {
            wireEditorFocus(sp, sp.getEditor());
            sp.editorProperty().addListener((obs, oldEd, newEd) -> {
                if (newEd != null) wireEditorFocus(sp, newEd);
            });
        }
    }

    public static void notifyShellParent(Node node, boolean isFocused) {
        if (node == null) return;
        Parent p = node.getParent();
        int depth = 0;
        while (p != null && depth < 4) {
            if (p instanceof DatePicker || p instanceof ComboBox<?> || p instanceof Spinner<?>) {
                updateFocusStyleClass(p, isFocused);
            }
            for (String sc : p.getStyleClass()) {
                if (sc.contains("search-shell") || sc.contains("search-strip") || sc.contains("query-shell")
                        || sc.contains("finance-filter-search") || sc.contains("report-search-shell")
                        || sc.contains("clients-search-strip")) {
                    updateFocusStyleClass(p, isFocused);
                    break;
                }
            }
            p = p.getParent();
            depth++;
        }
    }

    private static void wireEditorFocus(Control parentControl, TextField editor) {
        if (editor == null) return;
        selectAllOnFocus(editor);
        editor.focusedProperty().addListener((obs, was, isFocused) -> {
            updateFocusStyleClass(parentControl, isFocused);
            notifyShellParent(parentControl, isFocused);
        });
    }

    public static void updateFocusStyleClass(Node node, boolean isFocused) {
        if (node == null) return;
        if (Boolean.TRUE.equals(isFocused)) {
            if (!node.getStyleClass().contains(ACTIVE_FOCUS_CLASS)) {
                node.getStyleClass().add(ACTIVE_FOCUS_CLASS);
            }
        } else {
            node.getStyleClass().remove(ACTIVE_FOCUS_CLASS);
        }
    }

    private static boolean advanceOnEnter(Node node) {
        if (Boolean.FALSE.equals(node.getProperties().get("erp.focus.advance.on.enter"))) return false;
        if (node instanceof TextArea) return false;
        if (node instanceof Button || node instanceof MenuButton) return false;
        return node instanceof TextField || node instanceof ComboBox<?> || node instanceof DatePicker || node instanceof Spinner<?>;
    }

    private static void focusDirectly(Node node) {
        if (node instanceof ComboBox<?> cb && cb.isEditable() && cb.getEditor() != null) {
            cb.getEditor().requestFocus();
            cb.getEditor().selectAll();
        } else if (node instanceof DatePicker dp && dp.isEditable() && dp.getEditor() != null) {
            dp.getEditor().requestFocus();
            dp.getEditor().selectAll();
        } else if (node instanceof Spinner<?> sp && sp.isEditable() && sp.getEditor() != null) {
            sp.getEditor().requestFocus();
            sp.getEditor().selectAll();
        } else if (node instanceof TextField tf) {
            tf.requestFocus();
            tf.selectAll();
        } else {
            node.requestFocus();
        }
    }

    private static void focus(List<Node> nodes, int current, int direction) {
        int size = nodes.size();
        for (int step = 1; step <= size; step++) {
            int next = Math.floorMod(current + direction * step, size);
            Node candidate = nodes.get(next);
            if (candidate.isVisible() && candidate.isManaged() && !candidate.isDisabled() && candidate.isFocusTraversable()) {
                focusDirectly(candidate);
                return;
            }
        }
    }
}
