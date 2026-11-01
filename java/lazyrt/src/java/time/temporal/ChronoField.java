package java.time.temporal;

/** The fields of a date-time that LocalDateTime and the formatters know. */
public enum ChronoField implements TemporalField {
    NANO_OF_SECOND("NanoOfSecond"),
    MILLI_OF_SECOND("MilliOfSecond"),
    SECOND_OF_MINUTE("SecondOfMinute"),
    MINUTE_OF_HOUR("MinuteOfHour"),
    HOUR_OF_DAY("HourOfDay"),
    CLOCK_HOUR_OF_AMPM("ClockHourOfAmPm"),
    AMPM_OF_DAY("AmPmOfDay"),
    DAY_OF_WEEK("DayOfWeek"),
    DAY_OF_MONTH("DayOfMonth"),
    DAY_OF_YEAR("DayOfYear"),
    MONTH_OF_YEAR("MonthOfYear"),
    YEAR_OF_ERA("YearOfEra"),
    YEAR("Year");

    private final String label;

    ChronoField(String label) {
        this.label = label;
    }

    public String toString() {
        return label;
    }
}
