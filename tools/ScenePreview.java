import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/* CPU re-implementation of the MagicPortals portal shader, to check a baked scene without a client.
 * Renders one view through the portal (or from inside it) into a PNG.
 *
 * Usage: java tools/ScenePreview.java <folder with assets/> <scene> <output.png> [x y z yaw pitch]
 * Camera coordinates are portal-local: the opening spans x 0..3, y 0..3 at z = 0, players stand at z > 0.
 * yaw 0 looks north through the portal, pitch > 0 looks down. A camera with z < 0 is "inside". */
public final class ScenePreview {
    // must match SceneBaker
    static final int W = 96, H = 56, D = 80, DB = 24, DT = D + DB, HB = 7, GX0 = -46, GY0 = -16, COLUMNS = 32;
    static BufferedImage voxels, palette, atlas;
    static int scene;

    static int b8(int argb, int shift) { return (argb >> shift) & 255; }

    static int[] voxel(int cx, int cy, int cz) {
        int i = cx - GX0, j = cy - GY0, k = -cz - 1 + DB;
        if (i < 0 || j < 0 || k < 0 || i >= W || j >= H || k >= DT) return null;
        int p = voxels.getRGB(i + (j % 8) * W, k + (j / 8) * DT + scene * DT * HB);
        return new int[]{b8(p, 16) | (b8(p, 8) << 8), b8(p, 0)};
    }

    static int pal(int entry, int column) { return palette.getRGB(column, entry); }
    static int tile(int entry, int face) { int p = pal(entry, 5 + face); return b8(p, 16) * 256 + b8(p, 8); }

    static int atlasTexel(int tile, double u, double v) {
        int x = (tile % COLUMNS) * 16 + Math.min(15, Math.max(0, (int) Math.floor(u * 16)));
        int y = (tile / COLUMNS) * 16 + Math.min(15, Math.max(0, (int) Math.floor(v * 16)));
        return atlas.getRGB(x, y);
    }

    static double[] faceUV(int f, double[] p) {
        return switch (f) {
            case 0 -> new double[]{p[0], p[2]}; case 1 -> new double[]{p[0], 1 - p[2]};
            case 2 -> new double[]{1 - p[0], 1 - p[1]}; case 3 -> new double[]{p[0], 1 - p[1]};
            case 4 -> new double[]{1 - p[2], 1 - p[1]}; default -> new double[]{p[2], 1 - p[1]};
        };
    }

    static double faceShade(int f) { return f == 0 ? 1 : f == 1 ? .5 : f <= 3 ? .8 : .6; }
    static double lightFactor(int level) { return .18 + (1 - .18) * Math.pow(.82, 15 - level); }

    static double[] sky(double[] d) {
        double t = Math.max(0, Math.min(1, (d[1] + .05) / .6));
        t = t * t * (3 - 2 * t);
        return new double[]{.75 + (.47 - .75) * t, .85 + (.65 - .85) * t, 1};
    }

    static double[] tinted(int tex, int tint, boolean apply) {
        double[] c = {b8(tex, 16) / 255.0, b8(tex, 8) / 255.0, b8(tex, 0) / 255.0};
        if (apply) { c[0] *= b8(tint, 16) / 255.0; c[1] *= b8(tint, 8) / 255.0; c[2] *= b8(tint, 0) / 255.0; }
        return c;
    }

    public static void main(String[] args) throws Exception {
        String dir = args[0] + "/assets/minecraft/textures/effect/magicportals/";
        voxels = ImageIO.read(new File(dir + "voxel.png"));
        palette = ImageIO.read(new File(dir + "palette.png"));
        atlas = ImageIO.read(new File(dir + "atlas.png"));
        scene = Integer.parseInt(args[1]);
        double cx = 1.5, cy = 1.62, cz = 3.5, yaw = 0, pitch = 5;
        if (args.length >= 8) {
            cx = Double.parseDouble(args[3]); cy = Double.parseDouble(args[4]); cz = Double.parseDouble(args[5]);
            yaw = Double.parseDouble(args[6]); pitch = Double.parseDouble(args[7]);
        }
        int width = 640, height = 400;
        double fov = Math.toRadians(80);
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        double sinYaw = Math.sin(Math.toRadians(yaw)), cosYaw = Math.cos(Math.toRadians(yaw));
        double sinPitch = Math.sin(Math.toRadians(pitch)), cosPitch = Math.cos(Math.toRadians(pitch));
        for (int py = 0; py < height; py++) for (int px = 0; px < width; px++) {
            double sx = (2.0 * (px + .5) / width - 1) * Math.tan(fov / 2) * width / height;
            double sy = (1 - 2.0 * (py + .5) / height) * Math.tan(fov / 2);
            double[] v = {sx, sy * cosPitch - sinPitch, -cosPitch - sy * sinPitch};   // looking north (-z)
            double[] d = {v[0] * cosYaw - v[2] * sinYaw, v[1], v[0] * sinYaw + v[2] * cosYaw};
            double len = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
            d[0] /= len; d[1] /= len; d[2] /= len;
            double[] c;
            if (cz < 0) c = march(new double[]{cx, cy, cz}, d);
            else if (d[2] >= -1e-4) c = new double[]{.12, .12, .12};
            else {
                double t = -cz / d[2];
                double[] hit = {cx + d[0] * t, cy + d[1] * t, 0};
                c = march(hit, d);
                if (hit[0] < 0 || hit[0] > 3 || hit[1] < 0 || hit[1] > 3) { c[0] *= .35; c[1] *= .35; c[2] *= .35; }   // outside the opening: dimmed
            }
            image.setRGB(px, py, ((int) (Math.min(1, c[0]) * 255) << 16) | ((int) (Math.min(1, c[1]) * 255) << 8) | (int) (Math.min(1, c[2]) * 255));
        }
        ImageIO.write(image, "png", new File(args[2]));
    }

    /** DDA through the voxel grid, like march() in magicportals.fsh. */
    static double[] march(double[] start, double[] d) {
        double[] o = {start[0] + d[0] * 1e-3, start[1] + d[1] * 1e-3, start[2] + d[2] * 1e-3};
        int[] cell = {(int) Math.floor(o[0]), (int) Math.floor(o[1]), Math.min((int) Math.floor(o[2]), -1)};
        int[] step = new int[3];
        double[] tDelta = new double[3], tMax = new double[3];
        for (int i = 0; i < 3; i++) {
            step[i] = (int) Math.signum(d[i]);
            tDelta[i] = Math.abs(1 / d[i]);
            tMax[i] = (cell[i] + Math.max(step[i], 0) - o[i]) / d[i];
        }
        double[] color = {0, 0, 0};
        double rest = 1, tRay = 0;
        int prevLight = 15;
        boolean prevFluid = false;
        for (int n = 0; n < 340; n++) {
            int[] v = voxel(cell[0], cell[1], cell[2]);
            if (v == null) break;
            int entry = v[0], level = v[1] & 15;
            boolean fluidHere = false;
            if (entry != 0) {
                int p0 = pal(entry, 0);
                int shape = b8(p0, 16);
                boolean fluid = (b8(p0, 8) & 1) != 0;
                fluidHere = fluid;
                double[] local = {o[0] + d[0] * tRay - cell[0], o[1] + d[1] * tRay - cell[1], o[2] + d[2] * tRay - cell[2]};
                int tint = pal(entry, 3), tintMask = b8(pal(entry, 4), 16);
                if (shape == 1 && !(fluid && prevFluid)) {
                    int lo = pal(entry, 1), hi = pal(entry, 2);
                    double[] min = {b8(lo, 16) / 16.0, b8(lo, 8) / 16.0, b8(lo, 0) / 16.0}, max = {b8(hi, 16) / 16.0, b8(hi, 8) / 16.0, b8(hi, 0) / 16.0};
                    if (fluid && (v[1] & 16) != 0) max[1] = 1;
                    double near = -1e9, far = 1e9;
                    int face = 0;
                    for (int i = 0; i < 3; i++) {
                        double t0 = (min[i] - local[i]) / d[i], t1 = (max[i] - local[i]) / d[i];
                        double tn = Math.min(t0, t1), tf = Math.max(t0, t1);
                        if (tn > near) { near = tn; face = i == 0 ? (d[0] > 0 ? 5 : 4) : i == 1 ? (d[1] > 0 ? 1 : 0) : (d[2] > 0 ? 2 : 3); }
                        far = Math.min(far, tf);
                    }
                    if (near <= far && far >= 0) {
                        near = Math.max(near, 0);
                        double[] p = new double[3];
                        for (int i = 0; i < 3; i++) p[i] = Math.min(.9999, Math.max(0, local[i] + d[i] * near));
                        double[] uv = faceUV(face, p);
                        int tex = atlasTexel(tile(entry, face), uv[0], uv[1]);
                        if (((tex >>> 24) & 255) > 127) {
                            double[] c = tinted(tex, tint, ((tintMask >> face) & 1) != 0);
                            double bright = faceShade(face) * lightFactor(face == 0 || fluid ? Math.max(level, prevLight) : prevLight);
                            double fog = Math.max(0, Math.min(1, (tRay + near - D * .5) / (D * .47)));
                            fog = fog * fog * (3 - 2 * fog);
                            double[] s = sky(d);
                            for (int i = 0; i < 3; i++) c[i] = c[i] * bright * (1 - fog) + s[i] * fog;
                            if (fluid) { for (int i = 0; i < 3; i++) color[i] += rest * .62 * c[i]; rest *= .38; }
                            else { for (int i = 0; i < 3; i++) color[i] += rest * c[i]; return color; }
                        }
                    }
                } else if (shape == 2) {   // plants: two crossed planes
                    double best = 1e9;
                    double[] bestColor = null;
                    for (int e = 0; e < 2; e++) {
                        double nx = .7071, nz = e == 0 ? -.7071 : .7071, planeD = e == 0 ? 0 : .7071;
                        double dn = d[0] * nx + d[2] * nz;
                        if (Math.abs(dn) < 1e-5) continue;
                        double t = (planeD - (local[0] * nx + local[2] * nz)) / dn;
                        if (t < 0 || t >= best) continue;
                        double[] p = {local[0] + d[0] * t, local[1] + d[1] * t, local[2] + d[2] * t};
                        if (p[0] < 0 || p[1] < 0 || p[2] < 0 || p[0] > 1 || p[1] > 1 || p[2] > 1) continue;
                        int tex = atlasTexel(tile(entry, 2), e == 0 ? p[0] : 1 - p[0], 1 - p[1]);
                        if (((tex >>> 24) & 255) < 128) continue;
                        double[] c = tinted(tex, tint, (tintMask & 1) != 0);
                        for (int i = 0; i < 3; i++) c[i] *= .85 * lightFactor(level);
                        best = t;
                        bestColor = c;
                    }
                    if (bestColor != null) { for (int i = 0; i < 3; i++) color[i] += rest * bestColor[i]; return color; }
                }
            }
            prevFluid = fluidHere;
            if (entry == 0 || fluidHere) prevLight = level;
            if (tMax[0] < tMax[1] && tMax[0] < tMax[2]) { tRay = tMax[0]; tMax[0] += tDelta[0]; cell[0] += step[0]; }
            else if (tMax[1] < tMax[2]) { tRay = tMax[1]; tMax[1] += tDelta[1]; cell[1] += step[1]; }
            else { tRay = tMax[2]; tMax[2] += tDelta[2]; cell[2] += step[2]; }
        }
        double[] s = sky(d);
        for (int i = 0; i < 3; i++) color[i] += rest * s[i];
        return color;
    }
}
