package org.example.util;

import javafx.application.Platform;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecipientChipBoxTest {

    @BeforeAll
    static void initJavaFx() {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException ignored) {
            // JavaFX runtime already initialized
        }
    }

    @Test
    void testParseAndAddRecipients() {
        TextField legacyField = new TextField();
        RecipientChipBox box = new RecipientChipBox(RecipientChipBox.ChannelType.EMAIL, legacyField);

        box.setRecipients("alice@example.com; bob@example.com, charlie@example.com");

        List<String> list = box.getRecipients();
        assertEquals(3, list.size());
        assertTrue(list.contains("alice@example.com"));
        assertTrue(list.contains("bob@example.com"));
        assertTrue(list.contains("charlie@example.com"));
        assertEquals("alice@example.com; bob@example.com; charlie@example.com", legacyField.getText());
    }

    @Test
    void testOneClickRemoval() {
        TextField legacyField = new TextField();
        RecipientChipBox box = new RecipientChipBox(RecipientChipBox.ChannelType.EMAIL, legacyField);

        box.setRecipients("ssdhameliya123@gmail.com, ssdhameliya@gmail.com");
        assertEquals(2, box.getRecipients().size());

        // Simulate one-click removal of first recipient
        box.removeRecipient("ssdhameliya123@gmail.com");

        List<String> remaining = box.getRecipients();
        assertEquals(1, remaining.size());
        assertEquals("ssdhameliya@gmail.com", remaining.getFirst());
        assertEquals("ssdhameliya@gmail.com", legacyField.getText());
    }

    @Test
    void testClearAll() {
        TextField legacyField = new TextField();
        RecipientChipBox box = new RecipientChipBox(RecipientChipBox.ChannelType.WHATSAPP, legacyField);

        box.setRecipients("9099839199, 9876543210");
        assertEquals(2, box.getRecipients().size());

        box.clear();

        assertTrue(box.getRecipients().isEmpty());
        assertEquals("", legacyField.getText());
    }

    @Test
    void testAttachToParentVBox() {
        VBox parent = new VBox();
        TextField legacyField = new TextField("user1@jasvi.com, user2@jasvi.com");
        parent.getChildren().add(legacyField);

        RecipientChipBox box = RecipientChipBox.attach(parent, legacyField, RecipientChipBox.ChannelType.EMAIL);
        assertNotNull(box);
        assertFalse(legacyField.isVisible());
        assertFalse(legacyField.isManaged());

        assertEquals(2, box.getRecipients().size());
        box.removeRecipient("user1@jasvi.com");

        assertEquals(1, box.getRecipients().size());
        assertEquals("user2@jasvi.com", legacyField.getText());
    }
}
