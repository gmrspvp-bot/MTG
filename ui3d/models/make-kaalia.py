# Builds the Kaalia model for ui3d\models as .g3dj (libGDX's JSON model format).
#
#   kaalia of the vast.g3dj
#
# One file, not two: she is a creature from the moment she lands and never stops being one, so
# there is no "awake" form to swap to (see models\README.md, "Cards that are two different things").
#
# The design decision worth arguing about is the WINGS, which are deliberately mismatched: a
# feathered one on her right, a membraned one on her left. Her art gives her neither - the Angel,
# the Demon and the Dragon are behind her in it, and she is the woman with the sword. But a figure
# is read at table distance from its silhouette alone, and "armoured woman with a sword" is every
# other creature on the board. One wing of each says what the card actually does - she is the door
# those three come through - in the only language a 2 cm figure has.
#
# She stands with the sword planted point-down in front of her, both hands on the hilt: the pose
# before the attack, since her trigger is "whenever Kaalia attacks".

import math, os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))   # modelkit sits beside this file
from modelkit import (Mesh, mat, lathe, tube, strut, sphere, blade, membrane, quad,
                      add, sub, mul, norm, write, report)

OUT = os.path.dirname(os.path.abspath(__file__))


# Her colours are the card's: Mardu red and black, with white-gold where the Angel shows through.
ARMOUR  = mat("armour",  (0.55, 0.10, 0.10), (0.17, 0.03, 0.03), (0.62, 0.30, 0.26), 34.0)
PLATE   = mat("plate",   (0.30, 0.06, 0.07), (0.09, 0.02, 0.02), (0.40, 0.20, 0.18), 26.0)
GOLD    = mat("gold",    (0.86, 0.68, 0.26), (0.28, 0.21, 0.06), (0.98, 0.86, 0.46), 48.0,
              emissive=(0.14, 0.10, 0.01))
SKIN    = mat("skin",    (0.72, 0.54, 0.42), (0.24, 0.17, 0.13), (0.18, 0.14, 0.11), 10.0)
HAIR    = mat("hair",    (0.08, 0.06, 0.07), (0.02, 0.02, 0.02), (0.22, 0.18, 0.20), 18.0)
STEEL   = mat("steel",   (0.62, 0.65, 0.72), (0.20, 0.22, 0.26), (0.92, 0.95, 1.00), 70.0)
FEATHER = mat("feather", (0.92, 0.91, 0.86), (0.42, 0.41, 0.39), (0.70, 0.70, 0.68), 20.0,
              emissive=(0.16, 0.15, 0.13))
BONE    = mat("bone",    (0.34, 0.24, 0.22), (0.11, 0.08, 0.07), (0.26, 0.20, 0.18), 14.0)
WING    = mat("wing",    (0.40, 0.09, 0.11), (0.14, 0.03, 0.04), (0.34, 0.14, 0.14), 16.0,
              opacity=0.93)


# =========================================================================== the figure
def build():
    m = Mesh()

    # --- body ------------------------------------------------------------
    # One surface of revolution from the hem of the tasset skirt to the base of the neck: wide at
    # the ground, cinched at the waist, flared at the cuirass.
    PROFILE = [(0.000, 0.000), (0.300, 0.012), (0.306, 0.070), (0.292, 0.160),
               (0.262, 0.280), (0.232, 0.390), (0.196, 0.455), (0.166, 0.490),
               (0.150, 0.545), (0.172, 0.610), (0.194, 0.672), (0.202, 0.745),
               (0.188, 0.812), (0.148, 0.862), (0.094, 0.900), (0.072, 0.946),
               (0.078, 0.975), (0.000, 0.982)]

    def fold(a, y):
        # Overlapping skirt plates: strong at the hem, gone by the waist.
        w = max(0.0, 1.0 - y / 0.50)
        return 1.0 + 0.045 * w * math.sin(a * 8) + 0.018 * w * math.sin(a * 16 + 0.7)

    m.part("body", "armour")
    m.grid(lathe(PROFILE, 36, fold))

    # A gold band at the waist, where the eye goes first.
    m.part("belt", "gold")
    tube(m, 0.0, 0.0, 0.520, 0.566, 0.158, 0.162, seg=30, cap_top=False, cap_bot=False)

    # The gorget, so the head sits on something rather than floating.
    m.part("gorget", "gold")
    tube(m, 0.0, 0.0, 0.880, 0.918, 0.108, 0.086, seg=24, cap_top=False, cap_bot=False)

    m.part("pauldrons", "plate")
    for x in (-0.205, 0.205):
        sphere(m, x, 0.848, -0.010, 0.108, seg=14, rings=9, sy=0.78)

    m.part("head", "skin")
    sphere(m, 0.0, 1.028, 0.008, 0.082, seg=16, rings=11, sy=1.18, sz=1.02)

    # Hair: a mass behind and beside the head, down to the pauldrons. Two spheres and a fall.
    m.part("hair", "hair")
    sphere(m, 0.0, 1.044, -0.012, 0.094, seg=16, rings=10, sy=1.10, sz=1.00)
    sphere(m, 0.0, 0.930, -0.062, 0.078, seg=14, rings=9, sy=1.30, sz=0.72)

    # --- arms, both hands on the hilt -------------------------------------
    m.part("arms", "plate")
    for sx in (-1.0, 1.0):
        shoulder = (sx * 0.195, 0.830, -0.005)
        elbow    = (sx * 0.205, 0.690,  0.085)
        hand     = (sx * 0.052, 0.628,  0.178)
        strut(m, shoulder, elbow, 0.052, 0.042)
        strut(m, elbow, hand, 0.042, 0.034)
    m.part("hands", "skin")
    for sx in (-1.0, 1.0):
        sphere(m, sx * 0.050, 0.628, 0.178, 0.040, seg=10, rings=7)

    # --- the sword, planted ------------------------------------------------
    # Point down in front of her, which is the pose before the attack rather than during it -
    # her trigger reads "whenever Kaalia of the Vast attacks", so this is the moment before.
    m.part("grip", "bone")
    tube(m, 0.0, 0.185, 0.560, 0.720, 0.026, 0.026, seg=10)
    m.part("pommel", "gold")
    sphere(m, 0.0, 0.742, 0.185, 0.040, seg=12, rings=8)
    m.part("guard", "gold")
    strut(m, (-0.155, 0.548, 0.185), (0.155, 0.548, 0.185), 0.022, 0.022, seg=8)
    m.part("sword", "steel")
    blade(m, (0.0, 0.530, 0.185), (0.0, 0.012, 0.185), (1.0, 0.0, 0.0), 0.062, 0.006, 0.016)

    # --- the Angel's wing, her right (+X) ----------------------------------
    # Five primaries off a curved leading edge, each longer than the last, swept up and back.
    m.part("wingbone_r", "bone")
    R = (0.215, 0.872, -0.040)
    TIP_R = (0.372, 1.284, -0.455)
    CTRL_R = (0.330, 1.000, -0.175)
    pts = [quad(R, CTRL_R, TIP_R, i / 6.0) for i in range(7)]
    for i in range(6):
        strut(m, pts[i], pts[i + 1], 0.028 - 0.003 * i, 0.025 - 0.003 * i, seg=7)

    m.part("feathers", "feather")
    for i in range(1, 7):
        t = i / 6.0
        root = pts[i]
        drop = (0.040 * t, -0.62 - 0.30 * t, -0.52 - 0.26 * t)
        tip = add(root, mul(norm(drop), 0.22 + 0.34 * t))
        blade(m, root, tip, (0.0, 0.0, 1.0), 0.052 + 0.016 * t, 0.012, 0.011)

    # --- the Demon's wing, her left (-X) -----------------------------------
    # Three spars off one root with membrane slung between them, sagging towards the root so the
    # trailing edge is scalloped rather than straight.
    L = (-0.215, 0.872, -0.040)
    SPARS = [(-0.246, 1.280, -0.170), (-0.408, 1.118, -0.395),
             (-0.436, 0.820, -0.520), (-0.356, 0.520, -0.560)]
    m.part("wingbone_l", "bone")
    for s in SPARS:
        strut(m, L, s, 0.030, 0.011, seg=7)

    m.part("membrane", "wing")
    membrane(m, L, SPARS)

    return m, [ARMOUR, PLATE, GOLD, SKIN, HAIR, STEEL, FEATHER, BONE, WING], "kaalia"


if not os.path.isdir(OUT):
    raise SystemExit("no models folder at " + OUT)

mesh, mats, node = build()
name = "kaalia of the vast.g3dj"
report(name, mesh, write(os.path.join(OUT, name), mesh, mats, node))
