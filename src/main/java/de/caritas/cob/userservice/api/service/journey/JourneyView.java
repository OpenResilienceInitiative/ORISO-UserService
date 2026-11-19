package de.caritas.cob.userservice.api.service.journey;

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

  /** The journey fields an advice seeker's app needs; everything else stays with the editors. */
  private static final List<String> PUBLIC_JOURNEY_FIELDS =
      List.of("id", "conversationType", "phases", "survey");

  /**
   * Keeps only what the advice seeker's app needs (an allow-list of journey fields) and drops what
   * only editors see (block profiles, machine-translation marks) wherever it sits in the steps.
   */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> forAdviceSeekers(Map<String, Object> journey) {
    var copy = new LinkedHashMap<String, Object>();
    PUBLIC_JOURNEY_FIELDS.forEach(
        field -> {
          if (journey.containsKey(field)) {
            copy.put(
                field,
                field.equals("phases")
                    ? withoutEditorFields(journey.get(field))
                    : journey.get(field));
          }
        });
    return copy;
  }

  @SuppressWarnings("unchecked")
  private static Object withoutEditorFields(Object node) {
    if (node instanceof Map<?, ?> map) {
      var kept = new LinkedHashMap<String, Object>();
      ((Map<String, Object>) map)
          .forEach(
              (key, value) -> {
                if (!EDITOR_ONLY_STEP_FIELDS.contains(key)) {
                  kept.put(key, withoutEditorFields(value));
                }
              });
      return kept;
    }
    if (node instanceof List<?> list) {
      return list.stream().map(JourneyView::withoutEditorFields).toList();
    }
    return node;
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
