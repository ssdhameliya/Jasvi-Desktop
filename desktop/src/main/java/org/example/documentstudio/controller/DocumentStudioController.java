package org.example.documentstudio.controller;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import org.example.controller.DashboardController;
import org.example.documentstudio.model.DocumentType;
import org.example.documentstudio.model.ExcelTemplate;
import org.example.documentstudio.model.TemplateStatus;
import org.example.documentstudio.service.DocumentFlowRegistry;
import org.example.documentstudio.service.ExcelTemplateRenderer;
import org.example.documentstudio.service.ExcelTemplateStorageService;
import org.example.navigation.ScreenLifecycle;
import org.example.util.ExportFeedbackManager;
import org.example.util.UiTaskExecutor;
import org.example.util.AppDialogService;
import org.example.util.IconFactory;

import java.awt.Desktop;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** Universal Excel document template library and entry point for DSE ERP. */
public class DocumentStudioController implements ScreenLifecycle {
    @FXML private VBox root;
    @FXML private StackPane documentStudioPageIcon;
    @FXML private StackPane documentsKpiIcon, activeKpiIcon, defaultsKpiIcon, salesSafetyKpiIcon;
    @FXML private StackPane generalPdfModeIcon, erpTemplateModeIcon, oneEditorModeIcon;
    @FXML private FlowPane cards;
    @FXML private HBox excelActions, excelEmptyActions;
    @FXML private Label lblStudioTitle, lblStudioSubtitle, lblLibraryTitle, lblLibraryTip, lblEmptyTitle, lblEmptyText;
    @FXML private Label lblTotalKpiTitle, lblActiveKpiTitle, lblSafetyText;
    @FXML private Label lblGuideOneTitle, lblGuideOneText, lblGuideTwoTitle, lblGuideTwoText, lblGuideThreeTitle, lblGuideThreeText;
    @FXML private VBox emptyState;
    @FXML private TextField txtSearch;
    @FXML private ComboBox<Object> cmbType;
    @FXML private ComboBox<Object> cmbStatus;
    @FXML private Label lblTotal;
    @FXML private Label lblActive;
    @FXML private Label lblPurchaseDefault;

    private List<ExcelTemplate> excelAll = List.of();

    @FXML
    public void initialize() {
        if (documentsKpiIcon != null) documentsKpiIcon.getChildren().setAll(IconFactory.icon("document", 18));
        if (activeKpiIcon != null) activeKpiIcon.getChildren().setAll(IconFactory.icon("complete", 18));
        if (defaultsKpiIcon != null) defaultsKpiIcon.getChildren().setAll(IconFactory.icon("workflow", 18));
        if (salesSafetyKpiIcon != null) salesSafetyKpiIcon.getChildren().setAll(IconFactory.icon("security", 18));
        applyModePresentation();
        cmbType.setItems(FXCollections.observableArrayList());
        cmbType.getItems().add("All document types");
        cmbType.getItems().addAll(Arrays.asList(DocumentType.values()));
        cmbType.getSelectionModel().selectFirst();
        cmbStatus.setItems(FXCollections.observableArrayList("All status", TemplateStatus.DRAFT,
                TemplateStatus.ACTIVE, TemplateStatus.ARCHIVED));
        cmbStatus.getSelectionModel().selectFirst();
        txtSearch.textProperty().addListener((obs, old, value) -> applyFilters());
        cmbType.valueProperty().addListener((obs, old, value) -> applyFilters());
        cmbStatus.valueProperty().addListener((obs, old, value) -> applyFilters());
    }

    @Override
    public void onScreenShown(boolean reused) {
        applyModePresentation();
        refresh();
    }

    @Override
    public void onScreenHidden() {
        UiTaskExecutor.cancel("document-studio-refresh");
    }

    @FXML
    public void refresh() {
        UiTaskExecutor.submitLatest("document-studio-refresh",
                this::readStudioSnapshot,
                this::applyStudioSnapshot,
                error -> AppDialogService.error(root, "Document library could not be refreshed", "Excel Studio", rootMessage(error)));
    }

    private StudioSnapshot readStudioSnapshot() {
        List<ExcelTemplate> excelTemplates = ExcelTemplateStorageService.listAll();
        String sales = excelDefaultName(excelTemplates, DocumentType.SALES_INVOICE);
        String purchase = excelDefaultName(excelTemplates, DocumentType.PURCHASE_INVOICE);
        String purchaseReturn = excelDefaultName(excelTemplates, DocumentType.PURCHASE_RETURN);
        String quotation = excelDefaultName(excelTemplates, DocumentType.QUOTATION);
        String defaultSummary = "Excel • Sales: " + sales + "   •   Purchase: " + purchase
                + "   •   Purchase Return: " + purchaseReturn + "   •   Quotation: " + quotation
                + "   •   Missing/invalid defaults use built-in Excel.";
        return new StudioSnapshot(List.copyOf(excelTemplates), defaultSummary);
    }

    private void applyStudioSnapshot(StudioSnapshot snapshot) {
        excelAll = snapshot.excelTemplates();
        lblTotal.setText(Integer.toString(excelAll.size()));
        lblActive.setText(Long.toString(excelAll.stream().filter(t -> t.getStatus() == TemplateStatus.ACTIVE).count()));
        lblPurchaseDefault.setText(snapshot.defaultSummary());
        applyFilters();
    }

    private static String excelDefaultName(List<ExcelTemplate> templates, DocumentType type) {
        return templates.stream()
                .filter(t -> t.getDocumentType() == type)
                .filter(t -> t.getStatus() == TemplateStatus.ACTIVE)
                .filter(ExcelTemplate::isDefaultTemplate)
                .map(ExcelTemplate::getName)
                .findFirst().orElse("Built-in Excel");
    }

    private void applyModePresentation() {
        if (documentStudioPageIcon != null) documentStudioPageIcon.getChildren().setAll(IconFactory.icon("excel", 24));
        if (generalPdfModeIcon != null) generalPdfModeIcon.getChildren().setAll(IconFactory.icon("import", 18));
        if (erpTemplateModeIcon != null) erpTemplateModeIcon.getChildren().setAll(IconFactory.icon("workflow", 18));
        if (oneEditorModeIcon != null) oneEditorModeIcon.getChildren().setAll(IconFactory.icon("excel", 18));
        if (excelActions != null) { excelActions.setVisible(true); excelActions.setManaged(true); }
        if (excelEmptyActions != null) { excelEmptyActions.setVisible(true); excelEmptyActions.setManaged(true); }
        if (lblStudioTitle != null) lblStudioTitle.setText("Excel Studio");
        if (lblStudioSubtitle != null) lblStudioSubtitle.setText("Design, map and automate Excel business documents and reports.");
        if (lblTotalKpiTitle != null) lblTotalKpiTitle.setText("Excel Templates");
        if (lblActiveKpiTitle != null) lblActiveKpiTitle.setText("Active Excel Templates");
        if (lblSafetyText != null) lblSafetyText.setText("Standard PDF flows and runtime remain completely protected");
        if (lblLibraryTitle != null) lblLibraryTitle.setText("Excel Template Library");
        if (lblLibraryTip != null) lblLibraryTip.setText("Excel templates use a built-in workbook whenever no valid custom default exists.");
        if (lblEmptyTitle != null) lblEmptyTitle.setText("No Excel templates yet");
        if (lblEmptyText != null) lblEmptyText.setText("Upload an .xlsx workbook or create a starter ERP Excel template.");
        if (lblGuideOneTitle != null) lblGuideOneTitle.setText("UPLOAD WORKBOOK");
        if (lblGuideOneText != null) lblGuideOneText.setText("Upload your own .xlsx design or create a clean ERP workbook and preserve compatible Excel formatting.");
        if (lblGuideTwoTitle != null) lblGuideTwoTitle.setText("ERP FIELD MAPPING");
        if (lblGuideTwoText != null) lblGuideTwoText.setText("Map ERP fields into cells, define repeating item and charge rows, and keep the workbook layout you designed.");
        if (lblGuideThreeTitle != null) lblGuideThreeTitle.setText("DEFAULT & FALLBACK");
        if (lblGuideThreeText != null) lblGuideThreeText.setText("Preview live sample data, make a template default, and automatically fall back to built-in Excel when needed.");
    }

    private void applyFilters() {
        if (cards == null) return;
        String search = txtSearch.getText() == null ? "" : txtSearch.getText().trim().toLowerCase();
        Object type = cmbType.getValue();
        Object status = cmbStatus.getValue();
        cards.getChildren().clear();
        List<ExcelTemplate> filtered = excelAll.stream()
                .filter(t -> search.isBlank() || t.getName().toLowerCase().contains(search) || t.getDocumentType().label().toLowerCase().contains(search))
                .filter(t -> !(type instanceof DocumentType dt) || t.getDocumentType() == dt)
                .filter(t -> !(status instanceof TemplateStatus st) || t.getStatus() == st)
                .toList();
        filtered.forEach(t -> cards.getChildren().add(excelCard(t)));
        int count = filtered.size();
        boolean empty = count == 0;
        emptyState.setVisible(empty);
        emptyState.setManaged(empty);
        cards.setVisible(!empty);
        cards.setManaged(!empty);
    }

    @FXML
    private void importExcel() {
        org.example.service.PermissionService.require("DOCUMENT_STUDIO.CREATE", "create or import Document Studio content");
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Upload Excel Template");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Excel Workbook", "*.xlsx"));
        var file = chooser.showOpenDialog(root.getScene().getWindow());
        if (file == null) return;
        DocumentType type = chooseExcelType();
        if (type == null) return;
        String name = askName(stripExtension(file.getName()), type.label() + " Excel");
        if (name == null) return;
        try {
            openExcelDesigner(ExcelTemplateStorageService.importWorkbook(file.toPath(), name, type));
        } catch (Exception e) {
            AppDialogService.error(root, "Excel upload failed", "Excel Studio", rootMessage(e));
        }
    }

    @FXML
    private void createExcelTemplate() {
        org.example.service.PermissionService.require("DOCUMENT_STUDIO.CREATE", "create or import Document Studio content");
        DocumentType type = chooseExcelType();
        if (type == null) return;
        String name = askName("New " + type.label() + " Excel", type.label() + " Excel");
        if (name == null) return;
        try {
            openExcelDesigner(ExcelTemplateStorageService.createBlank(name, type));
        } catch (Exception e) {
            AppDialogService.error(root, "Excel template could not be created", "Excel Studio", rootMessage(e));
        }
    }

    private DocumentType chooseExcelType() {
        List<DocumentType> types = Arrays.stream(DocumentType.values()).filter(DocumentType::isErpConnected).toList();
        org.example.util.OwnedDialog<DocumentType> dialog = new org.example.util.OwnedDialog<>();
        dialog.setTitle("Excel Template Type");
        dialog.setHeaderText("Choose the ERP document type for this workbook");
        ComboBox<DocumentType> choice = new ComboBox<>(FXCollections.observableArrayList(types));
        choice.setPromptText("Select document type");
        choice.setMaxWidth(Double.MAX_VALUE);
        VBox content = new VBox(8, new Label("Document type:"), choice);
        dialog.getDialogPane().setContent(content);
        ButtonType create = new ButtonType("Continue", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(create, ButtonType.CANCEL);
        javafx.scene.Node createButton = dialog.getDialogPane().lookupButton(create);
        createButton.disableProperty().bind(choice.valueProperty().isNull());
        dialog.setResultConverter(button -> button == create ? choice.getValue() : null);
        return dialog.showAndWait().orElse(null);
    }

    private VBox excelCard(ExcelTemplate template) {
        VBox card = new VBox(9);
        card.getStyleClass().add("doc-template-card");
        card.setPrefWidth(320);
        card.setMinWidth(300);
        card.setMaxWidth(350);

        StackPane preview = new StackPane();
        preview.getStyleClass().add("doc-template-preview");
        preview.setPrefHeight(125);
        VBox previewText = new VBox(7, IconFactory.icon("excel", 34), new Label("EXCEL • .xlsx"));
        previewText.setAlignment(javafx.geometry.Pos.CENTER);
        preview.getChildren().add(previewText);

        Label name = new Label(template.getName());
        name.getStyleClass().add("doc-template-name");
        name.setWrapText(true);
        HBox identity = new HBox(6, badge("EXCEL", "doc-template-erp"), new Label(template.getDocumentType().label()));

        boolean automatic = DocumentFlowRegistry.isExcelAutomatic(template.getDocumentType());
        HBox badges = new HBox(6,
                badge(template.getStatus().name(), "doc-template-status-" + template.getStatus().name().toLowerCase()),
                badge(automatic ? "AUTOMATIC" : "DESIGN ONLY", automatic ? "doc-template-status-active" : "doc-template-version"),
                badge("v" + template.getVersion(), "doc-template-version"));
        if (automatic && template.isDefaultTemplate()) {
            badges.getChildren().add(badge("★ DEFAULT", "doc-template-default"));
        }

        Button edit = new Button("Edit", IconFactory.compactIcon("edit", 15));
        edit.setOnAction(e -> openExcelDesigner(template));
        edit.getStyleClass().addAll("approved-button", "approved-primary-button", "doc-template-action-button");

        Button previewButton = new Button("Preview", IconFactory.compactIcon("view", 15));
        previewButton.getStyleClass().addAll("approved-button", "approved-secondary-button", "doc-template-action-button");
        previewButton.setOnAction(e -> previewExcel(template));

        MenuButton more = new MenuButton("Actions", IconFactory.compactIcon("actions", 15));
        MenuItem setDefault = new MenuItem("Set as Default");
        setDefault.setDisable(!automatic);
        setDefault.setOnAction(e -> setExcelDefault(template));

        MenuItem duplicate = new MenuItem("Duplicate");
        duplicate.setOnAction(e -> {
            org.example.service.PermissionService.require("DOCUMENT_STUDIO.CREATE", "duplicate an Excel template");
            try {
                ExcelTemplateStorageService.duplicate(template);
                refresh();
            } catch (Exception ex) {
                AppDialogService.error(root, "Could not duplicate", "Excel Studio", rootMessage(ex));
            }
        });

        MenuItem download = new MenuItem("Download Template");
        download.setOnAction(e -> downloadExcelTemplate(template));

        MenuItem archive = new MenuItem("Archive");
        archive.setDisable(template.getStatus() == TemplateStatus.ARCHIVED);
        archive.setOnAction(e -> {
            org.example.service.PermissionService.require("DOCUMENT_STUDIO.EDIT", "archive an Excel template");
            try {
                ExcelTemplateStorageService.archive(template);
                refresh();
            } catch (Exception ex) {
                AppDialogService.error(root, "Could not archive", "Excel Studio", rootMessage(ex));
            }
        });

        MenuItem delete = new MenuItem("Delete");
        delete.setOnAction(e -> deleteExcel(template));

        more.getItems().addAll(setDefault, duplicate, download, archive, new SeparatorMenuItem(), delete);
        IconFactory.decorateActionMenu(more);
        more.getStyleClass().addAll("approved-menu-button", "doc-template-more-button");

        HBox actions = new HBox(7, edit, previewButton, more);
        HBox.setHgrow(edit, Priority.ALWAYS);
        HBox.setHgrow(previewButton, Priority.ALWAYS);
        edit.setMaxWidth(Double.MAX_VALUE);
        previewButton.setMaxWidth(Double.MAX_VALUE);

        card.getChildren().addAll(preview, name, identity, badges, actions);
        return card;
    }

    private void openExcelDesigner(ExcelTemplate template) {
        org.example.service.PermissionService.require("DOCUMENT_STUDIO.EDIT", "edit an Excel template");
        ExcelStudioContext.open(template.getId());
        DashboardController.navigateFromDocumentStudio("Excel Studio", "/fxml/pages/ExcelDesigner.fxml");
    }

    private void previewExcel(ExcelTemplate template) {
        try {
            Path out = org.example.config.WorkspaceManager.getTempFolder().resolve("excel-studio-sample-" + template.getId() + ".xlsx");
            ExcelTemplateRenderer.renderSample(template, out);
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(out.toFile());
        } catch (Exception e) {
            AppDialogService.error(root, "Excel preview failed", "Excel Studio", rootMessage(e));
        }
    }

    private void setExcelDefault(ExcelTemplate template) {
        org.example.service.PermissionService.require("DOCUMENT_STUDIO.EDIT", "activate a default Excel template");
        if (!AppDialogService.confirm(root, "Activate Default Excel Template",
                "Use " + template.getName() + " as the default " + template.getDocumentType().label() + " Excel template?",
                "If this workbook becomes unavailable or invalid, " + org.example.service.BrandingService.applicationName() + " automatically falls back to its built-in Excel output.")) {
            return;
        }
        try {
            ExcelTemplateStorageService.activateAndSetDefault(template);
            AppDialogService.success(root, "Default Excel template updated",
                    template.getName() + " is now active. Built-in Excel remains the automatic fallback.");
            refresh();
        } catch (Exception e) {
            AppDialogService.error(root, "Could not set Excel default", "Excel Studio", rootMessage(e));
        }
    }

    private void downloadExcelTemplate(ExcelTemplate template) {
        org.example.service.PermissionService.require("DOCUMENT_STUDIO.MANAGE_TEMPLATES", "download an Excel template");
        try {
            String safeName = template.getName().replaceAll("[^A-Za-z0-9._ -]", "_");
            java.io.File file = ExportFeedbackManager.resolveWorkspaceExportFile("Templates", safeName, "xlsx");
            if (file == null) return;
            if (!ExportFeedbackManager.checkFileWriteable(root, file)) return;
            java.nio.file.Files.copy(ExcelTemplateStorageService.sourceWorkbook(template), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            ExportFeedbackManager.notifyExportSuccess(root, file, "Excel Template Downloaded");
        } catch (Exception e) {
            AppDialogService.error(root, "Excel download failed", "Excel Studio", rootMessage(e));
        }
    }

    private void deleteExcel(ExcelTemplate template) {
        org.example.service.PermissionService.require("DOCUMENT_STUDIO.EDIT", "delete an Excel template");
        if (template.isDefaultTemplate()) {
            AppDialogService.info(root, "Default template", "Excel Studio", "Choose another default or archive this template first. Runtime Excel will otherwise use its built-in fallback.");
            return;
        }
        if (!AppDialogService.confirm(root, "Delete Excel Template", "Delete " + template.getName() + "?", "This removes the Document Studio copy and its version history.")) {
            return;
        }
        try {
            ExcelTemplateStorageService.delete(template);
            refresh();
        } catch (Exception e) {
            AppDialogService.error(root, "Could not delete", "Excel Studio", rootMessage(e));
        }
    }

    private String askName(String initial, String type) {
        TextInputDialog dialog = new org.example.util.OwnedTextInputDialog(initial);
        dialog.setTitle("Template Name");
        dialog.setHeaderText("Name your " + type + " template");
        dialog.setContentText("Template name:");
        return dialog.showAndWait().map(String::trim).filter(v -> !v.isBlank()).orElse(null);
    }

    private Label badge(String text, String style) {
        Label label = new Label(text);
        label.getStyleClass().addAll("doc-template-badge", style);
        return label;
    }

    private static String stripExtension(String value) {
        int dot = value.lastIndexOf('.');
        return dot > 0 ? value.substring(0, dot) : value;
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return root.getMessage() == null || root.getMessage().isBlank() ? root.getClass().getSimpleName() : root.getMessage();
    }

    private record StudioSnapshot(List<ExcelTemplate> excelTemplates, String defaultSummary) { }
}
