package de.caritas.cob.userservice.api.conversation.provider;

import static de.caritas.cob.userservice.api.conversation.model.ConversationListType.REGISTERED_ENQUIRY;

import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionListResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionResponseDTO;
import de.caritas.cob.userservice.api.conversation.model.ConversationListType;
import de.caritas.cob.userservice.api.conversation.model.PageableListRequest;
import de.caritas.cob.userservice.api.port.out.ConsultantTopicRepository;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.service.sessionlist.ConsultantSessionEnricher;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import java.util.List;
import java.util.stream.Collectors;
import lombok.NonNull;
import org.springframework.stereotype.Service;

/** {@link ConversationListProvider} to provide registered enquiry conversations. */
@Service
public class RegisteredEnquiryConversationListProvider extends DefaultConversationListProvider {

  private final @NonNull UserAccountService userAccountProvider;
  private final @NonNull SessionService sessionService;
  private final @NonNull ConsultantTopicRepository consultantTopicRepository;

  public RegisteredEnquiryConversationListProvider(
      @NonNull UserAccountService userAccountProvider,
      @NonNull ConsultantSessionEnricher consultantSessionEnricher,
      @NonNull SessionService sessionService,
      @NonNull ConsultantTopicRepository consultantTopicRepository) {
    super(consultantSessionEnricher);
    this.sessionService = sessionService;
    this.userAccountProvider = userAccountProvider;
    this.consultantTopicRepository = consultantTopicRepository;
  }

  /** {@inheritDoc} */
  @Override
  public ConsultantSessionListResponseDTO buildConversations(PageableListRequest request) {
    var consultant = this.userAccountProvider.retrieveValidatedConsultant();
    var registeredEnquiries = sessionService.getRegisteredEnquiriesForConsultant(consultant);
    List<Long> consultantTopicIds =
        consultantTopicRepository.findTopicIdsByConsultantId(consultant.getId());
    var filteredEnquiries = filterByConsultantTopics(registeredEnquiries, consultantTopicIds);

    return buildConversations(request, consultant, filteredEnquiries);
  }

  private List<ConsultantSessionResponseDTO> filterByConsultantTopics(
      List<ConsultantSessionResponseDTO> enquiries, List<Long> consultantTopicIds) {
    if (consultantTopicIds == null || consultantTopicIds.isEmpty()) {
      return enquiries;
    }

    return enquiries.stream()
        .filter(
            enquiry -> {
              Long mainTopicId = extractMainTopicId(enquiry);
              return mainTopicId == null || consultantTopicIds.contains(mainTopicId);
            })
        .collect(Collectors.toList());
  }

  private Long extractMainTopicId(ConsultantSessionResponseDTO enquiry) {
    if (enquiry.getSession() == null || enquiry.getSession().getTopic() == null) {
      return null;
    }
    return enquiry.getSession().getTopic().getId();
  }

  /** {@inheritDoc} */
  @Override
  public ConversationListType providedType() {
    return REGISTERED_ENQUIRY;
  }
}
