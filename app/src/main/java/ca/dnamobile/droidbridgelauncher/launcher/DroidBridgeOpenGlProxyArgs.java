/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, Xbox, third-party launcher, third-party launcher,
 * or any third-party project.
 */

package ca.dnamobile.droidbridgelauncher.launcher;

import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Locale;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.renderer.DroidBridgeMesaSupport;
import ca.dnamobile.droidbridgelauncher.renderer.DroidBridgeRenderSpec;
import ca.dnamobile.droidbridgelauncher.renderer.KopperZinkRenderer;
import ca.dnamobile.droidbridgelauncher.renderer.RendererInterface;
import ca.dnamobile.droidbridgelauncher.runtime.Logger;

public final class DroidBridgeOpenGlProxyArgs {
    public static final String OPENGL_PROXY_SONAME = "libGLDroidBridge.so";
    private static final String TAG = "DroidBridgeGLProxy";

    private DroidBridgeOpenGlProxyArgs() {
    }

    @NonNull
    public static LaunchPlan applyIfNeeded(
            @NonNull LaunchPlan plan,
            @NonNull RendererInterface renderer
    ) {
        final boolean kopperZink = KopperZinkRenderer.isRenderer(renderer);
        final boolean droidBridgeMesa = DroidBridgeMesaSupport.isDroidBridgeMesaRenderer(renderer);
        final boolean android10ModernWrappedOpenGl = Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q
                && DroidBridgeRenderSpec.isWrappedOpenGlRenderer(renderer)
                && isMinecraft26_2OrNewer(plan);

        if (!kopperZink && DroidBridgeMesaSupport.isMesaZinkTurnipRenderer(renderer)) {
            appendLog("DroidBridgeGLProxy: skipped for Vulkan Zink rollback path renderer="
                    + renderer.getRendererId());
            return plan;
        }

        if (!kopperZink && !droidBridgeMesa && !android10ModernWrappedOpenGl) {
            appendLog("DroidBridgeGLProxy: skipped for renderer=" + renderer.getRendererId());
            return plan;
        }

        ArrayList<String> args = new ArrayList<>(plan.getJvmArgs());

        removeManagedArg(args, "-Dorg.lwjgl.opengl.libname=");
        removeManagedArg(args, "-Dorg.lwjgl.opengles.libname=");
        removeManagedArg(args, "-Dorg.lwjgl.opengl.contextAPI=");
        removeManagedArg(args, "-Dorg.lwjgl.opengles.contextAPI=");
        removeManagedArg(args, "-Dorg.lwjgl.egl.libname=");
        removeManagedArg(args, "-Dorg.lwjgl.util.Debug=");
        removeManagedArg(args, "-Dorg.lwjgl.util.DebugLoader=");

        args.add(0, "-Dorg.lwjgl.util.Debug=true");
        args.add(1, "-Dorg.lwjgl.util.DebugLoader=true");
        args.add(2, "-Dorg.lwjgl.opengl.libname=" + OPENGL_PROXY_SONAME);
        args.add(3, "-Dorg.lwjgl.opengles.libname=" + OPENGL_PROXY_SONAME);

        appendLog("DroidBridgeGLProxy: enabled LWJGL OpenGL RenderSpec proxy="
                + OPENGL_PROXY_SONAME
                + " renderer="
                + renderer.getRendererId()
                + (kopperZink ? " kopperGlxToEgl=1" : "")
                + (android10ModernWrappedOpenGl ? " android10WrappedOpenGl=1" : ""));
        appendLog("DroidBridgeGLProxy: expecting native hook line containing 'replacing OpenGL with configured RenderSpec driver'");
        return plan.copyWithJvmArgs(args);
    }

    private static boolean isMinecraft26_2OrNewer(@NonNull LaunchPlan plan) {
        return isMinecraft26_2OrNewer(plan.getEffectiveMinecraftVersionId())
                || isMinecraft26_2OrNewer(plan.getVersionId());
    }

    private static boolean isMinecraft26_2OrNewer(@Nullable String value) {
        if (value == null) return false;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^26\\.(\\d+)")
                .matcher(normalized);
        if (!matcher.find()) return false;
        try {
            return Integer.parseInt(matcher.group(1)) >= 2;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static void removeManagedArg(@NonNull ArrayList<String> args, @NonNull String prefix) {
        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
        for (int i = args.size() - 1; i >= 0; i--) {
            String arg = args.get(i);
            if (arg != null && arg.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) {
                args.remove(i);
            }
        }
    }

    private static void appendLog(@Nullable String text) {
        if (text == null) return;
        try {
            Logger.appendToLog(text);
        } catch (Throwable ignored) {
        }
        try {
            Logging.i(TAG, text);
        } catch (Throwable ignored) {
        }
    }
}
