package de.caritas.cob.userservice.api.admin.service.consultant.create;

import static org.apache.commons.lang3.BooleanUtils.isTrue;

import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantDTO;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Adapter class to provide a {@link ConsultantCreationInput} based on a {@link
 * CreateConsultantDTO}.
 */
@RequiredArgsConstructor
public class CreateConsultantDTOCreationInputAdapter implements ConsultantCreationInput {

  private final @NonNull CreateConsultantDTO createConsultantDTO;

  /**
   * Provides the old id.
   *
   * @return always null here
   */
  @Override
  public Long getIdOld() {
    return null;
  }

  /**
   * Provides the user name.
   *
   * @return the user name
   */
  @Override
  public String getUserName() {
    return this.createConsultantDTO.getUsername();
  }

  /**
   * Provides the encoded user name.
   *
   * @return the encoded user name
   */
  @Override
  public String getEncodedUsername() {
    return new UsernameTranscoder().encodeUsername(createConsultantDTO.getUsername());
  }

  /**
   * Whether the created counsellor must set up a second factor before using the account.
   *
   * <p>Always true on this path. Everything reaching this adapter was provisioned through the admin
   * API: an administrator chooses the initial password and passes it to the counsellor out of band,
   * so that password is not a secret only the counsellor holds. A second factor is what makes the
   * account theirs again (ORISO-Admin#955, ORISO-Frontend#1402).
   */
  @Override
  public boolean isTwoFactorRequired() {
    return true;
  }

  /**
   * Whether the created counsellor must replace their password before using the account.
   *
   * <p>Always true on this path, for the same reason as the second factor: the password in this
   * payload was typed by an administrator and reaches the counsellor over some other channel. A
   * second factor protects the account; only replacing the password makes it theirs.
   */
  @Override
  public boolean isPasswordChangeRequired() {
    return true;
  }

  /**
   * Provides the first name.
   *
   * @return the first name
   */
  @Override
  public String getFirstName() {
    return this.createConsultantDTO.getFirstname();
  }

  /**
   * Provides the last name.
   *
   * @return the last name
   */
  @Override
  public String getLastName() {
    return this.createConsultantDTO.getLastname();
  }

  /**
   * Provides the email address.
   *
   * @return the email address
   */
  @Override
  public String getEmail() {
    return this.createConsultantDTO.getEmail();
  }

  @Override
  public String getPublicSlug() {
    return this.createConsultantDTO.getPublicSlug();
  }

  @Override
  public String getDisplayName() {
    return this.createConsultantDTO.getDisplayName();
  }

  @Override
  public String getInternalDisplayName() {
    return this.createConsultantDTO.getInternalDisplayName();
  }

  @Override
  public String getSalutation() {
    return this.createConsultantDTO.getSalutation();
  }

  @Override
  public String getPosition() {
    return this.createConsultantDTO.getPosition();
  }

  @Override
  public String getTitle() {
    return this.createConsultantDTO.getTitle();
  }

  @Override
  public String getAdminRemarks() {
    return this.createConsultantDTO.getAdminRemarks();
  }

  @Override
  public String getAvatarKind() {
    var avatarKind = this.createConsultantDTO.getAvatarKind();
    return avatarKind == null ? null : avatarKind.getValue();
  }

  @Override
  public String getAvatarId() {
    return this.createConsultantDTO.getAvatarId();
  }

  /**
   * Provides the password.
   *
   * @return the password from DTO, or null if not provided
   */
  @Override
  public String getPassword() {
    return this.createConsultantDTO.getPassword();
  }

  /**
   * Provides the absent flag.
   *
   * @return the absent flag
   */
  @Override
  public boolean isAbsent() {
    return isTrue(this.createConsultantDTO.getAbsent());
  }

  /**
   * Provides the absence message.
   *
   * @return the absence message
   */
  @Override
  public String getAbsenceMessage() {
    return this.createConsultantDTO.getAbsenceMessage();
  }

  /**
   * Provides the team consultant flag.
   *
   * @return the team consultant flag
   */
  @Override
  public boolean isTeamConsultant() {
    return false;
  }

  /**
   * Provides the language formal flag.
   *
   * @return the language formal flag
   */
  @Override
  public boolean isLanguageFormal() {
    return isTrue(this.createConsultantDTO.getFormalLanguage());
  }

  /**
   * Provides the tenant id.
   *
   * @return the tenant id
   */
  @Override
  public Long getTenantId() {
    return this.createConsultantDTO.getTenantId();
  }

  /**
   * Provides the directly assigned topic ids.
   *
   * @return the topic ids
   */
  @Override
  public List<Long> getTopicIds() {
    return this.createConsultantDTO.getTopicIds();
  }

  /**
   * Provides the agencies assigned as part of consultant creation.
   *
   * @return the agency ids
   */
  @Override
  public List<Long> getAgencyIds() {
    return this.createConsultantDTO.getAgencyIds();
  }
}
