package de.caritas.cob.userservice.api.admin.service.agency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDeletionFailureDTO.KindEnum;
import de.caritas.cob.userservice.api.admin.facade.AskerUserAdminFacade;
import de.caritas.cob.userservice.api.admin.facade.ConsultantAdminFacade;
import de.caritas.cob.userservice.api.admin.service.admin.AdminAgencyRelationService;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.admin.service.agency.AgencyAdviceSeekerUnlinker.Kept;
import de.caritas.cob.userservice.api.admin.service.agency.AgencyAdviceSeekerUnlinker.OnlyHere;
import de.caritas.cob.userservice.api.admin.service.agency.AgencyGroupChatDeleter.Deleted;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.model.ConsultantAgency.ConsultantAgencyBase;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.accountinvite.DeletedUnitInviteRevocation;
import de.caritas.cob.userservice.api.service.accountinvite.DeletedUnitInviteRevocation.Revoked;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
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
  @Mock private AgencyAdviceSeekerUnlinker adviceSeekerUnlinker;
  @Mock private AskerUserAdminFacade askerUserAdminFacade;
  @Mock private ConsultantAgencyRepository consultantAgencyRepository;
  @Mock private ConsultantAdminFacade consultantAdminFacade;
  @Mock private AdminAgencyRepository adminAgencyRepository;
  @Mock private AdminAgencyRelationService adminAgencyRelationService;
  @Mock private AgencyGroupChatDeleter groupChatDeleter;

  private AgencyDeletionCascadeService service;

  @BeforeEach
  void setUp() {
    service =
        new AgencyDeletionCascadeService(
            adminScope,
            inviteRevocation,
            sessionRepository,
            userRepository,
            adviceSeekerUnlinker,
            askerUserAdminFacade,
            consultantAgencyRepository,
            consultantAdminFacade,
            adminAgencyRepository,
            adminAgencyRelationService,
            groupChatDeleter,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void cascade_Should_handleEveryChildOfTheAgency_inOrder() {
    when(inviteRevocation.revokeForAgency(AGENCY)).thenReturn(new Revoked(1, 1));
    when(sessionRepository.closeUnassignedEnquiriesOfAgency(AGENCY, NOW_UTC)).thenReturn(3);
    when(userRepository.findActiveAdviceSeekerIdsLinkedToAgency(AGENCY))
        .thenReturn(List.of("only-here", "also-elsewhere"));
    when(adviceSeekerUnlinker.unlink("only-here", AGENCY, NOW_UTC)).thenReturn(new OnlyHere());
    when(adviceSeekerUnlinker.unlink("also-elsewhere", AGENCY, NOW_UTC)).thenReturn(new Kept(2));
    when(consultantAgencyRepository.findActiveConsultantIdsByAgencyId(AGENCY))
        .thenReturn(List.of("solo", "shared"));
    when(consultantAgencyRepository.findByConsultantIdInAndDeleteDateIsNull(
            List.of("solo", "shared")))
        .thenReturn(
            List.of(base("solo", AGENCY), base("shared", AGENCY), base("shared", OTHER_AGENCY)));
    when(adminAgencyRepository.findAgencyAdminIdsByAgencyId(AGENCY)).thenReturn(List.of("aa1"));
    when(groupChatDeleter.deleteChatsOf(AGENCY)).thenReturn(new Deleted(1, List.of()));

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
            adviceSeekerUnlinker,
            askerUserAdminFacade,
            consultantAdminFacade,
            adminAgencyRelationService,
            groupChatDeleter);
    order.verify(adminScope).assertMay(any());
    order.verify(inviteRevocation).revokeForAgency(AGENCY);
    order.verify(sessionRepository).closeUnassignedEnquiriesOfAgency(AGENCY, NOW_UTC);
    order.verify(adviceSeekerUnlinker).unlink("only-here", AGENCY, NOW_UTC);
    order.verify(askerUserAdminFacade).markAskerForDeletion("only-here");
    order.verify(adviceSeekerUnlinker).unlink("also-elsewhere", AGENCY, NOW_UTC);
    order.verify(consultantAdminFacade).markConsultantForDeletion("solo", true);
    order.verify(consultantAdminFacade).markConsultantAgencyForDeletion("shared", AGENCY);
    order.verify(adminAgencyRelationService).deleteAdminAgencyRelation("aa1", AGENCY);
    order.verify(groupChatDeleter).deleteChatsOf(AGENCY);

    // Advice seekers and counsellors who also belong elsewhere keep their account (AC 2).
    verify(askerUserAdminFacade, never()).markAskerForDeletion("also-elsewhere");
    verify(consultantAdminFacade, never()).markConsultantForDeletion(eq("shared"), any());
  }

  @Test
  void cascade_Should_findAnAdviceSeekerAgain_When_markingTheirAccountFailed() {
    when(inviteRevocation.revokeForAgency(AGENCY)).thenReturn(new Revoked(0, 0));
    // The other Beratungsstelle, deleted at the same time, already took its link: the advice
    // seeker belongs only here, and this link is not taken while their account is not marked.
    when(userRepository.findActiveAdviceSeekerIdsLinkedToAgency(AGENCY))
        .thenReturn(List.of("shared"));
    when(adviceSeekerUnlinker.unlink("shared", AGENCY, NOW_UTC)).thenReturn(new OnlyHere());
    doThrow(new IllegalStateException("Keycloak down"))
        .doNothing()
        .when(askerUserAdminFacade)
        .markAskerForDeletion("shared");
    when(groupChatDeleter.deleteChatsOf(AGENCY)).thenReturn(new Deleted(0, List.of()));

    var first = service.cascade(AGENCY);

    assertThat(first.getComplete()).isFalse();
    assertThat(first.getFailures())
        .extracting("kind", "id")
        .containsExactly(tuple(KindEnum.ADVICE_SEEKER, "shared"));

    var retry = service.cascade(AGENCY);

    assertThat(retry.getComplete()).isTrue();
    assertThat(retry.getAdviceSeekers()).isEqualTo(1L);
    verify(askerUserAdminFacade, times(2)).markAskerForDeletion("shared");
  }

  @Test
  void cascade_Should_reportTheAdviceSeekers_When_theyCannotBeListed() {
    when(inviteRevocation.revokeForAgency(AGENCY)).thenReturn(new Revoked(0, 0));
    when(userRepository.findActiveAdviceSeekerIdsLinkedToAgency(AGENCY))
        .thenThrow(new IllegalStateException("db"));
    when(groupChatDeleter.deleteChatsOf(AGENCY)).thenReturn(new Deleted(0, List.of()));

    var result = service.cascade(AGENCY);

    assertThat(result.getComplete()).isFalse();
    assertThat(result.getFailures())
        .extracting("kind", "id", "reason")
        .containsExactly(tuple(KindEnum.ADVICE_SEEKER, null, "IllegalStateException"));
    verifyNoInteractions(adviceSeekerUnlinker);
  }

  @Test
  void cascade_Should_carryOnAndReportEachFailure() {
    when(inviteRevocation.revokeForAgency(AGENCY)).thenReturn(new Revoked(0, 0));
    when(userRepository.findActiveAdviceSeekerIdsLinkedToAgency(AGENCY))
        .thenReturn(List.of("u1", "u2", "u3"));
    when(adviceSeekerUnlinker.unlink(any(), eq(AGENCY), eq(NOW_UTC))).thenReturn(new OnlyHere());
    when(adviceSeekerUnlinker.unlink("u3", AGENCY, NOW_UTC))
        .thenThrow(new IllegalStateException("lock timeout"));
    doThrow(new IllegalStateException("Keycloak down"))
        .when(askerUserAdminFacade)
        .markAskerForDeletion("u1");
    when(consultantAgencyRepository.findActiveConsultantIdsByAgencyId(AGENCY))
        .thenReturn(List.of("solo"));
    when(consultantAgencyRepository.findByConsultantIdInAndDeleteDateIsNull(List.of("solo")))
        .thenReturn(List.of(base("solo", AGENCY)));
    doThrow(new IllegalStateException("Matrix down"))
        .when(consultantAdminFacade)
        .markConsultantForDeletion("solo", true);
    when(groupChatDeleter.deleteChatsOf(AGENCY)).thenReturn(new Deleted(0, List.of(42L)));

    var result = service.cascade(AGENCY);

    assertThat(result.getComplete()).isFalse();
    assertThat(result.getAdviceSeekers()).isEqualTo(1L);
    assertThat(result.getKeptAdviceSeekerSessions()).isZero();
    assertThat(result.getFailures())
        .extracting("kind", "id", "reason")
        .containsExactly(
            tuple(KindEnum.ADVICE_SEEKER, "u1", "IllegalStateException"),
            tuple(KindEnum.ADVICE_SEEKER, "u3", "IllegalStateException"),
            tuple(KindEnum.CONSULTANT, "solo", "IllegalStateException"),
            tuple(KindEnum.GROUP_CHAT, "42", "NotDeleted"));
    verify(askerUserAdminFacade).markAskerForDeletion("u2");
  }

  @Test
  void preview_Should_lookUpTheCounsellorsRelationsInOneQuery() {
    when(consultantAgencyRepository.findActiveConsultantIdsByAgencyId(AGENCY))
        .thenReturn(List.of("solo", "shared"));
    when(consultantAgencyRepository.findByConsultantIdInAndDeleteDateIsNull(
            List.of("solo", "shared")))
        .thenReturn(List.of(base("solo", AGENCY), base("shared", OTHER_AGENCY)));

    service.preview(AGENCY);

    verify(consultantAgencyRepository, never()).findByConsultantIdAndDeleteDateIsNull(any());
    verify(consultantAgencyRepository, never()).findByAgencyIdAndDeleteDateIsNull(any());
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
        groupChatDeleter);
  }

  @Test
  void preview_Should_countWhatGoesWithTheAgency() {
    when(userRepository.countActiveAdviceSeekersOnlyInAgency(AGENCY)).thenReturn(4L);
    when(sessionRepository.countSessionsOfKeptAdviceSeekers(AGENCY)).thenReturn(2L);
    when(sessionRepository.countUnassignedEnquiriesOfAgency(AGENCY)).thenReturn(3L);
    when(consultantAgencyRepository.findActiveConsultantIdsByAgencyId(AGENCY))
        .thenReturn(List.of("solo", "shared"));
    when(consultantAgencyRepository.findByConsultantIdInAndDeleteDateIsNull(
            List.of("solo", "shared")))
        .thenReturn(
            List.of(base("solo", AGENCY), base("shared", AGENCY), base("shared", OTHER_AGENCY)));
    when(adminAgencyRepository.findAgencyAdminIdsByAgencyId(AGENCY)).thenReturn(List.of("a", "b"));
    when(groupChatDeleter.countChatsOf(AGENCY)).thenReturn(1L);

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

  private static ConsultantAgencyBase base(String consultantId, long agencyId) {
    return new ConsultantAgencyBase() {
      @Override
      public Long getId() {
        return null;
      }

      @Override
      public Long getAgencyId() {
        return agencyId;
      }

      @Override
      public String getConsultantId() {
        return consultantId;
      }

      @Override
      public LocalDateTime getDeleteDate() {
        return null;
      }
    };
  }
}
