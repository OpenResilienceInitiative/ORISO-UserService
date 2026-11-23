package de.caritas.cob.userservice.api.service.notification;

import static de.caritas.cob.userservice.api.service.notification.NotificationEmailDiagnostics.*;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantStatus;
import de.caritas.cob.userservice.api.port.out.IdentityAccountStatusLookup;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Current account eligibility, independent of optional notification preferences. */
@Service
@RequiredArgsConstructor
public class NotificationAccountEligibility {
  private final AccountInactivityService lifecycle;
  private final IdentityAccountStatusLookup identities;

  public boolean allows(Consultant consultant) {
    return consultant != null
        && consultant.getDeleteDate() == null
        && (consultant.getStatus() == ConsultantStatus.CREATED
            || consultant.getStatus() == ConsultantStatus.IN_PROGRESS)
        && allows(consultant.getId());
  }

  public boolean allows(String identityId) {
    if (identityId == null || identityId.isBlank()) return false;
    boolean active =
        at(
            Stage.ELIGIBILITY,
            () ->
                lifecycle
                    .snapshot(identityId)
                    .map(row -> row.status() == AccountInactivityService.Status.ACTIVE)
                    .orElse(true));
    return active && at(Stage.IDENTITY, () -> identities.findEnabledById(identityId).orElse(false));
  }
}
