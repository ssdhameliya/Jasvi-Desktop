package org.example.controller;

import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.stage.Window;
import org.example.model.Party;
import org.example.util.*;

import java.net.URL;
import java.util.List;
import java.util.Optional;

/**
 * Enterprise Party (Customer & Supplier) Specification Wizard Dialog for DSE ERP Desktop.
 * Modernized to the Universal Import & Document Inspection gold standard:
 * first-class {@link OwnedDialog}, window-modal ownership, drag physics,
 * 3-tier modular cards, and generous non-clipping viewport bounds.
 */
public class PartyDialog extends OwnedDialog<Party> {

    private final PartyDialogController controller;

    public PartyDialog(Window owner, String type, Party partyToEdit) {
        this(owner, type, partyToEdit, false);
    }

    public PartyDialog(Window owner, String type, Party partyToEdit, boolean viewOnly) {
        super(owner != null && owner.getScene() != null ? owner.getScene().getRoot() : null);
        if (owner != null) {
            initOwner(owner);
        }

        boolean customer = "CUSTOMER".equalsIgnoreCase(type);
        String entity = customer ? "Customer" : "Supplier";
        String title = partyToEdit == null
                ? entity + " Specification & Master Wizard - Add " + entity
                : (viewOnly ? entity + " Specification & Master Details - View " + entity + " (" + (partyToEdit.getPartyCode() != null ? partyToEdit.getPartyCode() : "") + ")"
                            : entity + " Specification & Master Wizard - Edit " + entity + " (" + (partyToEdit.getPartyCode() != null ? partyToEdit.getPartyCode() : "") + ")");
        setTitle(title);
        setResizable(true);

        DialogPane pane = getDialogPane();
        pane.getStyleClass().addAll("approved-dialog", "erp-screen-root", "approved-ui", "party-dialog");
        if (detectLightMode(owner) && !pane.getStyleClass().contains("light-theme")) {
            pane.getStyleClass().add("light-theme");
        }

        // Dynamic adaptive sizing: natural content bounds with zero margin waste (Rule 12)
        pane.setPrefWidth(1140);
        pane.setMinWidth(960);
        pane.setPrefHeight(800);
        pane.setMinHeight(640);

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
            URL url = ResourceLocator.require("/fxml/pages/PartyDialog.fxml");
            FXMLLoader loader = new FXMLLoader(url);
            Parent root = loader.load();
            ProfessionalUiEnhancer.enhance(root);
            this.controller = loader.getController();
            this.controller.setHostDialog(this);
            this.controller.configure(type, partyToEdit, viewOnly);
            pane.setContent(root);
            setResultConverter(btnType -> controller.getSavedResult());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load PartyDialog.fxml: " + e.getMessage(), e);
        }
    }

    public PartyDialog(Node ownerNode, String type, Party partyToEdit) {
        this(DialogOwnerResolver.resolve(ownerNode), type, partyToEdit, false);
    }

    public PartyDialog(Node ownerNode, String type, Party partyToEdit, boolean viewOnly) {
        this(DialogOwnerResolver.resolve(ownerNode), type, partyToEdit, viewOnly);
    }

    public static Optional<Party> show(Node ownerNode, String type, Party partyToEdit) {
        PartyDialog dialog = new PartyDialog(ownerNode, type, partyToEdit, false);
        return dialog.showAndWait();
    }

    public static Optional<Party> show(Window ownerWindow, String type, Party partyToEdit) {
        PartyDialog dialog = new PartyDialog(ownerWindow, type, partyToEdit, false);
        return dialog.showAndWait();
    }

    public static Optional<Party> showView(Node ownerNode, String type, Party partyToView) {
        PartyDialog dialog = new PartyDialog(ownerNode, type, partyToView, true);
        return dialog.showAndWait();
    }

    public static Optional<Party> showView(Window ownerWindow, String type, Party partyToView) {
        PartyDialog dialog = new PartyDialog(ownerWindow, type, partyToView, true);
        return dialog.showAndWait();
    }

    public PartyDialogController getController() {
        return controller;
    }

    private static boolean detectLightMode(Window owner) {
        if (owner != null && owner.getScene() != null && owner.getScene().getRoot() != null) {
            List<String> classes = owner.getScene().getRoot().getStyleClass();
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
