package org.example.server.admin;

import org.example.server.master.RoleMasterService;
import org.example.server.persistence.JpaNativeRepository;
import org.example.server.profile.ProfileDtos;
import org.example.server.profile.ProfileService;
import org.example.server.security.AuthenticatedUser;
import org.example.server.security.TokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.*;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminMfaToggleAndPolicyScenarioTest {
    @AfterEach void clearSecurity() { SecurityContextHolder.clearContext(); }

    @Test
    void currentStaffUserTurningMfaOnRevokesSessionClearsOldStateAndForcesEnrollment() {
        actor(7, "admin");
        ToggleDb db = new ToggleDb(false, "ADMIN_CONTROLLED");
        TokenService tokens = mock(TokenService.class);
        RoleMasterService roles = mock(RoleMasterService.class);
        when(roles.requireActive("SALES")).thenReturn(new RoleMasterService.RoleDefinition(2,"SALES","Sales","",true,0,1));
        AdminService service = service(db,tokens,roles,true);

        AdminDtos.UserDto saved = service.saveUser(request(true));

        assertTrue(saved.mfaEnabled());
        verify(tokens).revokeUser(17);
        assertTrue(db.sql.stream().anyMatch(s -> s.contains("totp_secret_enc=NULL")), "Changing MFA state must invalidate any old authenticator secret");
        assertTrue(db.sql.stream().anyMatch(s -> s.contains("DELETE FROM auth_totp_enrollment_pending")));
        assertTrue(db.sql.stream().anyMatch(s -> s.contains("DELETE FROM auth_totp_login_challenge")));
        assertTrue(db.sql.stream().anyMatch(s -> s.contains("MFA_ENROLLMENT_REQUIRED")));

        AdminDtos.MfaState state = new AdminService(new PolicyDb("SALES",true,"ADMIN_CONTROLLED","NO"), mock(PasswordEncoder.class), tokens, roles).mfaState(17);
        assertTrue(state.required());
        assertEquals("ENROLLMENT_REQUIRED", state.status());
    }

    @Test
    void currentStaffUserTurningMfaOffRevokesSessionAndInvalidatesAuthenticator() {
        actor(7, "admin");
        ToggleDb db = new ToggleDb(true, "ADMIN_CONTROLLED");
        TokenService tokens = mock(TokenService.class);
        RoleMasterService roles = mock(RoleMasterService.class);
        when(roles.requireActive("SALES")).thenReturn(new RoleMasterService.RoleDefinition(2,"SALES","Sales","",true,0,1));
        AdminService service = service(db,tokens,roles,false);

        AdminDtos.UserDto saved = service.saveUser(request(false));

        assertFalse(saved.mfaEnabled());
        verify(tokens).revokeUser(17);
        assertTrue(db.sql.stream().anyMatch(s -> s.contains("totp_secret_enc=NULL")));
        assertTrue(db.sql.stream().anyMatch(s -> s.contains("MFA_DISABLED")));
    }

    @Test
    void mfaPolicyMatrixMatchesRequiredAdminControlledDisabledAndSuperAdminIndependentRules() {
        TokenService tokens = mock(TokenService.class);
        RoleMasterService roles = mock(RoleMasterService.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);

        // REQUIRED: Both ADMIN and underlying users are forced into MFA
        assertState(new AdminService(new PolicyDb("ADMIN",false,"REQUIRED","NO"),passwords,tokens,roles), true, "ENROLLMENT_REQUIRED");
        assertState(new AdminService(new PolicyDb("SALES",false,"REQUIRED","NO"),passwords,tokens,roles), true, "ENROLLMENT_REQUIRED");

        // ADMIN_CONTROLLED: ADMIN is exempt; underlying users follow per-user flag
        assertState(new AdminService(new PolicyDb("ADMIN",true,"ADMIN_CONTROLLED","NO"),passwords,tokens,roles), false, "DISABLED");
        assertState(new AdminService(new PolicyDb("SALES",false,"ADMIN_CONTROLLED","NO"),passwords,tokens,roles), false, "DISABLED");
        assertState(new AdminService(new PolicyDb("SALES",true,"ADMIN_CONTROLLED","NO"),passwords,tokens,roles), true, "ENROLLMENT_REQUIRED");

        // DISABLED: Nobody (ADMIN or underlying users) has MFA
        assertState(new AdminService(new PolicyDb("ADMIN",true,"DISABLED","NO"),passwords,tokens,roles), false, "DISABLED");
        assertState(new AdminService(new PolicyDb("SALES",true,"DISABLED","NO"),passwords,tokens,roles), false, "DISABLED");

        // SUPER_ADMIN: Strictly obeys independent security.superadmin.mfa.enabled (Yes/No), immune to company policy
        assertState(new AdminService(new PolicyDb("SUPER_ADMIN",true,"REQUIRED","NO"),passwords,tokens,roles), false, "DISABLED");
        assertState(new AdminService(new PolicyDb("SUPER_ADMIN",false,"DISABLED","YES"),passwords,tokens,roles), true, "ENROLLMENT_REQUIRED");
    }

    @Test
    void superAdminUsersListAndProfileObeyIndependentFlagEvenWithStaleDbColumn() {
        actor(1, "superadmin", "SUPER_ADMIN");
        TokenService tokens = mock(TokenService.class);
        RoleMasterService roles = mock(RoleMasterService.class);

        // Even with stale users.mfa_enabled=1 and ACTIVE raw SQL status, setting security.superadmin.mfa.enabled=NO must project Disabled/Off everywhere
        UsersGridDb gridOff = new UsersGridDb("NO", 1, "ACTIVE");
        AdminService adminOff = new AdminService(gridOff, mock(PasswordEncoder.class), tokens, roles);
        List<AdminDtos.UserDto> listOff = adminOff.users();
        assertEquals(1, listOff.size());
        assertFalse(listOff.getFirst().mfaEnabled());
        assertEquals("DISABLED", listOff.getFirst().mfaStatus());
        assertFalse(adminOff.user(1).mfaEnabled());
        assertEquals("DISABLED", adminOff.user(1).mfaStatus());

        ProfileDtos.ProfileDto profileOff = new ProfileService(gridOff).current();
        assertFalse(profileOff.mfaEnabled(), "My Profile must reflect Super Admin MFA flag OFF even if stale DB flag is 1");

        // When Super Admin MFA flag is turned ON, grid and profile both report enabled/active
        UsersGridDb gridOn = new UsersGridDb("YES", 1, "ACTIVE");
        AdminService adminOn = new AdminService(gridOn, mock(PasswordEncoder.class), tokens, roles);
        assertTrue(adminOn.user(1).mfaEnabled());
        assertEquals("ACTIVE", adminOn.user(1).mfaStatus());
        assertTrue(new ProfileService(gridOn).current().mfaEnabled());
    }

    private static void assertState(AdminService service, boolean required, String status) {
        AdminDtos.MfaState state = service.mfaState(17);
        assertEquals(required,state.required());
        assertEquals(status,state.status());
    }

    private static AdminService service(ToggleDb db, TokenService tokens, RoleMasterService roles, boolean resultingMfa) {
        return new AdminService(db,mock(PasswordEncoder.class),tokens,roles) {
            @Override public AdminDtos.UserDto user(int id) {
                return new AdminDtos.UserDto(id,"salesrep","Sales Rep","sales@company.com","9876543210","SALES","Sales","STANDARD","Head Office",true,false,resultingMfa,"",5);
            }
        };
    }

    private static AdminDtos.UserSaveRequest request(boolean mfa) {
        return new AdminDtos.UserSaveRequest(17,"salesrep","","Sales Rep","sales@company.com","9876543210","SALES","Sales","STANDARD","Head Office",true,false,mfa,4);
    }

    private static void actor(int id, String username) {
        actor(id, username, "ADMIN");
    }

    private static void actor(int id, String username, String role) {
        var principal = new AuthenticatedUser(id,username,role);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,"",List.of()));
    }

    private static final class ToggleDb extends JpaNativeRepository {
        final boolean previousMfa; final String policy; final List<String> sql = new ArrayList<>();
        ToggleDb(boolean previousMfa,String policy){this.previousMfa=previousMfa;this.policy=policy;}
        @Override public int update(String query,Object...args){sql.add(query.replaceAll("\\s+"," ") + " ARGS=" + java.util.Arrays.toString(args));return 1;}
        @Override public Map<String,Object> queryForMap(String query,Object...args){
            if(query.contains("row_version") && query.contains("FOR UPDATE")) return Map.of("row_version",4L);
            if(query.contains("COALESCE(role,'') role") && query.contains("active")) return Map.of("role","SALES","active",1,"locked",0);
            throw new AssertionError("Unexpected map query: "+query);
        }
        @Override public <T> T queryForObject(String query,Class<T> type,Object...args){
            if(query.contains("security.auth.mfa.policy")) return type.cast(policy);
            if(query.contains("security.superadmin.mfa.enabled")) return type.cast("NO");
            if(query.contains("COUNT(*) FROM users") && query.contains("username")) return type.cast(Long.valueOf(0));
            if(query.contains("COUNT(*) FROM users") && query.contains("email")) return type.cast(Long.valueOf(0));
            if(query.contains("COUNT(*) FROM users") && query.contains("phone")) return type.cast(Long.valueOf(0));
            if(query.contains("SELECT mfa_enabled FROM users")) return type.cast(Boolean.valueOf(previousMfa));
            if(query.contains("SELECT locked FROM users")) return type.cast(Boolean.FALSE);
            if(query.contains("SELECT COALESCE(role,'') FROM users")) return type.cast("SALES");
            throw new AssertionError("Unexpected object query: "+query);
        }
        @Override public <T> List<T> query(String query, BiFunction<NativeRow,Integer,T> mapper,Object...args){
            if(query.contains("pg_advisory_xact_lock")) return List.of();
            throw new AssertionError("Unexpected row query: "+query);
        }
    }

    private static final class PolicyDb extends JpaNativeRepository {
        final String role; final boolean mfa; final String policy; final String superAdminMfa;
        PolicyDb(String role,boolean mfa,String policy,String superAdminMfa){this.role=role;this.mfa=mfa;this.policy=policy;this.superAdminMfa=superAdminMfa;}
        @Override public Map<String,Object> queryForMap(String q,Object...args){return Map.of("role",role,"mfa_enabled",mfa,"totp_secret_enc","");}
        @Override public <T> T queryForObject(String q,Class<T> type,Object...args){
            if(q.contains("security.auth.mfa.policy")) return type.cast(policy);
            if(q.contains("security.superadmin.mfa.enabled")) return type.cast(superAdminMfa);
            if(q.contains("auth_totp_enrollment_pending")) return type.cast(Long.valueOf(0));
            throw new AssertionError("Unexpected query: "+q);
        }
    }

    private static final class UsersGridDb extends JpaNativeRepository {
        final String superAdminMfa; final int rawMfa; final String rawStatus;
        UsersGridDb(String superAdminMfa, int rawMfa, String rawStatus) {
            this.superAdminMfa = superAdminMfa; this.rawMfa = rawMfa; this.rawStatus = rawStatus;
        }
        @Override public <T> T queryForObject(String q, Class<T> type, Object... args) {
            if (q.contains("security.superadmin.mfa.enabled")) return type.cast(superAdminMfa);
            if (q.contains("security.auth.mfa.policy")) return type.cast("REQUIRED");
            throw new AssertionError("Unexpected query: " + q);
        }
        @Override public <T> List<T> query(String q, BiFunction<NativeRow, Integer, T> mapper, Object... args) {
            NativeRow row = mock(NativeRow.class);
            when(row.getInt(1)).thenReturn(1);
            when(row.getString(2)).thenReturn("superadmin");
            when(row.getString(3)).thenReturn("Super Admin");
            when(row.getString(4)).thenReturn("superadmin@jasvi.test");
            when(row.getString(5)).thenReturn("9099939193");
            when(row.getString(6)).thenReturn("SUPER_ADMIN");
            when(row.getString(7)).thenReturn("SuperAdmin");
            when(row.getString(8)).thenReturn("FULL ACCESS");
            when(row.getString(9)).thenReturn("Nikol");
            when(row.getObject(10)).thenReturn(1);
            when(row.getObject(11)).thenReturn(0);
            when(row.getObject(12)).thenReturn(rawMfa);
            when(row.getObject(13)).thenReturn("2026-11-28T08:07:00Z");
            when(row.getLong(14)).thenReturn(1L);
            if (q.contains("AS mfa_status")) {
                when(row.getString(15)).thenReturn(rawStatus);
            }
            return List.of(mapper.apply(row, 0));
        }
    }
}
