package de.caritas.cob.userservice.api.service.session;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import java.util.function.ToIntFunction;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * US#1060: keeps the Matrix membership of an agency's open enquiry rooms in step with its roster,
 * strictly <em>after</em> the roster change is committed.
 *
 * <p>Why after the commit and not inline. {@code ConsultantAdminFacade#setConsultantAgencies} is
 * transactional and applies removals before creations, one agency at a time. An inline fan-out
 * would push the first agency's membership into Matrix and then, if a later agency is rejected,
 * roll the database back around it. Matrix has no transaction to join, so the counsellor would stay
 * a member of the enquiry rooms of an agency they were never given — a confidentiality bug, and the
 * mirror-image lockout on the removal side. Publishing an event and acting on {@link
 * TransactionPhase#AFTER_COMMIT} makes that outcome structurally impossible.
 *
 * <p>{@code fallbackExecution = true} is load-bearing rather than decorative: the POST endpoint
 * ({@code ConsultantAdminFacade#createNewConsultantAgency}) and the CSV import are not
 * transactional at all, and without the fallback the listener would silently never run on those
 * paths.
 *
 * <p>Nothing escapes this listener. {@code CreateConsultantSaga#assignAgenciesOrRollback} deletes
 * the freshly created consultant on any {@link RuntimeException}, so a Synapse hiccup here would
 * otherwise cost an admin the consultant they just created. The relation in the database is the
 * source of truth; a failed fan-out degrades to "membership repaired later".
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgencyMembershipSyncListener {

  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull AgencyLateJoinerMembershipService agencyLateJoinerMembershipService;

  /**
   * Kill switch for bulk operations. The CSV import creates relations row by row and agency by
   * agency, so a large import multiplies the per-relation fan-out by every open enquiry of every
   * agency involved. Operations can switch the fan-out off for that window and repair membership
   * afterwards rather than have one request hammer Synapse.
   */
  @Value("${matrix.membership.lateJoiner.enabled:true}")
  private boolean lateJoinerMembershipEnabled;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onConsultantJoinedAgency(ConsultantJoinedAgencyEvent event) {
    syncMembership(
        event.consultantId(),
        event.agencyId(),
        consultant ->
            agencyLateJoinerMembershipService.joinConsultantIntoOpenEnquiryRooms(
                consultant, event.agencyId()));
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onConsultantLeftAgency(ConsultantLeftAgencyEvent event) {
    syncMembership(
        event.consultantId(),
        event.agencyId(),
        consultant ->
            agencyLateJoinerMembershipService.removeConsultantFromOpenEnquiryRooms(
                consultant, event.agencyId()));
  }

  private void syncMembership(
      String consultantId, Long agencyId, ToIntFunction<Consultant> membershipChange) {
    if (!lateJoinerMembershipEnabled) {
      log.debug(
          "Late joiner membership is disabled; enquiry rooms of agency {} left untouched for"
              + " consultant {}",
          agencyId,
          consultantId);
      return;
    }

    try {
      consultantRepository
          .findByIdAndDeleteDateIsNull(consultantId)
          .ifPresentOrElse(
              membershipChange::applyAsInt,
              () ->
                  log.warn(
                      "Consultant {} no longer exists; enquiry room membership of agency {} not"
                          + " synchronised",
                      consultantId,
                      agencyId));
    } catch (RuntimeException ex) {
      log.warn(
          "Could not synchronise enquiry room membership of consultant {} in agency {}: {}",
          consultantId,
          agencyId,
          ex.getMessage());
    }
  }
}
