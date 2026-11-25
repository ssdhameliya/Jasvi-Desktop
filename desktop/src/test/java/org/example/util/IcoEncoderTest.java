package org.example.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies pure-Java multi-resolution PNG-in-ICO encoding and deterministic
 * per-workspace AppUserModelID generation used by OS-level taskbar/Dock branding.
 */
class IcoEncoderTest {

    @Test
    void writeMultiResIcoGeneratesStandardSquareFramesFromRectangularMark(@TempDir Path tempDir) throws Exception {
        Path source = tempDir.resolve("custom-mark.png");
        BufferedImage rect = new BufferedImage(320, 180, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = rect.createGraphics();
        g.setColor(new Color(0x1D, 0x4E, 0xD8, 255));
        g.fillRect(0, 0, 320, 180);
        g.dispose();
        assertTrue(ImageIO.write(rect, "png", source.toFile()));

        Path ico = tempDir.resolve("out").resolve("app-mark.ico");
        assertTrue(IcoEncoder.writeMultiResIco(source, ico));
        assertTrue(Files.isRegularFile(ico));

        byte[] bytes = Files.readAllBytes(ico);
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0, buf.getShort());
        assertEquals(1, buf.getShort());
        assertEquals(IcoEncoder.STANDARD_SIZES.length, buf.getShort());

        for (int expectedSize : IcoEncoder.STANDARD_SIZES) {
            int width = Byte.toUnsignedInt(buf.get());
            int height = Byte.toUnsignedInt(buf.get());
            buf.get(); // colorCount
            buf.get(); // reserved
            assertEquals(1, buf.getShort());
            assertEquals(32, buf.getShort());
            int payloadBytes = buf.getInt();
            int offset = buf.getInt();

            int resolvedWidth = width == 0 ? 256 : width;
            int resolvedHeight = height == 0 ? 256 : height;
            assertEquals(expectedSize, resolvedWidth);
            assertEquals(expectedSize, resolvedHeight);
            assertTrue(payloadBytes > 8);
            assertTrue(offset >= 6 + 16 * IcoEncoder.STANDARD_SIZES.length);
            assertEquals((byte) 0x89, bytes[offset]);
            assertEquals((byte) 'P', bytes[offset + 1]);
            assertEquals((byte) 'N', bytes[offset + 2]);
            assertEquals((byte) 'G', bytes[offset + 3]);
        }
    }

    @Test
    void buildAppUserModelIdIsDeterministicDistinctPerWorkspaceAndBounded(@TempDir Path tempDir) {
        Path wsA = tempDir.resolve("TenantA");
        Path wsB = tempDir.resolve("TenantB");

        String aUat1 = NativeAppBranding.buildAppUserModelId(wsA, "UAT");
        String aUat2 = NativeAppBranding.buildAppUserModelId(wsA.resolve("."), "UAT");
        String bUat = NativeAppBranding.buildAppUserModelId(wsB, "UAT");
        String aProd = NativeAppBranding.buildAppUserModelId(wsA, "PROD");

        assertEquals(aUat1, aUat2);
        assertNotEquals(aUat1, bUat);
        assertNotEquals(aUat1, aProd);
        assertTrue(aUat1.length() <= 64);
        assertTrue(aUat1.matches("[A-Za-z0-9.]+"));
    }
}
