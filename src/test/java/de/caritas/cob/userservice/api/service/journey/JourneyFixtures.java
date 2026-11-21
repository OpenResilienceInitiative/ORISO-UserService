package de.caritas.cob.userservice.api.service.journey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Journeys as the Admin sends them, mutable so the rules can fill in link slugs. */
final class JourneyFixtures {

  private JourneyFixtures() {}

  static Map<String, Object> step(String id, String kind, String core) {
    var step = new LinkedHashMap<String, Object>();
    step.put("id", id);
    step.put("kind", kind);
    if (core != null) step.put("core", core);
    step.put("title", Map.of("de", id));
    return step;
  }

  static Map<String, Object> link(String id, String name) {
    var link = new LinkedHashMap<String, Object>();
    link.put("id", id);
    link.put("name", name);
    link.put("topicIds", List.of(1));
    return link;
  }

  static Map<String, Object> mail(String id, Map<String, Object>... links) {
    var journey = new LinkedHashMap<String, Object>();
    journey.put("id", id);
    journey.put("number", 1);
    journey.put("name", "Standard");
    journey.put("conversationType", "AGENCY_COUNSELLING");
    var phases = new LinkedHashMap<String, Object>();
    phases.put(
        "departure",
        new ArrayList<>(
            List.of(
                step(id + ":topic", "core", "topic"),
                step(id + ":postcode", "core", "postcode"),
                step(id + ":agency", "core", "agency"),
                step(id + ":consent", "core", "consent"))));
    phases.put("flight", new ArrayList<>(List.of(step(id + ":enquiry", "core", "enquiry"))));
    phases.put("arrival", new ArrayList<>());
    journey.put("phases", phases);
    journey.put("survey", Map.of("anonymous", true));
    var routes = new LinkedHashMap<String, Object>();
    routes.put("registration", new ArrayList<>(List.of(1, 2)));
    routes.put("links", new ArrayList<>(List.of(links)));
    journey.put("routes", routes);
    return journey;
  }

  @SuppressWarnings("unchecked")
  static List<Object> phase(Map<String, Object> journey, String phase) {
    return (List<Object>) ((Map<String, Object>) journey.get("phases")).get(phase);
  }

  @SuppressWarnings("unchecked")
  static List<Map<String, Object>> links(Map<String, Object> journey) {
    return (List<Map<String, Object>>)
        (List<?>) ((Map<String, Object>) journey.get("routes")).get("links");
  }
}
