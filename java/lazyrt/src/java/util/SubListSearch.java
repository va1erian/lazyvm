package java.util;

/** Collections.indexOfSubList and lastIndexOfSubList, call for call as spec 8.C2 gives them. */
final class SubListSearch {
    // Spec 8.C2: below this source size a list that is not RandomAccess still uses get.
    private static final int SUBLIST_INDEXED = 35;

    private SubListSearch() {
    }

    static int first(List<?> source, List<?> target) {
        int n = source.size();
        int m = target.size();
        if (n < SUBLIST_INDEXED || (source instanceof RandomAccess && target instanceof RandomAccess)) {
            for (int start = 0; start <= n - m; start++) {
                if (matchesAt(source, target, start)) {
                    return start;
                }
            }
            return -1;
        }
        ListIterator<?> s = source.listIterator();
        for (int start = 0; start <= n - m; start++) {
            int matched = matchedPrefix(s, target);
            if (matched == m) {
                return start;
            }
            for (int j = 0; j < matched; j++) {
                s.previous();
            }
        }
        return -1;
    }

    static int last(List<?> source, List<?> target) {
        int n = source.size();
        int m = target.size();
        if (n < SUBLIST_INDEXED || source instanceof RandomAccess) {
            for (int start = n - m; start >= 0; start--) {
                if (matchesAt(source, target, start)) {
                    return start;
                }
            }
            return -1;
        }
        if (n - m < 0) {
            return -1;
        }
        ListIterator<?> s = source.listIterator(n - m);
        for (int start = n - m; start >= 0; start--) {
            int matched = matchedPrefix(s, target);
            if (matched == m) {
                return start;
            }
            if (start != 0) {
                for (int j = 0; j <= matched + 1; j++) {
                    s.previous();
                }
            }
        }
        return -1;
    }

    // Spec 8.C3: the target's element is the receiver of equals.
    private static boolean matchesAt(List<?> source, List<?> target, int start) {
        for (int i = 0, m = target.size(); i < m; i++) {
            if (!Support.eq(target.get(i), source.get(start + i))) {
                return false;
            }
        }
        return true;
    }

    /** Walks a fresh target iterator against s; returns target.size(), or the matches before a miss. */
    private static int matchedPrefix(ListIterator<?> s, List<?> target) {
        ListIterator<?> t = target.listIterator();
        for (int i = 0, m = target.size(); i < m; i++) {
            if (!Support.eq(t.next(), s.next())) {
                return i;
            }
        }
        return target.size();
    }
}
