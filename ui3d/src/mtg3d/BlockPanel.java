package mtg3d;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Matrix4;

import java.util.ArrayList;
import java.util.List;

/**
 * Declaring blockers, as a table rather than as arrows on the board.
 *
 * Blocking on the 3D table did not work (the owner, 2026-11-01). Attacking is fine - you drag
 * from your creature to a seat, and there is one arrow per attacker. Blocking is the other
 * shape: several of your creatures onto one of several attackers, every one of which may already
 * have blockers on it, and the board draws that as a bundle of arrows crossing each other over
 * cards that are themselves squeezed into strips. Three identical Robot tokens and two of your
 * blockers and there is nothing on screen that says which went where.
 *
 * So: a window. Attackers down the left, one row each, with what they are attacking and the
 * blockers already in front of them. Your creatures down the right. Drag one onto a row to block
 * with it, drag it off to take it back. Every creature shows its power and toughness, which is
 * the number the decision is actually made on, and hovering a name pops the card out full size
 * next to the table for when the numbers are not enough.
 *
 * This panel decides nothing. It calls back with "this blocker, that attacker" and the caller
 * sends it to Forge the same way a drag on the board did ({@link TableInput#blockWith}), so the
 * rules stay Forge's and the arcs on the board keep coming from the game. Rows are rebuilt from
 * the snapshot every frame, so a block Forge rejects simply never appears.
 */
public final class BlockPanel {

    /** What the panel asks the caller to do. The caller owns Forge; this owns the picture. */
    public interface Assign {
        /** Put this blocker in front of this attacker. */
        void block(int blockerId, int attackerId);
        /** Take this blocker out of combat. */
        void unblock(int blockerId);
        /** Blocks are as the player wants them. */
        void done();
    }

    /** One attacker and everything in front of it, as the panel needs to draw it. */
    public static final class Row {
        public Snapshot.CardV attacker;
        /** "you", or the name of the planeswalker or battle being attacked. */
        public String target = "";
        public final List<Snapshot.CardV> blockers = new ArrayList<>();
    }

    private static final float OPEN_RATE = 6.0f;
    private static final float ROW_H = 46f, CHIP_H = 22f, HEAD = 64f, FOOT = 52f;
    private static final float POP_W = 240f, POP_H = 336f;

    private final CardArt art;
    private boolean open, closing;
    private float anim;
    private final List<Row> rows = new ArrayList<>();
    private final List<Snapshot.CardV> mine = new ArrayList<>();
    private Assign assign;

    private boolean prevTouch, pressed;
    private float pressX, pressY;
    /** The creature being carried, and where it came from (-1 when it came from the right list). */
    private Snapshot.CardV held;
    private int heldFrom = -1;
    /** The card under the pointer, for the pop-out. */
    private Snapshot.CardV hovered;
    private boolean doneHover, clearHover;

    private float px, py, pw, ph;        // the panel, this frame

    public BlockPanel(CardArt art) { this.art = art; }

    public boolean isOpen() { return open; }

    public void close() {
        if (open) closing = true;
        held = null; pressed = false;
    }

    /**
     * Hand the panel this frame's picture of combat. Called every frame while blocking, because
     * Forge is the one that decides what a block actually is - the panel never holds state the
     * game could contradict.
     */
    public void show(List<Row> attackers, List<Snapshot.CardV> yours, Assign assign) {
        this.assign = assign;
        rows.clear();
        if (attackers != null) rows.addAll(attackers);
        mine.clear();
        if (yours != null) mine.addAll(yours);
        if (!open) { open = true; closing = false; anim = 0f; }
    }

    /** True while this creature is already in front of something. */
    private int blocking(int id) {
        for (Row r : rows) for (Snapshot.CardV b : r.blockers) if (b.id == id) return r.attacker.id;
        return Integer.MIN_VALUE;
    }

    /** Same shape as every other panel here: the caller ticks it, then renders it. */
    public void update(float dt) {
        if (!open) return;
        float target = closing ? 0f : 1f;
        anim += (target - anim) * Math.min(1f, dt * OPEN_RATE);
        if (closing && anim < 0.02f) { open = false; closing = false; anim = 0f; }
        else if (!closing && Math.abs(1f - anim) < 0.005f) anim = 1f;
    }

    public void render(SpriteBatch ui, ShapeRenderer shapes, BitmapFont font, BitmapFont fontBig) {
        if (!open) return;
        int w = Gdx.graphics.getWidth(), h = Gdx.graphics.getHeight();
        Matrix4 flat = new Matrix4().setToOrtho2D(0, 0, w, h);
        float t = anim * anim * (3f - 2f * anim);

        pw = Math.min(w - 80f, 1120f);
        ph = Math.min(h - 100f, Math.max(360f, HEAD + FOOT + Math.max(rows.size(), mine.size()) * ROW_H + 20f));
        px = (w - pw) / 2f;
        py = (h - ph) / 2f - (1f - t) * 40f;
        float leftW = pw * 0.60f;

        float mx = Gdx.input.getX(), my = h - Gdx.input.getY();
        boolean touch = Gdx.input.isButtonPressed(Input.Buttons.LEFT);
        boolean justDown = touch && !prevTouch, justUp = !touch && prevTouch;
        prevTouch = touch;
        hovered = null;

        Gdx.gl.glEnable(GL20.GL_BLEND);
        shapes.setProjectionMatrix(flat);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0f, 0f, 0f, 0.55f * t);
        shapes.rect(0, 0, w, h);                                   // dim the table behind it
        shapes.setColor(0.03f, 0.08f, 0.12f, 0.98f * t);
        shapes.rect(px, py, pw, ph);
        shapes.setColor(0.92f, 0.46f, 0.22f, t);                   // combat orange, as the arcs
        shapes.rect(px, py + ph - 3f, pw, 3f);
        shapes.setColor(0.10f, 0.16f, 0.22f, t);
        shapes.rect(px + leftW, py + 8f, 1f, ph - HEAD - 8f);      // the divider

        // ---- attacker rows ------------------------------------------------
        int dropRow = -1;
        for (int i = 0; i < rows.size(); i++) {
            float ry = rowY(i);
            boolean over = held != null && mx >= px && mx <= px + leftW && my >= ry && my < ry + ROW_H;
            if (over) dropRow = i;
            boolean any = !rows.get(i).blockers.isEmpty();
            if (over) shapes.setColor(0.24f, 0.46f, 0.30f, 0.95f * t);
            else if (any) shapes.setColor(0.10f, 0.19f, 0.16f, 0.90f * t);
            else shapes.setColor(0.09f, 0.13f, 0.17f, 0.80f * t);
            shapes.rect(px + 10f, ry + 2f, leftW - 22f, ROW_H - 5f);
            shapes.setColor(any ? 0.34f : 0.86f, any ? 0.72f : 0.34f, any ? 0.44f : 0.26f, t);
            shapes.rect(px + 10f, ry + 2f, 3f, ROW_H - 5f);        // unblocked reads red, blocked green
        }

        // ---- your creatures ------------------------------------------------
        for (int i = 0; i < mine.size(); i++) {
            float ry = rowY(i);
            Snapshot.CardV c = mine.get(i);
            boolean busy = blocking(c.id) != Integer.MIN_VALUE;
            boolean hot = mx > px + leftW && mx < px + pw && my >= ry && my < ry + ROW_H;
            shapes.setColor(hot ? 0.16f : 0.07f, hot ? 0.22f : 0.11f, hot ? 0.30f : 0.15f,
                (busy ? 0.45f : 0.85f) * t);
            shapes.rect(px + leftW + 12f, ry + 2f, pw - leftW - 24f, ROW_H - 5f);
        }
        shapes.end();

        ui.setProjectionMatrix(flat);
        ui.begin();
        font.setColor(1f, 0.82f, 0.62f, t);
        font.draw(ui, "DECLARE BLOCKERS", px + 14f, py + ph - 16f);
        font.setColor(0.52f, 0.64f, 0.74f, t);
        font.draw(ui, "Drag one of your creatures onto an attacker to block it. Drag it off to take it back."
            + "  Hover a name to see the card.", px + 180f, py + ph - 16f);
        font.setColor(0.42f, 0.55f, 0.66f, t);
        font.draw(ui, "ATTACKING YOU", px + 14f, py + ph - 40f);
        font.draw(ui, "YOUR CREATURES", px + leftW + 16f, py + ph - 40f);

        GlyphLayout gl = new GlyphLayout();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            float ry = rowY(i);
            font.setColor(0.94f, 0.96f, 1f, t);
            font.draw(ui, pt(r.attacker), px + 22f, ry + ROW_H - 12f);
            float nx = px + 78f;
            font.setColor(1f, 0.88f, 0.80f, t);
            font.draw(ui, r.attacker.name, nx, ry + ROW_H - 12f);
            gl.setText(font, r.attacker.name);
            if (mx >= nx && mx <= nx + gl.width && my >= ry && my < ry + ROW_H) hovered = r.attacker;
            font.setColor(0.46f, 0.58f, 0.68f, t);
            font.draw(ui, r.target.isEmpty() ? "" : "attacking " + r.target, nx, ry + 16f);

            // the blockers already in front of it, as chips along the row
            float cx = px + leftW - 26f;
            for (int b = r.blockers.size() - 1; b >= 0; b--) {
                Snapshot.CardV bc = r.blockers.get(b);
                String label = bc.name + " " + pt(bc);
                gl.setText(font, label);
                cx -= gl.width + 16f;
                if (cx < nx + 160f) { font.setColor(0.52f, 0.64f, 0.74f, t);
                    font.draw(ui, "+" + (b + 1) + " more", nx + 120f, ry + ROW_H - 12f); break; }
                font.setColor(0.56f, 0.92f, 0.66f, t);
                font.draw(ui, label, cx, ry + ROW_H - 16f);
                if (mx >= cx && mx <= cx + gl.width && my >= ry && my < ry + ROW_H) {
                    hovered = bc;
                    if (justDown) { held = bc; heldFrom = r.attacker.id; pressed = true; pressX = mx; pressY = my; }
                }
            }
        }

        for (int i = 0; i < mine.size(); i++) {
            Snapshot.CardV c = mine.get(i);
            float ry = rowY(i);
            int on = blocking(c.id);
            boolean busy = on != Integer.MIN_VALUE;
            font.setColor(busy ? 0.42f : 0.94f, busy ? 0.50f : 0.96f, busy ? 0.58f : 1f, t);
            font.draw(ui, pt(c), px + leftW + 22f, ry + ROW_H - 12f);
            float nx = px + leftW + 78f;
            font.draw(ui, c.name, nx, ry + ROW_H - 12f);
            gl.setText(font, c.name);
            boolean overName = mx >= nx && mx <= nx + gl.width && my >= ry && my < ry + ROW_H;
            if (overName) hovered = c;
            if (busy) {
                font.setColor(0.38f, 0.62f, 0.46f, t);
                font.draw(ui, "blocking " + nameOf(on), nx, ry + 16f);
            } else if (c.tapped) {
                font.setColor(0.62f, 0.42f, 0.36f, t);
                font.draw(ui, "tapped - cannot block", nx, ry + 16f);
            }
            boolean overRow = mx > px + leftW && mx < px + pw && my >= ry && my < ry + ROW_H;
            if (overRow && justDown && !c.tapped) {
                held = c; heldFrom = -1; pressed = true; pressX = mx; pressY = my;
            }
        }

        // ---- what is being carried, and the pop-out ---------------------------
        if (held != null) {
            font.setColor(1f, 0.92f, 0.70f, t);
            font.draw(ui, held.name + " " + pt(held), mx + 14f, my + 6f);
        } else if (hovered != null) {
            drawPop(ui, font, hovered, w, h, t);
        }

        // ---- buttons ------------------------------------------------------------
        float bw = 150f, bh = 30f, by = py + 12f;
        float dx = px + pw - bw - 14f, cx2 = dx - bw - 10f;
        doneHover = mx >= dx && mx <= dx + bw && my >= by && my <= by + bh;
        clearHover = mx >= cx2 && mx <= cx2 + bw && my >= by && my <= by + bh;
        ui.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(doneHover ? 0.24f : 0.15f, doneHover ? 0.62f : 0.40f, doneHover ? 0.38f : 0.26f, t);
        shapes.rect(dx, by, bw, bh);
        shapes.setColor(clearHover ? 0.40f : 0.22f, clearHover ? 0.20f : 0.12f, clearHover ? 0.18f : 0.12f, t);
        shapes.rect(cx2, by, bw, bh);
        shapes.end();
        ui.begin();
        font.setColor(0.88f, 1f, 0.92f, t);
        font.draw(ui, blockCount() == 0 ? "No blocks" : "Confirm blocks", dx, by + 20f, bw, 1, false);
        font.setColor(1f, 0.84f, 0.80f, t);
        font.draw(ui, "Clear all", cx2, by + 20f, bw, 1, false);
        ui.end();

        // ---- the drop -------------------------------------------------------------
        if (justUp && held != null) {
            Snapshot.CardV dropped = held;
            int from = heldFrom;
            held = null; pressed = false;
            if (dropRow >= 0) {
                int onto = rows.get(dropRow).attacker.id;
                if (onto != from && assign != null) assign.block(dropped.id, onto);
            } else if (from != -1 && assign != null && !inPanel(mx, my)) {
                assign.unblock(dropped.id);                  // dragged out of the window entirely
            } else if (from != -1 && mx > px + leftW && assign != null) {
                assign.unblock(dropped.id);                  // dragged back to your own list
            }
        } else if (justUp && pressed) {
            pressed = false;
            if (doneHover && assign != null) assign.done();
            else if (clearHover && assign != null) for (Row r : rows) for (Snapshot.CardV b : r.blockers) assign.unblock(b.id);
        }
        if (justUp) pressed = false;
    }

    private boolean inPanel(float x, float y) {
        return x >= px && x <= px + pw && y >= py && y <= py + ph;
    }

    private int blockCount() {
        int n = 0;
        for (Row r : rows) n += r.blockers.size();
        return n;
    }

    private String nameOf(int id) {
        for (Row r : rows) if (r.attacker.id == id) return r.attacker.name;
        return "?";
    }

    /** Power and toughness as one short token - the number the decision is made on. */
    private static String pt(Snapshot.CardV c) {
        String p = c.power == null || c.power.isEmpty() ? "?" : c.power;
        String tg = c.toughness == null || c.toughness.isEmpty() ? "?" : c.toughness;
        return p + "/" + tg;
    }

    private float rowY(int i) {
        return py + ph - HEAD - (i + 1) * ROW_H;
    }

    /**
     * The hovered card, full size, beside the panel.
     *
     * Beside and not over: the whole point of the table is comparing numbers across rows, and a
     * card dropped in the middle of it would cover the rows being compared. It goes to whichever
     * side has room.
     */
    private void drawPop(SpriteBatch ui, BitmapFont font, Snapshot.CardV c, int w, int h, float t) {
        float x = px + pw + 12f;
        if (x + POP_W > w - 8f) x = px - POP_W - 12f;
        if (x < 8f) x = w - POP_W - 8f;
        float y = Math.min(h - POP_H - 10f, Math.max(10f, Gdx.graphics.getHeight() - Gdx.input.getY() - POP_H / 2f));
        Texture tex = art == null ? null : art.get(c);
        ui.setColor(1f, 1f, 1f, t);
        if (tex != null) {
            ui.draw(tex, x, y, POP_W, POP_H);
        } else {
            font.setColor(0.86f, 0.92f, 1f, t);
            font.draw(ui, c.name + "  " + pt(c), x, y + POP_H - 10f, POP_W, -1, true);
            font.setColor(0.60f, 0.72f, 0.84f, t);
            font.draw(ui, c.typeLine == null ? "" : c.typeLine, x, y + POP_H - 60f, POP_W, -1, true);
            font.draw(ui, c.text == null ? "" : c.text, x, y + POP_H - 90f, POP_W, -1, true);
        }
    }
}
