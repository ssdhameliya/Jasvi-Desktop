package org.example.server.auth;

import org.example.server.communication.ServerNotificationTemplateService;
import org.example.server.master.RoleMasterService;
import org.example.server.persistence.JpaNativeRepository;
import org.example.server.persistence.entity.UserEntity;
import org.example.server.persistence.repository.UserRepository;
import org.example.server.security.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Verifies PostgreSQL 25P02 transaction safety across password reset OTP issuance, login seat checks,
 * notification template/briefing queries, and API error redaction.
 */
class PasswordResetPostgresSafetyTest {

    @Test
    void templateServiceAndBriefingSchedulerNeverQueryMissingAppConfigTable() throws Exception {
        TrackingPgTxManager txManager = new TrackingPgTxManager(fakeDataSource());
        StrictPostgresRepo db = new StrictPostgresRepo(txManager);
        ServerNotificationTemplateService templates = ServerNotificationTemplateService.forJdbc(db);

        assertEquals("fallback-body", templates.setting("email.template.auth_otp.body", "fallback-body"));
        assertEquals("Your Organization", templates.companyName());
        assertFalse(db.executedSql.isEmpty());
        assertTrue(db.executedSql.stream().noneMatch(sql -> sql.toLowerCase(Locale.ROOT).contains("app_config")),
                "Notification template lookups must never query nonexistent app_config table");

        Path templateSrc = resolveServerFile("src/main/java/org/example/server/communication/ServerNotificationTemplateService.java");
        Path schedulerSrc = resolveServerFile("src/main/java/org/example/server/communication/ExecutiveBriefingScheduler.java");
        assertFalse(Files.readString(templateSrc).contains("app_config"),
                "ServerNotificationTemplateService must not reference app_config");
        assertFalse(Files.readString(schedulerSrc).contains("app_config"),
                "ExecutiveBriefingScheduler must not reference app_config");
    }

    @Test
    void requestPasswordResetSuspendsTxDuringMailDispatchAndPrevents25P02Abort() throws Exception {
        TrackingPgTxManager txManager = new TrackingPgTxManager(fakeDataSource());
        StrictPostgresRepo db = new StrictPostgresRepo(txManager);
        db.failTemplateLookupOnce = true;

        AuthOtpService rawOtp = new AuthOtpService(db);
        rawOtp.setTransactionManager(txManager);

        UserRepository users = mock(UserRepository.class);
        RoleMasterService roles = mock(RoleMasterService.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        TokenService tokens = mock(TokenService.class);
        SmtpMailService mail = mock(SmtpMailService.class);
        TotpService totp = mock(TotpService.class);
        RegistrationCaptchaService captcha = mock(RegistrationCaptchaService.class);

        AuthService rawService = new AuthService(users, roles, passwords, tokens, rawOtp, mail, db, totp, captcha);
        rawService.setTransactionManager(txManager);
        AuthService proxiedService = wrapWithTransactions(rawService, txManager);

        UserEntity admin = user(1, "superadmin", "SUPER_ADMIN", "owner@jasvi.example");
        when(users.findActiveByIdentityIncludingLocked("superadmin")).thenReturn(Optional.of(admin));

        AtomicBoolean mailSawSuspendedTransaction = new AtomicBoolean(false);
        doAnswer(invocation -> {
            mailSawSuspendedTransaction.set(!TransactionSynchronizationManager.isActualTransactionActive());
            ServerNotificationTemplateService templates = ServerNotificationTemplateService.forJdbc(db);
            templates.companyName();
            templates.setting("email.template.auth_otp.body", "default");
            return null;
        }).when(mail).sendOtp(eq("owner@jasvi.example"), anyString(), anyString());

        AuthDtos.ChallengeResponse response = proxiedService.requestPasswordReset(
                new AuthDtos.PasswordResetOtpRequest("superadmin", null, null));

        assertTrue(response.success(), "Password reset OTP request must succeed cleanly");
        assertNotNull(response.challengeId());
        assertTrue(response.message().contains("@jasvi.example"), "Response must confirm masked destination email");
        assertFalse(response.message().contains("owner@jasvi.example"), "Full email address should be masked");
        assertTrue(mailSawSuspendedTransaction.get(),
                "Outbound SMTP and dynamic template queries must run outside any active PostgreSQL transaction");
        assertTrue(db.insertedChallenge, "auth_challenge row must be inserted after mail dispatch");
    }

    @Test
    void loginSurvivesOptionalMachineQuotaSqlFailureInIsolatedRequiresNewTransaction() throws Exception {
        TrackingPgTxManager txManager = new TrackingPgTxManager(fakeDataSource());
        StrictPostgresRepo db = new StrictPostgresRepo(txManager);
        db.failMachineQuotaQuery = true;

        AuthOtpService rawOtp = new AuthOtpService(db);
        rawOtp.setTransactionManager(txManager);

        UserRepository users = mock(UserRepository.class);
        RoleMasterService roles = mock(RoleMasterService.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        TokenService tokens = mock(TokenService.class);
        SmtpMailService mail = mock(SmtpMailService.class);
        TotpService totp = mock(TotpService.class);
        RegistrationCaptchaService captcha = mock(RegistrationCaptchaService.class);

        AuthService rawService = new AuthService(users, roles, passwords, tokens, rawOtp, mail, db, totp, captcha);
        rawService.setTransactionManager(txManager);
        AuthService proxiedService = wrapWithTransactions(rawService, txManager);

        UserEntity staff = user(7, "salesuser", "SALES", "sales@jasvi.example");
        when(users.findForAuthentication("salesuser")).thenReturn(Optional.of(staff));
        when(passwords.matches("wrong-pass", staff.getPassword())).thenReturn(false);

        AuthDtos.LoginResponse response = proxiedService.login(
                new AuthDtos.LoginRequest("salesuser", "wrong-pass"),
                "127.0.0.1",
                "WORKSTATION-01");

        assertFalse(response.success());
        assertEquals("Invalid email/username or password. Failed attempt 1 of 3.", response.message());
    }

    @Test
    void authApiExceptionHandlerRedactsJdbcAndSqlStateDetails() {
        AuthApiExceptionHandler handler = new AuthApiExceptionHandler();
        UncategorizedSQLException sqlEx = new UncategorizedSQLException(
                "delete",
                "DELETE FROM auth_challenge WHERE purpose=?",
                new SQLException("ERROR: current transaction is aborted, commands ignored until end of transaction block", "25P02"));

        var response = handler.serverError(sqlEx);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().success());
        String msg = response.getBody().message();
        assertFalse(msg.contains("JDBC"), "Raw JDBC exception text must never reach UI");
        assertFalse(msg.contains("25P02"), "Raw SQLSTATE must never reach UI");
        assertFalse(msg.contains("auth_challenge"), "Raw table name must never reach UI");
        assertTrue(msg.contains("database error"), "Friendly diagnostic message must be returned");
    }

    private static AuthService wrapWithTransactions(AuthService target, DataSourceTransactionManager txManager) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(txManager, new AnnotationTransactionAttributeSource()));
        return (AuthService) factory.getProxy();
    }

    private static DataSource fakeDataSource() {
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class[]{DataSource.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getConnection" -> fakeConnection();
                    case "isWrapperFor" -> false;
                    case "unwrap" -> null;
                    case "toString" -> "FakePgDataSource";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    private static Connection fakeConnection() {
        final boolean[] autoCommit = {true};
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getAutoCommit" -> autoCommit[0];
                    case "setAutoCommit" -> {
                        autoCommit[0] = (Boolean) args[0];
                        yield null;
                    }
                    case "isClosed" -> false;
                    case "isReadOnly" -> false;
                    case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
                    case "commit", "rollback", "close", "setReadOnly", "setTransactionIsolation" -> null;
                    case "toString" -> "FakePgConnection";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    private static UserEntity user(int id, String username, String role, String email) throws Exception {
        UserEntity user = new UserEntity();
        Field idField = UserEntity.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(user, id);
        user.setUsername(username);
        user.setPassword("$2a$12$test-bcrypt-placeholder");
        user.setFullName(username);
        user.setEmail(email);
        user.setRole(role);
        user.setActive(true);
        user.setLocked(false);
        user.setMfaEnabled(false);
        user.setApprovalStatus("APPROVED");
        user.setAccessLevel("STANDARD");
        return user;
    }

    private static Path resolveServerFile(String relativePath) {
        Path direct = Path.of(relativePath);
        if (Files.exists(direct)) return direct;
        Path reactor = Path.of("server").resolve(relativePath);
        if (Files.exists(reactor)) return reactor;
        throw new IllegalStateException("File not found: " + relativePath);
    }

    private static final class TrackingPgTxManager extends DataSourceTransactionManager {
        private final AtomicLong sequence = new AtomicLong();
        private final ThreadLocal<Deque<Long>> activeTxIds = ThreadLocal.withInitial(ArrayDeque::new);
        private final ThreadLocal<Deque<Long>> suspendedTxIds = ThreadLocal.withInitial(ArrayDeque::new);

        TrackingPgTxManager(DataSource dataSource) {
            super(dataSource);
        }

        Long currentTxId() {
            return activeTxIds.get().peek();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            activeTxIds.get().push(sequence.incrementAndGet());
            super.doBegin(transaction, definition);
        }

        @Override
        protected Object doSuspend(Object transaction) {
            Deque<Long> active = activeTxIds.get();
            suspendedTxIds.get().push(active.isEmpty() ? -1L : active.pop());
            return super.doSuspend(transaction);
        }

        @Override
        protected void doResume(Object transaction, Object suspendedResources) {
            Deque<Long> suspended = suspendedTxIds.get();
            if (!suspended.isEmpty()) {
                long id = suspended.pop();
                if (id > 0) activeTxIds.get().push(id);
            }
            super.doResume(transaction, suspendedResources);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            try {
                super.doCommit(status);
            } finally {
                if (!activeTxIds.get().isEmpty()) activeTxIds.get().pop();
            }
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            try {
                super.doRollback(status);
            } finally {
                if (!activeTxIds.get().isEmpty()) activeTxIds.get().pop();
            }
        }
    }

    /**
     * Simulates PostgreSQL strict transaction abort semantics: any SQL exception inside an active
     * transaction marks that specific transaction ID aborted (25P02) unless isolated via
     * REQUIRES_NEW or suspended via NOT_SUPPORTED.
     */
    private static final class StrictPostgresRepo extends JpaNativeRepository {
        private final TrackingPgTxManager txManager;
        final List<String> executedSql = new ArrayList<>();
        final Set<Long> abortedTxIds = new HashSet<>();
        boolean failTemplateLookupOnce;
        boolean failMachineQuotaQuery;
        boolean insertedChallenge;

        StrictPostgresRepo(TrackingPgTxManager txManager) {
            this.txManager = txManager;
        }

        private void beforeSql(String sql) {
            executedSql.add(sql);
            boolean activeTx = TransactionSynchronizationManager.isActualTransactionActive();
            Long txId = activeTx ? txManager.currentTxId() : null;
            if (txId != null && abortedTxIds.contains(txId)) {
                throw new UncategorizedSQLException("sql", sql,
                        new SQLException("ERROR: current transaction is aborted, commands ignored until end of transaction block", "25P02"));
            }
            if (sql.toLowerCase(Locale.ROOT).contains("app_config")) {
                if (txId != null) abortedTxIds.add(txId);
                throw new UncategorizedSQLException("query", sql,
                        new SQLException("ERROR: relation \"app_config\" does not exist", "42P01"));
            }
            if (failMachineQuotaQuery && sql.contains("licensed_client_registry")) {
                if (txId != null) abortedTxIds.add(txId);
                throw new UncategorizedSQLException("query", sql,
                        new SQLException("ERROR: simulated schema failure on licensed_client_registry", "42P01"));
            }
            if (failTemplateLookupOnce && sql.contains("application_setting")) {
                failTemplateLookupOnce = false;
                if (txId != null) abortedTxIds.add(txId);
                throw new UncategorizedSQLException("query", sql,
                        new SQLException("ERROR: simulated template query failure", "42P01"));
            }
        }

        @Override
        public int update(String sql, Object... args) {
            beforeSql(sql);
            if (sql.contains("INSERT INTO auth_challenge")) {
                insertedChallenge = true;
            }
            return 1;
        }

        @Override
        public <T> List<T> query(String sql, BiFunction<JpaNativeRepository.NativeRow, Integer, T> mapper, Object... args) {
            beforeSql(sql);
            return List.of();
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            beforeSql(sql);
            return List.of();
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> type, Object... args) {
            beforeSql(sql);
            if (sql.contains("login_throttle")) return type.cast(0L);
            if (sql.contains("auth_session")) return type.cast(0L);
            if (sql.contains("security.auth.mfa.policy")) return type.cast("ADMIN_CONTROLLED");
            return null;
        }
    }
}
