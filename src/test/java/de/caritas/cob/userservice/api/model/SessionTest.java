package de.caritas.cob.userservice.api.model;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.Test;

public class SessionTest {

  @Test
  public void equals_Should_returnTrue_When_objectIsSameReference() {
    Session session = new EasyRandom().nextObject(Session.class);

    assertThat(session, is(session));
  }

  @Test
  public void equals_Should_returnFalse_When_objectIsNoSessionInstance() {
    Session session = new EasyRandom().nextObject(Session.class);

    boolean equals = session.equals(new Object());

    assertThat(equals, is(false));
  }

  @Test
  public void equals_Should_returnFalse_When_sessionIdsAreDifferent() {
    Session session = new EasyRandom().nextObject(Session.class);
    session.setId(1L);
    Session otherSession = new EasyRandom().nextObject(Session.class);
    otherSession.setId(2L);

    boolean equals = session.equals(otherSession);

    assertThat(equals, is(false));
  }

  @Test
  public void equals_Should_returnTrue_When_sessionIdsAreEqual() {
    Session session = new EasyRandom().nextObject(Session.class);
    session.setId(1L);
    Session otherSession = new EasyRandom().nextObject(Session.class);
    otherSession.setId(1L);

    boolean equals = session.equals(otherSession);

    assertThat(equals, is(true));
  }

  @Test
  public void builder_Should_defaultSupervisionOptedOutToFalse() {
    // The is_supervision_opted_out column is NOT NULL. A newly built session must therefore carry
    // a non-null value, otherwise Hibernate inserts NULL and every session creation fails with a
    // constraint violation. @Builder ignores plain field initializers, so this pins
    // @Builder.Default.
    Session session =
        Session.builder()
            .registrationType(Session.RegistrationType.REGISTERED)
            .postcode("12345")
            .status(Session.SessionStatus.NEW)
            .build();

    assertThat(session.getSupervisionOptedOut(), is(false));
  }

  @Test
  public void noArgsConstructor_Should_defaultSupervisionOptedOutToFalse() {
    Session session = new Session();

    assertThat(session.getSupervisionOptedOut(), is(false));
  }

  /** FE#2494 *4: the stamp decides; the team flag only for a case not stamped yet. */
  @Test
  void isTeamCounsellingCase_Should_ReadTheStampBeforeTheTeamFlag() {
    var session = oneToOne();
    session.setTeamSession(true);
    assertThat(session.isTeamCounsellingCase(), is(true));

    session.setCaseSharingModel(CaseSharingModel.NONE);
    assertThat(session.isTeamCounsellingCase(), is(false));

    session.setTeamSession(false);
    session.setCaseSharingModel(CaseSharingModel.TEAM_COUNSELLING);
    assertThat(session.isTeamCounsellingCase(), is(true));
  }

  @Test
  void isTeamCounsellingCase_Should_BeFalseForGroupAndLegacyLiveChats() {
    var group = oneToOne();
    group.setTeamSession(true);
    group.setConversationType(ConversationType.INTERNAL_GROUP);
    assertThat(group.isTeamCounsellingCase(), is(false));

    var legacyLiveChat = oneToOne();
    legacyLiveChat.setTeamSession(true);
    legacyLiveChat.setConversationType(null);
    legacyLiveChat.setRegistrationType(Session.RegistrationType.ANONYMOUS);
    assertThat(legacyLiveChat.isTeamCounsellingCase(), is(false));

    var legacyPostcode = oneToOne();
    legacyPostcode.setTeamSession(true);
    legacyPostcode.setConversationType(null);
    legacyPostcode.setPostcode("00000");
    assertThat(legacyPostcode.isTeamCounsellingCase(), is(false));

    var legacyCase = oneToOne();
    legacyCase.setTeamSession(true);
    legacyCase.setConversationType(null);
    assertThat(legacyCase.isTeamCounsellingCase(), is(true));
  }

  /** The modality guard comes first: a stamp never makes a group or live chat a team case. */
  @Test
  void isTeamCounsellingCase_Should_IgnoreTheStampOnNonCounsellingModalities() {
    for (var type :
        java.util.List.of(
            ConversationType.INTERNAL_GROUP,
            ConversationType.SELF_HELP,
            ConversationType.LIVE_CHAT)) {
      var session = oneToOne();
      session.setConversationType(type);
      session.setCaseSharingModel(CaseSharingModel.TEAM_COUNSELLING);
      assertThat(session.isTeamCounsellingCase(), is(false));
    }
  }

  /** A known modality is authoritative over the legacy live-chat markers. */
  @Test
  void isTeamCounsellingCase_Should_TrustAnExplicitCounsellingModality() {
    var session = oneToOne();
    session.setRegistrationType(Session.RegistrationType.ANONYMOUS);
    session.setPostcode("00000");
    session.setCaseSharingModel(CaseSharingModel.TEAM_COUNSELLING);
    assertThat(session.isTeamCounsellingCase(), is(true));
  }

  private static Session oneToOne() {
    var session = new Session();
    session.setConversationType(ConversationType.AGENCY_COUNSELLING);
    session.setRegistrationType(Session.RegistrationType.REGISTERED);
    session.setPostcode("12345");
    return session;
  }
}
