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
        font.draw(ui, "A / D  walk round the table      S  back to your seat      W  overhead      N or ->  next phase      TAB  camera"
            + "      NUM 1-6  view   (SHIFT+7/8/9 saves)      right-drag  orbit      middle-drag  move      wheel  zoom      M  sound      H  hand back "
            + (cues != null && cues.muted() ? "(off)" : "(on)"), 14, 18);
        // Art counter: says at a glance whether Forge's picture cache was found at all, which is
        // the first thing to check if every card is still a coloured block.
        if (art != null) {
            String a = "art " + art.loaded() + " loaded / " + art.known() + " cached";
            font.draw(ui, a, w - 190, 18);
        }
        drawPhasePanel(w, h);
        bug.drawCogText(ui, font, w, h);
        // Strict mode: say so the moment Forge answers something the 3D window should own.
        // Without this a gap is invisible - the window just loses focus and nobody knows why.
        String fell = Gui3D.recentFallback();
        if (!fell.isEmpty()) {
            font.setColor(1f, 0.55f, 0.35f, 1f);
            font.draw(ui, "Forge answered: " + fell + "  (not taken over yet)", 14f, 80f);
        }
        drawPromptBar(w, h);
        drawPendingAsk(w, h);
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
        drawPhaseOnTable();
        drawZoneLabels();
        drawStackCounts();
        drawCentreTracker();
        drawLifePops();
        drawTargetBanner(w, h);
        drawStackLabels();
        ui.end();
        if (yourTurnAge >= 0f) drawYourTurn(w, h, flat);
        if (turnBannerAge >= 0f) drawTurnBanner(w, h, flat);
        if (phaseBannerAge >= 0f) drawPhaseBanner(w, h, flat);
        if (respondAge >= 0f) drawResponseBanner(w, h, flat);
        drawToast(w, flat);
        if (showLog) drawEventLog(w, h, flat);
        if (snap.gameOver) drawGameOver(w, h, flat);
        // Last, so the hovered card sits over every label rather than having them bleed through.
        if (hovered != null) drawDetailPanel(w, h, flat);
        bug.drawCog(shapes, ui, font, w, h, flat);
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
        Vector3 p = cam.project(new Vector3(CARD_W / 2f - 0.2f, CARD_THICK, CARD_H / 2f - 0.13f)
            .mul(d.inst.transform));
        if (p.z > 1f) return up;
        gl.setText(font, text);
        float bw = gl.width + 8f, bh = gl.height + 7f;
        boxes.add(new float[] { p.x - bw / 2f, p.y - bh / 2f + up, bw, bh });
        texts.add(text);
        colours.add(colour);
        return up + bh + 2f;
    }

    /**
     * The last few lines of Forge's game log, top right, so a trigger or a spell that went by
     * while you were looking elsewhere can be read back. Oldest at the top, newest at the bottom
     * and brightest; coloured by what kind of event it was. L hides it. The words are Forge's
     * own log, the one its window shows this player, so it reveals nothing new.
     */
    void drawEventLog(int w, int h, Matrix4 flat) {
        if (snap.log.isEmpty()) return;
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

    /**
     * The phase, written on the table between the centre monitors and the active player's
     * creatures, so where the turn is reads in the scene and not only in the corner text.
     */
    void drawPhaseOnTable() {
        int active = activeSeat();
        if (active < 0 || snap.phase.isEmpty()) return;
        Vector3 w = rotateY(0f, ROW_CREATURES - 1.45f, seatAngle(active, snap.seats.size()));
        Vector3 p = cam.project(new Vector3(w.x, 0.05f, w.z));
        if (p.z > 1f) return;
        fontBig.setColor(1f, 0.82f, 0.32f, 0.92f);
        // Day or night rides along with the phase: both are "what time is it" at the table.
        String text = snap.dayNight.isEmpty() ? snap.phase
            : snap.phase + "   " + (snap.dayNight.equals("DAY") ? "- day -" : "- night -");
        com.badlogic.gdx.graphics.g2d.GlyphLayout gl = new com.badlogic.gdx.graphics.g2d.GlyphLayout(fontBig, text);
        fontBig.draw(ui, gl, p.x - gl.width / 2f, p.y + gl.height / 2f);
    }

    /** Who cast what, under each item on the stack, plus a marker on the one resolving next. */
    void drawStackLabels() {
        if (snap.stack.isEmpty()) return;
        float step = CARD_W * 1.15f;
        float width = snap.stack.size() * step - (step - CARD_W);
        for (int i = 0; i < snap.stack.size(); i++) {
            Snapshot.CardV c = snap.stack.get(i);
            boolean top = (i == snap.stack.size() - 1);
            float along = -width / 2f + i * step + CARD_W / 2f;
            Vector3 w = rotateY(along, 0f, orbit);
            Vector3 p = cam.project(new Vector3(w.x, STACK_Y + (top ? 0.34f : 0f) - 0.8f, w.z));
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

        boolean waiting = okOn || cancelOn;

        // What the main button DOES, said on the button.
        //
        // Forge gives it a name of its own whenever it is really asking something - "Attack",
        // "Auto", "Yes". A plain "OK" means it is not asking, only offering to move on, and then
        // what moving on means depends entirely on the stack: with something on it, a pass lets
        // THAT resolve and leaves you exactly where you are; with nothing on it, a pass is the
        // end of the step. The owner asked to be able to tell those two apart - "I want to be
        // clear if all I am allowing is the current iteration of stack to resolve".
        boolean named = !okLabel.isEmpty() && !"OK".equalsIgnoreCase(okLabel);
        String topOfStack = snap.stack.isEmpty() ? ""
            : shortName(snap.stack.get(snap.stack.size() - 1).name);
        String mainLabel, mainHint;
        if (named) {
            mainLabel = okLabel;
            mainHint = "SPACE";
        } else if (!topOfStack.isEmpty()) {
            mainLabel = "Let it resolve";
            mainHint = "SPACE - " + topOfStack;
        } else {
            mainLabel = "Next phase";
            mainHint = "SPACE  or  N";
        }
        // Forge's cancel. During ordinary priority it is "End Turn", which does not mean "finish
        // this step": it arms an auto-pass for everything left in the turn. That is what cost a
        // whole turn on 2026-10-21, so the button says so rather than looking like a tidy way to
        // move on.
        boolean endsTurn = "End Turn".equalsIgnoreCase(cancelLabel);
        float bw = Math.min(w - 60f, 1120f), x = (w - bw) / 2f, y = 104f;
        float btnW = 190f, btnH = 50f, gap = 14f;

        // Measure the text and size the box to it. Forge's prompt is several lines - priority,
        // turn, phase, stack - and a fixed height cut the last one off below the box. Measuring
        // also means a one-line prompt gets a small bar instead of a mostly-empty one.
        float textW = bw - 60f - (okOn ? btnW + gap : 0f) - (cancelOn ? btnW + gap : 0f);
        com.badlogic.gdx.graphics.g2d.GlyphLayout gl = new com.badlogic.gdx.graphics.g2d.GlyphLayout();
        gl.setText(fontBig, msg, com.badlogic.gdx.graphics.Color.WHITE, textW, com.badlogic.gdx.utils.Align.left, true);
        float bh = Math.max(btnH + 28f, gl.height + 36f);

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
        float bx = x + bw - 20f - btnW;
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
            okBtn[0] = bx; okBtn[1] = by; okBtn[2] = btnW + 40f; okBtn[3] = btnH;
            boolean hot = inRect(okBtn, mx, my);
            // Green while it answers a question, blue while it only moves the game on, so the
            // two are not the same colour as well as in the same place.
            if (named) shapes.setColor(hot ? 0.24f : 0.16f, hot ? 0.64f : 0.48f, hot ? 0.40f : 0.31f, 1f);
            else shapes.setColor(hot ? 0.26f : 0.17f, hot ? 0.42f : 0.28f, hot ? 0.62f : 0.44f, 1f);
            shapes.rect(bx, by, btnW + 40f, btnH);
        }
        shapes.end();
        ui.begin();

        fontBig.setColor(waiting ? new Color(1f, 0.93f, 0.74f, 1f) : new Color(0.70f, 0.82f, 0.92f, 1f));
        fontBig.draw(ui, msg, x + 22f, y + bh - 18f, textW, -1, true);
        if (okOn) {
            fontBig.setColor(named ? new Color(0.86f, 1f, 0.90f, 1f) : new Color(0.84f, 0.90f, 1f, 1f));
            fontBig.draw(ui, mainLabel, okBtn[0], okBtn[1] + 34f, btnW + 40f, 1, false);
            font.setColor(named ? new Color(0.60f, 0.86f, 0.70f, 1f) : new Color(0.62f, 0.74f, 0.92f, 1f));
            font.draw(ui, mainHint, okBtn[0], okBtn[1] + 14f, btnW + 40f, 1, false);
        }
        if (cancelOn) {
            fontBig.setColor(1f, 0.86f, 0.82f, 1f);
            fontBig.draw(ui, endsTurn ? "End turn" : cancelLabel, cancelBtn[0], cancelBtn[1] + 34f, btnW, 1, false);
            font.setColor(0.88f, 0.64f, 0.60f, 1f);
            font.draw(ui, endsTurn ? "X - passes the rest" : "X", cancelBtn[0], cancelBtn[1] + 14f, btnW, 1, false);
        }
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
        if (ask == null) return;
        float bw = 760f, bh = 150f, x = (w - bw) / 2f, y = h * 0.60f;

        ui.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        com.badlogic.gdx.math.Matrix4 flat = new com.badlogic.gdx.math.Matrix4().setToOrtho2D(0, 0, w, h);
        shapes.setProjectionMatrix(flat);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.10f, 0.07f, 0.02f, 0.96f);
        shapes.rect(x, y, bw, bh);
        shapes.setColor(0.98f, 0.76f, 0.24f, 1f);
        shapes.rect(x, y + bh - 5f, bw, 5f);
        shapes.rect(x, y, bw, 2f);
        shapes.end();
        ui.begin();

        fontBig.setColor(1f, 0.90f, 0.55f, 1f);
        String head = ask.cardName.isEmpty() ? "Forge is asking" : ask.cardName;
        fontBig.draw(ui, head, x + 24f, y + bh - 24f);
        font.setColor(0.95f, 0.95f, 0.92f, 1f);
        font.draw(ui, ask.question, x + 24f, y + bh - 58f, bw - 48f, -1, true);
        font.setColor(0.70f, 0.95f, 0.70f, 1f);
        font.draw(ui, "SPACE = " + ask.yesLabel, x + 24f, y + 36f);
        font.setColor(0.95f, 0.72f, 0.66f, 1f);
        font.draw(ui, "X = " + ask.noLabel, x + 240f, y + 36f);
        font.setColor(0.62f, 0.66f, 0.72f, 1f);
        font.draw(ui, "H hands this question back to Forge's window", x + 24f, y + 16f);
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
                String state = s.lost ? "out" : s.hasPriority ? "deciding" : s.passed ? "passed" : "waiting";
                if (s.lost) font.setColor(0.42f, 0.38f, 0.38f, 1f);
                else if (s.hasPriority) font.setColor(0.70f, 0.95f, 1f, 1f);
                else if (s.passed) font.setColor(0.48f, 0.62f, 0.72f, 1f);
                else font.setColor(0.80f, 0.86f, 0.92f, 1f);
                font.draw(ui, shortName(s.name), PP_X + 26f * PP_SCALE, y + PB_ROW - 4f * PP_SCALE);
                gl.setText(font, state);
                font.draw(ui, state, PP_X + PP_W - 10f * PP_SCALE - gl.width, y + PB_ROW - 4f * PP_SCALE);
            }
        }

        font.setColor(followTurn ? new Color(0.72f, 0.92f, 1f, 1f) : new Color(0.48f, 0.60f, 0.70f, 1f));
        font.draw(ui, "follow the turn", PP_X + 30f * PP_SCALE, bottom + 23f * PP_SCALE);

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
            for (int k = 0; k < 3; k++) {
                Vector3 world = rotateY(5.3f, SEAT_DIST + 1.7f - k * 1.45f, angle);
                // On top of the pile, so a tall library does not hide its own count.
                Vector3 p = cam.project(new Vector3(world.x, pileTop(i, k) + 0.08f, world.z));
                if (p.z > 1f) continue;
                font.setColor(zc);
                font.draw(ui, names[k], p.x - 20, p.y + 6);
            }
            // Whose pads these are. Three unlabelled trays beside three other players' unlabelled
            // trays is the thing that was being misread, so the owner's name goes over the stack,
            // in the same colour as that seat's frame.
            Vector3 ow = rotateY(5.3f, SEAT_DIST + 1.7f + 1.0f, angle);
            Vector3 op = cam.project(new Vector3(ow.x, 0.1f, ow.z));
            if (op.z <= 1f) {
                String who = shortName(s.name).toUpperCase() + (s.isYou ? "  (YOU)" : "");
                font.setColor(zc);
                font.draw(ui, who, op.x - 20, op.y + 6);
            }
        }
        // The board itself, named at the edge nearest the middle of the table: the frame says
        // whose turn it is, this says whose half of the table you are looking at. Skipped for
        // your own seat, which is the one you are sitting behind and never in doubt.
        for (int i = 0; i < n; i++) {
            Snapshot.SeatV s = snap.seats.get(i);
            if (s.isYou) continue;
            Vector3 world = rotateY(-EDGE_X + 0.7f, EDGE_NEAR + 0.36f, seatAngle(i, n));
            Vector3 p = cam.project(new Vector3(world.x, 0.1f, world.z));
            if (p.z > 1f) continue;
            font.setColor(zoneLabelColour(s));
            font.draw(ui, shortName(s.name).toUpperCase()
                + (s.active ? "  -  THEIR TURN" : s.hasPriority ? "  -  RESPONDING" : ""), p.x, p.y + 6);
        }
        // The command zone: named just below its pad, with the tax the next cast will cost.
        if (!commanderGame()) return;
        for (int i = 0; i < n; i++) {
            Snapshot.SeatV s = snap.seats.get(i);
            Vector3 world = rotateY(COMMAND_X, COMMAND_Z + CARD_H / 2f + 0.35f, seatAngle(i, n));
            Vector3 p = cam.project(new Vector3(world.x, 0.1f, world.z));
            if (p.z > 1f) continue;
            int casts = 0;
            for (Snapshot.CardV c : s.command) casts = Math.max(casts, c.commanderCasts);
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
        boolean waitingOnYou = you != null && you.hasPriority && !you.active && !snap.gameOver;
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

    /** Targeting mode's instruction line, top centre, in the arc's violet. Inside the label pass. */
    void drawTargetBanner(int w, int h) {
        String line, sub;
        Color c;
        if (targeting != null && targeting.wired) {
            // Forge's own words for what it wants, under a plain heading.
            line = "Choose targets";
            sub = targeting.what + "   -   click a glowing card or a player   -   ESC cancels";
            c = new Color(0.80f, 0.62f, 1f, 1f);
        } else if (manaPlan != null && manaPlan.wired) {
            line = "Pay mana";
            sub = manaPlan.cost + "   -   click a glowing land or mana source   -   ESC cancels";
            c = new Color(0.60f, 0.92f, 0.62f, 1f);
        } else if (targeting != null) {
            line = "Choose " + (targeting.max == 1 ? "a " : targeting.max + " ") + targeting.what
                + "   (" + targeting.picked.size() + " of " + targeting.max + ")";
            sub = "click a glowing card   -   ESC cancels";
            c = new Color(0.80f, 0.62f, 1f, 1f);
        } else if (combatPlan != null && !combatPlan.blocking) {
            line = "Declare attackers   (" + combatPlan.attackSeat.size() + " attacking)";
            sub = "drag a glowing creature onto a player   -   drop it back to take it off   -   SPACE confirms   ESC cancels";
            c = new Color(1f, 0.55f, 0.40f, 1f);
        } else if (combatPlan != null) {
            line = "Declare blockers   (" + combatPlan.blockOn.size() + " blocking)";
            sub = "drag a glowing creature onto an attacker   -   SPACE confirms   ESC cancels";
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
     * The tracker in the middle of the table: each player's commander, their life, and the
     * commander damage they have taken. All of it is public knowledge, which is why it can sit in
     * the open where everyone's view of it is the same.
     *
     * Laid out as a readout rather than a list (the owner, 2026-10-21: "arrange the numbers on it
     * like a screen, think like a digital clock"). The portrait is on the left, the life total is
     * the one big number, and the small fields sit under it in fixed columns so they stay in the
     * same place as they change - a number that moves when it changes cannot be read at a glance.
     * Drawn in screen space, not onto the panel, so every seat's readout faces the camera
     * wherever it happens to be.
     */
    void drawCentreTracker() {
        int n = snap.seats.size();
        for (int i = 0; i < n; i++) {
            Snapshot.SeatV s = snap.seats.get(i);
            float angle = seatAngle(i, n);
            Vector3 w = rotateY(0f, MONITOR_DIST, angle);
            Vector3 p = cam.project(new Vector3(w.x, 0.62f, w.z));
            if (p.z > 1f) continue;                       // behind the camera

            // The commander's picture, if Forge has it cached. A missing one just leaves the
            // readout where it is rather than shifting everything left.
            final float portW = 40f, portH = 56f;
            float left = p.x - 54f;
            Snapshot.CardV cmd = commanderOf(s);
            com.badlogic.gdx.graphics.Texture tex =
                (art == null || cmd == null || !art.hasPicture(cmd)) ? null : art.get(cmd);
            if (tex != null) {
                ui.setColor(1f, 1f, 1f, s.lost ? 0.35f : 1f);
                ui.draw(tex, left, p.y - 18f, portW, portH);
                ui.setColor(1f, 1f, 1f, 1f);
            }

            float col = left + portW + 8f;                // everything else lines up here
            Color tint = s.lost ? new Color(0.50f, 0.44f, 0.44f, 1f)
                    : s.active ? new Color(1f, 0.85f, 0.35f, 1f)
                    : s.isYou ? new Color(0.65f, 0.95f, 1f, 1f)
                    : new Color(0.80f, 0.88f, 0.95f, 1f);

            font.setColor(0.52f, 0.66f, 0.78f, 1f);
            font.draw(ui, shortName(s.name), col, p.y + 36f);

            // The life total, big. The one number you look for.
            fontBig.getData().setScale(2.3f);
            fontBig.setColor(tint);
            fontBig.draw(ui, s.lost ? "OUT" : String.valueOf(s.life), col, p.y + 26f);
            fontBig.getData().setScale(1.6f);

            // Fixed columns under it, so a changing number never moves the ones beside it.
            font.setColor(0.62f, 0.74f, 0.84f, 1f);
            font.draw(ui, "LIB", col, p.y - 2f);
            font.draw(ui, "GY", col + 46f, p.y - 2f);
            font.draw(ui, "EX", col + 84f, p.y - 2f);
            font.setColor(0.82f, 0.90f, 0.96f, 1f);
            font.draw(ui, String.valueOf(s.librarySize), col, p.y - 15f);
            font.draw(ui, String.valueOf(s.graveyardSize), col + 46f, p.y - 15f);
            font.draw(ui, String.valueOf(s.exileSize), col + 84f, p.y - 15f);

            float y = p.y - 30f;
            if (s.poison > 0) {
                font.setColor(0.60f, 0.85f, 0.45f, 1f);
                font.draw(ui, "POISON " + s.poison, col, y);
                y -= 13f;
            }
            // Commander damage: the number that quietly kills you, so it stays on the readout.
            font.setColor(0.78f, 0.56f, 0.50f, 1f);
            int shown = 0;
            for (Snapshot.CmdDmg d : s.commanderDamage) {
                if (shown++ >= 2) break;                  // two is enough to see a threat building
                font.draw(ui, d.amount + " from " + shortName(d.from), col, y);
                y -= 13f;
            }
        }
    }

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
