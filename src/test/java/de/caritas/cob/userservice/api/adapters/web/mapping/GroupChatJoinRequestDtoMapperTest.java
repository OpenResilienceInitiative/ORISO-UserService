package de.caritas.cob.userservice.api.adapters.web.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.helper.ChatPermissionVerifier;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.GroupChatJoinRequest;
import de.caritas.cob.userservice.api.model.GroupChatJoinRequest.Status;
import de.caritas.cob.userservice.api.model.GroupChatParticipant.ParticipantRole;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.chat.GroupChatJoinRequestService;
import de.caritas.cob.userservice.api.service.chat.GroupChatJoinRequestService.PendingForModerator;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GroupChatJoinRequestDtoMapperTest {

  @Mock private ConsultantRepository consultants;
  @Mock private AgencyService agencies;
  @Mock private TenantService tenants;
  @Mock private ChatPermissionVerifier permissions;
  @Mock private GroupChatJoinRequestService requests;
  @InjectMocks private GroupChatJoinRequestDtoMapper mapper;

  @Test
  void resolvesSharedRequesterNamesOnceForTheModeratorList() {
    var first = consultant("first");
    var second = consultant("second");
    var now = LocalDateTime.now();
    var series =
        Chat.builder()
            .id(11L)
            .topic("Self-help")
            .initialStartDate(now)
            .startDate(now)
            .chatOwner(first)
            .build();
    when(requests.findPendingForModerator("moderator"))
        .thenReturn(List.of(pending(1L, first, series, now), pending(2L, second, series, now)));
    when(consultants.findAllWithAgenciesByIdIn(List.of("first", "second")))
        .thenReturn(List.of(first, second));
    when(tenants.getRestrictedTenantData(Set.of(7L)))
        .thenReturn(List.of(new RestrictedTenantDTO().id(7L).name("Tenant")));
    when(agencies.getAgency(100L)).thenReturn(new AgencyDTO().id(100L).name("Agency"));

    var mapped = mapper.pendingRequestsFor("moderator");

    assertThat(mapped).hasSize(2);
    assertThat(mapped)
        .allSatisfy(
            item -> {
              assertThat(item.getRequester().getAgencyName().get()).isEqualTo("Agency");
              assertThat(item.getRequester().getTenantName().get()).isEqualTo("Tenant");
            });
    verify(agencies).getAgency(100L);
    verify(tenants).getRestrictedTenantData(Set.of(7L));
    verify(consultants, never()).findById("first");
    verify(consultants, never()).findById("second");
  }

  private static Consultant consultant(String id) {
    var consultant =
        Consultant.builder()
            .id(id)
            .username(id)
            .firstName(id)
            .lastName("Counselor")
            .email(id + "@example.org")
            .tenantId(7L)
            .build();
    consultant.setConsultantAgencies(
        Set.of(ConsultantAgency.builder().consultant(consultant).agencyId(100L).build()));
    return consultant;
  }

  private static PendingForModerator pending(
      long requestId, Consultant requester, Chat series, LocalDateTime now) {
    var request =
        GroupChatJoinRequest.builder()
            .id(requestId)
            .seriesId(series.getId())
            .consultantId(requester.getId())
            .status(Status.PENDING)
            .requestedAt(now)
            .build();
    return new PendingForModerator(request, series, ParticipantRole.OWNER);
  }
}
