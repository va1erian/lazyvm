package s1;

public class IntMath {
    static int div(int a, int b) { return a / b; }
    static int rem(int a, int b) { return a % b; }

    static void tryDiv(int a, int b) {
        try {
            System.out.println("idiv " + a + "/" + b + " = " + div(a, b));
            System.out.println("irem " + a + "%" + b + " = " + rem(a, b));
        } catch (ArithmeticException e) {
            System.out.println("caught " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        int max = Integer.MAX_VALUE;
        int min = Integer.MIN_VALUE;
        System.out.println("max+1 = " + (max + 1));
        System.out.println("min-1 = " + (min - 1));
        System.out.println("max*2 = " + (max * 2));
        System.out.println("max*max = " + (max * max));
        System.out.println("min*min = " + (min * min));
        System.out.println("-min = " + (-min));
        System.out.println("100000*100000 = " + (100000 * (args.length + 100000)));

        tryDiv(7, 2);
        tryDiv(-7, 2);
        tryDiv(7, -2);
        tryDiv(-7, -2);
        tryDiv(min, -1);
        tryDiv(min, 1);
        tryDiv(0, -5);
        tryDiv(5, 0);
        tryDiv(0, 0);

        int one = 1;
        System.out.println("1<<0 = " + (one << 0));
        System.out.println("1<<31 = " + (one << 31));
        System.out.println("1<<32 = " + (one << 32));
        System.out.println("1<<33 = " + (one << 33));
        System.out.println("1<<-1 = " + (one << -1));
        System.out.println("1<<-31 = " + (one << -31));
        int neg = -256;
        System.out.println("-256>>4 = " + (neg >> 4));
        System.out.println("-256>>31 = " + (neg >> 31));
        System.out.println("-256>>32 = " + (neg >> 32));
        System.out.println("-256>>35 = " + (neg >> 35));
        System.out.println("-256>>>4 = " + (neg >>> 4));
        System.out.println("-256>>>28 = " + (neg >>> 28));
        System.out.println("-256>>>32 = " + (neg >>> 32));
        System.out.println("-256>>>-1 = " + (neg >>> -1));
        System.out.println("min>>>31 = " + (min >>> 31));
        System.out.println("min>>31 = " + (min >> 31));

        int a = 0xF0F0F0F0, b = 0x0FF00FF0;
        System.out.println("and = " + (a & b));
        System.out.println("or = " + (a | b));
        System.out.println("xor = " + (a ^ b));
        System.out.println("not = " + (~a));
        System.out.println("hex and = " + Integer.toHexString(a & b));
        System.out.println("hex xor = " + Integer.toHexString(a ^ b));

        int big = 0x12345678;
        System.out.println("i2b 0x12345678 = " + (byte) big);
        System.out.println("i2b 200 = " + (byte) (one + 199));
        System.out.println("i2b -129 = " + (byte) (one - 130));
        System.out.println("i2c -1 = " + (int) (char) (one - 2));
        System.out.println("i2c 0x12345678 = " + (int) (char) big);
        System.out.println("i2s 0x12348765 = " + (short) (big + 0x8765 - 0x5678));
        System.out.println("i2s 40000 = " + (short) (one + 39999));
        System.out.println("i2s -40000 = " + (short) (one - 40001));

        int x = 5;
        x += 1000;
        System.out.println("x += 1000 -> " + x);
        x -= 70000;
        System.out.println("x -= 70000 -> " + x);
        x += 100;
        x += 200;
        x += 32767;
        x += 32768;
        System.out.println("x after more += -> " + x);
        x *= 3;
        x /= 7;
        x %= 1000;
        x <<= 3;
        x >>= 1;
        x ^= 0xFF;
        System.out.println("compound ops -> " + x);
        int y = 0;
        y++;
        ++y;
        y--;
        System.out.println("inc/dec -> " + y + " " + (y++) + " " + (++y) + " " + y);
        byte bb = 127;
        bb++;
        System.out.println("byte 127++ = " + bb);
        short ss = -32768;
        ss--;
        System.out.println("short -32768-- = " + ss);
        char cc = 65535;
        cc++;
        System.out.println("char 65535++ = " + (int) cc);
        System.out.println("done");
    }
}
