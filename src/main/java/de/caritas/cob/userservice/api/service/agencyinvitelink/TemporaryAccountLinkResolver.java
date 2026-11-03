package de.caritas.cob.userservice.api.service.agencyinvitelink;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.model.AgencyInviteLink;
import de.caritas.cob.userservice.api.port.out.AgencyInviteLinkRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Validates counsellor direct links and links to a running call before Keycloak or Matrix account
 * creation (ADR-029 §3).
 */
@Component
@RequiredArgsConstructor
public class TemporaryAccountLinkResolver {

  private final @NonNull AgencyInviteLinkRepository repository;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull DirectInvitePolicy directInvitePolicy;
  private final @NonNull CallShareLinkService callShareLinks;

  /** The direct link the registration names, or empty for every other registration. */
  public Optional<AgencyInviteLink> resolve(UserDTO userDTO) {
    if (isBlank(userDTO.getDirectLinkToken())) {
      return Optional.empty();
    }
    if (userDTO.getGroupChatId() != null || userDTO.isConsultantSet()) {
      throw new BadRequestException(
          "A direct link cannot be combined with a group invite or a consultant link.");
    }
    var link =
        repository
            .findByToken(userDTO.getDirectLinkToken())
            .orElseThrow(() -> new ForbiddenException("The direct link is not valid"));
    if (InviteLinkKind.CALL.name().equals(link.getLinkKind())) {
      requireCallRegistration(link, userDTO);
      return Optional.of(link);
    }
    if (!InviteLinkKind.COUNSELLOR.name().equals(link.getLinkKind())
        || !InviteLinkChatType.isDirectMedium(link.getChatType())) {
      throw new ForbiddenException("The direct link is not valid");
    }
    if (InviteLinkStatus.DISABLED.name().equals(link.getStatus())) {
      throw new DirectLinkInactiveException();
    }
    if (!InviteLinkStatus.ACTIVE.name().equals(link.getStatus())
        || (link.getExpiresAt() != null && link.getExpiresAt().isBefore(LocalDateTime.now()))) {
      throw new BadRequestException("Invite link expired");
    }
    requireMatchingAccountType(userDTO, link.isTemporaryAccount());
    var registrationTenant = TenantContext.getCurrentTenant();
    if (registrationTenant != null && !Objects.equals(registrationTenant, link.getTenantId())) {
      throw new ForbiddenException("Invite link belongs to another tenant");
    }
    if (!directInvitePolicy.allows(
        link.getTenantId(), link.getAgencyId(), InviteLinkChatType.valueOf(link.getChatType()))) {
      throw new ForbiddenException("Direct invitations are not allowed here");
    }
    DirectLinkOwner.requireStillServing(consultantRepository, link);
    return Optional.of(link);
  }

  private void requireCallRegistration(AgencyInviteLink link, UserDTO userDTO) {
    if (link.getTenantId() == null
        || !Objects.equals(TenantContext.getCurrentTenant(), link.getTenantId())
        || link.getAgencyId() == null
        || !Objects.equals(link.getAgencyId(), userDTO.getAgencyId())
        || link.getConsultingTypeId() == null
        || !link.getConsultingTypeId().toString().equals(userDTO.getConsultingType())
        || !Objects.equals(link.getTopicId(), userDTO.getMainTopicId())) {
      throw new ForbiddenException("Registration does not match the call invitation");
    }
    if (!"true".equals(userDTO.getTermsAccepted())) {
      throw new BadRequestException("The terms must be accepted before joining the call");
    }
    callShareLinks.requireUsable(link);
    requireMatchingAccountType(
        userDTO, link.isTemporaryAccount() && callShareLinks.temporaryAccountAllowed(link));
  }

  private static void requireMatchingAccountType(UserDTO userDTO, boolean temporary) {
    if (userDTO.isTemporary() != temporary) {
      throw new BadRequestException(
          temporary
              ? "This direct link creates temporary accounts."
              : "This direct link does not create temporary accounts.");
    }
  }
}
