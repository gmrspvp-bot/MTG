// Java agent: as Forge classes load, renames selected methods to <name>$orig and adds a
// new <name> that forwards to one of our hook classes (which call the original through a
// MethodHandle). Uses the copy of ASM inside the JDK (needs --add-exports
// java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED at compile and run time).
// Packaged as scripts\tools\evalcache-agent.jar; run-sims.ps1 adds it with -javaagent
// unless -EvalCache:$false.
//
//   forge.ai.ComputerUtil.evaluateBoardPosition / aiLifeInDanger -> BoardEvalCache (speed)
//   forge.ai.AiAttackController.declareAttackers                -> AttackHook (Captain America, human-like targets)
//   forge.ai.ComputerUtil.wantMulligan                          -> HumanHooks (human-like mulligans)
//   forge.ai.AiBlockController.assignBlockersForCombat          -> BlockHook (commanders not thrown away)
//   forge.game.Match.startGame(Game, Runnable)                  -> PlayHook (desktop games: AI helpers + game log)
//   forge.ai.ComputerUtil.choosePermanentsToSacrifice           -> SacrificeHook (tokens before real cards, commander last)
//   forge.gamemodes.match.input.InputPassPriority.onOk          -> ConfirmPassHook (desktop: "Pass anyway?", counterspell reminder)
//   forge.gamemodes.match.input.InputAttack.onOk                -> AttackConfirmHook (desktop: "Skip combat anyway?")
//   forge.player.PlayerControllerHuman.pushActionableCards     -> ActionableCardsHook (pay-mana highlight race: retry, no crash window)
//   forge.trackable.Tracker.addDelayedPropChange              -> TrackerSyncHook (network sync race: thread-safe change list)
//   forge.ImageCache.scaleImage                               -> ImageScaleHook (no picture below 3x3 instead of an error)
//   forge.game.phase.PhaseHandler.onPhaseBegin                  -> CombatGuardHook (no crash if a rollback left no Combat)
//   forge.game.ability.effects.RollDiceEffect.rollDiceForPlayer
//     / rollAction, forge.game.trigger.TriggerRolledDie.performTest -> DiceIgnoreHook (house rule: ignored dice still seen)
//   forge.game.ability.effects.VillainousChoiceEffect.resolve  -> VillainousChoiceHook (rule 701.55b: no impossible options)
//   forge.player.PlayerControllerHuman.concede                  -> ConcedeHook (house rule: sit out until next upkeep)

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassVisitor;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.Type;

public class EvalCacheAgent {
    /** {class, method, descriptor, hook class, "static" or "instance"} */
    static final String[][] HOOKS = {
        {"forge/ai/ComputerUtil", "evaluateBoardPosition", "(Lforge/game/player/Player;Lforge/game/player/Player;)I", "BoardEvalCache", "static"},
        {"forge/ai/ComputerUtil", "aiLifeInDanger", "(Lforge/game/player/Player;ZI)Z", "BoardEvalCache", "static"},
        {"forge/ai/AiAttackController", "declareAttackers", "(Lforge/game/combat/Combat;)I", "AttackHook", "instance"},
        {"forge/ai/ComputerUtil", "wantMulligan", "(Lforge/game/player/Player;I)Z", "HumanHooks", "static"},
        {"forge/ai/AiBlockController", "assignBlockersForCombat", "(Lforge/game/combat/Combat;)V", "BlockHook", "instance"},
        {"forge/game/Match", "startGame", "(Lforge/game/Game;Ljava/lang/Runnable;)V", "PlayHook", "instance"},
        {"forge/ai/ComputerUtil", "choosePermanentsToSacrifice",
         "(Lforge/game/player/Player;Lforge/game/card/CardCollectionView;ILforge/game/spellability/SpellAbility;ZZ)Lforge/game/card/CardCollection;",
         "SacrificeHook", "static"},
        // A cast that is begun and then undone - Forge restores the game and says nothing to
        // the player, which is seven clicks on a card that will not go down (2026-10-27).
        {"forge/game/GameActionUtil", "rollbackAbility",
         "(Lforge/game/spellability/SpellAbility;Lforge/game/zone/Zone;ILforge/game/cost/CostPayment;Lforge/game/card/Card;)V",
         "RollbackHook", "static"},
        {"forge/gamemodes/match/input/InputPassPriority", "onOk", "()V", "ConfirmPassHook", "instance"},
        {"forge/gamemodes/match/input/InputAttack", "onOk", "()V", "AttackConfirmHook", "instance"},
        // Forge's pay-mana prompt racing the game thread (crash window, 2026-10-22): retried.
        {"forge/player/PlayerControllerHuman", "pushActionableCards", "(Z)V", "ActionableCardsHook", "instance"},
        // Forge's network sync reading the delayed-change list while the game writes it (2026-10-26).
        {"forge/trackable/Tracker", "addDelayedPropChange", "(Lforge/trackable/TrackableObject;Lforge/trackable/TrackableProperty;Ljava/lang/Object;)V", "TrackerSyncHook", "instance"},
        // Forge's window resizing card pictures through 2-3 pixels when it comes back (2026-10-26).
        {"forge/ImageCache", "scaleImage", "(Ljava/lang/String;IIZLforge/game/card/CardView;)Ljava/awt/image/BufferedImage;", "ImageScaleHook", "static"},
        {"forge/game/phase/PhaseHandler", "onPhaseBegin", "()V", "CombatGuardHook", "instance"},
        // Who arms the auto-pass that keeps eating the owner's turns (AutoPassHook). Logs a
        // stack trace the first few times and changes nothing else.
        {"forge/gamemodes/match/YieldController", "setAutoPassUntilEndOfTurn", "(Z)V", "AutoPassHook", "instance"},
        // Joining someone else's game: lets the 3D table wrap the client's GUI (ClientHook).
        {"forge/GuiDesktop", "getNewGuiGame", "()Lforge/gui/interfaces/IGuiGame;", "ClientHook", "instance"},
        // House rule: a die an "ignore the lowest" effect throws away still triggers roll watchers (DiceIgnoreHook).
        {"forge/game/ability/effects/RollDiceEffect", "rollDiceForPlayer",
         "(Lforge/game/spellability/SpellAbility;Lforge/game/player/Player;IIIILjava/util/List;Z)I", "DiceIgnoreHook", "static"},
        {"forge/game/ability/effects/RollDiceEffect", "rollAction",
         "(IIILjava/util/List;Ljava/util/List;Ljava/util/Map;Ljava/util/Set;Lforge/game/player/Player;Ljava/util/Map;)Ljava/util/List;",
         "DiceIgnoreHook", "static"},
        {"forge/game/trigger/TriggerRolledDie", "performTest", "(Ljava/util/Map;)Z", "DiceIgnoreHook", "instance"},
        // Rule 701.55b: a villainous choice offers only the options that player can carry out.
        {"forge/game/ability/effects/VillainousChoiceEffect", "resolve", "(Lforge/game/spellability/SpellAbility;)V",
         "VillainousChoiceHook", "instance"},
        // House rule: a player who concedes sits out until their next upkeep, then leaves.
        {"forge/player/PlayerControllerHuman", "concede", "()V", "ConcedeHook", "instance"},
        // A seat the AI is holding can be taken by whoever joins next (SeatClaimHook). Two
        // halves: let the login in, then hand the live Player back to a human controller.
        {"forge/gamemodes/net/server/FServerManager$RegisterClientHandler", "channelRead",
         "(Lio/netty/channel/ChannelHandlerContext;Ljava/lang/Object;)V",
         "forge/gamemodes/net/server/SeatClaimHook", "instance-object"},
        {"forge/gamemodes/net/server/RemoteClient", "swapChannel", "(Lio/netty/channel/Channel;)V",
         "forge/gamemodes/net/server/SeatClaimHook", "instance"},
    };

    public static void premain(String args, Instrumentation inst) {
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> cls, ProtectionDomain pd, byte[] bytes) {
                boolean ours = false;
                for (String[] h : HOOKS) { if (h[0].equals(name)) ours = true; }
                if (!ours) return null;
                try {
                    byte[] out = rewrite(name, bytes);
                    System.out.println("EvalCacheAgent: hooks installed in " + name.replace('/', '.'));
                    return out;
                } catch (Throwable t) {
                    System.out.println("EvalCacheAgent: FAILED for " + name + ", left unchanged (" + t + ")");
                    return null;
                }
            }
        });
    }

    static byte[] rewrite(String owner, byte[] bytes) {
        ClassReader cr = new ClassReader(bytes);
        ClassWriter cw = new ClassWriter(cr, 0);
        final boolean[] found = new boolean[HOOKS.length];
        final int[] access = new int[HOOKS.length];
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM8, cw) {
            @Override
            public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
                for (int i = 0; i < HOOKS.length; i++) {
                    if (HOOKS[i][0].equals(owner) && HOOKS[i][1].equals(name) && HOOKS[i][2].equals(desc)) {
                        found[i] = true;
                        access[i] = acc;
                        return super.visitMethod(acc, name + "$orig", desc, sig, ex);
                    }
                }
                return super.visitMethod(acc, name, desc, sig, ex);
            }

            @Override
            public void visitEnd() {
                for (int i = 0; i < HOOKS.length; i++) {
                    if (found[i]) addForwarder(cw, owner, HOOKS[i], access[i]);
                }
                super.visitEnd();
            }
        };
        cr.accept(cv, 0);
        for (int i = 0; i < HOOKS.length; i++) {
            if (HOOKS[i][0].equals(owner) && !found[i]) throw new IllegalStateException(HOOKS[i][1] + " not found");
        }
        return cw.toByteArray();
    }

    /**
     * static:          <name>(args)  { return Hook.<name>(args); }
     * instance:        <name>(args)  { return Hook.<name>(this, args); }
     * instance-object: the same, but the hook declares `this` as Object.
     *
     * The third exists because a hook cannot always NAME the class it is hooking. Forge's
     * FServerManager$RegisterClientHandler is a PRIVATE inner class, so no source file anywhere
     * - not even one in the same package - may write its type, and javac refuses the method
     * signature "instance" would need (SeatClaimHook, 2026-10-28). Bytecode has no such
     * objection: the receiver is pushed exactly as before and simply widens to Object at the
     * call. Only use it where the named form will not compile; the named form is checked.
     */
    static void addForwarder(ClassVisitor cv, String owner, String[] hook, int access) {
        String name = hook[1], desc = hook[2], hookClass = hook[3];
        boolean isStatic = "static".equals(hook[4]);
        boolean asObject = "instance-object".equals(hook[4]);
        MethodVisitor mv = cv.visitMethod(access, name, desc, null, null);
        mv.visitCode();
        int slot = 0;
        String hookDesc = desc;
        if (!isStatic) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            slot = 1;
            hookDesc = "(" + (asObject ? "Ljava/lang/Object;" : "L" + owner + ";") + desc.substring(1);
        }
        for (Type t : Type.getArgumentTypes(desc)) {
            mv.visitVarInsn(t.getOpcode(Opcodes.ILOAD), slot);
            slot += t.getSize();
        }
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, hookClass, name, hookDesc, false);
        mv.visitInsn(Type.getReturnType(desc).getOpcode(Opcodes.IRETURN));
        mv.visitMaxs(slot, slot);
        mv.visitEnd();
    }
}
