package lazy.ui;

import java.util.ArrayList;

/**
 * A recorded list of drawing commands, in design units from the canvas's top-left corner. A
 * {@link Canvas} replays it on every paint, so repaints run no bytecode; record a new drawing
 * when what it shows changes (docs/ui.md, "Drawing"). Colours are {@code 0xAARRGGBB}; the alpha
 * byte is ignored.
 */
public final class Drawing {
    // Opcodes, shared with crates/xui-bind/src/canvas.rs.
    private static final int CLEAR = 1, FILL_RECT = 2, STROKE_RECT = 3, LINE = 4;
    private static final int FILL_ELLIPSE = 5, FILL_ROUNDED = 6, TEXT = 7, IMAGE = 8;

    private int[] ops = new int[32];
    private int size;
    private final ArrayList<String> strings = new ArrayList<String>();
    private final ArrayList<int[]> images = new ArrayList<int[]>();

    public Drawing() {}

    private Drawing op(int... values) {
        while (size + values.length > ops.length) {
            int[] grown = new int[ops.length * 2];
            System.arraycopy(ops, 0, grown, 0, size);
            ops = grown;
        }
        System.arraycopy(values, 0, ops, size, values.length);
        size += values.length;
        return this;
    }

    /** Fills the whole canvas with {@code color}. */
    public Drawing clear(int color) {
        return op(CLEAR, color);
    }

    public Drawing fillRect(int x, int y, int width, int height, int color) {
        return op(FILL_RECT, x, y, width, height, color);
    }

    /** Outlines a rectangle with a line {@code lineWidth} design units wide. */
    public Drawing strokeRect(int x, int y, int width, int height, int color, int lineWidth) {
        return op(STROKE_RECT, x, y, width, height, color, lineWidth);
    }

    public Drawing line(int x1, int y1, int x2, int y2, int color, int lineWidth) {
        return op(LINE, x1, y1, x2, y2, color, lineWidth);
    }

    /** Fills the ellipse inside the rectangle. */
    public Drawing fillEllipse(int x, int y, int width, int height, int color) {
        return op(FILL_ELLIPSE, x, y, width, height, color);
    }

    public Drawing fillRoundedRect(int x, int y, int width, int height, int radius, int color) {
        return op(FILL_ROUNDED, x, y, width, height, radius, color);
    }

    /** Draws {@code text}, {@code size} design units tall, inside the rectangle. */
    public Drawing text(String text, int x, int y, int width, int height, int color, int size) {
        strings.add(Ui.nonNull(text, "text"));
        return op(TEXT, x, y, width, height, color, size, strings.size() - 1);
    }

    /**
     * Draws {@code pixels} ({@code 0xAARRGGBB}, row by row, {@code pixelWidth} by {@code
     * pixelHeight}) scaled into the rectangle. The pixels are copied now.
     */
    public Drawing image(int[] pixels, int pixelWidth, int pixelHeight, int x, int y, int width, int height) {
        Ui.nonNull(pixels, "pixels");
        if (pixelWidth <= 0 || pixelHeight <= 0 || (long) pixelWidth * pixelHeight != pixels.length)
            throw new IllegalArgumentException("image " + pixelWidth + "x" + pixelHeight + " for " + pixels.length + " pixels");
        images.add(new int[] {pixelWidth, pixelHeight});
        images.add(pixels.clone());
        return op(IMAGE, x, y, width, height, images.size() / 2 - 1);
    }

    /** Appends the drawing as the binding reads it: commands, strings, then images. */
    void encode(Encoder out) {
        int[] commands = new int[size];
        System.arraycopy(ops, 0, commands, 0, size);
        out.ints(commands);
        out.strings(strings.toArray(new String[0]));
        out.op(images.size() / 2);
        for (int i = 0; i < images.size(); i += 2) {
            out.op(Widget.length(images.get(i)[0]));
            out.op(Widget.length(images.get(i)[1]));
            out.ints(images.get(i + 1));
        }
    }
}
