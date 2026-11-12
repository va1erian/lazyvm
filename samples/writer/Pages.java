import java.util.function.Consumer;
import java.util.function.Predicate;

import lazy.ui.Menu;

/**
 * The Page setup menu: paper (A4 or Letter), orientation and margins (Normal, Narrow, Wide).
 * Pages are in hundredths of a millimetre: width, height, left, top, right, bottom.
 */
final class Pages {
    private final Writer app;
    private final Menu menu;
    /** The document's page as the menu opened, or null when no choice describes it. */
    private Choice shown;

    /** One choice per group. */
    static final class Choice {
        boolean letter, landscape;
        /** 0 Normal, 1 Narrow, 2 Wide. */
        int margins;

        int[] page() {
            int w = letter ? 21590 : 21000, h = letter ? 27940 : 29700, normal = letter ? 2540 : 2500;
            int side = margins == 1 ? 1270 : margins == 2 ? 5080 : normal;
            int topBottom = margins == 1 ? 1270 : normal;
            return landscape ? new int[] {h, w, side, topBottom, side, topBottom}
                : new int[] {w, h, side, topBottom, side, topBottom};
        }

        /** The choice that gives {@code page}, or null when none does. */
        static Choice of(int[] page) {
            for (int i = 0; i < 12; i++) {
                Choice c = new Choice();
                c.letter = i >= 6;
                c.landscape = i % 6 >= 3;
                c.margins = i % 3;
                if (same(c.page(), page)) return c;
            }
            return null;
        }

        private static boolean same(int[] a, int[] b) {
            for (int i = 0; i < 6; i++) if (Math.abs(a[i] - b[i]) > 2) return false;
            return true;
        }
    }

    Pages(final Writer app) {
        this.app = app;
        menu = app.window.menu()
            .submenu("Paper", null, m -> m
                .radio("A4 (210 x 297 mm)", () -> is(c -> !c.letter), () -> pick(c -> c.letter = false))
                .radio("Letter (8.5 x 11 in)", () -> is(c -> c.letter), () -> pick(c -> c.letter = true)))
            .submenu("Orientation", null, m -> m
                .radio("Portrait", () -> is(c -> !c.landscape), () -> pick(c -> c.landscape = false))
                .radio("Landscape", () -> is(c -> c.landscape), () -> pick(c -> c.landscape = true)))
            .submenu("Margins", null, m -> m
                .radio("Normal", () -> is(c -> c.margins == 0), () -> pick(c -> c.margins = 0))
                .radio("Narrow", () -> is(c -> c.margins == 1), () -> pick(c -> c.margins = 1))
                .radio("Wide", () -> is(c -> c.margins == 2), () -> pick(c -> c.margins = 2)));
    }

    /** Shows the menu under its button, the document's page checked. */
    void show() {
        shown = Choice.of(app.editor.page());
        menu.show(app.tools.pageSetup);
    }

    private boolean is(Predicate<Choice> test) {
        return shown != null && test.test(shown);
    }

    /**
     * Changes one part of the page, as one undoable step. A page no choice describes starts from
     * A4 with Normal margins, in its own orientation.
     */
    private void pick(Consumer<Choice> change) {
        int[] page = app.editor.page();
        Choice c = Choice.of(page);
        if (c == null) {
            c = new Choice();
            c.landscape = page[0] > page[1];
        }
        change.accept(c);
        app.editor.page(c.page());
        app.refreshPages();
        app.editor.focus();
    }
}
