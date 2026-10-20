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
    private Model faceModel, attackModel, backModel, zoneModel, commandPadModel, seatLostModel;
    private Model bandCreature, bandOther, bandLand, monitorModel, selectModel, hoverModel;
    private Model seatGlowModel;
    /** Band slabs are built one unit wide and scaled to the row, so there is one model per kind. */
    private static final float BAND_UNIT = 1f;
    private ModelInstance tableInst;
    private final List<ModelInstance> seatPads = new ArrayList<>();
    /** Scenery with no card behind it: zone pads and other players' face-down hands. */
    private final List<ModelInstance> props = new ArrayList<>();
    /** Click targets for the seats, in Snapshot.seats order. Rebuilt with the monitors. */
    private final List<BoundingBox> seatPick = new ArrayList<>();
    private int hoveredSeat = -1;
    /** How far a held card is tilted up towards its owner, in degrees. */
    private static final float HAND_TILT = 62f;
    private CardArt art;
    private SearchPanel search;
    private final BugReport bug = new BugReport();

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
    /** Walk round to whoever is active each turn. Off unless asked for, on the phase panel. */
    private boolean followTurn = false;
    private float orbit = 0f;
    private float orbitTarget = 0f;
    /** Mouse wheel: 1 = the designed distance, smaller is closer. Reset with DOWN / HOME. */
    private float zoom = 1f;
    /** Where the view has been dragged to, in world units on the table plane. */
    private float panX, panZ;
    /** How far the table can be dragged before it would leave the view entirely. */
    private static final float PAN_LIMIT = TABLE_HALF * 1.4f;
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
        // A slab under anything Forge will currently accept a click on. Without it you would have
        // to click hopefully and read the console to find out whether it did anything.
        selectModel = builder.createBox(CARD_W * 1.22f, 0.02f, CARD_H * 1.14f,
            new Material(ColorAttribute.createDiffuse(new Color(0.30f, 0.82f, 0.45f, 1f))), attrs);
        hoverModel = builder.createBox(CARD_W * 1.30f, 0.02f, CARD_H * 1.20f,
            new Material(ColorAttribute.createDiffuse(new Color(0.95f, 0.88f, 0.40f, 1f))), attrs);
        seatGlowModel = builder.createBox(2.5f, 0.06f, 1.7f,
            new Material(ColorAttribute.createDiffuse(new Color(0.95f, 0.82f, 0.32f, 1f))), attrs);
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
        // The command zone's pad: a card's size plus a rim, in a dark bronze so it reads as
        // a different kind of zone from the blue library/graveyard/exile pads.
        commandPadModel = builder.createBox(CARD_W + 0.24f, 0.02f, CARD_H + 0.24f,
            new Material(ColorAttribute.createDiffuse(new Color(0.30f, 0.21f, 0.10f, 1f))), attrs);
        createScoreboard(attrs);
        // -Dforge.ui3d.sound=false starts muted (M toggles either way).
        cues = new Cues();
        if ("false".equalsIgnoreCase(System.getProperty("forge.ui3d.sound"))) cues.toggleMute();
        // A seat whose player is out: the same pad, gone a dull dark red.
        seatLostModel = builder.createBox(7.6f, 0.06f, 3.4f,
            new Material(ColorAttribute.createDiffuse(new Color(0.16f, 0.07f, 0.08f, 1f))), attrs);

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
            // Typed characters only matter to the bug box; everything else this view needs is
            // polled, so this handler stays tiny.
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
        if (cues != null) cues.update(snap);
        if (art != null) art.update();   // GL objects may only be made on this thread
        // The heartbeat Handoff watches. A window that has stopped drawing cannot answer a
        // decision, and this is what lets the game thread find that out in a frame or two rather
        // than sitting out the whole timeout.
        Handoff.alive();

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
        // What a click would hit. With no selectable list during ordinary priority, this is the
        // only feedback there is before clicking, so it matters more than it looks.
        if (hovered != null) {
            ModelInstance ring = new ModelInstance(hoverModel);
            ring.transform.set(hovered.inst.transform).translate(0f, -CARD_THICK * 1.5f, 0f);
            modelBatch.render(ring, env);
        } else if (hoveredSeat >= 0 && hoveredSeat < seatPick.size()) {
            // A slab behind the hovered player's screen. Clicking a seat picks that player and
            // nothing said so; a monitor that lights up does.
            BoundingBox b = seatPick.get(hoveredSeat);
            Vector3 c = new Vector3();
            b.getCenter(c);
            ModelInstance glow = new ModelInstance(seatGlowModel);
            glow.transform.setToTranslation(c.x, c.y - 0.12f, c.z);
            modelBatch.render(glow, env);
        }
        for (Drawn d : drawn) {
            if (d.marker != null) modelBatch.render(d.marker, env);
            // The coloured body is only a stand-in for missing art. Drawing it UNDER a picture
            // put two near-coplanar surfaces in the depth buffer for no visual gain, which is
            // what produced the stripes; with art, the picture is the whole card.
            if (d.face != null) modelBatch.render(d.face, env);
            else modelBatch.render(d.inst, env);
        }
        modelBatch.end();
        drawScoreboard();
        drawCombatArcs();

        drawOverlay();

        // A list Forge wants shown opens the search panel in browse mode. Done here rather than
        // from the game thread because the panel belongs to the renderer, and the game thread is
        // already blocked inside reveal() waiting for it to be dismissed.
        Gui3D.Reveal rev = Gui3D.pendingReveal();
        if (rev != null && search != null && !search.isOpen()) {
            search.open(rev.title, "Close when you have read it.", rev.items, 0, 0,
                chosen -> Gui3D.dismissReveal());
        }
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
        // The cog is always live, so a bug can be reported whatever else is happening. It opens
        // a proper text dialog rather than an in-window field, so there is no typing state here.
        if (clickedCog()) { bug.ask(snap); return; }
        // A search owns the keyboard and mouse while it is up, otherwise ESC would both close the
        // panel and release the camera, and a click meant for a card would also move the view.
        if (search != null && search.isOpen()) return;
        if (Gdx.input.isKeyJustPressed(Input.Keys.S)) { openDemoSearch(); return; }
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
        if (clickedCard() && Gui3D.active()) {
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

    /** True on the frame the left button goes down on the bug cog. */
    private boolean clickedCog() {
        boolean down = Gdx.input.isButtonPressed(Input.Buttons.LEFT);
        boolean justDown = down && !leftWasDownCog;
        leftWasDownCog = down;
        return justDown && bug.overCog(Gdx.input.getX(), Gdx.graphics.getHeight() - Gdx.input.getY());
    }

    private boolean leftWasDownCog;

    /** True on the frame the left button goes down anywhere except the phase panel. */
    private boolean clickedCard() {
        boolean down = Gdx.input.isButtonPressed(Input.Buttons.LEFT);
        boolean justDown = down && !leftWasDownBoard;
        leftWasDownBoard = down;
        if (!justDown) return false;
        float mx = Gdx.input.getX(), my = Gdx.graphics.getHeight() - Gdx.input.getY();
        // The panel owns its own clicks; a click on the tickbox must not also cast a card behind it.
        return !(mx >= PP_X && mx <= PP_X + PP_W && my >= Gdx.graphics.getHeight() - 14f - panelHeight());
    }

    private boolean leftWasDownBoard;

    /** True on the frame the left button goes down inside the follow-turn tickbox. */
    private boolean clickedFollowBox() {
        boolean down = Gdx.input.isButtonPressed(Input.Buttons.LEFT);
        boolean justDown = down && !leftWasDown;
        leftWasDown = down;
        if (!justDown) return false;
        float mx = Gdx.input.getX(), my = Gdx.graphics.getHeight() - Gdx.input.getY();
        return mx >= followBox[0] && mx <= followBox[0] + followBox[2]
            && my >= followBox[1] && my <= followBox[1] + followBox[3];
    }

    private boolean leftWasDown;

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
            // Off by default - it is a choice on the phase panel, not something that happens to
            // you - and any manual move (arrow keys, right-drag) switches it back off.
            int active = activeSeat();
            if (followTurn && active >= 0 && active != lastActiveSeat) {
                lastActiveSeat = active;
                setViewSeat(active, n);
            } else if (!followTurn) {
                lastActiveSeat = active;     // so turning it on later does not jump a turn late
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

        // The pan slides eye and look-at together by the same amount, so the camera moves without
        // turning. Shifting only one of them would swing the view round instead.
        cam.position.set(eye.x + panX, eyeY, eye.z + panZ);
        cam.up.set(0, 1, 0);
        cam.lookAt(at.x + panX, 0f, at.z + panZ);
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

            ModelInstance pad = new ModelInstance(seat.lost ? seatLostModel
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
        stackAt.clear();
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
            // Where a target arc starts: the bottom edge of the upright card. Kept per stack index,
            // because an ability shares its id with its source, so poses cannot tell them apart.
            stackAt.add(new float[] { w.x, STACK_Y + (top ? 0.34f : 0f) - CARD_H / 2f, w.z });
        }
    }

    /** Per stack item, bottom first like Snapshot.stack: {x, y, z} of the bottom of its card. */
    private final List<float[]> stackAt = new ArrayList<>();

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
            // Each player's own screen doubles as the thing you click to pick that player -
            // choosing who starts, or targeting a seat. It is already one per player and already
            // carries their name and life, so it needs no separate hit target.
            BoundingBox box = new BoundingBox();
            inst.calculateBoundingBox(box);
            box.mul(inst.transform);
            seatPick.add(box);
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
    private List<Snapshot.CardV> unattached(List<Snapshot.CardV> cards) {
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

    /** Host id -> {attachment card, its controller's seat}, rebuilt each frame by layout(). */
    private final java.util.Map<Integer, List<Object[]>> attachedHere = new java.util.HashMap<>();
    /** Host id -> where the host was placed this frame: {x, z, yaw, its seat's yaw}. */
    private final java.util.Map<Integer, float[]> hostTargets = new java.util.HashMap<>();
    /** How much of each attachment peeks out past the top edge of its host (and of the one before). */
    private static final float ATTACH_PEEK = 0.40f;

    /**
     * Equipment, auras and fortifications sit UNDER what they are attached to, each one peeking
     * out a little further past its top edge - the way they are laid out on a real table, and in
     * Arena. They lie the same way as the host (so they turn with it when it taps), but always
     * peek towards the middle of the table, in the host SEAT's frame: peeking along a tapped
     * host's own "up" would push them sideways, under the neighbouring card, where they vanish.
     * An aura on an opponent's creature goes to that creature, wherever it sits. Slightly lower
     * than the host so the host covers it; the peeking strip is still pickable for the hover panel.
     */
    private void placeAttachments() {
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
        cards = unattached(cards);
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

    /** Where a seat's command zone sits, in its own frame: the other side from library/gy/exile. */
    private static final float COMMAND_X = -5.3f, COMMAND_Z = SEAT_DIST + 0.3f;

    /** True when anyone has a commander, i.e. the command zone means something this game. */
    private boolean commanderGame() {
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
    private void placeCommandZone() {
        if (!commanderGame()) return;
        int n = snap.seats.size();
        for (int i = 0; i < n; i++) {
            Snapshot.SeatV seat = snap.seats.get(i);
            float angle = seatAngle(i, n);
            float yaw = (float) Math.toDegrees(-angle);
            Vector3 w = rotateY(COMMAND_X, COMMAND_Z, angle);
            ModelInstance pad = new ModelInstance(commandPadModel);
            pad.transform.setToTranslation(w.x, 0.0f, w.z).rotate(Vector3.Y, yaw);
            props.add(pad);
            for (int k = 0; k < seat.command.size(); k++) {
                Vector3 cw = rotateY(COMMAND_X - k * (CARD_W + GAP), COMMAND_Z, angle);
                add(seat.command.get(k), seat, cw.x, cw.z, yaw);
            }
        }
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
        // Forge only publishes a selectable list for prompts that ask you to choose a card
        // ("discard one"), not for ordinary priority - there it outlines playable cards by another
        // route and accepts a click on anything. So this marker means "Forge has offered this
        // card", which is a narrower and more honest claim than "you can play this".
        if (!snap.selectableIds.isEmpty() && snap.selectableIds.contains(c.id)) {
            d.marker = new ModelInstance(selectModel);
            d.marker.transform.set(d.inst.transform).translate(0f, -CARD_THICK, 0f);
        } else if (c.attacking) {
            d.marker = new ModelInstance(attackModel);
            d.marker.transform.set(d.inst.transform).translate(0f, -CARD_THICK, 0f);
        }
        drawn.add(d);
    }

    // ---- stadium scoreboard ---------------------------------------------------------------------

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
    private static final float SB_Y = 5.4f, SB_FACE_W = 3.6f, SB_FACE_H = 1.35f;
    private static final int SB_TEX_W = 720, SB_TEX_H = 270;     // the face's own proportions
    private com.badlogic.gdx.graphics.glutils.FrameBuffer sbBuffer;
    private Model sbFaceModel, sbBodyModel;
    private BitmapFont sbFont;
    /** Sound cues for the moments that need you (see Cues). */
    private Cues cues;
    private String sbKey = "";

    private void createScoreboard(int attrs) {
        try {
            sbBuffer = new com.badlogic.gdx.graphics.glutils.FrameBuffer(
                com.badlogic.gdx.graphics.Pixmap.Format.RGBA8888, SB_TEX_W, SB_TEX_H, false);
        } catch (Throwable t) {
            sbBuffer = null;     // no FBO support: no scoreboard, everything else unaffected
            return;
        }
        // A FrameBuffer's texture comes out upside down when sampled; the flipped region fixes it.
        com.badlogic.gdx.graphics.g2d.TextureRegion region =
            new com.badlogic.gdx.graphics.g2d.TextureRegion(sbBuffer.getColorBufferTexture());
        region.flip(false, true);
        // Corner order as for faceModel: corner11/corner01 is the top of the image.
        sbFaceModel = builder.createRect(
            -SB_FACE_W / 2f, -SB_FACE_H / 2f, 0f,
             SB_FACE_W / 2f, -SB_FACE_H / 2f, 0f,
             SB_FACE_W / 2f,  SB_FACE_H / 2f, 0f,
            -SB_FACE_W / 2f,  SB_FACE_H / 2f, 0f,
            0f, 0f, 1f,
            new Material(com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute.createDiffuse(region),
                new com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute(1f)),
            Usage.Position | Usage.Normal | Usage.TextureCoordinates);
        sbBodyModel = builder.createBox(SB_FACE_W - 0.08f, SB_FACE_H + 0.16f, SB_FACE_W - 0.08f,
            new Material(ColorAttribute.createDiffuse(new Color(0.03f, 0.05f, 0.08f, 1f)),
                new com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute(1f)), attrs);
        sbFont = new BitmapFont();
        sbFont.getRegion().getTexture().setFilter(
            com.badlogic.gdx.graphics.Texture.TextureFilter.Linear,
            com.badlogic.gdx.graphics.Texture.TextureFilter.Linear);
    }

    private void drawScoreboard() {
        int n = snap.seats.size();
        if (n == 0 || sbBuffer == null) return;
        float alpha = 1f - smooth(lift);
        if (alpha < 0.02f) return;
        refreshScoreboard();

        // Faces round the centre: for n seats an n-sided ring whose sides are the faces.
        // Plus a little, so the 8-degree tip does not swing the bottom edge into the body.
        float r = (n >= 3 ? (float) (SB_FACE_W / 2f / Math.tan(Math.PI / n)) : 0.1f) + 0.12f;
        modelBatch.begin(cam);
        if (n == 4) {
            ModelInstance body = new ModelInstance(sbBodyModel);
            body.transform.setToTranslation(0f, SB_Y, 0f);
            opacity(body, alpha);
            modelBatch.render(body, env);
        }
        for (int i = 0; i < n; i++) {
            float angle = seatAngle(i, n);
            Vector3 w = rotateY(0f, r, angle);
            ModelInstance face = new ModelInstance(sbFaceModel);
            // Turned to face the seat, and tipped a few degrees down towards a seated player.
            face.transform.setToTranslation(w.x, SB_Y, w.z)
                .rotate(Vector3.Y, (float) Math.toDegrees(-angle))
                .rotate(Vector3.X, 8f);
            opacity(face, alpha);
            modelBatch.render(face);      // no environment: unlit, like a screen
        }
        modelBatch.end();
    }

    private static void opacity(ModelInstance inst, float a) {
        com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute b =
            (com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute)
                inst.materials.get(0).get(com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute.Type);
        if (b != null) b.opacity = a;
    }

    /** Redraws the scoreboard texture, but only when what it shows has changed. */
    private void refreshScoreboard() {
        StringBuilder k = new StringBuilder(snap.turnLabel).append(snap.dayNight);
        for (Snapshot.SeatV s : snap.seats) {
            k.append('|').append(s.name).append(':').append(s.life)
             .append(s.active ? 'A' : '-').append(s.isYou ? 'Y' : '-').append(s.hasPriority ? 'P' : '-')
             .append(s.lost ? 'L' : '-');
        }
        String key = k.toString();
        if (key.equals(sbKey)) return;
        sbKey = key;

        sbBuffer.begin();
        Gdx.gl.glClearColor(0.025f, 0.055f, 0.09f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        Matrix4 m = new Matrix4().setToOrtho2D(0, 0, SB_TEX_W, SB_TEX_H);

        int n = snap.seats.size();
        float head = 54f;
        float rowH = (SB_TEX_H - head - 14f) / Math.max(1, n);
        shapes.setProjectionMatrix(m);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.85f, 0.62f, 0.22f, 1f);                       // gold rim, top and bottom
        shapes.rect(0, SB_TEX_H - 8f, SB_TEX_W, 8f);
        shapes.rect(0, 0, SB_TEX_W, 8f);
        for (int i = 0; i < n; i++) {                                    // a lit band for the active row
            if (!snap.seats.get(i).active) continue;
            shapes.setColor(0.20f, 0.15f, 0.05f, 1f);
            shapes.rect(10f, SB_TEX_H - head - (i + 1) * rowH, SB_TEX_W - 20f, rowH - 4f);
        }
        shapes.end();

        // Keep the texture's own alpha opaque where text is drawn, so letters do not punch holes.
        ui.setBlendFunctionSeparate(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA, GL20.GL_ONE, GL20.GL_ONE);
        ui.setProjectionMatrix(m);
        ui.begin();
        GlyphLayout gl = new GlyphLayout();
        String turn = snap.turnLabel;
        int dash = turn.indexOf(" - ");
        if (dash > 0) turn = turn.substring(0, dash);
        if (!snap.dayNight.isEmpty()) turn = turn + "  -  " + snap.dayNight;
        sbFont.getData().setScale(2.8f);
        sbFont.setColor(0.98f, 0.82f, 0.45f, 1f);
        gl.setText(sbFont, turn.toUpperCase());
        sbFont.draw(ui, turn.toUpperCase(), (SB_TEX_W - gl.width) / 2f, SB_TEX_H - 13f);
        // As big as a row allows: the face is only a couple of hundred pixels wide from the chair.
        float nameScale = n > 4 ? 2.2f : 3.0f, lifeScale = n > 4 ? 2.6f : 3.4f;
        for (int i = 0; i < n; i++) {
            Snapshot.SeatV s = snap.seats.get(i);
            float mid = SB_TEX_H - head - i * rowH - rowH / 2f;
            Color c = s.lost ? new Color(0.45f, 0.40f, 0.40f, 1f)
                    : s.active ? new Color(1f, 0.85f, 0.38f, 1f)
                    : s.isYou ? new Color(0.62f, 0.95f, 1f, 1f)
                    : new Color(0.80f, 0.86f, 0.92f, 1f);
            sbFont.setColor(c);
            sbFont.getData().setScale(nameScale);
            String name = (s.hasPriority ? "> " : "   ") + s.name;
            gl.setText(sbFont, name);
            sbFont.draw(ui, name, 34f, mid + gl.height / 2f);
            sbFont.getData().setScale(lifeScale);
            String life = s.lost ? "OUT" : String.valueOf(s.life);
            gl.setText(sbFont, life);
            sbFont.draw(ui, life, SB_TEX_W - 40f - gl.width, mid + gl.height / 2f);
        }
        ui.end();
        ui.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        sbBuffer.end();
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
        if (arcs == null) return;
        boolean anyTargets = false;
        for (Snapshot.CardV c : snap.stack) if (!c.targetIds.isEmpty() || !c.targetSeats.isEmpty()) anyTargets = true;
        if (snap.combatArcs.isEmpty() && !anyTargets) return;
        arcClock += frameDt;
        int n = snap.seats.size();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        Gdx.gl.glDepthMask(false);
        arcs.begin(cam.combined, GL20.GL_TRIANGLES);
        drawTargetArcs(n);
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
            float weight = weightForPower(a.power);
            if (blocked) ribbon(from.x, 0.12f, from.z, tx, 0.12f, tz, 0.75f, 0.45f, 0.40f, 0.30f, weight);
            else         ribbon(from.x, 0.12f, from.z, tx, 0.12f, tz, 1.00f, 0.35f, 0.15f, 0.95f, weight);
            for (int b : a.blockedBy) {
                Pose bp = poses.get(b);
                // A blocker's own arrow is drawn at its own weight, so a chump block reads as one.
                if (bp != null) ribbon(bp.x, 0.12f, bp.z, from.x, 0.12f, from.z,
                    0.35f, 0.70f, 1.00f, 0.95f, weightForPower(powerOf(b)));
            }
            if (arcs.getNumVertices() > 3600) {   // flush before the buffer fills
                arcs.end();
                arcs.begin(cam.combined, GL20.GL_TRIANGLES);
            }
        }
        arcs.end();
        Gdx.gl.glDepthMask(true);
    }

    /**
     * Spell targets: a violet arc from each stack item down to what it is aimed at - a permanent,
     * a player's seat, or another spell on the stack (a counterspell arcs across the bar). Drawn
     * whenever the stack holds a targeted spell or ability, not only in combat, since a removal
     * spell is exactly the moment you want to see who it is pointed at. A target with no drawn
     * card (one in a graveyard or a hand) has nowhere to point, so it gets no arc.
     */
    /** A drawn card's power, for weighting a blocker's arrow. 0 when it is not on the table. */
    private int powerOf(int cardId) {
        for (Drawn d : drawn) {
            if (d.card.id != cardId) continue;
            try { return Integer.parseInt(d.card.power); } catch (Exception e) { return 0; }
        }
        return 0;
    }

    private void drawTargetArcs(int n) {
        for (int i = 0; i < snap.stack.size() && i < stackAt.size(); i++) {
            Snapshot.CardV c = snap.stack.get(i);
            float[] from = stackAt.get(i);
            for (int id : c.targetIds) {
                float[] to = null;
                // Another stack item first (a counterspell's target), then a drawn card.
                for (int j = 0; j < snap.stack.size() && j < stackAt.size(); j++) {
                    if (j != i && snap.stack.get(j).id == id) { to = stackAt.get(j); break; }
                }
                if (to == null) {
                    Pose p = poses.get(id);
                    if (p != null) to = new float[] { p.x, p.y + 0.1f, p.z };
                }
                // Violet for a spell, teal for an activated or triggered ability. They behave
                // differently enough - an ability on the stack usually cannot be countered the
                // same way - that telling them apart at a glance is worth a colour.
                if (to != null) {
                    if (c.stackIsAbility) ribbon(from[0], from[1], from[2], to[0], to[1], to[2], 0.35f, 0.88f, 0.82f, 0.95f);
                    else ribbon(from[0], from[1], from[2], to[0], to[1], to[2], 0.78f, 0.45f, 1.0f, 0.95f);
                }
            }
            for (int seat : c.targetSeats) {
                if (seat < 0 || seat >= n) continue;
                Vector3 s = seatPos(seatAngle(seat, n), SEAT_DIST);
                if (c.stackIsAbility) ribbon(from[0], from[1], from[2], s.x, 0.12f, s.z, 0.35f, 0.88f, 0.82f, 0.95f);
                else ribbon(from[0], from[1], from[2], s.x, 0.12f, s.z, 0.78f, 0.45f, 1.0f, 0.95f);
            }
        }
    }

    /** One arc from (x0,z0) to (x1,z1) across the table: see the general form below. */
    private void ribbon(float x0, float z0, float x1, float z1, float r, float g, float b, float alpha) {
        ribbon(x0, 0.12f, z0, x1, 0.12f, z1, r, g, b, alpha, 1f);
    }

    private void ribbon(float x0, float y0, float z0, float x1, float y1, float z1,
                        float r, float g, float b, float alpha) {
        ribbon(x0, y0, z0, x1, y1, z1, r, g, b, alpha, 1f);
    }

    /**
     * How heavy an attacker's arrow should be, from its power.
     *
     * Square-rooted and clamped: a 20/20 is a bigger hit than a 4/4 but not five times the
     * ribbon, and without a ceiling a Ghalta-sized creature would black out the table. A 0-power
     * attacker still draws a thin line rather than nothing, because it is still attacking.
     */
    private static float weightForPower(int power) {
        float p = Math.max(0, power);
        return Math.min(2.6f, 0.55f + 0.45f * (float) Math.sqrt(p));
    }

    /**
     * One arc from (x0,y0,z0) to (x1,y1,z1): a straight blend between the two heights plus a
     * parabolic hump over it, ending in an arrowhead at the far end.
     */
    private void ribbon(float x0, float y0, float z0, float x1, float y1, float z1,
                        float r, float g, float b, float alpha, float weight) {
        float dx = x1 - x0, dz = z1 - z0;
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        if (len < 0.3f) return;
        float peak = 0.6f + 0.16f * len;              // longer reaches fly higher, so they clear rows
        float sx = dz / len, sz = -dx / len;          // sideways, in the table's plane
        float tEnd = Math.max(0.5f, 1f - ARROW_LEN / len);
        float half = ARC_WIDTH * weight / 2f;
        float px = 0, py = 0, pz = 0, pc = 0;
        for (int i = 0; i <= ARC_SEGMENTS; i++) {
            float t = tEnd * i / ARC_SEGMENTS;
            float x = x0 + dx * t, z = z0 + dz * t;
            float y = y0 + (y1 - y0) * t + peak * 4f * t * (1f - t);
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
        float aw = ARC_WIDTH * 1.9f * weight;
        vert(px - sx * aw, py, pz - sz * aw, r, g, b, alpha);
        vert(px + sx * aw, py, pz + sz * aw, r, g, b, alpha);
        vert(x1, y1, z1, r, g, b, alpha);
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

    private static final String FORCE_HOVER = System.getProperty("forge.ui3d.hover");

    // ---- 2D overlay -----------------------------------------------------------------------------

    private void drawOverlay() {
        int w = Gdx.graphics.getWidth(), h = Gdx.graphics.getHeight();
        Matrix4 flat = new Matrix4().setToOrtho2D(0, 0, w, h);

        drawCardBadges(flat);
        // The phase panel's background, BEFORE the label pass below: drawn after it (as it was),
        // its 88%-opaque fill covered the panel's own text and also the hover card and banner.
        drawPhasePanelShapes(h, flat);
        ui.setProjectionMatrix(flat);
        ui.begin();
        // The turn, the phase and which seat we are at used to be printed here as well as on the
        // phase panel, in the same corner, so the two drew on top of each other. The panel owns
        // all three now.
        String mode = (lift > 0.5f ? "OVERHEAD" : "SEATED")
            + (manualOverride ? " (TAB held - ESC to follow the game)" : "")
            + "   DOWN for your seat";
        font.setColor(0.45f, 0.62f, 0.75f, 1f);
        font.draw(ui, mode, 14, 38);
        font.setColor(0.35f, 0.50f, 0.62f, 1f);
        font.draw(ui, "LEFT / RIGHT  walk round the table      DOWN  back to your seat      TAB  camera"
            + "      right-drag  orbit      middle-drag  move      wheel  zoom      M  sound      H  hand back "
            + (cues != null && cues.muted() ? "(off)" : "(on)"), 14, 18);
        // Art counter: says at a glance whether Forge's picture cache was found at all, which is
        // the first thing to check if every card is still a coloured block.
        if (art != null) {
            String a = "art " + art.loaded() + " loaded / " + art.known() + " cached";
            font.draw(ui, a, w - 190, 18);
        }
        drawPhasePanel(w, h);
        bug.drawCogText(ui, font, w, h);
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
        drawCentreTracker();
        drawStackLabels();
        ui.end();
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
    private void drawCardBadges(Matrix4 flat) {
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
    private float badge(Drawn d, String text, float up, Color colour, GlyphLayout gl,
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
     * The end of the game: a band across the middle of the screen saying so, and who won. The
     * board stays visible behind it (it is translucent), because the final position is worth
     * looking at; Forge's own window still handles what happens next.
     */
    private void drawGameOver(int w, int h, Matrix4 flat) {
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
    private void drawSeatInfo(int w, int h) {
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
        // Day or night rides along with the phase: both are "what time is it" at the table.
        String text = snap.dayNight.isEmpty() ? snap.phase
            : snap.phase + "   " + (snap.dayNight.equals("DAY") ? "- day -" : "- night -");
        com.badlogic.gdx.graphics.g2d.GlyphLayout gl = new com.badlogic.gdx.graphics.g2d.GlyphLayout(fontBig, text);
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

    /** Screen rects of the prompt bar's two buttons, so drawing and hit-testing cannot drift. */
    private final float[] okBtn = { 0f, 0f, 0f, 0f }, cancelBtn = { 0f, 0f, 0f, 0f };

    /**
     * What Forge is asking, drawn where you are already looking.
     *
     * All of this - the question, the button labels, whether each is enabled - has been in the
     * Snapshot since the wrapper went in, and nothing drew it. That is why clicking a seat felt
     * like nothing happened and the mulligan appeared only in Forge's window: the 3D side knew
     * both and said nothing. Sits above the hand, where the eye already is.
     */
    private void drawPromptBar(int w, int h) {
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

        // Bigger than it was: this is the one thing on screen that says the game is waiting on
        // you, and at 62px with small text it read as a status line rather than a decision.
        float bw = Math.min(w - 60f, 1120f), bh = 96f, x = (w - bw) / 2f, y = 104f;
        boolean waiting = okOn || cancelOn;
        float btnW = 190f, btnH = 50f, gap = 14f;

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
        if (okOn) {
            okBtn[0] = bx; okBtn[1] = by; okBtn[2] = btnW; okBtn[3] = btnH;
            boolean hot = inRect(okBtn, mx, my);
            shapes.setColor(hot ? 0.24f : 0.16f, hot ? 0.64f : 0.48f, hot ? 0.40f : 0.31f, 1f);
            shapes.rect(bx, by, btnW, btnH);
        }
        shapes.end();
        ui.begin();

        float textW = bw - 60f - (okOn ? btnW + gap : 0f) - (cancelOn ? btnW + gap : 0f);
        fontBig.setColor(waiting ? new Color(1f, 0.93f, 0.74f, 1f) : new Color(0.70f, 0.82f, 0.92f, 1f));
        fontBig.draw(ui, msg, x + 22f, y + bh - 26f, textW, -1, true);
        if (okOn) {
            fontBig.setColor(0.86f, 1f, 0.90f, 1f);
            fontBig.draw(ui, okLabel, okBtn[0], okBtn[1] + 34f, btnW, 1, false);
            font.setColor(0.60f, 0.86f, 0.70f, 1f);
            font.draw(ui, "SPACE", okBtn[0], okBtn[1] + 14f, btnW, 1, false);
        }
        if (cancelOn) {
            fontBig.setColor(1f, 0.86f, 0.82f, 1f);
            fontBig.draw(ui, cancelLabel, cancelBtn[0], cancelBtn[1] + 34f, btnW, 1, false);
            font.setColor(0.88f, 0.64f, 0.60f, 1f);
            font.draw(ui, "X", cancelBtn[0], cancelBtn[1] + 14f, btnW, 1, false);
        }
    }

    private static boolean inRect(float[] r, float mx, float my) {
        return r[2] > 0f && mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3];
    }

    /**
     * A question the 3D window is answering itself.
     *
     * Deliberately the loudest thing on screen. Forge is no longer showing this question and its
     * game thread is blocked waiting for an answer, so a player who does not notice it has a
     * game that appears to have frozen - the one failure the whole input plan is arranged to
     * avoid. It names the card, spells out both keys, and says H hands it back to Forge.
     */
    private void drawPendingAsk(int w, int h) {
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

    /** Counts printed over each seat's library / graveyard / exile pads. */
    /** Forge's phases, in order, with the short labels a phase strip wants. */
    private static final String[][] PHASES = {
        { "UNTAP", "Untap" }, { "UPKEEP", "Upkeep" }, { "DRAW", "Draw" },
        { "MAIN1", "Main 1" },
        { "COMBAT_BEGIN", "Begin combat" }, { "COMBAT_DECLARE_ATTACKERS", "Attackers" },
        { "COMBAT_DECLARE_BLOCKERS", "Blockers" }, { "COMBAT_FIRST_STRIKE_DAMAGE", "First strike" },
        { "COMBAT_DAMAGE", "Damage" }, { "COMBAT_END", "End combat" },
        { "MAIN2", "Main 2" },
        { "END_OF_TURN", "End step" }, { "CLEANUP", "Cleanup" },
    };

    /** Screen rect of the follow-turn tickbox, so the click test and the drawing cannot drift. */
    private float[] followBox = { 0f, 0f, 0f, 0f };

    // Panel geometry. One place, because the background is a ShapeRenderer pass and the labels
    // are a SpriteBatch pass: two passes that cannot be interleaved and must agree to the pixel.
    private static final float PP_X = 14f, PP_W = 186f;
    private static final float PP_ROW = 21f;          // height of one step row
    private static final float PP_HEAD = 62f;         // turn line, seat line and the rule under them
    private static final float PP_FOOT = 34f;         // the tickbox
    private static final float PP_GAP = 7f;           // space inserted between groups

    /**
     * Where the turn changes character: beginning steps, first main, combat, second main, end.
     * The column leaves a gap at each, so combat reads as a block rather than six identical rows.
     * That is what you actually track in a four-player game - before combat, in it, or past it.
     */
    private static final int[] PP_GROUPS = { 3, 4, 10, 11 };

    /** Y of row i, measured down from the top of the panel. */
    private static float phaseRowY(int i, float top) {
        float gaps = 0f;
        for (int g : PP_GROUPS) if (i >= g) gaps += PP_GAP;
        return top - PP_HEAD - i * PP_ROW - gaps;
    }

    private float panelHeight() {
        return PP_HEAD + PHASES.length * PP_ROW + PP_GROUPS.length * PP_GAP + prioBlockHeight() + PP_FOOT;
    }

    // The priority block under the steps: a heading with the stack depth, then one row per seat.
    private static final float PB_HEAD = 22f, PB_ROW = 18f;

    private float prioBlockHeight() {
        return snap.seats.isEmpty() ? 0f : PP_GAP + PB_HEAD + snap.seats.size() * PB_ROW + 4f;
    }

    /** Top of the priority block: just under the last step row. */
    private float prioBlockTop(float top) {
        return phaseRowY(PHASES.length - 1, top) - PP_GAP;
    }

    /** Y (bottom) of seat row k in the priority block. */
    private float prioRowY(int k, float top) {
        return prioBlockTop(top) - PB_HEAD - (k + 1) * PB_ROW;
    }

    private int currentPhase() {
        for (int i = 0; i < PHASES.length; i++) {
            if (PHASES[i][0].equals(snap.phaseKey)) return i;
        }
        return -1;
    }

    /** The panel's filled shapes. Must run before the SpriteBatch pass that writes the labels. */
    private void drawPhasePanelShapes(int h, com.badlogic.gdx.math.Matrix4 flat) {
        float top = h - 14f;
        float height = panelHeight();
        float bottom = top - height;
        int current = currentPhase();

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.setProjectionMatrix(flat);
        shapes.begin(ShapeRenderer.ShapeType.Filled);

        shapes.setColor(0.02f, 0.08f, 0.13f, 0.88f);
        shapes.rect(PP_X, bottom, PP_W, height);
        shapes.setColor(0.18f, 0.56f, 0.71f, 0.95f);
        shapes.rect(PP_X, top - 3f, PP_W, 3f);
        shapes.setColor(0.10f, 0.26f, 0.34f, 1f);
        shapes.rect(PP_X + 10f, top - PP_HEAD + 12f, PP_W - 20f, 1f);     // rule under the turn line

        for (int i = 0; i < PHASES.length; i++) {
            float y = phaseRowY(i, top);
            boolean on = (i == current);
            boolean past = current >= 0 && i < current;
            if (on) {
                shapes.setColor(0.95f, 0.72f, 0.22f, 1f);
                shapes.rect(PP_X + 8f, y, PP_W - 16f, PP_ROW - 3f);
            } else {
                // A marker down the left edge: filled for steps already done, hollow for those to
                // come, so progress through the turn reads without the text.
                shapes.setColor(past ? new Color(0.20f, 0.42f, 0.52f, 1f)
                                     : new Color(0.09f, 0.20f, 0.27f, 1f));
                shapes.rect(PP_X + 8f, y + 4f, 5f, PP_ROW - 11f);
            }
        }

        // Priority block: a rule, then a square per seat - lit for whoever holds priority, filled
        // for those who have passed, dark for those still to act. The dark ones are who you wait on.
        if (!snap.seats.isEmpty()) {
            float bt = prioBlockTop(top);
            shapes.setColor(0.10f, 0.26f, 0.34f, 1f);
            shapes.rect(PP_X + 10f, bt, PP_W - 20f, 1f);
            for (int k = 0; k < snap.seats.size(); k++) {
                Snapshot.SeatV s = snap.seats.get(k);
                float y = prioRowY(k, top);
                if (s.lost) continue;
                if (s.hasPriority) shapes.setColor(0.45f, 0.92f, 1f, 1f);
                else if (s.passed) shapes.setColor(0.20f, 0.42f, 0.52f, 1f);
                else shapes.setColor(0.09f, 0.20f, 0.27f, 1f);
                shapes.rect(PP_X + 10f, y + 4f, 10f, 10f);
            }
        }

        float by = bottom + 11f;
        followBox = new float[] { PP_X + 8f, by, PP_W - 16f, 15f };
        shapes.setColor(0.06f, 0.16f, 0.22f, 1f);
        shapes.rect(PP_X + 8f, by, 14f, 14f);
        if (followTurn) {
            shapes.setColor(0.45f, 0.86f, 1f, 1f);
            shapes.rect(PP_X + 11f, by + 3f, 8f, 8f);
        }
        shapes.end();
    }

    /** The panel's labels. The background was already drawn by drawPhasePanelShapes. */
    private void drawPhasePanel(int w, int h) {
        float top = h - 14f;
        float bottom = top - panelHeight();
        int current = currentPhase();

        font.setColor(0.78f, 0.90f, 0.98f, 1f);
        font.draw(ui, snap.turnLabel, PP_X + 10f, top - 14f);

        // Which seat we are looking from, since the camera no longer follows the turn by itself.
        if (viewSeat >= 0 && viewSeat < snap.seats.size()) {
            Snapshot.SeatV v = snap.seats.get(viewSeat);
            if (v.isYou) {
                font.setColor(0.50f, 0.68f, 0.80f, 1f);
                font.draw(ui, "your seat", PP_X + 10f, top - 30f);
            } else {
                font.setColor(1f, 0.78f, 0.45f, 1f);
                font.draw(ui, "at " + v.name + "'s seat", PP_X + 10f, top - 30f);
            }
        }

        for (int i = 0; i < PHASES.length; i++) {
            float y = phaseRowY(i, top);
            boolean on = (i == current);
            boolean past = current >= 0 && i < current;
            if (on) font.setColor(0.06f, 0.09f, 0.12f, 1f);          // dark on the lit row
            else if (past) font.setColor(0.52f, 0.66f, 0.76f, 1f);
            else font.setColor(0.38f, 0.50f, 0.60f, 1f);
            font.draw(ui, PHASES[i][1], PP_X + 20f, y + PP_ROW - 7f);
        }

        if (!snap.seats.isEmpty()) {
            float bt = prioBlockTop(top);
            font.setColor(0.55f, 0.72f, 0.84f, 1f);
            font.draw(ui, "PRIORITY", PP_X + 10f, bt - 5f);
            String depth = snap.stack.isEmpty() ? "stack empty" : "stack " + snap.stack.size();
            GlyphLayout gl = new GlyphLayout(font, depth);
            font.setColor(snap.stack.isEmpty() ? new Color(0.40f, 0.52f, 0.62f, 1f) : new Color(1f, 0.80f, 0.40f, 1f));
            font.draw(ui, depth, PP_X + PP_W - 10f - gl.width, bt - 5f);
            for (int k = 0; k < snap.seats.size(); k++) {
                Snapshot.SeatV s = snap.seats.get(k);
                float y = prioRowY(k, top);
                String state = s.lost ? "out" : s.hasPriority ? "deciding" : s.passed ? "passed" : "waiting";
                if (s.lost) font.setColor(0.42f, 0.38f, 0.38f, 1f);
                else if (s.hasPriority) font.setColor(0.70f, 0.95f, 1f, 1f);
                else if (s.passed) font.setColor(0.48f, 0.62f, 0.72f, 1f);
                else font.setColor(0.80f, 0.86f, 0.92f, 1f);
                font.draw(ui, shortName(s.name), PP_X + 26f, y + PB_ROW - 4f);
                gl.setText(font, state);
                font.draw(ui, state, PP_X + PP_W - 10f - gl.width, y + PB_ROW - 4f);
            }
        }

        font.setColor(followTurn ? new Color(0.72f, 0.92f, 1f, 1f) : new Color(0.48f, 0.60f, 0.70f, 1f));
        font.draw(ui, "follow the turn", PP_X + 30f, bottom + 23f);
    }

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

    /**
     * The enlarged card that follows the mouse. When Forge has the real picture cached it is shown
     * large, the way a client zooms a card on hover, with a strip under it for what the printed
     * card cannot say:
     * current power/toughness, counters, what it is attached to, tapped/attacking/blocking, and
     * what a stack item is doing. A drawn face (uncached card, face-down back) or a picture still
     * loading keeps the text panel, which reads better than small generated print blown up.
     */
    private void drawDetailPanel(int w, int h, Matrix4 flat) {
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
    private void drawPicturePanel(Snapshot.CardV c, com.badlogic.gdx.graphics.Texture tex,
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
        commandPadModel.dispose();
        if (cues != null) cues.dispose();
        seatLostModel.dispose();
        if (sbBuffer != null) sbBuffer.dispose();
        if (sbFaceModel != null) sbFaceModel.dispose();
        if (sbBodyModel != null) sbBodyModel.dispose();
        if (sbFont != null) sbFont.dispose();
        bandCreature.dispose();
        bandOther.dispose();
        bandLand.dispose();
        monitorModel.dispose();
        selectModel.dispose();
        hoverModel.dispose();
        seatGlowModel.dispose();
        tableModel.dispose();
        cardModel.dispose();
        landModel.dispose();
        creatureModel.dispose();
        commanderModel.dispose();
        seatModel.dispose();
        seatPriorityModel.dispose();
    }
}
