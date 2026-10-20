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

/** Keyboard and mouse: clicks on cards, seats, pads and buttons, camera keys, and picking what is under the mouse. See TableBase. */
abstract class TableInput extends TableHud {

    /**
     * Demo trigger (S): opens the search panel over everything currently in the snapshot, so the
     * animation and the grid can be exercised before stage 3 has anything real to search.
     */
    void openDemoSearch() {
        List<Snapshot.CardV> all = new ArrayList<>(snap.hand);
        for (Snapshot.SeatV s : snap.seats) {
            all.addAll(s.creatures);
            all.addAll(s.others);
            all.addAll(s.lands);
        }
        while (all.size() < 24 && !all.isEmpty()) all.addAll(new ArrayList<>(all));   // pad the grid
        search.open("Search your library", "Choose up to two cards.", all, 0, 2,
            chosen -> System.out.println("UI3D: search chose " + chosen.size() + " card(s)"));
    }

    void handleInput() {
        // While the bug dialog is open the 3D window reads nothing. libGDX polls GLFW key state
        // instead of consuming events, so without this, typing a report also passed priority and
        // ended turns underneath it.
        if (bug.isAsking()) return;
        // The cog is always live, so a bug can be reported whatever else is happening. It opens
        // a proper text dialog rather than an in-window field, so there is no typing state here.
        if (clickedCog()) { bug.ask(snap); return; }
        // A search owns the keyboard and mouse while it is up, otherwise ESC would both close the
        // panel and release the camera, and a click meant for a card would also move the view.
        if (search != null && search.isOpen()) return;
        if (Gdx.input.isKeyJustPressed(Input.Keys.S)) { openDemoSearch(); return; }
        // -Dforge.ui3d.openPile=<seat>,graveyard|exile: opens that pile once, for screenshot checks.
        if (OPEN_PILE != null && !pileOpened && frameNo > 30 && snap.seats.size() > 0) {
            pileOpened = true;
            String[] p = OPEN_PILE.split(",");
            Snapshot.SeatV s = snap.seats.get(Integer.parseInt(p[0].trim()) % snap.seats.size());
            List<Snapshot.CardV> pile = p.length > 1 && p[1].trim().startsWith("e") ? s.exile : s.graveyard;
            search.open(s.name + "'s " + (pile == s.exile ? "exile" : "graveyard") + " (" + pile.size() + ")",
                "Most recent first. Close when you have read it.", new ArrayList<>(pile), 0, 0, chosen -> { });
            return;
        }
        // SPACE passes priority / confirms, from here rather than from Forge's window. Only when
        // a game is actually running: in the demo nothing is installed and SPACE still steps the
        // phase, so the two never contend for the key.
        // A yes/no the 3D window owns takes the keys first: Forge is no longer showing this
        // question, so SPACE must answer it rather than pass priority underneath it.
        if (Gui3D.pendingAsk() != null) {
            if (Gdx.input.isKeyJustPressed(Input.Keys.SPACE)) Gui3D.answerAsk(true);
            if (Gdx.input.isKeyJustPressed(Input.Keys.X)) Gui3D.answerAsk(false);
            return;
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.SPACE) && Gui3D.active()) {
            if (!Gui3D.pressOk()) {
                System.out.println("UI3D: nothing to confirm right now");
            }
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.X) && Gui3D.active()) Gui3D.pressCancel();
        // Left-click a card Forge says is playable right now. The click is only sent for cards
        // already in the selectable set, so the 3D side is choosing among Forge's own legal
        // options and can never offer a play the rules disallow.
        // The prompt bar's buttons take the click before the board does, or pressing OK would
        // also try to play whatever card happens to be behind it.
        if (Gui3D.active() && Gui3D.pendingAsk() == null) {
            float mx = Gdx.input.getX(), my = Gdx.graphics.getHeight() - Gdx.input.getY();
            if (inRect(okBtn, mx, my) || inRect(cancelBtn, mx, my)) {
                if (clickedCard()) {
                    if (inRect(okBtn, mx, my)) Gui3D.pressOk(); else Gui3D.pressCancel();
                }
                return;
            }
        }
        boolean boardClick = clickedCard();
        // A click on an empty graveyard or exile pad browses that pile (any seat's: both are public).
        if (boardClick && hovered == null && openPileUnderMouse()) return;
        if (Gdx.input.isKeyJustPressed(Input.Keys.L)) showLog = !showLog;
        if (boardClick && Gui3D.active()) {
            if (hovered != null) {
                if (!Gui3D.clickCard(hovered.card.id)) {
                    System.out.println("UI3D: " + hovered.card.name + " is not selectable right now");
                }
            } else if (hoveredSeat >= 0) {
                // Picking a player: who starts, or a seat as a target.
                if (!Gui3D.clickPlayer(hoveredSeat)) {
                    System.out.println("UI3D: could not select that player");
                }
            }
        }
        // H gives the decision the game is waiting on back to Forge's window. Harmless when
        // nothing has been taken over, which is the case until step 3.
        if (Gdx.input.isKeyJustPressed(Input.Keys.H)) {
            Handoff.handBack();
            System.out.println("UI3D: handing the current decision back to Forge");
        }
        if (clickedFollowBox()) { followTurn = !followTurn; lastActiveSeat = activeSeat(); }
        if (Gdx.input.isKeyJustPressed(Input.Keys.M) && cues != null) cues.toggleMute();
        if (Gdx.input.isKeyJustPressed(Input.Keys.TAB)) {
            manualOverride = true;
            manualValue = lift < 0.5f;       // toggle to whichever pose we are not in
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) manualOverride = false;

        int n = snap.seats.size();
        if (n == 0) return;
        // Right-drag orbits freely round the table (left button is kept for clicking cards in
        // stage 3). It takes over from following the turn, like the arrow keys do.
        // Middle button: grab the table and move it. The drag is converted into the camera's own
        // left/right and forward directions, so it always follows the mouse whichever way round
        // the table you are sitting, and it scales with eye height - at the overhead pose a pixel
        // covers far more table than it does seated, and a fixed rate feels wrong at both.
        if (Gdx.input.isButtonPressed(Input.Buttons.MIDDLE)) {
            int dx = Gdx.input.getDeltaX(), dy = Gdx.input.getDeltaY();
            if (dx != 0 || dy != 0) {
                float rate = 0.004f * Math.max(1f, cam.position.y);
                Vector3 right = rotateY(1f, 0f, orbit);
                Vector3 fwd = rotateY(0f, -1f, orbit);
                panX -= right.x * dx * rate;
                panZ -= right.z * dx * rate;
                panX += fwd.x * dy * rate;
                panZ += fwd.z * dy * rate;
                float d = (float) Math.sqrt(panX * panX + panZ * panZ);
                if (d > PAN_LIMIT) { panX *= PAN_LIMIT / d; panZ *= PAN_LIMIT / d; }
            }
        }
        if (Gdx.input.isButtonPressed(Input.Buttons.RIGHT)) {
            int dx = Gdx.input.getDeltaX();
            if (dx != 0) {
                followTurn = false;
                orbit -= dx * ORBIT_PER_PIXEL;
                orbitTarget = orbit;
            }
        }
        // Seats are laid out anticlockwise, so stepping +1 moves the camera to the LEFT on screen.
        // The keys are deliberately matched to the direction you see, not to the index order.
        if (Gdx.input.isKeyJustPressed(Input.Keys.LEFT))  step(+1, n);
        if (Gdx.input.isKeyJustPressed(Input.Keys.RIGHT)) step(-1, n);
        // DOWN (or HOME) is the way back to your own seat, at the default zoom.
        if (Gdx.input.isKeyJustPressed(Input.Keys.DOWN) || Gdx.input.isKeyJustPressed(Input.Keys.HOME)) {
            zoom = 1f;
            panX = panZ = 0f;
            setViewSeat(snap.youIndex(), n);
        }
    }

    /**
     * If the mouse is over a graveyard or exile pad, opens that pile in the search panel in browse
     * mode (nothing to pick) and returns true. The pads are scenery, not cards, so this is a ray
     * hit on the table plane, turned into the seat's own frame and checked against the two pads
     * placeZones() lays out (k = 1 graveyard, k = 2 exile; k = 0 is the library, which stays shut).
     */
    boolean openPileUnderMouse() {
        if (search == null || snap.seats.isEmpty()) return false;
        Ray ray = cam.getPickRay(Gdx.input.getX(), Gdx.input.getY());
        if (Math.abs(ray.direction.y) < 1e-4f) return false;
        float t = -ray.origin.y / ray.direction.y;
        if (t <= 0f) return false;
        float hx = ray.origin.x + ray.direction.x * t, hz = ray.origin.z + ray.direction.z * t;
        int n = snap.seats.size();
        for (int i = 0; i < n; i++) {
            Vector3 local = rotateY(hx, hz, -seatAngle(i, n));    // into seat i's frame
            for (int k = 1; k <= 2; k++) {
                float cz = SEAT_DIST + 1.7f - k * 1.45f;
                if (Math.abs(local.x - 5.3f) > CARD_W * 0.5f || Math.abs(local.z - cz) > CARD_H * 0.5f) continue;
                Snapshot.SeatV s = snap.seats.get(i);
                List<Snapshot.CardV> pile = k == 1 ? s.graveyard : s.exile;
                String what = k == 1 ? "graveyard" : "exile";
                String title = s.name + "'s " + what + " (" + pile.size() + ")";
                search.open(title, pile.isEmpty() ? "Nothing here." : "Most recent first. Close when you have read it.",
                    new ArrayList<>(pile), 0, 0, chosen -> { });
                return true;
            }
        }
        return false;
    }

    /** True on the frame the left button goes down on the bug cog. */
    boolean clickedCog() {
        boolean down = Gdx.input.isButtonPressed(Input.Buttons.LEFT);
        boolean justDown = down && !leftWasDownCog;
        leftWasDownCog = down;
        return justDown && bug.overCog(Gdx.input.getX(), Gdx.graphics.getHeight() - Gdx.input.getY());
    }

    /** True on the frame the left button goes down anywhere except the phase panel. */
    boolean clickedCard() {
        boolean down = Gdx.input.isButtonPressed(Input.Buttons.LEFT);
        boolean justDown = down && !leftWasDownBoard;
        leftWasDownBoard = down;
        if (!justDown) return false;
        float mx = Gdx.input.getX(), my = Gdx.graphics.getHeight() - Gdx.input.getY();
        // The panel owns its own clicks; a click on the tickbox must not also cast a card behind it.
        return !(mx >= PP_X && mx <= PP_X + PP_W && my >= Gdx.graphics.getHeight() - 14f - panelHeight());
    }

    /** True on the frame the left button goes down inside the follow-turn tickbox. */
    boolean clickedFollowBox() {
        boolean down = Gdx.input.isButtonPressed(Input.Buttons.LEFT);
        boolean justDown = down && !leftWasDown;
        leftWasDown = down;
        if (!justDown) return false;
        float mx = Gdx.input.getX(), my = Gdx.graphics.getHeight() - Gdx.input.getY();
        return mx >= followBox[0] && mx <= followBox[0] + followBox[2]
            && my >= followBox[1] && my <= followBox[1] + followBox[3];
    }

    /** Ray-pick the card under the mouse; nearest hit wins. */
    void pick() {
        hovered = null;
        hoveredSeat = -1;
        if (search != null && search.isOpen()) return;
        Ray ray = cam.getPickRay(Gdx.input.getX(), Gdx.input.getY());
        float best = Float.MAX_VALUE;
        Vector3 hit = new Vector3();
        for (Drawn d : drawn) {
            if (!com.badlogic.gdx.math.Intersector.intersectRayBounds(ray, d.bounds, hit)) continue;
            float dist = hit.dst2(cam.position);
            if (dist < best) { best = dist; hovered = d; }
        }
        // -Dforge.ui3d.hover=<card name>: with screenshot mode, checks the hover panel without
        // anyone moving a mouse over the window.
        if (hovered == null && FORCE_HOVER != null) {
            for (Drawn d : drawn) if (FORCE_HOVER.equalsIgnoreCase(d.card.name)) { hovered = d; break; }
        }
        // Seats are only considered when no card is under the cursor, so a card lying over a
        // monitor still wins - the card is the more specific thing the player meant.
        if (hovered != null) return;
        for (int i = 0; i < seatPick.size(); i++) {
            if (!com.badlogic.gdx.math.Intersector.intersectRayBounds(ray, seatPick.get(i), hit)) continue;
            float dist = hit.dst2(cam.position);
            if (dist < best) { best = dist; hoveredSeat = i; }
        }
    }
}
