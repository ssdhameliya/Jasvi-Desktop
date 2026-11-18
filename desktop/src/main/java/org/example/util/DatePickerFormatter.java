package org.example.util;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Accordion;
import javafx.scene.control.DatePicker;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.util.StringConverter;

import java.time.LocalDate;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Ensures JavaFX DatePicker controls format and parse dates strictly according
 * to the configured ERP company.dateFormat setting (BusinessClock) rather than
 * relying on the host OS locale, with zero regression on underlying LocalDate values.
 *
 * Automatically tracks active DatePickers, supports instant 8-digit compact date
 * auto-conversion (e.g. 18112026 / 11182026 / 20261118 -> configured format),
 * 6-digit and alternate separator commit parsing, and refreshes editor text and
 * prompt text in real time whenever the user modifies the date format setting.
 */
public final class DatePickerFormatter {
    private static final String ATTACHED_KEY = "dse.datepicker.formatted";
    private static final String EDITOR_ATTACHED_KEY = "dse.datepicker.editor.wired";
    private static final String UPDATING_KEY = "dse.datepicker.updating";
    private static final Set<DatePicker> ACTIVE_PICKERS = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private DatePickerFormatter() { }

    public static DatePicker attach(DatePicker picker) {
        if (picker == null) return null;
        ACTIVE_PICKERS.add(picker);
        if (Boolean.TRUE.equals(picker.getProperties().get(ATTACHED_KEY))) {
            wireEditor(picker, picker.getEditor());
            refresh(picker);
            return picker;
        }
        picker.getProperties().put(ATTACHED_KEY, Boolean.TRUE);

        picker.setConverter(new StringConverter<>() {
            @Override
            public String toString(LocalDate date) {
                return date == null ? "" : BusinessClock.formatDate(date);
            }

            @Override
            public LocalDate fromString(String string) {
                if (string == null || string.isBlank()) return null;
                try {
                    LocalDate parsed = BusinessClock.parseDate(string.trim());
                    return parsed != null ? parsed : picker.getValue();
                } catch (Exception ignored) {
                    return picker.getValue();
                }
            }
        });

        refresh(picker);
        wireEditor(picker, picker.getEditor());
        picker.editorProperty().addListener((obs, oldEd, newEd) -> wireEditor(picker, newEd));

        picker.showingProperty().addListener((obs, wasShowing, isShowing) -> {
            if (isShowing) {
                commitEditorText(picker);
                refresh(picker);
            }
        });

        return picker;
    }

    public static void attachAll(DatePicker... pickers) {
        if (pickers == null) return;
        for (DatePicker picker : pickers) {
            attach(picker);
        }
    }

    /** Recursively attaches DatePickerFormatter to every DatePicker inside a screen, tab, or popup dialog subtree. */
    public static void attachIn(Node root) {
        attachIn(root, new IdentityHashMap<>());
    }

    private static void attachIn(Node node, IdentityHashMap<Node, Boolean> visited) {
        if (node == null || visited.put(node, Boolean.TRUE) != null) return;
        if (node instanceof DatePicker dp) {
            attach(dp);
        }
        if (node instanceof DialogPane dialogPane) {
            attachIn(dialogPane.getHeader(), visited);
            attachIn(dialogPane.getContent(), visited);
        }
        if (node instanceof ScrollPane scroll) {
            attachIn(scroll.getContent(), visited);
        } else if (node instanceof TabPane tabs) {
            for (Tab tab : tabs.getTabs()) attachIn(tab.getContent(), visited);
        } else if (node instanceof TitledPane titled) {
            attachIn(titled.getContent(), visited);
        } else if (node instanceof Accordion accordion) {
            for (TitledPane pane : accordion.getPanes()) attachIn(pane, visited);
        } else if (node instanceof SplitPane split) {
            for (Node item : split.getItems()) attachIn(item, visited);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                attachIn(child, visited);
            }
        }
    }

    private static void wireEditor(DatePicker picker, TextField editor) {
        if (picker == null || editor == null) return;
        if (Boolean.TRUE.equals(editor.getProperties().get(EDITOR_ATTACHED_KEY))) return;
        editor.getProperties().put(EDITOR_ATTACHED_KEY, Boolean.TRUE);

        // Instant auto-format when the user types an exact 8-digit compact date (e.g. 18112026, 11182026, 20261118)
        editor.textProperty().addListener((obs, oldText, newText) -> {
            if (Boolean.TRUE.equals(picker.getProperties().get(UPDATING_KEY))) return;
            if (newText == null) return;
            String trimmed = newText.trim();
            if (trimmed.length() != 8 || !isAllAsciiDigits(trimmed)) return;
            LocalDate parsed = BusinessClock.parseCompactEightDigitDate(trimmed);
            if (parsed == null) return;

            Runnable apply = () -> {
                TextField currentEditor = picker.getEditor();
                if (currentEditor == null) return;
                String live = currentEditor.getText();
                if (live == null || !trimmed.equals(live.trim())) return;
                applyParsedValue(picker, parsed);
            };
            try {
                Platform.runLater(apply);
            } catch (IllegalStateException noToolkit) {
                apply.run();
            }
        });

        // Commit synchronously before Enter / Tab triggers WorkflowFocusManager focus traversal
        editor.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.TAB) {
                commitEditorText(picker);
            }
        });

        editor.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) {
                commitEditorText(picker);
            } else {
                refresh(picker);
            }
        });
        editor.setOnAction(event -> commitEditorText(picker));
    }

    public static void refresh(DatePicker picker) {
        if (picker == null) return;
        updatePromptText(picker);
        LocalDate value = picker.getValue();
        picker.getProperties().put(UPDATING_KEY, Boolean.TRUE);
        try {
            if (picker.getEditor() != null) {
                String expected = value == null ? "" : BusinessClock.formatDate(value);
                if (!expected.equals(picker.getEditor().getText())) {
                    picker.getEditor().setText(expected);
                }
            }
            StringConverter<LocalDate> conv = picker.getConverter();
            if (conv != null) {
                picker.setConverter(null);
                picker.setConverter(conv);
            }
        } finally {
            picker.getProperties().remove(UPDATING_KEY);
        }
    }

    public static void refreshAll() {
        if (ACTIVE_PICKERS.isEmpty()) {
            return;
        }
        Runnable task = () -> {
            synchronized (ACTIVE_PICKERS) {
                for (DatePicker picker : ACTIVE_PICKERS) {
                    refresh(picker);
                }
            }
        };
        try {
            if (Platform.isFxApplicationThread()) {
                task.run();
            } else {
                Platform.runLater(task);
            }
        } catch (IllegalStateException noToolkit) {
            task.run();
        } catch (Throwable ignored) {
            java.lang.System.getLogger("org.example").log(java.lang.System.Logger.Level.DEBUG, "Suppressed exception: " + ignored.getMessage(), ignored);
        }
    }

    public static void updatePromptText(DatePicker picker) {
        if (picker == null) return;
        try {
            picker.setPromptText(BusinessClock.datePattern().toLowerCase(Locale.ROOT));
        } catch (Exception ignored) { java.lang.System.getLogger("org.example").log(java.lang.System.Logger.Level.DEBUG, "Suppressed exception: " + ignored.getMessage(), ignored); }
    }

    public static void commitEditorText(DatePicker picker) {
        if (picker == null || picker.getEditor() == null) return;
        if (Boolean.TRUE.equals(picker.getProperties().get(UPDATING_KEY))) return;
        String text = picker.getEditor().getText();
        if (text == null || text.trim().isEmpty()) {
            picker.getProperties().put(UPDATING_KEY, Boolean.TRUE);
            try {
                picker.setValue(null);
                picker.getEditor().setText("");
            } finally {
                picker.getProperties().remove(UPDATING_KEY);
            }
            return;
        }
        try {
            LocalDate parsed = BusinessClock.parseDate(text.trim());
            if (parsed != null) {
                applyParsedValue(picker, parsed);
            } else {
                restoreCurrentValueText(picker);
            }
        } catch (Exception ignored) {
            restoreCurrentValueText(picker);
        }
    }

    private static void applyParsedValue(DatePicker picker, LocalDate parsed) {
        if (picker == null || parsed == null) return;
        picker.getProperties().put(UPDATING_KEY, Boolean.TRUE);
        try {
            picker.setValue(parsed);
            TextField editor = picker.getEditor();
            if (editor != null) {
                String formatted = BusinessClock.formatDate(parsed);
                editor.setText(formatted);
                editor.positionCaret(formatted.length());
            }
        } finally {
            picker.getProperties().remove(UPDATING_KEY);
        }
    }

    private static void restoreCurrentValueText(DatePicker picker) {
        if (picker == null || picker.getEditor() == null) return;
        picker.getProperties().put(UPDATING_KEY, Boolean.TRUE);
        try {
            LocalDate current = picker.getValue();
            picker.getEditor().setText(current == null ? "" : BusinessClock.formatDate(current));
        } finally {
            picker.getProperties().remove(UPDATING_KEY);
        }
    }

    private static boolean isAllAsciiDigits(String value) {
        if (value == null || value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }
}
