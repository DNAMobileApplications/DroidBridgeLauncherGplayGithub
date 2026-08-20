/*
 * Copyright (c) 2026 DNA Mobile Applications.
 */
package ca.dnamobile.nativeglfw;

import android.content.Context;
import android.util.AttributeSet;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.Nullable;

/**
 * Simple SurfaceView wrapper used for smoke-testing the native EGL owner.
 * DroidBridge can either use this view directly or forward its existing Surface callbacks
 * into DroidBridgeNativeGlfw.
 */
public final class DroidBridgeNativeGlfwSurfaceView extends SurfaceView implements SurfaceHolder.Callback {
    private final DroidBridgeNativeGlfw nativeGlfw = new DroidBridgeNativeGlfw();
    private boolean desktopGl = false;
    private String eglLibrary = DroidBridgeNativeGlfw.EGL_SYSTEM;
    private String mesaDriver = "";

    public DroidBridgeNativeGlfwSurfaceView(Context context) {
        super(context);
        init();
    }

    public DroidBridgeNativeGlfwSurfaceView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        getHolder().addCallback(this);
    }

    public DroidBridgeNativeGlfw getNativeGlfw() {
        return nativeGlfw;
    }

    public void configureRenderer(String eglLibraryPathOrName, String driverOverride, boolean useDesktopGl) {
        this.eglLibrary = eglLibraryPathOrName == null ? DroidBridgeNativeGlfw.EGL_SYSTEM : eglLibraryPathOrName;
        this.mesaDriver = driverOverride == null ? "" : driverOverride;
        this.desktopGl = useDesktopGl;
        nativeGlfw.configureRenderer(eglLibrary, mesaDriver, desktopGl, 0, 0);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        nativeGlfw.configureRenderer(eglLibrary, mesaDriver, desktopGl, 0, 0);
        nativeGlfw.surfaceCreated(holder.getSurface(), Math.max(getWidth(), 1), Math.max(getHeight(), 1));
        nativeGlfw.clearTest(0.07f, 0.09f, 0.12f, 1.0f);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        nativeGlfw.surfaceChanged(width, height);
        nativeGlfw.clearTest(0.07f, 0.09f, 0.12f, 1.0f);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        nativeGlfw.surfaceDestroyed();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        nativeGlfw.close();
    }
}
