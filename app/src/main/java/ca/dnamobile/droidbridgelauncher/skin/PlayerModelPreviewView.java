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

package ca.dnamobile.droidbridgelauncher.skin;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Lightweight interactive Minecraft paper-doll preview.
 *
 * This intentionally stays on Canvas instead of GL so the settings screen remains cheap.
 * It uses the canonical Minecraft 64x64 UV layout, scales UVs for HD skins, handles
 * legacy 64x32 skins, draws the second layer only when it really contains transparent
 * layer data, and supports slim 3px-arm skins.
 */
public final class PlayerModelPreviewView extends View {
    private static final int FACE_FRONT = 0;
    private static final int FACE_RIGHT = 1;
    private static final int FACE_BACK = 2;
    private static final int FACE_LEFT = 3;

    private static final int PART_RIGHT = 0;
    private static final int PART_LEFT = 1;

    /**
     * Bounds/shadow boxes are useful while debugging UV placement, but they look like
     * wires around transparent skins. Keep them off for normal player previews.
     */
    private static final boolean DRAW_TEXTURE_PART_BOUNDS = false;
    private static final boolean DRAW_TEXTURE_PART_SHADOWS = false;

    private final Paint pixelPaint = new Paint(Paint.DITHER_FLAG);
    private final Paint uiPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);

    @Nullable private Bitmap skin;
    @Nullable private Bitmap cape;
    @Nullable private String skinUrl;
    @Nullable private String capeUrl;
    private int skinGeneration;
    private int capeGeneration;

    private float yawDegrees;
    private float downX;
    private float downYaw;
    private boolean dragging;
    private boolean userRotated;

    private boolean slimArms;
    private boolean slimArmsKnown;
    private boolean animationRunning;

    private final Runnable animationTick = new Runnable() {
        @Override
        public void run() {
            if (!animationRunning) return;
            if (isShown() && getWindowVisibility() == VISIBLE && hasSkin()) {
                invalidate();
            }
            postOnAnimation(this);
        }
    };

    public PlayerModelPreviewView(Context context) {
        super(context);
        init();
    }

    public PlayerModelPreviewView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public PlayerModelPreviewView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        setFocusable(true);
        setClickable(true);
        pixelPaint.setAntiAlias(false);
        pixelPaint.setFilterBitmap(false);
        pixelPaint.setDither(false);
        uiPaint.setFilterBitmap(false);
    }

    public void setSkinUrl(@Nullable String url) {
        String cleanUrl = normalizeTextureUrl(url);
        if (sameNullable(cleanUrl, skinUrl)) return;

        skinUrl = cleanUrl;
        skin = null;
        slimArms = false;
        slimArmsKnown = false;
        if (!userRotated) yawDegrees = 0f;
        invalidate();

        final int generation = ++skinGeneration;
        if (cleanUrl == null) return;

        Thread thread = new Thread(() -> {
            Bitmap loaded = downloadBitmap(cleanUrl);
            post(() -> {
                if (generation != skinGeneration) {
                    if (loaded != null && !loaded.isRecycled()) loaded.recycle();
                    return;
                }
                skin = loaded;
                slimArmsKnown = false;
                if (loaded != null && !loaded.isRecycled()) {
                    slimArms = detectLikelySlimArms(loaded);
                    slimArmsKnown = true;
                }
                invalidate();
            });
        }, "DroidBridgePlayerPreviewSkin");
        thread.setDaemon(true);
        thread.start();
    }

    public void setCapeUrl(@Nullable String url) {
        String cleanUrl = normalizeTextureUrl(url);
        if (sameNullable(cleanUrl, capeUrl)) return;

        capeUrl = cleanUrl;
        cape = null;
        if (!userRotated) yawDegrees = 0f;
        invalidate();

        final int generation = ++capeGeneration;
        if (cleanUrl == null) return;

        Thread thread = new Thread(() -> {
            Bitmap loaded = downloadBitmap(cleanUrl);
            post(() -> {
                if (generation != capeGeneration) {
                    if (loaded != null && !loaded.isRecycled()) loaded.recycle();
                    return;
                }
                cape = loaded;
                invalidate();
            });
        }, "DroidBridgePlayerPreviewCape");
        thread.setDaemon(true);
        thread.start();
    }

    @Nullable
    private static String normalizeTextureUrl(@Nullable String rawUrl) {
        if (rawUrl == null) return null;
        String value = rawUrl.trim();
        if (value.isEmpty()) return null;
        if (value.startsWith("//")) return "https:" + value;
        if (value.startsWith("http://textures.minecraft.net/")) {
            return "https://textures.minecraft.net/" + value.substring("http://textures.minecraft.net/".length());
        }
        return value;
    }

    private static boolean sameNullable(@Nullable String left, @Nullable String right) {
        if (left == null) return right == null;
        return left.equals(right);
    }

    @Nullable
    private static Bitmap downloadBitmap(@NonNull String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(15000);
            connection.setUseCaches(true);
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) return null;
            try (InputStream input = connection.getInputStream()) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                return BitmapFactory.decodeStream(input, null, options);
            }
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        animationRunning = true;
        postOnAnimation(animationTick);
    }

    @Override
    protected void onDetachedFromWindow() {
        animationRunning = false;
        removeCallbacks(animationTick);
        super.onDetachedFromWindow();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                downX = event.getX();
                downYaw = yawDegrees;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) return true;
                userRotated = true;
                yawDegrees = normalizeYaw(downYaw + ((event.getX() - downX) * 1.15f));
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                performClick();
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private static float normalizeYaw(float yaw) {
        float normalized = yaw % 360f;
        if (normalized < 0f) normalized += 360f;
        return normalized;
    }

    private int viewDirection() {
        return ((int) Math.floor((normalizeYaw(yawDegrees) + 45f) / 90f)) & 3;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float width = getWidth();
        float height = getHeight();
        if (width <= 0 || height <= 0) return;

        drawBackground(canvas, width, height);

        int direction = viewDirection();
        boolean side = direction == FACE_RIGHT || direction == FACE_LEFT;
        boolean slim = isSlimArms();
        float armW = slim ? 12f : 16f;
        // Front/back paper-doll width is exactly: arm + body + arm.
        // The previous spacing added an extra 16px column before the torso, which
        // made slim skins look like the arms were detached or half missing.
        float modelWidth = side ? 62f : (armW + 32f + armW);
        float modelHeight = 160f;
        float scale = Math.min((width - dp(18)) / modelWidth, (height - dp(30)) / modelHeight);
        float left = (width - modelWidth * scale) / 2f;
        float top = (height - modelHeight * scale) / 2f + dp(2);

        drawModel(canvas, left, top, scale, direction, slim);
        drawRotationHint(canvas, width, height, direction);
    }

    private void drawBackground(@NonNull Canvas canvas, float width, float height) {
        uiPaint.setStyle(Paint.Style.FILL);
        uiPaint.setColor(Color.argb(32, 255, 255, 255));
        RectF bg = new RectF(0, 0, width, height);
        canvas.drawRoundRect(bg, dp(20), dp(20), uiPaint);

        uiPaint.setStyle(Paint.Style.STROKE);
        uiPaint.setStrokeWidth(Math.max(1f, dp(1)));
        uiPaint.setColor(Color.argb(72, 255, 255, 255));
        canvas.drawRoundRect(bg.left + 1, bg.top + 1, bg.right - 1, bg.bottom - 1, dp(20), dp(20), uiPaint);
    }

    private void drawRotationHint(@NonNull Canvas canvas, float width, float height, int direction) {
        String label;
        switch (direction) {
            case FACE_RIGHT:
                label = "Right";
                break;
            case FACE_BACK:
                label = hasCape() ? "Back • cape" : "Back";
                break;
            case FACE_LEFT:
                label = "Left";
                break;
            case FACE_FRONT:
            default:
                label = "Front";
                break;
        }

        uiPaint.setStyle(Paint.Style.FILL);
        uiPaint.setColor(Color.argb(145, 255, 255, 255));
        uiPaint.setTextSize(dp(9));
        uiPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(label + " • drag", width / 2f, height - dp(9), uiPaint);
    }

    private boolean hasCape() {
        Bitmap bitmap = cape;
        return bitmap != null && !bitmap.isRecycled();
    }

    private boolean isSlimArms() {
        if (slimArmsKnown) return slimArms;
        Bitmap bitmap = skin;
        if (bitmap == null || bitmap.isRecycled()) return false;
        slimArms = detectLikelySlimArms(bitmap);
        slimArmsKnown = true;
        return slimArms;
    }

    private void drawModel(@NonNull Canvas canvas, float left, float top, float scale, int direction, boolean slim) {
        if (direction == FACE_RIGHT || direction == FACE_LEFT) {
            drawSideModel(canvas, left, top, scale, direction == FACE_RIGHT, slim);
        } else {
            drawFrontBackModel(canvas, left, top, scale, direction == FACE_BACK, slim);
        }
    }

    private void drawFrontBackModel(@NonNull Canvas canvas, float left, float top, float scale, boolean back, boolean slim) {
        float armW = slim ? 12f : 16f;
        // Keep the model packed the same way Minecraft's paper doll does:
        // left arm touches torso, torso touches right arm. No phantom spacer.
        float bodyLeft = left + armW * scale;
        float headLeft = bodyLeft;
        float screenLeftArm = left;
        float screenRightArm = bodyLeft + 32f * scale;
        float screenLeftLeg = bodyLeft;
        float screenRightLeg = bodyLeft + 16f * scale;

        int screenLeftPart = back ? PART_LEFT : PART_RIGHT;
        int screenRightPart = back ? PART_RIGHT : PART_LEFT;
        int face = back ? FACE_BACK : FACE_FRONT;

        RectF leftArmRect = new RectF(screenLeftArm, top + 50f * scale, screenLeftArm + armW * scale, top + 104f * scale);
        RectF rightArmRect = new RectF(screenRightArm, top + 50f * scale, screenRightArm + armW * scale, top + 104f * scale);
        RectF leftLegRect = new RectF(screenLeftLeg, top + 100f * scale, screenLeftLeg + 16f * scale, top + 148f * scale);
        RectF rightLegRect = new RectF(screenRightLeg, top + 100f * scale, screenRightLeg + 16f * scale, top + 148f * scale);
        RectF bodyRect = new RectF(bodyLeft, top + 50f * scale, bodyLeft + 32f * scale, top + 100f * scale);
        // Minecraft's paper-doll head touches the torso.  The old 12..44 placement
        // left a visible six-pixel neck gap against the torso starting at 50.
        RectF headRect = new RectF(headLeft, top + 18f * scale, headLeft + 32f * scale, top + 50f * scale);

        drawLeg(canvas, leftLegRect, screenLeftPart, face);
        drawLeg(canvas, rightLegRect, screenRightPart, face);
        drawBody(canvas, bodyRect, face);

        if (back && hasCape()) {
            drawCapeBack(canvas, new RectF(
                    bodyRect.left + 1.5f * scale,
                    top + 48f * scale,
                    bodyRect.right - 1.5f * scale,
                    top + 148f * scale
            ));
        }

        float swing = idleArmSwingDegrees();
        drawArmAnimated(canvas, leftArmRect, screenLeftPart, face, slim, swing);
        drawArmAnimated(canvas, rightArmRect, screenRightPart, face, slim, -swing);
        drawHead(canvas, headRect, face);
    }

    private void drawSideModel(@NonNull Canvas canvas, float left, float top, float scale, boolean rightSide, boolean slim) {
        float center = left + 31f * scale;
        int face = rightSide ? FACE_RIGHT : FACE_LEFT;
        int visiblePart = rightSide ? PART_RIGHT : PART_LEFT;
        float armW = slim ? 12f : 16f;

        if (hasCape()) {
            drawCapeSide(canvas, new RectF(
                    center + (rightSide ? -14f : 7f) * scale,
                    top + 48f * scale,
                    center + (rightSide ? -6f : 15f) * scale,
                    top + 148f * scale
            ), rightSide);
        }

        drawLeg(canvas, new RectF(center - 8f * scale, top + 100f * scale, center + 8f * scale, top + 148f * scale), visiblePart, face);
        drawBody(canvas, new RectF(center - 8f * scale, top + 50f * scale, center + 8f * scale, top + 100f * scale), face);
        RectF armRect = new RectF(center - (armW + 2f) * scale, top + 51f * scale, center - 2f * scale, top + 105f * scale);
        drawArmAnimated(canvas, armRect, visiblePart, face, slim, rightSide ? idleArmSwingDegrees() : -idleArmSwingDegrees());
        drawHead(canvas, new RectF(center - 16f * scale, top + 18f * scale, center + 16f * scale, top + 50f * scale), face);
    }

    private float idleArmSwingDegrees() {
        if (dragging) return 0f;
        double seconds = SystemClock.uptimeMillis() / 1000.0;
        return (float) Math.sin(seconds * Math.PI * 2.0 * 0.75) * 4.0f;
    }

    private void drawArmAnimated(
            @NonNull Canvas canvas,
            @NonNull RectF dst,
            int part,
            int face,
            boolean slim,
            float degrees
    ) {
        if (Math.abs(degrees) < 0.1f) {
            drawArm(canvas, dst, part, face, slim);
            return;
        }

        canvas.save();
        canvas.rotate(degrees, dst.centerX(), dst.top + Math.max(1f, dp(1f)));
        drawArm(canvas, dst, part, face, slim);
        canvas.restore();
    }

    private void drawHead(@NonNull Canvas canvas, @NonNull RectF dst, int face) {
        int x;
        switch (face) {
            case FACE_RIGHT: x = 0; break;
            case FACE_BACK: x = 24; break;
            case FACE_LEFT: x = 16; break;
            case FACE_FRONT:
            default: x = 8; break;
        }
        boolean drawn = drawSkinPart(canvas, new Uv(x, 8, 8, 8), new Uv(x + 32, 8, 8, 8), dst, true);
        if (!drawn) drawFallbackHead(canvas, dst, face);
    }

    private void drawBody(@NonNull Canvas canvas, @NonNull RectF dst, int face) {
        int x;
        int w = (face == FACE_RIGHT || face == FACE_LEFT) ? 4 : 8;
        switch (face) {
            case FACE_RIGHT: x = 16; break;
            case FACE_BACK: x = 32; break;
            case FACE_LEFT: x = 28; break;
            case FACE_FRONT:
            default: x = 20; break;
        }
        boolean drawn = drawSkinPart(canvas, new Uv(x, 20, w, 12), new Uv(x, 36, w, 12), dst, false);
        if (!drawn) drawFallbackRect(canvas, dst, Color.rgb(68, 126, 164), true);
    }

    private void drawArm(@NonNull Canvas canvas, @NonNull RectF dst, int part, int face, boolean slim) {
        boolean left = part == PART_LEFT;
        boolean oldSkin = isOldSkin();
        Uv base = left && !oldSkin ? leftArmUv(face, false, slim) : rightArmUv(face, false, slim);
        Uv overlay = left && !oldSkin ? leftArmUv(face, true, slim) : rightArmUv(face, true, slim);
        boolean drawn = drawSkinPart(canvas, base, overlay, dst, false);
        if (!drawn) drawFallbackArm(canvas, dst);
    }

    private void drawLeg(@NonNull Canvas canvas, @NonNull RectF dst, int part, int face) {
        boolean left = part == PART_LEFT;
        boolean oldSkin = isOldSkin();
        Uv base = left && !oldSkin ? leftLegUv(face, false) : rightLegUv(face, false);
        Uv overlay = left && !oldSkin ? leftLegUv(face, true) : rightLegUv(face, true);
        boolean drawn = drawSkinPart(canvas, base, overlay, dst, false);
        // Do not draw a solid fallback for fully transparent skins. Some novelty skins
        // intentionally use transparent base pixels plus an outer layer for tiny legs.
        if (!drawn && !hasSkin()) drawFallbackRect(canvas, dst, Color.rgb(67, 76, 164), true);
    }

    private boolean isOldSkin() {
        Bitmap bitmap = skin;
        return bitmap == null || bitmap.isRecycled() || bitmap.getHeight() < 64;
    }

    private boolean hasSkin() {
        Bitmap bitmap = skin;
        return bitmap != null && !bitmap.isRecycled();
    }

    private boolean drawSkinPart(
            @NonNull Canvas canvas,
            @NonNull Uv base,
            @NonNull Uv overlay,
            @NonNull RectF dst,
            boolean expandOverlay
    ) {
        Bitmap bitmap = skin;
        if (bitmap == null || bitmap.isRecycled()) return false;
        Rect baseSrc = skinSourceRect(bitmap, base.x, base.y, base.w, base.h);
        Rect overlaySrc = skinSourceRect(bitmap, overlay.x, overlay.y, overlay.w, overlay.h);
        boolean drewAny = false;

        if (DRAW_TEXTURE_PART_SHADOWS) drawShadow(canvas, dst);
        if (baseSrc != null && hasVisiblePixels(bitmap, baseSrc)) {
            canvas.drawBitmap(bitmap, baseSrc, snapped(dst), pixelPaint);
            drewAny = true;
        }

        if (overlaySrc != null && hasUsefulOverlay(bitmap, overlaySrc)) {
            RectF overlayDst = expandOverlay ? expanded(dst, 1.07f) : dst;
            canvas.drawBitmap(bitmap, overlaySrc, snapped(overlayDst), pixelPaint);
            drewAny = true;
        }

        if (drewAny && DRAW_TEXTURE_PART_BOUNDS) drawOutline(canvas, dst);
        return drewAny;
    }

    @NonNull
    private static Uv rightArmUv(int face, boolean overlay, boolean slim) {
        int y = overlay ? 36 : 20;
        if (slim) {
            switch (face) {
                case FACE_RIGHT: return new Uv(40, y, 4, 12);
                case FACE_BACK: return new Uv(51, y, 3, 12);
                case FACE_LEFT: return new Uv(47, y, 4, 12);
                case FACE_FRONT:
                default: return new Uv(44, y, 3, 12);
            }
        }
        switch (face) {
            case FACE_RIGHT: return new Uv(40, y, 4, 12);
            case FACE_BACK: return new Uv(52, y, 4, 12);
            case FACE_LEFT: return new Uv(48, y, 4, 12);
            case FACE_FRONT:
            default: return new Uv(44, y, 4, 12);
        }
    }

    @NonNull
    private static Uv leftArmUv(int face, boolean overlay, boolean slim) {
        int y = 52;
        int start = overlay ? 48 : 32;
        if (slim) {
            switch (face) {
                case FACE_RIGHT: return new Uv(start, y, 4, 12);
                case FACE_BACK: return new Uv(start + 11, y, 3, 12);
                case FACE_LEFT: return new Uv(start + 7, y, 4, 12);
                case FACE_FRONT:
                default: return new Uv(start + 4, y, 3, 12);
            }
        }
        switch (face) {
            case FACE_RIGHT: return new Uv(start, y, 4, 12);
            case FACE_BACK: return new Uv(start + 12, y, 4, 12);
            case FACE_LEFT: return new Uv(start + 8, y, 4, 12);
            case FACE_FRONT:
            default: return new Uv(start + 4, y, 4, 12);
        }
    }

    @NonNull
    private static Uv rightLegUv(int face, boolean overlay) {
        int y = overlay ? 36 : 20;
        switch (face) {
            case FACE_RIGHT: return new Uv(0, y, 4, 12);
            case FACE_BACK: return new Uv(12, y, 4, 12);
            case FACE_LEFT: return new Uv(8, y, 4, 12);
            case FACE_FRONT:
            default: return new Uv(4, y, 4, 12);
        }
    }

    @NonNull
    private static Uv leftLegUv(int face, boolean overlay) {
        int y = 52;
        int start = overlay ? 0 : 16;
        switch (face) {
            case FACE_RIGHT: return new Uv(start, y, 4, 12);
            case FACE_BACK: return new Uv(start + 12, y, 4, 12);
            case FACE_LEFT: return new Uv(start + 8, y, 4, 12);
            case FACE_FRONT:
            default: return new Uv(start + 4, y, 4, 12);
        }
    }

    private void drawCapeBack(@NonNull Canvas canvas, @NonNull RectF dst) {
        if (DRAW_TEXTURE_PART_SHADOWS) drawShadow(canvas, dst);
        boolean drewCape = drawCapeRegion(canvas, 1, 1, 10, 16, dst);
        if (!drewCape) {
            drawCapePlaceholder(canvas, dst);
            drawOutline(canvas, dst);
        } else if (DRAW_TEXTURE_PART_BOUNDS) {
            drawOutline(canvas, dst);
        }
    }

    private void drawCapeSide(@NonNull Canvas canvas, @NonNull RectF dst, boolean rightSide) {
        if (DRAW_TEXTURE_PART_SHADOWS) drawShadow(canvas, dst);
        boolean drewCape = drawCapeRegion(canvas, rightSide ? 0 : 11, 1, 1, 16, dst);
        if (!drewCape) {
            drewCape = drawCapeRegion(canvas, rightSide ? 1 : 10, 1, 1, 16, dst);
        }
        if (drewCape) {
            if (DRAW_TEXTURE_PART_BOUNDS) drawOutline(canvas, dst);
        } else {
            drawCapePlaceholder(canvas, dst);
            drawOutline(canvas, dst);
        }
    }

    private void drawCapePlaceholder(@NonNull Canvas canvas, @NonNull RectF dst) {
        uiPaint.setStyle(Paint.Style.FILL);
        uiPaint.setColor(Color.rgb(76, 54, 45));
        canvas.drawRect(snapped(dst), uiPaint);
        uiPaint.setColor(Color.argb(55, 255, 255, 255));
        canvas.drawRect(dst.left + dst.width() * 0.10f, dst.top + dst.height() * 0.06f, dst.right - dst.width() * 0.10f, dst.top + dst.height() * 0.16f, uiPaint);
        uiPaint.setColor(Color.argb(55, 0, 0, 0));
        canvas.drawRect(dst.right - dst.width() * 0.18f, dst.top, dst.right, dst.bottom, uiPaint);
    }

    private boolean drawCapeRegion(@NonNull Canvas canvas, int x, int y, int w, int h, @NonNull RectF dst) {
        Bitmap bitmap = cape;
        if (bitmap == null || bitmap.isRecycled()) return false;
        int bw = bitmap.getWidth();
        int bh = bitmap.getHeight();
        if (bw < 12 || bh < 17) return false;

        int sx = Math.round((x / 64f) * bw);
        int sy = Math.round((y / 32f) * bh);
        int sw = Math.max(1, Math.round((w / 64f) * bw));
        int sh = Math.max(1, Math.round((h / 32f) * bh));
        if (sx < 0 || sy < 0 || sx + sw > bw || sy + sh > bh) return false;

        Rect src = new Rect(sx, sy, sx + sw, sy + sh);
        if (!hasVisiblePixels(bitmap, src)) return false;
        canvas.drawBitmap(bitmap, src, snapped(dst), pixelPaint);
        return true;
    }

    @Nullable
    private static Rect skinSourceRect(@NonNull Bitmap bitmap, int x, int y, int w, int h) {
        int bw = bitmap.getWidth();
        int bh = bitmap.getHeight();
        if (bw < 64 || bh < 32) return null;

        int canonicalHeight = (bh * 2 == bw) ? 32 : 64;
        if (x < 0 || y < 0 || x + w > 64 || y + h > canonicalHeight) return null;

        float scaleX = bw / 64f;
        float scaleY = bh / (float) canonicalHeight;
        int sx = Math.max(0, Math.min(bw - 1, Math.round(x * scaleX)));
        int sy = Math.max(0, Math.min(bh - 1, Math.round(y * scaleY)));
        int sw = Math.max(1, Math.round(w * scaleX));
        int sh = Math.max(1, Math.round(h * scaleY));
        int right = Math.max(sx + 1, Math.min(bw, sx + sw));
        int bottom = Math.max(sy + 1, Math.min(bh, sy + sh));
        return new Rect(sx, sy, right, bottom);
    }

    private static boolean hasVisiblePixels(@NonNull Bitmap bitmap, @NonNull Rect src) {
        int stepX = Math.max(1, src.width() / 5);
        int stepY = Math.max(1, src.height() / 5);
        for (int y = src.top; y < src.bottom; y += stepY) {
            for (int x = src.left; x < src.right; x += stepX) {
                int alpha = (bitmap.getPixel(x, y) >>> 24) & 0xFF;
                if (alpha > 8) return true;
            }
        }
        return false;
    }

    private static boolean hasUsefulOverlay(@NonNull Bitmap bitmap, @NonNull Rect src) {
        if (bitmap.getHeight() < 64) return false;
        if (!bitmap.hasAlpha()) return false;

        int visible = 0;
        int transparent = 0;
        int total = 0;
        int stepX = Math.max(1, src.width() / 6);
        int stepY = Math.max(1, src.height() / 6);

        for (int y = src.top; y < src.bottom; y += stepY) {
            for (int x = src.left; x < src.right; x += stepX) {
                int alpha = (bitmap.getPixel(x, y) >>> 24) & 0xFF;
                total++;
                if (alpha > 8) visible++;
                if (alpha < 248) transparent++;
            }
        }

        // Need both visible and transparent pixels. A fully opaque outer-layer region is
        // usually PNG padding saved without alpha and should not cover the base skin.
        return total > 0 && visible > 0 && transparent > 0;
    }

    private static boolean detectLikelySlimArms(@NonNull Bitmap bitmap) {
        if (bitmap.getWidth() < 64 || bitmap.getHeight() < 64) return false;
        int rightFront = boundaryDifference(bitmap, new Uv(44, 20, 3, 12), 47);
        int rightBack = boundaryDifference(bitmap, new Uv(51, 20, 3, 12), 54);
        int leftFront = boundaryDifference(bitmap, new Uv(36, 52, 3, 12), 39);
        int leftBack = boundaryDifference(bitmap, new Uv(43, 52, 3, 12), 46);
        int score = 0;
        if (rightFront > 34) score++;
        if (rightBack > 34) score++;
        if (leftFront > 34) score++;
        if (leftBack > 34) score++;
        return score >= 2;
    }

    private static int boundaryDifference(@NonNull Bitmap bitmap, @NonNull Uv region, int outsideCanonicalX) {
        Rect inside = skinSourceRect(bitmap, region.x + region.w - 1, region.y, 1, region.h);
        Rect outside = skinSourceRect(bitmap, outsideCanonicalX, region.y, 1, region.h);
        if (inside == null || outside == null) return 0;
        int samples = Math.min(inside.height(), outside.height());
        if (samples <= 0) return 0;
        int total = 0;
        for (int i = 0; i < samples; i++) {
            int iy = inside.top + i;
            int oy = outside.top + i;
            int c1 = bitmap.getPixel(inside.left, iy);
            int c2 = bitmap.getPixel(outside.left, oy);
            total += colorDistance(c1, c2);
        }
        return total / samples;
    }

    private static int colorDistance(int c1, int c2) {
        int a1 = (c1 >>> 24) & 0xFF;
        int r1 = (c1 >>> 16) & 0xFF;
        int g1 = (c1 >>> 8) & 0xFF;
        int b1 = c1 & 0xFF;
        int a2 = (c2 >>> 24) & 0xFF;
        int r2 = (c2 >>> 16) & 0xFF;
        int g2 = (c2 >>> 8) & 0xFF;
        int b2 = c2 & 0xFF;
        return (Math.abs(a1 - a2) + Math.abs(r1 - r2) + Math.abs(g1 - g2) + Math.abs(b1 - b2)) / 4;
    }

    @NonNull
    private static RectF snapped(@NonNull RectF dst) {
        return new RectF(
                Math.round(dst.left),
                Math.round(dst.top),
                Math.round(dst.right),
                Math.round(dst.bottom)
        );
    }

    private void drawFallbackHead(@NonNull Canvas canvas, @NonNull RectF dst, int face) {
        drawFallbackRect(canvas, dst, Color.rgb(190, 142, 105), true);
        uiPaint.setStyle(Paint.Style.FILL);
        uiPaint.setColor(Color.rgb(74, 47, 35));
        canvas.drawRect(dst.left, dst.top, dst.right, dst.top + dst.height() * 0.22f, uiPaint);
        if (face == FACE_FRONT) {
            uiPaint.setColor(Color.rgb(55, 70, 88));
            canvas.drawRect(dst.left + dst.width() * 0.28f, dst.top + dst.height() * 0.40f, dst.left + dst.width() * 0.38f, dst.top + dst.height() * 0.50f, uiPaint);
            canvas.drawRect(dst.right - dst.width() * 0.38f, dst.top + dst.height() * 0.40f, dst.right - dst.width() * 0.28f, dst.top + dst.height() * 0.50f, uiPaint);
        }
    }

    private void drawFallbackArm(@NonNull Canvas canvas, @NonNull RectF dst) {
        drawFallbackRect(canvas, dst, Color.rgb(68, 126, 164), true);
        uiPaint.setStyle(Paint.Style.FILL);
        uiPaint.setColor(Color.rgb(190, 142, 105));
        canvas.drawRect(dst.left, dst.bottom - dst.height() * 0.22f, dst.right, dst.bottom, uiPaint);
    }

    private void drawFallbackRect(@NonNull Canvas canvas, @NonNull RectF dst, int color, boolean outline) {
        drawShadow(canvas, dst);
        uiPaint.setStyle(Paint.Style.FILL);
        uiPaint.setColor(color);
        canvas.drawRect(snapped(dst), uiPaint);
        if (outline) drawOutline(canvas, dst);
    }

    private void drawShadow(@NonNull Canvas canvas, @NonNull RectF dst) {
        RectF shadow = new RectF(dst);
        shadow.offset(dp(1.2f), dp(1.2f));
        uiPaint.setStyle(Paint.Style.FILL);
        uiPaint.setColor(Color.argb(36, 0, 0, 0));
        canvas.drawRect(snapped(shadow), uiPaint);
    }

    private void drawOutline(@NonNull Canvas canvas, @NonNull RectF dst) {
        uiPaint.setStyle(Paint.Style.STROKE);
        uiPaint.setStrokeWidth(Math.max(1f, dp(0.6f)));
        uiPaint.setColor(Color.argb(90, 0, 0, 0));
        canvas.drawRect(snapped(dst), uiPaint);
    }

    @NonNull
    private static RectF expanded(@NonNull RectF source, float multiplier) {
        float extraW = source.width() * (multiplier - 1f) / 2f;
        float extraH = source.height() * (multiplier - 1f) / 2f;
        return new RectF(source.left - extraW, source.top - extraH, source.right + extraW, source.bottom + extraH);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static final class Uv {
        final int x;
        final int y;
        final int w;
        final int h;

        Uv(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }
}
