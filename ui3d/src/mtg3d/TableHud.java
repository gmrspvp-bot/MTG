package mtg3d;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.VertexAttributes.Usage;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.math.collision.Ray;

import java.util.ArrayList;
import java.util.List;

/**
 * Every 2D overlay over the table: phase panel, seat labels, zone labels, centre tracker, stack
 * labels, card chips, prompt bar, yes/no banner, event log, game over, hover card. See TableBase.
 */
abstract class TableHud extends TableScene {

    void drawOverlay() {
        int w = Gdx.graphics.getWidth(), h = Gdx.graphics.getHeight();
        Matrix4 flat = new Matrix4().setToOrtho2D(0, 0, w, h);
        beatClock += frameDt;
        noticeChanges();

        drawCardBadges(flat);
        // The phase panel's background, BEFORE the label pass below: drawn after it (as it was),
        // its 88%-opaque fill covered the panel's own text and also the hover card and banner.
        drawPhasePanelShapes(h, flat);
        ui.setProjectionMatrix(flat);
        ui.begin();
        // The turn, the phase and which seat we are at used to be printed here as well as on the
        // phase panel, in the same corner, so the two drew on top of each other. The panel owns
        // all three now.
        // The chosen view is named, because which one you are in is now a setting you picked
        // rather than something the game did to you, and it survives a restart.
        TableCamera.View cv = TableCamera.VIEWS[Math.max(1, Math.min(TableCamera.VIEWS.length - 1, currentView))];
        String mode = (lift > 0.5f ? "OVERHEAD" : "SEATED")
            + "   view " + currentView + " - " + cv.name
            + (manualOverride && !cv.auto ? "" : " (following the game)")
            + "   DOWN for your seat";
        font.setColor(0.45f, 0.62f, 0.75f, 1f);
        font.draw(ui, mode, 14, 38);
        font.setColor(0.35f, 0.50f, 0.62f, 1f);
        // Whether a controller was found at all. Without this the only way to tell a pad that
        // is not detected from a binding that is wrong is to guess (the owner, 2026-10-21).
        if (Pad.connected()) {
            font.setColor(0.55f, 0.85f, 0.65f, 1f);
            font.draw(ui, "controller ready - stick picks a card, A plays it", 14, 56);
            font.setColor(0.35f, 0.50f, 0.62f, 1f);
        }
        // One short line, not the whole key list: that is on the help card (F1), and the long
        // line sat in a band across the bottom of the screen (owner, 2026-10-21).
        font.draw(ui, "F1  keys      SPACE  pass      A / D  walk      W  overhead      S  your seat"
            + (cues != null && cues.muted() ? "      (sound off)" : ""), 14, 18);
        // Art counter: says at a glance whether Forge's picture cache was found at all, which is
        // the first thing to check if every card is still a coloured block.
        if (art != null) {
            String a = "art " + art.loaded() + " loaded / " + art.known() + " cached";
            font.draw(ui, a, w - 190, 18);
        }
        drawPhasePanel(w, h);
        bug.drawCogText(ui, font, w, h);
        help.drawButtonText(ui, font, w, h);
        help.draw(shapes, ui, font, fontBig, w, h,
            new com.badlogic.gdx.math.Matrix4().setToOrtho2D(0, 0, w, h));
        // Strict mode: say so the moment Forge answers something the 3D window should own.
        // Without this a gap is invisible - the window just loses focus and nobody knows why.
        String fell = Gui3D.recentFallback();
        if (!fell.isEmpty()) {
            font.setColor(1f, 0.55f, 0.35f, 1f);
            font.draw(ui, "Forge answered: " + fell + "  (not taken over yet)", 14f, 80f);
        }
        // Things that would otherwise be silence - a click that counted for nothing because the
        // card had no ability Forge would offer. Middle of the screen, under the banners, where
        // someone clicking a land for the third time is actually looking.
        String note = Gui3D.recentNote();
        if (!note.isEmpty()) {
            GlyphLayout ngl = new GlyphLayout(fontBig, note);
            fontBig.setColor(1f, 0.82f, 0.45f, 1f);
            fontBig.draw(ui, note, (w - ngl.width) / 2f, h * 0.36f);
        }
        drawPromptBar(w, h);
        if (hoveredSeat >= 0 && hoveredSeat < snap.seats.size() && hovered == null) {
            // Just the name. It used to read "click to choose X", which implied a choice was
            // pending even long after one had been made - a seat is always clickable and Forge
            // simply ignores it when it is not asking for a player.
            font.setColor(1f, 0.86f, 0.42f, 1f);
            font.draw(ui, snap.seats.get(hoveredSeat).name,
                Gdx.input.getX() + 16f, h - Gdx.input.getY() - 6f);
        }
        if (!snap.forgeDialog.isEmpty()) {
            // Forge has a modal box open that this window cannot answer. Saying so beats the 3D
            // window looking idle while the game waits on something the player has not seen.
            font.setColor(1f, 0.70f, 0.35f, 1f);
            font.draw(ui, "Forge is asking: " + snap.forgeDialog, 14f, 62f);
            font.setColor(0.70f, 0.56f, 0.40f, 1f);
            font.draw(ui, "answer it in Forge's own window", 14f, 46f);
        }
        drawSeatInfo(w, h);
        drawZoneLabels();
        drawStackCounts();
        drawBeadCounts();
        drawBlockPairs();
        drawLifePops();
        drawTargetBanner(w, h);
        drawStackLabels();
        drawAbilityOnSources();
        ui.end();
        // Outside the overlay's batch: this one is drawn in the world, on the glass of each
        // screen, so it needs the camera's projection and a batch of its own.
        drawCentreTracker();
        drawPutAside(w, h);
        if (yourTurnAge >= 0f) drawYourTurn(w, h, flat);
        if (turnBannerAge >= 0f) drawTurnBanner(w, h, flat);
        if (phaseBannerAge >= 0f) drawPhaseBanner(w, h, flat);
        if (respondAge >= 0f) drawResponseBanner(w, h, flat);
        drawToast(w, flat);
        if (showLog) drawEventLog(w, h, flat);
        if (snap.gameOver) drawGameOver(w, h, flat);
        // Last, so the hovered card sits over every label rather than having them bleed through.
        // A card picked out in a search panel is NOT drawn here - the panel itself is drawn after
        // this whole overlay, so a pop-up raised here came out behind it (the owner, 2026-10-22).
        // See drawPanelHover, which Table3D calls once the panels are down.
        if (hovered != null && (search == null || search.hoveredCard() == null)) {
            drawDetailPanel(w, h, flat);
        }
        bug.drawCog(shapes, ui, font, w, h, flat);
        help.drawButton(shapes, w, h, flat);
        // Last of all, over everything. It used to be drawn before the seat names, the zone
        // labels and the life totals, all of which are printed on the board behind it - so the
        // question came out with the table's own writing through the middle of it (the owner,
        // 2026-10-22). It is the only thing on screen the game is actually waiting for; it goes
        // on top.
        drawPendingAsk(w, h);
    }

    /**
     * The enlarged card for whatever is picked out inside the search panel, drawn AFTER the panel.
     *
     * The grid in the panel is too small to read, which is the whole reason the pop-up was asked
     * for; drawn with the rest of the overlay it was covered by the very panel it belongs to.
     * Everything here is one card over the top of a panel that is already finished, so it owns
     * its own drawing and takes no part in the overlay's batching.
     */
    void drawPanelHover() {
        Snapshot.CardV inPanel = search == null ? null : search.hoveredCard();
        if (inPanel == null) return;
        int w = Gdx.graphics.getWidth(), h = Gdx.graphics.getHeight();
        Matrix4 flat = new Matrix4().setToOrtho2D(0, 0, w, h);
        Drawn d = new Drawn();
        d.card = inPanel;
        Drawn was = hovered;
        hovered = d;
        try {
            drawDetailPanel(w, h, flat);
        } finally {
            hovered = was;
        }
    }

    /**
     * Power/toughness and counters on cards that show their real printed picture. A generated face
     * draws these itself, but a cached picture only carries the PRINTED numbers, which go stale
     * as soon as anything pumps the creature or puts a counter on it. So: a small chip over the
     * bottom-right corner with the current P/T (where the printed box is), and one over the
     * bottom-left with the counters. Pinned to the card's corners through its drawn transform, so
     * they follow it as it taps and moves; dropped when the card is too small on screen to fit them.
     */
    void drawCardBadges(Matrix4 flat) {
        if (art == null) return;
        List<float[]> boxes = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        List<Color> colours = new ArrayList<>();
        GlyphLayout gl = new GlyphLayout();
        Vector3 a = new Vector3(), b = new Vector3();
        for (Drawn d : drawn) {
            Snapshot.CardV c = d.card;
            if (d.seat == null || d.face == null || !art.hasPicture(c)) continue;   // battlefield, real art
            if (c.attachedToId >= 0 && hostTargets.containsKey(c.attachedToId)) continue;   // under its host
            // On-screen width of the card, to skip ones too small (or seen edge-on) for a chip.
            cam.project(a.set(-CARD_W / 2f, 0f, 0f).mul(d.inst.transform));
            cam.project(b.set(CARD_W / 2f, 0f, 0f).mul(d.inst.transform));
            if (a.z > 1f || b.z > 1f || a.dst(b) < 34f) continue;
            // Both chips stack at the bottom-right corner (counters above P/T): side by side, a
            // counter chip on the left corner ran into the neighbouring card's P/T chip.
            float lift = 0f;
            if (c.creature && !c.power.isEmpty()) {
                lift = badge(d, c.power + "/" + c.toughness, 0f, new Color(0.96f, 0.93f, 0.82f, 1f),
                             gl, boxes, texts, colours);
            }
            if (c.counters > 0) {
                String t = c.counterText.isEmpty() ? c.counters + "" : c.counterText.get(0)
                    + (c.counterText.size() > 1 ? " +" : "");
                badge(d, t, lift, new Color(0.55f, 0.95f, 0.65f, 1f), gl, boxes, texts, colours);
            }
        }
        if (boxes.isEmpty()) return;
        Gdx.gl.glEnable(GL20.GL_BLEND);
        shapes.setProjectionMatrix(flat);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (float[] r : boxes) {
            shapes.setColor(0.03f, 0.05f, 0.08f, 0.88f);
            shapes.rect(r[0], r[1], r[2], r[3]);
        }
        shapes.end();
        ui.setProjectionMatrix(flat);
        ui.begin();
        for (int i = 0; i < boxes.size(); i++) {
            float[] r = boxes.get(i);
            font.setColor(colours.get(i));
            font.draw(ui, texts.get(i), r[0] + 4f, r[1] + r[3] - 3f);
        }
        ui.end();
    }

    /**
     * One chip over the card's bottom-right corner (where the printed P/T box is), raised `up`
     * pixels on screen. Returns how far the next chip must be raised to sit above this one.
     */
    float badge(Drawn d, String text, float up, Color colour, GlyphLayout gl,
                        List<float[]> boxes, List<String> texts, List<Color> colours) {
        // Bottom edge is local +Z (towards the card's owner); a little inside it, on the face.
        Vector3 p = cam.project(chipCorner(d).mul(d.inst.transform));
        if (p.z > 1f) return up;
        gl.setText(font, text);
        float bw = gl.width + 8f, bh = gl.height + 7f;
        boxes.add(new float[] { p.x - bw / 2f, p.y - bh / 2f + up, bw, bh });
        texts.add(text);
        colours.add(colour);
        return up + bh + 2f;
    }

    /**
     * Where a card's chips go, in the card's own frame: over the printed P/T box (bottom right),
     * unless the card is in a squeezed row and the next card covers that corner. Then it is
     * whichever corner lies furthest BACK along the row - the strip that still shows - worked
     * out in the world, so it is right for a tapped card too.
     */
    Vector3 chipCorner(Drawn d) {
        float x = CARD_W / 2f - 0.2f, z = CARD_H / 2f - 0.13f;
        float[] dir = d.card == null ? null : squeezed.get(d.card.id);
        if (dir == null) return new Vector3(x, CARD_THICK, z);
        // The two corners furthest back along the row are the visible strip's ends; alternate
        // cards take alternate ends (dir[2]), so neighbouring chips do not run together.
        java.util.List<Vector3> corners = new java.util.ArrayList<>();
        final java.util.Map<Vector3, Float> along = new java.util.HashMap<>();
        Vector3 w = new Vector3();
        for (float sx : new float[] { -1f, 1f }) {
            for (float sz : new float[] { -1f, 1f }) {
                Vector3 local = new Vector3(sx * x, CARD_THICK, sz * z);
                w.set(local).mul(d.inst.transform);
                along.put(local, w.x * dir[0] + w.z * dir[1]);
                corners.add(local);
            }
        }
        corners.sort((p1, p2) -> Float.compare(along.get(p1), along.get(p2)));
        Vector3 a1 = corners.get(0), a2 = corners.get(1);
        // Order the two by which is nearer the camera, so "end 0" is the same end on every card
        // in the row, tapped or not.
        Vector3 w1 = new Vector3(a1).mul(d.inst.transform), w2 = new Vector3(a2).mul(d.inst.transform);
        boolean firstIsNear = w1.dst2(cam.position) < w2.dst2(cam.position);
        Vector3 best = (dir[2] < 0.5f) == firstIsNear ? a1 : a2;
        return best;
    }

    /**
     * The last few lines of Forge's game log, top right, so a trigger or a spell that went by
     * while you were looking elsewhere can be read back. Oldest at the top, newest at the bottom
     * and brightest; coloured by what kind of event it was. L hides it. The words are Forge's
     * own log, the one its window shows this player, so it reveals nothing new.
     */
    void drawEventLog(int w, int h, Matrix4 flat) {
        if (snap.log.isEmpty()) return;
        // The help card hangs from the same corner. Two panels in one place is worse than
        // either, and the log is the one you can read again in a second - so while help is
        // open, the log stands aside.
        if (help.isOpen()) return;
        GlyphLayout gl = new GlyphLayout();
        List<String> lines = new ArrayList<>();
        for (Snapshot.LogLine l : snap.log) lines.add(fit(l.text, LOG_W - 20f, gl));
        float panelH = 30f + lines.size() * LOG_LINE + 8f;
        // Below the bug-report cog and its "report a bug" label, which own the very corner.
        float x = w - LOG_W - 14f, top = h - 72f, y = top - panelH;

        Gdx.gl.glEnable(GL20.GL_BLEND);
        shapes.setProjectionMatrix(flat);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.02f, 0.08f, 0.13f, 0.80f);
        shapes.rect(x, y, LOG_W, panelH);
        shapes.setColor(0.18f, 0.56f, 0.71f, 0.95f);
        shapes.rect(x, top - 3f, LOG_W, 3f);
        shapes.end();

        ui.setProjectionMatrix(flat);
        ui.begin();
        font.setColor(0.55f, 0.72f, 0.84f, 1f);
        font.draw(ui, "GAME LOG", x + 10f, top - 9f);
        font.setColor(0.35f, 0.48f, 0.58f, 1f);
        font.draw(ui, "L hides", x + LOG_W - 60f, top - 9f);
        for (int i = 0; i < lines.size(); i++) {
            Color c = logColour(snap.log.get(i).type);
            // Older lines fade a little, so the eye lands on the newest at the bottom.
            float age = (lines.size() - 1 - i) / (float) Math.max(1, lines.size() - 1);
            font.setColor(c.r, c.g, c.b, 1f - 0.45f * age);
            font.draw(ui, lines.get(i), x + 10f, top - 30f - i * LOG_LINE);
        }
        ui.end();
    }

    static Color logColour(String type) {
        switch (type) {
            case "STACK_ADD": case "STACK_RESOLVE": return new Color(0.80f, 0.62f, 1f, 1f);
            case "DAMAGE": case "LIFE": return new Color(1f, 0.55f, 0.45f, 1f);
            case "COMBAT": return new Color(1f, 0.72f, 0.40f, 1f);
            case "TURN": case "PHASE": return new Color(1f, 0.85f, 0.45f, 1f);
            case "LAND": case "MANA": return new Color(0.60f, 0.88f, 0.62f, 1f);
            case "GAME_OUTCOME": return new Color(1f, 0.92f, 0.60f, 1f);
            default: return new Color(0.78f, 0.86f, 0.92f, 1f);
        }
    }

    /**
     * The end of the game: a band across the middle of the screen saying so, and who won. The
     * board stays visible behind it (it is translucent), because the final position is worth
     * looking at; Forge's own window still handles what happens next.
     */
    void drawGameOver(int w, int h, Matrix4 flat) {
        float bandH = 120f, y = h / 2f - bandH / 2f + 60f;
        Gdx.gl.glEnable(GL20.GL_BLEND);
        shapes.setProjectionMatrix(flat);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.02f, 0.04f, 0.07f, 0.80f);
        shapes.rect(0, y, w, bandH);
        shapes.setColor(0.85f, 0.62f, 0.22f, 1f);
        shapes.rect(0, y + bandH - 3f, w, 3f);
        shapes.rect(0, y, w, 3f);
        shapes.end();

        ui.setProjectionMatrix(flat);
        ui.begin();
        GlyphLayout gl = new GlyphLayout();
        fontBig.getData().setScale(2.6f);
        fontBig.setColor(0.98f, 0.82f, 0.45f, 1f);
        gl.setText(fontBig, "GAME OVER");
        fontBig.draw(ui, "GAME OVER", (w - gl.width) / 2f, y + bandH - 18f);
        fontBig.getData().setScale(1.6f);       // back to its normal size for everything else
        String who = snap.winner.isEmpty() ? "A draw" : snap.winner + " wins";
        fontBig.setColor(0.85f, 0.93f, 1f, 1f);
        gl.setText(fontBig, who);
        fontBig.draw(ui, who, (w - gl.width) / 2f, y + 38f);
        ui.end();
    }

    /** Life totals and zone counts, printed near each seat so the pod reads at a glance. */
    void drawSeatInfo(int w, int h) {
        int n = snap.seats.size();
        for (int i = 0; i < n; i++) {
            Snapshot.SeatV s = snap.seats.get(i);
            float angle = seatAngle(i, n);
            Vector3 world = seatPos(angle, SEAT_DIST + 2.6f);
            Vector3 p = cam.project(new Vector3(world.x, 0.4f, world.z));
            if (p.z > 1f) continue;                       // behind the camera
            if (s.lost) font.setColor(0.50f, 0.42f, 0.42f, 1f);
            else if (s.active) font.setColor(1f, 0.85f, 0.35f, 1f);
            else if (s.isYou) font.setColor(0.65f, 0.95f, 1f, 1f);
            else font.setColor(0.72f, 0.78f, 0.86f, 1f);
            String line = s.name + (s.lost ? "   OUT" : "   " + s.life + " life");
            String line2 = "hand " + s.handSize + "   lib " + s.librarySize + "   gy " + s.graveyardSize;
            font.draw(ui, line, p.x - 60, p.y + 16);
            font.draw(ui, line2, p.x - 60, p.y);
            if (!s.marks.isEmpty() && !s.lost) {
                // Designations and player counters, one line under the totals: rare, but when the
                // monarch or a poison count is in play it decides who gets attacked.
                font.setColor(1f, 0.70f, 0.40f, 1f);
                font.draw(ui, String.join("   ", s.marks), p.x - 60, p.y - 16);
            }
            drawManaPool(s, p.x - 60, p.y - (s.marks.isEmpty() || s.lost ? 16 : 32));
            if (s.hasPriority) {
                // Matches the lit pad under the same seat.
                font.setColor(0.45f, 0.92f, 1f, 1f);
                font.draw(ui, "> priority", p.x - 60, p.y + 32);
            }
        }
    }

    /** Who cast what, under each item on the stack, plus a marker on the one resolving next. */
    void drawStackLabels() {
        if (snap.stack.isEmpty()) return;
        // Placed by placeStack with the cards, so a label is always under its own card - upright or
        // flat. (It used to work out its own spacing, which no longer matched the cards' once the
        // stack was drawn larger: with three items the labels sat between the cards.)
        for (int i = 0; i < snap.stack.size() && i < stackLabelAt.size(); i++) {
            Snapshot.CardV c = snap.stack.get(i);
            boolean top = (i == snap.stack.size() - 1);
            float[] at = stackLabelAt.get(i);
            Vector3 p = cam.project(new Vector3(at[0], at[1], at[2]));
            if (p.z > 1f) continue;
            font.setColor(top ? new Color(1f, 0.85f, 0.35f, 1f) : new Color(0.68f, 0.78f, 0.88f, 1f));
            font.draw(ui, c.controller, p.x - 30, p.y);
            float y = p.y - 13;
            if (top) { font.draw(ui, "resolving next", p.x - 30, y); y -= 13; }
            // Owner's list 2026-10-20: say WHICH ability is on the stack - the card picture is the
            // same for every ability of a card, so without this two activations look identical.
            if (c.stackIsAbility && c.stackText != null && !c.stackText.isEmpty()) {
                font.setColor(new Color(0.55f, 0.92f, 0.86f, 1f));      // the ability ribbon's teal
                java.util.List<String> lines = wrap(abilityOnly(c), STACK_LABEL_W);
                for (int k = 0; k < Math.min(2, lines.size()); k++) {
                    String l = lines.get(k);
                    if (k == 1 && lines.size() > 2) l = l + "...";
                    font.draw(ui, l, p.x - 30, y);
                    y -= 13;
                }
            }
        }
    }

    static final float STACK_LABEL_W = 160f;

    /**
     * Which of a permanent's abilities is on the stack, written on the permanent itself (the
     * owner's list: "highlight the source card on the battlefield, and the specific ability on
     * it"). The card already glows teal (abilitySourceIds); this names the ability over it, in
     * the same teal and the same words as the label under the stack card, so a card with three
     * abilities says which one. Two of the same card's abilities on the stack stack their lines.
     * Inside the overlay batch; the text is shadowed rather than boxed, since the batch is open.
     */
    void drawAbilityOnSources() {
        if (snap.stack.isEmpty()) return;
        java.util.Map<Integer, Integer> shownFor = new java.util.HashMap<>();
        Vector3 pos = new Vector3();
        for (Snapshot.CardV s : snap.stack) {
            if (!s.stackIsAbility || s.stackText == null || s.stackText.isEmpty()) continue;
            Drawn src = null;
            for (Drawn d : drawn) if (d.seat != null && d.card != null && d.card.id == s.id) { src = d; break; }
            if (src == null) continue;                          // not on the battlefield (a hand or graveyard ability)
            src.inst.transform.getTranslation(pos);
            Vector3 p = cam.project(new Vector3(pos.x, pos.y + 0.12f, pos.z));
            if (p.z > 1f) continue;
            int line = shownFor.merge(s.id, 1, Integer::sum) - 1;
            java.util.List<String> lines = wrap(abilityOnly(s), 210f);
            String text = lines.isEmpty() ? "" : lines.get(0) + (lines.size() > 1 ? "..." : "");
            com.badlogic.gdx.graphics.g2d.GlyphLayout gl = new com.badlogic.gdx.graphics.g2d.GlyphLayout(font, text);
            float x = p.x - gl.width / 2f, y = p.y + 34f + line * 15f;
            font.setColor(0f, 0f, 0f, 0.85f);
            font.draw(ui, text, x + 1.5f, y - 1.5f);
            font.setColor(0.55f, 0.92f, 0.86f, 1f);            // the ability ribbon's teal
            font.draw(ui, text, x, y);
        }
    }

    /** Forge's stack text starts with the card's name ("Name - {T}: ..."); the name is already shown. */
    static String abilityOnly(Snapshot.CardV c) {
        String t = c.stackText.trim();
        String prefix = c.name + " - ";
        return t.startsWith(prefix) ? t.substring(prefix.length()) : t;
    }

    /**
     * What Forge is asking, drawn where you are already looking.
     *
     * All of this - the question, the button labels, whether each is enabled - has been in the
     * Snapshot since the wrapper went in, and nothing drew it. That is why clicking a seat felt
     * like nothing happened and the mulligan appeared only in Forge's window: the 3D side knew
     * both and said nothing. Sits above the hand, where the eye already is.
     */
    void drawPromptBar(int w, int h) {
        okBtn[2] = cancelBtn[2] = 0f;                      // nothing clickable unless drawn
        // Drawn whenever the snapshot carries a prompt, so the demo can exercise it too;
        // only the buttons need a live game behind them.
        if (Gui3D.pendingAsk() != null) return;
        // Read the prompt LIVE from the wrapper, not from the snapshot.
        //
        // The snapshot is only rebuilt when the game fires an event, and a prompt arrives between
        // events - so the bar was a whole decision behind. The owner's first bug report caught it
        // exactly: at the moment of the mulligan the snapshot's prompt was empty and both buttons
        // disabled, and the question only appeared after SPACE had already answered it. These are
        // plain strings and booleans already copied out of Forge, so reading them from the render
        // thread is safe in a way reading Forge's own objects would not be.
        String msg;
        String okLabel, cancelLabel;
        boolean okOn, cancelOn;
        if (Gui3D.active()) {
            Gui3D.Prompt p = Gui3D.PROMPT;
            msg = p.message == null ? "" : p.message.trim();
            okLabel = p.okLabel; cancelLabel = p.cancelLabel;
            okOn = p.okEnabled; cancelOn = p.cancelEnabled;
        } else {
            msg = snap.promptText == null ? "" : snap.promptText.trim();
            okLabel = snap.okLabel; cancelLabel = snap.cancelLabel;
            okOn = snap.okEnabled; cancelOn = snap.cancelEnabled;
        }
        if (msg.isEmpty()) return;
        msg = condensePrompt(msg);

        boolean waiting = okOn || cancelOn;

        // What the main button DOES, said on the button.
        //
        // Forge gives it a name of its own whenever it is really asking something - "Attack",
        // "Auto", "Yes". A plain "OK" means it is not asking, only offering to move on, and then
        // what moving on means depends entirely on the stack: with something on it, a pass lets
        // THAT resolve and leaves you exactly where you are; with nothing on it, a pass is the
        // end of the step. The owner asked to be able to tell those two apart - "I want to be
        // clear if all I am allowing is the current iteration of stack to resolve".
        // The pad's buttons are named on screen only when one is plugged in: a mouse player
        // should not be reading about buttons they do not have.
        //
        // This bar belongs to the BOARD, where A plays the card the stick has picked out - so
        // the button that moves the game on is the D-pad, not A. A is the primary only while a
        // panel is covering the board (Pad.panelMode), and this bar is not a panel. Labelling it
        // "A" was copied from the panel mapping and was simply wrong (the owner, 2026-10-21).
        String padMain = Pad.connected() ? "  or  D-pad >" : "";
        // End turn has no pad button on purpose. It arms an auto-pass for the rest of the turn,
        // which has cost this game several turns already, and it does not belong on a face
        // button that can be caught with a thumb.
        String padOther = "";
        boolean named = !okLabel.isEmpty() && !"OK".equalsIgnoreCase(okLabel);
        String topOfStack = snap.stack.isEmpty() ? ""
            : shortName(snap.stack.get(snap.stack.size() - 1).name);
        String mainLabel, mainHint;
        if (named) {
            mainLabel = okLabel;
            mainHint = "SPACE" + padMain;
        } else if (!topOfStack.isEmpty()) {
            mainLabel = "Let it resolve";
            mainHint = "SPACE" + padMain + " - " + topOfStack;
        } else {
            mainLabel = "Next phase";
            mainHint = "SPACE  or  N" + padMain;
        }
        // Forge's cancel. During ordinary priority it is "End Turn", which does not mean "finish
        // this step": it arms an auto-pass for everything left in the turn. That is what cost a
        // whole turn on 2026-10-21, so the button says so rather than looking like a tidy way to
        // move on.
        boolean endsTurn = "End Turn".equalsIgnoreCase(cancelLabel);
        // Compact (owner, 2026-10-21: "the UI for resolving and ending turn is too big"): it was
        // 1120 wide, ~140 tall with four lines in the big font, and sat over the hand. Now one or
        // two lines in the normal font, smaller buttons, low on the screen above the hint line.
        float bw = Math.min(w - 60f, 860f), x = (w - bw) / 2f, y = 34f;
        float btnW = 150f, btnH = 36f, gap = 10f;

        // Measure the text and size the box to it, so a one-line prompt gets a slim bar.
        float textW = bw - 40f - (okOn ? btnW + 30f + gap : 0f) - (cancelOn ? btnW + gap : 0f);
        com.badlogic.gdx.graphics.g2d.GlyphLayout gl = new com.badlogic.gdx.graphics.g2d.GlyphLayout();
        gl.setText(font, msg, com.badlogic.gdx.graphics.Color.WHITE, textW, com.badlogic.gdx.utils.Align.left, true);
        float bh = Math.max(btnH + 14f, gl.height + 22f);

        ui.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        com.badlogic.gdx.math.Matrix4 flat = new com.badlogic.gdx.math.Matrix4().setToOrtho2D(0, 0, w, h);
        shapes.setProjectionMatrix(flat);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.02f, 0.08f, 0.13f, 0.95f);
        shapes.rect(x, y, bw, bh);
        shapes.setColor(waiting ? 0.95f : 0.18f, waiting ? 0.72f : 0.56f, waiting ? 0.24f : 0.71f, 1f);
        shapes.rect(x, y + bh - 4f, bw, 4f);

        // Buttons sit from the right edge inwards, so one button or two both look deliberate.
        float bx = x + bw - 12f - btnW;
        float by = y + (bh - btnH) / 2f;
        float mx = Gdx.input.getX(), my = h - Gdx.input.getY();
        if (cancelOn) {
            cancelBtn[0] = bx; cancelBtn[1] = by; cancelBtn[2] = btnW; cancelBtn[3] = btnH;
            boolean hot = inRect(cancelBtn, mx, my);
            shapes.setColor(hot ? 0.62f : 0.42f, hot ? 0.24f : 0.17f, hot ? 0.20f : 0.15f, 1f);
            shapes.rect(bx, by, btnW, btnH);
            bx -= btnW + gap;
        }
        // One button, not two. "OK" and "Next phase" were sending the same pass of priority,
        // which is exactly as clear as it sounds. The rect is wider because the label is now a
        // sentence about what the pass will do.
        nextOn = false;
        if (okOn) {
            float okW = btnW + 30f;
            bx -= 30f;
            okBtn[0] = bx; okBtn[1] = by; okBtn[2] = okW; okBtn[3] = btnH;
            boolean hot = inRect(okBtn, mx, my);
            // Green while it answers a question, blue while it only moves the game on, so the
            // two are not the same colour as well as in the same place.
            if (named) shapes.setColor(hot ? 0.24f : 0.16f, hot ? 0.64f : 0.48f, hot ? 0.40f : 0.31f, 1f);
            else shapes.setColor(hot ? 0.26f : 0.17f, hot ? 0.42f : 0.28f, hot ? 0.62f : 0.44f, 1f);
            shapes.rect(bx, by, okW, btnH);
        }
        // Hard left, past the prompt text - NOT beside the other buttons. It is a mode, not an
        // answer, and a mode button within a thumb's width of "End turn" is a turn waiting to be
        // lost (the owner, 2026-10-21).
        respondBox[0] = x + 14f; respondBox[1] = by; respondBox[2] = btnW; respondBox[3] = btnH;
        boolean hotR = inRect(respondBox, mx, my);
        float rbx = respondBox[0];
        if (askResponses) shapes.setColor(hotR ? 0.20f : 0.13f, hotR ? 0.34f : 0.24f, hotR ? 0.46f : 0.33f, 1f);
        else shapes.setColor(hotR ? 0.72f : 0.54f, hotR ? 0.48f : 0.34f, hotR ? 0.18f : 0.12f, 1f);
        shapes.rect(rbx, by, btnW, btnH);
        shapes.end();
        ui.begin();

        font.setColor(waiting ? new Color(1f, 0.93f, 0.74f, 1f) : new Color(0.70f, 0.82f, 0.92f, 1f));
        font.draw(ui, msg, x + 24f + btnW, y + bh - 12f, textW - btnW - 24f, -1, true);
        // Label and key on one line each, in the normal font: the button is 36 px tall now.
        if (okOn) {
            font.setColor(named ? new Color(0.88f, 1f, 0.92f, 1f) : new Color(0.88f, 0.93f, 1f, 1f));
            font.draw(ui, mainLabel, okBtn[0], okBtn[1] + 30f, okBtn[2], 1, false);
            font.setColor(named ? new Color(0.60f, 0.86f, 0.70f, 1f) : new Color(0.62f, 0.74f, 0.92f, 1f));
            font.draw(ui, mainHint, okBtn[0], okBtn[1] + 14f, okBtn[2], 1, false);
        }
        if (askResponses) {
            font.setColor(0.80f, 0.88f, 0.98f, 1f);
            font.draw(ui, "Ask me", respondBox[0], respondBox[1] + 30f, btnW, 1, false);
            font.setColor(0.58f, 0.70f, 0.84f, 1f);
            font.draw(ui, "R - stop asking", respondBox[0], respondBox[1] + 14f, btnW, 1, false);
        } else {
            font.setColor(1f, 0.90f, 0.66f, 1f);
            font.draw(ui, "Skipping", respondBox[0], respondBox[1] + 30f, btnW, 1, false);
            font.setColor(1f, 0.82f, 0.52f, 1f);
            font.draw(ui, "R - ask me again", respondBox[0], respondBox[1] + 14f, btnW, 1, false);
        }
        if (cancelOn) {
            font.setColor(1f, 0.88f, 0.84f, 1f);
            font.draw(ui, endsTurn ? "End turn" : cancelLabel, cancelBtn[0], cancelBtn[1] + 30f, btnW, 1, false);
            font.setColor(0.88f, 0.64f, 0.60f, 1f);
            font.draw(ui, (endsTurn ? "X - passes the rest" : "X") + padOther,
                cancelBtn[0], cancelBtn[1] + 14f, btnW, 1, false);
        }
    }

    /**
     * Forge's ordinary-priority prompt is four lines - "Priority: X / Turn: N / Phase: ... /
     * Stack: ..." - and the first two are already on the phase panel. Shown as one line: the phase
     * and the stack. Any other prompt (a real question) is kept as it is.
     */
    static String condensePrompt(String msg) {
        if (!msg.startsWith("Priority:")) return msg;
        String phase = null, stack = null;
        for (String line : msg.split("\\r?\\n")) {
            String l = line.trim();
            if (l.startsWith("Phase:")) phase = l.substring(6).trim();
            else if (l.startsWith("Stack:")) stack = l.substring(6).trim();
        }
        if (phase == null) return msg;
        String s = stack == null || stack.isEmpty() || stack.equalsIgnoreCase("Empty") ? "stack empty"
            : "stack: " + stack.replaceAll("\\.$", "");
        return phase + "   -   " + s;
    }

    /**
     * A question the 3D window is answering itself.
     *
     * Deliberately the loudest thing on screen. Forge is no longer showing this question and its
     * game thread is blocked waiting for an answer, so a player who does not notice it has a
     * game that appears to have frozen - the one failure the whole input plan is arranged to
     * avoid. It names the card, spells out both keys, and says H hands it back to Forge.
     */
    void drawPendingAsk(int w, int h) {
        Gui3D.Ask ask = Gui3D.pendingAsk();
        if (ask == null || peeking) return;      // put aside: the badge says it is still waiting
        // Measured, not guessed. The box was a fixed 150 high, and a question like "you are about
        // to end your turn, but 2 untapped mana and cards you could cast (...). Pass anyway?"
        // wraps to three lines and ran out of the bottom over the answers.
        float bw = Math.min(w - 120f, 860f);
        GlyphLayout q = new GlyphLayout(font, ask.question, Color.WHITE, bw - 48f,
            com.badlogic.gdx.utils.Align.left, true);
        float bh = Math.max(150f, 118f + q.height);
        float x = (w - bw) / 2f, y = h * 0.62f - bh / 2f;

        // Drawn after the overlay has closed its own batch, so this opens and closes one of
        // its own. It used to be called mid-batch and began with ui.end(); moving it to the end
        // of the frame left that end() with no batch to close, which threw out of the render
        // thread and took the whole window down - the game then sat waiting until Handoff gave
        // the question back to Forge's window (the owner, 2026-10-22).
        Gdx.gl.glEnable(GL20.GL_BLEND);
        com.badlogic.gdx.math.Matrix4 flat = new com.badlogic.gdx.math.Matrix4().setToOrtho2D(0, 0, w, h);
        shapes.setProjectionMatrix(flat);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        // A shade over the whole screen as well: this is a question the game is stopped on, and
        // the board behind it was showing through the box and through the text.
        shapes.setColor(0f, 0f, 0f, 0.45f);
        shapes.rect(0, 0, w, h);
        shapes.setColor(0.07f, 0.05f, 0.02f, 1f);
        shapes.rect(x, y, bw, bh);
        shapes.setColor(0.98f, 0.76f, 0.24f, 1f);
        shapes.rect(x, y + bh - 5f, bw, 5f);
        shapes.rect(x, y, bw, 2f);
        shapes.end();
        ui.setProjectionMatrix(flat);
        ui.begin();

        fontBig.setColor(1f, 0.90f, 0.55f, 1f);
        String head = ask.cardName.isEmpty() ? "Forge is asking" : ask.cardName;
        fontBig.draw(ui, head, x + 24f, y + bh - 24f);
        font.setColor(0.95f, 0.95f, 0.92f, 1f);
        font.draw(ui, ask.question, x + 24f, y + bh - 58f, bw - 48f, -1, true);
        font.setColor(0.70f, 0.95f, 0.70f, 1f);
        font.draw(ui, "SPACE" + (Pad.connected() ? " / A" : "") + " = " + ask.yesLabel, x + 24f, y + 40f);
        font.setColor(0.95f, 0.72f, 0.66f, 1f);
        font.draw(ui, "X" + (Pad.connected() ? " / X" : "") + " = " + ask.noLabel, x + 300f, y + 40f);
        font.setColor(0.62f, 0.66f, 0.72f, 1f);
        font.draw(ui, "H hands this question back to Forge's window", x + 24f, y + 18f);
        ui.end();
    }

    /** Y of row i, measured down from the top of the panel. */
    static float phaseRowY(int i, float top) {
        float gaps = 0f;
        for (int g : PP_GROUPS) if (i >= g) gaps += PP_GAP;
        return top - PP_HEAD - i * PP_ROW - gaps;
    }

    float panelHeight() {
        return PP_HEAD + PHASES.length * PP_ROW + PP_GROUPS.length * PP_GAP + prioBlockHeight() + PP_FOOT;
    }

    float prioBlockHeight() {
        return snap.seats.isEmpty() ? 0f : PP_GAP + PB_HEAD + snap.seats.size() * PB_ROW + 4f;
    }

    /** Top of the priority block: just under the last step row. */
    float prioBlockTop(float top) {
        return phaseRowY(PHASES.length - 1, top) - PP_GAP;
    }

    /** Y (bottom) of seat row k in the priority block. */
    float prioRowY(int k, float top) {
        return prioBlockTop(top) - PB_HEAD - (k + 1) * PB_ROW;
    }

    int currentPhase() {
        for (int i = 0; i < PHASES.length; i++) {
            if (PHASES[i][0].equals(snap.phaseKey)) return i;
        }
        return -1;
    }

    /** The panel's filled shapes. Must run before the SpriteBatch pass that writes the labels. */
    /** Is the turn being played ours, and is the game waiting on us right now? */
    boolean yourTurn() {
        Snapshot.SeatV me = snap.you();
        return me != null && me.active;
    }

    boolean youDecide() {
        Snapshot.SeatV me = snap.you();
        return me != null && me.hasPriority;
    }

    void drawPhasePanelShapes(int h, com.badlogic.gdx.math.Matrix4 flat) {
        float top = h - 14f;
        float height = panelHeight();
        float bottom = top - height;
        int current = currentPhase();
        boolean yourTurn = yourTurn(), youDecide = youDecide();

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.setProjectionMatrix(flat);
        shapes.begin(ShapeRenderer.ShapeType.Filled);

        shapes.setColor(0.02f, 0.08f, 0.13f, 0.88f);
        shapes.rect(PP_X, bottom, PP_W, height);
        shapes.setColor(0.18f, 0.56f, 0.71f, 0.95f);
        shapes.rect(PP_X, top - 3f * PP_SCALE, PP_W, 3f * PP_SCALE);
        shapes.setColor(0.10f, 0.26f, 0.34f, 1f);
        shapes.rect(PP_X + 10f * PP_SCALE, top - PP_HEAD + 12f * PP_SCALE, PP_W - 20f * PP_SCALE, 1f * PP_SCALE);     // rule under the turn line

        for (int i = 0; i < PHASES.length; i++) {
            float y = phaseRowY(i, top);
            boolean on = (i == current);
            boolean past = current >= 0 && i < current;
            if (on) {
                // Whose turn it is, in the one place you are already looking. Green when the
                // step belongs to you, red when you are a guest in someone else's turn - the
                // owner asked for exactly this, and it answers "can I act?" without reading a
                // word (2026-10-21).
                if (yourTurn) shapes.setColor(0.20f, 0.72f, 0.33f, 1f);
                else shapes.setColor(0.72f, 0.24f, 0.22f, 1f);
                shapes.rect(PP_X + 8f * PP_SCALE, y, PP_W - 16f * PP_SCALE, PP_ROW - 3f * PP_SCALE);
            } else {
                // A marker down the left edge: filled for steps already done, hollow for those to
                // come, so progress through the turn reads without the text.
                shapes.setColor(past ? new Color(0.20f, 0.42f, 0.52f, 1f)
                                     : new Color(0.09f, 0.20f, 0.27f, 1f));
                shapes.rect(PP_X + 8f * PP_SCALE, y + 4f * PP_SCALE, 5f * PP_SCALE, PP_ROW - 11f * PP_SCALE);
            }
            // The box beside each step: is the game waiting on YOU here? Only the step actually
            // being played can answer that, so the rest stay dark - what matters is that the
            // answer always lives in the same place, so a lit box anywhere in the column means
            // "they are waiting for you" even on somebody else's turn.
            float bx = PP_X + PP_W - 20f * PP_SCALE, bw = 10f * PP_SCALE;
            if (on && youDecide) shapes.setColor(0.45f, 0.92f, 1f, 1f);
            else if (on) shapes.setColor(0.16f, 0.30f, 0.38f, 1f);
            else shapes.setColor(0.07f, 0.14f, 0.19f, 1f);
            shapes.rect(bx, y + 4f * PP_SCALE, bw, PP_ROW - 11f * PP_SCALE);
        }

        // Priority block: a rule, then a square per seat - lit for whoever holds priority, filled
        // for those who have passed, dark for those still to act. The dark ones are who you wait on.
        if (!snap.seats.isEmpty()) {
            float bt = prioBlockTop(top);
            shapes.setColor(0.10f, 0.26f, 0.34f, 1f);
            shapes.rect(PP_X + 10f * PP_SCALE, bt, PP_W - 20f * PP_SCALE, 1f * PP_SCALE);
            for (int k = 0; k < snap.seats.size(); k++) {
                Snapshot.SeatV s = snap.seats.get(k);
                float y = prioRowY(k, top);
                if (s.lost) continue;
                if (s.hasPriority) shapes.setColor(0.45f, 0.92f, 1f, 1f);
                else if (s.passed) shapes.setColor(0.20f, 0.42f, 0.52f, 1f);
                else shapes.setColor(0.09f, 0.20f, 0.27f, 1f);
                shapes.rect(PP_X + 10f * PP_SCALE, y + 4f * PP_SCALE, 10f * PP_SCALE, 10f * PP_SCALE);
            }
        }

        float by = bottom + 11f * PP_SCALE;
        followBox = new float[] { PP_X + 8f * PP_SCALE, by, PP_W - 16f * PP_SCALE, 15f * PP_SCALE };
        shapes.setColor(0.06f, 0.16f, 0.22f, 1f);
        shapes.rect(PP_X + 8f * PP_SCALE, by, 14f * PP_SCALE, 14f * PP_SCALE);
        if (followTurn) {
            shapes.setColor(0.45f, 0.86f, 1f, 1f);
            shapes.rect(PP_X + 11f * PP_SCALE, by + 3f * PP_SCALE, 8f * PP_SCALE, 8f * PP_SCALE);
        }
        shapes.end();
    }

    /** The panel's labels. The background was already drawn by drawPhasePanelShapes. */
    void drawPhasePanel(int w, int h) {
        float top = h - 14f;
        float bottom = top - panelHeight();
        int current = currentPhase();
        // The panel's own text size. Set here and restored at the end, because `font`
        // is shared with everything else on the HUD.
        font.getData().setScale(PP_SCALE);

        font.setColor(0.78f, 0.90f, 0.98f, 1f);
        font.draw(ui, snap.turnLabel, PP_X + 10f * PP_SCALE, top - 14f * PP_SCALE);

        // Which seat we are looking from, since the camera no longer follows the turn by itself.
        if (viewSeat >= 0 && viewSeat < snap.seats.size()) {
            Snapshot.SeatV v = snap.seats.get(viewSeat);
            if (v.isYou) {
                font.setColor(0.50f, 0.68f, 0.80f, 1f);
                font.draw(ui, "your seat", PP_X + 10f * PP_SCALE, top - 30f * PP_SCALE);
            } else {
                font.setColor(1f, 0.78f, 0.45f, 1f);
                font.draw(ui, "at " + v.name + "'s seat", PP_X + 10f * PP_SCALE, top - 30f * PP_SCALE);
            }
        }

        for (int i = 0; i < PHASES.length; i++) {
            float y = phaseRowY(i, top);
            boolean on = (i == current);
            boolean past = current >= 0 && i < current;
            if (on) font.setColor(0.98f, 0.99f, 1f, 1f);             // light on the lit row
            else if (past) font.setColor(0.52f, 0.66f, 0.76f, 1f);
            else font.setColor(0.38f, 0.50f, 0.60f, 1f);
            font.draw(ui, PHASES[i][1], PP_X + 20f * PP_SCALE, y + PP_ROW - 7f * PP_SCALE);
        }

        if (!snap.seats.isEmpty()) {
            float bt = prioBlockTop(top);
            font.setColor(0.55f, 0.72f, 0.84f, 1f);
            font.draw(ui, "PRIORITY", PP_X + 10f * PP_SCALE, bt - 5f * PP_SCALE);
            String depth = snap.stack.isEmpty() ? "stack empty" : "stack " + snap.stack.size();
            GlyphLayout gl = new GlyphLayout(font, depth);
            font.setColor(snap.stack.isEmpty() ? new Color(0.40f, 0.52f, 0.62f, 1f) : new Color(1f, 0.80f, 0.40f, 1f));
            font.draw(ui, depth, PP_X + PP_W - 10f * PP_SCALE - gl.width, bt - 5f * PP_SCALE);
            for (int k = 0; k < snap.seats.size(); k++) {
                Snapshot.SeatV s = snap.seats.get(k);
                float y = prioRowY(k, top);
                String state = s.lost ? "out" : waitingOn(s) ? "deciding" : s.passed ? "passed" : "waiting";
                if (s.lost) font.setColor(0.42f, 0.38f, 0.38f, 1f);
                else if (waitingOn(s)) font.setColor(0.70f, 0.95f, 1f, 1f);
                else if (s.passed) font.setColor(0.48f, 0.62f, 0.72f, 1f);
                else font.setColor(0.80f, 0.86f, 0.92f, 1f);
                font.draw(ui, shortName(s.name), PP_X + 26f * PP_SCALE, y + PB_ROW - 4f * PP_SCALE);
                gl.setText(font, state);
                font.draw(ui, state, PP_X + PP_W - 10f * PP_SCALE - gl.width, y + PB_ROW - 4f * PP_SCALE);
            }
        }

        font.setColor(followTurn ? new Color(0.72f, 0.92f, 1f, 1f) : new Color(0.48f, 0.60f, 0.70f, 1f));
        font.draw(ui, "follow the turn   (F)", PP_X + 30f * PP_SCALE, bottom + 23f * PP_SCALE);

        // Forge auto-passing on our behalf used to be completely invisible: one key armed it,
        // the rest of the turn went past, and nothing on screen said why. If it is on, say so
        // where the eye already is, and say how to stop it.
        String auto = Gui3D.autoPassing();
        if (!auto.isEmpty()) {
            font.setColor(1f, 0.62f, 0.25f, 1f);
            font.draw(ui, "AUTO-PASSING " + auto, PP_X + 10f * PP_SCALE, bottom + 40f * PP_SCALE);
            font.setColor(1f, 0.80f, 0.55f, 1f);
            font.draw(ui, "N stops it", PP_X + 10f * PP_SCALE, bottom + 28f * PP_SCALE);
        }

        // Life, where the phase already is. It used to hang over the middle of the table, which
        // is both a second place to look and exactly where the stack now wants to be.
        float ly = bottom - 16f * PP_SCALE;
        for (int i = 0; i < snap.seats.size(); i++) {
            Snapshot.SeatV s = snap.seats.get(i);
            if (s.active) font.setColor(1f, 0.85f, 0.35f, 1f);
            else if (s.isYou) font.setColor(0.65f, 0.95f, 1f, 1f);
            else font.setColor(0.70f, 0.78f, 0.86f, 1f);
            String mark = s.active ? ">" : " ";
            font.draw(ui, mark + " " + s.name, PP_X + 8f * PP_SCALE, ly);
            font.draw(ui, String.valueOf(s.life), PP_X + PP_W - 46f * PP_SCALE, ly);
            ly -= 16f * PP_SCALE;
        }
        font.getData().setScale(1f);
    }

    /**
     * "x12" over a pile of identical permanents. The pile shows depth, but depth stops being
     * countable past about four cards and stops growing at six, so the number carries it.
     */
    /**
     * While blockers are being declared: a number over every attacker that is being blocked, and
     * the same number over each creature blocking it.
     *
     * The blue slab under an assigned blocker said THAT it was blocking and never WHICH attacker,
     * so an assignment was invisible until the damage was dealt - "I cannot tell if the blocker
     * is assigned until I proceed to the next step, in which I find out if I assigned it
     * correctly" (the owner, 2026-10-22). Numbers rather than arcs because the attackers that
     * need telling apart are usually identical tokens sitting next to each other, and a line
     * between two cards a few centimetres apart reads worse than a 1 and a 1.
     *
     * Drawn from the window's own plan, not from the game, so it appears the instant you click
     * and does not wait for anything to come back.
     */
    void drawBlockPairs() {
        if (combatPlan == null || !combatPlan.blocking || combatPlan.blockOn.isEmpty()) return;
        // The attackers that have something in front of them, numbered in the order they were
        // first blocked, so the numbers do not jump about as more blockers are added.
        java.util.LinkedHashMap<Integer, Integer> number = new java.util.LinkedHashMap<>();
        for (Integer attacker : combatPlan.blockOn.values()) {
            if (attacker != null && !number.containsKey(attacker)) number.put(attacker, number.size() + 1);
        }
        for (Drawn d : drawn) {
            if (d.card == null) continue;
            Integer n = number.get(d.card.id);
            boolean isAttacker = n != null;
            if (n == null) n = number.get(combatPlan.blockOn.get(d.card.id));
            if (n == null) continue;
            Vector3 mid = d.bounds.getCenter(new Vector3());
            Vector3 p = cam.project(new Vector3(mid.x, mid.y + 0.12f, mid.z));
            if (p.z > 1f) continue;
            // The attacker's chip is red and says how many are in front of it; a blocker's is
            // blue and carries only the number, so which is which never has to be worked out.
            int onIt = 0;
            if (isAttacker) {
                for (Integer a : combatPlan.blockOn.values()) if (a != null && a.intValue() == d.card.id) onIt++;
            }
            String label = isAttacker ? n + "  blocked by " + onIt : String.valueOf(n);
            GlyphLayout gl = new GlyphLayout(fontBig, label);
            ui.end();
            shapes.setProjectionMatrix(ui.getProjectionMatrix());
            Gdx.gl.glEnable(GL20.GL_BLEND);
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            if (isAttacker) shapes.setColor(0.36f, 0.06f, 0.06f, 0.90f);
            else shapes.setColor(0.05f, 0.16f, 0.30f, 0.90f);
            shapes.rect(p.x - gl.width / 2f - 8f, p.y - 5f, gl.width + 16f, gl.height + 11f);
            shapes.end();
            ui.begin();
            if (isAttacker) fontBig.setColor(1f, 0.70f, 0.60f, 1f);
            else fontBig.setColor(0.70f, 0.90f, 1f, 1f);
            fontBig.draw(ui, label, p.x - gl.width / 2f, p.y + gl.height + 1f);
        }
    }

    /**
     * The number over a bead stack: always for a stack taller than one, so a pool can be read at
     * a glance without counting beads, and necessarily once it is past BEAD_SHOW and has stopped
     * growing. In the colour of the mana, over the top bead.
     */
    void drawBeadCounts() {
        for (TableBase.BeadsAt b : beads) {
            if (b.count < 2) continue;
            Vector3 p = cam.project(new Vector3(b.x, b.top + BEAD_D * 0.9f, b.z));
            if (p.z > 1f) continue;
            String label = MANA_LETTERS[b.colour] + b.count;
            GlyphLayout gl = new GlyphLayout(font, label);
            ui.end();
            shapes.setProjectionMatrix(ui.getProjectionMatrix());
            Gdx.gl.glEnable(GL20.GL_BLEND);
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            shapes.setColor(0.03f, 0.08f, 0.12f, 0.80f);
            shapes.rect(p.x - gl.width / 2f - 5f, p.y - 3f, gl.width + 10f, gl.height + 7f);
            shapes.end();
            ui.begin();
            font.setColor(MANA_COLOURS[b.colour]);
            font.draw(ui, label, p.x - gl.width / 2f, p.y + gl.height + 1f);
        }
    }

    void drawStackCounts() {
        for (TableLayout.StackedAt s : stacked) {
            Vector3 p = cam.project(new Vector3(s.x, s.top + 0.10f, s.z));
            if (p.z > 1f) continue;
            String label = "x" + s.count;
            GlyphLayout gl = new GlyphLayout(fontBig, label);
            // A dark plate behind it: these sit over card art, where thin text disappears.
            ui.end();
            shapes.setProjectionMatrix(ui.getProjectionMatrix());
            Gdx.gl.glEnable(GL20.GL_BLEND);
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            shapes.setColor(0.03f, 0.08f, 0.12f, 0.82f);
            shapes.rect(p.x - gl.width / 2f - 7f, p.y - 4f, gl.width + 14f, gl.height + 9f);
            shapes.end();
            ui.begin();
            fontBig.setColor(1f, 0.90f, 0.55f, 1f);
            fontBig.draw(ui, label, p.x - gl.width / 2f, p.y + gl.height + 1f);
        }
    }

    void drawZoneLabels() {
        int n = snap.seats.size();
        for (int i = 0; i < n; i++) {
            Snapshot.SeatV s = snap.seats.get(i);
            float angle = seatAngle(i, n);
            String[] names = { "LIB " + s.librarySize, "GY " + s.graveyardSize, "EXI " + s.exileSize };
            Color zc = zoneLabelColour(s);
            // Whose pads these are, on every pad's own label, in that seat's colour (the owner's
            // list: "make it obvious which battlefield the command zone, exile, graveyard and library
            // belong to"). With the wedge-shaped mats each player's trays sit out at a corner, next
            // to a neighbour's board, and one floating name for the three - placed by a fixed 5.3
            // after the trays had moved in - landed by the wrong pads (2026-10-26 screenshot:
            // ZERMA beside Xenil's column). Positioned with zoneX, the pads' own helper.
            String who = shortName(s.name).toUpperCase() + (s.isYou ? " (YOU)" : "");
            for (int k = 0; k < 3; k++) {
                Vector3 world = rotateY(zoneX(k), SEAT_DIST + 1.7f - k * 1.45f, angle);
                // On top of the pile, so a tall library does not hide its own count.
                Vector3 p = cam.project(new Vector3(world.x, pileTop(i, k) + 0.08f, world.z));
                if (p.z > 1f) continue;
                font.setColor(zc);
                font.draw(ui, who, p.x - 20, p.y + 19);
                font.draw(ui, names[k], p.x - 20, p.y + 6);
            }
        }
        // The board itself, named at the edge nearest the middle of the table: the frame says
        // whose turn it is, this says whose half of the table you are looking at. Skipped for
        // your own seat, which is the one you are sitting behind and never in doubt.
        for (int i = 0; i < n; i++) {
            Snapshot.SeatV s = snap.seats.get(i);
            if (s.isYou) continue;
            // In the middle of the board's inner edge: at the edge's left end (-EDGE_X), where it
            // was, the wedge-shaped mats put it beside the next player's trays - "ZERMA" sat by
            // Xenil's graveyard (2026-10-26 screenshot). Seated view only: overhead, each board's
            // flat screen lies right there with the same name on it.
            if (smooth(lift) > 0.5f) break;
            Vector3 world = rotateY(0f, EDGE_NEAR + 0.36f, seatAngle(i, n));
            Vector3 p = cam.project(new Vector3(world.x, 0.1f, world.z));
            if (p.z > 1f) continue;
            String board = shortName(s.name).toUpperCase()
                + (s.active ? "  -  THEIR TURN" : waitingOn(s) ? "  -  RESPONDING" : "");
            com.badlogic.gdx.graphics.g2d.GlyphLayout bl = new com.badlogic.gdx.graphics.g2d.GlyphLayout(font, board);
            font.setColor(zoneLabelColour(s));
            font.draw(ui, board, p.x - bl.width / 2f, p.y + 6);
        }
        // The command zone: named just below its pad, with the tax the next cast will cost.
        if (!commanderGame()) return;
        for (int i = 0; i < n; i++) {
            Snapshot.SeatV s = snap.seats.get(i);
            Vector3 world = rotateY(commandX(), COMMAND_Z + CARD_H / 2f + 0.35f, seatAngle(i, n));
            Vector3 p = cam.project(new Vector3(world.x, 0.1f, world.z));
            if (p.z > 1f) continue;
            int casts = 0;
            for (Snapshot.CardV c : s.command) casts = Math.max(casts, c.commanderCasts);
            // The owner's name on it too, in their colour, like the other trays.
            font.setColor(zoneLabelColour(s));
            font.draw(ui, shortName(s.name).toUpperCase() + (s.isYou ? " (YOU)" : ""), p.x - 30, p.y + 19);
            font.setColor(0.92f, 0.74f, 0.42f, 1f);
            font.draw(ui, casts > 0 ? "COMMAND  tax +" + (2 * casts) : "COMMAND", p.x - 30, p.y + 6);
        }
    }

    static final String[] MANA_LETTERS = { "W", "U", "B", "R", "G", "C" };
    static final Color[] MANA_COLOURS = {
        new Color(1f, 0.96f, 0.80f, 1f), new Color(0.45f, 0.70f, 1f, 1f), new Color(0.72f, 0.62f, 0.78f, 1f),
        new Color(1f, 0.45f, 0.35f, 1f), new Color(0.45f, 0.90f, 0.50f, 1f), new Color(0.80f, 0.80f, 0.84f, 1f),
    };

    /**
     * A seat's floating mana, "MANA  W2 R1", each symbol in its own colour. Shown only while the
     * pool is not empty - which is rarely, and exactly when it matters (mid-payment, a ritual,
     * mana that will drain at the end of the step).
     */
    void drawManaPool(Snapshot.SeatV s, float x, float y) {
        if (s.lost) return;
        int total = 0;
        for (int m : s.mana) total += m;
        if (total == 0) return;
        GlyphLayout gl = new GlyphLayout();
        font.setColor(0.62f, 0.74f, 0.84f, 1f);
        font.draw(ui, "MANA", x, y);
        gl.setText(font, "MANA  ");
        float cx = x + gl.width;
        for (int i = 0; i < 6; i++) {
            if (s.mana[i] == 0) continue;
            String t = MANA_LETTERS[i] + s.mana[i];
            font.setColor(MANA_COLOURS[i]);
            font.draw(ui, t, cx, y);
            gl.setText(font, t + "  ");
            cx += gl.width;
        }
    }

    // ---- moments: life changes and your turn -----------------------------------------------------

    /** A "-7" or "+3" rising from a seat's monitor. */
    static final class LifePop {
        final int seat;
        int delta;
        float age;
        LifePop(int seat, int delta) { this.seat = seat; this.delta = delta; }
    }

    final List<LifePop> lifePops = new ArrayList<>();
    final java.util.Map<String, Integer> lastLife = new java.util.HashMap<>();
    boolean momentsPrimed;
    boolean lastYouActive;
    /** Seconds since the YOUR TURN sweep started, or -1 when it is not showing. */
    float yourTurnAge = -1f;
    static final float POP_LIFE = 1.6f, POP_MERGE = 0.35f, SWEEP_LIFE = 1.7f;

    /**
     * Compares this snapshot with the last one and starts the moments that changed: a popup per
     * life change (several within POP_MERGE of each other add up, so a burst of damage reads as
     * one number), and the sweep when your own turn begins. Nothing fires on the first snapshot,
     * so opening the window mid-game does not throw every life total at you.
     */
    void noticeChanges() {
        for (LifePop p : lifePops) p.age += frameDt;
        lifePops.removeIf(p -> p.age > POP_LIFE);
        if (yourTurnAge >= 0f) {
            yourTurnAge += frameDt;
            if (yourTurnAge > SWEEP_LIFE) yourTurnAge = -1f;
        }
        boolean youActive = false;
        for (int i = 0; i < snap.seats.size(); i++) {
            Snapshot.SeatV s = snap.seats.get(i);
            if (s.isYou && s.active) youActive = true;
            Integer before = lastLife.put(s.name, s.life);
            if (!momentsPrimed || before == null || before == s.life) continue;
            LifePop merge = null;
            for (LifePop p : lifePops) if (p.seat == i && p.age < POP_MERGE) merge = p;
            if (merge != null) { merge.delta += s.life - before; merge.age = 0f; }
            else lifePops.add(new LifePop(i, s.life - before));
        }
        if (momentsPrimed && youActive && !lastYouActive) yourTurnAge = 0f;
        lastYouActive = youActive;

        // Owner's list 2026-10-20: a banner for EVERY player's turn, and on your own turn one per
        // phase - the "Carol's turn" mix-up in bug report 143053 came from not seeing the change.
        if (turnBannerAge >= 0f) { turnBannerAge += frameDt; if (turnBannerAge > TURN_BANNER_LIFE) turnBannerAge = -1f; }
        if (phaseBannerAge >= 0f) { phaseBannerAge += frameDt; if (phaseBannerAge > PHASE_BANNER_LIFE) phaseBannerAge = -1f; }
        String activeName = null;
        for (Snapshot.SeatV s : snap.seats) if (s.active) activeName = s.name;
        if (momentsPrimed && activeName != null && !activeName.equals(lastActiveName) && !youActive) {
            turnBannerText = activeName.toUpperCase() + "'S TURN";
            turnBannerAge = 0f;
            phaseBannerAge = -1f;
        }
        if (activeName != null) lastActiveName = activeName;
        // YOUR RESPONSE: holding priority on someone else's turn for RESPONSE_AFTER seconds - easy
        // not to notice in a four-player game. Once per wait; re-armed when priority moves on.
        // (Was a Swing banner on Forge's window - PlayHook.ResponseWatcher - which now stays quiet
        // when the 3D table is on.)
        Snapshot.SeatV you = snap.you();
        boolean waitingOnYou = you != null && waitingOn(you) && !you.active && !snap.gameOver;
        if (!waitingOnYou) { respondWait = 0f; respondShown = false; }
        else if (!respondShown) {
            respondWait += frameDt;
            if (respondWait >= RESPONSE_AFTER) { respondShown = true; respondAge = 0f; }
        }
        if (respondAge >= 0f) { respondAge += frameDt; if (respondAge > RESPONSE_LIFE || !waitingOnYou) respondAge = -1f; }
        String key = snap.phaseKey == null ? "" : snap.phaseKey;
        if (momentsPrimed && youActive && !key.equals(lastPhaseKey)) {
            String label = PHASE_BANNERS.get(key);
            if (label != null) { phaseBannerText = label; phaseBannerAge = 0f; }
        }
        lastPhaseKey = key;
        if (!snap.seats.isEmpty()) momentsPrimed = true;
    }

    float respondWait, respondAge = -1f;
    boolean respondShown;
    /** Seconds of holding priority on another player's turn before YOUR RESPONSE shows (as PlayHook's). */
    static final float RESPONSE_AFTER = Long.getLong("forge.responsebanner.ms", 10000L) / 1000f, RESPONSE_LIFE = 6f;

    /** YOUR RESPONSE, in amber, the YOUR TURN band's shape; it stays until you act or 6 s pass. */
    void drawResponseBanner(int w, int h, Matrix4 flat) {
        float t = respondAge / RESPONSE_LIFE;
        float in = Math.min(1f, respondAge / 0.25f);
        float a = t < 0.8f ? 1f : Math.max(0f, 1f - (t - 0.8f) / 0.2f);
        float slide = (1f - smooth(in)) * -w;
        float bandH = 84f, y = h * 0.58f;
        Gdx.gl.glEnable(GL20.GL_BLEND);
        shapes.setProjectionMatrix(flat);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.08f, 0.05f, 0.02f, 0.75f * a);
        shapes.rect(slide, y, w, bandH);
        shapes.setColor(0.95f, 0.55f, 0.15f, a);
        shapes.rect(slide, y, w, 3f);
        shapes.rect(slide, y + bandH - 3f, w, 3f);
        shapes.end();
        ui.setProjectionMatrix(flat);
        ui.begin();
        GlyphLayout gl = new GlyphLayout();
        fontBig.getData().setScale(2.8f);
        fontBig.setColor(1f, 0.70f, 0.30f, a);
        gl.setText(fontBig, "YOUR RESPONSE");
        fontBig.draw(ui, "YOUR RESPONSE", slide + (w - gl.width) / 2f, y + bandH / 2f + gl.height / 2f + 8f);
        fontBig.getData().setScale(1.6f);
        String sub = "the game is waiting on you   -   SPACE passes";
        font.setColor(0.95f, 0.80f, 0.60f, a);
        gl.setText(font, sub);
        font.draw(ui, sub, slide + (w - gl.width) / 2f, y + 18f);
        ui.end();
    }

    String lastActiveName, lastPhaseKey = "";
    String turnBannerText = "", phaseBannerText = "";
    float turnBannerAge = -1f, phaseBannerAge = -1f;
    static final float TURN_BANNER_LIFE = 1.5f, PHASE_BANNER_LIFE = 1.2f;
    /**
     * The steps of your own turn worth a banner. Untap, cleanup and the combat sub-steps are left
     * out: they pass in a blink or have their own on-screen mode (attack / block drag), and a
     * banner for each would bury the ones that matter.
     */
    static final java.util.Map<String, String> PHASE_BANNERS = new java.util.LinkedHashMap<>();
    static {
        PHASE_BANNERS.put("UPKEEP", "UPKEEP");
        PHASE_BANNERS.put("DRAW", "DRAW");
        PHASE_BANNERS.put("MAIN1", "MAIN PHASE 1");
        PHASE_BANNERS.put("COMBAT_BEGIN", "COMBAT");
        PHASE_BANNERS.put("MAIN2", "MAIN PHASE 2");
        PHASE_BANNERS.put("END_OF_TURN", "END STEP");
    }

    /**
     * Someone else's turn: the YOUR TURN band's shape, smaller and in steel blue, so it reads as
     * "the table moved on" rather than "act now".
     */
    void drawTurnBanner(int w, int h, Matrix4 flat) {
        float t = turnBannerAge / TURN_BANNER_LIFE;
        float in = Math.min(1f, turnBannerAge / 0.22f);
        float a = t < 0.6f ? 1f : Math.max(0f, 1f - (t - 0.6f) / 0.4f);
        float slide = (1f - smooth(in)) * w;                            // from the right
        float bandH = 60f, y = h * 0.60f;
        Gdx.gl.glEnable(GL20.GL_BLEND);
        shapes.setProjectionMatrix(flat);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.03f, 0.05f, 0.08f, 0.62f * a);
        shapes.rect(slide, y, w, bandH);
        shapes.setColor(0.50f, 0.68f, 0.86f, a);
        shapes.rect(slide, y, w, 2f);
        shapes.rect(slide, y + bandH - 2f, w, 2f);
        shapes.end();
        ui.setProjectionMatrix(flat);
        ui.begin();
        GlyphLayout gl = new GlyphLayout();
        fontBig.getData().setScale(2.3f);
        fontBig.setColor(0.72f, 0.84f, 0.96f, a);
        gl.setText(fontBig, turnBannerText);
        fontBig.draw(ui, turnBannerText, slide + (w - gl.width) / 2f, y + bandH / 2f + gl.height / 2f);
        fontBig.getData().setScale(1.6f);
        ui.end();
    }

    /** The step of your own turn: a slim gold strip, small enough that it never hides the board. */
    void drawPhaseBanner(int w, int h, Matrix4 flat) {
        float t = phaseBannerAge / PHASE_BANNER_LIFE;
        float a = t < 0.15f ? t / 0.15f : t < 0.7f ? 1f : Math.max(0f, 1f - (t - 0.7f) / 0.3f);
        // Above the table, in the dark: lower down it sat on the phase name painted on the felt.
        float bandW = 360f, bandH = 40f, x = (w - bandW) / 2f, y = h * 0.72f;
        Gdx.gl.glEnable(GL20.GL_BLEND);
        shapes.setProjectionMatrix(flat);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.04f, 0.05f, 0.07f, 0.70f * a);
        shapes.rect(x, y, bandW, bandH);
        shapes.setColor(0.95f, 0.72f, 0.22f, a);
        shapes.rect(x, y, 3f, bandH);
        shapes.rect(x + bandW - 3f, y, 3f, bandH);
        shapes.end();
        ui.setProjectionMatrix(flat);
        ui.begin();
        GlyphLayout gl = new GlyphLayout();
        fontBig.getData().setScale(1.6f);
        fontBig.setColor(1f, 0.85f, 0.40f, a);
        gl.setText(fontBig, phaseBannerText);
        fontBig.draw(ui, phaseBannerText, x + (bandW - gl.width) / 2f, y + bandH / 2f + gl.height / 2f);
        ui.end();
    }

    // ---- toast: a short message by the mouse, e.g. why a click did nothing ------------------------

    String toastText;
    float toastAge, toastX, toastY;
    static final float TOAST_LIFE = 2.2f;

    void toast(String text, float x, float y) {
        toastText = text;
        toastAge = 0f;
        toastX = x;
        toastY = y;
    }

    /** Drawn after the label pass: a dark chip with the message, rising slightly and fading. */
    void drawToast(int w, Matrix4 flat) {
        if (toastText == null) return;
        toastAge += frameDt;
        if (toastAge > TOAST_LIFE) { toastText = null; return; }
        float a = toastAge < 0.15f ? toastAge / 0.15f : toastAge > TOAST_LIFE - 0.5f ? (TOAST_LIFE - toastAge) / 0.5f : 1f;
        GlyphLayout gl = new GlyphLayout(font, toastText);
        float bw = gl.width + 20f, bh = gl.height + 16f;
        float x = Math.max(8f, Math.min(w - bw - 8f, toastX + 16f)), y = toastY + 18f + toastAge * 10f;
        Gdx.gl.glEnable(GL20.GL_BLEND);
        shapes.setProjectionMatrix(flat);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.10f, 0.05f, 0.05f, 0.88f * a);
        shapes.rect(x, y, bw, bh);
        shapes.setColor(0.90f, 0.45f, 0.30f, a);
        shapes.rect(x, y, 3f, bh);
        shapes.end();
        ui.setProjectionMatrix(flat);
        ui.begin();
        font.setColor(1f, 0.85f, 0.75f, a);
        font.draw(ui, toastText, x + 12f, y + bh - 8f);
        ui.end();
    }

    /** The target / sacrifice helpers live with the clicks they guard (TableInput). */
    abstract Snapshot.CardV source();
    abstract boolean sacrificePrompt();
    abstract boolean sacrificeComesNext();
    abstract String lastTarget();

    /** Targeting mode's instruction line, top centre, in the arc's violet. Inside the label pass. */
    void drawTargetBanner(int w, int h) {
        String line, sub;
        Color c;
        if (grabbedId >= 0) {
            String name = "a card";
            for (Snapshot.CardV card : snap.hand) if (card.id == grabbedId) name = card.name;
            line = "Moving " + name + " in your hand   (place " + (grabIndex + 1)
                + " of " + snap.hand.size() + ")";
            sub = "left / right  or  the mouse   -   SPACE drops it   -   ESC puts it back"
                + (Pad.connected() ? "   -   pad: D-pad places, A drops, B cancels" : "");
            c = new Color(0.95f, 0.85f, 0.45f, 1f);
        } else if (targeting != null && targeting.wired) {
            // Forge's own words for what it wants, under a plain heading.
            Snapshot.CardV src = source();
            line = src == null ? "Choose targets" : "Choose what " + src.name + " hits";
            // Forge asks for the target BEFORE a sacrifice cost (Goblin Bombardment), the reverse
            // of how it reads on the card; a creature clicked to sacrifice here becomes the target.
            if (sacrificeComesNext()) {
                sub = "click the player or creature to HIT   -   you choose the SACRIFICE after this   -   ESC cancels";
            } else {
                sub = targeting.what + "   -   click a glowing card or a player   -   ESC cancels";
            }
            c = new Color(0.80f, 0.62f, 1f, 1f);
        } else if (sacrificePrompt()) {
            line = "Sacrifice" + (lastTarget().isEmpty() ? "" : "   -   target: " + lastTarget());
            sub = Gui3D.PROMPT.message + "   -   click a glowing card of yours   -   X cancels the whole ability";
            c = new Color(1f, 0.50f, 0.42f, 1f);
        } else if (manaPlan != null && manaPlan.wired) {
            line = "Pay mana";
            // Say the pool is spendable only when there is something in it, so the line does not
            // offer a thing that would do nothing.
            Snapshot.SeatV mine = snap.you();
            int floating = 0;
            if (mine != null) for (int v : mine.mana) floating += v;
            sub = manaPlan.cost + "   -   click a glowing land or mana source"
                + (floating > 0 ? "   -   or a bead to spend floating mana (1-6)" : "")
                + "   -   ESC cancels";
            c = new Color(0.60f, 0.92f, 0.62f, 1f);
        } else if (targeting != null) {
            line = "Choose " + (targeting.max == 1 ? "a " : targeting.max + " ") + targeting.what
                + "   (" + targeting.picked.size() + " of " + targeting.max + ")";
            sub = "click a glowing card   -   ESC cancels";
            c = new Color(0.80f, 0.62f, 1f, 1f);
        } else if (combatPlan != null && !combatPlan.blocking) {
            line = "Declare attackers   (" + combatPlan.attackSeat.size() + " attacking)";
            sub = "drag a glowing creature onto a player   -   a pile sends all of it, SHIFT sends one"
                + "   -   drop it back to take it off   -   SPACE confirms   ESC cancels";
            c = new Color(1f, 0.55f, 0.40f, 1f);
        } else if (combatPlan != null) {
            String on = null;
            if (combatPlan.current != Integer.MIN_VALUE) {
                for (Snapshot.SeatV s : snap.seats) {
                    for (Snapshot.CardV atk : s.creatures) if (atk.id == combatPlan.current) on = atk.name;
                }
            }
            int onThisOne = 0;
            for (Integer a : combatPlan.blockOn.values()) {
                if (a != null && a == combatPlan.current) onThisOne++;
            }
            line = on == null
                ? "Declare blockers   (" + combatPlan.blockOn.size() + " blocking)"
                : "Blocking " + on + "   (" + onThisOne + " on it, "
                    + combatPlan.blockOn.size() + " blocking in all)";
            sub = on == null
                ? "click an attacker to block it (or <- -> to step through them), then click your creatures"
                    + "   -   dragging still works   -   SPACE confirms   ESC cancels"
                : "click your creatures to put them in front of it   -   click another attacker to switch"
                    + "   -   SPACE confirms   ESC cancels";
            c = new Color(0.55f, 0.80f, 1f, 1f);
        } else if (manaPlan != null) {
            line = "Pay " + manaPlan.cost + "   (" + manaPlan.tapped.size() + " of " + manaPlan.total + " paid)";
            sub = "click a glowing land to tap it   -   ESC cancels";
            c = new Color(0.60f, 0.92f, 0.62f, 1f);
        } else {
            return;
        }
        GlyphLayout gl = new GlyphLayout();
        fontBig.getData().setScale(2.0f);
        fontBig.setColor(c);
        gl.setText(fontBig, line);
        fontBig.draw(ui, line, (w - gl.width) / 2f, h - 30f);
        fontBig.getData().setScale(1.6f);
        font.setColor(c.r * 0.78f, c.g * 0.78f, c.b * 0.78f, 1f);
        gl.setText(font, sub);
        font.draw(ui, sub, (w - gl.width) / 2f, h - 72f);
    }

    /** The popups, inside the label pass. They rise and fade from the seat's centre monitor. */
    void drawLifePops() {
        int n = snap.seats.size();
        for (LifePop p : lifePops) {
            if (p.seat >= n || p.delta == 0) continue;
            Vector3 w = rotateY(0f, MONITOR_DIST, seatAngle(p.seat, n));
            Vector3 s = cam.project(new Vector3(w.x, 0.62f, w.z));
            if (s.z > 1f) continue;
            float t = p.age / POP_LIFE;
            float a = t < 0.7f ? 1f : 1f - (t - 0.7f) / 0.3f;
            String txt = (p.delta > 0 ? "+" : "") + p.delta;
            fontBig.getData().setScale(2.2f);
            if (p.delta < 0) fontBig.setColor(1f, 0.38f, 0.30f, a);
            else fontBig.setColor(0.45f, 1f, 0.55f, a);
            fontBig.draw(ui, txt, s.x + 26f, s.y + 34f + t * 70f);
            fontBig.getData().setScale(1.6f);
        }
    }

    /**
     * YOUR TURN: a gold band sweeps in from the left across the middle of the screen, holds, and
     * fades - the moment the 3D window most needs you to look at it, made hard to miss.
     */
    void drawYourTurn(int w, int h, Matrix4 flat) {
        float t = yourTurnAge / SWEEP_LIFE;
        float in = Math.min(1f, yourTurnAge / 0.25f);                  // slide in over 0.25 s
        float a = t < 0.65f ? 1f : Math.max(0f, 1f - (t - 0.65f) / 0.35f);
        float slide = (1f - smooth(in)) * -w;
        float bandH = 90f, y = h * 0.58f;
        Gdx.gl.glEnable(GL20.GL_BLEND);
        shapes.setProjectionMatrix(flat);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.03f, 0.05f, 0.08f, 0.72f * a);
        shapes.rect(slide, y, w, bandH);
        shapes.setColor(0.95f, 0.72f, 0.22f, a);
        shapes.rect(slide, y, w, 3f);
        shapes.rect(slide, y + bandH - 3f, w, 3f);
        shapes.end();
        ui.setProjectionMatrix(flat);
        ui.begin();
        GlyphLayout gl = new GlyphLayout();
        fontBig.getData().setScale(3.2f);
        fontBig.setColor(1f, 0.85f, 0.40f, a);
        gl.setText(fontBig, "YOUR TURN");
        fontBig.draw(ui, "YOUR TURN", slide + (w - gl.width) / 2f, y + bandH / 2f + gl.height / 2f);
        fontBig.getData().setScale(1.6f);
        ui.end();
    }

    /**
     * The tracker in the middle of the table: life, and commander damage taken from each
     * commander. All of it is public knowledge, which is why it can sit in the open where
     * everyone's view of it is the same.
     */
    /**
     * Whose commander this is, wherever it happens to be. The command zone first, because that is
     * where it sits between casts, then the battlefield. Falls back to whatever else is in the
     * command zone so a partner pair or a companion still shows something.
     */
    static Snapshot.CardV commanderOf(Snapshot.SeatV s) {
        for (Snapshot.CardV c : s.command) if (c.commander) return c;
        for (Snapshot.CardV c : s.creatures) if (c.commander) return c;
        for (Snapshot.CardV c : s.others) if (c.commander) return c;
        return s.command.isEmpty() ? null : s.command.get(0);
    }

    /**
     * Each player's screen in the middle of the table, laid out to the owner's sketch
     * (ui3d\design\centre-screen-sketch-2026-10-23.jpg, settled with him on 2026-10-23):
     *
     *   left        the player's commander, as its card picture (partners side by side), with
     *               the commander tax in its corner
     *   right       the player's name; the life total, big like the scoreboard; one line per
     *               enemy commander that has dealt this player commander damage, by name, with
     *               the total (orange from 15, red at 21); designations and counters as small
     *               coloured chips (monarch, initiative, poison, energy...)
     *   bottom      floating mana as beads - one bead per mana, in its colour; none when empty
     *
     * Drawn ON the glass (the batch is given the glass's own matrix), so it lies in the screen's
     * plane and leans with it. The glass is GLASS_PX table units a pixel: 286 x 144 pixels, with
     * (0,0) at its middle. Shapes (beads, chips, picture frame) go first, words after. Drawn
     * after the 3D pass with its own projection, which it hands back when done.
     */
    void drawCentreTracker() {
        int n = snap.seats.size();
        if (monitorXf.size() < n) return;
        GlyphLayout gl = new GlyphLayout();
        for (int i = 0; i < n; i++) {
            Snapshot.SeatV s = snap.seats.get(i);
            float[] at = monitorAt.get(i);
            // The overhead stack lies over the screens; their readout would print over the
            // cards. The same numbers are on the scoreboard panel and by each seat.
            if (underFlatStack(at[0], at[2])) continue;

            // The glass's placement, with the drawing plane turned to lie in it (a SpriteBatch
            // draws in XY; the glass is a flat slab in its own XZ) and scaled from pixels to
            // table units.
            Matrix4 m = new Matrix4(monitorXf.get(i));
            m.rotate(Vector3.X, -90f);
            m.scale(GLASS_PX, GLASS_PX, GLASS_PX);

            final float L = -136f, R = 136f, T = 66f, B = -66f;      // inside a small margin
            final float beadY = B + 8f;                               // the mana strip
            // ---- left: the commander picture(s) -------------------------------------------
            java.util.List<Snapshot.CardV> cmds = commandersOf(s);
            float picH = T - (beadY + 12f), picW = picH * 5f / 7f;   // card proportions
            if (cmds.size() > 1) { picW = Math.min(picW, 92f / cmds.size()); picH = picW * 7f / 5f; }
            float right0 = L + picW * Math.max(1, cmds.size()) + 9f; // where the text column starts

            // ---- shapes: picture frames and mana beads --------------------------------------
            shapes.setProjectionMatrix(cam.combined);
            shapes.setTransformMatrix(m);
            Gdx.gl.glEnable(GL20.GL_BLEND);
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            for (int k = 0; k < cmds.size(); k++) {                 // no commander card: no frame
                shapes.setColor(0.04f, 0.05f, 0.08f, 0.85f);
                shapes.rect(L + k * picW - 1f, T - picH - 1f, picW + 2f, picH + 2f);
            }
            // Floating mana along the bottom: one bead per mana, in its colour.
            float bx = L + 5f;
            int beads = 0;
            for (int c = 0; c < 6; c++) {
                for (int k = 0; k < s.mana[c] && beads < MAX_BEADS; k++, beads++) {
                    Color bc = BEAD_COLOURS[c];
                    shapes.setColor(0.02f, 0.02f, 0.03f, 1f);
                    shapes.circle(bx, beadY, 6.4f, 16);
                    shapes.setColor(bc);
                    shapes.circle(bx, beadY, 5.4f, 16);
                    shapes.setColor(Math.min(1f, bc.r + 0.35f), Math.min(1f, bc.g + 0.35f), Math.min(1f, bc.b + 0.35f), 0.9f);
                    shapes.circle(bx - 1.7f, beadY + 1.7f, 1.6f, 8);      // a glint, so it reads as a bead
                    bx += 14.5f;
                }
            }
            shapes.end();

            // ---- words and the picture ------------------------------------------------------
            ui.setProjectionMatrix(cam.combined);
            ui.setTransformMatrix(m);
            ui.begin();
            for (int k = 0; k < cmds.size(); k++) {
                com.badlogic.gdx.graphics.Texture tex = art == null ? null : art.get(cmds.get(k));
                if (tex != null) {
                    ui.setColor(1f, 1f, 1f, s.lost ? 0.35f : 1f);
                    ui.draw(tex, L + k * picW, T - picH, picW, picH);
                    ui.setColor(1f, 1f, 1f, 1f);
                }
            }
            if (!cmds.isEmpty() && cmds.get(0).commanderCasts > 0) {
                font.getData().setScale(1.0f);
                font.setColor(1f, 0.80f, 0.40f, 1f);
                font.draw(ui, "tax +" + (2 * cmds.get(0).commanderCasts), L + 2f, T - picH + 10f);
                font.getData().setScale(1f);
            }

            float colW = R - right0;
            Color tint = s.lost ? new Color(0.50f, 0.44f, 0.44f, 1f)
                    : s.active ? new Color(1f, 0.85f, 0.35f, 1f)
                    : s.isYou ? new Color(0.65f, 0.95f, 1f, 1f)
                    : new Color(0.85f, 0.90f, 0.96f, 1f);
            // The player's name.
            fontBig.getData().setScale(1.55f);
            fontBig.setColor(tint);
            fontBig.draw(ui, fit(fontBig, gl, s.name, colW), right0, T);
            // The life total, big like the scoreboard; red at 10 or below.
            fontBig.getData().setScale(3.5f);
            if (!s.lost && s.life <= 10) fontBig.setColor(1f, 0.38f, 0.32f, 1f);
            fontBig.draw(ui, s.lost ? "OUT" : String.valueOf(s.life), right0, T - 19f);
            fontBig.getData().setScale(1.6f);

            // Commander damage this player has TAKEN, one line per enemy commander that has hit
            // them, by name - the owner's example: Captain America's screen shows
            // "Olivia, Opulent Outlaw   4". Nothing until a commander has dealt damage.
            float y = T - 64f;
            font.getData().setScale(1.2f);
            int shown = 0;
            for (Snapshot.CmdDmg d : s.commanderDamage) {
                if (shown++ >= 3) break;
                Color dc = d.amount >= 21 ? new Color(1f, 0.30f, 0.28f, 1f)
                        : d.amount >= 15 ? new Color(1f, 0.62f, 0.25f, 1f) : new Color(0.86f, 0.80f, 0.74f, 1f);
                String num = String.valueOf(d.amount);
                gl.setText(font, num);
                float numW = gl.width;
                font.setColor(dc);
                font.draw(ui, fit(font, gl, d.from, colW - numW - 8f), right0, y);
                font.draw(ui, num, R - numW, y);
                y -= 14f;
            }
            font.getData().setScale(1f);
            ui.end();

            // Designations and counters as small coloured chips, under the damage lines.
            java.util.List<String[]> chips = screenChips(s);
            if (!chips.isEmpty()) drawChips(m, chips, right0, y - 2f, R, gl);
        }
        // Hand the batches back the way the rest of the overlay expects to find them.
        Matrix4 ortho = new Matrix4().setToOrtho2D(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        shapes.setTransformMatrix(new Matrix4());
        shapes.setProjectionMatrix(ortho);
        ui.setTransformMatrix(new Matrix4());
        ui.setProjectionMatrix(ortho);
    }

    static final int MAX_BEADS = 24;
    /** W, U, B, R, G, colourless - B is a dark violet, so it still shows on the dark glass. */
    static final Color[] BEAD_COLOURS = {
        new Color(0.97f, 0.93f, 0.78f, 1f), new Color(0.22f, 0.52f, 0.96f, 1f), new Color(0.40f, 0.24f, 0.48f, 1f),
        new Color(0.92f, 0.28f, 0.20f, 1f), new Color(0.22f, 0.70f, 0.30f, 1f), new Color(0.66f, 0.66f, 0.70f, 1f) };

    /** The player's commander(s): partners and backgrounds side by side, at most two. */
    static java.util.List<Snapshot.CardV> commandersOf(Snapshot.SeatV s) {
        java.util.List<Snapshot.CardV> out = new java.util.ArrayList<>();
        for (java.util.List<Snapshot.CardV> zone : java.util.Arrays.asList(s.command, s.creatures, s.others)) {
            for (Snapshot.CardV c : zone) if (c.commander && out.size() < 2) out.add(c);
        }
        // Only cards marked as commanders: an emblem or a companion in the command zone is not one.
        return out;
    }

    /** Text cut to fit a width, with "..." when it had to be cut. Uses the font's current scale. */
    static String fit(com.badlogic.gdx.graphics.g2d.BitmapFont f, GlyphLayout gl, String text, float width) {
        if (text == null) return "";
        gl.setText(f, text);
        if (gl.width <= width) return text;
        for (int k = text.length() - 1; k > 1; k--) {
            String t = text.substring(0, k).trim() + "...";
            gl.setText(f, t);
            if (gl.width <= width) return t;
        }
        return text.substring(0, 1);
    }

    /**
     * The chips: a short label on a coloured pill per designation or counter, {label, r, g, b}.
     * Poison comes from the seat's own count unless the counters already carry it.
     */
    static java.util.List<String[]> screenChips(Snapshot.SeatV s) {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        boolean poisonMarked = false;
        for (String mk : s.marks) {
            String u = mk.toUpperCase();
            if (u.startsWith("POISON")) poisonMarked = true;
            String label = u.startsWith("CITY'S BLESSING") ? "BLESSING" : u.startsWith("RING-BEARER") ? "RING" : u;
            float[] c = u.startsWith("MONARCH") ? new float[] { 0.95f, 0.75f, 0.20f }
                : u.startsWith("INITIATIVE") ? new float[] { 0.95f, 0.50f, 0.20f }
                : u.startsWith("POISON") ? new float[] { 0.35f, 0.75f, 0.25f }
                : u.startsWith("ENERGY") ? new float[] { 0.95f, 0.85f, 0.25f }
                : u.startsWith("EXPERIENCE") ? new float[] { 0.65f, 0.45f, 0.90f }
                : u.startsWith("RAD") ? new float[] { 0.70f, 0.90f, 0.20f }
                : u.startsWith("SPEED") ? new float[] { 0.30f, 0.80f, 0.95f }
                : u.startsWith("RING") ? new float[] { 0.95f, 0.85f, 0.45f }
                : new float[] { 0.62f, 0.66f, 0.74f };
            out.add(new String[] { label, String.valueOf(c[0]), String.valueOf(c[1]), String.valueOf(c[2]) });
        }
        if (!poisonMarked && s.poison > 0) out.add(new String[] { "POISON " + s.poison, "0.35", "0.75", "0.25" });
        return out;
    }

    /** Pills left to right from (x0, y), wrapping once at the right edge; anything past that is dropped. */
    void drawChips(Matrix4 m, java.util.List<String[]> chips, float x0, float y, float right, GlyphLayout gl) {
        font.getData().setScale(0.95f);
        java.util.List<float[]> boxes = new java.util.ArrayList<>();
        float x = x0;
        int row = 0;
        for (String[] c : chips) {
            gl.setText(font, c[0]);
            float w = gl.width + 9f, h = 13f;
            if (x + w > right) { if (++row > 1) break; x = x0; }
            boxes.add(new float[] { x, y - h - row * 15f, w, h });
            x += w + 3f;
        }
        shapes.setProjectionMatrix(cam.combined);
        shapes.setTransformMatrix(m);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int k = 0; k < boxes.size(); k++) {
            float[] b = boxes.get(k);
            String[] c = chips.get(k);
            float r = Float.parseFloat(c[1]), g = Float.parseFloat(c[2]), bl = Float.parseFloat(c[3]);
            shapes.setColor(r * 0.45f, g * 0.45f, bl * 0.45f, 0.95f);
            shapes.rect(b[0], b[1], b[2], b[3]);
            shapes.setColor(r, g, bl, 1f);
            shapes.rect(b[0], b[1], 2f, b[3]);                     // the coloured edge is the "symbol"
        }
        shapes.end();
        ui.setProjectionMatrix(cam.combined);
        ui.setTransformMatrix(m);
        ui.begin();
        for (int k = 0; k < boxes.size(); k++) {
            float[] b = boxes.get(k);
            font.setColor(1f, 0.97f, 0.92f, 1f);
            font.draw(ui, chips.get(k)[0], b[0] + 5.5f, b[1] + b[3] - 2.5f);
        }
        ui.end();
        font.getData().setScale(1f);
    }

    /**
     * The badge that flashes while a question has been put aside to look at the board.
     *
     * It has to be impossible to forget. A panel that can be dismissed without being answered is
     * a game that quietly stops, and the player who put it aside is the only one who knows why -
     * so this pulses, names what is waiting, and says how to get back to it.
     *
     * Drawn after everything else, in its own batch, like the question box it stands in for.
     */
    void drawPutAside(int w, int h) {
        if (!peeking) return;
        Gui3D.Ask ask = Gui3D.pendingAsk();
        String what = ask != null && !ask.cardName.isEmpty() ? ask.cardName
            : ask != null ? "A question"
            : search != null && search.isOpen() ? "A list of cards"
            : "A decision";
        // Two seconds a cycle, never fully out: a badge that disappears is one you stop seeing.
        float pulse = 0.62f + 0.38f * (float) Math.abs(Math.sin(beatClock * 3.1f));
        String line = what + " is waiting";
        String sub = "V brings it back";
        GlyphLayout gl = new GlyphLayout(fontBig, line);
        GlyphLayout gs = new GlyphLayout(font, sub);
        float bw = Math.max(gl.width, gs.width) + 44f, bh = 64f;
        float x = w - bw - 24f, y = h - bh - 24f;

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Matrix4 flat = new Matrix4().setToOrtho2D(0, 0, w, h);
        shapes.setProjectionMatrix(flat);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.22f * pulse, 0.06f, 0.02f, 0.92f);
        shapes.rect(x, y, bw, bh);
        shapes.setColor(1f * pulse, 0.62f * pulse, 0.18f * pulse, 1f);
        shapes.rect(x, y + bh - 4f, bw, 4f);
        shapes.rect(x, y, bw, 3f);
        shapes.end();

        ui.setProjectionMatrix(flat);
        ui.begin();
        fontBig.setColor(1f, 0.80f + 0.18f * pulse, 0.50f, 1f);
        fontBig.draw(ui, line, x + 22f, y + bh - 18f);
        font.setColor(0.92f, 0.76f, 0.58f, 1f);
        font.draw(ui, sub, x + 22f, y + 22f);
        ui.end();
    }

    /** Seconds, for anything on the HUD that pulses. */
    float beatClock;

    /** One "pixel" of a readout, in table units: the glass is about 2.5 units across. */
    static final float GLASS_PX = 0.0088f;

    /**
     * The enlarged card that follows the mouse. When Forge has the real picture cached it is shown
     * large, the way a client zooms a card on hover, with a strip under it for what the printed
     * card cannot say:
     * current power/toughness, counters, what it is attached to, tapped/attacking/blocking, and
     * what a stack item is doing. A drawn face (uncached card, face-down back) or a picture still
     * loading keeps the text panel, which reads better than small generated print blown up.
     */
    void drawDetailPanel(int w, int h, Matrix4 flat) {
        Snapshot.CardV c = hovered.card;
        com.badlogic.gdx.graphics.Texture tex = art == null || !art.hasPicture(c) ? null : art.get(c);
        if (tex != null) {
            drawPicturePanel(c, tex, w, h, flat);
            return;
        }
        float panelW = 340f;
        List<String> lines = new ArrayList<>();
        lines.add(c.name + (c.manaCost.isEmpty() ? "" : "   " + c.manaCost));
        lines.add(c.typeLine);
        if (c.creature && !c.power.isEmpty()) lines.add(c.power + " / " + c.toughness);
        if (c.counters > 0) lines.add(c.counters + " counters");
        if (c.tapped) lines.add("(tapped)");
        if (!c.stackText.isEmpty()) {
            lines.addAll(wrap("ON THE STACK: " + c.stackText, panelW - 24f));
        }
        lines.addAll(wrap(c.text, panelW - 24f));
        for (String r : Keywords.reminders(c.text, 4)) lines.addAll(wrap(r, panelW - 24f));

        GlyphLayout gl = new GlyphLayout();
        float lineH = 18f, pad = 12f;
        float panelH = pad * 2 + lines.size() * lineH + 10f;

        float mx = Gdx.input.getX(), my = h - Gdx.input.getY();
        float px = Math.min(mx + 18f, w - panelW - 8f);
        float py = Math.min(Math.max(my - panelH / 2f, 8f), h - panelH - 8f);

        Gdx.gl.glEnable(GL20.GL_BLEND);
        shapes.setProjectionMatrix(flat);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.02f, 0.09f, 0.14f, 0.94f);
        shapes.rect(px, py, panelW, panelH);
        shapes.setColor(0.18f, 0.56f, 0.71f, 1f);
        shapes.rect(px, py + panelH - 3f, panelW, 3f);
        shapes.end();

        ui.setProjectionMatrix(flat);
        ui.begin();
        float y = py + panelH - pad;
        for (int i = 0; i < lines.size(); i++) {
            if (i == 0) fontBig.setColor(0.85f, 0.95f, 1f, 1f);
            font.setColor(i <= 2 ? new Color(0.78f, 0.90f, 1f, 1f) : new Color(0.66f, 0.76f, 0.84f, 1f));
            if (i == 0) {
                gl.setText(fontBig, lines.get(i));
                fontBig.draw(ui, lines.get(i), px + pad, y);
                y -= lineH + 8f;
            } else {
                font.draw(ui, lines.get(i), px + pad, y);
                y -= lineH;
            }
        }
        ui.end();
    }

    /** The picture version of the hover panel; see drawDetailPanel. */
    void drawPicturePanel(Snapshot.CardV c, com.badlogic.gdx.graphics.Texture tex,
                                  int w, int h, Matrix4 flat) {
        // Card proportions (5:7), as tall as fits comfortably: big enough to read the rules text
        // on the art, not so big it hides the board it is describing.
        float picH = Math.min(h * 0.62f, 640f), picW = picH * 5f / 7f;
        float pad = 10f, lineH = 18f;

        List<String> lines = new ArrayList<>();
        if (!c.stackText.isEmpty()) lines.addAll(wrap("On the stack: " + c.stackText, picW - pad * 2));
        if (c.creature && !c.power.isEmpty()) lines.add("Now " + c.power + " / " + c.toughness);
        if (!c.counterText.isEmpty()) lines.add("Counters: " + String.join(", ", c.counterText));
        else if (c.counters > 0) lines.add(c.counters + " counters");
        if (!c.attachedTo.isEmpty()) lines.addAll(wrap("Attached to " + c.attachedTo, picW - pad * 2));
        List<String> state = new ArrayList<>();
        if (c.attacking) state.add("attacking");
        if (c.blocking) state.add("blocking");
        if (c.tapped) state.add("tapped");
        if (c.token) state.add("token");
        if (c.faceDown) state.add("face down");
        if (!state.isEmpty()) lines.add(String.join(", ", state));
        // What the keywords on it mean - the printed card names them but does not explain them.
        for (String r : Keywords.reminders(c.text, 4)) lines.addAll(wrap(r, picW - pad * 2));
        float infoH = lines.isEmpty() ? 0f : pad * 2 + lines.size() * lineH - 4f;

        float totalH = picH + infoH;
        float mx = Gdx.input.getX(), my = h - Gdx.input.getY();
        // Beside the cursor, on whichever side has room, so it never covers the card itself.
        float px = mx + 24f + picW <= w - 8f ? mx + 24f : mx - 24f - picW;
        px = Math.max(8f, px);
        float py = Math.min(Math.max(my - totalH / 2f, 8f), h - totalH - 8f);

        Gdx.gl.glEnable(GL20.GL_BLEND);
        shapes.setProjectionMatrix(flat);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0f, 0f, 0f, 0.55f);                       // soft shadow
        shapes.rect(px + 6f, py - 6f, picW, totalH);
        if (infoH > 0f) {
            shapes.setColor(0.02f, 0.09f, 0.14f, 0.94f);
            shapes.rect(px, py, picW, infoH);
            shapes.setColor(0.18f, 0.56f, 0.71f, 1f);
            shapes.rect(px, py + infoH - 2f, picW, 2f);
        }
        shapes.end();

        ui.setProjectionMatrix(flat);
        ui.begin();
        ui.setColor(Color.WHITE);
        ui.draw(tex, px, py + infoH, picW, picH);
        float y = py + infoH - pad;
        font.setColor(0.78f, 0.90f, 1f, 1f);
        for (String l : lines) {
            font.draw(ui, l, px + pad, y);
            y -= lineH;
        }
        ui.end();
    }
}
