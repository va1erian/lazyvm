// ARGS: alpha beta  gamma
package s1;

public class Args {
    public static void main(String[] args) {
        System.out.println("args.length = " + args.length);
        for (int i = 0; i < args.length; i++) {
            System.out.println("args[" + i + "] = [" + args[i] + "] len " + args[i].length());
        }
        System.out.println("done");
    }
}
