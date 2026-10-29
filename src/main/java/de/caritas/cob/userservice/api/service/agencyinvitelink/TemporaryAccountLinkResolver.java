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
 * Registration through a counsellor direct link, temporary or persistent (ADR-029 §3, #1476). The
 * same checks as starting the conversation run before any Keycloak or Matrix user exists, so an
 * invalid or switched-off link never leaves an account behind.
 */
@Component
@RequiredArgsConstructor
public class TemporaryAccountLinkResolver {

  private final @NonNull AgencyInviteLinkRepository repository;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull DirectInvitePolicy directInvitePolicy;

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
    if (userDTO.isTemporary() != link.isTemporaryAccount()) {
      // The link decides the account kind; the registration must ask for the same one.
      throw new BadRequestException(
          link.isTemporaryAccount()
              ? "This direct link creates temporary accounts."
              : "This direct link does not create temporary accounts.");
    }
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
}
