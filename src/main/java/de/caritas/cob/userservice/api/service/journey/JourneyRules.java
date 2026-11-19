package de.caritas.cob.userservice.api.service.journey;

import java.text.Normalizer;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The rules a stored journey must keep, the same ones the Admin applies before it saves (ADR-036
 * decisions 36–39). The server does not trust the Admin: a journey or layer that breaks a rule is
 * refused with {@link IllegalArgumentException}, which the controller answers with 400.
 *
 * <p>Journeys are handled as the JSON the Admin edits ({@code Map<String, Object>}); only the
 * fields the rules need are read, everything else is stored as it came.
 */
public final class JourneyRules {

  public static final List<String> PHASES = List.of("departure", "flight", "arrival");

  /** What Träger and Beratungsstelle may change: on board and arrival, never the check-in. */
  public static final List<String> LOWER_LEVEL_PHASES = List.of("flight", "arrival");

  static final Set<String> CONVERSATION_TYPES =
      Set.of("AGENCY_COUNSELLING", "LIVE_CHAT", "SELF_HELP", "INTERNAL_GROUP");

  private static final Set<String> STEP_KINDS =
      Set.of("core", "addon", "question", "rating", "panel", "message");

  /** Groups have no check-in: nobody registers into a group through a journey. */
  private static final Set<String> WITHOUT_DEPARTURE = Set.of("SELF_HELP", "INTERNAL_GROUP");

  public static final Pattern ID = Pattern.compile("[A-Za-z0-9:._@-]{1,64}");
  private static final Pattern SLUG = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
  private static final int MAX_SLUG = 64;

  private JourneyRules() {}

  /** Checks a whole platform journey whose id is {@code journeyId}. */
  public static void checkJourney(String journeyId, Map<String, Object> journey) {
    require(journey != null, "journey missing");
    require(Objects.equals(journey.get("id"), journeyId), "journey id must match the path");
    var type = journey.get("conversationType");
    require(
        type instanceof String && CONVERSATION_TYPES.contains(type), "unknown conversation type");
    var phases = asMap(journey.get("phases"), "phases");
    require(phases.keySet().stream().allMatch(PHASES::contains), "unknown phase");
    var stepIds = new HashSet<String>();
    for (var phase : PHASES) {
      var steps = steps(phases.get(phase), phase);
      if (phase.equals("departure") && WITHOUT_DEPARTURE.contains(type)) {
        require(steps.isEmpty(), "this conversation type has no check-in");
      }
      checkSteps(phase, steps, stepIds);
      if (phase.equals("departure")) {
        checkCheckInOrder(steps);
      }
    }
    var routes = asMap(journey.get("routes"), "routes");
    var registration = list(routes.get("registration"), "routes.registration");
    require(
        registration.stream()
            .allMatch(
                id ->
                    (id instanceof Integer || id instanceof Long) && ((Number) id).longValue() > 0),
        "registration topics must be topic ids");
    require(
        registration.isEmpty() || "AGENCY_COUNSELLING".equals(type),
        "only Mailberatung has a registration route");
    var linkIds = new HashSet<String>();
    for (var entry : list(routes.get("links"), "routes.links")) {
      var link = asMap(entry, "link");
      require(link.get("id") instanceof String id && ID.matcher(id).matches(), "link id invalid");
      require(linkIds.add((String) link.get("id")), "link id used twice");
      var slug = link.get("slug");
      require(slug == null || slug instanceof String s && isSlug(s), "link slug invalid");
      require(link.get("name") == null || link.get("name") instanceof String, "link name invalid");
    }
  }

  /** Checks the layer of a Träger or Beratungsstelle: only on-board and arrival steps. */
  public static void checkLayer(Map<String, Object> phases) {
    require(phases != null, "phases missing");
    require(
        phases.keySet().stream().allMatch(LOWER_LEVEL_PHASES::contains),
        "a Träger or Beratungsstelle may change on board and arrival only");
    var stepIds = new HashSet<String>();
    for (var entry : phases.entrySet()) {
      checkSteps(entry.getKey(), steps(entry.getValue(), entry.getKey()), stepIds);
    }
  }

  /**
   * Gives every link of {@code journey} without a slug its slug, unique among {@code taken}, and
   * keeps the slug a link already had in {@code stored}: an address once saved never changes
   * (decision 39). {@code taken} holds the slugs of every other platform journey.
   */
  @SuppressWarnings("unchecked")
  public static void fixSlugs(
      Map<String, Object> journey, Map<String, Object> stored, Collection<String> takenElsewhere) {
    var before = slugsById(stored);
    var taken = new HashSet<>(takenElsewhere);
    var links = list(asMap(journey.get("routes"), "routes").get("links"), "routes.links");
    // Slugs this journey keeps or brings come first, so a new link never takes one of them.
    for (var entry : links) {
      var link = (Map<String, Object>) entry;
      var kept = before.get((String) link.get("id"));
      if (kept != null) {
        require(
            link.get("slug") == null || kept.equals(link.get("slug")),
            "a saved link address cannot change");
        link.put("slug", kept);
      }
      var slug = (String) link.get("slug");
      if (slug != null) {
        require(taken.add(slug), "link address already used: " + slug);
      }
    }
    for (var entry : links) {
      var link = (Map<String, Object>) entry;
      if (link.get("slug") == null) {
        var name = link.get("name") instanceof String text ? text : "";
        var slug = freeSlug(slugOf(name), taken);
        taken.add(slug);
        link.put("slug", slug);
      }
    }
  }

  /** The slugs of a journey's links, by link id. */
  @SuppressWarnings("unchecked")
  public static Map<String, String> slugsById(Map<String, Object> journey) {
    var slugs = new LinkedHashMap<String, String>();
    if (journey == null || !(journey.get("routes") instanceof Map<?, ?> routes)) {
      return slugs;
    }
    if (routes.get("links") instanceof List<?> links) {
      for (var entry : links) {
        if (entry instanceof Map<?, ?> link
            && link.get("id") instanceof String id
            && link.get("slug") instanceof String slug) {
          slugs.put(id, slug);
        }
      }
    }
    return slugs;
  }

  /** The topics whose registration runs this journey. */
  public static Set<Long> registrationTopics(Map<String, Object> journey) {
    var topics = new HashSet<Long>();
    if (journey != null
        && journey.get("routes") instanceof Map<?, ?> routes
        && routes.get("registration") instanceof List<?> ids) {
      ids.forEach(
          id -> {
            if (id instanceof Number number) {
              topics.add(number.longValue());
            }
          });
    }
    return topics;
  }

  /** The step ids of a journey, for the rule that no two journeys share one. */
  public static Set<String> stepIds(Map<String, Object> journey) {
    var ids = new HashSet<String>();
    if (journey.get("phases") instanceof Map<?, ?> phases) {
      for (var steps : phases.values()) {
        if (steps instanceof List<?> list) {
          for (var step : list) {
            if (step instanceof Map<?, ?> map && map.get("id") instanceof String id) {
              ids.add(id);
            }
          }
        }
      }
    }
    return ids;
  }

  static String slugOf(String name) {
    var text =
        (name == null ? "" : name)
            .toLowerCase()
            .replace("ä", "ae")
            .replace("ö", "oe")
            .replace("ü", "ue")
            .replace("ß", "ss");
    var plain =
        Normalizer.normalize(text, Normalizer.Form.NFKD)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+|-+$", "");
    if (plain.length() > MAX_SLUG - 4) {
      plain = plain.substring(0, MAX_SLUG - 4).replaceAll("-+$", "");
    }
    return plain.isEmpty() ? "link" : plain;
  }

  private static String freeSlug(String base, Set<String> taken) {
    if (!taken.contains(base)) {
      return base;
    }
    var number = 2;
    while (taken.contains(base + "-" + number)) {
      number++;
    }
    return base + "-" + number;
  }

  static boolean isSlug(String slug) {
    return slug.length() <= MAX_SLUG && SLUG.matcher(slug).matches();
  }

  private static void checkSteps(String phase, List<Object> steps, Set<String> stepIds) {
    for (var entry : steps) {
      var step = asMap(entry, "step");
      require(step.get("id") instanceof String id && ID.matcher(id).matches(), "step id invalid");
      require(stepIds.add((String) step.get("id")), "step id used twice: " + step.get("id"));
      var kind = step.get("kind");
      require(kind instanceof String && STEP_KINDS.contains(kind), "unknown step kind");
      require(
          !(phase.equals("departure") && kind.equals("message")),
          "a chat message cannot be part of the check-in");
    }
  }

  /** The topic first, and the postcode before the Beratungsstelle it finds (decision 36). */
  private static void checkCheckInOrder(List<Object> steps) {
    var topic = indexOfCore(steps, "topic");
    var postcode = indexOfCore(steps, "postcode");
    var agency = indexOfCore(steps, "agency");
    require(topic <= 0, "choosing the topic must come first");
    require(
        postcode < 0 || agency < 0 || postcode < agency, "the postcode comes before the centre");
  }

  private static int indexOfCore(List<Object> steps, String core) {
    for (var index = 0; index < steps.size(); index++) {
      if (steps.get(index) instanceof Map<?, ?> step && core.equals(step.get("core"))) {
        return index;
      }
    }
    return -1;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object value, String name) {
    require(value instanceof Map, name + " must be an object");
    return (Map<String, Object>) value;
  }

  private static List<Object> steps(Object value, String phase) {
    return value == null ? List.of() : list(value, "phases." + phase);
  }

  @SuppressWarnings("unchecked")
  private static List<Object> list(Object value, String name) {
    if (value == null) {
      return List.of();
    }
    require(value instanceof List, name + " must be a list");
    return (List<Object>) value;
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalArgumentException(message);
    }
  }
}
