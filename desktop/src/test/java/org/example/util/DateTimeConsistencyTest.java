package org.example.util;

import javafx.application.Platform;
import javafx.scene.control.DatePicker;
import org.example.config.ConfigManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class DateTimeConsistencyTest {

    private static boolean javaFxAvailable = false;

    @BeforeAll
    static void initJavaFx() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
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

    @AfterEach
    void resetConfig() {
        ConfigManager.applyServerBusinessPolicy("Asia/Kolkata", "dd/MM/yyyy", "hh:mm a");
    }

    @Test
    void testDateFormattingMatchesConfiguredPattern() {
        ConfigManager.applyServerBusinessPolicy("Asia/Kolkata", "dd/MM/yyyy", "hh:mm a");
        LocalDate date = LocalDate.of(2026, 10, 2);
        assertEquals("02/10/2026", BusinessClock.formatDate(date));

        ConfigManager.applyServerBusinessPolicy("Asia/Kolkata", "yyyy-MM-dd", "hh:mm a");
        assertEquals("2026-10-02", BusinessClock.formatDate(date));

        ConfigManager.applyServerBusinessPolicy("Asia/Kolkata", "dd-MM-yyyy", "hh:mm a");
        assertEquals("02-10-2026", BusinessClock.formatDate(date));

        ConfigManager.applyServerBusinessPolicy("Asia/Kolkata", "MM/dd/yyyy", "hh:mm a");
        assertEquals("10/02/2026", BusinessClock.formatDate(date));
    }

    @Test
    void testTimestampFormattingMatchesConfiguredPattern() {
        ConfigManager.applyServerBusinessPolicy("Asia/Kolkata", "dd/MM/yyyy", "hh:mm a");
        Instant instant = LocalDate.of(2026, 10, 2).atTime(15, 30, 0).atZone(ZoneId.of("Asia/Kolkata")).toInstant();
        String formatted = BusinessClock.formatTimestamp(instant);
        assertTrue(formatted.startsWith("02/10/2026"), "Formatted date should be dd/MM/yyyy: " + formatted);
        assertTrue(formatted.contains("03:30") || formatted.contains("3:30"), "Time should be 12-hour: " + formatted);
        assertTrue(formatted.toUpperCase().contains("PM"), "Time should have PM: " + formatted);

        ConfigManager.applyServerBusinessPolicy("Asia/Kolkata", "yyyy-MM-dd", "HH:mm:ss");
        String formatted24 = BusinessClock.formatTimestamp(instant);
        assertTrue(formatted24.startsWith("2026-10-02"), "Formatted date should be yyyy-MM-dd: " + formatted24);
        assertTrue(formatted24.contains("15:30:00"), "Time should be 24-hour with seconds: " + formatted24);
    }

    @Test
    void testDatePickerFormatterConverter() {
        if (!javaFxAvailable) return;

        ConfigManager.applyServerBusinessPolicy("Asia/Kolkata", "dd/MM/yyyy", "hh:mm a");
        DatePicker picker = new DatePicker();
        DatePickerFormatter.attach(picker);

        LocalDate date = LocalDate.of(2026, 10, 2);
        String text = picker.getConverter().toString(date);
        assertEquals("02/10/2026", text);

        LocalDate parsed = picker.getConverter().fromString("02/10/2026");
        assertEquals(date, parsed);

        // Dynamic change
        ConfigManager.applyServerBusinessPolicy("Asia/Kolkata", "yyyy-MM-dd", "hh:mm a");
        assertEquals("2026-10-02", picker.getConverter().toString(date));
        assertEquals(date, picker.getConverter().fromString("2026-10-02"));
    }

    @Test
    void testDatePickerRealTimeEditorRefresh() throws Exception {
        if (!javaFxAvailable) return;

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ConfigManager.applyServerBusinessPolicy("Asia/Kolkata", "dd/MM/yyyy", "hh:mm a");
                DatePicker picker = new DatePicker();
                DatePickerFormatter.attach(picker);

                LocalDate date = LocalDate.of(2026, 10, 2);
                picker.setValue(date);
                DatePickerFormatter.refresh(picker);
                assertEquals("02/10/2026", picker.getEditor().getText());

                // When business policy changes to yyyy-MM-dd, DatePickerFormatter.refreshAll() is triggered
                ConfigManager.applyServerBusinessPolicy("Asia/Kolkata", "yyyy-MM-dd", "hh:mm a");
                assertEquals("2026-10-02", picker.getEditor().getText());

                // And when changed to dd-MM-yyyy
                ConfigManager.applyServerBusinessPolicy("Asia/Kolkata", "dd-MM-yyyy", "hh:mm a");
                assertEquals("02-10-2026", picker.getEditor().getText());
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    void testSyncRuntimeBusinessPolicy() {
        ConfigManager.syncRuntimeBusinessPolicy("company.dateFormat", "dd-MM-yyyy");
        ConfigManager.syncRuntimeBusinessPolicy("company.timeZone", "Asia/Kolkata");
        ConfigManager.syncRuntimeBusinessPolicy("company.timeFormat", "HH:mm");

        assertEquals("dd-MM-yyyy", BusinessClock.datePattern());
        assertEquals("HH:mm", BusinessClock.timePattern());
        assertEquals(ZoneId.of("Asia/Kolkata"), BusinessClock.zone());

        LocalDate date = LocalDate.of(2026, 10, 2);
        assertEquals("02-10-2026", BusinessClock.formatDate(date));
    }

    @Test
    void testSmartCompactDigitDateParsingAcrossAllSettingsFormats() {
        LocalDate expected = LocalDate.of(2026, 11, 18);

        // Under YYYY-MM-DD setting, 18112026, 11182026, 20261118, 181126, 111826, and dot/space variants all resolve cleanly
        ConfigManager.applyServerBusinessPolicy("Asia/Kolkata", "yyyy-MM-dd", "hh:mm a");
        assertEquals(expected, BusinessClock.parseDate("18112026"));
        assertEquals(expected, BusinessClock.parseDate("11182026"));
        assertEquals(expected, BusinessClock.parseDate("20261118"));
        assertEquals(expected, BusinessClock.parseDate("181126"));
        assertEquals(expected, BusinessClock.parseDate("111826"));
        assertEquals(expected, BusinessClock.parseDate("18.11.2026"));
        assertEquals(expected, BusinessClock.parseDate("18 11 2026"));
        assertEquals("2026-11-18", BusinessClock.formatDate(BusinessClock.parseDate("18112026")));
        assertEquals("2026-11-18", BusinessClock.formatDate(BusinessClock.parseDate("11182026")));

        // Under DD/MM/YYYY setting, ambiguous 05062026 resolves day-first (5 June 2026), while 11182026 still falls back to 18 Nov 2026
        ConfigManager.applyServerBusinessPolicy("Asia/Kolkata", "dd/MM/yyyy", "hh:mm a");
        assertEquals(LocalDate.of(2026, 6, 5), BusinessClock.parseDate("05062026"));
        assertEquals(expected, BusinessClock.parseDate("18112026"));
        assertEquals(expected, BusinessClock.parseDate("11182026"));
        assertEquals(expected, BusinessClock.parseDate("20261118"));
        assertEquals("18/11/2026", BusinessClock.formatDate(BusinessClock.parseDate("11182026")));

        // Under MM/DD/YYYY setting, ambiguous 05062026 resolves month-first (6 May 2026), while 18112026 still falls back to 18 Nov 2026
        ConfigManager.applyServerBusinessPolicy("Asia/Kolkata", "MM/dd/yyyy", "hh:mm a");
        assertEquals(LocalDate.of(2026, 5, 6), BusinessClock.parseDate("05062026"));
        assertEquals(expected, BusinessClock.parseDate("11182026"));
        assertEquals(expected, BusinessClock.parseDate("18112026"));
        assertEquals("11/18/2026", BusinessClock.formatDate(BusinessClock.parseDate("18112026")));

        // Strict calendar validation rejects impossible dates instead of rolling over
        assertNull(BusinessClock.parseDate("31022026"));
        assertNull(BusinessClock.parseCompactEightDigitDate("31022026"));
        assertNull(BusinessClock.parseCompactEightDigitDate("181126"));
    }

    @Test
    void testDatePickerAutoFormatsEightDigitInputAndPreservesValueOnInvalidInput() throws Exception {
        if (!javaFxAvailable) return;

        CountDownLatch latch = new CountDownLatch(1);
        final Throwable[] failure = new Throwable[1];
        Platform.runLater(() -> {
            try {
                ConfigManager.applyServerBusinessPolicy("Asia/Kolkata", "yyyy-MM-dd", "hh:mm a");
                DatePicker picker = DatePickerFormatter.attach(new DatePicker());

                // 1) Typing 18112026 -> 2026-11-18 via commit
                picker.getEditor().setText("18112026");
                DatePickerFormatter.commitEditorText(picker);
                assertEquals(LocalDate.of(2026, 11, 18), picker.getValue());
                assertEquals("2026-11-18", picker.getEditor().getText());

                // 2) Typing 11182026 -> 2026-11-18 via commit
                picker.setValue(null);
                picker.getEditor().setText("11182026");
                DatePickerFormatter.commitEditorText(picker);
                assertEquals(LocalDate.of(2026, 11, 18), picker.getValue());
                assertEquals("2026-11-18", picker.getEditor().getText());

                // 3) Invalid date preserves previous valid value instead of wiping to null
                picker.getEditor().setText("31022026");
                DatePickerFormatter.commitEditorText(picker);
                assertEquals(LocalDate.of(2026, 11, 18), picker.getValue());
                assertEquals("2026-11-18", picker.getEditor().getText());

                // 4) Empty string cleanly clears the DatePicker
                picker.getEditor().setText("");
                DatePickerFormatter.commitEditorText(picker);
                assertNull(picker.getValue());
                assertEquals("", picker.getEditor().getText());
            } catch (Throwable t) {
                failure[0] = t;
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        if (failure[0] != null) {
            throw new AssertionError("FX thread assertion failed", failure[0]);
        }
    }
}
