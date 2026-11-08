package org.example.update;

import org.example.service.BrandingService;

/**
 * Small offline fallback for the in-app What's New dialog. Running-build release notes are
 * application-owned so LOCAL/UAT/PROD show the same user-facing summary.
 */
public final class ReleaseHighlights {
    private ReleaseHighlights() { }

    public static String forVersion(String version) {
        if (BuildInfo.version().equals(version)) {
            return BrandingService.applicationName() + " " + version + " — Universal Keyboard Navigation, Modal Modernization & Dropdown Search\n\n" + """
                    • Universal Instant Search Dropdowns: Restored instant automatic dropdown opening on typing across 199 comboboxes application-wide without input hijacking or character swallowing. Backing lists dynamically synchronize with async REST queries, and dropdown selection cleanly populates input fields.
                    • Universal Keyboard Workflow & High-Speed Tabbing: Tab and Enter key traversal moves logically through transaction forms (Search → Date → Account/Party → Line Items → Quantities/Rates → Notes → Save). All text fields, currency cells, and combobox editors auto-select on focus with high-visibility glowing borders.
                    • Universal Modal Loading Overlay Engine: Integrated non-blocking semi-transparent dimming backdrops, 3D frosted floating cards, and spinning loaders across all 79 FXML screens and modal dialogs with 1-line async integration.
                    • Complete Ecosystem Dialog & Drawer Unification: Standardized all transactional and administrative dialogs (Audit Trail, System Diagnostics, User Dialog, Lookup Dialog, Party Dialog, Item Dialog) to the canonical desktop window shell with top accent bar, circular semantic icon box, draggable header, and zero text or button clipping.
                    • Privacy-Hardened Enterprise Workstation Remote Gateway: Browsing Cloudflare tunnel endpoints displays an encrypted gateway status dashboard with active version, PostgreSQL connectivity, and TLS 1.3 verification with JWT authentication.
                    • Customer-facing branding follows Company Name centrally across the application shell, reports, PDFs and email surfaces while technical DSE package/API/database identifiers remain unchanged.
                    • Operational Navigation Parity: Direct shortcut routing to Sales Register, Purchase Register, Item Master, and Diagnostics ledger with real-time status indicators.
                    """;
        }
        return BrandingService.applicationName() + " " + version + "\n\nRelease notes are unavailable offline for this version.";
    }

    public static String resolve(String version, String onlineNotes) {
        String fallback = forVersion(version);
        // The running build owns its customer-facing release summary so LOCAL/UAT/PROD stay consistent
        // and cannot regress to stale or auto-generated GitHub release bodies.
        if (BuildInfo.version().equals(version)) return fallback;
        String online = onlineNotes == null ? "" : onlineNotes.strip();
        long meaningfulLines = online.lines().map(String::strip).filter(value -> !value.isBlank()).count();
        String lower = online.toLowerCase(java.util.Locale.ROOT);
        boolean generatedGitHubSummary = lower.contains("## what's changed")
                || lower.contains("**full changelog**")
                || (lower.contains("github.com/") && lower.contains("/pull/"));
        if (online.isBlank() || meaningfulLines < 3 || generatedGitHubSummary) return fallback;
        return online;
    }
}
