package de.caritas.cob.userservice.api.adapters.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * #1421: before a counsellor who moved to another Träger leaves a case room, the technical Matrix
 * admin has to hold the owner power level there. The agency account is gone by then, so after the
 * counsellor left nobody else could raise the colleague who takes the case over. The homeserver is
 * a stateful double that refuses what Synapse refuses, so the power level only moves when the
 * production gateway really made it.
 */
class MatrixSynapseServiceAdminPowerTest {

  private static final String COUNSELLOR = FakeSynapseHomeserver.mxid("counsellor1421");
  private static final String ASKER = FakeSynapseHomeserver.mxid("asker1421");
  private static final String OWNER = FakeSynapseHomeserver.mxid("owner1421");
  private static final String ADMIN = FakeSynapseHomeserver.adminMxid();

  private FakeSynapseHomeserver homeserver;
  private MatrixSynapseService gateway;

  @BeforeEach
  void setUp() {
    homeserver = new FakeSynapseHomeserver();
    gateway = new FakeSynapseWithSelfLeave(homeserver).gateway();
  }

  @Test
  void theOwnerGivesTheAdminInTheRoomPowerLevel100() {
    String room = homeserver.createRoom(COUNSELLOR);
    homeserver.member(room, ASKER, 0).member(room, ADMIN, 0);

    boolean done = gateway.ensureAdminHoldsRoomPower(room, COUNSELLOR, tokenOf(COUNSELLOR));

    assertThat(done).isTrue();
    assertThat(homeserver.powerOf(room, ADMIN)).isEqualTo(100);
    assertThat(homeserver.powerOf(room, COUNSELLOR)).isEqualTo(100);
    assertThat(homeserver.isMember(room, ADMIN)).isTrue();
  }

  @Test
  void anAdminWhoIsNotInTheRoomIsInvitedAndJoinedFirstThenRaised() {
    String room = homeserver.createRoom(COUNSELLOR);
    homeserver.member(room, ASKER, 0);

    boolean done = gateway.ensureAdminHoldsRoomPower(room, COUNSELLOR, tokenOf(COUNSELLOR));

    assertThat(done).isTrue();
    assertThat(homeserver.isMember(room, ADMIN)).isTrue();
    assertThat(homeserver.powerOf(room, ADMIN)).isEqualTo(100);
  }

  @Test
  void anAdminWhoLeftTheRoomEarlierIsInvitedBackAndRaised() {
    String room = homeserver.createRoom(COUNSELLOR);
    homeserver.member(room, ADMIN, 0).memberLeaves(room, ADMIN);

    boolean done = gateway.ensureAdminHoldsRoomPower(room, COUNSELLOR, tokenOf(COUNSELLOR));

    assertThat(done).isTrue();
    assertThat(homeserver.isMember(room, ADMIN)).isTrue();
    assertThat(homeserver.powerOf(room, ADMIN)).isEqualTo(100);
  }

  @Test
  void doingItAgainChangesNothingAndStillSucceeds() {
    String room = homeserver.createRoom(COUNSELLOR);
    homeserver.member(room, ADMIN, 0);
    String token = tokenOf(COUNSELLOR);

    assertThat(gateway.ensureAdminHoldsRoomPower(room, COUNSELLOR, token)).isTrue();
    assertThat(gateway.ensureAdminHoldsRoomPower(room, COUNSELLOR, token)).isTrue();

    assertThat(homeserver.powerOf(room, ADMIN)).isEqualTo(100);
    assertThat(homeserver.members(room)).containsExactlyInAnyOrder(COUNSELLOR, ADMIN);
  }

  @Test
  void anAdminWhoAlreadyHoldsPowerIsLeftAloneEvenIfTheCounsellorCouldNotRaiseThemAnyMore() {
    String room = homeserver.createRoom(OWNER);
    homeserver.member(room, COUNSELLOR, 0).member(room, ADMIN, 100);

    boolean done = gateway.ensureAdminHoldsRoomPower(room, COUNSELLOR, tokenOf(COUNSELLOR));

    assertThat(done).isTrue();
    assertThat(homeserver.powerOf(room, ADMIN)).isEqualTo(100);
  }

  @Test
  void aMemberWithoutOwnerPowerHasNothingToHandOver() {
    // A silent department member or a read-only co-reader: the room has its owner.
    String room = homeserver.createRoom(OWNER);
    homeserver.member(room, COUNSELLOR, 0).member(room, ADMIN, 0);

    boolean done = gateway.ensureAdminHoldsRoomPower(room, COUNSELLOR, tokenOf(COUNSELLOR));

    assertThat(done).isTrue();
    assertThat(homeserver.powerOf(room, ADMIN)).isZero();
    assertThat(homeserver.powerOf(room, OWNER)).isEqualTo(100);
  }

  @Test
  void aCounsellorWhoIsNotInTheRoomAnyMoreCannotHandOverAndTheCallSaysSo() {
    String room = homeserver.createRoom(COUNSELLOR);
    homeserver.member(room, ADMIN, 0).memberLeaves(room, COUNSELLOR);

    boolean done = gateway.ensureAdminHoldsRoomPower(room, COUNSELLOR, tokenOf(COUNSELLOR));

    assertThat(done).isFalse();
    assertThat(homeserver.powerOf(room, ADMIN)).isZero();
  }

  @Test
  void anUnknownRoomOrMissingArgumentsAreAFailureNotAnException() {
    assertThat(gateway.ensureAdminHoldsRoomPower("!nowhere:oriso.test", COUNSELLOR, "t")).isFalse();
    assertThat(gateway.ensureAdminHoldsRoomPower("", COUNSELLOR, tokenOf(COUNSELLOR))).isFalse();
    assertThat(gateway.ensureAdminHoldsRoomPower("!r:oriso.test", COUNSELLOR, null)).isFalse();
  }

  private String tokenOf(String mxid) {
    return gateway.loginAsUserAccessToken(mxid);
  }
}
