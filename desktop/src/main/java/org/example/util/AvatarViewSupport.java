package org.example.util;

import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Central helper for encoding, caching, and rendering circular user profile avatars. */
public final class AvatarViewSupport {
    private static final Map<String, Image> IMAGE_CACHE = new ConcurrentHashMap<>();
    private static final int AVATAR_EXPORT_SIZE = 256;

    private AvatarViewSupport() {}

    public static Image decodeImage(String rawAvatarData) {
        if (rawAvatarData == null || rawAvatarData.isBlank()) return null;
        String clean = stripDataPrefix(rawAvatarData);
        if (clean.isBlank()) return null;
        String key = shortHash(clean);
        return IMAGE_CACHE.computeIfAbsent(key, k -> {
            try {
                byte[] bytes = Base64.getDecoder().decode(clean);
                if (bytes.length == 0) return null;
                Image img = new Image(new ByteArrayInputStream(bytes));
                return img.isError() ? null : img;
            } catch (Exception ex) {
                return null;
            }
        });
    }

    public static StackPane createCircularAvatar(String rawAvatarData, double size) {
        Image img = decodeImage(rawAvatarData);
        if (img == null) return null;
        ImageView iv = new ImageView(img);
        configureCircularImageView(iv, size);
        StackPane wrap = new StackPane(iv);
        wrap.setMinSize(size, size);
        wrap.setPrefSize(size, size);
        wrap.setMaxSize(size, size);
        return wrap;
    }

    public static boolean applyToImageView(ImageView iv, String rawAvatarData, double size) {
        if (iv == null) return false;
        Image img = decodeImage(rawAvatarData);
        if (img == null) {
            iv.setImage(null);
            iv.setVisible(false);
            iv.setManaged(false);
            return false;
        }
        iv.setImage(img);
        configureCircularImageView(iv, size);
        iv.setVisible(true);
        iv.setManaged(true);
        return true;
    }

    private static void configureCircularImageView(ImageView iv, double size) {
        iv.setFitWidth(size);
        iv.setFitHeight(size);
        iv.setPreserveRatio(false);
        iv.setSmooth(true);
        double r = size / 2.0;
        iv.setClip(new Circle(r, r, r));
    }

    /** Reads a user-chosen image file, center-crops it to a 256x256 PNG, and returns base64 text. */
    public static String encodeFileToPngBase64(File file) throws Exception {
        if (file == null || !file.isFile()) {
            throw new IllegalArgumentException("Choose a valid image file.");
        }
        BufferedImage src = ImageIO.read(file);
        if (src == null) {
            throw new IllegalArgumentException("Unsupported image file. Choose PNG, JPG, or JPEG.");
        }
        int w = src.getWidth();
        int h = src.getHeight();
        int crop = Math.min(w, h);
        int x = Math.max(0, (w - crop) / 2);
        int y = Math.max(0, (h - crop) / 2);

        BufferedImage scaled = new BufferedImage(AVATAR_EXPORT_SIZE, AVATAR_EXPORT_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(src, 0, 0, AVATAR_EXPORT_SIZE, AVATAR_EXPORT_SIZE, x, y, x + crop, y + crop, null);
        } finally {
            g.dispose();
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(scaled, "png", out);
        byte[] bytes = out.toByteArray();
        if (bytes.length > 512 * 1024) {
            throw new IllegalArgumentException("Compressed avatar exceeds 512 KB limit.");
        }
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String stripDataPrefix(String raw) {
        String s = raw.trim();
        int comma = s.indexOf(',');
        if (s.startsWith("data:") && comma >= 0) {
            s = s.substring(comma + 1).trim();
        }
        return s;
    }

    private static String shortHash(String val) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(val.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception ex) {
            return String.valueOf(val.hashCode());
        }
    }
}
