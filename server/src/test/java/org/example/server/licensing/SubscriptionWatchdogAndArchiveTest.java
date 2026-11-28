package org.example.server.licensing;

import org.example.server.auth.SmtpMailService;
import org.example.server.communication.EmailOutboxService;
import org.example.server.persistence.JpaNativeRepository;
import org.example.server.whatsapp.BusinessWhatsappService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SubscriptionWatchdogAndArchiveTest {

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T instance) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(instance);
        return provider;
    }

    @Test
    void watchdogRaisesStageDeduplicatedExpirySeatAndOfflineAlerts() {
        List<String> executedSql = new ArrayList<>();
        JpaNativeRepository stubDb = new JpaNativeRepository() {
            @Override
            public List<Map<String, Object>> queryForList(String sql, Object... args) {
                executedSql.add(sql);
                if (sql.contains("FROM licensed_client_registry")) {
                    return List.of(Map.ofEntries(
                            Map.entry("id", 7L),
                            Map.entry("company_name", "Apex Castings Pvt Ltd"),
                            Map.entry("machine_id", "A1B2C3D4E5F60011"),
                            Map.entry("contact_person", "Kiran"),
                            Map.entry("phone", "9876543210"),
                            Map.entry("email", "ops@apex.example.com"),
                            Map.entry("tier", "ENTERPRISE"),
                            Map.entry("max_users", 10),
                            Map.entry("annual_fee", new BigDecimal("6500.00")),
                            Map.entry("valid_until", Timestamp.from(Instant.now().plus(7, ChronoUnit.DAYS))),
                            Map.entry("status", "ACTIVE"),
                            Map.entry("active_users_count", 9),
                            Map.entry("last_heartbeat_at", Timestamp.from(Instant.now().minus(60, ChronoUnit.HOURS))),
                            Map.entry("last_expiry_alert_stage", ""),
                            Map.entry("last_offline_alert_at", Timestamp.from(Instant.now().minus(5, ChronoUnit.DAYS)))
                    ));
                }
                return List.of();
            }

            @Override
            public <T> List<T> query(String sql, java.util.function.BiFunction<NativeRow, Integer, T> mapper, Object... args) {
                executedSql.add(sql);
                return List.of();
            }

            @Override
            public int update(String sql, Object... args) {
                executedSql.add(sql);
                return 1;
            }
        };

        EmailOutboxService outbox = mock(EmailOutboxService.class);
        SmtpMailService mail = mock(SmtpMailService.class);
        when(mail.isConfigured()).thenReturn(true);
        BusinessWhatsappService wa = mock(BusinessWhatsappService.class);
        ClientRegistryService registry = mock(ClientRegistryService.class);

        SubscriptionLifecycleScheduler scheduler = new SubscriptionLifecycleScheduler(
                stubDb, registry, providerOf(outbox), providerOf(mail), providerOf(wa));

        SubscriptionLifecycleScheduler.WatchdogSummary summary = scheduler.runWatchdog(false);

        assertEquals(1, summary.expiryAlertsSent());
        assertEquals(1, summary.seatWarningsRaised());
        assertEquals(1, summary.offlineAlertsRaised());
        assertTrue(executedSql.stream().anyMatch(s -> s.contains("SET last_expiry_alert_stage = ?")),
                "Must record expiry stage to prevent duplicate reminders");
        assertTrue(executedSql.stream().anyMatch(s -> s.contains("seat_alert_sent_at")),
                "Must record seat warning timestamp when utilization >= 90%");
        assertTrue(executedSql.stream().anyMatch(s -> s.contains("last_offline_alert_at")),
                "Must record stale heartbeat alert timestamp after >48h offline");
        verify(outbox, times(1)).sendWithOutbox(any());
    }

    @Test
    void watchdogSkipsDuplicateExpiryReminderWhenStageMatchesAlreadyNotifiedStage() {
        JpaNativeRepository stubDb = new JpaNativeRepository() {
            @Override
            public List<Map<String, Object>> queryForList(String sql, Object... args) {
                if (sql.contains("FROM licensed_client_registry")) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", 8L);
                    row.put("company_name", "Steady Corp");
                    row.put("machine_id", "B1B2C3D4E5F60022");
                    row.put("contact_person", "Neha");
                    row.put("phone", "9876543211");
                    row.put("email", "neha@steady.example.com");
                    row.put("tier", "GOLD");
                    row.put("max_users", 10);
                    row.put("annual_fee", new BigDecimal("3500.00"));
                    row.put("valid_until", Timestamp.from(Instant.now().plus(7, ChronoUnit.DAYS)));
                    row.put("status", "ACTIVE");
                    row.put("active_users_count", 3);
                    row.put("last_heartbeat_at", Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)));
                    row.put("last_expiry_alert_stage", "7_DAYS");
                    row.put("last_offline_alert_at", null);
                    row.put("seat_alert_sent_at", null);
                    return List.of(row);
                }
                return List.of();
            }

            @Override
            public <T> List<T> query(String sql, java.util.function.BiFunction<NativeRow, Integer, T> mapper, Object... args) {
                return List.of();
            }

            @Override
            public int update(String sql, Object... args) {
                return 1;
            }
        };

        EmailOutboxService outbox = mock(EmailOutboxService.class);
        SubscriptionLifecycleScheduler scheduler = new SubscriptionLifecycleScheduler(
                stubDb, mock(ClientRegistryService.class), providerOf(outbox), providerOf(null), providerOf(null));

        SubscriptionLifecycleScheduler.WatchdogSummary summary = scheduler.runWatchdog(false);

        assertEquals(0, summary.expiryAlertsSent());
        assertEquals(0, summary.seatWarningsRaised());
        assertEquals(0, summary.offlineAlertsRaised());
        verifyNoInteractions(outbox);
    }

    @Test
    void recordHeartbeatRollsUpMonthlySummaryBeforePurgingExpiredTelemetry() {
        List<String> executedSql = new ArrayList<>();
        JpaNativeRepository stubDb = new JpaNativeRepository() {
            @Override
            public List<Map<String, Object>> queryForList(String sql, Object... args) {
                executedSql.add(sql);
                return List.of();
            }

            @Override
            public <T> List<T> query(String sql, java.util.function.BiFunction<NativeRow, Integer, T> mapper, Object... args) {
                executedSql.add(sql);
                return List.of();
            }

            @Override
            public <T> T queryForObject(String sql, Class<T> type, Object... args) {
                executedSql.add(sql);
                return null;
            }

            @Override
            public int update(String sql, Object... args) {
                executedSql.add(sql);
                return 0;
            }
        };

        ClientRegistryService service = new ClientRegistryService(stubDb, null, null);
        service.recordHeartbeat(
                "AABB-CCDD-1122-3344",
                org.example.shared.RuntimeContract.appVersion(),
                1, "http://localhost", "Test Co", "127.0.0.1", List.of()
        );

        int rollupIdx = -1;
        int purgeIdx = -1;
        for (int i = 0; i < executedSql.size(); i++) {
            String sql = executedSql.get(i);
            if (rollupIdx < 0 && sql.contains("INSERT INTO client_telemetry_monthly_summary")) rollupIdx = i;
            if (purgeIdx < 0 && sql.contains("DELETE FROM client_user_telemetry")) purgeIdx = i;
        }
        assertTrue(rollupIdx >= 0, "Heartbeat must archive >30d usage into client_telemetry_monthly_summary");
        assertTrue(purgeIdx > rollupIdx, "Monthly rollup must run strictly BEFORE purging client_user_telemetry rows");
    }
}
