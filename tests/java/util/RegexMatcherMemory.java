// VMARGS: -Xmx48m
package util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What an idle matcher holds, in a small heap: matchers whose deep matches() and find() threw
 * keep nothing of them, so a large array fits afterwards and the matchers work again; and a
 * hundred thousand live matchers after one find() each fit, with a thousand that each ran 300
 * iterations with a lookahead.
 */
public class RegexMatcherMemory {
    /** "ab" repeated, that throws when the char at `bomb` is read. */
    static final class Bomb implements CharSequence {
        private final int length;
        private final int bomb;

        Bomb(int length, int bomb) {
            this.length = length;
            this.bomb = bomb;
        }

        public int length() {
            return length;
        }

        public char charAt(int i) {
            if (i >= bomb) {
                throw new IllegalStateException("text read at " + i);
            }
            return (i & 1) == 0 ? 'a' : 'b';
        }

        public CharSequence subSequence(int start, int end) {
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < end; i++) {
                sb.append(charAt(i));
            }
            return sb.toString();
        }

        public String toString() {
            return subSequence(0, length).toString();
        }
    }

    public static void main(String[] args) {
        Matcher deep = Pattern.compile("(?:(a)b)*").matcher(new Bomb(300000, 299999));
        try {
            System.out.println("deep match " + deep.matches());
        } catch (IllegalStateException e) {
            System.out.println("deep match threw: " + e.getMessage());
        }
        Matcher deepFind = Pattern.compile("(?:(a)b)*c").matcher(new Bomb(300000, 299999));
        try {
            System.out.println("deep find " + deepFind.find());
        } catch (IllegalStateException e) {
            System.out.println("deep find threw: " + e.getMessage());
        }
        int[] big = new int[7 << 20];
        System.out.println("then an array of " + big.length * 4 / (1 << 20) + " MB");
        big = null;
        deep.reset("abab");
        System.out.println("the matcher again: " + deep.matches() + " " + deep.start(1) + ":" + deep.end(1));
        deepFind.reset("ababc");
        System.out.println("the find matcher again: " + deepFind.find() + " " + deepFind.start() + ":" + deepFind.end());
        List<Matcher> live = new ArrayList<Matcher>();
        Pattern small = Pattern.compile("a?b?c");
        for (int i = 0; i < 100000; i++) {
            Matcher m = small.matcher("abc");
            if (!m.find()) {
                System.out.println("no match at " + i);
            }
            live.add(m);
        }
        Pattern look = Pattern.compile("(?:a(?=b)b)*c");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            sb.append("ab");
        }
        String text = sb.toString();
        for (int i = 0; i < 1000; i++) {
            Matcher m = look.matcher(text);
            m.lookingAt();
            live.add(m);
        }
        System.out.println("live matchers " + live.size());
        System.out.println("done");
    }
}
