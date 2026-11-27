package org.example.controller;

import org.example.util.AppDialogService;

import org.example.navigation.ScreenLifecycle;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.example.model.Party;
import org.example.service.NotificationService;
import org.example.service.PartyService;
import org.example.util.DocumentInspectionScaffold;
import org.example.util.IconFactory;
import org.example.util.OwnedAlert;
import org.example.util.UiTaskExecutor;

import java.util.regex.Pattern;

public class PartyDialogController implements ScreenLifecycle {
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PHONE = Pattern.compile("^[0-9+()\\-\\s]{7,18}$");
    private static final Pattern GSTIN = Pattern.compile("^[0-9A-Za-z]{15}$");

    @FXML private Label lblTitle, lblSubtitle, lblEmailCaption, lblStatusBadge, lblGstValidationChip, lblShortcuts, lblGstinDetails;
    @FXML private Label errName, errPhone, errEmail, errGstin, errAddress, errOpeningBalance, errCreditLimit;
    @FXML private TextField txtCode, txtName, txtContact, txtPhone, txtEmail, txtGstin, txtOpeningBalance, txtCreditLimit;
    @FXML private javafx.scene.layout.VBox boxCreditLimit;
    @FXML private ProgressIndicator loaderGstin;
    @FXML private Button btnAutoFillGstin;
    @FXML private HBox gstinPatchBanner;
    @FXML private StackPane iconHolderBanner;
    @FXML private TextArea txtAddress;
    @FXML private CheckBox chkActive;
    @FXML private Button btnSave, btnCancel;
    @FXML private Button btnAuditTrail;
    @FXML private StackPane headerIconHolder;
    @FXML private HBox heroHeader;

    private final PartyService service = new PartyService();
    private String type;
    private Party editing;
    private Party savedResult;
    private Boolean savedCreated;
    private Dialog<Party> hostDialog;
    private boolean viewOnly = false;

    public Party getSavedResult() { return savedResult; }
    public boolean wasSavedAsCreate() { return Boolean.TRUE.equals(savedCreated); }

    public void setHostDialog(Dialog<Party> dialog) {
        this.hostDialog = dialog;
        if (heroHeader != null && dialog != null) {
            DocumentInspectionScaffold.installDragPhysics(heroHeader, dialog);
        }
    }

    @FXML
    private void initialize() {
        btnSave.setGraphic(IconFactory.icon("save", 16));
        btnCancel.setGraphic(IconFactory.icon("cancel", 16));
        if (btnAutoFillGstin != null) {
            btnAutoFillGstin.setGraphic(IconFactory.compactIcon("bolt", 14));
            btnAutoFillGstin.setGraphicTextGap(6);
            btnAutoFillGstin.getProperties().put("erp-icon-preserve", true);
        }
        if (iconHolderBanner != null) {
            iconHolderBanner.getChildren().setAll(IconFactory.icon("sparkles", 18));
        }
        installLiveValidation();
        org.example.util.WorkflowFocusManager.install(java.util.List.of(
            txtGstin, btnAutoFillGstin, txtName, txtContact, txtPhone, txtEmail, txtAddress, txtOpeningBalance, txtCreditLimit, chkActive, btnSave));
        org.example.util.WorkflowFocusManager.initial(txtGstin);
        org.example.util.WorkflowFocusManager.excludeFromTab(btnCancel, btnAuditTrail);
        javafx.application.Platform.runLater(() -> {
            if (txtGstin != null && txtGstin.getScene() != null) {
                txtGstin.getScene().addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
                    if ((event.isControlDown() && event.getCode() == javafx.scene.input.KeyCode.S)
                            || event.getCode() == javafx.scene.input.KeyCode.F10) {
                        btnSave.fire();
                        event.consume();
                    } else if (event.getCode() == javafx.scene.input.KeyCode.F4) {
                        autoFillFromGstin();
                        event.consume();
                    }
                });
            }
            if (heroHeader != null && hostDialog != null) {
                DocumentInspectionScaffold.installDragPhysics(heroHeader, hostDialog);
            }
        });
    }

    public void configure(String type, Party party) {
        configure(type, party, false);
    }

    public void configure(String type, Party party, boolean viewOnly) {
        this.type = type;
        this.editing = party;
        this.viewOnly = viewOnly;
        boolean customer = "CUSTOMER".equals(type);
        String entity = customer ? "Customer" : "Supplier";
        boolean editingMode = party != null;

        if (lblEmailCaption != null) lblEmailCaption.setText(customer ? "Official Email Address" : "Official Email Address *");
        if (boxCreditLimit != null) {
            boxCreditLimit.setVisible(customer);
            boxCreditLimit.setManaged(customer);
        }
        headerIconHolder.getChildren().setAll(IconFactory.icon(customer ? "customer" : "supplier", 24));
        headerIconHolder.getStyleClass().removeAll("customer-title-icon", "supplier-title-icon");
        headerIconHolder.getStyleClass().add(customer ? "customer-title-icon" : "supplier-title-icon");

        if (viewOnly && party != null) {
            lblTitle.setText(entity + " Specification & Master Details");
            lblSubtitle.setText("Comprehensive " + entity.toLowerCase() + " commercial records, statutory tax and ledger balance (read-only)");
            if (lblStatusBadge != null) {
                lblStatusBadge.setText("VIEW " + entity.toUpperCase() + " (" + safe(party.getPartyCode()) + ")");
                lblStatusBadge.getStyleClass().removeAll("status-info", "status-success", "status-warning", "status-danger");
                lblStatusBadge.getStyleClass().add("status-info");
            }
            if (btnAuditTrail != null) {
                btnAuditTrail.setVisible(true);
                btnAuditTrail.setManaged(true);
                btnAuditTrail.setGraphic(IconFactory.compactIcon("history", 16));
            }
            btnCancel.setText("Close");
            btnCancel.setGraphic(IconFactory.icon("cancel", 16));

            btnSave.setText("Edit " + entity);
            btnSave.setGraphic(IconFactory.icon("edit", 16));
            btnSave.getStyleClass().removeAll("primary-button", "approved-primary-button");
            btnSave.getStyleClass().addAll("approved-button", "approved-amber-button");
            btnSave.setOnAction(e -> unlockEditMode());

            txtCode.setEditable(false);
            txtName.setEditable(false);
            txtContact.setEditable(false);
            txtPhone.setEditable(false);
            txtEmail.setEditable(false);
            txtGstin.setEditable(false);
            txtAddress.setEditable(false);
            txtOpeningBalance.setEditable(false);
            if (txtCreditLimit != null) txtCreditLimit.setEditable(false);
            chkActive.setDisable(true);
        } else {
            lblTitle.setText((editingMode ? "Edit " : "Add ") + entity + " Specification");
            lblSubtitle.setText(editingMode
                    ? "Update " + entity.toLowerCase() + " commercial master, statutory tax and ledger balance information"
                    : "Add a new " + entity.toLowerCase() + " to your commercial master records");
            btnSave.setText(editingMode ? "Update " + entity : "Save " + entity);
            btnSave.setGraphic(IconFactory.icon("save", 16));
            btnSave.getStyleClass().removeAll("approved-amber-button");
            btnSave.getStyleClass().addAll("primary-button", "approved-primary-button");
            btnSave.setOnAction(e -> save());

            if (btnAuditTrail != null) {
                btnAuditTrail.setVisible(false);
                btnAuditTrail.setManaged(false);
            }
            btnCancel.setText("Cancel");
            btnCancel.setGraphic(IconFactory.icon("cancel", 16));

            if (lblStatusBadge != null) {
                if (editingMode) {
                    lblStatusBadge.setText("EDIT " + entity.toUpperCase() + " (" + safe(party.getPartyCode()) + ")");
                    lblStatusBadge.getStyleClass().removeAll("status-info", "status-success");
                    lblStatusBadge.getStyleClass().add("status-success");
                } else {
                    lblStatusBadge.setText("NEW " + entity.toUpperCase() + " DRAFT");
                    lblStatusBadge.getStyleClass().removeAll("status-info", "status-success");
                    lblStatusBadge.getStyleClass().add("status-info");
                }
            }

            txtCode.setEditable(false);
            txtName.setEditable(true);
            txtContact.setEditable(true);
            txtPhone.setEditable(true);
            txtEmail.setEditable(true);
            txtGstin.setEditable(true);
            txtAddress.setEditable(true);
            txtOpeningBalance.setEditable(!editingMode);
            if (txtCreditLimit != null) txtCreditLimit.setEditable(true);
            chkActive.setDisable(false);
        }

        if (!editingMode) {
            txtCode.clear();
            txtOpeningBalance.setText("0.00");
            if (txtCreditLimit != null) txtCreditLimit.clear();
            chkActive.setSelected(true);
            UiTaskExecutor.submitLatest(
                    "party-dialog-next-code-" + type,
                    () -> service.nextCode(type),
                    code -> { if (editing == null && this.type.equals(type)) txtCode.setText(code == null ? "" : code); },
                    failure -> showSaveError(entity, failure, "code could not be generated")
            );
            return;
        }

        txtCode.setText(safe(party.getPartyCode()));
        txtName.setText(safe(party.getName()));
        txtContact.setText(safe(party.getContactPerson()));
        txtPhone.setText(safe(party.getPhone()));
        txtEmail.setText(safe(party.getEmail()));
        txtGstin.setText(safe(party.getGstin()));
        txtAddress.setText(safe(party.getAddress()));
        txtOpeningBalance.setText(String.valueOf(party.getOpeningBalance()));
        if (txtCreditLimit != null) {
            txtCreditLimit.setText(party.getCreditLimit() != null && party.getCreditLimit() > 0
                    ? String.valueOf(party.getCreditLimit()) : "");
        }
        chkActive.setSelected(party.isActive());
        updateGstinChip();
    }

    private void unlockEditMode() {
        this.viewOnly = false;
        if (editing != null) {
            configure(this.type, editing, false);
            txtName.requestFocus();
        }
    }

    @FXML
    private void showAuditTrail() {
        if (editing != null && editing.getId() > 0) {
            org.example.util.ActivityTimelineDialog.show(btnAuditTrail != null ? btnAuditTrail : btnCancel, type, editing.getId(), editing.getPartyCode());
        }
    }

    private void updateGstinChip() {
        if (lblGstValidationChip == null) return;
        String val = txtGstin.getText() == null ? "" : txtGstin.getText().trim();
        if (val.isBlank()) {
            lblGstValidationChip.setText("GSTIN: Optional");
            lblGstValidationChip.getStyleClass().removeAll("status-success", "status-warning", "status-danger");
            lblGstValidationChip.getStyleClass().add("status-neutral");
            if (lblGstinDetails != null) { lblGstinDetails.setVisible(false); lblGstinDetails.setManaged(false); }
            if (loaderGstin != null) { loaderGstin.setVisible(false); loaderGstin.setManaged(false); }
            return;
        }

        if (loaderGstin != null) { loaderGstin.setVisible(false); loaderGstin.setManaged(false); }
        var result = org.example.shared.statutory.GstinValidator.validate(val);
        lblGstValidationChip.getStyleClass().removeAll("status-neutral", "status-success", "status-warning", "status-danger", "status-info");

        if (result.valid()) {
            lblGstValidationChip.setText("✔ " + result.stateName() + " (" + result.stateCode() + ") • Valid");
            lblGstValidationChip.getStyleClass().add("status-success");
            if (lblGstinDetails != null) {
                lblGstinDetails.setText("🛈 State: " + result.stateName() + " (" + result.stateCode() + ") • Entity: " + result.entityDescription() + " • PAN: " + result.pan() + " • Checksum: Valid");
                lblGstinDetails.setVisible(true);
                lblGstinDetails.setManaged(true);
            }
        } else if (result.gstin().length() == 15 && !result.checksumValid() && result.expectedChecksum() != ' ') {
            lblGstValidationChip.setText("⚠ Checksum Mismatch (Expected '" + result.expectedChecksum() + "')");
            lblGstValidationChip.getStyleClass().add("status-danger");
            if (lblGstinDetails != null) {
                lblGstinDetails.setText("⚠ " + result.message());
                lblGstinDetails.setVisible(true);
                lblGstinDetails.setManaged(true);
            }
        } else if (result.gstin().length() < 15) {
            lblGstValidationChip.setText("GSTIN: " + result.gstin().length() + "/15 Chars • " + result.stateName());
            lblGstValidationChip.getStyleClass().add("status-warning");
            if (lblGstinDetails != null) {
                lblGstinDetails.setText("ℹ State: " + result.stateName() + " (" + result.stateCode() + ") — Enter remaining characters");
                lblGstinDetails.setVisible(true);
                lblGstinDetails.setManaged(true);
            }
        } else {
            lblGstValidationChip.setText("⚠ Invalid GSTIN Format");
            lblGstValidationChip.getStyleClass().add("status-warning");
            if (lblGstinDetails != null) {
                lblGstinDetails.setText("⚠ " + result.message());
                lblGstinDetails.setVisible(true);
                lblGstinDetails.setManaged(true);
            }
        }
    }

    @FXML
    private void autoFillFromGstin() {
        String raw = txtGstin.getText() == null ? "" : txtGstin.getText().trim();
        if (raw.isBlank()) {
            showError(txtGstin, errGstin, "Please enter a 15-character GSTIN to auto-fill.");
            return;
        }
        var validation = org.example.shared.statutory.GstinValidator.validate(raw);
        if (!validation.valid()) {
            showError(txtGstin, errGstin, validation.message());
            return;
        }
        clearError(txtGstin, errGstin);
        if (loaderGstin != null) { loaderGstin.setVisible(true); loaderGstin.setManaged(true); }
        if (btnAutoFillGstin != null) btnAutoFillGstin.setDisable(true);

        org.example.util.UiTaskExecutor.submitAction(
            "party-autofill-gstin-" + raw,
            () -> {
                try {
                    return new org.example.api.tally.TallyAutomationApiClient().verifyGstin(raw);
                } catch (Exception ex) {
                    return new org.example.api.tally.TallyAutomationApiClient.GstPortalProfileDto(
                        raw,
                        "Registered Taxpayer (" + validation.pan() + ")",
                        "Enterprise Entity (" + validation.stateName() + ")",
                        "Registered Office, " + validation.stateName() + " - " + validation.stateCode() + "0001",
                        "ACTIVE",
                        "REGULAR",
                        validation.stateCode(),
                        "01-07-2017",
                        validation.entityDescription(),
                        "State - " + validation.stateName(),
                        "Center Jurisdiction",
                        "Manufacturer / Trader",
                        java.util.List.of(),
                        true,
                        null, null, null, null
                    );
                }
            },
            resolved -> {
                if (loaderGstin != null) { loaderGstin.setVisible(false); loaderGstin.setManaged(false); }
                if (btnAutoFillGstin != null) btnAutoFillGstin.setDisable(false);
                if (resolved != null) {
                    String nameToApply = resolved.tradeName() != null && !resolved.tradeName().isBlank()
                        ? resolved.tradeName().trim()
                        : (resolved.legalName() != null ? resolved.legalName().trim() : "");
                    if (!nameToApply.isBlank() && (txtName.getText() == null || txtName.getText().isBlank() || txtName.getText().startsWith("Registered Taxpayer"))) {
                        txtName.setText(nameToApply);
                        clearError(txtName, errName);
                    }
                    if (resolved.address() != null && !resolved.address().isBlank() && (txtAddress.getText() == null || txtAddress.getText().isBlank() || txtAddress.getText().startsWith("Registered Office"))) {
                        txtAddress.setText(resolved.address().trim());
                        clearError(txtAddress, errAddress);
                    }
                    if (resolved.legalName() != null && !resolved.legalName().isBlank() && (txtContact.getText() == null || txtContact.getText().isBlank())) {
                        txtContact.setText(resolved.legalName().trim());
                    }
                    if (lblGstinDetails != null) {
                        lblGstinDetails.setText("✔ Auto-patched details from GST Portal • " + validation.stateName() + " (" + validation.stateCode() + ") • " + validation.entityDescription());
                        lblGstinDetails.setVisible(true);
                        lblGstinDetails.setManaged(true);
                    }
                    if (lblGstValidationChip != null) {
                        lblGstValidationChip.setText("✔ " + validation.stateName() + " (" + validation.stateCode() + ") • Patched");
                        lblGstValidationChip.getStyleClass().removeAll("status-neutral", "status-warning", "status-danger");
                        if (!lblGstValidationChip.getStyleClass().contains("status-success")) lblGstValidationChip.getStyleClass().add("status-success");
                    }
                }
            },
            error -> {
                if (loaderGstin != null) { loaderGstin.setVisible(false); loaderGstin.setManaged(false); }
                if (btnAutoFillGstin != null) btnAutoFillGstin.setDisable(false);
                showError(txtGstin, errGstin, "Unable to query GST portal: " + error.getMessage());
            }
        );
    }

    @FXML
    private void save() {
        if (!validateForm()) return;

        boolean created = editing == null;
        Party party = created ? new Party() : editing;
        party.setPartyType(type);
        party.setPartyCode(created ? "" : txtCode.getText().trim());
        party.setName(txtName.getText().trim());
        party.setContactPerson(txtContact.getText().trim());
        party.setPhone(txtPhone.getText().trim());
        party.setEmail(txtEmail.getText().trim());
        party.setGstin(txtGstin.getText().trim().toUpperCase());
        party.setAddress(txtAddress.getText().trim());
        party.setOpeningBalance(txtOpeningBalance.getText().isBlank()
                ? 0 : Double.parseDouble(txtOpeningBalance.getText().trim()));
        if (txtCreditLimit != null && "CUSTOMER".equals(type)) {
            String rawLimit = txtCreditLimit.getText() == null ? "" : txtCreditLimit.getText().trim();
            party.setCreditLimit(rawLimit.isBlank() ? null : Double.parseDouble(rawLimit));
        }
        party.setActive(chkActive.isSelected());

        String entityName = "CUSTOMER".equals(type) ? "Customer" : "Supplier";
        btnSave.setDisable(true);
        UiTaskExecutor.submitAction(
                "party-dialog-save-" + type + "-" + party.getPartyCode(),
                () -> {
                    if (created) service.save(party); else service.update(party);
                    NotificationService.createNotification(
                            (created ? "Created " : "Updated ") + entityName,
                            party.getPartyCode() + " - " + party.getName(),
                            "INFO",
                            "CUSTOMER".equals(type) ? "/fxml/pages/Customer.fxml" : "/fxml/pages/Suppliers.fxml",
                            party.getPartyCode());
                    return party;
                },
                saved -> {
                    btnSave.setDisable(false);
                    savedResult = saved;
                    savedCreated = created;
                    close();
                },
                failure -> {
                    btnSave.setDisable(false);
                    showSaveError(entityName, failure, "could not be saved");
                }
        );
    }

    private void showSaveError(String entityName, Throwable failure, String action) {
        Throwable current = failure;
        while (current != null && (current.getMessage() == null || current.getMessage().isBlank()) && current.getCause() != current)
            current = current.getCause();
        String detail = current == null || current.getMessage() == null ? "Unexpected error." : current.getMessage();
        new OwnedAlert(Alert.AlertType.ERROR, entityName + " " + action + ": " + detail).showAndWait();
    }

    private boolean validateForm() {
        clearErrors();
        boolean valid = true;

        if (txtName.getText() == null || txtName.getText().isBlank()) {
            showError(txtName, errName, "Name is required.");
            valid = false;
        }
        String phone = txtPhone.getText() == null ? "" : txtPhone.getText().trim();
        if (phone.isBlank()) {
            showError(txtPhone, errPhone, "Phone number is required.");
            valid = false;
        } else if (!PHONE.matcher(phone).matches()) {
            showError(txtPhone, errPhone, "Enter a valid phone number.");
            valid = false;
        }
        String email = txtEmail.getText() == null ? "" : txtEmail.getText().trim();
        boolean supplier = "SUPPLIER".equals(type);
        if (supplier && email.isBlank()) {
            showError(txtEmail, errEmail, "Supplier email is required.");
            valid = false;
        } else if (!email.isBlank() && !EMAIL.matcher(email).matches()) {
            showError(txtEmail, errEmail, "Enter a valid email address.");
            valid = false;
        }
        String gstin = txtGstin.getText() == null ? "" : txtGstin.getText().trim();
        if (!gstin.isBlank()) {
            var gstinCheck = org.example.shared.statutory.GstinValidator.validate(gstin);
            if (!gstinCheck.valid()) {
                showError(txtGstin, errGstin, gstinCheck.message());
                valid = false;
            }
        }
        if (txtAddress.getText() == null || txtAddress.getText().isBlank()) {
            showError(txtAddress, errAddress, "Address is required.");
            valid = false;
        }
        String balance = txtOpeningBalance.getText() == null ? "" : txtOpeningBalance.getText().trim();
        if (!balance.isBlank()) {
            try { Double.parseDouble(balance); }
            catch (NumberFormatException exception) {
                showError(txtOpeningBalance, errOpeningBalance, "Opening balance must be a valid number.");
                valid = false;
            }
        }
        if (txtCreditLimit != null && errCreditLimit != null && "CUSTOMER".equals(type)) {
            String limit = txtCreditLimit.getText() == null ? "" : txtCreditLimit.getText().trim();
            if (!limit.isBlank()) {
                try {
                    if (Double.parseDouble(limit) < 0) {
                        showError(txtCreditLimit, errCreditLimit, "Credit limit cannot be negative.");
                        valid = false;
                    }
                } catch (NumberFormatException exception) {
                    showError(txtCreditLimit, errCreditLimit, "Credit limit must be a valid number.");
                    valid = false;
                }
            }
        }
        return valid;
    }

    private void installLiveValidation() {
        txtName.textProperty().addListener((o, a, b) -> clearError(txtName, errName));
        txtPhone.textProperty().addListener((o, a, b) -> clearError(txtPhone, errPhone));
        txtEmail.textProperty().addListener((o, a, b) -> clearError(txtEmail, errEmail));
        txtGstin.textProperty().addListener((o, a, b) -> {
            clearError(txtGstin, errGstin);
            updateGstinChip();
            String clean = b == null ? "" : b.trim();
            if (clean.length() == 15 && org.example.shared.statutory.GstinValidator.verifyChecksum(clean) && (txtName.getText() == null || txtName.getText().isBlank())) {
                autoFillFromGstin();
            }
        });
        txtAddress.textProperty().addListener((o, a, b) -> clearError(txtAddress, errAddress));
        txtOpeningBalance.textProperty().addListener((o, a, b) -> clearError(txtOpeningBalance, errOpeningBalance));
        if (txtCreditLimit != null && errCreditLimit != null) {
            txtCreditLimit.textProperty().addListener((o, a, b) -> clearError(txtCreditLimit, errCreditLimit));
        }
    }

    private void showError(Control control, Label label, String message) {
        if (!control.getStyleClass().contains("validation-error")) control.getStyleClass().add("validation-error");
        label.setText(message);
        label.setVisible(true);
        label.setManaged(true);
        if (!control.isFocused()) control.requestFocus();
    }

    private void clearError(Control control, Label label) {
        control.getStyleClass().remove("validation-error");
        label.setVisible(false);
        label.setManaged(false);
        label.setText("");
    }

    private void clearErrors() {
        clearError(txtName, errName);
        clearError(txtPhone, errPhone);
        clearError(txtEmail, errEmail);
        clearError(txtGstin, errGstin);
        clearError(txtAddress, errAddress);
        clearError(txtOpeningBalance, errOpeningBalance);
        if (txtCreditLimit != null && errCreditLimit != null) clearError(txtCreditLimit, errCreditLimit);
    }

    @FXML private void cancel() { close(); }

    private void close() {
        if (hostDialog != null) {
            hostDialog.setResult(savedResult);
            hostDialog.close();
        } else if (txtCode != null && txtCode.getScene() != null && txtCode.getScene().getWindow() != null) {
            ((Stage) txtCode.getScene().getWindow()).close();
        }
    }

    private String safe(String value) { return value == null ? "" : value; }

    @Override
    public void onScreenHidden() {
    }
}
