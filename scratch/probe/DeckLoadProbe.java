import java.io.File;
import forge.GuiDesktop;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.deck.io.DeckSerializer;
import forge.gui.GuiBase;
import forge.item.PaperCard;
import forge.model.FModel;

/**
 * Loads a .dck the way Forge does and prints its commander, section sizes and a few printings -
 * a check after editing a deck file by hand (2026-10-22: the owner's chosen prints merged into
 * Mr House v4). Args: deck file path, then card names whose printing to show.
 * Run from forge-installer-2.0.15 with the Forge jar.
 */
public class DeckLoadProbe {
    public static void main(String[] a) throws Exception {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        Deck d = DeckSerializer.fromFile(new File(a[0]));
        if (d == null) { System.out.println("FAIL: Forge could not read the deck"); System.exit(1); }
        System.out.println("name: " + d.getName());
        for (DeckSection s : DeckSection.values()) {
            if (d.has(s)) System.out.println("section " + s + ": " + d.get(s).countAll());
        }
        boolean ok = d.has(DeckSection.Commander) && d.get(DeckSection.Commander).countAll() == 1;
        if (ok) for (PaperCard c : d.get(DeckSection.Commander).toFlatList()) System.out.println("commander: " + c.getName() + " " + c.getEdition());
        for (int i = 1; i < a.length; i++) {
            for (DeckSection s : DeckSection.values()) {
                if (!d.has(s)) continue;
                for (PaperCard c : d.get(s).toFlatList()) {
                    if (c.getName().equals(a[i])) { System.out.println("print: " + c.getName() + " " + c.getEdition() + " #" + c.getCollectorNumber()); break; }
                }
            }
        }
        System.out.println(ok ? "PASS" : "FAIL: no single commander");
        System.exit(ok ? 0 : 1);
    }
}
