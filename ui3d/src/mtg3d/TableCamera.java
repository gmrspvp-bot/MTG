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

/** The camera: which seat we look from, the orbit round the table, the seated/overhead lift, zoom. See TableBase for the chain. */
abstract class TableCamera extends TableBase {

    void step(int delta, int n) {
        followTurn = false;                  // stepping by hand takes over from the turn
        if (viewSeat < 0) viewSeat = snap.youIndex();
        setViewSeat(((viewSeat + delta) % n + n) % n, n);
    }

    /** Points the orbit at a seat, choosing whichever way round is shorter. */
    void setViewSeat(int seat, int n) {
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

    void updateCamera(float dt) {
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
}
