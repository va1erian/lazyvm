package lazy.gfx;

/**
 * One input event, filled in place by {@link Window#poll} so a game loop allocates nothing.
 * Which fields mean something depends on {@link #type}.
 */
public final class Event {
    /** A key went down; {@link #key} says which. Repeats while held arrive as more KEY_DOWNs. */
    public static final int KEY_DOWN = 1;
    /** A key went up; {@link #key} says which. */
    public static final int KEY_UP = 2;
    /** Text was typed; {@link #ch} holds one UTF-16 unit of it. */
    public static final int TEXT = 3;
    /** The pointer moved to ({@link #x}, {@link #y}), in window pixels. */
    public static final int MOUSE_MOVE = 4;
    /** {@link #button} (1 left, 2 right, 3 middle) went down at ({@link #x}, {@link #y}). */
    public static final int MOUSE_DOWN = 5;
    /** {@link #button} went up at ({@link #x}, {@link #y}). */
    public static final int MOUSE_UP = 6;
    /** The window gained ({@link #x} == 1) or lost ({@link #x} == 0) the keyboard focus. */
    public static final int FOCUS = 7;
    /** The client area is now {@link #x} by {@link #y} pixels. */
    public static final int RESIZE = 8;
    /** The user asked to close the window. It stays open until {@link Window#close}. */
    public static final int CLOSE = 9;

    public int type;
    /** A {@link Key} code, for KEY_DOWN and KEY_UP. */
    public int key;
    public char ch;
    public int x;
    public int y;
    public int button;
}
