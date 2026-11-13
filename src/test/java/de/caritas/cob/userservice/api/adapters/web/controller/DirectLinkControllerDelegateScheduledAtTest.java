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
  private final Consultant counsellor = new Consultant();
  private DirectLinkControllerDelegate delegate;

  @BeforeEach
  void setUp() {
    delegate =
        new DirectLinkControllerDelegate(
            service,
            accounts,
            topics,
            mock(DirectCallKnockService.class),
            mock(UserRepository.class),
            mock(TemporaryAccountService.class));
    counsellor.setId("counsellor-1");
    when(accounts.retrieveValidatedConsultant()).thenReturn(counsellor);
    when(topics.getAllTopicsMap()).thenReturn(Map.of());
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
