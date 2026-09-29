package de.caritas.cob.userservice.api.service.chat;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ChatAgency;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant.ParticipantRole;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GroupChatPermissionServiceTest {

  @Mock private GroupChatParticipantRepository participantRepository;

  private GroupChatPermissionService service;

  @BeforeEach
  void setUp() {
    service = new GroupChatPermissionService(new GroupChatConsultantAccess(participantRepository));
  }

  @Test
  void ownerAndCoModeratorCanOpenOrEndAnOccurrence() {
    var chat = chat(consultant("owner", 1L));
    for (ParticipantRole role : List.of(ParticipantRole.OWNER, ParticipantRole.CO_MODERATOR)) {
      when(participantRepository.findBySeriesId(42L))
          .thenReturn(List.of(participant("actor", role)));

      assertThatCode(() -> service.requireCanModerate(chat, consultant("actor", 1L)))
          .doesNotThrowAnyException();
    }
  }

  @Test
  void ordinaryParticipantCannotOpenOrEndAnOccurrenceEvenWithinAgency() {
    var chat = chat(consultant("owner", 1L));
    when(participantRepository.findBySeriesId(42L))
        .thenReturn(List.of(participant("actor", ParticipantRole.PARTICIPANT)));

    assertThatThrownBy(() -> service.requireCanModerate(chat, consultant("actor", 1L)))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void legacyChatWithoutSeriesMembershipKeepsAgencyAuthorization() {
    var chat = chat(consultant("owner", 1L));
    when(participantRepository.findBySeriesId(42L)).thenReturn(List.of());

    assertThatCode(() -> service.requireCanModerate(chat, consultant("colleague", 1L)))
        .doesNotThrowAnyException();
  }

  @Test
  void legacyChatRefusesACounsellorOfAnotherTragerSharingTheAgencyId() {
    var chat = chat(consultant("owner", 1L));
    when(participantRepository.findBySeriesId(42L)).thenReturn(List.of());

    assertThatThrownBy(() -> service.requireCanModerate(chat, consultant("foreign", 2L)))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void missingChatOrConsultantIsRefused() {
    assertThatThrownBy(() -> service.requireCanModerate(null, consultant("actor", 1L)))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> service.requireCanModerate(chat(consultant("owner", 1L)), null))
        .isInstanceOf(ForbiddenException.class);
  }

  private static GroupChatParticipant participant(String id, ParticipantRole role) {
    return GroupChatParticipant.builder().seriesId(42L).consultantId(id).role(role).build();
  }

  private static Consultant consultant(String id, Long tenantId) {
    var consultant = new Consultant();
    consultant.setId(id);
    consultant.setTenantId(tenantId);
    var agency = new ConsultantAgency();
    agency.setAgencyId(100L);
    consultant.setConsultantAgencies(Set.of(agency));
    return consultant;
  }

  private static Chat chat(Consultant owner) {
    var chat = new Chat();
    chat.setId(42L);
    chat.setChatOwner(owner);
    var agency = new ChatAgency();
    agency.setAgencyId(100L);
    chat.setChatAgencies(Set.of(agency));
    return chat;
  }
}
