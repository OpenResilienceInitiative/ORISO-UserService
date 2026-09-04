package de.caritas.cob.userservice.api.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionConsultantForConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionDTO;
import de.caritas.cob.userservice.api.helper.ConsultantDisplayNameResolver;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorMarkerRow;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * ADR-008 supervisor marker: the requesting consultant learns whether a list entry is theirs
 * because they supervise it, and who supervises it — resolved with ONE batched query per list.
 */
@ExtendWith(MockitoExtension.class)
class SessionSupervisionMarkerServiceTest {

  @InjectMocks private SessionSupervisionMarkerService service;
  @Mock private SessionSupervisorRepository sessionSupervisorRepository;
  @Mock private ConsultantDisplayNameResolver consultantDisplayNameResolver;
  @Mock private ConsultantRepository consultantRepository;

  private static Consultant consultant(String id) {
    var consultant = new Consultant();
    consultant.setId(id);
    return consultant;
  }

  private static ConsultantSessionResponseDTO entry(Long sessionId) {
    return new ConsultantSessionResponseDTO().session(new SessionDTO().id(sessionId));
  }

  private static ConsultantSessionResponseDTO entry(Long sessionId, String counsellorId) {
    return entry(sessionId).consultant(new SessionConsultantForConsultantDTO().id(counsellorId));
  }

  private static Session session(Long id, Consultant counsellor) {
    var session = new Session();
    session.setId(id);
    session.setConsultant(counsellor);
    return session;
  }

  @Test
  void enrich_Should_MarkSupervisedEntries_With_OneBatchedQuery() {
    var mine = entry(1L);
    var theirs = entry(2L);
    var unsupervised = entry(3L);
    when(sessionSupervisorRepository.findActiveMarkerRowsBySessionIdIn(anyCollection()))
        .thenReturn(
            List.of(
                new SessionSupervisorMarkerRow(1L, "me", "me-user", null, null),
                new SessionSupervisorMarkerRow(1L, "colleague", "c-user", "Colleague", null),
                new SessionSupervisorMarkerRow(2L, "colleague", "c-user", "Colleague", null)));
    when(consultantDisplayNameResolver.resolveInternalDisplayName(any(), any(), any()))
        .thenAnswer(inv -> "name-of-" + inv.getArgument(2));

    var result =
        service.enrich(new ArrayList<>(List.of(mine, theirs, unsupervised)), consultant("me"));

    assertThat(result).containsExactly(mine, theirs, unsupervised);
    ArgumentCaptor<Collection<Long>> ids = ArgumentCaptor.forClass(Collection.class);
    verify(sessionSupervisorRepository, times(1)).findActiveMarkerRowsBySessionIdIn(ids.capture());
    assertThat(ids.getValue()).containsExactlyInAnyOrder(1L, 2L, 3L);

    var mineMarker = mine.getSession().getSupervision();
    assertThat(mineMarker.getSupervisedByMe()).isTrue();
    assertThat(mineMarker.getSupervisorConsultantIds()).containsExactly("me", "colleague");
    assertThat(mineMarker.getSupervisorDisplayNames())
        .containsExactly("name-of-me-user", "name-of-c-user");

    var theirsMarker = theirs.getSession().getSupervision();
    assertThat(theirsMarker.getSupervisedByMe()).isFalse();
    assertThat(theirsMarker.getSupervisorConsultantIds()).containsExactly("colleague");

    var noneMarker = unsupervised.getSession().getSupervision();
    assertThat(noneMarker.getSupervisedByMe()).isFalse();
    assertThat(noneMarker.getSupervisorConsultantIds()).isEmpty();
    assertThat(noneMarker.getSupervisorDisplayNames()).isEmpty();
  }

  @Test
  void enrich_Should_ResolveDisplayNames_Through_TheInternalNameRule() {
    var entry = entry(7L);
    when(sessionSupervisorRepository.findActiveMarkerRowsBySessionIdIn(anyCollection()))
        .thenReturn(
            List.of(new SessionSupervisorMarkerRow(7L, "sup", "enc.abc", "Public", "Internal")));
    when(consultantDisplayNameResolver.resolveInternalDisplayName("Internal", "Public", "enc.abc"))
        .thenReturn("Internal");

    service.enrich(List.of(entry), consultant("me"));

    assertThat(entry.getSession().getSupervision().getSupervisorDisplayNames())
        .containsExactly("Internal");
  }

  @Test
  void enrich_Should_CarryTheCounsellorDisplayName_With_OneBatchedQuery() {
    var ownedByAnna = entry(1L, "anna");
    var alsoAnna = entry(2L, "anna");
    var ownedByBob = entry(3L, "bob");
    var unassigned = entry(4L);
    when(sessionSupervisorRepository.findActiveMarkerRowsBySessionIdIn(anyCollection()))
        .thenReturn(List.of());
    var anna = consultant("anna");
    var bob = consultant("bob");
    when(consultantRepository.findAllByIdIn(anyList())).thenReturn(List.of(anna, bob));
    when(consultantDisplayNameResolver.resolveInternalDisplayName(anna)).thenReturn("Anna (int)");
    when(consultantDisplayNameResolver.resolveInternalDisplayName(bob)).thenReturn("Bob (int)");

    service.enrich(List.of(ownedByAnna, alsoAnna, ownedByBob, unassigned), consultant("me"));

    ArgumentCaptor<List<String>> ids = ArgumentCaptor.forClass(List.class);
    verify(consultantRepository, times(1)).findAllByIdIn(ids.capture());
    assertThat(ids.getValue()).containsExactlyInAnyOrder("anna", "bob");
    assertThat(ownedByAnna.getSession().getSupervision().getCounsellorDisplayName())
        .isEqualTo("Anna (int)");
    assertThat(alsoAnna.getSession().getSupervision().getCounsellorDisplayName())
        .isEqualTo("Anna (int)");
    assertThat(ownedByBob.getSession().getSupervision().getCounsellorDisplayName())
        .isEqualTo("Bob (int)");
    assertThat(unassigned.getSession().getSupervision()).isNotNull();
    assertThat(unassigned.getSession().getSupervision().getCounsellorDisplayName()).isNull();
  }

  @Test
  void enrich_Should_NotLoadCounsellors_When_NoEntryHasOne() {
    var entry = entry(1L);
    when(sessionSupervisorRepository.findActiveMarkerRowsBySessionIdIn(anyCollection()))
        .thenReturn(List.of());

    service.enrich(List.of(entry), consultant("me"));

    verify(consultantRepository, never()).findAllByIdIn(any());
    assertThat(entry.getSession().getSupervision().getCounsellorDisplayName()).isNull();
  }

  @Test
  void enrich_Should_NotQuery_When_ListIsEmptyOrRequesterMissing() {
    assertThat(service.enrich(List.of(), consultant("me"))).isEmpty();
    var entries = List.of(entry(1L));
    assertThat(service.enrich(entries, null)).isSameAs(entries);
    assertThat(service.enrich(null, consultant("me"))).isNull();

    verify(sessionSupervisorRepository, never()).findActiveMarkerRowsBySessionIdIn(any());
    assertThat(entries.get(0).getSession().getSupervision()).isNull();
  }

  @Test
  void enrich_Should_SkipEntriesWithoutSession_And_NotQueryWhenNoIds() {
    var chatOnly = new ConsultantSessionResponseDTO();
    var noId = new ConsultantSessionResponseDTO().session(new SessionDTO());

    service.enrich(List.of(chatOnly, noId), consultant("me"));

    verify(sessionSupervisorRepository, never()).findActiveMarkerRowsBySessionIdIn(any());
    assertThat(noId.getSession().getSupervision()).isNull();
  }

  @Test
  void buildFor_Should_ResolveASingleSession_With_TheSameQuery() {
    when(sessionSupervisorRepository.findActiveMarkerRowsBySessionIdIn(Set.of(5L)))
        .thenReturn(List.of(new SessionSupervisorMarkerRow(5L, "me", "me-user", null, null)));
    when(consultantDisplayNameResolver.resolveInternalDisplayName(any(), any(), any()))
        .thenReturn("Me");
    var anna = consultant("anna");
    when(consultantDisplayNameResolver.resolveInternalDisplayName(anna)).thenReturn("Anna (int)");

    var marker = service.buildFor(session(5L, anna), consultant("me"));

    assertThat(marker.getSupervisedByMe()).isTrue();
    assertThat(marker.getSupervisorConsultantIds()).containsExactly("me");
    assertThat(marker.getSupervisorDisplayNames()).containsExactly("Me");
    assertThat(marker.getCounsellorDisplayName()).isEqualTo("Anna (int)");
    verify(consultantRepository, never()).findAllByIdIn(any());
  }

  @Test
  void buildFor_Should_LeaveCounsellorNameNull_When_SessionHasNoConsultant() {
    when(sessionSupervisorRepository.findActiveMarkerRowsBySessionIdIn(Set.of(6L)))
        .thenReturn(List.of());
    when(consultantDisplayNameResolver.resolveInternalDisplayName((Consultant) null))
        .thenReturn(null);

    var marker = service.buildFor(session(6L, null), consultant("me"));

    assertThat(marker.getSupervisedByMe()).isFalse();
    assertThat(marker.getCounsellorDisplayName()).isNull();
  }

  @Test
  void buildFor_Should_ReturnNull_When_SessionOrIdOrRequesterMissing() {
    assertThat(service.buildFor(null, consultant("me"))).isNull();
    assertThat(service.buildFor(session(null, null), consultant("me"))).isNull();
    assertThat(service.buildFor(session(5L, null), null)).isNull();
    verify(sessionSupervisorRepository, never()).findActiveMarkerRowsBySessionIdIn(any());
  }
}
