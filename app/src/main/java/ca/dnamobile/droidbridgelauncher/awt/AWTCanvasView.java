/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * DroidBridge launcher-side Java AWT/Cacio canvas for executing GUI installer jars.
 */

package ca.dnamobile.droidbridgelauncher.awt;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import ca.dnamobile.droidbridgelauncher.runtime.AWTInputBridge;
import ca.dnamobile.droidbridgelauncher.runtime.utils.JREUtils;

/**
 * Renders Cacio/Caciocavallo's managed AWT screen and sends Android touch input
 * back into the running installer JVM.
 *
 * Important: this must not render by polling from the Android UI thread.
 * DroidBridge previously extended View and called renderAWTScreenFrame() from
 * onDraw/main Handler. On Android 14/15 this could make the Java GUI process
 * abort immediately after JLI_Launch with:
 *
 *   FORTIFY: pthread_mutex_lock called on a destroyed mutex
 *
 * the prior implementation uses a TextureView and renders from a dedicated renderer thread. This
 * class follows that flow while keeping DroidBridge's existing start()/stop()
 * and touch API compatible with DroidBridgeStandaloneJarActivity.
 */
public class AWTCanvasView extends TextureView implements TextureView.SurfaceTextureListener, Runnable {
    private static final long FRAME_SLEEP_MS = 16L;

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private volatile boolean running;
    private volatile boolean destroyed = true;
    private volatile boolean startRequested;

    @Nullable private Thread renderThread;
    @Nullable private Surface surface;
    @Nullable private Bitmap bitmap;

    private int screenWidth;
    private int screenHeight;
    private String statusText = "Waiting for Java AWT frame…";

    public AWTCanvasView(@NonNull Context context) {
        this(context, null);
    }

    public AWTCanvasView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public AWTCanvasView(@NonNull Context context, int screenWidth, int screenHeight) {
        super(context);
        init();
        setManagedScreenSize(screenWidth, screenHeight);
    }

    private void init() {
        setFocusable(true);
        setFocusableInTouchMode(true);
        setOpaque(true);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(28f);
        setSurfaceTextureListener(this);
    }

    public void setManagedScreenSize(int width, int height) {
        screenWidth = Math.max(1, width);
        screenHeight = Math.max(1, height);
        SurfaceTexture texture = getSurfaceTexture();
        if (texture != null) {
            texture.setDefaultBufferSize(screenWidth, screenHeight);
        }
    }

    public void setStatusText(@Nullable String text) {
        statusText = text == null ? "" : text;
    }

    public synchronized void start() {
        startRequested = true;
        if (!destroyed && surface != null && renderThread == null) {
            running = true;
            renderThread = new Thread(this, "DroidBridgeAWTRenderer");
            renderThread.start();
        }
    }

    public synchronized void stop() {
        startRequested = false;
        running = false;
        Thread thread = renderThread;
        renderThread = null;
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
            try {
                thread.join(300L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        releaseBitmap();
    }

    @Override
    public synchronized void onSurfaceTextureAvailable(@NonNull SurfaceTexture texture, int width, int height) {
        int targetWidth = screenWidth > 0 ? screenWidth : Math.max(1, width);
        int targetHeight = screenHeight > 0 ? screenHeight : Math.max(1, height);
        screenWidth = targetWidth;
        screenHeight = targetHeight;
        texture.setDefaultBufferSize(targetWidth, targetHeight);

        destroyed = false;
        surface = new Surface(texture);
        if (startRequested) {
            start();
        }
    }

    @Override
    public synchronized boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture texture) {
        destroyed = true;
        stop();
        if (surface != null) {
            try {
                surface.release();
            } catch (Throwable ignored) {
            }
            surface = null;
        }
        return true;
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture texture, int width, int height) {
        int targetWidth = screenWidth > 0 ? screenWidth : Math.max(1, width);
        int targetHeight = screenHeight > 0 ? screenHeight : Math.max(1, height);
        texture.setDefaultBufferSize(targetWidth, targetHeight);
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture texture) {
    }

    @Override
    public void run() {
        try {
            while (running && !destroyed) {
                Surface currentSurface = surface;
                if (currentSurface == null || !currentSurface.isValid()) {
                    sleepQuietly(FRAME_SLEEP_MS);
                    continue;
                }

                Canvas canvas = null;
                try {
                    canvas = currentSurface.lockCanvas(null);
                    if (canvas == null) {
                        sleepQuietly(FRAME_SLEEP_MS);
                        continue;
                    }

                    canvas.drawColor(Color.BLACK);
                    drawAwtFrame(canvas);
                } catch (Throwable ignored) {
                    // The JVM/Cacio side may not be ready yet. Keep renderer alive.
                } finally {
                    try {
                        if (canvas != null && currentSurface.isValid()) {
                            currentSurface.unlockCanvasAndPost(canvas);
                        }
                    } catch (Throwable ignored) {
                    }
                }

                sleepQuietly(FRAME_SLEEP_MS);
            }
        } finally {
            releaseBitmap();
        }
    }

    private void drawAwtFrame(@NonNull Canvas canvas) {
        int[] pixels = null;
        try {
            pixels = JREUtils.renderAWTScreenFrame();
        } catch (Throwable ignored) {
        }

        if (pixels == null || pixels.length == 0) {
            if (statusText != null && !statusText.isEmpty()) {
                canvas.drawText(statusText, 24f, 48f, textPaint);
            }
            return;
        }

        int width = Math.max(1, screenWidth);
        int height = Math.max(1, screenHeight);
        if (pixels.length != width * height) {
            int guessedWidth = guessFrameWidth(pixels.length, width, height);
            if (guessedWidth > 0 && pixels.length % guessedWidth == 0) {
                width = guessedWidth;
                height = pixels.length / guessedWidth;
            }
        }

        if (width <= 0 || height <= 0 || width * height > pixels.length) return;
        if (bitmap == null || bitmap.getWidth() != width || bitmap.getHeight() != height) {
            releaseBitmap();
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            screenWidth = width;
            screenHeight = height;
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        canvas.drawBitmap(bitmap, null, canvas.getClipBounds(), paint);
    }

    private int guessFrameWidth(int pixelCount, int preferredWidth, int preferredHeight) {
        if (preferredWidth > 0 && pixelCount % preferredWidth == 0) return preferredWidth;
        int guessed = (int) Math.round(Math.sqrt(pixelCount * (preferredWidth / (double) Math.max(1, preferredHeight))));
        if (guessed > 0 && pixelCount % guessed == 0) return guessed;
        guessed = (int) Math.round(Math.sqrt(pixelCount));
        return guessed > 0 && pixelCount % guessed == 0 ? guessed : -1;
    }

    private synchronized void releaseBitmap() {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
        bitmap = null;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        requestFocus();
        int[] mapped = mapTouchToManagedScreen(event.getX(), event.getY());
        AWTInputBridge.sendMousePos(mapped[0], mapped[1]);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                AWTInputBridge.sendMouseButton(AWTInputBridge.BUTTON1_DOWN_MASK, true);
                return true;
            case MotionEvent.ACTION_MOVE:
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                AWTInputBridge.sendMouseButton(AWTInputBridge.BUTTON1_DOWN_MASK, false);
                return true;
            default:
                return true;
        }
    }

    @NonNull
    private int[] mapTouchToManagedScreen(float x, float y) {
        int viewWidth = Math.max(1, getWidth());
        int viewHeight = Math.max(1, getHeight());
        int managedWidth = Math.max(1, screenWidth > 0 ? screenWidth : viewWidth);
        int managedHeight = Math.max(1, screenHeight > 0 ? screenHeight : viewHeight);
        int mappedX = Math.max(0, Math.min(managedWidth - 1, Math.round(x * managedWidth / (float) viewWidth)));
        int mappedY = Math.max(0, Math.min(managedHeight - 1, Math.round(y * managedHeight / (float) viewHeight)));
        return new int[]{mappedX, mappedY};
    }
}
