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

/** 3D drawn after the cards: combat and spell-target arcs, and the hanging scoreboard. See TableBase for the chain. */
abstract class TableScene extends TableLayout {

    void createScoreboard(int attrs) {
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

    void drawScoreboard() {
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

    static void opacity(ModelInstance inst, float a) {
        com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute b =
            (com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute)
                inst.materials.get(0).get(com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute.Type);
        if (b != null) b.opacity = a;
    }

    /** Redraws the scoreboard texture, but only when what it shows has changed. */
    void refreshScoreboard() {
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

    /**
     * Targeting mode: a violet arc from the source to the mouse - snapped to a legal target when
     * the mouse is over one, otherwise to the spot on the table under the pointer - plus a fixed
     * gold arc to each target already picked. The "aiming" half of a spell, before Forge has it.
     */
    void drawAimArc() {
        drawPlanArcs();
        if (targeting == null) return;
        Pose from = poses.get(targeting.sourceId);
        if (from == null) return;
        for (int id : targeting.picked) {
            Pose p = poses.get(id);
            if (p != null) ribbon(from.x, from.y + 0.1f, from.z, p.x, p.y + 0.1f, p.z, 0.98f, 0.76f, 0.25f, 0.95f);
        }
        if (targeting.picked.size() >= targeting.max) return;
        float tx, ty, tz;
        if (hovered != null && targeting.legal.contains(hovered.card.id) && poses.containsKey(hovered.card.id)) {
            Pose p = poses.get(hovered.card.id);
            tx = p.x; ty = p.y + 0.1f; tz = p.z;
        } else {
            com.badlogic.gdx.math.collision.Ray ray = cam.getPickRay(Gdx.input.getX(), Gdx.input.getY());
            if (Math.abs(ray.direction.y) < 1e-4f) return;
            float k = -ray.origin.y / ray.direction.y;
            if (k <= 0f) return;
            tx = ray.origin.x + ray.direction.x * k; ty = 0.12f; tz = ray.origin.z + ray.direction.z * k;
        }
        ribbon(from.x, from.y + 0.1f, from.z, tx, ty, tz, 0.70f, 0.45f, 1f, 0.90f);
    }

    /**
     * Declaring attackers / blockers: an arc for every assignment made so far - red from attacker
     * to the defending seat's pad, blue from blocker onto attacker - and a lighter one from the
     * creature being dragged to the mouse, so the drop reads before it happens.
     */
    void drawPlanArcs() {
        if (combatPlan == null) return;
        int n = snap.seats.size();
        for (java.util.Map.Entry<Integer, Integer> e : combatPlan.attackSeat.entrySet()) {
            Pose p = poses.get(e.getKey());
            if (p == null || e.getValue() >= n) continue;
            Vector3 s = seatPos(seatAngle(e.getValue(), n), SEAT_DIST);
            ribbon(p.x, p.z, s.x, s.z, 1.00f, 0.35f, 0.15f, 0.95f);
        }
        for (java.util.Map.Entry<Integer, Integer> e : combatPlan.blockOn.entrySet()) {
            Pose b = poses.get(e.getKey()), a = poses.get(e.getValue());
            if (b != null && a != null) ribbon(b.x, b.z, a.x, a.z, 0.35f, 0.70f, 1.00f, 0.95f);
        }
        Pose d = poses.get(combatPlan.dragging);
        float[] m = mouseOnTable();
        if (d != null && m != null) {
            if (combatPlan.blocking) ribbon(d.x, d.z, m[0], m[1], 0.55f, 0.80f, 1.00f, 0.70f);
            else ribbon(d.x, d.z, m[0], m[1], 1.00f, 0.55f, 0.35f, 0.70f);
        }
    }

    void drawCombatArcs() {
        if (arcs == null) return;
        boolean anyTargets = false;
        for (Snapshot.CardV c : snap.stack) if (!c.targetIds.isEmpty() || !c.targetSeats.isEmpty()) anyTargets = true;
        if (snap.combatArcs.isEmpty() && !anyTargets && targeting == null && combatPlan == null) return;
        arcClock += frameDt;
        int n = snap.seats.size();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        Gdx.gl.glDepthMask(false);
        arcs.begin(cam.combined, GL20.GL_TRIANGLES);
        drawTargetArcs(n);
        drawAimArc();
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
    int powerOf(int cardId) {
        for (Drawn d : drawn) {
            if (d.card.id != cardId) continue;
            try { return Integer.parseInt(d.card.power); } catch (Exception e) { return 0; }
        }
        return 0;
    }

    void drawTargetArcs(int n) {
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
    void ribbon(float x0, float z0, float x1, float z1, float r, float g, float b, float alpha) {
        ribbon(x0, 0.12f, z0, x1, 0.12f, z1, r, g, b, alpha, 1f);
    }

    void ribbon(float x0, float y0, float z0, float x1, float y1, float z1,
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
    static float weightForPower(int power) {
        float p = Math.max(0, power);
        return Math.min(2.6f, 0.55f + 0.45f * (float) Math.sqrt(p));
    }

    /**
     * One arc from (x0,y0,z0) to (x1,y1,z1): a straight blend between the two heights plus a
     * parabolic hump over it, ending in an arrowhead at the far end.
     */
    void ribbon(float x0, float y0, float z0, float x1, float y1, float z1,
                        float r, float g, float b, float alpha, float weight) {
        float dx = x1 - x0, dz = z1 - z0;
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        if (len < 0.3f) return;
        float peak = 0.6f + 0.16f * len;              // longer reaches fly higher, so they clear rows
        float sx = dz / len, sz = -dx / len;          // sideways, in the table's plane
        float tEnd = Math.max(0.5f, 1f - ARROW_LEN / len);
        float half = ARC_WIDTH * weight / 2f;
        float px = 0, py = 0, pz = 0, pc = 0;
        final int segs = GraphicsQuality.arcSegments();         // ESC menu: graphics
        for (int i = 0; i <= segs; i++) {
            float t = tEnd * i / segs;
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

    void quad(float ax, float ay, float az, float bx, float by, float bz,
                      float cx, float cy, float cz, float dx, float dy, float dz,
                      float r, float g, float b, float a0, float a1) {
        vert(ax, ay, az, r, g, b, a0); vert(bx, by, bz, r, g, b, a0); vert(cx, cy, cz, r, g, b, a1);
        vert(ax, ay, az, r, g, b, a0); vert(cx, cy, cz, r, g, b, a1); vert(dx, dy, dz, r, g, b, a1);
    }

    void vert(float x, float y, float z, float r, float g, float b, float a) {
        arcs.color(r, g, b, a);
        arcs.vertex(x, y, z);
    }
}
