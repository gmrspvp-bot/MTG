package mtg3d;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
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
 * Dividing an attacker's combat damage among the creatures blocking it (and, with trample, the
 * player). The attacker on the left with how much it has to give; each blocker in order with its
 * share, -/+ to change it, and how much is lethal for it - green once that much is assigned.
 * Auto fills lethal in blocker order and puts the rest last (on the player, with trample).
 * Confirm lights when everything is assigned.
 *
 * The rules (lethal to each blocker before the next, in the damage assignment order) are Forge's
 * to enforce; this panel shows them rather than policing them. Presentation only: {@link #open}
 * takes the numbers and a {@link Result}, for Forge's damage-assignment decision later.
 */
public final class DamagePanel {

    public interface Result {
        /** Damage per blocker, in the order given, plus what goes to the player; null if cancelled. */
        void assigned(int[] toBlockers, int toPlayer);
    }

    private static final float OPEN_RATE = 5.0f, CARD_W = 120f, CARD_H = 168f, COL_W = 170f;

    private final CardArt art;
    private boolean open, closing, trample;
    private float anim;
    private Snapshot.CardV attacker;
    private int power;
    private final List<Snapshot.CardV> blockers = new ArrayList<>();
    private int[] lethal = {}, share = {};
    private int toPlayer;
    private Result result;
    private boolean prevTouch;
    private int hoverMinus = -1, hoverPlus = -1;          // column index; blockers.size() = the player
    private boolean autoHover, okHover;

    public DamagePanel(CardArt art) { this.art = art; }

    public void open(Snapshot.CardV attacker, int power, List<Snapshot.CardV> blockers, int[] lethal,
                     boolean trample, Result result) {
        this.attacker = attacker;
        this.power = power;
        this.blockers.clear();
        this.blockers.addAll(blockers);
        this.lethal = lethal.clone();
        this.share = new int[blockers.size()];
        this.trample = trample;
        this.toPlayer = 0;
        this.result = result;
        open = true;
        closing = false;
        anim = 0f;
        prevTouch = true;
    }

    public boolean isOpen() { return open; }

    /** Shut it without answering - the question went back to Forge (timeout, or H). */
    public void close() {
        if (open && !closing) closing = true;
    }

    private boolean live() { return open && !closing && anim > 0.85f; }

    private int remaining() {
        int r = power - toPlayer;
        for (int s : share) r -= s;
        return r;
    }

    public void update(float dt) {
        if (!open) return;
        float target = closing ? 0f : 1f;
        anim += (target - anim) * Math.min(1f, dt * OPEN_RATE);
        if (closing && anim < 0.02f) { open = false; closing = false; anim = 0f; }
        else if (!closing && Math.abs(1f - anim) < 0.005f) anim = 1f;
    }

    /** Lethal to each blocker in order, the rest to the player (trample) or the last blocker. */
    private void auto() {
        int left = power;
        toPlayer = 0;
        for (int i = 0; i < share.length; i++) {
            share[i] = Math.min(left, lethal[i]);
            left -= share[i];
        }
        if (left > 0) {
            if (trample) toPlayer = left;
            else if (share.length > 0) share[share.length - 1] += left;
        }
    }

    // Geometry shared by drawing and input.
    private float px, py, pw, ph;

    private float colX(int i) { return px + 230f + i * COL_W; }

    public void render(SpriteBatch ui, ShapeRenderer shapes, BitmapFont font, BitmapFont fontBig) {
        if (!open) return;
        int sw = Gdx.graphics.getWidth(), sh = Gdx.graphics.getHeight();
        Matrix4 flat = new Matrix4().setToOrtho2D(0, 0, sw, sh);
        float t = anim * anim * (3f - 2f * anim);
        int cols = blockers.size() + (trample ? 1 : 0);
        pw = 260f + cols * COL_W;
        ph = 450f;
        px = (sw - pw) / 2f;
        py = (sh - ph) / 2f - (1f - t) * 120f;
        float cardY = py + 180f, btnY = cardY - 50f;
        float mx = Gdx.input.getX(), my = sh - Gdx.input.getY();
        if (live()) handleInput(mx, my, cols, btnY);

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.setProjectionMatrix(flat);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0f, 0f, 0f, 0.55f * t);
        shapes.rect(0, 0, sw, sh);
        shapes.setColor(0.03f, 0.10f, 0.15f, 0.97f * t);
        shapes.rect(px, py, pw, ph);
        shapes.setColor(0.85f, 0.35f, 0.20f, t);
        shapes.rect(px, py + ph - 4f, pw, 4f);
        for (int i = 0; i < cols; i++) {
            float x = colX(i) + (COL_W - 20f - 96f) / 2f;
            shapes.setColor(hoverMinus == i ? 0.22f : 0.10f, hoverMinus == i ? 0.46f : 0.24f, hoverMinus == i ? 0.60f : 0.32f, t);
            shapes.rect(x, btnY, 40f, 34f);
            shapes.setColor(hoverPlus == i ? 0.22f : 0.10f, hoverPlus == i ? 0.46f : 0.24f, hoverPlus == i ? 0.60f : 0.32f, t);
            shapes.rect(x + 56f, btnY, 40f, 34f);
            if (i < blockers.size() && share[i] >= lethal[i]) {
                shapes.setColor(0.30f, 0.80f, 0.40f, t);            // lethal reached
                shapes.rect(colX(i) - 4f, cardY - 4f, CARD_W + 8f, CARD_H + 8f);
            }
        }
        shapes.setColor(autoHover ? 0.24f : 0.12f, autoHover ? 0.50f : 0.30f, autoHover ? 0.62f : 0.38f, t);
        shapes.rect(px + 22f, py + 20f, 110f, 36f);
        boolean ready = remaining() == 0;
        shapes.setColor(ready ? (okHover ? 0.20f : 0.13f) : 0.07f, ready ? (okHover ? 0.62f : 0.46f) : 0.18f,
            ready ? (okHover ? 0.36f : 0.26f) : 0.12f, t);
        shapes.rect(px + pw - 150f, py + 20f, 128f, 36f);
        shapes.end();

        ui.setProjectionMatrix(flat);
        ui.begin();
        GlyphLayout gl = new GlyphLayout();
        fontBig.setColor(0.95f, 0.88f, 0.80f, t);
        fontBig.draw(ui, "Assign combat damage", px + 22f, py + ph - 20f);
        font.setColor(0.70f, 0.80f, 0.88f, t);
        font.draw(ui, "Lethal damage to each blocker, in order, before the next." + (trample ? "  Trample: the rest may go to the player." : ""),
            px + 22f, py + ph - 48f);
        drawCard(ui, font, attacker, px + 40f, cardY, t);
        fontBig.setColor(1f, 0.70f, 0.45f, t);
        fontBig.draw(ui, power + " damage", px + 40f, cardY - 16f);
        int rem = remaining();
        font.setColor(rem == 0 ? new Color(0.55f, 0.95f, 0.60f, t) : new Color(1f, 0.85f, 0.45f, t));
        font.draw(ui, rem == 0 ? "all assigned" : rem + " left to assign", px + 40f, cardY - 44f);
        for (int i = 0; i < cols; i++) {
            float x = colX(i);
            boolean player = i >= blockers.size();
            if (player) {
                fontBig.setColor(0.85f, 0.92f, 1f, t);
                fontBig.draw(ui, "Player", x + 18f, cardY + CARD_H / 2f + 10f);
            } else {
                drawCard(ui, font, blockers.get(i), x, cardY, t);
                font.setColor(0.60f, 0.74f, 0.84f, t);
                font.draw(ui, (i + 1) + ".   lethal " + lethal[i], x, cardY + CARD_H + 18f);
            }
            int v = player ? toPlayer : share[i];
            fontBig.getData().setScale(2.0f);
            fontBig.setColor(1f, 0.86f, 0.45f, t);
            gl.setText(fontBig, String.valueOf(v));
            float bx = x + (COL_W - 20f - 96f) / 2f;
            fontBig.draw(ui, String.valueOf(v), bx + 48f - gl.width / 2f, btnY - 10f);
            fontBig.getData().setScale(1.6f);
            fontBig.setColor(0.90f, 0.96f, 1f, t);
            fontBig.draw(ui, "-", bx + 15f, btnY + 26f);
            fontBig.draw(ui, "+", bx + 56f + 12f, btnY + 26f);
        }
        fontBig.setColor(0.90f, 0.96f, 1f, t);
        fontBig.draw(ui, "Auto", px + 52f, py + 47f);
        fontBig.setColor(ready ? new Color(0.92f, 1f, 0.94f, t) : new Color(0.45f, 0.55f, 0.50f, t));
        fontBig.draw(ui, "Confirm", px + pw - 136f, py + 47f);
        ui.end();
    }

    private void drawCard(SpriteBatch ui, BitmapFont font, Snapshot.CardV c, float x, float y, float t) {
        Texture tex = art == null || c == null ? null : art.get(c);
        ui.setColor(1f, 1f, 1f, t);
        if (tex != null) ui.draw(tex, x, y, CARD_W, CARD_H);
        else if (c != null) {
            font.setColor(0.85f, 0.92f, 1f, t);
            font.draw(ui, c.name, x + 6f, y + CARD_H - 10f, CARD_W - 12f, -1, true);
        }
    }

    private void handleInput(float mx, float my, int cols, float btnY) {
        hoverMinus = hoverPlus = -1;
        for (int i = 0; i < cols; i++) {
            float bx = colX(i) + (COL_W - 20f - 96f) / 2f;
            if (my >= btnY && my <= btnY + 34f) {
                if (mx >= bx && mx <= bx + 40f) hoverMinus = i;
                if (mx >= bx + 56f && mx <= bx + 96f) hoverPlus = i;
            }
        }
        autoHover = mx >= px + 22f && mx <= px + 132f && my >= py + 20f && my <= py + 56f;
        okHover = mx >= px + pw - 150f && mx <= px + pw - 22f && my >= py + 20f && my <= py + 56f;
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) { finish(true); return; }
        if (Gdx.input.isKeyJustPressed(Input.Keys.A)) auto();
        if (Gdx.input.isKeyJustPressed(Input.Keys.ENTER) && remaining() == 0) { finish(false); return; }
        boolean touch = Gdx.input.isTouched();
        boolean clicked = touch && !prevTouch;
        prevTouch = touch;
        if (!clicked) return;
        if (autoHover) auto();
        else if (okHover && remaining() == 0) finish(false);
        else if (hoverPlus >= 0 && remaining() > 0) {
            if (hoverPlus >= blockers.size()) toPlayer++; else share[hoverPlus]++;
        } else if (hoverMinus >= 0) {
            if (hoverMinus >= blockers.size()) { if (toPlayer > 0) toPlayer--; }
            else if (share[hoverMinus] > 0) share[hoverMinus]--;
        }
    }

    private void finish(boolean cancelled) {
        closing = true;
        Result r = result;
        result = null;
        if (r != null) r.assigned(cancelled ? null : share.clone(), cancelled ? 0 : toPlayer);
    }
}
