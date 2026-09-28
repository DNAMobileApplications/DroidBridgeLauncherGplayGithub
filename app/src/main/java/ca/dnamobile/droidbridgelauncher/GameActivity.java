/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, or any third-party project.
 *
 * Files written entirely by DNA Mobile Applications are proprietary unless
 * a file header or separate license notice states otherwise.
 */

package ca.dnamobile.droidbridgelauncher;

import android.annotation.SuppressLint;
import android.Manifest;
import androidx.appcompat.app.AlertDialog;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioRecord;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioFormat;
import android.media.MediaRecorder;
import android.media.MediaPlayer;
import android.media.projection.MediaProjectionManager;
import android.hardware.display.DisplayManager;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.system.Os;
import android.view.Display;
import android.view.DisplayCutout;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import ca.dnamobile.droidbridgelauncher.controls.ControlsActivity;
import ca.dnamobile.droidbridgelauncher.controls.InGameControlsEditorDialog;
import ca.dnamobile.droidbridgelauncher.controls.ControlsPreferences;
import ca.dnamobile.droidbridgelauncher.controls.GameImeViewportController;
import ca.dnamobile.droidbridgelauncher.controls.TouchControlsLayoutData;
import ca.dnamobile.droidbridgelauncher.controls.TouchControlsOverlay;
import ca.dnamobile.droidbridgelauncher.controls.TouchControlsStore;
import ca.dnamobile.droidbridgelauncher.data.AccountStore;
import ca.dnamobile.droidbridgelauncher.fancymenu.MicrosoftAuthConfigPersonal;
import ca.dnamobile.droidbridgelauncher.fancymenu.MicrosoftAuthManagerPersonal;
import ca.dnamobile.droidbridgelauncher.databinding.ActivityGameBinding;
import ca.dnamobile.droidbridgelauncher.dualscreen.AynThorDisplayCompat;
import ca.dnamobile.droidbridgelauncher.dualscreen.DualScreenRefreshCompat;
import ca.dnamobile.droidbridgelauncher.dualscreen.DualScreenController;
import ca.dnamobile.droidbridgelauncher.dualscreen.DualScreenSwapActionBus;
import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.game.FloatingGameSettingsOverlayController;
import ca.dnamobile.droidbridgelauncher.input.GameCursorOverlay;
import ca.dnamobile.droidbridgelauncher.input.GamepadButton;
import ca.dnamobile.droidbridgelauncher.input.GamepadInputController;
import ca.dnamobile.droidbridgelauncher.input.GyroInputController;
import ca.dnamobile.droidbridgelauncher.input.InputEventDiagnosticLogger;
import ca.dnamobile.droidbridgelauncher.input.GamepadMappingDialog;
import ca.dnamobile.droidbridgelauncher.instance.LauncherInstance;
import ca.dnamobile.droidbridgelauncher.instance.LauncherInstanceManager;
import ca.dnamobile.droidbridgelauncher.launcher.InstanceLaunchSettings;
import ca.dnamobile.droidbridgelauncher.launcher.DroidBridgeLaunchActivity;
import ca.dnamobile.droidbridgelauncher.launcher.LaunchGame;
import ca.dnamobile.droidbridgelauncher.logs.LauncherLogManager;
import ca.dnamobile.droidbridgelauncher.modcompat.ControlifySDL;
import ca.dnamobile.droidbridgelauncher.modcompat.ControllerModCompat;
import ca.dnamobile.droidbridgelauncher.modcompat.TouchControllerModCompat;
import ca.dnamobile.droidbridgelauncher.modcompat.FFmpegPluginCompat;
import ca.dnamobile.droidbridgelauncher.modcompat.ModPreLaunchWarningManager;
import ca.dnamobile.droidbridgelauncher.renderer.DriverPluginManager;
import ca.dnamobile.droidbridgelauncher.renderer.KopperZinkRenderer;
import ca.dnamobile.droidbridgelauncher.renderer.DroidBridgeMesaSupport;
import ca.dnamobile.droidbridgelauncher.renderer.BtaRendererPolicy;
import ca.dnamobile.droidbridgelauncher.renderer.DroidBridgeNativeGlfwKgslRenderer;
import ca.dnamobile.droidbridgelauncher.renderer.DroidBridgeRenderSpec;
import ca.dnamobile.droidbridgelauncher.renderer.MobileGluesConfigHelper;
import ca.dnamobile.droidbridgelauncher.renderer.RendererInterface;
import ca.dnamobile.droidbridgelauncher.renderer.Renderers;
import ca.dnamobile.droidbridgelauncher.settings.GameResolutionSettings;
import ca.dnamobile.droidbridgelauncher.settings.LauncherPreferences;
import ca.dnamobile.droidbridgelauncher.recording.GameRecordingService;
import ca.dnamobile.droidbridgelauncher.recording.DualScreenRecordingSession;
import ca.dnamobile.droidbridgelauncher.recording.RecordingPreferences;
import ca.dnamobile.droidbridgelauncher.recording.RecordingFrameBridge;
import ca.dnamobile.droidbridgelauncher.recording.PrimaryRecordingFramePump;
import ca.dnamobile.droidbridgelauncher.security.LauncherSecurity;
import ca.dnamobile.droidbridgelauncher.utils.AppOrientationHelper;
import ca.dnamobile.droidbridgelauncher.utils.FullscreenUtils;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;
import ca.dnamobile.droidbridgelauncher.runtime.Logger;
import ca.dnamobile.droidbridgelauncher.runtime.MinecraftGLSurface;
import ca.dnamobile.droidbridgelauncher.runtime.DroidBridgeSDL3Bootstrap;

import org.libsdl.app.SDLControllerManager;
import org.lwjgl.glfw.CallbackBridge;
import ca.dnamobile.droidbridgelauncher.runtime.LwjglGlfwKeycode;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;


@SuppressLint("CustomSplashScreen")
public class GameActivity extends AppCompatActivity {
    private static final int COLOR_DIALOG_BG = Color.rgb(30, 34, 42);
    private static final int COLOR_CARD_BG = Color.rgb(38, 43, 53);
    private static final int COLOR_CARD_BG_PRESSED = Color.rgb(45, 51, 63);
    private static final int COLOR_CARD_STROKE = Color.rgb(54, 61, 74);
    private static final int COLOR_TEXT_PRIMARY = Color.rgb(238, 241, 248);
    private static final int COLOR_TEXT_SECONDARY = Color.rgb(198, 204, 216);
    private static final int COLOR_TEXT_MUTED = Color.rgb(150, 159, 176);
    private static final int COLOR_ACCENT = Color.rgb(37, 211, 128);
    private static final int COLOR_DANGER = Color.rgb(255, 108, 108);
    private static final String REPLAY_MOD_FFMPEG_INFO_URL =
            "https://github.com/DNAMobileApplications/DroidBridgeLauncherFFMPEG/releases/";
    private static final String VULKAN_EXTENSION_CHECKER_INSTALL_URL =
            "https://drive.google.com/file/d/1Gnpq_ndy3Qz916Y6i9KeA3qaHJKGVsbW/view?usp=sharing";
    private static final int REQUEST_GAME_RECORDING_CAPTURE = 7410;
    private static final int REQUEST_GAME_RECORDING_AUDIO_PERMISSION = 7411;

    public static final String EXTRA_VERSION_ID = "ca.dnamobile.droidbridgelauncher.extra.VERSION_ID";
    public static final String EXTRA_INSTANCE_SETTINGS_KEY =
            "ca.dnamobile.droidbridgelauncher.extra.INSTANCE_SETTINGS_KEY";
    public static final String EXTRA_QUICK_PLAY_WORLD =
            "ca.dnamobile.droidbridgelauncher.extra.QUICK_PLAY_WORLD";
    public static final String EXTRA_QUICK_PLAY_SERVER =
            "ca.dnamobile.droidbridgelauncher.extra.QUICK_PLAY_SERVER";

    private ActivityGameBinding binding;
    private GamepadInputController gamepadInputController;
    @Nullable
    private GyroInputController gyroInputController;
    private GameCursorOverlay gameCursorOverlay;
    private TouchControlsOverlay touchControlsOverlay;
    private int activeInGameMenuShortcutKeyCode = KeyEvent.KEYCODE_UNKNOWN;
    private int activeInGameMenuShortcutDeviceId = -1;
    @Nullable
    private AccountStore accountStore;
    @Nullable
    private MicrosoftAuthManagerPersonal microsoftAuthManager;
    private String versionId;
    @NonNull
    private String compatibilityVersionId = "";
    @NonNull
    private String instanceSettingsKey = "";
    @Nullable
    private String quickPlayWorld;
    @Nullable
    private String quickPlayServer;
    private boolean launchStarted;
    private boolean exiting;
    private boolean replayModFfmpegWarningPending;
    private boolean replayModFfmpegWarningAccepted;
    private boolean modPreLaunchWarningPending;
    @Nullable
    private String acceptedModPreLaunchWarningRuleId;
    private boolean microsoftAccountPreflightPending;
    private boolean microsoftAccountPreflightCompleted;
    private boolean microsoftAccountPreflightAcceptedAfterWarning;
    private Handler logOverlayHandler;
    private Runnable logOverlayRunnable;
    private long lastLogOverlayLength = -1L;
    private long lastLogOverlayModified = -1L;
    private Handler quitWatchdogHandler;
    private Runnable quitWatchdogRunnable;
    private boolean quitWatchdogForceScheduled;
    private boolean legacy4jGlfwFallbackAllowed;
    private boolean legacy4jFallbackLogged;
    private boolean btaGamepadMapperLogged;
    private long lastLegacy4jFallbackProbeMs;
    private FloatingGameSettingsOverlayController floatingGameSettingsOverlayController;
    private long androidVirtualMouseUiReleaseSuppressUntilMs;
    @Nullable
    private DualScreenController dualScreenController;
    @Nullable
    private MinecraftGLSurface minecraftSurface;
    @Nullable
    private AlertDialog inGameControlsDialog;
    @Nullable
    private InGameControlsEditorDialog inGameControlsEditorDialog;
    private boolean recordingStateReceiverRegistered;
    private boolean recordingTouchControlsHidden;
    @Nullable
    private PrimaryRecordingFramePump primaryRecordingFramePump;
    private long primaryRecordingFramePumpSessionId;
    private boolean recordingFrameBridgeReceiverRegistered;
    private final BroadcastReceiver gameRecordingStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !GameRecordingService.ACTION_STATE.equals(intent.getAction())) return;
            String state = intent.getStringExtra(GameRecordingService.EXTRA_STATE);
            String message = intent.getStringExtra(GameRecordingService.EXTRA_MESSAGE);
            if (GameRecordingService.STATE_STARTED.equals(state)) {
                applyRecordingTouchControlVisibility(true);
                if (message != null && !message.trim().isEmpty()) {
                    Toast.makeText(GameActivity.this, message, Toast.LENGTH_SHORT).show();
                }
                return;
            }

            if (GameRecordingService.STATE_STOPPED.equals(state)
                    || GameRecordingService.STATE_ERROR.equals(state)) {
                DualScreenRecordingSession.stopAsync();
                applyRecordingTouchControlVisibility(false);
                if (message != null && !message.trim().isEmpty()) {
                    Toast.makeText(GameActivity.this, message, Toast.LENGTH_LONG).show();
                }
            }
        }
    };
    private final BroadcastReceiver recordingFrameBridgeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            long sessionId = intent.getLongExtra(
                    RecordingFrameBridge.EXTRA_SESSION, Long.MIN_VALUE);

            if (RecordingFrameBridge.ACTION_DETACH.equals(action)) {
                if (sessionId == primaryRecordingFramePumpSessionId) {
                    stopPrimaryRecordingFramePump();
                }
                return;
            }

            if (!RecordingFrameBridge.ACTION_ATTACH.equals(action)) return;

            android.view.Surface encoderSurface = RecordingFrameBridge.readSurface(intent);
            int width = intent.getIntExtra(RecordingFrameBridge.EXTRA_WIDTH, 0);
            int height = intent.getIntExtra(RecordingFrameBridge.EXTRA_HEIGHT, 0);
            int fps = intent.getIntExtra(RecordingFrameBridge.EXTRA_FPS, 30);

            stopPrimaryRecordingFramePump();
            boolean success = false;
            String detail = null;
            try {
                if (encoderSurface == null || !encoderSurface.isValid()) {
                    detail = "Encoder surface is unavailable";
                } else if (minecraftSurface == null || !minecraftSurface.isRecordingFrameCaptureReady()) {
                    detail = "Minecraft render surface is not ready";
                } else {
                    PrimaryRecordingFramePump pump = new PrimaryRecordingFramePump(
                            minecraftSurface, encoderSurface, width, height, fps);
                    success = pump.start();
                    if (success) {
                        primaryRecordingFramePump = pump;
                        primaryRecordingFramePumpSessionId = sessionId;
                        detail = "Minecraft render-only frame pump attached";
                    } else {
                        pump.stop();
                        detail = "Minecraft render-only frame pump could not start";
                    }
                }
            } catch (Throwable throwable) {
                detail = throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
                Logging.e("GameActivity", "Unable to attach clean recording frame pump", throwable);
                if (encoderSurface != null) {
                    try { encoderSurface.release(); } catch (Throwable ignored) {}
                }
            }

            RecordingFrameBridge.sendAck(
                    GameActivity.this, sessionId, success, detail);
        }
    };
    @Nullable
    private OnBackPressedCallback androidBackPressedCallback;
    private long lastAndroidBackHandledUptimeMs;
    private static final long ANDROID_BACK_DUPLICATE_WINDOW_MS = 300L;
    private final Handler surfaceRefreshHandler = new Handler(Looper.getMainLooper());
    private final Handler launchStatusHandler = new Handler(Looper.getMainLooper());
    private boolean gameRenderingStarted;
    @Nullable
    private Runnable launchStatusAutoHideRunnable;
    private long quitWatchdogSessionStartWallMs;
    @Nullable
    private DisplayManager liveVsyncDisplayManager;
    @Nullable
    private DisplayManager.DisplayListener liveVsyncDisplayListener;
    private int lastLiveVsyncTargetFps = -1;
    @NonNull
    private String pendingLiveVsyncRefreshReason = "manual";
    private final Runnable liveVsyncRefreshRunnable = () -> updateLiveVsyncTargetFromDisplay(pendingLiveVsyncRefreshReason);
    private boolean liveVsyncPulseRunning;
    @Nullable
    private Runnable pendingMinecraftSurfaceRefreshRunnable;
    @Nullable
    private File liveVsyncOptionsFile;
    private long lastLiveVsyncOptionsModified = Long.MIN_VALUE;
    private long lastLiveVsyncOptionsLength = Long.MIN_VALUE;
    private long lastLiveVsyncOptionsReadUptimeMs;
    @Nullable
    private Boolean lastLiveMinecraftVsyncEnabled;
    private boolean liveMinecraftVsyncNativeApplied;
    private boolean liveVsyncTargetNativeApplied;
    @Nullable
    private Object lastSurfaceFrameRateHintSurface;
    private float lastSurfaceFrameRateHintHz = Float.NaN;
    private int lastSurfaceFrameRateHintCompatibility = Integer.MIN_VALUE;
    @Nullable
    private Boolean lastAppliedSustainedPerformance;
    private final Runnable liveVsyncPulseRunnable = new Runnable() {
        @Override
        public void run() {
            if (!liveVsyncPulseRunning || isFinishing() || isDestroyed()) return;
            // Minecraft writes options.txt when its VSync toggle changes. Sync only
            // that state here; display-rate updates are event driven and must not be
            // re-applied every second because Surface.setFrameRate may trigger a mode
            // transition and a visible frame drop.
            syncMinecraftVsyncFromOptions(false, "optionsPulse");
            if (!liveVsyncTargetNativeApplied) {
                updateLiveVsyncTargetFromDisplay("nativeRetry");
            }
            surfaceRefreshHandler.postDelayed(this, 750L);
        }
    };
    private static final int DROIDBRIDGE_ANDROID_TTS_PROXY_PORT = 38527;
    private static final int DROIDBRIDGE_ANDROID_MIC_PROXY_PORT = 38528;

    private static final String[] DROIDBRIDGE_ANDROID_TTS_ENGINE_CANDIDATES = new String[]{
            "com.google.android.tts",
            "com.samsung.SMT",
            "com.svox.pico",
            "com.huawei.vassistant",
            "com.iflytek.speechsuite"
    };
    @Nullable
    private TextToSpeech droidBridgeAndroidTts;
    @Nullable
    private MediaPlayer droidBridgeAndroidTtsMediaPlayer;
    @Nullable
    private android.media.AudioTrack droidBridgeAndroidTtsAudioTrack;
    @Nullable
    private DatagramSocket droidBridgeAndroidTtsSocket;
    @Nullable
    private ServerSocket droidBridgeAndroidTtsServerSocket;
    @Nullable
    private Thread droidBridgeAndroidTtsTcpThread;
    @Nullable
    private Thread droidBridgeAndroidTtsThread;
    @Nullable
    private android.os.HandlerThread droidBridgeAndroidTtsSpeakThread;
    @Nullable
    private Handler droidBridgeAndroidTtsSpeakHandler;
    private volatile boolean droidBridgeAndroidTtsProxyRunning;
    private volatile boolean droidBridgeAndroidTtsReady;
    private volatile boolean droidBridgeAndroidTtsInitialized;
    private volatile boolean droidBridgeAndroidTtsUnavailable;
    private volatile boolean droidBridgeAndroidTtsInitializing;
    private int droidBridgeAndroidTtsInitAttempt;
    private final Handler droidBridgeAndroidTtsHandler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> droidBridgeAndroidTtsPendingSpeech = new ArrayDeque<>();
    private final java.util.concurrent.ConcurrentHashMap<String, File> droidBridgeAndroidTtsSynthesisFiles = new java.util.concurrent.ConcurrentHashMap<>();
    private final Object droidBridgeAndroidTtsLock = new Object();
    private long droidBridgeAndroidTtsLastVerboseLogMs;
    private volatile long droidBridgeAndroidTtsLastStopPacketMs;
    private volatile long droidBridgeAndroidTtsLastCancelLogMs;
    private volatile long droidBridgeAndroidTtsPlaybackGeneration;
    private volatile boolean droidBridgeAndroidTtsInventoryReported;
    @Nullable private Logger.eventLogListener droidBridgeVerityTtsFallbackLogListener;
    @Nullable private volatile String droidBridgeAndroidTtsLastProxyText;
    private volatile long droidBridgeAndroidTtsLastProxyTextUptimeMs;
    @Nullable private volatile String droidBridgeVerityLastSelectedVoiceText;
    private volatile long droidBridgeVerityLastSelectedVoiceUptimeMs;
    @Nullable private volatile String droidBridgeVerityVoiceAttemptText;
    private volatile long droidBridgeVerityVoiceAttemptUptimeMs;
    private volatile long droidBridgeVerityVoiceAttemptGeneration;
    private volatile long droidBridgeVerityVoiceResolvedGeneration;
    @Nullable private volatile String droidBridgeAndroidTtsLastFallbackText;
    private volatile long droidBridgeAndroidTtsLastFallbackTextUptimeMs;
    @Nullable private volatile String droidBridgeVerityPendingTtsPayload;
    private volatile long droidBridgeVerityPendingTtsPayloadUptimeMs;
    private volatile long droidBridgeVerityIgnoreStopUntilUptimeMs;
    private volatile long droidBridgeVerityLastUserChatUptimeMs;
    private volatile long droidBridgeVerityMicFallbackGeneration;
    @Nullable private volatile Boolean droidBridgeVerity6Installed;
    @Nullable
    private volatile String droidBridgeAndroidGroqTtsApiKey;
    @NonNull
    private volatile String droidBridgeAndroidGroqTtsVoice = "daniel";
    @Nullable
    private ServerSocket droidBridgeAndroidMicServerSocket;
    @Nullable
    private Thread droidBridgeAndroidMicServerThread;
    @Nullable
    private AudioRecord droidBridgeAndroidMicRecorder;
    @Nullable
    private Thread droidBridgeAndroidMicThread;
    @Nullable
    private ByteArrayOutputStream droidBridgeAndroidMicBuffer;
    private volatile boolean droidBridgeAndroidMicProxyRunning;
    private volatile boolean droidBridgeAndroidMicRecording;
    private final Object droidBridgeAndroidMicLock = new Object();
    private final Object droidBridgeAndroidMicProxyServerLock = new Object();


    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ControlsPreferences.beginVirtualMouseLaunchSession(this);
        installAndroidBackDispatcher();
        // Hardware volume buttons must always control Minecraft/OpenAL media audio,
        // even while the focusable SurfaceView/SDL input bridge owns key dispatch.
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        AppOrientationHelper.applyToGameActivity(this);
        applySustainedPerformanceMode();
        installGameActivityCrashLogger();
        quitWatchdogSessionStartWallMs = System.currentTimeMillis();
        PathManager.initContextConstants(this);
        startDroidBridgeAndroidNarratorProxy();
        startDroidBridgeAndroidMicProxy();

        versionId = getIntent().getStringExtra(EXTRA_VERSION_ID);
        if (versionId == null || versionId.trim().isEmpty()) {
            throw new IllegalStateException("No version id was provided to GameActivity.");
        }
        String explicitSettingsKey = getIntent().getStringExtra(EXTRA_INSTANCE_SETTINGS_KEY);
        compatibilityVersionId = resolveCompatibilityVersionId(versionId, explicitSettingsKey);
        // Android-side marker used only by the Dual-screen HUD exact-icon bridge. 26.2 now
        // stays on the proven v9 mailbox transport, but this marker lets the bridge keep the
        // longer cold-start retry window required by a brand-new 26.2 GuiItemAtlas.
        System.setProperty("droidbridge.dualscreen.android.mc26_2",
                "26.2".equalsIgnoreCase(compatibilityVersionId.trim()) ? "true" : "false");
        DroidBridgeSDL3Bootstrap.configure(this, compatibilityVersionId);
        instanceSettingsKey = InstanceLaunchSettings.resolveInstanceKey(
                explicitSettingsKey,
                versionId
        );
        Logging.i("GameActivity", "Launch identity versionId=" + versionId
                + " compatibilityVersionId=" + compatibilityVersionId
                + " instanceSettingsKey=" + instanceSettingsKey
                + " explicit=" + (explicitSettingsKey != null && !explicitSettingsKey.trim().isEmpty()));

        InstanceLaunchSettings.Settings launchSettings =
                InstanceLaunchSettings.load(this, instanceSettingsKey);
        GameResolutionSettings.Profile resolutionOverride =
                InstanceLaunchSettings.resolveResolutionProfileOverride(launchSettings);
        GameResolutionSettings.setRuntimeProfileOverride(resolutionOverride);
        if (resolutionOverride != null) {
            GameResolutionSettings.ResolvedResolution loggedResolution =
                    GameResolutionSettings.resolveBaseResolution(resolutionOverride, 1, 1);
            Logging.i("GameActivity", "Per-instance game resolution override="
                    + resolutionOverride.mode + " "
                    + loggedResolution.width + "x" + loggedResolution.height);
        }

        quickPlayWorld = getIntent().getStringExtra(EXTRA_QUICK_PLAY_WORLD);
        quickPlayServer = getIntent().getStringExtra(EXTRA_QUICK_PLAY_SERVER);

        prepareRendererEnvironmentBeforeBridgeLoad();
        CallbackBridge.init(this);
        configureDroidBridgeRenderSpecAfterBridgeLoad();

        configureInputBridgeForVersion(versionId);
        // BTA uses its own controller/input-type system. Keep the old hardcoded
        // MinecraftGLSurface fallback disabled, but let GamepadInputController feed
        // the BTA-only LWJGL virtual controller so BTA can report/use a real controller.
        MinecraftGLSurface.legacyBtaInputFallbackEnabled = false;
        boolean btaLaunch = isBetterThanAdventureLaunch();
        GamepadInputController.btaNativeControllerBridgeEnabled = btaLaunch;
        GamepadInputController.btaNativeControllerOwnsLauncherInput = btaLaunch;
        GamepadInputController.glfwGamepadMirrorEnabled = false;
        if (GamepadInputController.btaNativeControllerBridgeEnabled) {
            GamepadInputController.initializeBtaNativeControllerBridge(this);
        }
        CallbackBridge.setInputReady(true);
        configureWindow();

        binding = ActivityGameBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        registerGameRecordingStateReceiver();
        registerRecordingFrameBridgeReceiver();
        minecraftSurface = binding.minecraftSurface;
        configureDualScreenController();
        if (dualScreenController != null) {
            dualScreenController.restoreOrEnableInitialMode(false);
        }
        applyGameSurfaceLayoutMode(minecraftSurface);
        configureActiveMinecraftSurface(minecraftSurface);
        applyGameDisplaySurfaceOptions();
        setupMicrosoftAccountPreflightManager();

        installCursorOverlay();
        installTouchControlsOverlay();
        configureLogOverlay();
        configureInGameSettingsButton();
        startQuitWatchdog();

        gamepadInputController = new GamepadInputController(
                binding.getRoot(),
                () -> runOnUiThread(this::openInGameButtonOverlay),
                delta -> {
                    DualScreenController controller = dualScreenController;
                    if (controller != null) {
                        controller.onMappedHotbarScroll(delta);
                    }
                }
        );
        gyroInputController = new GyroInputController(this);

        configureWindow();
        appendStatus(dualScreenController != null && dualScreenController.isShowing()
                ? "Dual screen active: game and controls are split across the two displays."
                : getString(R.string.game_surface_waiting));

        startLiveVsyncRefreshMonitor();
        syncMinecraftVsyncFromOptions(true, "onCreate");
        updateLiveVsyncTargetFromDisplay();

        if (minecraftSurface != null) {
            minecraftSurface.start(false);
        }
    }


    private void startDroidBridgeAndroidNarratorProxy() {
        if (droidBridgeAndroidTtsProxyRunning) {
            return;
        }

        droidBridgeAndroidTtsProxyRunning = true;
        droidBridgeAndroidTtsUnavailable = false;
        ensureDroidBridgeAndroidTtsSpeakHandler();
        // Verity provider routes are authoritative. Do not watch latestlog and
        // synthesize missing replies through the device narrator: that old safety
        // path could replay stale responses after LOCAL/voice changes. v72
        droidBridgeAndroidTtsThread = new Thread(this::runDroidBridgeAndroidNarratorProxy,
                "DroidBridge-Android-TTS-UDP-Proxy");
        droidBridgeAndroidTtsThread.setDaemon(true);
        droidBridgeAndroidTtsThread.start();
        droidBridgeAndroidTtsTcpThread = new Thread(this::runDroidBridgeAndroidNarratorTcpProxy,
                "DroidBridge-Android-TTS-TCP-Proxy");
        droidBridgeAndroidTtsTcpThread.setDaemon(true);
        droidBridgeAndroidTtsTcpThread.start();
        appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator TCP/UDP media proxy: start requested port="
                + DROIDBRIDGE_ANDROID_TTS_PROXY_PORT + " v42");

        runOnUiThread(this::initializeDroidBridgeAndroidNarratorTts);
    }

    private void installDroidBridgeVerityTtsFallbackListenerWhenReady() {
        if (!droidBridgeAndroidTtsProxyRunning || isFinishing() || isDestroyed()) return;
        if (!Logger.isNativeLogReady()) {
            droidBridgeAndroidTtsHandler.postDelayed(
                    this::installDroidBridgeVerityTtsFallbackListenerWhenReady,
                    250L
            );
            return;
        }
        installDroidBridgeVerityTtsFallbackListener();
    }

    private void installDroidBridgeVerityTtsFallbackListener() {
        if (droidBridgeVerityTtsFallbackLogListener != null) return;

        Logger.eventLogListener listener = line -> {
            if (line == null || !droidBridgeAndroidTtsProxyRunning) return;
            String trimmedLine = line.trim();
            long now = SystemClock.uptimeMillis();

            // Used by the Verity 6 STT fallback to avoid inserting a duplicate if
            // the mod's own recognizer succeeds before DroidBridge's safety path.
            if (trimmedLine.contains("[CHAT] <")
                    && !trimmedLine.toLowerCase(Locale.ROOT).contains("<verity>")) {
                droidBridgeVerityLastUserChatUptimeMs = now;
            }

            final String marker = "Client successfully received TTS Payload:";
            int markerIndex = trimmedLine.indexOf(marker);
            if (markerIndex >= 0) {
                String text = trimmedLine.substring(markerIndex + marker.length()).trim();
                if (text.startsWith("SAY_NOW\n")) {
                    text = text.substring("SAY_NOW\n".length()).trim();
                } else if (text.startsWith("SAY\n")) {
                    text = text.substring("SAY\n".length()).trim();
                }
                text = text.replace("\\n", " ").trim();
                if (!text.isEmpty() && !shouldSkipDroidBridgeAndroidNarratorText(text)) {
                    if (text.length() > 1000) text = text.substring(0, 1000);
                    droidBridgeVerityPendingTtsPayload = text;
                    droidBridgeVerityPendingTtsPayloadUptimeMs = now;
                    scheduleDroidBridgeVerityMissingVoiceRouteFallback(text, now);
                }
            }

            String lower = trimmedLine.toLowerCase(Locale.ROOT);
            boolean localVoiceFailure = (lower.contains("[verity local tts]")
                    && (lower.contains("failed")
                    || lower.contains("no audio")
                    || lower.contains("engine failed")))
                    || lower.contains("no sherpa-onnx-jni in java.library.path")
                    || lower.contains("could not initialize sherpa")
                    || lower.contains("failed to initialize sherpa");

            // Verity 6.1 can label Local Voices as selected but still route the
            // payload through its cloud TTS client. An empty/stale key then produces
            // "[Verity TTS Error]: Invalid API Key" and no audio. Treat any Verity
            // TTS error that follows a recent payload as a confirmed local-engine
            // failure and speak the pending payload through Android TTS instead.
            boolean verityTtsFailure = (lower.contains("[verity tts error]")
                    || lower.contains("verity tts error"))
                    && droidBridgeVerityPendingTtsPayload != null
                    && now - droidBridgeVerityPendingTtsPayloadUptimeMs <= 12000L;
            boolean selectedVoiceSilentRoute = (lower.contains("route=silent")
                    || lower.contains("requires a groq key")
                    || lower.contains("fallback remains suppressed")
                    || lower.contains("selected voice endpoint failed")
                    || lower.contains("orpheus bridge failed"))
                    && droidBridgeVerityPendingTtsPayload != null
                    && now - droidBridgeVerityPendingTtsPayloadUptimeMs <= 12000L;
            if (localVoiceFailure || verityTtsFailure || selectedVoiceSilentRoute) {
                forceDroidBridgeVerityLocalVoiceFallback();
            }
        };

        droidBridgeVerityTtsFallbackLogListener = listener;
        Logger.addLogListener(listener);
    }


    /**
     * Verity has already delivered a reply to the client at this point. The selected
     * voice bridge must claim it almost immediately. If no LOCAL_CLAIM/GROQ_TTS/SAY
     * packet follows, the mod selected a provider branch that never reached the
     * launcher. Use Android narration as a last-resort safety net without delaying
     * or duplicating a real Piper/Orpheus attempt.
     */
    private void scheduleDroidBridgeVerityMissingVoiceRouteFallback(
            @NonNull String text,
            long payloadUptimeMs
    ) {
        final String payloadText = text;
        droidBridgeAndroidTtsHandler.postDelayed(() -> {
            if (!droidBridgeAndroidTtsProxyRunning || isFinishing() || isDestroyed()) return;
            long now = SystemClock.uptimeMillis();
            synchronized (droidBridgeAndroidTtsLock) {
                if (!sameDroidBridgeNarratorText(droidBridgeVerityPendingTtsPayload, payloadText)) return;
                if (droidBridgeVerityPendingTtsPayloadUptimeMs != payloadUptimeMs) return;

                boolean selectedAttemptStarted = droidBridgeVerityVoiceAttemptUptimeMs >= payloadUptimeMs
                        && sameDroidBridgeNarratorText(droidBridgeVerityVoiceAttemptText, payloadText);
                boolean selectedPlaybackStarted = droidBridgeVerityLastSelectedVoiceUptimeMs >= payloadUptimeMs
                        && sameDroidBridgeNarratorText(droidBridgeVerityLastSelectedVoiceText, payloadText);
                boolean proxyPacketArrived = droidBridgeAndroidTtsLastProxyTextUptimeMs >= payloadUptimeMs
                        && sameDroidBridgeNarratorText(droidBridgeAndroidTtsLastProxyText, payloadText);
                if (selectedAttemptStarted || selectedPlaybackStarted || proxyPacketArrived) return;
            }
            fallbackDroidBridgeVerityVoiceAttempt(
                    payloadText,
                    "no selected-voice packet within 3000 ms"
            );
        }, 3000L);
    }

    private void scheduleDroidBridgeVerityLoggedPayloadFallback(
            @NonNull String fallbackText,
            long delayMs,
            boolean localVoiceFailure
    ) {
        droidBridgeAndroidTtsHandler.postDelayed(() -> {
            if (!droidBridgeAndroidTtsProxyRunning || isFinishing() || isDestroyed()) return;
            long now = SystemClock.uptimeMillis();

            String proxyText = droidBridgeAndroidTtsLastProxyText;
            if (!localVoiceFailure
                    && sameDroidBridgeNarratorText(proxyText, fallbackText)
                    && now - droidBridgeAndroidTtsLastProxyTextUptimeMs < 2200L) {
                return;
            }

            String previousFallback = droidBridgeAndroidTtsLastFallbackText;
            if (sameDroidBridgeNarratorText(previousFallback, fallbackText)
                    && now - droidBridgeAndroidTtsLastFallbackTextUptimeMs < 2200L) {
                return;
            }

            if (localVoiceFailure) {
                // Verity sends STOP after its failed local engine. Keep that stale
                // packet from immediately cancelling Android's replacement speech.
                droidBridgeVerityIgnoreStopUntilUptimeMs = now + 5000L;
            }
            droidBridgeAndroidTtsLastFallbackText = fallbackText;
            droidBridgeAndroidTtsLastFallbackTextUptimeMs = now;
            appendDroidBridgeAndroidProxyLog(
                    "DroidBridge Verity Android TTS fallback: routing logged payload directly len="
                            + fallbackText.length()
                            + " localFailure=" + localVoiceFailure
                            + " v48");
            speakDroidBridgeAndroidNarratorText(fallbackText, true);
        }, Math.max(0L, delayMs));
    }

    private long beginDroidBridgeVerityVoiceAttempt(
            @NonNull String text,
            long fallbackTimeoutMs,
            @NonNull String reason
    ) {
        String normalized = text.trim();
        if (normalized.length() > 1000) normalized = normalized.substring(0, 1000);
        final String attemptText = normalized;
        final long generation;
        final long now = SystemClock.uptimeMillis();
        synchronized (droidBridgeAndroidTtsLock) {
            generation = ++droidBridgeVerityVoiceAttemptGeneration;
            droidBridgeVerityVoiceAttemptText = attemptText;
            droidBridgeVerityVoiceAttemptUptimeMs = now;
            droidBridgeVerityPendingTtsPayload = attemptText;
            droidBridgeVerityPendingTtsPayloadUptimeMs = now;
        }
        reportDroidBridgeAndroidTtsResult("Verity voice attempt queued generation=" + generation
                + " reason=" + reason + " textChars=" + attemptText.length());

        if (fallbackTimeoutMs > 0L) {
            droidBridgeAndroidTtsHandler.postDelayed(() -> {
                if (!droidBridgeAndroidTtsProxyRunning || isFinishing() || isDestroyed()) return;
                synchronized (droidBridgeAndroidTtsLock) {
                    if (generation != droidBridgeVerityVoiceAttemptGeneration
                            || droidBridgeVerityVoiceResolvedGeneration >= generation) return;
                }
                fallbackDroidBridgeVerityVoiceAttempt(attemptText,
                        "playback timeout after " + fallbackTimeoutMs + " ms");
            }, fallbackTimeoutMs);
        }
        return generation;
    }

    private void markDroidBridgeVerityVoicePlaybackStarted(@NonNull String reason) {
        final long generation;
        final String text;
        final long now = SystemClock.uptimeMillis();
        synchronized (droidBridgeAndroidTtsLock) {
            generation = droidBridgeVerityVoiceAttemptGeneration;
            text = droidBridgeVerityVoiceAttemptText;
            if (generation <= 0L || text == null || text.trim().isEmpty()) return;
            if (droidBridgeVerityVoiceResolvedGeneration >= generation) return;
            if (now - droidBridgeVerityVoiceAttemptUptimeMs > 60000L) return;
            droidBridgeVerityVoiceResolvedGeneration = generation;
            droidBridgeVerityLastSelectedVoiceText = text;
            droidBridgeVerityLastSelectedVoiceUptimeMs = now;
            droidBridgeAndroidTtsLastProxyText = text;
            droidBridgeAndroidTtsLastProxyTextUptimeMs = now;
        }
        reportDroidBridgeAndroidTtsResult("Verity voice playback confirmed generation=" + generation
                + " reason=" + reason + " textChars=" + text.length());
    }

    private boolean isDroidBridgeVerityVoiceAttemptResolved(@NonNull String text) {
        synchronized (droidBridgeAndroidTtsLock) {
            long generation = droidBridgeVerityVoiceAttemptGeneration;
            return generation > 0L
                    && droidBridgeVerityVoiceResolvedGeneration >= generation
                    && sameDroidBridgeNarratorText(droidBridgeVerityVoiceAttemptText, text);
        }
    }

    private void fallbackDroidBridgeVerityVoiceAttempt(
            @NonNull String text,
            @NonNull String reason
    ) {
        String normalized = text.trim();
        if (normalized.length() > 1000) normalized = normalized.substring(0, 1000);
        synchronized (droidBridgeAndroidTtsLock) {
            long generation = droidBridgeVerityVoiceAttemptGeneration;
            if (generation > 0L
                    && sameDroidBridgeNarratorText(droidBridgeVerityVoiceAttemptText, normalized)) {
                droidBridgeVerityVoiceResolvedGeneration = Math.max(
                        droidBridgeVerityVoiceResolvedGeneration,
                        generation
                );
            }
            droidBridgeVerityPendingTtsPayload = null;
            droidBridgeVerityPendingTtsPayloadUptimeMs = 0L;
        }
        // LOCAL/GROQ/KOKORO failures must stay failures. Automatically speaking
        // through Android TextToSpeech made unavailable named voices sound like
        // the default female narrator and allowed stale queued replies to appear
        // after switching back to LOCAL. Only an explicit NATIVE SAY/SAY_NOW
        // packet is allowed to invoke the device narrator. v72
        reportDroidBridgeAndroidTtsResult("Verity voice route failed without narrator fallback reason="
                + reason + " textChars=" + normalized.length() + " v72");
        appendDroidBridgeAndroidProxyLog(
                "DroidBridge Verity voice route failed; Android narrator fallback suppressed reason="
                        + reason + " v72"
        );
    }

    private void forceDroidBridgeVerityLocalVoiceFallback() {
        String pending = droidBridgeVerityPendingTtsPayload;
        long now = SystemClock.uptimeMillis();
        if (pending == null || pending.trim().isEmpty()) return;
        if (now - droidBridgeVerityPendingTtsPayloadUptimeMs > 12000L) return;
        fallbackDroidBridgeVerityVoiceAttempt(
                pending,
                "Verity reported selected/local voice failure"
        );
    }

    private void removeDroidBridgeVerityTtsFallbackListener() {
        Logger.eventLogListener listener = droidBridgeVerityTtsFallbackLogListener;
        droidBridgeVerityTtsFallbackLogListener = null;
        if (listener != null) {
            Logger.removeLogListener(listener);
        }
    }

    private static boolean sameDroidBridgeNarratorText(
            @Nullable String first,
            @Nullable String second
    ) {
        if (first == null || second == null) return false;
        return first.trim().replaceAll("\\s+", " ")
                .equalsIgnoreCase(second.trim().replaceAll("\\s+", " "));
    }

    private void appendDroidBridgeAndroidProxyLog(@NonNull String message) {
        // Proxy diagnostics must stay in logcat only. System.out is redirected into
        // Minecraft's latestlog.txt while the JVM is running, and directly appending
        // here caused each narrator event to be written twice. A STOP packet storm
        // could therefore grow latestlog.txt to hundreds of megabytes and kill the game.
        try {
            android.util.Log.i("DroidBridgeProxy", message);
        } catch (Throwable ignored) {
        }
    }

    private void reportDroidBridgeAndroidTtsResult(@NonNull String message) {
        // Cloud-TTS result diagnostics are intentionally sparse: one request/result/playback
        // line per Verity reply. Unlike the normal proxy diagnostics, these are written to
        // System.out so they appear in latestlog.txt and expose provider/access failures.
        try {
            System.out.println("[DroidBridge/TTS v53]: " + message);
        } catch (Throwable ignored) {
        }
    }

    private void reportDroidBridgeAndroidTtsInventoryOnce() {
        if (droidBridgeAndroidTtsInventoryReported) {
            return;
        }
        droidBridgeAndroidTtsInventoryReported = true;
        try {
            TextToSpeech tts = droidBridgeAndroidTts;
            if (tts == null || Build.VERSION.SDK_INT < 21) {
                reportDroidBridgeAndroidTtsResult("Android voice inventory unavailable tts=" + (tts != null));
                return;
            }
            Set<Voice> voices = tts.getVoices();
            Voice active = tts.getVoice();
            reportDroidBridgeAndroidTtsResult("Android TTS engine=" + getDroidBridgeAndroidDefaultTtsEngine()
                    + " activeVoice=" + (active == null ? "none" : active.getName())
                    + " voiceCount=" + (voices == null ? 0 : voices.size()));
            if (voices == null) {
                return;
            }
            int shown = 0;
            for (Voice voice : voices) {
                if (voice == null || shown >= 24) {
                    continue;
                }
                reportDroidBridgeAndroidTtsResult("Android voice[" + shown + "] name=" + voice.getName()
                        + " locale=" + voice.getLocale()
                        + " networkRequired=" + voice.isNetworkConnectionRequired()
                        + " quality=" + voice.getQuality()
                        + " latency=" + voice.getLatency());
                shown++;
            }
            if (voices.size() > shown) {
                reportDroidBridgeAndroidTtsResult("Android voice inventory truncated remaining=" + (voices.size() - shown));
            }
        } catch (Throwable throwable) {
            reportDroidBridgeAndroidTtsResult("Android voice inventory failed " + throwable.getClass().getSimpleName()
                    + ": " + String.valueOf(throwable.getMessage()));
        }
    }

    @NonNull
    private Handler ensureDroidBridgeAndroidTtsSpeakHandler() {
        Handler existing = droidBridgeAndroidTtsSpeakHandler;
        if (existing != null) {
            return existing;
        }
        synchronized (droidBridgeAndroidTtsLock) {
            existing = droidBridgeAndroidTtsSpeakHandler;
            if (existing != null) {
                return existing;
            }
            android.os.HandlerThread thread = new android.os.HandlerThread("DroidBridge-Android-TTS-Speaker");
            thread.start();
            droidBridgeAndroidTtsSpeakThread = thread;
            Handler handler = new Handler(thread.getLooper());
            droidBridgeAndroidTtsSpeakHandler = handler;
            appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator native TTS: speaker thread ready v42");
            return handler;
        }
    }

    private void initializeDroidBridgeAndroidNarratorTts() {
        initializeDroidBridgeAndroidNarratorTts(false);
    }

    private void initializeDroidBridgeAndroidNarratorTts(boolean forceRetry) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread(() -> initializeDroidBridgeAndroidNarratorTts(forceRetry));
            return;
        }
        if (!droidBridgeAndroidTtsProxyRunning) {
            return;
        }
        if (droidBridgeAndroidTtsUnavailable) {
            return;
        }
        if (!forceRetry) {
            if (droidBridgeAndroidTtsReady) {
                return;
            }
            if (droidBridgeAndroidTtsInitializing) {
                return;
            }
            if (droidBridgeAndroidTts != null && droidBridgeAndroidTtsInitialized) {
                // A previous init returned ERROR. Keep the object until the watchdog/retry path
                // tears it down, otherwise Minecraft can spin and recreate TTS every frame.
                return;
            }
        }

        if (forceRetry) {
            shutdownDroidBridgeAndroidNarratorTts(false);
        }

        droidBridgeAndroidTtsInitAttempt++;
        final int attempt = droidBridgeAndroidTtsInitAttempt;
        droidBridgeAndroidTtsInitializing = true;
        droidBridgeAndroidTtsInitialized = true;
        droidBridgeAndroidTtsReady = false;

        final Context context = GameActivity.this;
        final String defaultEngine = getDroidBridgeAndroidDefaultTtsEngine();
        final String engineToUse = chooseDroidBridgeAndroidTtsEngineForAttempt(attempt, defaultEngine);
        final String visibleEngines = describeDroidBridgeAndroidTtsEngines();
        appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator native TTS: init attempt="
                + attempt
                + " force="
                + forceRetry
                + " defaultEngine="
                + defaultEngine
                + " engineToUse="
                + engineToUse
                + " visibleEngines="
                + visibleEngines
                + " context="
                + context.getClass().getName()
                + " v42");

        if ((defaultEngine == null || defaultEngine.trim().isEmpty())
                && (visibleEngines == null || visibleEngines.trim().isEmpty() || "none".equalsIgnoreCase(visibleEngines.trim()))) {
            appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator native TTS: no visible Android TTS engine from PackageManager; still trying default TextToSpeech constructor v42");
        }

        TextToSpeech.OnInitListener listener = status -> runOnUiThread(() -> {
            if (attempt != droidBridgeAndroidTtsInitAttempt) {
                appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator native TTS: stale init callback attempt="
                        + attempt
                        + " current="
                        + droidBridgeAndroidTtsInitAttempt
                        + " status="
                        + status);
                return;
            }

            droidBridgeAndroidTtsInitializing = false;
            boolean ready = status == TextToSpeech.SUCCESS;
            if (ready) {
                try {
                    int languageStatus = droidBridgeAndroidTts == null
                            ? TextToSpeech.LANG_MISSING_DATA
                            : droidBridgeAndroidTts.setLanguage(Locale.getDefault());
                    appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator native TTS: language="
                            + Locale.getDefault()
                            + " status="
                            + languageStatus);
                    if (languageStatus == TextToSpeech.LANG_MISSING_DATA
                            || languageStatus == TextToSpeech.LANG_NOT_SUPPORTED) {
                        try {
                            int fallbackStatus = droidBridgeAndroidTts.setLanguage(Locale.US);
                            appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator native TTS: fallback language=en_US status="
                                    + fallbackStatus);
                        } catch (Throwable fallbackThrowable) {
                            appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator native TTS: fallback language failed: "
                                    + fallbackThrowable);
                        }
                    }
                } catch (Throwable languageThrowable) {
                    appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator native TTS: language setup failed: "
                            + languageThrowable);
                }
            }

            if (ready && droidBridgeAndroidTts != null) {
                try {
                    if (Build.VERSION.SDK_INT >= 21) {
                        droidBridgeAndroidTts.setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build());
                    }
                    droidBridgeAndroidTts.setSpeechRate(1.0f);
                    droidBridgeAndroidTts.setPitch(1.0f);
                } catch (Throwable audioThrowable) {
                    appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator native TTS: audio setup failed: "
                            + audioThrowable);
                }
            }

            droidBridgeAndroidTtsReady = ready;
            appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator native TTS: init status="
                    + status
                    + " ready="
                    + droidBridgeAndroidTtsReady
                    + " attempt="
                    + attempt
                    + " engine="
                    + engineToUse
                    + " v42");
            if (ready) {
                reportDroidBridgeAndroidTtsInventoryOnce();
                flushDroidBridgeAndroidNarratorQueue();
            } else if (attempt < getDroidBridgeAndroidTtsMaxInitAttempts()) {
                droidBridgeAndroidTtsHandler.postDelayed(() -> initializeDroidBridgeAndroidNarratorTts(true),
                        1000L * attempt);
            } else {
                droidBridgeAndroidTtsUnavailable = true;
                appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator native TTS: unavailable after retries. "
                        + "Install/enable an Android TTS engine if visibleEngines was empty. v42");
            }
        });

        try {
            if (engineToUse != null && !engineToUse.trim().isEmpty()) {
                droidBridgeAndroidTts = new TextToSpeech(context, listener, engineToUse);
            } else {
                droidBridgeAndroidTts = new TextToSpeech(context, listener);
            }
        } catch (Throwable throwable) {
            droidBridgeAndroidTtsInitializing = false;
            droidBridgeAndroidTtsInitialized = false;
            droidBridgeAndroidTts = null;
            droidBridgeAndroidTtsReady = false;
            appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator native TTS: constructor failed: " + throwable);
            if (attempt < getDroidBridgeAndroidTtsMaxInitAttempts()) {
                droidBridgeAndroidTtsHandler.postDelayed(() -> initializeDroidBridgeAndroidNarratorTts(true),
                        1000L * attempt);
            } else {
                droidBridgeAndroidTtsUnavailable = true;
            }
            return;
        }

        droidBridgeAndroidTtsHandler.postDelayed(() -> {
            if (attempt == droidBridgeAndroidTtsInitAttempt
                    && droidBridgeAndroidTtsInitializing
                    && !droidBridgeAndroidTtsReady) {
                appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator native TTS: init callback timeout attempt="
                        + attempt
                        + " engine="
                        + engineToUse
                        + " visibleEngines="
                        + visibleEngines
                        + " v42");
                if (attempt < getDroidBridgeAndroidTtsMaxInitAttempts()) {
                    initializeDroidBridgeAndroidNarratorTts(true);
                } else {
                    droidBridgeAndroidTtsInitializing = false;
                    droidBridgeAndroidTtsUnavailable = true;
                    appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator native TTS: giving up after callback timeouts. "
                            + "This usually means no visible Android TTS engine or missing manifest TTS queries. v42");
                }
            }
        }, 4500L);
    }


    private int getDroidBridgeAndroidTtsMaxInitAttempts() {
        return 2 + DROIDBRIDGE_ANDROID_TTS_ENGINE_CANDIDATES.length;
    }

    @Nullable
    private String chooseDroidBridgeAndroidTtsEngineForAttempt(int attempt, @Nullable String defaultEngine) {
        if (attempt <= 1 && defaultEngine != null && !defaultEngine.trim().isEmpty()) {
            return defaultEngine.trim();
        }
        int index = attempt - 1;
        if (defaultEngine != null && !defaultEngine.trim().isEmpty()) {
            index = attempt - 2;
        }
        if (index >= 0 && index < DROIDBRIDGE_ANDROID_TTS_ENGINE_CANDIDATES.length) {
            return DROIDBRIDGE_ANDROID_TTS_ENGINE_CANDIDATES[index];
        }
        return null;
    }

    @Nullable
    private String getDroidBridgeAndroidDefaultTtsEngine() {
        try {
            String value = Settings.Secure.getString(getContentResolver(), Settings.Secure.TTS_DEFAULT_SYNTH);
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        } catch (Throwable throwable) {
            appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator native TTS: default engine lookup failed: " + throwable);
        }
        return null;
    }

    @NonNull
    private String describeDroidBridgeAndroidTtsEngines() {
        try {
            Intent intent = new Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE);
            List<ResolveInfo> services = getPackageManager().queryIntentServices(intent, 0);
            if (services == null || services.isEmpty()) {
                return "none";
            }
            StringBuilder builder = new StringBuilder();
            for (ResolveInfo info : services) {
                if (info == null || info.serviceInfo == null) {
                    continue;
                }
                if (builder.length() > 0) {
                    builder.append(',');
                }
                builder.append(info.serviceInfo.packageName);
                if (info.serviceInfo.name != null) {
                    builder.append('/').append(info.serviceInfo.name);
                }
            }
            return builder.length() == 0 ? "none" : builder.toString();
        } catch (Throwable throwable) {
            return "error:" + throwable.getClass().getSimpleName() + ':' + throwable.getMessage();
        }
    }

    private void shutdownDroidBridgeAndroidNarratorTts(boolean clearQueue) {
        TextToSpeech tts = droidBridgeAndroidTts;
        droidBridgeAndroidTts = null;
        droidBridgeAndroidTtsReady = false;
        droidBridgeAndroidTtsInitialized = false;
        droidBridgeAndroidTtsInitializing = false;
        if (clearQueue) {
            synchronized (droidBridgeAndroidTtsLock) {
                droidBridgeAndroidTtsPendingSpeech.clear();
            }
        }
        if (tts != null) {
            try {
                tts.stop();
            } catch (Throwable ignored) {
            }
            try {
                tts.shutdown();
            } catch (Throwable ignored) {
            }
        }
    }

    private void runDroidBridgeAndroidNarratorProxy() {
        try {
            DatagramSocket socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),
                    DROIDBRIDGE_ANDROID_TTS_PROXY_PORT));
            socket.setSoTimeout(500);
            droidBridgeAndroidTtsSocket = socket;
            appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator UDP proxy: listening on 127.0.0.1:"
                    + DROIDBRIDGE_ANDROID_TTS_PROXY_PORT);

            byte[] buffer = new byte[16384];
            while (droidBridgeAndroidTtsProxyRunning) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException ignored) {
                    continue;
                }

                String payload = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                handleDroidBridgeAndroidNarratorPacket(payload);
            }
        } catch (Throwable throwable) {
            if (droidBridgeAndroidTtsProxyRunning) {
                appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator UDP proxy failed: " + throwable);
            }
        } finally {
            try {
                DatagramSocket socket = droidBridgeAndroidTtsSocket;
                if (socket != null) {
                    socket.close();
                }
            } catch (Throwable ignored) {
            }
            droidBridgeAndroidTtsSocket = null;
        }
    }

    private void runDroidBridgeAndroidNarratorTcpProxy() {
        ServerSocket serverSocket = null;
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),
                    DROIDBRIDGE_ANDROID_TTS_PROXY_PORT));
            serverSocket.setSoTimeout(500);
            droidBridgeAndroidTtsServerSocket = serverSocket;
            appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator TCP proxy: listening on 127.0.0.1:"
                    + DROIDBRIDGE_ANDROID_TTS_PROXY_PORT + " v42");

            while (droidBridgeAndroidTtsProxyRunning) {
                try {
                    Socket client = serverSocket.accept();
                    Thread worker = new Thread(() -> handleDroidBridgeAndroidNarratorTcpClient(client),
                            "DroidBridge-Android-TTS-TCP-Client");
                    worker.setDaemon(true);
                    worker.start();
                } catch (SocketTimeoutException ignored) {
                    // keep polling until stop
                } catch (Throwable clientThrowable) {
                    if (droidBridgeAndroidTtsProxyRunning) {
                        appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator TCP proxy accept failed: "
                                + clientThrowable + " v42");
                    }
                }
            }
        } catch (Throwable throwable) {
            if (droidBridgeAndroidTtsProxyRunning) {
                appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator TCP proxy failed: " + throwable + " v42");
            }
        } finally {
            try {
                if (serverSocket != null) {
                    serverSocket.close();
                }
            } catch (Throwable ignored) {
            }
            droidBridgeAndroidTtsServerSocket = null;
        }
    }

    private void handleDroidBridgeAndroidNarratorTcpClient(@NonNull Socket client) {
        try {
            client.setSoTimeout(500);
            ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
            byte[] buffer = new byte[8192];
            java.io.InputStream input = client.getInputStream();
            long deadlineMs = System.currentTimeMillis() + 15000L;
            boolean localVoicePacket = false;
            while (droidBridgeAndroidTtsProxyRunning && out.size() < (32 * 1024 * 1024)) {
                try {
                    int read = input.read(buffer);
                    if (read == -1) {
                        break;
                    }
                    if (read > 0) {
                        out.write(buffer, 0, read);
                        if (!localVoicePacket && out.size() >= 12) {
                            byte[] prefixBytes = out.toByteArray();
                            String prefix = new String(prefixBytes, 0,
                                    Math.min(prefixBytes.length, 24), StandardCharsets.UTF_8);
                            localVoicePacket = prefix.startsWith("LOCAL_PCM\n")
                                    || prefix.startsWith("LOCAL_CLAIM\n");
                        }
                    }
                } catch (SocketTimeoutException readTimeout) {
                    if (!localVoicePacket && out.size() > 0) {
                        break;
                    }
                    if (System.currentTimeMillis() >= deadlineMs) {
                        break;
                    }
                }
            }

            byte[] payloadBytes = out.toByteArray();
            if (payloadBytes.length == 0) {
                appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator TCP proxy: empty payload after accept v64");
                return;
            }

            String payload = new String(payloadBytes, StandardCharsets.UTF_8);
            boolean responseRequired = false;
            boolean success = true;
            if (payload.startsWith("LOCAL_CLAIM\n")) {
                responseRequired = true;
                success = handleDroidBridgeVerityLocalVoiceClaim(
                        payload.substring("LOCAL_CLAIM\n".length()).trim());
            } else if (payload.startsWith("LOCAL_PCM\n")) {
                responseRequired = true;
                success = handleDroidBridgeVerityLocalPcmPacket(payload);
            } else {
                appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator TCP proxy: received payload bytes="
                        + out.size() + " prefix="
                        + payload.substring(0, Math.min(16, payload.length())).replace('\n', '/')
                        + " v64");
                handleDroidBridgeAndroidNarratorPacket(payload);
            }

            if (responseRequired) {
                java.io.OutputStream response = client.getOutputStream();
                response.write((success ? "OK\n" : "ERROR\n").getBytes(StandardCharsets.UTF_8));
                response.flush();
            }
        } catch (Throwable clientThrowable) {
            if (droidBridgeAndroidTtsProxyRunning) {
                appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator TCP proxy client failed: "
                        + clientThrowable + " v64");
            }
        } finally {
            try { client.close(); } catch (Throwable ignoredClose) {}
        }
    }

    private boolean handleDroidBridgeVerityLocalVoiceClaim(@NonNull String encodedText) {
        try {
            String text = new String(java.util.Base64.getDecoder().decode(encodedText),
                    StandardCharsets.UTF_8).trim();
            if (text.isEmpty()) return false;
            if (text.length() > 1000) text = text.substring(0, 1000);

            // A claim only means the selected voice accepted the request. Do not mark
            // it as spoken until AudioTrack/MediaPlayer actually starts.
            beginDroidBridgeVerityVoiceAttempt(text, 8000L, "local Piper claim");
            cancelDroidBridgeAndroidNarratorAudio("Verity local Piper voice attempt len=" + text.length());
            reportDroidBridgeAndroidTtsResult("Local Piper voice attempt textChars=" + text.length() + " v65");
            return true;
        } catch (Throwable throwable) {
            reportDroidBridgeAndroidTtsResult("Local Piper claim failed type="
                    + throwable.getClass().getSimpleName() + " message=" + String.valueOf(throwable.getMessage()));
            return false;
        }
    }

    private boolean handleDroidBridgeVerityLocalPcmPacket(@NonNull String payload) {
        try {
            int cursor = "LOCAL_PCM\n".length();
            int rateEnd = payload.indexOf('\n', cursor);
            int channelsEnd = rateEnd < 0 ? -1 : payload.indexOf('\n', rateEnd + 1);
            int bitsEnd = channelsEnd < 0 ? -1 : payload.indexOf('\n', channelsEnd + 1);
            int endianEnd = bitsEnd < 0 ? -1 : payload.indexOf('\n', bitsEnd + 1);
            if (rateEnd < 0 || channelsEnd < 0 || bitsEnd < 0 || endianEnd < 0) {
                reportDroidBridgeAndroidTtsResult("Local Piper PCM packet malformed header v65");
                String pending = droidBridgeVerityPendingTtsPayload;
                if (pending != null) fallbackDroidBridgeVerityVoiceAttempt(pending, "malformed local PCM packet");
                return false;
            }

            int sampleRate = Integer.parseInt(payload.substring(cursor, rateEnd).trim());
            int channels = Integer.parseInt(payload.substring(rateEnd + 1, channelsEnd).trim());
            int bits = Integer.parseInt(payload.substring(channelsEnd + 1, bitsEnd).trim());
            boolean bigEndian = "1".equals(payload.substring(bitsEnd + 1, endianEnd).trim());
            byte[] pcm = java.util.Base64.getDecoder().decode(payload.substring(endianEnd + 1).trim());
            String pendingBeforePlayback = droidBridgeVerityPendingTtsPayload;
            if (pendingBeforePlayback != null
                    && isDroidBridgeVerityVoiceAttemptResolved(pendingBeforePlayback)) {
                reportDroidBridgeAndroidTtsResult(
                        "Ignored late local Piper PCM after fallback already owned the sentence v65");
                return true;
            }
            boolean played = playDroidBridgeVerityLocalPcm(pcm, sampleRate, channels, bits, bigEndian);
            if (!played) {
                String pending = droidBridgeVerityPendingTtsPayload;
                if (pending != null) fallbackDroidBridgeVerityVoiceAttempt(pending, "local PCM playback failed");
            }
            return played;
        } catch (Throwable throwable) {
            reportDroidBridgeAndroidTtsResult("Local Piper PCM packet failed type="
                    + throwable.getClass().getSimpleName() + " message=" + String.valueOf(throwable.getMessage()));
            String pending = droidBridgeVerityPendingTtsPayload;
            if (pending != null) fallbackDroidBridgeVerityVoiceAttempt(pending, "local PCM packet exception");
            return false;
        }
    }

    private boolean playDroidBridgeVerityLocalPcm(
            @NonNull byte[] sourcePcm,
            int sampleRate,
            int channels,
            int bits,
            boolean bigEndian
    ) {
        if (sourcePcm.length == 0 || sampleRate < 8000 || sampleRate > 192000
                || (channels != 1 && channels != 2) || (bits != 8 && bits != 16)) {
            reportDroidBridgeAndroidTtsResult("Local Piper PCM rejected bytes=" + sourcePcm.length
                    + " rate=" + sampleRate + " channels=" + channels + " bits=" + bits + " v64");
            return false;
        }

        byte[] pcm = sourcePcm;
        if (bigEndian && bits == 16) {
            pcm = sourcePcm.clone();
            for (int i = 0; i + 1 < pcm.length; i += 2) {
                byte first = pcm[i];
                pcm[i] = pcm[i + 1];
                pcm[i + 1] = first;
            }
        }

        cancelDroidBridgeAndroidNarratorAudio("Verity local Piper PCM playback");
        final long generation = droidBridgeAndroidTtsPlaybackGeneration;
        requestDroidBridgeAndroidTtsAudioFocus();

        int channelConfig = channels == 1
                ? android.media.AudioFormat.CHANNEL_OUT_MONO
                : android.media.AudioFormat.CHANNEL_OUT_STEREO;
        int encoding = bits == 16
                ? android.media.AudioFormat.ENCODING_PCM_16BIT
                : android.media.AudioFormat.ENCODING_PCM_8BIT;
        int minBuffer = android.media.AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding);
        if (minBuffer <= 0) {
            minBuffer = 16384;
        }
        int bufferSize = Math.max(minBuffer, 32768);
        android.media.AudioTrack track = null;
        try {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            android.media.AudioFormat format = new android.media.AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .setEncoding(encoding)
                    .build();
            track = new android.media.AudioTrack(
                    attributes,
                    format,
                    bufferSize,
                    android.media.AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
            );
            if (track.getState() != android.media.AudioTrack.STATE_INITIALIZED) {
                reportDroidBridgeAndroidTtsResult("Local Piper AudioTrack failed to initialize v64");
                return false;
            }
            droidBridgeAndroidTtsAudioTrack = track;
            reportDroidBridgeAndroidTtsResult("Local Piper PCM playback started bytes=" + pcm.length
                    + " rate=" + sampleRate + " channels=" + channels + " bits=" + bits + " v64");
            track.play();
            markDroidBridgeVerityVoicePlaybackStarted("local Piper AudioTrack");
            int offset = 0;
            while (offset < pcm.length && generation == droidBridgeAndroidTtsPlaybackGeneration) {
                int remaining = pcm.length - offset;
                int toWrite = Math.min(remaining, 65536);
                int written;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    written = track.write(pcm, offset, toWrite, android.media.AudioTrack.WRITE_BLOCKING);
                } else {
                    written = track.write(pcm, offset, toWrite);
                }
                if (written <= 0) {
                    reportDroidBridgeAndroidTtsResult("Local Piper AudioTrack write failed code=" + written
                            + " offset=" + offset + " v64");
                    return false;
                }
                offset += written;
            }
            boolean queuedAllAudio = offset >= pcm.length;
            boolean superseded = generation != droidBridgeAndroidTtsPlaybackGeneration;
            if (queuedAllAudio && !superseded) {
                int bytesPerSample = Math.max(1, bits / 8);
                int frameSize = Math.max(1, channels * bytesPerSample);
                int frameCount = pcm.length / frameSize;
                long durationMs = Math.max(150L,
                        (long) frameCount * 1000L / Math.max(1, sampleRate));
                long deadline = android.os.SystemClock.uptimeMillis()
                        + Math.min(durationMs + 3000L, 115000L);
                int completionToleranceFrames = Math.max(1, sampleRate / 50);

                while (generation == droidBridgeAndroidTtsPlaybackGeneration
                        && android.os.SystemClock.uptimeMillis() < deadline) {
                    int playedFrames;
                    try {
                        playedFrames = track.getPlaybackHeadPosition();
                    } catch (Throwable playbackHeadError) {
                        reportDroidBridgeAndroidTtsResult(
                                "Local Piper playback-head query failed type="
                                        + playbackHeadError.getClass().getSimpleName() + " v70");
                        break;
                    }
                    if (playedFrames + completionToleranceFrames >= frameCount) {
                        break;
                    }
                    try {
                        Thread.sleep(20L);
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                superseded = generation != droidBridgeAndroidTtsPlaybackGeneration;
                if (!superseded) {
                    int playedFrames = 0;
                    try { playedFrames = track.getPlaybackHeadPosition(); } catch (Throwable ignored) {}
                    boolean drained = playedFrames + completionToleranceFrames >= frameCount;
                    if (!drained) {
                        reportDroidBridgeAndroidTtsResult(
                                "Local Piper PCM drain timeout playedFrames=" + playedFrames
                                        + " frameCount=" + frameCount + " bytes=" + pcm.length + " v70");
                        return false;
                    }
                }
            }

            if (superseded) {
                // A newer voice request or activity shutdown intentionally replaced this track.
                // Treat the old request as handled so it does not trigger a random narrator fallback.
                reportDroidBridgeAndroidTtsResult(
                        "Local Piper PCM superseded by newer playback generation bytes="
                                + pcm.length + " v70");
                return true;
            }

            boolean completed = queuedAllAudio;
            if (completed) {
                try { track.stop(); } catch (Throwable ignored) {}
                reportDroidBridgeAndroidTtsResult(
                        "Local Piper PCM playback fully drained bytes=" + pcm.length + " v70");
            }
            return completed;
        } catch (Throwable throwable) {
            reportDroidBridgeAndroidTtsResult("Local Piper AudioTrack playback failed type="
                    + throwable.getClass().getSimpleName() + " message=" + String.valueOf(throwable.getMessage()));
            return false;
        } finally {
            if (droidBridgeAndroidTtsAudioTrack == track) {
                droidBridgeAndroidTtsAudioTrack = null;
            }
            if (track != null) {
                try { track.stop(); } catch (Throwable ignored) {}
                try { track.release(); } catch (Throwable ignored) {}
            }
        }
    }

    private void handleDroidBridgeAndroidNarratorPacket(@Nullable String payload) {
        if (payload == null || payload.isEmpty()) {
            return;
        }

        if (payload.startsWith("GROQ_TTS\n")) {
            handleDroidBridgeAndroidGroqTtsPacket(payload.substring("GROQ_TTS\n".length()));
            return;
        }

        if (payload.startsWith("PLAY_WAV_PATH\n")) {
            String path = payload.substring("PLAY_WAV_PATH\n".length()).trim();
            appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator media proxy: received WAV path len="
                    + path.length() + " path=" + path + " v42");
            if (!path.isEmpty()) {
                runOnUiThread(() -> playDroidBridgeAndroidNarratorWavPath(path));
            }
            return;
        }

        boolean interrupt = false;
        String text = payload;
        if (payload.startsWith("SAY_NOW\n")) {
            interrupt = true;
            text = payload.substring("SAY_NOW\n".length());
        } else if (payload.startsWith("SAY\n")) {
            text = payload.substring("SAY\n".length());
        } else if (payload.startsWith("STOP")) {
            // STOP is always authoritative in the stable provider router. Never
            // preserve narrator speech after a LOCAL/provider failure. v72
            long now = System.currentTimeMillis();
            if (now - droidBridgeAndroidTtsLastStopPacketMs < 1500L) {
                return;
            }
            droidBridgeAndroidTtsLastStopPacketMs = now;
            cancelDroidBridgeAndroidNarratorAudio("STOP packet");
            return;
        }

        String finalText = text == null ? "" : text.trim();
        if (finalText.isEmpty()) {
            return;
        }
        if (shouldSkipDroidBridgeAndroidNarratorText(finalText)) {
            return;
        }
        if (finalText.length() > 1000) {
            finalText = finalText.substring(0, 1000);
        }

        // A selected Verity voice already owns this exact sentence. Verity 5.7.3
        // can also call Minecraft's narrator after its own voice finishes, which
        // previously made Android TTS repeat the sentence a second time.
        long selectedVoiceNow = SystemClock.uptimeMillis();
        if (sameDroidBridgeNarratorText(droidBridgeVerityLastSelectedVoiceText, finalText)
                && selectedVoiceNow - droidBridgeVerityLastSelectedVoiceUptimeMs < 60000L) {
            appendDroidBridgeAndroidProxyLog(
                    "DroidBridge Verity selected voice: skipped duplicate Android narrator text len="
                            + finalText.length() + " v58");
            return;
        }

        String attemptText = droidBridgeVerityVoiceAttemptText;
        long attemptGeneration = droidBridgeVerityVoiceAttemptGeneration;
        if (attemptGeneration > droidBridgeVerityVoiceResolvedGeneration
                && sameDroidBridgeNarratorText(attemptText, finalText)
                && selectedVoiceNow - droidBridgeVerityVoiceAttemptUptimeMs < 60000L) {
            appendDroidBridgeAndroidProxyLog(
                    "DroidBridge Verity selected voice: deferred Android narrator until playback/fallback len="
                            + finalText.length() + " generation=" + attemptGeneration + " v65");
            return;
        }

        long now = System.currentTimeMillis();
        if (now - droidBridgeAndroidTtsLastVerboseLogMs > 2500L) {
            droidBridgeAndroidTtsLastVerboseLogMs = now;
            appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator UDP proxy: received speech len="
                    + finalText.length()
                    + " interrupt="
                    + interrupt
                    + " ready="
                    + droidBridgeAndroidTtsReady
                    + " v42");
        }

        droidBridgeAndroidTtsLastProxyText = finalText;
        droidBridgeAndroidTtsLastProxyTextUptimeMs = SystemClock.uptimeMillis();

        boolean finalInterrupt = interrupt;
        String speakText = finalText;
        runOnUiThread(() -> speakDroidBridgeAndroidNarratorText(speakText, finalInterrupt));
    }


    private void handleDroidBridgeAndroidGroqTtsPacket(@NonNull String body) {
        int keyNewline = body.indexOf('\n');
        if (keyNewline < 0) {
            appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator cloud TTS proxy: invalid GROQ_TTS packet v45");
            return;
        }

        String apiKey = body.substring(0, keyNewline).trim();
        String remainder = body.substring(keyNewline + 1);
        String voice = droidBridgeAndroidGroqTtsVoice;
        String text = remainder;

        // v45 packets are key + voice + text. Retain compatibility with older
        // key + text packets produced by v40-v44.
        int voiceNewline = remainder.indexOf('\n');
        if (voiceNewline > 0) {
            String possibleVoice = remainder.substring(0, voiceNewline).trim();
            String normalizedVoice = normalizeDroidBridgeAndroidGroqVoice(possibleVoice);
            if (isDroidBridgeAndroidGroqVoice(possibleVoice)) {
                voice = normalizedVoice;
                text = remainder.substring(voiceNewline + 1);
            }
        }

        text = sanitizeDroidBridgeAndroidVeritySpeechText(text);
        if (text.isEmpty()) {
            appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator cloud TTS proxy: empty text v65");
            return;
        }
        if (text.length() > 1000) {
            text = text.substring(0, 1000);
        }

        beginDroidBridgeVerityVoiceAttempt(text, 35000L, "Orpheus request");
        if (apiKey.isEmpty()) {
            appendDroidBridgeAndroidProxyLog(
                    "DroidBridge Android narrator cloud TTS proxy: no API key; using Android narrator fallback v65");
            fallbackDroidBridgeVerityVoiceAttempt(text, "Orpheus API key missing");
            return;
        }

        voice = normalizeDroidBridgeAndroidGroqVoice(voice);
        droidBridgeAndroidGroqTtsApiKey = apiKey;
        droidBridgeAndroidGroqTtsVoice = voice;
        cancelDroidBridgeAndroidNarratorAudio("cloud TTS len=" + text.length());
        String finalText = text;
        String finalVoice = voice;
        Handler speaker = ensureDroidBridgeAndroidTtsSpeakHandler();
        appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator cloud TTS proxy: received cloud text len="
                + finalText.length() + " voice=" + finalVoice + " v45");
        reportDroidBridgeAndroidTtsResult("Orpheus request queued model=canopylabs/orpheus-v1-english voice="
                + finalVoice + " chars=" + finalText.length() + " keyPresent=" + (!apiKey.isEmpty()));
        speaker.post(() -> synthesizeDroidBridgeAndroidGroqTtsToFile(apiKey, finalText, finalVoice, "verity"));
    }

    private boolean isDroidBridgeAndroidGroqVoice(@Nullable String value) {
        if (value == null) {
            return false;
        }
        String voice = value.trim().toLowerCase(Locale.ROOT);
        return "autumn".equals(voice)
                || "diana".equals(voice)
                || "hannah".equals(voice)
                || "austin".equals(voice)
                || "daniel".equals(voice)
                || "troy".equals(voice);
    }

    @NonNull
    private String normalizeDroidBridgeAndroidGroqVoice(@Nullable String value) {
        if (isDroidBridgeAndroidGroqVoice(value)) {
            return value.trim().toLowerCase(Locale.ROOT);
        }
        return "daniel";
    }

    @NonNull
    private String sanitizeDroidBridgeAndroidVeritySpeechText(@Nullable String value) {
        if (value == null) {
            return "";
        }
        String text = value.trim();
        if (text.isEmpty()) {
            return "";
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.startsWith("api error:")
                || lower.contains("rate limit reached for model")
                || lower.contains("tokens per day")) {
            return "Verity could not respond because the AI service rate limit was reached.";
        }
        if (text.length() > 2048
                || text.contains("VerityBoundary")
                || text.contains("Content-Disposition: form-data")
                || text.startsWith("RIFF")
                || text.indexOf('\u0000') >= 0) {
            return "";
        }
        text = text.replace('`', ' ').replace('*', ' ');
        text = text.replaceAll("https?://\\S+", "link");
        text = text.replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("  +", " ")
                .trim();
        return text;
    }

    private void synthesizeDroidBridgeAndroidGroqTtsToFile(@NonNull String apiKey,
                                                           @NonNull String text,
                                                           @NonNull String reason) {
        synthesizeDroidBridgeAndroidGroqTtsToFile(apiKey, text, droidBridgeAndroidGroqTtsVoice, reason);
    }

    private void synthesizeDroidBridgeAndroidGroqTtsToFile(@NonNull String apiKey,
                                                           @NonNull String text,
                                                           @NonNull String voice,
                                                           @NonNull String reason) {
        if (apiKey.trim().isEmpty() || text.trim().isEmpty()) {
            return;
        }
        String speakText = sanitizeDroidBridgeAndroidVeritySpeechText(text);
        if (speakText.isEmpty()) {
            return;
        }
        if (speakText.length() > 190) {
            speakText = speakText.substring(0, 190);
        }
        String selectedVoice = normalizeDroidBridgeAndroidGroqVoice(voice);
        HttpURLConnection connection = null;
        File outFile = null;
        try {
            requestDroidBridgeAndroidTtsAudioFocus();
            // Orpheus accepts only model, input, voice and response_format.
            // The old speed field caused Groq to reject the request, which made
            // Android's fallback narrator speak instead of Verity's selected voice.
            String json = "{\"model\":\"canopylabs/orpheus-v1-english\",\"input\":\""
                    + droidBridgeAndroidJsonEscape(speakText)
                    + "\",\"voice\":\"" + selectedVoice
                    + "\",\"response_format\":\"wav\"}";
            byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
            URL url = new URL("https://api.groq.com/openai/v1/audio/speech");
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(30000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "audio/wav, application/octet-stream, */*");
            connection.setFixedLengthStreamingMode(jsonBytes.length);
            try (java.io.OutputStream output = connection.getOutputStream()) {
                output.write(jsonBytes);
                output.flush();
            }
            int status = connection.getResponseCode();
            String responseContentType = connection.getContentType();
            String requestId = connection.getHeaderField("x-request-id");
            String retryAfter = connection.getHeaderField("retry-after");
            String remainingRequests = connection.getHeaderField("x-ratelimit-remaining-requests");
            String remainingTokens = connection.getHeaderField("x-ratelimit-remaining-tokens");
            byte[] responseBody = readDroidBridgeHttpBody(connection, status >= 400);
            if (status != 200) {
                String error = responseBody == null ? "" : new String(responseBody, StandardCharsets.UTF_8);
                if (error.length() > 500) {
                    error = error.substring(0, 500);
                }
                appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator cloud TTS proxy: Groq failed status="
                        + status + " body=" + error + " reason=" + reason + " voice=" + selectedVoice + " payload=v47");
                String compactError = error.replace('\r', ' ').replace('\n', ' ').replaceAll("  +", " ").trim();
                if (compactError.length() > 360) compactError = compactError.substring(0, 360);
                reportDroidBridgeAndroidTtsResult("Orpheus HTTP failure status=" + status
                        + " contentType=" + responseContentType
                        + " voice=" + selectedVoice
                        + " requestId=" + requestId
                        + " retryAfter=" + retryAfter
                        + " remainingRequests=" + remainingRequests
                        + " remainingTokens=" + remainingTokens
                        + " body=" + compactError);
                fallbackDroidBridgeVerityVoiceAttempt(text, "Orpheus HTTP " + status);
                return;
            }
            if (responseBody == null || responseBody.length == 0) {
                appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator cloud TTS proxy: empty Groq audio reason="
                        + reason + " voice=" + selectedVoice + " payload=v47");
                reportDroidBridgeAndroidTtsResult("Orpheus HTTP 200 but audio body empty contentType="
                        + responseContentType + " voice=" + selectedVoice + " requestId=" + requestId);
                fallbackDroidBridgeVerityVoiceAttempt(text, "Orpheus returned empty audio");
                return;
            }
            if ("verity".equals(reason) && isDroidBridgeVerityVoiceAttemptResolved(text)) {
                reportDroidBridgeAndroidTtsResult(
                        "Discarded late Orpheus audio because Android narrator fallback already started");
                return;
            }
            outFile = File.createTempFile("droidbridge-android-groq-tts-", ".wav", getCacheDir());
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(outFile)) {
                out.write(responseBody);
                out.flush();
            }
            appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator cloud TTS proxy: Groq WAV ready bytes="
                    + responseBody.length + " file=" + outFile.getAbsolutePath() + " reason=" + reason
                    + " voice=" + selectedVoice + " payload=v47");
            String header = responseBody.length >= 4
                    ? new String(responseBody, 0, 4, StandardCharsets.US_ASCII).replaceAll("[^A-Za-z0-9]", "?")
                    : "short";
            reportDroidBridgeAndroidTtsResult("Orpheus HTTP 200 audioBytes=" + responseBody.length
                    + " contentType=" + responseContentType + " header=" + header
                    + " voice=" + selectedVoice + " requestId=" + requestId);
            playDroidBridgeAndroidNarratorSynthesizedFile(outFile, outFile.getAbsolutePath());
            outFile = null;
        } catch (Throwable throwable) {
            appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator cloud TTS proxy: failed "
                    + throwable + " reason=" + reason + " voice=" + selectedVoice + " payload=v47");
            reportDroidBridgeAndroidTtsResult("Orpheus request exception voice=" + selectedVoice + " type="
                    + throwable.getClass().getName() + " message=" + String.valueOf(throwable.getMessage()));
            fallbackDroidBridgeVerityVoiceAttempt(text, "Orpheus request exception");
        } finally {
            if (connection != null) {
                try { connection.disconnect(); } catch (Throwable ignored) {}
            }
            if (outFile != null) {
                try { outFile.delete(); } catch (Throwable ignored) {}
            }
        }
    }

    @Nullable
    private byte[] readDroidBridgeHttpBody(@NonNull HttpURLConnection connection, boolean errorStream) {
        java.io.InputStream input = null;
        try {
            input = errorStream ? connection.getErrorStream() : connection.getInputStream();
            if (input == null) {
                return new byte[0];
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            byte[] buffer = new byte[16384];
            int read;
            while ((read = input.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() > 8 * 1024 * 1024) {
                    break;
                }
            }
            return out.toByteArray();
        } catch (Throwable throwable) {
            appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator cloud TTS proxy: read HTTP body failed "
                    + throwable + " v42");
            return null;
        } finally {
            if (input != null) {
                try { input.close(); } catch (Throwable ignored) {}
            }
        }
    }

    @NonNull
    private String droidBridgeAndroidJsonEscape(@NonNull String value) {
        StringBuilder builder = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\': builder.append("\\\\"); break;
                case '"': builder.append("\\\""); break;
                case '\n': builder.append("\\n"); break;
                case '\r': builder.append("\\r"); break;
                case '\t': builder.append("\\t"); break;
                default:
                    if (c < 32) {
                        builder.append(String.format(Locale.US, "\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                    break;
            }
        }
        return builder.toString();
    }

    private void playDroidBridgeAndroidNarratorWavPath(@NonNull String path) {
        File audioFile = new File(path);
        if (!audioFile.exists() || !audioFile.isFile()) {
            appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator media proxy: WAV path missing " + path + " v42");
            return;
        }
        Handler speaker = ensureDroidBridgeAndroidTtsSpeakHandler();
        speaker.post(() -> playDroidBridgeAndroidNarratorWavPathOnSpeaker(audioFile, path));
    }

    private void playDroidBridgeAndroidNarratorWavPathOnSpeaker(@NonNull File audioFile, @NonNull String path) {
        if (!audioFile.exists() || !audioFile.isFile()) {
            appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator media proxy: WAV path disappeared " + path + " v42");
            return;
        }

        try {
            MediaPlayer oldPlayer = droidBridgeAndroidTtsMediaPlayer;
            droidBridgeAndroidTtsMediaPlayer = null;
            if (oldPlayer != null) {
                try { oldPlayer.stop(); } catch (Throwable ignored) {}
                try { oldPlayer.release(); } catch (Throwable ignored) {}
            }
            android.media.AudioTrack oldTrack = droidBridgeAndroidTtsAudioTrack;
            droidBridgeAndroidTtsAudioTrack = null;
            if (oldTrack != null) {
                try { oldTrack.stop(); } catch (Throwable ignored) {}
                try { oldTrack.release(); } catch (Throwable ignored) {}
            }

            DroidBridgeWavData wavData = readDroidBridgePcmWav(audioFile);
            if (wavData != null && playDroidBridgeAndroidNarratorWavWithAudioTrack(wavData, path)) {
                try { audioFile.delete(); } catch (Throwable ignored) {}
                return;
            }

            appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator media proxy: AudioTrack could not play WAV; falling back to MediaPlayer path=" + path + " v42");
            playDroidBridgeAndroidNarratorWavPathWithMediaPlayer(audioFile, path);
        } catch (Throwable throwable) {
            appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator media proxy: play WAV failed before fallback: " + throwable + " v42");
            try {
                playDroidBridgeAndroidNarratorWavPathWithMediaPlayer(audioFile, path);
            } catch (Throwable fallbackThrowable) {
                appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator media proxy: fallback MediaPlayer also failed: " + fallbackThrowable + " v42");
                try { audioFile.delete(); } catch (Throwable ignored) {}
            }
        }
    }

    private boolean playDroidBridgeAndroidNarratorWavWithAudioTrack(@NonNull DroidBridgeWavData wavData, @NonNull String path) {
        if (wavData.pcm == null || wavData.pcm.length == 0) {
            return false;
        }
        if (wavData.channels != 1 && wavData.channels != 2) {
            appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator media proxy: unsupported WAV channels=" + wavData.channels + " path=" + path + " v42");
            return false;
        }

        final int channelConfig = wavData.channels == 1
                ? AudioFormat.CHANNEL_OUT_MONO
                : AudioFormat.CHANNEL_OUT_STEREO;
        final int encoding;
        if (wavData.bitsPerSample == 16) {
            encoding = AudioFormat.ENCODING_PCM_16BIT;
        } else if (wavData.bitsPerSample == 8) {
            encoding = AudioFormat.ENCODING_PCM_8BIT;
        } else {
            appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator media proxy: unsupported WAV bits=" + wavData.bitsPerSample + " path=" + path + " v42");
            return false;
        }

        int minBuffer = android.media.AudioTrack.getMinBufferSize(wavData.sampleRate, channelConfig, encoding);
        if (minBuffer <= 0) {
            minBuffer = 4096;
        }
        int streamBuffer = Math.max(minBuffer, Math.min(Math.max(wavData.pcm.length, 4096), 262144));
        android.media.AudioTrack track = null;
        try {
            try {
                AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                if (audioManager != null) {
                    int volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                    int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator media proxy: musicVolume=" + volume + "/" + maxVolume + " v42");
                    if (Build.VERSION.SDK_INT >= 26) {
                        android.media.AudioFocusRequest request = new android.media.AudioFocusRequest.Builder(
                                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                                .setAudioAttributes(new AudioAttributes.Builder()
                                        .setUsage(AudioAttributes.USAGE_MEDIA)
                                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                        .build())
                                .build();
                        audioManager.requestAudioFocus(request);
                    } else {
                        audioManager.requestAudioFocus(new AudioManager.OnAudioFocusChangeListener() {
                            @Override
                            public void onAudioFocusChange(int focusChange) {
                            }
                        }, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
                    }
                }
            } catch (Throwable focusThrowable) {
                appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator media proxy: audio focus failed: " + focusThrowable + " v42");
            }

            track = new android.media.AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    wavData.sampleRate,
                    channelConfig,
                    encoding,
                    streamBuffer,
                    android.media.AudioTrack.MODE_STREAM);
            droidBridgeAndroidTtsAudioTrack = track;
            track.play();
            markDroidBridgeVerityVoicePlaybackStarted("WAV AudioTrack");

            int offset = 0;
            while (offset < wavData.pcm.length) {
                int chunk = Math.min(streamBuffer, wavData.pcm.length - offset);
                int written = track.write(wavData.pcm, offset, chunk);
                if (written <= 0) {
                    appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator media proxy: AudioTrack write returned=" + written + " offset=" + offset + " path=" + path + " v42");
                    break;
                }
                offset += written;
            }

            int frameSize = Math.max(1, wavData.channels * Math.max(1, wavData.bitsPerSample / 8));
            int frameCount = wavData.pcm.length / frameSize;
            long durationMs = Math.max(150L, (long) frameCount * 1000L / Math.max(1, wavData.sampleRate));
            long deadline = System.currentTimeMillis() + Math.min(durationMs + 2000L, 60000L);
            while (track.getPlaybackHeadPosition() < frameCount && System.currentTimeMillis() < deadline) {
                try { Thread.sleep(35L); } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            try { track.stop(); } catch (Throwable ignored) {}
            try { track.release(); } catch (Throwable ignored) {}
            if (droidBridgeAndroidTtsAudioTrack == track) {
                droidBridgeAndroidTtsAudioTrack = null;
            }
            reportDroidBridgeAndroidTtsResult("WAV playback completed via AudioTrack pcmBytes=" + wavData.pcm.length
                    + " sampleRate=" + wavData.sampleRate + " channels=" + wavData.channels);
            appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator media proxy: played WAV via AudioTrack bytes="
                    + wavData.pcm.length
                    + " sampleRate=" + wavData.sampleRate
                    + " channels=" + wavData.channels
                    + " bits=" + wavData.bitsPerSample
                    + " path=" + path
                    + " v42");
            return offset == wavData.pcm.length;
        } catch (Throwable throwable) {
            appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator media proxy: AudioTrack play failed: " + throwable + " path=" + path + " v42");
            if (track != null) {
                try { track.stop(); } catch (Throwable ignored) {}
                try { track.release(); } catch (Throwable ignored) {}
                if (droidBridgeAndroidTtsAudioTrack == track) {
                    droidBridgeAndroidTtsAudioTrack = null;
                }
            }
            return false;
        }
    }

    private void playDroidBridgeAndroidNarratorWavPathWithMediaPlayer(@NonNull File audioFile, @NonNull String path) {
        try {
            MediaPlayer player = new MediaPlayer();
            droidBridgeAndroidTtsMediaPlayer = player;
            if (Build.VERSION.SDK_INT >= 21) {
                player.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
            } else {
                player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            }
            player.setOnCompletionListener(mp -> {
                try { mp.release(); } catch (Throwable ignored) {}
                if (droidBridgeAndroidTtsMediaPlayer == mp) {
                    droidBridgeAndroidTtsMediaPlayer = null;
                }
                try { audioFile.delete(); } catch (Throwable ignored) {}
                reportDroidBridgeAndroidTtsResult("WAV playback completed via MediaPlayer");
                appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator media proxy: WAV playback completed via MediaPlayer v42");
            });
            player.setOnErrorListener((mp, what, extra) -> {
                reportDroidBridgeAndroidTtsResult("WAV playback error via MediaPlayer what=" + what + " extra=" + extra);
                appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator media proxy: WAV playback error what="
                        + what + " extra=" + extra + " path=" + path + " v42");
                try { mp.release(); } catch (Throwable ignored) {}
                if (droidBridgeAndroidTtsMediaPlayer == mp) {
                    droidBridgeAndroidTtsMediaPlayer = null;
                }
                try { audioFile.delete(); } catch (Throwable ignored) {}
                return true;
            });
            player.setDataSource(path);
            player.prepare();
            player.start();
            markDroidBridgeVerityVoicePlaybackStarted("WAV MediaPlayer");
            appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator media proxy: playing WAV via MediaPlayer len="
                    + audioFile.length() + " path=" + path + " v42");
        } catch (Throwable throwable) {
            reportDroidBridgeAndroidTtsResult("WAV playback exception via MediaPlayer type="
                    + throwable.getClass().getName() + " message=" + String.valueOf(throwable.getMessage()));
            appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator media proxy: MediaPlayer play WAV failed: " + throwable + " v42");
            try { audioFile.delete(); } catch (Throwable ignored) {}
        }
    }

    @Nullable
    private DroidBridgeWavData readDroidBridgePcmWav(@NonNull File audioFile) {
        byte[] bytes = readDroidBridgeSmallFile(audioFile, 8L * 1024L * 1024L);
        if (bytes == null || bytes.length < 44) {
            return null;
        }
        if (!droidBridgeFourCcEquals(bytes, 0, 'R', 'I', 'F', 'F')
                || !droidBridgeFourCcEquals(bytes, 8, 'W', 'A', 'V', 'E')) {
            return null;
        }

        int sampleRate = 0;
        int channels = 0;
        int bitsPerSample = 0;
        int audioFormat = 0;
        int dataOffset = -1;
        int dataSize = -1;
        int offset = 12;
        while (offset + 8 <= bytes.length) {
            int chunkSize = droidBridgeReadLeInt(bytes, offset + 4);
            int chunkData = offset + 8;
            if (droidBridgeFourCcEquals(bytes, offset, 'f', 'm', 't', ' ')) {
                if (chunkSize >= 16 && chunkData + 16 <= bytes.length) {
                    audioFormat = droidBridgeReadLeShort(bytes, offset + 8);
                    channels = droidBridgeReadLeShort(bytes, offset + 10);
                    sampleRate = droidBridgeReadLeInt(bytes, offset + 12);
                    bitsPerSample = droidBridgeReadLeShort(bytes, offset + 22);
                }
            } else if (droidBridgeFourCcEquals(bytes, offset, 'd', 'a', 't', 'a')) {
                dataOffset = chunkData;
                if (chunkSize > 0 && chunkData + chunkSize <= bytes.length) {
                    dataSize = chunkSize;
                } else {
                    dataSize = Math.max(0, bytes.length - dataOffset);
                    appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator media proxy: inferred WAV data size="
                            + dataSize + " chunkSize=" + chunkSize + " len=" + bytes.length + " v42");
                }
                break;
            }
            if (chunkSize < 0 || chunkData + chunkSize > bytes.length) {
                break;
            }
            offset = chunkData + chunkSize + (chunkSize & 1);
        }

        if (audioFormat == 0) {
            for (int i = 12; i + 24 <= bytes.length; i++) {
                if (droidBridgeFourCcEquals(bytes, i, 'f', 'm', 't', ' ')) {
                    int chunkData = i + 8;
                    audioFormat = droidBridgeReadLeShort(bytes, chunkData);
                    channels = droidBridgeReadLeShort(bytes, chunkData + 2);
                    sampleRate = droidBridgeReadLeInt(bytes, chunkData + 4);
                    bitsPerSample = droidBridgeReadLeShort(bytes, chunkData + 14);
                    break;
                }
            }
        }
        if (dataOffset < 0) {
            for (int i = 12; i + 8 <= bytes.length; i++) {
                if (droidBridgeFourCcEquals(bytes, i, 'd', 'a', 't', 'a')) {
                    int size = droidBridgeReadLeInt(bytes, i + 4);
                    dataOffset = i + 8;
                    if (size > 0 && dataOffset + size <= bytes.length) {
                        dataSize = size;
                    } else {
                        dataSize = Math.max(0, bytes.length - dataOffset);
                    }
                    appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator media proxy: found WAV data by scan offset="
                            + i + " size=" + size + " inferred=" + dataSize + " v42");
                    break;
                }
            }
        }
        if (dataOffset < 0 && audioFormat == 1 && channels > 0 && sampleRate > 0 && bitsPerSample > 0 && bytes.length > 44) {
            dataOffset = 44;
            dataSize = bytes.length - dataOffset;
            appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator media proxy: no WAV data chunk; using bytes after header len="
                    + dataSize + " v42");
        }
        if (dataSize > 0) {
            int frameSize = channels * Math.max(1, bitsPerSample / 8);
            if (frameSize > 0) {
                dataSize = dataSize - (dataSize % frameSize);
            }
            if (dataOffset + dataSize > bytes.length) {
                dataSize = Math.max(0, bytes.length - dataOffset);
            }
        }

        if (audioFormat != 1 || sampleRate <= 0 || channels <= 0 || bitsPerSample <= 0 || dataOffset < 0 || dataSize <= 0) {
            appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator media proxy: unsupported WAV fmt="
                    + audioFormat + " sr=" + sampleRate + " ch=" + channels + " bits=" + bitsPerSample
                    + " data=" + dataSize + " offset=" + dataOffset + " len=" + bytes.length + " v42");
            return null;
        }
        byte[] pcm = new byte[dataSize];
        System.arraycopy(bytes, dataOffset, pcm, 0, dataSize);
        return new DroidBridgeWavData(sampleRate, channels, bitsPerSample, pcm);
    }

    @Nullable
    private byte[] readDroidBridgeSmallFile(@NonNull File file, long maxBytes) {
        if (file.length() <= 0L || file.length() > maxBytes) {
            appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator media proxy: refusing WAV len=" + file.length() + " v42");
            return null;
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream((int) file.length());
        byte[] buffer = new byte[16384];
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (Throwable throwable) {
            appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator media proxy: read WAV failed: " + throwable + " v42");
            return null;
        }
    }

    private boolean droidBridgeFourCcEquals(@NonNull byte[] data, int offset, char a, char b, char c, char d) {
        return offset >= 0
                && offset + 3 < data.length
                && data[offset] == (byte) a
                && data[offset + 1] == (byte) b
                && data[offset + 2] == (byte) c
                && data[offset + 3] == (byte) d;
    }

    private int droidBridgeReadLeShort(@NonNull byte[] data, int offset) {
        if (offset + 1 >= data.length) {
            return 0;
        }
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private int droidBridgeReadLeInt(@NonNull byte[] data, int offset) {
        if (offset + 3 >= data.length) {
            return 0;
        }
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    private static final class DroidBridgeWavData {
        final int sampleRate;
        final int channels;
        final int bitsPerSample;
        @NonNull
        final byte[] pcm;

        DroidBridgeWavData(int sampleRate, int channels, int bitsPerSample, @NonNull byte[] pcm) {
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.bitsPerSample = bitsPerSample;
            this.pcm = pcm;
        }
    }


    private boolean shouldSkipDroidBridgeAndroidNarratorText(@Nullable String text) {
        if (text == null) {
            return true;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return true;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (trimmed.matches("^[0-9]{1,3}%$") || "done".equals(lower)) {
            appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator UDP proxy: skipped progress speech text=" + trimmed + " v42");
            return true;
        }
        if (lower.startsWith("<verity>")) {
            appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator UDP proxy: skipped Verity chat echo len=" + trimmed.length() + " v42");
            return true;
        }
        if (lower.startsWith("api error:") || lower.contains("rate limit reached for model")) {
            appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator UDP proxy: skipped API-error speech len=" + trimmed.length() + " v42");
            return true;
        }
        return false;
    }

    private void cancelDroidBridgeAndroidNarratorAudio(@NonNull String reason) {
        synchronized (droidBridgeAndroidTtsLock) {
            droidBridgeAndroidTtsPlaybackGeneration++;
            droidBridgeAndroidTtsPendingSpeech.clear();
        }
        try {
            Handler speaker = droidBridgeAndroidTtsSpeakHandler;
            if (speaker != null) {
                speaker.removeCallbacksAndMessages(null);
            }
        } catch (Throwable ignored) {
        }
        try {
            TextToSpeech tts = droidBridgeAndroidTts;
            if (tts != null) {
                tts.stop();
            }
        } catch (Throwable ignored) {
        }
        try {
            MediaPlayer oldPlayer = droidBridgeAndroidTtsMediaPlayer;
            droidBridgeAndroidTtsMediaPlayer = null;
            if (oldPlayer != null) {
                try { oldPlayer.stop(); } catch (Throwable ignored) {}
                try { oldPlayer.release(); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {
        }
        try {
            android.media.AudioTrack oldTrack = droidBridgeAndroidTtsAudioTrack;
            droidBridgeAndroidTtsAudioTrack = null;
            if (oldTrack != null) {
                try { oldTrack.stop(); } catch (Throwable ignored) {}
                try { oldTrack.release(); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {
        }
        try {
            for (File file : droidBridgeAndroidTtsSynthesisFiles.values()) {
                if (file != null) {
                    try { file.delete(); } catch (Throwable ignored) {}
                }
            }
            droidBridgeAndroidTtsSynthesisFiles.clear();
        } catch (Throwable ignored) {
        }
        long cancelLogNow = System.currentTimeMillis();
        if (cancelLogNow - droidBridgeAndroidTtsLastCancelLogMs >= 1500L) {
            droidBridgeAndroidTtsLastCancelLogMs = cancelLogNow;
            appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator audio: cancelled pending/current speech reason="
                    + reason + " generation=" + droidBridgeAndroidTtsPlaybackGeneration + " v42");
        }
    }

    private void speakDroidBridgeAndroidNarratorText(@NonNull String text, boolean interrupt) {
        if (shouldSkipDroidBridgeAndroidNarratorText(text)) {
            return;
        }
        cancelDroidBridgeAndroidNarratorAudio((interrupt ? "interrupt speech" : "replace speech") + " len=" + text.length());
        String cloudKey = droidBridgeAndroidGroqTtsApiKey;
        if (droidBridgeAndroidTtsUnavailable) {
            if (cloudKey != null && !cloudKey.trim().isEmpty()) {
                String cloudText = text;
                Handler speaker = ensureDroidBridgeAndroidTtsSpeakHandler();
                appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator UDP proxy: Android TTS unavailable; using cached cloud TTS len="
                        + cloudText.length() + " v42");
                speaker.post(() -> synthesizeDroidBridgeAndroidGroqTtsToFile(cloudKey, cloudText, "narrator-fallback-unavailable"));
                return;
            }
            appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator UDP proxy: dropped speech because Android TTS is unavailable and no cloud key is cached v42");
            return;
        }
        TextToSpeech tts = droidBridgeAndroidTts;
        if (tts == null || !droidBridgeAndroidTtsReady) {
            if (cloudKey != null && !cloudKey.trim().isEmpty()) {
                String cloudText = text;
                Handler speaker = ensureDroidBridgeAndroidTtsSpeakHandler();
                appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator UDP proxy: TextToSpeech not ready; using cached cloud TTS len="
                        + cloudText.length() + " v42");
                speaker.post(() -> synthesizeDroidBridgeAndroidGroqTtsToFile(cloudKey, cloudText, "narrator-fallback-not-ready"));
                return;
            }
            enqueueDroidBridgeAndroidNarratorText(text, interrupt);
            if (tts == null) {
                appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator UDP proxy: queued speech because TextToSpeech is null v42");
            } else {
                appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator UDP proxy: queued speech until TextToSpeech is ready v42");
            }
            initializeDroidBridgeAndroidNarratorTts();
            return;
        }

        final String speakText = text;
        final boolean finalInterrupt = interrupt;
        Handler speaker = ensureDroidBridgeAndroidTtsSpeakHandler();
        speaker.post(() -> {
            TextToSpeech localTts = droidBridgeAndroidTts;
            if (droidBridgeAndroidTtsUnavailable) {
                appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator UDP proxy: dropped queued speech because Android TTS is unavailable v42");
                return;
            }
            if (localTts == null || !droidBridgeAndroidTtsReady) {
                enqueueDroidBridgeAndroidNarratorText(speakText, finalInterrupt);
                runOnUiThread(this::initializeDroidBridgeAndroidNarratorTts);
                return;
            }
            try {
                requestDroidBridgeAndroidTtsAudioFocus();

                // v42: Prefer Android TTS synthesizeToFile -> launcher MediaPlayer/AudioTrack.
                // Direct TextToSpeech.speak() can silently fail/route oddly while the game owns audio.
                if (trySynthesizeDroidBridgeAndroidNarratorTextToFile(localTts, speakText, finalInterrupt)) {
                    return;
                }

                int queueMode = finalInterrupt ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
                Bundle speechParams = new Bundle();
                speechParams.putString(TextToSpeech.Engine.KEY_PARAM_STREAM, String.valueOf(AudioManager.STREAM_MUSIC));
                int result;
                if (Build.VERSION.SDK_INT >= 21) {
                    result = localTts.speak(speakText, queueMode, speechParams, "mc-direct-" + System.nanoTime());
                } else {
                    java.util.HashMap<String, String> oldParams = new java.util.HashMap<>();
                    oldParams.put(TextToSpeech.Engine.KEY_PARAM_STREAM, String.valueOf(AudioManager.STREAM_MUSIC));
                    result = localTts.speak(speakText, queueMode, oldParams);
                }
                appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator UDP proxy: direct speak fallback result="
                        + result
                        + " len="
                        + speakText.length()
                        + " interrupt="
                        + finalInterrupt
                        + " v42");
            } catch (Throwable throwable) {
                appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator UDP proxy: speak failed: " + throwable + " v42");
            }
        });
    }

    private void requestDroidBridgeAndroidTtsAudioFocus() {
        try {
            AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (audioManager != null) {
                if (Build.VERSION.SDK_INT >= 26) {
                    android.media.AudioFocusRequest request = new android.media.AudioFocusRequest.Builder(
                            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                            .setAudioAttributes(new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build())
                            .build();
                    audioManager.requestAudioFocus(request);
                } else {
                    audioManager.requestAudioFocus(new AudioManager.OnAudioFocusChangeListener() {
                        @Override
                        public void onAudioFocusChange(int focusChange) {
                        }
                    }, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
                }
            }
        } catch (Throwable focusThrowable) {
            appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator UDP proxy: audio focus failed: " + focusThrowable + " v42");
        }
    }

    private boolean trySynthesizeDroidBridgeAndroidNarratorTextToFile(@NonNull TextToSpeech localTts,
                                                                       @NonNull String speakText,
                                                                       boolean interrupt) {
        if (Build.VERSION.SDK_INT < 21) {
            return false;
        }
        try {
            File dir = new File(getCacheDir(), "droidbridge_tts");
            if (!dir.exists() && !dir.mkdirs()) {
                appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator synth proxy: cache dir unavailable " + dir + " v42");
                return false;
            }
            final File outFile = new File(dir, "tts-" + System.nanoTime() + ".wav");
            final String utteranceId = "droidbridge-tts-" + System.nanoTime();
            if (interrupt) {
                try { localTts.stop(); } catch (Throwable ignored) {}
                try {
                    MediaPlayer oldPlayer = droidBridgeAndroidTtsMediaPlayer;
                    droidBridgeAndroidTtsMediaPlayer = null;
                    if (oldPlayer != null) {
                        try { oldPlayer.stop(); } catch (Throwable ignored) {}
                        try { oldPlayer.release(); } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
                try {
                    android.media.AudioTrack oldTrack = droidBridgeAndroidTtsAudioTrack;
                    droidBridgeAndroidTtsAudioTrack = null;
                    if (oldTrack != null) {
                        try { oldTrack.stop(); } catch (Throwable ignored) {}
                        try { oldTrack.release(); } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
            }
            droidBridgeAndroidTtsSynthesisFiles.put(utteranceId, outFile);
            localTts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                @Override
                public void onStart(String id) {
                    if (id != null && droidBridgeAndroidTtsSynthesisFiles.containsKey(id)) {
                        appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator synth proxy: synth started id=" + id + " v42");
                    }
                }

                @Override
                public void onDone(String id) {
                    File file = id == null ? null : droidBridgeAndroidTtsSynthesisFiles.remove(id);
                    if (file == null) {
                        return;
                    }
                    appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator synth proxy: synth done len="
                            + file.length() + " file=" + file.getAbsolutePath() + " v42");
                    Handler speaker = ensureDroidBridgeAndroidTtsSpeakHandler();
                    speaker.post(() -> playDroidBridgeAndroidNarratorSynthesizedFile(file, file.getAbsolutePath()));
                }

                @Override
                public void onError(String id) {
                    File file = id == null ? null : droidBridgeAndroidTtsSynthesisFiles.remove(id);
                    if (file != null) {
                        try { file.delete(); } catch (Throwable ignored) {}
                    }
                    appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator synth proxy: synth error id=" + id + " v42");
                }

                @Override
                public void onError(String id, int errorCode) {
                    File file = id == null ? null : droidBridgeAndroidTtsSynthesisFiles.remove(id);
                    if (file != null) {
                        try { file.delete(); } catch (Throwable ignored) {}
                    }
                    appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator synth proxy: synth error id=" + id
                            + " code=" + errorCode + " v42");
                }
            });
            Bundle params = new Bundle();
            params.putString(TextToSpeech.Engine.KEY_PARAM_STREAM, String.valueOf(AudioManager.STREAM_MUSIC));
            int result = localTts.synthesizeToFile(speakText, params, outFile, utteranceId);
            appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator synth proxy: synthesizeToFile result="
                    + result
                    + " len="
                    + speakText.length()
                    + " interrupt="
                    + interrupt
                    + " file="
                    + outFile.getAbsolutePath()
                    + " v42");
            if (result != TextToSpeech.SUCCESS) {
                droidBridgeAndroidTtsSynthesisFiles.remove(utteranceId);
                try { outFile.delete(); } catch (Throwable ignored) {}
                return false;
            }
            return true;
        } catch (Throwable throwable) {
            appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator synth proxy: synthesizeToFile failed: " + throwable + " v42");
            return false;
        }
    }

    private void playDroidBridgeAndroidNarratorSynthesizedFile(@NonNull File audioFile, @NonNull String path) {
        if (!audioFile.exists() || !audioFile.isFile() || audioFile.length() <= 0L) {
            appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator synth proxy: synthesized file missing/empty path="
                    + path + " len=" + audioFile.length() + " v42");
            try { audioFile.delete(); } catch (Throwable ignored) {}
            return;
        }
        try {
            // Many Android TTS engines write WAV here, so try AudioTrack first. If the engine writes
            // another playable container, MediaPlayer handles it as fallback.
            DroidBridgeWavData wavData = readDroidBridgePcmWav(audioFile);
            boolean played = wavData != null && playDroidBridgeAndroidNarratorWavWithAudioTrack(wavData, path);
            if (played) {
                long playedLen = audioFile.length();
                try { audioFile.delete(); } catch (Throwable ignored) {}
                appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator synth proxy: played synthesized file via AudioTrack len="
                        + playedLen + " v42");
                return;
            }
        } catch (Throwable throwable) {
            appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator synth proxy: AudioTrack synthesized playback failed: "
                    + throwable + " v42");
        }
        appendDroidBridgeAndroidProxyLog("DroidBridge Android narrator synth proxy: using MediaPlayer for synthesized file len="
                + audioFile.length() + " path=" + path + " v42");
        playDroidBridgeAndroidNarratorWavPathWithMediaPlayer(audioFile, path);
    }

    private void enqueueDroidBridgeAndroidNarratorText(@NonNull String text, boolean interrupt) {
        synchronized (droidBridgeAndroidTtsLock) {
            if (interrupt) {
                droidBridgeAndroidTtsPendingSpeech.clear();
            }
            if (droidBridgeAndroidTtsPendingSpeech.size() >= 16) {
                droidBridgeAndroidTtsPendingSpeech.removeFirst();
            }
            droidBridgeAndroidTtsPendingSpeech.addLast((interrupt ? "1" : "0") + text);
        }
    }

    private void flushDroidBridgeAndroidNarratorQueue() {
        runOnUiThread(() -> {
            while (true) {
                String next;
                synchronized (droidBridgeAndroidTtsLock) {
                    next = droidBridgeAndroidTtsPendingSpeech.pollFirst();
                }
                if (next == null) {
                    break;
                }
                boolean interrupt = next.startsWith("1");
                String text = next.length() > 1 ? next.substring(1) : "";
                if (!text.isEmpty()) {
                    speakDroidBridgeAndroidNarratorText(text, interrupt);
                }
            }
        });
    }

    private void stopDroidBridgeAndroidNarratorProxy() {
        droidBridgeAndroidTtsProxyRunning = false;
        removeDroidBridgeVerityTtsFallbackListener();
        droidBridgeAndroidTtsHandler.removeCallbacksAndMessages(null);
        try {
            DatagramSocket socket = droidBridgeAndroidTtsSocket;
            if (socket != null) {
                socket.close();
            }
        } catch (Throwable ignored) {
        }
        droidBridgeAndroidTtsSocket = null;
        try {
            ServerSocket serverSocket = droidBridgeAndroidTtsServerSocket;
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (Throwable ignored) {
        }
        droidBridgeAndroidTtsServerSocket = null;

        android.os.HandlerThread speakThread = droidBridgeAndroidTtsSpeakThread;
        droidBridgeAndroidTtsSpeakThread = null;
        droidBridgeAndroidTtsSpeakHandler = null;
        if (speakThread != null) {
            try {
                if (Build.VERSION.SDK_INT >= 18) {
                    speakThread.quitSafely();
                } else {
                    speakThread.quit();
                }
            } catch (Throwable ignored) {
            }
        }

        MediaPlayer mediaPlayer = droidBridgeAndroidTtsMediaPlayer;
        droidBridgeAndroidTtsMediaPlayer = null;
        if (mediaPlayer != null) {
            try { mediaPlayer.stop(); } catch (Throwable ignored) {}
            try { mediaPlayer.release(); } catch (Throwable ignored) {}
        }
        android.media.AudioTrack audioTrack = droidBridgeAndroidTtsAudioTrack;
        droidBridgeAndroidTtsAudioTrack = null;
        if (audioTrack != null) {
            try { audioTrack.stop(); } catch (Throwable ignored) {}
            try { audioTrack.release(); } catch (Throwable ignored) {}
        }

        shutdownDroidBridgeAndroidNarratorTts(true);
    }


    private void startDroidBridgeAndroidMicProxy() {
        synchronized (droidBridgeAndroidMicProxyServerLock) {
            Thread existingThread = droidBridgeAndroidMicServerThread;
            ServerSocket existingSocket = droidBridgeAndroidMicServerSocket;
            if (droidBridgeAndroidMicProxyRunning
                    && existingThread != null
                    && existingThread.isAlive()
                    && existingSocket != null
                    && !existingSocket.isClosed()) {
                return;
            }

            // A previous bind/start failure used to leave this flag true forever,
            // which made every later restart request a no-op. Reset the stale state
            // and create a fresh listener thread.
            droidBridgeAndroidMicProxyRunning = true;
            droidBridgeAndroidMicServerSocket = null;
            Thread thread = new Thread(this::runDroidBridgeAndroidMicProxy,
                    "DroidBridge-Android-Mic-Proxy");
            thread.setDaemon(true);
            droidBridgeAndroidMicServerThread = thread;
            thread.start();
        }
        reportDroidBridgeAndroidMicProxyStatus(
                "start requested port=" + DROIDBRIDGE_ANDROID_MIC_PROXY_PORT + " v68");
    }

    private void runDroidBridgeAndroidMicProxy() {
        final Thread ownerThread = Thread.currentThread();
        int bindAttempt = 0;

        while (droidBridgeAndroidMicProxyRunning
                && droidBridgeAndroidMicServerThread == ownerThread) {
            ServerSocket serverSocket = null;
            try {
                bindAttempt++;
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),
                        DROIDBRIDGE_ANDROID_MIC_PROXY_PORT));
                serverSocket.setSoTimeout(500);
                droidBridgeAndroidMicServerSocket = serverSocket;
                appendDroidBridgeAndroidProxyLog(
                        "DroidBridge Verity Android microphone TCP proxy: listening on 127.0.0.1:"
                                + DROIDBRIDGE_ANDROID_MIC_PROXY_PORT + " v68");
                reportDroidBridgeAndroidMicProxyStatus(
                        "listening on 127.0.0.1:" + DROIDBRIDGE_ANDROID_MIC_PROXY_PORT
                                + " bindAttempt=" + bindAttempt + " v68");

                while (droidBridgeAndroidMicProxyRunning
                        && droidBridgeAndroidMicServerThread == ownerThread) {
                    try {
                        Socket socket = serverSocket.accept();
                        handleDroidBridgeAndroidMicSocket(socket);
                    } catch (SocketTimeoutException ignored) {
                        // Poll the running flag so shutdown and replacement are clean.
                    }
                }
                break;
            } catch (Throwable throwable) {
                if (!droidBridgeAndroidMicProxyRunning
                        || droidBridgeAndroidMicServerThread != ownerThread) {
                    break;
                }
                appendDroidBridgeAndroidProxyLog(
                        "DroidBridge Verity Android microphone TCP proxy bind/listen failed attempt="
                                + bindAttempt + ": " + throwable + " v68");
                reportDroidBridgeAndroidMicProxyStatus(
                        "bind/listen failed attempt=" + bindAttempt
                                + " error=" + throwable.getClass().getSimpleName()
                                + ": " + String.valueOf(throwable.getMessage()) + " v68");
                try {
                    Thread.sleep(Math.min(2000L, 200L * bindAttempt));
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } finally {
                try {
                    if (serverSocket != null) {
                        serverSocket.close();
                    }
                } catch (Throwable ignored) {
                }
                if (droidBridgeAndroidMicServerSocket == serverSocket) {
                    droidBridgeAndroidMicServerSocket = null;
                }
            }
        }

        synchronized (droidBridgeAndroidMicProxyServerLock) {
            if (droidBridgeAndroidMicServerThread == ownerThread) {
                droidBridgeAndroidMicServerThread = null;
                droidBridgeAndroidMicServerSocket = null;
                // Only clear the running state when this was not intentionally
                // replaced by a newer listener thread.
                droidBridgeAndroidMicProxyRunning = false;
            }
        }
        reportDroidBridgeAndroidMicProxyStatus("listener thread stopped v68");
    }

    private void reportDroidBridgeAndroidMicProxyStatus(@NonNull String message) {
        appendDroidBridgeAndroidProxyLog(
                "DroidBridge Verity Android microphone TCP proxy: " + message);
        try {
            System.out.println("[DroidBridge/MicProxy]: " + message);
        } catch (Throwable ignored) {
        }
    }

    private void handleDroidBridgeAndroidMicSocket(@NonNull Socket socket) {
        try (Socket closeableSocket = socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(closeableSocket.getInputStream(), StandardCharsets.UTF_8))) {
            String command = reader.readLine();
            if (command == null) {
                return;
            }
            command = command.trim().toUpperCase(Locale.ROOT);
            if ("START".equals(command)) {
                boolean ok = startDroidBridgeAndroidMicRecording();
                closeableSocket.getOutputStream().write((ok ? "OK\n" : "ERR\n").getBytes(StandardCharsets.UTF_8));
                closeableSocket.getOutputStream().flush();
            } else if ("STOP".equals(command)) {
                byte[] audio = stopDroidBridgeAndroidMicRecording();
                DataOutputStream out = new DataOutputStream(closeableSocket.getOutputStream());
                out.writeInt(audio.length);
                if (audio.length > 0) {
                    out.write(audio);
                }
                out.flush();
            } else if ("TRANSCRIBE".equals(command)) {
                String encodedAudio = reader.readLine();
                String transcription = transcribeDroidBridgeVerityProxyPayload(encodedAudio);
                DataOutputStream out = new DataOutputStream(closeableSocket.getOutputStream());
                if (transcription == null) {
                    out.write("ERR\n".getBytes(StandardCharsets.UTF_8));
                } else {
                    String encodedText = android.util.Base64.encodeToString(
                            transcription.getBytes(StandardCharsets.UTF_8),
                            android.util.Base64.NO_WRAP);
                    out.write("OK\n".getBytes(StandardCharsets.UTF_8));
                    out.write(encodedText.getBytes(StandardCharsets.UTF_8));
                    out.write('\n');
                }
                out.flush();
            } else {
                closeableSocket.getOutputStream().write("ERR\n".getBytes(StandardCharsets.UTF_8));
                closeableSocket.getOutputStream().flush();
            }
        } catch (Throwable throwable) {
            appendDroidBridgeAndroidProxyLog("DroidBridge Verity Android microphone TCP proxy socket failed: " + throwable);
        }
    }

    @Nullable
    private String transcribeDroidBridgeVerityProxyPayload(@Nullable String encodedAudio) {
        if (encodedAudio == null || encodedAudio.isEmpty()) {
            appendDroidBridgeAndroidProxyLog(
                    "DroidBridge Verity 6.1 STT launcher bridge rejected empty audio v63");
            return "";
        }
        if (encodedAudio.length() > 8 * 1024 * 1024) {
            appendDroidBridgeAndroidProxyLog(
                    "DroidBridge Verity 6.1 STT launcher bridge rejected oversized audio v63");
            return null;
        }

        final byte[] pcmAudio;
        try {
            pcmAudio = android.util.Base64.decode(encodedAudio, android.util.Base64.NO_WRAP);
        } catch (Throwable throwable) {
            appendDroidBridgeAndroidProxyLog(
                    "DroidBridge Verity 6.1 STT launcher bridge base64 failure="
                            + throwable.getClass().getSimpleName() + " v63");
            return null;
        }
        if (pcmAudio.length < 3200) {
            appendDroidBridgeAndroidProxyLog(
                    "DroidBridge Verity 6.1 STT launcher bridge ignored short audio bytes="
                            + pcmAudio.length + " v63");
            return "";
        }

        /*
         * The LibPatcher only sends TRANSCRIBE here when Verity's STT provider
         * is NATIVE. GROQ and WHISPER continue through Verity's own provider
         * implementations, so a local/Ollama chat setup must never require a
         * Groq key merely to use the Talk button.
         */
        appendDroidBridgeAndroidProxyLog(
                "DroidBridge Verity 6.1 native STT launcher bridge transcribing bytes="
                        + pcmAudio.length + " backend=AndroidSpeechRecognizer v67");
        String transcription = transcribeDroidBridgeVerityPcmWithAndroidSpeech(pcmAudio);
        if (transcription == null) {
            appendDroidBridgeAndroidProxyLog(
                    "DroidBridge Verity 6.1 native STT launcher bridge failed v67");
            return null;
        }
        appendDroidBridgeAndroidProxyLog(
                "DroidBridge Verity 6.1 native STT launcher bridge returning chars="
                        + transcription.length() + " v67");
        return transcription;
    }


    /**
     * Transcribes Verity's already-recorded 16 kHz mono PCM with Android's
     * speech-recognition service. Android 13 added EXTRA_AUDIO_SOURCE, which
     * lets us feed the exact PCM captured by the launcher instead of opening a
     * second microphone session. This keeps Verity's NATIVE STT independent of
     * whichever chat provider is selected (including Ollama).
     */
    @Nullable
    private String transcribeDroidBridgeVerityPcmWithAndroidSpeech(@NonNull byte[] pcmAudio) {
        if (Build.VERSION.SDK_INT < 33) {
            appendDroidBridgeAndroidProxyLog(
                    "DroidBridge Verity native STT requires Android 13+ for injected PCM; api="
                            + Build.VERSION.SDK_INT + " v67");
            return null;
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            appendDroidBridgeAndroidProxyLog(
                    "DroidBridge Verity native STT denied: RECORD_AUDIO permission missing v67");
            return null;
        }
        boolean recognitionAvailable;
        try {
            recognitionAvailable = SpeechRecognizer.isRecognitionAvailable(this);
        } catch (Throwable throwable) {
            appendDroidBridgeAndroidProxyLog(
                    "DroidBridge Verity native STT availability check failed="
                            + throwable.getClass().getSimpleName() + " v67");
            return null;
        }
        if (!recognitionAvailable) {
            appendDroidBridgeAndroidProxyLog(
                    "DroidBridge Verity native STT unavailable: no Android recognition service v67");
            return null;
        }

        boolean onDeviceAvailable = false;
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                onDeviceAvailable = SpeechRecognizer.isOnDeviceRecognitionAvailable(this);
            } catch (Throwable throwable) {
                appendDroidBridgeAndroidProxyLog(
                        "DroidBridge Verity native STT on-device availability check failed="
                                + throwable.getClass().getSimpleName() + " v67");
            }
        }

        DroidBridgeSpeechRecognitionResult result = null;
        if (onDeviceAvailable) {
            result = runDroidBridgeAndroidSpeechRecognitionAttempt(pcmAudio, true);
            if (result.text != null) {
                return result.text;
            }
            appendDroidBridgeAndroidProxyLog(
                    "DroidBridge Verity native STT on-device attempt failed error="
                            + describeDroidBridgeSpeechRecognitionError(result.errorCode)
                            + "; retrying default recognizer with offline preference v67");
        }

        result = runDroidBridgeAndroidSpeechRecognitionAttempt(pcmAudio, false);
        if (result.text != null) {
            return result.text;
        }
        appendDroidBridgeAndroidProxyLog(
                "DroidBridge Verity native STT default recognizer failed error="
                        + describeDroidBridgeSpeechRecognitionError(result.errorCode) + " v67");
        return result.noSpeech ? "" : null;
    }

    @NonNull
    private DroidBridgeSpeechRecognitionResult runDroidBridgeAndroidSpeechRecognitionAttempt(
            @NonNull byte[] pcmAudio,
            boolean onDevice
    ) {
        final CountDownLatch completion = new CountDownLatch(1);
        final AtomicReference<String> finalText = new AtomicReference<>();
        final AtomicReference<String> partialText = new AtomicReference<>();
        final AtomicInteger errorCode = new AtomicInteger(0);
        final AtomicReference<SpeechRecognizer> recognizerRef = new AtomicReference<>();
        final AtomicReference<ParcelFileDescriptor> readPipeRef = new AtomicReference<>();
        final AtomicReference<ParcelFileDescriptor> writePipeRef = new AtomicReference<>();

        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            readPipeRef.set(pipe[0]);
            writePipeRef.set(pipe[1]);
        } catch (Throwable throwable) {
            appendDroidBridgeAndroidProxyLog(
                    "DroidBridge Verity native STT audio pipe creation failed="
                            + throwable.getClass().getSimpleName() + " v67");
            return new DroidBridgeSpeechRecognitionResult(null,
                    DROIDBRIDGE_STT_ERROR_PIPE, false);
        }

        runOnUiThread(() -> {
            try {
                SpeechRecognizer recognizer;
                if (onDevice && Build.VERSION.SDK_INT >= 31) {
                    recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
                } else {
                    recognizer = SpeechRecognizer.createSpeechRecognizer(this);
                }
                recognizerRef.set(recognizer);
                recognizer.setRecognitionListener(new RecognitionListener() {
                    @Override
                    public void onReadyForSpeech(Bundle params) {
                        appendDroidBridgeAndroidProxyLog(
                                "DroidBridge Verity native STT ready backend="
                                        + (onDevice ? "on-device" : "default") + " v67");
                    }

                    @Override
                    public void onBeginningOfSpeech() {
                    }

                    @Override
                    public void onRmsChanged(float rmsdB) {
                    }

                    @Override
                    public void onBufferReceived(byte[] buffer) {
                    }

                    @Override
                    public void onEndOfSpeech() {
                    }

                    @Override
                    public void onError(int error) {
                        errorCode.compareAndSet(0, error);
                        completion.countDown();
                    }

                    @Override
                    public void onResults(Bundle results) {
                        String text = firstDroidBridgeSpeechResult(results);
                        if (!text.isEmpty()) {
                            finalText.set(text);
                        }
                        completion.countDown();
                    }

                    @Override
                    public void onPartialResults(Bundle partialResults) {
                        String text = firstDroidBridgeSpeechResult(partialResults);
                        if (!text.isEmpty()) {
                            partialText.set(text);
                        }
                    }

                    @Override
                    public void onEvent(int eventType, Bundle params) {
                    }
                });

                Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,
                        Locale.getDefault().toLanguageTag());
                intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
                intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
                intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readPipeRef.get());
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1);
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING,
                        AudioFormat.ENCODING_PCM_16BIT);
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000);
                recognizer.startListening(intent);

                Thread writer = new Thread(() -> {
                    ParcelFileDescriptor writePipe = writePipeRef.getAndSet(null);
                    if (writePipe == null) return;
                    try (FileOutputStream output =
                                 new FileOutputStream(writePipe.getFileDescriptor())) {
                        output.write(pcmAudio);
                        output.flush();
                    } catch (Throwable throwable) {
                        errorCode.compareAndSet(0, DROIDBRIDGE_STT_ERROR_PIPE);
                        appendDroidBridgeAndroidProxyLog(
                                "DroidBridge Verity native STT audio pipe write failed="
                                        + throwable.getClass().getSimpleName() + " v67");
                        completion.countDown();
                    } finally {
                        closeDroidBridgeParcelFileDescriptor(writePipe);
                    }
                }, "DroidBridge-Verity-STT-Audio-Writer");
                writer.setDaemon(true);
                writer.start();
            } catch (Throwable throwable) {
                errorCode.compareAndSet(0, DROIDBRIDGE_STT_ERROR_START);
                appendDroidBridgeAndroidProxyLog(
                        "DroidBridge Verity native STT start failed backend="
                                + (onDevice ? "on-device" : "default")
                                + " error=" + throwable.getClass().getSimpleName() + " v67");
                completion.countDown();
            }
        });

        boolean completed = false;
        try {
            completed = completion.await(25L, TimeUnit.SECONDS);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            errorCode.compareAndSet(0, DROIDBRIDGE_STT_ERROR_INTERRUPTED);
        }
        if (!completed) {
            errorCode.compareAndSet(0, DROIDBRIDGE_STT_ERROR_TIMEOUT);
        }

        SpeechRecognizer recognizer = recognizerRef.getAndSet(null);
        runOnUiThread(() -> {
            if (recognizer != null) {
                try { recognizer.cancel(); } catch (Throwable ignored) {}
                try { recognizer.destroy(); } catch (Throwable ignored) {}
            }
        });
        closeDroidBridgeParcelFileDescriptor(readPipeRef.getAndSet(null));
        closeDroidBridgeParcelFileDescriptor(writePipeRef.getAndSet(null));

        String text = finalText.get();
        if ((text == null || text.trim().isEmpty()) && completed) {
            text = partialText.get();
        }
        if (text != null) {
            text = text.replaceAll("[\\r\\n\\t]+", " ")
                    .replaceAll("  +", " ").trim();
            if (text.length() > 500) text = text.substring(0, 500).trim();
        }
        int resolvedError = errorCode.get();
        boolean noSpeech = resolvedError == SpeechRecognizer.ERROR_NO_MATCH
                || resolvedError == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
        appendDroidBridgeAndroidProxyLog(
                "DroidBridge Verity native STT completed backend="
                        + (onDevice ? "on-device" : "default")
                        + " chars=" + (text == null ? 0 : text.length())
                        + " error=" + describeDroidBridgeSpeechRecognitionError(resolvedError)
                        + " v67");
        return new DroidBridgeSpeechRecognitionResult(text, resolvedError, noSpeech);
    }

    @NonNull
    private static String firstDroidBridgeSpeechResult(@Nullable Bundle results) {
        if (results == null) return "";
        ArrayList<String> matches = results.getStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches == null) return "";
        for (String match : matches) {
            if (match != null && !match.trim().isEmpty()) {
                return match.trim();
            }
        }
        return "";
    }

    private static void closeDroidBridgeParcelFileDescriptor(
            @Nullable ParcelFileDescriptor descriptor
    ) {
        if (descriptor == null) return;
        try { descriptor.close(); } catch (Throwable ignored) {}
    }

    @NonNull
    private static String describeDroidBridgeSpeechRecognitionError(int error) {
        switch (error) {
            case 0: return "none";
            case SpeechRecognizer.ERROR_AUDIO: return "audio(" + error + ")";
            case SpeechRecognizer.ERROR_CLIENT: return "client(" + error + ")";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "insufficient-permissions(" + error + ")";
            case SpeechRecognizer.ERROR_NETWORK: return "network(" + error + ")";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "network-timeout(" + error + ")";
            case SpeechRecognizer.ERROR_NO_MATCH: return "no-match(" + error + ")";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "recognizer-busy(" + error + ")";
            case SpeechRecognizer.ERROR_SERVER: return "server(" + error + ")";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "speech-timeout(" + error + ")";
            case DROIDBRIDGE_STT_ERROR_TIMEOUT: return "launcher-timeout";
            case DROIDBRIDGE_STT_ERROR_INTERRUPTED: return "launcher-interrupted";
            case DROIDBRIDGE_STT_ERROR_PIPE: return "audio-pipe";
            case DROIDBRIDGE_STT_ERROR_START: return "recognizer-start";
            default: return "unknown(" + error + ")";
        }
    }

    private static final int DROIDBRIDGE_STT_ERROR_TIMEOUT = -1001;
    private static final int DROIDBRIDGE_STT_ERROR_INTERRUPTED = -1002;
    private static final int DROIDBRIDGE_STT_ERROR_PIPE = -1003;
    private static final int DROIDBRIDGE_STT_ERROR_START = -1004;

    private static final class DroidBridgeSpeechRecognitionResult {
        @Nullable final String text;
        final int errorCode;
        final boolean noSpeech;

        DroidBridgeSpeechRecognitionResult(
                @Nullable String text,
                int errorCode,
                boolean noSpeech
        ) {
            this.text = text;
            this.errorCode = errorCode;
            this.noSpeech = noSpeech;
        }
    }

    private boolean startDroidBridgeAndroidMicRecording() {
        synchronized (droidBridgeAndroidMicLock) {
            if (droidBridgeAndroidMicRecording) {
                return true;
            }
        }

        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            appendDroidBridgeAndroidProxyLog("DroidBridge Verity Android microphone: RECORD_AUDIO permission is not granted");
            return false;
        }

        try {
            int minBufferSize = AudioRecord.getMinBufferSize(16000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minBufferSize <= 0) {
                minBufferSize = 4096;
            }
            int bufferSize = Math.max(minBufferSize * 2, 8192);
            AudioRecord recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    16000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize);
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                try {
                    recorder.release();
                } catch (Throwable ignored) {
                }
                appendDroidBridgeAndroidProxyLog("DroidBridge Verity Android microphone: AudioRecord did not initialize");
                return false;
            }

            synchronized (droidBridgeAndroidMicLock) {
                droidBridgeAndroidMicRecorder = recorder;
                droidBridgeAndroidMicBuffer = new ByteArrayOutputStream(32768);
                droidBridgeAndroidMicRecording = true;
            }

            recorder.startRecording();
            final AudioRecord micRecorder = recorder;
            final int micChunkSize = minBufferSize;
            droidBridgeAndroidMicThread = new Thread(() -> runDroidBridgeAndroidMicRecordingLoop(micRecorder, micChunkSize),
                    "DroidBridge-Verity-Mic-Recorder");
            droidBridgeAndroidMicThread.setDaemon(true);
            droidBridgeAndroidMicThread.start();
            appendDroidBridgeAndroidProxyLog("DroidBridge Verity Android microphone: recording started sampleRate=16000 buffer="
                    + bufferSize);
            return true;
        } catch (Throwable throwable) {
            synchronized (droidBridgeAndroidMicLock) {
                droidBridgeAndroidMicRecording = false;
                droidBridgeAndroidMicRecorder = null;
                droidBridgeAndroidMicBuffer = null;
            }
            appendDroidBridgeAndroidProxyLog("DroidBridge Verity Android microphone: start failed: " + throwable);
            return false;
        }
    }

    private void runDroidBridgeAndroidMicRecordingLoop(@NonNull AudioRecord recorder, int chunkSize) {
        int safeChunkSize = Math.max(1024, Math.min(8192, chunkSize));
        byte[] buffer = new byte[safeChunkSize];
        while (droidBridgeAndroidMicRecording) {
            int read;
            try {
                read = recorder.read(buffer, 0, buffer.length);
            } catch (Throwable throwable) {
                appendDroidBridgeAndroidProxyLog("DroidBridge Verity Android microphone: read failed: " + throwable);
                break;
            }
            if (read > 0) {
                synchronized (droidBridgeAndroidMicLock) {
                    ByteArrayOutputStream stream = droidBridgeAndroidMicBuffer;
                    if (stream != null && stream.size() < 5 * 1024 * 1024) {
                        stream.write(buffer, 0, read);
                    }
                }
            }
        }
    }

    @NonNull
    private byte[] stopDroidBridgeAndroidMicRecording() {
        AudioRecord recorder;
        Thread thread;
        synchronized (droidBridgeAndroidMicLock) {
            if (!droidBridgeAndroidMicRecording) {
                ByteArrayOutputStream stream = droidBridgeAndroidMicBuffer;
                return stream != null ? stream.toByteArray() : new byte[0];
            }
            droidBridgeAndroidMicRecording = false;
            recorder = droidBridgeAndroidMicRecorder;
            thread = droidBridgeAndroidMicThread;
        }

        if (recorder != null) {
            try {
                recorder.stop();
            } catch (Throwable ignored) {
            }
        }
        if (thread != null && thread.isAlive()) {
            try {
                thread.join(750L);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        if (recorder != null) {
            try {
                recorder.release();
            } catch (Throwable ignored) {
            }
        }

        byte[] audio;
        synchronized (droidBridgeAndroidMicLock) {
            ByteArrayOutputStream stream = droidBridgeAndroidMicBuffer;
            audio = stream != null ? stream.toByteArray() : new byte[0];
            droidBridgeAndroidMicRecorder = null;
            droidBridgeAndroidMicThread = null;
            droidBridgeAndroidMicBuffer = null;
        }
        appendDroidBridgeAndroidProxyLog("DroidBridge Verity Android microphone: recording stopped bytes=" + audio.length);
        return audio;
    }


    /**
     * Verity 6 rewrote its native speech stack. On Android it may receive the
     * launcher PCM correctly but then fail silently while loading Sherpa JNI.
     * Give the mod time to succeed first; only use the configured Groq key if no
     * player chat line appears after the recording stops.
     */
    private void scheduleDroidBridgeVerity6SpeechToTextFallback(@NonNull byte[] pcmAudio) {
        /*
         * Verity 6.1 speech recognition is patched inside the mod JVM so its
         * original KeybindHandler receives the transcript and submits it through
         * Minecraft's ClientPacketListener. Do not synthesize T/character/Enter
         * events here: opening chat and typing in the same GLFW poll can leave an
         * empty ChatScreen with a flashing cursor.
         */
    }

    private void runDroidBridgeVerity6SpeechToTextFallback(
            @NonNull byte[] pcmAudio,
            long stoppedAt,
            long generation
    ) {
        String apiKey = resolveDroidBridgeVerityGroqApiKey();
        if (apiKey.isEmpty()) {
            appendDroidBridgeAndroidProxyLog(
                    "DroidBridge Verity 6 STT fallback skipped: no configured Groq key v48");
            return;
        }

        String transcription = transcribeDroidBridgeVerityPcmWithGroq(apiKey, pcmAudio);
        if (transcription.isEmpty()) return;
        if (generation != droidBridgeVerityMicFallbackGeneration) return;
        if (droidBridgeVerityLastUserChatUptimeMs > stoppedAt) {
            appendDroidBridgeAndroidProxyLog(
                    "DroidBridge Verity 6 STT fallback suppressed because the mod already inserted chat v48");
            return;
        }
        injectDroidBridgeVerityTranscriptionIntoChat(transcription, generation);
    }

    private boolean isDroidBridgeVerity6OrNewerInstalled() {
        Boolean cached = droidBridgeVerity6Installed;
        if (cached != null) return cached;

        boolean found = false;
        File gameDirectory = resolveActiveGameDirectoryForRuntime();
        File[] candidates = new File[]{
                new File(gameDirectory, "mods"),
                gameDirectory.getParentFile() == null
                        ? null : new File(gameDirectory.getParentFile(), "mods")
        };
        for (File directory : candidates) {
            if (directory == null) continue;
            File[] files = directory.listFiles();
            if (files == null) continue;
            for (File file : files) {
                if (file == null || !file.isFile()) continue;
                String name = file.getName().toLowerCase(Locale.ROOT);
                if (!name.endsWith(".jar") || !name.contains("verity")) continue;
                if (name.substring(0, name.length() - 4).matches(".*verity[^0-9]*(?:[6-9]|[1-9][0-9])(?:[^0-9].*)?")) {
                    found = true;
                    break;
                }
            }
            if (found) break;
        }
        droidBridgeVerity6Installed = found;
        return found;
    }

    @NonNull
    private String resolveDroidBridgeVerityGroqApiKey() {
        String cached = droidBridgeAndroidGroqTtsApiKey;
        if (cached != null && !cached.trim().isEmpty()) return cached.trim();

        File config = new File(new File(resolveActiveGameDirectoryForRuntime(), "config"),
                "verity-common.toml");
        String apiKey = "";
        String groqKey = "";
        if (!config.isFile()) return "";

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(config), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("[")) continue;
                int equals = trimmed.indexOf('=');
                if (equals <= 0) continue;
                String key = trimmed.substring(0, equals).trim();
                String value = stripDroidBridgeTomlValue(trimmed.substring(equals + 1));
                if (value.isEmpty()) continue;
                if ("groqKey".equalsIgnoreCase(key)) {
                    groqKey = value;
                } else if ("apiKey".equalsIgnoreCase(key)) {
                    apiKey = value;
                }
            }
        } catch (Throwable throwable) {
            appendDroidBridgeAndroidProxyLog(
                    "DroidBridge Verity 6 STT fallback could not read config: "
                            + throwable.getClass().getSimpleName() + " v48");
        }
        String result = !groqKey.isEmpty() ? groqKey : apiKey;
        if (!result.isEmpty()) droidBridgeAndroidGroqTtsApiKey = result;
        return result;
    }

    @NonNull
    private static String stripDroidBridgeTomlValue(@Nullable String value) {
        if (value == null) return "";
        String result = value.trim();
        int comment = result.indexOf(" #");
        if (comment >= 0) result = result.substring(0, comment).trim();
        if (result.length() >= 2) {
            char first = result.charAt(0);
            char last = result.charAt(result.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                result = result.substring(1, result.length() - 1);
            }
        }
        return result.trim();
    }

    @NonNull
    private String transcribeDroidBridgeVerityPcmWithGroq(
            @NonNull String apiKey,
            @NonNull byte[] pcmAudio
    ) {
        HttpURLConnection connection = null;
        try {
            byte[] wav = buildDroidBridgeVerityPcmWav(pcmAudio);
            String boundary = "DroidBridgeVerityBoundary" + Long.toHexString(System.nanoTime());
            ByteArrayOutputStream body = new ByteArrayOutputStream(wav.length + 1024);
            writeDroidBridgeMultipartText(body, boundary, "model", "whisper-large-v3-turbo");
            writeDroidBridgeMultipartText(body, boundary, "response_format", "json");
            body.write(("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"droidbridge-verity.wav\"\r\n"
                    + "Content-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(wav);
            body.write("\r\n".getBytes(StandardCharsets.UTF_8));
            body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            byte[] requestBody = body.toByteArray();

            URL url = new URL("https://api.groq.com/openai/v1/audio/transcriptions");
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(30000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            connection.setRequestProperty("Accept", "application/json");
            connection.setFixedLengthStreamingMode(requestBody.length);
            try (java.io.OutputStream output = connection.getOutputStream()) {
                output.write(requestBody);
                output.flush();
            }

            int status = connection.getResponseCode();
            byte[] responseBody = readDroidBridgeHttpBody(connection, status >= 400);
            String response = responseBody == null
                    ? "" : new String(responseBody, StandardCharsets.UTF_8);
            if (status != 200) {
                appendDroidBridgeAndroidProxyLog(
                        "DroidBridge Verity 6 STT fallback failed HTTP=" + status + " v48");
                return "";
            }
            String text = new JSONObject(response).optString("text", "").trim();
            text = text.replaceAll("[\r\n\\t]+", " ").replaceAll("  +", " ").trim();
            if (text.length() > 500) text = text.substring(0, 500).trim();
            appendDroidBridgeAndroidProxyLog(
                    "DroidBridge Verity 6 STT fallback recognized chars=" + text.length() + " v48");
            return text;
        } catch (Throwable throwable) {
            appendDroidBridgeAndroidProxyLog(
                    "DroidBridge Verity 6 STT fallback exception="
                            + throwable.getClass().getSimpleName() + " v48");
            return "";
        } finally {
            if (connection != null) {
                try { connection.disconnect(); } catch (Throwable ignored) {}
            }
        }
    }

    private static void writeDroidBridgeMultipartText(
            @NonNull ByteArrayOutputStream output,
            @NonNull String boundary,
            @NonNull String name,
            @NonNull String value
    ) throws java.io.IOException {
        output.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    @NonNull
    private static byte[] buildDroidBridgeVerityPcmWav(@NonNull byte[] pcm) {
        byte[] wav = new byte[44 + pcm.length];
        wav[0] = 'R'; wav[1] = 'I'; wav[2] = 'F'; wav[3] = 'F';
        writeDroidBridgeLeInt(wav, 4, 36 + pcm.length);
        wav[8] = 'W'; wav[9] = 'A'; wav[10] = 'V'; wav[11] = 'E';
        wav[12] = 'f'; wav[13] = 'm'; wav[14] = 't'; wav[15] = ' ';
        writeDroidBridgeLeInt(wav, 16, 16);
        writeDroidBridgeLeShort(wav, 20, 1); // PCM
        writeDroidBridgeLeShort(wav, 22, 1); // mono
        writeDroidBridgeLeInt(wav, 24, 16000);
        writeDroidBridgeLeInt(wav, 28, 16000 * 2);
        writeDroidBridgeLeShort(wav, 32, 2);
        writeDroidBridgeLeShort(wav, 34, 16);
        wav[36] = 'd'; wav[37] = 'a'; wav[38] = 't'; wav[39] = 'a';
        writeDroidBridgeLeInt(wav, 40, pcm.length);
        System.arraycopy(pcm, 0, wav, 44, pcm.length);
        return wav;
    }

    private static void writeDroidBridgeLeInt(@NonNull byte[] out, int offset, int value) {
        out[offset] = (byte) (value & 0xff);
        out[offset + 1] = (byte) ((value >>> 8) & 0xff);
        out[offset + 2] = (byte) ((value >>> 16) & 0xff);
        out[offset + 3] = (byte) ((value >>> 24) & 0xff);
    }

    private static void writeDroidBridgeLeShort(@NonNull byte[] out, int offset, int value) {
        out[offset] = (byte) (value & 0xff);
        out[offset + 1] = (byte) ((value >>> 8) & 0xff);
    }

    private void injectDroidBridgeVerityTranscriptionIntoChat(
            @NonNull String transcription,
            long generation
    ) {
        runOnUiThread(() -> {
            if (generation != droidBridgeVerityMicFallbackGeneration) return;
            if (binding == null || exiting) return;

            /*
             * Submit the complete sequence before the next GLFW event pump:
             * open chat, insert the transcription, then press Enter. Modern
             * Minecraft versions use DroidBridge's stack queue, so all events are
             * processed in order during one glfwPollEvents() call and the ChatScreen
             * never gets a rendered frame. This removes the visible open/close flash.
             */
            CallbackBridge.setInputReady(true);
            CallbackBridge.ensureInputFocus();
            boolean needsChatOpen = CallbackBridge.isGrabbing();
            if (needsChatOpen) {
                CallbackBridge.sendKeyPress(LwjglGlfwKeycode.GLFW_KEY_T, 0, true);
                CallbackBridge.sendKeyPress(LwjglGlfwKeycode.GLFW_KEY_T, 0, false);
            }

            for (int i = 0; i < transcription.length(); i++) {
                char character = transcription.charAt(i);
                if (character == '\r' || character == '\n' || Character.isISOControl(character)) continue;
                CallbackBridge.sendChar(character, 0);
            }

            CallbackBridge.sendKeyPress(LwjglGlfwKeycode.GLFW_KEY_ENTER, 0, true);
            CallbackBridge.sendKeyPress(LwjglGlfwKeycode.GLFW_KEY_ENTER, 0, false);
            appendDroidBridgeAndroidProxyLog(
                    "DroidBridge Verity 6 STT fallback submitted transcription invisibly v54");
        });
    }

    private void stopDroidBridgeAndroidMicProxy() {
        Thread thread;
        synchronized (droidBridgeAndroidMicProxyServerLock) {
            droidBridgeAndroidMicProxyRunning = false;
            thread = droidBridgeAndroidMicServerThread;
            droidBridgeAndroidMicServerThread = null;
            try {
                ServerSocket serverSocket = droidBridgeAndroidMicServerSocket;
                if (serverSocket != null) {
                    serverSocket.close();
                }
            } catch (Throwable ignored) {
            }
            droidBridgeAndroidMicServerSocket = null;
        }
        if (thread != null) {
            try { thread.interrupt(); } catch (Throwable ignored) {}
        }
        stopDroidBridgeAndroidMicRecording();
    }

    private void installGameActivityCrashLogger() {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                LauncherLogManager.append("GameActivity uncaught exception in "
                        + (thread != null ? thread.getName() : "<unknown>")
                        + ": "
                        + throwable);
                if (throwable != null) {
                    for (StackTraceElement element : throwable.getStackTrace()) {
                        LauncherLogManager.append("    at " + element);
                    }
                }
                if (versionId != null) {
                    LauncherLogManager.preserveLatestLogIfEnabled(this, versionId);
                }
            } catch (Throwable ignored) {
            }

            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            } else {
                try {
                    Process.killProcess(Process.myPid());
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private void scheduleMinecraftSurfaceRefresh() {
        MinecraftGLSurface surface = minecraftSurface;
        if (surface == null) return;

        Runnable previous = pendingMinecraftSurfaceRefreshRunnable;
        if (previous != null) {
            surface.removeCallbacks(previous);
            surfaceRefreshHandler.removeCallbacks(previous);
        }

        Runnable refresh = () -> {
            MinecraftGLSurface active = minecraftSurface;
            if (active == null || exiting) return;

            applyGameDisplaySurfaceOptions();
            active.refreshSize();
            active.requestLayout();
            active.invalidate();
        };
        pendingMinecraftSurfaceRefreshRunnable = refresh;

        surface.post(refresh);
        surfaceRefreshHandler.postDelayed(refresh, 120L);
        surfaceRefreshHandler.postDelayed(refresh, 350L);
    }
    private void prepareRendererEnvironmentBeforeBridgeLoad() {
        try {
            Renderers.reload(this);
            RendererInterface selectedRenderer = Renderers.getSelectedRenderer(this);
            RendererInterface renderer = InstanceLaunchSettings.resolveEffectiveRendererForLaunch(this, versionId, instanceSettingsKey);
            if (renderer != selectedRenderer) {
                Logging.i("GameActivity", "Early renderer resolved to "
                        + renderer.getRendererName() + " instead of global default "
                        + selectedRenderer.getRendererName() + " for " + versionId);
            }
            clearEarlyRendererEnvAliases();

            boolean systemVulkan = InstanceLaunchSettings.resolveEffectiveSystemVulkanDriverForLaunch(this, versionId, instanceSettingsKey);
            if (systemVulkan) {
                applyEarlySystemVulkanEnvironment();
            }

            if (DroidBridgeNativeGlfwKgslRenderer.isRenderer(renderer)) {
                boolean modernLwjgl = DroidBridgeMesaSupport.shouldUseNativeGlfwForModernLwjgl(versionId, null);
                boolean nativeGlfwAvailable = DroidBridgeMesaSupport.isNativeGlfwLibraryAvailable(this);

                if (modernLwjgl && nativeGlfwAvailable) {
                    // Minecraft 26.x uses LWJGL 3.4.x, which bypasses the old LWJGLX
                    // context-capability handoff used by the generic GLBridge route.
                    // Use the dedicated NativeGLFW bridge that previously worked for 26.2.
                    applyEarlyNativeGlfwKgslEnvironment(renderer);
                } else {
                    // Older Forge/LWJGL 3.3.x builds retain the GLBridge fallback because
                    // their early-display thread can lose a NativeGLFW-owned EGL context.
                    applyEarlyMesaFreedrenoEnvironment(renderer);
                }

                applyEarlyFreedrenoVsyncEnvironment();
                applyEarlyLegacyOptiFineShaderCompatibility(renderer);
                Logging.i("GameActivity", "Early Freedreno context route="
                        + (modernLwjgl && nativeGlfwAvailable ? "NativeGLFW" : "GLBridge")
                        + " versionId=" + versionId
                        + " nativeGlfwAvailable=" + nativeGlfwAvailable
                        + " DROIDBRIDGE_EGL=" + DroidBridgeMesaSupport.LIB_EGL_MESA);
                return;
            }

            if (DroidBridgeMesaSupport.isMesaZinkTurnipRenderer(renderer)) {
                applyEarlyMesaZinkTurnipEnvironment(renderer, systemVulkan);
                applyEarlyVulkanVsyncEnvironment();
                applyEarlyLegacyOptiFineShaderCompatibility(renderer);
                Logging.i("GameActivity", "Early renderer env prepared for " + renderer.getRendererName()
                        + " rendererId=vulkan_zink DROIDBRIDGE_EGL=" + DroidBridgeMesaSupport.LIB_EGL_MESA);
                return;
            }

            setEarlyEnv("DROIDBRIDGE_RENDERER", resolveBridgeRendererId(renderer));
            for (Map.Entry<String, String> entry : renderer.getRendererEnv().entrySet()) {
                setEarlyEnv(entry.getKey(), entry.getValue());
            }
            if (shouldApplyDriverPluginEnvironment(renderer)) {
                for (Map.Entry<String, String> entry : DriverPluginManager.buildEnvironment(this, renderer, systemVulkan).entrySet()) {
                    setEarlyEnv(entry.getKey(), entry.getValue());
                }
            }

            // MobileGlues reads MG_DIR_PATH from its ELF constructor. Set the
            // launch-readable mirror before CallbackBridge or any SDL/native bridge
            // can dlopen the plugin, not only later in JavaRuntimeBootstrap.
            applyEarlyMobileGluesConfigEnvironment(renderer);

            String egl = sanitizeLibraryName(renderer.getRendererEGL());
            if (isMobileGluesRenderer(renderer)) {
                egl = "libmobileglues.so";
            } else if (egl.isEmpty()) {
                egl = inferDroidBridgeExecEgl(renderer);
            }

            if (!egl.isEmpty()) setEarlyEnv("DROIDBRIDGE_EGL", egl);
            applyEarlyRendererBridgeAliases(renderer, egl);
            configureEarlySdl3WrappedOpenGlEnvironment(renderer);
            applyEarlyVulkanVsyncEnvironment();
            applyEarlyLegacyOptiFineShaderCompatibility(renderer);

            Logging.i("GameActivity", "Early renderer env prepared for " + renderer.getRendererName()
                    + " rendererId=" + resolveBridgeRendererId(renderer)
                    + " DROIDBRIDGE_EGL=" + egl);
        } catch (Throwable throwable) {
            Logging.e("GameActivity", "Failed to prepare early renderer env", throwable);
        }
    }

    private void applyEarlyLegacyOptiFineShaderCompatibility(@NonNull RendererInterface renderer) {
        if (!isLegacyOptiFineVersionId(versionId)) return;

        boolean mesaDesktopRenderer = DroidBridgeNativeGlfwKgslRenderer.isRenderer(renderer)
                || DroidBridgeMesaSupport.isMesaZinkTurnipRenderer(renderer)
                || DroidBridgeMesaSupport.isPureVulkanZinkRenderer(renderer)
                || KopperZinkRenderer.isRenderer(renderer);
        if (!mesaDesktopRenderer) return;

        // This must run before Mesa/libOSMesa/libEGL_mesa is preloaded. Old
        // OptiFine shader sources carry their own GLSL #version directives; a
        // global reported-language override can make legacy compatibility code
        // select behavior intended for newer GLSL versions.
        setEarlyEnv("DROIDBRIDGE_LEGACY_OPTIFINE_SHADER_COMPAT", "1");
        setEarlyEnv("DROIDBRIDGE_EGL_FORCE_COMPAT_PROFILE", "1");
        setEarlyEnv("DROIDBRIDGE_EGL_FORCE_CORE_PROFILE", "");
        setEarlyEnv("MESA_GL_VERSION_OVERRIDE", "4.6COMPAT");
        setEarlyEnv("MESA_GLSL_VERSION_OVERRIDE", "");
        setEarlyEnv("DROIDBRIDGE_PROP_MESA_GL_VERSION_OVERRIDE", "4.6COMPAT");
        setEarlyEnv("DROIDBRIDGE_PROP_MESA_GLSL_VERSION_OVERRIDE", "");
        setEarlyEnv("MESA_NO_ERROR", "0");
        setEarlyEnv("LIBGL_NOERROR", "0");
        setEarlyEnv("mesa_glthread", "false");

        String message = "Legacy OptiFine shader compatibility prepared before Mesa load"
                + " version=" + versionId
                + " renderer=" + renderer.getRendererId()
                + " gl=4.6COMPAT glslOverride=unset validation=on glthread=off";
        Logging.i("GameActivity", message);
        LauncherLogManager.append(message);
    }

    private static boolean isLegacyOptiFineVersionId(@Nullable String value) {
        if (value == null) return false;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.contains("optifine")) return false;

        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?<!\\d)1\\.(\\d+)(?:\\.(\\d+))?")
                .matcher(normalized);
        while (matcher.find()) {
            try {
                if (Integer.parseInt(matcher.group(1)) <= 12) return true;
            } catch (NumberFormatException ignored) {
            }
        }
        return false;
    }

    private void applyEarlyMobileGluesConfigEnvironment(@NonNull RendererInterface renderer) {
        if (!MobileGluesConfigHelper.isMobileGluesRenderer(renderer)) return;
        try {
            File launchDir = MobileGluesConfigHelper.prepareLaunchConfig(this);
            File configFile = MobileGluesConfigHelper.getLaunchConfigFile(this);
            setEarlyEnv("MG_DIR_PATH", launchDir.getAbsolutePath());
            setEarlyEnv("MOBILEGLUES_CONFIG_DIR", launchDir.getAbsolutePath());
            Logging.i("GameActivity", "MobileGlues bridge v12 early config prepared MG_DIR_PATH="
                    + launchDir.getAbsolutePath()
                    + " configExists=" + configFile.isFile()
                    + " configSize=" + (configFile.isFile() ? configFile.length() : 0));
        } catch (Throwable throwable) {
            File fallbackDir = MobileGluesConfigHelper.getConfigDirectory();
            setEarlyEnv("MG_DIR_PATH", fallbackDir.getAbsolutePath());
            setEarlyEnv("MOBILEGLUES_CONFIG_DIR", fallbackDir.getAbsolutePath());
            Logging.e("GameActivity", "MobileGlues bridge v12 early config fallback MG_DIR_PATH="
                    + fallbackDir.getAbsolutePath(), throwable);
        }
    }

    private void configureDroidBridgeRenderSpecAfterBridgeLoad() {
        try {
            Renderers.reload(this);
            RendererInterface renderer = InstanceLaunchSettings.resolveEffectiveRendererForLaunch(this, versionId, instanceSettingsKey);
            boolean mesaRenderSpec = DroidBridgeMesaSupport.isDroidBridgeMesaRenderer(renderer);
            boolean android10ModernWrappedRenderSpec = Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q
                    && DroidBridgeRenderSpec.isWrappedOpenGlRenderer(renderer)
                    && isMinecraft26_2OrNewer(compatibilityVersionId);
            if (!mesaRenderSpec && !android10ModernWrappedRenderSpec) {
                return;
            }

            boolean configured = DroidBridgeRenderSpec.configureForRenderer(this, renderer);
            Logging.i("GameActivity", "Early DroidBridge RenderSpec configured="
                    + configured
                    + " renderer="
                    + renderer.getRendererName()
                    + " rendererId="
                    + renderer.getRendererId()
                    + " wrappedOpenGl="
                    + DroidBridgeRenderSpec.isWrappedOpenGlRenderer(renderer)
                    + " android10ModernWrapped="
                    + android10ModernWrappedRenderSpec);
        } catch (Throwable throwable) {
            Logging.e("GameActivity", "Failed to configure early DroidBridge RenderSpec", throwable);
        }
    }





    private void applyEarlySystemVulkanEnvironment() {
        setEarlyEnv("DROIDBRIDGE_USE_SYSTEM_VULKAN", "1");
        setEarlyEnv("JAVA_LAUNCHER_USE_SYSTEM_VULKAN_DRIVER", "1");
        setEarlyEnv("JAVA_LAUNCHER_VULKAN_DRIVER", "system");
        setEarlyEnv("DROIDBRIDGE_LOAD_TURNIP", "");
        setEarlyEnv("DROIDBRIDGE_LOAD_TURNIP", "");
        setEarlyEnv("DROIDBRIDGE_USE_CUSTOM_TURNIP", "");
        setEarlyEnv("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER", "");
        setEarlyEnv("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER", "");
        setEarlyEnv("VK_ICD_FILENAMES", "");
        setEarlyEnv("VK_DRIVER_FILES", "");
        setEarlyEnv("VK_INSTANCE_LAYERS", "");
        setEarlyEnv("DRIVER_PATH", "");
    }

    private void applyEarlyNativeGlfwKgslEnvironment(@NonNull RendererInterface renderer) {
        File nativeDir = DroidBridgeMesaSupport.resolveMesaNativeDir(this);
        File aliasDir = DroidBridgeMesaSupport.prepareMesaLibraryAliases(this, renderer);
        File eglMesa = new File(nativeDir, DroidBridgeMesaSupport.LIB_EGL_MESA);
        File nativeGlfw = new File(nativeDir, "libdroidbridge_native_glfw_v82.so");
        String mesaSearchPath = nativeDir.getAbsolutePath() + File.pathSeparator + aliasDir.getAbsolutePath();

        for (Map.Entry<String, String> entry : renderer.getRendererEnv().entrySet()) {
            setEarlyEnv(entry.getKey(), entry.getValue());
        }

        setEarlyEnv("DROIDBRIDGE_NATIVE_GLFW", "1");
        setEarlyEnv("DROIDBRIDGE_NATIVE_GLFW_KGSL", "1");
        setEarlyEnv("DROIDBRIDGE_NATIVE_GLFW_DESKTOP_GL", "1");
        setEarlyEnv("DROIDBRIDGE_NATIVE_GLFW_LIB", nativeGlfw.getAbsolutePath());
        setEarlyEnv("DROIDBRIDGE_NATIVE_GLFW_EGL", eglMesa.getAbsolutePath());
        setEarlyEnv("DROIDBRIDGE_NATIVE_GLFW_DRIVER", "kgsl");

        setEarlyEnv("DROIDBRIDGE_RENDERER", "freedreno_kgsl");
        setEarlyEnv("DROIDBRIDGE_RENDERER_MESA_MODE", "freedreno_kgsl");
        setEarlyEnv("DROIDBRIDGE_MESA", "1");
        setEarlyEnv("DROIDBRIDGE_MESA_MODE", "freedreno_kgsl");
        setEarlyEnv("DROIDBRIDGE_MESA_DRIVER", "kgsl");
        setEarlyEnv("DROIDBRIDGE_MESA_NATIVE_DIR", nativeDir.getAbsolutePath());
        setEarlyEnv("DROIDBRIDGE_MESA_ALIAS_DIR", aliasDir.getAbsolutePath());
        setEarlyEnv("DROIDBRIDGE_MESA_NAMESPACE", "1");
        setEarlyEnv("DROIDBRIDGE_MESA_NAMESPACE_PATH", mesaSearchPath);
        setEarlyEnv("DROIDBRIDGE_MESA_EGL", eglMesa.getAbsolutePath());
        setEarlyEnv("DROIDBRIDGE_MESA_EGL_SINGLE_INSTANCE", "1");
        setEarlyEnv("DROIDBRIDGE_MESA_DESKTOP_GL", "1");
        setEarlyEnv("DROIDBRIDGE_NATIVEDIR", nativeDir.getAbsolutePath());

        setEarlyEnv("DROIDBRIDGE_EGL", DroidBridgeMesaSupport.LIB_EGL_MESA);
        setEarlyEnv("DROIDBRIDGE_EGL_LIBRARY", DroidBridgeMesaSupport.LIB_EGL_MESA);
        setEarlyEnv("DROIDBRIDGE_EGL_LIBRARY", DroidBridgeMesaSupport.LIB_EGL_MESA);
        setEarlyEnv("DROIDBRIDGE_RENDERER_LIBRARY", DroidBridgeMesaSupport.LIB_EGL_MESA);
        setEarlyEnv("DROIDBRIDGE_RENDERER_LIBRARY", DroidBridgeMesaSupport.LIB_EGL_MESA);
        setEarlyEnv("LIB_MESA_NAME", DroidBridgeMesaSupport.LIB_EGL_MESA);

        // Match DroidBridge Launcher's known-working Freedreno identity on Adreno 830.
        // Mesa driconf keys can be executable-name sensitive, so do not present this
        // route as DroidBridge while debugging the same KGSL stack.
        setEarlyEnv("MESA_PROCESS_NAME", "DroidBridge");
        setEarlyEnv("MESA_DRICONF_EXECUTABLE_OVERRIDE", "DroidBridge");
        setEarlyEnv("force_gl_vendor", "freedreno/DroidBridge");
        setEarlyEnv("GALLIUM_DRIVER", "");
        setEarlyEnv("MESA_LOADER_DRIVER_OVERRIDE", "kgsl");
        setEarlyEnv("EGL_PLATFORM", "android");
        setEarlyEnv("LIBGL_ES", "2");
        setEarlyEnv("MESA_GL_VERSION_OVERRIDE", "4.6COMPAT");
        setEarlyEnv("MESA_GLSL_VERSION_OVERRIDE", "460");
        setEarlyEnv("LIBGL_MIPMAP", "3");
        setEarlyEnv("LIBGL_NOINTOVLHACK", "1");
        setEarlyEnv("LIBGL_NORMALIZE", "1");
        setEarlyEnv("LIBGL_NOERROR", "1");
        setEarlyEnv("allow_higher_compat_version", "true");
        setEarlyEnv("force_glsl_extensions_warn", "true");
        setEarlyEnv("allow_glsl_extension_directive_midshader", "true");
        setEarlyEnv("DROIDBRIDGE_EGL_FORCE_DESKTOP_GL", "1");
        setEarlyEnv("DROIDBRIDGE_EGL_NO_SYSTEM_FALLBACK", "1");
        setEarlyEnv("MESA_EXTENSION_OVERRIDE", "");
        setEarlyEnv("mesa_glthread", "");
        setEarlyEnv("LIBGL_DRIVERS_PATH", mesaSearchPath);
        setEarlyEnv("EGL_DRIVERS_PATH", mesaSearchPath);
        setEarlyEnv("DRIVER_PATH", nativeDir.getAbsolutePath());

        setEarlyEnv("DROIDBRIDGE_LOAD_TURNIP", "");
        setEarlyEnv("DROIDBRIDGE_LOAD_TURNIP", "");
        setEarlyEnv("DROIDBRIDGE_USE_CUSTOM_TURNIP", "");
        setEarlyEnv("DROIDBRIDGE_USE_SYSTEM_VULKAN", "1");
        setEarlyEnv("VK_ICD_FILENAMES", "");
        setEarlyEnv("VK_DRIVER_FILES", "");
        setEarlyEnv("VK_INSTANCE_LAYERS", "");
        setEarlyEnv("OSMESA_LIB", "");
        setEarlyEnv("DROIDBRIDGE_OSMESA_LIBRARY", "");
        setEarlyEnv("OSMESA_LIBRARY", "");
        setEarlyEnv("LIBGL_OSMESA", "");

        Logging.i("GameActivity", "Native GLFW KGSL early env nativeDir=" + nativeDir.getAbsolutePath()
                + " egl=" + eglMesa.getAbsolutePath()
                + " nativeGlfw=" + nativeGlfw.getAbsolutePath()
                + " nativeGlfwExists=" + nativeGlfw.isFile());
    }

    private void applyEarlyMesaZinkTurnipEnvironment(@NonNull RendererInterface renderer, boolean systemVulkan) {
        File nativeDir = DroidBridgeMesaSupport.resolveMesaNativeDir(this);
        File aliasDir = DroidBridgeMesaSupport.prepareMesaLibraryAliases(this, renderer);
        File eglMesa = new File(nativeDir, DroidBridgeMesaSupport.LIB_EGL_MESA);
        File vulkanFreedreno = new File(nativeDir, "libvulkan_freedreno.so");
        String mesaSearchPath = nativeDir.getAbsolutePath() + File.pathSeparator + aliasDir.getAbsolutePath();

        // Do NOT use DROIDBRIDGE_RENDERER=vulkan_zink here. In this DroidBridge
        // bridge build that routes libdroidbridge_runtime into the legacy OSMesa path
        // and crashes in osm_init_context when libEGL_mesa.so has no OSMesa symbols.
        // Keep libdroidbridge_runtime on the GLES/EGL bridge and let DroidBridge's Mesa
        // variables below select the Zink + Turnip backend.
        setEarlyEnv("DROIDBRIDGE_RENDERER", "opengles3");
        setEarlyEnv("DROIDBRIDGE_RENDERER_MESA_MODE", "zink_turnip");
        setEarlyEnv("DROIDBRIDGE_MESA", "1");
        setEarlyEnv("MESA_PROCESS_NAME", "DroidBridge");
        setEarlyEnv("DROIDBRIDGE_MESA_SAFE_SWAPS", "");
        setEarlyEnv("DROIDBRIDGE_MESA_MODE", "zink_turnip");
        setEarlyEnv("DROIDBRIDGE_MESA_DRIVER", "zink");
        setEarlyEnv("DROIDBRIDGE_MESA_NATIVE_DIR", nativeDir.getAbsolutePath());
        setEarlyEnv("DROIDBRIDGE_MESA_ALIAS_DIR", aliasDir.getAbsolutePath());
        setEarlyEnv("DROIDBRIDGE_MESA_NAMESPACE", "1");
        setEarlyEnv("DROIDBRIDGE_MESA_NAMESPACE_PATH", mesaSearchPath);
        setEarlyEnv("DROIDBRIDGE_MESA_EGL", eglMesa.getAbsolutePath());
        setEarlyEnv("DROIDBRIDGE_MESA_EGL_SINGLE_INSTANCE", "1");
        setEarlyEnv("DROIDBRIDGE_EGL_FORCE_DESKTOP_GL", "1");
        setEarlyEnv("DROIDBRIDGE_EGL_NO_SYSTEM_FALLBACK", "1");
        setEarlyEnv("DROIDBRIDGE_NATIVEDIR", nativeDir.getAbsolutePath());

        setEarlyEnv("DROIDBRIDGE_EGL", DroidBridgeMesaSupport.LIB_EGL_MESA);
        setEarlyEnv("DROIDBRIDGE_EGL_LIBRARY", DroidBridgeMesaSupport.LIB_EGL_MESA);
        setEarlyEnv("DROIDBRIDGE_EGL_LIBRARY", DroidBridgeMesaSupport.LIB_EGL_MESA);
        setEarlyEnv("DROIDBRIDGE_RENDERER_LIBRARY", DroidBridgeMesaSupport.LIB_EGL_MESA);
        setEarlyEnv("DROIDBRIDGE_RENDERER_LIBRARY", DroidBridgeMesaSupport.LIB_EGL_MESA);
        setEarlyEnv("LIB_MESA_NAME", DroidBridgeMesaSupport.LIB_EGL_MESA);

        setEarlyEnv("GALLIUM_DRIVER", "zink");
        setEarlyEnv("MESA_LOADER_DRIVER_OVERRIDE", "zink");
        setEarlyEnv("LIBGL_ES", "");
        setEarlyEnv("DROIDBRIDGE_EGL_FORCE_DESKTOP_GL", "1");
        setEarlyEnv("DROIDBRIDGE_EGL_NO_SYSTEM_FALLBACK", "1");
        setEarlyEnv("MESA_NO_ERROR", "0");
        setEarlyEnv("MESA_GL_VERSION_OVERRIDE", "4.6COMPAT");
        setEarlyEnv("MESA_GLSL_VERSION_OVERRIDE", "460");
        // DroidBridge Mesa/Zink visual-stability profile.
        setEarlyEnv("MESA_EXTENSION_OVERRIDE", "-GL_ARB_buffer_storage -GL_ARB_direct_state_access -GL_ARB_vertex_attrib_binding -GL_EXT_direct_state_access -GL_ARB_multi_draw_indirect -GL_ARB_indirect_parameters -GL_ARB_shader_draw_parameters");
        setEarlyEnv("mesa_glthread", "false");
        setEarlyEnv("MESA_DEBUG", "");
        setEarlyEnv("MESA_VK_WSI_PRESENT_MODE", LauncherPreferences.isVulkanVsyncEnabled(this) ? "fifo" : "immediate");
        setEarlyEnv("LIBGL_DRIVERS_PATH", mesaSearchPath);
        setEarlyEnv("EGL_DRIVERS_PATH", mesaSearchPath);
        setEarlyEnv("DRIVER_PATH", nativeDir.getAbsolutePath());

        // System Vulkan must not be overwritten by the Zink/Turnip defaults.
        // Use custom Turnip only when the user explicitly is not using Android's
        // system Vulkan loader.
        if (systemVulkan) {
            applyEarlySystemVulkanEnvironment();
        } else {
            setEarlyEnv("DROIDBRIDGE_USE_SYSTEM_VULKAN", "0");
            setEarlyEnv("JAVA_LAUNCHER_USE_SYSTEM_VULKAN_DRIVER", "0");
            setEarlyEnv("JAVA_LAUNCHER_VULKAN_DRIVER", "custom");
            setEarlyEnv("DROIDBRIDGE_LOAD_TURNIP", "1");
            setEarlyEnv("DROIDBRIDGE_LOAD_TURNIP", "1");
            setEarlyEnv("DROIDBRIDGE_USE_CUSTOM_TURNIP", "1");
            if (vulkanFreedreno.isFile()) {
                setEarlyEnv("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER", vulkanFreedreno.getAbsolutePath());
                setEarlyEnv("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER", vulkanFreedreno.getAbsolutePath());
            } else {
                setEarlyEnv("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER", "");
                setEarlyEnv("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER", "");
            }
            setEarlyEnv("VK_ICD_FILENAMES", "");
            setEarlyEnv("VK_DRIVER_FILES", "");
        }

        // Hard-clear legacy OSMesa aliases before CallbackBridge/libdroidbridge_runtime loads.
        setEarlyEnv("OSMESA_LIB", "");
        setEarlyEnv("DROIDBRIDGE_OSMESA_LIBRARY", "");
        setEarlyEnv("OSMESA_LIBRARY", "");
        setEarlyEnv("LIBGL_OSMESA", "");
    }

    private void applyEarlyMesaFreedrenoEnvironment(@NonNull RendererInterface renderer) {
        File nativeDir = DroidBridgeMesaSupport.resolveMesaNativeDir(this);
        File aliasDir = DroidBridgeMesaSupport.prepareMesaLibraryAliases(this, renderer);
        File eglMesa = new File(nativeDir, DroidBridgeMesaSupport.LIB_EGL_MESA);
        File vulkanFreedreno = new File(nativeDir, "libvulkan_freedreno.so");
        String mesaSearchPath = nativeDir.getAbsolutePath() + File.pathSeparator + aliasDir.getAbsolutePath();
        setEarlyEnv("DROIDBRIDGE_RENDERER", "freedreno_kgsl");
        setEarlyEnv("DROIDBRIDGE_RENDERER_MESA_MODE", "freedreno_kgsl");
        setEarlyEnv("DROIDBRIDGE_MESA", "1");
        // Match DroidBridge Launcher's known-working Freedreno identity on Adreno 830.
        // Mesa driconf keys can be executable-name sensitive, so do not present this
        // route as DroidBridge while debugging the same KGSL stack.
        setEarlyEnv("MESA_PROCESS_NAME", "DroidBridge");
        setEarlyEnv("MESA_DRICONF_EXECUTABLE_OVERRIDE", "DroidBridge");
        setEarlyEnv("force_gl_vendor", "freedreno/DroidBridge");
        setEarlyEnv("DROIDBRIDGE_MESA_MODE", "freedreno_kgsl");
        setEarlyEnv("DROIDBRIDGE_MESA_DRIVER", "kgsl");
        setEarlyEnv("DROIDBRIDGE_MESA_NATIVE_DIR", nativeDir.getAbsolutePath());
        setEarlyEnv("DROIDBRIDGE_MESA_ALIAS_DIR", aliasDir.getAbsolutePath());
        setEarlyEnv("DROIDBRIDGE_MESA_NAMESPACE", "1");
        setEarlyEnv("DROIDBRIDGE_MESA_NAMESPACE_PATH", mesaSearchPath);
        setEarlyEnv("DROIDBRIDGE_MESA_EGL", eglMesa.getAbsolutePath());
        setEarlyEnv("DROIDBRIDGE_MESA_EGL_SINGLE_INSTANCE", "1");
        setEarlyEnv("DROIDBRIDGE_NATIVEDIR", nativeDir.getAbsolutePath());

        // Hard-disable the experimental NativeGLFW context path. The selected
        // renderer is still named DroidBridge Native Mesa, but this launch must
        // use the same direct KGSL surface/GLBridge route that DroidBridge uses.
        setEarlyEnv("DROIDBRIDGE_NATIVE_GLFW", "");
        setEarlyEnv("DROIDBRIDGE_NATIVE_GLFW_KGSL", "");
        setEarlyEnv("DROIDBRIDGE_NATIVE_GLFW_DESKTOP_GL", "");
        setEarlyEnv("DROIDBRIDGE_NATIVE_GLFW_LIB", "");
        setEarlyEnv("DROIDBRIDGE_NATIVE_GLFW_EGL", "");
        setEarlyEnv("DROIDBRIDGE_NATIVE_GLFW_DRIVER", "");
        setEarlyEnv("SDL_VIDEO_FORCE_EGL", "");
        setEarlyEnv("SDL_EGL_LIBRARY", "");
        setEarlyEnv("SDL_OPENGL_LIBRARY", "");
        setEarlyEnv("SDL_VIDEO_EGL_DRIVER", "");
        setEarlyEnv("SDL_VIDEO_GL_DRIVER", "");
        setEarlyEnv("DROIDBRIDGE_SDL3_MOBILEGLUES_OPENGL", "");
        setEarlyEnv("DROIDBRIDGE_SDL3_MOBILEGLUES_LIBRARY", "");
        setEarlyEnv("DROIDBRIDGE_SDL3_EGL_PROVIDER", "");
        setEarlyEnv("DROIDBRIDGE_SDL3_WRAPPED_OPENGL", "");
        setEarlyEnv("DROIDBRIDGE_SDL3_OPENGL_LIBRARY", "");
        setEarlyEnv("DROIDBRIDGE_SDL3_OPENGL_KIND", "");
        setEarlyEnv("DROIDBRIDGE_SDL3_DISABLE_PERSISTENT_MAPPING", "");
        DroidBridgeSDL3Bootstrap.clearOpenGlCompatibility();

        setEarlyEnv("DROIDBRIDGE_EGL", DroidBridgeMesaSupport.LIB_EGL_MESA);
        setEarlyEnv("DROIDBRIDGE_EGL_LIBRARY", DroidBridgeMesaSupport.LIB_EGL_MESA);
        setEarlyEnv("DROIDBRIDGE_EGL_LIBRARY", DroidBridgeMesaSupport.LIB_EGL_MESA);
        setEarlyEnv("DROIDBRIDGE_RENDERER_LIBRARY", DroidBridgeMesaSupport.LIB_EGL_MESA);
        setEarlyEnv("DROIDBRIDGE_RENDERER_LIBRARY", DroidBridgeMesaSupport.LIB_EGL_MESA);
        setEarlyEnv("LIB_MESA_NAME", DroidBridgeMesaSupport.LIB_EGL_MESA);
        setEarlyEnv("GALLIUM_DRIVER", "");
        setEarlyEnv("MESA_LOADER_DRIVER_OVERRIDE", "kgsl");
        setEarlyEnv("DROIDBRIDGE_PROP_MESA_LOADER_DRIVER_OVERRIDE", "kgsl");
        setEarlyEnv("DROIDBRIDGE_PROP_DEBUG_MESA_LOADER_DRIVER_OVERRIDE", "kgsl");
        setEarlyEnv("DROIDBRIDGE_PROP_GALLIUM_DRIVER", "");
        setEarlyEnv("DROIDBRIDGE_PROP_DEBUG_GALLIUM_DRIVER", "");
        setEarlyEnv("DROIDBRIDGE_PROP_MESA_GL_VERSION_OVERRIDE", "4.6COMPAT");
        setEarlyEnv("DROIDBRIDGE_PROP_MESA_GLSL_VERSION_OVERRIDE", "460");
        setEarlyEnv("DROIDBRIDGE_MESA_GL", "");
        setEarlyEnv("DROIDBRIDGE_MESA_DESKTOP_GL", "");
        setEarlyEnv("DROIDBRIDGE_MESA_SAFE_SWAPS", "");
        setEarlyEnv("DROIDBRIDGE_ZINK_V59_CLEAN_ALIAS", "");
        setEarlyEnv("DROIDBRIDGE_LEGACY_FREEDRENO_ALIAS_V59", "");
        setEarlyEnv("DROIDBRIDGE_MESA_EGL_PLATFORM_DISPLAY", "");
        setEarlyEnv("DROIDBRIDGE_EGL_RECOVER_CURRENT", "");
        setEarlyEnv("DROIDBRIDGE_MESA_EGL_SHIM", "");
        setEarlyEnv("MESA_EXTENSION_OVERRIDE", "");
        setEarlyEnv("mesa_glthread", "");
        setEarlyEnv("EGL_PLATFORM", "android");
        setEarlyEnv("LIBGL_ES", "2");
        setEarlyEnv("MESA_GL_VERSION_OVERRIDE", "4.6COMPAT");
        setEarlyEnv("MESA_GLSL_VERSION_OVERRIDE", "460");
        setEarlyEnv("DROIDBRIDGE_PROP_MESA_GL_VERSION_OVERRIDE", "4.6COMPAT");
        setEarlyEnv("DROIDBRIDGE_PROP_MESA_GLSL_VERSION_OVERRIDE", "460");
        setEarlyEnv("LIBGL_NOERROR", "1");
        setEarlyEnv("LIBGL_NORMALIZE", "1");
        setEarlyEnv("LIBGL_MIPMAP", "3");
        setEarlyEnv("LIBGL_NOINTOVLHACK", "1");
        setEarlyEnv("allow_higher_compat_version", "true");
        setEarlyEnv("force_glsl_extensions_warn", "true");
        setEarlyEnv("allow_glsl_extension_directive_midshader", "true");
        setEarlyEnv("DROIDBRIDGE_MESA_DESKTOP_GL", "1");
        setEarlyEnv("DROIDBRIDGE_EGL_FORCE_DESKTOP_GL", "1");
        setEarlyEnv("DROIDBRIDGE_EGL_FORCE_RGBA8888", "");
        setEarlyEnv("DROIDBRIDGE_EGL_FORCE_RGBX8888", "");
        setEarlyEnv("DROIDBRIDGE_DIRECT_FREEDRENO_OPAQUE_RGBX8888", "");
        // Prefer the native Surface route used by the working DroidBridge KGSL path.
        // TextureView was an older DroidBridge experiment and can change how Mesa
        // owns the window surface/context.
        setEarlyEnv("DROIDBRIDGE_DIRECT_FREEDRENO_NATIVE_SURFACE_V69", "1");
        setEarlyEnv("DROIDBRIDGE_DIRECT_FREEDRENO_TEXTUREVIEW_V70", "");
        setEarlyEnv("DROIDBRIDGE_DIRECT_FREEDRENO_TURNIP_ZINK_V68", "");
        setEarlyEnv("DROIDBRIDGE_ADRENO740_SAFE_GL_EXTENSIONS", "");
        setEarlyEnv("MESA_EXTENSION_OVERRIDE", "");
        setEarlyEnv("DROIDBRIDGE_EGL_NO_SYSTEM_FALLBACK", "1");
        setEarlyEnv("LIBGL_DRIVERS_PATH", mesaSearchPath);
        setEarlyEnv("EGL_DRIVERS_PATH", mesaSearchPath);
        setEarlyEnv("DRIVER_PATH", nativeDir.getAbsolutePath());

        setEarlyEnv("DROIDBRIDGE_LOAD_TURNIP", "");
        setEarlyEnv("DROIDBRIDGE_LOAD_TURNIP", "");
        setEarlyEnv("DROIDBRIDGE_USE_CUSTOM_TURNIP", "");
        setEarlyEnv("DROIDBRIDGE_DIRECT_FREEDRENO_LOAD_TURNIP", "");
        setEarlyEnv("DROIDBRIDGE_USE_SYSTEM_VULKAN", "1");
        setEarlyEnv("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER", "");
        setEarlyEnv("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER", "");
        setEarlyEnv("VK_ICD_FILENAMES", "");
        setEarlyEnv("VK_DRIVER_FILES", "");
        setEarlyEnv("DRIVER_PATH", nativeDir.getAbsolutePath());
    }

    private void applyEarlyRendererBridgeAliases(@NonNull RendererInterface renderer, @NonNull String egl) {
        String rendererLibrary = sanitizeLibraryName(renderer.getRendererLibrary());
        String combined = rendererIdentity(renderer);

        if (isLtwRenderer(renderer)) {
            boolean minecraft26Plus = isMinecraft26OrNewer(compatibilityVersionId);
            setEarlyEnv("DROIDBRIDGE_RENDERER", "opengles3_ltw");
            setEarlyEnv("POJAV_RENDERER", "opengles3_ltw");
            setEarlyEnv("DROIDBRIDGE_EGL", "libltw.so");
            setEarlyEnv("DROIDBRIDGE_EGL_LIBRARY", "libltw.so");
            setEarlyEnv("DROIDBRIDGE_RENDERER_LIBRARY", "libltw.so");
            setEarlyEnv("POJAV_RENDERER_LIBRARY", "libltw.so");
            setEarlyEnv("POJAVEXEC_EGL", "libltw.so");
            setEarlyEnv("POJAVEXEC_EGL_LIBRARY", "libltw.so");
            // Minecraft 26+ uses the newer OpenGL backend. Match LTW's current
            // Android launcher profile there without changing the known-good
            // pre-26 path used by 1.21.x.
            setEarlyEnv("LIBGL_ES", minecraft26Plus ? "2" : "3");
            setEarlyEnv("LIBGL_NOERROR", minecraft26Plus ? "1" : "");
            setEarlyEnv("LTW_NEVER_FLUSH_BUFFERS", minecraft26Plus ? "1" : "0");
            setEarlyEnv("LTW_COHERENT_DYNAMIC_STORAGE", minecraft26Plus ? "1" : "0");
            setEarlyEnv("DROIDBRIDGE_USE_SYSTEM_VULKAN", "1");
            setEarlyEnv("DRIVER_PATH", "");
            setEarlyEnv("VK_ICD_FILENAMES", "");
            setEarlyEnv("VK_DRIVER_FILES", "");
            setEarlyEnv("LIBGL_DRIVERS_PATH", "");
            setEarlyEnv("EGL_DRIVERS_PATH", "");
            setEarlyEnv("OSMESA_LIB", "");
            setEarlyEnv("GALLIUM_DRIVER", "");
            setEarlyEnv("MESA_LOADER_DRIVER_OVERRIDE", "");
            Logging.i("GameActivity", "LTW compatibility profile prepared minecraft26Plus="
                    + minecraft26Plus + " version=" + compatibilityVersionId);
            return;
        }

        if (KopperZinkRenderer.isRenderer(renderer)) {
            setEarlyEnv("DROIDBRIDGE_RENDERER", KopperZinkRenderer.RENDERER_ID);
            setEarlyEnv("POJAV_RENDERER", KopperZinkRenderer.RENDERER_ID);
            setEarlyEnv("DROIDBRIDGE_RENDERER_LIBRARY", KopperZinkRenderer.MAIN_LIBRARY);
            setEarlyEnv("POJAV_RENDERER_LIBRARY", KopperZinkRenderer.MAIN_LIBRARY);
            setEarlyEnv("DROIDBRIDGE_EGL", KopperZinkRenderer.EGL_LIBRARY);
            setEarlyEnv("DROIDBRIDGE_EGL_LIBRARY", KopperZinkRenderer.EGL_LIBRARY);
            setEarlyEnv("POJAVEXEC_EGL", KopperZinkRenderer.EGL_LIBRARY);
            setEarlyEnv("POJAVEXEC_EGL_LIBRARY", KopperZinkRenderer.EGL_LIBRARY);
            setEarlyEnv("LIBGL_ES", "3");
            setEarlyEnv("GALLIUM_DRIVER", "zink");
            setEarlyEnv("MESA_LOADER_DRIVER_OVERRIDE", "zink");
            setEarlyEnv("force_gl_vendor", "Mesa/DroidBridge");
            setEarlyEnv("MESA_ANDROID_NO_KMS_SWRAST", "1");
            setEarlyEnv("MESA_NO_ERROR", "0");
            setEarlyEnv("LIBGL_NOERROR", "0");
            setEarlyEnv("ZINK_DESCRIPTORS", "lazy");
            setEarlyEnv("ZINK_DEBUG", "compact,noreorder");
            setEarlyEnv("mesa_glthread", "false");
            setEarlyEnv("DROIDBRIDGE_EGL_FORCE_RGBX8888", "1");
            // Kopper must stay on TextureView even if the user enabled the global
            // native SurfaceView compatibility toggle for another renderer.
            setEarlyEnv("DROIDBRIDGE_KOPPER_FORCE_TEXTUREVIEW", "1");
            setEarlyEnv("DROIDBRIDGE_KOPPER_FORCE_NAMESPACE_VULKAN", "1");
            File kopperVulkanAliasDir = new File(PathManager.DIR_CACHE, "kopper-vulkan-loader");
            //noinspection ResultOfMethodCallIgnored
            kopperVulkanAliasDir.mkdirs();
            setEarlyEnv("DROIDBRIDGE_KOPPER_VULKAN_ALIAS_DIR", kopperVulkanAliasDir.getAbsolutePath());
            setEarlyEnv("POJAV_ZINK_PREFER_SYSTEM_DRIVER", "");
            setEarlyEnv("LIB_MESA_NAME", "");
            setEarlyEnv("OSMESA_LIB", "");
            setEarlyEnv("DROIDBRIDGE_OSMESA_LIBRARY", "");
            setEarlyEnv("OSMESA_LIBRARY", "");
            setEarlyEnv("LIBGL_OSMESA", "");
            return;
        }

        if (isMobileGluesRenderer(renderer)) {
            // Keep the native bridge on the generic GLES renderer id. The actual
            // EGL provider is still MobileGlues through DROIDBRIDGE_EGL. Using
            // DROIDBRIDGE_RENDERER=mobileglues here can route VulkanMod launches into
            // the wrong libdroidbridge_runtime OpenGL init path and crash at a null PC.
            String bridgeRendererId = renderer.getRendererId();
            if (bridgeRendererId == null || bridgeRendererId.trim().isEmpty() ||
                    bridgeRendererId.toLowerCase(Locale.ROOT).contains("mobileglues")) {
                bridgeRendererId = "opengles3";
            }
            setEarlyEnv("DROIDBRIDGE_RENDERER", bridgeRendererId);
            setEarlyEnv("DROIDBRIDGE_RENDERER_LIBRARY", "libmobileglues.so");
            setEarlyEnv("DROIDBRIDGE_RENDERER_LIBRARY", "libmobileglues.so");
            setEarlyEnv("DROIDBRIDGE_EGL", "libmobileglues.so");
            setEarlyEnv("DROIDBRIDGE_EGL_LIBRARY", "libmobileglues.so");
            setEarlyEnv("DROIDBRIDGE_EGL_LIBRARY", "libmobileglues.so");

            clearMesaAndVulkanDriverEnvironment();
            return;
        }

        if (!rendererLibrary.isEmpty()) {
            setEarlyEnv("DROIDBRIDGE_RENDERER_LIBRARY", rendererLibrary);
            setEarlyEnv("DROIDBRIDGE_RENDERER_LIBRARY", rendererLibrary);
            setEarlyEnv("OSMESA_LIB", rendererLibrary);
        }

        if (!egl.isEmpty()) {
            setEarlyEnv("DROIDBRIDGE_EGL_LIBRARY", egl);
            setEarlyEnv("DROIDBRIDGE_EGL_LIBRARY", egl);
        }

        if (combined.contains("zink") || combined.contains("osmesa")) {
            String osmesa = rendererLibrary.isEmpty() ? "libOSMesa_8.so" : rendererLibrary;
            setEarlyEnv("DROIDBRIDGE_RENDERER", "vulkan_zink");
            setEarlyEnv("DROIDBRIDGE_EGL", osmesa);
            setEarlyEnv("DROIDBRIDGE_EGL_LIBRARY", osmesa);
            setEarlyEnv("DROIDBRIDGE_EGL_LIBRARY", osmesa);
            setEarlyEnv("DROIDBRIDGE_RENDERER_LIBRARY", osmesa);
            setEarlyEnv("DROIDBRIDGE_RENDERER_LIBRARY", osmesa);
            setEarlyEnv("LIB_MESA_NAME", osmesa);
            setEarlyEnv("OSMESA_LIB", osmesa);
            setEarlyEnv("DROIDBRIDGE_OSMESA_LIBRARY", osmesa);
            setEarlyEnv("OSMESA_LIBRARY", osmesa);
            setEarlyEnv("LIBGL_OSMESA", osmesa);
            setEarlyEnv("LIBGL_ES", "3");
            setEarlyEnv("MESA_LOADER_DRIVER_OVERRIDE", "zink");
            setEarlyEnv("GALLIUM_DRIVER", "zink");
        }
    }

    /**
     * Minecraft 26.3 Snapshot 4 moved desktop OpenGL loading and context creation
     * from GLFW/GLBridge into SDL3. Android SDL still creates contexts through EGL,
     * while DroidBridge renderers such as MobileGlues and Krypton expose desktop GL
     * over an Android GLES context.
     *
     * Environment variables alone are not sufficient here: the embedded OpenJDK
     * copy of SDL can initialize before it imports Android process environment
     * hints. Store the exact provider paths in DroidBridgeSDL3Bootstrap as well.
     * After libSDL3.so is loaded, but before SDL_SetMainReady/SDL_Init, the native
     * bridge applies these values with SDL_SetHintWithPriority(OVERRIDE).
     */
    private void configureEarlySdl3WrappedOpenGlEnvironment(
            @NonNull RendererInterface renderer
    ) {
        if (!DroidBridgeSDL3Bootstrap.requiresAndroidSdlPlatform(compatibilityVersionId)) {
            DroidBridgeSDL3Bootstrap.clearOpenGlCompatibility();
            return;
        }

        InstanceLaunchSettings.Settings settings =
                InstanceLaunchSettings.load(this, instanceSettingsKey);
        String graphicsApi = InstanceLaunchSettings.resolveEffectiveGraphicsApiMode(this, settings);
        if (!InstanceLaunchSettings.GRAPHICS_API_OPENGL.equals(graphicsApi)) {
            DroidBridgeSDL3Bootstrap.clearOpenGlCompatibility();
            Logging.i("GameActivity", "SDL3 wrapped OpenGL compatibility not selected graphicsApi="
                    + graphicsApi);
            return;
        }

        String identity = rendererIdentity(renderer).toLowerCase(Locale.ROOT);
        final String rendererKind;
        final String[] fallbackNames;
        if (isMobileGluesRenderer(renderer)) {
            rendererKind = "mobileglues";
            fallbackNames = new String[] {"libmobileglues.so", "libMobileGlues.so"};
        } else if (isLtwRenderer(renderer)) {
            rendererKind = "ltw";
            fallbackNames = new String[] {"libltw.so"};
        } else if (identity.contains("krypton")
                || identity.contains("ng_gl4es")
                || "libng_gl4es.so".equalsIgnoreCase(
                        sanitizeLibraryName(renderer.getRendererLibrary()))) {
            rendererKind = "krypton";
            fallbackNames = new String[] {"libng_gl4es.so"};
        } else {
            DroidBridgeSDL3Bootstrap.clearOpenGlCompatibility();
            Logging.i("GameActivity", "SDL3 wrapped OpenGL compatibility unsupported renderer="
                    + renderer.getRendererName()
                    + " library=" + renderer.getRendererLibrary());
            return;
        }

        // SDL3 must load DroidBridge's EGL translation provider even for LTW.
        // LTW remains the wrapped desktop-GL provider, but using libltw.so as
        // SDL_EGL_LIBRARY makes SDL_GL_LoadLibrary fail on Android. The dedicated
        // shim owns SDL's EGL-facing API and routes GL proc resolution to LTW.
        File glWrapper = resolveRendererLibraryFile(renderer, fallbackNames);

        String glWrapperPath;
        if (glWrapper != null && glWrapper.isFile()) {
            glWrapperPath = glWrapper.getAbsolutePath();
        } else {
            String rendererLibrary = sanitizeLibraryName(renderer.getRendererLibrary());
            glWrapperPath = rendererLibrary.isEmpty()
                    ? fallbackNames[0]
                    : rendererLibrary;
            Logging.i("GameActivity", "SDL3 wrapped OpenGL absolute library path was not resolved; "
                    + "using loaded-library basename=" + glWrapperPath
                    + " searchPaths=" + renderer.getLibrarySearchPaths());
        }

        File eglProvider = new File(
                PathManager.DIR_NATIVE_LIB,
                "libdroidbridge_sdl3_egl.so"
        );
        if (!eglProvider.isFile()) {
            DroidBridgeSDL3Bootstrap.clearOpenGlCompatibility();
            Logging.e("GameActivity", "SDL3 wrapped OpenGL EGL provider is missing: "
                    + eglProvider.getAbsolutePath()
                    + "; rebuild the native droidbridge_sdl3_egl module");
            return;
        }
        String eglProviderPath = eglProvider.getAbsolutePath();

        setEarlyEnv("SDL_VIDEO_FORCE_EGL", "1");
        setEarlyEnv("SDL_EGL_LIBRARY", eglProviderPath);
        setEarlyEnv("SDL_OPENGL_LIBRARY", glWrapperPath);
        setEarlyEnv("SDL_VIDEO_EGL_DRIVER", eglProviderPath);
        setEarlyEnv("SDL_VIDEO_GL_DRIVER", glWrapperPath);
        setEarlyEnv("SDL_VIDEO_EGL_ALLOW_GETDISPLAY_FALLBACK", "1");
        setEarlyEnv("DROIDBRIDGE_SDL3_WRAPPED_OPENGL", "1");
        setEarlyEnv("DROIDBRIDGE_SDL3_OPENGL_LIBRARY", glWrapperPath);
        setEarlyEnv("DROIDBRIDGE_SDL3_OPENGL_KIND", rendererKind);
        // Keep LTW's native persistent-buffer implementation enabled. The 26.3
        // LTW fix is SDL3 context preservation, matching the working MJ-style
        // lifecycle rather than hiding GL_ARB_buffer_storage.
        setEarlyEnv("DROIDBRIDGE_SDL3_DISABLE_PERSISTENT_MAPPING",
                "ltw".equals(rendererKind) ? "0" : "1");

        // Keep the v11/v12 MobileGlues aliases for old source trees and logs.
        if ("mobileglues".equals(rendererKind)) {
            setEarlyEnv("DROIDBRIDGE_SDL3_MOBILEGLUES_OPENGL", "1");
            setEarlyEnv("DROIDBRIDGE_SDL3_MOBILEGLUES_LIBRARY", glWrapperPath);
        }

        DroidBridgeSDL3Bootstrap.configureOpenGlCompatibility(
                eglProviderPath,
                glWrapperPath,
                rendererKind
        );

        Logging.i("GameActivity", "SDL3 wrapped OpenGL compatibility prepared"
                + " kind=" + rendererKind
                + " eglProvider=" + eglProviderPath
                + " glLibrary=" + glWrapperPath
                + " graphicsApi=" + graphicsApi);
        System.err.println("DroidBridgeSDL3EGLSetup: prepared kind=" + rendererKind
                + " provider=" + eglProviderPath
                + " glLibrary=" + glWrapperPath);
    }

    @Nullable
    private File resolveRendererLibraryFile(
            @NonNull RendererInterface renderer,
            @NonNull String... fallbackNames
    ) {
        LinkedHashSet<File> candidates = new LinkedHashSet<>();
        String rawLibrary = renderer.getRendererLibrary();
        if (rawLibrary != null && !rawLibrary.trim().isEmpty()) {
            File raw = new File(rawLibrary.trim());
            if (raw.isAbsolute()) candidates.add(raw);
        }

        LinkedHashSet<String> names = new LinkedHashSet<>();
        String sanitized = sanitizeLibraryName(rawLibrary);
        if (!sanitized.isEmpty()) names.add(sanitized);
        for (String fallback : fallbackNames) {
            if (fallback != null && !fallback.trim().isEmpty()) {
                names.add(new File(fallback.trim()).getName());
            }
        }

        for (File searchPath : renderer.getLibrarySearchPaths()) {
            if (searchPath == null) continue;
            for (String name : names) {
                candidates.add(new File(searchPath, name));
            }
        }

        File appNative = new File(PathManager.DIR_NATIVE_LIB);
        for (String name : names) {
            candidates.add(new File(appNative, name));
        }

        for (File candidate : candidates) {
            try {
                if (candidate.isFile() && candidate.canRead()) {
                    return candidate.getCanonicalFile();
                }
            } catch (Throwable ignored) {
                if (candidate.isFile() && candidate.canRead()) {
                    return candidate.getAbsoluteFile();
                }
            }
        }
        return null;
    }

    private void applyEarlyFreedrenoVsyncEnvironment() {
        int targetFps = resolveEarlyDisplayRefreshRateFps();

        // This flag is a Surface/ANativeWindow capability gate despite its old
        // Zink-specific name. Direct Freedreno must keep it enabled regardless
        // of the separate Vulkan/Zink present-mode preference.
        setEarlyEnv("DROIDBRIDGE_VSYNC_IN_ZINK", "1");
        setEarlyEnv("DROIDBRIDGE_VSYNC_FRAME_PACING", "1");
        setEarlyEnv("DROIDBRIDGE_VSYNC_TARGET_FPS", Integer.toString(targetFps));

        // The launcher setting is explicitly for Vulkan/Zink. Do not let it
        // force FIFO/mailbox state into the direct KGSL OpenGL route.
        setEarlyEnv("DROIDBRIDGE_VULKAN_FORCE_FIFO", "0");
        setEarlyEnv("MESA_VK_WSI_PRESENT_MODE", "");

        Logging.i("GameActivity", "Early Freedreno VSync uses Minecraft options; "
                + "Vulkan/Zink toggle ignored targetFps=" + targetFps);
    }

    private void applyEarlyVulkanVsyncEnvironment() {
        boolean enabled = LauncherPreferences.isVulkanVsyncEnabled(this);
        int targetFps = resolveEarlyDisplayRefreshRateFps();
        setEarlyEnv("DROIDBRIDGE_VSYNC_IN_ZINK", enabled ? "1" : "0");
        setEarlyEnv("DROIDBRIDGE_VULKAN_FORCE_FIFO", enabled ? "1" : "0");
        // Keep this state flag for live swapchain-mode tracking. Never sleep at
        // glfwPollEvents/SDL_PollEvent; the patched LWJGL bridge applies any required
        // supplemental pacing once per successful Vulkan image-acquire cycle.
        setEarlyEnv("DROIDBRIDGE_VSYNC_FRAME_PACING", enabled ? "1" : "0");
        setEarlyEnv("DROIDBRIDGE_VSYNC_TARGET_FPS", Integer.toString(targetFps));
        setEarlyEnv("MESA_VK_WSI_PRESENT_MODE", enabled ? "fifo" : "immediate");
        Logging.i("GameActivity", "Early Vulkan VSync enabled=" + enabled
                + " targetFps=" + targetFps
                + " MESA_VK_WSI_PRESENT_MODE=" + (enabled ? "fifo" : "immediate"));
    }

    private int resolveEarlyDisplayRefreshRateFps() {
        return normalizeDisplayRefreshRateFps(resolveActiveDisplayRefreshRateHz());
    }

    private float resolveActiveDisplayRefreshRateHz() {
        try {
            Display display = resolveActiveGameDisplay();
            if (display == null) return 0f;

            // AYN Thor and AYANEO Pocket DS expose mismatched-refresh dual panels.
            // The Android application refresh rate can describe the high-refresh
            // primary panel even while Minecraft is hosted by the 60 Hz secondary
            // panel. Prefer a physical-panel compatibility answer before Android's
            // application-rate view.
            float panelOverride = DualScreenRefreshCompat.resolvePhysicalPanelRefreshRateHz(this, display);
            if (isSaneEarlyRefreshRate(panelOverride)) {
                return panelOverride;
            }

            // Display.Mode is the physical mode currently selected for this display.
            // Display.getRefreshRate() can instead reflect the app's requested frame
            // rate on modern Android, which is precisely how a 60 Hz panel can end
            // up feeding a false 120/165 Hz pacing target.
            Display.Mode mode = display.getMode();
            if (mode != null && isSaneEarlyRefreshRate(mode.getRefreshRate())) {
                return mode.getRefreshRate();
            }

            float refreshRate = display.getRefreshRate();
            return isSaneEarlyRefreshRate(refreshRate) ? refreshRate : 0f;
        } catch (Throwable throwable) {
            Logging.i("GameActivity", "Unable to read active display refresh rate: " + throwable);
            return 0f;
        }
    }

    @Nullable
    private Display resolveActiveGameDisplay() {
        try {
            if (minecraftSurface != null) {
                Display display = minecraftSurface.getDisplay();
                if (display != null) return display;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (binding != null && binding.getRoot() != null) {
                Display display = binding.getRoot().getDisplay();
                if (display != null) return display;
            }
        } catch (Throwable ignored) {
        }
        try {
            Object service = getSystemService(Context.DISPLAY_SERVICE);
            if (service instanceof DisplayManager) {
                Display display = ((DisplayManager) service).getDisplay(Display.DEFAULT_DISPLAY);
                if (display != null) return display;
            }
        } catch (Throwable ignored) {
        }
        try {
            return getWindowManager().getDefaultDisplay();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int normalizeDisplayRefreshRateFps(float refreshRate) {
        int fps = Math.round(refreshRate);
        if (fps < 30 || fps > 360) fps = 60;
        if (Math.abs(fps - 60) <= 1) return 60;
        if (Math.abs(fps - 90) <= 1) return 90;
        if (Math.abs(fps - 120) <= 2) return 120;
        if (Math.abs(fps - 144) <= 2) return 144;
        if (Math.abs(fps - 165) <= 2) return 165;
        if (Math.abs(fps - 240) <= 3) return 240;
        return fps;
    }

    private static boolean isSaneEarlyRefreshRate(float refreshRate) {
        return refreshRate >= 30f && refreshRate <= 360f;
    }

    private static native void nativeSetVsyncTargetFps(int targetFps);
    private static native void nativeApplyMinecraftVsync(boolean enabled);
    private static native void nativeSetVulkanPresentationPaused(boolean paused);

    public static void setVulkanPresentationPausedFromLifecycle(
            boolean paused,
            @NonNull String reason
    ) {
        try {
            nativeSetVulkanPresentationPaused(paused);
            LauncherLogManager.append(
                    "DroidBridgeVulkanLifecycle: presentationPaused=" + paused
                            + " reason=" + reason);
        } catch (UnsatisfiedLinkError ignored) {
            // Native bridge is not loaded during the early launcher-only phase.
        } catch (Throwable throwable) {
            Logging.i("GameActivity", "Unable to update Vulkan lifecycle gate: " + throwable);
        }
    }

    private void applySustainedPerformanceMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;

        boolean requested = LauncherPreferences.isSustainedPerformanceEnabled(this);
        boolean supported = false;
        try {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            supported = powerManager != null && powerManager.isSustainedPerformanceModeSupported();
            boolean enabled = supported && requested;
            getWindow().setSustainedPerformanceMode(enabled);

            if (lastAppliedSustainedPerformance == null
                    || lastAppliedSustainedPerformance != enabled) {
                Logging.i("GameActivity", "Sustained performance requested=" + requested
                        + " supported=" + supported
                        + " applied=" + enabled);
                lastAppliedSustainedPerformance = enabled;
            }
        } catch (Throwable throwable) {
            if (lastAppliedSustainedPerformance == null || lastAppliedSustainedPerformance) {
                Logging.e("GameActivity", "Unable to apply sustained performance mode", throwable);
                lastAppliedSustainedPerformance = false;
            }
        }
    }

    private void syncMinecraftVsyncFromOptions(boolean force, @NonNull String reason) {
        File options = liveVsyncOptionsFile;
        if (options == null) {
            options = new File(resolveActiveGameDirectoryForRuntime(), "options.txt");
            liveVsyncOptionsFile = options;
        }
        if (!options.isFile()) return;

        long now = SystemClock.uptimeMillis();
        long modified = options.lastModified();
        long length = options.length();
        boolean metadataChanged = modified != lastLiveVsyncOptionsModified
                || length != lastLiveVsyncOptionsLength;
        if (!force && !metadataChanged && now - lastLiveVsyncOptionsReadUptimeMs < 2000L) {
            return;
        }

        lastLiveVsyncOptionsModified = modified;
        lastLiveVsyncOptionsLength = length;
        lastLiveVsyncOptionsReadUptimeMs = now;

        Boolean enabled = readMinecraftVsyncOption(options);
        if (enabled == null) return;
        boolean stateUnchanged = enabled.equals(lastLiveMinecraftVsyncEnabled);
        if (!force && stateUnchanged && liveMinecraftVsyncNativeApplied) return;

        Boolean previous = lastLiveMinecraftVsyncEnabled;
        lastLiveMinecraftVsyncEnabled = enabled;

        // These launch-time clamps previously stayed frozen for the whole game
        // process. Keep them synchronized with Minecraft's live option instead.
        setEarlyEnv("FORCE_VSYNC", enabled ? "true" : "false");
        setEarlyEnv("DROIDBRIDGE_MOBILEGLUES_FORCE_VSYNC", enabled ? "1" : "0");
        // This flag now carries the live Minecraft VSync state into the native
        // swapchain bridge. Vulkan event polling never sleeps or counts frames; do not use
        // the launch-time System Vulkan preference as a proxy because 26.1.x can
        // still be running OpenGL with that preference enabled.
        setEarlyEnv("DROIDBRIDGE_VSYNC_FRAME_PACING", enabled ? "1" : "0");
        setEarlyEnv("DROIDBRIDGE_VULKAN_FORCE_FIFO", enabled ? "1" : "0");
        setEarlyEnv("MESA_VK_WSI_PRESENT_MODE", enabled ? "fifo" : "immediate");
        try {
            nativeApplyMinecraftVsync(enabled);
            liveMinecraftVsyncNativeApplied = true;
        } catch (UnsatisfiedLinkError error) {
            liveMinecraftVsyncNativeApplied = false;
            Logging.i("GameActivity", "Live VSync apply unavailable yet: " + error.getMessage());
        } catch (Throwable throwable) {
            liveMinecraftVsyncNativeApplied = false;
            Logging.e("GameActivity", "Unable to apply live Minecraft VSync", throwable);
        }

        float refreshRate = resolveActiveDisplayRefreshRateHz();
        int targetFps = normalizeDisplayRefreshRateFps(refreshRate);
        setEarlyEnv("DROIDBRIDGE_VSYNC_TARGET_FPS", Integer.toString(targetFps));
        try {
            nativeSetVsyncTargetFps(targetFps);
            liveVsyncTargetNativeApplied = true;
            lastLiveVsyncTargetFps = targetFps;
        } catch (UnsatisfiedLinkError error) {
            liveVsyncTargetNativeApplied = false;
            Logging.i("GameActivity", "Live VSync target unavailable yet: " + error.getMessage());
        } catch (Throwable throwable) {
            liveVsyncTargetNativeApplied = false;
            Logging.i("GameActivity", "Unable to apply live VSync target: " + throwable);
        }
        applyLiveSurfaceFrameRateHint(refreshRate, targetFps, enabled);
        Logging.i("GameActivity", "Live Minecraft VSync " + previous + " -> " + enabled
                + " reason=" + reason
                + " options=" + options.getAbsolutePath());
    }


    @Nullable
    private Boolean readMinecraftVsyncOption(@NonNull File options) {
        try (FileInputStream input = new FileInputStream(options);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                if (output.size() > 1024 * 1024) {
                    Logging.i("GameActivity", "Skipping oversized options.txt while reading VSync");
                    return null;
                }
            }

            String text = output.toString(StandardCharsets.UTF_8.name());
            for (String line : text.split("\\r?\\n")) {
                if (line == null) continue;
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

                int separator = trimmed.indexOf(':');
                if (separator <= 0) separator = trimmed.indexOf('=');
                if (separator <= 0) continue;

                String key = trimmed.substring(0, separator).trim();
                if (!"enableVsync".equalsIgnoreCase(key)) continue;

                String value = trimmed.substring(separator + 1).trim();
                return "true".equalsIgnoreCase(value)
                        || "1".equals(value)
                        || "yes".equalsIgnoreCase(value)
                        || "on".equalsIgnoreCase(value);
            }
        } catch (Throwable throwable) {
            Logging.e("GameActivity", "Unable to read live Minecraft VSync option", throwable);
        }
        return null;
    }

    private void startLiveVsyncRefreshMonitor() {
        if (liveVsyncDisplayListener != null) {
            return;
        }
        Object service = getSystemService(Context.DISPLAY_SERVICE);
        if (!(service instanceof DisplayManager)) {
            Logging.i("GameActivity", "Live VSync: DisplayManager unavailable; using launch-time refresh rate only.");
            return;
        }
        liveVsyncDisplayManager = (DisplayManager) service;
        liveVsyncDisplayListener = new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {
                scheduleLiveVsyncTargetRefresh("displayAdded:" + displayId);
            }

            @Override
            public void onDisplayRemoved(int displayId) {
                scheduleLiveVsyncTargetRefresh("displayRemoved:" + displayId);
            }

            @Override
            public void onDisplayChanged(int displayId) {
                Display active = resolveActiveGameDisplay();
                if (active == null || displayId == active.getDisplayId() || displayId == Display.DEFAULT_DISPLAY) {
                    scheduleLiveVsyncTargetRefresh("displayChanged:" + displayId);
                }
            }
        };
        try {
            liveVsyncDisplayManager.registerDisplayListener(liveVsyncDisplayListener, surfaceRefreshHandler);
            Logging.i("GameActivity", "Live VSync: display refresh monitor registered.");
        } catch (Throwable throwable) {
            Logging.i("GameActivity", "Live VSync: failed to register display listener: " + throwable);
            liveVsyncDisplayListener = null;
            liveVsyncDisplayManager = null;
        }
    }

    private void stopLiveVsyncRefreshMonitor() {
        stopLiveVsyncPulse();
        if (liveVsyncDisplayManager != null && liveVsyncDisplayListener != null) {
            try {
                liveVsyncDisplayManager.unregisterDisplayListener(liveVsyncDisplayListener);
            } catch (Throwable ignored) {
            }
        }
        surfaceRefreshHandler.removeCallbacks(liveVsyncRefreshRunnable);
        liveVsyncDisplayListener = null;
        liveVsyncDisplayManager = null;
    }

    private void startLiveVsyncPulse() {
        if (liveVsyncPulseRunning) return;
        liveVsyncPulseRunning = true;
        surfaceRefreshHandler.removeCallbacks(liveVsyncPulseRunnable);
        surfaceRefreshHandler.post(liveVsyncPulseRunnable);
    }

    private void stopLiveVsyncPulse() {
        liveVsyncPulseRunning = false;
        surfaceRefreshHandler.removeCallbacks(liveVsyncPulseRunnable);
    }

    private void scheduleLiveVsyncTargetRefresh(@NonNull String reason) {
        pendingLiveVsyncRefreshReason = reason;
        surfaceRefreshHandler.removeCallbacks(liveVsyncRefreshRunnable);
        surfaceRefreshHandler.post(liveVsyncRefreshRunnable);
        surfaceRefreshHandler.postDelayed(liveVsyncRefreshRunnable, 250L);
    }

    private void updateLiveVsyncTargetFromDisplay() {
        updateLiveVsyncTargetFromDisplay("manual");
    }

    private void updateLiveVsyncTargetFromDisplay(@NonNull String reason) {
        float refreshRate = resolveActiveDisplayRefreshRateHz();
        int targetFps = normalizeDisplayRefreshRateFps(refreshRate);
        if (targetFps == lastLiveVsyncTargetFps
                && liveVsyncTargetNativeApplied
                && !"manual".equals(reason)
                && !"onResume".equals(reason)) {
            return;
        }
        int oldTarget = lastLiveVsyncTargetFps;
        lastLiveVsyncTargetFps = targetFps;

        setEarlyEnv("DROIDBRIDGE_VSYNC_TARGET_FPS", Integer.toString(targetFps));
        try {
            nativeSetVsyncTargetFps(targetFps);
            liveVsyncTargetNativeApplied = true;
        } catch (UnsatisfiedLinkError error) {
            liveVsyncTargetNativeApplied = false;
            Logging.i("GameActivity", "Live VSync: native target update unavailable yet: " + error.getMessage());
        } catch (Throwable throwable) {
            liveVsyncTargetNativeApplied = false;
            Logging.i("GameActivity", "Live VSync: native target update failed: " + throwable);
        }
        applyLiveSurfaceFrameRateHint(
                refreshRate,
                targetFps,
                Boolean.TRUE.equals(lastLiveMinecraftVsyncEnabled)
        );
        Logging.i("GameActivity", "Live VSync: targetFps " + oldTarget + " -> " + targetFps
                + " refreshRate=" + String.format(Locale.ROOT, "%.3f", refreshRate)
                + " reason=" + reason);
    }

    private void applyLiveSurfaceFrameRateHint(float refreshRate, int targetFps, boolean vsyncEnabled) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        if (!isSaneEarlyRefreshRate(refreshRate)) {
            refreshRate = (float) targetFps;
        }

        // Games should use FRAME_RATE_COMPATIBILITY_DEFAULT (0), not the video-only
        // FIXED_SOURCE mode. Passing 0 Hz when VSync is disabled clears the previous
        // request so Android does not keep trying to match the display to a stale rate.
        float requestedRate = vsyncEnabled ? refreshRate : 0f;
        int compatibility = 0;
        try {
            Object surface = resolveMinecraftRenderSurfaceForFrameRateHint();
            if (surface == null) return;

            try {
                Object valid = surface.getClass().getMethod("isValid").invoke(surface);
                if (valid instanceof Boolean && !((Boolean) valid)) return;
            } catch (NoSuchMethodException ignored) {
                // Older/alternate Surface wrappers may not expose isValid.
            }

            if (surface == lastSurfaceFrameRateHintSurface
                    && Math.abs(lastSurfaceFrameRateHintHz - requestedRate) < 0.01f
                    && lastSurfaceFrameRateHintCompatibility == compatibility) {
                return;
            }

            surface.getClass().getMethod("setFrameRate", float.class, int.class)
                    .invoke(surface, requestedRate, compatibility);
            lastSurfaceFrameRateHintSurface = surface;
            lastSurfaceFrameRateHintHz = requestedRate;
            lastSurfaceFrameRateHintCompatibility = compatibility;
            Logging.i("GameActivity", "Live VSync: Surface frame-rate hint="
                    + String.format(Locale.ROOT, "%.3f", requestedRate)
                    + " compatibility=DEFAULT");
        } catch (NoSuchMethodException ignored) {
            // Surface.setFrameRate is optional; native swap interval remains authoritative.
        } catch (Throwable throwable) {
            Logging.i("GameActivity", "Live VSync: unable to apply Surface frame-rate hint: " + throwable);
        }
    }

    @Nullable
    private Object resolveMinecraftRenderSurfaceForFrameRateHint() {
        MinecraftGLSurface activeSurface = minecraftSurface;
        if (activeSurface == null) {
            return null;
        }

        try {
            Class<?> surfaceViewClass = Class.forName("android.view.SurfaceView");
            Class<?> surfaceHolderClass = Class.forName("android.view.SurfaceHolder");
            for (int i = 0; i < activeSurface.getChildCount(); i++) {
                View child = activeSurface.getChildAt(i);
                if (child != null && surfaceViewClass.isInstance(child)) {
                    Object holder = surfaceViewClass.getMethod("getHolder").invoke(child);
                    if (holder != null) {
                        return surfaceHolderClass.getMethod("getSurface").invoke(holder);
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            java.lang.reflect.Field textureSurfaceField = activeSurface.getClass().getDeclaredField("textureSurface");
            textureSurfaceField.setAccessible(true);
            Object textureSurface = textureSurfaceField.get(activeSurface);
            if (textureSurface != null) {
                return textureSurface;
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    private void clearEarlyRendererEnvAliases() {
        setEarlyEnv("DROIDBRIDGE_RENDERER", "");
        setEarlyEnv("DROIDBRIDGE_LEGACY_OPTIFINE_SHADER_COMPAT", "");
        setEarlyEnv("POJAV_RENDERER", "");
        setEarlyEnv("POJAV_RENDERER_LIBRARY", "");
        setEarlyEnv("POJAVEXEC_EGL", "");
        setEarlyEnv("POJAVEXEC_EGL_LIBRARY", "");
        setEarlyEnv("DROIDBRIDGE_RENDERER_MESA_MODE", "");
        setEarlyEnv("DROIDBRIDGE_RENDERER_LIBRARY", "");
        setEarlyEnv("DROIDBRIDGE_RENDERER_LIBRARY", "");
        setEarlyEnv("DROIDBRIDGE_EGL", "");
        setEarlyEnv("DROIDBRIDGE_EGL_LIBRARY", "");
        setEarlyEnv("DROIDBRIDGE_EGL_LIBRARY", "");
        setEarlyEnv("OSMESA_LIB", "");
        setEarlyEnv("DROIDBRIDGE_OSMESA_LIBRARY", "");
        setEarlyEnv("OSMESA_LIBRARY", "");
        setEarlyEnv("LIBGL_OSMESA", "");
        setEarlyEnv("GALLIUM_DRIVER", "");
        setEarlyEnv("MESA_LOADER_DRIVER_OVERRIDE", "");
        setEarlyEnv("LIBGL_ES", "");
        setEarlyEnv("DROIDBRIDGE_EGL_FORCE_DESKTOP_GL", "");
        setEarlyEnv("DROIDBRIDGE_EGL_FORCE_RGBX8888", "");
        setEarlyEnv("DROIDBRIDGE_EGL_NO_SYSTEM_FALLBACK", "");
        setEarlyEnv("DROIDBRIDGE_USE_SYSTEM_VULKAN", "");
        setEarlyEnv("JAVA_LAUNCHER_USE_SYSTEM_VULKAN_DRIVER", "");
        setEarlyEnv("DROIDBRIDGE_VSYNC_IN_ZINK", "");
        setEarlyEnv("DROIDBRIDGE_VULKAN_FORCE_FIFO", "");
        setEarlyEnv("DROIDBRIDGE_VSYNC_FRAME_PACING", "");
        setEarlyEnv("DROIDBRIDGE_VSYNC_TARGET_FPS", "");
        setEarlyEnv("JAVA_LAUNCHER_VULKAN_DRIVER", "");
        setEarlyEnv("DROIDBRIDGE_LOAD_TURNIP", "");
        setEarlyEnv("DROIDBRIDGE_LOAD_TURNIP", "");
        setEarlyEnv("DROIDBRIDGE_USE_CUSTOM_TURNIP", "");
        setEarlyEnv("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER", "");
        setEarlyEnv("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER", "");
        setEarlyEnv("DRIVER_PATH", "");
        setEarlyEnv("VK_ICD_FILENAMES", "");
        setEarlyEnv("VK_DRIVER_FILES", "");
        setEarlyEnv("LIBGL_DRIVERS_PATH", "");
        setEarlyEnv("EGL_DRIVERS_PATH", "");
        setEarlyEnv("LTW_NEVER_FLUSH_BUFFERS", "");
        setEarlyEnv("LTW_COHERENT_DYNAMIC_STORAGE", "");
        setEarlyEnv("DROIDBRIDGE_MESA", "");
        setEarlyEnv("MESA_PROCESS_NAME", "");
        setEarlyEnv("MESA_DRICONF_EXECUTABLE_OVERRIDE", "");
        setEarlyEnv("force_gl_vendor", "");
        setEarlyEnv("DROIDBRIDGE_MESA_DRIVER", "");
        setEarlyEnv("DROIDBRIDGE_MESA_MODE", "");
        setEarlyEnv("DROIDBRIDGE_MESA_NATIVE_DIR", "");
        setEarlyEnv("DROIDBRIDGE_MESA_ALIAS_DIR", "");
        setEarlyEnv("DROIDBRIDGE_KOPPER_VULKAN_ALIAS_DIR", "");
        setEarlyEnv("DROIDBRIDGE_MESA_NAMESPACE", "");
        setEarlyEnv("DROIDBRIDGE_MESA_NAMESPACE_PATH", "");
        setEarlyEnv("DROIDBRIDGE_MESA_EGL", "");
        setEarlyEnv("DROIDBRIDGE_MESA_EGL_SINGLE_INSTANCE", "");
        setEarlyEnv("DROIDBRIDGE_PROP_MESA_LOADER_DRIVER_OVERRIDE", "");
        setEarlyEnv("DROIDBRIDGE_PROP_DEBUG_MESA_LOADER_DRIVER_OVERRIDE", "");
        setEarlyEnv("DROIDBRIDGE_PROP_GALLIUM_DRIVER", "");
        setEarlyEnv("DROIDBRIDGE_PROP_DEBUG_GALLIUM_DRIVER", "");
        setEarlyEnv("DROIDBRIDGE_PROP_MESA_GL_VERSION_OVERRIDE", "");
        setEarlyEnv("DROIDBRIDGE_PROP_MESA_GLSL_VERSION_OVERRIDE", "");
        setEarlyEnv("DROIDBRIDGE_MESA_GL", "");
        setEarlyEnv("DROIDBRIDGE_MESA_DESKTOP_GL", "");
        setEarlyEnv("DROIDBRIDGE_MESA_SAFE_SWAPS", "");
        setEarlyEnv("DROIDBRIDGE_ZINK_V59_CLEAN_ALIAS", "");
        setEarlyEnv("DROIDBRIDGE_LEGACY_FREEDRENO_ALIAS_V59", "");
        setEarlyEnv("DROIDBRIDGE_MESA_EGL_PLATFORM_DISPLAY", "");
        setEarlyEnv("DROIDBRIDGE_EGL_RECOVER_CURRENT", "");
        setEarlyEnv("DROIDBRIDGE_MESA_EGL_SHIM", "");
        setEarlyEnv("MESA_EXTENSION_OVERRIDE", "");
        setEarlyEnv("mesa_glthread", "");
        setEarlyEnv("DROIDBRIDGE_NATIVE_GLFW", "");
        setEarlyEnv("DROIDBRIDGE_NATIVE_GLFW_KGSL", "");
        setEarlyEnv("DROIDBRIDGE_NATIVE_GLFW_DESKTOP_GL", "");
        setEarlyEnv("DROIDBRIDGE_NATIVE_GLFW_LIB", "");
        setEarlyEnv("DROIDBRIDGE_NATIVE_GLFW_EGL", "");
        setEarlyEnv("DROIDBRIDGE_NATIVE_GLFW_DRIVER", "");
        setEarlyEnv("SDL_VIDEO_FORCE_EGL", "");
        setEarlyEnv("SDL_EGL_LIBRARY", "");
        setEarlyEnv("SDL_OPENGL_LIBRARY", "");
        setEarlyEnv("SDL_VIDEO_EGL_DRIVER", "");
        setEarlyEnv("SDL_VIDEO_GL_DRIVER", "");
        setEarlyEnv("DROIDBRIDGE_SDL3_MOBILEGLUES_OPENGL", "");
        setEarlyEnv("DROIDBRIDGE_SDL3_MOBILEGLUES_LIBRARY", "");
        setEarlyEnv("DROIDBRIDGE_SDL3_EGL_PROVIDER", "");
        setEarlyEnv("DROIDBRIDGE_SDL3_WRAPPED_OPENGL", "");
        setEarlyEnv("DROIDBRIDGE_SDL3_OPENGL_LIBRARY", "");
        setEarlyEnv("DROIDBRIDGE_SDL3_OPENGL_KIND", "");
        setEarlyEnv("DROIDBRIDGE_SDL3_DISABLE_PERSISTENT_MAPPING", "");
        DroidBridgeSDL3Bootstrap.clearOpenGlCompatibility();
    }

    private void setEarlyEnv(@NonNull String key, String value) {
        if (value == null) return;
        try {
            if (value.isEmpty()) {
                Os.unsetenv(key);
            } else {
                Os.setenv(key, value, true);
            }
        } catch (Throwable throwable) {
            Logging.e("GameActivity", "Failed to update early env " + key, throwable);
        }
    }

    @NonNull
    private String inferDroidBridgeExecEgl(@NonNull RendererInterface renderer) {
        String combined = rendererIdentity(renderer);

        if (isLtwRenderer(renderer)) {
            return "libltw.so";
        }

        if (KopperZinkRenderer.isRenderer(renderer)) {
            return KopperZinkRenderer.EGL_LIBRARY;
        }

        if (isMobileGluesRenderer(renderer)) {
            return "libmobileglues.so";
        }

        if (combined.contains("gl4es")
                || combined.contains("opengles")
                || combined.contains("krypton")
                || combined.contains("ng_gl4es")) {
            return "libEGL.so";
        }

        if (combined.contains("osmesa")
                || combined.contains("zink")
                || combined.contains("mesa")
                || combined.contains("virgl")
                || combined.contains("freedreno")
                || combined.contains("panfrost")) {
            return sanitizeLibraryName(renderer.getRendererLibrary());
        }

        return "";
    }

    @NonNull
    private String resolveBridgeRendererId(@NonNull RendererInterface renderer) {
        if (isLtwRenderer(renderer)) return "opengles3_ltw";
        String id = renderer.getRendererId();
        if (id == null || id.trim().isEmpty()) return "opengles3";
        if (isMobileGluesRenderer(renderer) && id.toLowerCase(Locale.ROOT).contains("mobileglues")) {
            return "opengles3";
        }
        return id;
    }

    private boolean shouldApplyDriverPluginEnvironment(@NonNull RendererInterface renderer) {
        // DriverPluginManager exports the global Vulkan-driver state used by
        // VulkanMod too, not just the Zink renderer. Renderer-specific alias
        // blocks below still clear custom Turnip/Zink paths where they do not
        // belong.
        return true;
    }

    private void clearMesaAndVulkanDriverEnvironment() {
        setEarlyEnv("OSMESA_LIB", "");
        setEarlyEnv("DROIDBRIDGE_OSMESA_LIBRARY", "");
        setEarlyEnv("OSMESA_LIBRARY", "");
        setEarlyEnv("LIBGL_OSMESA", "");
        setEarlyEnv("LIB_MESA_NAME", "");

        setEarlyEnv("DROIDBRIDGE_MESA", "");
        setEarlyEnv("DROIDBRIDGE_MESA_MODE", "");
        setEarlyEnv("DROIDBRIDGE_MESA_DRIVER", "");
        setEarlyEnv("DROIDBRIDGE_MESA_EGL", "");
        setEarlyEnv("DROIDBRIDGE_MESA_NATIVE_DIR", "");
        setEarlyEnv("DROIDBRIDGE_MESA_ALIAS_DIR", "");
        setEarlyEnv("DROIDBRIDGE_KOPPER_VULKAN_ALIAS_DIR", "");
        setEarlyEnv("DROIDBRIDGE_MESA_NAMESPACE", "");
        setEarlyEnv("DROIDBRIDGE_MESA_NAMESPACE_PATH", "");
        setEarlyEnv("DROIDBRIDGE_MESA_EGL_SINGLE_INSTANCE", "");
        setEarlyEnv("DROIDBRIDGE_MESA_DESKTOP_GL", "");
        setEarlyEnv("DROIDBRIDGE_MESA_SAFE_SWAPS", "");
        setEarlyEnv("DROIDBRIDGE_EGL_FORCE_DESKTOP_GL", "");
        setEarlyEnv("DROIDBRIDGE_EGL_FORCE_RGBX8888", "");
        setEarlyEnv("DROIDBRIDGE_EGL_NO_SYSTEM_FALLBACK", "");

        setEarlyEnv("MESA_PROCESS_NAME", "");
        setEarlyEnv("MESA_DRICONF_EXECUTABLE_OVERRIDE", "");
        setEarlyEnv("force_gl_vendor", "");
        setEarlyEnv("GALLIUM_DRIVER", "");
        setEarlyEnv("MESA_LOADER_DRIVER_OVERRIDE", "");
        setEarlyEnv("MESA_GL_VERSION_OVERRIDE", "");
        setEarlyEnv("MESA_GLSL_VERSION_OVERRIDE", "");
        setEarlyEnv("MESA_EXTENSION_OVERRIDE", "");
        setEarlyEnv("MESA_NO_ERROR", "");
        setEarlyEnv("MESA_DEBUG", "");
        setEarlyEnv("mesa_glthread", "");
        setEarlyEnv("EGL_PLATFORM", "");
        setEarlyEnv("LIBGL_ES", "");
        setEarlyEnv("LIBGL_DRIVERS_PATH", "");
        setEarlyEnv("EGL_DRIVERS_PATH", "");
        setEarlyEnv("DRIVER_PATH", "");

        setEarlyEnv("DROIDBRIDGE_LOAD_TURNIP", "");
        setEarlyEnv("DROIDBRIDGE_LOAD_TURNIP", "");
        setEarlyEnv("DROIDBRIDGE_USE_CUSTOM_TURNIP", "");
        setEarlyEnv("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER", "");
        setEarlyEnv("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER", "");
        setEarlyEnv("VK_ICD_FILENAMES", "");
        setEarlyEnv("VK_DRIVER_FILES", "");
        setEarlyEnv("VK_INSTANCE_LAYERS", "");
        setEarlyEnv("DROIDBRIDGE_USE_SYSTEM_VULKAN", "1");
        setEarlyEnv("JAVA_LAUNCHER_USE_SYSTEM_VULKAN_DRIVER", "1");
        setEarlyEnv("JAVA_LAUNCHER_VULKAN_DRIVER", "system");
    }

    private boolean isLtwRenderer(@NonNull RendererInterface renderer) {
        String combined = rendererIdentity(renderer);
        return combined.contains("ltw") || combined.contains("libltw.so");
    }

    private boolean isMobileGluesRenderer(@NonNull RendererInterface renderer) {
        String combined = rendererIdentity(renderer);
        return combined.contains("mobileglues")
                || combined.contains("mobile glues")
                || combined.contains("com.fcl.plugin.mobileglues");
    }

    @NonNull
    private String rendererIdentity(@NonNull RendererInterface renderer) {
        return safeLower(renderer.getRendererId()) + " "
                + safeLower(renderer.getRendererName()) + " "
                + safeLower(renderer.getRendererLibrary()) + " "
                + safeLower(renderer.getRendererEGL()) + " "
                + safeLower(renderer.getUniqueIdentifier());
    }

    @NonNull
    private String safeLower(@Nullable String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    @NonNull
    private String sanitizeLibraryName(String library) {
        if (library == null) return "";
        String cleaned = library.trim();
        if (cleaned.isEmpty() || "null".equalsIgnoreCase(cleaned) || "(null)".equalsIgnoreCase(cleaned)) {
            return "";
        }
        return new File(cleaned).getName();
    }

    private void configureInputBridgeForVersion(@NonNull String id) {
        boolean useStackQueue = shouldUseInputStackQueue(id);
        CallbackBridge.setUseInputStackQueue(useStackQueue);
        Logging.i("GameActivity", "Input stack queue for " + id + " = " + useStackQueue);
    }
    private static boolean shouldUseInputStackQueue(@NonNull String versionId) {
        String lower = versionId.toLowerCase(Locale.ROOT).trim();

        // BTA is launched through a patched LWJGL 3 component, but its input layer is
        // still old LWJGL2-style polling. The modern stack-queue path can let the
        // game boot while mouse/key/controller events never reach Mouse/Keyboard.poll().
        if (isBetterThanAdventureVersionId(lower) || isLegacyMinecraftVersionId(lower)) {
            return false;
        }

        if (lower.startsWith("26")
                || lower.contains("snapshot")
                || lower.contains("pre")
                || lower.contains("rc")) {
            return true;
        }

        if (!lower.startsWith("1.")) {
            return true;
        }

        String[] parts = lower.split("[^0-9]+");
        if (parts.length >= 2) {
            try {
                int major = Integer.parseInt(parts[0]);
                int minor = Integer.parseInt(parts[1]);
                return major > 1 || (major == 1 && minor >= 13);
            } catch (NumberFormatException ignored) {
            }
        }

        return false;
    }

    private static boolean isBetterThanAdventureVersionId(@NonNull String lowerVersionId) {
        return lowerVersionId.startsWith("bta")
                || lowerVersionId.contains("betterthanadventure")
                || lowerVersionId.contains("better-than-adventure")
                || lowerVersionId.contains("better_than_adventure");
    }

    private static boolean isLegacyMinecraftVersionId(@NonNull String lowerVersionId) {
        return lowerVersionId.startsWith("b1.")
                || lowerVersionId.startsWith("a1.")
                || lowerVersionId.startsWith("c0.")
                || lowerVersionId.startsWith("rd-");
    }


    private void installTouchControlsOverlay() {
        if (binding == null) return;
        View root = binding.getRoot();
        if (!(root instanceof ViewGroup)) return;
        if (dualScreenController != null && dualScreenController.ownsTouchControls()) return;

        touchControlsOverlay = new TouchControlsOverlay(this);
        touchControlsOverlay.setAppMenuListener(this::openInGameButtonOverlay);
        touchControlsOverlay.setPassthroughTarget(minecraftSurface);
        touchControlsOverlay.setMinecraftOptionsFile(
                new File(resolveActiveGameDirectoryForRuntime(), "options.txt"));
        touchControlsOverlay.setInputViewportTarget(shouldUseCenteredPortraitGameViewport()
                ? minecraftSurface : null);
        touchControlsOverlay.loadSelectedLayout();
        touchControlsOverlay.applyVirtualMouseLaunchSessionState();
        touchControlsOverlay.setControlsVisible(ControlsPreferences.isTouchControlsEnabled(this)
                && (dualScreenController == null || !dualScreenController.ownsTouchControls()));
        touchControlsOverlay.setAlpha(1f);

        ((ViewGroup) root).addView(
                touchControlsOverlay,
                new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                )
        );

        // Reapply visibility semantics after live single/dual-screen transitions. Recording never
        // changes this live overlay's alpha; clean capture happens at the encoder source instead.
        if (recordingTouchControlsHidden || GameRecordingService.isRecording()) {
            applyRecordingTouchControlVisibility(true);
        }
    }

    private void removeTouchControlsOverlay() {
        if (touchControlsOverlay == null) return;
        ViewGroup parent = (ViewGroup) touchControlsOverlay.getParent();
        if (parent != null) parent.removeView(touchControlsOverlay);
        touchControlsOverlay = null;
    }

    private void refreshTouchControlsOverlay() {
        if (touchControlsOverlay == null) return;
        touchControlsOverlay.setInputViewportTarget(shouldUseCenteredPortraitGameViewport()
                ? minecraftSurface : null);
        touchControlsOverlay.setMinecraftOptionsFile(
                new File(resolveActiveGameDirectoryForRuntime(), "options.txt"));
        touchControlsOverlay.loadSelectedLayout();
        touchControlsOverlay.applyVirtualMouseLaunchSessionState();
        touchControlsOverlay.setControlsVisible(ControlsPreferences.isTouchControlsEnabled(this)
                && (dualScreenController == null || !dualScreenController.ownsTouchControls()));
        touchControlsOverlay.setAlpha(1f);
        touchControlsOverlay.bringToFront();
        if (binding != null && binding.layoutLogOverlay.getVisibility() == View.VISIBLE) {
            binding.layoutLogOverlay.bringToFront();
        }

        if (floatingGameSettingsOverlayController != null) {
            floatingGameSettingsOverlayController.bringToFront();
        } else if (binding != null && binding.buttonGameSettings.getVisibility() == View.VISIBLE) {
            binding.buttonGameSettings.bringToFront();
        }
    }

    private void installCursorOverlay() {
        refreshCursorOverlayTarget();
    }

    /**
     * Keep the launcher software mouse/cursor on the same display as the real
     * Minecraft surface. In dual-screen mode the phone/tablet screen is only the
     * HUD/touch deck, so controller/virtual mouse cursor drawing belongs on the
     * external Presentation root instead of the bottom controls view.
     */
    private void refreshCursorOverlayTarget() {
        if (binding == null) return;

        ViewGroup target = resolveCursorOverlayParent();
        if (target == null) return;

        if (gameCursorOverlay != null && gameCursorOverlay.getParent() == target) {
            gameCursorOverlay.setViewportTarget(minecraftSurface);
            target.post(() -> initializeBridgePointerForCursorTarget(
                    resolveCursorGeometryTarget(target)));
            return;
        }

        removeCursorOverlay();

        gameCursorOverlay = new GameCursorOverlay(target.getContext());
        gameCursorOverlay.setViewportTarget(minecraftSurface);
        target.addView(
                gameCursorOverlay,
                new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                )
        );
        gameCursorOverlay.bringToFront();
        target.post(() -> initializeBridgePointerForCursorTarget(
                resolveCursorGeometryTarget(target)));
    }

    @NonNull
    private View resolveCursorGeometryTarget(@NonNull ViewGroup fallback) {
        MinecraftGLSurface surface = minecraftSurface;
        if (surface != null && surface.getWidth() > 1 && surface.getHeight() > 1) {
            try {
                Display surfaceDisplay = surface.getDisplay();
                Display fallbackDisplay = fallback.getDisplay();
                if (surfaceDisplay == null || fallbackDisplay == null
                        || surfaceDisplay.getDisplayId() == fallbackDisplay.getDisplayId()) {
                    return surface;
                }
            } catch (Throwable ignored) {
            }
        }
        return fallback;
    }

    @Nullable
    private ViewGroup resolveCursorOverlayParent() {
        if (dualScreenController != null
                && dualScreenController.isExternalGameModeActive()
                && minecraftSurface != null
                && minecraftSurface.getParent() instanceof ViewGroup) {
            return (ViewGroup) minecraftSurface.getParent();
        }

        View root = binding == null ? null : binding.getRoot();
        return root instanceof ViewGroup ? (ViewGroup) root : null;
    }

    private void initializeBridgePointerForCursorTarget(@NonNull View target) {
        int targetWidth = Math.max(1, target.getWidth());
        int targetHeight = Math.max(1, target.getHeight());
        int resolutionScale = LauncherPreferences.getGameResolutionScalePercent(this);

        GameResolutionSettings.ResolvedResolution renderResolution =
                GameResolutionSettings.resolveRenderResolution(
                        this,
                        targetWidth,
                        targetHeight,
                        resolutionScale
                );
        GameResolutionSettings.DisplayBounds displayBounds =
                GameResolutionSettings.resolveDisplayBounds(this, targetWidth, targetHeight);

        // Display swaps can move the software cursor overlay between differently sized
        // roots. Re-resolve both coordinate spaces instead of replacing the selected
        // custom/4:3 framebuffer with the full Android display dimensions.
        CallbackBridge.windowWidth = renderResolution.width;
        CallbackBridge.windowHeight = renderResolution.height;
        CallbackBridge.physicalWidth = displayBounds.width;
        CallbackBridge.physicalHeight = displayBounds.height;

        CallbackBridge.setInputReady(true);
        CallbackBridge.ensureInputFocus();

        int cursorCoordinateWidth = DroidBridgeSDL3Bootstrap.isRequested()
                ? DroidBridgeSDL3Bootstrap.getSdlCursorCoordinateWidth()
                : renderResolution.width;
        int cursorCoordinateHeight = DroidBridgeSDL3Bootstrap.isRequested()
                ? DroidBridgeSDL3Bootstrap.getSdlCursorCoordinateHeight()
                : renderResolution.height;
        boolean outside = CallbackBridge.mouseX < 0f
                || CallbackBridge.mouseY < 0f
                || CallbackBridge.mouseX >= cursorCoordinateWidth
                || CallbackBridge.mouseY >= cursorCoordinateHeight;
        if (outside || (CallbackBridge.mouseX == 0f && CallbackBridge.mouseY == 0f)) {
            CallbackBridge.mouseX = cursorCoordinateWidth / 2f;
            CallbackBridge.mouseY = cursorCoordinateHeight / 2f;
        }
        CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
    }

    private void removeCursorOverlay() {
        if (gameCursorOverlay == null) return;
        ViewParent parent = gameCursorOverlay.getParent();
        gameCursorOverlay.removeSelf();
        if (parent instanceof ViewGroup) {
            ((ViewGroup) parent).removeView(gameCursorOverlay);
        }
        gameCursorOverlay = null;
    }


    private void configureLogOverlay() {
        if (binding == null) return;

        stopLogOverlayTicker();

        boolean showOverlay = LauncherPreferences.isShowGameLogOverlay(this);
        binding.layoutLogOverlay.setVisibility(showOverlay ? View.VISIBLE : View.GONE);
        binding.layoutLogOverlay.setEnabled(false);
        binding.scrollLogOverlay.setEnabled(false);
        binding.textLogOverlay.setEnabled(false);

        if (!showOverlay) return;

        binding.layoutLogOverlay.bringToFront();
        binding.layoutLogOverlay.setElevation(9000f);
        binding.layoutLogOverlay.setTranslationZ(9000f);
        binding.layoutLogOverlay.setAlpha(1f);

        View root = binding.getRoot();
        root.post(() -> {
            if (binding == null) return;
            int maxWidthPx = dpToPx(420);
            int preferredWidthPx = Math.max(dpToPx(260), (int) (root.getWidth() * 0.38f));
            ViewGroup.LayoutParams params = binding.layoutLogOverlay.getLayoutParams();
            params.width = Math.min(maxWidthPx, preferredWidthPx);
            binding.layoutLogOverlay.setLayoutParams(params);
            binding.layoutLogOverlay.bringToFront();
            if (binding.buttonGameSettings.getVisibility() == View.VISIBLE) {
                binding.buttonGameSettings.bringToFront();
            }
        });

        if (binding.textLogOverlay.length() == 0) {
            binding.textLogOverlay.setText("Waiting for log output...");
        }

        logOverlayHandler = new Handler(Looper.getMainLooper());
        logOverlayRunnable = new Runnable() {
            @Override
            public void run() {
                refreshLogOverlay();
                if (!exiting && binding != null && logOverlayHandler != null) {
                    logOverlayHandler.postDelayed(this, 500L);
                }
            }
        };
        logOverlayHandler.post(logOverlayRunnable);
    }

    private void stopLogOverlayTicker() {
        if (logOverlayHandler != null && logOverlayRunnable != null) {
            logOverlayHandler.removeCallbacks(logOverlayRunnable);
        }
        logOverlayRunnable = null;
        logOverlayHandler = null;
    }

    private void configureInGameSettingsButton() {
        if (binding == null) return;

        if (floatingGameSettingsOverlayController == null) {
            floatingGameSettingsOverlayController = new FloatingGameSettingsOverlayController(
                    this,
                    binding.buttonGameSettings
            );
            floatingGameSettingsOverlayController.attach();
        }

        binding.buttonGameSettings.setOnClickListener(view -> openInGameButtonOverlay());
        floatingGameSettingsOverlayController.refreshFromPreferences();
        floatingGameSettingsOverlayController.setSuppressed(
                dualScreenController != null && dualScreenController.ownsTouchControls());
        floatingGameSettingsOverlayController.bringToFront();
    }

    private void configureDualScreenController() {
        DualScreenSwapActionBus.setListener(() -> runOnUiThread(this::handleDualScreenSwapRequest));
        if (binding == null || !(binding.getRoot() instanceof ViewGroup)) return;

        // Dual-screen support is opt-in. Do not automatically enable it merely
        // because Android exposes a secondary display (notably the AYN Thor upper panel).
        boolean dualScreenSupport = LauncherPreferences.isDualScreenSupportEnabled(this);

        if (!dualScreenSupport) {
            // Keep the Fabric HUD bridge explicitly inactive even after a prior crash
            // or force-close left its last control file behind.
            DualScreenController.resetPersistedControlState(
                    this,
                    resolveDualScreenHudStateFile()
            );
            dualScreenController = null;
            return;
        }

        dualScreenController = new DualScreenController(
                this,
                resolveDualScreenHudStateFile(),
                () -> runOnUiThread(this::openInGameLauncherMenuFromBackShortcut),
                binding.minecraftSurface,
                (ViewGroup) binding.getRoot(),
                (surface, alreadyRunning) -> runOnUiThread(() -> {
                    minecraftSurface = surface;
                    applyGameSurfaceLayoutMode(surface);
                    configureActiveMinecraftSurface(surface);
                    if (alreadyRunning) {
                        surface.start(true);
                    }
                    boolean dualActive = dualScreenController != null
                            && dualScreenController.ownsTouchControls();
                    if (dualActive) {
                        removeTouchControlsOverlay();
                    } else if (touchControlsOverlay == null) {
                        installTouchControlsOverlay();
                    } else {
                        touchControlsOverlay.setPassthroughTarget(surface);
                        touchControlsOverlay.setInputViewportTarget(shouldUseCenteredPortraitGameViewport()
                                ? surface : null);
                        touchControlsOverlay.setDualScreenBottomHudHotbarMode(false);
                        touchControlsOverlay.setControlsVisible(ControlsPreferences.isTouchControlsEnabled(this));
                        touchControlsOverlay.setAlpha(1f);
                    }
                    if (floatingGameSettingsOverlayController != null) {
                        floatingGameSettingsOverlayController.setSuppressed(dualActive);
                    }
                    refreshCursorOverlayTarget();
                    scheduleMinecraftSurfaceRefresh();
                    scheduleLiveVsyncTargetRefresh("activeSurfaceChanged");
                })
        );

        // Initial dual-screen direction is applied once by restoreOrEnableInitialMode()
        // after this controller is created. Posting a second restore here races the
        // first Presentation setup and previously helped overwrite the saved swap.
    }

    /**
     * Keeps the live dual-screen controller aligned with Launcher Settings. This matters
     * when the settings Activity changes the master toggle or swap direction while the
     * game Activity is paused: the old controller previously resumed with stale fields
     * and could immediately overwrite the setting the user had just chosen.
     */
    private void reconcileDualScreenRuntimeFromPreferences() {
        boolean enabled = LauncherPreferences.isDualScreenSupportEnabled(this);

        if (!enabled) {
            if (dualScreenController != null) {
                dualScreenController.disableExternalGameMode(launchStarted);
                dualScreenController.release();
                dualScreenController = null;
            }
            if (touchControlsOverlay == null) {
                installTouchControlsOverlay();
            }
            if (floatingGameSettingsOverlayController != null) {
                floatingGameSettingsOverlayController.setSuppressed(false);
            }
            refreshCursorOverlayTarget();
            return;
        }

        if (dualScreenController == null) {
            configureDualScreenController();
            if (dualScreenController != null) {
                dualScreenController.restoreOrEnableInitialMode(launchStarted);
            }
        } else {
            dualScreenController.onResume(launchStarted);
        }
        if (floatingGameSettingsOverlayController != null) {
            floatingGameSettingsOverlayController.setSuppressed(
                    dualScreenController != null && dualScreenController.ownsTouchControls());
        }
        refreshCursorOverlayTarget();
    }

    private void handleDualScreenSwapRequest() {
        if (!LauncherPreferences.isDualScreenSupportEnabled(this)) {
            Toast.makeText(this, "Dual-screen support is disabled in Launcher Settings.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (dualScreenController == null) {
            configureDualScreenController();
        }
        if (dualScreenController != null) {
            dualScreenController.toggleScreenSwap(launchStarted);
            refreshCursorOverlayTarget();
        }
    }

    /**
     * "Portrait (Centered Game View)" keeps GameActivity in portrait while constraining
     * only the Activity-owned Minecraft surface to a centered landscape-aspect rectangle.
     * The render child itself remains MATCH_PARENT inside MinecraftGLSurface, so both the
     * TextureView and native SurfaceView paths are sized correctly before rendering starts.
     * External dual-screen Presentation surfaces intentionally remain fullscreen.
     */
    private void applyGameSurfaceLayoutMode(@Nullable MinecraftGLSurface surface) {
        if (surface == null || binding == null || surface != binding.minecraftSurface) return;
        if (!(surface.getLayoutParams() instanceof FrameLayout.LayoutParams)) return;

        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) surface.getLayoutParams();
        boolean centeredPortrait = LauncherPreferences.APP_ORIENTATION_PORTRAIT_CENTERED_GAME.equals(
                LauncherPreferences.getGameOrientationMode(this));
        boolean dualScreenActive = dualScreenController != null && dualScreenController.isShowing();

        int desiredWidth = ViewGroup.LayoutParams.MATCH_PARENT;
        int desiredHeight = ViewGroup.LayoutParams.MATCH_PARENT;
        int desiredGravity = Gravity.TOP | Gravity.START;

        if (centeredPortrait && !dualScreenActive) {
            // Use the root's actual post-inset content width. Using raw DisplayMetrics
            // made the surface height come from the full panel while MATCH_PARENT width
            // was measured inside notch padding, visibly squeezing the centered view.
            View root = binding.getRoot();
            int portraitWidth = 0;
            if (root != null && root.getWidth() > 1) {
                portraitWidth = Math.max(1, root.getWidth()
                        - root.getPaddingLeft() - root.getPaddingRight());
            }
            if (portraitWidth <= 1) {
                DisplayMetrics metrics = getResources().getDisplayMetrics();
                portraitWidth = Math.max(1, Math.min(metrics.widthPixels, metrics.heightPixels));
            }
            float landscapeAspect = resolveCenteredPortraitGameAspect();
            desiredHeight = Math.max(1, Math.round(portraitWidth / landscapeAspect));
            desiredGravity = Gravity.CENTER;
        }

        if (params.width != desiredWidth
                || params.height != desiredHeight
                || params.gravity != desiredGravity
                || params.leftMargin != 0
                || params.topMargin != 0
                || params.rightMargin != 0
                || params.bottomMargin != 0) {
            params.width = desiredWidth;
            params.height = desiredHeight;
            params.gravity = desiredGravity;
            params.leftMargin = 0;
            params.topMargin = 0;
            params.rightMargin = 0;
            params.bottomMargin = 0;
            surface.setLayoutParams(params);
        }

        if (touchControlsOverlay != null) {
            touchControlsOverlay.setInputViewportTarget(centeredPortrait && !dualScreenActive
                    ? surface : null);
        }
        if (gameCursorOverlay != null) {
            gameCursorOverlay.setViewportTarget(surface);
        }
    }

    private boolean shouldUseCenteredPortraitGameViewport() {
        return LauncherPreferences.APP_ORIENTATION_PORTRAIT_CENTERED_GAME.equals(
                LauncherPreferences.getGameOrientationMode(this))
                && (dualScreenController == null || !dualScreenController.isShowing());
    }

    private float resolveCenteredPortraitGameAspect() {
        final float fourThree = 4f / 3f;
        final float sixteenNine = 16f / 9f;
        try {
            GameResolutionSettings.Profile profile = GameResolutionSettings.getRuntimeProfileOverride();
            if (profile == null) profile = GameResolutionSettings.getProfile(this);

            if (GameResolutionSettings.MODE_BEST_4_3.equals(profile.mode)
                    || GameResolutionSettings.MODE_MCSX.equals(profile.mode)) {
                return fourThree;
            }
            if (GameResolutionSettings.MODE_1920_1080.equals(profile.mode)
                    || GameResolutionSettings.MODE_NATIVE.equals(profile.mode)) {
                // Native phone aspect ratios such as the S22's ~19.5:9 make a
                // portrait-centered game strip unnecessarily short. Native centered
                // portrait intentionally uses the conventional landscape 16:9 view.
                return sixteenNine;
            }
            if (GameResolutionSettings.MODE_CUSTOM.equals(profile.mode)) {
                int longSide = Math.max(profile.customWidth, profile.customHeight);
                int shortSide = Math.max(1, Math.min(profile.customWidth, profile.customHeight));
                float aspect = longSide / (float) shortSide;
                if (!Float.isNaN(aspect) && !Float.isInfinite(aspect)) {
                    // Keep the portrait-centered view useful instead of becoming a
                    // thin ultrawide strip. 4:3 through 16:9 still honors common custom
                    // landscape layouts without visible geometric squeezing.
                    return Math.max(fourThree, Math.min(sixteenNine, aspect));
                }
            }
        } catch (Throwable ignored) {
        }
        return sixteenNine;
    }

    private void configureActiveMinecraftSurface(@Nullable MinecraftGLSurface surface) {
        if (surface == null) return;

        InputEventDiagnosticLogger.installUnhandledKeyHook(
                getWindow() != null ? getWindow().getDecorView() : null,
                "GameActivity.decor"
        );
        InputEventDiagnosticLogger.installUnhandledKeyHook(
                surface,
                "MinecraftGLSurface"
        );
        InputEventDiagnosticLogger.mark(
                "GameActivity configured Minecraft surface"
                        + " surfaceFocus=" + surface.hasFocus()
                        + " surfaceWindowFocus=" + surface.hasWindowFocus()
                        + " currentFocus=" + (getCurrentFocus() == null
                        ? "<null>" : getCurrentFocus().getClass().getName())
        );

        surface.setSpecialKeyEventListener(event -> {
            // This listener runs inside MinecraftGLSurface before its physical-keyboard ->
            // GLFW/SDL injection. Keep the launcher shortcut here as well as at Activity level
            // so focused SurfaceView/TextureView paths cannot bypass it.
            if (handleInGameMenuKeyboardShortcut(event)) {
                return true;
            }
            return routeAynRearButtonEvent(event, "focused-surface");
        });
        surface.setAndroidVirtualMouseUiRouter(this::routeAndroidVirtualMouseUiEvent);
        if (GamepadButton.isAynOdinBuild()) {
            LauncherLogManager.append(
                    "AYN rear-button bridge active: Activity + focused Minecraft surface, "
                            + "M1=KEYCODE_BUTTON_Z/scan309, M2=KEYCODE_BUTTON_C/scan306"
            );
        }

        GameImeViewportController.registerMinecraftSurface(surface);
        surface.setMinecraftOptionsFile(
                new File(resolveActiveGameDirectoryForRuntime(), "options.txt"));
        surface.setOnRenderingStartedListener(() -> runOnUiThread(() -> {
            gameRenderingStarted = true;
            CallbackBridge.setInputReady(true);
            CallbackBridge.ensureInputFocus();
            hideLaunchStatusOverlay();
        }));
        surface.setSurfaceReadyListener(() -> {
            startLaunchOnce();
            scheduleLiveVsyncTargetRefresh("surfaceReady");
        });
        surface.setFitsSystemWindows(false);
        surface.setClipToOutline(false);
    }

    private boolean routeAndroidVirtualMouseUiEvent(
            int action,
            float screenX,
            float screenY,
            int actionButton,
            int buttonState,
            long eventTime
    ) {
        if (!LauncherPreferences.isAndroidVirtualPhysicalMouse(this)) return false;

        if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
                && eventTime > 0L
                && eventTime <= androidVirtualMouseUiReleaseSuppressUntilMs) {
            return true;
        }

        boolean primaryEvent = actionButton == MotionEvent.BUTTON_PRIMARY
                || (buttonState & MotionEvent.BUTTON_PRIMARY) != 0;

        if (action == MotionEvent.ACTION_DOWN && !primaryEvent) {
            // Captured mouse drivers do not all populate actionButton on ACTION_DOWN.
            // buttonState==0 is therefore allowed only when a visible DroidBridge target
            // is actually under the centered cursor.
            primaryEvent = actionButton == 0 && buttonState == 0;
        }

        FloatingGameSettingsOverlayController floating = floatingGameSettingsOverlayController;
        boolean cogGestureEligible = action != MotionEvent.ACTION_DOWN || primaryEvent;
        if (floating != null
                && cogGestureEligible
                && floating.dispatchCapturedPhysicalMouseGesture(action, screenX, screenY)) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                androidVirtualMouseUiReleaseSuppressUntilMs = Math.max(
                        androidVirtualMouseUiReleaseSuppressUntilMs,
                        (eventTime > 0L ? eventTime : android.os.SystemClock.uptimeMillis()) + 120L
                );
            }
            return true;
        }

        TouchControlsOverlay overlay = touchControlsOverlay;
        boolean overlayConsumed = false;
        if (overlay != null && primaryEvent) {
            overlayConsumed = overlay.dispatchCapturedPhysicalMouseToControl(
                    action, screenX, screenY, eventTime);
        } else if (overlay != null
                && (action == MotionEvent.ACTION_MOVE
                || action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_CANCEL)) {
            // Continue an already-started control press even though buttonState can be
            // zero on ACTION_UP and on some captured-pointer MOVE events.
            overlayConsumed = overlay.dispatchCapturedPhysicalMouseToControl(
                    action, screenX, screenY, eventTime);
        }
        if (overlayConsumed
                && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)) {
            androidVirtualMouseUiReleaseSuppressUntilMs = Math.max(
                    androidVirtualMouseUiReleaseSuppressUntilMs,
                    (eventTime > 0L ? eventTime : android.os.SystemClock.uptimeMillis()) + 120L
            );
        }
        return overlayConsumed;
    }

    private static boolean isScreenPointInsideVisibleView(
            @Nullable View view,
            float screenX,
            float screenY
    ) {
        if (view == null || view.getVisibility() != View.VISIBLE || !view.isShown()
                || view.getWidth() <= 0 || view.getHeight() <= 0) {
            return false;
        }
        int[] location = new int[2];
        try {
            view.getLocationOnScreen(location);
        } catch (Throwable ignored) {
            return false;
        }
        return screenX >= location[0]
                && screenX < location[0] + view.getWidth()
                && screenY >= location[1]
                && screenY < location[1] + view.getHeight();
    }

    @NonNull
    private File resolveDualScreenHudStateFile() {
        return new File(resolveActiveGameDirectoryForDualScreen(), "droidbridge_dual_screen_state.json");
    }

    @NonNull
    private File resolveActiveGameDirectoryForDualScreen() {
        return resolveActiveGameDirectoryForRuntime();
    }

    @NonNull
    private File resolveActiveGameDirectoryForRuntime() {
        try {
            LauncherInstance instance = null;
            if (instanceSettingsKey != null && !instanceSettingsKey.trim().isEmpty()) {
                instance = LauncherInstanceManager.findByNameOrId(this, instanceSettingsKey);
            }
            if (instance == null) {
                instance = LauncherInstanceManager.findByNameOrId(this, versionId);
            }
            if (instance != null) {
                return instance.getGameDirectory();
            }
        } catch (Throwable throwable) {
            Logging.e("GameActivity", "Unable to resolve active instance game directory", throwable);
        }

        String minecraftHome = PathManager.DIR_MINECRAFT_HOME;
        if (minecraftHome != null && !minecraftHome.trim().isEmpty()) {
            return new File(minecraftHome);
        }
        return getFilesDir();
    }

    private void registerGameRecordingStateReceiver() {
        if (recordingStateReceiverRegistered) return;
        IntentFilter filter = new IntentFilter(GameRecordingService.ACTION_STATE);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(gameRecordingStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(gameRecordingStateReceiver, filter);
            }
            recordingStateReceiverRegistered = true;
        } catch (Throwable throwable) {
            Logging.e("GameActivity", "Unable to register recording state receiver", throwable);
        }
    }

    private void unregisterGameRecordingStateReceiver() {
        if (!recordingStateReceiverRegistered) return;
        try {
            unregisterReceiver(gameRecordingStateReceiver);
        } catch (Throwable ignored) {
        }
        recordingStateReceiverRegistered = false;
    }

    private void registerRecordingFrameBridgeReceiver() {
        if (recordingFrameBridgeReceiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(RecordingFrameBridge.ACTION_ATTACH);
        filter.addAction(RecordingFrameBridge.ACTION_DETACH);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(recordingFrameBridgeReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(recordingFrameBridgeReceiver, filter);
            }
            recordingFrameBridgeReceiverRegistered = true;
        } catch (Throwable throwable) {
            Logging.e("GameActivity", "Unable to register clean recording frame receiver", throwable);
        }
    }

    private void unregisterRecordingFrameBridgeReceiver() {
        if (!recordingFrameBridgeReceiverRegistered) return;
        stopPrimaryRecordingFramePump();
        try {
            unregisterReceiver(recordingFrameBridgeReceiver);
        } catch (Throwable ignored) {
        }
        recordingFrameBridgeReceiverRegistered = false;
    }

    private void requestStartGameRecording() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this,
                    "Minecraft screen and audio recording requires Android 10 or newer.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        if (GameRecordingService.isRecordingOrStarting()) {
            Toast.makeText(this, "A DroidBridge recording is already active.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQUEST_GAME_RECORDING_AUDIO_PERMISSION
            );
            return;
        }
        requestMediaProjectionForGameRecording();
    }

    private void requestMediaProjectionForGameRecording() {
        MediaProjectionManager manager = (MediaProjectionManager)
                getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            Toast.makeText(this, "Android screen capture is unavailable on this device.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            startActivityForResult(
                    manager.createScreenCaptureIntent(),
                    REQUEST_GAME_RECORDING_CAPTURE
            );
        } catch (Throwable throwable) {
            Logging.e("GameActivity", "Unable to request screen-capture permission", throwable);
            Toast.makeText(this, "Unable to open Android's screen-capture permission.", Toast.LENGTH_LONG).show();
        }
    }

    private void startApprovedGameRecording(@NonNull Intent projectionData) {
        DisplayMetrics metrics = new DisplayMetrics();
        Display display = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? getDisplay()
                : getWindowManager().getDefaultDisplay();
        if (display != null) {
            display.getRealMetrics(metrics);
        } else {
            metrics.setTo(getResources().getDisplayMetrics());
        }

        int width = Math.max(2, metrics.widthPixels);
        int height = Math.max(2, metrics.heightPixels);
        if ((width & 1) != 0) width--;
        if ((height & 1) != 0) height--;

        boolean wantsBothScreens = LauncherPreferences.isDualScreenSupportEnabled(this)
                && RecordingPreferences.isRecordBothScreensEnabled(this);
        if (wantsBothScreens) {
            if (dualScreenController == null) configureDualScreenController();
            View secondScreenView = dualScreenController == null
                    ? null
                    : dualScreenController.getControlsRecordingView();
            if (secondScreenView != null) {
                // The second-screen recorder now suppresses touch artwork only inside its
                // encoder draw pass. Do not alter the live controls that the player sees.
                DualScreenRecordingSession.StartResult secondResult =
                        DualScreenRecordingSession.start(this, secondScreenView);
                if (!secondResult.success) {
                    Toast.makeText(
                            this,
                            "Second-screen recording could not start ("
                                    + (secondResult.errorMessage == null
                                    ? "unknown error" : secondResult.errorMessage)
                                    + "). The Minecraft screen will still be recorded.",
                            Toast.LENGTH_LONG
                    ).show();
                }
            } else {
                Toast.makeText(
                        this,
                        "Record both screens is enabled, but no active DroidBridge second-screen HUD was found. Recording the Minecraft screen only.",
                        Toast.LENGTH_LONG
                ).show();
            }
        }

        // Prepare capture-only touch-control visibility before MediaProjection queues its
        // first frame. The live player still sees a secure visual mirror of the controls.
        applyRecordingTouchControlVisibility(true);

        GameRecordingService.start(
                this,
                RESULT_OK,
                projectionData,
                width,
                height,
                Math.max(1, metrics.densityDpi)
        );
        Toast.makeText(
                this,
                DualScreenRecordingSession.isRecording()
                        ? "Starting dual-screen Minecraft recording..."
                        : "Starting Minecraft recording...",
                Toast.LENGTH_SHORT
        ).show();
    }

    private void applyRecordingTouchControlVisibility(boolean recording) {
        recordingTouchControlsHidden = recording
                && RecordingPreferences.isHideTouchControlsEnabled(this);

        // Never alter the live touch overlay for recording. When hiding is requested, the
        // primary recorder captures Minecraft's render layer directly through RecordingFrameBridge;
        // the player's actual controls therefore remain fully visible and fully interactive.
        if (touchControlsOverlay != null) {
            touchControlsOverlay.setControlsVisible(ControlsPreferences.isTouchControlsEnabled(this)
                    && (dualScreenController == null || !dualScreenController.ownsTouchControls()));
            touchControlsOverlay.setAlpha(1f);
            touchControlsOverlay.bringToFront();
        }

        // The second-screen recorder already suppresses control artwork only inside its encoder
        // draw pass, so its live bottom-screen controls also stay visible at full alpha.
        if (dualScreenController != null) {
            dualScreenController.setBottomTouchControlsRecordingHidden(false);
        }
        if (floatingGameSettingsOverlayController != null) {
            floatingGameSettingsOverlayController.bringToFront();
        }
    }

    private void stopPrimaryRecordingFramePump() {
        PrimaryRecordingFramePump pump = primaryRecordingFramePump;
        primaryRecordingFramePump = null;
        primaryRecordingFramePumpSessionId = 0L;
        if (pump != null) {
            try {
                pump.stop();
            } catch (Throwable throwable) {
                Logging.i("GameActivity", "Unable to stop clean recording frame pump: "
                        + throwable.getMessage());
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_GAME_RECORDING_CAPTURE) return;

        configureWindow();
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Screen recording was cancelled.", Toast.LENGTH_SHORT).show();
            return;
        }
        startApprovedGameRecording(data);
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_GAME_RECORDING_AUDIO_PERMISSION) return;

        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            Toast.makeText(this,
                    "DroidBridge needs audio permission for the selected recording audio mode.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        requestMediaProjectionForGameRecording();
    }

    private void openInGameButtonOverlay() {
        if (binding == null || exiting) return;

        if (inGameControlsDialog != null && inGameControlsDialog.isShowing()) {
            configureWindow();
            return;
        }

        // Android Virtual Mouse normally keeps a physical mouse captured while a
        // Minecraft GUI is open so the visible software cursor can truly match
        // Minecraft's centered logical cursor. This dialog is Android-owned UI, so
        // temporarily release capture here to let the real OS pointer click it.
        if (minecraftSurface != null) {
            minecraftSurface.setAndroidVirtualMouseUiInteractionMode(true);
        }

        boolean touchEnabled = ControlsPreferences.isTouchControlsEnabled(this);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        scrollView.setBackgroundColor(COLOR_DIALOG_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dpToPx(20), dpToPx(18), dpToPx(20), dpToPx(10));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView title = new TextView(this);
        title.setText("In-game controls");
        title.setTextColor(COLOR_TEXT_PRIMARY);
        title.setTextSize(24f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, 0, 0, dpToPx(4));
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView summary = new TextView(this);
        summary.setText("Quick controls, overlay settings, and recovery actions while Minecraft is running.");
        summary.setTextColor(COLOR_TEXT_SECONDARY);
        summary.setTextSize(13f);
        summary.setPadding(0, 0, 0, dpToPx(12));
        root.addView(summary, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        final AlertDialog[] dialogRef = new AlertDialog[1];

        root.addView(buildInGameDialogAction(
                "Controller / gamepad overlay",
                "Edit controller mappings, cursor behavior, FPS, log overlay, and floating button placement.",
                false,
                () -> {
                    dismissDialog(dialogRef[0]);
                    GamepadMappingDialog.show(this, () -> runOnUiThread(() -> {
                        if (gamepadInputController != null) {
                            gamepadInputController.onMappingsChanged();
                        }
                        applyInGameOverlayPreferences();
                        reloadTouchControlsLayout();
                    }));
                }
        ));

        // Keep touch-profile switching at the head of the in-game settings flow.
        // The full Controller / gamepad overlay still contains the same selector for
        // discoverability, but this copy is intentionally one-tap: selecting another
        // profile applies it to the running game immediately and closes this dialog.
        root.addView(buildInGameTouchLayoutSelector(dialogRef));

        root.addView(buildInGameDialogAction(
                touchEnabled ? "Hide touch controls" : "Show touch controls",
                touchEnabled
                        ? "Temporarily hides the active touch layout without leaving the game."
                        : "Shows the selected touch layout again.",
                false,
                () -> {
                    dismissDialog(dialogRef[0]);
                    boolean enabled = !ControlsPreferences.isTouchControlsEnabled(this);
                    ControlsPreferences.setTouchControlsEnabled(this, enabled);
                    if (touchControlsOverlay != null) {
                        touchControlsOverlay.setControlsVisible(enabled);
                        touchControlsOverlay.setAlpha(1f);
                        touchControlsOverlay.bringToFront();
                    }
                    if (dualScreenController != null && dualScreenController.ownsTouchControls()) {
                        dualScreenController.setBottomTouchControlsVisible(enabled);
                    }
                    applyInGameOverlayPreferences();
                }
        ));

        root.addView(buildInGameDialogAction(
                "Edit touch controls",
                "Open the touch layout editor for the current control layout.",
                false,
                () -> {
                    dismissDialog(dialogRef[0]);
                    showInGameTouchControlsEditor();
                }
        ));

        root.addView(buildInGameDialogAction(
                "Manage / import touch layouts",
                "Choose, import, or manage touch control layouts.",
                false,
                () -> {
                    dismissDialog(dialogRef[0]);
                    startActivity(new Intent(this, ControlsActivity.class));
                }
        ));

        if (LauncherPreferences.isDualScreenSupportEnabled(this)) {
            boolean dualScreenShowing = dualScreenController != null && dualScreenController.isShowing();
            root.addView(buildInGameDialogAction(
                    dualScreenShowing ? "Return game to this screen" : "Use external game screen",
                    dualScreenShowing
                            ? "Move Minecraft back to this display and close the bottom-screen HUD deck."
                            : "Move Minecraft to an attached external display and turn this display into the hotbar/HUD/action deck.",
                    false,
                    () -> {
                        dismissDialog(dialogRef[0]);
                        if (!LauncherPreferences.isDualScreenSupportEnabled(this)) {
                            Toast.makeText(this, "Dual-screen support is disabled in Launcher Settings.", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (dualScreenController == null) configureDualScreenController();
                        if (dualScreenController != null) {
                            dualScreenController.toggleExternalGameMode(launchStarted);
                            refreshCursorOverlayTarget();
                        }
                    }
            ));
        }

        boolean recordingFinalizing = GameRecordingService.isFinalizing();
        boolean recordingActive = GameRecordingService.isRecordingOrStarting();
        boolean recordBothScreens = LauncherPreferences.isDualScreenSupportEnabled(this)
                && RecordingPreferences.isRecordBothScreensEnabled(this);
        String recordingAudioLabel = RecordingPreferences.getAudioModeLabel(this);
        root.addView(buildInGameDialogAction(
                recordingFinalizing
                        ? "Finishing recording..."
                        : (recordingActive ? "Stop recording" : "Start recording"),
                recordingFinalizing
                        ? "DroidBridge is finalizing the MP4 and saving it to Movies/DroidBridge/Recordings."
                        : (recordingActive
                        ? "Finish the current recording and save it to Movies/DroidBridge/Recordings."
                        : (recordBothScreens
                        ? "Record the Minecraft/game display plus the DroidBridge HUD/touch display as a matched second-screen MP4. Audio: " + recordingAudioLabel + "."
                        : "Record the current Minecraft display. Audio: " + recordingAudioLabel + ". A second display may stay connected.")),
                false,
                () -> {
                    dismissDialog(dialogRef[0]);
                    if (recordingFinalizing) {
                        Toast.makeText(this, "The recording is already being finalized.", Toast.LENGTH_SHORT).show();
                    } else if (recordingActive) {
                        showStopGameRecordingDialog();
                    } else {
                        requestStartGameRecording();
                    }
                }
        ));

        TextView recoveryTitle = new TextView(this);
        recoveryTitle.setText("Recovery");
        recoveryTitle.setTextColor(COLOR_TEXT_MUTED);
        recoveryTitle.setTextSize(12f);
        recoveryTitle.setTypeface(Typeface.DEFAULT_BOLD);
        recoveryTitle.setPadding(dpToPx(2), dpToPx(8), 0, dpToPx(6));
        root.addView(recoveryTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        root.addView(buildInGameDialogAction(
                "Force close game",
                "Use this if Minecraft is frozen, crashed, or will not return to the launcher normally.",
                true,
                () -> {
                    dismissDialog(dialogRef[0]);
                    showForceCloseGameDialog();
                }
        ));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(scrollView)
                .create();
        dialogRef[0] = dialog;
        inGameControlsDialog = dialog;
        dialog.setOnDismissListener(d -> {
            if (inGameControlsDialog == dialog) {
                inGameControlsDialog = null;
            }
            if (minecraftSurface != null) {
                minecraftSurface.setAndroidVirtualMouseUiInteractionMode(false);
            }
            configureWindow();
        });
        dialog.show();
        styleDarkDialog(dialog);
    }


    private void openInGameLauncherMenuFromBackShortcut() {
        if (exiting || isFinishing() || isDestroyed()) return;
        runOnUiThread(this::openInGameButtonOverlay);
        configureWindow();
    }

    @NonNull
    private View buildInGameTouchLayoutSelector(@NonNull AlertDialog[] dialogRef) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dpToPx(14), dpToPx(12), dpToPx(14), dpToPx(12));
        card.setBackground(roundedDrawable(COLOR_CARD_BG, COLOR_CARD_STROKE, 18));

        TextView title = new TextView(this);
        title.setText("Touch controls profile");
        title.setTextColor(COLOR_TEXT_PRIMARY);
        title.setTextSize(16f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView summary = new TextView(this);
        summary.setText("Switch the active touch-control profile. A selection is applied immediately and closes this dialog.");
        summary.setTextColor(COLOR_TEXT_SECONDARY);
        summary.setTextSize(12.5f);
        summary.setPadding(0, dpToPx(3), 0, dpToPx(6));
        card.addView(summary, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        List<File> layouts = TouchControlsStore.listLayouts(this);
        ArrayList<File> touchLayouts = new ArrayList<>(layouts);
        ArrayList<String> labels = new ArrayList<>();
        String selectedPath = ControlsPreferences.getSelectedLayoutPath(this);
        int selectedIndex = 0;

        for (int i = 0; i < touchLayouts.size(); i++) {
            File file = touchLayouts.get(i);
            TouchControlsLayoutData data = TouchControlsStore.loadLayout(file);
            String name = data != null && data.name != null && !data.name.trim().isEmpty()
                    ? data.name.trim()
                    : file.getName();
            labels.add(name + "  •  " + file.getName());
            if (selectedPath != null && file.getAbsolutePath().equals(selectedPath)) {
                selectedIndex = i;
            }
        }

        if (touchLayouts.isEmpty()) {
            File fallback = TouchControlsStore.getDefaultLayoutFile(this);
            touchLayouts.add(fallback);
            labels.add("Default Touch Controls  •  " + fallback.getName());
            selectedIndex = 0;
        }

        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this,
                android.R.layout.simple_spinner_item,
                labels
        ) {
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                styleInGameTouchLayoutSpinnerRow(view, false);
                return view;
            }

            @Override
            public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View view = super.getDropDownView(position, convertView, parent);
                styleInGameTouchLayoutSpinnerRow(view, true);
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(Math.max(0, Math.min(selectedIndex, touchLayouts.size() - 1)), false);

        final String initiallySelectedPath = touchLayouts
                .get(Math.max(0, Math.min(selectedIndex, touchLayouts.size() - 1)))
                .getAbsolutePath();
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= touchLayouts.size()) return;

                File selected = touchLayouts.get(position);
                String nextPath = selected.getAbsolutePath();
                String currentPath = ControlsPreferences.getSelectedLayoutPath(GameActivity.this);
                String effectiveCurrentPath = currentPath == null ? initiallySelectedPath : currentPath;
                if (nextPath.equals(effectiveCurrentPath)) return;

                ControlsPreferences.setSelectedLayoutPath(GameActivity.this, nextPath);
                reloadTouchControlsLayout();
                applyInGameOverlayPreferences();
                LauncherLogManager.append(
                        "DroidBridgeControls: switched in-game touch profile -> " + selected.getName());
                dismissDialog(dialogRef[0]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        card.addView(spinner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, dpToPx(10));
        card.setLayoutParams(params);
        return card;
    }

    private void styleInGameTouchLayoutSpinnerRow(@NonNull View view, boolean dropdown) {
        view.setBackgroundColor(dropdown ? COLOR_CARD_BG_PRESSED : Color.TRANSPARENT);
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            text.setTextColor(COLOR_TEXT_PRIMARY);
            text.setTextSize(15f);
            text.setSingleLine(false);
            text.setPadding(
                    text.getPaddingLeft(),
                    dpToPx(8),
                    text.getPaddingRight(),
                    dpToPx(8)
            );
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @NonNull
    private View buildInGameDialogAction(
            @NonNull String title,
            @NonNull String summary,
            boolean destructive,
            @NonNull Runnable action
    ) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dpToPx(14), dpToPx(12), dpToPx(14), dpToPx(12));
        card.setBackground(roundedDrawable(COLOR_CARD_BG, COLOR_CARD_STROKE, 18));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(view -> action.run());
        card.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                view.setBackground(roundedDrawable(COLOR_CARD_BG_PRESSED, COLOR_CARD_STROKE, 18));
            } else if (event.getAction() == MotionEvent.ACTION_UP
                    || event.getAction() == MotionEvent.ACTION_CANCEL) {
                view.setBackground(roundedDrawable(COLOR_CARD_BG, COLOR_CARD_STROKE, 18));
            }
            return false;
        });

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(destructive ? COLOR_DANGER : COLOR_TEXT_PRIMARY);
        titleView.setTextSize(16f);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(titleView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView summaryView = new TextView(this);
        summaryView.setText(summary);
        summaryView.setTextColor(COLOR_TEXT_SECONDARY);
        summaryView.setTextSize(12.5f);
        summaryView.setPadding(0, dpToPx(3), 0, 0);
        card.addView(summaryView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, dpToPx(10));
        card.setLayoutParams(params);
        return card;
    }

    private void scheduleGameProcessKillAfterRecordingFinalizes(
            @NonNull String reason,
            long minimumDelayMs
    ) {
        GameRecordingService.stop(this);
        final Handler handler = new Handler(Looper.getMainLooper());
        final long earliestKill = SystemClock.elapsedRealtime() + Math.max(0L, minimumDelayMs);
        final long deadline = SystemClock.elapsedRealtime() + 15000L;
        final Runnable[] check = new Runnable[1];
        check[0] = () -> {
            long now = SystemClock.elapsedRealtime();
            boolean busy = GameRecordingService.isRecordingOrStarting();
            if (now < earliestKill) {
                handler.postDelayed(check[0], Math.min(150L, earliestKill - now));
                return;
            }
            if (busy && now < deadline) {
                handler.postDelayed(check[0], 100L);
                return;
            }
            if (busy) {
                LauncherLogManager.append("RecordingLifecycle: finalization timed out before process exit reason="
                        + reason + "; orphan recovery will run in launcher");
            } else {
                LauncherLogManager.append("RecordingLifecycle: finalization complete before process exit reason="
                        + reason);
            }
            Process.killProcess(Process.myPid());
        };
        handler.postDelayed(check[0], Math.max(50L, minimumDelayMs));
    }

    private void showStopGameRecordingDialog() {
        if (!GameRecordingService.isRecordingOrStarting()) {
            Toast.makeText(this, "The recording has already stopped.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (GameRecordingService.isFinalizing()) {
            Toast.makeText(this, "The recording is already being finalized.", Toast.LENGTH_SHORT).show();
            return;
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Stop recording?")
                .setMessage("Are you sure you want to stop the current recording? DroidBridge will finalize the MP4 and save it to Movies/DroidBridge/Recordings.")
                .setNegativeButton("Keep recording", null)
                .setPositiveButton("Stop recording", null)
                .create();

        dialog.setOnShowListener(dialogInterface -> {
            styleDarkDialog(dialog);

            TextView negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (negative != null) negative.setTextColor(COLOR_ACCENT);

            TextView positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (positive != null) {
                positive.setTextColor(COLOR_DANGER);
                positive.setOnClickListener(view -> {
                    dialog.dismiss();
                    GameRecordingService.stop(GameActivity.this);
                    Toast.makeText(GameActivity.this,
                            "Finishing recording...", Toast.LENGTH_SHORT).show();
                });
            }
        });
        dialog.setOnDismissListener(d -> configureWindow());
        dialog.show();
    }

    private void showForceCloseGameDialog() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dpToPx(22), dpToPx(18), dpToPx(22), 0);
        root.setBackgroundColor(COLOR_DIALOG_BG);

        TextView title = new TextView(this);
        title.setText("Force close game?");
        title.setTextColor(COLOR_TEXT_PRIMARY);
        title.setTextSize(22f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView message = new TextView(this);
        message.setText("This should only be used when Minecraft is frozen, crashed, or will not close normally. The launcher will save the latest log if log history is enabled, reset launcher-side game state, and close the game process.");
        message.setTextColor(COLOR_TEXT_SECONDARY);
        message.setTextSize(14f);
        message.setPadding(0, dpToPx(10), 0, dpToPx(2));
        root.addView(message, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(root)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Force close", null)
                .create();

        dialog.setOnShowListener(dialogInterface -> {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(COLOR_ACCENT);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(COLOR_DANGER);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                dialog.dismiss();
                forceCloseGameAndReturnToLauncher("User requested force close from in-game controls");
            });
        });
        dialog.setOnDismissListener(d -> configureWindow());
        dialog.show();
        styleDarkDialog(dialog);
    }

    private void forceCloseGameAndReturnToLauncher(@NonNull String reason) {
        if (exiting) return;
        exiting = true;
        if (GameRecordingService.isRecordingOrStarting()) {
            LauncherLogManager.append("RecordingLifecycle: force-close requested -> finalize before process kill");
            GameRecordingService.stop(this);
        }
        stopDroidBridgeAndroidMicProxy();
        stopDroidBridgeAndroidNarratorProxy();

        try {
            LauncherLogManager.append("ForceClose: " + reason);
            LauncherLogManager.preserveLatestLogIfEnabled(this, versionId);
        } catch (Throwable throwable) {
            Logging.e("GameActivity", "Failed to preserve latest log during force close", throwable);
        }

        runOnUiThread(() -> appendStatus("Force closing Minecraft..."));
        stopLogOverlayTicker();
        stopLiveVsyncRefreshMonitor();

        if (quitWatchdogHandler != null && quitWatchdogRunnable != null) {
            quitWatchdogHandler.removeCallbacks(quitWatchdogRunnable);
        }
        quitWatchdogRunnable = null;
        quitWatchdogHandler = null;

        try {
            CallbackBridge.clearInputFocus();
            CallbackBridge.setInputReady(false);
        } catch (Throwable ignored) {
        }

        if (floatingGameSettingsOverlayController != null) {
            floatingGameSettingsOverlayController.pause();
        }

        if (binding != null) {
            try {
                MinecraftGLSurface surface = minecraftSurface;
                surface.setSurfaceReadyListener(null);
                surface.setOnRenderingStartedListener(null);
            } catch (Throwable ignored) {
            }
        }

        ControlifySDL.reset();
        ControllerModCompat.reset();
        TouchControllerModCompat.reset();
        LaunchGame.resetLaunchState();

        try {
            Intent intent = new Intent(this, DroidBridgeLaunchActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
        } catch (Throwable throwable) {
            Logging.e("GameActivity", "Failed to reopen launcher after force close", throwable);
        }

        finishAndRemoveTask();

        LauncherLogManager.append("ForceClose: waiting for recording finalization before game process kill.");
        scheduleGameProcessKillAfterRecordingFinalizes("user force close", 250L);
    }

    private void dismissDialog(@Nullable AlertDialog dialog) {
        if (dialog == null) return;
        try {
            dialog.dismiss();
        } catch (Throwable ignored) {
        }
    }

    private void styleDarkDialog(@NonNull AlertDialog dialog) {
        if (dialog.getWindow() == null) return;
        dialog.getWindow().setBackgroundDrawable(roundedDrawable(COLOR_DIALOG_BG, COLOR_DIALOG_BG, 24));
        dialog.getWindow().setDimAmount(0.58f);
    }

    @NonNull
    private GradientDrawable roundedDrawable(int fillColor, int strokeColor, int cornerDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dpToPx(cornerDp));
        drawable.setStroke(Math.max(1, dpToPx(1)), strokeColor);
        return drawable;
    }

    private void applyInGameOverlayPreferences() {
        applyGameDisplaySurfaceOptions();
        configureLogOverlay();
        configureInGameSettingsButton();
        configureWindow();
        scheduleMinecraftSurfaceRefresh();
    }

    private void showInGameTouchControlsEditor() {
        if (isFinishing() || isDestroyed()) return;

        InGameControlsEditorDialog existing = inGameControlsEditorDialog;
        if (existing != null && existing.isShowing()) {
            return;
        }

        final InGameControlsEditorDialog[] dialogRef = new InGameControlsEditorDialog[1];
        InGameControlsEditorDialog dialog = new InGameControlsEditorDialog(
                this,
                touchControlsOverlay,
                () -> {
                    if (inGameControlsEditorDialog == dialogRef[0]) {
                        inGameControlsEditorDialog = null;
                    }
                    if (exiting || isFinishing() || isDestroyed()) return;
                    runOnUiThread(() -> {
                        if (exiting || isFinishing() || isDestroyed()) return;
                        reloadTouchControlsLayout();
                        applyInGameOverlayPreferences();
                        CallbackBridge.setInputReady(true);
                        CallbackBridge.ensureInputFocus();
                    });
                });
        dialogRef[0] = dialog;
        inGameControlsEditorDialog = dialog;

        try {
            dialog.show();
            LauncherLogManager.append(
                    "DroidBridgeControls: opened in-game editor without pausing game Surface");
        } catch (Throwable throwable) {
            inGameControlsEditorDialog = null;
            LauncherLogManager.append(
                    "DroidBridgeControls: unable to open in-game editor: " + throwable);
            Toast.makeText(this, "Unable to open touch controls editor.", Toast.LENGTH_SHORT).show();
        }
    }

    private void reloadTouchControlsLayout() {
        if (dualScreenController != null && dualScreenController.ownsTouchControls()) {
            dualScreenController.reloadBottomTouchControlsLayout();
            return;
        }
        if (touchControlsOverlay == null) return;

        touchControlsOverlay.loadSelectedLayout();
        touchControlsOverlay.applyVirtualMouseLaunchSessionState();
        touchControlsOverlay.setControlsVisible(ControlsPreferences.isTouchControlsEnabled(this));
        touchControlsOverlay.setAlpha(1f);
        touchControlsOverlay.requestLayout();
        touchControlsOverlay.invalidate();
        touchControlsOverlay.bringToFront();

        if (binding != null && binding.layoutLogOverlay.getVisibility() == View.VISIBLE) {
            binding.layoutLogOverlay.bringToFront();
        }

        if (floatingGameSettingsOverlayController != null) {
            floatingGameSettingsOverlayController.bringToFront();
        } else if (binding != null && binding.buttonGameSettings.getVisibility() == View.VISIBLE) {
            binding.buttonGameSettings.bringToFront();
        }
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void refreshLogOverlay() {
        if (binding == null || binding.layoutLogOverlay.getVisibility() != View.VISIBLE) return;

        File logFile = getReadableLatestLogFile();
        if (logFile == null || !logFile.isFile()) {
            binding.textLogOverlay.setText("Waiting for latestlog.txt...");
            return;
        }

        long length = logFile.length();
        long modified = logFile.lastModified();
        if (length == lastLogOverlayLength && modified == lastLogOverlayModified) {
            return;
        }

        lastLogOverlayLength = length;
        lastLogOverlayModified = modified;

        try {
            String tail = readLogTail(logFile, 96 * 1024);
            if (tail.trim().isEmpty()) return;
            binding.textLogOverlay.setText(tail);
            binding.scrollLogOverlay.post(() -> {
                if (binding != null) {
                    binding.scrollLogOverlay.fullScroll(View.FOCUS_DOWN);
                }
            });
        } catch (Throwable throwable) {
            Logging.e("GameActivity", "Failed to refresh game log overlay", throwable);
        }
    }

    @Nullable
    private File getReadableLatestLogFile() {
        File logFile = Logger.getCurrentLogFile();
        if (logFile != null && logFile.isFile()) return logFile;

        try {
            logFile = LauncherLogManager.getLatestLogFile(this);
            if (logFile.isFile()) return logFile;
        } catch (Throwable throwable) {
            Logging.e("GameActivity", "Failed to resolve latestlog.txt", throwable);
        }

        return null;
    }

    @NonNull
    private String readLogTail(@NonNull File file, int maxBytes) throws Exception {
        long length = file.length();
        long start = Math.max(0L, length - maxBytes);
        int bytesToRead = (int) Math.max(0L, length - start);
        byte[] buffer = new byte[bytesToRead];

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(start);
            raf.readFully(buffer);
        }

        String text = new String(buffer, StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace('\r', '\n');

        if (start > 0) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline >= 0 && firstNewline + 1 < text.length()) {
                text = "...\n" + text.substring(firstNewline + 1);
            }
        }

        return trimToLastLines(text, 280);
    }

    @NonNull
    private String trimToLastLines(@NonNull String text, int maxLines) {
        String[] lines = text.split("\n", -1);
        if (lines.length <= maxLines) return text;

        StringBuilder out = new StringBuilder("...\n");
        for (int i = lines.length - maxLines; i < lines.length; i++) {
            out.append(lines[i]);
            if (i + 1 < lines.length) out.append('\n');
        }
        return out.toString();
    }

    private void startQuitWatchdog() {
        if (!isMinecraft26_2OrNewer(compatibilityVersionId)) return;

        quitWatchdogSessionStartWallMs = System.currentTimeMillis();
        LauncherLogManager.append("QuitWatchdog: armed for " + versionId + " sessionStartMs=" + quitWatchdogSessionStartWallMs);

        quitWatchdogHandler = new Handler(Looper.getMainLooper());
        quitWatchdogRunnable = new Runnable() {
            @Override
            public void run() {
                pollQuitWatchdog();
                if (!exiting && binding != null && quitWatchdogHandler != null) {
                    quitWatchdogHandler.postDelayed(this, 1000L);
                }
            }
        };

        // Do not poll immediately. Some slower devices are still copying/runtime-preparing
        // at the 3 second mark, and the old code could read a stale previous latestlog
        // containing "Stopping!" + "EGLBridge: Terminating" and kill this fresh launch.
        quitWatchdogHandler.postDelayed(quitWatchdogRunnable, 15000L);
    }

    private void pollQuitWatchdog() {
        if (exiting || quitWatchdogForceScheduled || !launchStarted) return;

        try {
            File logFile = getReadableLatestLogFile();
            if (logFile == null || !logFile.isFile()) return;

            long modified = logFile.lastModified();
            if (modified > 0 && modified + 1000L < quitWatchdogSessionStartWallMs) {
                // Stale log from a previous launch. Never use it to kill the current GameActivity.
                return;
            }

            String tail = readLogTail(logFile, 64 * 1024);
            boolean minecraftStopping = tail.contains("Stopping!");
            boolean eglBridgeTerminating = tail.contains("EGLBridge: Terminating");

            if (minecraftStopping && eglBridgeTerminating) {
                scheduleForcedGameProcessExit("Minecraft stop detected but JVM did not return");
            }
        } catch (Throwable throwable) {
            Logging.e("GameActivity", "Quit watchdog failed", throwable);
            LauncherLogManager.append("QuitWatchdog failed: " + throwable);
        }
    }

    private void scheduleForcedGameProcessExit(@NonNull String reason) {
        if (quitWatchdogForceScheduled || exiting) return;
        quitWatchdogForceScheduled = true;

        LauncherLogManager.append("QuitWatchdog: " + reason + "; closing game process soon.");
        runOnUiThread(() -> {
            appendStatus("Minecraft quit detected; closing game process...");
            configureWindow();
        });

        if (quitWatchdogHandler == null) {
            quitWatchdogHandler = new Handler(Looper.getMainLooper());
        }

        quitWatchdogHandler.postDelayed(() -> {
            if (exiting) return;

            LauncherLogManager.append("QuitWatchdog: forcing GameActivity/game process exit.");
            LauncherLogManager.preserveLatestLogIfEnabled(this, versionId);
            if (GameRecordingService.isRecordingOrStarting()) {
                LauncherLogManager.append("RecordingLifecycle: quit watchdog -> finalize before process kill");
                GameRecordingService.stop(this);
            }
            exiting = true;
            finishAndRemoveTask();
            scheduleGameProcessKillAfterRecordingFinalizes("quit watchdog", 250L);
        }, 2500L);
    }

    @NonNull
    private String resolveCompatibilityVersionId(
            @NonNull String rawVersionId,
            @Nullable String explicitInstanceKey
    ) {
        LauncherInstance instance = null;
        try {
            if (explicitInstanceKey != null && !explicitInstanceKey.trim().isEmpty()) {
                instance = LauncherInstanceManager.findByNameOrId(this, explicitInstanceKey.trim());
            }
            if (instance == null) {
                instance = LauncherInstanceManager.findByNameOrId(this, rawVersionId);
            }
            if (instance != null) {
                String minecraftVersion = instance.getMinecraftVersionId();
                if (minecraftVersion != null && !minecraftVersion.trim().isEmpty()) {
                    String resolved = minecraftVersion.trim();
                    LauncherLogManager.append("GameActivity: compatibility Minecraft version resolved raw="
                            + rawVersionId + " instance=" + instance.getName() + " minecraft=" + resolved
                            + " base=" + instance.getBaseVersionId());
                    return resolved;
                }
                String baseVersion = instance.getBaseVersionId();
                if (baseVersion != null && !baseVersion.trim().isEmpty()) {
                    String resolved = baseVersion.trim();
                    LauncherLogManager.append("GameActivity: compatibility base version resolved raw="
                            + rawVersionId + " instance=" + instance.getName() + " base=" + resolved);
                    return resolved;
                }
            }
        } catch (Throwable throwable) {
            Logging.e("GameActivity", "Unable to resolve compatibility version for " + rawVersionId, throwable);
        }
        return rawVersionId;
    }

    private boolean isMinecraft26OrNewer(@NonNull String id) {
        String lower = id.toLowerCase(Locale.ROOT).trim();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?:^|[^0-9])(\\d+)\\.(\\d+)(?:\\.(\\d+))?")
                .matcher(lower);
        int major = -1;
        while (matcher.find()) {
            try {
                major = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return major >= 26;
    }

    private boolean isMinecraft26_2OrNewer(@NonNull String id) {
        String lower = id.toLowerCase(Locale.ROOT).trim();
        return lower.matches("^26\\.(2|[3-9]|[0-9]{2,}).*");
    }

    private void startLaunchOnce() {
        synchronized (this) {
            if (launchStarted || exiting) return;
        }

        if (shouldPauseLaunchForModPreLaunchWarning()) {
            return;
        }

        if (shouldPauseLaunchForReplayModFfmpegWarning()) {
            return;
        }

        if (shouldPauseLaunchForMicrosoftAccountPreflight()) {
            return;
        }

        synchronized (this) {
            if (launchStarted || exiting) return;
            launchStarted = true;
        }

        runOnUiThread(this::scheduleLaunchStatusAutoHide);

        configureInputBridgeForVersion(versionId);
        CallbackBridge.setInputReady(true);
        runOnUiThread(() -> appendStatus(getString(R.string.game_status_surface_ready)));

        try {
            if (DroidBridgeSDL3Bootstrap.isRequested()) {
                MinecraftGLSurface activeSurface = minecraftSurface;
                if (activeSurface == null) {
                    throw new IllegalStateException("SDL3 launch requested but Minecraft surface is unavailable");
                }
                runOnUiThread(() -> appendStatus("Preparing SDL3 Android platform..."));
                DroidBridgeSDL3Bootstrap.prepareForLaunch(this, activeSurface);
            }

            AccountStore.Account account = null;
            try {
                account = new AccountStore(this).load();
            } catch (Throwable throwable) {
                Logging.e("GameActivity", "Could not load saved account", throwable);
            }
            LauncherSecurity.requireOfficialBuildAndMicrosoftSession(this, account, "launch Minecraft");

            int width = Math.max(1, CallbackBridge.windowWidth);
            int height = Math.max(1, CallbackBridge.windowHeight);

            // Keep this status generic. The actual renderer is logged separately.
            LauncherLogManager.append("GameActivity: surface ready; starting LaunchGame for " + versionId
                    + " size=" + width + "x" + height);
            runOnUiThread(() -> appendStatus(getString(R.string.game_status_starting_minecraft)));
            int exitCode = LaunchGame.runGame(
                    this,
                    versionId,
                    instanceSettingsKey,
                    account,
                    width,
                    height,
                    quickPlayWorld,
                    quickPlayServer,
                    status -> runOnUiThread(() -> appendStatus(status))
            );

            runOnUiThread(() -> {
                appendStatus(getString(R.string.msg_launch_finished, exitCode));
                finishAfterExit();
            });
        } catch (Throwable throwable) {
            Logging.e("GameActivity", "Launch failed", throwable);
            runOnUiThread(() -> {
                binding.textStatus.setVisibility(View.VISIBLE);
                appendStatus(getString(
                        R.string.msg_launch_failed,
                        throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName()
                ));
            });
        }
    }



    private boolean shouldPauseLaunchForMicrosoftAccountPreflight() {
        if (microsoftAccountPreflightCompleted
                || microsoftAccountPreflightAcceptedAfterWarning
                || exiting) {
            return false;
        }

        if (microsoftAccountPreflightPending) {
            return true;
        }

        microsoftAccountPreflightPending = true;
        runOnUiThread(this::startMicrosoftAccountPreflightOnUiThread);
        return true;
    }

    private void setupMicrosoftAccountPreflightManager() {
        try {
            accountStore = new AccountStore(this);
            microsoftAuthManager = new MicrosoftAuthManagerPersonal(this, accountStore);
            microsoftAuthManager.setListener(new MicrosoftAuthManagerPersonal.Listener() {
                @Override
                public void onSignedIn(@NonNull AccountStore.Account account) {
                    finishMicrosoftAccountRefreshSuccess(account);
                }

                @Override
                public void onError(@NonNull String message) {
                    finishMicrosoftAccountRefreshFailure(message);
                }
            });
        } catch (Throwable throwable) {
            Logging.e("GameActivity", "Microsoft account preflight manager initialization failed", throwable);
            accountStore = null;
            microsoftAuthManager = null;
        }
    }

    private void startMicrosoftAccountPreflightOnUiThread() {
        if (exiting || isFinishing() || isDestroyed()) {
            microsoftAccountPreflightPending = false;
            return;
        }

        if (binding != null) {
            binding.textStatus.setVisibility(View.VISIBLE);
        }
        appendStatus("Checking Microsoft account login before launch...");

        AccountStore store = getAccountStoreForPreflight();
        if (store == null) {
            runTokenValidationAfterRefreshFailure("DroidBridge could not open the saved account store.");
            return;
        }

        AccountStore.Account activeAccount;
        try {
            activeAccount = store.load();
        } catch (Throwable throwable) {
            finishMicrosoftAccountSessionPreflight(PreLaunchMicrosoftSessionResult.warning(
                    "DroidBridge could not load the saved account before launch: " + friendlyThrowableMessage(throwable)
            ));
            return;
        }

        AccountStore.Account microsoftAccount = resolveMicrosoftAccountForPreflight(activeAccount);
        if (microsoftAccount == null) {
            finishMicrosoftAccountSessionPreflight(PreLaunchMicrosoftSessionResult.ok(
                    "Microsoft account refresh skipped: active launch account is offline/local."
            ));
            return;
        }

        if (microsoftAuthManager == null) {
            runTokenValidationAfterRefreshFailure("DroidBridge could not initialize the Microsoft refresh helper.");
            return;
        }

        if (!MicrosoftAuthConfigPersonal.isConfigured()) {
            runTokenValidationAfterRefreshFailure("Microsoft client ID is not configured, so DroidBridge could not refresh the login automatically.");
            return;
        }

        appendStatus("Refreshing Microsoft account session...");

        try {
            // This is asynchronous. The listener above continues launch only after the
            // refreshed Microsoft + Minecraft session has been saved to AccountStore.
            // If there is no refresh token, the auth manager opens the normal sign-in
            // screen and still calls onSignedIn only after the full login completes.
            microsoftAuthManager.refreshMicrosoftAccount();
        } catch (Throwable throwable) {
            runTokenValidationAfterRefreshFailure("Microsoft account refresh could not start: " + friendlyThrowableMessage(throwable));
        }
    }

    @Nullable
    private AccountStore getAccountStoreForPreflight() {
        if (accountStore != null) return accountStore;
        try {
            accountStore = new AccountStore(this);
            return accountStore;
        } catch (Throwable throwable) {
            Logging.e("GameActivity", "Unable to create AccountStore for preflight", throwable);
            return null;
        }
    }

    private void finishMicrosoftAccountRefreshSuccess(@NonNull AccountStore.Account refreshedAccount) {
        if (exiting || isFinishing() || isDestroyed()) {
            microsoftAccountPreflightPending = false;
            return;
        }

        microsoftAccountPreflightPending = false;
        microsoftAccountPreflightCompleted = true;
        appendStatus("Microsoft account login refreshed. Continuing launch...");
        continueLaunchAfterMicrosoftAccountPreflight();
    }

    private void finishMicrosoftAccountRefreshFailure(@NonNull String message) {
        if (exiting || isFinishing() || isDestroyed()) {
            microsoftAccountPreflightPending = false;
            return;
        }

        appendStatus("Microsoft account refresh failed; checking saved session...");
        runTokenValidationAfterRefreshFailure("Microsoft account refresh failed: " + trimForDialog(message, 700));
    }

    private void runTokenValidationAfterRefreshFailure(@NonNull String refreshFailureMessage) {
        Thread thread = new Thread(() -> {
            PreLaunchMicrosoftSessionResult result;
            try {
                AccountStore store = getAccountStoreForPreflight();
                AccountStore.Account account = store != null ? store.load() : null;
                AccountStore.Account microsoftAccount = resolveMicrosoftAccountForPreflight(account);
                PreLaunchMicrosoftSessionResult validation = checkMicrosoftAccountSession(microsoftAccount);
                if (validation.ok && microsoftAccount != null) {
                    result = PreLaunchMicrosoftSessionResult.ok(
                            refreshFailureMessage + "\n\nThe saved Minecraft session still verified successfully, so launch can continue."
                    );
                } else if (validation.ok) {
                    result = validation;
                } else {
                    result = PreLaunchMicrosoftSessionResult.warning(
                            refreshFailureMessage + "\n\n" + validation.message
                    );
                }
            } catch (Throwable throwable) {
                result = PreLaunchMicrosoftSessionResult.warning(
                        refreshFailureMessage + "\n\nDroidBridge could not check the saved Microsoft session: "
                                + friendlyThrowableMessage(throwable)
                );
            }

            PreLaunchMicrosoftSessionResult finalResult = result;
            runOnUiThread(() -> finishMicrosoftAccountSessionPreflight(finalResult));
        }, "DroidBridgeAccountPreflightValidation");
        thread.setDaemon(true);
        thread.start();
    }

    private void finishMicrosoftAccountSessionPreflight(@NonNull PreLaunchMicrosoftSessionResult result) {
        microsoftAccountPreflightPending = false;

        if (exiting || isFinishing() || isDestroyed()) {
            return;
        }

        if (result.ok) {
            microsoftAccountPreflightCompleted = true;
            if (!isNullOrBlank(result.message)) {
                appendStatus(result.message);
            }
            continueLaunchAfterMicrosoftAccountPreflight();
            return;
        }

        showMicrosoftAccountPreflightWarningDialog(result);
    }

    @NonNull
    private PreLaunchMicrosoftSessionResult checkMicrosoftAccountSession(@Nullable AccountStore.Account account) {
        if (account == null) {
            return PreLaunchMicrosoftSessionResult.ok("Microsoft account check skipped: no active Microsoft launch account was loaded.");
        }

        if (!account.isMicrosoftAccount() || !account.hasMinecraftSession()) {
            return PreLaunchMicrosoftSessionResult.ok("Microsoft account check skipped: active launch account is offline/local.");
        }

        if (isNullOrBlank(account.minecraftAccessToken)) {
            return PreLaunchMicrosoftSessionResult.warning(
                    "The active Microsoft account does not have a Minecraft access token. "
                            + "Open Settings and use Refresh Microsoft account/skin, or continue anyway for local play."
            );
        }

        return validateMinecraftProfileToken(account.minecraftAccessToken);
    }

    @Nullable
    private AccountStore.Account resolveMicrosoftAccountForPreflight(@Nullable AccountStore.Account account) {
        if (account != null && account.isMicrosoftAccount() && account.hasMinecraftSession()) {
            return account;
        }
        return null;
    }

    @NonNull
    private PreLaunchMicrosoftSessionResult validateMinecraftProfileToken(@NonNull String minecraftAccessToken) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL("https://api.minecraftservices.com/minecraft/profile");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Authorization", "Bearer " + minecraftAccessToken);
            connection.setRequestProperty("Accept", "application/json");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setUseCaches(false);

            int code = connection.getResponseCode();
            if (code >= 200 && code < 300) {
                return PreLaunchMicrosoftSessionResult.ok("Microsoft account session verified.");
            }

            String body = readHttpErrorBody(connection);
            String status = "HTTP " + code;
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED || code == HttpURLConnection.HTTP_FORBIDDEN) {
                return PreLaunchMicrosoftSessionResult.warning(
                        "The saved Minecraft session token looks expired or rejected (" + status + "). "
                                + "Servers and Realms may fail until the account is refreshed."
                                + (isNullOrBlank(body) ? "" : "\n\nServer response: " + body)
                );
            }

            return PreLaunchMicrosoftSessionResult.warning(
                    "DroidBridge could not verify the saved Minecraft session before launch (" + status + "). "
                            + "You can continue, but multiplayer/Realms may not work correctly."
                            + (isNullOrBlank(body) ? "" : "\n\nServer response: " + body)
            );
        } catch (Throwable throwable) {
            return PreLaunchMicrosoftSessionResult.warning(
                    "DroidBridge could not contact Minecraft services to verify the saved session. "
                            + "You can continue for local play, but servers/Realms may fail if the token is expired.\n\n"
                            + "Issue: " + friendlyThrowableMessage(throwable)
            );
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    @NonNull
    private static String readHttpErrorBody(@NonNull HttpURLConnection connection) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                connection.getErrorStream() != null ? connection.getErrorStream() : connection.getInputStream(),
                StandardCharsets.UTF_8
        ))) {
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null && out.length() < 900) {
                if (out.length() > 0) out.append('\n');
                out.append(line);
            }
            return trimForDialog(out.toString(), 900);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private void showMicrosoftAccountPreflightWarningDialog(@NonNull PreLaunchMicrosoftSessionResult result) {
        if (exiting || isFinishing() || isDestroyed()) {
            return;
        }

        configureWindow();

        String message = "DroidBridge checked the saved Microsoft/Minecraft session before launching and found a possible login problem. "
                + "This can cause servers or Realms to fail even if singleplayer still works.\n\n"
                + result.message
                + "\n\nYou can open Settings to use Refresh Microsoft account/skin, cancel launch, or continue anyway with the saved account.";

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Microsoft account needs attention")
                .setMessage(message)
                .setNegativeButton("Cancel launch", null)
                .setNeutralButton("Open Settings", null)
                .setPositiveButton("Continue anyway", null)
                .create();

        dialog.setOnShowListener(dialogInterface -> {
            styleDarkDialog(dialog);

            TextView negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (negative != null) {
                negative.setTextColor(COLOR_DANGER);
                negative.setOnClickListener(view -> {
                    microsoftAccountPreflightPending = false;
                    microsoftAccountPreflightCompleted = false;
                    microsoftAccountPreflightAcceptedAfterWarning = false;
                    appendStatus("Launch canceled so the Microsoft account can be refreshed first.");
                    dialog.dismiss();
                    configureWindow();
                });
            }

            TextView neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            if (neutral != null) {
                neutral.setTextColor(COLOR_ACCENT);
                neutral.setOnClickListener(view -> {
                    dialog.dismiss();
                    openAccountSettingsAfterMicrosoftPreflightWarning();
                });
            }

            TextView positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (positive != null) {
                positive.setTextColor(COLOR_ACCENT);
                positive.setOnClickListener(view -> {
                    microsoftAccountPreflightAcceptedAfterWarning = true;
                    microsoftAccountPreflightCompleted = true;
                    appendStatus("Continuing launch with the saved Microsoft session.");
                    dialog.dismiss();
                    continueLaunchAfterMicrosoftAccountPreflight();
                });
            }
        });

        dialog.setOnDismissListener(dialogInterface -> configureWindow());
        dialog.show();
    }

    private void continueLaunchAfterMicrosoftAccountPreflight() {
        if (exiting || launchStarted) return;
        new Thread(this::startLaunchOnce, "JVM Main thread").start();
    }

    private void openAccountSettingsAfterMicrosoftPreflightWarning() {
        try {
            Intent intent = new Intent(this, LauncherSettingsActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        } catch (Throwable throwable) {
            Logging.e("GameActivity", "Unable to open launcher settings after account preflight warning", throwable);
            appendStatus("Unable to open Settings: " + friendlyThrowableMessage(throwable));
            configureWindow();
            return;
        }

        exiting = true;
        finish();
    }

    @NonNull
    private static String friendlyThrowableMessage(@Nullable Throwable throwable) {
        if (throwable == null) return "Unknown error";
        String message = throwable.getMessage();
        if (!isNullOrBlank(message)) return trimForDialog(message, 700);
        return throwable.getClass().getSimpleName();
    }

    private static boolean isNullOrBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }

    @NonNull
    private static String trimForDialog(@Nullable String value, int maxLength) {
        if (value == null) return "";
        String cleaned = value.trim();
        if (cleaned.length() <= maxLength) return cleaned;
        return cleaned.substring(0, Math.max(0, maxLength - 1)).trim() + "…";
    }


    private boolean shouldPauseLaunchForModPreLaunchWarning() {
        if (modPreLaunchWarningPending || exiting) {
            return modPreLaunchWarningPending;
        }

        ModPreLaunchWarningManager.Warning warning = ModPreLaunchWarningManager.findPendingWarning(this, versionId);
        if (warning == null) {
            return false;
        }

        if (warning.ruleId.equals(acceptedModPreLaunchWarningRuleId)) {
            return false;
        }

        modPreLaunchWarningPending = true;
        runOnUiThread(() -> showModPreLaunchWarningDialog(warning));
        return true;
    }

    private void showModPreLaunchWarningDialog(@NonNull ModPreLaunchWarningManager.Warning warning) {
        if (isFinishing() || isDestroyed() || exiting) {
            modPreLaunchWarningPending = false;
            return;
        }

        ModPreLaunchWarningManager.recordShown(this, warning);
        configureWindow();

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        scrollView.setBackgroundColor(COLOR_DIALOG_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dpToPx(22), dpToPx(18), dpToPx(22), dpToPx(8));
        root.setBackgroundColor(COLOR_DIALOG_BG);
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView title = new TextView(this);
        title.setText(warning.title);
        title.setTextColor(COLOR_TEXT_PRIMARY);
        title.setTextSize(22f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, 0, 0, dpToPx(8));
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView summary = new TextView(this);
        summary.setText(warning.summary);
        summary.setTextColor(COLOR_TEXT_SECONDARY);
        summary.setTextSize(14f);
        summary.setPadding(0, 0, 0, dpToPx(12));
        root.addView(summary, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dpToPx(14), dpToPx(12), dpToPx(14), dpToPx(12));
        card.setBackground(roundedDrawable(COLOR_CARD_BG, COLOR_CARD_STROKE, 18));

        TextView cardTitle = new TextView(this);
        cardTitle.setText(warning.cardTitle);
        cardTitle.setTextColor(COLOR_TEXT_PRIMARY);
        cardTitle.setTextSize(16f);
        cardTitle.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(cardTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView cardBody = new TextView(this);
        cardBody.setText(warning.cardBody);
        cardBody.setTextColor(COLOR_TEXT_SECONDARY);
        cardBody.setTextSize(12.5f);
        cardBody.setPadding(0, dpToPx(4), 0, 0);
        card.addView(cardBody, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView reminder = new TextView(this);
        reminder.setText(warning.reminderText);
        reminder.setTextColor(COLOR_TEXT_MUTED);
        reminder.setTextSize(11.5f);
        reminder.setPadding(0, dpToPx(8), 0, 0);
        card.addView(reminder, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView detected = new TextView(this);
        detected.setText("Detected version: " + warning.versionText + "\nDetected in: " + warning.gameDirectory);
        detected.setTextColor(COLOR_TEXT_MUTED);
        detected.setTextSize(11.5f);
        detected.setPadding(0, dpToPx(8), 0, 0);
        card.addView(detected, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cardParams.setMargins(0, 0, 0, dpToPx(8));
        root.addView(card, cardParams);

        AlertDialog.Builder builder = new MaterialAlertDialogBuilder(this)
                .setView(scrollView)
                .setPositiveButton("I understand, launch", null)
                .setNegativeButton(android.R.string.cancel, null);

        if (warning.neutralButtonLabel != null && !warning.neutralButtonLabel.trim().isEmpty()) {
            builder.setNeutralButton(warning.neutralButtonLabel, null);
        }

        AlertDialog dialog = builder.create();

        dialog.setCanceledOnTouchOutside(false);
        dialog.setCancelable(false);
        dialog.setOnCancelListener(dialogInterface -> cancelLaunchFromModPreLaunchWarning());
        dialog.setOnShowListener(dialogInterface -> {
            styleDarkDialog(dialog);

            TextView positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (positive != null) {
                positive.setTextColor(COLOR_ACCENT);
                positive.setOnClickListener(view -> {
                    acceptedModPreLaunchWarningRuleId = warning.ruleId;
                    modPreLaunchWarningPending = false;
                    dialog.dismiss();
                    startLaunchAfterModPreLaunchWarning();
                });
            }

            TextView neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            if (neutral != null) {
                neutral.setTextColor(COLOR_ACCENT);
                neutral.setOnClickListener(view -> {
                    if (ModPreLaunchWarningManager.NEUTRAL_ACTION_VULKAN_EXTENSION_CHECKER.equals(warning.neutralAction)) {
                        openVulkanExtensionCheckerInstallLink();
                    }
                });
            }

            TextView negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (negative != null) {
                negative.setTextColor(COLOR_DANGER);
                negative.setOnClickListener(view -> {
                    dialog.dismiss();
                    cancelLaunchFromModPreLaunchWarning();
                });
            }
        });
        dialog.setOnDismissListener(dialogInterface -> configureWindow());
        dialog.show();
    }

    private void cancelLaunchFromModPreLaunchWarning() {
        modPreLaunchWarningPending = false;
        exiting = true;
        clearLaunchStatusOverlay();
        finish();
    }

    private void startLaunchAfterModPreLaunchWarning() {
        new Thread(this::startLaunchOnce, "JVM Main thread").start();
    }

    private void openVulkanExtensionCheckerInstallLink() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(VULKAN_EXTENSION_CHECKER_INSTALL_URL));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Throwable throwable) {
            Logging.e("GameActivity", "Unable to open Vulkan Extension Checker install link", throwable);
            appendStatus("Unable to open Vulkan Extension Checker install link.");
        }
    }

    private static final class PreLaunchMicrosoftSessionResult {
        final boolean ok;
        @NonNull final String message;

        private PreLaunchMicrosoftSessionResult(boolean ok, @NonNull String message) {
            this.ok = ok;
            this.message = message;
        }

        @NonNull
        static PreLaunchMicrosoftSessionResult ok(@NonNull String message) {
            return new PreLaunchMicrosoftSessionResult(true, message);
        }

        @NonNull
        static PreLaunchMicrosoftSessionResult warning(@NonNull String message) {
            return new PreLaunchMicrosoftSessionResult(false, message);
        }
    }

    private boolean shouldPauseLaunchForReplayModFfmpegWarning() {
        if (replayModFfmpegWarningAccepted || replayModFfmpegWarningPending || exiting) {
            return replayModFfmpegWarningPending;
        }

        ReplayModFfmpegPreflight preflight = checkReplayModFfmpegPreflight();
        if (!preflight.shouldWarn) {
            return false;
        }

        replayModFfmpegWarningPending = true;
        runOnUiThread(() -> showReplayModFfmpegMissingDialog(preflight));
        return true;
    }

    @NonNull
    private ReplayModFfmpegPreflight checkReplayModFfmpegPreflight() {
        try {
            File gameDirectory = findReplayModGameDirectoryForPreflight();
            if (gameDirectory == null) {
                Logging.i("GameActivity", "Replay Mod FFmpeg preflight skipped; Replay Mod was not found in the current launch instance.");
                return ReplayModFfmpegPreflight.noWarning();
            }

            FFmpegPluginCompat.Result ffmpeg = FFmpegPluginCompat.discoverForReplayMod(this, gameDirectory);
            if (!ffmpeg.replayModPresent) {
                Logging.i("GameActivity", "Replay Mod FFmpeg preflight skipped; current instance does not contain Replay Mod: "
                        + gameDirectory.getAbsolutePath());
                return ReplayModFfmpegPreflight.noWarning();
            }

            if (ffmpeg.available) {
                return ReplayModFfmpegPreflight.noWarning();
            }

            return ReplayModFfmpegPreflight.warning(
                    gameDirectory,
                    ffmpeg.errorMessage != null ? ffmpeg.errorMessage : "DroidBridge FFmpeg plugin is not installed."
            );
        } catch (Throwable throwable) {
            Logging.e("GameActivity", "Replay Mod FFmpeg preflight check failed", throwable);
            return ReplayModFfmpegPreflight.noWarning();
        }
    }

    @Nullable
    private File findReplayModGameDirectoryForPreflight() {
        LinkedHashSet<File> candidates = new LinkedHashSet<>();

        try {
            File home = new File(PathManager.DIR_MINECRAFT_HOME);

            // Do not scan the shared .minecraft folder or every instance here.
            // This warning should only appear for the instance the user is launching.
            // If DIR_MINECRAFT_HOME already points at the current instance/game folder,
            // allow it. Otherwise only inspect instance folders whose name matches versionId.
            if (isCurrentLaunchDirectoryForPreflight(home)) {
                addReplayModDirectoryCandidates(candidates, home);
            }

            File parent = home.getParentFile();
            if (parent != null && isCurrentLaunchDirectoryForPreflight(parent)) {
                addReplayModDirectoryCandidates(candidates, parent);
            }

            File grandParent = parent != null ? parent.getParentFile() : null;
            if (grandParent != null && isCurrentLaunchDirectoryForPreflight(grandParent)) {
                addReplayModDirectoryCandidates(candidates, grandParent);
            }

            addReplayModInstanceCandidates(candidates, new File(home, "instances"));
            if (parent != null) addReplayModInstanceCandidates(candidates, new File(parent, "instances"));
            if (grandParent != null) addReplayModInstanceCandidates(candidates, new File(grandParent, "instances"));

            for (File candidate : candidates) {
                if (candidate != null && FFmpegPluginCompat.hasReplayMod(candidate)) {
                    return candidate;
                }
            }
        } catch (Throwable throwable) {
            Logging.e("GameActivity", "Unable to resolve Replay Mod game directory for FFmpeg preflight", throwable);
        }

        return null;
    }

    private boolean isCurrentLaunchDirectoryForPreflight(@Nullable File directory) {
        if (directory == null) return false;
        String normalizedVersionId = normalizeReplayPreflightName(versionId);
        if (normalizedVersionId.isEmpty()) return false;
        return normalizeReplayPreflightName(directory.getAbsolutePath()).contains(normalizedVersionId);
    }

    private void addReplayModDirectoryCandidates(@NonNull LinkedHashSet<File> out, @Nullable File directory) {
        if (directory == null) return;
        out.add(directory);
        out.add(new File(directory, "game"));
        out.add(new File(directory, ".minecraft"));
    }

    private void addReplayModInstanceCandidates(
            @NonNull LinkedHashSet<File> out,
            @Nullable File instancesDirectory
    ) {
        if (instancesDirectory == null || !instancesDirectory.isDirectory()) return;

        File[] instances = instancesDirectory.listFiles();
        if (instances == null) return;

        String normalizedVersionId = normalizeReplayPreflightName(versionId);
        if (normalizedVersionId.isEmpty()) return;

        for (File instance : instances) {
            if (instance == null || !instance.isDirectory()) continue;

            String normalizedName = normalizeReplayPreflightName(instance.getName());
            boolean matchesCurrentVersion = normalizedName.equals(normalizedVersionId)
                    || normalizedName.contains(normalizedVersionId)
                    || normalizedVersionId.contains(normalizedName);
            if (!matchesCurrentVersion) continue;

            addReplayModDirectoryCandidates(out, instance);
            addReplayModDirectoryCandidates(out, new File(instance, "game"));
        }
    }

    @NonNull
    private String normalizeReplayPreflightName(@Nullable String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private void showReplayModFfmpegMissingDialog(@NonNull ReplayModFfmpegPreflight preflight) {
        if (isFinishing() || isDestroyed() || exiting) {
            replayModFfmpegWarningPending = false;
            return;
        }

        configureWindow();

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        scrollView.setBackgroundColor(COLOR_DIALOG_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dpToPx(22), dpToPx(18), dpToPx(22), dpToPx(8));
        root.setBackgroundColor(COLOR_DIALOG_BG);
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView title = new TextView(this);
        title.setText("Replay Mod FFmpeg missing");
        title.setTextColor(COLOR_TEXT_PRIMARY);
        title.setTextSize(22f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, 0, 0, dpToPx(8));
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView summary = new TextView(this);
        summary.setText("Replay Mod was detected, but the DroidBridge FFmpeg plugin is not installed or could not be found. Replay Mod can still open without it, but video rendering/export may fail until FFmpeg is installed.");
        summary.setTextColor(COLOR_TEXT_SECONDARY);
        summary.setTextSize(14f);
        summary.setPadding(0, 0, 0, dpToPx(12));
        root.addView(summary, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dpToPx(14), dpToPx(12), dpToPx(14), dpToPx(12));
        card.setBackground(roundedDrawable(COLOR_CARD_BG, COLOR_CARD_STROKE, 18));

        TextView cardTitle = new TextView(this);
        cardTitle.setText("What this means");
        cardTitle.setTextColor(COLOR_TEXT_PRIMARY);
        cardTitle.setTextSize(16f);
        cardTitle.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(cardTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView cardBody = new TextView(this);
        cardBody.setText("Install the FFmpeg companion APK, then launch this instance again. If you just installed it, Android may require reopening the launcher before package discovery updates.");
        cardBody.setTextColor(COLOR_TEXT_SECONDARY);
        cardBody.setTextSize(12.5f);
        cardBody.setPadding(0, dpToPx(4), 0, 0);
        card.addView(cardBody, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        if (preflight.gameDirectory != null) {
            TextView path = new TextView(this);
            path.setText("Detected in: " + preflight.gameDirectory.getAbsolutePath());
            path.setTextColor(COLOR_TEXT_MUTED);
            path.setTextSize(11.5f);
            path.setPadding(0, dpToPx(8), 0, 0);
            card.addView(path, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
        }

        if (preflight.errorMessage != null && !preflight.errorMessage.trim().isEmpty()) {
            TextView error = new TextView(this);
            error.setText("Discovery status: " + preflight.errorMessage);
            error.setTextColor(COLOR_TEXT_MUTED);
            error.setTextSize(11.5f);
            error.setPadding(0, dpToPx(6), 0, 0);
            card.addView(error, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
        }

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cardParams.setMargins(0, 0, 0, dpToPx(8));
        root.addView(card, cardParams);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(scrollView)
                .setPositiveButton("Launch anyway", null)
                .setNeutralButton("Get FFmpeg plugin", null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        dialog.setCanceledOnTouchOutside(false);
        dialog.setCancelable(false);
        dialog.setOnCancelListener(dialogInterface -> cancelLaunchFromReplayModFfmpegWarning());
        dialog.setOnShowListener(dialogInterface -> {
            styleDarkDialog(dialog);

            TextView positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (positive != null) {
                positive.setTextColor(COLOR_ACCENT);
                positive.setOnClickListener(view -> {
                    replayModFfmpegWarningAccepted = true;
                    replayModFfmpegWarningPending = false;
                    dialog.dismiss();
                    startLaunchAfterReplayModFfmpegWarning();
                });
            }

            TextView neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            if (neutral != null) {
                neutral.setTextColor(COLOR_ACCENT);
                neutral.setOnClickListener(view -> openReplayModFfmpegInfoUrl());
            }

            TextView negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (negative != null) {
                negative.setTextColor(COLOR_DANGER);
                negative.setOnClickListener(view -> {
                    dialog.dismiss();
                    cancelLaunchFromReplayModFfmpegWarning();
                });
            }
        });
        dialog.setOnDismissListener(dialogInterface -> configureWindow());
        dialog.show();
    }

    private void cancelLaunchFromReplayModFfmpegWarning() {
        replayModFfmpegWarningPending = false;
        exiting = true;
        clearLaunchStatusOverlay();
        finish();
    }

    private void startLaunchAfterReplayModFfmpegWarning() {
        new Thread(this::startLaunchOnce, "JVM Main thread").start();
    }

    private void openReplayModFfmpegInfoUrl() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(REPLAY_MOD_FFMPEG_INFO_URL));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Throwable throwable) {
            Logging.e("GameActivity", "Unable to open Replay Mod FFmpeg info URL", throwable);
            appendStatus("Unable to open FFmpeg plugin link.");
        }
    }

    private static final class ReplayModFfmpegPreflight {
        final boolean shouldWarn;
        @Nullable final File gameDirectory;
        @Nullable final String errorMessage;

        private ReplayModFfmpegPreflight(
                boolean shouldWarn,
                @Nullable File gameDirectory,
                @Nullable String errorMessage
        ) {
            this.shouldWarn = shouldWarn;
            this.gameDirectory = gameDirectory;
            this.errorMessage = errorMessage;
        }

        @NonNull
        static ReplayModFfmpegPreflight noWarning() {
            return new ReplayModFfmpegPreflight(false, null, null);
        }

        @NonNull
        static ReplayModFfmpegPreflight warning(
                @Nullable File gameDirectory,
                @Nullable String errorMessage
        ) {
            return new ReplayModFfmpegPreflight(true, gameDirectory, errorMessage);
        }
    }

    private void appendStatus(@NonNull String text) {
        if (binding == null) return;
        if (gameRenderingStarted) {
            hideLaunchStatusOverlay();
            return;
        }

        CharSequence existing = binding.textStatus.getText();
        String prefix = existing == null || existing.length() == 0 ? "" : existing + "\n";
        binding.textStatus.setText(prefix + text);
    }

    private void scheduleLaunchStatusAutoHide() {
        cancelLaunchStatusAutoHide();
        launchStatusAutoHideRunnable = () -> {
            if (binding == null || exiting || !launchStarted || gameRenderingStarted) return;
            hideLaunchStatusOverlay();
        };
        launchStatusHandler.postDelayed(launchStatusAutoHideRunnable, 45000L);
    }

    private void cancelLaunchStatusAutoHide() {
        if (launchStatusAutoHideRunnable != null) {
            launchStatusHandler.removeCallbacks(launchStatusAutoHideRunnable);
            launchStatusAutoHideRunnable = null;
        }
    }

    private void hideLaunchStatusOverlay() {
        cancelLaunchStatusAutoHide();
        if (binding == null) return;
        binding.textStatus.setText("");
        binding.textStatus.setVisibility(View.GONE);
    }

    private void clearLaunchStatusOverlay() {
        cancelLaunchStatusAutoHide();
        if (binding == null) return;
        binding.textStatus.setText("");
        binding.textStatus.setVisibility(View.GONE);
    }

    private void finishAfterExit() {
        exiting = true;
        getWindow().getDecorView().postDelayed(this::finish, 1000);
    }

    private boolean shouldForceAynThorDualScreenFullscreen() {
        return AynThorDisplayCompat.isAynThorBuild()
                && dualScreenController != null
                && dualScreenController.isShowing();
    }

    private void configureWindow() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED);

        // Keep all fullscreen/notch/cutout behavior in one helper. This applies:
        // - immersive fullscreen flags
        // - WindowCompat-style decor fitting behavior on Android 11+
        // - transparent system bars
        // - LAYOUT_IN_DISPLAY_CUTOUT_MODE_* from LauncherPreferences
        // The Thor requires an edge-to-edge host window whenever a real Presentation
        // is active, even if the global Force Fullscreen preference is off. Keeping
        // this runtime-only avoids coupling the two saved settings or affecting phones.
        FullscreenUtils.enableImmersive(this, shouldForceAynThorDualScreenFullscreen());

        // Force Fullscreen controls the host window's edge-to-edge geometry. The
        // separate Ignore display notch preference is enforced below as a physical
        // safe inset, so it must not change this window-size decision.
        boolean layoutBehindSystemBars = shouldForceAynThorDualScreenFullscreen()
                || LauncherPreferences.isForceFullscreenMode(this);
        View decorView = getWindow().getDecorView();
        if (decorView != null) {
            decorView.setFitsSystemWindows(!layoutBehindSystemBars);
            decorView.setClipToOutline(false);
            try {
                decorView.requestApplyInsets();
            } catch (Throwable ignored) {
            }
        }

        if (binding != null) {
            View root = binding.getRoot();
            root.setFitsSystemWindows(!layoutBehindSystemBars);
            root.setClipToOutline(false);
            if (minecraftSurface != null) {
                minecraftSurface.setFitsSystemWindows(!layoutBehindSystemBars);
                minecraftSurface.setClipToOutline(false);
            }
        }
    }

    private void applyGameDisplaySurfaceOptions() {
        if (binding == null) return;

        View root = binding.getRoot();
        boolean layoutBehindSystemBars = shouldForceAynThorDualScreenFullscreen()
                || LauncherPreferences.isForceFullscreenMode(this);
        root.setFitsSystemWindows(!layoutBehindSystemBars);
        root.setClipToOutline(false);

        if (minecraftSurface != null) {
            minecraftSurface.setFitsSystemWindows(!layoutBehindSystemBars);
            minecraftSurface.setClipToOutline(false);
        }

        if (root instanceof ViewGroup) {
            ViewGroup rootGroup = (ViewGroup) root;
            rootGroup.setClipChildren(false);
            rootGroup.setClipToPadding(false);
        }

        // "Ignore display notch" means treat the camera cutout as unavailable game
        // space. Android 15/16 can force edge-to-edge even when the legacy cutout
        // attribute says NEVER, so enforce the actual DisplayCutout safe inset on
        // the shared game root. Force Fullscreen remains independent.
        boolean ignoreDisplayCutout = LauncherPreferences.isIgnoreDisplayCutout(this);
        boolean avoidRoundedCorners = LauncherPreferences.isAvoidRoundedDisplayCorners(this);
        int roundedCornerInset = avoidRoundedCorners ? dpToPx(10) : 0;
        applyDisplayCutoutSafeArea(root, ignoreDisplayCutout, roundedCornerInset);

        try {
            root.requestApplyInsets();
            if (minecraftSurface != null) minecraftSurface.requestApplyInsets();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Applies the physical display-cutout safe rectangle to the whole game root without
     * asking Android to resize the native game window. Keeping the adjustment on the root
     * means Minecraft, the touch controls, the software cursor and launcher overlays all
     * share exactly the same coordinate space.
     *
     * <p>When Ignore display notch is ON, the physical camera cutout is excluded from
     * the usable game/control rectangle. When it is OFF, no cutout padding is added.</p>
     */
    private void applyDisplayCutoutSafeArea(
            @NonNull View root,
            boolean ignoreDisplayCutout,
            int roundedCornerInset
    ) {
        // Android 15/16 may enforce edge-to-edge and SDL can change system-UI flags
        // after GameActivity starts. Apply the real cutout only when the toggle is ON.
        // A rounded-corner margin may still require the listener when notch avoidance
        // itself is OFF.
        if (!ignoreDisplayCutout && roundedCornerInset <= 0) {
            root.setOnApplyWindowInsetsListener(null);
            root.setPadding(0, 0, 0, 0);
            reapplyCenteredPortraitLayoutAfterInsets();
            return;
        }

        final View.OnApplyWindowInsetsListener listener = (view, insets) -> {
            applyPhysicalDisplaySafeInsets(view, insets, ignoreDisplayCutout, roundedCornerInset);
            // Never consume the insets. IME/navigation-aware children still need the
            // original object, and the touch editor uses the same game-root geometry.
            return insets;
        };
        root.setOnApplyWindowInsetsListener(listener);

        // Keep the rounded-corner preference useful before the first inset dispatch.
        root.setPadding(
                roundedCornerInset,
                roundedCornerInset,
                roundedCornerInset,
                roundedCornerInset
        );

        // requestApplyInsets() can be ignored if SDL changes the decor flags in the
        // same frame. Apply any already-known root insets now and then request a fresh
        // dispatch on the next frame as well.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                WindowInsets current = root.getRootWindowInsets();
                if (current != null) {
                    applyPhysicalDisplaySafeInsets(root, current, ignoreDisplayCutout, roundedCornerInset);
                }
            } catch (Throwable ignored) {
            }
        }
        root.post(() -> {
            try {
                WindowInsets current = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? root.getRootWindowInsets() : null;
                if (current != null) {
                    applyPhysicalDisplaySafeInsets(root, current, ignoreDisplayCutout, roundedCornerInset);
                }
                root.requestApplyInsets();
            } catch (Throwable ignored) {
            }
        });
    }

    private void applyPhysicalDisplaySafeInsets(
            @NonNull View view,
            @Nullable WindowInsets insets,
            boolean ignoreDisplayCutout,
            int roundedCornerInset
    ) {
        int rawLeft = 0;
        int rawTop = 0;
        int rawRight = 0;
        int rawBottom = 0;

        if (ignoreDisplayCutout && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && insets != null) {
            try {
                DisplayCutout cutout = insets.getDisplayCutout();
                if (cutout != null) {
                    rawLeft = Math.max(rawLeft, cutout.getSafeInsetLeft());
                    rawTop = Math.max(rawTop, cutout.getSafeInsetTop());
                    rawRight = Math.max(rawRight, cutout.getSafeInsetRight());
                    rawBottom = Math.max(rawBottom, cutout.getSafeInsetBottom());
                }
            } catch (Throwable ignored) {
            }
        }

        if (ignoreDisplayCutout && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && insets != null) {
            try {
                android.graphics.Insets cutoutInsets = insets.getInsets(WindowInsets.Type.displayCutout());
                rawLeft = Math.max(rawLeft, cutoutInsets.left);
                rawTop = Math.max(rawTop, cutoutInsets.top);
                rawRight = Math.max(rawRight, cutoutInsets.right);
                rawBottom = Math.max(rawBottom, cutoutInsets.bottom);
            } catch (Throwable ignored) {
            }
        }

        // If PhoneWindow/OEM decor fitting has already moved or shrunk the app root
        // away from an unsafe edge, subtract that amount. This prevents double-insetting
        // on Android versions that still honor LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER while
        // retaining the manual safety net required by Android 15/16 edge-to-edge.
        int occupiedLeft = 0;
        int occupiedTop = 0;
        int occupiedRight = 0;
        int occupiedBottom = 0;
        try {
            View decor = getWindow() == null ? null : getWindow().getDecorView();
            if (decor != null && decor.getWidth() > 0 && decor.getHeight() > 0
                    && view.getWidth() > 0 && view.getHeight() > 0) {
                int[] viewLocation = new int[2];
                int[] decorLocation = new int[2];
                view.getLocationOnScreen(viewLocation);
                decor.getLocationOnScreen(decorLocation);
                occupiedLeft = Math.max(0, viewLocation[0] - decorLocation[0]);
                occupiedTop = Math.max(0, viewLocation[1] - decorLocation[1]);
                occupiedRight = Math.max(0,
                        decor.getWidth() - occupiedLeft - view.getWidth());
                occupiedBottom = Math.max(0,
                        decor.getHeight() - occupiedTop - view.getHeight());
            }
        } catch (Throwable ignored) {
        }

        int left = Math.max(roundedCornerInset, Math.max(0, rawLeft - occupiedLeft));
        int top = Math.max(roundedCornerInset, Math.max(0, rawTop - occupiedTop));
        int right = Math.max(roundedCornerInset, Math.max(0, rawRight - occupiedRight));
        int bottom = Math.max(roundedCornerInset, Math.max(0, rawBottom - occupiedBottom));

        boolean paddingChanged = view.getPaddingLeft() != left
                || view.getPaddingTop() != top
                || view.getPaddingRight() != right
                || view.getPaddingBottom() != bottom;
        if (paddingChanged) {
            view.setPadding(left, top, right, bottom);
            reapplyCenteredPortraitLayoutAfterInsets();
        }

        LauncherLogManager.append(
                "DroidBridgeCutout: ignoreNotch=" + ignoreDisplayCutout
                        + " raw=" + rawLeft + "," + rawTop + "," + rawRight + "," + rawBottom
                        + " alreadySafe=" + occupiedLeft + "," + occupiedTop + ","
                        + occupiedRight + "," + occupiedBottom
                        + " applied=" + left + "," + top + "," + right + "," + bottom
                        + " forceFullscreen=" + LauncherPreferences.isForceFullscreenMode(this)
                        + " sdk=" + Build.VERSION.SDK_INT
        );
    }

    private void reapplyCenteredPortraitLayoutAfterInsets() {
        if (!shouldUseCenteredPortraitGameViewport() || minecraftSurface == null) return;
        minecraftSurface.post(() -> applyGameSurfaceLayoutMode(minecraftSurface));
    }

    @Override
    protected void onStart() {
        super.onStart();
        DroidBridgeSDL3Bootstrap.onStart(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Recover from a transient localhost bind race or a dead proxy thread.
        // This is intentionally safe to call repeatedly.
        startDroidBridgeAndroidMicProxy();
        DroidBridgeSDL3Bootstrap.onResume(this);
        if (minecraftSurface != null) {
            minecraftSurface.resumeVulkanPresentationIfSurfaceReady("GameActivity onResume");
        }
        boolean preserveLiveSdlSurface = DroidBridgeSDL3Bootstrap.isRequested() && launchStarted;

        // Reapplying orientation, window flags and three delayed surface refreshes
        // after a screenshot/share Activity returns can invalidate an active Android
        // Vulkan surface. The SDL window is already correctly configured; preserve
        // it and refresh only launcher input/overlays.
        if (!preserveLiveSdlSurface) {
            AppOrientationHelper.applyToGameActivity(this);
            configureWindow();
        }
        applySustainedPerformanceMode();
        configureInputBridgeForVersion(versionId);
        CallbackBridge.setInputReady(true);
        CallbackBridge.ensureInputFocus();
        if (gyroInputController != null) {
            gyroInputController.refreshFromPreferences();
        }
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        if (minecraftSurface != null && DroidBridgeSDL3Bootstrap.isRequested()) {
            minecraftSurface.recenterCursorForCurrentMode();
        }
        reconcileDualScreenRuntimeFromPreferences();
        refreshTouchControlsOverlay();

        if (floatingGameSettingsOverlayController != null) {
            floatingGameSettingsOverlayController.resume();
        }

        if (!preserveLiveSdlSurface) {
            scheduleMinecraftSurfaceRefresh();
        } else {
            LauncherLogManager.append(
                    "DroidBridgeSDL3: onResume preserved live Surface; skipped orientation/window/resize refresh");
        }
        startLiveVsyncRefreshMonitor();
        startLiveVsyncPulse();
        syncMinecraftVsyncFromOptions(true, "onResume");
        if (!preserveLiveSdlSurface) {
            updateLiveVsyncTargetFromDisplay("onResume");
        }
        if (!droidBridgeAndroidTtsUnavailable && !droidBridgeAndroidTtsReady && !droidBridgeAndroidTtsInitializing) {
            initializeDroidBridgeAndroidNarratorTts(true);
        }
    }
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        DroidBridgeSDL3Bootstrap.onWindowFocusChanged(hasFocus);
        if (!hasFocus) {
            setVulkanPresentationPausedFromLifecycle(true, "GameActivity window focus lost");
        } else if (minecraftSurface != null) {
            minecraftSurface.resumeVulkanPresentationIfSurfaceReady(
                    "GameActivity window focus restored");
        }

        if (hasFocus) {
            applySustainedPerformanceMode();
            boolean preserveLiveSdlSurface = DroidBridgeSDL3Bootstrap.isRequested() && launchStarted;
            if (!preserveLiveSdlSurface) {
                configureWindow();
                scheduleMinecraftSurfaceRefresh();
                scheduleLiveVsyncTargetRefresh("windowFocus");
            } else {
                LauncherLogManager.append(
                        "DroidBridgeSDL3: window focus restored without touching live Surface");
            }
            if (!droidBridgeAndroidTtsReady && !droidBridgeAndroidTtsInitializing) {
                initializeDroidBridgeAndroidNarratorTts(true);
            }
        }
    }


    @Override
    protected void onPause() {
        setVulkanPresentationPausedFromLifecycle(true, "GameActivity onPause");
        DroidBridgeSDL3Bootstrap.onPause();
        if (dualScreenController != null) {
            dualScreenController.onPause();
        }

        if (floatingGameSettingsOverlayController != null) {
            floatingGameSettingsOverlayController.pause();
        }
        stopLiveVsyncPulse();
        if (gyroInputController != null) {
            gyroInputController.stop();
        }
        CallbackBridge.clearInputFocus();
        super.onPause();
    }

    @Override
    protected void onStop() {
        // Screen capture is scoped to the running DroidBridge game experience. If the game
        // leaves the foreground, finalize immediately instead of allowing a foreground
        // recorder to outlive Minecraft and strand an IS_PENDING MediaStore entry.
        if (GameRecordingService.isRecordingOrStarting()) {
            LauncherLogManager.append("RecordingLifecycle: GameActivity onStop -> finalize recording");
            GameRecordingService.stop(this);
            applyRecordingTouchControlVisibility(false);
        }
        setVulkanPresentationPausedFromLifecycle(true, "GameActivity onStop");
        DroidBridgeSDL3Bootstrap.onStop();
        super.onStop();
    }

    private boolean isBetterThanAdventureLaunch() {
        String id = versionId == null ? "" : versionId.trim().toLowerCase(Locale.ROOT);
        // Instance display names can be "BTA (v7.3)" while the base version is
        // "bta-v7.3". Treat any BTA-prefixed display/base id as BTA so the
        // Android controller bridge is enabled for Better Than Adventure.
        return id.startsWith("bta")
                || id.contains("betterthanadventure")
                || id.contains("better-than-adventure")
                || id.contains("better_than_adventure");
    }

    private boolean shouldRouteGamepadToMinecraftFallback() {
        if (!ControllerModCompat.shouldSuppressLauncherGamepadInput() || MinecraftGLSurface.sdlEnabled) {
            return false;
        }

        if (!isLegacy4JGlfwFallbackLikelyActive()) {
            return false;
        }

        if (!legacy4jFallbackLogged) {
            legacy4jFallbackLogged = true;
            Logger.appendToLog("ControllerModCompat: allowing Legacy4J/GLFW fallback to receive gamepad input");
            Logging.i("GameActivity", "Allowing Legacy4J/GLFW fallback to receive gamepad input");
        }
        return true;
    }

    private boolean shouldConsumeLauncherGamepadInput() {
        return ControllerModCompat.shouldSuppressLauncherGamepadInput() && !MinecraftGLSurface.sdlEnabled;
    }

    private boolean isLegacy4JGlfwFallbackLikelyActive() {
        if (legacy4jGlfwFallbackAllowed) {
            return true;
        }

        String id = versionId == null ? "" : versionId.toLowerCase(Locale.ROOT);
        if (id.contains("legacy4j") || id.equals("legacy") || id.contains("legacy")) {
            legacy4jGlfwFallbackAllowed = true;
            return true;
        }

        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastLegacy4jFallbackProbeMs < 1000L) {
            return false;
        }
        lastLegacy4jFallbackProbeMs = now;

        try {
            File logFile = getReadableLatestLogFile();
            if (logFile == null || !logFile.isFile()) {
                return false;
            }

            String tail = readLogTail(logFile, 96 * 1024).toLowerCase(Locale.ROOT);
            boolean detected = tail.contains("controllermodcompat: legacy4j detected")
                    || tail.contains("sdl3 (isxander's libsdl4j)")
                    || tail.contains("glfw will be used instead")
                    || tail.contains("\n\t- legacy ")
                    || tail.contains("\n- legacy ")
                    || tail.contains(" legacy 1.");

            if (detected) {
                legacy4jGlfwFallbackAllowed = true;
                return true;
            }
        } catch (Throwable throwable) {
            Logging.e("GameActivity", "Unable to inspect Legacy4J controller fallback status", throwable);
        }

        return false;
    }

    private void logBtaGamepadMapperOnce() {
        if (btaGamepadMapperLogged || !isBetterThanAdventureLaunch()) return;
        btaGamepadMapperLogged = true;
        Logger.appendToLog("BTA controller route: using BTA native virtual controller bridge with isolated LWJGL2 queue");
        Logging.i("GameActivity", "BTA controller route: using BTA native virtual controller bridge");
    }

    @Override
    public boolean dispatchGenericMotionEvent(@NonNull MotionEvent event) {
        InputEventDiagnosticLogger.logMotionButtonEvent(
                "GameActivity.dispatchGenericMotionEvent",
                event
        );

        // Minecraft 26.3+ uses SDL for physical mouse input. Joystick motion is
        // routed here only when Controlify owns SDL controller input; otherwise
        // DroidBridge's virtual cursor mapper handles it below.
        if (DroidBridgeSDL3Bootstrap.routeGenericMotionEvent(event)) {
            CallbackBridge.setInputReady(true);
            return true;
        }

        if (isBetterThanAdventureLaunch()
                && event.getActionMasked() == MotionEvent.ACTION_MOVE
                && GamepadInputController.feedBtaNativeControllerMotion(event)) {
            CallbackBridge.setInputReady(true);
            logBtaGamepadMapperOnce();
            return true;
        }

        if (isGamepadMotionEvent(event)) {
            if (isBetterThanAdventureLaunch()) {
                CallbackBridge.setInputReady(true);
                logBtaGamepadMapperOnce();
                if (GamepadInputController.feedBtaNativeControllerMotion(event)) {
                    return true;
                }
            }

            if (ControllerModCompat.shouldMirrorAndroidGamepadToGlfw()) {
                CallbackBridge.setInputReady(true);
                GamepadInputController.feedGlfwGamepadMirrorMotion(event);
                if (!MinecraftGLSurface.sdlEnabled) {
                    // Legacy GLFW controller mods own the real controller input here.
                    // DroidBridge only mirrors state into GLFW, then consumes the
                    // Android event so it cannot also drive the launcher mouse/camera.
                    return true;
                }
            }

            /*
             * Controllable handles controller discovery/input inside the Minecraft JVM.
             * Do not let Android focus navigation or DroidBridge's virtual mouse overlay
             * see joystick motion. Returning true here stops the "focus jumps / top-right
             * cursor drift" issue when the native controller moves.
             */
            if (shouldRouteGamepadToMinecraftFallback()) {
                CallbackBridge.setInputReady(true);
                return super.dispatchGenericMotionEvent(event);
            }

            if (shouldConsumeLauncherGamepadInput()) {
                return true;
            }

            CallbackBridge.setInputReady(true);

            if (MinecraftGLSurface.sdlEnabled
                    && (event.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
                try {
                    if (SDLControllerManager.handleJoystickMotionEvent(event)) {
                        return true;
                    }
                } catch (UnsatisfiedLinkError e) {
                    Logger.appendToLog(
                            "SDL controller routing disabled: SDLControllerManager native glue is missing: "
                                    + e.getMessage()
                    );
                    MinecraftGLSurface.sdlEnabled = false;
                    return true;
                } catch (Throwable t) {
                    Logger.appendToLog(
                            "SDL controller routing disabled after controller motion failure: "
                                    + t.getClass().getName() + ": " + t.getMessage()
                    );
                    MinecraftGLSurface.sdlEnabled = false;
                    return true;
                }
            }

            if (isBetterThanAdventureLaunch()) {
                logBtaGamepadMapperOnce();
            }

            if (gamepadInputController != null && gamepadInputController.handleMotionEvent(event)) {
                return true;
            }
        }

        CallbackBridge.setInputReady(true);
        return super.dispatchGenericMotionEvent(event);
    }

    /**
     * Registers an explicit Activity back callback so Android 13+ predictive back,
     * gesture navigation, OEM navigation buttons, and legacy onBackPressed() all use
     * the same launcher-selected action instead of falling through to Activity finish.
     */
    private void installAndroidBackDispatcher() {
        androidBackPressedCallback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleAndroidNavigationBack("dispatcher");
            }
        };
        getOnBackPressedDispatcher().addCallback(this, androidBackPressedCallback);
    }

    private void handleAndroidNavigationBack(@NonNull String origin) {
        if (exiting || isFinishing() || isDestroyed()) {
            return;
        }

        long now = SystemClock.uptimeMillis();
        if (now - lastAndroidBackHandledUptimeMs < ANDROID_BACK_DUPLICATE_WINDOW_MS) {
            LauncherLogManager.append("GameActivity: duplicate Android Back ignored origin=" + origin);
            return;
        }
        lastAndroidBackHandledUptimeMs = now;

        // A launcher dialog or editor owns Back before the global gameplay action.
        // This also prevents predictive Back from finishing GameActivity behind a dialog.
        if (inGameControlsEditorDialog != null && inGameControlsEditorDialog.isShowing()) {
            LauncherLogManager.append("GameActivity: Android Back closing touch editor origin=" + origin);
            inGameControlsEditorDialog.onBackPressed();
            return;
        }
        if (inGameControlsDialog != null && inGameControlsDialog.isShowing()) {
            LauncherLogManager.append("GameActivity: Android Back closing launcher menu origin=" + origin);
            dismissDialog(inGameControlsDialog);
            return;
        }

        String action = LauncherPreferences.getAndroidBackButtonAction(this);
        LauncherLogManager.append("GameActivity: Android Back handled origin=" + origin
                + " action=" + action);
        if (LauncherPreferences.ANDROID_BACK_ACTION_LAUNCHER_MENU.equals(action)) {
            openInGameLauncherMenuFromBackShortcut();
        } else if (LauncherPreferences.ANDROID_BACK_ACTION_DISABLED.equals(action)) {
            // Consume the gesture/key and restore immersive flags. Never call super.
            configureWindow();
        } else {
            sendAndroidBackPauseTap();
        }
    }

    private void markAndroidBackKeyHandled(@NonNull KeyEvent event, @NonNull String action) {
        if (event.isCanceled()) return;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                // Mark DOWN immediately so an OEM cannot also deliver an Activity
                // back callback for the same physical press.
                lastAndroidBackHandledUptimeMs = SystemClock.uptimeMillis();
            }
            return;
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            lastAndroidBackHandledUptimeMs = SystemClock.uptimeMillis();
            LauncherLogManager.append("GameActivity: Android Back key handled action=" + action);
        }
    }

    /**
     * Routes a real keyboard Escape into Minecraft before Android can interpret it
     * as navigation Back. This also covers OEM/Bluetooth keyboards that expose the
     * physical Escape key as KEYCODE_BACK.
     */
    private boolean routePhysicalKeyboardEscapeToMinecraft(@NonNull KeyEvent event) {
        // Controller events must stay with GamepadInputController. Mixed HID devices
        // often advertise a keyboard source as well as GAMEPAD/DPAD.
        if (isGamepadKeyEvent(event)) return false;

        MinecraftGLSurface surface = minecraftSurface;
        if (surface == null) {
            return false;
        }

        if (inGameControlsDialog != null && inGameControlsDialog.isShowing()) {
            return false;
        }

        if (!MinecraftGLSurface.shouldRouteBackKeyToMinecraft(event)) {
            return false;
        }

        return surface.handleKeyEventFromActivity(event);
    }

    /**
     * Some Android/OEM mouse stacks synthesize KEYCODE_BACK for the secondary mouse
     * button. Let the surface consume or reconstruct that right click before the
     * launcher Back shortcut is considered.
     */
    private boolean routePointerBackToMinecraftMouse(@NonNull KeyEvent event) {
        // Never classify a controller B/Back event as a mouse secondary click.
        if (isGamepadKeyEvent(event)) return false;

        MinecraftGLSurface surface = minecraftSurface;
        if (surface == null || !surface.isPointerBackKeyEvent(event)) {
            return false;
        }

        // Never click through a launcher dialog into Minecraft. The event still
        // needs to be consumed so Android cannot interpret it as navigation Back.
        if (inGameControlsDialog != null && inGameControlsDialog.isShowing()) {
            return true;
        }
        return surface.handlePointerBackKeyFromActivity(event);
    }

    private boolean handleInGameControlsBackShortcut(@NonNull KeyEvent event) {
        if (!isInGameControlsBackShortcut(event)) {
            return false;
        }

        // Controller SELECT/MENU remains an emergency launcher-menu shortcut when the
        // normal on-screen shortcuts are hidden. The preference applies only to the
        // genuine Android navigation Back button/gesture.
        if (event.getKeyCode() != KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                openInGameLauncherMenuFromBackShortcut();
            }
            return true;
        }

        // Consume DOWN immediately. Resolve dialog/editor closing and configured
        // launcher actions on UP so one physical press cannot trigger twice.
        if (inGameControlsEditorDialog != null && inGameControlsEditorDialog.isShowing()) {
            markAndroidBackKeyHandled(event, "close_touch_editor");
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                inGameControlsEditorDialog.onBackPressed();
            }
            return true;
        }
        if (inGameControlsDialog != null && inGameControlsDialog.isShowing()) {
            markAndroidBackKeyHandled(event, "close_launcher_menu");
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                dismissDialog(inGameControlsDialog);
            }
            return true;
        }

        String action = LauncherPreferences.getAndroidBackButtonAction(this);
        if (LauncherPreferences.ANDROID_BACK_ACTION_DISABLED.equals(action)) {
            markAndroidBackKeyHandled(event, action);
            return true;
        }
        if (LauncherPreferences.ANDROID_BACK_ACTION_LAUNCHER_MENU.equals(action)) {
            markAndroidBackKeyHandled(event, action);
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                openInGameLauncherMenuFromBackShortcut();
            }
            return true;
        }

        boolean handled = routeAndroidBackToMinecraftPause(event);
        markAndroidBackKeyHandled(event, action);
        return handled;
    }

    private boolean routeAndroidBackToMinecraftPause(@NonNull KeyEvent event) {
        int eventAction = event.getAction();
        if (eventAction != KeyEvent.ACTION_DOWN && eventAction != KeyEvent.ACTION_UP) {
            return true;
        }
        if (eventAction == KeyEvent.ACTION_DOWN && event.getRepeatCount() > 0) {
            return true;
        }

        try {
            CallbackBridge.setInputReady(true);
            CallbackBridge.sendKeyPress(
                    256, // GLFW_KEY_ESCAPE
                    CallbackBridge.getCurrentMods(),
                    eventAction == KeyEvent.ACTION_DOWN);
        } catch (Throwable throwable) {
            LauncherLogManager.append("Android Back pause routing failed: " + throwable);
        }
        return true;
    }

    private void sendAndroidBackPauseTap() {
        try {
            CallbackBridge.setInputReady(true);
            int modifiers = CallbackBridge.getCurrentMods();
            CallbackBridge.sendKeyPress(256, modifiers, true);
            CallbackBridge.sendKeyPress(256, modifiers, false);
        } catch (Throwable throwable) {
            LauncherLogManager.append("Android gesture Back pause routing failed: " + throwable);
        }
    }

    private boolean isInGameControlsBackShortcut(@NonNull KeyEvent event) {
        int keyCode = event.getKeyCode();

        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // A controller face button that an OEM mislabeled as BACK belongs to
            // the gamepad mapper. A real handheld/navigation Back key must still
            // open the DroidBridge in-game launcher menu, even when its device also
            // advertises DPAD or GAMEPAD sources.
            if (isControllerFaceButtonBackEvent(event)) {
                return false;
            }

            MinecraftGLSurface surface = minecraftSurface;
            if (MinecraftGLSurface.isPhysicalKeyboardBackAsEsc(event)
                    || (surface != null && surface.isPointerBackKeyEvent(event))) {
                return false;
            }

            // Only genuine Android navigation Back reaches the launcher dialog.
            return true;
        }

        // Some gamepads report the small Back/Select/Menu button as SELECT or MENU.
        // Only reserve those controller buttons as an emergency overlay shortcut when
        // the user has hidden both normal on-screen ways to open the controls dialog.
        boolean hasOnScreenShortcut = ControlsPreferences.isTouchControlsEnabled(this)
                || LauncherPreferences.isShowInGameSettingsButton(this);
        if (!hasOnScreenShortcut && isGamepadKeyEvent(event)) {
            return keyCode == KeyEvent.KEYCODE_BUTTON_SELECT
                    || keyCode == KeyEvent.KEYCODE_MENU;
        }

        return false;
    }


    private static boolean isControllerFaceButtonBackEvent(@NonNull KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_BACK || !isGamepadKeyEvent(event)) {
            return false;
        }

        int scanCode = event.getScanCode();
        if (scanCode == 1 || scanCode == 158) {
            // Linux KEY_ESC belongs to a keyboard; Linux KEY_BACK is a real
            // Android/navigation Back button. Neither is a controller face button.
            return false;
        }

        // Linux gamepad face-button scan codes (BTN_SOUTH/EAST/NORTH/WEST).
        // Some OEM controller stacks translate one of these into KEYCODE_BACK.
        if (scanCode == 304 || scanCode == 305 || scanCode == 307 || scanCode == 308) {
            return true;
        }

        InputDevice device = event.getDevice();
        if (device != null) {
            // If Android exposes a normal B key on this same controller, a separate
            // BACK event is almost certainly the handheld's navigation Back button.
            try {
                boolean[] hasB = device.hasKeys(KeyEvent.KEYCODE_BUTTON_B);
                if (hasB != null && hasB.length > 0 && hasB[0]) {
                    return false;
                }
            } catch (Throwable ignored) {
            }

            // Built-in key devices should retain Android navigation semantics when
            // their vendor driver omits a useful scan code.
            try {
                if (scanCode == 0 && !device.isExternal()) {
                    return false;
                }
            } catch (Throwable ignored) {
            }
        }

        // Last-resort compatibility only for external controller devices that have
        // no dedicated BUTTON_B capability and expose their cancel face button as
        // BACK. Unknown/built-in events retain real Android Back semantics.
        if (device != null) {
            try {
                return device.isExternal();
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    @NonNull
    private static KeyEvent normalizeControllerBackAsButtonB(@NonNull KeyEvent event) {
        if (!isControllerFaceButtonBackEvent(event)) {
            return event;
        }

        // Only a confirmed controller face-button BACK is normalized. Real Android
        // Back keys (including AYN handheld Back) remain KEYCODE_BACK and open the
        // launcher menu.
        return new KeyEvent(
                event.getDownTime(),
                event.getEventTime(),
                event.getAction(),
                KeyEvent.KEYCODE_BUTTON_B,
                event.getRepeatCount(),
                event.getMetaState(),
                event.getDeviceId(),
                event.getScanCode(),
                event.getFlags(),
                event.getSource()
        );
    }


    private boolean routeAynRearButtonEvent(
            @NonNull KeyEvent event,
            @NonNull String origin
    ) {
        GamepadButton button = GamepadButton.fromAndroidKeyEvent(event);
        if ((button != GamepadButton.M1 && button != GamepadButton.M2)
                || !GamepadButton.isAynOdinRearButtonEvent(event)) {
            return false;
        }

        CallbackBridge.setInputReady(true);

        String detail = "AYN rear event origin=" + origin
                + " key=" + KeyEvent.keyCodeToString(event.getKeyCode())
                + " scanCode=" + event.getScanCode()
                + " action=" + event.getAction()
                + " source=0x" + Integer.toHexString(event.getSource())
                + " deviceId=" + event.getDeviceId()
                + " routedAs=" + button;
        Logging.i("GameActivity", detail);
        LauncherLogManager.append(detail);

        if (gamepadInputController == null) {
            LauncherLogManager.append(
                    "AYN rear event consumed before GamepadInputController initialization"
            );
            return true;
        }

        return gamepadInputController.handleAynRearButtonEvent(event, button);
    }

    private boolean handleMediaVolumeKey(@NonNull KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP
                && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN
                && keyCode != KeyEvent.KEYCODE_VOLUME_MUTE) {
            return false;
        }

        // Consume both DOWN and UP so the same physical press cannot leak into SDL,
        // controller mapping, or focused SurfaceView keyboard handling.
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return true;
        }

        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return true;

        int direction;
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            direction = AudioManager.ADJUST_RAISE;
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            direction = AudioManager.ADJUST_LOWER;
        } else {
            direction = AudioManager.ADJUST_TOGGLE_MUTE;
        }

        try {
            audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    direction,
                    AudioManager.FLAG_SHOW_UI
            );
            if (event.getRepeatCount() == 0) {
                LauncherLogManager.append("GameActivity: media volume key handled "
                        + KeyEvent.keyCodeToString(keyCode));
            }
        } catch (Throwable throwable) {
            LauncherLogManager.append("GameActivity: media volume adjustment failed: "
                    + throwable);
        }
        return true;
    }

    private boolean handleInGameMenuKeyboardShortcut(@NonNull KeyEvent event) {
        int source = event.getSource();
        boolean keyboardSource = source == 0
                || (source & InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD;
        if (!keyboardSource || isControllerOnlyShortcutKey(event)) return false;

        int eventKeyCode = normalizeInGameMenuShortcutKeyCode(event);
        int action = event.getAction();

        // Once the shortcut primary key is accepted, consume its complete key edge even if
        // the user releases Ctrl/Alt/Shift/Meta before releasing the primary key. This keeps
        // an orphaned Escape/Insert/etc. release from leaking into Minecraft.
        if (activeInGameMenuShortcutKeyCode != KeyEvent.KEYCODE_UNKNOWN
                && eventKeyCode == activeInGameMenuShortcutKeyCode
                && (activeInGameMenuShortcutDeviceId < 0
                || event.getDeviceId() == activeInGameMenuShortcutDeviceId)) {
            if (action == KeyEvent.ACTION_UP || action == KeyEvent.ACTION_MULTIPLE) {
                activeInGameMenuShortcutKeyCode = KeyEvent.KEYCODE_UNKNOWN;
                activeInGameMenuShortcutDeviceId = -1;
            }
            return true;
        }

        int configuredKeyCode = LauncherPreferences.getInGameMenuKeyboardShortcutKeyCode(this);
        if (configuredKeyCode <= 0 || eventKeyCode != configuredKeyCode) return false;

        int configuredModifiers = LauncherPreferences.getInGameMenuKeyboardShortcutModifiers(this);
        int eventModifiers = normalizedInGameMenuShortcutModifiers(event.getMetaState());
        if (eventModifiers != configuredModifiers) return false;

        if (action == KeyEvent.ACTION_DOWN) {
            activeInGameMenuShortcutKeyCode = eventKeyCode;
            activeInGameMenuShortcutDeviceId = event.getDeviceId();
            if (event.getRepeatCount() == 0 && !event.isCanceled()) {
                LauncherLogManager.append(
                        "GameActivity: physical keyboard launcher-menu shortcut pressed keyCode="
                                + configuredKeyCode + " modifiers=0x"
                                + Integer.toHexString(configuredModifiers));
                openInGameLauncherMenuFromBackShortcut();
            }
            // Consume repeats too so the shortcut primary key never enters SDL/GLFW.
            return true;
        }
        return action == KeyEvent.ACTION_UP || action == KeyEvent.ACTION_MULTIPLE;
    }

    /**
     * Shortcut routing deliberately does not use the broad isGamepadKeyEvent() test. USB/DeX
     * keyboard+mouse receivers can expose multiple HID sources under one Android InputDevice,
     * including a joystick source, even though Insert/End/Escape/letters are genuine keyboard
     * keys. Only reject actual controller button keycodes here so composite keyboards remain
     * bindable while Controlify/Legacy4J/controller buttons keep their existing route.
     */
    private static boolean isControllerOnlyShortcutKey(@NonNull KeyEvent event) {
        if (GamepadButton.fromAndroidKeyEvent(event) != null) return true;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BUTTON_C:
            case KeyEvent.KEYCODE_BUTTON_X:
            case KeyEvent.KEYCODE_BUTTON_Y:
            case KeyEvent.KEYCODE_BUTTON_Z:
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_BUTTON_R1:
            case KeyEvent.KEYCODE_BUTTON_L2:
            case KeyEvent.KEYCODE_BUTTON_R2:
            case KeyEvent.KEYCODE_BUTTON_THUMBL:
            case KeyEvent.KEYCODE_BUTTON_THUMBR:
            case KeyEvent.KEYCODE_BUTTON_START:
            case KeyEvent.KEYCODE_BUTTON_SELECT:
            case KeyEvent.KEYCODE_BUTTON_MODE:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                return true;
            default:
                return false;
        }
    }

    private static int normalizeInGameMenuShortcutKeyCode(@NonNull KeyEvent event) {
        // Several Android HID stacks expose a physical keyboard Esc as KEYCODE_BACK with
        // Linux scan code 1. Match it as Escape without changing genuine Android Back.
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                && MinecraftGLSurface.isKeyboardEscapeKey(event)) {
            return KeyEvent.KEYCODE_ESCAPE;
        }
        return event.getKeyCode();
    }

    private static int normalizedInGameMenuShortcutModifiers(int metaState) {
        int normalized = KeyEvent.normalizeMetaState(metaState);
        return normalized & (KeyEvent.META_CTRL_ON
                | KeyEvent.META_ALT_ON
                | KeyEvent.META_SHIFT_ON
                | KeyEvent.META_META_ON);
    }

    @Override
    public boolean dispatchKeyEvent(@NonNull KeyEvent event) {
        InputEventDiagnosticLogger.logKeyEvent(
                "GameActivity.dispatchKeyEvent.beforeRouting",
                event
        );
        if (handleMediaVolumeKey(event)) {
            return true;
        }
        event = normalizeControllerBackAsButtonB(event);

        // Optional user-selected physical keyboard shortcut. Check it before SDL/GLFW so
        // only the reserved key is consumed; every unbound key keeps the existing route.
        if (handleInGameMenuKeyboardShortcut(event)) {
            return true;
        }

        // Record the physical R2 key edge before SDL consumes it. Some OEM
        // controller drivers deliver the matching bogus right-stick sample in a
        // later MotionEvent, so the shared SDL axis filter needs this timestamp.
        boolean allowVanillaR2MouseRoute = !MinecraftGLSurface.sdlEnabled
                && !MinecraftGLSurface.controllerModOwnsGamepadInput
                && !ControllerModCompat.shouldMirrorAndroidGamepadToGlfw()
                && !isBetterThanAdventureLaunch();
        if (SDLControllerManager.noteControllerKeyEvent(
                event,
                allowVanillaR2MouseRoute)) {
            CallbackBridge.setInputReady(true);
            return true;
        }

        if (routeAynRearButtonEvent(event, "activity")) {
            return true;
        }

        // Genuine Android Back must be resolved before SDL. SDL treats KEYCODE_BACK
        // as a normal key and would otherwise consume it before the user's selected
        // Pause / Launcher menu / Disabled action can run. Mouse Back and keyboards
        // that report Escape as Back retain their existing Minecraft routing first.
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (routePointerBackToMinecraftMouse(event)) {
                return true;
            }
            if (routePhysicalKeyboardEscapeToMinecraft(event)) {
                return true;
            }
            if (handleInGameControlsBackShortcut(event)) {
                return true;
            }
        }

        // Snapshot 4+ uses SDL for keyboard/mouse input. Controller buttons are
        // routed here only when Controlify owns SDL controller input; otherwise
        // DroidBridge's mapped virtual mouse/keyboard path handles them below.
        if (DroidBridgeSDL3Bootstrap.routeKeyEvent(event)) {
            CallbackBridge.setInputReady(true);
            return true;
        }

        if (event.getKeyCode() != KeyEvent.KEYCODE_BACK) {
            if (routePointerBackToMinecraftMouse(event)) {
                return true;
            }

            if (routePhysicalKeyboardEscapeToMinecraft(event)) {
                return true;
            }

            if (handleInGameControlsBackShortcut(event)) {
                return true;
            }
        }

        /*
         * BTA/Odin/Xbox controllers can report face buttons as SOURCE_KEYBOARD or
         * SOURCE_DPAD instead of SOURCE_GAMEPAD. Route them into BTA's native
         * GLFW gamepad state before the normal launcher checks reject them.
         */
        if (isBetterThanAdventureLaunch()) {
            CallbackBridge.setInputReady(true);
            if (GamepadInputController.feedBtaNativeControllerKey(event)) {
                logBtaGamepadMapperOnce();
                return true;
            }
        }

        if (isGamepadKeyEvent(event)) {
            if (isBetterThanAdventureLaunch()) {
                CallbackBridge.setInputReady(true);
                logBtaGamepadMapperOnce();
                if (GamepadInputController.feedBtaNativeControllerKey(event)) {
                    return true;
                }
            }

            if (ControllerModCompat.shouldMirrorAndroidGamepadToGlfw()) {
                CallbackBridge.setInputReady(true);
                GamepadInputController.feedGlfwGamepadMirrorKey(event);
                if (!MinecraftGLSurface.sdlEnabled) {
                    // Legacy GLFW controller mods own the real controller input here.
                    // DroidBridge only mirrors state into GLFW, then consumes the
                    // Android event so it cannot also drive mapped launcher actions.
                    return true;
                }
            }

            /*
             * Same as motion: when Controllable is active and launcher SDL routing is not,
             * consume gamepad keys so Android does not treat DPAD/stick buttons as focus
             * navigation events.
             */
            if (shouldRouteGamepadToMinecraftFallback()) {
                CallbackBridge.setInputReady(true);
                return super.dispatchKeyEvent(event);
            }

            if (shouldConsumeLauncherGamepadInput()) {
                return true;
            }

            CallbackBridge.setInputReady(true);

            if (MinecraftGLSurface.sdlEnabled) {
                try {
                    int deviceId = event.getDeviceId();
                    int keyCode = event.getKeyCode();

                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        if (SDLControllerManager.onNativePadDown(deviceId, keyCode)) {
                            return true;
                        }
                    } else if (event.getAction() == KeyEvent.ACTION_UP) {
                        if (SDLControllerManager.onNativePadUp(deviceId, keyCode)) {
                            return true;
                        }
                    }

                    return super.dispatchKeyEvent(event);
                } catch (UnsatisfiedLinkError e) {
                    Logger.appendToLog(
                            "SDL controller routing disabled: SDLControllerManager key native glue is missing: "
                                    + e.getMessage()
                    );
                    MinecraftGLSurface.sdlEnabled = false;
                    return true;
                } catch (Throwable t) {
                    Logger.appendToLog(
                            "SDL controller routing disabled after key failure: "
                                    + t.getClass().getName() + ": " + t.getMessage()
                    );
                    MinecraftGLSurface.sdlEnabled = false;
                    return true;
                }
            }

            if (isBetterThanAdventureLaunch()) {
                logBtaGamepadMapperOnce();
            }

            if (gamepadInputController != null && gamepadInputController.handleKeyEvent(event)) {
                return true;
            }
        }

        CallbackBridge.setInputReady(true);
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchKeyShortcutEvent(@NonNull KeyEvent event) {
        InputEventDiagnosticLogger.logKeyEvent(
                "GameActivity.dispatchKeyShortcutEvent",
                event
        );
        return super.dispatchKeyShortcutEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, @NonNull KeyEvent event) {
        InputEventDiagnosticLogger.logKeyEvent("GameActivity.onKeyDown", event);
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, @NonNull KeyEvent event) {
        InputEventDiagnosticLogger.logKeyEvent("GameActivity.onKeyUp", event);
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, @NonNull KeyEvent event) {
        InputEventDiagnosticLogger.logKeyEvent("GameActivity.onKeyLongPress", event);
        return super.onKeyLongPress(keyCode, event);
    }

    @Override
    public boolean onKeyMultiple(
            int keyCode,
            int repeatCount,
            @NonNull KeyEvent event
    ) {
        InputEventDiagnosticLogger.logKeyEvent("GameActivity.onKeyMultiple", event);
        return super.onKeyMultiple(keyCode, repeatCount, event);
    }

    private static boolean isGamepadMotionEvent(@NonNull MotionEvent event) {
        int source = event.getSource();
        return (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD;
    }

    private static boolean isGamepadKeyEvent(@NonNull KeyEvent event) {
        int deviceId = event.getDeviceId();
        if (deviceId < 0) return false;

        // Rear/custom buttons can arrive with SOURCE_KEYBOARD even though they
        // belong to the built-in controller. Recognize the logical button first.
        if (GamepadButton.fromAndroidKeyEvent(event) != null) {
            return true;
        }

        int source = event.getSource();
        if ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD
                || SDLControllerManager.isDeviceSDLJoystick(deviceId)) {
            return true;
        }

        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BUTTON_X:
            case KeyEvent.KEYCODE_BUTTON_Y:
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_BUTTON_R1:
            case KeyEvent.KEYCODE_BUTTON_L2:
            case KeyEvent.KEYCODE_BUTTON_R2:
            case KeyEvent.KEYCODE_BUTTON_THUMBL:
            case KeyEvent.KEYCODE_BUTTON_THUMBR:
            case KeyEvent.KEYCODE_BUTTON_START:
            case KeyEvent.KEYCODE_BUTTON_SELECT:
            case KeyEvent.KEYCODE_BUTTON_MODE:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                return true;
            default:
                return false;
        }
    }

    @Override
    public void onBackPressed() {
        // Legacy/OEM entry point. Never call super: finishing GameActivity would
        // terminate the running instance instead of applying the selected action.
        handleAndroidNavigationBack("legacy-onBackPressed");
    }

    @Override
    protected void onDestroy() {
        exiting = true;
        if (GameRecordingService.isRecordingOrStarting()) {
            GameRecordingService.stop(this);
        }
        DualScreenRecordingSession.stopAsync();
        applyRecordingTouchControlVisibility(false);
        unregisterRecordingFrameBridgeReceiver();
        unregisterGameRecordingStateReceiver();
        if (androidBackPressedCallback != null) {
            androidBackPressedCallback.remove();
            androidBackPressedCallback = null;
        }
        setVulkanPresentationPausedFromLifecycle(true, "GameActivity onDestroy");
        DroidBridgeSDL3Bootstrap.shutdown();
        if (minecraftSurface != null) {
            // The retained SDL3 SurfaceTexture is intentionally not released by
            // pause/stop/detach. Release it only after native SDL has destroyed its
            // VkSurfaceKHR during the Activity's final teardown.
            minecraftSurface.releaseRetainedSdlSurfaceForShutdown();
            minecraftSurface.setSpecialKeyEventListener(null);
            minecraftSurface.setAndroidVirtualMouseUiRouter(null);
        }
        GameResolutionSettings.clearRuntimeProfileOverride();
        stopDroidBridgeAndroidMicProxy();
        stopDroidBridgeAndroidNarratorProxy();

        dismissDialog(inGameControlsDialog);
        inGameControlsDialog = null;
        if (inGameControlsEditorDialog != null) {
            try {
                inGameControlsEditorDialog.dismiss();
            } catch (Throwable ignored) {
            }
            inGameControlsEditorDialog = null;
        }

        stopLogOverlayTicker();
        stopLiveVsyncRefreshMonitor();

        if (quitWatchdogHandler != null && quitWatchdogRunnable != null) {
            quitWatchdogHandler.removeCallbacks(quitWatchdogRunnable);
        }
        quitWatchdogRunnable = null;
        quitWatchdogHandler = null;

        CallbackBridge.clearInputFocus();
        CallbackBridge.setInputReady(false);
        MinecraftGLSurface.legacyBtaInputFallbackEnabled = false;
        GamepadInputController.glfwGamepadMirrorEnabled = false;
        GamepadInputController.btaNativeControllerBridgeEnabled = false;
        GamepadInputController.btaNativeControllerOwnsLauncherInput = false;
        MinecraftGLSurface.controllerModOwnsGamepadInput = false;

        if (gamepadInputController != null) {
            gamepadInputController.removeSelf();
            gamepadInputController = null;
        }
        if (gyroInputController != null) {
            gyroInputController.stop();
            gyroInputController = null;
        }

        removeCursorOverlay();

        if (touchControlsOverlay != null) {
            ViewGroup parent = (ViewGroup) touchControlsOverlay.getParent();
            if (parent != null) parent.removeView(touchControlsOverlay);
            touchControlsOverlay = null;
        }

        if (dualScreenController != null) {
            dualScreenController.release();
            dualScreenController = null;
        }
        DualScreenSwapActionBus.setListener(null);

        if (floatingGameSettingsOverlayController != null) {
            floatingGameSettingsOverlayController.detach();
            floatingGameSettingsOverlayController = null;
        }

        cancelLaunchStatusAutoHide();
        launchStatusHandler.removeCallbacksAndMessages(null);

        if (minecraftSurface != null) {
            minecraftSurface.setImeViewportBottomInset(0);
            minecraftSurface.setSurfaceReadyListener(null);
            minecraftSurface.setOnRenderingStartedListener(null);
        }
        GameImeViewportController.detachActive(true);
        GameImeViewportController.registerMinecraftSurface(null);

        binding = null;

        if (microsoftAuthManager != null) {
            microsoftAuthManager.dispose();
            microsoftAuthManager = null;
        }
        accountStore = null;

        ControlifySDL.reset();
        ControllerModCompat.reset();
        TouchControllerModCompat.reset();
        LaunchGame.resetLaunchState();
        super.onDestroy();
    }
}
