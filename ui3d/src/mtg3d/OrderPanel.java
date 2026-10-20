package mtg3d;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Matrix4;

import java.util.ArrayList;
import java.util.List;

/**
 * Putting cards in order: scry (top / bottom), surveil (top / graveyard), ordering simultaneous
 * triggers, "put them back in any order". One or two rows of cards; drag a card to move it -
 * within its row to reorder, or to the other row - and in two-row mode a plain click sends it
 * across, which is the quick way to scry. Positions are numbered, so it is never in doubt which
 * card ends up on top or which trigger resolves first.
 *
 * Presentation only: {@link #open} takes the rows and a {@link Result} with the final rows, so
 * Forge's order / scry decisions can be pointed at it later. Same arrival as the other panels.
 */
public final class OrderPanel {

    public interface Result {
        /** The rows as left, in order; null if cancelled. */
        void chosen(List<List<Snapshot.CardV>> rows);
    }

    private static final float OPEN_RATE = 5.0f, CARD_W = 132f, CARD_H = 185f, GAP = 14f, ROW_H = 250f;

    private final CardArt art;
    private boolean open, closing;
    private float anim;
    private String title = "", prompt = "";
    private final List<List<Snapshot.CardV>> rows = new ArrayList<>();
    private String[] rowNames = {};
    private String[] numberWords = {};
    private Result result;
    private boolean prevTouch, okHover, cancelHover;
    // Dragging: which card, where it was picked up, and where the press started (to tell a click).
    private int dragRow = -1, dragIndex = -1;
    private float pressX, pressY;
    private boolean pressed;

    public OrderPanel(CardArt art) { this.art = art; }

    /**
     * @param rowNames     one name per row ("Top of library", "Bottom")
     * @param numberWords  what position 1 means in each row ("drawn first", "resolves first"), or ""
     */
    public void open(String title, String prompt, List<List<Snapshot.CardV>> start,
                     String[] rowNames, String[] numberWords, Result result) {
        this.title = title == null ? "" : title;
        this.prompt = prompt == null ? "" : prompt;
        rows.clear();
        for (List<Snapshot.CardV> r : start) rows.add(new ArrayList<>(r));
        this.rowNames = rowNames;
        this.numberWords = numberWords;
        this.result = result;
        open = true;
        closing = false;
        anim = 0f;
        prevTouch = true;
        dragRow = -1;
        pressed = false;
    }

    public boolean isOpen() { return open; }

    /** Shut it without answering - the question went back to Forge (timeout, or H). */
    public void close() {
        if (open && !closing) closing = true;
    }

    private boolean live() { return open && !closing && anim > 0.85f; }

    public void update(float dt) {
        if (!open) return;
        float target = closing ? 0f : 1f;
        anim += (target - anim) * Math.min(1f, dt * OPEN_RATE);
        if (closing && anim < 0.02f) { open = false; closing = false; anim = 0f; }
        else if (!closing && Math.abs(1f - anim) < 0.005f) anim = 1f;
    }

    private void finish(boolean cancelled) {
        Result r = result;
        result = null;
        closing = true;
        if (r == null) return;
        if (cancelled) { r.chosen(null); return; }
        List<List<Snapshot.CardV>> out = new ArrayList<>();
        for (List<Snapshot.CardV> row : rows) out.add(new ArrayList<>(row));
        r.chosen(out);
    }

    // Geometry, shared by drawing and input so they cannot drift apart.
    private float px, py, pw, ph;

    private void layout(int sw, int sh, float t) {
        pw = Math.min(sw - 120f, Math.max(640f, 220f + rows.stream().mapToInt(List::size).sum() * (CARD_W + GAP)));
        ph = 110f + rows.size() * ROW_H + 50f;
        px = (sw - pw) / 2f;
        py = (sh - ph) / 2f - (1f - t) * 120f;
    }

    private float rowY(int r) { return py + ph - 100f - (r + 1) * ROW_H + (ROW_H - CARD_H) / 2f; }

    private float slotX(int i) { return px + 200f + i * (CARD_W + GAP); }

    public void render(SpriteBatch ui, ShapeRenderer shapes, BitmapFont font, BitmapFont fontBig) {
        if (!open) return;
        int sw = Gdx.graphics.getWidth(), sh = Gdx.graphics.getHeight();
        Matrix4 flat = new Matrix4().setToOrtho2D(0, 0, sw, sh);
        float t = anim * anim * (3f - 2f * anim);
        layout(sw, sh, t);
        float mx = Gdx.input.getX(), my = sh - Gdx.input.getY();
        if (live()) handleInput(mx, my);

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.setProjectionMatrix(flat);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0f, 0f, 0f, 0.55f * t);
        shapes.rect(0, 0, sw, sh);
        shapes.setColor(0.03f, 0.10f, 0.15f, 0.97f * t);
        shapes.rect(px, py, pw, ph);
        shapes.setColor(0.18f, 0.56f, 0.71f, t);
        shapes.rect(px, py + ph - 4f, pw, 4f);
        for (int r = 0; r < rows.size(); r++) {
            // Each row a lane, the second a little warmer so top and bottom never blur together.
            shapes.setColor(r == 0 ? 0.05f : 0.09f, r == 0 ? 0.15f : 0.12f, r == 0 ? 0.21f : 0.16f, t);
            shapes.rect(px + 14f, rowY(r) - 18f, pw - 28f, CARD_H + 36f);
        }
        // Where the dragged card would land.
        if (dragRow >= 0 && pressed) {
            int[] at = dropAt(mx, my);
            if (at != null) {
                shapes.setColor(0.95f, 0.72f, 0.25f, t);
                shapes.rect(slotX(at[1]) - GAP / 2f - 2f, rowY(at[0]) - 6f, 4f, CARD_H + 12f);
            }
        }
        shapes.end();

        ui.setProjectionMatrix(flat);
        ui.begin();
        fontBig.setColor(0.85f, 0.95f, 1f, t);
        fontBig.draw(ui, title, px + 22f, py + ph - 20f);
        font.setColor(0.60f, 0.76f, 0.88f, t);
        font.draw(ui, prompt, px + 22f, py + ph - 48f);
        for (int r = 0; r < rows.size(); r++) {
            float y = rowY(r);
            font.setColor(0.85f, 0.92f, 1f, t);
            font.draw(ui, r < rowNames.length ? rowNames[r] : "", px + 26f, y + CARD_H - 4f);
            font.setColor(0.50f, 0.64f, 0.74f, t);
            String nw = r < numberWords.length ? numberWords[r] : "";
            if (!nw.isEmpty()) font.draw(ui, "1 = " + nw, px + 26f, y + CARD_H - 24f);
            font.draw(ui, rows.get(r).size() + " card" + (rows.get(r).size() == 1 ? "" : "s"), px + 26f, y + 14f);
            List<Snapshot.CardV> row = rows.get(r);
            for (int i = 0; i < row.size(); i++) {
                if (r == dragRow && i == dragIndex && pressed && moved(mx, my)) continue;   // drawn at the mouse
                drawCard(ui, font, row.get(i), slotX(i), y, t);
                font.setColor(1f, 0.86f, 0.42f, t);
                font.draw(ui, String.valueOf(i + 1), slotX(i) + CARD_W / 2f - 4f, y + CARD_H + 16f);
            }
        }
        if (dragRow >= 0 && pressed && moved(mx, my)) {
            drawCard(ui, font, rows.get(dragRow).get(dragIndex), mx - CARD_W / 2f, my - CARD_H / 2f, t);
        }
        font.setColor(0.40f, 0.52f, 0.62f, t);
        font.draw(ui, rows.size() > 1 ? "drag to reorder or move between rows   -   click sends a card across"
            : "drag to reorder", px + 22f, py + 24f);
        font.setColor(okHover ? new Color(1f, 0.92f, 0.60f, t) : new Color(0.65f, 0.95f, 1f, t));
        font.draw(ui, "Confirm", px + pw - 100f, py + 24f);
        font.setColor(cancelHover ? new Color(1f, 0.80f, 0.70f, t) : new Color(0.55f, 0.68f, 0.78f, t));
        font.draw(ui, "Cancel", px + pw - 180f, py + 24f);
        ui.end();
    }

    private void drawCard(SpriteBatch ui, BitmapFont font, Snapshot.CardV c, float x, float y, float t) {
        Texture tex = art == null ? null : art.get(c);
        ui.setColor(1f, 1f, 1f, t);
        if (tex != null) ui.draw(tex, x, y, CARD_W, CARD_H);
        else {
            font.setColor(0.80f, 0.88f, 0.95f, t);
            font.draw(ui, c.name, x + 6f, y + CARD_H - 10f, CARD_W - 12f, -1, true);
        }
        // A trigger is its source card plus what the trigger does; say what it does.
        if (c.stackText != null && !c.stackText.isEmpty()) {
            font.setColor(0.90f, 0.95f, 1f, t);
            font.draw(ui, c.stackText, x, y - 6f, CARD_W, -1, true);
        }
    }

    private boolean moved(float mx, float my) {
        return Math.abs(mx - pressX) > 6f || Math.abs(my - pressY) > 6f;
    }

    /** {row, index} under the mouse for a drop, or null if not over a row. */
    private int[] dropAt(float mx, float my) {
        for (int r = 0; r < rows.size(); r++) {
            float y = rowY(r);
            if (my < y - 18f || my > y + CARD_H + 18f) continue;
            int n = rows.get(r).size();
            int i = (int) Math.floor((mx - px - 200f + (CARD_W + GAP) / 2f) / (CARD_W + GAP));
            if (r == dragRow && n > 0) n -= 1;               // it leaves its own slot
            return new int[] { r, Math.max(0, Math.min(n, i)) };
        }
        return null;
    }

    private void handleInput(float mx, float my) {
        okHover = mx >= px + pw - 105f && mx <= px + pw - 30f && my >= py + 8f && my <= py + 32f;
        cancelHover = mx >= px + pw - 185f && mx <= px + pw - 115f && my >= py + 8f && my <= py + 32f;
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) { finish(true); return; }
        if (Gdx.input.isKeyJustPressed(Input.Keys.ENTER)) { finish(false); return; }
        boolean touch = Gdx.input.isTouched();
        boolean down = touch && !prevTouch, up = !touch && prevTouch;
        prevTouch = touch;
        if (down) {
            if (okHover) { finish(false); return; }
            if (cancelHover) { finish(true); return; }
            for (int r = 0; r < rows.size(); r++) {
                float y = rowY(r);
                for (int i = 0; i < rows.get(r).size(); i++) {
                    float x = slotX(i);
                    if (mx >= x && mx <= x + CARD_W && my >= y && my <= y + CARD_H) {
                        dragRow = r; dragIndex = i; pressed = true; pressX = mx; pressY = my;
                    }
                }
            }
        } else if (up && pressed && dragRow >= 0) {
            Snapshot.CardV c = rows.get(dragRow).get(dragIndex);
            if (!moved(mx, my)) {
                // A click: across to the other row (end of it), in two-row mode.
                if (rows.size() > 1) {
                    rows.get(dragRow).remove(dragIndex);
                    rows.get(1 - dragRow).add(c);
                }
            } else {
                int[] at = dropAt(mx, my);
                if (at != null) {
                    rows.get(dragRow).remove(dragIndex);
                    rows.get(at[0]).add(Math.min(at[1], rows.get(at[0]).size()), c);
                }
            }
            dragRow = -1;
            pressed = false;
        }
    }
}
