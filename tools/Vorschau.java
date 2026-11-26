import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/* CPU-Nachbau des Dorfportal-Shaders zum Prüfen der gebackenen Szene.
 * Aufruf: java Vorschau <shader-paket> <Szene> <ausgabe.png> [kx ky kz yawGrad pitchGrad] */
public final class Vorschau {
    static final int W = 96, H = 56, D = 80, DF = 24, DT = D + DF, HB = 7, GX0 = -46, GY0 = -16, SP = 32;
    static BufferedImage vox, pal, atl;
    static int szene;

    static int b8(int argb, int sh) { return (argb >> sh) & 255; }
    static int[] voxel(int cx, int cy, int cz) {
        int i = cx - GX0, j = cy - GY0, k = -cz - 1 + DF;
        if (i < 0 || j < 0 || k < 0 || i >= W || j >= H || k >= DT) return null;
        int p = vox.getRGB(i + (j % 8) * W, k + (j / 8) * DT + szene * DT * HB);
        return new int[]{b8(p, 16) | (b8(p, 8) << 8), b8(p, 0)};
    }
    static int pal(int e, int s) { return pal.getRGB(s, e); }
    static int kachel(int e, int f) { int p = pal(e, 5 + f); return b8(p, 16) * 256 + b8(p, 8); }
    static int atlas(int k, double u, double v) {
        int x = (k % SP) * 16 + Math.min(15, Math.max(0, (int) Math.floor(u * 16)));
        int y = (k / SP) * 16 + Math.min(15, Math.max(0, (int) Math.floor(v * 16)));
        return atl.getRGB(x, y);
    }
    static double[] uv(int f, double[] p) {
        return switch (f) { case 0 -> new double[]{p[0], p[2]}; case 1 -> new double[]{p[0], 1 - p[2]};
            case 2 -> new double[]{1 - p[0], 1 - p[1]}; case 3 -> new double[]{p[0], 1 - p[1]};
            case 4 -> new double[]{1 - p[2], 1 - p[1]}; default -> new double[]{p[2], 1 - p[1]}; };
    }
    static double schatten(int f) { return f == 0 ? 1 : f == 1 ? .5 : f <= 3 ? .8 : .6; }
    static double licht(int s) { return .18 + (1 - .18) * Math.pow(.82, 15 - s); }
    static double[] himmel(double[] d) {
        double t = Math.max(0, Math.min(1, (d[1] + .05) / .6)); t = t * t * (3 - 2 * t);
        return new double[]{.75 + (.47 - .75) * t, .85 + (.65 - .85) * t, 1};
    }

    public static void main(String[] a) throws Exception {
        String pfad = a[0] + "/assets/minecraft/textures/effect/magicportals/";
        vox = ImageIO.read(new File(pfad + "voxel.png")); pal = ImageIO.read(new File(pfad + "palette.png")); atl = ImageIO.read(new File(pfad + "atlas.png"));
        szene = Integer.parseInt(a[1]);
        double kx = 1.5, ky = 1.62, kz = 3.5, yaw = 0, pitch = 5;
        if (a.length >= 8) { kx = Double.parseDouble(a[3]); ky = Double.parseDouble(a[4]); kz = Double.parseDouble(a[5]); yaw = Double.parseDouble(a[6]); pitch = Double.parseDouble(a[7]); }
        int bw = 640, bh = 400; double fov = Math.toRadians(80);
        var bild = new BufferedImage(bw, bh, BufferedImage.TYPE_INT_RGB);
        double cy = Math.cos(Math.toRadians(yaw)), sy = Math.sin(Math.toRadians(yaw)), cp = Math.cos(Math.toRadians(pitch)), spch = Math.sin(Math.toRadians(pitch));
        for (int py = 0; py < bh; py++) for (int px = 0; px < bw; px++) {
            double sx = (2.0 * (px + .5) / bw - 1) * Math.tan(fov / 2) * bw / bh, syy = (1 - 2.0 * (py + .5) / bh) * Math.tan(fov / 2);
            // Blick nach Norden (-z), yaw dreht nach rechts, pitch nach unten
            double[] v = {sx, syy * cp - spch, -cp - syy * spch};
            double[] d = {v[0] * cy - v[2] * sy * 0 , v[1], v[2]};
            d = new double[]{v[0] * cy + v[2] * -sy, v[1], v[0] * sy + v[2] * cy};
            double l = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]); d[0] /= l; d[1] /= l; d[2] /= l;
            int farbe;
            if (kz < 0) { double[] c = strahl(new double[]{kx, ky, kz}, d); farbe = ((int) (Math.min(1, c[0]) * 255) << 16) | ((int) (Math.min(1, c[1]) * 255) << 8) | (int) (Math.min(1, c[2]) * 255); }
            else if (d[2] >= -1e-4) farbe = 0x202020;
            else {
                double t0 = -kz / d[2];
                double[] h = {kx + d[0] * t0, ky + d[1] * t0, 0};
                boolean imTor = h[0] >= 0 && h[0] <= 3 && h[1] >= 0 && h[1] <= 3;
                double[] c = strahl(h, d);
                if (!imTor) { c[0] *= .35; c[1] *= .35; c[2] *= .35; }
                farbe = ((int) (Math.min(1, c[0]) * 255) << 16) | ((int) (Math.min(1, c[1]) * 255) << 8) | (int) (Math.min(1, c[2]) * 255);
            }
            bild.setRGB(px, py, farbe);
        }
        ImageIO.write(bild, "png", new File(a[2]));
    }

    static double[] strahl(double[] treffer, double[] d) {
        double[] o0 = {treffer[0] + d[0] * 1e-3, treffer[1] + d[1] * 1e-3, treffer[2] + d[2] * 1e-3};
        int[] zelle = {(int) Math.floor(o0[0]), (int) Math.floor(o0[1]), Math.min((int) Math.floor(o0[2]), -1)};
        int[] schritt = new int[3]; double[] tD = new double[3], tM = new double[3];
        for (int i = 0; i < 3; i++) {
            schritt[i] = (int) Math.signum(d[i]); tD[i] = Math.abs(1 / d[i]);
            double g = zelle[i] + Math.max(schritt[i], 0); tM[i] = (g - o0[i]) / d[i];
        }
        double[] farbe = {0, 0, 0}; double rest = 1, tS = 0; int vorherLicht = 15; boolean vorherFl = false;
        for (int n = 0; n < 320; n++) {
            int[] v = voxel(zelle[0], zelle[1], zelle[2]);
            if (v == null) break;
            int e = v[0], ls = v[1] & 15; boolean flHier = false;
            if (e != 0) {
                int p0 = pal(e, 0); int form = b8(p0, 16), mk = b8(p0, 8); boolean fl = (mk & 1) != 0; flHier = fl;
                double[] o = {o0[0] + d[0] * tS - zelle[0], o0[1] + d[1] * tS - zelle[1], o0[2] + d[2] * tS - zelle[2]};
                int tint = pal(e, 3); int tm = b8(pal(e, 4), 16);
                if (form == 1 && !(fl && vorherFl)) {
                    int lo = pal(e, 1), hi = pal(e, 2);
                    double[] L = {b8(lo, 16) / 16.0, b8(lo, 8) / 16.0, b8(lo, 0) / 16.0}, Hh = {b8(hi, 16) / 16.0, b8(hi, 8) / 16.0, b8(hi, 0) / 16.0};
                    if (fl && (v[1] & 16) != 0) Hh[1] = 1;
                    double nah = -1e9, fern = 1e9; int f = 0;
                    for (int i = 0; i < 3; i++) {
                        double t0 = (L[i] - o[i]) / d[i], t1 = (Hh[i] - o[i]) / d[i];
                        double tn = Math.min(t0, t1), tf = Math.max(t0, t1);
                        if (tn > nah) { nah = tn; f = i == 0 ? (d[0] > 0 ? 5 : 4) : i == 1 ? (d[1] > 0 ? 1 : 0) : (d[2] > 0 ? 2 : 3); }
                        fern = Math.min(fern, tf);
                    }
                    if (nah <= fern && fern >= 0) {
                        nah = Math.max(nah, 0);
                        double[] p = {o[0] + d[0] * nah, o[1] + d[1] * nah, o[2] + d[2] * nah};
                        for (int i = 0; i < 3; i++) p[i] = Math.min(.9999, Math.max(0, p[i]));
                        double[] uv = uv(f, p);
                        int tex = atlas(kachel(e, f), uv[0], uv[1]);
                        if (((tex >>> 24) & 255) > 127) {
                            double[] c = {b8(tex, 16) / 255.0, b8(tex, 8) / 255.0, b8(tex, 0) / 255.0};
                            if (((tm >> f) & 1) != 0) { c[0] *= b8(tint, 16) / 255.0; c[1] *= b8(tint, 8) / 255.0; c[2] *= b8(tint, 0) / 255.0; }
                            double hell = schatten(f) * licht(f == 0 || fl ? Math.max(ls, vorherLicht) : vorherLicht);
                            double weg = tS + nah, neb = Math.max(0, Math.min(1, (weg - D * .5) / (D * .47))); neb = neb * neb * (3 - 2 * neb);
                            double[] hm = himmel(d);
                            for (int i = 0; i < 3; i++) c[i] = c[i] * hell * (1 - neb) + hm[i] * neb;
                            if (fl) { for (int i = 0; i < 3; i++) farbe[i] += rest * .62 * c[i]; rest *= .38; }
                            else { for (int i = 0; i < 3; i++) farbe[i] += rest * c[i]; return farbe; }
                        }
                    }
                } else if (form == 2) {
                    double best = 1e9; double[] bc = null;
                    for (int ee = 0; ee < 2; ee++) {
                        double[] nr = ee == 0 ? new double[]{.7071, 0, -.7071} : new double[]{.7071, 0, .7071};
                        double ab = ee == 0 ? 0 : .7071, dn = d[0] * nr[0] + d[2] * nr[2];
                        if (Math.abs(dn) < 1e-5) continue;
                        double t = (ab - (o[0] * nr[0] + o[2] * nr[2])) / dn;
                        if (t < 0 || t >= best) continue;
                        double[] p = {o[0] + d[0] * t, o[1] + d[1] * t, o[2] + d[2] * t};
                        if (p[0] < 0 || p[1] < 0 || p[2] < 0 || p[0] > 1 || p[1] > 1 || p[2] > 1) continue;
                        int tex = atlas(kachel(e, 2), ee == 0 ? p[0] : 1 - p[0], 1 - p[1]);
                        if (((tex >>> 24) & 255) < 128) continue;
                        double[] c = {b8(tex, 16) / 255.0, b8(tex, 8) / 255.0, b8(tex, 0) / 255.0};
                        if ((tm & 1) != 0) { c[0] *= b8(tint, 16) / 255.0; c[1] *= b8(tint, 8) / 255.0; c[2] *= b8(tint, 0) / 255.0; }
                        for (int i = 0; i < 3; i++) c[i] *= .85 * licht(ls);
                        best = t; bc = c;
                    }
                    if (bc != null) { for (int i = 0; i < 3; i++) farbe[i] += rest * bc[i]; return farbe; }
                }
            }
            vorherFl = flHier; if (e == 0 || flHier) vorherLicht = ls;
            if (tM[0] < tM[1] && tM[0] < tM[2]) { tS = tM[0]; tM[0] += tD[0]; zelle[0] += schritt[0]; }
            else if (tM[1] < tM[2]) { tS = tM[1]; tM[1] += tD[1]; zelle[1] += schritt[1]; }
            else { tS = tM[2]; tM[2] += tD[2]; zelle[2] += schritt[2]; }
        }
        double[] hm = himmel(d);
        for (int i = 0; i < 3; i++) farbe[i] += rest * hm[i];
        return farbe;
    }
}
