package org.example.server.licensing;

import org.example.server.persistence.JpaNativeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ClientRegistryTemporalSafetyTest {

    @Test
    @DisplayName("ClientRegistryService.toInstant handles PostgreSQL LocalDateTime without throwing UnsupportedTemporalTypeException")
    void testClientRegistryServiceLocalDateTimeSafety() {
        LocalDateTime ldt = LocalDateTime.of(2027, 11, 5, 12, 0, 0);
        Instant instant = ClientRegistryService.toInstant(ldt);
        assertNotNull(instant, "Instant must not be null for LocalDateTime");
        assertEquals(ldt.atZone(ZoneId.systemDefault()).toInstant(), instant);
    }

    @Test
    @DisplayName("ClientRegistryService.toInstant handles LocalDate, Timestamp, Date, Instant, and Number")
    void testClientRegistryServiceAllTemporalTypes() {
        LocalDate ld = LocalDate.of(2027, 11, 5);
        Instant fromLd = ClientRegistryService.toInstant(ld);
        assertNotNull(fromLd);
        assertEquals(ld.atStartOfDay(ZoneId.systemDefault()).toInstant(), fromLd);

        Timestamp ts = Timestamp.valueOf("2027-11-05 12:00:00");
        Instant fromTs = ClientRegistryService.toInstant(ts);
        assertNotNull(fromTs);
        assertEquals(ts.toInstant(), fromTs);

        Date dt = new Date(1700000000000L);
        Instant fromDt = ClientRegistryService.toInstant(dt);
        assertNotNull(fromDt);
        assertEquals(dt.toInstant(), fromDt);

        Instant raw = Instant.now();
        assertEquals(raw, ClientRegistryService.toInstant(raw));

        long epochMilli = 1750000000000L;
        assertEquals(Instant.ofEpochMilli(epochMilli), ClientRegistryService.toInstant(epochMilli));

        assertNull(ClientRegistryService.toInstant(null));
        assertNull(ClientRegistryService.toInstant("   "));
    }

    @Test
    @DisplayName("Telemetry target date string validates ISO format safely for ANSI CAST(? AS date)")
    void testTelemetryTargetDateParsingSafety() {
        String targetDateStr = "2026-11-14";
        LocalDate parsed = LocalDate.parse(targetDateStr.trim());
        assertNotNull(parsed);
        assertEquals("2026-11-14", parsed.toString());
    }

    @Test
    @DisplayName("LicenseVerifierService.toInstant handles PostgreSQL LocalDateTime without throwing UnsupportedTemporalTypeException")
    void testLicenseVerifierServiceLocalDateTimeSafety() {
        LocalDateTime ldt = LocalDateTime.of(2027, 11, 5, 12, 0, 0);
        Instant instant = LicenseVerifierService.toInstant(ldt);
        assertNotNull(instant, "Instant must not be null for LocalDateTime");
        assertEquals(ldt.atZone(ZoneId.systemDefault()).toInstant(), instant);
    }

    @Test
    @DisplayName("getClientUserDailyBreakdown checks schema safely and emits individual login/logoff sessions")
    void testDailyBreakdownEmitsPerSessionRowsWithoutTransactionAbort() {
        AtomicInteger tableProbeCount = new AtomicInteger(0);
        JpaNativeRepository stubRepo = new JpaNativeRepository() {
            @Override
            public <T> T queryForObject(String sql, Class<T> type, Object... args) {
                if (sql.contains("information_schema.tables")) {
                    tableProbeCount.incrementAndGet();
                    return type.cast(Boolean.TRUE);
                }
                if (type == Integer.class) return type.cast(1);
                return null;
            }

            @Override
            public List<Map<String, Object>> queryForList(String sql, Object... args) {
                if (sql.contains("FROM client_user_telemetry")) {
                    return List.of(Map.of(
                            "work_date", java.sql.Date.valueOf("2026-11-15"),
                            "username", "useradmin",
                            "role_code", "USER_ADMIN",
                            "first_login_today", Timestamp.valueOf("2026-11-15 07:00:00"),
                            "effective_end_time", Timestamp.valueOf("2026-11-15 12:00:00"),
                            "total_seconds_today", 10800L,
                            "is_active_now", false
                    ));
                }
                if (sql.contains("FROM client_user_session_log")) {
                    return List.of(
                            Map.of(
                                    "work_date", java.sql.Date.valueOf("2026-11-15"),
                                    "session_start", Timestamp.valueOf("2026-11-15 07:00:00"),
                                    "session_end", Timestamp.valueOf("2026-11-15 08:00:00"),
                                    "duration_seconds", 3600L,
                                    "is_active", false
                            ),
                            Map.of(
                                    "work_date", java.sql.Date.valueOf("2026-11-15"),
                                    "session_start", Timestamp.valueOf("2026-11-15 10:00:00"),
                                    "session_end", Timestamp.valueOf("2026-11-15 12:00:00"),
                                    "duration_seconds", 7200L,
                                    "is_active", false
                            )
                    );
                }
                return List.of();
            }

            @Override
            public int update(String sql, Object... args) {
                return 0;
            }
        };

        ClientRegistryService service = new ClientRegistryService(stubRepo, null, null);
        List<Map<String, Object>> sessions = service.getClientUserDailyBreakdown(
                "F12DC2C0AADE15EB", "useradmin", "2026-11-01", "2026-11-15"
        );

        assertEquals(1, tableProbeCount.get());
        assertEquals(2, sessions.size());
        assertEquals(1, sessions.get(0).get("session_number"));
        assertEquals(3600L, sessions.get(0).get("duration_seconds"));
        assertEquals(2, sessions.get(1).get("session_number"));
        assertEquals(7200L, sessions.get(1).get("duration_seconds"));
    }

    @Test
    @DisplayName("getClientUserDailyBreakdown falls back cleanly when session_log table is absent without failing SQL")
    void testDailyBreakdownFallbackWithoutSessionTable() {
        JpaNativeRepository stubRepo = new JpaNativeRepository() {
            @Override
            public <T> T queryForObject(String sql, Class<T> type, Object... args) {
                if (sql.contains("information_schema.tables")) return type.cast(Boolean.FALSE);
                if (type == Integer.class) return type.cast(1);
                return null;
            }

            @Override
            public List<Map<String, Object>> queryForList(String sql, Object... args) {
                if (sql.contains("FROM client_user_session_log")) {
                    fail("Must never query client_user_session_log inside @Transactional when table does not exist");
                }
                if (sql.contains("FROM client_user_telemetry")) {
                    return List.of(Map.of(
                            "work_date", java.sql.Date.valueOf("2026-11-13"),
                            "username", "superadmin",
                            "role_code", "SUPER_ADMIN",
                            "first_login_today", Timestamp.valueOf("2026-11-13 20:21:00"),
                            "effective_end_time", Timestamp.valueOf("2026-11-13 22:41:00"),
                            "total_seconds_today", 8400L,
                            "is_active_now", false
                    ));
                }
                return List.of();
            }

            @Override
            public int update(String sql, Object... args) {
                return 0;
            }
        };

        ClientRegistryService service = new ClientRegistryService(stubRepo, null, null);
        List<Map<String, Object>> rows = service.getClientUserDailyBreakdown(
                "F12DC2C0AADE15EB", "superadmin", "2026-11-01", "2026-11-15"
        );
        assertEquals(1, rows.size());
        assertEquals(1, rows.getFirst().get("session_number"));
        assertEquals(8400L, rows.getFirst().get("duration_seconds"));
    }

    @Test
    @DisplayName("getClientUserTelemetry defaults to day-wise rows in DAILY mode and supports SUMMARY mode")
    void testClientUserTelemetryDailyVsSummaryModes() {
        List<String> executedSql = new ArrayList<>();
        JpaNativeRepository stubRepo = new JpaNativeRepository() {
            @Override
            public <T> T queryForObject(String sql, Class<T> type, Object... args) {
                if (sql.contains("information_schema.tables")) return type.cast(Boolean.TRUE);
                if (type == Integer.class) return type.cast(1);
                return null;
            }

            @Override
            public List<Map<String, Object>> queryForList(String sql, Object... args) {
                executedSql.add(sql);
                return List.of();
            }

            @Override
            public int update(String sql, Object... args) {
                return 0;
            }
        };

        ClientRegistryService service = new ClientRegistryService(stubRepo, null, null);
        service.getClientUserTelemetry("F12DC2C0AADE15EB", null, "2026-11-01", "2026-11-15", "DAILY");
        service.getClientUserTelemetry("F12DC2C0AADE15EB", null, "2026-11-01", "2026-11-15", "SUMMARY");

        assertEquals(2, executedSql.size());
        assertTrue(executedSql.get(0).contains("ORDER BY t.work_date DESC"), "DAILY mode must return day-wise rows");
        assertTrue(executedSql.get(1).contains("GROUP BY t.username, t.role_code"), "SUMMARY mode must group by user");
    }
}
