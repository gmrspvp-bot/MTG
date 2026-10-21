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
        // Targeting mode: a violet slab under each legal target, gold once picked.
        targetModel = builder.createBox(CARD_W * 1.24f, 0.02f, CARD_H * 1.16f,
            new Material(ColorAttribute.createDiffuse(new Color(0.62f, 0.38f, 0.95f, 1f))), attrs);
        pickedModel = builder.createBox(CARD_W * 1.24f, 0.02f, CARD_H * 1.16f,
            new Material(ColorAttribute.createDiffuse(new Color(0.98f, 0.76f, 0.25f, 1f))), attrs);
        // Declaring blockers: a creature assigned to block, in the blue of the block arcs.
        blockModel = builder.createBox(CARD_W * 1.22f, 0.02f, CARD_H * 1.14f,
            new Material(ColorAttribute.createDiffuse(new Color(0.30f, 0.62f, 1f, 1f))), attrs);
        // An ability on the stack, or being chosen: its permanent, in the ability ribbon's teal.
        sourceModel = builder.createBox(CARD_W * 1.22f, 0.02f, CARD_H * 1.14f,
            new Material(ColorAttribute.createDiffuse(new Color(0.35f, 0.88f, 0.82f, 1f))), attrs);
        // Floating mana, as beads in six little trays on each board. White so the colour can
        // be set per bead - one model, six colours, however many are floating.
        beadModel = builder.createSphere(BEAD_D, BEAD_D, BEAD_D, 12, 10,
            new Material(ColorAttribute.createDiffuse(new Color(1f, 1f, 1f, 1f))), attrs);
        beadTrayModel = builder.createBox(BEAD_D * 1.45f, 0.012f, BEAD_D * 1.45f,
            new Material(ColorAttribute.createDiffuse(new Color(0.05f, 0.12f, 0.17f, 1f))), attrs);
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
        // The monitor's case: a near-black bezel. The glass (screenModel) is inset on its face and
        // the stand (standModel) holds it up off the felt (TableLayout.placeMonitors).
        // Wide enough for the commander's portrait beside the readout (TableHud
        // .drawCentreTracker). Four of these sit on a circle of radius MONITOR_DIST, so the two
        // sizes have to be chosen together or the neighbours overlap.
        monitorModel = builder.createBox(2.7f, 0.07f, 1.45f,
            new Material(ColorAttribute.createDiffuse(new Color(0.03f, 0.05f, 0.07f, 1f))), attrs);
        // Built white with an emissive glow; each instance sets its own tint (active / you / other).
        screenModel = builder.createBox(2.52f, 0.01f, 1.27f,
            new Material(ColorAttribute.createDiffuse(Color.WHITE), ColorAttribute.createEmissive(new Color(0.10f, 0.20f, 0.28f, 1f))), attrs);
        standModel = builder.createBox(0.16f, 0.42f, 0.16f,
            new Material(ColorAttribute.createDiffuse(new Color(0.06f, 0.07f, 0.09f, 1f))), attrs);
        // Built white: every instance overrides its own diffuse colour each frame, so the
        // model's colour is never the one you see (see TableLayout.placeSeatEdges).
        // The copies under the top card of a stack. Card-sized and card-coloured edge-on, so a
        // pile of twenty Treasures reads as a pile of cards rather than a block.
        stackShimModel = builder.createBox(CARD_W, STACK_STEP, CARD_H,
            new Material(ColorAttribute.createDiffuse(new Color(0.78f, 0.78f, 0.74f, 1f))), attrs);
        seatEdgeModel = builder.createBox(BAND_UNIT, 0.016f, EDGE_W,
            new Material(ColorAttribute.createDiffuse(Color.WHITE)), attrs);
        zoneModel = builder.createBox(CARD_W * 0.88f, 0.03f, CARD_H * 0.88f,
            new Material(ColorAttribute.createDiffuse(new Color(0.06f, 0.18f, 0.26f, 1f))), attrs);
        // A battlefield's plinth: a shade lighter and greyer than the felt, so the raised area reads.
        plinthModel = builder.createBox(1f, 1f, 1f,
            new Material(ColorAttribute.createDiffuse(new Color(0.09f, 0.20f, 0.29f, 1f))), attrs);
        // A zone's pile, a little smaller than its pad so the pad shows as a rim. Built white: each
        // instance sets its own colour (library / graveyard / exile) every frame.
        pileModel = builder.createBox(CARD_W * 0.78f, 1f, CARD_H * 0.78f,
            new Material(ColorAttribute.createDiffuse(Color.WHITE)), attrs);
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
        order = new OrderPanel(art);
        mulligan = new MulliganPanel(art);
        damage = new DamagePanel(art);
        // libGDX only delivers the wheel through an InputProcessor; everything else this view
        // needs is polled, so this is the one handler.
        Gdx.input.setInputProcessor(new com.badlogic.gdx.InputAdapter() {
            // Typed characters only matter to the bug box and the text panel (Forge asking for a
            // typed answer); everything else this view needs is polled, so this handler stays tiny.
            @Override public boolean keyTyped(char c) {
                return text.typed(c);
            }

            @Override public boolean keyDown(int keycode) {
                return text.key(keycode);
            }
            @Override public boolean scrolled(float amountX, float amountY) {
                if (search.isOpen()) {
                    search.scrollBy(amountY);
                    return true;
                }
                if (numbers.isOpen()) {
                    numbers.scrollBy(amountY);
                    return true;
                }
                if (options.isOpen()) {
                    options.scrollBy(amountY);
                    return true;
                }
                // The wheel zooms the camera when no panel wants it; DOWN / HOME resets it.
                zoom = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, zoom * (1f + amountY * 0.1f)));
                return true;
            }
        });
        snap = source.get();
    }

    /** The question each panel was last opened for, so an answered one is never shown twice. */
    Object shownChoice, shownNumber, shownOrder, shownDamage, shownText;
    /** The opened zone the search panel was last opened for, and whether as a pick panel. */
    Object shownPeek;
    boolean shownPeekPick;
    /** The Forge question OptionPanel was last opened for; null-or-stale when the menu has it. */
    Object optionsOwner;

    /**
     * Opens the panel for whatever Forge is waiting on - a text choice, a number, an ordering, a
     * damage split - once per question, and closes a panel whose question has gone (answered in
     * Forge's window, or handed back by Handoff). Card choices stay with SearchPanel, opened by
     * the caller as before.
     */
    void servicePending(Gui3D.Choice choice) {
        if (choice != null && !choice.cards && choice != shownChoice && !options.isOpen()) {
            shownChoice = choice;
            final Gui3D.Choice c = choice;
            List<OptionPanel.Option> opts = new ArrayList<>();
            for (int i = 0; i < c.labels.size(); i++) {
                Snapshot.CardV v = c.items.get(i);
                String detail = v.typeLine.equals("Player") ? "player" : "";
                opts.add(new OptionPanel.Option(c.labels.get(i), detail, null));
            }
            options.open(c.title, c.max <= 1 ? (c.min == 0 ? "Choose one, or ESC for none." : "Choose one.")
                : "Choose " + (c.min == c.max ? String.valueOf(c.max) : c.min + " to " + c.max) + ".",
                opts, c.min, c.max, picked -> Gui3D.answerChoice(picked));
            // Which ability of a card: a drop-down beside that card, wherever it is drawn.
            if (c.anchorCardId >= 0) {
                for (Drawn d : drawn) {
                    if (d.card == null || d.card.id != c.anchorCardId) continue;
                    Vector3 mid = d.bounds.getCenter(new Vector3());
                    Vector3 s = cam.project(mid);
                    if (s.z <= 1f) options.anchorAt(s.x, s.y);
                    break;
                }
            }
            optionsOwner = c;
        }
        // Only close the panel if it is still showing that question - the ESC menu uses it too.
        if (choice == null && shownChoice != null) {
            if (options.isOpen() && optionsOwner == shownChoice) options.close();
            shownChoice = null;
        }

        Gui3D.NumberAsk n = Gui3D.pendingNumber();
        if (n != null && n != shownNumber && !numbers.isOpen()) {
            shownNumber = n;
            numbers.open(n.title, "", n.min, n.max, n.min, null, Gui3D::answerNumber);
        }
        if (n == null && shownNumber != null && numbers.isOpen()) { numbers.close(); shownNumber = null; }

        Gui3D.TextAsk ta = Gui3D.pendingText();
        if (ta != null && ta != shownText && !text.isOpen()) {
            shownText = ta;
            text.open(ta.title, ta.prompt, ta.initial, ta.numeric, Gui3D::answerText);
        }
        if (ta == null && shownText != null) { text.close(); shownText = null; }

        Gui3D.OrderAsk o = Gui3D.pendingOrder();
        if (o != null && o != shownOrder && !order.isOpen()) {
            shownOrder = o;
            final List<Snapshot.CardV> items = o.items;
            List<List<Snapshot.CardV>> rows = new ArrayList<>();
            if (o.rowNames != null) {
                // Top / Bottom (manipulateCardList): drag between the rows and within them.
                rows.add(new ArrayList<>(items.subList(0, Math.min(o.firstRowCount, items.size()))));
                if (o.rowNames.length > 1) rows.add(new ArrayList<>(items.subList(Math.min(o.firstRowCount, items.size()), items.size())));
                String[] words = new String[o.rowNames.length];
                java.util.Arrays.fill(words, "");
                words[0] = o.rowNames[0].equals("Top") ? "on top" : "";
                order.open(o.title, "Drag each card to the top or the bottom, in order, then Confirm.", rows, o.rowNames, words, r -> {
                    if (r == null) { Gui3D.answerOrderRows(null, null); return; }
                    List<List<Integer>> idx = new ArrayList<>();
                    for (List<Snapshot.CardV> row : r) {
                        List<Integer> one = new ArrayList<>();
                        for (Snapshot.CardV v : row) for (int i = 0; i < items.size(); i++) if (items.get(i) == v) one.add(i);
                        idx.add(one);
                    }
                    Gui3D.answerOrderRows(idx.get(0), idx.size() > 1 ? idx.get(1) : new ArrayList<>());
                });
            } else {
                rows.add(new ArrayList<>(items));
                order.open(o.title, "Drag them into order, then Confirm.", rows,
                    new String[] { o.caption.isEmpty() ? "Order" : o.caption }, new String[] { "first" }, r -> {
                        if (r == null) { Gui3D.answerOrder(null); return; }
                        List<Integer> idx = new ArrayList<>();
                        for (Snapshot.CardV v : r.get(0)) {
                            for (int i = 0; i < items.size(); i++) if (items.get(i) == v) idx.add(i);
                        }
                        Gui3D.answerOrder(idx);
                    });
            }
        }
        if (o == null && shownOrder != null && order.isOpen()) { order.close(); shownOrder = null; }

        Gui3D.DamageAsk d = Gui3D.pendingDamage();
        if (d != null && d != shownDamage && !damage.isOpen()) {
            shownDamage = d;
            damage.open(d.attacker, d.damage, d.blockers, d.lethal, d.trample, (perBlocker, toPlayer) -> Gui3D.answerDamage(perBlocker, toPlayer));
        }
        if (d == null && shownDamage != null && damage.isOpen()) { damage.close(); shownDamage = null; }
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
        // The rail on the camera's own side is skipped: seated, the camera sits just above it, and
        // it filled the bottom of the screen as a dark band (owner, 2026-10-21: "a black bar on
        // the bottom of the screen"). The other three still frame the table. Rail i stands at
        // angle i * 90 degrees, in the same frame as the camera's orbit.
        float twoPi = (float) (Math.PI * 2);
        float o = ((orbit % twoPi) + twoPi) % twoPi;
        int near = Math.round(o / (float) (Math.PI / 2)) % 4;
        for (int i = 0; i < railInsts.size(); i++) if (i != near) modelBatch.render(railInsts.get(i), env);
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
        servicePending(choice);
        if (choice != null && choice.cards && choice != shownChoice && search != null && !search.isOpen()) {
            shownChoice = choice;
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
        // Forge finished showing it (hideRevealedCards): the panel we opened for it closes too.
        if (peek == null && shownPeek != null && search != null) {
            if (search.isOpen()) search.close();
            shownPeek = null;
        }
        if (peek != null && search != null && Gui3D.pendingReveal() == null) {
            // Searching a library: Forge offers the cards you may take (setSelectables), and you
            // click them in its zone window. Here the panel then shows just those, pickable, and
            // a pick is sent as that same click (Gui3D.clickCard) - Forge judges it exactly as
            // it judges a click in its own window. Looking at a hand: nothing is offered, so the
            // panel only shows the cards. Forge may offer them after the zone opens, so a
            // browse panel for this zone is reopened as a pick panel when that happens.
            java.util.List<Snapshot.CardV> offered = new ArrayList<>();
            for (Snapshot.CardV v : peek.items) if (!v.faceDown && Gui3D.isSelectable(v.id)) offered.add(v);
            boolean pick = !offered.isEmpty();
            boolean ours = shownPeek == peek && search.isOpen();
            if (!search.isOpen() || (ours && shownPeekPick != pick)) {
                shownPeek = peek;
                shownPeekPick = pick;
                if (pick) {
                    search.open(peek.title, "Choose - SPACE when done if Forge asks for more.", offered, 1, offered.size(),
                        chosen -> {
                            for (Snapshot.CardV v : chosen) Gui3D.clickCard(v.id);
                            Gui3D.dismissPeek();
                        });
                } else {
                    search.open(peek.title, "Close when you have read it.", peek.items, 0, 0,
                        chosen -> Gui3D.dismissPeek());
                }
            }
        }
        Gui3D.Reveal rev = Gui3D.pendingReveal();
        if (rev != null && search != null && !search.isOpen()) {
            search.open(rev.title, "Close when you have read it.", rev.items, 0, 0,
                chosen -> Gui3D.dismissReveal());
        }
        if (search != null) {
            search.update(Gdx.graphics.getDeltaTime());
            search.render(ui, shapes, font, fontBig);
            drawPanelHover();          // over the panel, not under it
        }
        options.update(Gdx.graphics.getDeltaTime());
        options.render(ui, shapes, font, fontBig);
        numbers.update(Gdx.graphics.getDeltaTime());
        numbers.render(ui, shapes, font, fontBig);
        text.render(ui, shapes, font, fontBig, Gdx.graphics.getDeltaTime());
        order.update(Gdx.graphics.getDeltaTime());
        order.render(ui, shapes, font, fontBig);
        mulligan.update(Gdx.graphics.getDeltaTime());
        mulligan.render(ui, shapes, font, fontBig);
        damage.update(Gdx.graphics.getDeltaTime());
        damage.render(ui, shapes, font, fontBig);
        // The end screen opens by itself, once, when the game ends; ESC leaves the board to look at.
        if (snap.gameOver && !endShown) {
            endShown = true;
            endScreen.open(snap, what -> {
                if (Gui3D.active()) Gui3D.nextGame(what);
                else System.out.println("UI3D: end screen chose " + what + " (demo)");
            });
        }
        if (!snap.gameOver) endShown = false;
        endScreen.update(Gdx.graphics.getDeltaTime());
        endScreen.render(ui, shapes, font, fontBig);
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
        pileModel.dispose();
        plinthModel.dispose();
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
        screenModel.dispose();
        standModel.dispose();
        selectModel.dispose();
        targetModel.dispose();
        pickedModel.dispose();
        blockModel.dispose();
        sourceModel.dispose();
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
