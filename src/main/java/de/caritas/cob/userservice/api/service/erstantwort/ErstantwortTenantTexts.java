package de.caritas.cob.userservice.api.service.erstantwort;

import lombok.Builder;
import lombok.Value;

/** The Träger-level texts of one send, already resolved to a language and reduced to plain text. */
@Value
@Builder
public class ErstantwortTenantTexts {
  String greeting;
  String whoReadsAlong;
  String emergencyAddition;
  String freeNotice;
  String closing;

  /** The Träger's Antwortfrist when 1-30, otherwise {@code null} (= platform default). */
  Integer responseDeadlineDays;

  public static ErstantwortTenantTexts none() {
    return ErstantwortTenantTexts.builder().build();
  }
}
