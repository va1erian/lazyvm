package lazy.ui;

/** A separator or a spacer: nothing to change once shown. */
final class Plain extends Widget {
    private final int kind;
    private final boolean vertical;

    Plain(int kind, boolean vertical) {
        this.kind = kind;
        this.vertical = vertical;
    }

    @Override
    void encode(Encoder out) {
        head(out, kind);
        if (kind == Encoder.SEPARATOR) out.flag(vertical);
    }
}
