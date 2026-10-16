// Loads Forge's card database the same way the simulator does and prints the
// script Forge actually uses for a card (to confirm custom-card overrides).
//
// Build/run from forge-installer-2.0.15 (see scripts\tools\cardcheck.ps1).

import forge.GuiDesktop;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.card.CardRules;

public class CardCheck {
    public static void main(String[] args) {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        for (String name : args) {
            CardRules rules = FModel.getMagicDb().getCommonCards().getRules(name, false);
            System.out.println("=== " + name);
            if (rules == null) { System.out.println("NOT FOUND"); continue; }
            System.out.println("custom=" + rules.isCustom());
            for (String a : rules.getMainPart().getAbilities()) {
                System.out.println("  A: " + a.substring(0, Math.min(140, a.length())));
            }
        }
        System.exit(0);
    }
}
