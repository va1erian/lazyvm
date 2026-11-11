package bench;

/**
 * 3D gradient noise in float, sampled at double coordinates over a grid, as
 * terrain generators do: floor by cast, long hashing of lattice points, float
 * gradients and a quintic fade, summed over octaves.
 */
public final class Noise {
    private static final long SEED = 0x5DEECE66DL;

    private Noise() {
    }

    public static void main(String[] args) {
        int size = 96;
        int rounds = 2 * Report.scale(args);
        long start = System.nanoTime();
        double sum = 0;
        for (int r = 0; r < rounds; r++) {
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    sum += octaves(x * 0.031, y * 0.027, r * 0.5);
                }
            }
        }
        Report.print("noise", Double.doubleToLongBits(sum), start);
    }

    private static float octaves(double x, double y, double z) {
        float total = 0;
        float amp = 1;
        for (int o = 0; o < 4; o++) {
            total += amp * noise(x, y, z);
            x *= 2;
            y *= 2;
            z *= 2;
            amp *= 0.5f;
        }
        return total;
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    private static float noise(double x, double y, double z) {
        int xi = floor(x);
        int yi = floor(y);
        int zi = floor(z);
        float fx = (float) (x - xi);
        float fy = (float) (y - yi);
        float fz = (float) (z - zi);
        float u = fade(fx);
        float v = fade(fy);
        float w = fade(fz);
        float x00 = lerp(u, grad(xi, yi, zi, fx, fy, fz), grad(xi + 1, yi, zi, fx - 1, fy, fz));
        float x10 = lerp(u, grad(xi, yi + 1, zi, fx, fy - 1, fz), grad(xi + 1, yi + 1, zi, fx - 1, fy - 1, fz));
        float x01 = lerp(u, grad(xi, yi, zi + 1, fx, fy, fz - 1), grad(xi + 1, yi, zi + 1, fx - 1, fy, fz - 1));
        float x11 = lerp(u, grad(xi, yi + 1, zi + 1, fx, fy - 1, fz - 1),
                grad(xi + 1, yi + 1, zi + 1, fx - 1, fy - 1, fz - 1));
        return lerp(w, lerp(v, x00, x10), lerp(v, x01, x11));
    }

    private static float fade(float t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static float lerp(float t, float a, float b) {
        return a + t * (b - a);
    }

    private static float grad(long x, long y, long z, float dx, float dy, float dz) {
        long h = SEED ^ x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL ^ z * 0x165667B19E3779F9L;
        h = (h ^ (h >>> 29)) * 0xBF58476D1CE4E5B9L;
        int g = (int) (h >>> 60);
        float a = (g & 1) == 0 ? dx : -dx;
        float b = (g & 2) == 0 ? dy : -dy;
        float c = (g & 4) == 0 ? dz : -dz;
        return (g & 8) == 0 ? a + b : b + c;
    }
}
