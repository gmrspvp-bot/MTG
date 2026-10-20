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

    static final float TABLE_HALF = 11f;

    static final float SEAT_DIST = 6.4f;     // how far a seat's rows sit from the centre

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

    Model bandCreature, bandOther, bandLand, monitorModel, selectModel, hoverModel;

    Model seatGlowModel, stackFrameModel;

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
    /** Scry / surveil / trigger ordering. Made in create(), since it needs the card art. */
    OrderPanel order;

    /** True while any panel owns the keyboard and mouse, so the table must not react underneath it. */
    boolean panelOpen() {
        return (search != null && search.isOpen()) || options.isOpen() || numbers.isOpen()
            || (order != null && order.isOpen());
    }

    final BugReport bug = new BugReport();

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

    com.badlogic.gdx.graphics.Texture feltTex, floorTex;

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
    static final float MONITOR_DIST = 2.0f;

    static final float ROW_CREATURES = SEAT_DIST - 2.1f;

    static final float ROW_OTHERS    = SEAT_DIST - 0.5f;

    static final float ROW_LANDS     = SEAT_DIST + 1.3f;

    /** Where a seat's command zone sits, in its own frame: the other side from library/gy/exile. */
    static final float COMMAND_X = -5.3f, COMMAND_Z = SEAT_DIST + 0.3f;

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

    // Panel geometry. One place, because the background is a ShapeRenderer pass and the labels
    // are a SpriteBatch pass: two passes that cannot be interleaved and must agree to the pixel.
    static final float PP_X = 14f, PP_W = 186f;

    static final float PP_ROW = 21f;          // height of one step row

    static final float PP_HEAD = 62f;         // turn line, seat line and the rule under them

    static final float PP_FOOT = 34f;         // the tickbox

    static final float PP_GAP = 7f;           // space inserted between groups

    /**
     * Where the turn changes character: beginning steps, first main, combat, second main, end.
     * The column leaves a gap at each, so combat reads as a block rather than six identical rows.
     * That is what you actually track in a four-player game - before combat, in it, or past it.
     */
    static final int[] PP_GROUPS = { 3, 4, 10, 11 };

    // The priority block under the steps: a heading with the stack depth, then one row per seat.
    static final float PB_HEAD = 22f, PB_ROW = 18f;

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
