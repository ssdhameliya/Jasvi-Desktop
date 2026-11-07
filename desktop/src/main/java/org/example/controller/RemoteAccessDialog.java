package org.example.controller;

import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.stage.Window;
import org.example.util.*;

import java.net.URL;

/**
 * Enterprise Remote & Multi-Branch Cloudflare Tunnel Wizard Dialog for DSE ERP Desktop.
 * Modernized to the Universal Import & Document Inspection gold standard:
 * first-class {@link OwnedDialog}, window-modal ownership, drag physics,
 * 2-tier modular cards, and generous non-clipping viewport bounds.
 */
public class RemoteAccessDialog extends OwnedDialog<Void> {

    private final RemoteAccessDialogController controller;

    public RemoteAccessDialog(Window owner) {
        super(owner != null && owner.getScene() != null ? owner.getScene().getRoot() : null);
        if (owner != null) {
            initOwner(owner);
        }

        setTitle("Remote & Multi-Branch Tunnel Gateway");
        setResizable(true);

        DialogPane pane = getDialogPane();
        pane.getStyleClass().addAll("approved-dialog", "erp-screen-root", "approved-ui", "remote-access-dialog");
        if (detectLightMode(owner) && !pane.getStyleClass().contains("light-theme")) {
            pane.getStyleClass().add("light-theme");
        }

        // Dynamic adaptive sizing: natural content bounds with zero margin waste (Rule 12)
        pane.setPrefWidth(1100);
        pane.setMinWidth(960);
        pane.setPrefHeight(740);
        pane.setMinHeight(580);

        pane.getButtonTypes().add(ButtonType.CLOSE);
        setOnShowing(ev -> {
            Node defaultButtonBar = pane.lookup(".button-bar");
            if (defaultButtonBar != null) {
                defaultButtonBar.setVisible(false);
                defaultButtonBar.setManaged(false);
            }
        });

        pane.sceneProperty().addListener((obs, oldScene, scene) -> {
            if (scene != null) {
                scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
            }
        });

        try {
            URL url = ResourceLocator.require("/fxml/pages/RemoteAccessDialog.fxml");
            FXMLLoader loader = new FXMLLoader(url);
            Parent root = loader.load();
            ProfessionalUiEnhancer.enhance(root);
            this.controller = loader.getController();
            this.controller.setHostDialog(this);
            pane.setContent(root);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load RemoteAccessDialog.fxml: " + e.getMessage(), e);
        }
    }

    public RemoteAccessDialog(Node ownerNode) {
        this(DialogOwnerResolver.resolve(ownerNode));
    }

    public static void show(Node ownerNode) {
        RemoteAccessDialog dialog = new RemoteAccessDialog(ownerNode);
        dialog.showAndWait();
    }

    public static void show(Window ownerWindow) {
        RemoteAccessDialog dialog = new RemoteAccessDialog(ownerWindow);
        dialog.showAndWait();
    }

    public RemoteAccessDialogController getController() {
        return controller;
    }

    private static boolean detectLightMode(Window owner) {
        if (owner != null && owner.getScene() != null && owner.getScene().getRoot() != null) {
            var classes = owner.getScene().getRoot().getStyleClass();
            if (classes.contains("dse-theme-light") || classes.contains("light-theme") || classes.contains("theme-light")) {
                return true;
            }
            if (classes.contains("dse-theme-dark")) {
                return false;
            }
        }
        return !org.example.theme.ThemeManager.isDark();
    }
}
