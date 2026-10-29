package de.caritas.cob.userservice.api.service.creationoutcome;

import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantDTO;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAvatarKind;
import de.caritas.cob.userservice.api.model.ConsultantAvatars;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/** Explicit, versioned effective public projection. Canonical bytes never leave this method. */
public final class CreationRequestFingerprint {
  private CreationRequestFingerprint() {}

  public static String of(CreateConsultantDTO effective, CreationOperation.Scope scope) {
    Objects.requireNonNull(effective);
    Objects.requireNonNull(scope);
    if (effective.getPublicSlug() != null && effective.getPublicSlug().length() > 255)
      throw new IllegalArgumentException("Public slug too long");
    var topics = CreationOperation.canonicalIds(effective.getTopicIds());
    if (!CreationOperation.canonicalIds(effective.getAgencyIds()).equals(scope.agencyIds())) {
      throw new IllegalArgumentException("Inconsistent effective agencies");
    }
    var avatar = new Consultant();
    ConsultantAvatars.apply(
        avatar,
        effective.getAvatarKind() == null
            ? null
            : ConsultantAvatarKind.fromNameOrNull(effective.getAvatarKind().getValue()),
        effective.getAvatarId());
    try {
      var bytes = new ByteArrayOutputStream();
      var canonical = new DataOutputStream(bytes);
      canonical.writeShort(1);
      field(canonical, effective.getUsername());
      field(canonical, effective.getFirstname());
      field(canonical, effective.getLastname());
      field(
          canonical,
          effective.getEmail() == null
              ? null
              : effective.getEmail().trim().toLowerCase(Locale.ROOT));
      canonical.writeBoolean(Boolean.TRUE.equals(effective.getFormalLanguage()));
      canonical.writeBoolean(Boolean.TRUE.equals(effective.getAbsent()));
      field(canonical, effective.getAbsenceMessage());
      canonical.writeLong(scope.tenantId());
      field(canonical, scope.role());
      boolean groupChat = Boolean.TRUE.equals(effective.getIsGroupchatConsultant());
      canonical.writeBoolean(groupChat);
      field(canonical, groupChat ? "GROUP_CHAT_CONSULTANT" : null);
      canonical.writeInt(topics.size());
      for (long id : topics) canonical.writeLong(id);
      canonical.writeInt(scope.agencyIds().size());
      for (long id : scope.agencyIds()) canonical.writeLong(id);
      field(
          canonical,
          effective.getPublicSlug() == null
              ? null
              : effective.getPublicSlug().trim().toLowerCase(Locale.ROOT));
      field(canonical, effective.getDisplayName());
      field(canonical, effective.getInternalDisplayName());
      field(canonical, effective.getSalutation());
      field(canonical, effective.getPosition());
      field(canonical, effective.getTitle());
      field(canonical, effective.getAdminRemarks());
      field(canonical, avatar.getAvatarKind() == null ? null : avatar.getAvatarKind().name());
      field(canonical, avatar.getAvatarId());
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    } catch (IOException | NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("Fingerprint unavailable");
    }
  }

  private static void field(DataOutputStream out, String value) throws IOException {
    if (value == null) {
      out.writeInt(-1);
      return;
    }
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    out.writeInt(bytes.length);
    out.write(bytes);
  }
}
