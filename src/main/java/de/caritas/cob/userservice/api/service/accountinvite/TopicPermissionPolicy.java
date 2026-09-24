package de.caritas.cob.userservice.api.service.accountinvite;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.TopicPermission;
import java.util.List;

/** How far an invited counsellor may extend their own topics. Pure rules, no lookups. */
public final class TopicPermissionPolicy {

  /** Must match what AgencyService writes onto every newly created agency. */
  static final TopicPermission NEW_AGENCY_DEFAULT = TopicPermission.NONE;

  // TODO(ORISO-Admin#1026): open product question. Frank decided "new invites default NONE", but
  // an omitted value still takes the agency default (CREATE for every existing agency). Once he
  // confirms, set this to false; nothing else changes.
  static final boolean OMITTED_TAKES_THE_AGENCY_DEFAULT = true;

  private TopicPermissionPolicy() {}

  /** The permission a new invite is stored with; {@code agency} is null for a new agency. */
  public static TopicPermission decide(
      AccountInviteTargetRole role,
      AgencyFacts.Agency agency,
      boolean newAgency,
      Long departmentId,
      TopicPermission requested) {
    if (alwaysCreates(role)) {
      return TopicPermission.CREATE;
    }
    if (role != AccountInviteTargetRole.COUNSELLOR) {
      return TopicPermission.NONE;
    }
    TopicPermission permission = requested != null ? requested : omitted(agency, newAgency);
    requireATopicToPick(
        permission, departmentId, newAgency, agency == null ? List.of() : agency.topicIds());
    return permission;
  }

  /** The permission the onboarding wizard applies; rows from before the setting mean CREATE. */
  public static TopicPermission effective(AccountInvite invite) {
    if (alwaysCreates(invite.getTargetRole()) || invite.getTopicPermission() == null) {
      return TopicPermission.CREATE;
    }
    return invite.getTopicPermission();
  }

  /** A new agency has no topics yet; its admin brings them before any counsellor onboards. */
  static void requireATopicToPick(
      TopicPermission permission, Long departmentId, boolean newAgency, List<Long> agencyTopics) {
    if (permission != TopicPermission.CREATE
        && departmentId == null
        && !newAgency
        && agencyTopics.isEmpty()) {
      throw new BadRequestException(
          "topicPermission "
              + permission
              + " needs a departmentId or an agency with at least one topic, otherwise the"
              + " counsellor could not choose any topic");
    }
  }

  /** An agency admin founds or runs the agency, so they always bring its topics. */
  private static boolean alwaysCreates(AccountInviteTargetRole role) {
    return role == AccountInviteTargetRole.AGENCY_ADMIN;
  }

  private static TopicPermission omitted(AgencyFacts.Agency agency, boolean newAgency) {
    if (!OMITTED_TAKES_THE_AGENCY_DEFAULT) {
      return TopicPermission.NONE;
    }
    if (newAgency) {
      return NEW_AGENCY_DEFAULT;
    }
    return agency == null ? TopicPermission.CREATE : agency.defaultPermission();
  }
}
