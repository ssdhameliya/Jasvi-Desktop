package org.example.server.profile;

public final class ProfileDtos {
    private ProfileDtos() {}

    public record ProfileDto(
            int id,
            String username,
            String fullName,
            String email,
            String phone,
            String role,
            String department,
            String branch,
            String accessLevel,
            boolean active,
            boolean locked,
            boolean mfaEnabled,
            String lastLogin,
            long rowVersion,
            String avatarData
    ) {
        public ProfileDto(int id, String username, String fullName, String email, String phone, String role,
                          String department, String branch, String accessLevel, boolean active, boolean locked,
                          boolean mfaEnabled, String lastLogin, long rowVersion) {
            this(id, username, fullName, email, phone, role, department, branch, accessLevel, active, locked, mfaEnabled, lastLogin, rowVersion, "");
        }
    }

    public record ProfileUpdate(String fullName, String email, String phone, String department, String branch, long rowVersion) {}
    public record AvatarUpdateRequest(String avatarData) {}
}
