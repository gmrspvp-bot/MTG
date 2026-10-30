# Builds the Atreus model for ui3d\models as .g3dj.
#
#   atreus impulsive son.g3dj
#
# Atreus, Impulsive Son - {1}{U}{R} 2/4 Legendary God Archer with reach, Partner: Father & Son.
#
# The other half of the pair, and built against Kratos on purpose. Both models are scaled to the
# same height by the loader, so "boy" cannot be done with size - it is done with proportion: a
# head nearly as large as his father's on a body two thirds the width, narrow shoulders, the bow
# almost as tall as he is. Put the two side by side and the difference reads instantly; measure
# them and they are the same height.
#
# Reach is in the pose: the bow is up and drawn, not slung. He is the one who shoots.
#
# THE BOWSTRING AND THE NOCKED ARROW ARE THEIR OWN MESH PART, "bow", with their own material
# "bowglow", and nothing else in this file uses that material. Figures.glow() raises its emissive
# when Kratos and Atreus are on the battlefield together. See the note at the top of
# make-kratos.py - a model file cannot know anything about the game, so all it can do is name the
# part and leave the deciding to the table. Rename this material and the glow silently stops.

import math, os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))   # modelkit sits beside this file
from modelkit import (Mesh, mat, lathe, tube, strut, spine, sphere, blade, quad,
                      add, sub, mul, norm, write, report)

OUT = os.path.dirname(os.path.abspath(__file__))


SKIN   = mat("skin",   (0.80, 0.62, 0.50), (0.29, 0.22, 0.18), (0.20, 0.16, 0.13), 10.0)
TUNIC  = mat("tunic",  (0.36, 0.30, 0.24), (0.12, 0.10, 0.08), (0.14, 0.12, 0.09), 9.0)
HOOD   = mat("hood",   (0.26, 0.22, 0.19), (0.09, 0.07, 0.06), (0.11, 0.09, 0.08), 8.0)
WRAP   = mat("wrap",   (0.52, 0.42, 0.30), (0.18, 0.14, 0.10), (0.16, 0.13, 0.09), 10.0)
HAIR   = mat("hair",   (0.30, 0.19, 0.11), (0.10, 0.06, 0.03), (0.22, 0.15, 0.09), 16.0)
WOOD   = mat("wood",   (0.38, 0.26, 0.16), (0.13, 0.09, 0.05), (0.20, 0.15, 0.10), 16.0)
STRAP  = mat("strap",  (0.20, 0.16, 0.13), (0.06, 0.05, 0.04), (0.12, 0.10, 0.08), 8.0)
# The one material the table touches. Pale at rest, lit when the pair is out together.
BOWGLOW = mat("bowglow", (0.76, 0.86, 0.92), (0.30, 0.35, 0.39), (0.96, 0.99, 1.00), 66.0,
              emissive=(0.08, 0.11, 0.14))


def build():
    m = Mesh()

    # --- legs ----------------------------------------------------------------
    m.part("legs", "wrap")
    for sx in (-1.0, 1.0):
        strut(m, (sx * 0.070, 0.420, 0.000), (sx * 0.078, 0.230, 0.012), 0.050, 0.040)
        strut(m, (sx * 0.078, 0.230, 0.012), (sx * 0.074, 0.040, -0.008), 0.040, 0.034)
    m.part("feet", "strap")
    for sx in (-1.0, 1.0):
        sphere(m, sx * 0.074, 0.036, 0.016, 0.044, seg=10, rings=7, sy=0.55, sz=1.30)

    # --- tunic and torso -------------------------------------------------------
    # Narrow. His widest point is the hem of the tunic, not his shoulders, which is the opposite
    # of his father and is most of what makes him look like a child.
    TORSO = [(0.000, 0.330), (0.142, 0.342), (0.150, 0.400), (0.138, 0.470),
             (0.126, 0.545), (0.132, 0.625), (0.140, 0.700), (0.132, 0.760),
             (0.098, 0.802), (0.062, 0.832), (0.052, 0.862), (0.000, 0.870)]
    m.part("tunic", "tunic")
    def creases(a, y):
        w = max(0.0, 1.0 - max(0.0, y - 0.33) / 0.22)
        return 1.0 + 0.040 * w * math.sin(a * 7 + 0.3)
    m.grid(lathe(TORSO, 26, creases))

    m.part("belt", "strap")
    tube(m, 0.0, 0.0, 0.528, 0.564, 0.130, 0.134, seg=22, cap_top=False, cap_bot=False)

    # --- head, hood down over the back ------------------------------------------
    # The head is 0.082 across where Kratos's is 0.088, on a body two thirds his width. That
    # ratio is the whole trick.
    m.part("head", "skin")
    sphere(m, 0.0, 0.930, 0.008, 0.082, seg=16, rings=11, sy=1.06, sz=1.00)
    m.part("hair", "hair")
    sphere(m, 0.0, 0.948, -0.006, 0.088, seg=14, rings=10, sy=0.96, sz=1.00)
    m.part("hood", "hood")
    # Pushed back off his head and bunched at the neck - he has been running.
    sphere(m, 0.0, 0.868, -0.078, 0.092, seg=14, rings=9, sy=1.05, sz=0.80)

    # --- quiver across the back ---------------------------------------------------
    m.part("quiver", "strap")
    strut(m, (-0.118, 0.560, -0.130), (0.110, 0.830, -0.108), 0.048, 0.044, seg=10)
    m.part("arrows", "wood")
    for (dx, dy) in ((0.00, 0.000), (0.028, 0.014), (-0.026, 0.010)):
        strut(m, (0.106 + dx, 0.836 + dy, -0.108), (0.146 + dx, 0.948 + dy, -0.096),
              0.008, 0.007, seg=5)

    # --- arms: left holds the bow out, right draws to the cheek ---------------------
    GRIP = (-0.186, 0.690, 0.158)
    NOCK = (0.060, 0.878, 0.044)
    m.part("arms", "skin")
    strut(m, (-0.128, 0.790, 0.000), (-0.176, 0.738, 0.080), 0.036, 0.030)
    strut(m, (-0.176, 0.738, 0.080), GRIP, 0.030, 0.026)
    strut(m, (0.128, 0.790, 0.000), (0.166, 0.846, -0.060), 0.036, 0.030)
    strut(m, (0.166, 0.846, -0.060), NOCK, 0.030, 0.026)
    m.part("hands", "skin")
    sphere(m, GRIP[0], GRIP[1], GRIP[2], 0.030, seg=10, rings=7)
    sphere(m, NOCK[0], NOCK[1], NOCK[2], 0.030, seg=10, rings=7)

    # --- the bow ---------------------------------------------------------------------
    # Two limbs curving away from the grip, nearly as long as he is tall. Drawn with the string
    # pulled back to the nock, so the string is not straight - which is the shape that says
    # "about to shoot" rather than "carrying a bow".
    TIP_U = (-0.300, 1.118, 0.012)
    TIP_L = (-0.300, 0.262, 0.012)
    m.part("bowlimbs", "wood")
    upper = [quad(GRIP, (-0.272, 0.918, 0.148), TIP_U, i / 7.0) for i in range(8)]
    lower = [quad(GRIP, (-0.272, 0.462, 0.148), TIP_L, i / 7.0) for i in range(8)]
    spine(m, upper, [0.021, 0.020, 0.018, 0.017, 0.015, 0.013, 0.011, 0.007], seg=7)
    spine(m, lower, [0.021, 0.020, 0.018, 0.017, 0.015, 0.013, 0.011, 0.007], seg=7)

    # The string, bent round the nock, and the arrow on it. The whole glowing part.
    m.part("bow", "bowglow")
    strut(m, TIP_U, NOCK, 0.0055, 0.0055, seg=5)
    strut(m, NOCK, TIP_L, 0.0055, 0.0055, seg=5)
    strut(m, NOCK, (-0.252, 0.742, 0.118), 0.009, 0.007, seg=6)        # the shaft
    blade(m, (-0.252, 0.742, 0.118), (-0.316, 0.706, 0.138), (0.0, 1.0, 0.0),
          0.018, 0.003, 0.004)                                          # the head
    for d in ((0.016, 0.020, -0.010), (-0.004, -0.024, 0.018), (-0.014, 0.008, -0.016)):
        blade(m, NOCK, add(NOCK, mul(d, 1.6)), (0.0, 0.0, 1.0), 0.004, 0.012, 0.003)   # fletching

    return m, [SKIN, TUNIC, HOOD, WRAP, HAIR, WOOD, STRAP, BOWGLOW], "atreus"


if not os.path.isdir(OUT):
    raise SystemExit("no models folder at " + OUT)

mesh, mats, node = build()
name = "atreus impulsive son.g3dj"
report(name, mesh, write(os.path.join(OUT, name), mesh, mats, node))
