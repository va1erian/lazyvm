import java.util.function.IntUnaryOperator;

class Box {
    final int size;
    final int max = 3;

    Box(int s) {
        size = s;
    }

    Box() {
        this(1);
    }

    int room() {
        return max - size;
    }
}

/** The Java mirror of Finals.sie: it must print the same. */
public class Finals {
    static final int LIMIT = 5;
    static final String NAME = "box";
    public static int shown = 0;

    static int twice() {
        return LIMIT * 2;
    }

    public static void main(String[] args) {
        IntUnaryOperator inc = count -> count + 1;
        int count = inc.applyAsInt(LIMIT);
        shown = new Box(2).room() + new Box().room();
        System.out.println(NAME + " " + twice() + " " + count + " " + shown);
    }
}
