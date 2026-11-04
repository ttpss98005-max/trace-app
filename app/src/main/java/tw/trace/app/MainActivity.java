package tw.trace.app;

import static tw.trace.app.Core.projects;
import static tw.trace.app.Core.s;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Ties the board, the form panel and the sync engine together. Follows rotation without restarting. */
public final class MainActivity extends Activity implements Board.Host, Panel.Host, Sync.Ui {
    private static final int REQ_SAVE = 11, REQ_OPEN = 12;

    private Board board;
    private Theme theme;
    private Sync sync;
    private Panel panel;
    private Stage stage;
    private Painter painter;
    private FrameLayout root;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Painter.Fonts fonts;
    private String pendingSave;
    private boolean demo = false, signingIn = false;
    private int lastW, lastH, lastTop, lastG;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        fonts = new Painter.Fonts();
        fonts.cjk = Typeface.create("sans-serif", Typeface.NORMAL);
        fonts.cjkMed = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        fonts.cjkBold = Typeface.create("sans-serif", Typeface.BOLD);
        fonts.mono = Typeface.createFromAsset(getAssets(), "fonts/IBMPlexMono-Regular.ttf");
        fonts.monoMed = Typeface.createFromAsset(getAssets(), "fonts/IBMPlexMono-Medium.ttf");
        fonts.disp = Typeface.createFromAsset(getAssets(), "fonts/BarlowCondensed-Medium.ttf");
        fonts.dispSemi = Typeface.createFromAsset(getAssets(), "fonts/BarlowCondensed-SemiBold.ttf");

        theme = new Theme();
        board = new Board(this);
        board.reduce = Settings.Global.getFloat(getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        board.store = Core.freshStore();
        board.state = projects(board.store).get(0);
        board.ref = board.refDay();
        Configuration cf = getResources().getConfiguration();
        board.VW = cf.screenWidthDp;
        board.VH = cf.screenHeightDp;
        board.screenH = cf.screenHeightDp;
        sync = new Sync(this, board, this);
        board.layout();
        panel = new Panel(this, this);
        stage = new Stage(this, board);
        painter = new Painter(board, theme, fonts);
        painter.info = sync;
        stage.setPainter(painter);
        root = new FrameLayout(this);
        root.addView(stage, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(panel.frame, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        // draw beside the camera cutout in landscape instead of leaving a black strip there
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets in) {
                    android.view.DisplayCutout dc = in.getDisplayCutout();
                    v.setPadding(dc == null ? 0 : dc.getSafeInsetLeft(), 0, dc == null ? 0 : dc.getSafeInsetRight(), 0);
                    return in;
                }
            });
        }
        // the keyboard and rotation change the free space: move the form along
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int bm, int ol, int ot, int or, int ob) {
                if (panel.open()) root.post(new Runnable() { @Override public void run() { layoutPanel(); } });
            }
        });
        applyTheme();

        demo = getIntent() != null && getIntent().getBooleanExtra("demo", false);
        if (demo) startDemo();
        else {
            readJoinLink(getIntent());
            sync.start();
        }
        // an app left open past midnight still shows the right dates
        main.postDelayed(new Runnable() {
            @Override public void run() {
                int d = Core.today();
                if (d != board.today) { board.today = d; board.refresh(); }
                sync.autoBackup();
                main.postDelayed(this, 60000);
            }
        }, 60000);
    }

    /** Opens with example data and no account; only reachable from developer tools, for automated screenshots. */
    private void startDemo() {
        board.store = Core.sampleStore(board.today);
        board.state = projects(board.store).get(0);
        board.store.put("current", board.state.get("id"));
        board.ref = board.refDay();
        board.syncText = "試用模式";
        board.refresh();
        board.initialView();
        Intent i = getIntent();
        String sc = i.getStringExtra("screen");
        if ("hub".equals(sc)) board.showHub();
        else if ("login".equals(sc)) { board.loginOpen = true; sync.setNote("登入後，專案會跟著你的 Google 帳戶同步。", false); }
        else if ("panel".equals(sc)) stage.post(new Runnable() { @Override public void run() { openEdit(s(Core.nodes(board.state).get(3), "id"), true); } });
        else if ("settings".equals(sc)) stage.post(new Runnable() { @Override public void run() { openSettings(null); } });
    }

    @Override protected void onStart() {
        super.onStart();
        stage.setRunning(true);
    }

    @Override protected void onResume() {
        super.onResume();
        sync.onResume();
    }

    @Override protected void onPause() {
        super.onPause();
        sync.flushSave();
        sync.onPause();
    }

    @Override protected void onStop() {
        super.onStop();
        stage.setRunning(false);
    }

    @Override public void onConfigurationChanged(Configuration cf) {
        super.onConfigurationChanged(cf);
        board.screenH = cf.screenHeightDp;
        applyTheme();
        layoutPanel();
        board.need();
    }

    @Override public void onBackPressed() {
        if (board.finaleOpen) { board.finaleOpen = false; board.need(); return; }
        if (panel.open()) { panel.close(); return; }
        if (board.loginOpen) { super.onBackPressed(); return; }
        if (!board.hubOpen) {
            if (!board.warpStack.isEmpty()) board.back();
            else board.showHub();
            return;
        }
        super.onBackPressed();
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        readJoinLink(i);
        if (sync.pendingJoinCode != null && sync.curUser != null && !board.loginOpen) {
            String c = sync.pendingJoinCode;
            sync.pendingJoinCode = null;
            openJoinCode(c);
        }
    }

    /** Invitation links from the website open here, with the code filled in. */
    private void readJoinLink(Intent i) {
        Uri u = i == null ? null : i.getData();
        String f = u == null ? null : u.getFragment();
        if (f != null && f.startsWith("join-")) sync.pendingJoinCode = f.substring(5);
    }

    private void openJoinCode(String raw) {
        String c = raw.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        board.showHub();
        panel.openJoin(c.length() >= 5 ? Core.prettyCode(c) : c);
        board.toast("確認後按「加入專案」", null, null);
    }

    /* ---------- Board.Host ---------- */
    @Override public void openEdit(String id, boolean recenter) { panel.openEdit(id, recenter); }
    @Override public void openNew(String lane, String date, String from) { panel.openNew(lane, date, from); }
    @Override public void openSettings(String focusLane) { panel.openSettings(focusLane); }
    @Override public void closePanel() { panel.close(); }
    @Override public boolean panelOpen() { return panel != null && panel.open(); }
    @Override public String panelMode() { return panel == null ? null : panel.mode; }
    @Override public void redrawPanel() { panel.redraw(); }
    @Override public boolean typingInPanel() { return panel != null && panel.typing(); }
    @Override public void scheduleSave() { sync.scheduleSave(); }
    @Override public void later(long ms, Runnable r) { main.postDelayed(r, ms); }
    @Override public void redraw() { if (stage != null) stage.postInvalidateOnAnimation(); }
    @Override public void afterRefresh() { }

    @Override public void hubAction(String key, String arg) {
        switch (key) {
            case "hubBackup": panel.openBackups(); break;
            case "hubJoin": panel.openJoin(null); break;
            case "hubOut": logout(); break;
            case "hubNew": {
                Map<String, Object> p = Core.newProject("新專案");
                Json.arr(board.store.get("projects")).add(p);
                sync.scheduleSave();
                board.warpStack.clear();
                board.showBoard(s(p, "id"));
                panel.openSettings(null);
                break;
            }
            case "popen":
                board.warpStack.clear();
                board.showBoard(arg);
                break;
            case "pdel": deleteProject(arg); break;
            default: break;
        }
    }

    private void deleteProject(final String id) {
        if (!board.delArm.containsKey(id)) { board.delArm.put(id, 1); board.need(); return; }
        board.delArm.remove(id);
        sync.backupNow("刪除專案前", true, null);
        final Sync.Meta m = sync.curUser != null ? sync.metaOf(id) : null;
        final String uid = sync.curUser != null ? sync.curUser.uid : null;
        if (m != null && !uid.equals(m.owner)) {
            sync.background(new Sync.Task<Boolean>() {
                @Override public Boolean run() throws Exception { sync.cloud.removeMember(id, uid); return true; }
            }, new Sync.Result<Boolean>() {
                @Override public void ok(Boolean v) { sync.onResume(); }
                @Override public void fail(Exception e) { board.toast("無法退出，請確認網路連線", null, null); }
            });
            return;
        }
        final boolean shared = m != null && m.members.size() > 1;
        if (m != null) sync.background(new Sync.Task<Boolean>() {
            @Override public Boolean run() throws Exception { sync.cloud.delete("projects/" + id); return true; }
        }, new Sync.Result<Boolean>() {
            @Override public void ok(Boolean v) { }
            @Override public void fail(Exception e) { board.toast("無法刪除雲端專案，請確認網路連線", null, null); }
        });
        sync.forgetProject(id);
        final Map<String, Object> removed = Core.project(board.store, id);
        final String name = s(removed, "name");
        Json.arr(board.store.get("projects")).remove(removed);
        board.warpStack.remove(id);
        board.HIST.remove(id);
        if (id.equals(s(board.state, "id"))) {
            board.clearBoard();
            board.state = projects(board.store).get(0);
            board.store.put("current", board.state.get("id"));
            board.ref = board.refDay();
            board.refresh();
        }
        sync.scheduleSave();
        // restoring a shared project would silently drop its members, so that case goes through 備份與還原
        if (shared) board.toast("已刪除專案「" + name + "」，可從「備份與還原」救回", null, null);
        else board.toast("已刪除專案「" + name + "」", "復原", new Runnable() {
            @Override public void run() {
                Json.arr(board.store.get("projects")).add(removed);
                sync.scheduleSave();
                board.need();
            }
        });
    }

    @Override public void loginTap() {
        if (signingIn) return;
        if (demo) { board.loginOpen = false; board.need(); return; }
        signingIn = true;
        sync.setNote("正在開啟 Google 登入…", true);
        final Activity self = this;
        sync.background(new Sync.Task<String>() {
            @Override public String run() throws Exception { return Cloud.googleClientId(); }
        }, new Sync.Result<String>() {
            @Override public void ok(String clientId) {
                GoogleSignIn.start(self, clientId, new GoogleSignIn.Done() {
                    @Override public void ok(final String token) {
                        sync.setNote("正在登入…", true);
                        sync.background(new Sync.Task<Cloud.Account>() {
                            @Override public Cloud.Account run() throws Exception { return Cloud.signInWithGoogle(token); }
                        }, new Sync.Result<Cloud.Account>() {
                            @Override public void ok(Cloud.Account a) { signingIn = false; sync.signedIn(a); }
                            @Override public void fail(Exception e) {
                                signingIn = false;
                                sync.setNote(e instanceof Cloud.ApiError ? "登入失敗（" + e.getMessage() + "），請再試一次。" : "連不上 Google，請檢查網路後再試一次。", false);
                            }
                        });
                    }
                    @Override public void fail(String message, boolean canceled) {
                        signingIn = false;
                        sync.setNote(message, false);
                    }
                });
            }
            @Override public void fail(Exception e) {
                signingIn = false;
                sync.setNote(e instanceof Cloud.ApiError ? "Firebase 還沒啟用 Google 登入，請到 Authentication 開啟。" : "連不上 Google，請檢查網路後再試一次。", false);
            }
        });
    }

    /* ---------- Sync.Ui ---------- */
    @Override public void applyTheme() {
        Object t = board.store.get("theme");
        boolean sys = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        theme.apply(t instanceof Map ? Json.obj(t) : null, sys);
        applyBars();
        if (panel != null && panel.open()) { panel.frame.setBackgroundColor(theme.card); }
        board.need();
    }

    @SuppressWarnings("deprecation")
    private void applyBars() {
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.setStatusBarColor(theme.bg);
        w.setNavigationBarColor(theme.bg);
        w.getDecorView().setBackgroundColor(theme.bg);
        if (root != null) root.setBackgroundColor(theme.bg);
        View dv = w.getDecorView();
        int f = dv.getSystemUiVisibility();
        if (!theme.dark) f |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        else f &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        dv.setSystemUiVisibility(f);
    }

    @Override public void afterRemote() {
        board.histTrack();
        if (Core.project(board.store, s(board.state, "id")) == null) {
            String was = s(board.state, "name");
            board.state = projects(board.store).get(0);
            if (panel.open()) panel.close();
            board.clearBoard();
            board.ref = board.refDay();
            board.toast("你已無法存取「" + was + "」", null, null);
        }
        board.refresh();
        String mode = panel.mode;
        if ("node".equals(mode) && board.selId != null) {
            if (Core.nodeOf(board.state, board.selId) != null) { if (!panel.typing()) panel.openEdit(board.selId, false); }
            else panel.close();
        } else if ("set".equals(mode) && !panel.typing()) panel.redraw();
    }

    @Override public void reRenderBackups() { panel.renderBackups(); }

    @Override public void showLoginScreen() {
        if (panel.open()) panel.close();
        board.hubOpen = false;
        board.loginOpen = true;
        board.need();
    }

    @Override public void enter(String joinCode) {
        board.loginOpen = false;
        if (joinCode != null) openJoinCode(joinCode);
        else board.showBoard(s(board.state, "id"));
    }

    /* ---------- Panel.Host ---------- */
    @Override public Board board() { return board; }
    @Override public Sync sync() { return sync; }
    @Override public Theme theme() { return theme; }
    @Override public Typeface mono() { return fonts.mono; }
    @Override public Typeface display() { return fonts.dispSemi; }

    @Override public void applyThemeNow() { applyTheme(); }
    @Override public void logout() {
        if (demo) { board.showHub(); return; }
        sync.logout();
    }

    /** Places the form: right side in landscape or on a tablet, bottom sheet on a portrait phone. */
    @Override public void layoutPanel() {
        if (panel == null) return;
        float d = getResources().getDisplayMetrics().density;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) panel.frame.getLayoutParams();
        boolean side = board.panelSide();
        double hudTop = board.hubOpen ? 0 : board.rulerY;
        if (side) {
            lp.width = (int) (board.panelW() * d);
            lp.height = FrameLayout.LayoutParams.MATCH_PARENT;
            lp.gravity = android.view.Gravity.END | android.view.Gravity.TOP;
            lp.topMargin = (int) (hudTop * d);
        } else {
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT;
            // never taller than what the keyboard leaves free
            lp.height = (int) (Math.max(160, Math.min(board.screenH * .62, board.VH - hudTop - 8)) * d);
            lp.gravity = android.view.Gravity.BOTTOM;
            lp.topMargin = 0;
        }
        if (panel.frame.getLayoutParams() == lp && lp.width == lastW && lp.height == lastH && lp.topMargin == lastTop && lp.gravity == lastG) return;
        lastW = lp.width;
        lastH = lp.height;
        lastTop = lp.topMargin;
        lastG = lp.gravity;
        panel.frame.setLayoutParams(lp);
        panel.frame.setElevation(8 * d);
    }

    @Override public void exportProjects(List<Map<String, Object>> list, String base) {
        String safe = base.replaceAll("[\\\\/:*?\"<>|]", "_");
        String text = Json.str(Core.payload(board.store, new ArrayList<>(list), Instant.now().toString()));
        saveText(safe + "-" + Panel.stamp() + ".json", text);
    }

    @SuppressWarnings("deprecation")
    @Override public void saveText(String name, String text) {
        pendingSave = text;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, name);
        startActivityForResult(i, REQ_SAVE);
    }

    @SuppressWarnings("deprecation")
    @Override public void pickImport() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_OPEN);
    }

    @SuppressWarnings("deprecation")
    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        if (req == REQ_SAVE && pendingSave != null) {
            try (OutputStream os = getContentResolver().openOutputStream(u)) {
                os.write(pendingSave.getBytes("UTF-8"));
                board.toast("已匯出，請確認檔案已儲存", null, null);
            } catch (Exception e) {
                board.toast("匯出失敗，請再試一次", null, null);
            }
            pendingSave = null;
        } else if (req == REQ_OPEN) {
            try (InputStream in = getContentResolver().openInputStream(u)) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[32768];
                int n, total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > 5000000) throw new IllegalArgumentException("檔案太大，上限是 5 MB。");
                    bo.write(buf, 0, n);
                }
                final List<Map<String, Object>> list = Core.parseBackup(bo.toString("UTF-8"));
                sync.backupNow("匯入前", true, new Sync.Done() {
                    @Override public void done(boolean ok) {
                        List<Map<String, Object>> out = Core.importProjects(board.store, list, "（匯入）");
                        board.commit();
                        board.toast("已匯入 " + out.size() + " 個專案", null, null);
                        panel.renderBackups();
                    }
                });
            } catch (IllegalArgumentException e) {
                board.toast(e.getMessage(), null, null);
            } catch (Exception e) {
                board.toast("匯入失敗，請確認檔案內容", null, null);
            }
        }
    }

    @Override public void shareText(String title, String text) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, title);
        i.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(i, "分享"));
    }

    @Override public void copyText(String t, String done) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("TRACE", t));
        board.toast(done, null, null);
    }
}
