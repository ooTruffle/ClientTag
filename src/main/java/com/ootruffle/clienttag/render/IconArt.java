package com.ootruffle.clienttag.render;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import org.apache.logging.log4j.LogManager;

/**
 * Paints the white icon shapes (tinted at draw time) into {@link ClientIcon#TEXTURE_SIZE}
 * square textures, 4x4 supersampled for smooth edges. Shapes are described in unit
 * coordinates, (0, 0) top-left to (1, 1) bottom-right.
 */
public final class IconArt {

    private static final int SAMPLES = 4;

    @FunctionalInterface
    private interface Shape {
        boolean contains(double x, double y);
    }

    private IconArt() {}

    /**
     * Build-time only (Gradle's generateIconFont task): writes the drawn icons as PNGs into
     * the directory given as the only argument, as the glyph textures of the modern versions'
     * {@code clienttag:icons} font. Doesn't touch ClientIcon, whose color lookups need the game.
     */
    public static void main(String[] args) throws Exception {
        final Map<String, Consumer<int[]>> icons = new LinkedHashMap<>();
        icons.put("lunar", IconArt::crescent);
        icons.put("dawn", IconArt::sunrise);
        icons.put("essential", IconArt::sparkle);
        icons.put("norisk", IconArt::shield);
        icons.put("labymod", IconArt::wolf);
        icons.put("clienttag", IconArt::nametag);

        final File dir = new File(args[0]);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Couldn't create " + dir);
        }
        final int size = ClientIcon.TEXTURE_SIZE;
        for (Map.Entry<String, Consumer<int[]>> icon : icons.entrySet()) {
            final int[] pixels = new int[size * size];
            icon.getValue().accept(pixels);
            final BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0, 0, size, size, pixels, 0, size);
            ImageIO.write(image, "png", new File(dir, icon.getKey() + ".png"));
        }
    }

    /** Paints a PNG from the mod jar, scaled to fit and centered; left blank if it can't be read. */
    static Consumer<int[]> image(String resource) {
        return pixels -> {
            final int size = ClientIcon.TEXTURE_SIZE;
            try (InputStream in = IconArt.class.getResourceAsStream(resource)) {
                final BufferedImage source = in == null ? null : ImageIO.read(in);
                if (source == null) {
                    throw new IllegalStateException("missing " + resource);
                }
                final double scale = Math.min((double) size / source.getWidth(), (double) size / source.getHeight());
                final int w = (int) Math.round(source.getWidth() * scale), h = (int) Math.round(source.getHeight() * scale);
                final BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
                final Graphics2D g = scaled.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.drawImage(source, (size - w) / 2, (size - h) / 2, w, h, null);
                g.dispose();
                scaled.getRGB(0, 0, size, size, pixels, 0, size);
            } catch (Exception e) {
                LogManager.getLogger("ClientTag").warn("Couldn't load icon {}: {}", resource, e.toString());
            }
        };
    }

    /** A crescent: a full disc minus an offset disc. */
    static void crescent(int[] pixels) {
        paint(pixels, (x, y) -> inDisc(x, y, 0.5, 0.5, 0.46) && !inDisc(x, y, 0.68, 0.36, 0.36));
    }

    /** A rising sun: a half disc with three rays above a horizon bar. */
    static void sunrise(int[] pixels) {
        final double cx = 0.5, cy = 0.66;
        paint(pixels, (x, y) -> {
            if (x >= 0.04 && x <= 0.96 && y >= 0.74 && y <= 0.88) {
                return true;
            }
            if (y <= cy && inDisc(x, y, cx, cy, 0.27)) {
                return true;
            }
            for (double angle : new double[]{45, 90, 135}) {
                final double dx = Math.cos(Math.toRadians(angle)), dy = -Math.sin(Math.toRadians(angle));
                if (nearSegment(x, y, cx + dx * 0.37, cy + dy * 0.37, cx + dx * 0.52, cy + dy * 0.52, 0.065)) {
                    return true;
                }
            }
            return false;
        });
    }

    /** A four-pointed sparkle: an astroid star, sqrt|dx| + sqrt|dy| <= sqrt(r). */
    static void sparkle(int[] pixels) {
        final double r = Math.sqrt(0.47);
        paint(pixels, (x, y) -> Math.sqrt(Math.abs(x - 0.5)) + Math.sqrt(Math.abs(y - 0.5)) <= r);
    }

    /** A heater shield: straight sides that curve in to a point, with a dipped top edge. */
    static void shield(int[] pixels) {
        paint(pixels, (x, y) -> {
            final double dx = Math.abs(x - 0.5);
            // Top edge dips slightly toward the middle.
            if (y < 0.08 + 0.06 * (1 - dx / 0.38) || dx > 0.38) {
                return false;
            }
            if (y <= 0.5) {
                return true;
            }
            // Lower half: half-width falls off along a quarter cosine to the point at y = 0.95.
            final double t = (y - 0.5) / 0.45;
            return t <= 1 && dx <= 0.38 * Math.cos(t * Math.PI / 2);
        });
    }

    /** A front-facing wolf head: pointed ears, wide cheeks and a muzzle, with the eyes cut out. */
    static void wolf(int[] pixels) {
        final double[] xs = {0.14, 0.36, 0.64, 0.86, 0.92, 0.72, 0.50, 0.28, 0.08};
        final double[] ys = {0.06, 0.28, 0.28, 0.06, 0.54, 0.74, 0.96, 0.74, 0.54};
        paint(pixels, (x, y) -> inPolygon(x, y, xs, ys)
                && !inDisc(x, y, 0.36, 0.52, 0.065) && !inDisc(x, y, 0.64, 0.52, 0.065));
    }

    /** A luggage-style name tag pointing left, with a hole punched near the point. */
    static void nametag(int[] pixels) {
        final double[] xs = {0.04, 0.30, 0.96, 0.96, 0.30};
        final double[] ys = {0.50, 0.20, 0.20, 0.80, 0.80};
        paint(pixels, (x, y) -> inPolygon(x, y, xs, ys) && !inDisc(x, y, 0.27, 0.50, 0.08));
    }

    private static void paint(int[] pixels, Shape shape) {
        final int size = ClientIcon.TEXTURE_SIZE;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int hits = 0;
                for (int sy = 0; sy < SAMPLES; sy++) {
                    for (int sx = 0; sx < SAMPLES; sx++) {
                        if (shape.contains((x + (sx + 0.5) / SAMPLES) / size, (y + (sy + 0.5) / SAMPLES) / size)) {
                            hits++;
                        }
                    }
                }
                final int alpha = hits * 255 / (SAMPLES * SAMPLES);
                pixels[y * size + x] = alpha << 24 | 0xFFFFFF;
            }
        }
    }

    /** Even-odd point-in-polygon test. */
    private static boolean inPolygon(double x, double y, double[] xs, double[] ys) {
        boolean inside = false;
        for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
            if ((ys[i] > y) != (ys[j] > y) && x < (xs[j] - xs[i]) * (y - ys[i]) / (ys[j] - ys[i]) + xs[i]) {
                inside = !inside;
            }
        }
        return inside;
    }

    private static boolean inDisc(double x, double y, double cx, double cy, double r) {
        return sq(x - cx) + sq(y - cy) <= sq(r);
    }

    /** Within {@code radius} of the segment (x1, y1)-(x2, y2), i.e. a line with round caps. */
    private static boolean nearSegment(double x, double y, double x1, double y1, double x2, double y2, double radius) {
        final double vx = x2 - x1, vy = y2 - y1;
        final double t = Math.max(0, Math.min(1, ((x - x1) * vx + (y - y1) * vy) / (vx * vx + vy * vy)));
        return inDisc(x, y, x1 + vx * t, y1 + vy * t, radius);
    }

    private static double sq(double v) {
        return v * v;
    }

}
