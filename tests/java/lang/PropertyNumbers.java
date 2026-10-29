// VMARGS: -Dp0=0x10 -Dp1=#10 -Dp2=010 -Dp3=-0x10 -Dp4=99999999999 -Dp5=2147483647 -Dp6=2147483648 -Dp7=-2147483648 -Dp8=-2147483649 -Dp9= -Dp10=abc -Dp11=+7 -Dp12=-#F -Dp13=9223372036854775807 -Dp14=9223372036854775808 -Dp15=0X1f -Dp16=-010 -Dp17=0x -Db1=true -Db2=TRUE -Db3=yes -Db4=
package lang;

/** Integer.getInteger, Long.getLong and Boolean.getBoolean on properties. */
public class PropertyNumbers {
    public static void main(String[] args) {
        for (int i = 0; i <= 17; i++) {
            String n = "p" + i;
            System.out.println(n + " " + Integer.getInteger(n) + " " + Integer.getInteger(n, 7) + " "
                    + Integer.getInteger(n, Integer.valueOf(9)) + " | " + Long.getLong(n) + " "
                    + Long.getLong(n, 7L) + " " + Long.getLong(n, Long.valueOf(9)));
        }
        String[] odd = {"nosuch", "", null};
        for (String n : odd) {
            System.out.println("[" + n + "] " + Integer.getInteger(n) + " " + Integer.getInteger(n, 7) + " "
                    + Integer.getInteger(n, (Integer) null) + " " + Long.getLong(n) + " " + Long.getLong(n, 7L));
        }
        System.out.println(Boolean.getBoolean(null) + " " + Boolean.getBoolean("") + " "
                + Boolean.getBoolean("nosuch"));
        System.out.println(Boolean.getBoolean("b1") + " " + Boolean.getBoolean("b2") + " "
                + Boolean.getBoolean("b3") + " " + Boolean.getBoolean("b4"));
    }
}
