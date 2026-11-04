package org.example.tally;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Windows native OCR integration leveraging Windows 10/11 WinRT OcrEngine (Windows.Media.Ocr.OcrEngine).
 * Provides fast, zero-dependency on-device text recognition for invoice images and scanned PDF pages.
 */
public final class WindowsOcrEngine {
    private static final Logger log = LoggerFactory.getLogger(WindowsOcrEngine.class);
    private static final boolean IS_WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("win");

    private WindowsOcrEngine() {}

    public static boolean isAvailable() {
        return IS_WINDOWS;
    }

    /**
     * Extracts text lines from an image file (.png, .jpg, .jpeg) using native Windows OCR.
     */
    public static List<String> extractTextLines(File imageFile) {
        if (!IS_WINDOWS || imageFile == null || !imageFile.exists()) {
            return Collections.emptyList();
        }

        List<String> lines = new ArrayList<>();
        try {
            String sanitizedPath = imageFile.getAbsolutePath().replace("'", "''");
            String script = "$ErrorActionPreference = 'Stop';" +
                    "Add-Type -AssemblyName System.Runtime.WindowsRuntime;" +
                    "$asTaskGeneric = [System.WindowsRuntimeSystemExtensions].GetMethods() | ? { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' };" +
                    "function Await($t, $r) { $m = $asTaskGeneric.MakeGenericMethod($r); $tk = $m.Invoke($null, @($t)); $tk.Wait(-1) | Out-Null; $tk.Result };" +
                    "[Windows.Storage.StorageFile, Windows.Storage, ContentType = WindowsRuntime] | Out-Null;" +
                    "[Windows.Graphics.Imaging.BitmapDecoder, Windows.Graphics.Imaging, ContentType = WindowsRuntime] | Out-Null;" +
                    "[Windows.Media.Ocr.OcrEngine, Windows.Media.Ocr, ContentType = WindowsRuntime] | Out-Null;" +
                    "$f = Await ([Windows.Storage.StorageFile]::GetFileFromPathAsync('" + sanitizedPath + "')) ([Windows.Storage.StorageFile]);" +
                    "$s = Await ($f.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream]);" +
                    "$d = Await ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($s)) ([Windows.Graphics.Imaging.BitmapDecoder]);" +
                    "$b = Await ($d.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap]);" +
                    "$e = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages();" +
                    "$res = Await ($e.RecognizeAsync($b)) ([Windows.Media.Ocr.OcrResult]);" +
                    "foreach ($l in $res.Lines) { Write-Output $l.Text }";

            ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isBlank()) {
                        lines.add(line);
                    }
                }
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.warn("Windows OCR process exited with code {} for {}", exitCode, imageFile.getName());
            }
        } catch (Exception ex) {
            log.warn("Windows OCR execution failed for {}: {}", imageFile.getName(), ex.getMessage());
        }
        return lines;
    }

    /**
     * Renders a BufferedImage to a temporary PNG file and extracts text lines using native Windows OCR.
     */
    public static List<String> extractTextLines(BufferedImage image) {
        if (!IS_WINDOWS || image == null) {
            return Collections.emptyList();
        }

        File tempFile = null;
        try {
            tempFile = File.createTempFile("dse-ocr-page-", ".png");
            ImageIO.write(image, "png", tempFile);
            return extractTextLines(tempFile);
        } catch (Exception ex) {
            log.warn("Failed to render buffered image for OCR: {}", ex.getMessage());
            return Collections.emptyList();
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile.toPath());
                } catch (Exception ignored) {}
            }
        }
    }
}
