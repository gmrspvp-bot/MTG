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

    /**
     * Where the grid is, worked out once per frame and read by both the drawing and the hit
     * testing, so a button is always clickable exactly where it is painted.
     *
     * The list used to be one column as tall as it needed to be. "Choose a creature type" hands
     * us about 250 options, which made the panel thousands of pixels tall: most of it off screen
     * at both ends, with no way to reach it (the owner, 2026-10-21). It now fits the screen,
     * spills into columns, and scrolls.
     */
    private int lCols = 1, lRows = 1, lTotalRows = 1;
    private float lBtnH = BTN_H, lGap = BTN_GAP, lColW = PANEL_W - 40f;
    private float lPx, lPy, lW, lH, lListTop;
    /** First visible grid row. Rows, not pixels: the grid is uniform, so there is no part row. */
    private int scrollRow;

    private void layout(int sw, int sh, float t) {
        int n = Math.max(1, options.size());
        // Past about twenty, being able to see the list at all matters more than big buttons.
        boolean compact = options.size() > 18;
        lBtnH = compact ? 34f : BTN_H;
        lGap = compact ? 6f : BTN_GAP;
        lColW = compact ? 230f : PANEL_W - 40f;
        float headerH = 74f, footerH = single() ? 40f : 58f;
        float maxW = sw - 80f, maxH = sh - 80f;
        int maxCols = Math.max(1, (int) ((maxW - 40f + lGap) / (lColW + lGap)));
        int rowsFit = Math.max(1, (int) ((maxH - headerH - footerH - 20f + lGap) / (lBtnH + lGap)));
        lCols = Math.min(maxCols, Math.max(1, (int) Math.ceil(n / (double) rowsFit)));
        lTotalRows = (int) Math.ceil(n / (double) lCols);
        lRows = Math.min(rowsFit, lTotalRows);
        int maxScroll = Math.max(0, lTotalRows - lRows);
        if (scrollRow > maxScroll) scrollRow = maxScroll;
        if (scrollRow < 0) scrollRow = 0;
        float listH = lRows * (lBtnH + lGap) - lGap;
        lW = 40f + lCols * lColW + (lCols - 1) * lGap;
        lH = headerH + listH + footerH + 20f;
        lPx = (sw - lW) / 2f;
        lPy = (sh - lH) / 2f - (1f - t) * 120f;
        lListTop = lPy + lH - headerH;
    }

    /** The option at a grid cell, or -1 past the end. Row major, so scrolling moves whole rows. */
    private int indexAt(int row, int col) {
        int i = (scrollRow + row) * lCols + col;
        return i >= 0 && i < options.size() ? i : -1;
    }

    /** Mouse wheel, forwarded by the owner since libGDX delivers it through an InputProcessor. */
    public void scrollBy(float amount) {
        scrollRow += (int) Math.signum(amount) * Math.max(1, lRows / 3);
    }

    public void render(SpriteBatch ui, ShapeRenderer shapes, BitmapFont font, BitmapFont fontBig) {
        if (!open) return;
        int sw = Gdx.graphics.getWidth(), sh = Gdx.graphics.getHeight();
        Matrix4 flat = new Matrix4().setToOrtho2D(0, 0, sw, sh);
        float t = anim * anim * (3f - 2f * anim);
        layout(sw, sh, t);
        float px = lPx, py = lPy, w = lW, h = lH, listTop = lListTop;
        boolean compact = options.size() > 18;

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
        for (int r = 0; r < lRows; r++) {
            for (int c = 0; c < lCols; c++) {
                int i = indexAt(r, c);
                if (i < 0) continue;
                float bx = px + 20f + c * (lColW + lGap);
                float by = listTop - (r + 1) * lBtnH - r * lGap;
                boolean sel = picked.contains(i);
                if (sel) shapes.setColor(0.95f, 0.70f, 0.25f, t);
                else if (i == hover) shapes.setColor(0.16f, 0.40f, 0.52f, t);
                else shapes.setColor(0.07f, 0.17f, 0.24f, t);
                shapes.rect(bx, by, lColW, lBtnH);
                Option o = options.get(i);
                if (o.swatch != null) {
                    shapes.setColor(o.swatch.r, o.swatch.g, o.swatch.b, t);
                    shapes.rect(bx + 10f, by + 10f, lBtnH - 20f, lBtnH - 20f);
                }
            }
        }
        // A scrollbar, only when there is somewhere to scroll to.
        if (lTotalRows > lRows) {
            float trackH = lRows * (lBtnH + lGap) - lGap;
            float thumbH = Math.max(24f, trackH * lRows / lTotalRows);
            float at = scrollRow / (float) Math.max(1, lTotalRows - lRows);
            shapes.setColor(0.10f, 0.24f, 0.32f, t);
            shapes.rect(px + w - 14f, listTop - trackH, 5f, trackH);
            shapes.setColor(0.35f, 0.62f, 0.76f, t);
            shapes.rect(px + w - 14f, listTop - thumbH - at * (trackH - thumbH), 5f, thumbH);
        }
        shapes.end();

        ui.setProjectionMatrix(flat);
        ui.begin();
        fontBig.setColor(0.85f, 0.95f, 1f, t);
        fontBig.draw(ui, title, px + 22f, py + h - 20f);
        font.setColor(0.60f, 0.76f, 0.88f, t);
        font.draw(ui, prompt, px + 22f, py + h - 48f);
        for (int r = 0; r < lRows; r++) {
            for (int c = 0; c < lCols; c++) {
                int i = indexAt(r, c);
                if (i < 0) continue;
                Option o = options.get(i);
                float bx = px + 20f + c * (lColW + lGap);
                float by = listTop - (r + 1) * lBtnH - r * lGap;
                boolean sel = picked.contains(i);
                float tx = bx + (o.swatch != null ? lBtnH + 4f : 14f);
                // The number key that picks it: the first nine ON SCREEN, since the keys are a
                // shortcut for what you can see, not an index into a list scrolled out of view.
                int shown = r * lCols + c;
                if (shown < 9) {
                    font.setColor(sel ? new Color(0.20f, 0.14f, 0.04f, t) : new Color(0.45f, 0.60f, 0.72f, t));
                    font.draw(ui, String.valueOf(shown + 1), bx + lColW - 16f, by + lBtnH / 2f + 6f);
                }
                BitmapFont lf = compact ? font : fontBig;
                lf.setColor(sel ? new Color(0.10f, 0.08f, 0.04f, t) : new Color(0.88f, 0.95f, 1f, t));
                boolean twoLine = !o.detail.isEmpty() && !compact;
                float labelY = twoLine ? by + lBtnH - 12f : by + lBtnH / 2f + (compact ? 5f : 9f);
                lf.draw(ui, o.label, tx, labelY);
                if (twoLine) {
                    font.setColor(sel ? new Color(0.22f, 0.16f, 0.06f, t) : new Color(0.58f, 0.72f, 0.82f, t));
                    font.draw(ui, o.detail, tx, by + 18f);
                }
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
            String hint = lTotalRows > lRows
                ? options.size() + " options   -   scroll or PgUp/PgDn   -   ESC cancels"
                : "click or press a number   -   ESC cancels";
            font.draw(ui, hint, px + 22f, py + 24f);
        }
        ui.end();
    }

    private void handleInput(float px, float listTop, float w, float py, int sh) {
        float mx = Gdx.input.getX(), my = sh - Gdx.input.getY();
        hover = -1;
        for (int r = 0; r < lRows; r++) {
            for (int c = 0; c < lCols; c++) {
                int i = indexAt(r, c);
                if (i < 0) continue;
                float bx = px + 20f + c * (lColW + lGap);
                float by = listTop - (r + 1) * lBtnH - r * lGap;
                if (mx >= bx && mx <= bx + lColW && my >= by && my <= by + lBtnH) hover = i;
            }
        }
        okHover = !single() && mx >= px + w - 115f && mx <= px + w - 40f && my >= py + 16f && my <= py + 42f;
        cancelHover = !single() && mx >= px + w - 205f && mx <= px + w - 135f && my >= py + 16f && my <= py + 42f;

        if (Keys3D.just(Input.Keys.ESCAPE)) { finish(true); return; }
        // Keys as well as the wheel: a list this long is miserable to work with one input.
        if (Keys3D.just(Input.Keys.DOWN)) scrollRow++;
        if (Keys3D.just(Input.Keys.UP)) scrollRow--;
        if (Keys3D.just(Input.Keys.PAGE_DOWN)) scrollRow += lRows;
        if (Keys3D.just(Input.Keys.PAGE_UP)) scrollRow -= lRows;
        if (Keys3D.just(Input.Keys.HOME)) scrollRow = 0;
        if (Keys3D.just(Input.Keys.END)) scrollRow = Math.max(0, lTotalRows - lRows);
        for (int k = 0; k < 9; k++) {
            if (!Keys3D.just(Input.Keys.NUM_1 + k)) continue;
            int i = indexAt(k / lCols, k % lCols);
            if (i >= 0) { toggle(i); return; }
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
