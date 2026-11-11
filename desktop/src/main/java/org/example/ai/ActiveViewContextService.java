package org.example.ai;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.example.config.ConfigManager;
import org.example.model.AppUser;
import org.example.service.PermissionService;
import org.example.service.SessionService;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Real-time Screen & Form Telemetry Inspector for the Jasvi ERP AI Copilot.
 *
 * <p>Reads the active JavaFX scene graph in read-only mode to capture the current
 * screen title, operator role, disabled action buttons, visible validation errors,
 * empty mandatory inputs, and selected table row context while masking sensitive
 * PII (PAN, bank account numbers, passwords) before AI prompt generation.</p>
 */
public final class ActiveViewContextService {

    private static final ActiveViewContextService INSTANCE = new ActiveViewContextService();
    private static final Pattern PAN_PATTERN = Pattern.compile("\\b[A-Z]{5}[0-9]{4}[A-Z]\\b");
    private static final Pattern ACCOUNT_DIGITS_PATTERN = Pattern.compile("\\b\\d{9,18}\\b");

    private volatile String activeScreenTitle = "Login / Startup";
    private volatile String activeFxmlPath = "/fxml/pages/Login.fxml";
    private volatile Node activeContentRoot;

    public record ScreenContext(
            String screenName,
            String fxmlPath,
            String role,
            String username,
            String companyName,
            String gstinMasked,
            List<String> disabledControls,
            List<String> validationErrors,
            List<String> emptyRequiredFields,
            String activeSelectionSummary
    ) {
        public String toPromptContext() {
            return String.format("""
                    - Active Screen: %s (%s)
                    - Authenticated Role: %s (User: %s)
                    - Company: %s (GSTIN: %s)
                    - Disabled Buttons on Screen: %s
                    - Active Validation Errors: %s
                    - Empty / Attention Fields: %s
                    - Active Table Selection: %s
                    """,
                    screenName,
                    fxmlPath,
                    role,
                    username,
                    companyName,
                    gstinMasked,
                    disabledControls.isEmpty() ? "None" : String.join(", ", disabledControls),
                    validationErrors.isEmpty() ? "None" : String.join(" | ", validationErrors),
                    emptyRequiredFields.isEmpty() ? "None" : String.join(", ", emptyRequiredFields),
                    activeSelectionSummary == null || activeSelectionSummary.isBlank() ? "None" : activeSelectionSummary
            );
        }
    }

    private ActiveViewContextService() {}

    public static ActiveViewContextService getInstance() {
        return INSTANCE;
    }

    public void updateActiveView(String title, String fxmlPath, Node contentRoot) {
        if (title != null && !title.isBlank()) {
            this.activeScreenTitle = title.trim();
        }
        if (fxmlPath != null && !fxmlPath.isBlank()) {
            this.activeFxmlPath = fxmlPath.trim();
        }
        if (contentRoot != null) {
            this.activeContentRoot = contentRoot;
        }
    }

    public ScreenContext capture(Node ownerAnchor) {
        Node rootToInspect = resolveInspectionRoot(ownerAnchor);
        String screenTitle = resolveScreenTitle(rootToInspect);
        String role = resolveUserRole();
        String username = resolveUsername();
        String company = ConfigManager.get("company.name", "Unconfigured Company").trim();
        String gstin = maskSensitive(ConfigManager.get("company.gstin", "Not Configured").trim());

        Set<String> disabledButtons = new LinkedHashSet<>();
        Set<String> validationErrors = new LinkedHashSet<>();
        Set<String> emptyFields = new LinkedHashSet<>();
        List<String> selectionSummaries = new ArrayList<>();

        if (rootToInspect != null) {
            inspectNodeTree(rootToInspect, disabledButtons, validationErrors, emptyFields, selectionSummaries);
        }

        String selection = selectionSummaries.isEmpty()
                ? "No table row currently selected"
                : maskSensitive(String.join(" • ", selectionSummaries));

        return new ScreenContext(
                screenTitle,
                activeFxmlPath,
                role,
                username,
                company.isBlank() ? "Jasvi Enterprise" : company,
                gstin.isBlank() ? "Not Configured" : gstin,
                new ArrayList<>(disabledButtons),
                new ArrayList<>(validationErrors),
                new ArrayList<>(emptyFields),
                selection
        );
    }

    private Node resolveInspectionRoot(Node ownerAnchor) {
        if (activeContentRoot != null && activeContentRoot.getScene() != null) {
            return activeContentRoot;
        }
        if (ownerAnchor != null && ownerAnchor.getScene() != null) {
            Scene scene = ownerAnchor.getScene();
            Node content = scene.lookup("#contentPane");
            return content != null ? content : scene.getRoot();
        }
        for (Window window : Window.getWindows()) {
            if (window.isShowing() && window instanceof Stage stage && stage.getScene() != null) {
                Node content = stage.getScene().lookup("#contentPane");
                return content != null ? content : stage.getScene().getRoot();
            }
        }
        return activeContentRoot;
    }

    private String resolveScreenTitle(Node root) {
        if (root != null && root.getScene() != null) {
            Node titleNode = root.getScene().lookup("#lblPageTitle");
            if (titleNode instanceof Label lbl && lbl.getText() != null && !lbl.getText().isBlank()) {
                return lbl.getText().trim();
            }
        }
        return activeScreenTitle;
    }

    private String resolveUserRole() {
        try {
            if (SessionService.isSuperAdmin()) {
                return "SUPER_ADMIN";
            }
            AppUser current = SessionService.current();
            if (current != null && current.getRole() != null && !current.getRole().isBlank()) {
                return current.getRole().trim();
            }
        } catch (Exception ignored) {}
        return "GUEST / PRE-LOGIN";
    }

    private String resolveUsername() {
        try {
            AppUser current = SessionService.current();
            if (current != null && current.getUsername() != null && !current.getUsername().isBlank()) {
                return current.getUsername().trim();
            }
        } catch (Exception ignored) {}
        return "Prospective / Unauthenticated Operator";
    }

    private void inspectNodeTree(
            Node node,
            Set<String> disabledButtons,
            Set<String> validationErrors,
            Set<String> emptyFields,
            List<String> selectionSummaries
    ) {
        if (node == null || !node.isVisible()) {
            return;
        }

        if (node instanceof ButtonBase btn) {
            String label = btn.getText() != null ? btn.getText().trim() : "";
            if (label.isBlank() && btn.getTooltip() != null) {
                label = btn.getTooltip().getText();
            }
            if (btn.isDisabled() && label != null && !label.isBlank() && disabledButtons.size() < 10) {
                disabledButtons.add(label);
            }
        } else if (node instanceof Label lbl) {
            String text = lbl.getText() != null ? lbl.getText().trim() : "";
            if (!text.isBlank()) {
                boolean isError = lbl.getStyleClass().stream().anyMatch(s ->
                        s.contains("error") || s.contains("negative") || s.contains("danger") || s.contains("warning"));
                if (isError && validationErrors.size() < 8) {
                    validationErrors.add(maskSensitive(text));
                }
            }
        } else if (node instanceof TextInputControl input && !(node instanceof PasswordField)) {
            if (input.isEditable() && !input.isDisabled()) {
                String val = input.getText() != null ? input.getText().trim() : "";
                String prompt = input.getPromptText() != null ? input.getPromptText().trim() : "";
                String id = input.getId() != null ? input.getId() : "";
                if (val.isEmpty() && (!prompt.isEmpty() || !id.isEmpty()) && emptyFields.size() < 10) {
                    String descriptor = !prompt.isEmpty() ? prompt : id;
                    if (!descriptor.toLowerCase().contains("search")) {
                        emptyFields.add(descriptor);
                    }
                }
            }
        } else if (node instanceof ComboBoxBase<?> combo) {
            if (!combo.isDisabled() && combo.getValue() == null && emptyFields.size() < 10) {
                String prompt = combo.getPromptText() != null ? combo.getPromptText().trim() : "";
                String id = combo.getId() != null ? combo.getId() : "Dropdown";
                emptyFields.add(!prompt.isEmpty() ? prompt : id);
            }
        } else if (node instanceof TableView<?> table) {
            int totalItems = table.getItems() != null ? table.getItems().size() : 0;
            Object selected = table.getSelectionModel() != null ? table.getSelectionModel().getSelectedItem() : null;
            if (selected != null && selectionSummaries.size() < 3) {
                selectionSummaries.add("Table (" + totalItems + " rows), Selected: " + summarizeObject(selected));
            } else if (totalItems > 0 && selectionSummaries.isEmpty()) {
                selectionSummaries.add("Table with " + totalItems + " loaded rows (none selected)");
            }
        }

        if (node instanceof ScrollPane scroll && scroll.getContent() != null) {
            inspectNodeTree(scroll.getContent(), disabledButtons, validationErrors, emptyFields, selectionSummaries);
        } else if (node instanceof SplitPane split) {
            for (Node item : split.getItems()) {
                inspectNodeTree(item, disabledButtons, validationErrors, emptyFields, selectionSummaries);
            }
        } else if (node instanceof TabPane tabs) {
            Tab selectedTab = tabs.getSelectionModel().getSelectedItem();
            if (selectedTab != null && selectedTab.getContent() != null) {
                inspectNodeTree(selectedTab.getContent(), disabledButtons, validationErrors, emptyFields, selectionSummaries);
            }
        } else if (node instanceof TitledPane titled && titled.getContent() != null) {
            inspectNodeTree(titled.getContent(), disabledButtons, validationErrors, emptyFields, selectionSummaries);
        } else if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                inspectNodeTree(child, disabledButtons, validationErrors, emptyFields, selectionSummaries);
            }
        }
    }

    private String summarizeObject(Object obj) {
        if (obj == null) return "None";
        String raw = obj.toString();
        if (raw.length() > 120) {
            raw = raw.substring(0, 117) + "...";
        }
        return maskSensitive(raw);
    }

    public static String maskSensitive(String input) {
        if (input == null || input.isBlank()) return "";
        String maskedPan = PAN_PATTERN.matcher(input).replaceAll("XXXXX0000X");
        return ACCOUNT_DIGITS_PATTERN.matcher(maskedPan).replaceAll(match -> {
            String digits = match.group();
            if (digits.length() <= 4) return digits;
            return "XXXX" + digits.substring(digits.length() - 4);
        });
    }
}
