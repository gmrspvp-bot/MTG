package mtg3d;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.loader.G3dModelLoader;
import com.badlogic.gdx.graphics.g3d.loader.ObjLoader;
import com.badlogic.gdx.graphics.g3d.utils.AnimationController;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.UBJsonReader;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Models loaded from files, for when a box is not enough.
 *
 * Everything on this table is built at startup out of boxes and generated textures, which is why
 * it ships with no art and runs on a fresh clone. This is the other half: a folder you can drop a
 * real model into, which is then used wherever a card would otherwise be a flat rectangle - a
 * figure standing on the card, a statue on the command zone, a token that is actually a dragon.
 *
 * Three rules make this safe to build on:
 *
 *   NOTHING IS REQUIRED. No model file, no change: every lookup returns null and the table draws
 *   exactly what it drew before. The folder can be empty forever and nothing breaks.
 *
 *   SIZE IS NOT THE MODELLER'S PROBLEM. A model is measured when it loads and scaled so it stands
 *   a given height on the table, whatever units it was exported in. Drop in a dragon from
 *   anywhere and it is the right size.
 *
 *   ANIMATIONS PLAY THEMSELVES. A model with animations gets an AnimationController and its first
 *   animation on a loop. One without is simply still.
 *
 * <h3>Formats</h3>
 * libGDX reads <b>.g3db</b> (binary, the one to use), <b>.g3dj</b> (the same thing as JSON,
 * readable and much larger) and <b>.obj</b> (geometry only - no skeleton, so no animation). A
 * Blender or Maya model becomes .g3db through <i>fbx-conv</i>, which is a separate download; .obj
 * needs no tool at all and is the quick way to try a shape.
 *
 * <h3>Where files go</h3>
 * <code>ui3d\models\</code>, looked up by name:
 * <pre>
 *   ui3d\models\serra angel.g3db        a model for that exact card
 *   ui3d\models\_creature.g3db          every creature with no model of its own
 *   ui3d\models\_artifact.g3db          every artifact, and so on by type
 * </pre>
 * Names are matched the way card pictures are: lower case, punctuation ignored.
 *
 * -Dforge.ui3d.models=&lt;path&gt; points it somewhere else; -Dforge.ui3d.models=off disables it.
 */
final class Models {

    private Models() {}

    /** Loaded models by key. A key that is known to have no file maps to null. */
    private static final Map<String, Model> CACHE = new HashMap<>();
    /** How much to scale each model so it stands FIGURE_H high; worked out once, with the model. */
    private static final Map<String, Float> SCALE = new HashMap<>();
    /** Everything loaded, for disposal - a Model holds meshes and textures on the GPU. */
    private static final List<Model> LOADED = new ArrayList<>();

    /** How tall a figure standing on a card is, in table units (a card is 1.0 x 1.4). */
    static final float FIGURE_H = 0.95f;

    private static File root;
    private static boolean off;
    private static boolean announced;

    /** The folder models are read from, or null when there is none. */
    private static synchronized File root() {
        if (off) return null;
        if (root != null) return root;
        String given = System.getProperty("forge.ui3d.models");
        if ("off".equalsIgnoreCase(given)) { off = true; return null; }
        for (String p : given != null ? new String[] { given }
                : new String[] { "ui3d/models", "../ui3d/models", "../../ui3d/models", "models" }) {
            File f = new File(p);
            if (f.isDirectory()) {
                root = f;
                if (!announced) {
                    announced = true;
                    String[] kids = f.list();
                    System.out.println("UI3D: models from " + f.getPath()
                        + " (" + (kids == null ? 0 : kids.length) + " file(s))");
                }
                return root;
            }
        }
        off = true;              // no folder: stop looking, every frame, forever
        return null;
    }

    /**
     * The model filed under this key, or null when there is none.
     *
     * Called from the render thread only: loading a model makes GPU objects, and a Model made on
     * another thread belongs to no context.
     */
    static synchronized Model get(String key) {
        if (key == null || key.isEmpty()) return null;
        String k = CardArt.loose(key);
        if (CACHE.containsKey(k)) return CACHE.get(k);
        CACHE.put(k, null);                       // remember the miss before trying, so a bad
                                                  // file is not re-read every frame
        File dir = root();
        if (dir == null) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            String name = f.getName();
            int dot = name.lastIndexOf('.');
            if (dot <= 0) continue;
            String ext = name.substring(dot + 1).toLowerCase();
            if (!ext.equals("g3db") && !ext.equals("g3dj") && !ext.equals("obj")) continue;
            if (!CardArt.loose(name.substring(0, dot)).equals(k)) continue;
            try {
                FileHandle fh = Gdx.files.absolute(f.getAbsolutePath());
                Model m = ext.equals("obj") ? new ObjLoader().loadModel(fh)
                    : new G3dModelLoader(ext.equals("g3dj")
                        ? new com.badlogic.gdx.utils.JsonReader() : new UBJsonReader()).loadModel(fh);
                CACHE.put(k, m);
                LOADED.add(m);
                SCALE.put(k, fitScale(m));
                System.out.println("UI3D: model '" + name + "' loaded"
                    + (m.animations.size > 0 ? " (" + m.animations.size + " animation(s))" : ""));
                return m;
            } catch (Throwable t) {
                System.out.println("UI3D: could not load the model " + name + " (" + t + ")");
                return null;
            }
        }
        return null;
    }

    /** The scale that makes a model stand FIGURE_H high, from its own bounding box. */
    private static float fitScale(Model m) {
        try {
            BoundingBox b = m.calculateBoundingBox(new BoundingBox());
            Vector3 size = b.getDimensions(new Vector3());
            float tall = Math.max(size.y, 0.0001f);
            return FIGURE_H / tall;
        } catch (Throwable t) {
            return 1f;
        }
    }

    /**
     * A model standing ready to be placed, or null when there is no model for any of the keys.
     *
     * The keys are tried in order, so a card can ask for itself first and fall back to something
     * generic: get("Serra Angel", "_creature").
     */
    static Figure figure(String... keys) {
        for (String key : keys) {
            Model m = get(key);
            if (m == null) continue;
            Figure f = new Figure();
            f.instance = new ModelInstance(m);
            f.scale = SCALE.getOrDefault(CardArt.loose(key), 1f);
            if (m.animations.size > 0) {
                f.anim = new AnimationController(f.instance);
                // The first animation, looped, offset so a row of the same creature is not a
                // chorus line - the phase comes from the instance, not from the clock.
                f.anim.setAnimation(m.animations.get(0).id, -1);
                f.anim.update((float) (Math.random() * 2.0));
            }
            return f;
        }
        return null;
    }

    /** One placed model: the instance, how much to scale it, and its animation if it has one. */
    static final class Figure {
        ModelInstance instance;
        AnimationController anim;
        float scale = 1f;
        /** The emissive each lit material had before anything lit it, so it can be put back. */
        private Map<String, Color> rest;

        /** Stand it at a point on the table, turned to `yaw` degrees. */
        void stand(float x, float y, float z, float yaw) {
            instance.transform.setToTranslation(x, y, z)
                .rotate(Vector3.Y, yaw)
                .scale(scale, scale, scale);
        }

        void update(float dt) {
            if (anim != null) anim.update(dt);
        }

        /**
         * Light one named material of this figure, or put it back with {@link #unglow}.
         *
         * This is the only way the game state reaches a model. A .g3dj file knows nothing and can
         * be told nothing - it has no script, and its one animation loops whatever is happening -
         * so a model that should react to the board names the part that reacts and the table does
         * the deciding. {@link TableLayout#PAIR_GLOW} is the first caller.
         *
         * Safe on a model that has no such material: a replaced or hand-edited file simply does
         * not light, the same way a missing model file simply leaves the card flat.
         *
         * A ModelInstance copies its Model's materials, so this lights one figure and not every
         * other copy of the same card on the table.
         */
        void glow(String materialId, float r, float g, float b) {
            Material mt = instance.getMaterial(materialId);
            if (mt == null) return;
            remember(materialId, mt);
            mt.set(ColorAttribute.createEmissive(r, g, b, 1f));
        }

        /** Put a lit material back to the emissive the model file gave it. */
        void unglow(String materialId) {
            if (rest == null) return;
            Color was = rest.get(materialId);
            if (was == null) return;
            Material mt = instance.getMaterial(materialId);
            if (mt != null) mt.set(ColorAttribute.createEmissive(was));
        }

        private void remember(String materialId, Material mt) {
            if (rest == null) rest = new HashMap<>();
            if (rest.containsKey(materialId)) return;
            ColorAttribute a = (ColorAttribute) mt.get(ColorAttribute.Emissive);
            rest.put(materialId, new Color(a == null ? Color.BLACK : a.color));
        }
    }

    /** Called when the window closes: a Model holds meshes and textures that are not the GC's. */
    static synchronized void dispose() {
        for (Model m : LOADED) {
            try { m.dispose(); } catch (Throwable ignored) { /* going away anyway */ }
        }
        LOADED.clear();
        CACHE.clear();
        SCALE.clear();
        root = null;
        off = false;
        announced = false;
    }
}
