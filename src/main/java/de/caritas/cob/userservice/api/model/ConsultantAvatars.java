package de.caritas.cob.userservice.api.model;

/**
 * The single place that decides what an avatar choice looks like once it is persisted (#1046).
 *
 * <p>Creation and update both go through {@link #apply(Consultant, ConsultantAvatarKind, String)},
 * so a "half choice" — an {@link ConsultantAvatarKind#ICON} without a motif id, or a motif id
 * hanging off a non-icon kind — can never reach the database from either path.
 */
public final class ConsultantAvatars {

  private ConsultantAvatars() {}

  /**
   * Normalises the resolved choice and writes both columns.
   *
   * <ul>
   *   <li>kind {@code null} → no choice at all: both columns are cleared
   *   <li>kind {@link ConsultantAvatarKind#ICON} without a motif id → demoted to {@link
   *       ConsultantAvatarKind#INITIALS}, id cleared
   *   <li>any other kind → the motif id is dropped, it belongs to icons only
   * </ul>
   *
   * @param consultant the consultant to write to
   * @param kind the resolved kind, may be null
   * @param avatarId the resolved motif id, may be null or blank
   */
  public static void apply(Consultant consultant, ConsultantAvatarKind kind, String avatarId) {
    String trimmedId = avatarId == null || avatarId.isBlank() ? null : avatarId.trim();

    if (kind == null) {
      consultant.setAvatarKind(null);
      consultant.setAvatarId(null);
      return;
    }
    if (kind != ConsultantAvatarKind.ICON) {
      consultant.setAvatarKind(kind);
      consultant.setAvatarId(null);
      return;
    }
    if (trimmedId == null) {
      consultant.setAvatarKind(ConsultantAvatarKind.INITIALS);
      consultant.setAvatarId(null);
      return;
    }
    consultant.setAvatarKind(ConsultantAvatarKind.ICON);
    consultant.setAvatarId(trimmedId);
  }
}
