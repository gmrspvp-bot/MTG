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
 * Placement: layout() rebuilds where every card, pad, band and prop goes each frame, plus the
 * ModelInstance pool that makes that cheap and the table's surround. See TableBase for the chain.
 */
abstract class TableLayout extends TableCamera {

    /** A ModelInstance of `m` for this frame only. */
    ModelInstance inst(Model m) {
        List<ModelInstance> list = pool.computeIfAbsent(m, k -> new ArrayList<>());
        int[] used = poolUsed.computeIfAbsent(m, k -> new int[1]);
        ModelInstance mi;
        if (used[0] < list.size()) {
            mi = list.get(used[0]);
            mi.transform.idt();
        } else {
            mi = new ModelInstance(m);
            list.add(mi);
        }
        used[0]++;
        return mi;
    }

    /** Called once a frame, before layout(): every pooled instance is free again. */
    void poolReset() {
        for (int[] u : poolUsed.values()) u[0] = 0;
    }

    /**
     * The table as furniture rather than a slab: generated felt on top (see Felt), a dark padded
     * rail round the edge, and a floor with a dim pool of light under the table so it stands in a
     * room. The rail sits OUTSIDE the playing area, so nothing laid out on the table moves.
     */
    void createSurround(int attrs) {
        com.badlogic.gdx.graphics.Pixmap fp = Felt.felt(1024);
        feltTex = new com.badlogic.gdx.graphics.Texture(fp, true);
        fp.dispose();
        feltTex.setFilter(com.badlogic.gdx.graphics.Texture.TextureFilter.MipMapLinearLinear,
            com.badlogic.gdx.graphics.Texture.TextureFilter.Linear);
        try {
            float max = com.badlogic.gdx.graphics.Texture.getMaxAnisotropicFilterLevel();
            if (max > 1f) feltTex.setAnisotropicFilter(Math.min(8f, max));   // seen at a grazing angle
        } catch (Throwable ignored) {
            // softer at a distance, that is all
        }
        int texAttrs = Usage.Position | Usage.Normal | Usage.TextureCoordinates;
        float t = TABLE_HALF;
        feltModel = builder.createRect(-t, 0f, t, t, 0f, t, t, 0f, -t, -t, 0f, -t, 0f, 1f, 0f,
            new Material(com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute.createDiffuse(feltTex)), texAttrs);
        feltInst = new ModelInstance(feltModel);
        feltInst.transform.setToTranslation(0f, -0.015f, 0f);     // just above the body's top (-0.02)

        com.badlogic.gdx.graphics.Pixmap flp = Felt.floor(512);
        floorTex = new com.badlogic.gdx.graphics.Texture(flp);
        flp.dispose();
        floorTex.setFilter(com.badlogic.gdx.graphics.Texture.TextureFilter.Linear,
            com.badlogic.gdx.graphics.Texture.TextureFilter.Linear);
        float f = 70f;
        floorModel = builder.createRect(-f, 0f, f, f, 0f, f, f, 0f, -f, -f, 0f, -f, 0f, 1f, 0f,
            new Material(com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute.createDiffuse(floorTex)), texAttrs);
        floorInst = new ModelInstance(floorModel);
        floorInst.transform.setToTranslation(0f, -3.2f, 0f);

        // One rail model, as long as a whole side including both corners, turned for each side.
        float len = 2f * (t + RAIL_W);
        railModel = builder.createBox(len, RAIL_H, RAIL_W,
            new Material(ColorAttribute.createDiffuse(new Color(0.11f, 0.075f, 0.06f, 1f))), attrs);
        for (int side = 0; side < 4; side++) {
            ModelInstance r = new ModelInstance(railModel);
            Vector3 c = rotateY(0f, t + RAIL_W / 2f, (float) (side * Math.PI / 2));
            r.transform.setToTranslation(c.x, -0.02f + RAIL_H / 2f, c.z)
                .rotate(Vector3.Y, side * -90f);
            railInsts.add(r);
        }
    }

    /**
     * Rebuilds the card placement every frame. At these counts that is far cheaper than tracking
     * what changed, and it means the view can never drift out of step with the snapshot.
     */
    void layout() {
        drawn.clear();
        seatPads.clear();
        props.clear();
        seatPick.clear();
        if (snap.seats.isEmpty()) return;

        int n = snap.seats.size();
        // Attachments whose host is on the battlefield leave their own row and are laid under the
        // host instead (placeAttachments), so first learn which ids are on the battlefield.
        hostTargets.clear();
        attachedHere.clear();
        java.util.Set<Integer> onField = new java.util.HashSet<>();
        for (Snapshot.SeatV seat : snap.seats) {
            for (Snapshot.CardV c : seat.creatures) onField.add(c.id);
            for (Snapshot.CardV c : seat.others) onField.add(c.id);
            for (Snapshot.CardV c : seat.lands) onField.add(c.id);
        }
        for (Snapshot.SeatV seat : snap.seats) {
            for (List<Snapshot.CardV> row : java.util.Arrays.asList(seat.creatures, seat.others, seat.lands)) {
                for (Snapshot.CardV c : row) {
                    if (c.attachedToId >= 0 && c.attachedToId != c.id && onField.contains(c.attachedToId)) {
                        attachedHere.computeIfAbsent(c.attachedToId, k -> new ArrayList<>()).add(new Object[] { c, seat });
                    }
                }
            }
        }
        for (int i = 0; i < n; i++) {
            Snapshot.SeatV seat = snap.seats.get(i);
            // Seats are fixed in the world. The camera orbits to whichever one we are looking from,
            // so a player's board never moves out from under them when the view changes.
            float angle = seatAngle(i, n);

            ModelInstance pad = inst(seat.lost ? seatLostModel
                : seat.hasPriority ? seatPriorityModel : seatModel);
            Vector3 c = seatPos(angle, SEAT_DIST);
            pad.transform.setToTranslation(c.x, -0.01f, c.z).rotate(Vector3.Y, (float) Math.toDegrees(-angle));
            seatPads.add(pad);

            // Three bands per seat, running from the middle of the table outwards: creatures,
            // then other permanents (artifacts, enchantments, planeswalkers), then lands nearest
            // their owner. Each band gets a faint slab so the grouping reads without labelling it.
            placeBand(seat.creatures, angle, ROW_CREATURES, bandCreature);
            placeBand(seat.others, angle, ROW_OTHERS, bandOther);
            placeBand(seat.lands, angle, ROW_LANDS, bandLand);
            placeRow(seat, seat.creatures, angle, ROW_CREATURES);
            placeRow(seat, seat.others, angle, ROW_OTHERS);
            placeRow(seat, seat.lands, angle, ROW_LANDS);
        }
        placeAttachments();
        placeCommandZone();
        placeHand();
        placeOpponentHands();
        placeZones();
        placeMonitors();
        placeStack();
        placeDying();
    }

    // ---- zone-change animations ------------------------------------------------------------------

    /** A card on its way into a graveyard or exile pile: drawn sliding onto the pad for a moment. */
    static final class Dying {
        final Snapshot.CardV card;
        final int seat, pad;      // pad: 1 graveyard, 2 exile (placeZones' k)
        float age;
        Dying(Snapshot.CardV card, int seat, int pad) { this.card = card; this.seat = seat; this.pad = pad; }
    }

    final java.util.Map<Integer, Dying> dying = new java.util.HashMap<>();
    /** Ids drawn on the battlefield or the stack last frame. */
    java.util.Set<Integer> lastShown = new java.util.HashSet<>();
    /** False until the first real layout, so opening the window mid-game animates nothing. */
    boolean zonesPrimed;
    static final float DYING_TIME = 0.6f;

    /**
     * Cards that just left the battlefield or the stack for a graveyard or exile. Their pose from
     * last frame still exists at this point (poses are only forgotten after layout), so adding
     * them again with the pad as the target makes them slide from where they were onto it, by the
     * same easing everything else uses. A token ceases to exist instead, so it simply goes.
     */
    void placeDying() {
        int n = snap.seats.size();
        java.util.Set<Integer> shown = new java.util.HashSet<>();
        for (Snapshot.SeatV s : snap.seats) {
            for (Snapshot.CardV c : s.creatures) shown.add(c.id);
            for (Snapshot.CardV c : s.others) shown.add(c.id);
            for (Snapshot.CardV c : s.lands) shown.add(c.id);
        }
        for (Snapshot.CardV c : snap.stack) shown.add(c.id);
        if (zonesPrimed) {
            for (int id : lastShown) {
                if (shown.contains(id) || dying.containsKey(id) || !poses.containsKey(id)) continue;
                for (int i = 0; i < n; i++) {
                    Snapshot.SeatV s = snap.seats.get(i);
                    for (Snapshot.CardV c : s.graveyard) if (c.id == id) dying.put(id, new Dying(c, i, 1));
                    for (Snapshot.CardV c : s.exile) if (c.id == id) dying.put(id, new Dying(c, i, 2));
                }
            }
        }
        lastShown = shown;
        if (n > 0) zonesPrimed = true;

        for (java.util.Iterator<Dying> it = dying.values().iterator(); it.hasNext(); ) {
            Dying d = it.next();
            d.age += frameDt;
            if (d.age > DYING_TIME || d.seat >= n || shown.contains(d.card.id)) { it.remove(); continue; }
            float angle = seatAngle(d.seat, n);
            Vector3 w = rotateY(5.3f, SEAT_DIST + 1.7f - d.pad * 1.45f, angle);
            add(d.card, snap.seats.get(d.seat), w.x, 0.05f, w.z, (float) Math.toDegrees(-angle), 0f);
        }
    }

    /**
     * A card that has just been drawn starts at your library pad, so it is seen coming off the
     * deck into the hand rather than appearing there. Called for hand cards with no pose yet.
     */
    void seedFromLibrary(Snapshot.CardV c) {
        if (!zonesPrimed || poses.containsKey(c.id) || snap.seats.isEmpty()) return;
        float angle = seatAngle(snap.youIndex(), snap.seats.size());
        Vector3 lib = rotateY(5.3f, SEAT_DIST + 1.7f, angle);
        Pose p = new Pose();
        p.x = lib.x; p.y = 0.05f; p.z = lib.z;
        p.yaw = (float) Math.toDegrees(-angle);
        p.tilt = 0f;
        p.seen = frameNo - 1;
        poses.put(c.id, p);
    }

    /**
     * The stack, floating above the middle of the table as a bar that fills left to right in the
     * order things were cast (Snapshot.stack is bottom-first for exactly this reason).
     *
     * It is laid out along the VIEWED seat's local X and turned to face that seat, so the bar is
     * always broadside to the camera and reads in the same direction from every seat. The item
     * about to resolve - the top of the stack - is the rightmost and sits slightly higher, which
     * is the one piece of information the bar has to convey at a glance.
     */
    void placeStack() {
        stackAt.clear();
        if (snap.stack.isEmpty()) return;
        float step = CARD_W * STACK_SCALE * 1.18f;
        float width = snap.stack.size() * step - (step - CARD_W * STACK_SCALE);
        float yaw = (float) Math.toDegrees(-orbit);

        // A panel behind the cards. Floating bare cards read as loose cards; a frame says "this
        // is the stack", which is the thing you most need to notice is not empty.
        Vector3 c0 = rotateY(0f, 0f, orbit);
        ModelInstance frame = new ModelInstance(stackFrameModel);
        frame.transform.setToTranslation(c0.x, STACK_Y + 0.18f, c0.z)
            .rotate(Vector3.Y, yaw)
            .rotate(Vector3.X, 90f)
            .scale((width + 0.9f) / STACK_FRAME_UNIT, 1f, 1f);
        props.add(frame);

        for (int i = 0; i < snap.stack.size(); i++) {
            Snapshot.CardV c = snap.stack.get(i);
            boolean top = (i == snap.stack.size() - 1);
            float along = -width / 2f + i * step + CARD_W * STACK_SCALE / 2f;
            Vector3 w = rotateY(along, 0f, orbit);
            add(c, null, w.x, STACK_Y + (top ? 0.34f : 0f), w.z, yaw, 90f, STACK_SCALE);
            // Where a target arc starts: the bottom edge of the upright card. Kept per stack index,
            // because an ability shares its id with its source, so poses cannot tell them apart.
            stackAt.add(new float[] { w.x, STACK_Y + (top ? 0.34f : 0f) - CARD_H * STACK_SCALE / 2f, w.z });
        }
    }

    /**
     * Four small screens in the middle of the table, one per player, each tilted up and turned to
     * face its own seat - the arrangement a physical life-tracker app uses, rather than one shared
     * scoreboard that only reads correctly from one side. Everything on them is public knowledge,
     * so there is nothing here that any seat should not see.
     */
    void placeMonitors() {
        int n = snap.seats.size();
        for (int i = 0; i < n; i++) {
            float angle = seatAngle(i, n);
            Vector3 w = rotateY(0f, MONITOR_DIST, angle);
            ModelInstance inst = inst(monitorModel);
            inst.transform.setToTranslation(w.x, 0.42f, w.z)
                .rotate(Vector3.Y, (float) Math.toDegrees(-angle))
                .rotate(Vector3.X, 34f);
            props.add(inst);
            // Each player's own screen doubles as the thing you click to pick that player -
            // choosing who starts, or targeting a seat. It is already one per player and already
            // carries their name and life, so it needs no separate hit target.
            BoundingBox box = new BoundingBox();
            inst.calculateBoundingBox(box);
            box.mul(inst.transform);
            seatPick.add(box);
        }
    }

    void placeRow(Snapshot.SeatV seat, List<Snapshot.CardV> cards, float angle, float dist) {
        cards = unattached(cards);
        if (cards.isEmpty()) return;
        // A tapped card lies sideways, so it needs a CARD_H-wide slot, not CARD_W: with equal
        // slots, tapped neighbours overlapped (more visibly once tapping animated).
        float width = -GAP;
        for (Snapshot.CardV c : cards) width += (c.tapped ? CARD_H : CARD_W) + GAP;
        float cursor = -width / 2f;
        for (int j = 0; j < cards.size(); j++) {
            Snapshot.CardV c = cards.get(j);
            float slot = c.tapped ? CARD_H : CARD_W;
            float along = cursor + slot / 2f;
            cursor += slot + GAP;
            Vector3 w = rotateY(along, dist, angle);
            // LibGDX's rotate(Y, deg) is the inverse of the rotation above, hence the minus.
            float yaw = (float) Math.toDegrees(-angle) + (c.tapped ? 90f : 0f);
            add(c, seat, w.x, w.z, yaw);
            if (attachedHere.containsKey(c.id)) {
                hostTargets.put(c.id, new float[] { w.x, w.z, yaw, (float) Math.toDegrees(-angle) });
            }
        }
    }

    /** The row without the cards that are laid under a host instead (see placeAttachments). */
    List<Snapshot.CardV> unattached(List<Snapshot.CardV> cards) {
        if (attachedHere.isEmpty()) return cards;
        List<Snapshot.CardV> out = new ArrayList<>(cards.size());
        for (Snapshot.CardV c : cards) {
            boolean under = false;
            if (c.attachedToId >= 0) {
                List<Object[]> l = attachedHere.get(c.attachedToId);
                if (l != null) for (Object[] e : l) if (e[0] == c) { under = true; break; }
            }
            if (!under) out.add(c);
        }
        return out;
    }

    /**
     * Equipment, auras and fortifications sit UNDER what they are attached to, each one peeking
     * out a little further past its top edge - the way they are laid out on a real table, and in
     * Arena. They lie the same way as the host (so they turn with it when it taps), but always
     * peek towards the middle of the table, in the host SEAT's frame: peeking along a tapped
     * host's own "up" would push them sideways, under the neighbouring card, where they vanish.
     * An aura on an opponent's creature goes to that creature, wherever it sits. Slightly lower
     * than the host so the host covers it; the peeking strip is still pickable for the hover panel.
     */
    void placeAttachments() {
        for (java.util.Map.Entry<Integer, List<Object[]>> e : attachedHere.entrySet()) {
            float[] host = hostTargets.get(e.getKey());
            if (host == null) continue;
            float seatAngle = (float) Math.toRadians(-host[3]);
            List<Object[]> list = e.getValue();
            for (int k = 0; k < list.size(); k++) {
                Snapshot.CardV c = (Snapshot.CardV) list.get(k)[0];
                Snapshot.SeatV seat = (Snapshot.SeatV) list.get(k)[1];
                // Toward the table centre is the seat's local -Z (the top of an untapped card).
                // Attachments lie like the host, so they are as deep as it is and only the peek shows.
                Vector3 off = rotateY(0f, -ATTACH_PEEK * (k + 1), seatAngle);
                add(c, seat, host[0] + off.x, -0.004f * (k + 1), host[1] + off.z, host[2], 0f);
            }
        }
    }

    /**
     * Your hand, anchored at YOUR seat rather than at whichever seat is being viewed.
     *
     * This is the hidden-information rule made structural. The snapshot only ever contains the
     * hand of the player this client controls (StateReader reads no one else's), so stepping round
     * to another seat cannot reveal their cards - there is nothing to reveal. Keeping the hand at
     * your own seat also means it is never mistaken for the hand of the player you are looking at:
     * from their seat you see your own cards lying across the table, from the back.
     */
    void placeHand() {
        if (snap.hand.isEmpty() || snap.seats.isEmpty()) return;
        float angle = seatAngle(snap.youIndex(), snap.seats.size());
        float step = CARD_W * 0.80f;
        float width = snap.hand.size() * step;
        // The card under the mouse (as picked last frame) rises and stands a little more upright,
        // the way you pull a card up out of a hand to read it. The pose easing animates it. The
        // lift is small enough that the raised card still covers the spot the mouse is on, or
        // it would drop, be hovered again, and flicker.
        int lifted = hovered != null && hovered.seat == null ? hovered.card.id : Integer.MIN_VALUE;
        for (int i = 0; i < snap.hand.size(); i++) {
            Snapshot.CardV c = snap.hand.get(i);
            float along = -width / 2f + i * step + step / 2f;
            boolean up = c.id == lifted;
            seedFromLibrary(c);
            Vector3 w = rotateY(along, SEAT_DIST + 3.6f - (up ? 0.15f : 0f), angle);
            add(c, null, w.x, 0.62f + (up ? 0.35f : 0f), w.z, (float) Math.toDegrees(-angle),
                up ? HAND_TILT + 12f : HAND_TILT);
        }
    }

    /**
     * Face-down cards for everyone else's hand. How many cards a player is holding is public
     * knowledge, so it is shown; what they are is not, and the renderer is never told. Tilted away
     * from the table so from any other seat you see only backs.
     */
    void placeOpponentHands() {
        int n = snap.seats.size();
        int you = snap.youIndex();
        for (int i = 0; i < n; i++) {
            if (i == you) continue;
            Snapshot.SeatV seat = snap.seats.get(i);
            int shown = Math.min(seat.handSize, 12);     // a big hand stops growing the fan
            if (shown <= 0) continue;
            float angle = seatAngle(i, n);
            float step = CARD_W * 0.62f;      // a clear gap between backs, so they count at a glance
            float width = shown * step;
            for (int j = 0; j < shown; j++) {
                float along = -width / 2f + j * step + step / 2f;
                Vector3 w = rotateY(along, SEAT_DIST + 3.4f, angle);
                ModelInstance inst = inst(backModel);
                inst.transform.setToTranslation(w.x, 0.62f, w.z)
                    .rotate(Vector3.Y, (float) Math.toDegrees(-angle))
                    .rotate(Vector3.X, HAND_TILT);
                props.add(inst);
            }
        }
    }

    /**
     * The faint slab under a band of permanents. Only drawn when the band has something in it, so
     * an empty board stays clean rather than showing three empty trays.
     */
    void placeBand(List<Snapshot.CardV> cards, float angle, float dist, Model band) {
        cards = unattached(cards);
        if (cards.isEmpty()) return;
        float width = 0.5f - GAP;   // same slot widths as placeRow: tapped cards are CARD_H wide
        for (Snapshot.CardV c : cards) width += (c.tapped ? CARD_H : CARD_W) + GAP;
        Vector3 w = rotateY(0f, dist, angle);
        ModelInstance inst = inst(band);
        inst.transform.setToTranslation(w.x, -0.005f, w.z)
            .rotate(Vector3.Y, (float) Math.toDegrees(-angle))
            .scale(width / BAND_UNIT, 1f, 1f);
        props.add(inst);
    }

    /** True when anyone has a commander, i.e. the command zone means something this game. */
    boolean commanderGame() {
        for (Snapshot.SeatV s : snap.seats) {
            if (!s.command.isEmpty()) return true;
            for (Snapshot.CardV c : s.creatures) if (c.commander) return true;
            for (Snapshot.CardV c : s.others) if (c.commander) return true;
        }
        return false;
    }

    /**
     * The command zone: a pad beside each seat, mirrored from the library/graveyard/exile pads,
     * holding whatever is in that player's command zone - the commander between casts, emblems,
     * a companion. A partner pair sits side by side, running outwards. Laid with add(), so a
     * commander slides from here to the stack and onto the battlefield (it keeps its id), and
     * back again when it dies or is exiled and goes home. The pad is only drawn in a commander
     * game, so other formats are not left with an empty tray that means nothing.
     */
    void placeCommandZone() {
        if (!commanderGame()) return;
        int n = snap.seats.size();
        for (int i = 0; i < n; i++) {
            Snapshot.SeatV seat = snap.seats.get(i);
            float angle = seatAngle(i, n);
            float yaw = (float) Math.toDegrees(-angle);
            Vector3 w = rotateY(COMMAND_X, COMMAND_Z, angle);
            ModelInstance pad = inst(commandPadModel);
            pad.transform.setToTranslation(w.x, 0.0f, w.z).rotate(Vector3.Y, yaw);
            props.add(pad);
            for (int k = 0; k < seat.command.size(); k++) {
                Vector3 cw = rotateY(COMMAND_X - k * (CARD_W + GAP), COMMAND_Z, angle);
                add(seat.command.get(k), seat, cw.x, cw.z, yaw);
            }
        }
    }

    /** Library, graveyard and exile, as pads beside each seat's rows. Counts are drawn over them. */
    void placeZones() {
        int n = snap.seats.size();
        for (int i = 0; i < n; i++) {
            float angle = seatAngle(i, n);
            for (int k = 0; k < 3; k++) {
                Vector3 w = rotateY(5.3f, SEAT_DIST + 1.7f - k * 1.45f, angle);
                ModelInstance inst = inst(zoneModel);
                inst.transform.setToTranslation(w.x, 0.0f, w.z)
                    .rotate(Vector3.Y, (float) Math.toDegrees(-angle));
                props.add(inst);
            }
        }
    }

    void add(Snapshot.CardV c, Snapshot.SeatV seat, float x, float z, float yawDeg) {
        // On the table: flat, at table height.
        add(c, seat, x, 0.02f, z, yawDeg, 0f);
    }

    void add(Snapshot.CardV c, Snapshot.SeatV seat, float x, float y, float z,
                     float yawDeg, float tiltDeg) {
        add(c, seat, x, y, z, yawDeg, tiltDeg, 1f);
    }

    void add(Snapshot.CardV c, Snapshot.SeatV seat, float x, float y, float z,
                     float yawDeg, float tiltDeg, float scale) {
        Model m = c.commander ? commanderModel : c.land ? landModel : c.creature ? creatureModel : cardModel;
        Drawn d = new Drawn();
        d.inst = inst(m);
        d.card = c;
        d.seat = seat;
        // Where to draw it this frame: eased from where it was drawn last frame (see Pose). A new
        // card starts at its target; a second copy of an id in the same frame (an ability on the
        // stack shares its source's id) is drawn at its target without disturbing the first.
        Pose p = poses.get(c.id);
        if (p == null) {
            p = new Pose();
            p.x = x; p.y = y; p.z = z; p.yaw = yawDeg; p.tilt = tiltDeg;
            poses.put(c.id, p);
        } else if (p.seen == frameNo) {
            p = new Pose();
            p.x = x; p.y = y; p.z = z; p.yaw = yawDeg; p.tilt = tiltDeg;
        } else {
            ease(p, x, y, z, yawDeg, tiltDeg);
        }
        p.seen = frameNo;
        d.inst.transform.setToTranslation(p.x, p.y, p.z).rotate(Vector3.Y, p.yaw);
        // Tilting is what makes a card readable when it is not lying on the table: the hand is
        // fanned up towards its owner, stack items stand fully upright facing the viewer.
        if (Math.abs(p.tilt) > 0.01f) d.inst.transform.rotate(Vector3.X, p.tilt);
        if (scale != 1f) d.inst.transform.scale(scale, 1f, scale);
        d.inst.calculateBoundingBox(d.bounds);
        d.bounds.mul(d.inst.transform);

        // The picture, if Forge has it cached. Asking each frame is what makes it appear once the
        // background decode finishes; until then the coloured body stands in for it.
        com.badlogic.gdx.graphics.Texture tex = art == null ? null : art.get(c);
        if (tex != null) {
            d.face = inst(faceModel);
            d.face.transform.set(d.inst.transform).translate(0f, CARD_THICK / 2f + 0.03f, 0f);
            d.face.materials.get(0).set(
                com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute.createDiffuse(tex));
        }
        // Forge only publishes a selectable list for prompts that ask you to choose a card
        // ("discard one"), not for ordinary priority - there it outlines playable cards by another
        // route and accepts a click on anything. So this marker means "Forge has offered this
        // card", which is a narrower and more honest claim than "you can play this".
        if (!snap.selectableIds.isEmpty() && snap.selectableIds.contains(c.id)) {
            d.marker = inst(selectModel);
            d.marker.transform.set(d.inst.transform).translate(0f, -CARD_THICK, 0f);
        } else if (c.attacking) {
            d.marker = inst(attackModel);
            d.marker.transform.set(d.inst.transform).translate(0f, -CARD_THICK, 0f);
        }
        drawn.add(d);
    }
}
