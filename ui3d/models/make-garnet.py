# Builds the Garnet model for ui3d\models as .g3dj.
#
#   garnet princess of alexandria.g3dj
#
# Garnet, Princess of Alexandria - {G}{W} 2/2 lifelink, and in the Sigurd deck she is the other
# half of the Saga engine: she takes lore counters OFF your Sagas and becomes bigger for each one.
#
# So she is modelled mid-summon rather than standing still: rod in her right hand, left hand open
# and raised, and three motes rising into that palm - the counters coming off the Sagas and into
# her. That is the card's whole text as a shape, and it is the only thing distinguishing her from
# any other robed figure at 2 cm.
#
# She is small on purpose. A 2/2 next to a 9/9 Bahamut on the same board should not be the same
# size, and the loader normalises every model to the same height, so the only way to say "small"
# is proportion: a narrow figure with a big skirt and a high waist reads as slight and young.

import math, os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))   # modelkit sits beside this file
from modelkit import (Mesh, mat, lathe, tube, strut, sphere, facet_gem, write, report)

OUT = os.path.dirname(os.path.abspath(__file__))


# Selesnya, and her own colours in the game she comes from: white and ivory with a warm trim.
GOWN   = mat("gown",   (0.92, 0.90, 0.84), (0.34, 0.33, 0.31), (0.42, 0.41, 0.38), 16.0)
TRIM   = mat("trim",   (0.78, 0.42, 0.16), (0.26, 0.13, 0.04), (0.70, 0.46, 0.22), 28.0)
LEAF   = mat("leaf",   (0.26, 0.52, 0.28), (0.08, 0.17, 0.09), (0.30, 0.48, 0.32), 18.0)
GOLD   = mat("gold",   (0.86, 0.70, 0.30), (0.28, 0.22, 0.08), (0.98, 0.88, 0.50), 48.0,
             emissive=(0.12, 0.09, 0.01))
SKIN   = mat("skin",   (0.84, 0.66, 0.54), (0.30, 0.22, 0.17), (0.20, 0.16, 0.13), 10.0)
HAIR   = mat("hair",   (0.14, 0.09, 0.07), (0.04, 0.03, 0.02), (0.26, 0.20, 0.16), 20.0)
WOOD   = mat("wood",   (0.40, 0.32, 0.24), (0.13, 0.10, 0.07), (0.16, 0.13, 0.10), 12.0)
# The pendant and the motes are the same material on purpose: what she is holding and what she is
# pulling out of the Sagas are the same kind of thing.
SPARK  = mat("spark",  (0.56, 0.92, 0.78), (0.44, 0.86, 0.70), (0.90, 1.00, 0.96), 60.0,
             emissive=(0.40, 0.88, 0.72))


def build():
    m = Mesh()

    # --- gown --------------------------------------------------------------
    # High waist, wide hem. The waist sits at 0.58 of her height rather than halfway, which is
    # most of what makes a figure read as young rather than merely short.
    PROFILE = [(0.000, 0.000), (0.268, 0.010), (0.272, 0.060), (0.258, 0.150),
               (0.230, 0.262), (0.198, 0.372), (0.166, 0.462), (0.132, 0.530),
               (0.108, 0.580), (0.116, 0.636), (0.128, 0.692), (0.136, 0.748),
               (0.126, 0.800), (0.098, 0.842), (0.062, 0.874), (0.050, 0.906),
               (0.054, 0.930), (0.000, 0.938)]

    def fold(a, y):
        # Soft cloth, not plate: fewer folds than Kaalia's skirt and a gentler amplitude, dying
        # out at the waist.
        w = max(0.0, 1.0 - y / 0.58)
        return 1.0 + 0.032 * w * math.sin(a * 6 + 0.4) + 0.014 * w * math.sin(a * 11)

    m.part("gown", "gown")
    m.grid(lathe(PROFILE, 32, fold))

    # A green overskirt hanging from the waist - the G half of {G}{W}, and it breaks up a figure
    # that is otherwise one colour from hem to collar.
    m.part("overskirt", "leaf")
    OVER = [(0.118, 0.572), (0.158, 0.498), (0.188, 0.420), (0.206, 0.342), (0.210, 0.300)]
    def owave(a, y):
        return 1.0 + 0.055 * math.sin(a * 5 + 1.1)
    m.grid(lathe(OVER, 32, owave))

    m.part("sash", "trim")
    tube(m, 0.0, 0.0, 0.558, 0.604, 0.112, 0.118, seg=28, cap_top=False, cap_bot=False)

    m.part("collar", "gold")
    tube(m, 0.0, 0.0, 0.896, 0.922, 0.060, 0.050, seg=20, cap_top=False, cap_bot=False)

    m.part("head", "skin")
    sphere(m, 0.0, 0.998, 0.006, 0.070, seg=16, rings=11, sy=1.16, sz=1.02)

    # The bob she cuts her hair into: a rounded mass, short at the nape, not a long fall.
    m.part("hair", "hair")
    sphere(m, 0.0, 1.010, -0.008, 0.080, seg=16, rings=10, sy=1.08, sz=1.02)
    sphere(m, 0.0, 0.942, -0.042, 0.062, seg=12, rings=8, sy=0.86, sz=0.70)

    # The pendant, which in her own story is the thing everyone is after.
    m.part("pendant", "spark")
    sphere(m, 0.0, 0.878, 0.052, 0.022, seg=10, rings=7)

    # --- arms --------------------------------------------------------------
    # Right hand down on the rod, left raised with the palm open and up.
    m.part("arms", "gown")
    strut(m, (0.128, 0.790, 0.000), (0.158, 0.672, 0.036), 0.040, 0.034)
    strut(m, (0.158, 0.672, 0.036), (0.148, 0.566, 0.062), 0.034, 0.028)
    strut(m, (-0.128, 0.790, 0.000), (-0.206, 0.712, 0.058), 0.040, 0.034)
    strut(m, (-0.206, 0.712, 0.058), (-0.168, 0.838, 0.126), 0.034, 0.028)
    m.part("hands", "skin")
    sphere(m, 0.146, 0.556, 0.064, 0.032, seg=10, rings=7)
    sphere(m, -0.166, 0.852, 0.132, 0.034, seg=10, rings=7, sy=0.72)

    # --- the rod -----------------------------------------------------------
    m.part("rod", "wood")
    tube(m, 0.146, 0.064, 0.090, 0.930, 0.019, 0.016, seg=10)
    m.part("rodcap", "gold")
    tube(m, 0.146, 0.064, 0.930, 0.952, 0.030, 0.026, seg=12, cap_bot=False)
    m.part("rodgem", "spark")
    facet_gem(m, 0.146, 1.006, 0.064, 0.040, sy=1.5)

    # --- the summon, three motes rising into her open palm ------------------
    # Her trigger removes a lore counter from each of any number of Sagas. Three is enough to read
    # as "several"; they are spaced along the path so the eye follows them up into the hand.
    m.part("motes", "spark")
    for (x, y, z, r) in ((-0.262, 0.636, 0.168, 0.020),
                         (-0.226, 0.758, 0.182, 0.026),
                         (-0.178, 0.930, 0.166, 0.032)):
        sphere(m, x, y, z, r, seg=10, rings=7)

    return m, [GOWN, TRIM, LEAF, GOLD, SKIN, HAIR, WOOD, SPARK], "garnet"


if not os.path.isdir(OUT):
    raise SystemExit("no models folder at " + OUT)

mesh, mats, node = build()
name = "garnet princess of alexandria.g3dj"
report(name, mesh, write(os.path.join(OUT, name), mesh, mats, node))
