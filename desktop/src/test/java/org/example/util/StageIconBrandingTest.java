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
    }
}
