package org.example.util;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java square resampler and multi-resolution PNG-in-ICO encoder used for
 * Windows taskbar/shortcut branding and macOS Dock image sizing.
 */
public final class IcoEncoder {

    public static final int[] STANDARD_SIZES = {16, 24, 32, 48, 64, 128, 256};

    private IcoEncoder() { }

    /** Reads an image file and renders an aspect-fitted square ARGB frame. */
    public static BufferedImage readSquareBufferedImage(Path sourceImage, int size) throws IOException {
        if (sourceImage == null || !Files.isRegularFile(sourceImage) || size <= 0) return null;
        BufferedImage decoded = ImageIO.read(sourceImage.toFile());
        return decoded == null ? null : renderSquareFrame(decoded, size);
    }

    /** Reads a classpath image stream and renders an aspect-fitted square ARGB frame. */
    public static BufferedImage readSquareBufferedImage(InputStream stream, int size) throws IOException {
        if (stream == null || size <= 0) return null;
        BufferedImage decoded = ImageIO.read(stream);
        return decoded == null ? null : renderSquareFrame(decoded, size);
    }

    /** Encodes a multi-resolution Windows ICO containing 16..256 px PNG frames. */
    public static boolean writeMultiResIco(Path sourceImage, Path outputIco) throws IOException {
        if (sourceImage == null || outputIco == null || !Files.isRegularFile(sourceImage)) return false;
        BufferedImage decoded = ImageIO.read(sourceImage.toFile());
        if (decoded == null || decoded.getWidth() <= 0 || decoded.getHeight() <= 0) return false;

        List<Frame> frames = new ArrayList<>(STANDARD_SIZES.length);
        for (int size : STANDARD_SIZES) {
            BufferedImage square = renderSquareFrame(decoded, size);
            ByteArrayOutputStream png = new ByteArrayOutputStream(4096);
            if (!ImageIO.write(square, "png", png)) return false;
            frames.add(new Frame(size, png.toByteArray()));
        }

        byte[] icoBytes = assembleIco(frames);
        Path parent = outputIco.toAbsolutePath().normalize().getParent();
        if (parent != null) Files.createDirectories(parent);

        Path temp = Files.createTempFile(parent == null ? Path.of(".") : parent, ".app-mark-", ".ico.tmp");
        try {
            Files.write(temp, icoBytes);
            try {
                Files.move(temp, outputIco, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, outputIco, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } finally {
            try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
        }
    }

    static BufferedImage renderSquareFrame(BufferedImage source, int targetSize) {
        int srcW = Math.max(1, source.getWidth());
        int srcH = Math.max(1, source.getHeight());
        double scale = Math.min((double) targetSize / srcW, (double) targetSize / srcH);
        int drawW = Math.max(1, (int) Math.round(srcW * scale));
        int drawH = Math.max(1, (int) Math.round(srcH * scale));
        int x = (targetSize - drawW) / 2;
        int y = (targetSize - drawH) / 2;

        BufferedImage canvas = new BufferedImage(targetSize, targetSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(source, x, y, drawW, drawH, null);
        } finally {
            g.dispose();
        }
        return canvas;
    }

    private static byte[] assembleIco(List<Frame> frames) {
        int headerSize = 6;
        int dirEntrySize = 16;
        int totalPayload = 0;
        for (Frame frame : frames) totalPayload += frame.pngBytes().length;

        ByteBuffer buf = ByteBuffer.allocate(headerSize + frames.size() * dirEntrySize + totalPayload)
                .order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 0);
        buf.putShort((short) 1);
        buf.putShort((short) frames.size());

        int offset = headerSize + frames.size() * dirEntrySize;
        for (Frame frame : frames) {
            byte dim = frame.size() >= 256 ? (byte) 0 : (byte) frame.size();
            buf.put(dim);
            buf.put(dim);
            buf.put((byte) 0);
            buf.put((byte) 0);
            buf.putShort((short) 1);
            buf.putShort((short) 32);
            buf.putInt(frame.pngBytes().length);
            buf.putInt(offset);
            offset += frame.pngBytes().length;
        }
        for (Frame frame : frames) {
            buf.put(frame.pngBytes());
        }
        return buf.array();
    }

    private record Frame(int size, byte[] pngBytes) { }
}
