package mtg3d;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One-line reminders for the keywords on a card, for the hover panel. Forge's oracle text uses
 * the bare keyword ("Flying, lifelink"), which is fine for a regular but leaves anyone newer
 * guessing - and a few (ward, menace, toxic) catch out regulars too.
 *
 * Matching is a whole-word, case-insensitive search of the rules text, so a card that only
 * MENTIONS flying ("creatures with flying get +1/+1") also gets the flying line. That is still
 * useful information about the card, so it is left that way rather than trying to parse Magic.
 */
final class Keywords {
    private Keywords() {}

    private static final Map<String, String> REMINDER = new LinkedHashMap<>();
    static {
        // Evasion and combat first: the ones that decide blocks.
        r("Flying", "can only be blocked by creatures with flying or reach");
        r("Reach", "can block creatures with flying");
        r("Menace", "can't be blocked except by two or more creatures");
        r("Trample", "excess combat damage goes through to the player");
        r("Double strike", "deals both first-strike and regular combat damage");
        r("First strike", "deals combat damage before creatures without first strike");
        r("Deathtouch", "any damage it deals to a creature is enough to destroy it");
        r("Lifelink", "damage it deals also gains its controller that much life");
        r("Vigilance", "attacking doesn't cause it to tap");
        r("Haste", "can attack and tap the turn it comes under your control");
        r("Defender", "can't attack");
        r("Indestructible", "damage and \"destroy\" effects don't destroy it");
        r("Hexproof", "can't be the target of opponents' spells or abilities");
        r("Shroud", "can't be the target of any spells or abilities");
        r("Ward", "opponents' spells/abilities targeting it are countered unless they pay the ward cost");
        r("Protection", "can't be blocked, targeted, dealt damage or enchanted/equipped by the named quality");
        r("Flash", "can be cast any time you could cast an instant");
        r("Infect", "deals damage to creatures as -1/-1 counters and to players as poison counters");
        r("Toxic", "players dealt combat damage by it also get that many poison counters");
        r("Wither", "deals damage to creatures as -1/-1 counters");
        r("Prowess", "+1/+1 until end of turn whenever you cast a noncreature spell");
        r("Exalted", "a creature attacking alone gets +1/+1 for each exalted instance");
        r("Annihilator", "when it attacks, the defending player sacrifices that many permanents");
        r("Myriad", "when it attacks, copies attack each other opponent too");
        r("Persist", "when it dies without a -1/-1 counter, it returns with one");
        r("Undying", "when it dies without a +1/+1 counter, it returns with one");
        r("Cascade", "when cast, exile until a cheaper nonland card and cast it free");
        r("Storm", "copy it for each spell cast before it this turn");
        r("Convoke", "your creatures can tap to help pay for it");
        r("Delve", "exile cards from your graveyard to help pay for it");
        r("Affinity", "costs 1 less for each of the named permanents you control");
        r("Flashback", "can be cast from your graveyard, then is exiled");
        r("Kicker", "an optional extra cost for an extra effect");
        r("Cycling", "pay the cost, discard it: draw a card");
        r("Equip", "attach to a creature you control; only as a sorcery");
        r("Ninjutsu", "swap it in from your hand for an unblocked attacker");
        r("Morph", "can be cast face down as a 2/2 for {3} and turned up for its morph cost");
        r("Disguise", "can be cast face down as a 2/2 with ward {2} and turned up for its cost");
        r("Foretell", "exile it face down for {2}, cast it later for its foretell cost");
        r("Partner", "can be your commander alongside another partner");
    }

    private static final Map<String, Pattern> PATTERN = new LinkedHashMap<>();
    static {
        for (String k : REMINDER.keySet()) {
            PATTERN.put(k, Pattern.compile("\\b" + Pattern.quote(k) + "\\b", Pattern.CASE_INSENSITIVE));
        }
    }

    private static void r(String keyword, String text) {
        REMINDER.put(keyword, text);
    }

    /** "Flying - can only be blocked by ..." lines for the keywords in this text, at most `max`. */
    static List<String> reminders(String text, int max) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        boolean doubleStrike = PATTERN.get("Double strike").matcher(text).find();
        for (Map.Entry<String, Pattern> e : PATTERN.entrySet()) {
            if (out.size() >= max) break;
            // "double strike" contains "strike" but not "first strike"; still, skip first strike
            // when the only match is inside the double-strike reminder sense.
            if (e.getKey().equals("First strike") && doubleStrike
                && !text.toLowerCase().replace("double strike", "").contains("first strike")) continue;
            if (e.getValue().matcher(text).find()) out.add(e.getKey() + " - " + REMINDER.get(e.getKey()));
        }
        return out;
    }
}
