package de.caritas.cob.userservice.api.service.consultingtype;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.Messenger;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantTopicRepository;
import de.caritas.cob.userservice.api.service.availability.ConsultantActivityRegistry;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TopicConsultantRoutingServiceTest {

  @Mock private ConsultantTopicRepository consultantTopicRepository;
  @Mock private ConsultantRepository consultantRepository;
  @Mock private Messenger messenger;
  @Mock private MatrixSynapseService matrixSynapseService;
  @Mock private ConsultantActivityRegistry consultantActivityRegistry;

  @InjectMocks private TopicConsultantRoutingService service;

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(service, "activeWindowMs", 120_000L);
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private Consultant consultant(String id, boolean absent) {
    Consultant c = new Consultant();
    c.setId(id);
    c.setAbsent(absent);
    return c;
  }

  private Consultant consultantWithMatrix(String id, String matrixUserId, boolean absent) {
    Consultant c = consultant(id, absent);
    c.setMatrixUserId(matrixUserId);
    return c;
  }

  private Consultant consultantWithRc(String id, String rcId, boolean absent) {
    Consultant c = consultant(id, absent);
    c.setRocketChatId(rcId);
    return c;
  }

  // ── findAvailableConsultantIds ────────────────────────────────────────────

  @Test
  void findAvailableConsultantIds_Should_ReturnEmpty_When_TopicIdIsNull() {
    List<String> result = service.findAvailableConsultantIds(null);

    assertThat(result).isEmpty();
    verify(consultantTopicRepository, never()).findConsultantIdsByTopicId(any());
  }

  @Test
  void findAvailableConsultantIds_Should_ReturnEmpty_When_NoConsultantsAssignedToTopic() {
    when(consultantTopicRepository.findConsultantIdsByTopicId(1L))
        .thenReturn(Collections.emptyList());

    List<String> result = service.findAvailableConsultantIds(1L);

    assertThat(result).isEmpty();
    verify(consultantRepository, never()).findAllByIdIn(any());
  }

  @Test
  void findAvailableConsultantIds_Should_ReturnEmpty_When_AllConsultantsAreAbsent() {
    when(consultantTopicRepository.findConsultantIdsByTopicId(1L)).thenReturn(List.of("c1", "c2"));
    when(consultantRepository.findAllByIdIn(List.of("c1", "c2")))
        .thenReturn(List.of(consultant("c1", true), consultant("c2", true)));

    List<String> result = service.findAvailableConsultantIds(1L);

    assertThat(result).isEmpty();
    verify(consultantActivityRegistry, never()).filterActive(any(), anyLong());
  }

  @Test
  void findAvailableConsultantIds_Should_ReturnEmpty_When_NoneActiveInRegistry() {
    when(consultantTopicRepository.findConsultantIdsByTopicId(1L)).thenReturn(List.of("c1"));
    when(consultantRepository.findAllByIdIn(List.of("c1")))
        .thenReturn(List.of(consultant("c1", false)));
    when(consultantActivityRegistry.filterActive(List.of("c1"), 120_000L))
        .thenReturn(Collections.emptySet());

    List<String> result = service.findAvailableConsultantIds(1L);

    assertThat(result).isEmpty();
  }

  @Test
  void findAvailableConsultantIds_Should_ReturnActiveConsultants() {
    when(consultantTopicRepository.findConsultantIdsByTopicId(5L))
        .thenReturn(List.of("c1", "c2", "c3"));
    when(consultantRepository.findAllByIdIn(List.of("c1", "c2", "c3")))
        .thenReturn(
            List.of(
                consultant("c1", false),
                consultant("c2", true), // absent — filtered out
                consultant("c3", false)));
    when(consultantActivityRegistry.filterActive(List.of("c1", "c3"), 120_000L))
        .thenReturn(Set.of("c1"));

    List<String> result = service.findAvailableConsultantIds(5L);

    assertThat(result).containsExactly("c1");
  }

  @Test
  void findAvailableConsultantIds_Should_ExcludeNullConsultantsFromRepo() {
    when(consultantTopicRepository.findConsultantIdsByTopicId(1L)).thenReturn(List.of("c1"));
    // repo may return null entries in some edge cases
    when(consultantRepository.findAllByIdIn(List.of("c1")))
        .thenReturn(Collections.singletonList(null));

    List<String> result = service.findAvailableConsultantIds(1L);

    assertThat(result).isEmpty();
  }

  // ── findEligibleConsultantIds ─────────────────────────────────────────────

  @Test
  void findEligibleConsultantIds_Should_ReturnEmpty_When_TopicIdIsNull() {
    List<String> result = service.findEligibleConsultantIds(null, 1);

    assertThat(result).isEmpty();
    verify(consultantTopicRepository, never()).findConsultantIdsByTopicId(any());
  }

  @Test
  void findEligibleConsultantIds_Should_ReturnEmpty_When_NoConsultantsAssignedToTopic() {
    when(consultantTopicRepository.findConsultantIdsByTopicId(2L))
        .thenReturn(Collections.emptyList());

    List<String> result = service.findEligibleConsultantIds(2L, 1);

    assertThat(result).isEmpty();
  }

  @Test
  void findEligibleConsultantIds_Should_ReturnEmpty_When_AllConsultantsAbsent() {
    when(consultantTopicRepository.findConsultantIdsByTopicId(3L)).thenReturn(List.of("c1"));
    when(consultantRepository.findAllByIdIn(List.of("c1")))
        .thenReturn(List.of(consultant("c1", true)));

    List<String> result = service.findEligibleConsultantIds(3L, 1);

    assertThat(result).isEmpty();
    verify(matrixSynapseService, never()).findOnlineMatrixUserIds(any());
  }

  @Test
  void
      findEligibleConsultantIds_Should_ReturnMatrixOnlineConsultants_When_MatrixPresentAndMatches() {
    Consultant c1 = consultantWithMatrix("c1", "@c1:matrix.org", false);
    Consultant c2 = consultantWithMatrix("c2", "@c2:matrix.org", false);
    when(consultantTopicRepository.findConsultantIdsByTopicId(4L)).thenReturn(List.of("c1", "c2"));
    when(consultantRepository.findAllByIdIn(List.of("c1", "c2"))).thenReturn(List.of(c1, c2));
    when(matrixSynapseService.findOnlineMatrixUserIds(List.of("@c1:matrix.org", "@c2:matrix.org")))
        .thenReturn(Optional.of(Set.of("@c1:matrix.org")));

    List<String> result = service.findEligibleConsultantIds(4L, 10);

    assertThat(result).containsExactly("c1");
    verify(messenger, never()).findAvailableConsultants(any(int.class));
  }

  @Test
  void findEligibleConsultantIds_Should_ReturnEmpty_When_MatrixPresentButNobodyOnline() {
    Consultant c1 = consultantWithMatrix("c1", "@c1:matrix.org", false);
    when(consultantTopicRepository.findConsultantIdsByTopicId(4L)).thenReturn(List.of("c1"));
    when(consultantRepository.findAllByIdIn(List.of("c1"))).thenReturn(List.of(c1));
    when(matrixSynapseService.findOnlineMatrixUserIds(List.of("@c1:matrix.org")))
        .thenReturn(Optional.of(Collections.emptySet()));

    List<String> result = service.findEligibleConsultantIds(4L, 10);

    // Matrix said nobody online — authoritative empty, do NOT fall back
    assertThat(result).isEmpty();
  }

  @Test
  void
      findEligibleConsultantIds_Should_FallbackToActiveIds_When_MatrixUnavailableAndConsultingTypeNull() {
    Consultant c1 = consultantWithMatrix("c1", "@c1:matrix.org", false);
    when(consultantTopicRepository.findConsultantIdsByTopicId(5L)).thenReturn(List.of("c1"));
    when(consultantRepository.findAllByIdIn(List.of("c1"))).thenReturn(List.of(c1));
    when(matrixSynapseService.findOnlineMatrixUserIds(any())).thenReturn(Optional.empty());

    List<String> result = service.findEligibleConsultantIds(5L, null);

    assertThat(result).containsExactly("c1");
    verify(messenger, never()).findAvailableConsultants(any(int.class));
  }

  @Test
  void findEligibleConsultantIds_Should_FallbackToActiveIds_When_MessengerThrowsException() {
    Consultant c1 = consultantWithMatrix("c1", "@c1:matrix.org", false);
    when(consultantTopicRepository.findConsultantIdsByTopicId(6L)).thenReturn(List.of("c1"));
    when(consultantRepository.findAllByIdIn(List.of("c1"))).thenReturn(List.of(c1));
    when(matrixSynapseService.findOnlineMatrixUserIds(any())).thenReturn(Optional.empty());
    when(messenger.findAvailableConsultants(99)).thenThrow(new RuntimeException("RC down"));

    List<String> result = service.findEligibleConsultantIds(6L, 99);

    assertThat(result).containsExactly("c1");
  }

  @Test
  void findEligibleConsultantIds_Should_FallbackToActiveIds_When_MessengerReturnsEmptySet() {
    Consultant c1 = consultantWithMatrix("c1", null, false);
    when(consultantTopicRepository.findConsultantIdsByTopicId(7L)).thenReturn(List.of("c1"));
    when(consultantRepository.findAllByIdIn(List.of("c1"))).thenReturn(List.of(c1));
    when(matrixSynapseService.findOnlineMatrixUserIds(any())).thenReturn(Optional.empty());
    when(messenger.findAvailableConsultants(5)).thenReturn(Collections.emptySet());

    List<String> result = service.findEligibleConsultantIds(7L, 5);

    assertThat(result).containsExactly("c1");
  }

  @Test
  void findEligibleConsultantIds_Should_ReturnIntersection_When_MessengerMatchesByConsultantId() {
    Consultant c1 = consultant("c1", false);
    Consultant c2 = consultant("c2", false);
    when(consultantTopicRepository.findConsultantIdsByTopicId(8L)).thenReturn(List.of("c1", "c2"));
    when(consultantRepository.findAllByIdIn(List.of("c1", "c2"))).thenReturn(List.of(c1, c2));
    when(matrixSynapseService.findOnlineMatrixUserIds(any())).thenReturn(Optional.empty());
    when(messenger.findAvailableConsultants(3)).thenReturn(Set.of("c1"));

    List<String> result = service.findEligibleConsultantIds(8L, 3);

    assertThat(result).containsExactly("c1");
  }

  @Test
  void findEligibleConsultantIds_Should_MatchByRocketChatId_When_MessengerUsesRcIds() {
    Consultant c1 = consultantWithRc("c1", "rc-c1", false);
    when(consultantTopicRepository.findConsultantIdsByTopicId(9L)).thenReturn(List.of("c1"));
    when(consultantRepository.findAllByIdIn(List.of("c1"))).thenReturn(List.of(c1));
    when(matrixSynapseService.findOnlineMatrixUserIds(any())).thenReturn(Optional.empty());
    // messenger returns RC id, not consultant id
    when(messenger.findAvailableConsultants(7)).thenReturn(Set.of("rc-c1"));

    List<String> result = service.findEligibleConsultantIds(9L, 7);

    assertThat(result).containsExactly("c1");
  }

  @Test
  void findEligibleConsultantIds_Should_FallbackToActiveIds_When_MessengerMatchesNobody() {
    Consultant c1 = consultant("c1", false);
    when(consultantTopicRepository.findConsultantIdsByTopicId(10L)).thenReturn(List.of("c1"));
    when(consultantRepository.findAllByIdIn(List.of("c1"))).thenReturn(List.of(c1));
    when(matrixSynapseService.findOnlineMatrixUserIds(any())).thenReturn(Optional.empty());
    // online set is populated but no overlap with this topic's consultants
    when(messenger.findAvailableConsultants(4)).thenReturn(Set.of("someone-else"));

    List<String> result = service.findEligibleConsultantIds(10L, 4);

    // best-effort fallback: return all active topic consultants rather than empty
    assertThat(result).containsExactly("c1");
  }

  @Test
  void findEligibleConsultantIds_Should_ExcludeAbsentConsultants_From_MatrixCandidates() {
    Consultant present = consultantWithMatrix("c1", "@c1:matrix.org", false);
    Consultant absent = consultantWithMatrix("c2", "@c2:matrix.org", true);
    when(consultantTopicRepository.findConsultantIdsByTopicId(11L)).thenReturn(List.of("c1", "c2"));
    when(consultantRepository.findAllByIdIn(List.of("c1", "c2")))
        .thenReturn(List.of(present, absent));
    // only @c1 should be sent to Matrix check — @c2 is absent
    when(matrixSynapseService.findOnlineMatrixUserIds(List.of("@c1:matrix.org")))
        .thenReturn(Optional.of(Set.of("@c1:matrix.org")));

    List<String> result = service.findEligibleConsultantIds(11L, 1);

    assertThat(result).containsExactly("c1");
  }

  @Test
  void findEligibleConsultantIds_Should_SkipMatrixCheck_When_AllConsultantsHaveNoMatrixId() {
    Consultant c1 = consultant("c1", false); // matrixUserId is null
    when(consultantTopicRepository.findConsultantIdsByTopicId(12L)).thenReturn(List.of("c1"));
    when(consultantRepository.findAllByIdIn(List.of("c1"))).thenReturn(List.of(c1));
    when(messenger.findAvailableConsultants(2)).thenReturn(Set.of("c1"));

    List<String> result = service.findEligibleConsultantIds(12L, 2);

    // no Matrix user IDs → Optional.empty without calling matrixSynapseService
    verify(matrixSynapseService, never()).findOnlineMatrixUserIds(any());
    assertThat(result).containsExactly("c1");
  }

  // ── database interaction: correct IDs forwarded between repo calls ─────────

  @Test
  @SuppressWarnings("unchecked")
  void findAvailableConsultantIds_Should_ForwardTopicConsultantIds_To_ConsultantRepo() {
    List<String> topicIds = List.of("c1", "c2", "c3");
    when(consultantTopicRepository.findConsultantIdsByTopicId(20L)).thenReturn(topicIds);
    when(consultantRepository.findAllByIdIn(topicIds)).thenReturn(Collections.emptyList());

    service.findAvailableConsultantIds(20L);

    ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
    verify(consultantRepository).findAllByIdIn(captor.capture());
    assertThat(captor.getValue()).containsExactlyElementsOf(topicIds);
  }

  @Test
  @SuppressWarnings("unchecked")
  void findEligibleConsultantIds_Should_ForwardTopicConsultantIds_To_ConsultantRepo() {
    List<String> topicIds = List.of("x1", "x2");
    when(consultantTopicRepository.findConsultantIdsByTopicId(21L)).thenReturn(topicIds);
    when(consultantRepository.findAllByIdIn(topicIds)).thenReturn(Collections.emptyList());

    service.findEligibleConsultantIds(21L, 1);

    ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
    verify(consultantRepository).findAllByIdIn(captor.capture());
    assertThat(captor.getValue()).containsExactlyElementsOf(topicIds);
  }

  @Test
  void findAvailableConsultantIds_Should_HandlePartialRepoResult_When_SomeIdsNotFound() {
    // Repo returns fewer consultants than IDs (e.g. some deleted between calls)
    when(consultantTopicRepository.findConsultantIdsByTopicId(22L))
        .thenReturn(List.of("c1", "c2", "c3"));
    when(consultantRepository.findAllByIdIn(List.of("c1", "c2", "c3")))
        .thenReturn(List.of(consultant("c1", false))); // c2 and c3 missing from DB
    when(consultantActivityRegistry.filterActive(List.of("c1"), 120_000L)).thenReturn(Set.of("c1"));

    List<String> result = service.findAvailableConsultantIds(22L);

    assertThat(result).containsExactly("c1");
  }

  @Test
  void findEligibleConsultantIds_Should_HandlePartialRepoResult_When_SomeIdsNotFound() {
    when(consultantTopicRepository.findConsultantIdsByTopicId(23L)).thenReturn(List.of("c1", "c2"));
    // only c1 found in DB
    Consultant c1 = consultantWithMatrix("c1", "@c1:matrix.org", false);
    when(consultantRepository.findAllByIdIn(List.of("c1", "c2"))).thenReturn(List.of(c1));
    when(matrixSynapseService.findOnlineMatrixUserIds(List.of("@c1:matrix.org")))
        .thenReturn(Optional.of(Set.of("@c1:matrix.org")));

    List<String> result = service.findEligibleConsultantIds(23L, 5);

    assertThat(result).containsExactly("c1");
  }

  // ── concurrency: activeWindowMs is forwarded correctly ────────────────────

  @Test
  void findAvailableConsultantIds_Should_PassConfiguredWindowMs_To_Registry() {
    ReflectionTestUtils.setField(service, "activeWindowMs", 30_000L);
    when(consultantTopicRepository.findConsultantIdsByTopicId(30L)).thenReturn(List.of("c1"));
    when(consultantRepository.findAllByIdIn(List.of("c1")))
        .thenReturn(List.of(consultant("c1", false)));
    when(consultantActivityRegistry.filterActive(List.of("c1"), 30_000L)).thenReturn(Set.of("c1"));

    List<String> result = service.findAvailableConsultantIds(30L);

    assertThat(result).containsExactly("c1");
    verify(consultantActivityRegistry).filterActive(List.of("c1"), 30_000L);
  }

  // ── edge cases ─────────────────────────────────────────────────────────────

  @Test
  void findEligibleConsultantIds_Should_ExcludeBlankMatrixUserId_From_MatrixCandidates() {
    // blank matrixUserId must not be sent to Matrix (same filter as null)
    Consultant c1 = consultantWithMatrix("c1", "   ", false);
    when(consultantTopicRepository.findConsultantIdsByTopicId(40L)).thenReturn(List.of("c1"));
    when(consultantRepository.findAllByIdIn(List.of("c1"))).thenReturn(List.of(c1));
    when(messenger.findAvailableConsultants(1)).thenReturn(new HashSet<>(Set.of("c1")));

    List<String> result = service.findEligibleConsultantIds(40L, 1);

    verify(matrixSynapseService, never()).findOnlineMatrixUserIds(any());
    assertThat(result).containsExactly("c1");
  }

  @Test
  void findEligibleConsultantIds_Should_ReturnAllOnlineConsultants_When_MultipleMatchMatrix() {
    Consultant c1 = consultantWithMatrix("c1", "@c1:matrix.org", false);
    Consultant c2 = consultantWithMatrix("c2", "@c2:matrix.org", false);
    Consultant c3 = consultantWithMatrix("c3", "@c3:matrix.org", false);
    when(consultantTopicRepository.findConsultantIdsByTopicId(41L))
        .thenReturn(List.of("c1", "c2", "c3"));
    when(consultantRepository.findAllByIdIn(List.of("c1", "c2", "c3")))
        .thenReturn(List.of(c1, c2, c3));
    when(matrixSynapseService.findOnlineMatrixUserIds(
            List.of("@c1:matrix.org", "@c2:matrix.org", "@c3:matrix.org")))
        .thenReturn(Optional.of(Set.of("@c1:matrix.org", "@c3:matrix.org")));

    List<String> result = service.findEligibleConsultantIds(41L, 1);

    assertThat(result).containsExactlyInAnyOrder("c1", "c3");
  }
}
