package org.example.util;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.util.Duration;
import javafx.util.StringConverter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Universal Searchable Dropdown Helper for the Jasvi ERP Ecosystem.
 * Converts any standard JavaFX ComboBox<T> into a high-speed, type-to-filter searchable dropdown
 * with instant substring matching, keyboard commit (Enter/Tab), and smooth focus traversal.
 */
public final class SearchableComboBoxHelper {
    public static final String SEARCHABLE_INSTALLED_PROP = "erp.searchable.installed";
    private static final String MASTER_ITEMS_PROP = "erp.searchable.master.items";
    private static final String INTERNAL_UPDATE_PROP = "erp.searchable.internal.update";
    private static final String EXTRACTOR_PROP = "erp.searchable.extractor";

    private SearchableComboBoxHelper() {}

    /**
     * Converts any ComboBox into a searchable dropdown using toString() representation.
     */
    public static <T> void makeSearchable(ComboBox<T> comboBox) {
        makeSearchable(comboBox, null);
    }

    /**
     * Converts any ComboBox into a searchable dropdown with a custom text extractor.
     */
    public static <T> void makeSearchable(ComboBox<T> comboBox, Function<T, String> textExtractor) {
        if (comboBox == null) return;
        if (Boolean.TRUE.equals(comboBox.getProperties().get(SEARCHABLE_INSTALLED_PROP))) return;
        comboBox.getProperties().put(SEARCHABLE_INSTALLED_PROP, true);

        if (textExtractor != null) {
            comboBox.getProperties().put(EXTRACTOR_PROP, textExtractor);
        }

        // Initialize backing master list
        final ObservableList<T> masterList = FXCollections.observableArrayList();
        if (comboBox.getItems() != null) {
            masterList.addAll(comboBox.getItems());
        }
        comboBox.getProperties().put(MASTER_ITEMS_PROP, masterList);

        // Keep master list synchronized when items are updated externally or replaced via setItems()
        final ListChangeListener<T> itemsChangeListener = change -> {
            if (isInternalUpdate(comboBox)) return;
            masterList.setAll(comboBox.getItems() == null ? java.util.List.of() : comboBox.getItems());
        };
        if (comboBox.getItems() != null) {
            comboBox.getItems().addListener(itemsChangeListener);
        }

        comboBox.itemsProperty().addListener((obs, oldItems, newItems) -> {
            if (isInternalUpdate(comboBox)) return;
            if (oldItems != null) {
                oldItems.removeListener(itemsChangeListener);
            }
            if (newItems != null) {
                masterList.setAll(newItems);
                newItems.addListener(itemsChangeListener);
            } else {
                masterList.clear();
            }
        });

        comboBox.setEditable(true);

        // Ensure string converter matches the display extractor
        StringConverter<T> existingConverter = comboBox.getConverter();
        if (existingConverter == null) {
            comboBox.setConverter(createDefaultConverter(comboBox, textExtractor));
        }

        // Keep editor text synchronized when value is changed via popup selection or programmatically
        comboBox.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (isInternalUpdate(comboBox)) return;
            TextField ed = comboBox.getEditor();
            if (ed != null) {
                setInternalUpdate(comboBox, true);
                try {
                    String display = getDisplayString(comboBox, newValue, textExtractor);
                    if (!Objects.equals(ed.getText(), display)) {
                        ed.setText(display);
                        if (display != null) {
                            ed.positionCaret(display.length());
                        }
                    }
                } finally {
                    setInternalUpdate(comboBox, false);
                }
            }
        });

        PauseTransition debounce = new PauseTransition(Duration.millis(80));

        TextField editor = comboBox.getEditor();
        if (editor != null) {
            // Keep prompt text synchronized between ComboBox and its editable TextField
            if (comboBox.getPromptText() != null && !comboBox.getPromptText().isBlank()) {
                editor.setPromptText(comboBox.getPromptText());
            }
            comboBox.promptTextProperty().addListener((obs, oldPrompt, newPrompt) -> {
                if (editor != null && newPrompt != null) {
                    editor.setPromptText(newPrompt);
                }
            });

            editor.textProperty().addListener((obs, oldText, newText) -> {
                if (isInternalUpdate(comboBox)) return;
                if (!editor.isFocused()) return;

                T committed = comboBox.getValue();
                String committedDisplay = getDisplayString(comboBox, committed, textExtractor);
                if (newText != null && newText.trim().equalsIgnoreCase(committedDisplay.trim())) {
                    debounce.stop();
                    return;
                }

                // If user is navigating or arrowing through existing list, prevent destructive wiping
                if (newText != null && !newText.isBlank()) {
                    String trimmed = newText.trim();
                    for (T item : masterList) {
                        if (item != null && getDisplayString(comboBox, item, textExtractor).equalsIgnoreCase(trimmed)) {
                            debounce.stop();
                            return;
                        }
                    }
                }

                debounce.stop();
                debounce.setOnFinished(event -> filterItems(comboBox, newText, textExtractor, masterList));
                debounce.playFromStart();
            });

            // Keyboard navigation filter on editor
            editor.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
                if (event.getCode() == KeyCode.ENTER) {
                    commitSelection(comboBox, textExtractor, masterList);
                    if (comboBox.isShowing()) {
                        comboBox.hide();
                    }
                } else if (event.getCode() == KeyCode.TAB) {
                    commitSelection(comboBox, textExtractor, masterList);
                    if (comboBox.isShowing()) {
                        comboBox.hide();
                    }
                } else if (event.getCode() == KeyCode.ESCAPE) {
                    restoreSelection(comboBox, textExtractor, masterList);
                    if (comboBox.isShowing()) {
                        comboBox.hide();
                    }
                    event.consume();
                } else if (event.getCode() == KeyCode.DOWN) {
                    if (!comboBox.isShowing() && !comboBox.getItems().isEmpty()) {
                        comboBox.show();
                        event.consume();
                    }
                }
            });

            // Focus lifecycle: select all text when gained so typing 'S'/'P' immediately searches, commit on loss
            editor.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
                if (Boolean.TRUE.equals(isFocused)) {
                    Platform.runLater(editor::selectAll);
                } else if (!comboBox.isFocused()) {
                    commitSelection(comboBox, textExtractor, masterList);
                    resetMasterItems(comboBox, masterList);
                }
            });
        }

        comboBox.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (Boolean.TRUE.equals(isFocused) && comboBox.isEditable() && comboBox.getEditor() != null) {
                Platform.runLater(() -> {
                    comboBox.getEditor().requestFocus();
                    comboBox.getEditor().selectAll();
                });
            } else if (!isFocused && (editor == null || !editor.isFocused())) {
                commitSelection(comboBox, textExtractor, masterList);
                resetMasterItems(comboBox, masterList);
            }
        });

        // When dropdown opens, ensure all master items are available if editor hasn't filtered
        comboBox.setOnShowing(event -> {
            if (editor == null || editor.getText() == null || editor.getText().isBlank()) {
                resetMasterItems(comboBox, masterList);
            }
        });

        comboBox.showingProperty().addListener((obs, wasShowing, isShowing) -> {
            if (!isShowing) {
                resetMasterItems(comboBox, masterList);
            }
        });
    }

    private static <T> void filterItems(ComboBox<T> comboBox, String query, Function<T, String> extractor, ObservableList<T> masterList) {
        String trimmed = query == null ? "" : query.trim().toLowerCase();
        List<T> matches = new ArrayList<>();

        if (trimmed.isEmpty()) {
            matches.addAll(masterList);
        } else {
            // First add prefix matches, then substring matches for intuitive search
            List<T> prefixMatches = new ArrayList<>();
            List<T> containsMatches = new ArrayList<>();

            for (T item : masterList) {
                if (item == null) continue;
                String display = getDisplayString(comboBox, item, extractor).toLowerCase();
                if (display.startsWith(trimmed)) {
                    prefixMatches.add(item);
                } else if (display.contains(trimmed)) {
                    containsMatches.add(item);
                }
            }
            matches.addAll(prefixMatches);
            matches.addAll(containsMatches);
        }

        TextField editor = comboBox.getEditor();
        String typed = editor != null ? editor.getText() : null;

        setInternalUpdate(comboBox, true);
        try {
            comboBox.getItems().setAll(matches);
            // Non-destructive typing: Never overwrite editor text while user is typing!
            // Preserve user's typed keystrokes and caret position
            if (editor != null && typed != null && !typed.isBlank()) {
                editor.setText(typed);
                editor.positionCaret(typed.length());
            }
        } finally {
            setInternalUpdate(comboBox, false);
        }

        boolean focused = comboBox.isFocused() || (comboBox.getEditor() != null && comboBox.getEditor().isFocused());
        if (focused && !matches.isEmpty() && !comboBox.isShowing()) {
            Platform.runLater(comboBox::show);
        } else if (matches.isEmpty() && comboBox.isShowing()) {
            Platform.runLater(comboBox::hide);
        }
    }

    private static <T> void commitSelection(ComboBox<T> comboBox, Function<T, String> extractor, ObservableList<T> masterList) {
        TextField editor = comboBox.getEditor();
        String text = editor != null ? editor.getText() : null;

        T selected = null;

        // If user typed something specific, check if it matches an item exactly or closely
        if (text != null && !text.isBlank()) {
            String trimmed = text.trim();
            for (T item : masterList) {
                if (item != null && getDisplayString(comboBox, item, extractor).equalsIgnoreCase(trimmed)) {
                    selected = item;
                    break;
                }
            }
            if (selected == null) {
                for (T item : masterList) {
                    if (item != null && getDisplayString(comboBox, item, extractor).toLowerCase().startsWith(trimmed.toLowerCase())) {
                        selected = item;
                        break;
                    }
                }
            }
            if (selected == null) {
                for (T item : masterList) {
                    if (item != null && getDisplayString(comboBox, item, extractor).toLowerCase().contains(trimmed.toLowerCase())) {
                        selected = item;
                        break;
                    }
                }
            }
        }

        if (selected == null) {
            selected = comboBox.getSelectionModel().getSelectedItem();
        }
        if (selected == null && !comboBox.getItems().isEmpty() && text != null && !text.isBlank()) {
            selected = comboBox.getItems().getFirst();
        }

        setInternalUpdate(comboBox, true);
        try {
            if (selected != null) {
                comboBox.setValue(selected);
                comboBox.getSelectionModel().select(selected);
                if (editor != null) {
                    String display = getDisplayString(comboBox, selected, extractor);
                    editor.setText(display);
                    editor.positionCaret(display.length());
                }
            } else if (text == null || text.isBlank()) {
                comboBox.setValue(null);
                comboBox.getSelectionModel().clearSelection();
                if (editor != null) {
                    editor.clear();
                }
            }
        } finally {
            setInternalUpdate(comboBox, false);
        }
    }

    private static <T> void restoreSelection(ComboBox<T> comboBox, Function<T, String> extractor, ObservableList<T> masterList) {
        T current = comboBox.getValue();
        setInternalUpdate(comboBox, true);
        try {
            resetMasterItems(comboBox, masterList);
            if (current != null) {
                comboBox.setValue(current);
                if (comboBox.getEditor() != null) {
                    String display = getDisplayString(comboBox, current, extractor);
                    comboBox.getEditor().setText(display);
                }
            }
        } finally {
            setInternalUpdate(comboBox, false);
        }
    }

    private static <T> void resetMasterItems(ComboBox<T> comboBox, ObservableList<T> masterList) {
        if (!comboBox.getItems().equals(masterList)) {
            TextField editor = comboBox.getEditor();
            String currentText = editor != null ? editor.getText() : null;
            setInternalUpdate(comboBox, true);
            try {
                T current = comboBox.getValue();
                comboBox.getItems().setAll(masterList);
                if (current != null) {
                    comboBox.setValue(current);
                    comboBox.getSelectionModel().select(current);
                }
                if (editor != null && currentText != null) {
                    editor.setText(currentText);
                    editor.positionCaret(currentText.length());
                }
            } finally {
                setInternalUpdate(comboBox, false);
            }
        }
    }

    private static <T> String getDisplayString(ComboBox<T> comboBox, T item, Function<T, String> extractor) {
        if (item == null) return "";
        if (extractor != null) {
            try {
                String val = extractor.apply(item);
                if (val != null) return val;
            } catch (Exception ignored) {}
        }
        if (comboBox != null && comboBox.getConverter() != null) {
            try {
                String val = comboBox.getConverter().toString(item);
                if (val != null && !val.isBlank()) return val;
            } catch (Exception ignored) {}
        }
        if (item instanceof org.example.model.Party party) {
            return org.example.document.DocumentLookupPolicy.partyDisplay(party);
        }
        if (item instanceof org.example.model.Lookup lookup) {
            return lookup.getLookupValue() != null ? lookup.getLookupValue() : "";
        }
        return item.toString();
    }

    private static <T> StringConverter<T> createDefaultConverter(ComboBox<T> comboBox, Function<T, String> extractor) {
        return new StringConverter<>() {
            @Override
            public String toString(T object) {
                return getDisplayString(comboBox, object, extractor);
            }

            @Override
            @SuppressWarnings("unchecked")
            public T fromString(String string) {
                if (string == null || string.isBlank()) return null;
                String trimmed = string.trim();
                ObservableList<T> master = (ObservableList<T>) comboBox.getProperties().get(MASTER_ITEMS_PROP);
                if (master != null) {
                    for (T item : master) {
                        if (item != null && getDisplayString(comboBox, item, extractor).equalsIgnoreCase(trimmed)) {
                            return item;
                        }
                    }
                }
                for (T item : comboBox.getItems()) {
                    if (item != null && getDisplayString(comboBox, item, extractor).equalsIgnoreCase(trimmed)) {
                        return item;
                    }
                }
                return comboBox.getValue();
            }
        };
    }

    private static boolean isInternalUpdate(ComboBox<?> box) {
        return box != null && Boolean.TRUE.equals(box.getProperties().get(INTERNAL_UPDATE_PROP));
    }

    private static void setInternalUpdate(ComboBox<?> box, boolean value) {
        if (box == null) return;
        if (value) box.getProperties().put(INTERNAL_UPDATE_PROP, Boolean.TRUE);
        else box.getProperties().remove(INTERNAL_UPDATE_PROP);
    }

    /**
     * Recursively makes all ComboBoxes inside any screen or dialog root searchable,
     * skipping pagination/page-size selectors.
     */
    public static void makeAllSearchable(Parent root) {
        if (root == null) return;
        List<ComboBox<?>> combos = new ArrayList<>();
        findCombos(root, combos);
        for (ComboBox<?> cb : combos) {
            if (shouldMakeSearchable(cb)) {
                makeSearchableGeneric(cb);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> void makeSearchableGeneric(ComboBox<?> cb) {
        makeSearchable((ComboBox<T>) cb);
    }

    private static boolean shouldMakeSearchable(ComboBox<?> cb) {
        if (cb == null) return false;
        if (Boolean.TRUE.equals(cb.getProperties().get(SEARCHABLE_INSTALLED_PROP))) return false;
        if (cb.getProperties().containsKey(PartySearchUi.class.getName() + ".taskKey")) return false;

        String id = cb.getId() != null ? cb.getId().toLowerCase() : "";
        if (id.contains("pagesize") || id.contains("rowsperpage") || "cmbrows".equals(id)) return false;

        for (String styleClass : cb.getStyleClass()) {
            if (styleClass.contains("page-size") || styleClass.contains("pagination")) return false;
        }
        return true;
    }

    private static void findCombos(Parent parent, List<ComboBox<?>> result) {
        if (parent == null) return;
        if (parent instanceof ScrollPane scroll && scroll.getContent() instanceof Parent p) {
            findCombos(p, result);
        } else if (parent instanceof TabPane tabPane) {
            for (Tab tab : tabPane.getTabs()) {
                if (tab.getContent() instanceof Parent p) findCombos(p, result);
            }
        } else if (parent instanceof TitledPane titledPane && titledPane.getContent() instanceof Parent p) {
            findCombos(p, result);
        } else if (parent instanceof Accordion accordion) {
            for (TitledPane pane : accordion.getPanes()) {
                if (pane.getContent() instanceof Parent p) findCombos(p, result);
            }
        } else if (parent instanceof DialogPane dialogPane && dialogPane.getContent() instanceof Parent p) {
            findCombos(p, result);
        } else if (parent instanceof SplitPane split) {
            for (Node item : split.getItems()) {
                if (item instanceof Parent p) findCombos(p, result);
            }
        }

        for (Node child : parent.getChildrenUnmodifiable()) {
            if (child instanceof ComboBox<?> cb) {
                result.add(cb);
            } else if (child instanceof Parent nested) {
                findCombos(nested, result);
            }
        }
    }
}
