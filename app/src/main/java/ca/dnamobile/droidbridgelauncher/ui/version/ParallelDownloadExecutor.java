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

package ca.dnamobile.droidbridgelauncher.ui.version;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Small bounded parallel worker used by the Minecraft/loader installers.
 *
 * The old installer downloaded every asset and loader library one-by-one. That
 * makes Fabric/Forge/NeoForge installs feel slow even on fast internet because a
 * Minecraft install can contain hundreds of small asset requests. This keeps the
 * install safe by limiting concurrency instead of spawning one thread per file.
 */
public final class ParallelDownloadExecutor {
    private static final int MAX_NETWORK_THREADS = 6;

    private ParallelDownloadExecutor() {
    }

    public interface Worker<T> {
        void run(@NonNull T item) throws Exception;
    }

    public interface Progress<T> {
        void onItemComplete(int completed, int total, @NonNull T item);
    }

    public static int defaultNetworkThreads() {
        return MAX_NETWORK_THREADS;
    }

    public static <T> void run(
            @NonNull List<T> items,
            int maxThreads,
            @NonNull Worker<T> worker,
            @Nullable Progress<T> progress
    ) throws Exception {
        if (items.isEmpty()) return;

        int workerCount = Math.max(1, Math.min(Math.min(maxThreads, items.size()), MAX_NETWORK_THREADS));
        ExecutorService executor = Executors.newFixedThreadPool(workerCount, new ThreadFactory() {
            private final AtomicInteger nextId = new AtomicInteger(1);

            @Override
            public Thread newThread(@NonNull Runnable runnable) {
                Thread thread = new Thread(runnable, "Installer download #" + nextId.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            }
        });

        AtomicInteger completed = new AtomicInteger(0);
        ArrayList<Future<?>> futures = new ArrayList<>(items.size());

        try {
            for (T item : items) {
                futures.add(executor.submit(() -> {
                    worker.run(item);
                    int done = completed.incrementAndGet();
                    if (progress != null) {
                        progress.onItemComplete(done, items.size(), item);
                    }
                    return null;
                }));
            }

            for (Future<?> future : futures) {
                future.get();
            }
        } catch (ExecutionException e) {
            cancelAll(futures);
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new Exception(cause);
        } catch (InterruptedException e) {
            cancelAll(futures);
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            executor.shutdownNow();
        }
    }

    private static void cancelAll(@NonNull ArrayList<Future<?>> futures) {
        for (Future<?> future : futures) {
            future.cancel(true);
        }
    }
}
