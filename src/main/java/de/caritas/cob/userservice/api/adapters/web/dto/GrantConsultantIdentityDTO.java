package de.caritas.cob.userservice.api.adapters.web.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.Data;

/**
 * Request payload for granting an existing admin user a full functional consultant identity.
 *
 * <p>Part of the multi-identity foundation: the same Keycloak account (and id) may hold both an
 * admin row and a consultant row. This DTO carries the consultant-specific attributes needed to
 * materialise the consultant identity (absence, language, agency and topic assignments) without
 * creating a new Keycloak user or changing the existing password.
 */
@Data
public class GrantConsultantIdentityDTO {

  /** Whether the granted consultant identity should also receive the group-chat-consultant role. */
  private boolean isGroupchatConsultant = false;

  /** Whether the consultant identity should initially be marked as absent. */
  private boolean absent;

  /** Optional absence message shown while the consultant is absent. */
  private String absenceMessage;

  /** Whether the consultant uses formal language (Sie) instead of informal (Du). */
  private boolean formalLanguage;

  /** Agencies the new consultant identity should be assigned to. Must not be {@code null}. */
  @NotNull private List<Long> agencyIds;

  /** Optional topics the new consultant identity should be associated with. */
  private List<Long> topicIds;
}
