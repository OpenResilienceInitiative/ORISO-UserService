package de.caritas.cob.userservice.api.service.addon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.AddonContextDTO.ConsultantRef;
import de.caritas.cob.userservice.api.adapters.web.dto.AddonContextDTO.SessionRef;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AddonContextServiceTest {

  private static final String ASKER_ID = "8d3c3c2e-4a8e-4b8a-9f43-6c0a0c7d1f10";
  private static final String CONSULTANT_ID = "2d18c0de-4a8e-4b8a-9f43-6c0a0c7d1f10";

  @Mock private UserRepository userRepository;
  @Mock private ConsultantRepository consultantRepository;
  @Mock private ConsultantAgencyRepository consultantAgencyRepository;
  @Mock private SessionRepository sessionRepository;

  private final UsernameTranscoder usernameTranscoder = new UsernameTranscoder();
  private AddonContextService service;
  private Consultant consultant;

  @BeforeEach
  void setUp() {
    service =
        new AddonContextService(
            userRepository,
            consultantRepository,
            consultantAgencyRepository,
            sessionRepository,
            usernameTranscoder);
    consultant = new Consultant();
    consultant.setId(CONSULTANT_ID);
    consultant.setTenantId(1L);
    consultant.setUsername(usernameTranscoder.encodeUsername("florian-test-berater"));
    consultant.setFirstName("Florian");
    consultant.setLastName("Test");
    when(userRepository.findByUserIdAndDeleteDateIsNull(anyString())).thenReturn(Optional.empty());
    when(consultantRepository.findByIdAndDeleteDateIsNull(anyString()))
        .thenReturn(Optional.empty());
  }

  @Test
  void askerWithTwoSessionsAndOneConsultant() {
    var asker = new User();
    asker.setUserId(ASKER_ID);
    asker.setTenantId(1L);
    when(userRepository.findByUserIdAndDeleteDateIsNull(ASKER_ID)).thenReturn(Optional.of(asker));
    when(sessionRepository.findByUserUserId(ASKER_ID))
        .thenReturn(
            List.of(
                session(17L, 4L, null, SessionStatus.NEW, null),
                session(16L, 0L, consultant, SessionStatus.IN_PROGRESS, 1L)));

    var context = service.contextOf(ASKER_ID, 1L);

    assertThat(context.userId()).isEqualTo(ASKER_ID);
    assertThat(context.type()).isEqualTo("asker");
    assertThat(context.agencyIds()).containsExactly(0L, 4L);
    assertThat(context.sessions())
        .containsExactly(
            new SessionRef(16L, 0L, CONSULTANT_ID, "IN_PROGRESS"),
            new SessionRef(17L, 4L, null, "NEW"));
    assertThat(context.consultants())
        .containsExactly(new ConsultantRef(CONSULTANT_ID, "florian-test-berater", "Florian Test"));
  }

  @Test
  void consultantWithAgenciesAndSessions() {
    consultant.setFirstName("");
    consultant.setLastName(" ");
    when(consultantRepository.findByIdAndDeleteDateIsNull(CONSULTANT_ID))
        .thenReturn(Optional.of(consultant));
    when(consultantAgencyRepository.findByConsultantIdAndDeleteDateIsNull(CONSULTANT_ID))
        .thenReturn(
            List.of(
                ConsultantAgency.builder().agencyId(7L).build(),
                ConsultantAgency.builder().agencyId(0L).build()));
    when(sessionRepository.findByConsultantId(eq(CONSULTANT_ID), any(Pageable.class)))
        .thenReturn(
            new PageImpl<>(
                List.of(
                    session(21L, 7L, consultant, SessionStatus.IN_PROGRESS, 1L),
                    session(16L, 0L, consultant, SessionStatus.DONE, 1L),
                    session(99L, 0L, consultant, SessionStatus.DONE, 2L))));

    var context = service.contextOf(CONSULTANT_ID, 1L);

    assertThat(context.userId()).isEqualTo(CONSULTANT_ID);
    assertThat(context.type()).isEqualTo("consultant");
    assertThat(context.agencyIds()).containsExactly(0L, 7L);
    assertThat(context.sessions())
        .containsExactly(
            new SessionRef(21L, 7L, CONSULTANT_ID, "IN_PROGRESS"),
            new SessionRef(16L, 0L, CONSULTANT_ID, "DONE"));
    assertThat(context.consultants())
        .containsExactly(
            new ConsultantRef(CONSULTANT_ID, "florian-test-berater", "florian-test-berater"));

    var pageable = ArgumentCaptor.forClass(Pageable.class);
    verify(sessionRepository).findByConsultantId(eq(CONSULTANT_ID), pageable.capture());
    assertThat(pageable.getValue().getPageSize()).isEqualTo(500);
    assertThat(pageable.getValue().getSort().getOrderFor("createDate").getDirection())
        .isEqualTo(Sort.Direction.DESC);
  }

  @Test
  void consultantContextReadsEverySessionPage() {
    when(consultantRepository.findByIdAndDeleteDateIsNull(CONSULTANT_ID))
        .thenReturn(Optional.of(consultant));
    when(consultantAgencyRepository.findByConsultantIdAndDeleteDateIsNull(CONSULTANT_ID))
        .thenReturn(List.of());
    var firstPage = PageRequest.of(0, AddonContextService.SESSION_PAGE_SIZE);
    when(sessionRepository.findByConsultantId(eq(CONSULTANT_ID), any(Pageable.class)))
        .thenReturn(
            new PageImpl<>(
                List.of(session(30L, 7L, consultant, SessionStatus.IN_PROGRESS, 1L)),
                firstPage,
                AddonContextService.SESSION_PAGE_SIZE + 1L))
        .thenReturn(
            new PageImpl<>(
                List.of(session(3L, 7L, consultant, SessionStatus.IN_PROGRESS, 1L)),
                firstPage.next(),
                AddonContextService.SESSION_PAGE_SIZE + 1L));

    var context = service.contextOf(CONSULTANT_ID, 1L);

    assertThat(context.sessions())
        .containsExactly(
            new SessionRef(30L, 7L, CONSULTANT_ID, "IN_PROGRESS"),
            new SessionRef(3L, 7L, CONSULTANT_ID, "IN_PROGRESS"));
    var pageable = ArgumentCaptor.forClass(Pageable.class);
    verify(sessionRepository, times(2)).findByConsultantId(eq(CONSULTANT_ID), pageable.capture());
    assertThat(pageable.getAllValues()).extracting(Pageable::getPageNumber).containsExactly(0, 1);
  }

  @Test
  void userOfAnotherTenantIsNotFound() {
    var asker = new User();
    asker.setUserId(ASKER_ID);
    asker.setTenantId(2L);
    when(userRepository.findByUserIdAndDeleteDateIsNull(ASKER_ID)).thenReturn(Optional.of(asker));
    when(consultantRepository.findByIdAndDeleteDateIsNull(CONSULTANT_ID))
        .thenReturn(Optional.of(consultant));

    assertThatThrownBy(() -> service.contextOf(ASKER_ID, 1L)).isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> service.contextOf(CONSULTANT_ID, 3L))
        .isInstanceOf(NotFoundException.class);
    verify(sessionRepository, never()).findByUserUserId(anyString());
    verify(sessionRepository, never()).findByConsultantId(anyString(), any(Pageable.class));
  }

  @Test
  void legacyRowsWithoutTenantBelongToTenantOne() {
    var asker = new User();
    asker.setUserId(ASKER_ID);
    when(userRepository.findByUserIdAndDeleteDateIsNull(ASKER_ID)).thenReturn(Optional.of(asker));
    when(sessionRepository.findByUserUserId(ASKER_ID)).thenReturn(List.of());

    assertThat(service.contextOf(ASKER_ID, 1L).type()).isEqualTo("asker");
    assertThatThrownBy(() -> service.contextOf(ASKER_ID, 2L)).isInstanceOf(NotFoundException.class);
  }

  @Test
  void unknownUserIsNotFound() {
    assertThatThrownBy(() -> service.contextOf("unknown", 1L))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void missingParametersAreBadRequests() {
    assertThatThrownBy(() -> service.contextOf(null, 1L)).isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> service.contextOf(ASKER_ID, null))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> service.contextOf(ASKER_ID, 0L))
        .isInstanceOf(BadRequestException.class);
  }

  private static Session session(
      Long id, Long agencyId, Consultant consultant, SessionStatus status, Long tenantId) {
    var session = new Session();
    session.setId(id);
    session.setAgencyId(agencyId);
    session.setConsultant(consultant);
    session.setStatus(status);
    session.setTenantId(tenantId);
    return session;
  }
}
