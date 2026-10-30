# Builds the Kratos model for ui3d\models as .g3dj.
#
#   kratos stoic father.g3dj
#
# Kratos, Stoic Father - {2}{R}{W} 4/4 Legendary God Warrior, Partner: Father & Son.
#
# Built as the heavy half of a pair: wide shoulders, short neck, weight low, axe held down and
# still. Atreus is built narrow and high-headed against exactly these proportions, because the
# loader scales both models to the same height and proportion is the only size cue a model has
# (models\README.md, "Size").
#
# THE AXE HEAD IS ITS OWN MESH PART, "axe", with its own material "axeglow", and nothing else in
# this file uses that material. That is deliberate and the table depends on it: Figures.glow()
# raises the emissive of exactly that material when Kratos and Atreus are on the battlefield at
# the same time. A model file cannot react to the game - it has no way to know anything - so the
# most it can do is name the part that should react and leave the deciding to the table. If you
# rename the material here, rename it in Figures.PAIR_GLOW too or the glow silently stops.

import math, os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))   # modelkit sits beside this file
from modelkit import (Mesh, mat, lathe, tube, strut, sphere, blade, facet_gem,
                      add, sub, mul, norm, write, report)

OUT = os.path.dirname(os.path.abspath(__file__))


ASH    = mat("ash",    (0.80, 0.78, 0.74), (0.30, 0.29, 0.28), (0.24, 0.23, 0.22), 10.0)
MARK   = mat("mark",   (0.62, 0.10, 0.08), (0.22, 0.03, 0.02), (0.30, 0.10, 0.08), 12.0)
BEARD  = mat("beard",  (0.42, 0.26, 0.16), (0.14, 0.08, 0.05), (0.18, 0.12, 0.08), 12.0)
KILT   = mat("kilt",   (0.30, 0.22, 0.17), (0.10, 0.07, 0.05), (0.14, 0.11, 0.08), 10.0)
PELT   = mat("pelt",   (0.34, 0.27, 0.21), (0.12, 0.09, 0.07), (0.10, 0.08, 0.06), 6.0)
STRAP  = mat("strap",  (0.20, 0.15, 0.12), (0.06, 0.05, 0.04), (0.12, 0.10, 0.08), 8.0)
BRASS  = mat("brass",  (0.74, 0.58, 0.26), (0.24, 0.19, 0.08), (0.90, 0.78, 0.42), 42.0)
HAFT   = mat("haft",   (0.36, 0.28, 0.20), (0.12, 0.09, 0.06), (0.18, 0.14, 0.10), 14.0)
# The one material the table touches. Frost-blue at rest, lit when the pair is out together.
AXEGLOW = mat("axeglow", (0.70, 0.80, 0.88), (0.26, 0.31, 0.36), (0.94, 0.98, 1.00), 66.0,
              emissive=(0.06, 0.10, 0.14))


def build():
    m = Mesh()

    # --- legs and war kilt ---------------------------------------------------
    m.part("legs", "ash")
    for sx in (-1.0, 1.0):
        strut(m, (sx * 0.105, 0.470, 0.000), (sx * 0.118, 0.250, 0.015), 0.078, 0.060)
        strut(m, (sx * 0.118, 0.250, 0.015), (sx * 0.112, 0.045, -0.010), 0.060, 0.050)
    m.part("feet", "strap")
    for sx in (-1.0, 1.0):
        sphere(m, sx * 0.112, 0.040, 0.020, 0.062, seg=10, rings=7, sy=0.55, sz=1.35)

    m.part("kilt", "kilt")
    KILTP = [(0.168, 0.330), (0.196, 0.400), (0.206, 0.470), (0.198, 0.540), (0.182, 0.580)]
    def strips(a, y):
        return 1.0 + 0.060 * math.sin(a * 9) + 0.022 * math.sin(a * 18 + 0.5)
    m.grid(lathe(KILTP, 30, strips))

    # --- torso ---------------------------------------------------------------
    # Broad at the shoulders, barely narrowed at the waist: a wall, not an hourglass.
    TORSO = [(0.000, 0.540), (0.178, 0.552), (0.190, 0.620), (0.204, 0.700),
             (0.222, 0.780), (0.238, 0.850), (0.228, 0.905), (0.170, 0.942),
             (0.104, 0.962), (0.090, 0.988), (0.000, 0.996)]
    m.part("torso", "ash")
    m.grid(lathe(TORSO, 28))

    # The red mark, laid over the chest as its own slab. It is the one thing on him that is not
    # grey, and at 2 cm it is the whole of "this is Kratos" before the axe registers.
    m.part("mark", "mark")
    blade(m, (0.0, 0.930, 0.196), (0.0, 0.620, 0.200), (1.0, 0.0, 0.0), 0.062, 0.030, 0.010)
    blade(m, (-0.012, 0.930, 0.196), (-0.118, 1.012, 0.112), (0.0, 0.0, 1.0), 0.030, 0.016, 0.009)

    m.part("head", "ash")
    sphere(m, 0.0, 1.052, 0.010, 0.088, seg=16, rings=11, sy=1.08, sz=1.00)

    # Beard: the bottom half of the head, pushed forward. Bald above it, so the silhouette of the
    # head is a sphere with a wedge hanging off the front - which is how you tell him from Atreus
    # at a distance even when the hood is down.
    m.part("beard", "beard")
    sphere(m, 0.0, 0.996, 0.050, 0.080, seg=14, rings=9, sy=1.10, sz=0.92)

    # --- bear pelt over the right shoulder, shield on the left forearm --------
    m.part("pelt", "pelt")
    sphere(m, 0.212, 0.906, -0.010, 0.120, seg=14, rings=9, sy=0.74)
    sphere(m, 0.176, 0.790, -0.072, 0.092, seg=12, rings=8, sy=1.10, sz=0.72)

    m.part("arms", "ash")
    # Right arm down the haft, left arm across the body behind the shield.
    strut(m, (0.212, 0.880, 0.000), (0.252, 0.736, 0.052), 0.070, 0.058)
    strut(m, (0.252, 0.736, 0.052), (0.212, 0.598, 0.112), 0.058, 0.048)
    strut(m, (-0.212, 0.880, 0.000), (-0.248, 0.742, 0.040), 0.070, 0.058)
    strut(m, (-0.248, 0.742, 0.040), (-0.150, 0.676, 0.148), 0.058, 0.048)
    m.part("hands", "ash")
    sphere(m, 0.206, 0.586, 0.118, 0.048, seg=10, rings=7)
    sphere(m, -0.146, 0.670, 0.154, 0.048, seg=10, rings=7)

    m.part("shield", "brass")
    # A disc standing on edge, facing +Z: tube with almost no depth, seen face-on from the front.
    tube(m, -0.150, 0.190, 0.600, 0.640, 0.150, 0.150, seg=20)
    m.part("shieldboss", "strap")
    sphere(m, -0.150, 0.620, 0.214, 0.044, seg=10, rings=7, sz=0.50)

    # --- the Leviathan Axe, held head-down at his right -----------------------
    m.part("haft", "haft")
    tube(m, 0.206, 0.128, 0.300, 0.760, 0.024, 0.022, seg=10)
    m.part("pommel", "brass")
    sphere(m, 0.206, 0.776, 0.128, 0.032, seg=10, rings=7)
    m.part("collar", "brass")
    tube(m, 0.206, 0.128, 0.296, 0.322, 0.038, 0.034, seg=12, cap_bot=False)

    # The head: a broad single bit, its edge facing forward, plus a small rune stone. Both are
    # "axeglow" and nothing else is.
    m.part("axe", "axeglow")
    blade(m, (0.206, 0.288, 0.128), (0.206, 0.108, 0.128), (0.0, 0.0, 1.0), 0.098, 0.062, 0.020)
    blade(m, (0.206, 0.198, 0.206), (0.206, 0.198, 0.300), (0.0, 1.0, 0.0), 0.088, 0.040, 0.014)
    facet_gem(m, 0.206, 0.252, 0.128, 0.026, sy=1.1)

    return m, [ASH, MARK, BEARD, KILT, PELT, STRAP, BRASS, HAFT, AXEGLOW], "kratos"


if not os.path.isdir(OUT):
    raise SystemExit("no models folder at " + OUT)

mesh, mats, node = build()
name = "kratos stoic father.g3dj"
report(name, mesh, write(os.path.join(OUT, name), mesh, mats, node))
