package s1;

public class Sieve {
    public static void main(String[] args) {
        int n = 10000;
        boolean[] composite = new boolean[n];
        for (int i = 2; (long) i * i < n; i++) {
            if (!composite[i]) {
                for (int j = i * i; j < n; j += i) composite[j] = true;
            }
        }
        int count = 0;
        int[] primes = new int[n];
        for (int i = 2; i < n; i++) {
            if (!composite[i]) primes[count++] = i;
        }
        System.out.println("primes below " + n + ": " + count);
        System.out.println("first 10:");
        for (int i = 0; i < 10; i++) System.out.println(primes[i]);
        System.out.println("last 10:");
        for (int i = count - 10; i < count; i++) System.out.println(primes[i]);
        long sum = 0;
        for (int i = 0; i < count; i++) sum += primes[i];
        System.out.println("sum " + sum);
        int twins = 0;
        for (int i = 1; i < count; i++) if (primes[i] - primes[i - 1] == 2) twins++;
        System.out.println("twin pairs " + twins);
        System.out.println("done");
    }
}
