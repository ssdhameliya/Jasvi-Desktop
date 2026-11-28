package org.example.api.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.ConfigManager;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class ProfileApiClient {
    private final HttpClient http = org.example.api.ApiRuntime.HTTP;
    private final ObjectMapper json = org.example.api.ApiRuntime.JSON;
    private final String base;

    public ProfileApiClient() {
        String b = ConfigManager.getDataApiBaseUrl();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        base = b;
    }

    public ProfileDto current() {
        return request("GET", "/api/profile", null);
    }

    public ProfileDto update(ProfileUpdate u) {
        return request("PUT", "/api/profile", u);
    }

    public ProfileDto updateAvatar(String avatarData) {
        return request("PUT", "/api/profile/avatar", new AvatarUpdateRequest(avatarData));
    }

    public ProfileDto removeAvatar() {
        return request("DELETE", "/api/profile/avatar", null);
    }

    private ProfileDto request(String method, String path, Object body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json");
            org.example.api.ApiSession.authorize(b);
            if (body == null) {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                b.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            }
            var r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() < 200 || r.statusCode() >= 300) {
                throw new IllegalStateException(org.example.api.ApiRuntime.errorMessage("Profile API", r.statusCode(), r.body()));
            }
            return json.readValue(r.body(), ProfileDto.class);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot reach profile API at " + base, e);
        }
    }

    public record ProfileDto(
            int id, String username, String fullName, String email, String phone,
            String role, String department, String branch, String accessLevel,
            boolean active, boolean locked, boolean mfaEnabled, String lastLogin, long rowVersion,
            String avatarData
    ) {
        public ProfileDto(int id, String username, String fullName, String email, String phone,
                          String role, String department, String branch, String accessLevel,
                          boolean active, boolean locked, boolean mfaEnabled, String lastLogin, long rowVersion) {
            this(id, username, fullName, email, phone, role, department, branch, accessLevel, active, locked, mfaEnabled, lastLogin, rowVersion, "");
        }
    }

    public record ProfileUpdate(String fullName, String email, String phone, String department, String branch, long rowVersion) {}
    public record AvatarUpdateRequest(String avatarData) {}
}
