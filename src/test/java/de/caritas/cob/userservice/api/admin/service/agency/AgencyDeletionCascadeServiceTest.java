package de.caritas.cob.userservice.api.admin.service.agency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDeletionFailureDTO.KindEnum;
import de.caritas.cob.userservice.api.admin.facade.AskerUserAdminFacade;
import de.caritas.cob.userservice.api.admin.facade.ConsultantAdminFacade;
import de.caritas.cob.userservice.api.admin.service.admin.AdminAgencyRelationService;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.admin.service.agency.AgencyGroupChatStopper.Stopped;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserAgencyRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.accountinvite.DeletedUnitInviteRevocation;
import de.caritas.cob.userservice.api.service.accountinvite.DeletedUnitInviteRevocation.Revoked;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgencyDeletionCascadeServiceTest {

  private static final long AGENCY = 7L;
  private static final long OTHER_AGENCY = 8L;
  private static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");
  private static final LocalDateTime NOW_UTC = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

  @Mock private AdminScope adminScope;
  @Mock private DeletedUnitInviteRevocation inviteRevocation;
  @Mock private SessionRepository sessionRepository;
  @Mock private UserRepository userRepository;
  @Mock private UserAgencyRepository userAgencyRepository;
  @Mock private AskerUserAdminFacade askerUserAdminFacade;
  @Mock private ConsultantAgencyRepository consultantAgencyRepository;
  @Mock private ConsultantAdminFacade consultantAdminFacade;
  @Mock private AdminAgencyRepository adminAgencyRepository;
  @Mock private AdminAgencyRelationService adminAgencyRelationService;
  @Mock private AgencyGroupChatStopper groupChatStopper;

  private AgencyDeletionCascadeService service;

  @BeforeEach
  void setUp() {
    service =
        new AgencyDeletionCascadeService(
            adminScope,
            inviteRevocation,
            sessionRepository,
            userRepository,
            userAgencyRepository,
            askerUserAdminFacade,
            consultantAgencyRepository,
            consultantAdminFacade,
            adminAgencyRepository,
            adminAgencyRelationService,
            groupChatStopper,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void cascade_Should_handleEveryChildOfTheAgency_inOrder() {
    when(inviteRevocation.revokeForAgency(AGENCY)).thenReturn(new Revoked(1, 1));
    when(sessionRepository.closeUnassignedEnquiriesOfAgency(AGENCY, NOW_UTC)).thenReturn(3);
    when(userRepository.findActiveAdviceSeekersOnlyInAgency(AGENCY))
        .thenReturn(List.of(asker("only-here")));
    when(sessionRepository.markSessionsOfKeptAdviceSeekersForDeletion(AGENCY, NOW_UTC))
        .thenReturn(2);
    when(consultantAgencyRepository.findByAgencyIdAndDeleteDateIsNull(AGENCY))
        .thenReturn(List.of(relation("solo", AGENCY), relation("shared", AGENCY)));
    when(consultantAgencyRepository.findByConsultantIdInAndDeleteDateIsNull(
            Set.of("solo", "shared")))
        .thenReturn(
            List.of(
                relation("solo", AGENCY),
                relation("shared", AGENCY),
                relation("shared", OTHER_AGENCY)));
    when(adminAgencyRepository.findAgencyAdminIdsByAgencyId(AGENCY)).thenReturn(List.of("aa1"));
    when(groupChatStopper.stopActiveChatsOf(AGENCY)).thenReturn(new Stopped(1, List.of()));

    var result = service.cascade(AGENCY);

    assertThat(result.getComplete()).isTrue();
    assertThat(result.getInvites()).isEqualTo(2L);
    assertThat(result.getOpenEnquiries()).isEqualTo(3L);
    assertThat(result.getAdviceSeekers()).isEqualTo(1L);
    assertThat(result.getKeptAdviceSeekerSessions()).isEqualTo(2L);
    assertThat(result.getConsultantsDeleted()).isEqualTo(1L);
    assertThat(result.getConsultantsUnassigned()).isEqualTo(1L);
    assertThat(result.getAgencyAdmins()).isEqualTo(1L);
    assertThat(result.getGroupChats()).isEqualTo(1L);
    assertThat(result.getFailures()).isEmpty();

    // Enquiries are closed and advice seekers marked before the counsellors go: the last
    // counsellor of a Beratungsstelle with open enquiries would otherwise be refused.
    var order =
        inOrder(
            adminScope,
            inviteRevocation,
            sessionRepository,
            askerUserAdminFacade,
            userAgencyRepository,
            consultantAdminFacade,
            adminAgencyRelationService,
            groupChatStopper);
    order.verify(adminScope).assertMay(any());
    order.verify(inviteRevocation).revokeForAgency(AGENCY);
    order.verify(sessionRepository).closeUnassignedEnquiriesOfAgency(AGENCY, NOW_UTC);
    order.verify(askerUserAdminFacade).markAskerForDeletion("only-here");
    order.verify(sessionRepository).markSessionsOfKeptAdviceSeekersForDeletion(AGENCY, NOW_UTC);
    order.verify(userAgencyRepository).deleteMembershipsOfKeptAdviceSeekers(AGENCY);
    order.verify(consultantAdminFacade).markConsultantForDeletion("solo", true);
    order.verify(consultantAdminFacade).markConsultantAgencyForDeletion("shared", AGENCY);
    order.verify(adminAgencyRelationService).deleteAdminAgencyRelation("aa1", AGENCY);
    order.verify(groupChatStopper).stopActiveChatsOf(AGENCY);

    // A counsellor who also works elsewhere keeps the account (AC 2).
    verify(consultantAdminFacade, never()).markConsultantForDeletion(eq("shared"), any());
  }

  @Test
  void cascade_Should_carryOnAndReportEachFailure() {
    when(inviteRevocation.revokeForAgency(AGENCY)).thenReturn(new Revoked(0, 0));
    when(userRepository.findActiveAdviceSeekersOnlyInAgency(AGENCY))
        .thenReturn(List.of(asker("u1"), asker("u2")));
    doThrow(new IllegalStateException("Keycloak down"))
        .when(askerUserAdminFacade)
        .markAskerForDeletion("u1");
    when(consultantAgencyRepository.findByAgencyIdAndDeleteDateIsNull(AGENCY))
        .thenReturn(List.of(relation("solo", AGENCY)));
    when(consultantAgencyRepository.findByConsultantIdInAndDeleteDateIsNull(Set.of("solo")))
        .thenReturn(List.of(relation("solo", AGENCY)));
    doThrow(new IllegalStateException("Matrix down"))
        .when(consultantAdminFacade)
        .markConsultantForDeletion("solo", true);
    when(groupChatStopper.stopActiveChatsOf(AGENCY)).thenReturn(new Stopped(0, List.of(42L)));

    var result = service.cascade(AGENCY);

    assertThat(result.getComplete()).isFalse();
    assertThat(result.getAdviceSeekers()).isEqualTo(1L);
    assertThat(result.getFailures())
        .extracting("kind", "id", "reason")
        .containsExactly(
            tuple(KindEnum.ADVICE_SEEKER, "u1", "IllegalStateException"),
            tuple(KindEnum.CONSULTANT, "solo", "IllegalStateException"),
            tuple(KindEnum.GROUP_CHAT, "42", "NotStopped"));
    verify(askerUserAdminFacade).markAskerForDeletion("u2");
    // u1's marker did not commit: its sessions and membership wait for the next call.
    verify(sessionRepository, never()).markSessionsOfKeptAdviceSeekersForDeletion(any(), any());
    verify(userAgencyRepository, never()).deleteMembershipsOfKeptAdviceSeekers(any());
    assertThat(result.getKeptAdviceSeekerSessions()).isZero();
  }

  @Test
  void preview_Should_lookUpTheCounsellorsRelationsInOneQuery() {
    when(consultantAgencyRepository.findByAgencyIdAndDeleteDateIsNull(AGENCY))
        .thenReturn(List.of(relation("solo", AGENCY), relation("shared", AGENCY)));
    when(consultantAgencyRepository.findByConsultantIdInAndDeleteDateIsNull(
            Set.of("solo", "shared")))
        .thenReturn(List.of(relation("solo", AGENCY), relation("shared", OTHER_AGENCY)));

    service.preview(AGENCY);

    verify(consultantAgencyRepository, never()).findByConsultantIdAndDeleteDateIsNull(any());
  }

  @Test
  void cascade_Should_touchNothing_When_theAgencyIsOutsideTheCallersReach() {
    doThrow(new ForbiddenException("not yours")).when(adminScope).assertMay(any());

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.cascade(AGENCY))
        .isInstanceOf(ForbiddenException.class);

    verifyNoInteractions(
        inviteRevocation,
        sessionRepository,
        askerUserAdminFacade,
        consultantAdminFacade,
        groupChatStopper);
  }

  @Test
  void preview_Should_countWhatGoesWithTheAgency() {
    when(userRepository.countActiveAdviceSeekersOnlyInAgency(AGENCY)).thenReturn(4L);
    when(sessionRepository.countSessionsOfKeptAdviceSeekers(AGENCY)).thenReturn(2L);
    when(sessionRepository.countUnassignedEnquiriesOfAgency(AGENCY)).thenReturn(3L);
    when(consultantAgencyRepository.findByAgencyIdAndDeleteDateIsNull(AGENCY))
        .thenReturn(List.of(relation("solo", AGENCY), relation("shared", AGENCY)));
    when(consultantAgencyRepository.findByConsultantIdInAndDeleteDateIsNull(
            Set.of("solo", "shared")))
        .thenReturn(
            List.of(
                relation("solo", AGENCY),
                relation("shared", AGENCY),
                relation("shared", OTHER_AGENCY)));
    when(adminAgencyRepository.findAgencyAdminIdsByAgencyId(AGENCY)).thenReturn(List.of("a", "b"));
    when(groupChatStopper.countActiveChatsOf(AGENCY)).thenReturn(1L);

    var preview = service.preview(AGENCY);

    assertThat(preview.getAdviceSeekers()).isEqualTo(4L);
    assertThat(preview.getKeptAdviceSeekerSessions()).isEqualTo(2L);
    assertThat(preview.getOpenEnquiries()).isEqualTo(3L);
    assertThat(preview.getConsultantsDeleted()).isEqualTo(1L);
    assertThat(preview.getConsultantsUnassigned()).isEqualTo(1L);
    assertThat(preview.getAgencyAdmins()).isEqualTo(2L);
    assertThat(preview.getGroupChats()).isEqualTo(1L);
    verify(adminScope).assertMay(any());
    verifyNoInteractions(askerUserAdminFacade, consultantAdminFacade, inviteRevocation);
  }

  private static User asker(String id) {
    var user = new User();
    user.setUserId(id);
    return user;
  }

  private static ConsultantAgency relation(String consultantId, long agencyId) {
    var consultant = new Consultant();
    consultant.setId(consultantId);
    var relation = new ConsultantAgency();
    relation.setConsultant(consultant);
    relation.setAgencyId(agencyId);
    return relation;
  }
}
