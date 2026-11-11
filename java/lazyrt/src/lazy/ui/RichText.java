package lazy.ui;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * A rich-text editor (xui's {@code xui-rich-text}): styled runs, headings, lists, pictures that
 * text flows around, tables, and a page view. The document lives in the editor; typing, moving
 * the caret and painting run no bytecode. The program runs commands on it, hears about changes,
 * and saves and loads it whole as JSON.
 */
public final class RichText extends Widget {
    /** Commands for {@link #exec}. */
    public static final int UNDO = 0, REDO = 1, CUT = 2, COPY = 3, PASTE = 4, SELECT_ALL = 5;
    public static final int BOLD = 6, ITALIC = 7, UNDERLINE = 8, STRIKE = 9, INDENT = 10, OUTDENT = 11;
    public static final int PAGE_BREAK = 12, NEXT_CELL = 13, PREV_CELL = 14;
    public static final int ROW_ABOVE = 15, ROW_BELOW = 16, COLUMN_LEFT = 17, COLUMN_RIGHT = 18;
    public static final int DELETE_ROWS = 19;
    /** Paragraph kinds for {@link #block}. */
    public static final int BODY = 0, HEADING_1 = 1, HEADING_2 = 2, HEADING_3 = 3, QUOTE = 4;
    /** Alignments for {@link #align}. */
    public static final int LEFT = 0, CENTER = 1, RIGHT = 2, JUSTIFY = 3;
    /** Lists for {@link #list}. */
    public static final int BULLETS = 0, NUMBERS = 1;
    /** Picture wraps for {@link #wrap(int)}. */
    public static final int INLINE = 0, FLOAT_LEFT = 1, FLOAT_RIGHT = 2, TOP_AND_BOTTOM = 3;

    // Ops, shared with crates/xui-bind/src/rich.rs.
    private static final int EXEC = 100, BLOCK = 101, FAMILY = 102, SIZE = 103, LINK = 104, ALIGN = 105;
    private static final int LIST = 106, WRAP = 107, PAGE_VIEW = 108, PAGE = 109, GET_PAGE = 110;
    private static final int PAGE_INFO = 111, TABLE_CURSOR = 112, INSERT_TABLE = 113, TABLE_SWITCH = 114;
    private static final int GET_JSON = 115, SET_JSON = 116, SET_PLAIN = 117, MARKDOWN = 118;
    private static final int INSERT_IMAGE = 119, WORDS = 121, GET_WRAP = 122, GET_STYLE = 123;
    private static final int GET_FAMILY = 124;

    private boolean pageView = true;
    private IntConsumer onChange;
    private Consumer<Style> onStyle;
    private Consumer<String> onLink;

    RichText() {}

    /** The formatting under the selection, as {@link #onStyle} reports it. */
    public static final class Style {
        /** For the character marks: off, on, or mixed across the selection. */
        public static final int OFF = 0, ON = 1, MIXED = 2;

        public final int bold, italic, underline, strike;
        /** {@link #LEFT} to {@link #JUSTIFY}, or -1 when mixed. */
        public final int align;
        /** -1 mixed, 0 no list, 1 bullets, 2 numbers. */
        public final int list;
        /** {@link #BODY} to {@link #QUOTE}, or -1 when mixed. */
        public final int block;
        /** The size in design units, or NaN when mixed. */
        public final double size;
        /** The font family, or null for the default one or when mixed. */
        public final String family;
        public final boolean familyMixed;

        Style(int bits, double size, String family) {
            bold = bits & 3;
            italic = bits >> 2 & 3;
            underline = bits >> 4 & 3;
            strike = bits >> 6 & 3;
            int a = bits >> 8 & 7, l = bits >> 11 & 3, k = bits >> 13 & 7;
            align = a == 7 ? -1 : a;
            list = l == 3 ? -1 : l;
            block = k == 7 ? -1 : k;
            this.size = (bits >> 16 & 1) != 0 ? Double.NaN : size;
            familyMixed = (bits >> 17 & 1) != 0;
            this.family = family;
        }
    }

    /** Shows the document as pages (on) or as one column (off). */
    public RichText pageView(boolean on) {
        if (mounted()) call(PAGE_VIEW, on ? 1 : 0, 0, null);
        pageView = on;
        return this;
    }

    public boolean pageView() {
        return pageView;
    }

    /** Runs {@code action} with the word count after each change to the document. */
    public RichText onChange(IntConsumer action) {
        if (mounted()) throw new IllegalStateException("set onChange before the window opens");
        onChange = Ui.nonNull(action, "action");
        return this;
    }

    /** Runs {@code action} when the selection, or the formatting under it, changes. */
    public RichText onStyle(Consumer<Style> action) {
        if (mounted()) throw new IllegalStateException("set onStyle before the window opens");
        onStyle = Ui.nonNull(action, "action");
        return this;
    }

    /** Runs {@code action} with a link's address when the user Ctrl+clicks it. */
    public RichText onLink(Consumer<String> action) {
        if (mounted()) throw new IllegalStateException("set onLink before the window opens");
        onLink = Ui.nonNull(action, "action");
        return this;
    }

    /** Runs a command, one of this class's {@code UNDO} to {@code DELETE_ROWS}. */
    public void exec(int command) {
        if (command < UNDO || command > DELETE_ROWS) throw new IllegalArgumentException("command " + command);
        call(EXEC, command, 0, null);
    }

    /** Makes the selected paragraphs {@link #BODY} to {@link #QUOTE}. */
    public void block(int kind) {
        if (kind < BODY || kind > QUOTE) throw new IllegalArgumentException("block " + kind);
        call(BLOCK, kind, 0, null);
    }

    /** Sets the selection's font family, or the default one with null. */
    public void family(String family) {
        call(FAMILY, family);
    }

    /** Sets the selection's size, in design units. */
    public void size(double size) {
        if (!(size >= 1 && size <= 1000)) throw new IllegalArgumentException("size " + size);
        call(SIZE, 0, size, null);
    }

    /** Links the selection to {@code url}, or removes its link when {@code url} is blank. */
    public void link(String url) {
        call(LINK, Ui.nonNull(url, "url"));
    }

    public void align(int align) {
        if (align < LEFT || align > JUSTIFY) throw new IllegalArgumentException("align " + align);
        call(ALIGN, align, 0, null);
    }

    /** Turns {@link #BULLETS} or {@link #NUMBERS} on or off for the selected paragraphs. */
    public void list(int kind) {
        if (kind != BULLETS && kind != NUMBERS) throw new IllegalArgumentException("list " + kind);
        call(LIST, kind, 0, null);
    }

    /** Sets how text flows around the selected picture; nothing when none is selected. */
    public void wrap(int wrap) {
        if (wrap < INLINE || wrap > TOP_AND_BOTTOM) throw new IllegalArgumentException("wrap " + wrap);
        call(WRAP, wrap, 0, null);
    }

    /** The selected picture's wrap, or -1 when no picture is selected. */
    public int wrap() {
        return callInt(GET_WRAP) - 1;
    }

    /**
     * The page: width, height, then the left, top, right and bottom margins, in hundredths of a
     * millimetre.
     */
    public int[] page() {
        return (int[]) call(GET_PAGE, null);
    }

    /** Sets the page, as one undoable step; see {@link #page()}. */
    public void page(int[] page) {
        if (Ui.nonNull(page, "page").length != 6) throw new IllegalArgumentException("a page has 6 numbers");
        call(PAGE, page.clone());
    }

    /** The page the caret is on, from 1, and the page count. */
    public int[] pageInfo() {
        int[] p = (int[]) call(PAGE_INFO, null);
        return new int[] {p[0] + 1, p[1]};
    }

    /**
     * Where the caret is in a table: row, column, row count, column count, and whether the header
     * row and borders are on (1) or off (0); null outside a table.
     */
    public int[] tableCursor() {
        int[] c = (int[]) call(TABLE_CURSOR, null);
        return c.length == 0 ? null : c;
    }

    public void insertTable(int rows, int columns) {
        if (rows < 1 || rows > 64 || columns < 1 || columns > 64)
            throw new IllegalArgumentException("table " + rows + " x " + columns);
        call(INSERT_TABLE, rows, columns, null);
    }

    /** Turns the caret's table's header row on or off. */
    public void tableHeader(boolean on) {
        call(TABLE_SWITCH, 0, on ? 1 : 0, null);
    }

    /** Turns the caret's table's borders on or off. */
    public void tableBorders(boolean on) {
        call(TABLE_SWITCH, 1, on ? 1 : 0, null);
    }

    public void deleteColumns() {
        call(TABLE_SWITCH, 2, 0, null);
    }

    public void deleteTable() {
        call(TABLE_SWITCH, 3, 0, null);
    }

    /** The document in xui's lossless JSON format. */
    public String json() {
        return (String) call(GET_JSON, null);
    }

    /**
     * Replaces the document with one in xui's JSON format.
     *
     * @throws IllegalStateException if the text is not such a document
     */
    public void json(String json) {
        call(SET_JSON, Ui.nonNull(json, "json"));
    }

    /** Replaces the document with plain text, a paragraph per line. */
    public void plainText(String text) {
        call(SET_PLAIN, Ui.nonNull(text, "text"));
    }

    /**
     * The document as Markdown. Pictures are linked as {@code <prefix><n>.png}, from 1, and their
     * PNG files are added to {@code images} in that order.
     */
    public String markdown(String imagePrefix, List<byte[]> images) {
        Object[] r = (Object[]) call(MARKDOWN, Ui.nonNull(imagePrefix, "imagePrefix"));
        for (int i = 1; i < r.length; i++) images.add((byte[]) r[i]);
        return (String) r[0];
    }

    /**
     * Inserts a PNG or JPEG picture at the caret, at most 360 design units wide.
     *
     * @throws IllegalStateException if the bytes are not such a picture, or it is over 4096
     *     pixels a side
     */
    public void insertImage(byte[] file, String alt) {
        call(INSERT_IMAGE, new Object[] {Ui.nonNull(alt, "alt"), Ui.nonNull(file, "file").clone()});
    }

    /**
     * The formatting at the start of the document, as {@link #onStyle} would report it for a
     * caret there: what to show once a document is loaded.
     */
    public Style startStyle() {
        int[] s = (int[]) call(GET_STYLE, null);
        return new Style(s[0], s[1] / 100.0, (String) call(GET_FAMILY, null));
    }

    /** The number of words in the document. */
    public int words() {
        return callInt(WORDS);
    }

    @Override
    void encode(Encoder out) {
        head(out, Encoder.RICH);
        out.flag(pageView);
        out.op((onChange != null ? 1 : 0) | (onStyle != null ? 2 : 0) | (onLink != null ? 4 : 0));
    }

    @Override
    void fire(int kind, int i, double d, String s) {
        if (kind == Ui.CHANGE && onChange != null) onChange.accept(i);
        else if (kind == Ui.STYLE && onStyle != null) onStyle.accept(new Style(i, d, s));
        else if (kind == Ui.LINK && onLink != null) onLink.accept(s);
    }
}
