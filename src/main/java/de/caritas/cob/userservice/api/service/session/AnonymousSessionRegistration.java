package de.caritas.cob.userservice.api.service.session;

import static java.util.Objects.nonNull;

import de.caritas.cob.userservice.api.model.Session;

/** Canonical classification for anonymous and anonymous-style Live Chat registrations. */
public final class AnonymousSessionRegistration {

  private static final String ANONYMOUS_POSTCODE = "00000";
  private static final String ANONYMOUS_USERNAME_PREFIX = "Anonymous-";

  private AnonymousSessionRegistration() {}

  public static boolean matches(Session session) {
    if (session == null) {
      return false;
    }
    String username =
        nonNull(session.getUser()) && nonNull(session.getUser().getUsername())
            ? session.getUser().getUsername()
            : null;
    String registrationType =
        nonNull(session.getRegistrationType()) ? session.getRegistrationType().name() : null;
    return matches(registrationType, session.getPostcode(), username);
  }

  public static boolean matches(String registrationType, String postcode, String username) {
    return Session.RegistrationType.ANONYMOUS.name().equals(registrationType)
        || ANONYMOUS_POSTCODE.equals(postcode)
        || nonNull(username) && username.startsWith(ANONYMOUS_USERNAME_PREFIX);
  }
}
