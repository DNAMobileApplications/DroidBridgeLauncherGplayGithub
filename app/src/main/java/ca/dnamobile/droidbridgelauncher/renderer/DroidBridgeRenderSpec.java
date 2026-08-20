/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * DroidBridge-owned Java bridge for configuring the Mesa RenderSpec handle.
 * Clean-main rewrite pass: native parameters are assembled in one value object
 * before crossing the JNI boundary.
 */

package ca.dnamobile.droidbridgelauncher.renderer;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;

public final class DroidBridgeRenderSpec {
    private static final String TAG = "DroidBridgeRenderSpec";

    private DroidBridgeRenderSpec() {
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
                namespacePath
        );
        if (tryConfigure("namespace", namespaceRequest, nativeDir, aliasDir)) {
            return true;
        }

        RenderSpecRequest fallbackRequest = RenderSpecRequest.direct(eglMesa.getAbsolutePath());
        return tryConfigure("fallback", fallbackRequest, nativeDir, aliasDir);
    }

    private static boolean tryConfigure(
            @NonNull String mode,
            @NonNull RenderSpecRequest request,
            @NonNull File nativeDir,
            @NonNull File aliasDir
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
                    + " nativeDir=" + nativeDir.getAbsolutePath()
                    + " aliasDir=" + aliasDir.getAbsolutePath());
            return configured;
        } catch (Throwable throwable) {
            Logging.e(TAG, mode + " RenderSpec configure failed", throwable);
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

        static RenderSpecRequest namespace(@NonNull String eglName, @NonNull String namespacePath) {
            return new RenderSpecRequest(eglName, namespacePath, true, false, 0);
        }

        static RenderSpecRequest direct(@NonNull String eglPath) {
            return new RenderSpecRequest(eglPath, "", false, false, 0);
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
