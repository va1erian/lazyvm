package java.util;

/** Package-private comparator implementations behind the static factories of Comparator. */
final class Comparators {
    private Comparators() {
    }

    // Natural order; compares through Comparable so a null argument throws NPE.
    static final class Natural implements Comparator<Comparable<Object>> {
        public int compare(Comparable<Object> a, Comparable<Object> b) {
            return a.compareTo(b);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        public Comparator<Comparable<Object>> reversed() {
            return (Comparator) ReverseNatural.INSTANCE;
        }
    }

    static final class ReverseNatural implements Comparator<Comparable<Object>> {
        static final ReverseNatural INSTANCE = new ReverseNatural();

        public int compare(Comparable<Object> a, Comparable<Object> b) {
            return b.compareTo(a);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        public Comparator<Comparable<Object>> reversed() {
            return (Comparator) NATURAL;
        }
    }

    static final Natural NATURAL = new Natural();

    @SuppressWarnings({"unchecked", "rawtypes"})
    static <T extends Comparable<? super T>> Comparator<T> reverseNatural() {
        return (Comparator) ReverseNatural.INSTANCE;
    }

    static final class Reverse<T> implements Comparator<T> {
        final Comparator<T> cmp;

        Reverse(Comparator<T> cmp) {
            this.cmp = cmp;
        }

        public int compare(T a, T b) {
            return cmp.compare(b, a);
        }

        public Comparator<T> reversed() {
            return cmp;
        }
    }

    static <T> Comparator<T> reverse(Comparator<T> c) {
        return new Reverse<T>(c);
    }

    static final class Nulls<T> implements Comparator<T> {
        final boolean nullFirst;
        final Comparator<T> real;

        @SuppressWarnings("unchecked")
        Nulls(boolean nullFirst, Comparator<? super T> real) {
            this.nullFirst = nullFirst;
            this.real = (Comparator<T>) real;
        }

        public int compare(T a, T b) {
            if (a == null) {
                return b == null ? 0 : (nullFirst ? -1 : 1);
            }
            if (b == null) {
                return nullFirst ? 1 : -1;
            }
            return real == null ? 0 : real.compare(a, b);
        }

        public Comparator<T> thenComparing(Comparator<? super T> other) {
            Objects.requireNonNull(other);
            return new Nulls<T>(nullFirst, real == null ? other : real.thenComparing(other));
        }

        public Comparator<T> reversed() {
            return new Nulls<T>(!nullFirst, real == null ? null : real.reversed());
        }
    }
}
