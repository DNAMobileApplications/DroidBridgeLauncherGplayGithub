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

import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;

/**
 * Diagnostic-only sync fence monitor for MobileGlues on Adreno.
 *
 * Do NOT close /proc/self/fd entries from here.
 * anon_inode:sync_file descriptors are owned by EGL, SurfaceFlinger, the GPU
 * driver, or other native layers. Adopting and closing those raw FDs can trigger
 * Android fdsan aborts such as:
 *
 *     fdsan: double-close of file descriptor X detected
 *
 * This class intentionally logs pressure only so the launcher can warn about
 * renderer/device instability without corrupting native FD ownership.
 */
public final class AdrenoSyncFenceFdGuard {
    private static final String TAG = "AdrenoSyncFenceFdGuard";

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final ConcurrentHashMap<Integer, Long> FIRST_SEEN_MS = new ConcurrentHashMap<>();

    private static final long SCAN_INTERVAL_MS = 2000L;

    private static final int WARN_SYNC_FDS = 1536;
    private static final int CRITICAL_SYNC_FDS = 4096;

    private AdrenoSyncFenceFdGuard() {
    }

    public static void startIfNeeded(@Nullable RendererInterface renderer) {
        if (!MobileGluesConfigHelper.isMobileGluesRenderer(renderer)) return;
        if (!STARTED.compareAndSet(false, true)) return;

        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                runGuardLoop();
            }
        }, "DroidBridge-AdrenoSyncFenceGuard");
        thread.setDaemon(true);
        thread.start();

        Logging.i(TAG, "Started Adreno sync_file FD monitor"
                + " device=" + safe(Build.MANUFACTURER) + "/" + safe(Build.HARDWARE)
                + " renderer=" + renderer.getRendererName()
                + " mode=log-only"
                + " warn=" + WARN_SYNC_FDS
                + " critical=" + CRITICAL_SYNC_FDS);
    }

    private static void runGuardLoop() {
        int scan = 0;
        while (true) {
            try {
                Thread.sleep(SCAN_INTERVAL_MS);
                scan++;

                Snapshot snapshot = scan();
                boolean warn = snapshot.syncFileFds.size() >= WARN_SYNC_FDS;
                boolean critical = snapshot.syncFileFds.size() >= CRITICAL_SYNC_FDS;

                if (scan == 1 || scan % 5 == 0 || warn) {
                    Logging.i(TAG, "Sync fence FD monitor snapshot"
                            + " openFds=" + snapshot.openFdCount
                            + " syncFiles=" + snapshot.syncFileFds.size()
                            + " state=" + stateName(snapshot.syncFileFds.size())
                            + " action=log-only"
                            + " samples=" + snapshot.samples);
                }

                if (critical) {
                    Logging.e(TAG, "Critical MobileGlues/Adreno sync_file FD pressure detected; "
                            + "not closing foreign FDs. Try MobileGlues multidrawMode=Force DrawElements "
                            + "or disable experimental compute/indirect draw paths."
                            + " openFds=" + snapshot.openFdCount
                            + " syncFiles=" + snapshot.syncFileFds.size());
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable throwable) {
                Logging.e(TAG, "Adreno sync_file FD monitor failed", throwable);
            }
        }
    }

    @NonNull
    private static Snapshot scan() {
        long nowMs = System.currentTimeMillis();
        Snapshot snapshot = new Snapshot();

        File dir = new File("/proc/self/fd");
        String[] names = dir.list();
        snapshot.openFdCount = names != null ? names.length : -1;

        HashSet<Integer> presentSyncFds = new HashSet<>();

        if (names != null) {
            for (String name : names) {
                int fd = parseFd(name);
                if (fd < 0) continue;

                String target = readFdTarget(name);
                if (!isSyncFile(target)) continue;

                snapshot.syncFileFds.add(fd);
                presentSyncFds.add(fd);

                Long firstSeen = FIRST_SEEN_MS.putIfAbsent(fd, nowMs);
                if (firstSeen == null) firstSeen = nowMs;

                if (snapshot.samples.size() < 10) {
                    snapshot.samples.add(fd + ":" + target + ":ageMs=" + Math.max(0L, nowMs - firstSeen));
                }
            }
        }

        Collections.sort(snapshot.syncFileFds);

        Iterator<Integer> iterator = FIRST_SEEN_MS.keySet().iterator();
        while (iterator.hasNext()) {
            Integer fd = iterator.next();
            if (!presentSyncFds.contains(fd)) iterator.remove();
        }

        return snapshot;
    }

    private static boolean isSyncFile(@Nullable String target) {
        return target != null && target.toLowerCase(Locale.ROOT).contains("anon_inode:sync_file");
    }

    @NonNull
    private static String readFdTarget(@NonNull String name) {
        try {
            Path target = Files.readSymbolicLink(Paths.get("/proc/self/fd", name));
            return String.valueOf(target);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static int parseFd(@Nullable String name) {
        if (name == null) return -1;
        try {
            return Integer.parseInt(name);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    @NonNull
    private static String safe(@Nullable String value) {
        return value == null ? "" : value;
    }

    @NonNull
    private static String stateName(int syncFileCount) {
        if (syncFileCount >= CRITICAL_SYNC_FDS) return "critical";
        if (syncFileCount >= WARN_SYNC_FDS) return "warning";
        return "normal";
    }

    private static final class Snapshot {
        final ArrayList<Integer> syncFileFds = new ArrayList<>();
        final ArrayList<String> samples = new ArrayList<>();
        int openFdCount;
    }
}
