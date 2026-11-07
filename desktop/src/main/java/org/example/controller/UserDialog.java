package org.example.controller;

import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.stage.Window;
import org.example.api.admin.AdminApiClient;
import org.example.util.*;

import java.net.URL;
import java.util.Optional;

/**
 * Enterprise User Account & Security Privilege Wizard Dialog for DSE ERP Desktop.
 * Modernized to the Universal Import & Document Inspection gold standard:
 * first-class {@link OwnedDialog}, window-modal ownership, drag physics,
 * 3-tier modular cards, and generous non-clipping viewport bounds.
 */
public class UserDialog extends OwnedDialog<AdminApiClient.UserDto> {

    private final UserDialogController controller;

    public UserDialog(Window owner, Integer userIdToEdit) {
        this(owner, userIdToEdit, false);
    }

    public UserDialog(Window owner, Integer userIdToEdit, boolean viewOnly) {
        super(owner != null && owner.getScene() != null ? owner.getScene().getRoot() : null);
        if (owner != null) {
            initOwner(owner);
        }

        String title = viewOnly
                ? "User Profile & Security Privilege - View User (#" + (userIdToEdit != null ? userIdToEdit : "") + ")"
                : (userIdToEdit == null
                ? "User Account & Security Privilege Wizard - Add User"
                : "User Account & Security Privilege Wizard - Edit User (#" + userIdToEdit + ")");
        setTitle(title);
        setResizable(true);

        DialogPane pane = getDialogPane();
        pane.getStyleClass().addAll("approved-dialog", "erp-screen-root", "approved-ui", "user-dialog");
        if (detectLightMode(owner) && !pane.getStyleClass().contains("light-theme")) {
            pane.getStyleClass().add("light-theme");
        }

        // Dynamic adaptive sizing: natural content bounds with zero margin waste (Rule 12)
        pane.setPrefWidth(1140);
        pane.setMinWidth(960);
        pane.setPrefHeight(viewOnly ? 720 : 800);
        pane.setMinHeight(viewOnly ? 560 : 640);

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
            URL url = ResourceLocator.require("/fxml/pages/UserDialog.fxml");
            FXMLLoader loader = new FXMLLoader(url);
            Parent root = loader.load();
            ProfessionalUiEnhancer.enhance(root);
            this.controller = loader.getController();
            this.controller.setHostDialog(this);
            this.controller.setViewMode(viewOnly);
            if (userIdToEdit != null) {
                this.controller.editUser(userIdToEdit);
            }
            pane.setContent(root);
            setResultConverter(btnType -> controller.getSavedResult());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load UserDialog.fxml: " + e.getMessage(), e);
        }
    }

    public UserDialog(Node ownerNode, Integer userIdToEdit) {
        this(DialogOwnerResolver.resolve(ownerNode), userIdToEdit, false);
    }

    public UserDialog(Node ownerNode, Integer userIdToEdit, boolean viewOnly) {
        this(DialogOwnerResolver.resolve(ownerNode), userIdToEdit, viewOnly);
    }

    public static Optional<AdminApiClient.UserDto> show(Node ownerNode, Integer userIdToEdit) {
        UserDialog dialog = new UserDialog(ownerNode, userIdToEdit, false);
        return dialog.showAndWait();
    }

    public static Optional<AdminApiClient.UserDto> show(Window ownerWindow, Integer userIdToEdit) {
        UserDialog dialog = new UserDialog(ownerWindow, userIdToEdit, false);
        return dialog.showAndWait();
    }

    public static Optional<AdminApiClient.UserDto> showView(Node ownerNode, Integer userIdToView) {
        UserDialog dialog = new UserDialog(ownerNode, userIdToView, true);
        return dialog.showAndWait();
    }

    public static Optional<AdminApiClient.UserDto> showView(Window ownerWindow, Integer userIdToView) {
        UserDialog dialog = new UserDialog(ownerWindow, userIdToView, true);
        return dialog.showAndWait();
    }

    public UserDialogController getController() {
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
