package de.caritas.cob.userservice.api.service.session;

import de.caritas.cob.userservice.api.model.Session;

/**
 * Canonical classification for anonymous and anonymous-style Live Chat registrations.
 *
 * <p>A guest is recognised by what was stored about the registration: registration type {@code
 * ANONYMOUS}, or the live-chat postcode {@code 00000} that early guests and invite-link guests were
 * written with as {@code REGISTERED}. Never by the User-ID text (ORISO-UserService#1697): guests
 * have carried {@code Anonymous-<timestamp>}, {@code anon_N} and now animal handles, and every
 * prefix rule broke the next naming generation. {@code guest-prefix-guard.yml} keeps it that way.
 */
public final class AnonymousSessionRegistration {

  private static final String ANONYMOUS_POSTCODE = "00000";

  private AnonymousSessionRegistration() {}

  public static boolean matches(Session session) {
    if (session == null) {
      return false;
    }
    String registrationType =
        session.getRegistrationType() != null ? session.getRegistrationType().name() : null;
    return matches(registrationType, session.getPostcode());
  }

  public static boolean matches(String registrationType, String postcode) {
    return Session.RegistrationType.ANONYMOUS.name().equals(registrationType)
        || ANONYMOUS_POSTCODE.equals(postcode);
  }
}
