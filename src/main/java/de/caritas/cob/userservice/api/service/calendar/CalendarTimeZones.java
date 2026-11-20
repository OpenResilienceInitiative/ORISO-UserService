package de.caritas.cob.userservice.api.service.calendar;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.zone.ZoneRules;
import java.time.zone.ZoneRulesException;
import java.time.zone.ZoneRulesProvider;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.IntStream;
import net.fortuna.ical4j.model.ConstraintViolationException;
import net.fortuna.ical4j.model.TimeZone;
import net.fortuna.ical4j.model.TimeZoneRegistry;
import net.fortuna.ical4j.model.ZoneRulesBuilder;

/**
 * The time zones one calendar document defines itself (VTIMEZONE), while it is read (ADR-032).
 *
 * <p>java.time knows a zone only by an id that a provider registered, and keeps every provider and
 * every id until the process ends. ical4j 4.1 registers a provider at the end of every document it
 * reads, and a new id for every zone the document defines: each import would add both for good.
 * Here one provider registers {@value #MAX} ids once. A document reads its zones under these ids,
 * and what an id stands for is bound to the thread that reads the document, until it is read.
 */
final class CalendarTimeZones implements TimeZoneRegistry, AutoCloseable {

  /** Time zones one document may define; the parser refuses a document with more. */
  static final int MAX = 100;

  private static final List<String> IDS;
  private static final Map<String, Integer> SLOTS;
  private static final ThreadLocal<ZoneRules[]> BOUND = new ThreadLocal<>();

  static {
    // Ids no other copy of this class has: a second class loader registers its own.
    var prefix = "oriso-calendar-" + UUID.randomUUID().toString().substring(0, 8) + "-";
    IDS = IntStream.range(0, MAX).mapToObj(slot -> prefix + slot).toList();
    var slots = new HashMap<String, Integer>();
    for (var slot = 0; slot < MAX; slot++) {
      slots.put(IDS.get(slot), slot);
    }
    SLOTS = Map.copyOf(slots);
    ZoneRulesProvider.registerProvider(new Provider());
  }

  private final ZoneRules[] rules = new ZoneRules[MAX];
  private final ZoneRules[] outer = BOUND.get();
  private final Map<String, Integer> slotOfTzid = new HashMap<>();
  private final Map<String, TimeZone> definitions = new HashMap<>();
  private final Map<String, ZoneRules> rulesById = new LinkedHashMap<>();

  private CalendarTimeZones() {
    BOUND.set(rules);
  }

  /** The zones of a document read on this thread; closing it ends what its ids stand for. */
  static CalendarTimeZones open() {
    return new CalendarTimeZones();
  }

  @Override
  public void close() {
    if (outer == null) {
      BOUND.remove();
    } else {
      BOUND.set(outer);
    }
  }

  @Override
  public void register(TimeZone timezone) {
    register(timezone, false);
  }

  /** Never an update: no definition is fetched (see {@code ical4j.properties}). */
  @Override
  public void register(TimeZone timezone, boolean update) {
    ZoneRules built;
    try {
      built = new ZoneRulesBuilder().vTimeZone(timezone.getVTimeZone()).build();
    } catch (ConstraintViolationException unreadable) {
      throw new IllegalArgumentException("Unreadable time zone", unreadable);
    }
    var tzid = timezone.getID();
    var slot = slotOfTzid.get(tzid);
    if (slot == null) {
      if (slotOfTzid.size() == MAX) {
        throw new IllegalStateException("More time zones than a document may define");
      }
      slot = slotOfTzid.size();
      slotOfTzid.put(tzid, slot);
    }
    rules[slot] = built;
    rulesById.put(IDS.get(slot), built);
    definitions.put(tzid, timezone);
  }

  @Override
  public void clear() {
    Arrays.fill(rules, null);
    slotOfTzid.clear();
    definitions.clear();
    rulesById.clear();
  }

  /** Only the document's own zones: nothing that reads a document asks for another. */
  @Override
  public TimeZone getTimeZone(String id) {
    return definitions.get(id);
  }

  @Override
  public Map<String, ZoneRules> getZoneRules() {
    return Collections.unmodifiableMap(rulesById);
  }

  /** A zone the document does not define is looked up among the global ones (ical4j's TzId). */
  @Override
  public ZoneId getZoneId(String tzId) {
    var slot = slotOfTzid.get(tzId);
    if (slot == null) {
      throw new DateTimeException("Not a time zone of this document");
    }
    return ZoneId.of(IDS.get(slot));
  }

  @Override
  public String getTzId(String zoneId) {
    var slot = SLOTS.get(zoneId);
    return slotOfTzid.entrySet().stream()
        .filter(entry -> entry.getValue().equals(slot))
        .map(Map.Entry::getKey)
        .findFirst()
        .orElse(null);
  }

  /** The one provider of the ids; it resolves each to the zone bound on the asking thread. */
  private static final class Provider extends ZoneRulesProvider {

    @Override
    protected Set<String> provideZoneIds() {
      return SLOTS.keySet();
    }

    @Override
    protected ZoneRules provideRules(String zoneId, boolean forCaching) {
      if (forCaching) {
        // What an id stands for changes from one document to the next: no ZoneId may keep it.
        return null;
      }
      var bound = BOUND.get();
      var slot = SLOTS.get(zoneId);
      var found = bound == null || slot == null ? null : bound[slot];
      if (found == null) {
        throw new ZoneRulesException("No calendar document defines this zone here: " + zoneId);
      }
      return found;
    }

    @Override
    protected NavigableMap<String, ZoneRules> provideVersions(String zoneId) {
      var versions = new TreeMap<String, ZoneRules>();
      versions.put("document", provideRules(zoneId, false));
      return versions;
    }
  }
}
