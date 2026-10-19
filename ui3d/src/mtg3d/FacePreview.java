package mtg3d;

import java.io.File;

/**
 * Renders a sheet of generated card faces to a PNG so they can be looked at without starting the
 * 3D view. FaceMaker.draw() is deliberately free of libGDX so this needs no GL context.
 *
 *   java -cp ui3d\classes mtg3d.FacePreview <out.png>
 */
public final class FacePreview {
    public static void main(String[] args) throws Exception {
        Snapshot.CardV[] cards0 = {
            card("Plains", "Land - Plains", "", false, true, null, null),
            card("Island", "Land - Island", "", false, true, null, null),
            card("Mountain", "Land - Mountain", "", false, true, null, null),
            card("Lyra Dawnbringer", "Legendary Creature - Angel", "{3}{W}{W}", true, false, "5", "5"),
            card("Sol Ring", "Artifact", "{1}", false, false, null, null),
            card("Wrath of the Skies", "Sorcery", "{X}{W}{W}", false, false, null, null),
        };
        Snapshot.CardV[] cards = cards0;
        cards[4].artifact = true;
        // The three new cases: counters, a token, and a face-down card.
        Snapshot.CardV counted = card("Walking Ballista", "Artifact Creature - Construct",
            "{X}{X}", true, false, "3", "3");
        counted.artifact = true;
        counted.counterText.add("+1/+1 x3");
        counted.counterText.add("shield x1");
        Snapshot.CardV token = card("Angel", "Token Creature - Angel", "", true, false, "4", "4");
        token.token = true;
        Snapshot.CardV down = new Snapshot.CardV(99, "", "Creature", true, false);
        down.faceDown = true;
        Snapshot.CardV[] all = new Snapshot.CardV[cards.length + 3];
        System.arraycopy(cards, 0, all, 0, cards.length);
        all[cards.length] = counted;
        all[cards.length + 1] = token;
        all[cards.length + 2] = down;
        cards = all;
        int w = 420, h = 588, cols = cards.length;
        java.awt.image.BufferedImage sheet =
            new java.awt.image.BufferedImage(w * cols, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = sheet.createGraphics();
        for (int i = 0; i < cards.length; i++) g.drawImage(FaceMaker.draw(cards[i]), i * w, 0, null);
        g.dispose();
        File out = new File(args.length > 0 ? args[0] : "face-preview.png");
        javax.imageio.ImageIO.write(sheet, "png", out);
        System.out.println("wrote " + out.getAbsolutePath());
    }

    private static Snapshot.CardV card(String name, String type, String cost,
                                       boolean creature, boolean land, String p, String tgh) {
        Snapshot.CardV c = new Snapshot.CardV(1, name, type, creature, land);
        c.manaCost = cost;
        if (p != null) { c.power = p; c.toughness = tgh; }
        return c;
    }
}
