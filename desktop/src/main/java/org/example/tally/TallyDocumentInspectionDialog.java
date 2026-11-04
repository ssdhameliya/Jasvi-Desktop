package org.example.tally;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.poi.ss.usermodel.*;
import org.example.api.tally.TallyAutomationApiClient;
import org.example.util.IconFactory;
import org.example.util.OwnedDialog;
import org.example.util.PopupTableWorkspace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Universal modal window popup for inspecting original documents across all formats:
 * PDF, Images (PNG, JPG, JPEG, BMP), Excel (XLSX, XLS), and CSV.
 * Features high-resolution rendering, zoom in/out, fit width, rotation, page navigation,
 * spreadsheet grids, and native window minimize, maximize, and restore controls.
 */
public final class TallyDocumentInspectionDialog {
    private static final Logger log = LoggerFactory.getLogger(TallyDocumentInspectionDialog.class);

    private TallyDocumentInspectionDialog() {}

    public static void show(Node ownerNode, TallyAutomationApiClient.BillRecordDto bill) {
        if (bill == null) return;
        String title = "Invoice: " + (bill.invoiceNumber() != null ? bill.invoiceNumber() : "Voucher");
        String meta = "Vendor: " + (bill.vendorNamePrinted() != null ? bill.vendorNamePrinted() : "N/A")
                + " | Date: " + (bill.invoiceDate() != null ? bill.invoiceDate().format(DateTimeFormatter.ofPattern("dd-MM-yyyy")) : "N/A")
                + (bill.vendorGstin() != null && !bill.vendorGstin().isBlank() ? " | GSTIN: " + bill.vendorGstin() : "");
        String financial = String.format(Locale.ROOT, "Taxable: ₹%.2f   GST: ₹%.2f   Total: ₹%.2f",
                bill.taxableAmount() != null ? bill.taxableAmount().doubleValue() : 0.0,
                (bill.cgstAmount() != null ? bill.cgstAmount().doubleValue() : 0.0)
                        + (bill.sgstAmount() != null ? bill.sgstAmount().doubleValue() : 0.0)
                        + (bill.igstAmount() != null ? bill.igstAmount().doubleValue() : 0.0),
                bill.grandTotal() != null ? bill.grandTotal().doubleValue() : 0.0);
        File file = (bill.attachedFilePaths() != null && !bill.attachedFilePaths().isBlank())
                ? new File(bill.attachedFilePaths())
                : null;
        show(ownerNode, file, title, meta, financial);
    }

    public static void show(Node ownerNode, File file, String title, String subtitle, String financialSummary) {
        OwnedDialog<ButtonType> dialog = new OwnedDialog<>(ownerNode);
        dialog.setTitle(title != null ? title : "Document Inspection");
        dialog.setHeaderText(null);
        dialog.setResizable(true);

        DialogPane pane = dialog.getDialogPane();
        pane.getStyleClass().addAll("approved-dialog", "erp-document-inspection-dialog");
        pane.getProperties().put("dse.dialog.presented", true);
        PopupTableWorkspace.applyInspectionSizing(dialog, ownerNode);

        pane.getButtonTypes().add(ButtonType.CLOSE);
        dialog.setOnShowing(ev -> {
            Node defaultButtonBar = pane.lookup(".button-bar");
            if (defaultButtonBar != null) {
                defaultButtonBar.setVisible(false);
                defaultButtonBar.setManaged(false);
            }
        });

        VBox root = new VBox(10);
        root.setPadding(new Insets(12));
        root.getStyleClass().addAll("approved-card", "erp-ui-standard");
        VBox.setVgrow(root, Priority.ALWAYS);

        DocumentViewerState state = new DocumentViewerState();

        // 1. Header Bar with document meta, financial summary, maximize & close buttons
        HBox header = buildHeaderBar(dialog, title, subtitle, financialSummary);

        // 2. Viewer Toolbar (Zoom, Fit, Rotate, Paging)
        HBox toolbar = buildToolbar(state);

        // 3. Main Viewer Container
        StackPane viewerContentArea = new StackPane();
        VBox.setVgrow(viewerContentArea, Priority.ALWAYS);
        state.contentArea = viewerContentArea;

        ScrollPane scrollPane = buildViewerScrollPane(state);
        state.scrollPane = scrollPane;
        viewerContentArea.getChildren().add(scrollPane);

        root.getChildren().addAll(header, toolbar, viewerContentArea);
        pane.setContent(root);

        // Responsive min window bounds
        dialog.setOnShown(ev -> {
            if (pane.getScene() != null && pane.getScene().getWindow() instanceof Stage stage) {
                stage.setMinWidth(900);
                stage.setMinHeight(650);
            }
        });

        // Load document content
        loadFileIntoViewer(file, state);

        try {
            dialog.showAndWait();
        } finally {
            if (state.pdfDoc != null) {
                try {
                    state.pdfDoc.close();
                } catch (Exception ignored) {}
            }
        }
    }

    private static HBox buildHeaderBar(Dialog<?> dialog, String titleText, String subtitleText, String financialSummary) {
        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(8, 12, 8, 12));
        header.getStyleClass().addAll("approved-card", "erp-page-identity-header");

        StackPane iconHolder = new StackPane();
        iconHolder.getChildren().setAll(IconFactory.icon("view", 22));

        VBox titleBox = new VBox(2);
        Label title = new Label(titleText != null ? titleText : "Document Inspection");
        title.getStyleClass().addAll("approved-page-title", "invoice-dialog-title");

        Label subtitle = new Label(subtitleText != null ? subtitleText : "Inspect original source document");
        subtitle.getStyleClass().addAll("approved-page-subtitle", "invoice-dialog-meta");
        titleBox.getChildren().addAll(title, subtitle);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox controls = new HBox(8);
        controls.setAlignment(Pos.CENTER_RIGHT);

        if (financialSummary != null && !financialSummary.isBlank()) {
            Label financialLabel = new Label(financialSummary);
            financialLabel.getStyleClass().addAll("financial-summary-badge", "erp-badge-emerald");
            controls.getChildren().add(financialLabel);
        }

        Button btnMaximize = new Button("Maximize");
        btnMaximize.getStyleClass().addAll("approved-button", "approved-secondary-button");
        btnMaximize.setGraphic(IconFactory.compactIcon("fullscreen", 14));
        btnMaximize.setOnAction(ev -> {
            if (dialog.getDialogPane().getScene() != null && dialog.getDialogPane().getScene().getWindow() instanceof Stage stage) {
                boolean isMax = stage.isMaximized();
                stage.setMaximized(!isMax);
                btnMaximize.setText(!isMax ? "Restore" : "Maximize");
                btnMaximize.setGraphic(IconFactory.compactIcon(!isMax ? "restore" : "fullscreen", 14));
            }
        });

        Button btnClose = new Button("Close");
        btnClose.getStyleClass().addAll("approved-button", "approved-danger-button");
        btnClose.setGraphic(IconFactory.compactIcon("cancel", 14));
        btnClose.setOnAction(ev -> dialog.close());

        controls.getChildren().addAll(btnMaximize, btnClose);
        header.getChildren().addAll(iconHolder, titleBox, spacer, controls);
        return header;
    }

    private static HBox buildToolbar(DocumentViewerState state) {
        HBox toolbar = new HBox(8);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(6, 10, 6, 10));
        toolbar.getStyleClass().addAll("approved-card", "document-viewer-toolbar");

        Button btnZoomIn = new Button("+");
        btnZoomIn.getStyleClass().addAll("approved-button", "approved-secondary-button");
        btnZoomIn.setGraphic(IconFactory.compactIcon("add", 13));
        btnZoomIn.setTooltip(new Tooltip("Zoom In (Ctrl + Wheel Up)"));
        btnZoomIn.setOnAction(ev -> state.zoom(1.2));

        Button btnZoomOut = new Button("-");
        btnZoomOut.getStyleClass().addAll("approved-button", "approved-secondary-button");
        btnZoomOut.setGraphic(IconFactory.compactIcon("delete", 13));
        btnZoomOut.setTooltip(new Tooltip("Zoom Out (Ctrl + Wheel Down)"));
        btnZoomOut.setOnAction(ev -> state.zoom(0.8));

        Button btnFit = new Button("Fit 100%");
        btnFit.getStyleClass().addAll("approved-button", "approved-secondary-button");
        btnFit.setGraphic(IconFactory.compactIcon("view", 13));
        btnFit.setOnAction(ev -> state.resetZoom());

        Button btnRotate = new Button("Rotate");
        btnRotate.getStyleClass().addAll("approved-button", "approved-secondary-button");
        btnRotate.setGraphic(IconFactory.compactIcon("refresh", 13));
        btnRotate.setOnAction(ev -> state.rotate90());

        Label zoomLabel = new Label("100%");
        zoomLabel.getStyleClass().addAll("zoom-percent-badge", "erp-badge-blue");
        state.zoomLabel = zoomLabel;

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button btnPrev = new Button("Prev");
        btnPrev.getStyleClass().addAll("approved-button", "approved-secondary-button");
        btnPrev.setGraphic(IconFactory.compactIcon("previous", 13));
        btnPrev.setOnAction(ev -> state.prevPage());
        state.btnPrev = btnPrev;

        Label pageLabel = new Label("Page 1 of 1");
        pageLabel.getStyleClass().addAll("document-page-indicator", "erp-badge-purple");
        state.pageLabel = pageLabel;

        Button btnNext = new Button("Next");
        btnNext.getStyleClass().addAll("approved-button", "approved-secondary-button");
        btnNext.setGraphic(IconFactory.compactIcon("next", 13));
        btnNext.setOnAction(ev -> state.nextPage());
        state.btnNext = btnNext;

        toolbar.getChildren().addAll(btnZoomIn, btnZoomOut, btnFit, btnRotate, zoomLabel, spacer, btnPrev, pageLabel, btnNext);
        return toolbar;
    }

    private static ScrollPane buildViewerScrollPane(DocumentViewerState state) {
        ScrollPane sp = new ScrollPane();
        sp.setFitToWidth(true);
        sp.setFitToHeight(true);
        sp.setPannable(true);
        sp.getStyleClass().addAll("approved-card", "document-preview-scroll");

        StackPane container = new StackPane();
        container.setAlignment(Pos.CENTER);
        container.setPadding(new Insets(16));

        ImageView iv = new ImageView();
        iv.setPreserveRatio(true);
        iv.setSmooth(true);
        state.imageView = iv;
        state.container = container;

        container.getChildren().add(iv);
        sp.setContent(container);

        // Ctrl + Mouse Wheel Zoom
        sp.addEventFilter(ScrollEvent.SCROLL, ev -> {
            if (ev.isControlDown()) {
                double delta = ev.getDeltaY();
                if (delta > 0) state.zoom(1.15);
                else if (delta < 0) state.zoom(0.85);
                ev.consume();
            }
        });

        return sp;
    }

    private static void loadFileIntoViewer(File f, DocumentViewerState state) {
        if (f == null) {
            state.showError("No document file is selected or attached.");
            return;
        }

        if (!f.exists() || !f.isFile()) {
            state.showError("Document file not found on disk: " + f.getAbsolutePath());
            return;
        }

        state.file = f;
        String lower = f.getName().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) {
            loadPdfDocument(f, state);
        } else if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".bmp") || lower.endsWith(".gif") || lower.endsWith(".webp")) {
            loadImageDocument(f, state);
        } else if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
            loadSpreadsheetDocument(f, state);
        } else if (lower.endsWith(".csv")) {
            loadCsvDocument(f, state);
        } else {
            state.showError("Preview is not supported for this file format (" + f.getName() + ").");
        }
    }

    private static void loadPdfDocument(File file, DocumentViewerState state) {
        try {
            PDDocument doc = Loader.loadPDF(file);
            state.pdfDoc = doc;
            state.totalPages = doc.getNumberOfPages();
            state.currentPage = 0;
            renderCurrentPdfPage(state);
        } catch (Exception ex) {
            log.error("Failed to render PDF: {}", ex.getMessage(), ex);
            state.showError("Could not render PDF preview: " + ex.getMessage());
        }
    }

    private static void renderCurrentPdfPage(DocumentViewerState state) {
        if (state.pdfDoc == null) return;
        try {
            PDFRenderer renderer = new PDFRenderer(state.pdfDoc);
            BufferedImage bim = renderer.renderImageWithDPI(state.currentPage, 150);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(bim, "png", baos);
            Image fxImg = new Image(new ByteArrayInputStream(baos.toByteArray()));
            state.imageView.setImage(fxImg);
            state.updatePageUi();
            state.applyTransforms();
        } catch (Exception ex) {
            log.error("Failed to render PDF page {}: {}", state.currentPage, ex.getMessage(), ex);
            state.showError("Could not render page " + (state.currentPage + 1));
        }
    }

    private static void loadImageDocument(File file, DocumentViewerState state) {
        try (var fis = new FileInputStream(file)) {
            Image img = new Image(fis);
            state.imageView.setImage(img);
            state.totalPages = 1;
            state.currentPage = 0;
            state.updatePageUi();
            state.applyTransforms();
        } catch (Exception ex) {
            log.error("Failed to load image: {}", ex.getMessage(), ex);
            state.showError("Could not load image: " + ex.getMessage());
        }
    }

    private static void loadSpreadsheetDocument(File file, DocumentViewerState state) {
        try (var fis = new FileInputStream(file);
             var workbook = WorkbookFactory.create(fis)) {
            int sheetCount = workbook.getNumberOfSheets();
            if (sheetCount == 0) {
                state.showError("Excel workbook contains no sheets.");
                return;
            }

            TabPane tabPane = new TabPane();
            tabPane.getStyleClass().addAll("approved-tab-pane", "spreadsheet-preview-tabs");
            VBox.setVgrow(tabPane, Priority.ALWAYS);

            for (int i = 0; i < sheetCount; i++) {
                Sheet sheet = workbook.getSheetAt(i);
                TableView<List<String>> table = buildTableViewForSheet(sheet);
                Tab tab = new Tab(sheet.getSheetName(), table);
                tab.setClosable(false);
                tabPane.getTabs().add(tab);
            }

            state.contentArea.getChildren().setAll(tabPane);
            if (state.pageLabel != null) state.pageLabel.setText(sheetCount + " Sheet" + (sheetCount > 1 ? "s" : ""));
            if (state.btnPrev != null) state.btnPrev.setDisable(true);
            if (state.btnNext != null) state.btnNext.setDisable(true);
            if (state.zoomLabel != null) state.zoomLabel.setText("Table View");
        } catch (Exception ex) {
            log.error("Failed to load spreadsheet: {}", ex.getMessage(), ex);
            state.showError("Could not render Excel workbook: " + ex.getMessage());
        }
    }

    private static TableView<List<String>> buildTableViewForSheet(Sheet sheet) {
        TableView<List<String>> table = new TableView<>();
        table.getStyleClass().addAll("approved-table", "spreadsheet-grid");

        DataFormatter formatter = new DataFormatter();
        int maxCols = 0;
        List<List<String>> rows = new ArrayList<>();

        for (Row row : sheet) {
            List<String> rowData = new ArrayList<>();
            short lastCellNum = row.getLastCellNum();
            if (lastCellNum > maxCols) maxCols = lastCellNum;
            for (int cn = 0; cn < lastCellNum; cn++) {
                org.apache.poi.ss.usermodel.Cell cell = row.getCell(cn, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                rowData.add(cell != null ? formatter.formatCellValue(cell) : "");
            }
            rows.add(rowData);
        }

        // Generate columns
        for (int c = 0; c < Math.min(maxCols, 30); c++) {
            final int colIndex = c;
            String colName = (colIndex < 26) ? String.valueOf((char) ('A' + colIndex)) : "Col " + (colIndex + 1);
            if (!rows.isEmpty() && colIndex < rows.get(0).size() && !rows.get(0).get(colIndex).isBlank()) {
                colName = rows.get(0).get(colIndex);
            }

            TableColumn<List<String>, String> column = new TableColumn<>(colName);
            column.setCellValueFactory(data -> {
                List<String> r = data.getValue();
                return new SimpleStringProperty((colIndex < r.size()) ? r.get(colIndex) : "");
            });
            table.getColumns().add(column);
        }

        // Add rows (skip header row 0 if used as column name)
        ObservableList<List<String>> tableData = FXCollections.observableArrayList();
        for (int r = 1; r < rows.size(); r++) {
            tableData.add(rows.get(r));
        }
        table.setItems(tableData);
        return table;
    }

    private static void loadCsvDocument(File file, DocumentViewerState state) {
        try {
            List<String> rawLines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            if (rawLines.isEmpty()) {
                state.showError("CSV file is empty.");
                return;
            }

            TableView<List<String>> table = new TableView<>();
            table.getStyleClass().addAll("approved-table", "spreadsheet-grid");

            String firstLine = rawLines.get(0);
            String delimiter = firstLine.contains("\t") ? "\t" : (firstLine.contains(";") ? ";" : ",");
            String[] headers = firstLine.split(delimiter);

            for (int c = 0; c < headers.length; c++) {
                final int colIndex = c;
                TableColumn<List<String>, String> column = new TableColumn<>(headers[c].trim().replaceAll("^\"|\"$", ""));
                column.setCellValueFactory(data -> {
                    List<String> r = data.getValue();
                    return new SimpleStringProperty((colIndex < r.size()) ? r.get(colIndex) : "");
                });
                table.getColumns().add(column);
            }

            ObservableList<List<String>> tableData = FXCollections.observableArrayList();
            for (int i = 1; i < rawLines.size(); i++) {
                String line = rawLines.get(i).trim();
                if (line.isBlank()) continue;
                String[] tokens = line.split(delimiter);
                List<String> row = new ArrayList<>();
                for (String t : tokens) row.add(t.trim().replaceAll("^\"|\"$", ""));
                tableData.add(row);
            }
            table.setItems(tableData);

            state.contentArea.getChildren().setAll(table);
            if (state.pageLabel != null) state.pageLabel.setText(tableData.size() + " CSV Rows");
            if (state.btnPrev != null) state.btnPrev.setDisable(true);
            if (state.btnNext != null) state.btnNext.setDisable(true);
            if (state.zoomLabel != null) state.zoomLabel.setText("CSV Grid");
        } catch (Exception ex) {
            log.error("Failed to load CSV: {}", ex.getMessage(), ex);
            state.showError("Could not render CSV file: " + ex.getMessage());
        }
    }

    private static final class DocumentViewerState {
        File file;
        PDDocument pdfDoc;
        int currentPage = 0;
        int totalPages = 1;
        double zoomScale = 1.0;
        double rotation = 0.0;

        StackPane contentArea;
        ScrollPane scrollPane;
        ImageView imageView;
        StackPane container;
        Label zoomLabel;
        Label pageLabel;
        Button btnPrev;
        Button btnNext;

        void zoom(double factor) {
            zoomScale = Math.max(0.2, Math.min(5.0, zoomScale * factor));
            applyTransforms();
        }

        void resetZoom() {
            zoomScale = 1.0;
            rotation = 0.0;
            applyTransforms();
        }

        void rotate90() {
            rotation = (rotation + 90.0) % 360.0;
            applyTransforms();
        }

        void nextPage() {
            if (pdfDoc != null && currentPage < totalPages - 1) {
                currentPage++;
                renderCurrentPdfPage(this);
            }
        }

        void prevPage() {
            if (pdfDoc != null && currentPage > 0) {
                currentPage--;
                renderCurrentPdfPage(this);
            }
        }

        void updatePageUi() {
            if (pageLabel != null) {
                pageLabel.setText("Page " + (currentPage + 1) + " of " + totalPages);
            }
            if (btnPrev != null) btnPrev.setDisable(currentPage <= 0);
            if (btnNext != null) btnNext.setDisable(currentPage >= totalPages - 1);
        }

        void applyTransforms() {
            if (imageView != null) {
                imageView.setScaleX(zoomScale);
                imageView.setScaleY(zoomScale);
                imageView.setRotate(rotation);
            }
            if (zoomLabel != null) {
                zoomLabel.setText(Math.round(zoomScale * 100) + "%");
            }
        }

        void showError(String msg) {
            if (container != null) {
                Label lbl = new Label(msg);
                lbl.getStyleClass().add("muted-label");
                container.getChildren().setAll(lbl);
            }
        }
    }
}
