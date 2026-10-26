package s1;

public class LongMath {
    static long div(long a, long b) { return a / b; }
    static long rem(long a, long b) { return a % b; }

    static void tryDiv(long a, long b) {
        try {
            System.out.println("ldiv " + a + "/" + b + " = " + div(a, b));
            System.out.println("lrem " + a + "%" + b + " = " + rem(a, b));
        } catch (ArithmeticException e) {
            System.out.println("caught " + e.getMessage());
        }
    }

    static void cmp(long a, long b) {
        System.out.println(a + " vs " + b + ": < " + (a < b) + " <= " + (a <= b)
            + " > " + (a > b) + " >= " + (a >= b) + " == " + (a == b) + " != " + (a != b));
    }

    public static void main(String[] args) {
        long max = Long.MAX_VALUE;
        long min = Long.MIN_VALUE;
        System.out.println("max+1 = " + (max + 1));
        System.out.println("min-1 = " + (min - 1));
        System.out.println("max*2 = " + (max * 2));
        System.out.println("min*min = " + (min * min));
        System.out.println("-min = " + (-min));
        System.out.println("1e10*1e10 = " + (10000000000L * (10000000000L + args.length)));

        tryDiv(7, 2);
        tryDiv(-7, 2);
        tryDiv(7, -2);
        tryDiv(-7, -2);
        tryDiv(min, -1);
        tryDiv(5000000000L, 7);
        tryDiv(5, 0);

        cmp(1, 2);
        cmp(2, 1);
        cmp(5, 5);
        cmp(min, max);
        cmp(max, min);
        cmp(-1, 0);
        cmp(0x100000000L, 1);
        cmp(0xFFFFFFFFL, 0x100000000L);

        long v = 0x123456789ABCDEFL;
        System.out.println("l2i = " + (int) v);
        System.out.println("l2i hex = " + Integer.toHexString((int) v));
        System.out.println("l2i 2^32 = " + (int) (4294967296L + args.length));
        System.out.println("l2i 2^31 = " + (int) (2147483648L + args.length));
        System.out.println("l2i -1 = " + (int) (-1L + args.length));
        System.out.println("i2l -5 = " + (long) (-5 + args.length));
        int neg = -5;
        System.out.println("i2l neg hex = " + Long.toHexString(neg));

        long one = 1;
        System.out.println("1<<0 = " + (one << 0));
        System.out.println("1<<32 = " + (one << 32));
        System.out.println("1<<63 = " + (one << 63));
        System.out.println("1<<64 = " + (one << 64));
        System.out.println("1<<65 = " + (one << 65));
        System.out.println("1<<-1 = " + (one << -1));
        long n = -1024;
        System.out.println("-1024>>3 = " + (n >> 3));
        System.out.println("-1024>>63 = " + (n >> 63));
        System.out.println("-1024>>64 = " + (n >> 64));
        System.out.println("-1024>>>3 = " + (n >>> 3));
        System.out.println("-1024>>>60 = " + (n >>> 60));
        System.out.println("-1024>>>64 = " + (n >>> 64));
        System.out.println("-1024>>>127 = " + (n >>> 127));
        System.out.println("min>>>63 = " + (min >>> 63));
        int sh = 40;
        System.out.println("1<<(long 40) = " + (one << (long) sh));

        long a = 0xF0F0F0F0F0F0F0F0L, b = 0x0FF00FF00FF00FF0L;
        System.out.println("and = " + Long.toHexString(a & b));
        System.out.println("or = " + Long.toHexString(a | b));
        System.out.println("xor = " + Long.toHexString(a ^ b));
        System.out.println("not = " + Long.toHexString(~a));

        long x = 5;
        x += 1000;
        x *= 1000000007L;
        x -= 3;
        x %= 998244353L;
        x ^= 0xFFFFFFFFFFL;
        System.out.println("compound = " + x);
        long s = 0;
        for (int i = 1; i <= 100; i++) {
            s += (long) i * i * i * i * i * i * i * i * i * i;
        }
        System.out.println("sum i^10 (wraps) = " + s);
        long f = 1;
        for (int i = 1; i <= 25; i++) {
            f *= i;
            if (i >= 19) System.out.println(i + "! = " + f);
        }
        System.out.println("done");
    }
}
