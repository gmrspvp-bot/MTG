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
 * The 3D table.
 *
 * Four seats around a square table, you at the near edge. The camera has two poses and eases
 * between them:
 *
 *   SEATED     low and close behind your own board, which is where you spend most of the game
 *   OVERHEAD   lifted above the table looking straight down, so the whole pod is visible
 *
 * It lifts to OVERHEAD by itself whenever the snapshot says combat or targeting is happening, and
 * settles back down afterwards; TAB forces it either way. The easing is on a single 0..1 value
 * (`lift`) that both the eye position and the look-at point interpolate along, which is why the
 * movement reads as one continuous motion rather than two separate animations.
 *
 * Hovering a card ray-picks it and draws an enlarged, readable panel of its details. Stage 1 is
 * read-only: Forge's own UI still runs the game, and this window just shows it.
 */
public final class Table3D extends ApplicationAdapter {

    // ---- table geometry, in world units (1 unit ~ one card width) -------------------------------
    private static final float CARD_W = 1.0f, CARD_H = 1.4f, CARD_THICK = 0.04f;
    private static final float GAP = 0.18f;
    private static final float TABLE_HALF = 11f;
    private static final float SEAT_DIST = 6.4f;     // how far a seat's rows sit from the centre

    private final Supplier source;

    private PerspectiveCamera cam;
    private ModelBatch modelBatch;
    private Environment env;
    private ModelBuilder builder;
    private SpriteBatch ui;
    private BitmapFont font, fontBig;
    private ShapeRenderer shapes;

    private Model tableModel, cardModel, landModel, creatureModel, commanderModel, seatModel, seatPriorityModel;
    private Model faceModel, attackModel, backModel, zoneModel;
    private Model bandCreature, bandOther, bandLand, monitorModel;
    /** Band slabs are built one unit wide and scaled to the row, so there is one model per kind. */
    private static final float BAND_UNIT = 1f;
    private ModelInstance tableInst;
    private final List<ModelInstance> seatPads = new ArrayList<>();
    /** Scenery with no card behind it: zone pads and other players' face-down hands. */
    private final List<ModelInstance> props = new ArrayList<>();
    /** How far a held card is tilted up towards its owner, in degrees. */
    private static final float HAND_TILT = 62f;
    private CardArt art;
    private SearchPanel search;

    /** One drawable card plus where it is, so the same list serves rendering and picking. */
    private static final class Drawn {
        ModelInstance inst;
        /** The card picture, laid just above the body. Null when Forge has no art cached for it. */
        ModelInstance face;
        /** A slab under an attacking creature, so combat reads at a glance from the overhead pose. */
        ModelInstance marker;
        BoundingBox bounds = new BoundingBox();
        Snapshot.CardV card;
        Snapshot.SeatV seat;
    }

    private final List<Drawn> drawn = new ArrayList<>();
    private Drawn hovered;

    /**
     * Animation without giving up the per-frame rebuild. layout() still works out every card's
     * TARGET pose from the snapshot each frame, so the view always converges on the true state;
     * what persists between frames is only where each card was last DRAWN, keyed by card id.
     * Every frame each card eases from there towards its target. That turns tapping into a
     * quarter-turn and makes a card slide when it changes row or zone (hand -> stack ->
     * battlefield keeps its id), with no change-tracking anywhere else.
     */
    private static final class Pose {
        float x, y, z, yaw, tilt;
        int seen;
    }

    private final java.util.Map<Integer, Pose> poses = new java.util.HashMap<>();
    private float frameDt;
    private int frameNo;
    /** Ease rates, per second: about 0.25 s to finish a tap, a little longer to cross the table. */
    private static final float TURN_RATE = 14f, MOVE_RATE = 9f;

    /** Moves p a frame's worth towards the target; returns p. Yaw takes the short way round. */
    private Pose ease(Pose p, float x, float y, float z, float yaw, float tilt) {
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
    private float lift = 0f;
    private float liftTarget = 0f;
    private boolean manualOverride = false;
    private boolean manualValue = false;

    /**
     * Whose seat we are looking from. The table itself never moves: the camera orbits around it,
     * which is what makes stepping between players read as walking round the table rather than
     * the world spinning.
     *
     * `orbit` is kept as a continuous angle rather than being wrapped into 0..2pi, so going from
     * the last seat back to the first turns the short way instead of unwinding all the way round.
     */
    private int viewSeat = -1;
    private boolean followTurn = true;      // off once you step manually, back on with DOWN
    private float orbit = 0f;
    private float orbitTarget = 0f;
    /** Mouse wheel: 1 = the designed distance, smaller is closer. Reset with DOWN / HOME. */
    private float zoom = 1f;
    private static final float ZOOM_MIN = 0.55f, ZOOM_MAX = 1.7f;
    /** Right-drag: radians of orbit per pixel of horizontal mouse movement. */
    private static final float ORBIT_PER_PIXEL = 0.006f;
    private int lastActiveSeat = -1;

    private Snapshot snap = new Snapshot();

    /** Where the view gets its state. Demo supplies a fake one; the Forge bridge supplies the real one. */
    public interface Supplier {
        Snapshot get();
    }

    public Table3D(Supplier source) {
        this.source = source;
    }

    @Override
    public void create() {
        cam = new PerspectiveCamera(60f, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        // Depth precision is governed by the near/far RATIO, not the range. 0.1 to 300 is 3000:1,
        // which leaves so little resolution at table distance that the card picture and the card
        // body underneath it fought for the same depth and striped. Nothing on this table is
        // nearer than about a metre or further than the far corner, so the range can be tight.
        cam.near = 0.5f;
        cam.far = 120f;

        modelBatch = new ModelBatch();
        builder = new ModelBuilder();
        ui = new SpriteBatch();
        shapes = new ShapeRenderer();
        arcs = new com.badlogic.gdx.graphics.glutils.ImmediateModeRenderer20(4000, false, true, 0);
        font = new BitmapFont();
        fontBig = new BitmapFont();
        fontBig.getData().setScale(1.6f);

        env = new Environment();
        env.set(new ColorAttribute(ColorAttribute.AmbientLight, 0.55f, 0.60f, 0.70f, 1f));
        DirectionalLight key = new DirectionalLight();
        key.set(0.85f, 0.90f, 1.0f, -0.4f, -1f, -0.3f);
        env.add(key);

        int attrs = Usage.Position | Usage.Normal;
        tableModel = builder.createBox(TABLE_HALF * 2, 0.5f, TABLE_HALF * 2,
            new Material(ColorAttribute.createDiffuse(new Color(0.07f, 0.16f, 0.24f, 1f))), attrs);
        tableInst = new ModelInstance(tableModel);
        tableInst.transform.setToTranslation(0, -0.27f, 0);

        cardModel     = box(new Color(0.78f, 0.80f, 0.86f, 1f), attrs);
        landModel     = box(new Color(0.45f, 0.63f, 0.42f, 1f), attrs);
        creatureModel = box(new Color(0.85f, 0.72f, 0.42f, 1f), attrs);
        commanderModel= box(new Color(0.92f, 0.55f, 0.25f, 1f), attrs);
        // Smaller and darker than they were (9.2 x 4.2, a bright slab that out-shouted the cards).
        seatModel = builder.createBox(7.6f, 0.06f, 3.4f,
            new Material(ColorAttribute.createDiffuse(new Color(0.07f, 0.16f, 0.23f, 1f))), attrs);
        // The same pad, lit, under whoever holds priority right now.
        seatPriorityModel = builder.createBox(7.6f, 0.06f, 3.4f,
            new Material(ColorAttribute.createDiffuse(new Color(0.09f, 0.36f, 0.46f, 1f))), attrs);
        attackModel = builder.createBox(CARD_W * 1.18f, 0.02f, CARD_H * 1.12f,
            new Material(ColorAttribute.createDiffuse(new Color(0.85f, 0.25f, 0.18f, 1f))), attrs);
        backModel = box(new Color(0.16f, 0.13f, 0.26f, 1f), attrs);          // a face-down card
        // Band slabs, one unit wide so placeBand can scale them to the row's length. The colours
        // are only just off the table: enough to group, not enough to compete with the cards.
        bandCreature = builder.createBox(BAND_UNIT, 0.012f, CARD_H + 0.42f,
            new Material(ColorAttribute.createDiffuse(new Color(0.13f, 0.27f, 0.33f, 1f))), attrs);
        bandOther = builder.createBox(BAND_UNIT, 0.012f, CARD_H + 0.42f,
            new Material(ColorAttribute.createDiffuse(new Color(0.17f, 0.24f, 0.36f, 1f))), attrs);
        bandLand = builder.createBox(BAND_UNIT, 0.012f, CARD_H + 0.42f,
            new Material(ColorAttribute.createDiffuse(new Color(0.12f, 0.29f, 0.26f, 1f))), attrs);
        monitorModel = builder.createBox(2.1f, 0.07f, 1.25f,
            new Material(ColorAttribute.createDiffuse(new Color(0.05f, 0.13f, 0.20f, 1f))), attrs);
        zoneModel = builder.createBox(CARD_W * 0.88f, 0.03f, CARD_H * 0.88f,
            new Material(ColorAttribute.createDiffuse(new Color(0.06f, 0.18f, 0.26f, 1f))), attrs);

        // A flat quad for the card picture. Corner order sets the UVs: libGDX puts the top of the
        // image along the corner11/corner01 edge, so that edge goes at -Z, which is "away from the
        // seat" - i.e. the card reads the right way up to whoever is sitting there.
        faceModel = builder.createRect(
            -CARD_W / 2f, 0f,  CARD_H / 2f,
             CARD_W / 2f, 0f,  CARD_H / 2f,
             CARD_W / 2f, 0f, -CARD_H / 2f,
            -CARD_W / 2f, 0f, -CARD_H / 2f,
            0f, 1f, 0f,
            new Material(ColorAttribute.createDiffuse(Color.WHITE)),
            Usage.Position | Usage.Normal | Usage.TextureCoordinates);

        art = new CardArt(CardArt.defaultRoot());
        search = new SearchPanel(art);
        // libGDX only delivers the wheel through an InputProcessor; everything else this view
        // needs is polled, so this is the one handler.
        Gdx.input.setInputProcessor(new com.badlogic.gdx.InputAdapter() {
            @Override public boolean scrolled(float amountX, float amountY) {
                if (search.isOpen()) {
                    search.scrollBy(amountY);
                    return true;
                }
                // The wheel zooms the camera when no panel wants it; DOWN / HOME resets it.
                zoom = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, zoom * (1f + amountY * 0.1f)));
                return true;
            }
        });
        snap = source.get();
    }

    private Model box(Color c, int attrs) {
        return builder.createBox(CARD_W, CARD_THICK, CARD_H,
            new Material(ColorAttribute.createDiffuse(c)), attrs);
    }

    @Override
    public void resize(int width, int height) {
        cam.viewportWidth = width;
        cam.viewportHeight = height;
        cam.update();
    }

    @Override
    public void render() {
        Snapshot s = source.get();
        if (s != null) snap = s;
        if (art != null) art.update();   // GL objects may only be made on this thread

        handleInput();
        updateCamera(Gdx.graphics.getDeltaTime());
        frameDt = Math.min(Gdx.graphics.getDeltaTime(), 0.1f);   // a stall must not fling cards
        frameNo++;
        layout();
        // Cards not drawn this frame have left the view; forget them, so one that comes back
        // (a commander recast, a card returned to hand) appears where it is instead of flying in
        // from where it was last seen.
        poses.values().removeIf(p -> p.seen != frameNo);
        pick();

        Gdx.gl.glClearColor(0.02f, 0.05f, 0.08f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);

        modelBatch.begin(cam);
        modelBatch.render(tableInst, env);
        for (ModelInstance p : seatPads) modelBatch.render(p, env);
        for (ModelInstance p : props) modelBatch.render(p, env);
        for (Drawn d : drawn) {
            if (d.marker != null) modelBatch.render(d.marker, env);
            // The coloured body is only a stand-in for missing art. Drawing it UNDER a picture
            // put two near-coplanar surfaces in the depth buffer for no visual gain, which is
            // what produced the stripes; with art, the picture is the whole card.
            if (d.face != null) modelBatch.render(d.face, env);
            else modelBatch.render(d.inst, env);
        }
        modelBatch.end();
        drawCombatArcs();

        drawOverlay();

        if (search != null) {
            search.update(Gdx.graphics.getDeltaTime());
            search.render(ui, shapes, font, fontBig);
        }
        maybeShoot();
        WindowMemory.track();
    }

    /**
     * -Dforge.ui3d.shots=<dir>: save the frame as a PNG every forge.ui3d.shotEvery seconds (default
     * 0.5). Lets a change to the view be checked without anyone looking at the screen: an agent
     * cannot see the window, and Windows screen capture misses this GL surface (it comes out
     * white). Off unless the property is set.
     */
    private static final String SHOTS = System.getProperty("forge.ui3d.shots");
    private static final float SHOT_EVERY = Float.parseFloat(System.getProperty("forge.ui3d.shotEvery", "0.5"));
    private float shotClock;
    private int shotNo;

    private void maybeShoot() {
        if (SHOTS == null) return;
        shotClock += Gdx.graphics.getDeltaTime();
        if (shotClock < SHOT_EVERY) return;
        shotClock = 0f;
        try {
            int w = Gdx.graphics.getBackBufferWidth(), h = Gdx.graphics.getBackBufferHeight();
            com.badlogic.gdx.graphics.Pixmap pm = com.badlogic.gdx.graphics.Pixmap.createFromFrameBuffer(0, 0, w, h);
            java.nio.ByteBuffer px = pm.getPixels();
            for (int i = 3; i < px.limit(); i += 4) px.put(i, (byte) 255);   // the back buffer's alpha isn't meaningful
            com.badlogic.gdx.graphics.PixmapIO.writePNG(Gdx.files.absolute(SHOTS + "/shot" + String.format("%03d", shotNo++) + ".png"),
                pm, java.util.zip.Deflater.BEST_SPEED, true);
            pm.dispose();
        } catch (Throwable t) {
            System.out.println("UI3D: screenshot failed (" + t + ")");
        }
    }

    /**
     * Demo trigger (S): opens the search panel over everything currently in the snapshot, so the
     * animation and the grid can be exercised before stage 3 has anything real to search.
     */
    private void openDemoSearch() {
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

    private void handleInput() {
        // A search owns the keyboard and mouse while it is up, otherwise ESC would both close the
        // panel and release the camera, and a click meant for a card would also move the view.
        if (search != null && search.isOpen()) return;
        if (Gdx.input.isKeyJustPressed(Input.Keys.S)) { openDemoSearch(); return; }
        if (Gdx.input.isKeyJustPressed(Input.Keys.TAB)) {
            manualOverride = true;
            manualValue = lift < 0.5f;       // toggle to whichever pose we are not in
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) manualOverride = false;

        int n = snap.seats.size();
        if (n == 0) return;
        // Right-drag orbits freely round the table (left button is kept for clicking cards in
        // stage 3). It takes over from following the turn, like the arrow keys do.
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
        // DOWN (or HOME) is the way back: your own seat, and the camera follows the turn again.
        if (Gdx.input.isKeyJustPressed(Input.Keys.DOWN) || Gdx.input.isKeyJustPressed(Input.Keys.HOME)) {
            followTurn = true;
            zoom = 1f;
            setViewSeat(snap.youIndex(), n);
        }
    }

    private void step(int delta, int n) {
        followTurn = false;                  // stepping by hand takes over from the turn
        if (viewSeat < 0) viewSeat = snap.youIndex();
        setViewSeat(((viewSeat + delta) % n + n) % n, n);
    }

    /** Points the orbit at a seat, choosing whichever way round is shorter. */
    private void setViewSeat(int seat, int n) {
        viewSeat = seat;
        float want = seatAngle(seat, n);
        // Bring the target into the same turn as the current angle, then pick the nearer equivalent.
        float twoPi = (float) (Math.PI * 2);
        float base = orbit - ((orbit % twoPi) + twoPi) % twoPi;
        float best = base + want;
        for (float cand : new float[] { base + want - twoPi, base + want, base + want + twoPi }) {
            if (Math.abs(cand - orbit) < Math.abs(best - orbit)) best = cand;
        }
        orbitTarget = best;
    }

    /** Seats sit at fixed, absolute angles round the table; seat 0 is the far edge of the world. */
    private static float seatAngle(int index, int n) {
        return (float) (index * 2 * Math.PI / n);
    }

    private void updateCamera(float dt) {
        int n = snap.seats.size();
        if (n > 0) {
            if (viewSeat < 0) { viewSeat = snap.youIndex(); orbit = orbitTarget = seatAngle(viewSeat, n); }
            // Follow the turn: when a new player becomes active, walk round to their seat.
            int active = activeSeat();
            if (followTurn && active >= 0 && active != lastActiveSeat) {
                lastActiveSeat = active;
                setViewSeat(active, n);
            }
        }

        boolean wantOverhead = manualOverride ? manualValue : (snap.combat || snap.targeting);
        liftTarget = wantOverhead ? 1f : 0f;

        // Ease towards the targets; the rates are per-second, so this is frame-rate independent.
        lift += (liftTarget - lift) * Math.min(1f, dt * 2.6f);
        if (Math.abs(liftTarget - lift) < 0.001f) lift = liftTarget;
        orbit += (orbitTarget - orbit) * Math.min(1f, dt * 3.4f);
        if (Math.abs(orbitTarget - orbit) < 0.0005f) orbit = orbitTarget;

        float t = smooth(lift);

        // Both poses are expressed in the viewed seat's own frame, then rotated into the world by
        // `orbit`. Interpolating before rotating keeps the lift and the walk round as one motion.
        // Seated sits back far enough, and high enough, to keep the fanned hand in frame; the
        // look-at is pushed past the middle of your own rows so the view tilts down onto the table.
        float atZ  = lerp(SEAT_DIST - 3.0f, 0.4f, t);
        // Zoom moves the eye along its line to the look-at point (which is on the table, y = 0).
        float eyeZ = atZ + (lerp(SEAT_DIST + 8.6f, 7.0f, t) - atZ) * zoom;
        float eyeY = lerp(5.0f, 17.5f, t) * zoom;

        Vector3 eye = rotateY(0f, eyeZ, orbit);
        Vector3 at  = rotateY(0f, atZ, orbit);

        cam.position.set(eye.x, eyeY, eye.z);
        cam.up.set(0, 1, 0);
        cam.lookAt(at.x, 0f, at.z);
        cam.update();
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** Index of the seat whose turn it is, or -1. */
    private int activeSeat() {
        for (int i = 0; i < snap.seats.size(); i++) if (snap.seats.get(i).active) return i;
        return -1;
    }

    /** Smoothstep, so the move eases out of one pose and into the other instead of starting abruptly. */
    private static float smooth(float x) {
        return x * x * (3f - 2f * x);
    }

    /**
     * Rebuilds the card placement every frame. At these counts that is far cheaper than tracking
     * what changed, and it means the view can never drift out of step with the snapshot.
     */
    private void layout() {
        drawn.clear();
        seatPads.clear();
        props.clear();
        if (snap.seats.isEmpty()) return;

        int n = snap.seats.size();
        for (int i = 0; i < n; i++) {
            Snapshot.SeatV seat = snap.seats.get(i);
            // Seats are fixed in the world. The camera orbits to whichever one we are looking from,
            // so a player's board never moves out from under them when the view changes.
            float angle = seatAngle(i, n);

            ModelInstance pad = new ModelInstance(seat.hasPriority ? seatPriorityModel : seatModel);
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
        placeHand();
        placeOpponentHands();
        placeZones();
        placeMonitors();
        placeStack();
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
    private void placeStack() {
        if (snap.stack.isEmpty()) return;
        float step = CARD_W * 1.15f;
        float width = snap.stack.size() * step - (step - CARD_W);
        float yaw = (float) Math.toDegrees(-orbit);
        for (int i = 0; i < snap.stack.size(); i++) {
            Snapshot.CardV c = snap.stack.get(i);
            boolean top = (i == snap.stack.size() - 1);
            float along = -width / 2f + i * step + CARD_W / 2f;
            Vector3 w = rotateY(along, 0f, orbit);
            add(c, null, w.x, STACK_Y + (top ? 0.34f : 0f), w.z, yaw, 90f);
        }
    }

    /**
     * Four small screens in the middle of the table, one per player, each tilted up and turned to
     * face its own seat - the arrangement a physical life-tracker app uses, rather than one shared
     * scoreboard that only reads correctly from one side. Everything on them is public knowledge,
     * so there is nothing here that any seat should not see.
     */
    private void placeMonitors() {
        int n = snap.seats.size();
        for (int i = 0; i < n; i++) {
            float angle = seatAngle(i, n);
            Vector3 w = rotateY(0f, MONITOR_DIST, angle);
            ModelInstance inst = new ModelInstance(monitorModel);
            inst.transform.setToTranslation(w.x, 0.42f, w.z)
                .rotate(Vector3.Y, (float) Math.toDegrees(-angle))
                .rotate(Vector3.X, 34f);
            props.add(inst);
        }
    }

    /**
     * Seats are laid out by rotating a point on the near edge around the table's centre.
     * angle 0 is the near edge (you); a four-player pod then goes anticlockwise at 90 degree steps.
     * Rotating local (x, z) by `angle` about Y:  wx = x*cos - z*sin,  wz = x*sin + z*cos.
     */
    private static Vector3 rotateY(float lx, float lz, float angle) {
        float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
        return new Vector3(lx * cos - lz * sin, 0f, lx * sin + lz * cos);
    }

    /** A seat's centre: the middle of its rows, pushed out from the table centre. */
    private Vector3 seatPos(float angle, float dist) {
        return rotateY(0f, dist, angle);
    }

    private void placeRow(Snapshot.SeatV seat, List<Snapshot.CardV> cards, float angle, float dist) {
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
            add(c, seat, w.x, w.z, (float) Math.toDegrees(-angle) + (c.tapped ? 90f : 0f));
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
    private void placeHand() {
        if (snap.hand.isEmpty() || snap.seats.isEmpty()) return;
        float angle = seatAngle(snap.youIndex(), snap.seats.size());
        float step = CARD_W * 0.80f;
        float width = snap.hand.size() * step;
        for (int i = 0; i < snap.hand.size(); i++) {
            Snapshot.CardV c = snap.hand.get(i);
            float along = -width / 2f + i * step + step / 2f;
            Vector3 w = rotateY(along, SEAT_DIST + 3.6f, angle);
            add(c, null, w.x, 0.62f, w.z, (float) Math.toDegrees(-angle), HAND_TILT);
        }
    }

    /**
     * Face-down cards for everyone else's hand. How many cards a player is holding is public
     * knowledge, so it is shown; what they are is not, and the renderer is never told. Tilted away
     * from the table so from any other seat you see only backs.
     */
    private void placeOpponentHands() {
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
                ModelInstance inst = new ModelInstance(backModel);
                inst.transform.setToTranslation(w.x, 0.62f, w.z)
                    .rotate(Vector3.Y, (float) Math.toDegrees(-angle))
                    .rotate(Vector3.X, HAND_TILT);
                props.add(inst);
            }
        }
    }

    /** How far each band sits from the middle of the table. */
    /** How high the stack bar floats above the table. */
    private static final float STACK_Y = 3.4f;
    /** How far each player's little screen sits from the middle of the table. */
    private static final float MONITOR_DIST = 2.0f;
    private static final float ROW_CREATURES = SEAT_DIST - 2.1f;
    private static final float ROW_OTHERS    = SEAT_DIST - 0.5f;
    private static final float ROW_LANDS     = SEAT_DIST + 1.3f;

    /**
     * The faint slab under a band of permanents. Only drawn when the band has something in it, so
     * an empty board stays clean rather than showing three empty trays.
     */
    private void placeBand(List<Snapshot.CardV> cards, float angle, float dist, Model band) {
        if (cards.isEmpty()) return;
        float width = 0.5f - GAP;   // same slot widths as placeRow: tapped cards are CARD_H wide
        for (Snapshot.CardV c : cards) width += (c.tapped ? CARD_H : CARD_W) + GAP;
        Vector3 w = rotateY(0f, dist, angle);
        ModelInstance inst = new ModelInstance(band);
        inst.transform.setToTranslation(w.x, -0.005f, w.z)
            .rotate(Vector3.Y, (float) Math.toDegrees(-angle))
            .scale(width / BAND_UNIT, 1f, 1f);
        props.add(inst);
    }

    /** Library, graveyard and exile, as pads beside each seat's rows. Counts are drawn over them. */
    private void placeZones() {
        int n = snap.seats.size();
        for (int i = 0; i < n; i++) {
            float angle = seatAngle(i, n);
            for (int k = 0; k < 3; k++) {
                Vector3 w = rotateY(5.3f, SEAT_DIST + 1.7f - k * 1.45f, angle);
                ModelInstance inst = new ModelInstance(zoneModel);
                inst.transform.setToTranslation(w.x, 0.0f, w.z)
                    .rotate(Vector3.Y, (float) Math.toDegrees(-angle));
                props.add(inst);
            }
        }
    }

    private void add(Snapshot.CardV c, Snapshot.SeatV seat, float x, float z, float yawDeg) {
        // On the table: flat, at table height.
        add(c, seat, x, 0.02f, z, yawDeg, 0f);
    }

    private void add(Snapshot.CardV c, Snapshot.SeatV seat, float x, float y, float z,
                     float yawDeg, float tiltDeg) {
        Model m = c.commander ? commanderModel : c.land ? landModel : c.creature ? creatureModel : cardModel;
        Drawn d = new Drawn();
        d.inst = new ModelInstance(m);
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
        d.inst.calculateBoundingBox(d.bounds);
        d.bounds.mul(d.inst.transform);

        // The picture, if Forge has it cached. Asking each frame is what makes it appear once the
        // background decode finishes; until then the coloured body stands in for it.
        com.badlogic.gdx.graphics.Texture tex = art == null ? null : art.get(c);
        if (tex != null) {
            d.face = new ModelInstance(faceModel);
            d.face.transform.set(d.inst.transform).translate(0f, CARD_THICK / 2f + 0.03f, 0f);
            d.face.materials.get(0).set(
                com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute.createDiffuse(tex));
        }
        if (c.attacking) {
            d.marker = new ModelInstance(attackModel);
            d.marker.transform.set(d.inst.transform).translate(0f, -CARD_THICK, 0f);
        }
        drawn.add(d);
    }

    // ---- combat arcs ----------------------------------------------------------------------------

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
    private com.badlogic.gdx.graphics.glutils.ImmediateModeRenderer20 arcs;
    private float arcClock;
    private static final int ARC_SEGMENTS = 28;
    private static final float ARC_WIDTH = 0.16f, ARROW_LEN = 0.55f;

    private void drawCombatArcs() {
        if (snap.combatArcs.isEmpty() || arcs == null) return;
        arcClock += frameDt;
        int n = snap.seats.size();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        Gdx.gl.glDepthMask(false);
        arcs.begin(cam.combined, GL20.GL_TRIANGLES);
        for (Snapshot.CombatArc a : snap.combatArcs) {
            Pose from = poses.get(a.attackerId);
            if (from == null) continue;
            float tx, tz;
            Pose target = a.defenderCardId >= 0 ? poses.get(a.defenderCardId) : null;
            if (target != null) {
                tx = target.x; tz = target.z;
            } else if (a.defenderSeat >= 0 && a.defenderSeat < n) {
                Vector3 s = seatPos(seatAngle(a.defenderSeat, n), SEAT_DIST);
                tx = s.x; tz = s.z;
            } else {
                continue;
            }
            boolean blocked = !a.blockedBy.isEmpty();
            if (blocked) ribbon(from.x, from.z, tx, tz, 0.75f, 0.45f, 0.40f, 0.30f);
            else         ribbon(from.x, from.z, tx, tz, 1.00f, 0.35f, 0.15f, 0.95f);
            for (int b : a.blockedBy) {
                Pose bp = poses.get(b);
                if (bp != null) ribbon(bp.x, bp.z, from.x, from.z, 0.35f, 0.70f, 1.00f, 0.95f);
            }
            if (arcs.getNumVertices() > 3600) {   // flush before the buffer fills
                arcs.end();
                arcs.begin(cam.combined, GL20.GL_TRIANGLES);
            }
        }
        arcs.end();
        Gdx.gl.glDepthMask(true);
    }

    /** One arc from (x0,z0) to (x1,z1): a parabola above the table, ending in an arrowhead. */
    private void ribbon(float x0, float z0, float x1, float z1, float r, float g, float b, float alpha) {
        float dx = x1 - x0, dz = z1 - z0;
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        if (len < 0.3f) return;
        float peak = 0.6f + 0.16f * len;              // longer reaches fly higher, so they clear rows
        float sx = dz / len, sz = -dx / len;          // sideways, in the table's plane
        float tEnd = Math.max(0.5f, 1f - ARROW_LEN / len);
        float half = ARC_WIDTH / 2f;
        float px = 0, py = 0, pz = 0, pc = 0;
        for (int i = 0; i <= ARC_SEGMENTS; i++) {
            float t = tEnd * i / ARC_SEGMENTS;
            float x = x0 + dx * t, z = z0 + dz * t;
            float y = 0.12f + peak * 4f * t * (1f - t);
            // Pulses travel from attacker to target, about one and a half a second.
            float wave = (float) Math.sin((t * len * 0.9f - arcClock * 4f) * Math.PI);
            float c = 0.55f + 0.45f * Math.max(0f, wave);
            if (i > 0) {
                quad(px - sx * half, py, pz - sz * half, px + sx * half, py, pz + sz * half,
                     x + sx * half, y, z + sz * half, x - sx * half, y, z - sz * half,
                     r, g, b, alpha * pc, alpha * c);
            }
            px = x; py = y; pz = z; pc = c;
        }
        // Arrowhead from the end of the ribbon down to the target.
        float aw = ARC_WIDTH * 1.9f;
        float tipY = 0.12f;
        vert(px - sx * aw, py, pz - sz * aw, r, g, b, alpha);
        vert(px + sx * aw, py, pz + sz * aw, r, g, b, alpha);
        vert(x1, tipY, z1, r, g, b, alpha);
    }

    private void quad(float ax, float ay, float az, float bx, float by, float bz,
                      float cx, float cy, float cz, float dx, float dy, float dz,
                      float r, float g, float b, float a0, float a1) {
        vert(ax, ay, az, r, g, b, a0); vert(bx, by, bz, r, g, b, a0); vert(cx, cy, cz, r, g, b, a1);
        vert(ax, ay, az, r, g, b, a0); vert(cx, cy, cz, r, g, b, a1); vert(dx, dy, dz, r, g, b, a1);
    }

    private void vert(float x, float y, float z, float r, float g, float b, float a) {
        arcs.color(r, g, b, a);
        arcs.vertex(x, y, z);
    }

    /** Ray-pick the card under the mouse; nearest hit wins. */
    private void pick() {
        hovered = null;
        if (search != null && search.isOpen()) return;
        Ray ray = cam.getPickRay(Gdx.input.getX(), Gdx.input.getY());
        float best = Float.MAX_VALUE;
        Vector3 hit = new Vector3();
        for (Drawn d : drawn) {
            if (!com.badlogic.gdx.math.Intersector.intersectRayBounds(ray, d.bounds, hit)) continue;
            float dist = hit.dst2(cam.position);
            if (dist < best) { best = dist; hovered = d; }
        }
    }

    // ---- 2D overlay -----------------------------------------------------------------------------

    private void drawOverlay() {
        int w = Gdx.graphics.getWidth(), h = Gdx.graphics.getHeight();
        Matrix4 flat = new Matrix4().setToOrtho2D(0, 0, w, h);

        if (hovered != null) drawDetailPanel(w, h, flat);

        ui.setProjectionMatrix(flat);
        ui.begin();
        font.setColor(0.70f, 0.90f, 1f, 1f);
        font.draw(ui, snap.turnLabel + (snap.phase.isEmpty() ? "" : "   " + snap.phase), 14, h - 12);
        // Say plainly whose seat we are in, and that their hand is not ours to see.
        if (viewSeat >= 0 && viewSeat < snap.seats.size()) {
            Snapshot.SeatV v = snap.seats.get(viewSeat);
            if (v.isYou) {
                font.setColor(0.65f, 0.95f, 1f, 1f);
                font.draw(ui, "Your seat", 14, h - 32);
            } else {
                font.setColor(1f, 0.78f, 0.45f, 1f);
                font.draw(ui, "Viewing " + v.name + "'s seat  (their hand stays hidden)", 14, h - 32);
            }
        }
        String mode = (lift > 0.5f ? "OVERHEAD" : "SEATED")
            + (manualOverride ? " (TAB held - ESC to follow the game)" : "")
            + (followTurn ? "   following the turn" : "   DOWN to follow again");
        font.setColor(0.45f, 0.62f, 0.75f, 1f);
        font.draw(ui, mode, 14, 38);
        font.setColor(0.35f, 0.50f, 0.62f, 1f);
        font.draw(ui, "LEFT / RIGHT  walk round the table      DOWN  back to your seat      TAB  camera"
            + "      right-drag  orbit      wheel  zoom", 14, 18);
        // Art counter: says at a glance whether Forge's picture cache was found at all, which is
        // the first thing to check if every card is still a coloured block.
        if (art != null) {
            String a = "art " + art.loaded() + " loaded / " + art.known() + " cached";
            font.draw(ui, a, w - 190, 18);
        }
        drawSeatInfo(w, h);
        drawPhaseOnTable();
        drawZoneLabels();
        drawCentreTracker();
        drawStackLabels();
        ui.end();
    }

    /** Life totals and zone counts, printed near each seat so the pod reads at a glance. */
    private void drawSeatInfo(int w, int h) {
        int n = snap.seats.size();
        for (int i = 0; i < n; i++) {
            Snapshot.SeatV s = snap.seats.get(i);
            float angle = seatAngle(i, n);
            Vector3 world = seatPos(angle, SEAT_DIST + 2.6f);
            Vector3 p = cam.project(new Vector3(world.x, 0.4f, world.z));
            if (p.z > 1f) continue;                       // behind the camera
            if (s.active) font.setColor(1f, 0.85f, 0.35f, 1f);
            else if (s.isYou) font.setColor(0.65f, 0.95f, 1f, 1f);
            else font.setColor(0.72f, 0.78f, 0.86f, 1f);
            String line = s.name + "   " + s.life + " life";
            String line2 = "hand " + s.handSize + "   lib " + s.librarySize + "   gy " + s.graveyardSize;
            font.draw(ui, line, p.x - 60, p.y + 16);
            font.draw(ui, line2, p.x - 60, p.y);
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
    private void drawPhaseOnTable() {
        int active = activeSeat();
        if (active < 0 || snap.phase.isEmpty()) return;
        Vector3 w = rotateY(0f, ROW_CREATURES - 1.45f, seatAngle(active, snap.seats.size()));
        Vector3 p = cam.project(new Vector3(w.x, 0.05f, w.z));
        if (p.z > 1f) return;
        fontBig.setColor(1f, 0.82f, 0.32f, 0.92f);
        com.badlogic.gdx.graphics.g2d.GlyphLayout gl = new com.badlogic.gdx.graphics.g2d.GlyphLayout(fontBig, snap.phase);
        fontBig.draw(ui, gl, p.x - gl.width / 2f, p.y + gl.height / 2f);
    }

    /** Who cast what, under each item on the stack, plus a marker on the one resolving next. */
    private void drawStackLabels() {
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
            if (top) font.draw(ui, "resolving next", p.x - 30, p.y - 13);
        }
    }

    /** Counts printed over each seat's library / graveyard / exile pads. */
    private void drawZoneLabels() {
        int n = snap.seats.size();
        for (int i = 0; i < n; i++) {
            Snapshot.SeatV s = snap.seats.get(i);
            float angle = seatAngle(i, n);
            String[] names = { "LIB " + s.librarySize, "GY " + s.graveyardSize, "EXI " + s.exileSize };
            for (int k = 0; k < 3; k++) {
                Vector3 world = rotateY(5.3f, SEAT_DIST + 1.7f - k * 1.45f, angle);
                Vector3 p = cam.project(new Vector3(world.x, 0.1f, world.z));
                if (p.z > 1f) continue;
                font.setColor(0.58f, 0.76f, 0.88f, 1f);
                font.draw(ui, names[k], p.x - 20, p.y + 6);
            }
        }
    }

    /**
     * The tracker in the middle of the table: life, and commander damage taken from each
     * commander. All of it is public knowledge, which is why it can sit in the open where
     * everyone's view of it is the same.
     */
    private void drawCentreTracker() {
        int n = snap.seats.size();
        for (int i = 0; i < n; i++) {
            Snapshot.SeatV s = snap.seats.get(i);
            float angle = seatAngle(i, n);
            Vector3 w = rotateY(0f, MONITOR_DIST, angle);
            Vector3 p = cam.project(new Vector3(w.x, 0.62f, w.z));
            if (p.z > 1f) continue;                       // behind the camera

            // The number is drawn in screen space rather than onto the panel, so every seat's
            // total stays readable from wherever the camera happens to be.
            if (s.active) fontBig.setColor(1f, 0.85f, 0.35f, 1f);
            else if (s.isYou) fontBig.setColor(0.65f, 0.95f, 1f, 1f);
            else fontBig.setColor(0.80f, 0.88f, 0.95f, 1f);
            fontBig.draw(ui, String.valueOf(s.life), p.x - 16, p.y + 14);

            font.setColor(0.52f, 0.66f, 0.78f, 1f);
            font.draw(ui, s.name, p.x - 16, p.y + 30);
            float y = p.y - 6;
            if (s.poison > 0) {
                font.setColor(0.60f, 0.85f, 0.45f, 1f);
                font.draw(ui, "poison " + s.poison, p.x - 16, y);
                y -= 13f;
            }
            font.setColor(0.70f, 0.52f, 0.46f, 1f);
            int shown = 0;
            for (Snapshot.CmdDmg d : s.commanderDamage) {
                if (shown++ >= 2) break;                  // two is enough to see a threat building
                font.draw(ui, d.amount + " " + shortName(d.from), p.x - 16, y);
                y -= 13f;
            }
        }
    }

    /** "Captain America, First Avenger" -> "Captain America", so the tracker stays narrow. */
    private static String shortName(String s) {
        if (s == null) return "?";
        int comma = s.indexOf(',');
        return comma > 0 ? s.substring(0, comma) : s;
    }

    /** The enlarged, readable card panel that follows the mouse. */
    private void drawDetailPanel(int w, int h, Matrix4 flat) {
        Snapshot.CardV c = hovered.card;
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

    /** Crude word wrap against a pixel width - enough for rules text in the hover panel. */
    private List<String> wrap(String text, float maxPx) {
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

    @Override
    public void dispose() {
        modelBatch.dispose();
        ui.dispose();
        shapes.dispose();
        arcs.dispose();
        font.dispose();
        fontBig.dispose();
        if (art != null) art.dispose();
        faceModel.dispose();
        attackModel.dispose();
        backModel.dispose();
        zoneModel.dispose();
        bandCreature.dispose();
        bandOther.dispose();
        bandLand.dispose();
        monitorModel.dispose();
        tableModel.dispose();
        cardModel.dispose();
        landModel.dispose();
        creatureModel.dispose();
        commanderModel.dispose();
        seatModel.dispose();
        seatPriorityModel.dispose();
    }
}
