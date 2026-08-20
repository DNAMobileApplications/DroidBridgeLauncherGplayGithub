/*
 * Clean-main rewrite pass: DroidBridge-owned dual-screen asset code.
 * Launcher-side generated model JSON helpers.
 *
 * This class intentionally returns normal old-style Minecraft model JSON. The existing
 * DualScreenControlsView JSON rasterizer can already consume that style after the caller
 * converts it through ModelDefinition.fromJson(...).
 */
package ca.dnamobile.droidbridgelauncher.dualscreen;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

final class DualScreenSpecialItemModelFactory {
    private DualScreenSpecialItemModelFactory() { }

    @NonNull
    static JSONObject buildModelJsonForSpecialLayer(@NonNull DualScreenItemDefinitionTranslator.Layer layer) throws Exception {
        String specialType = DualScreenItemDefinitionTranslator.normalizeType(layer.specialType);
        String textureId = DualScreenItemDefinitionTranslator.textureIdForSpecial(layer);
        if ("minecraft:bed".equals(specialType)) return buildBedModel(textureId);
        if ("minecraft:chest".equals(specialType)) return buildChestModel(textureId);
        if ("minecraft:shulker_box".equals(specialType)) return buildShulkerBoxModel(textureId);
        throw new IllegalArgumentException("Unsupported special item model: " + specialType);
    }

    @NonNull
    static JSONObject buildBedModel(@NonNull String bedTextureId) throws Exception {
        // This is the BetterBeds-style geometry converted into old-style model JSON so Android
        // can rasterize it. Texture points to the installed entity bed texture, for example
        // minecraft:entity/bed/black.
        JSONObject root = new JSONObject();
        root.put("ambientocclusion", false);
        root.put("texture_size", new JSONArray().put(64).put(64));
        root.put("textures", new JSONObject().put("particle", "minecraft:block/oak_planks").put("bed", bedTextureId));
        JSONArray elements = new JSONArray();
        elements.put(box(0, 3, 8, 16, 9, 24,
                faces(new Face("east", 0, 1.5, 1.5, 5.5, 270, "#bed"),
                        new Face("south", 1.5, 0, 5.5, 1.5, 180, "#bed"),
                        new Face("west", 5.5, 1.5, 7, 5.5, 90, "#bed"),
                        new Face("up", 1.5, 1.5, 5.5, 5.5, 180, "#bed"),
                        new Face("down", 7, 1.5, 11, 5.5, 0, "#bed"))));
        elements.put(box(0, 0, 21, 3, 3, 24,
                faces(new Face("north", 14.75, 0.75, 15.5, 1.5, 0, "#bed"),
                        new Face("east", 14, 0.75, 14.75, 1.5, 0, "#bed"),
                        new Face("south", 13.25, 0.75, 14, 1.5, 0, "#bed"),
                        new Face("west", 12.5, 0.75, 13.25, 1.5, 0, "#bed"),
                        new Face("down", 14, 0, 14.75, 0.75, 0, "#bed"))));
        elements.put(box(13, 0, 21, 16, 3, 24,
                faces(new Face("north", 14, 2.25, 14.75, 3, 0, "#bed"),
                        new Face("east", 13.25, 2.25, 14, 3, 0, "#bed"),
                        new Face("south", 12.5, 2.25, 13.25, 3, 0, "#bed"),
                        new Face("west", 14.75, 2.25, 15.5, 3, 0, "#bed"),
                        new Face("down", 14, 1.5, 14.75, 2.25, 0, "#bed"))));
        elements.put(box(0, 3, -8, 16, 9, 8,
                faces(new Face("north", 5.5, 5.5, 9.5, 7, 180, "#bed"),
                        new Face("east", 0, 7, 1.5, 11, 270, "#bed"),
                        new Face("west", 5.5, 7, 7, 11, 90, "#bed"),
                        new Face("up", 1.5, 7, 5.5, 11, 180, "#bed"),
                        new Face("down", 7, 7, 11, 11, 0, "#bed"))));
        elements.put(box(0, 0, -8, 3, 3, -5,
                faces(new Face("north", 12.5, 5.25, 13.25, 6, 0, "#bed"),
                        new Face("east", 14.75, 5.25, 15.5, 6, 0, "#bed"),
                        new Face("south", 14, 5.25, 14.75, 6, 0, "#bed"),
                        new Face("west", 13.25, 5.25, 14, 6, 0, "#bed"),
                        new Face("down", 14, 4.5, 14.75, 5.25, 0, "#bed"))));
        elements.put(box(13, 0, -8, 16, 3, -5,
                faces(new Face("north", 13.25, 3.75, 14, 4.5, 0, "#bed"),
                        new Face("east", 12.5, 3.75, 13.25, 4.5, 0, "#bed"),
                        new Face("south", 14.75, 3.75, 15.5, 4.5, 0, "#bed"),
                        new Face("west", 14, 3.75, 14.75, 4.5, 0, "#bed"),
                        new Face("down", 14, 3, 14.75, 3.75, 0, "#bed"))));
        root.put("elements", elements);
        root.put("display", guiDisplay(30, 160, 0, 0.25, 1.5, 0, 0.5325));
        return root;
    }

    @NonNull
    static JSONObject buildChestModel(@NonNull String chestTextureId) throws Exception {
        // Generated from the 26.x item definition special type minecraft:chest. The exact
        // texture comes from the installed game asset, for example minecraft:entity/chest/normal.
        JSONObject root = new JSONObject();
        root.put("ambientocclusion", false);
        root.put("texture_size", new JSONArray().put(64).put(64));
        root.put("textures", new JSONObject().put("chest", chestTextureId).put("particle", chestTextureId));
        JSONArray elements = new JSONArray();

        // 1.0.152: use vanilla chest ModelPart UV bands, converted from 64px sheet
        // coordinates to the Android renderer's 0..16 UV space. The 1.0.150 model used
        // the body top/bottom band for visible side faces, which sampled transparent
        // gutters and produced the hollow/holed chest look. Fix 1.0.152 also swaps
        // the lid/body UP and DOWN UVs; the visible top face is the second top-band
        // rectangle in Mojang's cuboid unwrap.
        // Body texOffs(0,19), dimensions 14x10x14:
        // sides: y 33..43, top/bottom: y 19..33.
        elements.put(box(1, 0, 1, 15, 10, 15,
                faces(new Face("north", 3.5, 8.25, 7, 10.75, 0, "#chest"),
                        new Face("south", 10.5, 8.25, 14, 10.75, 0, "#chest"),
                        new Face("west", 0, 8.25, 3.5, 10.75, 0, "#chest"),
                        new Face("east", 7, 8.25, 10.5, 10.75, 0, "#chest"),
                        new Face("up", 7, 4.75, 10.5, 8.25, 0, "#chest"),
                        new Face("down", 3.5, 4.75, 7, 8.25, 0, "#chest"))));
        // Lid texOffs(0,0), dimensions 14x5x14:
        // sides: y 14..19, top/bottom: y 0..14.
        elements.put(box(1, 10, 1, 15, 15, 15,
                faces(new Face("north", 3.5, 3.5, 7, 4.75, 0, "#chest"),
                        new Face("south", 10.5, 3.5, 14, 4.75, 0, "#chest"),
                        new Face("west", 0, 3.5, 3.5, 4.75, 0, "#chest"),
                        new Face("east", 7, 3.5, 10.5, 4.75, 0, "#chest"),
                        new Face("up", 7, 0, 10.5, 3.5, 0, "#chest"),
                        new Face("down", 3.5, 0, 7, 3.5, 0, "#chest"))));
        elements.put(box(7, 7, 0, 9, 11, 1,
                faces(new Face("north", 0.25, 0.25, 0.75, 1.25, 0, "#chest"),
                        new Face("south", 0.25, 0.25, 0.75, 1.25, 0, "#chest"),
                        new Face("west", 0.25, 0.25, 0.5, 1.25, 0, "#chest"),
                        new Face("east", 0.5, 0.25, 0.75, 1.25, 0, "#chest"),
                        new Face("up", 0.25, 0.25, 0.75, 0.5, 0, "#chest"),
                        new Face("down", 0.25, 1, 0.75, 1.25, 0, "#chest"))));
        root.put("elements", elements);
        root.put("display", guiDisplay(30, 225, 0, 0, 0, 0, 0.625));
        return root;
    }

    @NonNull
    static JSONObject buildShulkerBoxModel(@NonNull String shulkerTextureId) throws Exception {
        JSONObject root = new JSONObject();
        root.put("ambientocclusion", false);
        root.put("texture_size", new JSONArray().put(64).put(64));
        root.put("textures", new JSONObject().put("shulker", shulkerTextureId).put("particle", shulkerTextureId));
        JSONArray elements = new JSONArray();

        // 1.0.168: use the real vanilla ShulkerModel/ModelPart UV unwrap instead of
        // sampling small arbitrary 16x16 quadrants from the entity sheet.  The previous
        // generated model had the right cube pose, but the wrong UV bands, so the sides
        // missed the darker border/corner pixels that make the vanilla shulker-box icon
        // read as a solid cube.  Minecraft's shulker model uses:
        //   lid  texOffs(0, 0),  dimensions 16x12x16
        //   base texOffs(0, 28), dimensions 16x8x16
        // Converted to this renderer's 0..16 UV space from the 64x64 entity texture.

        // Base: texOffs(0,28), dimensions 16x8x16.
        // Side band is y=44..52, top/bottom band is y=28..44.
        elements.put(box(0, 0, 0, 16, 8, 16,
                faces(new Face("west", 0, 11, 4, 13, 0, "#shulker"),
                        new Face("north", 4, 11, 8, 13, 0, "#shulker"),
                        new Face("east", 8, 11, 12, 13, 0, "#shulker"),
                        new Face("south", 12, 11, 16, 13, 0, "#shulker"),
                        new Face("down", 4, 7, 8, 11, 0, "#shulker"),
                        new Face("up", 8, 7, 12, 11, 0, "#shulker"))));

        // Lid: texOffs(0,0), dimensions 16x12x16.
        // Side band is y=16..28, top/bottom band is y=0..16.
        elements.put(box(0, 8, 0, 16, 16, 16,
                faces(new Face("west", 0, 4, 4, 7, 0, "#shulker"),
                        new Face("north", 4, 4, 8, 7, 0, "#shulker"),
                        new Face("east", 8, 4, 12, 7, 0, "#shulker"),
                        new Face("south", 12, 4, 16, 7, 0, "#shulker"),
                        new Face("down", 4, 0, 8, 4, 0, "#shulker"),
                        new Face("up", 8, 0, 12, 4, 0, "#shulker"))));
        root.put("elements", elements);
        root.put("display", guiDisplay(30, 225, 0, 0, 0, 0, 0.625));
        return root;
    }

    @NonNull private static JSONObject box(double x1, double y1, double z1, double x2, double y2, double z2, @NonNull JSONObject faces) throws Exception {
        return new JSONObject().put("from", new JSONArray().put(x1).put(y1).put(z1))
                .put("to", new JSONArray().put(x2).put(y2).put(z2))
                .put("faces", faces);
    }

    @NonNull private static JSONObject faces(@NonNull Face... faces) throws Exception {
        JSONObject out = new JSONObject();
        for (Face f : faces) {
            JSONObject face = new JSONObject()
                    .put("uv", new JSONArray().put(f.u1).put(f.v1).put(f.u2).put(f.v2))
                    .put("texture", f.texture);
            if (f.rotation != 0) face.put("rotation", f.rotation);
            out.put(f.name, face);
        }
        return out;
    }

    @NonNull private static JSONObject guiDisplay(double rx, double ry, double rz, double tx, double ty, double tz, double scale) throws Exception {
        return new JSONObject().put("gui", new JSONObject()
                .put("rotation", new JSONArray().put(rx).put(ry).put(rz))
                .put("translation", new JSONArray().put(tx).put(ty).put(tz))
                .put("scale", new JSONArray().put(scale).put(scale).put(scale)));
    }

    private static final class Face {
        final String name;
        final double u1, v1, u2, v2;
        final int rotation;
        final String texture;
        Face(String name, double u1, double v1, double u2, double v2, int rotation, String texture) {
            this.name = name;
            this.u1 = u1;
            this.v1 = v1;
            this.u2 = u2;
            this.v2 = v2;
            this.rotation = rotation;
            this.texture = texture;
        }
    }
}
