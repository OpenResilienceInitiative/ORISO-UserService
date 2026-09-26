package de.caritas.cob.userservice.api.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import java.time.LocalDateTime;
import java.util.List;
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
}
