package org.example.util;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;

import java.util.Locale;
import java.util.Optional;

/**
 * The single semantic dialog API for DSE ERP.
 *
 * <p>Business code supplies meaning; {@link AppDialogRenderer} owns every visual,
 * icon, semantic colour, action placement and responsive rule.</p>
 */
public final class AppDialogService {
    private AppDialogService() {}

    public static boolean confirm(Node owner, String title, String heading, String message) {
        String action = inferPrimaryAction(title, heading, message);
        return confirm(owner, "confirmation", title, heading, message, action, "Cancel");
    }

    public static boolean confirm(Node owner, String title, String heading, String message, String primaryAction) {
        return confirm(owner, "confirmation", title, heading, message, primaryAction, "Cancel");
    }

    public static boolean destructive(Node owner, String title, String heading, String message, String primaryAction) {
        return confirm(owner, "delete", title, heading, message, primaryAction, "Cancel");
    }

    /**
     * Canonical double-confirmation dialog for catastrophic or irreversible operations
     * (e.g. database restore, system rollback, master license revocation, factory reset).
     * Requires the user to explicitly type the target keyword to proceed.
     */
    public static boolean doubleConfirmation(Node owner, String keyword, String title, String heading, String message) {
        if (keyword == null || keyword.isBlank()) {
            return destructive(owner, title, heading, message, "Confirm");
        }
        String cleanKeyword = keyword.trim().toUpperCase(Locale.ROOT);
        javafx.scene.control.TextInputDialog typed = new OwnedTextInputDialog("");
        typed.setTitle(title != null ? title : "Final Confirmation Required");
        typed.setHeaderText(heading != null ? heading : "Irreversible Operation Warning");
        typed.setContentText((message != null ? message + "\n\n" : "") + "Type " + cleanKeyword + " to confirm:");
        Optional<String> confirmation = typed.showAndWait();
        if (confirmation.isEmpty()) return false;
        boolean matches = cleanKeyword.equalsIgnoreCase(confirmation.get().trim());
        if (!matches) {
            warning(owner, "Action Cancelled", "Confirmation text did not match",
                    "The operation was aborted because the confirmation text did not match '" + cleanKeyword + "'. No changes were applied.");
        }
        return matches;
    }

    public static boolean warningConfirm(Node owner, String title, String heading, String message, String primaryAction, String safeAction) {
        return confirm(owner, "warning", title, heading, message, primaryAction, safeAction);
    }

    public static boolean unsaved(Node owner, String screenName, String summary, String destination) {
        String target = destination == null || destination.isBlank() ? "another screen" : destination;
        String message = "This " + screenName + " has changes that have not been saved. Leaving now will discard the current work before opening " + target + ".";
        return confirm(owner, "unsaved", "Unsaved changes", "Leave " + screenName + "?", message,
                "Discard & Leave", "Stay & Continue Editing", summary);
    }

    public static void info(Object owner, String title, String heading, String message) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> info(owner, title, heading, message));
            return;
        }
        show(owner, "notification", title, heading, message, null, new ButtonType("Close", ButtonBar.ButtonData.CANCEL_CLOSE));
    }

    public static void info(Object owner, String title, String message) {
        info(owner, title, "Notification", message);
    }

    public static void warning(Object owner, String title, String heading, String message) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> warning(owner, title, heading, message));
            return;
        }
        show(owner, "warning", title, heading, message, null, new ButtonType("Review", ButtonBar.ButtonData.CANCEL_CLOSE));
    }

    public static void warning(Object owner, String title, String message) {
        warning(owner, title, "Attention Required", message);
    }

    public static void error(Object owner, String title, String heading, String message) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> error(owner, title, heading, message));
            return;
        }
        String visible = UserFacingErrorMapper.message(message);
        String detail = UserFacingErrorMapper.reference(message);
        if (message != null && !message.isBlank() && !visible.contains(message.trim())
                && !message.toLowerCase().contains("stacktrace") && !message.toLowerCase().contains("exception")) {
            detail = (detail != null && !detail.isBlank()) ? detail + " • " + message : message;
        }
        ButtonType btnDiagnose = new ButtonType("AI Diagnose", ButtonBar.ButtonData.OTHER);
        ButtonType btnClose = new ButtonType("Close", ButtonBar.ButtonData.CANCEL_CLOSE);
        Optional<ButtonType> res = show(owner, "error", title, heading, visible, detail, btnDiagnose, btnClose);
        if (res.isPresent() && res.get() == btnDiagnose) {
            Node nodeOwner = owner instanceof Node n ? n : null;
            AiDiagnosisDialog.show(nodeOwner, title, heading, visible, detail);
        }
    }

    public static void error(Object owner, String title, String message) {
        error(owner, title, "Operation Failed", message);
    }

    public static void success(Object owner, String title, String heading, String message) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> success(owner, title, heading, message));
            return;
        }
        show(owner, "complete", title, heading, message, null, new ButtonType("Done", ButtonBar.ButtonData.CANCEL_CLOSE));
    }

    public static void success(Object owner, String title, String message) {
        success(owner, title, "Completed", message);
    }

    public static void success(Node owner, String title, String message) {
        success((Object) owner, title, "Completed", message);
    }

    public static void configureWorkspace(OwnedDialog<?> dialog, String semantic) {
        AppDialogRenderer.configureWorkspace(dialog, semantic);
    }

    public static void configureCompact(OwnedDialog<?> dialog, String semantic) {
        AppDialogRenderer.configureCompact(dialog, semantic);
    }

    /**
     * Opens a large application-owned workspace inside the same centralized dialog renderer used
     * everywhere else in DSE ERP. Feature code supplies only semantic content and actions; shell,
     * icons, theme, spacing and responsive fitting remain centralized.
     */
    public static Optional<ButtonType> workspace(Node owner, String semantic, String title, String heading,
                                                 String message, Node content, double preferredWidth, double preferredHeight,
                                                 ButtonType... buttons) {
        OwnedDialog<ButtonType> dialog = new OwnedDialog<>(owner);
        AppDialogRenderer.configureWorkspace(dialog, semantic, title, heading, message, null);
        DialogPane pane = dialog.getDialogPane();
        pane.setContent(content);

        javafx.geometry.Rectangle2D screen = WindowUtilsFx.visualBoundsFor(owner);
        double maxW = Math.max(760, screen.getWidth() - 48);
        double maxH = Math.max(500, screen.getHeight() - 48);
        double targetW = Math.min(maxW, Math.max(720, Math.min(Math.max(900, preferredWidth), screen.getWidth() * 0.82)));
        double targetH = Math.min(maxH, Math.max(480, Math.min(Math.max(620, preferredHeight), screen.getHeight() * 0.82)));

        pane.setMinWidth(Math.min(720, targetW));
        pane.setMinHeight(Math.min(460, targetH));
        pane.setPrefWidth(targetW);
        pane.setPrefHeight(targetH);
        pane.setMaxWidth(maxW);
        pane.setMaxHeight(maxH);
        pane.getButtonTypes().setAll(buttons);
        return dialog.showAndWait();
    }

    private static boolean confirm(Node owner, String semantic, String title, String heading, String message,
                                   String primaryAction, String safeAction) {
        return confirm(owner, semantic, title, heading, message, primaryAction, safeAction, null);
    }

    private static boolean confirm(Node owner, String semantic, String title, String heading, String message,
                                   String primaryAction, String safeAction, String detail) {
        ButtonType safe = new ButtonType(cleanLabel(safeAction, "Cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        ButtonType primary = new ButtonType(cleanLabel(primaryAction, "Continue"), ButtonBar.ButtonData.OK_DONE);
        return show(owner, semantic, title, heading, message, detail, safe, primary)
                .filter(primary::equals)
                .isPresent();
    }

    private static Optional<ButtonType> show(Object owner, String semantic, String title, String heading, String message,
                                             String detail, ButtonType... buttons) {
        OwnedDialog<ButtonType> dialog = new OwnedDialog<>(owner);
        AppDialogRenderer.configureMessage(dialog, semantic, title, heading, message, detail);
        DialogPane pane = dialog.getDialogPane();
        pane.getButtonTypes().setAll(buttons);
        return dialog.showAndWait();
    }

    static String inferPrimaryAction(String... values) {
        String combined = String.join(" ", java.util.Arrays.stream(values)
                .filter(java.util.Objects::nonNull).toList()).toLowerCase(Locale.ROOT);
        if (combined.contains("discard") && (combined.contains("leave") || combined.contains("unsaved"))) return "Discard & Leave";
        if (combined.contains("delete")) return "Delete";
        if (combined.contains("remove")) return "Remove";
        if (combined.contains("reject")) return "Reject";
        if (combined.contains("approve")) return "Approve";
        if (combined.contains("publish")) return "Publish";
        if (combined.contains("overwrite") || combined.contains("replace")) return "Overwrite";
        if (combined.contains("restore")) return "Restore";
        if (combined.contains("reset")) return "Reset";
        if (combined.contains("clear")) return "Clear";
        if (combined.contains("sign out") || combined.contains("logout")) return "Sign Out";
        if (combined.contains("set as default") || combined.contains("system default") || combined.contains("activate default")) return "Set as Default";
        if (combined.contains("install")) return "Install";
        if (combined.contains("update")) return "Update";
        if (combined.contains("save")) return "Save";
        if (combined.contains("apply")) return "Apply";
        if (combined.contains("retry")) return "Retry";
        if (combined.contains("send")) return "Send";
        if (combined.contains("continue")) return "Continue";
        return "Continue";
    }

    private static String cleanLabel(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
