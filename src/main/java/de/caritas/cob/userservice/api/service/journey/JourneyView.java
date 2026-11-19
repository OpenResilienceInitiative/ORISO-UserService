package de.caritas.cob.userservice.api.service.journey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a journey looks like to an advice seeker (ADR-036 part E, ORISO-Frontend#2333): the platform
 * journey with the Träger layer and then the centre layer on top (each replaces whole phases, as in
 * the Admin's {@code effectiveJourneys}), without what only editors see.
 */
public final class JourneyView {

  /** Only these phases can come from a layer; the check-in is always the platform's. */
  static final List<String> LAYER_PHASES = JourneyRules.LOWER_LEVEL_PHASES;

  /** For editors and the block library only: who wrote a block, for whom, its search terms. */
  private static final List<String> EDITOR_ONLY_STEP_FIELDS = List.of("profile", "machine");

  private JourneyView() {}

  /**
   * The journey as seen below the given layers, in order (Träger first, then centre). Layers are
   * the stored bodies ({@code {"phases": {...}}}); missing ones are skipped.
   */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> effective(
      Map<String, Object> platform, List<Map<String, Object>> layers) {
    var journey = new LinkedHashMap<>(platform);
    var phases =
        new LinkedHashMap<String, Object>(
            journey.get("phases") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of());
    layers.forEach(
        layer -> {
          if (layer != null && layer.get("phases") instanceof Map<?, ?> own) {
            LAYER_PHASES.forEach(
                phase -> {
                  if (own.get(phase) instanceof List<?> steps) {
                    phases.put(phase, steps);
                  }
                });
          }
        });
    journey.put("phases", phases);
    return journey;
  }

  /** Drops what only editors see: block profiles and machine-translation marks. */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> forAdviceSeekers(Map<String, Object> journey) {
    var copy = new LinkedHashMap<>(journey);
    var phases = new LinkedHashMap<String, Object>();
    if (journey.get("phases") instanceof Map<?, ?> map) {
      ((Map<String, Object>) map)
          .forEach(
              (phase, steps) -> {
                var cleaned = new ArrayList<Object>();
                if (steps instanceof List<?> list) {
                  list.forEach(
                      step -> {
                        if (step instanceof Map<?, ?> fields) {
                          var kept = new LinkedHashMap<>((Map<String, Object>) fields);
                          EDITOR_ONLY_STEP_FIELDS.forEach(kept::remove);
                          cleaned.add(kept);
                        }
                      });
                }
                phases.put(phase, cleaned);
              });
    }
    copy.put("phases", phases);
    // The journey's name is the editors' label ("Schulden ausführlich"); the person never sees it.
    copy.remove("name");
    // The other ways in (other links, their topics) are none of the person's business; the way
    // they came by is answered beside the journey.
    copy.remove("routes");
    return copy;
  }

  /** Whether the journey's official registration runs for this topic. */
  public static boolean registersTopic(Map<String, Object> journey, long topicId) {
    return routes(journey).get("registration") instanceof List<?> topics
        && topics.stream().anyMatch(id -> id instanceof Number n && n.longValue() == topicId);
  }

  /** The journey's link with this address, if it has one. */
  @SuppressWarnings("unchecked")
  public static Optional<Map<String, Object>> linkWithSlug(
      Map<String, Object> journey, String slug) {
    if (!(routes(journey).get("links") instanceof List<?> links)) {
      return Optional.empty();
    }
    return links.stream()
        .filter(entry -> entry instanceof Map<?, ?> link && slug.equals(link.get("slug")))
        .map(entry -> (Map<String, Object>) entry)
        .findFirst();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> routes(Map<String, Object> journey) {
    return journey.get("routes") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
  }
}
