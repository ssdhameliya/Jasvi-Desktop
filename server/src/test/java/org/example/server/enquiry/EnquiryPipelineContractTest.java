package org.example.server.enquiry;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class EnquiryPipelineContractTest {

    @Test
    void tokenServiceSignsAndRejectsTampering() {
        EnquiryTokenService tokens = new EnquiryTokenService("unit-test-secret-key");
        String token = tokens.generateToken(42L, "ENY/2026-01");
        assertEquals(32, token.length());
        assertTrue(tokens.verifyToken(42L, "eny/2026-01", token));
        assertFalse(tokens.verifyToken(43L, "ENY/2026-01", token));
        assertFalse(tokens.verifyToken(42L, "ENY/2026-02", token));
        assertFalse(tokens.verifyToken(42L, "ENY/2026-01", "0".repeat(32)));
    }

    @Test
    void mobileViewUrlsTargetCorrectUatAndProdHosts() {
        EnquiryNotificationDispatcher uat = new EnquiryNotificationDispatcher(
                null, null, null, "UAT",
                "http://localhost:58080", "https://api-uat.jasviindustries.in", "https://api.jasviindustries.in");
        EnquiryNotificationDispatcher prod = new EnquiryNotificationDispatcher(
                null, null, null, "PROD",
                "http://localhost:58080", "https://api-uat.jasviindustries.in", "https://api.jasviindustries.in");

        assertEquals("https://api-uat.jasviindustries.in/api/public/enquiry-view?id=9&token=abc",
                uat.buildMobileViewUrl(9L, "abc"));
        assertEquals("https://api.jasviindustries.in/api/public/enquiry-view?id=9&token=abc",
                prod.buildMobileViewUrl(9L, "abc"));
    }

    @Test
    void enquiryPipelineFollowsMasterDataLookupAndRule10Contracts() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V11_0_96__public_enquiries_register.sql"));
        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS public_enquiries"));
        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS enquiry_activity"));

        String master = Files.readString(Path.of("src/main/java/org/example/server/master/MasterDataService.java"));
        assertTrue(master.contains("ensureReferenceFormat(\"REF_ENQUIRY\""));
        assertTrue(master.contains("QTS_WEB"));

        String service = Files.readString(Path.of("src/main/java/org/example/server/enquiry/EnquiryService.java"));
        assertTrue(service.contains("operations.nextConfiguredReference"));
        assertTrue(service.contains("REF_ENQUIRY"));
        assertTrue(service.contains("\"/fxml/pages/EnquiriesList.fxml\""));

        String dispatcher = Files.readString(Path.of("src/main/java/org/example/server/enquiry/EnquiryNotificationDispatcher.java"));
        assertTrue(dispatcher.contains("TransactionPhase.AFTER_COMMIT"));
        assertTrue(dispatcher.contains("Thread.ofVirtual()"));

        String interceptor = Files.readString(Path.of("src/main/java/org/example/server/licensing/LicenseSecurityInterceptor.java"));
        assertTrue(interceptor.contains("/api/public/"));
        assertTrue(interceptor.contains("/public/"));

        String security = Files.readString(Path.of("src/main/java/org/example/server/security/SecurityConfig.java"));
        assertTrue(security.contains("https://web-uat.jasviindustries.in"));
        assertTrue(security.contains("https://jasviindustries.in"));
        assertTrue(security.contains("/api/enquiries/**"));
    }
}
