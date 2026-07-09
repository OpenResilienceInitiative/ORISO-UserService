package de.caritas.cob.userservice.api.conversation.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionListResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionResponseDTO;
import de.caritas.cob.userservice.api.conversation.model.ConversationListType;
import de.caritas.cob.userservice.api.conversation.model.PageableListRequest;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.service.sessionlist.ConsultantSessionEnricher;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultConversationListProviderTest {

  @Mock private ConsultantSessionEnricher consultantSessionEnricher;

  private DefaultConversationListProvider provider;

  @BeforeEach
  void setUp() {
    when(consultantSessionEnricher.updateRequiredConsultantSessionValues(
            anyList(), anyString(), any()))
        .thenAnswer(inv -> inv.getArgument(0));

    provider =
        new DefaultConversationListProvider(consultantSessionEnricher) {
          @Override
          public ConsultantSessionListResponseDTO buildConversations(
              PageableListRequest pageableListRequest) {
            return null;
          }

          @Override
          public ConversationListType providedType() {
            return ConversationListType.REGISTERED_ENQUIRY;
          }
        };
  }

  // ---------------------------------------------------------------------------
  // obtainPageByOffsetAndCount — default interface method
  // ---------------------------------------------------------------------------

  @Test
  void obtainPageByOffsetAndCount_Should_ThrowException_When_CountIsZero() {
    PageableListRequest request = PageableListRequest.builder().offset(0).count(0).build();

    assertThrows(
        InternalServerErrorException.class, () -> provider.obtainPageByOffsetAndCount(request));
  }

  @Test
  void obtainPageByOffsetAndCount_Should_ThrowException_When_CountIsNegative() {
    PageableListRequest request = PageableListRequest.builder().offset(0).count(-1).build();

    assertThrows(
        InternalServerErrorException.class, () -> provider.obtainPageByOffsetAndCount(request));
  }

  @Test
  void obtainPageByOffsetAndCount_Should_ReturnCorrectPage_When_OffsetAndCountAreValid() {
    PageableListRequest request = PageableListRequest.builder().offset(20).count(10).build();

    int page = provider.obtainPageByOffsetAndCount(request);

    assertThat(page).isEqualTo(2);
  }

  @Test
  void obtainPageByOffsetAndCount_Should_ReturnZero_When_OffsetIsZero() {
    PageableListRequest request = PageableListRequest.builder().offset(0).count(10).build();

    int page = provider.obtainPageByOffsetAndCount(request);

    assertThat(page).isEqualTo(0);
  }

  // ---------------------------------------------------------------------------
  // buildConversations (protected)
  // ---------------------------------------------------------------------------

  @Test
  void buildConversations_Should_ReturnResponseWithSessions_When_SessionsProvided() {
    List<ConsultantSessionResponseDTO> sessions = List.of(new ConsultantSessionResponseDTO());
    PageableListRequest request =
        PageableListRequest.builder().offset(0).count(10).rcToken("token").build();
    Consultant consultant = new Consultant();

    ConsultantSessionListResponseDTO result =
        provider.buildConversations(request, consultant, sessions);

    assertThat(result).isNotNull();
    assertThat(result.getSessions()).isNotNull();
    assertThat(result.getTotal()).isEqualTo(1);
  }

  @Test
  void buildConversations_Should_ReturnEmptyList_When_NoSessionsProvided() {
    PageableListRequest request =
        PageableListRequest.builder().offset(0).count(10).rcToken("token").build();
    Consultant consultant = new Consultant();

    ConsultantSessionListResponseDTO result =
        provider.buildConversations(request, consultant, new ArrayList<>());

    assertThat(result).isNotNull();
    assertThat(result.getSessions()).isEmpty();
    assertThat(result.getTotal()).isEqualTo(0);
  }

  @Test
  void buildConversations_Should_CallEnricher_With_CorrectArgs() {
    List<ConsultantSessionResponseDTO> sessions = new ArrayList<>();
    sessions.add(new ConsultantSessionResponseDTO());
    PageableListRequest request =
        PageableListRequest.builder().offset(0).count(10).rcToken("rc-token-123").build();
    Consultant consultant = new Consultant();
    ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);

    provider.buildConversations(request, consultant, sessions);

    verify(consultantSessionEnricher)
        .updateRequiredConsultantSessionValues(anyList(), tokenCaptor.capture(), any());
    assertThat(tokenCaptor.getValue()).isEqualTo("rc-token-123");
  }

  @Test
  void buildConversations_Should_ApplyPagination_When_OffsetAndCountSet() {
    List<ConsultantSessionResponseDTO> sessions = new ArrayList<>();
    for (int i = 0; i < 25; i++) {
      sessions.add(new ConsultantSessionResponseDTO());
    }
    PageableListRequest request =
        PageableListRequest.builder().offset(10).count(10).rcToken("token").build();
    Consultant consultant = new Consultant();

    ConsultantSessionListResponseDTO result =
        provider.buildConversations(request, consultant, sessions);

    assertThat(result.getOffset()).isEqualTo(10);
    assertThat(result.getTotal()).isEqualTo(25);
    assertThat(result.getCount()).isEqualTo(10);
  }

  @Test
  void buildConversations_Should_ReturnOffsetInResponse_When_OffsetIsSet() {
    PageableListRequest request =
        PageableListRequest.builder().offset(5).count(5).rcToken("token").build();
    Consultant consultant = new Consultant();

    ConsultantSessionListResponseDTO result =
        provider.buildConversations(request, consultant, new ArrayList<>());

    assertThat(result.getOffset()).isEqualTo(5);
  }
}
