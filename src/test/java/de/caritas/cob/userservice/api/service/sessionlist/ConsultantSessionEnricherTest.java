package de.caritas.cob.userservice.api.service.sessionlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionDTO;
import de.caritas.cob.userservice.api.service.session.SessionTopicEnrichmentService;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ConsultantSessionEnricherTest {

  @InjectMocks private ConsultantSessionEnricher consultantSessionEnricher;
  @Mock private SessionTopicEnrichmentService sessionTopicEnrichmentService;

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(consultantSessionEnricher, "topicsFeatureEnabled", false);
    ReflectionTestUtils.setField(
        consultantSessionEnricher, "sessionTopicEnrichmentService", sessionTopicEnrichmentService);
  }

  @Test
  void marksDatabaseBackedSessionEntriesAsRead() {
    var session = new SessionDTO().messagesRead(false);
    var response = new ConsultantSessionResponseDTO().session(session);

    var result = consultantSessionEnricher.updateRequiredConsultantSessionValues(List.of(response));

    assertThat(result).containsExactly(response);
    assertThat(session.getMessagesRead()).isTrue();
  }

  @Test
  void preservesTheLatestMessageCalculatedFromDatabaseState() {
    var latestMessage = new Date(1_725_000_000_000L);
    var response =
        new ConsultantSessionResponseDTO()
            .session(new SessionDTO().messagesRead(false))
            .latestMessage(latestMessage);

    consultantSessionEnricher.updateRequiredConsultantSessionValues(List.of(response));

    assertThat(response.getLatestMessage()).isSameAs(latestMessage);
  }

  @Test
  void enrichesTopicsWhenTheFeatureIsEnabled() {
    ReflectionTestUtils.setField(consultantSessionEnricher, "topicsFeatureEnabled", true);
    var session = new SessionDTO();
    var response = new ConsultantSessionResponseDTO().session(session);

    consultantSessionEnricher.updateRequiredConsultantSessionValues(List.of(response));

    verify(sessionTopicEnrichmentService).enrichSessionWithTopicData(session);
  }

  @Test
  void skipsTopicEnrichmentWhenTheFeatureIsDisabled() {
    var response = new ConsultantSessionResponseDTO().session(new SessionDTO());

    consultantSessionEnricher.updateRequiredConsultantSessionValues(List.of(response));

    verifyNoInteractions(sessionTopicEnrichmentService);
  }

  @Test
  void oneInvalidEntryDoesNotPreventOtherEntriesFromBeingEnriched() {
    var invalid = new ConsultantSessionResponseDTO();
    var validSession = new SessionDTO().messagesRead(false);
    var valid = new ConsultantSessionResponseDTO().session(validSession);

    var result =
        consultantSessionEnricher.updateRequiredConsultantSessionValues(List.of(invalid, valid));

    assertThat(result).containsExactly(invalid, valid);
    assertThat(validSession.getMessagesRead()).isTrue();
  }
}
