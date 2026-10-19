package de.caritas.cob.userservice.api.service.accountinvite;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.TopicPermission;
import java.util.List;

/** How far an invited counsellor may extend their own topics. Pure rules, no lookups. */
public final class TopicPermissionPolicy {

  /**
   * An invite that names no permission (e.g. a CSV row without that column) lets the counsellor
   * pick their own departments, whatever the agency default says (product decision, #1026).
   */
  static final TopicPermission OMITTED = TopicPermission.SELECT_EXISTING;

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
    TopicPermission permission = requested != null ? requested : OMITTED;
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
}
