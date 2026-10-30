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
#
# Model conventions, from models\README.md: feet on the ground at the origin, Y up, facing +Z.
# The loader normalises on the Y bounding box, so the absolute height here does not matter.

import json, math, io, os

OUT = os.path.dirname(os.path.abspath(__file__))   # this folder, wherever the clone is


# --------------------------------------------------------------------------- mesh building
class Mesh(object):
    """POSITION + NORMAL, one vertex array, several named parts - the shape g3dj wants."""

    def __init__(self):
        self.v = []            # flat floats: x y z nx ny nz
        self.parts = []        # (id, material, [indices])
        self.cur = None

    def part(self, pid, material):
        self.cur = (pid, material, [])
        self.parts.append(self.cur)

    def vert(self, p, n):
        i = len(self.v) // 6
        self.v.extend([round(c, 5) for c in p])
        self.v.extend([round(c, 5) for c in n])
        return i

    def tri(self, a, b, c):
        self.cur[2].extend([a, b, c])

    def quad_flat(self, p0, p1, p2, p3):
        """A flat-shaded quad - its own four vertices, one face normal. Plate armour wants this."""
        n = normal(p0, p1, p2)
        if n is None:
            return
        a = self.vert(p0, n); b = self.vert(p1, n)
        c = self.vert(p2, n); d = self.vert(p3, n)
        self.tri(a, b, c); self.tri(a, c, d)

    def tri_flat(self, p0, p1, p2):
        n = normal(p0, p1, p2)
        if n is None:
            return
        self.tri(self.vert(p0, n), self.vert(p1, n), self.vert(p2, n))

    def tri_two_sided(self, p0, p1, p2):
        """Both windings. A wing membrane is a surface with no thickness, and whether libGDX is
        culling back faces this frame is not something a model file should depend on."""
        self.tri_flat(p0, p1, p2)
        self.tri_flat(p0, p2, p1)

    def grid(self, pts, closed_u=True):
        """A smooth-shaded (rows x cols) patch; normals come from the surface itself, so the
        skirt's folds get the shading of the folded shape rather than of the cylinder."""
        rows, cols = len(pts), len(pts[0])
        idx = [[0] * cols for _ in range(rows)]
        for i in range(rows):
            for j in range(cols):
                ip, iq = max(0, i - 1), min(rows - 1, i + 1)
                jp = (j - 1) % cols if closed_u else max(0, j - 1)
                jn = (j + 1) % cols if closed_u else min(cols - 1, j + 1)
                du = sub(pts[i][jn], pts[i][jp])
                dv = sub(pts[iq][j], pts[ip][j])
                n = norm(cross(dv, du))
                if n == (0.0, 0.0, 0.0):
                    n = (0.0, 1.0, 0.0)
                idx[i][j] = self.vert(pts[i][j], n)
        for i in range(rows - 1):
            for j in range(cols if closed_u else cols - 1):
                j2 = (j + 1) % cols
                a, b, c, d = idx[i][j], idx[i][j2], idx[i + 1][j2], idx[i + 1][j]
                if dist(pts[i][j], pts[i][j2]) > 1e-6:
                    self.tri(a, b, c)
                if dist(pts[i + 1][j], pts[i + 1][j2]) > 1e-6:
                    self.tri(a, c, d)


def add(a, b):   return (a[0] + b[0], a[1] + b[1], a[2] + b[2])
def sub(a, b):   return (a[0] - b[0], a[1] - b[1], a[2] - b[2])
def mul(a, s):   return (a[0] * s, a[1] * s, a[2] * s)
def lerp(a, b, t): return (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t)
def cross(a, b): return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
def dist(a, b):  return math.sqrt(sum((a[i] - b[i]) ** 2 for i in range(3)))


def norm(v):
    L = math.sqrt(v[0] ** 2 + v[1] ** 2 + v[2] ** 2)
    return (0.0, 0.0, 0.0) if L < 1e-9 else (v[0] / L, v[1] / L, v[2] / L)


def normal(p0, p1, p2):
    n = norm(cross(sub(p1, p0), sub(p2, p0)))
    return None if n == (0.0, 0.0, 0.0) else n


def frame(d):
    """An orthonormal pair across a direction - what a tube between two arbitrary points needs."""
    d = norm(d)
    up = (0.0, 1.0, 0.0) if abs(d[1]) < 0.9 else (1.0, 0.0, 0.0)
    u = norm(cross(up, d))
    return u, norm(cross(d, u))


def quad(p0, p1, p2, t):
    u = 1 - t
    return tuple(u * u * p0[i] + 2 * u * t * p1[i] + t * t * p2[i] for i in range(3))


# --------------------------------------------------------------------------- primitives
def lathe(profile, seg, fold=None, cx=0.0, cz=0.0):
    """Surface of revolution. `fold` scales the radius by angle and height - the skirt plates."""
    rows = []
    for (r, y) in profile:
        row = []
        for j in range(seg):
            a = 2 * math.pi * j / seg
            rr = r * (fold(a, y) if fold else 1.0)
            row.append((cx + math.cos(a) * rr, y, cz + math.sin(a) * rr))
        rows.append(row)
    return rows


def tube(m, x, z, y0, y1, r0, r1, seg=14, cap_top=True, cap_bot=True):
    """An upright tapered cylinder, smooth around its side."""
    rows = []
    for (y, r) in ((y0, r0), (y1, r1)):
        rows.append([(x + math.cos(2 * math.pi * j / seg) * r, y,
                      z + math.sin(2 * math.pi * j / seg) * r) for j in range(seg)])
    m.grid(rows)
    for (do, y, r, up) in ((cap_bot, y0, r0, False), (cap_top, y1, r1, True)):
        if not do:
            continue
        n = (0.0, 1.0, 0.0) if up else (0.0, -1.0, 0.0)
        c = m.vert((x, y, z), n)
        ring = [m.vert((x + math.cos(2 * math.pi * j / seg) * r, y,
                        z + math.sin(2 * math.pi * j / seg) * r), n) for j in range(seg)]
        for j in range(seg):
            a, b = ring[j], ring[(j + 1) % seg]
            m.tri(c, a, b) if up else m.tri(c, b, a)


def strut(m, p0, p1, r0, r1, seg=8):
    """A tapered tube between two arbitrary points - arms, and the bones of both wings."""
    d = sub(p1, p0)
    if dist(p0, p1) < 1e-6:
        return
    u, w = frame(d)
    rows = []
    for (p, r) in ((p0, r0), (p1, r1)):
        rows.append([add(p, add(mul(u, math.cos(2 * math.pi * j / seg) * r),
                                mul(w, math.sin(2 * math.pi * j / seg) * r))) for j in range(seg)])
    m.grid(rows)


def sphere(m, cx, cy, cz, r, seg=16, rings=10, sy=1.0, sz=1.0):
    rows = []
    for i in range(rings + 1):
        phi = math.pi * i / rings
        rows.append([(cx + math.sin(phi) * math.cos(2 * math.pi * j / seg) * r,
                      cy + math.cos(phi) * r * sy,
                      cz + math.sin(phi) * math.sin(2 * math.pi * j / seg) * r * sz)
                     for j in range(seg)])
    m.grid(rows)


def blade(m, root, tip, side, w0, w1, t):
    """A flat tapered slab with thickness - a feather, and the sword's blade.

    `side` is the in-plane direction the width runs along; thickness goes across both.
    """
    d = norm(sub(tip, root))
    s = norm(sub(side, mul(d, side[0] * d[0] + side[1] * d[1] + side[2] * d[2])))
    n = norm(cross(d, s))
    def corners(p, w, k):
        return (add(add(p, mul(s,  w)), mul(n,  k)), add(add(p, mul(s,  w)), mul(n, -k)),
                add(add(p, mul(s, -w)), mul(n, -k)), add(add(p, mul(s, -w)), mul(n,  k)))
    a0, b0, c0, d0 = corners(root, w0, t)
    a1, b1, c1, d1 = corners(tip,  w1, t * 0.45)
    m.quad_flat(a0, a1, b1, b0)      # the two flats
    m.quad_flat(c0, c1, d1, d0)
    m.quad_flat(b0, b1, c1, c0)      # the two edges
    m.quad_flat(d0, d1, a1, a0)
    m.quad_flat(a0, b0, c0, d0)      # the root cap; the tip closes itself at w1 ~ 0


# --------------------------------------------------------------------------- materials
def mat(mid, diffuse, ambient, spec=(0.10, 0.10, 0.11), shine=8.0, emissive=None, opacity=None):
    d = {"id": mid, "ambient": list(ambient), "diffuse": list(diffuse),
         "specular": list(spec), "shininess": shine}
    if emissive:
        d["emissive"] = list(emissive)
    if opacity is not None:
        d["opacity"] = opacity        # libGDX turns this into a BlendingAttribute
    return d


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
    HILT = (0.0, 0.620, 0.185)
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
    STEPS = 5
    for k in range(len(SPARS) - 1):
        a, b = SPARS[k], SPARS[k + 1]
        prev = None
        for i in range(STEPS + 1):
            t = i / float(STEPS)
            pa, pb = lerp(L, a, t), lerp(L, b, t)
            # sag: the middle of each panel is pulled back towards the root, and more so the
            # further out it is, which is what makes a bat wing read as one.
            edge = [lerp(pa, pb, j / 3.0) for j in range(4)]
            edge = [lerp(e, L, 0.13 * math.sin(math.pi * (j / 3.0)) * t) for j, e in enumerate(edge)]
            if prev is not None:
                for j in range(3):
                    m.tri_two_sided(prev[j], prev[j + 1], edge[j + 1])
                    m.tri_two_sided(prev[j], edge[j + 1], edge[j])
            prev = edge

    return m, [ARMOUR, PLATE, GOLD, SKIN, HAIR, STEEL, FEATHER, BONE, WING], "kaalia"


# =========================================================================== write and check
def write(path, mesh, mats, node_id):
    parts, used = [], []
    for (pid, material, idx) in mesh.parts:
        if not idx:
            continue
        parts.append({"id": pid, "type": "TRIANGLES", "indices": idx})
        used.append({"meshpartid": pid, "materialid": material})
    doc = {
        "version": [0, 1],
        "id": "",
        "meshes": [{"attributes": ["POSITION", "NORMAL"], "vertices": mesh.v, "parts": parts}],
        "materials": mats,
        "nodes": [{"id": node_id, "parts": used}],
        "animations": []
    }
    with io.open(path, "w", encoding="utf-8") as f:
        json.dump(doc, f, separators=(",", ":"))
    return doc


def check(doc):
    """The three ways a .g3dj loads as nothing, silently (models\\README.md)."""
    mesh = doc["meshes"][0]
    n = len(mesh["vertices"]) // 6
    ids = {mm["id"] for mm in doc["materials"]}
    for p in mesh["parts"]:
        assert len(p["indices"]) % 3 == 0, p["id"] + ": indices not a multiple of three"
        assert p["indices"] and min(p["indices"]) >= 0 and max(p["indices"]) < n, \
            p["id"] + ": index out of range"
    have = {p["id"] for p in mesh["parts"]}
    for u in doc["nodes"][0]["parts"]:
        assert u["materialid"] in ids, u["meshpartid"] + ": no material " + u["materialid"]
        assert u["meshpartid"] in have, u["meshpartid"] + ": no such mesh part"
    return n, sum(len(p["indices"]) for p in mesh["parts"]) // 3


def bounds(mesh):
    xs = [mesh.v[i] for i in range(0, len(mesh.v), 6)]
    ys = [mesh.v[i] for i in range(1, len(mesh.v), 6)]
    zs = [mesh.v[i] for i in range(2, len(mesh.v), 6)]
    return (max(xs) - min(xs), max(ys) - min(ys), max(zs) - min(zs)), min(ys)


if not os.path.isdir(OUT):
    raise SystemExit("no models folder at " + OUT)

mesh, mats, node = build()
doc = write(os.path.join(OUT, "kaalia of the vast.g3dj"), mesh, mats, node)
v, t = check(doc)
(w, h, d), floor = bounds(mesh)
print("kaalia of the vast.g3dj   %d verts  %d tris   %.2f w x %.2f h x %.2f d   feet at y=%.3f"
      % (v, t, w, h, d, floor))
