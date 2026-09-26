package de.caritas.cob.userservice.api.service.chat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * The secret part of a group's invite link (#1237). The group number alone is guessable; the link
 * also carries this token, and only someone who holds the link can join.
 */
public final class GroupChatInviteTokens {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int TOKEN_BYTES = 24;

  private GroupChatInviteTokens() {}

  public static String newToken() {
    var bytes = new byte[TOKEN_BYTES];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** Constant-time comparison; a missing token on either side never matches. */
  public static boolean matches(String expected, String presented) {
    if (expected == null || presented == null || expected.isEmpty()) {
      return false;
    }
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
  }
}
