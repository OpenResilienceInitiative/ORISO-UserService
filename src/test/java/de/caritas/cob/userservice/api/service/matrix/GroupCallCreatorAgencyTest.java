package de.caritas.cob.userservice.api.service.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.model.ChatAgency;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.MatrixCallBinding;
import de.caritas.cob.userservice.api.port.out.ChatAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** #1662: the Beratungsstelle a group call's guests register under. */
class GroupCallCreatorAgencyTest {
  private static final long TENANT = 7L;
  private static final long CHAT = 600L;
  private static final String CREATOR = "@creator:example";
  private static final java.util.concurrent.atomic.AtomicLong NEXT_ID =
      new java.util.concurrent.atomic.AtomicLong();

  private final ConsultantRepository consultants = mock(ConsultantRepository.class);
  private final ChatAgencyRepository chatAgencies = mock(ChatAgencyRepository.class);
  private final AgencyService agencyService = mock(AgencyService.class);
  private final GroupCallCreatorAgency creatorAgency =
      new GroupCallCreatorAgency(consultants, chatAgencies, agencyService);

  @BeforeEach
  void setUp() {
    groupAgencies(99L, 101L);
    creatorIn(TENANT, agency(99L), agency(50L));
  }

  @Test
  void theCreatorsOneAgencyWithinTheGroup() {
    assertThat(creatorAgency.of(groupCall())).contains(99L);
  }

  @Test
  void noneWhenTheCreatorServesTwoAgenciesOfTheGroup() {
    // Which consent text applies would be a guess: fail closed (only existing accounts).
    creatorIn(TENANT, agency(99L), agency(101L));
    assertThat(creatorAgency.of(groupCall())).isEmpty();
  }

  @Test
  void noneWhenTheCreatorServesNoAgencyOfTheGroup() {
    creatorIn(TENANT, agency(50L));
    assertThat(creatorAgency.of(groupCall())).isEmpty();
  }

  @Test
  void aMembershipTheCreatorLeftDoesNotCount() {
    var left = agency(99L);
    left.setDeleteDate(LocalDateTime.now());
    var stays = agency(50L);
    creatorIn(TENANT, left, stays);
    assertThat(creatorAgency.of(groupCall())).isEmpty();
  }

  @Test
  void noneWhenTheCallWasStartedByAnAdviceSeeker() {
    when(consultants.findAllByMatrixUserIdAndDeleteDateIsNull(CREATOR)).thenReturn(List.of());
    assertThat(creatorAgency.of(groupCall())).isEmpty();
  }

  @Test
  void noneForACreatorOfAnotherTraeger() {
    creatorIn(8L, agency(99L));
    assertThat(creatorAgency.of(groupCall())).isEmpty();
  }

  @Test
  void noneWhenTheCreatorsMatrixIdStandsForTwoAccountsOfTheTraeger() {
    var first = consultant(TENANT, agency(99L));
    var twin = consultant(TENANT, agency(99L));
    twin.setId("twin");
    when(consultants.findAllByMatrixUserIdAndDeleteDateIsNull(CREATOR))
        .thenReturn(List.of(first, twin));
    assertThat(creatorAgency.of(groupCall())).isEmpty();
  }

  @Test
  void noneForA1to1SessionCall() {
    var call =
        MatrixCallBinding.builder()
            .id(4L)
            .sourceRoomId("!session:example")
            .callId("call")
            .mediaRoomId("!media:example")
            .callerMatrixId(CREATOR)
            .sessionId(16L)
            .tenantId(TENANT)
            .build();
    assertThat(creatorAgency.of(call)).isEmpty();
  }

  @Test
  void theConsultingTypeComesFromTheAgency() {
    when(agencyService.getAgency(99L)).thenReturn(new AgencyDTO().id(99L).consultingType(3));
    assertThat(creatorAgency.consultingTypeOf(99L)).contains(3);
  }

  @Test
  void noConsultingTypeWhenTheAgencyIsUnknownOrUnavailable() {
    when(agencyService.getAgency(98L)).thenReturn(null);
    when(agencyService.getAgency(97L)).thenThrow(new IllegalStateException("down"));
    when(agencyService.getAgency(96L)).thenReturn(new AgencyDTO().id(96L));
    assertThat(creatorAgency.consultingTypeOf(98L)).isEmpty();
    assertThat(creatorAgency.consultingTypeOf(97L)).isEmpty();
    assertThat(creatorAgency.consultingTypeOf(96L)).isEmpty();
    assertThat(creatorAgency.consultingTypeOf(null)).isEmpty();
  }

  private void groupAgencies(Long... ids) {
    when(chatAgencies.findByChat_Id(CHAT))
        .thenReturn(
            Arrays.stream(ids).map(id -> ChatAgency.builder().agencyId(id).build()).toList());
  }

  private void creatorIn(long tenant, ConsultantAgency... memberships) {
    var creator = consultant(tenant, memberships);
    when(consultants.findAllByMatrixUserIdAndDeleteDateIsNull(any())).thenReturn(List.of(creator));
  }

  private static Consultant consultant(long tenant, ConsultantAgency... memberships) {
    var consultant = new Consultant();
    consultant.setId("creator");
    consultant.setTenantId(tenant);
    consultant.setMatrixUserId(CREATOR);
    consultant.setConsultantAgencies(Arrays.stream(memberships).collect(Collectors.toSet()));
    return consultant;
  }

  private static ConsultantAgency agency(long id) {
    return ConsultantAgency.builder()
        .id(NEXT_ID.incrementAndGet())
        .agencyId(id)
        .tenantId(TENANT)
        .build();
  }

  private static MatrixCallBinding groupCall() {
    return MatrixCallBinding.builder()
        .id(3L)
        .sourceRoomId("!group:example")
        .callId("call")
        .mediaRoomId("!media:example")
        .callerMatrixId(CREATOR)
        .chatId(CHAT)
        .tenantId(TENANT)
        .build();
  }
}
