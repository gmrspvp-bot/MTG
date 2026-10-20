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
                put(px, (y * size + x) * 4, 0.07f * k, 0.16f * k, 0.24f * k);
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

    private static void put(ByteBuffer px, int i, float r, float g, float b) {
        px.put(i, (byte) (int) (Math.max(0f, Math.min(1f, r)) * 255));
        px.put(i + 1, (byte) (int) (Math.max(0f, Math.min(1f, g)) * 255));
        px.put(i + 2, (byte) (int) (Math.max(0f, Math.min(1f, b)) * 255));
        px.put(i + 3, (byte) 255);
    }
}
