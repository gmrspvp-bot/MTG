# Builds the Bahamut model for ui3d\models as .g3dj.
#
#   summon bahamut.g3dj
#
# Summon: Bahamut - {9} 9/9 Enchantment Creature Saga Dragon with flying, whose last chapter is
# Mega Flare. One file: it is a creature the whole time it is on the battlefield, so there is no
# second form to swap to (models\README.md, "Cards that are two different things").
#
# Reared up on its hind legs with the tail coiled on the ground, rather than in flight. Two
# reasons. A flying pose on a model that is placed standing on a card looks like a mistake, since
# the table has no way to lift it off the surface. And a coiled tail gives a nine-mana creature a
# wide, heavy base, which is most of what makes it read as massive next to Garnet's 2/2 - the
# loader scales every model to the same height, so weight is the only size cue a model controls.
#
# The chest core is lit. Mega Flare is the chapter everyone is waiting for, and a dragon with a
# glowing chest reads as charging something from right across the table.

import math, os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))   # modelkit sits beside this file
from modelkit import (Mesh, mat, tube, strut, spine, sphere, blade, membrane, quad,
                      add, sub, mul, norm, lerp, write, report)

OUT = os.path.dirname(os.path.abspath(__file__))


SCALE  = mat("scale",  (0.16, 0.17, 0.30), (0.05, 0.05, 0.10), (0.44, 0.48, 0.70), 42.0)
BELLY  = mat("belly",  (0.52, 0.50, 0.44), (0.18, 0.17, 0.15), (0.34, 0.33, 0.30), 20.0)
HORN   = mat("horn",   (0.72, 0.70, 0.62), (0.26, 0.25, 0.22), (0.56, 0.55, 0.50), 36.0)
CLAW   = mat("claw",   (0.08, 0.08, 0.10), (0.02, 0.02, 0.03), (0.40, 0.42, 0.50), 50.0)
MEMB   = mat("memb",   (0.22, 0.17, 0.34), (0.07, 0.05, 0.12), (0.30, 0.26, 0.44), 18.0,
             opacity=0.92)
CORE   = mat("core",   (0.58, 0.88, 1.00), (0.48, 0.82, 1.00), (0.92, 0.98, 1.00), 64.0,
             emissive=(0.44, 0.80, 1.00))
EYE    = mat("eye",    (1.00, 0.86, 0.42), (0.92, 0.74, 0.30), (1.00, 0.96, 0.70), 60.0,
             emissive=(0.86, 0.66, 0.18))


def build():
    m = Mesh()

    # --- tail, coiled on the ground ----------------------------------------
    # Laid down first because it is the base everything else stands on.
    m.part("tail", "scale")
    TAIL = [(0.000, 0.430, -0.140), (0.020, 0.310, -0.330), (0.090, 0.180, -0.510),
            (0.210, 0.095, -0.620), (0.360, 0.060, -0.585), (0.455, 0.050, -0.455),
            (0.448, 0.046, -0.320), (0.370, 0.042, -0.232)]
    spine(m, TAIL, [0.115, 0.098, 0.082, 0.066, 0.052, 0.038, 0.026, 0.010])

    # A fin down the last third of it, so the tail ends in something rather than tapering out.
    m.part("tailfin", "memb")
    for i in range(3, 7):
        up = (0.0, 1.0, 0.0)
        blade(m, TAIL[i], add(TAIL[i], (0.0, 0.085 - 0.014 * i, 0.0)),
              norm(sub(TAIL[i + 1], TAIL[i])), 0.055, 0.030, 0.006)

    # --- hind legs ----------------------------------------------------------
    m.part("legs", "scale")
    for sx in (-1.0, 1.0):
        hip   = (sx * 0.150, 0.470, -0.075)
        knee  = (sx * 0.215, 0.285,  0.080)
        ankle = (sx * 0.172, 0.105, -0.035)
        toe   = (sx * 0.168, 0.040,  0.115)
        strut(m, hip, knee, 0.088, 0.062)
        strut(m, knee, ankle, 0.062, 0.046)
        strut(m, ankle, toe, 0.046, 0.040)
    m.part("claws", "claw")
    for sx in (-1.0, 1.0):
        for dz, dx in ((0.055, 0.042), (0.062, 0.000), (0.055, -0.042)):
            root = (sx * 0.168 + dx, 0.040, 0.115)
            strut(m, root, add(root, (dx * 0.6, -0.012, dz)), 0.015, 0.004, seg=6)

    # --- torso --------------------------------------------------------------
    m.part("torso", "scale")
    BODY = [(0.0, 0.395, -0.130), (0.0, 0.500, -0.085), (0.0, 0.610, -0.030),
            (0.0, 0.710, 0.015), (0.0, 0.805, 0.045), (0.0, 0.870, 0.052)]
    spine(m, BODY, [0.165, 0.198, 0.205, 0.188, 0.150, 0.118], seg=12)

    # The lighter front, laid over the chest as a separate shell so the belly is not the same
    # colour as the back - which is how every real animal is built and how none of them read
    # when you skip it.
    m.part("belly", "belly")
    for i in range(1, 5):
        p = BODY[i]
        q = BODY[i + 1]
        r = (0.205, 0.188, 0.150, 0.118)[i - 1] * 0.74
        blade(m, add(p, (0.0, 0.0, r * 0.95)), add(q, (0.0, 0.0, r * 0.88)),
              (1.0, 0.0, 0.0), r * 0.80, r * 0.70, 0.012)

    m.part("core", "core")
    sphere(m, 0.0, 0.735, 0.150, 0.062, seg=14, rings=9, sz=0.70)

    # --- neck and head -------------------------------------------------------
    m.part("neck", "scale")
    NECK = [quad((0.0, 0.860, 0.050), (0.0, 1.030, 0.055), (0.0, 1.185, 0.250), i / 6.0)
            for i in range(7)]
    spine(m, NECK, [0.108, 0.098, 0.088, 0.080, 0.072, 0.064, 0.058], seg=11)

    m.part("head", "scale")
    sphere(m, 0.0, 1.218, 0.300, 0.078, seg=14, rings=10, sy=0.86, sz=1.30)
    m.part("snout", "scale")
    strut(m, (0.0, 1.212, 0.350), (0.0, 1.182, 0.498), 0.058, 0.034, seg=10)
    m.part("jaw", "belly")
    strut(m, (0.0, 1.168, 0.342), (0.0, 1.150, 0.470), 0.040, 0.022, seg=8)
    m.part("eyes", "eye")
    for sx in (-1.0, 1.0):
        sphere(m, sx * 0.052, 1.246, 0.338, 0.017, seg=8, rings=6)

    # Horns: a big swept pair off the back of the skull and a smaller pair under them.
    m.part("horns", "horn")
    for sx in (-1.0, 1.0):
        base = (sx * 0.052, 1.272, 0.272)
        mid  = (sx * 0.098, 1.372, 0.160)
        tip  = (sx * 0.112, 1.428, 0.022)
        strut(m, base, mid, 0.026, 0.017, seg=7)
        strut(m, mid, tip, 0.017, 0.005, seg=7)
        b2 = (sx * 0.062, 1.206, 0.252)
        strut(m, b2, (sx * 0.112, 1.252, 0.148), 0.016, 0.004, seg=6)

    # The ridge down the back of the neck - cheap, and it stops the neck reading as a pipe.
    m.part("ridge", "horn")
    for i in range(1, 6):
        p = NECK[i]
        d = norm(sub(NECK[i], NECK[i - 1]))
        out = norm((0.0, d[2], -d[1]))        # perpendicular to the neck, in the YZ plane
        blade(m, p, add(p, mul(out, 0.052 + 0.006 * i)), (1.0, 0.0, 0.0), 0.030, 0.012, 0.006)

    # --- wings ----------------------------------------------------------------
    # Spread and raised. Held wide rather than folded, because folded wings on a 2 cm figure are
    # a lump on its back and the card says flying.
    for sx in (-1.0, 1.0):
        root = (sx * 0.135, 0.862, -0.020)
        spars = [(sx * 0.300, 1.300, -0.100), (sx * 0.560, 1.140, -0.265),
                 (sx * 0.620, 0.845, -0.385), (sx * 0.498, 0.545, -0.425)]
        m.part("wingbone_%d" % (sx > 0), "horn")
        for s in spars:
            strut(m, root, s, 0.034, 0.012, seg=7)
        m.part("membrane_%d" % (sx > 0), "memb")
        membrane(m, root, spars, steps=5, sag=0.15)
        # A claw at the elbow of the leading spar, which is the detail that says dragon rather
        # than bat.
        m.part("wingclaw_%d" % (sx > 0), "claw")
        elbow = lerp(root, spars[0], 0.82)
        strut(m, elbow, add(elbow, (sx * 0.035, 0.070, 0.030)), 0.014, 0.003, seg=6)

    return m, [SCALE, BELLY, HORN, CLAW, MEMB, CORE, EYE], "bahamut"


if not os.path.isdir(OUT):
    raise SystemExit("no models folder at " + OUT)

mesh, mats, node = build()
name = "summon bahamut.g3dj"
report(name, mesh, write(os.path.join(OUT, name), mesh, mats, node))
