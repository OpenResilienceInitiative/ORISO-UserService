package de.caritas.cob.userservice.api.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ChatAgency;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant.ParticipantRole;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GroupChatConsultantAccessTest {

  private static final long AGENCY = 10L;
  private static final long OTHER_AGENCY = 20L;

  @Mock private GroupChatParticipantRepository participantRepository;

  private GroupChatConsultantAccess access(boolean multitenancy) {
    return new GroupChatConsultantAccess(participantRepository, multitenancy);
  }

  @Test
  void aPlainMemberOfTheGroupsOwnBeratungsstelleMayNotModerate() {
    var chat = chat(1L, owner(1L), AGENCY);
    var member = consultant("member", 1L, AGENCY);
    memberOf(1L, member, ParticipantRole.PARTICIPANT);

    assertThat(access(true).mayModerate(chat, member)).isFalse();
  }

  @Test
  void aCoModeratorMayModerate() {
    var chat = chat(1L, owner(1L), AGENCY);
    var coModerator = consultant("co", 1L, OTHER_AGENCY);
    memberOf(1L, coModerator, ParticipantRole.CO_MODERATOR);

    assertThat(access(true).mayModerate(chat, coModerator)).isTrue();
  }

  @Test
  void aSameTragerColleagueOfTheBeratungsstelleWhoIsNoMemberMayModerate() {
    var chat = chat(1L, owner(1L), AGENCY);
    var colleague = consultant("colleague", 1L, AGENCY);

    assertThat(access(true).mayModerate(chat, colleague)).isTrue();
  }

  @Test
  void withMultitenancyAMissingTenantOnEitherSideIsNeverTheSameTrager() {
    var colleagueWithoutTenant = consultant("colleague", null, AGENCY);
    var ownerWithoutTenant = chat(1L, owner(null), AGENCY);

    assertThat(access(true).mayAccess(chat(1L, owner(1L), AGENCY), colleagueWithoutTenant))
        .isFalse();
    assertThat(access(true).mayAccess(ownerWithoutTenant, consultant("c", 1L, AGENCY))).isFalse();
    assertThat(access(true).mayAccess(ownerWithoutTenant, colleagueWithoutTenant)).isFalse();
  }

  @Test
  void withoutMultitenancyTenantlessColleaguesOfTheBeratungsstelleKeepAccess() {
    var chat = chat(1L, owner(null), AGENCY);

    assertThat(access(false).mayAccess(chat, consultant("colleague", null, AGENCY))).isTrue();
  }

  @Test
  void aColleagueOfAnotherTragerSharingTheAgencyIdIsRefused() {
    var chat = chat(1L, owner(1L), AGENCY);

    assertThat(access(true).mayAccess(chat, consultant("foreign", 2L, AGENCY))).isFalse();
  }

  @Test
  void accessibleChatsKeepsOwnAndMemberGroupsAndDropsForeignOnesWithOneParticipantQuery() {
    var consultant = consultant("me", 1L, AGENCY);
    var sameBeratungsstelle = chat(1L, owner(1L), AGENCY);
    var foreignButMember = chat(2L, owner(2L), OTHER_AGENCY);
    var foreign = chat(3L, owner(2L), OTHER_AGENCY);
    when(participantRepository.findBySeriesIdInAndConsultantId(any(), eq("me")))
        .thenReturn(List.of(participant(2L, "me", ParticipantRole.PARTICIPANT)));

    var result =
        access(true)
            .accessibleChats(List.of(sameBeratungsstelle, foreignButMember, foreign), consultant);

    assertThat(result).containsExactly(sameBeratungsstelle, foreignButMember);
    verify(participantRepository, times(1)).findBySeriesIdInAndConsultantId(any(), eq("me"));
    verify(participantRepository, never()).findBySeriesIdAndConsultantId(any(), anyString());
  }

  @Test
  void accessibleChatsIsEmptyForNoConsultant() {
    assertThat(access(true).accessibleChats(List.of(chat(1L, owner(1L), AGENCY)), null)).isEmpty();
  }

  @Test
  void onceAGroupHasMembersOnlyOwnerOrCoModeratorMayStartStopOrBan() {
    var chat = chat(1L, owner(1L), AGENCY);
    var colleague = consultant("colleague", 1L, AGENCY);
    var coModerator = consultant("co", 1L, OTHER_AGENCY);
    when(participantRepository.findBySeriesId(1L))
        .thenReturn(
            List.of(
                participant(1L, "owner", ParticipantRole.OWNER),
                participant(1L, "co", ParticipantRole.CO_MODERATOR),
                participant(1L, "member", ParticipantRole.PARTICIPANT)));

    assertThat(access(true).mayStartStopOrBan(chat, coModerator)).isTrue();
    assertThat(access(true).mayStartStopOrBan(chat, colleague)).isFalse();
    assertThat(access(true).mayStartStopOrBan(chat, consultant("member", 1L, AGENCY))).isFalse();
  }

  @Test
  void aLegacyGroupWithoutMembersFallsBackToSameTragerColleaguesOfTheBeratungsstelle() {
    var chat = chat(1L, owner(1L), AGENCY);
    when(participantRepository.findBySeriesId(1L)).thenReturn(List.of());

    assertThat(access(true).mayStartStopOrBan(chat, consultant("colleague", 1L, AGENCY)))
        .isTrue();
    assertThat(access(true).mayStartStopOrBan(chat, consultant("foreign", 2L, AGENCY))).isFalse();
  }

  private void memberOf(Long seriesId, Consultant consultant, ParticipantRole role) {
    lenient()
        .when(participantRepository.findBySeriesIdAndConsultantId(seriesId, consultant.getId()))
        .thenReturn(Optional.of(participant(seriesId, consultant.getId(), role)));
  }

  private static GroupChatParticipant participant(
      Long seriesId, String consultantId, ParticipantRole role) {
    return GroupChatParticipant.builder()
        .seriesId(seriesId)
        .chatId(seriesId)
        .consultantId(consultantId)
        .role(role)
        .build();
  }

  private static Consultant owner(Long tenantId) {
    return consultant("owner", tenantId, AGENCY);
  }

  private static Consultant consultant(String id, Long tenantId, long agencyId) {
    var consultant = new Consultant();
    consultant.setId(id);
    consultant.setTenantId(tenantId);
    var consultantAgency = new ConsultantAgency();
    consultantAgency.setAgencyId(agencyId);
    consultant.setConsultantAgencies(new HashSet<>(Set.of(consultantAgency)));
    return consultant;
  }

  private static Chat chat(Long id, Consultant owner, long agencyId) {
    var chatAgency = new ChatAgency();
    chatAgency.setAgencyId(agencyId);
    var chat = new Chat();
    chat.setId(id);
    chat.setChatOwner(owner);
    chat.setChatAgencies(new HashSet<>(Set.of(chatAgency)));
    return chat;
  }
}
