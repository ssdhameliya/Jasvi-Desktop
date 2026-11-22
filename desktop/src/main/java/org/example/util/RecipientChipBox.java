package org.example.util;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Enterprise Gmail-style Recipient Chip Box for Jasvi ERP Desktop.
 * Renders multiple email addresses or WhatsApp phone numbers as individual interactive chips
 * with one-click '(X)' removal, real-time typing/pasting tag completion, and 'Clear All' capability.
 */
public class RecipientChipBox extends VBox {

    public enum ChannelType {
        EMAIL,
        WHATSAPP
    }

    private final ChannelType channelType;
    private final ObservableList<String> recipients = FXCollections.observableArrayList();
    private final FlowPane chipsFlow = new FlowPane(6, 6);
    private final TextField inputField = new TextField();
    private final HBox inputRow = new HBox(8);
    private final Button btnClearAll = new Button("Clear All");
    private final TextField boundHiddenField;
    private boolean isInternalSync = false;
    private Consumer<List<String>> onChangeListener;

    public RecipientChipBox(ChannelType channelType, TextField boundHiddenField) {
        this.channelType = channelType != null ? channelType : ChannelType.EMAIL;
        this.boundHiddenField = boundHiddenField;

        setSpacing(8);
        getStyleClass().add("recipient-chip-box-container");

        boolean isEmail = this.channelType == ChannelType.EMAIL;

        // 1. Chips FlowPane (only shown when recipients exist)
        chipsFlow.setAlignment(Pos.CENTER_LEFT);
        chipsFlow.getStyleClass().add("recipient-chips-flow");
        chipsFlow.setManaged(false);
        chipsFlow.setVisible(false);

        // 2. Input Field
        inputField.setPromptText(isEmail
                ? "Type email address (press Enter or comma to add)..."
                : "Type mobile number (press Enter or comma to add)...");
        inputField.getStyleClass().addAll("approved-input", "recipient-chip-input");
        HBox.setHgrow(inputField, Priority.ALWAYS);

        // 3. Clear All Button
        btnClearAll.setGraphic(IconFactory.compactIcon("cancel", 11));
        btnClearAll.getStyleClass().addAll("approved-button", "approved-secondary-button", "recipient-clear-all-btn");
        btnClearAll.getProperties().put("erp-icon-preserve", true);
        btnClearAll.setTooltip(IconFactory.createTooltip("Remove all recipients"));
        btnClearAll.setManaged(false);
        btnClearAll.setVisible(false);
        btnClearAll.setOnAction(e -> clear());

        inputRow.setAlignment(Pos.CENTER_LEFT);
        inputRow.getChildren().addAll(inputField, btnClearAll);

        getChildren().addAll(chipsFlow, inputRow);

        // Keystroke listeners
        inputField.setOnKeyPressed(ev -> {
            if (ev.getCode() == KeyCode.ENTER || ev.getCode() == KeyCode.COMMA || ev.getCode() == KeyCode.SEMICOLON) {
                commitInput();
                ev.consume();
            } else if (ev.getCode() == KeyCode.BACK_SPACE) {
                if (inputField.getText().isEmpty() && !recipients.isEmpty()) {
                    removeRecipient(recipients.getLast());
                    ev.consume();
                }
            }
        });

        // Fast auto-splitting for pasting
        inputField.textProperty().addListener((obs, old, val) -> {
            if (val != null && (val.contains(",") || val.contains(";") || val.contains("\n") || val.contains("\r"))) {
                commitInput();
            }
        });

        // Auto-commit on focus lost
        inputField.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) {
                commitInput();
            }
        });

        // Sync with legacy bound TextField
        if (boundHiddenField != null) {
            boundHiddenField.setVisible(false);
            boundHiddenField.setManaged(false);

            String existing = boundHiddenField.getText();
            if (existing != null && !existing.isBlank()) {
                setRecipients(existing);
            }

            boundHiddenField.textProperty().addListener((obs, old, val) -> {
                if (isInternalSync) return;
                if (val != null && !val.equals(getRecipientString())) {
                    setRecipients(val);
                }
            });
        }
    }

    public static RecipientChipBox attach(VBox parentContainer, TextField legacyField, ChannelType channelType) {
        if (parentContainer == null || legacyField == null) return null;
        int idx = parentContainer.getChildren().indexOf(legacyField);
        RecipientChipBox chipBox = new RecipientChipBox(channelType, legacyField);
        if (idx >= 0) {
            parentContainer.getChildren().add(idx, chipBox);
        } else {
            parentContainer.getChildren().add(chipBox);
        }
        return chipBox;
    }

    public void commitInput() {
        String text = inputField.getText();
        if (text == null || text.isBlank()) return;
        addRecipients(text);
        inputField.clear();
    }

    public void addRecipients(String raw) {
        if (raw == null || raw.isBlank()) return;
        List<String> tokens = Arrays.stream(raw.split("[;,\\n\\r]+"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();

        boolean changed = false;
        for (String token : tokens) {
            if (!containsIgnoreCase(token)) {
                recipients.add(token);
                changed = true;
            }
        }
        if (changed) {
            rebuildChips();
        }
    }

    public void removeRecipient(String value) {
        if (value == null) return;
        boolean removed = recipients.removeIf(item -> item.equalsIgnoreCase(value.trim()));
        if (removed) {
            rebuildChips();
        }
    }

    public void setRecipients(String raw) {
        recipients.clear();
        if (raw != null && !raw.isBlank()) {
            addRecipients(raw);
        } else {
            rebuildChips();
        }
    }

    public void clear() {
        recipients.clear();
        inputField.clear();
        rebuildChips();
    }

    public List<String> getRecipients() {
        commitInput();
        return new ArrayList<>(recipients);
    }

    public String getRecipientString() {
        List<String> current = getRecipients();
        return String.join(channelType == ChannelType.EMAIL ? "; " : ", ", current);
    }

    public void setOnChangeListener(Consumer<List<String>> listener) {
        this.onChangeListener = listener;
    }

    public TextField getInputField() {
        return inputField;
    }

    public Button getClearAllButton() {
        return btnClearAll;
    }

    private boolean containsIgnoreCase(String token) {
        return recipients.stream().anyMatch(r -> r.equalsIgnoreCase(token.trim()));
    }

    private void rebuildChips() {
        chipsFlow.getChildren().clear();
        boolean hasItems = !recipients.isEmpty();

        chipsFlow.setVisible(hasItems);
        chipsFlow.setManaged(hasItems);
        btnClearAll.setVisible(hasItems);
        btnClearAll.setManaged(hasItems);

        boolean isEmail = channelType == ChannelType.EMAIL;

        for (String item : recipients) {
            HBox chip = new HBox(6);
            chip.setAlignment(Pos.CENTER_LEFT);
            chip.getStyleClass().addAll("recipient-input-chip", isEmail ? "recipient-chip-email" : "recipient-chip-whatsapp");

            Node icon = IconFactory.compactIcon(isEmail ? "email" : "whatsapp", 12);
            Label lblText = new Label(item);
            lblText.getStyleClass().add("recipient-chip-text");

            Button btnRemove = new Button();
            btnRemove.setGraphic(IconFactory.compactIcon("cancel", 10));
            btnRemove.getStyleClass().addAll("recipient-chip-remove-btn", "icon-button");
            btnRemove.getProperties().put("erp-icon-preserve", true);
            btnRemove.setTooltip(IconFactory.createTooltip("Remove " + item));
            btnRemove.setOnAction(e -> removeRecipient(item));

            chip.getChildren().addAll(icon, lblText, btnRemove);
            chipsFlow.getChildren().add(chip);
        }

        // Sync to legacy hidden field if present
        if (boundHiddenField != null) {
            isInternalSync = true;
            try {
                String combined = getRecipientString();
                if (!combined.equals(boundHiddenField.getText())) {
                    boundHiddenField.setText(combined);
                }
            } finally {
                isInternalSync = false;
            }
        }

        if (onChangeListener != null) {
            onChangeListener.accept(new ArrayList<>(recipients));
        }
    }
}
