package util;

import java.util.Arrays;
import java.util.BitSet;

/** java.util.BitSet (docs/design/util-data.md, section 6). */
public class BitSets {
    static long seed = 0xB175E7L;

    static int next(int bound) {
        seed ^= seed << 13;
        seed ^= seed >>> 7;
        seed ^= seed << 17;
        return (int) ((seed >>> 1) % bound);
    }

    static String s(BitSet b) {
        return b + " size " + b.size() + " len " + b.length() + " card " + b.cardinality();
    }

    interface Op {
        Object run();
    }

    static String t(Op op) {
        try {
            Object o = op.run();
            return String.valueOf(o instanceof BitSet ? s((BitSet) o) : o);
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    static BitSet of(int... bits) {
        BitSet b = new BitSet();
        for (int i : bits) b.set(i);
        return b;
    }

    public static void main(String[] args) {
        // BS1: the constructor's size rounds nbits up to whole 64-bit words.
        StringBuilder sb = new StringBuilder("ctor sizes");
        for (int n : new int[] {0, 1, 63, 64, 65, 127, 128, 129, 1000}) sb.append(' ').append(new BitSet(n).size());
        System.out.println(sb.append(" default ").append(new BitSet().size()));
        System.out.println("ctor -1: " + t(() -> new BitSet(-1)) + " | min: " + t(() -> new BitSet(Integer.MIN_VALUE)));
        // BS2: growth to the larger of twice the words and the words needed.
        BitSet g = new BitSet();
        sb = new StringBuilder("growth default");
        for (int i : new int[] {0, 63, 64, 100, 130, 500, 1000, 5000, 5100, 20000}) {
            g.set(i);
            sb.append(' ').append(i).append(':').append(g.size());
        }
        System.out.println(sb);
        BitSet g64 = new BitSet(64);
        sb = new StringBuilder("growth 64");
        for (int i : new int[] {200, 300, 1000, 1001, 4000}) {
            g64.set(i);
            sb.append(' ').append(i).append(':').append(g64.size());
        }
        System.out.println(sb);
        BitSet g0 = new BitSet(0);
        sb = new StringBuilder("growth 0");
        for (int i : new int[] {0, 64, 65, 200}) {
            g0.set(i);
            sb.append(' ').append(i).append(':').append(g0.size());
        }
        System.out.println(sb);
        // BS3: which operations grow: set, flip, set ranges, or, xor; never clear, get, and, andNot.
        System.out.println("no growth: " + t(() -> { BitSet b = new BitSet(); b.clear(5000); b.set(5000, false); b.flip(70, 70); b.set(64, 64);
            b.get(10000); b.get(0, 5000); b.clear(100, 9000); b.set(300, 300, true); return b; }));
        System.out.println("flip grows: " + t(() -> { BitSet b = new BitSet(); b.flip(200); return b; }) + " | flip twice "
            + t(() -> { BitSet b = new BitSet(); b.flip(200); b.flip(200); return b; }));
        System.out.println("range grows: " + t(() -> { BitSet b = new BitSet(); b.set(60, 130); return b; }) + " | false "
            + t(() -> { BitSet b = new BitSet(); b.set(60, 130, false); return b; }) + " | flip range "
            + t(() -> { BitSet b = new BitSet(); b.flip(0, 64); return b; }) + " | flip 1..65 "
            + t(() -> { BitSet b = new BitSet(); b.flip(1, 65); return b; }));
        System.out.println("set false grows? " + t(() -> { BitSet b = new BitSet(); b.set(700, false); return b; })
            + " | set true " + t(() -> { BitSet b = new BitSet(); b.set(700, true); return b; }));
        BitSet big = of(1000), small = of(1);
        System.out.println("or " + t(() -> { BitSet b = of(1); b.or(big); return b; }) + " | xor " + t(() -> { BitSet b = of(1); b.xor(big); return b; })
            + " | and " + t(() -> { BitSet b = new BitSet(2000); b.set(1); b.and(new BitSet()); return b; })
            + " | andNot " + t(() -> { BitSet b = new BitSet(2000); b.set(1); b.andNot(big); return b; })
            + " | big.and " + t(() -> { BitSet b = of(1000, 1); b.and(small); return b; })
            + " | or empty " + t(() -> { BitSet b = new BitSet(); b.or(new BitSet(5000)); return b; })
            + " | or self " + t(() -> { BitSet b = of(3, 300); b.or(b); return b; }) + " | xor self " + t(() -> { BitSet b = of(3, 300); b.xor(b); return b; }));
        // BS4: clone keeps the size of a set made with an explicit size, else trims to the words in use.
        System.out.println("clone default " + t(() -> { BitSet b = of(10, 1000); b.clear(1000); return b.clone(); })
            + " | clone sized " + t(() -> { BitSet b = new BitSet(1000); b.set(5); return b.clone(); })
            + " | clone sized grown " + t(() -> { BitSet b = new BitSet(64); b.set(500); b.clear(500); return b.clone(); })
            + " | clone empty " + t(() -> new BitSet().clone()) + " | clone of 0 " + t(() -> new BitSet(0).clone())
            + " | clone keeps sized " + t(() -> { BitSet b = new BitSet(640); return ((BitSet) b.clone()).clone(); }));
        System.out.println("clone after or " + t(() -> { BitSet b = new BitSet(); b.or(of(500)); b.clear(500); return b.clone(); })
            + " | clone after and " + t(() -> { BitSet b = of(500); b.and(of(2)); return b.clone(); }));
        System.out.println("clone sized, or grows " + t(() -> { BitSet b = new BitSet(64); b.or(of(500)); b.clear(500); return b.clone(); })
            + " | sized, or no growth " + t(() -> { BitSet b = new BitSet(640); b.or(of(5)); return b.clone(); })
            + " | sized, xor grows " + t(() -> { BitSet b = new BitSet(1); b.xor(of(100)); b.clear(100); return b.clone(); })
            + " | sized, set within " + t(() -> { BitSet b = new BitSet(640); b.set(639); b.clear(639); return b.clone(); })
            + " | valueOf " + t(() -> BitSet.valueOf(new long[] {0, 0, 5}).clone()) + " | valueOf cleared "
            + t(() -> { BitSet b = BitSet.valueOf(new long[] {0, 0, 5}); b.clear(130); b.clear(128); return b.clone(); })
            + " | get result " + t(() -> of(1, 300).get(250, 260).clone()) + " | get result trimmed "
            + t(() -> { BitSet b = of(1, 300).get(0, 301); b.clear(299); return b.clone(); }));
        // BS5: toString, valueOf and the arrays, which drop trailing zero words and bytes.
        System.out.println("toString " + new BitSet() + " " + of(0) + " " + of(1, 3, 64, 1000) + " " + of(Integer.MAX_VALUE >> 10));
        System.out.println("valueOf long " + s(BitSet.valueOf(new long[] {1, 0, 0})) + " | " + s(BitSet.valueOf(new long[] {0, 0, 5}))
            + " | " + s(BitSet.valueOf(new long[0])) + " | " + s(BitSet.valueOf(new long[] {-1L, Long.MIN_VALUE})));
        System.out.println("valueOf byte " + s(BitSet.valueOf(new byte[] {1, 0, 0, 0, 0, 0, 0, 0, 0, 0})) + " | "
            + s(BitSet.valueOf(new byte[] {0, 0, 0, 0, 0, 0, 0, 0, (byte) 0x80})) + " | " + s(BitSet.valueOf(new byte[] {(byte) 0xff, 1}))
            + " | " + s(BitSet.valueOf(new byte[0])));
        long[] shared = {6};
        BitSet fromArr = BitSet.valueOf(shared);
        shared[0] = 0;
        System.out.println("valueOf copies " + fromArr);
        System.out.println("toLongArray " + Arrays.toString(of(0, 64, 129).toLongArray()) + " " + Arrays.toString(new BitSet(500).toLongArray())
            + " " + Arrays.toString(of(63).toLongArray()) + " | toByteArray " + Arrays.toString(of(0, 15, 64).toByteArray())
            + " " + Arrays.toString(of(7, 8).toByteArray()) + " " + Arrays.toString(new BitSet(500).toByteArray())
            + " " + Arrays.toString(of(63).toByteArray()) + " " + Arrays.toString(BitSet.valueOf(new byte[] {1, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0x80}).toByteArray()));
        // BS6: get(from, to) returns a new set holding the range shifted down; its size fits the bits found.
        BitSet src = of(3, 5, 64, 70, 200, 300);
        System.out.println("get range " + t(() -> src.get(4, 201)) + " | " + t(() -> src.get(0, 0)) + " | " + t(() -> src.get(301, 900))
            + " | " + t(() -> src.get(64, 65)) + " | " + t(() -> src.get(5, 5000)) + " | " + t(() -> src.get(250, 260)));
        // BS7: searches.
        BitSet q = of(0, 1, 63, 64, 200);
        sb = new StringBuilder("searches");
        for (int i : new int[] {0, 1, 2, 63, 64, 65, 199, 200, 201, 5000, Integer.MAX_VALUE}) {
            sb.append(" [").append(i).append(' ').append(q.nextSetBit(i)).append(' ').append(q.nextClearBit(i)).append(' ')
                .append(q.previousSetBit(i)).append(' ').append(q.previousClearBit(i)).append(']');
        }
        System.out.println(sb.append(" prev(-1) ").append(q.previousSetBit(-1)).append(' ').append(q.previousClearBit(-1)));
        BitSet full = new BitSet();
        full.set(0, 128);
        System.out.println("full nextClear " + full.nextClearBit(0) + " " + full.nextClearBit(127) + " prevClear " + full.previousClearBit(127)
            + " " + full.previousClearBit(500) + " | empty " + new BitSet().nextSetBit(0) + " " + new BitSet().previousSetBit(100) + " "
            + new BitSet().nextClearBit(77) + " " + new BitSet().previousClearBit(77));
        // BS8: exceptions and their messages.
        BitSet x = new BitSet();
        System.out.println("err set " + t(() -> { x.set(-1); return 1; }) + " | get " + t(() -> x.get(-1)) + " | clear " + t(() -> { x.clear(-2); return 1; })
            + " | flip " + t(() -> { x.flip(-2); return 1; }) + " | set(b) " + t(() -> { x.set(-3, true); return 1; }));
        System.out.println("err ranges " + t(() -> { x.set(5, 3); return 1; }) + " | " + t(() -> { x.set(-1, 3); return 1; }) + " | "
            + t(() -> { x.set(1, -3); return 1; }) + " | " + t(() -> { x.set(-5, -3); return 1; }) + " | " + t(() -> { x.clear(9, 8); return 1; })
            + " | " + t(() -> { x.flip(-1, 0); return 1; }) + " | " + t(() -> x.get(3, 1)) + " | " + t(() -> { x.set(2, 1, false); return 1; })
            + " | " + t(() -> x.get(-1, -2)));
        System.out.println("err searches " + t(() -> x.nextSetBit(-1)) + " | " + t(() -> x.nextClearBit(-1)) + " | " + t(() -> x.previousSetBit(-2))
            + " | " + t(() -> x.previousClearBit(-2)) + " | " + t(() -> x.previousSetBit(Integer.MIN_VALUE)));
        System.out.println("err null " + t(() -> { x.and(null); return 1; }) + " | " + t(() -> BitSet.valueOf((long[]) null)) + " | "
            + t(() -> x.intersects(null)) + " | equals null " + x.equals(null));
        // BS9: equals ignores size; hashCode from the words.
        BitSet e1 = new BitSet(5000), e2 = new BitSet();
        e1.set(3);
        e2.set(3);
        System.out.println("equals " + e1.equals(e2) + " " + e1.equals(of(4)) + " " + e1.equals("x") + " hash " + e1.hashCode() + " " + e2.hashCode()
            + " " + new BitSet().hashCode() + " " + of(0).hashCode() + " " + of(64).hashCode() + " " + of(63, 127, 1000).hashCode());
        System.out.println("intersects " + of(1, 100).intersects(of(100)) + " " + of(1).intersects(of(2)) + " " + new BitSet().intersects(new BitSet())
            + " cardinality " + of(0, 1, 2, 64, 1000).cardinality() + " isEmpty " + new BitSet(900).isEmpty() + " " + of(5).isEmpty());
        BitSet cl = of(5, 500);
        cl.clear();
        System.out.println("clear() " + s(cl) + " | clear range " + t(() -> { BitSet b = of(1, 2, 3, 200); b.clear(2, 200); return b; })
            + " | clear top " + t(() -> { BitSet b = of(1, 200); b.clear(200); return b; }));
        // Fuzz BS1-BS9: random operations; a checksum of every observable after each step.
        int sum = 0;
        BitSet a = new BitSet(next(300)), b2 = new BitSet();
        for (int round = 0; round < 3000; round++) {
            int i = next(700), j = i + next(200), op = next(14);
            switch (op) {
                case 0: a.set(i); break;
                case 1: a.clear(i); break;
                case 2: a.flip(i); break;
                case 3: a.set(i, j); break;
                case 4: a.clear(i, j); break;
                case 5: a.flip(i, j); break;
                case 6: a.set(i, j, next(2) == 0); break;
                case 7: a.or(b2); break;
                case 8: a.and(b2); break;
                case 9: a.xor(b2); break;
                case 10: a.andNot(b2); break;
                case 11: a = a.get(i / 4, j); break;
                case 12: a = (BitSet) a.clone(); break;
                default: b2 = next(2) == 0 ? BitSet.valueOf(a.toLongArray()) : BitSet.valueOf(a.toByteArray()); b2.flip(next(300)); break;
            }
            sum = sum * 31 + a.size();
            sum = sum * 31 + a.length();
            sum = sum * 31 + a.cardinality();
            sum = sum * 31 + a.hashCode();
            sum = sum * 31 + a.nextSetBit(i) + a.nextClearBit(i) + a.previousSetBit(j) * 3 + a.previousClearBit(j) * 7;
            sum = sum * 31 + (a.intersects(b2) ? 1 : 2) + (a.equals(b2) ? 4 : 8);
            if (round % 50 == 0) sum = sum * 31 + a.toString().hashCode() + Arrays.hashCode(a.toByteArray()) + Arrays.hashCode(a.toLongArray());
        }
        System.out.println("fuzz sum " + sum + " final " + s(a).length());
        System.out.println("done");
    }
}
