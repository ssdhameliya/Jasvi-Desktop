package org.example.util;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;

/**
 * Universal Screen Guidance Manager.
 * Installs interactive [ ℹ Screen Guide ] buttons on screen headers
 * and triggers rich operational modal guidance with zero controller boilerplate.
 */
public final class ScreenGuidanceManager {

    private ScreenGuidanceManager() {}

    /**
     * Creates a standardized, theme-aware Screen Guide button.
     */
    public static Button createGuideButton(Object ownerTarget, String screenKey) {
        return createGuideButton(ownerTarget, screenKey, "Screen Guide");
    }

    /**
     * Overloaded helper allowing (screenKey, ownerTarget) parameter order.
     */
    public static Button createGuideButton(String screenKey, Object ownerTarget) {
        return createGuideButton(ownerTarget, screenKey, "Screen Guide");
    }

    /**
     * Creates a standardized Guide button with custom label text.
     */
    public static Button createGuideButton(Object ownerTarget, String screenKey, String label) {
        Button btn = new Button();
        btn.setContentDisplay(javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
        btn.setGraphic(IconFactory.compactIcon("info", 16));
        String tip = label != null && !label.isBlank() ? label : "Screen Guide";
        btn.setTooltip(new javafx.scene.control.Tooltip(tip));
        btn.setAccessibleText(tip);
        btn.getStyleClass().addAll("approved-button", "approved-secondary-button", "icon-button", "erp-screen-guide-button");
        btn.setOnAction(e -> showGuidance(ownerTarget != null ? ownerTarget : btn, screenKey));
        return btn;
    }

    /**
     * Opens the rich operational guidance dialog for the specified screen key.
     */
    public static void showGuidance(Object ownerTarget, String screenKey) {
        ScreenGuidance guidance = ScreenGuidanceRegistry.get(screenKey);
        if (guidance != null) {
            ScreenGuidanceDialog.show(ownerTarget, guidance);
        } else {
            Node node = ownerTarget instanceof Node n ? n : null;
            AppDialogService.info(node, "Operational Guide", "Guide for " + screenKey + " is coming soon.");
        }
    }

    /**
     * Installs a Screen Guide button into an HBox header container at the end.
     */
    public static Button install(HBox headerContainer, String screenKey) {
        if (headerContainer == null) return null;
        Button btn = createGuideButton(headerContainer, screenKey);
        headerContainer.getChildren().add(btn);
        return btn;
    }
}
