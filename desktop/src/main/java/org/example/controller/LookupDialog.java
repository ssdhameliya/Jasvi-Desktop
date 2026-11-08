package org.example.controller;

import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.stage.Window;
import org.example.model.Lookup;
import org.example.util.*;

import java.net.URL;
import java.util.Optional;

/**
 * Enterprise Master Dropdown & Reusable Value Wizard Dialog for DSE ERP Desktop.
 * Modernized to the Universal Import & Document Inspection gold standard:
 * first-class {@link OwnedDialog}, window-modal ownership, drag physics,
 * 2-tier modular cards, and generous non-clipping viewport bounds.
 */
public class LookupDialog extends OwnedDialog<Lookup> {

    private final LookupDialogController controller;

    public LookupDialog(Window owner, String type, Lookup lookupToEdit) {
        this(owner, type, lookupToEdit, false);
    }

    public LookupDialog(Window owner, String type, Lookup lookupToEdit, boolean viewOnly) {
        super(owner != null && owner.getScene() != null ? owner.getScene().getRoot() : null);
        if (owner != null) {
            initOwner(owner);
        }

        String title = viewOnly
                ? "Master Value Details - View " + (type != null ? type : (lookupToEdit != null && lookupToEdit.getLookupType() != null ? lookupToEdit.getLookupType() : "Master")) + " (" + (lookupToEdit != null && lookupToEdit.getLookupCode() != null ? lookupToEdit.getLookupCode() : "") + ")"
                : (lookupToEdit == null
                ? "Master Value Wizard - Add " + (type != null ? type : "Master")
                : "Master Value Wizard - Edit " + (type != null ? type : "Master") + " (" + (lookupToEdit.getLookupCode() != null ? lookupToEdit.getLookupCode() : "") + ")");
        setTitle(title);
        setResizable(true);

        DialogPane pane = getDialogPane();
        pane.getStyleClass().addAll("approved-dialog", "erp-screen-root", "approved-ui", "lookup-dialog");
        if (detectLightMode(owner) && !pane.getStyleClass().contains("light-theme")) {
            pane.getStyleClass().add("light-theme");
        }

        // Dynamic adaptive sizing: responsive bounds based on monitor usable area (Rule 12)
        javafx.geometry.Rectangle2D screen = WindowUtilsFx.visualBoundsFor(owner);
        double maxW = Math.max(760, screen.getWidth() - 48);
        double maxH = Math.max(500, screen.getHeight() - 48);
        double targetW = Math.min(maxW, Math.max(880, Math.min(1080, screen.getWidth() * 0.80)));
        double targetH = Math.min(maxH, Math.max(viewOnly ? 480 : 560, Math.min(viewOnly ? 640 : 720, screen.getHeight() * 0.80)));

        pane.setPrefSize(targetW, targetH);
        pane.setMaxWidth(maxW);
        pane.setMaxHeight(maxH);
        pane.setMinWidth(Math.min(800, targetW));
        pane.setMinHeight(Math.min(460, targetH));

        pane.getButtonTypes().add(ButtonType.CLOSE);
        setOnShowing(ev -> {
            Node defaultButtonBar = pane.lookup(".button-bar");
            if (defaultButtonBar != null) {
                defaultButtonBar.setVisible(false);
                defaultButtonBar.setManaged(false);
            }
        });
        setOnShown(ev -> {
            if (pane.getScene() != null && pane.getScene().getWindow() instanceof javafx.stage.Stage stage) {
                WindowUtilsFx.fitDialogToOwnerScreen(stage, owner);
            }
        });

        pane.sceneProperty().addListener((obs, oldScene, scene) -> {
            if (scene != null) {
                scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
            }
        });

        try {
            URL url = ResourceLocator.require("/fxml/pages/lookupDialog.fxml");
            FXMLLoader loader = new FXMLLoader(url);
            Parent root = loader.load();
            ProfessionalUiEnhancer.enhance(root);
            this.controller = loader.getController();
            this.controller.setHostDialog(this);
            this.controller.setViewMode(viewOnly);
            if (lookupToEdit != null) {
                this.controller.setLookup(lookupToEdit);
            } else if (type != null) {
                this.controller.setLookupType(type);
            }
            pane.setContent(root);
            setResultConverter(btnType -> controller.getSavedResult());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load lookupDialog.fxml: " + e.getMessage(), e);
        }
    }

    public LookupDialog(Node ownerNode, String type, Lookup lookupToEdit) {
        this(DialogOwnerResolver.resolve(ownerNode), type, lookupToEdit, false);
    }

    public LookupDialog(Node ownerNode, String type, Lookup lookupToEdit, boolean viewOnly) {
        this(DialogOwnerResolver.resolve(ownerNode), type, lookupToEdit, viewOnly);
    }

    public static Optional<Lookup> show(Node ownerNode, String type, Lookup lookupToEdit) {
        LookupDialog dialog = new LookupDialog(ownerNode, type, lookupToEdit, false);
        return dialog.showAndWait();
    }

    public static Optional<Lookup> show(Window ownerWindow, String type, Lookup lookupToEdit) {
        LookupDialog dialog = new LookupDialog(ownerWindow, type, lookupToEdit, false);
        return dialog.showAndWait();
    }

    public static Optional<Lookup> showView(Node ownerNode, Lookup lookup) {
        LookupDialog dialog = new LookupDialog(ownerNode, lookup != null ? lookup.getLookupType() : null, lookup, true);
        return dialog.showAndWait();
    }

    public static Optional<Lookup> showView(Window ownerWindow, Lookup lookup) {
        LookupDialog dialog = new LookupDialog(ownerWindow, lookup != null ? lookup.getLookupType() : null, lookup, true);
        return dialog.showAndWait();
    }

    public LookupDialogController getController() {
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
