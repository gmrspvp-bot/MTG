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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The opening hand, as the start of a game deserves: the seven cards fanned large across the
 * middle of the screen over a dimmed table, and two big buttons - Keep, or Mulligan (to six,
 * five...). After a London mulligan the same screen asks for the cards to put on the bottom:
 * click them (they lift and dim), Confirm lights once exactly N are chosen.
 *
 * Presentation only: {@link #openKeep} and {@link #openBottom} take the hand and a callback, so
 * Forge's mulligan Input and its London "choose cards to bottom" decision can be pointed at it.
 */
public final class MulliganPanel {

    public interface KeepResult { void keep(boolean keep); }
    public interface BottomResult { void bottom(List<Snapshot.CardV> cards); }

    private static final float OPEN_RATE = 5.0f;

    private final CardArt art;
    private boolean open, closing, bottomMode;
    private float anim;
    private final List<Snapshot.CardV> hand = new ArrayList<>();
    private final Set<Integer> picked = new LinkedHashSet<>();
    private int mulligans, toBottom;
    private KeepResult keepResult;
    private BottomResult bottomResult;
    private int hover = -1;
    /** True while the arrows own the highlight; the mouse takes it back when it moves. */
    private boolean keyPicking;
    private float lastMx = -1f, lastMy = -1f;
    private boolean keepHover, mullHover, prevTouch;

    public MulliganPanel(CardArt art) { this.art = art; }

    /** Keep or mulligan. `mulligans` = how many taken so far (0 for the first look). */
    public void openKeep(List<Snapshot.CardV> cards, int mulligans, KeepResult r) {
        start(cards);
        this.mulligans = mulligans;
        bottomMode = false;
        keepResult = r;
    }

    /** London mulligan: choose `n` cards to put on the bottom. */
    public void openBottom(List<Snapshot.CardV> cards, int n, BottomResult r) {
        start(cards);
        toBottom = n;
        bottomMode = true;
        bottomResult = r;
    }

    /**
     * Keep the cards shown in step with the hand while the panel is open.
     *
     * The panel used to take a copy of the hand the instant it opened and show that copy until
     * it was answered. The hand is still arriving at that point: on 2026-10-28 the owner's
     * opening seven was offered to him as "Your opening hand - 3 cards / Keep 3", with all seven
     * sitting on the table behind the panel. Nothing was wrong with the game - the panel had
     * simply photographed the hand too early and never looked again.
     *
     * Only ever while nothing has been picked. In the London step the player is choosing
     * specific cards to put on the bottom, and a list that shuffled itself under them mid-choice
     * would be a worse bug than the one this fixes.
     */
    void refresh(List<Snapshot.CardV> cards) {
        if (!open || closing || cards == null || !picked.isEmpty()) return;
        if (cards.size() == hand.size()) {
            boolean same = true;
            for (int i = 0; i < cards.size(); i++) {
                if (cards.get(i).id != hand.get(i).id) { same = false; break; }
            }
            if (same) return;
        }
        hand.clear();
        hand.addAll(cards);
        hover = -1;
    }

    private void start(List<Snapshot.CardV> cards) {
        hand.clear();
        if (cards != null) hand.addAll(cards);
        picked.clear();
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

    public void update(float dt) {
        if (!open) return;
        float target = closing ? 0f : 1f;
        anim += (target - anim) * Math.min(1f, dt * OPEN_RATE);
        if (closing && anim < 0.02f) { open = false; closing = false; anim = 0f; }
        else if (!closing && Math.abs(1f - anim) < 0.005f) anim = 1f;
    }

    // Geometry shared by drawing and input.
    private float cardW, cardH, step, left, baseY, btnY, btnW = 260f, btnH = 64f;

    private void layout(int sw, int sh, float t) {
        cardH = Math.min(sh * 0.42f, 360f);
        cardW = cardH * 5f / 7f;
        int n = Math.max(1, hand.size());
        step = Math.min(cardW + 16f, (sw - 160f - cardW) / Math.max(1, n - 1));
        float total = (n - 1) * step + cardW;
        left = (sw - total) / 2f;
        baseY = sh * 0.36f - (1f - t) * 160f;
        btnY = baseY - btnH - 40f;
    }

    private float cardX(int i) { return left + i * step; }

    private float cardY(int i) {
        float lift = picked.contains(i) ? 40f : (i == hover ? 16f : 0f);
        return baseY + lift;
    }

    public void render(SpriteBatch ui, ShapeRenderer shapes, BitmapFont font, BitmapFont fontBig) {
        if (!open) return;
        int sw = Gdx.graphics.getWidth(), sh = Gdx.graphics.getHeight();
        Matrix4 flat = new Matrix4().setToOrtho2D(0, 0, sw, sh);
        float t = anim * anim * (3f - 2f * anim);
        layout(sw, sh, t);
        float mx = Gdx.input.getX(), my = sh - Gdx.input.getY();
        float keepX = sw / 2f - btnW - 20f, mullX = sw / 2f + 20f;
        if (live()) handleInput(mx, my, keepX, mullX);

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.setProjectionMatrix(flat);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0f, 0f, 0f, 0.68f * t);
        shapes.rect(0, 0, sw, sh);
        for (int i = 0; i < hand.size(); i++) {
            if (!picked.contains(i) && i != hover) continue;
            shapes.setColor(picked.contains(i) ? new Color(0.95f, 0.70f, 0.25f, t) : new Color(0.30f, 0.62f, 0.78f, t));
            shapes.rect(cardX(i) - 5f, cardY(i) - 5f, cardW + 10f, cardH + 10f);
        }
        boolean ready = !bottomMode || picked.size() == toBottom;
        shapes.setColor(ready ? (keepHover ? 0.22f : 0.13f) : 0.07f, ready ? (keepHover ? 0.62f : 0.46f) : 0.20f,
            ready ? (keepHover ? 0.36f : 0.26f) : 0.14f, t);
        shapes.rect(keepX, btnY, btnW, btnH);
        if (!bottomMode) {
            shapes.setColor(mullHover ? 0.56f : 0.42f, mullHover ? 0.22f : 0.16f, mullHover ? 0.18f : 0.13f, t);
            shapes.rect(mullX, btnY, btnW, btnH);
        }
        shapes.end();

        ui.setProjectionMatrix(flat);
        ui.begin();
        for (int i = 0; i < hand.size(); i++) {
            Texture tex = art == null ? null : art.get(hand.get(i));
            float a = bottomMode && picked.contains(i) ? 0.55f * t : t;     // cards going to the bottom dim
            ui.setColor(1f, 1f, 1f, a);
            if (tex != null) ui.draw(tex, cardX(i), cardY(i), cardW, cardH);
            else {
                font.setColor(0.85f, 0.92f, 1f, a);
                font.draw(ui, hand.get(i).name, cardX(i) + 8f, cardY(i) + cardH - 12f, cardW - 16f, -1, true);
            }
        }
        ui.setColor(1f, 1f, 1f, 1f);
        GlyphLayout gl = new GlyphLayout();
        fontBig.getData().setScale(2.2f);
        fontBig.setColor(1f, 0.86f, 0.45f, t);
        String head = bottomMode ? "Put " + toBottom + " on the bottom" : "Your opening hand";
        gl.setText(fontBig, head);
        fontBig.draw(ui, head, (sw - gl.width) / 2f, baseY + cardH + 90f);
        fontBig.getData().setScale(1.6f);
        String sub = bottomMode ? picked.size() + " of " + toBottom
                + " chosen - click one, or arrows and SPACE"
            : hand.size() + " cards" + (mulligans > 0 ? "   (mulligan " + mulligans + ")" : "");
        font.setColor(0.70f, 0.84f, 0.95f, t);
        gl.setText(font, sub);
        font.draw(ui, sub, (sw - gl.width) / 2f, baseY + cardH + 46f);
        fontBig.setColor(ready ? new Color(0.92f, 1f, 0.94f, t) : new Color(0.45f, 0.55f, 0.50f, t));
        String keepLabel = bottomMode ? "Confirm" : "Keep " + hand.size();
        gl.setText(fontBig, keepLabel);
        fontBig.draw(ui, keepLabel, keepX + (btnW - gl.width) / 2f, btnY + btnH / 2f + gl.height / 2f);
        if (!bottomMode) {
            String mull = "Mulligan (to " + Math.max(0, 7 - mulligans - 1) + ")";
            fontBig.setColor(1f, 0.92f, 0.88f, t);
            gl.setText(fontBig, mull);
            fontBig.draw(ui, mull, mullX + (btnW - gl.width) / 2f, btnY + btnH / 2f + gl.height / 2f);
        }
        font.setColor(0.45f, 0.58f, 0.68f, t);
        String keys = bottomMode
            ? "ENTER or Start confirms   -   arrows / D-pad choose, SPACE or A takes"
            : "SPACE or A keeps   -   X mulligans";
        gl.setText(font, keys);
        font.draw(ui, keys, (sw - gl.width) / 2f, btnY - 16f);
        ui.end();
    }

    private void handleInput(float mx, float my, float keepX, float mullX) {
        // The keyboard owns the highlight while it is being used; the mouse takes it back the
        // moment it moves. Same rule as SearchPanel, and the same reason: a resting pointer
        // otherwise drags the highlight back every frame.
        boolean mouseMoved = mx != lastMx || my != lastMy;
        lastMx = mx; lastMy = my;
        if (mouseMoved) keyPicking = false;

        if (!keyPicking) {
            hover = -1;
            // Topmost card wins where they overlap: scan right to left.
            for (int i = hand.size() - 1; i >= 0; i--) {
                if (mx >= cardX(i) && mx <= cardX(i) + cardW && my >= cardY(i) && my <= cardY(i) + cardH) { hover = i; break; }
            }
        }
        // Choosing which cards go to the bottom was click-only, so with a controller the panel
        // could be reached and not answered (the owner, 2026-10-21). The arrows walk the hand,
        // SPACE takes the one under the highlight. Through Keys3D, so the D-pad does it too.
        if (bottomMode && !hand.isEmpty()) {
            int moved = -1;
            if (Keys3D.just(Input.Keys.RIGHT)) moved = Math.min(hand.size() - 1, Math.max(0, hover) + 1);
            if (Keys3D.just(Input.Keys.LEFT))  moved = Math.max(0, Math.max(0, hover) - 1);
            if (moved >= 0) { keyPicking = true; hover = moved; }
            if (Keys3D.just(Input.Keys.SPACE) && hover >= 0) {
                if (picked.contains(hover)) picked.remove(hover);
                else if (picked.size() < toBottom) picked.add(hover);
            }
        }
        keepHover = mx >= keepX && mx <= keepX + btnW && my >= btnY && my <= btnY + btnH;
        mullHover = !bottomMode && mx >= mullX && mx <= mullX + btnW && my >= btnY && my <= btnY + btnH;
        boolean ready = !bottomMode || picked.size() == toBottom;
        if (!bottomMode && Keys3D.just(Input.Keys.SPACE)) { done(true); return; }
        // X as well as M: X is the table's "the other button" everywhere else, and it is what
        // the pad's X is mapped to while a panel is up. M stays because it was here first.
        if (!bottomMode && (Keys3D.just(Input.Keys.M) || Keys3D.just(Input.Keys.X))) { done(false); return; }
        if (bottomMode && ready && Keys3D.just(Input.Keys.ENTER)) { done(true); return; }
        boolean touch = Pad.touch();
        boolean clicked = touch && !prevTouch;
        prevTouch = touch;
        if (!clicked) return;
        if (keepHover && ready) done(true);
        else if (mullHover) done(false);
        else if (bottomMode && hover >= 0) {
            if (picked.contains(hover)) picked.remove(hover);
            else if (picked.size() < toBottom) picked.add(hover);
        }
    }

    private void done(boolean keep) {
        closing = true;
        if (bottomMode) {
            BottomResult r = bottomResult;
            bottomResult = null;
            List<Snapshot.CardV> out = new ArrayList<>();
            for (int i : picked) out.add(hand.get(i));
            if (r != null) r.bottom(out);
        } else {
            KeepResult r = keepResult;
            keepResult = null;
            if (r != null) r.keep(keep);
        }
    }
}
