package org.example.util;

import javafx.stage.Stage;
import org.example.config.ConfigManager;
import org.example.config.WorkspaceManager;
import org.example.service.BrandingService;

import java.awt.GraphicsEnvironment;
import java.awt.Taskbar;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bridges desktop Settings branding onto OS-level taskbar, shortcut, and Dock surfaces
 * beyond what JavaFX Stage.getIcons() controls on packaged Windows/macOS builds.
 */
public final class NativeAppBranding {

    private static final System.Logger LOG = System.getLogger("org.example.branding");
    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    private static final String AUMID_PREFIX = "Jasvi.DSEERP.";
    private static final int WM_SETICON = 0x0080;
    private static final long ICON_SMALL = 0L;
    private static final long ICON_BIG = 1L;
    private static final int IMAGE_ICON = 1;
    private static final int LR_LOADFROMFILE = 0x0010;
    private static final int SM_CXICON = 11;
    private static final int SM_CYICON = 12;
    private static final int SM_CXSMICON = 49;
    private static final int SM_CYSMICON = 50;
    private static final int GW_OWNER = 4;
    private static final int COINIT_APARTMENTTHREADED = 0x2;
    private static final int CLSCTX_INPROC_SERVER = 0x1;
    private static final int STGM_READ = 0;
    private static final int STGM_READWRITE = 2;
    private static final short VT_EMPTY = 0;
    private static final short VT_LPWSTR = 31;
    private static final int SHCNE_ASSOCCHANGED = 0x08000000;
    private static final int SHCNF_IDLIST = 0x0000;

    private static final UUID CLSID_SHELL_LINK = UUID.fromString("00021401-0000-0000-C000-000000000046");
    private static final UUID IID_ISHELL_LINK_W = UUID.fromString("000214F9-0000-0000-C000-000000000046");
    private static final UUID IID_IPERSIST_FILE = UUID.fromString("0000010B-0000-0000-C000-000000000046");
    private static final UUID IID_IPROPERTY_STORE = UUID.fromString("886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99");
    private static final UUID FMTID_APP_USER_MODEL = UUID.fromString("9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3");

    private static final int PID_RELAUNCH_COMMAND = 2;
    private static final int PID_RELAUNCH_ICON_RESOURCE = 3;
    private static final int PID_RELAUNCH_DISPLAY_NAME = 4;
    private static final int PID_APP_USER_MODEL_ID = 5;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = Thread.ofVirtual().name("native-app-branding").unstarted(runnable);
        thread.setDaemon(true);
        return thread;
    });

    private static volatile String processAppUserModelId;
    private static volatile String lastAppliedMarkSignature;
    private static volatile String lastAppliedWindowSignature;
    private static volatile String lastShortcutSignature;
    private static long lastBigHicon;
    private static long lastSmallHicon;

    private NativeAppBranding() { }

    public static boolean isWindows() { return OS.contains("win"); }

    public static boolean isMac() { return OS.contains("mac"); }

    /** Deterministic Windows AppUserModelID isolated per workspace and environment. */
    public static String buildAppUserModelId(Path workspaceRoot, String deploymentEnv) {
        String env = sanitizeToken(deploymentEnv == null || deploymentEnv.isBlank() ? "LOCAL" : deploymentEnv, "LOCAL");
        if (workspaceRoot == null) {
            return AUMID_PREFIX + env + ".Setup";
        }
        String normalized = workspaceRoot.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
        String digest = shortSha256(normalized + "|" + env);
        String candidate = AUMID_PREFIX + env + "." + digest;
        return candidate.length() <= 64 ? candidate : candidate.substring(0, 64);
    }

    /**
     * Stamps the process AppUserModelID before the primary Stage HWND is created so Windows
     * treats each configured customer workspace independently from the installer default.
     */
    public static void initializeProcessIdentity() {
        if (!isWindowsNativeEnabled()) return;
        Path workspace = WorkspaceManager.isConfigured() ? WorkspaceManager.getWorkspaceRoot() : null;
        String env = WorkspaceManager.isConfigured() ? ConfigManager.getDeploymentEnvironment() : "SETUP";
        String aumid = buildAppUserModelId(workspace, env);
        processAppUserModelId = aumid;
        try (Arena arena = Arena.ofConfined()) {
            Win32.setCurrentProcessExplicitAppUserModelId(arena, aumid);
        } catch (Throwable failure) {
            LOG.log(System.Logger.Level.DEBUG, "Unable to set process AppUserModelID: " + failure.getMessage());
        }
    }

    /**
     * Applies dynamic branding to native OS surfaces (macOS Dock, Windows Taskbar HWND +
     * PropertyStore + packaged user .lnk shortcuts) off the JavaFX Application Thread.
     */
    public static void applyToPrimaryStageAsync(Stage stage, Path configuredMarkPath, String baseTitle) {
        if (stage == null || GraphicsEnvironment.isHeadless()) return;
        if (!isMac() && !isWindowsNativeEnabled()) return;
        if (!stage.isShowing()) return;

        final String liveTitle = stage.getTitle() == null || stage.getTitle().isBlank() ? baseTitle : stage.getTitle();
        final String displayName = baseTitle == null || baseTitle.isBlank()
                ? BrandingService.technicalProductName() : baseTitle;
        final Path workspace = WorkspaceManager.isConfigured() ? WorkspaceManager.getWorkspaceRoot() : null;
        final String env = WorkspaceManager.isConfigured() ? ConfigManager.getDeploymentEnvironment() : "SETUP";

        WORKER.submit(() -> {
            if (isMac()) {
                applyMacDockIcon(configuredMarkPath);
            } else if (isWindowsNativeEnabled()) {
                applyWindowsBranding(workspace, env, configuredMarkPath, liveTitle, displayName);
            }
        });
    }

    private static void applyMacDockIcon(Path configuredMarkPath) {
        try {
            if (!Taskbar.isTaskbarSupported()) return;
            Taskbar taskbar = Taskbar.getTaskbar();
            if (!taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) return;

            BufferedImage icon = null;
            if (configuredMarkPath != null && Files.isRegularFile(configuredMarkPath)) {
                icon = IcoEncoder.readSquareBufferedImage(configuredMarkPath, 256);
            }
            if (icon == null) {
                try (InputStream fallback = ResourceLocator.open("/installer/logo-1024.png")) {
                    icon = IcoEncoder.readSquareBufferedImage(fallback, 256);
                }
            }
            if (icon != null) {
                taskbar.setIconImage(icon);
            }
        } catch (Throwable failure) {
            LOG.log(System.Logger.Level.DEBUG, "macOS Dock icon update warning: " + failure.getMessage());
        }
    }

    private static void applyWindowsBranding(
            Path workspace,
            String env,
            Path configuredMarkPath,
            String liveWindowTitle,
            String displayName
    ) {
        try {
            String aumid = processAppUserModelId != null
                    ? processAppUserModelId : buildAppUserModelId(workspace, env);
            Path icoPath = resolveOrBuildCustomIco(configuredMarkPath);
            Path packagedExe = resolvePackagedExecutable();

            try (Arena arena = Arena.ofConfined()) {
                int pid = Win32.getCurrentProcessId();
                long hwnd = Win32.findPrimaryWindow(arena, pid, liveWindowTitle);
                if (hwnd != 0L) {
                    String winSig = hwnd + "|" + aumid + "|" + lastAppliedMarkSignature + "|" + displayName + "|" + packagedExe;
                    if (!winSig.equals(lastAppliedWindowSignature)) {
                        applyWindowIcons(arena, hwnd, icoPath);
                        Win32.applyWindowAppUserModel(arena, hwnd, aumid, displayName, packagedExe, icoPath);
                        lastAppliedWindowSignature = winSig;
                    }
                }
                if (packagedExe != null && Boolean.getBoolean("dse.erp.packaged")) {
                    syncPackagedShortcutsIfChanged(arena, aumid, packagedExe, icoPath);
                }
            }
        } catch (Throwable failure) {
            LOG.log(System.Logger.Level.DEBUG, "Windows native branding warning: " + failure.getMessage());
        }
    }

    private static Path resolveOrBuildCustomIco(Path configuredMarkPath) {
        Path icoFile = WorkspaceManager.isConfigured()
                ? WorkspaceManager.getConfigurationFolder().resolve("assets").resolve("app-mark.ico")
                : null;
        if (icoFile == null) return null;

        if (configuredMarkPath == null || !Files.isRegularFile(configuredMarkPath)) {
            try { Files.deleteIfExists(icoFile); } catch (Exception ignored) { }
            lastAppliedMarkSignature = "NONE";
            return null;
        }
        try {
            long mtime = Files.getLastModifiedTime(configuredMarkPath).toMillis();
            long size = Files.size(configuredMarkPath);
            String signature = configuredMarkPath.toAbsolutePath().normalize() + "|" + mtime + "|" + size;
            if (!signature.equals(lastAppliedMarkSignature) || !Files.isRegularFile(icoFile)) {
                if (!IcoEncoder.writeMultiResIco(configuredMarkPath, icoFile)) {
                    return null;
                }
                lastAppliedMarkSignature = signature;
            }
            return icoFile;
        } catch (Exception failure) {
            LOG.log(System.Logger.Level.DEBUG, "ICO conversion warning: " + failure.getMessage());
            return null;
        }
    }

    private static void applyWindowIcons(Arena arena, long hwnd, Path customIco) {
        if (customIco == null || !Files.isRegularFile(customIco)) {
            Win32.sendWmSetIcon(hwnd, ICON_BIG, 0L);
            Win32.sendWmSetIcon(hwnd, ICON_SMALL, 0L);
            clearPreviousNativeIcons();
            return;
        }
        int bigW = Math.max(32, Win32.getSystemMetrics(SM_CXICON));
        int bigH = Math.max(32, Win32.getSystemMetrics(SM_CYICON));
        int smW = Math.max(16, Win32.getSystemMetrics(SM_CXSMICON));
        int smH = Math.max(16, Win32.getSystemMetrics(SM_CYSMICON));

        long hBig = Win32.loadIconFromFile(arena, customIco.toAbsolutePath().toString(), bigW, bigH);
        long hSmall = Win32.loadIconFromFile(arena, customIco.toAbsolutePath().toString(), smW, smH);

        if (hBig != 0L) {
            Win32.sendWmSetIcon(hwnd, ICON_BIG, hBig);
            if (lastBigHicon != 0L && lastBigHicon != hBig) Win32.destroyIcon(lastBigHicon);
            lastBigHicon = hBig;
        }
        if (hSmall != 0L) {
            Win32.sendWmSetIcon(hwnd, ICON_SMALL, hSmall);
            if (lastSmallHicon != 0L && lastSmallHicon != hSmall) Win32.destroyIcon(lastSmallHicon);
            lastSmallHicon = hSmall;
        }
    }

    private static void clearPreviousNativeIcons() {
        if (lastBigHicon != 0L) {
            Win32.destroyIcon(lastBigHicon);
            lastBigHicon = 0L;
        }
        if (lastSmallHicon != 0L) {
            Win32.destroyIcon(lastSmallHicon);
            lastSmallHicon = 0L;
        }
    }

    private static void syncPackagedShortcutsIfChanged(Arena arena, String aumid, Path packagedExe, Path customIco) {
        String desiredIcon = customIco != null && Files.isRegularFile(customIco)
                ? customIco.toAbsolutePath().normalize().toString() : "";
        String signature = aumid + "|" + packagedExe + "|" + desiredIcon;
        if (signature.equals(lastShortcutSignature)) return;

        boolean updatedAny = false;
        for (Path shortcut : shortcutCandidates()) {
            if (Files.isRegularFile(shortcut) && Files.isWritable(shortcut)) {
                if (Win32.updateMatchingShortcut(arena, shortcut, packagedExe, desiredIcon, aumid)) {
                    updatedAny = true;
                }
            }
        }
        lastShortcutSignature = signature;
        if (updatedAny) {
            Win32.notifyShellAssocChanged();
        }
    }

    private static List<Path> shortcutCandidates() {
        String userHome = System.getProperty("user.home", "");
        String appData = System.getenv("APPDATA");
        String publicDir = System.getenv("PUBLIC");
        var paths = new java.util.ArrayList<Path>(3);
        if (!userHome.isBlank()) paths.add(Path.of(userHome, "Desktop", "DSE ERP.lnk"));
        if (appData != null && !appData.isBlank()) {
            paths.add(Path.of(appData, "Microsoft", "Windows", "Start Menu", "Programs", "DSE ERP", "DSE ERP.lnk"));
        }
        if (publicDir != null && !publicDir.isBlank()) paths.add(Path.of(publicDir, "Desktop", "DSE ERP.lnk"));
        return paths;
    }

    private static Path resolvePackagedExecutable() {
        if (!Boolean.getBoolean("dse.erp.packaged")) return null;
        try {
            return ProcessHandle.current().info().command()
                    .map(Path::of)
                    .map(p -> p.toAbsolutePath().normalize())
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".exe"))
                    .orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isWindowsNativeEnabled() {
        if (!isWindows() || GraphicsEnvironment.isHeadless()) return false;
        if (Boolean.getBoolean("dse.erp.disableNativeBranding")) return false;
        return Boolean.getBoolean("dse.erp.nativeAccessRelaunch") || Boolean.getBoolean("dse.erp.packaged");
    }

    private static String sanitizeToken(String value, String fallback) {
        String cleaned = value == null ? "" : value.replaceAll("[^A-Za-z0-9]", "");
        if (cleaned.isBlank()) return fallback;
        return cleaned.length() > 16 ? cleaned.substring(0, 16) : cleaned;
    }

    private static String shortSha256(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 12);
        } catch (Exception ignored) {
            return Integer.toHexString(input.hashCode());
        }
    }

    /** Isolated Win32 FFM + COM interop helper loaded only on Windows native desktop runs. */
    private static final class Win32 {
        private static final Linker LINKER = Linker.nativeLinker();
        private static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
        private static final SymbolLookup USER32 = SymbolLookup.libraryLookup("user32", Arena.global());
        private static final SymbolLookup SHELL32 = SymbolLookup.libraryLookup("shell32", Arena.global());
        private static final SymbolLookup OLE32 = SymbolLookup.libraryLookup("ole32", Arena.global());

        private static final MethodHandle GET_CURRENT_PROCESS_ID = downcall(KERNEL32, "GetCurrentProcessId",
                FunctionDescriptor.of(ValueLayout.JAVA_INT));
        private static final MethodHandle SET_CURRENT_AUMID = downcall(SHELL32, "SetCurrentProcessExplicitAppUserModelID",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        private static final MethodHandle SH_GET_PROPERTY_STORE_FOR_WINDOW = downcall(SHELL32, "SHGetPropertyStoreForWindow",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        private static final MethodHandle SH_CHANGE_NOTIFY = downcall(SHELL32, "SHChangeNotify",
                FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        private static final MethodHandle CO_INITIALIZE_EX = downcall(OLE32, "CoInitializeEx",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        private static final MethodHandle CO_UNINITIALIZE = downcall(OLE32, "CoUninitialize",
                FunctionDescriptor.ofVoid());
        private static final MethodHandle PROP_VARIANT_CLEAR = downcall(OLE32, "PropVariantClear",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        private static final MethodHandle CO_CREATE_INSTANCE = downcall(OLE32, "CoCreateInstance",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        private static final MethodHandle ENUM_WINDOWS = downcall(USER32, "EnumWindows",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        private static final MethodHandle IS_WINDOW_VISIBLE = downcall(USER32, "IsWindowVisible",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        private static final MethodHandle GET_WINDOW = downcall(USER32, "GetWindow",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        private static final MethodHandle GET_WINDOW_THREAD_PROCESS_ID = downcall(USER32, "GetWindowThreadProcessId",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        private static final MethodHandle GET_WINDOW_TEXT_W = downcall(USER32, "GetWindowTextW",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        private static final MethodHandle GET_SYSTEM_METRICS = downcall(USER32, "GetSystemMetrics",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        private static final MethodHandle LOAD_IMAGE_W = downcall(USER32, "LoadImageW",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        private static final MethodHandle SEND_MESSAGE_W = downcall(USER32, "SendMessageW",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
        private static final MethodHandle DESTROY_ICON = downcall(USER32, "DestroyIcon",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

        private static final MethodHandle ENUM_CALLBACK_TARGET;

        static {
            try {
                ENUM_CALLBACK_TARGET = MethodHandles.lookup().findStatic(
                        Win32.class, "onEnumWindow",
                        MethodType.methodType(int.class, MemorySegment.class, long.class));
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private static volatile int enumTargetPid;
        private static volatile String enumPreferredTitle;
        private static volatile long enumMatchedHwnd;
        private static volatile long enumFallbackHwnd;

        private static MethodHandle downcall(SymbolLookup lib, String name, FunctionDescriptor desc) {
            MemorySegment sym = lib.find(name).orElseThrow(() -> new UnsatisfiedLinkError("Missing Win32 symbol: " + name));
            return LINKER.downcallHandle(sym, desc);
        }

        static void setCurrentProcessExplicitAppUserModelId(Arena arena, String aumid) throws Throwable {
            int ignored = (int) SET_CURRENT_AUMID.invokeExact(allocWide(arena, aumid));
        }

        static int getCurrentProcessId() throws Throwable {
            return (int) GET_CURRENT_PROCESS_ID.invokeExact();
        }

        static int getSystemMetrics(int metric) {
            try { return (int) GET_SYSTEM_METRICS.invokeExact(metric); }
            catch (Throwable ignored) { return 32; }
        }

        static long loadIconFromFile(Arena arena, String path, int width, int height) {
            try {
                MemorySegment handle = (MemorySegment) LOAD_IMAGE_W.invokeExact(
                        MemorySegment.NULL, allocWide(arena, path), IMAGE_ICON, width, height, LR_LOADFROMFILE);
                return handle.address();
            } catch (Throwable ignored) {
                return 0L;
            }
        }

        static void sendWmSetIcon(long hwnd, long wParam, long hIcon) {
            try {
                long ignored = (long) SEND_MESSAGE_W.invokeExact(MemorySegment.ofAddress(hwnd), WM_SETICON, wParam, hIcon);
            } catch (Throwable ignored) { }
        }

        static void destroyIcon(long hIcon) {
            if (hIcon == 0L) return;
            try { int ignored = (int) DESTROY_ICON.invokeExact(MemorySegment.ofAddress(hIcon)); }
            catch (Throwable ignored) { }
        }

        static void notifyShellAssocChanged() {
            try { SH_CHANGE_NOTIFY.invokeExact(SHCNE_ASSOCCHANGED, SHCNF_IDLIST, MemorySegment.NULL, MemorySegment.NULL); }
            catch (Throwable ignored) { }
        }

        static synchronized long findPrimaryWindow(Arena arena, int pid, String preferredTitle) throws Throwable {
            enumTargetPid = pid;
            enumPreferredTitle = preferredTitle == null ? "" : preferredTitle;
            enumMatchedHwnd = 0L;
            enumFallbackHwnd = 0L;
            MemorySegment stub = LINKER.upcallStub(
                    ENUM_CALLBACK_TARGET,
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
                    arena);
            int ignored = (int) ENUM_WINDOWS.invokeExact(stub, 0L);
            return enumMatchedHwnd != 0L ? enumMatchedHwnd : enumFallbackHwnd;
        }

        @SuppressWarnings("unused")
        private static int onEnumWindow(MemorySegment hwnd, long lParam) {
            try (Arena local = Arena.ofConfined()) {
                if ((int) IS_WINDOW_VISIBLE.invokeExact(hwnd) == 0) return 1;
                MemorySegment owner = (MemorySegment) GET_WINDOW.invokeExact(hwnd, GW_OWNER);
                if (!owner.equals(MemorySegment.NULL)) return 1;
                MemorySegment pidOut = local.allocate(ValueLayout.JAVA_INT);
                int ignored = (int) GET_WINDOW_THREAD_PROCESS_ID.invokeExact(hwnd, pidOut);
                if (pidOut.get(ValueLayout.JAVA_INT, 0) != enumTargetPid) return 1;

                if (enumFallbackHwnd == 0L) enumFallbackHwnd = hwnd.address();
                if (!enumPreferredTitle.isBlank()) {
                    MemorySegment buf = local.allocate(ValueLayout.JAVA_CHAR, 512);
                    int len = (int) GET_WINDOW_TEXT_W.invokeExact(hwnd, buf, 512);
                    if (len > 0) {
                        String title = new String(buf.asSlice(0, (long) len * 2).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_16LE);
                        if (title.equals(enumPreferredTitle)) {
                            enumMatchedHwnd = hwnd.address();
                            return 0;
                        }
                    }
                }
                return 1;
            } catch (Throwable ignored) {
                return 1;
            }
        }

        static void applyWindowAppUserModel(
                Arena arena,
                long hwnd,
                String aumid,
                String displayName,
                Path packagedExe,
                Path customIco
        ) throws Throwable {
            int co = (int) CO_INITIALIZE_EX.invokeExact(MemorySegment.NULL, COINIT_APARTMENTTHREADED);
            boolean comInitialized = (co == 0 || co == 1);
            MemorySegment pStore = MemorySegment.NULL;
            try {
                MemorySegment ppv = arena.allocate(ValueLayout.ADDRESS);
                int hr = (int) SH_GET_PROPERTY_STORE_FOR_WINDOW.invokeExact(
                        MemorySegment.ofAddress(hwnd), allocGuid(arena, IID_IPROPERTY_STORE), ppv);
                if (hr != 0) return;
                pStore = ppv.get(ValueLayout.ADDRESS, 0);
                if (pStore.equals(MemorySegment.NULL)) return;

                setPropertyString(arena, pStore, PID_APP_USER_MODEL_ID, aumid);
                if (displayName != null && !displayName.isBlank()) {
                    setPropertyString(arena, pStore, PID_RELAUNCH_DISPLAY_NAME, displayName);
                }
                if (packagedExe != null) {
                    setPropertyString(arena, pStore, PID_RELAUNCH_COMMAND, "\"" + packagedExe + "\"");
                    String iconRes = customIco != null && Files.isRegularFile(customIco)
                            ? customIco.toAbsolutePath().normalize() + ",0"
                            : packagedExe + ",0";
                    setPropertyString(arena, pStore, PID_RELAUNCH_ICON_RESOURCE, iconRes);
                } else {
                    if (customIco != null && Files.isRegularFile(customIco)) {
                        setPropertyString(arena, pStore, PID_RELAUNCH_ICON_RESOURCE,
                                customIco.toAbsolutePath().normalize() + ",0");
                    } else {
                        clearProperty(arena, pStore, PID_RELAUNCH_ICON_RESOURCE);
                    }
                }
                commitPropertyStore(pStore);
            } finally {
                releaseCom(pStore);
                if (comInitialized) CO_UNINITIALIZE.invokeExact();
            }
        }

        static boolean updateMatchingShortcut(
                Arena arena,
                Path lnkPath,
                Path packagedExe,
                String desiredIconPath,
                String aumid
        ) {
            MemorySegment pLink = MemorySegment.NULL;
            MemorySegment pPersist = MemorySegment.NULL;
            MemorySegment pStore = MemorySegment.NULL;
            boolean comInitialized = false;
            try {
                int co = (int) CO_INITIALIZE_EX.invokeExact(MemorySegment.NULL, COINIT_APARTMENTTHREADED);
                comInitialized = (co == 0 || co == 1);

                MemorySegment ppLink = arena.allocate(ValueLayout.ADDRESS);
                int hr = (int) CO_CREATE_INSTANCE.invokeExact(
                        allocGuid(arena, CLSID_SHELL_LINK), MemorySegment.NULL, CLSCTX_INPROC_SERVER,
                        allocGuid(arena, IID_ISHELL_LINK_W), ppLink);
                if (hr != 0) return false;
                pLink = ppLink.get(ValueLayout.ADDRESS, 0);

                pPersist = queryInterface(arena, pLink, IID_IPERSIST_FILE);
                if (pPersist.equals(MemorySegment.NULL)) return false;

                MemorySegment lnkWide = allocWide(arena, lnkPath.toAbsolutePath().toString());
                int loadHr = (int) comCall(pPersist, 5,
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT))
                        .invokeExact(pPersist, lnkWide, STGM_READWRITE);
                if (loadHr != 0) return false;

                MemorySegment pathBuf = arena.allocate(ValueLayout.JAVA_CHAR, 520);
                int pathHr = (int) comCall(pLink, 3,
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT))
                        .invokeExact(pLink, pathBuf, 520, MemorySegment.NULL, 0);
                if (pathHr != 0) return false;
                String target = readWide(pathBuf);
                if (target.isBlank() || !Path.of(target).toAbsolutePath().normalize().toString()
                        .equalsIgnoreCase(packagedExe.toString())) {
                    return false;
                }

                MemorySegment iconBuf = arena.allocate(ValueLayout.JAVA_CHAR, 520);
                MemorySegment iconIdx = arena.allocate(ValueLayout.JAVA_INT);
                int iconHr = (int) comCall(pLink, 16,
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS))
                        .invokeExact(pLink, iconBuf, 520, iconIdx);
                String currentIcon = iconHr == 0 ? readWide(iconBuf) : "";
                boolean iconMatches = currentIcon.equalsIgnoreCase(desiredIconPath) && iconIdx.get(ValueLayout.JAVA_INT, 0) == 0;

                pStore = queryInterface(arena, pLink, IID_IPROPERTY_STORE);
                String currentAumid = pStore.equals(MemorySegment.NULL) ? "" : getPropertyString(arena, pStore, PID_APP_USER_MODEL_ID);
                boolean aumidMatches = aumid.equals(currentAumid);

                if (iconMatches && aumidMatches) return false;

                if (!iconMatches) {
                    int ignored = (int) comCall(pLink, 17, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT))
                            .invokeExact(pLink, allocWide(arena, desiredIconPath), 0);
                }
                if (!pStore.equals(MemorySegment.NULL) && !aumidMatches) {
                    setPropertyString(arena, pStore, PID_APP_USER_MODEL_ID, aumid);
                    commitPropertyStore(pStore);
                }
                int saveHr = (int) comCall(pPersist, 6,
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT))
                        .invokeExact(pPersist, lnkWide, 1);
                return saveHr == 0;
            } catch (Throwable failure) {
                LOG.log(System.Logger.Level.DEBUG, "Shortcut sync warning for " + lnkPath + ": " + failure.getMessage());
                return false;
            } finally {
                releaseCom(pStore);
                releaseCom(pPersist);
                releaseCom(pLink);
                if (comInitialized) {
                    try { CO_UNINITIALIZE.invokeExact(); } catch (Throwable ignored) { }
                }
            }
        }

        private static MemorySegment queryInterface(Arena arena, MemorySegment comObj, UUID iid) throws Throwable {
            MemorySegment ppv = arena.allocate(ValueLayout.ADDRESS);
            int hr = (int) comCall(comObj, 0,
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS))
                    .invokeExact(comObj, allocGuid(arena, iid), ppv);
            return hr == 0 ? ppv.get(ValueLayout.ADDRESS, 0) : MemorySegment.NULL;
        }

        private static String getPropertyString(Arena arena, MemorySegment pStore, int pid) {
            MemorySegment pv = arena.allocate(24);
            try {
                int hr = (int) comCall(pStore, 5,
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS))
                        .invokeExact(pStore, allocPropertyKey(arena, pid), pv);
                if (hr != 0) return "";
                short vt = pv.get(ValueLayout.JAVA_SHORT, 0);
                if (vt != VT_LPWSTR) return "";
                MemorySegment ptr = pv.get(ValueLayout.ADDRESS, 8);
                if (ptr.equals(MemorySegment.NULL)) return "";
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 512; i++) {
                    char c = ptr.reinterpret((long) (i + 1) * 2).getAtIndex(ValueLayout.JAVA_CHAR, i);
                    if (c == '\0') break;
                    sb.append(c);
                }
                return sb.toString();
            } catch (Throwable ignored) {
                return "";
            } finally {
                try { int ignored = (int) PROP_VARIANT_CLEAR.invokeExact(pv); } catch (Throwable ignored) { }
            }
        }

        private static void setPropertyString(Arena arena, MemorySegment pStore, int pid, String value) throws Throwable {
            MemorySegment pv = arena.allocate(24);
            pv.set(ValueLayout.JAVA_SHORT, 0, VT_LPWSTR);
            pv.set(ValueLayout.ADDRESS, 8, allocWide(arena, value));
            int ignored = (int) comCall(pStore, 6,
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS))
                    .invokeExact(pStore, allocPropertyKey(arena, pid), pv);
        }

        private static void clearProperty(Arena arena, MemorySegment pStore, int pid) throws Throwable {
            MemorySegment pv = arena.allocate(24);
            pv.set(ValueLayout.JAVA_SHORT, 0, VT_EMPTY);
            int ignored = (int) comCall(pStore, 6,
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS))
                    .invokeExact(pStore, allocPropertyKey(arena, pid), pv);
        }

        private static void commitPropertyStore(MemorySegment pStore) throws Throwable {
            int ignored = (int) comCall(pStore, 7, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)).invokeExact(pStore);
        }

        private static void releaseCom(MemorySegment comObj) {
            if (comObj == null || comObj.equals(MemorySegment.NULL)) return;
            try {
                int ignored = (int) comCall(comObj, 2, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)).invokeExact(comObj);
            } catch (Throwable ignored) { }
        }

        private static MethodHandle comCall(MemorySegment comObj, int slotIndex, FunctionDescriptor desc) {
            MemorySegment vtblPtr = comObj.reinterpret(ValueLayout.ADDRESS.byteSize()).get(ValueLayout.ADDRESS, 0);
            MemorySegment vtbl = vtblPtr.reinterpret((long) (slotIndex + 1) * ValueLayout.ADDRESS.byteSize());
            MemorySegment fn = vtbl.getAtIndex(ValueLayout.ADDRESS, slotIndex);
            return LINKER.downcallHandle(fn, desc);
        }

        private static MemorySegment allocWide(Arena arena, String value) {
            String safe = value == null ? "" : value;
            char[] chars = new char[safe.length() + 1];
            safe.getChars(0, safe.length(), chars, 0);
            MemorySegment seg = arena.allocate(ValueLayout.JAVA_CHAR, chars.length);
            MemorySegment.copy(chars, 0, seg, ValueLayout.JAVA_CHAR, 0, chars.length);
            return seg;
        }

        private static String readWide(MemorySegment seg) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 512; i++) {
                char c = seg.getAtIndex(ValueLayout.JAVA_CHAR, i);
                if (c == '\0') break;
                sb.append(c);
            }
            return sb.toString();
        }

        private static MemorySegment allocGuid(Arena arena, UUID uuid) {
            MemorySegment seg = arena.allocate(16);
            writeGuidBytes(seg, 0, uuid);
            return seg;
        }

        private static MemorySegment allocPropertyKey(Arena arena, int pid) {
            MemorySegment seg = arena.allocate(20);
            writeGuidBytes(seg, 0, FMTID_APP_USER_MODEL);
            seg.set(ValueLayout.JAVA_INT, 16, pid);
            return seg;
        }

        private static void writeGuidBytes(MemorySegment seg, long offset, UUID uuid) {
            long msb = uuid.getMostSignificantBits();
            long lsb = uuid.getLeastSignificantBits();
            seg.set(ValueLayout.JAVA_INT, offset, (int) (msb >>> 32));
            seg.set(ValueLayout.JAVA_SHORT, offset + 4, (short) (msb >>> 16));
            seg.set(ValueLayout.JAVA_SHORT, offset + 6, (short) msb);
            for (int i = 0; i < 8; i++) {
                seg.set(ValueLayout.JAVA_BYTE, offset + 8 + i, (byte) (lsb >>> (56 - i * 8)));
            }
        }
    }
}
