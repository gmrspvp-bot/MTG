package mtg3d;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Card images, taken from the cache Forge already fills.
 *
 * Forge stores them as <code>forge-data\cache\pics\cards\&lt;SET&gt;\&lt;Card Name&gt;.fullborder.jpg</code>
 * - whole-card pictures, whose 2.5:3.5 proportions are exactly the card size the table uses. Only
 * the cards Forge has had reason to download are there (a few hundred, not the whole of Magic),
 * so a miss is completely normal and simply leaves the card as a coloured block.
 *
 * Two things make this safe to call from the renderer every frame:
 *
 *   - the name index is built once on a background thread, so the first frame is never blocked;
 *   - decoding happens on a worker thread, but the Texture itself is created on the GL thread in
 *     {@link #update}, a couple per frame, because OpenGL objects may only be made there. That
 *     budget is what stops a board full of new cards from stalling the view for a frame.
 */
public final class CardArt {

    private static final int TEXTURES_PER_FRAME = 2;
    /** Enough for several full boards. Past this, the least recently drawn are let go. */
    private static final int MAX_TEXTURES = 400;
    /**
     * A texture is only evicted once it has not been asked for in this many frames. The renderer
     * rebuilds its card list every frame and fetches every visible texture, so "not asked for
     * recently" means "not on screen"; the grace period covers a card that is briefly hidden
     * behind the search panel or off the edge of an overhead sweep.
     */
    private static final long EVICT_AFTER_FRAMES = 600;      // about ten seconds at 60fps

    private final File root;
    private final Map<String, File> index = new ConcurrentHashMap<>();
    private final Map<String, Texture> ready = new HashMap<>();
    /** Frame number each texture was last asked for, which is what drives eviction. */
    private final Map<String, Long> lastUsed = new HashMap<>();
    private long frame;
    private final Map<String, Boolean> requested = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Loaded> decoded = new ConcurrentLinkedQueue<>();
    private volatile boolean indexed = false;

    private static final class Loaded {
        final String key;
        final Pixmap pixmap;
        Loaded(String key, Pixmap pixmap) { this.key = key; this.pixmap = pixmap; }
    }

    public CardArt(File cacheRoot) {
        this.root = cacheRoot;
        Thread t = new Thread(this::buildIndex, "mtg3d-art-index");
        t.setDaemon(true);
        t.start();
    }

    /** Where Forge keeps the pictures, relative to the repo root. */
    public static File defaultRoot() {
        String dir = System.getProperty("forge.ui3d.pics");
        if (dir != null) return new File(dir);
        // play.ps1 runs Forge with the install folder as the working directory.
        File rel = new File("../forge-data/cache/pics/cards");
        if (rel.isDirectory()) return rel;
        return new File("forge-data/cache/pics/cards");
    }

    private void buildIndex() {
        try {
            scan(root, 0);
        } catch (Throwable t) {
            // no art is a perfectly good outcome
        }
        indexed = true;
    }

    private void scan(File dir, int depth) {
        if (dir == null || depth > 4 || !dir.isDirectory()) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f.isDirectory()) {
                scan(f, depth + 1);
            } else {
                String n = f.getName();
                int dot = n.indexOf('.');
                if (dot <= 0) continue;
                if (!n.endsWith(".jpg") && !n.endsWith(".png")) continue;
                // "Sol Ring.fullborder.jpg" -> "sol ring". First one found wins; any printing will do.
                index.putIfAbsent(key(n.substring(0, dot)), f);
            }
        }
    }

    private static String key(String name) {
        return name.toLowerCase().trim();
    }

    /**
     * The texture for a card, or null if there is none yet. Asking queues a load, so calling this
     * every frame is how art arrives; it never blocks.
     */
    public Texture get(Snapshot.CardV card) {
        if (card == null || card.name == null || card.name.isEmpty() || !indexed) return null;
        String k = key(card.name);
        Texture t = ready.get(k);
        if (t != null) { lastUsed.put(k, frame); return t; }

        File f = index.get(k);
        if (f == null) {
            // No picture cached. Rather than leave a blank block - which is every basic land, and
            // most of the table - draw a readable face from what we know about the card. Keyed on
            // the card's own details, so two Plains share one texture but a 5/5 Angel and a 4/4
            // one do not.
            String gk = FaceMaker.keyFor(card);
            Texture gen = ready.get(gk);
            if (gen != null) { lastUsed.put(gk, frame); return gen; }
            queue(gk, () -> FaceMaker.make(card));
            return null;
        }
        queue(k, () -> new Pixmap(new com.badlogic.gdx.files.FileHandle(f)));
        return null;
    }

    /** Decodes or draws on a worker thread, once per key; the Texture is made later, on the GL thread. */
    private void queue(final String k, final java.util.function.Supplier<Pixmap> source) {
        if (requested.containsKey(k)) return;
        if (ready.size() >= MAX_TEXTURES) return;
        requested.put(k, Boolean.TRUE);
        Thread worker = new Thread(() -> {
            try {
                decoded.add(new Loaded(k, source.get()));
            } catch (Throwable e) {
                // leave it marked as requested so we do not try again every frame
            }
        }, "mtg3d-art");
        worker.setDaemon(true);
        worker.start();
    }

    /** Call once a frame on the render thread: turns decoded images into textures, a few at a time. */
    public void update() {
        frame++;
        evict();
        for (int i = 0; i < TEXTURES_PER_FRAME; i++) {
            Loaded l = decoded.poll();
            if (l == null) return;
            // The cap has to be enforced HERE as well as at queue time. A burst of requests is
            // queued while `ready` is still small - a fresh board asks for everything at once -
            // so checking only on the way in let the count sail past the cap as they completed.
            if (ready.size() >= MAX_TEXTURES) evict();
            if (ready.size() >= MAX_TEXTURES) {
                l.pixmap.dispose();
                requested.remove(l.key);      // so it can be asked for again when there is room
                continue;
            }
            try {
                Texture tex = new Texture(l.pixmap, true);
                tex.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
                // Cards lie almost flat, so they are seen at a very grazing angle. That is exactly
                // the case plain mipmapping handles badly: it picks one level for the whole quad
                // and the card ends up striped. Anisotropic filtering samples along the direction
                // of compression instead, which is what actually removes the shimmer.
                try {
                    float max = Texture.getMaxAnisotropicFilterLevel();
                    if (max > 1f) tex.setAnisotropicFilter(Math.min(16f, max));
                } catch (Throwable ignored) {
                    // unsupported: the card is just softer at a distance
                }
                ready.put(l.key, tex);
                lastUsed.put(l.key, frame);
            } catch (Throwable e) {
                // skip this one
            } finally {
                l.pixmap.dispose();
            }
        }
    }

    /**
     * Drops the textures nobody has looked at for a while, oldest first, once we are near the cap.
     * Runs on the render thread because disposing a Texture is a GL call. A dropped card simply
     * reloads the next time it is drawn - the file or the generated face is still available - so
     * the cost of being wrong here is a brief blank card, not a broken one.
     */
    private void evict() {
        if (ready.size() < MAX_TEXTURES * 9 / 10) return;
        List<Map.Entry<String, Long>> old = new ArrayList<>();
        for (Map.Entry<String, Long> e : lastUsed.entrySet()) {
            if (frame - e.getValue() > EVICT_AFTER_FRAMES) old.add(e);
        }
        if (old.isEmpty()) return;
        old.sort(Comparator.comparingLong(Map.Entry::getValue));
        int target = Math.max(1, ready.size() - MAX_TEXTURES * 7 / 10);
        for (int i = 0; i < Math.min(target, old.size()); i++) {
            String k = old.get(i).getKey();
            Texture tex = ready.remove(k);
            lastUsed.remove(k);
            requested.remove(k);          // let it load again if it comes back on screen
            if (tex != null) {
                try { tex.dispose(); } catch (Throwable ignored) { }
            }
        }
    }

    public int loaded() { return ready.size(); }

    public int known() { return index.size(); }

    public void dispose() {
        for (Texture t : ready.values()) {
            try { t.dispose(); } catch (Throwable ignored) { }
        }
        ready.clear();
        lastUsed.clear();
        for (Loaded l : decoded) {
            try { l.pixmap.dispose(); } catch (Throwable ignored) { }
        }
        decoded.clear();
    }
}
