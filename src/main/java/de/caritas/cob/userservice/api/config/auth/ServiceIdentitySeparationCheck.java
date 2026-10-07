package de.caritas.cob.userservice.api.config.auth;

import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakCustomConfig;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import jakarta.annotation.PostConstruct;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Task clients and proof keys stay distinct from each other and the human client. */
@Component
@RequiredArgsConstructor
public class ServiceIdentitySeparationCheck {
  private final @NonNull KeycloakCustomConfig keycloakCustomConfig;
  private final @NonNull IdentityClientConfig identityClientConfig;

  @org.springframework.beans.factory.annotation.Value("${identity.consultant-import.client-id:}")
  private String consultantImportClientId;

  @org.springframework.beans.factory.annotation.Value(
      "${identity.consultant-import.service-subject:}")
  private String consultantImportSubject;

  @org.springframework.beans.factory.annotation.Value("${oriso.commands.provisioning-origin-key:}")
  private String provisioningOriginKey;

  @org.springframework.beans.factory.annotation.Value("${oriso.commands.maintenance-origin-key:}")
  private String maintenanceOriginKey;

  @org.springframework.beans.factory.annotation.Value(
      "${oriso.commands.wizard-policy-context-key:}")
  private String wizardPolicyContextKey;

  @PostConstruct
  public void verifyBackendClients() {
    if (blank(keycloakCustomConfig.getAppClientId()))
      throw new IllegalStateException(
          "Configure the human application client separately from task identities");
    var clients = new java.util.HashSet<String>();
    var secrets = new java.util.HashSet<String>();
    var subjects = new java.util.HashSet<String>();
    for (var task : TaskIdentity.values()) {
      var configured = identityClientConfig.getTaskIdentity(task);
      if (configured == null
          || blank(configured.getClientId())
          || blank(configured.getClientSecret())
          || blank(configured.getServiceSubject())
          || configured.getClientId().equals(keycloakCustomConfig.getAppClientId())
          || !clients.add(configured.getClientId())
          || !secrets.add(configured.getClientSecret())
          || !subjects.add(configured.getServiceSubject()))
        throw new IllegalStateException(
            "Configure distinct task clients, subjects and secrets, separate from the human app client");
    }
    if (blank(consultantImportClientId)
        || blank(consultantImportSubject)
        || consultantImportClientId.equals(keycloakCustomConfig.getAppClientId())
        || clients.contains(consultantImportClientId)
        || subjects.contains(consultantImportSubject))
      throw new IllegalStateException(
          "Configure a separate incoming consultant-import client and subject");
    byte[] provisioning = key(provisioningOriginKey);
    byte[] maintenance = key(maintenanceOriginKey);
    byte[] wizard = key(wizardPolicyContextKey);
    if (java.security.MessageDigest.isEqual(provisioning, maintenance)
        || java.security.MessageDigest.isEqual(provisioning, wizard)
        || java.security.MessageDigest.isEqual(maintenance, wizard)
        || secrets.stream()
            .anyMatch(
                secret ->
                    sameSecret(secret, provisioningOriginKey, provisioning)
                        || sameSecret(secret, maintenanceOriginKey, maintenance)
                        || sameSecret(secret, wizardPolicyContextKey, wizard)))
      throw new IllegalStateException(
          "Command origin keys must be distinct from task client credentials");
  }

  private static byte[] key(String encoded) {
    try {
      byte[] key = java.util.Base64.getDecoder().decode(encoded == null ? "" : encoded);
      if (key.length < 32) throw new IllegalArgumentException();
      return key;
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException(
          "Configure distinct managed command origin keys of at least 256 bits");
    }
  }

  private static boolean sameSecret(String secret, String encoded, byte[] decoded) {
    return secret.equals(encoded)
        || java.security.MessageDigest.isEqual(
            secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), decoded);
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
