package org.example.tally;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.example.api.tally.TallyAutomationApiClient;
import org.example.util.DynamicTableLayoutManager;
import org.example.util.IconFactory;
import org.example.util.OwnedDialog;
import org.example.util.SemanticTableCells;
import org.example.util.ToastManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;

/**
 * Centered modal preview dialog for TallyPrime JSON, GST Portal JSON, Tally Excel, and TallyPrime HTTP Push.
 * Provides interactive payload inspection and one-click direct automatic downloading to the user's Downloads folder
 * without painful file picker popups.
 */
public final class TallyExportPreviewDialog {
    private static final Logger log = LoggerFactory.getLogger(TallyExportPreviewDialog.class);
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    public enum ExportType {
        TALLY_JSON("TallyPrime 7.0 JSON", ".json", "Export Tally JSON"),
        GST_PORTAL_JSON("GST Portal B2B JSON", ".json", "Export GST Portal JSON"),
        TALLY_EXCEL("Tally Excel Register", ".xlsx", "Export Tally Excel"),
        TALLY_PUSH("TallyPrime HTTP Gateway", ".xml", "Push to TallyPrime (Port 9000)");

        final String title;
        final String extension;
        final String actionLabel;

        ExportType(String title, String extension, String actionLabel) {
            this.title = title;
            this.extension = extension;
            this.actionLabel = actionLabel;
        }
    }

    private TallyExportPreviewDialog() {}

    public static void show(Node ownerNode,
                            ExportType type,
                            Long batchId,
                            List<TallyAutomationApiClient.BillRecordDto> bills,
                            String payloadContent,
                            byte[] binaryPayload,
                            Consumer<TallyExportPreviewDialog> onConfirmAction) {

        OwnedDialog<ButtonType> dialog = new OwnedDialog<>(ownerNode);
        dialog.setTitle("Export Preview — " + type.title);
        dialog.setHeaderText(null);
        dialog.setResizable(true);

        DialogPane pane = dialog.getDialogPane();
        pane.getStyleClass().addAll("approved-dialog", "erp-export-preview-dialog");
        pane.getProperties().put("dse.dialog.presented", true);
        pane.setMinWidth(780);
        pane.setMinHeight(560);
        pane.setPrefSize(840, 620);

        pane.getButtonTypes().add(ButtonType.CLOSE);
        dialog.setOnShowing(ev -> {
            Node defaultButtonBar = pane.lookup(".button-bar");
            if (defaultButtonBar != null) {
                defaultButtonBar.setVisible(false);
                defaultButtonBar.setManaged(false);
            }
        });

        VBox root = new VBox(12);
        root.setPadding(new Insets(16));
        root.getStyleClass().addAll("approved-card", "erp-ui-standard");
        VBox.setVgrow(root, Priority.ALWAYS);

        // Header Card
        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(10, 14, 10, 14));
        header.getStyleClass().addAll("approved-card", "erp-page-identity-header");

        StackPane iconHolder = new StackPane();
        iconHolder.getChildren().setAll(IconFactory.icon(type == ExportType.TALLY_EXCEL ? "excel" : "file", 24));

        String timestamp = LocalDateTime.now().format(FILE_TS);
        String defaultFileName = (type == ExportType.TALLY_PUSH ? "Tally_Voucher_Push_" : "Tally_Batch_" + (batchId != null ? batchId : "ALL") + "_") + timestamp + type.extension;

        VBox titleBox = new VBox(3);
        Label lblTitle = new Label(type.title + " Preview");
        lblTitle.getStyleClass().addAll("erp-page-title", "approved-strong-label");

        int count = bills != null ? bills.size() : 0;
        double sumTotal = bills != null ? bills.stream().mapToDouble(b -> b.grandTotal() != null ? b.grandTotal().doubleValue() : 0.0).sum() : 0.0;
        Label lblSubtitle = new Label(String.format("Batch #%s | %d Vouchers | Total Value: ₹%,.2f | Target: ~/Downloads/%s",
                batchId != null ? batchId : "N/A", count, sumTotal, defaultFileName));
        lblSubtitle.getStyleClass().addAll("erp-page-subtitle", "approved-muted-label");
        titleBox.getChildren().addAll(lblTitle, lblSubtitle);
        HBox.setHgrow(titleBox, Priority.ALWAYS);

        header.getChildren().addAll(iconHolder, titleBox);
        org.example.util.DocumentInspectionScaffold.installDragPhysics(header, dialog);

        // Preview Area
        VBox previewBox = new VBox(8);
        VBox.setVgrow(previewBox, Priority.ALWAYS);

        Label lblSection = new Label(type == ExportType.TALLY_EXCEL ? "Voucher Summary Rows to Export" : "Generated Payload Preview");
        lblSection.getStyleClass().addAll("approved-strong-label", "erp-section-header");

        Node previewContent;
        if (type == ExportType.TALLY_EXCEL && (payloadContent == null || payloadContent.isBlank())) {
            // Tabular row preview for Excel
            TableView<TallyAutomationApiClient.BillRecordDto> tblSummary = new TableView<>();
            tblSummary.getStyleClass().addAll("approved-table", "erp-table-view");
            VBox.setVgrow(tblSummary, Priority.ALWAYS);

            TableColumn<TallyAutomationApiClient.BillRecordDto, String> cInv = new TableColumn<>("Invoice No");
            cInv.setCellValueFactory(v -> new javafx.beans.property.SimpleStringProperty(v.getValue().invoiceNumber()));
            TableColumn<TallyAutomationApiClient.BillRecordDto, String> cDate = new TableColumn<>("Date");
            cDate.setCellValueFactory(v -> new javafx.beans.property.SimpleStringProperty(v.getValue().invoiceDate() != null ? v.getValue().invoiceDate().toString() : ""));
            TableColumn<TallyAutomationApiClient.BillRecordDto, String> cVendor = new TableColumn<>("Vendor");
            cVendor.setCellValueFactory(v -> new javafx.beans.property.SimpleStringProperty(v.getValue().vendorNamePrinted()));
            TableColumn<TallyAutomationApiClient.BillRecordDto, String> cGstin = new TableColumn<>("GSTIN");
            cGstin.setCellValueFactory(v -> new javafx.beans.property.SimpleStringProperty(v.getValue().vendorGstin()));
            TableColumn<TallyAutomationApiClient.BillRecordDto, String> cTotal = new TableColumn<>("Grand Total (₹)");
            cTotal.setCellValueFactory(v -> new javafx.beans.property.SimpleStringProperty(v.getValue().grandTotal() != null ? String.format("₹%,.2f", v.getValue().grandTotal()) : "₹0.00"));
            cTotal.setCellFactory(x -> SemanticTableCells.amountString("total"));

            tblSummary.getColumns().addAll(cInv, cDate, cVendor, cGstin, cTotal);
            if (bills != null) tblSummary.getItems().setAll(bills);
            DynamicTableLayoutManager.install(tblSummary);
            previewContent = tblSummary;
        } else {
            TextArea txtPayload = new TextArea();
            txtPayload.setEditable(false);
            txtPayload.setWrapText(true);
            txtPayload.getStyleClass().addAll("approved-text-area", "erp-code-viewer");
            txtPayload.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 12px;");
            VBox.setVgrow(txtPayload, Priority.ALWAYS);
            txtPayload.setText(payloadContent != null ? payloadContent : "{}");
            previewContent = txtPayload;
        }

        previewBox.getChildren().addAll(lblSection, previewContent);

        // Bottom Action Bar
        HBox footer = new HBox(12);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(10, 0, 0, 0));

        Label lblAutoNotice = new Label("✓ Automatic download saves directly to Downloads without file picker");
        lblAutoNotice.getStyleClass().addAll("approved-muted-label", "erp-status-note");
        HBox.setHgrow(lblAutoNotice, Priority.ALWAYS);

        Button btnDownload = new Button(type == ExportType.TALLY_PUSH ? "Push to TallyPrime" : "Download to ~/Downloads");
        btnDownload.getStyleClass().addAll("approved-button", "approved-primary-button");
        btnDownload.setGraphic(IconFactory.compactIcon(type == ExportType.TALLY_PUSH ? "upload" : "download", 14));

        Button btnClose = new Button("Close");
        btnClose.getStyleClass().addAll("approved-button", "approved-secondary-button");
        btnClose.setGraphic(IconFactory.compactIcon("close", 14));
        btnClose.setOnAction(e -> dialog.close());

        btnDownload.setOnAction(e -> {
            if (type == ExportType.TALLY_PUSH) {
                dialog.close();
                if (onConfirmAction != null) onConfirmAction.accept(null);
                return;
            }

            // Perform automatic direct download
            Path downloadsPath = Path.of(System.getProperty("user.home"), "Downloads", defaultFileName);
            try {
                if (binaryPayload != null && binaryPayload.length > 0) {
                    Files.write(downloadsPath, binaryPayload);
                } else if (payloadContent != null) {
                    Files.writeString(downloadsPath, payloadContent, StandardCharsets.UTF_8);
                } else {
                    // Generate basic workbook or text
                    Files.writeString(downloadsPath, "No content generated.", StandardCharsets.UTF_8);
                }

                dialog.close();
                ToastManager.success(ownerNode, "Downloaded Successfully", "Saved to " + downloadsPath.getFileName().toString());

                // Reveal in folder if supported
                revealDownloadedFile(downloadsPath.toFile());
            } catch (Exception ex) {
                log.error("Failed automatic download to {}: {}", downloadsPath, ex.getMessage());
                ToastManager.error(ownerNode, "Download Failed", ex.getMessage());
            }
        });

        footer.getChildren().addAll(lblAutoNotice, btnClose, btnDownload);
        root.getChildren().addAll(header, previewBox, footer);
        pane.setContent(root);

        dialog.showAndWait();
    }

    private static void revealDownloadedFile(File file) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                // If file exists, we can optionally open or select it
                log.info("Saved export artifact to: {}", file.getAbsolutePath());
            }
        } catch (Exception ignored) {}
    }
}
