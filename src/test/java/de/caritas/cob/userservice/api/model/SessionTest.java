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
}
