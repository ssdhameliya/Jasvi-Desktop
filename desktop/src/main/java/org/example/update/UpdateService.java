package org.example.update;

import org.example.backup.BackupManager;
import org.example.api.runtime.ManagedPostgresRuntime;
import org.example.api.runtime.RuntimeBootstrapper;
import org.example.config.ConfigManager;
import org.example.config.WorkspaceManager;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.DoubleConsumer;
import java.util.logging.Logger;

public final class UpdateService {
    public static final String DEFAULT_VERSION="DEV";
    private static final Logger LOG=Logger.getLogger(UpdateService.class.getName());
    private final ServerReleaseClient releases=new ServerReleaseClient();
    private final HttpClient http=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(60)).build();

    public record VerifiedDownload(Path file,String checksum,boolean reusedCache) { }

    public String currentVersion(){return BuildInfo.version();}
    public UpdateRelease check() throws Exception {
        boolean beta="BETA".equalsIgnoreCase(ConfigManager.getEffectiveUpdateChannel());
        return releases.latest(beta);
    }
    public UpdateRelease byVersion(String version) throws Exception { return releases.byVersion(version); }
    public List<UpdateRelease> releases(boolean includePrerelease,int limit) throws Exception { return releases.releases(includePrerelease,limit); }
    public boolean isNewer(UpdateRelease release){return release.version().compareTo(SemanticVersion.parse(currentVersion()))>0;}
    public UpdateRelease.Asset assetFor(UpdateRelease release){return PlatformPackage.select(release).orElseThrow(()->new IllegalStateException("This release does not contain an installer for "+PlatformPackage.current()+"."));}

    /**
     * Returns only a SHA-256 verified installer. A pre-existing final installer is never trusted
     * from filename/size alone: it is verified first, otherwise both it and its partial download
     * are removed and a clean download is attempted automatically. A freshly downloaded checksum
     * mismatch is also purged and retried once from byte zero before failing closed.
     */
    @FunctionalInterface
    public interface DownloadListener {
        void onProgress(double fraction, long copiedBytes, long totalBytes, double speedBytesPerSec);
    }

    public VerifiedDownload downloadVerified(UpdateRelease release, UpdateRelease.Asset asset, DoubleConsumer progress) throws Exception {
        Objects.requireNonNull(progress, "progress");
        return downloadVerified(release, asset, (fraction, copied, total, speed) -> progress.accept(fraction));
    }

    public VerifiedDownload downloadVerified(UpdateRelease release, UpdateRelease.Asset asset, DownloadListener progress) throws Exception {
        Objects.requireNonNull(release,"release"); Objects.requireNonNull(asset,"asset"); Objects.requireNonNull(progress,"progress");
        String checksum=expectedChecksum(release,asset.name());
        if(checksum.isBlank())throw new SecurityException("The published DSE ERP release must include checksums.txt with a SHA-256 entry for "+asset.name()+".");

        Path target=downloadTarget(asset);
        cleanupDownloadedInstallers(target);
        if(Files.isRegularFile(target)){
            if(cachedInstallerMatches(target,asset.size(),checksum)){
                LOG.info("UPDATE_CACHE_CHECKSUM_OK installer="+target.getFileName());
                progress.onProgress(1d, asset.size() > 0 ? asset.size() : Files.size(target), asset.size() > 0 ? asset.size() : Files.size(target), 0d);
                return new VerifiedDownload(target,checksum,true);
            }
            LOG.warning("UPDATE_CACHE_CHECKSUM_MISMATCH installer="+target.getFileName()+"; removing cached installer and partial download");
            purgeCachedInstaller(target);
        }else{
            discardImpossiblePartial(target,asset.size());
        }

        SecurityException lastMismatch=null;
        for(int verificationAttempt=1;verificationAttempt<=2;verificationAttempt++){
            Path file=download(asset,progress);
            try{
                ChecksumVerifier.verify(file,checksum);
                LOG.info("UPDATE_CHECKSUM_OK installer="+file.getFileName()+" attempt="+verificationAttempt);
                return new VerifiedDownload(file,checksum,false);
            }catch(SecurityException mismatch){
                lastMismatch=mismatch;
                LOG.warning("UPDATE_DOWNLOADED_CHECKSUM_MISMATCH installer="+file.getFileName()+" attempt="+verificationAttempt+"; forcing clean redownload");
                purgeCachedInstaller(file);
                if(verificationAttempt<2)progress.onProgress(0d, 0L, asset.size(), 0d);
            }
        }
        throw new SecurityException("Update checksum verification failed after a clean redownload. Installation was blocked. "+message(lastMismatch),lastMismatch);
    }

    /** Downloads bytes only. Call downloadVerified for any installable release asset. */
    public Path download(UpdateRelease.Asset asset, DoubleConsumer progress) throws Exception {
        Objects.requireNonNull(progress, "progress");
        return download(asset, (fraction, copied, total, speed) -> progress.accept(fraction));
    }

    public Path download(UpdateRelease.Asset asset, DownloadListener progress) throws Exception {
        Path target=downloadTarget(asset); Path partial=partialPath(target);
        Files.deleteIfExists(target);
        discardImpossiblePartial(target,asset.size());
        Exception last=null;
        long startTime = System.nanoTime();
        for(int attempt=1;attempt<=3;attempt++){
            long existing=Files.isRegularFile(partial)?Files.size(partial):0;
            HttpRequest.Builder builder=HttpRequest.newBuilder(asset.downloadUrl()).timeout(Duration.ofMinutes(45)).header("User-Agent","DSE-ERP-Updater");
            if(existing>0)builder.header("Range","bytes="+existing+"-");
            try{
                HttpResponse<java.io.InputStream> response=http.send(builder.GET().build(),HttpResponse.BodyHandlers.ofInputStream());
                boolean resumed=existing>0&&response.statusCode()==206;
                if(response.statusCode()<200||response.statusCode()>=300)throw new IllegalStateException("Installer download failed with HTTP "+response.statusCode()+".");
                if(!resumed)existing=0;
                long total=asset.size()>0?asset.size():existing+response.headers().firstValueAsLong("Content-Length").orElse(0);
                StandardOpenOption[] options=resumed
                    ? new StandardOpenOption[]{StandardOpenOption.CREATE,StandardOpenOption.APPEND}
                    : new StandardOpenOption[]{StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING};
                long copied=existing;
                try(var in=response.body();var out=Files.newOutputStream(partial,options)){
                    byte[] b=new byte[131072];
                    int n;
                    while((n=in.read(b))>=0){
                        if(n==0)continue;
                        out.write(b,0,n);
                        copied+=n;
                        double elapsedSec = Math.max(0.001, (System.nanoTime() - startTime) / 1_000_000_000.0);
                        double speed = (copied - existing) / elapsedSec;
                        double fraction = total > 0 ? Math.min(1d, (double) copied / total) : -1d;
                        progress.onProgress(fraction, copied, total, speed);
                    }
                }
                if(asset.size()>0&&Files.size(partial)!=asset.size())throw new IllegalStateException("Installer download is incomplete ("+Files.size(partial)+" of "+asset.size()+" bytes).");
                Files.move(partial,target,StandardCopyOption.REPLACE_EXISTING);
                progress.onProgress(1d, copied, total, 0d);
                return target;
            }catch(Exception failure){last=failure;if(attempt<3)pause(attempt);}
        }
        throw new IllegalStateException("Installer download failed after 3 attempts: "+message(last),last);
    }

    public String expectedChecksum(UpdateRelease release,String assetName) throws Exception {
        Optional<UpdateRelease.Asset> checksumAsset=release.assets().stream().filter(a->{String n=a.name().toLowerCase(Locale.ROOT);return n.equals("checksums.txt")||n.equals("sha256sums.txt")||n.endsWith("-checksums.txt");}).findFirst();
        if(checksumAsset.isEmpty())return "";
        Exception last=null;
        for(int attempt=1;attempt<=3;attempt++)try{
            HttpRequest request=HttpRequest.newBuilder(checksumAsset.get().downloadUrl()).timeout(Duration.ofSeconds(90)).header("User-Agent","DSE-ERP-Updater").GET().build();
            HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if(response.statusCode()<200||response.statusCode()>=300)throw new IllegalStateException("Checksum download failed with HTTP "+response.statusCode()+".");
            for(String line:response.body().split("\\R")){String clean=line.trim();if(clean.isBlank()||clean.startsWith("#"))continue;String[] p=clean.split("\\s+",2);if(p.length==2&&p[1].replace("*","").trim().equals(assetName))return p[0];}
            return "";
        }catch(Exception failure){last=failure;if(attempt<3)pause(attempt);}
        throw new IllegalStateException("Checksum download failed after 3 attempts: "+message(last),last);
    }

    static boolean cachedInstallerMatches(Path target,long expectedSize,String checksum) throws Exception {
        if(target==null||!Files.isRegularFile(target)||checksum==null||checksum.isBlank())return false;
        if(expectedSize>0&&Files.size(target)!=expectedSize)return false;
        try{ChecksumVerifier.verify(target,checksum);return true;}catch(SecurityException mismatch){return false;}
    }

    private static final java.util.regex.Pattern INSTALLER_VERSION_PATTERN =
            java.util.regex.Pattern.compile("^(?:dse-erp|jasvi)-v?(\\d+\\.\\d+\\.\\d+(?:-(?:alpha|beta|rc)[0-9a-zA-Z.-]*)?)(?:-[^.]*)?\\.(?:exe|dmg|msi|pkg)(?:\\.part)?$", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern HELPER_VERSION_PATTERN =
            java.util.regex.Pattern.compile("^install-dse-erp-v?(\\d+\\.\\d+\\.\\d+(?:-(?:alpha|beta|rc)[0-9a-zA-Z.-]*)?)\\.(?:ps1|command)$", java.util.regex.Pattern.CASE_INSENSITIVE);

    static void purgeCachedInstaller(Path target) throws Exception {
        if(target==null)return;
        Files.deleteIfExists(target);
        Files.deleteIfExists(partialPath(target));
    }

    /**
     * Reclaims disk space after a clean application startup/upgrade by removing downloaded
     * installers and detached update helper scripts for the installed version and any older
     * versions, while preserving a pending newer installer (> currentVersion) if one was
     * downloaded for later installation.
     */
    public static void cleanupObsoleteAfterStartup(String currentVersion) {
        if (currentVersion == null || currentVersion.isBlank() || DEFAULT_VERSION.equalsIgnoreCase(currentVersion.trim())) {
            return;
        }
        SemanticVersion current;
        try {
            if (!currentVersion.trim().matches("^[vV]?\\d+\\.\\d+\\.\\d+.*")) return;
            current = SemanticVersion.parse(currentVersion);
        } catch (Exception ignored) {
            return;
        }
        int removedInstallers = 0;
        for (Path folder : candidateUpdatesFolders()) {
            removedInstallers += cleanupInstallersInFolder(folder, null, current);
        }
        int removedHelpers = cleanupHelpersInFolder(
                ConfigManager.getConfigFolder().resolve("Updates").resolve("helpers"), current);
        if (removedInstallers > 0 || removedHelpers > 0) {
            LOG.info("UPDATE_CACHE_CLEANUP_OK version=" + current + " removedInstallers=" + removedInstallers + " removedHelpers=" + removedHelpers);
        }
    }

    /** Removes all older managed installers in update folders except the active target and its .part file. */
    public static int cleanupDownloadedInstallers(Path keepInstaller) {
        int removed = 0;
        for (Path folder : candidateUpdatesFolders()) {
            removed += cleanupInstallersInFolder(folder, keepInstaller, null);
        }
        if (removed > 0) {
            LOG.info("UPDATE_PRE_DOWNLOAD_CLEANUP_OK removedInstallers=" + removed);
        }
        return removed;
    }

    static int cleanupInstallersInFolder(Path folder, Path keepInstaller, SemanticVersion maxObsoleteVersion) {
        if (folder == null || !Files.isDirectory(folder)) return 0;
        Path keepNormalized = keepInstaller == null ? null : keepInstaller.toAbsolutePath().normalize();
        Path keepPartialNormalized = keepInstaller == null ? null : partialPath(keepInstaller).toAbsolutePath().normalize();
        int removed = 0;
        try (var stream = Files.list(folder)) {
            for (Path entry : stream.toList()) {
                if (!Files.isRegularFile(entry)) continue;
                String name = entry.getFileName().toString();
                if (!isManagedInstallerArtifact(name)) continue;
                Path normalized = entry.toAbsolutePath().normalize();
                if (keepNormalized != null && (normalized.equals(keepNormalized) || normalized.equals(keepPartialNormalized))) {
                    continue;
                }
                if (maxObsoleteVersion != null) {
                    Optional<SemanticVersion> parsed = extractInstallerVersion(name);
                    if (parsed.isPresent() && parsed.get().compareTo(maxObsoleteVersion) > 0) {
                        continue; // Preserve pending newer installer not yet installed
                    }
                    if (parsed.isEmpty() && !name.toLowerCase(Locale.ROOT).endsWith(".part")) {
                        continue; // Never delete unversioned custom offline installers during startup
                    }
                }
                try {
                    if (Files.deleteIfExists(entry)) removed++;
                } catch (Exception ex) {
                    LOG.warning("UPDATE_CACHE_CLEANUP_SKIP file=" + name + " reason=" + message(ex));
                }
            }
        } catch (Exception ex) {
            LOG.warning("UPDATE_CACHE_CLEANUP_SCAN_FAILED folder=" + folder + " reason=" + message(ex));
        }
        return removed;
    }

    static int cleanupHelpersInFolder(Path helperFolder, SemanticVersion maxObsoleteVersion) {
        if (helperFolder == null || !Files.isDirectory(helperFolder)) return 0;
        int removed = 0;
        try (var stream = Files.list(helperFolder)) {
            for (Path entry : stream.toList()) {
                if (!Files.isRegularFile(entry)) continue;
                String name = entry.getFileName().toString();
                if (!isManagedHelperScript(name)) continue;
                if (maxObsoleteVersion != null) {
                    Optional<SemanticVersion> parsed = extractHelperVersion(name);
                    if (parsed.isPresent() && parsed.get().compareTo(maxObsoleteVersion) > 0) {
                        continue;
                    }
                }
                try {
                    if (Files.deleteIfExists(entry)) removed++;
                } catch (Exception ex) {
                    LOG.warning("UPDATE_HELPER_CLEANUP_SKIP file=" + name + " reason=" + message(ex));
                }
            }
        } catch (Exception ex) {
            LOG.warning("UPDATE_HELPER_CLEANUP_SCAN_FAILED folder=" + helperFolder + " reason=" + message(ex));
        }
        return removed;
    }

    static boolean isManagedInstallerArtifact(String fileName) {
        if (fileName == null || fileName.isBlank()) return false;
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("dse-erp-") && !lower.startsWith("jasvi-")) return false;
        return lower.endsWith(".exe") || lower.endsWith(".dmg") || lower.endsWith(".msi") || lower.endsWith(".pkg")
                || lower.endsWith(".exe.part") || lower.endsWith(".dmg.part") || lower.endsWith(".msi.part") || lower.endsWith(".pkg.part");
    }

    static boolean isManagedHelperScript(String fileName) {
        if (fileName == null || fileName.isBlank()) return false;
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.startsWith("install-dse-erp-") && (lower.endsWith(".ps1") || lower.endsWith(".command"));
    }

    static Optional<SemanticVersion> extractInstallerVersion(String fileName) {
        if (fileName == null) return Optional.empty();
        var matcher = INSTALLER_VERSION_PATTERN.matcher(fileName);
        return matcher.matches() ? Optional.of(SemanticVersion.parse(matcher.group(1))) : Optional.empty();
    }

    static Optional<SemanticVersion> extractHelperVersion(String fileName) {
        if (fileName == null) return Optional.empty();
        var matcher = HELPER_VERSION_PATTERN.matcher(fileName);
        return matcher.matches() ? Optional.of(SemanticVersion.parse(matcher.group(1))) : Optional.empty();
    }

    private static Set<Path> candidateUpdatesFolders() {
        Set<Path> folders = new LinkedHashSet<>();
        if (WorkspaceManager.isConfigured()) {
            folders.add(WorkspaceManager.getUpdatesFolder().toAbsolutePath().normalize());
        }
        folders.add(Path.of(System.getProperty("user.home"), ".dse-erp", "Updates").toAbsolutePath().normalize());
        return folders;
    }

    private static Path partialPath(Path target){return target.resolveSibling(target.getFileName()+".part");}

    private static void discardImpossiblePartial(Path target,long expectedSize) throws Exception {
        Path partial=partialPath(target);
        if(expectedSize>0&&Files.isRegularFile(partial)&&Files.size(partial)>=expectedSize){
            LOG.warning("UPDATE_PARTIAL_INVALID installer="+target.getFileName()+"; restarting download from byte zero");
            Files.deleteIfExists(partial);
        }
    }

    private static Path downloadTarget(UpdateRelease.Asset asset) throws Exception {
        Path folder = WorkspaceManager.isConfigured()
                ? WorkspaceManager.getUpdatesFolder()
                : Path.of(System.getProperty("user.home"), ".dse-erp", "Updates").toAbsolutePath().normalize();
        Files.createDirectories(folder);
        return folder.resolve(asset.name());
    }

    public Path createPreUpdateBackup() throws Exception {return BackupManager.createBackup("Before-Update","PRE_UPDATE");}

    public UpdateInstallerLauncher.LaunchResult launchInstaller(Path installer, String targetVersion) throws Exception {
        // The updater must never replace runtime/postgresql while the postmaster
        // is still running from that directory. This is mandatory for managed
        // workspaces and intentionally fails closed if shutdown cannot be proven.
        RuntimeBootstrapper.shutdownManagedServer();
        ManagedPostgresRuntime.shutdownForUpdate();
        return UpdateInstallerLauncher.launch(installer, targetVersion);
    }

    /** Backwards-compatible entry point used by offline updates. */
    public UpdateInstallerLauncher.LaunchResult launchInstaller(Path installer) throws Exception {
        return launchInstaller(installer, "offline");
    }

    public Path verifyOfflinePackage(Path packageFile,String checksum) throws Exception {if(packageFile==null||!Files.isRegularFile(packageFile))throw new IllegalArgumentException("Select a valid update package.");if(checksum!=null&&!checksum.isBlank())ChecksumVerifier.verify(packageFile,checksum);return packageFile;}
    private static void pause(int attempt) throws InterruptedException {Thread.sleep(attempt*1500L);}
    private static String message(Throwable failure){return failure==null?"Unknown network failure":(failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage());}
}
