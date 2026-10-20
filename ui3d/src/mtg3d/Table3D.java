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
public final class Table3D extends TableInput {

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
        // The table body: now only its sides show, so it is darker than the felt laid on top.
        tableModel = builder.createBox(TABLE_HALF * 2, 0.5f, TABLE_HALF * 2,
            new Material(ColorAttribute.createDiffuse(new Color(0.03f, 0.07f, 0.11f, 1f))), attrs);
        tableInst = new ModelInstance(tableModel);
        tableInst.transform.setToTranslation(0, -0.27f, 0);
        createSurround(attrs);

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
        stackFrameModel = builder.createBox(STACK_FRAME_UNIT, 0.05f, CARD_H * STACK_SCALE + 0.8f,
            new Material(ColorAttribute.createDiffuse(new Color(0.05f, 0.14f, 0.21f, 1f))), attrs);
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
                if (numbers.isOpen()) {
                    numbers.scrollBy(amountY);
                    return true;
                }
                if (options.isOpen()) return true;
                // The wheel zooms the camera when no panel wants it; DOWN / HOME resets it.
                zoom = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, zoom * (1f + amountY * 0.1f)));
                return true;
            }
        });
        snap = source.get();
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
        poolReset();
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
        modelBatch.render(floorInst);          // unlit: the floor's light pool is in its texture
        modelBatch.render(tableInst, env);
        modelBatch.render(feltInst, env);
        for (ModelInstance r : railInsts) modelBatch.render(r, env);
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
        // The hanging scoreboard has moved into the left panel - one place to look instead of
        // two, and it no longer floats over the middle of the table where the stack now sits.
        // -Dforge.ui3d.hangingscore=true brings it back.
        if (HANGING_SCORE) drawScoreboard();
        drawCombatArcs();

        drawOverlay();

        // A list Forge wants shown opens the search panel in browse mode. Done here rather than
        // from the game thread because the panel belongs to the renderer, and the game thread is
        // already blocked inside reveal() waiting for it to be dismissed.
        // A choice Forge is waiting on - a library search, a graveyard pick. The game thread is
        // blocked inside it, so this must open or Handoff will eventually hand it back.
        Gui3D.Choice choice = Gui3D.pendingChoice();
        if (choice != null && search != null && !search.isOpen()) {
            final java.util.List<Snapshot.CardV> opts = choice.items;
            search.open(choice.title,
                choice.max <= 1 ? "Choose one." : "Choose " + choice.min + " to " + choice.max + ".",
                opts, choice.min, choice.max, chosen -> {
                    // Back as indices into the list Forge handed us, so the entity returned is
                    // the very object it offered.
                    java.util.List<Integer> idx = new ArrayList<>();
                    for (Snapshot.CardV c : chosen) {
                        int i = opts.indexOf(c);
                        if (i >= 0) idx.add(i);
                    }
                    Gui3D.answerChoice(idx);
                });
        }
        // A zone Forge opened for us to look at. Nothing is blocked on it, so closing the panel
        // simply clears it; Forge's own window is still showing the same zone.
        Gui3D.Peek peek = Gui3D.pendingPeek();
        if (peek != null && search != null && !search.isOpen() && Gui3D.pendingReveal() == null) {
            search.open(peek.title, "Close when you have read it.", peek.items, 0, 0,
                chosen -> Gui3D.dismissPeek());
        }
        Gui3D.Reveal rev = Gui3D.pendingReveal();
        if (rev != null && search != null && !search.isOpen()) {
            search.open(rev.title, "Close when you have read it.", rev.items, 0, 0,
                chosen -> Gui3D.dismissReveal());
        }
        if (search != null) {
            search.update(Gdx.graphics.getDeltaTime());
            search.render(ui, shapes, font, fontBig);
        }
        options.update(Gdx.graphics.getDeltaTime());
        options.render(ui, shapes, font, fontBig);
        numbers.update(Gdx.graphics.getDeltaTime());
        numbers.render(ui, shapes, font, fontBig);
        maybeShoot();
        WindowMemory.track();
    }

    void maybeShoot() {
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
        stackFrameModel.dispose();
        tableModel.dispose();
        feltModel.dispose();
        floorModel.dispose();
        railModel.dispose();
        feltTex.dispose();
        floorTex.dispose();
        cardModel.dispose();
        landModel.dispose();
        creatureModel.dispose();
        commanderModel.dispose();
        seatModel.dispose();
        seatPriorityModel.dispose();
    }
}
