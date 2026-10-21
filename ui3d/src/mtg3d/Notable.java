package mtg3d;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Which permanents on the table are worth noticing, and how much.
 *
 * Three different things make a card worth a second look, and they are deliberately kept
 * separate rather than added into one score:
 *
 *   known      a card the format thinks highly of - EDHREC's rank, fetched once by
 *              scripts\card-ranks.ps1 and read from ui3d\data\card-ranks.txt. Rank 1 is the
 *              most played card in Commander; anything inside the top few hundred is a card
 *              most players would recognise on sight
 *   big        sheer size. A 7/7 is a problem whoever printed it
 *   loaded     a pile of keywords. Flying and trample and lifelink on one body is a different
 *              card from a vanilla one with the same numbers
 *
 * A card that is any of them glows; a card that is two or more glows harder. The point is to
 * answer "what on this table can kill me" across a four-player board without reading forty card
 * texts (the owner, 2026-10-22).
 */
final class Notable {

    private Notable() {}

    /** name (lower case) -> EDHREC rank. Empty when the file is missing, which is harmless. */
    private static final Map<String, Integer> RANK = new HashMap<>();
    private static boolean loaded;

    /** The rank a card has to beat to count as well regarded. */
    private static final int GOOD_RANK = 600;
    /** And the rank below which it is one of the cards everybody knows. */
    private static final int GREAT_RANK = 120;

    /** Power + toughness at which a creature is big on its own account. */
    private static final int BIG = 11;
    private static final int HUGE = 15;

    /** How many keywords count as "loaded". */
    private static final int LOADED = 3;

    /**
     * The keywords worth counting. Deliberately the ones that change how a creature attacks,
     * blocks or dies, rather than every word Magic has ever printed - "heaps of abilities like
     * toughness, flying, trample" is the owner's description and this is that list.
     */
    private static final String[] KEYWORDS = {
        "flying", "trample", "deathtouch", "lifelink", "first strike", "double strike",
        "vigilance", "menace", "hexproof", "indestructible", "haste", "reach", "protection from",
        "ward", "shroud", "flash", "annihilator", "infect", "toxic", "horsemanship",
    };

    /** Read the rank file once, from wherever the classes were loaded from. */
    private static synchronized void load() {
        if (loaded) return;
        loaded = true;
        File f = find();
        if (f == null) {
            System.out.println("UI3D: no card-ranks.txt - cards will be judged on size and keywords only");
            return;
        }
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                int eq = line.lastIndexOf('=');
                if (eq <= 0) continue;
                try {
                    RANK.put(line.substring(0, eq).trim().toLowerCase(), Integer.parseInt(line.substring(eq + 1).trim()));
                } catch (NumberFormatException ignored) {
                    // a line we cannot read is a line we skip
                }
            }
            System.out.println("UI3D: " + RANK.size() + " ranked cards loaded");
        } catch (Throwable t) {
            System.out.println("UI3D: could not read the card ranks (" + t + ")");
        }
    }

    /** The rank file, looked for beside the classes and then in the usual places from a run. */
    private static File find() {
        String given = System.getProperty("forge.ui3d.ranks");
        if (given != null) {
            File f = new File(given);
            return f.isFile() ? f : null;
        }
        for (String p : new String[] {
                "ui3d/data/card-ranks.txt", "../ui3d/data/card-ranks.txt",
                "../../ui3d/data/card-ranks.txt", "data/card-ranks.txt" }) {
            File f = new File(p);
            if (f.isFile()) return f;
        }
        return null;
    }

    /** EDHREC's rank for a card, or 0 when we have none. */
    static int rank(String name) {
        load();
        if (name == null || name.isEmpty()) return 0;
        Integer r = RANK.get(name.toLowerCase());
        return r == null ? 0 : r;
    }

    /** How many of the keywords this file counts appear in a card's text. */
    static int keywords(Snapshot.CardV c) {
        if (c == null || c.text == null || c.text.isEmpty()) return 0;
        String t = c.text.toLowerCase();
        int n = 0;
        for (String k : KEYWORDS) if (t.contains(k)) n++;
        return n;
    }

    /**
     * 0 for an ordinary permanent, 1 for one worth noticing, 2 for one that should stop you.
     *
     * Only creatures and only on the battlefield: a famous land is still a land, and a card in
     * a graveyard is not about to attack anybody. Face-down cards are never marked, which also
     * means nothing can be read off the glow about a card nobody is allowed to see.
     */
    static int threat(Snapshot.CardV c) {
        if (c == null || c.faceDown) return 0;
        int marks = 0, hard = 0;

        int r = rank(c.name);
        if (r > 0 && r <= GREAT_RANK) { marks++; hard++; }
        else if (r > 0 && r <= GOOD_RANK) marks++;

        if (c.creature) {
            int size = num(c.power) + num(c.toughness);
            if (size >= HUGE) { marks++; hard++; }
            else if (size >= BIG) marks++;

            int kw = keywords(c);
            if (kw >= LOADED + 2) { marks++; hard++; }
            else if (kw >= LOADED) marks++;
        }

        if (hard >= 1 && marks >= 2) return 2;
        return marks >= 1 ? 1 : 0;
    }

    /** A printed number like "7", or 0 for "*" and the blanks. */
    private static int num(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
