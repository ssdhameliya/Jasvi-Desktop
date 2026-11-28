package org.example.service;

import javafx.application.Platform;
import javafx.scene.image.Image;
import org.example.api.ApiSession;
import org.example.config.ConfigManager;
import org.example.util.UiTaskExecutor;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/** Workspace-aware branding with safe built-in fallbacks for first launch. */
public final class BrandingService {
    private static final String TECHNICAL_PRODUCT_NAME = "DSE ERP";
    public static final String PANEL_BACKGROUND_PATH_KEY = "application.backgroundImagePath";
    public static final String PANEL_BACKGROUND_ENABLED_KEY = "application.backgroundEnabled";
    public static final String PANEL_BACKGROUND_SCOPE_KEY = "application.backgroundScope";
    public static final String PANEL_BACKGROUND_OPACITY_KEY = "application.backgroundOpacity";
    private static volatile String sharedCompanyName;
    private static volatile String sharedCompanyTagline;
    private static final AtomicBoolean sharedIdentityRefreshPending = new AtomicBoolean();

    private BrandingService() { }

    /** Stable internal product identity used only where a technical product name is required. */
    public static String technicalProductName() { return TECHNICAL_PRODUCT_NAME; }

    /** Canonical customer-facing identity. Shared clients resolve company.* from the company server after login. */
    public static String companyName() {
        String fallback = value("application.displayName", "");
        if (fallback.isBlank()) fallback = TECHNICAL_PRODUCT_NAME;
        if (mustAvoidSharedServerReadOnFxThread()) {
            String cached = sharedCompanyName;
            if (cached == null) refreshSharedIdentityAsync();
            return cached == null || cached.isBlank() ? fallback : cached;
        }
        String company = value("company.name", "");
        if (!company.isBlank()) {
            sharedCompanyName = company;
            return company;
        }
        return fallback;
    }

    /**
     * Backward-compatible UI accessor. Customer-facing surfaces must follow company.name rather
     * than a workstation-local application.displayName that can drift from UAT/PROD company data.
     */
    public static String applicationName() { return companyName(); }

    public static String tagline() {
        String applicationTagline = value("application.tagline", "");
        if (!applicationTagline.isBlank()) return applicationTagline;
        if (mustAvoidSharedServerReadOnFxThread()) {
            String cached = sharedCompanyTagline;
            if (cached == null) refreshSharedIdentityAsync();
            return cached == null || cached.isBlank() ? "Business Management Suite" : cached;
        }
        String companyTagline = value("company.tagline", "Business Management Suite");
        sharedCompanyTagline = companyTagline;
        return companyTagline;
    }

    /**
     * Invalidates the lightweight shared-company identity cache after Company settings change.
     * The next UI read returns a safe local fallback immediately and refreshes in the background.
     */
    public static void invalidateSharedIdentity() {
        sharedCompanyName = null;
        sharedCompanyTagline = null;
        sharedIdentityRefreshPending.set(false);
    }

    /** Preloads shared-client company identity without ever blocking the JavaFX application thread. */
    public static void refreshSharedIdentityAsync() {
        if (!ConfigManager.isSharedClient() || !ApiSession.isEstablished()) return;
        if (!sharedIdentityRefreshPending.compareAndSet(false, true)) return;
        UiTaskExecutor.submitLatest(
                "branding-shared-identity",
                () -> {
                    String company = value("company.name", "");
                    String tagline = value("company.tagline", "Business Management Suite");
                    return new String[]{company, tagline};
                },
                values -> {
                    sharedCompanyName = values[0];
                    sharedCompanyTagline = values[1];
                    sharedIdentityRefreshPending.set(false);
                },
                failure -> sharedIdentityRefreshPending.set(false)
        );
    }

    private static boolean mustAvoidSharedServerReadOnFxThread() {
        return Platform.isFxApplicationThread() && ConfigManager.isSharedClient() && ApiSession.isEstablished();
    }

    public static String startingText() {
        String configured = value("application.startingText", "");
        if (configured.isBlank() || configured.equalsIgnoreCase("Welcome")
                || configured.equalsIgnoreCase("Starting...")
                || configured.equalsIgnoreCase("Starting DSE ERP...")
                || configured.equalsIgnoreCase("Starting DSE ERP…")) {
            return "Welcome to " + applicationName();
        }
        return configured.replace(TECHNICAL_PRODUCT_NAME, applicationName());
    }

    public static String loginDescription() { return "Role-aware secure access to " + applicationName(); }

    /** Application UI banner used by Splash/Login/Registration/Email screens. */
    public static Image applicationBrandImage() {
        return configuredImage("application.brandImagePath");
    }

    /** Square application mark used below the wide banner on auth/startup screens. */
    public static Image applicationMarkImage() {
        return configuredImage("application.markImagePath");
    }

    /** Optional widescreen wallpaper rendered subtly across whitespace areas of auth/workspace screens. */
    public static Image panelBackgroundImage() {
        return configuredImage(PANEL_BACKGROUND_PATH_KEY);
    }

    public static boolean isPanelBackgroundEnabled() {
        String raw = value(PANEL_BACKGROUND_ENABLED_KEY, "true");
        return !"false".equalsIgnoreCase(raw) && !"no".equalsIgnoreCase(raw);
    }

    public static boolean isPanelBackgroundAppliedToWorkspace() {
        String raw = value(PANEL_BACKGROUND_SCOPE_KEY, "ALL_PANELS");
        return !"AUTH_ONLY".equalsIgnoreCase(raw);
    }

    public static double panelBackgroundImageOpacity() {
        String raw = value(PANEL_BACKGROUND_OPACITY_KEY, "0.25");
        try {
            double parsed = Double.parseDouble(raw);
            return Math.max(0.10, Math.min(0.65, parsed));
        } catch (NumberFormatException ignored) {
            return 0.25;
        }
    }

    public static Image companyLogo() { return configuredImage("company.logoPath"); }
    public static Image authorizedSignature() { return configuredImage("company.signaturePath"); }
    public static Image paymentQrImage() { return configuredImage("payment.qrImagePath"); }

    /** Bundled square emblem packaged with desktop installers and fallback branding surfaces. */
    public static Image bundledMarkImage() {
        try (InputStream input = org.example.util.ResourceLocator.open("/logo.png")) {
            if (input == null) return null;
            Image image = new Image(input);
            return image.isError() ? null : image;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Resolves the effective square mark for auth, splash, chat, and menu bar headers:
     * preserves configured Settings marks and company logos, falling back to the
     * bundled application emblem when no custom logo is configured.
     */
    public static Image effectiveMarkImage() {
        Image mark = applicationMarkImage();
        if (mark != null && !mark.isError()) return mark;
        Image company = companyLogo();
        if (company != null && !company.isError()) return company;
        return bundledMarkImage();
    }

    /** Resolves the configured square mark (or company logo fallback) file path for OS taskbar/Dock icons. */
    public static Path resolveLocalMarkFile() {
        Path mark = configuredLocalPath("application.markImagePath");
        return mark != null ? mark : configuredLocalPath("company.logoPath");
    }

    /** Backward-compatible alias retained for older callers. */
    public static Image brandImage() { return applicationBrandImage(); }

    /** Backward-compatible alias retained for older callers. */
    public static Image logo() { return applicationBrandImage(); }

    private static Path configuredLocalPath(String configKey) {
        try {
            String local = ConfigManager.getLocalProperty(configKey, "");
            String configured = local == null ? "" : local.trim();
            if (configured.isBlank() && !Platform.isFxApplicationThread()) {
                String fallback = ConfigManager.get(configKey, "");
                configured = fallback == null ? "" : fallback.trim();
            }
            if (configured.isBlank()) return null;
            Path path = Path.of(configured).toAbsolutePath().normalize();
            return Files.isRegularFile(path) ? path : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Image configuredImage(String configKey) {
        try {
            Path path = configuredLocalPath(configKey);
            if (path == null) {
                String configured = ConfigManager.get(configKey, "").trim();
                if (configured.isBlank()) return null;
                path = Path.of(configured).toAbsolutePath().normalize();
                if (!Files.isRegularFile(path)) return null;
            }
            // Load from a fresh stream so replacing the same asset filename does
            // not leave a stale URL-cached image in a long-running application.
            try (InputStream input = Files.newInputStream(path)) {
                Image image = new Image(input);
                return image.isError() ? null : image;
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String value(String key, String fallback) {
        try {
            if (mustAvoidSharedServerReadOnFxThread()) {
                String local = ConfigManager.getLocalProperty(key, fallback);
                return local == null || local.isBlank() ? fallback : local.trim();
            }
            String configured = ConfigManager.get(key, fallback);
            return configured == null || configured.isBlank() ? fallback : configured.trim();
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
