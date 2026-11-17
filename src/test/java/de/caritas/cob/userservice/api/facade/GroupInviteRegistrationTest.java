package de.caritas.cob.userservice.api.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ChatAgency;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.GuestAccountMode;
import de.caritas.cob.userservice.api.port.out.ChatAgencyRepository;
import de.caritas.cob.userservice.api.service.ChatService;
import de.caritas.cob.userservice.api.service.agencyinvitelink.GuestAccountModePolicy;
import de.caritas.cob.userservice.api.service.agencyinvitelink.InviteLinkChatType;
import de.caritas.cob.userservice.api.service.agencyinvitelink.PlatformGuestAccountModePolicy;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** #1662: a Gesprächskreis invite admits the accounts its guest account mode allows. */
class GroupInviteRegistrationTest {

  private static final long GROUP = 600L;
  private static final long AGENCY = 70L;
  private static final String TOKEN = "invite-secret";

  private final ChatAgencyRepository chatAgencies = mock(ChatAgencyRepository.class);
  private boolean temporaryAllowed = true;
  private final GuestAccountModePolicy policy =
      new PlatformGuestAccountModePolicy((tenant, agency, medium) -> temporaryAllowed);
  private final GroupInviteRegistration registration =
      new GroupInviteRegistration(chatAgencies, mock(ChatService.class), policy);
  private Chat group;

  @BeforeEach
  void setUp() {
    group =
        Chat.builder()
            .id(GROUP)
            .topic("Kreis")
            .initialStartDate(java.time.LocalDateTime.now())
            .startDate(java.time.LocalDateTime.now())
            .conversationType(ConversationType.SELF_HELP)
            .inviteToken(TOKEN)
            .build();
    when(chatAgencies.findByChat_Id(GROUP))
        .thenReturn(List.of(ChatAgency.builder().chat(group).agencyId(AGENCY).build()));
  }

  @ParameterizedTest
  @CsvSource({
    "TEMPORARY, true, true",
    "TEMPORARY, false, false",
    "PERMANENT, true, false",
    "PERMANENT, false, true",
    "GUEST_CHOOSES, true, true",
    "GUEST_CHOOSES, false, true"
  })
  void theModeDecidesWhichAccountMayJoin(GuestAccountMode mode, boolean temporary, boolean ok) {
    group.setGuestAccountMode(mode);

    if (ok) {
      assertThat(registration.resolveInvitedGroup(invite(temporary))).contains(group);
    } else {
      assertThatThrownBy(() -> registration.resolveInvitedGroup(invite(temporary)))
          .isInstanceOf(BadRequestException.class);
    }
  }

  @Test
  void theDefaultLetsGuestsChoose() {
    var fresh = new Chat();

    assertThat(fresh.getGuestAccountMode()).isEqualTo(GuestAccountMode.GUEST_CHOOSES);
  }

  @Test
  void noTemporaryAccountWhileThePlatformForbidsThem() {
    group.setGuestAccountMode(GuestAccountMode.GUEST_CHOOSES);
    temporaryAllowed = false;

    assertThatThrownBy(() -> registration.resolveInvitedGroup(invite(true)))
        .isInstanceOf(BadRequestException.class);
    assertThat(registration.resolveInvitedGroup(invite(false))).contains(group);
    assertThat(policy.allowedModes(null, AGENCY, InviteLinkChatType.CHAT))
        .containsExactly(GuestAccountMode.PERMANENT);
  }

  @Test
  void theInvitationNamesTheModeTheGuestJoinsUnder() {
    group.setGuestAccountMode(GuestAccountMode.GUEST_CHOOSES);

    var invitation = registration.resolveInvitation(invite(true)).orElseThrow();

    assertThat(invitation.group()).isSameAs(group);
    assertThat(invitation.mode()).isEqualTo(GuestAccountMode.GUEST_CHOOSES);
  }

  @Test
  void theCircleFollowsTheCapOfTheTraegerItRegistersIn() {
    var capping = mock(GuestAccountModePolicy.class);
    when(capping.cap(5L, AGENCY, InviteLinkChatType.CHAT, GuestAccountMode.GUEST_CHOOSES))
        .thenReturn(GuestAccountMode.PERMANENT);
    var inTraeger = new GroupInviteRegistration(chatAgencies, mock(ChatService.class), capping);
    de.caritas.cob.userservice.api.tenant.TenantContext.setCurrentTenant(5L);
    try {
      assertThatThrownBy(() -> inTraeger.resolveInvitation(invite(true)))
          .isInstanceOf(BadRequestException.class);
    } finally {
      de.caritas.cob.userservice.api.tenant.TenantContext.clear();
    }
  }

  private static UserDTO invite(boolean temporary) {
    var dto = new UserDTO();
    dto.setGroupChatId(GROUP);
    dto.setAgencyId(AGENCY);
    dto.setGroupChatInviteToken(TOKEN);
    dto.setTemporary(temporary);
    return dto;
  }
}
