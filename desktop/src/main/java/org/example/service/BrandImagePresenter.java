package org.example.service;

import javafx.geometry.Rectangle2D;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Region;
import javafx.scene.shape.Rectangle;

/**
 * Central image-geometry contract for user supplied branding assets.
 *
 * <p>Application branding is a responsive 5:1 banner. It is center-cropped
 * to that ratio and then rendered to the full available width, so the UI never
 * stretches the artwork and individual screens cannot invent their own image
 * dimensions. Logos, signatures and QR images use contain semantics and are
 * never cropped.</p>
 */
public final class BrandImagePresenter {
    public static final double APPLICATION_BANNER_RATIO = 5.0;
    private static final double DEFAULT_INSET = 14.0;

    private BrandImagePresenter() { }

    public static void applicationBanner(ImageView view, Region container) {
        configureApplicationBanner(view, container);
    }

    /** Settings preview variant: the container owns its height; the banner is fitted inside it. */
    public static void applicationBannerPreview(ImageView view, Region container) {
        configureApplicationBanner(view, container);
    }

    private static void configureApplicationBanner(ImageView view, Region container) {
        if (view == null || container == null) return;
        view.setSmooth(true);
        view.setPreserveRatio(false);
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(container.widthProperty());
        clip.heightProperty().bind(container.heightProperty());
        container.setClip(clip);
        Runnable resize = () -> {
            double width = Math.max(1.0, container.getWidth() - DEFAULT_INSET);
            double height = width / APPLICATION_BANNER_RATIO;
            double availableHeight = Math.max(1.0, container.getHeight() - DEFAULT_INSET);
            if (height > availableHeight) {
                height = availableHeight;
                width = height * APPLICATION_BANNER_RATIO;
            }
            view.setFitWidth(width);
            view.setFitHeight(height);
        };
        container.widthProperty().addListener((obs, oldValue, newValue) -> resize.run());
        container.heightProperty().addListener((obs, oldValue, newValue) -> resize.run());
        view.imageProperty().addListener((obs, oldImage, newImage) -> applyApplicationViewport(view, newImage));
        applyApplicationViewport(view, view.getImage());
        resize.run();
    }

    public static void contain(ImageView view, Region container) {
        contain(view, container, 18.0);
    }

    public static void contain(ImageView view, Region container, double totalInset) {
        if (view == null || container == null) return;
        view.setViewport(null);
        view.setSmooth(true);
        view.setPreserveRatio(true);
        Runnable resize = () -> {
            view.setFitWidth(Math.max(1.0, container.getWidth() - totalInset));
            view.setFitHeight(Math.max(1.0, container.getHeight() - totalInset));
        };
        container.widthProperty().addListener((obs, oldValue, newValue) -> resize.run());
        container.heightProperty().addListener((obs, oldValue, newValue) -> resize.run());
        resize.run();
    }

    private static void applyApplicationViewport(ImageView view, Image image) {
        if (image == null || image.isError() || image.getWidth() <= 0 || image.getHeight() <= 0) {
            view.setViewport(null);
            return;
        }

        double sourceWidth = image.getWidth();
        double sourceHeight = image.getHeight();
        double sourceRatio = sourceWidth / sourceHeight;

        if (Math.abs(sourceRatio - APPLICATION_BANNER_RATIO) < 0.001) {
            view.setViewport(new Rectangle2D(0, 0, sourceWidth, sourceHeight));
            return;
        }

        if (sourceRatio > APPLICATION_BANNER_RATIO) {
            double cropWidth = sourceHeight * APPLICATION_BANNER_RATIO;
            double x = Math.max(0, (sourceWidth - cropWidth) / 2.0);
            view.setViewport(new Rectangle2D(x, 0, cropWidth, sourceHeight));
        } else {
            double cropHeight = sourceWidth / APPLICATION_BANNER_RATIO;
            double y = Math.max(0, (sourceHeight - cropHeight) / 2.0);
            view.setViewport(new Rectangle2D(0, y, sourceWidth, cropHeight));
        }
    }

    /** Configures a cover-cropped Settings preview thumbnail for panel background images. */
    public static void panelBackgroundPreview(ImageView view, Region container) {
        if (view == null || container == null) return;
        container.setMinWidth(0.0);
        view.setManaged(false);
        view.setSmooth(true);
        view.setPreserveRatio(false);
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(container.widthProperty());
        clip.heightProperty().bind(container.heightProperty());
        container.setClip(clip);
        Runnable resize = () -> {
            double width = container.getWidth();
            double height = container.getHeight();
            if (width <= 0 || height <= 0) return;
            view.setFitWidth(width);
            view.setFitHeight(height);
            view.resizeRelocate(0.0, 0.0, width, height);
            applyCoverViewport(view, width, height);
        };
        if (!Boolean.TRUE.equals(container.getProperties().get("erp.bg.preview.bound"))) {
            container.getProperties().put("erp.bg.preview.bound", true);
            container.widthProperty().addListener((obs, oldValue, newValue) -> resize.run());
            container.heightProperty().addListener((obs, oldValue, newValue) -> resize.run());
            view.imageProperty().addListener((obs, oldImage, newImage) -> resize.run());
        }
        resize.run();
    }

    /**
     * Renders or clears the Settings-managed screen panel wallpaper inside a host StackPane.
     * Unmanaged full-bleed cover layer decoupled from container padding/layout so windows never expand.
     */
    public static void applyPanelBackground(javafx.scene.layout.StackPane host, boolean workspaceSurface,
                                            Region... transparentContentContainers) {
        if (host == null) return;
        final String layerKey = "erp.panel.bg.layer";
        boolean enabled = BrandingService.isPanelBackgroundEnabled()
                && (!workspaceSurface || BrandingService.isPanelBackgroundAppliedToWorkspace());
        Image background = enabled ? BrandingService.panelBackgroundImage() : null;
        boolean active = background != null && !background.isError();

        javafx.scene.layout.StackPane existing = host.getProperties().get(layerKey) instanceof javafx.scene.layout.StackPane sp ? sp : null;
        if (!active) {
            if (existing != null) {
                host.getChildren().remove(existing);
                host.getProperties().remove(layerKey);
            }
            host.getStyleClass().remove("has-panel-background");
            if (transparentContentContainers != null) {
                for (Region region : transparentContentContainers) {
                    if (region != null) region.getStyleClass().remove("has-panel-background");
                }
            }
            return;
        }

        javafx.scene.layout.StackPane layer = existing;
        ImageView bgView;
        Region scrim;
        if (layer == null) {
            bgView = new ImageView();
            bgView.getStyleClass().add("erp-panel-bg-image");
            bgView.setManaged(false);
            bgView.setPreserveRatio(false);
            bgView.setSmooth(true);
            bgView.setMouseTransparent(true);

            scrim = new Region();
            scrim.getStyleClass().add("erp-panel-bg-scrim");
            scrim.setManaged(false);
            scrim.setMouseTransparent(true);

            layer = new javafx.scene.layout.StackPane(bgView, scrim);
            layer.getStyleClass().add("erp-panel-bg-layer");
            layer.setMouseTransparent(true);
            layer.setManaged(false);

            Rectangle clip = new Rectangle();
            clip.widthProperty().bind(host.widthProperty());
            clip.heightProperty().bind(host.heightProperty());
            layer.setClip(clip);

            final javafx.scene.layout.StackPane targetLayer = layer;
            Runnable resize = () -> {
                double w = host.getWidth();
                double h = host.getHeight();
                if (w <= 0 || h <= 0) return;
                targetLayer.resizeRelocate(0.0, 0.0, w, h);
                bgView.setFitWidth(w);
                bgView.setFitHeight(h);
                bgView.resizeRelocate(0.0, 0.0, w, h);
                scrim.resizeRelocate(0.0, 0.0, w, h);
                applyCoverViewport(bgView, w, h);
            };
            host.widthProperty().addListener((obs, oldV, newV) -> resize.run());
            host.heightProperty().addListener((obs, oldV, newV) -> resize.run());
            bgView.imageProperty().addListener((obs, oldImg, newImg) -> resize.run());
            layer.getProperties().put("bgView", bgView);
            layer.getProperties().put("bgScrim", scrim);
            layer.getProperties().put("bgResize", resize);
            host.getProperties().put(layerKey, layer);
        } else {
            bgView = (ImageView) layer.getProperties().get("bgView");
            scrim = (Region) layer.getProperties().get("bgScrim");
        }

        if (!host.getChildren().contains(layer)) {
            host.getChildren().addFirst(layer);
        } else if (host.getChildren().getFirst() != layer) {
            layer.toBack();
        }

        bgView.setImage(background);
        double imageOpacity = BrandingService.panelBackgroundImageOpacity();
        scrim.setOpacity(Math.max(0.35, Math.min(0.90, 1.0 - imageOpacity)));
        Object resize = layer.getProperties().get("bgResize");
        if (resize instanceof Runnable r) r.run();

        if (!host.getStyleClass().contains("has-panel-background")) {
            host.getStyleClass().add("has-panel-background");
        }
        if (transparentContentContainers != null) {
            for (Region region : transparentContentContainers) {
                if (region != null && !region.getStyleClass().contains("has-panel-background")) {
                    region.getStyleClass().add("has-panel-background");
                }
            }
        }
    }

    private static void applyCoverViewport(ImageView view, double targetWidth, double targetHeight) {
        Image image = view == null ? null : view.getImage();
        if (view == null || image == null || image.isError() || image.getWidth() <= 0 || image.getHeight() <= 0
                || targetWidth <= 0 || targetHeight <= 0) {
            if (view != null) view.setViewport(null);
            return;
        }
        double sourceWidth = image.getWidth();
        double sourceHeight = image.getHeight();
        double sourceRatio = sourceWidth / sourceHeight;
        double targetRatio = targetWidth / targetHeight;
        if (Math.abs(sourceRatio - targetRatio) < 0.005) {
            view.setViewport(new Rectangle2D(0, 0, sourceWidth, sourceHeight));
            return;
        }
        if (sourceRatio > targetRatio) {
            double cropWidth = Math.max(1.0, sourceHeight * targetRatio);
            double x = Math.max(0.0, (sourceWidth - cropWidth) / 2.0);
            view.setViewport(new Rectangle2D(x, 0.0, cropWidth, sourceHeight));
        } else {
            double cropHeight = Math.max(1.0, sourceWidth / targetRatio);
            double y = Math.max(0.0, (sourceHeight - cropHeight) / 2.0);
            view.setViewport(new Rectangle2D(0.0, y, sourceWidth, cropHeight));
        }
    }
}
