package lazy.ui;

import java.util.function.DoubleConsumer;

/** A slider over a range of numbers. */
public final class Slider extends Widget {
    private final int min, max;
    private int value;
    private DoubleConsumer onChange;

    Slider(int min, int max) {
        if (min > max) throw new IllegalArgumentException("range " + min + ".." + max);
        this.min = min;
        this.max = max;
        this.value = min;
    }

    /** The value; the user changes it, so it is read from the window. */
    public double value() {
        return mounted() ? ((double[]) call(Op.GET_VALUE, null))[0] : value;
    }

    /** Moves the slider to {@code value}, without running the change action. */
    public Slider value(int value) {
        if (value < min || value > max) throw new IllegalArgumentException("value " + value);
        if (mounted()) call(Op.VALUE, 0, value, null);
        this.value = value;
        return this;
    }

    /** Runs {@code action} with the value as the user moves the slider. */
    public Slider onChange(DoubleConsumer action) {
        if (mounted()) throw new IllegalStateException("set onChange before the window opens");
        onChange = Ui.nonNull(action, "action");
        return this;
    }

    @Override
    void encode(Encoder out) {
        head(out, Encoder.SLIDER);
        out.op(min);
        out.op(max);
        out.op(value);
        out.flag(onChange != null);
    }

    @Override
    void fire(int kind, int i, double d, String s) {
        if (kind == Ui.SLIDE && onChange != null) onChange.accept(d);
    }
}
