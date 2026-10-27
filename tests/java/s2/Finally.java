package s2;

public class Finally {
    static StringBuilder log = new StringBuilder();

    static int normal() {
        try {
            log.append("try;");
        } finally {
            log.append("finally;");
        }
        return 1;
    }

    static int withReturn() {
        try {
            log.append("try;");
            return 10;
        } finally {
            log.append("finally;");
        }
    }

    static int overriding() {
        try {
            return 1;
        } finally {
            return 2;
        }
    }

    @SuppressWarnings("finally")
    static int swallow() {
        try {
            throw new RuntimeException("lost");
        } finally {
            return 3;
        }
    }

    static int noEffect() {
        int x = 1;
        try {
            return x;
        } finally {
            x = 99;
            log.append("x=" + x + ";");
        }
    }

    static int[] arrayEffect() {
        int[] a = {1};
        try {
            return a;
        } finally {
            a[0] = 99;
        }
    }

    static String sbEffect() {
        StringBuilder sb = new StringBuilder("a");
        try {
            return sb.toString();
        } finally {
            sb.append("b");
        }
    }

    static void loops() {
        for (int i = 0; i < 4; i++) {
            try {
                if (i == 1) continue;
                if (i == 3) break;
                log.append("body" + i + ";");
            } finally {
                log.append("fin" + i + ";");
            }
        }
        int j = 0;
        while (true) {
            try {
                j++;
                if (j > 2) break;
            } finally {
                log.append("w" + j + ";");
            }
        }
    }

    static void exceptionPath() {
        try {
            try {
                log.append("t;");
                throw new IllegalStateException("x");
            } finally {
                log.append("f1;");
            }
        } catch (IllegalStateException e) {
            log.append("c;");
        } finally {
            log.append("f2;");
        }
    }

    static void finallyThrows() {
        try {
            try {
                throw new IllegalStateException("original");
            } finally {
                throw new IllegalArgumentException("replacement");
            }
        } catch (RuntimeException e) {
            log.append("got " + e.getMessage() + ";");
        }
    }

    static int nestedReturn() {
        try {
            try {
                return 1;
            } finally {
                log.append("inner;");
            }
        } finally {
            log.append("outer;");
        }
    }

    static int labeled() {
        int count = 0;
        outer:
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                try {
                    if (j == 1) continue outer;
                    count += 10;
                } finally {
                    count++;
                }
            }
        }
        return count;
    }

    static void flush(String label, int result) {
        System.out.println(label + " -> " + result + " log=" + log);
        log.setLength(0);
    }

    public static void main(String[] args) {
        flush("normal", normal());
        flush("withReturn", withReturn());
        flush("overriding", overriding());
        flush("swallow", swallow());
        flush("noEffect", noEffect());
        System.out.println("arrayEffect " + arrayEffect()[0]);
        System.out.println("sbEffect " + sbEffect());
        loops();
        flush("loops", 0);
        exceptionPath();
        flush("exceptionPath", 0);
        finallyThrows();
        flush("finallyThrows", 0);
        flush("nestedReturn", nestedReturn());
        flush("labeled", labeled());
        System.out.println("done");
    }
}
