package java.util.stream;

/** The small mutable holders collectors accumulate into. */
final class Cells {
    private Cells() {
    }

    /** One value that is replaced as elements arrive. */
    static final class Box<V> {
        V value;

        Box(V value) { this.value = value; }
    }

    /** Running totals: a count, a long total and an int total that wraps in an int. */
    static final class Tally {
        long count;
        long total;
        int narrow;
    }
}
