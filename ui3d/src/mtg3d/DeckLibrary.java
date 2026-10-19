package mtg3d;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The decks on disk, read well enough for a lobby to list them.
 *
 * Groundwork for stage 4. Deliberately a plain text parse of the `.dck` files rather than
 * `forge.deck.io.DeckSerializer.fromFile`, for one reason: Forge's reader resolves every line to
 * a real card, so it needs Forge's card database loaded. That is fine inside a running game and
 * useless everywhere else - a lobby that has to boot the whole card database before it can show a
 * list of deck names is the wrong shape, and `Demo` could not list decks at all.
 *
 * So: this reads names and counts with no dependencies, and {@link #load} hands the file to
 * Forge's own reader when an actual game is being started and the database is up. Listing is
 * cheap and always works; resolving happens once, late, and only when it has to.
 */
public final class DeckLibrary {
    private DeckLibrary() {}

    /** One deck, as much as can be known without Forge's card database. */
    public static final class DeckInfo {
        public final File file;
        public String name = "";
        public final List<String> commanders = new ArrayList<>();
        /** Cards in [main], counting duplicates - 99 for a legal Commander deck. */
        public int mainCount;
        /** Everything including the commander, so a legal Commander deck is 100. */
        public int totalCount;

        DeckInfo(File file) { this.file = file; }

        /** Commander needs exactly one commander (or two partners) and a hundred cards. */
        public boolean looksLikeCommander() {
            return !commanders.isEmpty() && commanders.size() <= 2 && totalCount == 100;
        }

        public String describe() {
            String who = commanders.isEmpty() ? "no commander" : String.join(" + ", commanders);
            return name + "  (" + who + ", " + totalCount + " cards)"
                + (looksLikeCommander() ? "" : "  [not a legal Commander deck]");
        }

        @Override public String toString() { return describe(); }
    }

    /** Where the repo keeps its decks, relative to wherever we were started from. */
    public static File defaultDir() {
        String dir = System.getProperty("forge.ui3d.decks");
        if (dir != null) return new File(dir);
        // play.ps1 starts Forge with the install folder as the working directory.
        File rel = new File("../decks");
        return rel.isDirectory() ? rel : new File("decks");
    }

    /** Every readable .dck in the folder, by name. Unreadable files are skipped, not fatal. */
    public static List<DeckInfo> scan(File dir) {
        List<DeckInfo> out = new ArrayList<>();
        if (dir == null || !dir.isDirectory()) return out;
        File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".dck"));
        if (files == null) return out;
        for (File f : files) {
            DeckInfo info = read(f);
            if (info != null) out.add(info);
        }
        out.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    /**
     * Reads one .dck. The format is sections in square brackets, then "<count> <card name>"
     * lines; a name may carry a set after a pipe, which is dropped here since the lobby only
     * needs to show the card.
     */
    public static DeckInfo read(File f) {
        DeckInfo info = new DeckInfo(f);
        String section = "";
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = stripBom(line).trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("[") && line.endsWith("]")) {
                    section = line.substring(1, line.length() - 1).toLowerCase();
                    continue;
                }
                if (section.equals("metadata")) {
                    if (line.toLowerCase().startsWith("name=")) info.name = line.substring(5).trim();
                    continue;
                }
                if (!section.equals("commander") && !section.equals("main")) continue;   // sideboard etc.

                int count = 1;
                String name = line;
                int sp = line.indexOf(' ');
                if (sp > 0) {
                    try {
                        count = Integer.parseInt(line.substring(0, sp));
                        name = line.substring(sp + 1).trim();
                    } catch (NumberFormatException e) {
                        count = 1;                      // a line with no leading count is one card
                    }
                }
                int pipe = name.indexOf('|');
                if (pipe > 0) name = name.substring(0, pipe).trim();
                if (name.isEmpty()) continue;

                info.totalCount += count;
                if (section.equals("commander")) {
                    for (int i = 0; i < count; i++) info.commanders.add(name);
                } else {
                    info.mainCount += count;
                }
            }
        } catch (Throwable t) {
            return null;
        }
        if (info.name.isEmpty()) {
            String n = f.getName();
            info.name = n.toLowerCase().endsWith(".dck") ? n.substring(0, n.length() - 4) : n;
        }
        return info;
    }

    /** Windows tools sometimes leave a BOM on the first line; it would corrupt the first key. */
    private static String stripBom(String s) {
        return (!s.isEmpty() && s.charAt(0) == '﻿') ? s.substring(1) : s;
    }

    /**
     * Resolves a deck through Forge's own reader, for actually starting a game. Returns null when
     * Forge's card database is not loaded - which is the normal case outside a running game, and
     * why listing does not go through here. Reflection keeps this file free of a hard dependency
     * on Forge, so Demo and the tests still run without it.
     */
    public static Object load(DeckInfo info) {
        if (info == null) return null;
        try {
            Class<?> ser = Class.forName("forge.deck.io.DeckSerializer");
            return ser.getMethod("fromFile", File.class).invoke(null, info.file);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Lists what is on disk, so the parse can be checked against the real decks folder. */
    public static void main(String[] args) {
        File dir = args.length > 0 ? new File(args[0]) : defaultDir();
        List<DeckInfo> decks = scan(dir);
        System.out.println(decks.size() + " deck(s) in " + dir.getAbsolutePath());
        int legal = 0;
        for (DeckInfo d : decks) {
            System.out.println("  " + d.describe());
            if (d.looksLikeCommander()) legal++;
        }
        System.out.println(legal + " of " + decks.size() + " look like legal Commander decks");
    }
}
