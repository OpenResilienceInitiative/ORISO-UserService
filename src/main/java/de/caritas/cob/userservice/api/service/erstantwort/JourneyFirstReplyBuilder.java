package de.caritas.cob.userservice.api.service.erstantwort;

import static de.caritas.cob.userservice.api.service.erstantwort.ErstantwortPayloadBuilder.FIRST_RESPONSE_TYPE;
import static de.caritas.cob.userservice.api.service.erstantwort.ErstantwortPayloadBuilder.SYSTEM_NOTIFICATION_PREFIX;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.service.journey.PublicJourneyService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The first reply built from the journey (ADR-036 decision 42, ORISO-Admin#1660): the on-board
 * steps of the effective journey for the enquiry's topic and Beratungsstelle, in their order, as a
 * version-2 payload (ADR-036 decision 8).
 *
 * <ul>
 *   <li>The core step „Begrüßung der Beratungsstelle" sends today's resolved Bausteine, so a
 *       journey that keeps it says the same words as version 1.
 *   <li>A panel step sends one panel per aspect; a message step sends its text.
 *   <li>Only steps sent <em>with the greeting</em> belong here; later and manual steps do not.
 *   <li><b>Emergency numbers are always sent.</b> When no section is marked as safety, the platform
 *       emergency panel is appended (decision 42 amends decision 6 for the first reply).
 * </ul>
 *
 * <p>No journey for the topic, or the switch off, means no answer here: the caller sends version 1
 * unchanged. The switch exists so this can be enabled per environment once the Frontend renders
 * version 2.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JourneyFirstReplyBuilder {

  public static final int MODULE_PAYLOAD_VERSION = 2;
  static final String CONVERSATION_TYPE = "AGENCY_COUNSELLING";
  static final String GREETING_CORE = "agencyGreeting";
  static final String WITH_GREETING = "withGreeting";
  static final String EMERGENCY_BAUSTEIN = "emergencyNumbers";

  /** Bausteine that carry emergency contacts: drawn as safety panels. */
  private static final Set<String> SAFETY_BAUSTEINE =
      Set.of(EMERGENCY_BAUSTEIN, "emergencyAddition");

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final @NonNull PublicJourneyService publicJourneyService;
  private final @NonNull ErstantwortPayloadBuilder payloadBuilder;

  @Value("${erstantwort.journey-first-reply.enabled:false}")
  private boolean enabled;

  /**
   * The version-2 body for this enquiry, or empty when version 1 is to be sent: the switch is off,
   * there is no topic, no journey reaches the topic, or the journey could not be read.
   */
  public Optional<String> buildFor(Long topicId, Long agencyId, ErstantwortContext context) {
    if (!enabled || topicId == null) {
      return Optional.empty();
    }
    Optional<PublicJourneyService.PublicJourney> journey;
    try {
      journey = publicJourneyService.forRegistration(topicId, agencyId);
    } catch (RuntimeException unreadable) {
      // An unreadable journey must not cost the person their first reply: send version 1.
      log.warn("Journey for topic {} not readable, sending the version-1 first reply", topicId);
      return Optional.empty();
    }
    return journey.map(found -> build(found.journey(), context));
  }

  /** The version-2 body for the given (effective, public) journey. */
  public String build(Map<String, Object> journey, ErstantwortContext context) {
    var bausteine = payloadBuilder.buildBausteine(context);
    var sections = new ArrayList<Map<String, Object>>();
    for (var step : onBoardSteps(journey)) {
      if (!sentWithGreeting(step)) {
        continue;
      }
      sections.addAll(sectionsOf(step, bausteine, context));
    }
    if (sections.stream().noneMatch(section -> Boolean.TRUE.equals(section.get("safety")))) {
      emergencyPanel(bausteine).ifPresent(sections::add);
    }
    var payload = new LinkedHashMap<String, Object>();
    payload.put("type", FIRST_RESPONSE_TYPE);
    payload.put("version", MODULE_PAYLOAD_VERSION);
    payload.put("conversationType", CONVERSATION_TYPE);
    payload.put("sections", sections);
    try {
      return SYSTEM_NOTIFICATION_PREFIX + OBJECT_MAPPER.writeValueAsString(payload);
    } catch (JsonProcessingException exception) {
      log.warn("Could not serialize the journey first reply", exception);
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> onBoardSteps(Map<String, Object> journey) {
    if (journey == null
        || !(journey.get("phases") instanceof Map<?, ?> phases)
        || !(phases.get("flight") instanceof List<?> steps)) {
      return List.of();
    }
    return steps.stream()
        .filter(Map.class::isInstance)
        .map(step -> (Map<String, Object>) step)
        .toList();
  }

  private static boolean sentWithGreeting(Map<String, Object> step) {
    var trigger = step.get("trigger");
    return trigger == null || WITH_GREETING.equals(trigger);
  }

  private List<Map<String, Object>> sectionsOf(
      Map<String, Object> step, List<Map<String, Object>> bausteine, ErstantwortContext context) {
    var id = String.valueOf(step.get("id"));
    var kind = step.get("kind");
    if ("core".equals(kind) && GREETING_CORE.equals(step.get("core"))) {
      return greetingSections(id, bausteine);
    }
    if ("core".equals(kind) && "email".equals(step.get("core"))) {
      return List.of(fixed(id, "email", text(step.get("title"), context)));
    }
    if ("addon".equals(kind) && "accountCompletion".equals(step.get("addon"))) {
      return List.of(fixed(id, "accountCompletion", text(step.get("title"), context)));
    }
    if ("panel".equals(kind)) {
      return panelSections(step, context);
    }
    if ("message".equals(kind)) {
      var body = text(step.get("body"), context);
      return body == null ? List.of() : List.of(message(id, body));
    }
    // Questions and ratings follow in a later slice; other core steps send nothing here.
    return List.of();
  }

  /** Today's Bausteine as sections: with a headline a panel, without one a message. */
  private static List<Map<String, Object>> greetingSections(
      String stepId, List<Map<String, Object>> bausteine) {
    var sections = new ArrayList<Map<String, Object>>();
    for (var baustein : bausteine) {
      var bausteinId = String.valueOf(baustein.get("id"));
      var id = stepId + ":" + bausteinId;
      var headline = baustein.get("headline") instanceof String value ? value : null;
      var body = withLinks(String.valueOf(baustein.get("body")), baustein.get("links"));
      if ("emailNotification".equals(bausteinId)) {
        sections.add(fixed(id, "email", headline));
      } else if (headline == null) {
        sections.add(message(id, body));
      } else {
        sections.add(panel(id, headline, body, "collapsed", SAFETY_BAUSTEINE.contains(bausteinId)));
      }
    }
    return sections;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> panelSections(
      Map<String, Object> step, ErstantwortContext context) {
    if (!(step.get("aspects") instanceof List<?> aspects)) {
      return List.of();
    }
    var display = "open".equals(step.get("display")) ? "open" : "collapsed";
    var sections = new ArrayList<Map<String, Object>>();
    for (var entry : aspects) {
      if (!(entry instanceof Map<?, ?> raw)) {
        continue;
      }
      var aspect = (Map<String, Object>) raw;
      var title = text(aspect.get("title"), context);
      var body = text(aspect.get("body"), context);
      // The Frontend drops a panel without title or text; so does the payload.
      if (title != null && body != null) {
        sections.add(
            panel(
                String.valueOf(aspect.get("id")),
                title,
                body,
                display,
                Boolean.TRUE.equals(aspect.get("safety"))));
      }
    }
    return sections;
  }

  private static Optional<Map<String, Object>> emergencyPanel(List<Map<String, Object>> bausteine) {
    return bausteine.stream()
        .filter(baustein -> EMERGENCY_BAUSTEIN.equals(baustein.get("id")))
        .findFirst()
        .map(
            baustein ->
                panel(
                    "platform:" + EMERGENCY_BAUSTEIN,
                    String.valueOf(baustein.get("headline")),
                    String.valueOf(baustein.get("body")),
                    "collapsed",
                    true));
  }

  /** A journey text ({@code {de: "<p>…</p>", en: …}}) in the reader's language, as plain text. */
  @SuppressWarnings("unchecked")
  private static String text(Object langMap, ErstantwortContext context) {
    if (!(langMap instanceof Map<?, ?> map)) {
      return null;
    }
    var texts = new LinkedHashMap<String, String>();
    ((Map<Object, Object>) map)
        .forEach(
            (language, value) -> {
              if (language instanceof String key && value instanceof String html) {
                texts.put(key, html);
              }
            });
    return ErstantwortTenantTextResolver.pick(texts, context.getLocale(), context.isInformal());
  }

  /** Version 2 has no link field: the privacy and imprint addresses travel in the text. */
  private static String withLinks(String body, Object links) {
    if (!(links instanceof List<?> list) || list.isEmpty()) {
      return body;
    }
    var text = new StringBuilder(body);
    for (var entry : list) {
      if (entry instanceof Map<?, ?> link) {
        text.append('\n').append(link.get("label")).append(": ").append(link.get("url"));
      }
    }
    return text.toString();
  }

  private static Map<String, Object> message(String id, String body) {
    var section = new LinkedHashMap<String, Object>();
    section.put("id", id);
    section.put("kind", "message");
    section.put("body", body);
    return section;
  }

  private static Map<String, Object> panel(
      String id, String title, String body, String display, boolean safety) {
    var section = new LinkedHashMap<String, Object>();
    section.put("id", id);
    section.put("kind", "panel");
    section.put("title", title);
    section.put("display", display);
    section.put("body", body);
    if (safety) {
      section.put("safety", true);
    }
    return section;
  }

  private static Map<String, Object> fixed(String id, String module, String title) {
    var section = new LinkedHashMap<String, Object>();
    section.put("id", id);
    section.put("kind", "fixed");
    section.put("module", module);
    if (title != null) {
      section.put("title", title);
    }
    return section;
  }
}
