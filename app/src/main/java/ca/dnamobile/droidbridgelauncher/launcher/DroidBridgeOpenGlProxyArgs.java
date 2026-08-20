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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Locale;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.renderer.DroidBridgeMesaSupport;
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
        if (DroidBridgeMesaSupport.isMesaZinkTurnipRenderer(renderer)) {
            appendLog("DroidBridgeGLProxy: skipped for Vulkan Zink rollback path renderer="
                    + renderer.getRendererId());
            return plan;
        }

        if (!DroidBridgeMesaSupport.isDroidBridgeMesaRenderer(renderer)) {
            appendLog("DroidBridgeGLProxy: skipped for renderer=" + renderer.getRendererId());
            return plan;
        }

        ArrayList<String> args = new ArrayList<>(plan.getJvmArgs());

        removeManagedArg(args, "-Dorg.lwjgl.opengl.libname=");
        removeManagedArg(args, "-Dorg.lwjgl.opengles.libname=");
        removeManagedArg(args, "-Dorg.lwjgl.opengl.contextAPI=");
        removeManagedArg(args, "-Dorg.lwjgl.opengles.contextAPI=");
        removeManagedArg(args, "-Dorg.lwjgl.util.Debug=");
        removeManagedArg(args, "-Dorg.lwjgl.util.DebugLoader=");

        args.add(0, "-Dorg.lwjgl.util.Debug=true");
        args.add(1, "-Dorg.lwjgl.util.DebugLoader=true");
        args.add(2, "-Dorg.lwjgl.opengl.libname=" + OPENGL_PROXY_SONAME);
        args.add(3, "-Dorg.lwjgl.opengles.libname=" + OPENGL_PROXY_SONAME);

        appendLog("DroidBridgeGLProxy: enabled DroidBridge Mesa LWJGL OpenGL proxy="
                + OPENGL_PROXY_SONAME
                + " renderer="
                + renderer.getRendererId());
        appendLog("DroidBridgeGLProxy: expecting native hook line containing 'DroidBridge RenderSpec request'");
        return plan.copyWithJvmArgs(args);
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
