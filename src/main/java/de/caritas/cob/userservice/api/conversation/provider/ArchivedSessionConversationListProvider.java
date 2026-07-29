package de.caritas.cob.userservice.api.conversation.provider;

import static de.caritas.cob.userservice.api.conversation.model.ConversationListType.ARCHIVED_SESSION;

import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionListResponseDTO;
import de.caritas.cob.userservice.api.conversation.model.ConversationListType;
import de.caritas.cob.userservice.api.conversation.model.PageableListRequest;
import de.caritas.cob.userservice.api.service.session.ConsultantSessionQueryService;
import de.caritas.cob.userservice.api.service.sessionlist.ConsultantSessionEnricher;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import lombok.NonNull;
import org.springframework.stereotype.Service;

/** {@link ConversationListProvider} to provide archived session conversations. */
@Service
public class ArchivedSessionConversationListProvider extends DefaultConversationListProvider {

  private final ConsultantSessionQueryService consultantSessionQueryService;
  private final UserAccountService userAccountProvider;

  public ArchivedSessionConversationListProvider(
      @NonNull UserAccountService userAccountProvider,
      @NonNull ConsultantSessionEnricher consultantSessionEnricher,
      @NonNull ConsultantSessionQueryService consultantSessionQueryService) {
    super(consultantSessionEnricher);
    this.consultantSessionQueryService = consultantSessionQueryService;
    this.userAccountProvider = userAccountProvider;
  }

  /** {@inheritDoc} */
  @Override
  public ConsultantSessionListResponseDTO buildConversations(
      PageableListRequest pageableListRequest) {
    var consultant = this.userAccountProvider.retrieveValidatedConsultant();

    return buildConversations(
        pageableListRequest,
        consultant,
        consultantSessionQueryService.getArchivedSessionsForConsultant(consultant));
  }

  /** {@inheritDoc} */
  @Override
  public ConversationListType providedType() {
    return ARCHIVED_SESSION;
  }
}
