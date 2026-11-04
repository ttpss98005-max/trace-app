package tw.trace.app;

import android.graphics.Path;

/** Just enough of the SVG path language for the Google logo. */
final class Svg {
    private Svg() {}

    static void into(Path p, String d) {
        int i = 0, n = d.length();
        char cmd = 'M';
        float x = 0, y = 0, cx = 0, cy = 0;
        float[] a = new float[6];
        boolean lastCurve = false;
        while (i < n) {
            char ch = d.charAt(i);
            if (Character.isLetter(ch)) { cmd = ch; i++; if (ch == 'z' || ch == 'Z') { p.close(); lastCurve = false; } continue; }
            if (ch == ' ' || ch == ',') { i++; continue; }
            int need;
            switch (Character.toLowerCase(cmd)) {
                case 'c': need = 6; break;
                case 's': need = 4; break;
                case 'h': case 'v': need = 1; break;
                default: need = 2;
            }
            for (int k = 0; k < need; k++) {
                while (i < n && (d.charAt(i) == ' ' || d.charAt(i) == ',')) i++;
                int st = i;
                if (i < n && (d.charAt(i) == '-' || d.charAt(i) == '+')) i++;
                boolean dot = false;
                while (i < n) {
                    char c = d.charAt(i);
                    if (Character.isDigit(c)) i++;
                    else if (c == '.' && !dot) { dot = true; i++; }
                    else break;
                }
                a[k] = Float.parseFloat(d.substring(st, i));
            }
            boolean rel = Character.isLowerCase(cmd);
            float ox = rel ? x : 0, oy = rel ? y : 0;
            switch (Character.toLowerCase(cmd)) {
                case 'm':
                    x = ox + a[0]; y = oy + a[1];
                    p.moveTo(x, y);
                    cmd = rel ? 'l' : 'L';
                    lastCurve = false;
                    break;
                case 'l':
                    x = ox + a[0]; y = oy + a[1];
                    p.lineTo(x, y);
                    lastCurve = false;
                    break;
                case 'h':
                    x = ox + a[0];
                    p.lineTo(x, y);
                    lastCurve = false;
                    break;
                case 'v':
                    y = oy + a[0];
                    p.lineTo(x, y);
                    lastCurve = false;
                    break;
                case 'c':
                    p.cubicTo(ox + a[0], oy + a[1], ox + a[2], oy + a[3], ox + a[4], oy + a[5]);
                    cx = ox + a[2]; cy = oy + a[3];
                    x = ox + a[4]; y = oy + a[5];
                    lastCurve = true;
                    break;
                case 's': {
                    float x1 = lastCurve ? 2 * x - cx : x, y1 = lastCurve ? 2 * y - cy : y;
                    p.cubicTo(x1, y1, ox + a[0], oy + a[1], ox + a[2], oy + a[3]);
                    cx = ox + a[0]; cy = oy + a[1];
                    x = ox + a[2]; y = oy + a[3];
                    lastCurve = true;
                    break;
                }
                default:
                    break;
            }
        }
    }
}
