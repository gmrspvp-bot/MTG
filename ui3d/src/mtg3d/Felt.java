package mtg3d;

import com.badlogic.gdx.graphics.Pixmap;

import java.nio.ByteBuffer;

/**
 * Generated surfaces for the table, so there are no image files to ship.
 *
 *   felt   the playing surface: the same deep blue the table has always had (everything drawn on
 *          it was tuned against that), with a fine grain so it reads as cloth rather than plastic,
 *          a soft lighter pool in the middle like a lamp over the table, and a faint ring marking
 *          the centre where the monitors and the stack live
 *   floor  what the table stands on: almost black, with a dim pool of light under the table, so
 *          the table sits in a room instead of floating in a void
 *
 * Both are deterministic (fixed seed), so a screenshot of the same snapshot is the same picture.
 * Built into a Pixmap on whatever thread calls them; the Texture is made on the render thread.
 */
final class Felt {
    private Felt() {}

    /** The felt. `size` pixels square, covering the whole table top. */
    static Pixmap felt(int size) {
        Pixmap pm = new Pixmap(size, size, Pixmap.Format.RGBA8888);
        ByteBuffer px = pm.getPixels();
        java.util.Random rnd = new java.util.Random(20261021L);
        float half = size / 2f;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float dx = (x - half) / half, dy = (y - half) / half;
                float r = (float) Math.sqrt(dx * dx + dy * dy);          // 0 centre, ~1.41 corner
                // Lamp pool: brighter in the middle, falling off smoothly towards the rail.
                float pool = 1.10f - 0.32f * Math.min(1f, r * r);
                // Cloth grain: fine noise plus a faint weave in two directions.
                float grain = (rnd.nextFloat() - 0.5f) * 0.10f
                    + 0.025f * (float) Math.sin(x * 1.9f) * (float) Math.sin(y * 1.7f);
                // A faint ring round the centre of the table.
                float ring = Math.abs(r - 0.36f) < 0.006f ? 0.10f : 0f;
                float k = pool + grain + ring;
                // A deep blue-green baize rather than the flat blue it was. Cloth has a colour
                // that shifts with the light across it, so the lit middle runs warmer and the
                // falloff towards the rail goes cooler and darker - that shift is most of what
                // makes a surface read as cloth rather than as a painted plane.
                float warm = Math.max(0f, 1f - r * 0.9f);
                put(px, (y * size + x) * 4,
                    (0.045f + 0.035f * warm) * k,
                    (0.125f + 0.030f * warm) * k,
                    (0.130f + 0.010f * warm) * k);
            }
        }
        return pm;
    }

    /** The floor under the table: a dim pool of light fading to black. */
    static Pixmap floor(int size) {
        Pixmap pm = new Pixmap(size, size, Pixmap.Format.RGBA8888);
        ByteBuffer px = pm.getPixels();
        java.util.Random rnd = new java.util.Random(7L);
        float half = size / 2f;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float dx = (x - half) / half, dy = (y - half) / half;
                float r = (float) Math.sqrt(dx * dx + dy * dy);
                float light = Math.max(0f, 1f - r * 1.15f);
                light = light * light;
                float n = (rnd.nextFloat() - 0.5f) * 0.012f;
                put(px, (y * size + x) * 4, 0.020f + 0.06f * light + n, 0.035f + 0.08f * light + n, 0.05f + 0.10f * light + n);
            }
        }
        return pm;
    }

    /**
     * Wood for the rail round the table: a dark walnut with grain running the long way, a few
     * darker streaks, and the faint sheen of a polished edge along the top.
     *
     * Generated rather than shipped, like the rest of this file, and deterministic.
     */
    static Pixmap wood(int w, int h) {
        Pixmap pm = new Pixmap(w, h, Pixmap.Format.RGBA8888);
        ByteBuffer px = pm.getPixels();
        java.util.Random rnd = new java.util.Random(99L);
        for (int y = 0; y < h; y++) {
            float v = y / (float) h;
            // Across the rail: lighter where the light catches the top edge, dark underneath.
            float edge = 0.78f + 0.42f * (float) Math.pow(1f - v, 1.7f);
            for (int x = 0; x < w; x++) {
                float u = x / (float) w;
                // Grain: a few slow waves along the length, with the rings crowding together
                // here and there the way sawn timber does.
                float rings = (float) Math.sin(u * 46f + 2.1f * (float) Math.sin(u * 7f) + v * 1.3f);
                float grain = 0.055f * rings + 0.03f * (float) Math.sin(u * 150f + v * 4f);
                float streak = (float) Math.sin(u * 9.3f + 1.7f) > 0.86f ? -0.05f : 0f;
                float n = (rnd.nextFloat() - 0.5f) * 0.03f;
                float k = edge + grain + streak + n;
                put(px, (y * w + x) * 4, 0.145f * k, 0.082f * k, 0.052f * k);
            }
        }
        return pm;
    }

    /**
     * Dark leather for the player boards: the mat each seat's cards are laid out on. Fine pebble
     * grain, slightly lighter towards the middle where it would have worn.
     */
    static Pixmap leather(int size) {
        Pixmap pm = new Pixmap(size, size, Pixmap.Format.RGBA8888);
        ByteBuffer px = pm.getPixels();
        java.util.Random rnd = new java.util.Random(4242L);
        float half = size / 2f;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float dx = (x - half) / half, dy = (y - half) / half;
                float r = (float) Math.sqrt(dx * dx + dy * dy);
                float worn = 1.06f - 0.16f * Math.min(1f, r);
                // Pebble grain: two scales of noise, the coarse one giving the hide its texture.
                float coarse = 0.05f * (float) Math.sin(x * 0.7f + 3f * (float) Math.sin(y * 0.11f))
                    * (float) Math.sin(y * 0.6f + 3f * (float) Math.sin(x * 0.13f));
                float fine = (rnd.nextFloat() - 0.5f) * 0.05f;
                float k = worn + coarse + fine;
                put(px, (y * size + x) * 4, 0.072f * k, 0.088f * k, 0.105f * k);
            }
        }
        return pm;
    }

    private static void put(ByteBuffer px, int i, float r, float g, float b) {
        px.put(i, (byte) (int) (Math.max(0f, Math.min(1f, r)) * 255));
        px.put(i + 1, (byte) (int) (Math.max(0f, Math.min(1f, g)) * 255));
        px.put(i + 2, (byte) (int) (Math.max(0f, Math.min(1f, b)) * 255));
        px.put(i + 3, (byte) 255);
    }
}
