package de.caritas.cob.userservice.api.service.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ChatAgency;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.MatrixCallBinding;
import de.caritas.cob.userservice.api.port.out.ChatAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
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
  private final ChatRepository chats = mock(ChatRepository.class);
  private final GroupCallCreatorAgency creatorAgency =
      new GroupCallCreatorAgency(consultants, chatAgencies, chats, agencyService);

  @BeforeEach
  void setUp() {
    groupAgencies(99L, 101L);
    creatorIn(TENANT, agency(99L), agency(50L));
    when(chats.findById(CHAT)).thenReturn(Optional.empty());
  }

  @Test
  void theCreatorsOneAgencyWithinTheGroup() {
    assertThat(creatorAgency.of(groupCall())).contains(99L);
  }

  @Test
  void noneWhenTheCreatorServesTwoAgenciesOfTheGroupAndTheGroupHasNoOwner() {
    // Which consent text applies would be a guess: fail closed (only existing accounts).
    creatorIn(TENANT, agency(99L), agency(101L));
    assertThat(creatorAgency.of(groupCall())).isEmpty();
  }

  @Test
  void noneWhenTheCreatorServesNoAgencyOfTheGroupAndTheGroupHasNoOwner() {
    creatorIn(TENANT, agency(50L));
    assertThat(creatorAgency.of(groupCall())).isEmpty();
  }

  // Frank, 17 Nov: a creator with several or none of the group's Beratungsstellen hands over to
  // the group chat's owner.
  @Test
  void theOwnersAgencyWhenTheCreatorServesTwoAgenciesOfTheGroup() {
    creatorIn(TENANT, agency(99L), agency(101L));
    ownedBy(owner(TENANT, agency(101L), agency(50L)));
    assertThat(creatorAgency.of(groupCall())).contains(101L);
  }

  @Test
  void theOwnersAgencyWhenTheCreatorServesNoAgencyOfTheGroup() {
    creatorIn(TENANT, agency(50L));
    ownedBy(owner(TENANT, agency(99L)));
    assertThat(creatorAgency.of(groupCall())).contains(99L);
  }

  @Test
  void theCreatorsOwnAgencyStillComesBeforeTheOwners() {
    ownedBy(owner(TENANT, agency(101L)));
    assertThat(creatorAgency.of(groupCall())).contains(99L);
  }

  @Test
  void anOwnerOfSeveralGroupAgenciesGivesTheLowestIdSoTheLinkStaysStable() {
    creatorIn(TENANT, agency(50L));
    ownedBy(owner(TENANT, agency(101L), agency(99L)));
    assertThat(creatorAgency.of(groupCall())).contains(99L);
  }

  @Test
  void noneWhenTheOwnerServesNoAgencyOfTheGroupEither() {
    creatorIn(TENANT, agency(50L));
    ownedBy(owner(TENANT, agency(51L)));
    assertThat(creatorAgency.of(groupCall())).isEmpty();
  }

  @Test
  void anOwnerWhoLeftTheAgencyOrWasDeletedOrBelongsElsewhereDoesNotCount() {
    creatorIn(TENANT, agency(50L));
    var left = agency(99L);
    left.setDeleteDate(LocalDateTime.now());
    ownedBy(owner(TENANT, left));
    assertThat(creatorAgency.of(groupCall())).isEmpty();

    var deleted = owner(TENANT, agency(99L));
    deleted.setDeleteDate(LocalDateTime.now());
    ownedBy(deleted);
    assertThat(creatorAgency.of(groupCall())).isEmpty();

    ownedBy(owner(8L, agency(99L)));
    assertThat(creatorAgency.of(groupCall())).isEmpty();
  }

  @Test
  void noOwnerFallbackForACallAnAdviceSeekerStarted() {
    when(consultants.findAllByMatrixUserIdAndDeleteDateIsNull(CREATOR)).thenReturn(List.of());
    ownedBy(owner(TENANT, agency(99L)));
    assertThat(creatorAgency.of(groupCall())).isEmpty();
  }

  @Test
  void noOwnerFallbackWhenTheCreatorsMatrixIdStandsForTwoAccounts() {
    var first = consultant(TENANT, agency(50L));
    var twin = consultant(TENANT, agency(50L));
    twin.setId("twin");
    when(consultants.findAllByMatrixUserIdAndDeleteDateIsNull(CREATOR))
        .thenReturn(List.of(first, twin));
    ownedBy(owner(TENANT, agency(99L)));
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
  void noConsultingTypeWhenTheAgencyIsUnknown() {
    when(agencyService.getAgency(98L)).thenReturn(null);
    when(agencyService.getAgency(96L)).thenReturn(new AgencyDTO().id(96L));
    assertThat(creatorAgency.consultingTypeOf(98L)).isEmpty();
    assertThat(creatorAgency.consultingTypeOf(96L)).isEmpty();
    assertThat(creatorAgency.consultingTypeOf(null)).isEmpty();
  }

  @Test
  void aFailedAgencyLookupIsNotMistakenForAnAgencyWithoutConsultingType() {
    // #1662: an empty answer would be stored on the call's link and refuse every registration for
    // the rest of the call; the caller must see the outage instead.
    when(agencyService.getAgency(97L)).thenThrow(new IllegalStateException("down"));
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> creatorAgency.consultingTypeOf(97L))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException
                .class);
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

  private void ownedBy(Consultant owner) {
    var chat = new Chat();
    chat.setId(CHAT);
    chat.setChatOwner(owner);
    when(chats.findById(CHAT)).thenReturn(Optional.of(chat));
  }

  private static Consultant owner(long tenant, ConsultantAgency... memberships) {
    var owner = consultant(tenant, memberships);
    owner.setId("owner");
    owner.setMatrixUserId("@owner:example");
    return owner;
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
