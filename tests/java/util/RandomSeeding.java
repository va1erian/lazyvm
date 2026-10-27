package util;

import java.util.*;

/** Random(long) and setSeed: the sequence from a seed, and when a subclass's setSeed is called. */
public class RandomSeeding {
    static int calls;

    static class Counting extends Random {
        Counting(long seed) { super(seed); }
        Counting() { super(); }
        public synchronized void setSeed(long seed) {
            calls++;
            super.setSeed(seed);
        }
    }

    public static void main(String[] args) {
        calls = 0;
        new Random(5);
        System.out.println("plain Random(long): " + calls);
        new Counting(5);
        System.out.println("subclass Random(long): " + calls);
        calls = 0;
        new Counting();
        System.out.println("subclass Random(): " + calls);
        calls = 0;
        Counting c = new Counting(7);
        c.setSeed(8);
        System.out.println("explicit setSeed adds one: " + calls);
        Random r = new Random(42);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) sb.append(r.nextInt()).append(' ');
        for (int i = 0; i < 5; i++) sb.append(r.nextInt(10)).append(' ');
        for (int i = 0; i < 3; i++) sb.append(r.nextInt(16)).append(' ');
        sb.append(r.nextLong()).append(' ').append(r.nextBoolean()).append(' ').append(r.nextFloat()).append(' ').append(r.nextDouble()).append(' ').append(r.nextGaussian()).append(' ').append(r.nextGaussian());
        System.out.println(sb);
        byte[] b = new byte[7];
        r.nextBytes(b);
        System.out.println(Arrays.toString(b));
        r.setSeed(42);
        System.out.println(r.nextInt() + " " + new Random(42).nextInt());
        r.nextGaussian();
        r.setSeed(1);
        System.out.println(r.nextGaussian() + " " + new Random(1).nextGaussian());
        try { r.nextInt(0); } catch (IllegalArgumentException e) { System.out.println(e.getMessage()); }
        try { r.nextInt(-3); } catch (IllegalArgumentException e) { System.out.println(e.getMessage()); }
        System.out.println(new Random(-1L).nextInt() + " " + new Random(Long.MAX_VALUE).nextLong() + " " + new Random(0).nextInt(1 << 30));
        System.out.println(new Random().nextLong() != new Random().nextLong());
        System.out.println("done");
    }
}
