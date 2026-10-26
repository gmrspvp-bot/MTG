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
     * The ESC menu, built on OptionPanel. The toggles act at once and reopen the menu so their
     * new state shows. Concede and undo ARE wired - they have been since the controller went in,
     * and the note here saying otherwise outlived that by some weeks (corrected 2026-10-26).
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

    /**
     * Owner's joined game, 2026-10-21: Goblin Bombardment asks for its TARGET first and the
     * creature to sacrifice second. Twice a robot clicked as the sacrifice became the target, and
     * the game was lost at 2 life. So while choosing a target for something that hurts (damage,
     * destroy, exile, -N/-N, fight), the first click on one of our own permanents only warns, if
     * anything of an opponent's could have been picked instead; a second click within
     * STRAY_TAP_MS goes through. Something that helps (a pump, a blink) is never held, and nor is
     * a spell that can only target our own things. -Dforge.ui3d.owntargetguard=false turns it off.
     * True = the click was held back.
     */
    boolean ownTargetHeld(Drawn d) {
        if (!OWN_TARGET_GUARD || d.card == null || d.seat == null || !d.seat.isYou || targeting == null) return false;
        if (!harmful()) return false;
        boolean otherLegal = false;
        for (Snapshot.SeatV s : snap.seats) {
            if (s.isYou) continue;
            for (List<Snapshot.CardV> zone : java.util.Arrays.asList(s.creatures, s.others, s.lands)) {
                for (Snapshot.CardV c : zone) if (targeting.legal.contains(c.id)) otherLegal = true;
            }
        }
        String m = targeting.what == null ? "" : targeting.what.toLowerCase();
        if (m.contains("any target") || m.contains("player") || m.contains("opponent")) otherLegal = true;
        if (!otherLegal) return false;
        long now = System.currentTimeMillis();
        if (ownTargetId == d.card.id && now - ownTargetAt < STRAY_TAP_MS) { ownTargetId = -1; return false; }
        ownTargetId = d.card.id;
        ownTargetAt = now;
        return true;
    }

    static final boolean OWN_TARGET_GUARD = !"false".equalsIgnoreCase(System.getProperty("forge.ui3d.owntargetguard", "true"));
    static final java.util.regex.Pattern HARMFUL = java.util.regex.Pattern.compile(
        "deals? [^.]*damage|destroy|exile target|[-−](\\d+|x)/[-−](\\d+|x)|fights?\\b");
    int ownTargetId = -1;
    long ownTargetAt;
    /** What the last target click picked ("Robot Token (yours)", "Alec"), for the sacrifice step. */
    String lastTargetLabel = "";

    @Override String lastTarget() { return lastTargetLabel; }

    /** Does what we are choosing targets for hurt what it hits? Forge's prompt, then the source's text. */
    boolean harmful() {
        String m = targeting == null || targeting.what == null ? "" : targeting.what.toLowerCase();
        if (HARMFUL.matcher(m).find()) return true;
        Snapshot.CardV src = source();
        return src != null && HARMFUL.matcher(src.text.toLowerCase()).find();
    }

    /**
     * The card whose ability or spell wants the target: the card last clicked, but only when
     * Forge's prompt names it - a trigger asks for targets with a stale lastCastId.
     */
    @Override Snapshot.CardV source() {
        if (targeting == null || targeting.what == null) return null;
        Snapshot.CardV c = findCard(lastCastId);
        return c != null && !c.name.isEmpty() && targeting.what.contains(c.name) ? c : null;
    }

    String sourceName() {
        Snapshot.CardV c = source();
        return c == null ? "That" : c.name;
    }

    Snapshot.CardV findCard(int id) {
        for (Snapshot.CardV c : snap.hand) if (c.id == id) return c;
        for (Snapshot.SeatV s : snap.seats) {
            for (List<Snapshot.CardV> zone : java.util.Arrays.asList(s.creatures, s.others, s.lands, s.command)) {
                for (Snapshot.CardV c : zone) if (c.id == id) return c;
            }
        }
        return null;
    }

    /** Forge is asking which permanent to sacrifice as a cost ("Select a creature to sacrifice (1 left)"). */
    @Override boolean sacrificePrompt() {
        String m = Gui3D.PROMPT.message;
        return Gui3D.active() && m != null && m.toLowerCase().contains("to sacrifice") && targeting == null;
    }

    /** The source's cost includes a sacrifice ("Sacrifice a creature: ..."), so one comes after the target. */
    @Override boolean sacrificeComesNext() {
        Snapshot.CardV c = source();
        String t = c == null ? (targeting == null || targeting.what == null ? "" : targeting.what) : c.text;
        t = t.toLowerCase();
        int colon = t.indexOf(':');
        return colon > 0 && t.substring(0, colon).contains("sacrifice");
    }

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
        // The target is remembered for the sacrifice that follows it, and forgotten after.
        if (!"InputSelectTargets".equals(in) && !sacrificePrompt() && changed) lastTargetLabel = "";

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

        // Paying mana: Forge offers the sources that can pay, or failing that, everything of
        // ours that could make mana.
        if (in.startsWith("InputPayMana")) {
            if (manaPlan == null || !manaPlan.wired || changed) {
                ManaPlan mp = new ManaPlan();
                mp.wired = true;
                manaPlan = mp;
            }
            manaPlan.cost = Gui3D.PROMPT.message;
            manaPlan.usable.clear();
            if (!sel.isEmpty()) manaPlan.usable.addAll(sel);
            else if (me != null) {
                // The fallback used to be "untapped lands", which is where Sol Ring went. Forge
                // sends no selectables during a payment, so the lands were the whole list, and a
                // click on any other mana source was dropped here without ever reaching Forge -
                // the Auto button worked because it never comes through the board at all (the
                // owner, 2026-10-22, and "cannot tap artifacts for mana" the night before).
                for (java.util.List<Snapshot.CardV> row
                        : java.util.Arrays.asList(me.lands, me.others, me.creatures)) {
                    for (Snapshot.CardV c : row) if (couldMakeMana(c)) manaPlan.usable.add(c.id);
                }
            }
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
        if (p.blocking) {
            // The attacker being blocked can be stepped through without the mouse, and the one
            // chosen is named in the banner and ringed on the table, so "which attacker am I
            // blocking?" is never a guess.
            if (Keys3D.just(Input.Keys.LEFT) || Pad.justAs(Input.Keys.LEFT)) stepAttacker(-1);
            if (Keys3D.just(Input.Keys.RIGHT) || Pad.justAs(Input.Keys.RIGHT)) stepAttacker(+1);
            if (p.current != Integer.MIN_VALUE && !p.attackers.contains(p.current)) {
                p.current = Integer.MIN_VALUE;       // it left combat
            }
            // Press an attacker: it becomes the one being blocked. Forge is told at once, so its
            // idea of the current attacker and ours can never drift apart.
            if (down && !planLeftWas && hovered != null && p.attackers.contains(hovered.card.id)) {
                chooseAttacker(hovered.card.id);
                planLeftWas = down;
                return false;
            }
        }
        if (down && !planLeftWas && hovered != null && hovered.seat != null && p.mine.contains(hovered.card.id)) {
            p.dragging = hovered.card.id;
        }
        if (!down && planLeftWas && p.dragging != Integer.MIN_VALUE) {
            int id = p.dragging;
            p.dragging = Integer.MIN_VALUE;
            if (p.blocking) {
                Integer was = p.blockOn.get(id);
                if (hovered != null && p.attackers.contains(hovered.card.id)) {
                    // Dragged onto an attacker: that attacker becomes the current one and this
                    // creature blocks it.
                    blockWith(id, hovered.card.id, was);
                } else if (hovered != null && hovered.card.id == id && p.current != Integer.MIN_VALUE
                        && (was == null || was != p.current)) {
                    // A click rather than a drag, with an attacker already chosen: block that
                    // one. This is the half that was missing - with three identical attackers,
                    // dragging an arrow onto the right one of them is the hard way to do
                    // something Forge models as "pick the attacker, then pick the blockers".
                    blockWith(id, p.current, was);
                } else {
                    // Dropped elsewhere, or clicked while already blocking the chosen attacker:
                    // take the block off (the same two clicks toggle it back).
                    if (p.wired && was != null) { Gui3D.clickCard(was); Gui3D.clickCard(id); }
                    if (was != null) {
                        String off = nameOf(id);
                        if (off != null) toast(off + " is not blocking now",
                            Gdx.graphics.getWidth() / 2f, Gdx.graphics.getHeight() * 0.34f);
                    }
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
                // Everything in the pile goes where its top card goes, unless SHIFT is held
                // to move just the one. Dragging nine Robots over one at a time is what this is
                // for; the arrow drawn is the same, the pile behind it simply comes along.
                for (int member : draggedPile(id)) {
                    boolean was = p.attackSeat.containsKey(member);
                    if (seat >= 0 && seat < snap.seats.size() && !snap.seats.get(seat).isYou) {
                        // Forge's InputAttack: click the player (they become the defender), then
                        // the creature (it attacks them). Re-aiming means taking it off first.
                        if (p.wired) {
                            Integer before = p.attackSeat.get(member);
                            if (was && before != null && before != seat) Gui3D.clickCard(member);
                            if (!was || (before != null && before != seat)) {
                                Gui3D.clickPlayer(seat);
                                Gui3D.clickCard(member);
                            }
                        }
                        p.attackSeat.put(member, seat);
                    } else {
                        if (p.wired && was) Gui3D.clickCard(member);   // a second click takes it off
                        p.attackSeat.remove(member);
                    }
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
            // Take the keyboard back the moment a question arrives, once per question. Forge
            // raises its own window to ask, and the keys went with it.
            Object askNow = Gui3D.pendingAsk();
            if (askNow != askFocused) { askFocused = askNow; Bridge.focusWindow(); }
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
            if (spentFloatingMana()) return;
            if (Keys3D.just(Input.Keys.ESCAPE)) {
                if (manaPlan.wired) Gui3D.pressCancel(); else manaPlan = null;
                return;
            }
            // In a real game every click on one of OUR permanents goes to Forge, whether or not
            // the glow says it is usable: the glow is this window's guess, and a guess must never
            // be the thing that stops a click. Forge refuses what it cannot do, and says so in
            // the log. Only the demo, which has no Forge to judge it, holds to the list.
            boolean mine = hovered != null && hovered.seat != null && hovered.seat.isYou;
            if (clickedCard() && hovered != null
                    && (manaPlan.usable.contains(hovered.card.id) || (manaPlan.wired && mine))) {
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
                        if (ownTargetHeld(hovered)) {
                            toast(sourceName() + " would hit YOUR " + hovered.card.name + ". Click it again to do that,"
                                + " or pick an opponent", Gdx.input.getX(), Gdx.graphics.getHeight() - Gdx.input.getY());
                        } else if (Gui3D.clickCard(id)) {
                            if (targeting.picked.contains(id)) targeting.picked.remove(id); else targeting.picked.add(id);
                            lastTargetLabel = hovered.card.name + (hovered.seat == null ? ""
                                : hovered.seat.isYou ? " (yours)" : " (" + hovered.seat.name + "'s)");
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
                    if (Gui3D.clickPlayer(hoveredSeat) && hoveredSeat < snap.seats.size()) {   // a player as the target
                        Snapshot.SeatV s = snap.seats.get(hoveredSeat);
                        lastTargetLabel = s.isYou ? "you" : s.name;
                    }
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
        // V puts a question aside so the board can be read, and brings it back. Checked
        // before everything else so it works from inside a panel as well as from the table.
        if (Keys3D.just(Input.Keys.V) || Pad.justAs(Input.Keys.DOWN)) {
            if (peeking) {
                peeking = false;
            } else if (panelUp()) {
                peeking = true;
                toast("Looking at the board - V brings the question back",
                    Gdx.graphics.getWidth() / 2f, Gdx.graphics.getHeight() * 0.34f);
            }
        }
        if (handArranging()) return;
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
        if (Keys3D.just(Input.Keys.X) && Gui3D.active()) cancelPressed();
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
                    else if (inRect(cancelBtn, mx, my)) cancelPressed();
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
                if (sacrificePrompt()) {
                    // The target is chosen BEFORE the sacrifice, so a player clicked now is a target
                    // meant for a step that has passed. It used to be dropped without a word (owner's
                    // game, 2026-10-21: three clicks on an opponent, then a robot was shot instead).
                    toast("Forge wants the creature to SACRIFICE now - the target was already chosen"
                        + (lastTargetLabel.isEmpty() ? "" : " (" + lastTargetLabel + ")") + ". X cancels if that's wrong",
                        Gdx.input.getX(), Gdx.graphics.getHeight() - Gdx.input.getY());
                } else if (!Gui3D.clickPlayer(hoveredSeat)) {
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
        if (!Gui3D.active()) return;
        Snapshot.SeatV me = snap.you();
        // THE HARD SAFEGUARD. On your own turn nothing passes for you - not this toggle, and not
        // Forge either: any auto-pass it has armed is torn down every frame your turn is running.
        // The owner has lost whole turns to this four separate ways, and asked that a player must
        // go through their own phases by hand (2026-10-21). Checked before the toggle is even
        // read, so no setting can reach past it.
        if (me != null && me.active) {
            if (!Gui3D.autoPassing().isEmpty()) Gui3D.stopAutoPass();
            return;
        }
        if (askResponses || panelOpen() || Gui3D.pendingAsk() != null) return;
        if (me == null || !me.hasPriority) return;
        Gui3D.Prompt p = Gui3D.PROMPT;
        // Only ever the ordinary pass. Declaring blockers is ALSO a prompt whose button reads
        // "OK", and pressing it there means "no blocks": the owner died with sixteen untapped
        // creatures facing a lethal attack because this answered his block step for him
        // (2026-10-22, the whole step lasted under a second). Discards, sacrifices and the like
        // are the same trap.
        //
        // With the owner's refinement (2026-10-26): a block declaration is only a block
        // declaration if somebody is actually attacking you. That matters on a CLIENT, where
        // there is no Input object to name and the window has to guess from the prompt text -
        // anything with "block" in it during the declare-blockers step reads as InputBlock, so a
        // plain priority window in that step would have stalled the skip for a player nobody was
        // attacking. Being attacked is read from the combat itself, not from the words.
        if ("InputBlock".equals(p.inputName)) {
            if (attackersAimedAtMe()) {
                if (!blockHeld) {
                    blockHeld = true;
                    toast("Attackers declared against you - skipping is paused",
                        Gdx.graphics.getWidth() / 2f, Gdx.graphics.getHeight() * 0.56f);
                }
                return;
            }
        } else if (!"InputPassPriority".equals(p.inputName)) {
            return;
        } else {
            blockHeld = false;
        }
        if (!p.okEnabled) return;
        if (!p.okLabel.isEmpty() && !"OK".equalsIgnoreCase(p.okLabel)) return;   // a named question
        Object token = p.token;
        if (token == null || token == lastAutoPassed) return;
        lastAutoPassed = token;
        Gui3D.pressOk();
    }

    /** When the End-turn confirmation was asked for, and how long it stands. */
    private long endTurnAskedAt;
    private static final long END_TURN_CONFIRM_MS = 3000L;

    /**
     * Cancel - which during ordinary priority is NOT "no", it is "end my turn".
     *
     * Forge's cancel on InputPassPriority arms an auto-pass for everything left in the turn.
     * Players press it meaning "no" or "close this", and lose the turn. The stack trace from the
     * owner's game of 2026-10-21 caught it arriving from the network client twice in the first
     * turn, and the same cancel is what quietly declined a Kodama's Reach search earlier.
     *
     * So when cancel would end the turn it asks once. Any other cancel - a real question with a
     * No, a panel - goes straight through, because there it means what it says.
     */
    void cancelPressed() {
        String label = Gui3D.PROMPT.cancelLabel;
        boolean endsTurn = label != null && label.equalsIgnoreCase("End Turn");
        long now = System.currentTimeMillis();
        if (endsTurn && now - endTurnAskedAt > END_TURN_CONFIRM_MS) {
            endTurnAskedAt = now;
            toast("End your turn? Press X again to confirm - it passes everything left",
                Gdx.graphics.getWidth() / 2f, Gdx.graphics.getHeight() * 0.62f);
            return;
        }
        endTurnAskedAt = 0L;
        Gui3D.pressCancel();
    }

    /**
     * Arranging your own hand: G picks the card under the pointer up, left and right place it,
     * SPACE drops it there, ESC puts it back.
     *
     * Returns true while a card is held, and the caller stops there - which is the point of
     * holding one. The arrow keys and SPACE mean something else on the board (next phase, pass),
     * and a mode that quietly passed your turn while you tidied your hand would be worse than no
     * mode at all. Nothing reaches Forge until the drop.
     *
     * On the pad: D-pad UP picks up (nothing else uses it on the board), left and right place,
     * A drops, B cancels.
     */
    boolean handArranging() {
        if (panelOpen() || Gui3D.pendingAsk() != null) { grabbedId = -1; return false; }
        if (grabbedId < 0) {
            boolean pickUp = Keys3D.just(Input.Keys.G) || Pad.justAs(Input.Keys.UP);
            if (!pickUp) return false;
            if (hovered == null || hovered.seat != null) {
                toast("Point at a card in your hand first, then G to move it",
                    Gdx.graphics.getWidth() / 2f, Gdx.graphics.getHeight() * 0.30f);
                return false;
            }
            int at = handIndexOf(hovered.card.id);
            if (at < 0) return false;
            grabbedId = hovered.card.id;
            grabIndex = at;
            return true;
        }
        // The card can leave your hand while it is held - you play it from Forge's window, an
        // opponent makes you discard it - and then there is nothing to move.
        int now = handIndexOf(grabbedId);
        if (now < 0) { grabbedId = -1; return false; }
        int last = Math.max(0, snap.hand.size() - 1);
        if (grabIndex > last) grabIndex = last;

        if (Keys3D.just(Input.Keys.LEFT) || Keys3D.just(Input.Keys.COMMA) || Pad.justAs(Input.Keys.LEFT)) {
            grabIndex = Math.max(0, grabIndex - 1);
        }
        if (Keys3D.just(Input.Keys.RIGHT) || Keys3D.just(Input.Keys.PERIOD) || Pad.justAs(Input.Keys.RIGHT)) {
            grabIndex = Math.min(last, grabIndex + 1);
        }
        // The mouse places it too, so this is a drag for anyone who would rather drag: point at
        // the card you want it to sit on.
        if (hovered != null && hovered.seat == null && hovered.card.id != grabbedId) {
            int over = handIndexOf(hovered.card.id);
            if (over >= 0) grabIndex = over;
        }
        if (Keys3D.just(Input.Keys.ESCAPE) || Pad.justAs(Input.Keys.ESCAPE)) {
            grabbedId = -1;
            return true;
        }
        boolean drop = Keys3D.just(Input.Keys.G) || Keys3D.just(Input.Keys.SPACE)
            || Keys3D.just(Input.Keys.ENTER) || Pad.just(org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_A)
            || clickedCard();
        if (drop) {
            if (grabIndex != now) Gui3D.reorderHand(grabbedId, grabIndex);
            grabbedId = -1;
        }
        return true;
    }

    /** The question we last took the keyboard for, so it is asked for once and not every frame. */
    private Object askFocused;

    /** Said once per combat, not once a frame, when the skip stops for a block declaration. */
    private boolean blockHeld;

    /**
     * Is anything in this combat attacking us, or something of ours?
     *
     * Read from the combat the game published rather than from the prompt's wording, so it is
     * the same answer on the host and on a client. A planeswalker or a battle of ours being
     * attacked counts: those are block declarations too.
     */
    boolean attackersAimedAtMe() {
        int seat = snap.youIndex();
        if (seat < 0) return false;
        for (Snapshot.CombatArc a : snap.combatArcs) {
            if (a.defenderSeat == seat) return true;
            if (a.defenderCardId >= 0 && onMyBattlefield(a.defenderCardId)) return true;
        }
        return false;
    }

    /** True when that card id is a permanent of ours. */
    boolean onMyBattlefield(int id) {
        Snapshot.SeatV me = snap.you();
        if (me == null) return false;
        for (java.util.List<Snapshot.CardV> row
                : java.util.Arrays.asList(me.creatures, me.others, me.lands)) {
            for (Snapshot.CardV c : row) if (c.id == id) return true;
        }
        return false;
    }

    /** Where a card sits in your hand as drawn, or -1 if it is not in it. */
    int handIndexOf(int cardId) {
        for (int i = 0; i < snap.hand.size(); i++) if (snap.hand.get(i).id == cardId) return i;
        return -1;
    }

    /**
     * Could this permanent of ours be tapped for mana right now?
     *
     * A guess, and only ever used when Forge has offered no list of its own - Forge still judges
     * every click, so being wrong here costs a refusal, not a bad play. Lands are in because a
     * basic has no rules text to go on; anything else is in if its text talks about adding mana,
     * which is every signet, talisman, Sol Ring and mana creature.
     */
    static boolean couldMakeMana(Snapshot.CardV c) {
        if (c == null || c.tapped) return false;
        if (c.land) return true;              // before the sick test: a land is never sick
        if (c.sick) return false;             // a mana creature played this turn cannot tap
        String text = c.text == null ? "" : c.text.toLowerCase();
        return text.contains("add {") || text.contains("add one mana") || text.contains("add two mana")
            || text.contains("add three mana") || text.contains("add x mana")
            || text.contains("mana of any color") || text.contains("mana of any colour");
    }

    /**
     * The creatures a drag from `id` speaks for: the whole pile it sits on top of, or just that
     * one when SHIFT is held or it is not a pile.
     *
     * Only ever creatures the plan says may attack - a pile can hold one that is summoning sick
     * or already tapped, and offering those to Forge would have it refuse the lot.
     */
    java.util.List<Integer> draggedPile(int id) {
        java.util.List<Integer> one = java.util.Collections.singletonList(id);
        CombatPlan p = combatPlan;
        if (p == null || p.blocking) return one;
        if (Keys3D.down(Input.Keys.SHIFT_LEFT) || Keys3D.down(Input.Keys.SHIFT_RIGHT)) return one;
        java.util.List<Integer> pile = pileMembers.get(id);
        if (pile == null || pile.size() < 2) return one;
        java.util.List<Integer> out = new java.util.ArrayList<>(pile.size());
        for (int member : pile) if (p.mine.contains(member)) out.add(member);
        return out.isEmpty() ? one : out;
    }

    /**
     * Put `blocker` in front of `attacker`, taking it off whatever it was blocking first.
     *
     * Forge's InputBlock is a two-step: a click on an attacking creature makes it current, and a
     * click on one of your own creatures toggles it as a blocker of THAT attacker. So the order
     * of clicks matters, and a stale current attacker is how blocks end up on the wrong one.
     */
    void blockWith(int blocker, int attacker, Integer was) {
        CombatPlan p = combatPlan;
        if (p == null) return;
        if (p.wired) {
            if (was != null && was != attacker) { Gui3D.clickCard(was); Gui3D.clickCard(blocker); }
            Gui3D.clickCard(attacker);
            Gui3D.clickCard(blocker);
        }
        p.current = attacker;
        p.blockOn.put(blocker, attacker);
        // Say it out loud as well as marking it. The marks are on the cards, which is where you
        // look afterwards; this is for the instant of the click.
        String who = nameOf(blocker), what = nameOf(attacker);
        if (who != null && what != null) {
            toast(who + " blocks " + what, Gdx.graphics.getWidth() / 2f, Gdx.graphics.getHeight() * 0.34f);
        }
    }

    /** A card's name by id, or null when the snapshot has no such card on the battlefield. */
    String nameOf(int id) {
        for (Snapshot.CardV c : allCards()) if (c.id == id) return c.name;
        return null;
    }

    /** Make `id` the attacker that new blocks go on, and tell Forge the same. */
    void chooseAttacker(int id) {
        CombatPlan p = combatPlan;
        if (p == null || p.current == id) return;
        p.current = id;
        if (p.wired) Gui3D.clickCard(id);
        for (Snapshot.CardV c : allCards()) {
            if (c.id != id) continue;
            toast("Blocking " + c.name + " - click your creatures to block it",
                Gdx.graphics.getWidth() / 2f, Gdx.graphics.getHeight() * 0.30f);
            break;
        }
    }

    /** Step through the attackers with the arrow keys or the D-pad, in the order they were declared. */
    void stepAttacker(int by) {
        CombatPlan p = combatPlan;
        if (p == null || p.attackers.isEmpty()) return;
        java.util.List<Integer> order = new java.util.ArrayList<>(p.attackers);
        java.util.Collections.sort(order);
        int at = order.indexOf(p.current);
        int next = at < 0 ? (by > 0 ? 0 : order.size() - 1)
            : ((at + by) % order.size() + order.size()) % order.size();
        chooseAttacker(order.get(next));
    }

    /** Every card the snapshot knows about on the battlefield, for naming one by id. */
    java.util.List<Snapshot.CardV> allCards() {
        java.util.List<Snapshot.CardV> out = new java.util.ArrayList<>();
        for (Snapshot.SeatV s : snap.seats) {
            out.addAll(s.creatures);
            out.addAll(s.others);
            out.addAll(s.lands);
        }
        return out;
    }

    /**
     * Spending the mana already floating in your pool, by clicking its beads.
     *
     * The pool has been on the table since the beads went in and there was no way to touch it -
     * the only thing in the game that could spend floating mana was Forge's own window, so a
     * player at the 3D table could see mana they could not use (the owner, 2026-10-26). Forge's
     * own window spends it by clicking a symbol in the pool; this is the same thing, on the
     * beads.
     *
     * Only your own beads, and only while something is actually being paid for. Forge judges
     * the rest: mana that will not pay this cost is simply refused.
     */
    boolean spentFloatingMana() {
        if (manaPlan == null || !manaPlan.wired || beads.isEmpty()) return false;
        int me = snap.youIndex();
        if (me < 0) return false;
        // A key each, in WUBRGC order, for anyone not using a mouse - and the same six are what
        // the pad's D-pad and A reach through the pointer.
        int[] keys = { Input.Keys.NUM_1, Input.Keys.NUM_2, Input.Keys.NUM_3,
                       Input.Keys.NUM_4, Input.Keys.NUM_5, Input.Keys.NUM_6 };
        for (int m = 0; m < 6; m++) {
            if (Keys3D.just(keys[m])) return spend(m);
        }
        if (!clickedCard()) return false;
        float mx = Gdx.input.getX(), my = Gdx.graphics.getHeight() - Gdx.input.getY();
        TableBase.BeadsAt best = null;
        float bestD = 42f;                        // within this many pixels of the stack
        for (TableBase.BeadsAt b : beads) {
            if (b.seat != me) continue;
            com.badlogic.gdx.math.Vector3 p =
                cam.project(new com.badlogic.gdx.math.Vector3(b.x, b.top, b.z));
            if (p.z > 1f) continue;
            float d = (float) Math.hypot(p.x - mx, p.y - my);
            if (d < bestD) { bestD = d; best = b; }
        }
        if (best == null) return false;
        return spend(best.colour);
    }

    /** Ask Forge to spend one floating mana of WUBRGC index `m`, and say so. */
    private boolean spend(int m) {
        Snapshot.SeatV me = snap.you();
        if (me == null || m < 0 || m >= me.mana.length || me.mana[m] <= 0) return false;
        if (!Gui3D.useMana(forge.card.MagicColor.WUBRGC[m])) return false;
        toast("Spent " + TableHud.MANA_LETTERS[m] + " from your pool",
            Gdx.graphics.getWidth() / 2f, Gdx.graphics.getHeight() * 0.30f);
        return true;
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
        // Put aside, not answered: the board is readable and nothing on it is clickable. A stray
        // click reaching the game through a panel that is not on screen would be worse than the
        // problem this solves.
        if (peeking) return false;
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
