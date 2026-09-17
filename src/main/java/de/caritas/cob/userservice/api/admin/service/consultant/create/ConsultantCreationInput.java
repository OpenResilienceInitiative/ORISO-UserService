package de.caritas.cob.userservice.api.admin.service.consultant.create;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;

import java.time.LocalDateTime;
import java.util.List;

/** Definition for required input data used to create a new consultant. */
interface ConsultantCreationInput {

  Long getIdOld();

  String getUserName();

  String getEncodedUsername();

  String getFirstName();

  String getLastName();

  String getEmail();

  default String getPublicSlug() {
    return null;
  }

  default String getDisplayName() {
    return null;
  }

  default String getInternalDisplayName() {
    return null;
  }

  default String getSalutation() {
    return null;
  }

  default String getPosition() {
    return null;
  }

  default String getTitle() {
    return null;
  }

  default String getAdminRemarks() {
    return null;
  }

  /**
   * Counsellor avatar choice (#1046) as its wire spelling, e.g. {@code "ICON"}. Kept as a plain
   * string here so this input contract stays free of generated DTO types; unknown values are
   * resolved to "no choice" downstream.
   */
  default String getAvatarKind() {
    return null;
  }

  /** Id of the chosen counsellor motif; only meaningful together with {@code ICON}. */
  default String getAvatarId() {
    return null;
  }

  String getPassword();

  default boolean shouldGeneratePassword() {
    return false;
  }

  boolean isAbsent();

  String getAbsenceMessage();

  boolean isTeamConsultant();

  boolean isLanguageFormal();

  default LocalDateTime getCreateDate() {
    return nowInUtc();
  }

  default LocalDateTime getUpdateDate() {
    return nowInUtc();
  }

  Long getTenantId();

  default List<Long> getTopicIds() {
    return null;
  }

  default List<Long> getAgencyIds() {
    return null;
  }
}
