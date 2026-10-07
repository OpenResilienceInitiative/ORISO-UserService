package de.caritas.cob.userservice.api.service.accountinvite;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.manager.consultingtype.ConsultingTypeManager;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** Actual live agency and public consulting policy precede atomic invitation account creation. */
@Component
@RequiredArgsConstructor
public class ConsultantInitialRolesForInvite {
  public record InitialRoles(AgencyDTO agency, Set<String> roles) {}

  private final AgencyService agencies;
  private final ConsultingTypeManager consultingTypes;

  public InitialRoles resolve(AccountInvite invite) {
    var agency = agencies.getPublicImportAgency(invite.getAgencyId(), invite.getTenantId());
    if (agency == null
        || !Objects.equals(agency.getId(), invite.getAgencyId())
        || !Objects.equals(agency.getTenantId(), invite.getTenantId()))
      throw new AccessDeniedException("Invite agency changed before account creation");
    var settings = consultingTypes.getConsultingTypeSettings(agency.getConsultingType());
    var roles = new LinkedHashSet<String>();
    roles.add("consultant");
    if (settings.getRoles() != null && settings.getRoles().getConsultant() != null)
      roles.addAll(
          settings
              .getRoles()
              .getConsultant()
              .getRoleSets()
              .getOrDefault("CONSULTANT_DEFAULT", List.of()));
    if (!Set.of("consultant", "group-chat-consultant").containsAll(roles))
      throw new AccessDeniedException("Invitation default policy exceeds consultant initial roles");
    return new InitialRoles(agency, Set.copyOf(roles));
  }
}
