package de.caritas.cob.userservice.api.config.auth;

import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Wires staged task selection without adding infrastructure to the legacy value configuration. */
@Component
@Primary
public class TaskAwareIdentityClientConfig implements IdentityClientConfig {
  private final IdentityConfig legacy;
  private final Environment environment;
  private final ObjectProvider<TaskActorTokenBroker> broker;

  public TaskAwareIdentityClientConfig(
      IdentityConfig legacy, Environment environment, ObjectProvider<TaskActorTokenBroker> broker) {
    this.legacy = legacy;
    this.environment = environment;
    this.broker = broker;
  }

  @Override
  public boolean isFiveActorMode() {
    String mode = environment.getProperty("TASK_IDENTITY_MODEL", "LEGACY");
    if (!Set.of("LEGACY", "FIVE_ACTORS").contains(mode)) {
      throw new IllegalStateException("Unsupported task identity model");
    }
    return "FIVE_ACTORS".equals(mode);
  }

  @Override
  public String taskAccessToken(TaskPurpose purpose) {
    if (!isFiveActorMode()) {
      throw new IllegalStateException("Task actor authentication unavailable");
    }
    return broker.getObject().issue(purpose).accessToken();
  }

  @Override
  public String getErrorMessageDuplicatedEmail() {
    return legacy.getErrorMessageDuplicatedEmail();
  }

  @Override
  public String getErrorMessageDuplicatedUsername() {
    return legacy.getErrorMessageDuplicatedUsername();
  }

  @Override
  public String getOpenidConnectUrl() {
    return legacy.getOpenidConnectUrl();
  }

  @Override
  public String getOpenIdConnectUrl(String path) {
    return legacy.getOpenIdConnectUrl(path);
  }

  @Override
  public String getOpenIdConnectUrl(String path, String arg) {
    return legacy.getOpenIdConnectUrl(path, arg);
  }

  @Override
  public String getOtpUrl(String path, String arg) {
    return legacy.getOtpUrl(path, arg);
  }

  @Override
  public Boolean getOtpAllowedForUsers() {
    return legacy.getOtpAllowedForUsers();
  }

  @Override
  public Boolean getOtpAllowedForConsultants() {
    return legacy.getOtpAllowedForConsultants();
  }

  @Override
  public Boolean getOtpAllowedForSingleTenantAdmins() {
    return legacy.getOtpAllowedForSingleTenantAdmins();
  }

  @Override
  public Boolean getOtpAllowedForTenantSuperAdmins() {
    return legacy.getOtpAllowedForTenantSuperAdmins();
  }

  @Override
  public Boolean getDisplayNameAllowedForConsultants() {
    return legacy.getDisplayNameAllowedForConsultants();
  }

  @Override
  public TechnicalUserConfig getTechnicalUser() {
    return legacy.getTechnicalUser();
  }

  @Override
  public String getEmailDummySuffix() {
    return legacy.getEmailDummySuffix();
  }

  @Override
  public boolean isOtpAllowed(Set<String> roles) {
    return legacy.isOtpAllowed(roles);
  }
}
