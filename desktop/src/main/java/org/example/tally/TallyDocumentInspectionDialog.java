package org.example.tally;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.example.api.tally.TallyAutomationApiClient;
import org.example.util.IconFactory;
import org.example.util.OwnedDialog;
import org.example.util.PopupTableWorkspace;
import org.example.util.UiActionIcons;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Centered, full-size modal window popup for inspecting original Tally bill documents (PDF, Excel, JPG, PNG).
 * Features high-resolution rendering, zoom in/out, fit to window, rotation, and multi-page navigation.
 */
public final class TallyDocumentInspectionDialog {
    private static final Logger log = LoggerFactory.getLogger(TallyDocumentInspectionDialog.class);

    private TallyDocumentInspectionDialog() {}

    public static void show(Node ownerNode, TallyAutomationApiClient.BillRecordDto bill) {
        if (bill == null) return;

        OwnedDialog<ButtonType> dialog = new OwnedDialog<>(ownerNode);
        dialog.setTitle("Document Inspection — " + (bill.invoiceNumber() != null ? bill.invoiceNumber() : "Voucher"));
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

        // 1. Header Bar: Document identity & Financial Summary
        HBox header = buildHeaderBar(bill, dialog);

        // 2. Viewer Component with interactive zoom, rotation, and paging
        VBox viewerBox = new VBox(8);
        VBox.setVgrow(viewerBox, Priority.ALWAYS);

        DocumentViewerState state = new DocumentViewerState();
        HBox toolbar = buildToolbar(state);
        ScrollPane scrollPane = buildViewerScrollPane(state);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        viewerBox.getChildren().addAll(toolbar, scrollPane);
        root.getChildren().addAll(header, viewerBox);
        pane.setContent(root);

        // Load document content
        loadFileIntoViewer(bill.attachedFilePaths(), state);

        dialog.showAndWait();
    }

    private static HBox buildHeaderBar(TallyAutomationApiClient.BillRecordDto bill, Dialog<?> dialog) {
        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(8, 12, 8, 12));
        header.getStyleClass().addAll("approved-card", "erp-page-identity-header");

        StackPane iconHolder = new StackPane();
        iconHolder.getChildren().setAll(IconFactory.icon("view", 22));

        VBox titleBox = new VBox(2);
        Label lblTitle = new Label("Invoice: " + (bill.invoiceNumber() != null ? bill.invoiceNumber() : "—"));
        lblTitle.getStyleClass().add("section-title");

        String vendorInfo = "Vendor: " + (bill.vendorNamePrinted() != null && !bill.vendorNamePrinted().isBlank() ? bill.vendorNamePrinted() : "Unspecified");
        if (bill.vendorGstin() != null && !bill.vendorGstin().isBlank()) {
            vendorInfo += " | GSTIN: " + bill.vendorGstin();
        }
        if (bill.invoiceDate() != null) {
            vendorInfo += " | Date: " + bill.invoiceDate().format(DateTimeFormatter.ofPattern("dd-MM-yyyy"));
        }
        Label lblSub = new Label(vendorInfo);
        lblSub.getStyleClass().add("muted-label");
        titleBox.getChildren().addAll(lblTitle, lblSub);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Financial KPI badges
        HBox kpiBox = new HBox(8);
        kpiBox.setAlignment(Pos.CENTER_RIGHT);

        BigDecimal taxable = bill.taxableAmount() != null ? bill.taxableAmount() : BigDecimal.ZERO;
        BigDecimal gst = (bill.cgstAmount() != null ? bill.cgstAmount() : BigDecimal.ZERO)
                .add(bill.sgstAmount() != null ? bill.sgstAmount() : BigDecimal.ZERO)
                .add(bill.igstAmount() != null ? bill.igstAmount() : BigDecimal.ZERO);
        BigDecimal total = bill.grandTotal() != null ? bill.grandTotal() : BigDecimal.ZERO;

        Label badgeTaxable = new Label("Taxable: ₹" + taxable.toPlainString());
        badgeTaxable.getStyleClass().addAll("approved-badge", "approved-blue-badge");

        Label badgeGst = new Label("GST: ₹" + gst.toPlainString());
        badgeGst.getStyleClass().addAll("approved-badge", "approved-purple-badge");

        Label badgeTotal = new Label("Total: ₹" + total.toPlainString());
        badgeTotal.getStyleClass().addAll("approved-badge", "approved-emerald-badge");

        Button btnClose = new Button("Close");
        btnClose.getStyleClass().addAll("approved-button", "approved-secondary-button");
        UiActionIcons.apply(btnClose, "cancel", "Close Document Inspection");
        btnClose.setOnAction(e -> dialog.close());

        kpiBox.getChildren().addAll(badgeTaxable, badgeGst, badgeTotal, btnClose);
        header.getChildren().addAll(iconHolder, titleBox, spacer, kpiBox);
        return header;
    }

    private static HBox buildToolbar(DocumentViewerState state) {
        HBox bar = new HBox(8);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6, 10, 6, 10));
        bar.getStyleClass().addAll("approved-card", "erp-table-toolbar");

        Button btnZoomIn = new Button("+");
        btnZoomIn.getStyleClass().addAll("approved-button", "approved-secondary-button");
        btnZoomIn.setOnAction(e -> state.zoom(1.2));

        Button btnZoomOut = new Button("-");
        btnZoomOut.getStyleClass().addAll("approved-button", "approved-secondary-button");
        btnZoomOut.setOnAction(e -> state.zoom(0.8));

        Button btnFit = new Button("Fit 100%");
        btnFit.getStyleClass().addAll("approved-button", "approved-secondary-button");
        btnFit.setOnAction(e -> state.resetZoom());

        Button btnRotate = new Button("Rotate");
        btnRotate.getStyleClass().addAll("approved-button", "approved-secondary-button");
        UiActionIcons.apply(btnRotate, "refresh", "Rotate 90 degrees clockwise");
        btnRotate.setOnAction(e -> state.rotate90());

        Label lblZoom = new Label("100%");
        lblZoom.getStyleClass().add("metric-note");
        state.zoomLabel = lblZoom;

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button btnPrev = new Button("Prev");
        btnPrev.getStyleClass().addAll("approved-button", "approved-secondary-button");
        btnPrev.setOnAction(e -> state.prevPage());

        Label lblPage = new Label("Page 1 of 1");
        lblPage.getStyleClass().add("metric-note");
        state.pageLabel = lblPage;

        Button btnNext = new Button("Next");
        btnNext.getStyleClass().addAll("approved-button", "approved-secondary-button");
        btnNext.setOnAction(e -> state.nextPage());

        state.btnPrev = btnPrev;
        state.btnNext = btnNext;

        bar.getChildren().addAll(btnZoomIn, btnZoomOut, btnFit, btnRotate, lblZoom, spacer, btnPrev, lblPage, btnNext);
        return bar;
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

    private static void loadFileIntoViewer(String filePath, DocumentViewerState state) {
        if (filePath == null || filePath.isBlank()) {
            state.showError("No document file path is attached to this bill record.");
            return;
        }

        File f = new File(filePath);
        if (!f.exists() || !f.isFile()) {
            state.showError("Document file not found on disk: " + filePath);
            return;
        }

        state.file = f;
        String lower = f.getName().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) {
            loadPdfDocument(f, state);
        } else if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".bmp") || lower.endsWith(".gif")) {
            loadImageDocument(f, state);
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
        try {
            Image img = new Image(new FileInputStream(file));
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

    private static final class DocumentViewerState {
        File file;
        PDDocument pdfDoc;
        int currentPage = 0;
        int totalPages = 1;
        double zoomScale = 1.0;
        double rotation = 0.0;

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
