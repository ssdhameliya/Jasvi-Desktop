package org.example.util;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.scene.control.ComboBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SearchableComboBoxHelperTest {

    private static boolean javaFxAvailable = false;

    @BeforeAll
    static void initJavaFx() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("linux") && (System.getenv("DISPLAY") == null || System.getenv("DISPLAY").isBlank())) {
            javaFxAvailable = false;
            return;
        }
        try {
            CountDownLatch latch = new CountDownLatch(1);
            Platform.startup(latch::countDown);
            if (latch.await(3, TimeUnit.SECONDS)) {
                javaFxAvailable = true;
            }
        } catch (IllegalStateException alreadyStarted) {
            javaFxAvailable = true;
        } catch (Throwable ignored) {
            javaFxAvailable = false;
        }
    }

    @Test
    void nonDestructiveFilteringPreservesTypedKeystrokes() throws Exception {
        if (!javaFxAvailable) return;

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Platform.runLater(() -> {
            try {
                ComboBox<String> combo = new ComboBox<>();
                combo.getItems().setAll("All", "PAID", "PARTIAL", "PENDING", "OVERDUE");
                SearchableComboBoxHelper.makeSearchable(combo);

                assertTrue(combo.isEditable());
                assertNotNull(combo.getEditor());

                // Simulate user typing "P"
                combo.getEditor().setText("P");
                combo.getEditor().positionCaret(1);

                // Invoke filter items directly via reflection to test non-destructive typing
                var filterMethod = SearchableComboBoxHelper.class.getDeclaredMethod("filterItems",
                        ComboBox.class, String.class, java.util.function.Function.class, javafx.collections.ObservableList.class);
                filterMethod.setAccessible(true);

                var masterList = combo.getProperties().get("erp.searchable.master.items");
                filterMethod.invoke(null, combo, "P", null, masterList);

                // 1. User typed string must remain "P" (NOT hijacked to "PAID" or "PENDING")
                assertEquals("P", combo.getEditor().getText(), "Typing must not be destroyed by first match auto-select");
                assertEquals(1, combo.getEditor().getCaretPosition());

                // 2. Dropdown items must contain all matching candidates in prefix/contains order
                assertTrue(combo.getItems().contains("PAID"));
                assertTrue(combo.getItems().contains("PARTIAL"));
                assertTrue(combo.getItems().contains("PENDING"));
                assertFalse(combo.getItems().contains("All"));

                // 3. Commit with "PENDING"
                var commitMethod = SearchableComboBoxHelper.class.getDeclaredMethod("commitSelection",
                        ComboBox.class, java.util.function.Function.class, javafx.collections.ObservableList.class);
                commitMethod.setAccessible(true);

                combo.getEditor().setText("pen");
                commitMethod.invoke(null, combo, null, masterList);
                assertEquals("PENDING", combo.getValue(), "Submitting 'pen' must commit PENDING cleanly");

            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });

        assertTrue(done.await(5, TimeUnit.SECONDS));
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    @Test
    void setItemsKeepsMasterListSynchronized() throws Exception {
        if (!javaFxAvailable) return;

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Platform.runLater(() -> {
            try {
                ComboBox<String> combo = new ComboBox<>();
                SearchableComboBoxHelper.makeSearchable(combo);

                // Controller asynchronously replaces list via setItems
                combo.setItems(FXCollections.observableArrayList("Alpha", "Beta", "Gamma"));

                var filterMethod = SearchableComboBoxHelper.class.getDeclaredMethod("filterItems",
                        ComboBox.class, String.class, java.util.function.Function.class, javafx.collections.ObservableList.class);
                filterMethod.setAccessible(true);

                var masterList = combo.getProperties().get("erp.searchable.master.items");
                filterMethod.invoke(null, combo, "b", null, masterList);

                assertEquals(1, combo.getItems().size());
                assertEquals("Beta", combo.getItems().getFirst());
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });

        assertTrue(done.await(5, TimeUnit.SECONDS));
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }
}
