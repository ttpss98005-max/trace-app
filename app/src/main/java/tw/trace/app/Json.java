package tw.trace.app;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON that behaves exactly like the browser's JSON.parse / JSON.stringify.
 * Data lives in plain maps and lists, so fields written by another version are kept untouched.
 * stable() must produce the same text as the web version: both sides compare it to detect changes.
 */
final class Json {
    private Json() {}

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object o) { return (Map<String, Object>) o; }

    @SuppressWarnings("unchecked")
    static List<Object> arr(Object o) { return (List<Object>) o; }

    /* ---------- parse ---------- */
    static Object parse(String s) {
        P p = new P(s);
        p.ws();
        Object v = p.val();
        p.ws();
        if (p.i != s.length()) throw new IllegalArgumentException("trailing text at " + p.i);
        return v;
    }

    private static final class P {
        final String s;
        int i = 0;
        P(String s) { this.s = s; }

        void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++;
                else break;
            }
        }

        char need() {
            if (i >= s.length()) throw new IllegalArgumentException("unexpected end");
            return s.charAt(i);
        }

        Object val() {
            char c = need();
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            return number();
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (need() == '}') { i++; return m; }
            while (true) {
                ws();
                if (need() != '"') throw new IllegalArgumentException("key expected at " + i);
                String k = string();
                ws();
                if (need() != ':') throw new IllegalArgumentException(": expected at " + i);
                i++;
                ws();
                m.put(k, val());
                ws();
                char c = need();
                i++;
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException(", expected at " + i);
            }
        }

        List<Object> array() {
            List<Object> a = new ArrayList<>();
            i++;
            ws();
            if (need() == ']') { i++; return a; }
            while (true) {
                ws();
                a.add(val());
                ws();
                char c = need();
                i++;
                if (c == ']') return a;
                if (c != ',') throw new IllegalArgumentException(", expected at " + i);
            }
        }

        String string() {
            i++;
            int start = i;
            while (true) {
                char c = need();
                if (c == '"') { String r = s.substring(start, i); i++; return r; }
                if (c == '\\') break;
                i++;
            }
            StringBuilder b = new StringBuilder(s.substring(start, i));
            while (true) {
                char c = need();
                i++;
                if (c == '"') return b.toString();
                if (c != '\\') { b.append(c); continue; }
                char e = need();
                i++;
                switch (e) {
                    case '"': b.append('"'); break;
                    case '\\': b.append('\\'); break;
                    case '/': b.append('/'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break;
                    case 't': b.append('\t'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw new IllegalArgumentException("bad escape");
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: throw new IllegalArgumentException("bad escape at " + i);
                }
            }
        }

        Double number() {
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (st == i) throw new IllegalArgumentException("value expected at " + i);
            return Double.valueOf(s.substring(st, i));
        }
    }

    /* ---------- stringify ---------- */
    static String str(Object v) {
        StringBuilder b = new StringBuilder();
        write(b, v, false);
        return b.toString();
    }

    /** Same output as the web's stable(): keys sorted at every level. */
    static String stable(Object v) {
        StringBuilder b = new StringBuilder();
        write(b, v, true);
        return b.toString();
    }

    private static void write(StringBuilder b, Object v, boolean sorted) {
        if (v == null) { b.append("null"); return; }
        if (v instanceof String) { quote(b, (String) v); return; }
        if (v instanceof Boolean) { b.append(((Boolean) v) ? "true" : "false"); return; }
        if (v instanceof Number) { b.append(num(((Number) v).doubleValue())); return; }
        if (v instanceof List) {
            b.append('[');
            boolean first = true;
            for (Object x : (List<?>) v) {
                if (!first) b.append(',');
                first = false;
                write(b, x, sorted);
            }
            b.append(']');
            return;
        }
        if (v instanceof Map) {
            Map<String, Object> m = obj(v);
            List<String> keys = new ArrayList<>(m.keySet());
            if (sorted) Collections.sort(keys);
            b.append('{');
            boolean first = true;
            for (String k : keys) {
                if (!first) b.append(',');
                first = false;
                quote(b, k);
                b.append(':');
                write(b, m.get(k), sorted);
            }
            b.append('}');
            return;
        }
        throw new IllegalArgumentException("not JSON: " + v.getClass());
    }

    /** Number formatting of JavaScript. */
    static String num(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return "null";
        if (d == 0) return "0";
        if (d == Math.rint(d) && Math.abs(d) < 1e21) return new BigDecimal(d).toPlainString();
        double a = Math.abs(d);
        if (a >= 1e-6 && a < 1e21) {
            String p = new BigDecimal(Double.toString(d)).stripTrailingZeros().toPlainString();
            return p;
        }
        String e = Double.toString(d).replace(".0E", "E");
        int ix = e.indexOf('E');
        if (ix < 0) return e;
        String exp = e.substring(ix + 1);
        return e.substring(0, ix) + "e" + (exp.startsWith("-") ? "" : "+") + exp;
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\b': b.append("\\b"); break;
                case '\f': b.append("\\f"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) { u(b, c); break; }
                    if (Character.isHighSurrogate(c)) {
                        if (i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                            b.append(c).append(s.charAt(++i));
                        } else u(b, c);
                        break;
                    }
                    if (Character.isLowSurrogate(c)) { u(b, c); break; }
                    b.append(c);
            }
        }
        b.append('"');
    }

    private static void u(StringBuilder b, char c) {
        b.append("\\u");
        String h = Integer.toHexString(c);
        for (int k = h.length(); k < 4; k++) b.append('0');
        b.append(h);
    }

    /* ---------- helpers ---------- */
    static Object copy(Object v) {
        if (v instanceof Map) {
            Map<String, Object> o = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : obj(v).entrySet()) o.put(e.getKey(), copy(e.getValue()));
            return o;
        }
        if (v instanceof List) {
            List<Object> o = new ArrayList<>();
            for (Object x : (List<?>) v) o.add(copy(x));
            return o;
        }
        return v;
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static List<Object> list(Object... v) {
        List<Object> a = new ArrayList<>();
        Collections.addAll(a, v);
        return a;
    }
}
