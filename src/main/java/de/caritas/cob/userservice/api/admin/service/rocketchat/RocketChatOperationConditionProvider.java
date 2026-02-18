package de.caritas.cob.userservice.api.admin.service.rocketchat;

import static java.util.Objects.nonNull;

import de.caritas.cob.userservice.api.manager.consultingtype.ConsultingTypeManager;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** Provides conditions used for Rocket.Chat group interactions. */
@RequiredArgsConstructor
class RocketChatOperationConditionProvider {

  private final @NonNull IdentityClient identityClient;
  private final @NonNull Session session;
  private final @NonNull Consultant consultant;
  private final @NonNull ConsultingTypeManager consultingTypeManager;

  /**
   * Checks if the current {@link Consultant} can be added to Rocket.Chat group.
   *
   * @return true if consultant can be added
   */
  boolean canAddToRocketChatGroup() {
    return isEnquiry() || isTeamSession();
  }

  private boolean isEnquiry() {
    return this.session.getStatus().equals(SessionStatus.NEW);
  }

  private boolean isTeamSession() {
    return this.session.getStatus().equals(SessionStatus.IN_PROGRESS)
        && this.session.isTeamSession()
        && canAddToTeamConsultingSession();
  }

  private Boolean canAddToTeamConsultingSession() {
    var consultingTypeSettings =
        consultingTypeManager.getConsultingTypeSettings(this.session.getConsultingTypeId());
    return nonNull(consultingTypeSettings);
  }
}
