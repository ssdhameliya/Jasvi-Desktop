package org.example.util;

import javafx.scene.Node;
import javafx.stage.Stage;
import javafx.stage.Window;

/** Resolves the primary ERP window for owned dialogs on every platform. */
public final class DialogOwnerResolver {
    private DialogOwnerResolver() {}

    public static Window resolve(Node node) {
        if (node != null && node.getScene() != null && node.getScene().getWindow() != null
                && node.getScene().getWindow().isShowing()) {
            return node.getScene().getWindow();
        }
        Window focused = Window.getWindows().stream()
                .filter(Window::isShowing)
                .filter(Window::isFocused)
                .filter(DialogOwnerResolver::isPrimaryCandidate)
                .findFirst().orElse(null);
        if (focused != null) return focused;
        Window primary = Window.getWindows().stream()
                .filter(Window::isShowing)
                .filter(DialogOwnerResolver::isPrimaryCandidate)
                .findFirst().orElse(null);
        if (primary != null) return primary;
        return Window.getWindows().stream()
                .filter(Window::isShowing)
                .filter(w -> w instanceof Stage)
                .findFirst().orElse(null);
    }

    public static Window resolve() { return resolve((Node) null); }

    public static Window resolve(Object target) {
        if (target instanceof Window window && window.isShowing()) return window;
        if (target instanceof Node node) return resolve(node);
        return resolve((Node) null);
    }

    private static boolean isPrimaryCandidate(Window window) {
        if (!(window instanceof Stage stage)) return false;
        if (stage.getOwner() != null) return false;
        String title = stage.getTitle() == null ? "" : stage.getTitle().toLowerCase();
        return !title.contains("toast") && !title.contains("notification");
    }
}
