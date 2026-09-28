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

package ca.dnamobile.droidbridgelauncher.renderer;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;

public final class DroidBridgeRenderSpec {
    private static final String TAG = "DroidBridgeRenderSpec";

    private DroidBridgeRenderSpec() {
    }

    /**
     * Configures the native RenderSpec before the embedded JVM starts.
     *
     * Mesa already used this path. Modern wrapped OpenGL renderers need the
     * same early provider selection so LWJGL 3.4 can resolve OpenGL before
     * GLFW/SDL has created the game window. This mirrors the renderer setup
     * order used by launchers that prepare the EGL wrapper before JVM startup.
     */
    public static boolean configureForRenderer(
            @NonNull Context context,
            @Nullable RendererInterface renderer
    ) {
        if (renderer == null) return false;
        if (DroidBridgeMesaSupport.isDroidBridgeMesaRenderer(renderer)) {
            return configureForMesa(context, renderer);
        }
        if (isWrappedOpenGlRenderer(renderer)) {
            return configureForWrappedOpenGl(context, renderer);
        }
        return false;
    }

    public static boolean configureForMesa(
            @NonNull Context context,
            @Nullable RendererInterface renderer
    ) {
        if (!DroidBridgeMesaSupport.isDroidBridgeMesaRenderer(renderer)) {
            return false;
        }

        File nativeDir = DroidBridgeMesaSupport.resolveMesaNativeDir(context);
        File aliasDir = DroidBridgeMesaSupport.prepareMesaLibraryAliases(context, renderer);
        String namespacePath = DroidBridgeMesaSupport.buildMesaNamespacePath(context, renderer);
        File eglMesa = new File(nativeDir, DroidBridgeMesaSupport.LIB_EGL_MESA);

        RenderSpecRequest namespaceRequest = RenderSpecRequest.namespace(
                DroidBridgeMesaSupport.LIB_EGL_MESA,
                namespacePath,
                false,
                0
        );
        String details = " nativeDir=" + nativeDir.getAbsolutePath()
                + " aliasDir=" + aliasDir.getAbsolutePath();
        if (tryConfigure("namespace", namespaceRequest, details)) {
            return true;
        }

        RenderSpecRequest fallbackRequest = RenderSpecRequest.direct(
                eglMesa.getAbsolutePath(),
                false,
                0
        );
        return tryConfigure("fallback", fallbackRequest, details);
    }

    /**
     * LTW and MobileGlues are GLES-backed desktop-OpenGL wrappers. Their EGL/
     * GL provider must be selected before LWJGL's GL class initializes on
     * Minecraft 26.2+; otherwise LWJGL can probe OpenGL before GLFW/SDL and
     * report that no context-management API exists.
     */
    private static boolean configureForWrappedOpenGl(
            @NonNull Context context,
            @NonNull RendererInterface renderer
    ) {
        String rendererLibrary = sanitize(renderer.getRendererLibrary());
        String eglName = sanitize(renderer.getRendererEGL());
        if (eglName.isEmpty()) {
            eglName = fileName(rendererLibrary);
        }
        if (eglName.isEmpty()) {
            Logging.i(TAG, "Wrapped RenderSpec skipped: renderer has no EGL/GL provider "
                    + renderer.getRendererName());
            return false;
        }

        File provider = resolveProviderFile(renderer, eglName, rendererLibrary);
        String namespacePath = buildWrappedNamespacePath(context, renderer, provider);
        String providerName = provider != null ? provider.getName() : fileName(eglName);
        String details = " renderer=" + renderer.getRendererName()
                + " rendererId=" + renderer.getRendererId()
                + " provider=" + (provider != null ? provider.getAbsolutePath() : eglName);

        /*
         * Prefer the normal process namespace first. LTW on Android 10 is
         * intentionally extracted into DroidBridge's private files directory,
         * and the SDL3 bridge later opens that same absolute file. Loading it
         * here in the default namespace guarantees that LWJGL and SDL/EGL share
         * one wrapper image and one global LTW state. This matches MJ's
         * namespace=0 RenderSpec setup. Use a dedicated namespace only as a
         * fallback for plugins whose dependencies cannot resolve directly.
         */
        if (provider != null && provider.isFile()) {
            RenderSpecRequest directRequest = RenderSpecRequest.direct(
                    provider.getAbsolutePath(),
                    true,
                    3
            );
            if (tryConfigure("wrapped-direct", directRequest, details)) {
                return true;
            }
        }

        if (!namespacePath.isEmpty() && !providerName.isEmpty()) {
            RenderSpecRequest namespaceRequest = RenderSpecRequest.namespace(
                    providerName,
                    namespacePath,
                    true,
                    3
            );
            if (tryConfigure("wrapped-namespace", namespaceRequest, details)) {
                return true;
            }
        }

        String directName = !rendererLibrary.isEmpty() ? rendererLibrary : eglName;
        return tryConfigure(
                "wrapped-name",
                RenderSpecRequest.direct(directName, true, 3),
                details
        );
    }

    public static boolean isWrappedOpenGlRenderer(@Nullable RendererInterface renderer) {
        if (renderer == null) return false;
        String identity = (sanitize(renderer.getRendererId()) + " "
                + sanitize(renderer.getUniqueIdentifier()) + " "
                + sanitize(renderer.getRendererName()) + " "
                + sanitize(renderer.getRendererLibrary()) + " "
                + sanitize(renderer.getRendererEGL())).toLowerCase(Locale.ROOT);
        return identity.contains("ltw")
                || identity.contains("mobileglues")
                || identity.contains("mobile glues");
    }

    @Nullable
    private static File resolveProviderFile(
            @NonNull RendererInterface renderer,
            @NonNull String eglName,
            @NonNull String rendererLibrary
    ) {
        File directEgl = new File(eglName);
        if (directEgl.isAbsolute() && directEgl.isFile()) return directEgl;

        File directRenderer = new File(rendererLibrary);
        if (directRenderer.isAbsolute() && directRenderer.isFile()) {
            if (fileName(rendererLibrary).equalsIgnoreCase(fileName(eglName))) {
                return directRenderer;
            }
        }

        List<File> searchPaths = renderer.getLibrarySearchPaths();
        for (File searchPath : searchPaths) {
            if (searchPath == null || !searchPath.isDirectory()) continue;
            File candidate = new File(searchPath, fileName(eglName));
            if (candidate.isFile()) return candidate;
            if (!rendererLibrary.isEmpty()) {
                candidate = new File(searchPath, fileName(rendererLibrary));
                if (candidate.isFile()) return candidate;
            }
        }
        return directRenderer.isAbsolute() && directRenderer.isFile() ? directRenderer : null;
    }

    @NonNull
    private static String buildWrappedNamespacePath(
            @NonNull Context context,
            @NonNull RendererInterface renderer,
            @Nullable File provider
    ) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();

        if (provider != null && provider.getParentFile() != null) {
            addDirectory(paths, provider.getParentFile());
        }
        for (File searchPath : renderer.getLibrarySearchPaths()) {
            addDirectory(paths, searchPath);
        }
        try {
            String nativeLibraryDir = context.getApplicationInfo().nativeLibraryDir;
            if (nativeLibraryDir != null && !nativeLibraryDir.trim().isEmpty()) {
                addDirectory(paths, new File(nativeLibraryDir));
            }
        } catch (Throwable ignored) {
        }

        StringBuilder builder = new StringBuilder();
        for (String path : paths) {
            if (builder.length() > 0) builder.append(File.pathSeparatorChar);
            builder.append(path);
        }
        return builder.toString();
    }

    private static void addDirectory(
            @NonNull LinkedHashSet<String> paths,
            @Nullable File directory
    ) {
        if (directory == null || !directory.isDirectory()) return;
        paths.add(directory.getAbsolutePath());
    }

    @NonNull
    private static String fileName(@Nullable String value) {
        String clean = sanitize(value);
        return clean.isEmpty() ? "" : new File(clean).getName();
    }

    @NonNull
    private static String sanitize(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean tryConfigure(
            @NonNull String mode,
            @NonNull RenderSpecRequest request,
            @NonNull String details
    ) {
        try {
            boolean configured = nativeConfigure(
                    request.eglPath,
                    request.namespacePath,
                    request.useNamespace,
                    request.forceGlesContext,
                    request.overrideMajorVersion
            );

            Logging.i(TAG, "Configured " + mode + " RenderSpec result=" + configured
                    + " egl=" + request.eglPath
                    + " namespacePath=" + request.namespacePath
                    + " forceGles=" + request.forceGlesContext
                    + " overrideMajor=" + request.overrideMajorVersion
                    + details);
            return configured;
        } catch (Throwable throwable) {
            Logging.e(TAG, mode + " RenderSpec configure failed" + details, throwable);
            return false;
        }
    }

    private static final class RenderSpecRequest {
        final String eglPath;
        final String namespacePath;
        final boolean useNamespace;
        final boolean forceGlesContext;
        final int overrideMajorVersion;

        private RenderSpecRequest(
                @NonNull String eglPath,
                @NonNull String namespacePath,
                boolean useNamespace,
                boolean forceGlesContext,
                int overrideMajorVersion
        ) {
            this.eglPath = eglPath;
            this.namespacePath = namespacePath;
            this.useNamespace = useNamespace;
            this.forceGlesContext = forceGlesContext;
            this.overrideMajorVersion = overrideMajorVersion;
        }

        static RenderSpecRequest namespace(
                @NonNull String eglName,
                @NonNull String namespacePath,
                boolean forceGlesContext,
                int overrideMajorVersion
        ) {
            return new RenderSpecRequest(
                    eglName,
                    namespacePath,
                    true,
                    forceGlesContext,
                    overrideMajorVersion
            );
        }

        static RenderSpecRequest direct(
                @NonNull String eglPath,
                boolean forceGlesContext,
                int overrideMajorVersion
        ) {
            return new RenderSpecRequest(
                    eglPath,
                    "",
                    false,
                    forceGlesContext,
                    overrideMajorVersion
            );
        }
    }

    private static native boolean nativeConfigure(
            String eglPath,
            String namespacePath,
            boolean useNamespace,
            boolean forceGlesContext,
            int overrideMajorVersion
    );
}
