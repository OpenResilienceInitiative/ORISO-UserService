package de.caritas.cob.userservice.api.helper;

/**
 * Utility for parsing Matrix IDs.
 *
 * <p>Consolidates duplicated Matrix ID parsing that previously appeared 17 times across 7 classes —
 * all using the same inline {@code substring(1).split(":")[0]} pattern.
 *
 * <p>Examples:
 *
 * <pre>
 *   MatrixIds.localpart("@alice:matrix.oriso.org")  →  "alice"
 *   MatrixIds.isRoomId("!abc123:matrix.oriso.org")   →  true
 *   MatrixIds.isUserId("@alice:matrix.oriso.org")    →  true
 * </pre>
 */
public final class MatrixIds {

  private MatrixIds() {
    throw new UnsupportedOperationException("Utility class — do not instantiate");
  }

  /**
   * Extracts the local part of a Matrix ID (the portion before the first colon, minus the leading
   * sigil).
   *
   * @param matrixId a fully-qualified Matrix ID, e.g. {@code @user:server} or {@code !room:server}
   * @return the local part, e.g. {@code "user"} from {@code @user:server}
   * @throws IllegalArgumentException if the input is null, empty, or has no colon separator
   */
  public static String localpart(String matrixId) {
    if (matrixId == null || matrixId.isEmpty()) {
      throw new IllegalArgumentException("Matrix ID must not be null or empty");
    }
    int colon = matrixId.indexOf(':');
    if (colon < 1) {
      throw new IllegalArgumentException("Matrix ID has no colon separator: " + matrixId);
    }
    return matrixId.substring(1, colon);
  }

  /**
   * Lenient variant of {@link #localpart(String)} for call sites that must tolerate partially
   * formed input instead of failing fast.
   *
   * <p>Unlike {@link #localpart(String)} this method never throws: blank input is returned
   * unchanged, a leading {@code @} sigil is stripped only when present, and an ID without a colon
   * separator yields the remaining string instead of an exception.
   *
   * @param matrixId a Matrix user ID, possibly missing the sigil or server part
   * @return the local part, or the (sigil-stripped) input when no colon is present, or the original
   *     value when blank
   */
  public static String localpartLenient(String matrixId) {
    if (matrixId == null || matrixId.isBlank()) {
      return matrixId;
    }
    var id = matrixId.startsWith("@") ? matrixId.substring(1) : matrixId;
    int colon = id.indexOf(':');
    return colon > 0 ? id.substring(0, colon) : id;
  }

  /** Returns {@code true} if the given ID starts with {@code !} (room ID sigil). */
  public static boolean isRoomId(String id) {
    return id != null && id.startsWith("!");
  }

  /** Returns {@code true} if the given ID starts with {@code @} (user ID sigil). */
  public static boolean isUserId(String id) {
    return id != null && id.startsWith("@");
  }
}
