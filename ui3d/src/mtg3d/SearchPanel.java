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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The panel every search opens into: look through a library, browse a graveyard, pick targets,
 * choose a mode, order triggers. One component rather than one per question, because Forge asks
 * these in a dozen slightly different ways and they should all look and behave the same.
 *
 * Built as presentation first. Stage 3 wires it to the `IGuiGame` callbacks that currently fall
 * through to Forge's own UI, which is why {@link #open} takes a {@link Result} callback and a
 * min/max rather than assuming a single pick.
 *
 * Deliberately 2D over the 3D table. A library search is ninety-nine cards; a fan of them in world
 * space looks impressive and is miserable to read. Screen space gives a dense scrollable grid, and
 * the cards are the same {@link CardArt} textures the table uses, so nothing is loaded twice.
 *
 * The open and close animation runs on one eased 0..1 value, the same approach as the camera: the
 * panel rises and scales from the bottom of the screen while the backdrop fades in, so it reads as
 * one movement. Input is ignored until it is most of the way open, so a click meant for the board
 * cannot be caught by a panel that is still arriving.
 */
public final class SearchPanel {

    /** What the caller gets back. An empty list means cancelled. */
    public interface Result {
        void chosen(List<Snapshot.CardV> cards);
    }

    private static final float OPEN_RATE = 5.0f;        // per second
    private static final float CARD_W = 150f, CARD_H = 210f, PAD = 14f;

    private boolean open;
    private float anim;                                  // 0 shut, 1 fully open
    private boolean closing;

    private String title = "";
    private String prompt = "";
    private final List<Snapshot.CardV> items = new ArrayList<>();
    private final Set<Integer> picked = new LinkedHashSet<>();   // indices, in the order picked
    private int minPick, maxPick;
    private Result result;

    private float scroll;
    private int hoverIndex = -1;
    private boolean okHover, cancelHover;
    private boolean prevTouch;

    private final CardArt art;

    public SearchPanel(CardArt art) {
        this.art = art;
    }

    /**
     * @param minPick  how many must be chosen before OK is allowed
     * @param maxPick  0 for a browse-only panel (graveyard, exile, revealed cards)
     */
    public void open(String title, String prompt, List<Snapshot.CardV> cards,
                     int minPick, int maxPick, Result result) {
        this.title = title == null ? "" : title;
        this.prompt = prompt == null ? "" : prompt;
        this.items.clear();
        if (cards != null) this.items.addAll(cards);
        this.picked.clear();
        this.minPick = minPick;
        this.maxPick = maxPick;
        this.result = result;
        this.scroll = 0f;
        this.open = true;
        this.closing = false;
        this.anim = 0f;
        this.prevTouch = true;     // swallow the click that opened it
    }

    public boolean isOpen() {
        return open;
    }

    /** Shut it without answering - what it showed has gone (Forge hid the revealed cards). */
    public void close() {
        if (open && !closing) {
            closing = true;
            result = null;
        }
    }

    /** True once it is far enough open to take input - see the note in the class comment. */
    private boolean live() {
        return open && !closing && anim > 0.85f;
    }

    public void update(float dt) {
        if (!open) return;
        float target = closing ? 0f : 1f;
        anim += (target - anim) * Math.min(1f, dt * OPEN_RATE);
        if (closing && anim < 0.02f) {
            open = false;
            closing = false;
            anim = 0f;
        } else if (!closing && Math.abs(1f - anim) < 0.005f) {
            anim = 1f;
        }
    }

    private void finish(boolean cancelled) {
        List<Snapshot.CardV> out = new ArrayList<>();
        if (!cancelled) for (int i : picked) out.add(items.get(i));
        Result r = result;
        closing = true;
        result = null;
        if (r != null) r.chosen(out);
    }

    /** Draws the panel and handles its input. Returns true if it consumed the mouse this frame. */
    public boolean render(SpriteBatch ui, ShapeRenderer shapes, BitmapFont font, BitmapFont fontBig) {
        if (!open) return false;

        int sw = Gdx.graphics.getWidth(), sh = Gdx.graphics.getHeight();
        Matrix4 flat = new Matrix4().setToOrtho2D(0, 0, sw, sh);
        float t = smooth(anim);

        float panelW = Math.min(sw - 120f, 1180f);
        float panelH = Math.min(sh - 120f, 760f);
        // Rise from the bottom and scale up slightly, so it arrives rather than appearing.
        float scale = 0.90f + 0.10f * t;
        float w = panelW * scale, h = panelH * scale;
        float px = (sw - w) / 2f;
        float py = (sh - h) / 2f - (1f - t) * 120f;

        if (live()) handleInput(px, py, w, h, sh);

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        shapes.setProjectionMatrix(flat);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0f, 0f, 0f, 0.62f * t);                       // backdrop
        shapes.rect(0, 0, sw, sh);
        shapes.setColor(0.03f, 0.10f, 0.15f, 0.97f * t);              // panel
        shapes.rect(px, py, w, h);
        shapes.setColor(0.18f, 0.56f, 0.71f, t);                      // top rule
        shapes.rect(px, py + h - 4f, w, 4f);
        shapes.end();

        float headerH = 62f, footerH = 58f;
        float gridY = py + footerH, gridH = h - headerH - footerH;
        drawCardSlots(shapes, flat, px, gridY, w, gridH, t);

        ui.setProjectionMatrix(flat);
        ui.begin();
        drawCards(ui, font, px, gridY, w, gridH, t);
        fontBig.setColor(0.85f, 0.95f, 1f, t);
        fontBig.draw(ui, title, px + 22f, py + h - 20f);
        font.setColor(0.60f, 0.76f, 0.88f, t);
        font.draw(ui, prompt, px + 22f, py + h - 44f);
        drawFooter(ui, font, px, py, w, footerH, t);
        ui.end();

        return true;
    }

    /** Selection highlights sit behind the card art, so they read as a frame around it. */
    private void drawCardSlots(ShapeRenderer shapes, Matrix4 flat, float px, float gy,
                               float w, float gh, float t) {
        int cols = Math.max(1, (int) ((w - PAD) / (CARD_W + PAD)));
        shapes.setProjectionMatrix(flat);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < items.size(); i++) {
            float[] r = slot(i, cols, px, gy, gh, w);
            if (r == null) continue;
            boolean sel = picked.contains(i);
            if (sel) shapes.setColor(0.95f, 0.70f, 0.25f, t);
            else if (i == hoverIndex) shapes.setColor(0.25f, 0.55f, 0.70f, t);
            else shapes.setColor(0.07f, 0.16f, 0.22f, t);
            float m = sel || i == hoverIndex ? 4f : 2f;
            shapes.rect(r[0] - m, r[1] - m, CARD_W + m * 2, CARD_H + m * 2);
        }
        shapes.end();
    }

    private void drawCards(SpriteBatch ui, BitmapFont font, float px, float gy,
                           float w, float gh, float t) {
        int cols = Math.max(1, (int) ((w - PAD) / (CARD_W + PAD)));
        for (int i = 0; i < items.size(); i++) {
            float[] r = slot(i, cols, px, gy, gh, w);
            if (r == null) continue;
            Snapshot.CardV c = items.get(i);
            Texture tex = art == null ? null : art.get(c);
            if (tex != null) {
                ui.setColor(1f, 1f, 1f, t);
                ui.draw(tex, r[0], r[1], CARD_W, CARD_H);
            } else {
                // The face is still being drawn on a worker thread; show the name meanwhile.
                ui.setColor(1f, 1f, 1f, t);
                font.setColor(0.80f, 0.88f, 0.95f, t);
                font.draw(ui, c.name, r[0] + 6f, r[1] + CARD_H - 10f, CARD_W - 12f, -1, true);
            }
            if (picked.contains(i)) {
                font.setColor(1f, 0.88f, 0.45f, t);
                font.draw(ui, String.valueOf(order(i)), r[0] + 6f, r[1] + CARD_H - 6f);
            }
        }
        ui.setColor(1f, 1f, 1f, 1f);
    }

    /** 1-based position in the pick order, which matters when the caller asked for an ordering. */
    private int order(int index) {
        int n = 1;
        for (int i : picked) {
            if (i == index) return n;
            n++;
        }
        return n;
    }

    /** Screen rect of item i, or null if scrolled out of view. */
    private float[] slot(int i, int cols, float px, float gy, float gh, float w) {
        int col = i % cols, row = i / cols;
        float gridLeft = px + (w - (cols * (CARD_W + PAD) - PAD)) / 2f;
        float x = gridLeft + col * (CARD_W + PAD);
        float y = gy + gh - CARD_H - PAD - row * (CARD_H + PAD) + scroll;
        if (y + CARD_H < gy || y > gy + gh) return null;
        return new float[] { x, y };
    }

    private void drawFooter(SpriteBatch ui, BitmapFont font, float px, float py,
                            float w, float footerH, float t) {
        String okLabel = maxPick <= 0 ? "Close" : "Confirm";
        boolean okReady = maxPick <= 0 || picked.size() >= minPick;
        font.setColor(okReady ? new Color(0.65f, 0.95f, 1f, t) : new Color(0.35f, 0.45f, 0.52f, t));
        font.draw(ui, okLabel, px + w - 150f, py + 34f);
        if (maxPick > 0) {
            font.setColor(0.55f, 0.68f, 0.78f, t);
            font.draw(ui, "Cancel", px + w - 250f, py + 34f);
            font.setColor(0.60f, 0.76f, 0.88f, t);
            String counter = picked.size() + " of " + (minPick == maxPick ? String.valueOf(maxPick)
                : minPick + "-" + maxPick) + " chosen";
            font.draw(ui, counter, px + 22f, py + 34f);
        }
        font.setColor(0.35f, 0.50f, 0.62f, t);
        font.draw(ui, items.size() + " cards   -   scroll to see more   -   ESC closes", px + 22f, py + 16f);
    }

    private void handleInput(float px, float py, float w, float h, int sh) {
        float mx = Gdx.input.getX(), my = sh - Gdx.input.getY();
        float footerH = 58f, headerH = 62f;
        float gridY = py + footerH, gridH = h - headerH - footerH;
        int cols = Math.max(1, (int) ((w - PAD) / (CARD_W + PAD)));

        // Scrolling is clamped to the content, so an over-scroll cannot leave an empty panel.
        int rows = (items.size() + cols - 1) / cols;
        float contentH = rows * (CARD_H + PAD);
        float maxScroll = Math.max(0f, contentH - gridH + PAD);
        if (scroll < 0f) scroll = 0f;
        if (scroll > maxScroll) scroll = maxScroll;

        hoverIndex = -1;
        for (int i = 0; i < items.size(); i++) {
            float[] r = slot(i, cols, px, gridY, gridH, w);
            if (r == null) continue;
            if (mx >= r[0] && mx <= r[0] + CARD_W && my >= r[1] && my <= r[1] + CARD_H) {
                hoverIndex = i;
                break;
            }
        }

        okHover = mx >= px + w - 155f && mx <= px + w - 80f && my >= py + 18f && my <= py + 44f;
        cancelHover = maxPick > 0 && mx >= px + w - 255f && mx <= px + w - 185f
            && my >= py + 18f && my <= py + 44f;

        boolean touch = Gdx.input.isTouched();
        boolean clicked = touch && !prevTouch;
        prevTouch = touch;

        if (Keys3D.just(Input.Keys.ESCAPE)) { finish(true); return; }

        if (clicked) {
            if (okHover) {
                if (maxPick <= 0 || picked.size() >= minPick) finish(false);
            } else if (cancelHover) {
                finish(true);
            } else if (hoverIndex >= 0 && maxPick > 0) {
                if (picked.contains(hoverIndex)) {
                    picked.remove(hoverIndex);
                } else if (picked.size() < maxPick) {
                    picked.add(hoverIndex);
                    // A single pick is the common case and should not need a second click.
                    if (maxPick == 1 && minPick <= 1) finish(false);
                }
            }
        }
    }

    /** Mouse wheel, forwarded by the owner since libGDX delivers it through an InputProcessor. */
    public void scrollBy(float amount) {
        if (!open) return;
        scroll += amount * (CARD_H + PAD) * 0.5f;
        if (scroll < 0f) scroll = 0f;
    }

    private static float smooth(float x) {
        return x * x * (3f - 2f * x);
    }
}
