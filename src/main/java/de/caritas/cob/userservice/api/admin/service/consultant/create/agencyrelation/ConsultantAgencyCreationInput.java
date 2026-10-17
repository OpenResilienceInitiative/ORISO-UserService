package de.caritas.cob.userservice.api.admin.service.consultant.create.agencyrelation;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Set;

/** Definition for required input data used to create a new consultantAgency. */
public interface ConsultantAgencyCreationInput {

  String getConsultantId();

  Set<String> getRoleSetNames();

  Long getAgencyId();

  default Collection<Long> getAdditionalAgencyIds() {
    return Set.of(getAgencyId());
  }

  /**
   * Whether the caller writes the identity roles itself, later (see {@code identityRolesOf}). Used
   * by a caller that has a refusable step of its own and wants the roles to follow it, so a refusal
   * leaves the identity provider as it was.
   */
  default boolean defersIdentityRoles() {
    return false;
  }

  default LocalDateTime getCreateDate() {
    return nowInUtc();
  }

  default LocalDateTime getUpdateDate() {
    return nowInUtc();
  }
}
