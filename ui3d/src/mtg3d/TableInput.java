package mtg3d;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.VertexAttributes.Usage;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.math.collision.Ray;

import java.util.ArrayList;
import java.util.List;

/** Keyboard and mouse: clicks on cards, seats, pads and buttons, camera keys, and picking what is under the mouse. See TableBase. */
abstract class TableInput extends TableHud {

    /**
     * Demo trigger (S): opens the search panel over everything currently in the snapshot, so the
     * animation and the grid can be exercised before stage 3 has anything real to search.
     */
    /**
     * The ESC menu, built on OptionPanel. The toggles act at once and reopen the menu so their new
     * state shows; concede and undo are listed but not wired to Forge yet (attended work), and say so.
     */
    void openMenu() {
        List<OptionPanel.Option> o = new ArrayList<>();
        o.add(new OptionPanel.Option("Resume", "back to the table", null));
        o.add(new OptionPanel.Option("Sound: " + (cues != null && cues.muted() ? "off" : "on"), "M", null));
        o.add(new OptionPanel.Option("Game log: " + (showLog ? "shown" : "hidden"), "L", null));
        o.add(new OptionPanel.Option("Follow the turn: " + (followTurn ? "on" : "off"), "the camera walks to whoever's turn it is", null));
        o.add(new OptionPanel.Option("Undo", "takes back the last action Forge allows (tapping for mana, mostly)", null));
        o.add(new OptionPanel.Option("Concede", "asks first", null));
        o.add(new OptionPanel.Option("Keys", "SPACE ok  X cancel  H hand back  TAB camera  arrows walk  wheel zoom", null));
        o.add(new OptionPanel.Option("Graphics: " + GraphicsQuality.label(), GraphicsQuality.detail(), null));
        options.open("Menu", "ESC closes", o, 1, 1, picked -> {
            if (picked.isEmpty()) return;
            switch (picked.get(0)) {
                case 1: if (cues != null) cues.toggleMute(); openMenu(); break;
                case 2: showLog = !showLog; openMenu(); break;
                case 3: followTurn = !followTurn; lastActiveSeat = activeSeat(); openMenu(); break;
                case 4:
                    if (Gui3D.active()) Gui3D.undo(); else System.out.println("UI3D: undo (demo)");
                    break;
                case 5: {
                    List<OptionPanel.Option> yn = new ArrayList<>();
                    yn.add(new OptionPanel.Option("Yes, concede", "you lose this game", null));
                    yn.add(new OptionPanel.Option("No", "back to the game", null));
                    options.open("Concede?", "This cannot be undone.", yn, 1, 1, ans -> {
                        if (!ans.isEmpty() && ans.get(0) == 0) {
                            if (Gui3D.active()) Gui3D.concede(); else System.out.println("UI3D: concede (demo)");
                        }
                    });
                    break;
                }
                case 7: GraphicsQuality.next(); applyGraphics(); openMenu(); break;
                case 6: openMenu(); break;
                default: break;
            }
        });
    }

    /**
     * Forge does not say why it refused a click - the card is simply not in its selectable set -
     * so this is the best guess the snapshot allows, worded as a guess where it is one. It beats
     * silence: before, a refused click only reached the console.
     */
    String whyNot(Drawn d) {
        Snapshot.CardV c = d.card;
        Snapshot.SeatV me = snap.you();
        // Declaring attackers with a creature that arrived this turn: the commonest refusal in
        // combat, and invisible on the board (owner's game, 2026-10-21: nine new Robots).
        if (c.sick && d.seat != null && d.seat.isYou && "InputAttack".equals(Gui3D.PROMPT.inputName)) {
            return c.name + " is summoning sick - it came in this turn and has no haste";
        }
        if (me != null && !me.hasPriority) return "Not your priority - Forge is waiting on someone else";
        boolean inHand = false;
        for (Snapshot.CardV h : snap.hand) if (h.id == c.id) inHand = true;
        if (inHand) {
            // The commonest refusal there is, and the least obvious: you tap out for a mana rock,
            // click the thing you meant to cast with it, and nothing happens - because the rock is
            // still ON THE STACK and is not making mana yet. Forge is right to refuse; the player
            // is owed the reason and the one key that fixes it. Named before the generic answers,
            // which blamed "wrong timing, or not enough mana" and sent the owner hunting for a
            // state bug (2026-10-21).
            if (!snap.stack.isEmpty()) {
                Snapshot.CardV top = snap.stack.get(snap.stack.size() - 1);   // the stack is bottom first
                String what = top.name.isEmpty() ? "something" : top.name;
                return (c.land ? "Can't play " : "Can't cast ") + c.name + " yet - " + what
                    + " is still on the stack. SPACE lets it resolve";
            }
            if (c.land) return "Can't play " + c.name + " now - lands need your main phase, an empty stack, one a turn";
            return "Can't cast " + c.name + " now - wrong timing, or not enough mana";
        }
        if (d.seat != null && !d.seat.isYou) return "That's " + d.seat.name + "'s card - nothing to do with it right now";
        if (d.seat != null) return c.name + " has nothing it can do right now";
        return "Forge won't take that right now";
    }

    /**
     * Owner's bug report 2026-10-20 (143053): a Plains clicked on someone else's turn, meaning to
     * cast a creature with it, was simply tapped - Forge's answer to a land click at ordinary
     * priority is its mana ability, and the floating mana was gone by the next step. So the first
     * click on one of our own untapped lands, when nothing is being paid for, only warns; a second
     * click on the same land within STRAY_TAP_MS taps it (floating mana is sometimes meant).
     * True = the click was held back. -Dforge.ui3d.landguard=false turns this off.
     */
    boolean strayLandTap(Drawn d) {
        if (!LAND_GUARD || d.card == null || !d.card.land || d.card.tapped) return false;
        if (d.seat == null || !d.seat.isYou || manaPlan != null || targeting != null) return false;
        boolean onBattlefield = false;
        for (Snapshot.CardV l : d.seat.lands) if (l.id == d.card.id) onBattlefield = true;
        if (!onBattlefield) return false;
        long now = System.currentTimeMillis();
        if (strayTapId == d.card.id && now - strayTapAt < STRAY_TAP_MS) { strayTapId = -1; return false; }
        strayTapId = d.card.id;
        strayTapAt = now;
        return true;
    }

    static final boolean LAND_GUARD = !"false".equalsIgnoreCase(System.getProperty("forge.ui3d.landguard", "true"));
    static final long STRAY_TAP_MS = 3000;
    int strayTapId = -1;
    long strayTapAt;

    /** The graphics level's live parts: frame cap and vsync now; the rest when things next load. */
    void applyGraphics() {
        try {
            Gdx.graphics.setForegroundFPS(GraphicsQuality.fps());
            Gdx.graphics.setVSync(GraphicsQuality.vsync());
        } catch (Throwable ignored) {
            // frame cap stays as it was
        }
        toast("Graphics: " + GraphicsQuality.label() + (GraphicsQuality.current() == GraphicsQuality.Level.LOW
            ? "" : " - anti-aliasing changes next time the window opens"), Gdx.graphics.getWidth() / 2f - 200f, 120f);
    }

    boolean planLeftWas;
    /** The prompt token the board modes were last synced to, and the card last clicked from hand. */
    Object modeToken;
    int lastCastId = Integer.MIN_VALUE;
    /** Mulligans taken this game, for the "Mulligan (to N)" label; reset when the mulligan ends. */
    int mulligansTaken;
    Object mulliganShownFor;

    /**
     * Turns the board modes on and off from the Input Forge is waiting on (Gui3D.PROMPT.inputName),
     * so in a real game they appear by themselves: targets glow when a spell wants targets, the
     * drag-to-attack mode is on during declare attackers, and so on. In wired mode the visible
     * assignments are refreshed from the game every frame (combat arcs, selectables), so what is
     * drawn is what Forge has accepted, not what was hoped. The demo (no Gui3D) is left alone.
     */
    void syncBoardModes() {
        if (!Gui3D.active()) return;
        String in = Gui3D.PROMPT.inputName;
        Object tok = Gui3D.PROMPT.token;
        boolean changed = tok != modeToken;
        modeToken = tok;
        Snapshot.SeatV me = snap.you();
        int you = snap.youIndex();
        List<Integer> sel = Gui3D.PROMPT.selectable;

        // Targets: InputSelectTargets puts the legal cards in the selectable set.
        if ("InputSelectTargets".equals(in)) {
            if (targeting == null || !targeting.wired || changed) {
                Targeting tg = new Targeting();
                tg.wired = true;
                tg.sourceId = lastCastId;
                tg.max = 99;
                targeting = tg;
            }
            targeting.legal.clear();
            targeting.legal.addAll(sel);
            if (sel.isEmpty()) {
                // No list from Forge: let every permanent be clickable and let Forge judge.
                for (Snapshot.SeatV s : snap.seats) {
                    for (Snapshot.CardV c : s.creatures) targeting.legal.add(c.id);
                    for (Snapshot.CardV c : s.others) targeting.legal.add(c.id);
                    for (Snapshot.CardV c : s.lands) targeting.legal.add(c.id);
                }
            }
            targeting.what = Gui3D.PROMPT.message;
        } else if (targeting != null && targeting.wired) {
            targeting = null;
        }

        // Declare attackers / blockers.
        boolean attack = "InputAttack".equals(in), block = "InputBlock".equals(in);
        if ((attack || block) && me != null) {
            if (combatPlan == null || !combatPlan.wired || combatPlan.blocking != block) {
                CombatPlan cp = new CombatPlan(block);
                cp.wired = true;
                combatPlan = cp;
            }
            CombatPlan cp = combatPlan;
            cp.mine.clear();
            // What may act glows: untapped, and for an attack not summoning sick. It used to glow
            // every untapped creature for an attack (new Robots looked ready - owner's game,
            // 2026-10-21) and every creature, tapped ones too, for a block.
            for (Snapshot.CardV c : me.creatures) if (!c.tapped && (block || !c.sick)) cp.mine.add(c.id);
            if (attack) {
                // What Forge has accepted, from the live combat - the drawn arcs follow the game.
                if (cp.dragging == Integer.MIN_VALUE) {
                    cp.attackSeat.clear();
                    for (Snapshot.CombatArc a : snap.combatArcs) {
                        if (cp.mine.contains(a.attackerId) && a.defenderSeat >= 0) cp.attackSeat.put(a.attackerId, a.defenderSeat);
                    }
                }
            } else {
                cp.attackers.clear();
                for (Snapshot.CombatArc a : snap.combatArcs) {
                    if (a.defenderSeat == you) cp.attackers.add(a.attackerId);
                }
                if (cp.dragging == Integer.MIN_VALUE) {
                    cp.blockOn.clear();
                    for (Snapshot.CombatArc a : snap.combatArcs) for (int b : a.blockedBy) cp.blockOn.put(b, a.attackerId);
                }
            }
        } else if (combatPlan != null && combatPlan.wired) {
            combatPlan = null;
        }

        // Paying mana: Forge offers the sources that can pay, or failing that, our untapped lands.
        if (in.startsWith("InputPayMana")) {
            if (manaPlan == null || !manaPlan.wired || changed) {
                ManaPlan mp = new ManaPlan();
                mp.wired = true;
                manaPlan = mp;
            }
            manaPlan.cost = Gui3D.PROMPT.message;
            manaPlan.usable.clear();
            if (!sel.isEmpty()) manaPlan.usable.addAll(sel);
            else if (me != null) for (Snapshot.CardV c : me.lands) if (!c.tapped) manaPlan.usable.add(c.id);
        } else if (manaPlan != null && manaPlan.wired) {
            manaPlan = null;
        }

        // The mulligan: keep / mulligan, then (London) the cards to put on the bottom.
        if ("InputConfirmMulligan".equals(in) && tok != mulliganShownFor && !mulligan.isOpen()) {
            mulliganShownFor = tok;
            final boolean okIsKeep = !Gui3D.PROMPT.okLabel.toLowerCase().contains("mull");
            mulligan.openKeep(new ArrayList<>(snap.hand), mulligansTaken, keep -> {
                if (!keep) mulligansTaken++;
                if (keep == okIsKeep) Gui3D.pressOk(); else Gui3D.pressCancel();
            });
        } else if ("InputLondonMulligan".equals(in) && tok != mulliganShownFor && !mulligan.isOpen()) {
            mulliganShownFor = tok;
            int n = 1;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(Gui3D.PROMPT.message);
            if (m.find()) n = Math.max(1, Integer.parseInt(m.group(1)));
            mulligan.openBottom(new ArrayList<>(snap.hand), n, cards -> {
                for (Snapshot.CardV c : cards) Gui3D.clickCard(c.id);
                Gui3D.pressOkQueued();
            });
        } else if (!in.contains("Mulligan")) {
            if (mulligan.isOpen() && mulliganShownFor != null) mulligan.close();
            mulliganShownFor = null;
            if (!in.isEmpty()) mulligansTaken = 0;
        }
    }

    /**
     * Declaring attackers / blockers by drag. Press on a glowing creature to pick it up, release
     * over the target: an opponent's monitor, pad or card (attacking - which player is worked out
     * from the side of the table), or an attacker (blocking). Releasing anywhere else, or back on
     * the creature, takes its assignment off. Returns true when the frame's input is used up.
     */
    boolean handleCombatPlan() {
        CombatPlan p = combatPlan;
        if (p.wired) {
            // A real declaration: SPACE is Forge's OK (declare these), ESC opens the menu as usual.
            if (Keys3D.just(Input.Keys.SPACE)) { Gui3D.pressOk(); return true; }
        } else {
            if (Keys3D.just(Input.Keys.ESCAPE)) { combatPlan = null; return true; }
            if (Keys3D.just(Input.Keys.SPACE)) {
                System.out.println("UI3D: " + (p.blocking ? "blocks " + p.blockOn : "attacks " + p.attackSeat) + " (demo)");
                combatPlan = null;
                return true;
            }
        }
        clickedCard();                       // eat the click edge: no card is played meanwhile
        // A on the pad counts as the left button, so every picker, pad and panel that already
        // works from a click works from the controller with no second code path.
        boolean down = Gdx.input.isButtonPressed(Input.Buttons.LEFT) || Pad.clickHeld();
        if (down && !planLeftWas && hovered != null && hovered.seat != null && p.mine.contains(hovered.card.id)) {
            p.dragging = hovered.card.id;
        }
        if (!down && planLeftWas && p.dragging != Integer.MIN_VALUE) {
            int id = p.dragging;
            p.dragging = Integer.MIN_VALUE;
            if (p.blocking) {
                Integer was = p.blockOn.get(id);
                if (hovered != null && p.attackers.contains(hovered.card.id)) {
                    // Forge's InputBlock: click the attacker (it becomes current), then the blocker.
                    if (p.wired) {
                        if (was != null && was != hovered.card.id) { Gui3D.clickCard(was); Gui3D.clickCard(id); }
                        Gui3D.clickCard(hovered.card.id);
                        Gui3D.clickCard(id);
                    }
                    p.blockOn.put(id, hovered.card.id);
                } else {
                    // Dropped elsewhere: take the block off (the same two clicks toggle it back).
                    if (p.wired && was != null) { Gui3D.clickCard(was); Gui3D.clickCard(id); }
                    p.blockOn.remove(id);
                }
            } else {
                int seat = -1;
                if (hoveredSeat >= 0) seat = hoveredSeat;
                else if (hovered != null && hovered.card.id != id && hovered.seat != null) seat = snap.seats.indexOf(hovered.seat);
                else {
                    float[] m = mouseOnTable();
                    if (m != null && (hovered == null || hovered.card.id != id)) seat = seatAt(m[0], m[1]);
                }
                boolean was = p.attackSeat.containsKey(id);
                if (seat >= 0 && seat < snap.seats.size() && !snap.seats.get(seat).isYou) {
                    // Forge's InputAttack: click the player (they become the defender), then the
                    // creature (it attacks them). Re-aiming an attacker means taking it off first.
                    if (p.wired) {
                        Integer before = p.attackSeat.get(id);
                        if (was && before != null && before != seat) Gui3D.clickCard(id);
                        if (!was || (before != null && before != seat)) { Gui3D.clickPlayer(seat); Gui3D.clickCard(id); }
                    }
                    p.attackSeat.put(id, seat);
                } else {
                    if (p.wired && was) Gui3D.clickCard(id);     // a second click takes it off
                    p.attackSeat.remove(id);
                }
            }
        }
        planLeftWas = down;
        return false;
    }

    static final String OPEN_PANEL = System.getProperty("forge.ui3d.openPanel");
    boolean panelShown;

    /** Sample contents for each not-yet-wired panel, so they can be looked at (and screenshotted). */
    void openSamplePanel(String which) {
        List<OptionPanel.Option> o = new ArrayList<>();
        switch (which) {
            case "modes":
                o.add(new OptionPanel.Option("Deal 4 damage to target creature", "Boros Charm, mode 1", null));
                o.add(new OptionPanel.Option("Permanents you control gain indestructible", "until end of turn", null));
                o.add(new OptionPanel.Option("Target creature gains double strike", "until end of turn", null));
                options.open("Boros Charm", "Choose one.", o, 1, 1, picked -> System.out.println("UI3D: mode " + picked));
                break;
            case "ability":
                // Which ability of a card, as the drop-down beside it (Gui3D.takeAbility).
                o.add(new OptionPanel.Option("{3}{W}{W}{W}: Until end of turn, Resplendent Angel gets +2/+0 and gains lifelink."));
                o.add(new OptionPanel.Option("Cast Resplendent Angel"));
                options.open("Resplendent Angel - which ability?", "Choose one, or ESC for none.", o, 0, 1, picked -> { });
                for (Drawn d : drawn) {
                    if (d.card == null || d.seat == null || !d.card.name.equals("Resplendent Angel")) continue;
                    com.badlogic.gdx.math.Vector3 sp = cam.project(d.bounds.getCenter(new com.badlogic.gdx.math.Vector3()));
                    options.anchorAt(sp.x, sp.y);
                    break;
                }
                break;
            case "colour":
                o.add(new OptionPanel.Option("White", "", new Color(0.96f, 0.93f, 0.80f, 1f)));
                o.add(new OptionPanel.Option("Blue", "", new Color(0.30f, 0.55f, 0.90f, 1f)));
                o.add(new OptionPanel.Option("Black", "", new Color(0.25f, 0.22f, 0.28f, 1f)));
                o.add(new OptionPanel.Option("Red", "", new Color(0.88f, 0.30f, 0.22f, 1f)));
                o.add(new OptionPanel.Option("Green", "", new Color(0.30f, 0.70f, 0.35f, 1f)));
                options.open("Choose a colour", "Painter's Servant", o, 1, 1, picked -> { });
                break;
            case "player":
                for (Snapshot.SeatV s : snap.seats) {
                    o.add(new OptionPanel.Option(s.name, s.life + " life" + (s.isYou ? "  (you)" : ""), null));
                }
                options.open("Choose a player", "Target player draws two cards.", o, 1, 1, picked -> { });
                break;
            case "types": {
                // The case that broke it: Unclaimed Territory asks for a creature type and Forge
                // offers every one there is. Worth keeping as a sample, because any panel that
                // handles this handles everything shorter.
                String[] types = {
                    "Advisor", "Aetherborn", "Alien", "Ally", "Angel", "Antelope", "Ape", "Archer", "Archon",
                    "Armadillo", "Army", "Artificer", "Assassin", "Assembly-Worker", "Astartes", "Atog",
                    "Aurochs", "Avatar", "Azra", "Badger", "Balloon", "Barbarian", "Bard", "Basilisk", "Bat",
                    "Bear", "Beast", "Beaver", "Beeble", "Beholder", "Berserker", "Bird", "Blinkmoth", "Boar",
                    "Bringer", "Brushwagg", "Camarid", "Camel", "Capybara", "Caribou", "Carrier", "Cat",
                    "Centaur", "Cephalid", "Child", "Chimera", "Citizen", "Cleric", "Clown", "Cockatrice",
                    "Construct", "Coward", "Coyote", "Crab", "Crocodile", "Custodes", "Cyberman", "Cyclops",
                    "Dalek", "Dauthi", "Demigod", "Demon", "Deserter", "Detective", "Devil", "Dinosaur",
                    "Djinn", "Doctor", "Dog", "Dragon", "Drake", "Dreadnought", "Drone", "Druid", "Dryad",
                    "Dwarf", "Efreet", "Egg", "Elder", "Eldrazi", "Elemental", "Elephant", "Elf", "Elk",
                    "Employee", "Eye", "Faerie", "Ferret", "Fish", "Flagbearer", "Fox", "Fractal", "Frog",
                    "Fungus", "Gamer", "Gargoyle", "Germ", "Giant", "Gith", "Glimmer", "Gnoll", "Gnome",
                    "Goat", "Goblin", "God", "Golem", "Gorgon", "Graveborn", "Gremlin", "Griffin", "Guest",
                    "Hag", "Halfling", "Hamster", "Harpy", "Hellion", "Hippo", "Hippogriff", "Homarid",
                    "Homunculus", "Horror", "Horse", "Human", "Hydra", "Hyena", "Illusion", "Imp",
                    "Incarnation", "Inkling", "Inquisitor", "Insect", "Jackal", "Jellyfish", "Juggernaut",
                    "Kavu", "Kirin", "Kithkin", "Knight", "Kobold", "Kor", "Kraken", "Llama", "Lamia",
                    "Lammasu", "Leech", "Lemur", "Leviathan", "Lhurgoyf", "Licid", "Lizard", "Manticore",
                    "Masticore", "Mercenary", "Merfolk", "Metathran", "Minion", "Minotaur", "Mite", "Mole",
                    "Monger", "Mongoose", "Monk", "Monkey", "Moonfolk", "Mount", "Mouse", "Mutant", "Myr",
                    "Mystic", "Nautilus", "Necron", "Nephilim", "Nightmare", "Nightstalker", "Ninja",
                    "Noble", "Noggle", "Nomad", "Nymph", "Octopus", "Ogre", "Ooze", "Orb", "Orc", "Orgg",
                    "Otter", "Ouphe", "Ox", "Oyster", "Pangolin", "Peasant", "Pegasus", "Pentavite",
                    "Performer", "Pest", "Phelddagrif", "Phoenix", "Phyrexian", "Pilot", "Pincher",
                    "Pirate", "Plant", "Porcupine", "Possum", "Praetor", "Primarch", "Prism", "Processor",
                    "Rabbit", "Raccoon", "Ranger", "Rat", "Rebel", "Reflection", "Rhino", "Rigger", "Robot",
                    "Rogue", "Sable", "Salamander", "Samurai", "Sand", "Saproling", "Satyr", "Scarecrow",
                    "Scientist", "Scion", "Scorpion", "Scout", "Sculpture", "Serf", "Serpent", "Servo",
                    "Shade", "Shaman", "Shapeshifter", "Shark", "Sheep", "Siren", "Skeleton", "Skunk",
                    "Slith", "Sliver", "Sloth", "Slug", "Snail", "Snake", "Soldier", "Soltari", "Spawn",
                    "Specter", "Spellshaper", "Sphinx", "Spider", "Spike", "Spirit", "Splinter", "Sponge",
                    "Squid", "Squirrel", "Starfish", "Surrakar", "Survivor", "Synth", "Tentacle",
                    "Tetravite", "Thalakos", "Thopter", "Thrull", "Tiefling", "Time Lord", "Toy",
                    "Treefolk", "Trilobite", "Triskelavite", "Troll", "Turtle", "Tyranid", "Unicorn",
                    "Vampire", "Varmint", "Vedalken", "Vehicle", "Volver", "Wall", "Walrus", "Warlock",
                    "Warrior", "Weasel", "Weird", "Werewolf", "Whale", "Wizard", "Wolf", "Wolverine",
                    "Wombat", "Worm", "Wraith", "Wurm", "Yeti", "Zombie", "Zubera",
                };
                for (String ty : types) o.add(new OptionPanel.Option(ty));
                options.open("Choose a creature type", "Unclaimed Territory", o, 1, 1, picked -> { });
                break;
            }
            case "two":
                o.add(new OptionPanel.Option("Counter target spell", "", null));
                o.add(new OptionPanel.Option("Return target permanent to its owner's hand", "", null));
                o.add(new OptionPanel.Option("Tap all creatures your opponents control", "", null));
                o.add(new OptionPanel.Option("Draw a card", "", null));
                options.open("Cryptic Command", "Choose two.", o, 2, 2, picked -> { });
                break;
            case "scry": {
                List<Snapshot.CardV> top = new ArrayList<>();
                int id = 970;
                for (String n : new String[] { "Sol Ring", "Swords to Plowshares", "Plains" }) {
                    top.add(new Snapshot.CardV(id++, n, "", false, n.equals("Plains")));
                }
                List<List<Snapshot.CardV>> rowsIn = new ArrayList<>();
                rowsIn.add(top);
                rowsIn.add(new ArrayList<>());
                order.open("Scry 3", "Put any number on the bottom and the rest back in any order.", rowsIn,
                    new String[] { "Top of library", "Bottom" }, new String[] { "drawn first", "" }, r -> { });
                break;
            }
            case "surveil": {
                List<Snapshot.CardV> top = new ArrayList<>();
                top.add(new Snapshot.CardV(975, "Arcane Denial", "", false, false));
                top.add(new Snapshot.CardV(976, "Lyra Dawnbringer", "", true, false));
                List<List<Snapshot.CardV>> rowsIn = new ArrayList<>();
                rowsIn.add(top);
                rowsIn.add(new ArrayList<>());
                order.open("Surveil 2", "Put any number into your graveyard and the rest back in any order.", rowsIn,
                    new String[] { "Top of library", "Graveyard" }, new String[] { "drawn first", "" }, r -> { });
                break;
            }
            case "triggers": {
                List<Snapshot.CardV> trig = new ArrayList<>();
                String[][] tr = {
                    { "Lyra Dawnbringer", "You gain 7 life" },
                    { "Resplendent Angel", "If you gained 5+ life, create a 4/4 Angel" },
                    { "Giada, Font of Hope", "Put a +1/+1 counter on each Angel" },
                };
                int id = 980;
                for (String[] x : tr) {
                    Snapshot.CardV c = new Snapshot.CardV(id++, x[0], "", true, false);
                    c.stackText = x[1];
                    trig.add(c);
                }
                List<List<Snapshot.CardV>> rowsIn = new ArrayList<>();
                rowsIn.add(trig);
                order.open("Order triggers", "These trigger at the same time. Put them on the stack in order.", rowsIn,
                    new String[] { "Stack order" }, new String[] { "resolves last" }, r -> { });
                break;
            }
            case "mulligan": {
                List<Snapshot.CardV> h = new ArrayList<>(snap.hand);
                int id = 1000;
                for (String n : new String[] { "Plains", "Sol Ring", "Command Tower" }) h.add(new Snapshot.CardV(id++, n, "", false, !n.equals("Sol Ring")));
                mulligan.openKeep(h, 0, keep -> System.out.println("UI3D: keep " + keep));
                break;
            }
            case "bottom": {
                List<Snapshot.CardV> h = new ArrayList<>(snap.hand);
                int id = 1010;
                for (String n : new String[] { "Plains", "Sol Ring", "Command Tower" }) h.add(new Snapshot.CardV(id++, n, "", false, !n.equals("Sol Ring")));
                mulligan.openBottom(h, 1, cards -> { });
                break;
            }
            case "menu":
                openMenu();
                break;
            case "toast": {
                // A refused click on a hand card, as it would read.
                Drawn fake = new Drawn();
                fake.card = snap.hand.isEmpty() ? new Snapshot.CardV(1, "Lyra Dawnbringer", "", true, false) : snap.hand.get(0);
                toast(whyNot(fake), Gdx.graphics.getWidth() / 2f - 60f, 250f);
                break;
            }
            case "target": {
                // Swords to Plowshares from the hand, at any opponent's creature.
                Targeting tg = new Targeting();
                tg.sourceId = 901;
                tg.what = "creature an opponent controls";
                for (Snapshot.SeatV s : snap.seats) {
                    if (s.isYou) continue;
                    for (Snapshot.CardV c : s.creatures) tg.legal.add(c.id);
                }
                targeting = tg;
                break;
            }
            case "attack": {
                CombatPlan cp = new CombatPlan(false);
                Snapshot.SeatV me = snap.you();
                for (Snapshot.CardV c : me.creatures) if (!c.tapped) cp.mine.add(c.id);
                // Two already assigned, so the look shows arcs without anyone dragging.
                int k = 0;
                for (Snapshot.CardV c : me.creatures) {
                    if (k == 0) cp.attackSeat.put(c.id, 1);
                    if (k == 1) cp.attackSeat.put(c.id, 2);
                    k++;
                }
                combatPlan = cp;
                break;
            }
            case "block": {
                CombatPlan cp = new CombatPlan(true);
                for (Snapshot.CardV c : snap.you().creatures) cp.mine.add(c.id);
                for (Snapshot.CardV c : snap.seats.get(1).creatures) cp.attackers.add(c.id);
                if (!snap.you().creatures.isEmpty() && !snap.seats.get(1).creatures.isEmpty()) {
                    cp.blockOn.put(snap.you().creatures.get(0).id, snap.seats.get(1).creatures.get(0).id);
                }
                combatPlan = cp;
                break;
            }
            case "mana": {
                ManaPlan mp = new ManaPlan();
                mp.cost = "{2}{W}{W}";
                mp.total = 4;
                for (Snapshot.CardV c : snap.you().lands) if (!c.tapped) mp.usable.add(c.id);
                if (!mp.usable.isEmpty()) mp.tapped.add(mp.usable.iterator().next());
                manaPlan = mp;
                break;
            }
            case "damage": {
                Snapshot.SeatV me = snap.you();
                Snapshot.CardV lyra = null;
                for (Snapshot.CardV c : me.creatures) if (c.name.startsWith("Lyra")) lyra = c;
                List<Snapshot.CardV> bl = new ArrayList<>();
                for (Snapshot.SeatV s : snap.seats) if (!s.isYou && !s.creatures.isEmpty() && bl.size() < 2) bl.add(s.creatures.get(0));
                int[] lethal = new int[bl.size()];
                java.util.Arrays.fill(lethal, 4);
                damage.open(lyra != null ? lyra : me.creatures.get(0), 7, bl, lethal, true, (a, p) -> { });
                break;
            }
            default:   // "x"
                numbers.open("Choose X", "Wrath of the Skies: X energy, then pay any amount.", 0, 9, 3,
                    x -> "you pay {" + x + "}{W}{W}  -  total " + (x + 2) + " mana", v -> { });
        }
    }

    void openDemoSearch() {
        List<Snapshot.CardV> all = new ArrayList<>(snap.hand);
        for (Snapshot.SeatV s : snap.seats) {
            all.addAll(s.creatures);
            all.addAll(s.others);
            all.addAll(s.lands);
        }
        while (all.size() < 24 && !all.isEmpty()) all.addAll(new ArrayList<>(all));   // pad the grid
        search.open("Search your library", "Choose up to two cards.", all, 0, 2,
            chosen -> System.out.println("UI3D: search chose " + chosen.size() + " card(s)"));
    }

    void handleInput() {
        // The pad first, so its selection is settled before anything reads what is hovered.
        Pad.poll(Gdx.graphics.getDeltaTime());
        // The mouse is never taken away by the controller: moving it hands control straight back.
        // Without this a pointer resting over a card would fight the selection every frame.
        int mouseAt = Gdx.input.getX() * 4001 + Gdx.input.getY();
        if (mouseAt != lastMouseAt) { lastMouseAt = mouseAt; padSelecting = false; }
        // Which pair of things the face buttons mean this frame.
        Pad.panelMode(panelOpen());
        // While the bug dialog is open the 3D window reads nothing. libGDX polls GLFW key state
        // instead of consuming events, so without this, typing a report also passed priority and
        // ended turns underneath it. Pad reads go through the same gate.
        if (bug.isAsking()) return;
        padBindings();
        // The cog is always live, so a bug can be reported whatever else is happening. It opens
        // a proper text dialog rather than an in-window field, so there is no typing state here.
        if (clickedCog()) { bug.ask(snap); return; }
        // HELP, next to the cog: the keys on screen. Checked before anything else can take the
        // click, the same way the cog is, so it works whatever else is happening.
        if (clickedHelp()) { help.toggle(); return; }
        if (help.isOpen() && Keys3D.just(Input.Keys.ESCAPE)) { help.close(); return; }
        // A search owns the keyboard and mouse while it is up, otherwise ESC would both close the
        // panel and release the camera, and a click meant for a card would also move the view.
        syncBoardModes();
        // A yes/no the 3D window owns takes the keys first: Forge is no longer showing this
        // question, so SPACE must answer it rather than pass priority underneath it. Above the
        // early return below, because an Ask now counts as a panel - putting it after meant the
        // question could be read and not answered at all.
        if (Gui3D.pendingAsk() != null) {
            if (Keys3D.just(Input.Keys.SPACE)) Gui3D.answerAsk(true);
            if (Keys3D.just(Input.Keys.X)) Gui3D.answerAsk(false);
            if (Keys3D.just(Input.Keys.H)) { Handoff.handBack(); }
            return;
        }
        if (panelOpen()) {
            // H on a search shown in 3D: show it in Forge's window as well (Gui3D.mirrorOffTable).
            if (Keys3D.just(Input.Keys.H)) Gui3D.releaseSelectables();
            return;
        }
        // The board modes own clicks and ESC while they are on: a click on a glowing card picks,
        // taps or targets it instead of playing it. They consume the click edge, so nothing below
        // can also play a card from the same click; camera keys further down still work. Wired to
        // Forge (a real game), every pick is a guarded click Forge judges; in the demo they are local.
        if (combatPlan != null && handleCombatPlan()) return;
        if (manaPlan != null) {
            if (Keys3D.just(Input.Keys.ESCAPE)) {
                if (manaPlan.wired) Gui3D.pressCancel(); else manaPlan = null;
                return;
            }
            if (clickedCard() && hovered != null && manaPlan.usable.contains(hovered.card.id)) {
                Integer id = hovered.card.id;
                if (manaPlan.wired) {
                    if (Gui3D.clickCard(id) && !manaPlan.tapped.contains(id)) manaPlan.tapped.add(id);
                } else {
                    if (manaPlan.tapped.contains(id)) manaPlan.tapped.remove(id);
                    else if (manaPlan.tapped.size() < manaPlan.total) manaPlan.tapped.add(id);
                    if (manaPlan.tapped.size() >= manaPlan.total) {
                        System.out.println("UI3D: paid with " + manaPlan.tapped + " (demo)");
                        manaPlan = null;
                    }
                }
            }
        }
        if (targeting != null) {
            if (Keys3D.just(Input.Keys.ESCAPE)) {
                if (targeting.wired) Gui3D.pressCancel(); else targeting = null;
                return;
            }
            if (clickedCard()) {
                if (hovered != null && targeting.legal.contains(hovered.card.id)) {
                    Integer id = hovered.card.id;
                    if (targeting.wired) {
                        if (Gui3D.clickCard(id)) {
                            if (targeting.picked.contains(id)) targeting.picked.remove(id); else targeting.picked.add(id);
                        }
                    } else {
                        if (targeting.picked.contains(id)) targeting.picked.remove(id);
                        else if (targeting.picked.size() < targeting.max) targeting.picked.add(id);
                        if (targeting.picked.size() >= targeting.max) {
                            System.out.println("UI3D: targets chosen " + targeting.picked + " (demo)");
                            targeting = null;
                        }
                    }
                } else if (hovered == null && hoveredSeat >= 0 && targeting.wired) {
                    Gui3D.clickPlayer(hoveredSeat);        // a player as the target
                } else if (hovered != null) {
                    toast("Not a legal target", Gdx.input.getX(), Gdx.graphics.getHeight() - Gdx.input.getY());
                }
            }
        }
        if (Keys3D.just(Input.Keys.S)) { openDemoSearch(); return; }
        // -Dforge.ui3d.openPile=<seat>,graveyard|exile: opens that pile once, for screenshot checks.
        if (OPEN_PILE != null && !pileOpened && frameNo > 30 && snap.seats.size() > 0) {
            pileOpened = true;
            String[] p = OPEN_PILE.split(",");
            Snapshot.SeatV s = snap.seats.get(Integer.parseInt(p[0].trim()) % snap.seats.size());
            List<Snapshot.CardV> pile = p.length > 1 && p[1].trim().startsWith("e") ? s.exile : s.graveyard;
            search.open(s.name + "'s " + (pile == s.exile ? "exile" : "graveyard") + " (" + pile.size() + ")",
                "Most recent first. Close when you have read it.", new ArrayList<>(pile), 0, 0, chosen -> { });
            return;
        }
        // -Dforge.ui3d.openPanel=modes|colour|player|x: opens a sample OptionPanel / NumberPicker
        // once, for screenshot checks of panels that are not wired to Forge yet.
        if (OPEN_PANEL != null && !panelShown && frameNo > 30) {
            panelShown = true;
            openSamplePanel(OPEN_PANEL.trim().toLowerCase());
            return;
        }
        // SPACE passes priority / confirms, from here rather than from Forge's window. Only when
        // a game is actually running: in the demo nothing is installed and SPACE still steps the
        // phase, so the two never contend for the key.
        // SPACE is the main button, whatever it says this moment - answering a question,
        // letting the stack resolve, or ending the step. It goes through nextPhase() so it
        // always clears an auto-pass first: the key you press most should never be the one
        // that quietly hands the rest of your turn away.
        if (Keys3D.just(Input.Keys.SPACE) && Gui3D.active()) {
            if (!Gui3D.nextPhase()) {
                System.out.println("UI3D: nothing to confirm right now");
                toast("Nothing to confirm right now", Gdx.input.getX(), Gdx.graphics.getHeight() - Gdx.input.getY());
            }
        }
        if (Keys3D.just(Input.Keys.X) && Gui3D.active()) Gui3D.pressCancel();
        // N, or the RIGHT arrow: done here, move on by one step. Clears any auto-pass first,
        // so it is also the way out if something armed one by accident.
        if ((Keys3D.just(Input.Keys.N) || Keys3D.just(Input.Keys.RIGHT)) && Gui3D.active()) {
            if (!Gui3D.nextPhase()) toast("Nothing to pass right now", Gdx.input.getX(),
                Gdx.graphics.getHeight() - Gdx.input.getY());
        }
        // Left-click a card Forge says is playable right now. The click is only sent for cards
        // already in the selectable set, so the 3D side is choosing among Forge's own legal
        // options and can never offer a play the rules disallow.
        // The prompt bar's buttons take the click before the board does, or pressing OK would
        // also try to play whatever card happens to be behind it.
        if (Gui3D.active() && Gui3D.pendingAsk() == null) {
            float mx = Gdx.input.getX(), my = Gdx.graphics.getHeight() - Gdx.input.getY();
            if (inRect(okBtn, mx, my) || inRect(cancelBtn, mx, my) || (nextOn && inRect(nextBtn, mx, my))) {
                if (clickedCard()) {
                    if (inRect(okBtn, mx, my)) Gui3D.pressOk();
                    else if (inRect(cancelBtn, mx, my)) Gui3D.pressCancel();
                    else Gui3D.nextPhase();
                }
                return;
            }
        }
        boolean boardClick = clickedCard();
        // A click on an empty graveyard or exile pad browses that pile (any seat's: both are public).
        if (boardClick && hovered == null && openPileUnderMouse()) return;
        if (Keys3D.just(Input.Keys.L)) showLog = !showLog;
        if (boardClick && Gui3D.active()) {
            if (hovered != null) {
                // Remembered so targeting mode can draw its arc from the spell being cast.
                lastCastId = hovered.card.id;
                if (strayLandTap(hovered)) {
                    toast("Tapping " + hovered.card.name + " now only floats its mana - it empties at the end of"
                        + " the step. Click again to tap it.", Gdx.input.getX(), Gdx.graphics.getHeight() - Gdx.input.getY());
                } else if (!Gui3D.clickCard(hovered.card.id)) {
                    System.out.println("UI3D: " + hovered.card.name + " is not selectable right now");
                    toast(whyNot(hovered), Gdx.input.getX(), Gdx.graphics.getHeight() - Gdx.input.getY());
                }
            } else if (hoveredSeat >= 0) {
                // Picking a player: who starts, or a seat as a target.
                if (!Gui3D.clickPlayer(hoveredSeat)) {
                    System.out.println("UI3D: could not select that player");
                }
            }
        }
        // H gives the decision the game is waiting on back to Forge's window. Harmless when
        // nothing has been taken over, which is the case until step 3.
        if (Keys3D.just(Input.Keys.H)) {
            Handoff.handBack();
            Gui3D.releaseSelectables();
            System.out.println("UI3D: handing the current decision back to Forge");
        }
        if (clickedFollowBox()) { followTurn = !followTurn; lastActiveSeat = activeSeat(); }
        // Keys for the three things that were click-only. Nothing should need a mouse: a
        // controller cannot reach a tickbox in a corner, and neither can someone playing from
        // the keyboard (the owner, 2026-10-21).
        if (Keys3D.just(Input.Keys.F1)) help.toggle();
        if (Keys3D.just(Input.Keys.F2)) { bug.ask(snap); return; }
        if (Keys3D.just(Input.Keys.F)) { followTurn = !followTurn; lastActiveSeat = activeSeat(); }
        if (Keys3D.just(Input.Keys.R)) {
            askResponses = !askResponses;
            toast(askResponses ? "Checking with you for responses" : "Skipping to your turn",
                Gdx.graphics.getWidth() / 2f, Gdx.graphics.getHeight() * 0.72f);
        }
        if (clickedRespondBox()) { askResponses = !askResponses; }
        autoPassOthersTurns();
        if (Keys3D.just(Input.Keys.M) && cues != null) cues.toggleMute();
        if (Keys3D.just(Input.Keys.TAB)) {
            manualOverride = true;
            manualValue = lift < 0.5f;       // toggle to whichever pose we are not in
        }
        // The number pad. 1-6 are the fixed views; 7-9 are yours - SHIFT saves where the
        // camera is standing, the key alone goes back there. TAB is still a quick look at the
        // other pose; this is where you decide to live.
        boolean shift = Keys3D.down(Input.Keys.SHIFT_LEFT) || Keys3D.down(Input.Keys.SHIFT_RIGHT);
        float toastX = Gdx.graphics.getWidth() / 2f, toastY = Gdx.graphics.getHeight() * 0.72f;
        for (int k = 1; k <= LAST_SLOT; k++) {
            if (!Keys3D.just(Input.Keys.NUMPAD_0 + k)) continue;
            if (isSlot(k)) {
                if (shift) {
                    saveSlot(k);
                    toast("View " + k + " saved - press " + k + " to come back", toastX, toastY);
                } else if (recallSlot(k)) {
                    toast("View " + k, toastX, toastY);
                } else {
                    toast("View " + k + " is empty - SHIFT + " + k + " saves this one", toastX, toastY);
                }
            } else if (k < TableCamera.VIEWS.length) {
                String name = applyView(k);
                if (name != null) toast("View " + k + ": " + name, toastX, toastY);
            }
        }
        // ESC: releases a camera held with TAB, as before; otherwise it opens the menu.
        if (Keys3D.just(Input.Keys.ESCAPE)) {
            if (manualOverride) manualOverride = false;
            else { openMenu(); return; }
        }

        int n = snap.seats.size();
        if (n == 0) return;
        // Right-drag orbits freely round the table (left button is kept for clicking cards in
        // stage 3). It takes over from following the turn, like the arrow keys do.
        // Middle button: grab the table and move it. The drag is converted into the camera's own
        // left/right and forward directions, so it always follows the mouse whichever way round
        // the table you are sitting, and it scales with eye height - at the overhead pose a pixel
        // covers far more table than it does seated, and a fixed rate feels wrong at both.
        if (Gdx.input.isButtonPressed(Input.Buttons.MIDDLE)) {
            int dx = Gdx.input.getDeltaX(), dy = Gdx.input.getDeltaY();
            if (dx != 0 || dy != 0) {
                float rate = 0.004f * Math.max(1f, cam.position.y);
                Vector3 right = rotateY(1f, 0f, orbit);
                Vector3 fwd = rotateY(0f, -1f, orbit);
                panX -= right.x * dx * rate;
                panZ -= right.z * dx * rate;
                panX += fwd.x * dy * rate;
                panZ += fwd.z * dy * rate;
                float d = (float) Math.sqrt(panX * panX + panZ * panZ);
                if (d > PAN_LIMIT) { panX *= PAN_LIMIT / d; panZ *= PAN_LIMIT / d; }
            }
        }
        if (Gdx.input.isButtonPressed(Input.Buttons.RIGHT)) {
            int dx = Gdx.input.getDeltaX();
            if (dx != 0) {
                followTurn = false;
                orbit -= dx * ORBIT_PER_PIXEL;
                orbitTarget = orbit;
            }
        }
        // Walking round the table moved to A and D, and back to your seat to S, so the arrow
        // keys are free for the game itself - RIGHT is now "next phase", which is the thing you
        // press most (the owner, 2026-10-21). Seats are laid out anticlockwise, so stepping +1
        // moves the camera to the LEFT on screen: the keys match the direction you see, not the
        // index order.
        if (Keys3D.just(Input.Keys.A)) step(+1, n);
        if (Keys3D.just(Input.Keys.D)) step(-1, n);
        if (Keys3D.just(Input.Keys.W)) { manualOverride = true; manualValue = true; }
        // S (or HOME) is the way back to your own seat, at the default zoom.
        if (Keys3D.just(Input.Keys.S) || Keys3D.just(Input.Keys.HOME)) {
            zoom = 1f;
            panX = panZ = 0f;
            manualOverride = false;
            setViewSeat(snap.youIndex(), n);
        }
    }

    /**
     * If the mouse is over a graveyard or exile pad, opens that pile in the search panel in browse
     * mode (nothing to pick) and returns true. The pads are scenery, not cards, so this is a ray
     * hit on the table plane, turned into the seat's own frame and checked against the two pads
     * placeZones() lays out (k = 1 graveyard, k = 2 exile; k = 0 is the library, which stays shut).
     */
    boolean openPileUnderMouse() {
        if (search == null || snap.seats.isEmpty()) return false;
        Ray ray = cam.getPickRay(Gdx.input.getX(), Gdx.input.getY());
        if (Math.abs(ray.direction.y) < 1e-4f) return false;
        float t = -ray.origin.y / ray.direction.y;
        if (t <= 0f) return false;
        float hx = ray.origin.x + ray.direction.x * t, hz = ray.origin.z + ray.direction.z * t;
        int n = snap.seats.size();
        for (int i = 0; i < n; i++) {
            Vector3 local = rotateY(hx, hz, -seatAngle(i, n));    // into seat i's frame
            for (int k = 1; k <= 2; k++) {
                float cz = SEAT_DIST + 1.7f - k * 1.45f;
                if (Math.abs(local.x - 5.3f) > CARD_W * 0.5f || Math.abs(local.z - cz) > CARD_H * 0.5f) continue;
                Snapshot.SeatV s = snap.seats.get(i);
                List<Snapshot.CardV> pile = k == 1 ? s.graveyard : s.exile;
                String what = k == 1 ? "graveyard" : "exile";
                String title = s.name + "'s " + what + " (" + pile.size() + ")";
                search.open(title, pile.isEmpty() ? "Nothing here." : "Most recent first. Close when you have read it.",
                    new ArrayList<>(pile), 0, 0, chosen -> { });
                return true;
            }
        }
        return false;
    }

    /**
     * The controller, mapped onto the things the keyboard and mouse already do.
     *
     * Nothing here is a new action: every binding calls the same method a key or a click calls,
     * so a pad can never drift out of step with the rest of the table. The left stick moves the
     * pointer (see Pad), which is what makes A a click on whatever is under it.
     *
     *   left stick  the pointer        A   click whatever is under it
     *   D-pad       the arrow keys     Start   ENTER (confirm)      B   ESCAPE (close / back)
     *   X           hand the current decision back to Forge
     *   Y           help
     *   LB / RB     walk round the table
     *   L3          back to your own seat     R3  overhead toggle
     *   triggers    zoom out / in
     *   right stick orbit (x) and overhead-lift (y)
     *
     * The first three lines are not implemented here: they are keys, mapped in Pad.justAs and
     * read through Keys3D, so they work in every panel without any panel knowing.
     */
    /**
     * Move the selection with the left stick, in the order the cards are drawn - your hand, then
     * the boards round the table. One step per flick, so it snaps between cards instead of
     * sliding a pointer at them.
     */
    void padSelection() {
        if (drawn.isEmpty()) return;
        int step = Pad.flickX();
        if (step == 0) step = Pad.flickY() * 4;    // up and down jump a handful, for a long row
        if (step == 0) return;
        padSelecting = true;
        int at = -1;
        for (int i = 0; i < drawn.size(); i++) {
            Drawn d = drawn.get(i);
            if (d.card != null && d.card.id == padSelectedId) { at = i; break; }
        }
        int size = drawn.size();
        int next = at < 0 ? (step > 0 ? 0 : size - 1) : ((at + step) % size + size) % size;
        Drawn d = drawn.get(next);
        padSelectedId = d.card == null ? -1 : d.card.id;
    }

    void padBindings() {
        if (!Pad.connected()) return;
        int n = snap.seats.size();
        padSelection();

        // B (escape) and the D-pad (arrows) arrive through Keys3D now, so they are not repeated
        // here - a second copy would fire the action twice.
        // Only on the board: while a panel is up, X is that panel's second button
        // (Pad.panelMode), and handing the decision back from under it would be a
        // surprise rather than a choice.
        if (!panelOpen() && Pad.just(org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_X)) {
            Handoff.handBack();
            System.out.println("UI3D: handing the current decision back to Forge (pad)");
        }
        if (Pad.just(org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_Y)) help.toggle();
        if (Pad.just(org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_BACK)) { bug.ask(snap); return; }
        // Start is ENTER (confirm) via Keys3D; the overhead toggle moved to R3 so the two do
        // not share a button.
        if (Pad.just(org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_RIGHT_THUMB)) {
            manualOverride = true;
            manualValue = lift < 0.5f;
        }
        if (n > 0) {
            if (Pad.just(org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_LEFT_BUMPER)) step(+1, n);
            if (Pad.just(org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_RIGHT_BUMPER)) step(-1, n);
            if (Pad.just(org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_LEFT_THUMB)) {
                zoom = 1f;
                panX = panZ = 0f;
                manualOverride = false;
                setViewSeat(snap.youIndex(), n);
            }
        }

        float dt = Gdx.graphics.getDeltaTime();
        // Orbiting takes over from following the turn, exactly as the arrow keys and right-drag do.
        float rx = Pad.rightX();
        if (rx != 0f) {
            followTurn = false;
            orbit -= rx * 2.2f * dt;
            orbitTarget = orbit;
        }
        // Right stick up lifts towards the overhead pose and holds it there, so it is a dial
        // rather than the toggle Start gives you.
        float ry = Pad.rightY();
        if (ry != 0f) {
            manualOverride = true;
            manualValue = ry < 0f;
        }
        float z = Pad.rightTrigger() - Pad.leftTrigger();
        if (z != 0f) zoom = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, zoom * (1f - z * 1.4f * dt)));
    }

    /**
     * With "check with me for responses" off, pass the priority we are given on somebody else's
     * turn, and keep passing until our own turn comes round.
     *
     * Deliberately narrow. It only ever acts when:
     *   - it is NOT our turn (our own turn is never passed for us - that is the bug this is
     *     modelled on, not a feature to reproduce),
     *   - no panel or question is up (a real choice still stops the game, whatever this says),
     *   - Forge is offering the ordinary pass, not a named action like "Attack",
     *   - and once per prompt, because pressOk is queued onto the Swing thread and sending it
     *     every frame would stack up answers to a decision that has already moved on.
     */
    void autoPassOthersTurns() {
        if (askResponses || !Gui3D.active() || panelOpen() || Gui3D.pendingAsk() != null) return;
        Snapshot.SeatV me = snap.you();
        if (me == null || !me.hasPriority || me.active) return;
        Gui3D.Prompt p = Gui3D.PROMPT;
        if (!p.okEnabled) return;
        if (!p.okLabel.isEmpty() && !"OK".equalsIgnoreCase(p.okLabel)) return;   // a named question
        Object token = p.token;
        if (token == null || token == lastAutoPassed) return;
        lastAutoPassed = token;
        Gui3D.pressOk();
    }

    /** True on the frame the left button goes down on the responses tickbox. */
    boolean clickedRespondBox() {
        boolean down = Gdx.input.isButtonPressed(Input.Buttons.LEFT) || Pad.clickHeld();
        boolean justDown = down && !leftWasDownRespond;
        leftWasDownRespond = down;
        if (!justDown) return false;
        float mx = Gdx.input.getX(), my = Gdx.graphics.getHeight() - Gdx.input.getY();
        return mx >= respondBox[0] && mx <= respondBox[0] + respondBox[2]
            && my >= respondBox[1] && my <= respondBox[1] + respondBox[3];
    }

    /** True on the frame the left button goes down on the HELP button. */
    boolean clickedHelp() {
        // A on the pad counts as the left button, so every picker, pad and panel that already
        // works from a click works from the controller with no second code path.
        boolean down = Gdx.input.isButtonPressed(Input.Buttons.LEFT) || Pad.clickHeld();
        boolean justDown = down && !leftWasDownHelp;
        leftWasDownHelp = down;
        return justDown && help.overButton(Gdx.input.getX(), Gdx.graphics.getHeight() - Gdx.input.getY());
    }

    /** True on the frame the left button goes down on the bug cog. */
    boolean clickedCog() {
        // A on the pad counts as the left button, so every picker, pad and panel that already
        // works from a click works from the controller with no second code path.
        boolean down = Gdx.input.isButtonPressed(Input.Buttons.LEFT) || Pad.clickHeld();
        boolean justDown = down && !leftWasDownCog;
        leftWasDownCog = down;
        return justDown && bug.overCog(Gdx.input.getX(), Gdx.graphics.getHeight() - Gdx.input.getY());
    }

    /** True on the frame the left button goes down anywhere except the phase panel. */
    boolean clickedCard() {
        // A on the pad counts as the left button, so every picker, pad and panel that already
        // works from a click works from the controller with no second code path.
        boolean down = Gdx.input.isButtonPressed(Input.Buttons.LEFT) || Pad.clickHeld();
        boolean justDown = down && !leftWasDownBoard;
        leftWasDownBoard = down;
        if (!justDown) return false;
        float mx = Gdx.input.getX(), my = Gdx.graphics.getHeight() - Gdx.input.getY();
        // The panel owns its own clicks; a click on the tickbox must not also cast a card behind it.
        return !(mx >= PP_X && mx <= PP_X + PP_W && my >= Gdx.graphics.getHeight() - 14f - panelHeight());
    }

    /** True on the frame the left button goes down inside the follow-turn tickbox. */
    boolean clickedFollowBox() {
        // A on the pad counts as the left button, so every picker, pad and panel that already
        // works from a click works from the controller with no second code path.
        boolean down = Gdx.input.isButtonPressed(Input.Buttons.LEFT) || Pad.clickHeld();
        boolean justDown = down && !leftWasDown;
        leftWasDown = down;
        if (!justDown) return false;
        float mx = Gdx.input.getX(), my = Gdx.graphics.getHeight() - Gdx.input.getY();
        return mx >= followBox[0] && mx <= followBox[0] + followBox[2]
            && my >= followBox[1] && my <= followBox[1] + followBox[3];
    }

    /**
     * What the player is pointing at: the card under the mouse, or - when the controller is the
     * thing being used - the one the selection has been flicked to.
     *
     * Both end up in `hovered`, which every click, hover panel and targeting path already reads,
     * so the controller needs nothing of its own anywhere else. The selection is held as a card
     * id rather than an index, because the board is rebuilt every frame and an index would be
     * pointing at a different card the moment anything moved.
     *
     * The mouse is never taken away: moving it hands control straight back (see handleInput).
     */
    void pick() {
        hovered = null;
        hoveredSeat = -1;
        if (panelOpen()) return;
        if (padSelecting) {
            for (Drawn d : drawn) {
                if (d.card != null && d.card.id == padSelectedId) { hovered = d; return; }
            }
            padSelectedId = -1;        // it has left the board; fall through to the mouse
        }
        Ray ray = cam.getPickRay(Gdx.input.getX(), Gdx.input.getY());
        float best = Float.MAX_VALUE;
        Vector3 hit = new Vector3();
        for (Drawn d : drawn) {
            if (!com.badlogic.gdx.math.Intersector.intersectRayBounds(ray, d.bounds, hit)) continue;
            float dist = hit.dst2(cam.position);
            if (dist < best) { best = dist; hovered = d; }
        }
        // -Dforge.ui3d.hover=<card name>: with screenshot mode, checks the hover panel without
        // anyone moving a mouse over the window.
        if (hovered == null && FORCE_HOVER != null) {
            for (Drawn d : drawn) if (FORCE_HOVER.equalsIgnoreCase(d.card.name)) { hovered = d; break; }
        }
        // Seats are only considered when no card is under the cursor, so a card lying over a
        // monitor still wins - the card is the more specific thing the player meant.
        if (hovered != null) return;
        for (int i = 0; i < seatPick.size(); i++) {
            if (!com.badlogic.gdx.math.Intersector.intersectRayBounds(ray, seatPick.get(i), hit)) continue;
            float dist = hit.dst2(cam.position);
            if (dist < best) { best = dist; hoveredSeat = i; }
        }
    }
}
