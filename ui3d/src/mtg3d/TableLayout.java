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
        com.badlogic.gdx.graphics.Pixmap fp = Felt.felt(GraphicsQuality.feltSize());
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

        // The rail is walnut, not a brown box: generated grain, with a little specular so the
        // top edge catches the light the way a polished rail does.
        com.badlogic.gdx.graphics.Pixmap wp = Felt.wood(512, 64);
        woodTex = new com.badlogic.gdx.graphics.Texture(wp, true);
        wp.dispose();
        woodTex.setFilter(com.badlogic.gdx.graphics.Texture.TextureFilter.MipMapLinearLinear,
            com.badlogic.gdx.graphics.Texture.TextureFilter.Linear);
        Material railMat = new Material(
            com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute.createDiffuse(woodTex),
            ColorAttribute.createSpecular(0.22f, 0.17f, 0.12f, 1f),
            com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute.createShininess(18f));
        // One rail model, as long as a whole side including both corners, turned for each side.
        float len = 2f * (t + RAIL_W);
        railModel = builder.createBox(len, RAIL_H, RAIL_W, railMat, texAttrs);
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
        stacked.clear();
        pileMembers.clear();
        beads.clear();
        squeezed.clear();
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

            float push = boardPush(seat);
            if (seatPush.length != n) seatPush = new float[n];
            seatPush[i] = push;
            rowCap = rowLimit(push);        // every row of THIS seat is measured against this

            // The mat, and only the mat. There used to be a second dark slab laid under it -
            // the seat pad - of a different size and a different colour, so every board sat on
            // two stacked rectangles with a black lip showing round the edge (the owner,
            // 2026-10-22). Whose turn it is and who is being waited on are already said by the
            // frame round the seat, the phase strip and the centre screen; the pad was saying it
            // a fourth time, in a layer.
            placePlinth(seat, i, angle, push);

            // Three bands per seat, running from the middle of the table outwards: creatures,
            // then other permanents (artifacts, enchantments, planeswalkers), then lands nearest
            // their owner. Each band gets a faint slab so the grouping reads without labelling it.
            placeBand(seat.creatures, angle, ROW_CREATURES + push, bandCreature);
            placeBand(seat.others, angle, ROW_OTHERS + push, bandOther);
            placeBand(seat.lands, angle, ROW_LANDS + push, bandLand);
            placeRow(seat, seat.creatures, angle, ROW_CREATURES + push);
            placeRow(seat, seat.others, angle, ROW_OTHERS + push);
            placeRow(seat, seat.lands, angle, ROW_LANDS + push);
        }
        placeManaBeads();
        placeSeatEdges();
        placeAttachments();
        placeCommandZone();
        placeHand();
        placeOpponentHands();
        placeZones();
        placeMonitors();
        placeStack();
        placeDying();
    }

    /**
     * How far a marker slab (playable, attacking, blocking, target, paid) sits below its card. It was
     * -CARD_THICK, which put the slab's top under the row's band slab (top at y 0.001), so every
     * marker was hidden; at this drop its rim (top y 0.016) clears the band and stays under the
     * card picture (y 0.07).
     */
    static final float MARKER_DROP = -0.014f;

    /**
     * Ids of permanents to mark in teal: the source of every ability or trigger on the stack (a
     * stack item's id is its source card's), and the card whose abilities are being chosen
     * between (Gui3D.takeAbility - its options carry the host's id). Rebuilt only when the
     * snapshot or the pending choice changes, not for every card on every frame.
     */
    java.util.Set<Integer> abilitySourceIds() {
        Gui3D.Choice choice = Gui3D.pendingChoice();
        if (snap != sourcesSnap || choice != sourcesChoice) {
            sourcesSnap = snap;
            sourcesChoice = choice;
            sourceIds.clear();
            for (Snapshot.CardV s : snap.stack) if (s.stackIsAbility) sourceIds.add(s.id);
            if (choice != null && !choice.cards) {
                for (Snapshot.CardV o : choice.items) if (o.stackText != null && !o.stackText.isEmpty() && o.id > 0) sourceIds.add(o.id);
            }
        }
        return sourceIds;
    }

    private Snapshot sourcesSnap;
    private Gui3D.Choice sourcesChoice;
    private final java.util.Set<Integer> sourceIds = new java.util.HashSet<>();

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
            Vector3 w = rotateY(zoneX(d.pad), SEAT_DIST + 1.7f - d.pad * 1.45f, angle);
            // Onto the top of the pile, not into it.
            add(d.card, snap.seats.get(d.seat), w.x, pileTop(d.seat, d.pad) + 0.03f, w.z, (float) Math.toDegrees(-angle), 0f);
        }
    }

    /**
     * A card that has just been drawn starts at your library pad, so it is seen coming off the
     * deck into the hand rather than appearing there. Called for hand cards with no pose yet.
     */
    void seedFromLibrary(Snapshot.CardV c) {
        if (!zonesPrimed || poses.containsKey(c.id) || snap.seats.isEmpty()) return;
        float angle = seatAngle(snap.youIndex(), snap.seats.size());
        Vector3 lib = rotateY(zoneX(0), SEAT_DIST + 1.7f, angle);
        Pose p = new Pose();
        p.x = lib.x; p.y = pileTop(snap.youIndex(), 0) + 0.03f; p.z = lib.z;   // off the top of the deck
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
        stackLabelAt.clear();
        flatStack = null;
        if (snap.stack.isEmpty()) return;
        // Seated, the stack stands upright in the air, facing you. In the overhead (TAB) view it
        // lies flat over the middle of the table instead (the owner, 2026-10-22): looked down on,
        // upright cards are edge-on and unreadable. `t` follows the camera's own lift, so the
        // cards lie down and stand up with the camera rather than snapping.
        float t = smooth(lift);
        int n = snap.stack.size();
        float scale = STACK_SCALE + (FLAT_STACK_SCALE - STACK_SCALE) * t;
        float tilt = 90f * (1f - t);
        float baseY = STACK_Y + (FLAT_STACK_Y - STACK_Y) * t;
        float cardW = CARD_W * scale;
        float step = cardW * 1.18f;
        // Flat, the bar must stay between the side seats' creature rows: past FLAT_STACK_MAX the
        // cards overlap, the later (nearer the top of the stack) over the earlier.
        float maxW = FLAT_STACK_MAX + (1f - t) * 1000f;
        if (n > 1 && (n - 1) * step + cardW > maxW) step = (maxW - cardW) / (n - 1);
        float width = (n - 1) * step + cardW;
        float yaw = (float) Math.toDegrees(-orbit);
        // Down the card's face, from its centre towards its bottom edge: straight down while it
        // stands, towards the viewer once it lies flat. Arcs and labels hang off that edge.
        Vector3 o = rotateY(0f, 0f, orbit), toViewer = rotateY(0f, 1f, orbit).sub(o);
        float rad = (float) Math.toRadians(tilt);
        Vector3 down = new Vector3(toViewer).scl((float) Math.cos(rad)).add(0f, -(float) Math.sin(rad), 0f);

        // A panel behind the cards. Floating bare cards read as loose cards; a frame says "this
        // is the stack", which is the thing you most need to notice is not empty.
        ModelInstance frame = new ModelInstance(stackFrameModel);
        frame.transform.setToTranslation(o.x, baseY + 0.18f * (1f - t) - 0.04f * t, o.z)
            .rotate(Vector3.Y, yaw)
            .rotate(Vector3.X, tilt)
            .scale((width + 0.9f) / STACK_FRAME_UNIT, 1f, (CARD_H * scale + 0.8f) / (CARD_H * STACK_SCALE + 0.8f));
        props.add(frame);
        flatStack = t < 0.5f ? null : new float[] { o.x, o.z, toViewer.x, toViewer.z,
            width / 2f + 0.45f, CARD_H * scale / 2f + 0.4f };

        for (int i = 0; i < n; i++) {
            Snapshot.CardV c = snap.stack.get(i);
            boolean top = (i == n - 1);
            float along = -width / 2f + i * step + cardW / 2f;
            Vector3 w = rotateY(along, 0f, orbit);
            // Upright, the card about to resolve stands a little higher; flat, each card lies a
            // hair above the one before, so the overlap shows the newest on top.
            float y = baseY + (top ? 0.34f * (1f - t) : 0f) + i * 0.01f * t;
            add(c, null, w.x, y, w.z, yaw, tilt, scale);
            // Where a target arc starts: the bottom edge of the card. Kept per stack index,
            // because an ability shares its id with its source, so poses cannot tell them apart.
            float half = CARD_H * scale / 2f;
            stackAt.add(new float[] { w.x + down.x * half, y + down.y * half, w.z + down.z * half });
            // Where its "who cast it" label goes: low on the upright card (as it always was), and
            // just past the near edge of a flat one.
            float lab = 0.8f + (half + 0.25f - 0.8f) * t;
            stackLabelAt.add(new float[] { w.x + down.x * lab, y + down.y * lab, w.z + down.z * lab });
        }
    }

    /** The stack in the overhead view: how big its cards are, how high it lies, how wide it may run. */
    static final float FLAT_STACK_SCALE = 1.5f, FLAT_STACK_Y = 1.0f, FLAT_STACK_MAX = 7.0f;
    /** Where each stack item's label is drawn (TableHud), filled with the cards by placeStack. */
    final List<float[]> stackLabelAt = new ArrayList<>();
    /**
     * The flat stack's footprint while it lies on the table - centre x, z; the toward-the-viewer
     * direction x, z; half its length and half its depth, frame included - or null.
     */
    float[] flatStack;

    /** True when this point of the table is under the flat (overhead) stack. */
    boolean underFlatStack(float x, float z) {
        float[] f = flatStack;
        if (f == null || snap.stack.isEmpty()) return false;
        float dx = x - f[0], dz = z - f[1];
        float across = dx * f[2] + dz * f[3];                 // towards the viewer
        float along = dx * -f[3] + dz * f[2];                 // the bar's own direction
        return Math.abs(along) <= f[4] && Math.abs(across) <= f[5];
    }

    /**
     * Four small screens in the middle of the table, one per player, each tilted up and turned to
     * face its own seat - the arrangement a physical life-tracker app uses, rather than one shared
     * scoreboard that only reads correctly from one side. Everything on them is public knowledge,
     * so there is nothing here that any seat should not see.
     */
    /**
     * One screen in the middle of the table, split into a panel per player, turned to face
     * whoever is sitting at this client.
     *
     * It used to be four separate screens, one in front of each seat, each facing its own
     * player - so three quarters of the scoreboard was always edge-on or back-to-front from
     * where you were sitting. The owner's point is the one that makes this easy: everybody runs
     * their own client, so the table does not have to be a compromise between four viewpoints.
     * It can be laid out for the one person looking at it, and on their neighbour's machine it
     * is laid out for them instead.
     *
     * So: one case, the panels side by side across it in seat order, the whole thing square on
     * to the local player. Each panel keeps its own glass and its own tint - gold for whoever's
     * turn it is, cyan for your own, steel blue otherwise - and is still what you click to pick
     * that player.
     */
    /**
     * A screen for each player, standing in front of that player's own board - so whose it is
     * needs no working out - but every one of them turned square on to whoever is sitting at
     * THIS client.
     *
     * One merged screen was tried first and the owner's objection was immediate and right: four
     * panels in a row say nothing about which player each belongs to. Position is what identifies
     * them, and position is exactly what a screen in front of each seat already had.
     *
     * What was wrong before was only the facing. Each screen used to face its own player, so
     * three quarters of the table was edge-on or back-to-front from where you sat. They all face
     * you now, and because everybody runs their own client, on the next machine they all face
     * that player instead.
     *
     * The far screen - the seat opposite - is raised, and the two beside you a little, so the
     * row nearest you does not hide the rest. Nobody's own screen is in their way.
     */
    void placeMonitors() {
        int n = snap.seats.size();
        if (n == 0) return;
        monitorAt.clear();
        int you = Math.max(0, snap.youIndex());
        float view = seatAngle(you, n);
        float yaw = (float) Math.toDegrees(-view);

        for (int i = 0; i < n; i++) {
            Snapshot.SeatV s = snap.seats.get(i);
            // Where this screen stands, in OUR frame rather than at its own seat's angle: the
            // screens are furniture for the person looking at the table, so they are arranged
            // for that person and not for the player they belong to.
            //
            //   ours        nearest, straight in front
            //   opposite    directly beyond it and raised, so it reads as the same column
            //               carrying on - "continue on from our closest screen"
            //   the rest    out to the sides, clear of the middle, because standing between us
            //               and a neighbour's board is standing in front of their cards (the
            //               owner, 2026-10-22)
            int rel = Math.floorMod(i - you, n);
            boolean opposite = n % 2 == 0 && rel == n / 2;
            float lx, lz, lift;
            if (rel == 0) {
                lx = 0f; lz = MONITOR_DIST; lift = 0.40f;
            } else if (opposite) {
                lx = 0f; lz = MONITOR_DIST - 1.60f; lift = 1.12f;
            } else {
                // Clockwise from us goes right, anticlockwise left; a bigger table puts the
                // second and third along each side further out again.
                boolean right = rel <= n / 2;
                int rank = right ? rel : n - rel;
                lx = (right ? 1f : -1f) * (3.10f + (rank - 1) * 1.95f);
                lz = MONITOR_DIST - 1.25f;
                lift = 0.70f;
            }
            Vector3 w = rotateY(lx, lz, view);

            ModelInstance frame = inst(monitorModel);
            frame.transform.setToTranslation(w.x, lift, w.z)
                .rotate(Vector3.Y, yaw)
                .rotate(Vector3.X, 34f);
            props.add(frame);

            ModelInstance glass = inst(screenModel);
            glass.transform.set(frame.transform).translate(0f, 0.035f, 0f);
            Color tint = s.lost ? SCREEN_OUT : s.active ? SCREEN_ACTIVE : s.isYou ? SCREEN_YOU : SCREEN_OTHER;
            // Dim glass: the life total is drawn over it in a bright colour of the same family,
            // so the screen must stay dark enough for that to read.
            glass.materials.get(0).set(ColorAttribute.createDiffuse(tint.r * 0.55f, tint.g * 0.55f, tint.b * 0.55f, 1f));
            glass.materials.get(0).set(ColorAttribute.createEmissive(tint.r * 0.30f, tint.g * 0.30f, tint.b * 0.30f, 1f));
            props.add(glass);

            // The stand reaches from the felt to wherever the screen has been lifted to.
            ModelInstance stand = inst(standModel);
            float standH = Math.max(0.1f, lift - 0.21f);
            stand.transform.setToTranslation(w.x, standH / 2f - 0.015f, w.z)
                .rotate(Vector3.Y, yaw)
                .scale(1f, standH / 0.42f, 1f);
            props.add(stand);

            Vector3 at = new Vector3();
            glass.transform.getTranslation(at);
            monitorAt.add(new float[] { at.x, at.y, at.z });
            // Each player's own screen doubles as the thing you click to pick that player.
            BoundingBox box = new BoundingBox();
            frame.calculateBoundingBox(box);
            box.mul(frame.transform);
            seatPick.add(box);
        }
    }

    /**
     * What makes two permanents the same card for stacking.
     *
     * The owner's rule, and the right one: twenty Treasures stack, five 1/1 Humans stack, but two
     * 1/1 Humans and two 2/2 Humans do not - if they differ in any way you can see, you have to be
     * able to see it. So the key is everything the face shows: name, printed power and toughness,
     * counters, whether it is tapped, a token, face down, or a commander. Anything with something
     * attached to it is never stacked (placeAttachments lays those out itself), and nor is
     * anything in combat, where each attacker needs its own arc.
     */
    private String stackKey(Snapshot.CardV c) {
        // Who a creature has been told to attack is part of what makes it the same as its
        // neighbour while attackers are being declared: six Robots going at Ashton and three
        // staying home are two piles, not one of nine.
        String order = "";
        if (combatPlan != null && !combatPlan.blocking && c.creature) {
            Integer seat = combatPlan.attackSeat.get(c.id);
            order = "|atk" + (seat == null ? "-" : seat);
        }
        return order + plainStackKey(c);
    }

    private static String plainStackKey(Snapshot.CardV c) {
        // + sick (2026-10-21): nine Robots made this turn shared a pile with the one that could
        // attack, so the pile looked ready while most of it was summoning sick, and dragging it
        // offered Forge whichever robot was on top.
        return c.name + "|" + c.power + "/" + c.toughness + "|" + c.counterText
            + "|" + c.tapped + c.token + c.faceDown + c.commander + (c.sick ? "|sick" : "");
    }

    /**
     * How far a seat's rows lean towards whoever is looking, in degrees.
     *
     * Cards lying flat on a table are read at a glance by the person sitting at that side and
     * are a smear of colour from anywhere else - which is every other seat, on a screen. Each
     * row is a single line of cards, so turning every card in it about its own long axis tilts
     * that row as one plane: a shelf, angled at the viewer.
     *
     * The lean follows the CAMERA, not the seat, so it re-aims as you walk round the table and
     * whichever seat you are at is always the one squarely facing you. cos(seat - camera) does
     * the whole job: +1 at the seat you are standing behind, -1 at the one opposite (which
     * must lean the other way to face you), and 0 at the sides, where a lean about that axis
     * would do nothing anyway because you are looking along it.
     *
     * Off by default: the owner looked at it in a game and wanted the table flat again for now
     * (2026-10-21). -Dforge.ui3d.dais=26 turns the lean back on, in degrees.
     */
    static final float DAIS_TILT =
        Math.max(0f, Math.min(60f, Float.parseFloat(System.getProperty("forge.ui3d.dais", "0"))));

    /** The lean for a seat's rows, given where the camera is standing this frame. */
    float daisTilt(float seatAngle) {
        if (DAIS_TILT <= 0f) return 0f;
        return DAIS_TILT * (float) Math.cos(seatAngle - orbit);
    }

    /** -Dforge.ui3d.stack=false lays every permanent in its own slot again. */
    static final boolean STACK_ALIKE =
        !"false".equalsIgnoreCase(System.getProperty("forge.ui3d.stack", "true"));

    private boolean stackable(Snapshot.CardV c) {
        // Blockers still get a slot each. A pile is one card on screen with the rest as scenery,
        // so a second drag from it sent Forge the SAME token again, which Forge reads as taking
        // it off, and two tokens could never block one menace creature (the owner's game,
        // 2026-10-21). Blocks go to different attackers, so there is nothing to group by.
        //
        // Attackers are different: they nearly always go to the same player, and one drag now
        // assigns the whole pile (handleCombatPlan). Piles with different orders stay apart,
        // because the assignment is part of the key.
        if (combatPlan != null && combatPlan.blocking && c.creature) return false;
        // The same thing again, for every prompt that asks you to pick cards one at a time.
        // Olivia's ability wants two Treasures sacrificed; the three Treasures were one pile with
        // one clickable card on top, so the second one could not be picked at all (the owner,
        // 2026-10-22). A card Forge is offering gets its own slot, and only while it is being
        // offered - the pile comes back the moment the prompt is answered.
        if (!snap.selectableIds.isEmpty() && snap.selectableIds.contains(c.id)) return false;
        // Targets are chosen the same way, one card at a time, and that list is ours rather than
        // Forge's. So is paying mana: two identical Signets in a pile are one tap, not two.
        if (targeting != null && targeting.legal.contains(c.id)) return false;
        if (manaPlan != null && manaPlan.usable.contains(c.id)) return false;
        return STACK_ALIKE && !c.attacking && !c.blocking && !hostTargets.containsKey(c.id)
            && !attachedHere.containsKey(c.id) && c.attachedToId < 0;
    }

    /** A pile of identical permanents: where it is, how deep, and what to label it. */
    static final class StackedAt {
        final float x, z, top;
        final int count;
        StackedAt(float x, float z, float top, int count) { this.x = x; this.z = z; this.top = top; this.count = count; }
    }

    /** Filled every frame by placeRow, drawn as "x12" over the pile by TableHud. */
    final List<StackedAt> stacked = new ArrayList<>();

    /**
     * Every card in a pile, by the id of the one on top - the only one that can be pointed at.
     *
     * Declaring attackers used to break every pile apart so each creature could be dragged, which
     * meant nine Robots were nine separate arrows to drag one at a time ("we do not want to
     * assign combat for each individually" - the owner, 2026-10-22). They stay piled now, and one
     * drag speaks for all of them.
     */
    final java.util.Map<Integer, List<Integer>> pileMembers = new java.util.HashMap<>();

    /** How much each card under the top one lifts it. A real card is CARD_THICK; this reads better. */
    static final float STACK_STEP = 0.022f;
    static final int STACK_SHOW = 6;     // past this the pile stops growing and the count carries it

    /**
     * Identical permanents share a slot: twenty Treasure tokens were twenty slots wide and ran
     * straight through the next seat's board (the owner, 2026-10-21). Order is first appearance,
     * so the board does not reshuffle itself as copies arrive.
     */
    List<List<Snapshot.CardV>> rowGroups(List<Snapshot.CardV> cards) {
        List<List<Snapshot.CardV>> groups = new ArrayList<>();
        java.util.Map<String, List<Snapshot.CardV>> byKey = new java.util.LinkedHashMap<>();
        for (Snapshot.CardV c : cards) {
            if (!stackable(c)) { groups.add(java.util.Collections.singletonList(c)); continue; }
            List<Snapshot.CardV> g = byKey.get(stackKey(c));
            if (g == null) { g = new ArrayList<>(); byKey.put(stackKey(c), g); groups.add(g); }
            g.add(c);
        }
        return groups;
    }

    /** The slot a group needs: the card on top is the one drawn, and a tapped one lies sideways. */
    private static float slotFor(List<Snapshot.CardV> g) {
        return g.get(g.size() - 1).tapped ? CARD_H : CARD_W;
    }

    /**
     * How wide a row actually is, measured from the SAME groups placeRow lays out.
     *
     * The piles went in and the boards kept their old width: the slab under each band and the
     * plinth under each seat were still counting cards one by one, so a seat with twenty
     * Treasures grew a board twenty slots wide with one pile sitting in the middle of it (the
     * owner, 2026-10-21). Everything that measures a row measures it here.
     */
    float rowWidth(List<List<Snapshot.CardV>> groups) {
        return Math.min(naturalWidth(groups), rowCap);
    }

    /**
     * The widest a row may be for the seat being laid out right now - set once per seat from
     * rowLimit(push), because it depends on how far that seat's board has backed away. ROW_MAX
     * until a seat says otherwise.
     */
    float rowCap = ROW_MAX;

    /** The row laid side by side with nothing overlapping, however wide that is. */
    private static float naturalWidth(List<List<Snapshot.CardV>> groups) {
        if (groups.isEmpty()) return 0f;
        float width = -GAP;
        for (List<Snapshot.CardV> g : groups) width += slotFor(g) + GAP;
        return width;
    }

    /**
     * The widest a row may be: from just inside the command pad on one side to the same distance
     * the other way (the library / graveyard / exile pads, on the other side, start further out).
     * Rows grew without limit, so late in a game the board ran over every one of those pads and
     * hid them (the owner, 2026-10-22). A row wider than this is squeezed instead: the cards
     * overlap like a fanned hand, each one showing a strip of the one before it, and each sits a
     * hair above the last, so the strip you can see is the card you point at.
     */
    static final float ROW_MAX = 2f * (-COMMAND_X - (CARD_W + 0.24f) / 2f - 0.15f);
    /** How much higher each card in a squeezed row sits than the one it overlaps. */
    static final float SQUEEZE_RISE = 0.003f;

    /**
     * Cards a squeezed row has partly covered, by id, with the row's direction in the world (x, z):
     * the strip still showing is the end facing away from it. TableHud puts a card's P/T chip there.
     */
    final java.util.Map<Integer, float[]> squeezed = new java.util.HashMap<>();

    void placeRow(Snapshot.SeatV seat, List<Snapshot.CardV> cards, float angle, float dist) {
        cards = unattached(cards);
        if (cards.isEmpty()) return;
        List<List<Snapshot.CardV>> groups = rowGroups(cards);
        float natural = naturalWidth(groups);
        float width = rowWidth(groups);
        // Squeezed: every step between cards shrinks by the same factor and the last card keeps
        // its full width, so the row comes out exactly ROW_MAX wide.
        float last = slotFor(groups.get(groups.size() - 1));
        float squeeze = natural > rowCap && groups.size() > 1 ? (rowCap - last) / (natural - last) : 1f;
        Vector3 origin = rotateY(0f, dist, angle), ahead = rotateY(1f, dist, angle);
        float[] rowDir = { ahead.x - origin.x, ahead.z - origin.z };
        float cursor = -width / 2f;
        for (int gi = 0; gi < groups.size(); gi++) {
            List<Snapshot.CardV> g = groups.get(gi);
            Snapshot.CardV top = g.get(g.size() - 1);
            float slot = slotFor(g);
            float along = cursor + slot / 2f;
            float step = (slot + GAP) * squeeze;
            cursor += step;
            boolean covered = squeeze < 1f && gi < groups.size() - 1;
            Vector3 w = rotateY(along, dist, angle);
            // LibGDX's rotate(Y, deg) is the inverse of the rotation above, hence the minus.
            float yaw = (float) Math.toDegrees(-angle) + (top.tapped ? 90f : 0f);
            float base = 0.02f + FIELD_RAISE + (squeeze < 1f ? gi * SQUEEZE_RISE : 0f);
            // Alternate cards put their chip and pile count at opposite ends of their strip, so two
            // neighbours' labels never sit side by side on strips narrower than the label.
            if (covered) squeezed.put(top.id, new float[] { rowDir[0], rowDir[1], gi % 2 });
            // The ones underneath, as faces rather than a block, so the pile still looks like
            // cards. Only the top one is added by id - the rest are scenery, and giving them
            // poses would have them race each other every time the group changes size.
            int under = Math.min(g.size(), STACK_SHOW) - 1;
            float tilt = daisTilt(angle);
            for (int k = under; k >= 1; k--) {
                ModelInstance shim = inst(stackShimModel);
                // Leaned with the card on top of it, or the pile splays open as the board tilts.
                shim.transform.setToTranslation(w.x, base + k * STACK_STEP - STACK_STEP / 2f, w.z)
                    .rotate(Vector3.Y, yaw)
                    .rotate(Vector3.X, tilt);
                props.add(shim);
            }
            add(top, seat, w.x, base + under * STACK_STEP, w.z, yaw, tilt);
            if (g.size() > 1) {
                // The "x12" goes over the part of the pile still showing, not under the next card.
                Vector3 label = covered
                    ? rotateY(along - slot / 2f + Math.min(step, slot) / 2f, dist + (gi % 2 == 0 ? 0.35f : -0.35f), angle) : w;
                stacked.add(new StackedAt(label.x, label.z, base + under * STACK_STEP, g.size()));
                List<Integer> ids = new ArrayList<>(g.size());
                for (Snapshot.CardV member : g) ids.add(member.id);
                pileMembers.put(top.id, ids);
            }
            if (attachedHere.containsKey(top.id)) {
                hostTargets.put(top.id, new float[] { w.x, w.z, yaw, (float) Math.toDegrees(-angle) });
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
                add(c, seat, host[0] + off.x, FIELD_RAISE - 0.004f * (k + 1), host[1] + off.z, host[2], 0f);
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
        if (lift > 0.5f) { placeHandOverhead(); return; }
        float angle = seatAngle(snap.youIndex(), snap.seats.size());
        // Was 0.80, which overlapped every card with the one beside it - the owner could not
        // tell where one ended. A full card width plus a gap costs table space and buys a hand
        // you can actually read.
        float step = CARD_W * 1.12f;
        float width = snap.hand.size() * step;
        // The card under the mouse (as picked last frame) rises and stands a little more upright,
        // the way you pull a card up out of a hand to read it. The pose easing animates it. The
        // lift is small enough that the raised card still covers the spot the mouse is on, or
        // it would drop, be hovered again, and flicker.
        int lifted = hovered != null && hovered.seat == null ? hovered.card.id : Integer.MIN_VALUE;
        List<Snapshot.CardV> hand = handOrder();
        for (int i = 0; i < hand.size(); i++) {
            Snapshot.CardV c = hand.get(i);
            float along = -width / 2f + i * step + step / 2f;
            boolean up = c.id == lifted || c.id == grabbedId;
            seedFromLibrary(c);
            Vector3 w = rotateY(along, SEAT_DIST + 3.6f - (up ? 0.15f : 0f), angle);
            add(c, null, w.x, 0.62f + (up ? 0.35f : 0f), w.z, (float) Math.toDegrees(-angle),
                up ? HAND_TILT + 12f : HAND_TILT);
        }
    }

    /**
     * Owner's list 2026-10-20: "overhead mode: keep your hand visible and usable - move it to the
     * side rather than dropping it". From the overhead pose the hand at your seat is below and
     * behind the camera (or under the prompt bar), so the pose was read-only. In overhead the hand
     * becomes a column down the right of the screen, the free space under the game log: each
     * card is put on the camera's ray through its spot on screen, far enough out to be about
     * OVERHEAD_CARD_PX tall, and turned to face the camera. Lower cards sit a little nearer, so
     * the overlap never flickers. The usual pose easing slides the hand between the two places;
     * hovering pulls a card out to the left. Clicks and hover work as anywhere else (bounds).
     */
    /**
     * The hand as it should be drawn: the snapshot's order, except while a card is being moved,
     * when it is drawn where it would land. Forge is not told until the card is dropped, so this
     * is the only thing that shows the move - without it you would be arranging blind.
     */
    List<Snapshot.CardV> handOrder() {
        if (grabbedId < 0 || grabIndex < 0) return snap.hand;
        List<Snapshot.CardV> out = new ArrayList<>(snap.hand);
        int from = -1;
        for (int i = 0; i < out.size(); i++) if (out.get(i).id == grabbedId) { from = i; break; }
        if (from < 0) return snap.hand;
        Snapshot.CardV c = out.remove(from);
        out.add(Math.max(0, Math.min(out.size(), grabIndex)), c);
        return out;
    }

    void placeHandOverhead() {
        int w = Gdx.graphics.getWidth(), h = Gdx.graphics.getHeight();
        if (w <= 0 || h <= 0) return;
        int count = snap.hand.size();
        float top = h * 0.30f, bottom = h * 0.92f;             // under the game log, above the bottom bar
        float cardPx = OVERHEAD_CARD_PX;
        float step = Math.min(cardPx * 0.58f, (bottom - top - cardPx) / Math.max(1, count - 1));
        float halfFov = (float) Math.toRadians(cam.fieldOfView / 2f);
        float dist = CARD_H * h / (cardPx * 2f * (float) Math.tan(halfFov));
        int lifted = hovered != null && hovered.seat == null ? hovered.card.id : Integer.MIN_VALUE;
        List<Snapshot.CardV> hand = handOrder();
        float yaw = (float) Math.toDegrees(-orbit);
        // Parallel to the screen: tilted by the CAMERA's angle from straight down (as HAND_TILT
        // does seated). Tilting each card to its own ray sheared the column at the screen edge.
        Vector3 d = cam.direction;
        float tilt = (float) Math.toDegrees(Math.atan2(Math.sqrt(d.x * d.x + d.z * d.z), -d.y));
        float cardWpx = cardPx * CARD_W / CARD_H;
        for (int i = 0; i < count; i++) {
            Snapshot.CardV c = hand.get(i);
            boolean up = c.id == lifted || c.id == grabbedId;
            float sx = w - OVERHEAD_MARGIN_PX - cardWpx / 2f - (up ? cardWpx * 0.6f : 0f);
            float sy = top + cardPx / 2f + i * step;              // screen y, from the top
            Ray ray = cam.getPickRay(sx, sy);
            // Along the ray to the plane `dist` in front of the camera, so every card is the same
            // size; lower cards a touch nearer, so the overlap never flickers.
            float along = (dist - i * 0.04f) / Math.max(0.2f, ray.direction.dot(d));
            Vector3 p = new Vector3(ray.direction).scl(along).add(ray.origin);
            seedFromLibrary(c);
            add(c, null, p.x, p.y, p.z, yaw, tilt);
        }
    }

    static final float OVERHEAD_CARD_PX = 150f, OVERHEAD_MARGIN_PX = 24f;

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
     * Owner's list 2026-10-20: "raise each battlefield a little off the felt". A plinth under each
     * seat's three rows, from just in front of the creature row to just behind the land row and
     * as wide as the widest row; the rows, their band slabs, attachments and the seat pad sit on
     * it (FIELD_RAISE). The seat frame stays on the felt, round the outside. Neighbouring seats'
     * areas meet at the table corners, so each plinth is a hair higher than the last - two at
     * exactly the same height would flicker where they overlap.
     */
    /**
     * How far this seat's rows back away from the middle of the table.
     *
     * Nothing until a row is three quarters of the width it is allowed, then growing to PUSH_MAX
     * as it fills. Measured from the widest of the three rows, through the same grouping the row
     * is laid out with, so a board of twenty Treasures in one pile does not back away for no
     * reason.
     */
    float boardPush(Snapshot.SeatV seat) {
        float widest = 0f;
        for (List<Snapshot.CardV> row : java.util.Arrays.asList(seat.creatures, seat.others, seat.lands)) {
            // The TRUE width, not the capped one - the cap is what we are choosing here.
            widest = Math.max(widest, naturalWidth(rowGroups(unattached(row))));
        }
        // Enough push that the row would fit inside the seat's own wedge, capped. Pushing back
        // buys width, because the wedge opens out as it goes - which is why this is worth doing
        // at all rather than only squeezing.
        float need = 0f;
        for (float p = 0f; p <= PUSH_MAX + 0.001f; p += 0.1f) {
            need = p;
            if (widest <= rowLimit(p)) break;
        }
        return Math.min(PUSH_MAX, need);
    }

    /**
     * The widest a row may be drawn at this push, so that it stays inside the seat's own wedge of
     * the table and clear of its own pads.
     *
     * Four seats at ninety degrees each: a board is only its own if it stays inside the lines
     * running out from the middle of the table to the corners. It did not - rows were capped at a
     * flat width that reached well past them, so late in a game one player's board was drawn over
     * the next player's, and the coloured frame round it over both (the owner, 2026-10-22). The
     * wedge opens out with distance, so backing away earns room rather than losing it.
     */
    float rowLimit(float push) {
        float near = ROW_CREATURES + push - CARD_H / 2f - 0.2f;
        float byWedge = 2f * wedgeHalf(near);
        return Math.min(ROW_MAX, byWedge);
    }

    /** Half the width the seat's wedge allows at distance z from the middle, less a margin. */
    float wedgeHalf(float z) {
        int n = Math.max(1, snap.seats.size());
        if (n < 3) return Math.max(0.5f, z * 2f);         // one or two seats: no neighbour to meet
        float half = z * (float) Math.tan(Math.PI / n);
        return Math.max(0.5f, half - 0.18f);
    }

    /** How high this seat's library, graveyard, exile and command pads float, as its board fills. */
    float zoneLift(int seatIndex) {
        if (seatIndex < 0 || seatIndex >= seatPush.length) return 0f;
        return ZONE_LIFT * seatPush[seatIndex] / PUSH_MAX;
    }

    /**
     * A player's mat: the whole of their wedge of the table, in that shape.
     *
     * It was a rectangle, which is not the shape of the space a seat has - the table gives each
     * player a wedge that opens out as it goes, and a rectangle laid in one either wastes the
     * far corners or runs over the lines at the near end (the owner, 2026-10-22). The mat is the
     * wedge now: narrow where it meets the middle of the table, wide at the player's own edge.
     */
    void placePlinth(Snapshot.SeatV seat, int index, float angle, float push) {
        float near = ROW_CREATURES + push - CARD_H / 2f - 0.5f;
        float far = ROW_LANDS + push + CARD_H / 2f + 0.5f;
        // INSIDE the wedge line by a margin, so there is bare felt between one player's mat
        // and the next, and the coloured frame has somewhere to be seen. Mats that met edge to
        // edge tiled the whole table and hid every frame under themselves.
        float hNear = wedgeHalf(near) - MAT_INSET;
        float hFar = wedgeHalf(far) - MAT_INSET;
        Model mat = matModel(index, near, far, hNear, hFar);
        if (mat == null) return;

        ModelInstance inst = inst(mat);
        // Built in the seat's own frame (x across, z out from the middle), so the instance only
        // has to be turned to the seat's angle and lifted onto the felt.
        inst.transform.setToTranslation(0f, FIELD_RAISE - 0.004f + index * 0.0015f, 0f)
            .rotate(Vector3.Y, (float) Math.toDegrees(-angle));
        // The same leather, dyed per player: the shader multiplies this through the texture, so
        // the grain stays and only the colour changes. A seat that is out goes grey.
        Color tint = MAT_TINTS[index % MAT_TINTS.length];
        if (seat.lost) inst.materials.get(0).set(ColorAttribute.createDiffuse(0.42f, 0.40f, 0.40f, 1f));
        else inst.materials.get(0).set(ColorAttribute.createDiffuse(tint));
        props.add(inst);
    }

    /**
     * The mat mesh for a seat, rebuilt only when its shape actually changes.
     *
     * Four corners and one quad. Corner order follows the felt's own (far edge first, then back
     * towards the middle of the table) so the face points up and is lit like everything else.
     */
    private Model matModel(int index, float near, float far, float hNear, float hFar) {
        int n = snap.seats.size();
        if (matModels.length != n) {
            for (Model m : matModels) if (m != null) m.dispose();
            matModels = new Model[n];
            matShape = new float[n][];
        }
        if (index < 0 || index >= n) return null;
        float[] want = { near, far, hNear, hFar };
        float[] have = matShape[index];
        boolean same = have != null;
        if (same) for (int k = 0; k < 4; k++) if (Math.abs(have[k] - want[k]) > 0.004f) same = false;
        if (same && matModels[index] != null) return matModels[index];

        if (matModels[index] != null) matModels[index].dispose();
        Material mat = new Material(
            com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute.createDiffuse(leatherTex),
            ColorAttribute.createSpecular(0.10f, 0.10f, 0.11f, 1f),
            com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute.createShininess(8f));
        matModels[index] = builder.createRect(
            -hFar, 0f, far,   hFar, 0f, far,   hNear, 0f, near,   -hNear, 0f, near,
            0f, 1f, 0f, mat,
            Usage.Position | Usage.Normal | Usage.TextureCoordinates);
        matShape[index] = want;
        return matModels[index];
    }

    static final Color SCREEN_ACTIVE = new Color(0.42f, 0.28f, 0.08f, 1f), SCREEN_YOU = new Color(0.16f, 0.42f, 0.50f, 1f),
        SCREEN_OTHER = new Color(0.16f, 0.27f, 0.38f, 1f), SCREEN_OUT = new Color(0.20f, 0.10f, 0.10f, 1f);

    /** How far the battlefield (plinth top) sits above the felt. */
    static final float FIELD_RAISE = 0.12f;

    /**
     * The faint slab under a band of permanents. Only drawn when the band has something in it, so
     * an empty board stays clean rather than showing three empty trays.
     */
    void placeBand(List<Snapshot.CardV> cards, float angle, float dist, Model band) {
        cards = unattached(cards);
        if (cards.isEmpty()) return;
        float width = rowWidth(rowGroups(cards)) + 0.5f;    // the grouped row, plus a small rim
        Vector3 w = rotateY(0f, dist, angle);
        ModelInstance inst = inst(band);
        inst.transform.setToTranslation(w.x, -0.005f + FIELD_RAISE, w.z)
            .rotate(Vector3.Y, (float) Math.toDegrees(-angle))
            .scale(width / BAND_UNIT, 1f, 1f);
        props.add(inst);
    }

    /**
     * Each seat's floating mana as six stacks of beads along the near edge of its board.
     *
     * A tray is drawn for every colour that has something in it and for nothing else, so an empty
     * pool - which is most of the time - leaves the board exactly as it was. Beads stack upwards
     * and stop at BEAD_SHOW; past that the count is drawn over the stack instead of building a
     * tower nobody can read.
     */
    void placeManaBeads() {
        int n = snap.seats.size();
        for (int i = 0; i < n; i++) {
            Snapshot.SeatV seat = snap.seats.get(i);
            if (seat.lost) continue;
            float angle = seatAngle(i, n);
            float yaw = (float) Math.toDegrees(-angle);
            // Six trays centred on the board, in WUBRGC order from the owner's left.
            float across = -(6 - 1) * BEAD_STEP / 2f;
            for (int m = 0; m < 6; m++) {
                int count = m < seat.mana.length ? seat.mana[m] : 0;
                if (count <= 0) continue;
                float x = across + m * BEAD_STEP;
                // Back with the board, like everything else of this seat's: the trays sit in the
                // gap between the frame and the creature band, and that gap moves (boardPush).
                float push = i < seatPush.length ? seatPush[i] : 0f;
                Vector3 w = rotateY(x, BEAD_Z + push, angle);
                ModelInstance tray = inst(beadTrayModel);
                tray.transform.setToTranslation(w.x, FIELD_RAISE, w.z).rotate(Vector3.Y, yaw);
                props.add(tray);
                int show = Math.min(count, BEAD_SHOW);
                for (int k = 0; k < show; k++) {
                    ModelInstance bead = inst(beadModel);
                    bead.transform.setToTranslation(w.x, FIELD_RAISE + BEAD_D / 2f + k * BEAD_RISE, w.z);
                    bead.materials.get(0).set(ColorAttribute.createDiffuse(TableHud.MANA_COLOURS[m]));
                    props.add(bead);
                }
                beads.add(new BeadsAt(w.x, w.z,
                    FIELD_RAISE + BEAD_D / 2f + (show - 1) * BEAD_RISE, count, m));
            }
        }
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
            float lift = zoneLift(i);
            // The same clamp, on the other side of the board.
            float cmdHalf = (CARD_W + 0.24f) / 2f;
            float cmdX = -Math.min(-COMMAND_X, wedgeHalf(COMMAND_Z) - cmdHalf - 0.08f);
            Vector3 w = rotateY(cmdX, COMMAND_Z, angle);
            if (lift > 0.001f) {
                ModelInstance stand = inst(zoneStandModel);
                stand.transform.setToTranslation(w.x, lift / 2f, w.z)
                    .rotate(Vector3.Y, yaw).scale(1.18f, lift, 1.18f);
                props.add(stand);
            }
            ModelInstance pad = inst(commandPadModel);
            pad.transform.setToTranslation(w.x, lift, w.z).rotate(Vector3.Y, yaw);
            props.add(pad);
            for (int k = 0; k < seat.command.size(); k++) {
                // A partner pair runs INWARDS from the pad: outwards is off the seat's own
                // half of the table now that the pad is clamped to it.
                Vector3 cw = rotateY(cmdX + k * (CARD_W + GAP), COMMAND_Z, angle);
                add(seat.command.get(k), seat, cw.x, lift + 0.02f + FIELD_RAISE, cw.z, yaw, 0f);
            }
        }
    }

    /**
     * A frame round each seat's own territory, green on that player's turn and yellow while the
     * game is waiting on them to respond.
     *
     * Four bars rather than a filled slab: a slab that size either hides the felt or has to be
     * translucent, and a translucent one under the cards made the whole half of the table murky.
     * An outline reads as a boundary at a glance and leaves the board alone.
     *
     * Pooled instances are reused between frames, so the colour is set every frame rather than
     * once - an instance that was green last frame is whatever seat needs it this frame.
     */
    void placeSeatEdges() {
        int n = snap.seats.size();
        for (int i = 0; i < n; i++) {
            Snapshot.SeatV seat = snap.seats.get(i);
            float angle = seatAngle(i, n);
            float yaw = (float) Math.toDegrees(-angle);
            Color c = edgeColour(seat);
            // The frame goes back with the board it is drawn round (boardPush), or a crowded
            // seat's cards sit outside their own outline.
            float push = i < seatPush.length ? seatPush[i] : 0f;
            float near = EDGE_NEAR + push, far = EDGE_FAR + push;
            // A TRAPEZIUM, not a rectangle: the sides run out along the lines that divide the
            // table between the seats, so a frame can never cross into a neighbour's half - the
            // old one was a flat 12.6 wide at every distance and lay over both of them (the
            // owner, 2026-10-22). It also widens as it goes, which is the shape of the space a
            // player actually has.
            float hNear = Math.min(EDGE_X, wedgeHalf(near) + 0.18f);
            float hFar = Math.min(EDGE_X, wedgeHalf(far) + 0.18f);
            float midZ = (near + far) / 2f;
            bar(0f, near, 2f * hNear, 0f, angle, yaw, c);
            bar(0f, far, 2f * hFar, 0f, angle, yaw, c);
            // The two sides, each a bar leaning out by however much the frame widens.
            float run = hFar - hNear, depth = far - near;
            float side = (float) Math.sqrt(run * run + depth * depth);
            float lean = (float) Math.toDegrees(Math.atan2(run, depth));
            bar(-(hNear + hFar) / 2f, midZ, side, 90f - lean, angle, yaw, c);
            bar((hNear + hFar) / 2f, midZ, side, 90f + lean, angle, yaw, c);
        }
    }

    /** One bar of a seat frame, at (x, z) in that seat's frame, `len` long, turned `spin` on top. */
    private void bar(float x, float z, float len, float spin, float angle, float yaw, Color c) {
        Vector3 w = rotateY(x, z, angle);
        ModelInstance inst = inst(seatEdgeModel);
        inst.transform.setToTranslation(w.x, EDGE_Y, w.z)
            .rotate(Vector3.Y, yaw)
            .rotate(Vector3.Y, spin)
            .scale(len / BAND_UNIT, 1f, 1f);
        inst.materials.get(0).set(ColorAttribute.createDiffuse(c));
        props.add(inst);
    }

    /**
     * Library, graveyard and exile, as pads beside each seat's rows, each with a pile of cards on
     * it that is as tall as the zone is big (owner's list 2026-10-20: "stacks that grow as cards
     * arrive, not flat pads with a number"). The height eases toward the count, so a card
     * arriving shows as the pile rising. Counts are drawn on top of each pile (TableHud).
     */
    void placeZones() {
        int n = snap.seats.size();
        if (pileHeight.length != n) pileHeight = new float[n][3];
        float ease = Math.min(1f, frameDt * 6f);
        for (int i = 0; i < n; i++) {
            float angle = seatAngle(i, n);
            Snapshot.SeatV s = snap.seats.get(i);
            int[] counts = { s.librarySize, s.graveyardSize, s.exileSize };
            float lift = zoneLift(i);
            for (int k = 0; k < 3; k++) {
                Vector3 w = rotateY(zoneX(k), SEAT_DIST + 1.7f - k * 1.45f, angle);
                float yaw = (float) Math.toDegrees(-angle);
                // The pad stands on something rather than hovering. The stand is what grows
                // as the board pushes the zone up - a slab floating in mid-air with nothing
                // under it looked exactly as odd as it sounds (the owner, 2026-10-22).
                if (lift > 0.001f) {
                    ModelInstance stand = inst(zoneStandModel);
                    stand.transform.setToTranslation(w.x, lift / 2f, w.z)
                        .rotate(Vector3.Y, yaw).scale(1f, lift, 1f);
                    props.add(stand);
                }
                // A rim round the tray, the same as the command zone's - the owner pointed at
                // that one and asked for "something like this for each of the card piles". It is
                // the command pad's own model, scaled down to sit just outside this tray, so the
                // four zones are one set of furniture rather than three flat patches and a tray.
                ModelInstance rim = inst(commandPadModel);
                rim.transform.setToTranslation(w.x, lift - 0.004f, w.z).rotate(Vector3.Y, yaw)
                    .scale((CARD_W * 0.88f + 0.20f) / (CARD_W + 0.24f), 1f,
                           (CARD_H * 0.88f + 0.20f) / (CARD_H + 0.24f));
                props.add(rim);
                ModelInstance inst = inst(zoneModel);
                inst.transform.setToTranslation(w.x, lift + 0.012f, w.z).rotate(Vector3.Y, yaw);
                // The pad shows round the pile as a rim, in the owner's seat colour (the colour of
                // their name over these pads), darkened to sit on the felt - so whose piles these
                // are reads at a glance, as the owner asked. The pile keeps its zone colour.
                Color own = zoneLabelColour(s);
                inst.materials.get(0).set(ColorAttribute.createDiffuse(own.r * 0.42f, own.g * 0.42f, own.b * 0.42f, 1f));
                props.add(inst);
                float target = pileTarget(counts[k]);
                float h = pileHeight[i][k];
                h = h + (target - h) * ease;
                if (Math.abs(target - h) < 0.0005f) h = target;
                pileHeight[i][k] = h;
                if (h < 0.001f) continue;                       // empty zone: just the pad
                ModelInstance pile = inst(pileModel);
                // The pile model is one unit tall and centred, so scale, then lift by half.
                pile.transform.setToTranslation(w.x, lift + 0.012f + PAD_TOP + h / 2f, w.z).rotate(Vector3.Y, yaw).scale(1f, h, 1f);
                pile.materials.get(0).set(ColorAttribute.createDiffuse(PILE_COLOURS[k]));
                props.add(pile);
                // Graveyard and exile are public: their top card lies face up on the pile (the
                // lists are most recent first). Scenery, not a card you can hover or click, so a
                // click on the pile still opens it to browse. A face-down exile shows its back.
                java.util.List<Snapshot.CardV> zone = k == 1 ? s.graveyard : k == 2 ? s.exile : null;
                if (zone != null && !zone.isEmpty() && art != null) {
                    com.badlogic.gdx.graphics.Texture tex = art.get(zone.get(0));
                    if (tex != null) {
                        ModelInstance top = inst(faceModel);
                        top.transform.setToTranslation(w.x, lift + 0.012f + PAD_TOP + h + 0.004f, w.z).rotate(Vector3.Y, yaw).scale(0.76f, 1f, 0.76f);
                        top.materials.get(0).set(com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute.createDiffuse(tex));
                        top.materials.get(0).set(FACE_LIT);
                        props.add(top);
                    }
                }
            }
        }
    }

    /** A pile's height for this many cards: 6 mm a card, capped at 100 cards (a full library). */
    static float pileTarget(int cards) {
        return Math.min(Math.max(cards, 0), PILE_CAP) * PILE_PER_CARD;
    }

    /** Where a zone's count label sits: just above the top of its pile. */
    float pileTop(int seat, int zone) {
        if (seat < 0 || seat >= pileHeight.length) return PAD_TOP;
        return zoneLift(seat) + PAD_TOP + pileHeight[seat][zone];
    }

    static final float PAD_TOP = 0.015f, PILE_PER_CARD = 0.006f;

    /** Half the width of a zone pad (zoneModel is CARD_W * 0.88 across), for keeping it in the wedge. */
    static final float ZONE_PAD_HALF = CARD_W * 0.44f;

    /**
     * Where a seat's library / graveyard / exile pad sits across the table.
     *
     * Never further out than 5.3, which is where they have always been, and never outside the
     * seat's own wedge. They were at a flat 5.3 whatever the shape of the table, so the exile pad
     * - the nearest one in, where the wedge is narrowest - sat outside the frame drawn round the
     * player's own space, and a player's piles were not in their own half (the owner,
     * 2026-10-22).
     *
     * Shared by the pads and by everything that has to find one - a card sliding into a
     * graveyard, the pile a drawn card comes off - so they cannot drift apart.
     */
    float zoneX(int pad) {
        float z = SEAT_DIST + 1.7f - pad * 1.45f;
        return Math.min(5.3f, wedgeHalf(z) - ZONE_PAD_HALF - 0.08f);
    }
    static final int PILE_CAP = 100;
    /** Library like a card back, graveyard dark, exile pale violet - three kinds of pile at a glance. */
    static final Color[] PILE_COLOURS = {
        new Color(0.30f, 0.22f, 0.14f, 1f), new Color(0.16f, 0.16f, 0.18f, 1f), new Color(0.55f, 0.50f, 0.70f, 1f) };
    /** Eased pile heights, [seat][library, graveyard, exile]. */
    float[][] pileHeight = new float[0][3];

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
            // Set every frame, lit or dim, because pooled face instances are reused across cards.
            boolean dim = targeting != null && seat != null && !targeting.legal.contains(c.id);
            d.face.materials.get(0).set(dim ? FACE_DIM : FACE_LIT);
        }
        // Targeting mode outranks the other markers: legal targets violet, picked ones gold.
        if (targeting != null && seat != null && targeting.legal.contains(c.id)) {
            d.marker = inst(targeting.picked.contains(c.id) ? pickedModel : targetModel);
            d.marker.transform.set(d.inst.transform).translate(0f, MARKER_DROP, 0f);
            drawn.add(d);
            return;
        }
        // Declaring attackers / blockers: a creature that may act glows green until assigned, then
        // red (attacking) or blue (blocking); the attackers being blocked keep their red slab.
        Model planMarker = null;
        if (combatPlan != null && seat != null) {
            if (combatPlan.attackSeat.containsKey(c.id)) planMarker = attackModel;
            else if (combatPlan.blockOn.containsKey(c.id)) planMarker = blockModel;
            else if (combatPlan.attackers.contains(c.id)) planMarker = attackModel;
            else if (combatPlan.mine.contains(c.id)) planMarker = selectModel;
        }
        // Paying mana: lands that can pay glow green, chosen ones gold.
        if (manaPlan != null && seat != null) {
            if (manaPlan.tapped.contains(c.id)) planMarker = pickedModel;
            else if (manaPlan.usable.contains(c.id)) planMarker = selectModel;
        }
        if (planMarker != null) {
            d.marker = inst(planMarker);
            d.marker.transform.set(d.inst.transform).translate(0f, MARKER_DROP, 0f);
            drawn.add(d);
            return;
        }
        // Owner's list 2026-10-20: while one of a permanent's abilities is on the stack, or its
        // abilities are being chosen between, the permanent itself is marked in the ability
        // ribbon's teal - otherwise you have to work out from the stack which card on the table it
        // came from.
        if (seat != null && abilitySourceIds().contains(c.id)) {
            d.marker = inst(sourceModel);
            d.marker.transform.set(d.inst.transform).translate(0f, MARKER_DROP, 0f);
            drawn.add(d);
            return;
        }
        // Forge only publishes a selectable list for prompts that ask you to choose a card
        // ("discard one"), not for ordinary priority - there it outlines playable cards by another
        // route and accepts a click on anything. So this marker means "Forge has offered this
        // card", which is a narrower and more honest claim than "you can play this".
        if (!snap.selectableIds.isEmpty() && snap.selectableIds.contains(c.id)) {
            d.marker = inst(selectModel);
            d.marker.transform.set(d.inst.transform).translate(0f, MARKER_DROP, 0f);
        } else if (c.attacking) {
            d.marker = inst(attackModel);
            d.marker.transform.set(d.inst.transform).translate(0f, MARKER_DROP, 0f);
        }
        drawn.add(d);
    }
}
