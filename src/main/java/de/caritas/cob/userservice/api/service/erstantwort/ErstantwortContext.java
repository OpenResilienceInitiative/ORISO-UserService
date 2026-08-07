package de.caritas.cob.userservice.api.service.erstantwort;

import lombok.Builder;
import lombok.Value;

/**
 * Everything the Erstantwort needs in order to resolve, gathered once at send time.
 *
 * <p>The {@code …ByTopic / …ByAgency / …ByTenant} triples implement ADR-018 §6: editorial content
 * hangs at Träger level, but the resolution chain <em>Fachbereich ?? Beratungsstelle ?? Träger ??
 * Plattform</em> exists from day one, so that adding the middle levels later is a form rather than
 * a migration. In this slice only the platform link is populated in production — the fields are
 * here, resolved and tested, waiting for ORISO-Admin#601 to fill them.
 *
 * <p>Derived values ({@code responseDeadlineDays}, {@code dataPrivacyUrl}, {@code imprintUrl}) come
 * from configuration and are never text fields: a typed claim about the system can be false, a
 * rendered one cannot.
 */
@Value
@Builder
public class ErstantwortContext {

  ErstantwortModality modality;

  /**
   * The Antwortfrist as a <em>number</em> (ADR-018), so the promise is exactly one value that can
   * be compared against what actually happened. {@code null} falls back to the platform default.
   */
  Integer responseDeadlineDays;

  /** The tenant's existing {@code languageFormal} flag: a Träger writes one German variant. */
  boolean informal;

  String dataPrivacyUrl;
  String imprintUrl;

  String greetingByTopic;
  String greetingByAgency;
  String greetingByTenant;

  String whoReadsAlongByTopic;
  String whoReadsAlongByAgency;
  String whoReadsAlongByTenant;

  String freeNoticeByTopic;
  String freeNoticeByAgency;
  String freeNoticeByTenant;

  String closingByTopic;
  String closingByAgency;
  String closingByTenant;

  /** Live Chat is the safe default only for what is *absent*, never for what is asserted. */
  public ErstantwortModality modalityOrDefault() {
    return modality == null ? ErstantwortModality.AGENCY_COUNSELLING : modality;
  }
}
