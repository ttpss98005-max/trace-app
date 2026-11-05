package tw.trace.app;

import static tw.trace.app.Json.arr;
import static tw.trace.app.Json.obj;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Pattern;

/** Data rules shared with the web version. Every function here mirrors one in the web source. */
final class Core {
    private Core() {}

    static final int APP_SCHEMA = 4;
    static final int DOC_LIMIT = 950000;
    static final int BK_MAX = 10, BK_LIMIT = 900000;
    static final long BK_EVERY = 6L * 3600000L;

    /* ---------- time ---------- */
    private static final Map<String, Integer> dnMemo = new HashMap<>();

    static synchronized int dn(String s) {
        Integer v = dnMemo.get(s);
        if (v != null) return v;
        int r;
        try {
            String[] p = String.valueOf(s).split("-");
            int y = Integer.parseInt(p[0]), m = Integer.parseInt(p[1]), d = Integer.parseInt(p[2]);
            y += Math.floorDiv(m - 1, 12);
            m = Math.floorMod(m - 1, 12) + 1;
            r = (int) LocalDate.of(y, m, 1).plusDays(d - 1L).toEpochDay();
        } catch (RuntimeException e) {
            r = today();
        }
        if (dnMemo.size() > 5000) dnMemo.clear();
        dnMemo.put(s, r);
        return r;
    }

    static String ds(int n) {
        return LocalDate.ofEpochDay(n).toString();
    }

    static int today() {
        long now = System.currentTimeMillis();
        return (int) Math.floorDiv(now + TimeZone.getDefault().getOffset(now), 86400000L);
    }

    /* ---------- ids ---------- */
    static String uid() {
        // Math.random().toString(36).slice(2,9)
        StringBuilder b = new StringBuilder();
        String al = "0123456789abcdefghijklmnopqrstuvwxyz";
        for (int i = 0; i < 7; i++) b.append(al.charAt((int) (Math.random() * 36)));
        return b.toString();
    }

    /* ---------- accessors ---------- */
    static String s(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : (v instanceof String ? (String) v : Json.str(v));
    }

    static boolean b(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) return false;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).doubleValue() != 0;
        return !(v instanceof String) || !((String) v).isEmpty();
    }

    static double n(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Number ? ((Number) v).doubleValue() : 0;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> nodes(Map<String, Object> p) { return (List<Map<String, Object>>) p.get("nodes"); }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> lanes(Map<String, Object> p) { return (List<Map<String, Object>>) p.get("lanes"); }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> projects(Map<String, Object> st) { return (List<Map<String, Object>>) st.get("projects"); }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> items(Map<String, Object> n) {
        Object v = n.get("items");
        return v instanceof List ? (List<Map<String, Object>>) v : new ArrayList<Map<String, Object>>();
    }

    static List<Object> links(Map<String, Object> n) {
        Object v = n.get("links");
        return v instanceof List ? arr(v) : new ArrayList<Object>();
    }

    static boolean isPortal(Map<String, Object> n) { return "portal".equals(n.get("kind")); }

    /** A review switch: no checklist or due date, just passed (manualDone) or not. Older versions see a plain node. */
    static boolean isGate(Map<String, Object> n) { return "gate".equals(n.get("kind")); }

    static boolean counted(Map<String, Object> n) { return !(isPortal(n) && b(n, "noCount")); }

    static Map<String, Object> project(Map<String, Object> st, String id) {
        if (id == null) return null;
        for (Map<String, Object> p : projects(st)) if (id.equals(s(p, "id"))) return p;
        return null;
    }

    static Map<String, Object> nodeOf(Map<String, Object> p, String id) {
        if (id == null) return null;
        for (Map<String, Object> n : nodes(p)) if (id.equals(s(n, "id"))) return n;
        return null;
    }

    static Map<String, Object> laneOf(Map<String, Object> p, String id) {
        if (id == null) return null;
        for (Map<String, Object> l : lanes(p)) if (id.equals(s(l, "id"))) return l;
        return null;
    }

    /* ---------- projects ---------- */
    static Map<String, Object> newProject(String name) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", uid());
        p.put("name", name);
        p.put("density", "month");
        p.put("lanes", Json.list(
                Json.map("id", "pay", "name", "付費", "kind", "branch", "color", "#D9920F"),
                Json.map("id", "main", "name", "專案主線", "kind", "main", "color", "#E8304A"),
                Json.map("id", "misc", "name", "雜項", "kind", "branch", "color", "#119EAE")));
        p.put("nodes", new ArrayList<Object>());
        return p;
    }

    static Map<String, Object> freshStore() {
        Map<String, Object> p = newProject("我的第一個專案");
        Map<String, Object> st = new LinkedHashMap<>();
        st.put("v", 3d);
        st.put("theme", Json.map("preset", "auto"));
        st.put("projects", Json.list(p));
        st.put("current", p.get("id"));
        return st;
    }

    static void syncNode(Map<String, Object> n) {
        List<Map<String, Object>> it = items(n);
        int d = 0;
        for (Map<String, Object> x : it) if (b(x, "done")) d++;
        if (it.isEmpty()) {
            boolean md = b(n, "manualDone");
            n.put("pct", md ? 100d : 0d);
            n.put("status", md ? "done" : b(n, "blocked") ? "blocked" : "todo");
            return;
        }
        n.put("pct", (double) Math.round(d * 100d / it.size()));
        n.put("status", d == it.size() ? "done" : b(n, "blocked") ? "blocked" : d > 0 ? "doing" : "todo");
    }

    static double projTotal(Map<String, Object> st, Map<String, Object> p) {
        return projTotal(st, p, new HashSet<String>());
    }

    private static double projTotal(Map<String, Object> st, Map<String, Object> p, Set<String> seen) {
        if (p == null || nodes(p).isEmpty()) return 0;
        seen.add(s(p, "id"));
        double t = 0;
        int c = 0;
        for (Map<String, Object> n : nodes(p)) {
            if (isPortal(n)) {
                Map<String, Object> q = project(st, s(n, "target"));
                if (q != null && !seen.contains(s(q, "id"))) n.put("pct", (double) Math.round(projTotal(st, q, new HashSet<>(seen))));
                else if (q == null) n.put("pct", 0d);
                double pc = n(n, "pct");
                n.put("status", pc >= 100 ? "done" : pc > 0 ? "doing" : "todo");
            } else syncNode(n);
            if (counted(n)) { t += n(n, "pct"); c++; }
        }
        return c > 0 ? t / c : 0;
    }

    static boolean isWon(Map<String, Object> st, Map<String, Object> p) {
        if (p == null) return false;
        boolean any = false;
        for (Map<String, Object> n : nodes(p)) if (counted(n)) { any = true; break; }
        return any && projTotal(st, p) >= 100;
    }

    /* ---------- saved form ---------- */
    /** canon(): the saved form of a project, without derived values or dangling references. */
    static Map<String, Object> canon(Map<String, Object> p) {
        Map<String, Object> c = obj(Json.copy(p));
        Set<String> ids = new HashSet<>();
        for (Map<String, Object> n : nodes(c)) ids.add(s(n, "id"));
        for (Map<String, Object> n : nodes(c)) {
            n.remove("pct");
            n.remove("status");
            if (n.containsKey("links") && b(n, "links")) {
                List<Object> v = new ArrayList<>();
                Set<Object> u = new LinkedHashSet<>(arr(n.get("links")));
                String me = s(n, "id");
                for (Object i : u) if (ids.contains(String.valueOf(i)) && !String.valueOf(i).equals(me)) v.add(i);
                if (!v.isEmpty()) n.put("links", v);
                else n.remove("links");
            }
        }
        for (Map<String, Object> l : lanes(c)) {
            String f = s(l, "from");
            if (b(l, "from") && !"root".equals(f) && !ids.contains(f)) l.remove("from");
        }
        c.remove("theme");
        c.remove("v");
        return c;
    }

    private static final Pattern HEX = Pattern.compile("^#[0-9a-fA-F]{6}$"), DAY = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    /** cleanProject(): make any loaded project safe to use. Throws on damaged data. */
    static Map<String, Object> cleanProject(Map<String, Object> p) {
        if (p == null || !(p.get("lanes") instanceof List) || !(p.get("nodes") instanceof List) || arr(p.get("lanes")).isEmpty())
            throw new IllegalArgumentException("bad project");
        p.put("name", p.get("name") == null ? "未命名專案" : jsString(p.get("name")));
        String den = s(p, "density");
        if (!"week".equals(den) && !"month".equals(den) && !"quarter".equals(den)) p.put("density", "month");
        List<Object> ls = onlyObjects(arr(p.get("lanes")));
        p.put("lanes", ls);
        for (Object o : ls) {
            Map<String, Object> l = obj(o);
            l.put("id", jsString(l.get("id")));
            l.put("name", l.get("name") == null ? "未命名" : jsString(l.get("name")));
            Object col = l.get("color");
            if (!(col instanceof String) || !HEX.matcher((String) col).find()) l.put("color", "#119EAE");
            if (!Boolean.TRUE.equals(l.get("detached"))) l.remove("detached");
        }
        List<Object> ns = onlyObjects(arr(p.get("nodes")));
        p.put("nodes", ns);
        for (Object o : ns) {
            Map<String, Object> n = obj(o);
            n.put("id", jsString(n.get("id")));
            n.put("lane", jsString(n.get("lane")));
            n.put("title", n.get("title") == null ? "未命名" : jsString(n.get("title")));
            Object dt = n.get("date");
            if (!(dt instanceof String) || !DAY.matcher((String) dt).find()) n.put("date", ds(today()));
            Object pc = n.get("pct");
            if (!(pc instanceof Number) || Double.isNaN(((Number) pc).doubleValue()) || Double.isInfinite(((Number) pc).doubleValue())) n.put("pct", 0d);
            String stt = s(n, "status");
            if (!"todo".equals(stt) && !"doing".equals(stt) && !"done".equals(stt) && !"blocked".equals(stt)) n.put("status", "todo");
            if (b(n, "items") && !(n.get("items") instanceof List)) n.put("items", new ArrayList<Object>());
            if (b(n, "items")) n.put("items", onlyObjects(arr(n.get("items"))));
            if (b(n, "links") && !(n.get("links") instanceof List)) n.remove("links");
            if (!Boolean.TRUE.equals(n.get("cutIn"))) n.remove("cutIn");
        }
        return p;
    }

    private static String jsString(Object v) {
        if (v == null) return "null";
        if (v instanceof String) return (String) v;
        if (v instanceof Number) return Json.num(((Number) v).doubleValue());
        return String.valueOf(v);
    }

    private static List<Object> onlyObjects(List<Object> a) {
        List<Object> o = new ArrayList<>();
        for (Object x : a) if (x instanceof Map) o.add(x);
        return o;
    }

    /* ---------- three-way merge ---------- */
    private interface ItemMerge { Map<String, Object> merge(Map<String, Object> b, Map<String, Object> m, Map<String, Object> r); }

    private static final Object UNDEF = new Object();

    private static Object at(Map<String, Object> m, String k) {
        return m != null && m.containsKey(k) ? m.get(k) : UNDEF;
    }

    private static String st(Object v) { return v == UNDEF ? "null" : Json.stable(v); }

    private static final ItemMerge MERGE_ITEM = new ItemMerge() {
        @Override public Map<String, Object> merge(Map<String, Object> b, Map<String, Object> m, Map<String, Object> r) { return mergeObj(b, m, r, null); }
    };
    private static final ItemMerge MERGE_NODE = new ItemMerge() {
        @Override public Map<String, Object> merge(Map<String, Object> b, Map<String, Object> m, Map<String, Object> r) {
            Map<String, ItemMerge> k = new HashMap<>();
            k.put("items", MERGE_ITEM);
            return mergeObj(b, m, r, k);
        }
    };

    private static Map<String, Object> mergeObj(Map<String, Object> b, Map<String, Object> m, Map<String, Object> r, Map<String, ItemMerge> listKeys) {
        Map<String, Object> out = new LinkedHashMap<>();
        Set<String> keys = new LinkedHashSet<>();
        if (m != null) keys.addAll(m.keySet());
        if (r != null) keys.addAll(r.keySet());
        for (String k : keys) {
            if (listKeys != null && listKeys.containsKey(k)) {
                out.put(k, mergeList(listOf(b, k), listOf(m, k), listOf(r, k), listKeys.get(k)));
                continue;
            }
            Object mv = at(m, k), rv = at(r, k), bv = at(b, k);
            Object v = st(mv).equals(st(bv)) ? rv : mv;
            if (v != UNDEF) out.put(k, v);
        }
        return out;
    }

    private static List<Object> listOf(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v instanceof List ? arr(v) : new ArrayList<Object>();
    }

    private static Map<String, Map<String, Object>> byId(List<Object> a) {
        Map<String, Map<String, Object>> o = new LinkedHashMap<>();
        for (Object x : a) o.put(idOf(x), obj(x));
        return o;
    }

    private static String idOf(Object x) {
        Object v = obj(x).get("id");
        return v instanceof String ? (String) v : jsString(v);
    }

    private static List<Object> mergeList(List<Object> b, List<Object> m, List<Object> r, ItemMerge item) {
        Map<String, Map<String, Object>> B = byId(b), M = byId(m), R = byId(r);
        List<Object> o1 = new ArrayList<>(), o2 = new ArrayList<>();
        for (Object x : m) if (B.containsKey(idOf(x))) o1.add(obj(x).get("id"));
        for (Object x : b) if (M.containsKey(idOf(x))) o2.add(obj(x).get("id"));
        boolean moved = !Json.stable(o1).equals(Json.stable(o2));
        List<Object> order = new ArrayList<>(moved ? m : r);
        order.addAll(moved ? r : m);
        Set<String> seen = new HashSet<>();
        List<Object> out = new ArrayList<>();
        for (Object o : order) {
            String id = idOf(o);
            if (!seen.add(id)) continue;
            Map<String, Object> bi = B.get(id), mi = M.get(id), ri = R.get(id), v;
            if (bi != null && mi == null) v = null;
            else if (bi != null && ri == null) v = Json.stable(mi).equals(Json.stable(bi)) ? null : mi;
            else if (bi == null) v = mi != null && ri != null ? item.merge(mi, mi, ri) : (mi != null ? mi : ri);
            else v = item.merge(bi, mi, ri);
            if (v != null) out.add(v);
        }
        return out;
    }

    /** merge3(): what I changed since the last sync wins, everything else comes from the other account. */
    static Map<String, Object> merge3(Map<String, Object> b, Map<String, Object> m, Map<String, Object> r) {
        Map<String, ItemMerge> k = new HashMap<>();
        k.put("nodes", MERGE_NODE);
        k.put("lanes", MERGE_ITEM);
        return mergeObj(b, m, r, k);
    }

    /** applyProject(): write a saved form into the live project, keeping node objects alive for open forms. */
    static void applyProject(Map<String, Object> store, String pid, String json) {
        Map<String, Object> remote = cleanProject(obj(Json.parse(json)));
        Map<String, Object> p = project(store, pid);
        if (p == null) { arr(store.get("projects")).add(remote); return; }
        for (String k : new ArrayList<>(p.keySet())) if (!k.equals("nodes") && !remote.containsKey(k)) p.remove(k);
        for (Map.Entry<String, Object> e : remote.entrySet()) if (!e.getKey().equals("nodes")) p.put(e.getKey(), e.getValue());
        Map<String, Map<String, Object>> map = new HashMap<>();
        for (Map<String, Object> n : nodes(p)) map.put(s(n, "id"), n);
        List<Object> out = new ArrayList<>();
        for (Object o : arr(remote.get("nodes"))) {
            Map<String, Object> r = obj(o), e = map.get(s(r, "id"));
            if (e == null) { out.add(r); continue; }
            for (String k : new ArrayList<>(e.keySet())) if (!r.containsKey(k)) e.remove(k);
            e.putAll(r);
            out.add(e);
        }
        p.put("nodes", out);
    }

    /* ---------- upgrade of old saved data ---------- */
    private static final Map<String, String[]> SAMPLE_ITEMS = new HashMap<>();
    static {
        String[][] t = {
            {"需求定案", "訪談使用者", "確認功能範圍", "簽核驗收條件"}, {"原型驗證", "製作可點擊原型", "五人可用性測試", "修正主要流程"}, {"Alpha 版", "核心功能完成", "內部測試", "修復嚴重錯誤"},
            {"Beta 測試", "招募測試者", "第一輪回饋", "修正回報問題", "第二輪回饋", "效能檢查"}, {"內容鎖定", "文案定稿", "圖片定稿", "翻譯校對", "凍結內容"}, {"送審", "準備商店素材", "填寫隱私問卷", "送出審核"}, {"正式上線", "上架", "監控錯誤", "發布公告"},
            {"網域與主機年費", "比價", "完成付款"}, {"字型授權", "確認授權範圍", "完成付款"}, {"開發者帳號", "註冊帳號", "上傳公司文件", "等待審核", "完成付款", "啟用帳號"}, {"廣告預算第一期", "提報預算", "簽核", "撥款"}, {"法務審約", "送交條款", "修改意見", "定稿"},
            {"產品命名", "發想名單", "商標查詢", "決定名稱"}, {"備份機制", "選擇方案", "設定排程", "還原演練"}, {"客服信箱", "申請信箱", "設定轉寄", "撰寫範本", "測試收發"}, {"使用手冊", "列大綱", "撰寫內容", "截圖", "校對", "發布"}, {"上線公告", "撰寫草稿", "排程發送"},
            {"活動企劃", "確認目標", "預算分配"}, {"素材製作", "主視覺", "短影片", "社群圖"}, {"媒體排程", "選定平台", "排定檔期"}, {"KOL 合作訂金", "確認名單", "簽約付款"}, {"廣告投放", "設定受眾", "上線投放"}, {"社群貼文", "文案", "排程", "互動回覆", "成效整理", "下期規劃"}
        };
        for (String[] r : t) {
            String[] v = new String[r.length - 1];
            System.arraycopy(r, 1, v, 0, v.length);
            SAMPLE_ITEMS.put(r[0], v);
        }
    }

    private static Map<String, Object> demoNode(String lane, String title, int day, String status, double pct, String note) {
        return Json.map("id", uid(), "lane", lane, "title", title, "date", ds(day), "status", status, "pct", pct, "note", note);
    }

    private static void addDemo(Map<String, Object> st, Map<String, Object> app, int T) {
        Map<String, Object> mk = newProject("上線行銷活動");
        mk.put("isSample", true);
        List<Object> ns = arr(mk.get("nodes"));
        ns.add(demoNode("main", "活動企劃", T - 12, "done", 100, ""));
        ns.add(demoNode("main", "素材製作", T + 4, "doing", 45, ""));
        ns.add(demoNode("main", "媒體排程", T + 18, "todo", 0, ""));
        Map<String, Object> back = demoNode("main", "回到 App 上線計畫", T + 30, "todo", 0, "");
        back.put("kind", "portal");
        back.put("target", app.get("id"));
        ns.add(back);
        ns.add(demoNode("pay", "KOL 合作訂金", T + 8, "todo", 0, ""));
        ns.add(demoNode("pay", "廣告投放", T + 24, "todo", 0, ""));
        ns.add(demoNode("misc", "社群貼文", T + 12, "todo", 20, ""));
        arr(st.get("projects")).add(mk);
        String mainId = null;
        for (Map<String, Object> l : lanes(app)) if ("main".equals(l.get("kind"))) { mainId = s(l, "id"); break; }
        if (mainId != null) {
            Map<String, Object> go = demoNode(mainId, "行銷活動", T + 9, "todo", 0, "");
            go.put("kind", "portal");
            go.put("target", mk.get("id"));
            arr(app.get("nodes")).add(go);
        }
    }

    /** sample(): the example project of the web preview, used for screenshots and the demo mode. */
    static Map<String, Object> sampleStore(int T) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("v", 1d);
        p.put("isSample", true);
        p.put("name", "手機 App 上線計畫");
        p.put("density", "month");
        p.put("theme", Json.map("preset", "auto"));
        p.put("lanes", Json.list(
                Json.map("id", "pay", "name", "付費", "kind", "branch", "color", "#D9920F"),
                Json.map("id", "main", "name", "專案主線", "kind", "main", "color", "#E8304A"),
                Json.map("id", "misc", "name", "雜項", "kind", "branch", "color", "#119EAE")));
        List<Object> ns = new ArrayList<>();
        Object[][] rows = {
            {"main", "需求定案", -60, "done", 100, "功能範圍與驗收條件已簽核。"}, {"main", "原型驗證", -41, "done", 100, ""}, {"main", "Alpha 版", -22, "done", 100, ""},
            {"main", "Beta 測試", -3, "doing", 65, "第二輪回饋收集中。"}, {"main", "內容鎖定", 15, "todo", 10, ""}, {"main", "送審", 28, "todo", 0, ""}, {"main", "正式上線", 42, "todo", 0, "專案終點。"},
            {"pay", "網域與主機年費", -52, "done", 100, ""}, {"pay", "字型授權", -30, "done", 100, ""}, {"pay", "開發者帳號", -9, "blocked", 40, "公司資料審核中，尚未付款。"}, {"pay", "廣告預算第一期", 22, "todo", 0, ""}, {"pay", "法務審約", 36, "todo", 0, ""},
            {"misc", "產品命名", -56, "done", 100, ""}, {"misc", "備份機制", -35, "done", 100, ""}, {"misc", "客服信箱", -14, "doing", 70, ""}, {"misc", "使用手冊", 6, "todo", 20, ""}, {"misc", "上線公告", 36, "todo", 0, ""}
        };
        for (Object[] r : rows) ns.add(demoNode((String) r[0], (String) r[1], T + (Integer) r[2], (String) r[3], ((Integer) r[4]).doubleValue(), (String) r[5]));
        p.put("nodes", ns);
        return migrate(p, T);
    }

    /** migrate(): upgrade any older saved shape to the current one. */
    static Map<String, Object> migrate(Map<String, Object> raw, int T) {
        return upgrade(migrateV2(raw, T));
    }

    private static Map<String, Object> migrateV2(Map<String, Object> raw, int T) {
        if (raw.containsKey("projects")) return raw;
        Map<String, Object> p = raw, st = new LinkedHashMap<>();
        st.put("v", 2d);
        st.put("theme", b(p, "theme") ? p.get("theme") : Json.map("preset", "auto"));
        st.put("projects", Json.list(p));
        st.put("current", null);
        if (!b(p, "id")) p.put("id", uid());
        p.remove("theme");
        p.remove("v");
        st.put("current", p.get("id"));
        if (b(p, "isSample")) addDemo(st, p, T);
        return st;
    }

    private static Map<String, Object> upgrade(Map<String, Object> st) {
        if (n(st, "v") >= 3) return st;
        for (Map<String, Object> p : projects(st)) for (Map<String, Object> n : nodes(p)) {
            if (isPortal(n) || n.get("items") instanceof List) continue;
            if ("blocked".equals(n.get("status"))) n.put("blocked", true);
            String[] t = b(p, "isSample") ? SAMPLE_ITEMS.get(s(n, "title")) : null;
            List<Object> out = new ArrayList<>();
            if (t != null) {
                long k = Math.round(n(n, "pct") / 100 * t.length);
                for (int i = 0; i < t.length; i++) out.add(Json.map("id", uid(), "text", t[i], "done", i < k));
            }
            n.put("items", out);
        }
        st.put("v", 3d);
        return st;
    }

    /* ---------- backup, export, import ---------- */
    static long hashStr(String t) {
        long h = 5381;
        for (int i = 0; i < t.length(); i++) {
            long x = (h * 33) & 0xFFFFFFFFL;
            h = (x ^ t.charAt(i)) & 0xFFFFFFFFL;
        }
        return h;
    }

    static String hash36(String t) { return Long.toString(hashStr(t), 36); }

    static Map<String, Object> payload(Map<String, Object> store, List<Map<String, Object>> list, String nowIso) {
        List<Object> ps = new ArrayList<>();
        for (Map<String, Object> p : list) ps.add(canon(p));
        Object th = store.get("theme");
        return Json.map("format", "trace-backup", "version", 1d, "schema", (double) APP_SCHEMA, "exportedAt", nowIso,
                "theme", th != null ? th : Json.map("preset", "auto"), "projects", ps);
    }

    /** parseBackup(): throws with a message for the person when the file is unusable. */
    static List<Map<String, Object>> parseBackup(String text) {
        Object d;
        try { d = Json.parse(text); } catch (RuntimeException e) { throw new IllegalArgumentException("這不是有效的 JSON 檔案。"); }
        List<Object> list = null;
        Map<String, Object> m = d instanceof Map ? obj(d) : null;
        if (m != null && m.get("projects") instanceof List) list = arr(m.get("projects"));
        else if (m != null && m.get("lanes") instanceof List) list = Json.list(m);
        if (list == null || list.isEmpty()) throw new IllegalArgumentException("檔案裡找不到專案資料。");
        if (m.get("schema") instanceof Number && n(m, "schema") > APP_SCHEMA) throw new IllegalArgumentException("這個檔案來自較新的版本，請先更新 App。");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            Map<String, Object> p = o instanceof Map ? obj(o) : null;
            if (p == null || !(p.get("name") instanceof String) || !(p.get("lanes") instanceof List) || !(p.get("nodes") instanceof List) || arr(p.get("lanes")).isEmpty())
                throw new IllegalArgumentException("檔案格式不正確，缺少專案名稱或時間線。");
            out.add(p);
        }
        return out;
    }

    private static String cut(String s, int n) { return s.length() > n ? s.substring(0, n) : s; }

    private static String truthyStr(Object v, String dflt) {
        if (v == null || Boolean.FALSE.equals(v) || "".equals(v)) return dflt;
        if (v instanceof Number && ((Number) v).doubleValue() == 0) return dflt;
        return jsString(v);
    }

    /** importProjects(): always added as new copies, so an import can never overwrite existing work. */
    static List<Map<String, Object>> importProjects(Map<String, Object> store, List<Map<String, Object>> list, String suffix) {
        Map<String, String> idMap = new HashMap<>();
        for (Map<String, Object> p : list) idMap.put(s(p, "id"), uid());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> src : list) {
            Map<String, Object> p = obj(Json.copy(src));
            p.put("id", idMap.get(s(src, "id")));
            p.remove("isSample");
            p.put("name", cut(jsString(p.get("name")), 50) + (suffix == null ? "" : suffix));
            List<Map<String, Object>> ls = new ArrayList<>();
            for (Object o : arr(p.get("lanes"))) if (o instanceof Map) ls.add(obj(o));
            boolean hasMain = false;
            for (Map<String, Object> l : ls) if ("main".equals(l.get("kind"))) hasMain = true;
            if (!hasMain && !ls.isEmpty()) ls.get(0).put("kind", "main");
            Set<String> laneIds = new HashSet<>();
            for (Map<String, Object> l : ls) {
                l.put("id", truthyStr(l.get("id"), uid()));
                l.put("name", cut(truthyStr(l.get("name"), "未命名"), 16));
                if (!"main".equals(l.get("kind"))) l.put("kind", "branch");
                laneIds.add(s(l, "id"));
            }
            String first = ls.isEmpty() ? "" : s(ls.get(0), "id");
            List<Object> ns = new ArrayList<>();
            for (Object o : arr(p.get("nodes"))) {
                if (!(o instanceof Map)) continue;
                Map<String, Object> n = obj(o);
                n.put("id", truthyStr(n.get("id"), uid()));
                n.put("title", cut(truthyStr(n.get("title"), "未命名"), 60));
                n.put("note", truthyStr(n.get("note"), ""));
                if (!laneIds.contains(jsString(n.get("lane")))) n.put("lane", first);
                if (isPortal(n)) {
                    String nt = idMap.get(s(n, "target"));
                    if (nt != null) n.put("target", nt);
                } else {
                    if (!isGate(n)) n.remove("kind");
                    List<Object> its = new ArrayList<>();
                    if (n.get("items") instanceof List) for (Object x : arr(n.get("items"))) {
                        if (!(x instanceof Map)) continue;
                        Map<String, Object> xm = obj(x);
                        its.add(Json.map("id", truthyStr(xm.get("id"), uid()), "text", cut(truthyStr(xm.get("text"), ""), 80), "done", b(xm, "done")));
                    }
                    n.put("items", its);
                }
                ns.add(n);
            }
            p.put("nodes", ns);
            out.add(cleanProject(p));
        }
        for (Map<String, Object> p : out) arr(store.get("projects")).add(p);
        return out;
    }

    /* ---------- invite codes ---------- */
    private static final String ALPHA = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";

    static String newInviteCode() {
        java.security.SecureRandom r = new java.security.SecureRandom();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 8; i++) b.append(ALPHA.charAt(r.nextInt(ALPHA.length())));
        return b.toString();
    }

    static String prettyCode(String c) { return c.substring(0, 4) + "-" + c.substring(4); }
}
