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

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.renderer.MobileGluesConfigHelper;
import ca.dnamobile.droidbridgelauncher.renderer.RendererInterface;
import ca.dnamobile.droidbridgelauncher.runtime.Logger;
public final class SodiumMobileGluesShaderPatch {
    private static final String TAG = "SodiumMobileGluesPatch";

    // MobileGlues now handles the Sodium terrain path itself. Keep the patch
    // implementation available for a future regression, but never apply it.

    private static final String MARKER_ENTRY = "META-INF/droidbridge/sodium_mobileglues_0319_parity_v18";
    private static final List<String> OLD_MARKER_ENTRIES = Arrays.asList(
            "META-INF/droidbridge/sodium_mobileglues_glsl320_patch_v1",
            "META-INF/droidbridge/sodium_mobileglues_glsl320_patch_v2",
            "META-INF/droidbridge/sodium_mobileglues_glsl320_patch_v3",
            "META-INF/droidbridge/sodium_mobileglues_glsl320_patch_v4",
            "META-INF/droidbridge/sodium_mobileglues_glsl320_patch_v5",
            "META-INF/droidbridge/sodium_mobileglues_glsl320_patch_v6",
            "META-INF/droidbridge/sodium_mobileglues_glsl320_patch_v7",
            "META-INF/droidbridge/sodium_mobileglues_glsl320_patch_v8",
            "META-INF/droidbridge/sodium_mobileglues_inline_no_line_patch_v9",
            "META-INF/droidbridge/sodium_mobileglues_inline_no_line_v10",
            "META-INF/droidbridge/sodium_mobileglues_opaque_alpha_v11",
            "META-INF/droidbridge/sodium_mobileglues_opaque_surface_v12",
            "META-INF/droidbridge/sodium_mobileglues_culling_safe_v13",
            "META-INF/droidbridge/sodium_mobileglues_terrain_culling_mod_safety_v14",
            "META-INF/droidbridge/sodium_mobileglues_restore_working_v15",
            "META-INF/droidbridge/sodium_mobileglues_true_restore_v16",
            "META-INF/droidbridge/sodium_mobileglues_0319_parity_v17"
    );

    private static final String WORK_DIR = ".droidbridge/compat-backups/sodium-mobileglues";
    private static final String OLD_DISABLED_SUFFIX = ".disabled-by-droidbridge-mobileglues";
    private static final String SHADER_PREFIX = "assets/sodium/shaders/";

    private static final Set<String> SODIUM_LIKE_MOD_IDS = new LinkedHashSet<>(Arrays.asList(
            "sodium",
            "rubidium",
            "embeddium"
    ));

    private static final Set<String> SHADER_PIPELINE_MOD_IDS = new LinkedHashSet<>(Arrays.asList(
            "iris",
            "oculus"
    ));

    private static final String BLOCK_OPAQUE_VSH = "assets/sodium/shaders/blocks/block_layer_opaque.vsh";
    private static final String BLOCK_OPAQUE_FSH = "assets/sodium/shaders/blocks/block_layer_opaque.fsh";

    private static final Pattern VERSION_PATTERN = Pattern.compile("(?m)^\\s*#version\\s+([0-9]+)(?:\\s+core|\\s+es)?\\s*$");
    private static final Pattern FLOAT_SUFFIX_PATTERN = Pattern.compile("(?<![A-Za-z0-9_])(\\d+\\.\\d+)f\\b");

    private SodiumMobileGluesShaderPatch() {
    }

    private static boolean isPatchEnabled() {
        return false;
    }

    public static void prepare(
            @Nullable File gameDir,
            @Nullable String minecraftVersion,
            @Nullable RendererInterface renderer
    ) {
        if (gameDir == null) return;

        if (!isPatchEnabled()) {
            restoreRetiredPatchState(gameDir);
            return;
        }

        boolean mobileGluesRenderer = MobileGluesConfigHelper.isMobileGluesRenderer(renderer);

        appendBlankLogLine();

        try {
            List<File> modsDirs = getCandidateModsDirs(gameDir);
            boolean shaderPipelinePresent = containsAnyModId(modsDirs, SHADER_PIPELINE_MOD_IDS);
            boolean shouldPatch = mobileGluesRenderer && isMinecraft26_2(minecraftVersion);

            appendLog("Sodium MobileGlues patch: about to run on " + gameDir.getAbsolutePath()
                    + " version=" + safe(minecraftVersion)
                    + " renderer=" + rendererName(renderer)
                    + " shaderPipeline=" + shaderPipelinePresent
                    + " enabled=" + shouldPatch);
            if (mobileGluesRenderer && shaderPipelinePresent && !shouldPatch) {
                appendLog("Sodium MobileGlues patch: Iris/Oculus detected outside 26.2; not rewriting Forge Embeddium/Oculus terrain shaders. Restoring any previous DroidBridge terrain-shader rewrite and letting the MobileGlues safe draw profile handle this path.");
            } else if (shouldPatch && shaderPipelinePresent) {
                appendLog("Sodium MobileGlues patch: Iris/Oculus detected, keeping Sodium terrain shader rewrite enabled for 26.2 transparency fix");
            }

            boolean foundSodium = false;

            for (File modsDir : modsDirs) {
                appendLog("Sodium MobileGlues patch: scanning " + modsDir.getAbsolutePath() + " exists=" + modsDir.exists());
                restorePreviouslyDisabledModFamily(modsDir);

                File[] mods = modsDir.listFiles(file -> file.isFile()
                        && file.getName().toLowerCase(Locale.ROOT).endsWith(".jar"));
                if (mods == null || mods.length == 0) continue;

                for (File modJar : mods) {
                    if (!looksLikeSodiumJar(modJar)) continue;
                    foundSodium = true;

                    try {
                        if (!isSodiumLikeJar(modJar)) {
                            appendLog("Sodium MobileGlues patch: skipped filename match that is not Sodium/Embeddium/Rubidium: " + modJar.getName());
                            continue;
                        }

                        if (!shouldPatch) {
                            restoreIfPatched(gameDir, modJar);
                            continue;
                        }

                        if (!hasPatchTargets(modJar)) {
                            appendLog("Sodium MobileGlues patch: Sodium/Embeddium/Rubidium jar has no 26.2 terrain shader targets: " + modJar.getName());
                            continue;
                        }

                        if (isPatchedV2(modJar)) {
                            appendLog("Sodium MobileGlues patch: already patched with 0.3.19 parity v18 " + modJar.getAbsolutePath());
                            continue;
                        }

                        patchSodiumJar(gameDir, modJar);
                        appendLog("Sodium MobileGlues patch: patched successfully " + modJar.getAbsolutePath());
                    } catch (Throwable throwable) {
                        appendLog("Sodium MobileGlues patch: failed for " + modJar.getAbsolutePath() + ": " + throwable);
                        Log.e(TAG, "Failed Sodium MobileGlues patch for " + modJar.getAbsolutePath(), throwable);
                    }
                }
            }

            if (!foundSodium) {
                appendLog("Sodium MobileGlues patch: no Sodium/Embeddium/Rubidium jar found in candidate mod directories");
            }
        } finally {
            appendLog("Sodium MobileGlues patch: finished");
            appendBlankLogLine();
        }
    }


    /**
     * Removes modifications left by older DroidBridge builds without applying
     * any new shader rewrite. This path is normally gated by PreLaunchModScan,
     * so clean installations do not open or inspect Sodium jars.
     */
    private static void restoreRetiredPatchState(@NonNull File gameDir) {
        int restoredFiles = 0;
        boolean failed = false;

        for (File modsDir : getCandidateModsDirs(gameDir)) {
            File[] disabled = modsDir.listFiles(file -> file.isFile()
                    && file.getName().endsWith(OLD_DISABLED_SUFFIX));
            if (disabled != null) {
                for (File disabledJar : disabled) {
                    String restoredName = disabledJar.getName().substring(
                            0, disabledJar.getName().length() - OLD_DISABLED_SUFFIX.length());
                    File target = new File(modsDir, restoredName);
                    if (target.isFile()) {
                        failed = true;
                        appendLog("Warning: retired Sodium/MobileGlues cleanup kept "
                                + disabledJar.getName() + " because " + target.getName()
                                + " already exists");
                        continue;
                    }
                    if (disabledJar.renameTo(target)) {
                        restoredFiles++;
                    } else {
                        failed = true;
                        appendLog("Warning: retired Sodium/MobileGlues cleanup could not restore "
                                + disabledJar.getAbsolutePath());
                    }
                }
            }

            File[] mods = modsDir.listFiles(file -> file.isFile()
                    && file.getName().toLowerCase(Locale.ROOT).endsWith(".jar"));
            if (mods == null) continue;

            for (File modJar : mods) {
                if (!looksLikeSodiumJar(modJar)) continue;

                try {
                    if (!isAnyDroidBridgePatched(modJar)) continue;

                    File backup = new File(getWorkDir(gameDir), modJar.getName() + ".original");
                    if (!backup.isFile()) {
                        failed = true;
                        appendLog("Warning: retired Sodium/MobileGlues patch remains in "
                                + modJar.getName() + " because its original backup is missing");
                        continue;
                    }

                    copyFile(backup, modJar);
                    if (isAnyDroidBridgePatched(modJar)) {
                        throw new IOException("restored jar still contains a DroidBridge patch marker");
                    }
                    restoredFiles++;
                } catch (Throwable throwable) {
                    failed = true;
                    appendLog("Warning: retired Sodium/MobileGlues cleanup failed for "
                            + modJar.getName() + ": " + throwable);
                    Log.e(TAG, "Failed to retire Sodium MobileGlues patch for "
                            + modJar.getAbsolutePath(), throwable);
                }
            }
        }

        if (!failed && !hasRemainingRetiredPatchArtifacts(gameDir)) {
            deleteTree(getWorkDir(gameDir));
            deleteEmptyParentDirectories(getWorkDir(gameDir), gameDir);
        }

        if (restoredFiles > 0) {
            appendLog("Info: retired Sodium/MobileGlues shader patch removed; restored "
                    + restoredFiles + " original file(s)");
        }
    }

    private static boolean hasRemainingRetiredPatchArtifacts(@NonNull File gameDir) {
        for (File modsDir : getCandidateModsDirs(gameDir)) {
            File[] disabled = modsDir.listFiles(file -> file.isFile()
                    && file.getName().endsWith(OLD_DISABLED_SUFFIX));
            if (disabled != null && disabled.length > 0) return true;

            File[] mods = modsDir.listFiles(file -> file.isFile()
                    && file.getName().toLowerCase(Locale.ROOT).endsWith(".jar"));
            if (mods == null) continue;

            for (File modJar : mods) {
                if (!looksLikeSodiumJar(modJar)) continue;
                try {
                    if (isAnyDroidBridgePatched(modJar)) return true;
                } catch (Throwable ignored) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void deleteTree(@Nullable File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteTree(child);
                }
            }
        }
        if (!file.delete()) {
            Log.w(TAG, "Could not delete retired patch state: " + file.getAbsolutePath());
        }
    }

    private static void deleteEmptyParentDirectories(
            @NonNull File start,
            @NonNull File stopAt
    ) {
        File cursor = start.getParentFile();
        while (cursor != null && !cursor.equals(stopAt)) {
            File[] children = cursor.listFiles();
            if (children == null || children.length != 0 || !cursor.delete()) return;
            cursor = cursor.getParentFile();
        }
    }

    private static boolean containsAnyModId(@NonNull List<File> modsDirs, @NonNull Set<String> expectedIds) {
        for (File modsDir : modsDirs) {
            File[] mods = modsDir.listFiles(file -> file.isFile()
                    && file.getName().toLowerCase(Locale.ROOT).endsWith(".jar"));
            if (mods == null) continue;

            for (File modJar : mods) {
                try {
                    Set<String> ids = readModIds(modJar);
                    for (String expectedId : expectedIds) {
                        if (ids.contains(expectedId.toLowerCase(Locale.ROOT))) {
                            return true;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return false;
    }

    private static boolean isMinecraft26_2(@Nullable String minecraftVersion) {
        if (minecraftVersion == null) return false;
        String value = minecraftVersion.toLowerCase(Locale.ROOT);
        return value.equals("26.2")
                || value.startsWith("26.2-")
                || value.endsWith("-26.2")
                || value.contains("-26.2-")
                || value.contains("26.2 ")
                || value.contains("26.2+");
    }

    private static boolean looksLikeSodiumJar(@NonNull File jarFile) {
        String name = jarFile.getName().toLowerCase(Locale.ROOT);
        return name.contains("sodium")
                || name.contains("rubidium")
                || name.contains("embeddium");
    }

    private static boolean isSodiumLikeJar(@NonNull File jarFile) throws IOException {
        Set<String> ids = readModIds(jarFile);
        for (String id : SODIUM_LIKE_MOD_IDS) {
            if (ids.contains(id)) return true;
        }
        return false;
    }

    @NonNull
    private static Set<String> readModIds(@NonNull File jarFile) throws IOException {
        Set<String> ids = new LinkedHashSet<>();
        try (ZipFile zipFile = new ZipFile(jarFile)) {
            ZipEntry fabricModJson = zipFile.getEntry("fabric.mod.json");
            if (fabricModJson != null) {
                collectJsonIds(readText(zipFile, fabricModJson), ids);
            }

            ZipEntry modsToml = zipFile.getEntry("META-INF/mods.toml");
            if (modsToml != null) {
                collectTomlIds(readText(zipFile, modsToml), ids);
            }

            ZipEntry neoforgeModsToml = zipFile.getEntry("META-INF/neoforge.mods.toml");
            if (neoforgeModsToml != null) {
                collectTomlIds(readText(zipFile, neoforgeModsToml), ids);
            }
        }
        return ids;
    }

    private static void collectJsonIds(@NonNull String text, @NonNull Set<String> ids) {
        Matcher matcher = Pattern.compile("\\\"id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(text);
        while (matcher.find()) {
            ids.add(matcher.group(1).toLowerCase(Locale.ROOT));
        }
    }

    private static void collectTomlIds(@NonNull String text, @NonNull Set<String> ids) {
        Matcher matcher = Pattern.compile("(?m)^\\s*modId\\s*=\\s*\"([^\"]+)\"").matcher(text);
        while (matcher.find()) {
            ids.add(matcher.group(1).toLowerCase(Locale.ROOT));
        }
    }

    private static boolean hasPatchTargets(@NonNull File jarFile) throws IOException {
        try (ZipFile zipFile = new ZipFile(jarFile)) {
            return zipFile.getEntry(BLOCK_OPAQUE_VSH) != null && zipFile.getEntry(BLOCK_OPAQUE_FSH) != null;
        }
    }

    private static boolean isPatchedV2(@NonNull File jarFile) throws IOException {
        try (ZipFile zipFile = new ZipFile(jarFile)) {
            return zipFile.getEntry(MARKER_ENTRY) != null;
        }
    }

    private static boolean isAnyDroidBridgePatched(@NonNull File jarFile) throws IOException {
        try (ZipFile zipFile = new ZipFile(jarFile)) {
            if (zipFile.getEntry(MARKER_ENTRY) != null) return true;
            for (String oldMarker : OLD_MARKER_ENTRIES) {
                if (zipFile.getEntry(oldMarker) != null) return true;
            }
            return false;
        }
    }

    private static boolean hasOldPatchMarker(@NonNull File jarFile) throws IOException {
        try (ZipFile zipFile = new ZipFile(jarFile)) {
            for (String oldMarker : OLD_MARKER_ENTRIES) {
                if (zipFile.getEntry(oldMarker) != null) return true;
            }
            return false;
        }
    }

    private static void patchSodiumJar(@NonNull File gameDir, @NonNull File jarFile) throws IOException {
        File workDir = getWorkDir(gameDir);
        if (!workDir.exists() && !workDir.mkdirs()) {
            throw new IOException("Could not create Sodium patch backup directory: " + workDir.getAbsolutePath());
        }

        File backup = new File(workDir, jarFile.getName() + ".original");
        File tempFile = new File(workDir, jarFile.getName() + ".tmp");
        deleteIfExists(tempFile);

        if (!backup.isFile()) {
            copyFile(jarFile, backup);
            appendLog("Sodium MobileGlues patch: backup created " + backup.getAbsolutePath());
        } else {
            appendLog("Sodium MobileGlues patch: backup already exists " + backup.getAbsolutePath());
        }

        boolean oldPatchedJar = hasOldPatchMarker(jarFile);
        File patchBase = oldPatchedJar && backup.isFile() ? backup : jarFile;
        if (oldPatchedJar && backup.isFile()) {
            appendLog("Sodium MobileGlues patch: old marker found, rebuilding 0.3.19 parity v18 from original backup");
        }

        boolean changedAny = false;
        try (ZipFile zipFile = new ZipFile(patchBase);
             ZipOutputStream output = new ZipOutputStream(new FileOutputStream(tempFile))) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            byte[] buffer = new byte[8192];

            while (entries.hasMoreElements()) {
                ZipEntry inEntry = entries.nextElement();
                String name = inEntry.getName();

                if (MARKER_ENTRY.equals(name) || OLD_MARKER_ENTRIES.contains(name)) {
                    continue;
                }

                ZipEntry outEntry = cloneEntryMetadata(inEntry);
                output.putNextEntry(outEntry);

                if (!inEntry.isDirectory()) {
                    if (isSodiumShaderResource(name)) {
                        String original = readText(zipFile, inEntry);
                        String patched = patchShaderSource(name, original);
                        if (!patched.equals(original)) {
                            changedAny = true;
                            appendLog("Sodium MobileGlues patch: rewrote " + name);
                        }
                        output.write(patched.getBytes(StandardCharsets.UTF_8));
                    } else {
                        try (InputStream input = zipFile.getInputStream(inEntry)) {
                            int read;
                            while ((read = input.read(buffer)) != -1) {
                                output.write(buffer, 0, read);
                            }
                        }
                    }
                }

                output.closeEntry();
            }

            ZipEntry marker = new ZipEntry(MARKER_ENTRY);
            marker.setTime(System.currentTimeMillis());
            output.putNextEntry(marker);
            output.write(("DroidBridge Sodium MobileGlues 0.3.19 parity patch v18\n" + jarFile.getName()).getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            changedAny = true;
        }

        if (!changedAny) {
            deleteIfExists(tempFile);
            appendLog("Sodium MobileGlues patch: nothing changed for " + jarFile.getName());
            return;
        }

        replaceFile(tempFile, jarFile, backup);
        deleteIfExists(tempFile);
    }

    private static boolean isSodiumShaderResource(@NonNull String name) {
        if (!name.startsWith(SHADER_PREFIX)) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".vsh") || lower.endsWith(".fsh") || lower.endsWith(".glsl");
    }

    @NonNull
    private static String patchShaderSource(@NonNull String name, @NonNull String source) {
        if (BLOCK_OPAQUE_VSH.equals(name)) {
            return buildInlineBlockLayerOpaqueVsh();
        }
        if (BLOCK_OPAQUE_FSH.equals(name)) {
            return buildInlineBlockLayerOpaqueFsh();
        }

        String patched = source;

        Matcher matcher = VERSION_PATTERN.matcher(patched);
        if (matcher.find()) {
            patched = matcher.replaceFirst("#version 320 es\n"
                    + "#extension GL_EXT_texture_buffer : enable\n"
                    + "precision highp float;\n"
                    + "precision highp int;\n"
                    + "precision highp sampler2D;\n"
                    + "precision highp isamplerBuffer;");
        }

        // Desktop GLSL permits 0.5f style suffixes. GLES GLSL does not.
        patched = FLOAT_SUFFIX_PATTERN.matcher(patched).replaceAll("$1");

        // Strip source-name #line directives. Mali/MobileGlues rejects quoted filenames in GLES shaders.
        patched = stripQuotedLineDirectives(patched);

        // GLSL ES is stricter than many desktop GLSL compilers about uint -> float conversions.
        patched = patched.replace(
                "return _get_relative_chunk_coord(pos) * vec3(16.0);",
                "return vec3(_get_relative_chunk_coord(pos)) * vec3(16.0);");
        patched = patched.replace(
                "_vert_position = (_deinterleave_u20x3(a_Position) * VERTEX_SCALE) + VERTEX_OFFSET;",
                "_vert_position = (vec3(_deinterleave_u20x3(a_Position)) * VERTEX_SCALE) + VERTEX_OFFSET;");
        patched = patched.replace(
                "return ALPHA_CUTOFF[(material >> MATERIAL_ALPHA_CUTOFF_OFFSET) & 3u];",
                "return ALPHA_CUTOFF[int((material >> MATERIAL_ALPHA_CUTOFF_OFFSET) & 3u)];");
        patched = patched.replace(
                "const float[4] ALPHA_CUTOFF = float[4](0.0, 0.1, 0.5, 1.0);",
                "const float ALPHA_CUTOFF[4] = float[4](0.0, 0.1, 0.5, 1.0);");
        patched = patched.replace(
                "bvec2(a_TexCoord >> TEXTURE_BITS)",
                "notEqual((a_TexCoord >> uvec2(TEXTURE_BITS)), uvec2(0u))");

        return patched;
    }

    @NonNull
    private static String buildInlineBlockLayerOpaqueVsh() {
        return "#version 320 es\n"
                + "#extension GL_EXT_texture_buffer : enable\n"
                + "precision highp float;\n"
                + "precision highp int;\n"
                + "precision highp sampler2D;\n"
                + "precision highp isamplerBuffer;\n"
                + "\n"
                + "layout(std140) uniform u_Globals {\n"
                + "    mat4 u_ProjectionMatrix;\n"
                + "    mat4 u_ModelViewMatrix;\n"
                + "    vec4 u_FogColor;\n"
                + "    vec2 u_EnvironmentFog;\n"
                + "    vec2 u_RenderFog;\n"
                + "    vec2 u_TexelSize;\n"
                + "    vec2 u_TexCoordShrink;\n"
                + "    float u_FadePeriodInv;\n"
                + "    bool u_UseRGSS;\n"
                + "};\n"
                + "\n"
                + "const int FOG_SHAPE_SPHERICAL = 0;\n"
                + "const int FOG_SHAPE_CYLINDRICAL = 1;\n"
                + "\n"
                + "float linear_fog_value(float vertexDistance, float fogStart, float fogEnd) {\n"
                + "    if (vertexDistance <= fogStart) {\n"
                + "        return 0.0;\n"
                + "    } else if (vertexDistance >= fogEnd) {\n"
                + "        return 1.0;\n"
                + "    }\n"
                + "    return (vertexDistance - fogStart) / (fogEnd - fogStart);\n"
                + "}\n"
                + "\n"
                + "float total_fog_value(float sphericalVertexDistance, float cylindricalVertexDistance, float environmentalStart, float environmantalEnd, float renderDistanceStart, float renderDistanceEnd) {\n"
                + "    return max(linear_fog_value(sphericalVertexDistance, environmentalStart, environmantalEnd), linear_fog_value(cylindricalVertexDistance, renderDistanceStart, renderDistanceEnd));\n"
                + "}\n"
                + "\n"
                + "vec4 _linearFog(vec4 fragColor, vec2 fragDistance, vec4 fogColor, vec2 environmentFog, vec2 renderFog, float fadeFactor) {\n"
                + "#ifdef USE_FOG\n"
                + "    float fogValue = max(1.0 - fadeFactor, total_fog_value(fragDistance.y, fragDistance.x, environmentFog.x, environmentFog.y, renderFog.x, renderFog.y));\n"
                + "    return vec4(mix(fragColor.rgb, fogColor.rgb, fogValue * fogColor.a), fragColor.a);\n"
                + "#else\n"
                + "    return fragColor;\n"
                + "#endif\n"
                + "}\n"
                + "\n"
                + "vec2 getFragDistance(vec3 position) {\n"
                + "    return vec2(max(length(position.xz), abs(position.y)), length(position));\n"
                + "}\n"
                + "\n"
                + "vec3 _vert_position;\n"
                + "vec2 _vert_tex_diffuse_coord;\n"
                + "vec2 _vert_tex_diffuse_coord_bias;\n"
                + "vec2 _vert_tex_light_coord;\n"
                + "vec4 _vert_color;\n"
                + "uint _draw_id;\n"
                + "uint _material_params;\n"
                + "\n"
                + "#ifdef USE_VERTEX_COMPRESSION\n"
                + "const uint POSITION_BITS        = 20u;\n"
                + "const uint POSITION_MAX_COORD   = 1u << POSITION_BITS;\n"
                + "const uint POSITION_MAX_VALUE   = POSITION_MAX_COORD - 1u;\n"
                + "const uint TEXTURE_BITS         = 15u;\n"
                + "const uint TEXTURE_MAX_COORD    = 1u << TEXTURE_BITS;\n"
                + "const uint TEXTURE_MAX_VALUE    = TEXTURE_MAX_COORD - 1u;\n"
                + "const float VERTEX_SCALE = 32.0 / float(POSITION_MAX_COORD);\n"
                + "const float VERTEX_OFFSET = -8.0;\n"
                + "in uvec2 a_Position;\n"
                + "in vec4 a_Color;\n"
                + "in uvec2 a_TexCoord;\n"
                + "in uvec4 a_LightAndData;\n"
                + "uvec3 _deinterleave_u20x3(uvec2 data) {\n"
                + "    uvec3 hi = (uvec3(data.x) >> uvec3(0u, 10u, 20u)) & uvec3(0x3FFu);\n"
                + "    uvec3 lo = (uvec3(data.y) >> uvec3(0u, 10u, 20u)) & uvec3(0x3FFu);\n"
                + "    return (hi << uvec3(10u)) | lo;\n"
                + "}\n"
                + "vec2 _get_texcoord() {\n"
                + "    return vec2(a_TexCoord & uvec2(TEXTURE_MAX_VALUE)) / float(TEXTURE_MAX_COORD);\n"
                + "}\n"
                + "vec2 _get_texcoord_bias() {\n"
                + "    return mix(vec2(-1.0), vec2(1.0), notEqual((a_TexCoord >> uvec2(TEXTURE_BITS)), uvec2(0u)));\n"
                + "}\n"
                + "void _vert_init() {\n"
                + "    _vert_position = (vec3(_deinterleave_u20x3(a_Position)) * VERTEX_SCALE) + VERTEX_OFFSET;\n"
                + "    _vert_color = a_Color;\n"
                + "    _vert_tex_diffuse_coord = _get_texcoord();\n"
                + "    _vert_tex_diffuse_coord_bias = _get_texcoord_bias();\n"
                + "    _vert_tex_light_coord = vec2(a_LightAndData.xy) / vec2(256.0);\n"
                + "    _material_params = a_LightAndData[2];\n"
                + "    _draw_id = a_LightAndData[3];\n"
                + "}\n"
                + "#else\n"
                + "#error Vertex compression must be enabled\n"
                + "#endif\n"
                + "\n"
                + "out vec4 v_Color;\n"
                + "out vec2 v_TexCoord;\n"
                + "#ifdef USE_FOG\n"
                + "out vec2 v_FragDistance;\n"
                + "out float fadeFactor;\n"
                + "#endif\n"
                + "uniform isamplerBuffer u_SectionTimeInfo;\n"
                + "#ifdef VULKAN\n"
                + "layout(push_constant) uniform PC {\n"
                + "    vec3 u_RegionOffset;\n"
                + "    int u_CurrentTime;\n"
                + "    uint u_RegionID;\n"
                + "};\n"
                + "#else\n"
                + "uniform vec3 u_RegionOffset;\n"
                + "uniform int u_CurrentTime;\n"
                + "uniform uint u_RegionID;\n"
                + "#endif\n"
                + "uniform sampler2D u_LightTex;\n"
                + "uvec3 _get_relative_chunk_coord(uint pos) {\n"
                + "    return uvec3(pos) >> uvec3(5u, 0u, 2u) & uvec3(7u, 3u, 7u);\n"
                + "}\n"
                + "vec3 _get_draw_translation(uint pos) {\n"
                + "    return vec3(_get_relative_chunk_coord(pos)) * vec3(16.0);\n"
                + "}\n"
                + "void main() {\n"
                + "    _vert_init();\n"
                + "    vec3 translation = u_RegionOffset + _get_draw_translation(_draw_id);\n"
                + "    vec3 position = _vert_position + translation;\n"
                + "#ifdef USE_FOG\n"
                + "    v_FragDistance = getFragDistance(position);\n"
                + "    int chunkId = int(_draw_id);\n"
                + "    int chunkFade = texelFetch(u_SectionTimeInfo, int((u_RegionID * 256u) + uint(chunkId))).r;\n"
                + "    int fadeTime = u_CurrentTime - chunkFade;\n"
                + "    float elapsed = float(fadeTime);\n"
                + "    float fade = clamp(float(u_CurrentTime - chunkFade) * u_FadePeriodInv, 0.0, 1.0);\n"
                + "    fadeFactor = (chunkFade < 0) ? 1.0 : fade;\n"
                + "#endif\n"
                + "    gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0);\n"
                + "    v_Color = _vert_color * texture(u_LightTex, _vert_tex_light_coord);\n"
                + "    v_TexCoord = (_vert_tex_diffuse_coord_bias * u_TexCoordShrink) + _vert_tex_diffuse_coord;\n"
                + "}\n";
    }

    @NonNull
    private static String buildInlineBlockLayerOpaqueFsh() {
        return "#version 320 es\n"
                + "#extension GL_EXT_texture_buffer : enable\n"
                + "precision highp float;\n"
                + "precision highp int;\n"
                + "precision highp sampler2D;\n"
                + "precision highp isamplerBuffer;\n"
                + "\n"
                + "layout(std140) uniform u_Globals {\n"
                + "    mat4 u_ProjectionMatrix;\n"
                + "    mat4 u_ModelViewMatrix;\n"
                + "    vec4 u_FogColor;\n"
                + "    vec2 u_EnvironmentFog;\n"
                + "    vec2 u_RenderFog;\n"
                + "    vec2 u_TexelSize;\n"
                + "    vec2 u_TexCoordShrink;\n"
                + "    float u_FadePeriodInv;\n"
                + "    bool u_UseRGSS;\n"
                + "};\n"
                + "\n"
                + "const int FOG_SHAPE_SPHERICAL = 0;\n"
                + "const int FOG_SHAPE_CYLINDRICAL = 1;\n"
                + "float linear_fog_value(float vertexDistance, float fogStart, float fogEnd) {\n"
                + "    if (vertexDistance <= fogStart) { return 0.0; }\n"
                + "    else if (vertexDistance >= fogEnd) { return 1.0; }\n"
                + "    return (vertexDistance - fogStart) / (fogEnd - fogStart);\n"
                + "}\n"
                + "float total_fog_value(float sphericalVertexDistance, float cylindricalVertexDistance, float environmentalStart, float environmantalEnd, float renderDistanceStart, float renderDistanceEnd) {\n"
                + "    return max(linear_fog_value(sphericalVertexDistance, environmentalStart, environmantalEnd), linear_fog_value(cylindricalVertexDistance, renderDistanceStart, renderDistanceEnd));\n"
                + "}\n"
                + "vec4 _linearFog(vec4 fragColor, vec2 fragDistance, vec4 fogColor, vec2 environmentFog, vec2 renderFog, float fadeFactor) {\n"
                + "#ifdef USE_FOG\n"
                + "    float fogValue = max(1.0 - fadeFactor, total_fog_value(fragDistance.y, fragDistance.x, environmentFog.x, environmentFog.y, renderFog.x, renderFog.y));\n"
                + "    return vec4(mix(fragColor.rgb, fogColor.rgb, fogValue * fogColor.a), fragColor.a);\n"
                + "#else\n"
                + "    return fragColor;\n"
                + "#endif\n"
                + "}\n"
                + "vec2 getFragDistance(vec3 position) {\n"
                + "    return vec2(max(length(position.xz), abs(position.y)), length(position));\n"
                + "}\n"
                + "const uint MATERIAL_USE_MIP_OFFSET = 0u;\n"
                + "const uint MATERIAL_ALPHA_CUTOFF_OFFSET = 1u;\n"
                + "const float ALPHA_CUTOFF[4] = float[4](0.0, 0.1, 0.5, 1.0);\n"
                + "bool _material_use_mips(uint material) {\n"
                + "    return ((material >> MATERIAL_USE_MIP_OFFSET) & 1u) != 0u;\n"
                + "}\n"
                + "float _material_alpha_cutoff(uint material) {\n"
                + "    return ALPHA_CUTOFF[int((material >> MATERIAL_ALPHA_CUTOFF_OFFSET) & 3u)];\n"
                + "}\n"
                + "in vec4 v_Color;\n"
                + "in vec2 v_TexCoord;\n"
                + "in vec2 v_FragDistance;\n"
                + "in float fadeFactor;\n"
                + "uniform sampler2D u_BlockTex;\n"
                + "out vec4 fragColor;\n"
                + "vec4 sampleNearest(sampler2D source, vec2 uv, vec2 pixelSize, vec2 du, vec2 dv, vec2 texelScreenSize) {\n"
                + "    vec2 uvTexelCoords = uv / pixelSize;\n"
                + "    vec2 texelCenter = round(uvTexelCoords) - 0.5;\n"
                + "    vec2 texelOffset = uvTexelCoords - texelCenter;\n"
                + "    texelOffset = (texelOffset - 0.5) * pixelSize / texelScreenSize + 0.5;\n"
                + "    texelOffset = clamp(texelOffset, 0.0, 1.0);\n"
                + "    uv = (texelCenter + texelOffset) * pixelSize;\n"
                + "    return textureGrad(source, uv, du, dv);\n"
                + "}\n"
                + "vec4 sampleNearest(sampler2D source, vec2 uv, vec2 pixelSize) {\n"
                + "    vec2 du = dFdx(uv);\n"
                + "    vec2 dv = dFdy(uv);\n"
                + "    vec2 texelScreenSize = sqrt(du * du + dv * dv);\n"
                + "    return sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);\n"
                + "}\n"
                + "vec4 sampleRGSS(sampler2D source, vec2 uv, vec2 pixelSize) {\n"
                + "    vec2 du = dFdx(uv);\n"
                + "    vec2 dv = dFdy(uv);\n"
                + "    vec2 texelScreenSize = sqrt(du * du + dv * dv);\n"
                + "    float maxTexelSize = max(texelScreenSize.x, texelScreenSize.y);\n"
                + "    float minPixelSize = min(pixelSize.x, pixelSize.y);\n"
                + "    float transitionStart = minPixelSize * 1.0;\n"
                + "    float transitionEnd = minPixelSize * 2.0;\n"
                + "    float blendFactor = smoothstep(transitionStart, transitionEnd, maxTexelSize);\n"
                + "    float duLength = length(du);\n"
                + "    float dvLength = length(dv);\n"
                + "    float minDerivative = min(duLength, dvLength);\n"
                + "    float maxDerivative = max(duLength, dvLength);\n"
                + "    float effectiveDerivative = sqrt(minDerivative * maxDerivative);\n"
                + "    float mipLevelExact = max(0.0, log2(effectiveDerivative / minPixelSize));\n"
                + "    const vec2 offsets[4] = vec2[4](\n"
                + "        vec2(0.125, 0.375),\n"
                + "        vec2(-0.125, -0.375),\n"
                + "        vec2(0.375, -0.125),\n"
                + "        vec2(-0.375, 0.125)\n"
                + "    );\n"
                + "    vec4 rgssColor = vec4(0.0);\n"
                + "    for (int i = 0; i < 4; ++i) {\n"
                + "        vec2 sampleUV = uv + offsets[i] * pixelSize;\n"
                + "        rgssColor += textureLod(source, sampleUV, mipLevelExact);\n"
                + "    }\n"
                + "    rgssColor *= 0.25;\n"
                + "    vec4 nearestColor = sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);\n"
                + "    return mix(nearestColor, rgssColor, blendFactor);\n"
                + "}\n"
                + "void main() {\n"
                + "    vec4 color = u_UseRGSS ? sampleRGSS(u_BlockTex, v_TexCoord, u_TexelSize) : sampleNearest(u_BlockTex, v_TexCoord, u_TexelSize);\n"
                + "    color *= v_Color;\n"
                + "#ifdef ALPHA_CUTOUT\n"
                + "    if (color.a < ALPHA_CUTOUT) { discard; }\n"
                + "#endif\n"
                + "    fragColor = _linearFog(color, v_FragDistance, u_FogColor, u_EnvironmentFog, u_RenderFog, fadeFactor);\n"
                + "}\n";
    }

    @NonNull
    private static String stripQuotedLineDirectives(@NonNull String source) {
        return source.replaceAll("(?m)^\\s*#line\\s+([0-9]+)\\s+\\\"[^\\\"]*\\\"\\s*$", "#line $1");
    }

    private static void restorePreviouslyDisabledModFamily(@NonNull File modsDir) {
        File[] disabled = modsDir.listFiles(file -> file.isFile()
                && file.getName().endsWith(OLD_DISABLED_SUFFIX));
        if (disabled == null || disabled.length == 0) return;

        for (File disabledJar : disabled) {
            String restoredName = disabledJar.getName().substring(0,
                    disabledJar.getName().length() - OLD_DISABLED_SUFFIX.length());
            File target = new File(modsDir, restoredName);
            if (target.isFile()) {
                appendLog("Sodium MobileGlues patch: not restoring previously disabled jar because target exists: " + target.getName());
                continue;
            }
            if (disabledJar.renameTo(target)) {
                appendLog("Sodium MobileGlues patch: restored previously disabled jar: " + target.getName());
            } else {
                appendLog("Sodium MobileGlues patch: failed to restore previously disabled jar: " + disabledJar.getName());
            }
        }
    }

    private static void restoreIfPatched(@NonNull File gameDir, @NonNull File jarFile) throws IOException {
        if (!isAnyDroidBridgePatched(jarFile)) return;

        File backup = new File(getWorkDir(gameDir), jarFile.getName() + ".original");
        if (!backup.isFile()) {
            appendLog("Sodium MobileGlues patch: patched Sodium exists but no backup found for restore: " + jarFile.getName());
            return;
        }

        appendLog("Sodium MobileGlues patch: renderer/version no longer requires patch, restoring " + jarFile.getName());
        copyFile(backup, jarFile);
        appendLog("Sodium MobileGlues patch: restored original Sodium jar " + jarFile.getAbsolutePath());
    }

    @NonNull
    private static File getWorkDir(@NonNull File gameDir) {
        return new File(gameDir, WORK_DIR);
    }

    @NonNull
    private static ZipEntry cloneEntryMetadata(@NonNull ZipEntry inEntry) {
        ZipEntry outEntry = new ZipEntry(inEntry.getName());
        outEntry.setTime(inEntry.getTime());
        return outEntry;
    }

    private static void replaceFile(
            @NonNull File replacement,
            @NonNull File target,
            @NonNull File backup
    ) throws IOException {
        File targetParent = target.getParentFile();
        if (targetParent == null) {
            throw new IOException("Could not resolve Sodium jar parent: " + target.getAbsolutePath());
        }

        File oldFile = new File(targetParent, target.getName() + ".droidbridge_old");
        deleteIfExists(oldFile);

        if (!target.renameTo(oldFile)) {
            copyFile(target, oldFile);
            if (!target.delete()) {
                throw new IOException("Could not move original Sodium jar aside: " + target.getAbsolutePath());
            }
        }

        boolean replaced = replacement.renameTo(target);
        if (!replaced) {
            copyFile(replacement, target);
            replaced = target.isFile() && target.length() > 0L;
        }

        if (!replaced) {
            copyFile(backup, target);
            throw new IOException("Could not replace Sodium jar with patched copy: " + target.getAbsolutePath());
        }

        deleteIfExists(oldFile);
    }

    @NonNull
    private static List<File> getCandidateModsDirs(@NonNull File gameDir) {
        Set<File> dirs = new LinkedHashSet<>();

        dirs.add(new File(gameDir, "mods"));

        File parent = gameDir.getParentFile();
        if (parent != null) dirs.add(new File(parent, "mods"));

        File minecraftRoot = findMinecraftRoot(gameDir);
        if (minecraftRoot != null) dirs.add(new File(minecraftRoot, "mods"));

        return new ArrayList<>(dirs);
    }

    @Nullable
    private static File findMinecraftRoot(@Nullable File start) {
        File cursor = start;
        while (cursor != null) {
            if (".minecraft".equals(cursor.getName())) return cursor;
            cursor = cursor.getParentFile();
        }
        return null;
    }

    @NonNull
    private static String readText(@NonNull ZipFile zipFile, @NonNull ZipEntry entry) throws IOException {
        try (InputStream input = zipFile.getInputStream(entry);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void copyFile(@NonNull File source, @NonNull File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create target directory: " + parent.getAbsolutePath());
        }

        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
    }

    private static void deleteIfExists(@NonNull File file) {
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "Could not delete temporary file: " + file.getAbsolutePath());
        }
    }

    @NonNull
    private static String rendererName(@Nullable RendererInterface renderer) {
        if (renderer == null) return "<none>";
        return renderer.getRendererName() + "/" + renderer.getRendererId() + "/" + renderer.getUniqueIdentifier();
    }

    @NonNull
    private static String safe(@Nullable String value) {
        return value == null ? "<null>" : value;
    }

    private static void appendLog(@NonNull String message) {
        try {
            Logger.appendToLog(stripTrailingLineBreaks(message));
        } catch (Throwable ignored) {
            Logging.i(TAG, message);
        }
    }

    private static void appendBlankLogLine() {
        try {
            Logger.appendToLog("");
        } catch (Throwable ignored) {
            Logging.i(TAG, "");
        }
    }

    @NonNull
    private static String stripTrailingLineBreaks(@NonNull String message) {
        int end = message.length();
        while (end > 0) {
            char c = message.charAt(end - 1);
            if (c != '\n' && c != '\r') break;
            end--;
        }
        return end == message.length() ? message : message.substring(0, end);
    }
}
