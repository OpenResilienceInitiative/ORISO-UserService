package de.caritas.cob.userservice.api.admin.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.SessionAdminResultDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionFilter;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SessionAdminServiceTest {

  @InjectMocks private SessionAdminService sessionAdminService;

  @Mock private SessionRepository sessionRepository;

  // ---------------------------------------------------------------------------
  // findSessions — page/perPage bounds
  // ---------------------------------------------------------------------------

  @Test
  void findSessions_Should_UseZeroBasedIndex_When_PageIsOne() {
    when(sessionRepository.findAll(any(Pageable.class))).thenReturn(emptyPage(0));
    ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);

    sessionAdminService.findSessions(1, 10, new SessionFilter());

    verify(sessionRepository).findAll(captor.capture());
    assertThat(captor.getValue().getPageNumber()).isEqualTo(0);
    assertThat(captor.getValue().getPageSize()).isEqualTo(10);
  }

  @Test
  void findSessions_Should_ClampPageIndexToZero_When_PageIsZero() {
    when(sessionRepository.findAll(any(Pageable.class))).thenReturn(emptyPage(0));
    ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);

    sessionAdminService.findSessions(0, 10, new SessionFilter());

    verify(sessionRepository).findAll(captor.capture());
    assertThat(captor.getValue().getPageNumber()).isEqualTo(0);
  }

  @Test
  void findSessions_Should_ClampPerPageToOne_When_PerPageIsZero() {
    when(sessionRepository.findAll(any(Pageable.class))).thenReturn(emptyPage(0));
    ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);

    sessionAdminService.findSessions(1, 0, new SessionFilter());

    verify(sessionRepository).findAll(captor.capture());
    assertThat(captor.getValue().getPageSize()).isEqualTo(1);
  }

  // ---------------------------------------------------------------------------
  // findSessions — provider selection
  // ---------------------------------------------------------------------------

  @Test
  void findSessions_Should_UseAllSessionsProvider_When_FilterIsEmpty() {
    when(sessionRepository.findAll(any(Pageable.class))).thenReturn(emptyPage(5));

    SessionAdminResultDTO result = sessionAdminService.findSessions(1, 10, new SessionFilter());

    verify(sessionRepository).findAll(any(Pageable.class));
    assertThat(result.getTotal()).isEqualTo(5);
  }

  @Test
  void findSessions_Should_UseAgencyProvider_When_AgencyFilterIsSet() {
    when(sessionRepository.findByAgencyId(anyLong(), any(Pageable.class))).thenReturn(emptyPage(2));
    SessionFilter filter = new SessionFilter().agency(42);

    SessionAdminResultDTO result = sessionAdminService.findSessions(1, 10, filter);

    verify(sessionRepository).findByAgencyId(anyLong(), any(Pageable.class));
    assertThat(result.getTotal()).isEqualTo(2);
  }

  @Test
  void findSessions_Should_UseAskerProvider_When_AskerFilterIsSet() {
    when(sessionRepository.findByUserUserId(anyString(), any(Pageable.class)))
        .thenReturn(emptyPage(1));
    SessionFilter filter = new SessionFilter().asker("user-abc");

    sessionAdminService.findSessions(1, 10, filter);

    verify(sessionRepository).findByUserUserId(anyString(), any(Pageable.class));
  }

  @Test
  void findSessions_Should_UseConsultantProvider_When_ConsultantFilterIsSet() {
    when(sessionRepository.findByConsultantId(anyString(), any(Pageable.class)))
        .thenReturn(emptyPage(3));
    SessionFilter filter = new SessionFilter().consultant("consultant-xyz");

    sessionAdminService.findSessions(1, 10, filter);

    verify(sessionRepository).findByConsultantId(anyString(), any(Pageable.class));
  }

  @Test
  void findSessions_Should_UseConsultingTypeProvider_When_ConsultingTypeFilterIsSet() {
    when(sessionRepository.findByConsultingTypeId(anyInt(), any(Pageable.class)))
        .thenReturn(emptyPage(0));
    SessionFilter filter = new SessionFilter().consultingType(5);

    sessionAdminService.findSessions(1, 10, filter);

    verify(sessionRepository).findByConsultingTypeId(anyInt(), any(Pageable.class));
  }

  // ---------------------------------------------------------------------------
  // findSessions — result DTO
  // ---------------------------------------------------------------------------

  @Test
  void findSessions_Should_ReturnNonNullResultDto_Always() {
    when(sessionRepository.findAll(any(Pageable.class))).thenReturn(emptyPage(0));

    SessionAdminResultDTO result = sessionAdminService.findSessions(1, 10, new SessionFilter());

    assertThat(result).isNotNull();
    assertThat(result.getLinks()).isNotNull();
    assertThat(result.getEmbedded()).isNotNull();
  }

  @Test
  void findSessions_Should_ReturnZeroTotal_When_NoSessionsFound() {
    when(sessionRepository.findAll(any(Pageable.class))).thenReturn(emptyPage(0));

    SessionAdminResultDTO result = sessionAdminService.findSessions(1, 10, new SessionFilter());

    assertThat(result.getTotal()).isEqualTo(0);
    assertThat(result.getEmbedded()).isEmpty();
  }

  private Page<Session> emptyPage(int totalElements) {
    return new PageImpl<>(Collections.emptyList(), Pageable.ofSize(10), totalElements);
  }
}
