# Builds the two Athreos models for ui3d\models as .g3dj (libGDX's JSON model format).
#
# Two files, because the card has two faces in play and the table should say which without
# anyone reading it:
#
#   athreos god of passage.g3dj        dormant - a roadside stele, he is not a creature
#   athreos god of passage awake.g3dj  devotion 7+, he is a 4/7 and can block
#
# Both are built to the same overall height so Models.fitScale (which normalises on the Y
# bounding box to FIGURE_H) gives them the same presence on the card.

import json, math, io, os

OUT = os.path.dirname(os.path.abspath(__file__))   # this folder, wherever the clone is
H = 1.18                       # both models stand this tall before the loader rescales


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
        """A flat-shaded quad - its own four vertices, one face normal. Stone wants this."""
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

    def grid(self, pts, closed_u=True):
        """A smooth-shaded (rows x cols) patch; normals come from the surface itself, so cloth
        folds get the right shading instead of the shading the un-folded shape would have."""
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


def sub(a, b):   return (a[0] - b[0], a[1] - b[1], a[2] - b[2])
def cross(a, b): return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
def dist(a, b):  return math.sqrt(sum((a[i] - b[i]) ** 2 for i in range(3)))


def norm(v):
    L = math.sqrt(v[0] ** 2 + v[1] ** 2 + v[2] ** 2)
    return (0.0, 0.0, 0.0) if L < 1e-9 else (v[0] / L, v[1] / L, v[2] / L)


def normal(p0, p1, p2):
    n = norm(cross(sub(p1, p0), sub(p2, p0)))
    return None if n == (0.0, 0.0, 0.0) else n


# --------------------------------------------------------------------------- primitives
def lathe(profile, seg, fold=None, cx=0.0, cz=0.0):
    """Surface of revolution. `fold` scales the radius by angle and height - the cloth."""
    rows = []
    for (r, y) in profile:
        row = []
        for j in range(seg):
            a = 2 * math.pi * j / seg
            rr = r * (fold(a, y) if fold else 1.0)
            row.append((cx + math.cos(a) * rr, y, cz + math.sin(a) * rr))
        rows.append(row)
    return rows


def tube(m, mat, x, z, y0, y1, r0, r1, seg=14, cap_top=True, cap_bot=True):
    """A tapered cylinder, smooth around its side."""
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


def sphere(m, cx, cy, cz, r, seg=16, rings=10, sy=1.0, sz=1.0, top_only=False):
    half = math.pi / 2 if top_only else math.pi
    rows = []
    for i in range(rings + 1):
        phi = half * i / rings
        rows.append([(cx + math.sin(phi) * math.cos(2 * math.pi * j / seg) * r,
                      cy + math.cos(phi) * r * sy,
                      cz + math.sin(phi) * math.sin(2 * math.pi * j / seg) * r * sz)
                     for j in range(seg)])
    m.grid(rows)


def torus_arc(m, cx, cy, cz, R, t, a0, a1, seg=22, tube_seg=8):
    """The crook's hook. Lies in the XY plane, so it reads as a hook from the front."""
    rows = []
    for i in range(seg + 1):
        a = a0 + (a1 - a0) * i / seg
        ca, sa = math.cos(a), math.sin(a)
        ring = []
        for j in range(tube_seg):
            b = 2 * math.pi * j / tube_seg
            rr = R + math.cos(b) * t
            ring.append((cx + ca * rr, cy + sa * rr, cz + math.sin(b) * t))
        rows.append(ring)
    m.grid(rows, closed_u=True)


def column(m, y0, y1, r0, r1, rot=math.pi / 4, sides=4):
    """A tapered column, flat-shaded - a cut block of stone, not a turned one."""
    def ring(y, r):
        return [(math.cos(rot + 2 * math.pi * j / sides) * r, y,
                 math.sin(rot + 2 * math.pi * j / sides) * r) for j in range(sides)]
    lo, hi = ring(y0, r0), ring(y1, r1)
    for j in range(sides):
        k = (j + 1) % sides
        m.quad_flat(lo[j], lo[k], hi[k], hi[j])
    for j in range(1, sides - 1):
        m.tri_flat(hi[0], hi[j], hi[j + 1])
        m.tri_flat(lo[0], lo[j + 1], lo[j])


def hood_outline(n=26):
    """The silhouette cut into the stele: shoulders, a rise, the peak of a drawn hood."""
    def q(p0, p1, p2, t):
        u = 1 - t
        return (u * u * p0[0] + 2 * u * t * p1[0] + t * t * p2[0],
                u * u * p0[1] + 2 * u * t * p1[1] + t * t * p2[1])
    pts = [(-0.150, -0.330)]
    arcs = [((-0.150, -0.330), (-0.178, -0.020), (-0.132, 0.140)),
            ((-0.132, 0.140), (-0.112, 0.308), (0.000, 0.348)),
            ((0.000, 0.348), (0.112, 0.308), (0.132, 0.140)),
            ((0.132, 0.140), (0.178, -0.020), (0.150, -0.330))]
    for a in arcs:
        for i in range(1, n + 1):
            pts.append(q(a[0], a[1], a[2], i / float(n)))
    return pts


def relief(m, outline, cy, z_back, z_front):
    """The outline raised off the stele face, with a rim so it catches the light."""
    cen = (sum(p[0] for p in outline) / len(outline),
           sum(p[1] for p in outline) / len(outline))
    n = len(outline)
    # front face
    fn = (0.0, 0.0, 1.0)
    c = m.vert((cen[0], cy + cen[1], z_front), fn)
    ring = [m.vert((p[0], cy + p[1], z_front), fn) for p in outline]
    for j in range(n):
        m.tri(c, ring[j], ring[(j + 1) % n])
    # rim
    for j in range(n):
        k = (j + 1) % n
        p0 = (outline[j][0], cy + outline[j][1], z_back)
        p1 = (outline[k][0], cy + outline[k][1], z_back)
        p2 = (outline[k][0], cy + outline[k][1], z_front)
        p3 = (outline[j][0], cy + outline[j][1], z_front)
        m.quad_flat(p0, p1, p2, p3)


# --------------------------------------------------------------------------- materials
def mat(mid, diffuse, ambient, spec=(0.10, 0.10, 0.11), shine=8.0, emissive=None, opacity=None):
    d = {"id": mid, "ambient": list(ambient), "diffuse": list(diffuse),
         "specular": list(spec), "shininess": shine}
    if emissive:
        d["emissive"] = list(emissive)
    if opacity is not None:
        d["opacity"] = opacity        # libGDX turns this into a BlendingAttribute
    return d


CRYSTAL = mat("crystal", (0.18, 0.62, 0.34), (0.05, 0.22, 0.12), (0.72, 1.00, 0.80), 64.0,
              emissive=(0.05, 0.26, 0.13), opacity=0.76)
SHARD   = mat("shard",   (0.12, 0.44, 0.24), (0.03, 0.14, 0.08), (0.50, 0.85, 0.60), 48.0,
              emissive=(0.03, 0.17, 0.09), opacity=0.88)
SEED    = mat("seed",    (0.03, 0.11, 0.07), (0.01, 0.04, 0.02), (0.05, 0.14, 0.08), 6.0)
HEART   = mat("heart",   (0.52, 0.98, 0.66), (0.42, 0.92, 0.58), (0.80, 1.00, 0.88), 56.0,
              emissive=(0.34, 0.86, 0.48))
ROBE   = mat("robe",   (0.40, 0.43, 0.52), (0.13, 0.15, 0.20), (0.09, 0.10, 0.13), 9.0)
MANTLE = mat("mantle", (0.26, 0.29, 0.36), (0.09, 0.10, 0.13), (0.07, 0.08, 0.10), 7.0)
HOLLOW = mat("hollow", (0.02, 0.03, 0.05), (0.00, 0.01, 0.01), (0.00, 0.00, 0.00), 1.0)
EYE    = mat("eye",    (0.75, 0.95, 0.98), (0.60, 0.88, 0.92), (0.90, 1.00, 1.00), 48.0,
             emissive=(0.56, 0.88, 0.92))
WOOD   = mat("wood",   (0.43, 0.39, 0.31), (0.13, 0.12, 0.09), (0.14, 0.13, 0.10), 14.0)
GOLD   = mat("gold",   (0.83, 0.66, 0.24), (0.26, 0.20, 0.06), (0.95, 0.82, 0.42), 40.0,
             emissive=(0.20, 0.14, 0.02))


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
    return len(mesh.v) // 6, sum(len(p["indices"]) for p in parts) // 3


# =========================================================================== A. DORMANT
# Not a creature: a green crystal cocoon with him suspended inside it, waiting on devotion.
#
# This replaced a stele, which read as a gravestone - exactly the wrong idea, because a dormant
# god is not a dead one. A chrysalis says held, not buried, and it says the other half too: what
# is inside is going to come out.
#
# Faceted on purpose. Eight sides flat-shaded catch the table lights as hard edges, which is what
# makes it read as crystal rather than as a green egg.
def build_dormant():
    m = Mesh()
    SEG = 8
    ROT = math.pi / SEG          # a facet faces front rather than an edge

    # the pod: a point at the foot, the swell at a third height, a drawn-out tip
    POD = [(0.000, 0.000), (0.148, 0.095), (0.244, 0.230), (0.296, 0.420),
           (0.306, 0.560), (0.286, 0.715), (0.236, 0.865), (0.160, 1.000),
           (0.078, 1.105), (0.000, 1.180)]

    m.part("pod", "crystal")
    for k in range(len(POD) - 1):
        (r0, y0), (r1, y1) = POD[k], POD[k + 1]
        lo = [(math.cos(ROT + 2 * math.pi * j / SEG) * r0, y0,
               math.sin(ROT + 2 * math.pi * j / SEG) * r0) for j in range(SEG)]
        hi = [(math.cos(ROT + 2 * math.pi * j / SEG) * r1, y1,
               math.sin(ROT + 2 * math.pi * j / SEG) * r1) for j in range(SEG)]
        for j in range(SEG):
            i2 = (j + 1) % SEG
            if r0 < 1e-6:
                m.tri_flat(lo[0], hi[i2], hi[j])
            elif r1 < 1e-6:
                m.tri_flat(hi[0], lo[j], lo[i2])
            else:
                m.quad_flat(lo[j], lo[i2], hi[i2], hi[j])

    # shards pushing out of the ground around it, so it has grown rather than been set down
    m.part("shards", "shard")
    for (ang, lean, hgt, rad) in ((0.5, 0.30, 0.46, 0.085), (2.1, 0.22, 0.32, 0.070),
                                  (3.5, 0.34, 0.52, 0.090), (4.6, 0.18, 0.27, 0.060),
                                  (5.6, 0.28, 0.38, 0.075)):
        bx, bz = math.cos(ang) * 0.30, math.sin(ang) * 0.30
        tip = (bx + math.cos(ang) * lean, hgt, bz + math.sin(ang) * lean)
        base = [(bx + math.cos(ang + 2 * math.pi * j / 3) * rad, 0.0,
                 bz + math.sin(ang + 2 * math.pi * j / 3) * rad) for j in range(3)]
        for j in range(3):
            m.tri_flat(base[j], base[(j + 1) % 3], tip)
        m.tri_flat(base[0], base[2], base[1])

    # him, folded inside: the hood and the curve of a shoulder, no more. Seen through the
    # crystal it only has to be enough to tell you somebody is in there.
    m.part("seed", "seed")
    sphere(m, 0.0, 0.640, 0.0, 0.112, seg=12, rings=8, sy=1.25, sz=0.92)
    tube(m, "seed", 0.0, 0.0, 0.330, 0.560, 0.055, 0.135, seg=10)

    # the light he is keeping
    m.part("heart", "heart")
    sphere(m, 0.0, 0.640, 0.078, 0.034, seg=10, rings=7)

    return m, [CRYSTAL, SHARD, SEED, HEART], "athreos_dormant"


# =========================================================================== B. AWAKE
# Devotion 7+. The ferryman himself: a robe of revolution with cloth folds, an empty hood with
# two cold lights in it, a hem that frays into the water instead of ending in feet, and the
# crook he hooks souls with. The coin at the grip is the toll - it is the ability, in one object.
def build_awake():
    m = Mesh()

    PROFILE = [(0.000, 0.000), (0.330, 0.015), (0.322, 0.075), (0.298, 0.170),
               (0.272, 0.290), (0.244, 0.410), (0.216, 0.515), (0.194, 0.595),
               (0.182, 0.655), (0.202, 0.700), (0.172, 0.762), (0.132, 0.820),
               (0.148, 0.880), (0.152, 0.930), (0.118, 0.972), (0.052, 1.000),
               (0.000, 1.006)]

    def fold(a, y):
        w = max(0.0, 1.0 - y / 0.75)        # folds die away towards the hood
        return 1.0 + 0.055 * w * math.sin(a * 9) + 0.022 * w * math.sin(a * 5 + 1.3)

    m.part("robe", "robe")
    m.grid(lathe(PROFILE, 40, fold))

    m.part("mantle", "mantle")
    tube(m, "mantle", 0.0, 0.0, 0.705, 0.590, 0.0, 0.265, seg=28, cap_top=False, cap_bot=False)

    m.part("hollow", "hollow")
    sphere(m, 0.0, 0.885, 0.055, 0.105, seg=16, rings=10, sy=1.12, sz=0.78)

    m.part("eyes", "eye")
    for x in (-0.040, 0.040):
        sphere(m, x, 0.898, 0.125, 0.0175, seg=10, rings=6)

    m.part("hem", "mantle")
    tube(m, "mantle", 0.0, 0.0, 0.130, -0.040, 0.355, 0.400, seg=30,
         cap_top=False, cap_bot=False)

    m.part("arm", "robe")
    tube(m, "robe", 0.150, 0.070, 0.740, 0.420, 0.034, 0.056, seg=10)

    # --- the crook -------------------------------------------------------
    CX, CZ = 0.255, 0.055
    m.part("shaft", "wood")
    tube(m, "wood", CX, CZ, 0.030, 1.056, 0.022, 0.018, seg=10)

    m.part("hook", "wood")
    torus_arc(m, CX + 0.082, 1.056, CZ, 0.105, 0.019,
              math.radians(205), math.radians(205 + 261))

    m.part("cap", "gold")
    sphere(m, CX, 1.070, CZ, 0.030, seg=12, rings=8)

    # the coin that hangs at the grip: the toll, always on his person
    m.part("coin", "gold")
    tube(m, "gold", CX, CZ + 0.030, 0.796, 0.803, 0.042, 0.042, seg=20)

    return m, [ROBE, MANTLE, HOLLOW, EYE, WOOD, GOLD], "athreos_awake"


# =========================================================================== write them
def height(mesh):
    ys = [mesh.v[i] for i in range(1, len(mesh.v), 6)]
    return max(ys) - min(ys)


if not os.path.isdir(OUT):
    raise SystemExit("no models folder at " + OUT)

for (fname, builder) in (("athreos god of passage.g3dj", build_dormant),
                         ("athreos god of passage awake.g3dj", build_awake)):
    mesh, mats, node = builder()
    v, t = write(os.path.join(OUT, fname), mesh, mats, node)
    print("%-38s %5d verts %5d tris  height %.3f" % (fname, v, t, height(mesh)))
