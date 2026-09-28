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

package ca.dnamobile.droidbridgelauncher.logs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Locale;

public final class LatestLogTextFilter {
    private LatestLogTextFilter() {
    }

    @Nullable
    public static String cleanLauncherLine(@Nullable String text) {
        if (text == null) return null;

        String line = normalizeLauncherLine(text);
        if (line.isEmpty()) return "";

        if (isForeignMesaBuilderBranding(line)) return null;
        if (isControllerCompatibilityNoise(line, false)) return null;
        if (isLauncherProgressNoise(line)) return null;
        if (isHighFrequencyRuntimeNoise(line)) return null;
        if (isMitigationBoundaryNoise(line)) return null;

        return line;
    }

    @Nullable
    public static String cleanRealtimeLine(@Nullable String text) {
        if (text == null) return null;

        String line = normalizeLauncherLine(text);
        if (line.isEmpty()) return "";

        if (isForeignMesaBuilderBranding(line)) return null;
        if (isControlifyScanSpam(line)) return null;
        if (isControllerCompatibilityNoise(line, false)) return null;
        if (isLauncherProgressNoise(line)) return null;
        if (isHighFrequencyRuntimeNoise(line)) return null;
        if (isMitigationBoundaryNoise(line)) return null;

        return line;
    }

    @NonNull
    public static String normalizeLauncherLine(@Nullable String text) {
        if (text == null) return "";

        String value = text.replace("\r\n", "\n").replace('\r', '\n');

        while (value.endsWith("\n")) {
            value = value.substring(0, value.length() - 1);
        }

        return value.trim();
    }

    @NonNull
    public static String cleanWholeLog(@Nullable String rawLog) {
        if (rawLog == null || rawLog.isEmpty()) return "";

        String normalized = rawLog.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        boolean controllableLaunch = normalized.contains("Game: 1.20.1 Controllable")
                || normalized.contains("ControllerModCompat: Controllable")
                || normalized.toLowerCase(Locale.ROOT).contains("controllable detected");

        ArrayList<String> out = new ArrayList<>();

        boolean inLwjglMismatchBlock = false;
        boolean keptFirstLwjglMismatchBlock = false;
        boolean emittedDuplicateLwjglSummary = false;

        boolean inNarratorFliteBlock = false;
        boolean emittedNarratorSummary = false;

        boolean inRendererSetupBlock = false;
        boolean inLegacyFileHeader = false;
        boolean waitingForMinecraftOutput = false;

        int jreDlopenSuccessCount = 0;

        for (String rawLine : lines) {
            String line = stripTrailingLineBreaks(rawLine);
            String trimmed = line.trim();

            if ("DroidBridge Launcher latestlog.txt".equals(trimmed)) {
                inLegacyFileHeader = true;
                continue;
            }

            if (inLegacyFileHeader) {
                if (isLegacyFileHeaderContinuation(trimmed)) {
                    continue;
                }
                inLegacyFileHeader = false;
            }

            if (isLaunchHeader(trimmed)) {
                inRendererSetupBlock = false;
                addBlankLine(out);
            }

            if (inNarratorFliteBlock) {
                if (isLikelyNewImportantLogLine(trimmed)) {
                    inNarratorFliteBlock = false;
                } else {
                    continue;
                }
            }

            if (inLwjglMismatchBlock) {
                if (isLwjglMismatchContinuation(trimmed)) {
                    if (!keptFirstLwjglMismatchBlock) {
                        addLine(out, line);
                    }
                    continue;
                }

                inLwjglMismatchBlock = false;
                keptFirstLwjglMismatchBlock = true;
            }

            if (trimmed.isEmpty()) {
                flushJreDlopenSummary(out, jreDlopenSuccessCount);
                jreDlopenSuccessCount = 0;
                addBlankLine(out);
                continue;
            }

            if (isForeignMesaBuilderBranding(trimmed)
                    || isControlifyScanSpam(trimmed)
                    || isControllerCompatibilityNoise(trimmed, controllableLaunch)
                    || isLauncherProgressNoise(trimmed)
                    || isHighFrequencyRuntimeNoise(trimmed)
                    || isMitigationBoundaryNoise(trimmed)) {
                continue;
            }

            if (isJreDlopenSuccess(trimmed)) {
                jreDlopenSuccessCount++;
                continue;
            }

            flushJreDlopenSummary(out, jreDlopenSuccessCount);
            jreDlopenSuccessCount = 0;

            if (isLwjglMismatchStart(trimmed)) {
                inLwjglMismatchBlock = true;
                if (!keptFirstLwjglMismatchBlock) {
                    addLine(out, line);
                } else if (!emittedDuplicateLwjglSummary) {
                    addLine(out, "Warning: duplicate LWJGL Java/native version mismatch warnings suppressed.");
                    emittedDuplicateLwjglSummary = true;
                }
                continue;
            }

            if (isNarratorFliteStart(trimmed)) {
                if (!emittedNarratorSummary) {
                    addLine(out, "Info: Minecraft narrator native library is unavailable on Android; ignored.");
                    emittedNarratorSummary = true;
                }
                inNarratorFliteBlock = true;
                continue;
            }

            if (isSectionHeader(trimmed)) {
                addBlankLine(out);
                if ("----- Starting Minecraft -----".equals(trimmed)) {
                    waitingForMinecraftOutput = true;
                }
            }

            if (waitingForMinecraftOutput && isMinecraftOrJvmOutputStart(trimmed)) {
                addBlankLine(out);
                waitingForMinecraftOutput = false;
            }

            if (isRendererSetupStart(trimmed)) {
                addBlankLine(out);
                inRendererSetupBlock = true;
            } else if (inRendererSetupBlock && isMinecraftOrJvmOutputStart(trimmed)) {
                addBlankLine(out);
                inRendererSetupBlock = false;
            }

            if (isConsecutiveDuplicate(out, trimmed)) {
                continue;
            }

            addLine(out, line);
        }

        flushJreDlopenSummary(out, jreDlopenSuccessCount);

        compactDiagnosticBlankLines(out);

        while (!out.isEmpty() && out.get(out.size() - 1).trim().isEmpty()) {
            out.remove(out.size() - 1);
        }

        StringBuilder builder = new StringBuilder();
        for (String line : out) {
            builder.append(line).append('\n');
        }
        return builder.toString();
    }


    private static boolean isForeignMesaBuilderBranding(@NonNull String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        // Imported Kopper/Mesa builds may print their builder banner directly to stdout.
        // DroidBridge keeps Mesa's legal attribution separately and suppresses only the
        // foreign-launcher runtime banner from launcher-owned logs.
        return lower.startsWith("hello, zink! (c) mesa,") && lower.endsWith(", fcl");
    }

    private static boolean isControllerCompatibilityNoise(
            @NonNull String line,
            boolean controllableLaunch
    ) {
        if (line.startsWith("INPUT#")
                || line.startsWith("INPUT-MOTION#")
                || line.startsWith("INPUT-MARK")) {
            return true;
        }

        if (line.startsWith("GLFW: Set size for window ")) return true;
        if ("ControlifySDL: Controlify not found, SDL controller routing disabled".equals(line)) return true;
        if ("TouchController: TouchController mod not found; launcher raw touch proxy disabled".equals(line)) return true;

        if (line.startsWith("ControllerModCompat:")) {
            String lower = line.toLowerCase(Locale.ROOT);
            boolean status = lower.contains("loaded successfully")
                    || lower.contains("loaded with limited compatibility");
            boolean problem = lower.contains("failed")
                    || lower.contains("failure")
                    || lower.contains(" error")
                    || lower.contains(" missing")
                    || lower.contains("unable")
                    || lower.contains("still loading")
                    || lower.contains("did not")
                    || lower.contains("not enabled:")
                    || lower.contains("hit the jna")
                    || lower.contains("crash")
                    || lower.contains("disabled by preference");
            return !status && !problem;
        }

        if (line.startsWith("BTA controller bridge:")) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("failed") || lower.contains("no android controller devices")) {
                return false;
            }
            if (controllableLaunch) return true;
            return lower.contains("android controller id=")
                    || lower.contains("first android motion")
                    || lower.contains("first android button")
                    || lower.contains("right-stick y sample")
                    || lower.contains("right-stick menu scroll routed");
        }

        return false;
    }

    private static boolean isLauncherProgressNoise(@NonNull String line) {
        if ("Building launch arguments...".equals(line)) return true;
        if ("Checking controller compatibility...".equals(line)) return true;
        if (line.startsWith("Preparing launch for ")) return true;
        if (line.startsWith("LaunchGame: launch state reset")) return true;
        if (line.startsWith("Info: Launch step:")) return true;
        if (line.startsWith("Info: Instance settings key:")) return true;
        if ("Info: Per-instance launch settings: enabled".equals(line)) return true;
        if (line.startsWith("Info: Runtime patch:")) return true;
        if (line.startsWith("Info: Network DNS args:")) return true;

        return false;
    }

    private static boolean isHighFrequencyRuntimeNoise(@NonNull String line) {
        // These are useful only while actively debugging input/surface plumbing. They can
        // emit many lines per minute and bury the launch configuration and actual game errors.
        if (line.startsWith("DroidBridgeSDL3MouseSync:")) return true;
        if (line.startsWith("DroidBridgeSDL3Cursor:")) return true;
        if (line.startsWith("DroidBridgeSDL3Mouse:")) return true;
        if (line.startsWith("DroidBridgeSDL3: cursor recentered reason=")) return true;
        if (line.startsWith("DroidBridgeSDL3: SDL relative mouse=")) return true;
        if (line.startsWith("DroidBridgeSDL3: Android SDL input devices registered;")) return true;

        // Touch-keyboard cancellation can be emitted repeatedly without representing an
        // actual Vulkan lifecycle transition. Preserve real pause/resume and failure lines.
        return line.startsWith("DroidBridgeVulkanLifecycle: presentationPaused=false")
                && line.contains("reason=TouchKeyboardHelper IME cancel");
    }

    private static boolean isMitigationBoundaryNoise(@NonNull String line) {
        String lower = line.toLowerCase(Locale.ROOT);

        if (lower.contains(" mitigation: about to run on ")) return true;
        if (lower.endsWith(" mitigation: finished")) return true;
        if (lower.contains(" patch: about to run on ")) return true;
        if (lower.endsWith(" patch: finished")) return true;

        return lower.startsWith("distant horizons gc mitigation: skipped:")
                || lower.startsWith("vulkanmod mitigation: skipped:")
                || lower.startsWith("not enough vulkan mitigation: skipped:")
                || lower.startsWith("native mesa mitigation: skipped:")
                || lower.startsWith("sodium mobileglues patch: skipped:");
    }

    private static boolean isLegacyFileHeaderContinuation(@NonNull String line) {
        return line.isEmpty()
                || line.startsWith("Launcher:")
                || line.startsWith("Build package:")
                || line.startsWith("Minecraft:")
                || line.startsWith("Started:")
                || "----------------------------------------".equals(line);
    }

    private static boolean isLaunchHeader(@NonNull String line) {
        return line.startsWith("===== DroidBridge launch")
                || line.startsWith("--------- Start launching DroidBridge Launcher")
                || line.startsWith("New launch started for ");
    }

    private static boolean isSectionHeader(@NonNull String line) {
        return line.startsWith("----- ") && line.endsWith(" -----");
    }

    private static boolean isConsecutiveDuplicate(
            @NonNull ArrayList<String> out,
            @NonNull String line
    ) {
        if (out.isEmpty() || line.isEmpty()) return false;

        // Preserve stack structure even if recursive frames happen to repeat.
        if (line.startsWith("at ")
                || line.startsWith("Caused by:")
                || line.startsWith("Suppressed:")) {
            return false;
        }

        String previous = out.get(out.size() - 1).trim();
        return line.equals(previous);
    }

    private static void compactDiagnosticBlankLines(@NonNull ArrayList<String> lines) {
        for (int i = lines.size() - 2; i > 0; i--) {
            if (!lines.get(i).trim().isEmpty()) continue;

            String previous = lines.get(i - 1).trim();
            String next = lines.get(i + 1).trim();
            // Keep the deliberate separator immediately before structured section
            // headers. Only remove accidental spacing inside diagnostic blocks.
            if (isSectionHeader(next) || isLaunchHeader(next)) continue;

            if (isCompactDiagnosticLine(previous) && isCompactDiagnosticLine(next)) {
                lines.remove(i);
            }
        }
    }

    private static boolean isCompactDiagnosticLine(@NonNull String line) {
        return isLauncherOwnedLine(line)
                || line.startsWith("ControllerModCompat:")
                || line.startsWith("ControlifySDL:")
                || line.startsWith("TouchController:")
                || line.startsWith("BTA controller bridge:")
                || line.startsWith("DroidBridgeGLProxy:")
                || line.startsWith("DroidBridgeSDL3")
                || line.startsWith("DroidBridgeMobileGlues:")
                || line.startsWith("RuntimeBootstrap:")
                || line.startsWith("JavaLauncherExecHook:")
                || line.startsWith("EGLBridge:")
                || line.startsWith("GLBridge:")
                || line.startsWith("DroidBridgeEGLLoader:")
                || line.startsWith("OSMDroid:");
    }

    private static boolean isLauncherOwnedLine(@NonNull String line) {
        return line.startsWith("Info:")
                || line.startsWith("Warning:")
                || line.startsWith("Error:")
                || line.startsWith("Launcher:")
                || line.startsWith("Device:")
                || line.startsWith("Game:")
                || line.startsWith("Instance:")
                || line.startsWith("Account:")
                || line.startsWith("Renderer:")
                || line.startsWith("Renderer plugin:")
                || line.startsWith("Renderer settings (selected):")
                || line.startsWith("Graphics:")
                || line.startsWith("Graphics API:")
                || line.startsWith("System Vulkan driver:")
                || line.startsWith("System Vulkan driver (effective):")
                || line.startsWith("Use System Vulkan Driver setting:")
                || line.startsWith("Use OpenGL for Minecraft 26+:")
                || line.startsWith("Version-specific renderer defaults:")
                || line.startsWith("Vulkan VSync:")
                || line.startsWith("Vulkan Zink driver:")
                || line.startsWith("Alternative surface rendering:")
                || line.startsWith("Sustained performance:")
                || line.startsWith("Game resolution:")
                || line.startsWith("Resolution scale:")
                || line.startsWith("Surface size at launch:")
                || line.startsWith("Force fullscreen:")
                || line.startsWith("Ignore notch:")
                || line.startsWith("Avoid rounded display corners:")
                || line.startsWith("MobileGlues:")
                || line.startsWith("MobileGlues config source:")
                || line.startsWith("Mods:")
                || line.startsWith("Mod:")
                || line.startsWith("Java:")
                || line.startsWith("Instance overrides:")
                || line.startsWith("Detected:")
                || line.startsWith("Distant Horizons:")
                || line.startsWith("Distant Horizons + Iris:")
                || line.startsWith("Veil/ImGui:")
                || line.startsWith("Cleanroom:")
                || line.startsWith("Main class:")
                || line.startsWith("Network:")
                || line.startsWith("=====")
                || line.startsWith("----- ");
    }

    private static void flushJreDlopenSummary(@NonNull ArrayList<String> out, int count) {
        if (count <= 0) return;
        if (count == 1) {
            addLine(out, "Info: JRE native library loaded successfully.");
        } else {
            addLine(out, "Info: JRE native libraries loaded successfully (" + count + " entries collapsed).");
        }
    }

    private static void addLine(@NonNull ArrayList<String> out, @NonNull String line) {
        out.add(line);
    }

    private static void addBlankLine(@NonNull ArrayList<String> out) {
        if (out.isEmpty()) return;
        if (out.get(out.size() - 1).trim().isEmpty()) return;
        out.add("");
    }

    private static boolean isControlifyScanSpam(@NonNull String line) {
        return line.startsWith("ControlifySDL: scan ");
    }

    private static boolean isJreDlopenSuccess(@NonNull String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("d/jrelog")
                && lower.contains("dlopen")
                && lower.endsWith("success");
    }

    private static boolean isLwjglMismatchStart(@NonNull String line) {
        return line.contains("[LWJGL] [ERROR] Incompatible Java and native library versions detected.");
    }

    private static boolean isLwjglMismatchContinuation(@NonNull String line) {
        if (line.isEmpty()) return true;

        String lower = line.toLowerCase(Locale.ROOT);
        return lower.startsWith("possible reasons:")
                || lower.startsWith("possible solutions:")
                || lower.startsWith("a) ")
                || lower.startsWith("b) ")
                || lower.startsWith("sure the folder")
                || lower.startsWith("check the classpath")
                || lower.contains("-djava.library.path")
                || lower.contains("shared libraries of an older lwjgl version")
                || lower.contains("jar files of an older lwjgl version")
                || lower.contains("jar files of the same lwjgl version");
    }

    private static boolean isNarratorFliteStart(@NonNull String line) {
        return line.contains("Error while loading the narrator")
                || line.contains("Failed to load library flite")
                || line.contains("Unable to load library 'flite'")
                || line.contains("Native library (linux-aarch64/libflite.so) not found in resource path");
    }

    private static boolean isRendererSetupStart(@NonNull String line) {
        String lower = line.toLowerCase(Locale.ROOT);

        return lower.startsWith("initializing mobileglues")
                || lower.startsWith("initialising mobileglues")
                || lower.startsWith("initializing krypton wrapper")
                || lower.startsWith("initialising krypton wrapper")
                || lower.startsWith("initializing gl4es")
                || lower.startsWith("initialising gl4es")
                || lower.startsWith("initializing virgl")
                || lower.startsWith("initialising virgl")
                || lower.startsWith("initializing renderer")
                || lower.startsWith("initialising renderer");
    }

    private static boolean isMinecraftOrJvmOutputStart(@NonNull String line) {
        if (line.isEmpty()) return false;

        return line.matches("^\\[\\d{2}:\\d{2}:\\d{2}\\] \\[.+")
                || line.startsWith("--------- beginning of main")
                || line.startsWith("D/jrelog")
                || line.startsWith("E/jrelog")
                || line.startsWith("W/jrelog")
                || line.startsWith("I/jrelog")
                || line.startsWith("WARNING:")
                || line.matches("^\\d{4}-\\d{2}-\\d{2}T.+")
                || line.startsWith("Registered forkAndExec");
    }

    private static boolean isLikelyNewImportantLogLine(@NonNull String line) {
        if (line.isEmpty()) return false;

        return line.startsWith("[")
                || line.startsWith("EGLBridge:")
                || line.startsWith("OpenGL ES Version:")
                || line.startsWith("Registered forkAndExec")
                || line.startsWith("OSMDroid:")
                || line.startsWith("D/jrelog")
                || line.startsWith("E/jrelog")
                || line.startsWith("W/jrelog")
                || line.startsWith("WARNING:")
                || line.startsWith("Info:")
                || line.startsWith("Warning:")
                || line.startsWith("Error:")
                || line.startsWith("=====")
                || line.startsWith("----- ")
                || line.startsWith("VulkanMod mitigation:")
                || line.startsWith("Beryl/Vulkan shader path mitigation:")
                || line.startsWith("---------");
    }

    @NonNull
    private static String stripTrailingLineBreaks(@NonNull String text) {
        int end = text.length();
        while (end > 0) {
            char c = text.charAt(end - 1);
            if (c != '\n' && c != '\r') break;
            end--;
        }
        return end == text.length() ? text : text.substring(0, end);
    }
}
