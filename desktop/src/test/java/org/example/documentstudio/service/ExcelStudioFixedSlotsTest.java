package org.example.documentstudio.service;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.example.documentstudio.model.TemplateData;
import org.example.invoice.model.TaxInvoiceItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExcelStudioFixedSlotsTest {

    @Test
    @DisplayName("Zero charges: Slot 1 & 2 populate from master fallback with 0.00 and zero row shift")
    void zeroChargesPopulatesMasterSlotsWithZero() throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Invoice");
            Row bankAndCharge1 = sheet.createRow(0);
            bankAndCharge1.createCell(0).setCellValue("BANK: KOTAK");
            bankAndCharge1.createCell(1).setCellValue("{{charge[1].name}}");
            bankAndCharge1.createCell(2).setCellValue("{{charge[1].amount}}");

            Row bankAndCharge2 = sheet.createRow(1);
            bankAndCharge2.createCell(0).setCellValue("BRANCH: NIKOL");
            bankAndCharge2.createCell(1).setCellValue("{{charge[2].name}}");
            bankAndCharge2.createCell(2).setCellValue("{{charge[2].amount}}");

            TemplateData data = new TemplateData(
                    Map.of("config.masterChargeTypes", "Freight Charges,Packaging Charges,Insurance Charges"),
                    Map.of(),
                    List.of(new TaxInvoiceItem(1, "ITEM-1", "Product A", "", 1, "NOS", 1000, 0, 18)),
                    List.of(),
                    "GST"
            );

            ExcelTemplateRenderer.fillWorkbook(workbook, data, List.of());

            // Left side Bank Details must remain completely unshifted on row 0 and row 1
            assertEquals("BANK: KOTAK", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("BRANCH: NIKOL", sheet.getRow(1).getCell(0).getStringCellValue());

            // Right side Charge slots must resolve master lookups with 0.00
            assertEquals("Freight Charges", sheet.getRow(0).getCell(1).getStringCellValue());
            assertEquals(0.00, sheet.getRow(0).getCell(2).getNumericCellValue(), 0.001);
            assertEquals(CellType.NUMERIC, sheet.getRow(0).getCell(2).getCellType());

            assertEquals("Packaging Charges", sheet.getRow(1).getCell(1).getStringCellValue());
            assertEquals(0.00, sheet.getRow(1).getCell(2).getNumericCellValue(), 0.001);
            assertEquals(CellType.NUMERIC, sheet.getRow(1).getCell(2).getCellType());
        }
    }

    @Test
    @DisplayName("One charge: Slot 1 picks order charge, Slot 2 picks next master fallback with 0.00")
    void oneChargePicksOrderAndMasterFallback() throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Invoice");
            Row r0 = sheet.createRow(0);
            r0.createCell(0).setCellValue("{{charge[1].name}}");
            r0.createCell(1).setCellValue("{{charge[1].amount}}");

            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("{{charge[2].name}}");
            r1.createCell(1).setCellValue("{{charge[2].amount}}");

            TemplateData data = new TemplateData(
                    Map.of("config.masterChargeTypes", "Freight Charges,Packaging Charges,Insurance Charges"),
                    Map.of(),
                    List.of(new TaxInvoiceItem(1, "ITEM-1", "Product A", "", 1, "NOS", 1000, 0, 18)),
                    List.of(),
                    "GST"
            );

            List<ExcelTemplateRenderer.ChargeData> charges = List.of(
                    new ExcelTemplateRenderer.ChargeData("Freight Charges", 350.0, true, 18.0, 63.0, 413.0)
            );

            ExcelTemplateRenderer.fillWorkbook(workbook, data, charges);

            assertEquals("Freight Charges", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals(350.0, sheet.getRow(0).getCell(1).getNumericCellValue(), 0.001);

            // Slot 2 must pick next unused master lookup (Packaging Charges) with 0.00
            assertEquals("Packaging Charges", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals(0.00, sheet.getRow(1).getCell(1).getNumericCellValue(), 0.001);
        }
    }

    @Test
    @DisplayName("Adaptive Tax Tokens: Intra-State vs Inter-State rendering")
    void adaptiveTaxTokensIntraAndInterState() throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("TaxTest");
            Row r0 = sheet.createRow(0);
            r0.createCell(0).setCellValue("{{tax.primaryLabel}}");
            r0.createCell(1).setCellValue("{{tax.primaryAmount}}");
            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("{{tax.secondaryLabel}}");
            r1.createCell(1).setCellValue("{{tax.secondaryAmount}}");

            // Intra-State GST (CGST + SGST)
            TemplateData intraData = new TemplateData(
                    Map.of(), Map.of(),
                    List.of(new TaxInvoiceItem(1, "ITEM-1", "Product A", "", 1, "NOS", 1000, 0, 18)),
                    List.of(), "GST"
            );

            ExcelTemplateRenderer.fillWorkbook(workbook, intraData, List.of());
            assertEquals("CGST @ 9%", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals(90.0, sheet.getRow(0).getCell(1).getNumericCellValue(), 0.001);
            assertEquals("SGST @ 9%", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals(90.0, sheet.getRow(1).getCell(1).getNumericCellValue(), 0.001);
        }

        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("TaxTest");
            Row r0 = sheet.createRow(0);
            r0.createCell(0).setCellValue("{{tax.primaryLabel}}");
            r0.createCell(1).setCellValue("{{tax.primaryAmount}}");
            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("{{tax.secondaryLabel}}");
            r1.createCell(1).setCellValue("{{tax.secondaryAmount}}");

            // Inter-State IGST
            TemplateData interData = new TemplateData(
                    Map.of(), Map.of(),
                    List.of(new TaxInvoiceItem(1, "ITEM-1", "Product A", "", 1, "NOS", 1000, 0, 18)),
                    List.of(), "IGST"
            );

            ExcelTemplateRenderer.fillWorkbook(workbook, interData, List.of());
            assertEquals("IGST @ 18%", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals(180.0, sheet.getRow(0).getCell(1).getNumericCellValue(), 0.001);
            assertEquals("", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals(0.00, sheet.getRow(1).getCell(1).getNumericCellValue(), 0.001);
        }
    }
}
