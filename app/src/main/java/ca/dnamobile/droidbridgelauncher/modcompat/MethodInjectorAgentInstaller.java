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

package ca.dnamobile.droidbridgelauncher.modcompat;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import ca.dnamobile.droidbridgelauncher.runtime.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Copies the Java agent out of APK assets and adds it to the game JVM with -javaagent.
 */
public final class MethodInjectorAgentInstaller {
    private static final String ASSET_PATH = "components/methods_injector_agent/methods_injector_agent.jar";
    private static final String OUTPUT_DIR = "methods_injector_agent";
    private static final String OUTPUT_JAR = "methods_injector_agent.jar";

    private MethodInjectorAgentInstaller() {
    }

    @Nullable
    public static File install(@NonNull Context context) {
        File dir = new File(context.getFilesDir(), OUTPUT_DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            log("MethodInjectorAgent: failed to create directory: " + dir.getAbsolutePath());
            return null;
        }

        File out = new File(dir, OUTPUT_JAR);

        try (InputStream input = context.getAssets().open(ASSET_PATH)) {
            copyAssetToFile(input, out);

            if (!out.isFile() || out.length() <= 0) {
                log("MethodInjectorAgent: copied jar is missing or empty: " + out.getAbsolutePath());
                return null;
            }

            log("MethodInjectorAgent: installed " + out.getAbsolutePath() + " (" + out.length() + " bytes)");
            return out;
        } catch (IOException e) {
            log("MethodInjectorAgent: failed to install asset " + ASSET_PATH + ": " + e);
            return null;
        } catch (Throwable t) {
            log("MethodInjectorAgent: unexpected install failure: " + t);
            return null;
        }
    }

    public static boolean addJavaAgentArg(@NonNull Context context, @NonNull List<String> jvmArgs) {
        File jar = install(context);
        if (jar == null) {
            log("MethodInjectorAgent: disabled because jar could not be installed");
            return false;
        }

        String arg = "-javaagent:" + jar.getAbsolutePath();

        for (int i = jvmArgs.size() - 1; i >= 0; i--) {
            String existing = jvmArgs.get(i);
            if (existing != null && existing.startsWith("-javaagent:") && existing.contains(OUTPUT_JAR)) {
                jvmArgs.remove(i);
            }
        }

        jvmArgs.add(0, arg);
        log("MethodInjectorAgent: enabled " + arg);
        return true;
    }

    private static void copyAssetToFile(@NonNull InputStream input, @NonNull File out) throws IOException {
        File parent = out.getParentFile();
        if (parent == null) {
            throw new IOException("Output file has no parent: " + out);
        }

        File tmp = new File(parent, out.getName() + ".tmp");

        try (FileOutputStream output = new FileOutputStream(tmp, false)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.flush();
        }

        // Always replace the installed agent. A size-only comparison can leave stale jars behind.
        if (out.exists() && !out.delete()) {
            throw new IOException("Unable to replace old agent jar: " + out.getAbsolutePath());
        }

        if (!tmp.renameTo(out)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("Unable to move temp agent jar to: " + out.getAbsolutePath());
        }
    }

    private static void log(@NonNull String message) {
        try {
            Logger.appendToLog(message.endsWith("\n") ? message : message + "\n");
        } catch (Throwable ignored) {
            android.util.Log.i("MethodInjectorAgent", message);
        }
    }
}
