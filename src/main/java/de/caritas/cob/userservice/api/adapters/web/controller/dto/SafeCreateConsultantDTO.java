package de.caritas.cob.userservice.api.adapters.web.controller.dto;

import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantDTO;

/** Keeps framework argument rendering from disclosing the decoded body. */
public final class SafeCreateConsultantDTO extends CreateConsultantDTO {
  @Override
  public String toString() {
    return "[REDACTED consultant creation]";
  }
}
