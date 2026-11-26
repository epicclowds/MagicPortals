import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/* Renders the MagicPortals logo (same design as docs/images/logo.svg) to PNG.
 * Usage: java tools/LogoRender.java <output.png> <size> */
public final class LogoRender {
    static final int[][] RISS = {{128,22},{141,50},{133,70},{153,95},{145,118},{167,142},{150,167},{157,190},{139,213},{128,236},
            {119,212},{107,187},{114,162},{92,138},{106,112},{97,88},{114,64},{110,44}};

    public static void main(String[] a) throws Exception {
        int groesse = Integer.parseInt(a[1]);
        var bild = new BufferedImage(groesse, groesse, BufferedImage.TYPE_INT_ARGB);
        var g = bild.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.scale(groesse / 256.0, groesse / 256.0);

        // background
        g.setPaint(new RadialGradientPaint(new Point2D.Float(128, 108), 180, new float[]{0f, .55f, 1f},
                new Color[]{new Color(0x2a1d5c), new Color(0x141436), new Color(0x07081a)}));
        g.fill(new RoundRectangle2D.Float(0, 0, 256, 256, 88, 88));
        g.setClip(new RoundRectangle2D.Float(0, 0, 256, 256, 88, 88));
        g.setColor(new Color(255, 255, 255, 210));
        for (float[] s : new float[][]{{40,46,1.6f},{66,96,1.1f},{208,58,1.8f},{196,150,1.2f},{52,186,1.3f},{216,206,1.5f},{84,30,1f},{176,30,1.1f},{30,132,1.1f},{228,110,1f}})
            g.fill(new Ellipse2D.Float(s[0] - s[2], s[1] - s[2], s[2] * 2, s[2] * 2));

        var form = new Path2D.Float();
        form.moveTo(RISS[0][0], RISS[0][1]);
        for (int i = 1; i < RISS.length; i++) form.lineTo(RISS[i][0], RISS[i][1]);
        form.closePath();
        var kante = new LinearGradientPaint(new Point2D.Float(110, 22), new Point2D.Float(150, 236), new float[]{0f, .3f, .65f, 1f},
                new Color[]{new Color(0xffd35a), new Color(0xff4df2), new Color(0x40d9ff), new Color(0xff4df2)});

        // outer glow: wide, faint strokes
        for (int i = 14; i >= 1; i--) {
            g.setComposite(AlphaComposite.SrcOver.derive(0.035f));
            g.setPaint(kante);
            g.setStroke(new BasicStroke(i * 2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(form);
        }
        g.setComposite(AlphaComposite.SrcOver);

        // destination world inside
        var alt = g.getClip();
        g.clip(form);
        g.setPaint(new GradientPaint(0, 20, new Color(0xbfe6ff), 0, 160, new Color(0x8fcdf5)));
        g.fillRect(80, 20, 96, 140);
        g.setColor(new Color(0x5fb24f));
        g.fillPolygon(new int[]{80, 100, 118, 138, 176, 176, 80}, new int[]{160, 150, 156, 146, 154, 240, 240}, 7);
        g.setColor(new Color(0x6b4a2b)); g.fillRect(133, 122, 8, 28);
        g.setColor(new Color(0x2f7d33)); g.fillRect(120, 104, 34, 22);
        g.setColor(new Color(0x3a8c3c)); g.fillRect(126, 96, 22, 10);
        g.setColor(new Color(63, 143, 58, 150)); g.fillRect(80, 184, 96, 60);
        g.setColor(new Color(255, 255, 255, 215)); g.fillRect(98, 46, 22, 7); g.fillRect(110, 41, 18, 6);
        g.setClip(alt);

        // bright edge
        for (int i = 4; i >= 1; i--) {
            g.setComposite(AlphaComposite.SrcOver.derive(0.25f));
            g.setPaint(kante);
            g.setStroke(new BasicStroke(i * 2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(form);
        }
        g.setComposite(AlphaComposite.SrcOver);
        g.setPaint(kante); g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_MITER)); g.draw(form);
        g.setColor(new Color(255, 255, 255, 240)); g.setStroke(new BasicStroke(1.4f)); g.draw(form);

        // lightning arcs
        bogen(g, new int[]{166, 182, 178, 196}, new int[]{142, 134, 124, 116}, new Color(0xff4df2));
        bogen(g, new int[]{92, 76, 82, 64}, new int[]{138, 146, 156, 166}, new Color(0x40d9ff));
        bogen(g, new int[]{141, 156, 152}, new int[]{50, 40, 30}, new Color(0xffd35a));

        // sparkles
        g.setColor(Color.WHITE);
        stern(g, 190, 90, 8); stern(g, 70, 78, 6); stern(g, 180, 201, 5.3f);
        g.dispose();
        ImageIO.write(bild, "png", new File(a[0]));
    }

    static void bogen(Graphics2D g, int[] x, int[] y, Color farbe) {
        for (int i = 3; i >= 1; i--) {
            g.setColor(new Color(farbe.getRed(), farbe.getGreen(), farbe.getBlue(), 70));
            g.setStroke(new BasicStroke(i * 2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawPolyline(x, y, x.length);
        }
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawPolyline(x, y, x.length);
    }

    static void stern(Graphics2D g, float cx, float cy, float r) {
        var p = new Path2D.Float();
        p.moveTo(cx, cy - r); p.lineTo(cx + r * .25f, cy - r * .25f); p.lineTo(cx + r, cy); p.lineTo(cx + r * .25f, cy + r * .25f);
        p.lineTo(cx, cy + r); p.lineTo(cx - r * .25f, cy + r * .25f); p.lineTo(cx - r, cy); p.lineTo(cx - r * .25f, cy - r * .25f);
        p.closePath(); g.fill(p);
    }
}
