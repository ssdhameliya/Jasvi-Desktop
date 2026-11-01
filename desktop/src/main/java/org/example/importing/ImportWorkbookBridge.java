package org.example.importing;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Universal Workbook Bridge for Jasvi ERP.
 * Seamlessly transparent support for .xlsx, .xls, and .csv across all 8 ERP loaders.
 * If the source is CSV, parses lines into an in-memory XSSFWorkbook so downstream
 * POI layout detection and value reading work identically with zero regression.
 */
public final class ImportWorkbookBridge {
    private ImportWorkbookBridge() {}

    public static Workbook openWorkbook(Path path) throws Exception {
        if (path == null) throw new IllegalArgumentException("File path cannot be null");
        String name = path.getFileName().toString().toLowerCase();

        if (name.endsWith(".csv") || name.endsWith(".txt")) {
            return csvToWorkbook(path);
        }
        return WorkbookFactory.create(path.toFile());
    }

    private static Workbook csvToWorkbook(Path path) throws IOException {
        Workbook wb = new XSSFWorkbook();
        Sheet sheet = wb.createSheet("Import Data");

        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        int rowIdx = 0;
        for (String line : lines) {
            if (line == null || line.trim().isEmpty()) continue;
            List<String> cells = parseCsvLine(line);
            Row row = sheet.createRow(rowIdx++);
            for (int colIdx = 0; colIdx < cells.size(); colIdx++) {
                Cell cell = row.createCell(colIdx);
                String val = cells.get(colIdx);
                cell.setCellValue(val);
            }
        }
        return wb;
    }

    public static List<String> parseCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        if (line == null) return cells;

        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '\"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '\"') {
                    current.append('\"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if ((ch == ',' || ch == ';') && !inQuotes) {
                cells.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        cells.add(current.toString().trim());
        return cells;
    }
}
