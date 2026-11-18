package de.caritas.cob.userservice.api.service.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The same dates the Frontend's calendarHolidays.ts shades as days off (contract v1.2). */
class GermanPublicHolidaysTest {

  @Test
  void easterSunday_Should_FollowTheGregorianComputus() {
    assertThat(GermanPublicHolidays.easterSunday(2026)).isEqualTo(LocalDate.of(2026, 4, 5));
    assertThat(GermanPublicHolidays.easterSunday(2027)).isEqualTo(LocalDate.of(2027, 3, 28));
    assertThat(GermanPublicHolidays.easterSunday(2019)).isEqualTo(LocalDate.of(2019, 4, 21));
  }

  @ParameterizedTest
  @CsvSource({
    // nationwide
    "2026-12-25, BE, true",
    "2027-03-26, HH, true", // Good Friday
    "2027-05-06, NW, true", // Ascension (Easter + 39)
    "2026-10-03, SN, true",
    // regional
    "2027-05-27, BY, true", // Corpus Christi (Easter + 60)
    "2027-05-27, BE, false",
    "2026-11-18, SN, true", // Buß- und Bettag: Wednesday before 23 November
    "2026-11-18, BY, false",
    "2026-01-06, BW, true",
    "2026-01-06, NW, false",
    // since
    "2017-10-31, HH, false",
    "2018-10-31, HH, true",
    "2018-03-08, BE, false",
    "2019-03-08, BE, true",
    "2022-03-08, MV, false",
    "2023-03-08, MV, true",
    // an ordinary working day
    "2026-11-19, SN, false"
  })
  void isHoliday_Should_CountOnlyTheStatutoryHolidaysOfTheRegion(
      LocalDate date, String region, boolean holiday) {
    assertThat(GermanPublicHolidays.isHoliday(date, region)).isEqualTo(holiday);
  }

  @Test
  void isHoliday_Should_CountNothing_When_NoOrAnUnknownRegionIsSet() {
    assertThat(GermanPublicHolidays.isHoliday(LocalDate.of(2026, 12, 25), null)).isFalse();
    assertThat(GermanPublicHolidays.isHoliday(LocalDate.of(2026, 12, 25), "XX")).isFalse();
  }
}
