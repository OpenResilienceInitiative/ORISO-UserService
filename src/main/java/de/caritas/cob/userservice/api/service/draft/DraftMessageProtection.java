package de.caritas.cob.userservice.api.service.draft;

import de.caritas.cob.userservice.api.model.DraftMessage;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.regex.Pattern;

final class DraftMessageProtection {

  static final String SCOPE_NAMESPACE = "draft.";
  static final int MAX_ENVELOPE_LENGTH = 1_048_576;
  private static final Pattern SCOPE = Pattern.compile("draft\\.v1\\.[A-Za-z0-9_-]{43}");
  private static final Pattern REVISION = Pattern.compile("[A-Za-z0-9_-]{43}");
  private static final Pattern ENVELOPE =
      Pattern.compile("draft\\.v1\\.[A-Za-z0-9_-]{16}\\.[A-Za-z0-9_-]{22,}");

  private DraftMessageProtection() {}

  static boolean isProtectedScope(String scopeKey) {
    return scopeKey != null && SCOPE.matcher(scopeKey).matches();
  }

  static boolean isRevision(String revision) {
    return revision != null && REVISION.matcher(revision).matches();
  }

  static boolean isEnvelope(String text) {
    return text != null
        && text.length() <= MAX_ENVELOPE_LENGTH
        && ENVELOPE.matcher(text).matches()
        && (text.length() - text.lastIndexOf('.') - 1) % 4 != 1;
  }

  /**
   * Length-framed fields avoid ambiguity and detect changes even when the database timestamps two
   * edits in the same second.
   */
  static String revision(DraftMessage draft) {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }

    for (Object field :
        new Object[] {
          draft.getId(),
          draft.getUserId(),
          draft.getTenantId(),
          draft.getScopeKey(),
          draft.getProtectedScopeKey(),
          draft.getText(),
          draft.getActionPath(),
          draft.getTitle(),
          draft.getSourceSessionId(),
          draft.getRoomRef(),
          draft.getThreadRootId(),
          draft.getCreateDate(),
          draft.getUpdateDate()
        }) {
      if (field == null) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
      } else {
        byte[] bytes = field.toString().getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
      }
    }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest());
  }
}
