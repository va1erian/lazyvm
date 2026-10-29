package lazy.gfx;

/**
 * Key codes for {@link Event#key}. They name physical keys on a US layout, so WASD stays under
 * the same fingers on any layout; typed text comes separately as {@link Event#TEXT}. Letters and
 * digits use their ASCII codes, so {@code 'A'} and {@code '7'} are valid codes.
 */
public final class Key {
    private Key() {
    }

    public static final int UNKNOWN = 0;
    public static final int SPACE = ' ';
    public static final int QUOTE = '\'';
    public static final int COMMA = ',';
    public static final int MINUS = '-';
    public static final int PERIOD = '.';
    public static final int SLASH = '/';
    public static final int SEMICOLON = ';';
    public static final int EQUALS = '=';
    public static final int OPEN_BRACKET = '[';
    public static final int BACKSLASH = '\\';
    public static final int CLOSE_BRACKET = ']';
    public static final int BACK_QUOTE = '`';

    public static final int ESCAPE = 256;
    public static final int ENTER = 257;
    public static final int TAB = 258;
    public static final int BACKSPACE = 259;
    public static final int INSERT = 260;
    public static final int DELETE = 261;
    public static final int RIGHT = 262;
    public static final int LEFT = 263;
    public static final int DOWN = 264;
    public static final int UP = 265;
    public static final int PAGE_UP = 266;
    public static final int PAGE_DOWN = 267;
    public static final int HOME = 268;
    public static final int END = 269;
    public static final int SHIFT = 270;
    public static final int CTRL = 271;
    public static final int ALT = 272;
    /** F1 to F12 are F1 + 0 to F1 + 11. */
    public static final int F1 = 280;

    private static final String[] NAMES = {
        "ESCAPE", "ENTER", "TAB", "BACKSPACE", "INSERT", "DELETE", "RIGHT", "LEFT", "DOWN", "UP",
        "PAGE_UP", "PAGE_DOWN", "HOME", "END", "SHIFT", "CTRL", "ALT",
    };

    /**
     * An upper-case name for {@code code}: {@code "A"}, {@code "7"}, {@code "UP"}, {@code "F5"},
     * {@code "COMMA"}; {@code "UNKNOWN"} for codes this class does not define.
     */
    public static String name(int code) {
        if (code >= 'A' && code <= 'Z' || code >= '0' && code <= '9') return String.valueOf((char) code);
        if (code >= ESCAPE && code < ESCAPE + NAMES.length) return NAMES[code - ESCAPE];
        if (code >= F1 && code < F1 + 12) return "F" + (code - F1 + 1);
        switch (code) {
            case SPACE: return "SPACE";
            case QUOTE: return "QUOTE";
            case COMMA: return "COMMA";
            case MINUS: return "MINUS";
            case PERIOD: return "PERIOD";
            case SLASH: return "SLASH";
            case SEMICOLON: return "SEMICOLON";
            case EQUALS: return "EQUALS";
            case OPEN_BRACKET: return "OPEN_BRACKET";
            case BACKSLASH: return "BACKSLASH";
            case CLOSE_BRACKET: return "CLOSE_BRACKET";
            case BACK_QUOTE: return "BACK_QUOTE";
            default: return "UNKNOWN";
        }
    }
}
