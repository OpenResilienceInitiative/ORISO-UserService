package de.caritas.cob.userservice.api.service.accountinvite;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.model.ConsultantAvatarKind;

/**
 * The inviting admin's optional avatar and display-name suggestion for the invitee
 * (ORISO-Admin#1274).
 *
 * <p>Normalised the same way {@code ConsultantAvatars#apply} normalises a consultant's own choice,
 * so a suggestion can never be "half": an ICON without a motif id becomes INITIALS, and a motif id
 * hanging off a non-icon kind is dropped. An unknown kind is a 400 here, unlike the public
 * registration (which ignores it): this is an authenticated admin request, and silently dropping a
 * typo would hide a mistake from the admin.
 */
public record InviteAvatarSuggestion(
    ConsultantAvatarKind kind, String avatarId, String displayName) {

  static final int MAX_AVATAR_ID_LENGTH = 64;
  static final int MAX_DISPLAY_NAME_LENGTH = 100;

  public static final InviteAvatarSuggestion NONE = new InviteAvatarSuggestion(null, null, null);

  public static InviteAvatarSuggestion of(String avatarKind, String avatarId, String displayName) {
    String id = trimToNull(avatarId);
    String name = trimToNull(displayName);
    if (id != null && id.length() > MAX_AVATAR_ID_LENGTH) {
      throw new BadRequestException(
          "avatarId must be at most " + MAX_AVATAR_ID_LENGTH + " characters");
    }
    if (name != null && name.length() > MAX_DISPLAY_NAME_LENGTH) {
      throw new BadRequestException(
          "displayName must be at most " + MAX_DISPLAY_NAME_LENGTH + " characters");
    }
    ConsultantAvatarKind kind = parseKind(avatarKind);
    if (kind == null) {
      return new InviteAvatarSuggestion(null, null, name);
    }
    if (kind != ConsultantAvatarKind.ICON) {
      return new InviteAvatarSuggestion(kind, null, name);
    }
    if (id == null) {
      return new InviteAvatarSuggestion(ConsultantAvatarKind.INITIALS, null, name);
    }
    return new InviteAvatarSuggestion(ConsultantAvatarKind.ICON, id, name);
  }

  /** Copies the suggestion onto the invite; a no-op suggestion leaves all three columns null. */
  public void applyTo(de.caritas.cob.userservice.api.model.AccountInvite invite) {
    invite.setSuggestedAvatarKind(kind);
    invite.setSuggestedAvatarId(avatarId);
    invite.setSuggestedDisplayName(displayName);
  }

  private static ConsultantAvatarKind parseKind(String avatarKind) {
    String trimmed = trimToNull(avatarKind);
    if (trimmed == null) {
      return null;
    }
    ConsultantAvatarKind kind = ConsultantAvatarKind.fromNameOrNull(trimmed);
    if (kind == null) {
      throw new BadRequestException("avatarKind must be one of ICON, INITIALS, PICTURE");
    }
    return kind;
  }

  private static String trimToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
