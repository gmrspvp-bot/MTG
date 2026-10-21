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
 * Bottom of the Table3D chain: the table's state, and the small helpers everything else uses.
 *
 * Table3D was one ~2,500-line class, and every merge conflict between the two agents landed in
 * it. It is now a chain of abstract classes, one concern per file, each extending the one before:
 *
 *   TableBase    fields, the Drawn / Pose records, geometry and maths helpers   (this file)
 *   TableCamera  the camera: seats, orbit, lift, zoom
 *   TableLayout  placing everything on the table each frame; the instance pool; the surround
 *   TableScene   3D extras drawn after the cards: combat and target arcs, the scoreboard
 *   TableHud     every 2D overlay: panels, labels, prompt bar, hover card, log, banners
 *   TableInput   keyboard and mouse, picking
 *   Table3D      the libGDX entry points: create, render, resize, dispose
 *
 * The code was moved, not rewritten: every method is as it was, members lost `private` so the
 * layers can see each other, and a lower layer that needs something from a higher one declares
 * it abstract here. To add a feature, put it in the file for its concern.
 */
abstract class TableBase extends ApplicationAdapter {

    // ---- table geometry, in world units (1 unit ~ one card width) -------------------------------
    static final float CARD_W = 1.0f, CARD_H = 1.4f, CARD_THICK = 0.04f;

    static final float GAP = 0.18f;

    // 14, was 11: seated, the bottom of the view reached past the near edge to the dark floor -
    // a black band along the bottom of the screen (owner, 2026-10-21). Only the cloth and rails
    // reach further; nothing laid out on the table moves.
    static final float TABLE_HALF = 14f;

    /**
     * How far a seat's rows sit from the middle of the table.
     *
     * 6.4 before: the boards began close enough in that the screens, the stack and the four
     * mats were all competing for the same bit of felt, and a screen set out to one side had
     * nowhere to stand that was not in front of somebody's cards (the owner, 2026-10-22). This
     * is the one number that buys room in the middle, and it buys width as well - the wedge a
     * seat gets opens out with distance, so every board is wider for it too.
     */
    static final float SEAT_DIST = 7.3f;

    Table3D.Supplier source;      // set once, by Table3D's constructor

    PerspectiveCamera cam;

    ModelBatch modelBatch;

    Environment env;

    ModelBuilder builder;

    SpriteBatch ui;

    BitmapFont font, fontBig;

    ShapeRenderer shapes;

    Model tableModel, cardModel, landModel, creatureModel, commanderModel, seatModel, seatPriorityModel;

    Model faceModel, attackModel, backModel, zoneModel, commandPadModel, seatLostModel;
    /** A zone's pile of cards: one unit tall, scaled to the zone's size (TableLayout.placeZones). */
    Model pileModel;
    /** A battlefield's raised plinth: a unit cube, scaled to the seat's rows (TableLayout.placePlinth). */
    Model plinthModel;
    /** A centre monitor's glowing glass and its stand (TableLayout.placeMonitors). */
    Model screenModel, standModel;

    Model bandCreature, bandOther, bandLand, monitorModel, selectModel, hoverModel;
    /** Floating mana: one white sphere coloured per bead, and the little tray under a stack. */
    Model beadModel, beadTrayModel;
    /** The stand a library / graveyard / exile / command pad stands on as the board fills. */
    Model zoneStandModel;

    Model seatGlowModel, stackFrameModel;

    /** One unit-long bar; four of them, scaled and turned, frame each seat's area. */
    Model seatEdgeModel;

    /** A card-sized slab: the copies under the top one in a stack of identical permanents. */
    Model stackShimModel;

    /** Band slabs are built one unit wide and scaled to the row, so there is one model per kind. */
    static final float BAND_UNIT = 1f;

    ModelInstance tableInst;

    final List<ModelInstance> seatPads = new ArrayList<>();

    /** Scenery with no card behind it: zone pads and other players' face-down hands. */
    final List<ModelInstance> props = new ArrayList<>();

    /** Click targets for the seats, in Snapshot.seats order. Rebuilt with the monitors. */
    final List<BoundingBox> seatPick = new ArrayList<>();

    int hoveredSeat = -1;

    /** How far a held card is tilted up towards its owner, in degrees. */
    static final float HAND_TILT = 62f;

    CardArt art;

    SearchPanel search;
    /** Text choices (modes, players, colours, types) and number picks (X). Not wired to Forge yet. */
    final OptionPanel options = new OptionPanel();
    final NumberPicker numbers = new NumberPicker();
    /** Typing an answer (Forge's free-text showInputDialog). */
    final TextPanel text = new TextPanel();
    /** Scry / surveil / trigger ordering. Made in create(), since it needs the card art. */
    OrderPanel order;
    /** The opening hand: keep / mulligan, and the London bottom pick. Made in create(). */
    MulliganPanel mulligan;
    /** Results and Rematch / Next / Quit when a game ends; opens by itself once per game. */
    final EndScreen endScreen = new EndScreen();
    boolean endShown;
    /** Splitting an attacker's damage among blockers. Made in create(). */
    DamagePanel damage;

    /**
     * Targeting mode (look only until wired to Forge's target selection): while set, legal
     * targets glow violet, everything else on the battlefield dims, an arc follows the mouse from
     * the source, and clicks pick targets instead of playing cards. Null when not targeting.
     */
    static final class Targeting {
        int sourceId = Integer.MIN_VALUE;
        final java.util.Set<Integer> legal = new java.util.HashSet<>();
        final java.util.List<Integer> picked = new ArrayList<>();
        int max = 1;
        String what = "target";
        /** Driven by Forge (InputSelectTargets): clicks go to Forge, Forge decides when it is done. */
        boolean wired;
    }

    Targeting targeting;
    Model targetModel, pickedModel, blockModel;
    /** Teal, the ability ribbon's colour: a permanent whose ability is on the stack or being chosen. */
    Model sourceModel;

    /**
     * Declaring attackers or blockers by dragging (look only until wired to Forge's InputAttack /
     * InputBlock). Attacking: drag one of `mine` onto an opponent (their monitor, pad or one of
     * their cards). Blocking: drag one of `mine` onto one of `attackers`. Drop it back on itself,
     * or anywhere else, to take the assignment off. SPACE confirms, ESC cancels.
     */
    static final class CombatPlan {
        final boolean blocking;
        final java.util.Set<Integer> mine = new java.util.HashSet<>();
        final java.util.Set<Integer> attackers = new java.util.HashSet<>();
        final java.util.Map<Integer, Integer> attackSeat = new java.util.LinkedHashMap<>();
        final java.util.Map<Integer, Integer> blockOn = new java.util.LinkedHashMap<>();
        int dragging = Integer.MIN_VALUE;
        /**
         * While blocking: the attacker new blockers go on, which is Forge's own model.
         * InputBlock keeps a "current attacker" and every creature you click after choosing one
         * blocks IT. Dragging an arrow per blocker hid that, and when two of the owner's blockers
         * both landed on one of three identical Robot tokens there was nothing on screen saying
         * which attacker they had gone to until the damage was dealt (2026-10-22).
         */
        int current = Integer.MIN_VALUE;
        /** Driven by Forge (InputAttack / InputBlock): drops become clicks, the arcs come from the game. */
        boolean wired;
        CombatPlan(boolean blocking) { this.blocking = blocking; }
    }

    CombatPlan combatPlan;

    /** Where the mouse ray meets the table top (y = 0) as {x, z}, or null if it misses. */
    float[] mouseOnTable() {
        Ray ray = cam.getPickRay(Gdx.input.getX(), Gdx.input.getY());
        if (Math.abs(ray.direction.y) < 1e-4f) return null;
        float k = -ray.origin.y / ray.direction.y;
        if (k <= 0f) return null;
        return new float[] { ray.origin.x + ray.direction.x * k, ray.origin.z + ray.direction.z * k };
    }

    /** The seat whose side of the table (x, z) is on, by direction from the centre; -1 near the middle. */
    int seatAt(float x, float z) {
        int n = snap.seats.size();
        if (n == 0 || x * x + z * z < 2.5f * 2.5f) return -1;
        int best = -1;
        float bestDot = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            Vector3 d = rotateY(0f, 1f, seatAngle(i, n));
            float dot = d.x * x + d.z * z;
            if (dot > bestDot) { bestDot = dot; best = i; }
        }
        return best;
    }

    /**
     * Paying a cost by choosing lands (look only until wired to Forge's InputPayMana): lands that
     * can pay glow, a click taps one (gold), and the banner counts the cost down. ESC cancels.
     */
    static final class ManaPlan {
        String cost = "";
        int total;
        final java.util.Set<Integer> usable = new java.util.HashSet<>();
        final java.util.List<Integer> tapped = new ArrayList<>();
        /** Driven by Forge (InputPayMana...): clicks tap for real; Forge ends it once paid. */
        boolean wired;
    }

    ManaPlan manaPlan;
    /** Shared, never mutated: the card picture at full brightness, and dimmed for a non-target. */
    static final ColorAttribute FACE_LIT = ColorAttribute.createDiffuse(Color.WHITE);
    static final ColorAttribute FACE_DIM = ColorAttribute.createDiffuse(new Color(0.38f, 0.38f, 0.42f, 1f));

    /** True while any panel owns the keyboard and mouse, so the table must not react underneath it. */
    boolean panelOpen() {
        // Gui3D's Ask counts. It is a yes/no the 3D window owns, drawn over the board and
        // answered with SPACE and X - the same two keys the pad's A and X stand in for while a
        // panel is up. Leaving it out meant the controller could read the question and not
        // answer it (the owner, 2026-10-21).
        return (search != null && search.isOpen()) || options.isOpen() || numbers.isOpen()
            || (order != null && order.isOpen()) || (mulligan != null && mulligan.isOpen()) || endScreen.isOpen()
            || (damage != null && damage.isOpen()) || Gui3D.pendingAsk() != null
            // Found by auditing every overlay against this list rather than waiting for the
            // next screenshot. TextPanel takes typed characters, so the board reacting
            // underneath it is the bug-report-typing leak all over again; the help card is a
            // full-screen read where a click should not also play a card.
            || text.isOpen() || help.isOpen();
    }

    final BugReport bug = new BugReport();

    /** The keys, on screen, behind a button next to the cog. */
    final HelpCard help = new HelpCard();

    /** One drawable card plus where it is, so the same list serves rendering and picking. */
    static final class Drawn {
        ModelInstance inst;
        /** The card picture, laid just above the body. Null when Forge has no art cached for it. */
        ModelInstance face;
        /** A slab under an attacking creature, so combat reads at a glance from the overhead pose. */
        ModelInstance marker;
        BoundingBox bounds = new BoundingBox();
        Snapshot.CardV card;
        Snapshot.SeatV seat;
    }

    final List<Drawn> drawn = new ArrayList<>();

    Drawn hovered;

    /**
     * The controller's selection: which card it is on, and whether it - rather than the mouse -
     * is deciding what `hovered` is. By id, because the board is rebuilt every frame.
     */
    int padSelectedId = -1;

    boolean padSelecting;

    int lastMouseAt = -1;

    /**
     * Arranging your hand: the card picked up, and where in the hand it would go.
     *
     * Nothing is sent to Forge until it is dropped, so the whole move can be thought about and
     * abandoned. -1 means nothing is being moved. Forge keeps the hand in a real order and
     * reorders the zone itself, so the new order is the game's, not a trick of this window: it
     * survives drawing a card, and Forge's own window shows the same hand.
     */
    int grabbedId = -1;

    int grabIndex = -1;

    /**
     * Animation without giving up the per-frame rebuild. layout() still works out every card's
     * TARGET pose from the snapshot each frame, so the view always converges on the true state;
     * what persists between frames is only where each card was last DRAWN, keyed by card id.
     * Every frame each card eases from there towards its target. That turns tapping into a
     * quarter-turn and makes a card slide when it changes row or zone (hand -> stack ->
     * battlefield keeps its id), with no change-tracking anywhere else.
     */
    static final class Pose {
        float x, y, z, yaw, tilt;
        int seen;
    }

    final java.util.Map<Integer, Pose> poses = new java.util.HashMap<>();

    float frameDt;

    int frameNo;

    /** Ease rates, per second: about 0.25 s to finish a tap, a little longer to cross the table. */
    static final float TURN_RATE = 14f, MOVE_RATE = 9f;

    /** Moves p a frame's worth towards the target; returns p. Yaw takes the short way round. */
    Pose ease(Pose p, float x, float y, float z, float yaw, float tilt) {
        float m = 1f - (float) Math.exp(-MOVE_RATE * frameDt);
        float t = 1f - (float) Math.exp(-TURN_RATE * frameDt);
        p.x += (x - p.x) * m;
        p.y += (y - p.y) * m;
        p.z += (z - p.z) * m;
        float dYaw = ((yaw - p.yaw) % 360f + 540f) % 360f - 180f;
        p.yaw += dYaw * t;
        p.tilt += (tilt - p.tilt) * t;
        return p;
    }

    /** 0 = fully seated, 1 = fully overhead. Everything about the camera follows this. */
    float lift = 0f;

    float liftTarget = 0f;

    boolean manualOverride = false;

    boolean manualValue = false;

    /**
     * Whose seat we are looking from. The table itself never moves: the camera orbits around it,
     * which is what makes stepping between players read as walking round the table rather than
     * the world spinning.
     *
     * `orbit` is kept as a continuous angle rather than being wrapped into 0..2pi, so going from
     * the last seat back to the first turns the short way instead of unwinding all the way round.
     */
    int viewSeat = -1;

    /** Walk round to whoever is active each turn. Off unless asked for, on the phase panel. */
    boolean followTurn = false;

    float orbit = 0f;

    float orbitTarget = 0f;

    /** Mouse wheel: 1 = the designed distance, smaller is closer. Reset with DOWN / HOME. */
    float zoom = 1f;

    /** Where the view has been dragged to, in world units on the table plane. */
    float panX, panZ;

    /** How far the table can be dragged before it would leave the view entirely. */
    static final float PAN_LIMIT = TABLE_HALF * 1.4f;

    static final float ZOOM_MIN = 0.55f, ZOOM_MAX = 1.7f;

    /** Right-drag: radians of orbit per pixel of horizontal mouse movement. */
    static final float ORBIT_PER_PIXEL = 0.006f;

    int lastActiveSeat = -1;

    Snapshot snap = new Snapshot();

    /**
     * layout() rebuilds every placement each frame (see its comment - that is what keeps the view
     * from ever drifting from the snapshot), and it used to do so with fresh ModelInstances: ~90 a
     * frame, each copying its model's nodes, parts and materials, so several thousand short-lived
     * objects a second and GC stutter to match. Now each model keeps a list of instances that are
     * handed out again every frame. Everything that matters is overwritten on reuse: transforms are
     * set with setToTranslation / set(...), and the one material that changes - the card picture -
     * is set every frame in add(). Instances are never shared between two placements in a frame.
     */
    final java.util.Map<Model, List<ModelInstance>> pool = new java.util.HashMap<>();

    final java.util.Map<Model, int[]> poolUsed = new java.util.HashMap<>();

    Model feltModel, floorModel, railModel;

    com.badlogic.gdx.graphics.Texture feltTex, floorTex, woodTex, leatherTex;

    ModelInstance feltInst, floorInst;

    final List<ModelInstance> railInsts = new ArrayList<>();

    /** The padded rail round the table: how wide, and how far it stands above the felt. */
    static final float RAIL_W = 0.75f, RAIL_H = 0.26f;

    Model box(Color c, int attrs) {
        return builder.createBox(CARD_W, CARD_THICK, CARD_H,
            new Material(ColorAttribute.createDiffuse(c)), attrs);
    }

    /**
     * -Dforge.ui3d.shots=<dir>: save the frame as a PNG every forge.ui3d.shotEvery seconds (default
     * 0.5). Lets a change to the view be checked without anyone looking at the screen: an agent
     * cannot see the window, and Windows screen capture misses this GL surface (it comes out
     * white). Off unless the property is set.
     */
    static final String SHOTS = System.getProperty("forge.ui3d.shots");

    static final float SHOT_EVERY = Float.parseFloat(System.getProperty("forge.ui3d.shotEvery", "0.5"));

    float shotClock;

    int shotNo;

    boolean leftWasDownCog;

    boolean leftWasDownHelp;

    boolean leftWasDownRespond;

    boolean leftWasDownBoard;

    boolean leftWasDown;

    /** Seats sit at fixed, absolute angles round the table; seat 0 is the far edge of the world. */
    static float seatAngle(int index, int n) {
        return (float) (index * 2 * Math.PI / n);
    }

    static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** Index of the seat whose turn it is, or -1. */
    int activeSeat() {
        for (int i = 0; i < snap.seats.size(); i++) if (snap.seats.get(i).active) return i;
        return -1;
    }

    /** Smoothstep, so the move eases out of one pose and into the other instead of starting abruptly. */
    static float smooth(float x) {
        return x * x * (3f - 2f * x);
    }

    /** Per stack item, bottom first like Snapshot.stack: {x, y, z} of the bottom of its card. */
    final List<float[]> stackAt = new ArrayList<>();

    /**
     * Seats are laid out by rotating a point on the near edge around the table's centre.
     * angle 0 is the near edge (you); a four-player pod then goes anticlockwise at 90 degree steps.
     * Rotating local (x, z) by `angle` about Y:  wx = x*cos - z*sin,  wz = x*sin + z*cos.
     */
    static Vector3 rotateY(float lx, float lz, float angle) {
        float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
        return new Vector3(lx * cos - lz * sin, 0f, lx * sin + lz * cos);
    }

    /** A seat's centre: the middle of its rows, pushed out from the table centre. */
    Vector3 seatPos(float angle, float dist) {
        return rotateY(0f, dist, angle);
    }

    /** Host id -> {attachment card, its controller's seat}, rebuilt each frame by layout(). */
    final java.util.Map<Integer, List<Object[]>> attachedHere = new java.util.HashMap<>();

    /** Host id -> where the host was placed this frame: {x, z, yaw, its seat's yaw}. */
    final java.util.Map<Integer, float[]> hostTargets = new java.util.HashMap<>();

    /** How much of each attachment peeks out past the top edge of its host (and of the one before). */
    static final float ATTACH_PEEK = 0.40f;

    /** How far each band sits from the middle of the table. */
    /** How high the stack bar floats above the table. */
    // 5.2 put the top of the cards off the screen in the default seated view; 4.5 clears the
    // table and the hand without leaving the frame.
    static final float STACK_Y = 4.5f;

    /** The stack is drawn larger than a table card: it is the thing you must not miss. */
    static final float STACK_SCALE = 2.1f;

    static final float STACK_FRAME_UNIT = 1f;

    /** How far each player's little screen sits from the middle of the table. */
    static final float MONITOR_DIST = 2.45f;

    static final float ROW_CREATURES = SEAT_DIST - 2.1f;

    static final float ROW_OTHERS    = SEAT_DIST - 0.5f;

    static final float ROW_LANDS     = SEAT_DIST + 1.3f;

    /** Where a seat's command zone sits, in its own frame: the other side from library/gy/exile. */
    static final float COMMAND_X = -5.3f, COMMAND_Z = SEAT_DIST + 0.3f;

    /**
     * Floating mana, drawn as beads in six trays along the near edge of each board - white, blue,
     * black, red, green, then colourless, left to right as the owner sits.
     *
     * The pool was a line of text in the corner panel, which is the one place nobody looks while
     * they are paying for something, and only for your own seat: there was no way to see that an
     * opponent was holding two untapped Islands' worth of blue (the owner, 2026-10-22). Beads on
     * the board are the same thing you would push across a real table.
     *
     * BEAD_Z sits in the gap between the seat frame (EDGE_NEAR 3.1) and the creature band (3.39),
     * so the tray never lands on a card or on the frame itself.
     */
    /**
     * How far back a full board is pushed, and how high its zone pads float as it goes.
     *
     * A board that fills its width is drawn closer and closer to the next seat's, and from a
     * seated view it ends up over the top of their cards and over its own library and graveyard
     * pads (the owner, 2026-10-22). So a crowded board backs away from the middle of the table
     * instead, and the pads - which are the things you have to be able to click - rise above the
     * cards rather than being buried by them.
     */
    /**
     * How far a mat, and the frame round it, stop short of the line dividing this seat from the
     * next.
     *
     * The frame used to sit ON that line - which belongs to both seats equally, so the moment
     * the mats grew to the full length of it two neighbouring frames were drawn along the same
     * line and crossed each other (the owner, 2026-10-22). Each one stays on its own side of it
     * now, with felt between.
     */
    static final float EDGE_INSET = 0.05f;
    static final float MAT_INSET = 0.30f;

    static final float PUSH_MAX = 0.8f;
    static final float ZONE_LIFT = 0.52f;

    /** How far each seat's rows have been pushed back this frame, by seat index. */
    float[] seatPush = new float[0];

    /**
     * A colour of their own for each player's mat - the same leather, dyed differently.
     *
     * Muted on purpose: a mat is what a player's cards are read against, so it should say whose
     * half of the table this is without ever competing with what is laid on it.
     */
    static final Color[] MAT_TINTS = {
        new Color(0.95f, 0.86f, 0.74f, 1f),
        new Color(0.74f, 0.88f, 0.96f, 1f),
        new Color(0.80f, 0.95f, 0.82f, 1f),
        new Color(0.97f, 0.80f, 0.82f, 1f),
        new Color(0.88f, 0.82f, 0.98f, 1f),
        new Color(0.98f, 0.94f, 0.76f, 1f),
    };

    /** Where each seat's panel of the centre screen ended up, for the readout drawn over it. */
    final java.util.List<float[]> monitorAt = new java.util.ArrayList<>();

    /**
     * The full placement of each screen's glass, so the readout can be drawn ON it.
     *
     * The numbers used to be drawn in screen space at the projected position of the screen,
     * which means they always faced the camera square on while the glass behind them was turned
     * and tilted - "like you have got a display and then figurines on it" (the owner,
     * 2026-10-22). With the glass's own matrix the text can be laid in its plane instead, and
     * then it leans with the screen as a screen's contents should.
     */
    final java.util.List<com.badlogic.gdx.math.Matrix4> monitorXf = new java.util.ArrayList<>();

    /**
     * One mat mesh per seat, the shape of that seat's wedge of the table.
     *
     * A trapezium cannot be made by scaling a rectangle, so each is its own small mesh - four
     * corners, built when its size changes and kept until it changes again. Which is rarely: a
     * mat only changes shape when the board behind it backs away (boardPush) or a player leaves.
     */
    Model[] matModels = new Model[0];
    float[][] matShape = new float[0][];

    static final float BEAD_D = 0.19f;
    static final float BEAD_Z = 3.26f;
    static final float BEAD_STEP = BEAD_D * 1.6f;        // between the six trays
    static final float BEAD_RISE = BEAD_D * 0.62f;       // one bead on top of another
    /** Past this the stack stops growing and a count is drawn over it instead (TableHud). */
    static final int BEAD_SHOW = 8;

    /** Where a seat's bead stacks ended up, for the count over a tall one. */
    static final class BeadsAt {
        final float x, z, top;
        final int count, colour;
        BeadsAt(float x, float z, float top, int count, int colour) {
            this.x = x; this.z = z; this.top = top; this.count = count; this.colour = colour;
        }
    }

    final java.util.List<BeadsAt> beads = new java.util.ArrayList<>();

    /**
     * The rectangle framing one seat's territory, in that seat's own frame. Wide enough to take
     * in the command pad on one side (x -5.92 at its edge) and the library/graveyard/exile pads
     * on the other (x 5.74), and deep enough for the creature row through to the far zone pad,
     * with clear air either end so the frame never lands on top of a band or a pad - the bands
     * sit at z 3.39..5.21 and 6.79..8.61, the pads at x 4.86..5.74.
     */
    // Tied to SEAT_DIST, not written out: the frame goes round the mat, and the mat is placed
    // from the rows, which are placed from SEAT_DIST. Fixed numbers here meant that opening the
    // middle of the table out left the frames behind, round nothing (2026-10-22).
    static final float EDGE_X = 6.3f;

    /**
     * How far a player's mat runs, from the middle of the table out to their own edge.
     *
     * It used to stop at the far side of the lands row, which left the library, graveyard,
     * exile and command trays standing on bare felt outside it, and the frame round the mat
     * cutting between a player and their own piles (the owner drew the right outline in red,
     * 2026-10-22). A mat is the player's whole place at the table: everything of theirs is on
     * it.
     */
    static final float MAT_NEAR = SEAT_DIST - 3.6f, MAT_FAR = SEAT_DIST + 4.3f;

    /** The frame goes round the outside of the mat, with a hand's width of felt showing. */
    static final float EDGE_NEAR = MAT_NEAR - 0.22f, EDGE_FAR = MAT_FAR + 0.22f;

    /** How thick the frame reads from above, and how far it floats over the felt (top at -0.015). */
    // ABOVE the mat, not under it. The mat used to be a raised plinth and the frame lay on the
    // felt beside it; now the mat is a flat wedge at FIELD_RAISE, so a frame at -0.004 was
    // simply buried - "you have obscured the markers for turn and response" (the owner,
    // 2026-10-22).
    static final float EDGE_W = 0.09f, EDGE_Y = 0.126f;      // FIELD_RAISE (0.12) + a hair

    /**
     * Whose turn it is, and who the game is actually waiting on. In a four-player game the
     * question "is it me?" was being answered by reading names off a panel; a coloured frame
     * round the player's own half of the table answers it from across the room.
     */
    static final Color EDGE_ACTIVE     = new Color(0.16f, 0.78f, 0.34f, 1f);   // green: your turn
    static final Color EDGE_RESPONDING = new Color(0.98f, 0.78f, 0.12f, 1f);   // yellow: responding
    static final Color EDGE_LOST       = new Color(0.34f, 0.11f, 0.12f, 1f);
    static final Color EDGE_IDLE       = new Color(0.10f, 0.18f, 0.25f, 1f);

    /** The frame colour for a seat: green on its turn, yellow when the game waits on it. */
    static Color edgeColour(Snapshot.SeatV s) {
        if (s.lost) return EDGE_LOST;
        if (s.active) return EDGE_ACTIVE;
        if (waitingOn(s)) return EDGE_RESPONDING;
        return EDGE_IDLE;
    }

    /**
     * Is the game really waiting on this seat?
     *
     * Forge's PlayerView.getHasPriority is a snapshot: PhaseHandler writes it at the end of a
     * loop step and then the game thread sits inside somebody's Input for as long as that player
     * takes, with nothing updating the flag meanwhile. That had the yellow frame on the owner's
     * own seat right through another player's combat (2026-10-22).
     *
     * For our OWN seat the window can check instead of believing, and it does it HERE, as the
     * frame is drawn, rather than when the snapshot is built - the answer changes between game
     * events, so a snapshot is the wrong place to keep it. Other seats are Forge's word only.
     */
    static boolean waitingOn(Snapshot.SeatV s) {
        if (!s.hasPriority) return false;
        if (!s.isYou || !Gui3D.active()) return true;
        return Gui3D.beingAsked();
    }

    /**
     * The same four states as the frame, but as text over the felt. EDGE_IDLE is almost black -
     * right for a frame lying on dark felt, unreadable as a word - so the resting state is a
     * pale blue instead, and your own seat is tinted so you can find yourself immediately.
     */
    static Color zoneLabelColour(Snapshot.SeatV s) {
        if (s.lost) return new Color(0.46f, 0.38f, 0.38f, 1f);
        if (s.active) return new Color(0.42f, 0.95f, 0.52f, 1f);
        if (waitingOn(s)) return new Color(1f, 0.86f, 0.30f, 1f);
        if (s.isYou) return new Color(0.62f, 0.95f, 1f, 1f);
        return new Color(0.58f, 0.76f, 0.88f, 1f);
    }

    static final boolean HANGING_SCORE =
        "true".equalsIgnoreCase(System.getProperty("forge.ui3d.hangingscore", "false"));

    /**
     * A jumbotron hanging over the middle of the table, above the stack bar: one face turned to
     * each seat, all showing the same board - the turn, and every player's life, the active
     * player in gold, your own row in cyan, "> " on whoever holds priority. It adds to the four
     * little monitors on the table (which carry commander damage too) rather than replacing them:
     * this is the at-a-glance read from your chair, the way a scoreboard is in a stadium.
     *
     * The faces are a FrameBuffer texture, redrawn only when something on it changes. They are
     * drawn unlit, like a screen, and the whole thing fades out as the camera lifts overhead -
     * from up there it would sit right in the middle of the view, and the overhead pose already
     * shows everything. Height chosen so the bottom clears the stack's raised top card and the top
     * stays inside the seated camera's view.
     */
    static final float SB_Y = 5.4f, SB_FACE_W = 3.6f, SB_FACE_H = 1.35f;

    static final int SB_TEX_W = 720, SB_TEX_H = 270;     // the face's own proportions

    com.badlogic.gdx.graphics.glutils.FrameBuffer sbBuffer;

    Model sbFaceModel, sbBodyModel;

    BitmapFont sbFont;

    /** Sound cues for the moments that need you (see Cues). */
    Cues cues;

    String sbKey = "";

    /**
     * Combat, drawn as arcs over the table: one from each attacker to what it is attacking (the
     * player's seat, or the planeswalker / battle itself), with an arrowhead at the far end and
     * pulses running along it so the direction reads even from the side.
     *
     * An attack that is getting through is bright red-orange. A blocked one fades, and each blocker
     * gets its own short blue arc onto the attacker it is stopping - so at a glance you see who is
     * really taking damage. Both ends come from `poses`, i.e. where the cards are DRAWN this frame,
     * so the arcs follow a card while it slides or taps rather than pointing at where it will be.
     *
     * Ribbons lie flat (wide in the table's plane): the camera lifts overhead in combat, and from
     * there a flat ribbon is what shows. Drawn after the cards with depth test on but depth writes
     * off, so cards in front still hide them and overlapping arcs blend instead of cutting holes.
     */
    com.badlogic.gdx.graphics.glutils.ImmediateModeRenderer20 arcs;

    float arcClock;

    static final int ARC_SEGMENTS = 28;

    static final float ARC_WIDTH = 0.16f, ARROW_LEN = 0.55f;

    static final String FORCE_HOVER = System.getProperty("forge.ui3d.hover");

    static final String OPEN_PILE = System.getProperty("forge.ui3d.openPile");

    boolean pileOpened;

    /** The event log is on unless L has hidden it. */
    boolean showLog = !"false".equalsIgnoreCase(System.getProperty("forge.ui3d.log"));

    static final float LOG_W = 400f, LOG_LINE = 17f;

    /** The text cut to fit a pixel width, with an ellipsis if it had to be cut. */
    String fit(String s, float maxPx, GlyphLayout gl) {
        if (s == null) return "";
        s = s.replace('\n', ' ');
        gl.setText(font, s);
        if (gl.width <= maxPx) return s;
        int lo = 0, hi = s.length();
        while (lo < hi) {                                   // longest prefix that fits with "..."
            int mid = (lo + hi + 1) / 2;
            gl.setText(font, s.substring(0, mid) + "...");
            if (gl.width <= maxPx) lo = mid; else hi = mid - 1;
        }
        return s.substring(0, lo) + "...";
    }

    /** Screen rects of the prompt bar's two buttons, so drawing and hit-testing cannot drift. */
    final float[] okBtn = { 0f, 0f, 0f, 0f }, cancelBtn = { 0f, 0f, 0f, 0f };

    /** "Next phase": drawn by TableHud, clicked in TableInput, so the rect lives here. */
    final float[] nextBtn = { 0f, 0f, 0f, 0f };

    boolean nextOn;

    static boolean inRect(float[] r, float mx, float my) {
        return r[2] > 0f && mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3];
    }

    /** Counts printed over each seat's library / graveyard / exile pads. */
    /** Forge's phases, in order, with the short labels a phase strip wants. */
    static final String[][] PHASES = {
        { "UNTAP", "Untap" }, { "UPKEEP", "Upkeep" }, { "DRAW", "Draw" },
        { "MAIN1", "Main 1" },
        { "COMBAT_BEGIN", "Begin combat" }, { "COMBAT_DECLARE_ATTACKERS", "Attackers" },
        { "COMBAT_DECLARE_BLOCKERS", "Blockers" }, { "COMBAT_FIRST_STRIKE_DAMAGE", "First strike" },
        { "COMBAT_DAMAGE", "Damage" }, { "COMBAT_END", "End combat" },
        { "MAIN2", "Main 2" },
        { "END_OF_TURN", "End step" }, { "CLEANUP", "Cleanup" },
    };

    /** Screen rect of the follow-turn tickbox, so the click test and the drawing cannot drift. */
    float[] followBox = { 0f, 0f, 0f, 0f };

    /**
     * "Check with me for responses." On, the game stops whenever you could act on somebody
     * else's turn, as it always has. Off, those windows are passed for you and the table runs on
     * until your own turn comes round.
     *
     * This is the thing Forge's auto-yield was doing behind everyone's back, and it ate four of
     * the owner's turns because nothing said it was on (2026-10-21). The difference is entirely
     * that this one is a switch you can see, it says what it does, and it NEVER passes your own
     * turn - only the priority you get during other people's.
     */
    boolean askResponses = true;

    float[] respondBox = { 0f, 0f, 0f, 0f };

    /** The prompt we last auto-passed, so one decision is answered once and not every frame. */
    Object lastAutoPassed;

    // Panel geometry. One place, because the background is a ShapeRenderer pass and the labels
    // are a SpriteBatch pass: two passes that cannot be interleaved and must agree to the pixel.
    /**
     * How much bigger the whole panel is than it was first drawn. Everything below is a base
     * size multiplied by this, and drawPhasePanel scales the font to match, so the two passes
     * stay in agreement and the tickbox and priority rows stay clickable where they are drawn.
     * Raise this one number to make the panel bigger; nothing else needs touching.
     * -Dforge.ui3d.panelscale overrides it, so a size can be tried without a rebuild.
     */
    static final float PP_SCALE =
        Math.max(1f, Math.min(2.5f, Float.parseFloat(System.getProperty("forge.ui3d.panelscale", "1.45"))));

    static final float PP_X = 14f, PP_W = 186f * PP_SCALE;

    static final float PP_ROW = 21f * PP_SCALE;          // height of one step row

    static final float PP_HEAD = 62f * PP_SCALE;         // turn line, seat line and the rule under them

    static final float PP_FOOT = 34f * PP_SCALE;         // the tickbox

    static final float PP_GAP = 7f * PP_SCALE;           // space inserted between groups

    /**
     * Where the turn changes character: beginning steps, first main, combat, second main, end.
     * The column leaves a gap at each, so combat reads as a block rather than six identical rows.
     * That is what you actually track in a four-player game - before combat, in it, or past it.
     */
    static final int[] PP_GROUPS = { 3, 4, 10, 11 };

    // The priority block under the steps: a heading with the stack depth, then one row per seat.
    static final float PB_HEAD = 22f * PP_SCALE, PB_ROW = 18f * PP_SCALE;

    /** "Captain America, First Avenger" -> "Captain America", so the tracker stays narrow. */
    static String shortName(String s) {
        if (s == null) return "?";
        int comma = s.indexOf(',');
        return comma > 0 ? s.substring(0, comma) : s;
    }

    /** Crude word wrap against a pixel width - enough for rules text in the hover panel. */
    List<String> wrap(String text, float maxPx) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        GlyphLayout gl = new GlyphLayout();
        for (String para : text.split("\n")) {
            StringBuilder line = new StringBuilder();
            for (String word : para.split(" ")) {
                String probe = line.length() == 0 ? word : line + " " + word;
                gl.setText(font, probe);
                if (gl.width > maxPx && line.length() > 0) {
                    out.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(probe);
                }
            }
            if (line.length() > 0) out.add(line.toString());
        }
        return out;
    }
}
