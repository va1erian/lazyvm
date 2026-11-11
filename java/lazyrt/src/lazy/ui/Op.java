package lazy.ui;

/** The ops {@code call0} runs on a mounted node (crates/xui-bind/src/props.rs and rich.rs). */
final class Op {
    static final int TEXT = 1, GET_TEXT = 2, ENABLED = 3, VISIBLE = 4, FOCUS = 5;
    static final int CHECKED = 6, GET_CHECKED = 7, SELECT = 8, GET_SELECTED = 9;
    static final int VALUE = 10, GET_VALUE = 11, PART = 12, ROWS = 13, TOOLTIP = 15;
    static final int OPEN = 16, CLOSE = 17, TITLE = 18, ITEM_ENABLED = 19, ITEM_CHECKED = 20;
    static final int DRAWING = 21, BOUNDS = 23, NAME = 24, DIR = 25, MESSAGE = 27;

    private Op() {}
}
