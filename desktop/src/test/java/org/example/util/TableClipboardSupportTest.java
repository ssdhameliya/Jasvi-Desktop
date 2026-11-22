package org.example.util;

import javafx.application.Platform;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import org.example.model.Party;
import org.example.model.Sales;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;
import java.time.LocalDate;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TableClipboardSupportTest {

    private static boolean javaFxAvailable = false;

    @BeforeAll
    static void initJavaFx() {
        if (GraphicsEnvironment.isHeadless()) {
            javaFxAvailable = false;
            return;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("linux") && (System.getenv("DISPLAY") == null || System.getenv("DISPLAY").isBlank())) {
            javaFxAvailable = false;
            return;
        }
        try {
            CountDownLatch latch = new CountDownLatch(1);
            Platform.startup(latch::countDown);
            if (latch.await(3, TimeUnit.SECONDS)) {
                javaFxAvailable = true;
            }
        } catch (IllegalStateException alreadyStarted) {
            javaFxAvailable = true;
        } catch (Throwable ignored) {
            javaFxAvailable = false;
        }
    }

    @Test
    void testExtractCellTextAndRowTsv() {
        TableView<Sales> table = new TableView<>();
        TableColumn<Sales, String> colInvoice = new TableColumn<>("Invoice No.");
        colInvoice.setCellValueFactory(v -> new SimpleStringProperty(v.getValue().getInvoiceNo()));

        TableColumn<Sales, Double> colTotal = new TableColumn<>("Amount");
        colTotal.setCellValueFactory(v -> new SimpleDoubleProperty(v.getValue().getTotalAmount()).asObject());

        TableColumn<Sales, String> colCustomer = new TableColumn<>("Customer");
        colCustomer.setCellValueFactory(v -> new SimpleStringProperty(
                v.getValue().getCustomer() != null ? v.getValue().getCustomer().getName() : ""));

        table.getColumns().addAll(colInvoice, colTotal, colCustomer);

        Sales sale = new Sales();
        sale.setInvoiceNo("IN/17-11-2026/0001");
        sale.setTotalAmount(353705.00);
        sale.setInvoiceDate(LocalDate.of(2026, 11, 17));
        Party customer = new Party();
        customer.setName("Shailesh");
        sale.setCustomer(customer);

        table.setItems(FXCollections.observableArrayList(sale));

        // Test extractCellText
        String cellVal = TableClipboardSupport.extractCellText(null, colInvoice, 0, table);
        assertEquals("IN/17-11-2026/0001", cellVal, "Cell value must match invoice number");

        // Test extractRowTsv
        String rowVal = TableClipboardSupport.extractRowTsv(table, 0);
        assertTrue(rowVal.contains("IN/17-11-2026/0001"), "Row TSV must contain invoice number");
        assertTrue(rowVal.contains("Shailesh"), "Row TSV must contain customer name");
    }

    @Test
    void testClipboardCopyActions() throws Exception {
        assumeTrue(javaFxAvailable && !GraphicsEnvironment.isHeadless(),
                "Active graphical display is required for native clipboard copy tests");

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean copyCellResult = new AtomicBoolean();
        AtomicBoolean copyRowResult = new AtomicBoolean();

        Platform.runLater(() -> {
            try {
                TableView<Sales> table = new TableView<>();
                TableColumn<Sales, String> colInvoice = new TableColumn<>("Invoice No.");
                colInvoice.setCellValueFactory(v -> new SimpleStringProperty(v.getValue().getInvoiceNo()));

                TableColumn<Sales, Double> colTotal = new TableColumn<>("Amount");
                colTotal.setCellValueFactory(v -> new SimpleDoubleProperty(v.getValue().getTotalAmount()).asObject());

                table.getColumns().addAll(colInvoice, colTotal);

                Sales sale = new Sales();
                sale.setInvoiceNo("IN/17-11-2026/0001");
                sale.setTotalAmount(353705.00);
                table.setItems(FXCollections.observableArrayList(sale));

                TableClipboardSupport.install(table);

                table.getSelectionModel().select(0);
                table.getProperties().put("erp.table.clicked.row", 0);
                table.getProperties().put("erp.table.clicked.col", colInvoice);

                boolean cOk = TableClipboardSupport.copyFocusedCell(table);
                copyCellResult.set(cOk);

                boolean rOk = TableClipboardSupport.copySelectedRows(table);
                copyRowResult.set(rOk);
            } catch (Throwable t) {
                t.printStackTrace();
            } finally {
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "JavaFX thread timed out");
        assertTrue(copyCellResult.get(), "copyFocusedCell must return true");
        assertTrue(copyRowResult.get(), "copySelectedRows must return true");
    }
}
