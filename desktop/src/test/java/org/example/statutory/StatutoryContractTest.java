package org.example.statutory;

import org.example.shared.statutory.GstinValidator;
import org.example.shared.statutory.HsnTariffRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class StatutoryContractTest {

    @Test
    void testGstinChecksumAndStateResolution() {
        // Authentic Jasvi Industries GSTIN
        String realGstin = "24ANXPD3352N1ZK";
        var result = GstinValidator.validate(realGstin);

        assertTrue(result.valid());
        assertTrue(result.checksumValid());
        assertEquals("24", result.stateCode());
        assertEquals("Gujarat", result.stateName());
        assertEquals("ANXPD3352N", result.pan());
        assertEquals("P", result.entityType());
        assertEquals('K', result.expectedChecksum());

        // Test with checksum typo (e.g. typing '5' instead of 'K')
        String typoGstin = "24ANXPD3352N1Z5";
        var typoResult = GstinValidator.validate(typoGstin);
        assertFalse(typoResult.valid());
        assertFalse(typoResult.checksumValid());
        assertEquals('K', typoResult.expectedChecksum());
        assertTrue(typoResult.message().contains("Luhn Checksum mismatch"));
    }

    @Test
    void testHsnTariffLookupAndRateMapping() {
        // Test 8-digit exact match
        var match8 = HsnTariffRegistry.lookup("73063000");
        assertNotNull(match8);
        assertEquals(18.0, match8.gstRate());
        assertTrue(match8.description().contains("tubes, pipes"));

        // Test 4-digit computer heading
        var match4 = HsnTariffRegistry.lookup("8471");
        assertNotNull(match4);
        assertEquals(18.0, match4.gstRate());
        assertTrue(match4.description().contains("data processing"));

        // Test service SAC
        var serviceMatch = HsnTariffRegistry.lookup("9965");
        assertNotNull(serviceMatch);
        assertEquals(5.0, serviceMatch.gstRate());

        // Test format validator
        assertTrue(HsnTariffRegistry.isValidHsnFormat("8471"));
        assertTrue(HsnTariffRegistry.isValidHsnFormat("730630"));
        assertTrue(HsnTariffRegistry.isValidHsnFormat("73063000"));
        assertFalse(HsnTariffRegistry.isValidHsnFormat("84"));
        assertFalse(HsnTariffRegistry.isValidHsnFormat("12345"));
    }
}
