package de.caritas.cob.userservice.api.actions.session;

import static org.apache.commons.lang3.exception.ExceptionUtils.getStackTrace;

import de.caritas.cob.userservice.api.config.apiclient.MessageServiceApiControllerFactory;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.httpheader.TenantHeaderSupplier;
import de.caritas.cob.userservice.messageservice.generated.ApiClient;
import de.caritas.cob.userservice.messageservice.generated.web.model.AliasOnlyMessageDTO;
import de.caritas.cob.userservice.messageservice.generated.web.model.MessageType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Abstract base class for alias message action commands. Handles authentication headers and
 * posting.
 */
@Slf4j
@RequiredArgsConstructor
public abstract class AliasMessageCommand {

  protected final MessageServiceApiControllerFactory messageServiceApiControllerFactory;
  protected final SecurityHeaderSupplier securityHeaderSupplier;
  protected final TenantHeaderSupplier tenantHeaderSupplier;
  protected final IdentityClient identityClient;
  protected final IdentityClientConfig identityClientConfig;

  /**
   * Posts an alias only message to the given Rocket.Chat group.
   *
   * @param groupId the Rocket.Chat group id
   * @param messageType the {@link MessageType} to post
   */
  protected void postAliasOnlyMessage(String groupId, MessageType messageType) {
    postAliasOnlyMessages(List.of(groupId), messageType);
  }

  protected void postAliasOnlyMessages(List<String> groupIds, MessageType messageType) {
    try {
      var messageControllerApi = messageServiceApiControllerFactory.createControllerApi();
      addDefaultHeaders(messageControllerApi.getApiClient());
      groupIds.forEach(
          groupId ->
              messageControllerApi.saveAliasOnlyMessage(
                  groupId, new AliasOnlyMessageDTO().messageType(messageType)));
    } catch (Exception e) {
      log.error("Unable to post alias messages of type {} to groups {}", messageType, groupIds);
      log.error(getStackTrace(e));
    }
  }

  @SuppressWarnings("Duplicates")
  private void addDefaultHeaders(ApiClient apiClient) {
    var techUser = identityClientConfig.getTechnicalUser();
    var keycloakLogin = identityClient.loginUser(techUser.getUsername(), techUser.getPassword());
    var headers =
        securityHeaderSupplier.getKeycloakAndCsrfHttpHeaders(keycloakLogin.getAccessToken());
    tenantHeaderSupplier.addTenantHeader(headers);
    headers.forEach((key, value) -> apiClient.addDefaultHeader(key, value.iterator().next()));
  }
}
