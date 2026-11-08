package org.example.controller;

import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.stage.Window;
import org.example.model.Item;
import org.example.util.*;

import java.net.URL;
import java.util.List;
import java.util.Optional;

/**
 * Enterprise Item Specification & Catalog Wizard Dialog for DSE ERP Desktop.
 * Modernized to the Universal Import & Document Inspection gold standard:
 * first-class {@link OwnedDialog}, window-modal ownership, drag physics,
 * 3-tier modular cards, real-time margin intelligence, and generous non-clipping viewport bounds.
 */
public class ItemDialog extends OwnedDialog<Item> {

    private final ItemDialogController controller;

    public ItemDialog(Window owner, Item itemToEdit) {
        this(owner, itemToEdit, false);
    }

    public ItemDialog(Window owner, Item itemToEdit, boolean viewOnly) {
        super(owner != null && owner.getScene() != null ? owner.getScene().getRoot() : null);
        if (owner != null) {
            initOwner(owner);
        }

        String title = itemToEdit == null
                ? "Item Specification & Catalog Wizard - Add Item"
                : (viewOnly ? "Item Specification & Catalog Wizard - View Item (" + (itemToEdit.getItemCode() != null ? itemToEdit.getItemCode() : "") + ")"
                            : "Item Specification & Catalog Wizard - Edit Item (" + (itemToEdit.getItemCode() != null ? itemToEdit.getItemCode() : "") + ")");
        setTitle(title);
        setResizable(true);

        DialogPane pane = getDialogPane();
        pane.getStyleClass().addAll("approved-dialog", "erp-screen-root", "approved-ui", "item-dialog");
        if (detectLightMode(owner) && !pane.getStyleClass().contains("light-theme")) {
            pane.getStyleClass().add("light-theme");
        }

        // Dynamic adaptive sizing: responsive bounds based on monitor usable area (Rule 12)
        javafx.geometry.Rectangle2D screen = WindowUtilsFx.visualBoundsFor(owner);
        double maxW = Math.max(760, screen.getWidth() - 48);
        double maxH = Math.max(520, screen.getHeight() - 48);
        double targetW = Math.min(maxW, Math.max(920, Math.min(1140, screen.getWidth() * 0.82)));
        double targetH = Math.min(maxH, Math.max(580, Math.min(800, screen.getHeight() * 0.82)));

        pane.setPrefSize(targetW, targetH);
        pane.setMaxWidth(maxW);
        pane.setMaxHeight(maxH);
        pane.setMinWidth(Math.min(840, targetW));
        pane.setMinHeight(Math.min(500, targetH));

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
            URL url = ResourceLocator.require("/fxml/pages/Itemdialog.fxml");
            FXMLLoader loader = new FXMLLoader(url);
            Parent root = loader.load();
            ProfessionalUiEnhancer.enhance(root);
            this.controller = loader.getController();
            this.controller.setHostDialog(this);
            this.controller.setViewMode(viewOnly);
            if (itemToEdit != null) {
                this.controller.setItem(itemToEdit);
            }
            pane.setContent(root);
            setResultConverter(btnType -> controller.getSavedResult());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load Itemdialog.fxml: " + e.getMessage(), e);
        }
    }

    public ItemDialog(Node ownerNode, Item itemToEdit) {
        this(DialogOwnerResolver.resolve(ownerNode), itemToEdit, false);
    }

    public ItemDialog(Node ownerNode, Item itemToEdit, boolean viewOnly) {
        this(DialogOwnerResolver.resolve(ownerNode), itemToEdit, viewOnly);
    }

    public static Optional<Item> show(Node ownerNode, Item itemToEdit) {
        ItemDialog dialog = new ItemDialog(ownerNode, itemToEdit);
        return dialog.showAndWait();
    }

    public static Optional<Item> show(Window ownerWindow, Item itemToEdit) {
        ItemDialog dialog = new ItemDialog(ownerWindow, itemToEdit);
        return dialog.showAndWait();
    }

    public static Optional<Item> showView(Node ownerNode, Item itemToView) {
        ItemDialog dialog = new ItemDialog(ownerNode, itemToView, true);
        return dialog.showAndWait();
    }

    public static Optional<Item> showView(Window ownerWindow, Item itemToView) {
        ItemDialog dialog = new ItemDialog(ownerWindow, itemToView, true);
        return dialog.showAndWait();
    }

    public ItemDialogController getController() {
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
