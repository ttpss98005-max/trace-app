package tw.trace.app;

import static tw.trace.app.Core.dn;
import static tw.trace.app.Core.ds;
import static tw.trace.app.Core.items;
import static tw.trace.app.Core.lanes;
import static tw.trace.app.Core.nodes;
import static tw.trace.app.Core.s;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Everything the screen shows and does that is not drawing: layout of the timeline, the camera,
 * touch handling, undo and redo. Mirrors the web version function by function. All coordinates
 * are CSS-style pixels (Android dp), so the numbers are the same as on the web.
 */
final class Board {
    /** What the board needs from the rest of the app. */
    interface Host {
        void openEdit(String id, boolean recenter);
        void openNew(String lane, String date, String from);
        void openSettings(String focusLane);
        void closePanel();
        boolean panelOpen();
        String panelMode();
        void redrawPanel();
        boolean typingInPanel();
        void scheduleSave();
        void later(long ms, Runnable r);
        void redraw();
        void hubAction(String key, String arg);
        void loginTap();
        void afterRefresh();
    }

    static final double W = 196, H = 104, GAP = 30, RG = 22, PAD = 28;
    static final double R_HEAD = 170, RW = 224, PERIOD = 13, IT_H = 22;
    static final int IT_MAX = 8;
    static final double Z_MIN = .16, Z_MAX = 2.4;
    static final double BAND_H = 165, FOUR_H = 150;
    static final String[] LANE_COLORS = {"#7A5CFF", "#2E9E4F", "#E0559A", "#3D7BFF", "#B4641E"};

    final Host host;
    Map<String, Object> store, state;
    boolean reduce = false;

    Board(Host host) { this.host = host; }

    int today = Core.today();
    int ref = today;

    /* screen */
    double VW = 390, VH = 844, rulerY = 120;
    /** Screen height without the keyboard, so typing never flips the layout. */
    double screenH = 844;

    boolean compact() { return VW <= 760 || screenH <= 480; }
    boolean shortScreen() { return screenH <= 480; }
    /** The form sits on the right in landscape, and as a bottom sheet on a portrait phone. */
    boolean panelSide() { return VW > 760 || VW > screenH; }
    double panelW() { return Math.min(348, VW * .56); }
    double leftPad() { return compact() ? 12 : 136; }

    /* ---------- geometry ---------- */
    static final class Geo {
        String id;
        Map<String, Object> n;
        double x, y, ax, ay, h = H;
        int row;
    }

    static final class Lane {
        Map<String, Object> lane;
        String id, name, kind;
        List<Map<String, Object>> items = new ArrayList<>();
        double top, h, tx, ty;
        /* tag rectangle on screen, measured while drawing */
        double tagY, tagH, tagW;
        boolean tagVisible;
    }

    final Map<String, Geo> G = new LinkedHashMap<>();
    final List<Lane> laneBoxes = new ArrayList<>();
    final Map<String, Integer> seq = new HashMap<>();
    /** Nodes behind a review that has not passed. They stay editable; the screen marks them as waiting. */
    final Set<String> locked = new HashSet<>();
    Lane mainBox;
    double total, spine, bottom, headX, headY, minX;
    int d0, d1;
    int rev = 0;
    float[] envV;
    double envS0;

    private static final Collator COLLATE = Collator.getInstance(Locale.TRADITIONAL_CHINESE);

    static double hOf(Map<String, Object> n) {
        if (Core.isPortal(n)) return H;
        int c = Math.max(1, items(n).size());
        return H + 5 + (Math.min(c, IT_MAX) + (c > IT_MAX ? 1 : 0)) * IT_H;
    }

    static double aw(String id) { return "root".equals(id) ? RW : W; }

    Map<String, Object> mainLane() {
        for (Map<String, Object> l : lanes(state)) if ("main".equals(l.get("kind"))) return l;
        return lanes(state).get(0);
    }

    int ppd() {
        String d = s(state, "density");
        return "week".equals(d) ? 34 : "quarter".equals(d) ? 5 : 14;
    }

    double xOf(double d) { return (d - ref) * ppd(); }
    int dayAt(double x) { return (int) Math.floor(x / ppd() + ref + .5); }
    double dayAt0(double x) { return x / ppd() + ref; }

    int refDay() {
        List<Map<String, Object>> ns = nodes(state);
        if (ns.isEmpty()) return today;
        int m = Integer.MAX_VALUE;
        for (Map<String, Object> n : ns) m = Math.min(m, dn(s(n, "date")));
        return m;
    }

    /** anchorOf(): the node (or the project) a lane starts from. */
    String anchorId(Map<String, Object> lane) {
        String from = s(lane, "from");
        if (from != null && !from.isEmpty() && !"root".equals(from)) {
            Map<String, Object> n = Core.nodeOf(state, from);
            if (n != null && !s(lane, "id").equals(s(n, "lane"))) return from;
        }
        return "root";
    }

    int anchorDay(Map<String, Object> lane) {
        String id = anchorId(lane);
        return "root".equals(id) ? d0 : dn(s(Core.nodeOf(state, id), "date"));
    }

    void layout() {
        total = Core.projTotal(store, state);
        int p = ppd();
        double y = 0, maxRight = Double.NEGATIVE_INFINITY, mnX = Double.POSITIVE_INFINITY;
        List<Map<String, Object>> sorted = new ArrayList<>(nodes(state));
        sorted.sort(new Comparator<Map<String, Object>>() {
            @Override public int compare(Map<String, Object> a, Map<String, Object> b) {
                int c = dn(s(a, "date")) - dn(s(b, "date"));
                return c != 0 ? c : COLLATE.compare(s(a, "title"), s(b, "title"));
            }
        });
        for (int i = 0; i < sorted.size(); i++) seq.put(s(sorted.get(i), "id"), i + 1);
        laneBoxes.clear();
        for (Map<String, Object> lane : lanes(state)) {
            Lane box = new Lane();
            box.lane = lane;
            box.id = s(lane, "id");
            box.name = s(lane, "name");
            box.kind = s(lane, "kind");
            for (Map<String, Object> n : nodes(state)) if (box.id.equals(s(n, "lane"))) box.items.add(n);
            box.items.sort(new Comparator<Map<String, Object>>() {
                @Override public int compare(Map<String, Object> a, Map<String, Object> b) { return dn(s(a, "date")) - dn(s(b, "date")); }
            });
            List<Double> rows = new ArrayList<>(), rowH = new ArrayList<>();
            int[] placeRow = new int[box.items.size()];
            double[] placeX = new double[box.items.size()];
            for (int k = 0; k < box.items.size(); k++) {
                Map<String, Object> n = box.items.get(k);
                double x = (dn(s(n, "date")) - ref) * (double) p;
                int r = 0;
                while (r < rows.size() && rows.get(r) + W + GAP > x) r++;
                if (r == rows.size()) { rows.add(x); rowH.add(H); }
                else rows.set(r, x);
                rowH.set(r, Math.max(rowH.get(r), hOf(n)));
                placeRow[k] = r;
                placeX[k] = x;
                if (x + W > maxRight) maxRight = x + W;
                if (x < mnX) mnX = x;
            }
            double[] rowY = new double[rowH.size()];
            double ry = y + PAD;
            for (int i = 0; i < rowY.length; i++) { rowY[i] = ry; ry += rowH.get(i) + RG; }
            for (int k = 0; k < box.items.size(); k++) {
                Map<String, Object> n = box.items.get(k);
                String id = s(n, "id");
                Geo g = G.get(id);
                if (g == null) { g = new Geo(); g.id = id; g.ax = placeX[k]; g.ay = rowY[placeRow[k]]; G.put(id, g); }
                g.n = n;
                g.x = placeX[k];
                g.y = rowY[placeRow[k]];
                g.row = placeRow[k];
                g.h = hOf(n);
            }
            box.top = y;
            box.h = rowH.isEmpty() ? PAD * 2 + H : ry - RG + PAD - y;
            if (!box.items.isEmpty()) {
                Geo last = G.get(s(box.items.get(box.items.size() - 1), "id"));
                box.tx = last.x + W + 26;
                box.ty = last.y + H / 2 - 20;
                if (box.tx + 40 > maxRight) maxRight = box.tx + 40;
            } else {
                box.tx = 0;
                box.ty = y + box.h / 2 - 20;
            }
            laneBoxes.add(box);
            y += box.h;
        }
        bottom = y;
        mainBox = null;
        for (Lane l : laneBoxes) if ("main".equals(l.kind)) { mainBox = l; break; }
        if (mainBox == null && !laneBoxes.isEmpty()) mainBox = laneBoxes.get(0);
        spine = mainBox != null ? mainBox.top + PAD + H / 2 : 0;
        if (Double.isInfinite(mnX)) mnX = xOf(today);
        if (Double.isInfinite(maxRight)) maxRight = xOf(today) + W;
        minX = mnX;
        d0 = Integer.MAX_VALUE;
        d1 = Integer.MIN_VALUE;
        for (Map<String, Object> n : nodes(state)) { int d = dn(s(n, "date")); d0 = Math.min(d0, d); d1 = Math.max(d1, d); }
        if (d0 == Integer.MAX_VALUE) { d0 = today; d1 = today + 30; }
        double rx = mnX - RW - 72, ryy = spine - H / 2;
        Geo gr = G.get("root");
        if (gr == null) { gr = new Geo(); gr.id = "root"; gr.ax = rx; gr.ay = ryy; G.put("root", gr); }
        gr.x = rx;
        gr.y = ryy;
        gr.h = H;
        for (Lane l : laneBoxes) if (l.items.isEmpty()) {
            Geo g = G.get(anchorId(l.lane));
            l.tx = g.x + aw(g.id) + 48;
            if (l.tx + 40 > maxRight) maxRight = l.tx + 40;
        }
        headX = maxRight + R_HEAD + 90;
        headY = spine;
        // drop geometry of removed nodes
        Set<String> live = new HashSet<>();
        live.add("root");
        for (Map<String, Object> n : nodes(state)) live.add(s(n, "id"));
        G.keySet().retainAll(live);
        computeLocked();
        buildEnvelope();
    }

    private static boolean openGate(Map<String, Object> n) { return Core.isGate(n) && !Core.b(n, "manualDone"); }

    private void computeLocked() {
        locked.clear();
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Lane l : laneBoxes) {
                String aid = anchorId(l.lane);
                boolean shut = false;
                if (!"root".equals(aid) && !Core.b(l.lane, "detached")) {
                    Map<String, Object> a = Core.nodeOf(state, aid);
                    shut = a != null && (openGate(a) || locked.contains(aid));
                }
                for (int i = 0; i < l.items.size(); i++) {
                    Map<String, Object> n = l.items.get(i);
                    /* a deleted wire ends the hold: nothing connects those nodes to the review any more */
                    if (i > 0 && Core.b(n, "cutIn")) shut = false;
                    if (shut && locked.add(s(n, "id"))) grew = true;
                    if (openGate(n)) shut = true;
                }
            }
        }
    }

    /** The node the person last touched: it and what is wired to it stay bright. */
    String focusId() {
        String f = hoverId != null ? hoverId : selId;
        return f != null && Core.nodeOf(state, f) != null ? f : null;
    }

    /** Nodes wired directly to this one, plus the project card when it starts the main line. */
    Set<String> connectedTo(String id) {
        Set<String> out = new HashSet<>();
        out.add(id);
        Map<String, Object> me = Core.nodeOf(state, id);
        if (me == null) return out;
        for (Object o : Core.links(me)) out.add(String.valueOf(o));
        for (Map<String, Object> n : nodes(state)) if (Core.links(n).contains(id)) out.add(s(n, "id"));
        for (Lane l : laneBoxes) {
            int i = -1;
            for (int k = 0; k < l.items.size(); k++) if (id.equals(s(l.items.get(k), "id"))) i = k;
            if (i > 0 && !Core.b(l.items.get(i), "cutIn")) out.add(s(l.items.get(i - 1), "id"));
            if (i >= 0 && i + 1 < l.items.size() && !Core.b(l.items.get(i + 1), "cutIn")) out.add(s(l.items.get(i + 1), "id"));
            String aid = anchorId(l.lane);
            boolean started = !Core.b(l.lane, "detached");
            if (i == 0 && started) out.add(l == mainBox ? "root" : aid);
            if (id.equals(aid) && started && l != mainBox && !l.items.isEmpty()) out.add(s(l.items.get(0), "id"));
        }
        return out;
    }

    private void buildEnvelope() {
        List<Integer> days = new ArrayList<>();
        for (Map<String, Object> n : nodes(state)) if (Core.counted(n)) days.add(dn(s(n, "date")));
        double span = Math.max(1, d1 - d0), sg = Math.max(3.2, span / 32);
        double e0 = envBase(d0, days, sg), e1 = envBase(d1, days, sg);
        double s0 = dayAt0(G.get("root").x - 40), s1 = dayAt0(headX + R_HEAD + 60);
        int n = (int) Math.min(8000, Math.ceil((s1 - s0) * 2) + 2);
        float[] v = new float[n];
        for (int i = 0; i < n; i++) {
            double d = s0 + i * .5;
            v[i] = (float) (days.isEmpty() ? .02 : d < d0 ? e0 * Math.exp(-(d0 - d) / 24) : d > d1 ? e1 * Math.exp(-(d - d1) / 24) : envBase(d, days, sg));
        }
        envS0 = s0;
        envV = v;
    }

    private static double envBase(double d, List<Integer> days, double sg) {
        double v = 0;
        for (int t : days) { double u = (d - t) / sg; if (u > -5 && u < 5) v += Math.exp(-.5 * u * u); }
        return .02 + .98 * (1 - Math.exp(-v * .42));
    }

    static double bandTop() { return -215; }
    double fourTop() { return bottom + 44; }

    double[] bounds() {
        return new double[]{G.get("root").x - 40, headX + R_HEAD + 60, bandTop() - 70, fourTop() + FOUR_H + 10};
    }

    Lane laneAt(double y) {
        for (Lane l : laneBoxes) if (y >= l.top && y < l.top + l.h) return l;
        return y < 0 ? laneBoxes.get(0) : laneBoxes.get(laneBoxes.size() - 1);
    }

    /* ---------- camera ---------- */
    double camX, camY, camZ = 1, tX, tY, tZ;
    boolean camOn = false;

    double SX(double x) { return x * camZ + camX; }
    double SY(double y) { return y * camZ + camY; }

    static double clamp(double v, double a, double b) { return Math.max(a, Math.min(b, v)); }

    void go(double z, double x, double y, boolean animate) {
        if (!animate || reduce) { camX = x; camY = y; camZ = z; camOn = false; }
        else { tX = x; tY = y; tZ = z; camOn = true; }
        need();
    }

    void fit(boolean animate) {
        double[] b = bounds();
        double aL = leftPad(), aT = rulerY + 34, aR = VW - 16, aB = VH - (compact() ? 16 : 118);
        double z = clamp(Math.min((aR - aL) / (b[1] - b[0]), (aB - aT) / (b[3] - b[2])), Z_MIN, 1);
        go(z, aL + ((aR - aL) - (b[1] - b[0]) * z) / 2 - b[0] * z, aT + ((aB - aT) - (b[3] - b[2]) * z) / 2 - b[2] * z, animate);
    }

    void centerOn(double wx, double wy, double z, boolean animate, boolean panelSafe) {
        boolean open = panelSafe && host.panelOpen();
        boolean side = panelSide();
        double aw = open && side ? VW - panelW() : VW;
        double ah = open && !side ? VH * .3 + rulerY : VH;
        double cy = open && !side ? rulerY + (ah - rulerY) / 2 + 30 : (rulerY + VH) / 2;
        go(z, (leftPad() + aw) / 2 - wx * z, cy - wy * z, animate);
    }

    void initialView() {
        if (VW <= 760 || shortScreen()) centerOn(xOf(today), spine, .62, false, false);
        else fit(false);
    }

    /** Keeps what is at the middle of the screen in the middle when the screen rotates. */
    void resized(double w, double h) {
        double wx = (VW / 2 - camX) / camZ, wy = (VH / 2 - camY) / camZ;
        boolean rotated = (w > h) != (VW > VH) && Math.abs(w - VW) > 1;
        VW = w;
        VH = h;
        if (rotated) { camX = VW / 2 - wx * camZ; camY = VH / 2 - wy * camZ; camOn = false; }
        need();
    }

    /* ---------- frame ---------- */
    boolean need = true;
    double T = 0;
    private double lastTs = 0;

    void need() { need = true; host.redraw(); }

    boolean moving() { return camOn || drag != null || pan != null || link != null || fin != null || warp != null || pinch != null; }

    /** Advances animations; returns true if anything is still moving towards its place. */
    boolean step(double ts) {
        double dt = Math.min(64, lastTs == 0 ? 16.7 : ts - lastTs);
        lastTs = ts;
        T = ts;
        if (camOn) {
            double q = 1 - Math.pow(.84, dt / 16.67);
            camX += (tX - camX) * q;
            camY += (tY - camY) * q;
            camZ += (tZ - camZ) * q;
            if (Math.abs(tX - camX) < .4 && Math.abs(tY - camY) < .4 && Math.abs(tZ - camZ) < .001) { camX = tX; camY = tY; camZ = tZ; camOn = false; }
        }
        double q = reduce ? 1 : 1 - Math.pow(.8, dt / 16.67);
        boolean settling = false;
        for (Geo g : G.values()) {
            if (drag != null && drag.moved && drag.id.equals(g.id)) continue;
            g.ax += (g.x - g.ax) * q;
            g.ay += (g.y - g.ay) * q;
            if (Math.abs(g.x - g.ax) < .2) g.ax = g.x; else settling = true;
            if (Math.abs(g.y - g.ay) < .2) g.ay = g.y; else settling = true;
        }
        return settling || camOn;
    }

    /* ---------- pointer interaction ---------- */
    static final class Drag { String id; double ox, oy, sx, sy; boolean moved, warp; int date; String lane, item; }
    static final class Pan { double sx, sy, cx, cy; boolean moved, root; }
    static final class Link { boolean h, moved; String from; double sx, sy; String tKind, tVal; int tDay; }
    static final class Pinch { double d, z, mx, my, cx, cy; }

    Drag drag;
    Pan pan;
    Link link;
    Pinch pinch;
    String hoverId, selId;

    /* every wire drawn this frame, in screen coordinates, so a tap can find the one under the finger */
    static final class Wire { String key, kind, id, to; double[][] pts; int color; }
    final List<Wire> wires = new ArrayList<>();
    String selWire;
    double selWx, selWy;

    void addWire(String kind, String id, String to, double[][] pts, int color) {
        Wire w = new Wire();
        w.kind = kind; w.id = id; w.to = to; w.pts = pts; w.color = color;
        w.key = kind + ":" + id + (to == null ? "" : ":" + to);
        wires.add(w);
    }

    Wire wireByKey(String key) {
        if (key == null) return null;
        for (Wire w : wires) if (key.equals(w.key)) return w;
        return null;
    }

    private static double segDist(double x, double y, double[] a, double[] e) {
        double dx = e[0] - a[0], dy = e[1] - a[1], q = dx * dx + dy * dy;
        double k = q > 0 ? clamp(((x - a[0]) * dx + (y - a[1]) * dy) / q, 0, 1) : 0;
        return Math.hypot(x - a[0] - k * dx, y - a[1] - k * dy);
    }

    Wire wireAt(double x, double y, double tol) {
        Wire best = null;
        double bd = tol;
        for (Wire w : wires) {
            double d = Double.MAX_VALUE;
            for (int i = 0; i + 1 < w.pts.length; i++) d = Math.min(d, segDist(x, y, w.pts[i], w.pts[i + 1]));
            if ("link".equals(w.kind)) d -= .5;
            if (d <= bd) { bd = d; best = w; }
        }
        return best;
    }

    void deleteWire(String key) {
        Wire w = wireByKey(key);
        if (w == null) return;
        Map<String, Object> src = null;
        if ("link".equals(w.kind)) {
            src = Core.nodeOf(state, w.id);
            if (src != null) {
                List<Object> keep = new ArrayList<>(Core.links(src));
                keep.remove(w.to);
                if (keep.isEmpty()) src.remove("links"); else src.put("links", keep);
            }
        } else if ("seq".equals(w.kind)) {
            Map<String, Object> n = Core.nodeOf(state, w.id);
            if (n != null) n.put("cutIn", Boolean.TRUE);
        } else {
            Map<String, Object> l = Core.laneOf(state, w.id);
            if (l != null) l.put("detached", Boolean.TRUE);
        }
        selWire = null;
        commit();
        if (src != null && "node".equals(host.panelMode()) && w.id.equals(selId)) host.openEdit(w.id, false);
        if ("set".equals(host.panelMode())) host.redrawPanel();
        toast("已刪除連線", "復原", undo);
    }

    /** The node right before this one on its own timeline, the one a timeline wire would come from. */
    private String prevInLane(Map<String, Object> n) {
        for (Lane l : laneBoxes) {
            if (!l.id.equals(s(n, "lane"))) continue;
            for (int i = 1; i < l.items.size(); i++) if (l.items.get(i) == n) return s(l.items.get(i - 1), "id");
        }
        return null;
    }

    /** Demo mode only: shows the screenshots what picking and deleting a wire look like. */
    void demoWire(boolean cut) {
        Wire seq = null;
        for (Wire w : wires) if ("seq".equals(w.kind)) { seq = w; break; }
        if (seq == null) return;
        if (cut) {
            for (Wire w : new ArrayList<>(wires)) if ("seq".equals(w.kind) || "start".equals(w.kind)) { deleteWire(w.key); break; }
            return;
        }
        double[] p = seq.pts[0], e = seq.pts[seq.pts.length - 1];
        double x = (p[0] + e[0]) / 2, y = (p[1] + e[1]) / 2;
        selWire = seq.key;
        selWx = (x - camX) / camZ;
        selWy = (y - camY) / camZ;
        need();
    }
    double touchX = -9999, touchY = -9999;
    private double lastTap = 0, lastTapX = 0, lastTapY = 0;
    private final Map<Integer, double[]> pts = new LinkedHashMap<>();

    /* far view (zoomed out) uses larger text inside the cards */
    boolean far() { return camZ < .62; }

    /** Distance from the top of a card to its first checklist row. */
    double itemsTop() { return far() ? 94 : 90; }

    private String pickPull(double wx, double wy, double slop) {
        // top-most cards are last in the list
        List<Geo> list = new ArrayList<>(G.values());
        for (int i = list.size() - 1; i >= 0; i--) {
            Geo g = list.get(i);
            double cx = g.ax + aw(g.id) / 2;
            if (Math.abs(wx - cx) <= slop && (Math.abs(wy - g.ay) <= slop || Math.abs(wy - (g.ay + g.h)) <= slop)) return g.id;
        }
        return null;
    }

    private Geo pickCard(double wx, double wy) {
        List<Geo> list = new ArrayList<>(G.values());
        for (int i = list.size() - 1; i >= 0; i--) {
            Geo g = list.get(i);
            if (wx >= g.ax && wx <= g.ax + aw(g.id) && wy >= g.ay && wy <= g.ay + g.h) return g;
        }
        return null;
    }

    private String pickItem(Geo g, double wy) {
        Map<String, Object> n = g.n;
        if (n == null || Core.isPortal(n)) return null;
        double rel = wy - g.ay - itemsTop();
        if (rel < 0) return null;
        int row = (int) (rel / IT_H);
        List<Map<String, Object>> it = items(n);
        if (it.isEmpty()) return row == 0 ? "__self" : null;
        if (row < Math.min(it.size(), IT_MAX)) return s(it.get(row), "id");
        return null;
    }

    private Lane pickTail(double wx, double wy, double slop) {
        for (Lane l : laneBoxes) if (wx >= l.tx - slop && wx <= l.tx + 40 + slop && wy >= l.ty - slop && wy <= l.ty + 40 + slop) return l;
        return null;
    }

    void pointerDown(int pid, double x, double y) {
        pts.put(pid, new double[]{x, y});
        touchX = x;
        touchY = y;
        if (pts.size() == 2) {
            drag = null; pan = null; link = null;
            List<double[]> v = new ArrayList<>(pts.values());
            double[] a = v.get(0), b = v.get(1);
            Pinch p = new Pinch();
            p.d = Math.hypot(a[0] - b[0], a[1] - b[1]);
            p.z = camZ;
            p.mx = (a[0] + b[0]) / 2;
            p.my = (a[1] + b[1]) / 2;
            p.cx = camX;
            p.cy = camY;
            pinch = p;
            return;
        }
        if (pts.size() > 2) return;
        camOn = false;
        if (selWire != null) { selWire = null; need(); }
        double wx = (x - camX) / camZ, wy = (y - camY) / camZ;
        /* fingers need bigger targets than the drawn dots, whatever the zoom */
        double slopPull = Math.max(far() ? 18 : 15.5, 16 / camZ);
        String pull = pickPull(wx, wy, slopPull);
        if (pull != null) {
            Link l = new Link();
            l.from = pull; l.sx = x; l.sy = y;
            link = l;
            hoverId = "root".equals(pull) ? null : pull;
            need();
            return;
        }
        double slopPort = Math.max(14.5, 16 / camZ);
        for (Geo g : G.values()) {
            if ("root".equals(g.id)) continue;
            if (Math.abs(wx - (g.ax + W)) <= slopPort && Math.abs(wy - (g.ay + 52)) <= slopPort) {
                Link l = new Link();
                l.h = true; l.from = g.id; l.sx = x; l.sy = y;
                link = l;
                hoverId = g.id;
                need();
                return;
            }
        }
        Geo card = pickCard(wx, wy);
        hoverId = card != null && !"root".equals(card.id) ? card.id : null;
        if (card != null && "root".equals(card.id)) {
            Pan p = new Pan();
            p.sx = x; p.sy = y; p.cx = camX; p.cy = camY; p.root = true;
            pan = p;
            return;
        }
        if (card != null) {
            Drag d = new Drag();
            d.id = card.id; d.ox = wx - card.ax; d.oy = wy - card.ay; d.sx = x; d.sy = y;
            if (Core.isPortal(card.n)) {
                // the 傳送 button sits at the right end of the first row
                double bw = far() ? 74 : 58, bh = far() ? 36 : 28;
                boolean onBtn = wx >= card.ax + W - bw && wy <= card.ay + bh;
                d.warp = onBtn && Core.project(store, s(card.n, "target")) != null;
            } else d.item = pickItem(card, wy);
            drag = d;
            // bring to front
            G.remove(card.id);
            G.put(card.id, card);
            need();
            return;
        }
        Pan p = new Pan();
        p.sx = x; p.sy = y; p.cx = camX; p.cy = camY;
        pan = p;
        need();
    }

    void pointerMove(int pid, double x, double y) {
        if (!pts.containsKey(pid)) return;
        pts.put(pid, new double[]{x, y});
        touchX = x;
        touchY = y;
        need();
        if (pinch != null && pts.size() >= 2) {
            List<double[]> v = new ArrayList<>(pts.values());
            double[] a = v.get(0), b = v.get(1);
            double d = Math.hypot(a[0] - b[0], a[1] - b[1]), mx = (a[0] + b[0]) / 2, my = (a[1] + b[1]) / 2;
            double z = clamp(pinch.z * d / pinch.d, Z_MIN, Z_MAX), wx = (pinch.mx - pinch.cx) / pinch.z, wy = (pinch.my - pinch.cy) / pinch.z;
            camZ = z;
            camX = mx - wx * z;
            camY = my - wy * z;
            return;
        }
        if (link != null) {
            if (Math.hypot(x - link.sx, y - link.sy) > 5) link.moved = true;
            double wx = (x - camX) / camZ, wy = (y - camY) / camZ;
            if (link.h) {
                link.tKind = null;
                for (Map<String, Object> n : nodes(state)) {
                    Geo g = G.get(s(n, "id"));
                    if (g != null && !g.id.equals(link.from) && wx >= g.ax && wx <= g.ax + W && wy >= g.ay && wy <= g.ay + g.h) { link.tKind = "node"; link.tVal = g.id; break; }
                }
                if (link.tKind == null && wy >= 0 && wy < bottom) { link.tKind = "spot"; link.tVal = laneAt(wy).id; link.tDay = dayAt(wx - W / 2); }
                return;
            }
            Map<String, Object> src = Core.nodeOf(state, link.from);
            String srcLane = src == null ? null : s(src, "lane");
            if (wy < 0) { link.tKind = "add"; link.tVal = "top"; }
            else if (wy >= bottom) { link.tKind = "add"; link.tVal = "bottom"; }
            else {
                Lane l = laneAt(wy);
                if (l == mainBox) { link.tKind = "main"; link.tVal = null; }
                else if (l.id.equals(srcLane)) { link.tKind = "own"; link.tVal = null; }
                else { link.tKind = "lane"; link.tVal = l.id; }
            }
            return;
        }
        if (pan != null) {
            if (Math.hypot(x - pan.sx, y - pan.sy) > 4) pan.moved = true;
            camX = pan.cx + x - pan.sx;
            camY = pan.cy + y - pan.sy;
            return;
        }
        if (drag != null) {
            if (!drag.moved && Math.hypot(x - drag.sx, y - drag.sy) < 5) return;
            drag.moved = true;
            Geo g = G.get(drag.id);
            if (g == null) { drag = null; return; }
            double wx = (x - camX) / camZ, wy = (y - camY) / camZ;
            g.ax = wx - drag.ox;
            g.ay = wy - drag.oy;
            drag.date = dayAt(g.ax);
            drag.lane = laneAt(g.ay + H / 2).id;
        }
    }

    void pointerUp(int pid, double x, double y, boolean canceled, double now) {
        pts.remove(pid);
        if (pinch != null) { if (pts.size() < 2) pinch = null; need(); return; }
        if (link != null) { Link k = link; link = null; need(); if (k.moved && !canceled && k.tKind != null) endLink(k); return; }
        if (drag != null) { endDrag(canceled); return; }
        if (pan != null) { endPan(x, y, canceled, now); }
    }

    private void endLink(Link k) {
        if (k.h) {
            Map<String, Object> src = Core.nodeOf(state, k.from);
            if (src == null) return;
            if ("node".equals(k.tKind)) {
                List<Object> links = Core.links(src);
                if (!(src.get("links") instanceof List)) src.put("links", links);
                Map<String, Object> to = Core.nodeOf(state, k.tVal);
                if (to != null && Core.b(to, "cutIn") && s(to, "lane").equals(s(src, "lane")) && k.from.equals(prevInLane(to))) {
                    if (links.isEmpty()) src.remove("links");
                    to.remove("cutIn");
                    commit();
                    toast("已接回「" + s(to, "title") + "」", "復原", undo);
                    return;
                }
                if (links.contains(k.tVal)) { toast("這兩個節點已經連接了", null, null); return; }
                links.add(k.tVal);
                commit();
                if ("node".equals(host.panelMode()) && k.from.equals(selId)) host.openEdit(k.from, false);
                toast("已連接到「" + s(to, "title") + "」", "復原", undo);
            } else if ("spot".equals(k.tKind)) {
                host.openNew(k.tVal, ds(k.tDay), k.tVal.equals(s(src, "lane")) ? null : k.from);
            }
            return;
        }
        String fromName;
        if ("root".equals(k.from)) fromName = s(state, "name");
        else { Map<String, Object> f = Core.nodeOf(state, k.from); fromName = f == null ? "" : s(f, "title"); }
        if ("lane".equals(k.tKind)) {
            Map<String, Object> lane = Core.laneOf(state, k.tVal);
            lane.put("from", k.from);
            lane.remove("detached");
            commit();
            if ("set".equals(host.panelMode())) host.redrawPanel();
            toast("「" + s(lane, "name") + "」改從「" + fromName + "」開始", "復原", undo);
        } else if ("add".equals(k.tKind)) {
            String id = Core.uid();
            Map<String, Object> lane = Json.map("id", id, "name", "新支線", "kind", "branch", "color", LANE_COLORS[lanes(state).size() % LANE_COLORS.length], "from", k.from);
            List<Object> ls = Json.arr(state.get("lanes"));
            if ("top".equals(k.tVal)) ls.add(0, lane); else ls.add(lane);
            commit();
            host.openSettings(id);
        }
    }

    private void endDrag(boolean canceled) {
        Drag d = drag;
        drag = null;
        if (d.moved && !canceled) {
            Map<String, Object> n = Core.nodeOf(state, d.id);
            if (n != null) {
                if (!String.valueOf(d.lane).equals(s(n, "lane"))) n.remove("cutIn");
                n.put("date", ds(d.date));
                n.put("lane", d.lane);
                commit();
                if (d.id.equals(selId) && "node".equals(host.panelMode())) host.openEdit(d.id, false);
            }
        } else if (!d.moved && !canceled) {
            if (d.warp) teleport(d.id);
            else if (d.item != null) {
                Map<String, Object> n = Core.nodeOf(state, d.id);
                if (n != null) {
                    if ("__self".equals(d.item)) { n.put("manualDone", !Core.b(n, "manualDone")); commit(); }
                    else for (Map<String, Object> it : items(n)) if (d.item.equals(s(it, "id"))) { it.put("done", !Core.b(it, "done")); commit(); break; }
                    if ("node".equals(host.panelMode()) && d.id.equals(selId)) host.openEdit(d.id, false);
                }
            } else host.openEdit(d.id, true);
        } else layout();
        need();
    }

    private void endPan(double x, double y, boolean canceled, double now) {
        boolean moved = pan.moved, root = pan.root;
        pan = null;
        if (canceled || moved) return;
        if (root) { host.openSettings(null); return; }
        double wx = (x - camX) / camZ, wy = (y - camY) / camZ;
        Lane tail = pickTail(wx, wy, Math.max(0, 8 / camZ));
        if (tail != null) { host.openNew(tail.id, null, null); return; }
        Wire hit = wireAt(x, y, 14);
        if (hit != null) {
            lastTap = 0;
            selWire = hit.key;
            selWx = wx;
            selWy = wy;
            hoverId = null;
            need();
            return;
        }
        if (total >= 100 && Math.abs(wx - headX) < R_HEAD * .95 && Math.abs(wy - headY) < R_HEAD * 1.3) { playFinale(); return; }
        if (now - lastTap < 340 && Math.hypot(x - lastTapX, y - lastTapY) < 16) {
            lastTap = 0;
            host.openNew(laneAt(wy).id, ds(dayAt(wx - W / 2)), null);
        } else {
            lastTap = now;
            lastTapX = x;
            lastTapY = y;
            hoverId = null;
            need();
        }
    }

    /* ---------- undo and redo ---------- */
    static final class Entry { String before, after, label; boolean typing; }
    static final class Hist { final List<Entry> undo = new ArrayList<>(), redo = new ArrayList<>(); String last; double at; }
    private static final int HIST_MAX = 100;
    private static final long HIST_CHARS = 24000000L;
    private static final long COALESCE_MS = 1200;
    final Map<String, Hist> HIST = new HashMap<>();

    final Runnable undo = new Runnable() { @Override public void run() { histStep(-1); } };

    static String histSig(Map<String, Object> p) {
        Map<String, Object> c = Core.canon(p);
        c.remove("density");
        return Json.stable(c);
    }

    Hist histFor(Map<String, Object> p) {
        String id = s(p, "id");
        Hist h = HIST.get(id);
        if (h == null) { h = new Hist(); h.last = histSig(p); HIST.put(id, h); }
        return h;
    }

    void histTrack() { for (Map<String, Object> p : Core.projects(store)) histFor(p).last = histSig(p); }

    private static String histLabel(String aJ, String bJ) {
        Map<String, Object> a = Json.obj(Json.parse(aJ)), b = Json.obj(Json.parse(bJ));
        Map<String, Map<String, Object>> A = new HashMap<>(), B = new HashMap<>();
        for (Map<String, Object> n : nodes(a)) A.put(s(n, "id"), n);
        for (Map<String, Object> n : nodes(b)) B.put(s(n, "id"), n);
        List<Map<String, Object>> added = new ArrayList<>(), gone = new ArrayList<>(), changed = new ArrayList<>();
        for (Map<String, Object> n : nodes(b)) {
            Map<String, Object> o = A.get(s(n, "id"));
            if (o == null) added.add(n);
            else if (!Json.stable(o).equals(Json.stable(n))) changed.add(n);
        }
        for (Map<String, Object> n : nodes(a)) if (!B.containsKey(s(n, "id"))) gone.add(n);
        if (added.size() > 1 || gone.size() > 1) return (added.size() + gone.size()) + " 個節點";
        if (!added.isEmpty()) return "新增「" + s(added.get(0), "title") + "」";
        if (!gone.isEmpty()) return "刪除「" + s(gone.get(0), "title") + "」";
        if (changed.size() == 1) return "修改「" + s(changed.get(0), "title") + "」";
        if (changed.size() > 1) return changed.size() + " 個節點";
        if (!Json.stable(a.get("lanes")).equals(Json.stable(b.get("lanes")))) return "時間線設定";
        if (!String.valueOf(a.get("name")).equals(String.valueOf(b.get("name")))) return "專案名稱";
        return "上一個動作";
    }

    private void recordEdit() {
        Hist h = histFor(state);
        double now = System.currentTimeMillis();
        String cur = histSig(state);
        if (cur.equals(h.last)) return;
        boolean typing = host.typingInPanel();
        Entry top = h.undo.isEmpty() ? null : h.undo.get(h.undo.size() - 1);
        if (typing && top != null && top.typing && now - h.at < COALESCE_MS) {
            top.after = cur;
        } else {
            Entry e = new Entry();
            e.before = h.last;
            e.after = cur;
            e.typing = typing;
            h.undo.add(e);
            long size = 0;
            for (Entry x : h.undo) size += x.before.length() + x.after.length();
            while (h.undo.size() > 1 && (h.undo.size() > HIST_MAX || size > HIST_CHARS)) {
                Entry o = h.undo.remove(0);
                size -= o.before.length() + o.after.length();
            }
        }
        if (top != null && cur.equals(top.after)) top.label = null;
        h.redo.clear();
        h.last = cur;
        h.at = now;
    }

    void histStep(int dir) {
        if (hubOpen || loginOpen || finaleOpen || fin != null) return;
        Hist h = histFor(state);
        List<Entry> from = dir < 0 ? h.undo : h.redo, to = dir < 0 ? h.redo : h.undo;
        if (from.isEmpty()) { toast(dir < 0 ? "沒有可以復原的動作" : "沒有可以重做的動作", null, null); return; }
        Entry e = from.remove(from.size() - 1);
        Map<String, Object> curP = Json.obj(Json.parse(histSig(state)));
        Map<String, Object> eb = Json.obj(Json.parse(e.before)), ea = Json.obj(Json.parse(e.after));
        Map<String, Object> merged = dir < 0 ? Core.merge3(ea, eb, curP) : Core.merge3(eb, ea, curP);
        merged.put("density", state.get("density"));
        Core.applyProject(store, s(state, "id"), Json.stable(merged));
        to.add(e);
        h.last = histSig(state);
        h.at = 0;
        refresh();
        host.scheduleSave();
        String mode = host.panelMode();
        if ("node".equals(mode) && selId != null) {
            if (Core.nodeOf(state, selId) != null) host.openEdit(selId, false);
            else host.closePanel();
        } else if ("set".equals(mode)) host.redrawPanel();
        toast((dir < 0 ? "已復原：" : "已重做：") + entryLabel(e), null, null);
    }

    String entryLabel(Entry e) {
        if (e.label == null) e.label = histLabel(e.before, e.after);
        return e.label;
    }

    /* ---------- refresh and commit ---------- */
    void refresh() {
        histFor(state);
        layout();
        rev++;
        host.afterRefresh();
        need();
    }

    /** commit(): an edit happened. Records undo, redraws, saves, and celebrates reaching 100%. */
    void commit() {
        recordEdit();
        boolean was = total >= 100;
        refresh();
        host.scheduleSave();
        if (!was && Core.isWon(store, state)) playFinale();
    }

    void clearBoard() {
        G.clear();
        seq.clear();
        hoverId = null;
        selId = null;
        wires.clear();
        selWire = null;
    }

    /* ---------- screens ---------- */
    boolean hubOpen = false, loginOpen = false;
    final List<String> warpStack = new ArrayList<>();
    double hubScroll = 0, hubContentH = 0;
    final Map<String, Integer> delArm = new HashMap<>();

    /** warpFx */
    double[] warp;

    void showBoard(String id) {
        Map<String, Object> p = Core.project(store, id);
        if (p == null) p = Core.projects(store).get(0);
        hubOpen = false;
        if (host.panelOpen()) host.closePanel();
        state = p;
        store.put("current", p.get("id"));
        ref = refDay();
        clearBoard();
        refresh();
        initialView();
    }

    void teleport(String nodeId) {
        Map<String, Object> n = Core.nodeOf(state, nodeId);
        final Map<String, Object> t = n == null ? null : Core.project(store, s(n, "target"));
        if (t == null) return;
        Geo g = G.get(nodeId);
        warp = new double[]{SX(g.ax + W / 2), SY(g.ay + H / 2), T};
        warpStack.add(s(state, "id"));
        host.later(reduce ? 0 : 230, new Runnable() {
            @Override public void run() {
                showBoard(s(t, "id"));
                toast("已傳送到「" + s(t, "name") + "」", null, null);
            }
        });
        need();
    }

    void back() {
        if (warpStack.isEmpty()) return;
        String id = warpStack.remove(warpStack.size() - 1);
        if (Core.project(store, id) != null) {
            warp = new double[]{VW / 2, VH / 2, T};
            showBoard(id);
        } else toast("那個專案已無法開啟", null, null);
    }

    void showHub() {
        selWire = null;
        if (host.panelOpen()) host.closePanel();
        hubOpen = true;
        hubScroll = 0;
        delArm.clear();
        for (Map<String, Object> p : Core.projects(store)) Core.projTotal(store, p);
        rev++;
        need();
    }

    void setDensity(String d) {
        int mid = dayAt((VW / 2 - camX) / camZ);
        state.put("density", d);
        commit();
        go(camZ, VW / 2 - xOf(mid) * camZ, camY, false);
    }

    void centerToday() {
        centerOn(xOf(today), spine, Math.max(camZ, .7), true, false);
    }

    /* ---------- finale ---------- */
    static final class Fin {
        double t;
        final List<double[]> burst = new ArrayList<>();
        final List<String> order = new ArrayList<>();
    }

    Fin fin;
    boolean finaleOpen = false;
    double finaleAt = 0;

    void playFinale() {
        if (fin != null || hubOpen) return;
        if (host.panelOpen()) host.closePanel();
        if (reduce) { showFinale(); return; }
        fit(true);
        Fin f = new Fin();
        f.t = T;
        List<Map<String, Object>> sorted = new ArrayList<>(nodes(state));
        sorted.sort(new Comparator<Map<String, Object>>() {
            @Override public int compare(Map<String, Object> a, Map<String, Object> b) { return dn(s(a, "date")) - dn(s(b, "date")); }
        });
        for (Map<String, Object> n : sorted) f.order.add(s(n, "id"));
        fin = f;
        need();
    }

    void showFinale() {
        finaleOpen = true;
        finaleAt = T;
        need();
    }

    /* ---------- toast ---------- */
    String toastMsg, toastAct;
    Runnable toastFn;
    double toastUntil = 0;

    void toast(String msg, String act, Runnable fn) {
        toastMsg = msg;
        toastAct = act;
        toastFn = fn;
        toastUntil = System.currentTimeMillis() + (act != null ? 7000 : 2600);
        need();
    }

    String syncText = "";

    /* ---------- on-screen buttons, rebuilt on every frame by the painter ---------- */
    static final class Btn {
        double x, y, w, h;
        String key, arg;
    }

    final List<Btn> btns = new ArrayList<>();
    private Btn pressed;
    private double pressX, pressY, scrollStart;
    private boolean scrolling;
    String pressedKey, pressedArg;

    Btn addBtn(double x, double y, double w, double h, String key, String arg) {
        Btn b = new Btn();
        b.x = x; b.y = y; b.w = w; b.h = h; b.key = key; b.arg = arg;
        btns.add(b);
        return b;
    }

    private Btn btnAt(double x, double y) {
        for (int i = btns.size() - 1; i >= 0; i--) {
            Btn b = btns.get(i);
            if (x >= b.x - 3 && x <= b.x + b.w + 3 && y >= b.y - 3 && y <= b.y + b.h + 3) return b;
        }
        return null;
    }

    private boolean anyPointer = false;

    /** First finger: on-screen buttons take it, otherwise the current screen. Returns true when a button took it. */
    boolean touchDown(int pid, double x, double y, boolean first) {
        if (first) {
            pressed = btnAt(x, y);
            pressX = x;
            pressY = y;
            scrolling = false;
            scrollStart = hubScroll;
            pressedKey = pressed == null ? null : pressed.key;
            pressedArg = pressed == null ? null : pressed.arg;
            anyPointer = true;
            need();
            if (pressed != null && !hubOpen) return true;
            if (finaleOpen || loginOpen || hubOpen) return true;
            /* the top bar blocks the canvas under it */
            if (y < rulerY) return true;
        } else if (pressed != null || finaleOpen || loginOpen || hubOpen || !anyPointer) {
            return true;
        }
        pointerDown(pid, x, y);
        return false;
    }

    void touchMove(int pid, double x, double y) {
        if (hubOpen && pid >= 0) {
            double dy = y - pressY;
            if (!scrolling && Math.abs(dy) > 6) { scrolling = true; pressedKey = null; pressedArg = null; pressed = null; }
            if (scrolling) { hubScroll = clamp(scrollStart - dy, 0, Math.max(0, hubContentH - VH)); need(); }
            return;
        }
        if (pressed != null && Math.hypot(x - pressX, y - pressY) > 14) { pressed = null; pressedKey = null; pressedArg = null; need(); }
    }

    void touchUp(double x, double y, boolean canceled) {
        Btn b = pressed;
        pressed = null;
        pressedKey = null;
        pressedArg = null;
        anyPointer = false;
        need();
        if (canceled || scrolling) return;
        if (b != null) { fire(b.key, b.arg); return; }
        if (finaleOpen) { finaleOpen = false; need(); }
    }

    boolean isPressed(String key, String arg) {
        return key.equals(pressedKey) && (arg == null ? pressedArg == null : arg.equals(pressedArg));
    }

    /** All actions of the on-screen buttons. */
    private void fire(String key, String arg) {
        switch (key) {
            case "density": setDensity(arg); break;
            case "undo": histStep(-1); break;
            case "redo": histStep(1); break;
            case "back": back(); break;
            case "hub": case "pname": showHub(); break;
            case "fit": fit(true); break;
            case "today": centerToday(); break;
            case "settings": host.openSettings(null); break;
            case "add": host.openNew(null, null, null); break;
            case "tag": host.openSettings(arg); break;
            case "finGo": finaleOpen = false; need(); break;
            case "wireDel": deleteWire(selWire); break;
            case "toastAct": {
                Runnable f = toastFn;
                toastMsg = null;
                toastFn = null;
                if (f != null) f.run();
                need();
                break;
            }
            case "gBtn": host.loginTap(); break;
            case "mini": miniGo(arg); break;
            default: host.hubAction(key, arg);
        }
    }

    double miniS, miniOx, miniOy, miniX, miniY;

    private void miniGo(String ignored) {
        if (miniS == 0) return;
        centerOn((pressX - miniX - miniOx) / miniS, (pressY - miniY - miniOy) / miniS, camZ, false, false);
    }
}
