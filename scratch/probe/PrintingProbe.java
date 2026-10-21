import forge.GuiDesktop;
import forge.gui.GuiBase;
import forge.item.PaperCard;
import forge.model.FModel;

/**
 * Which printing Forge picks for a card named in a deck without a set (2026-10-22: Clown Car had
 * no picture even in Forge's own window - Forge picks its Zeta Set printing, SLZ #100).
 *
 * For each card name given, prints one line Forge's picture cache can be filled from:
 *   PRINT|<name>|<set>|<scryfall set code>|<collector number>|<file name Forge looks for>
 * The file name is "<Name><art>.fullborder.jpg" when the set has several arts of the card,
 * "<Name>.fullborder.jpg" when it has one. Or "MISSING|<name>" when Forge does not know it.
 * Run from forge-installer-2.0.15 with the Forge jar.
 */
public class PrintingProbe {
    public static void main(String[] a) throws Exception {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        for (String name : a) {
            PaperCard pc = FModel.getMagicDb().getCommonCards().getCard(name);
            if (pc == null) { System.out.println("MISSING|" + name); continue; }
            int arts = 0;
            for (PaperCard p : FModel.getMagicDb().getCommonCards().getAllCards(name)) {
                if (p.getEdition().equals(pc.getEdition())) arts++;
            }
            String scry = pc.getEdition();
            try {
                String s = FModel.getMagicDb().getEditions().get(pc.getEdition()).getScryfallCode();
                if (s != null && !s.isEmpty()) scry = s;
            } catch (Throwable ignored) {
                // fall back to Forge's own code
            }
            String file = pc.getName().replace("//", "").replace("  ", " ")
                + (arts > 1 ? String.valueOf(pc.getArtIndex()) : "") + ".fullborder.jpg";
            System.out.println("PRINT|" + name + "|" + pc.getEdition() + "|" + scry.toLowerCase() + "|"
                + pc.getCollectorNumber() + "|" + file);
        }
        System.exit(0);
    }
}
