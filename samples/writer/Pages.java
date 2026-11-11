import lazy.ui.Menu;

/**
 * The Page setup menu: paper (A4 or Letter), orientation and margins (Normal, Narrow, Wide).
 * Pages are in hundredths of a millimetre: width, height, left, top, right, bottom.
 */
final class Pages {
    // Menu entries: a submenu per group, then its radios.
    private static final int A4 = 1, LETTER = 2, PORTRAIT = 4, LANDSCAPE = 5, NORMAL = 7, NARROW = 8, WIDE = 9;
    private static final int[] RADIOS = {A4, LETTER, PORTRAIT, LANDSCAPE, NORMAL, NARROW, WIDE};

    private final Writer app;
    private final Menu menu;

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

        boolean checks(int entry) {
            switch (entry) {
                case A4: return !letter;
                case LETTER: return letter;
                case PORTRAIT: return !landscape;
                case LANDSCAPE: return landscape;
                case NORMAL: return margins == 0;
                case NARROW: return margins == 1;
                default: return margins == 2;
            }
        }
    }

    Pages(final Writer app) {
        this.app = app;
        menu = app.window.menu()
            .submenu("Paper", null, m -> m.radio("A4 (210 x 297 mm)", false, on -> pick(A4))
                .radio("Letter (8.5 x 11 in)", false, on -> pick(LETTER)))
            .submenu("Orientation", null, m -> m.radio("Portrait", false, on -> pick(PORTRAIT))
                .radio("Landscape", false, on -> pick(LANDSCAPE)))
            .submenu("Margins", null, m -> m.radio("Normal", false, on -> pick(NORMAL))
                .radio("Narrow", false, on -> pick(NARROW)).radio("Wide", false, on -> pick(WIDE)));
    }

    /** Shows the menu under its button, the document's page checked. */
    void show() {
        Choice c = Choice.of(app.editor.page());
        for (int entry : RADIOS) menu.checked(entry, c != null && c.checks(entry));
        menu.show(app.tools.pageSetup);
    }

    /**
     * Changes one part of the page, as one undoable step. A page no choice describes starts from
     * A4 with Normal margins, in its own orientation.
     */
    private void pick(int entry) {
        int[] page = app.editor.page();
        Choice c = Choice.of(page);
        if (c == null) {
            c = new Choice();
            c.landscape = page[0] > page[1];
        }
        if (entry == A4 || entry == LETTER) c.letter = entry == LETTER;
        else if (entry == PORTRAIT || entry == LANDSCAPE) c.landscape = entry == LANDSCAPE;
        else c.margins = entry - NORMAL;
        app.editor.page(c.page());
        app.refreshPages();
        app.editor.focus();
    }
}
