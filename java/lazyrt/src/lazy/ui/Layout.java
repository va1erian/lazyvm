package lazy.ui;

import java.util.ArrayList;

/**
 * A column, a row or a wrap of widgets. An arrangement has no node of its own in xui, so its
 * children are fixed once the window opens; put a {@link Panel} where content must change.
 */
public final class Layout extends Widget {
    /** xui's largest length, in design units. */
    static final int MAX_LENGTH = 32768;

    /** Alignments for {@link #align} and {@link #justify}. */
    public static final int START = 0, CENTER = 1, END = 2, STRETCH = 3;

    private final int kind;
    private int left, top, right, bottom, gap;
    private int align = -1, justify = -1;
    private final ArrayList<Widget> children = new ArrayList<Widget>();

    Layout(int kind) {
        this.kind = kind;
    }

    /** Space around the children, in design units. */
    public Layout padding(int padding) {
        return padding(padding, padding, padding, padding);
    }

    /** Space around the children on each side, in design units. */
    public Layout padding(int left, int top, int right, int bottom) {
        fixed();
        this.left = length(left);
        this.top = length(top);
        this.right = length(right);
        this.bottom = length(bottom);
        return this;
    }

    /** Space between the children, in design units. */
    public Layout gap(int gap) {
        fixed();
        this.gap = length(gap);
        return this;
    }

    /** Where children sit across the layout's direction: {@link #START} to {@link #STRETCH}. */
    public Layout align(int align) {
        fixed();
        this.align = alignment(align);
        return this;
    }

    /** Where the children sit along the layout's direction when they leave space over. */
    public Layout justify(int justify) {
        fixed();
        this.justify = alignment(justify);
        return this;
    }

    private static int alignment(int v) {
        if (v < START || v > STRETCH) throw new IllegalArgumentException("alignment " + v);
        return v;
    }

    /** Appends {@code widgets}, before the window opens. */
    public Layout add(Widget... widgets) {
        fixed();
        for (Widget w : widgets) children.add(Ui.nonNull(w, "widget"));
        return this;
    }

    private void fixed() {
        if (mounted()) throw new IllegalStateException("a layout is fixed once its window opens");
    }

    @Override
    void encode(Encoder out) {
        head(out, kind);
        out.op(left);
        out.op(top);
        out.op(right);
        out.op(bottom);
        out.op(gap);
        out.op(align);
        out.op(justify);
        out.op(children.size());
        for (Widget w : children) w.encode(out);
    }

    @Override
    void unmount() {
        // Children first: freed slots are reused last-in first-out, so a new tree
        // encoded in the same order takes the same slots.
        for (Widget w : children) w.unmount();
        super.unmount();
    }
}
