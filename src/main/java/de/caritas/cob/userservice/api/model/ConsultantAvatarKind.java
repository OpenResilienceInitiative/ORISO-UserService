package de.caritas.cob.userservice.api.model;

/**
 * How a counsellor's avatar is rendered (#1046, product decision Frank 2026-09-17).
 *
 * <p>A counsellor has exactly one of three choices:
 *
 * <ul>
 *   <li>{@link #ICON} — one of the platform's monochrome counsellor motifs; the chosen motif id is
 *       stored alongside in {@code Consultant#avatarId}
 *   <li>{@link #INITIALS} — the generated initials tile, the fallback when nothing is chosen
 *   <li>{@link #PICTURE} — an own uploaded picture. The upload path (storage, validation, serving)
 *       is a SEPARATE ticket (#1048/#1049) and is deliberately NOT implemented here; the value only
 *       exists so the persisted contract does not have to change when that ticket lands.
 * </ul>
 */
public enum ConsultantAvatarKind {
  ICON,
  INITIALS,
  PICTURE;

  /**
   * Null-safe, exception-free parse by name. The only place in this service that turns a wire
   * string into an avatar kind — an unknown or blank value is NOT an error (a public onboarding
   * request must never 500 on it), it simply means "no choice made".
   *
   * @param name the wire spelling, e.g. {@code "ICON"}
   * @return the matching kind, or {@code null} if the value is null, blank or unknown
   */
  public static ConsultantAvatarKind fromNameOrNull(String name) {
    if (name == null || name.isBlank()) {
      return null;
    }
    for (ConsultantAvatarKind kind : values()) {
      if (kind.name().equals(name.trim())) {
        return kind;
      }
    }
    return null;
  }
}
