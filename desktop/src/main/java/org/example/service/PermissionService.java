package org.example.service;

import org.example.api.auth.AuthApiClient;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Desktop permission facade backed by the saved server Permission Matrix. ADMIN
 * remains full access. If the admin API is temporarily unavailable the legacy
 * role defaults are used so navigation never crashes during startup.
 */
public final class PermissionService {
    private static final Set<String> MANAGER_DENIED = Set.of("usersview", "backupview", "settingsview");
    private static final Set<String> SALES_ALLOWED = Set.of("salesview", "quotationview", "customersview", "remindersview");
    private static volatile String cachedRole;
    private static volatile Set<String> cachedAllowed = Set.of();
    private static volatile boolean cacheLoaded;
    private static volatile Set<String> cachedSubscribedFeatures;
    private static volatile boolean featuresLoaded;
    private static final Set<Runnable> CHANGE_LISTENERS = new CopyOnWriteArraySet<>();

    private PermissionService() {}

    public static boolean allowed(String permissionKey) {
        if (SessionService.current() == null) return false;
        if (SessionService.isSuperAdmin()) return true;
        String module = extractModule(permissionKey);
        if (!isModuleSubscribed(module)) {
            return false; // Gated by Company Subscription Agreement!
        }
        String role = normalizeRole(SessionService.current().getRole());
        if (SessionService.isAdminRole(role)) {
            String token = org.example.api.ApiSession.token();
            if (token == null || token.isBlank()) return true;
            ensureLoaded(role);
            String wanted = normalize(permissionKey);
            if (cacheLoaded && role.equals(cachedRole) && !cachedAllowed.isEmpty()) {
                return cachedAllowed.contains(wanted);
            }
            return true;
        }
        ensureLoaded(role);
        String wanted = normalize(permissionKey);
        if (cacheLoaded && role.equals(cachedRole)) return cachedAllowed.contains(wanted);
        return legacyAllowed(role, wanted);
    }

    /** Enforces a desktop-local action such as export, update installation or rollback. */
    public static void require(String permissionKey, String operation) {
        if (!allowed(permissionKey)) {
            String label = operation == null || operation.isBlank() ? "perform this action" : operation.trim();
            throw new SecurityException("Permission denied: " + permissionKey + " is required to " + label + ".");
        }
    }

    /** Subscribe a live shell/control surface to permission changes. */
    public static void addChangeListener(Runnable listener) {
        if (listener != null) CHANGE_LISTENERS.add(listener);
    }

    public static void removeChangeListener(Runnable listener) {
        if (listener != null) CHANGE_LISTENERS.remove(listener);
    }

    /** Refresh after login or after an administrator saves the matrix. */
    public static synchronized void refresh() {
        refresh(false);
    }

    /** Login-time refresh: permission/API failures are fatal instead of silently falling back. */
    public static synchronized void refreshStrict() {
        refresh(true);
    }

    private static void refresh(boolean strict) {
        cachedRole = null; cachedAllowed = Set.of(); cacheLoaded = false;
        cachedSubscribedFeatures = null; featuresLoaded = false;
        if (SessionService.current() != null) {
            ensureFeaturesLoaded();
            String role = normalizeRole(SessionService.current().getRole());
            // ADMIN is a system policy, not a permission-matrix row. Never make an
            // administrator's desktop access depend on the permission endpoint being available.
            if (SessionService.isSuperAdmin()) {
                cachedRole = role;
                cacheLoaded = true;
            } else if (SessionService.isAdminRole(role)) {
                String token = org.example.api.ApiSession.token();
                if (token == null || token.isBlank()) {
                    cachedRole = role;
                    cacheLoaded = true;
                } else {
                    ensureLoaded(role, false);
                    if (!cacheLoaded) {
                        cachedRole = role;
                        cacheLoaded = true;
                    }
                }
            } else {
                ensureLoaded(role, strict);
            }
        }
        notifyChangeListeners();
    }

    private static void notifyChangeListeners() {
        for (Runnable listener : CHANGE_LISTENERS) {
            try { listener.run(); } catch (RuntimeException ignored) { /* one view must not block others */ }
        }
    }

    private static synchronized void ensureLoaded(String role) {
        ensureLoaded(role, false);
    }

    private static synchronized void ensureLoaded(String role, boolean strict) {
        if (cacheLoaded && role.equals(cachedRole)) return;
        try {
            Set<String> allowed = new HashSet<>();
            for (var p : new AuthApiClient().effectivePermissions()) {
                allowed.add(normalize(p.module()+"."+p.action()));
            }
            cachedRole = role; cachedAllowed = Set.copyOf(allowed); cacheLoaded = true;
        } catch (org.example.api.ApiSession.AuthenticationRequiredException authenticationFailure) {
            cachedRole = role; cachedAllowed = Set.of(); cacheLoaded = false;
            throw authenticationFailure;
        } catch (Exception failure) {
            cachedRole = role; cachedAllowed = Set.of(); cacheLoaded = false;
            if (strict) {
                throw failure instanceof RuntimeException runtime ? runtime
                        : new IllegalStateException("Unable to load effective permissions", failure);
            }
        }
    }

    private static boolean legacyAllowed(String role, String wanted) {
        if ("MANAGER".equals(role)) return !MANAGER_DENIED.contains(wanted);
        if ("SALES".equals(role)) return SALES_ALLOWED.contains(wanted);
        return false;
    }

    private static String normalizeRole(String value) {
        return SessionService.canonicalRole(value);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    public static String extractModule(String permissionKey) {
        if (permissionKey == null || permissionKey.isBlank()) return "";
        String clean = permissionKey.trim().toUpperCase(Locale.ROOT);
        int dot = clean.indexOf('.');
        return dot > 0 ? clean.substring(0, dot) : clean;
    }

    public static String featureTagForModule(String module) {
        if (module == null || module.isBlank()) return null;
        String mod = module.trim().toUpperCase(Locale.ROOT).replace("-", "_").replace(" ", "_");
        if (org.example.shared.PermissionCatalog.isAlwaysAccessibleModule(mod)) {
            return null;
        }
        return mod;
    }

    public static boolean isFeatureSubscribed(String featureTag) {
        if (SessionService.isSuperAdmin()) return true;
        if (featureTag == null || featureTag.isBlank()) return true;
        String mod = featureTag.trim().toUpperCase(Locale.ROOT).replace("-", "_").replace(" ", "_");
        if (org.example.shared.PermissionCatalog.isAlwaysAccessibleModule(mod)) return true;
        ensureFeaturesLoaded();
        if (cachedSubscribedFeatures == null || cachedSubscribedFeatures.isEmpty()) {
            return true;
        }
        return cachedSubscribedFeatures.contains(mod);
    }

    public static boolean isModuleSubscribed(String module) {
        if (SessionService.isSuperAdmin()) return true;
        if (module == null || module.isBlank()) return true;
        String mod = module.trim().toUpperCase(Locale.ROOT).replace("-", "_").replace(" ", "_");
        if (org.example.shared.PermissionCatalog.isAlwaysAccessibleModule(mod)) {
            return true;
        }
        return isFeatureSubscribed(mod);
    }

    public static Set<String> getSubscribedFeatures() {
        ensureFeaturesLoaded();
        return cachedSubscribedFeatures != null ? cachedSubscribedFeatures : Set.of();
    }

    public static synchronized void ensureFeaturesLoaded() {
        if (featuresLoaded && cachedSubscribedFeatures != null) return;
        try {
            org.example.api.licensing.LicenseApiClient client = new org.example.api.licensing.LicenseApiClient();
            org.example.api.licensing.LicenseApiClient.LicenseStatusDto status = client.getStatus();
            if (status != null && status.features() != null && !status.features().isEmpty()) {
                cachedSubscribedFeatures = org.example.shared.PermissionCatalog.expandFeatureTokens(status.features());
                featuresLoaded = true;
                return;
            }
            String tier = (status != null && status.tier() != null) ? status.tier() : "GOLD";
            cachedSubscribedFeatures = org.example.shared.PermissionCatalog.expandFeatureTokens(fallbackFeaturesForTier(tier));
            featuresLoaded = true;
        } catch (Exception e) {
            cachedSubscribedFeatures = org.example.shared.PermissionCatalog.expandFeatureTokens(defaultAllFeatures());
            featuresLoaded = true;
        }
    }

    public static List<String> fallbackFeaturesForTier(String tier) {
        String t = tier == null ? "GOLD" : tier.trim().toUpperCase(Locale.ROOT);
        try {
            var catalog = new org.example.api.licensing.LicenseApiClient().getCatalog();
            if (catalog != null && catalog.get("plans") instanceof List<?> plans) {
                for (Object item : plans) {
                    if (item instanceof java.util.Map<?, ?> plan) {
                        String code = String.valueOf(plan.get("tier_code") != null ? plan.get("tier_code") : plan.get("tierCode")).trim().toUpperCase(Locale.ROOT);
                        if (t.equals(code)) {
                            String featStr = String.valueOf(plan.get("features") != null ? plan.get("features") : "").trim();
                            if (!featStr.isBlank() && !"null".equalsIgnoreCase(featStr)) {
                                Set<String> expanded = org.example.shared.PermissionCatalog.expandFeatureTokens(List.of(featStr));
                                if (!expanded.isEmpty()) return List.copyOf(expanded);
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return defaultAllFeatures();
    }

    public static List<String> defaultAllFeatures() {
        List<String> all = new java.util.ArrayList<>();
        for (var m : org.example.shared.PermissionCatalog.subscriptionModules()) {
            all.add(m.key());
        }
        return List.copyOf(all);
    }
}
