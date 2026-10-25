package io.github.evildarkarchon.andysdice.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * The History tab's dates and times (spec §3.2 "History tab"): fixed English whatever the system locale, day headers
 * "Today", "Yesterday", then "Wed 21 Oct" with the year only when it isn't the current one, and 24-hour {@code HH:mm}
 * in the given time zone. Pure.
 */
class HistoryDatesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 25);

    @Test
    void theLastTwoDaysAreNamed() {
        assertThat(HistoryDates.dayHeader(TODAY, TODAY)).isEqualTo("Today");
        assertThat(HistoryDates.dayHeader(TODAY.minusDays(1), TODAY)).isEqualTo("Yesterday");
    }

    @Test
    void anOlderDayInThisYearReadsWeekdayDayAndMonth() {
        assertThat(HistoryDates.dayHeader(LocalDate.of(2026, 10, 21), TODAY)).isEqualTo("Wed 21 Oct");
        assertThat(HistoryDates.dayHeader(LocalDate.of(2026, 9, 1), TODAY)).isEqualTo("Tue 1 Sep");
    }

    @Test
    void aDayInAnotherYearAddsTheYear() {
        assertThat(HistoryDates.dayHeader(LocalDate.of(2025, 10, 21), TODAY)).isEqualTo("Tue 21 Oct 2025");
        // Yesterday across New Year is still "Yesterday".
        assertThat(HistoryDates.dayHeader(LocalDate.of(2025, 12, 31), LocalDate.of(2026, 1, 1)))
                .isEqualTo("Yesterday");
    }

    @Test
    void theWordsStayEnglishWhateverTheSystemLocale() {
        Locale was = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertThat(HistoryDates.dayHeader(LocalDate.of(2026, 3, 4), TODAY)).isEqualTo("Wed 4 Mar");
            assertThat(HistoryDates.full(Instant.parse("2026-03-04T08:07:06Z"), ZoneId.of("UTC")))
                    .isEqualTo("Wednesday 4 March 2026, 08:07:06");
        } finally {
            Locale.setDefault(was);
        }
    }

    @Test
    void timesAre24HourInTheGivenZone() {
        Instant at = Instant.parse("2026-10-21T21:05:09Z");

        assertThat(HistoryDates.time(at, ZoneId.of("UTC"))).isEqualTo("21:05");
        assertThat(HistoryDates.time(at, ZoneId.of("America/New_York"))).isEqualTo("17:05");
        assertThat(HistoryDates.day(at, ZoneId.of("Asia/Tokyo"))).isEqualTo(LocalDate.of(2026, 10, 22));
    }
}
