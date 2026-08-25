import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.List;
import java.util.ArrayList;

/**
 * Draws the app mark and writes every size the three installers ask for.
 *
 *   java tools/icon/GenerateIcon.java <output-dir>
 *
 * Run from the repo root. Regenerates `desktop/src/main/resources/icon/`; the outputs are
 * committed, because a build must not depend on this having been run.
 *
 * <h2>The mark</h2>
 * A lens over records. A stack of bars stands for what the app reads — a table described by rows
 * of metadata — and a ring set down on them stands for reading it: under the ring the bars are
 * accent-coloured, outside it they sit back. Two shapes, which is about what survives being drawn
 * at 32 pixels.
 *
 * <p>Deliberately not an iceberg and not Apache Iceberg's mark. The project reads Paimon too, and
 * the name is already an open trademark question (see TODO.md) — an icon quoting a foundation's
 * logo would be a second one.
 *
 * <p>Everything is proportional to the canvas, so the 16px and the 1024px renders are the same
 * drawing rather than two drawings that resemble each other.
 */
public final class GenerateIcon {

    // Same family as the app's own dark surface: a cool slate that a dock does not fight with.
    private static final Color FIELD_TOP = new Color(0x24, 0x2C, 0x38);
    private static final Color FIELD_BOTTOM = new Color(0x16, 0x1B, 0x23);
    private static final Color STRATA_DIM = new Color(0x6B, 0x7A, 0x90);
    private static final Color STRATA_LIT = new Color(0x7F, 0xB8, 0xF7);
    private static final Color RING = new Color(0xE8, 0xEE, 0xF6);

    public static void main(String[] args) throws Exception {
        Path out = Paths.get(args.length > 0 ? args[0] : "desktop/src/main/resources/icon");
        Files.createDirectories(out);

        int[] sizes = {16, 32, 64, 128, 256, 512, 1024};
        for (int size : sizes) {
            ImageIO.write(draw(size), "png", out.resolve("icon-" + size + ".png").toFile());
        }
        // Linux takes a bare PNG; jpackage wants one file, and 512 is the size it scales from.
        Files.copy(out.resolve("icon-512.png"), out.resolve("icon.png"), StandardCopyOption.REPLACE_EXISTING);
        writeIco(out.resolve("icon.ico"), new int[]{16, 32, 64, 128, 256});
        writeIcns(out.resolve("icon.icns"));
        System.out.println("wrote " + out.toAbsolutePath());
    }

    static BufferedImage draw(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        double s = size;
        // macOS leaves the tile short of the full square; the other platforms crop nothing, and a
        // margin here is what stops the mark touching a neighbouring dock icon.
        double inset = s * 0.055;
        double tile = s - 2 * inset;
        Shape field = new RoundRectangle2D.Double(inset, inset, tile, tile, tile * 0.235, tile * 0.235);

        g.setPaint(new GradientPaint(0, (float) inset, FIELD_TOP, 0, (float) (inset + tile), FIELD_BOTTOM));
        g.fill(field);

        // Strata. Five bars of unequal width, left-aligned, so the shape reads as records rather
        // than as the three-identical-bars hamburger every reader already knows.
        Shape clip = g.getClip();
        g.setClip(field);
        double barHeight = tile * 0.098;
        double gap = tile * 0.052;
        double[] widths = {0.70, 0.52, 0.78, 0.44, 0.62};
        double stackHeight = widths.length * barHeight + (widths.length - 1) * gap;
        double left = inset + tile * 0.13;
        double top = inset + (tile - stackHeight) / 2.0;
        List<Shape> bars = new ArrayList<>();
        for (int i = 0; i < widths.length; i++) {
            double y = top + i * (barHeight + gap);
            double w = tile * widths[i];
            bars.add(new RoundRectangle2D.Double(left, y, w, barHeight, barHeight * 0.45, barHeight * 0.45));
        }
        g.setColor(STRATA_DIM);
        bars.forEach(g::fill);

        // The lens sits off-centre, over the lower right of the stack. Concentric reads as a
        // target or a record label; placed to one side it reads as an instrument put down on
        // something, which is what the app does.
        double lensR = tile * 0.255;
        double lensCx = inset + tile * 0.615;
        double lensCy = inset + tile * 0.605;
        Shape lens = new Ellipse2D.Double(lensCx - lensR, lensCy - lensR, lensR * 2, lensR * 2);

        // The same bars again, lit, clipped to the lens. Drawing them twice rather than masking is
        // what makes the part being looked at the brightest thing on the tile.
        g.setClip(lens);
        g.setColor(STRATA_LIT);
        bars.forEach(g::fill);

        g.setClip(field);
        double stroke = Math.max(1.0, tile * 0.042);
        g.setColor(RING);
        g.setStroke(new BasicStroke((float) stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(lens);

        g.setClip(clip);
        g.dispose();
        return image;
    }

    /**
     * A minimal ICO holding PNG payloads.
     *
     * Windows has read PNG-in-ICO since Vista, so the container is a 6-byte header plus a 16-byte
     * directory entry per size — small enough to write here and avoid a build dependency whose
     * only job is repacking images this program already produced.
     */
    static void writeIco(Path target, int[] sizes) throws IOException {
        List<byte[]> payloads = new ArrayList<>();
        for (int size : sizes) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            ImageIO.write(draw(size), "png", buffer);
            payloads.add(buffer.toByteArray());
        }
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(target)))) {
            writeShortLE(out, 0);              // reserved
            writeShortLE(out, 1);              // type: icon
            writeShortLE(out, sizes.length);
            int offset = 6 + 16 * sizes.length;
            for (int i = 0; i < sizes.length; i++) {
                // 256 is stored as 0; the field is one byte and the format predates larger icons.
                out.writeByte(sizes[i] >= 256 ? 0 : sizes[i]);
                out.writeByte(sizes[i] >= 256 ? 0 : sizes[i]);
                out.writeByte(0);              // palette size
                out.writeByte(0);              // reserved
                writeShortLE(out, 1);          // colour planes
                writeShortLE(out, 32);         // bits per pixel
                writeIntLE(out, payloads.get(i).length);
                writeIntLE(out, offset);
                offset += payloads.get(i).length;
            }
            for (byte[] payload : payloads) out.write(payload);
        }
    }

    /**
     * An ICNS holding PNG payloads, written here for the same reason as the ICO.
     *
     * `iconutil` would do it in one line and only exists on macOS, which would make the Windows
     * and Linux icons regenerable on a Mac and nowhere else. The container is a magic word, a
     * length, and one typed chunk per size.
     *
     * <p>The four-letter types are the format's own names for a size, and the retina variants
     * repeat a pixel count under a second name — `ic13` and `ic08` are both 256 pixels, one
     * meaning "128 at 2x" and the other "256 at 1x". macOS picks by name, so both must be there.
     */
    static void writeIcns(Path target) throws IOException {
        String[] types = {"ic11", "ic12", "ic07", "ic13", "ic08", "ic14", "ic09", "ic10"};
        int[] pixels = {32, 64, 128, 256, 256, 512, 512, 1024};

        List<byte[]> chunks = new ArrayList<>();
        int total = 8;
        for (int i = 0; i < types.length; i++) {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(draw(pixels[i]), "png", png);
            ByteArrayOutputStream chunk = new ByteArrayOutputStream();
            chunk.write(types[i].getBytes("US-ASCII"));
            writeIntBE(chunk, png.size() + 8);
            png.writeTo(chunk);
            chunks.add(chunk.toByteArray());
            total += chunk.size();
        }
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(target))) {
            out.write("icns".getBytes("US-ASCII"));
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            writeIntBE(header, total);
            header.writeTo(out);
            for (byte[] chunk : chunks) out.write(chunk);
        }
    }

    private static void writeIntBE(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeShortLE(DataOutputStream out, int value) throws IOException {
        out.writeByte(value & 0xFF);
        out.writeByte((value >>> 8) & 0xFF);
    }

    private static void writeIntLE(DataOutputStream out, int value) throws IOException {
        out.writeByte(value & 0xFF);
        out.writeByte((value >>> 8) & 0xFF);
        out.writeByte((value >>> 16) & 0xFF);
        out.writeByte((value >>> 24) & 0xFF);
    }
}
