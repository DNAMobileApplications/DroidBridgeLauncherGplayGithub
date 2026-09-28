/*
 * Clean-main rewrite pass: DroidBridge-owned dual-screen asset code.
 * DroidBridge dual-screen Android HUD asset translator.
 *
 * Launcher-side only. Do NOT package this class into the Fabric/Minecraft mod jar.
 * This reads installed Minecraft 1.21.5+/26.x item definition JSON files from
 * assets/<namespace>/items/*.json and converts them into a small render plan that
 * DualScreenControlsView can turn into Android Canvas/model output.
 */
package ca.dnamobile.droidbridgelauncher.dualscreen;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

final class DualScreenItemDefinitionTranslator {
    interface AssetResolver {
        @Nullable File resolveAssetFile(@NonNull String relativePath);
    }

    static final class RenderPlan {
        @NonNull final ArrayList<Layer> layers = new ArrayList<>();
        @NonNull String source = "";
        boolean isEmpty() { return layers.isEmpty(); }
    }

    static final class Layer {
        static final int KIND_MODEL = 1;
        static final int KIND_SPECIAL = 2;

        int kind;
        @NonNull String modelId = "";       // minecraft:item/foo or minecraft:block/foo
        @NonNull String baseModelId = "";   // minecraft:item/chest, minecraft:item/black_bed, etc.
        @NonNull String specialType = "";   // minecraft:chest, minecraft:bed, minecraft:shulker_box, ...
        @NonNull String texture = "";       // minecraft:normal, minecraft:black, etc.
        @NonNull String color = "";         // banner dye color for minecraft:banner specials
        @NonNull String part = "";          // head/foot for bed, empty otherwise
        @NonNull Transform transform = Transform.identity();
    }

    static final class Transform {
        @NonNull final float[] leftRotation;
        @NonNull final float[] rightRotation;
        @NonNull final float[] scale;
        @NonNull final float[] translation;

        private Transform(@NonNull float[] leftRotation,
                          @NonNull float[] rightRotation,
                          @NonNull float[] scale,
                          @NonNull float[] translation) {
            this.leftRotation = leftRotation;
            this.rightRotation = rightRotation;
            this.scale = scale;
            this.translation = translation;
        }

        @NonNull static Transform identity() {
            return new Transform(
                    new float[] {0f, 0f, 0f, 1f},
                    new float[] {0f, 0f, 0f, 1f},
                    new float[] {1f, 1f, 1f},
                    new float[] {0f, 0f, 0f}
            );
        }

        @NonNull static Transform fromJson(@Nullable JSONObject json) {
            if (json == null) return identity();
            return new Transform(
                    floatArray(json.optJSONArray("left_rotation"), new float[] {0f, 0f, 0f, 1f}),
                    floatArray(json.optJSONArray("right_rotation"), new float[] {0f, 0f, 0f, 1f}),
                    floatArray(json.optJSONArray("scale"), new float[] {1f, 1f, 1f}),
                    floatArray(json.optJSONArray("translation"), new float[] {0f, 0f, 0f})
            );
        }
    }

    private DualScreenItemDefinitionTranslator() { }

    @Nullable
    static RenderPlan resolveInstalledItemDefinition(@NonNull String itemId,
                                                     @NonNull AssetResolver resolver,
                                                     long nowMillis) {
        File file = resolveItemDefinitionFile(itemId, resolver);
        if (file == null || !file.isFile() || file.length() <= 0L) return null;
        try {
            JSONObject root = readJson(file);
            JSONObject modelNode = root.optJSONObject("model");
            if (modelNode == null) return null;
            RenderPlan plan = new RenderPlan();
            plan.source = file.getAbsolutePath() + "#" + file.lastModified() + "#" + file.length();
            addNodeToPlan(plan, modelNode, Transform.identity(), nowMillis);
            return plan.isEmpty() ? null : plan;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private static File resolveItemDefinitionFile(@NonNull String itemId, @NonNull AssetResolver resolver) {
        ResourceLocation id = ResourceLocation.parse(itemId);
        if (id.path.length() == 0) return null;
        File exact = resolver.resolveAssetFile("assets/" + id.namespace + "/items/" + id.path + ".json");
        if (exact != null && exact.isFile()) return exact;
        String flattened = id.path.replace('/', '_');
        if (!flattened.equals(id.path)) {
            File flat = resolver.resolveAssetFile("assets/" + id.namespace + "/items/" + flattened + ".json");
            if (flat != null && flat.isFile()) return flat;
        }
        return null;
    }

    private static void addNodeToPlan(@NonNull RenderPlan plan,
                                      @Nullable JSONObject node,
                                      @NonNull Transform inheritedTransform,
                                      long nowMillis) {
        if (node == null) return;
        String type = normalizeType(node.optString("type", "minecraft:model"));

        if ("minecraft:model".equals(type)) {
            String model = node.optString("model", "").trim();
            if (model.length() == 0) return;
            Layer layer = new Layer();
            layer.kind = Layer.KIND_MODEL;
            layer.modelId = model;
            layer.transform = Transform.fromJson(node.optJSONObject("transformation"));
            if (node.optJSONObject("transformation") == null) layer.transform = inheritedTransform;
            plan.layers.add(layer);
            return;
        }

        if ("minecraft:special".equals(type)) {
            JSONObject special = node.optJSONObject("model");
            if (special == null) return;
            Layer layer = new Layer();
            layer.kind = Layer.KIND_SPECIAL;
            layer.baseModelId = node.optString("base", "").trim();
            layer.specialType = normalizeType(special.optString("type", ""));
            layer.texture = special.optString("texture", "").trim();
            layer.color = special.optString("color", "").trim();
            layer.part = special.optString("part", "").trim();
            layer.transform = Transform.fromJson(node.optJSONObject("transformation"));
            if (node.optJSONObject("transformation") == null) layer.transform = inheritedTransform;
            plan.layers.add(layer);
            return;
        }

        if ("minecraft:composite".equals(type)) {
            JSONArray models = node.optJSONArray("models");
            if (models == null) return;
            for (int i = 0; i < models.length(); i++) {
                addNodeToPlan(plan, models.optJSONObject(i), inheritedTransform, nowMillis);
            }
            return;
        }

        if ("minecraft:select".equals(type)) {
            JSONObject selected = selectCase(node, nowMillis);
            if (selected == null) selected = node.optJSONObject("fallback");
            if (selected == null) selected = firstCaseModel(node.optJSONArray("cases"));
            addNodeToPlan(plan, selected, inheritedTransform, nowMillis);
            return;
        }

        if ("minecraft:condition".equals(type)) {
            // The bottom HUD does not have every transient ItemStack property available.
            // Prefer the stable false branch, then true, so condition-based definitions
            // still produce a real icon rather than an empty slot.
            JSONObject selected = node.optJSONObject("on_false");
            if (selected == null) selected = node.optJSONObject("on_true");
            addNodeToPlan(plan, selected, inheritedTransform, nowMillis);
            return;
        }

        if ("minecraft:range_dispatch".equals(type)) {
            JSONObject selected = node.optJSONObject("fallback");
            if (selected == null) {
                JSONArray entries = node.optJSONArray("entries");
                if (entries != null && entries.length() > 0) {
                    JSONObject entry = entries.optJSONObject(0);
                    if (entry != null) selected = entry.optJSONObject("model");
                }
            }
            addNodeToPlan(plan, selected, inheritedTransform, nowMillis);
            return;
        }

        // Future/pack-defined wrappers frequently keep their usable model in one of these
        // conventional fields. Following them is safe and keeps unknown item-definition
        // types from becoming a blank hotbar slot.
        JSONObject fallback = node.optJSONObject("fallback");
        if (fallback == null) fallback = node.optJSONObject("model");
        if (fallback == null) fallback = node.optJSONObject("on_false");
        if (fallback == null) fallback = node.optJSONObject("on_true");
        if (fallback != null && fallback != node) {
            addNodeToPlan(plan, fallback, inheritedTransform, nowMillis);
        }
    }

    @Nullable
    private static JSONObject firstCaseModel(@Nullable JSONArray cases) {
        if (cases == null) return null;
        for (int i = 0; i < cases.length(); i++) {
            JSONObject c = cases.optJSONObject(i);
            if (c == null) continue;
            JSONObject model = c.optJSONObject("model");
            if (model != null) return model;
        }
        return null;
    }

    @Nullable
    private static JSONObject selectCase(@NonNull JSONObject node, long nowMillis) {
        String property = normalizeType(node.optString("property", ""));
        if (!"minecraft:local_time".equals(property)) return null;
        String pattern = node.optString("pattern", "MM-dd");
        String current;
        try {
            current = new SimpleDateFormat(pattern, Locale.US).format(new Date(nowMillis));
        } catch (Throwable ignored) {
            current = new SimpleDateFormat("MM-dd", Locale.US).format(new Date(nowMillis));
        }
        JSONArray cases = node.optJSONArray("cases");
        if (cases == null) return null;
        for (int i = 0; i < cases.length(); i++) {
            JSONObject c = cases.optJSONObject(i);
            if (c == null) continue;
            JSONArray when = c.optJSONArray("when");
            if (when == null) continue;
            for (int w = 0; w < when.length(); w++) {
                if (current.equals(when.optString(w, ""))) return c.optJSONObject("model");
            }
        }
        return null;
    }

    @NonNull
    static String textureIdForSpecial(@NonNull Layer layer) {
        String type = normalizeType(layer.specialType);
        if ("minecraft:banner".equals(type)) {
            return "minecraft:entity/banner/base";
        }
        if ("minecraft:decorated_pot".equals(type)) {
            return "minecraft:entity/decorated_pot/decorated_pot_side";
        }
        String texture = layer.texture.trim();
        if (texture.length() == 0) return "";
        ResourceLocation id = ResourceLocation.parse(texture);

        if ("minecraft:bed".equals(type)) {
            // Item definition says texture=minecraft:black, but the real installed asset is
            // assets/minecraft/textures/entity/bed/black.png.
            return id.namespace + ":entity/bed/" + id.path;
        }
        if ("minecraft:chest".equals(type)) {
            // normal/trapped/christmas/ender are stored as chest entity sheets.
            return id.namespace + ":entity/chest/" + id.path;
        }
        if ("minecraft:shulker_box".equals(type)) {
            return id.namespace + ":entity/shulker/shulker_" + id.path;
        }
        return id.namespace + ":" + id.path;
    }

    @NonNull
    static String normalizeType(@Nullable String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (value.length() == 0) return "";
        return value.indexOf(':') >= 0 ? value : "minecraft:" + value;
    }

    @NonNull
    private static JSONObject readJson(@NonNull File file) throws Exception {
        return new JSONObject(new String(readFileBytes(file), StandardCharsets.UTF_8));
    }

    @NonNull
    private static byte[] readFileBytes(@NonNull File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, (int) Math.min(file.length(), 1024 * 1024)))) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
            return out.toByteArray();
        }
    }

    @NonNull
    private static float[] floatArray(@Nullable JSONArray array, @NonNull float[] fallback) {
        if (array == null || array.length() <= 0) return fallback.clone();
        float[] out = fallback.clone();
        int limit = Math.min(array.length(), out.length);
        for (int i = 0; i < limit; i++) out[i] = (float) array.optDouble(i, out[i]);
        return out;
    }

    private static final class ResourceLocation {
        @NonNull final String namespace;
        @NonNull final String path;

        private ResourceLocation(@NonNull String namespace, @NonNull String path) {
            this.namespace = namespace;
            this.path = path;
        }

        @NonNull static ResourceLocation parse(@NonNull String raw) {
            String value = raw.trim().toLowerCase(Locale.ROOT);
            String namespace = "minecraft";
            String path = value;
            int colon = value.indexOf(':');
            if (colon >= 0) {
                namespace = value.substring(0, colon);
                path = value.substring(colon + 1);
            }
            if (path.startsWith("items/")) path = path.substring("items/".length());
            if (path.endsWith(".json")) path = path.substring(0, path.length() - 5);
            return new ResourceLocation(namespace, path);
        }
    }
}
