package org.example.service;

import org.example.model.AppUser;

import java.util.Locale;

public final class SessionService {
    private static AppUser current;

    private SessionService() {
    }

    public static void signIn(AppUser user) {
        ReferenceDataCache.invalidateAll();
        NotificationPreferenceService.reset();
        current = user;
    }

    public static AppUser current() {
        return current;
    }

    /** Canonical security role code used by all desktop permission decisions. */
    public static String canonicalRole(String value) {
        String role = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        // Legacy databases/UI values used ADMINISTRATOR before ADMIN became the stable role code.
        return "ADMINISTRATOR".equals(role) ? "ADMIN" : role;
    }

    public static boolean isAdminRole(String value) {
        String role = canonicalRole(value);
        return "ADMIN".equals(role) || "SUPER_ADMIN".equals(role);
    }

    public static boolean isAdmin() {
        return current != null && isAdminRole(current.getRole());
    }

    public static String currentRole() {
        return current != null ? canonicalRole(current.getRole()) : "USER";
    }

    public static boolean isSuperAdmin() {
        return current != null && ("SUPER_ADMIN".equalsIgnoreCase(current.getRole()) || "superadmin".equalsIgnoreCase(current.getUsername()));
    }

    public static void clear() {
        ReferenceDataCache.invalidateAll();
        NotificationPreferenceService.reset();
        current = null;
        org.example.api.ApiSession.clear();
        org.example.util.UiTaskExecutor.cancelAllReads();
        org.example.navigation.NavigationManager.clearGlobalCache();
        org.example.navigation.WorkspaceTabManager.reset();
    }
}
