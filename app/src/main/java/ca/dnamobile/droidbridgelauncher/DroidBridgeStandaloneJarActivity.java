/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * DroidBridge launcher-side Java GUI jar executor.
 */

package ca.dnamobile.droidbridgelauncher;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.oracle.dalvik.VMLauncher;

import ca.dnamobile.droidbridgelauncher.runtime.AWTInputBridge;
import ca.dnamobile.droidbridgelauncher.runtime.multirt.MultiRTUtils;
import ca.dnamobile.droidbridgelauncher.runtime.utils.JREUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import ca.dnamobile.droidbridgelauncher.awt.AWTCanvasView;
import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.utils.path.LibPath;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;

/**
 * Executes desktop-style Java installer jars that need a Swing/AWT window.
 *
 * LauncherSettingsActivity already searches for this class before falling back
 * to its headless ProcessBuilder runner.  This activity is the missing bridge:
 * it starts an in-process OpenJDK VM using VMLauncher, injects Cacio/Caciocavallo
 * as the AWT backend, renders the Cacio framebuffer, and forwards touch/input.
 */
public class DroidBridgeStandaloneJarActivity extends Activity {
    public static final String EXTRA_MOD_URI = "modUri";
    public static final String EXTRA_JAVA_ARGS = "javaArgs";
    public static final String EXTRA_JRE_NAME = "jre_name";
    public static final String EXTRA_FORCE_SHOW_LOG = "forceShowLog";
    public static final String EXTRA_OPEN_LOG_OUTPUT = "openLogOutput";
    public static final String EXTRA_SUBSCRIBE_JVM_EXIT_EVENT = "subscribe_jvm_exit_event";

    private static final String TAG = "DroidBridgeStandaloneJar";

    private static final int VK_ENTER = 10;
    private static final int VK_BACK_SPACE = 8;
    private static final int VK_TAB = 9;
    private static final int VK_ESCAPE = 27;
    private static final int VK_DELETE = 127;
    private static final int VK_CONTROL = 17;
    private static final int VK_C = 67;
    private static final int VK_V = 86;
    private static final long UI_READY_TIMEOUT_MS = 2500L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private AWTCanvasView awtCanvasView;
    private TextView logTextView;
    private ScrollView logScrollView;
    private boolean subscribeJvmExitEvent;
    private volatile boolean launchStarted;
    private volatile boolean uiCreated;
    @Nullable private volatile Throwable nativeBridgePreloadError;
    @Nullable private HandlerThread nativePreloadThread;
    @Nullable private Handler nativePreloadHandler;
    private final ArrayList<String> pendingLogLines = new ArrayList<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        LauncherTheme.apply(this);
        super.onCreate(savedInstanceState);
        PathManager.initContextConstants(this);
        LibPath.refresh();

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        subscribeJvmExitEvent = getIntent().getBooleanExtra(EXTRA_SUBSCRIBE_JVM_EXIT_EVENT, true);

        /*
         * DroidBridge-compatible GUI installer boot order.
         *
         * Create the Android view tree first, load the DroidBridge native bridge
         * from the Activity main thread where Android has a Looper, then start
         * JLI_Launch from a dedicated Java thread.  The previous HandlerThread
         * JLI path could reach JLI_Launch but Android/HotSpot aborted with:
         *   FORTIFY: pthread_mutex_lock called on a destroyed mutex
         *
         * Do not use this Activity for normal loader installers.  It is only kept
         * for real Swing/AWT installers such as the OptiFine standalone profile
         * flow where the prior implementation still uses a GUI installer process.
         */
        ensureUiCreated();
        try {
            JREUtils.chdir(getFilesDir().getAbsolutePath());
            appendLog("Native bridge preloaded on Activity main thread");
        } catch (Throwable throwable) {
            nativeBridgePreloadError = throwable;
            Logging.e(TAG, "Native bridge preload failed on Activity main thread", throwable);
            appendLog("Native bridge preload failed: "
                    + (throwable.getMessage() != null ? throwable.getMessage() : throwable.toString()));
        }
        new Thread(this::runJarLaunchOnCurrentLooperThread, "JREMainThread").start();
    }

    private void preloadNativeBridgeThenStartJar() {
        try {
            nativePreloadThread = new HandlerThread("DroidBridgeNativePreload");
            nativePreloadThread.start();
            nativePreloadHandler = new Handler(nativePreloadThread.getLooper());
            nativePreloadHandler.post(() -> {
                try {
                    if (Looper.myLooper() == null) {
                        throw new IllegalStateException("Native bridge preload thread has no Looper.");
                    }

                    /*
                     * chdir() is harmless here and forces JREUtils.<clinit>() to run.
                     * Do not call dlopen() with a fake path; just load the Java/native
                     * bridge cleanly and let buildLaunchRequest() set the real working
                     * directory and LD_LIBRARY_PATH immediately before JLI_Launch.
                     */
                    JREUtils.chdir(getFilesDir().getAbsolutePath());
                    appendLog("Native bridge preloaded on dedicated looper thread");
                } catch (Throwable throwable) {
                    nativeBridgePreloadError = throwable;
                    Logging.e(TAG, "Native bridge preload failed", throwable);
                    appendLog("Native bridge preload failed: "
                            + (throwable.getMessage() != null ? throwable.getMessage() : throwable.toString()));
                } finally {
                    runJarLaunchOnCurrentLooperThread();
                }
            });
        } catch (Throwable throwable) {
            nativeBridgePreloadError = throwable;
            Logging.e(TAG, "Unable to start native bridge preload thread", throwable);
            appendLog("Unable to start native bridge preload thread: "
                    + (throwable.getMessage() != null ? throwable.getMessage() : throwable.toString()));
            mainHandler.post(this::ensureUiCreated);
            showToast("Unable to start Java GUI preload thread.");
        }
    }

    @Override
    protected void onDestroy() {
        if (awtCanvasView != null) awtCanvasView.stop();
        if (nativePreloadThread != null) {
            nativePreloadThread.quitSafely();
            nativePreloadThread = null;
            nativePreloadHandler = null;
        }
        super.onDestroy();
    }

    private void ensureUiCreated() {
        if (uiCreated || isFinishing() || isDestroyed()) return;
        uiCreated = true;
        buildUi();
        flushPendingLogsToView();
        if (awtCanvasView != null) awtCanvasView.start();
    }

    private void scheduleUiAfterJliStart() {
        mainHandler.postDelayed(this::ensureUiCreated, 1200L);
    }

    private void createUiBeforeJliLaunch() throws InterruptedException {
        if (uiCreated) return;

        if (Looper.myLooper() == Looper.getMainLooper()) {
            ensureUiCreated();
            return;
        }

        CountDownLatch latch = new CountDownLatch(1);
        mainHandler.post(() -> {
            try {
                ensureUiCreated();
            } finally {
                latch.countDown();
            }
        });

        if (!latch.await(UI_READY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            appendLog("WARNING: Timed out waiting for Java GUI canvas before JLI_Launch");
        }
    }

    private void buildUi() {
        int width = Math.max(1, getResources().getDisplayMetrics().widthPixels);
        int height = Math.max(1, getResources().getDisplayMetrics().heightPixels);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        awtCanvasView = new AWTCanvasView(this, width, height);
        root.addView(awtCanvasView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(dp(6), dp(4), dp(6), dp(4));
        controls.setBackgroundColor(0xAA101010);

        Button closeButton = makeButton("Close");
        closeButton.setOnClickListener(v -> finish());
        controls.addView(closeButton);

        Button logButton = makeButton("Log");
        logButton.setOnClickListener(v -> toggleLog());
        controls.addView(logButton);

        Button tabButton = makeButton("Tab");
        tabButton.setOnClickListener(v -> tapAwtKey('\t', VK_TAB));
        controls.addView(tabButton);

        Button enterButton = makeButton("Enter");
        enterButton.setOnClickListener(v -> tapAwtKey('\n', VK_ENTER));
        controls.addView(enterButton);

        Button escButton = makeButton("Esc");
        escButton.setOnClickListener(v -> tapAwtKey((char) 27, VK_ESCAPE));
        controls.addView(escButton);

        Button pasteButton = makeButton("Paste");
        pasteButton.setOnClickListener(v -> pasteClipboardToAwt());
        controls.addView(pasteButton);

        Button upButton = makeButton("↑");
        upButton.setOnClickListener(v -> AWTInputBridge.moveAllWindows(0, -dp(24)));
        controls.addView(upButton);

        Button downButton = makeButton("↓");
        downButton.setOnClickListener(v -> AWTInputBridge.moveAllWindows(0, dp(24)));
        controls.addView(downButton);

        EditText textInput = new EditText(this);
        textInput.setSingleLine(true);
        textInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        textInput.setHint("send text");
        textInput.setTextColor(0xFFFFFFFF);
        textInput.setHintTextColor(0xFFAAAAAA);
        controls.addView(textInput, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button sendButton = makeButton("Send");
        sendButton.setOnClickListener(v -> {
            String text = textInput.getText() == null ? "" : textInput.getText().toString();
            sendTextToAwt(text);
            textInput.setText("");
            if (awtCanvasView != null) awtCanvasView.requestFocus();
        });
        controls.addView(sendButton);

        FrameLayout.LayoutParams controlsParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
        );
        root.addView(controls, controlsParams);

        logTextView = new TextView(this);
        logTextView.setTextColor(0xFFFFFFFF);
        logTextView.setTextSize(12f);
        logTextView.setPadding(dp(8), dp(8), dp(8), dp(8));
        logTextView.setText("Java GUI launcher starting…\n");

        logScrollView = new ScrollView(this);
        logScrollView.setBackgroundColor(0xDD000000);
        logScrollView.addView(logTextView);
        logScrollView.setVisibility(
                getIntent().getBooleanExtra(EXTRA_FORCE_SHOW_LOG, false)
                        || getIntent().getBooleanExtra(EXTRA_OPEN_LOG_OUTPUT, false)
                        ? View.VISIBLE
                        : View.GONE
        );

        FrameLayout.LayoutParams logParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(170),
                Gravity.TOP
        );
        root.addView(logScrollView, logParams);

        setContentView(root);
        LauncherTheme.applyRainbowBackgroundIfNeeded(this);
    }

    @NonNull
    private Button makeButton(@NonNull String text) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextSize(12f);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(40)
        );
        params.rightMargin = dp(4);
        button.setLayoutParams(params);
        return button;
    }

    private void toggleLog() {
        if (logScrollView == null) return;
        logScrollView.setVisibility(logScrollView.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
    }

    private void runJarLaunchOnCurrentLooperThread() {
        if (launchStarted) return;
        launchStarted = true;

        /*
         * This method intentionally runs on DroidBridgeNativePreload, the same
         * HandlerThread that loaded libdroidbridge_runtime.  Do not wrap this in a plain
         * new Thread(): Cacio/LWJGL/CallbackBridge can still ask Android for a
         * Choreographer after JLI_Launch begins, and that requires the launching
         * thread to have a Looper.
         */
        int exitCode = -1;
        try {
            if (nativeBridgePreloadError != null) {
                throw new IllegalStateException("Native bridge failed to preload on the dedicated looper thread.", nativeBridgePreloadError);
            }

            appendLog("Launching JVM on Java thread: " + Thread.currentThread().getName());
            LaunchRequest request = buildLaunchRequest();
            appendLog("Runtime: " + request.runtimeName);
            appendLog("Working dir: " + request.workingDirectory.getAbsolutePath());
            appendLog("Java args: " + safeJoinForLog(request.jvmArgs));
            appendLog("Java GUI canvas ready before JLI_Launch");
            exitCode = VMLauncher.launchJVM(request.jvmArgs.toArray(new String[0]));
            appendLog("JVM exited with code " + exitCode);
        } catch (Throwable throwable) {
            Logging.e(TAG, "GUI jar execution failed", throwable);
            mainHandler.post(this::ensureUiCreated);
            appendLog("ERROR: " + (throwable.getMessage() != null ? throwable.getMessage() : throwable.toString()));
            showToast("Java GUI jar failed: " + (throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName()));
        } finally {
            final int finalExitCode = exitCode;
            if (subscribeJvmExitEvent) {
                mainHandler.postDelayed(() -> {
                    if (!isFinishing()) {
                        Toast.makeText(this, "Jar finished with exit code " + finalExitCode, Toast.LENGTH_LONG).show();
                        finish();
                    }
                }, 900L);
            }
        }
    }

    @NonNull
    private LaunchRequest buildLaunchRequest() throws Exception {
        Intent intent = getIntent();
        String javaArgs = intent.getStringExtra(EXTRA_JAVA_ARGS);
        String forcedRuntime = intent.getStringExtra(EXTRA_JRE_NAME);
        Uri modUri = intent.getParcelableExtra(EXTRA_MOD_URI);

        File jarFile = null;
        ArrayList<String> jarArgs = new ArrayList<>();

        if (javaArgs != null && !javaArgs.trim().isEmpty()) {
            jarArgs.addAll(splitCommandLine(javaArgs));
            jarFile = findJarFromArgs(jarArgs);
        }

        if (jarFile == null && modUri != null) {
            jarFile = copyUriToGuiJarCache(modUri);
            jarArgs.clear();
            jarArgs.add("-jar");
            jarArgs.add(jarFile.getAbsolutePath());
        }

        if (jarFile == null || !jarFile.isFile()) {
            throw new IOException("No readable installer jar was provided.");
        }

        RuntimeChoice runtimeChoice = chooseRuntime(forcedRuntime, jarFile);
        MultiRTUtils.postPrepare(runtimeChoice.name);

        File workingDirectory = resolveWorkingDirectory();
        RuntimeNativePaths nativePaths = resolveRuntimeNativePaths(runtimeChoice.dir);
        JREUtils.chdir(workingDirectory.getAbsolutePath());

        /*
         * VMLauncher only needs libjli to be reachable before JLI_Launch.
         * Do NOT preload libjvm or the whole Internal-8 native folder here.
         * Preloading libjvm/AWT libraries before JLI_Launch can make HotSpot
         * abort on Android with:
         *   FORTIFY: pthread_mutex_lock called on a destroyed mutex
         *
         * The native launcher patch can also find libjli from LD_LIBRARY_PATH,
         * but this small absolute-path preload keeps the Java-only path safe too.
         */
        JREUtils.setLdLibraryPath(nativePaths.nativeLinkerPath);
        appendLog("LD_LIBRARY_PATH: " + nativePaths.nativeLinkerPath);
        preloadGuiRuntimeLibraries(runtimeChoice.dir, nativePaths);

        System.setProperty("java.home", runtimeChoice.dir.getAbsolutePath());
        System.setProperty("user.home", workingDirectory.getAbsolutePath());
        System.setProperty("user.dir", workingDirectory.getAbsolutePath());
        System.setProperty("java.io.tmpdir", getCacheDir().getAbsolutePath());

        ArrayList<String> jvmArgs = new ArrayList<>();
        jvmArgs.add("java");
        jvmArgs.add("-Xint");
        jvmArgs.add("-Djava.io.tmpdir=" + getCacheDir().getAbsolutePath());
        jvmArgs.add("-Duser.home=" + workingDirectory.getAbsolutePath());
        jvmArgs.add("-Duser.dir=" + workingDirectory.getAbsolutePath());
        jvmArgs.addAll(buildCacioJvmArgs(runtimeChoice.dir));
        jvmArgs.addAll(jarArgs);

        return new LaunchRequest(runtimeChoice.name, workingDirectory, jvmArgs);
    }

    @NonNull
    private File copyUriToGuiJarCache(@NonNull Uri uri) throws IOException {
        String displayName = queryDisplayName(uri);
        if (displayName == null || !displayName.toLowerCase(Locale.US).endsWith(".jar")) {
            displayName = "installer.jar";
        }
        displayName = displayName.replaceAll("[^A-Za-z0-9._-]", "_");

        File cacheDir = new File(getCacheDir(), "java_gui_jars");
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw new IOException("Unable to create GUI jar cache: " + cacheDir.getAbsolutePath());
        }
        File out = new File(cacheDir, System.currentTimeMillis() + "_" + displayName);
        try (InputStream input = getContentResolver().openInputStream(uri);
             OutputStream output = new FileOutputStream(out)) {
            if (input == null) throw new IOException("Unable to open selected jar.");
            copy(input, output);
        }
        appendLog("Copied jar to " + out.getAbsolutePath());
        return out;
    }

    @Nullable
    private String queryDisplayName(@NonNull Uri uri) {
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            String path = uri.getPath();
            return path == null ? null : new File(path).getName();
        }
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return cursor.getString(idx);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @NonNull
    private RuntimeChoice chooseRuntime(@Nullable String forcedRuntime, @NonNull File jarFile) throws IOException {
        if (forcedRuntime != null && !forcedRuntime.trim().isEmpty()) {
            File forced = MultiRTUtils.getRuntimeDir(forcedRuntime.trim());
            if (isUsableRuntime(forced)) return new RuntimeChoice(forcedRuntime.trim(), forced);
        }

        int requiredJava = readRequiredJavaVersion(jarFile);
        if (requiredJava > 17) {
            throw new IOException("This GUI jar requires Java " + requiredJava
                    + ". DroidBridge's Cacio GUI bridge is only safe up to Java 17 right now."
                    + " Use a headless installer mode for this jar if it has one.");
        }

        ArrayList<String> candidates = new ArrayList<>();
        if (requiredJava > 8) {
            candidates.add("Internal-17");
            candidates.add("Internal-21");
            candidates.add("Internal-8");
        } else if (requiredJava > 0) {
            candidates.add("Internal-8");
            candidates.add("Internal-17");
            candidates.add("Internal-21");
        } else {
            candidates.add("Internal-8");
            candidates.add("Internal-17");
            candidates.add("Internal-21");
        }

        for (String candidate : candidates) {
            File dir = MultiRTUtils.getRuntimeDir(candidate);
            if (isUsableRuntime(dir)) return new RuntimeChoice(candidate, dir);
        }

        throw new IOException("No usable Internal Java runtime was found. Reinstall components/runtimes first.");
    }

    private boolean isUsableRuntime(@Nullable File dir) {
        if (dir == null || !dir.isDirectory()) return false;
        if (new File(dir, "bin/java").isFile()) return true;
        if (new File(dir, "lib/rt.jar").isFile()) return true;
        return new File(dir, "lib/modules").isFile();
    }

    private int readRequiredJavaVersion(@NonNull File jarFile) {
        try (ZipFile zipFile = new ZipFile(jarFile)) {
            ZipEntry manifestEntry = zipFile.getEntry("META-INF/MANIFEST.MF");
            if (manifestEntry == null) return -1;
            Manifest manifest = new Manifest(zipFile.getInputStream(manifestEntry));
            Attributes attrs = manifest.getMainAttributes();
            String mainClass = attrs.getValue("Main-Class");
            if (mainClass == null || mainClass.trim().isEmpty()) return -1;
            String classEntryName = mainClass.trim().replace('.', '/') + ".class";
            ZipEntry classEntry = zipFile.getEntry(classEntryName);
            if (classEntry == null) return -1;
            try (InputStream input = zipFile.getInputStream(classEntry)) {
                byte[] header = new byte[8];
                if (input.read(header) != header.length) return -1;
                if ((header[0] & 0xFF) != 0xCA || (header[1] & 0xFF) != 0xFE
                        || (header[2] & 0xFF) != 0xBA || (header[3] & 0xFF) != 0xBE) {
                    return -1;
                }
                int major = ((header[6] & 0xFF) << 8) | (header[7] & 0xFF);
                return major >= 45 ? major - 44 : -1;
            }
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to infer jar Java version: " + throwable);
            return -1;
        }
    }

    @NonNull
    private File resolveWorkingDirectory() {
        File minecraftHome = new File(PathManager.DIR_MINECRAFT_HOME);
        if (!minecraftHome.exists()) {
            //noinspection ResultOfMethodCallIgnored
            minecraftHome.mkdirs();
        }
        return minecraftHome.isDirectory() ? minecraftHome : getFilesDir();
    }

    @NonNull
    private ArrayList<String> buildCacioJvmArgs(@NonNull File runtimeDir) throws IOException {
        ArrayList<String> args = new ArrayList<>();
        boolean java8 = runtimeDir.getName().contains("8") || new File(runtimeDir, "lib/rt.jar").isFile();
        File cacioDir = java8 ? LibPath.CACIO_8 : LibPath.CACIO_17;
        String cacioClassPath = buildJarClassPath(cacioDir);
        if (cacioClassPath.isEmpty()) {
            throw new IOException("Cacio/Caciocavallo GUI jars are missing from "
                    + (cacioDir != null ? cacioDir.getAbsolutePath() : "<null>")
                    + ". Reinstall DroidBridge components first.");
        }

        int width = Math.max(1, getResources().getDisplayMetrics().widthPixels);
        int height = Math.max(1, getResources().getDisplayMetrics().heightPixels);
        args.add("-Djava.awt.headless=false");
        args.add("-Dcacio.managed.screensize=" + width + "x" + height);
        args.add("-Dcacio.font.fontmanager=sun.awt.X11FontManager");
        args.add("-Dcacio.font.fontscaler=sun.font.FreetypeFontScaler");
        args.add("-Dswing.defaultlaf=javax.swing.plaf.nimbus.NimbusLookAndFeel");

        if (java8) {
            args.add("-Dawt.toolkit=net.java.openjdk.cacio.ctc.CTCToolkit");
            args.add("-Djava.awt.graphicsenv=net.java.openjdk.cacio.ctc.CTCGraphicsEnvironment");
            args.add("-Xbootclasspath/p:" + cacioClassPath);
        } else {
            args.add("-Dawt.toolkit=com.github.caciocavallosilano.cacio.ctc.CTCToolkit");
            args.add("-Djava.awt.graphicsenv=com.github.caciocavallosilano.cacio.ctc.CTCGraphicsEnvironment");
            if (LibPath.CACIO_17_AGENT != null && LibPath.CACIO_17_AGENT.isFile()) {
                args.add("-javaagent:" + LibPath.CACIO_17_AGENT.getAbsolutePath());
            }
            args.add("--add-exports=java.desktop/java.awt=ALL-UNNAMED");
            args.add("--add-exports=java.desktop/java.awt.peer=ALL-UNNAMED");
            args.add("--add-exports=java.desktop/sun.awt.image=ALL-UNNAMED");
            args.add("--add-exports=java.desktop/sun.java2d=ALL-UNNAMED");
            args.add("--add-exports=java.desktop/java.awt.dnd.peer=ALL-UNNAMED");
            args.add("--add-exports=java.desktop/sun.awt=ALL-UNNAMED");
            args.add("--add-exports=java.desktop/sun.awt.event=ALL-UNNAMED");
            args.add("--add-exports=java.desktop/sun.awt.datatransfer=ALL-UNNAMED");
            args.add("--add-exports=java.desktop/sun.font=ALL-UNNAMED");
            args.add("--add-exports=java.base/sun.security.action=ALL-UNNAMED");
            args.add("--add-opens=java.base/java.util=ALL-UNNAMED");
            args.add("--add-opens=java.desktop/java.awt=ALL-UNNAMED");
            args.add("--add-opens=java.desktop/sun.font=ALL-UNNAMED");
            args.add("--add-opens=java.desktop/sun.java2d=ALL-UNNAMED");
            args.add("--add-opens=java.base/java.lang.reflect=ALL-UNNAMED");
            args.add("--add-opens=java.base/java.net=ALL-UNNAMED");
            args.add("-Xbootclasspath/a:" + cacioClassPath);
        }
        return args;
    }

    @NonNull
    private String buildJarClassPath(@Nullable File directory) {
        if (directory == null || !directory.isDirectory()) return "";
        File[] jars = directory.listFiles((dir, name) -> name != null && name.toLowerCase(Locale.US).endsWith(".jar"));
        if (jars == null || jars.length == 0) return "";

        ArrayList<File> ordered = new ArrayList<>();
        addNamedJarFirst(ordered, jars, "ResConfHack.jar");
        addNamedJarFirst(ordered, jars, "cacio-androidnw-1.10-SNAPSHOT.jar");
        addNamedJarFirst(ordered, jars, "cacio-shared-1.10-SNAPSHOT.jar");
        addNamedJarFirst(ordered, jars, "cacio-tta.jar");
        addNamedJarFirst(ordered, jars, "cacio-shared.jar");
        java.util.Arrays.sort(jars, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File jar : jars) {
            if (!ordered.contains(jar)) ordered.add(jar);
        }

        StringBuilder out = new StringBuilder();
        for (File jar : ordered) {
            if (out.length() > 0) out.append(':');
            out.append(jar.getAbsolutePath());
        }
        return out.toString();
    }

    private void addNamedJarFirst(@NonNull ArrayList<File> ordered, @NonNull File[] jars, @NonNull String name) {
        for (File jar : jars) {
            if (jar.getName().equalsIgnoreCase(name) && !ordered.contains(jar)) {
                ordered.add(jar);
                return;
            }
        }
    }

    @NonNull
    private RuntimeNativePaths resolveRuntimeNativePaths(@NonNull File runtimeDir) {
        File runtimeLibDir = resolveRuntimeLibDir(runtimeDir);
        File jvmLibraryDir = new File(runtimeLibDir, "server/libjvm.so").isFile()
                ? new File(runtimeLibDir, "server")
                : new File(runtimeLibDir, "client");

        ArrayList<File> paths = new ArrayList<>();

        // These first three entries are the important ones for VMLauncher/JLI.
        addExistingPath(paths, jvmLibraryDir);
        addExistingPath(paths, new File(runtimeLibDir, "jli"));
        addExistingPath(paths, runtimeLibDir);

        // Keep the flat Java 17/21/25 layout available too.
        addExistingPath(paths, new File(runtimeDir, "lib/server"));
        addExistingPath(paths, new File(runtimeDir, "lib/jli"));
        addExistingPath(paths, new File(runtimeDir, "lib"));

        addExistingPath(paths, new File(PathManager.DIR_NATIVE_LIB));
        addExistingPath(paths, new File("/system/lib64"));
        addExistingPath(paths, new File("/vendor/lib64"));
        addExistingPath(paths, new File("/vendor/lib64/hw"));
        addExistingPath(paths, new File("/system/lib"));
        addExistingPath(paths, new File("/vendor/lib"));
        addExistingPath(paths, new File("/vendor/lib/hw"));

        String ldLibraryPath = joinFilePathList(paths);
        String nativeLinkerPath = jvmLibraryDir.getAbsolutePath() + ":" + ldLibraryPath;
        return new RuntimeNativePaths(runtimeLibDir, jvmLibraryDir, ldLibraryPath, nativeLinkerPath);
    }

    @NonNull
    private File resolveRuntimeLibDir(@NonNull File runtimeDir) {
        String[] archCandidates = new String[]{
                "aarch64",
                "arm64",
                "arm64-v8a",
                System.getProperty("os.arch", ""),
                "amd64",
                "x86_64"
        };
        for (String arch : archCandidates) {
            if (arch == null || arch.trim().isEmpty()) continue;
            File candidate = new File(runtimeDir, "lib/" + arch);
            if (candidate.isDirectory()) return candidate;
        }
        return new File(runtimeDir, "lib");
    }

    private void preloadLauncherLibraryOnly(@NonNull RuntimeNativePaths paths) {
        // libjli is the launcher entry point.  Leave libjvm and AWT libraries
        // for JLI/HotSpot to load in the order it expects.
        File libjli = new File(paths.runtimeLibDir, "jli/libjli.so");
        if (!dlopenOptional(libjli)) {
            dlopenOptional(new File(paths.runtimeLibDir, "libjli.so"));
        }
    }

    private void preloadGuiRuntimeLibraries(
            @NonNull File runtimeDir,
            @NonNull RuntimeNativePaths paths
    ) {
        /*
         * the prior implementation's DroidBridgeStandaloneJarActivity preloads the OpenJDK runtime before
         * calling JLI_Launch.  DroidBridge's earlier GUI bridge only dlopened
         * libjli and then Android/HotSpot aborted immediately after JLI_Launch
         * on some devices.  For GUI installers, match the prior implementation's order: make the
         * JRE native libraries resident first, then enter JLI.
         */
        appendLog("Preloading GUI installer runtime libraries");

        dlopenOptional(new File(PathManager.DIR_NATIVE_LIB, "libSDL3.so"));
        dlopenOptional(new File(PathManager.DIR_NATIVE_LIB, "libSDL2.so"));
        dlopenOptional(new File(PathManager.DIR_NATIVE_LIB, "libspirv-cross.so"));
        dlopenOptional(new File(PathManager.DIR_NATIVE_LIB, "libshaderc.so"));
        dlopenOptional(new File(PathManager.DIR_NATIVE_LIB, "libshaderc_shared.so"));
        dlopenOptional(new File(PathManager.DIR_NATIVE_LIB, "liblwjgl_vma.so"));
        dlopenOptional(new File(PathManager.DIR_NATIVE_LIB, "libopenal.so"));

        preloadLauncherLibraryOnly(paths);
        dlopenOptional(new File(paths.jvmLibraryDir, "libjvm.so"));

        String[] required = new String[]{
                "libverify.so",
                "libjava.so",
                "libnet.so",
                "libnio.so",
                "libawt.so",
                "libawt_headless.so",
                "libfreetype.so",
                "libfontmanager.so",
                "libzip.so",
                "libjawt.so",
                "libawt_xawt.so"
        };
        for (String name : required) {
            dlopenRuntimeLibraryByName(paths.runtimeLibDir, name);
        }

        preloadRuntimeTree(paths.runtimeLibDir);
        preloadRuntimeTree(new File(runtimeDir, "lib"));
    }

    private void dlopenRuntimeLibraryByName(@NonNull File runtimeLibDir, @NonNull String name) {
        if (dlopenOptional(new File(runtimeLibDir, name))) return;
        if (dlopenOptional(new File(runtimeLibDir, "server/" + name))) return;
        //noinspection ResultOfMethodCallIgnored
        dlopenOptional(new File(runtimeLibDir, "client/" + name));
    }

    private void preloadRuntimeTree(@Nullable File root) {
        if (root == null || !root.isDirectory()) return;
        File[] children = root.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                preloadRuntimeTree(child);
            } else if (child.getName().toLowerCase(Locale.US).endsWith(".so")) {
                dlopenOptional(child);
            }
        }
    }

    private boolean dlopenOptional(@Nullable File file) {
        if (file == null || !file.isFile()) return false;
        try {
            boolean loaded = JREUtils.dlopen(file.getAbsolutePath());
            appendLog("dlopen " + file.getName() + " = " + loaded);
            return loaded;
        } catch (Throwable throwable) {
            appendLog("dlopen failed: " + file.getAbsolutePath() + " • "
                    + (throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName()));
            return false;
        }
    }

    private void addExistingPath(@NonNull ArrayList<File> paths, @Nullable File path) {
        if (path == null || !path.isDirectory()) return;
        if (!paths.contains(path)) paths.add(path);
    }

    @NonNull
    private String joinFilePathList(@NonNull ArrayList<File> paths) {
        StringBuilder out = new StringBuilder();
        for (File path : paths) {
            if (out.length() > 0) out.append(':');
            out.append(path.getAbsolutePath());
        }
        return out.toString();
    }

    private static final class RuntimeNativePaths {
        @NonNull final File runtimeLibDir;
        @NonNull final File jvmLibraryDir;
        @NonNull final String ldLibraryPath;
        @NonNull final String nativeLinkerPath;

        RuntimeNativePaths(
                @NonNull File runtimeLibDir,
                @NonNull File jvmLibraryDir,
                @NonNull String ldLibraryPath,
                @NonNull String nativeLinkerPath
        ) {
            this.runtimeLibDir = runtimeLibDir;
            this.jvmLibraryDir = jvmLibraryDir;
            this.ldLibraryPath = ldLibraryPath;
            this.nativeLinkerPath = nativeLinkerPath;
        }
    }

    @NonNull
    private ArrayList<String> splitCommandLine(@NonNull String commandLine) {
        ArrayList<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        char quoteChar = 0;
        boolean escaping = false;

        for (int i = 0; i < commandLine.length(); i++) {
            char ch = commandLine.charAt(i);
            if (escaping) {
                current.append(ch);
                escaping = false;
                continue;
            }
            if (ch == '\\') {
                escaping = true;
                continue;
            }
            if ((ch == '\'' || ch == '"')) {
                if (inQuote && ch == quoteChar) {
                    inQuote = false;
                    quoteChar = 0;
                } else if (!inQuote) {
                    inQuote = true;
                    quoteChar = ch;
                } else {
                    current.append(ch);
                }
                continue;
            }
            if (Character.isWhitespace(ch) && !inQuote) {
                if (current.length() > 0) {
                    out.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(ch);
        }
        if (escaping) current.append('\\');
        if (current.length() > 0) out.add(current.toString());
        return out;
    }

    @Nullable
    private File findJarFromArgs(@NonNull ArrayList<String> args) {
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if ("-jar".equals(arg) && i + 1 < args.size()) {
                File file = new File(args.get(i + 1));
                return file.isFile() ? file : null;
            }
            if (arg.toLowerCase(Locale.US).endsWith(".jar")) {
                File file = new File(arg);
                if (file.isFile()) return file;
            }
        }
        return null;
    }

    @NonNull
    private String safeJoinForLog(@NonNull ArrayList<String> args) {
        StringBuilder builder = new StringBuilder();
        for (String arg : args) {
            if (builder.length() > 0) builder.append(' ');
            if (arg.indexOf(' ') >= 0 || arg.indexOf('\t') >= 0) {
                builder.append('"').append(arg.replace("\"", "\\\"")).append('"');
            } else {
                builder.append(arg);
            }
        }
        return builder.toString();
    }

    private void tapAwtKey(char keyChar, int awtCode) {
        AWTInputBridge.sendKeyTap(keyChar, awtCode);
        if (awtCanvasView != null) awtCanvasView.requestFocus();
    }

    private void sendTextToAwt(@NonNull String text) {
        for (int i = 0; i < text.length(); i++) {
            AWTInputBridge.sendChar(text.charAt(i));
        }
    }

    private void pasteClipboardToAwt() {
        try {
            ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager == null || !manager.hasPrimaryClip()) return;
            ClipData clipData = manager.getPrimaryClip();
            if (clipData == null || clipData.getItemCount() == 0) return;
            CharSequence text = clipData.getItemAt(0).coerceToText(this);
            if (text != null) sendTextToAwt(text.toString());
        } catch (Throwable throwable) {
            appendLog("Paste failed: " + throwable);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (getCurrentFocus() instanceof EditText) {
            return super.dispatchKeyEvent(event);
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN || event.getAction() == KeyEvent.ACTION_UP) {
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            int awtCode = mapAndroidKeyCodeToAwt(event.getKeyCode(), event);
            char keyChar = (char) Math.max(0, event.getUnicodeChar());
            if (awtCode != 0) {
                AWTInputBridge.sendKey(keyChar, awtCode, down);
                if (!down && keyChar >= 32 && keyChar != 127) {
                    AWTInputBridge.sendChar(keyChar);
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private int mapAndroidKeyCodeToAwt(int androidKeyCode, @NonNull KeyEvent event) {
        int unicode = event.getUnicodeChar();
        if (unicode >= 'a' && unicode <= 'z') return Character.toUpperCase(unicode);
        if (unicode >= 'A' && unicode <= 'Z') return unicode;
        if (unicode >= '0' && unicode <= '9') return unicode;
        switch (androidKeyCode) {
            case KeyEvent.KEYCODE_ENTER:
                return VK_ENTER;
            case KeyEvent.KEYCODE_DEL:
                return VK_BACK_SPACE;
            case KeyEvent.KEYCODE_TAB:
                return VK_TAB;
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_BACK:
                return VK_ESCAPE;
            case KeyEvent.KEYCODE_FORWARD_DEL:
                return VK_DELETE;
            case KeyEvent.KEYCODE_C:
                return VK_C;
            case KeyEvent.KEYCODE_V:
                return VK_V;
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT:
                return VK_CONTROL;
            default:
                if (unicode >= 32 && unicode <= 126) return unicode;
                return 0;
        }
    }

    private void appendLog(@NonNull String line) {
        Logging.i(TAG, line);
        synchronized (pendingLogLines) {
            pendingLogLines.add(line);
            if (pendingLogLines.size() > 250) {
                pendingLogLines.remove(0);
            }
        }
        mainHandler.post(() -> {
            if (logTextView == null) return;
            flushPendingLogsToView();
            if (logScrollView != null) {
                logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    private void flushPendingLogsToView() {
        if (logTextView == null) return;
        List<String> copy;
        synchronized (pendingLogLines) {
            if (pendingLogLines.isEmpty()) return;
            copy = new ArrayList<>(pendingLogLines);
            pendingLogLines.clear();
        }
        for (String line : copy) {
            logTextView.append(line + "\n");
        }
        if (logScrollView != null) {
            logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void showToast(@NonNull String text) {
        mainHandler.post(() -> Toast.makeText(this, text, Toast.LENGTH_LONG).show());
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void copy(@NonNull InputStream input, @NonNull OutputStream output) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
    }

    private static final class RuntimeChoice {
        final String name;
        final File dir;

        RuntimeChoice(@NonNull String name, @NonNull File dir) {
            this.name = name;
            this.dir = dir;
        }
    }

    private static final class LaunchRequest {
        final String runtimeName;
        final File workingDirectory;
        final ArrayList<String> jvmArgs;

        LaunchRequest(@NonNull String runtimeName, @NonNull File workingDirectory, @NonNull ArrayList<String> jvmArgs) {
            this.runtimeName = runtimeName;
            this.workingDirectory = workingDirectory;
            this.jvmArgs = jvmArgs;
        }
    }
}
