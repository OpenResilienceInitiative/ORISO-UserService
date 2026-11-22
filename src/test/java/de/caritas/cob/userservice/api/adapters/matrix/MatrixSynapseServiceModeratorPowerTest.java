package de.caritas.cob.userservice.api.adapters.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ORISO-Frontend#2229: a named group moderator (co-moderator, or an owner who did not create the
 * room) holds the Matrix moderator level (50), so they can blur passages and lift other moderators'
 * blurs. Only someone above them may write that level, so the gateway looks for a joined member who
 * holds the owner level (100): the group's current owner first, then whoever created the room or
 * the technical admin. The homeserver is a stateful double that refuses what Synapse refuses.
 */
class MatrixSynapseServiceModeratorPowerTest {

  private static final String CREATOR = FakeSynapseHomeserver.mxid("creator2229");
  private static final String NEW_OWNER = FakeSynapseHomeserver.mxid("newowner2229");
  private static final String CO_MODERATOR = FakeSynapseHomeserver.mxid("comod2229");
  private static final String PARTICIPANT = FakeSynapseHomeserver.mxid("participant2229");
  private static final String ADMIN = FakeSynapseHomeserver.adminMxid();

  private FakeSynapseHomeserver homeserver;
  private MatrixSynapseService gateway;

  @BeforeEach
  void setUp() {
    homeserver = new FakeSynapseHomeserver();
    gateway = new FakeSynapseWithSelfLeave(homeserver).gateway();
  }

  @Test
  void theOwnerWhoCreatedTheRoomRaisesACoModeratorToTheModeratorLevel() {
    String room = homeserver.createRoom(CREATOR);
    homeserver.member(room, CO_MODERATOR, 0).member(room, PARTICIPANT, 0);

    boolean done = gateway.syncModeratorPower(room, Map.of(CO_MODERATOR, true), CREATOR);

    assertThat(done).isTrue();
    assertThat(homeserver.powerOf(room, CO_MODERATOR)).isEqualTo(50);
    assertThat(homeserver.powerOf(room, PARTICIPANT)).isZero();
    assertThat(homeserver.powerOf(room, CREATOR)).isEqualTo(100);
  }

  /** Selected self-help moderators are only invited: the level is set before they join. */
  @Test
  void aModeratorWhoIsOnlyInvitedYetIsRaisedAsWell() {
    String room = homeserver.createRoom(CREATOR);
    homeserver.account(CO_MODERATOR);

    boolean done = gateway.syncModeratorPower(room, Map.of(CO_MODERATOR, true), CREATOR);

    assertThat(done).isTrue();
    assertThat(homeserver.powerOf(room, CO_MODERATOR)).isEqualTo(50);
  }

  /**
   * After a transfer of primary ownership the group's owner is no longer the room creator: they
   * hold the moderator level only and may not hand it out. The creator, still joined, writes it.
   */
  @Test
  void anOwnerWhoMayNotWritePowerLevelsIsStandedInForByTheRoomCreator() {
    String room = homeserver.createRoom(CREATOR);
    homeserver.member(room, NEW_OWNER, 50).member(room, CO_MODERATOR, 0);

    boolean done = gateway.syncModeratorPower(room, Map.of(CO_MODERATOR, true), NEW_OWNER);

    assertThat(done).isTrue();
    assertThat(homeserver.powerOf(room, CO_MODERATOR)).isEqualTo(50);
  }

  /**
   * The creator left for good (#1421): the technical admin holds the owner level in their place.
   */
  @Test
  void theTechnicalAdminWritesTheLevelWhenTheCreatorHasLeft() {
    String room = homeserver.createRoom(CREATOR);
    homeserver
        .member(room, ADMIN, 100)
        .member(room, NEW_OWNER, 50)
        .member(room, CO_MODERATOR, 0)
        .memberLeaves(room, CREATOR);

    boolean done = gateway.syncModeratorPower(room, Map.of(CO_MODERATOR, true), NEW_OWNER);

    assertThat(done).isTrue();
    assertThat(homeserver.powerOf(room, CO_MODERATOR)).isEqualTo(50);
  }

  @Test
  void aDemotedModeratorFallsBackToZeroButTheCreatorKeepsTheOwnerLevel() {
    String room = homeserver.createRoom(CREATOR);
    homeserver.member(room, CO_MODERATOR, 50);

    boolean done =
        gateway.syncModeratorPower(room, Map.of(CO_MODERATOR, false, CREATOR, false), CREATOR);

    assertThat(done).isTrue();
    assertThat(homeserver.powerOf(room, CO_MODERATOR)).isZero();
    assertThat(homeserver.powerOf(room, CREATOR)).isEqualTo(100);
  }

  /** Nothing to change: no member is impersonated, so no Matrix device is created for nothing. */
  @Test
  void aRoomThatIsAlreadyRightIsNotWrittenAndNobodyIsImpersonated() {
    String room = homeserver.createRoom(CREATOR);
    homeserver.member(room, CO_MODERATOR, 50).member(room, PARTICIPANT, 0);

    boolean done =
        gateway.syncModeratorPower(
            room, Map.of(CO_MODERATOR, true, PARTICIPANT, false, CREATOR, true), CREATOR);

    assertThat(done).isTrue();
    assertThat(homeserver.impersonatedAccounts()).isEmpty();
  }

  @Test
  void aRoomInWhichNobodyMayWritePowerLevelsAnyMoreIsReportedAsNotDone() {
    String room = homeserver.createRoom(CREATOR);
    homeserver
        .member(room, NEW_OWNER, 50)
        .member(room, CO_MODERATOR, 0)
        .memberLeaves(room, CREATOR);

    boolean done = gateway.syncModeratorPower(room, Map.of(CO_MODERATOR, true), NEW_OWNER);

    assertThat(done).isFalse();
    assertThat(homeserver.powerOf(room, CO_MODERATOR)).isZero();
  }

  @Test
  void anUnknownRoomOrMissingArgumentsAreAFailureNotAnException() {
    assertThat(
            gateway.syncModeratorPower("!nowhere:oriso.test", Map.of(CO_MODERATOR, true), CREATOR))
        .isFalse();
    assertThat(gateway.syncModeratorPower("", Map.of(CO_MODERATOR, true), CREATOR)).isFalse();
    assertThat(gateway.syncModeratorPower("!r:oriso.test", Map.of(), CREATOR)).isTrue();
  }

  /**
   * Only the power-levels event with the empty state key counts. A stray one, listed before or
   * after it, must not make a demotion look done while the user still holds the level.
   */
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
  void aStrayPowerLevelsEventDoesNotHideALevelThatMustBeTakenBack(boolean strayListedLast) {
    String room = homeserver.createRoom(CREATOR);
    homeserver
        .member(room, CO_MODERATOR, 50)
        .strayPowerLevels(room, Map.of("users", Map.of()), strayListedLast);

    boolean done = gateway.syncModeratorPower(room, Map.of(CO_MODERATOR, false), CREATOR);

    assertThat(done).isTrue();
    assertThat(homeserver.powerOf(room, CO_MODERATOR)).isZero();
  }

  @Test
  void theHoldersOfTheModeratorLevelAreReadFromTheAuthoritativeEventOnly() {
    String room = homeserver.createRoom(CREATOR);
    homeserver
        .member(room, CO_MODERATOR, 50)
        .member(room, PARTICIPANT, 0)
        .strayPowerLevels(room, Map.of("users", Map.of(PARTICIPANT, 50)), true);

    assertThat(gateway.moderatorLevelHolders(room)).containsExactly(CO_MODERATOR);
    assertThat(gateway.moderatorLevelHolders("")).isEmpty();
  }
}
