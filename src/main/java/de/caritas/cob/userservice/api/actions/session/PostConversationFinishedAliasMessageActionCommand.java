package de.caritas.cob.userservice.api.actions.session;

import static de.caritas.cob.userservice.messageservice.generated.web.model.MessageType.FINISHED_CONVERSATION;
import static java.util.Objects.nonNull;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import de.caritas.cob.userservice.api.actions.ActionCommand;
import de.caritas.cob.userservice.api.config.apiclient.MessageServiceApiControllerFactory;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.httpheader.TenantHeaderSupplier;
import de.caritas.cob.userservice.messageservice.generated.web.model.AliasOnlyMessageDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Action to post a conversation finished alias message in rocket chat via the message service. */
@Slf4j
@Component
public class PostConversationFinishedAliasMessageActionCommand extends AliasMessageCommand
    implements ActionCommand<Session> {

  public PostConversationFinishedAliasMessageActionCommand(
      MessageServiceApiControllerFactory messageServiceApiControllerFactory,
      SecurityHeaderSupplier securityHeaderSupplier,
      TenantHeaderSupplier tenantHeaderSupplier,
      IdentityClient identityClient,
      IdentityClientConfig identityClientConfig) {
    super(
        messageServiceApiControllerFactory,
        securityHeaderSupplier,
        tenantHeaderSupplier,
        identityClient,
        identityClientConfig);
  }

  /**
   * Posts a {@link AliasOnlyMessageDTO} with type finished conversation into rocket chat.
   *
   * @param actionTarget the session containing the rocket chat group id
   */
  @Override
  public void execute(Session actionTarget) {
    if (nonNull(actionTarget) && isNotBlank(actionTarget.getGroupId())) {
      postAliasOnlyMessage(actionTarget.getGroupId(), FINISHED_CONVERSATION);
    }
  }
}
