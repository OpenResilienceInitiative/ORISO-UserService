package de.caritas.cob.userservice.api.service.chat;

import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant.ParticipantRole;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.time.LocalDateTime;
import java.util.Comparator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Keeps the counsellor assigned to the session of a members-only chat a current member of it.
 *
 * <p>The session is created for the creator and stays assigned to them. A session assigned to a
 * counsellor is listed to and opened by that counsellor whatever the group membership says, so a
 * creator who handed the room over and left, or was removed, would keep seeing a room they are no
 * longer part of. Whoever leaves the chat therefore hands the session over first.
 */
@Component
@RequiredArgsConstructor
public class MembersOnlySessionAssignment {

  private final SessionRepository sessionRepository;
  private final ConsultantRepository consultantRepository;
  private final GroupChatParticipantRepository participantRepository;
  private final SessionSupervisorRepository supervisorRepository;

  /**
   * Call before the participation of {@code leaving} is deleted. Does nothing for an ordinary chat
   * (its session is a team session of the centre). Ends the leaving member's supervision of the
   * session; if the session is assigned to the leaving member, it goes to a remaining member, an
   * owner first.
   */
  public void handOverFrom(Chat series, GroupChatParticipant leaving) {
    if (series == null
        || !series.isMembersOnly()
        || leaving == null
        || leaving.getChatId() == null) {
      return;
    }
    TenantContext.supplyAcrossTenants(
        () -> {
          endSupervisionBy(leaving);
          var session = sessionRepository.findById(leaving.getChatId()).orElse(null);
          if (session == null || !session.isAdvisedBy(leaving.getConsultantId())) {
            return null;
          }
          participantRepository.findBySeriesId(series.getId()).stream()
              .filter(member -> !leaving.getConsultantId().equals(member.getConsultantId()))
              .sorted(
                  Comparator.comparing(member -> member.getRole() == ParticipantRole.OWNER ? 0 : 1))
              .map(
                  member ->
                      consultantRepository.findByIdAndDeleteDateIsNull(member.getConsultantId()))
              .flatMap(java.util.Optional::stream)
              .findFirst()
              .ifPresent(
                  successor -> {
                    session.setConsultant(successor);
                    sessionRepository.save(session);
                  });
          return null;
        });
  }

  /**
   * A member who was added as supervisor of the session reads it through that relation, not through
   * the membership, so it ends together with the membership.
   */
  private void endSupervisionBy(GroupChatParticipant leaving) {
    supervisorRepository
        .findBySessionIdAndSupervisorConsultantIdAndIsActiveTrue(
            leaving.getChatId(), leaving.getConsultantId())
        .ifPresent(
            supervision -> {
              supervision.setIsActive(false);
              supervision.setRemovedDate(LocalDateTime.now());
              supervisorRepository.save(supervision);
            });
  }
}
