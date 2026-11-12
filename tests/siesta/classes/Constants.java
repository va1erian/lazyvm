class Config {
    static final int SIZE = 128;
    static final long LOW = -9223372036854775808L;
    static final double HALF = 0.5;
    static final float THIRD = -0.25f;
    static final char MARK = 'x';
    static final boolean ON = true;
    static final String NAME = "cfg";
    static final Integer BOXED = 7;
    static int touched = touch();

    static int touch() {
        System.out.println("Config initialised");
        return 1;
    }
}

/** The Java mirror of Constants.sie: it must print the same. */
public class Constants {
    static final int TILE = 16;
    static final int[] NOT_CONSTANT = {1, 2};

    public static void main(String[] args) {
        System.out.println(Config.SIZE + " " + Config.LOW + " " + Config.HALF + " " + Config.THIRD);
        System.out.println(Config.MARK + " " + Config.ON + " " + Config.NAME + " " + Config.NAME.length());
        System.out.println("before the box");
        System.out.println(Config.BOXED);
        System.out.println(TILE * TILE + " " + NOT_CONSTANT.length);
    }
}
