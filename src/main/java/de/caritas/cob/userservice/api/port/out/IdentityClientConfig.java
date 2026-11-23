package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.config.auth.TaskPurpose;
import de.caritas.cob.userservice.api.config.auth.TechnicalUserConfig;
import jakarta.validation.constraints.NotNull;
import java.util.Set;

public interface IdentityClientConfig {

  /** Staged source selection; the production preflight remains a separate full-cutover gate. */
  default boolean isFiveActorMode() {
    return false;
  }

  default String taskAccessToken(TaskPurpose purpose) {
    throw new IllegalStateException("Task actor authentication unavailable");
  }

  String getErrorMessageDuplicatedEmail();

  String getErrorMessageDuplicatedUsername();

  String getOpenidConnectUrl();

  String getOpenIdConnectUrl(String path);

  String getOpenIdConnectUrl(String path, String arg);

  String getOtpUrl(String path, String arg);

  Boolean getOtpAllowedForUsers();

  Boolean getOtpAllowedForConsultants();

  Boolean getOtpAllowedForSingleTenantAdmins();

  Boolean getOtpAllowedForTenantSuperAdmins();

  Boolean getDisplayNameAllowedForConsultants();

  TechnicalUserConfig getTechnicalUser();

  String getEmailDummySuffix();

  boolean isOtpAllowed(@NotNull Set<String> roles);
}
