package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.GroupChatJoinRequest;
import de.caritas.cob.userservice.api.model.GroupChatJoinRequest.Status;
import de.caritas.cob.userservice.api.model.GroupChatJoinRequest.Via;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.agencyinvitelink.CounsellorDirectLinkService;
import de.caritas.cob.userservice.api.service.agencyinvitelink.DirectCallKnockService;
import de.caritas.cob.userservice.api.service.consultingtype.TopicService;
import de.caritas.cob.userservice.api.service.temporaryaccount.TemporaryAccountService;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** #1487 on top of #1479: a knock through a call share link shows the guest's pseudonym too. */
class DirectLinkControllerDelegateCallLinkTest {

  private static final String PSEUDONYM = "Gast Mond";

  private final UserAccountService accounts = mock(UserAccountService.class);
  private final DirectCallKnockService knocks = mock(DirectCallKnockService.class);
  private final UserRepository users = mock(UserRepository.class);
  private final Consultant counsellor = new Consultant();
  private final GroupChatJoinRequest knock =
      GroupChatJoinRequest.builder()
          .id(9L)
          .via(Via.CALL_LINK)
          .inviteLinkId(5L)
          .sessionId(77L)
          .userId("guest-1")
          .status(Status.PENDING)
          .medium("VIDEO")
          .requestedAt(LocalDateTime.now())
          .build();
  private DirectLinkControllerDelegate delegate;

  @BeforeEach
  void setUp() {
    delegate =
        new DirectLinkControllerDelegate(
            mock(CounsellorDirectLinkService.class),
            accounts,
            mock(TopicService.class),
            knocks,
            users,
            mock(TemporaryAccountService.class));
    counsellor.setId("counsellor-1");
    when(accounts.retrieveValidatedConsultant()).thenReturn(counsellor);
    var guest = new User();
    guest.setUserId("guest-1");
    guest.setUsername(new UsernameTranscoder().encodeUsername(PSEUDONYM));
    when(users.findAllById(List.of("guest-1"))).thenReturn(List.of(guest));
    when(users.findById("guest-1")).thenReturn(Optional.of(guest));
  }

  @Test
  void callLinkKnocks_Should_CarryTheDisplayName_InTheFilteredList() {
    when(knocks.pendingFor(counsellor, null, "!room:oriso")).thenReturn(List.of(knock));

    var body = delegate.getPendingCallKnocks(null, "!room:oriso").getBody();

    assertThat(body)
        .singleElement()
        .satisfies(
            dto -> {
              assertThat(dto.getDisplayName()).isEqualTo(PSEUDONYM);
              assertThat(dto.getCallShareLinkId()).isEqualTo(5L);
              assertThat(dto.getSessionId()).isNull();
            });
  }

  @Test
  void callLinkKnock_Should_CarryTheDisplayName_WhenDecidedOneByOne() {
    when(knocks.admit(9L, counsellor)).thenReturn(knock);

    assertThat(delegate.admitCallKnock(9L).getBody().getDisplayName()).isEqualTo(PSEUDONYM);
  }
}
