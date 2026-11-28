package org.example.server.auth;

import org.example.server.master.RoleMasterService;
import org.example.server.persistence.JpaNativeRepository;
import org.example.server.persistence.entity.UserEntity;
import org.example.server.persistence.repository.UserRepository;
import org.example.server.security.SecurityIncidentNotifier;
import org.example.server.security.TokenService;
import org.example.server.util.BusinessClock;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.lang.reflect.Field;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminLockEscalationScenarioTest {

    @Test
    void adminThirdWrongPasswordLocksRecoverablyWithoutNotifyingSuperAdmin() throws Exception {
        Fixture f = new Fixture();
        UserEntity admin = adminUser(11, "officeadmin", "admin@example.com");
        when(f.users.findForAuthentication("officeadmin")).thenReturn(Optional.of(admin));
        when(f.passwords.matches("bad-pass", admin.getPassword())).thenReturn(false);

        AuthDtos.LoginResponse r1 = f.service.login(new AuthDtos.LoginRequest("officeadmin", "bad-pass"), "10.1.1.10");
        AuthDtos.LoginResponse r2 = f.service.login(new AuthDtos.LoginRequest("officeadmin", "bad-pass"), "10.1.1.10");
        AuthDtos.LoginResponse r3 = f.service.login(new AuthDtos.LoginRequest("officeadmin", "bad-pass"), "10.1.1.10");

        assertFalse(r1.success());
        assertFalse(r2.success());
        assertFalse(r3.success());
        assertTrue(admin.isLocked());
        assertEquals("FAILED_PASSWORD", admin.getLockReason());
        assertNull(r3.incidentTicketId(), "Recoverable 3rd attempt must not generate or expose a Super Admin ticket");
        assertTrue(r3.message().contains("Forgot Password"), "Admin should be guided to use Forgot Password");
        verify(f.tokens).revokeUser(11);
        verifyNoInteractions(f.notifier);
    }

    @Test
    void adminThirdWrongPasswordNeverRaisesSupportTicketAtLoginEvenWhenNoEmailOrDailyResetUsed() throws Exception {
        Fixture f = new Fixture();
        UserEntity adminResetToday = adminUser(12, "officeadmin", "admin@example.com");
        adminResetToday.setLastPasswordResetUtc(BusinessClock.nowUtcText());
        when(f.users.findForAuthentication("officeadmin")).thenReturn(Optional.of(adminResetToday));
        when(f.passwords.matches("bad-pass", adminResetToday.getPassword())).thenReturn(false);

        f.service.login(new AuthDtos.LoginRequest("officeadmin", "bad-pass"), "10.0.0.5");
        f.service.login(new AuthDtos.LoginRequest("officeadmin", "bad-pass"), "10.0.0.5");
        AuthDtos.LoginResponse rReset = f.service.login(new AuthDtos.LoginRequest("officeadmin", "bad-pass"), "10.0.0.5");

        assertFalse(rReset.success());
        assertTrue(adminResetToday.isLocked());
        assertEquals("FAILED_PASSWORD", adminResetToday.getLockReason());
        assertNull(rReset.incidentTicketId(), "Login failure must never raise a support ticket");

        UserEntity adminNoEmail = adminUser(13, "officeadmin2", "");
        when(f.users.findForAuthentication("officeadmin2")).thenReturn(Optional.of(adminNoEmail));
        when(f.passwords.matches("bad-pass", adminNoEmail.getPassword())).thenReturn(false);

        f.service.login(new AuthDtos.LoginRequest("officeadmin2", "bad-pass"), "10.0.0.6");
        f.service.login(new AuthDtos.LoginRequest("officeadmin2", "bad-pass"), "10.0.0.6");
        AuthDtos.LoginResponse rNoEmail = f.service.login(new AuthDtos.LoginRequest("officeadmin2", "bad-pass"), "10.0.0.6");

        assertFalse(rNoEmail.success());
        assertTrue(adminNoEmail.isLocked());
        assertNull(rNoEmail.incidentTicketId(), "Missing email login failure must never raise a support ticket");
        verifyNoInteractions(f.notifier);
    }

    @Test
    void lockedAdminFirstFailedForgotPasswordOtpEscalatesOnceToSuperAdmin() throws Exception {
        Fixture f = new Fixture();
        UserEntity admin = adminUser(14, "officeadmin", "admin@example.com");
        admin.setLocked(true);
        admin.setLockReason("FAILED_PASSWORD");
        when(f.otp.verifyWithoutConsume(AuthOtpService.Purpose.PASSWORD_RESET, "chal-otp", "000000", ""))
                .thenThrow(new IllegalArgumentException("Invalid verification code"));
        when(f.otp.challengeUser(AuthOtpService.Purpose.PASSWORD_RESET, "chal-otp")).thenReturn(14);
        when(f.users.findById(14)).thenReturn(Optional.of(admin));
        when(f.notifier.notifyAccountLockedAsync(eq(admin), eq("PERMANENT_LOCK"), anyString(), eq("10.0.0.8")))
                .thenReturn("SEC-900003");
        f.db.activeTicket = "SEC-900003";

        AuthDtos.OperationResponse first = f.service.completePasswordReset(
                new AuthDtos.PasswordResetCompleteRequest("chal-otp", "000000", "", "ValidPass1"),
                "10.0.0.8"
        );

        assertFalse(first.success());
        assertEquals("PERMANENT_LOCK", admin.getLockReason());
        assertEquals("SEC-900003", first.incidentTicketId());
        assertTrue(first.message().contains("SEC-900003"));
        verify(f.otp).invalidate(AuthOtpService.Purpose.PASSWORD_RESET, "user:14");
        verify(f.notifier, times(1)).notifyAccountLockedAsync(eq(admin), eq("PERMANENT_LOCK"), anyString(), eq("10.0.0.8"));

        // Re-submitting against an already permanently locked account reuses active ticket and avoids duplicate alerts
        AuthDtos.OperationResponse duplicate = f.service.completePasswordReset(
                new AuthDtos.PasswordResetCompleteRequest("chal-otp", "000000", "", "ValidPass1"),
                "10.0.0.8"
        );
        assertFalse(duplicate.success());
        assertEquals("SEC-900003", duplicate.incidentTicketId());
        verifyNoMoreInteractions(f.notifier);
    }

    @Test
    void lockedAdminFirstFailedForgotPasswordTotpEscalatesOnceToSuperAdmin() throws Exception {
        Fixture f = new Fixture();
        f.db.policy = "REQUIRED";
        UserEntity admin = adminUser(15, "officeadmin", "admin@example.com");
        admin.setLocked(true);
        admin.setLockReason("FAILED_PASSWORD");
        admin.setMfaEnabled(true);
        admin.setTotpSecretEnc("ENC-TOTP");
        when(f.otp.verifyWithoutConsume(AuthOtpService.Purpose.PASSWORD_RESET, "chal-totp", "123456", ""))
                .thenReturn(new AuthOtpService.Verified(15));
        when(f.users.findById(15)).thenReturn(Optional.of(admin));
        when(f.totp.verifyEncrypted("ENC-TOTP", "999999")).thenReturn(false);
        when(f.notifier.notifyAccountLockedAsync(eq(admin), eq("PERMANENT_LOCK"), anyString(), eq("10.0.0.9")))
                .thenReturn("SEC-900004");

        AuthDtos.OperationResponse res = f.service.completePasswordReset(
                new AuthDtos.PasswordResetCompleteRequest("chal-totp", "123456", "999999", "ValidPass1"),
                "10.0.0.9"
        );

        assertFalse(res.success());
        assertEquals("PERMANENT_LOCK", admin.getLockReason());
        assertEquals("SEC-900004", res.incidentTicketId());
        assertTrue(res.message().contains("SEC-900004"));
        verify(f.otp).invalidate(AuthOtpService.Purpose.PASSWORD_RESET, "user:15");
        verify(f.notifier, times(1)).notifyAccountLockedAsync(eq(admin), eq("PERMANENT_LOCK"), anyString(), eq("10.0.0.9"));
    }

    @Test
    void lockedAdminValidForgotPasswordClearsLockWithoutNotifyingSuperAdmin() throws Exception {
        Fixture f = new Fixture();
        f.db.policy = "REQUIRED";
        UserEntity admin = adminUser(16, "officeadmin", "admin@example.com");
        admin.setLocked(true);
        admin.setLockReason("FAILED_PASSWORD");
        admin.setMfaEnabled(true);
        admin.setTotpSecretEnc("ENC-TOTP");
        when(f.otp.verifyWithoutConsume(AuthOtpService.Purpose.PASSWORD_RESET, "chal-ok", "123456", ""))
                .thenReturn(new AuthOtpService.Verified(16));
        when(f.users.findById(16)).thenReturn(Optional.of(admin));
        when(f.totp.verifyEncrypted("ENC-TOTP", "654321")).thenReturn(true);
        when(f.passwords.matches("NewStrongPass1", admin.getPassword())).thenReturn(false);
        when(f.passwords.encode("NewStrongPass1")).thenReturn("$2a$12$encodedNewPassword");

        AuthDtos.OperationResponse res = f.service.completePasswordReset(
                new AuthDtos.PasswordResetCompleteRequest("chal-ok", "123456", "654321", "NewStrongPass1"),
                "10.0.0.10"
        );

        assertTrue(res.success());
        assertNull(res.incidentTicketId());
        assertFalse(admin.isLocked());
        assertEquals("NONE", admin.getLockReason());
        assertEquals("$2a$12$encodedNewPassword", admin.getPassword());
        verify(f.otp).consume(AuthOtpService.Purpose.PASSWORD_RESET, "chal-ok");
        verify(f.tokens).revokeUser(16);
        verifyNoInteractions(f.notifier);
    }

    @Test
    void adminThirdWrongLoginMfaLocksRecoverablyWithoutNotifyingSuperAdmin() throws Exception {
        Fixture f = new Fixture();
        f.db.policy = "REQUIRED";
        UserEntity admin = adminUser(17, "officeadmin", "admin@example.com");
        admin.setMfaEnabled(true);
        admin.setTotpSecretEnc("ENC-TOTP");
        when(f.totp.peekLogin("mfa-chal")).thenReturn(17);
        when(f.users.findByIdForAuthentication(17)).thenReturn(Optional.of(admin));
        when(f.roles.isActive("ADMIN")).thenReturn(true);
        when(f.totp.verifyEncrypted("ENC-TOTP", "000000")).thenReturn(false);

        f.service.completeLoginMfa(new AuthDtos.LoginMfaCompleteRequest("mfa-chal", "000000"), "10.0.0.11");
        f.service.completeLoginMfa(new AuthDtos.LoginMfaCompleteRequest("mfa-chal", "000000"), "10.0.0.11");
        AuthDtos.LoginResponse r3 = f.service.completeLoginMfa(new AuthDtos.LoginMfaCompleteRequest("mfa-chal", "000000"), "10.0.0.11");

        assertFalse(r3.success());
        assertTrue(admin.isLocked());
        assertEquals("FAILED_MFA", admin.getLockReason());
        assertNull(r3.incidentTicketId());
        verify(f.otp).invalidate(AuthOtpService.Purpose.LOGIN_MFA, "user:17");
        verifyNoInteractions(f.notifier);
    }

    @Test
    void accountStatusOnlyExposesActiveTicketForEscalatedLocks() throws Exception {
        Fixture f = new Fixture();
        UserEntity recoverable = adminUser(18, "recadmin", "rec@example.com");
        recoverable.setLocked(true);
        recoverable.setLockReason("FAILED_PASSWORD");
        f.db.activeTicket = "SEC-OLD-111111";
        when(f.users.findActiveByIdentityIncludingLocked("recadmin")).thenReturn(Optional.of(recoverable));

        Map<String, Object> recoverableStatus = f.service.getAccountStatus("recadmin");
        assertEquals(true, recoverableStatus.get("locked"));
        assertEquals("FAILED_PASSWORD", recoverableStatus.get("lockReason"));
        assertEquals("", recoverableStatus.get("ticketId"));

        UserEntity permanent = adminUser(19, "permadmin", "perm@example.com");
        permanent.setLocked(true);
        permanent.setLockReason("PERMANENT_LOCK");
        f.db.activeTicket = "SEC-900099";
        when(f.users.findActiveByIdentityIncludingLocked("permadmin")).thenReturn(Optional.of(permanent));

        Map<String, Object> permanentStatus = f.service.getAccountStatus("permadmin");
        assertEquals("PERMANENT_LOCK", permanentStatus.get("lockReason"));
        assertEquals("SEC-900099", permanentStatus.get("ticketId"));
        assertTrue(f.db.checkedActiveOnly, "Ticket lookup must filter to unused unexpired tokens");
    }

    @Test
    void springTransactionalProxiesCommitPermanentLockWithoutUnexpectedRollbackOnWrongForgotPasswordOtp() throws Exception {
        RecordingTxManager txManager = new RecordingTxManager();
        ScenarioDb db = new ScenarioDb();
        db.challengeRow = Map.of(
                "challenge_id", "chal-real-tx",
                "purpose", "PASSWORD_RESET",
                "challenge_key", "user:20",
                "binding_hash", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                "user_id", 20,
                "salt_base64", Base64.getEncoder().encodeToString(new byte[16]),
                "code_hash", "0000000000000000000000000000000000000000000000000000000000000000",
                "attempts", 0
        );
        UserRepository users = mock(UserRepository.class);
        RoleMasterService roles = mock(RoleMasterService.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        TokenService tokens = mock(TokenService.class);
        SmtpMailService mail = mock(SmtpMailService.class);
        TotpService totp = mock(TotpService.class);
        RegistrationCaptchaService captcha = mock(RegistrationCaptchaService.class);
        SecurityIncidentNotifier notifier = mock(SecurityIncidentNotifier.class);

        UserEntity admin = adminUser(20, "Shailesh", "shailesh@example.com");
        admin.setLocked(true);
        admin.setLockReason("FAILED_PASSWORD");
        when(users.findById(20)).thenReturn(Optional.of(admin));
        when(notifier.notifyAccountLockedAsync(eq(admin), eq("PERMANENT_LOCK"), anyString(), eq("127.0.0.1")))
                .thenReturn("SEC-TX0001");

        try (var ctx = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            ctx.registerBean(PlatformTransactionManager.class, () -> txManager);
            ctx.registerBean(TxConfig.class);
            ctx.registerBean(AuthOtpService.class, () -> new AuthOtpService(db));
            ctx.registerBean(AuthService.class, () -> new AuthService(
                    users, roles, passwords, tokens, ctx.getBean(AuthOtpService.class), mail, db, totp, captcha, notifier));
            ctx.refresh();

            AuthService proxiedAuth = ctx.getBean(AuthService.class);
            AuthDtos.OperationResponse res = proxiedAuth.completePasswordReset(
                    new AuthDtos.PasswordResetCompleteRequest("chal-real-tx", "565656", "", "Jsk@9099J"),
                    "127.0.0.1");

            assertFalse(res.success());
            assertEquals("PERMANENT_LOCK", admin.getLockReason());
            assertEquals("SEC-TX0001", res.incidentTicketId());
            assertTrue(txManager.committedCount >= 1, "Outer transaction must commit rather than rollback-only");
            assertEquals(0, txManager.rolledBackCount, "Wrong OTP inside caught handler must not mark shared TX rollback-only");
            verify(notifier, times(1)).notifyAccountLockedAsync(eq(admin), eq("PERMANENT_LOCK"), anyString(), eq("127.0.0.1"));
        }
    }

    @Test
    void springTransactionalProxiesCommitLoginMfaFailureWithoutUnexpectedRollback() throws Exception {
        RecordingTxManager txManager = new RecordingTxManager();
        ScenarioDb db = new ScenarioDb();
        db.policy = "REQUIRED";
        db.challengeRow = Map.of(
                "challenge_id", "mfa-real-tx",
                "purpose", "LOGIN_MFA",
                "challenge_key", "user:21",
                "binding_hash", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                "user_id", 21,
                "salt_base64", Base64.getEncoder().encodeToString(new byte[16]),
                "code_hash", "0000000000000000000000000000000000000000000000000000000000000000",
                "attempts", 0
        );
        UserRepository users = mock(UserRepository.class);
        RoleMasterService roles = mock(RoleMasterService.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        TokenService tokens = mock(TokenService.class);
        SmtpMailService mail = mock(SmtpMailService.class);
        TotpService totp = mock(TotpService.class);
        RegistrationCaptchaService captcha = mock(RegistrationCaptchaService.class);
        SecurityIncidentNotifier notifier = mock(SecurityIncidentNotifier.class);

        UserEntity admin = adminUser(21, "officeadmin", "admin@example.com");
        admin.setMfaEnabled(true);
        when(totp.peekLogin("mfa-real-tx")).thenReturn(null);
        when(users.findByIdForAuthentication(21)).thenReturn(Optional.of(admin));
        when(roles.isActive("ADMIN")).thenReturn(true);

        try (var ctx = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            ctx.registerBean(PlatformTransactionManager.class, () -> txManager);
            ctx.registerBean(TxConfig.class);
            ctx.registerBean(AuthOtpService.class, () -> new AuthOtpService(db));
            ctx.registerBean(AuthService.class, () -> new AuthService(
                    users, roles, passwords, tokens, ctx.getBean(AuthOtpService.class), mail, db, totp, captcha, notifier));
            ctx.refresh();

            AuthService proxiedAuth = ctx.getBean(AuthService.class);
            AuthDtos.LoginResponse res = proxiedAuth.completeLoginMfa(
                    new AuthDtos.LoginMfaCompleteRequest("mfa-real-tx", "565656"), "127.0.0.1");

            assertFalse(res.success());
            assertEquals(1, admin.getMfaFailedAttempts());
            assertTrue(txManager.committedCount >= 1);
            assertEquals(0, txManager.rolledBackCount);
        }
    }

    @Test
    void requestPasswordResetReportsTotpRequiredDynamicallyAndUnlockedAdminResetFailureRaisesTicket() throws Exception {
        Fixture f = new Fixture();
        UserEntity admin = adminUser(31, "admin31", "a31@example.com");
        admin.setTotpSecretEnc("ENC-A31");
        when(f.users.findActiveByIdentityIncludingLocked("admin31")).thenReturn(Optional.of(admin));
        when(f.otp.issue(eq(AuthOtpService.Purpose.PASSWORD_RESET), eq("user:31"), eq(""), eq(31), eq("a31@example.com"), eq(f.mail)))
                .thenReturn(new AuthOtpService.Issued("chal-31", true));

        // 1. ADMIN_CONTROLLED -> Admin exempted -> totpRequired=false
        f.db.policy = "ADMIN_CONTROLLED";
        AuthDtos.ChallengeResponse controlledResp = f.service.requestPasswordReset(new AuthDtos.PasswordResetOtpRequest("admin31"));
        assertTrue(controlledResp.success());
        assertFalse(controlledResp.totpRequired(), "Exempted ADMIN under ADMIN_CONTROLLED must disable TOTP on Forgot Password screen");

        // 2. REQUIRED -> Admin has MFA on + active secret -> totpRequired=true
        f.db.policy = "REQUIRED";
        AuthDtos.ChallengeResponse requiredResp = f.service.requestPasswordReset(new AuthDtos.PasswordResetOtpRequest("admin31"));
        assertTrue(requiredResp.success());
        assertTrue(requiredResp.totpRequired(), "ADMIN with active MFA under REQUIRED must enable TOTP field");

        // 3. Unlocked ADMIN failing reset OTP 3 times raises SEC ticket on 3rd failure
        when(f.otp.verifyWithoutConsume(AuthOtpService.Purpose.PASSWORD_RESET, "chal-31", "000000", ""))
                .thenThrow(new IllegalArgumentException("Invalid verification code"));
        when(f.otp.challengeUser(AuthOtpService.Purpose.PASSWORD_RESET, "chal-31")).thenReturn(31);
        when(f.users.findById(31)).thenReturn(Optional.of(admin));
        when(f.notifier.notifyAccountLockedAsync(eq(admin), eq("PERMANENT_LOCK"), anyString(), eq("10.0.0.21")))
                .thenReturn("SEC-UNLOCKED-3");

        var r1 = f.service.completePasswordReset(new AuthDtos.PasswordResetCompleteRequest("chal-31", "000000", "", "ValidPass1"), "10.0.0.21");
        var r2 = f.service.completePasswordReset(new AuthDtos.PasswordResetCompleteRequest("chal-31", "000000", "", "ValidPass1"), "10.0.0.21");
        assertNull(r1.incidentTicketId());
        assertNull(r2.incidentTicketId());
        var r3 = f.service.completePasswordReset(new AuthDtos.PasswordResetCompleteRequest("chal-31", "000000", "", "ValidPass1"), "10.0.0.21");
        assertEquals("SEC-UNLOCKED-3", r3.incidentTicketId());
        assertEquals("PERMANENT_LOCK", admin.getLockReason());
        verify(f.notifier, times(1)).notifyAccountLockedAsync(eq(admin), eq("PERMANENT_LOCK"), anyString(), eq("10.0.0.21"));
    }

    @EnableTransactionManagement
    static class TxConfig {}

    private static final class RecordingTxManager extends AbstractPlatformTransactionManager
            implements org.springframework.transaction.support.SmartTransactionObject {
        private boolean active;
        private boolean rollbackOnly;
        int committedCount;
        int rolledBackCount;

        @Override
        protected Object doGetTransaction() {
            return this;
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return active;
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            active = true;
            rollbackOnly = false;
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            active = false;
            rollbackOnly = false;
            committedCount++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            active = false;
            rollbackOnly = false;
            rolledBackCount++;
        }

        @Override
        protected void doSetRollbackOnly(DefaultTransactionStatus status) {
            rollbackOnly = true;
        }

        @Override
        public boolean isRollbackOnly() {
            return rollbackOnly;
        }
    }

    private static UserEntity adminUser(int id, String username, String email) throws Exception {
        UserEntity user = new UserEntity();
        Field idField = UserEntity.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(user, id);
        user.setUsername(username);
        user.setPassword("$2a$12$test-bcrypt-placeholder");
        user.setFullName(username);
        user.setEmail(email);
        user.setRole("ADMIN");
        user.setActive(true);
        user.setLocked(false);
        user.setApprovalStatus("APPROVED");
        user.setAccessLevel("FULL");
        return user;
    }

    private static final class Fixture {
        final UserRepository users = mock(UserRepository.class);
        final RoleMasterService roles = mock(RoleMasterService.class);
        final PasswordEncoder passwords = mock(PasswordEncoder.class);
        final TokenService tokens = mock(TokenService.class);
        final AuthOtpService otp = mock(AuthOtpService.class);
        final SmtpMailService mail = mock(SmtpMailService.class);
        final TotpService totp = mock(TotpService.class);
        final RegistrationCaptchaService captcha = mock(RegistrationCaptchaService.class);
        final SecurityIncidentNotifier notifier = mock(SecurityIncidentNotifier.class);
        final ScenarioDb db = new ScenarioDb();
        final AuthService service = new AuthService(users, roles, passwords, tokens, otp, mail, db, totp, captcha, notifier);
    }

    private static final class ScenarioDb extends JpaNativeRepository {
        String activeTicket;
        boolean checkedActiveOnly;
        Map<String, Object> challengeRow;
        String policy = "ADMIN_CONTROLLED";
        String superAdminMfa = "NO";

        @Override
        public int update(String sql, Object... args) {
            if ((sql.contains("DELETE FROM auth_challenge WHERE challenge_id=?")
                    || sql.contains("DELETE FROM auth_challenge WHERE purpose=? AND challenge_key=?"))
                    && challengeRow != null) {
                challengeRow = null;
            }
            return 1;
        }

        @Override
        public <T> List<T> query(String sql, java.util.function.BiFunction<JpaNativeRepository.NativeRow, Integer, T> mapper, Object... args) {
            return List.of();
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            if (sql.contains("FROM auth_challenge")) {
                return challengeRow == null ? List.of() : List.of(challengeRow);
            }
            if (sql.contains("emergency_security_action")) {
                checkedActiveOnly = sql.contains("used = FALSE") && sql.contains("expires_at > CURRENT_TIMESTAMP");
                if (checkedActiveOnly && activeTicket != null && !activeTicket.isBlank()) {
                    return List.of(Map.of("ticket_id", activeTicket));
                }
                return List.of();
            }
            return List.of();
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> type, Object... args) {
            if (sql.contains("security.auth.mfa.policy")) return type.cast(policy);
            if (sql.contains("security.superadmin.mfa.enabled")) return type.cast(superAdminMfa);
            if (sql.contains("auth_totp_enrollment_pending")) return type.cast(0L);
            if (sql.contains("login_throttle")) return type.cast(0L);
            if (sql.contains("registration_request")) return type.cast(0L);
            if (sql.contains("app_license")) return null;
            if (sql.contains("auth_session")) return type.cast(0L);
            throw new AssertionError("Unexpected query: " + sql);
        }
    }
}
