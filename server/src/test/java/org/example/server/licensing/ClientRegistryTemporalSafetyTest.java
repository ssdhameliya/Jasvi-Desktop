package org.example.server.licensing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.*;
import java.util.Date;

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
    @DisplayName("LicenseVerifierService.toInstant handles PostgreSQL LocalDateTime without throwing UnsupportedTemporalTypeException")
    void testLicenseVerifierServiceLocalDateTimeSafety() {
        LocalDateTime ldt = LocalDateTime.of(2027, 11, 5, 12, 0, 0);
        Instant instant = LicenseVerifierService.toInstant(ldt);
        assertNotNull(instant, "Instant must not be null for LocalDateTime");
        assertEquals(ldt.atZone(ZoneId.systemDefault()).toInstant(), instant);
    }
}
