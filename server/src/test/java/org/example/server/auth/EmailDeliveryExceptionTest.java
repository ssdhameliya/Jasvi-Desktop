package org.example.server.auth;

import jakarta.mail.AuthenticationFailedException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EmailDeliveryExceptionTest {
    @Test
    void gmail535IsSanitizedForRegistrationButActionableForAdministrator() {
        var failure = EmailDeliveryException.verification(
                new AuthenticationFailedException("535 5.7.8 Username and Password not accepted. BadCredentials"));
        assertEquals("Verification email is temporarily unavailable. Please contact your administrator or try again later.",
                failure.getMessage());
        assertFalse(failure.getMessage().contains("535"));
        assertFalse(failure.getMessage().toLowerCase().contains("password not accepted"));
        assertTrue(failure.adminMessage().contains("SMTP authentication failed"));
        assertTrue(failure.adminMessage().contains("Google App Password"));
    }

    @Test
    void parseRecipientAddressesSupportsMultipleDeduplicatedRecipients() throws Exception {
        var parsed = SmtpMailService.parseRecipientAddresses("accounts@example.com; manager@example.com, ACCOUNTS@example.com ; ");
        assertEquals(2, parsed.length);
        assertEquals("accounts@example.com", parsed[0].getAddress());
        assertEquals("manager@example.com", parsed[1].getAddress());
        assertThrows(jakarta.mail.internet.AddressException.class,
                () -> SmtpMailService.parseRecipientAddresses("accounts@example.com; invalid address"));
    }
}
