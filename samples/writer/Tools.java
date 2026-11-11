import lazy.ui.Button;
import lazy.ui.ComboBox;
import lazy.ui.Layout;
import lazy.ui.RichText;
import lazy.ui.ToggleButton;
import lazy.ui.Ui;
import lazy.ui.Widget;

/** The formatting row, and how its controls follow the selection. */
final class Tools {
    /** The font sizes offered, in design units; a new document's text is 14. */
    static final double[] SIZES = {10, 12, 14, 16, 18, 24, 32, 48};
    static final int DEFAULT_SIZE = 2;
    static final String[] WRAPS = {"Inline", "Float left", "Float right", "Top and bottom"};
    /** The width of an icon-only button, and the gap between groups. */
    private static final int ICON = 32, GAP = 6;

    private final Writer app;
    final ComboBox block, family, size, wrap;
    /** Bold, italic, underline, strike-through. */
    final ToggleButton[] marks = new ToggleButton[4];
    /** Left, centre, right, justify. */
    final ToggleButton[] aligns = new ToggleButton[4];
    /** Bullets, numbers. */
    final ToggleButton[] lists = new ToggleButton[2];
    final ToggleButton pageView;
    final Button pageSetup, table;

    Tools(final Writer app) {
        this.app = app;
        block = Ui.comboBox(Writer.BLOCKS).icon(0, "Type").icon(1, "Heading1").icon(2, "Heading2")
            .icon(3, "Heading3").icon(4, "TextQuote").onSelect(i -> {
                app.editor.block(i);
                app.refocus();
            });
        family = Ui.comboBox(Writer.FAMILIES).onSelect(i -> {
            app.editor.family(i == 1 ? app.serif : i == 2 ? app.mono : null);
            app.refocus();
        });
        String[] sizes = new String[SIZES.length];
        for (int i = 0; i < sizes.length; i++) sizes[i] = Integer.toString((int) SIZES[i]);
        size = Ui.comboBox(sizes).select(DEFAULT_SIZE).onSelect(i -> {
            app.editor.size(SIZES[i]);
            app.refocus();
        });
        wrap = Ui.comboBox(WRAPS).onSelect(i -> {
            app.editor.wrap(i);
            app.refocus();
        });
        wrap.enabled(false);
        String[][] markIcons = {{"Bold", "Bold (Ctrl+B)"}, {"Italic", "Italic (Ctrl+I)"},
            {"Underline", "Underline (Ctrl+U)"}, {"Strikethrough", "Strikethrough"}};
        int[] markCommands = {RichText.BOLD, RichText.ITALIC, RichText.UNDERLINE, RichText.STRIKE};
        for (int i = 0; i < 4; i++) {
            final int c = markCommands[i];
            marks[i] = toggle(markIcons[i], () -> app.format(c));
        }
        String[][] alignIcons = {{"TextAlignStart", "Align left"}, {"TextAlignCenter", "Centre"},
            {"TextAlignEnd", "Align right"}, {"TextAlignJustify", "Justify"}};
        for (int i = 0; i < 4; i++) {
            final int a = i;
            aligns[i] = toggle(alignIcons[i], () -> {
                app.editor.align(a);
                app.refocus();
            });
        }
        lists[0] = toggle(new String[] {"List", "Bulleted list"}, () -> list(RichText.BULLETS));
        lists[1] = toggle(new String[] {"ListOrdered", "Numbered list"}, () -> list(RichText.NUMBERS));
        pageView = Ui.toggle("").icon("BookOpen").checked(true).onToggle(on -> {
            app.editor.pageView(on);
            app.editor.focus();
            app.refreshPages();
        });
        pageView.tooltip("Page view");
        pageSetup = push("Ruler", "Page setup", () -> app.pages.show());
        table = push("Table", "Table", () -> app.tables.show());
    }

    private void list(int kind) {
        app.editor.list(kind);
        app.refocus();
    }

    private static ToggleButton toggle(String[] iconTip, final Runnable action) {
        ToggleButton b = Ui.toggle("").icon(iconTip[0]).onToggle(on -> action.run());
        b.tooltip(iconTip[1]);
        return b;
    }

    private static Button push(String icon, String tip, Runnable action) {
        Button b = Ui.button("").icon(icon).onClick(action);
        b.tooltip(tip);
        return b;
    }

    private static Widget gap() {
        return Ui.spacer().width(GAP);
    }

    Layout row() {
        return Ui.row().gap(4).padding(8, 3, 8, 3).add(
            block.width(120), family.width(96), size.width(64), gap(),
            marks[0].width(ICON), marks[1].width(ICON), marks[2].width(ICON), marks[3].width(ICON), gap(),
            aligns[0].width(ICON), aligns[1].width(ICON), aligns[2].width(ICON), aligns[3].width(ICON), gap(),
            lists[0].width(ICON), lists[1].width(ICON),
            push("IndentIncrease", "Indent", () -> app.format(RichText.INDENT)).width(ICON),
            push("IndentDecrease", "Outdent", () -> app.format(RichText.OUTDENT)).width(ICON), gap(),
            wrap.width(120), gap(), table.width(ICON), Ui.spacer(),
            pageView.width(ICON), pageSetup.width(ICON));
    }

    /** Shows {@code s} in the controls; a mixed attribute shows as off. */
    void sync(RichText.Style s) {
        int[] tris = {s.bold, s.italic, s.underline, s.strike};
        for (int i = 0; i < 4; i++) marks[i].checked(tris[i] == RichText.Style.ON);
        for (int i = 0; i < 4; i++) aligns[i].checked(s.align == i);
        lists[0].checked(s.list == 1);
        lists[1].checked(s.list == 2);
        if (s.block >= 0) block.select(s.block);
        if (!s.familyMixed) family.select(familyIndex(s.family));
        for (int i = 0; i < SIZES.length; i++) if (SIZES[i] == s.size) size.select(i);
    }

    /** The picker entry of a run's family: Serif or Mono by this system's names, else Sans. */
    int familyIndex(String name) {
        if (app.serif.equals(name)) return 1;
        if (app.mono.equals(name)) return 2;
        return 0;
    }

    /** Enables the wrap picker while a picture is selected, showing its wrap. */
    void syncWrap(int current) {
        wrap.enabled(current >= 0);
        if (current >= 0) wrap.select(current);
    }
}
