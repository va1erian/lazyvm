package lazy.ui;

/** A widget that shows a {@link Drawing}; repaints the window causes cost no bytecode. */
public final class Canvas extends Widget {
    private final int width, height;
    private Drawing drawing;

    Canvas(int width, int height, Drawing drawing) {
        this.width = length(width);
        this.height = length(height);
        this.drawing = Ui.nonNull(drawing, "drawing");
    }

    public Drawing drawing() {
        return drawing;
    }

    /** Shows {@code drawing} from the next paint on. */
    public Canvas drawing(Drawing drawing) {
        Ui.nonNull(drawing, "drawing");
        if (mounted()) {
            Encoder out = new Encoder();
            drawing.encode(out);
            call(Op.DRAWING, new Object[] {out.ops(), out.objects()});
        }
        this.drawing = drawing;
        return this;
    }

    @Override
    void encode(Encoder out) {
        head(out, Encoder.CANVAS);
        out.op(width);
        out.op(height);
        drawing.encode(out);
    }
}
