package de.caritas.cob.userservice.api.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionTopicDTO;
import de.caritas.cob.userservice.api.service.consultingtype.TopicService;
import de.caritas.cob.userservice.topicservice.generated.web.model.TopicDTO;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.assertj.core.util.Maps;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConsultantSessionTopicEnrichmentServiceTest {

  @InjectMocks ConsultantSessionTopicEnrichmentService sessionTopicEnrichmentService;

  @Mock TopicService topicService;

  @Test
  void enrichSessionWithTopicData_Should_LoadTopicsOnceForMainTopicAndTopicList() {
    givenAllTopicsMap();
    var session =
        new ConsultantSessionDTO()
            .mainTopic(new SessionTopicDTO().id(1L))
            .topics(List.of(new SessionTopicDTO().id(2L)));

    sessionTopicEnrichmentService.enrichSessionWithTopicData(session);

    verify(topicService, times(1)).getAllTopicsMap();
    assertThat(session.getMainTopic().getName()).isEqualTo("first topic");
    assertThat(session.getTopics())
        .singleElement()
        .extracting(SessionTopicDTO::getName)
        .isEqualTo("second topic");
  }

  @Test
  void enrichSessionWithTopicData_Should_NotLoadTopicsWithoutTopicIdentifiers() {
    var session = new ConsultantSessionDTO().mainTopic(null).topics(Collections.emptyList());

    sessionTopicEnrichmentService.enrichSessionWithTopicData(session);

    verify(topicService, never()).getAllTopicsMap();
  }

  @Test
  void enrichSessionWithTopicData_Should_EnrichMainTopicFromTopicService() {
    // given
    givenAllTopicsMap();
    var session = new ConsultantSessionDTO().mainTopic(new SessionTopicDTO().id(1L));

    // when
    sessionTopicEnrichmentService.enrichSessionWithTopicData(session);

    // then
    assertThat(session.getMainTopic().getId()).isEqualTo(1);
    assertThat(session.getMainTopic().getName()).isEqualTo("first topic");
    assertThat(session.getMainTopic().getDescription()).isEqualTo("first desc");
  }

  @Test
  void enrichSessionWithTopicData_Should_NotEnrichMainTopicIfNoMatchingTopicIsFound() {
    // given
    givenAllTopicsMap();
    var session = new ConsultantSessionDTO().mainTopic(new SessionTopicDTO().id(3L));

    // when
    sessionTopicEnrichmentService.enrichSessionWithTopicData(session);

    // then
    assertThat(session.getMainTopic().getId()).isEqualTo(3);
    assertThat(session.getMainTopic().getName()).isNull();
    assertThat(session.getMainTopic().getDescription()).isNull();
  }

  @Test
  void enrichSessionWithTopicData_Should_NotLoadTopicDataIfMainTopicIsNull() {
    // given
    var session = new ConsultantSessionDTO().mainTopic(null);

    // when
    sessionTopicEnrichmentService.enrichSessionWithTopicData(session);

    // then
    verify(topicService, never()).getAllTopics();
    assertThat(session.getMainTopic()).isNull();
  }

  @Test
  void enrichSessionWithTopicData_Should_NotLoadTopicDataIfMainTopicIdIsNotSet() {
    // given
    var session = new ConsultantSessionDTO().mainTopic(new SessionTopicDTO());

    // when
    sessionTopicEnrichmentService.enrichSessionWithTopicData(session);

    // then
    verify(topicService, never()).getAllTopics();
    assertThat(session.getMainTopic().getId()).isNull();
  }

  @Test
  void enrichSessionWithTopicData_Should_EnrichTopicListFromTopicService() {
    // given
    givenAllTopicsMap();
    var session =
        new ConsultantSessionDTO()
            .topics(List.of(new SessionTopicDTO().id(1L), new SessionTopicDTO().id(2L)));

    // when
    sessionTopicEnrichmentService.enrichSessionWithTopicData(session);

    // then
    assertThat(session.getTopics().get(0).getId()).isEqualTo(1);
    assertThat(session.getTopics().get(0).getName()).isEqualTo("first topic");
    assertThat(session.getTopics().get(0).getDescription()).isEqualTo("first desc");

    assertThat(session.getTopics().get(1).getId()).isEqualTo(2);
    assertThat(session.getTopics().get(1).getName()).isEqualTo("second topic");
    assertThat(session.getTopics().get(1).getDescription()).isEqualTo("second desc");
  }

  @Test
  void enrichSessionWithTopicData_Should_DropUnmatchedTopicListEntries() {
    // given
    givenAllTopicsMap();
    var session =
        new ConsultantSessionDTO()
            .topics(
                List.of(
                    new SessionTopicDTO().id(1L),
                    new SessionTopicDTO().id(2L),
                    new SessionTopicDTO().id(3L)));

    // when
    sessionTopicEnrichmentService.enrichSessionWithTopicData(session);

    // then
    assertThat(session.getTopics()).size().isEqualTo(2);
    assertThat(session.getTopics().get(0).getId()).isEqualTo(1);
    assertThat(session.getTopics().get(1).getId()).isEqualTo(2);
  }

  @Test
  void enrichSessionWithTopicData_Should_NotLoadTopicDataIfTopicListIsNull() {
    // given
    var session = new ConsultantSessionDTO().topics(null);

    // when
    sessionTopicEnrichmentService.enrichSessionWithTopicData(session);

    // then
    verify(topicService, never()).getAllTopics();
    assertThat(session.getTopics()).isNull();
  }

  @Test
  void enrichSessionWithTopicData_Should_NotLoadTopicDataIfTopicListIsEmpty() {
    // given
    var session = new ConsultantSessionDTO().topics(Collections.emptyList());

    // when
    sessionTopicEnrichmentService.enrichSessionWithTopicData(session);

    // then
    verify(topicService, never()).getAllTopics();
    assertThat(session.getTopics()).isEmpty();
  }

  private void givenAllTopicsMap() {
    Map<Long, TopicDTO> availableTopicsMap =
        Maps.newHashMap(1L, new TopicDTO().id(1L).name("first topic").description("first desc"));
    availableTopicsMap.put(
        2L, new TopicDTO().id(2L).name("second topic").description("second desc"));
    when(topicService.getAllTopicsMap()).thenReturn(availableTopicsMap);
  }
}
