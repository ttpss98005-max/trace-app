package tw.trace.app;

import static tw.trace.app.Core.APP_SCHEMA;
import static tw.trace.app.Core.BK_EVERY;
import static tw.trace.app.Core.BK_LIMIT;
import static tw.trace.app.Core.BK_MAX;
import static tw.trace.app.Core.DOC_LIMIT;
import static tw.trace.app.Core.projects;
import static tw.trace.app.Core.s;
import static tw.trace.app.Json.obj;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Keeps this phone and the cloud in step, with the same rules as the web version: local copy first,
 * three-way merge on every write, newer data versions are never overwritten.
 * Changes from other devices arrive by reading again every few seconds while the app is open.
 */
final class Sync implements Cloud.Session, Painter.Info {
    /** What the engine asks of the screen. */
    interface Ui {
        void applyTheme();
        void afterRemote();
        void reRenderBackups();
        void enter(String joinCode);
        void showLoginScreen();
    }

    static final class User { String uid, email, name; }
    static final class Meta { List<Object> members = new ArrayList<>(); String owner; Map<String, Object> memberInfo = new LinkedHashMap<>(); }

    private static final long POLL_MS = 8000;

    private final Context ctx;
    private final Board b;
    private final Ui ui;
    private final SharedPreferences sp;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final ExecutorService pushNet = Executors.newSingleThreadExecutor();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    final Cloud cloud;

    User curUser;
    boolean dirty = false, demo = false;
    final Map<String, Meta> meta = new HashMap<>();
    String[] invite;   // {pid, code}
    String afterJoin;
    String pendingJoinCode;

    private Map<String, String> base = new HashMap<>();
    private final Set<String> blocked = new HashSet<>();
    private String prefsSig = "", placeholder = null, loginNote = "";
    private boolean pushing = false, again = false, polling = false, entered = false, hadCache = false, resumed = true, loginBusy = false, compacted = false;
    private int seenSchema = 0;
    private long epoch = 0;
    private long bkAt = 0;
    private int bkSlot = -1;
    private String bkHash = "";

    Sync(Context c, Board b, Ui ui) {
        ctx = c;
        this.b = b;
        this.ui = ui;
        sp = c.getSharedPreferences("trace", Context.MODE_PRIVATE);
        cloud = new Cloud(this);
    }

    /* ---------- session ---------- */
    @Override public String refreshToken() { return sp.getString("rt", ""); }
    @Override public String idToken() { return sp.getString("id", ""); }
    @Override public long idExpiry() { return sp.getLong("exp", 0); }
    @Override public void saveToken(String id, long exp, String rt) { sp.edit().putString("id", id).putLong("exp", exp).putString("rt", rt).apply(); }

    /* ---------- facts for the screens ---------- */
    Meta metaOf(String id) { return meta.get(id); }

    String nameOfMember(Meta m, String id) {
        Object i = m.memberInfo.get(id);
        Map<String, Object> o = i instanceof Map ? obj(i) : new HashMap<String, Object>();
        String n = s(o, "name"), e = s(o, "email");
        return n != null && !n.isEmpty() ? n : e != null && !e.isEmpty() ? e : "另一位成員";
    }

    @Override public String others(String id) {
        Meta m = metaOf(id);
        if (m == null || m.members.size() < 2 || curUser == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Object x : m.members) {
            if (curUser.uid.equals(x)) continue;
            if (sb.length() > 0) sb.append("、");
            sb.append(nameOfMember(m, String.valueOf(x)));
        }
        return sb.toString();
    }

    @Override public boolean ownedByOther(String id) {
        Meta m = metaOf(id);
        return curUser != null && m != null && !curUser.uid.equals(m.owner);
    }

    @Override public int memberCount(String id) { Meta m = metaOf(id); return m == null ? 0 : m.members.size(); }
    @Override public boolean signedIn() { return curUser != null; }
    @Override public String loginNote() { return loginNote; }
    @Override public boolean loginBusy() { return loginBusy; }

    void setNote(String t, boolean busy) { loginNote = t; loginBusy = busy; b.need(); }

    /* ---------- local copy ---------- */
    private File file(String name) { return new File(ctx.getFilesDir(), name); }
    private String cacheName() { return "cache-" + (curUser == null ? "none" : curUser.uid) + ".json"; }

    private static String readFile(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        }
    }

    /** Written beside the real file first, so a crash mid-write cannot damage the previous copy. */
    private static void writeFile(File f, String text) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes("UTF-8"));
            out.getFD().sync();
        }
        if (!tmp.renameTo(f)) throw new IOException("rename failed");
    }

    private Runnable localT;

    private void writeLocal(boolean now) {
        if (localT != null) { main.removeCallbacks(localT); localT = null; }
        if (curUser == null) return;
        final File f = file(cacheName()), fb = file(cacheName() + ".base");
        final String a = Json.str(b.store), c = Json.str(new LinkedHashMap<String, Object>(base));
        Runnable job = new Runnable() {
            @Override public void run() {
                try { writeFile(f, a); writeFile(fb, c); } catch (IOException ignore) { }
            }
        };
        if (now) job.run();
        else io.execute(job);
    }

    private Runnable saveT;

    void scheduleSave() {
        if (curUser == null) return;
        dirty = true;
        if (localT == null) {
            localT = new Runnable() { @Override public void run() { localT = null; writeLocal(false); } };
            main.postDelayed(localT, 250);
        }
        if (saveT != null) main.removeCallbacks(saveT);
        saveT = new Runnable() { @Override public void run() { pushHosted(null); } };
        main.postDelayed(saveT, 700);
        b.syncText = "儲存中…";
        b.need();
    }

    /** Leaving the app must not lose the last edits. */
    void flushSave() {
        if (curUser == null) return;
        if (localT != null || dirty) writeLocal(true);
        if (dirty) pushHosted(null);
    }

    /* ---------- pushing ---------- */
    private static final class Job {
        String pid, name, mine, baseJson;
        Cloud.Saved saved;
        boolean failed, tooBig, denied;
    }

    private Runnable retryT;

    private Map<String, Object> memberOf(User u) { return Json.map("name", u.name == null ? "" : u.name, "email", u.email == null ? "" : u.email); }

    /** pushHosted(): send every changed project, merging with whatever is in the cloud. */
    void pushHosted(final Runnable done) {
        if (curUser == null) { if (done != null) done.run(); return; }
        if (pushing) {
            again = true;
            if (done != null) main.postDelayed(new Runnable() { @Override public void run() { pushHosted(done); } }, 200);
            return;
        }
        pushing = true;
        final long myEpoch = epoch;
        final User user = curUser;
        final List<Job> jobs = new ArrayList<>();
        String problem0 = "";
        for (Map<String, Object> p : projects(b.store)) {
            String pid = s(p, "id");
            if (blocked.contains(pid)) continue;
            String mine = Json.stable(Core.canon(p));
            if (mine.equals(base.get(pid))) continue;
            if (mine.length() > DOC_LIMIT) { problem0 = "「" + s(p, "name") + "」太大，無法同步，請拆成多個專案"; continue; }
            Job j = new Job();
            j.pid = pid; j.name = s(p, "name"); j.mine = mine; j.baseJson = base.get(pid);
            jobs.add(j);
        }
        final String prefs = Json.stable(Json.map("theme", b.store.get("theme") == null ? Json.map("preset", "auto") : b.store.get("theme"), "current", b.state.get("id")));
        final boolean prefsChanged = !prefs.equals(prefsSig);
        final String problemStart = problem0;
        pushNet.execute(new Runnable() {
            @Override public void run() {
                boolean anyFail = false;
                for (final Job j : jobs) {
                    try {
                        j.saved = cloud.mergeSave(j.pid, new Cloud.Builder() {
                            @Override public Cloud.Built build(Map<String, Object> remote) {
                                Cloud.Built o = new Cloud.Built();
                                if (remote != null && Core.n(remote, "schema") > APP_SCHEMA) { o.abort = true; return o; }
                                String at = Instant.now().toString();
                                if (remote == null) {
                                    if (j.baseJson != null) { o.nothing = true; return o; }   // the owner deleted it while I was editing
                                    o.create = true;
                                    Map<String, Object> d = new LinkedHashMap<>();
                                    d.put("json", j.mine);
                                    d.put("schema", (double) APP_SCHEMA);
                                    d.put("rev", Core.uid());
                                    d.put("at", at);
                                    d.put("owner", user.uid);
                                    d.put("members", Json.list(user.uid));
                                    d.put("memberInfo", Json.map(user.uid, memberOf(user)));
                                    o.data = d;
                                    return o;
                                }
                                Object mineO = Json.parse(j.mine);
                                String rj = s(remote, "json");
                                Object merged = j.baseJson != null && rj != null ? Core.merge3(obj(Json.parse(j.baseJson)), obj(mineO), obj(Json.parse(rj))) : mineO;
                                Map<String, Object> d = new LinkedHashMap<>();
                                d.put("json", Json.stable(merged));
                                d.put("schema", (double) APP_SCHEMA);
                                d.put("rev", Core.uid());
                                d.put("at", at);
                                o.data = d;
                                return o;
                            }
                        });
                    } catch (Cloud.ApiError e) {
                        if (e.denied()) j.denied = true;
                        else if (e.tooBig()) j.tooBig = true;
                        else { j.failed = true; anyFail = true; }
                    } catch (IOException | RuntimeException e) {
                        j.failed = true;
                        anyFail = true;
                    }
                }
                boolean prefsOk = true;
                if (!anyFail && prefsChanged) {
                    try { cloud.setMerge("users/" + user.uid + "/trace/prefs", obj(Json.parse(prefs))); }
                    catch (IOException | RuntimeException e) { prefsOk = false; anyFail = true; }
                }
                final boolean failed = anyFail, po = prefsOk;
                main.post(new Runnable() {
                    @Override public void run() { pushDone(jobs, failed, po, prefs, prefsChanged, problemStart, myEpoch, done); }
                });
            }
        });
    }

    private void pushDone(List<Job> jobs, boolean failed, boolean prefsOk, String prefs, boolean prefsChanged, String problem, long myEpoch, Runnable done) {
        pushing = false;
        if (myEpoch != epoch || curUser == null) { if (done != null) done.run(); return; }
        for (Job j : jobs) {
            if (j.denied) { problem = "沒有權限儲存這個專案，請確認你還是成員"; continue; }
            if (j.tooBig) { problem = "「" + j.name + "」太大，無法同步，請拆成多個專案"; continue; }
            if (j.failed || j.saved == null) continue;
            if (j.saved.abort) { blockProject(j.pid); continue; }
            if (j.saved.gone) {
                base.remove(j.pid);
                removeProject(j.pid);
                if (!projects(b.store).isEmpty()) ui.afterRemote();
                continue;
            }
            base.put(j.pid, j.saved.json);
            Map<String, Object> p = Core.project(b.store, j.pid);
            if (p != null && !j.saved.json.equals(j.mine) && Json.stable(Core.canon(p)).equals(j.mine)) {
                Core.applyProject(b.store, j.pid, j.saved.json);
                ui.afterRemote();
            }
        }
        if (prefsChanged && prefsOk && !failed) prefsSig = prefs;
        writeLocal(false);
        if (retryT != null) main.removeCallbacks(retryT);
        if (failed) {
            b.syncText = "離線中，連線後會自動同步";
            b.need();
            retryT = new Runnable() { @Override public void run() { pushHosted(null); } };
            main.postDelayed(retryT, 15000);
            if (done != null) done.run();
            return;
        }
        boolean left = false;
        for (Map<String, Object> p : projects(b.store)) {
            String pid = s(p, "id");
            String m = Json.stable(Core.canon(p));
            if (!blocked.contains(pid) && m.length() <= DOC_LIMIT && !m.equals(base.get(pid))) left = true;
        }
        if (again || left) {
            again = false;
            // a project refused by the rules would loop forever without a pause
            retryT = new Runnable() { @Override public void run() { pushHosted(null); } };
            main.postDelayed(retryT, problem.isEmpty() ? 250 : 15000);
            if (!problem.isEmpty()) { b.syncText = problem; b.need(); }
            if (done != null) done.run();
            return;
        }
        dirty = false;
        boolean anyBlocked = false;
        for (Map<String, Object> p : projects(b.store)) if (blocked.contains(s(p, "id"))) anyBlocked = true;
        b.syncText = anyBlocked ? "請更新 App 到最新版本" : !problem.isEmpty() ? problem : "已同步到 Google 帳戶";
        b.need();
        if (done != null) done.run();
    }

    private void removeProject(String pid) {
        List<Map<String, Object>> ps = projects(b.store);
        for (int i = ps.size() - 1; i >= 0; i--) if (pid.equals(s(ps.get(i), "id"))) ps.remove(i);
    }

    /** A newer app version wrote this project; this copy must not overwrite it. */
    private void blockProject(String id) {
        if (!blocked.add(id)) return;
        b.toast("有專案已用新版本編輯，請更新 App 後再繼續", null, null);
        b.syncText = "請更新 App 到最新版本";
    }

    /* ---------- signing in ---------- */
    /** Starts where the last session left off, or at the login screen. */
    void start() {
        String uid = sp.getString("uid", "");
        if (uid.isEmpty() || refreshToken().isEmpty()) {
            setNote("登入後，專案會跟著你的 Google 帳戶同步。", false);
            ui.showLoginScreen();
            return;
        }
        User u = new User();
        u.uid = uid;
        u.email = sp.getString("email", "");
        u.name = sp.getString("name", "");
        ui.showLoginScreen();
        loadUser(u);
    }

    void signedIn(Cloud.Account a) {
        sp.edit().putString("uid", a.uid).putString("email", a.email).putString("name", a.name)
                .putString("rt", a.refreshToken).putString("id", a.idToken).putLong("exp", a.expiry).apply();
        User u = new User();
        u.uid = a.uid;
        u.email = a.email;
        u.name = a.name;
        loadUser(u);
    }

    private void loadUser(final User user) {
        epoch++;
        final long myEpoch = epoch;
        curUser = user;
        setNote("正在載入你的專案…", true);
        Map<String, Object> cached = null;
        base = new HashMap<>();
        meta.clear();
        blocked.clear();
        prefsSig = "";
        placeholder = null;
        invite = null;
        entered = false;
        compacted = false;
        b.warpStack.clear();
        b.HIST.clear();
        try {
            File f = file(cacheName());
            if (f.exists()) {
                Object o = Json.parse(readFile(f));
                if (o instanceof Map && obj(o).get("projects") instanceof List) {
                    cached = obj(o);
                    File fb = file(cacheName() + ".base");
                    if (fb.exists()) for (Map.Entry<String, Object> e : obj(Json.parse(readFile(fb))).entrySet()) base.put(e.getKey(), String.valueOf(e.getValue()));
                    for (Map<String, Object> p : projects(cached)) Core.cleanProject(p);
                    if (projects(cached).isEmpty()) { cached = null; base = new HashMap<>(); }
                }
            }
        } catch (IOException | RuntimeException e) {
            cached = null;
            base = new HashMap<>();
        }
        hadCache = cached != null;
        if (cached != null) b.store = cached;
        else {
            b.store = Core.freshStore();
            placeholder = s(projects(b.store).get(0), "id");
        }
        Map<String, Object> st = Core.project(b.store, s(b.store, "current"));
        b.state = st != null ? st : projects(b.store).get(0);
        b.clearBoard();
        b.ref = b.refDay();
        ui.applyTheme();
        b.refresh();
        net.execute(new Runnable() {
            @Override public void run() {
                Map<String, Object> prefs = null;
                boolean failed = false;
                String oldJson = null;
                try {
                    Cloud.Doc d = cloud.get("users/" + user.uid + "/trace/prefs");
                    prefs = d == null ? null : d.data;
                    if (prefs == null || !Core.b(prefs, "movedToProjects")) {
                        Cloud.Doc old = cloud.get("users/" + user.uid + "/trace/board");
                        if (old != null && old.data.get("json") instanceof String) {
                            oldJson = (String) old.data.get("json");
                            Map<String, Object> os = Core.migrate(obj(Json.parse(oldJson)), Core.today());
                            for (Map<String, Object> p : projects(os)) {
                                Map<String, Object> dt = new LinkedHashMap<>();
                                dt.put("json", Json.stable(Core.canon(Core.cleanProject(p))));
                                dt.put("schema", (double) APP_SCHEMA);
                                dt.put("rev", Core.uid());
                                dt.put("at", Instant.now().toString());
                                dt.put("owner", user.uid);
                                dt.put("members", Json.list(user.uid));
                                dt.put("memberInfo", Json.map(user.uid, memberOf(user)));
                                cloud.set("projects/" + s(p, "id"), dt);
                            }
                            if (os.get("theme") != null) {
                                final Object th = os.get("theme");
                                main.post(new Runnable() { @Override public void run() { if (myEpoch == epoch) { b.store.put("theme", th); ui.applyTheme(); } } });
                            }
                        }
                        cloud.setMerge("users/" + user.uid + "/trace/prefs", Json.map("movedToProjects", true));
                    }
                } catch (IOException | RuntimeException e) {
                    failed = true;
                    if (e instanceof Cloud.ApiError && ((Cloud.ApiError) e).signedOut()) { sessionLost(myEpoch); return; }
                }
                final Map<String, Object> pr = prefs;
                final boolean fail = failed;
                main.post(new Runnable() {
                    @Override public void run() {
                        if (myEpoch != epoch) return;
                        if (fail && !hadCache) { setNote("無法讀取你的資料。請確認已連上網路，再試一次。", true); main.postDelayed(new Runnable() { @Override public void run() { if (myEpoch == epoch) loadUser(user); } }, 5000); return; }
                        if (pr != null && pr.get("theme") instanceof Map) {
                            b.store.put("theme", pr.get("theme"));
                            ui.applyTheme();
                            prefsSig = Json.stable(Json.map("theme", pr.get("theme"), "current", pr.get("current") == null ? "" : pr.get("current")));
                        }
                        if (pr != null && Core.b(pr, "current")) b.store.put("current", pr.get("current"));
                        seenSchema = pr == null ? 0 : (int) Core.n(pr, "appSchema");
                        if (pr != null && pr.get("backupAt") != null) {
                            bkAt = (long) Core.n(pr, "backupAt");
                            bkSlot = pr.get("backupSlot") == null ? -1 : (int) Core.n(pr, "backupSlot");
                            bkHash = pr.get("backupHash") == null ? "" : s(pr, "backupHash");
                        }
                        startPolling(myEpoch);
                    }
                });
            }
        });
    }

    private void sessionLost(final long myEpoch) {
        main.post(new Runnable() {
            @Override public void run() {
                if (myEpoch != epoch) return;
                signOutNow();
                setNote("登入已過期，請重新登入。", false);
            }
        });
    }

    private void enter() {
        if (entered) return;
        entered = true;
        String code = pendingJoinCode;
        pendingJoinCode = null;
        ui.enter(code);
        b.syncText = "已同步到 Google 帳戶";
    }

    /* ---------- reading ---------- */
    private void startPolling(final long myEpoch) {
        polling = true;
        pollOnce(myEpoch);
    }

    private Runnable pollT;

    private void pollOnce(final long myEpoch) {
        if (pollT != null) { main.removeCallbacks(pollT); pollT = null; }
        if (myEpoch != epoch || curUser == null) return;
        final String uid = curUser.uid;
        net.execute(new Runnable() {
            @Override public void run() {
                List<Cloud.Doc> docs = null;
                String err = null;
                try { docs = cloud.listProjects(uid); }
                catch (Cloud.ApiError e) {
                    if (e.signedOut()) { sessionLost(myEpoch); return; }
                    err = e.denied() ? "denied" : "net";
                } catch (IOException | RuntimeException e) { err = "net"; }
                final List<Cloud.Doc> d = docs;
                final String er = err;
                main.post(new Runnable() {
                    @Override public void run() {
                        if (myEpoch != epoch) return;
                        if (d != null) onDocs(d);
                        else if (hadCache) {
                            enter();
                            b.syncText = "離線中，連線後會自動同步";
                            b.need();
                        } else if ("denied".equals(er)) setNote("無法讀取你的資料。請確認 Firestore 規則已更新，再重新登入。", false);
                        else setNote("需要網路才能第一次載入專案，連上後會自動繼續。", true);
                        schedulePoll(myEpoch, d == null && !entered ? 4000 : POLL_MS);
                    }
                });
            }
        });
    }

    private void schedulePoll(final long myEpoch, long ms) {
        if (!resumed) return;
        pollT = new Runnable() { @Override public void run() { pollOnce(myEpoch); } };
        main.postDelayed(pollT, ms);
    }

    void onResume() {
        resumed = true;
        if (curUser != null && polling) pollOnce(epoch);
    }

    void onPause() {
        resumed = false;
        if (pollT != null) { main.removeCallbacks(pollT); pollT = null; }
    }

    /** One reading of the cloud; the same steps as the web's snapshot listener. */
    private void onDocs(List<Cloud.Doc> docs) {
        boolean changed = false;
        Set<String> ids = new HashSet<>();
        for (Cloud.Doc d : docs) {
            ids.add(d.id);
            Meta m = new Meta();
            if (d.data.get("members") instanceof List) m.members = Json.arr(d.data.get("members"));
            m.owner = s(d.data, "owner");
            if (d.data.get("memberInfo") instanceof Map) m.memberInfo = obj(d.data.get("memberInfo"));
            Meta prev = meta.get(d.id);
            String before = prev == null ? "null" : metaSig(prev);
            meta.put(d.id, m);
            if (!before.equals(metaSig(m))) changed = true;
            if (Core.n(d.data, "schema") > APP_SCHEMA) { blockProject(d.id); continue; }
            String json = s(d.data, "json");
            if (json == null || json.equals(base.get(d.id))) continue;
            Map<String, Object> p = Core.project(b.store, d.id);
            if (p != null && base.containsKey(d.id) && !Json.stable(Core.canon(p)).equals(base.get(d.id))) { scheduleSave(); continue; }   // local edits pending: the push merges this
            // one damaged project must not stop the others from loading
            try {
                Core.applyProject(b.store, d.id, json);
                base.put(d.id, json);
                changed = true;
            } catch (RuntimeException e) {
                b.syncText = "無法讀取專案 " + d.id + "，已略過";
            }
        }
        if (placeholder != null) {
            Map<String, Object> ph = Core.project(b.store, placeholder);
            // drop the blank starter project only if the account already has real projects and it was never edited
            if (ids.contains(placeholder) || ph == null || !Core.nodes(ph).isEmpty()) placeholder = null;
            else if (!docs.isEmpty()) {
                removeProject(placeholder);
                if (placeholder.equals(s(b.state, "id"))) {
                    Map<String, Object> c = Core.project(b.store, s(b.store, "current"));
                    b.state = c != null ? c : projects(b.store).get(0);
                    b.clearBoard();
                    b.ref = b.refDay();
                }
                placeholder = null;
                changed = true;
            }
        }
        for (Map<String, Object> p : new ArrayList<>(projects(b.store))) {
            String pid = s(p, "id");
            if (!ids.contains(pid) && base.containsKey(pid)) {
                base.remove(pid);
                meta.remove(pid);
                removeProject(pid);
                changed = true;
            }
        }
        if (projects(b.store).isEmpty()) {
            Map<String, Object> f = Core.newProject("我的第一個專案");
            Json.arr(b.store.get("projects")).add(f);
            placeholder = null;
            changed = true;
        }
        if (changed) ui.afterRemote();
        openJoined();
        enter();
        // keep a copy whenever a new app version meets existing data; send anything still waiting from last time
        if (seenSchema != APP_SCHEMA && !docs.isEmpty()) {
            backupNow("更新版本前", true, null);
            final String uid = curUser.uid;
            net.execute(new Runnable() { @Override public void run() { try { cloud.setMerge("users/" + uid + "/trace/prefs", Json.map("appSchema", (double) APP_SCHEMA)); } catch (IOException | RuntimeException ignore) { } } });
        }
        seenSchema = APP_SCHEMA;
        for (Map<String, Object> p : projects(b.store)) {
            String pid = s(p, "id");
            boolean ph = pid.equals(placeholder) && Core.nodes(p).isEmpty();
            if (!blocked.contains(pid) && !ph && !Json.stable(Core.canon(p)).equals(base.get(pid))) {
                dirty = true;
                if (!pushing) pushHosted(null);
                break;
            }
        }
        compactBackups();
        autoBackup();
    }

    private String metaSig(Meta m) { return Json.stable(Json.map("members", m.members, "owner", m.owner, "memberInfo", m.memberInfo)); }

    void openJoined() {
        if (afterJoin != null && Core.project(b.store, afterJoin) != null) {
            String id = afterJoin;
            afterJoin = null;
            b.warpStack.clear();
            b.showBoard(id);
            b.toast("已加入「" + s(Core.project(b.store, id), "name") + "」", null, null);
        }
    }

    /* ---------- signing out ---------- */
    void logout() {
        if (curUser == null) { ui.showLoginScreen(); return; }
        if (dirty) pushHosted(new Runnable() { @Override public void run() { finishLogout(); } });
        else finishLogout();
    }

    private void finishLogout() {
        if (!dirty && curUser != null) {
            // nothing is waiting to be sent, so the local copy can go
            file(cacheName()).delete();
            file(cacheName() + ".base").delete();
        }
        signOutNow();
        setNote("登入後，專案會跟著你的 Google 帳戶同步。", false);
    }

    private void signOutNow() {
        if (dirty) writeLocal(true);
        epoch++;
        curUser = null;
        polling = false;
        sp.edit().remove("uid").remove("email").remove("name").remove("rt").remove("id").remove("exp").apply();
        base = new HashMap<>();
        meta.clear();
        blocked.clear();
        invite = null;
        dirty = false;
        pushing = false;
        again = false;
        if (retryT != null) main.removeCallbacks(retryT);
        if (saveT != null) main.removeCallbacks(saveT);
        if (localT != null) { main.removeCallbacks(localT); localT = null; }
        if (pollT != null) main.removeCallbacks(pollT);
        b.warpStack.clear();
        b.HIST.clear();
        bkAt = 0;
        bkSlot = -1;
        bkHash = "";
        b.store = Core.freshStore();
        b.clearBoard();
        b.state = projects(b.store).get(0);
        b.ref = b.refDay();
        b.refresh();
        ui.applyTheme();
        ui.showLoginScreen();
    }

    /* ---------- backups ---------- */
    interface Done { void done(boolean ok); }

    static String gzip(String t) {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (GZIPOutputStream g = new GZIPOutputStream(bo)) { g.write(t.getBytes("UTF-8")); }
            return Base64.getEncoder().encodeToString(bo.toByteArray());
        } catch (IOException e) {
            return null;
        }
    }

    static String gunzip(String b64) throws IOException {
        byte[] raw = Base64.getDecoder().decode(b64);
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(raw))) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        }
    }

    static String backupText(Map<String, Object> rec) throws IOException {
        Object gz = rec.get("gz");
        return gz != null && !"".equals(gz) ? gunzip(String.valueOf(gz)) : String.valueOf(rec.get("json"));
    }

    private static Map<String, Object> pack(String at, String reason, String text) {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("at", at);
        rec.put("reason", reason);
        String gz = gzip(text);
        if (gz != null && gz.length() < text.length()) rec.put("gz", gz);
        else rec.put("json", text);
        return rec;
    }

    void backupNow(final String reason, boolean force, final Done cb) {
        if (curUser == null) { if (cb != null) cb.done(false); return; }
        String now = Instant.now().toString();
        Map<String, Object> data = Core.payload(b.store, projects(b.store), now);
        final String hash = Core.hash36(Json.str(data.get("projects")));
        if (!force && hash.equals(bkHash)) { if (cb != null) cb.done(false); return; }
        final long prevAt = bkAt;
        final int prevSlot = bkSlot;
        final String prevHash = bkHash;
        bkAt = System.currentTimeMillis();
        bkHash = hash;
        bkSlot = (bkSlot + 1) % BK_MAX;
        final int slot = bkSlot;
        final long at = bkAt;
        final String text = Json.str(data), uid = curUser.uid, atIso = now;
        net.execute(new Runnable() {
            @Override public void run() {
                boolean ok;
                boolean tooBig = false;
                try {
                    Map<String, Object> rec = pack(atIso, reason, text);
                    Object body = rec.containsKey("gz") ? rec.get("gz") : rec.get("json");
                    if (String.valueOf(body).length() > BK_LIMIT) { ok = false; tooBig = true; }
                    else {
                        cloud.set("users/" + uid + "/trace/backup-" + slot, rec);
                        cloud.setMerge("users/" + uid + "/trace/prefs", Json.map("backupAt", (double) at, "backupSlot", (double) slot, "backupHash", hash));
                        ok = true;
                    }
                } catch (IOException | RuntimeException e) {
                    ok = false;
                }
                final boolean fin = ok, big = tooBig;
                main.post(new Runnable() {
                    @Override public void run() {
                        if (!fin) {
                            bkAt = prevAt;
                            bkSlot = prevSlot;
                            bkHash = prevHash;
                            if (big) b.toast("資料太大，無法自動備份，請改用匯出", null, null);
                        }
                        if (cb != null) cb.done(fin);
                    }
                });
            }
        });
    }

    void autoBackup() {
        if (curUser != null && System.currentTimeMillis() - bkAt >= BK_EVERY) backupNow("自動備份", false, null);
    }

    interface BackupList { void list(List<Map<String, Object>> items, boolean failed); }

    void listBackups(final BackupList cb) {
        if (curUser == null) { cb.list(new ArrayList<Map<String, Object>>(), false); return; }
        final String uid = curUser.uid;
        net.execute(new Runnable() {
            @Override public void run() {
                final List<Map<String, Object>> out = new ArrayList<>();
                boolean failed = false;
                try {
                    for (int i = 0; i < BK_MAX; i++) {
                        Cloud.Doc d = cloud.get("users/" + uid + "/trace/backup-" + i);
                        if (d != null) { d.data.put("slot", (double) i); out.add(d.data); }
                    }
                    out.sort((x, y) -> String.valueOf(y.get("at")).compareTo(String.valueOf(x.get("at"))));
                } catch (IOException | RuntimeException e) { failed = true; }
                final boolean f = failed;
                main.post(new Runnable() { @Override public void run() { cb.list(out, f); } });
            }
        });
    }

    /** Shrinks backups written before compression existed (once per session). */
    private void compactBackups() {
        if (compacted || curUser == null) return;
        compacted = true;
        final String uid = curUser.uid;
        net.execute(new Runnable() {
            @Override public void run() {
                try {
                    for (int i = 0; i < BK_MAX; i++) {
                        Cloud.Doc d = cloud.get("users/" + uid + "/trace/backup-" + i);
                        if (d == null || !(d.data.get("json") instanceof String)) continue;
                        String text = (String) d.data.get("json");
                        String gz = gzip(text);
                        if (gz == null || gz.length() >= text.length()) continue;
                        d.data.remove("json");
                        d.data.put("gz", gz);
                        cloud.set("users/" + uid + "/trace/backup-" + i, d.data);
                    }
                } catch (IOException | RuntimeException e) { compacted = false; }
            }
        });
    }

    /* ---------- runs on the network thread, reports back on the main thread ---------- */
    interface Task<T> { T run() throws Exception; }
    interface Result<T> { void ok(T v); void fail(Exception e); }

    <T> void background(final Task<T> t, final Result<T> r) {
        net.execute(new Runnable() {
            @Override public void run() {
                try {
                    final T v = t.run();
                    main.post(new Runnable() { @Override public void run() { r.ok(v); } });
                } catch (final Exception e) {
                    main.post(new Runnable() { @Override public void run() { r.fail(e); } });
                }
            }
        });
    }

    void forgetProject(String id) {
        base.remove(id);
        meta.remove(id);
    }

    Map<String, Object> memberInfoOfMe() { return memberOf(curUser); }
}
