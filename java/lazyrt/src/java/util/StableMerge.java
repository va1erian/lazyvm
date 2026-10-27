package java.util;

/** The two merges of spec 7.S1: forward with run A set aside, backward with run B set aside. */
final class StableMerge {
    // Spec 7.S1: galloping continues while either side took at least this many at once.
    private static final int KEEP_GALLOPING = 7;

    private StableMerge() {
    }

    /** Merges A = a[base1, +len1) and B = a[base2, +len2), adjacent, from the left. */
    static <T> void forward(StableSort<T> s, int base1, int len1, int base2, int len2) {
        T[] a = s.array();
        T[] tmp = s.scratch(len1);
        System.arraycopy(a, base1, tmp, 0, len1);
        int c1 = 0;
        int c2 = base2;
        int dest = base1;
        a[dest++] = a[c2++];
        if (--len2 == 0) {
            System.arraycopy(tmp, c1, a, dest, len1);
            return;
        }
        if (len1 == 1) {
            System.arraycopy(a, c2, a, dest, len2);
            a[dest + len2] = tmp[c1];
            return;
        }
        int g = s.gallopAt();
        outer:
        while (true) {
            int w1 = 0;
            int w2 = 0;
            do {
                if (s.less(a[c2], tmp[c1])) {
                    a[dest++] = a[c2++];
                    w2++;
                    w1 = 0;
                    if (--len2 == 0) {
                        break outer;
                    }
                } else {
                    a[dest++] = tmp[c1++];
                    w1++;
                    w2 = 0;
                    if (--len1 == 1) {
                        break outer;
                    }
                }
            } while ((w1 | w2) < g);
            do {
                w1 = s.gallop(a[c2], tmp, c1, len1, 0, false);
                if (w1 != 0) {
                    System.arraycopy(tmp, c1, a, dest, w1);
                    dest += w1;
                    c1 += w1;
                    len1 -= w1;
                    if (len1 <= 1) {
                        break outer;
                    }
                }
                a[dest++] = a[c2++];
                if (--len2 == 0) {
                    break outer;
                }
                w2 = s.gallop(tmp[c1], a, c2, len2, 0, true);
                if (w2 != 0) {
                    System.arraycopy(a, c2, a, dest, w2);
                    dest += w2;
                    c2 += w2;
                    len2 -= w2;
                    if (len2 == 0) {
                        break outer;
                    }
                }
                a[dest++] = tmp[c1++];
                if (--len1 == 1) {
                    break outer;
                }
                g--;
            } while (w1 >= KEEP_GALLOPING | w2 >= KEEP_GALLOPING);
            g = Math.max(g, 0) + 2;
        }
        s.gallopAt(Math.max(g, 1));
        if (len1 == 1) {
            System.arraycopy(a, c2, a, dest, len2);
            a[dest + len2] = tmp[c1];
        } else if (len1 == 0) {
            throw StableSort.violated();
        } else {
            System.arraycopy(tmp, c1, a, dest, len1);
        }
    }

    /** The mirror of forward: merges from the right end, with B copied out. */
    static <T> void backward(StableSort<T> s, int base1, int len1, int base2, int len2) {
        T[] a = s.array();
        T[] tmp = s.scratch(len2);
        System.arraycopy(a, base2, tmp, 0, len2);
        int c1 = base1 + len1 - 1;
        int c2 = len2 - 1;
        int dest = base2 + len2 - 1;
        a[dest--] = a[c1--];
        if (--len1 == 0) {
            System.arraycopy(tmp, 0, a, dest - (len2 - 1), len2);
            return;
        }
        if (len2 == 1) {
            dest -= len1;
            c1 -= len1;
            System.arraycopy(a, c1 + 1, a, dest + 1, len1);
            a[dest] = tmp[c2];
            return;
        }
        int g = s.gallopAt();
        outer:
        while (true) {
            int w1 = 0;
            int w2 = 0;
            do {
                if (s.less(tmp[c2], a[c1])) {
                    a[dest--] = a[c1--];
                    w1++;
                    w2 = 0;
                    if (--len1 == 0) {
                        break outer;
                    }
                } else {
                    a[dest--] = tmp[c2--];
                    w2++;
                    w1 = 0;
                    if (--len2 == 1) {
                        break outer;
                    }
                }
            } while ((w1 | w2) < g);
            do {
                w1 = len1 - s.gallop(tmp[c2], a, base1, len1, len1 - 1, false);
                if (w1 != 0) {
                    dest -= w1;
                    c1 -= w1;
                    len1 -= w1;
                    System.arraycopy(a, c1 + 1, a, dest + 1, w1);
                    if (len1 == 0) {
                        break outer;
                    }
                }
                a[dest--] = tmp[c2--];
                if (--len2 == 1) {
                    break outer;
                }
                w2 = len2 - s.gallop(a[c1], tmp, 0, len2, len2 - 1, true);
                if (w2 != 0) {
                    dest -= w2;
                    c2 -= w2;
                    len2 -= w2;
                    System.arraycopy(tmp, c2 + 1, a, dest + 1, w2);
                    if (len2 <= 1) {
                        break outer;
                    }
                }
                a[dest--] = a[c1--];
                if (--len1 == 0) {
                    break outer;
                }
                g--;
            } while (w1 >= KEEP_GALLOPING | w2 >= KEEP_GALLOPING);
            g = Math.max(g, 0) + 2;
        }
        s.gallopAt(Math.max(g, 1));
        if (len2 == 1) {
            dest -= len1;
            c1 -= len1;
            System.arraycopy(a, c1 + 1, a, dest + 1, len1);
            a[dest] = tmp[c2];
        } else if (len2 == 0) {
            throw StableSort.violated();
        } else {
            System.arraycopy(tmp, 0, a, dest - (len2 - 1), len2);
        }
    }
}
