package bench;

import java.util.Locale;

/** String case mapping as a game calls it every tick: short ASCII names, with and without Locale. */
public final class CaseMap {
    private CaseMap() {
    }

    public static void main(String[] args) {
        int rounds = 8000 * Report.scale(args);
        String[] words = {"Stone Pickaxe", "iron ore", "OAK PLANKS", "health: 10/10", "score", "Lava Bucket", "x", "MiXeD cAsE 123"};
        long start = System.nanoTime();
        long sum = 0;
        for (int r = 0; r < rounds; r++) {
            for (int i = 0; i < words.length; i++) {
                String w = words[i];
                sum += w.toUpperCase().hashCode();
                sum += w.toLowerCase().hashCode();
                sum += w.toUpperCase(Locale.ENGLISH).length();
                sum += w.toLowerCase(Locale.ROOT).charAt(0);
                String same = w.toLowerCase();
                sum += same == w ? 1 : 0;
            }
        }
        Report.print("casemap", sum, start);
    }
}
