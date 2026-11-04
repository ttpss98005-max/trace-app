package tw.trace.app;

import static tw.trace.app.Core.dn;
import static tw.trace.app.Core.ds;
import static tw.trace.app.Core.items;
import static tw.trace.app.Core.nodes;
import static tw.trace.app.Core.s;
import static tw.trace.app.Theme.alpha;

import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Draws every screen except the form panel. The drawing is a line-by-line port of the web canvas code. */
final class Painter {
    /** Fonts, supplied by the app. */
    static final class Fonts {
        Typeface cjk, cjkMed, cjkBold, mono, monoMed, disp, dispSemi;
    }

    /** Facts about sharing that live in the sync engine. */
    interface Info {
        String others(String projectId);
        boolean ownedByOther(String projectId);
        int memberCount(String projectId);
        boolean signedIn();
        String loginNote();
        boolean loginBusy();
    }

    final Board b;
    final Theme th;
    final Fonts F;
    Info info;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final Random rng = new Random();

    private double VW, VH, T, z, camX, camY;

    Painter(Board b, Theme th, Fonts f) {
        this.b = b;
        this.th = th;
        this.F = f;
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        text.setStyle(Paint.Style.FILL);
        text.setTextLocale(java.util.Locale.TRADITIONAL_CHINESE);
    }

    /* ---------- small tools ---------- */
    private static float f(double v) { return (float) v; }
    private static double q(double v) { return Math.round(v) + .5; }
    private static double clamp(double v, double a, double b) { return Math.max(a, Math.min(b, v)); }
    private double SX(double x) { return x * z + camX; }
    private double SY(double y) { return y * z + camY; }

    private Paint fillOf(int c) { fill.setColor(c); return fill; }

    private Paint strokeOf(int c, double w) {
        stroke.setColor(c);
        stroke.setStrokeWidth(f(w));
        stroke.setPathEffect(null);
        return stroke;
    }

    private void dash(double phase, float... pattern) { stroke.setPathEffect(new DashPathEffect(pattern, f(phase))); }

    private void fr(Canvas c, double x, double y, double w, double h, int col) { c.drawRect(f(x), f(y), f(x + w), f(y + h), fillOf(col)); }

    private void line(Canvas c, double x0, double y0, double x1, double y1) { c.drawLine(f(x0), f(y0), f(x1), f(y1), stroke); }

    private void rectLine(Canvas c, double x, double y, double w, double h) {
        double x0 = Math.round(x) + .5, y0 = Math.round(y) + .5;
        c.drawRect(f(x0), f(y0), f(x0 + Math.round(w)), f(y0 + Math.round(h)), stroke);
    }

    private void font(Typeface t, double size, int color) {
        text.setTypeface(t);
        text.setTextSize(f(size));
        text.setColor(color);
    }

    private void txt(Canvas c, String s, double x, double y) { c.drawText(s, f(x), f(y), text); }

    private double tw(String s) { return text.measureText(s); }

    /** Baseline that centres the current font in a line box. */
    private double base(double top, double lineH) {
        Paint.FontMetrics m = text.getFontMetrics();
        return top + (lineH - (m.descent - m.ascent)) / 2 - m.ascent;
    }

    private String ell(String s, double max) {
        if (tw(s) <= max) return s;
        int n = text.breakText(s, true, f(Math.max(0, max - tw("…"))), null);
        return s.substring(0, Math.max(0, n)) + "…";
    }

    private final Map<String, String[]> wrapCache = new HashMap<>();

    /** Word-aware wrapping that also breaks between CJK characters; the last line gets an ellipsis if cut. */
    private String[] wrap(String s, double max, int lines) {
        String key = s + "|" + Math.round(text.getTextSize() * 10) + "|" + Math.round(max) + "|" + lines + "|" + System.identityHashCode(text.getTypeface());
        String[] hit = wrapCache.get(key);
        if (hit != null) return hit;
        List<String> out = new ArrayList<>();
        String rest = s.replace('\n', ' ');
        while (!rest.isEmpty() && out.size() < lines) {
            int n = text.breakText(rest, true, f(Math.max(1, max)), null);
            if (n <= 0) n = 1;
            if (n >= rest.length()) { out.add(rest); rest = ""; break; }
            int cut = n;
            char a = rest.charAt(n - 1), bc = rest.charAt(n);
            if ("，。、；：！？）」』》".indexOf(bc) >= 0 && n > 1) { cut = n - 1; out.add(rest.substring(0, cut).trim()); rest = rest.substring(cut).trim(); continue; }
            if (a < 0x2E80 && bc < 0x2E80 && a != ' ' && bc != ' ') {
                int sp = n - 1;
                while (sp > 0 && rest.charAt(sp) != ' ' && rest.charAt(sp) < 0x2E80) sp--;
                if (sp > 0) cut = sp + (rest.charAt(sp) == ' ' ? 1 : 1);
            }
            out.add(rest.substring(0, cut).trim());
            rest = rest.substring(cut).trim();
        }
        if (!rest.isEmpty() && !out.isEmpty()) {
            String last = out.get(out.size() - 1) + rest;
            out.set(out.size() - 1, ell(last, max));
        }
        String[] r = out.isEmpty() ? new String[]{""} : out.toArray(new String[0]);
        if (wrapCache.size() > 1500) wrapCache.clear();
        wrapCache.put(key, r);
        return r;
    }

    private void poly(Path p, double[][] pts) {
        for (int i = 0; i < pts.length; i++) {
            if (i == 0) p.moveTo(f(q(pts[i][0])), f(q(pts[i][1])));
            else p.lineTo(f(q(pts[i][0])), f(q(pts[i][1])));
        }
    }

    private static String pad2(int v) { return v < 10 ? "0" + v : String.valueOf(v); }

    private double hash(double a, double bb) {
        double s = Math.sin(a * 127.1 + bb * 311.7) * 43758.5453;
        return s - Math.floor(s);
    }

    private double rnd(double a, double bb) { return a + rng.nextDouble() * (bb - a); }

    private int cacheRev = -1;
    private final Map<String, Double> totalMemo = new HashMap<>();
    private final Map<String, Boolean> wonMemo = new HashMap<>();

    private void checkMemo() {
        if (cacheRev != b.rev) { cacheRev = b.rev; totalMemo.clear(); wonMemo.clear(); }
    }

    private double totalCached(Map<String, Object> p) {
        checkMemo();
        Double v = totalMemo.get(s(p, "id"));
        if (v == null) { v = Core.projTotal(b.store, p); totalMemo.put(s(p, "id"), v); }
        return v;
    }

    private boolean isWonCached(Map<String, Object> p) {
        checkMemo();
        Boolean v = wonMemo.get(s(p, "id"));
        if (v == null) { v = Core.isWon(b.store, p); wonMemo.put(s(p, "id"), v); }
        return v;
    }

    /* ---------- entry ---------- */
    void draw(Canvas c, double ts) {
        VW = b.VW;
        VH = b.VH;
        T = ts;
        z = b.camZ;
        camX = b.camX;
        camY = b.camY;
        b.btns.clear();
        c.drawColor(th.bg);
        if (b.loginOpen) {
            drawLogin(c);
        } else if (b.hubOpen) {
            drawHub(c);
            drawWarp(c);
        } else {
            drawBoard(c);
        }
        if (b.finaleOpen) drawFinaleBox(c);
        drawToast(c);
    }

    private void drawBoard(Canvas c) {
        // the bar first, because everything below depends on its height
        Path scratch = path;
        scratch.reset();
        double hudH = measureHud();
        if (Math.abs(hudH - b.rulerY) > .5) b.rulerY = hudH;
        drawGrid(c);
        drawFrames(c);
        drawTimeGrid(c);
        drawLanes(c);
        curveBand(c);
        waveBand(c);
        drawHead(c);
        drawWires(c);
        drawCards(c);
        drawTags(c);
        drawRuler(c);
        drawWarp(c);
        drawLink(c);
        drawFin(c);
        drawMini(c);
        drawFoot(c);
        if (nodes(b.state).isEmpty()) drawEmpty(c);
        drawHud(c);
    }

    /* ---------- background of the table ---------- */
    private void drawGrid(Canvas c) {
        double s = 40;
        while (s * z < 22) s *= 2;
        double sp = s * z, ox = ((camX % sp) + sp) % sp, oy = ((camY % sp) + sp) % sp;
        int col = alpha(th.ink, .2);
        fillOf(col);
        for (double x = ox; x < VW; x += sp) for (double y = oy; y < VH; y += sp) c.drawRect(f(x - .6), f(y - .6), f(x + .6), f(y + .6), fill);
    }

    private void drawFrames(Canvas c) {
        double cell = 300;
        int i0 = (int) Math.floor((-camX) / z / cell) - 1, i1 = (int) Math.floor((VW - camX) / z / cell) + 1;
        int j0 = (int) Math.floor((-camY) / z / cell) - 1, j1 = (int) Math.floor((VH - camY) / z / cell) + 1;
        strokeOf(alpha(th.ink, .13), 1);
        path.reset();
        for (int i = i0; i <= i1; i++) for (int j = j0; j <= j1; j++) {
            if (hash(i, j) > .5) continue;
            int k = (int) Math.floor(hash(i + 7, j) * 4);
            double w = 34 * Math.pow(1.55, k) * z, h = w * (1.08 + hash(i, j + 9) * .3);
            double x = SX(i * cell + hash(i, j + 3) * cell * .7), y = SY(j * cell + hash(i + 2, j + 5) * cell * .7);
            if (x < -w || y < -h || x > VW + w || y > VH + h) continue;
            float x0 = f(Math.round(x) + .5), y0 = f(Math.round(y) + .5);
            path.addRect(x0, y0, x0 + Math.round(w), y0 + Math.round(h), Path.Direction.CW);
        }
        c.drawPath(path, stroke);
    }

    private void drawLanes(Canvas c) {
        for (Board.Lane l : b.laneBoxes) {
            double y = SY(l.top);
            boolean lit = (b.drag != null && b.drag.moved && l.id.equals(b.drag.lane)) || (b.link != null && b.link.moved && "lane".equals(b.link.tKind) && l.id.equals(b.link.tVal));
            if (lit) fr(c, 0, y, VW, l.h * z, alpha(Theme.hex(s(l.lane, "color")), .09));
            strokeOf(alpha(th.ink, .42), 1);
            line(c, 0, q(y), VW, q(y));
            double yy = q(y);
            strokeOf(alpha(th.ink, .3), 1);
            double st = b.ppd() * 7 * z, o0 = ((camX + b.xOf(0)) % st + st) % st;
            if (st > 9) {
                path.reset();
                for (double x = o0; x < VW; x += st) { path.moveTo(f(q(x)), f(yy)); path.lineTo(f(q(x)), f(yy + 4)); }
                c.drawPath(path, stroke);
            }
        }
        double by = q(SY(b.bottom));
        strokeOf(alpha(th.ink, .42), 1);
        line(c, 0, by, VW, by);
    }

    /** monthsIn(): first days of the months between two days. */
    private List<int[]> monthsIn(int a, int e) {
        List<int[]> out = new ArrayList<>();
        LocalDate d = LocalDate.ofEpochDay(a).withDayOfMonth(1);
        while (d.toEpochDay() <= e) {
            out.add(new int[]{(int) d.toEpochDay(), d.getYear(), d.getMonthValue() - 1});
            d = d.plusMonths(1);
        }
        return out;
    }

    private void drawTimeGrid(Canvas c) {
        int a = b.dayAt((-camX) / z) - 31, e = b.dayAt((VW - camX) / z) + 1;
        strokeOf(alpha(th.ink, .12), 1);
        double yTop = SY(Board.bandTop()), yBot = SY(b.fourTop() + Board.FOUR_H);
        path.reset();
        for (int[] m : monthsIn(a, e)) { double x = q(SX(b.xOf(m[0]))); path.moveTo(f(x), f(yTop)); path.lineTo(f(x), f(yBot)); }
        c.drawPath(path, stroke);
        double tx0 = SX(b.xOf(b.today));
        int remain = b.d1 - b.today;
        if (remain > 4) {
            font(F.mono, 10, alpha(th.ink, .55));
            double lastLabel = Double.POSITIVE_INFINITY;
            for (int k = 2; k <= 6; k++) {
                double x = q(SX(b.xOf(b.today + remain / (double) k)));
                strokeOf(alpha(th.ink, .2), 1);
                dash(0, 2, 4);
                path.reset();
                path.moveTo(f(x), f(SY(0)));
                path.lineTo(f(x), f(SY(b.bottom)));
                c.drawPath(path, stroke);
                if (z > .3 && lastLabel - x > 30) { txt(c, "T/" + k, x + 3, SY(b.bottom) - 5); lastLabel = x; }
            }
        }
        strokeOf(th.accent, 1.25);
        line(c, q(tx0), yTop, q(tx0), yBot);
    }

    /* ---------- wires ---------- */
    private double[][] elbowPts(double x0, double y0, double x1, double y1) {
        if (Math.abs(y1 - y0) < 1) return new double[][]{{x0, y0}, {x1, y1}};
        double st = 12 * z;
        if (x1 - x0 >= st * 2) { double m = (x0 + x1) / 2; return new double[][]{{x0, y0}, {m, y0}, {m, y1}, {x1, y1}}; }
        double ym = (y0 + y1) / 2;
        return new double[][]{{x0, y0}, {x0 + st, y0}, {x0 + st, ym}, {x1 - st, ym}, {x1 - st, y1}, {x1, y1}};
    }

    private boolean offScreen(double[][] pts) {
        double a = 1e18, e = -1e18, g = 1e18, h = -1e18;
        for (double[] p : pts) { a = Math.min(a, p[0]); e = Math.max(e, p[0]); g = Math.min(g, p[1]); h = Math.max(h, p[1]); }
        return e < -20 || a > VW + 20 || h < -20 || g > VH + 20;
    }

    private double[] pointAt(double[][] pts, double[] lens, double d) {
        for (int i = 1; i < pts.length; i++) {
            if (d <= lens[i]) {
                double l = lens[i] - lens[i - 1], u = l > 0 ? (d - lens[i - 1]) / l : 0;
                return new double[]{pts[i - 1][0] + (pts[i][0] - pts[i - 1][0]) * u, pts[i - 1][1] + (pts[i][1] - pts[i - 1][1]) * u};
            }
        }
        return pts[pts.length - 1];
    }

    private void packets(Canvas c, double[][] pts, int col, double al, double upto) {
        if (b.reduce || pts.length < 2 || offScreen(pts)) return;
        double[] lens = new double[pts.length];
        for (int i = 1; i < pts.length; i++) lens[i] = lens[i - 1] + Math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]);
        double len = lens[pts.length - 1];
        if (len < 20) return;
        double gap = Math.max(64, 150 * z), off = (T * .075 * z) % gap, sz = Math.max(2.4, 3.6 * z), step = Math.max(4, 7 * z);
        double end = len * upto, fadeLen = Math.max(20, end * .45);
        for (double d = off; d < end; d += gap) {
            double g = upto < 1 ? clamp((end - d) / fadeLen, 0, 1) : 1;
            for (int i = 4; i >= 1; i--) {
                double e = d - i * step;
                if (e < 0) continue;
                double[] p = pointAt(pts, lens, e);
                double m = sz * (1 - i * .14);
                fr(c, p[0] - m / 2, p[1] - m / 2, m, m, alpha(col, g * al * (.6 - i * .12)));
            }
            double[] p = pointAt(pts, lens, d);
            fr(c, p[0] - sz, p[1] - sz, sz * 2, sz * 2, alpha(th.bg, g));
            strokeOf(alpha(col, g * al), 1.2);
            c.drawRect(f(p[0] - sz), f(p[1] - sz), f(p[0] + sz), f(p[1] + sz), stroke);
            fr(c, p[0] - sz * .4, p[1] - sz * .4, sz * .8, sz * .8, alpha(col, g * al));
        }
    }

    private void strokePoly(Canvas c, double[][] pts) {
        path.reset();
        poly(path, pts);
        c.drawPath(path, stroke);
    }

    private void drawWires(Canvas c) {
        String hover = b.focusId();
        boolean hN = hover != null;
        double sy = b.spine, ssy = SY(sy);
        Map<String, Board.Geo> G = b.G;
        Board.Lane main = b.mainBox;
        // ribs: every branch node leans into the spine at 60 degrees, as on a fishbone chart
        Path solid = new Path(), dashed = new Path(), marks = new Path();
        for (Board.Lane l : b.laneBoxes) {
            if (l == main) continue;
            int col = Theme.hex(s(l.lane, "color"));
            solid.reset();
            dashed.reset();
            marks.reset();
            for (Map<String, Object> n : l.items) {
                Board.Geo g = G.get(s(n, "id"));
                boolean above = g.ay < sy;
                double sx0 = g.ax + Board.W / 2, sy0 = above ? g.ay + g.h : g.ay, ex = sx0 + Math.abs(sy - sy0) * .577;
                double a = SX(sx0), bb = SY(sy0), e = SX(ex);
                if (Math.max(a, e) < -20 || Math.min(a, e) > VW + 20) continue;
                boolean done = "done".equals(n.get("status"));
                if (g.id.equals(hover)) {
                    strokeOf(col, 1.6);
                    if (!done) dash(0, 3, 4);
                    path.reset();
                    path.moveTo(f(a), f(bb));
                    path.lineTo(f(e), f(ssy));
                    c.drawPath(path, stroke);
                } else {
                    Path t = done ? solid : dashed;
                    t.moveTo(f(a), f(bb));
                    t.lineTo(f(e), f(ssy));
                }
                marks.moveTo(f(e), f(ssy - 3.5));
                marks.lineTo(f(e + 3.5), f(ssy));
                marks.lineTo(f(e), f(ssy + 3.5));
                marks.lineTo(f(e - 3.5), f(ssy));
                marks.close();
            }
            strokeOf(alpha(col, hN ? .08 : .2), 1);
            c.drawPath(solid, stroke);
            dash(0, 3, 4);
            c.drawPath(dashed, stroke);
            c.drawPath(marks, fillOf(alpha(col, .4)));
        }
        // spine
        Board.Geo root = G.get("root");
        double xS = root.ax + Board.RW, xE = b.headX - Board.R_HEAD * .85;
        strokeOf(alpha(th.ink, .72), 1);
        line(c, SX(xS), SY(sy) - 2.5, SX(xE), SY(sy) - 2.5);
        line(c, SX(xS), SY(sy) + 2.5, SX(xE), SY(sy) + 2.5);
        int mc = main != null ? Theme.hex(s(main.lane, "color")) : th.accent;
        double xNow = clamp(b.xOf(b.today), xS, xE);
        strokeOf(mc, 3);
        line(c, SX(xS), SY(sy), SX(xNow), SY(sy));
        strokeOf(alpha(th.ink, .8), 1.2);
        path.reset();
        path.moveTo(f(SX(xE) - 9), f(SY(sy) - 7));
        path.lineTo(f(SX(xE)), f(SY(sy)));
        path.lineTo(f(SX(xE) - 9), f(SY(sy) + 7));
        c.drawPath(path, stroke);
        // each branch starts from its anchor: the project node or any node on another lane
        int mi = b.laneBoxes.indexOf(main);
        for (int i = 0; i < b.laneBoxes.size(); i++) {
            Board.Lane l = b.laneBoxes.get(i);
            if (l == main) continue;
            String aid = b.anchorId(l.lane);
            Board.Geo g = G.get(aid);
            if (g == null) continue;
            Map<String, Object> an = "root".equals(aid) ? null : g.n;
            int ai = mi;
            if (an != null) for (int k = 0; k < b.laneBoxes.size(); k++) if (b.laneBoxes.get(k).id.equals(s(an, "lane"))) ai = k;
            boolean up = i < ai;
            double x0 = g.ax + Board.aw(aid) / 2, y0 = up ? g.ay : g.ay + g.h;
            Map<String, Object> first = l.items.isEmpty() ? null : l.items.get(0);
            Board.Geo fg = first == null ? null : G.get(s(first, "id"));
            double x1 = fg != null ? fg.ax : l.tx, y1 = fg != null ? fg.ay + Board.H / 2 : l.ty + 20;
            boolean lit = aid.equals(hover) || (fg != null && fg.id.equals(hover));
            double sx0 = SX(x0), sy0 = SY(y0), sx1 = SX(x1), sy1 = SY(y1);
            double[][] route;
            if (sx1 - sx0 >= 18 * z) route = new double[][]{{sx0, sy0}, {sx0, sy1}, {sx1, sy1}};
            else {
                double yc = SY(up ? l.top + l.h : l.top), xc = sx1 - 18 * z;
                route = new double[][]{{sx0, sy0}, {sx0, yc}, {xc, yc}, {xc, sy1}, {sx1, sy1}};
            }
            if (offScreen(route)) continue;
            int col = Theme.hex(s(l.lane, "color"));
            strokeOf(alpha(col, hN && !lit ? .35 : 1), lit ? 2.8 : 2);
            strokePoly(c, route);
            if (an == null || "done".equals(an.get("status"))) packets(c, route, col, hN && !lit ? .35 : 1, first != null && "done".equals(first.get("status")) ? 1 : .5);
            c.drawCircle(f(sx0), f(sy0), 3.5f, fillOf(col));
        }
        // project node into the first main-line node
        if (main != null && !main.items.isEmpty()) {
            Map<String, Object> fn = main.items.get(0);
            Board.Geo fg = G.get(s(fn, "id"));
            double[][] pts = elbowPts(SX(root.ax + Board.RW), SY(root.ay + Board.H / 2), SX(fg.ax), SY(fg.ay + Board.H / 2));
            strokeOf(mc, 2);
            strokePoly(c, pts);
            packets(c, pts, mc, 1, "done".equals(fn.get("status")) ? 1 : .5);
        }
        // wires in each lane
        for (Board.Lane l : b.laneBoxes) {
            int col = Theme.hex(s(l.lane, "color"));
            for (int i = 0; i + 1 < l.items.size(); i++) {
                Map<String, Object> a = l.items.get(i), e = l.items.get(i + 1);
                Board.Geo A = G.get(s(a, "id")), B = G.get(s(e, "id"));
                boolean lit = A.id.equals(hover) || B.id.equals(hover);
                double[][] pts = elbowPts(SX(A.ax + Board.W), SY(A.ay + Board.H / 2), SX(B.ax), SY(B.ay + Board.H / 2));
                if (offScreen(pts)) continue;
                double al = hN && !lit ? .3 : 1;
                boolean shut = b.locked.contains(B.id);
                boolean ad = "done".equals(a.get("status")) && !b.locked.contains(A.id), bd = "done".equals(e.get("status")) && !shut;
                strokeOf(alpha(col, shut ? al * .55 : al), lit ? 2.4 : (ad && bd ? 1.8 : 1.2));
                if (!(ad && bd)) {
                    boolean doing = "doing".equals(a.get("status")) || "doing".equals(e.get("status"));
                    dash(doing && !b.reduce ? (T * .025) % 11 * -1 + 11 : 0, 6, 5);
                }
                strokePoly(c, pts);
                if (ad && !shut) packets(c, pts, col, hN && !lit ? .35 : 1, bd ? 1 : .5);
            }
        }
        // hand-drawn connections pulled from a node's right port
        for (Map<String, Object> n : nodes(b.state)) {
            List<Object> ls = Core.links(n);
            if (ls.isEmpty()) continue;
            Board.Geo A = G.get(s(n, "id"));
            Map<String, Object> lane = Core.laneOf(b.state, s(n, "lane"));
            if (A == null || lane == null) continue;
            int col = Theme.hex(s(lane, "color"));
            for (Object o : ls) {
                String id = String.valueOf(o);
                Board.Geo B = G.get(id);
                if (B == null || B.n == null) continue;
                boolean lit = A.id.equals(hover) || id.equals(hover);
                double[][] pts = elbowPts(SX(A.ax + Board.W), SY(A.ay + Board.H / 2), SX(B.ax), SY(B.ay + Board.H / 2));
                if (offScreen(pts)) continue;
                strokeOf(alpha(col, hN && !lit ? .3 : 1), lit ? 2.4 : 1.6);
                strokePoly(c, pts);
                if ("done".equals(n.get("status"))) packets(c, pts, col, hN && !lit ? .35 : 1, "done".equals(B.n.get("status")) ? 1 : .5);
            }
        }
        // the spine carries everything into the head
        List<Map<String, Object>> mItems = main != null ? main.items : new ArrayList<Map<String, Object>>();
        int firstOpen = -1;
        for (int i = 0; i < mItems.size(); i++) if (!"done".equals(mItems.get(i).get("status"))) { firstOpen = i; break; }
        double stopX = xE;
        if (firstOpen >= 0) {
            double prevEnd = firstOpen > 0 ? G.get(s(mItems.get(firstOpen - 1), "id")).ax + Board.W : root.ax + Board.RW;
            stopX = (prevEnd + G.get(s(mItems.get(firstOpen), "id")).ax) / 2;
        }
        double upto = firstOpen >= 0 ? clamp((stopX - (root.ax + Board.RW)) / Math.max(1, xE - (root.ax + Board.RW)), 0, 1) : 1;
        packets(c, new double[][]{{SX(root.ax + Board.RW), SY(sy)}, {SX(xE), SY(sy)}}, th.ink, .9, upto);
    }

    /* ---------- fish head: lit rows of the dot matrix = overall progress ---------- */
    private void drawHead(Canvas c) {
        double hx = SX(b.headX), hy = SY(b.headY), r = Board.R_HEAD * z;
        if (hx < -r * 2.2 || hx > VW + r * 2.2 || hy < -r * 2.2 || hy > VH + r * 2.2) return;
        Board.Lane main = b.mainBox;
        int mc = main != null ? Theme.hex(s(main.lane, "color")) : th.accent;
        double prog = b.total / 100;
        boolean won = prog >= 1, rd = b.reduce;
        double spin = rd ? 0 : T * (won ? .00016 : .00007);
        float[][] dashes = {null, {2, 7}, {14, 6, 2, 6}, {3, 3}};
        for (int k = 0; k < 4; k++) {
            strokeOf(alpha(th.ink, k > 0 ? .34 : .2), 1);
            if (dashes[k] != null) dash(rd ? 0 : (k % 2 == 1 ? -1 : 1) * T * .0035 * (k + 1), dashes[k]);
            path.reset();
            path.addCircle(f(hx), f(hy), f(r * 1.62 * Math.pow(.8, k)), Path.Direction.CW);
            c.drawPath(path, stroke);
        }
        if (!rd) for (int i = 0; i < 3; i++) {
            double qq = ((T / 5200) + i / 3.0) % 1;
            strokeOf(alpha(mc, (1 - qq) * .75), 1.2);
            c.drawCircle(f(hx), f(hy), f(r * (.95 + qq * .95)), stroke);
        }
        for (int k = 0; k < 4; k++) {
            double R = r * 1.62 * Math.pow(.8, k);
            int dir = k % 2 == 1 ? -1 : 1;
            double a0 = rd ? k * 1.4 : dir * T * .00016 * (1 + k * .45) + k * 1.4, len = .5 + .28 * k;
            strokeOf(mc, k == 0 ? 2.4 : 1.6);
            rect.set(f(hx - R), f(hy - R), f(hx + R), f(hy + R));
            c.drawArc(rect, f(Math.toDegrees(a0)), f(Math.toDegrees(len)), false, stroke);
            double ox = hx + Math.cos(a0 + len) * R, oy = hy + Math.sin(a0 + len) * R, sq = Math.max(3, 5 * z);
            fr(c, ox - sq / 2, oy - sq / 2, sq, sq, mc);
        }
        for (int t = 0; t < 2; t++) {
            boolean lng = t == 1;
            strokeOf(alpha(th.ink, lng ? .4 : .22), 1);
            path.reset();
            for (int a = 0; a < 36; a++) {
                if ((a % 3 == 0) != lng) continue;
                double an = a / 36.0 * Math.PI * 2 + spin, r0 = r * 1.62, r1 = r0 + (lng ? 10 : 5), co = Math.cos(an), si = Math.sin(an);
                path.moveTo(f(hx + co * r0), f(hy + si * r0));
                path.lineTo(f(hx + co * r1), f(hy + si * r1));
            }
            c.drawPath(path, stroke);
        }
        double bw = r * 1.9, bh = r * 2.5, bx = hx - bw / 2, by = hy - bh / 2;
        fr(c, bx, by, bw, bh, alpha(th.bg, .82));
        int RN = 24, CN = 18;
        double pad = r * .14, gx = (bw - pad * 2) / (CN - 1), gy = (bh - pad * 2) / (RN - 1);
        int litRows = (int) Math.round(prog * RN);
        for (int i = 0; i < RN; i++) {
            boolean lit = RN - i <= litRows;
            double base = Math.max(.7, (1.1 + 1.5 * (1.0 / (RN - i))) * Math.max(.5, z * 1.5));
            int col = lit ? (won && !rd ? alpha(mc, .45 + .55 * (.5 + .5 * Math.sin(T * .004 - i * .55))) : mc) : alpha(th.ink, .3);
            fillOf(col);
            for (int j = 0; j < CN; j++) {
                float dx = f(bx + pad + j * gx - base / 2), dy = f(by + pad + i * gy - base / 2);
                c.drawRect(dx, dy, dx + f(base), dy + f(base), fill);
            }
        }
        double scanY = by + bh - (bh * prog);
        strokeOf(mc, 1);
        line(c, bx - 14, q(scanY), bx + bw + 14, q(scanY));
        strokeOf(mc, 1.5);
        rectLine(c, bx, by, bw, bh);
        strokeOf(alpha(th.ink, .35), 1);
        rectLine(c, bx - 8, by - 8, bw + 16, bh + 16);
        font(F.disp, Math.max(20, 54 * z), mc);
        txt(c, Math.round(b.total) + "%", bx, by - Math.max(16, 18 * z));
        if (z > .28) {
            List<Map<String, Object>> it = main != null ? main.items : null;
            Map<String, Object> end = it != null && !it.isEmpty() ? it.get(it.size() - 1) : null;
            font(F.monoMed, Math.max(10, 12 * z), th.ink);
            txt(c, won ? "全部亮起 · 專案完成 · 點此重播" : "已亮 " + litRows + "/" + RN + " 行 · 終點 " + (end != null ? s(end, "title") : "未設定"), bx, by + bh + Math.max(20, 26 * z));
        }
    }

    /* ---------- bands above and below the lanes ---------- */
    private void curveBand(Canvas c) {
        double y0 = SY(Board.bandTop()), h = Board.BAND_H * z, x0 = SX(b.minX - 40);
        int d0 = b.d0, d1 = Math.max(b.d1, d0 + 1);
        double x1 = SX(b.xOf(d1) + Board.W);
        if (y0 > VH || y0 + h < 0) return;
        for (double v : new double[]{0, .5, 1}) {
            double yy = y0 + h - v * h;
            strokeOf(alpha(th.ink, v == .5 ? .14 : .28), 1);
            line(c, x0, yy, x1, yy);
            if (z > .3) {
                font(F.mono, 10, alpha(th.ink, .6));
                txt(c, Math.round(v * 100) + "%", x0 + 3, yy - 3);
            }
        }
        double span = d1 - d0, t0 = d0 + span / 2, k = 9 / span;
        strokeOf(alpha(th.ink, .8), 1.1);
        dash(0, 5, 4);
        path.reset();
        for (int i = 0; i <= 80; i++) {
            double d = d0 + span * i / 80, x = SX(b.xOf(d)), v = 1 / (1 + Math.exp(-k * (d - t0))), yy = y0 + h - v * h;
            if (i == 0) path.moveTo(f(x), f(yy)); else path.lineTo(f(x), f(yy));
        }
        c.drawPath(path, stroke);
        // actual
        List<Map<String, Object>> cn = new ArrayList<>();
        for (Map<String, Object> n : nodes(b.state)) if (Core.counted(n)) cn.add(n);
        int N = Math.max(1, cn.size());
        cn.sort((a, e) -> dn(s(a, "date")) - dn(s(e, "date")));
        int col = b.mainBox != null ? Theme.hex(s(b.mainBox.lane, "color")) : th.accent;
        double cum = 0;
        strokeOf(col, 2);
        path.reset();
        path.moveTo(f(SX(b.xOf(d0))), f(y0 + h));
        for (Map<String, Object> n : cn) {
            int d = dn(s(n, "date"));
            if (d > b.today) break;
            double x = SX(b.xOf(d));
            path.lineTo(f(x), f(y0 + h - (cum / 100 / N) * h));
            cum += Core.n(n, "pct");
            path.lineTo(f(x), f(y0 + h - (cum / 100 / N) * h));
        }
        double xT = SX(b.xOf(clamp(b.today, d0, d1))), val = b.total / 100;
        path.lineTo(f(xT), f(y0 + h - (cum / 100 / N) * h));
        path.lineTo(f(xT), f(y0 + h - val * h));
        c.drawPath(path, stroke);
        fr(c, xT - 3, y0 + h - val * h - 3, 6, 6, col);
        font(F.monoMed, Math.max(11, 12 * z), col);
        txt(c, "實際 " + Math.round(b.total) + "%", xT + 8, y0 + h - val * h + 4);
        if (z > .26) {
            font(F.mono, Math.max(10, 11.5 * z), alpha(th.ink, .82));
            txt(c, "P(t) = 1 / (1 + e^(−k(t − t0)))  理想曲線，k = 9 / 工期", x0 + 3, y0 - Math.max(18, 20 * z));
        }
    }

    private void waveBand(Canvas c) {
        double y0 = SY(b.fourTop()), h = Board.FOUR_H * z;
        if (y0 > VH || y0 + h < 0 || b.envV == null) return;
        Board.Geo root = b.G.get("root");
        double sx0 = SX(root.x - 40), sx1 = SX(b.headX + Board.R_HEAD + 60), xa = Math.max(-2, sx0), xb = Math.min(VW + 2, sx1);
        if (xb <= xa) return;
        double mid = y0 + h / 2, amp = h * .47, ph = b.reduce ? 0 : T * .0009, twd = 110 * z;
        int mc = b.mainBox != null ? Theme.hex(s(b.mainBox.lane, "color")) : th.accent;
        strokeOf(alpha(th.ink, .22), 1);
        line(c, xa, q(mid), xb, q(mid));
        double[][] LINES = {{1, .34}, {.8, .3}, {.62, .26}, {.44, .22}, {.27, .18}};
        double step = Math.max(2, (xb - xa) / 600);
        int n = (int) Math.ceil((xb - xa) / step) + 1;
        double[] pp = new double[n], dd = new double[n];
        for (int i = 0; i < n; i++) {
            double x = xa + i * step, d = b.dayAt0((x - camX) / z), u = clamp(Math.min(x - sx0, sx1 - x) / twd, 0, 1);
            int ix = (int) clamp(Math.round((d - b.envS0) * 2), 0, b.envV.length - 1);
            pp[i] = b.envV[ix] * u * u * (3 - 2 * u) * amp;
            dd[i] = d / Board.PERIOD * 6.283 - ph;
        }
        for (int li = LINES.length - 1; li >= 0; li--) {
            double kk = LINES[li][0], al = LINES[li][1], off = li * .34;
            boolean lead = li == 0;
            strokeOf(lead ? mc : alpha(th.ink, al + .08), lead ? 1.3 : .9);
            path.reset();
            for (int i = 0; i < n; i++) {
                double y = mid - pp[i] * kk * Math.sin(dd[i] + off);
                if (i == 0) path.moveTo(f(xa), f(y)); else path.lineTo(f(xa + i * step), f(y));
            }
            c.drawPath(path, stroke);
        }
        if (z > .26) {
            double fs = Math.max(10, 11.5 * z);
            font(F.mono, fs, alpha(th.ink, .82));
            txt(c, "y(t) = A(t) sin(ωt + φ)    A(t) = 1 − exp(−k Σ e^(−(t − ti)² / 2σ²))", Math.max(xa, b.leftPad()) + 3, y0 + h + fs + 5);
        }
    }

    /* ---------- ruler ---------- */
    private void drawRuler(Canvas c) {
        double h = 30, y = b.rulerY;
        fr(c, 0, y, VW, h, alpha(th.bg, .94));
        strokeOf(alpha(th.ink, .85), 1);
        line(c, 0, y + .5, VW, y + .5);
        line(c, 0, y + h - .5, VW, y + h - .5);
        double pd = b.ppd() * z;
        int a = b.dayAt(-camX / z) - 1, e = b.dayAt((VW - camX) / z) + 1;
        strokeOf(alpha(th.ink, .5), 1);
        path.reset();
        if (pd >= 5) {
            for (int d = a; d <= e; d++) {
                double x = q(SX(b.xOf(d)));
                boolean wk = (d - b.today) % 7 == 0;
                path.moveTo(f(x), f(y + h));
                path.lineTo(f(x), f(y + h - (wk ? 8 : 3)));
            }
        } else if (pd >= 1) {
            for (int d = a; d <= e; d++) {
                if ((d - b.today) % 7 != 0) continue;
                double x = q(SX(b.xOf(d)));
                path.moveTo(f(x), f(y + h));
                path.lineTo(f(x), f(y + h - 5));
            }
        }
        c.drawPath(path, stroke);
        int every = pd < .7 ? 3 : pd < 1.6 ? 2 : 1;
        font(F.monoMed, 11, th.ink);
        strokeOf(th.ink, 1);
        for (int[] m : monthsIn(a - 31, e)) {
            if (m[2] % every != 0) continue;
            double x = q(SX(b.xOf(m[0])));
            line(c, x, y + h, x, y + 3);
            txt(c, m[1] + "." + pad2(m[2] + 1), x + 4, y + 14);
        }
        double tx0 = SX(b.xOf(b.today));
        if (tx0 > -40 && tx0 < VW + 40) {
            fr(c, tx0 - 19, y + h, 38, 16, th.accent);
            font(F.cjkBold, 10.5, th.onAccent);
            txt(c, "今日", tx0 - tw("今日") / 2, y + h + 12);
        }
        if (b.drag != null && b.drag.moved) {
            double x = SX(b.xOf(b.drag.date));
            strokeOf(th.accent, 1);
            dash(0, 3, 3);
            path.reset();
            path.moveTo(f(x), f(y + h));
            path.lineTo(f(x), f(VH));
            c.drawPath(path, stroke);
            String t = ds(b.drag.date);
            font(F.monoMed, 11, th.bg);
            double w = tw(t) + 12;
            fr(c, x, y + h, w, 18, th.ink);
            txt(c, t, x + 6, y + h + 13);
        }
    }

    /* ---------- cards ---------- */
    private void drawCards(Canvas c) {
        boolean far = b.far();
        // tails
        for (Board.Lane l : b.laneBoxes) {
            double sx = SX(l.tx), sy = SY(l.ty);
            if (sx > VW + 60 || sy > VH + 60 || sx + 40 * z < -60 || sy + 40 * z < -60) continue;
            int col = Theme.hex(s(l.lane, "color"));
            c.save();
            c.translate(f(camX), f(camY));
            c.scale(f(z), f(z));
            fr(c, l.tx, l.ty, 40, 40, th.bg);
            strokeOf(col, 1);
            dash(0, 3, 3);
            c.drawRect(f(l.tx + .5), f(l.ty + .5), f(l.tx + 39.5), f(l.ty + 39.5), stroke);
            font(F.mono, 24, col);
            txt(c, "+", l.tx + 20 - tw("+") / 2, base(l.ty, 40));
            c.restore();
        }
        String focus = b.focusId();
        java.util.Set<String> bright = focus == null ? null : b.connectedTo(focus);
        for (Board.Geo g : new ArrayList<>(b.G.values())) {
            double sx = SX(g.ax), sy = SY(g.ay);
            if (sx > VW + 60 || sy > VH + 60 || sx + Board.aw(g.id) * z < -60 || sy + g.h * z < -60) continue;
            c.save();
            c.translate(f(camX), f(camY));
            c.scale(f(z), f(z));
            if ("root".equals(g.id)) drawRoot(c, g, far);
            else drawNode(c, g, far);
            if (bright != null && !bright.contains(g.id)) fr(c, g.ax - 8, g.ay - 8, Board.aw(g.id) + 16, g.h + 16, alpha(0xFF000000, th.dark ? .5 : .2));
            c.restore();
        }
    }

    private void pull(Canvas c, double cx, double cy, int col, boolean far) {
        float r = far ? 7f : 4.5f;
        c.drawCircle(f(cx), f(cy), r, fillOf(th.bg));
        strokeOf(col, 1.5);
        c.drawCircle(f(cx), f(cy), r, stroke);
    }

    private void drawRoot(Canvas c, Board.Geo g, boolean far) {
        double x = g.ax, y = g.ay, w = Board.RW, h = Board.H;
        fr(c, x, y, w, h, th.ink);
        strokeOf(th.ink, 1.25);
        c.drawRect(f(x - 3.6), f(y - 3.6), f(x + w + 3.6), f(y + h + 3.6), stroke);
        Board.Lane m = b.mainBox;
        int col = m != null ? Theme.hex(s(m.lane, "color")) : th.accent;
        font(F.monoMed, 10.5, alpha(th.bg, .72));
        txt(c, "PROJECT", x + 13, base(y + 10, 10.5));
        font(F.cjkBold, far ? 23 : 17, th.bg);
        String[] ls = wrap(s(b.state, "name"), w - 26, 2);
        double lh = (far ? 23 : 17) * 1.22, ty = y + 26;
        for (String t : ls) { txt(c, t, x + 13, base(ty, lh)); ty += lh; }
        if (!far) {
            font(F.monoMed, 10.5, alpha(th.bg, .82));
            int cnt = 0;
            for (Map<String, Object> n : nodes(b.state)) if (Core.counted(n)) cnt++;
            String a = nodes(b.state).isEmpty() ? "尚無節點" : ds(b.d0).substring(2).replace('-', '.');
            String mid = cnt + " 節點", e = Math.round(b.total) + "%";
            double by = base(y + h - 9 - 10.5, 10.5);
            txt(c, a, x + 13, by);
            txt(c, mid, x + w / 2 - tw(mid) / 2, by);
            txt(c, e, x + w - 13 - tw(e), by);
        }
        pull(c, x + w / 2, y, col, far);
        pull(c, x + w / 2, y + h, col, far);
    }


    private static String stName(String k) {
        return "doing".equals(k) ? "進行中" : "done".equals(k) ? "已完成" : "blocked".equals(k) ? "卡關" : "未完成";
    }

    private void drawNode(Canvas c, Board.Geo g, boolean far) {
        Map<String, Object> n = g.n;
        if (n == null) return;
        Map<String, Object> lane = Core.laneOf(b.state, s(n, "lane"));
        if (lane == null) return;
        int col = Theme.hex(s(lane, "color"));
        String status = String.valueOf(n.get("status"));
        boolean gate = Core.isGate(n), passed = gate && Core.b(n, "manualDone"), waiting = b.locked.contains(g.id);
        boolean portal = Core.isPortal(n), sel = g.id.equals(b.selId), dragging = b.drag != null && b.drag.moved && g.id.equals(b.drag.id);
        double x = g.ax, y = g.ay, W = Board.W, h = g.h, pct = Core.n(n, "pct");
        Map<String, Object> tp = portal ? Core.project(b.store, s(n, "target")) : null;
        if (dragging) fr(c, x + 4, y + 8, W, h, alpha(th.ink, .16));
        // card and frame
        fr(c, x, y, W, h, th.card);
        boolean blocked = "blocked".equals(status) && !portal;
        if (blocked) {
            c.save();
            c.clipRect(f(x), f(y), f(x + W), f(y + h));
            strokeOf(Theme.mix(th.danger, .13, th.card), 1);
            path.reset();
            double span = W + h;
            for (double o = 0; o < span; o += 11.31) { path.moveTo(f(x + o), f(y)); path.lineTo(f(x + o - h), f(y + h)); }
            c.drawPath(path, stroke);
            c.restore();
        }
        int border = blocked ? th.danger : portal ? col : th.line;
        strokeOf(border, 1);
        if ("todo".equals(status) && !portal) dash(0, 3, 3);
        c.drawRect(f(x + .5), f(y + .5), f(x + W - .5), f(y + h - .5), stroke);
        if (portal) {
            double off = sel ? 6 : 3.5;
            strokeOf(col, sel ? 2 : 1);
            c.drawRect(f(x - off), f(y - off), f(x + W + off), f(y + h + off), stroke);
            if (!b.reduce) for (int i = 0; i < 3; i++) {
                double a = (T / 2100 + i / 3.0) % 1;
                strokeOf(alpha(col, .95 * (1 - a)), 1);
                c.drawRect(f(x - 40 * a), f(y - 34 * a), f(x + W + 40 * a), f(y + h + 34 * a), stroke);
            } else {
                strokeOf(alpha(col, .5), 1);
                c.drawRect(f(x - 8), f(y - 8), f(x + W + 8), f(y + h + 8), stroke);
            }
        } else {
            double ow = sel || "doing".equals(status) ? 2 : 1.25, off = (sel ? 7 : 3) + ow / 2;
            strokeOf(col, ow);
            c.drawRect(f(x - off), f(y - off), f(x + W + off), f(y + h + off), stroke);
        }
        // top row
        double px = x + 11, inner = W - 22, top = y + 9;
        double rowH = portal ? (far ? 27 : 19) : (far ? 14 : 10.5);
        double cellsW = far ? 36 : 29, cellsH = far ? 12 : 10, cellsK = cellsH / 10;
        double cy = top + rowH / 2 - cellsH / 2;
        double tfs = far ? 13 : 10.5;
        if (portal) {
            strokeOf(col, 1);
            c.drawRect(f(px + .5 * cellsK), f(cy + .5 * cellsK), f(px + 28.5 * cellsK), f(cy + 9.5 * cellsK), stroke);
            c.drawRect(f(px + 5 * cellsK), f(cy + 2.5 * cellsK), f(px + 24 * cellsK), f(cy + 7.5 * cellsK), stroke);
            fr(c, px + 11 * cellsK, cy + 4 * cellsK, 7 * cellsK, 2 * cellsK, col);
            font(F.monoMed, tfs, th.dim);
            txt(c, "傳送門", px + cellsW + 6, base(top, rowH));
            String lb = tp != null ? "傳送" : "失效";
            double wfs = far ? 15 : 11, wp = far ? 9 : 7, wv = far ? 6 : 4, tri = tp != null ? wfs * .75 : 0;
            font(F.cjkBold, wfs, th.bg);
            double bw = tw(lb) + wp * 2 + tri, bh = wfs + wv * 2, bx = x + W - 11 - bw, by = top + rowH / 2 - bh / 2;
            fr(c, bx, by, bw, bh, tp != null ? col : th.danger);
            txt(c, lb, bx + wp, base(by, bh));
            if (tp != null) {
                double tx = bx + wp + tw(lb) + wfs * .25, ty0 = by + bh / 2, ts = wfs * .28;
                path.reset();
                path.moveTo(f(tx), f(ty0 - ts));
                path.lineTo(f(tx + ts * 1.5), f(ty0));
                path.lineTo(f(tx), f(ty0 + ts));
                path.close();
                c.drawPath(path, fillOf(th.bg));
            }
        } else if (gate) {
            padlock(c, px, cy - 1 * cellsK, 12 * cellsK, passed, col);
            font(F.monoMed, tfs, th.dim);
            txt(c, "審核", px + 12 * cellsK + 6, base(top, rowH));
            String st = passed ? "已通過" : "待審核";
            font(F.cjkBold, far ? 14 : 10.5, passed ? col : th.danger);
            txt(c, st, x + W - 11 - tw(st), base(top, rowH));
        } else {
            int on = (int) Math.ceil(pct / 20);
            for (int i = 0; i < 5; i++) {
                double cx = px + (.5 + i * 6) * cellsK, cw = 4 * cellsK, ch = 9 * cellsK, ct = cy + .5 * cellsK;
                if (i < on) fr(c, cx, ct, cw, ch, col);
                strokeOf(col, .8);
                c.drawRect(f(cx), f(ct), f(cx + cw), f(ct + ch), stroke);
            }
            font(F.monoMed, tfs, th.dim);
            Integer sq = b.seq.get(g.id);
            txt(c, "N-" + pad2(sq == null ? 0 : sq), px + cellsW + 6, base(top, rowH));
            String st = (!items(n).isEmpty() && !"todo".equals(status) ? Math.round(pct) + "% " : "") + stName(status);
            int stc = "blocked".equals(status) ? th.danger : ("done".equals(status) || "doing".equals(status)) ? col : th.dim;
            font(F.cjkBold, far ? 14 : 10.5, stc);
            txt(c, st, x + W - 11 - tw(st), base(top, rowH));
        }
        double cursor = top + rowH + 5;
        // title
        double tfsT = far ? 21 : 14.5, tlh = far ? 21 * 1.18 : 14.5 * 1.25, tboxH = far ? 53 : 37;
        font(F.cjkBold, tfsT, "done".equals(status) ? th.dim : th.ink);
        double ty = cursor;
        for (String t : wrap(s(n, "title"), inner, 2)) { txt(c, t, px, base(ty, tlh)); ty += tlh; }
        cursor += tboxH + 5;
        // meta (hidden when zoomed out)
        if (!far) {
            font(F.monoMed, 10.5, th.dim);
            double mb = base(cursor, 10.5);
            String date = s(n, "date");
            if (gate) {
                String t = waiting ? "等待前面的審核" : passed ? "可以繼續" : "通過後才繼續";
                font(F.cjkMed, 10.5, th.dim);
                txt(c, t, px, mb);
            } else if (portal) {
                String a = "→ " + (tp != null ? s(tp, "name") : "已刪除的專案"), e = date.substring(5).replace('-', '.');
                String p = Core.b(n, "noCount") ? "" : Math.round(pct) + "%";
                double right = x + W - 11;
                if (!p.isEmpty()) { txt(c, p, right - tw(p), mb); right -= tw(p) + 8; }
                txt(c, e, right - tw(e), mb);
                right -= tw(e) + 8;
                font(F.cjkMed, 10.5, th.ink);
                txt(c, ell(a, right - px), px, mb);
            } else {
                String a = date.substring(2).replace('-', '.');
                String due, cnt;
                boolean late = false;
                if ("done".equals(status)) due = "完成";
                else {
                    int d = dn(date) - b.today;
                    if (waiting) due = "等待審核";
                    else if (d < 0) { due = "逾期 " + (-d) + " 天"; late = true; }
                    else if (d == 0) due = "今天到期";
                    else due = "D-" + d;
                }
                List<Map<String, Object>> its = items(n);
                if (!its.isEmpty()) {
                    int dc = 0;
                    for (Map<String, Object> it : its) if (Core.b(it, "done")) dc++;
                    cnt = dc + "/" + its.size();
                } else cnt = "無細項";
                txt(c, a, px, mb);
                double aw = tw(a), cw = tw(cnt);
                txt(c, cnt, x + W - 11 - cw, mb);
                font(F.monoMed, 10.5, late ? th.danger : th.dim);
                double dw = tw(due), free = inner - aw - cw - dw;
                txt(c, due, px + aw + Math.max(0, free / 2), mb);
            }
            cursor += 10.5 + 5;
        }
        // progress bar
        if (!(portal && Core.b(n, "noCount"))) {
            fr(c, px, cursor, inner, 3, th.line);
            fr(c, px, cursor, inner * clamp(pct, 0, 100) / 100, 3, col);
            cursor += 3 + 5;
        }
        // checklist
        if (!portal) {
            List<Map<String, Object>> its = items(n);
            double boxS = far ? 13 : 10, itFs = far ? 17 : 12, row = y + (far ? 94 : 90);
            if (gate) {
                double th0 = far ? 18 : 14, tw0 = th0 * 2, ty0 = row + 11 - th0 / 2;
                fr(c, px, ty0, tw0, th0, passed ? col : th.line);
                fr(c, passed ? px + tw0 - th0 + 2 : px + 2, ty0 + 2, th0 - 4, th0 - 4, passed ? th.card : th.dim);
                font(F.cjkMed, itFs, th.ink);
                txt(c, passed ? "通過" : "未通過", px + tw0 + 8, base(row, 22));
            } else if (its.isEmpty()) {
                boolean d = Core.b(n, "manualDone");
                if (d) fr(c, px, row + 11 - boxS / 2, boxS, boxS, col);
                strokeOf(col, 1);
                c.drawRect(f(px + .5), f(row + 11 - boxS / 2 + .5), f(px + boxS - .5), f(row + 11 + boxS / 2 - .5), stroke);
                font(F.cjkMed, itFs, d ? th.dim : th.ink);
                txt(c, ell(d ? "已完成" : "標記完成", inner - boxS - 7), px + boxS + (far ? 8 : 7), base(row, 22));
            } else {
                int cnt = Math.min(its.size(), Board.IT_MAX);
                for (int i = 0; i < cnt; i++) {
                    Map<String, Object> it = its.get(i);
                    boolean d = Core.b(it, "done");
                    double ry = row + i * Board.IT_H;
                    if (d) fr(c, px, ry + 11 - boxS / 2, boxS, boxS, col);
                    strokeOf(col, 1);
                    c.drawRect(f(px + .5), f(ry + 11 - boxS / 2 + .5), f(px + boxS - .5), f(ry + 11 + boxS / 2 - .5), stroke);
                    font(F.cjkMed, itFs, d ? th.dim : th.ink);
                    String t = ell(String.valueOf(it.get("text")), inner - boxS - 7);
                    double tx = px + boxS + (far ? 8 : 7), tb = base(ry, 22);
                    txt(c, t, tx, tb);
                    if (d) { strokeOf(th.dim, far ? 1.2 : .8); line(c, tx, tb - itFs * .33, tx + tw(t), tb - itFs * .33); }
                }
                if (its.size() > Board.IT_MAX) {
                    font(F.mono, far ? 14 : 11, th.dim);
                    txt(c, "還有 " + (its.size() - Board.IT_MAX) + " 項", px, base(row + cnt * Board.IT_H, 22));
                }
            }
        }
        // handles
        pull(c, x + W / 2, y, col, far);
        pull(c, x + W / 2, y + h, col, far);
        for (int i = 0; i < 2; i++) {
            double dx = i == 0 ? x : x + W, dy = y + 52;
            path.reset();
            path.moveTo(f(dx), f(dy - 5));
            path.lineTo(f(dx + 5), f(dy));
            path.lineTo(f(dx), f(dy + 5));
            path.lineTo(f(dx - 5), f(dy));
            path.close();
            c.drawPath(path, fillOf(th.bg));
            strokeOf(col, 1.5);
            c.drawPath(path, stroke);
        }
    }

    private void padlock(Canvas c, double x, double y, double s0, boolean open, int col) {
        double bw = s0 * .8, bh = s0 * .55, bx = x, by = y + s0 - bh;
        fr(c, bx, by, bw, bh, col);
        strokeOf(col, s0 * .13);
        double cx = bx + bw / 2, r = bw * .3, top = by - s0 * .22;
        path.reset();
        path.moveTo(f(cx - r), f(by));
        path.lineTo(f(cx - r), f(top));
        rect.set(f(cx - r), f(top - r), f(cx + r), f(top + r));
        path.arcTo(rect, 180, 180, false);
        path.lineTo(f(cx + r), f(open ? top : by));
        c.drawPath(path, stroke);
    }

    /* ---------- lane tags ---------- */
    private void drawTags(Canvas c) {
        boolean cm = b.compact();
        double minY = b.rulerY + 34;
        for (Board.Lane l : b.laneBoxes) {
            double top = SY(l.top), botm = SY(l.top + l.h);
            l.tagVisible = botm > b.rulerY + 30 && top < VH;
            if (!l.tagVisible) continue;
            int col = Theme.hex(s(l.lane, "color"));
            int done = 0, cn = 0;
            double sum = 0;
            for (Map<String, Object> n : l.items) if (Core.counted(n)) { cn++; sum += Core.n(n, "pct"); if ("done".equals(n.get("status"))) done++; }
            long pc = cn > 0 ? Math.round(sum / cn) : 0;
            String name = l.name;
            if (cm) {
                double h = 24;
                font(F.cjkBold, 12, th.ink);
                String nm = ell(name, 86);
                double nw = tw(nm);
                font(F.dispSemi, 13, col);
                String em = pc + "%";
                double ew = tw(em), w = 3 + 6 + nw + 6 + ew + 7;
                double y = Math.round(clamp(top + 1, minY, VH - h - 8));
                fr(c, 0, y, w, h, th.hud);
                strokeOf(th.line, 1);
                c.drawRect(.5f, f(y + .5), f(w - .5), f(y + h - .5), stroke);
                fr(c, 0, y, 3, h, col);
                font(F.cjkBold, 12, th.ink);
                txt(c, nm, 9, base(y, h));
                font(F.dispSemi, 13, col);
                txt(c, em, 9 + nw + 6, base(y, h));
                l.tagY = y; l.tagH = h; l.tagW = w;
                b.addBtn(0, y, w, h, "tag", l.id);
            } else {
                double w = 122, h = 66;
                double y = Math.round(clamp(SY(l.top + l.h / 2) - h / 2, minY, VH - h - 8));
                fr(c, 0, y, w, h, th.hud);
                strokeOf(th.line, 1);
                c.drawRect(.5f, f(y + .5), f(w - .5), f(y + h - .5), stroke);
                fr(c, 0, y, 3, h, col);
                font(F.mono, 10, th.dim);
                txt(c, ("main".equals(l.kind) ? "MAIN " : "BRANCH ") + done + "/" + cn, 12, base(y + 7, 10));
                font(F.cjkBold, 14, th.ink);
                txt(c, ell(name, w - 20), 12, base(y + 20, 17));
                font(F.dispSemi, 18, col);
                txt(c, pc + "%", 12, base(y + 40, 18));
                l.tagY = y; l.tagH = h; l.tagW = w;
                b.addBtn(0, y, w, h, "tag", l.id);
            }
        }
    }

    /* ---------- link being pulled ---------- */
    private void label(Canvas c, String t, double mx, double my) {
        font(F.cjkBold, 12, th.bg);
        double w = tw(t) + 14, lx = mx + 16, ly = my + 14;
        if (lx + w > VW - 8) lx = mx - w - 16;
        if (ly + 22 > VH) ly = my - 34;
        lx = Math.max(4, lx);
        // a finger hides what is under it, so the label goes above it
        ly = my - 56 < b.rulerY + 30 ? ly : my - 56;
        fr(c, lx, ly, w, 22, th.ink);
        txt(c, t, lx + 7, ly + 15.5);
    }

    private void drawLink(Canvas c) {
        Board.Link k = b.link;
        if (k == null || !k.moved) return;
        Board.Geo g = b.G.get(k.from);
        if (g == null) return;
        double mx = b.touchX, my = b.touchY, ph = b.reduce ? 0 : (T * .04) % 12 * -1 + 12;
        if (k.h) {
            double x0 = SX(g.ax + Board.W), y0 = SY(g.ay + Board.H / 2);
            Board.Geo hit = "node".equals(k.tKind) ? b.G.get(k.tVal) : null;
            double ex = hit != null ? SX(hit.ax) : mx, ey = hit != null ? SY(hit.ay + Board.H / 2) : my;
            strokeOf(th.accent, 2);
            dash(ph, 7, 5);
            strokePoly(c, elbowPts(x0, y0, ex, ey));
            if (hit != null) { strokeOf(th.accent, 2); rectLine(c, SX(hit.ax) - 5, SY(hit.ay) - 5, Board.W * z + 10, hit.h * z + 10); }
            String t;
            if (hit != null) t = "放開：連接到「" + s(hit.n, "title") + "」";
            else if ("spot".equals(k.tKind)) t = "放開：在「" + s(Core.laneOf(b.state, k.tVal), "name") + "」新增節點";
            else t = "拖到節點或時間線內";
            label(c, t, mx, my);
            return;
        }
        boolean down = my > SY(g.ay + Board.H / 2);
        double x0 = SX(g.ax + Board.aw(k.from) / 2), y0 = SY(down ? g.ay + g.h : g.ay);
        strokeOf(th.accent, 2);
        dash(ph, 7, 5);
        strokePoly(c, new double[][]{{x0, y0}, {x0, my}, {mx, my}});
        String t = "";
        if ("lane".equals(k.tKind)) t = "放開：「" + s(Core.laneOf(b.state, k.tVal), "name") + "」從這裡開始";
        else if ("add".equals(k.tKind)) t = "放開：拉出一條新支線";
        else if ("main".equals(k.tKind)) t = "拖到支線，或拖到時間線區外新增";
        else if ("own".equals(k.tKind)) t = "不能從同一條時間線拉出";
        if (!t.isEmpty()) label(c, t, mx, my);
    }

    /* ---------- warp and finale ---------- */
    private void drawWarp(Canvas c) {
        double[] w = b.warp;
        if (w == null) return;
        double a = (T - w[2]) / 560;
        if (a >= 1 || b.reduce) { b.warp = null; return; }
        for (int k = 0; k < 6; k++) {
            double qq = clamp(a * 1.5 - k * .09, 0, 1);
            if (qq <= 0 || qq >= 1) continue;
            double ww = 30 + Math.pow(qq, 1.6) * Math.max(VW, VH) * 1.3, h = ww * .72;
            strokeOf(alpha(th.accent, (1 - qq) * .9), k % 2 == 1 ? 1 : 1.8);
            rectLine(c, w[0] - ww / 2, w[1] - h / 2, ww, h);
        }
    }

    private void drawFin(Canvas c) {
        Board.Fin fin = b.fin;
        if (fin == null) return;
        double now = T, e = now - fin.t;
        int mc = b.mainBox != null ? Theme.hex(s(b.mainBox.lane, "color")) : th.accent;
        if (e < 1300) {
            Board.Geo g = b.G.get("root");
            double x0 = SX(g.ax + Board.RW), x1 = SX(b.headX), qq = clamp(e / 1100, 0, 1), bx = x0 + (x1 - x0) * qq * qq, y = SY(b.spine);
            strokeOf(mc, 5);
            line(c, x0, y, bx, y);
            strokeOf(th.accent, 1);
            rectLine(c, bx - 22, y - 28, 44, 56);
            for (int i = 0; i < fin.order.size(); i++) {
                Board.Geo n = b.G.get(fin.order.get(i));
                if (n == null) continue;
                double a = (e - i / (double) Math.max(1, fin.order.size()) * 1000) / 320;
                if (a < 0 || a > 1) continue;
                double pd = 4 + (1 - a) * 26, al = 1 - a;
                fr(c, SX(n.ax), SY(n.ay), Board.W * z, n.h * z, alpha(th.accent, al * .28));
                strokeOf(alpha(th.accent, al), 1.6);
                rectLine(c, SX(n.ax) - pd, SY(n.ay) - pd, Board.W * z + pd * 2, n.h * z + pd * 2);
            }
        }
        if (e > 950 && e < 2450) {
            double rate = clamp((e - 950) / 1000, .1, 1) * 14;
            for (int i = 0; i < rate; i++) {
                double w = rnd(24, 190);
                fin.burst.add(new double[]{rnd(0, VW), rnd(0, VH), w, w * rnd(1.05, 1.4), now, rnd(70, 210)});
            }
        }
        for (int i = fin.burst.size() - 1; i >= 0; i--) {
            double[] q0 = fin.burst.get(i);
            double a = (now - q0[4]) / q0[5];
            if (a >= 1) { fin.burst.remove(i); continue; }
            strokeOf(alpha(th.accent, .85 * (1 - a * a)), rng.nextDouble() < .2 ? 2 : 1);
            rectLine(c, q0[0] - q0[2] / 2, q0[1] - q0[3] / 2, q0[2], q0[3]);
        }
        if (e > 2150) {
            double a = clamp((e - 2150) / 360, 0, 1), k = a * a * a, hx0 = SX(b.headX), hy0 = SY(b.headY);
            double w = 40 + k * VW * 2.4, h = 56 + k * VH * 2.4;
            fr(c, hx0 - w / 2, hy0 - h / 2, w, h, th.accent);
            if (a >= 1) { b.fin = null; b.showFinale(); }
        }
    }

    private void drawFinaleBox(Canvas c) {
        fr(c, 0, 0, VW, VH, th.accent);
        int on = th.onAccent;
        boolean shake = !b.reduce && T - b.finaleAt < 2000;
        double[][] frames = {{.06, .12, .13, .30}, {.80, .09, .09, .20}, {.11, .62, .08, .18}, {.76, .56, .17, .34}, {.27, .05, .05, .11}, {.62, .84, .06, .12}};
        strokeOf(alpha(on, .55), 1);
        for (double[] q0 : frames) {
            double dx = shake ? rnd(-2, 2) : 0, dy = shake ? rnd(-2, 2) : 0;
            c.drawRect(f(q0[0] * VW + dx), f(q0[1] * VH + dy), f((q0[0] + q0[2]) * VW + dx), f((q0[1] + q0[3]) * VH + dy), stroke);
        }
        double numFs = Math.min(clamp(VW * .22, 96, 230), VH * .3), tagFs = clamp(VW * .032, 20, 34), nameFs = clamp(VW * .02, 15, 20);
        double padV = clamp(VW * .04, 18, 42), padH = clamp(VW * .06, 26, 70);
        int items = 0, cnt = 0;
        for (Map<String, Object> n : nodes(b.state)) { items += items(n).size(); if (Core.counted(n)) cnt++; }
        String stat = "節點 " + cnt + "    細項 " + items + "    時間線 " + Core.lanes(b.state).size() + "    歷時 " + (b.d1 - b.d0) + " 天";
        font(F.monoMed, 12.5, on);
        double statW = Math.min(tw(stat), VW - 92);
        font(F.disp, numFs, on);
        String nm = "100";
        double numW = tw(nm);
        font(F.disp, numFs * .34, on);
        numW += tw("%");
        double boxW = Math.min(VW - 32, Math.max(Math.max(numW, statW), 200) + padH * 2);
        font(F.cjkBold, nameFs, on);
        String[] nmLines = wrap(s(b.state, "name"), boxW - padH * 2, 2);
        double boxH = padV * 2 + tagFs * 1.2 + 10 + numFs * .82 + 10 + nmLines.length * nameFs * 1.35 + 10 + 18 + 18 + 38;
        double bx = (VW - boxW) / 2, by = (VH - boxH) / 2;
        double dx = shake ? rnd(-2, 2) : 0, dy = shake ? rnd(-2, 2) : 0;
        bx += dx;
        by += dy;
        strokeOf(on, 2);
        c.drawRect(f(bx), f(by), f(bx + boxW), f(by + boxH), stroke);
        double cx = bx + boxW / 2, y = by + padV;
        font(F.cjkBold, tagFs, on);
        text.setLetterSpacing(.3f);
        txt(c, "專案完成", cx - tw("專案完成") / 2, base(y, tagFs * 1.2));
        text.setLetterSpacing(0f);
        y += tagFs * 1.2 + 10;
        font(F.disp, numFs, on);
        double nw = tw(nm);
        double supFs = numFs * .34;
        font(F.disp, supFs, on);
        double sw = tw("%");
        font(F.disp, numFs, on);
        txt(c, nm, cx - (nw + sw) / 2, base(y, numFs * .82));
        font(F.disp, supFs, on);
        txt(c, "%", cx - (nw + sw) / 2 + nw + 2, y + supFs * .8);
        y += numFs * .82 + 10;
        font(F.cjkBold, nameFs, on);
        for (String t : nmLines) { txt(c, t, cx - tw(t) / 2, base(y, nameFs * 1.35)); y += nameFs * 1.35; }
        y += 10;
        font(F.monoMed, 12.5, on);
        String st2 = ell(stat, boxW - 16);
        txt(c, st2, cx - tw(st2) / 2, base(y, 18));
        y += 18 + 18;
        font(F.cjkBold, 14, on);
        double gw = tw("繼續") + 36;
        strokeOf(on, 1);
        c.drawRect(f(cx - gw / 2), f(y), f(cx + gw / 2), f(y + 38), stroke);
        txt(c, "繼續", cx - tw("繼續") / 2, base(y, 38));
        b.addBtn(cx - gw / 2, y, gw, 38, "finGo", null);
    }

    /* ---------- minimap, footer, empty hint ---------- */
    private void drawMini(Canvas c) {
        if (b.compact()) return;
        double mw = 220, mh = 96, mx = 16, my = VH - 12 - mh;
        fr(c, mx, my, mw, mh, th.hud);
        strokeOf(th.line, 1);
        c.drawRect(f(mx + .5), f(my + .5), f(mx + mw - .5), f(my + mh - .5), stroke);
        double[] bd = b.bounds();
        double sc = Math.min((mw - 12) / (bd[1] - bd[0]), (mh - 12) / (bd[3] - bd[2]));
        double ox = 6 - bd[0] * sc + ((mw - 12) - (bd[1] - bd[0]) * sc) / 2, oy = 6 - bd[2] * sc + ((mh - 12) - (bd[3] - bd[2]) * sc) / 2;
        b.miniS = sc; b.miniOx = ox; b.miniOy = oy; b.miniX = mx; b.miniY = my;
        c.save();
        c.clipRect(f(mx), f(my), f(mx + mw), f(my + mh));
        c.translate(f(mx), f(my));
        for (Board.Lane l : b.laneBoxes) {
            strokeOf(alpha(th.ink, .3), 1);
            line(c, 0, q(oy + l.top * sc), mw, q(oy + l.top * sc));
            int col = Theme.hex(s(l.lane, "color"));
            for (Map<String, Object> n : l.items) {
                Board.Geo g = b.G.get(s(n, "id"));
                fr(c, ox + g.x * sc, oy + g.y * sc, Math.max(4, Board.W * sc), Math.max(3, Board.H * sc * .7), "done".equals(n.get("status")) ? col : alpha(col, .45));
            }
        }
        Board.Geo r = b.G.get("root");
        fr(c, ox + r.x * sc, oy + r.y * sc, Math.max(5, Board.RW * sc), Math.max(3, Board.H * sc * .7), th.ink);
        strokeOf(th.accent, 1);
        double t = q(ox + b.xOf(b.today) * sc);
        line(c, t, 0, t, mh);
        double vx = (-camX / z) * sc + ox, vy = ((b.rulerY - camY) / z) * sc + oy, vw = (VW / z) * sc, vh = ((VH - b.rulerY) / z) * sc;
        strokeOf(th.ink, 1);
        rectLine(c, vx, vy, vw, vh);
        c.restore();
        b.addBtn(mx, my, mw, mh, "mini", null);
    }

    private void drawFoot(Canvas c) {
        double right = VW - 16, y = VH - 12;
        font(F.mono, 11, th.dim);
        String sync = b.syncText == null ? "" : b.syncText;
        if (!sync.isEmpty()) {
            double w = tw(sync);
            fr(c, right - w - 4, y - 15, w + 8, 16, alpha(th.bg, .72));
            txt(c, sync, right - w, y - 3);
            y -= 16;
        }
        font(F.disp, 15, th.ink);
        String zm = Math.round(z * 100) + "%";
        txt(c, zm, right - tw(zm), y - 3);
    }

    private void drawEmpty(Canvas c) {
        String t = "這個專案還沒有節點。按時間線旁的 ＋ 或上方「新增節點」，建立第一個節點。";
        font(F.cjk, 14, th.ink);
        double mw = Math.min(380, VW - 32), inner = mw - 32;
        String[] ls = wrap(t, inner, 4);
        double h = ls.length * 22.4 + 28, x = (VW - mw) / 2, y = VH * .44 - h / 2;
        fr(c, x, y, mw, h, th.hud);
        strokeOf(th.line, 1);
        c.drawRect(f(x + .5), f(y + .5), f(x + mw - .5), f(y + h - .5), stroke);
        double ty = y + 14;
        for (String s : ls) { txt(c, s, VW / 2 - tw(s) / 2, base(ty, 22.4)); ty += 22.4; }
    }

    /* ---------- top bar ---------- */
    private static final class Tool { String key, arg, label; double w; int kind; boolean on, off; }
    private final List<Tool> tools = new ArrayList<>();

    private void buildTools(double h, double fs, double padX, boolean wideText) {
        tools.clear();
        font(F.cjkMed, fs, th.ink);
        String den = s(b.state, "density");
        String[][] seg = {{"week", "週"}, {"month", "月"}, {"quarter", "季"}};
        for (String[] q0 : seg) {
            Tool t = new Tool();
            t.key = "density"; t.arg = q0[0]; t.label = q0[1]; t.kind = 1; t.on = q0[0].equals(den);
            t.w = tw(q0[1]) + padX * 2 + 2;
            tools.add(t);
        }
        Board.Hist hist = b.histFor(b.state);
        Tool u = new Tool();
        u.key = "undo"; u.label = "↶"; u.kind = 2; u.off = hist.undo.isEmpty(); u.w = h;
        tools.add(u);
        Tool r = new Tool();
        r.key = "redo"; r.label = "↷"; r.kind = 2; r.off = hist.redo.isEmpty(); r.w = h;
        tools.add(r);
        if (!b.warpStack.isEmpty()) {
            Map<String, Object> prev = Core.project(b.store, b.warpStack.get(b.warpStack.size() - 1));
            if (prev != null) {
                Tool t = new Tool();
                t.key = "back"; t.kind = 3;
                t.label = b.shortScreen() ? "← 返回" : ell("← 返回 " + s(prev, "name"), 170);
                t.w = tw(t.label) + padX * 2;
                tools.add(t);
            }
        }
        addTool("hub", wideText ? "全部專案" : "專案", padX);
        addTool("fit", "全覽", padX);
        addTool("today", "今日", padX);
        addTool("settings", wideText ? "主題與時間線" : "主題", padX);
        font(F.cjkBold, fs, th.ink);
        Tool add = new Tool();
        add.key = "add"; add.kind = 4; add.label = wideText ? "＋ 新增節點" : "＋ 新增";
        add.w = tw(add.label) + padX * 2;
        tools.add(add);
    }

    private void addTool(String key, String label, double padX) {
        Tool t = new Tool();
        t.key = key; t.label = label; t.w = tw(label) + padX * 2;
        tools.add(t);
    }

    private double toolsWidth(double gap) {
        double w = 0;
        Tool prev = null;
        for (Tool t : tools) {
            boolean glued = prev != null && (prev.kind == t.kind) && (t.kind == 1 || t.kind == 2);
            w += t.w + (prev == null || glued ? 0 : gap);
            prev = t;
        }
        return w;
    }


    private double measureHud() { return layoutHud(null); }

    private void drawHud(Canvas c) { layoutHud(c); }

    /** Lays the bar out and, when given a canvas, draws it. Returns its height. */
    private double layoutHud(Canvas c) {
        boolean shortS = b.shortScreen(), phone = VW <= 760;
        boolean wideText = !phone && !shortS;
        double h = shortS ? 30 : phone ? 34 : 36, fs = shortS ? 12 : phone ? 12.5 : 13, padX = shortS ? 8 : phone ? 9 : 12, gap = shortS ? 5 : phone ? 6 : 8;
        double L = 16, R = VW - 16, top = 10;
        buildTools(h, fs, padX, wideText);
        double tw0 = toolsWidth(gap);
        double pctFs = shortS ? 30 : phone ? 36 : 44, nameFs = shortS ? 14 : phone ? 16 : 18, statFs = shortS ? 10.5 : phone ? 10.5 : 11.5;
        // text of the brand block
        String name = s(b.state, "name");
        boolean won = isWonCached(b.state), sample = Core.b(b.state, "isSample");
        long pc = Math.round(b.total);
        font(F.disp, pctFs, th.accent);
        String pctS = String.valueOf(pc);
        double pctW = tw(pctS);
        font(F.disp, pctFs * .41, th.accent);
        double supW = tw("%") + 2;
        double brandW;
        boolean oneRow;
        if (shortS) { oneRow = true; brandW = Math.max(120, R - L - tw0 - 12); }
        else if (phone) { oneRow = false; brandW = R - L; }
        else { oneRow = 300 + 20 + tw0 <= R - L; brandW = oneRow ? R - L - tw0 - 20 : R - L; }
        double textX = L + pctW + supW + (shortS ? 8 : phone ? 10 : 14), textW = L + brandW - textX;
        // stats wrap inside the text column
        List<String[]> stats = statLines();
        font(F.mono, statFs, th.dim);
        double statLH = statFs * 1.35;
        List<List<String[]>> rows = new ArrayList<>();
        List<String[]> row = new ArrayList<>();
        double rw = 0;
        for (String[] st : stats) {
            double w = tw(st[0]);
            if (!row.isEmpty() && rw + 12 + w > textW) {
                if (shortS) break;
                rows.add(row);
                row = new ArrayList<>();
                rw = 0;
            }
            rw += (row.isEmpty() ? 0 : 12) + w;
            row.add(st);
        }
        if (!row.isEmpty()) rows.add(row);
        double nameH = nameFs * 1.2, textH = nameH + 3 + rows.size() * statLH, brandH = Math.max(pctFs * .9, textH);
        double brandBottom = top + brandH;
        double toolsY;
        boolean toolsOwn;
        if (oneRow) { toolsOwn = false; toolsY = top + Math.max(0, (brandH - h) / 2); }
        else { toolsOwn = true; toolsY = brandBottom + 8; }
        // flow of tools
        double x = oneRow ? R - tw0 : L, y = toolsY;
        Tool prev = null;
        double[] xs = new double[tools.size()], ys = new double[tools.size()];
        int idx = 0;
        for (Tool t : tools) {
            boolean glued = prev != null && prev.kind == t.kind && (t.kind == 1 || t.kind == 2);
            double nx = x + (prev == null || glued ? 0 : gap);
            int groupEnd = idx;
            double gw = t.w;
            // keep a glued group together when wrapping
            if (!glued) while (groupEnd + 1 < tools.size() && tools.get(groupEnd + 1).kind == t.kind && (t.kind == 1 || t.kind == 2)) { groupEnd++; gw += tools.get(groupEnd).w; }
            if (!glued && !oneRow && nx + gw > R && prev != null) { y += h + gap; nx = L; }
            if (t.kind == 4 && phone && !oneRow) nx = Math.max(nx, R - t.w);
            xs[idx] = nx;
            ys[idx] = y;
            x = nx + t.w;
            prev = t;
            idx++;
        }
        double bottom = Math.max(brandBottom, y + h) + (shortS ? 6 : 8);
        if (c == null) return bottom;
        // draw
        fr(c, 0, 0, VW, bottom, th.hud);
        double pb = top + brandH / 2;
        font(F.disp, pctFs, th.accent);
        double pctBase = base(pb - pctFs * .45, pctFs * .9);
        txt(c, pctS, L, pctBase);
        font(F.disp, pctFs * .41, th.accent);
        txt(c, "%", L + pctW + 2, pb - pctFs * .45 + pctFs * .41 * .9);
        // name + chips
        double ty = top + Math.max(0, (brandH - textH) / 2);
        double chipsW = 0;
        font(F.cjkBold, 10.5, th.accent);
        if (won) chipsW += tw("專案完成") + 18;
        if (sample) chipsW += tw("範例資料") + 18;
        font(F.cjkBold, nameFs, th.ink);
        String nm = ell(name, Math.max(40, textW - chipsW));
        txt(c, nm, textX, base(ty, nameH));
        double chipX = textX + tw(nm) + 8;
        double nameEnd = chipX;
        if (won) chipX = chip(c, "專案完成", chipX, ty + nameH / 2, true);
        if (sample) chipX = chip(c, "範例資料", chipX, ty + nameH / 2, false);
        b.addBtn(textX, ty, Math.max(nameEnd - textX, 60), nameH + 6, "pname", null);
        double sy = ty + nameH + 3;
        for (List<String[]> rr : rows) {
            double sx = textX;
            for (String[] st : rr) {
                font(st[1] != null ? F.monoMed : F.mono, statFs, st[1] != null ? th.danger : th.dim);
                String shown = shortS ? ell(st[0], textX + textW - sx) : st[0];
                txt(c, shown, sx, base(sy, statLH));
                sx += tw(shown) + 12;
            }
            sy += statLH;
        }
        // tools
        idx = 0;
        for (Tool t : tools) {
            drawTool(c, t, xs[idx], ys[idx], h, fs);
            idx++;
        }
        return bottom;
    }

    private double chip(Canvas c, String t, double x, double midY, boolean solid) {
        font(F.cjkBold, 10.5, solid ? th.onAccent : th.accent);
        double w = tw(t) + 10, h = 15, y = midY - h / 2;
        if (solid) fr(c, x, y, w, h, th.accent);
        strokeOf(th.accent, 1);
        c.drawRect(f(x + .5), f(y + .5), f(x + w - .5), f(y + h - .5), stroke);
        txt(c, t, x + 5, base(y, h));
        return x + w + 8;
    }

    private void drawTool(Canvas c, Tool t, double x, double y, double h, double fs) {
        boolean pressed = b.isPressed(t.key, t.arg);
        int bgc = th.card, fg = th.ink, bd = th.line;
        Typeface tf = F.cjkMed;
        switch (t.kind) {
            case 1: bgc = t.on ? th.ink : th.card; fg = t.on ? th.bg : th.dim; break;
            case 3: bgc = 0; fg = th.accent; bd = th.accent; tf = F.cjkBold; break;
            case 4: bgc = th.accent; fg = th.onAccent; bd = th.accent; tf = F.cjkBold; break;
            default: break;
        }
        if (t.off) fg = alpha(fg, .38);
        if (bgc != 0) fr(c, x, y, t.w, h, bgc);
        if (pressed) fr(c, x, y, t.w, h, alpha(t.kind == 4 ? th.bg : th.ink, .14));
        strokeOf(pressed ? th.ink : bd, 1);
        c.drawRect(f(x + .5), f(y + .5), f(x + t.w - .5), f(y + h - .5), stroke);
        if (t.kind == 2) {
            undoArrow(c, x + t.w / 2, y + h / 2, "redo".equals(t.key), fg);
        } else {
            font(tf, fs, fg);
            txt(c, t.label, x + t.w / 2 - tw(t.label) / 2, base(y, h));
        }
        if (!t.off) b.addBtn(x, y, t.w, h, t.key, t.arg);
    }

    private void undoArrow(Canvas c, double cx, double cy, boolean mirror, int col) {
        double d = mirror ? -1 : 1;
        strokeOf(col, 1.5);
        path.reset();
        path.moveTo(f(cx - 6 * d), f(cy - 2));
        path.lineTo(f(cx + 1 * d), f(cy - 2));
        path.cubicTo(f(cx + 7 * d), f(cy - 2), f(cx + 7 * d), f(cy + 6), f(cx + 1 * d), f(cy + 6));
        path.lineTo(f(cx - 2 * d), f(cy + 6));
        path.moveTo(f(cx - 3 * d), f(cy - 5.5));
        path.lineTo(f(cx - 6.5 * d), f(cy - 2));
        path.lineTo(f(cx - 3 * d), f(cy + 1.5));
        c.drawPath(path, stroke);
    }

    /** Text of the stats row: {text, marker when it is a warning}. */
    private List<String[]> statLines() {
        List<String[]> out = new ArrayList<>();
        int cnt = 0, done = 0, late = 0, blocked = 0;
        for (Map<String, Object> n : nodes(b.state)) {
            String st = String.valueOf(n.get("status"));
            if (Core.counted(n)) { cnt++; if ("done".equals(st)) done++; }
            if (!"done".equals(st) && !Core.isGate(n) && dn(s(n, "date")) < b.today) late++;
            if ("blocked".equals(st)) blocked++;
        }
        out.add(new String[]{"節點 " + done + "/" + cnt, null});
        List<Map<String, Object>> mi = b.mainBox != null ? b.mainBox.items : null;
        if (mi != null && !mi.isEmpty()) {
            Map<String, Object> end = mi.get(mi.size() - 1);
            int left = dn(s(end, "date")) - b.today;
            out.add(new String[]{"終點 " + s(end, "title") + " " + (left >= 0 ? "D-" + left : "已過 " + (-left) + " 天"), null});
        }
        if (late > 0) out.add(new String[]{"逾期 " + late, "1"});
        if (blocked > 0) out.add(new String[]{"卡關 " + blocked, "1"});
        String others = info == null ? "" : info.others(s(b.state, "id"));
        if (others != null && !others.isEmpty()) out.add(new String[]{"共同編輯 " + others, null});
        return out;
    }

    /* ---------- toast ---------- */
    private void drawToast(Canvas c) {
        if (b.toastMsg == null) return;
        if (System.currentTimeMillis() > b.toastUntil) { b.toastMsg = null; b.toastFn = null; return; }
        font(F.cjk, 13, th.bg);
        String act = b.toastAct;
        double max = VW - 32, actW = 0;
        if (act != null) { font(F.cjkBold, 13, th.bg); actW = tw(act) + 12; font(F.cjk, 13, th.bg); }
        String[] ls = wrap(b.toastMsg, max - 28 - actW, 3);
        double w = 0;
        for (String t : ls) w = Math.max(w, tw(t));
        w += 28 + actW;
        double h = ls.length * 19 + 20, x = (VW - w) / 2, y = VH - 20 - h;
        fr(c, x, y, w, h, th.ink);
        double ty = y + 10;
        for (String t : ls) { txt(c, t, x + 14, base(ty, 19)); ty += 19; }
        if (act != null) {
            font(F.cjkBold, 13, th.bg);
            double ax = x + w - 14 - tw(act), ab = base(y, h);
            txt(c, act, ax, ab);
            strokeOf(th.bg, 1);
            line(c, ax, ab + 2, ax + tw(act), ab + 2);
            b.addBtn(ax - 8, y, tw(act) + 22, h, "toastAct", null);
        }
    }

    /* ---------- login ---------- */
    private void drawLogin(Canvas c) {
        boolean phone = VW <= 760, shortS = b.shortScreen();
        double cardW = Math.min(372, VW - 32), padX = shortS ? 22 : 28, padT = shortS ? 16 : 30, padB = shortS ? 14 : 24, gap = shortS ? 8 : 14;
        double h1 = shortS ? 28 : phone ? 36 : 42, inner = cardW - padX * 2;
        String note = info != null ? info.loginNote() : "";
        font(F.cjk, 14, th.dim);
        String[] ps = wrap("把每個專案排成一條時間線，從節點到細項一眼看完。", inner, 3);
        font(F.cjk, 12.5, th.dim);
        String[] ns = note == null || note.isEmpty() ? new String[0] : wrap(note, inner, shortS ? 3 : 7);
        double cardH = padT + 11 + gap + h1 * 1.08 + gap + ps.length * 22.4 + gap + 6 + 46 + (ns.length > 0 ? gap + 13 + ns.length * 20 : 0) + padB;
        double x = phone ? (VW - cardW) / 2 : VW - cardW - Math.max(VW * .09, 16), y = Math.max(12, (VH - cardH) / 2);
        double[] rc = {x, y, x + cardW, y + cardH};
        drawTech(c, phone ? VH * .84 : VH * .5, phone ? VH * .1 : VH * .33, phone ? VW * .5 : VW * .3, phone ? VH * .14 : VH * .5, Math.min(VW, VH) * (phone ? .3 : .46), true, rc);
        fr(c, x, y, cardW, cardH, th.card);
        strokeOf(th.line, 1);
        c.drawRect(f(x + .5), f(y + .5), f(x + cardW - .5), f(y + cardH - .5), stroke);
        strokeOf(th.accent, 1.5);
        c.drawRect(f(x - 6.75), f(y - 6.75), f(x + cardW + 6.75), f(y + cardH + 6.75), stroke);
        double ty = y + padT;
        font(F.monoMed, 11, th.dim);
        text.setLetterSpacing(.14f);
        txt(c, "TRACE", x + padX, base(ty, 11));
        text.setLetterSpacing(0f);
        ty += 11 + gap;
        font(F.cjkBold, h1, th.ink);
        txt(c, "軌跡", x + padX, base(ty, h1 * 1.08));
        ty += h1 * 1.08 + gap;
        font(F.cjk, 14, th.dim);
        for (String t : ps) { txt(c, t, x + padX, base(ty, 22.4)); ty += 22.4; }
        ty += gap + 6;
        boolean busy = info != null && info.loginBusy();
        boolean pressed = b.isPressed("gBtn", null);
        int gbg = pressed ? 0xFFEDEDED : 0xFFFFFFFF;
        fr(c, x + padX, ty, inner, 46, gbg);
        strokeOf(th.ink, 1);
        c.drawRect(f(x + padX + .5), f(ty + .5), f(x + padX + inner - .5), f(ty + 45.5), stroke);
        font(F.cjkMed, 15, busy ? 0x801F1F1F : 0xFF1F1F1F);
        String gt = "使用 Google 帳戶登入";
        double gw = 19 + 10 + tw(gt), gx = x + padX + (inner - gw) / 2;
        drawG(c, gx, ty + 13.5, 19, busy ? .55 : 1);
        txt(c, gt, gx + 29, base(ty, 46));
        if (!busy) b.addBtn(x + padX, ty, inner, 46, "gBtn", null);
        ty += 46;
        if (ns.length > 0) {
            ty += gap;
            strokeOf(th.line, 1);
            line(c, x + padX, ty, x + padX + inner, ty);
            ty += 12;
            font(F.cjk, 12.5, th.dim);
            for (String t : ns) { txt(c, t, x + padX, base(ty, 20)); ty += 20; }
        }
    }

    private void drawG(Canvas c, double x, double y, double size, double al) {
        c.save();
        c.translate(f(x), f(y));
        c.scale(f(size / 48), f(size / 48));
        path.reset();
        Svg.into(path, "M24 9.5c3.5 0 6.7 1.2 9.2 3.6l6.9-6.9C35.9 2.4 30.5 0 24 0 14.6 0 6.5 5.4 2.6 13.2l8 6.2C12.5 13.7 17.8 9.5 24 9.5z");
        c.drawPath(path, fillOf(alpha(0xFFEA4335, al)));
        path.reset();
        Svg.into(path, "M46.9 24.5c0-1.6-.1-3.1-.4-4.5H24v9h12.9c-.6 3-2.3 5.5-4.8 7.2l7.7 6c4.5-4.2 7.1-10.3 7.1-17.7z");
        c.drawPath(path, fillOf(alpha(0xFF4285F4, al)));
        path.reset();
        Svg.into(path, "M10.6 28.6c-.5-1.4-.8-3-.8-4.6s.3-3.2.8-4.6l-8-6.2C1 16.4 0 20.1 0 24s1 7.6 2.6 10.8l8-6.2z");
        c.drawPath(path, fillOf(alpha(0xFFFBBC05, al)));
        path.reset();
        Svg.into(path, "M24 48c6.5 0 11.9-2.1 15.9-5.8l-7.7-6c-2.2 1.5-4.9 2.3-8.2 2.3-6.2 0-11.5-4.2-13.4-9.9l-8 6.2C6.5 42.6 14.6 48 24 48z");
        c.drawPath(path, fillOf(alpha(0xFF34A853, al)));
        c.restore();
    }

    /** drawTech(): the moving instrument drawing behind the login and overview screens. */
    private void drawTech(Canvas c, final double mid, final double amp, double cx0, double cy0, double R0, boolean formula, double[] r) {
        final double k = VW / 1440;
        boolean phone = VW <= 760;
        final double TT = b.reduce ? 0 : T;
        final double ph = TT * .0007, per = Math.max(170, 300 * k);
        fillOf(alpha(th.ink, .2));
        for (double x = 20; x < VW; x += 40) for (double y = 20; y < VH; y += 40) c.drawRect(f(x - .6), f(y - .6), f(x + .6), f(y + .6), fill);
        for (int n = 2; n <= 11; n++) {
            double x = Math.round(VW / n) + .5;
            strokeOf(alpha(th.ink, .11 + .16 / n * 2), 1);
            line(c, x, 0, x, VH);
            if (!phone && n <= 7) { font(F.mono, 10, alpha(th.ink, .55)); txt(c, "W/" + n, x + 4, VH - 46); }
        }
        strokeOf(alpha(th.ink, .2), 1);
        double hb = phone ? 90 : 190;
        for (int i = 0; i < 9; i++) {
            double g = i * i * 2.4;
            line(c, g, VH, g + hb * .577, VH - hb);
            line(c, VW - g, 0, VW - g - hb * .577, hb);
        }
        for (int i = 0; i < 5; i++) {
            double R = R0 * Math.pow(.76, i), rot = TT * .00008 * (i % 2 == 1 ? -1 : 1) * (i + 1);
            strokeOf(alpha(th.ink, .2), 1);
            c.drawCircle(f(cx0), f(cy0), f(R), stroke);
            int ticks = new int[]{72, 48, 36, 24, 12}[i];
            for (int t = 0; t < 2; t++) {
                boolean lng = t == 1;
                strokeOf(alpha(th.ink, lng ? .5 : .24), 1);
                path.reset();
                for (int a = 0; a < ticks; a++) {
                    if ((a % 6 == 0) != lng) continue;
                    double an = a / (double) ticks * 6.283 + rot, l = lng ? 9 : 4, co = Math.cos(an), si = Math.sin(an);
                    path.moveTo(f(cx0 + co * R), f(cy0 + si * R));
                    path.lineTo(f(cx0 + co * (R + l)), f(cy0 + si * (R + l)));
                }
                c.drawPath(path, stroke);
            }
            double an = rot * 2.4 + i * 1.3;
            fr(c, cx0 + Math.cos(an) * R - 2.5, cy0 + Math.sin(an) * R - 2.5, 5, 5, th.accent);
        }
        strokeOf(alpha(th.ink, .2), 1);
        line(c, cx0 - R0 * 1.1, cy0, cx0 + R0 * 1.1, cy0);
        line(c, cx0, cy0 - R0 * 1.1, cx0, cy0 + R0 * 1.1);
        strokeOf(alpha(th.ink, .3), 1);
        line(c, 0, Math.round(mid) + .5, VW, Math.round(mid) + .5);
        double[] qs = {1, .82, .66, .5, .36, .22, .1};
        for (int li = 0; li < qs.length; li++) {
            if (li == 0) strokeOf(th.accent, Math.max(1.2, 1.6 * k));
            else strokeOf(alpha(th.ink, .42 - li * .03), .9);
            path.reset();
            for (double x = 0; x <= VW; x += 2) {
                double y = techWave(x, qs[li], li, mid, amp, per, ph, TT);
                if (x == 0) path.moveTo(f(x), f(y)); else path.lineTo(f(x), f(y));
            }
            c.drawPath(path, stroke);
        }
        int locks = phone ? 3 : 6;
        for (int i = 0; i < locks; i++) {
            double x = (((i + .5) / locks * VW + TT * .012 * (i % 2 == 1 ? 1 : -1) + VW * 4) % VW + VW) % VW, y = techWave(x, 1, 0, mid, amp, per, ph, TT);
            double w = (i % 3 + 2) * 16, h = w * 1.26;
            if (r != null && x - w / 2 < r[2] + 12 && x + w / 2 > r[0] - 12 && y - h / 2 < r[3] + 12 && y + h / 2 > r[1] - 12) continue;
            strokeOf(th.accent, 1.3);
            rectLine(c, x - w / 2, y - h / 2, w, h);
            fr(c, x - 2, y - 2, 4, 4, th.accent);
            font(F.mono, 10, th.ink);
            double v = (mid - y) / amp;
            txt(c, pad2(i + 1) + "  " + (v >= 0 ? "+" : "") + String.format(java.util.Locale.ROOT, "%.3f", v), x - w / 2, y + h / 2 + 13);
        }
        double[] yys = {0, VH};
        for (double yy : yys) {
            int dir = yy > 0 ? -1 : 1;
            double off = (TT * .018) % 120;
            strokeOf(alpha(th.ink, .85), 1);
            line(c, 0, yy + dir * .5, VW, yy + dir * .5);
            double[][] sets = {{.28, 1, 5}, {.5, 5, 10}, {.8, 10, 16}};
            for (double[] st : sets) {
                strokeOf(alpha(th.ink, st[0]), 1);
                path.reset();
                int n = 0;
                for (double x = -120 + off; x < VW; x += 12, n++) {
                    if (n % (int) st[1] != 0) continue;
                    double qq = Math.round(x) + .5;
                    path.moveTo(f(qq), f(yy));
                    path.lineTo(f(qq), f(yy + dir * st[2]));
                }
                c.drawPath(path, stroke);
            }
        }
        if (!phone) {
            double bx = 28, by = VH - 34;
            int[] series = {1, 2, 3, 5, 8, 13, 21};
            for (int i = 0; i < series.length; i++) {
                double sz = series[i] * 1.1 + 3;
                boolean on = (long) Math.floor(TT / 500) % 8 > i;
                strokeOf(alpha(th.ink, .7), 1);
                rectLine(c, bx, by - sz, sz, sz);
                if (on) fr(c, bx + 1, by - sz + 1, sz - 1, sz - 1, th.accent);
                bx += sz + 7;
            }
            if (formula) {
                fr(c, 22, 30, 322, 58, alpha(th.bg, .86));
                font(F.mono, 11, alpha(th.ink, .85));
                txt(c, "y(t) = A(t) sin(ωt + φ)", 28, 44);
                font(F.mono, 11, alpha(th.ink, .6));
                txt(c, "A(t) = 1 − exp(−k Σ e^(−(t − ti)² / 2σ²))", 28, 62);
                txt(c, "φ = " + String.format(java.util.Locale.ROOT, "%.3f", ph % 6.283) + " rad", 28, 80);
            }
        }
    }

    private double techWave(double x, double qq, int li, double mid, double amp, double per, double ph, double TT) {
        double u = x / VW, sn = Math.sin(u * Math.PI * 1.08);
        double env = .14 + .86 * sn * sn * (.7 + .3 * Math.sin(u * 11 + TT * .0002));
        return mid - env * amp * qq * Math.sin(x / per * 6.283 - ph + li * .3);
    }

    /* ---------- overview of all projects ---------- */
    private void drawHub(Canvas c) {
        boolean phone = VW <= 760;
        drawTech(c, phone ? VH * .88 : VH * .76, phone ? VH * .07 : VH * .17, phone ? VW * .5 : VW * .8, phone ? VH * .62 : VH * .58, Math.min(VW, VH) * (phone ? .42 : .5), false, null);
        c.save();
        double sc = b.hubScroll;
        c.translate(0, f(-sc));
        double content = hubLayout(c, sc);
        c.restore();
        b.hubContentH = content;
    }

    private double hubButton(Canvas c, double x, double y, String label, String key, boolean primary, double sc) {
        font(primary ? F.cjkBold : F.cjkMed, 13, primary ? th.onAccent : th.ink);
        double w = tw(label) + 24, h = 36;
        boolean press = b.isPressed(key, null);
        fr(c, x, y, w, h, primary ? th.accent : th.card);
        if (press) fr(c, x, y, w, h, alpha(primary ? th.bg : th.ink, .14));
        strokeOf(primary ? th.accent : th.line, 1);
        c.drawRect(f(x + .5), f(y + .5), f(x + w - .5), f(y + h - .5), stroke);
        txt(c, label, x + 12, base(y, h));
        b.addBtn(x, y - sc, w, h, key, null);
        return w;
    }

    private double hubLayout(Canvas c, double sc) {
        List<Map<String, Object>> ps = Core.projects(b.store);
        double L = 16, R = VW - 16, maxW = 1240;
        if (VW > maxW + 32) { L = (VW - maxW) / 2; R = L + maxW; }
        double y = 24;
        // header
        double headTop = y;
        double h1 = clamp(VW * .04, 28, 44);
        int n = ps.size();
        double sumT = 0;
        int late = 0, portals = 0;
        double[] totals = new double[n];
        for (int i = 0; i < n; i++) {
            Map<String, Object> p = ps.get(i);
            totals[i] = totalCached(p);
            sumT += totals[i];
            for (Map<String, Object> m : nodes(p)) {
                if (Core.isPortal(m)) portals++;
                else if (!Core.isGate(m) && !"done".equals(m.get("status")) && dn(s(m, "date")) < b.today) late++;
            }
        }
        long avg = n > 0 ? Math.round(sumT / n) : 0;
        String[][] sums = {{String.valueOf(n), "專案"}, {avg + "%", "平均完成度"}, {String.valueOf(portals), "傳送門"}, {String.valueOf(late), "逾期節點"}};
        double innerL = L + 16;
        // title and numbers side by side when there is room
        double sumW = 0;
        for (String[] st : sums) {
            font(F.dispSemi, 28, th.ink);
            double a = tw(st[0]);
            font(F.mono, 12, th.dim);
            sumW += Math.max(a, tw(st[1])) + 22;
        }
        font(F.cjkBold, h1, th.ink);
        double titleW = tw("專案總覽");
        boolean inline = titleW + 28 + sumW < R - innerL - 16;
        double tY = headTop + 14;
        double sY = inline ? tY + Math.max(0, h1 * 1.1 - 45) : tY + h1 * 1.1 + 12;
        double headBottomBeforeBtns = (inline ? tY + Math.max(h1 * 1.1, 45) : sY + 45) + 12;
        // buttons: measure rows
        String[][] bt = {{"備份與還原", "hubBackup"}, {"輸入邀請碼", "hubJoin"}, {"登出", "hubOut"}, {"＋ 新增專案", "hubNew"}};
        double bx = innerL, by = headBottomBeforeBtns, bh = 36, gap = 12;
        List<double[]> pos = new ArrayList<>();
        for (int i = 0; i < bt.length; i++) {
            if (bt[i][1].equals("hubJoin") && (info == null || !info.signedIn())) { pos.add(null); continue; }
            font(i == 3 ? F.cjkBold : F.cjkMed, 13, th.ink);
            double w = tw(bt[i][0]) + 24;
            if (bx + w > R - 16 && bx > innerL) { bx = innerL; by += bh + 8; }
            pos.add(new double[]{bx, by});
            bx += w + gap;
        }
        double headBottom = by + bh + 16;
        // cards are drawn only on screen, but always measured
        fr(c, L, headTop, R - L, headBottom - headTop, alpha(th.bg, .86));
        strokeOf(th.ink, 1);
        line(c, L, headBottom, R, headBottom);
        font(F.cjkBold, h1, th.ink);
        txt(c, "專案總覽", innerL, base(tY, h1 * 1.1));
        double sx = inline ? innerL + titleW + 28 : innerL;
        for (int i = 0; i < sums.length; i++) {
            boolean bad = i == 3 && late > 0;
            font(F.dispSemi, 28, bad ? th.danger : th.ink);
            double a = tw(sums[i][0]);
            txt(c, sums[i][0], sx, base(sY, 28));
            font(F.mono, 12, th.dim);
            double e = tw(sums[i][1]);
            txt(c, sums[i][1], sx, base(sY + 29, 16));
            sx += Math.max(a, e) + 22;
        }
        for (int i = 0; i < bt.length; i++) {
            double[] q0 = pos.get(i);
            if (q0 != null) hubButton(c, q0[0], q0[1], bt[i][0], bt[i][1], i == 3, sc);
        }
        y = headBottom + 24 + 4;
        double colGap = 26, rowGap = 30;
        double avail = R - L - 8;
        int cols = Math.max(1, (int) Math.floor((avail + colGap) / (Math.min(340, avail) + colGap)));
        double cw = (avail - colGap * (cols - 1)) / cols;
        boolean single = n < 2;
        int i = 0;
        double vTop = sc - 60, vBot = sc + VH + 60;
        while (i < n) {
            double rowH = 0;
            for (int k = 0; k < cols && i + k < n; k++) rowH = Math.max(rowH, hubCard(null, ps.get(i + k), i + k, 0, 0, cw, totals[i + k], single, 0, 0));
            for (int k = 0; k < cols && i + k < n; k++) {
                double cx = L + 4 + k * (cw + colGap);
                if (y + rowH > vTop && y < vBot) hubCard(c, ps.get(i + k), i + k, cx, y, cw, totals[i + k], single, rowH, sc);
            }
            y += rowH + rowGap;
            i += cols;
        }
        return y + 16;
    }

    /** Measures a project card, and draws it when given a canvas. */
    private double hubCard(Canvas c, Map<String, Object> p, int idx, double x, double y, double w, double total, boolean single, double forceH, double sc) {
        String id = s(p, "id");
        boolean cur = b.state != null && id.equals(s(b.state, "id")), won = isWonCached(p), sample = Core.b(p, "isSample");
        long pc = Math.round(total);
        double padX = 16, inner = w - padX * 2;
        double top = y + 14;
        // big number
        font(F.disp, 44, th.accent);
        String ns = String.valueOf(pc);
        double numW = Math.max(70, tw(ns) + 18);
        double nameX = x + padX + numW + 12, nameW = w - padX * 2 - numW - 12;
        font(F.cjkBold, 18, th.ink);
        String[] nameLines = wrap(s(p, "name"), nameW, 3);
        double chipW = 0;
        if (won) chipW += 62;
        if (sample) chipW += 62;
        font(F.cjkBold, 18, th.ink);
        double lastW = tw(nameLines[nameLines.length - 1]);
        boolean chipsBelow = (won || sample) && lastW + 8 + chipW > nameW;
        double hBlock = Math.max(40, nameLines.length * 22.5 + (chipsBelow ? 20 : 0));
        // facts
        List<Map<String, Object>> cn = new ArrayList<>();
        int done = 0, lt = 0;
        Map<String, Object> next = null;
        for (Map<String, Object> m : nodes(p)) {
            String st = String.valueOf(m.get("status"));
            if (Core.counted(m)) {
                cn.add(m);
                if ("done".equals(st)) done++;
                else if (dn(s(m, "date")) >= b.today && (next == null || dn(s(m, "date")) < dn(s(next, "date")))) next = m;
            }
            if (!"done".equals(st) && !Core.isPortal(m) && !Core.isGate(m) && dn(s(m, "date")) < b.today) lt++;
        }
        List<String[]> facts = new ArrayList<>();
        facts.add(new String[]{"節點 " + done + "/" + cn.size(), null});
        facts.add(new String[]{"時間線 " + Core.lanes(p).size(), null});
        String others = info == null ? "" : info.others(id);
        if (others != null && !others.isEmpty()) facts.add(new String[]{"共同編輯 " + others, null});
        if (lt > 0) facts.add(new String[]{"逾期 " + lt, "1"});
        if (next != null) facts.add(new String[]{"下一步 " + s(next, "title") + " " + s(next, "date").substring(5).replace('-', '/'), null});
        font(F.mono, 11.5, th.dim);
        List<List<String[]>> rows = new ArrayList<>();
        List<String[]> row = new ArrayList<>();
        double rw = 0;
        for (String[] ft : facts) {
            double fw = Math.min(tw(ft[0]), inner);
            if (!row.isEmpty() && rw + 11 + fw > inner) { rows.add(row); row = new ArrayList<>(); rw = 0; }
            rw += (row.isEmpty() ? 0 : 11) + fw;
            row.add(ft);
        }
        if (!row.isEmpty()) rows.add(row);
        double factsH = rows.size() * 15.5;
        double svgH = inner * 64 / 320;
        // portal chips
        List<String> links = new ArrayList<>();
        for (Map<String, Object> m : nodes(p)) if (Core.isPortal(m)) {
            Map<String, Object> q0 = Core.project(b.store, s(m, "target"));
            if (q0 != null) links.add("傳送門 → " + s(q0, "name"));
        }
        font(F.cjkMed, 12, th.ink);
        List<List<String>> linkRows = new ArrayList<>();
        List<String> lr = new ArrayList<>();
        double lw = 0;
        for (String t : links) {
            double tw0 = Math.min(tw(t) + 14, inner);
            if (!lr.isEmpty() && lw + 5 + tw0 > inner) { linkRows.add(lr); lr = new ArrayList<>(); lw = 0; }
            lw += (lr.isEmpty() ? 0 : 5) + tw0;
            lr.add(t);
        }
        if (!lr.isEmpty()) linkRows.add(lr);
        double linksH = linkRows.isEmpty() ? 0 : linkRows.size() * 23 + 10;
        double footH = 42;
        double H0 = 14 + hBlock + 10 + factsH + 10 + svgH + linksH + 12 + footH;
        if (c == null) return H0;
        double h = Math.max(H0, forceH);
        fr(c, x, y, w, h, th.card);
        strokeOf(th.line, 1);
        c.drawRect(f(x + .5), f(y + .5), f(x + w - .5), f(y + h - .5), stroke);
        boolean press = b.isPressed("popen", id);
        if (press) fr(c, x, y, w, h, alpha(th.ink, .05));
        double off = 4 + (cur || won ? 1 : .625);
        strokeOf(th.accent, cur || won ? 2 : 1.25);
        c.drawRect(f(x - off), f(y - off), f(x + w + off), f(y + h + off), stroke);
        if (won) { strokeOf(th.accent, 1); c.drawRect(f(x - 7.5), f(y - 7.5), f(x + w + 7.5), f(y + h + 7.5), stroke); }
        font(F.disp, 44, th.accent);
        double ax = x + padX;
        txt(c, ns, ax, base(top, 40));
        double nw = tw(ns);
        font(F.disp, 17, th.accent);
        txt(c, "%", ax + nw + 1, top + 14);
        font(F.cjkBold, 18, th.ink);
        double ly = top;
        for (String t : nameLines) { txt(c, t, nameX, base(ly, 22.5)); ly += 22.5; }
        double cx0 = chipsBelow ? nameX : nameX + lastW + 8, cy0 = chipsBelow ? ly + 10 : ly - 11;
        if (won) cx0 = chip(c, "專案完成", cx0, cy0, true);
        if (sample) chip(c, "範例資料", cx0, cy0, false);
        double yy = top + hBlock + 10;
        for (List<String[]> rr : rows) {
            double fx = x + padX;
            for (String[] ft : rr) {
                font(ft[1] != null ? F.monoMed : F.mono, 11.5, ft[1] != null ? th.danger : th.dim);
                String t = ell(ft[0], inner);
                txt(c, t, fx, base(yy, 15.5));
                fx += tw(t) + 11;
            }
            yy += 15.5;
        }
        yy += 10;
        miniMap(c, p, x + padX, yy, inner, svgH);
        yy += svgH;
        if (!linkRows.isEmpty()) {
            yy += 10;
            for (List<String> rr : linkRows) {
                double fx = x + padX;
                for (String t : rr) {
                    font(F.cjkMed, 12, th.ink);
                    double tw0 = Math.min(tw(t) + 14, inner);
                    strokeOf(th.line, 1);
                    c.drawRect(f(fx + .5), f(yy + .5), f(fx + tw0 - .5), f(yy + 19.5), stroke);
                    txt(c, ell(t, tw0 - 14), fx + 7, base(yy, 20));
                    fx += tw0 + 5;
                }
                yy += 23;
            }
        }
        // foot
        double fy = y + h - footH;
        font(F.mono, 11, th.dim);
        txt(c, cur ? "目前開啟" : "P-" + pad2(idx + 1), x + padX, base(fy, 30));
        b.addBtn(x, y - sc, w, fy - y, "popen", id);
        if (!single) {
            Integer arm = b.delArm.get(id);
            boolean other = info != null && info.ownedByOther(id);
            String lb = arm == null ? (other ? "退出" : "刪除") : other ? "再按一次確認退出" : info != null && info.memberCount(id) > 1 ? "所有成員都會失去，再按一次" : "再按一次確認刪除";
            font(F.cjkMed, 12, arm != null ? th.bg : th.dim);
            double dw = tw(lb) + 18, dx = x + w - padX - dw;
            if (arm != null) fr(c, dx, fy, dw, 30, th.danger);
            strokeOf(arm != null ? th.danger : th.line, 1);
            c.drawRect(f(dx + .5), f(fy + .5), f(dx + dw - .5), f(fy + 29.5), stroke);
            txt(c, lb, dx + 9, base(fy, 30));
            b.addBtn(dx, fy - sc, dw, 30, "pdel", id);
        }
        return h;
    }

    /** miniSvg() */
    private void miniMap(Canvas c, Map<String, Object> p, double x, double y, double w, double h) {
        strokeOf(th.line, 1);
        line(c, x, y + .5, x + w, y + .5);
        line(c, x, y + h - .5, x + w, y + h - .5);
        List<Map<String, Object>> ns = nodes(p);
        if (ns.isEmpty()) {
            font(F.cjk, 12 * w / 320, alpha(th.ink, .6));
            txt(c, "尚無節點", x + 12 * w / 320, y + 36 * w / 320);
            return;
        }
        int a = b.today, e = b.today;
        for (Map<String, Object> m : ns) { int d = dn(s(m, "date")); a = Math.min(a, d); e = Math.max(e, d); }
        double K = w / 320;
        List<Map<String, Object>> ls = Core.lanes(p);
        int rows = ls.size();
        for (int i = 0; i < rows; i++) {
            Map<String, Object> l = ls.get(i);
            double ry = y + (i + .5) * h / rows;
            strokeOf(alpha(th.ink, .18), 1);
            line(c, x, ry, x + w, ry);
            int col = Theme.hex(s(l, "color"));
            for (Map<String, Object> m : ns) {
                if (!s(l, "id").equals(s(m, "lane"))) continue;
                double dx = x + (10 + (dn(s(m, "date")) - a) / (double) Math.max(1, e - a) * 300) * K - 3 * K, dy = ry - 3.5 * K;
                if (Core.isPortal(m)) {
                    strokeOf(col, 1);
                    c.drawRect(f(dx - 1.5 * K), f(dy - 1.5 * K), f(dx + 7.5 * K), f(dy + 8.5 * K), stroke);
                    fr(c, dx + 1 * K, dy + 1 * K, 4 * K, 5 * K, col);
                } else {
                    if ("done".equals(m.get("status"))) fr(c, dx, dy, 6 * K, 7 * K, col);
                    strokeOf(col, 1);
                    c.drawRect(f(dx), f(dy), f(dx + 6 * K), f(dy + 7 * K), stroke);
                }
            }
        }
        double t = x + (10 + (b.today - a) / (double) Math.max(1, e - a) * 300) * K;
        strokeOf(th.accent, 1);
        line(c, t, y, t, y + h);
    }
}
