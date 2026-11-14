package org.example.util;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Dialog;
import javafx.stage.Modality;
import javafx.stage.StageStyle;
import javafx.stage.Window;

/** Dialog owned by the ERP window before it is shown. */
public class OwnedDialog<R> extends Dialog<R> {
    public OwnedDialog() { this((Node) null); }

    public OwnedDialog(Node ownerNode) {
        this((Object) ownerNode);
    }

    public OwnedDialog(Window ownerWindow) {
        this((Object) ownerWindow);
    }

    public OwnedDialog(Object ownerTarget) {
        Window owner = DialogOwnerResolver.resolve(ownerTarget);
        initStyle(StageStyle.TRANSPARENT);
        if (owner != null) {
            initOwner(owner);
            initModality(Modality.WINDOW_MODAL);
        } else {
            initModality(Modality.APPLICATION_MODAL);
        }
        AppDialogRenderer.install(this);
    }
}
