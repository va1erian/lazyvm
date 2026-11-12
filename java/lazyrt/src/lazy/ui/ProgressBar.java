package lazy.ui;

/** A bar that fills from 0 to its maximum. */
public final class ProgressBar extends Widget {
    private final int max;
    private int value;

    ProgressBar(int max) {
        if (max < 0) throw new IllegalArgumentException("max " + max);
        this.max = max;
    }

    public int value() {
        return value;
    }

    /** Fills the bar up to {@code value}, from 0 to the maximum. */
    public ProgressBar value(int value) {
        if (value < 0 || value > max) throw new IllegalArgumentException("value " + value + " of " + max);
        if (mounted()) call(Op.VALUE, value, 0, null);
        this.value = value;
        return this;
    }

    @Override
    void encode(Encoder out) {
        head(out, Encoder.PROGRESS);
        out.op(max);
        out.op(value);
    }
}
