package mtg3d;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;

import java.io.File;
import java.util.HashMap;
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
    /** Enough for several full boards; past this we stop loading rather than exhaust video memory. */
    private static final int MAX_TEXTURES = 400;

    private final File root;
    private final Map<String, File> index = new ConcurrentHashMap<>();
    private final Map<String, Texture> ready = new HashMap<>();
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
    public Texture get(String cardName) {
        if (cardName == null || cardName.isEmpty() || !indexed) return null;
        String k = key(cardName);
        Texture t = ready.get(k);
        if (t != null) return t;
        if (requested.containsKey(k)) return null;
        File f = index.get(k);
        if (f == null) { requested.put(k, Boolean.TRUE); return null; }   // no art for this card
        if (ready.size() >= MAX_TEXTURES) return null;
        requested.put(k, Boolean.TRUE);
        Thread worker = new Thread(() -> {
            try {
                decoded.add(new Loaded(k, new Pixmap(new com.badlogic.gdx.files.FileHandle(f))));
            } catch (Throwable e) {
                // leave it marked as requested so we do not try again every frame
            }
        }, "mtg3d-art");
        worker.setDaemon(true);
        worker.start();
        return null;
    }

    /** Call once a frame on the render thread: turns decoded images into textures, a few at a time. */
    public void update() {
        for (int i = 0; i < TEXTURES_PER_FRAME; i++) {
            Loaded l = decoded.poll();
            if (l == null) return;
            try {
                Texture tex = new Texture(l.pixmap, true);
                tex.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
                ready.put(l.key, tex);
            } catch (Throwable e) {
                // skip this one
            } finally {
                l.pixmap.dispose();
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
        for (Loaded l : decoded) {
            try { l.pixmap.dispose(); } catch (Throwable ignored) { }
        }
        decoded.clear();
    }
}
