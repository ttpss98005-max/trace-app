package tw.trace.app;

import static tw.trace.app.Json.arr;
import static tw.trace.app.Json.obj;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Firebase over plain HTTPS, talking to the same project and the same documents as the web version.
 * Security stays with firestore.rules: every request carries the signed-in person's token.
 */
final class Cloud {
    static final String API_KEY = "AIzaSyCN5cO1J0ddSZ5z51SZNOHhoHUawj45BRc";
    static final String PROJECT_ID = "trace-site-26529";
    static final String SITE = "https://ttpss98005-max.github.io/trace-site/";
    private static final String DB = "projects/" + PROJECT_ID + "/databases/(default)/documents";
    private static final String BASE = "https://firestore.googleapis.com/v1/" + DB;

    /** A request the server refused; code is the Firestore status such as PERMISSION_DENIED. */
    static final class ApiError extends IOException {
        final int http;
        final String code;
        ApiError(int http, String code, String m) { super(m); this.http = http; this.code = code; }
        boolean conflict() { return http == 409 || http == 412 || "ABORTED".equals(code) || "FAILED_PRECONDITION".equals(code) || "ALREADY_EXISTS".equals(code); }
        boolean denied() { return http == 403 || "PERMISSION_DENIED".equals(code); }
        boolean tooBig() { return "INVALID_ARGUMENT".equals(code); }
        boolean signedOut() { return http == 401 || "UNAUTHENTICATED".equals(code); }
    }

    /** Where the signed-in session is kept between launches. */
    interface Session {
        String refreshToken();
        String idToken();
        long idExpiry();
        void saveToken(String idToken, long expiry, String refreshToken);
    }

    private final Session session;

    Cloud(Session s) { this.session = s; }

    /* ---------- http ---------- */
    private static String http(String method, String url, String body, String contentType, String bearer) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        try {
            h.setConnectTimeout(12000);
            h.setReadTimeout(25000);
            h.setRequestMethod(method);
            h.setRequestProperty("Referer", SITE);
            if (bearer != null) h.setRequestProperty("Authorization", "Bearer " + bearer);
            if (body != null) {
                h.setDoOutput(true);
                h.setRequestProperty("Content-Type", contentType);
                OutputStream os = h.getOutputStream();
                os.write(body.getBytes("UTF-8"));
                os.close();
            }
            int code = h.getResponseCode();
            InputStream in = code >= 400 ? h.getErrorStream() : h.getInputStream();
            String text = "";
            if (in != null) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                in.close();
                text = bo.toString("UTF-8");
            }
            if (code >= 400) throw toError(code, text);
            return text;
        } finally {
            h.disconnect();
        }
    }

    private static ApiError toError(int code, String text) {
        String st = "", msg = text.length() > 300 ? text.substring(0, 300) : text;
        try {
            Object v = Json.parse(text);
            if (v instanceof List && !arr(v).isEmpty()) v = arr(v).get(0);
            Map<String, Object> e = obj(obj(v).get("error"));
            st = Core.s(e, "status");
            String m = Core.s(e, "message");
            if (st == null) st = "";
            if (m != null) msg = m;
        } catch (RuntimeException ignore) { }
        return new ApiError(code, st, msg);
    }

    /* ---------- sign-in ---------- */
    static final class Account {
        String uid, email, name, idToken, refreshToken;
        long expiry;
    }

    /** The project's Google client id, read from Firebase so it never has to be typed in. */
    static String googleClientId() throws IOException {
        String body = Json.str(Json.map("providerId", "google.com", "continueUri", "http://localhost"));
        String res = http("POST", "https://identitytoolkit.googleapis.com/v1/accounts:createAuthUri?key=" + API_KEY, body, "application/json", null);
        String uri = Core.s(obj(Json.parse(res)), "authUri");
        if (uri != null) for (String part : uri.substring(uri.indexOf('?') + 1).split("&")) {
            if (part.startsWith("client_id=")) return URLDecoder.decode(part.substring(10), "UTF-8");
        }
        throw new IOException("Firebase 還沒啟用 Google 登入");
    }

    static Account signInWithGoogle(String googleIdToken) throws IOException {
        String body = Json.str(Json.map(
                "postBody", "id_token=" + URLEncoder.encode(googleIdToken, "UTF-8") + "&providerId=google.com",
                "requestUri", "http://localhost", "returnIdpCredential", true, "returnSecureToken", true));
        Map<String, Object> o = obj(Json.parse(http("POST", "https://identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key=" + API_KEY, body, "application/json", null)));
        Account a = new Account();
        a.uid = Core.s(o, "localId");
        a.email = Core.s(o, "email");
        a.name = Core.s(o, "displayName");
        a.idToken = Core.s(o, "idToken");
        a.refreshToken = Core.s(o, "refreshToken");
        a.expiry = System.currentTimeMillis() + parseSecs(Core.s(o, "expiresIn")) * 1000L;
        if (a.email == null) a.email = "";
        if (a.name == null) a.name = "";
        return a;
    }

    private static long parseSecs(String s) {
        try { return Long.parseLong(s); } catch (RuntimeException e) { return 3600; }
    }

    private synchronized String token() throws IOException {
        String t = session.idToken();
        if (t != null && !t.isEmpty() && System.currentTimeMillis() < session.idExpiry() - 60000L) return t;
        String body = "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(session.refreshToken(), "UTF-8");
        Map<String, Object> o = obj(Json.parse(http("POST", "https://securetoken.googleapis.com/v1/token?key=" + API_KEY, body, "application/x-www-form-urlencoded", null)));
        String id = Core.s(o, "id_token");
        String rt = Core.s(o, "refresh_token");
        session.saveToken(id, System.currentTimeMillis() + parseSecs(Core.s(o, "expires_in")) * 1000L, rt == null ? session.refreshToken() : rt);
        return id;
    }

    /* ---------- Firestore values ---------- */
    static Map<String, Object> encode(Object v) {
        if (v == null) return Json.map("nullValue", null);
        if (v instanceof String) return Json.map("stringValue", v);
        if (v instanceof Boolean) return Json.map("booleanValue", v);
        if (v instanceof Instant) return Json.map("timestampValue", v.toString());
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (d == Math.rint(d) && Math.abs(d) <= 9007199254740991d) return Json.map("integerValue", Json.num(d));
            return Json.map("doubleValue", d);
        }
        if (v instanceof List) {
            List<Object> vals = new ArrayList<>();
            for (Object x : (List<?>) v) vals.add(encode(x));
            return Json.map("arrayValue", Json.map("values", vals));
        }
        if (v instanceof Map) return Json.map("mapValue", Json.map("fields", fields(obj(v))));
        throw new IllegalArgumentException("cannot store " + v.getClass());
    }

    static Map<String, Object> fields(Map<String, Object> m) {
        Map<String, Object> f = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : m.entrySet()) f.put(e.getKey(), encode(e.getValue()));
        return f;
    }

    static Object decode(Map<String, Object> v) {
        if (v.containsKey("stringValue")) return v.get("stringValue");
        if (v.containsKey("integerValue")) return Double.valueOf(String.valueOf(v.get("integerValue")));
        if (v.containsKey("doubleValue")) {
            Object d = v.get("doubleValue");
            return d instanceof Number ? (Object) ((Number) d).doubleValue() : Double.valueOf(String.valueOf(d));
        }
        if (v.containsKey("booleanValue")) return v.get("booleanValue");
        if (v.containsKey("timestampValue")) return v.get("timestampValue");
        if (v.containsKey("arrayValue")) {
            List<Object> out = new ArrayList<>();
            Object vals = obj(v.get("arrayValue")).get("values");
            if (vals != null) for (Object x : arr(vals)) out.add(decode(obj(x)));
            return out;
        }
        if (v.containsKey("mapValue")) {
            Object fs = obj(v.get("mapValue")).get("fields");
            return fs == null ? new LinkedHashMap<String, Object>() : unfields(obj(fs));
        }
        return null;
    }

    static Map<String, Object> unfields(Map<String, Object> f) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : f.entrySet()) m.put(e.getKey(), decode(obj(e.getValue())));
        return m;
    }

    /* ---------- documents ---------- */
    static final class Doc {
        String id, updateTime;
        Map<String, Object> data;
    }

    private static Doc toDoc(Map<String, Object> d) {
        Doc o = new Doc();
        String name = Core.s(d, "name");
        o.id = name.substring(name.lastIndexOf('/') + 1);
        o.updateTime = Core.s(d, "updateTime");
        Object f = d.get("fields");
        o.data = f == null ? new LinkedHashMap<String, Object>() : unfields(obj(f));
        return o;
    }

    private static String enc(String segment) throws IOException { return URLEncoder.encode(segment, "UTF-8").replace("+", "%20"); }

    /** Returns null when the document does not exist. */
    Doc get(String path) throws IOException {
        StringBuilder b = new StringBuilder(BASE);
        for (String seg : path.split("/")) b.append('/').append(enc(seg));
        try {
            return toDoc(obj(Json.parse(http("GET", b.toString(), null, null, token()))));
        } catch (ApiError e) {
            if (e.http == 404) return null;
            throw e;
        }
    }

    private List<Object> commit(List<Object> writes) throws IOException {
        String res = http("POST", BASE + ":commit", Json.str(Json.map("writes", writes)), "application/json", token());
        Object wr = obj(Json.parse(res)).get("writeResults");
        return wr == null ? new ArrayList<Object>() : arr(wr);
    }

    private static String docName(String path) { return DB + "/" + path; }

    /** set(data, {merge:true}) */
    void setMerge(String path, Map<String, Object> data) throws IOException {
        List<Object> mask = new ArrayList<>(data.keySet());
        commit(Json.list(Json.map("update", Json.map("name", docName(path), "fields", fields(data)), "updateMask", Json.map("fieldPaths", mask))));
    }

    /** set(data) */
    void set(String path, Map<String, Object> data) throws IOException {
        commit(Json.list(Json.map("update", Json.map("name", docName(path), "fields", fields(data)))));
    }

    void delete(String path) throws IOException {
        commit(Json.list(Json.map("delete", docName(path))));
    }

    /* ---------- projects ---------- */
    /** watchProjects(): one reading of every project this account belongs to. */
    List<Doc> listProjects(String uid) throws IOException {
        Map<String, Object> q = Json.map("structuredQuery", Json.map(
                "from", Json.list(Json.map("collectionId", "projects")),
                "where", Json.map("fieldFilter", Json.map(
                        "field", Json.map("fieldPath", "members"),
                        "op", "ARRAY_CONTAINS",
                        "value", encode(uid)))));
        Object rows = Json.parse(http("POST", BASE + ":runQuery", Json.str(q), "application/json", token()));
        List<Doc> out = new ArrayList<>();
        for (Object r : arr(rows)) {
            Object d = obj(r).get("document");
            if (d != null) out.add(toDoc(obj(d)));
        }
        return out;
    }

    /** What mergeSave decided for a project. */
    static final class Built {
        boolean abort, create, nothing;
        Map<String, Object> data;
    }

    interface Builder { Built build(Map<String, Object> remote); }

    static final class Saved {
        boolean abort, gone;
        String json;
    }

    /**
     * mergeSave(): read, merge, write. The write only succeeds if the document has not changed since the
     * read, otherwise it starts over, so two accounts can never overwrite each other (same guarantee as a transaction).
     */
    Saved mergeSave(String pid, Builder builder) throws IOException {
        String path = "projects/" + pid;
        for (int attempt = 0; ; attempt++) {
            Doc cur = get(path);
            Built b = builder.build(cur == null ? null : cur.data);
            Saved s = new Saved();
            if (b.nothing) { s.gone = true; return s; }
            if (b.abort) { s.abort = true; return s; }
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("update", Json.map("name", docName(path), "fields", fields(b.data)));
            if (b.create) {
                w.put("currentDocument", Json.map("exists", false));
            } else {
                w.put("updateMask", Json.map("fieldPaths", new ArrayList<Object>(b.data.keySet())));
                w.put("currentDocument", cur == null ? Json.map("exists", true) : Json.map("updateTime", cur.updateTime));
            }
            try {
                commit(Json.list(w));
                s.json = Core.s(b.data, "json");
                return s;
            } catch (ApiError e) {
                if (!e.conflict() || attempt >= 6) throw e;
                try { Thread.sleep(150L * (attempt + 1)); } catch (InterruptedException ie) { throw new IOException("interrupted"); }
            }
        }
    }

    /** A field path that is safe for any account id. */
    private static String memberPath(String uid) { return "memberInfo.`" + uid + "`"; }

    void removeMember(String pid, String uid) throws IOException {
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("update", Json.map("name", docName("projects/" + pid), "fields", new LinkedHashMap<String, Object>()));
        w.put("updateMask", Json.map("fieldPaths", Json.list(memberPath(uid))));
        w.put("updateTransforms", Json.list(Json.map("fieldPath", "members", "removeAllFromArray", Json.map("values", Json.list(encode(uid))))));
        w.put("currentDocument", Json.map("exists", true));
        commit(Json.list(w));
    }

    /* ---------- invites ---------- */
    String createInvite(String pid, String uid) throws IOException {
        String code = Core.newInviteCode();
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("projectId", pid);
        d.put("createdBy", uid);
        d.put("usedBy", null);
        d.put("expiresAt", Instant.ofEpochMilli(System.currentTimeMillis() + 7L * 86400000L));
        set("invites/" + code, d);
        return code;
    }

    /** Returns the project id, or throws JoinError with what went wrong. */
    static final class JoinError extends Exception {
        final String kind;
        JoinError(String kind) { this.kind = kind; }
    }

    String join(String code, String uid, Map<String, Object> info) throws IOException, JoinError {
        Doc d = get("invites/" + code);
        if (d == null) throw new JoinError("missing");
        if (uid.equals(d.data.get("createdBy"))) throw new JoinError("self");
        if (d.data.get("usedBy") != null) throw new JoinError("used");
        Object exp = d.data.get("expiresAt");
        if (exp == null || Instant.parse(String.valueOf(exp)).toEpochMilli() < System.currentTimeMillis()) throw new JoinError("expired");
        String pid = Core.s(d.data, "projectId");
        Map<String, Object> inv = new LinkedHashMap<>();
        inv.put("update", Json.map("name", docName("invites/" + code), "fields", Json.map("usedBy", encode(uid))));
        inv.put("updateMask", Json.map("fieldPaths", Json.list("usedBy")));
        inv.put("currentDocument", Json.map("exists", true));
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("joinedWith", encode(code));
        fields.put("memberInfo", encode(Json.map(uid, info)));
        Map<String, Object> pr = new LinkedHashMap<>();
        pr.put("update", Json.map("name", docName("projects/" + pid), "fields", fields));
        pr.put("updateMask", Json.map("fieldPaths", Json.list("joinedWith", memberPath(uid))));
        pr.put("updateTransforms", Json.list(Json.map("fieldPath", "members", "appendMissingElements", Json.map("values", Json.list(encode(uid))))));
        pr.put("currentDocument", Json.map("exists", true));
        commit(Json.list(inv, pr));
        return pid;
    }
}
