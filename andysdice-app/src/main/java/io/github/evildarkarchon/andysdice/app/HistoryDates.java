package io.github.evildarkarchon.andysdice.app;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * How the History tab writes dates and times (spec §3.2 "History tab"). Like all UI text they are fixed English
 * whatever the system locale: day headers read "Today", "Yesterday", then "Wed 21 Oct", adding the year when it isn't
 * the current one ("Wed 21 Oct 2025"); times are 24-hour {@code HH:mm} in the system time zone, which the caller
 * passes in.
 *
 * <p>{@link Locale#ENGLISH}, not {@code Locale.UK}: the CLDR data for {@code en-GB} abbreviates September to "Sept",
 * which would break the three-letter month the spec shows.
 *
 * <p>Pure; safe on any thread.
 */
final class HistoryDates {

    private static final DateTimeFormatter THIS_YEAR = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter OTHER_YEAR = DateTimeFormatter.ofPattern("EEE d MMM uuuu", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("EEEE d MMMM uuuu, HH:mm:ss",
            Locale.ENGLISH);

    private HistoryDates() {}

    /**
     * Returns the day header for the Rolls of one day.
     *
     * @param day the Rolls' local date
     * @param today the local date now
     * @return "Today", "Yesterday", or the weekday, day and month, with the year if it isn't {@code today}'s
     */
    static String dayHeader(LocalDate day, LocalDate today) {
        if (day.equals(today)) {
            return "Today";
        }
        if (day.equals(today.minusDays(1))) {
            return "Yesterday";
        }
        return (day.getYear() == today.getYear() ? THIS_YEAR : OTHER_YEAR).format(day);
    }

    /**
     * Returns the local date of an instant, which decides the day header it falls under.
     *
     * @param at the instant
     * @param zone the system time zone
     * @return its date there
     */
    static LocalDate day(Instant at, ZoneId zone) {
        return LocalDate.ofInstant(at, zone);
    }

    /**
     * Returns a row's time.
     *
     * @param at when the Roll was revealed
     * @param zone the system time zone
     * @return 24-hour {@code HH:mm}
     */
    static String time(Instant at, ZoneId zone) {
        return TIME.format(at.atZone(zone));
    }

    /**
     * Returns a row's full date and time, shown on hover.
     *
     * @param at when the Roll was revealed
     * @param zone the system time zone
     * @return for example "Wednesday 21 October 2026, 14:05:09"
     */
    static String full(Instant at, ZoneId zone) {
        return FULL.format(at.atZone(zone));
    }
}
