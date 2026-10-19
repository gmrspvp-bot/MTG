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

    private Model tableModel, cardModel, landModel, creatureModel, commanderModel, seatModel;
    private ModelInstance tableInst;
    private final List<ModelInstance> seatPads = new ArrayList<>();

    /** One drawable card plus where it is, so the same list serves rendering and picking. */
    private static final class Drawn {
        ModelInstance inst;
        BoundingBox bounds = new BoundingBox();
        Snapshot.CardV card;
        Snapshot.SeatV seat;
    }

    private final List<Drawn> drawn = new ArrayList<>();
    private Drawn hovered;

    /** 0 = fully seated, 1 = fully overhead. Everything about the camera follows this. */
    private float lift = 0f;
    private float liftTarget = 0f;
    private boolean manualOverride = false;
    private boolean manualValue = false;

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
        cam.near = 0.1f;
        cam.far = 300f;

        modelBatch = new ModelBatch();
        builder = new ModelBuilder();
        ui = new SpriteBatch();
        shapes = new ShapeRenderer();
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
        seatModel = builder.createBox(9.2f, 0.06f, 4.2f,
            new Material(ColorAttribute.createDiffuse(new Color(0.10f, 0.24f, 0.34f, 1f))), attrs);

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

        handleInput();
        updateCamera(Gdx.graphics.getDeltaTime());
        layout();
        pick();

        Gdx.gl.glClearColor(0.02f, 0.05f, 0.08f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);

        modelBatch.begin(cam);
        modelBatch.render(tableInst, env);
        for (ModelInstance p : seatPads) modelBatch.render(p, env);
        for (Drawn d : drawn) modelBatch.render(d.inst, env);
        modelBatch.end();

        drawOverlay();
    }

    private void handleInput() {
        if (Gdx.input.isKeyJustPressed(Input.Keys.TAB)) {
            manualOverride = true;
            manualValue = lift < 0.5f;       // toggle to whichever pose we are not in
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) manualOverride = false;
    }

    private void updateCamera(float dt) {
        boolean wantOverhead = manualOverride ? manualValue : (snap.combat || snap.targeting);
        liftTarget = wantOverhead ? 1f : 0f;

        // Ease towards the target; the rate is per-second so it is frame-rate independent.
        float rate = 2.6f;
        lift += (liftTarget - lift) * Math.min(1f, dt * rate);
        if (Math.abs(liftTarget - lift) < 0.001f) lift = liftTarget;

        float t = smooth(lift);

        // SEATED: just behind and above your own row, looking across the table.
        Vector3 seatedEye = new Vector3(0f, 3.1f, SEAT_DIST + 5.6f);
        Vector3 seatedAt  = new Vector3(0f, 0f, SEAT_DIST - 1.6f);
        // OVERHEAD: high, slightly back, looking at the middle of the table.
        Vector3 overEye   = new Vector3(0f, 17.5f, 7.0f);
        Vector3 overAt    = new Vector3(0f, 0f, 0.4f);

        Vector3 eye = new Vector3(seatedEye).lerp(overEye, t);
        Vector3 at  = new Vector3(seatedAt).lerp(overAt, t);

        cam.position.set(eye);
        cam.up.set(0, 1, 0);
        cam.lookAt(at);
        cam.update();
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
        if (snap.seats.isEmpty()) return;

        int n = snap.seats.size();
        int youIdx = snap.youIndex();
        for (int i = 0; i < n; i++) {
            Snapshot.SeatV seat = snap.seats.get(i);
            // Rotate the seating so you are always at the near edge, whatever seat you really are.
            int rel = ((i - youIdx) % n + n) % n;
            float angle = (float) (rel * 2 * Math.PI / n);   // 0 = near edge, then anticlockwise

            ModelInstance pad = new ModelInstance(seatModel);
            Vector3 c = seatPos(angle, SEAT_DIST);
            pad.transform.setToTranslation(c.x, -0.01f, c.z).rotate(Vector3.Y, (float) Math.toDegrees(-angle));
            seatPads.add(pad);

            // Two rows per seat: nonlands nearer the middle of the table, lands behind them.
            placeRow(seat, seat.nonlands, angle, SEAT_DIST - 1.0f);
            placeRow(seat, seat.lands, angle, SEAT_DIST + 1.1f);
        }
        placeHand();
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
        float step = CARD_W + GAP;
        float width = cards.size() * step - GAP;
        for (int j = 0; j < cards.size(); j++) {
            Snapshot.CardV c = cards.get(j);
            float along = -width / 2f + j * step + CARD_W / 2f;
            Vector3 w = rotateY(along, dist, angle);
            // LibGDX's rotate(Y, deg) is the inverse of the rotation above, hence the minus.
            add(c, seat, w.x, w.z, (float) Math.toDegrees(-angle) + (c.tapped ? 90f : 0f));
        }
    }

    private void placeHand() {
        if (snap.hand.isEmpty()) return;
        float step = CARD_W * 0.72f;
        float width = snap.hand.size() * step;
        for (int i = 0; i < snap.hand.size(); i++) {
            Snapshot.CardV c = snap.hand.get(i);
            float x = -width / 2f + i * step + step / 2f;
            add(c, null, x, SEAT_DIST + 4.3f, 0f);
        }
    }

    private void add(Snapshot.CardV c, Snapshot.SeatV seat, float x, float z, float yawDeg) {
        Model m = c.commander ? commanderModel : c.land ? landModel : c.creature ? creatureModel : cardModel;
        Drawn d = new Drawn();
        d.inst = new ModelInstance(m);
        d.card = c;
        d.seat = seat;
        float y = (seat == null) ? 0.55f : 0.02f;        // hand cards float slightly above the table
        d.inst.transform.setToTranslation(x, y, z).rotate(Vector3.Y, yawDeg);
        d.inst.calculateBoundingBox(d.bounds);
        d.bounds.mul(d.inst.transform);
        drawn.add(d);
    }

    /** Ray-pick the card under the mouse; nearest hit wins. */
    private void pick() {
        hovered = null;
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
        String mode = (lift > 0.5f ? "OVERHEAD" : "SEATED")
            + (manualOverride ? " (TAB held - ESC to follow the game)" : "")
            + "   TAB camera";
        font.setColor(0.45f, 0.62f, 0.75f, 1f);
        font.draw(ui, mode, 14, 22);
        drawSeatInfo(w, h);
        ui.end();
    }

    /** Life totals and zone counts, printed near each seat so the pod reads at a glance. */
    private void drawSeatInfo(int w, int h) {
        int n = snap.seats.size();
        int youIdx = snap.youIndex();
        for (int i = 0; i < n; i++) {
            Snapshot.SeatV s = snap.seats.get(i);
            int rel = ((i - youIdx) % n + n) % n;
            float angle = (float) (rel * 2 * Math.PI / n);
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
        }
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
        font.dispose();
        fontBig.dispose();
        tableModel.dispose();
        cardModel.dispose();
        landModel.dispose();
        creatureModel.dispose();
        commanderModel.dispose();
        seatModel.dispose();
    }
}
