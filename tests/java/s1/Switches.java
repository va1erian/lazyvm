package s1;

public class Switches {
    static String dense(int x) {
        switch (x) {
            case 0: return "zero";
            case 1: return "one";
            case 2: return "two";
            case 3: return "three";
            case 5: return "five";
            case 6: return "six";
            default: return "other";
        }
    }

    static String sparse(int x) {
        switch (x) {
            case Integer.MIN_VALUE: return "min";
            case -1000000: return "-1e6";
            case -1: return "minus one";
            case 7: return "seven";
            case 1000: return "thousand";
            case 123456789: return "big";
            case Integer.MAX_VALUE: return "max";
            default: return "none";
        }
    }

    static String fall(int x) {
        StringBuilder sb = new StringBuilder();
        switch (x) {
            case 1: sb.append("a");
            case 2: sb.append("b");
            case 3: sb.append("c"); break;
            case 4: sb.append("d");
            default: sb.append("z");
            case 5: sb.append("e");
        }
        return sb.toString();
    }

    static String noDefault(int x) {
        String r = "unset";
        switch (x) {
            case 10: r = "ten"; break;
            case 20: r = "twenty"; break;
        }
        return r;
    }

    static String ch(char c) {
        switch (c) {
            case 'a': return "letter a";
            case 'b': return "letter b";
            case '0': return "digit zero";
            case ' ': return "space";
            case '\n': return "newline";
            case 'é': return "e acute";
            case '￿': return "max char";
            default: return "char " + (int) c;
        }
    }

    static String by(byte b) {
        switch (b) {
            case -128: return "min byte";
            case -1: return "minus one";
            case 0: return "zero";
            case 127: return "max byte";
            default: return "byte " + b;
        }
    }

    static String sh(short s) {
        switch (s) {
            case Short.MIN_VALUE: return "min short";
            case 300: return "three hundred";
            case Short.MAX_VALUE: return "max short";
            default: return "short " + s;
        }
    }

    static int negDense(int x) {
        switch (x) {
            case -3: return 30;
            case -2: return 20;
            case -1: return 10;
            case 0: return 0;
            case 1: return -10;
            default: return 99;
        }
    }

    static String onString(String s) {
        switch (s) {
            case "alpha": return "A";
            case "beta": return "B";
            case "Aa": return "collide1";
            case "BB": return "collide2";
            default: return "?";
        }
    }

    public static void main(String[] args) {
        for (int i = -1; i <= 7; i++) System.out.println("dense " + i + " -> " + dense(i));
        int[] keys = {Integer.MIN_VALUE, Integer.MIN_VALUE + 1, -1000000, -2, -1, 0, 7, 8, 1000, 123456789,
            Integer.MAX_VALUE - 1, Integer.MAX_VALUE};
        for (int k : keys) System.out.println("sparse " + k + " -> " + sparse(k));
        for (int i = 0; i <= 6; i++) System.out.println("fall " + i + " -> " + fall(i));
        System.out.println("noDefault 10 -> " + noDefault(10));
        System.out.println("noDefault 15 -> " + noDefault(15));
        System.out.println("noDefault 20 -> " + noDefault(20));
        char[] cs = {'a', 'b', 'c', '0', ' ', '\n', 'é', '￿'};
        for (char c : cs) System.out.println("ch -> " + ch(c));
        byte[] bs = {-128, -1, 0, 1, 127};
        for (byte b : bs) System.out.println("by -> " + by(b));
        short[] ss = {Short.MIN_VALUE, 300, 301, Short.MAX_VALUE};
        for (short s : ss) System.out.println("sh -> " + sh(s));
        for (int i = -4; i <= 2; i++) System.out.println("negDense " + i + " -> " + negDense(i));
        String[] strs = {"alpha", "beta", "Aa", "BB", "gamma"};
        for (String s : strs) System.out.println("string " + s + " -> " + onString(s));
        System.out.println("done");
    }
}
