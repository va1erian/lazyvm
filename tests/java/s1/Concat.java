package s1;

public class Concat {
    public static void main(String[] args) {
        byte b = -5;
        short s = 1234;
        char c = 'q';
        int i = -42;
        long l = 1234567890123L;
        float f = 3.25f;
        double d = -0.5;
        boolean t = true;
        String n = null;
        Object o = null;
        System.out.println("byte " + b);
        System.out.println("short " + s);
        System.out.println("char " + c);
        System.out.println("int " + i);
        System.out.println("long " + l);
        System.out.println("float " + f);
        System.out.println("double " + d);
        System.out.println("boolean " + t + " " + !t);
        System.out.println("null string " + n);
        System.out.println("null object " + o);
        System.out.println(n + n);
        System.out.println("" + c + c);
        System.out.println(c + c + "");
        System.out.println(c + 1);
        System.out.println("" + c + 1);
        System.out.println((char) (c + 1));
        System.out.println('a' + 'b' + "c");
        System.out.println("c" + 'a' + 'b');
        System.out.println(1 + 2 + "3" + 4 + 5);
        System.out.println("x" + (1 + 2));
        System.out.println(1 + 'a');
        System.out.println((char) ('a' + 1));
        System.out.println("" + 'a' + (char) 98);
        System.out.println("max " + Integer.MAX_VALUE + " min " + Integer.MIN_VALUE);
        System.out.println("lmax " + Long.MAX_VALUE + " lmin " + Long.MIN_VALUE);
        System.out.println("neg zero " + -0.0 + " " + -0.0f);
        System.out.println("nan " + (0.0 / (args.length)) + " " + (1.0f / args.length));
        String acc = "";
        for (int k = 0; k < 5; k++) acc += k;
        System.out.println(acc);
        acc += 'z';
        acc += 1.5;
        acc += null;
        acc += true;
        System.out.println(acc);
        String s2 = "a";
        s2 += s2 + s2;
        System.out.println(s2);

        StringBuilder sb = new StringBuilder();
        sb.append(b).append(' ').append(s).append(' ').append(c).append(' ').append(i).append(' ')
          .append(l).append(' ').append(f).append(' ').append(d).append(' ').append(t).append(' ')
          .append(n).append(' ').append(o).append(' ').append("str").append(' ').append((Object) "obj");
        System.out.println(sb.toString());
        System.out.println(sb.length());
        sb.setLength(0);
        sb.append('a' + 1).append((char) ('a' + 1)).append(1.0f).append(100L);
        System.out.println(sb);
        sb.insert(0, "<<").append(">>").reverse();
        System.out.println(sb);
        char[] arr = {'x', 'y', 'z'};
        sb.setLength(0);
        sb.append(arr).append(arr, 1, 2);
        System.out.println(sb);
        System.out.println(new StringBuilder("abc").append(1).append("def").insert(3, '-'));
        System.out.println(new StringBuilder("hello").deleteCharAt(1).delete(0, 1).replace(0, 1, "LL"));
        System.out.println(String.valueOf(arr) + String.valueOf(3.0) + String.valueOf(true));
        System.out.println("done");
    }
}
