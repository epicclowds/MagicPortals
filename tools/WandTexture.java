import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/* Draws the Portal Wand item texture: a dark staff with gold bands and a glowing violet-cyan crystal,
 * 16x16, 8 animation frames stacked vertically (the crystal pulses, sparkles twinkle).
 *
 * Usage: java tools/WandTexture.java src/main/resources/pack/assets/magicportals/textures/item/wand.png */
public final class WandTexture {
    static final int FRAMES = 8;

    static int rgb(int r, int g, int b) { return 0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b); }
    static int clamp(int v) { return Math.max(0, Math.min(255, v)); }
    static int mix(int a, int b, double t) {
        int r = (int) Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) Math.round((a & 255) * (1 - t) + (b & 255) * t);
        return rgb(r, g, bl);
    }
    static int brighten(int c, double f) {
        return rgb((int) (((c >> 16) & 255) * f), (int) (((c >> 8) & 255) * f), (int) ((c & 255) * f));
    }

    public static void main(String[] args) throws Exception {
        var img = new BufferedImage(16, 16 * FRAMES, BufferedImage.TYPE_INT_ARGB);
        int outline = rgb(24, 12, 38), woodDark = rgb(58, 34, 70), wood = rgb(92, 56, 108), woodLight = rgb(128, 84, 146);
        int gold = rgb(240, 190, 70), goldDark = rgb(170, 120, 40);
        int violet = rgb(168, 85, 247), magenta = rgb(232, 121, 249), cyan = rgb(56, 189, 248), white = rgb(255, 255, 255);

        for (int f = 0; f < FRAMES; f++) {
            int oy = f * 16;
            double pulse = 0.85 + 0.15 * Math.sin(f / (double) FRAMES * Math.PI * 2);
            // staff: diagonal from the bottom-left corner up to the crystal, 2 px thick with a dark outline
            for (int i = 0; i <= 8; i++) {
                int x = 1 + i, y = 14 - i;
                set(img, x - 1, oy + y, outline); set(img, x - 1, oy + y + 1, outline); set(img, x, oy + y + 1, outline);
                set(img, x + 1, oy + y - 1, outline); set(img, x + 2, oy + y - 1, outline);
            }
            for (int i = 0; i <= 8; i++) {
                int x = 1 + i, y = 14 - i;
                set(img, x, oy + y, i % 3 == 0 ? woodLight : wood);
                set(img, x + 1, oy + y, woodDark);
            }
            // gold bands
            set(img, 3, oy + 12, gold); set(img, 4, oy + 12, goldDark);
            set(img, 6, oy + 9, gold); set(img, 7, oy + 9, goldDark);
            // crystal: an elongated gem along the staff direction, violet at the base, cyan at the tip
            double cx = 11.6, cy = 4.4, ax = 0.7071, ay = -0.7071;   // axis towards the top right
            for (int y = 0; y <= 10; y++) for (int x = 6; x <= 15; x++) {
                double px = x + 0.5 - cx, py = y + 0.5 - cy;
                double along = px * ax + py * ay, across = px * -ay + py * ax;
                double d = Math.abs(along) / 4.6 + Math.abs(across) / 2.5;
                if (d > 1.0) continue;
                double t = (along + 4.6) / 9.2;                      // 0 base … 1 tip
                int c = t < 0.5 ? mix(violet, magenta, t * 2) : mix(magenta, cyan, (t - 0.5) * 2);
                double facet = across > 0 ? 1.12 : 0.88;              // two facets, light from the top left
                c = brighten(c, pulse * facet * (1.2 - 0.4 * d));
                if (d > 0.8) c = mix(c, outline, 0.6);               // dark rim
                set(img, x, oy + y, c);
            }
            // gold claw at the base of the crystal
            set(img, 9, oy + 6, gold); set(img, 10, oy + 7, goldDark); set(img, 8, oy + 5, goldDark);
            // highlights
            set(img, 12, oy + 2, white); set(img, 11, oy + 3, mix(white, magenta, 0.35)); set(img, 13, oy + 2, mix(white, cyan, 0.5));
            // sparkles around the tip, twinkling frame by frame
            int[][] sparks = {{15, 0}, {15, 7}, {9, 1}, {14, 9}, {7, 3}};
            for (int s = 0; s < sparks.length; s++) {
                double tw = Math.sin((f + s * 1.7) / (double) FRAMES * Math.PI * 2);
                if (tw > 0.35) set(img, sparks[s][0], oy + sparks[s][1], mix(cyan, white, tw));
            }
        }
        File out = new File(args[0]);
        out.getParentFile().mkdirs();
        ImageIO.write(img, "png", out);
    }

    static void set(BufferedImage img, int x, int y, int c) {
        if (x >= 0 && x < 16 && y >= 0 && y < img.getHeight()) img.setRGB(x, y, c);
    }
}
