package de.caritas.cob.userservice.api.testHelper;

import de.caritas.cob.userservice.tenantservice.generated.web.model.ChatRecoveryMode;
import de.caritas.cob.userservice.tenantservice.generated.web.model.ChatRecoverySettings;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.Settings;
import de.caritas.cob.userservice.tenantservice.generated.web.model.TenantAdminControls;

public final class ChatRecoveryPolicyFixtures {
  private ChatRecoveryPolicyFixtures() {}

  public static RestrictedTenantDTO tenant() {
    return new RestrictedTenantDTO()
        .id(1L)
        .settings(
            new Settings()
                .tenantAdminControls(
                    new TenantAdminControls()
                        .chatRecoverySettings(
                            new ChatRecoverySettings()
                                .asker(ChatRecoveryMode.LOGIN_PASSWORD)
                                .consultant(ChatRecoveryMode.LOGIN_PASSWORD)
                                .revision(0L))));
  }
}
