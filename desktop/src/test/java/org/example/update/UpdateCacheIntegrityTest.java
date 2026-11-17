package org.example.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Regression for the 10.0.11 stale-installer same-size cache failure. */
class UpdateCacheIntegrityTest {
    @TempDir Path temp;

    @Test
    void sameSizeWrongInstallerIsNeverTrustedAndPurgeRemovesPartialToo() throws Exception {
        Path installer=temp.resolve("DSE-ERP-current-Windows-x64.exe");
        byte[] official="official-installer".getBytes();
        byte[] stale="stale----installer".getBytes();
        assertEquals(official.length,stale.length,"fixture must reproduce same-size stale cache");
        Files.write(installer,stale);
        Files.writeString(installer.resolveSibling(installer.getFileName()+".part"),"partial");
        String expected=sha256(temp.resolve("official.bin"),official);

        assertFalse(UpdateService.cachedInstallerMatches(installer,official.length,expected));
        UpdateService.purgeCachedInstaller(installer);
        assertFalse(Files.exists(installer));
        assertFalse(Files.exists(installer.resolveSibling(installer.getFileName()+".part")));
    }

    @Test
    void correctlyHashedCachedInstallerCanBeReused() throws Exception {
        Path installer=temp.resolve("DSE-ERP-current-macOS-arm64.dmg");
        byte[] official="verified-installer".getBytes();
        Files.write(installer,official);
        String expected=ChecksumVerifier.sha256(installer);
        assertTrue(UpdateService.cachedInstallerMatches(installer,official.length,expected));
    }

    @Test
    void startupCleanupRemovesCurrentAndOlderInstallersButPreservesPendingNewerAndUserFiles() throws Exception {
        Path updates = Files.createDirectories(temp.resolve("Updates"));
        Path olderExe = Files.writeString(updates.resolve("DSE-ERP-2.0.0-Windows-x64.exe"), "old-exe");
        Path currentExe = Files.writeString(updates.resolve("DSE-ERP-2.0.2-Windows-x64.exe"), "current-exe");
        Path currentPart = Files.writeString(updates.resolve("DSE-ERP-2.0.2-Windows-x64.exe.part"), "part");
        Path olderDmg = Files.writeString(updates.resolve("DSE-ERP-2.0.1-macOS-arm64.dmg"), "old-dmg");
        Path pendingNewer = Files.writeString(updates.resolve("DSE-ERP-2.0.3-Windows-x64.exe"), "newer-exe");
        Path unrelatedFile = Files.writeString(updates.resolve("my-other-tool.exe"), "keep");
        Path notesFile = Files.writeString(updates.resolve("notes.txt"), "keep");

        int removed = UpdateService.cleanupInstallersInFolder(updates, null, SemanticVersion.parse("2.0.2"));

        assertEquals(4, removed);
        assertFalse(Files.exists(olderExe));
        assertFalse(Files.exists(currentExe));
        assertFalse(Files.exists(currentPart));
        assertFalse(Files.exists(olderDmg));
        assertTrue(Files.exists(pendingNewer), "pending newer update should be preserved");
        assertTrue(Files.exists(unrelatedFile), "non-DSE user files must never be deleted");
        assertTrue(Files.exists(notesFile), "non-installer files must never be deleted");
    }

    @Test
    void preDownloadCleanupRemovesStaleInstallersWhileKeepingActiveTargetAndPart() throws Exception {
        Path updates = Files.createDirectories(temp.resolve("PreDownload"));
        Path stale1 = Files.writeString(updates.resolve("DSE-ERP-2.0.0-Windows-x64.exe"), "stale1");
        Path stale2 = Files.writeString(updates.resolve("DSE-ERP-2.0.1-Windows-x64.exe"), "stale2");
        Path target = Files.writeString(updates.resolve("DSE-ERP-2.0.2-Windows-x64.exe"), "active");
        Path targetPart = Files.writeString(updates.resolve("DSE-ERP-2.0.2-Windows-x64.exe.part"), "resume");

        int removed = UpdateService.cleanupInstallersInFolder(updates, target, null);

        assertEquals(2, removed);
        assertFalse(Files.exists(stale1));
        assertFalse(Files.exists(stale2));
        assertTrue(Files.exists(target));
        assertTrue(Files.exists(targetPart));
    }

    @Test
    void helperCleanupRemovesObsoleteHelperScriptsOnly() throws Exception {
        Path helpers = Files.createDirectories(temp.resolve("helpers"));
        Path oldPs1 = Files.writeString(helpers.resolve("install-dse-erp-2.0.1.ps1"), "old");
        Path currentPs1 = Files.writeString(helpers.resolve("install-dse-erp-2.0.2.ps1"), "current");
        Path oldMac = Files.writeString(helpers.resolve("install-dse-erp-2.0.0.command"), "old");
        Path newerPs1 = Files.writeString(helpers.resolve("install-dse-erp-2.0.3.ps1"), "newer");
        Path customScript = Files.writeString(helpers.resolve("custom-helper.ps1"), "keep");

        int removed = UpdateService.cleanupHelpersInFolder(helpers, SemanticVersion.parse("2.0.2"));

        assertEquals(3, removed);
        assertFalse(Files.exists(oldPs1));
        assertFalse(Files.exists(currentPs1));
        assertFalse(Files.exists(oldMac));
        assertTrue(Files.exists(newerPs1));
        assertTrue(Files.exists(customScript));
    }

    private static String sha256(Path file,byte[] bytes) throws Exception {
        Files.write(file,bytes);
        return ChecksumVerifier.sha256(file);
    }
}
