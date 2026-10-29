package lazy.ui;

import java.util.ArrayList;

/**
 * A column or a row of widgets. An arrangement has no node of its own in xui, so its children are
 * fixed once the window opens (docs/ui.md).
 */
public final class Layout extends Widget {
    /** xui's largest length, in design units. */
    private static final int MAX_LENGTH = 32768;

    private final int kind;
    private int padding, gap;
    private final ArrayList<Widget> children = new ArrayList<Widget>();

    Layout(int kind) {
        this.kind = kind;
    }

    /** Space around the children, in design units. */
    public Layout padding(int padding) {
        this.padding = length(padding);
        return this;
    }

    /** Space between the children, in design units. */
    public Layout gap(int gap) {
        this.gap = length(gap);
        return this;
    }

    /** Appends {@code widgets}, before the window opens. */
    public Layout add(Widget... widgets) {
        fixed();
        for (Widget w : widgets) children.add(Ui.nonNull(w, "widget"));
        return this;
    }

    private int length(int v) {
        fixed();
        if (v < 0 || v > MAX_LENGTH) throw new IllegalArgumentException("length " + v);
        return v;
    }

    private void fixed() {
        if (mounted()) throw new IllegalStateException("a layout is fixed once its window opens");
    }

    @Override
    void encode(Encoder out) {
        out.node(kind, this);
        out.op(padding);
        out.op(gap);
        out.op(children.size());
        for (Widget w : children) w.encode(out);
    }
}
