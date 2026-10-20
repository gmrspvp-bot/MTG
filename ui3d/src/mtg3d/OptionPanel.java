package mtg3d;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Matrix4;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The text-choice sibling of {@link SearchPanel}: a column of large buttons for every question
 * Forge asks that is not "pick some cards" - a modal spell's "choose one" (or two), a player, a
 * colour, a card type, a yes/no with more than two answers.
 *
 * Presentation only, as SearchPanel was at first: {@link #open} takes the options, how many may
 * be picked, and a {@link Result} callback, so the Forge side (IGuiGame one / many / getChoices
 * with string or player items) can be wired to it later without touching the drawing.
 *
 * Same feel as SearchPanel on purpose: it rises from below over a dimmed table, ignores input
 * until it is mostly open, ESC cancels. Keys 1-9 pick the matching option.
 */
public final class OptionPanel {

    /** One button. `swatch` (may be null) is drawn as a colour chip, for "choose a colour". */
    public static final class Option {
        public final String label, detail;
        public final Color swatch;
        public Option(String label, String detail, Color swatch) {
            this.label = label == null ? "" : label;
            this.detail = detail == null ? "" : detail;
            this.swatch = swatch;
        }
        public Option(String label) { this(label, "", null); }
    }

    /** What the caller gets back: the chosen indices in pick order. Empty means cancelled. */
    public interface Result {
        void chosen(List<Integer> indices);
    }

    private static final float OPEN_RATE = 5.0f, BTN_H = 58f, BTN_GAP = 10f, PANEL_W = 620f;

    private boolean open, closing;
    private float anim;
    private String title = "", prompt = "";
    private final List<Option> options = new ArrayList<>();
    private final Set<Integer> picked = new LinkedHashSet<>();
    private int minPick, maxPick;
    private Result result;
    private int hover = -1;
    private boolean okHover, cancelHover, prevTouch;

    /** @param minPick/maxPick  how many must / may be chosen; 1/1 picks on the first click. */
    public void open(String title, String prompt, List<Option> opts, int minPick, int maxPick, Result result) {
        this.title = title == null ? "" : title;
        this.prompt = prompt == null ? "" : prompt;
        options.clear();
        if (opts != null) options.addAll(opts);
        picked.clear();
        this.minPick = minPick;
        this.maxPick = Math.max(1, maxPick);
        this.result = result;
        open = true;
        closing = false;
        anim = 0f;
        prevTouch = true;         // swallow the click that opened it
    }

    public boolean isOpen() { return open; }

    /** Shut it without answering - the question went back to Forge (timeout, or H). */
    public void close() {
        if (open && !closing) closing = true;
    }

    private boolean live() { return open && !closing && anim > 0.85f; }

    private boolean single() { return maxPick == 1 && minPick <= 1; }

    public void update(float dt) {
        if (!open) return;
        float target = closing ? 0f : 1f;
        anim += (target - anim) * Math.min(1f, dt * OPEN_RATE);
        if (closing && anim < 0.02f) { open = false; closing = false; anim = 0f; }
        else if (!closing && Math.abs(1f - anim) < 0.005f) anim = 1f;
    }

    private void finish(boolean cancelled) {
        List<Integer> out = new ArrayList<>();
        if (!cancelled) out.addAll(picked);
        Result r = result;
        result = null;
        closing = true;
        if (r != null) r.chosen(out);
    }

    public void render(SpriteBatch ui, ShapeRenderer shapes, BitmapFont font, BitmapFont fontBig) {
        if (!open) return;
        int sw = Gdx.graphics.getWidth(), sh = Gdx.graphics.getHeight();
        Matrix4 flat = new Matrix4().setToOrtho2D(0, 0, sw, sh);
        float t = anim * anim * (3f - 2f * anim);

        float headerH = 74f, footerH = single() ? 40f : 58f;
        float listH = options.size() * (BTN_H + BTN_GAP) - BTN_GAP;
        float w = PANEL_W, h = headerH + listH + footerH + 20f;
        float px = (sw - w) / 2f, py = (sh - h) / 2f - (1f - t) * 120f;
        float listTop = py + h - headerH;

        if (live()) handleInput(px, listTop, w, py, sh);

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.setProjectionMatrix(flat);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0f, 0f, 0f, 0.55f * t);
        shapes.rect(0, 0, sw, sh);
        shapes.setColor(0.03f, 0.10f, 0.15f, 0.97f * t);
        shapes.rect(px, py, w, h);
        shapes.setColor(0.18f, 0.56f, 0.71f, t);
        shapes.rect(px, py + h - 4f, w, 4f);
        for (int i = 0; i < options.size(); i++) {
            float by = listTop - (i + 1) * BTN_H - i * BTN_GAP;
            boolean sel = picked.contains(i);
            if (sel) shapes.setColor(0.95f, 0.70f, 0.25f, t);
            else if (i == hover) shapes.setColor(0.16f, 0.40f, 0.52f, t);
            else shapes.setColor(0.07f, 0.17f, 0.24f, t);
            shapes.rect(px + 20f, by, w - 40f, BTN_H);
            Option o = options.get(i);
            if (o.swatch != null) {
                shapes.setColor(o.swatch.r, o.swatch.g, o.swatch.b, t);
                shapes.rect(px + 30f, by + 12f, BTN_H - 24f, BTN_H - 24f);
            }
        }
        shapes.end();

        ui.setProjectionMatrix(flat);
        ui.begin();
        fontBig.setColor(0.85f, 0.95f, 1f, t);
        fontBig.draw(ui, title, px + 22f, py + h - 20f);
        font.setColor(0.60f, 0.76f, 0.88f, t);
        font.draw(ui, prompt, px + 22f, py + h - 48f);
        GlyphLayout gl = new GlyphLayout();
        for (int i = 0; i < options.size(); i++) {
            Option o = options.get(i);
            float by = listTop - (i + 1) * BTN_H - i * BTN_GAP;
            boolean sel = picked.contains(i);
            float tx = px + (o.swatch != null ? 30f + BTN_H - 14f : 34f);
            // The number key that picks it, then the label, then any detail in a quieter colour.
            if (i < 9) {
                font.setColor(sel ? new Color(0.20f, 0.14f, 0.04f, t) : new Color(0.45f, 0.60f, 0.72f, t));
                font.draw(ui, String.valueOf(i + 1), px + w - 46f, by + BTN_H / 2f + 6f);
            }
            fontBig.setColor(sel ? new Color(0.10f, 0.08f, 0.04f, t) : new Color(0.88f, 0.95f, 1f, t));
            float labelY = o.detail.isEmpty() ? by + BTN_H / 2f + 9f : by + BTN_H - 12f;
            fontBig.draw(ui, o.label, tx, labelY);
            if (!o.detail.isEmpty()) {
                font.setColor(sel ? new Color(0.22f, 0.16f, 0.06f, t) : new Color(0.58f, 0.72f, 0.82f, t));
                gl.setText(font, o.detail);
                font.draw(ui, o.detail, tx, by + 18f);
            }
        }
        if (!single()) {
            boolean ready = picked.size() >= minPick;
            font.setColor(0.60f, 0.76f, 0.88f, t);
            font.draw(ui, picked.size() + " of " + (minPick == maxPick ? String.valueOf(maxPick) : minPick + "-" + maxPick)
                + " chosen", px + 22f, py + 34f);
            font.setColor(okHover ? new Color(1f, 0.92f, 0.60f, t)
                : ready ? new Color(0.65f, 0.95f, 1f, t) : new Color(0.35f, 0.45f, 0.52f, t));
            font.draw(ui, "Confirm", px + w - 110f, py + 34f);
            font.setColor(cancelHover ? new Color(1f, 0.80f, 0.70f, t) : new Color(0.55f, 0.68f, 0.78f, t));
            font.draw(ui, "Cancel", px + w - 200f, py + 34f);
        } else {
            font.setColor(0.35f, 0.50f, 0.62f, t);
            font.draw(ui, "click or press a number   -   ESC cancels", px + 22f, py + 24f);
        }
        ui.end();
    }

    private void handleInput(float px, float listTop, float w, float py, int sh) {
        float mx = Gdx.input.getX(), my = sh - Gdx.input.getY();
        hover = -1;
        for (int i = 0; i < options.size(); i++) {
            float by = listTop - (i + 1) * BTN_H - i * BTN_GAP;
            if (mx >= px + 20f && mx <= px + w - 20f && my >= by && my <= by + BTN_H) hover = i;
        }
        okHover = !single() && mx >= px + w - 115f && mx <= px + w - 40f && my >= py + 16f && my <= py + 42f;
        cancelHover = !single() && mx >= px + w - 205f && mx <= px + w - 135f && my >= py + 16f && my <= py + 42f;

        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) { finish(true); return; }
        for (int k = 0; k < Math.min(9, options.size()); k++) {
            if (Gdx.input.isKeyJustPressed(Input.Keys.NUM_1 + k)) { toggle(k); return; }
        }
        boolean touch = Gdx.input.isTouched();
        boolean clicked = touch && !prevTouch;
        prevTouch = touch;
        if (!clicked) return;
        if (okHover) { if (picked.size() >= minPick) finish(false); }
        else if (cancelHover) finish(true);
        else if (hover >= 0) toggle(hover);
    }

    private void toggle(int i) {
        if (picked.contains(i)) { picked.remove(i); return; }
        if (picked.size() >= maxPick) return;
        picked.add(i);
        if (single()) finish(false);      // one pick: no second click needed
    }
}
