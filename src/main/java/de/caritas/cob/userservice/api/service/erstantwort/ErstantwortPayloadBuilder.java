package de.caritas.cob.userservice.api.service.erstantwort;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Builds the one persisted {@code [SYSTEM_NOTIFICATION]} event that carries the whole Erstantwort
 * (ADR-018, ORISO-UserService#926).
 *
 * <h2>What this class is responsible for</h2>
 *
 * Resolving each Baustein of the platform catalogue against this session's configuration and
 * <b>freezing the resulting wording into the payload</b>. That is the load-bearing decision of
 * ADR-018 §4: what was said to a person has to stay provable, and a Träger who edits their greeting
 * next month must not retroactively change what an advice seeker was told in a room that carries
 * §11 KDG special-category data.
 *
 * <h2>What it deliberately does not do</h2>
 *
 * <ul>
 *   <li><b>It stores no completion state.</b> An action Baustein carries only its {@code kind}; the
 *       client reads live whether the person already has an e-mail address or 2FA. Storing it here
 *       would be the new state ADR-018 §4 forbids, and it would go stale the moment somebody
 *       changed their profile.
 *   <li><b>It creates no Matrix account.</b> Carimat is a rendering identity (ADR-018 §3). A bot in
 *       the room would be an additional Megolm key holder in a counselling room, and a bot has no
 *       Schweigepflicht. The event is posted with the existing credential resolution.
 * </ul>
 *
 * <p>The wording here is the German platform text. It is written <b>gender-neutral by
 * reformulation</b>, not by notation (ADR-018 §7) — "eine passende Ansprechperson", never {@code
 * Berater*in} / {@code Berater_innen} / {@code Berater:innen}. The frontend catalogue
 * (ORISO-Frontend {@code erstantwortCatalogue.ts}) carries the same texts for the client-side
 * triggers and for Storybook; both sides are pinned by their own tests.
 */
@Component
@Slf4j
public class ErstantwortPayloadBuilder {

  public static final String SYSTEM_NOTIFICATION_PREFIX = "[SYSTEM_NOTIFICATION]";
  public static final String FIRST_RESPONSE_TYPE = "FIRST_RESPONSE";

  /**
   * Wire-format version, shared with the frontend. Bump on any breaking change to the shape; a
   * client that meets a higher version renders nothing rather than guessing.
   */
  public static final int PAYLOAD_VERSION = 1;

  /** ADR-018: the platform default Antwortfrist. */
  public static final int DEFAULT_RESPONSE_DEADLINE_DAYS = 2;

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  /**
   * @return the full message body including the {@code [SYSTEM_NOTIFICATION]} prefix, or {@code
   *     null} if the payload could not be serialised (in which case nothing is posted — a raw blob
   *     in a counselling room is worse than no Erstantwort).
   */
  public String buildFirstResponseBody(ErstantwortContext context) {
    var payload = new LinkedHashMap<String, Object>();
    payload.put("type", FIRST_RESPONSE_TYPE);
    payload.put("version", PAYLOAD_VERSION);
    payload.put("bausteine", buildBausteine(context));
    try {
      return SYSTEM_NOTIFICATION_PREFIX + OBJECT_MAPPER.writeValueAsString(payload);
    } catch (JsonProcessingException exception) {
      log.warn("Could not serialize Erstantwort payload", exception);
      return null;
    }
  }

  /**
   * Catalogue order <b>is</b> render order, and two positions in it are not cosmetic: the two
   * safety-bearing Bausteine come before every optional action, because consent has to precede data
   * transmission; and the free notice sits at a fixed position just before the closing, so a Träger
   * cannot push their own text to the top of a message that is meant to be a transparency record.
   */
  private List<Map<String, Object>> buildBausteine(ErstantwortContext context) {
    var modality = context.modalityOrDefault();
    var informal = context.isInformal();
    var bausteine = new ArrayList<Map<String, Object>>();

    add(
        bausteine,
        "greeting",
        null,
        resolve(
            context.getGreetingByTopic(),
            context.getGreetingByAgency(),
            context.getGreetingByTenant(),
            informal
                ? "Schön, dass Du Dich gemeldet hast. Deine Nachricht ist bei uns angekommen."
                : "Schön, dass Sie sich gemeldet haben. Ihre Nachricht ist bei uns angekommen."));

    if (modality.isAsynchronous()) {
      // Live Chat has neither teams nor case handover — nothing to disclose.
      add(
          bausteine,
          "whoReadsAlong",
          informal ? "Wer Deine Nachricht liest" : "Wer Ihre Nachricht liest",
          resolve(
              context.getWhoReadsAlongByTopic(),
              context.getWhoReadsAlongByAgency(),
              context.getWhoReadsAlongByTenant(),
              (informal ? "Deine" : "Ihre")
                  + " Nachricht lesen ausschließlich die Fachkräfte der zuständigen"
                  + " Beratungsstelle. Alle sind zur Verschwiegenheit verpflichtet."));

      var days =
          context.getResponseDeadlineDays() == null
              ? DEFAULT_RESPONSE_DEADLINE_DAYS
              : context.getResponseDeadlineDays();
      add(
          bausteine,
          "responseDeadline",
          informal ? "Wann Du eine Antwort bekommst" : "Wann Sie eine Antwort erhalten",
          informal
              ? "Du bekommst innerhalb von "
                  + days
                  + " Werktagen eine Antwort von einer passenden Ansprechperson."
              : "Sie erhalten innerhalb von "
                  + days
                  + " Werktagen eine Antwort von einer passenden Ansprechperson.");
    }

    add(
        bausteine,
        "modalityNote",
        null,
        "Die Beratung findet schriftlich in diesem geschützten Bereich statt. "
            + (informal
                ? "Du kannst jederzeit weiterschreiben."
                : "Sie können jederzeit weiterschreiben."));

    /* ADR-018 §6: the two safety-bearing Bausteine carry no toggle at all — the
    deliberate interim substitute for the postponed platform/Träger permission
    model. They are therefore unconditional here, in every modality. */
    add(
        bausteine,
        "noPersonalData",
        "Bitte keine persönlichen Daten senden",
        (informal ? "Bitte schreib uns" : "Bitte schreiben Sie uns")
            + " keinen vollständigen Namen, keine Adresse und keine Telefonnummer."
            + " Für die Beratung brauchen wir das nicht.");

    add(
        bausteine,
        "emergencyNumbers",
        "Wenn es nicht warten kann",
        "Bei einer akuten Notlage "
            + (informal ? "erreichst Du" : "erreichen Sie")
            + " rund um die Uhr die Telefonseelsorge unter 0800 111 0 111 oder"
            + " 0800 111 0 222 und den Rettungsdienst unter 112.");

    var dataProtection =
        add(
            bausteine,
            "dataProtection",
            null,
            "Wie wir mit "
                + (informal ? "Deinen" : "Ihren")
                + " Daten umgehen, steht in der Datenschutzerklärung.");
    addLinks(dataProtection, context);

    // The single escape hatch, at a fixed position. Absent until a Träger fills it.
    var freeNotice =
        resolve(
            context.getFreeNoticeByTopic(),
            context.getFreeNoticeByAgency(),
            context.getFreeNoticeByTenant(),
            null);
    if (isNotBlank(freeNotice)) {
      add(bausteine, "freeNotice", null, freeNotice);
    }

    var emailBaustein =
        add(
            bausteine,
            "emailNotification",
            "Benachrichtigung per E-Mail",
            (informal ? "Du kannst" : "Sie können")
                + " freiwillig eine E-Mail-Adresse hinterlegen. Dann "
                + (informal ? "bekommst Du" : "erhalten Sie")
                + " eine Nachricht, sobald eine Antwort da ist. Der Inhalt der"
                + " Beratung steht nie in dieser E-Mail.");
    emailBaustein.put("action", action("ADD_EMAIL", "E-Mail-Adresse angeben"));

    var protectionBaustein =
        add(
            bausteine,
            "accountProtection",
            (informal ? "Dein" : "Ihr") + " Zugang, zusätzlich geschützt",
            (informal ? "Du kannst Deinen" : "Sie können Ihren")
                + " Zugang mit einem zweiten Faktor sichern, damit niemand sonst"
                + " hineinkommt.");
    protectionBaustein.put("action", action("ENABLE_2FA", "Zugang schützen"));

    add(
        bausteine,
        "closing",
        null,
        resolve(
            context.getClosingByTopic(),
            context.getClosingByAgency(),
            context.getClosingByTenant(),
            informal
                ? "Bis bald — wir melden uns bei Dir."
                : "Bis bald — wir melden uns bei Ihnen."));

    return bausteine;
  }

  /**
   * The resolution chain of ADR-018 §6, most specific first. A blank value at any level counts as
   * absent rather than as an intentionally empty Baustein — an editor that saved an empty field
   * must not silence a text the level below it still provides.
   */
  private String resolve(String byTopic, String byAgency, String byTenant, String platform) {
    if (isNotBlank(byTopic)) {
      return byTopic.trim();
    }
    if (isNotBlank(byAgency)) {
      return byAgency.trim();
    }
    if (isNotBlank(byTenant)) {
      return byTenant.trim();
    }
    return platform;
  }

  private Map<String, Object> add(
      List<Map<String, Object>> bausteine, String id, String headline, String body) {
    var baustein = new LinkedHashMap<String, Object>();
    baustein.put("id", id);
    if (isNotBlank(headline)) {
      baustein.put("headline", headline);
    }
    baustein.put("body", body);
    bausteine.add(baustein);
    return baustein;
  }

  /**
   * Derived link targets, from configuration only. The department owns its privacy policy and
   * imprint (ADR-003 / ADR-014), and an absent one produces no link rather than a dead one.
   */
  private void addLinks(Map<String, Object> baustein, ErstantwortContext context) {
    var links = new ArrayList<Map<String, String>>();
    if (isNotBlank(context.getDataPrivacyUrl())) {
      links.add(Map.of("label", "Datenschutzerklärung", "url", context.getDataPrivacyUrl().trim()));
    }
    if (isNotBlank(context.getImprintUrl())) {
      links.add(Map.of("label", "Impressum", "url", context.getImprintUrl().trim()));
    }
    if (!links.isEmpty()) {
      baustein.put("links", links);
    }
  }

  private Map<String, String> action(String kind, String label) {
    var action = new LinkedHashMap<String, String>();
    action.put("kind", kind);
    action.put("label", label);
    return action;
  }
}
