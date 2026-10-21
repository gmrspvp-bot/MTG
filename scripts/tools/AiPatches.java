// Runtime additions to Forge's AI, applied before a game starts. Forge's own
// files are not modified. Which additions run is chosen with the system property
// forge.aipatches (comma list), e.g. -Dforge.aipatches=rolldice
//
//   rolldice - Forge's dice AI has no answer when a roll asks "which roll
//              modifier card do you want to use?" (e.g. Night Shift of the
//              Living Dead's pay-1-life +/-1). The default picks the first card
//              and can be asked again forever, freezing the game. For optional
//              choices we decline (a legal choice); mandatory ones keep Forge's
//              default.
//   catch    - Captain America's Catch attaches protection first (see CatchAwareAttachAi).
//   counter  - Captain America's counterspells wait for a dangerous spell (see
//              ThreatAwareCounterAi). "counterall" gives the same logic to every player.
//   textbox  - registers AlwaysPlayAi for "exchange text boxes" so the AI casts
//              Deadpool, Trading Card. OFF by default: Forge's engine ran out of
//              memory after text swaps in long games.

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import forge.ai.SpellAbilityAi;
import forge.ai.SpellApiToAi;
import forge.ai.ability.AlwaysPlayAi;
import forge.ai.ability.RollDiceAi;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

public final class AiPatches {
    private AiPatches() {}

    /** Dice AI that declines optional "choose a card" prompts instead of looping. */
    public static class SafeRollDiceAi extends RollDiceAi {
        @Override
        protected Card chooseSingleCard(Player ai, SpellAbility sa, Iterable<Card> options, boolean isOptional,
                                        Player targetedPlayer, Map<String, Object> params) {
            if (isOptional) {
                return null;
            }
            return super.chooseSingleCard(ai, sa, options, isOptional, targetedPlayer, params);
        }
    }

    /**
     * Dice as an engine, the way the owner plays Mr House (2026-10-21; scratch\house-style.ps1
     * on their recorded games vs ~9,000 sim games): they rolled about 9 times a game - 4.7 on
     * their own turn, 4.0 on opponents' turns - and got ~24 Mr. House triggers; the AI rolled
     * 1.4 times a game, never on its own turn, and got ~3. Forge's RollDiceAi only allows a paid
     * dice ability in the end step right before the AI's own turn, once a round at most.
     *
     * When the AI controls a "whenever you roll" payoff (Mr. House, Wyll, Barbarian Class,
     * Vexing Puzzlebox, "Lifetime" Pass Holder, Night Shift...), or the ability is good by itself
     * (Component Pouch, The Deck of Many Things, Treasure Chest), its ACTIVATED dice abilities
     * are used whenever the mana would otherwise go unused:
     *   - instant speed: at the end of EVERY opponent's turn, and at its own end step; free
     *     ones ({T} only) also in its own second main phase;
     *   - sorcery speed / planeswalker (Comet, Stellar Pup): in its own main phases.
     * Guards: The Deck of Many Things only with 4 or fewer cards in hand (it subtracts hand size,
     * and 0 or less discards the hand); Treasure Chest only at 10+ life (a 1 costs 3 life).
     * Spells and triggers that roll are left to Forge. Logs DICEAI. Patch "dice".
     */
    public static class DiceEngineAi extends SafeRollDiceAi {
        static boolean enabled;

        static boolean hasRollPayoff(Player ai) {
            for (Card c : ai.getCardsIn(forge.game.zone.ZoneType.Battlefield)) {
                for (forge.game.trigger.Trigger t : c.getTriggers()) {
                    if (t.getMode() == forge.game.trigger.TriggerType.RolledDie
                        || t.getMode() == forge.game.trigger.TriggerType.RolledDieOnce) return true;
                }
            }
            return false;
        }

        static final List<String> GOOD_ALONE = Arrays.asList("Component Pouch", "The Deck of Many Things",
            "Treasure Chest", "Comet, Stellar Pup", "Mr. House, President and CEO");

        @Override
        protected forge.ai.AiAbilityDecision checkApiLogic(Player ai, SpellAbility sa) {
            Card host = sa.getHostCard();
            if (!enabled || host == null || !(sa.isActivatedAbility() || sa.isPwAbility()) || sa.isManaAbility()) {
                return super.checkApiLogic(ai, sa);
            }
            String name = host.getName();
            if (!hasRollPayoff(ai) && !GOOD_ALONE.contains(name)) return super.checkApiLogic(ai, sa);
            forge.ai.AiAbilityDecision no = new forge.ai.AiAbilityDecision(0, forge.ai.AiPlayDecision.CantPlayAi);
            if ("The Deck of Many Things".equals(name) && ai.getCardsIn(forge.game.zone.ZoneType.Hand).size() > 4) return no;
            if ("Treasure Chest".equals(name) && ai.getLife() < 10) return no;

            forge.game.phase.PhaseHandler ph = ai.getGame().getPhaseHandler();
            boolean mine = ph.isPlayerTurn(ai);
            boolean sorcery = sa.isPwAbility() || sa.getRestrictions() != null && sa.getRestrictions().isSorcerySpeed();
            boolean freeTap = sa.getPayCosts() == null || !sa.getPayCosts().hasManaCost();
            String when = null;
            if (sorcery) {
                if (mine && (ph.is(forge.game.phase.PhaseType.MAIN2) || ph.is(forge.game.phase.PhaseType.MAIN1))) when = "own main phase";
            } else if (ph.is(forge.game.phase.PhaseType.END_OF_TURN)) {
                when = mine ? "own end step" : "an opponent's end step";
            } else if (mine && freeTap && ph.is(forge.game.phase.PhaseType.MAIN2)) {
                when = "own main 2 (free)";
            }
            if (when == null) return no;
            System.out.println("DICEAI " + ai.getName() + ": " + name + " rolls at " + when + " (turn " + ph.getTurn() + ")");
            return new forge.ai.AiAbilityDecision(100, forge.ai.AiPlayDecision.WillPlay);
        }

        /**
         * Mana abilities that roll a die (Vexing Puzzlebox: "{T}: Add one mana of any color. Roll
         * a d20."). The AI only ever uses a mana ability to pay for something, so it never rolled
         * with one; the owner taps Puzzlebox for the roll alone, every round - and a d20 is 4+ (a
         * Mr. House robot) 85% of the time. At the end step of the player whose turn comes just
         * before the AI's, each untapped permanent of the AI with such a mana ability is tapped
         * for it: the mana is lost, which costs nothing - the permanent untaps a moment later -
         * and until then it stayed available as mana all round. Called from TurnHook.
         */
        /** -Dforge.dice.puzzlebox=false turns the end-step tap off (for A/B runs). */
        static final boolean PUZZLEBOX = !"false".equalsIgnoreCase(System.getProperty("forge.dice.puzzlebox", "true"));

        static void endStepRolls(forge.game.Game game, forge.game.event.GameEventTurnPhase ev) {
            if (!enabled || !PUZZLEBOX || ev.phase() != forge.game.phase.PhaseType.END_OF_TURN) return;
            Player next = game.getPhaseHandler().getNextTurn();
            if (next == null || next.hasLost() || !(next.getController() instanceof forge.ai.PlayerControllerAi)) return;
            for (Card c : new java.util.ArrayList<>(next.getCardsIn(forge.game.zone.ZoneType.Battlefield))) {
                if (c.isTapped()) continue;
                for (SpellAbility sa : c.getManaAbilities()) {
                    if (!rollsDice(sa)) continue;
                    sa.setActivatingPlayer(next);
                    if (!sa.canPlay()) continue;
                    forge.ai.ComputerUtil.playNoStack(next, sa, game, true);
                    System.out.println("DICEAI " + next.getName() + ": " + c.getName() + " tapped for its roll at the end step before its turn");
                    break;
                }
            }
        }

        static boolean rollsDice(SpellAbility sa) {
            for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
                if (s.getApi() == ApiType.RollDice) return true;
            }
            return false;
        }
    }

    /**
     * Captain America's "Catch" (attach up to one target Equipment you control to him at
     * beginning of combat). Forge's AttachAi picks the equipment essentially at random,
     * sometimes re-attaching gear he already wears. We choose: protection first if he has
     * none, otherwise the highest mana value piece (strongest / best Throw ammo).
     */
    public static class CatchAwareAttachAi extends forge.ai.ability.AttachAi {
        // Hexproof / shroud first: they stop targeted exile and destroy alike.
        static final List<String> HEXPROOF = Arrays.asList("Whispersilk Cloak", "Champion's Helm",
            "Lightning Greaves", "Swiftfoot Boots");
        static final List<String> OTHER_PROTECTION = Arrays.asList("Mithril Coat", "Darksteel Plate",
            "Commander's Plate", "Sword of Feast and Famine", "Sword of Light and Shadow", "Sword of War and Peace",
            "Kaldra Compleat", "Cloudsteel Kirin");   // Kaldra: indestructible; Kirin: "you can't lose" (v7)

        /** Cloudsteel Kirin goes on first at or below this life: "you can't lose the game". */
        static final int KIRIN_LIFE = 15;

        static Card pick(List<String> names, List<Card> candidates) {
            for (String name : names) {
                for (Card c : candidates) { if (c.getName().equals(name)) return c; }
            }
            return null;
        }

        @Override
        protected forge.ai.AiAbilityDecision checkApiLogic(Player ai, SpellAbility sa) {
            ManaHold.refresh(ai);   // equip costs mana too; keep counterspell mana open
            forge.ai.AiAbilityDecision d = super.checkApiLogic(ai, sa);
            if (sa.isEquip() && sa.getHostCard() != null && "Skullclamp".equals(sa.getHostCard().getName())) return steerSkullclamp(ai, sa);
            if (CaptainPolicy.enabled && sa.isEquip()) {
                forge.ai.AiAbilityDecision loop = loopEquip(ai, sa);
                if (loop != null) return loop;
            }
            if (CaptainPolicy.enabled && sa.isEquip()) d = steerEquip(ai, sa, d);
            return d;
        }

        /**
         * Re-equip for the throw loop: in our second main phase, put unattached big gear (mana
         * value >= LOOP_MIN_DAMAGE, not protection) back on Captain America when there is mana
         * for the equip and another throw ({3}, or {1} with Zirda out). Forge's own attach AI
         * doesn't equip after combat.
         */
        static forge.ai.AiAbilityDecision loopEquip(Player ai, SpellAbility sa) {
            Card eq = sa.getHostCard();
            if (eq == null || eq.getEquipping() != null) return null;
            if (eq.getCMC() < CaptainPolicy.loopMinDamage(ai) && !deathtouchWipeReady(ai, eq)) return null;
            if (HEXPROOF.contains(eq.getName()) || OTHER_PROTECTION.contains(eq.getName())) return null;
            forge.game.phase.PhaseHandler ph = ai.getGame().getPhaseHandler();
            if (ph.getPlayerTurn() != ai || !ai.getGame().getStack().isEmpty()
                    || (ph.getPhase() != forge.game.phase.PhaseType.MAIN1 && ph.getPhase() != forge.game.phase.PhaseType.MAIN2)) return null;
            Card cap = null;
            for (Card c : ai.getCardsIn(forge.game.zone.ZoneType.Battlefield)) {
                if (CaptainPolicy.isCaptain(c)) cap = c;
            }
            if (cap == null || !sa.canTarget(cap)) return null;
            // equip + throw; Puresteel Paladin makes equip {0}, Zirda takes {2} off each
            int equip = sa.getPayCosts() == null || sa.getPayCosts().getTotalMana() == null ? 2 : sa.getPayCosts().getTotalMana().getCMC();
            int need = CaptainPolicy.abilityCost(ai, sa, equip) + CaptainPolicy.throwCost(ai);
            if (forge.ai.ComputerUtilMana.getAvailableManaEstimate(ai, false) < need) return null;
            if (!forge.ai.ComputerUtilCost.canPayCost(sa, ai, false)) return null;
            sa.resetTargets();
            sa.getTargets().add(cap);
            System.out.println("EQUIPAI " + eq.getName() + " -> Captain America (throw loop)");
            return new forge.ai.AiAbilityDecision(100, forge.ai.AiPlayDecision.WillPlay);
        }

        /**
         * Captain America has deathtouch from other gear (Nazgul Battle-Mace) and this piece, thrown,
         * would kill 2+ opposing creatures at 1 damage each (the user's repeated wipe).
         */
        static boolean deathtouchWipeReady(Player ai, Card eq) {
            if (!CaptainPolicy.SPLIT || eq.getCMC() < 2 || CaptainPolicy.grantsDeathtouch(eq) || "Sword of Kaldra".equals(eq.getName())) return false;
            boolean dt = false, sword = false;
            for (Card c : ai.getCardsIn(forge.game.zone.ZoneType.Battlefield)) {
                if (!CaptainPolicy.isCaptain(c)) continue;
                for (Card e : c.getEquippedBy()) {
                    if (CaptainPolicy.grantsDeathtouch(e)) dt = true;
                    if ("Sword of Kaldra".equals(e.getName()) && eq.getCMC() != 4) sword = true;   // throws of this piece keep the Sword
                }
            }
            if (!dt && !sword) return false;
            int killable = 0;
            for (Player opp : ai.getOpponents()) {
                for (Card c : opp.getCreaturesInPlay()) {
                    if ((sword || !c.hasKeyword(forge.game.keyword.Keyword.INDESTRUCTIBLE)) && (!c.isToken() || c.getNetPower() >= 2)) killable++;
                }
            }
            return killable >= 2;
        }

        /** A creature Skullclamp pays off on: dies at once (1 toughness) or is a token; never a commander. */
        static boolean goodClampTarget(Card c) {
            return c != null && !c.isCommander() && (c.isToken() || c.getNetToughness() <= 1);
        }

        /**
         * Skullclamp, for every player: on a 1-toughness creature (dies at once, draw two), else
         * the smallest token, else not equipped at all. Forge put it on whatever it rated best,
         * e.g. Mr. House himself (0/4 -> 1/3) 30 times in 200 games, or Leonin Abunas.
         */
        static forge.ai.AiAbilityDecision steerSkullclamp(Player ai, SpellAbility sa) {
            Card eq = sa.getHostCard();
            // Already on a good clamp target: leave it (free equips from Puresteel Paladin
            // otherwise bounced it between tokens thousands of times in one game).
            if (goodClampTarget(eq.getEquipping())) return new forge.ai.AiAbilityDecision(0, forge.ai.AiPlayDecision.CantPlayAi);
            Card best = null;
            for (Card c : ai.getCreaturesInPlay()) {
                if (!goodClampTarget(c) || c == eq.getEquipping() || !sa.canTarget(c)) continue;
                if (c.getNetToughness() <= 1) { if (best == null || best.getNetToughness() > 1 || c.isToken()) best = c; }
                else if (best == null || (best.getNetToughness() > 1 && c.getNetToughness() < best.getNetToughness())) best = c;
            }
            if (best == null) return new forge.ai.AiAbilityDecision(0, forge.ai.AiPlayDecision.CantPlayAi);
            sa.resetTargets();
            sa.getTargets().add(best);
            System.out.println("EQUIPAI Skullclamp -> " + best.getName());
            return new forge.ai.AiAbilityDecision(100, forge.ai.AiPlayDecision.WillPlay);
        }

        /**
         * Equip targets for Captain America's deck (only when he is on our side of the table):
         * equipment goes on Captain America while he is out, rather than another creature.
         * (Skullclamp is handled by steerSkullclamp.)
         */
        static forge.ai.AiAbilityDecision steerEquip(Player ai, SpellAbility sa, forge.ai.AiAbilityDecision d) {
            Card eq = sa.getHostCard();
            Card cap = null;
            for (Card c : ai.getCardsIn(forge.game.zone.ZoneType.Battlefield)) { if (CaptainPolicy.isCaptain(c)) cap = c; }
            if (cap == null || eq == null) return d;
            // Never move equipment off Captain America (that and the steer back made loops).
            if (eq.getEquipping() == cap) return new forge.ai.AiAbilityDecision(0, forge.ai.AiPlayDecision.CantPlayAi);
            if (!d.willingToPlay() || !sa.canTarget(cap)) return d;
            Card chosen = sa.getTargets() == null || sa.getTargets().getTargetCards().isEmpty() ? null : sa.getTargets().getTargetCards().get(0);
            if (chosen != null && chosen != cap) {
                sa.resetTargets();
                sa.getTargets().add(cap);
                System.out.println("EQUIPAI " + eq.getName() + ": " + chosen.getName() + " -> Captain America");
            }
            return d;
        }

        /**
         * The Attach half of Codsworth's / Weapons Vendor's ability: EquipHelperPumpAi has
         * already chosen both targets, so accept it (Forge's attach AI would reject it and
         * with it the whole ability).
         */
        @Override
        public forge.ai.AiAbilityDecision chkDrawback(Player ai, SpellAbility sa) {
            SpellAbility parent = sa.getParent();
            if (CaptainPolicy.enabled && parent != null && EquipHelper.applies(parent) && sa.getTargets() != null
                    && !sa.getTargets().isEmpty()) {
                return new forge.ai.AiAbilityDecision(100, forge.ai.AiPlayDecision.WillPlay);
            }
            return super.chkDrawback(ai, sa);
        }

        @Override
        protected forge.ai.AiAbilityDecision doTriggerNoCost(Player ai, SpellAbility sa, boolean mandatory) {
            Card host = sa.getHostCard();
            if (CaptainPolicy.enabled && host != null && "Sokka and Suki".equals(host.getName())
                    && "Targeted".equals(sa.getParam("Object")) && sa.usesTargeting()) {
                // Sokka and Suki attach "up to one" Equipment to the Ally that entered (often a
                // 1/1 token): only take gear lying unattached, never strip Captain America.
                Card pick = null;
                for (Card e : ai.getCardsIn(forge.game.zone.ZoneType.Battlefield)) {
                    if (e.isEquipment() && e.getEquipping() == null && sa.canTarget(e)
                            && (pick == null || e.getCMC() > pick.getCMC())) pick = e;
                }
                sa.resetTargets();
                if (pick != null) sa.getTargets().add(pick);
                System.out.println("EQUIPAI Sokka and Suki: " + (pick == null ? "nothing (no unattached equipment)" : pick.getName()));
                return new forge.ai.AiAbilityDecision(100, forge.ai.AiPlayDecision.WillPlay);
            }
            if (host == null || !"Captain America, First Avenger".equals(host.getName())
                    || !"Targeted".equals(sa.getParam("Object")) || !sa.usesTargeting()) {
                return super.doTriggerNoCost(ai, sa, mandatory);
            }
            java.util.List<Card> candidates = new java.util.ArrayList<>();
            for (Card c : ai.getCardsIn(forge.game.zone.ZoneType.Battlefield)) {
                if (c.isEquipment() && c.getEquipping() != host && sa.canTarget(c)) candidates.add(c);
            }
            boolean hasHexproof = false, hasAnyProtection = false;
            for (Card e : host.getEquippedBy()) {
                if (HEXPROOF.contains(e.getName())) { hasHexproof = true; hasAnyProtection = true; }
                if (OTHER_PROTECTION.contains(e.getName())) { hasAnyProtection = true; }
            }
            Card choice = null;
            // Low on life, "you can't lose the game" beats hexproof (the owner lost on 2 life to
            // unblockable flyers).
            if (ai.getLife() <= KIRIN_LIFE) choice = pick(Arrays.asList("Cloudsteel Kirin"), candidates);
            if (choice == null && !hasHexproof) choice = pick(HEXPROOF, candidates);
            if (choice == null && !hasAnyProtection) choice = pick(OTHER_PROTECTION, candidates);
            if (choice == null) {
                for (Card c : candidates) {
                    if (choice == null) { choice = c; continue; }
                    boolean cIsClamp = "Skullclamp".equals(c.getName());
                    boolean chIsClamp = "Skullclamp".equals(choice.getName());
                    if (chIsClamp && !cIsClamp) { choice = c; continue; }
                    if (cIsClamp && !chIsClamp) continue;
                    if (c.getCMC() > choice.getCMC()) choice = c;
                }
            }
            sa.resetTargets();
            if (choice != null) sa.getTargets().add(choice);
            return new forge.ai.AiAbilityDecision(100, forge.ai.AiPlayDecision.WillPlay);
        }
    }

    /**
     * Counterspells that wait for a real threat. Forge's CounterAi counters the top spell
     * on the stack by dice roll on mana value (30% at 1, 75% at 2, 100% at 3+) plus a few
     * "always" categories, so it spends Spell Pierce on an Arcane Signet. For the players
     * this applies to, an opponent's spell is countered only if it is dangerous; then it
     * is always countered (if affordable), otherwise the counter is held.
     */
    public static class ThreatAwareCounterAi extends forge.ai.ability.CounterAi {
        static final List<String> COMMANDERS = Arrays.asList("Captain America, First Avenger");
        static final java.util.Set<ApiType> WIPES = java.util.EnumSet.of(ApiType.DestroyAll, ApiType.DamageAll,
            ApiType.ChangeZoneAll, ApiType.SacrificeAll);
        static boolean forEveryone = false;
        static SpellAbility lastLogged = null;

        static boolean appliesTo(Player ai) {
            if (forEveryone) return true;
            for (Card c : ai.getCommanders()) { if (COMMANDERS.contains(c.getName())) return true; }
            return false;
        }

        /** Why the spell is worth countering, or null if it isn't. */
        static String threat(Player ai, SpellAbility top) {
            Card host = top.getHostCard();
            if (host != null && host.isCommander()) return "commander";
            for (SpellAbility s = top; s != null; s = s.getSubAbility()) {
                ApiType api = s.getApi();
                if (api != null && WIPES.contains(api)
                        && (api != ApiType.ChangeZoneAll || s.getParamOrDefault("Origin", "").contains("Battlefield"))) {
                    return "board wipe";
                }
                forge.game.spellability.TargetChoices t = s.getTargets();
                if (t == null) continue;
                for (Card c : t.getTargetCards()) { if (c.getController() == ai) return "targets my " + c.getName(); }
                for (Player p : t.getTargetPlayers()) { if (p == ai) return "targets me"; }
                for (SpellAbility ts : t.getTargetSpells()) { if (ts.getActivatingPlayer() == ai) return "targets my spell"; }
            }
            if (host != null && host.getCMC() >= 4) return "mana value " + host.getCMC();
            // The player furthest ahead gets less room: their 3-drops are threats too.
            Player caster = top.getActivatingPlayer();
            if (host != null && host.getCMC() >= 3 && caster != null && caster == leader(ai)) return "leader's mana value " + host.getCMC();
            return null;
        }

        /** Opponent with the strongest board: total creature power + non-land permanents. */
        static Player leader(Player ai) {
            Player best = null;
            int bestScore = -1;
            for (Player p : ai.getOpponents()) {
                int score = 0;
                for (Card c : p.getCardsIn(forge.game.zone.ZoneType.Battlefield)) {
                    if (c.isCreature()) score += Math.max(0, c.getNetPower());
                    if (!c.isLand()) score++;
                }
                if (score > bestScore) { bestScore = score; best = p; }
            }
            return best;
        }

        @Override
        protected forge.ai.AiAbilityDecision checkApiLogic(Player ai, SpellAbility sa) {
            forge.game.Game game = ai.getGame();
            if (!appliesTo(ai) || game.getStack().isEmpty()) return super.checkApiLogic(ai, sa);
            SpellAbility top = forge.ai.ComputerUtilAbility.getTopSpellAbilityOnStack(game, sa);
            if (top == null || top.getActivatingPlayer() == null || !ai.isOpponentOf(top.getActivatingPlayer())) {
                return super.checkApiLogic(ai, sa);
            }
            String why = threat(ai, top);
            if (why == null) return new forge.ai.AiAbilityDecision(0, forge.ai.AiPlayDecision.CantPlayAi);
            int heldBefore = 0, untappedLands = 0, lands = 0;
            for (Card c : ai.getCardsIn(forge.game.zone.ZoneType.Battlefield)) {
                if (!c.isLand()) continue;
                lands++;
                if (!c.isTapped()) untappedLands++;
                if (forge.ai.AiCardMemory.isRememberedCard(ai, c, ManaHold.HELD)) heldBefore++;
            }
            String diag = " [held " + heldBefore + ", untapped lands " + untappedLands + "/" + lands
                + ", phase " + game.getPhaseHandler().getPhase() + ", last hold: " + ManaHold.lastInfo.get(ai) + "]";
            ManaHold.release(ai);   // the held lands are for exactly this
            forge.ai.AiAbilityDecision d = super.checkApiLogic(ai, sa);
            if (!d.willingToPlay()) ManaHold.refresh(ai);
            // Forge's own dice roll says no (CantPlayAi) after it has already chosen the target
            // and any X: overrule it. Other refusals (can't afford, can't target) stand.
            if (!d.willingToPlay() && d.decision() == forge.ai.AiPlayDecision.CantPlayAi
                    && sa.getTargets().isTargetingAnySpell()) {
                d = new forge.ai.AiAbilityDecision(100, forge.ai.AiPlayDecision.WillPlay);
            }
            if (d.willingToPlay() && top != lastLogged) {
                lastLogged = top;
                System.out.println("COUNTERAI " + sa.getHostCard().getName() + " -> "
                    + (top.getHostCard() == null ? top : top.getHostCard().getName()) + " (" + why + ") can pay: "
                    + (forge.ai.ComputerUtilCost.canPayCost(sa.getRootAbility(), ai, false) ? "yes" : "no") + diag);
            }
            return d;
        }
    }

    /**
     * Keeps mana open for dice (the owner's Mr House games, 2026-10-21: about 8.7 dice
     * activations a game against the AI's 1.4-2.6 - the AI spends every land in its main phases,
     * so at the end steps, where DiceEngineAi rolls, it had nothing left). While the AI controls
     * an instant-speed activated ability that rolls a die and costs mana (Mr. House, Treasure
     * Chest...), enough untapped lands for the cheapest one are held - the same Forge memory set
     * ManaHold uses, which payment code will not tap - from each turn's start, and let go at every
     * end step, which is when the AI rolls with them. Only with lands to spare (the cost plus
     * three), and only lands whose colours the rest of the hand does not need, where possible.
     * Patch "dice", OFF by default (no gain in the A/B); -Dforge.dice.hold=true turns it on. Logged as "DICEHOLD".
     */
    static final class DiceHold {
        // Off by default: the overnight A/B (2026-10-22, 120 games each, Mr House v4 / Doom v3 /
        // Rin and Seri / Olivia) found no gain - 26% vs 27% - and the held lands were seldom
        // spent on dice (0.3 rolls a game on opponents' turns). -Dforge.dice.hold=true to try it.
        static final boolean ON = "true".equalsIgnoreCase(System.getProperty("forge.dice.hold", "false"));
        static final java.util.Map<Player, List<Card>> held = new java.util.WeakHashMap<>();
        /** Between an end step and the next turn's start the hold is off, so the lands can be used. */
        static volatile boolean endStep;

        static boolean on() { return ON && DiceEngineAi.enabled; }

        static void onEvent(forge.game.Game game, Object ev) {
            if (!on()) return;
            if (ev instanceof forge.game.event.GameEventTurnBegan) {
                endStep = false;
                for (Player p : game.getPlayers()) hold(p);
            } else if (ev instanceof forge.game.event.GameEventTurnPhase) {
                forge.game.phase.PhaseType ph = ((forge.game.event.GameEventTurnPhase) ev).phase();
                if (ph == forge.game.phase.PhaseType.END_OF_TURN) {
                    endStep = true;
                    for (Player p : game.getPlayers()) let(p);
                } else if (ph == forge.game.phase.PhaseType.MAIN1 || ph == forge.game.phase.PhaseType.MAIN2) {
                    Player active = game.getPhaseHandler().getPlayerTurn();
                    if (active != null) hold(active);
                }
            }
        }

        /** After ManaHold cleared the set: remember our lands again, unless we are in an end step. */
        static void reapply(Player ai) {
            if (!on() || endStep) return;
            List<Card> mine = held.get(ai);
            if (mine == null) return;
            for (Card c : mine) { if (!c.isTapped()) forge.ai.AiCardMemory.rememberCard(ai, c, ManaHold.HELD); }
        }

        static void let(Player ai) {
            List<Card> mine = held.remove(ai);
            if (mine == null || !(ai.getController() instanceof forge.ai.PlayerControllerAi)) return;
            for (Card c : mine) forge.ai.AiCardMemory.forgetCard(ai, c, ManaHold.HELD);
        }

        static void hold(Player ai) {
            if (ai.hasLost() || !(ai.getController() instanceof forge.ai.PlayerControllerAi)) return;
            let(ai);
            List<String> need = null;
            String what = null;
            for (Card c : ai.getCardsIn(forge.game.zone.ZoneType.Battlefield)) {
                for (SpellAbility sa : c.getSpellAbilities()) {
                    if (!sa.isActivatedAbility() || sa.isManaAbility() || sa.isPwAbility() || !DiceEngineAi.rollsDice(sa)) continue;
                    if (sa.getRestrictions() != null && sa.getRestrictions().isSorcerySpeed()) continue;
                    forge.game.cost.Cost cost = sa.getPayCosts();
                    if (cost == null || !cost.hasManaCost()) continue;            // free ones need nothing held
                    if (cost.hasTapCost() && c.isTapped()) continue;
                    List<String> req = ManaHold.manaNeeded(cost.getTotalMana());
                    if (req.isEmpty()) continue;
                    if (need == null || req.size() < need.size()) { need = req; what = c.getName(); }
                }
            }
            if (need == null) return;
            int landCount = 0;
            List<Card> lands = new java.util.ArrayList<>();
            for (Card c : ai.getCardsIn(forge.game.zone.ZoneType.Battlefield)) {
                if (!c.isLand()) continue;
                landCount++;
                if (!c.isTapped()) lands.add(c);
            }
            if (landCount < need.size() + 3 || lands.size() < need.size()) return;
            java.util.Set<String> otherColours = new java.util.HashSet<>();
            for (Card c : ai.getCardsIn(forge.game.zone.ZoneType.Hand)) {
                if (c.isLand()) continue;
                for (String sym : ManaHold.manaNeeded(c.getManaCost())) { if (!sym.equals("1")) otherColours.add(sym); }
            }
            lands.sort((a, b) -> Integer.compare(ManaHold.otherColourCount(a, otherColours), ManaHold.otherColourCount(b, otherColours)));
            List<Card> pick = new java.util.ArrayList<>();
            for (String sym : need) {
                if (sym.equals("1")) continue;
                Card found = null;
                for (Card c : lands) { if (!pick.contains(c) && ManaHold.produces(c, sym)) { found = c; break; } }
                if (found == null) return;
                pick.add(found);
            }
            for (String sym : need) {
                if (!sym.equals("1")) continue;
                for (Card c : lands) { if (!pick.contains(c)) { pick.add(c); break; } }
            }
            if (pick.size() < need.size()) return;
            for (Card c : pick) forge.ai.AiCardMemory.rememberCard(ai, c, ManaHold.HELD);
            held.put(ai, pick);
            if (ai.getGame().getPhaseHandler().getPhase() == forge.game.phase.PhaseType.MAIN1
                && ai.getGame().getPhaseHandler().isPlayerTurn(ai)) {
                System.out.println("DICEHOLD " + ai.getName() + ": " + pick.size() + " lands kept for " + what
                    + " (turn " + ai.getGame().getPhaseHandler().getTurn() + ")");
            }
        }
    }
    /**
     * Keeps mana open for a counterspell. Forge's AI spends everything on its own turn, so
     * threats arrive while it is tapped out. While a counterspell is in hand (and there are
     * at least 2 other lands), enough untapped lands for the cheapest one are put in the AI's
     * HELD_MANA_SOURCES_FOR_DECLBLK memory, which Forge's payment code won't tap in any
     * phase except declare blockers / cleanup. Refreshed by TurnHook (own upkeep and main
     * phases, other players' turn starts) and whenever the AI weighs casting a permanent or
     * equipping; released by ThreatAwareCounterAi when it counters.
     */
    static final class ManaHold {
        static final forge.ai.AiCardMemory.MemorySet HELD = forge.ai.AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_DECLBLK;

        static void release(Player ai) {
            if (ai.getController() instanceof forge.ai.PlayerControllerAi) forge.ai.AiCardMemory.clearMemorySet(ai, HELD);
        }

        static boolean enabled = false;
        /** Diagnostics: what the last refresh did for each player. */
        static final java.util.Map<Player, String> lastInfo = new java.util.WeakHashMap<>();

        static void note(Player ai, String what) {
            lastInfo.put(ai, what + " @" + ai.getGame().getPhaseHandler().getPhase() + " t" + ai.getGame().getPhaseHandler().getTurn());
        }

        /** Every refresh also puts the dice hold back: release() above clears the whole set. */
        static void refresh(Player ai) {
            try { refreshCounter(ai); } finally { DiceHold.reapply(ai); }
        }

        static void refreshCounter(Player ai) {
            if (!enabled || !ThreatAwareCounterAi.appliesTo(ai) || !(ai.getController() instanceof forge.ai.PlayerControllerAi)) return;
            release(ai);
            // Hold mana for the cheapest counterspell in hand (what a player would keep up).
            List<String> need = null;
            for (Card c : ai.getCardsIn(forge.game.zone.ZoneType.Hand)) {
                if (!isCounterspell(c)) continue;
                List<String> req = manaNeeded(c.getManaCost());
                if (req.isEmpty()) continue;   // free counters (Force of Will etc.) need nothing held
                if (need == null || req.size() < need.size()) need = req;
            }
            if (need == null) { note(ai, "no counter in hand"); return; }
            List<Card> lands = new java.util.ArrayList<>();
            for (Card c : ai.getCardsIn(forge.game.zone.ZoneType.Battlefield)) { if (c.isLand() && !c.isTapped()) lands.add(c); }
            int landCount = 0;
            for (Card c : ai.getCardsIn(forge.game.zone.ZoneType.Battlefield)) { if (c.isLand()) landCount++; }
            if (landCount < need.size() + 2) { note(ai, "too few lands (" + landCount + ")"); return; }   // keep at least 2 lands free to develop
            // Colours the rest of the hand needs: hold lands that don't make those where possible,
            // so cheap spells (e.g. a 1-mana enchantment) can still be cast around the hold.
            java.util.Set<String> otherColours = new java.util.HashSet<>();
            for (Card c : ai.getCardsIn(forge.game.zone.ZoneType.Hand)) {
                if (c.isLand() || isCounterspell(c)) continue;
                for (String sym : manaNeeded(c.getManaCost())) { if (!sym.equals("1")) otherColours.add(sym); }
            }
            lands.sort((a, b) -> Integer.compare(otherColourCount(a, otherColours), otherColourCount(b, otherColours)));
            List<Card> held = new java.util.ArrayList<>();
            for (String sym : need) {          // coloured symbols first, then generic
                if (sym.equals("1")) continue;
                Card pick = null;
                for (Card c : lands) { if (!held.contains(c) && produces(c, sym)) { pick = c; break; } }
                if (pick == null) { note(ai, "no untapped " + sym + " source (" + lands.size() + " untapped)"); return; }   // can't keep that colour open anyway
                held.add(pick);
            }
            for (String sym : need) {
                if (!sym.equals("1")) continue;
                for (Card c : lands) { if (!held.contains(c)) { held.add(c); break; } }
            }
            for (Card c : held) forge.ai.AiCardMemory.rememberCard(ai, c, HELD);
            note(ai, "held " + held.size() + " for " + need);
        }

        static int otherColourCount(Card land, java.util.Set<String> colours) {
            int n = 0;
            for (String col : colours) { if (produces(land, col)) n++; }
            return n;
        }

        static boolean isCounterspell(Card c) {
            if (!c.isInstant()) return false;
            for (SpellAbility sa : c.getSpells()) { if (sa.getApi() == ApiType.Counter) return true; }
            String text = c.getOracleText();
            return text != null && text.contains("Counter target");   // modal ones, e.g. Amazing Acrobatics
        }

        /** Mana symbols to keep open for a cost: one entry per coloured symbol ("U"), "1" per generic. */
        static List<String> manaNeeded(forge.card.mana.ManaCost cost) {
            List<String> req = new java.util.ArrayList<>();
            int generic = cost.getGenericCost() + (cost.countX() > 0 ? 1 : 0);
            for (forge.card.mana.ManaCostShard sh : cost) {
                if (sh.isMonoColor() && !sh.isPhyrexian()) req.add(forge.card.MagicColor.toShortString(sh.getColorMask()));
                else if (!sh.isPhyrexian()) generic++;
            }
            for (int i = 0; i < generic; i++) req.add("1");
            return req;
        }

        static boolean produces(Card c, String colour) {
            for (SpellAbility m : c.getManaAbilities()) {
                forge.game.spellability.AbilityManaPart p = m.getManaPart();
                if (p != null && (p.isAnyMana() || p.canProduce(colour, m))) return true;
            }
            return false;
        }
    }

    /**
     * Game-event hook (subscribed by SimRunner): at the start of every turn, each player
     * whose turn it isn't re-holds mana for a counterspell. Forge clears the held lands
     * in the holder's own cleanup step, so without this nothing stays held over the
     * opponents' turns unless the AI happened to weigh a permanent spell late in its turn.
     */
    /**
     * "rampfirst": Forge's AI only casts mana rocks in its second main phase (unless the card
     * script says PlayMain1), by which time it has usually spent its mana, so Arcane Signet
     * and friends sat in hand. People play rocks first. At game start every cheap mana rock
     * (non-land artifact with a mana ability, mana value <= 4) gets PlayMain1:ALWAYS and a
     * small cast-priority boost, unless its script already sets them.
     */
    static boolean rampFirst = false;
    /**
     * "cmdrfirst": commanders were first cast in round 5-7 although they cost 3-4; in 55-60%
     * of the turns they stayed in the command zone the mana had gone on other spells (Forge
     * holds creatures for the second main phase and spends main phase 1 elsewhere). People
     * cast their commander as soon as they can, so commanders get PlayMain1:ALWAYS and a high
     * cast priority (kept if the script already has one, e.g. Captain America's 9).
     */
    static boolean commanderFirst = false;

    /**
     * Timing experiment, -Dforge.timing=...: "equipfirst" casts equipment in the first main
     * phase (PlayMain1:ALWAYS) so it can be equipped and attack that turn; Forge otherwise
     * cast about a third of Captain America's equipment after combat. Default "forge" = off.
     */
    static final String TIMING = System.getProperty("forge.timing", "forge").toLowerCase();

    public static void prepareGame(forge.game.Game game) {
        for (Player p : game.getPlayers()) {
            if ("equipfirst".equals(TIMING)) {
                for (Card c : p.getAllCards()) {
                    if (c.isEquipment() && !c.isToken() && !c.hasSVar("PlayMain1")) c.setSVar("PlayMain1", "ALWAYS");
                }
            } else if ("allmain1".equals(TIMING)) {
                // Comparison point: every nonland permanent cast before combat.
                for (Card c : p.getAllCards()) {
                    if (c.isPermanent() && !c.isLand() && !c.isToken() && !c.hasSVar("PlayMain1")) c.setSVar("PlayMain1", "ALWAYS");
                }
            }
            if (commanderFirst) {
                for (Card c : p.getCommanders()) {
                    c.setSVar("PlayMain1", "ALWAYS");
                    if (!c.hasSVar("AIPriorityModifier")) c.setSVar("AIPriorityModifier", "8");
                }
            }
            if (!rampFirst) continue;
            for (Card c : p.getAllCards()) {
                if (c.isLand() || !c.isArtifact() || c.getManaAbilities().isEmpty() || c.getCMC() > 4 || c.isToken()) continue;
                if (!c.hasSVar("PlayMain1")) c.setSVar("PlayMain1", "ALWAYS");
                if (!c.hasSVar("AIPriorityModifier")) c.setSVar("AIPriorityModifier", "4");
            }
        }
    }

    public static class TurnHook {
        private final forge.game.Game game;
        private boolean prepared = false;

        public TurnHook(forge.game.Game game) { this.game = game; }

        @com.google.common.eventbus.Subscribe
        public void onEvent(Object ev) {
            if (!prepared && ev instanceof forge.game.event.GameEventTurnBegan) {
                prepared = true;
                try { prepareGame(game); } catch (Throwable t) { /* never break the game */ }
            }
            if (ev instanceof forge.game.event.GameEventTurnPhase) {
                try { DiceEngineAi.endStepRolls(game, (forge.game.event.GameEventTurnPhase) ev); } catch (Throwable t) { /* never break the game */ }
            }
            try { DiceHold.onEvent(game, ev); } catch (Throwable t) { /* never break the game */ }
            if (!ManaHold.enabled) return;
            try {
                Player active = game.getPhaseHandler().getPlayerTurn();
                if (ev instanceof forge.game.event.GameEventTurnBegan) {
                    // Re-arm for everyone else (Forge clears the hold in its owner's cleanup).
                    for (Player p : game.getPlayers()) { if (p != active) ManaHold.refresh(p); }
                } else if (ev instanceof forge.game.event.GameEventLandPlayed && active != null) {
                    // The AI often plays its land late in the turn; that land may be what makes
                    // holding a counter possible.
                    ManaHold.refresh(active);
                } else if (ev instanceof forge.game.event.GameEventTurnPhase && active != null) {
                    // Lands spent on your own turn stay tapped through the opponents' turns, so
                    // the hold has to be in place before the active player spends anything:
                    // after untap (upkeep), and again for each main phase.
                    forge.game.phase.PhaseType ph = ((forge.game.event.GameEventTurnPhase) ev).phase();
                    if (ph == forge.game.phase.PhaseType.UPKEEP || ph == forge.game.phase.PhaseType.MAIN1
                            || ph == forge.game.phase.PhaseType.MAIN2) {
                        ManaHold.refresh(active);
                    }
                }
            } catch (Throwable t) {
                // never break the game
            }
        }
    }

    /**
     * Removal aimed by threat (see ThreatTracker). After Forge picks the single target of a
     * destroy / exile / bounce effect, switch to an opponent's permanent the effect can also
     * target if its threat score is clearly higher (RETARGET_MARGIN). Mainly matters for
     * non-creature engines, which Forge otherwise judges by mana value alone.
     */
    static final class ThreatRetarget {
        static final double RETARGET_MARGIN = 60;
        static final int SMALL_TOKEN = 170;   // Forge creature evaluation: about a 2/2 or less

        static double value(Card c) {
            return (c.isCreature() ? forge.ai.ComputerUtilCard.evaluateCreature(c) : 200) + ThreatTracker.score(c)
                + (c.isCommander() ? 150 : 0);
        }

        /**
         * Removal aimed at an opponent's token creature (Terminate on an Ox token, Bedevil on a
         * Hero token...): switch to a real (non-token) creature or planeswalker worth at least
         * 80% as much, if the spell can hit one (canKill: e.g. enough damage). Otherwise a small
         * token (about 2/2 or less) isn't worth the card: the spell waits, unless that token is
         * attacking us right now. Returns the decision to use.
         */
        static forge.ai.AiAbilityDecision avoidTokens(Player ai, SpellAbility sa, forge.ai.AiAbilityDecision d,
                                                      java.util.function.Predicate<Card> canKill) {
            if (!ThreatTracker.enabled || d == null || !d.willingToPlay() || !sa.usesTargeting() || sa.isTrigger()) return d;
            forge.game.spellability.TargetChoices t = sa.getTargets();
            if (t == null || t.size() != 1 || t.getTargetCards().size() != 1) return d;
            Card chosen = t.getTargetCards().get(0);
            if (!chosen.isToken() || !chosen.isCreature() || chosen.getController() == null || !ai.isOpponentOf(chosen.getController())) return d;
            double tokenValue = value(chosen);
            Card alt = null;
            double altValue = -1;
            for (Player opp : ai.getOpponents()) {
                for (Card c : opp.getCardsIn(forge.game.zone.ZoneType.Battlefield)) {
                    if (c.isToken() || !(c.isCreature() || c.isPlaneswalker()) || !sa.canTarget(c) || !canKill.test(c)) continue;
                    double v = value(c);
                    if (v > altValue) { altValue = v; alt = c; }
                }
            }
            String name = sa.getHostCard().getName();
            if (alt != null && altValue >= 0.8 * tokenValue) {
                sa.resetTargets();
                sa.getTargets().add(alt);
                System.out.println("THREATAI " + name + ": token " + chosen.getName() + " -> " + alt.getName());
                return d;
            }
            forge.game.combat.Combat combat = ai.getGame().getCombat();
            boolean attackingMe = combat != null && combat.isAttacking(chosen) && combat.getDefenderPlayerByAttacker(chosen) == ai;
            if (forge.ai.ComputerUtilCard.evaluateCreature(chosen) < SMALL_TOKEN && !attackingMe) {
                System.out.println("THREATAI " + name + ": not spent on small token " + chosen.getName());
                return new forge.ai.AiAbilityDecision(0, forge.ai.AiPlayDecision.CantPlayAi);
            }
            return d;
        }

        // At least as big: a removed commander comes back for {2} more, an exiled creature doesn't
        // (1.25 kept the removal on Seluma (351) next to Lyra Dawnbringer (386)).
        static final double COMMANDER_SWAP_RATIO = 1.0;
        static final int COMMANDER_SWAP_MIN = 300;   // Forge evaluation: a real threat (a 3/3 vanilla is ~200)

        /**
         * Captain America's player only: removal aimed at an opposing commander is moved to a
         * non-commander creature at least as big (Forge value >= the commander's, and 300+).
         * A removed commander is recast from the command zone; against Angels most of the AI's
         * removal went at commanders (Doom 12, Olivia 9, Seluma 9) and only 23% at Lyra,
         * Baneslayer, Gisela and the other angels doing the damage. Destroy-only removal skips
         * indestructible creatures. -Dforge.captain.cmdrswap=false turns this off (A/B tests).
         */
        static final boolean CMDR_SWAP = !"false".equalsIgnoreCase(System.getProperty("forge.captain.cmdrswap", "true"));

        static Card biggerNonCommander(Player ai, SpellAbility sa, Card chosen, boolean zoneChange) {
            if (!CaptainPolicy.enabled || !CMDR_SWAP) return null;
            boolean capPlayer = false;
            for (Card c : ai.getCommanders()) { if (CaptainPolicy.isCaptain(c)) capPlayer = true; }
            if (!capPlayer || !chosen.isCreature()) return null;
            double need = Math.max(COMMANDER_SWAP_MIN, COMMANDER_SWAP_RATIO * forge.ai.ComputerUtilCard.evaluateCreature(chosen));
            Card best = null;
            double bestValue = -1;
            for (Player opp : ai.getOpponents()) {
                for (Card c : opp.getCreaturesInPlay()) {
                    if (c.isCommander() || c.isToken() || !sa.canTarget(c)) continue;
                    if (!zoneChange && c.hasKeyword(forge.game.keyword.Keyword.INDESTRUCTIBLE)) continue;
                    double v = forge.ai.ComputerUtilCard.evaluateCreature(c);
                    if (v < need) continue;
                    v += ThreatTracker.score(c);
                    if (v > bestValue) { bestValue = v; best = c; }
                }
            }
            return best;
        }

        static forge.ai.AiAbilityDecision apply(Player ai, SpellAbility sa, forge.ai.AiAbilityDecision d, boolean zoneChange) {
            if (!ThreatTracker.enabled || d == null || !d.willingToPlay() || !sa.usesTargeting()) return d;
            if (zoneChange) {
                String origin = sa.getParamOrDefault("Origin", "");
                String dest = sa.getParamOrDefault("Destination", "");
                if (!origin.contains("Battlefield") || dest.contains("Battlefield")) return d;
            }
            d = avoidTokens(ai, sa, d, c -> !(!zoneChange && c.hasKeyword(forge.game.keyword.Keyword.INDESTRUCTIBLE)));
            if (!d.willingToPlay()) return d;
            forge.game.spellability.TargetChoices t = sa.getTargets();
            if (t == null || t.size() != 1 || t.getTargetCards().size() != 1) return d;
            Card chosen = t.getTargetCards().get(0);
            if (chosen.getController() == null || !ai.isOpponentOf(chosen.getController())) return d;
            // Forge's own pick already reflects a creature's strength (and its threat, via the
            // evaluation hint), and a commander is a threat before it has done anything.
            if (chosen.isCommander()) {
                Card alt = biggerNonCommander(ai, sa, chosen, zoneChange);
                if (alt == null) return d;
                sa.resetTargets();
                sa.getTargets().add(alt);
                System.out.println("THREATAI " + sa.getHostCard().getName() + ": commander " + chosen.getName() + " -> " + alt.getName()
                    + " (bigger non-commander threat; commanders come back)");
                return d;
            }
            double best = ThreatTracker.score(chosen) + (chosen.isCreature() ? 100 : RETARGET_MARGIN);
            Card pick = null;
            for (Player opp : ai.getOpponents()) {
                for (Card c : opp.getCardsIn(forge.game.zone.ZoneType.Battlefield)) {
                    double s = ThreatTracker.score(c);
                    if (c != chosen && s > best && sa.canTarget(c)) { best = s; pick = c; }
                }
            }
            if (pick == null) return d;
            sa.resetTargets();
            sa.getTargets().add(pick);
            System.out.println("THREATAI " + sa.getHostCard().getName() + ": " + chosen.getName() + " -> " + pick.getName()
                + " (threat " + Math.round(ThreatTracker.score(chosen)) + " -> " + Math.round(best) + ")");
            return d;
        }
    }

    public static class ThreatDestroyAi extends forge.ai.ability.DestroyAi {
        @Override
        protected forge.ai.AiAbilityDecision checkApiLogic(Player ai, SpellAbility sa) {
            return ThreatRetarget.apply(ai, sa, super.checkApiLogic(ai, sa), false);
        }

        @Override
        protected forge.ai.AiAbilityDecision doTriggerNoCost(Player ai, SpellAbility sa, boolean mandatory) {
            return ThreatRetarget.apply(ai, sa, super.doTriggerNoCost(ai, sa, mandatory), false);
        }
    }

    public static class ThreatChangeZoneAi extends forge.ai.ability.ChangeZoneAi {
        @Override
        protected forge.ai.AiAbilityDecision checkApiLogic(Player ai, SpellAbility sa) {
            forge.ai.AiAbilityDecision ov = OneSidedOverload.decide(ai, sa);
            if (ov != null) return ov;
            forge.ai.AiAbilityDecision sfm = putEquipmentFromHand(ai, sa);
            if (sfm != null) return sfm;
            return ThreatRetarget.apply(ai, sa, super.checkApiLogic(ai, sa), true);
        }

        /**
         * Stoneforge Mystic's "{1}{W}, {T}: put an Equipment card from your hand onto the
         * battlefield" (any such hand-to-battlefield Equipment ability): Forge's AI refused it
         * even with Kaldra Compleat in hand (2 uses in 15 Stoneforge casts, while Kaldra and
         * Excalibur were discarded to hand size). Use it in our main phase whenever an Equipment
         * in hand costs more than the activation; Forge already picks the most expensive one.
         */
        static forge.ai.AiAbilityDecision putEquipmentFromHand(Player ai, SpellAbility sa) {
            if (!sa.isActivatedAbility() || !"Hand".equals(sa.getParam("Origin")) || !"Battlefield".equals(sa.getParam("Destination"))
                    || !sa.getParamOrDefault("ChangeType", "").contains("Equipment")) return null;
            forge.game.phase.PhaseHandler ph = ai.getGame().getPhaseHandler();
            if (ph.getPlayerTurn() != ai || !ph.getPhase().isMain()) return null;
            int cost = sa.getPayCosts() == null || sa.getPayCosts().getTotalMana() == null ? 0 : sa.getPayCosts().getTotalMana().getCMC();
            Card best = null;
            for (Card c : ai.getCardsIn(forge.game.zone.ZoneType.Hand)) {
                if (c.isEquipment() && c.getCMC() > cost && (best == null || c.getCMC() > best.getCMC())) best = c;
            }
            if (best == null) return null;
            System.out.println("EQUIPAI " + sa.getHostCard().getName() + " puts " + best.getName() + " onto the battlefield");
            return new forge.ai.AiAbilityDecision(100, forge.ai.AiPlayDecision.WillPlay);
        }

        @Override
        protected forge.ai.AiAbilityDecision doTriggerNoCost(Player ai, SpellAbility sa, boolean mandatory) {
            forge.ai.AiAbilityDecision thor = thorFlicker(ai, sa);
            if (thor != null) return thor;
            return ThreatRetarget.apply(ai, sa, super.doTriggerNoCost(ai, sa, mandatory), true);
        }

        /**
         * The Mighty Thor, Jane Foster: "whenever Thor attacks, exile up to one target nontoken
         * artifact or creature, then return it tapped". Forge's AI flickered its own permanents
         * (Kaldra Compleat twice - it comes back unattached on a fresh Germ, so Captain America
         * loses it - Mithril Coat, Leonin Abunas). Instead: the best untapped creature an
         * opponent could block with (it returns tapped); else our own Stoneforge Mystic (its
         * search triggers again); else nothing. Never our own Equipment.
         */
        static forge.ai.AiAbilityDecision thorFlicker(Player ai, SpellAbility sa) {
            Card host = sa.getHostCard();
            if (host == null || !"The Mighty Thor, Jane Foster".equals(host.getName()) || !"Exile".equals(sa.getParam("Destination"))) return null;
            Card best = null;
            for (Player opp : ai.getOpponents()) {
                for (Card c : opp.getCreaturesInPlay()) {
                    if (c.isTapped() || c.isToken() || !sa.canTarget(c)) continue;
                    if (best == null || forge.ai.ComputerUtilCard.evaluateCreature(c) > forge.ai.ComputerUtilCard.evaluateCreature(best)) best = c;
                }
            }
            if (best == null) {
                for (Card c : ai.getCreaturesInPlay()) {
                    if ("Stoneforge Mystic".equals(c.getName()) && sa.canTarget(c)) { best = c; break; }
                }
            }
            sa.resetTargets();
            if (best != null) sa.getTargets().add(best);
            System.out.println("THORAI flicker " + (best == null ? "nothing" : best.getName() + " (" + best.getController().getName() + ")"));
            return new forge.ai.AiAbilityDecision(100, forge.ai.AiPlayDecision.WillPlay);
        }
    }

    /**
     * "captain": how Captain America, First Avenger uses Throw and when he attacks.
     *  - Throw is saved for eliminations (lethal on a player) and for killing a high-value
     *    permanent: an opponent's commander, a planeswalker, a proven threat (ThreatTracker
     *    score >= 80) or a big creature (Forge evaluation >= 250). Any time, either turn.
     *  - He only attacks if the attack is lethal on that player, nothing there can block him
     *    (free damage), or he carries Nazgul Battle-Mace (annihilator triggers on attack).
     *    Applied by AttackHook after Forge's own attacker choice.
     */
    static final class CaptainPolicy {
        static final String NAME = "Captain America, First Avenger";
        static boolean enabled = false;

        static boolean isCaptain(Card c) { return c != null && NAME.equals(c.getName()); }

        /** He controls Captain America and Captain wears equipment that Throw can use. */
        static boolean canThrow(Player ai) {
            for (Card c : ai.getCardsIn(forge.game.zone.ZoneType.Battlefield)) {
                if (!isCaptain(c)) continue;
                for (Card e : c.getEquippedBy()) { if (!"Basilisk Collar".equals(e.getName())) return true; }
            }
            return false;
        }

        static boolean isThrow(SpellAbility sa) {
            return sa != null && isCaptain(sa.getHostCard()) && sa.isActivatedAbility()
                && sa.getApi() == ApiType.DealDamage;
        }

        /** Damage this throw assigns to an object (divided or not). */
        static int damageTo(SpellAbility sa, forge.game.GameObject o) {
            Integer v = sa.isDividedAsYouChoose() ? sa.getDividedValue(o) : null;
            if (v != null) return v;
            return forge.game.ability.AbilityUtils.calculateAmount(sa.getHostCard(), sa.getParamOrDefault("NumDmg", "0"), sa);
        }

        static final int HIGH_THREAT = 80;          // ThreatTracker score
        static final int HIGH_CREATURE_VALUE = 250; // Forge creature evaluation (a 3/3 vanilla is ~200)

        /** Why this opponent's permanent is worth a throw, or null. */
        static String highValue(Card c) {
            if (c.isCommander()) return "commander";
            if (c.isPlaneswalker()) return "planeswalker";
            // The user only ever threw at a commander's creature; other throws went at faces.
            // -Dforge.captain.throwcreatures=true restores the old threat / big-creature kills.
            if (!Boolean.getBoolean("forge.captain.throwcreatures")) return null;
            if (ThreatTracker.score(c) >= HIGH_THREAT) return "threat " + Math.round(ThreatTracker.score(c));
            if (c.isCreature() && forge.ai.ComputerUtilCard.evaluateCreature(c) >= HIGH_CREATURE_VALUE) return "big creature";
            return null;
        }

        /** The throw kills it (damage vs remaining toughness, or loyalty). */
        static boolean kills(SpellAbility sa, Card c) {
            int dmg = damageTo(sa, c);
            if (c.isPlaneswalker()) return dmg >= c.getCurrentLoyalty();
            if (!c.isCreature() || c.hasKeyword(forge.game.keyword.Keyword.INDESTRUCTIBLE)) return false;
            return dmg >= c.getNetToughness() - c.getDamage();
        }

        /**
         * Why the throw is allowed now, or null. Saved for eliminations (lethal on a player)
         * and for killing a high-value permanent.
         */
        static String throwReason(Player ai, SpellAbility sa) {
            forge.game.spellability.TargetChoices t = sa.getTargets();
            if (t == null) return null;
            for (Player p : t.getTargetPlayers()) {
                if (ai.isOpponentOf(p) && p.getLife() <= damageTo(sa, p)) return "lethal on " + p.getName();
            }
            for (Card c : t.getTargetCards()) {
                if (c.getController() == null || !ai.isOpponentOf(c.getController())) continue;
                String hv = highValue(c);
                if (hv != null && kills(sa, c)) return "kills " + c.getName() + " (" + hv + ")";
            }
            return null;
        }

        /**
         * Forge's own target choice didn't qualify: aim the whole throw at the most valuable
         * opponent permanent it can kill (highest Forge value + threat), if there is one.
         */
        static String retargetToBest(Player ai, SpellAbility sa) {
            int total = forge.game.ability.AbilityUtils.calculateAmount(sa.getHostCard(), sa.getParamOrDefault("NumDmg", "0"), sa);
            if (total <= 0) return null;
            Card best = null;
            double bestValue = -1;
            String bestWhy = null;
            for (Player opp : ai.getOpponents()) {
                for (Card c : opp.getCardsIn(forge.game.zone.ZoneType.Battlefield)) {
                    String hv = highValue(c);
                    if (hv == null || !sa.canTarget(c)) continue;
                    boolean dies = c.isPlaneswalker() ? total >= c.getCurrentLoyalty()
                        : c.isCreature() && !c.hasKeyword(forge.game.keyword.Keyword.INDESTRUCTIBLE) && total >= c.getNetToughness() - c.getDamage();
                    if (!dies) continue;
                    double value = (c.isCreature() ? forge.ai.ComputerUtilCard.evaluateCreature(c) : 200) + ThreatTracker.score(c)
                        + (c.isCommander() ? 150 : 0);
                    if (value > bestValue) { bestValue = value; best = c; bestWhy = hv; }
                }
            }
            if (best == null) return null;
            sa.resetTargets();
            sa.getTargets().add(best);
            if (sa.isDividedAsYouChoose()) sa.addDividedAllocation(best, total);
            return "kills " + best.getName() + " (" + bestWhy + ", re-aimed)";
        }

        /**
         * Play style, from -Dforge.captain.mode (for testing styles against each other):
         *   highvalue (default) - throws: eliminations + high-value kills; attacks: attackReason
         *   defend - throws only at creatures attacking him (+ eliminations); attacks: attackReason
         *   aggro  - throws as highvalue; attacks every combat
         *   face   - every throw at the lowest-life opponent; attacks: attackReason
         *   mix    - eliminations, high-value kills, attackers, then spare throws at the
         *            lowest-life opponent at an opponent's end step; attacks: attackReason or
         *            when no blocker can kill him
         *   pressure - face throws + attacks every combat
         */
        static final String MODE = System.getProperty("forge.captain.mode", "highvalue").toLowerCase();

        static int totalDamage(SpellAbility sa) {
            return forge.game.ability.AbilityUtils.calculateAmount(sa.getHostCard(), sa.getParamOrDefault("NumDmg", "0"), sa);
        }

        static void aimAll(SpellAbility sa, forge.game.GameEntity target, int total) {
            sa.resetTargets();
            sa.getTargets().add(target);
            if (sa.isDividedAsYouChoose()) sa.addDividedAllocation(target, total);
        }

        /** Any opponent this throw would finish off: aim at them. */
        static String retargetLethal(Player ai, SpellAbility sa) {
            int total = totalDamage(sa);
            List<Player> opps = new java.util.ArrayList<>();
            for (Player p : ai.getOpponents()) { if (!p.hasLost() && sa.canTarget(p)) opps.add(p); }
            opps.sort((a, b) -> Integer.compare(a.getLife(), b.getLife()));
            if (opps.isEmpty() || opps.get(0).getLife() > total) return null;
            if (!sa.isDividedAsYouChoose()) { aimAll(sa, opps.get(0), total); return "lethal on " + opps.get(0).getName(); }
            // The user's Excalibur splits: exactly the lethal amount on each player it finishes
            // (lowest life first, up to three targets), the rest at the next opponent's face
            // (Kaitlyn at 3 took 3, Augusta the other 9).
            int slots = Math.min(3, total), left = total;
            sa.resetTargets();
            StringBuilder why = new StringBuilder("lethal on ");
            List<Player> rest = new java.util.ArrayList<>();
            for (Player p : opps) {
                int need = Math.max(1, p.getLife());
                if (sa.getTargets().size() < slots && need <= left) {
                    sa.getTargets().add(p);
                    sa.addDividedAllocation(p, need);
                    left -= need;
                    why.append(sa.getTargets().size() > 1 ? ", " : "").append(p.getName());
                } else rest.add(p);
            }
            if (left > 0) {
                if (!rest.isEmpty() && sa.getTargets().size() < slots) {
                    // Overflow at the most threatening survivor.
                    Player next = rest.get(0);
                    for (Player p : rest) { if (HumanHooks.threat(p) > HumanHooks.threat(next)) next = p; }
                    sa.getTargets().add(next);
                    sa.addDividedAllocation(next, left);
                    why.append(" + ").append(left).append(" at ").append(next.getName());
                } else {
                    Player first = opps.get(0);
                    sa.addDividedAllocation(first, Math.max(1, first.getLife()) + left);
                }
            }
            return why.toString();
        }

        /** Best creature attacking him (or his planeswalkers) that the throw kills. */
        static String retargetAttacker(Player ai, SpellAbility sa) {
            forge.game.combat.Combat combat = ai.getGame().getCombat();
            forge.game.phase.PhaseType ph = ai.getGame().getPhaseHandler().getPhase();
            if (combat == null || combat.getAttackingPlayer() == null || !ai.isOpponentOf(combat.getAttackingPlayer())) return null;
            if (ph != forge.game.phase.PhaseType.COMBAT_DECLARE_ATTACKERS && ph != forge.game.phase.PhaseType.COMBAT_DECLARE_BLOCKERS) return null;
            int total = totalDamage(sa);
            Card best = null;
            int bestValue = -1;
            for (Card c : combat.getAttackers()) {
                if (combat.getDefenderPlayerByAttacker(c) != ai || !sa.canTarget(c)) continue;
                if (c.hasKeyword(forge.game.keyword.Keyword.INDESTRUCTIBLE) || total < c.getNetToughness() - c.getDamage()) continue;
                int v = forge.ai.ComputerUtilCard.evaluateCreature(c);
                if (v > bestValue) { bestValue = v; best = c; }
            }
            if (best == null) return null;
            aimAll(sa, best, total);
            return "at attacker " + best.getName();
        }

        /** Lowest-life opponent the throw can hit. */
        static String retargetFace(Player ai, SpellAbility sa) {
            Player best = null;
            for (Player p : ai.getOpponents()) {
                if (sa.canTarget(p) && (best == null || p.getLife() < best.getLife())) best = p;
            }
            if (best == null) return null;
            aimAll(sa, best, totalDamage(sa));
            return "face " + best.getName();
        }

        /** Whether (and why) to throw now under the current style; sets the targets. */
        /**
         * The throw's cost could unattach Cloudsteel Kirin (mana value 3) while we're low on
         * life and it's what keeps us in the game.
         */
        static boolean kirinAtRisk(Player ai, SpellAbility sa) {
            if (ai.getLife() > CatchAwareAttachAi.KIRIN_LIFE || totalDamage(sa) != 3) return false;
            for (Card e : sa.getHostCard().getEquippedBy()) { if ("Cloudsteel Kirin".equals(e.getName())) return true; }
            return false;
        }

        static String decideThrow(Player ai, SpellAbility sa, boolean forgeWilling) {
            int left = 0;
            for (Player p : ai.getOpponents()) { if (!p.hasLost()) left++; }
            // Keep Kirin on unless this throw can end the game (one opponent left).
            if (kirinAtRisk(ai, sa) && left > 1) return null;
            if (forgeWilling) {
                String why = throwReason(ai, sa);
                if (why != null && why.startsWith("lethal")) return why;
            }
            String why = retargetLethal(ai, sa);
            if (why != null) return why;
            if (!throwTimingOk(ai)) return null;
            // Sword of Kaldra is the exile engine for split throws: a non-lethal throw doesn't
            // unattach it (with Puresteel + Zirda the AI kept re-equipping it and throwing it for 4).
            if (throwsSwordOfKaldra(sa)) return null;
            why = decideNonLethal(ai, sa, forgeWilling);
            if (why != null && !protectionOk(ai, sa)) return null;
            return why;
        }

        /**
         * Throws that aren't lethal, by style. Split out of decideThrow so the timing and
         * protection checks apply to all of them.
         */
        static String decideNonLethal(Player ai, SpellAbility sa, boolean forgeWilling) {
            String why;
            switch (MODE) {
                case "defend":
                    return retargetAttacker(ai, sa);
                case "face":
                case "pressure":   // pressure = face throws + aggressive attacks (AttackHook)
                    return retargetFace(ai, sa);
                case "mix": {
                    if (forgeWilling && (why = throwReason(ai, sa)) != null) return why;
                    if ((why = retargetToBest(ai, sa)) != null) return why;
                    if ((why = retargetAttacker(ai, sa)) != null) return why;
                    Player active = ai.getGame().getPhaseHandler().getPlayerTurn();
                    if (ai.getGame().getPhaseHandler().getPhase() == forge.game.phase.PhaseType.END_OF_TURN
                            && active != null && ai.isOpponentOf(active)) {
                        return retargetFace(ai, sa);
                    }
                    return null;
                }
                default:   // highvalue, aggro
                    // Big gear goes at faces (the user's loop: every Excalibur throw hit a player);
                    // small gear is still used to kill valuable creatures.
                    // A face throw that finishes someone within two throws beats a board wipe;
                    // otherwise kill two or three creatures at once (the user's split throws).
                    if (totalDamage(sa) >= loopMinDamage(ai) && loopFinishTarget(ai, sa) != null
                            && (why = loopThrow(ai, sa)) != null) return why;
                    if (splitWindow(ai, sa) && (why = splitKill(ai, sa)) != null) return why;
                    if (totalDamage(sa) >= loopMinDamage(ai) && (why = loopThrow(ai, sa)) != null) return why;
                    if (forgeWilling && (why = throwReason(ai, sa)) != null) return why;
                    if ((why = retargetToBest(ai, sa)) != null) return why;
                    return loopThrow(ai, sa);
            }
        }

        /** No untapped blocker there could kill him. */
        static boolean survivesAnyBlock(Card cap, Player def) {
            if (cap.hasKeyword(forge.game.keyword.Keyword.INDESTRUCTIBLE)) return true;
            int toughnessLeft = cap.getNetToughness() - cap.getDamage();
            for (Card b : def.getCardsIn(forge.game.zone.ZoneType.Battlefield)) {
                if (!b.isCreature() || b.isTapped() || !forge.game.combat.CombatUtil.canBlock(cap, b)) continue;
                if (b.getNetCombatDamage() >= toughnessLeft || b.hasKeyword(forge.game.keyword.Keyword.DEATHTOUCH)) return false;
            }
            return true;
        }

        /**
         * Voltron plan: don't cast Captain America until he can be protected the turn he lands,
         * i.e. protective equipment is on our battlefield (Catch attaches it at beginning of
         * combat), Codsworth is out (commanders have ward {2}), or Flawless Maneuver is in hand.
         * From round 7 on he is cast anyway. Returns why to wait, or null to cast.
         */
        static String castWaitReason(Player ai) {
            int players = Math.max(1, ai.getGame().getRegisteredPlayers().size());
            // From round CAST_ANYWAY_ROUND on he is cast without protection (the user: turns 4-6,
            // average 5; the AI waited until round 7 and averaged 5.9).
            if (ai.getGame().getPhaseHandler().getTurn() >= (CAST_ANYWAY_ROUND - 1) * players) return null;
            for (Card c : ai.getCardsIn(forge.game.zone.ZoneType.Battlefield)) {
                if (CatchAwareAttachAi.HEXPROOF.contains(c.getName()) || CatchAwareAttachAi.OTHER_PROTECTION.contains(c.getName())) return null;
                if ("Codsworth, Handy Helper".equals(c.getName())) return null;
            }
            for (Card c : ai.getCardsIn(forge.game.zone.ZoneType.Hand)) {
                if ("Flawless Maneuver".equals(c.getName())) return null;
            }
            // Creatures that can be sacrificed to make the team indestructible (SavingPumpAllAi
            // uses them): the user cast Captain America turn 4 with Spectacular Spider-Man out.
            for (Card c : ai.getCardsIn(forge.game.zone.ZoneType.Battlefield)) {
                if (SACRIFICE_PROTECTION.contains(c.getName())) return null;
            }
            return "no protection ready";
        }

        static final int CAST_ANYWAY_ROUND = Integer.getInteger("forge.captain.castround", 6);

        static final List<String> SACRIFICE_PROTECTION = Arrays.asList("Spectacular Spider-Man", "Boromir, Warden of the Tower");

        /** Commander damage still needed for a commander-damage kill (21 in Commander). */
        static int commanderDamageLeft(Card commander, Player def) {
            return 21 - def.getCommanderDamage(commander);
        }

        static final int LOOP_MIN_DAMAGE = 5;

        /**
         * Smallest gear worth looping. With Puresteel Paladin (metalcraft: equip {0}) and Zirda
         * (throw {1}) a loop costs 1 mana, so mana value 3 gear is worth it too.
         */
        static int loopMinDamage(Player ai) {
            return zirdaOut(ai) && puresteelActive(ai) ? 3 : LOOP_MIN_DAMAGE;
        }

        /** Puresteel Paladin out with metalcraft (3+ artifacts): our Equipment have equip {0}. */
        static boolean puresteelActive(Player ai) {
            boolean paladin = false;
            int artifacts = 0;
            for (Card c : ai.getCardsIn(forge.game.zone.ZoneType.Battlefield)) {
                if ("Puresteel Paladin".equals(c.getName())) paladin = true;
                if (c.isArtifact()) artifacts++;
            }
            return paladin && artifacts >= 3;
        }

        /** Mana an activated ability costs us: Puresteel makes equip {0}; Zirda takes {2} off (not below 1). */
        static int abilityCost(Player ai, SpellAbility sa, int base) {
            if (sa.isEquip() && puresteelActive(ai)) return 0;
            if (base > 0 && zirdaOut(ai)) return Math.max(1, base - 2);
            return base;
        }

        static int throwCost(Player ai) { return zirdaOut(ai) ? 1 : 3; }

        /**
         * Split throw as a board wipe (the user split throws over up to three creatures): the
         * set of up to min(3, damage) opposing creatures the damage kills outright that is worth
         * the most (Forge value + threat; commanders +150, tokens count half). Used when it kills
         * at least two, or a commander plus something; leftover damage goes to the lowest-life
         * opponent's face if a target slot is left, else onto the best creature.
         */
        static final boolean SPLIT = !"false".equalsIgnoreCase(System.getProperty("forge.captain.split", "true"));

        /**
         * -Dforge.captain.throwtiming=main: non-lethal throws only in our main phases, an
         * opponent's declare attackers/blockers (defending) or the end step before our turn. The
         * user threw 44% in main 1, 41% in main 2 and never in upkeep; the AI threw 38% in its
         * upkeep (Forge's damage AI likes upkeep for commander kills). Default "main" since
         * 2026-10-17 (overnight, 760 games each: closest to the user's throws, 22.2% wins vs
         * 20.3%); "any" = the old behaviour.
         */
        static final boolean MAIN_TIMING = "main".equalsIgnoreCase(System.getProperty("forge.captain.throwtiming", "main"));

        static boolean throwTimingOk(Player ai) {
            if (!MAIN_TIMING) return true;
            forge.game.phase.PhaseHandler ph = ai.getGame().getPhaseHandler();
            if (ph.getPlayerTurn() == ai && (ph.getPhase() == forge.game.phase.PhaseType.MAIN1
                    || ph.getPhase() == forge.game.phase.PhaseType.MAIN2)) return true;
            return splitWindow(ai);
        }

        /**
         * -Dforge.captain.protthrow: whether a throw may unattach protection (hexproof/shroud/
         * indestructible gear) when it isn't lethal.
         *   legacy - before 2026-10-17: only the face loop and Catch window refuse it
         *   never - never
         *   user (default since 2026-10-17) - the user's rule (2026-10-16: threw Whispersilk
         *          Cloak at Tidus in main 2 while Champion's Helm kept him hexproof; Catch put the
         *          Cloak back next combat): only in our own main phase, and only while other
         *          protection stays on him.
         */
        static final String PROT_THROW = System.getProperty("forge.captain.protthrow", "user").toLowerCase();

        static boolean protectionOk(Player ai, SpellAbility sa) {
            if ("legacy".equals(PROT_THROW) || !throwsProtection(sa)) return true;
            if ("never".equals(PROT_THROW)) return false;
            forge.game.phase.PhaseHandler ph = ai.getGame().getPhaseHandler();
            if (ph.getPlayerTurn() != ai || (ph.getPhase() != forge.game.phase.PhaseType.MAIN1
                    && ph.getPhase() != forge.game.phase.PhaseType.MAIN2)) return false;
            // Which piece of that mana value the cost unattaches isn't ours to pick, so assume a
            // protective one goes: protection stays if another piece remains (Champion's Helm and
            // Whispersilk Cloak are both mana value 3, as in the user's game).
            int dmg = totalDamage(sa), sameValue = 0, other = 0;
            for (Card e : sa.getHostCard().getEquippedBy()) {
                if (!CatchAwareAttachAi.HEXPROOF.contains(e.getName()) && !CatchAwareAttachAi.OTHER_PROTECTION.contains(e.getName())) continue;
                if (e.getCMC() == dmg) sameValue++; else other++;
            }
            return other + sameValue - 1 >= 1;
        }

        static boolean grantsDeathtouch(Card eq) {
            String t = eq.getOracleText();
            return t != null && t.toLowerCase().contains("deathtouch");
        }

        /**
         * Captain America still has deathtouch after this throw's cost (it unattaches gear with
         * mana value = the damage). The user's main wipe: Nazgul Battle-Mace (deathtouch, MV 5)
         * stays on and an MV-3 sword is thrown as 1 damage each at three creatures.
         */
        static boolean keepsDeathtouch(SpellAbility sa) {
            Card cap = sa.getHostCard();
            if (cap == null || !cap.hasKeyword(forge.game.keyword.Keyword.DEATHTOUCH)) return false;
            int dmg = totalDamage(sa);
            boolean fromOtherGear = false, thrownPieceClean = false, anyGearGrants = false;
            for (Card e : cap.getEquippedBy()) {
                boolean dt = grantsDeathtouch(e);
                if (dt) anyGearGrants = true;
                if (dt && e.getCMC() != dmg) fromOtherGear = true;
                if (!dt && e.getCMC() == dmg) thrownPieceClean = true;
            }
            return !anyGearGrants || fromOtherGear || thrownPieceClean;
        }

        /**
         * Sword of Kaldra stays on him through this throw ("whenever equipped creature deals
         * damage to a creature, exile that creature"): the throw is damage from Captain America,
         * so 1 damage exiles each creature hit - indestructible ones and Angels that would come
         * back included. The cost unattaches gear of the throw's mana value, so a 4-damage throw
         * might take the Sword itself.
         */
        static boolean keepsSwordOfKaldra(SpellAbility sa) {
            if (totalDamage(sa) == 4) return false;
            for (Card e : sa.getHostCard().getEquippedBy()) { if ("Sword of Kaldra".equals(e.getName())) return true; }
            return false;
        }

        /** This throw's cost could unattach Sword of Kaldra (the throw is 4 damage and he wears it). */
        static boolean throwsSwordOfKaldra(SpellAbility sa) {
            if (totalDamage(sa) != 4) return false;
            for (Card e : sa.getHostCard().getEquippedBy()) { if ("Sword of Kaldra".equals(e.getName())) return true; }
            return false;
        }

        /** Damage this throw needs to kill c (1 with deathtouch). */
        static int killNeed(Card c, boolean deathtouch) {
            int need = c.getNetToughness() - c.getDamage();
            return deathtouch && need > 0 ? 1 : need;
        }

        static String splitKill(Player ai, SpellAbility sa) {
            if (!SPLIT || !sa.isDividedAsYouChoose()) return null;
            int dmg = totalDamage(sa);
            int slots = Math.min(3, dmg);
            if (slots < 2) return null;
            final boolean exile = keepsSwordOfKaldra(sa);
            final boolean dt = keepsDeathtouch(sa) || exile;   // either way 1 damage removes it
            List<Card> cands = new java.util.ArrayList<>();
            for (Player opp : ai.getOpponents()) {
                for (Card c : opp.getCreaturesInPlay()) {
                    if ((!exile && c.hasKeyword(forge.game.keyword.Keyword.INDESTRUCTIBLE)) || !sa.canTarget(c)) continue;
                    int need = killNeed(c, dt);
                    if (need > 0 && need <= dmg) cands.add(c);
                }
            }
            // Face slot (the user's Basilisk Collar + Excalibur throws, 2026-10-18): a big throw
            // kills the best one or two creatures and puts the rest on one player's face, rather
            // than spending every target on creatures and dumping the leftover on one of them.
            Player face = FACE_SLOT && dmg >= FACE_SLOT_MIN ? faceTarget(ai, sa) : null;
            int minSize = face != null ? 1 : 2, maxSize = face != null ? slots - 1 : slots;
            int budget = face != null ? dmg - 1 : dmg;
            if (cands.size() < minSize) return null;
            final int myLife = ai.getLife();
            cands.sort((a, b) -> Double.compare(splitValue(b, myLife), splitValue(a, myLife)));
            if (cands.size() > 12) cands = cands.subList(0, 12);
            Card[] best = bestKillSet(cands, dt, budget, minSize, maxSize, myLife);
            if (best == null) return null;
            // Small tokens alone aren't worth unattaching gear for; with deathtouch the user did
            // wipe three 3/3 Robot tokens (one mana a throw with Zirda + Puresteel).
            int real = 0, bigTokens = 0;
            for (Card c : best) { if (!c.isToken()) real++; else if (c.getNetPower() >= 2) bigTokens++; }
            if (real == 0 && !(dt && bigTokens == best.length)) return null;
            int left = dmg;
            sa.resetTargets();
            StringBuilder names = new StringBuilder();
            for (Card c : best) {
                int need = killNeed(c, dt);
                sa.getTargets().add(c);
                sa.addDividedAllocation(c, need);
                left -= need;
                names.append(names.length() > 0 ? ", " : "").append(c.getName());
            }
            if (left > 0) {
                if (face == null && best.length < slots) {
                    for (Player p : ai.getOpponents()) {
                        if (!p.hasLost() && sa.canTarget(p) && (face == null || p.getLife() < face.getLife())) face = p;
                    }
                }
                if (face != null) {
                    sa.getTargets().add(face);
                    sa.addDividedAllocation(face, left);
                    names.append(" + ").append(left).append(" at ").append(face.getName());
                } else {
                    sa.addDividedAllocation(best[0], killNeed(best[0], dt) + left);
                }
            }
            return (exile ? "split exile (Sword of Kaldra) " : dt ? "split kill (deathtouch) " : "split kill ") + names;
        }

        /**
         * -Dforge.captain.faceslot=true: throws of FACE_SLOT_MIN+ damage keep one target for a
         * player's face (see splitKill). Default false until tested.
         */
        static final boolean FACE_SLOT = "true".equalsIgnoreCase(System.getProperty("forge.captain.faceslot", "false"));
        static final int FACE_SLOT_MIN = 6;

        /** Face for a split throw's leftover: the most threatening opponent it can hit. */
        static Player faceTarget(Player ai, SpellAbility sa) {
            Player best = null;
            double bestThreat = -1;
            for (Player p : ai.getOpponents()) {
                if (p.hasLost() || !sa.canTarget(p)) continue;
                double t = HumanHooks.threat(p);
                if (t > bestThreat || (t == bestThreat && best != null && p.getLife() < best.getLife())) { bestThreat = t; best = p; }
            }
            return best;
        }

        /** Most valuable set of minSize..maxSize (at most 3) creatures killable within budget damage. */
        static Card[] bestKillSet(List<Card> cands, boolean dt, int budget, int minSize, int maxSize, int myLife) {
            int n = cands.size();
            Card[] best = null;
            double bestValue = 0;
            for (int i = 0; i < n; i++) {
                for (int j = i; j < n; j++) {
                    for (int k = j; k < n; k++) {
                        // i == j: a single card; j == k: a pair (k loop runs once for it).
                        Card[] set;
                        if (i == j) { if (k != j) break; set = new Card[] { cands.get(i) }; }
                        else if (j == k) set = new Card[] { cands.get(i), cands.get(j) };
                        else set = new Card[] { cands.get(i), cands.get(j), cands.get(k) };
                        if (set.length < minSize || set.length > maxSize) continue;
                        int need = 0;
                        double value = 0;
                        for (Card c : set) { need += killNeed(c, dt); value += splitValue(c, myLife); }
                        if (need <= budget && value > bestValue) { bestValue = value; best = set; }
                    }
                }
            }
            return best;
        }

        /**
         * Worth of killing c in a split throw. Low on life (the user at 1-2 life killed Mr. House
         * and Ingenious Artillerist, the creatures that could finish him), creatures that can hurt
         * us count extra.
         */
        static double splitValue(Card c, int myLife) {
            double v = forge.ai.ComputerUtilCard.evaluateCreature(c) + ThreatTracker.score(c) + (c.isCommander() ? 150 : 0);
            if (myLife <= 10) v += 40 * Math.min(10, Math.max(0, c.getNetPower())) + ThreatTracker.score(c);
            return c.isToken() ? v / 2 : v;
        }

        /** Throw would unattach a piece of protection (same mana value as this throw). */
        static boolean throwsProtection(SpellAbility sa) {
            int dmg = totalDamage(sa);
            for (Card e : sa.getHostCard().getEquippedBy()) {
                if (e.getCMC() == dmg && (CatchAwareAttachAi.HEXPROOF.contains(e.getName())
                        || CatchAwareAttachAi.OTHER_PROTECTION.contains(e.getName()))) return true;
            }
            return false;
        }

        /**
         * Our main phases with an empty stack, or the end step right before our turn. The user
         * looped Excalibur in the main phase before combat and attacked with the rest afterwards.
         */
        static boolean loopWindow(Player ai) {
            forge.game.phase.PhaseHandler ph = ai.getGame().getPhaseHandler();
            if (!ai.getGame().getStack().isEmpty()) return false;
            if (ph.getPlayerTurn() == ai) {
                if (ph.getPhase() == forge.game.phase.PhaseType.MAIN2) return true;
                // Before combat only with Zirda (throw + re-equip {1} each): without it the throw
                // would strip his +10 before the attack (attack rate fell from 66% to 50%).
                return ph.getPhase() == forge.game.phase.PhaseType.MAIN1 && zirdaOut(ai);
            }
            return ph.getPhase() == forge.game.phase.PhaseType.END_OF_TURN && ph.getNextTurn() == ai;
        }

        static boolean zirdaOut(Player ai) {
            for (Card c : ai.getCardsIn(forge.game.zone.ZoneType.Battlefield)) { if ("Zirda, the Dawnwaker".equals(c.getName())) return true; }
            return false;
        }

        /**
         * Throw loop (learned from the user, who won two games with it): a big piece of gear
         * (Excalibur, mana value 12) thrown at an opponent's face is 12 damage, and with Zirda
         * the throw and the re-equip cost {1} each. In our second main phase (or the end step
         * before our turn - Catch re-attaches it at our next combat), throw high mana-value,
         * non-protective equipment at a player: one it finishes off in two throws (lowest life
         * first), else the biggest threat. Re-equipping in main 2 is allowed by
         * CatchAwareAttachAi.loopEquip, so with mana to spare it repeats.
         */
        /** Lowest-life opponent this throw finishes within two throws, or null. */
        static Player loopFinishTarget(Player ai, SpellAbility sa) {
            int dmg = totalDamage(sa);
            Player target = null;
            for (Player p : ai.getOpponents()) {
                if (p.hasLost() || !sa.canTarget(p) || p.getLife() > 2 * dmg) continue;
                if (target == null || p.getLife() < target.getLife()) target = p;
            }
            return target;
        }

        /**
         * When a split throw may go off: the loop window, or an opponent's combat (their
         * attackers are on the table) - not our first main phase, where it would strip the gear
         * before our own attack.
         */
        static boolean splitWindow(Player ai) {
            if (loopWindow(ai)) return true;
            forge.game.phase.PhaseHandler ph = ai.getGame().getPhaseHandler();
            return ph.getPlayerTurn() != ai && ph.getPlayerTurn() != null && ai.isOpponentOf(ph.getPlayerTurn())
                && (ph.getPhase() == forge.game.phase.PhaseType.COMBAT_DECLARE_ATTACKERS
                    || ph.getPhase() == forge.game.phase.PhaseType.COMBAT_DECLARE_BLOCKERS);
        }

        /** splitWindow, plus our first main phase when Catch gives the thrown piece back for free. */
        static boolean splitWindow(Player ai, SpellAbility sa) {
            return splitWindow(ai) || catchReturnsIt(ai, sa);
        }

        /**
         * Our first main phase, and Catch (beginning of combat: attach one Equipment to him) will
         * put the thrown piece straight back on: no other unattached Equipment of ours is waiting
         * for it. The user threw before combat for that reason - it clears blockers and costs the
         * attack nothing. Not protection (he'd be bare until combat).
         */
        static boolean catchReturnsIt(Player ai, SpellAbility sa) {
            forge.game.phase.PhaseHandler ph = ai.getGame().getPhaseHandler();
            if (ph.getPlayerTurn() != ai || ph.getPhase() != forge.game.phase.PhaseType.MAIN1
                    || !ai.getGame().getStack().isEmpty() || throwsProtection(sa)) return false;
            for (Card c : ai.getCardsIn(forge.game.zone.ZoneType.Battlefield)) {
                if (c.isEquipment() && !c.isAttachedToEntity()) return false;
            }
            return true;
        }

        static String loopThrow(Player ai, SpellAbility sa) {
            int dmg = totalDamage(sa);
            if (dmg < loopMinDamage(ai) || !loopWindow(ai) || throwsProtection(sa)) return null;
            Player target = loopFinishTarget(ai, sa);
            if (target == null) {
                double best = -1;
                for (Player p : ai.getOpponents()) {
                    if (p.hasLost() || !sa.canTarget(p)) continue;
                    double t = HumanHooks.threat(p);
                    if (t > best) { best = t; target = p; }
                }
            }
            if (target == null) return null;
            aimAll(sa, target, dmg);
            return "loop throw " + dmg + " at " + target.getName();
        }

        static boolean wearsProtection(Card cap) {
            for (Card e : cap.getEquippedBy()) {
                if (CatchAwareAttachAi.HEXPROOF.contains(e.getName()) || CatchAwareAttachAi.OTHER_PROTECTION.contains(e.getName())) return true;
            }
            return false;
        }

        /** Keep Captain America in this attack? */
        static String attackReason(forge.game.combat.Combat combat, Card cap) {
            Player def = combat.getDefenderPlayerByAttacker(cap);
            if (def == null) return "attacking a planeswalker/battle";
            int power = 0;
            for (Card a : combat.getAttackersOf(def)) power += Math.max(0, a.getNetCombatDamage());
            if (power >= def.getLife()) return "lethal on " + def.getName();
            if (cap.getNetCombatDamage() >= commanderDamageLeft(cap, def)) return "commander-damage lethal on " + def.getName();
            for (Card e : cap.getEquippedBy()) { if (e.getName().startsWith("Nazg")) return "annihilator (Battle-Mace)"; }
            boolean blockable = false;
            for (Card b : def.getCardsIn(forge.game.zone.ZoneType.Battlefield)) {
                if (b.isCreature() && !b.isTapped() && forge.game.combat.CombatUtil.canBlock(cap, b)) { blockable = true; break; }
            }
            if (!blockable) return "no blockers - free damage";
            // The user attacked whenever nothing there could kill him, suited up or not (a bare
            // 4/4 into Doom on turn 5); the AI only did so with protection on (attacks 50% vs 67%).
            if (survivesAnyBlock(cap, def)) return wearsProtection(cap) ? "suited up - survives any block" : "survives any block";
            return null;
        }
    }

    /**
     * Codsworth, Handy Helper and Weapons Vendor attach equipment through a "Pump" ability
     * with an Attach sub-ability; Forge's pump AI sees nothing to pump, so Codsworth's was
     * never used (0 times in 359 games) and Weapons Vendor's targets were arbitrary. For
     * these two cards: attach to Captain America if he is out (else the best creature),
     * choosing hexproof gear first if he has none, otherwise the strongest unattached piece,
     * otherwise one from a weaker creature. Never moves what he already wears.
     */
    static final class EquipHelper {
        static final List<String> HOSTS = Arrays.asList("Codsworth, Handy Helper", "Weapons Vendor");

        static boolean applies(SpellAbility sa) {
            return sa != null && sa.getHostCard() != null && HOSTS.contains(sa.getHostCard().getName())
                && sa.getApi() == ApiType.Pump && sa.getSubAbility() != null && sa.getSubAbility().getApi() == ApiType.Attach;
        }

        /** Sets the equipment target and the creature target; returns a description or null. */
        static String choose(Player ai, SpellAbility sa) {
            forge.game.spellability.AbilitySub sub = sa.getSubAbility();
            Card dest = null;
            for (Card c : ai.getCardsIn(forge.game.zone.ZoneType.Battlefield)) {
                if (CaptainPolicy.isCaptain(c) && sub.canTarget(c)) dest = c;
            }
            if (dest == null) {
                int best = -1;
                for (Card c : ai.getCreaturesInPlay()) {
                    if (c == sa.getHostCard()) continue;   // Codsworth taps to do this; no point equipping himself
                    int v = forge.ai.ComputerUtilCard.evaluateCreature(c);
                    if (v > best && sub.canTarget(c)) { best = v; dest = c; }
                }
            }
            if (dest == null) return null;
            boolean destHexproof = false;
            for (Card e : dest.getEquippedBy()) { if (CatchAwareAttachAi.HEXPROOF.contains(e.getName())) destHexproof = true; }
            int destValue = forge.ai.ComputerUtilCard.evaluateCreature(dest);
            List<Card> free = new java.util.ArrayList<>(), fromWeaker = new java.util.ArrayList<>();
            for (Card e : ai.getCardsIn(forge.game.zone.ZoneType.Battlefield)) {
                if (!e.isEquipment() || e.getEquipping() == dest || !sa.canTarget(e)) continue;
                if ("Skullclamp".equals(e.getName()) && !CatchAwareAttachAi.goodClampTarget(dest)) continue;   // clamp only where it pays off
                Card on = e.getEquipping();
                if (on == null) free.add(e);
                else if (!CaptainPolicy.isCaptain(on) && forge.ai.ComputerUtilCard.evaluateCreature(on) < destValue) fromWeaker.add(e);
            }
            Card pick = null;
            if (CaptainPolicy.isCaptain(dest) && !destHexproof) {
                List<Card> all = new java.util.ArrayList<>(free);
                all.addAll(fromWeaker);
                pick = CatchAwareAttachAi.pick(CatchAwareAttachAi.HEXPROOF, all);
            }
            if (pick == null) pick = strongest(free);
            if (pick == null) pick = strongest(fromWeaker);
            if (pick == null) return null;
            sa.resetTargets();
            sa.getTargets().add(pick);
            sub.resetTargets();
            sub.getTargets().add(dest);
            return pick.getName() + " -> " + dest.getName();
        }

        static Card strongest(List<Card> list) {
            Card best = null;
            for (Card c : list) {
                if ("Skullclamp".equals(c.getName()) && best != null) continue;
                if (best == null || "Skullclamp".equals(best.getName()) || c.getCMC() > best.getCMC()) best = c;
            }
            return best;
        }
    }

    /**
     * Chandra's Ignition (2026-10-21, added to Captain America v7): "target creature you control
     * deals damage equal to its power to each other creature and each opponent". Forge scripts it
     * as a Pump with the generic PowerDmg hint, which knows nothing about it being a one-sided wipe
     * or about our own board - in an equipment deck it could fire a 2-power creature, or wipe our
     * own creatures for nothing.
     *
     * For every creature it could target this works out what dies (deathtouch kills everything it
     * touches, indestructible survives), on both sides, and the damage to each opponent; then casts
     * only for a clearly good result - a kill on a player, or their losses (plus the face damage)
     * well ahead of ours. Like the Blasphemous Act rule it holds when our own Captain America would
     * die, unless it kills a player or wipes a board of theirs. Logs IGNITIONAI.
     */
    static final class IgnitionAi {
        static boolean applies(SpellAbility sa) {
            return sa.getHostCard() != null && "Chandra's Ignition".equals(sa.getHostCard().getName())
                && sa.usesTargeting();
        }

        static forge.ai.AiAbilityDecision decide(Player ai, SpellAbility sa) {
            Card best = null;
            int bestScore = Integer.MIN_VALUE;
            String bestWhy = "";
            int opps = ai.getOpponents().size();
            for (Card t : ai.getCreaturesInPlay()) {
                if (!sa.canTarget(t)) continue;
                int p = t.getNetPower();
                if (p <= 0) continue;
                boolean dt = t.hasKeyword(forge.game.keyword.Keyword.DEATHTOUCH);
                int theirsVal = 0, oursVal = 0, theirsDead = 0, oursDead = 0;
                boolean capDies = false;
                for (Card c : ai.getGame().getCardsIn(forge.game.zone.ZoneType.Battlefield)) {
                    if (c == t || !c.isCreature()) continue;
                    if (c.hasKeyword(forge.game.keyword.Keyword.INDESTRUCTIBLE)) continue;
                    boolean dies = dt || c.getNetToughness() - c.getDamage() <= p;
                    if (!dies) continue;
                    int v = forge.ai.ComputerUtilCard.evaluateCreature(c);
                    if (c.getController() == ai) {
                        oursVal += v; oursDead++;
                        if (CaptainPolicy.isCaptain(c)) capDies = true;
                    } else {
                        theirsVal += v; theirsDead++;
                    }
                }
                boolean killsPlayer = false;
                for (Player o : ai.getOpponents()) if (o.getLife() <= p && o.canLoseLife()) killsPlayer = true;
                // Face damage to every opponent counts: ~8 evaluation points per point of damage.
                int score = theirsVal - oursVal + p * opps * 8 + (killsPlayer ? 1000 : 0);
                if (capDies && !killsPlayer && theirsDead < 4) score -= 2000;   // keep our Captain alive
                if (score > bestScore) {
                    bestScore = score;
                    best = t;
                    bestWhy = t.getName() + " (" + p + (dt ? ", deathtouch" : "") + "): kills " + theirsDead
                        + " of theirs / " + oursDead + " of ours, " + p + " to each of " + opps + " opponents"
                        + (killsPlayer ? ", KILLS A PLAYER" : "") + (capDies ? ", Cap dies" : "");
                }
            }
            // Worth a 5-mana card: a kill, or a clear swing (~ two decent creatures, or 5+ to each of three).
            if (best == null || bestScore < 220) {
                System.out.println("IGNITIONAI " + ai.getName() + ": held" + (best == null ? " (no creature to fire it)" : " - best was " + bestWhy + ", score " + bestScore));
                return new forge.ai.AiAbilityDecision(0, forge.ai.AiPlayDecision.CantPlayAi);
            }
            sa.resetTargets();
            sa.getTargets().add(best);
            System.out.println("IGNITIONAI " + ai.getName() + ": cast at " + bestWhy + ", score " + bestScore);
            return new forge.ai.AiAbilityDecision(100, forge.ai.AiPlayDecision.WillPlay);
        }
    }

    public static class EquipHelperPumpAi extends forge.ai.ability.PumpAi {
        static boolean isRikku(SpellAbility sa) {
            return AbilityHelpers.enabled && AbilityHelpers.host(sa, "Rikku, Resourceful Guardian") && sa.getSubAbility() != null;
        }

        @Override
        protected boolean checkPhaseRestrictions(Player ai, SpellAbility sa, forge.game.phase.PhaseHandler ph) {
            return EquipHelper.applies(sa) || isRikku(sa) || DeathSave.applies(sa) || IgnitionAi.applies(sa) || super.checkPhaseRestrictions(ai, sa, ph);
        }

        @Override
        protected boolean checkPhaseRestrictions(Player ai, SpellAbility sa, forge.game.phase.PhaseHandler ph, String logic) {
            return EquipHelper.applies(sa) || isRikku(sa) || DeathSave.applies(sa) || IgnitionAi.applies(sa) || super.checkPhaseRestrictions(ai, sa, ph, logic);
        }

        @Override
        protected forge.ai.AiAbilityDecision checkApiLogic(Player ai, SpellAbility sa) {
            if (IgnitionAi.applies(sa)) return IgnitionAi.decide(ai, sa);   // Chandra's Ignition
            if (DeathSave.applies(sa)) return DeathSave.decide(ai, sa);   // Undying Evil
            if (isRikku(sa)) {
                String what = AbilityHelpers.rikkuChoose(ai, sa);
                return what == null ? new forge.ai.AiAbilityDecision(0, forge.ai.AiPlayDecision.CantPlayAi) : AbilityHelpers.yes(what);
            }
            if (!EquipHelper.applies(sa)) return super.checkApiLogic(ai, sa);
            String what = EquipHelper.choose(ai, sa);
            if (what == null) return new forge.ai.AiAbilityDecision(0, forge.ai.AiPlayDecision.CantPlayAi);
            System.out.println("EQUIPAI " + sa.getHostCard().getName() + ": " + what);
            return new forge.ai.AiAbilityDecision(100, forge.ai.AiPlayDecision.WillPlay);
        }

        @Override
        protected forge.ai.AiAbilityDecision doTriggerNoCost(Player ai, SpellAbility sa, boolean mandatory) {
            if (!EquipHelper.applies(sa)) return super.doTriggerNoCost(ai, sa, mandatory);
            String what = EquipHelper.choose(ai, sa);
            if (what == null) return mandatory ? super.doTriggerNoCost(ai, sa, true) : new forge.ai.AiAbilityDecision(0, forge.ai.AiPlayDecision.CantPlayAi);
            System.out.println("EQUIPAI " + sa.getHostCard().getName() + ": " + what);
            return new forge.ai.AiAbilityDecision(100, forge.ai.AiPlayDecision.WillPlay);
        }

        @Override
        public boolean confirmAction(Player ai, SpellAbility sa, forge.game.player.PlayerActionConfirmMode mode, String message,
                                     java.util.Map<String, Object> params) {
            return super.confirmAction(ai, sa, mode, message, params);
        }
    }

    /** Damage AI with Captain America's Throw policy (other damage effects unchanged). */
    public static class ThrowAwareDamageAi extends forge.ai.ability.DamageDealAi {
        @Override
        protected forge.ai.AiAbilityDecision canPlay(Player ai, SpellAbility sa) {
            forge.ai.AiAbilityDecision d = super.canPlay(ai, sa);
            if (!CaptainPolicy.isThrow(sa) && sa.isSpell() && !sa.isDividedAsYouChoose()) {
                // Burn aimed at a token (Galvanic Blast on a Thopter): a real creature it kills instead.
                int dmg = CaptainPolicy.totalDamage(sa);
                return ThreatRetarget.avoidTokens(ai, sa, d, c -> c.isPlaneswalker() ? dmg >= c.getCurrentLoyalty()
                    : !c.hasKeyword(forge.game.keyword.Keyword.INDESTRUCTIBLE) && dmg >= c.getNetToughness() - c.getDamage());
            }
            if (!CaptainPolicy.enabled || !CaptainPolicy.isThrow(sa)) return d;
            boolean forgeWilling = d.willingToPlay();
            // Styles that pick their own targets may throw even when Forge's damage AI wouldn't;
            // so may the throw loop (Forge's damage AI often won't hit a healthy player's face).
            boolean ownTargets = !"highvalue".equals(CaptainPolicy.MODE) && !"aggro".equals(CaptainPolicy.MODE);
            // (Split kills need at least 2 damage; decideThrow says no when nothing qualifies.)
            if (!forgeWilling && !ownTargets && !CaptainPolicy.splitWindow(ai, sa)) return d;
            if (!forgeWilling && !ownTargets && CaptainPolicy.totalDamage(sa) < 2) return d;
            String why = CaptainPolicy.decideThrow(ai, sa, forgeWilling);
            if (why == null) return new forge.ai.AiAbilityDecision(0, forge.ai.AiPlayDecision.CantPlayAi);
            if (!forgeWilling) d = new forge.ai.AiAbilityDecision(100, forge.ai.AiPlayDecision.WillPlay);
            StringBuilder tg = new StringBuilder();
            for (Card c : sa.getTargets().getTargetCards()) tg.append(tg.length() > 0 ? ", " : "").append(c.getName());
            for (Player p : sa.getTargets().getTargetPlayers()) tg.append(tg.length() > 0 ? ", " : "").append(p.getName());
            System.out.println("THROWAI " + tg + " (" + why + ")");
            return d;
        }
    }

    /**
     * "deploy": permanents Forge's AI kept in hand although the mana was there. In 80 games
     * Tidus discarded ~30 spells to hand size, and held Hardened Scales (76% of end-of-turn
     * checks), Blitzball Stadium (73%), Wildwood Scourge (65%) and Incubation Druid (50%).
     *  - UNDERVALUED cards are cast in our own main phase when affordable. X costs: Wildwood
     *    Scourge takes all spare mana (at least 2, it's a 0/0), Blitzball Stadium supports up to
     *    one counter per creature we have.
     *  - Any other permanent spell is cast in our second main phase instead of being discarded
     *    when the hand is over its maximum size (not X spells; Forge's legend / affordability
     *    / targeting refusals stand).
     * Logged as "DEPLOYAI ...". Works through the Holding* permanent AIs (the "counter" patch).
     */
    static final class Deploy {
        static boolean enabled = false;
        static final List<String> UNDERVALUED = Arrays.asList("Hardened Scales", "Incubation Druid", "Wildwood Scourge",
            "Blitzball Stadium");

        static boolean myMain(Player ai) {
            forge.game.phase.PhaseHandler ph = ai.getGame().getPhaseHandler();
            return ph.getPlayerTurn() == ai && ai.getGame().getStack().isEmpty()
                && (ph.getPhase() == forge.game.phase.PhaseType.MAIN1 || ph.getPhase() == forge.game.phase.PhaseType.MAIN2);
        }

        static boolean undervalued(SpellAbility sa) {
            return enabled && sa.isSpell() && sa.getHostCard() != null && UNDERVALUED.contains(sa.getHostCard().getName());
        }

        /** Overrule Forge's "not now" for this permanent spell, or keep it (null). */
        static forge.ai.AiAbilityDecision overrule(Player ai, SpellAbility sa, forge.ai.AiAbilityDecision d) {
            if (!enabled || d.willingToPlay() || !sa.isSpell() || sa.getHostCard() == null || !myMain(ai)) return d;
            forge.ai.AiPlayDecision dec = d.decision();
            if (dec == forge.ai.AiPlayDecision.CantAfford || dec == forge.ai.AiPlayDecision.CantAffordX
                    || dec == forge.ai.AiPlayDecision.WouldDestroyLegend || dec == forge.ai.AiPlayDecision.TargetingFailed
                    || dec == forge.ai.AiPlayDecision.CantPlaySa) return d;
            Card c = sa.getHostCard();
            boolean under = undervalued(sa);
            int hand = ai.getCardsIn(forge.game.zone.ZoneType.Hand).size();
            boolean overflow = ai.getGame().getPhaseHandler().getPhase() == forge.game.phase.PhaseType.MAIN2 && hand > ai.getMaxHandSize();
            if (!under && !overflow) return d;
            String why = under ? "undervalued" : "hand " + hand + " over " + ai.getMaxHandSize();
            if (sa.costHasManaX()) {
                if (!under) return d;
                int max = forge.ai.ComputerUtilCost.setMaxXValue(sa, ai, false);
                int x = "Blitzball Stadium".equals(c.getName()) ? Math.min(max, ai.getCreaturesInPlay().size()) : max;
                if (x < ("Wildwood Scourge".equals(c.getName()) ? 2 : 1)) return d;
                sa.setXManaCostPaid(x);
                why += ", X=" + x;
            }
            if (!forge.ai.ComputerUtilCost.canPayCost(sa, ai, false)) return d;
            System.out.println("DEPLOYAI " + c.getName() + " (" + why + ")");
            return new forge.ai.AiAbilityDecision(100, forge.ai.AiPlayDecision.WillPlay);
        }
    }

    public static class HoldingPermanentCreatureAi extends forge.ai.ability.PermanentCreatureAi {
        @Override
        protected boolean checkPhaseRestrictions(Player ai, SpellAbility sa, forge.game.phase.PhaseHandler ph) {
            return (Deploy.undervalued(sa) && Deploy.myMain(ai)) || super.checkPhaseRestrictions(ai, sa, ph);
        }

        @Override
        protected forge.ai.AiAbilityDecision checkApiLogic(Player ai, SpellAbility sa) {
            if (CaptainPolicy.enabled && CaptainPolicy.isCaptain(sa.getHostCard())) {
                String wait = CaptainPolicy.castWaitReason(ai);
                if (wait != null) return new forge.ai.AiAbilityDecision(0, forge.ai.AiPlayDecision.CantPlayAi);
            }
            ManaHold.refresh(ai);
            return Deploy.overrule(ai, sa, super.checkApiLogic(ai, sa));
        }
    }

    public static class HoldingPermanentNoncreatureAi extends forge.ai.ability.PermanentNoncreatureAi {
        @Override
        protected boolean checkPhaseRestrictions(Player ai, SpellAbility sa, forge.game.phase.PhaseHandler ph) {
            return (Deploy.undervalued(sa) && Deploy.myMain(ai)) || super.checkPhaseRestrictions(ai, sa, ph);
        }

        @Override
        protected forge.ai.AiAbilityDecision checkApiLogic(Player ai, SpellAbility sa) {
            ManaHold.refresh(ai);
            return Deploy.overrule(ai, sa, super.checkApiLogic(ai, sa));
        }
    }

    /**
     * "deathsave": Deadpool's "when it dies, return it" instants (Feign Death, Not Dead After
     * All, Undying Evil) were held 70-77% of the time - Forge has no AI for them. Now cast in
     * response to an opponent's destroy / damage effect, or after blocks, on the most valuable
     * creature of ours that would die (Undying Evil: only one without +1/+1 counters).
     * Logged as "SAVEAI ...".
     */
    static final class DeathSave {
        static boolean enabled = false;
        static final List<String> CARDS = Arrays.asList("Feign Death", "Not Dead After All", "Undying Evil");

        static boolean applies(SpellAbility sa) {
            return enabled && sa.isSpell() && sa.getHostCard() != null && CARDS.contains(sa.getHostCard().getName());
        }

        static forge.ai.AiAbilityDecision decide(Player ai, SpellAbility sa) {
            List<Card> dying = SavingPumpAllAi.threatenedCreatures(ai, sa);
            boolean undying = "Undying Evil".equals(sa.getHostCard().getName());
            Card best = null;
            int bestValue = -1;
            for (Card c : dying) {
                if (!sa.canTarget(c) || (undying && c.getCounters(forge.game.card.CounterEnumType.P1P1) > 0)) continue;
                int v = forge.ai.ComputerUtilCard.evaluateCreature(c) + (c.isCommander() ? 300 : 0);
                if (v > bestValue) { bestValue = v; best = c; }
            }
            if (best == null) return new forge.ai.AiAbilityDecision(0, forge.ai.AiPlayDecision.CantPlayAi);
            sa.resetTargets();
            sa.getTargets().add(best);
            System.out.println("SAVEAI " + sa.getHostCard().getName() + ": " + best.getName() + " comes back");
            return new forge.ai.AiAbilityDecision(100, forge.ai.AiPlayDecision.WillPlay);
        }
    }

    /**
     * "captain": "creatures you control gain indestructible" (Flawless Maneuver) only when it
     * saves something. Forge's PumpAll AI cast it (free while a commander is out) in response
     * to harmless things - a creature spell, an exile effect, his own Throw - in about half of
     * its 30 casts in 200 games. Now: an opponent's spell or ability on top of the stack would
     * destroy or damage our creatures (the commander, a valuable creature or 2+ creatures), or
     * after blocks a commander / valuable creature would die in combat.
     */
    public static class SavingPumpAllAi extends forge.ai.ability.PumpAllAi {
        static final java.util.Set<ApiType> DESTROYS = java.util.EnumSet.of(ApiType.Destroy, ApiType.DestroyAll,
            ApiType.DealDamage, ApiType.DamageAll);
        static final int VALUABLE = 200;   // Forge creature evaluation (a 3/3 vanilla is ~200)

        static boolean grantsIndestructible(SpellAbility sa) {
            return sa.getParamOrDefault("KW", "").contains("Indestructible")
                && sa.getParamOrDefault("ValidCards", "").contains("YouCtrl");
        }

        static boolean worthSaving(Card c) {
            return c.isCommander() || forge.ai.ComputerUtilCard.evaluateCreature(c) >= VALUABLE;
        }

        /**
         * Our non-indestructible creatures that are about to die: hit by an opponent's destroy /
         * damage spell or ability on top of the stack (a wipe threatens all of them), or - with
         * an empty stack after blocks - losing their fight. Exile, bounce, -X/-X and sacrifice
         * are not counted (indestructible doesn't help there).
         */
        static java.util.List<Card> threatenedCreatures(Player ai, SpellAbility sa) {
            forge.game.Game game = ai.getGame();
            java.util.List<Card> threatened = new java.util.ArrayList<>();
            if (!game.getStack().isEmpty()) {
                SpellAbility top = forge.ai.ComputerUtilAbility.getTopSpellAbilityOnStack(game, sa);
                if (top == null || top.getActivatingPlayer() == null || !ai.isOpponentOf(top.getActivatingPlayer())) return threatened;
                boolean wipe = false, destroys = false;
                for (SpellAbility s = top; s != null; s = s.getSubAbility()) {
                    if (!DESTROYS.contains(s.getApi())) continue;
                    destroys = true;
                    if (s.getApi() == ApiType.DestroyAll || s.getApi() == ApiType.DamageAll) wipe = true;
                }
                if (!destroys) return threatened;
                for (forge.game.GameObject o : forge.ai.ComputerUtil.predictThreatenedObjects(ai, sa)) {
                    if (o instanceof Card && ((Card) o).isCreature() && ((Card) o).getController() == ai) threatened.add((Card) o);
                }
                if (threatened.isEmpty() && wipe) threatened.addAll(ai.getCreaturesInPlay());
            } else {
                forge.game.combat.Combat combat = game.getCombat();
                if (combat == null || game.getPhaseHandler().getPhase() != forge.game.phase.PhaseType.COMBAT_DECLARE_BLOCKERS) return threatened;
                for (Card c : ai.getCreaturesInPlay()) {
                    if ((combat.isAttacking(c) || combat.isBlocking(c)) && forge.ai.ComputerUtilCombat.combatantWouldBeDestroyed(ai, c, combat)) threatened.add(c);
                }
            }
            threatened.removeIf(c -> c.hasKeyword(forge.game.keyword.Keyword.INDESTRUCTIBLE));
            return threatened;
        }

        /** Why casting it now saves something, or null. */
        static String saveReason(Player ai, SpellAbility sa) {
            java.util.List<Card> threatened = threatenedCreatures(ai, sa);
            boolean combatOnly = ai.getGame().getStack().isEmpty();
            for (Card c : threatened) { if (worthSaving(c)) return "saves " + c.getName() + (combatOnly ? " in combat" : ""); }
            if (!combatOnly && threatened.size() >= 2) return "saves " + threatened.size() + " creatures";
            return null;
        }

        @Override
        protected forge.ai.AiAbilityDecision checkApiLogic(Player ai, SpellAbility sa) {
            if (!CaptainPolicy.enabled || !grantsIndestructible(sa)) return super.checkApiLogic(ai, sa);
            String why = saveReason(ai, sa);
            if (why == null) return new forge.ai.AiAbilityDecision(0, forge.ai.AiPlayDecision.CantPlayAi);
            System.out.println("SAVEAI " + sa.getHostCard().getName() + ": " + why);
            return new forge.ai.AiAbilityDecision(100, forge.ai.AiPlayDecision.WillPlay);
        }
    }

    /**
     * One-sided overload removal (Winds of Abandon; works for any overload spell whose
     * battlefield-leaving effect only hits "Creature.YouDontCtrl"). Forge's AI always turned the
     * overloaded version down and cast the single-target one (11 of 11 casts in v8's first
     * games), wasting an exile-everything-of-theirs wipe. With 3+ opposing creatures (or 2 worth
     * 500+ by Forge's evaluation), cast it overloaded and hold back the single-target version.
     */
    static final class OneSidedOverload {
        static final int MIN_CREATURES = 3, MIN_VALUE_FOR_TWO = 500;

        static boolean oneSided(SpellAbility sa) {
            String valid = sa.getParamOrDefault("ValidTgts", "") + "|" + sa.getParamOrDefault("Defined", "");
            return "Battlefield".equals(sa.getParam("Origin")) && valid.contains("Creature.YouDontCtrl");
        }

        static SpellAbility overloadOf(Card host) {
            for (SpellAbility s : host.getAllSpellAbilities()) {
                if (s.isAlternativeCost(forge.game.spellability.AlternativeCost.Overload)) return s;
            }
            return null;
        }

        static boolean worthIt(Player ai) {
            int n = 0, value = 0;
            for (Player opp : ai.getOpponents()) {
                for (Card c : opp.getCreaturesInPlay()) { n++; value += forge.ai.ComputerUtilCard.evaluateCreature(c); }
            }
            return n >= MIN_CREATURES || (n >= 2 && value >= MIN_VALUE_FOR_TWO);
        }

        /** A decision for this spell ability, or null to leave it to Forge. */
        static forge.ai.AiAbilityDecision decide(Player ai, SpellAbility sa) {
            Card host = sa.getHostCard();
            if (host == null || !sa.isSpell() || !oneSided(sa)) return null;
            SpellAbility over = overloadOf(host);
            if (over == null) return null;
            if (sa.isAlternativeCost(forge.game.spellability.AlternativeCost.Overload)) {
                if (!worthIt(ai)) return null;
                System.out.println("WIPEAI " + ai.getName() + ": " + host.getName() + " overloaded (one-sided)");
                return new forge.ai.AiAbilityDecision(100, forge.ai.AiPlayDecision.WillPlay);
            }
            // Single-target version: hold it if the overload is worth it and affordable now.
            over.setActivatingPlayer(ai);
            if (worthIt(ai) && forge.ai.ComputerUtilCost.canPayCost(over, ai, false)) {
                return new forge.ai.AiAbilityDecision(0, forge.ai.AiPlayDecision.CantPlayAi);
            }
            return null;
        }
    }

    /**
     * Board-damage wipes (Blasphemous Act) for the Captain America player: Forge's AI cast it
     * while it killed our own Captain America (3 of 6 casts in v8's first games). Only cast it
     * when he survives (indestructible, or toughness left above the damage) or isn't on the
     * battlefield - unless our life is in danger anyway.
     */
    public static class CaptainSafeDamageAllAi extends forge.ai.ability.DamageAllAi {
        static boolean capDies(Player ai, SpellAbility sa) {
            int dmg = forge.game.ability.AbilityUtils.calculateAmount(sa.getHostCard(), sa.getParamOrDefault("NumDmg", "0"), sa);
            for (Card c : ai.getCreaturesInPlay()) {
                if (!CaptainPolicy.isCaptain(c)) continue;
                if (c.hasKeyword(forge.game.keyword.Keyword.INDESTRUCTIBLE)) return false;
                return c.getNetToughness() - c.getDamage() <= dmg;
            }
            return false;
        }

        /**
         * Chandra's Ignition's damage is this API as a sub-ability, and Forge vets the sub on its
         * own: DamageAllAi only weighs creatures traded, so it vetoed an Ignition that IgnitionAi
         * had chosen for killing a player (IgnitionProbe case 5). The whole spell was already
         * judged by IgnitionAi - board on both sides, face damage, our Captain - so stand by that.
         */
        @Override
        public forge.ai.AiAbilityDecision chkDrawback(Player ai, SpellAbility sa) {
            if (sa.getHostCard() != null && "Chandra's Ignition".equals(sa.getHostCard().getName())) {
                return new forge.ai.AiAbilityDecision(100, forge.ai.AiPlayDecision.WillPlay);
            }
            return super.chkDrawback(ai, sa);
        }

        @Override
        protected forge.ai.AiAbilityDecision checkApiLogic(Player ai, SpellAbility sa) {
            forge.ai.AiAbilityDecision d = super.checkApiLogic(ai, sa);
            if (!CaptainPolicy.enabled || !d.willingToPlay() || !capDies(ai, sa)) return d;
            // "serious" = the board could kill us: the mild check fired even at 40 life.
            if (forge.ai.ComputerUtil.aiLifeInDanger(ai, true, 0)) {
                System.out.println("WIPEAI " + ai.getName() + ": " + sa.getHostCard().getName() + " although it kills Captain America (life in danger)");
                return d;
            }
            // Clearly behind on board: the trade is worth losing him (his Equipment stays for the
            // recast). Holding it for a safe Cap left it in hand until discarded (7 times in 120 games).
            int dmg = forge.game.ability.AbilityUtils.calculateAmount(sa.getHostCard(), sa.getParamOrDefault("NumDmg", "0"), sa);
            int theirs = 0, ours = 0;
            for (Card c : ai.getGame().getCardsIn(forge.game.zone.ZoneType.Battlefield)) {
                if (!c.isCreature() || c.hasKeyword(forge.game.keyword.Keyword.INDESTRUCTIBLE) || c.getNetToughness() - c.getDamage() > dmg) continue;
                if (c.getController() == ai) ours++; else theirs++;
            }
            if (theirs >= 4 && ours <= 2) {
                System.out.println("WIPEAI " + ai.getName() + ": " + sa.getHostCard().getName() + " although it kills Captain America (" + theirs + " of theirs vs " + ours + " of ours)");
                return d;
            }
            System.out.println("WIPEAI " + ai.getName() + ": " + sa.getHostCard().getName() + " held (would kill Captain America)");
            return new forge.ai.AiAbilityDecision(0, forge.ai.AiPlayDecision.CantPlayAi);
        }
    }

    @SuppressWarnings("unchecked")
    public static void apply() {
        List<String> wanted = Arrays.asList(System.getProperty("forge.aipatches", "").toLowerCase().split(","));
        try {
            Field f = SpellApiToAi.class.getDeclaredField("apiToInstance");
            f.setAccessible(true);
            Map<ApiType, SpellAbilityAi> instances = (Map<ApiType, SpellAbilityAi>) f.get(SpellApiToAi.Converter);
            if (wanted.contains("rolldice")) {
                instances.put(ApiType.RollDice, new SafeRollDiceAi());
                System.out.println("AiPatches: rolldice (optional dice-modifier prompts declined)");
            }
            if (wanted.contains("dice")) {
                DiceEngineAi.enabled = true;
                instances.put(ApiType.RollDice, new DiceEngineAi());
                System.out.println("AiPatches: dice (dice abilities used every turn when a roll payoff is out - the owner's Mr House)");
            }
            if (wanted.contains("housestyle")) {
                HouseAttack.enabled = true;
                System.out.println("AiPatches: housestyle (with Mr. House out: build robots behind blockers, then go wide - the owner's style)");
            }
            if (wanted.contains("catch")) {
                instances.put(ApiType.Attach, new CatchAwareAttachAi());
                System.out.println("AiPatches: catch (Captain America attaches protection first)");
            }
            if (wanted.contains("counter") || wanted.contains("counterall")) {
                ThreatAwareCounterAi.forEveryone = wanted.contains("counterall");
                instances.put(ApiType.Counter, new ThreatAwareCounterAi());
                instances.put(ApiType.PermanentCreature, new HoldingPermanentCreatureAi());
                instances.put(ApiType.PermanentNoncreature, new HoldingPermanentNoncreatureAi());
                ManaHold.enabled = true;
                System.out.println("AiPatches: counter (counterspells held for threats, mana kept open; "
                    + (ThreatAwareCounterAi.forEveryone ? "all players)" : "Captain America only)"));
            }
            if (wanted.contains("abilities")) {
                AbilityHelpers.enabled = true;
                instances.put(ApiType.Dig, new AbilityHelpers.HelperDigAi());
                instances.put(ApiType.Animate, new AbilityHelpers.HelperAnimateAi());
                instances.put(ApiType.Draw, new AbilityHelpers.HelperDrawAi());
                instances.put(ApiType.MoveCounter, new AbilityHelpers.HelperMoveCounterAi());
                instances.put(ApiType.CopyPermanent, new AbilityHelpers.HelperCopyPermanentAi());   // Kiki-Jiki loop guard
                instances.put(ApiType.Pump, new EquipHelperPumpAi());
                instances.put(ApiType.ChangeZoneAll, new AbilityHelpers.LanternChangeZoneAllAi());   // Soul-Guide Lantern
                instances.put(ApiType.Charm, new AbilityHelpers.FarewellCharmAi());   // Farewell: never exile our Equipment
                System.out.println("AiPatches: abilities (Face-Breaker, Blitzball Stadium, Rikku, Loran)");
            }
            if (wanted.contains("humanlike")) {
                HumanHooks.enabled = true;
                System.out.println("AiPatches: humanlike (human-like mulligans and attack targets)");
            }
            if (wanted.contains("cmdrfirst")) {
                commanderFirst = true;
                System.out.println("AiPatches: cmdrfirst (commanders cast as soon as affordable)");
            }
            if (wanted.contains("captain")) {
                CaptainPolicy.enabled = true;
                instances.put(ApiType.DealDamage, new ThrowAwareDamageAi());
                instances.put(ApiType.Pump, new EquipHelperPumpAi());   // Codsworth / Weapons Vendor attach
                instances.put(ApiType.PumpAll, new SavingPumpAllAi());   // Flawless Maneuver only when it saves something
                instances.put(ApiType.DamageAll, new CaptainSafeDamageAllAi());   // Blasphemous Act: don't kill our own Cap
                System.out.println("AiPatches: captain (Throw saved for attackers or lethal; attacks only when it pays off)");
            }
            if (wanted.contains("deploy")) {
                Deploy.enabled = true;
                System.out.println("AiPatches: deploy (undervalued permanents cast; permanents cast rather than discarded to hand size)");
            }
            if (wanted.contains("deathsave")) {
                DeathSave.enabled = true;
                System.out.println("AiPatches: deathsave (Feign Death / Not Dead After All / Undying Evil in response to lethal damage or destroy)");
            }
            if (wanted.contains("rampfirst")) {
                rampFirst = true;
                System.out.println("AiPatches: rampfirst (mana rocks cast in the first main phase)");
            }
            if (wanted.contains("threat")) {
                ThreatTracker.enabled = true;
                instances.put(ApiType.Destroy, new ThreatDestroyAi());
                instances.put(ApiType.ChangeZone, new ThreatChangeZoneAi());
                System.out.println("AiPatches: threat (dynamic threat values; removal aimed at proven threats)");
            }
            if (wanted.contains("textbox")) {
                instances.put(ApiType.valueOf("ExchangeTextBox"), new AlwaysPlayAi());
                System.out.println("AiPatches: textbox (AlwaysPlayAi for ExchangeTextBox)");
            }
        } catch (Exception e) {
            System.out.println("AiPatches: FAILED to apply (" + e + ")");
        }
    }
}
