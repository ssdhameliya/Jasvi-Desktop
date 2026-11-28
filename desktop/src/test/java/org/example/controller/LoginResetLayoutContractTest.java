package org.example.controller;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Prevents vertical compression, ellipsis truncation, and theme mismatch on Login / Reset Password screens.
 */
class LoginResetLayoutContractTest {

    private static Path resolveDesktopFile(String relativePath) {
        Path direct = Path.of(relativePath);
        if (Files.exists(direct)) {
            return direct;
        }
        Path reactor = Path.of("desktop").resolve(relativePath);
        if (Files.exists(reactor)) {
            return reactor;
        }
        throw new IllegalStateException("File not found from test working directory: " + relativePath);
    }

    @Test
    void loginFxmlWrapsAuthCardInScrollPaneWithVerticalScrolling() throws Exception {
        String fxml = Files.readString(resolveDesktopFile("src/main/resources/fxml/pages/Login.fxml"));
        assertTrue(fxml.contains("<ScrollPane"), "Login.fxml center content must be wrapped in a ScrollPane");
        assertTrue(fxml.contains("fitToWidth=\"true\""), "Login ScrollPane must fit content horizontally");
        assertTrue(fxml.contains("fitToHeight=\"true\""), "Login ScrollPane must fill viewport height when room is available");
        assertTrue(fxml.contains("hbarPolicy=\"NEVER\""), "Login ScrollPane must disable horizontal scrollbars");
        assertTrue(fxml.contains("vbarPolicy=\"AS_NEEDED\""), "Login ScrollPane must scroll vertically when reset/errors expand");
        assertTrue(fxml.contains("styleClass=\"auth-scroll-pane\""), "Login ScrollPane must use theme-aware auth-scroll-pane class");
        assertTrue(fxml.contains("fx:id=\"btnResendOtp\" text=\"Get Help\""), "MFA recovery Get Help button contract must remain intact");
    }

    @Test
    void loginAndResetLabelsPreventVerticalTruncationWithWrapAndMinHeight() throws Exception {
        String fxml = Files.readString(resolveDesktopFile("src/main/resources/fxml/pages/Login.fxml"));

        assertTrue(Pattern.compile("fx:id=\"lblBrandDescription\"[^>]*wrapText=\"true\"[^>]*minHeight=\"-Infinity\"")
                        .matcher(fxml).find(),
                "lblBrandDescription must wrap cleanly without vertical compression");
        assertTrue(Pattern.compile("Password reset is permitted once per day\\.[^>]*wrapText=\"true\"[^>]*minHeight=\"-Infinity\"")
                        .matcher(fxml).find(),
                "Reset password guidance paragraph must preserve full multi-line height without ellipsis");
        assertTrue(Pattern.compile("fx:id=\"lblMessage\"[^>]*wrapText=\"true\"[^>]*minHeight=\"-Infinity\"")
                        .matcher(fxml).find(),
                "lblMessage status/error banner must preserve full multi-line height without ellipsis");

        Matcher fieldErrors = Pattern.compile("<Label\\b[^>]*styleClass=\"field-error\"/>").matcher(fxml);
        int count = 0;
        while (fieldErrors.find()) {
            String tag = fieldErrors.group();
            assertTrue(tag.contains("wrapText=\"true\"") && tag.contains("minHeight=\"-Infinity\""),
                    "Every field-error label must enable wrapText and minHeight=-Infinity: " + tag);
            count++;
        }
        assertEquals(10, count, "All 10 field-error labels in Login.fxml must be protected against truncation");
    }

    @Test
    void lightAndDarkThemesStyleAuthScrollPaneAndAllowMultiLineAuthMessage() throws Exception {
        String light = Files.readString(resolveDesktopFile("src/main/resources/css/light-theme.css"));
        String dark = Files.readString(resolveDesktopFile("src/main/resources/css/dark-theme.css"));

        for (String theme : new String[]{light, dark}) {
            assertTrue(theme.contains(".auth-standard-shell .auth-scroll-pane"),
                    "Theme must style .auth-standard-shell .auth-scroll-pane");
            assertTrue(theme.contains(".erp-ui-standard.auth-standard-shell .auth-scroll-pane"),
                    "Theme must style .erp-ui-standard.auth-standard-shell .auth-scroll-pane");
            assertFalse(Pattern.compile("\\.auth-standard-shell\\s+\\.auth-message\\s*\\{[^}]*-fx-min-height\\s*:\\s*18px")
                            .matcher(theme).find(),
                    ".auth-standard-shell .auth-message must not hardcode -fx-min-height: 18px which overrides FXML USE_PREF_SIZE");
            assertTrue(Pattern.compile("\\.auth-standard-shell\\s+\\.auth-message\\s*\\{[^}]*-fx-wrap-text\\s*:\\s*true")
                            .matcher(theme).find(),
                    ".auth-standard-shell .auth-message must explicitly enable text wrapping");
        }
    }

    @Test
    void loginOverlayHostSitsOutsideScrollPaneAndIncidentBannerControlsDoNotClip() throws Exception {
        String fxml = Files.readString(resolveDesktopFile("src/main/resources/fxml/pages/Login.fxml"));
        String controller = Files.readString(resolveDesktopFile("src/main/java/org/example/controller/LoginController.java"));
        String enhancer = Files.readString(resolveDesktopFile("src/main/java/org/example/util/ProfessionalUiEnhancer.java"));

        int rootPaneIdx = fxml.indexOf("<StackPane fx:id=\"rootPane\">");
        int scrollIdx = fxml.indexOf("<ScrollPane");
        assertTrue(rootPaneIdx >= 0 && scrollIdx > rootPaneIdx,
                "Login center ScrollPane must sit inside an un-scrolled StackPane#rootPane so loading overlays never scroll or clip");

        assertTrue(fxml.contains("fx:id=\"lblIncidentIcon\" text=\"🚨\" minWidth=\"-Infinity\""));
        assertTrue(fxml.contains("fx:id=\"lblIncidentTicketBadge\" text=\"Ticket #SEC-000000\" minWidth=\"-Infinity\""));
        assertTrue(fxml.contains("fx:id=\"btnCopyTicket\" text=\"Copy\" minWidth=\"-Infinity\""));
        assertTrue(fxml.contains("fx:id=\"btnCheckUnlockStatus\" text=\"Check Status Now\" minWidth=\"-Infinity\""));
        assertTrue(fxml.contains("fx:id=\"lblPollerStatus\"") && fxml.contains("wrapText=\"true\" minHeight=\"-Infinity\""));

        assertTrue(controller.contains("btnCheckUnlockStatus.getProperties().put(\"erp.refresh.managed\", Boolean.TRUE)"));
        assertTrue(controller.contains("UiTaskExecutor.submitActionWithOverlay(rootPane, \"Checking ticket & unlock status…\""));
        assertTrue(enhancer.contains("Boolean.TRUE.equals(button.getProperties().get(\"erp.refresh.managed\"))"),
                "Pre-managed refresh controls must skip the blind 950ms generic refresh timer");
    }
}
