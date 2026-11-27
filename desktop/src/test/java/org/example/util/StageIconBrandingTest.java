package org.example.util;

import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that Stage/taskbar icon generation produces standard square Windows/desktop
 * resolutions from arbitrary non-square marks and that startup loads configuration
 * before initializing the primary Stage icon.
 */
class StageIconBrandingTest {

    @Test
    void buildStageIconsProducesAllStandardSquareResolutionsWithoutStretching() {
        WritableImage source = new WritableImage(166, 154);
        PixelWriter writer = source.getPixelWriter();
        for (int y = 20; y < 134; y++) {
            for (int x = 20; x < 146; x++) {
                writer.setArgb(x, y, 0xFF1D4ED8);
            }
        }

        List<Image> icons = SceneManager.buildStageIcons(source);
        int[] expectedSizes = {16, 32, 48, 64, 128, 256};
        assertEquals(expectedSizes.length, icons.size());

        for (int i = 0; i < expectedSizes.length; i++) {
            int expected = expectedSizes[i];
            Image icon = icons.get(i);
            assertEquals(expected, (int) icon.getWidth());
            assertEquals(expected, (int) icon.getHeight());

            PixelReader reader = icon.getPixelReader();
            assertNotNull(reader);
            int centerAlpha = (reader.getArgb(expected / 2, expected / 2) >>> 24) & 0xFF;
            int cornerAlpha = (reader.getArgb(0, 0) >>> 24) & 0xFF;
            assertTrue(centerAlpha > 200, "Center pixel must preserve opaque mark content for size " + expected);
            assertEquals(0, cornerAlpha, "Aspect-fit padding must preserve transparent corner for size " + expected);
        }
    }

    @Test
    void startupLoadsWorkspaceConfigurationBeforeInitializingStageIcon() throws Exception {
        String mainSource = Files.readString(Path.of("src/main/java/org/example/app/Main.java"));
        int loadIndex = mainSource.indexOf("ConfigManager.load();");
        int initIndex = mainSource.indexOf("SceneManager.initialize(stage);");
        assertTrue(loadIndex >= 0 && initIndex >= 0);
        assertTrue(loadIndex < initIndex,
                "ConfigManager.load() must execute before SceneManager.initialize(stage) so primaryStage receives configured branding");

        String sceneSource = Files.readString(Path.of("src/main/java/org/example/util/SceneManager.java"));
        assertFalse(sceneSource.contains("if (primaryStage.getIcons().isEmpty()) applyStageIcon(primaryStage);"),
                "Screen transitions must refresh the stage icon rather than locking onto a pre-load fallback");
        assertTrue(sceneSource.contains("BrandingService.companyLogo()"),
                "Stage icon resolution should fall back to company logo before bundled product icon");

        String bridgeSource = Files.readString(Path.of("src/main/java/org/example/config/SharedAssetBridge.java"));
        assertTrue(bridgeSource.contains("DashboardController.refreshBranding()"),
                "Background business asset sync must refresh active shell branding and Stage icons");
        assertTrue(bridgeSource.contains("String alias = getAliasKey(key);")
                        && bridgeSource.indexOf("syncPublicBranding()") >= 0,
                "Public branding sync must preserve alias fallback before login");
    }

    @Test
    void firstRunAndNativeOsHooksSynchronizeBrandingAcrossWindowsAndMac() throws Exception {
        String mainSource = Files.readString(Path.of("src/main/java/org/example/app/Main.java"));
        int completeFirstRunIndex = mainSource.indexOf("private void completeFirstRun(Stage stage)");
        assertTrue(completeFirstRunIndex >= 0);
        String firstRunSection = mainSource.substring(completeFirstRunIndex);
        assertTrue(firstRunSection.contains("SharedAssetBridge.syncPublicBranding()"),
                "First-run setup completion must fetch public branding before Login opens");
        assertTrue(mainSource.contains("NativeAppBranding.initializeProcessIdentity()"),
                "Startup must stamp explicit process AppUserModelID before Stage show");

        String sceneSource = Files.readString(Path.of("src/main/java/org/example/util/SceneManager.java"));
        assertTrue(sceneSource.contains("NativeAppBranding.applyToPrimaryStageAsync"),
                "Stage branding transitions must keep Windows taskbar/shortcut and macOS Dock icons synchronized");
    }

    @Test
    void bundledLogoInstallerAssetsAndDashboardMenuBarBrandingAreConfigured() throws Exception {
        Path macMasterPng = Path.of("src/main/resources/installer/logo-1024.png");
        Path windowsIco = Path.of("src/main/resources/installer/DSE-ERP.ico");
        Path desktopMarkPng = Path.of("src/main/resources/logo.png");
        Path removedLegacyServerLogo = Path.of("../server/src/main/resources/logo.png");

        assertTrue(Files.isRegularFile(macMasterPng), "macOS installer master PNG must exist");
        assertTrue(Files.isRegularFile(windowsIco), "Windows installer ICO must exist");
        assertTrue(Files.isRegularFile(desktopMarkPng), "Desktop bundled mark PNG must exist");
        assertTrue(Files.notExists(removedLegacyServerLogo), "Legacy server/src/main/resources/logo.png must stay removed");

        byte[] icoBytes = Files.readAllBytes(windowsIco);
        assertTrue(icoBytes.length > 6 + 16 * 7);
        int icoType = ((icoBytes[3] & 0xFF) << 8) | (icoBytes[2] & 0xFF);
        int icoCount = ((icoBytes[5] & 0xFF) << 8) | (icoBytes[4] & 0xFF);
        assertEquals(1, icoType, "DSE-ERP.ico must have ICO type 1");
        assertTrue(icoCount >= 7, "DSE-ERP.ico must include standard 16..256 icon frames");

        for (Path pngPath : List.of(macMasterPng, desktopMarkPng)) {
            Image img;
            try (var in = Files.newInputStream(pngPath)) {
                img = new Image(in);
            }
            assertFalse(img.isError(), "Bundled logo PNG must decode cleanly: " + pngPath);
            int width = (int) img.getWidth();
            int height = (int) img.getHeight();
            assertEquals(width, height, "Bundled logo PNG must remain square: " + pngPath);
            assertTrue(width >= 512, "Bundled logo PNG must be high-resolution: " + pngPath);
            PixelReader reader = img.getPixelReader();
            assertNotNull(reader);
            int centerAlpha = (reader.getArgb(width / 2, height / 2) >>> 24) & 0xFF;
            int cornerAlpha = (reader.getArgb(0, 0) >>> 24) & 0xFF;
            assertTrue(centerAlpha > 200, "Center emblem pixel must be visible: " + pngPath);
            assertEquals(0, cornerAlpha, "Circular emblem corner must stay transparent: " + pngPath);
        }

        Image bundled = org.example.service.BrandingService.bundledMarkImage();
        assertNotNull(bundled);
        assertFalse(bundled.isError());
        assertNotNull(org.example.service.BrandingService.effectiveMarkImage());

        String dashboardFxml = Files.readString(Path.of("src/main/resources/fxml/pages/Dashboard.fxml"));
        assertTrue(dashboardFxml.contains("fx:id=\"sidebarBrandHeader\""));
        assertTrue(dashboardFxml.contains("fx:id=\"imgSidebarBrandLogo\""));
        assertTrue(dashboardFxml.contains("fx:id=\"topBarBrandLogoBox\""));
        assertTrue(dashboardFxml.contains("fx:id=\"imgTopBarBrandLogo\""));
    }
}
