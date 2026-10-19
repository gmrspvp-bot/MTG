package mtg3d;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

import java.util.concurrent.LinkedBlockingQueue;

/**
 * Sound cues for the moments that need you, and only those.
 *
 * Priority reaches every player at nearly every step in Forge, so a sound on "you have priority"
 * would be a metronome. Instead there are four cues, each fired on a CHANGE between snapshots:
 *
 *   your turn begins                       two rising notes
 *   an opponent puts something on the      one bright ping - the moment to think about a response
 *     stack
 *   something attacks you or your          a low double knock
 *     planeswalker / battle
 *   the game ends                          a rising chord if you won, falling if not
 *
 * The tones are synthesised (a sine with a little second harmonic and a quick decay), so there
 * are no audio files to ship. They play through javax.sound on a daemon thread of their own,
 * not libGDX audio: that needs no OpenAL context inside Forge's JVM, and a slow or missing sound
 * device can never stall the render thread. If there is no sound device, cues are silently off.
 */
final class Cues {
    private static final float RATE = 44100f;
    private static final float VOLUME = 0.22f;

    private final LinkedBlockingQueue<short[]> queue = new LinkedBlockingQueue<>();
    private volatile boolean muted;
    private volatile boolean closed;

    // What the previous snapshot looked like, so cues fire on changes only.
    private boolean primed;
    private int lastActive = -1;
    private int lastStackSize;
    private int lastStackTopId = Integer.MIN_VALUE;
    private final java.util.Set<Integer> attackersOnYou = new java.util.HashSet<>();
    private boolean lastGameOver;

    Cues() {
        Thread t = new Thread(this::play, "mtg3d-cues");
        t.setDaemon(true);
        t.start();
    }

    boolean muted() { return muted; }

    void toggleMute() { muted = !muted; }

    void dispose() {
        closed = true;
        queue.offer(new short[0]);   // wake the player thread so it can finish
    }

    /** Call once per frame on the render thread with the snapshot being drawn. */
    void update(Snapshot s) {
        if (s == null || s.seats.isEmpty()) return;
        int you = -1;
        for (int i = 0; i < s.seats.size(); i++) if (s.seats.get(i).isYou) you = i;
        String youName = you >= 0 ? s.seats.get(you).name : "";

        int active = -1;
        for (int i = 0; i < s.seats.size(); i++) if (s.seats.get(i).active) active = i;

        int topId = s.stack.isEmpty() ? Integer.MIN_VALUE : s.stack.get(s.stack.size() - 1).id;
        String topController = s.stack.isEmpty() ? "" : s.stack.get(s.stack.size() - 1).controller;

        // Attacks on you: on your seat, or on a permanent you control (a planeswalker, a battle).
        java.util.Set<Integer> onYou = new java.util.HashSet<>();
        if (you >= 0) {
            for (Snapshot.CombatArc a : s.combatArcs) {
                if (a.defenderSeat == you) onYou.add(a.attackerId);
            }
        }

        if (primed && you >= 0) {
            if (active == you && lastActive != you) cue(notes(new float[] { 659.3f, 880f }, 0.11f));
            boolean grew = s.stack.size() > lastStackSize && topId != lastStackTopId;
            if (grew && !topController.isEmpty() && !topController.equals(youName)) {
                cue(notes(new float[] { 1318.5f }, 0.16f));
            }
            boolean newAttack = false;
            for (int id : onYou) if (!attackersOnYou.contains(id)) newAttack = true;
            if (newAttack) cue(notes(new float[] { 196f, 196f }, 0.09f));
            if (s.gameOver && !lastGameOver) {
                boolean won = !youName.isEmpty() && youName.equals(s.winner);
                cue(won ? notes(new float[] { 523.3f, 659.3f, 784f, 1046.5f }, 0.13f)
                        : notes(new float[] { 784f, 622.3f, 523.3f, 392f }, 0.15f));
            }
        }
        primed = true;
        lastActive = active;
        lastStackSize = s.stack.size();
        lastStackTopId = topId;
        attackersOnYou.clear();
        attackersOnYou.addAll(onYou);
        lastGameOver = s.gameOver;
    }

    /** Cues fired so far, counted even when muted - lets a test check the triggers silently. */
    int fired;

    private void cue(short[] pcm) {
        fired++;
        if (!muted && queue.size() < 4) queue.offer(pcm);   // never let a backlog build up
    }

    /** A short phrase: each note `len` seconds, played one after another. */
    private static short[] notes(float[] hz, float len) {
        int per = (int) (RATE * len);
        short[] out = new short[per * hz.length];
        for (int n = 0; n < hz.length; n++) {
            for (int i = 0; i < per; i++) {
                double t = i / RATE;
                double env = Math.min(1.0, i / (RATE * 0.005)) * Math.exp(-t * 9.0);   // 5 ms in, quick decay
                double v = Math.sin(2 * Math.PI * hz[n] * t) + 0.25 * Math.sin(4 * Math.PI * hz[n] * t);
                out[n * per + i] = (short) (v / 1.25 * env * VOLUME * Short.MAX_VALUE);
            }
        }
        return out;
    }

    private void play() {
        SourceDataLine line = null;
        try {
            AudioFormat fmt = new AudioFormat(RATE, 16, 1, true, false);
            line = AudioSystem.getSourceDataLine(fmt);
            line.open(fmt, (int) RATE / 4);
            line.start();
            while (!closed) {
                short[] pcm = queue.take();
                if (pcm.length == 0) continue;
                byte[] b = new byte[pcm.length * 2];
                for (int i = 0; i < pcm.length; i++) {
                    b[2 * i] = (byte) pcm[i];
                    b[2 * i + 1] = (byte) (pcm[i] >> 8);
                }
                line.write(b, 0, b.length);
            }
        } catch (Throwable t) {
            // no sound device, or it went away: cues are simply off
        } finally {
            if (line != null) {
                try { line.drain(); line.close(); } catch (Throwable ignored) { }
            }
        }
    }
}
