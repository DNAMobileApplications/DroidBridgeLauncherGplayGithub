/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 */

package ca.dnamobile.droidbridgelauncher.dualscreen;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.util.SparseArray;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import ca.dnamobile.droidbridgelauncher.runtime.LwjglGlfwKeycode;
import ca.dnamobile.droidbridgelauncher.settings.LauncherPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.lwjgl.glfw.CallbackBridge;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Collections;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.Inflater;
import java.util.zip.ZipFile;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;

/**
 * Touch-friendly bottom-screen deck.  It is intentionally independent from the
 * main Minecraft SurfaceView: taps send GLFW keys/mouse buttons straight through
 * CallbackBridge, so no Android INJECT_EVENTS permission is needed.
 */
final class DualScreenControlsView extends View {
    // DroidBridge dual-screen icon renderer fix 1.0.145: Android-only BetterBeds model for HUD;
    // keep working JSON items, and prefer corrected Android special renderers over temporary cache stubs.
    private static final String TAG = "DualScreenControlsView";
    private static final int SLOT_COUNT = 9;
    private static final int MAP_PIXEL_SIZE = 128;
    private static final long HUD_POLL_MS = 75L;
    private static final long HUD_STALE_MS = 30000L;
    private static final long CONTROLLER_HOTBAR_NAV_DEBOUNCE_MS = 115L;
    // Touching a hotbar slot updates the local selector before the Fabric HUD-state
    // writer can make its next pass. Hold the tapped slot briefly until Minecraft
    // acknowledges it so one stale poll cannot flash the previously selected slot.
    private static final long TOUCH_HOTBAR_ACK_TIMEOUT_MS = 900L;

    // Default Minecraft block GUI projection. Some models, especially stairs, author a
    // different display.gui yaw. Respect that transform instead of flipping the finished image.
    private static final float MODEL_GUI_ROT_X_DEGREES = 30f;
    private static final float MODEL_GUI_ROT_Y_DEGREES = 225f;
    private static final float MODEL_GUI_ROT_Z_DEGREES = 0f;
    private static final GuiTransform DEFAULT_BLOCK_GUI_TRANSFORM =
            new GuiTransform(MODEL_GUI_ROT_X_DEGREES, MODEL_GUI_ROT_Y_DEGREES, MODEL_GUI_ROT_Z_DEGREES);
    private static final GuiTransform VANILLA_STAIRS_GUI_TRANSFORM =
            new GuiTransform(30f, 135f, 0f);
    private static final GuiTransform VANILLA_TRAPDOOR_GUI_TRANSFORM =
            // Fix14 was the correct renderer base. In this projection the user-observed
            // down-right trapdoor handle is the opposite of the bad 225/up-left attempt.
            new GuiTransform(30f, 45f, 0f);

    private static final String ACTION_MC_MENU = "mc_menu";
    private static final String ACTION_LAUNCHER_MENU = "launcher_menu";
    private static final String ACTION_INVENTORY = "inventory";
    private static final String ACTION_CHAT = "chat";
    private static final String ACTION_ATTACK = "attack";
    private static final String ACTION_USE = "use";
    private static final String ACTION_JUMP = "jump";
    private static final String ACTION_SNEAK = "sneak";
    private static final String ACTION_PERSPECTIVE = "perspective";

    @NonNull private final File hudStateFile;
    @NonNull private final File coordinateStateFile;
    @NonNull private final Runnable launcherMenuCallback;
    @NonNull private final Handler handler = new Handler(Looper.getMainLooper());
    @NonNull private final Runnable pollRunnable = new Runnable() {
        @Override public void run() {
            hudState = HudState.read(hudStateFile);
            coordinateState = CoordinateState.read(coordinateStateFile);
            invalidate();
            if (attached) handler.postDelayed(this, HUD_POLL_MS);
        }
    };

    @NonNull private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    @NonNull private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    @NonNull private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    @NonNull private final RectF titleRect = new RectF();
    @NonNull private final RectF statusRect = new RectF();
    @NonNull private final RectF hotbarOuter = new RectF();
    @NonNull private final RectF offhandSlot = new RectF();
    @NonNull private final RectF mapRect = new RectF();
    @NonNull private final RectF xpRect = new RectF();
    @NonNull private final RectF scratch = new RectF();
    @NonNull private final RectF scratch2 = new RectF();
    @NonNull private final Path shapePath = new Path();
    @NonNull private final RectF[] hotbarSlots = new RectF[SLOT_COUNT];
    @NonNull private final List<ButtonRegion> buttons = new ArrayList<>();
    @NonNull private final SparseArray<ActiveTouch> activeTouches = new SparseArray<>();
    @NonNull private final Map<String, Bitmap> itemIconCache = new HashMap<>();
    @NonNull private final Map<String, ModelDefinition> jsonModelCache = new HashMap<>();
    @NonNull private final Map<String, File> installedAssetFileCache = new HashMap<>();
    @NonNull private final Map<String, String> installedAssetHashCache = new HashMap<>();
    @Nullable private File minecraftRootCache;
    private boolean installedAssetIndexesLoaded = false;
    @NonNull private final Map<String, Bitmap> faceBitmapCache = new HashMap<>();
    @NonNull private final Map<String, Bitmap> hudAssetCache = new HashMap<>();
    // Completed hotbar icon cache. JSON/model rasterizing is expensive enough that
    // doing it on every bottom-view redraw can steal time from Minecraft.
    @NonNull private final Map<String, Bitmap> renderedItemCache = new HashMap<>();
    @NonNull private final DualScreenRenderedIconCache externalRenderedIconCache;
    @Nullable private Bitmap mapBitmap;
    @Nullable private String mapBitmapKey;
    @Nullable private Bitmap mapFrameBitmap;
    @Nullable private String mapFrameBitmapKey;

    @NonNull private HudState hudState = HudState.empty();
    @NonNull private CoordinateState coordinateState = CoordinateState.empty();
    private boolean attached;
    private int selectedSlot = 0;
    private int touchedHotbarSlot = -1;
    private int pendingTouchHotbarSlot = -1;
    private long pendingTouchHotbarUntilMs = 0L;
    private long lastControllerHotbarNavMs = 0L;
    private boolean leftTriggerWasDown = false;
    private boolean rightTriggerWasDown = false;

    DualScreenControlsView(
            @NonNull Context context,
            @NonNull File hudStateFile,
            @NonNull Runnable launcherMenuCallback
    ) {
        super(context);
        this.hudStateFile = hudStateFile;
        File hudParent = hudStateFile.getParentFile();
        this.coordinateStateFile = new File(
                hudParent != null ? hudParent : new File(System.getProperty("user.dir", ".")),
                "droidbridge_dual_screen_coordinates.json"
        );
        this.launcherMenuCallback = launcherMenuCallback;
        this.externalRenderedIconCache = new DualScreenRenderedIconCache(hudStateFile);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(true);
        for (int i = 0; i < hotbarSlots.length; i++) hotbarSlots[i] = new RectF();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        keepMinecraftInputReady();
        handler.removeCallbacks(pollRunnable);
        handler.post(pollRunnable);
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        handler.removeCallbacks(pollRunnable);
        releaseAllActiveTouches();
        recycleBitmapCache(hudAssetCache);
        recycleBitmapCache(renderedItemCache);
        externalRenderedIconCache.clearMemory();
        if (mapBitmap != null && !mapBitmap.isRecycled()) mapBitmap.recycle();
        mapBitmap = null;
        mapBitmapKey = null;
        if (mapFrameBitmap != null && !mapFrameBitmap.isRecycled()) mapFrameBitmap.recycle();
        mapFrameBitmap = null;
        mapFrameBitmapKey = null;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        recycleBitmapCache(renderedItemCache);
        rebuildLayout(w, h);
    }

    private void rebuildLayout(int width, int height) {
        buttons.clear();
        float d = getResources().getDisplayMetrics().density;
        float pad = Math.max(10f * d, Math.min(width, height) * 0.025f);
        float gap = Math.max(8f * d, pad * 0.65f);
        // Compact DS controller layout: keep the title small, remove the large duplicate
        // status/HUD panel, and reserve the bottom for a Minecraft-like HUD row above
        // a smaller vanilla hotbar.
        float titleHeight = 0f;
        float statusHeight = 0f;

        titleRect.set(pad, pad, width - pad, pad);
        statusRect.set(pad, pad, width - pad, pad);

        // Match vanilla's 182x22 hotbar geometry instead of stretching the texture to
        // an arbitrary nine-slot rectangle. This makes each 20x20 slot square and keeps
        // exported/cached Minecraft item icons at their intended aspect ratio.
        float hotbarHeight = Math.max(44f * d, Math.min(78f * d, height * 0.090f));
        float vanillaAspect = 182f / 22f;
        float hotbarWidth = hotbarHeight * vanillaAspect;
        float availableWidth = width - (pad * 2f);
        if (hotbarWidth > availableWidth) {
            hotbarWidth = availableWidth;
            hotbarHeight = hotbarWidth / vanillaAspect;
        }
        float hotbarLeft = (width - hotbarWidth) / 2f;
        float hotbarTop = height - pad - hotbarHeight;
        hotbarOuter.set(hotbarLeft, hotbarTop, hotbarLeft + hotbarWidth, hotbarTop + hotbarHeight);
        float vanillaScale = hotbarHeight / 22f;
        float slotSide = 20f * vanillaScale;
        for (int i = 0; i < SLOT_COUNT; i++) {
            float left = hotbarOuter.left + vanillaScale + (i * slotSide);
            hotbarSlots[i].set(
                    left,
                    hotbarOuter.top + vanillaScale,
                    left + slotSide,
                    hotbarOuter.top + vanillaScale + slotSide
            );
        }

        // The optional exploration map lives in the visual center of the controls display,
        // leaving the vanilla HUD/hotbar area clear at the bottom.
        float mapSide = Math.min(width * 0.38f, height * 0.39f);
        mapSide = Math.max(Math.min(width, height) * 0.24f, mapSide);
        float mapCx = width * 0.5f;
        float mapCy = height * 0.43f;
        mapRect.set(mapCx - mapSide * 0.5f, mapCy - mapSide * 0.5f,
                mapCx + mapSide * 0.5f, mapCy + mapSide * 0.5f);

        // No built-in fixed buttons here. In dual-screen mode the user's normal
        // TouchControlsOverlay layout is rendered over this view, so the bottom
        // screen behaves like the same configurable controller used in single-screen mode.
    }

    private void addButton(float left, float top, float width, float height,
                           @NonNull String label, @NonNull String subLabel,
                           @NonNull String action, boolean holdAction) {
        buttons.add(new ButtonRegion(new RectF(left, top, left + width, top + height), label, subLabel, action, holdAction));
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        drawBackground(canvas);
        // 1.0.115: keep the bottom touch overlay available on menus/title screens,
        // but hide the Minecraft HUD mirror until the state writer reports a real
        // in-world player HUD. This prevents old hotbar data from staying visible
        // while the player is at the main menu or another full-screen Minecraft UI.
        if (!shouldDrawCompactGameHud()) return;
        drawCenterMap(canvas);
        drawCoordinates(canvas);
        drawHotbar(canvas);
    }

    private boolean shouldDrawCompactGameHud() {
        HudState state = hudState;
        // 1.0.115: 1.0.115 hid the bottom HUD too aggressively because some
        // Minecraft builds write a non-empty "screen" value even while the
        // player is already in-world.  Draw whenever the live state is fresh and
        // has sane player stats; stale/menu startup state still stays hidden.
        return state != null && state.shouldDrawLiveHud();
    }

    private void drawBackground(@NonNull Canvas canvas) {
        // 1.0.107: keep this view transparent so the normal single-screen
        // TouchControlsOverlay remains visible on the bottom screen.  The old
        // full-screen black fill covered the configurable touch buttons even
        // though the overlay was still present underneath.
    }

    private void drawTitle(@NonNull Canvas canvas) {
        drawRoundRect(canvas, titleRect, Color.rgb(15, 20, 31), Color.rgb(42, 57, 76), 18f);
        text.setColor(Color.WHITE);
        text.setFakeBoldText(true);
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(sp(16));
        canvas.drawText("DroidBridge Dual Screen HUD + Controls", titleRect.left + dp(16), titleRect.centerY() - dp(2), text);
        text.setFakeBoldText(false);
        text.setTextSize(sp(9));
        text.setColor(Color.rgb(173, 185, 202));
        canvas.drawText("External screen stays clean; bottom screen acts as the Minecraft HUD + touch controller.", titleRect.left + dp(16), titleRect.centerY() + dp(18), text);
    }

    private void drawStatus(@NonNull Canvas canvas) {
        drawRoundRect(canvas, statusRect, Color.rgb(12, 16, 25), Color.rgb(35, 48, 66), 16f);
        float left = statusRect.left + dp(14);
        float right = statusRect.right - dp(14);
        float top = statusRect.top + dp(10);

        if (hudState.isFresh()) {
            if (hudState.selectedSlot >= 0 && hudState.selectedSlot < SLOT_COUNT) selectedSlot = hudState.selectedSlot;
            drawHudMirror(canvas, left, top, right, statusRect.bottom - dp(10), hudState, false);
        } else {
            // Draw a visible fallback HUD instead of leaving this panel empty. This makes it
            // obvious the bottom-screen renderer is active even before the mod starts writing
            // live state. Once a valid JSON state file is parsed, drawHudMirror() above uses
            // the real values automatically.
            HudState fallback = HudState.fallback();
            drawHudMirror(canvas, left, top, right, statusRect.bottom - dp(10), fallback, true);
            text.setTextAlign(Paint.Align.LEFT);
            text.setColor(Color.rgb(210, 218, 231));
            text.setFakeBoldText(true);
            text.setTextSize(sp(11));
            canvas.drawText("HUD mirror waiting for live state", left, statusRect.bottom - dp(20), text);
            text.setFakeBoldText(false);
            text.setTextSize(sp(8));
            text.setColor(Color.rgb(147, 160, 179));
            canvas.drawText(fitText("State file: " + hudStateFile.getAbsolutePath(), Math.max(dp(120), right - left), text), left, statusRect.bottom - dp(6), text);
        }
    }

    private void drawHudMirror(@NonNull Canvas canvas, float left, float top, float right, float bottom, @NonNull HudState state, boolean fallback) {
        float d = getResources().getDisplayMetrics().density;
        float rowGap = Math.max(5f * d, (bottom - top) * 0.055f);
        float pip = Math.max(12f * d, Math.min(18f * d, (bottom - top) * 0.19f));
        float row1 = top;
        float row2 = row1 + pip + rowGap;
        float row3 = row2 + pip + rowGap;

        drawHearts(canvas, left, row1, state.health, state.maxHealth, state.absorption);
        drawFood(canvas, right - dp(186), row1, state.food);
        drawArmor(canvas, left, row2, state.armor);
        drawAir(canvas, right - dp(186), row2, state.air, state.maxAir);
        drawXp(canvas, left, row3, right, row3 + Math.max(dp(12), pip * 0.62f), state.experienceProgress, state.experienceLevel);

        text.setTextAlign(Paint.Align.RIGHT);
        text.setFakeBoldText(false);
        text.setTextSize(sp(10));
        text.setColor(Color.rgb(176, 188, 206));
        String screen = fallback ? "Fallback HUD - waiting for live JSON" : (state.screenTitle == null || state.screenTitle.trim().isEmpty() ? "In game" : state.screenTitle);
        canvas.drawText(fitText(screen, Math.max(dp(96), right - left - dp(280)), text), right, row2 + pip - dp(1), text);
    }


    private void drawHudStatusBackplates(@NonNull Canvas canvas, float left, float row1, float right, float row2, float pip) {
        float mid = (left + right) * 0.5f;
        float top = row1 - dp(5);
        float bottom = row2 + pip + dp(6);
        scratch.set(left - dp(5), top, Math.min(left + dp(292), mid - dp(12)), bottom);
        drawHudBackplate(canvas, scratch);
        scratch.set(Math.max(right - dp(235), mid + dp(12)), top, right + dp(5), bottom);
        drawHudBackplate(canvas, scratch);
    }

    private void drawHudBackplate(@NonNull Canvas canvas, @NonNull RectF r) {
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(Color.argb(46, 28, 36, 52));
        canvas.drawRoundRect(r, dp(11), dp(11), fill);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(1));
        stroke.setColor(Color.argb(70, 79, 104, 139));
        canvas.drawRoundRect(r, dp(11), dp(11), stroke);
    }

    private void drawHearts(@NonNull Canvas canvas, float x, float y, float health, float maxHealth, float absorption) {
        if (maxHealth <= 0f) maxHealth = 20f;
        if (health < 0f) health = maxHealth;
        int containers = Math.max(10, Math.min(20, (int) Math.ceil(Math.max(maxHealth, 20f) / 2f)));
        float value = Math.max(0f, health) / 2f;
        if (!drawHudSpritePips(canvas, x, y, containers, value, "heart_full", "heart_half", "heart_container")) {
            drawHudPips(canvas, x, y, containers, value, Color.rgb(227, 49, 58), Color.rgb(77, 30, 39), "heart");
        }
        if (absorption > 0f) {
            drawHudPips(canvas, x + dp(186), y, Math.min(10, (int) Math.ceil(absorption / 2f)), absorption / 2f, Color.rgb(238, 201, 69), Color.rgb(80, 65, 25), "heart");
        }
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(sp(10));
        text.setColor(Color.rgb(231, 205, 208));
        canvas.drawText(String.format(Locale.US, "%.0f/%.0f", Math.max(0f, health), Math.max(0f, maxHealth)), x + dp(212), y + dp(13), text);
    }

    private void drawFood(@NonNull Canvas canvas, float x, float y, int food) {
        if (food < 0) food = 20;
        if (!drawHudSpritePips(canvas, x, y, 10, Math.max(0, food) / 2f, "food_full", "food_half", "food_empty")) {
            drawHudPips(canvas, x, y, 10, Math.max(0, food) / 2f, Color.rgb(229, 139, 46), Color.rgb(73, 46, 22), "food");
        }
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(sp(10));
        text.setColor(Color.rgb(238, 214, 176));
        canvas.drawText("Food " + Math.max(0, food), x + dp(154), y + dp(13), text);
    }

    private void drawArmor(@NonNull Canvas canvas, float x, float y, int armor) {
        if (armor < 0) armor = 0;
        float value = Math.max(0, armor) / 2f;
        if (!drawHudSpritePipsAny(canvas, x, y, 10, value, 255,
                new String[] { "armor_full", "armor/full" },
                new String[] { "armor_half", "armor/half" },
                new String[] { "armor_empty", "armor/container", "armor_empty" })) {
            drawHudPips(canvas, x, y, 10, value, Color.rgb(169, 190, 213), Color.rgb(46, 56, 70), "armor");
        }
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(sp(10));
        text.setColor(Color.rgb(198, 211, 226));
        canvas.drawText("Armor " + Math.max(0, armor), x + dp(154), y + dp(13), text);
    }

    private void drawAir(@NonNull Canvas canvas, float x, float y, int air, int maxAir) {
        boolean fullAir = air < 0 || maxAir <= 0 || air >= maxAir;
        if (fullAir) return;
        float value = Math.max(0f, Math.min(10f, (air / (float) maxAir) * 10f));
        boolean drewSprites = drawHudSpritePipsAny(canvas, x, y, 10, value, 255,
                new String[] { "air_full", "air", "bubble_full", "bubble" },
                new String[] { "air_half", "air_bursting", "bubble_half", "bubble_bursting" },
                new String[] { "air_empty", "bubble_empty" });
        if (!drewSprites) {
            drawHudPips(canvas, x, y, 10, value, Color.rgb(92, 183, 235), Color.rgb(28, 55, 74), "air");
        }
    }

    private void drawXp(@NonNull Canvas canvas, float left, float top, float right, float bottom, float progress, int level) {
        float p = clamp01(progress);
        xpRect.set(left, top, right, bottom);
        drawRoundRect(canvas, xpRect, Color.rgb(31, 43, 32), Color.rgb(55, 83, 55), 5f);
        scratch.set(left + dp(2), top + dp(2), left + dp(2) + Math.max(0f, (right - left - dp(4)) * p), bottom - dp(2));
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(Color.rgb(93, 212, 79));
        canvas.drawRoundRect(scratch, dp(4), dp(4), fill);
        if (level >= 0) {
            drawMinecraftStyleXpLevel(canvas, level, (left + right) * 0.5f, bottom - dp(1));
        }
    }


    private boolean drawHudSpritePipsAny(@NonNull Canvas canvas, float x, float y, int count, float filled, int alpha,
                                         @NonNull String[] fullNames, @NonNull String[] halfNames, @NonNull String[] emptyNames) {
        Bitmap full = loadFirstHudAsset(fullNames);
        Bitmap half = loadFirstHudAsset(halfNames);
        Bitmap empty = loadFirstHudAsset(emptyNames);
        if (full == null && empty == null) return false;
        if (empty == null) empty = full;

        float size = dp(13);
        float gap = dp(3);
        int limit = Math.min(10, Math.max(0, count));
        int oldAlpha = fill.getAlpha();
        fill.setFilterBitmap(false);
        fill.setDither(false);
        for (int i = 0; i < limit; i++) {
            scratch.set(x + i * (size + gap), y, x + i * (size + gap) + size, y + size);
            // Match the vanilla HUD layering: draw the empty/container sprite first, then
            // overlay the full/half sprite. This gives armor and air the same real icon
            // background behavior that hearts/hunger already use.
            if (empty != null) {
                fill.setAlpha(Math.max(35, Math.min(255, alpha)));
                canvas.drawBitmap(empty, null, scratch, fill);
            }
            float v = filled - i;
            Bitmap overlay = v >= 1f ? full : (v > 0f && half != null ? half : null);
            if (overlay != null) {
                fill.setAlpha(Math.max(0, Math.min(255, alpha)));
                canvas.drawBitmap(overlay, null, scratch, fill);
            }
        }
        fill.setAlpha(oldAlpha);
        return true;
    }

    @Nullable
    private Bitmap loadFirstHudAsset(@NonNull String[] names) {
        for (String name : names) {
            if (name == null || name.trim().isEmpty()) continue;
            Bitmap bitmap = loadHudAsset(name.trim());
            if (bitmap != null && !bitmap.isRecycled()) return bitmap;
        }
        return null;
    }

    private boolean drawHudSpritePips(@NonNull Canvas canvas, float x, float y, int count, float filled,
                                      @NonNull String fullName, @NonNull String halfName, @NonNull String emptyName) {
        Bitmap full = loadHudAsset(fullName);
        Bitmap half = loadHudAsset(halfName);
        Bitmap empty = loadHudAsset(emptyName);
        if (full == null && empty == null) return false;
        if (empty == null) empty = full;
        float size = dp(13);
        float gap = dp(3);
        int limit = Math.min(10, Math.max(0, count));
        fill.setFilterBitmap(false);
        fill.setDither(false);
        for (int i = 0; i < limit; i++) {
            float v = filled - i;
            scratch.set(x + i * (size + gap), y, x + i * (size + gap) + size, y + size);
            if (empty != null) canvas.drawBitmap(empty, null, scratch, fill);
            Bitmap overlay = v >= 1f ? full : (v > 0f && half != null ? half : null);
            if (overlay != null) canvas.drawBitmap(overlay, null, scratch, fill);
        }
        return true;
    }

    @Nullable
    private Bitmap loadHudAsset(@NonNull String name) {
        String clean = name.endsWith(".png") ? name : name + ".png";
        Bitmap cached = hudAssetCache.get(clean);
        if (cached != null && !cached.isRecycled()) return cached;
        File file = resolveHudAssetFile(clean);
        if (file == null || !file.isFile() || file.length() <= 0L) return null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (bitmap != null) {
                hudAssetCache.put(clean, bitmap);
                return bitmap;
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to load dual-screen HUD asset " + clean, throwable);
        }
        return null;
    }

    @Nullable
    private File resolveHudAssetFile(@NonNull String name) {
        ArrayList<File> candidates = new ArrayList<>();
        File parent = hudStateFile.getParentFile();
        addHudAssetCandidate(candidates, parent, name);
        if (parent != null) {
            addHudAssetCandidate(candidates, new File(parent, "game"), name);
            File grand = parent.getParentFile();
            if (grand != null) {
                addHudAssetCandidate(candidates, grand, name);
                addHudAssetCandidate(candidates, new File(grand, "game"), name);
            }
        }
        for (File candidate : candidates) {
            if (candidate != null && candidate.isFile() && candidate.length() > 0L) return candidate;
        }
        return null;
    }

    private void addHudAssetCandidate(@NonNull ArrayList<File> candidates, @Nullable File base, @NonNull String name) {
        if (base == null) return;
        File file = new File(new File(new File(base, "droidbridge_dual_screen_assets"), "hud"), name);
        String path = file.getAbsolutePath();
        for (File existing : candidates) {
            if (path.equals(existing.getAbsolutePath())) return;
        }
        candidates.add(file);
    }

    private void drawHudPips(@NonNull Canvas canvas, float x, float y, int count, float filled, int fullColor, int emptyColor, @NonNull String type) {
        float size = dp(13);
        float gap = dp(3);
        int limit = Math.min(10, Math.max(0, count));
        for (int i = 0; i < limit; i++) {
            float v = filled - i;
            int color = v >= 1f ? fullColor : (v > 0f ? blend(emptyColor, fullColor, 0.55f) : emptyColor);
            float l = x + i * (size + gap);
            scratch.set(l, y, l + size, y + size);
            if ("heart".equals(type)) drawHeart(canvas, scratch, color, emptyColor);
            else if ("food".equals(type)) drawFoodPip(canvas, scratch, color, emptyColor);
            else if ("armor".equals(type)) drawShield(canvas, scratch, color, emptyColor);
            else drawBubble(canvas, scratch, color, emptyColor);
        }
    }

    private void drawHeart(@NonNull Canvas canvas, @NonNull RectF r, int color, int border) {
        shapePath.reset();
        float cx = r.centerX();
        float top = r.top + r.height() * 0.20f;
        shapePath.moveTo(cx, r.bottom - r.height() * 0.12f);
        shapePath.cubicTo(r.left - r.width() * 0.12f, r.top + r.height() * 0.55f, r.left + r.width() * 0.06f, top, cx, r.top + r.height() * 0.34f);
        shapePath.cubicTo(r.right - r.width() * 0.06f, top, r.right + r.width() * 0.12f, r.top + r.height() * 0.55f, cx, r.bottom - r.height() * 0.12f);
        fill.setStyle(Paint.Style.FILL); fill.setColor(color); canvas.drawPath(shapePath, fill);
        stroke.setStyle(Paint.Style.STROKE); stroke.setStrokeWidth(dp(1)); stroke.setColor(border); canvas.drawPath(shapePath, stroke);
    }

    private void drawFoodPip(@NonNull Canvas canvas, @NonNull RectF r, int color, int border) {
        fill.setStyle(Paint.Style.FILL); fill.setColor(color);
        canvas.drawRoundRect(r, dp(5), dp(5), fill);
        scratch2.set(r.left + r.width() * 0.56f, r.top - r.height() * 0.05f, r.right + r.width() * 0.15f, r.top + r.height() * 0.40f);
        fill.setColor(Color.rgb(80, 170, 68));
        canvas.drawOval(scratch2, fill);
        stroke.setStyle(Paint.Style.STROKE); stroke.setStrokeWidth(dp(1)); stroke.setColor(border); canvas.drawRoundRect(r, dp(5), dp(5), stroke);
    }

    private void drawShield(@NonNull Canvas canvas, @NonNull RectF r, int color, int border) {
        shapePath.reset();
        shapePath.moveTo(r.centerX(), r.top);
        shapePath.lineTo(r.right, r.top + r.height() * 0.25f);
        shapePath.lineTo(r.right - r.width() * 0.12f, r.bottom - r.height() * 0.18f);
        shapePath.lineTo(r.centerX(), r.bottom);
        shapePath.lineTo(r.left + r.width() * 0.12f, r.bottom - r.height() * 0.18f);
        shapePath.lineTo(r.left, r.top + r.height() * 0.25f);
        shapePath.close();
        fill.setStyle(Paint.Style.FILL); fill.setColor(color); canvas.drawPath(shapePath, fill);
        stroke.setStyle(Paint.Style.STROKE); stroke.setStrokeWidth(dp(1)); stroke.setColor(border); canvas.drawPath(shapePath, stroke);
    }

    private void drawBubble(@NonNull Canvas canvas, @NonNull RectF r, int color, int border) {
        fill.setStyle(Paint.Style.FILL); fill.setColor(color); canvas.drawOval(r, fill);
        stroke.setStyle(Paint.Style.STROKE); stroke.setStrokeWidth(dp(1)); stroke.setColor(border); canvas.drawOval(r, stroke);
    }

    private int blend(int a, int b, float t) {
        t = clamp01(t);
        int ar = Color.red(a), ag = Color.green(a), ab = Color.blue(a);
        int br = Color.red(b), bg = Color.green(b), bb = Color.blue(b);
        return Color.rgb((int) (ar + (br - ar) * t), (int) (ag + (bg - ag) * t), (int) (ab + (bb - ab) * t));
    }

    private void drawButtons(@NonNull Canvas canvas) {
        for (ButtonRegion button : buttons) {
            boolean pressed = isActionPressed(button.action);
            int bg = pressed ? Color.rgb(40, 62, 84) : Color.rgb(18, 25, 38);
            int border = pressed ? Color.rgb(86, 166, 255) : Color.rgb(49, 65, 88);
            drawRoundRect(canvas, button.bounds, bg, border, 18f);
            text.setTextAlign(Paint.Align.CENTER);
            text.setFakeBoldText(true);
            text.setColor(Color.WHITE);
            text.setTextSize(sp(14));
            canvas.drawText(button.label, button.bounds.centerX(), button.bounds.centerY() - dp(2), text);
            text.setFakeBoldText(false);
            text.setColor(Color.rgb(164, 176, 195));
            text.setTextSize(sp(10));
            canvas.drawText(button.subLabel, button.bounds.centerX(), button.bounds.centerY() + dp(16), text);
        }
    }

    private void drawCompactMinecraftHudAboveHotbar(@NonNull Canvas canvas) {
        HudState state = hudState.isFresh() ? hudState : HudState.fallback();
        float pip = Math.max(dp(11), Math.min(dp(17), hotbarOuter.height() * 0.26f));
        float gap = Math.max(dp(1.5f), pip * 0.18f);
        float rowGap = Math.max(dp(2), pip * 0.30f);
        float hudWidth = (pip * 10f) + (gap * 9f);
        float leftX = hotbarOuter.left + dp(7);
        float rightX = hotbarOuter.right - dp(7) - hudWidth;
        float baseY = hotbarOuter.top - (pip * 2f) - rowGap - dp(5);
        if (baseY < titleRect.bottom + dp(4)) baseY = hotbarOuter.top - pip - dp(4);

        // Minecraft-style placement above the hotbar:
        // left = armor row over health row, right = air row over hunger row.
        drawCompactHudSpritePips(canvas, leftX, baseY, 10, Math.max(0, state.armor) / 2f,
                new String[] { "armor_full", "armor/full" },
                new String[] { "armor_half", "armor/half" },
                new String[] { "armor_empty", "armor/container" }, pip, gap, 255);
        drawCompactHudSpritePips(canvas, leftX, baseY + pip + rowGap, 10, Math.max(0f, state.health) / 2f,
                new String[] { "heart_full" },
                new String[] { "heart_half" },
                new String[] { "heart_container" }, pip, gap, 255);

        boolean fullAir = state.air < 0 || state.maxAir <= 0 || state.air >= state.maxAir;
        if (!fullAir) {
            float airValue = Math.max(0f, Math.min(10f, (state.air / (float) Math.max(1, state.maxAir)) * 10f));
            drawCompactHudSpritePips(canvas, rightX, baseY, 10, airValue,
                    new String[] { "air_full", "air", "bubble_full", "bubble" },
                    new String[] { "air_half", "air_bursting", "bubble_half", "bubble_bursting" },
                    new String[] { "air_empty", "bubble_empty" }, pip, gap, 255);
        }
        drawCompactHudSpritePips(canvas, rightX, baseY + pip + rowGap, 10, Math.max(0, state.food) / 2f,
                new String[] { "food_full" },
                new String[] { "food_half" },
                new String[] { "food_empty" }, pip, gap, 255);

        // XP directly above the vanilla hotbar, matching the normal HUD stack.
        float xpH = Math.max(dp(4), pip * 0.34f);
        float xpTop = hotbarOuter.top - xpH - dp(2);
        drawCompactXpBar(canvas, hotbarOuter.left + dp(4), xpTop, hotbarOuter.right - dp(4), xpTop + xpH,
                state.experienceProgress, state.experienceLevel);
    }

    private boolean drawCompactHudSpritePips(@NonNull Canvas canvas, float x, float y, int count, float filled,
                                             @NonNull String[] fullNames, @NonNull String[] halfNames, @NonNull String[] emptyNames,
                                             float size, float gap, int alpha) {
        Bitmap full = loadFirstHudAsset(fullNames);
        Bitmap half = loadFirstHudAsset(halfNames);
        Bitmap empty = loadFirstHudAsset(emptyNames);
        if (full == null && empty == null) return false;
        if (empty == null) empty = full;
        int oldAlpha = fill.getAlpha();
        fill.setFilterBitmap(false);
        fill.setDither(false);
        for (int i = 0; i < Math.min(10, Math.max(0, count)); i++) {
            scratch.set(x + i * (size + gap), y, x + i * (size + gap) + size, y + size);
            if (empty != null) {
                fill.setAlpha(Math.max(25, Math.min(255, alpha)));
                canvas.drawBitmap(empty, null, scratch, fill);
            }
            float v = filled - i;
            Bitmap overlay = v >= 1f ? full : (v > 0f && half != null ? half : null);
            if (overlay != null) {
                fill.setAlpha(Math.max(0, Math.min(255, alpha)));
                canvas.drawBitmap(overlay, null, scratch, fill);
            }
        }
        fill.setAlpha(oldAlpha);
        return true;
    }

    private void drawCompactXpBar(@NonNull Canvas canvas, float left, float top, float right, float bottom, float progress, int level) {
        float p = clamp01(progress);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(Color.rgb(31, 43, 32));
        canvas.drawRect(left, top, right, bottom, fill);
        fill.setColor(Color.rgb(93, 212, 79));
        canvas.drawRect(left, top, left + (right - left) * p, bottom, fill);
        if (level >= 0) {
            drawMinecraftStyleXpLevel(canvas, level, (left + right) * 0.5f, top - dp(1));
        }
    }

    // Small 5x7 pixel numeral set. This intentionally uses a Minecraft-like bitmap
    // treatment instead of Android's proportional system font: hard square pixels,
    // bright XP green, and a one-pixel dark shadow. It keeps the level number visually
    // consistent with the vanilla HUD without bundling a separate font file.
    private static final String[][] XP_DIGITS = new String[][] {
            {"11111","10001","10011","10101","11001","10001","11111"},
            {"00100","01100","00100","00100","00100","00100","01110"},
            {"11110","00001","00001","11110","10000","10000","11111"},
            {"11110","00001","00001","01110","00001","00001","11110"},
            {"10010","10010","10010","11111","00010","00010","00010"},
            {"11111","10000","10000","11110","00001","00001","11110"},
            {"01111","10000","10000","11110","10001","10001","01110"},
            {"11111","00001","00010","00100","01000","01000","01000"},
            {"01110","10001","10001","01110","10001","10001","01110"},
            {"01110","10001","10001","01111","00001","00001","11110"}
    };

    private void drawMinecraftStyleXpLevel(@NonNull Canvas canvas, int level, float centerX, float bottomY) {
        String value = String.valueOf(Math.max(0, level));
        float pixel = Math.max(1f, dp(1.15f));
        float glyphWidth = 5f * pixel;
        float gap = pixel;
        float totalWidth = value.length() * glyphWidth + Math.max(0, value.length() - 1) * gap;
        float left = centerX - totalWidth * 0.5f;
        float top = bottomY - 7f * pixel;

        drawXpPixelDigits(canvas, value, left + pixel, top + pixel, pixel, Color.rgb(28, 64, 8));
        drawXpPixelDigits(canvas, value, left, top, pixel, Color.rgb(128, 255, 32));
    }

    private void drawXpPixelDigits(@NonNull Canvas canvas, @NonNull String value, float left, float top, float pixel, int color) {
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(color);
        float x = left;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') continue;
            String[] rows = XP_DIGITS[c - '0'];
            for (int row = 0; row < rows.length; row++) {
                String bits = rows[row];
                for (int col = 0; col < bits.length(); col++) {
                    if (bits.charAt(col) != '1') continue;
                    float px = x + col * pixel;
                    float py = top + row * pixel;
                    canvas.drawRect(px, py, px + pixel, py + pixel, fill);
                }
            }
            x += 6f * pixel;
        }
    }


    private boolean drawMinecraftHotbarChrome(@NonNull Canvas canvas) {
        Bitmap hotbar = loadFirstHudAsset(new String[] { "hotbar", "hud_hotbar", "hotbar_background" });
        if (hotbar == null) return false;
        fill.setFilterBitmap(false);
        fill.setDither(false);
        canvas.drawBitmap(hotbar, null, hotbarOuter, fill);

        if (selectedSlot >= 0 && selectedSlot < SLOT_COUNT) {
            Bitmap selected = loadFirstHudAsset(new String[] { "hotbar_selection", "hotbar_selected", "selected_hotbar_slot" });
            RectF slot = hotbarSlots[selectedSlot];
            float growX = Math.max(dp(1.5f), slot.width() * 0.10f);
            float growY = Math.max(dp(1.5f), slot.height() * 0.10f);
            scratch.set(slot.left - growX, slot.top - growY, slot.right + growX, slot.bottom + growY);
            if (selected != null) {
                canvas.drawBitmap(selected, null, scratch, fill);
            } else {
                stroke.setStyle(Paint.Style.STROKE);
                stroke.setStrokeWidth(dp(2));
                stroke.setColor(Color.rgb(255, 255, 255));
                canvas.drawRect(scratch, stroke);
            }
        }
        return true;
    }

    private void drawCenterMap(@NonNull Canvas canvas) {
        HudState state = hudState;
        if (state == null || !state.mapEnabled || state.mapData.isEmpty()) return;
        Bitmap bitmap = obtainMapBitmap(state);
        if (bitmap == null || bitmap.isRecycled()) return;

        fill.setFilterBitmap(false);
        fill.setDither(false);

        // Use the Minecraft-style parchment texture as the actual map surface. The live
        // exploration pixels are drawn inside its ragged border rather than over a generic
        // rounded rectangle. The supplied texture is 160x160; a 10px logical inset keeps
        // the explored data clear of the torn/dark edge at every display size.
        Bitmap frameBitmap = obtainMapFrameBitmap();
        if (frameBitmap != null && !frameBitmap.isRecycled()) {
            canvas.drawBitmap(frameBitmap, null, mapRect, fill);
            float textureInset = Math.max(dp(3f), mapRect.width() * (10f / 160f));
            scratch2.set(mapRect);
            scratch2.inset(textureInset, textureInset);
        } else {
            // Safe fallback for a launcher build that is missing the new drawable.
            float frame = Math.max(dp(5f), mapRect.width() * 0.025f);
            scratch.set(mapRect);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(Color.argb(238, 72, 57, 38));
            canvas.drawRoundRect(scratch, dp(8), dp(8), fill);
            scratch.inset(frame, frame);
            fill.setColor(Color.argb(245, 214, 199, 155));
            canvas.drawRect(scratch, fill);
            float innerPad = Math.max(dp(3f), scratch.width() * 0.018f);
            scratch2.set(scratch);
            scratch2.inset(innerPad, innerPad);
        }

        canvas.drawBitmap(bitmap, null, scratch2, fill);

        // Vanilla-like player marker. It tracks position inside the fixed 128x128 map
        // and rotates with player yaw; outside-map positions clamp to the edge.
        float px = clamp(state.mapPlayerX / Math.max(1f, state.mapSize), 0f, 1f);
        float py = clamp(state.mapPlayerY / Math.max(1f, state.mapSize), 0f, 1f);
        float cx = scratch2.left + px * scratch2.width();
        float cy = scratch2.top + py * scratch2.height();
        float marker = Math.max(dp(5f), scratch2.width() * 0.026f);
        shapePath.reset();
        shapePath.moveTo(0f, -marker);
        shapePath.lineTo(marker * 0.72f, marker * 0.82f);
        shapePath.lineTo(0f, marker * 0.46f);
        shapePath.lineTo(-marker * 0.72f, marker * 0.82f);
        shapePath.close();
        canvas.save();
        canvas.translate(cx, cy);
        canvas.rotate(state.mapRotation);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(Color.WHITE);
        canvas.drawPath(shapePath, fill);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(dp(1f), marker * 0.15f));
        stroke.setColor(Color.rgb(55, 45, 35));
        canvas.drawPath(shapePath, stroke);
        canvas.restore();
    }

    // Optional Minecraft-style coordinate readout exported by the Fabric companion mod.
    // Keep this launcher-side renderer font-independent: a tiny 5x7 bitmap alphabet gives
    // the same crisp pixel treatment as the XP level without bundling a TTF/OTF file.
    private void drawCoordinates(@NonNull Canvas canvas) {
        CoordinateState state = coordinateState;
        if (state == null || !state.enabled || !state.isFresh()) return;

        String value = String.format(Locale.US, "X %d  Y %d  Z %d", state.x, state.y, state.z);
        float pixel = Math.max(1f, Math.min(dp(1.9f), getWidth() / Math.max(120f, value.length() * 6.5f)));
        float width = measurePixelText(value, pixel);
        float left = (getWidth() - width) * 0.5f;
        float top = Math.max(dp(10f), mapRect.top - (8f * pixel) - dp(8f));

        drawPixelText(canvas, value, left + pixel, top + pixel, pixel, Color.argb(220, 20, 20, 20));
        drawPixelText(canvas, value, left, top, pixel, Color.WHITE);
    }

    private float measurePixelText(@NonNull String value, float pixel) {
        if (value.isEmpty()) return 0f;
        float width = 0f;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            width += (c == ' ' ? 3f : 6f) * pixel;
        }
        return Math.max(0f, width - pixel);
    }

    private void drawPixelText(@NonNull Canvas canvas, @NonNull String value, float left, float top, float pixel, int color) {
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(color);
        float x = left;
        for (int i = 0; i < value.length(); i++) {
            char c = Character.toUpperCase(value.charAt(i));
            if (c == ' ') {
                x += 3f * pixel;
                continue;
            }
            String[] rows = coordinateGlyph(c);
            if (rows == null) {
                x += 6f * pixel;
                continue;
            }
            for (int row = 0; row < rows.length; row++) {
                String bits = rows[row];
                for (int col = 0; col < bits.length(); col++) {
                    if (bits.charAt(col) != '1') continue;
                    float px = x + col * pixel;
                    float py = top + row * pixel;
                    canvas.drawRect(px, py, px + pixel, py + pixel, fill);
                }
            }
            x += 6f * pixel;
        }
    }

    @Nullable
    private static String[] coordinateGlyph(char c) {
        if (c >= '0' && c <= '9') return XP_DIGITS[c - '0'];
        switch (c) {
            case 'X': return new String[]{"10001","01010","00100","00100","01010","10001","10001"};
            case 'Y': return new String[]{"10001","10001","01010","00100","00100","00100","00100"};
            case 'Z': return new String[]{"11111","00001","00010","00100","01000","10000","11111"};
            case '-': return new String[]{"00000","00000","00000","11111","00000","00000","00000"};
            default: return null;
        }
    }

    @Nullable
    private Bitmap obtainMapFrameBitmap() {
        File custom = LauncherPreferences.getDualScreenMapFrameImageFile(getContext());
        boolean useCustom = custom.isFile() && custom.length() > 0L;
        String desiredKey = useCustom
                ? custom.getAbsolutePath() + ":" + custom.lastModified() + ":" + custom.length()
                : "resource:default_parchment";

        if (desiredKey.equals(mapFrameBitmapKey)
                && mapFrameBitmap != null
                && !mapFrameBitmap.isRecycled()) {
            return mapFrameBitmap;
        }

        if (mapFrameBitmap != null && !mapFrameBitmap.isRecycled()) {
            mapFrameBitmap.recycle();
        }
        mapFrameBitmap = null;
        mapFrameBitmapKey = desiredKey;

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        if (useCustom) {
            try {
                mapFrameBitmap = BitmapFactory.decodeFile(custom.getAbsolutePath(), options);
                if (mapFrameBitmap != null) return mapFrameBitmap;
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to load custom dual-screen map texture", throwable);
            }
        }

        try {
            // Compile-time resource lookup works for debug/applicationId-suffixed builds.
            final int resourceId = ca.dnamobile.droidbridgelauncher.R.drawable.droidbridge_dualscreen_map_frame;
            mapFrameBitmap = BitmapFactory.decodeResource(getResources(), resourceId, options);
            return mapFrameBitmap;
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to load dual-screen map frame texture", throwable);
            return null;
        }
    }

    @Nullable
    private Bitmap obtainMapBitmap(@NonNull HudState state) {
        String key = state.mapSize + ":" + state.mapData.hashCode();
        if (key.equals(mapBitmapKey) && mapBitmap != null && !mapBitmap.isRecycled()) return mapBitmap;
        try {
            byte[] compressed = Base64.decode(state.mapData, Base64.DEFAULT);
            int size = state.mapSize > 0 ? state.mapSize : MAP_PIXEL_SIZE;
            int expected = size * size * 4;
            byte[] raw = new byte[expected];
            Inflater inflater = new Inflater();
            inflater.setInput(compressed);
            int offset = 0;
            while (!inflater.finished() && offset < raw.length) {
                int read = inflater.inflate(raw, offset, raw.length - offset);
                if (read <= 0) break;
                offset += read;
            }
            inflater.end();
            if (offset < expected) return null;
            int[] pixels = new int[size * size];
            int o = 0;
            for (int i = 0; i < pixels.length; i++) {
                int a = raw[o++] & 0xFF;
                int r = raw[o++] & 0xFF;
                int g = raw[o++] & 0xFF;
                int b = raw[o++] & 0xFF;
                pixels[i] = Color.argb(a, r, g, b);
            }
            Bitmap decoded = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888);
            if (mapBitmap != null && !mapBitmap.isRecycled()) mapBitmap.recycle();
            mapBitmap = decoded;
            mapBitmapKey = key;
            return decoded;
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to decode dual-screen exploration map", throwable);
            return null;
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private void drawHotbar(@NonNull Canvas canvas) {
        // 1.0.110: always mirror Minecraft's real selected slot before drawing.
        // L1/R1 and the on-screen Scroll U/D buttons change the hotbar through Minecraft's
        // normal mouse-wheel path, so the bottom HUD must follow hudState.selectedSlot.
        // Earlier builds only synced this in drawStatus(), but drawStatus() is no longer
        // called in the compact HUD, leaving the bottom selector stuck on the old slot.
        syncSelectedSlotFromHudState();
        drawCompactMinecraftHudAboveHotbar(canvas);
        boolean vanillaChrome = drawMinecraftHotbarChrome(canvas);
        if (!vanillaChrome) {
            drawRoundRect(canvas, hotbarOuter, Color.rgb(11, 12, 15), Color.rgb(84, 84, 92), 10f);
        }
        for (int i = 0; i < SLOT_COUNT; i++) {
            RectF slot = hotbarSlots[i];
            boolean selected = i == selectedSlot;
            if (!vanillaChrome) {
                drawRoundRect(
                        canvas,
                        slot,
                        selected ? Color.rgb(61, 61, 66) : Color.rgb(27, 28, 34),
                        selected ? Color.rgb(245, 245, 245) : Color.rgb(88, 88, 98),
                        selected ? 8f : 5f
                );
            }

            HudItem item = hudState.item(i);
            RectF icon = scratch;
            // Always render into a centered square. This prevents flat PNGs and Android
            // synthetic 3D models from inheriting any slot/chrome aspect-ratio mismatch.
            float slotSide = Math.min(slot.width(), slot.height());
            float iconPad = vanillaChrome
                    ? Math.max(dp(2.5f), slotSide * 0.115f)
                    : Math.max(dp(4.0f), slotSide * 0.125f);
            float iconSide = Math.max(1f, slotSide - iconPad * 2f);
            icon.set(slot.centerX() - iconSide * 0.5f, slot.centerY() - iconSide * 0.5f,
                    slot.centerX() + iconSide * 0.5f, slot.centerY() + iconSide * 0.5f);
            drawItemGlyph(canvas, icon, item, selected, !vanillaChrome);

            if (item.count > 1) {
                text.setTextAlign(Paint.Align.RIGHT);
                text.setFakeBoldText(true);
                text.setTextSize(sp(11));
                text.setColor(Color.WHITE);
                canvas.drawText(String.valueOf(item.count), slot.right - dp(4), slot.bottom - dp(5), text);
                text.setFakeBoldText(false);
            }

            if (item.maxDamage > 0 && item.damage >= 0) {
                float durability = clamp01(1f - (item.damage / (float) Math.max(1, item.maxDamage)));
                RectF bar = new RectF(slot.left + dp(8), slot.bottom - dp(14), slot.right - dp(8), slot.bottom - dp(9));
                fill.setStyle(Paint.Style.FILL);
                fill.setColor(Color.rgb(45, 38, 35));
                canvas.drawRect(bar, fill);
                scratch.set(bar.left, bar.top, bar.left + bar.width() * durability, bar.bottom);
                fill.setColor(durability > 0.5f ? Color.rgb(78, 220, 72) : durability > 0.25f ? Color.rgb(230, 202, 61) : Color.rgb(225, 67, 55));
                canvas.drawRect(scratch, fill);
            }

            // 1.0.112: do not draw per-slot + global selection outlines together.
            // That created the double border around the selected item.

        }
        drawOffhandSlot(canvas, vanillaChrome);

        // Do not draw a second Android-side hover/touch outline over the hotbar.
        // Minecraft's hotbar_selection texture is the only selection chrome the user
        // should see. The temporary outline/tooltip used here made a tapped slot flash
        // with a foreign-looking border before the vanilla selector caught up.
    }

    private void drawOffhandSlot(@NonNull Canvas canvas, boolean vanillaChrome) {
        HudItem item = hudState == null ? HudItem.empty() : hudState.offhand;
        if (item == null || item.isEmpty()) {
            offhandSlot.setEmpty();
            return;
        }
        RectF first = hotbarSlots[0];
        float side = Math.min(first.width(), first.height());
        float gap = Math.max(dp(5f), side * 0.18f);
        boolean mainArmLeft = hudState.mainArm.toLowerCase(Locale.ROOT).contains("left");
        float left = mainArmLeft ? hotbarOuter.right + gap : hotbarOuter.left - gap - side;
        float top = first.top;
        if (left < dp(4f)) left = hotbarOuter.right + gap;
        if (left + side > getWidth() - dp(4f)) left = hotbarOuter.left - gap - side;
        offhandSlot.set(left, top, left + side, top + side);

        if (vanillaChrome) {
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(Color.argb(235, 20, 20, 20));
            canvas.drawRect(offhandSlot, fill);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(Math.max(dp(1.4f), side * 0.035f));
            stroke.setColor(Color.rgb(145, 145, 145));
            canvas.drawRect(offhandSlot, stroke);
        } else {
            drawRoundRect(canvas, offhandSlot, Color.rgb(27, 28, 34), Color.rgb(88, 88, 98), 5f);
        }

        float iconSide = side * 0.76f;
        float iconLeft = offhandSlot.centerX() - iconSide * 0.5f;
        float iconTop = offhandSlot.centerY() - iconSide * 0.5f;
        scratch.set(iconLeft, iconTop, iconLeft + iconSide, iconTop + iconSide);
        drawItemGlyph(canvas, scratch, item, false, false);

        if (item.count > 1) {
            text.setTextAlign(Paint.Align.RIGHT);
            text.setFakeBoldText(true);
            text.setTextSize(sp(10));
            text.setColor(Color.WHITE);
            canvas.drawText(String.valueOf(item.count), offhandSlot.right - dp(3), offhandSlot.bottom - dp(4), text);
            text.setFakeBoldText(false);
        }
        if (item.maxDamage > 0 && item.damage >= 0) {
            float durability = clamp01(1f - (item.damage / (float) Math.max(1, item.maxDamage)));
            float inset = side * 0.16f;
            scratch.set(offhandSlot.left + inset, offhandSlot.bottom - dp(9), offhandSlot.right - inset, offhandSlot.bottom - dp(6));
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(Color.rgb(45, 38, 35));
            canvas.drawRect(scratch, fill);
            scratch2.set(scratch.left, scratch.top, scratch.left + scratch.width() * durability, scratch.bottom);
            fill.setColor(durability > 0.5f ? Color.rgb(78, 220, 72) : durability > 0.25f ? Color.rgb(230, 202, 61) : Color.rgb(225, 67, 55));
            canvas.drawRect(scratch2, fill);
        }
    }

    private void syncSelectedSlotFromHudState() {
        if (hudState == null || !hudState.isFresh()) return;
        int live = hudState.selectedSlot;
        if (live < 0 || live >= SLOT_COUNT) return;

        // Touch selection is optimistic: the bottom HUD changes immediately, while the
        // Fabric state writer can still contain the previous slot for one or two polls.
        // Never let that stale value flash the old selector. Once Minecraft reports the
        // tapped slot we clear the pending state immediately; if it never acknowledges
        // the key press, the timeout restores the live Minecraft state safely.
        if (pendingTouchHotbarSlot >= 0) {
            if (live == pendingTouchHotbarSlot) {
                selectedSlot = live;
                pendingTouchHotbarSlot = -1;
                pendingTouchHotbarUntilMs = 0L;
                return;
            }
            if (SystemClock.uptimeMillis() < pendingTouchHotbarUntilMs) {
                selectedSlot = pendingTouchHotbarSlot;
                return;
            }
            pendingTouchHotbarSlot = -1;
            pendingTouchHotbarUntilMs = 0L;
        }

        // Keep the current selector stable for the duration of an active drag gesture.
        if (activeTouches.size() > 0 && touchedHotbarSlot >= 0) return;
        if (selectedSlot != live) selectedSlot = live;
    }

    private void selectHotbarByTouch(int slot) {
        if (slot < 0 || slot >= SLOT_COUNT) return;
        selectedSlot = slot;
        touchedHotbarSlot = slot;
        pendingTouchHotbarSlot = slot;
        pendingTouchHotbarUntilMs = SystemClock.uptimeMillis() + TOUCH_HOTBAR_ACK_TIMEOUT_MS;
        sendKeyTap(LwjglGlfwKeycode.GLFW_KEY_1 + slot);
    }

    private void drawStrongHotbarSelectionOverlay(@NonNull Canvas canvas, @NonNull RectF slot, boolean activeTouch) {
        float grow = Math.max(dp(activeTouch ? 1.2f : 1.0f), slot.width() * (activeTouch ? 0.030f : 0.020f));
        scratch.set(slot.left - grow, slot.top - grow, slot.right + grow, slot.bottom + grow);
        // 1.0.108: outline only.  The previous translucent fill was drawn over the
        // item after touch/controller selection, which made the icon flash dark/light.
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(dp(activeTouch ? 2.0f : 1.6f), slot.width() * 0.030f));
        stroke.setColor(activeTouch ? Color.rgb(255, 246, 190) : Color.rgb(245, 245, 245));
        canvas.drawRoundRect(scratch, dp(5), dp(5), stroke);
        // Single outline only; vanilla hotbar_selection already provides the normal selected border.
    }

    private void drawHotbarSlotTouchFeedback(@NonNull Canvas canvas, @NonNull RectF slot, @NonNull HudItem item) {
        float grow = Math.max(dp(2), slot.width() * 0.09f);
        scratch.set(slot.left - grow, slot.top - grow, slot.right + grow, slot.bottom + grow);
        // 1.0.108: no alpha fill over the item.  The border still shows the user
        // exactly what slot is being touched without dimming the item bitmap.
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(dp(2.4f), slot.width() * 0.048f));
        stroke.setColor(Color.rgb(255, 246, 190));
        canvas.drawRoundRect(scratch, dp(5), dp(5), stroke);
    }

    private void drawHotbarTouchTooltip(@NonNull Canvas canvas, int slot, @NonNull HudItem item) {
        String label = item.displayLabel();
        if (label == null || label.trim().isEmpty()) label = "Slot " + (slot + 1);
        else label = (slot + 1) + ": " + label;
        text.setTextSize(sp(9));
        text.setFakeBoldText(true);
        text.setTextAlign(Paint.Align.CENTER);
        float maxWidth = Math.max(dp(80), hotbarOuter.width() * 0.48f);
        label = fitText(label, maxWidth, text);
        float tw = text.measureText(label);
        RectF source = hotbarSlots[Math.max(0, Math.min(SLOT_COUNT - 1, slot))];
        float cx = source.centerX();
        float w = Math.min(maxWidth + dp(16), tw + dp(18));
        float h = dp(20);
        float left = Math.max(dp(8), Math.min(getWidth() - dp(8) - w, cx - w * 0.5f));
        float top = hotbarOuter.top - h - dp(8);
        scratch.set(left, top, left + w, top + h);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(Color.argb(210, 10, 12, 18));
        canvas.drawRoundRect(scratch, dp(8), dp(8), fill);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(1));
        stroke.setColor(Color.argb(150, 255, 246, 190));
        canvas.drawRoundRect(scratch, dp(8), dp(8), stroke);
        text.setColor(Color.WHITE);
        canvas.drawText(label, scratch.centerX(), scratch.centerY() + dp(3.5f), text);
        text.setFakeBoldText(false);
    }

    private void drawBitmapVisibleCropped(@NonNull Canvas canvas, @NonNull Bitmap bitmap, @NonNull RectF dst) {
        if (bitmap.isRecycled() || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) return;
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 8) {
                    if (x < minX) minX = x;
                    if (y < minY) minY = y;
                    if (x > maxX) maxX = x;
                    if (y > maxY) maxY = y;
                }
            }
        }
        fill.setFilterBitmap(false);
        fill.setDither(false);
        if (maxX < minX || maxY < minY) {
            canvas.drawBitmap(bitmap, null, dst, fill);
            return;
        }
        Rect src = new Rect(Math.max(0, minX - 1), Math.max(0, minY - 1), Math.min(w, maxX + 2), Math.min(h, maxY + 2));
        // 1.0.115: draw cached TESR icons slightly inside the requested icon rect.
        // Cropping transparent pixels is still useful, but mapping the cropped source
        // to the full slot made beds/chests/shulkers look larger than vanilla.
        RectF finalDst = new RectF(dst);
        float extraPad = Math.max(dp(0.75f), Math.min(dst.width(), dst.height()) * 0.045f);
        finalDst.inset(extraPad, extraPad);
        canvas.drawBitmap(bitmap, src, finalDst, fill);
    }

    private void drawItemGlyph(@NonNull Canvas canvas, @NonNull RectF icon, @NonNull HudItem item, boolean selected, boolean drawBackplate) {
        if (item.isEmpty()) {
            if (drawBackplate) {
                drawRoundRect(canvas, icon, Color.rgb(37, 39, 46), selected ? Color.rgb(255, 246, 190) : Color.rgb(72, 78, 92), 8f);
                fill.setStyle(Paint.Style.FILL);
                fill.setColor(Color.rgb(78, 84, 96));
                canvas.drawCircle(icon.centerX(), icon.centerY(), Math.max(dp(1.4f), icon.width() * 0.025f), fill);
            }
            return;
        }

        String key = item.itemKey();
        int border = selected ? Color.rgb(255, 246, 190) : Color.rgb(74, 82, 96);
        if (drawBackplate) drawRoundRect(canvas, icon, Color.rgb(31, 34, 42), border, 8f);
        scratch2.set(icon.left + dp(0.75f), icon.top + dp(0.75f), icon.right - dp(0.75f), icon.bottom - dp(0.75f));

        int requestedIconSize = Math.max(24, Math.min(128, Math.round(Math.min(scratch2.width(), scratch2.height()))));
        // 1.0.148: do not draw pre-rendered/disk cached icon PNGs before the
        // Android asset translator.  The old rendered_icons cache is exactly what kept
        // chests, chest boats, stonecutter, pots, shulkers, etc. looking unchanged after
        // the 26.x item-definition translator was added.  The correct order is now:
        // installed assets/item definition -> Android JSON/special model renderer ->
        // normal flat icon fallback.
        //
        // externalRenderedIconCache is kept only as a write-through/debug cache holder for
        // now; it must not be allowed to win the draw path.

        Bitmap flatIcon = loadItemIcon(item.iconPath);
        Bitmap rendererFlatIcon = flatIcon;

        // 1.0.159: beds were originally fixed by using the finished/private HUD item
        // path, not by drawing the raw bed entity sheet as a manual crop. Let a verified
        // Minecraft-captured finished icon win for beds before the special renderer path.
        // Normal chests are intentionally not included here.
        if (isBedItem(key) && drawCapturedMinecraftIcon(canvas, scratch2, item, key, flatIcon)) {
            return;
        }

        // Keep the verified-live-icon-first route only for items that have proven final
        // Minecraft item captures. Shulkers/dripleaf/pots stay on the shaped Android path.
        if (shouldPreferLiveVanillaIconForKnownMismatch(key)
                && drawCapturedMinecraftIcon(canvas, scratch2, item, key, flatIcon)) {
            return;
        }

        boolean specialRendererFamily = isMinecraftSpecialRendererItem(key);

        // Fix36: do NOT let raw/captured atlas tiles win for block-entity and template
        // item families.  Chests, beds, shulkers, pots, fences, walls, buttons and similar
        // items often expose a valid PNG path that is only a source/material texture, not
        // the final Minecraft GUI icon.  Drawing that first is what turns them into brown,
        // purple, terracotta, or plank squares.  Route those families straight into the
        // Android JSON/synthetic renderer and pass null for the flat texture so it cannot
        // fall back to the wrong square.
        if (specialRendererFamily && drawCachedItemIcon(canvas, scratch2, item, key, null)) {
            return;
        }

        // Only normal generated items may use Minecraft-captured flat icons.  The hidden
        // hotbar-probe path is not trusted for special/block-entity items because it can
        // leak to the main screen and it commonly captures source material tiles.
        if (drawCapturedMinecraftIcon(canvas, scratch2, item, key, flatIcon)) {
            return;
        }

        // No-flicker fallback path. The finished icon is cached so moving the mouse does
        // not force 9 z-buffer rasterizations every redraw.
        if (drawCachedItemIcon(canvas, scratch2, item, key, rendererFlatIcon)) {
            return;
        }

        drawMissingItemPlaceholder(canvas, scratch2);
    }


    @NonNull
    private String renderedIconLookupKey(@NonNull HudItem item, @NonNull String key) {
        String label = item.displayLabel();
        if (label == null || label.trim().isEmpty()) return key;
        String clean = label.trim().toLowerCase(Locale.ROOT)
                .replace(' ', '_')
                .replace('-', '_')
                .replaceAll("[^a-z0-9_:.]+", "_");
        while (clean.contains("__")) clean = clean.replace("__", "_");
        if (clean.startsWith("_")) clean = clean.substring(1);
        if (clean.endsWith("_")) clean = clean.substring(0, clean.length() - 1);
        if (clean.isEmpty() || key.toLowerCase(Locale.ROOT).contains(clean)) return key;
        return key + "|" + clean;
    }

    private boolean shouldPreferAndroidSpecialRenderer(@NonNull String key) {
        String value = key == null ? "" : key.toLowerCase(Locale.ROOT);

        // 1.0.148: every item family that can be represented by a Minecraft 26.x
        // assets/minecraft/items/*.json definition, JSON model, or generated Android
        // special model must bypass the old rendered_icons PNG cache.  The previous
        // method only returned true for beds/template blocks, which is why beds changed
        // but chests, chest boats, stonecutter, pots, shulkers, and banners kept drawing
        // stale cached images.
        return isMinecraftSpecialRendererItem(value)
                || isChestBoatItem(value)
                || isChestMinecartItem(value)
                || isJsonBlockModelItem(value)
                || isWallItem(value)
                || isFenceItem(value)
                || isButtonItem(value)
                || isTrapdoorItem(value)
                || value.contains("stairs")
                || isSculkLikeBlockItem(value)
                || isBedItem(value)
                || isFlowerPotItem(value);
    }

    private boolean drawCachedItemIcon(@NonNull Canvas canvas, @NonNull RectF dst,
                                       @NonNull HudItem item, @NonNull String key,
                                       @Nullable Bitmap flatIcon) {
        int size = Math.max(24, Math.min(128, Math.round(Math.min(dst.width(), dst.height()))));
        String cacheKey = renderedIconCacheKey(item, key, flatIcon, size);
        Bitmap cached = renderedItemCache.get(cacheKey);
        if (cached != null && !cached.isRecycled()) {
            fill.setFilterBitmap(false);
            fill.setDither(false);
            canvas.drawBitmap(cached, null, dst, fill);
            return true;
        }
        if (renderedItemCache.size() > 96) recycleBitmapCache(renderedItemCache);

        Bitmap rendered = null;
        try {
            rendered = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas offscreen = new Canvas(rendered);
            RectF local = new RectF(0f, 0f, size, size);
            boolean drawn = drawJsonModelIcon(offscreen, local, item, key, flatIcon);
            if (!drawn && flatIcon != null) {
                fill.setFilterBitmap(false);
                fill.setDither(false);
                offscreen.drawBitmap(flatIcon, null, local, fill);
                drawn = true;
            }
            if (!drawn || isBitmapEffectivelyBlank(rendered)) {
                if (rendered != null && !rendered.isRecycled()) rendered.recycle();
                return false;
            }
            renderedItemCache.put(cacheKey, rendered);
            externalRenderedIconCache.store(cacheKey, rendered);
            fill.setFilterBitmap(false);
            fill.setDither(false);
            canvas.drawBitmap(rendered, null, dst, fill);
            return true;
        } catch (Throwable throwable) {
            if (rendered != null && !rendered.isRecycled()) rendered.recycle();
            Logging.e(TAG, "Unable to render cached dual-screen item icon " + key, throwable);
            return false;
        }
    }

    @NonNull
    private String renderedIconCacheKey(@NonNull HudItem item, @NonNull String key,
                                        @Nullable Bitmap flatIcon, int size) {
        StringBuilder out = new StringBuilder(160);
        out.append("fix174|").append(key).append('|').append(item.id).append('|').append(item.label)
                .append('|').append(size).append('|').append(item.iconPath);
        // Fix103: do not call quickJsonModelStamp() or quickSpecialTextureStamp() from the
        // draw path. Those helpers scan droidbridge_dual_screen_assets/model_jsons and
        // special_textures for every hotbar item on every View redraw, which makes the game
        // feel laggy even though the Minecraft renderer itself is still near 120 FPS.
        // Model/texture changes during a running world are rare; if they happen, the cache
        // naturally clears on size changes / detach, and the item icon path/id changes when
        // the state writer exports a different item.
        if (flatIcon != null) out.append("|b=").append(flatIcon.getWidth()).append('x').append(flatIcon.getHeight());
        return out.toString();
    }

    @NonNull
    private String quickSpecialTextureStamp(@NonNull String key) {
        String value = key.toLowerCase(Locale.ROOT);
        ArrayList<String> names = new ArrayList<>();
        if (isChestLikeItem(value)) {
            if (value.contains("ender_chest")) names.add("chest_ender.png");
            else if (value.contains("trapped_chest")) names.add("chest_trapped.png");
            else names.add("chest_normal.png");
        } else if (isBedItem(value)) {
            names.add("bed_" + bedColorNameForKey(value) + ".png");
            names.add(bedColorNameForKey(value) + "_bed.png");
        } else if (isBannerItem(value)) {
            names.add("banner_base.png");
        } else if (isShulkerBoxItem(value)) {
            String color = shulkerColorNameForKey(value);
            if (color.length() > 0) names.add("shulker_" + color + ".png");
            names.add("shulker.png");
        } else if (isDecoratedPotLikeItem(value)) {
            names.add("decorated_pot.png");
            names.add("decorated_pot_side.png");
        }
        if (names.isEmpty()) return "";
        StringBuilder out = new StringBuilder(96);
        out.append(quickSpecialTextureDirectoryStamp());
        for (String name : names) {
            File f = resolveSpecialTextureFile(name);
            out.append('|').append(name).append('=');
            if (f != null && f.isFile()) out.append(f.lastModified()).append(':').append(f.length());
            else out.append("missing");
        }
        return out.toString();
    }

    @NonNull
    private String quickSpecialTextureDirectoryStamp() {
        ArrayList<File> dirs = new ArrayList<>();
        File parent = hudStateFile.getParentFile();
        addSpecialTextureDirCandidate(dirs, parent);
        if (parent != null) {
            addSpecialTextureDirCandidate(dirs, new File(parent, "game"));
            File grand = parent.getParentFile();
            if (grand != null) {
                addSpecialTextureDirCandidate(dirs, grand);
                addSpecialTextureDirCandidate(dirs, new File(grand, "game"));
            }
        }
        long latest = 0L;
        long count = 0L;
        long total = 0L;
        for (File dir : dirs) {
            if (dir == null || !dir.isDirectory()) continue;
            latest = Math.max(latest, dir.lastModified());
            File[] files = dir.listFiles();
            if (files == null) continue;
            count += files.length;
            for (File f : files) {
                if (f != null && f.isFile()) {
                    latest = Math.max(latest, f.lastModified());
                    total += Math.max(0L, f.length());
                }
            }
        }
        return "dir=" + latest + ':' + count + ':' + total;
    }

    private void addSpecialTextureDirCandidate(@NonNull ArrayList<File> dirs, @Nullable File base) {
        if (base == null) return;
        File dir = new File(new File(base, "droidbridge_dual_screen_assets"), "special_textures");
        String path = dir.getAbsolutePath();
        for (File existing : dirs) if (path.equals(existing.getAbsolutePath())) return;
        dirs.add(dir);
    }

    @NonNull
    private String shulkerColorNameForKey(@NonNull String key) {
        String value = key.toLowerCase(Locale.ROOT);
        String[] colors = new String[] {
                "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
                "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"
        };
        for (String color : colors) if (value.contains(color + "_shulker_box")) return color;
        return "";
    }

    @NonNull
    private String quickJsonModelStamp(@NonNull HudItem item) {
        String cleanId = item.id == null ? "" : item.id.trim().toLowerCase(Locale.ROOT);
        if (cleanId.isEmpty()) cleanId = item.itemKey();
        if (cleanId.isEmpty()) return "";
        String fullSafe = cleanId.replace(':', '_').replace('/', '_') + ".json";
        String shortSafe = cleanId;
        int colon = cleanId.indexOf(':');
        if (colon >= 0 && colon + 1 < cleanId.length()) shortSafe = cleanId.substring(colon + 1);
        shortSafe = shortSafe.replace(':', '_').replace('/', '_') + ".json";
        File file = resolveJsonModelFile(fullSafe);
        if (file == null) file = resolveJsonModelFile(shortSafe);
        if (file == null || !file.isFile()) return "";
        return file.getAbsolutePath() + '#' + file.lastModified() + '#' + file.length();
    }

    private boolean isBitmapEffectivelyBlank(@NonNull Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        if (w <= 0 || h <= 0) return true;
        int stepX = Math.max(1, w / 12);
        int stepY = Math.max(1, h / 12);
        for (int y = 0; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 8) return false;
            }
        }
        return true;
    }

    private boolean drawJsonModelIcon(@NonNull Canvas canvas, @NonNull RectF r, @NonNull HudItem item,
                                      @NonNull String key, @Nullable Bitmap flatIcon) {
        ModelDefinition model = loadJsonModelDefinition(item);


        // 1.0.159: preserve the original fixed bed route.  Beds must use the
        // DroidBridge/BetterBeds-shaped model before the installed 26.x special item
        // definition or the manual entity-sheet crop can take over.  This is the path
        // that fixed the bottom HUD without touching Minecraft's own inventory/hand model.
        if (isBedItem(key)) {
            if (model != null && model.elements.size() > 0
                    && renderJsonModel(canvas, r, model, guiTransformForModel(model, key))) {
                return true;
            }
            if (drawGeneratedBetterBedModelIcon(canvas, r, key)) return true;
        }


        // 1.0.174: these three item families are the ones still not represented correctly
        // by the Android cuboid/template approximations. Use DroidBridge-private reference
        // icons bundled in the companion Fabric jar, so Minecraft's own inventory/hand
        // models stay untouched and fixed beds/shulkers/chests do not regress.
        if (isDecoratedPotLikeItem(key) && drawDroidBridgeReferenceItemIcon(canvas, r, referenceIconNameForItem(item, key))) return true;
        if (isStonecutterItem(key) && drawDroidBridgeReferenceItemIcon(canvas, r, referenceIconNameForItem(item, key))) return true;
        if (isBannerItem(key) && drawDroidBridgeReferenceItemIcon(canvas, r, referenceIconNameForItem(item, key))) return true;

        // Asset-translator path: Minecraft 1.21.5+/26.x item definitions live under
        // assets/<namespace>/items/<item>.json. This handles minecraft:model,
        // minecraft:select, minecraft:composite, and minecraft:special before the older
        // bundled-PNG/synthetic fallback code can draw the wrong material tile.
        if (drawInstalledItemDefinitionIcon(canvas, r, item, key)) return true;

        // Trapdoors must not go through the generic JSON first-pass.  Some exported
        // item parents collapse to a cube/full-block particle model, which is why the
        // last test turned trapdoors into cubes.  Force the same thin 3px cuboid path
        // used by the last correct trapdoor version before any generic model draw.
        if (isTrapdoorItem(key)) {
            if (model != null && renderSyntheticTrapdoorModel(canvas, r, model, flatIcon)) return true;
            if (flatIcon != null && drawBitmapAsThinTrapdoor(canvas, r, flatIcon)) return true;
        }

        // 1.0.156: these two vanilla items are technically block/plant models, but the
        // generic Android cuboid renderer makes them look like oversized flat/boxy assets
        // compared to the real hotbar. Give them tiny dedicated renderers before the generic
        // JSON path; verified captured Minecraft icons still win earlier when available.
        if (isDripleafItem(key) && drawDripleafLikeIcon(canvas, r, key, model, flatIcon)) return true;
        if (isLecternItem(key) && drawLecternLikeIcon(canvas, r, model, flatIcon)) return true;

        // Vanilla inventory-template items must be handled before the generic JSON pass.
        // Their exported model can be partial, texture-only, or flat/generated depending on
        // which parent was resolved.  Build the actual inventory parent shape here so all
        // materials in the same family render consistently: every wall, every fence, every
        // button, and scaffolding.
        if (isButtonItem(key) && drawButtonLikeIcon(canvas, r, key, model, flatIcon)) return true;
        if (isFenceItem(key) && drawFenceLikeIcon(canvas, r, key, model, flatIcon)) return true;
        if (isWallItem(key) && drawWallLikeIcon(canvas, r, key, model, flatIcon)) return true;
        if (isScaffoldingItem(key) && drawScaffoldingLikeIcon(canvas, r, model, flatIcon)) return true;

        // First try the real resolved JSON model for normal block/item models after the
        // template families above have had a chance to render.
        if (model != null && model.elements.size() > 0
                && !isTrapdoorItem(key)
                && !isChestLikeItem(key)
                && !isBedItem(key)
                && !isBannerItem(key)
                && !isShulkerBoxItem(key)
                && !isDecoratedPotLikeItem(key)
                && !isButtonItem(key)
                && !isFenceItem(key)
                && !isWallItem(key)
                && !isScaffoldingItem(key)) {
            if (renderJsonModel(canvas, r, model, guiTransformForModel(model, key))) return true;
        }

        // BetterBeds-style resource packs convert beds into a normal JSON item model.
        // That is the cleanest workaround for the missing middle bed frame: use the real
        // model when one is available, then fall back to the old Android bed silhouette.
        if (isBedItem(key) && model != null && model.elements.size() > 0) {
            if (renderJsonModel(canvas, r, model, guiTransformForModel(model, key))) return true;
        }

        // Special/block-entity style fallbacks. These only run after the real JSON/template
        // model path failed or exported no elements.
        if (isChestBoatItem(key) && drawChestBoatLikeIcon(canvas, r, key, flatIcon)) return true;
        if (isChestMinecartItem(key) && drawChestMinecartLikeIcon(canvas, r, key, flatIcon)) return true;
        if (isBedItem(key) && drawBedLikeIcon(canvas, r, key, flatIcon)) return true;
        if (isBannerItem(key) && drawBannerLikeIcon(canvas, r, key, flatIcon)) return true;
        if (isShulkerBoxItem(key) && drawShulkerBoxLikeIcon(canvas, r, key, model, flatIcon)) return true;
        if (isChestLikeItem(key) && drawChestLikeIcon(canvas, r, key, model, flatIcon)) return true;
        if (isDecoratedPotLikeItem(key)) {
            if (drawDecoratedPotLikeIcon(canvas, r, model, flatIcon)) return true;
        }
        if (model != null && model.elements.size() > 0) {
            // Do not replace trapdoor JSON with a single synthetic texture first. The real
            // model contains separate top/bottom/side face assignments, and those are needed
            // for the hotbar preview to look like Minecraft instead of a flat one-texture tile.
            if (isTrapdoorItem(key) && modelLooksLikeFullCube(model)) {
                if (renderSyntheticTrapdoorModel(canvas, r, model, flatIcon)) return true;
            } else if (renderJsonModel(canvas, r, model, guiTransformForModel(model, key))) {
                return true;
            }
        }

        if (isTrapdoorItem(key)) {
            if (model != null && renderSyntheticTrapdoorModel(canvas, r, model, flatIcon)) return true;
            if (flatIcon != null && drawBitmapAsThinTrapdoor(canvas, r, flatIcon)) return true;
        }

        // Let real JSON models handle these first. If the exporter only gave us particle
        // or incomplete data, use a cheap fallback preview instead of overriding a valid
        // vanilla cuboid model with a worse synthetic drawing.
        if (isStonecutterItem(key) && drawStonecutterLikeIcon(canvas, r, model, flatIcon)) return true;
        if (isScaffoldingItem(key) && drawScaffoldingLikeIcon(canvas, r, model, flatIcon)) return true;

        // item/generated and other texture-only JSON models use layer0/layer1. For special
        // non-cuboid items such as decorated pots, prefer the already-exported vanilla item
        // icon when present; their JSON texture can be a raw template/material texture, not
        // the composed hotbar icon.
        if (model != null) {
            if (flatIcon != null && shouldPreferFlatIconForGeneratedItem(key, model)) {
                fill.setFilterBitmap(false);
                fill.setDither(false);
                canvas.drawBitmap(flatIcon, null, r, fill);
                return true;
            }
            String layer0 = firstResolvedTexture(model, "layer0", "particle", "texture", "all", "side", "top");
            Bitmap tex = layer0 == null ? null : loadJsonTexture(layer0);
            if (tex != null) {
                fill.setFilterBitmap(false);
                fill.setDither(false);
                canvas.drawBitmap(tex, null, r, fill);
                return true;
            }
        }
        return false;
    }

    private boolean drawInstalledItemDefinitionIcon(@NonNull Canvas canvas,
                                                    @NonNull RectF r,
                                                    @NonNull HudItem item,
                                                    @NonNull String key) {
        final String cleanId = item.id == null || item.id.trim().isEmpty()
                ? item.itemKey()
                : item.id.trim().toLowerCase(Locale.ROOT);
        if (cleanId.isEmpty()) return false;

        // 1.0.149: never do expensive/unsupported 26.x item-definition translation
        // from the Android draw path for special families that are not fully translated
        // yet. Shulker boxes and decorated/flower pots caused UI-thread stalls/ANRs
        // because their item definitions were resolved and rasterized during onDraw().
        // Let the existing lightweight Android fallbacks draw them until they get a
        // background-rendered translator.
        if (!shouldUseInstalledItemDefinitionPath(cleanId, key)) return false;

        DualScreenItemDefinitionTranslator.RenderPlan plan =
                DualScreenMinecraftAssetRendererService.resolveInstalledItemDefinition(
                        cleanId,
                        new DualScreenMinecraftAssetRendererService.AssetResolver() {
                            @Override public File resolveAssetFile(@NonNull String relativePath) {
                                return resolveExportedAssetFile(relativePath);
                            }
                        },
                        System.currentTimeMillis()
                );
        if (plan == null || plan.isEmpty()) return false;

        boolean drew = false;
        ArrayList<String> drawnSpecialKeys = new ArrayList<>();
        for (DualScreenItemDefinitionTranslator.Layer layer : plan.layers) {
            try {
                if (layer.kind == DualScreenItemDefinitionTranslator.Layer.KIND_MODEL) {
                    File modelFile = resolveModelAssetByResourceLocation(layer.modelId);
                    if (modelFile == null || !modelFile.isFile()) continue;
                    ModelDefinition model = loadModelDefinitionFromFile(modelFile, 0);
                    if (model != null && model.elements.size() > 0) {
                        drew |= renderJsonModel(canvas, r, model, guiTransformForModel(model, key));
                    }
                    continue;
                }

                if (layer.kind == DualScreenItemDefinitionTranslator.Layer.KIND_SPECIAL) {
                    String specialKey = layer.specialType + "|" + layer.texture + "|" + layer.part;
                    // Bed item definitions are composite head+foot special layers. Our current
                    // Android model factory builds the complete bed model from the installed
                    // bed texture, so avoid drawing the same full bed twice.
                    String type = DualScreenItemDefinitionTranslator.normalizeType(layer.specialType);
                    if ("minecraft:bed".equals(type)) {
                        specialKey = layer.specialType + "|" + layer.texture + "|whole_bed";
                    }
                    if (drawnSpecialKeys.contains(specialKey)) continue;
                    drawnSpecialKeys.add(specialKey);

                    JSONObject generated = DualScreenMinecraftAssetRendererService.buildGeneratedSpecialModel(layer);
                    if (generated == null) continue;
                    ModelDefinition generatedModel = ModelDefinition.fromJson(generated);
                    if (generatedModel != null && generatedModel.elements.size() > 0) {
                        drew |= renderJsonModel(canvas, r, generatedModel, guiTransformForModel(generatedModel, key));
                    }
                }
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to render installed item definition for " + cleanId, throwable);
            }
        }
        return drew;
    }


    private boolean shouldUseInstalledItemDefinitionPath(@NonNull String cleanId, @NonNull String key) {
        String value = (cleanId + " " + key).toLowerCase(Locale.ROOT);

        // These families currently need dedicated generated geometry and/or layered
        // pattern composition. Resolving their assets synchronously in onDraw() is what
        // produced the ANRs seen when a shulker box was placed in the HUD.
        if (value.contains("shulker_box")) return false;
        if (value.contains("decorated_pot") || value.contains("decorative_pot")) return false;
        if (value.contains("flower_pot") || value.contains("pottery_sherd")) return false;
        if (value.endsWith("_banner") || value.contains(":banner") || value.contains("/banner")) return false;
        if (value.contains("skull") || value.contains("head")) return false;
        if (value.contains("shield")) return false;
        if (value.contains("sign")) return false;

        // Keep the proven/cheap paths active. Beds are the known working case, and
        // chest/ender/trapped chest can use the generated chest cuboid path.
        if (isBedItem(value)) return true;
        if (isChestLikeItem(value)) return true;
        if (isStonecutterItem(value)) return true;

        // Normal model definitions are okay, but template families already have cheap
        // local Android renderers below and do not need this new path.
        return !isMinecraftSpecialRendererItem(value);
    }


    @Nullable
    private ModelDefinition loadJsonModelDefinition(@NonNull HudItem item) {
        String cleanId = item.id == null ? "" : item.id.trim().toLowerCase(Locale.ROOT);
        if (cleanId.isEmpty()) cleanId = item.itemKey();
        if (cleanId.isEmpty()) return null;
        String fullSafe = cleanId.replace(':', '_').replace('/', '_') + ".json";
        String shortSafe = cleanId;
        int colon = cleanId.indexOf(':');
        if (colon >= 0 && colon + 1 < cleanId.length()) shortSafe = cleanId.substring(colon + 1);
        shortSafe = shortSafe.replace(':', '_').replace('/', '_') + ".json";
        File file = null;
        // 1.0.145: beds must use DroidBridge's Android-only BetterBeds model, not
        // assets/minecraft overrides. 1.0.141 proved this fixes the bottom HUD, but
        // placing the model in assets/minecraft made Minecraft itself load it and broke
        // inventory/hand rendering. Keep this path private to Android so the game remains vanilla.
        boolean bedItem = isBedItem(cleanId);
        if (bedItem) {
            file = resolveDroidBridgeAndroidOnlyModelFile(cleanId);
        }
        if (file == null) file = resolveJsonModelFile(fullSafe);
        if (file == null) file = resolveJsonModelFile(shortSafe);
        if (!bedItem) {
            if (file == null) file = resolveVanillaModelAssetFile(cleanId, true);
            if (file == null) file = resolveVanillaModelAssetFile(cleanId, false);
        }
        if (file == null || !file.isFile() || file.length() <= 0L) return null;
        String cacheKey = "model/" + file.getAbsolutePath() + "#" + file.lastModified() + "#" + file.length();
        ModelDefinition cached = jsonModelCache.get(cacheKey);
        if (cached != null) return cached;
        if (jsonModelCache.size() > 192) jsonModelCache.clear();
        try {
            ModelDefinition model = loadModelDefinitionFromFile(file, 0);
            if (model != null) jsonModelCache.put(cacheKey, model);
            return model;
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to load dual-screen JSON model " + file.getAbsolutePath(), throwable);
            return null;
        }
    }

    @Nullable
    private ModelDefinition loadModelDefinitionFromFile(@NonNull File file, int depth) throws Exception {
        if (depth > 24 || !file.isFile() || file.length() <= 0L) return null;
        byte[] bytes = readFileBytes(file);
        JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));

        // Minecraft 1.21.5+/26.x can route item definitions through assets/<ns>/items/*.json
        // instead of a direct assets/<ns>/models/item/*.json.  These wrapper files usually
        // look like {"model":{"type":"minecraft:model","model":"minecraft:block/foo"}}.
        // Follow that target so the Android HUD can use the actual vanilla model from the
        // already-installed game jar instead of needing bundled launcher assets.
        JSONObject itemWrapper = root.optJSONObject("model");
        if (itemWrapper != null) {
            String type = itemWrapper.optString("type", "");
            String target = itemWrapper.optString("model", "");
            if (target.length() > 0 && (type.length() == 0 || type.endsWith(":model") || "model".equals(type))) {
                File targetFile = resolveModelAssetByResourceLocation(target);
                if (targetFile != null && targetFile.isFile()) {
                    ModelDefinition targetModel = loadModelDefinitionFromFile(targetFile, depth + 1);
                    if (targetModel != null) return targetModel;
                }
            }
        }

        ModelDefinition child = ModelDefinition.fromJson(root);
        if (child.parent.length() > 0) {
            File parentFile = resolveModelAssetByResourceLocation(child.parent);
            if (parentFile != null && parentFile.isFile()) {
                ModelDefinition parent = loadModelDefinitionFromFile(parentFile, depth + 1);
                if (parent != null) return mergeModelDefinitions(parent, child);
            }
        }
        return child;
    }

    @Nullable
    private ModelDefinition mergeModelDefinitions(@NonNull ModelDefinition parent, @NonNull ModelDefinition child) {
        ModelDefinition out = new ModelDefinition();
        out.textures.putAll(parent.textures);
        out.textures.putAll(child.textures);
        out.elements.addAll(child.elements.isEmpty() ? parent.elements : child.elements);
        out.guiTransform = child.guiTransform != null ? child.guiTransform : parent.guiTransform;
        return out;
    }


    @Nullable
    private File resolveDroidBridgeAndroidOnlyModelFile(@NonNull String itemId) {
        String item = itemNameFromKey(itemId);
        if (item.length() == 0) return null;
        File exact = resolveBundledDroidBridgeAssetFile("assets/droidbridge_dualscreen/android_models/item/" + item + ".json");
        if (exact != null && exact.isFile()) return exact;
        return null;
    }

    @Nullable
    private File resolveDroidBridgeAndroidOnlyModelByResourceLocation(@NonNull String modelId) {
        String value = modelId.trim().toLowerCase(Locale.ROOT);
        if (value.length() == 0) return null;
        String namespace = "minecraft";
        String path = value;
        int colon = value.indexOf(':');
        if (colon >= 0) {
            namespace = value.substring(0, colon);
            path = value.substring(colon + 1);
        }
        if (!"droidbridge_dualscreen".equals(namespace)) return null;
        if (path.endsWith(".json")) path = path.substring(0, path.length() - 5);
        if (path.startsWith("models/")) path = path.substring("models/".length());
        if (!(path.startsWith("item/") || path.startsWith("block/"))) return null;
        return resolveBundledDroidBridgeAssetFile("assets/droidbridge_dualscreen/android_models/" + path + ".json");
    }

    @Nullable
    private File resolveBundledDroidBridgeAssetFile(@NonNull String cleanPath) {
        String clean = cleanPath.replace('\\', '/');
        while (clean.startsWith("/")) clean = clean.substring(1);
        ArrayList<File> jars = new ArrayList<>();
        File game = hudStateFile.getParentFile();
        File mcRoot = findMinecraftRoot();
        addJarFilesFromDirectory(jars, game == null ? null : new File(game, "mods"));
        addJarFilesFromDirectory(jars, mcRoot == null ? null : new File(mcRoot, "mods"));
        File parent = game == null ? null : game.getParentFile();
        if (parent != null) addJarFilesFromDirectory(jars, new File(parent, "mods"));
        Collections.sort(jars, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                boolean ad = a.getName().toLowerCase(Locale.ROOT).contains("droidbridge")
                        && a.getName().toLowerCase(Locale.ROOT).contains("dualscreen");
                boolean bd = b.getName().toLowerCase(Locale.ROOT).contains("droidbridge")
                        && b.getName().toLowerCase(Locale.ROOT).contains("dualscreen");
                if (ad != bd) return ad ? -1 : 1;
                long d = b.lastModified() - a.lastModified();
                return d > 0 ? 1 : (d < 0 ? -1 : a.getName().compareTo(b.getName()));
            }
        });
        for (File jar : jars) {
            if (jar == null || !jar.isFile() || jar.length() <= 0L) continue;
            String name = jar.getName().toLowerCase(Locale.ROOT);
            if (!name.contains("droidbridge") || !name.contains("dualscreen")) continue;
            File extracted = extractAssetFromZip(jar, clean);
            if (extracted != null && extracted.isFile() && extracted.length() > 0L) return extracted;
        }
        return null;
    }

    @Nullable
    private File resolveModelAssetByResourceLocation(@NonNull String modelId) {
        String value = modelId.trim().toLowerCase(Locale.ROOT);
        if (value.length() == 0 || value.startsWith("builtin/")) return null;
        File droidBridgePrivateModel = resolveDroidBridgeAndroidOnlyModelByResourceLocation(value);
        if (droidBridgePrivateModel != null && droidBridgePrivateModel.isFile()) return droidBridgePrivateModel;
        String namespace = "minecraft";
        String path = value;
        int colon = value.indexOf(':');
        if (colon >= 0) {
            namespace = value.substring(0, colon);
            path = value.substring(colon + 1);
        }
        if (path.endsWith(".json")) path = path.substring(0, path.length() - 5);
        ArrayList<String> relatives = new ArrayList<>();
        if (path.startsWith("models/")) {
            relatives.add("assets/" + namespace + "/" + path + ".json");
        } else if (path.startsWith("block/") || path.startsWith("item/")) {
            relatives.add("assets/" + namespace + "/models/" + path + ".json");
        } else if (path.startsWith("items/")) {
            relatives.add("assets/" + namespace + "/" + path + ".json");
        } else {
            relatives.add("assets/" + namespace + "/models/item/" + path + ".json");
            relatives.add("assets/" + namespace + "/models/block/" + path + ".json");
            relatives.add("assets/" + namespace + "/items/" + path + ".json");
        }
        for (String relative : relatives) {
            File resolved = resolveExportedAssetFile(relative);
            if (resolved != null && resolved.isFile() && resolved.length() > 0L) return resolved;
        }
        return null;
    }

    @Nullable
    private File resolveVanillaModelAssetFile(@NonNull String itemId, boolean itemModel) {
        String value = itemId.trim().toLowerCase(Locale.ROOT);
        if (value.length() == 0) return null;
        String namespace = "minecraft";
        String path = value;
        int colon = value.indexOf(':');
        if (colon >= 0) {
            namespace = value.substring(0, colon);
            path = value.substring(colon + 1);
        }
        if (path.length() == 0) return null;
        String relative = "assets/" + namespace + "/models/" + (itemModel ? "item/" : "block/") + path.replace('/', '_') + ".json";
        File resolved = resolveExportedAssetFile(relative);
        if (resolved != null) return resolved;
        relative = "assets/" + namespace + "/models/" + (itemModel ? "item/" : "block/") + path + ".json";
        resolved = resolveExportedAssetFile(relative);
        if (resolved != null) return resolved;
        if (itemModel) {
            // 26.x item definitions can live under assets/<namespace>/items/. These are
            // followed by loadModelDefinitionFromFile() to the real model target.
            relative = "assets/" + namespace + "/items/" + path.replace('/', '_') + ".json";
            resolved = resolveExportedAssetFile(relative);
            if (resolved != null) return resolved;
            relative = "assets/" + namespace + "/items/" + path + ".json";
            return resolveExportedAssetFile(relative);
        }
        return null;
    }

    @Nullable
    private File resolveJsonModelFile(@NonNull String name) {
        ArrayList<File> candidates = new ArrayList<>();
        File parent = hudStateFile.getParentFile();
        addJsonModelCandidate(candidates, parent, name);
        if (parent != null) {
            addJsonModelCandidate(candidates, new File(parent, "game"), name);
            File grand = parent.getParentFile();
            if (grand != null) {
                addJsonModelCandidate(candidates, grand, name);
                addJsonModelCandidate(candidates, new File(grand, "game"), name);
            }
        }
        for (File candidate : candidates) {
            if (candidate != null && candidate.isFile() && candidate.length() > 0L) return candidate;
        }
        return null;
    }

    private void addJsonModelCandidate(@NonNull ArrayList<File> candidates, @Nullable File base, @NonNull String name) {
        if (base == null) return;
        File file = new File(new File(new File(base, "droidbridge_dual_screen_assets"), "model_jsons"), name);
        String path = file.getAbsolutePath();
        for (File existing : candidates) {
            if (path.equals(existing.getAbsolutePath())) return;
        }
        candidates.add(file);
    }


    @Nullable
    private String referenceIconNameForItem(@NonNull HudItem item, @NonNull String key) {
        String value = (key + " " + (item.id == null ? "" : item.id) + " " + (item.label == null ? "" : item.label)).toLowerCase(Locale.ROOT);
        if (isDecoratedPotLikeItem(value)) return "decorated_pot.png";
        if (isStonecutterItem(value)) return "stonecutter.png";
        if (isBannerItem(value)) {
            if (value.contains("ominous")) return "ominous_banner.png";
            String color = bannerColorNameForKey(value);
            if (color.length() > 0) return color + "_banner.png";
            return "white_banner.png";
        }
        return null;
    }

    @NonNull
    private String bannerColorNameForKey(@NonNull String key) {
        String value = key.toLowerCase(Locale.ROOT);
        String[] colors = new String[] {
                "light_gray", "light_blue", "black", "blue", "brown", "cyan", "gray", "green",
                "lime", "magenta", "orange", "pink", "purple", "white", "yellow", "red"
        };
        for (String color : colors) {
            if (value.contains(color + "_banner") || value.contains(":" + color + "_banner")
                    || value.contains("/" + color + "_banner") || value.contains(color + " banner")) {
                return color;
            }
        }
        return "";
    }

    private boolean drawDroidBridgeReferenceItemIcon(@NonNull Canvas canvas, @NonNull RectF dst,
                                                     @Nullable String name) {
        if (name == null || name.trim().isEmpty()) return false;
        Bitmap icon = loadDroidBridgeReferenceIcon(name.trim());
        if (icon == null || icon.isRecycled()) return false;
        drawBitmapVisibleCropped(canvas, icon, dst);
        return true;
    }

    @Nullable
    private Bitmap loadDroidBridgeReferenceIcon(@NonNull String name) {
        String clean = name.endsWith(".png") ? name : name + ".png";
        String cacheKey = "reference/" + clean;
        Bitmap cached = itemIconCache.get(cacheKey);
        if (cached != null && !cached.isRecycled()) return cached;
        File file = resolveBundledDroidBridgeAssetFile("assets/droidbridge_dualscreen/reference_icons/" + clean);
        if (file == null || !file.isFile() || file.length() <= 0L) return null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (bitmap != null) itemIconCache.put(cacheKey, bitmap);
            return bitmap;
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to load DroidBridge reference icon " + clean, throwable);
            return null;
        }
    }


    private boolean isTrapdoorItem(@NonNull String key) {
        return key.contains("trapdoor");
    }

    private boolean shouldPreferBakedIconOverAndroidModel(@NonNull String key, @NonNull Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled() || bitmap.getWidth() <= 2 || bitmap.getHeight() <= 2) return false;

        // Fix30: the first offscreen GuiItemAtlas attempt can still hand Android a raw
        // material tile for block-entity/special items.  A real Minecraft hotbar item
        // icon has transparent padding/corners after the atlas slot is trimmed.  Raw
        // textures such as oak_planks, shulker material, scaffolding_top, decorated_pot
        // side, and stonecutter_top are mostly opaque square tiles.  Never let those
        // override the JSON/special-texture renderer.
        if (!bitmapHasTransparentBorder(bitmap)) return false;
        if (looksLikeSolidMaterialTile(bitmap)) return false;

        String value = key.toLowerCase(Locale.ROOT);

        // Finished bed captures are the safest way to match vanilla exactly. Raw bed
        // material/entity sheets normally fail the transparent-border/material checks above.
        if (isBedItem(value)) return true;

        // 1.0.156: only the verified mismatch families may use the live captured vanilla
        // item icon before the Android synthetic renderer. Normal chests/beds stay on the
        // existing Android renderer.
        if (shouldPreferLiveVanillaIconForKnownMismatch(value)) return true;

        // These are exactly the categories that Android-side JSON/source-texture rendering
        // cannot reproduce perfectly.  Prefer captured icons only after the transparency
        // checks above prove the file looks like a finished item icon, not a raw texture.
        if (isChestLikeItem(value) || isBedItem(value) || isBannerItem(value)
                || isShulkerBoxItem(value) || isDecoratedPotLikeItem(value)
                || isScaffoldingItem(value) || isStonecutterItem(value)
                || isButtonItem(value) || isFenceItem(value) || isWallItem(value)
                || isSculkLikeBlockItem(value) || isJsonBlockModelItem(value)) {
            // Current probe captures for these are not trustworthy yet. Returning false
            // prevents flat/captured atlas sprites from beating the shaped Android
            // JSON/model renderer.
            return false;
        }

        return true;
    }

    private boolean shouldPreferLiveVanillaIconForKnownMismatch(@NonNull String key) {
        String value = key == null ? "" : key.toLowerCase(Locale.ROOT);
        // 1.0.157: keep the v155 captured-icon-first route only for the cases where it
        // actually looked like a final Minecraft item icon. Shulkers, dripleaf, and pots
        // can pass the transparent-border check while still being raw/source textures, so
        // route them back through the Android shaped renderers instead of drawing flat tiles.
        return isChestBoatItem(value)
                || isChestMinecartItem(value)
                || isLecternItem(value)
                || isStonecutterItem(value);
    }


    private boolean drawExportedVanillaItemIcon(@NonNull Canvas canvas, @NonNull RectF dst, @NonNull String key) {
        ArrayList<String> names = exportedVanillaItemIconNames(key);
        if (names.isEmpty()) return false;
        for (String name : names) {
            Bitmap icon = loadSpecialTexture(name);
            if (icon == null || icon.isRecycled()) continue;
            if (!looksLikeFinalMinecraftItemIcon(icon)) continue;
            drawBitmapCenteredPixelPerfect(canvas, icon, dst);
            return true;
        }
        return false;
    }

    @NonNull
    private ArrayList<String> exportedVanillaItemIconNames(@NonNull String key) {
        ArrayList<String> names = new ArrayList<>();
        String item = itemNameFromKey(key);
        if (item.length() == 0) return names;

        addUniqueName(names, "item_" + item + ".png");

        // Some exported names use their generic family icon as a fallback.  Keep these as
        // lower priority than the exact item ID so colored beds/shulkers/chests still win.
        if (item.endsWith("_bed")) addUniqueName(names, "item_bed.png");
        if (item.endsWith("_shulker_box")) addUniqueName(names, "item_shulker_box.png");
        if (item.endsWith("_banner")) addUniqueName(names, "item_banner.png");
        if (item.endsWith("_wall")) addUniqueName(names, "item_wall.png");
        if (item.endsWith("_fence")) addUniqueName(names, "item_fence.png");
        if (item.endsWith("_button")) addUniqueName(names, "item_button.png");
        return names;
    }

    private void addUniqueName(@NonNull ArrayList<String> names, @NonNull String name) {
        for (String existing : names) if (existing.equals(name)) return;
        names.add(name);
    }

    @NonNull
    private String itemNameFromKey(@NonNull String key) {
        String value = key.trim().toLowerCase(Locale.ROOT);
        int colon = value.indexOf(':');
        if (colon >= 0 && colon + 1 < value.length()) value = value.substring(colon + 1);
        int slash = value.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < value.length()) value = value.substring(slash + 1);
        int dot = value.lastIndexOf('.');
        if (dot > 0) value = value.substring(0, dot);
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') out.append(c);
        }
        return out.toString();
    }

    private boolean looksLikeFinalMinecraftItemIcon(@NonNull Bitmap bitmap) {
        if (bitmap.isRecycled() || bitmap.getWidth() <= 2 || bitmap.getHeight() <= 2) return false;
        if (!bitmapHasTransparentBorder(bitmap)) return false;
        if (looksLikeSolidMaterialTile(bitmap)) return false;
        return true;
    }

    private void drawBitmapCenteredPixelPerfect(@NonNull Canvas canvas, @NonNull Bitmap bitmap, @NonNull RectF dst) {
        fill.setFilterBitmap(false);
        fill.setDither(false);
        float bw = Math.max(1f, bitmap.getWidth());
        float bh = Math.max(1f, bitmap.getHeight());
        float scale = Math.min(dst.width() / bw, dst.height() / bh);
        float w = bw * scale;
        float h = bh * scale;
        scratch.set(dst.centerX() - w * 0.5f, dst.centerY() - h * 0.5f,
                dst.centerX() + w * 0.5f, dst.centerY() + h * 0.5f);
        canvas.drawBitmap(bitmap, null, scratch, fill);
    }

    private boolean drawCapturedMinecraftIcon(@NonNull Canvas canvas, @NonNull RectF dst,
                                              @NonNull HudItem item, @NonNull String key,
                                              @Nullable Bitmap flatIcon) {
        if (flatIcon == null || flatIcon.isRecycled()) return false;
        if (!isCapturedIconPath(item.iconPath)) return false;
        if (!shouldPreferBakedIconOverAndroidModel(key, flatIcon)) return false;

        fill.setFilterBitmap(false);
        fill.setDither(false);

        // Do not stretch a trimmed baked icon into a full square. Minecraft's GuiItemAtlas
        // captures can be trimmed to the visible pixels, so preserve aspect ratio and center it.
        float bw = Math.max(1f, flatIcon.getWidth());
        float bh = Math.max(1f, flatIcon.getHeight());
        float scale = Math.min(dst.width() / bw, dst.height() / bh);
        float w = bw * scale;
        float h = bh * scale;
        scratch.set(dst.centerX() - w * 0.5f, dst.centerY() - h * 0.5f,
                dst.centerX() + w * 0.5f, dst.centerY() + h * 0.5f);
        canvas.drawBitmap(flatIcon, null, scratch, fill);
        return true;
    }

    private boolean isCapturedIconPath(@Nullable String path) {
        if (path == null) return false;
        String value = path.trim().toLowerCase(Locale.ROOT);
        return value.contains("droidbridge_dual_screen_icons") && value.contains("flat_") && value.endsWith(".png");
    }

    private boolean isMinecraftSpecialRendererItem(@NonNull String key) {
        String value = key.toLowerCase(Locale.ROOT);
        return value.contains("chest")
                || value.endsWith("_button") || value.contains(":button")
                || value.endsWith("_bed") || value.contains(":bed")
                || value.endsWith("_wall") || value.contains(":wall")
                || value.contains("decorated_pot") || value.contains("flower_pot") || value.contains("pottery_sherd")
                || value.contains("shulker_box")
                || value.endsWith("_banner") || value.contains(":banner")
                || value.contains("stonecutter")
                || value.contains("scaffolding")
                || value.contains("grindstone")
                || value.contains("anvil");
    }

    private boolean bitmapHasTransparentBorder(@NonNull Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        if (w <= 0 || h <= 0) return false;
        int transparent = 0;
        int sampled = 0;
        int stepX = Math.max(1, w / 8);
        int stepY = Math.max(1, h / 8);
        for (int x = 0; x < w; x += stepX) {
            sampled += 2;
            if (Color.alpha(bitmap.getPixel(x, 0)) < 16) transparent++;
            if (Color.alpha(bitmap.getPixel(x, h - 1)) < 16) transparent++;
        }
        for (int y = 0; y < h; y += stepY) {
            sampled += 2;
            if (Color.alpha(bitmap.getPixel(0, y)) < 16) transparent++;
            if (Color.alpha(bitmap.getPixel(w - 1, y)) < 16) transparent++;
        }
        return sampled > 0 && transparent >= Math.max(2, sampled / 5);
    }

    private boolean isBedItem(@NonNull String key) {
        return key.endsWith("_bed") || key.contains(":bed") || key.equals("bed") || key.contains("/bed");
    }

    private boolean isChestBoatItem(@NonNull String key) {
        String value = key.toLowerCase(Locale.ROOT);
        return value.contains("chest_boat") || value.contains("chest_raft");
    }

    private boolean isChestMinecartItem(@NonNull String key) {
        String value = key.toLowerCase(Locale.ROOT);
        return value.contains("chest_minecart") || value.contains("minecart_with_chest");
    }

    private boolean isChestLikeItem(@NonNull String key) {
        // Chests are block-entity rendered in vanilla, including trapped and ender chests.
        // Shulker boxes and chest boats have their own special item paths.
        return key.contains("chest") && !key.contains("shulker_box") && !isChestBoatItem(key) && !isChestMinecartItem(key);
    }

    private boolean isShulkerBoxItem(@NonNull String key) {
        return key.contains("shulker_box");
    }

    private boolean isButtonItem(@NonNull String key) {
        return key.endsWith("_button") || key.contains(":button") || key.contains("/button");
    }

    private boolean isWallItem(@NonNull String key) {
        return key.endsWith("_wall") || key.contains(":wall") || key.contains("/wall");
    }

    private boolean isFenceItem(@NonNull String key) {
        String value = key.toLowerCase(Locale.ROOT);
        return (value.endsWith("_fence") || value.contains(":fence") || value.contains("/fence"))
                && !value.contains("fence_gate");
    }

    private boolean isBannerItem(@NonNull String key) {
        return key.endsWith("_banner") || key.contains(":banner") || key.contains("/banner");
    }

    private boolean isStonecutterItem(@NonNull String key) {
        return key.contains("stonecutter");
    }

    private boolean isLecternItem(@NonNull String key) {
        return key.contains("lectern");
    }

    private boolean isDripleafItem(@NonNull String key) {
        String value = key == null ? "" : key.toLowerCase(Locale.ROOT);
        return value.contains("dripleaf");
    }

    private boolean isScaffoldingItem(@NonNull String key) {
        return key.contains("scaffolding");
    }

    @Nullable
    private String firstResolvedOrMaterialTexture(@NonNull String key, @Nullable ModelDefinition model,
                                                  @NonNull String... names) {
        String textureId = model == null ? null : firstResolvedTexture(model, names);
        if (textureId != null && textureId.length() > 0 && !textureId.startsWith("#")) return textureId;
        return materialTextureForItemKey(key);
    }

    @Nullable
    private String materialTextureForItemKey(@NonNull String key) {
        String value = key.toLowerCase(Locale.ROOT).trim();
        int colon = value.indexOf(':');
        if (colon >= 0 && colon + 1 < value.length()) value = value.substring(colon + 1);
        value = value.replace('/', '_');

        if (value.endsWith("_button")) {
            String base = value.substring(0, value.length() - "_button".length());
            return materialTextureForBaseName(base);
        }
        if (value.endsWith("_fence") && !value.endsWith("_fence_gate")) {
            String base = value.substring(0, value.length() - "_fence".length());
            return materialTextureForBaseName(base);
        }
        if (value.endsWith("_wall")) {
            String base = value.substring(0, value.length() - "_wall".length());
            return materialTextureForBaseName(base);
        }
        return materialTextureForBaseName(value);
    }

    @NonNull
    private String materialTextureForBaseName(@NonNull String base) {
        String b = base.toLowerCase(Locale.ROOT);
        if ("oak".equals(b) || "spruce".equals(b) || "birch".equals(b) || "jungle".equals(b)
                || "acacia".equals(b) || "dark_oak".equals(b) || "mangrove".equals(b)
                || "cherry".equals(b) || "bamboo".equals(b) || "pale_oak".equals(b)) {
            return "minecraft:block/" + b + "_planks";
        }
        if ("crimson".equals(b) || "warped".equals(b)) return "minecraft:block/" + b + "_planks";
        if ("nether_brick".equals(b)) return "minecraft:block/nether_bricks";
        if ("red_nether_brick".equals(b)) return "minecraft:block/red_nether_bricks";
        if ("stone_brick".equals(b)) return "minecraft:block/stone_bricks";
        if ("mossy_stone_brick".equals(b)) return "minecraft:block/mossy_stone_bricks";
        if ("end_stone_brick".equals(b)) return "minecraft:block/end_stone_bricks";
        if ("prismarine_brick".equals(b)) return "minecraft:block/prismarine_bricks";
        if ("mud_brick".equals(b)) return "minecraft:block/mud_bricks";
        if ("polished_blackstone_brick".equals(b)) return "minecraft:block/polished_blackstone_bricks";
        if (b.endsWith("_brick")) return "minecraft:block/" + b + "s";
        if (b.endsWith("_tile")) return "minecraft:block/" + b + "s";
        return "minecraft:block/" + b;
    }

    @Nullable
    private Bitmap loadMaterialTextureForItem(@NonNull String key, @Nullable ModelDefinition model,
                                              @NonNull String... names) {
        String textureId = bestMaterialTextureIdForItem(key, model, names);
        return textureId == null ? null : loadJsonTexture(textureId);
    }

    @Nullable
    private String bestMaterialTextureIdForItem(@NonNull String key, @Nullable ModelDefinition model,
                                                @NonNull String... names) {
        String resolved = model == null ? null : firstResolvedTexture(model, names);
        if (resolved != null && resolved.length() > 0 && !resolved.startsWith("#") && loadJsonTexture(resolved) != null) {
            return resolved;
        }
        ArrayList<String> candidates = materialTextureCandidatesForItemKey(key);
        for (int i = 0; i < candidates.size(); i++) {
            String candidate = candidates.get(i);
            if (candidate != null && candidate.length() > 0 && loadJsonTexture(candidate) != null) return candidate;
        }
        return resolved != null && resolved.length() > 0 && !resolved.startsWith("#") ? resolved : materialTextureForItemKey(key);
    }

    @NonNull
    private ArrayList<String> materialTextureCandidatesForItemKey(@NonNull String key) {
        String value = key.toLowerCase(Locale.ROOT).trim();
        int colon = value.indexOf(':');
        if (colon >= 0 && colon + 1 < value.length()) value = value.substring(colon + 1);
        value = value.replace('/', '_');
        String base = value;
        if (base.endsWith("_button")) base = base.substring(0, base.length() - "_button".length());
        else if (base.endsWith("_fence") && !base.endsWith("_fence_gate")) base = base.substring(0, base.length() - "_fence".length());
        else if (base.endsWith("_wall")) base = base.substring(0, base.length() - "_wall".length());

        ArrayList<String> out = new ArrayList<>();
        addTextureCandidate(out, materialTextureForBaseName(base));
        addTextureCandidate(out, "minecraft:block/" + base);
        if (!base.endsWith("s")) addTextureCandidate(out, "minecraft:block/" + base + "s");
        addTextureCandidate(out, "minecraft:block/" + base + "_planks");
        addTextureCandidate(out, "minecraft:block/" + base + "_side");
        addTextureCandidate(out, "minecraft:block/" + base + "_top");
        if (base.endsWith("_brick")) addTextureCandidate(out, "minecraft:block/" + base + "s");
        if (base.endsWith("_tile")) addTextureCandidate(out, "minecraft:block/" + base + "s");
        if (base.endsWith("_bricks")) addTextureCandidate(out, "minecraft:block/" + base);
        if (base.endsWith("_tiles")) addTextureCandidate(out, "minecraft:block/" + base);
        return out;
    }

    private void addTextureCandidate(@NonNull ArrayList<String> out, @Nullable String value) {
        if (value == null || value.length() == 0) return;
        for (int i = 0; i < out.size(); i++) if (value.equals(out.get(i))) return;
        out.add(value);
    }

    @NonNull
    private GuiTransform guiTransformForModel(@NonNull ModelDefinition model, @NonNull String key) {
        // Stairs and trapdoors have to match the Minecraft hotbar/item facing shown on
        // the main screen. Do this before using exported display.gui, because inherited
        // vanilla parents can report the generic block yaw and make thin panels face
        // up-left again.
        if (key.contains("stairs")) return VANILLA_STAIRS_GUI_TRANSFORM;
        if (isTrapdoorItem(key)) return VANILLA_TRAPDOOR_GUI_TRANSFORM;
        if (model.guiTransform != null) return model.guiTransform;
        return DEFAULT_BLOCK_GUI_TRANSFORM;
    }

    private boolean shouldPreferFlatIconForGeneratedItem(@NonNull String key, @NonNull ModelDefinition model) {
        if (model.elements.size() > 0) return false;
        if (key.contains("decorated_pot") || key.contains("pottery_sherd")) return true;
        if (key.contains("spawn_egg") || key.contains("banner") || key.contains("shield")) return true;
        if (key.contains("bed") || key.contains("boat") || key.contains("minecart")) return true;
        if (key.contains("bucket") || key.contains("potion") || key.contains("tipped_arrow")) return true;
        return !isJsonBlockModelItem(key);
    }

    private boolean isDecoratedPotLikeItem(@NonNull String key) {
        return key.contains("decorated_pot") || key.contains("flower_pot") || key.contains("pottery_sherd");
    }

    private boolean isFlowerPotItem(@NonNull String key) {
        return key.contains("flower_pot");
    }

    private boolean drawDecoratedPotLikeIcon(@NonNull Canvas canvas, @NonNull RectF r,
                                             @Nullable ModelDefinition model,
                                             @Nullable Bitmap flatIcon) {
        // 1.0.157: do not draw the flat/captured pot texture first. Pot captures often pass
        // transparent-border checks while still being the side/material texture, which is why
        // the bottom HUD kept showing a blocky red square. Use the vanilla entity texture as
        // material for a shaped pot, or fall back to solid pottery colours.
        if (flatIcon != null && looksLikeFinalMinecraftItemIcon(flatIcon)) {
            drawBitmapCenteredPixelPerfect(canvas, flatIcon, r);
            return true;
        }

        Bitmap texture = loadSpecialTexture("decorated_pot_side.png");
        if (texture == null) texture = loadSpecialTexture("decorated_pot.png");
        if (texture == null && model != null) {
            String textureId = firstResolvedTexture(model, "decorated_pot_side", "front", "side", "sherd", "particle", "texture", "all");
            texture = textureId == null ? null : loadJsonTexture(textureId);
        }

        float cx = r.centerX();
        float top = r.top + r.height() * 0.18f;
        float rim = r.top + r.height() * 0.30f;
        float shoulder = r.top + r.height() * 0.43f;
        float bottom = r.bottom - r.height() * 0.14f;
        float neckHalf = r.width() * 0.18f;
        float shoulderHalf = r.width() * 0.31f;
        float footHalf = r.width() * 0.22f;

        float[] rimTop = new float[] {
                cx - neckHalf * 1.08f, top,
                cx + neckHalf * 1.08f, top,
                cx + neckHalf * 1.30f, rim,
                cx - neckHalf * 1.30f, rim
        };
        float[] neck = new float[] {
                cx - neckHalf * 0.86f, rim,
                cx + neckHalf * 0.86f, rim,
                cx + shoulderHalf * 0.82f, shoulder,
                cx - shoulderHalf * 0.82f, shoulder
        };
        float[] body = new float[] {
                cx - shoulderHalf, shoulder,
                cx + shoulderHalf, shoulder,
                cx + footHalf, bottom,
                cx - footHalf, bottom
        };
        float[] foot = new float[] {
                cx - footHalf * 0.86f, bottom - r.height() * 0.02f,
                cx + footHalf * 0.86f, bottom - r.height() * 0.02f,
                cx + footHalf * 0.70f, bottom + r.height() * 0.07f,
                cx - footHalf * 0.70f, bottom + r.height() * 0.07f
        };

        if (texture != null) {
            drawTexturedQuad(canvas, texture, neck, 0.98f);
            drawTexturedQuad(canvas, texture, body, 0.90f);
        } else {
            fillQuad(canvas, neck, Color.rgb(159, 83, 48));
            fillQuad(canvas, body, Color.rgb(181, 93, 54));
        }
        fillQuad(canvas, rimTop, Color.rgb(116, 63, 42));
        fillQuad(canvas, foot, Color.rgb(123, 66, 45));

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(dp(0.55f), r.width() * 0.011f));
        stroke.setColor(Color.argb(78, 255, 205, 150));
        canvas.drawLine(cx - shoulderHalf * 0.52f, shoulder + r.height() * 0.08f,
                cx + shoulderHalf * 0.42f, shoulder + r.height() * 0.08f, stroke);
        drawQuadStroke(canvas, rimTop, true);
        drawQuadStroke(canvas, neck, true);
        drawQuadStroke(canvas, body, true);
        drawQuadStroke(canvas, foot, true);
        return true;
    }

    private boolean looksLikeSolidMaterialTile(@NonNull Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        if (w <= 0 || h <= 0) return false;
        int samples = 0;
        int opaque = 0;
        int transparentCorners = 0;
        int[][] corners = new int[][] {{0,0},{Math.max(0,w-1),0},{0,Math.max(0,h-1)},{Math.max(0,w-1),Math.max(0,h-1)}};
        for (int[] corner : corners) {
            if (Color.alpha(bitmap.getPixel(corner[0], corner[1])) < 24) transparentCorners++;
        }
        if (transparentCorners >= 2) return false;
        int stepX = Math.max(1, w / 8);
        int stepY = Math.max(1, h / 8);
        for (int y = 0; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                samples++;
                if (Color.alpha(bitmap.getPixel(x, y)) > 220) opaque++;
            }
        }
        return samples > 0 && opaque >= samples * 0.92f;
    }


    private boolean drawChestMinecartLikeIcon(@NonNull Canvas canvas, @NonNull RectF r,
                                              @NonNull String key, @Nullable Bitmap flatIcon) {
        // 1.0.148: never use the exported flat/cached chest-minecart PNG.  Build a small
        // minecart hull and place the translated chest model inside it so the hotbar does
        // not regress to the old fake cached image.
        int metal = Color.rgb(92, 92, 96);
        int metalDark = Color.rgb(48, 50, 55);
        int metalLight = Color.rgb(148, 150, 154);
        float w = r.width();
        float h = r.height();
        float cx = r.centerX();
        float top = r.top + h * 0.50f;
        float mid = r.top + h * 0.66f;
        float bottom = r.bottom - h * 0.12f;
        float half = w * 0.40f;
        float depth = h * 0.12f;

        float[] leftSide = new float[] {
                cx - half, top,
                cx, top + depth,
                cx, bottom,
                cx - half * 0.90f, bottom - depth
        };
        float[] rightSide = new float[] {
                cx, top + depth,
                cx + half, top,
                cx + half * 0.90f, bottom - depth,
                cx, bottom
        };
        float[] rim = new float[] {
                cx - half, top,
                cx + half, top,
                cx + half * 0.72f, mid,
                cx - half * 0.72f, mid
        };
        fillQuad(canvas, leftSide, metalDark);
        fillQuad(canvas, rightSide, metal);
        fillQuad(canvas, rim, metalLight);
        drawQuadStroke(canvas, leftSide, true);
        drawQuadStroke(canvas, rightSide, true);
        drawQuadStroke(canvas, rim, true);

        RectF chest = new RectF(r.left + w * 0.25f, r.top + h * 0.14f, r.right - w * 0.25f, r.top + h * 0.64f);
        drawChestLikeIcon(canvas, chest, "minecraft:chest", null, null);
        return true;
    }

    private boolean drawChestBoatLikeIcon(@NonNull Canvas canvas, @NonNull RectF r,
                                          @NonNull String key, @Nullable Bitmap flatIcon) {
        // Chest boats are item/entity rendered in vanilla.  Build a clear boat hull first,
        // then place the same chest preview inside it instead of letting the generic
        // chest path turn the whole item into only a chest block.
        int wood = key.toLowerCase(Locale.ROOT).contains("spruce") ? Color.rgb(92, 65, 38) : fallbackMaterialColorForItem(key);
        int side = lighten(wood, 0.74f);
        int top = lighten(wood, 1.08f);
        float w = r.width();
        float h = r.height();
        float cx = r.centerX();
        float yTop = r.top + h * 0.42f;
        float yMid = r.top + h * 0.58f;
        float yBot = r.bottom - h * 0.13f;
        float half = w * 0.42f;
        float nose = w * 0.14f;

        float[] leftSide = new float[] {
                cx - half, yTop,
                cx - nose, yMid,
                cx - w * 0.05f, yBot,
                cx - half * 0.86f, yBot - h * 0.05f
        };
        float[] rightSide = new float[] {
                cx - nose, yMid,
                cx + half, yTop,
                cx + half * 0.86f, yBot - h * 0.05f,
                cx - w * 0.05f, yBot
        };
        float[] inner = new float[] {
                cx - half * 0.72f, yTop + h * 0.05f,
                cx + half * 0.72f, yTop + h * 0.05f,
                cx + half * 0.46f, yMid + h * 0.02f,
                cx - half * 0.46f, yMid + h * 0.02f
        };
        fillQuad(canvas, leftSide, side);
        fillQuad(canvas, rightSide, wood);
        fillQuad(canvas, inner, top);
        drawQuadStroke(canvas, leftSide, true);
        drawQuadStroke(canvas, rightSide, true);
        drawQuadStroke(canvas, inner, true);

        RectF chest = new RectF(r.left + w * 0.27f, r.top + h * 0.16f, r.right - w * 0.23f, r.top + h * 0.63f);
        drawChestLikeIcon(canvas, chest, "minecraft:chest", null, flatIcon);
        return true;
    }

    private boolean drawChestLikeIcon(@NonNull Canvas canvas, @NonNull RectF r,
                                      @NonNull String key, @Nullable ModelDefinition model,
                                      @Nullable Bitmap flatIcon) {
        // Chest items are block-entity rendered by Minecraft. The no-flicker base jar
        // cannot copy the final GuiItemAtlas yet, so Fix25 adds a safe companion exporter
        // that only copies vanilla entity texture PNGs from Minecraft resources. Prefer
        // those real chest sheets here, then fall back to the older synthetic chest.
        String value = key.toLowerCase(Locale.ROOT);
        boolean ender = value.contains("ender_chest");
        boolean trapped = value.contains("trapped_chest");
        Bitmap chestSheet = loadSpecialTexture(ender ? "chest_ender.png" : (trapped ? "chest_trapped.png" : "chest_normal.png"));
        if (chestSheet != null && drawChestFromEntitySheet(canvas, r, chestSheet, ender, trapped)) return true;

        int topColor = ender ? Color.rgb(42, 54, 67) : Color.rgb(158, 100, 42);
        int frontColor = ender ? Color.rgb(25, 32, 44) : Color.rgb(105, 62, 28);
        int sideColor = ender ? Color.rgb(33, 42, 55) : Color.rgb(128, 78, 34);
        int trimColor = ender ? Color.rgb(10, 15, 22) : Color.rgb(42, 27, 16);
        int latchColor = ender ? Color.rgb(64, 230, 190) : (trapped ? Color.rgb(205, 75, 45) : Color.rgb(218, 163, 48));

        Bitmap wood = null;
        if (!ender) {
            // Prefer the exported plank texture if available, but only use it as material.
            // Do not draw it as the whole icon.
            wood = loadJsonTexture("minecraft:block/oak_planks");
            if (wood == null && flatIcon != null && looksLikeSolidMaterialTile(flatIcon)) wood = flatIcon;
        }

        float w = r.width();
        float h = r.height();
        float cx = r.centerX();
        float topY = r.top + h * 0.20f;
        float midY = r.top + h * 0.43f;
        float bottomY = r.bottom - h * 0.15f;
        float halfW = w * 0.36f;
        float depth = h * 0.17f;

        // Top diamond + two visible sides, matching the normal Minecraft GUI block pose.
        float[] top = new float[] { cx, topY, cx + halfW, midY, cx, midY + depth, cx - halfW, midY };
        float[] left = new float[] { cx - halfW, midY, cx, midY + depth, cx, bottomY, cx - halfW, bottomY - depth };
        float[] right = new float[] { cx, midY + depth, cx + halfW, midY, cx + halfW, bottomY - depth, cx, bottomY };

        if (wood != null && !ender) {
            drawTexturedQuad(canvas, wood, top, 1.05f);
            drawTexturedQuad(canvas, wood, left, 0.70f);
            drawTexturedQuad(canvas, wood, right, 0.86f);
        } else {
            fillQuad(canvas, top, topColor);
            fillQuad(canvas, left, frontColor);
            fillQuad(canvas, right, sideColor);
        }

        // Dark lid/body seam and chest straps so it reads as a chest, not planks.
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.SQUARE);
        stroke.setStrokeWidth(Math.max(dp(0.8f), w * 0.018f));
        stroke.setColor(Color.argb(175, Color.red(trimColor), Color.green(trimColor), Color.blue(trimColor)));
        canvas.drawLine(cx - halfW * 0.88f, midY + depth * 0.12f, cx + halfW * 0.82f, midY + depth * 0.12f, stroke);
        canvas.drawLine(cx, midY + depth * 0.22f, cx, bottomY - h * 0.02f, stroke);
        canvas.drawLine(cx - halfW * 0.55f, midY + depth * 0.42f, cx - halfW * 0.55f, bottomY - depth * 0.45f, stroke);
        canvas.drawLine(cx + halfW * 0.54f, midY + depth * 0.42f, cx + halfW * 0.54f, bottomY - depth * 0.45f, stroke);

        fill.setStyle(Paint.Style.FILL);
        fill.setColor(latchColor);
        scratch.set(cx - w * 0.045f, midY + depth * 0.45f, cx + w * 0.045f, midY + depth * 0.45f + h * 0.10f);
        canvas.drawRect(scratch, fill);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(dp(0.45f), w * 0.010f));
        stroke.setColor(Color.argb(165, 28, 20, 8));
        canvas.drawRect(scratch, stroke);

        drawQuadStroke(canvas, top, true);
        drawQuadStroke(canvas, left, true);
        drawQuadStroke(canvas, right, true);
        return true;
    }


    private boolean drawBedLikeIcon(@NonNull Canvas canvas, @NonNull RectF r, @NonNull String key, @Nullable Bitmap flatIcon) {
        // Beds are not normal block-model inventory items. Prefer the real exported
        // entity bed sheet from the companion special-textures jar. That still keeps
        // the no-flicker rule because it only copies PNG resources, not rendered GUI.
        Bitmap bedSheet = loadSpecialTexture("bed_" + bedColorNameForKey(key) + ".png");
        if (bedSheet != null && drawBedFromEntitySheet(canvas, r, key, bedSheet)) return true;

        // Fallback: draw the same compact diagonal bed silhouette Minecraft uses in the hotbar.
        int blanket = bedColorForKey(key);
        int blanketSide = lighten(blanket, 0.70f);
        int blanketDark = lighten(blanket, 0.54f);
        int wood = Color.rgb(116, 73, 38);
        int woodDark = Color.rgb(66, 43, 25);

        float w = r.width();
        float h = r.height();
        float left = r.left + w * 0.24f;
        float right = r.right - w * 0.07f;
        float headTop = r.top + h * 0.26f;
        float footTop = r.top + h * 0.38f;
        float footBottom = r.bottom - h * 0.28f;
        float skew = w * 0.24f;
        float drop = h * 0.14f;

        float[] underside = new float[] {
                left - skew, footBottom - h * 0.06f,
                right - skew, footBottom,
                right - skew, footBottom + drop,
                left - skew, footBottom + drop - h * 0.06f
        };
        float[] footSide = new float[] {
                right - skew, footBottom,
                right, footTop,
                right, footTop + drop,
                right - skew, footBottom + drop
        };
        float[] blanketFront = new float[] {
                left - skew, footBottom - h * 0.06f,
                right - skew, footBottom,
                right - skew, footBottom + drop * 0.72f,
                left - skew, footBottom + drop * 0.72f - h * 0.06f
        };
        float[] blanketTop = new float[] {
                left, headTop,
                right, footTop,
                right - skew, footBottom,
                left - skew, footBottom - h * 0.06f
        };
        float[] pillow = new float[] {
                left + w * 0.02f, headTop + h * 0.02f,
                left + w * 0.32f, headTop + h * 0.07f,
                left + w * 0.18f, headTop + h * 0.22f,
                left - w * 0.10f, headTop + h * 0.16f
        };

        fillQuad(canvas, underside, woodDark);
        fillQuad(canvas, footSide, wood);
        fillQuad(canvas, blanketFront, blanketSide);
        fillQuad(canvas, blanketTop, blanket);
        fillQuad(canvas, pillow, Color.rgb(236, 236, 224));

        // Bed legs: small and dark, kept outside the blanket so the item reads as a bed.
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(woodDark);
        float legW = Math.max(dp(1.0f), w * 0.036f);
        scratch.set(left - skew - legW * 0.2f, footBottom + drop * 0.42f, left - skew + legW, footBottom + drop + h * 0.08f);
        canvas.drawRect(scratch, fill);
        scratch.set(right - skew - legW * 0.2f, footBottom + drop * 0.42f, right - skew + legW, footBottom + drop + h * 0.08f);
        canvas.drawRect(scratch, fill);
        scratch.set(right - legW * 0.4f, footTop + drop * 0.48f, right + legW * 0.6f, footTop + drop + h * 0.07f);
        canvas.drawRect(scratch, fill);

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.SQUARE);
        stroke.setStrokeWidth(Math.max(dp(0.45f), w * 0.010f));
        stroke.setColor(Color.argb(85, 0, 0, 0));
        canvas.drawLine(left - skew * 0.92f, footBottom - h * 0.04f, right - skew * 0.10f, footBottom, stroke);

        drawQuadStroke(canvas, underside, true);
        drawQuadStroke(canvas, footSide, true);
        drawQuadStroke(canvas, blanketFront, true);
        drawQuadStroke(canvas, blanketTop, true);
        drawQuadStroke(canvas, pillow, true);
        return true;
    }



    private boolean drawChestFromEntitySheet(@NonNull Canvas canvas, @NonNull RectF r,
                                             @NonNull Bitmap sheet, boolean ender, boolean trapped) {
        // Approximate the vanilla chest model with real entity texture crops.  The texture
        // layout is still the chest entity sheet, not a block texture, so crop stable areas
        // that contain the lid/body wood/ender material.  At hotbar size this is much closer
        // than drawing oak_planks as a cube.
        // 1.0.151: use the real vanilla ModelPart UV bands. 1.0.150 was still
        // sampling the body top/bottom band (v=19..33) for visible side faces, which
        // includes transparent/unused space on chest sheets and made chests look like
        // hollow frames.  The body side faces live at v=33..43; the lid top is v=0..14.
        Bitmap topTex = solidifyTransparentTexture(cropSpecialTexture(sheet, 14f / 64f, 0f / 64f, 28f / 64f, 14f / 64f), ender ? Color.rgb(35, 49, 61) : Color.rgb(154, 95, 40));
        Bitmap sideTex = solidifyTransparentTexture(cropSpecialTexture(sheet, 28f / 64f, 33f / 64f, 42f / 64f, 43f / 64f), ender ? Color.rgb(25, 36, 48) : Color.rgb(116, 70, 32));
        Bitmap frontTex = solidifyTransparentTexture(cropSpecialTexture(sheet, 14f / 64f, 33f / 64f, 28f / 64f, 43f / 64f), ender ? Color.rgb(25, 36, 48) : Color.rgb(130, 78, 34));
        if (topTex == null) topTex = sheet;
        if (sideTex == null) sideTex = sheet;
        if (frontTex == null) frontTex = sideTex;

        float w = r.width();
        float h = r.height();
        float cx = r.centerX();
        float topY = r.top + h * 0.20f;
        float midY = r.top + h * 0.43f;
        float bottomY = r.bottom - h * 0.15f;
        float halfW = w * 0.36f;
        float depth = h * 0.17f;

        float[] top = new float[] { cx, topY, cx + halfW, midY, cx, midY + depth, cx - halfW, midY };
        float[] left = new float[] { cx - halfW, midY, cx, midY + depth, cx, bottomY, cx - halfW, bottomY - depth };
        float[] right = new float[] { cx, midY + depth, cx + halfW, midY, cx + halfW, bottomY - depth, cx, bottomY };

        drawTexturedQuad(canvas, topTex, top, 1.05f);
        drawTexturedQuad(canvas, frontTex, left, 0.72f);
        drawTexturedQuad(canvas, sideTex, right, 0.88f);

        int trimColor = ender ? Color.rgb(6, 10, 16) : Color.rgb(42, 27, 16);
        int latchColor = ender ? Color.rgb(64, 230, 190) : (trapped ? Color.rgb(205, 75, 45) : Color.rgb(218, 163, 48));
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.SQUARE);
        stroke.setStrokeWidth(Math.max(dp(0.8f), w * 0.018f));
        stroke.setColor(Color.argb(175, Color.red(trimColor), Color.green(trimColor), Color.blue(trimColor)));
        canvas.drawLine(cx - halfW * 0.88f, midY + depth * 0.12f, cx + halfW * 0.82f, midY + depth * 0.12f, stroke);
        canvas.drawLine(cx, midY + depth * 0.22f, cx, bottomY - h * 0.02f, stroke);

        fill.setStyle(Paint.Style.FILL);
        fill.setColor(latchColor);
        scratch.set(cx - w * 0.045f, midY + depth * 0.45f, cx + w * 0.045f, midY + depth * 0.45f + h * 0.10f);
        canvas.drawRect(scratch, fill);
        drawQuadStroke(canvas, top, true);
        drawQuadStroke(canvas, left, true);
        drawQuadStroke(canvas, right, true);
        return true;
    }

    private boolean drawBedFromEntitySheet(@NonNull Canvas canvas, @NonNull RectF r,
                                           @NonNull String key, @NonNull Bitmap sheet) {
        // Bed entity sheets contain both blanket and frame material. Crop broad stable
        // regions instead of drawing a flat raw texture tile.
        Bitmap blanketTopTex = cropSpecialTexture(sheet, 0f / 64f, 0f / 64f, 28f / 64f, 22f / 64f);
        Bitmap blanketSideTex = cropSpecialTexture(sheet, 0f / 64f, 22f / 64f, 28f / 64f, 32f / 64f);
        Bitmap woodTex = cropSpecialTexture(sheet, 28f / 64f, 0f / 64f, 56f / 64f, 20f / 64f);
        if (blanketTopTex == null) blanketTopTex = sheet;
        if (blanketSideTex == null) blanketSideTex = blanketTopTex;
        int blanket = bedColorForKey(key);
        int woodDark = Color.rgb(66, 43, 25);
        int wood = Color.rgb(116, 73, 38);

        float w = r.width();
        float h = r.height();
        float left = r.left + w * 0.23f;
        float right = r.right - w * 0.07f;
        float headTop = r.top + h * 0.29f;
        float footTop = r.top + h * 0.39f;
        float footBottom = r.bottom - h * 0.30f;
        float skew = w * 0.24f;
        float drop = h * 0.12f;

        float[] underside = { left - skew, footBottom - h * 0.06f, right - skew, footBottom, right - skew, footBottom + drop, left - skew, footBottom + drop - h * 0.06f };
        float[] footSide = { right - skew, footBottom, right, footTop, right, footTop + drop, right - skew, footBottom + drop };
        float[] blanketFront = { left - skew, footBottom - h * 0.06f, right - skew, footBottom, right - skew, footBottom + drop * 0.72f, left - skew, footBottom + drop * 0.72f - h * 0.06f };
        float[] blanketTop = { left, headTop, right, footTop, right - skew, footBottom, left - skew, footBottom - h * 0.06f };
        float[] pillow = { left + w * 0.02f, headTop + h * 0.02f, left + w * 0.32f, headTop + h * 0.07f, left + w * 0.18f, headTop + h * 0.22f, left - w * 0.10f, headTop + h * 0.16f };

        if (woodTex != null) drawTexturedQuad(canvas, woodTex, underside, 0.72f); else fillQuad(canvas, underside, woodDark);
        if (woodTex != null) drawTexturedQuad(canvas, woodTex, footSide, 0.88f); else fillQuad(canvas, footSide, wood);
        drawTexturedQuad(canvas, blanketSideTex, blanketFront, 0.82f);
        drawTexturedQuad(canvas, blanketTopTex, blanketTop, 1.02f);
        fillQuad(canvas, pillow, Color.rgb(238, 238, 226));

        fill.setStyle(Paint.Style.FILL);
        fill.setColor(woodDark);
        float legW = Math.max(dp(1.0f), w * 0.036f);
        scratch.set(left - skew - legW * 0.2f, footBottom + drop * 0.42f, left - skew + legW, footBottom + drop + h * 0.08f);
        canvas.drawRect(scratch, fill);
        scratch.set(right - skew - legW * 0.2f, footBottom + drop * 0.42f, right - skew + legW, footBottom + drop + h * 0.08f);
        canvas.drawRect(scratch, fill);
        drawQuadStroke(canvas, underside, true);
        drawQuadStroke(canvas, footSide, true);
        drawQuadStroke(canvas, blanketFront, true);
        drawQuadStroke(canvas, blanketTop, true);
        drawQuadStroke(canvas, pillow, true);
        return true;
    }

    private boolean drawButtonLikeIcon(@NonNull Canvas canvas, @NonNull RectF r,
                                       @NonNull String key, @Nullable ModelDefinition model, @Nullable Bitmap flatIcon) {
        String textureId = bestMaterialTextureIdForItem(key, model, "texture", "all", "side", "top", "particle", "layer0");
        Bitmap textureBitmap = textureId == null ? null : loadJsonTexture(textureId);
        if (textureBitmap == null) textureBitmap = solidMaterialBitmapForItem(key);

        ModelDefinition button = new ModelDefinition();
        if (textureBitmap != null && textureId != null && loadJsonTexture(textureId) != null) {
            button.textures.put("texture", textureId);
            addBox(button, 5f, 6f, 6f, 11f, 10f, 10f, "#texture");
            return renderJsonModel(canvas, r, button, DEFAULT_BLOCK_GUI_TRANSFORM);
        }

        // Fallback for missing exported material textures: render the same tiny button
        // silhouette with a generated material color instead of disappearing.
        int c = fallbackMaterialColorForItem(key);
        float cx = r.centerX();
        float top = r.top + r.height() * 0.43f;
        float half = r.width() * 0.20f;
        float d = r.height() * 0.07f;
        float[] face = new float[] { cx - half, top, cx + half, top, cx + half - d, top + d, cx - half - d, top + d };
        fillQuad(canvas, face, c);
        drawQuadStroke(canvas, face, true);
        return true;
    }


    private boolean drawFenceLikeIcon(@NonNull Canvas canvas, @NonNull RectF r,
                                      @NonNull String key, @Nullable ModelDefinition model, @Nullable Bitmap flatIcon) {
        String textureId = bestMaterialTextureIdForItem(key, model, "texture", "all", "side", "top", "particle", "layer0");
        Bitmap texture = textureId == null ? null : loadJsonTexture(textureId);
        if (texture != null) {
            ModelDefinition fence = new ModelDefinition();
            fence.textures.put("texture", textureId);
            // Vanilla fence_inventory-style cuboids. Keep the out-of-range rail caps because
            // vanilla's inventory model uses them to make the rails read correctly in GUI pose.
            addBox(fence, 6f, 0f, 0f, 10f, 16f, 4f, "#texture");
            addBox(fence, 6f, 0f, 12f, 10f, 16f, 16f, "#texture");
            addBox(fence, 7f, 12f, -2f, 9f, 15f, 18f, "#texture");
            addBox(fence, 7f, 6f, -2f, 9f, 9f, 18f, "#texture");
            return renderJsonModel(canvas, r, fence, VANILLA_STAIRS_GUI_TRANSFORM);
        }
        // Some woods/materials may not have been exported yet. Keep every fence visible.
        return drawManualFenceIcon(canvas, r, fallbackMaterialColorForItem(key));
    }

    private boolean drawManualFenceIcon(@NonNull Canvas canvas, @NonNull RectF r, int color) {
        int dark = lighten(color, 0.72f);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.SQUARE);
        stroke.setStrokeWidth(Math.max(dp(1.1f), r.width() * 0.040f));
        stroke.setColor(color);
        float cx = r.centerX();
        float cy = r.centerY();
        float half = r.width() * 0.25f;
        float top = r.top + r.height() * 0.24f;
        float bot = r.bottom - r.height() * 0.18f;
        canvas.drawLine(cx - half, top, cx - half, bot, stroke);
        canvas.drawLine(cx + half, top + r.height() * 0.05f, cx + half, bot + r.height() * 0.02f, stroke);
        stroke.setColor(dark);
        stroke.setStrokeWidth(Math.max(dp(1.0f), r.width() * 0.034f));
        canvas.drawLine(cx - half * 1.32f, cy - r.height() * 0.10f, cx + half * 1.22f, cy - r.height() * 0.03f, stroke);
        canvas.drawLine(cx - half * 1.25f, cy + r.height() * 0.10f, cx + half * 1.18f, cy + r.height() * 0.17f, stroke);
        return true;
    }

    private boolean drawManualWallIcon(@NonNull Canvas canvas, @NonNull RectF r, int color) {
        int side = lighten(color, 0.75f);
        int top = lighten(color, 1.08f);
        float cx = r.centerX();
        float topY = r.top + r.height() * 0.24f;
        float midY = r.top + r.height() * 0.45f;
        float bottomY = r.bottom - r.height() * 0.17f;
        float half = r.width() * 0.22f;
        float depth = r.height() * 0.13f;
        float[] postTop = {cx, topY, cx + half, midY, cx, midY + depth, cx - half, midY};
        float[] postLeft = {cx - half, midY, cx, midY + depth, cx, bottomY, cx - half, bottomY - depth};
        float[] postRight = {cx, midY + depth, cx + half, midY, cx + half, bottomY - depth, cx, bottomY};
        fillQuad(canvas, postTop, top);
        fillQuad(canvas, postLeft, side);
        fillQuad(canvas, postRight, color);
        drawQuadStroke(canvas, postTop, true);
        drawQuadStroke(canvas, postLeft, true);
        drawQuadStroke(canvas, postRight, true);
        return true;
    }

    @NonNull
    private Bitmap solidMaterialBitmapForItem(@NonNull String key) {
        String cacheKey = "solid-material/" + key.toLowerCase(Locale.ROOT);
        Bitmap cached = itemIconCache.get(cacheKey);
        if (cached != null && !cached.isRecycled()) return cached;
        int c = fallbackMaterialColorForItem(key);
        int hi = lighten(c, 1.12f);
        int lo = lighten(c, 0.72f);
        Bitmap b = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int color = ((x + y) & 3) == 0 ? hi : (((x * 3 + y) & 5) == 0 ? lo : c);
                b.setPixel(x, y, Color.argb(255, Color.red(color), Color.green(color), Color.blue(color)));
            }
        }
        itemIconCache.put(cacheKey, b);
        return b;
    }

    private int fallbackMaterialColorForItem(@NonNull String key) {
        String v = key.toLowerCase(Locale.ROOT);
        if (v.contains("dark_oak")) return Color.rgb(73, 48, 27);
        if (v.contains("spruce")) return Color.rgb(92, 65, 38);
        if (v.contains("birch")) return Color.rgb(196, 179, 113);
        if (v.contains("jungle")) return Color.rgb(153, 105, 64);
        if (v.contains("acacia")) return Color.rgb(178, 92, 46);
        if (v.contains("mangrove")) return Color.rgb(118, 52, 45);
        if (v.contains("cherry")) return Color.rgb(205, 146, 155);
        if (v.contains("bamboo")) return Color.rgb(190, 159, 72);
        if (v.contains("crimson")) return Color.rgb(132, 52, 70);
        if (v.contains("warped")) return Color.rgb(44, 128, 122);
        if (v.contains("end_stone")) return Color.rgb(218, 224, 166);
        if (v.contains("blackstone")) return Color.rgb(45, 38, 44);
        if (v.contains("deepslate")) return Color.rgb(72, 72, 78);
        if (v.contains("granite")) return Color.rgb(151, 104, 86);
        if (v.contains("diorite")) return Color.rgb(188, 188, 188);
        if (v.contains("andesite")) return Color.rgb(133, 135, 134);
        if (v.contains("brick")) return Color.rgb(151, 83, 67);
        if (v.contains("sandstone")) return Color.rgb(205, 190, 132);
        if (v.contains("cobblestone") || v.contains("stone")) return Color.rgb(125, 125, 125);
        return Color.rgb(132, 93, 55);
    }

    private boolean drawBannerLikeIcon(@NonNull Canvas canvas, @NonNull RectF r,
                                       @NonNull String key, @Nullable Bitmap flatIcon) {
        // 1.0.108: fallback only.  A real banner is a builtin/block-entity style item;
        // when the mod-side rendered cache exists, drawItemGlyph() uses that first.  If
        // it is missing, draw a compact vertical banner item instead of the old angled
        // flag-like placeholder.
        if (flatIcon != null && !looksLikeSolidMaterialTile(flatIcon) && bitmapHasTransparentBorder(flatIcon)) {
            fill.setFilterBitmap(false);
            fill.setDither(false);
            scratch.set(r.left + r.width() * 0.12f, r.top + r.height() * 0.08f,
                    r.right - r.width() * 0.12f, r.bottom - r.height() * 0.08f);
            canvas.drawBitmap(flatIcon, null, scratch, fill);
            return true;
        }

        int cloth = bannerColorForKey(key);
        int clothDark = lighten(cloth, 0.72f);
        int clothLight = lighten(cloth, 1.12f);
        int pole = Color.rgb(112, 78, 43);
        int poleDark = Color.rgb(68, 45, 24);

        float w = r.width();
        float h = r.height();
        float poleX = r.left + w * 0.30f;
        float topY = r.top + h * 0.13f;
        float bottomY = r.bottom - h * 0.11f;
        float clothLeft = poleX + w * 0.045f;
        float clothRight = r.right - w * 0.16f;
        float clothTop = r.top + h * 0.18f;
        float clothBottom = r.bottom - h * 0.26f;
        float skew = w * 0.055f;

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.SQUARE);
        stroke.setStrokeWidth(Math.max(dp(1.35f), w * 0.040f));
        stroke.setColor(pole);
        canvas.drawLine(poleX, topY, poleX, bottomY, stroke);
        stroke.setStrokeWidth(Math.max(dp(1.15f), w * 0.034f));
        stroke.setColor(poleDark);
        canvas.drawLine(poleX - w * 0.08f, bottomY, poleX + w * 0.28f, bottomY, stroke);

        // Small top rail, then the hanging rectangular cloth with a tiny V cut.
        stroke.setStrokeWidth(Math.max(dp(1.05f), w * 0.030f));
        stroke.setColor(pole);
        canvas.drawLine(poleX - w * 0.015f, clothTop, clothRight + w * 0.025f, clothTop + skew, stroke);

        float[] body = new float[] {
                clothLeft, clothTop + h * 0.035f,
                clothRight, clothTop + skew + h * 0.035f,
                clothRight, clothBottom,
                clothLeft, clothBottom - skew
        };
        fillQuad(canvas, body, cloth);
        // darker lower band gives the same inventory-item depth without pretending it is a waving flag.
        float[] band = new float[] {
                clothLeft, clothBottom - h * 0.14f - skew,
                clothRight, clothBottom - h * 0.14f,
                clothRight, clothBottom,
                clothLeft + w * 0.09f, clothBottom - h * 0.035f
        };
        fillQuad(canvas, band, clothDark);
        // subtle highlight on the front edge.
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.SQUARE);
        stroke.setStrokeWidth(Math.max(dp(0.55f), w * 0.012f));
        stroke.setColor(Color.argb(90, Color.red(clothLight), Color.green(clothLight), Color.blue(clothLight)));
        canvas.drawLine(clothLeft + w * 0.035f, clothTop + h * 0.07f,
                clothLeft + w * 0.035f, clothBottom - h * 0.10f, stroke);
        drawQuadStroke(canvas, body, true);
        drawQuadStroke(canvas, band, true);
        return true;
    }


    private boolean drawWallLikeIcon(@NonNull Canvas canvas, @NonNull RectF r,
                                     @NonNull String key, @Nullable ModelDefinition model, @Nullable Bitmap flatIcon) {
        String textureId = bestMaterialTextureIdForItem(key, model, "wall", "side", "all", "particle", "texture", "top");
        Bitmap texture = textureId == null ? null : loadJsonTexture(textureId);
        if (texture != null) {
            ModelDefinition wall = new ModelDefinition();
            wall.textures.put("wall", textureId);
            // Vanilla block/wall_inventory.json dimensions: center post + full wall rail.
            addBox(wall, 4f, 0f, 4f, 12f, 16f, 12f, "#wall");
            addBox(wall, 5f, 0f, 0f, 11f, 13f, 16f, "#wall");
            return renderJsonModel(canvas, r, wall, VANILLA_STAIRS_GUI_TRANSFORM);
        }
        return drawManualWallIcon(canvas, r, fallbackMaterialColorForItem(key));
    }


    private boolean drawGeneratedBetterBedModelIcon(@NonNull Canvas canvas, @NonNull RectF r,
                                                    @NonNull String key) {
        try {
            JSONObject generated = DualScreenSpecialItemModelFactory.buildBedModel(
                    "minecraft:entity/bed/" + bedColorNameForKey(key)
            );
            ModelDefinition generatedModel = ModelDefinition.fromJson(generated);
            if (generatedModel == null || generatedModel.elements.size() <= 0) return false;
            return renderJsonModel(canvas, r, generatedModel, guiTransformForModel(generatedModel, key));
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to render generated BetterBeds model for " + key, throwable);
            return false;
        }
    }

    private boolean drawGeneratedShulkerBoxModelIcon(@NonNull Canvas canvas, @NonNull RectF r,
                                                     @NonNull String key) {
        String colorName = shulkerColorNameForKey(key);
        String texture = colorName.length() > 0
                ? "minecraft:entity/shulker/shulker_" + colorName
                : "minecraft:entity/shulker/shulker";
        // 1.0.161: shulker-only change. Do not touch the bed path. Only use the
        // generated vanilla shulker model when the matching installed entity sheet exists;
        // otherwise keep the safe shaped fallback instead of drawing a raw/source tile.
        if (loadJsonTexture(texture) == null) return false;
        try {
            JSONObject generated = DualScreenSpecialItemModelFactory.buildShulkerBoxModel(texture);
            ModelDefinition generatedModel = ModelDefinition.fromJson(generated);
            if (generatedModel == null || generatedModel.elements.size() <= 0) return false;
            return renderJsonModel(canvas, r, generatedModel, guiTransformForModel(generatedModel, key));
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to render generated shulker-box model for " + key, throwable);
            return false;
        }
    }

    private boolean drawShulkerBoxLikeIcon(@NonNull Canvas canvas, @NonNull RectF r,
                                           @NonNull String key, @Nullable ModelDefinition model,
                                           @Nullable Bitmap flatIcon) {
        // 1.0.161: start from the fixed 159 bed/chest/dripleaf baseline and adjust only
        // the manual shulker fallback. Beds still use the earlier BetterBeds/private model
        // route above and are not routed through this code.
        if (drawGeneratedShulkerBoxModelIcon(canvas, r, key)) return true;

        int color = shulkerColorForKey(key);
        int topColor = lighten(color, 1.18f);
        int lidSide = lighten(color, 0.94f);
        int bodyLeft = lighten(color, 0.66f);
        int bodyRight = lighten(color, 0.82f);
        int bottomColor = lighten(color, 0.54f);

        float w = r.width();
        float h = r.height();
        float cx = r.centerX();
        float topY = r.top + h * 0.18f;
        float lidMidY = r.top + h * 0.39f;
        float bodyMidY = r.top + h * 0.50f;
        float bottomY = r.bottom - h * 0.16f;
        float halfW = w * 0.30f;
        float depth = h * 0.16f;

        float[] lidTop = { cx, topY, cx + halfW, lidMidY, cx, lidMidY + depth, cx - halfW, lidMidY };
        float[] lidFront = { cx - halfW, lidMidY, cx, lidMidY + depth, cx, bodyMidY + depth * 0.36f, cx - halfW, bodyMidY - depth * 0.64f };
        float[] bodyLeftQuad = { cx - halfW, bodyMidY - depth * 0.64f, cx, bodyMidY + depth * 0.36f, cx, bottomY, cx - halfW, bottomY - depth };
        float[] bodyRightQuad = { cx, bodyMidY + depth * 0.36f, cx + halfW, bodyMidY - depth * 0.64f, cx + halfW, bottomY - depth, cx, bottomY };
        float[] bottomFace = { cx - halfW * 0.72f, bottomY - depth * 0.10f, cx + halfW * 0.72f, bottomY - depth * 0.10f, cx + halfW * 0.48f, bottomY + h * 0.040f, cx - halfW * 0.48f, bottomY + h * 0.040f };

        fillQuad(canvas, bottomFace, bottomColor);
        fillQuad(canvas, lidTop, topColor);
        fillQuad(canvas, lidFront, lidSide);
        fillQuad(canvas, bodyLeftQuad, bodyLeft);
        fillQuad(canvas, bodyRightQuad, bodyRight);

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.SQUARE);
        stroke.setStrokeWidth(Math.max(dp(0.75f), w * 0.016f));
        stroke.setColor(Color.argb(155, 0, 0, 0));
        canvas.drawLine(cx - halfW * 0.86f, bodyMidY - depth * 0.55f,
                cx + halfW * 0.84f, bodyMidY - depth * 0.55f, stroke);
        canvas.drawLine(cx, bodyMidY + depth * 0.10f, cx, bottomY - h * 0.01f, stroke);

        drawQuadStroke(canvas, lidTop, true);
        drawQuadStroke(canvas, lidFront, true);
        drawQuadStroke(canvas, bodyLeftQuad, true);
        drawQuadStroke(canvas, bodyRightQuad, true);
        drawQuadStroke(canvas, bottomFace, true);
        return true;
    }

    private boolean drawShulkerBoxFromEntitySheet(@NonNull Canvas canvas, @NonNull RectF r,
                                                  @NonNull String key, @NonNull Bitmap sheet) {
        int fallback = shulkerColorForKey(key);
        Bitmap topTex = solidifyTransparentTexture(cropSpecialTexture(sheet, 0f / 64f, 0f / 64f, 32f / 64f, 16f / 64f), lighten(fallback, 1.14f));
        Bitmap sideTex = solidifyTransparentTexture(cropSpecialTexture(sheet, 0f / 64f, 16f / 64f, 32f / 64f, 32f / 64f), lighten(fallback, 0.88f));
        Bitmap frontTex = solidifyTransparentTexture(cropSpecialTexture(sheet, 32f / 64f, 16f / 64f, 64f / 64f, 32f / 64f), lighten(fallback, 0.72f));
        if (topTex == null) topTex = solidifyTransparentTexture(sheet, lighten(fallback, 1.10f));
        if (sideTex == null) sideTex = topTex;
        if (frontTex == null) frontTex = sideTex;

        float cx = r.centerX();
        float topY = r.top + r.height() * 0.18f;
        float midY = r.top + r.height() * 0.42f;
        float bottomY = r.bottom - r.height() * 0.13f;
        float halfW = r.width() * 0.33f;
        float depth = r.height() * 0.17f;

        float[] topQuad = { cx, topY, cx + halfW, midY, cx, midY + depth, cx - halfW, midY };
        float[] leftQuad = { cx - halfW, midY, cx, midY + depth, cx, bottomY, cx - halfW, bottomY - depth };
        float[] rightQuad = { cx, midY + depth, cx + halfW, midY, cx + halfW, bottomY - depth, cx, bottomY };

        drawTexturedQuad(canvas, topTex, topQuad, 1.08f);
        drawTexturedQuad(canvas, frontTex, leftQuad, 0.76f);
        drawTexturedQuad(canvas, sideTex, rightQuad, 0.92f);

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(dp(0.75f), r.width() * 0.016f));
        stroke.setColor(Color.argb(140, 0, 0, 0));
        canvas.drawLine(cx - halfW * 0.82f, midY + depth * 0.42f,
                cx + halfW * 0.82f, midY + depth * 0.42f, stroke);
        drawQuadStroke(canvas, topQuad, true);
        drawQuadStroke(canvas, leftQuad, true);
        drawQuadStroke(canvas, rightQuad, true);
        return true;
    }

    private boolean drawDripleafLikeIcon(@NonNull Canvas canvas, @NonNull RectF r,
                                         @NonNull String key, @Nullable ModelDefinition model,
                                         @Nullable Bitmap flatIcon) {
        // 1.0.157: final captured dripleaf icons can still be raw leaf textures, so this
        // fallback deliberately draws a smaller item-shaped dripleaf instead of filling the
        // whole slot with the big_dripleaf_top PNG.
        Bitmap top = loadJsonTexture("minecraft:block/big_dripleaf_top");
        if (top == null) top = loadJsonTexture("minecraft:block/big_dripleaf_tip");
        Bitmap stem = loadJsonTexture("minecraft:block/big_dripleaf_stem");
        if (top == null && model != null) {
            String tex = firstResolvedTexture(model, "top", "leaf", "texture", "all", "particle", "layer0");
            top = tex == null ? null : loadJsonTexture(tex);
        }

        float w = r.width();
        float h = r.height();
        float cx = r.centerX();
        float leafTop = r.top + h * 0.26f;
        float leafMid = r.top + h * 0.42f;
        float leafBot = r.top + h * 0.56f;
        float half = w * 0.24f;
        float[] leaf = new float[] {
                cx - half * 0.95f, leafMid,
                cx + half * 0.10f, leafTop,
                cx + half * 1.05f, leafMid + h * 0.025f,
                cx - half * 0.05f, leafBot
        };
        if (top != null) drawTexturedQuad(canvas, top, leaf, 1.05f);
        else fillQuad(canvas, leaf, Color.rgb(101, 165, 54));
        drawQuadStroke(canvas, leaf, true);

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.SQUARE);
        stroke.setStrokeWidth(Math.max(dp(0.85f), w * 0.028f));
        stroke.setColor(Color.rgb(86, 119, 45));
        float stemX = cx - w * 0.04f;
        canvas.drawLine(stemX, leafBot - h * 0.01f, stemX, r.bottom - h * 0.20f, stroke);
        stroke.setStrokeWidth(Math.max(dp(0.55f), w * 0.019f));
        stroke.setColor(Color.rgb(55, 82, 33));
        canvas.drawLine(cx - w * 0.13f, r.bottom - h * 0.20f, cx + w * 0.12f, r.bottom - h * 0.20f, stroke);
        if (stem != null) {
            scratch.set(cx - w * 0.070f, leafBot, cx + w * 0.005f, r.bottom - h * 0.20f);
            fill.setFilterBitmap(false);
            fill.setDither(false);
            canvas.drawBitmap(stem, null, scratch, fill);
        }
        return true;
    }

    private boolean drawLecternLikeIcon(@NonNull Canvas canvas, @NonNull RectF r,
                                        @Nullable ModelDefinition model, @Nullable Bitmap flatIcon) {
        if (flatIcon != null && looksLikeFinalMinecraftItemIcon(flatIcon)) {
            drawBitmapCenteredPixelPerfect(canvas, flatIcon, r);
            return true;
        }

        Bitmap topTex = loadJsonTexture("minecraft:block/lectern_top");
        Bitmap frontTex = loadJsonTexture("minecraft:block/lectern_front");
        Bitmap sideTex = loadJsonTexture("minecraft:block/oak_planks");
        if (topTex == null && model != null) {
            String tex = firstResolvedTexture(model, "top", "base", "side", "front", "particle", "texture");
            topTex = tex == null ? null : loadJsonTexture(tex);
        }
        if (frontTex == null) frontTex = sideTex;
        if (sideTex == null) sideTex = topTex;

        float w = r.width();
        float h = r.height();
        float cx = r.centerX();
        float topY = r.top + h * 0.24f;
        float midY = r.top + h * 0.45f;
        float bottomY = r.bottom - h * 0.16f;
        float half = w * 0.35f;
        float depth = h * 0.13f;

        float[] topQuad = { cx, topY, cx + half, midY, cx, midY + depth, cx - half, midY };
        float[] left = { cx - half * 0.54f, midY + depth * 0.40f, cx, midY + depth * 0.72f, cx, bottomY, cx - half * 0.28f, bottomY - depth };
        float[] right = { cx, midY + depth * 0.72f, cx + half * 0.52f, midY + depth * 0.40f, cx + half * 0.26f, bottomY - depth, cx, bottomY };
        float[] base = { cx - half * 0.42f, bottomY - depth * 0.10f, cx + half * 0.42f, bottomY - depth * 0.10f, cx + half * 0.30f, bottomY + h * 0.05f, cx - half * 0.30f, bottomY + h * 0.05f };

        if (topTex != null) drawTexturedQuad(canvas, topTex, topQuad, 1.06f); else fillQuad(canvas, topQuad, Color.rgb(151, 101, 56));
        if (frontTex != null) drawTexturedQuad(canvas, frontTex, left, 0.78f); else fillQuad(canvas, left, Color.rgb(105, 67, 35));
        if (sideTex != null) drawTexturedQuad(canvas, sideTex, right, 0.90f); else fillQuad(canvas, right, Color.rgb(123, 79, 40));
        if (sideTex != null) drawTexturedQuad(canvas, sideTex, base, 0.82f); else fillQuad(canvas, base, Color.rgb(94, 61, 32));

        // Vanilla lectern icon is visually identified by the book on top. Add a tiny
        // red page wedge so it does not read as a plain table/trapdoor in the bottom HUD.
        float[] book = { cx - half * 0.34f, topY + h * 0.04f, cx + half * 0.08f, topY + h * 0.01f,
                cx + half * 0.30f, midY - h * 0.04f, cx - half * 0.10f, midY + h * 0.01f };
        fillQuad(canvas, book, Color.rgb(169, 43, 38));
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(dp(0.45f), w * 0.010f));
        stroke.setColor(Color.argb(120, 235, 210, 142));
        canvas.drawLine(cx - half * 0.10f, topY + h * 0.045f, cx + half * 0.14f, midY - h * 0.025f, stroke);

        drawQuadStroke(canvas, topQuad, true);
        drawQuadStroke(canvas, left, true);
        drawQuadStroke(canvas, right, true);
        drawQuadStroke(canvas, base, true);
        drawQuadStroke(canvas, book, true);
        return true;
    }

    private int bannerColorForKey(@NonNull String key) {
        return colorForDyeKey(key, Color.rgb(226, 226, 218));
    }

    private int shulkerColorForKey(@NonNull String key) {
        return colorForDyeKey(key, Color.rgb(150, 95, 164));
    }

    private int colorForDyeKey(@NonNull String key, int fallback) {
        String value = key.toLowerCase(Locale.ROOT);
        if (value.contains("black_")) return Color.rgb(30, 31, 36);
        if (value.contains("blue_")) return Color.rgb(48, 76, 165);
        if (value.contains("brown_")) return Color.rgb(104, 68, 42);
        if (value.contains("cyan_")) return Color.rgb(38, 130, 148);
        if (value.contains("gray_") || value.contains("grey_")) return Color.rgb(86, 86, 90);
        if (value.contains("green_")) return Color.rgb(80, 126, 42);
        if (value.contains("light_blue_")) return Color.rgb(86, 165, 216);
        if (value.contains("light_gray_") || value.contains("light_grey_")) return Color.rgb(170, 170, 164);
        if (value.contains("lime_")) return Color.rgb(111, 184, 56);
        if (value.contains("magenta_")) return Color.rgb(179, 69, 178);
        if (value.contains("orange_")) return Color.rgb(218, 124, 42);
        if (value.contains("pink_")) return Color.rgb(218, 128, 165);
        if (value.contains("purple_")) return Color.rgb(126, 68, 160);
        if (value.contains("red_")) return Color.rgb(170, 42, 40);
        if (value.contains("white_")) return Color.rgb(226, 226, 218);
        if (value.contains("yellow_")) return Color.rgb(220, 188, 57);
        return fallback;
    }

    private int lighten(int color, float amount) {
        return Color.rgb(
                Math.max(0, Math.min(255, Math.round(Color.red(color) * amount))),
                Math.max(0, Math.min(255, Math.round(Color.green(color) * amount))),
                Math.max(0, Math.min(255, Math.round(Color.blue(color) * amount)))
        );
    }

    private boolean drawStonecutterLikeIcon(@NonNull Canvas canvas, @NonNull RectF r,
                                            @Nullable ModelDefinition model, @Nullable Bitmap flatIcon) {
        // Fallback only. The real JSON stonecutter model has a 16x9 base plus a saw plane;
        // the generic z-buffer renderer is preferred when that model is exported. Keep this
        // compact so it does not look worse than the real model.
        Bitmap side = loadJsonTexture("minecraft:block/stonecutter_side");
        Bitmap top = loadJsonTexture("minecraft:block/stonecutter_top");
        Bitmap saw = loadJsonTexture("minecraft:block/stonecutter_saw");
        if (top == null && model != null) {
            String tex = firstResolvedTexture(model, "top", "all", "particle", "side");
            top = tex == null ? null : loadJsonTexture(tex);
        }
        if (side == null && model != null) {
            String tex = firstResolvedTexture(model, "side", "all", "particle", "top");
            side = tex == null ? null : loadJsonTexture(tex);
        }
        if (side == null) side = flatIcon;
        if (top == null) top = side;

        float cx = r.centerX();
        float topY = r.top + r.height() * 0.36f;
        float midY = r.top + r.height() * 0.52f;
        float bottomY = r.bottom - r.height() * 0.17f;
        float halfW = r.width() * 0.33f;
        float depth = r.height() * 0.14f;
        float[] topQuad = { cx, topY, cx + halfW, midY, cx, midY + depth, cx - halfW, midY };
        float[] leftQuad = { cx - halfW, midY, cx, midY + depth, cx, bottomY, cx - halfW, bottomY - depth };
        float[] rightQuad = { cx, midY + depth, cx + halfW, midY, cx + halfW, bottomY - depth, cx, bottomY };
        if (top != null) drawTexturedQuad(canvas, top, topQuad, 1.04f); else fillQuad(canvas, topQuad, Color.rgb(124, 124, 124));
        if (side != null) drawTexturedQuad(canvas, side, leftQuad, 0.78f); else fillQuad(canvas, leftQuad, Color.rgb(78, 78, 78));
        if (side != null) drawTexturedQuad(canvas, side, rightQuad, 0.90f); else fillQuad(canvas, rightQuad, Color.rgb(98, 98, 98));

        if (saw != null) {
            // Draw the saw as a narrow vertical plane like vanilla, not as a huge flat tile.
            float[] sawQuad = new float[] {
                    cx - r.width()*0.20f, r.top + r.height()*0.20f,
                    cx + r.width()*0.22f, r.top + r.height()*0.26f,
                    cx + r.width()*0.22f, r.top + r.height()*0.49f,
                    cx - r.width()*0.20f, r.top + r.height()*0.43f
            };
            drawTexturedQuad(canvas, saw, sawQuad, 1.08f);
            drawQuadStroke(canvas, sawQuad, true);
        } else {
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(Color.rgb(186, 186, 186));
            scratch.set(cx - r.width()*0.18f, r.top + r.height()*0.23f, cx + r.width()*0.23f, r.top + r.height()*0.47f);
            canvas.drawOval(scratch, fill);
        }
        drawQuadStroke(canvas, topQuad, true);
        drawQuadStroke(canvas, leftQuad, true);
        drawQuadStroke(canvas, rightQuad, true);
        return true;
    }


    private boolean drawScaffoldingLikeIcon(@NonNull Canvas canvas, @NonNull RectF r,
                                            @Nullable ModelDefinition model, @Nullable Bitmap flatIcon) {
        // Do not let scaffolding become the raw top texture square. Its inventory model is a
        // thin open frame, so draw that frame directly and use the exported top texture only
        // for the upper grate if it exists.
        Bitmap top = loadJsonTexture("minecraft:block/scaffolding_top");
        if (top == null && model != null) {
            String tex = firstResolvedTexture(model, "top", "all", "side", "particle");
            top = tex == null ? null : loadJsonTexture(tex);
        }

        int bamboo = Color.rgb(199, 163, 72);
        int bambooDark = Color.rgb(128, 101, 43);
        float cx = r.centerX();
        float topY = r.top + r.height() * 0.26f;
        float midY = r.top + r.height() * 0.48f;
        float bottomY = r.bottom - r.height() * 0.19f;
        float halfW = r.width() * 0.30f;
        float depth = r.height() * 0.15f;
        float[] topQuad = new float[] { cx, topY, cx + halfW, midY, cx, midY + depth, cx - halfW, midY };
        if (top != null) drawTexturedQuad(canvas, top, topQuad, 1.03f); else fillQuad(canvas, topQuad, Color.rgb(190, 155, 68));

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.SQUARE);
        stroke.setStrokeWidth(Math.max(dp(1.05f), r.width() * 0.035f));
        stroke.setColor(bamboo);
        float lx = cx - halfW;
        float rx = cx + halfW;
        canvas.drawLine(lx, midY, lx, bottomY - depth * 0.40f, stroke);
        canvas.drawLine(rx, midY, rx, bottomY - depth * 0.40f, stroke);
        canvas.drawLine(cx, midY + depth, cx, bottomY, stroke);
        canvas.drawLine(cx, topY, cx, bottomY - depth * 0.65f, stroke);

        stroke.setStrokeWidth(Math.max(dp(0.7f), r.width() * 0.024f));
        stroke.setColor(bambooDark);
        canvas.drawLine(lx, (midY + bottomY) * 0.52f, cx, bottomY, stroke);
        canvas.drawLine(rx, (midY + bottomY) * 0.52f, cx, bottomY, stroke);
        canvas.drawLine(lx, midY, rx, midY, stroke);
        canvas.drawLine(cx - halfW * 0.45f, midY + depth * 0.52f, cx + halfW * 0.45f, midY + depth * 0.52f, stroke);
        drawQuadStroke(canvas, topQuad, true);
        return true;
    }

    private void fillQuad(@NonNull Canvas canvas, @NonNull float[] verts, int color) {
        Path p = new Path();
        p.moveTo(verts[0], verts[1]);
        p.lineTo(verts[2], verts[3]);
        p.lineTo(verts[4], verts[5]);
        p.lineTo(verts[6], verts[7]);
        p.close();
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(color);
        canvas.drawPath(p, fill);
    }

    @NonNull
    private String bedColorNameForKey(@NonNull String key) {
        String value = key.toLowerCase(Locale.ROOT);
        String[] colors = new String[] {"light_gray", "light_blue", "black", "blue", "brown", "cyan", "gray", "green", "lime", "magenta", "orange", "pink", "purple", "white", "yellow", "red"};
        for (String color : colors) if (value.contains(color + "_bed") || value.contains(":" + color + "_bed") || value.contains("/" + color + "_bed")) return color;
        return "red";
    }

    private int bedColorForKey(@NonNull String key) {
        String value = key.toLowerCase(Locale.ROOT);
        if (value.contains("black_bed")) return Color.rgb(35, 35, 40);
        if (value.contains("blue_bed")) return Color.rgb(52, 86, 170);
        if (value.contains("brown_bed")) return Color.rgb(111, 72, 43);
        if (value.contains("cyan_bed")) return Color.rgb(40, 132, 150);
        if (value.contains("gray_bed") || value.contains("grey_bed")) return Color.rgb(92, 92, 96);
        if (value.contains("green_bed")) return Color.rgb(84, 128, 45);
        if (value.contains("light_blue_bed")) return Color.rgb(92, 173, 215);
        if (value.contains("light_gray_bed") || value.contains("light_grey_bed")) return Color.rgb(172, 172, 165);
        if (value.contains("lime_bed")) return Color.rgb(112, 185, 55);
        if (value.contains("magenta_bed")) return Color.rgb(180, 70, 180);
        if (value.contains("orange_bed")) return Color.rgb(218, 125, 42);
        if (value.contains("pink_bed")) return Color.rgb(218, 130, 165);
        if (value.contains("purple_bed")) return Color.rgb(128, 70, 160);
        if (value.contains("white_bed")) return Color.rgb(222, 222, 216);
        if (value.contains("yellow_bed")) return Color.rgb(218, 188, 60);
        return Color.rgb(175, 45, 42);
    }

    private boolean modelLooksLikeFullCube(@NonNull ModelDefinition model) {
        if (model.elements.size() != 1) return false;
        ModelElement e = model.elements.get(0);
        return nearly(e.from[0], 0f) && nearly(e.from[1], 0f) && nearly(e.from[2], 0f)
                && nearly(e.to[0], 16f) && nearly(e.to[1], 16f) && nearly(e.to[2], 16f);
    }

    private boolean nearly(float a, float b) {
        return Math.abs(a - b) < 0.05f;
    }

    private boolean renderSyntheticTrapdoorModel(@NonNull Canvas canvas, @NonNull RectF r,
                                                 @NonNull ModelDefinition source,
                                                 @Nullable Bitmap flatIcon) {
        String texture = firstResolvedTexture(source, "texture", "all", "top", "side", "bottom", "layer0");
        if ((texture == null || texture.length() == 0) && flatIcon == null) return false;
        if (texture == null || texture.length() == 0) {
            // Last-resort path: draw the flat item texture as a real thin panel instead of
            // as a full cube. This keeps broken/missing trapdoor model exports recognizable.
            return drawBitmapAsThinTrapdoor(canvas, r, flatIcon);
        }

        ModelDefinition panel = new ModelDefinition();
        panel.textures.put("texture", texture);
        ModelElement e = new ModelElement();
        // Vanilla trapdoor block models are thin cuboids, not cubes. Keep the
        // element in 0..16 block space so it renders like the normal hotbar item.
        e.from = new float[] {0f, 0f, 0f};
        e.to = new float[] {16f, 3f, 16f};
        addSyntheticFace(e, "up", "#texture");
        addSyntheticFace(e, "down", "#texture");
        addSyntheticFace(e, "north", "#texture");
        addSyntheticFace(e, "south", "#texture");
        addSyntheticFace(e, "west", "#texture");
        addSyntheticFace(e, "east", "#texture");
        panel.elements.add(e);
        return renderJsonModel(canvas, r, panel, VANILLA_TRAPDOOR_GUI_TRANSFORM);
    }

    @Nullable
    private String firstResolvedTexture(@NonNull ModelDefinition model, @NonNull String... names) {
        for (String name : names) {
            String raw = model.texture(name);
            String resolved = model.resolveTexture(raw);
            if (resolved != null && resolved.length() > 0 && !resolved.startsWith("#")) return resolved;
        }
        for (String raw : model.textures.values()) {
            String resolved = model.resolveTexture(raw);
            if (resolved != null && resolved.length() > 0 && !resolved.startsWith("#")) return resolved;
        }
        return null;
    }

    private void addSyntheticFace(@NonNull ModelElement e, @NonNull String name, @NonNull String texture) {
        ModelFace face = new ModelFace();
        face.name = name;
        face.texture = texture;
        face.uv = new float[] {0f, 0f, 16f, 16f};
        face.rotation = 0;
        e.faces.put(name, face);
    }

    private void addBox(@NonNull ModelDefinition model, float x1, float y1, float z1,
                        float x2, float y2, float z2, @NonNull String texture) {
        ModelElement e = new ModelElement();
        e.from = new float[] {x1, y1, z1};
        e.to = new float[] {x2, y2, z2};
        addSyntheticFace(e, "down", texture);
        addSyntheticFace(e, "up", texture);
        addSyntheticFace(e, "north", texture);
        addSyntheticFace(e, "south", texture);
        addSyntheticFace(e, "west", texture);
        addSyntheticFace(e, "east", texture);
        model.elements.add(e);
    }

    private boolean drawBitmapAsThinTrapdoor(@NonNull Canvas canvas, @NonNull RectF r, @Nullable Bitmap texture) {
        if (texture == null) return false;
        // Thin horizontal panel, drawn in the same down-right orientation as JSON cuboids.
        float left = r.left + r.width() * 0.17f;
        float right = r.right - r.width() * 0.10f;
        float top = r.top + r.height() * 0.32f;
        float bottom = r.bottom - r.height() * 0.28f;
        float skew = r.width() * 0.20f;
        float[] face = new float[] {
                left, top,
                right, top + skew * 0.45f,
                right - skew, bottom + skew * 0.45f,
                left - skew, bottom
        };
        drawTexturedQuad(canvas, texture, face, 1f);
        drawQuadStroke(canvas, face);
        return true;
    }

    private boolean renderJsonModel(@NonNull Canvas canvas, @NonNull RectF r, @NonNull ModelDefinition model,
                                    @NonNull GuiTransform guiTransform) {
        ArrayList<FaceDraw> faces = new ArrayList<>();
        Bounds2D bounds = new Bounds2D();
        // Use the canonical 16x16x16 block-space bounds for scaling, then include
        // any out-of-range custom elements.  Scaling only to the model's own elements
        // made thin models such as trapdoors stretch into full cubes.
        addFullBlockBounds(bounds, guiTransform);
        for (ModelElement element : model.elements) {
            addElementBounds(bounds, element, guiTransform);
        }
        if (!bounds.valid()) return false;
        for (ModelElement element : model.elements) {
            addModelFace(faces, model, element, "down", r, bounds, guiTransform);
            addModelFace(faces, model, element, "north", r, bounds, guiTransform);
            addModelFace(faces, model, element, "west", r, bounds, guiTransform);
            addModelFace(faces, model, element, "south", r, bounds, guiTransform);
            addModelFace(faces, model, element, "east", r, bounds, guiTransform);
            addModelFace(faces, model, element, "up", r, bounds, guiTransform);
        }
        if (faces.isEmpty()) return false;
        Collections.sort(faces, new Comparator<FaceDraw>() {
            @Override public int compare(FaceDraw a, FaceDraw b) {
                return Float.compare(a.depth, b.depth);
            }
        });
        // Do not rely on Canvas painter ordering for JSON models. Minecraft/asset-renderer
        // rasterizes triangles with depth testing; Canvas drawBitmapMesh does not. The lack of
        // depth testing is what made Fix11/12 draw hidden stair/trapdoor faces over the real
        // texture and turn the mesh dark/broken. Rasterize the model into a tiny ARGB bitmap
        // with a z-buffer first, then draw that finished icon into the slot.
        return drawRasterizedModel(canvas, r, faces);
    }

    private boolean drawRasterizedModel(@NonNull Canvas canvas, @NonNull RectF dst,
                                        @NonNull ArrayList<FaceDraw> faces) {
        int size = Math.round(Math.min(dst.width(), dst.height()));
        size = Math.max(32, Math.min(96, size));
        if (size <= 0 || faces.isEmpty()) return false;

        int[] pixels = new int[size * size];
        float[] zBuffer = new float[size * size];
        for (int i = 0; i < zBuffer.length; i++) zBuffer[i] = Float.NEGATIVE_INFINITY;

        int drawn = 0;
        for (FaceDraw f : faces) {
            Bitmap bitmap = loadJsonTexture(f.texture);
            if (bitmap == null) continue;
            Bitmap faceBitmap = cropTextureForUv(bitmap, f.texture, f.uv, f.rotation);
            Bitmap tex = faceBitmap == null ? bitmap : faceBitmap;
            if (tex == null || tex.isRecycled() || tex.getWidth() <= 0 || tex.getHeight() <= 0) continue;
            FaceDraw localFace = toLocalRasterFace(f, dst, size);
            rasterizeFace(pixels, zBuffer, size, size, tex, localFace, brightnessForFace(f.faceName));
            drawn++;
        }
        if (drawn <= 0) return false;

        Bitmap rendered = null;
        try {
            rendered = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888);
            fill.setFilterBitmap(false);
            fill.setDither(false);
            canvas.drawBitmap(rendered, null, dst, fill);
            return true;
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to rasterize dual-screen JSON model", throwable);
            return false;
        } finally {
            if (rendered != null && !rendered.isRecycled()) rendered.recycle();
        }
    }

    @NonNull
    private FaceDraw toLocalRasterFace(@NonNull FaceDraw source, @NonNull RectF dst, int size) {
        FaceDraw out = new FaceDraw();
        out.faceName = source.faceName;
        out.texture = source.texture;
        out.uv = source.uv;
        out.rotation = source.rotation;
        out.depth = source.depth;
        out.depths = source.depths.clone();
        float width = Math.max(1f, dst.width());
        float height = Math.max(1f, dst.height());
        float max = Math.max(1f, size - 1f);
        for (int i = 0; i < 4; i++) {
            out.verts[i * 2] = ((source.verts[i * 2] - dst.left) / width) * max;
            out.verts[i * 2 + 1] = ((source.verts[i * 2 + 1] - dst.top) / height) * max;
        }
        return out;
    }

    private void rasterizeFace(@NonNull int[] pixels, @NonNull float[] zBuffer,
                               int width, int height, @NonNull Bitmap texture,
                               @NonNull FaceDraw face, float brightness) {
        int tw = texture.getWidth();
        int th = texture.getHeight();
        float[] u = new float[] {0f, tw - 1f, tw - 1f, 0f};
        float[] v = new float[] {0f, 0f, th - 1f, th - 1f};
        rasterizeTriangle(pixels, zBuffer, width, height, texture, face,
                0, 1, 2, u, v, brightness);
        rasterizeTriangle(pixels, zBuffer, width, height, texture, face,
                0, 2, 3, u, v, brightness);
    }

    private void rasterizeTriangle(@NonNull int[] pixels, @NonNull float[] zBuffer,
                                   int width, int height, @NonNull Bitmap texture,
                                   @NonNull FaceDraw face, int ia, int ib, int ic,
                                   @NonNull float[] u, @NonNull float[] v, float brightness) {
        float ax = face.verts[ia * 2];
        float ay = face.verts[ia * 2 + 1];
        float bx = face.verts[ib * 2];
        float by = face.verts[ib * 2 + 1];
        float cx = face.verts[ic * 2];
        float cy = face.verts[ic * 2 + 1];
        float area = edge(ax, ay, bx, by, cx, cy);
        if (Math.abs(area) < 0.0001f) return;

        int minX = Math.max(0, (int) Math.floor(Math.min(ax, Math.min(bx, cx))));
        int maxX = Math.min(width - 1, (int) Math.ceil(Math.max(ax, Math.max(bx, cx))));
        int minY = Math.max(0, (int) Math.floor(Math.min(ay, Math.min(by, cy))));
        int maxY = Math.min(height - 1, (int) Math.ceil(Math.max(ay, Math.max(by, cy))));
        if (minX > maxX || minY > maxY) return;

        float za = face.depths[ia];
        float zb = face.depths[ib];
        float zc = face.depths[ic];
        float ua = u[ia], ub = u[ib], uc = u[ic];
        float va = v[ia], vb = v[ib], vc = v[ic];
        boolean positive = area > 0f;

        for (int py = minY; py <= maxY; py++) {
            float y = py + 0.5f;
            for (int px = minX; px <= maxX; px++) {
                float x = px + 0.5f;
                float w0 = edge(bx, by, cx, cy, x, y);
                float w1 = edge(cx, cy, ax, ay, x, y);
                float w2 = edge(ax, ay, bx, by, x, y);
                if (positive) {
                    if (w0 < 0f || w1 < 0f || w2 < 0f) continue;
                } else {
                    if (w0 > 0f || w1 > 0f || w2 > 0f) continue;
                }
                w0 /= area;
                w1 /= area;
                w2 /= area;
                float z = (za * w0) + (zb * w1) + (zc * w2);
                int index = py * width + px;
                if (z + 0.0001f < zBuffer[index]) continue;

                float sampleU = (ua * w0) + (ub * w1) + (uc * w2);
                float sampleV = (va * w0) + (vb * w1) + (vc * w2);
                int tx = Math.max(0, Math.min(texture.getWidth() - 1, Math.round(sampleU)));
                int ty = Math.max(0, Math.min(texture.getHeight() - 1, Math.round(sampleV)));
                int argb = texture.getPixel(tx, ty);
                int alpha = Color.alpha(argb);
                if (alpha <= 8) continue;
                int shaded = shadeColor(argb, brightness);
                pixels[index] = alpha >= 250 ? shaded : blendSrcOver(shaded, pixels[index]);
                zBuffer[index] = z;
            }
        }
    }

    private float edge(float ax, float ay, float bx, float by, float cx, float cy) {
        return ((cx - ax) * (by - ay)) - ((cy - ay) * (bx - ax));
    }

    private int shadeColor(int argb, float brightness) {
        int a = Color.alpha(argb);
        int r = Math.max(0, Math.min(255, Math.round(Color.red(argb) * brightness)));
        int g = Math.max(0, Math.min(255, Math.round(Color.green(argb) * brightness)));
        int b = Math.max(0, Math.min(255, Math.round(Color.blue(argb) * brightness)));
        return Color.argb(a, r, g, b);
    }

    private int blendSrcOver(int src, int dst) {
        int sa = Color.alpha(src);
        if (sa <= 0) return dst;
        if (sa >= 255 || Color.alpha(dst) <= 0) return src;
        float a = sa / 255f;
        int da = Color.alpha(dst);
        int outA = Math.min(255, sa + Math.round(da * (1f - a)));
        int outR = Math.round(Color.red(src) * a + Color.red(dst) * (1f - a));
        int outG = Math.round(Color.green(src) * a + Color.green(dst) * (1f - a));
        int outB = Math.round(Color.blue(src) * a + Color.blue(dst) * (1f - a));
        return Color.argb(outA, outR, outG, outB);
    }

    private void addFullBlockBounds(@NonNull Bounds2D bounds, @NonNull GuiTransform guiTransform) {
        float[] xs = {0f, 16f};
        float[] ys = {0f, 16f};
        float[] zs = {0f, 16f};
        for (float x : xs) for (float y : ys) for (float z : zs) bounds.include(projectRaw(x, y, z, guiTransform));
    }

    private void addElementBounds(@NonNull Bounds2D bounds, @NonNull ModelElement e, @NonNull GuiTransform guiTransform) {
        float[] xs = {e.from[0], e.to[0]};
        float[] ys = {e.from[1], e.to[1]};
        float[] zs = {e.from[2], e.to[2]};
        for (float x : xs) for (float y : ys) for (float z : zs) bounds.include(projectRaw(x, y, z, guiTransform));
    }

    private void addModelFace(@NonNull ArrayList<FaceDraw> out, @NonNull ModelDefinition model,
                              @NonNull ModelElement e, @NonNull String faceName,
                              @NonNull RectF dst, @NonNull Bounds2D bounds, @NonNull GuiTransform guiTransform) {
        ModelFace face = e.faces.get(faceName);
        if (face == null || face.texture == null || face.texture.length() == 0) return;
        String texture = model.resolveTexture(face.texture);
        if (texture == null || texture.length() == 0 || texture.startsWith("#")) {
            texture = fallbackTextureForFace(model, faceName);
        }
        if (texture == null || texture.length() == 0 || texture.startsWith("#")) return;
        if (loadJsonTexture(texture) == null) {
            String fallbackTexture = fallbackTextureForFace(model, faceName);
            if (fallbackTexture != null && fallbackTexture.length() > 0 && !fallbackTexture.startsWith("#")) {
                texture = fallbackTexture;
            }
        }
        float[][] points = faceCorners(e, faceName);
        if (points == null) return;
        float[] verts = new float[8];
        float depth = 0f;
        for (int i = 0; i < 4; i++) {
            float[] projected = projectRaw(points[i][0], points[i][1], points[i][2], guiTransform);
            float[] scaled = scaleProjected(projected, dst, bounds);
            verts[i * 2] = scaled[0];
            verts[i * 2 + 1] = scaled[1];
            depth += depthForPoint(points[i][0], points[i][1], points[i][2], guiTransform);
        }
        FaceDraw draw = new FaceDraw();
        draw.faceName = faceName;
        draw.texture = texture;
        draw.uv = face.uv;
        draw.rotation = face.rotation;
        draw.verts = verts;
        draw.depths = new float[] {
                depthForPoint(points[0][0], points[0][1], points[0][2], guiTransform),
                depthForPoint(points[1][0], points[1][1], points[1][2], guiTransform),
                depthForPoint(points[2][0], points[2][1], points[2][2], guiTransform),
                depthForPoint(points[3][0], points[3][1], points[3][2], guiTransform)
        };
        draw.depth = depth / 4f;
        out.add(draw);
    }

    @Nullable
    private String fallbackTextureForFace(@NonNull ModelDefinition model, @NonNull String faceName) {
        // Some exported/inherited stair and trapdoor JSONs resolve one face variable but not
        // another. Do not leave those quads empty; fall back to the same Minecraft texture
        // groups the vanilla parents use: top/up, bottom/down, and side for vertical faces.
        String resolved;
        if ("up".equals(faceName)) {
            resolved = firstResolvedTexture(model, "top", "up", "all", "side", "texture", "layer0", "particle");
        } else if ("down".equals(faceName)) {
            resolved = firstResolvedTexture(model, "bottom", "down", "all", "side", "texture", "layer0", "particle");
        } else {
            resolved = firstResolvedTexture(model, "side", faceName, "all", "texture", "top", "bottom", "layer0", "particle");
        }
        return resolved;
    }

    private boolean isFrontFacingFace(@NonNull String faceName, @NonNull GuiTransform guiTransform) {
        float[] n = faceNormal(faceName);
        if (n == null) return false;
        // After the display transform, positive Z is facing the GUI camera in this
        // orthographic projection. This keeps culling stable for [30,225,0], [30,135,0],
        // trapdoors, stairs, and custom cuboid models without post-render mirroring.
        return transformedNormalDepth(n[0], n[1], n[2], guiTransform) > 0.001f;
    }

    @Nullable
    private float[] faceNormal(@NonNull String faceName) {
        if ("up".equals(faceName)) return new float[] {0f, 1f, 0f};
        if ("down".equals(faceName)) return new float[] {0f, -1f, 0f};
        if ("north".equals(faceName)) return new float[] {0f, 0f, -1f};
        if ("south".equals(faceName)) return new float[] {0f, 0f, 1f};
        if ("west".equals(faceName)) return new float[] {-1f, 0f, 0f};
        if ("east".equals(faceName)) return new float[] {1f, 0f, 0f};
        return null;
    }

    private float transformedNormalDepth(float x, float y, float z, @NonNull GuiTransform guiTransform) {
        final double rx = Math.toRadians(guiTransform.rotX);
        final double ry = Math.toRadians(guiTransform.rotY);
        final double rz = Math.toRadians(guiTransform.rotZ);
        final float cosY = (float) Math.cos(ry);
        final float sinY = (float) Math.sin(ry);
        final float cosX = (float) Math.cos(rx);
        final float sinX = (float) Math.sin(rx);
        final float cosZ = (float) Math.cos(rz);
        final float sinZ = (float) Math.sin(rz);

        float x1 = (x * cosY) + (z * sinY);
        float z1 = (-x * sinY) + (z * cosY);
        float y1 = (y * cosX) - (z1 * sinX);
        float z2 = (y * sinX) + (z1 * cosX);
        // Z rotation does not change depth, but keep the same transform order for clarity.
        float ignoredX = (x1 * cosZ) - (y1 * sinZ);
        if (ignoredX == Float.MIN_VALUE) return z2;
        return z2;
    }

    @Nullable
    private float[][] faceCorners(@NonNull ModelElement e, @NonNull String face) {
        float x1 = e.from[0], y1 = e.from[1], z1 = e.from[2];
        float x2 = e.to[0], y2 = e.to[1], z2 = e.to[2];
        if ("up".equals(face)) return new float[][] {{x1,y2,z1},{x2,y2,z1},{x2,y2,z2},{x1,y2,z2}};
        if ("down".equals(face)) return new float[][] {{x1,y1,z2},{x2,y1,z2},{x2,y1,z1},{x1,y1,z1}};
        if ("north".equals(face)) return new float[][] {{x2,y2,z1},{x1,y2,z1},{x1,y1,z1},{x2,y1,z1}};
        if ("south".equals(face)) return new float[][] {{x1,y2,z2},{x2,y2,z2},{x2,y1,z2},{x1,y1,z2}};
        if ("west".equals(face)) return new float[][] {{x1,y2,z1},{x1,y2,z2},{x1,y1,z2},{x1,y1,z1}};
        if ("east".equals(face)) return new float[][] {{x2,y2,z2},{x2,y2,z1},{x2,y1,z1},{x2,y1,z2}};
        return null;
    }

    private float[] projectRaw(float x, float y, float z, @NonNull GuiTransform guiTransform) {
        float[] transformed = transformForMinecraftGui(x, y, z, guiTransform);
        return new float[] { transformed[0], transformed[1] };
    }

    @NonNull
    private float[] transformForMinecraftGui(float x, float y, float z, @NonNull GuiTransform guiTransform) {
        // Vanilla block/item GUI display transform in centered block space. The default
        // root block pose is [30,225,0], while authored models can override it, such as
        // stairs using [30,135,0]. Keep this as a camera/model display transform, not a
        // screen-space flip of the completed image.
        final float nx = x - 8f;
        final float ny = y - 8f;
        final float nz = z - 8f;

        final double rx = Math.toRadians(guiTransform.rotX);
        final double ry = Math.toRadians(guiTransform.rotY);
        final double rz = Math.toRadians(guiTransform.rotZ);
        final float cosY = (float) Math.cos(ry);
        final float sinY = (float) Math.sin(ry);
        final float cosX = (float) Math.cos(rx);
        final float sinX = (float) Math.sin(rx);
        final float cosZ = (float) Math.cos(rz);
        final float sinZ = (float) Math.sin(rz);

        // Preserve the projection style that already keeps full cubes/logs upright.
        // Only the selected display.gui yaw changes for authored models like stairs.
        float x1 = (nx * cosY) + (nz * sinY);
        float z1 = (-nx * sinY) + (nz * cosY);
        float y1 = (ny * cosX) - (z1 * sinX);
        float z2 = (ny * sinX) + (z1 * cosX);
        float x2 = (x1 * cosZ) - (y1 * sinZ);
        float y2 = (x1 * sinZ) + (y1 * cosZ);

        return new float[] { x2, -y2, z2 };
    }

    private float[] scaleProjected(@NonNull float[] p, @NonNull RectF dst, @NonNull Bounds2D b) {
        float pad = Math.max(dp(1f), Math.min(dst.width(), dst.height()) * 0.06f);
        float scale = Math.min((dst.width() - pad * 2f) / Math.max(1f, b.maxX - b.minX),
                (dst.height() - pad * 2f) / Math.max(1f, b.maxY - b.minY));
        float cx = (b.minX + b.maxX) * 0.5f;
        float cy = (b.minY + b.maxY) * 0.5f;
        return new float[] { dst.centerX() + (p[0] - cx) * scale, dst.centerY() + (p[1] - cy) * scale };
    }

    private float depthForPoint(float x, float y, float z, @NonNull GuiTransform guiTransform) {
        return transformForMinecraftGui(x, y, z, guiTransform)[2];
    }

    private float brightnessForFace(@NonNull String face) {
        // Keep the Minecraft-style separation between top and side faces, but do not darken
        // side quads so aggressively that stair/trapdoor textures look like empty black mesh.
        if ("up".equals(face)) return 1.08f;
        if ("east".equals(face) || "south".equals(face)) return 0.90f;
        if ("west".equals(face) || "north".equals(face)) return 0.78f;
        return 0.68f;
    }

    @Nullable
    private Bitmap loadSpecialTexture(@NonNull String name) {
        String clean = name.endsWith(".png") ? name : name + ".png";
        String cacheKey = "special/" + clean;
        Bitmap cached = itemIconCache.get(cacheKey);
        if (cached != null && !cached.isRecycled()) return cached;
        File file = resolveSpecialTextureFile(clean);
        if (file == null || !file.isFile() || file.length() <= 0L) return null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (bitmap != null) itemIconCache.put(cacheKey, bitmap);
            return bitmap;
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to load exported special texture " + clean, throwable);
            return null;
        }
    }

    @Nullable
    private File resolveSpecialTextureFile(@NonNull String name) {
        ArrayList<File> candidates = new ArrayList<>();
        File parent = hudStateFile.getParentFile();
        addSpecialTextureCandidate(candidates, parent, name);
        if (parent != null) {
            addSpecialTextureCandidate(candidates, new File(parent, "game"), name);
            File grand = parent.getParentFile();
            if (grand != null) {
                addSpecialTextureCandidate(candidates, grand, name);
                addSpecialTextureCandidate(candidates, new File(grand, "game"), name);
            }
        }
        for (File candidate : candidates) if (candidate != null && candidate.isFile() && candidate.length() > 0L) return candidate;
        return resolveVanillaSpecialTextureAssetFile(name);
    }

    @Nullable
    private File resolveVanillaSpecialTextureAssetFile(@NonNull String name) {
        String n = name.toLowerCase(Locale.ROOT);
        ArrayList<String> relatives = new ArrayList<>();
        if ("chest_normal.png".equals(n)) relatives.add("assets/minecraft/textures/entity/chest/normal.png");
        else if ("chest_trapped.png".equals(n)) relatives.add("assets/minecraft/textures/entity/chest/trapped.png");
        else if ("chest_ender.png".equals(n)) relatives.add("assets/minecraft/textures/entity/chest/ender.png");
        else if ("banner_base.png".equals(n)) relatives.add("assets/minecraft/textures/entity/banner/base.png");
        else if ("decorated_pot.png".equals(n)) relatives.add("assets/minecraft/textures/entity/decorated_pot/decorated_pot_base.png");
        else if ("decorated_pot_side.png".equals(n)) relatives.add("assets/minecraft/textures/entity/decorated_pot/decorated_pot_side.png");
        else if ("shulker.png".equals(n)) relatives.add("assets/minecraft/textures/entity/shulker/shulker.png");
        else if (n.startsWith("shulker_") && n.endsWith(".png")) {
            String color = n.substring("shulker_".length(), n.length() - 4);
            relatives.add("assets/minecraft/textures/entity/shulker/shulker_" + color + ".png");
            relatives.add("assets/minecraft/textures/entity/shulker/" + color + ".png");
        } else if (n.startsWith("bed_") && n.endsWith(".png")) {
            String color = n.substring("bed_".length(), n.length() - 4);
            relatives.add("assets/minecraft/textures/entity/bed/" + color + ".png");
        } else if (n.endsWith("_bed.png")) {
            String color = n.substring(0, n.length() - "_bed.png".length());
            relatives.add("assets/minecraft/textures/entity/bed/" + color + ".png");
        }
        for (String relative : relatives) {
            File resolved = resolveExportedAssetFile(relative);
            if (resolved != null && resolved.isFile() && resolved.length() > 0L) return resolved;
        }
        return null;
    }

    private void addSpecialTextureCandidate(@NonNull ArrayList<File> candidates, @Nullable File base, @NonNull String name) {
        if (base == null) return;
        File file = new File(new File(new File(base, "droidbridge_dual_screen_assets"), "special_textures"), name);
        String path = file.getAbsolutePath();
        for (File existing : candidates) if (path.equals(existing.getAbsolutePath())) return;
        candidates.add(file);
    }

    @Nullable
    private Bitmap cropSpecialTexture(@NonNull Bitmap source, float left, float top, float right, float bottom) {
        if (source.isRecycled() || source.getWidth() <= 0 || source.getHeight() <= 0) return null;
        int l = Math.max(0, Math.min(source.getWidth() - 1, Math.round(left * source.getWidth())));
        int t = Math.max(0, Math.min(source.getHeight() - 1, Math.round(top * source.getHeight())));
        int r = Math.max(l + 1, Math.min(source.getWidth(), Math.round(right * source.getWidth())));
        int b = Math.max(t + 1, Math.min(source.getHeight(), Math.round(bottom * source.getHeight())));
        String key = "specialcrop151/" + System.identityHashCode(source) + "/" + l + "," + t + "," + r + "," + b;
        Bitmap cached = faceBitmapCache.get(key);
        if (cached != null && !cached.isRecycled()) return cached;
        if (faceBitmapCache.size() > 256) {
            for (Bitmap old : faceBitmapCache.values()) if (old != null && !old.isRecycled()) old.recycle();
            faceBitmapCache.clear();
        }
        try {
            Bitmap cropped = Bitmap.createBitmap(source, l, t, Math.max(1, r - l), Math.max(1, b - t));
            faceBitmapCache.put(key, cropped);
            return cropped;
        } catch (Throwable throwable) {
            return null;
        }
    }

    @Nullable
    private Bitmap solidifyTransparentTexture(@Nullable Bitmap source, int fallbackColor) {
        if (source == null || source.isRecycled() || source.getWidth() <= 0 || source.getHeight() <= 0) return source;
        String cacheKey = "solid152/" + System.identityHashCode(source) + "/" + fallbackColor;
        Bitmap cached = faceBitmapCache.get(cacheKey);
        if (cached != null && !cached.isRecycled()) return cached;
        int w = source.getWidth();
        int h = source.getHeight();
        int transparent = 0;
        long aSum = 0L, rSum = 0L, gSum = 0L, bSum = 0L, opaque = 0L;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = source.getPixel(x, y);
                int a = Color.alpha(c);
                if (a <= 8) {
                    transparent++;
                } else {
                    opaque++;
                    aSum += a;
                    rSum += Color.red(c);
                    gSum += Color.green(c);
                    bSum += Color.blue(c);
                }
            }
        }
        if (transparent == 0) return source;
        int average = fallbackColor;
        if (opaque > 0L) {
            average = Color.argb(255,
                    Math.round(rSum / (float) opaque),
                    Math.round(gSum / (float) opaque),
                    Math.round(bSum / (float) opaque));
        }
        try {
            Bitmap out = source.copy(Bitmap.Config.ARGB_8888, true);
            if (out == null) return source;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (Color.alpha(out.getPixel(x, y)) > 8) continue;
                    int replacement = nearestOpaquePixel(source, x, y, average);
                    out.setPixel(x, y, replacement);
                }
            }
            faceBitmapCache.put(cacheKey, out);
            return out;
        } catch (Throwable ignored) {
            return source;
        }
    }

    private int nearestOpaquePixel(@NonNull Bitmap bitmap, int sx, int sy, int fallbackColor) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        for (int radius = 1; radius <= 12; radius++) {
            int left = Math.max(0, sx - radius);
            int right = Math.min(w - 1, sx + radius);
            int top = Math.max(0, sy - radius);
            int bottom = Math.min(h - 1, sy + radius);
            for (int y = top; y <= bottom; y++) {
                for (int x = left; x <= right; x++) {
                    if (x != left && x != right && y != top && y != bottom) continue;
                    int c = bitmap.getPixel(x, y);
                    if (Color.alpha(c) > 8) return Color.argb(255, Color.red(c), Color.green(c), Color.blue(c));
                }
            }
        }
        return fallbackColor;
    }

    private boolean shouldSolidifyEntityTexture(@NonNull String textureId) {
        String value = textureId.toLowerCase(Locale.ROOT);
        return value.contains("entity/chest")
                || value.contains("entity/shulker")
                || value.contains("textures_entity_chest")
                || value.contains("textures_entity_shulker");
    }

    @Nullable
    private Bitmap loadJsonTexture(@NonNull String textureId) {
        String clean = textureId.trim().toLowerCase(Locale.ROOT);
        if (clean.length() == 0) return null;
        String safe = clean.replace(':', '_').replace('/', '_') + ".png";
        Bitmap cached = itemIconCache.get("jsontex/" + safe);
        if (cached != null && !cached.isRecycled()) return cached;
        File file = resolveJsonTextureFile(safe);
        if (file == null) {
            int colon = clean.indexOf(':');
            String shortName = colon >= 0 ? clean.substring(colon + 1) : clean;
            String shortSafe = shortName.replace(':', '_').replace('/', '_');
            file = resolveJsonTextureFile(shortSafe + ".png");
            if (file == null && shortSafe.startsWith("block_")) {
                file = resolveJsonTextureFile(shortSafe.substring("block_".length()) + ".png");
            }
            if (file == null && shortName.contains("/")) {
                String leaf = shortName.substring(shortName.lastIndexOf('/') + 1);
                file = resolveJsonTextureFile(leaf.replace(':', '_').replace('/', '_') + ".png");
            }
        }
        if (file == null) file = resolveVanillaTextureAssetFile(clean);
        if (file == null || !file.isFile() || file.length() <= 0L) return null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (bitmap != null) itemIconCache.put("jsontex/" + safe, bitmap);
            return bitmap;
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to load exported JSON texture " + textureId, throwable);
            return null;
        }
    }

    @Nullable
    private File resolveVanillaTextureAssetFile(@NonNull String textureId) {
        String value = textureId.trim().toLowerCase(Locale.ROOT);
        if (value.length() == 0) return null;
        String namespace = "minecraft";
        String path = value;
        int colon = value.indexOf(':');
        if (colon >= 0) {
            namespace = value.substring(0, colon);
            path = value.substring(colon + 1);
        }
        if (path.endsWith(".png")) path = path.substring(0, path.length() - 4);
        if (path.startsWith("textures/")) path = path.substring("textures/".length());
        String relative = "assets/" + namespace + "/textures/" + path + ".png";
        File resolved = resolveExportedAssetFile(relative);
        if (resolved != null) return resolved;

        // Some older exporters flatten names. Keep this as a fallback so an assets dump can
        // be copied without having to match one exact folder layout.
        String flat = path.replace('/', '_') + ".png";
        return resolveExportedAssetFile("assets/" + namespace + "/textures/" + flat);
    }

    @Nullable
    private File resolveExportedAssetFile(@NonNull String relativePath) {
        String clean = relativePath.replace('\\', '/');
        while (clean.startsWith("/")) clean = clean.substring(1);
        ArrayList<File> candidates = new ArrayList<>();
        File parent = hudStateFile.getParentFile();
        addExportedAssetCandidate(candidates, parent, clean);
        if (parent != null) {
            addExportedAssetCandidate(candidates, new File(parent, "game"), clean);
            File grand = parent.getParentFile();
            if (grand != null) {
                addExportedAssetCandidate(candidates, grand, clean);
                addExportedAssetCandidate(candidates, new File(grand, "game"), clean);
            }
        }
        for (File candidate : candidates) {
            if (candidate != null && candidate.isFile() && candidate.length() > 0L) return candidate;
        }

        // Do not require users to manually copy or embed vanilla assets.  Resolve the same
        // assets from the installed Minecraft game path: resourcepacks/mod jars first, then
        // the version client jar, and finally the normal .minecraft/assets object store.
        return resolveInstalledMinecraftAssetFile(clean);
    }

    @Nullable
    private File resolveInstalledMinecraftAssetFile(@NonNull String relativePath) {
        String clean = relativePath.replace('\\', '/');
        while (clean.startsWith("/")) clean = clean.substring(1);
        String cacheKey = "installed/" + clean;
        File cached = installedAssetFileCache.get(cacheKey);
        if (cached != null && cached.isFile() && cached.length() > 0L) return cached;

        File loose = resolveLooseInstalledAssetFile(clean);
        if (loose != null && loose.isFile() && loose.length() > 0L) {
            installedAssetFileCache.put(cacheKey, loose);
            return loose;
        }

        File zipped = extractInstalledAssetFromJars(clean);
        if (zipped != null && zipped.isFile() && zipped.length() > 0L) {
            installedAssetFileCache.put(cacheKey, zipped);
            return zipped;
        }

        File object = resolveInstalledAssetObject(clean);
        if (object != null && object.isFile() && object.length() > 0L) {
            installedAssetFileCache.put(cacheKey, object);
            return object;
        }
        return null;
    }

    @Nullable
    private File resolveLooseInstalledAssetFile(@NonNull String clean) {
        ArrayList<File> roots = new ArrayList<>();
        File game = hudStateFile.getParentFile();
        File mcRoot = findMinecraftRoot();
        addLooseAssetRoot(roots, game);
        addLooseAssetRoot(roots, mcRoot);
        if (game != null) addLooseAssetRoot(roots, new File(game, "resourcepacks"));
        if (mcRoot != null) addLooseAssetRoot(roots, new File(mcRoot, "resourcepacks"));
        for (File root : roots) {
            if (root == null || !root.isDirectory()) continue;
            File direct = new File(root, clean);
            if (direct.isFile() && direct.length() > 0L) return direct;
            File[] packs = root.listFiles();
            if (packs == null) continue;
            for (File pack : packs) {
                if (pack == null || !pack.isDirectory()) continue;
                File inside = new File(pack, clean);
                if (inside.isFile() && inside.length() > 0L) return inside;
            }
        }
        return null;
    }

    private void addLooseAssetRoot(@NonNull ArrayList<File> roots, @Nullable File root) {
        if (root == null) return;
        String path = root.getAbsolutePath();
        for (File existing : roots) if (path.equals(existing.getAbsolutePath())) return;
        roots.add(root);
    }

    @Nullable
    private File resolveInstalledAssetObject(@NonNull String clean) {
        File mcRoot = findMinecraftRoot();
        if (mcRoot == null) return null;
        loadInstalledAssetIndexes(mcRoot);
        String[] keys = assetIndexKeysFor(clean);
        for (String key : keys) {
            String hash = installedAssetHashCache.get(key);
            if (hash == null || hash.length() < 2) continue;
            File object = new File(new File(new File(mcRoot, "assets"), "objects"), hash.substring(0, 2) + File.separator + hash);
            if (object.isFile() && object.length() > 0L) return object;
        }
        return null;
    }

    private void loadInstalledAssetIndexes(@NonNull File mcRoot) {
        if (installedAssetIndexesLoaded) return;
        installedAssetIndexesLoaded = true;
        File indexes = new File(new File(mcRoot, "assets"), "indexes");
        File[] files = indexes.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file == null || !file.isFile() || !file.getName().endsWith(".json")) continue;
            try {
                JSONObject root = new JSONObject(new String(readFileBytes(file), StandardCharsets.UTF_8));
                JSONObject objects = root.optJSONObject("objects");
                if (objects == null) continue;
                JSONArray names = objects.names();
                if (names == null) continue;
                for (int i = 0; i < names.length(); i++) {
                    String key = names.optString(i, "");
                    JSONObject obj = objects.optJSONObject(key);
                    if (key.length() == 0 || obj == null) continue;
                    String hash = obj.optString("hash", "");
                    if (hash.length() >= 2) installedAssetHashCache.put(key.replace('\\', '/'), hash);
                }
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to read Minecraft asset index " + file.getAbsolutePath(), throwable);
            }
        }
    }

    @NonNull
    private String[] assetIndexKeysFor(@NonNull String clean) {
        String noAssets = clean.startsWith("assets/") ? clean.substring("assets/".length()) : clean;
        String noMinecraftAssets = clean.startsWith("assets/minecraft/") ? clean.substring("assets/".length()) : noAssets;
        return new String[] { clean, noAssets, noMinecraftAssets };
    }

    @Nullable
    private File extractInstalledAssetFromJars(@NonNull String clean) {
        ArrayList<File> jars = new ArrayList<>();
        File game = hudStateFile.getParentFile();
        File mcRoot = findMinecraftRoot();
        addJarFilesFromDirectory(jars, game == null ? null : new File(game, "resourcepacks"));
        addJarFilesFromDirectory(jars, mcRoot == null ? null : new File(mcRoot, "resourcepacks"));
        addJarFilesFromDirectory(jars, game == null ? null : new File(game, "mods"));
        addJarFilesFromDirectory(jars, mcRoot == null ? null : new File(mcRoot, "mods"));
        addVersionJars(jars, mcRoot);

        for (File jar : jars) {
            File extracted = extractAssetFromZip(jar, clean);
            if (extracted != null && extracted.isFile() && extracted.length() > 0L) return extracted;
        }
        return null;
    }

    private void addVersionJars(@NonNull ArrayList<File> jars, @Nullable File mcRoot) {
        if (mcRoot == null) return;
        File versions = new File(mcRoot, "versions");
        File[] dirs = versions.listFiles();
        if (dirs == null) return;
        ArrayList<File> found = new ArrayList<>();
        for (File dir : dirs) {
            if (dir == null || !dir.isDirectory()) continue;
            File jar = new File(dir, dir.getName() + ".jar");
            if (jar.isFile() && jar.length() > 0L) found.add(jar);
            File[] children = dir.listFiles();
            if (children != null) {
                for (File child : children) if (child != null && child.isFile() && child.getName().endsWith(".jar")) found.add(child);
            }
        }
        Collections.sort(found, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                long d = b.lastModified() - a.lastModified();
                return d > 0 ? 1 : (d < 0 ? -1 : a.getName().compareTo(b.getName()));
            }
        });
        for (File jar : found) addUniqueFile(jars, jar);
    }

    private void addJarFilesFromDirectory(@NonNull ArrayList<File> jars, @Nullable File dir) {
        if (dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file == null || !file.isFile()) continue;
            String name = file.getName().toLowerCase(Locale.ROOT);
            if ((name.endsWith(".jar") || name.endsWith(".zip")) && file.length() > 0L) addUniqueFile(jars, file);
        }
    }

    private void addUniqueFile(@NonNull ArrayList<File> files, @Nullable File file) {
        if (file == null) return;
        String path = file.getAbsolutePath();
        for (File existing : files) if (path.equals(existing.getAbsolutePath())) return;
        files.add(file);
    }

    @Nullable
    private File extractAssetFromZip(@NonNull File zip, @NonNull String clean) {
        if (!zip.isFile() || zip.length() <= 0L) return null;
        ZipFile zipFile = null;
        try {
            zipFile = new ZipFile(zip);
            ZipEntry entry = zipFile.getEntry(clean);
            if (entry == null && clean.startsWith("assets/")) entry = zipFile.getEntry(clean.substring("assets/".length()));
            if (entry == null || entry.isDirectory() || entry.getSize() == 0L) return null;

            File cacheDir = resolvedAssetCacheDir();
            if (cacheDir == null) return null;
            if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) return null;
            String safe = clean.replace('/', '_').replace(':', '_');
            String stamp = Integer.toHexString((zip.getAbsolutePath() + "|" + zip.lastModified() + "|" + zip.length() + "|" + clean).hashCode());
            File out = new File(cacheDir, stamp + "_" + safe);
            if (out.isFile() && out.length() > 0L) return out;

            InputStream in = zipFile.getInputStream(entry);
            FileOutputStream fos = new FileOutputStream(out);
            try {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) fos.write(buffer, 0, read);
            } finally {
                try { fos.close(); } catch (Throwable ignored) {}
                try { in.close(); } catch (Throwable ignored) {}
            }
            return out.isFile() && out.length() > 0L ? out : null;
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (zipFile != null) try { zipFile.close(); } catch (Throwable ignored) {}
        }
    }

    @Nullable
    private File resolvedAssetCacheDir() {
        File parent = hudStateFile.getParentFile();
        if (parent == null) return null;
        return new File(new File(parent, "droidbridge_dual_screen_assets"), "resolved_assets_cache");
    }

    @Nullable
    private File findMinecraftRoot() {
        if (minecraftRootCache != null && minecraftRootCache.isDirectory()) return minecraftRootCache;
        File cursor = hudStateFile.getParentFile();
        for (int i = 0; i < 10 && cursor != null; i++) {
            File versions = new File(cursor, "versions");
            File assets = new File(cursor, "assets");
            if (versions.isDirectory() || assets.isDirectory() || ".minecraft".equals(cursor.getName())) {
                minecraftRootCache = cursor;
                return cursor;
            }
            cursor = cursor.getParentFile();
        }
        return null;
    }

    private void addExportedAssetCandidate(@NonNull ArrayList<File> candidates, @Nullable File base, @NonNull String relativePath) {
        if (base == null) return;
        File root = new File(base, "droidbridge_dual_screen_assets");
        File[] files = new File[] {
                new File(root, relativePath),
                new File(new File(root, "vanilla"), relativePath),
                new File(new File(root, "minecraft"), relativePath)
        };
        for (File file : files) {
            String path = file.getAbsolutePath();
            boolean duplicate = false;
            for (File existing : candidates) {
                if (path.equals(existing.getAbsolutePath())) { duplicate = true; break; }
            }
            if (!duplicate) candidates.add(file);
        }
    }

    @Nullable
    private File resolveJsonTextureFile(@NonNull String name) {
        ArrayList<File> candidates = new ArrayList<>();
        File parent = hudStateFile.getParentFile();
        addJsonTextureCandidate(candidates, parent, name);
        if (parent != null) {
            addJsonTextureCandidate(candidates, new File(parent, "game"), name);
            File grand = parent.getParentFile();
            if (grand != null) {
                addJsonTextureCandidate(candidates, grand, name);
                addJsonTextureCandidate(candidates, new File(grand, "game"), name);
            }
        }
        for (File candidate : candidates) {
            if (candidate != null && candidate.isFile() && candidate.length() > 0L) return candidate;
        }
        return null;
    }

    private void addJsonTextureCandidate(@NonNull ArrayList<File> candidates, @Nullable File base, @NonNull String name) {
        if (base == null) return;
        File file = new File(new File(new File(base, "droidbridge_dual_screen_assets"), "textures"), name);
        String path = file.getAbsolutePath();
        for (File existing : candidates) {
            if (path.equals(existing.getAbsolutePath())) return;
        }
        candidates.add(file);
    }

    @Nullable
    private Bitmap cropTextureForUv(@NonNull Bitmap source, @NonNull String textureId, @Nullable float[] uv, int rotation) {
        if (uv == null || uv.length < 4 || source.getWidth() <= 0 || source.getHeight() <= 0) return source;
        int l = Math.max(0, Math.min(source.getWidth() - 1, Math.round(Math.min(uv[0], uv[2]) / 16f * source.getWidth())));
        int t = Math.max(0, Math.min(source.getHeight() - 1, Math.round(Math.min(uv[1], uv[3]) / 16f * source.getHeight())));
        int r = Math.max(l + 1, Math.min(source.getWidth(), Math.round(Math.max(uv[0], uv[2]) / 16f * source.getWidth())));
        int b = Math.max(t + 1, Math.min(source.getHeight(), Math.round(Math.max(uv[1], uv[3]) / 16f * source.getHeight())));
        if (l == 0 && t == 0 && r == source.getWidth() && b == source.getHeight() && rotation == 0) return source;
        boolean flipX = uv[0] > uv[2];
        boolean flipY = uv[1] > uv[3];
        int normalizedRotation = ((rotation % 360) + 360) % 360;
        String key = "face151/" + textureId + "/" + l + "," + t + "," + r + "," + b + ","
                + normalizedRotation + "," + flipX + "," + flipY;
        Bitmap cached = faceBitmapCache.get(key);
        if (cached != null && !cached.isRecycled()) return cached;
        if (faceBitmapCache.size() > 256) {
            for (Bitmap old : faceBitmapCache.values()) if (old != null && !old.isRecycled()) old.recycle();
            faceBitmapCache.clear();
        }
        try {
            Bitmap cropped = Bitmap.createBitmap(source, l, t, Math.max(1, r - l), Math.max(1, b - t));
            if (flipX || flipY || normalizedRotation != 0) {
                Matrix matrix = new Matrix();
                matrix.postScale(flipX ? -1f : 1f, flipY ? -1f : 1f, cropped.getWidth() * 0.5f, cropped.getHeight() * 0.5f);
                if (normalizedRotation != 0) {
                    matrix.postRotate(normalizedRotation, cropped.getWidth() * 0.5f, cropped.getHeight() * 0.5f);
                }
                cropped = Bitmap.createBitmap(cropped, 0, 0, cropped.getWidth(), cropped.getHeight(), matrix, false);
            }
            if (shouldSolidifyEntityTexture(textureId)) {
                cropped = solidifyTransparentTexture(cropped, Color.rgb(118, 74, 35));
            }
            faceBitmapCache.put(key, cropped);
            return cropped;
        } catch (Throwable ignored) {
            return source;
        }
    }

    private boolean drawSpritePips(@NonNull Canvas canvas, float x, float y, int count, float filled, @NonNull String type) {
        Bitmap full = loadHudSprite(type + "_full");
        Bitmap half = loadHudSprite(type + "_half");
        Bitmap empty = loadHudSprite(type + "_empty");
        if (full == null && empty == null) return false;
        float size = dp(13);
        float gap = dp(3);
        int limit = Math.min(10, Math.max(0, count));
        fill.setFilterBitmap(false);
        fill.setDither(false);
        for (int i = 0; i < limit; i++) {
            float l = x + i * (size + gap);
            scratch.set(l, y, l + size, y + size);
            Bitmap base = filled - i >= 1f ? full : (filled - i > 0f && half != null ? half : empty);
            if (base == null) base = full != null ? full : empty;
            if (base != null) canvas.drawBitmap(base, null, scratch, fill);
        }
        return true;
    }

    @Nullable
    private Bitmap loadHudSprite(@NonNull String name) {
        String clean = name.endsWith(".png") ? name : name + ".png";
        Bitmap cached = itemIconCache.get("hud/" + clean);
        if (cached != null && !cached.isRecycled()) return cached;
        File file = resolveHudSpriteFile(clean);
        if (file == null || !file.isFile() || file.length() <= 0L) return null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (bitmap != null) itemIconCache.put("hud/" + clean, bitmap);
            return bitmap;
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to load HUD sprite " + clean, throwable);
            return null;
        }
    }

    @Nullable
    private File resolveHudSpriteFile(@NonNull String name) {
        ArrayList<File> candidates = new ArrayList<>();
        File parent = hudStateFile.getParentFile();
        addHudSpriteCandidate(candidates, parent, name);
        if (parent != null) {
            addHudSpriteCandidate(candidates, new File(parent, "game"), name);
            File grand = parent.getParentFile();
            if (grand != null) {
                addHudSpriteCandidate(candidates, grand, name);
                addHudSpriteCandidate(candidates, new File(grand, "game"), name);
            }
        }
        for (File candidate : candidates) if (candidate != null && candidate.isFile() && candidate.length() > 0L) return candidate;
        return null;
    }

    private void addHudSpriteCandidate(@NonNull ArrayList<File> candidates, @Nullable File base, @NonNull String name) {
        if (base == null) return;
        File file = new File(new File(new File(base, "droidbridge_dual_screen_assets"), "hud"), name);
        String path = file.getAbsolutePath();
        for (File existing : candidates) if (path.equals(existing.getAbsolutePath())) return;
        candidates.add(file);
    }

    @NonNull
    private static byte[] readFileBytes(@NonNull File file) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(128, (int) Math.min(file.length(), 64 * 1024)));
        FileInputStream in = new FileInputStream(file);
        try {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toByteArray();
        } finally {
            try { in.close(); } catch (Throwable ignored) {}
        }
    }

    private boolean isSculkLikeBlockItem(@NonNull String key) {
        String value = key == null ? "" : key.toLowerCase(Locale.ROOT);
        return value.contains("sculk")
                || value.contains("shrieker")
                || value.contains("shriek")
                || value.contains("catalyst")
                || value.contains("calibrated_sensor")
                || value.contains("sculk_sensor");
    }

    private boolean isJsonBlockModelItem(@NonNull String key) {
        return containsAny(key,
                "_stairs", "_slab", "_door", "_trapdoor", "_planks", "_log", "_wood", "_stem", "_hyphae",
                "stone", "cobblestone", "deepslate", "andesite", "granite", "diorite", "tuff", "brick",
                "dirt", "grass_block", "podzol", "mycelium", "mud", "sand", "gravel", "clay", "snow_block",
                "ice", "wool", "concrete", "terracotta", "glass", "crafting_table", "furnace", "chest",
                "barrel", "bookshelf", "hopper", "dispenser", "dropper", "observer", "piston", "ore",
                "lectern", "dripleaf", "sculk", "shrieker", "sensor", "catalyst", "vein");
    }

    @Nullable
    private Bitmap loadModelTexture(@NonNull HudItem item, @NonNull String suffix) {
        String cleanId = item.id == null ? "" : item.id.trim().toLowerCase(Locale.ROOT);
        if (cleanId.isEmpty()) cleanId = item.itemKey();
        if (cleanId.isEmpty()) return null;
        String safe = cleanId.replace(':', '_').replace('/', '_');
        String shortSafe = safe;
        int colon = cleanId.indexOf(':');
        if (colon >= 0 && colon + 1 < cleanId.length()) {
            shortSafe = cleanId.substring(colon + 1).replace(':', '_').replace('/', '_');
        }
        Bitmap bitmap = loadModelTextureByName(safe + "_" + suffix + ".png");
        if (bitmap == null) bitmap = loadModelTextureByName(shortSafe + "_" + suffix + ".png");
        return bitmap;
    }

    @Nullable
    private Bitmap loadModelTextureByName(@NonNull String name) {
        String clean = name.endsWith(".png") ? name : name + ".png";
        String cacheKey = "model/" + clean;
        Bitmap cached = itemIconCache.get(cacheKey);
        if (cached != null && !cached.isRecycled()) return cached;
        File file = resolveModelTextureFile(clean);
        if (file == null || !file.isFile() || file.length() <= 0L) return null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (bitmap != null) {
                itemIconCache.put(cacheKey, bitmap);
                return bitmap;
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to load dual-screen model texture " + clean, throwable);
        }
        return null;
    }

    @Nullable
    private File resolveModelTextureFile(@NonNull String name) {
        ArrayList<File> candidates = new ArrayList<>();
        File parent = hudStateFile.getParentFile();
        addModelTextureCandidate(candidates, parent, name);
        if (parent != null) {
            addModelTextureCandidate(candidates, new File(parent, "game"), name);
            File grand = parent.getParentFile();
            if (grand != null) {
                addModelTextureCandidate(candidates, grand, name);
                addModelTextureCandidate(candidates, new File(grand, "game"), name);
            }
        }
        for (File candidate : candidates) {
            if (candidate != null && candidate.isFile() && candidate.length() > 0L) return candidate;
        }
        return null;
    }

    private void addModelTextureCandidate(@NonNull ArrayList<File> candidates, @Nullable File base, @NonNull String name) {
        if (base == null) return;
        File file = new File(new File(new File(base, "droidbridge_dual_screen_assets"), "model_textures"), name);
        String path = file.getAbsolutePath();
        for (File existing : candidates) {
            if (path.equals(existing.getAbsolutePath())) return;
        }
        candidates.add(file);
    }

    private void drawCuboidModelIcon(@NonNull Canvas canvas, @NonNull RectF r, @Nullable Bitmap side, @Nullable Bitmap top, float heightScale) {
        if (side == null && top == null) return;
        if (side == null) side = top;
        if (top == null) top = side;
        float cx = r.centerX();
        float topY = r.top + r.height() * (heightScale >= 0.95f ? 0.06f : 0.28f);
        float midY = r.top + r.height() * (heightScale >= 0.95f ? 0.34f : 0.46f);
        float bottomY = r.top + r.height() * 0.84f;
        float halfW = r.width() * 0.36f;
        float depth = r.height() * 0.18f;
        float[] topQuad = { cx, topY, cx + halfW, midY, cx, midY + depth, cx - halfW, midY };
        float[] leftQuad = { cx - halfW, midY, cx, midY + depth, cx, bottomY, cx - halfW, bottomY - depth };
        float[] rightQuad = { cx, midY + depth, cx + halfW, midY, cx + halfW, bottomY - depth, cx, bottomY };
        drawTexturedQuad(canvas, top, topQuad, 1.12f);
        drawTexturedQuad(canvas, side, leftQuad, 0.72f);
        drawTexturedQuad(canvas, side, rightQuad, 0.88f);
        drawQuadStroke(canvas, topQuad);
        drawQuadStroke(canvas, leftQuad);
        drawQuadStroke(canvas, rightQuad);
    }

    private void drawStairsModelIcon(@NonNull Canvas canvas, @NonNull RectF r, @Nullable Bitmap side, @Nullable Bitmap top) {
        if (side == null && top == null) return;
        if (side == null) side = top;
        if (top == null) top = side;
        RectF bottom = new RectF(r.left, r.top + r.height() * 0.22f, r.right, r.bottom);
        RectF upper = new RectF(r.left + r.width() * 0.18f, r.top + r.height() * 0.04f, r.right - r.width() * 0.10f, r.top + r.height() * 0.63f);
        drawCuboidModelIcon(canvas, bottom, side, top, 0.52f);
        drawCuboidModelIcon(canvas, upper, side, top, 0.52f);
    }

    private void drawThinPanelModelIcon(@NonNull Canvas canvas, @NonNull RectF r, @Nullable Bitmap texture) {
        if (texture == null) return;
        float insetX = r.width() * 0.18f;
        float insetY = r.height() * 0.06f;
        float skew = r.width() * 0.10f;
        float[] front = { r.left + insetX, r.top + insetY, r.right - insetX, r.top + insetY, r.right - insetX - skew, r.bottom - insetY, r.left + insetX - skew, r.bottom - insetY };
        float[] side = { r.right - insetX, r.top + insetY, r.right - insetX + skew * 0.45f, r.top + insetY + skew * 0.30f, r.right - insetX - skew + skew * 0.45f, r.bottom - insetY + skew * 0.30f, r.right - insetX - skew, r.bottom - insetY };
        drawTexturedQuad(canvas, texture, side, 0.62f);
        drawTexturedQuad(canvas, texture, front, 1f);
        drawQuadStroke(canvas, front);
    }

    private void drawTexturedQuad(@NonNull Canvas canvas, @Nullable Bitmap bitmap, @NonNull float[] verts, float brightness) {
        if (bitmap == null) return;
        fill.setFilterBitmap(false);
        fill.setDither(false);
        try {
            // drawBitmapMesh(1, 1) expects vertices in grid order:
            // top-left, top-right, bottom-left, bottom-right.  The model pipeline stores
            // quads in perimeter order: top-left, top-right, bottom-right, bottom-left.
            // Passing perimeter order directly creates crossed triangles, which is what made
            // the model faces look unfilled/hollow.
            float[] meshVerts = new float[] {
                    verts[0], verts[1],
                    verts[2], verts[3],
                    verts[6], verts[7],
                    verts[4], verts[5]
            };
            canvas.drawBitmapMesh(bitmap, 1, 1, meshVerts, 0, null, 0, fill);
        } catch (Throwable ignored) {
            float minX = Math.min(Math.min(verts[0], verts[2]), Math.min(verts[4], verts[6]));
            float maxX = Math.max(Math.max(verts[0], verts[2]), Math.max(verts[4], verts[6]));
            float minY = Math.min(Math.min(verts[1], verts[3]), Math.min(verts[5], verts[7]));
            float maxY = Math.max(Math.max(verts[1], verts[3]), Math.max(verts[5], verts[7]));
            canvas.drawBitmap(bitmap, null, new RectF(minX, minY, maxX, maxY), fill);
        }
        if (brightness < 0.99f || brightness > 1.01f) {
            Path p = new Path();
            p.moveTo(verts[0], verts[1]);
            p.lineTo(verts[2], verts[3]);
            p.lineTo(verts[4], verts[5]);
            p.lineTo(verts[6], verts[7]);
            p.close();
            fill.setStyle(Paint.Style.FILL);
            if (brightness < 1f) fill.setColor(Color.argb((int) ((1f - brightness) * 150f), 0, 0, 0));
            else fill.setColor(Color.argb((int) ((brightness - 1f) * 90f), 255, 255, 255));
            canvas.drawPath(p, fill);
        }
    }

    private void drawQuadStroke(@NonNull Canvas canvas, @NonNull float[] verts) {
        drawQuadStroke(canvas, verts, false);
    }

    private void drawQuadStroke(@NonNull Canvas canvas, @NonNull float[] verts, boolean subtle) {
        Path p = new Path();
        p.moveTo(verts[0], verts[1]);
        p.lineTo(verts[2], verts[3]);
        p.lineTo(verts[4], verts[5]);
        p.lineTo(verts[6], verts[7]);
        p.close();
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(subtle ? dp(0.45f) : dp(0.9f));
        stroke.setColor(Color.argb(subtle ? 42 : 110, 0, 0, 0));
        canvas.drawPath(p, stroke);
    }

    private void drawMissingItemPlaceholder(@NonNull Canvas canvas, @NonNull RectF r) {
        drawRoundRect(canvas, r, Color.rgb(41, 43, 51), Color.rgb(75, 82, 96), 7f);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(Color.rgb(94, 102, 118));
        canvas.drawCircle(r.centerX(), r.centerY(), Math.max(dp(1.5f), r.width() * 0.035f), fill);
    }

    private boolean containsAny(@NonNull String value, @NonNull String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }


    private void recycleBitmapCache(@NonNull Map<String, Bitmap> cache) {
        for (Bitmap old : cache.values()) {
            if (old != null && !old.isRecycled()) old.recycle();
        }
        cache.clear();
    }

    @Nullable
    private Bitmap loadItemIcon(@Nullable String path) {
        if (path == null || path.trim().isEmpty()) return null;
        String rawPath = path.trim();
        try {
            File file = new File(rawPath);
            if (!file.isFile() || file.length() <= 0L) return null;

            // The bridge jar may overwrite the same iconPath after Minecraft's
            // GuiItemAtlas capture succeeds. Include timestamp and length so
            // the bottom HUD refreshes instead of reusing an older flat PNG.
            String key = file.getAbsolutePath() + "#" + file.lastModified() + "#" + file.length();
            Bitmap cached = itemIconCache.get(key);
            if (cached != null && !cached.isRecycled()) return cached;

            if (itemIconCache.size() > 128) {
                for (Bitmap old : itemIconCache.values()) {
                    if (old != null && !old.isRecycled()) old.recycle();
                }
                itemIconCache.clear();
            }

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (bitmap != null) {
                itemIconCache.put(key, bitmap);
                return bitmap;
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to load dual-screen item icon " + rawPath, throwable);
        }
        return null;
    }

    private float clamp01(float value) {
        if (value < 0f) return 0f;
        if (value > 1f) return 1f;
        return value;
    }

    private void keepMinecraftInputReady() {
        CallbackBridge.setInputReady(true);
        CallbackBridge.ensureInputFocus();
    }

    private boolean isActionPressed(@NonNull String action) {
        for (int i = 0; i < activeTouches.size(); i++) {
            ActiveTouch touch = activeTouches.valueAt(i);
            if (action.equals(touch.action)) return true;
        }
        return false;
    }

    private void drawRoundRect(@NonNull Canvas canvas, @NonNull RectF bounds, int fillColor, int strokeColor, float radiusDp) {
        float radius = dp(radiusDp);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(fillColor);
        canvas.drawRoundRect(bounds, radius, radius, fill);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(1.2f));
        stroke.setColor(strokeColor);
        canvas.drawRoundRect(bounds, radius, radius, stroke);
    }

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        if (event.getSource() == 0) event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        int action = event.getActionMasked();
        int index = event.getActionIndex();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                return handlePointerDown(event, index);
            case MotionEvent.ACTION_MOVE:
                if (activeTouches.size() <= 0) return false;
                handlePointerMove(event);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                if (index < 0 || index >= event.getPointerCount()) return false;
                if (activeTouches.get(event.getPointerId(index)) == null) return false;
                handlePointerUp(event, index, false);
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (activeTouches.size() <= 0) return false;
                releaseAllActiveTouches();
                return true;
            default:
                return activeTouches.size() > 0;
        }
    }

    private boolean handlePointerDown(@NonNull MotionEvent event, int index) {
        if (index < 0 || index >= event.getPointerCount()) return false;
        int pointerId = event.getPointerId(index);
        float x = event.getX(index);
        float y = event.getY(index);

        int slot = hotbarSlotAt(x, y);
        if (slot >= 0) {
            selectHotbarByTouch(slot);
            activeTouches.put(pointerId, ActiveTouch.hotbar(slot));
            keepMinecraftInputReady();
            invalidate();
            return true;
        }

        ButtonRegion button = buttonAt(x, y);
        if (button == null) return false;

        activeTouches.put(pointerId, ActiveTouch.button(button.action, button.holdAction));
        if (button.holdAction) {
            sendActionDown(button.action);
        }
        keepMinecraftInputReady();
        invalidate();
        return true;
    }

    private void handlePointerMove(@NonNull MotionEvent event) {
        for (int i = 0; i < event.getPointerCount(); i++) {
            int pointerId = event.getPointerId(i);
            ActiveTouch active = activeTouches.get(pointerId);
            if (active == null || !active.hotbar) continue;
            int slot = hotbarSlotAt(event.getX(i), event.getY(i));
            if (slot >= 0 && slot != active.slot) {
                active.slot = slot;
                selectHotbarByTouch(slot);
                invalidate();
            }
        }
    }

    private void handlePointerUp(@NonNull MotionEvent event, int index, boolean cancel) {
        if (index < 0 || index >= event.getPointerCount()) return;
        int pointerId = event.getPointerId(index);
        ActiveTouch active = activeTouches.get(pointerId);
        if (active == null) return;
        activeTouches.remove(pointerId);

        if (active.hotbar) {
            handler.removeCallbacks(clearControllerSlotFeedbackRunnable);
            handler.postDelayed(clearControllerSlotFeedbackRunnable, 650L);
            invalidate();
            return;
        }

        if (active.holdAction) {
            sendActionUp(active.action);
        } else if (!cancel) {
            sendActionTap(active.action);
        }
        invalidate();
    }

    private int hotbarSlotAt(float x, float y) {
        for (int i = 0; i < hotbarSlots.length; i++) {
            if (hotbarSlots[i].contains(x, y)) return i;
        }
        return -1;
    }

    @Nullable
    private ButtonRegion buttonAt(float x, float y) {
        for (int i = buttons.size() - 1; i >= 0; i--) {
            ButtonRegion button = buttons.get(i);
            if (button.bounds.contains(x, y)) return button;
        }
        return null;
    }

    private void releaseAllActiveTouches() {
        for (int i = 0; i < activeTouches.size(); i++) {
            ActiveTouch touch = activeTouches.valueAt(i);
            if (touch != null && touch.holdAction) sendActionUp(touch.action);
        }
        activeTouches.clear();
        touchedHotbarSlot = -1;
        invalidate();
    }


    @Override
    public boolean onKeyDown(int keyCode, @NonNull KeyEvent event) {
        if (handleControllerHotbarKey(keyCode, event)) return true;
        return super.onKeyDown(keyCode, event);
    }

    public boolean handleControllerHotbarKey(int keyCode, @NonNull KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (isControllerHotbarPreviousKey(keyCode)) {
            navigateHotbarByController(-1);
            return true;
        }
        if (isControllerHotbarNextKey(keyCode)) {
            navigateHotbarByController(1);
            return true;
        }
        return false;
    }

    @Override
    public boolean onGenericMotionEvent(@NonNull MotionEvent event) {
        if (handleControllerHotbarMotion(event)) return true;
        return super.onGenericMotionEvent(event);
    }

    public boolean handleControllerHotbarMotion(@NonNull MotionEvent event) {
        int source = event.getSource();
        boolean controller = (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD;
        if (!controller) return false;

        float l = Math.max(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE));
        float r = Math.max(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS));
        boolean lDown = l > 0.35f;
        boolean rDown = r > 0.35f;
        boolean handled = false;
        if (lDown && !leftTriggerWasDown) {
            navigateHotbarByController(-1);
            handled = true;
        }
        if (rDown && !rightTriggerWasDown) {
            navigateHotbarByController(1);
            handled = true;
        }
        leftTriggerWasDown = lDown;
        rightTriggerWasDown = rDown;
        return handled;
    }

    private boolean isControllerHotbarPreviousKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_BUTTON_L1 || keyCode == KeyEvent.KEYCODE_BUTTON_L2;
    }

    private boolean isControllerHotbarNextKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_BUTTON_R1 || keyCode == KeyEvent.KEYCODE_BUTTON_R2;
    }

    private void navigateHotbarByController(int delta) {
        long now = SystemClock.uptimeMillis();
        if (now - lastControllerHotbarNavMs < CONTROLLER_HOTBAR_NAV_DEBOUNCE_MS) return;
        // A real controller/trigger navigation should immediately supersede any old
        // touchscreen acknowledgment window. Controller behavior itself is unchanged.
        pendingTouchHotbarSlot = -1;
        pendingTouchHotbarUntilMs = 0L;
        lastControllerHotbarNavMs = now;
        int base = selectedSlot;
        // 1.0.112: when the user is actively scrolling, do not wait for the next
        // file-state poll before choosing the next local slot. The state writer can
        // lag one poll behind mouse-wheel/trigger input.
        if (touchedHotbarSlot < 0 && hudState.isFresh() && hudState.selectedSlot >= 0 && hudState.selectedSlot < SLOT_COUNT) base = hudState.selectedSlot;
        int next = (base + delta) % SLOT_COUNT;
        if (next < 0) next += SLOT_COUNT;
        selectedSlot = next;
        touchedHotbarSlot = next;
        sendKeyTap(LwjglGlfwKeycode.GLFW_KEY_1 + next);
        invalidate();
        handler.removeCallbacks(clearControllerSlotFeedbackRunnable);
        handler.postDelayed(clearControllerSlotFeedbackRunnable, 700L);
    }

    @NonNull private final Runnable clearControllerSlotFeedbackRunnable = new Runnable() {
        @Override public void run() {
            touchedHotbarSlot = -1;
            invalidate();
        }
    };

    private void sendActionTap(@NonNull String action) {
        switch (action) {
            case ACTION_MC_MENU:
                sendKeyTap(LwjglGlfwKeycode.GLFW_KEY_ESCAPE);
                break;
            case ACTION_LAUNCHER_MENU:
                launcherMenuCallback.run();
                break;
            case ACTION_INVENTORY:
                sendKeyTap(LwjglGlfwKeycode.GLFW_KEY_E);
                break;
            case ACTION_CHAT:
                sendKeyTap(LwjglGlfwKeycode.GLFW_KEY_T);
                break;
            case ACTION_PERSPECTIVE:
                sendKeyTap(LwjglGlfwKeycode.GLFW_KEY_F5);
                break;
            default:
                sendActionDown(action);
                sendActionUp(action);
                break;
        }
    }

    private void sendActionDown(@NonNull String action) {
        switch (action) {
            case ACTION_ATTACK:
                sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, true);
                break;
            case ACTION_USE:
                sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT, true);
                break;
            case ACTION_JUMP:
                sendKey(LwjglGlfwKeycode.GLFW_KEY_SPACE, true);
                break;
            case ACTION_SNEAK:
                sendKey(LwjglGlfwKeycode.GLFW_KEY_LEFT_SHIFT, true);
                break;
            default:
                break;
        }
    }

    private void sendActionUp(@NonNull String action) {
        switch (action) {
            case ACTION_ATTACK:
                sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, false);
                break;
            case ACTION_USE:
                sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT, false);
                break;
            case ACTION_JUMP:
                sendKey(LwjglGlfwKeycode.GLFW_KEY_SPACE, false);
                break;
            case ACTION_SNEAK:
                sendKey(LwjglGlfwKeycode.GLFW_KEY_LEFT_SHIFT, false);
                break;
            default:
                break;
        }
    }

    private void sendKeyTap(int keyCode) {
        sendKey(keyCode, true);
        sendKey(keyCode, false);
    }

    private void sendKey(int keyCode, boolean down) {
        try {
            CallbackBridge.setInputReady(true);
            CallbackBridge.sendKeyPress(keyCode, CallbackBridge.getCurrentMods(), down);
            CallbackBridge.setModifiers(keyCode, down);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to send dual-screen key " + keyCode + " down=" + down, throwable);
        }
    }

    private void sendMouseButton(int button, boolean down) {
        try {
            CallbackBridge.setInputReady(true);
            CallbackBridge.sendMouseButton(button, down);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to send dual-screen mouse button " + button + " down=" + down, throwable);
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }

    @NonNull
    private String fitText(@NonNull String value, float maxWidth, @NonNull Paint paint) {
        if (value.isEmpty() || paint.measureText(value) <= maxWidth) return value;
        String ellipsis = "…";
        int end = value.length();
        while (end > 1 && paint.measureText(value.substring(0, end) + ellipsis) > maxWidth) {
            end--;
        }
        return value.substring(0, Math.max(1, end)) + ellipsis;
    }

    private static final class GuiTransform {
        final float rotX;
        final float rotY;
        final float rotZ;

        GuiTransform(float rotX, float rotY, float rotZ) {
            this.rotX = rotX;
            this.rotY = rotY;
            this.rotZ = rotZ;
        }

        @Nullable static GuiTransform fromJson(@Nullable JSONObject displayJson) {
            if (displayJson == null) return null;
            JSONObject gui = displayJson.optJSONObject("gui");
            if (gui == null) return null;
            JSONArray rotation = gui.optJSONArray("rotation");
            if (rotation == null || rotation.length() < 3) return null;
            return new GuiTransform(
                    (float) rotation.optDouble(0, MODEL_GUI_ROT_X_DEGREES),
                    (float) rotation.optDouble(1, MODEL_GUI_ROT_Y_DEGREES),
                    (float) rotation.optDouble(2, MODEL_GUI_ROT_Z_DEGREES)
            );
        }
    }

    private static final class ModelDefinition {
        @NonNull final Map<String, String> textures = new HashMap<>();
        @NonNull final ArrayList<ModelElement> elements = new ArrayList<>();
        @NonNull String parent = "";
        @Nullable GuiTransform guiTransform;

        @Nullable String texture(@NonNull String key) { return textures.get(key); }

        @Nullable String resolveTexture(@Nullable String raw) {
            if (raw == null || raw.length() == 0) return null;
            String value = raw;
            for (int i = 0; i < 16 && value != null && value.startsWith("#"); i++) {
                value = textures.get(value.substring(1));
            }
            return value;
        }

        @NonNull static ModelDefinition fromJson(@NonNull JSONObject root) {
            ModelDefinition model = new ModelDefinition();
            model.parent = root.optString("parent", "").trim();
            model.guiTransform = GuiTransform.fromJson(root.optJSONObject("display"));
            JSONObject texturesJson = root.optJSONObject("textures");
            if (texturesJson != null) {
                JSONArray names = texturesJson.names();
                if (names != null) {
                    for (int i = 0; i < names.length(); i++) {
                        String name = names.optString(i, "");
                        String value = texturesJson.optString(name, "");
                        if (!name.isEmpty() && !value.isEmpty()) model.textures.put(name, value);
                    }
                }
            }
            JSONArray elementsJson = root.optJSONArray("elements");
            if (elementsJson != null) {
                for (int i = 0; i < elementsJson.length(); i++) {
                    JSONObject object = elementsJson.optJSONObject(i);
                    if (object == null) continue;
                    ModelElement element = ModelElement.fromJson(object);
                    if (element != null) model.elements.add(element);
                }
            }
            return model;
        }
    }

    private static final class ModelElement {
        @NonNull float[] from = new float[] {0f, 0f, 0f};
        @NonNull float[] to = new float[] {16f, 16f, 16f};
        @NonNull final Map<String, ModelFace> faces = new HashMap<>();

        @Nullable static ModelElement fromJson(@NonNull JSONObject root) {
            ModelElement element = new ModelElement();
            element.from = floatArray(root.optJSONArray("from"), element.from);
            element.to = floatArray(root.optJSONArray("to"), element.to);
            JSONObject facesJson = root.optJSONObject("faces");
            if (facesJson == null) return null;
            JSONArray names = facesJson.names();
            if (names == null) return null;
            for (int i = 0; i < names.length(); i++) {
                String name = names.optString(i, "");
                JSONObject faceJson = facesJson.optJSONObject(name);
                if (name.isEmpty() || faceJson == null) continue;
                ModelFace face = ModelFace.fromJson(name, faceJson);
                if (face != null) element.faces.put(name, face);
            }
            return element.faces.isEmpty() ? null : element;
        }

        @NonNull private static float[] floatArray(@Nullable JSONArray array, @NonNull float[] fallback) {
            if (array == null || array.length() <= 0) return fallback;
            float[] out = new float[Math.min(array.length(), fallback.length)];
            for (int i = 0; i < out.length; i++) out[i] = (float) array.optDouble(i, fallback[Math.min(i, fallback.length - 1)]);
            if (out.length == fallback.length) return out;
            float[] padded = fallback.clone();
            System.arraycopy(out, 0, padded, 0, out.length);
            return padded;
        }
    }

    private static final class ModelFace {
        @NonNull String name = "";
        @NonNull String texture = "";
        @Nullable float[] uv;
        int rotation;

        @Nullable static ModelFace fromJson(@NonNull String name, @NonNull JSONObject root) {
            String texture = root.optString("texture", "");
            if (texture.isEmpty()) return null;
            ModelFace face = new ModelFace();
            face.name = name;
            face.texture = texture;
            JSONArray uvJson = root.optJSONArray("uv");
            if (uvJson != null && uvJson.length() >= 4) {
                face.uv = new float[] {(float) uvJson.optDouble(0), (float) uvJson.optDouble(1), (float) uvJson.optDouble(2), (float) uvJson.optDouble(3)};
            }
            face.rotation = root.optInt("rotation", 0);
            return face;
        }
    }

    private static final class FaceDraw {
        @NonNull String faceName = "";
        @NonNull String texture = "";
        @Nullable float[] uv;
        int rotation;
        @NonNull float[] verts = new float[8];
        @NonNull float[] depths = new float[4];
        float depth;
    }

    private static final class Bounds2D {
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        void include(@NonNull float[] p) {
            if (p[0] < minX) minX = p[0];
            if (p[0] > maxX) maxX = p[0];
            if (p[1] < minY) minY = p[1];
            if (p[1] > maxY) maxY = p[1];
        }
        boolean valid() { return minX < maxX && minY < maxY; }
    }

    private static final class ButtonRegion {
        @NonNull final RectF bounds;
        @NonNull final String label;
        @NonNull final String subLabel;
        @NonNull final String action;
        final boolean holdAction;

        ButtonRegion(@NonNull RectF bounds, @NonNull String label, @NonNull String subLabel,
                     @NonNull String action, boolean holdAction) {
            this.bounds = bounds;
            this.label = label;
            this.subLabel = subLabel;
            this.action = action;
            this.holdAction = holdAction;
        }
    }

    private static final class ActiveTouch {
        final boolean hotbar;
        final boolean holdAction;
        @NonNull final String action;
        int slot;

        private ActiveTouch(boolean hotbar, int slot, @NonNull String action, boolean holdAction) {
            this.hotbar = hotbar;
            this.slot = slot;
            this.action = action;
            this.holdAction = holdAction;
        }

        static ActiveTouch hotbar(int slot) {
            return new ActiveTouch(true, slot, "hotbar", false);
        }

        static ActiveTouch button(@NonNull String action, boolean holdAction) {
            return new ActiveTouch(false, -1, action, holdAction);
        }
    }

    private static final class HudItem {
        @NonNull final String label;
        @NonNull final String id;
        @NonNull final String iconPath;
        @NonNull final String iconMode;
        final int count;
        final int damage;
        final int maxDamage;

        HudItem(@Nullable String label, @Nullable String id, @Nullable String iconPath, @Nullable String iconMode, int count, int damage, int maxDamage) {
            String cleanId = sanitizeItemId(id, label);
            this.label = sanitizeItemLabel(label, cleanId);
            this.id = cleanId;
            this.iconPath = iconPath == null ? "" : iconPath.trim();
            this.iconMode = iconMode == null ? "" : iconMode.trim();
            this.count = Math.max(0, count);
            this.damage = damage;
            this.maxDamage = maxDamage;
        }

        static HudItem empty() { return new HudItem("", "", "", "", 0, -1, -1); }
        boolean isEmpty() { return count <= 0 && label.isEmpty() && id.isEmpty(); }
        @NonNull String displayLabel() {
            if (!label.isEmpty()) return label;
            String fromId = displayFromId(id);
            return fromId.isEmpty() ? "" : fromId;
        }
        @NonNull String itemKey() {
            if (!id.isEmpty()) return id.toLowerCase(Locale.ROOT);
            String fromLabel = displayLabel().toLowerCase(Locale.ROOT).replace(' ', '_');
            if (!fromLabel.isEmpty()) return fromLabel;
            return itemKeyFromPath(iconPath);
        }

        @NonNull private static String itemKeyFromPath(@Nullable String rawPath) {
            if (rawPath == null) return "";
            String value = rawPath.trim().toLowerCase(Locale.ROOT);
            if (value.isEmpty()) return "";
            value = value.replace('\\', '/');
            int slash = value.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < value.length()) value = value.substring(slash + 1);
            int dot = value.lastIndexOf('.');
            if (dot > 0) value = value.substring(0, dot);

            // Runtime/exported icon names often contain the only useful item identity when
            // the state writer produced a blank label/id for the first few HUD frames.
            // Normalize those names so block-entity/template families do not fall through
            // to raw square PNG drawing.
            String[] prefixes = new String[] {
                    "flat_minecraft_", "item_minecraft_", "minecraft_",
                    "flat_", "item_", "icon_", "slot_"
            };
            boolean changed = true;
            while (changed) {
                changed = false;
                for (String prefix : prefixes) {
                    if (value.startsWith(prefix) && value.length() > prefix.length()) {
                        value = value.substring(prefix.length());
                        changed = true;
                    }
                }
            }

            if (value.equals("chest_normal") || value.equals("normal_chest")) return "chest";
            if (value.equals("chest_trapped") || value.equals("trapped_chest")) return "trapped_chest";
            if (value.equals("chest_ender") || value.equals("ender_chest")) return "ender_chest";
            if (value.startsWith("bed_")) return value.substring(4) + "_bed";
            if (value.startsWith("shulker_")) return value.substring(8) + "_shulker_box";

            // Remove common numeric/hash suffixes from probe/cache filenames when the
            // readable item name appears before the suffix.
            value = value.replaceAll("_[0-9a-f]{8,}$", "");
            value = value.replaceAll("_[0-9]+$", "");
            return value;
        }
        @NonNull String shortCode() {
            String source = displayLabel();
            if (source.isEmpty()) return "·";
            String[] parts = source.replace('_', ' ').trim().split("\\s+");
            StringBuilder out = new StringBuilder(3);
            for (String part : parts) {
                if (part.length() == 0) continue;
                out.append(Character.toUpperCase(part.charAt(0)));
                if (out.length() >= 3) break;
            }
            if (out.length() == 0) out.append(Character.toUpperCase(source.charAt(0)));
            return out.toString();
        }

        @NonNull private static String sanitizeItemLabel(@Nullable String raw, @NonNull String id) {
            String value = raw == null ? "" : raw.trim();
            String keyId = idFromTranslation(value);
            if (!keyId.isEmpty()) return displayFromId(keyId);
            if (value.startsWith("translation{") || value.startsWith("translatable{") || value.contains("key='")) {
                String fromId = displayFromId(id);
                return fromId.isEmpty() ? "" : fromId;
            }
            if (!value.isEmpty()) return value;
            return displayFromId(id);
        }

        @NonNull private static String sanitizeItemId(@Nullable String rawId, @Nullable String rawLabel) {
            String fromLabel = idFromTranslation(rawLabel == null ? "" : rawLabel);
            if (!fromLabel.isEmpty()) return fromLabel;
            String id = rawId == null ? "" : rawId.trim();
            String extracted = extractResourceLocation(id);
            return extracted.isEmpty() ? id.toLowerCase(Locale.ROOT) : extracted;
        }

        @NonNull private static String idFromTranslation(@NonNull String raw) {
            String key = extractBetween(raw, "key='", "'");
            if (key.isEmpty()) key = extractBetween(raw, "key=\"", "\"");
            if (key.startsWith("item.minecraft.")) return "minecraft:" + key.substring("item.minecraft.".length());
            if (key.startsWith("block.minecraft.")) return "minecraft:" + key.substring("block.minecraft.".length());
            if (key.startsWith("item.")) {
                String rest = key.substring(5); int dot = rest.indexOf('.');
                if (dot > 0 && dot + 1 < rest.length()) return rest.substring(0, dot) + ":" + rest.substring(dot + 1);
            }
            if (key.startsWith("block.")) {
                String rest = key.substring(6); int dot = rest.indexOf('.');
                if (dot > 0 && dot + 1 < rest.length()) return rest.substring(0, dot) + ":" + rest.substring(dot + 1);
            }
            return "";
        }

        @NonNull private static String displayFromId(@Nullable String rawId) {
            String id = extractResourceLocation(rawId == null ? "" : rawId);
            if (id.isEmpty()) return "";
            int colon = id.lastIndexOf(':');
            String name = colon >= 0 ? id.substring(colon + 1) : id;
            String[] parts = name.replace('_', ' ').trim().split("\\s+");
            StringBuilder out = new StringBuilder();
            for (String part : parts) {
                if (part.length() == 0) continue;
                if (out.length() > 0) out.append(' ');
                out.append(Character.toUpperCase(part.charAt(0))).append(part.length() > 1 ? part.substring(1) : "");
            }
            return out.toString();
        }

        @NonNull private static String extractResourceLocation(@NonNull String raw) {
            int colon = raw.indexOf(':');
            if (colon <= 0) return "";
            int start = colon - 1;
            while (start >= 0) {
                char c = raw.charAt(start);
                boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.';
                if (!ok) break;
                start--;
            }
            int end = colon + 1;
            while (end < raw.length()) {
                char c = raw.charAt(end);
                boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.' || c == '/';
                if (!ok) break;
                end++;
            }
            return raw.substring(start + 1, end).toLowerCase(Locale.ROOT).replace('/', '_');
        }

        @NonNull private static String extractBetween(@NonNull String text, @NonNull String start, @NonNull String end) {
            int a = text.indexOf(start);
            if (a < 0) return "";
            a += start.length();
            int b = text.indexOf(end, a);
            if (b < 0) return "";
            return text.substring(a, b);
        }
    }

    private static final class CoordinateState {
        final long readAtMs;
        final long fileModifiedMs;
        final boolean enabled;
        final int x;
        final int y;
        final int z;
        final boolean valid;

        private CoordinateState(long readAtMs, long fileModifiedMs, boolean enabled, int x, int y, int z, boolean valid) {
            this.readAtMs = readAtMs;
            this.fileModifiedMs = fileModifiedMs;
            this.enabled = enabled;
            this.x = x;
            this.y = y;
            this.z = z;
            this.valid = valid;
        }

        static CoordinateState empty() {
            return new CoordinateState(0L, 0L, false, 0, 0, 0, false);
        }

        boolean isFresh() {
            return valid && fileModifiedMs > 0L && SystemClock.uptimeMillis() - readAtMs <= HUD_STALE_MS;
        }

        @Nullable
        private static File resolveReadableStateFile(@NonNull File preferred) {
            ArrayList<File> candidates = new ArrayList<>();
            addCandidate(candidates, preferred);
            File parent = preferred.getParentFile();
            if (parent != null) {
                addCandidate(candidates, new File(parent, "droidbridge_dual_screen_coordinates.json"));
                addCandidate(candidates, new File(new File(parent, "game"), "droidbridge_dual_screen_coordinates.json"));
                File grand = parent.getParentFile();
                if (grand != null) {
                    addCandidate(candidates, new File(grand, "droidbridge_dual_screen_coordinates.json"));
                    addCandidate(candidates, new File(new File(grand, "game"), "droidbridge_dual_screen_coordinates.json"));
                }
            }
            File best = null;
            long bestModified = Long.MIN_VALUE;
            for (File candidate : candidates) {
                if (candidate == null || !candidate.isFile()) continue;
                long modified = candidate.lastModified();
                if (best == null || modified > bestModified) {
                    best = candidate;
                    bestModified = modified;
                }
            }
            return best;
        }

        private static void addCandidate(@NonNull ArrayList<File> candidates, @Nullable File file) {
            if (file == null) return;
            String path = file.getAbsolutePath();
            for (File candidate : candidates) {
                if (path.equals(candidate.getAbsolutePath())) return;
            }
            candidates.add(file);
        }

        static CoordinateState read(@NonNull File file) {
            long now = SystemClock.uptimeMillis();
            try {
                File actual = resolveReadableStateFile(file);
                if (actual == null || !actual.isFile()) return empty();
                byte[] bytes = HudState.readAllBytes(actual);
                JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                return new CoordinateState(
                        now,
                        actual.lastModified(),
                        root.optBoolean("enabled", false),
                        root.optInt("x", 0),
                        root.optInt("y", 0),
                        root.optInt("z", 0),
                        true
                );
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to read dual-screen coordinate state from " + file.getAbsolutePath(), throwable);
                return empty();
            }
        }
    }

    private static final class HudState {
        final long readAtMs;
        final long fileModifiedMs;
        final float health;
        final float maxHealth;
        final float absorption;
        final int armor;
        final int food;
        final int air;
        final int maxAir;
        final float experienceProgress;
        final int experienceLevel;
        final int selectedSlot;
        @Nullable final String screenTitle;
        @NonNull final HudItem[] items;
        @NonNull final HudItem offhand;
        @NonNull final String mainArm;
        final boolean mapEnabled;
        final int mapSize;
        final float mapPlayerX;
        final float mapPlayerY;
        final float mapRotation;
        @NonNull final String mapData;
        final boolean valid;

        private HudState(long readAtMs, long fileModifiedMs, float health, float maxHealth,
                         float absorption, int armor, int food, int air, int maxAir,
                         float experienceProgress, int experienceLevel, int selectedSlot,
                         @Nullable String screenTitle, @NonNull HudItem[] items,
                         @NonNull HudItem offhand, @NonNull String mainArm,
                         boolean mapEnabled, int mapSize, float mapPlayerX, float mapPlayerY,
                         float mapRotation, @NonNull String mapData, boolean valid) {
            this.readAtMs = readAtMs;
            this.fileModifiedMs = fileModifiedMs;
            this.health = health;
            this.maxHealth = maxHealth;
            this.absorption = absorption;
            this.armor = armor;
            this.food = food;
            this.air = air;
            this.maxAir = maxAir;
            this.experienceProgress = experienceProgress;
            this.experienceLevel = experienceLevel;
            this.selectedSlot = selectedSlot;
            this.screenTitle = screenTitle;
            this.items = items;
            this.offhand = offhand;
            this.mainArm = mainArm;
            this.mapEnabled = mapEnabled;
            this.mapSize = mapSize;
            this.mapPlayerX = mapPlayerX;
            this.mapPlayerY = mapPlayerY;
            this.mapRotation = mapRotation;
            this.mapData = mapData;
            this.valid = valid;
        }

        static HudState empty() {
            HudItem[] items = new HudItem[SLOT_COUNT];
            for (int i = 0; i < items.length; i++) items[i] = HudItem.empty();
            return new HudState(0L, 0L, -1f, -1f, 0f, -1, -1, -1, -1, 0f, -1, -1, null, items,
                    HudItem.empty(), "right", false, MAP_PIXEL_SIZE, 64f, 64f, 0f, "", false);
        }

        static HudState fallback() {
            HudItem[] items = new HudItem[SLOT_COUNT];
            for (int i = 0; i < items.length; i++) items[i] = HudItem.empty();
            return new HudState(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), 20f, 20f, 0f, 0, 20, -1, -1, 0f, -1, 0, null, items,
                    HudItem.empty(), "right", false, MAP_PIXEL_SIZE, 64f, 64f, 0f, "", true);
        }

        boolean isFresh() {
            // readAtMs is updated each time we successfully parse a state file.  Do not hide the
            // bottom HUD just because Android/Java file timestamps drift for a moment; if the mod
            // has written valid state, keep drawing it while the poller keeps seeing the file.
            return valid && fileModifiedMs > 0L && SystemClock.uptimeMillis() - readAtMs <= HUD_STALE_MS;
        }

        boolean shouldDrawLiveHud() {
            if (!isFresh()) return false;
            return maxHealth > 0f && health >= 0f && food >= 0 && selectedSlot >= 0 && selectedSlot < SLOT_COUNT;
        }

        @NonNull HudItem item(int slot) {
            if (slot < 0 || slot >= items.length || items[slot] == null) return HudItem.empty();
            return items[slot];
        }

        @NonNull String itemLabel(int slot) { return item(slot).displayLabel(); }

        @NonNull
        private static byte[] readAllBytes(@NonNull File file) throws Exception {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(128, (int) Math.min(file.length(), 64 * 1024)));
            FileInputStream in = new FileInputStream(file);
            try {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                return out.toByteArray();
            } finally {
                try { in.close(); } catch (Throwable ignored) {}
            }
        }


        @Nullable
        private static File resolveReadableStateFile(@NonNull File preferred) {
            File best = null;
            long bestModified = Long.MIN_VALUE;

            ArrayList<File> candidates = new ArrayList<>();
            addCandidate(candidates, preferred);

            File parent = preferred.getParentFile();
            if (parent != null) {
                addCandidate(candidates, new File(parent, "droidbridge_dual_screen_state.json"));
                addCandidate(candidates, new File(new File(parent, "game"), "droidbridge_dual_screen_state.json"));
                File grand = parent.getParentFile();
                if (grand != null) {
                    addCandidate(candidates, new File(grand, "droidbridge_dual_screen_state.json"));
                    addCandidate(candidates, new File(new File(grand, "game"), "droidbridge_dual_screen_state.json"));
                }
            }

            for (int i = 0; i < candidates.size(); i++) {
                File candidate = candidates.get(i);
                if (candidate == null || !candidate.isFile()) continue;
                long modified = candidate.lastModified();
                if (best == null || modified > bestModified) {
                    best = candidate;
                    bestModified = modified;
                }
            }
            return best;
        }

        private static void addCandidate(@NonNull ArrayList<File> candidates, @Nullable File file) {
            if (file == null) return;
            String path = file.getAbsolutePath();
            for (int i = 0; i < candidates.size(); i++) {
                if (path.equals(candidates.get(i).getAbsolutePath())) return;
            }
            candidates.add(file);
        }

        static HudState read(@NonNull File file) {
            long now = SystemClock.uptimeMillis();
            try {
                File actual = resolveReadableStateFile(file);
                if (actual == null || !actual.isFile()) return empty();
                long modified = actual.lastModified();
                byte[] bytes = readAllBytes(actual);
                JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                HudItem[] items = new HudItem[SLOT_COUNT];
                for (int i = 0; i < items.length; i++) items[i] = HudItem.empty();
                JSONArray hotbar = root.optJSONArray("hotbar");
                if (hotbar != null) {
                    for (int i = 0; i < Math.min(SLOT_COUNT, hotbar.length()); i++) {
                        Object raw = hotbar.opt(i);
                        if (raw instanceof JSONObject) {
                            JSONObject object = (JSONObject) raw;
                            items[i] = new HudItem(
                                    object.optString("label", ""),
                                    object.optString("id", ""),
                                    object.optString("iconPath", ""),
                                    object.optString("iconMode", ""),
                                    object.optInt("count", object.optBoolean("empty", false) ? 0 : 1),
                                    object.optInt("damage", -1),
                                    object.optInt("maxDamage", -1)
                            );
                        } else {
                            String label = hotbar.optString(i, "");
                            items[i] = new HudItem(label, "", "", "", label.trim().isEmpty() ? 0 : 1, -1, -1);
                        }
                    }
                }
                HudItem offhand = HudItem.empty();
                JSONObject offhandObject = root.optJSONObject("offhand");
                if (offhandObject != null) {
                    offhand = new HudItem(
                            offhandObject.optString("label", ""),
                            offhandObject.optString("id", ""),
                            offhandObject.optString("iconPath", ""),
                            offhandObject.optString("iconMode", ""),
                            offhandObject.optInt("count", offhandObject.optBoolean("empty", false) ? 0 : 1),
                            offhandObject.optInt("damage", -1),
                            offhandObject.optInt("maxDamage", -1)
                    );
                }
                return new HudState(
                        now,
                        modified,
                        (float) root.optDouble("health", -1d),
                        (float) root.optDouble("maxHealth", -1d),
                        (float) root.optDouble("absorption", 0d),
                        root.optInt("armor", -1),
                        root.optInt("food", -1),
                        root.optInt("air", -1),
                        root.optInt("maxAir", -1),
                        (float) root.optDouble("experienceProgress", root.optDouble("xpProgress", 0d)),
                        root.optInt("experienceLevel", root.optInt("xpLevel", -1)),
                        root.optInt("selectedSlot", -1),
                        root.optString("screen", ""),
                        items,
                        offhand,
                        root.optString("mainArm", "right"),
                        root.optBoolean("mapEnabled", false),
                        root.optInt("mapSize", MAP_PIXEL_SIZE),
                        (float) root.optDouble("mapPlayerX", MAP_PIXEL_SIZE * 0.5d),
                        (float) root.optDouble("mapPlayerY", MAP_PIXEL_SIZE * 0.5d),
                        (float) root.optDouble("mapRotation", 0d),
                        root.optString("mapData", ""),
                        true
                );
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to read dual-screen HUD state from " + file.getAbsolutePath(), throwable);
                return empty();
            }
        }
    }

}
