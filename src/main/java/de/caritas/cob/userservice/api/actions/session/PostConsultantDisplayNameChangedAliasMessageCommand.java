package de.caritas.cob.userservice.api.actions.session;

import static de.caritas.cob.userservice.api.model.Session.SessionStatus.DONE;
import static de.caritas.cob.userservice.api.model.Session.SessionStatus.IN_ARCHIVE;
import static de.caritas.cob.userservice.api.model.Session.SessionStatus.IN_PROGRESS;
import static de.caritas.cob.userservice.messageservice.generated.web.model.MessageType.CONSULTANT_DISPLAY_NAME_CHANGED;
import static java.util.Objects.nonNull;

import de.caritas.cob.userservice.api.actions.ActionCommand;
import de.caritas.cob.userservice.api.config.apiclient.MessageServiceApiControllerFactory;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.httpheader.TenantHeaderSupplier;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Action to post a {@code CONSULTANT_DISPLAY_NAME_CHANGED} alias message into all sessions
 * (IN_PROGRESS, DONE, IN_ARCHIVE) of a consultant via the message service.
 */
@Slf4j
@Component
public class PostConsultantDisplayNameChangedAliasMessageCommand extends AliasMessageCommand
    implements ActionCommand<Consultant> {

  private static final List<SessionStatus> RELEVANT_STATUSES =
      List.of(IN_PROGRESS, DONE, IN_ARCHIVE);

  private final SessionRepository sessionRepository;

  public PostConsultantDisplayNameChangedAliasMessageCommand(
      MessageServiceApiControllerFactory messageServiceApiControllerFactory,
      SecurityHeaderSupplier securityHeaderSupplier,
      TenantHeaderSupplier tenantHeaderSupplier,
      IdentityClient identityClient,
      IdentityClientConfig identityClientConfig,
      SessionRepository sessionRepository) {
    super(
        messageServiceApiControllerFactory,
        securityHeaderSupplier,
        tenantHeaderSupplier,
        identityClient,
        identityClientConfig);
    this.sessionRepository = sessionRepository;
  }

  /**
   * Posts a {@code CONSULTANT_DISPLAY_NAME_CHANGED} alias message into all relevant sessions of the
   * given consultant.
   *
   * @param consultant the consultant whose display name has changed
   */
  @Override
  public void execute(Consultant consultant) {
    if (nonNull(consultant)) {
      sessionRepository
          .findByConsultantAndStatusIn(consultant, RELEVANT_STATUSES)
          .forEach(
              session ->
                  postAliasOnlyMessage(session.getGroupId(), CONSULTANT_DISPLAY_NAME_CHANGED));
    }
  }
}
