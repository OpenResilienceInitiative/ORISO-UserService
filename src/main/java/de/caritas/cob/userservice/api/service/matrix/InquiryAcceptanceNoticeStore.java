package de.caritas.cob.userservice.api.service.matrix;

import de.caritas.cob.userservice.api.helper.ConsultantDisplayNameResolver;
import de.caritas.cob.userservice.api.helper.MatrixIds;
import de.caritas.cob.userservice.api.model.*;
import de.caritas.cob.userservice.api.model.InquiryAcceptanceNotice.DeliveryState;
import de.caritas.cob.userservice.api.port.out.*;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The immutable acceptance fact shares the existing short ownership transaction, not its network
 * steps.
 */
@Service
@RequiredArgsConstructor
public class InquiryAcceptanceNoticeStore {
  private final SessionRepository sessions;
  private final InquiryAcceptanceNoticeRepository notices;
  private final ConsultantDisplayNameResolver names;

  /** Called only inside the winning INITIAL/NEW-to-IN_PROGRESS ownership update. */
  @Transactional
  public void prepare(Session current) {
    if (current.getConversationType() != ConversationType.AGENCY_COUNSELLING
        || current.getConsultant() == null) return;
    if (notices.findById(current.getId()).isEmpty()) {
      var copy = InquiryAcceptanceNoticeCopy.forSession(current);
      notices.save(
          InquiryAcceptanceNotice.builder()
              .sessionId(current.getId())
              .ownerId(current.getConsultant().getId())
              .ownershipRevision(current.getOwnershipRevision())
              .acceptedAtUtc(LocalDateTime.now(ZoneOffset.UTC))
              .publicAdvisorName(names.resolveMatrixDisplayName(current.getConsultant()))
              .title(copy.title())
              .description(copy.description())
              .deliveryState(DeliveryState.PREPARING)
              .build());
    }
  }

  /** A failed Matrix assignment cancels only its own unfinalized fact. */
  @Transactional
  public void cancelPreparation(Long sessionId, long revision) {
    var notice = notices.findByIdForUpdate(sessionId).orElse(null);
    if (notice != null
        && notice.getOwnershipRevision() == revision
        && notice.getDeliveryState() == DeliveryState.PREPARING) notices.delete(notice);
  }

  @Transactional
  public Optional<Long> recordSuccessfulInitialAcceptance(Session accepted) {
    if (accepted == null || accepted.getId() == null) return Optional.empty();
    Session current = sessions.findByIdForUpdate(accepted.getId()).orElse(null);
    var notice = notices.findByIdForUpdate(accepted.getId()).orElse(null);
    if (!matches(current, notice)
        || current.getOwnershipRevision() != accepted.getOwnershipRevision()
        || accepted.getConsultant() == null
        || !Objects.equals(current.getConsultant().getId(), accepted.getConsultant().getId()))
      return Optional.empty();
    return activate(current, notice);
  }

  private boolean matches(Session current, InquiryAcceptanceNotice notice) {
    return current != null
        && notice != null
        && current.getStatus() == Session.SessionStatus.IN_PROGRESS
        && current.getConversationType() == ConversationType.AGENCY_COUNSELLING
        && current.getConsultant() != null
        && Objects.equals(current.getConsultant().getId(), notice.getOwnerId())
        && current.getOwnershipRevision() == notice.getOwnershipRevision();
  }

  private boolean validRoomParticipants(Session current) {
    return MatrixIds.isRoomId(current.getMatrixRoomId())
        && current.getConsultant() != null
        && MatrixIds.isUserId(current.getConsultant().getMatrixUserId())
        && current.getUser() != null
        && MatrixIds.isUserId(current.getUser().getMatrixUserId());
  }

  private Optional<Long> activate(Session current, InquiryAcceptanceNotice notice) {
    if (notice.getDeliveryState() != DeliveryState.PREPARING || !validRoomParticipants(current))
      return Optional.empty();
    notice.setMatrixRoomId(current.getMatrixRoomId());
    notice.setSenderMatrixId(current.getConsultant().getMatrixUserId());
    notice.setDeliveryState(DeliveryState.PENDING);
    notice.setNextAttemptAtUtc(LocalDateTime.now(ZoneOffset.UTC));
    notices.save(notice);
    return Optional.of(current.getId());
  }

  /** Durable pre-transport backoff makes the bounded queue fair to newer deliverable notices. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void deferPending(Long sessionId) {
    var notice = notices.findByIdForUpdate(sessionId).orElse(null);
    if (notice == null || notice.getDeliveryState() != DeliveryState.PENDING) return;
    notice.setNextAttemptAtUtc(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1));
    notices.save(notice);
  }

  /** Separate committed claim is required even when called from an afterCommit callback. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean claimForSend(Long sessionId) {
    var notice = notices.findByIdForUpdate(sessionId).orElse(null);
    if (notice == null || notice.getDeliveryState() != DeliveryState.PENDING) return false;
    notice.setDeliveryState(DeliveryState.UNCERTAIN);
    notices.save(notice);
    return true;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void acknowledge(Long sessionId, String eventId) {
    var notice = notices.findByIdForUpdate(sessionId).orElseThrow();
    if (notice.getDeliveryState() != DeliveryState.UNCERTAIN) return;
    notice.setMatrixEventId(eventId);
    notice.setDeliveryState(DeliveryState.SENT);
    notices.save(notice);
  }
}
