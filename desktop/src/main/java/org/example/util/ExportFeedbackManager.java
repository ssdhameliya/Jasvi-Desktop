package org.example.util;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;

import java.awt.Desktop;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Universal Post-Export and Real-Time Feedback Manager for Jasvi ERP.
 * Provides interactive notification cards allowing users to instantly Open File,
 * Show in Windows Explorer, or Dismiss, along with pre-flight file lock checks.
 */
public final class ExportFeedbackManager {

    private ExportFeedbackManager() {}

    /**
     * Resolves a clean, structured export file path under the workspace drive:
     * D:\DSE Production\Exports\{module}\{financialYear}\{baseName}_{timestamp}.{extension}
     * Completely eliminates intrusive OS folder picker prompts.
     */
    public static File resolveWorkspaceExportFile(String module, String baseName, String extension) {
        try {
            return org.example.config.WorkspaceStorageManager.structuredExportFile(module, baseName, extension).toFile();
        } catch (Exception ex) {
            Path fallback = java.nio.file.Path.of(System.getProperty("user.home"), "Downloads");
            String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String ext = extension == null || extension.isBlank() ? "xlsx" : extension.replace(".", "");
            return fallback.resolve(baseName + "_" + stamp + "." + ext).toFile();
        }
    }

    /**
     * Checks if target file is writeable or locked by an external application (e.g. Microsoft Excel).
     * Returns true if file can be safely written, false if user should be alerted.
     */
    public static boolean checkFileWriteable(Object owner, File file) {
        if (file == null) return false;
        if (!file.exists()) return true;

        try (FileOutputStream fos = new FileOutputStream(file, true)) {
            return true;
        } catch (IOException ex) {
            Platform.runLater(() -> AppDialogService.warning(
                    owner,
                    "File Locked by Another Application",
                    "Cannot Overwrite File",
                    "The file '" + file.getName() + "' is currently open in Microsoft Excel or another program.\n\n"
                            + "Please close the file in Excel or choose a different file name, then try exporting again."
            ));
            return false;
        }
    }

    /**
     * Shows an interactive post-export completion dialog with [Open File], [Show in Folder], and [Close].
     */
    public static void notifyExportSuccess(Object owner, File file, String title, int recordCount) {
        if (file == null || !file.exists()) return;

        Platform.runLater(() -> {
            String cleanTitle = title != null && !title.isBlank() ? title : "Export Completed";
            String countText = recordCount > 0 ? " (" + String.format("%,d", recordCount) + " records)" : "";
            String sizeText = formatFileSize(file.length());

            String heading = "File Saved Successfully";
            String message = "Exported" + countText + " to:\n"
                    + file.getName() + " [" + sizeText + "]\n\n"
                    + "Directory: " + file.getParent();

            ButtonType btnOpen = new ButtonType("Open File", ButtonBar.ButtonData.OK_DONE);
            ButtonType btnFolder = new ButtonType("Show in Folder", ButtonBar.ButtonData.OTHER);
            ButtonType btnClose = new ButtonType("Close", ButtonBar.ButtonData.CANCEL_CLOSE);

            OwnedDialog<ButtonType> dialog = new OwnedDialog<>(owner);
            AppDialogRenderer.configureMessage(dialog, "complete", cleanTitle, heading, message, null);
            DialogPane pane = dialog.getDialogPane();
            pane.getButtonTypes().setAll(btnOpen, btnFolder, btnClose);

            Optional<ButtonType> choice = dialog.showAndWait();

            if (choice.isPresent()) {
                if (choice.get() == btnOpen) {
                    openFile(file);
                } else if (choice.get() == btnFolder) {
                    showInFolder(file);
                }
            }
        });
    }

    /**
     * Convenience overload without row count.
     */
    public static void notifyExportSuccess(Object owner, File file, String title) {
        notifyExportSuccess(owner, file, title, -1);
    }

    /**
     * Opens the file in the operating system's default viewer (Excel, Acrobat, etc.).
     */
    public static void openFile(File file) {
        if (file == null || !file.exists()) return;
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(file);
            }
        } catch (Exception ex) {
            java.lang.System.getLogger("org.example.export").log(
                    java.lang.System.Logger.Level.WARNING,
                    "Unable to auto-open exported file: " + file.getAbsolutePath(),
                    ex
            );
        }
    }

    /**
     * Opens Windows Explorer with the exported file selected and highlighted.
     */
    public static void showInFolder(File file) {
        if (file == null || !file.exists()) return;
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                new ProcessBuilder("explorer.exe", "/select,", file.getAbsolutePath()).start();
            } else if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(file.getParentFile());
            }
        } catch (Exception ex) {
            java.lang.System.getLogger("org.example.export").log(
                    java.lang.System.Logger.Level.WARNING,
                    "Unable to open directory in file explorer: " + file.getAbsolutePath(),
                    ex
            );
        }
    }

    private static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }
}
