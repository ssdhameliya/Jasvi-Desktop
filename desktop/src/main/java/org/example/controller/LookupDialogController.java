package org.example.controller;

import org.example.navigation.ScreenLifecycle;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.example.model.Lookup;
import org.example.service.LookupService;
import org.example.util.DocumentInspectionScaffold;
import org.example.util.IconFactory;
import org.example.util.OwnedAlert;
import org.example.util.UiTaskExecutor;

import java.util.Locale;

public class LookupDialogController implements ScreenLifecycle {
    @FXML private TextField txtCode, txtValue;
    @FXML private TextArea txtDescription;
    @FXML private Spinner<Integer> spnOrder;
    @FXML private CheckBox chkActive;
    @FXML private Label lblTitle, lblSubtitle, lblCodeLabel, lblValueLabel, lblCodeHint, lblStatusBadge, lblShortcuts, errCode, errValue;
    @FXML private Button btnSave, btnCancel;
    @FXML private StackPane headerIconHolder;
    @FXML private HBox heroHeader;

    private final LookupService service = new LookupService();
    private String lookupType;
    private Lookup editingLookup;
    private boolean saved;
    private boolean roleMode;
    private Lookup savedResult;
    private Dialog<Lookup> hostDialog;
    private boolean viewOnly = false;

    public void setViewMode(boolean viewOnly) {
        this.viewOnly = viewOnly;
        if (viewOnly) {
            applyViewMode();
        }
    }

    private void applyViewMode() {
        if (lblTitle != null) {
            lblTitle.setText("View " + (roleMode ? "Role" : (lookupType != null ? lookupType : "Master")) + " Details");
        }
        if (lblSubtitle != null) {
            lblSubtitle.setText("Master lookup value specification and display order configuration");
        }
        if (lblStatusBadge != null && editingLookup != null) {
            lblStatusBadge.setText("VIEW " + (lookupType != null ? lookupType.toUpperCase() : "MASTER") + " (" + (editingLookup.getLookupCode() != null ? editingLookup.getLookupCode() : "") + ")");
            lblStatusBadge.getStyleClass().removeAll("status-success", "status-info");
            lblStatusBadge.getStyleClass().add("status-info");
        }
        txtCode.setEditable(false);
        txtValue.setEditable(false);
        txtDescription.setEditable(false);
        spnOrder.setDisable(true);
        chkActive.setDisable(true);
        if (btnSave != null) {
            btnSave.setVisible(false);
            btnSave.setManaged(false);
        }
        if (btnCancel != null) {
            btnCancel.setText("Close");
        }
        if (lblShortcuts != null) {
            lblShortcuts.setText("Shortcuts: [Esc] Close");
        }
    }

    public void setHostDialog(Dialog<Lookup> dialog) {
        this.hostDialog = dialog;
        if (heroHeader != null && dialog != null) {
            DocumentInspectionScaffold.installDragPhysics(heroHeader, dialog);
        }
    }

    @FXML
    public void initialize() {
        spnOrder.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 999, 1));
        btnSave.setGraphic(IconFactory.icon("save", 16));
        btnCancel.setGraphic(IconFactory.icon("cancel", 16));
        if (headerIconHolder != null) {
            headerIconHolder.getChildren().setAll(IconFactory.icon("master", 24));
        }
        txtValue.textProperty().addListener((o, a, b) -> {
            clearError(txtValue, errValue);
            if (roleMode && editingLookup == null) {
                txtCode.setText(roleDisplayCode(b));
                clearError(txtCode, errCode);
            }
        });
        javafx.application.Platform.runLater(() -> {
            if (heroHeader != null && hostDialog != null) {
                DocumentInspectionScaffold.installDragPhysics(heroHeader, hostDialog);
            }
        });
    }

    public void setLookupType(String type) {
        this.lookupType = type;
        this.roleMode = isRoleType(type);
        txtCode.clear();
        if (!roleMode) {
            UiTaskExecutor.submitLatest(
                    "lookup-dialog-next-code-" + type,
                    () -> service.generateNextCode(type),
                    code -> { if (editingLookup == null && java.util.Objects.equals(this.lookupType, type)) txtCode.setText(code == null ? "" : code); },
                    failure -> showOperationError("Unable to generate master code", failure)
            );
        }
        lblTitle.setText("Add " + (roleMode ? "Role" : type) + " Value");
        lblSubtitle.setText(roleMode ? "Add an application role. Role Name is the security identity used by Login, User Access and Permissions." : "Add a reusable value to " + type);
        if (lblCodeLabel != null) lblCodeLabel.setText(roleMode ? "Role Code" : "Master Code *");
        if (lblValueLabel != null) lblValueLabel.setText(roleMode ? "Role Name *" : "Display Value *");
        if (lblCodeHint != null) lblCodeHint.setText(roleMode ? "Derived from Role Name; the internal master ID is hidden." : "System-generated master identifier");
        if (lblStatusBadge != null) {
            lblStatusBadge.setText("NEW " + (type != null ? type.toUpperCase() : "MASTER") + " DRAFT");
            lblStatusBadge.getStyleClass().removeAll("status-success", "status-info");
            lblStatusBadge.getStyleClass().add("status-info");
        }
        txtCode.setEditable(!roleMode);
        txtCode.setFocusTraversable(!roleMode);
        txtValue.setPromptText(roleMode ? "Example: Purchase" : "Enter a meaningful value");
        btnSave.setText("Save Master");
    }

    public void setLookup(Lookup lookup) {
        this.editingLookup = lookup;
        this.lookupType = lookup.getLookupType();
        this.roleMode = isRoleType(lookupType);
        txtValue.setText(lookup.getLookupValue());
        txtCode.setText(roleMode ? roleDisplayCode(lookup.getLookupValue()) : lookup.getLookupCode());
        txtDescription.setText(lookup.getDescription());
        spnOrder.getValueFactory().setValue(lookup.getDisplayOrder());
        chkActive.setSelected(lookup.isActive());
        lblTitle.setText("Edit " + (roleMode ? "Role" : lookupType) + " Value");
        lblSubtitle.setText(roleMode ? "Update the Role Name. Assigned users and permissions are migrated atomically when a role is renamed." : "Update the selected " + lookupType + " value");
        if (lblCodeLabel != null) lblCodeLabel.setText(roleMode ? "Role Code" : "Master Code *");
        if (lblValueLabel != null) lblValueLabel.setText(roleMode ? "Role Name *" : "Display Value *");
        if (lblCodeHint != null) lblCodeHint.setText(roleMode ? "Derived from Role Name; the internal master ID is hidden." : "System-generated master identifier");
        if (lblStatusBadge != null) {
            lblStatusBadge.setText("EDIT " + (lookupType != null ? lookupType.toUpperCase() : "MASTER") + " (" + (lookup.getLookupCode() != null ? lookup.getLookupCode() : "") + ")");
            lblStatusBadge.getStyleClass().removeAll("status-info", "status-success");
            lblStatusBadge.getStyleClass().add("status-success");
        }
        txtCode.setEditable(!roleMode);
        txtCode.setFocusTraversable(!roleMode);
        btnSave.setText("Update Master");
        if (viewOnly) {
            applyViewMode();
        }
    }

    @FXML
    private void save() {
        if (!validateForm()) return;

        boolean created = editingLookup == null;
        Lookup lookup = created ? new Lookup() : editingLookup;
        lookup.setLookupType(lookupType);
        lookup.setLookupCode(created ? "" : (roleMode ? editingLookup.getLookupCode() : txtCode.getText().trim()));
        lookup.setLookupValue(txtValue.getText().trim());
        lookup.setDescription(txtDescription.getText().trim());
        lookup.setDisplayOrder(spnOrder.getValue());
        lookup.setActive(chkActive.isSelected());

        btnSave.setDisable(true);
        UiTaskExecutor.submitAction(
                "lookup-dialog-save-" + lookup.getLookupType() + "-" + lookup.getLookupCode(),
                () -> { if (created) service.save(lookup); else service.update(lookup); return lookup; },
                savedLookup -> {
                    btnSave.setDisable(false);
                    saved = true;
                    savedResult = savedLookup;
                    close();
                    String displayCode = roleMode ? roleDisplayCode(lookup.getLookupValue()) : lookup.getLookupCode();
                    org.example.util.ToastManager.success((javafx.stage.Window) null,
                            "Master value " + (created ? "saved" : "updated"),
                            displayCode + " - " + lookup.getLookupValue());
                },
                failure -> { btnSave.setDisable(false); showOperationError("Unable to save master value", failure); }
        );
    }

    private void showOperationError(String title, Throwable failure) {
        Throwable current = failure;
        while (current != null && (current.getMessage() == null || current.getMessage().isBlank()) && current.getCause() != current) current = current.getCause();
        String detail = current == null || current.getMessage() == null ? "Unexpected error." : current.getMessage();
        new OwnedAlert(Alert.AlertType.ERROR, title + ": " + detail).showAndWait();
    }

    private boolean validateForm() {
        clearError(txtCode, errCode);
        clearError(txtValue, errValue);
        boolean valid = true;
        if (!roleMode && (txtCode.getText() == null || txtCode.getText().isBlank())) {
            showError(txtCode, errCode, "Code could not be generated.");
            valid = false;
        }
        if (txtValue.getText() == null || txtValue.getText().isBlank()) {
            showError(txtValue, errValue, "Value is required.");
            valid = false;
        }
        return valid;
    }

    private void showError(Control control, Label label, String message) {
        if (!control.getStyleClass().contains("validation-error")) control.getStyleClass().add("validation-error");
        label.setText(message);
        label.setVisible(true);
        label.setManaged(true);
        control.requestFocus();
    }

    private void clearError(Control control, Label label) {
        control.getStyleClass().remove("validation-error");
        label.setText("");
        label.setVisible(false);
        label.setManaged(false);
    }

    private static boolean isRoleType(String type) {
        return "ROLE".equalsIgnoreCase(type == null ? "" : type.trim());
    }

    private static String roleDisplayCode(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    public boolean wasSaved() { return saved; }
    public Lookup getSavedResult() { return savedResult; }

    @FXML private void cancel() { close(); }

    private void close() {
        if (hostDialog != null) {
            hostDialog.setResult(savedResult);
            hostDialog.close();
        } else if (txtCode != null && txtCode.getScene() != null && txtCode.getScene().getWindow() != null) {
            ((Stage) txtCode.getScene().getWindow()).close();
        }
    }

    @Override
    public void onScreenHidden() {
    }
}
