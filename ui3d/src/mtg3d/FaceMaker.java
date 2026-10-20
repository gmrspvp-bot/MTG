package mtg3d;

import com.badlogic.gdx.graphics.Pixmap;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws a card face for anything Forge has no picture for.
 *
 * Forge's cache only holds cards it has had reason to download, and basic lands are never in it -
 * which is most of the table in most games. A blank coloured block tells you nothing, so this
 * paints a readable stand-in instead: the name, the type line, mana cost, and power/toughness,
 * tinted by what the card is.
 *
 * Rendered with AWT because it has text and libGDX, without the FreeType extension wired up, does
 * not have an easy way to draw into a Pixmap. AWT is available here anyway (Forge's own UI is
 * Swing) and this runs on a worker thread, never on the render thread. The result is converted to
 * a Pixmap and handed to CardArt, which creates the Texture on the GL thread like any other.
 */
public final class FaceMaker {
    private FaceMaker() {}

    /** Card proportions at a size that stays sharp when a card is held up close. */
    private static final int W = 420, H = 588;

    public static Pixmap make(Snapshot.CardV c) {
        return toPixmap(draw(c));
    }

    /**
     * The drawing itself, kept free of libGDX so it can be rendered and looked at without a GL
     * context - see ui3d\src\mtg3d\FacePreview.java.
     */
    public static BufferedImage draw(Snapshot.CardV c) {
        if (c.faceDown) return drawBack(c);
        if (isEmblem(c)) return drawEmblem(c);
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        Color base = tint(c);
        // Card stock: a dark frame with a lighter panel inside, like a real border.
        g.setColor(new Color(18, 18, 22));
        g.fillRect(0, 0, W, H);
        g.setColor(base);
        g.fillRoundRect(14, 14, W - 28, H - 28, 26, 26);
        g.setColor(darker(base, 0.55f));
        g.fillRoundRect(30, 86, W - 60, H - 210, 14, 14);      // the "art" box
        g.setColor(new Color(0, 0, 0, 90));
        g.drawRoundRect(14, 14, W - 28, H - 28, 26, 26);

        Color ink = luminance(base) > 0.55f ? new Color(20, 18, 16) : new Color(240, 240, 236);

        // Mana cost first, so the name can be given the width that is actually left. Measuring
        // after drawing the name is what let long names run straight through the cost.
        int costW = 0;
        if (c.manaCost != null && !c.manaCost.isEmpty()) {
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 24));
            costW = g.getFontMetrics().stringWidth(c.manaCost);
            g.setColor(ink);
            g.drawString(c.manaCost, W - 34 - costW, 62);
        }
        g.setColor(ink);
        drawFitted(g, c.name, 34, 62, W - 68 - costW - (costW > 0 ? 16 : 0), 34f, Font.BOLD);

        // Type line, under the art box.
        g.setColor(ink);
        drawFitted(g, c.typeLine == null ? "" : c.typeLine, 34, H - 112, W - 68, 22f, Font.PLAIN);

        // Power / toughness in the corner, where a real card has it.
        if (c.creature && c.power != null && !c.power.isEmpty()) {
            String pt = c.power + " / " + c.toughness;
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 34));
            int pw = g.getFontMetrics().stringWidth(pt);
            g.setColor(new Color(0, 0, 0, 120));
            g.fillRoundRect(W - 52 - pw, H - 76, pw + 32, 48, 12, 12);
            g.setColor(ink);
            g.drawString(pt, W - 36 - pw, H - 42);
        }

        // Counters, stacked up the left edge where they do not fight the type line.
        if (!c.counterText.isEmpty()) {
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
            int cy = H - 150;
            for (int i = 0; i < Math.min(3, c.counterText.size()); i++) {
                String s = c.counterText.get(i);
                int sw2 = g.getFontMetrics().stringWidth(s);
                g.setColor(new Color(0, 0, 0, 140));
                g.fillRoundRect(30, cy - 20, sw2 + 18, 28, 10, 10);
                g.setColor(new Color(250, 236, 190));
                g.drawString(s, 39, cy);
                cy -= 34;
            }
        }

        // Tokens have no printing and never will, so say so - loudly enough to read from across
        // the table, where the old small corner tag disappeared: a silver frame round the whole
        // card and a full-width banner over the art box.
        if (c.token) {
            g.setColor(new Color(214, 220, 228));
            g.setStroke(new java.awt.BasicStroke(10f));
            g.drawRoundRect(9, 9, W - 18, H - 18, 30, 30);
            g.setStroke(new java.awt.BasicStroke(1f));
            g.setColor(new Color(20, 24, 30, 200));
            g.fillRect(30, 104, W - 60, 56);
            g.setColor(new Color(232, 238, 246));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 40));
            int tw = g.getFontMetrics().stringWidth("TOKEN");
            g.drawString("TOKEN", (W - tw) / 2, 147);
        }

        // A watermark of the kind, so a glance at the table still groups things.
        g.setColor(new Color(ink.getRed(), ink.getGreen(), ink.getBlue(), 38));
        String mark = kindMark(c);
        float mf = 92f;
        while (mf > 24f) {
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.round(mf)));
            if (g.getFontMetrics().stringWidth(mark) <= W - 90) break;   // keep it inside the art box
            mf -= 3f;
        }
        int mw = g.getFontMetrics().stringWidth(mark);
        g.drawString(mark, (W - mw) / 2, H / 2 + 40);

        g.dispose();
        return img;
    }

    /**
     * A card back, for anything face down. StateReader puts no name, type or text for a face-down
     * card into the snapshot at all. What it does carry, on the battlefield, is the face-down
     * creature's power and toughness - public (a morph is a 2/2 everyone can see) and the one
     * thing you need at a glance to decide whether to block it - so the back shows those large,
     * with FACE DOWN above them so it is never mistaken for an unloaded picture.
     */
    private static BufferedImage drawBack(Snapshot.CardV c) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(18, 18, 22));
        g.fillRect(0, 0, W, H);
        g.setColor(new Color(44, 36, 72));
        g.fillRoundRect(14, 14, W - 28, H - 28, 26, 26);
        g.setColor(new Color(74, 60, 116));
        g.fillOval(W / 2 - 110, H / 2 - 110, 220, 220);
        g.setColor(new Color(30, 24, 50));
        g.fillOval(W / 2 - 86, H / 2 - 86, 172, 172);
        g.setColor(new Color(196, 184, 236));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 34));
        int fw = g.getFontMetrics().stringWidth("FACE DOWN");
        g.drawString("FACE DOWN", (W - fw) / 2, 92);
        if (c != null && c.power != null && !c.power.isEmpty()) {
            String pt = c.power + "/" + c.toughness;
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 92));
            int pw = g.getFontMetrics().stringWidth(pt);
            g.setColor(new Color(236, 230, 255));
            g.drawString(pt, (W - pw) / 2, H / 2 + 32);
        }
        g.dispose();
        return img;
    }

    /** An emblem: Forge types it "Emblem", and it has no cost, no P/T - only its text. */
    private static boolean isEmblem(Snapshot.CardV c) {
        return c.typeLine != null && c.typeLine.toLowerCase().contains("emblem");
    }

    /**
     * An emblem is nothing BUT its text, so that is what it shows: a gold medallion with the
     * ability written out, instead of a generic "SPELL" face with no words on it. It lives in the
     * command zone and never moves, so it can afford to be the one face that is mostly text.
     */
    private static BufferedImage drawEmblem(Snapshot.CardV c) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(18, 16, 12));
        g.fillRect(0, 0, W, H);
        g.setColor(new Color(64, 48, 22));
        g.fillRoundRect(14, 14, W - 28, H - 28, 26, 26);
        g.setColor(new Color(212, 168, 82));
        g.setStroke(new java.awt.BasicStroke(8f));
        g.drawOval(W / 2 - 120, 40, 240, 240);
        g.setStroke(new java.awt.BasicStroke(1f));
        g.setColor(new Color(240, 206, 120));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 40));
        int ew = g.getFontMetrics().stringWidth("EMBLEM");
        g.drawString("EMBLEM", (W - ew) / 2, 174);
        // Whose emblem it is, if the name says (Forge names them "Emblem - <planeswalker>").
        String who = c.name == null ? "" : c.name.replaceFirst("(?i)^emblem\\s*[-:]?\\s*", "");
        g.setColor(new Color(236, 222, 190));
        if (!who.isEmpty() && !who.equalsIgnoreCase("emblem")) drawFitted(g, who, 40, 330, W - 80, 28f, Font.BOLD);
        // The ability, wrapped into the lower half.
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 24));
        java.awt.FontMetrics fm = g.getFontMetrics();
        int y = 380;
        for (String para : (c.text == null ? "" : c.text).split("\n")) {
            StringBuilder line = new StringBuilder();
            for (String word : para.split(" ")) {
                String probe = line.length() == 0 ? word : line + " " + word;
                if (fm.stringWidth(probe) > W - 80 && line.length() > 0) {
                    g.drawString(line.toString(), 40, y);
                    y += 30;
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(probe);
                }
            }
            if (line.length() > 0) { g.drawString(line.toString(), 40, y); y += 30; }
            if (y > H - 40) break;
        }
        g.dispose();
        return img;
    }

    /** Shrinks the font until the string fits the width, then draws it. */
    private static void drawFitted(Graphics2D g, String s, int x, int y, int maxW, float size, int style) {
        if (s == null || s.isEmpty()) return;
        float f = size;
        while (f > 10f) {
            g.setFont(new Font(Font.SANS_SERIF, style, Math.round(f)));
            if (g.getFontMetrics().stringWidth(s) <= maxW) break;
            f -= 1.5f;
        }
        g.drawString(s, x, y);
    }

    /** Basic lands get their own colour; everything else is tinted by what it is. */
    private static Color tint(Snapshot.CardV c) {
        String n = c.name == null ? "" : c.name;
        if (n.equals("Plains")) return new Color(240, 232, 200);
        if (n.equals("Island")) return new Color(150, 198, 232);
        if (n.equals("Swamp")) return new Color(120, 112, 124);
        if (n.equals("Mountain")) return new Color(226, 150, 118);
        if (n.equals("Forest")) return new Color(150, 196, 148);
        if (n.equals("Wastes")) return new Color(196, 196, 200);
        if (c.land) return new Color(176, 186, 170);
        if (c.planeswalker) return new Color(196, 158, 206);
        if (c.creature) return new Color(206, 184, 142);
        if (c.enchantment) return new Color(176, 192, 206);
        if (c.artifact) return new Color(186, 192, 198);
        return new Color(200, 200, 206);
    }

    private static String kindMark(Snapshot.CardV c) {
        if (c.land) return "LAND";
        if (c.planeswalker) return "PW";
        if (c.creature) return "CREATURE";
        if (c.enchantment) return "ENCH";
        if (c.artifact) return "ARTIFACT";
        return "SPELL";
    }

    private static Color darker(Color c, float f) {
        return new Color(Math.round(c.getRed() * f), Math.round(c.getGreen() * f), Math.round(c.getBlue() * f));
    }

    private static float luminance(Color c) {
        return (0.299f * c.getRed() + 0.587f * c.getGreen() + 0.114f * c.getBlue()) / 255f;
    }

    /** AWT ARGB to libGDX RGBA8888, a pixel at a time - small images, once per distinct card. */
    private static Pixmap toPixmap(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        Pixmap pm = new Pixmap(w, h, Pixmap.Format.RGBA8888);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            img.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                int argb = row[x];
                int a = (argb >>> 24) & 0xff, r = (argb >> 16) & 0xff, gg = (argb >> 8) & 0xff, b = argb & 0xff;
                pm.drawPixel(x, y, (r << 24) | (gg << 16) | (b << 8) | a);
            }
        }
        return pm;
    }

    /** Distinct faces are cached by this, so two Plains share one texture. */
    public static String keyFor(Snapshot.CardV c) {
        List<String> parts = new ArrayList<>();
        parts.add(c.name == null ? "" : c.name);
        parts.add(c.typeLine == null ? "" : c.typeLine);
        parts.add(c.manaCost == null ? "" : c.manaCost);
        if (c.creature) parts.add(c.power + "/" + c.toughness);
        if (c.token) parts.add("token");
        parts.addAll(c.counterText);          // a card that gains a counter needs a new face
        // Face-down backs share a texture per P/T (most morphs are 2/2, so it is still mostly one).
        if (c.faceDown) return "gen:facedown:" + (c.power == null ? "" : c.power + "/" + c.toughness);
        if (isEmblem(c)) parts.add(c.text == null ? "" : c.text);
        return "gen:" + String.join("|", parts).toLowerCase();
    }
}
