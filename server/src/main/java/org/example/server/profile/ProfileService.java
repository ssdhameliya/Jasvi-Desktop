package org.example.server.profile;

import org.example.server.chat.TeamChatService;
import org.example.server.persistence.JpaNativeRepository;
import org.example.server.security.CurrentUser;
import org.example.server.util.BusinessClock;
import org.example.server.web.ConcurrentEditException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.util.*;

@Service
public class ProfileService {
    private static final int MAX_AVATAR_BYTES = 512 * 1024;
    private final JpaNativeRepository jdbc;
    private final ObjectProvider<TeamChatService> teamChatProvider;

    public ProfileService(JpaNativeRepository jdbc) {
        this(jdbc, null);
    }

    @Autowired
    public ProfileService(JpaNativeRepository jdbc, ObjectProvider<TeamChatService> teamChatProvider) {
        this.jdbc = jdbc;
        this.teamChatProvider = teamChatProvider;
    }

    @Transactional(readOnly = true)
    public ProfileDtos.ProfileDto current() {
        int id = CurrentUser.require().id();
        List<ProfileDtos.ProfileDto> rows = jdbc.query(
                "SELECT u.id,u.username,u.full_name,u.email,u.phone,COALESCE(NULLIF(TRIM(u.role),''),'SALES')," +
                "u.department,u.branch,u.access_level,u.active,u.locked,u.mfa_enabled," +
                "COALESCE(NULLIF(u.last_login_utc,''),CAST(u.last_login AS text)),COALESCE(u.row_version,0),COALESCE(u.avatar_data,'') " +
                "FROM users u WHERE u.id=?",
                (rs, n) -> {
                    String uname = rs.getString(2);
                    String rcode = rs.getString(6);
                    boolean effectiveMfa = resolveEffectiveMfa(rcode, uname, flag(rs.getObject(12)));
                    return new ProfileDtos.ProfileDto(
                            rs.getInt(1), uname, rs.getString(3), rs.getString(4), rs.getString(5), rcode,
                            rs.getString(7), rs.getString(8), rs.getString(9),
                            flag(rs.getObject(10)), flag(rs.getObject(11)), effectiveMfa,
                            BusinessClock.toUtcText(rs.getObject(13)), rs.getLong(14), clean(rs.getString(15))
                    );
                }, id);
        if (rows.isEmpty()) throw new IllegalArgumentException("User profile was not found");
        return rows.getFirst();
    }

    private boolean resolveEffectiveMfa(String role, String username, boolean storedFlag) {
        String r = role == null ? "" : role.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        if ("SUPER_ADMIN".equals(r) || "superadmin".equalsIgnoreCase(username)) return isSuperAdminMfaEnabled();
        String policy = mfaPolicy();
        if ("DISABLED".equals(policy)) return false;
        if ("REQUIRED".equals(policy)) return true;
        if ("ADMIN".equals(r)) return false;
        return storedFlag;
    }

    private boolean isSuperAdminMfaEnabled() {
        try {
            String v = jdbc.queryForObject("SELECT setting_value FROM application_setting WHERE setting_key='security.superadmin.mfa.enabled'", String.class);
            if (v == null) return false;
            String n = v.trim().toUpperCase(Locale.ROOT);
            return "YES".equals(n) || "TRUE".equals(n) || "1".equals(n) || "ON".equals(n);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private String mfaPolicy() {
        try {
            String v = jdbc.queryForObject("SELECT setting_value FROM application_setting WHERE setting_key='security.auth.mfa.policy'", String.class);
            String n = v == null ? "REQUIRED" : v.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
            return switch (n) {
                case "ADMIN_CONTROLLED", "DISABLED" -> n;
                default -> "REQUIRED";
            };
        } catch (RuntimeException ignored) {
            return "REQUIRED";
        }
    }

    @Transactional
    public ProfileDtos.ProfileDto update(ProfileDtos.ProfileUpdate r) {
        if (r == null || blank(r.fullName())) throw new IllegalArgumentException("Full name is required");
        if (blank(r.email()) || !r.email().trim().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new IllegalArgumentException("Enter a valid email address");
        }
        int id = CurrentUser.require().id();
        Long duplicate = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE id<>? AND LOWER(TRIM(COALESCE(email,'')))=LOWER(TRIM(?))",
                Long.class, id, clean(r.email()));
        if (duplicate != null && duplicate > 0) throw new IllegalArgumentException("Email is already registered");
        int changed = jdbc.update(
                "UPDATE users SET full_name=?,email=?,phone=?,department=?,branch=?,row_version=row_version+1 WHERE id=? AND row_version=?",
                clean(r.fullName()), clean(r.email()), clean(r.phone()), clean(r.department()), clean(r.branch()), id, r.rowVersion());
        if (changed != 1) throw new ConcurrentEditException("User profile");
        jdbc.update("INSERT INTO activity_log(entity_type,entity_id,action,detail,created_by,created_at) VALUES('USER',?,'PROFILE_UPDATED','Own profile details updated',?,?)",
                id, CurrentUser.require().username(), BusinessClock.nowUtcText());
        broadcastProfilePresence(id);
        return current();
    }

    @Transactional
    public ProfileDtos.ProfileDto updateAvatar(String rawAvatarData) {
        String normalized = normalizeValidAvatar(rawAvatarData);
        int id = CurrentUser.require().id();
        jdbc.update("UPDATE users SET avatar_data=?, row_version=row_version+1 WHERE id=?", normalized, id);
        jdbc.update("INSERT INTO activity_log(entity_type,entity_id,action,detail,created_by,created_at) VALUES('USER',?,'PROFILE_AVATAR_UPDATED',?,?,?)",
                id, normalized.isEmpty() ? "Profile picture removed" : "Profile picture updated",
                CurrentUser.require().username(), BusinessClock.nowUtcText());
        broadcastProfilePresence(id);
        return current();
    }

    private void broadcastProfilePresence(int userId) {
        TeamChatService chat = teamChatProvider == null ? null : teamChatProvider.getIfAvailable();
        if (chat != null) {
            try {
                chat.broadcastUserProfileUpdated(userId);
            } catch (Exception ignored) {}
        }
    }

    private static String normalizeValidAvatar(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String trimmed = raw.trim();
        int comma = trimmed.indexOf(',');
        if (trimmed.startsWith("data:") && comma >= 0) {
            trimmed = trimmed.substring(comma + 1).trim();
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(trimmed);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid profile picture encoding.");
        }
        if (decoded.length == 0) return "";
        if (decoded.length > MAX_AVATAR_BYTES) {
            throw new IllegalArgumentException("Profile picture must be 512 KB or smaller after scaling.");
        }
        try {
            if (ImageIO.read(new ByteArrayInputStream(decoded)) == null) {
                throw new IllegalArgumentException("Unsupported image file. Please choose PNG or JPG.");
            }
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Could not decode profile picture.");
        }
        return trimmed;
    }

    private static boolean flag(Object v) { return v instanceof Boolean b ? b : v instanceof Number n && n.intValue() != 0; }
    private static boolean blank(String v) { return v == null || v.isBlank(); }
    private static String clean(String v) { return v == null ? "" : v.trim(); }
}
