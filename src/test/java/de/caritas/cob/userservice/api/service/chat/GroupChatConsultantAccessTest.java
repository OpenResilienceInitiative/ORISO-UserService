package de.caritas.cob.userservice.api.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ChatAgency;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.ConversationType;
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
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class GroupChatConsultantAccessTest {

  @Mock
  private de.caritas.cob.userservice.api.service.chat.MembersOnlyChatAccess membersOnlyChatAccess;

  @Mock private GroupChatParticipantRepository participants;
  @Mock private InternalGroupChatRecipientScopeResolver scopes;
  @InjectMocks private GroupChatConsultantAccess access;

  @Test
  void filtersMultipleCrossTenantGroupsWithOneMembershipLookup() {
    var consultant = consultant("counselor", 1L, 100L);
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
    var sharedAgency = series(13L, consultant("same-tenant-owner", 1L, 100L), 100L);
    when(participants.findBySeriesIdInAndConsultantId(List.of(11L, 12L, 13L), "counselor"))
        .thenReturn(
            List.of(
                GroupChatParticipant.builder().seriesId(11L).consultantId("counselor").build()));

    assertThat(access.filterAccessible(List.of(admitted, denied, sharedAgency), consultant))
        .containsExactly(admitted, sharedAgency);

    verify(participants).findBySeriesIdInAndConsultantId(List.of(11L, 12L, 13L), "counselor");
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

  @Test
  void aPlainMemberOfTheGroupsOwnBeratungsstelleMayNotModerate() {
    var member = consultant("member", 1L, 100L);
    var series = series(31L, consultant("owner", 1L, 100L), 100L);
    when(participants.findBySeriesIdAndConsultantId(31L, "member"))
        .thenReturn(Optional.of(participant(31L, "member", ParticipantRole.PARTICIPANT)));

    assertThat(access.mayModerate(series, member)).isFalse();
  }

  @Test
  void aSameTragerColleagueOfTheBeratungsstelleWhoIsNoMemberMayModerate() {
    var colleague = consultant("colleague", 1L, 100L);
    var series = series(32L, consultant("owner", 1L, 100L), 100L);
    when(participants.findBySeriesIdAndConsultantId(32L, "colleague")).thenReturn(Optional.empty());

    assertThat(access.mayModerate(series, colleague)).isTrue();
  }

  @Test
  void withMultitenancyAMissingTenantOnEitherSideIsNeverTheSameTrager() {
    ReflectionTestUtils.setField(access, "multitenancy", true);
    var tenantless = consultant("tenantless", null, 100L);
    var tenantlessOwnerSeries = series(33L, consultant("owner", null, 100L), 100L);

    assertThat(access.mayAccess(series(34L, consultant("owner", 1L, 100L), 100L), tenantless))
        .isFalse();
    assertThat(access.mayAccess(tenantlessOwnerSeries, consultant("colleague", 1L, 100L)))
        .isFalse();
    assertThat(access.mayAccess(tenantlessOwnerSeries, tenantless)).isFalse();
    assertThat(access.filterAccessible(List.of(tenantlessOwnerSeries), tenantless)).isEmpty();
  }

  @Test
  void withoutMultitenancyTenantlessColleaguesOfTheBeratungsstelleKeepAccess() {
    ReflectionTestUtils.setField(access, "multitenancy", false);
    var series = series(35L, consultant("owner", null, 100L), 100L);

    assertThat(access.mayAccess(series, consultant("colleague", null, 100L))).isTrue();
  }

  @Test
  void onceAGroupHasMembersOnlyItsOwnerOrCoModeratorsMayStartStopOrBan() {
    var series = series(36L, consultant("owner", 1L, 100L), 100L);
    when(participants.findBySeriesId(36L))
        .thenReturn(
            List.of(
                participant(36L, "owner", ParticipantRole.OWNER),
                participant(36L, "co", ParticipantRole.CO_MODERATOR),
                participant(36L, "member", ParticipantRole.PARTICIPANT)));

    assertThat(access.mayStartStopOrBan(series, consultant("owner", 1L, 100L))).isTrue();
    assertThat(access.mayStartStopOrBan(series, consultant("co", 1L, 200L))).isTrue();
    assertThat(access.mayStartStopOrBan(series, consultant("member", 1L, 100L))).isFalse();
    assertThat(access.mayStartStopOrBan(series, consultant("colleague", 1L, 100L))).isFalse();
  }

  @Test
  void aLegacyGroupWithoutMembersFallsBackToSameTragerColleaguesOfTheBeratungsstelle() {
    var series = series(37L, consultant("owner", 1L, 100L), 100L);
    when(participants.findBySeriesId(37L)).thenReturn(List.of());

    assertThat(access.mayStartStopOrBan(series, consultant("colleague", 1L, 100L))).isTrue();
    assertThat(access.mayStartStopOrBan(series, consultant("foreign", 2L, 100L))).isFalse();
    assertThat(access.mayStartStopOrBan(series, consultant("elsewhere", 1L, 200L))).isFalse();
  }

  @Test
  void aMembersOnlyChatIsOpenToItsMembersAndToNobodyElseWhateverTheScope() {
    var owner = consultant("owner", 1L, 100L);
    var chat = series(41L, owner, 100L);
    chat.setMembersOnly(true);
    when(membersOnlyChatAccess.concerns(chat)).thenReturn(true);
    lenient().when(scopes.resolve(1L)).thenReturn(InternalGroupChatRecipientScope.PLATFORM);
    var member = consultant("member", 1L, 100L);
    var colleague = consultant("colleague", 1L, 100L);
    when(participants.findBySeriesIdAndConsultantId(41L, "member"))
        .thenReturn(Optional.of(participant(41L, "member", ParticipantRole.PARTICIPANT)));
    when(participants.findBySeriesIdAndConsultantId(41L, "colleague")).thenReturn(Optional.empty());
    when(participants.findBySeriesIdInAndConsultantId(List.of(41L), "colleague"))
        .thenReturn(List.of());
    when(participants.findBySeriesIdInAndConsultantId(List.of(41L), "member"))
        .thenReturn(List.of(participant(41L, "member", ParticipantRole.PARTICIPANT)));

    assertThat(access.mayAccess(chat, member)).isTrue();
    assertThat(access.mayAccess(chat, colleague)).isFalse();
    assertThat(access.mayModerate(chat, colleague)).isFalse();
    assertThat(access.filterAccessible(List.of(chat), colleague)).isEmpty();
    assertThat(access.filterAccessible(List.of(chat), member)).containsExactly(chat);
  }

  @Test
  void theTraegerScopeDecidesWhoMaySeeAndJoinAnOrdinaryInternalChat() {
    var owner = consultant("owner", 1L, 100L);
    var chat = series(42L, owner, 100L);
    var otherCentre = consultant("other-centre", 1L, 200L);
    var otherTraeger = consultant("other-traeger", 2L, 300L);
    when(participants.findBySeriesIdAndConsultantId(any(), any())).thenReturn(Optional.empty());

    when(scopes.resolve(1L)).thenReturn(InternalGroupChatRecipientScope.AGENCY);
    assertThat(access.mayAccess(chat, otherCentre)).isFalse();
    assertThat(access.mayAccess(chat, otherTraeger)).isFalse();

    when(scopes.resolve(1L)).thenReturn(InternalGroupChatRecipientScope.TENANT);
    assertThat(access.mayAccess(chat, otherCentre)).isTrue();
    assertThat(access.mayAccess(chat, otherTraeger)).isFalse();

    when(scopes.resolve(1L)).thenReturn(InternalGroupChatRecipientScope.PLATFORM);
    assertThat(access.mayAccess(chat, otherCentre)).isTrue();
    assertThat(access.mayAccess(chat, otherTraeger)).isTrue();
  }

  @Test
  void theRecipientScopeNeverWidensAConversationCircleOrAnyOtherClientFacingChat() {
    var owner = consultant("owner", 1L, 100L);
    var otherCentre = consultant("other-centre", 1L, 200L);
    var otherTraeger = consultant("other-traeger", 2L, 300L);
    var sameCentre = consultant("same-centre", 1L, 100L);
    lenient().when(scopes.resolve(1L)).thenReturn(InternalGroupChatRecipientScope.PLATFORM);
    when(participants.findBySeriesIdAndConsultantId(any(), any())).thenReturn(Optional.empty());
    when(participants.findBySeriesIdInAndConsultantId(any(), any())).thenReturn(List.of());

    for (var type :
        List.of(
            ConversationType.SELF_HELP,
            ConversationType.AGENCY_COUNSELLING,
            ConversationType.LIVE_CHAT)) {
      var circle = series(50L, owner, 100L);
      circle.setConversationType(type);
      assertThat(access.mayAccess(circle, otherCentre)).as("%s other centre", type).isFalse();
      assertThat(access.mayAccess(circle, otherTraeger)).as("%s other Traeger", type).isFalse();
      assertThat(access.filterAccessible(List.of(circle), otherTraeger))
          .as("%s list", type)
          .isEmpty();
      assertThat(access.mayAccess(circle, sameCentre)).as("%s own centre", type).isTrue();
    }
    // A legacy circle without a stored type is recognised by its repetition.
    var legacyCircle = series(51L, owner, 100L);
    legacyCircle.setRepetitive(true);
    assertThat(access.mayAccess(legacyCircle, otherTraeger)).isFalse();
    assertThat(access.mayAccess(legacyCircle, sameCentre)).isTrue();
    // The internal chat itself is widened by the same scope.
    var internal = series(52L, owner, 100L);
    internal.setConversationType(ConversationType.INTERNAL_GROUP);
    assertThat(access.mayAccess(internal, otherTraeger)).isTrue();
  }

  private static GroupChatParticipant participant(
      Long seriesId, String consultantId, ParticipantRole role) {
    return GroupChatParticipant.builder()
        .seriesId(seriesId)
        .consultantId(consultantId)
        .role(role)
        .build();
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
