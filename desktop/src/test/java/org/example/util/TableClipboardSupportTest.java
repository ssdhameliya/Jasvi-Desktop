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

import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TableClipboardSupportTest {

    @BeforeAll
    static void initJavaFx() {
        try {
            CountDownLatch latch = new CountDownLatch(1);
            Platform.startup(latch::countDown);
            latch.await(10, TimeUnit.SECONDS);
        } catch (IllegalStateException ignored) {
            // Already started
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void testCopyTextAndExtraction() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> extractedCell = new AtomicReference<>();
        AtomicReference<String> extractedRow = new AtomicReference<>();
        AtomicBoolean copyCellResult = new AtomicBoolean();
        AtomicBoolean copyRowResult = new AtomicBoolean();

        Platform.runLater(() -> {
            try {
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

                TableClipboardSupport.install(table);

                // Test extractCellText
                String cellVal = TableClipboardSupport.extractCellText(null, colInvoice, 0, table);
                extractedCell.set(cellVal);

                // Test extractRowTsv
                String rowVal = TableClipboardSupport.extractRowTsv(table, 0);
                extractedRow.set(rowVal);

                // Select row 0 and test copy
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

        assertTrue(latch.await(15, TimeUnit.SECONDS), "JavaFX thread timed out");

        assertEquals("IN/17-11-2026/0001", extractedCell.get(), "Cell value must match invoice number");
        assertTrue(extractedRow.get().contains("IN/17-11-2026/0001"), "Row TSV must contain invoice number");
        assertTrue(extractedRow.get().contains("Shailesh"), "Row TSV must contain customer name");
        assertTrue(copyCellResult.get(), "copyFocusedCell must return true");
        assertTrue(copyRowResult.get(), "copySelectedRows must return true");
    }
}
