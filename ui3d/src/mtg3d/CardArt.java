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
    /** The same pictures under a punctuation-free key, tried only when the exact one misses. */
    private final Map<String, File> loosely = new ConcurrentHashMap<>();
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
                String base = baseName(f.getName());
                if (base == null) continue;
                put(base, f);
                // Cards with several arts are saved with a number: "Plains1.fullborder.jpg". The
                // card is called "Plains", so without this no basic land ever found its picture.
                String plain = base.replaceFirst("\\d+$", "");
                if (!plain.isEmpty() && !plain.equals(base)) put(plain, f);
                // A split or double-faced card is filed under the whole name ("Wear // Tear"),
                // but Forge often hands us only the front face. Index both halves.
                int slash = base.indexOf("//");
                if (slash > 0) {
                    put(base.substring(0, slash), f);
                    put(base.substring(slash + 2), f);
                }
            }
        }
    }

    /**
     * Forge downloads pictures while the game runs (the console's "Saved image to ..."), but the
     * index was built once, when the window opened - so a card whose picture arrived later stayed
     * a drawn stand-in for the whole game, and on a fresh install that was nearly every card. A
     * card with no picture now asks for a rescan; one runs in the background at most every
     * RESCAN_MS, and only while cards are missing. The next get() after it finds the file and
     * swaps the real picture in.
     */
    private void rescanSoon() {
        long now = System.currentTimeMillis();
        if (rescanning || now - lastRescan < RESCAN_MS) return;
        rescanning = true;
        lastRescan = now;
        Thread t = new Thread(() -> {
            try {
                scan(root, 0);
            } catch (Throwable ignored) {
                // try again next time
            } finally {
                rescanning = false;
            }
        }, "mtg3d-art-rescan");
        t.setDaemon(true);
        t.start();
    }

    private volatile boolean rescanning;
    private volatile long lastRescan = System.currentTimeMillis();
    static final long RESCAN_MS = 4000;

    /**
     * The card name out of a picture file name, or null if it is not a picture.
     *
     * It used to cut at the FIRST dot, which is right for "Sol Ring.fullborder.jpg" and wrong for
     * every card with a dot in its name: "Mr. House, President and CEO.fullborder.jpg" was filed
     * under "Mr", so Mr. House never found his picture and nor did any other Mr., Dr. or St.
     * (the owner's board, 2026-10-21). Take the extension off, then the one marker suffix Forge
     * adds, and keep the rest of the name whole.
     */
    private static String baseName(String fileName) {
        String n = fileName;
        String lower = n.toLowerCase();
        if (!lower.endsWith(".jpg") && !lower.endsWith(".jpeg") && !lower.endsWith(".png")) return null;
        n = n.substring(0, n.lastIndexOf('.'));
        for (String suffix : MARKERS) {
            if (n.toLowerCase().endsWith(suffix)) { n = n.substring(0, n.length() - suffix.length()); break; }
        }
        n = n.trim();
        return n.isEmpty() ? null : n;
    }

    /** What Forge puts between the card name and the extension. */
    private static final String[] MARKERS = { ".fullborder", ".full", ".border", ".png", ".jpg" };

    /** File a picture under both its plain key and its loose one. First one found wins. */
    private void put(String name, File f) {
        String k = key(name);
        if (k.isEmpty()) return;
        index.putIfAbsent(k, f);
        String l = loose(k);
        if (!l.isEmpty()) loosely.putIfAbsent(l, f);
    }

    private static String key(String name) {
        return name.toLowerCase().trim();
    }

    /**
     * The name with everything but letters and digits taken out, for matching across the small
     * differences between what Forge calls a card and what a file may be called: the comma in
     * "Mr. House, President and CEO", the colon in "Ratonhnhake:ton", a stray apostrophe, the
     * accent on "Aether". Only ever used after the exact key has missed, so it can never pull a
     * picture away from a card that had its own.
     */
    private static String loose(String name) {
        StringBuilder b = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = Character.toLowerCase(name.charAt(i));
            if (c >= 'a' && c <= 'z') b.append(c);
            else if (c >= '0' && c <= '9') b.append(c);
            else if (c == 0x00e6) b.append("ae");        // the ligature in AEther / AEtherize
        }
        return b.toString();
    }

    /**
     * The picture for a card name, trying the exact name, then the front face of a split or
     * double-faced card, then the loose match. Null when we have nothing.
     */
    File fileFor(String name) {
        if (name == null || name.isEmpty()) return null;
        File f = index.get(key(name));
        if (f != null) return f;
        int slash = name.indexOf("//");
        if (slash > 0) {
            f = index.get(key(name.substring(0, slash)));
            if (f != null) return f;
        }
        return loosely.get(loose(name));
    }

    /**
     * The texture for a card, or null if there is none yet. Asking queues a load, so calling this
     * every frame is how art arrives; it never blocks.
     */
    public Texture get(Snapshot.CardV card) {
        if (card == null || !indexed) return null;
        // A face-down card has no name by design, so it never looks in the cache: there is nothing
        // to look up, and nothing of it that may be shown. It goes straight to the generated back,
        // which every face-down card shares. Without this, the empty-name guard below would leave
        // morphs with no texture at all.
        if (card.faceDown) {
            String bk = FaceMaker.keyFor(card);
            Texture back = ready.get(bk);
            if (back != null) { lastUsed.put(bk, frame); return back; }
            queue(bk, () -> FaceMaker.make(card));
            return null;
        }
        if (card.name == null || card.name.isEmpty()) return null;
        // Tokens only: real cards are found by name (which also sees pictures card-images.ps1
        // downloaded), and asking Forge about a card's key it cannot find adds it to Forge's list of
        // missing pictures for nothing.
        File keyed = card.token ? fileForKey(card.imageKey) : null;
        // A token's picture is kept under its own key, so a 3/3 Robot and a Clown Robot, or two
        // Robots of different sets, never share a texture by name.
        String k = keyed != null && card.token ? "key:" + card.imageKey : key(card.name);
        Texture t = ready.get(k);
        if (t != null) { lastUsed.put(k, frame); return t; }

        File f = keyed != null ? keyed : fileFor(card.name);
        if (f == null) rescanSoon();
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

    /**
     * True when this card has a real cached picture, as opposed to a face FaceMaker draws. The
     * hover panel shows real art large but keeps its text panel for drawn faces, whose small
     * print is less readable blown up than plain text.
     */
    public boolean hasPicture(Snapshot.CardV card) {
        if (card == null || card.faceDown || !indexed) return false;
        if (card.name == null || card.name.isEmpty()) return false;
        return (card.token && fileForKey(card.imageKey) != null) || fileFor(card.name) != null;
    }

    /**
     * The cached picture for Forge's own image key, found the way Forge's window finds it
     * (ImageKeys.getImageFile). Tokens need this: Forge files them by code -
     * tokens\PIP\16_c_3_3_a_robot.jpg - so a name lookup for "Robot" never found one and every
     * token was a drawn stand-in (owner, 2026-10-22: a Mr House board is mostly Robots and
     * Treasures). A miss is remembered for RESCAN_MS, so Forge is not asked every frame, and
     * tried again after that in case Forge has downloaded it since. Without Forge (the demo)
     * this always misses and the name lookup carries on as before.
     */
    public File fileForKey(String imageKey) {
        if (imageKey == null || imageKey.isEmpty()) return null;
        File f = byKey.get(imageKey);
        if (f != null) return f;
        long now = System.currentTimeMillis();
        Long miss = keyMissAt.get(imageKey);
        if (miss != null && now - miss < RESCAN_MS) return null;
        try {
            f = forge.ImageKeys.getImageFile(imageKey);
        } catch (Throwable t) {
            f = null;          // Forge not set up (demo), or a key it cannot read
        }
        if (f != null && f.isFile()) { byKey.put(imageKey, f); return f; }
        keyMissAt.put(imageKey, now);
        return null;
    }

    private final Map<String, File> byKey = new ConcurrentHashMap<>();
    private final Map<String, Long> keyMissAt = new ConcurrentHashMap<>();

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
                    if (max > 1f) tex.setAnisotropicFilter(Math.min(GraphicsQuality.anisotropy(), max));   // ESC menu: graphics
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
