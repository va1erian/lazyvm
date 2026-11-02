package bench;

/** Work the integer fast paths do not cover: double and long arithmetic, conversions and ldc constants. */
public final class Numeric {
    private Numeric() {
    }

    public static void main(String[] args) {
        int size = 280 * Report.scale(args);
        long start = System.nanoTime();
        long sum = 0;
        for (int octave = 0; octave < 4; octave++) {
            double frequency = 0.0371 * (1 << octave);
            double amplitude = 1.0 / (1 << octave);
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    double v = noise(x * frequency + 0.123456789, y * frequency + 0.987654321, 77L + octave);
                    sum += (long) (v * amplitude * 1000000.0);
                }
            }
        }
        sum = sum * 31 + mix(size * 2000);
        Report.print("numeric", sum, start);
    }

    /** A xorshift-multiply chain on longs. */
    private static long mix(int rounds) {
        long h = 0x2545F4914F6CDD1DL;
        for (int i = 0; i < rounds; i++) {
            h ^= h << 13;
            h ^= h >>> 7;
            h ^= h << 17;
            h *= 0x9E3779B97F4A7C15L;
            h += (long) i * 31L;
        }
        return h;
    }

    private static long hash(long x, long y, long seed) {
        long h = x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL ^ seed;
        h ^= h >>> 31;
        h *= 0x94D049BB133111EBL;
        return h ^ (h >>> 29);
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double corner(long cx, long cy, double dx, double dy, long seed) {
        double angle = (hash(cx, cy, seed) & 1023L) * (6.283185307179586 / 1024.0);
        // A cheap polynomial stands in for sin and cos, which differ between platforms' libms.
        double gx = 1.0 - 2.0 * (angle / 6.283185307179586);
        double gy = gx * gx - 0.5;
        return gx * dx + gy * dy;
    }

    private static double noise(double x, double y, long seed) {
        double fx = Math.floor(x);
        double fy = Math.floor(y);
        long cx = (long) fx;
        long cy = (long) fy;
        double dx = x - fx;
        double dy = y - fy;
        double u = fade(dx);
        double v = fade(dy);
        double a = corner(cx, cy, dx, dy, seed);
        double b = corner(cx + 1, cy, dx - 1.0, dy, seed);
        double c = corner(cx, cy + 1, dx, dy - 1.0, seed);
        double d = corner(cx + 1, cy + 1, dx - 1.0, dy - 1.0, seed);
        return lerp(lerp(a, b, u), lerp(c, d, u), v);
    }
}
