package de.caritas.cob.userservice.api.adapters.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ORISO-Frontend#2252 checkpoint 3: a room state write on behalf of the conversation's counsellor
 * (history visibility, link-invited marker). The homeserver is a stateful double that refuses what
 * Synapse refuses, so a write only counts when Matrix holds the state afterwards.
 */
class MatrixSynapseServiceRoomStateTest {

  private static final String COUNSELLOR = FakeSynapseHomeserver.mxid("counsellor2252");
  private static final String AGENCY = FakeSynapseHomeserver.mxid("agency2252");
  private static final String ASKER = FakeSynapseHomeserver.mxid("asker2252");

  private FakeSynapseHomeserver homeserver;
  private MatrixSynapseService gateway;

  @BeforeEach
  void setUp() {
    homeserver = new FakeSynapseHomeserver();
    gateway = homeserver.gateway();
  }

  @Test
  void aRoomWithoutTheSettingShowsEarlierMessages() {
    String room = homeserver.createRoom(COUNSELLOR);

    assertThat(gateway.getRoomHistoryVisibility(room)).contains("shared");
  }

  private static final String ADMIN = FakeSynapseHomeserver.adminMxid();
  private static final String MARKER = "org.oriso.direct_invitation_member";

  @Test
  void theTechnicalAdminTakesOverTheRoomThroughItsHighestLocalMember() {
    String room = homeserver.createRoom(AGENCY);
    homeserver.member(room, COUNSELLOR, 0).member(room, ASKER, 0);

    assertThat(gateway.prepareAdminAsRoomOwner(room, COUNSELLOR)).isTrue();

    assertThat(homeserver.isMember(room, ADMIN)).isTrue();
    assertThat(homeserver.powerOf(room, ADMIN)).isEqualTo(100);
  }

  @Test
  void theTakeOverFailsWhenNobodyInTheRoomCanGrantThePower() {
    String room = homeserver.createRoom(AGENCY);
    homeserver.member(room, COUNSELLOR, 0).memberLeaves(room, AGENCY);

    assertThat(gateway.prepareAdminAsRoomOwner(room, COUNSELLOR)).isFalse();
  }

  @Test
  void theAdminHidesEarlierMessagesFromLaterMembers() {
    String room = homeserver.createRoom(AGENCY);
    homeserver.member(room, COUNSELLOR, 0);
    gateway.prepareAdminAsRoomOwner(room, COUNSELLOR);

    boolean written =
        gateway.putRoomStateAsAdmin(
            room, "m.room.history_visibility", "", Map.of("history_visibility", "joined"));

    assertThat(written).isTrue();
    assertThat(gateway.getRoomHistoryVisibility(room)).isEqualTo(Optional.of("joined"));
  }

  @Test
  void anOrdinaryMemberCanNeitherWriteNorClearAMarkOnceTheRoomIsLocked() {
    String room = homeserver.createRoom(AGENCY);
    homeserver.member(room, COUNSELLOR, 0).member(room, ASKER, 0);
    gateway.prepareAdminAsRoomOwner(room, COUNSELLOR);

    boolean locked =
        gateway.lockRoomStateEvents(
            room, Map.of(MARKER, 100, "m.room.history_visibility", 100), 50);
    gateway.putRoomStateAsAdmin(room, MARKER, ASKER, Map.of("link_id", 7));

    assertThat(locked).isTrue();
    assertThat(homeserver.eventLevelOf(room, MARKER)).isEqualTo(100);
    assertThat(homeserver.redactLevelOf(room)).isGreaterThanOrEqualTo(50);
    // Without the lock state_default (50) applies; an ordinary member (0) is refused either way,
    // and a member raised to the default moderator level is refused too.
    homeserver.member(room, "@mod2252:oriso.test", 50);
    assertThat(homeserver.trySendState(room, "@mod2252:oriso.test", MARKER, ASKER, Map.of()))
        .isFalse();
    assertThat(homeserver.trySendState(room, ASKER, MARKER, ASKER, Map.of())).isFalse();
    assertThat(homeserver.stateOf(room, MARKER, ASKER)).containsEntry("link_id", 7);
  }

  @Test
  void aMemberMarkerIsStoredUnderTheMembersMatrixIdAndSentByTheAdmin() {
    String room = homeserver.createRoom(COUNSELLOR);
    gateway.prepareAdminAsRoomOwner(room, COUNSELLOR);

    boolean written = gateway.putRoomStateAsAdmin(room, MARKER, ASKER, Map.of("link_id", 7));

    assertThat(written).isTrue();
    assertThat(homeserver.stateOf(room, MARKER, ASKER)).containsEntry("link_id", 7);
    assertThat(homeserver.stateSenderOf(room, MARKER, ASKER)).isEqualTo(ADMIN);
  }

  @Test
  void aPersonInvitedIntoAnExisting1to1JoinsOnlyAfterTheRoomHidesEarlierMessages() {
    String guest = FakeSynapseHomeserver.mxid("guest2252");
    homeserver.account(guest);
    String room = homeserver.createRoom(AGENCY);
    homeserver
        .member(room, COUNSELLOR, 0)
        .member(room, ASKER, 0)
        .member(room, FakeSynapseHomeserver.adminMxid(), 0);
    var counsellor = new de.caritas.cob.userservice.api.model.Consultant();
    counsellor.setMatrixUserId(COUNSELLOR);
    var session = new de.caritas.cob.userservice.api.model.Session();
    session.setId(1L);
    session.setMatrixRoomId(room);
    session.setConsultant(counsellor);
    var user = new de.caritas.cob.userservice.api.model.User();
    user.setMatrixUserId(guest);

    new de.caritas.cob.userservice.api.service.agencyinvitelink.DirectInvitationRoomMembership(
            gateway)
        .addInvitedGuestToExistingRoom(session, user, 7L);

    assertThat(gateway.getRoomHistoryVisibility(room)).contains("joined");
    assertThat(homeserver.stateOf(room, "org.oriso.direct_invitation_member", guest))
        .containsEntry("link_id", 7L)
        .containsKey("since");
    assertThat(homeserver.isMember(room, guest)).isTrue();
  }
}
