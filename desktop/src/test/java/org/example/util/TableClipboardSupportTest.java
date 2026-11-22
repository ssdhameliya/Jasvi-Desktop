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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
            Platform.startup(() -> {
                try {
                    Platform.setImplicitExit(false);
                } catch (Throwable ignored) {}
                latch.countDown();
            });
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
    void testCopyTextBlankValidation() {
        assertFalse(TableClipboardSupport.copyText(null));
        assertFalse(TableClipboardSupport.copyText(""));
        assertFalse(TableClipboardSupport.copyText("   \t\n "));
    }

    @Test
    void testFormatValue() {
        assertEquals("123.45", TableClipboardSupport.formatValue(123.45));
        assertEquals("100.50", TableClipboardSupport.formatValue(new BigDecimal("100.50")));
        assertEquals("", TableClipboardSupport.formatValue(null));
        assertEquals("test", TableClipboardSupport.formatValue("test"));
        assertEquals(BusinessClock.formatDate(LocalDate.of(2026, 11, 17)),
                TableClipboardSupport.formatValue(LocalDate.of(2026, 11, 17)));
    }

    @Test
    void testIsIgnoredColumn() {
        assertTrue(TableClipboardSupport.isIgnoredColumn(null));
        if (javaFxAvailable) {
            TableColumn<Object, String> actionCol = new TableColumn<>("Actions");
            assertTrue(TableClipboardSupport.isIgnoredColumn(actionCol));

            TableColumn<Object, String> dataCol = new TableColumn<>("Invoice No.");
            assertFalse(TableClipboardSupport.isIgnoredColumn(dataCol));
        }
    }

    @Test
    void testExtractCellTextAndRowTsv() {
        assumeTrue(javaFxAvailable, "Active JavaFX toolkit required for TableView extraction");

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
    void testTableClipboardSupportInstall() {
        assumeTrue(javaFxAvailable, "Active JavaFX toolkit required for TableClipboardSupport install");

        TableView<Sales> table = new TableView<>();
        assertNull(table.getProperties().get("erp.table.clipboard.installed"));

        TableClipboardSupport.install(table);

        assertEquals(true, table.getProperties().get("erp.table.clipboard.installed"));
        assertNotNull(table.getContextMenu(), "Context menu should be installed on TableView");
    }
}
