package org.example.util;

import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import org.example.controller.NotificationCenterController;

/**
 * Centered, content-adaptive modal inspection popup for the Jasvi ERP Notification Center.
 * <p>
 * Replaces full-page tab navigation with a rich, responsive inspection dialog
 * based on {@link DocumentInspectionScaffold}. Sizes dynamically to usable screen bounds
 * with generous margins, draggable header, zero truncation, and deep-link record actions.
 * </p>
 */
public final class NotificationCenterDialog {

    private NotificationCenterDialog() {}

    /**
     * Shows the Notification Center modal inspection dialog.
     *
     * @param ownerNode calling UI node for owner stage anchoring
     * @param onClosed  optional callback invoked when the dialog is closed or dismissed
     */
    public static void show(Node ownerNode, Runnable onClosed) {
        var shell = DocumentInspectionScaffold.createShell(ownerNode,
                "Notification Center",
                "notification-inspection-dialog");

        try {
            FXMLLoader loader = new FXMLLoader(NotificationCenterDialog.class.getResource("/fxml/pages/NotificationCenter.fxml"));
            Parent content = loader.load();
            NotificationCenterController controller = loader.getController();

            shell.getHost().getChildren().setAll(content);

            shell.getDialog().setOnHidden(event -> {
                if (onClosed != null) {
                    try {
                        onClosed.run();
                    } catch (Exception ignored) {
                    }
                }
            });

            shell.getDialog().showAndWait();
        } catch (Exception e) {
            java.lang.System.getLogger(NotificationCenterDialog.class.getName())
                    .log(java.lang.System.Logger.Level.ERROR, "Failed to launch Notification Center Dialog", e);
        }
    }
}
