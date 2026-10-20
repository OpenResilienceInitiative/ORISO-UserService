package de.caritas.cob.userservice.api.service.identity.observation;

import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import java.util.List;
import java.util.Locale;

/** Exact selectors only; none conveys authority, a host, a realm or a query fragment. */
public record IdentityProbe(String identityId, List<String> usernames, String email) {
  public IdentityProbe {
    usernames = List.copyOf(usernames);
  }

  public static IdentityProbe of(
      String id, String username, String email, UsernameTranscoder transcoder) {
    validate(id, 255);
    validate(username, 255);
    validate(email, 254);
    if (id == null && username == null && email == null)
      throw new IllegalArgumentException("At least one exact selector is required");
    List<String> names = List.of();
    if (username != null) {
      var decoded = IdentityUsername.decode(username);
      if (!decoded.resolved()) throw new IllegalArgumentException("Invalid encoded username");
      String raw = decoded.value();
      validate(raw, 255);
      String encoded = transcoder.encodeUsername(raw);
      if (IdentityUsername.encoded(username) && !encoded.equalsIgnoreCase(username))
        throw new IllegalArgumentException("Invalid encoded username");
      names = List.of(raw, encoded);
    }
    return new IdentityProbe(id, names, email == null ? null : email.toLowerCase(Locale.ROOT));
  }

  private static void validate(String value, int maximum) {
    if (value != null
        && (value.isBlank()
            || value.length() > maximum
            || value
                .codePoints()
                .anyMatch(c -> Character.isISOControl(c) || "*%?".indexOf(c) >= 0)))
      throw new IllegalArgumentException("Invalid exact selector");
  }

  public boolean matches(String id, String username, String address) {
    return (identityId != null && identityId.equals(id))
        || (username != null
            && !usernames.isEmpty()
            && IdentityUsername.decode(username).resolved()
            && usernames.getFirst().equalsIgnoreCase(IdentityUsername.decode(username).value()))
        || (email != null && email.equalsIgnoreCase(address));
  }
}
