package de.caritas.cob.userservice.api.service.identity.observation;

import de.caritas.cob.userservice.api.helper.UsernameTranscoder;

/** Decode before case comparison: lowercasing a Base32 representation changes its payload. */
public final class IdentityUsername {
  private static final UsernameTranscoder TRANSCODER = new UsernameTranscoder();

  private IdentityUsername() {}

  public record Decoded(String value, boolean resolved) {}

  public static boolean encoded(String value) {
    return value != null && value.regionMatches(true, 0, "enc.", 0, 4);
  }

  public static Decoded decode(String value) {
    if (!encoded(value)) return new Decoded(value, true);
    String normalized = "enc." + value.substring(4);
    String raw = TRANSCODER.decodeUsername(normalized);
    boolean resolved =
        !raw.isBlank() && TRANSCODER.encodeUsername(raw).equalsIgnoreCase(normalized);
    return new Decoded(resolved ? raw : null, resolved);
  }
}
