// Villainous choice: no impossible options (rule 701.55b, Comprehensive Rules of 2025-11-14:
// "While facing a villainous choice, a player may not choose an option that is illegal or
// impossible"). Forge 2.0.15 predates that wording and offers every option, so in the owner's
// game of 2026-10-29 opponents with empty hands kept answering Dr. Eggman with "discard a card"
// and he never got his Construct (CARD-MECHANICS.md).
//
// EvalCacheAgent routes VillainousChoiceEffect.resolve here (original renamed resolve$orig).
// This is Forge's own resolve, step for step, except that each player is only offered the
// options they can carry out:
//   - Discard: impossible with an empty hand;
//   - Sacrifice: impossible with nothing that matches SacValid.
// Everything else counts as possible. If every option is impossible the player keeps the full
// list (nothing to choose between either way), and a single option left is taken without asking.
// Logged as "VILLAINAI <player>: <option> only (<why>)". -Dforge.villainous.legal=false turns it
// off. Test: scratch\probe\VillainousProbe.java.

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;

import com.google.common.collect.ImmutableMap;

import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.ability.SpellAbilityEffect;
import forge.game.ability.effects.VillainousChoiceEffect;
import forge.game.card.Card;
import forge.game.card.CardLists;
import forge.game.player.Player;
import forge.game.player.PlayerCollection;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

public final class VillainousChoiceHook {
    private VillainousChoiceHook() {}

    static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("forge.villainous.legal", "true"));
    private static volatile MethodHandle orig, extractAmount, definedPlayers;

    public static void resolve(VillainousChoiceEffect self, SpellAbility sa) throws Throwable {
        if (!ENABLED) {
            if (orig == null) {
                orig = MethodHandles.privateLookupIn(VillainousChoiceEffect.class, MethodHandles.lookup())
                    .findVirtual(VillainousChoiceEffect.class, "resolve$orig", MethodType.methodType(void.class, SpellAbility.class));
            }
            orig.invoke(self, sa);
            return;
        }
        if (extractAmount == null) {
            MethodHandles.Lookup l = MethodHandles.privateLookupIn(SpellAbilityEffect.class, MethodHandles.lookup());
            extractAmount = l.findVirtual(SpellAbilityEffect.class, "extractAmount", MethodType.methodType(int.class, SpellAbility.class));
            definedPlayers = l.findStatic(SpellAbilityEffect.class, "getDefinedPlayersOrTargeted",
                MethodType.methodType(PlayerCollection.class, SpellAbility.class));
        }
        // Forge's resolve from here, with the options filtered per player.
        List<SpellAbility> choices = new ArrayList<>(sa.getAdditionalAbilityList("Choices"));
        int amount = (int) extractAmount.invoke((SpellAbilityEffect) self, sa);
        String prompt = sa.getParamOrDefault("ChoicePrompt", "Villainous choice by " + sa.getActivatingPlayer());
        Card source = sa.getHostCard();
        for (Player p : (PlayerCollection) definedPlayers.invoke(sa)) {
            int times = p.getAdditionalVillainousChoices() + 1;
            List<SpellAbility> chosen = new ArrayList<>();
            for (int i = 0; i < times; i++) {
                List<SpellAbility> legal = new ArrayList<>();
                StringBuilder why = new StringBuilder();
                for (SpellAbility c : choices) {
                    String no = impossible(c, p, source);
                    if (no == null) legal.add(c);
                    else why.append(why.length() == 0 ? "" : "; ").append(no);
                }
                if (legal.isEmpty() || legal.size() == choices.size()) {
                    chosen.addAll(p.getController().chooseSpellAbilitiesForEffect(choices, sa, prompt, amount, ImmutableMap.of()));
                } else if (legal.size() == 1) {
                    System.out.println("VILLAINAI " + p.getName() + ": " + legal.get(0).getDescription() + " only (" + why + ")");
                    chosen.add(legal.get(0));
                } else {
                    chosen.addAll(p.getController().chooseSpellAbilitiesForEffect(legal, sa, prompt, amount, ImmutableMap.of()));
                }
            }
            for (SpellAbility c : chosen) {
                source.addRemembered(p);
                AbilityUtils.resolve(c);
                source.removeRemembered(p);
            }
        }
    }

    /** Why player p cannot carry out this option, or null if they can. */
    static String impossible(SpellAbility c, Player p, Card source) {
        ApiType api = c.getApi();
        if (api == ApiType.Discard) {
            if (p.getCardsIn(ZoneType.Hand).isEmpty()) return "no cards in hand to discard";
        } else if (api == ApiType.Sacrifice) {
            String valid = c.getParamOrDefault("SacValid", "Permanent");
            if (CardLists.getValidCards(p.getCardsIn(ZoneType.Battlefield), valid, p, source, c).isEmpty())
                return "nothing to sacrifice";
        }
        return null;
    }
}
