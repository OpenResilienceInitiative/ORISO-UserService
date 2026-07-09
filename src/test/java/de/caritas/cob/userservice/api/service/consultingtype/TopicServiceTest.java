package de.caritas.cob.userservice.api.service.consultingtype;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.config.apiclient.TopicServiceApiControllerFactory;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.httpheader.TenantHeaderSupplier;
import de.caritas.cob.userservice.topicservice.generated.ApiClient;
import de.caritas.cob.userservice.topicservice.generated.web.TopicControllerApi;
import de.caritas.cob.userservice.topicservice.generated.web.model.TopicDTO;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TopicServiceTest {

  @Mock private TopicServiceApiControllerFactory topicServiceApiControllerFactory;
  @Mock private SecurityHeaderSupplier securityHeaderSupplier;
  @Mock private TenantHeaderSupplier tenantHeaderSupplier;

  @InjectMocks private TopicService service;

  private TopicControllerApi controllerApi;
  private ApiClient apiClient;

  @BeforeEach
  void setUp() {
    controllerApi = mock(TopicControllerApi.class);
    apiClient = mock(ApiClient.class);
    when(topicServiceApiControllerFactory.createControllerApi()).thenReturn(controllerApi);
    when(controllerApi.getApiClient()).thenReturn(apiClient);
    when(securityHeaderSupplier.getKeycloakAndCsrfHttpHeaders()).thenReturn(new HttpHeaders());
    doNothing().when(tenantHeaderSupplier).addTenantHeader(any(HttpHeaders.class));
  }

  // ─── getAllTopics ──────────────────────────────────────────────────────────

  @Test
  void getAllTopics_Should_ReturnTopics_From_ControllerApi() {
    List<TopicDTO> topics = List.of(buildTopic(1L, "Topic A"), buildTopic(2L, "Topic B"));
    when(controllerApi.getAllTopics()).thenReturn(topics);

    List<TopicDTO> result = service.getAllTopics();

    assertThat(result).hasSize(2);
    verify(securityHeaderSupplier).getKeycloakAndCsrfHttpHeaders();
  }

  @Test
  void getAllTopics_Should_ReturnEmptyList_When_ApiReturnsEmpty() {
    when(controllerApi.getAllTopics()).thenReturn(List.of());

    List<TopicDTO> result = service.getAllTopics();

    assertThat(result).isEmpty();
  }

  // ─── getAllActiveTopics ────────────────────────────────────────────────────

  @Test
  void getAllActiveTopics_Should_ReturnActiveTopics_Without_SecurityHeaders() {
    List<TopicDTO> topics = List.of(buildTopic(3L, "Active Topic"));
    when(controllerApi.getAllActiveTopics()).thenReturn(topics);

    List<TopicDTO> result = service.getAllActiveTopics();

    assertThat(result).hasSize(1);
    verify(securityHeaderSupplier, never()).getKeycloakAndCsrfHttpHeaders();
    verify(tenantHeaderSupplier).addTenantHeader(any(HttpHeaders.class));
  }

  @Test
  void getAllActiveTopics_Should_ReturnEmptyList_When_ApiReturnsEmpty() {
    when(controllerApi.getAllActiveTopics()).thenReturn(List.of());

    List<TopicDTO> result = service.getAllActiveTopics();

    assertThat(result).isEmpty();
  }

  // ─── getTopicById ─────────────────────────────────────────────────────────

  @Test
  void getTopicById_Should_ReturnNull_When_TopicIdIsNull() {
    TopicDTO result = service.getTopicById(null);

    assertThat(result).isNull();
    verify(topicServiceApiControllerFactory, never()).createControllerApi();
  }

  @Test
  void getTopicById_Should_ReturnTopic_When_TopicFound() {
    TopicDTO topic = buildTopic(5L, "Found Topic");
    when(controllerApi.getAllActiveTopics()).thenReturn(List.of(topic));

    TopicDTO result = service.getTopicById(5L);

    assertThat(result).isNotNull();
    assertThat(result.getId()).isEqualTo(5L);
  }

  @Test
  void getTopicById_Should_ReturnNull_When_TopicNotFound() {
    when(controllerApi.getAllActiveTopics()).thenReturn(List.of(buildTopic(5L, "Other")));

    TopicDTO result = service.getTopicById(99L);

    assertThat(result).isNull();
  }

  // ─── getAllTopicsMap ───────────────────────────────────────────────────────

  @Test
  void getAllTopicsMap_Should_ReturnMapByTopicId() {
    when(controllerApi.getAllTopics())
        .thenReturn(List.of(buildTopic(1L, "A"), buildTopic(2L, "B")));

    Map<Long, TopicDTO> result = service.getAllTopicsMap();

    assertThat(result).containsKeys(1L, 2L);
    assertThat(result.get(1L).getName()).isEqualTo("A");
  }

  @Test
  void getAllTopicsMap_Should_ReturnEmptyMap_When_ApiReturnsEmptyList() {
    when(controllerApi.getAllTopics()).thenReturn(List.of());

    Map<Long, TopicDTO> result = service.getAllTopicsMap();

    assertThat(result).isEmpty();
  }

  @Test
  void getAllTopicsMap_Should_ReturnEmptyMap_When_ApiReturnsNull() {
    when(controllerApi.getAllTopics()).thenReturn(null);

    Map<Long, TopicDTO> result = service.getAllTopicsMap();

    assertThat(result).isEmpty();
  }

  // ─── getAllActiveTopicsMap ─────────────────────────────────────────────────

  @Test
  void getAllActiveTopicsMap_Should_ReturnMapByTopicId() {
    when(controllerApi.getAllActiveTopics())
        .thenReturn(List.of(buildTopic(10L, "Active A"), buildTopic(20L, "Active B")));

    Map<Long, TopicDTO> result = service.getAllActiveTopicsMap();

    assertThat(result).containsKeys(10L, 20L);
  }

  @Test
  void getAllActiveTopicsMap_Should_ReturnEmptyMap_When_NoActiveTopics() {
    when(controllerApi.getAllActiveTopics()).thenReturn(List.of());

    Map<Long, TopicDTO> result = service.getAllActiveTopicsMap();

    assertThat(result).isEmpty();
  }

  // ─── findTopicsInternalAttributes ─────────────────────────────────────────

  @Test
  void findTopicsInternalAttributes_Should_ReturnEmptyList_When_TopicsListIsNull() {
    List<String> result = service.findTopicsInternalAttributes(null);

    assertThat(result).isEmpty();
    verify(topicServiceApiControllerFactory, never()).createControllerApi();
  }

  @Test
  void findTopicsInternalAttributes_Should_ReturnInternalIdentifiers_When_TopicsFound() {
    TopicDTO topic = buildTopic(1L, "Topic").internalIdentifier("INT-001");
    when(controllerApi.getAllActiveTopics()).thenReturn(List.of(topic));

    List<String> result = service.findTopicsInternalAttributes(List.of(1L));

    assertThat(result).containsExactly("INT-001");
  }

  @Test
  void findTopicsInternalAttributes_Should_ReturnBlankEntry_When_TopicNotFound() {
    when(controllerApi.getAllActiveTopics()).thenReturn(List.of(buildTopic(99L, "Other")));

    List<String> result = service.findTopicsInternalAttributes(List.of(1L));

    assertThat(result).containsExactly("");
  }

  @Test
  void findTopicsInternalAttributes_Should_ReturnBlankEntry_When_TopicIdIsNull() {
    when(controllerApi.getAllActiveTopics()).thenReturn(List.of());

    List<String> result = service.findTopicsInternalAttributes(Arrays.asList((Long) null));

    assertThat(result).containsExactly("");
  }

  @Test
  void findTopicsInternalAttributes_Should_MapMultipleTopics() {
    TopicDTO t1 = buildTopic(1L, "T1").internalIdentifier("A");
    TopicDTO t2 = buildTopic(2L, "T2").internalIdentifier("B");
    when(controllerApi.getAllActiveTopics()).thenReturn(List.of(t1, t2));

    List<String> result = service.findTopicsInternalAttributes(List.of(1L, 2L));

    assertThat(result).containsExactly("A", "B");
  }

  // ─── findTopicInternalIdentifier ──────────────────────────────────────────

  @Test
  void findTopicInternalIdentifier_Should_ReturnBlank_When_TopicIdIsNull() {
    String result = service.findTopicInternalIdentifier(null);

    assertThat(result).isEmpty();
    verify(topicServiceApiControllerFactory, never()).createControllerApi();
  }

  @Test
  void findTopicInternalIdentifier_Should_ReturnIdentifier_When_TopicFound() {
    TopicDTO topic = buildTopic(5L, "T5").internalIdentifier("INT-005");
    when(controllerApi.getAllActiveTopics()).thenReturn(List.of(topic));

    String result = service.findTopicInternalIdentifier(5L);

    assertThat(result).isEqualTo("INT-005");
  }

  @Test
  void findTopicInternalIdentifier_Should_ReturnBlank_When_TopicNotFound() {
    when(controllerApi.getAllActiveTopics()).thenReturn(List.of());

    String result = service.findTopicInternalIdentifier(99L);

    assertThat(result).isEmpty();
  }

  @Test
  void findTopicInternalIdentifier_Should_ReturnBlank_When_InternalIdentifierIsNull() {
    TopicDTO topic = buildTopic(5L, "T5"); // internalIdentifier not set → null
    when(controllerApi.getAllActiveTopics()).thenReturn(List.of(topic));

    String result = service.findTopicInternalIdentifier(5L);

    assertThat(result).isEmpty();
  }

  // ─── helpers ──────────────────────────────────────────────────────────────

  private TopicDTO buildTopic(Long id, String name) {
    return new TopicDTO().id(id).name(name);
  }
}
