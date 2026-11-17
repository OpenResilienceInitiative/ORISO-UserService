package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.CreateDirectLinkDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.DirectLinkMedium;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateDirectLinkDTO;
import de.caritas.cob.userservice.api.model.AgencyInviteLink;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.agencyinvitelink.CounsellorDirectLinkService;
import de.caritas.cob.userservice.api.service.agencyinvitelink.CounsellorDirectLinkService.CreateCommand;
import de.caritas.cob.userservice.api.service.agencyinvitelink.CounsellorDirectLinkService.UpdateCommand;
import de.caritas.cob.userservice.api.service.agencyinvitelink.DirectCallKnockService;
import de.caritas.cob.userservice.api.service.consultingtype.TopicService;
import de.caritas.cob.userservice.api.service.temporaryaccount.TemporaryAccountService;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * ORISO-Frontend#2142: a direct link carries an optional, informational date and time. It is kept
 * in UTC like every other time of the link and handed back with its offset.
 */
class DirectLinkControllerDelegateScheduledAtTest {

  private final CounsellorDirectLinkService service = mock(CounsellorDirectLinkService.class);
  private final UserAccountService accounts = mock(UserAccountService.class);
  private final TopicService topics = mock(TopicService.class);
  private final de.caritas.cob.userservice.api.service.session.SessionConsentService consents =
      mock(de.caritas.cob.userservice.api.service.session.SessionConsentService.class);
  private final Consultant counsellor = new Consultant();
  private final de.caritas.cob.userservice.api.service.agencyinvitelink.DirectInvitationService
      invitations =
          mock(
              de.caritas.cob.userservice.api.service.agencyinvitelink.DirectInvitationService
                  .class);
  private DirectLinkControllerDelegate delegate;

  @BeforeEach
  void setUp() {
    delegate =
        new DirectLinkControllerDelegate(
            service,
            invitations,
            accounts,
            topics,
            mock(DirectCallKnockService.class),
            mock(de.caritas.cob.userservice.api.service.chat.GroupWaitingRoomService.class),
            mock(UserRepository.class),
            mock(TemporaryAccountService.class),
            consents);
    counsellor.setId("counsellor-1");
    when(accounts.retrieveValidatedConsultant()).thenReturn(counsellor);
    when(topics.getAllTopicsMap()).thenReturn(Map.of());
  }

  @Test
  void startDirectLinkConversation_Should_TellAJoinerTheirOwnConsentIsStillOpen() {
    var joiner = new de.caritas.cob.userservice.api.model.User();
    joiner.setUserId("joiner-1");
    when(accounts.retrieveValidatedUser()).thenReturn(joiner);
    when(service.startConversation("token", joiner)).thenReturn(started(true));
    when(consents.consentRequired(42L, "joiner-1")).thenReturn(true);

    var body = delegate.startDirectLinkConversation("token").getBody();

    assertThat(body.getConsentRequired()).isTrue();
  }

  @Test
  void startDirectLinkConversation_Should_LeaveConsentOut_ForAnOrdinary1to1() {
    var asker = new de.caritas.cob.userservice.api.model.User();
    asker.setUserId("asker-1");
    when(accounts.retrieveValidatedUser()).thenReturn(asker);
    when(service.startConversation("token", asker)).thenReturn(started(false));

    var body = delegate.startDirectLinkConversation("token").getBody();

    assertThat(body.getConsentRequired()).isNull();
    org.mockito.Mockito.verifyNoInteractions(consents);
  }

  private static CounsellorDirectLinkService.StartedConversation started(boolean shared) {
    return new CounsellorDirectLinkService.StartedConversation(
        42L,
        "!room:oriso",
        de.caritas.cob.userservice.api.service.agencyinvitelink.InviteLinkChatType.CHAT,
        de.caritas.cob.userservice.api.service.agencyinvitelink.InviteLinkAdmission.DIRECT,
        false,
        null,
        null,
        null,
        shared,
        null);
  }

  @Test
  void create_Should_PassTheScheduledTimeInUtc_And_ReturnIt() {
    when(service.create(eq(counsellor), any()))
        .thenAnswer(
            i -> {
              CreateCommand cmd = i.getArgument(1);
              return link(cmd.scheduledAt());
            });

    var response =
        delegate.createDirectLink(
            new CreateDirectLinkDTO()
                .medium(DirectLinkMedium.VIDEO)
                .topicId(11L)
                .name("Termin Frau M.")
                .scheduledAt(OffsetDateTime.of(2026, 11, 20, 14, 30, 0, 0, ZoneOffset.ofHours(1))));

    var captor = ArgumentCaptor.forClass(CreateCommand.class);
    verify(service).create(eq(counsellor), captor.capture());
    assertThat(captor.getValue().scheduledAt()).isEqualTo(LocalDateTime.of(2026, 11, 20, 13, 30));
    assertThat(response.getBody().getScheduledAt())
        .isEqualTo(OffsetDateTime.of(2026, 11, 20, 13, 30, 0, 0, ZoneOffset.UTC));
  }

  @Test
  void create_Should_LeaveTheScheduledTimeEmpty_When_NotGiven() {
    when(service.create(eq(counsellor), any())).thenReturn(link(null));

    var response =
        delegate.createDirectLink(
            new CreateDirectLinkDTO().medium(DirectLinkMedium.CHAT).topicId(11L));

    var captor = ArgumentCaptor.forClass(CreateCommand.class);
    verify(service).create(eq(counsellor), captor.capture());
    assertThat(captor.getValue().scheduledAt()).isNull();
    assertThat(response.getBody().getScheduledAt()).isNull();
  }

  @Test
  void update_Should_PassTheScheduledTimeAndTheClearFlag() {
    when(service.update(eq(counsellor), eq(5L), any())).thenReturn(link(null));

    delegate.updateDirectLink(
        5L,
        new UpdateDirectLinkDTO()
            .scheduledAt(OffsetDateTime.of(2026, 12, 1, 10, 0, 0, 0, ZoneOffset.ofHours(1))));
    delegate.updateDirectLink(5L, new UpdateDirectLinkDTO().clearScheduledAt(true));

    var captor = ArgumentCaptor.forClass(UpdateCommand.class);
    verify(service, org.mockito.Mockito.times(2)).update(eq(counsellor), eq(5L), captor.capture());
    assertThat(captor.getAllValues().get(0).scheduledAt())
        .isEqualTo(LocalDateTime.of(2026, 12, 1, 9, 0));
    assertThat(captor.getAllValues().get(0).clearScheduledAt()).isFalse();
    assertThat(captor.getAllValues().get(1).scheduledAt()).isNull();
    assertThat(captor.getAllValues().get(1).clearScheduledAt()).isTrue();
  }

  /** ORISO-Frontend#2252: the list reads the appointments of all links at once, not per link. */
  @Test
  void list_Should_ReadTheAppointmentsOfAllLinksInOneGo() {
    var first = link(null);
    first.setAppointmentId(java.util.UUID.randomUUID());
    var second = link(null);
    second.setId(6L);
    second.setAppointmentId(java.util.UUID.randomUUID());
    var appointment = new de.caritas.cob.userservice.api.model.SessionAppointment();
    appointment.setId(first.getAppointmentId());
    appointment.setStatus(de.caritas.cob.userservice.api.model.SessionAppointment.Status.SCHEDULED);
    appointment.setStartTime(java.time.Instant.parse("2030-01-01T10:00:00Z"));
    appointment.setEndTime(java.time.Instant.parse("2030-01-01T11:00:00Z"));
    when(service.listOwn(counsellor)).thenReturn(java.util.List.of(first, second));
    when(invitations.appointmentsOf(java.util.List.of(first, second)))
        .thenReturn(Map.of(appointment.getId(), appointment));

    var response = delegate.getOwnDirectLinks();

    org.mockito.Mockito.verify(invitations, org.mockito.Mockito.never()).appointmentOf(any());
    assertThat(response.getBody().get(0).getAppointment().getId()).isEqualTo(appointment.getId());
    assertThat(response.getBody().get(1).getAppointment()).isNull();
  }

  private static AgencyInviteLink link(LocalDateTime scheduledAt) {
    return AgencyInviteLink.builder()
        .id(5L)
        .token("token-5")
        .topicId(11L)
        .chatType("VIDEO")
        .status("ACTIVE")
        .scheduledAt(scheduledAt)
        .build();
  }
}
