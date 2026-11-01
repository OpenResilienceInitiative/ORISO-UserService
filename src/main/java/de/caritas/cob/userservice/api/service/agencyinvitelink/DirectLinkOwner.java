package de.caritas.cob.userservice.api.service.agencyinvitelink;

import de.caritas.cob.userservice.api.model.AgencyInviteLink;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import java.util.Objects;

/** Whether the owner of a counsellor direct link still serves it (#1475). */
final class DirectLinkOwner {

  private DirectLinkOwner() {}

  /** Other links have no owner to check. A departed owner makes the link "no longer active". */
  static void requireStillServing(ConsultantRepository consultants, AgencyInviteLink link) {
    if (!InviteLinkKind.COUNSELLOR.name().equals(link.getLinkKind())
        || !InviteLinkChatType.isDirectMedium(link.getChatType())) {
      return;
    }
    // The owner may have left the Beratungsstelle or the platform since creating the link.
    var stillServing =
        link.getConsultantId() != null
            && consultants
                .findByIdAndDeleteDateIsNull(link.getConsultantId())
                .filter(owner -> worksFor(owner, link.getAgencyId()))
                .isPresent();
    if (!stillServing) {
      throw new DirectLinkInactiveException();
    }
  }

  /** One rule for creating a link and for its owner still serving it. */
  static boolean worksFor(Consultant consultant, Long agencyId) {
    return consultant.getConsultantAgencies() != null
        && consultant.getConsultantAgencies().stream()
            .anyMatch(
                ca -> ca.getDeleteDate() == null && Objects.equals(ca.getAgencyId(), agencyId));
  }
}
