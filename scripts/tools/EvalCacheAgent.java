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
        {"forge/gamemodes/match/input/InputPassPriority", "onOk", "()V", "ConfirmPassHook", "instance"},
        {"forge/gamemodes/match/input/InputAttack", "onOk", "()V", "AttackConfirmHook", "instance"},
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
     * static:   <name>(args)      { return Hook.<name>(args); }
     * instance: <name>(args)      { return Hook.<name>(this, args); }
     */
    static void addForwarder(ClassVisitor cv, String owner, String[] hook, int access) {
        String name = hook[1], desc = hook[2], hookClass = hook[3];
        boolean isStatic = "static".equals(hook[4]);
        MethodVisitor mv = cv.visitMethod(access, name, desc, null, null);
        mv.visitCode();
        int slot = 0;
        String hookDesc = desc;
        if (!isStatic) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            slot = 1;
            hookDesc = "(L" + owner + ";" + desc.substring(1);
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
