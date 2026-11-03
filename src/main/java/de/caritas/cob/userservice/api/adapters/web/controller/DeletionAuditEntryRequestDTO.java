package de.caritas.cob.userservice.api.adapters.web.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionAuditOutcome;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionAuditTargetType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

/**
 * A deletion that AgencyService or TenantService reports. Opaque ids and labels only - the patterns
 * keep names and e-mail addresses out of the log.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class DeletionAuditEntryRequestDTO {

  /** Only {@code AGENCY} and {@code TENANT} may be reported this way. */
  @NotNull private DeletionAuditTargetType targetType;

  @NotNull
  @Pattern(regexp = "[0-9]{1,18}")
  private String targetId;

  private Long tenantId;

  @NotNull private DeletionAuditOutcome outcome;

  /** Honoured for the technical account only; an admin's own token names the actor. */
  @Pattern(regexp = "[A-Za-z0-9-]{1,64}")
  private String actorId;

  @Pattern(regexp = "[A-Z_]{1,32}")
  private String actorRole;
}
