package de.caritas.cob.userservice.api.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * ADR-022 decision 2 — the session consent pointer. These tests pin the two properties the ADR
 * argues about: the pointer is <b>overwritten</b> (never appended to), and only the help-seeker who
 * owns the room can move it.
 */
@ExtendWith(MockitoExtension.class)
class SessionConsentServiceTest {

  @Mock private SessionRepository sessionRepository;

  private SessionConsentService sessionConsentService;

  @BeforeEach
  void setUp() {
    sessionConsentService = new SessionConsentService(sessionRepository);
  }

  @Test
  void recordConsentStoresThePointerForTheOwningAdviceSeeker() {
    var session = sessionOf(42L, "asker-1", null);
    when(sessionRepository.findById(42L)).thenReturn(Optional.of(session));

    sessionConsentService.recordConsent(42L, askerWithId("asker-1"), 7L);

    var saved = ArgumentCaptor.forClass(Session.class);
    verify(sessionRepository).save(saved.capture());
    assertThat(saved.getValue().getConsentedLegalVersionId()).isEqualTo(7L);
  }

  @Test
  void recordConsentOverwritesAnEarlierPointerInsteadOfKeepingHistory() {
    // ADR-022 decision 2 rejects a consent event log: re-consent replaces the value in place.
    var session = sessionOf(42L, "asker-1", 6L);
    when(sessionRepository.findById(42L)).thenReturn(Optional.of(session));

    sessionConsentService.recordConsent(42L, askerWithId("asker-1"), 7L);

    var saved = ArgumentCaptor.forClass(Session.class);
    verify(sessionRepository).save(saved.capture());
    assertThat(saved.getValue().getConsentedLegalVersionId()).isEqualTo(7L);
  }

  @Test
  void recordConsentRejectsAForeignSession() {
    var session = sessionOf(42L, "asker-1", null);
    when(sessionRepository.findById(42L)).thenReturn(Optional.of(session));
    var stranger = askerWithId("asker-2");

    assertThatThrownBy(() -> sessionConsentService.recordConsent(42L, stranger, 7L))
        .isInstanceOf(ForbiddenException.class);
    verify(sessionRepository, never()).save(any());
  }

  @Test
  void recordConsentRejectsAnUnknownSession() {
    when(sessionRepository.findById(42L)).thenReturn(Optional.empty());
    var asker = askerWithId("asker-1");

    assertThatThrownBy(() -> sessionConsentService.recordConsent(42L, asker, 7L))
        .isInstanceOf(NotFoundException.class);
    verify(sessionRepository, never()).save(any());
  }

  @Test
  void recordConsentRejectsAMissingLegalVersion() {
    // The pointer must always point at something: a null would be indistinguishable from
    // "never consented" and would silently reopen the gate.
    var asker = askerWithId("asker-1");

    assertThatThrownBy(() -> sessionConsentService.recordConsent(42L, asker, null))
        .isInstanceOf(BadRequestException.class);
    verify(sessionRepository, never()).save(any());
  }

  private Session sessionOf(Long id, String askerId, Long consentedLegalVersionId) {
    var session = new Session();
    session.setId(id);
    session.setUser(askerWithId(askerId));
    session.setConsentedLegalVersionId(consentedLegalVersionId);
    return session;
  }

  private User askerWithId(String userId) {
    var user = new User();
    user.setUserId(userId);
    return user;
  }
}
