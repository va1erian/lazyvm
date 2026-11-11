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
    public Widget enabled(boolean on) {
        nodeless();
        return super.enabled(on);
    }

    @Override
    public Widget visible(boolean on) {
        nodeless();
        return super.visible(on);
    }

    @Override
    public Widget tooltip(String text) {
        nodeless();
        return super.tooltip(text);
    }

    /** A spacer is only room in its layout: it has no node to change. */
    private void nodeless() {
        if (kind == Encoder.SPACER) throw new UnsupportedOperationException("a spacer has no node");
    }

    @Override
    void encode(Encoder out) {
        head(out, kind);
        if (kind == Encoder.SEPARATOR) out.flag(vertical);
    }
}
