package de.caritas.cob.userservice.api.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ChatAgency;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant.ParticipantRole;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GroupChatConsultantAccessTest {

  @Mock private GroupChatParticipantRepository participants;
  @InjectMocks private GroupChatConsultantAccess access;

  @Test
  void filtersMultipleCrossTenantGroupsWithOneMembershipLookup() {
    var consultant =
        Consultant.builder()
            .id("counselor")
            .username("counselor")
            .firstName("Counselor")
            .lastName("One")
            .email("counselor@example.org")
            .tenantId(1L)
            .build();
    var otherTenantOwner =
        Consultant.builder()
            .id("owner")
            .username("owner")
            .firstName("Owner")
            .lastName("Two")
            .email("owner@example.org")
            .tenantId(2L)
            .build();
    var start = LocalDateTime.now();
    var admitted =
        Chat.builder()
            .id(11L)
            .topic("admitted")
            .initialStartDate(start)
            .startDate(start)
            .chatOwner(otherTenantOwner)
            .build();
    var denied =
        Chat.builder()
            .id(12L)
            .topic("denied")
            .initialStartDate(start)
            .startDate(start)
            .chatOwner(otherTenantOwner)
            .build();
    when(participants.findBySeriesIdInAndConsultantId(List.of(11L, 12L), "counselor"))
        .thenReturn(
            List.of(
                GroupChatParticipant.builder().seriesId(11L).consultantId("counselor").build()));

    assertThat(access.filterAccessible(List.of(admitted, denied), consultant))
        .containsExactly(admitted);

    verify(participants).findBySeriesIdInAndConsultantId(List.of(11L, 12L), "counselor");
    verify(participants, never()).findBySeriesIdAndConsultantId(any(), any());
  }

  @Test
  void sharedAgencyDoesNotGrantAccessAcrossTenants() {
    var consultant = consultant("counselor", 1L, 100L);
    var series = series(21L, consultant("owner", 2L, 100L), 100L);
    when(participants.findBySeriesIdAndConsultantId(21L, "counselor")).thenReturn(Optional.empty());

    assertThat(access.mayAccess(series, consultant)).isFalse();
    assertThat(access.mayModerate(series, consultant)).isFalse();
  }

  @Test
  void crossTenantParticipantMayReadButNotModerate() {
    var consultant = consultant("counselor", 1L, 200L);
    var series = series(22L, consultant("owner", 2L, 100L), 100L);
    when(participants.findBySeriesIdAndConsultantId(22L, "counselor"))
        .thenReturn(
            Optional.of(
                GroupChatParticipant.builder()
                    .seriesId(22L)
                    .consultantId("counselor")
                    .role(ParticipantRole.PARTICIPANT)
                    .build()));

    assertThat(access.mayAccess(series, consultant)).isTrue();
    assertThat(access.mayModerate(series, consultant)).isFalse();
  }

  @Test
  void crossAgencyCoModeratorMayModerate() {
    var consultant = consultant("counselor", 1L, 200L);
    var series = series(23L, consultant("owner", 2L, 100L), 100L);
    when(participants.findBySeriesIdAndConsultantId(23L, "counselor"))
        .thenReturn(
            Optional.of(
                GroupChatParticipant.builder()
                    .seriesId(23L)
                    .consultantId("counselor")
                    .role(ParticipantRole.CO_MODERATOR)
                    .build()));

    assertThat(access.mayModerate(series, consultant)).isTrue();
  }

  private static Consultant consultant(String id, Long tenantId, Long agencyId) {
    var consultant =
        Consultant.builder()
            .id(id)
            .username(id)
            .firstName(id)
            .lastName("Counselor")
            .email(id + "@example.org")
            .tenantId(tenantId)
            .build();
    consultant.setConsultantAgencies(
        Set.of(ConsultantAgency.builder().consultant(consultant).agencyId(agencyId).build()));
    return consultant;
  }

  private static Chat series(Long id, Consultant owner, Long agencyId) {
    var start = LocalDateTime.now();
    var chat =
        Chat.builder()
            .id(id)
            .topic("Self-help")
            .initialStartDate(start)
            .startDate(start)
            .chatOwner(owner)
            .build();
    chat.setChatAgencies(Set.of(ChatAgency.builder().chat(chat).agencyId(agencyId).build()));
    return chat;
  }
}
