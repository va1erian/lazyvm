package lazy.ui;

import java.util.ArrayList;
import java.util.function.IntConsumer;

/** Pages of content, one shown at a time, picked by a row of tabs. */
public final class Tabs extends Widget {
    private final ArrayList<String> titles = new ArrayList<String>();
    private final ArrayList<Layout> pages = new ArrayList<Layout>();
    private IntConsumer onChange;

    Tabs() {}

    /** Adds a page titled {@code title}, before the window opens. */
    public Tabs page(String title, Layout content) {
        if (mounted()) throw new IllegalStateException("add pages before the window opens");
        titles.add(Ui.nonNull(title, "title"));
        pages.add(Ui.nonNull(content, "content"));
        return this;
    }

    /** The page shown; the user changes it, so it is read from the window. */
    public int selected() {
        return mounted() ? callInt(Op.GET_SELECTED) : 0;
    }

    /** Shows page {@code index}. */
    public Tabs select(int index) {
        if (index < 0 || index >= pages.size()) throw new IndexOutOfBoundsException("page " + index);
        call(Op.SELECT, index, 0, null);
        return this;
    }

    /** Runs {@code action} with the page the user picks. */
    public Tabs onChange(IntConsumer action) {
        if (mounted()) throw new IllegalStateException("set onChange before the window opens");
        onChange = Ui.nonNull(action, "action");
        return this;
    }

    @Override
    void encode(Encoder out) {
        head(out, Encoder.TABS);
        out.op(pages.size());
        for (int i = 0; i < pages.size(); i++) {
            out.string(titles.get(i));
            pages.get(i).encode(out);
        }
        out.flag(onChange != null);
    }

    @Override
    void fire(int kind, int i, double d, String s) {
        if (kind == Ui.SELECT && onChange != null) onChange.accept(i);
    }

    @Override
    void unmount() {
        super.unmount();
        for (Layout page : pages) page.unmount();
    }
}
