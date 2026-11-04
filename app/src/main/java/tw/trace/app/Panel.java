package tw.trace.app;

import static tw.trace.app.Core.b;
import static tw.trace.app.Core.dn;
import static tw.trace.app.Core.ds;
import static tw.trace.app.Core.items;
import static tw.trace.app.Core.lanes;
import static tw.trace.app.Core.nodes;
import static tw.trace.app.Core.projects;
import static tw.trace.app.Core.s;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** The side panel (bottom sheet on a portrait phone): node form, settings, sharing, backups. */
final class Panel {
    interface Host {
        Board board();
        Sync sync();
        Theme theme();
        Typeface mono();
        Typeface display();
        void applyThemeNow();
        void exportProjects(List<Map<String, Object>> list, String base);
        void saveText(String name, String text);
        void pickImport();
        void shareText(String title, String text);
        void copyText(String t, String done);
        void logout();
        void layoutPanel();
    }

    private final Context ctx;
    private final Host host;
    final FrameLayout frame;
    private final ScrollView scroll;
    private final LinearLayout body;
    private final float dens;

    String mode;   // null, "new", "node", "set", "backup", "join"
    private Map<String, Object> draft;
    private String draftFrom;
    private boolean armDel = false;

    Panel(Context c, Host h) {
        ctx = c;
        host = h;
        dens = c.getResources().getDisplayMetrics().density;
        frame = new FrameLayout(c);
        frame.setVisibility(View.GONE);
        frame.setClickable(true);
        scroll = new ScrollView(c);
        scroll.setFillViewport(true);
        body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        frame.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    boolean open() { return frame.getVisibility() == View.VISIBLE; }

    private Board bd() { return host.board(); }
    private Theme th() { return host.theme(); }
    private int dp(double v) { return (int) Math.round(v * dens); }

    boolean typing() {
        View f = frame.findFocus();
        return f instanceof EditText;
    }

    private void hideKeyboard() {
        InputMethodManager im = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(frame.getWindowToken(), 0);
    }

    private void showKeyboard(final View v) {
        v.post(new Runnable() {
            @Override public void run() {
                v.requestFocus();
                InputMethodManager im = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (im != null) im.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    /* ---------- building blocks ---------- */
    private GradientDrawable box(int fill, int border) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setStroke(Math.max(1, dp(1)), border);
        return d;
    }

    private LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }
    private LinearLayout.LayoutParams full() { return lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); }

    private View gapped(View v, double top) {
        ViewGroup.LayoutParams o = v.getLayoutParams();
        LinearLayout.LayoutParams l = o instanceof LinearLayout.LayoutParams ? (LinearLayout.LayoutParams) o : full();
        l.topMargin = dp(top);
        v.setLayoutParams(l);
        return v;
    }

    private TextView tv(String t, double size, int color, Typeface f) {
        TextView v = new TextView(ctx);
        v.setText(t);
        v.setTextSize(TypedValue.COMPLEX_UNIT_DIP, (float) size);
        v.setTextColor(color);
        if (f != null) v.setTypeface(f);
        return v;
    }

    private TextView note(String t) {
        TextView v = tv(t, 12.5, th().dim, null);
        v.setLineSpacing(0, 1.3f);
        return v;
    }

    private void addH3(String t) {
        TextView v = tv(t, 15, th().dim, host.display());
        v.setLetterSpacing(.14f);
        body.addView(gapped(v, 18));
    }

    private void addLabel(ViewGroup to, String t) {
        TextView v = tv(t, 12, th().dim, null);
        if (to instanceof LinearLayout) to.addView(gapped(v, 14));
        else to.addView(v);
    }

    private Button btn(String t, View.OnClickListener l) {
        Button v = new Button(ctx, null, 0, android.R.style.Widget_Material_Button_Borderless);
        v.setAllCaps(false);
        v.setText(t);
        v.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        v.setTextColor(th().ink);
        v.setMinHeight(dp(38));
        v.setMinimumHeight(dp(38));
        v.setMinWidth(0);
        v.setMinimumWidth(0);
        v.setPadding(dp(12), 0, dp(12), 0);
        v.setBackground(box(th().card, th().line));
        v.setStateListAnimator(null);
        v.setOnClickListener(l);
        return v;
    }

    private Button primary(String t, View.OnClickListener l) {
        Button v = btn(t, l);
        v.setBackground(box(th().accent, th().accent));
        v.setTextColor(th().onAccent);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private void toPrimary(Button v, String t) {
        v.setText(t);
        v.setBackground(box(th().accent, th().accent));
        v.setTextColor(th().onAccent);
    }

    private Button danger(String t, View.OnClickListener l) {
        Button v = btn(t, l);
        v.setBackground(box(Color.TRANSPARENT, th().danger));
        v.setTextColor(th().danger);
        return v;
    }

    private EditText input(String value, String hint, int max) {
        EditText v = new EditText(ctx);
        v.setText(value);
        v.setHint(hint);
        v.setHintTextColor(th().dim);
        v.setTextColor(th().ink);
        v.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        v.setBackground(box(th().bg, th().line));
        v.setPadding(dp(9), dp(7), dp(9), dp(7));
        v.setSingleLine(true);
        v.setInputType(InputType.TYPE_CLASS_TEXT);
        v.setImeOptions(EditorInfo.IME_ACTION_DONE);
        if (max > 0) v.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});
        return v;
    }

    private abstract static class Change implements TextWatcher {
        @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
        @Override public void onTextChanged(CharSequence s, int a, int c, int d) {}
        @Override public void afterTextChanged(Editable e) { changed(e.toString()); }
        abstract void changed(String v);
    }

    private interface Pick { void picked(int index); }

    private void choose(String title, final String[] names, final Pick p) {
        new AlertDialog.Builder(ctx, dialogTheme()).setTitle(title).setItems(names, new android.content.DialogInterface.OnClickListener() {
            @Override public void onClick(android.content.DialogInterface d, int i) { p.picked(i); }
        }).show();
    }

    @SuppressWarnings("deprecation")
    private int dialogTheme() { return th().dark ? AlertDialog.THEME_DEVICE_DEFAULT_DARK : AlertDialog.THEME_DEVICE_DEFAULT_LIGHT; }

    /** Looks like a form select, opens a list. */
    private Button select(String current, final String title, final String[] names, final Pick p) {
        Button v = btn(current == null || current.isEmpty() ? "選擇" : current, new View.OnClickListener() {
            @Override public void onClick(View x) { choose(title, names, p); }
        });
        v.setBackground(box(th().bg, th().line));
        v.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        v.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        return v;
    }

    /** Two-way switch used for 類型 and 影響本專案進度. */
    private LinearLayout segmented(String[] labels, final int on, final Pick p) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < labels.length; i++) {
            final int k = i;
            Button v = btn(labels[i], new View.OnClickListener() { @Override public void onClick(View x) { p.picked(k); } });
            boolean sel = i == on;
            v.setBackground(box(sel ? th().ink : Color.TRANSPARENT, th().line));
            v.setTextColor(sel ? th().bg : th().dim);
            row.addView(v, new LinearLayout.LayoutParams(0, dp(38), 1));
        }
        return row;
    }

    private void head(String title) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView h = tv(title, 19, th().ink, Typeface.DEFAULT_BOLD);
        row.addView(h, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button x = btn("×", new View.OnClickListener() { @Override public void onClick(View v) { close(); } });
        x.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        x.setPadding(0, 0, 0, 0);
        x.setContentDescription("關閉");
        row.addView(x, lp(dp(40), dp(40)));
        body.addView(row, full());
    }

    private void begin(String m) {
        mode = m;
        Theme t = th();
        frame.setBackgroundColor(t.card);
        View top = frame;
        top.setForeground(null);
        body.removeAllViews();
        body.setPadding(dp(18), dp(16), dp(16), dp(24));
        if (frame.getVisibility() != View.VISIBLE) {
            frame.setVisibility(View.VISIBLE);
            scroll.scrollTo(0, 0);
        }
        host.layoutPanel();
    }

    void close() {
        hideKeyboard();
        frame.setVisibility(View.GONE);
        mode = null;
        draft = null;
        bd().selId = null;
        bd().need();
    }

    /* ---------- node form ---------- */
    void openNew(String lane, String date, String from) {
        Board B = bd();
        String l = lane != null ? lane : s(B.mainLane(), "id");
        String d = date;
        if (d == null) {
            int base = B.today;
            Board.Lane box = null;
            for (Board.Lane x : B.laneBoxes) if (x.id.equals(l)) box = x;
            if (box != null && !box.items.isEmpty()) base = dn(s(box.items.get(box.items.size() - 1), "date"));
            else {
                Map<String, Object> lo = Core.laneOf(B.state, l);
                if (lo != null && !nodes(B.state).isEmpty()) base = B.anchorDay(lo);
            }
            d = ds(base + 7);
        }
        draft = Json.map("title", "", "lane", l, "date", d, "status", "todo", "pct", 0d, "note", "", "items", new ArrayList<Object>());
        draftFrom = from;
        B.selId = null;
        begin("new");
        nodeForm(draft, true, true);
        B.need();
    }

    void openEdit(String id, boolean recenter) {
        Board B = bd();
        Map<String, Object> n = Core.nodeOf(B.state, id);
        if (n == null) return;
        B.selId = id;
        draft = n;
        armDel = false;
        begin("node");
        nodeForm(n, false, false);
        if (recenter) {
            Board.Geo g = B.G.get(id);
            double sx = B.SX(g.x), sy = B.SY(g.y), sw = Board.W * B.camZ;
            boolean side = B.panelSide();
            double safeR = side ? B.VW - B.panelW() - 20 : B.VW - 20, safeB = side ? B.VH - 20 : B.VH * .22 + B.rulerY;
            if (sx + sw > safeR || sx < B.leftPad() || sy < B.rulerY + 30 || sy + Board.H * B.camZ > safeB)
                B.centerOn(g.x + Board.W / 2, g.y + Board.H / 2, B.camZ, true, true);
        }
        B.need();
    }

    private void set(boolean isNew) { if (!isNew) bd().commit(); }

    private void nodeForm(final Map<String, Object> n, final boolean isNew, boolean focusTitle) {
        final Board B = bd();
        body.removeAllViews();
        head(isNew ? "新增節點" : "編輯節點");
        // title
        addLabel(body, "節點名稱");
        final EditText title = input(s(n, "title"), "例如：完成第一版設計", 60);
        final String[] goodTitle = {s(n, "title")};
        title.addTextChangedListener(new Change() {
            @Override void changed(String v) {
                n.put("title", v);
                if (!isNew && !v.trim().isEmpty()) { goodTitle[0] = v; set(false); }
            }
        });
        // a node can't be left nameless: clearing the box and leaving restores the last name
        title.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                if (!has && !isNew && title.getText().toString().trim().isEmpty()) { n.put("title", goodTitle[0]); title.setText(goodTitle[0]); }
            }
        });
        body.addView(gapped(title, 5));
        // lane + date
        LinearLayout two = new LinearLayout(ctx);
        two.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout c1 = new LinearLayout(ctx), c2 = new LinearLayout(ctx);
        c1.setOrientation(LinearLayout.VERTICAL);
        c2.setOrientation(LinearLayout.VERTICAL);
        final List<Map<String, Object>> ls = lanes(B.state);
        String[] names = new String[ls.size()];
        String cur = "";
        for (int i = 0; i < ls.size(); i++) {
            names[i] = s(ls.get(i), "name") + ("main".equals(ls.get(i).get("kind")) ? "（主線）" : "");
            if (s(ls.get(i), "id").equals(s(n, "lane"))) cur = names[i];
        }
        addLabel(c1, "時間線");
        final Button laneBtn = select(cur, "時間線", names, null);
        final String[] namesF = names;
        laneBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                choose("時間線", namesF, new Pick() {
                    @Override public void picked(int i) { n.put("lane", s(ls.get(i), "id")); laneBtn.setText(namesF[i]); set(isNew); }
                });
            }
        });
        c1.addView(gapped(laneBtn, 5));
        addLabel(c2, "日期");
        final Button dateBtn = select(s(n, "date"), "", new String[0], null);
        dateBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int[] p = ymd(s(n, "date"));
                new DatePickerDialog(ctx, dialogTheme(), new DatePickerDialog.OnDateSetListener() {
                    @Override public void onDateSet(android.widget.DatePicker dp0, int y, int m, int d) {
                        String t = String.format(java.util.Locale.ROOT, "%04d-%02d-%02d", y, m + 1, d);
                        n.put("date", t);
                        dateBtn.setText(t);
                        set(isNew);
                    }
                }, p[0], p[1] - 1, p[2]).show();
            }
        });
        c2.addView(gapped(dateBtn, 5));
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1), b0 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        b0.leftMargin = dp(10);
        two.addView(c1, a);
        two.addView(c2, b0);
        body.addView(two, full());
        // kind
        addLabel(body, "類型");
        boolean portal = Core.isPortal(n);
        body.addView(gapped(segmented(new String[]{"一般節點", "傳送門"}, portal ? 1 : 0, new Pick() {
            @Override public void picked(int i) {
                if (i == 1) {
                    n.put("kind", "portal");
                    if (Core.project(B.store, s(n, "target")) == null) {
                        Object t = null;
                        for (Map<String, Object> p : projects(B.store)) if (!s(p, "id").equals(s(B.state, "id"))) { t = p.get("id"); break; }
                        n.put("target", t);
                    }
                } else {
                    n.remove("kind");
                    n.remove("target");
                    n.remove("noCount");
                    if (!(n.get("items") instanceof List)) n.put("items", new ArrayList<Object>());
                }
                set(isNew);
                nodeForm(n, isNew, false);
            }
        }), 5));
        if (portal) portalPart(n, isNew); else itemsPart(n, isNew);
        // links
        if (!isNew) {
            final List<Object> valid = new ArrayList<>();
            for (Object o : Core.links(n)) if (Core.nodeOf(B.state, String.valueOf(o)) != null) valid.add(o);
            if (!valid.isEmpty()) {
                addLabel(body, "連接到");
                for (final Object o : valid) {
                    LinearLayout row = new LinearLayout(ctx);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    TextView name = tv(s(Core.nodeOf(B.state, String.valueOf(o)), "title"), 13, th().ink, null);
                    name.setBackground(box(Color.TRANSPARENT, th().line));
                    name.setPadding(dp(8), dp(7), dp(8), dp(7));
                    row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                    Button x = btn("×", new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            List<Object> keep = new ArrayList<>(Core.links(n));
                            keep.remove(o);
                            if (keep.isEmpty()) n.remove("links"); else n.put("links", keep);
                            set(isNew);
                            nodeForm(n, isNew, false);
                        }
                    });
                    x.setPadding(0, 0, 0, 0);
                    LinearLayout.LayoutParams xp = lp(dp(34), dp(34));
                    xp.leftMargin = dp(5);
                    row.addView(x, xp);
                    body.addView(gapped(row, 5));
                }
            }
        }
        // note
        addLabel(body, "備註");
        EditText noteE = input(s(n, "note") == null ? "" : s(n, "note"), "卡在哪裡、下一步是什麼", 500);
        noteE.setSingleLine(false);
        noteE.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        noteE.setMinLines(3);
        noteE.setGravity(Gravity.TOP | Gravity.START);
        noteE.addTextChangedListener(new Change() { @Override void changed(String v) { n.put("note", v); set(isNew); } });
        body.addView(gapped(noteE, 5));
        final TextView err = tv("", 12.5, th().danger, null);
        err.setVisibility(View.GONE);
        body.addView(gapped(err, 8));
        if (isNew) {
            Button create = primary("建立節點", new View.OnClickListener() {
                @Override public void onClick(View v) { submitNew(n, err, title); }
            });
            body.addView(gapped(create, 14));
        } else {
            final Button del = danger("刪除節點", null);
            del.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (!armDel) { armDel = true; toPrimary(del, "再按一次確認刪除"); return; }
                    String name = s(n, "title");
                    List<Map<String, Object>> list = nodes(B.state);
                    String id = s(n, "id");
                    for (int i = list.size() - 1; i >= 0; i--) if (id.equals(s(list.get(i), "id"))) list.remove(i);
                    close();
                    B.commit();
                    B.toast("已刪除「" + name + "」", "復原", B.undo);
                }
            });
            body.addView(gapped(del, 14));
        }
        if (focusTitle) showKeyboard(title);
    }

    private static int[] ymd(String s) {
        try {
            String[] p = s.split("-");
            return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])};
        } catch (RuntimeException e) {
            java.time.LocalDate d = java.time.LocalDate.ofEpochDay(Core.today());
            return new int[]{d.getYear(), d.getMonthValue(), d.getDayOfMonth()};
        }
    }

    private void portalPart(final Map<String, Object> n, final boolean isNew) {
        final Board B = bd();
        addLabel(body, "傳送到");
        final List<Map<String, Object>> others = new ArrayList<>();
        for (Map<String, Object> p : projects(B.store)) if (!s(p, "id").equals(s(B.state, "id"))) others.add(p);
        final String[] names = new String[others.size() + 1];
        for (int i = 0; i < others.size(); i++) names[i] = s(others.get(i), "name");
        names[others.size()] = "＋ 建立新專案";
        final Map<String, Object> tp = Core.project(B.store, s(n, "target"));
        body.addView(gapped(select(tp == null ? "選擇專案" : s(tp, "name"), "傳送到", names, new Pick() {
            @Override public void picked(int i) {
                if (i == others.size()) {
                    String t = s(n, "title");
                    Map<String, Object> np = Core.newProject(t != null && !t.trim().isEmpty() ? t.trim() : "新專案");
                    Json.arr(B.store.get("projects")).add(np);
                    n.put("target", np.get("id"));
                    host.sync().scheduleSave();
                    B.toast("已建立專案「" + s(np, "name") + "」", null, null);
                } else n.put("target", others.get(i).get("id"));
                set(isNew);
                nodeForm(n, isNew, false);
            }
        }), 5));
        addLabel(body, "影響本專案進度");
        body.addView(gapped(segmented(new String[]{"計入", "不計入"}, b(n, "noCount") ? 1 : 0, new Pick() {
            @Override public void picked(int i) {
                if (i == 1) n.put("noCount", true); else n.remove("noCount");
                set(isNew);
                nodeForm(n, isNew, false);
            }
        }), 5));
        if (tp != null && !b(n, "noCount")) {
            addLabel(body, "目標專案進度");
            long pc = Math.round(Core.projTotal(B.store, tp));
            LinearLayout row = new LinearLayout(ctx);
            row.setGravity(Gravity.CENTER_VERTICAL);
            FrameLayout track = new FrameLayout(ctx);
            track.setBackgroundColor(th().line);
            LinearLayout inner = new LinearLayout(ctx);
            View on = new View(ctx);
            on.setBackgroundColor(th().accent);
            inner.addView(on, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, pc));
            inner.addView(new View(ctx), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 100 - pc));
            track.addView(inner, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            row.addView(track, new LinearLayout.LayoutParams(0, dp(3), 1));
            TextView o = tv(pc + "%", 22, th().ink, host.display());
            o.setGravity(Gravity.END);
            row.addView(o, lp(dp(58), ViewGroup.LayoutParams.WRAP_CONTENT));
            body.addView(gapped(row, 5));
        }
        if (tp != null && !isNew) {
            final String nid = s(n, "id");
            body.addView(gapped(primary("傳送到「" + s(tp, "name") + "」", new View.OnClickListener() {
                @Override public void onClick(View v) { B.teleport(nid); }
            }), 12));
        }
    }

    private void itemsPart(final Map<String, Object> n, final boolean isNew) {
        addLabel(body, "細項");
        LinearLayout prog = new LinearLayout(ctx);
        prog.setGravity(Gravity.CENTER_VERTICAL);
        final TextView pv = tv("", 30, th().accent, host.display());
        final TextView cnt = tv("", 12, th().dim, host.mono());
        cnt.setPadding(dp(10), 0, dp(8), 0);
        prog.addView(pv);
        prog.addView(cnt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final Button bl = btn("標記卡關", null);
        bl.setMinHeight(dp(32));
        bl.setMinimumHeight(dp(32));
        prog.addView(bl, lp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)));
        body.addView(gapped(prog, 5));
        final LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        body.addView(gapped(box, 6));
        final Runnable[] draw = new Runnable[1];
        final Runnable syncBl = new Runnable() {
            @Override public void run() {
                boolean on = b(n, "blocked");
                bl.setBackground(box(on ? th().danger : Color.TRANSPARENT, on ? th().danger : th().line));
                bl.setTextColor(on ? th().bg : th().dim);
            }
        };
        syncBl.run();
        bl.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                n.put("blocked", !b(n, "blocked"));
                set(isNew);
                syncBl.run();
                draw[0].run();
            }
        });
        final EditText add = input("", "輸入細項後按完成", 80);
        draw[0] = new Runnable() {
            @Override public void run() {
                if (!(n.get("items") instanceof List)) n.put("items", new ArrayList<Object>());
                final List<Object> it = Json.arr(n.get("items"));
                Core.syncNode(n);
                int d = 0;
                for (Map<String, Object> x : items(n)) if (b(x, "done")) d++;
                pv.setText(Math.round(Core.n(n, "pct")) + "%");
                String st = String.valueOf(n.get("status"));
                String stName = "doing".equals(st) ? "進行中" : "done".equals(st) ? "已完成" : "blocked".equals(st) ? "卡關" : "未完成";
                cnt.setText(it.isEmpty() ? stName : d + "/" + it.size() + " 細項 · " + stName);
                box.removeAllViews();
                if (it.isEmpty()) {
                    CheckBox c = check(b(n, "manualDone"));
                    c.setText("沒有細項，直接標記完成");
                    c.setTextColor(th().ink);
                    c.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                    c.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                        @Override public void onCheckedChanged(android.widget.CompoundButton v, boolean on) {
                            n.put("manualDone", on);
                            set(isNew);
                            box.post(draw[0]);
                        }
                    });
                    box.addView(c);
                    return;
                }
                for (final Object o : new ArrayList<>(it)) {
                    final Map<String, Object> x = Json.obj(o);
                    LinearLayout row = new LinearLayout(ctx);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    final boolean done = b(x, "done");
                    CheckBox c = check(done);
                    c.setContentDescription("完成 " + x.get("text"));
                    c.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                        @Override public void onCheckedChanged(android.widget.CompoundButton v, boolean on) {
                            x.put("done", on);
                            set(isNew);
                            box.post(draw[0]);
                        }
                    });
                    row.addView(c);
                    final EditText t = input(String.valueOf(x.get("text")), "", 80);
                    t.setPadding(dp(8), dp(5), dp(8), dp(5));
                    if (done) { t.setTextColor(th().dim); t.setPaintFlags(t.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG); }
                    t.addTextChangedListener(new Change() { @Override void changed(String v) { x.put("text", v); set(isNew); } });
                    t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                        @Override public void onFocusChange(View v, boolean has) {
                            if (!has && String.valueOf(x.get("text")).trim().isEmpty()) {
                                it.remove(x);
                                set(isNew);
                                box.post(draw[0]);
                            }
                        }
                    });
                    t.setOnEditorActionListener(new TextView.OnEditorActionListener() {
                        @Override public boolean onEditorAction(TextView v, int a, KeyEvent e) { add.requestFocus(); return true; }
                    });
                    row.addView(t, new LinearLayout.LayoutParams(0, dp(38), 1));
                    Button del = btn("×", new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            it.remove(x);
                            set(isNew);
                            box.post(draw[0]);
                        }
                    });
                    del.setPadding(0, 0, 0, 0);
                    del.setTextColor(th().dim);
                    del.setContentDescription("刪除細項 " + x.get("text"));
                    LinearLayout.LayoutParams xp = lp(dp(34), dp(38));
                    xp.leftMargin = dp(5);
                    row.addView(del, xp);
                    box.addView(gapped(row, 5));
                }
            }
        };
        draw[0].run();
        LinearLayout addRow = new LinearLayout(ctx);
        addRow.setGravity(Gravity.CENTER_VERTICAL);
        add.setBackground(box(th().bg, th().ink));
        final View.OnClickListener doAdd = new View.OnClickListener() {
            @Override public void onClick(View v) {
                String t = add.getText().toString().trim();
                if (t.isEmpty()) return;
                if (!(n.get("items") instanceof List)) n.put("items", new ArrayList<Object>());
                Json.arr(n.get("items")).add(Json.map("id", Core.uid(), "text", t, "done", false));
                add.setText("");
                set(isNew);
                draw[0].run();
                add.requestFocus();
            }
        };
        add.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int a, KeyEvent e) { doAdd.onClick(v); return true; }
        });
        addRow.addView(add, new LinearLayout.LayoutParams(0, dp(40), 1));
        Button ab = btn("新增", doAdd);
        LinearLayout.LayoutParams ap = lp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        ap.leftMargin = dp(6);
        addRow.addView(ab, ap);
        body.addView(gapped(addRow, 6));
        pendingAdd = add;
    }

    private EditText pendingAdd;

    private CheckBox check(boolean on) {
        CheckBox c = new CheckBox(ctx);
        c.setChecked(on);
        c.setButtonTintList(ColorStateList.valueOf(th().accent));
        return c;
    }

    private void submitNew(Map<String, Object> n, TextView err, EditText title) {
        Board B = bd();
        if (pendingAdd != null && !Core.isPortal(n) && !pendingAdd.getText().toString().trim().isEmpty()) {
            if (!(n.get("items") instanceof List)) n.put("items", new ArrayList<Object>());
            Json.arr(n.get("items")).add(Json.map("id", Core.uid(), "text", pendingAdd.getText().toString().trim(), "done", false));
        }
        if (Core.isPortal(n) && Core.project(B.store, s(n, "target")) == null) {
            err.setVisibility(View.VISIBLE);
            err.setText("請先選擇要傳送到哪個專案。");
            return;
        }
        String t = s(n, "title");
        if ((t == null || t.trim().isEmpty()) && Core.isPortal(n)) n.put("title", s(Core.project(B.store, s(n, "target")), "name"));
        t = s(n, "title");
        if (t == null || t.trim().isEmpty()) {
            err.setVisibility(View.VISIBLE);
            err.setText("請先輸入節點名稱。");
            title.requestFocus();
            return;
        }
        Map<String, Object> nn = new java.util.LinkedHashMap<>();
        nn.put("id", Core.uid());
        nn.putAll(n);
        nn.put("title", t.trim());
        if (!Core.isPortal(nn)) Core.syncNode(nn);
        if (draftFrom != null) {
            Map<String, Object> src = Core.nodeOf(B.state, draftFrom);
            if (src != null) {
                List<Object> ls = Core.links(src);
                ls.add(nn.get("id"));
                src.put("links", ls);
            }
            draftFrom = null;
        }
        Json.arr(B.state.get("nodes")).add(nn);
        B.commit();
        String nid = s(nn, "id");
        Board.Geo g = B.G.get(nid);
        g.ax = g.x;
        g.ay = g.y + 18;
        hideKeyboard();
        double sx = g.x * B.camZ + B.camX;
        if (sx > B.VW - Board.W * B.camZ || sx < B.leftPad()) B.centerOn(g.x + Board.W / 2, g.y + Board.H / 2, B.camZ, true, false);
        if (B.fin == null) openEdit(nid, false);
        else close();
        B.toast("已建立「" + s(nn, "title") + "」", null, null);
    }

    /* ---------- settings ---------- */
    void openSettings(String focusLane) {
        final Board B = bd();
        B.selId = null;
        draft = null;
        begin("set");
        head("主題與時間線");
        addLabel(body, "專案名稱");
        EditText name = input(s(B.state, "name"), "", 50);
        name.addTextChangedListener(new Change() {
            @Override void changed(String v) {
                B.state.put("name", v.isEmpty() ? "未命名專案" : v);
                B.commit();
            }
        });
        body.addView(gapped(name, 5));
        addH3("介面主題");
        // swatches
        GridLayout grid = new GridLayout(ctx);
        grid.setColumnCount(3);
        final Map<String, Object> t = B.store.get("theme") instanceof Map ? Json.obj(B.store.get("theme")) : Json.map("preset", "auto");
        String preset = s(t, "preset");
        for (final String[] p : Theme.PRESETS) {
            LinearLayout sw = new LinearLayout(ctx);
            sw.setGravity(Gravity.CENTER_VERTICAL);
            sw.setPadding(dp(8), 0, dp(4), 0);
            boolean on = p[0].equals(preset);
            GradientDrawable d = box(Color.TRANSPARENT, on ? th().ink : th().line);
            if (on) d.setStroke(dp(2), th().ink);
            sw.setBackground(d);
            int[] cols = p[2].isEmpty() ? new int[]{0xFFE7EBED, 0xFF0A0D11, 0xFFD81E3A} : new int[]{Theme.hex(p[2]), Theme.hex(p[3]), Theme.hex(p[4])};
            for (int c : cols) { View dot = new View(ctx); dot.setBackgroundColor(c); sw.addView(dot, lp(dp(9), dp(18))); }
            TextView nm = tv(p[1], 13, th().ink, null);
            nm.setPadding(dp(6), 0, 0, 0);
            nm.setSingleLine(true);
            nm.setEllipsize(TextUtils.TruncateAt.END);
            sw.addView(nm);
            sw.setClickable(true);
            sw.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    B.store.put("theme", Json.map("preset", p[0]));
                    host.applyThemeNow();
                    host.sync().scheduleSave();
                    openSettings(null);
                }
            });
            GridLayout.LayoutParams g = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            g.width = 0;
            g.height = dp(38);
            g.setMargins(0, dp(6), dp(6), 0);
            grid.addView(sw, g);
        }
        body.addView(grid, full());
        // custom colours
        LinearLayout cols = new LinearLayout(ctx);
        final String[] keys = {"bg", "ink", "accent"}, labels = {"背景", "線條文字", "強調色"};
        final int[] now = {th().bg, th().ink, th().accent};
        for (int i = 0; i < 3; i++) {
            final int k = i;
            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView lb = tv(labels[i], 12, th().dim, null);
            col.addView(lb);
            View sw = colorSwatch(now[i], new ColorDone() {
                @Override public void done(String hex) {
                    Map<String, Object> th2 = Json.map("preset", "custom", "bg", Theme.toHex(now[0]), "ink", Theme.toHex(now[1]), "accent", Theme.toHex(now[2]));
                    th2.put(keys[k], hex);
                    B.store.put("theme", th2);
                    host.applyThemeNow();
                    host.sync().scheduleSave();
                    openSettings(null);
                }
            }, labels[i]);
            col.addView(gapped(sw, 5));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (i > 0) cp.leftMargin = dp(8);
            cols.addView(col, cp);
        }
        body.addView(gapped(cols, 10));
        addH3("時間線顏色");
        laneBox = new LinearLayout(ctx);
        laneBox.setOrientation(LinearLayout.VERTICAL);
        body.addView(gapped(laneBox, 4));
        renderLaneList(focusLane);
        body.addView(gapped(btn("＋ 新增支線", new View.OnClickListener() {
            @Override public void onClick(View v) {
                String id = Core.uid();
                Json.arr(B.state.get("lanes")).add(Json.map("id", id, "name", "新支線", "kind", "branch", "color", Board.LANE_COLORS[lanes(B.state).size() % Board.LANE_COLORS.length]));
                B.commit();
                renderLaneList(id);
            }
        }), 10));
        Sync sy = host.sync();
        if (sy.curUser != null) collab();
        addH3("帳號");
        String who = sy.curUser != null ? ((sy.curUser.name != null && !sy.curUser.name.isEmpty() ? sy.curUser.name + " · " : "") + sy.curUser.email) : "試用模式，尚未連上 Google。";
        body.addView(gapped(note(who), 6));
        body.addView(gapped(btn("登出", new View.OnClickListener() { @Override public void onClick(View v) { host.logout(); } }), 8));
        addH3("專案資料");
        boolean sample = b(B.state, "isSample");
        if (sample) body.addView(gapped(note("目前顯示的是範例資料。你的修改會保留；準備好時可清空節點，從自己的專案開始。"), 6));
        body.addView(gapped(btn("匯出這個專案", new View.OnClickListener() {
            @Override public void onClick(View v) { host.exportProjects(Collections.singletonList(B.state), "TRACE-project"); }
        }), 8));
        body.addView(gapped(btn("預覽 100% 特效", new View.OnClickListener() {
            @Override public void onClick(View v) { B.playFinale(); }
        }), 8));
        final Button clr = danger(sample ? "清空範例，開始我的專案" : "清空所有節點", null);
        final boolean[] arm = {false};
        clr.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!arm[0]) { arm[0] = true; toPrimary(clr, "再按一次，刪除 " + nodes(B.state).size() + " 個節點"); return; }
                host.sync().backupNow("清空節點前", true, null);
                boolean wasSample = b(B.state, "isSample");
                B.state.put("nodes", new ArrayList<Object>());
                B.state.remove("isSample");
                if (wasSample) B.state.put("name", "我的專案");
                B.ref = B.today;
                B.commit();
                openSettings(null);
                B.fit(true);
                B.toast("已清空節點", "復原", new Runnable() {
                    @Override public void run() { B.histStep(-1); B.ref = B.refDay(); B.refresh(); B.fit(true); }
                });
            }
        });
        body.addView(gapped(clr, 8));
    }

    private interface ColorDone { void done(String hex); }

    private View colorSwatch(final int color, final ColorDone cb, final String title) {
        View v = new View(ctx);
        v.setBackground(box(color, th().line));
        v.setMinimumHeight(dp(36));
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)));
        v.setContentDescription(title);
        v.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View x) { pickColor(color, title, cb); } });
        return v;
    }

    /** A small colour chooser: hue, saturation and brightness sliders, or a typed code. */
    private void pickColor(int start, String title, final ColorDone cb) {
        final float[] hsv = new float[3];
        Color.colorToHSV(start, hsv);
        LinearLayout w = new LinearLayout(ctx);
        w.setOrientation(LinearLayout.VERTICAL);
        w.setPadding(dp(20), dp(12), dp(20), dp(4));
        final View preview = new View(ctx);
        preview.setBackgroundColor(start);
        w.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        final EditText code = input(Theme.toHex(start), "#RRGGBB", 7);
        code.setBackground(null);
        code.setTextColor(th().ink);
        w.addView(code);
        String[] names = {"色相", "飽和度", "亮度"};
        final SeekBar[] bars = new SeekBar[3];
        final boolean[] guard = {false};
        for (int i = 0; i < 3; i++) {
            final int k = i;
            w.addView(tv(names[i], 12, th().dim, null));
            SeekBar s = new SeekBar(ctx);
            s.setMax(k == 0 ? 360 : 100);
            s.setProgress(Math.round(k == 0 ? hsv[0] : hsv[k] * 100));
            s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar b0, int p, boolean user) {
                    if (!user) return;
                    hsv[k] = k == 0 ? p : p / 100f;
                    int c = Color.HSVToColor(hsv);
                    preview.setBackgroundColor(c);
                    guard[0] = true;
                    code.setText(Theme.toHex(c));
                    guard[0] = false;
                }
                @Override public void onStartTrackingTouch(SeekBar b0) {}
                @Override public void onStopTrackingTouch(SeekBar b0) {}
            });
            bars[i] = s;
            w.addView(s);
        }
        code.addTextChangedListener(new Change() {
            @Override void changed(String v) {
                if (guard[0] || !v.matches("^#[0-9a-fA-F]{6}$")) return;
                int c = Theme.hex(v);
                preview.setBackgroundColor(c);
                Color.colorToHSV(c, hsv);
                bars[0].setProgress(Math.round(hsv[0]));
                bars[1].setProgress(Math.round(hsv[1] * 100));
                bars[2].setProgress(Math.round(hsv[2] * 100));
            }
        });
        new AlertDialog.Builder(ctx, dialogTheme()).setTitle(title).setView(w)
                .setNegativeButton("取消", null)
                .setPositiveButton("確定", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int i) {
                        String v = code.getText().toString();
                        cb.done(v.matches("^#[0-9a-fA-F]{6}$") ? v.toLowerCase(java.util.Locale.ROOT) : String.format("#%06x", Color.HSVToColor(hsv) & 0xFFFFFF));
                    }
                }).show();
    }

    private LinearLayout laneBox;

    void renderLaneList(String focus) {
        final Board B = bd();
        if (laneBox == null) return;
        laneBox.removeAllViews();
        final List<Map<String, Object>> ls = lanes(B.state);
        for (int i = 0; i < ls.size(); i++) {
            final Map<String, Object> l = ls.get(i);
            final int idx = i;
            final String id = s(l, "id");
            boolean isMain = "main".equals(l.get("kind"));
            LinearLayout row = new LinearLayout(ctx);
            row.setGravity(Gravity.CENTER_VERTICAL);
            View col = colorSwatch(Theme.hex(s(l, "color")), new ColorDone() {
                @Override public void done(String hex) { l.put("color", hex); B.commit(); renderLaneList(null); }
            }, s(l, "name") + " 顏色");
            row.addView(col, lp(dp(38), dp(38)));
            EditText n = input(s(l, "name"), "", 16);
            n.setPadding(dp(8), dp(6), dp(8), dp(6));
            n.addTextChangedListener(new Change() {
                @Override void changed(String v) { l.put("name", v.isEmpty() ? "未命名" : v); B.commit(); }
            });
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, dp(38), 1);
            np.leftMargin = dp(5);
            row.addView(n, np);
            row.addView(small("↑", i > 0, "上移", new View.OnClickListener() {
                @Override public void onClick(View v) { Collections.swap(Json.arr(B.state.get("lanes")), idx, idx - 1); B.commit(); renderLaneList(null); }
            }));
            row.addView(small("↓", i < ls.size() - 1, "下移", new View.OnClickListener() {
                @Override public void onClick(View v) { Collections.swap(Json.arr(B.state.get("lanes")), idx, idx + 1); B.commit(); renderLaneList(null); }
            }));
            final boolean[] arm = {false};
            final Button[] del = new Button[1];
            del[0] = small("×", !isMain, "刪除時間線", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (!arm[0]) {
                        arm[0] = true;
                        del[0].setText("?");
                        del[0].setContentDescription("再按一次確認刪除");
                        del[0].setTextColor(th().danger);
                        del[0].setBackground(box(Color.TRANSPARENT, th().danger));
                        return;
                    }
                    String name = s(l, "name");
                    Json.arr(B.state.get("lanes")).remove(l);
                    List<Map<String, Object>> ns = nodes(B.state);
                    for (int k = ns.size() - 1; k >= 0; k--) if (id.equals(s(ns.get(k), "lane"))) ns.remove(k);
                    B.commit();
                    renderLaneList(null);
                    B.toast("已刪除時間線「" + name + "」", "復原", B.undo);
                }
            });
            row.addView(del[0]);
            laneBox.addView(gapped(row, 10));
            int cnt = 0;
            for (Map<String, Object> m : nodes(B.state)) if (id.equals(s(m, "lane"))) cnt++;
            TextView kind = tv((isMain ? "主線" : "支線") + " · " + cnt + " 個節點", 11, th().dim, host.mono());
            kind.setPadding(dp(43), 0, 0, 0);
            laneBox.addView(gapped(kind, 3));
            if (!isMain) {
                final String aid = B.anchorId(l);
                final List<Map<String, Object>> opts = new ArrayList<>();
                for (Map<String, Object> m : nodes(B.state)) if (!id.equals(s(m, "lane"))) opts.add(m);
                opts.sort((x, y) -> dn(s(x, "date")) - dn(s(y, "date")));
                final String[] names = new String[opts.size() + 1];
                names[0] = "起點：專案（" + s(B.state, "name") + "）";
                String cur = names[0];
                for (int k = 0; k < opts.size(); k++) {
                    Map<String, Object> m = opts.get(k);
                    Map<String, Object> ml = Core.laneOf(B.state, s(m, "lane"));
                    names[k + 1] = "起點：" + (ml == null ? "" : s(ml, "name")) + " / " + s(m, "title") + "（" + s(m, "date").substring(5).replace('-', '/') + "）";
                    if (aid.equals(s(m, "id"))) cur = names[k + 1];
                }
                Button sel = select(cur, s(l, "name") + " 的起點", names, new Pick() {
                    @Override public void picked(int k) {
                        l.put("from", k == 0 ? "root" : s(opts.get(k - 1), "id"));
                        B.commit();
                        renderLaneList(null);
                    }
                });
                sel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
                LinearLayout.LayoutParams sp = full();
                sp.leftMargin = dp(43);
                sp.topMargin = dp(4);
                sel.setLayoutParams(sp);
                laneBox.addView(sel);
                if (!"root".equals(aid)) {
                    int early = 0, ad = dn(s(Core.nodeOf(B.state, aid), "date"));
                    for (Map<String, Object> m : nodes(B.state)) if (id.equals(s(m, "lane")) && dn(s(m, "date")) < ad) early++;
                    if (early > 0) {
                        TextView w = tv("有 " + early + " 個節點早於起點", 11, th().danger, host.mono());
                        w.setPadding(dp(43), 0, 0, 0);
                        laneBox.addView(gapped(w, 3));
                    }
                }
            }
            if (id.equals(focus)) { n.requestFocus(); n.selectAll(); showKeyboard(n); }
        }
    }

    private Button small(String t, boolean enabled, String desc, View.OnClickListener l) {
        Button v = btn(t, l);
        v.setPadding(0, 0, 0, 0);
        v.setContentDescription(desc);
        v.setEnabled(enabled);
        v.setAlpha(enabled ? 1f : .3f);
        LinearLayout.LayoutParams p = lp(dp(32), dp(38));
        p.leftMargin = dp(4);
        v.setLayoutParams(p);
        return v;
    }

    /* ---------- sharing ---------- */
    private LinearLayout invBox;

    private void collab() {
        final Board B = bd();
        final Sync sy = host.sync();
        Sync.Meta m = sy.metaOf(s(B.state, "id"));
        if (m == null) {
            addH3("共同編輯");
            body.addView(gapped(note("專案同步完成後才能邀請。"), 6));
            return;
        }
        addH3("共同編輯（" + m.members.size() + " 人）");
        boolean owner = sy.curUser.uid.equals(m.owner);
        for (Object o : m.members) {
            final String id = String.valueOf(o);
            boolean me = id.equals(sy.curUser.uid);
            LinearLayout row = new LinearLayout(ctx);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(9), dp(7), dp(9), dp(7));
            row.setBackground(box(Color.TRANSPARENT, th().line));
            LinearLayout who = new LinearLayout(ctx);
            who.setOrientation(LinearLayout.VERTICAL);
            who.addView(tv(sy.nameOfMember(m, id) + (me ? "（你）" : ""), 13, th().ink, Typeface.DEFAULT_BOLD));
            Object inf = m.memberInfo.get(id);
            String mail = inf instanceof Map ? s(Json.obj(inf), "email") : "";
            who.addView(tv((mail == null ? "" : mail) + (id.equals(m.owner) ? " · 建立者" : ""), 11, th().dim, host.mono()));
            row.addView(who, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if ((owner && !me) || (!owner && me)) {
                final boolean[] arm = {false};
                final Button k = btn(owner ? "移除" : "退出專案", null);
                k.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (!arm[0]) { arm[0] = true; k.setText("再按一次確認"); return; }
                        k.setEnabled(false);
                        final String pid = s(B.state, "id");
                        sy.background(new Sync.Task<Boolean>() {
                            @Override public Boolean run() throws Exception { sy.cloud.removeMember(pid, id); return true; }
                        }, new Sync.Result<Boolean>() {
                            @Override public void ok(Boolean v0) { sy.onResume(); }
                            @Override public void fail(Exception e) { k.setEnabled(true); arm[0] = false; k.setText("操作失敗，再試一次"); }
                        });
                    }
                });
                row.addView(k, lp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)));
            }
            body.addView(gapped(row, 6));
        }
        if (owner) {
            final Button inv = btn(sy.invite != null && s(B.state, "id").equals(sy.invite[0]) ? "再產生一組" : "產生邀請碼", null);
            inv.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    inv.setEnabled(false);
                    final String pid = s(B.state, "id"), uid = sy.curUser.uid;
                    final String[] oldInv = sy.invite;
                    sy.pushHosted(new Runnable() {
                        @Override public void run() {
                            sy.background(new Sync.Task<String>() {
                                @Override public String run() throws Exception {
                                    String code = sy.cloud.createInvite(pid, uid);
                                    if (oldInv != null && pid.equals(oldInv[0])) try { sy.cloud.delete("invites/" + oldInv[1]); } catch (Exception ignore) { }
                                    return code;
                                }
                            }, new Sync.Result<String>() {
                                @Override public void ok(String code) {
                                    sy.invite = new String[]{pid, code};
                                    inv.setEnabled(true);
                                    inv.setText("再產生一組");
                                    drawInvite();
                                }
                                @Override public void fail(Exception e) {
                                    inv.setEnabled(true);
                                    if (invBox != null) {
                                        invBox.removeAllViews();
                                        invBox.addView(note("無法產生邀請碼。請確認已連上網路，且 Firestore 規則已更新。"));
                                    }
                                }
                            });
                        }
                    });
                }
            });
            body.addView(gapped(inv, 8));
            invBox = new LinearLayout(ctx);
            invBox.setOrientation(LinearLayout.VERTICAL);
            body.addView(invBox, full());
            drawInvite();
        }
    }

    private void drawInvite() {
        final Board B = bd();
        final Sync sy = host.sync();
        if (invBox == null) return;
        invBox.removeAllViews();
        if (sy.invite == null || !s(B.state, "id").equals(sy.invite[0])) return;
        final String code = Core.prettyCode(sy.invite[1]), link = Cloud.SITE + "#join-" + sy.invite[1];
        TextView big = tv(code, 24, th().accent, host.mono());
        big.setLetterSpacing(.08f);
        big.setPadding(dp(11), dp(9), dp(11), dp(9));
        big.setBackground(box(Color.TRANSPARENT, th().accent));
        invBox.addView(gapped(big, 8));
        LinearLayout row = new LinearLayout(ctx);
        final String nm = s(B.state, "name");
        row.addView(btn("複製邀請碼", new View.OnClickListener() { @Override public void onClick(View v) { host.copyText(code, "已複製邀請碼"); } }), new LinearLayout.LayoutParams(0, dp(38), 1));
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, dp(38), 1), p3 = new LinearLayout.LayoutParams(0, dp(38), 1);
        p2.leftMargin = dp(6);
        p3.leftMargin = dp(6);
        row.addView(btn("複製連結", new View.OnClickListener() { @Override public void onClick(View v) { host.copyText(link, "已複製邀請連結"); } }), p2);
        row.addView(primary("分享", new View.OnClickListener() {
            @Override public void onClick(View v) { host.shareText("加入「" + nm + "」", "邀請你一起編輯「" + nm + "」。\n邀請碼：" + code + "（7 天內有效）\n" + link); }
        }), p3);
        invBox.addView(gapped(row, 6));
        invBox.addView(gapped(note("對方打開連結並登入後按「加入專案」，或在「全部專案」按「輸入邀請碼」。一組只給一個人用，7 天內有效；要邀更多人就再產生一組。"), 6));
    }

    /* ---------- join ---------- */
    void openJoin(String prefill) {
        final Sync sy = host.sync();
        begin("join");
        head("輸入邀請碼");
        final EditText code = input(prefill == null ? "" : prefill, "XXXX-XXXX", 9);
        code.setTypeface(host.mono());
        code.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        body.addView(gapped(code, 12));
        final TextView err = tv("", 12.5, th().danger, null);
        err.setVisibility(View.GONE);
        body.addView(gapped(err, 8));
        final Button go = primary("加入專案", null);
        go.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final String c = code.getText().toString().toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]", "");
                err.setVisibility(View.GONE);
                if (c.length() != 8) { err.setText("邀請碼是 8 個英數字。"); err.setVisibility(View.VISIBLE); return; }
                go.setEnabled(false);
                final String uid = sy.curUser.uid;
                final Map<String, Object> info = sy.memberInfoOfMe();
                sy.background(new Sync.Task<String>() {
                    @Override public String run() throws Exception { return sy.cloud.join(c, uid, info); }
                }, new Sync.Result<String>() {
                    @Override public void ok(String pid) {
                        go.setEnabled(true);
                        close();
                        sy.afterJoin = pid;
                        sy.openJoined();
                        sy.onResume();
                    }
                    @Override public void fail(Exception e) {
                        go.setEnabled(true);
                        String t = "無法加入。這個邀請碼可能已失效，或目前連不上網路。";
                        if (e instanceof Cloud.JoinError) {
                            String k = ((Cloud.JoinError) e).kind;
                            t = "missing".equals(k) ? "找不到這個邀請碼，請確認有沒有打錯。" : "used".equals(k) ? "這個邀請碼已經用過了，請對方重新產生。" : "expired".equals(k) ? "這個邀請碼已過期，請對方重新產生。" : "這是你自己產生的邀請碼。";
                        }
                        err.setText(t);
                        err.setVisibility(View.VISIBLE);
                    }
                });
            }
        });
        body.addView(gapped(go, 10));
        if (prefill == null) showKeyboard(code);
    }

    /* ---------- backups ---------- */
    private LinearLayout bkList;

    void openBackups() {
        final Board B = bd();
        final Sync sy = host.sync();
        begin("backup");
        head("備份與還原");
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.addView(gapped(btn("匯出全部專案", new View.OnClickListener() {
            @Override public void onClick(View v) { host.exportProjects(projects(B.store), "TRACE-backup"); }
        }), 12));
        row.addView(gapped(btn("匯入檔案", new View.OnClickListener() { @Override public void onClick(View v) { host.pickImport(); } }), 8));
        row.addView(gapped(btn("立即備份", new View.OnClickListener() {
            @Override public void onClick(View v) {
                sy.backupNow("手動備份", true, new Sync.Done() {
                    @Override public void done(boolean ok) {
                        B.toast(ok ? "已建立備份" : "備份失敗，請確認網路連線", null, null);
                        renderBackups();
                    }
                });
            }
        }), 8));
        body.addView(row, full());
        body.addView(gapped(note("每 6 小時自動保留一份，刪除專案或清空節點前也會先備份，最多保留 " + Core.BK_MAX + " 份。還原會新增一份專案複本，不會覆蓋現有內容。"), 10));
        bkList = new LinearLayout(ctx);
        bkList.setOrientation(LinearLayout.VERTICAL);
        body.addView(gapped(bkList, 10));
        renderBackups();
    }

    void renderBackups() {
        final Board B = bd();
        final Sync sy = host.sync();
        if (bkList == null || !"backup".equals(mode)) return;
        bkList.removeAllViews();
        bkList.addView(note("讀取中…"));
        sy.listBackups(new Sync.BackupList() {
            @Override public void list(List<Map<String, Object>> items, boolean failed) {
                if (bkList == null || !"backup".equals(mode)) return;
                bkList.removeAllViews();
                if (failed) { bkList.addView(note("無法讀取備份，請確認已連上網路。")); return; }
                if (items.isEmpty()) { bkList.addView(note("還沒有備份。")); return; }
                for (Map<String, Object> rec : items) {
                    final String text;
                    final List<Map<String, Object>> projs;
                    try {
                        text = Sync.backupText(rec);
                        projs = Core.parseBackup(text);
                    } catch (Exception e) { continue; }
                    int ns = 0;
                    for (Map<String, Object> p : projs) ns += Json.arr(p.get("nodes")).size();
                    final String when = fmtTime(String.valueOf(rec.get("at")));
                    final LinearLayout card = new LinearLayout(ctx);
                    card.setOrientation(LinearLayout.VERTICAL);
                    card.setBackground(box(th().bg, th().line));
                    LinearLayout sum = new LinearLayout(ctx);
                    sum.setOrientation(LinearLayout.VERTICAL);
                    sum.setPadding(dp(10), dp(8), dp(10), dp(8));
                    sum.addView(tv(when, 13, th().ink, Typeface.DEFAULT_BOLD));
                    String reason = rec.get("reason") == null ? "" : String.valueOf(rec.get("reason"));
                    sum.addView(tv(reason + " · " + projs.size() + " 個專案 · " + ns + " 個節點", 11.5, th().dim, host.mono()));
                    card.addView(sum);
                    final LinearLayout detail = new LinearLayout(ctx);
                    detail.setOrientation(LinearLayout.VERTICAL);
                    detail.setVisibility(View.GONE);
                    for (final Map<String, Object> p : projs) {
                        LinearLayout r = new LinearLayout(ctx);
                        r.setGravity(Gravity.CENTER_VERTICAL);
                        r.setPadding(dp(10), dp(6), dp(10), dp(6));
                        r.addView(tv(s(p, "name") + "（" + Json.arr(p.get("nodes")).size() + " 節點）", 13, th().ink, null), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                        final Button re = btn("還原", null);
                        re.setOnClickListener(new View.OnClickListener() {
                            @Override public void onClick(View v) {
                                re.setEnabled(false);
                                sy.backupNow("還原前", true, new Sync.Done() {
                                    @Override public void done(boolean ok) {
                                        List<Map<String, Object>> single = Collections.singletonList(p);
                                        List<Map<String, Object>> out = Core.importProjects(B.store, single, "（還原 " + when.substring(5) + "）");
                                        B.commit();
                                        B.toast("已還原為新專案「" + s(out.get(0), "name") + "」", null, null);
                                        renderBackups();
                                    }
                                });
                            }
                        });
                        r.addView(re, lp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)));
                        detail.addView(r);
                    }
                    LinearLayout r = new LinearLayout(ctx);
                    r.setGravity(Gravity.CENTER_VERTICAL);
                    r.setPadding(dp(10), dp(6), dp(10), dp(6));
                    r.addView(tv("下載這份備份", 13, th().ink, null), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                    r.addView(btn("下載", new View.OnClickListener() {
                        @Override public void onClick(View v) { host.saveText("TRACE-backup-" + stamp() + ".json", text); }
                    }), lp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)));
                    detail.addView(r);
                    card.addView(detail);
                    sum.setClickable(true);
                    sum.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) { detail.setVisibility(detail.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); }
                    });
                    bkList.addView(gapped(card, 6));
                }
                if (bkList.getChildCount() == 0) bkList.addView(note("備份無法讀取。"));
            }
        });
    }

    static String stamp() {
        java.time.LocalDateTime d = java.time.LocalDateTime.now();
        return String.format(java.util.Locale.ROOT, "%04d%02d%02d-%02d%02d", d.getYear(), d.getMonthValue(), d.getDayOfMonth(), d.getHour(), d.getMinute());
    }

    private static String fmtTime(String iso) {
        try {
            java.time.ZonedDateTime d = java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault());
            return String.format(java.util.Locale.ROOT, "%04d/%02d/%02d %02d:%02d", d.getYear(), d.getMonthValue(), d.getDayOfMonth(), d.getHour(), d.getMinute());
        } catch (RuntimeException e) {
            return iso.length() >= 5 ? iso : "     " + iso;
        }
    }

    /* ---------- redraw after changes from elsewhere ---------- */
    void redraw() {
        if ("set".equals(mode)) { int y = scroll.getScrollY(); openSettings(null); scroll.scrollTo(0, y); }
        else if ("node".equals(mode) && draft != null) nodeForm(draft, false, false);
    }
}
