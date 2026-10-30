# The shared half of the model scripts: mesh building, primitives, materials, writing, checking.
#
# models\README.md says "copy it for the next card rather than starting over", and that was right
# when there was one script. At three it is not: the primitives had already been copied once
# verbatim, and a fix to a copy is a fix one model gets and the others do not. So the toolkit lives
# here and each make-<card>.py is only the card - its materials, its shapes, its reasons.
#
# make-athreos.py is deliberately NOT changed to use this. It is the worked example the README
# points at, it is the only script whose output has been seen on a real table, and rewriting proven
# geometry to save duplication is a bad trade. If a third thing needs a primitive it has, move it
# here then.
#
# Conventions every model here follows (models\README.md): feet on the ground at the origin, Y up,
# facing +Z. The loader measures the model and scales it to Models.FIGURE_H, so absolute size does
# not matter - but the X and Z extents do, because they are not normalised and a card is 1.0 x 1.4.

import json, math, io


# --------------------------------------------------------------------------- vector maths
def add(a, b):     return (a[0] + b[0], a[1] + b[1], a[2] + b[2])
def sub(a, b):     return (a[0] - b[0], a[1] - b[1], a[2] - b[2])
def mul(a, s):     return (a[0] * s, a[1] * s, a[2] * s)
def dot(a, b):     return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
def lerp(a, b, t): return (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t)
def cross(a, b):   return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
def dist(a, b):    return math.sqrt(sum((a[i] - b[i]) ** 2 for i in range(3)))


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
    """A point along a quadratic Bezier - how every curved bone and spine here is laid out."""
    u = 1 - t
    return tuple(u * u * p0[i] + 2 * u * t * p1[i] + t * t * p2[i] for i in range(3))


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
        """A flat-shaded quad - its own four vertices, one face normal. Plate and scale want this."""
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
        """A smooth-shaded (rows x cols) patch; normals come from the surface itself, so folds get
        the shading of the folded shape rather than of the cylinder they started as."""
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


# --------------------------------------------------------------------------- primitives
def lathe(profile, seg, fold=None, cx=0.0, cz=0.0):
    """Surface of revolution. `fold` scales the radius by angle and height - cloth, plate skirts."""
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
    """A tapered tube between two arbitrary points - limbs, wing bones, horns."""
    if dist(p0, p1) < 1e-6:
        return
    u, w = frame(sub(p1, p0))
    rows = []
    for (p, r) in ((p0, r0), (p1, r1)):
        rows.append([add(p, add(mul(u, math.cos(2 * math.pi * j / seg) * r),
                                mul(w, math.sin(2 * math.pi * j / seg) * r))) for j in range(seg)])
    m.grid(rows)


def spine(m, pts, radii, seg=9):
    """A tapered tube following a whole curve, smooth across the joins - necks and tails.

    A chain of struts would crease at every join, which on a dragon's neck is exactly where the
    eye goes. One grid over the whole curve does not.
    """
    rows = []
    for i, p in enumerate(pts):
        d = sub(pts[min(i + 1, len(pts) - 1)], pts[max(i - 1, 0)])
        u, w = frame(d)
        r = radii[i]
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
    """A flat tapered slab with thickness - feathers, sword blades, tail fins.

    `side` is the in-plane direction the width runs along; thickness goes across both.
    """
    d = norm(sub(tip, root))
    s = norm(sub(side, mul(d, dot(side, d))))
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


def membrane(m, root, spars, steps=5, sag=0.13):
    """Skin slung between spars that share a root - a bat or dragon wing.

    `sag` pulls the middle of each panel back towards the root, and more so further out, which is
    what makes the trailing edge scallop instead of running straight from tip to tip.
    """
    for k in range(len(spars) - 1):
        a, b = spars[k], spars[k + 1]
        prev = None
        for i in range(steps + 1):
            t = i / float(steps)
            pa, pb = lerp(root, a, t), lerp(root, b, t)
            edge = [lerp(pa, pb, j / 3.0) for j in range(4)]
            edge = [lerp(e, root, sag * math.sin(math.pi * (j / 3.0)) * t) for j, e in enumerate(edge)]
            if prev is not None:
                for j in range(3):
                    m.tri_two_sided(prev[j], prev[j + 1], edge[j + 1])
                    m.tri_two_sided(prev[j], edge[j + 1], edge[j])
            prev = edge


def facet_gem(m, cx, cy, cz, r, sy=1.4):
    """A cut stone: two flat-shaded pyramids on a shared belt, so it catches light as hard edges
    the way a smooth sphere of the same colour never does."""
    seg = 6
    belt = [(cx + math.cos(2 * math.pi * j / seg) * r, cy,
             cz + math.sin(2 * math.pi * j / seg) * r) for j in range(seg)]
    for (ty, up) in ((cy + r * sy, True), (cy - r * sy, False)):
        tip = (cx, ty, cz)
        for j in range(seg):
            a, b = belt[j], belt[(j + 1) % seg]
            m.tri_flat(a, b, tip) if up else m.tri_flat(b, a, tip)


# --------------------------------------------------------------------------- materials
def mat(mid, diffuse, ambient, spec=(0.10, 0.10, 0.11), shine=8.0, emissive=None, opacity=None):
    d = {"id": mid, "ambient": list(ambient), "diffuse": list(diffuse),
         "specular": list(spec), "shininess": shine}
    if emissive:
        d["emissive"] = list(emissive)
    if opacity is not None:
        d["opacity"] = opacity        # libGDX turns this into a BlendingAttribute
    return d


# --------------------------------------------------------------------------- write and check
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
    """The three ways a .g3dj loads as nothing, silently (models\\README.md). Asserts, not advice."""
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


def report(name, mesh, doc):
    v, t = check(doc)
    xs = [mesh.v[i] for i in range(0, len(mesh.v), 6)]
    ys = [mesh.v[i] for i in range(1, len(mesh.v), 6)]
    zs = [mesh.v[i] for i in range(2, len(mesh.v), 6)]
    print("%-34s %5d verts %5d tris   %.2f w x %.2f h x %.2f d   feet at y=%.3f"
          % (name, v, t, max(xs) - min(xs), max(ys) - min(ys), max(zs) - min(zs), min(ys)))
