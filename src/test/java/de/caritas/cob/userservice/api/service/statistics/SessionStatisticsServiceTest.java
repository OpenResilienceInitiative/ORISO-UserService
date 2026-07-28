package de.caritas.cob.userservice.api.service.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.RegistrationType;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.statistics.model.SessionStatisticsResultDTO;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SessionStatisticsServiceTest {

  @Mock private SessionRepository sessionRepository;

  @InjectMocks private SessionStatisticsService service;

  // ─── retrieveSession via sessionId ───────────────────────────────────────

  @Test
  void retrieveSession_Should_ReturnDTO_When_SessionFoundById() {
    var session = buildSession(42L, "!room-1:matrix.example");
    when(sessionRepository.findById(42L)).thenReturn(Optional.of(session));

    SessionStatisticsResultDTO result = service.retrieveSession(42L, null);

    assertThat(result.getId()).isEqualTo(42L);
    assertThat(result.getMatrixRoomId()).isEqualTo("!room-1:matrix.example");
    assertThat(result.getAgencyId()).isEqualTo(10L);
    assertThat(result.getConsultingType()).isEqualTo(1);
    assertThat(result.getIsTeamSession()).isTrue();
    assertThat(result.getPostcode()).isEqualTo("12345");
  }

  @Test
  void retrieveSession_Should_UseSessionId_When_BothParamsProvided() {
    var session = buildSession(7L, "rc-other");
    when(sessionRepository.findById(7L)).thenReturn(Optional.of(session));

    SessionStatisticsResultDTO result = service.retrieveSession(7L, "rc-other");

    assertThat(result.getId()).isEqualTo(7L);
  }

  @Test
  void retrieveSession_Should_IncludeDates_When_SessionHasDates() {
    var session =
        Session.builder()
            .id(5L)
            .matrixRoomId("rc-5")
            .agencyId(10L)
            .consultingTypeId(2)
            .teamSession(false)
            .postcode("88888")
            .registrationType(RegistrationType.REGISTERED)
            .status(SessionStatus.IN_PROGRESS)
            .createDate(LocalDateTime.of(2026, 1, 1, 10, 0))
            .enquiryMessageDate(LocalDateTime.of(2026, 1, 2, 9, 0))
            .build();
    when(sessionRepository.findById(5L)).thenReturn(Optional.of(session));

    SessionStatisticsResultDTO result = service.retrieveSession(5L, null);

    assertThat(result.getCreateDate()).contains("2026-01-01");
    assertThat(result.getMessageDate()).contains("2026-01-02");
  }

  @Test
  void retrieveSession_Should_ThrowNotFoundException_When_SessionIdNotFound() {
    when(sessionRepository.findById(99L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.retrieveSession(99L, null))
        .isInstanceOf(NotFoundException.class);
  }

  // ─── retrieveSession via matrixRoomId ───────────────────────────────────────

  @Test
  void retrieveSession_Should_ReturnDTO_When_SessionFoundByMatrixRoomId() {
    var session = buildSession(3L, "rc-abc");
    when(sessionRepository.findByMatrixRoomId("rc-abc")).thenReturn(Optional.of(session));

    SessionStatisticsResultDTO result = service.retrieveSession(null, "rc-abc");

    assertThat(result.getId()).isEqualTo(3L);
    assertThat(result.getMatrixRoomId()).isEqualTo("rc-abc");
  }

  @Test
  void retrieveSession_Should_ThrowNotFoundException_When_MatrixRoomIdNotFound() {
    when(sessionRepository.findByMatrixRoomId("missing")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.retrieveSession(null, "missing"))
        .isInstanceOf(NotFoundException.class);
  }

  // ─── Bad request — both params null ──────────────────────────────────────

  @Test
  void retrieveSession_Should_ThrowBadRequestException_When_BothParamsNull() {
    assertThatThrownBy(() -> service.retrieveSession(null, null))
        .isInstanceOf(BadRequestException.class);
  }

  // ─── DTO field mapping ────────────────────────────────────────────────────

  @Test
  void retrieveSession_Should_MapNullDates_When_SessionHasNoDates() {
    var session =
        Session.builder()
            .id(1L)
            .matrixRoomId("rc-null-dates")
            .agencyId(5L)
            .consultingTypeId(0)
            .teamSession(false)
            .postcode("00000")
            .registrationType(RegistrationType.REGISTERED)
            .status(SessionStatus.NEW)
            .createDate(null)
            .enquiryMessageDate(null)
            .build();
    when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));

    SessionStatisticsResultDTO result = service.retrieveSession(1L, null);

    assertThat(result.getCreateDate()).isNull();
    assertThat(result.getMessageDate()).isNull();
    assertThat(result.getPostcode()).isEqualTo("00000");
  }

  @Test
  void retrieveSession_Should_MapTeamSessionFalse_When_NotTeamSession() {
    var session = buildSession(2L, "rc-2");
    session.setTeamSession(false);
    when(sessionRepository.findById(2L)).thenReturn(Optional.of(session));

    SessionStatisticsResultDTO result = service.retrieveSession(2L, null);

    assertThat(result.getIsTeamSession()).isFalse();
  }

  // ─── helpers ──────────────────────────────────────────────────────────────

  private Session buildSession(Long id, String groupId) {
    return Session.builder()
        .id(id)
        .matrixRoomId(groupId)
        .agencyId(10L)
        .consultingTypeId(1)
        .teamSession(true)
        .postcode("12345")
        .registrationType(RegistrationType.REGISTERED)
        .status(SessionStatus.IN_PROGRESS)
        .createDate(LocalDateTime.of(2026, 6, 1, 8, 0))
        .enquiryMessageDate(LocalDateTime.of(2026, 6, 1, 9, 0))
        .build();
  }
}
