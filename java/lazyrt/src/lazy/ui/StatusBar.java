package lazy.ui;

/** A bar along the bottom of a window, of a fixed number of text parts. */
public final class StatusBar extends Widget {
    private final String[] parts;

    StatusBar(String[] parts) {
        if (parts.length == 0) throw new IllegalArgumentException("a status bar needs parts");
        this.parts = parts.clone();
        for (String p : this.parts) Ui.nonNull(p, "part");
    }

    /** The text of part {@code index}. */
    public String text(int index) {
        return parts[check(index)];
    }

    /** Shows {@code text} in part {@code index}. */
    public StatusBar text(int index, String text) {
        check(index);
        Ui.nonNull(text, "text");
        if (mounted()) call(Op.PART, index, 0, text);
        parts[index] = text;
        return this;
    }

    private int check(int index) {
        if (index < 0 || index >= parts.length) throw new IndexOutOfBoundsException("part " + index);
        return index;
    }

    @Override
    void encode(Encoder out) {
        head(out, Encoder.STATUS);
        out.strings(parts);
    }
}
