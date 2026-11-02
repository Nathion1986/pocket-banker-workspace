package com.pocketbanker.perception;

import com.pocketbanker.contracts.BackendException;
import com.pocketbanker.security.PerceptionAccess;
import java.io.StringReader;
import java.time.*;
import java.time.temporal.*;
import java.util.*;
import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.*;
import org.springframework.stereotype.Component;

/** Bounded RFC5545 ingestion. Raw calendar text is never stored or passed to a model. */
@Component
public class CalendarIcsReader {
  public record Entry(
      String identity,
      String kind,
      LocalDate start,
      LocalDate end,
      String city,
      String amount,
      String currency,
      String startsAt,
      String endsAt,
      String timezone,
      boolean allDay,
      boolean cancelled) {}

  public List<Entry> read(String input, LocalDate from, String timezone) {
    if (input == null || input.length() > 100_000)
      throw new BackendException(413, "calendar_input_too_large");
    try {
      ZoneId fallback = ZoneId.of(timezone);
      var calendar = new CalendarBuilder().build(new StringReader(input));
      List<VEvent> events = calendar.getComponents("VEVENT");
      if (events.size() > 200) throw new BackendException(413, "calendar_component_limit");
      Map<String, VEvent> masters = new LinkedHashMap<>();
      Map<String, VEvent> overrides = new LinkedHashMap<>();
      for (var event : events) {
        String uid = value(event, "UID");
        if (uid == null || uid.length() > 200) throw invalid();
        var recurrence = event.<RecurrenceId<Temporal>>getProperty("RECURRENCE-ID");
        if (recurrence.isPresent()) {
          if (recurrence.get().getParameter("RANGE").isPresent())
            throw new BackendException(400, "calendar_range_override_unsupported");
          String key = uid + "|" + identity(recurrence.get().getDate(), fallback);
          if (overrides.put(key, event) != null) throw invalid();
        } else if (masters.put(uid, event) != null) throw invalid();
      }
      List<Entry> result = new ArrayList<>();
      for (var item : masters.entrySet()) {
        VEvent master = item.getValue();
        if ("CANCELLED".equals(value(master, "STATUS"))) {
          overrides.keySet().removeIf(key -> key.startsWith(item.getKey() + "|"));
          continue;
        }
        Temporal seed = master.getStartDate().orElseThrow(CalendarIcsReader::invalid).getDate();
        if (date(seed, fallback).isBefore(LocalDate.of(1900, 1, 1))) throw invalid();
        Set<Temporal> dates = new LinkedHashSet<>();
        dates.add(seed);
        var rules = master.<RRule<Temporal>>getProperties("RRULE");
        if (rules.size() > 1 || !master.getProperties("EXRULE").isEmpty())
          throw new BackendException(400, "calendar_recurrence_unsupported");
        for (var rule : rules) {
          var recur = rule.getRecur();
          if (!Set.of("DAILY", "WEEKLY", "MONTHLY", "YEARLY").contains(recur.getFrequency().name())
              || !recur.getHourList().isEmpty()
              || !recur.getMinuteList().isEmpty()
              || !recur.getSecondList().isEmpty())
            throw new BackendException(400, "calendar_subdaily_recurrence_unsupported");
          dates.addAll(recur.getDates(seed, at(from, seed), at(from.plusDays(367), seed), 401));
        }
        for (var property : master.<RDate<Temporal>>getProperties("RDATE"))
          dates.addAll(property.getDates());
        Set<String> excluded = new HashSet<>();
        for (var property : master.<ExDate<Temporal>>getProperties("EXDATE"))
          for (var d : property.getDates()) excluded.add(identity(d, fallback));
        boolean series = !rules.isEmpty() || !master.getProperties("RDATE").isEmpty();
        for (Temporal occurrence : dates) {
          if (dates.size() > 400) throw new BackendException(413, "calendar_occurrence_limit");
          String key = item.getKey() + "|" + identity(occurrence, fallback);
          VEvent override = overrides.remove(key);
          if (excluded.contains(identity(occurrence, fallback)) && override == null) continue;
          VEvent selected = override == null ? master : override;
          boolean cancelled = "CANCELLED".equals(value(selected, "STATUS"));
          Temporal start =
              override == null
                  ? occurrence
                  : selected
                      .getStartDate()
                      .map(DateProperty::getDate)
                      .orElseGet(
                          () -> {
                            if (!cancelled) throw invalid();
                            return occurrence;
                          });
          if (date(start, fallback).isBefore(from)
              || date(start, fallback).isAfter(from.plusDays(366))) continue;
          VEvent durationSource =
              selected.getProperty("DTEND").isPresent()
                      || selected.getProperty("DURATION").isPresent()
                  ? selected
                  : master;
          Temporal originalStart =
              durationSource.getStartDate().orElseThrow(CalendarIcsReader::invalid).getDate();
          Temporal originalEnd =
              durationSource
                  .<DtEnd<Temporal>>getProperty("DTEND")
                  .map(DateProperty::getDate)
                  .orElse(null);
          Temporal end;
          if (originalEnd != null)
            end =
                start.plus(
                    originalStart instanceof LocalDate
                        ? java.time.Period.ofDays(
                            Math.toIntExact(ChronoUnit.DAYS.between(originalStart, originalEnd)))
                        : java.time.Duration.between(
                            instant(originalStart, fallback), instant(originalEnd, fallback)));
          else if (durationSource.getProperty("DURATION").isPresent()) {
            String text = value(durationSource, "DURATION");
            java.time.Duration duration =
                text.matches("P\\d+W")
                    ? java.time.Duration.ofDays(
                        Math.multiplyExact(Long.parseLong(text.substring(1, text.length() - 1)), 7))
                    : java.time.Duration.parse(text);
            if (duration.isNegative() || duration.isZero()) throw invalid();
            if (start instanceof LocalDate) {
              if (duration.getSeconds() % 86400 != 0 || duration.getNano() != 0) throw invalid();
              end = start.plus(duration.toDays(), ChronoUnit.DAYS);
            } else end = start.plus(duration);
          } else end = start instanceof LocalDate ? start.plus(1, ChronoUnit.DAYS) : start;
          if (instant(end, fallback).isBefore(instant(start, fallback))) throw invalid();
          String kind = value(selected, "X-PB-KIND");
          if (kind == null) kind = value(master, "X-PB-KIND");
          if (kind == null || !Set.of("trip", "birthday", "payment").contains(kind))
            throw new BackendException(400, "calendar_kind_mapping_required");
          String city = inherited(selected, master, "LOCATION");
          if (city != null && city.length() > 100) throw invalid();
          // RFC5545 DTEND is exclusive. The structured trip API uses inclusive city-days.
          LocalDate last = date(end, fallback);
          if (end instanceof LocalDate
              || instant(end, fallback).atZone(fallback).toLocalTime().equals(LocalTime.MIDNIGHT))
            last = last.minusDays(1);
          if (last.isBefore(date(start, fallback))) last = date(start, fallback);
          result.add(
              new Entry(
                  PerceptionAccess.hash(series ? key : item.getKey()),
                  kind,
                  date(start, fallback),
                  last,
                  city,
                  inherited(selected, master, "X-PB-AMOUNT"),
                  Objects.toString(inherited(selected, master, "X-PB-CURRENCY"), "CNY"),
                  instant(start, fallback).toString(),
                  instant(end, fallback).toString(),
                  start instanceof ZonedDateTime z ? z.getZone().getId() : fallback.getId(),
                  start instanceof LocalDate,
                  cancelled));
          if (result.size() > 400) throw new BackendException(413, "calendar_occurrence_limit");
        }
      }
      if (!overrides.isEmpty()) throw new BackendException(400, "calendar_orphan_override");
      return List.copyOf(result);
    } catch (BackendException e) {
      throw e;
    } catch (Exception e) {
      throw invalid();
    }
  }

  private static String value(VEvent event, String name) {
    return event.<Property>getProperty(name).map(Property::getValue).orElse(null);
  }

  private static String inherited(VEvent selected, VEvent master, String name) {
    String value = value(selected, name);
    return value == null ? value(master, name) : value;
  }

  private static Temporal at(LocalDate date, Temporal seed) {
    if (seed instanceof LocalDate) return date;
    if (seed instanceof ZonedDateTime z) return date.atStartOfDay(z.getZone());
    if (seed instanceof OffsetDateTime o) return date.atStartOfDay().atOffset(o.getOffset());
    if (seed instanceof Instant) return date.atStartOfDay(ZoneOffset.UTC).toInstant();
    return date.atStartOfDay();
  }

  private static Instant instant(Temporal value, ZoneId fallback) {
    if (value instanceof LocalDate d) return d.atStartOfDay(fallback).toInstant();
    if (value instanceof LocalDateTime d) return d.atZone(fallback).toInstant();
    return Instant.from(value);
  }

  private static LocalDate date(Temporal value, ZoneId fallback) {
    return value instanceof LocalDate d
        ? d
        : instant(value, fallback).atZone(fallback).toLocalDate();
  }

  private static String identity(Temporal value, ZoneId fallback) {
    return value instanceof LocalDate d ? d.toString() : instant(value, fallback).toString();
  }

  private static BackendException invalid() {
    return new BackendException(400, "invalid_calendar");
  }
}
