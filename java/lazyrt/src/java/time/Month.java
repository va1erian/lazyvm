package java.time;

public enum Month {
    JANUARY, FEBRUARY, MARCH, APRIL, MAY, JUNE, JULY, AUGUST, SEPTEMBER, OCTOBER, NOVEMBER, DECEMBER;

    /** values() copies its array on every call; the constants never change. */
    private static final Month[] ALL = values();

    public static Month of(int month) {
        if (month < 1 || month > 12) {
            throw new DateTimeException("Invalid value for MonthOfYear: " + month);
        }
        return ALL[month - 1];
    }

    public int getValue() {
        return ordinal() + 1;
    }
}
