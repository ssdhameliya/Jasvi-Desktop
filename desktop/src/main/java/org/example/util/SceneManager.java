package org.example.util;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import org.example.api.ApiSession;
import org.example.controller.SetupWizardController;
import org.example.controller.SplashController;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.example.theme.ThemeManager;
import org.example.service.BrandingService;
import org.example.service.SessionActivityManager;
import org.example.service.SessionService;
import org.example.config.ConfigManager;
import org.example.navigation.UnsavedChangesManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class SceneManager {

    private static final int[] STAGE_ICON_SIZES = {16, 32, 48, 64, 128, 256};

    public static Stage primaryStage;
    private static volatile String activeScreenTitle = "";



    private SceneManager() {
    }

    public static void initialize(Stage stage) {

        primaryStage = stage;
        primaryStage.setTitle(applicationTitle());
        WindowUtilsFx.applyAdaptiveMinimums(primaryStage, javafx.stage.Screen.getPrimary().getVisualBounds());
        applyStageIcon(primaryStage);
    }

    /**
     * Dynamically attaches multi-resolution square application marks to the stage titlebar and taskbar.
     * Follows the user-configured Application Mark / Splash Logo with fallback to Company Logo and bundled logos.
     */
    public static void applyStageIcon(Stage stage) {
        if (stage == null) return;
        if (Platform.isFxApplicationThread() && ConfigManager.isSharedClient() && ApiSession.isEstablished()) {
            Thread.startVirtualThread(() -> {
                List<Image> icons = resolveStageIcons();
                if (!icons.isEmpty()) {
                    Platform.runLater(() -> stage.getIcons().setAll(icons));
                }
            });
            return;
        }
        List<Image> icons = resolveStageIcons();
        if (icons.isEmpty()) return;
        if (Platform.isFxApplicationThread()) {
            stage.getIcons().setAll(icons);
        } else {
            Platform.runLater(() -> stage.getIcons().setAll(icons));
        }
    }

    private static List<Image> resolveStageIcons() {
        try {
            Image mark = BrandingService.applicationMarkImage();
            if (mark == null || mark.isError()) {
                mark = BrandingService.companyLogo();
            }
            if (mark == null || mark.isError()) {
                mark = loadClasspathImage("/installer/logo-1024.png");
            }
            if (mark == null || mark.isError()) {
                mark = loadClasspathImage("/logo.png");
            }
            return buildStageIcons(mark);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static Image loadClasspathImage(String resourcePath) {
        try (var is = ResourceLocator.open(resourcePath)) {
            if (is == null) return null;
            Image img = new Image(is);
            return img.isError() ? null : img;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Generates crisp square icon frames across standard Windows/macOS/Linux sizes
     * (16..256 px) so Windows updates both ICON_SMALL (titlebar) and ICON_BIG (taskbar),
     * even when the uploaded mark has non-square dimensions.
     */
    public static List<Image> buildStageIcons(Image source) {
        if (source == null || source.isError() || source.getWidth() <= 0 || source.getHeight() <= 0) {
            return List.of();
        }
        PixelReader reader = source.getPixelReader();
        if (reader == null) {
            return List.of(source);
        }
        int srcW = Math.max(1, (int) Math.round(source.getWidth()));
        int srcH = Math.max(1, (int) Math.round(source.getHeight()));
        List<Image> icons = new ArrayList<>(STAGE_ICON_SIZES.length);
        for (int size : STAGE_ICON_SIZES) {
            icons.add(renderSquareIcon(reader, srcW, srcH, size));
        }
        return List.copyOf(icons);
    }

    private static Image renderSquareIcon(PixelReader reader, int srcW, int srcH, int targetSize) {
        WritableImage canvas = new WritableImage(targetSize, targetSize);
        PixelWriter writer = canvas.getPixelWriter();
        double scale = Math.min((double) targetSize / srcW, (double) targetSize / srcH);
        double drawW = Math.max(1.0, srcW * scale);
        double drawH = Math.max(1.0, srcH * scale);
        double offsetX = (targetSize - drawW) / 2.0;
        double offsetY = (targetSize - drawH) / 2.0;

        for (int y = 0; y < targetSize; y++) {
            double srcY = (y + 0.5 - offsetY) / scale - 0.5;
            if (srcY < -0.5 || srcY > srcH - 0.5) continue;
            for (int x = 0; x < targetSize; x++) {
                double srcX = (x + 0.5 - offsetX) / scale - 0.5;
                if (srcX < -0.5 || srcX > srcW - 0.5) continue;
                writer.setArgb(x, y, sampleBilinear(reader, srcW, srcH, srcX, srcY));
            }
        }
        return canvas;
    }

    private static int sampleBilinear(PixelReader reader, int srcW, int srcH, double srcX, double srcY) {
        double clampedX = Math.max(0.0, Math.min(srcW - 1.0, srcX));
        double clampedY = Math.max(0.0, Math.min(srcH - 1.0, srcY));
        int x0 = (int) Math.floor(clampedX);
        int y0 = (int) Math.floor(clampedY);
        int x1 = Math.min(srcW - 1, x0 + 1);
        int y1 = Math.min(srcH - 1, y0 + 1);
        double tx = clampedX - x0;
        double ty = clampedY - y0;

        int c00 = reader.getArgb(x0, y0);
        int c10 = reader.getArgb(x1, y0);
        int c01 = reader.getArgb(x0, y1);
        int c11 = reader.getArgb(x1, y1);

        int a = interpolateChannel((c00 >>> 24) & 0xFF, (c10 >>> 24) & 0xFF, (c01 >>> 24) & 0xFF, (c11 >>> 24) & 0xFF, tx, ty);
        int r = interpolateChannel((c00 >>> 16) & 0xFF, (c10 >>> 16) & 0xFF, (c01 >>> 16) & 0xFF, (c11 >>> 16) & 0xFF, tx, ty);
        int g = interpolateChannel((c00 >>> 8) & 0xFF, (c10 >>> 8) & 0xFF, (c01 >>> 8) & 0xFF, (c11 >>> 8) & 0xFF, tx, ty);
        int b = interpolateChannel(c00 & 0xFF, c10 & 0xFF, c01 & 0xFF, c11 & 0xFF, tx, ty);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int interpolateChannel(int c00, int c10, int c01, int c11, double tx, double ty) {
        double top = c00 + (c10 - c00) * tx;
        double bottom = c01 + (c11 - c01) * tx;
        return Math.max(0, Math.min(255, (int) Math.round(top + (bottom - top) * ty)));
    }


    public static void showSetupWizard(Runnable onCompleted) {
        activeScreenTitle = "";
        try {
            var url = ResourceLocator.require("/fxml/pages/SetupWizard.fxml");
            FXMLLoader loader = new FXMLLoader(url);
            Parent root = loader.load();
            ProfessionalUiEnhancer.enhance(root);
            AuthExperienceEnhancer.enhance(root);
            SetupWizardController controller = loader.getController();
            controller.setOnCompleted(onCompleted);
            javafx.geometry.Rectangle2D usable = javafx.stage.Screen.getPrimary().getVisualBounds();
            Scene scene = new Scene(root, Math.min(1280, usable.getWidth()), Math.min(820, usable.getHeight()));
            ThemeManager.applyTheme(scene);
            PlatformUiSupport.installResponsiveClasses(scene);
            primaryStage.setScene(scene);
            applyStageIcon(primaryStage);
            primaryStage.setTitle(applicationTitle());
            if (!primaryStage.isShowing()) primaryStage.centerOnScreen();
            primaryStage.show();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to open first-run setup.", exception);
        }
    }

    /** Shows the approved full startup dashboard while configuration/database services initialize. */
    public static void showSplash() {
        activeScreenTitle = "";
        if (primaryStage != null) {
            javafx.geometry.Rectangle2D usable = WindowUtilsFx.visualBoundsFor(primaryStage);
            primaryStage.setWidth(usable.getWidth());
            primaryStage.setHeight(usable.getHeight());
            primaryStage.setX(usable.getMinX());
            primaryStage.setY(usable.getMinY());
            primaryStage.setTitle(applicationTitle() + " - Starting Up...");
        }
        load("/fxml/pages/Splash.fxml");
    }

    public static void showLogin() {
        activeScreenTitle = "";
        SessionActivityManager.stop();
        load("/fxml/pages/Login.fxml");
    }
    /** Updates the approved startup splash from background runtime bootstrap work. */
    public static void updateSplashStatus(String message) {
        updateSplashStage(inferSplashStage(message), message);
    }

    public static void updateSplashStage(int stage, String message) {
        javafx.application.Platform.runLater(() -> {
            if (primaryStage == null || primaryStage.getScene() == null) return;
            Object controller = primaryStage.getScene().getRoot().getProperties().get("dse.splash.controller");
            if (controller instanceof SplashController splash) {
                splash.updateStage(stage, message);
                return;
            }
            javafx.scene.Node node = primaryStage.getScene().lookup("#lblStatus");
            if (node instanceof javafx.scene.control.Label label) label.setText(message);
        });
    }

    public static void refreshSplashBranding() {
        javafx.application.Platform.runLater(() -> {
            if (primaryStage == null || primaryStage.getScene() == null) return;
            Object controller = primaryStage.getScene().getRoot().getProperties().get("dse.splash.controller");
            if (controller instanceof SplashController splash) {
                splash.refreshBranding();
                primaryStage.setTitle(applicationTitle() + " - Starting Up...");
            }
            applyStageIcon(primaryStage);
        });
    }

    public static void markSplashReady(String message) {
        javafx.application.Platform.runLater(() -> {
            if (primaryStage == null || primaryStage.getScene() == null) return;
            Object controller = primaryStage.getScene().getRoot().getProperties().get("dse.splash.controller");
            if (controller instanceof SplashController splash) splash.markReady(message);
        });
    }

    private static int inferSplashStage(String message) {
        String value = message == null ? "" : message.toLowerCase(java.util.Locale.ROOT);
        if (value.contains("postgres") || value.contains("database runtime")) return 2;
        if (value.contains("starting dse erp services") || value.contains("spring boot")) return 3;
        if (value.contains("schema") || value.contains("verifying")) return 4;
        if (value.contains("opening") || value.contains("services ready")) return 5;
        return 1;
    }

    public static void showRegistration() { activeScreenTitle = ""; load("/fxml/pages/Registration.fxml");}
    public static void loadEmailSettings() { load("/fxml/pages/EmailSettings.fxml"); }


    public static void showDashboard() {
        load("/fxml/pages/Dashboard.fxml");
    }

    private static void load(String fxml) {

        if (primaryStage != null && primaryStage.isShowing() && !UnsavedChangesManager.allowNavigation(fxml)) return;
        try {

            System.out.println("Loading FXML: " + fxml);

            var url = ResourceLocator.require(fxml);

            System.out.println("URL = " + url);

            FXMLLoader loader = new FXMLLoader(url);

            Parent root = loader.load();
            ProfessionalUiEnhancer.enhance(root);
            AuthExperienceEnhancer.enhance(root);
            if ("/fxml/pages/Splash.fxml".equals(fxml)) {
                root.getProperties().put("dse.splash.controller", loader.getController());
            }

            javafx.geometry.Rectangle2D usable = WindowUtilsFx.visualBoundsFor(primaryStage);
            double width = Math.min(Math.max(primaryStage.getWidth(), 960), usable.getWidth());
            double height = Math.min(Math.max(primaryStage.getHeight(), 640), usable.getHeight());
            AuthLayoutManager.prepare(root, width);
            Scene scene = new Scene(root, width, height);
            AuthLayoutManager.install(root, scene);

            ThemeManager.applyTheme(scene);

            PlatformUiSupport.installResponsiveClasses(scene);
            primaryStage.setScene(scene);
            applyStageIcon(primaryStage);
            if ("/fxml/pages/Registration.fxml".equals(fxml)) {
                var tracker = UnsavedChangesManager.track(root, "Registration");
                javafx.application.Platform.runLater(tracker::resetBaseline);
            } else if ("/fxml/pages/EmailSettings.fxml".equals(fxml)) {
                var tracker = UnsavedChangesManager.track(root, "Email / OTP Settings");
                javafx.application.Platform.runLater(tracker::resetBaseline);
            }
            if (SessionService.current() != null && "/fxml/pages/Dashboard.fxml".equals(fxml)) SessionActivityManager.install(scene);
            else if (SessionService.current() == null) SessionActivityManager.stop();
            if (!"/fxml/pages/Splash.fxml".equals(fxml)) {
                primaryStage.setTitle(applicationTitle());
            }
            if (!primaryStage.isShowing()) primaryStage.centerOnScreen();
            primaryStage.show();

        } catch (Exception e) {
            throw new IllegalStateException("Unable to load application screen: " + fxml, e);
        }

    }

    public static void updateScreenTitle(String screenTitle) {
        activeScreenTitle = screenTitle == null ? "" : screenTitle.trim();
        if (primaryStage != null) primaryStage.setTitle(applicationTitle());
    }

    public static void refreshApplicationTitle() {
        if (primaryStage != null) primaryStage.setTitle(applicationTitle());
    }

    /** Customer-facing window title. Technical package/service identifiers remain unchanged elsewhere. */
    public static String brandedWindowTitle(String screenTitle) {
        String base = BrandingService.applicationName();
        String env = ConfigManager.getDeploymentEnvironment();
        String identity = "LOCAL".equals(env) ? base : base + " [" + env + "]";
        String screen = screenTitle == null ? "" : screenTitle.trim();
        return screen.isBlank() ? identity : identity + " — " + screen;
    }

    private static String applicationTitle() {
        return brandedWindowTitle(activeScreenTitle);
    }

    public static void showPurchaseList() {

        load("/fxml/pages/PurchaseList.fxml");

    }

}
