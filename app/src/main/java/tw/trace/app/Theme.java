package tw.trace.app;

import java.util.Map;

/** Interface colours: the same presets and the same mixing rules as the web version. */
final class Theme {
    int bg, ink, accent, onAccent, danger, card, dim, line, hud;
    boolean dark;

    static final String[][] PRESETS = {
        {"auto", "跟隨系統", "", "", ""},
        {"frost", "霜白", "#E7EBED", "#10161B", "#D81E3A"},
        {"abyss", "深淵", "#0A0D11", "#D8E0E6", "#FF3B52"},
        {"ember", "赤曜", "#170A0D", "#F0DCDD", "#FF5F45"},
        {"ink", "青墨", "#0B1719", "#D2E4E1", "#2FD2BF"},
        {"lead", "鉛灰", "#C7CCCF", "#12171A", "#1C3FD4"},
    };

    static int hex(String h) {
        if (h == null) return 0xFF000000;
        h = h.trim();
        try {
            if (h.length() == 4) h = "#" + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2) + h.charAt(3) + h.charAt(3);
            return 0xFF000000 | Integer.parseInt(h.substring(1, 7), 16);
        } catch (RuntimeException e) {
            return 0xFF000000;
        }
    }

    static String toHex(int c) { return String.format("#%06X", c & 0xFFFFFF); }

    /** Perceived brightness, 0 to 1. */
    static double lum(int c) {
        return (.299 * ((c >> 16) & 255) + .587 * ((c >> 8) & 255) + .114 * (c & 255)) / 255;
    }

    /** color-mix(in srgb, a p, b) */
    static int mix(int a, double p, int b) {
        int r = (int) Math.round(((a >> 16) & 255) * p + ((b >> 16) & 255) * (1 - p));
        int g = (int) Math.round(((a >> 8) & 255) * p + ((b >> 8) & 255) * (1 - p));
        int bl = (int) Math.round((a & 255) * p + (b & 255) * (1 - p));
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    static int alpha(int c, double a) {
        int al = (int) Math.round(Math.max(0, Math.min(1, a)) * 255);
        return (c & 0xFFFFFF) | (al << 24);
    }

    void apply(Map<String, Object> theme, boolean systemDark) {
        String preset = theme == null ? "auto" : Core.s(theme, "preset");
        String b = null, i = null, ac = null;
        if ("custom".equals(preset) && theme != null) { b = Core.s(theme, "bg"); i = Core.s(theme, "ink"); ac = Core.s(theme, "accent"); }
        else for (String[] p : PRESETS) if (p[0].equals(preset) && !p[2].isEmpty()) { b = p[2]; i = p[3]; ac = p[4]; }
        if (b != null) {
            bg = hex(b);
            ink = hex(i);
            accent = hex(ac);
            onAccent = lum(accent) > .55 ? 0xFF0A0D11 : 0xFFFFFFFF;
            dark = lum(bg) <= .5;
            danger = dark ? 0xFFFF5A6E : 0xFFC4122F;
            card = mix(bg, dark ? .90 : .88, 0xFFFFFFFF);
        } else if (systemDark) {
            bg = 0xFF0A0D11; ink = 0xFFD8E0E6; accent = 0xFFFF3B52; onAccent = 0xFF0A0D11; danger = 0xFFFF5A6E;
            dark = true;
            card = mix(bg, .90, 0xFFFFFFFF);
        } else {
            bg = 0xFFE7EBED; ink = 0xFF10161B; accent = 0xFFD81E3A; onAccent = 0xFFFFFFFF; danger = 0xFFC4122F;
            dark = false;
            card = mix(bg, .88, 0xFFFFFFFF);
        }
        dim = mix(ink, .62, bg);
        line = mix(ink, .22, bg);
        hud = alpha(bg, .90);
    }
}
