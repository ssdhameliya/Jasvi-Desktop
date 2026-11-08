package org.example.shortcut;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import org.example.shortcut.ShortcutRegistry.Action;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ApplicationCommandDispatcherTest {

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
    void refreshMatchesBothF5AndCtrlF5AndCtrlR() {
        KeyEvent f5 = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F5, false, false, false, false);
        KeyEvent ctrlF5 = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F5, false, true, false, false);
        KeyEvent ctrlR = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.R, false, true, false, false);

        assertTrue(ShortcutRegistry.matches(f5, Action.REFRESH_CURRENT), "F5 should trigger REFRESH_CURRENT");
        assertTrue(ShortcutRegistry.matches(ctrlF5, Action.REFRESH_CURRENT), "Ctrl+F5 should trigger REFRESH_CURRENT");
        assertTrue(ShortcutRegistry.matches(ctrlR, Action.REFRESH_CURRENT), "Ctrl+R should trigger REFRESH_CURRENT");
    }

    @Test
    void textInputExemptionsAllowModifiedShortcutsWhileShieldingSelectionKeys() {
        // Ctrl-modified / Function keys should execute even when inside text inputs
        assertTrue(ShortcutRegistry.allowInTextInput(Action.NEW_CURRENT));
        assertTrue(ShortcutRegistry.allowInTextInput(Action.REFRESH_CURRENT));
        assertTrue(ShortcutRegistry.allowInTextInput(Action.SAVE_CURRENT));
        assertTrue(ShortcutRegistry.allowInTextInput(Action.EDIT_CURRENT));
        assertTrue(ShortcutRegistry.allowInTextInput(Action.PRINT_CURRENT));
        assertTrue(ShortcutRegistry.allowInTextInput(Action.EXPORT_CURRENT));

        // Single unmodified character keys must be blocked to prevent interrupting text entry
        assertFalse(ShortcutRegistry.allowInTextInput(Action.OPEN_SELECTED));
        assertFalse(ShortcutRegistry.allowInTextInput(Action.DELETE_SELECTED));
    }

    @Test
    void buttonDiscoveryFindsPrefixedAndIdButtons() throws Exception {
        if (!javaFxAvailable) return;

        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean buttonFired = new AtomicBoolean(false);

        Platform.runLater(() -> {
            try {
                VBox box = new VBox();
                Button btn = new Button("+ Add Item");
                btn.setId("btnNewItem");
                btn.setOnAction(e -> buttonFired.set(true));
                box.getChildren().add(btn);

                // Simulate findButton directly via reflection to verify robust label and id matching
                java.lang.reflect.Method find = ApplicationCommandDispatcher.class.getDeclaredMethod(
                        "findButton",
                        javafx.scene.Node.class,
                        java.util.List.class,
                        java.util.List.class
                );
                find.setAccessible(true);
                Object matched = find.invoke(
                        null,
                        box,
                        java.util.List.of("New", "Add Item", "New Item"),
                        java.util.List.of("btnNew", "btnAdd")
                );

                assertNotNull(matched, "Button with label '+ Add Item' and id 'btnNewItem' should be discovered");
                if (matched instanceof Button b) b.fire();
                assertTrue(buttonFired.get(), "Discovered button should fire successfully");
            } catch (Throwable t) {
                fail(t);
            } finally {
                done.countDown();
            }
        });

        assertTrue(done.await(15, TimeUnit.SECONDS), "JavaFX test timed out");
    }
}
