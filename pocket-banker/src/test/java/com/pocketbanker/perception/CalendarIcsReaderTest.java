package com.pocketbanker.perception;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class CalendarIcsReaderTest {
  private final CalendarIcsReader reader = new CalendarIcsReader();

  String calendar(String events) {
    return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//PocketBanker//Tests//CN\r\n"
        + events.replace("\n", "\r\n")
        + "END:VCALENDAR\r\n";
  }

  @Test
  void allDayEndIsExclusiveAndTextIsNotReturned() {
    var rows =
        reader.read(
            calendar(
                """
        BEGIN:VEVENT
        UID:trip
        DTSTART;VALUE=DATE:20261001
        DTEND;VALUE=DATE:20261008
        X-PB-KIND:trip
        LOCATION:三亚
        DESCRIPTION:PRIVATE ORIGINAL CALENDAR TEXT
        END:VEVENT
        """),
            LocalDate.of(2026, 9, 30),
            "Asia/Shanghai");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).end()).isEqualTo(LocalDate.of(2026, 10, 7));
    assertThat(rows.toString()).doesNotContain("PRIVATE");
  }

  @Test
  void recurrenceExclusionAndMovedInstanceHaveStableIdentity() {
    var rows =
        reader.read(
            calendar(
                """
        BEGIN:VEVENT
        UID:rent
        DTSTART;VALUE=DATE:20261001
        RRULE:FREQ=MONTHLY;COUNT=3
        EXDATE;VALUE=DATE:20261101
        X-PB-KIND:payment
        X-PB-AMOUNT:100.25
        END:VEVENT
        BEGIN:VEVENT
        UID:rent
        RECURRENCE-ID;VALUE=DATE:20261201
        DTSTART;VALUE=DATE:20261203
        X-PB-KIND:payment
        X-PB-AMOUNT:100.25
        END:VEVENT
        """),
            LocalDate.of(2026, 9, 30),
            "Asia/Shanghai");
    assertThat(rows).hasSize(2);
    assertThat(rows)
        .extracting(CalendarIcsReader.Entry::start)
        .containsExactly(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 3));
    assertThat(rows).extracting(CalendarIcsReader.Entry::identity).doesNotHaveDuplicates();
  }

  @Test
  void timezoneAndDstRetainLocalOccurrenceHour() {
    var rows =
        reader.read(
            calendar(
                """
        BEGIN:VEVENT
        UID:dst
        DTSTART;TZID=America/New_York:20261031T090000
        DTEND;TZID=America/New_York:20261031T100000
        RRULE:FREQ=DAILY;COUNT=3
        X-PB-KIND:trip
        LOCATION:纽约
        END:VEVENT
        """),
            LocalDate.of(2026, 10, 30),
            "America/New_York");
    assertThat(rows).hasSize(3);
    assertThat(rows.get(0).startsAt()).isEqualTo("2026-10-31T13:00:00Z");
    assertThat(rows.get(1).startsAt()).isEqualTo("2026-11-01T14:00:00Z");
  }

  @Test
  void allDayDurationAndCancelledOverrideWithoutStartAreSupported() {
    var rows =
        reader.read(
            calendar(
                """
        BEGIN:VEVENT
        UID:duration
        DTSTART;VALUE=DATE:20261001
        DURATION:P2D
        RRULE:FREQ=WEEKLY;COUNT=2
        X-PB-KIND:trip
        LOCATION:三亚
        END:VEVENT
        BEGIN:VEVENT
        UID:duration
        RECURRENCE-ID;VALUE=DATE:20261008
        STATUS:CANCELLED
        END:VEVENT
        """),
            LocalDate.of(2026, 9, 30),
            "Asia/Shanghai");
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).end()).isEqualTo(LocalDate.of(2026, 10, 2));
    assertThat(rows.get(1).cancelled()).isTrue();
    assertThat(rows.get(1).start()).isEqualTo(LocalDate.of(2026, 10, 8));
  }

  @Test
  void movedPaymentInheritsAmountCurrencyAndDuration() {
    var rows =
        reader.read(
            calendar(
                """
        BEGIN:VEVENT
        UID:inherit
        DTSTART;VALUE=DATE:20261001
        DTEND;VALUE=DATE:20261003
        RRULE:FREQ=MONTHLY;COUNT=2
        X-PB-KIND:payment
        X-PB-AMOUNT:123.45
        X-PB-CURRENCY:CNY
        END:VEVENT
        BEGIN:VEVENT
        UID:inherit
        RECURRENCE-ID;VALUE=DATE:20261101
        DTSTART;VALUE=DATE:20261104
        END:VEVENT
        """),
            LocalDate.of(2026, 9, 30),
            "Asia/Shanghai");
    assertThat(rows.get(1).amount()).isEqualTo("123.45");
    assertThat(rows.get(1).currency()).isEqualTo("CNY");
    assertThat(rows.get(1).end()).isEqualTo(LocalDate.of(2026, 11, 5));
  }

  @Test
  void birthdaysMayStartMoreThanTwentyYearsAgo() {
    var rows =
        reader.read(
            calendar(
                """
        BEGIN:VEVENT
        UID:birthday
        DTSTART;VALUE=DATE:19601101
        RRULE:FREQ=YEARLY
        X-PB-KIND:birthday
        END:VEVENT
        """),
            LocalDate.of(2026, 10, 5),
            "Asia/Shanghai");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).start()).isEqualTo(LocalDate.of(2026, 11, 1));
  }

  @Test
  void denseOrUnsupportedInputsFailExplicitly() {
    assertThatThrownBy(
            () ->
                reader.read(
                    calendar(
                        """
        BEGIN:VEVENT
        UID:dense
        DTSTART:20261001T000000Z
        RRULE:FREQ=SECONDLY
        X-PB-KIND:trip
        END:VEVENT
        """),
                    LocalDate.of(2026, 9, 30),
                    "UTC"))
        .hasMessageContaining("calendar_subdaily_recurrence_unsupported");
    assertThatThrownBy(() -> reader.read("x".repeat(100001), LocalDate.now(), "UTC"))
        .hasMessageContaining("calendar_input_too_large");
  }
}
