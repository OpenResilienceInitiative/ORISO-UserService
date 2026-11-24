package de.caritas.cob.userservice.api.service.erstantwort;

import static de.caritas.cob.userservice.api.service.erstantwort.ErstantwortPayloadBuilder.FIRST_RESPONSE_TYPE;
import static de.caritas.cob.userservice.api.service.erstantwort.ErstantwortPayloadBuilder.SYSTEM_NOTIFICATION_PREFIX;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.service.journey.PublicJourneyService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
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
 *   <li><b>The platform emergency numbers are always sent.</b> Unless a safety section already
 *       carries the platform's emergency text, the platform emergency panel is added before the
 *       first optional action (decision 42 amends decision 6 for the first reply; order as in
 *       ADR-018). An own safety text never replaces it: it could say anything.
 *   <li>The second-factor invitation („Zugang, zusätzlich geschützt") is not part of the first
 *       reply (Frank, 2026-10-14), exactly as version 1 silences it in the Frontend.
 *   <li>Link targets travel as {@code links} ({@code [{label, url}]}) beside the plain text, so the
 *       Frontend can draw them as links.
 * </ul>
 *
 * <p>No journey for the topic, an on-board phase that sends nothing of its own, an error while
 * building, or the switch off, means no answer here: the caller sends version 1 unchanged. The
 * switch exists so this can be enabled per environment once the Frontend renders version 2.
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

  /** Silenced in version 1 by the Frontend (Frank, 2026-10-14); never sent in version 2. */
  static final Set<String> SILENCED_BAUSTEINE = Set.of("accountProtection");

  /** Link targets the Frontend may open; anything else stays text. */
  private static final List<String> LINK_SCHEMES =
      List.of("https://", "http://", "mailto:", "tel:");

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
    try {
      return journey.flatMap(found -> build(found.journey(), context));
    } catch (RuntimeException broken) {
      // A journey we cannot turn into a first reply must not cost the person theirs either.
      log.warn("Journey first reply for topic {} failed, sending version 1", topicId, broken);
      return Optional.empty();
    }
  }

  /**
   * The version-2 body for the given (effective, public) journey, or empty when its on-board phase
   * sends nothing of its own (missing, empty, or only steps of a later slice): then version 1 is
   * the better first reply, not one that holds only the emergency numbers.
   */
  public Optional<String> build(Map<String, Object> journey, ErstantwortContext context) {
    var bausteine = payloadBuilder.buildBausteine(context);
    var sections = new ArrayList<Map<String, Object>>();
    for (var step : onBoardSteps(journey)) {
      if (!sentWithGreeting(step)) {
        continue;
      }
      sections.addAll(sectionsOf(step, bausteine, context));
    }
    if (sections.isEmpty()) {
      return Optional.empty();
    }
    emergencyPanel(bausteine)
        .filter(platform -> !carriesPlatformEmergencyText(sections, platform))
        .ifPresent(platform -> sections.add(beforeFirstAction(sections), platform));
    var payload = new LinkedHashMap<String, Object>();
    payload.put("type", FIRST_RESPONSE_TYPE);
    payload.put("version", MODULE_PAYLOAD_VERSION);
    payload.put("conversationType", CONVERSATION_TYPE);
    payload.put("sections", sections);
    try {
      return Optional.of(SYSTEM_NOTIFICATION_PREFIX + OBJECT_MAPPER.writeValueAsString(payload));
    } catch (JsonProcessingException exception) {
      log.warn("Could not serialize the journey first reply, sending version 1", exception);
      return Optional.empty();
    }
  }

  /** A safety section that already says the platform's emergency text. */
  private static boolean carriesPlatformEmergencyText(
      List<Map<String, Object>> sections, Map<String, Object> platform) {
    var platformText = String.valueOf(platform.get("body"));
    return sections.stream()
        .filter(section -> Boolean.TRUE.equals(section.get("safety")))
        .anyMatch(section -> String.valueOf(section.get("body")).contains(platformText));
  }

  /** Safety comes before every optional action (ADR-018): the e-mail, the closing. */
  private static int beforeFirstAction(List<Map<String, Object>> sections) {
    for (var index = 0; index < sections.size(); index++) {
      var section = sections.get(index);
      if ("fixed".equals(section.get("kind"))
          || String.valueOf(section.get("id")).endsWith(":closing")) {
        return index;
      }
    }
    return sections.size();
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
      return List.of(fixed(id, "email", plain(step.get("title"), context), null));
    }
    if ("addon".equals(kind) && "accountCompletion".equals(step.get("addon"))) {
      return List.of(fixed(id, "accountCompletion", plain(step.get("title"), context), null));
    }
    if ("panel".equals(kind)) {
      return panelSections(step, context);
    }
    if ("message".equals(kind)) {
      var html = html(step.get("body"), context);
      return html == null
          ? List.of()
          : List.of(
              withLinks(
                  message(id, ErstantwortTenantTextResolver.toPlainText(html)), linksOf(html)));
    }
    // Questions and ratings follow in a later slice; other core steps send nothing here.
    return List.of();
  }

  /**
   * Today's Bausteine as sections: with a headline a panel, without one a message; the e-mail
   * Baustein becomes the fixed e-mail module and keeps its explanation.
   */
  private static List<Map<String, Object>> greetingSections(
      String stepId, List<Map<String, Object>> bausteine) {
    var sections = new ArrayList<Map<String, Object>>();
    for (var baustein : bausteine) {
      var bausteinId = String.valueOf(baustein.get("id"));
      if (SILENCED_BAUSTEINE.contains(bausteinId)) {
        continue;
      }
      var id = stepId + ":" + bausteinId;
      var headline = baustein.get("headline") instanceof String value ? value : null;
      var body = String.valueOf(baustein.get("body"));
      Map<String, Object> section;
      if ("emailNotification".equals(bausteinId)) {
        section = fixed(id, "email", headline, body);
      } else if (headline == null) {
        section = message(id, body);
      } else {
        section = panel(id, headline, body, "collapsed", SAFETY_BAUSTEINE.contains(bausteinId));
      }
      sections.add(withLinks(section, bausteinLinks(baustein.get("links"))));
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
      var title = plain(aspect.get("title"), context);
      var html = html(aspect.get("body"), context);
      // The Frontend drops a panel without title or text; so does the payload.
      if (title != null && html != null) {
        sections.add(
            withLinks(
                panel(
                    String.valueOf(aspect.get("id")),
                    title,
                    ErstantwortTenantTextResolver.toPlainText(html),
                    display,
                    Boolean.TRUE.equals(aspect.get("safety"))),
                linksOf(html)));
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
  private static String plain(Object langMap, ErstantwortContext context) {
    var html = html(langMap, context);
    return html == null ? null : ErstantwortTenantTextResolver.toPlainText(html);
  }

  /** A journey text in the reader's language, as stored (HTML). */
  @SuppressWarnings("unchecked")
  private static String html(Object langMap, ErstantwortContext context) {
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
    return ErstantwortTenantTextResolver.pickHtml(texts, context.getLocale(), context.isInformal());
  }

  /** The editor's links ({@code <a href>}): plain text would otherwise lose where they lead. */
  private static List<Map<String, String>> linksOf(String html) {
    var links = new ArrayList<Map<String, String>>();
    for (var anchor : Jsoup.parseBodyFragment(html).select("a[href]")) {
      var url = anchor.attr("href").trim();
      var label = anchor.text().isBlank() ? url : anchor.text().strip();
      link(url, label).ifPresent(links::add);
    }
    return links;
  }

  /** The privacy and imprint links of a version-1 Baustein. */
  private static List<Map<String, String>> bausteinLinks(Object raw) {
    var links = new ArrayList<Map<String, String>>();
    if (raw instanceof List<?> list) {
      for (var entry : list) {
        if (entry instanceof Map<?, ?> link) {
          link(String.valueOf(link.get("url")), String.valueOf(link.get("label")))
              .ifPresent(links::add);
        }
      }
    }
    return links;
  }

  private static Optional<Map<String, String>> link(String url, String label) {
    if (url == null || LINK_SCHEMES.stream().noneMatch(url.toLowerCase(Locale.ROOT)::startsWith)) {
      return Optional.empty();
    }
    var link = new LinkedHashMap<String, String>();
    link.put("label", label);
    link.put("url", url);
    return Optional.of(link);
  }

  private static Map<String, Object> withLinks(
      Map<String, Object> section, List<Map<String, String>> links) {
    if (!links.isEmpty()) {
      section.put("links", links);
    }
    return section;
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

  private static Map<String, Object> fixed(String id, String module, String title, String body) {
    var section = new LinkedHashMap<String, Object>();
    section.put("id", id);
    section.put("kind", "fixed");
    section.put("module", module);
    if (title != null) {
      section.put("title", title);
    }
    if (body != null) {
      section.put("body", body);
    }
    return section;
  }
}
